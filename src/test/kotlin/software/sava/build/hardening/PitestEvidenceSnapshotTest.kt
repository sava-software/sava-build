package software.sava.build.hardening

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.task.HardeningCommandLines
import java.io.File

class PitestEvidenceSnapshotTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `capture preserves the evidence fingerprint contract byte for byte`() {
    val source = file("src/main/java/example/Codec.java", "class Codec {}")
    val classes = file("build/mutation-classes/example/Codec.class", "class-bytes")
    val runtimeA = file("build/resources/main/settings.txt", "settings")
    val runtimeB = file("deps/runtime.jar", "runtime")
    val toolA = file("deps/pitest.jar", "pitest")
    val toolB = file("deps/plugin.jar", "plugin")
    val pluginCode = file("plugin/sava-build.jar", "sava-build")

    val evidence = PitestEvidenceSnapshot.capture(input(
      sourceFiles = listOf(source),
      classFiles = listOf(classes),
      runtimeClasspath = listOf(runtimeB, runtimeA),
      toolClasspath = listOf(toolB, toolA),
      pluginCode = pluginCode,
    ))

    val configurationText = buildString {
      appendLine("targetClasses=example.Codec,example.Parser")
      appendLine("excludedClasses=example.CodecFuzz,example.Generated")
      appendLine("targetTests=example.*Test*")
      appendLine("mutators=STRONGER")
      appendLine("threads=4")
      appendLine("timeoutFactor=1.25")
      appendLine("timeoutConst=4000")
      appendLine("mutationBytecodeRelease=21")
      appendLine("recompileExcludes=Integ.java,Scratch.java")
      appendLine(
        "classpathOrderSha256=" + PitestEvidence.sha256(
          listOf(runtimeB, runtimeA).joinToString("\n") { it.absoluteFile.normalize().path }
        )
      )
      appendLine(
        "toolClasspathOrderSha256=" + PitestEvidence.sha256(
          listOf(toolB, toolA).joinToString("\n") { it.absoluteFile.normalize().path }
        )
      )
    }

