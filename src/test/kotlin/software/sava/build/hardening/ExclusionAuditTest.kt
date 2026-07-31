package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExclusionAuditTest {

  @TempDir
  lateinit var classesDir: File

  @TempDir
  lateinit var testSrcDir: File

  private fun writeClass(binaryName: String) {
    val file = File(classesDir, binaryName.replace('.', '/') + ".class")
    file.parentFile.mkdirs()
    file.writeBytes(byteArrayOf(1))
  }

  private fun writeTestSource(binaryName: String) {
    val file = File(testSrcDir, binaryName.replace('.', '/') + ".java")
    file.parentFile.mkdirs()
    file.writeText("// test source\n")
  }

  private fun audit(
    targetGlobs: List<String>,
    excludedGlobs: List<String>,
  ): List<ExclusionAudit.Swallowed> = ExclusionAudit.swallowedProductionClasses(
    classesDir, targetGlobs, excludedGlobs, listOf(testSrcDir)
  )

  @Test
  fun `a production class matched by an exclusion glob is reported with its glob`() {
    writeClass("com.example.Codec")
    writeClass("com.example.RetryTestPolicy")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*"),
    )

    assertEquals(listOf(ExclusionAudit.Swallowed("com.example.RetryTestPolicy", "com.example.*Test*")), swallowed)
  }

  @Test
  fun `classes outside the suite's targets are not the suite's problem`() {
    // an exclusion can only swallow what the targets would otherwise mutate
    writeClass("com.other.StubLikeProduction")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.other.Stub*"),
    )

    assertTrue(swallowed.isEmpty(), swallowed.toString())
  }

  @Test
  fun `nested classes follow their outer class's fate`() {
    writeClass("com.example.RetryTestPolicy")
    writeClass("com.example.RetryTestPolicy\$Inner")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*"),
    )

    assertEquals(1, swallowed.size, swallowed.toString())
    assertEquals("com.example.RetryTestPolicy", swallowed.first().binaryName)
  }

  @Test
  fun `a class whose source sits under a test source dir is the glob's prey, not a finding`() {
    // the mutation recompile contains test sources too — the fixtures the globs
    // exist to exclude must not be reported as swallowed production classes
    writeClass("com.example.CodecTests")
    writeClass("com.example.RecordingCodec")
    writeTestSource("com.example.CodecTests")
    writeTestSource("com.example.RecordingCodec")
    writeClass("com.example.RetryTestPolicy")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*", "com.example.Recording*"),
    )

    assertEquals(
      listOf(ExclusionAudit.Swallowed("com.example.RetryTestPolicy", "com.example.*Test*")),
      swallowed
    )
  }

  @Test
  fun `an unmatched population produces no warning`() {
    writeClass("com.example.Codec")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*", "com.example.Stub*"),
    )

    assertTrue(swallowed.isEmpty(), swallowed.toString())
    assertNull(ExclusionAudit.warning("encoding", swallowed))
  }

  @Test
  fun `the warning names each class, its glob, and the deliberate channel`() {
    writeClass("com.example.RetryTestPolicy")
    writeClass("com.example.StubResolver")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*", "com.example.Stub*"),
    )

    val warning = ExclusionAudit.warning("encoding", swallowed)!!
    assertTrue(warning.contains("2 production class(es) swallowed"), warning)
    assertTrue(warning.contains("com.example.RetryTestPolicy (glob 'com.example.*Test*')"), warning)
    assertTrue(warning.contains("com.example.StubResolver (glob 'com.example.Stub*')"), warning)
    assertTrue(warning.contains("recompileExcludes"), warning)
  }

  @Test
  fun `glob semantics match PIT - star spans separators and dollar is literal`() {
    writeClass("com.example.deep.nested.WsTestDriver")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.*Test*"),
    )
    assertEquals(1, swallowed.size, "star must span package separators: $swallowed")

    // PIT's '**.' marker means zero or more packages: '**.Swallowed' must match the
    // default package and any depth, which plain '*'-expansion cannot express
    writeClass("Swallowed")
    writeClass("com.example.deep.Swallowed")
    val doubleStar = audit(
      targetGlobs = listOf("**.Swallowed", "Swallowed"),
      excludedGlobs = listOf("**.Swallowed", "Swallowed"),
    )
    assertEquals(listOf("Swallowed", "com.example.deep.Swallowed"), doubleStar.map { it.binaryName })

    // a '$' in a glob is literal, not regex end-anchor: 'Foo$*' must not match 'FooBar'
    writeClass("com.example.FooBar")
    val dollar = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.Foo\$*"),
    )
    assertTrue(dollar.none { it.binaryName == "com.example.FooBar" }, dollar.toString())
  }
}
