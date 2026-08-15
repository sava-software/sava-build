package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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

  private fun input(
    sourceFiles: Iterable<File> = emptyList(),
    classFiles: Iterable<File> = emptyList(),
    runtimeClasspath: Iterable<File> = emptyList(),
    toolClasspath: Iterable<File> = emptyList(),
    pluginCode: File,
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
    targetClasses = listOf("example.Parser", "example.Codec"),
    excludedClasses = listOf("example.Generated", "example.CodecFuzz"),
    targetTests = "example.*Test*",
    mutators = "STRONGER",
    threads = 4,
    timeoutFactor = 1.25,
    timeoutConst = 4000,
    mutationBytecodeRelease = 21,
    recompileExcludes = listOf("Scratch.java", "Integ.java"),
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
