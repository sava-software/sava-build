package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class MutationToolchainRecordTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `schema one round trips a licensed portable identity`() {
    val toolA = genericJar("cache-a/pitest.jar")
    val toolB = baseJar("cache-a/arcmutate-base.jar")
    val projectLicence = licence("project/arcmutate-licence.txt", "15/08/2027")
    val record = MutationToolchainRecord.capture(
      pitestVersion = "1.25.9",
      junitPluginVersion = "1.2.3",
      toolClasspath = listOf(toolA, toolB),
      arcMutateBaseVersion = "1.7.2",
      arcMutateEnabled = true,
      reportDirectory = projectLicence.parentFile.resolve("build/reports/pitest/encoding"),
      projectBaseDirectory = projectLicence.parentFile,
      lookupStartDirectory = projectLicence.parentFile,
      observationDate = LocalDate.parse("2027-08-15"),
    )

    val toolHash = MutationToolchainRecord.orderedArtifactContentSha256(listOf(toolA, toolB))
    val licenceHash = PitestEvidence.sha256(projectLicence)
    val expected =
      "schema\t1\n" +
          "pitest\t1.25.9\n" +
          "junitPlugin\t1.2.3\n" +
          "toolClasspathSha256\t$toolHash\n" +
          "arcMutateBase\t1.7.2\n" +
          "arcMutateLicenceSha256\t$licenceHash\n" +
          "arcMutateLicenceExpires\t2027-08-15\n"

    assertEquals(expected, record.render())
    assertEquals(record, MutationToolchainRecord.parse(expected))
    assertEquals(PitestEvidence.sha256(expected), record.identitySha256)
  }

  @Test
  fun `artifact identity is path independent but order and content sensitive`() {
    val leftA = file("left/cache/pitest.jar", "pitest")
    val leftB = file("left/cache/plugin.jar", "plugin")
    val leftDir = file("left/classes/META-INF/services/plugin", "implementation").parentFile.parentFile.parentFile
    val rightA = file("right/elsewhere/pitest.jar", "pitest")
    val rightB = file("right/elsewhere/plugin.jar", "plugin")
    val rightDir = file("right/classes/META-INF/services/plugin", "implementation").parentFile.parentFile.parentFile

    val left = MutationToolchainRecord.orderedArtifactContentSha256(listOf(leftA, leftB, leftDir))
    val relocated = MutationToolchainRecord.orderedArtifactContentSha256(listOf(rightA, rightB, rightDir))
    assertEquals(left, relocated, "absolute cache/check-out paths entered the committed identity")

    val reversed = MutationToolchainRecord.orderedArtifactContentSha256(listOf(rightB, rightA, rightDir))
    assertNotEquals(left, reversed, "tool classpath order was erased")

    rightB.writeText("changed plugin")
    val changed = MutationToolchainRecord.orderedArtifactContentSha256(listOf(rightA, rightB, rightDir))
    assertNotEquals(left, changed, "artifact bytes were not bound")
  }

  @Test
  fun `missing classpath entries are ignored but an effective artifact is required`() {
    assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.orderedArtifactContentSha256(emptyList())
    }
    val artifact = file("effective/pitest.jar", "pitest")
    assertEquals(
      MutationToolchainRecord.orderedArtifactContentSha256(listOf(artifact)),
      MutationToolchainRecord.orderedArtifactContentSha256(
        listOf(tempDir.resolve("missing-before"), artifact, tempDir.resolve("missing-after"))),
    )
    assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.orderedArtifactContentSha256(listOf(tempDir.resolve("missing.jar")))
    }
  }

  @Test
  fun `effective ancestry resolution prefers project over root and supports root only`() {
    val rootLicence = licence("ancestry/arcmutate-licence.txt", "15/08/2027")
    val project = tempDir.resolve("ancestry/module").apply { mkdirs() }
    val projectLicence = licence("ancestry/module/arcmutate-licence.txt", "16/08/2027")
    val report = project.resolve("build/reports/pitest/encoding")

    val nearest = MutationToolchainRecord.resolveEffectiveArcMutateLicence(report, project, project)
    assertEquals(PitestEvidence.sha256(projectLicence), nearest?.sha256)
    assertEquals(LocalDate.parse("2027-08-16"), nearest?.expires)

    assertTrue(projectLicence.delete())
    val rooted = MutationToolchainRecord.resolveEffectiveArcMutateLicence(report, project, project)
    assertEquals(PitestEvidence.sha256(rootLicence), rooted?.sha256)
    assertEquals(LocalDate.parse("2027-08-15"), rooted?.expires)

    assertTrue(rootLicence.delete())
    assertNull(MutationToolchainRecord.resolveEffectiveArcMutateLicence(report, project, project))
  }

  @Test
  fun `certificate expiry is strict normalized and OSSS grace matches ArcMutate`() {
    val tool = baseJar("deps/arcmutate-base.jar")
    val leapDay = licence("valid/arcmutate-licence.txt", "29/02/2028")
    val record = MutationToolchainRecord.capture(
      pitestVersion = "1.25.9",
      junitPluginVersion = "1.2.3",
      toolClasspath = listOf(tool),
      arcMutateBaseVersion = "1.7.2",
      arcMutateEnabled = true,
      reportDirectory = leapDay.parentFile.resolve("build/reports/pitest/encoding"),
      projectBaseDirectory = leapDay.parentFile,
      lookupStartDirectory = leapDay.parentFile,
      observationDate = LocalDate.parse("2028-03-29"),
    )
    assertEquals(LocalDate.parse("2028-02-29"), record.arcMutateLicenceExpires)

    val expired = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        pitestVersion = "1.25.9",
        junitPluginVersion = "1.2.3",
        toolClasspath = listOf(tool),
        arcMutateBaseVersion = "1.7.2",
        arcMutateEnabled = true,
        reportDirectory = leapDay.parentFile.resolve("build/reports/pitest/encoding"),
        projectBaseDirectory = leapDay.parentFile,
        lookupStartDirectory = leapDay.parentFile,
        observationDate = LocalDate.parse("2028-03-30"),
      )
    }
    assertTrue(expired.message.orEmpty().contains("grace period ended on 2028-03-29"))

    listOf("29/02/2027", "3/08/2027", "2027-08-15", "15/13/2027").forEachIndexed { index, date ->
      val invalid = licence("invalid-$index/arcmutate-licence.txt", date)
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        MutationToolchainRecord.resolveEffectiveArcMutateLicence(
          invalid.parentFile.resolve("build/reports/pitest/encoding"),
          invalid.parentFile,
          invalid.parentFile,
        )
      }
      assertTrue(refusal.message.orEmpty().contains("expires"), "wrong refusal for '$date': $refusal")
    }
  }

  @Test
  fun `capture accepts the last OSSS grace day then refuses and records a truly open toolchain`() {
    val tool = baseJar("deps/arcmutate-base.jar")
    val expired = licence("licensed/arcmutate-licence.txt", "15/08/2027")
    val lastGraceDay = MutationToolchainRecord.capture(
      pitestVersion = "1.25.9",
      junitPluginVersion = "1.2.3",
      toolClasspath = listOf(tool),
      arcMutateBaseVersion = "1.7.2",
      arcMutateEnabled = true,
      reportDirectory = expired.parentFile.resolve("build/reports/pitest/encoding"),
      projectBaseDirectory = expired.parentFile,
      lookupStartDirectory = expired.parentFile,
      observationDate = LocalDate.parse("2027-09-15"),
    )
    assertEquals(LocalDate.parse("2027-08-15"), lastGraceDay.arcMutateLicenceExpires)

    val refusal = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        pitestVersion = "1.25.9",
        junitPluginVersion = "1.2.3",
        toolClasspath = listOf(tool),
        arcMutateBaseVersion = "1.7.2",
        arcMutateEnabled = true,
        reportDirectory = expired.parentFile.resolve("build/reports/pitest/encoding"),
        projectBaseDirectory = expired.parentFile,
        lookupStartDirectory = expired.parentFile,
        observationDate = LocalDate.parse("2027-09-16"),
      )
    }
    assertTrue(refusal.message.orEmpty().contains("observation date 2027-09-16"))

    val open = MutationToolchainRecord.capture(
      pitestVersion = "1.25.9",
      junitPluginVersion = "1.2.3",
      toolClasspath = listOf(genericJar("open/pitest.jar")),
      arcMutateBaseVersion = "1.7.2",
      arcMutateEnabled = false,
      reportDirectory = tempDir.resolve("open/build/reports/pitest/encoding"),
      projectBaseDirectory = tempDir.resolve("open"),
      lookupStartDirectory = tempDir.resolve("open"),
      observationDate = LocalDate.parse("2030-01-01"),
    )
    assertNull(open.arcMutateBaseVersion)
    assertNull(open.arcMutateLicenceSha256)
    assertNull(open.arcMutateLicenceExpires)
    assertTrue(open.render().contains("arcMutateBase\tabsent\n"))
    assertEquals(open, MutationToolchainRecord.parse(open.render()))
  }

  @Test
  fun `effective resolver refuses higher precedence report and dot-pitest licences`() {
    val project = tempDir.resolve("shadowed").apply { mkdirs() }
    licence("shadowed/arcmutate-licence.txt", "15/08/2027")
    val report = project.resolve("build/reports/pitest/encoding")
    licence(
      "shadowed/build/reports/pitest/encoding/arcmutate-licences/report-licence.txt",
      "16/08/2027",
    )
    licence("shadowed/.pitest/team/licence.txt", "17/08/2027")

    val refusal = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.resolveEffectiveArcMutateLicence(report, project, project)
    }

    assertTrue(refusal.message.orEmpty().contains("higher-precedence licence candidate"), refusal.message)
    assertTrue(refusal.message.orEmpty().contains("report-licence.txt"), refusal.message)
    assertTrue(refusal.message.orEmpty().contains(".pitest"), refusal.message)
  }

  @Test
  fun `effective resolver refuses a symlinked higher precedence store`() {
    val project = tempDir.resolve("linked-store/project").apply { mkdirs() }
    licence("linked-store/project/arcmutate-licence.txt", "15/08/2027")
    val target = tempDir.resolve("linked-store/external").apply { mkdirs() }
    licence("linked-store/external/team-licence.txt", "16/08/2027")
    Files.createSymbolicLink(project.resolve(".pitest").toPath(), target.toPath())

    val refusal = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.resolveEffectiveArcMutateLicence(
        project.resolve("build/reports/pitest/encoding"), project, project)
    }

    assertTrue(refusal.message.orEmpty().contains("not a real directory"), refusal.message)
  }

  @Test
  fun `capture refuses configured versus effective base drift and unaudited versions`() {
    val openTool = genericJar("mismatch/pitest.jar")
    val project = tempDir.resolve("mismatch").apply { mkdirs() }
    licence("mismatch/arcmutate-licence.txt", "15/08/2027")
    val missingBase = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(openTool), "1.7.2", true,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(missingBase.message.orEmpty().contains("disagrees with the effective PIT tool classpath"))

    val previousBase = baseJar("mismatch/arcmutate-1.7.1.jar", "1.7.1")
    val previousUnsupported = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(previousBase), "1.7.1", true,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(
      previousUnsupported.message.orEmpty().contains("supports base 1.7.2"),
      previousUnsupported.message,
    )

    val unexpectedBase = baseJar("mismatch/arcmutate-1.8.jar", "1.8.0")
    val unsupported = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(unexpectedBase), "1.8.0", true,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(unsupported.message.orEmpty().contains("supports base 1.7.2"), unsupported.message)

    val hiddenBase = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(baseJar("mismatch/hidden-base.jar")), "1.7.2", false,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(hiddenBase.message.orEmpty().contains("disagrees with the effective PIT tool classpath"))
  }

  @Test
  fun `capture refuses markerless and corrupt ArcMutate artifacts`() {
    val project = tempDir.resolve("unidentified-arcmutate").apply { mkdirs() }
    licence("unidentified-arcmutate/arcmutate-licence.txt", "15/08/2027")
    val markerlessClass = baseJar(
      "unidentified-arcmutate/markerless.jar",
      includeMavenMarker = false,
    )
    val markerlessService = toolJar(
      "unidentified-arcmutate/service-only.jar",
      serviceImplementation = "com.groupcdg.arcmutate.history.ArcmutateHistoryFactory",
    )
    val corruptMarker = baseJar(
      "unidentified-arcmutate/corrupt-marker.jar",
      markerVersion = null,
    )
    val markerOnly = toolJar(
      "unidentified-arcmutate/marker-only.jar",
      markerPath = "META-INF/maven/com.arcmutate/base/pom.properties",
      markerVersion = "1.7.2",
    )
    val realPit = toolJar(
      "unidentified-arcmutate/pitest-command-line.jar",
      markerPath = "META-INF/maven/org.pitest/pitest-command-line/pom.properties",
      markerVersion = "1.25.9",
      sentinelPath = "org/pitest/mutationtest/commandline/MutationCoverageReport.class",
    )

    listOf(markerlessClass, markerlessService).forEach { artifact ->
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        MutationToolchainRecord.capture(
          "1.25.9", "1.2.3", listOf(artifact), "1.7.2", false,
          project.resolve("build/reports/pitest/encoding"), project, project,
          LocalDate.parse("2027-08-15"),
        )
      }
      assertTrue(refusal.message.orEmpty().contains("markerless ArcMutate"), refusal.message)
    }

    val corrupt = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(corruptMarker), "1.7.2", false,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(corrupt.message.orEmpty().contains("has no version"), corrupt.message)

    val spoofed = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.3", listOf(realPit, markerOnly), "1.7.2", true,
        project.resolve("build/reports/pitest/encoding"), project, project,
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(spoofed.message.orEmpty().contains("marker but no ArcMutate code/services"), spoofed.message)
  }

  @Test
  fun `capture validates recognizable PIT and JUnit artifacts but permits intentional fakes`() {
    val project = tempDir.resolve("effective-versions").apply { mkdirs() }
    val pit = toolJar(
      "effective-versions/pitest-command-line.jar",
      markerPath = "META-INF/maven/org.pitest/pitest-command-line/pom.properties",
      markerVersion = "1.25.9",
      sentinelPath = "org/pitest/mutationtest/commandline/MutationCoverageReport.class",
    )
    val junit = toolJar(
      "effective-versions/pitest-junit5-plugin.jar",
      markerPath = "META-INF/maven/org.pitest/pitest-junit5-plugin/pom.properties",
      markerVersion = "1.2.3",
      sentinelPath = "org/pitest/junit5/JUnit5TestPluginFactory.class",
    )
    val commonArguments = arrayOf(
      project.resolve("build/reports/pitest/encoding"), project, project,
    )

    val identified = MutationToolchainRecord.capture(
      "1.25.9", "1.2.3", listOf(pit, junit), "1.7.2", false,
      commonArguments[0], commonArguments[1], commonArguments[2],
      LocalDate.parse("2027-08-15"),
    )
    assertEquals("1.25.9", identified.pitestVersion)
    assertEquals("1.2.3", identified.junitPluginVersion)

    val wrongPit = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.8", "1.2.3", listOf(pit, junit), "1.7.2", false,
        commonArguments[0], commonArguments[1], commonArguments[2],
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(wrongPit.message.orEmpty().contains("effective artifact version 1.25.9"), wrongPit.message)

    val wrongJunit = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.capture(
        "1.25.9", "1.2.2", listOf(pit, junit), "1.7.2", false,
        commonArguments[0], commonArguments[1], commonArguments[2],
        LocalDate.parse("2027-08-15"),
      )
    }
    assertTrue(wrongJunit.message.orEmpty().contains("effective artifact version 1.2.3"), wrongJunit.message)

    listOf(
      toolJar(
        "effective-versions/markerless-pit.jar",
        sentinelPath = "org/pitest/mutationtest/commandline/MutationCoverageReport.class",
      ),
      toolJar(
        "effective-versions/markerless-junit.jar",
        sentinelPath = "org/pitest/junit5/JUnit5TestPluginFactory.class",
      ),
    ).forEach { markerless ->
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        MutationToolchainRecord.capture(
          "1.25.9", "1.2.3", listOf(markerless), "1.7.2", false,
          commonArguments[0], commonArguments[1], commonArguments[2],
          LocalDate.parse("2027-08-15"),
        )
      }
      assertTrue(refusal.message.orEmpty().contains("missing or unreadable"), refusal.message)
    }

    val fake = MutationToolchainRecord.capture(
      "fixture-pit", "fixture-junit", listOf(genericJar("effective-versions/fake.jar")),
      "1.7.2", false,
      commonArguments[0], commonArguments[1], commonArguments[2],
      LocalDate.parse("2027-08-15"),
    )
    assertEquals("fixture-pit", fake.pitestVersion)
    assertEquals("fixture-junit", fake.junitPluginVersion)
  }

  @Test
  fun `effective resolver refuses a multi-certificate file`() {
    val project = tempDir.resolve("multiple").apply { mkdirs() }
    val certificate = licence("multiple/arcmutate-licence.txt", "15/08/2027")
    certificate.appendText("\nexpires=16/08/2027\nsignature=second\n")

    val refusal = assertThrows(IllegalArgumentException::class.java) {
      MutationToolchainRecord.resolveEffectiveArcMutateLicence(
        project.resolve("build/reports/pitest/encoding"), project, project)
    }
    assertTrue(refusal.message.orEmpty().contains("exactly one signed certificate"), refusal.message)
  }

  @Test
  fun `schema parser rejects unknown duplicate missing malformed and incoherent fields`() {
    val hash = "0".repeat(64)
    val valid =
      "schema\t1\n" +
          "pitest\t1.25.9\n" +
          "junitPlugin\t1.2.3\n" +
          "toolClasspathSha256\t$hash\n" +
          "arcMutateBase\tabsent\n" +
          "arcMutateLicenceSha256\tabsent\n" +
          "arcMutateLicenceExpires\tabsent\n"

    val invalidRecords = listOf(
      valid.replace("schema\t1", "schema\t2"),
      valid + "unknown\tvalue\n",
      valid + "pitest\t1.25.9\n",
      valid.replace("junitPlugin\t1.2.3\n", ""),
      valid.replace("toolClasspathSha256\t$hash", "toolClasspathSha256\tNOT-A-HASH"),
      valid.replace("pitest\t1.25.9", "pitest\t1.25.9\textra"),
      valid.replace("arcMutateBase\tabsent", "arcMutateBase\t1.7.2"),
      valid.replace("arcMutateLicenceExpires\tabsent", "arcMutateLicenceExpires\t2027-8-15"),
      valid.replace("junitPlugin\t1.2.3\n", "junitPlugin\t1.2.3\n\n"),
      valid.replace("\n", "\r\n"),
    )
    invalidRecords.forEachIndexed { index, invalid ->
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        MutationToolchainRecord.parse(invalid)
      }
      assertTrue(refusal.message.orEmpty().isNotEmpty(), "invalid record $index had no diagnostic")
    }
  }

  @Test
  fun `identity changes with every population defining field`() {
    val hash = "0".repeat(64)
    val licenceHash = "1".repeat(64)
    val original = MutationToolchainRecord(
      pitestVersion = "1.25.9",
      junitPluginVersion = "1.2.3",
      toolClasspathSha256 = hash,
      arcMutateBaseVersion = "1.7.2",
      arcMutateLicenceSha256 = licenceHash,
      arcMutateLicenceExpires = LocalDate.parse("2027-08-15"),
    )
    val variants = listOf(
      original.copy(pitestVersion = "1.26.0"),
      original.copy(junitPluginVersion = "1.2.4"),
      original.copy(toolClasspathSha256 = "2".repeat(64)),
      original.copy(arcMutateBaseVersion = "1.8.0"),
      original.copy(arcMutateLicenceSha256 = "3".repeat(64)),
      original.copy(arcMutateLicenceExpires = LocalDate.parse("2028-08-15")),
    )
    variants.forEach { variant -> assertNotEquals(original.identitySha256, variant.identitySha256) }
  }

  private fun licence(
    relative: String,
    expires: String,
    type: String = "OSSS",
  ): File = file(
    relative,
    "# ArcMutate OSS certificate\n" +
        "expires=$expires\n" +
        "keyVersion=1\n" +
        "packages=software.sava.*\n" +
        "signature=signed-value\n" +
        "type=$type\n",
  )

  private fun baseJar(
    relative: String,
    version: String = MutationToolchainRecord.SUPPORTED_ARCMUTATE_BASE_VERSION,
    includeMavenMarker: Boolean = true,
    markerVersion: String? = version,
  ): File = toolJar(
    relative = relative,
    markerPath = if (includeMavenMarker) {
      "META-INF/maven/com.arcmutate/base/pom.properties"
    } else null,
    markerVersion = markerVersion,
    sentinelPath = "com/arcmutate/BaseMarker.class",
  )

  private fun genericJar(relative: String): File = toolJar(
    relative = relative,
    sentinelPath = "fixture/Tool.class",
  )

  private fun toolJar(
    relative: String,
    markerPath: String? = null,
    markerVersion: String? = null,
    sentinelPath: String? = null,
    serviceImplementation: String? = null,
  ): File = tempDir.resolve(relative).also { jarFile ->
    jarFile.parentFile.mkdirs()
    JarOutputStream(jarFile.outputStream()).use { jar ->
      markerPath?.let { path ->
        jar.putNextEntry(JarEntry(path))
        val versionRow = markerVersion?.let { "version=$it\n" }.orEmpty()
        jar.write(versionRow.toByteArray())
        jar.closeEntry()
      }
      sentinelPath?.let { path ->
        jar.putNextEntry(JarEntry(path))
        jar.write(byteArrayOf(1, 2, 3))
        jar.closeEntry()
      }
      serviceImplementation?.let { implementation ->
        jar.putNextEntry(JarEntry("META-INF/services/org.pitest.mutationtest.HistoryFactory"))
        jar.write("$implementation\n".toByteArray())
        jar.closeEntry()
      }
    }
  }

  private fun file(relative: String, content: String): File = tempDir.resolve(relative).also {
    it.parentFile.mkdirs()
    it.writeText(content)
  }
}