    assertEquals("encoding", evidence.suite)
    assertEquals("run-123", evidence.invocationId)
    assertEquals("1.25.8", evidence.pitestVersion)
    assertEquals("1.2.3", evidence.junitPluginVersion)
    assertEquals("25", evidence.javaVersion)
    assertEquals(PitestEvidence.fingerprintTree(pluginCode), evidence.pluginSha256)
    assertEquals(PitestEvidence.fingerprint(tempDir, listOf(source)), evidence.sourceSha256)
    assertEquals(PitestEvidence.fingerprint(tempDir, listOf(classes)), evidence.classesSha256)
    assertEquals(
      PitestEvidence.fingerprint(tempDir, listOf(runtimeB, runtimeA)),
      evidence.classpathSha256,
    )
    assertEquals(
      PitestEvidence.fingerprint(tempDir, listOf(toolB, toolA)),
      evidence.toolClasspathSha256,
    )
    assertEquals(PitestEvidence.sha256(configurationText), evidence.configurationSha256)
    assertEquals("report-hash", evidence.reportSha256)
    assertEquals(PitestEvidence.FULL_SCOPE, evidence.scope)
    assertEquals(false, evidence.historyAssisted)
    assertEquals(PitestEvidence.CURRENT_IDENTITY_SCHEMA, evidence.identitySchema)
  }

  @Test
  fun `classpath order changes configuration identity but not content identity`() {
    val first = file("deps/first.jar", "first")
    val second = file("deps/second.jar", "second")
    val pluginCode = file("plugin/classes/Marker.class", "plugin")
    val ordered = PitestEvidenceSnapshot.capture(input(
      runtimeClasspath = listOf(first, second),
      toolClasspath = listOf(second, first),
      pluginCode = pluginCode.parentFile,
    ))
    val reversed = PitestEvidenceSnapshot.capture(input(
      runtimeClasspath = listOf(second, first),
      toolClasspath = listOf(first, second),
      pluginCode = pluginCode.parentFile,
    ))

    assertEquals(ordered.classpathSha256, reversed.classpathSha256)
    assertEquals(ordered.toolClasspathSha256, reversed.toolClasspathSha256)
    assertNotEquals(ordered.configurationSha256, reversed.configurationSha256)
  }

  @Test
  fun `minion JVM argument order changes configuration identity`() {
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val input = input(pluginCode = pluginCode)
    val default = PitestEvidenceSnapshot.capture(input)
    val explicitEmpty = PitestEvidenceSnapshot.capture(input, emptyList())
    val first = PitestEvidenceSnapshot.capture(
      input,
      listOf("-Xms256m", "-Xmx1g"),
    )
    val reversed = PitestEvidenceSnapshot.capture(
      input,
      listOf("-Xmx1g", "-Xms256m"),
    )

    assertEquals(default.configurationSha256, explicitEmpty.configurationSha256)
    assertNotEquals(default.configurationSha256, first.configurationSha256)
    assertNotEquals(first.configurationSha256, reversed.configurationSha256)
  }

  @Test
  fun `internal capture uses the application-time plugin identity scalar`() {
    val pluginCode = file("plugin/sava-build.jar", "application-time bytes")
    val expected = PitestEvidence.sha256(pluginCode)
    val input = input(pluginCode = pluginCode)
    pluginCode.writeText("later bytes")

    val evidence = PitestEvidenceSnapshot.capture(input, emptyList(), expected)

    assertEquals(expected, evidence.pluginSha256)
    assertNotEquals(PitestEvidence.sha256(pluginCode), evidence.pluginSha256)
  }

  @Test
  fun `isolated mutation units change configuration identity without changing defaults`() {
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val input = input(pluginCode = pluginCode)
    val pluginSha256 = PitestEvidence.sha256(pluginCode)
    val legacy = PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256)
    val explicitZero =
      PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 0)
    val isolated =
      PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 1)

    assertEquals(legacy.configurationSha256, explicitZero.configurationSha256)
    assertNotEquals(legacy.configurationSha256, isolated.configurationSha256)
  }

  @Test
  fun `non-default PIT verbosity is opaque evidence input while default stays compatible`() {
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val input = input(pluginCode = pluginCode)
    val pluginSha256 = PitestEvidence.sha256(pluginCode)
    val legacy = PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 0)
    val explicitDefault = PitestEvidenceSnapshot.capture(
      input,
      emptyList(),
      pluginSha256,
      0,
      HardeningCommandLines.PitestVerbosity.DEFAULT,
    )
    val verbose = PitestEvidenceSnapshot.capture(
      input,
      emptyList(),
      pluginSha256,
      0,
      " verbose_no_spinner ",
    )

    assertEquals(legacy.configurationSha256, explicitDefault.configurationSha256)
    assertNotEquals(legacy.configurationSha256, verbose.configurationSha256)
  }

  @Test
  fun `removed test classes are set-ordered evidence while an empty set stays compatible`() {
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val input = input(pluginCode = pluginCode)
    val pluginSha256 = PitestEvidence.sha256(pluginCode)
    val default = HardeningCommandLines.PitestVerbosity.DEFAULT
    // The published contract this setting had to preserve: a suite that removes no
    // test class keeps the configuration hash it published before the option existed.
    val legacy = PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 0, default)
    val explicitEmpty =
      PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 0, default, emptyList())
    val removed = PitestEvidenceSnapshot.capture(
      input, emptyList(), pluginSha256, 0, default, listOf("example.SlowTest"))
    // PIT ORs the exclusions, so registration order is not a configuration difference.
    val reordered = PitestEvidenceSnapshot.capture(
      input, emptyList(), pluginSha256, 0, default, listOf("example.ScriptTest", "example.SlowTest"))
    val sameSet = PitestEvidenceSnapshot.capture(
      input, emptyList(), pluginSha256, 0, default, listOf("example.SlowTest", "example.ScriptTest"))

    assertEquals(legacy.configurationSha256, explicitEmpty.configurationSha256)
    assertNotEquals(legacy.configurationSha256, removed.configurationSha256)
    assertNotEquals(removed.configurationSha256, reordered.configurationSha256)
    assertEquals(reordered.configurationSha256, sameSet.configurationSha256)
  }

  @Test
  fun `the evidence encoder refuses selection values that could forge a configuration line`() {
    // The canonical text is one key=value line per setting, so a value carrying a
    // newline writes a line of its own: targetTests ending '|\nexcludedTestClasses=y'
    // beside the record 'com.ZTest' would render exactly as targetTests ending '|'
    // beside the single record 'y\nexcludedTestClasses=com.ZTest' — one
    // configurationSha256 for two suites that hand PIT different arguments, only one
    // of which excludes com.ZTest.
    //
    // Refused at the encoder rather than only on the suite's providers, because
    // every capture overload funnels here, the one-argument form is published, and a
    // caller reaching this directly would otherwise mint the colliding identity.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val pluginSha256 = PitestEvidence.sha256(pluginCode)
    val default = HardeningCommandLines.PitestVerbosity.DEFAULT
    fun capture(targetTests: String, excludedTestClasses: List<String>) =
      PitestEvidenceSnapshot.capture(
        input(pluginCode = pluginCode, targetTests = targetTests),
        emptyList(), pluginSha256, 0, default, excludedTestClasses,
      )

    assertThrows(GradleException::class.java, {
      capture("~com\\..*Test$|\nexcludedTestClasses=y", listOf("com.ZTest"))
    }, "a targetTests that opens a configuration line was encoded")

    assertThrows(GradleException::class.java, {
      capture("~com\\..*Test$|", listOf("y\nexcludedTestClasses=com.ZTest"))
    }, "an exclusion that closes a forged configuration line was encoded")

    // The published one-argument form funnels here too, so it is covered by the same
    // check rather than by whatever its caller happened to validate.
    assertThrows(GradleException::class.java, {
      PitestEvidenceSnapshot.capture(
        input(pluginCode = pluginCode, targetTests = "example.*Test*\nmutators=EVIL"))
    }, "the published capture overload encoded a forged configuration line")
  }

  @Test
  fun `the encoder refuses every textual field that could forge a line or a list entry`() {
    // The pair-at-a-time version of this check missed a collision spread across two
    // fields that were each individually fine: targetClasses ["a\nexcludedClasses=b"]
    // with excludedClasses ["c"] renders exactly as targetClasses ["a"] with
    // excludedClasses ["b\nexcludedClasses=c"], while PIT is handed different
    // arguments. So the rule belongs to the encoder as a whole — every raw textual
    // field it writes, through the published entry point.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")

    // A newline forges a whole line, in any field.
    listOf(
      "targetClasses" to input(pluginCode = pluginCode,
        targetClasses = listOf("example.A\nexcludedClasses=example.B")),
      "excludedClasses" to input(pluginCode = pluginCode,
        excludedClasses = listOf("example.B\nexcludedClasses=example.C")),
      "targetTests" to input(pluginCode = pluginCode, targetTests = "example.*Test*\nmutators=EVIL"),
      "mutators" to input(pluginCode = pluginCode, mutators = "STRONGER\nthreads=99"),
      "recompileExcludes" to input(pluginCode = pluginCode,
        recompileExcludes = listOf("Integ.java\nmutators=EVIL")),
    ).forEach { (field, forged) ->
      assertThrows(GradleException::class.java, {
        PitestEvidenceSnapshot.capture(forged)
      }, "a newline in $field was encoded")
    }

    // A comma forges a neighbouring entry, in the fields rendered as a joined list.
    // It is legal in the scalars, where it is the field's own separator.
    assertThrows(GradleException::class.java, {
      PitestEvidenceSnapshot.capture(
        input(pluginCode = pluginCode, recompileExcludes = listOf("A.java,B.java")))
    }, "['A.java,B.java'] must not hash like ['A.java', 'B.java']")
    assertThrows(GradleException::class.java, {
      PitestEvidenceSnapshot.capture(
        input(pluginCode = pluginCode, targetClasses = listOf("example.A,example.B")))
    }, "a comma in a targetClasses entry was encoded")

    // Commas in the scalars stay legal: they are PIT's own list separators there.
    PitestEvidenceSnapshot.capture(
      input(pluginCode = pluginCode, targetTests = "a.*Test*,b.*Test*", mutators = "STRONGER,MATH"))
  }

  /** An Iterable that yields once and then refuses, like a consumed Sequence. */
  private class OneShot<T>(private val items: List<T>) : Iterable<T> {
    private var used = false
    override fun iterator(): Iterator<T> {
      check(!used) { "iterated twice" }
      used = true
      return items.iterator()
    }
  }

  /** An Iterable that answers safely while checked and differently while written. */
  private class Shifting(private val first: List<String>, private val then: List<String>) :
    Iterable<String> {
    private var calls = 0
    override fun iterator(): Iterator<String> = (if (calls++ == 0) first else then).iterator()
  }

  @Test
  fun `every iterable input is read exactly once, so validation sees what is serialized`() {
    // Iterable promises nothing about repeatability, and it is the published input
    // type. Read twice, a one-shot field validates clean then serializes as empty,
    // and a stateful one can answer safely while being checked and differently while
    // being written — validation and encoding would disagree about the same field.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val source = file("src/main/java/example/Codec.java", "class Codec {}")

    // Every one-shot field at once: anything read twice throws from OneShot.
    val oneShot = PitestEvidenceSnapshot.capture(input(
      pluginCode = pluginCode,
      sourceFiles = OneShot(listOf(source)),
      classFiles = OneShot(listOf(source)),
      runtimeClasspath = OneShot(listOf(source)),
      toolClasspath = OneShot(listOf(source)),
      targetClasses = OneShot(listOf("example.Codec")),
      excludedClasses = OneShot(listOf("example.Generated")),
      recompileExcludes = OneShot(listOf("Integ.java")),
    ))
    val plain = PitestEvidenceSnapshot.capture(input(
      pluginCode = pluginCode,
      sourceFiles = listOf(source),
      classFiles = listOf(source),
      runtimeClasspath = listOf(source),
      toolClasspath = listOf(source),
      targetClasses = listOf("example.Codec"),
      excludedClasses = listOf("example.Generated"),
      recompileExcludes = listOf("Integ.java"),
    ))
    // Not merely "did not throw": a field read twice would also have hashed as empty
    // on the second read, so the two captures must agree field for field.
    assertEquals(plain.configurationSha256, oneShot.configurationSha256)
    assertEquals(plain.sourceSha256, oneShot.sourceSha256)
    assertEquals(plain.classesSha256, oneShot.classesSha256)
    assertEquals(plain.classpathSha256, oneShot.classpathSha256)
    assertEquals(plain.toolClasspathSha256, oneShot.toolClasspathSha256)

    // And a field whose second read differs never has a second read: the value that
    // was validated is the value that is hashed. Asserted as equality with the first
    // list rather than as a refusal — a refusal would mean the forged second value
    // had been reached, which is the defect, not the fix.
    val shifting = PitestEvidenceSnapshot.capture(input(
      pluginCode = pluginCode,
      targetClasses = Shifting(listOf("example.Codec"), listOf("example.A\nexcludedClasses=B")),
    ))
    assertEquals(
      PitestEvidenceSnapshot.capture(
        input(pluginCode = pluginCode, targetClasses = listOf("example.Codec"))
      ).configurationSha256,
      shifting.configurationSha256,
      "a value that only appears on the second read reached the configuration text",
    )
  }

  @Test
  fun `classpath order hashing cannot be forged by a path containing a newline`() {
    // A newline is legal in a Unix path, and the order hash joins paths with one. For
    // Y = X + "\n" + X the orders [X, Y] and [Y, X] join to the same string, and both
    // hold the same files so the order-independent content hashes match too — two
    // classpaths that shadow differently in the JVM, one complete evidence identity.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val x = file("deps/x.jar", "x")
    val forged = File(tempDir, "deps/x.jar\n" + File(tempDir, "deps/x.jar").absolutePath)
      .apply { parentFile.mkdirs(); writeText("x") }

    listOf<(List<File>) -> PitestEvidenceSnapshotInput>(
      { input(pluginCode = pluginCode, runtimeClasspath = it) },
      { input(pluginCode = pluginCode, toolClasspath = it) },
    ).forEach { build ->
      assertThrows(GradleException::class.java, {
        PitestEvidenceSnapshot.capture(build(listOf(x, forged)))
      }, "a newline-bearing classpath entry was hashed")
      // Reversed too: the collision is between the two orders, so neither may encode.
      assertThrows(GradleException::class.java, {
        PitestEvidenceSnapshot.capture(build(listOf(forged, x)))
      }, "a newline-bearing classpath entry was hashed in the reverse order")
    }
  }

  @Test
  fun `a blank list entry is refused, since it renders as no entry at all`() {
    // A list holding one blank entry joins to the empty string, exactly as an empty
    // list does — two different configurations, one line, one hash.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    listOf<(List<String>) -> PitestEvidenceSnapshotInput>(
      { input(pluginCode = pluginCode, targetClasses = it) },
      { input(pluginCode = pluginCode, excludedClasses = it) },
      { input(pluginCode = pluginCode, recompileExcludes = it) },
    ).forEach { build ->
      assertThrows(GradleException::class.java, {
        PitestEvidenceSnapshot.capture(build(listOf("")))
      }, "a list holding one empty entry hashed as an empty list")
      assertThrows(GradleException::class.java, {
        PitestEvidenceSnapshot.capture(build(listOf("   ")))
      }, "a list holding one blank entry hashed as an empty list")
    }
  }

  @Test
  fun `a substituted main class is part of the configuration identity, the default is absent`() {
    // Which process ran is part of what produced the report: a fixed main reading a
    // flag can emit either of two reports, and without this line the second would
    // validate under the first's identity. Absent at PIT's own entry point, so no
    // recorded hash moves for any ordinary run.
    val pluginCode = file("plugin/sava-build.jar", "sava-build")
    val input = input(pluginCode = pluginCode)
    val pluginSha256 = PitestEvidence.sha256(pluginCode)
    val default = HardeningCommandLines.PitestVerbosity.DEFAULT
    val legacy = PitestEvidenceSnapshot.capture(input, emptyList(), pluginSha256, 0, default, emptyList())
    val explicitDefault = PitestEvidenceSnapshot.capture(
      input, emptyList(), pluginSha256, 0, default, emptyList(),
      PitestEvidenceSnapshot.DEFAULT_MAIN_CLASS)
    val substituted = PitestEvidenceSnapshot.capture(
      input, emptyList(), pluginSha256, 0, default, emptyList(), "com.example.FakePit")

    assertEquals(legacy.configurationSha256, explicitDefault.configurationSha256)
    assertNotEquals(legacy.configurationSha256, substituted.configurationSha256)
  }

  private fun input(
    sourceFiles: Iterable<File> = emptyList(),
    classFiles: Iterable<File> = emptyList(),
    runtimeClasspath: Iterable<File> = emptyList(),
    toolClasspath: Iterable<File> = emptyList(),
    pluginCode: File,
    targetTests: String = "example.*Test*",
    // Iterable, not List: the published input type is Iterable, and typing the
    // fixture more narrowly would hide exactly the repeatability defect below.
    targetClasses: Iterable<String> = listOf("example.Parser", "example.Codec"),
    excludedClasses: Iterable<String> = listOf("example.Generated", "example.CodecFuzz"),
    mutators: String = "STRONGER",
    recompileExcludes: Iterable<String> = listOf("Scratch.java", "Integ.java"),
  ) = PitestEvidenceSnapshotInput(
    suite = "encoding",
    invocationId = "run-123",
    pitestVersion = "1.25.8",
    junitPluginVersion = "1.2.3",
    javaVersion = "25",
    projectDirectory = tempDir,
    pluginCode = pluginCode,
    sourceFiles = sourceFiles,
    classFiles = classFiles,
    runtimeClasspath = runtimeClasspath,
    toolClasspath = toolClasspath,
    mutationToolchainSha256 = "0".repeat(64),
    targetClasses = targetClasses,
    excludedClasses = excludedClasses,
    targetTests = targetTests,
    mutators = mutators,
    threads = 4,
    timeoutFactor = 1.25,
    timeoutConst = 4000,
    mutationBytecodeRelease = 21,
    recompileExcludes = recompileExcludes,
    reportSha256 = "report-hash",
    scope = PitestEvidence.FULL_SCOPE,
    historyAssisted = false,
  )

  private fun file(relative: String, content: String): File =
    tempDir.resolve(relative).also {
      it.parentFile.mkdirs()
      it.writeText(content)
    }
}
