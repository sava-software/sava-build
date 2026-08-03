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
    siblingScopes: List<ExclusionAudit.SuiteScope> = emptyList(),
  ): List<ExclusionAudit.Swallowed> = ExclusionAudit.swallowedProductionClasses(
    classesDir, targetGlobs, excludedGlobs, listOf(testSrcDir), siblingScopes
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
  @Test
  fun `an argued decline takes its classes out of the report`() {
    // the deliberate-opt-out category: "generated bindings" is a judgment the scan
    // cannot derive from the globs, so it is written down instead
    val swallowed = listOf(
      ExclusionAudit.Swallowed("com.example.gen.Foo", "com.example.gen.*"),
      ExclusionAudit.Swallowed("com.example.gen.Bar", "com.example.gen.*"),
      ExclusionAudit.Swallowed("com.example.Orphan", "com.example.Orphan"),
    )

    val declined = ExclusionAudit.applyDeclines(
      swallowed, mapOf("com.example.gen.*" to "generated bindings; the generator's own suites cover them"))

    assertEquals(
      listOf(ExclusionAudit.Swallowed("com.example.Orphan", "com.example.Orphan")),
      declined.reported,
      "an undeclined glob must still be reported"
    )
    assertEquals(emptyList<String>(), declined.staleGlobs)
    assertEquals(emptyList<String>(), declined.blankGlobs)
    assertNull(ExclusionAudit.staleDeclineWarning("encoding", declined.staleGlobs))
    assertNull(ExclusionAudit.blankDeclineWarning("encoding", declined.blankGlobs))
  }

  @Test
  fun `a blank reason suppresses nothing and is named`() {
    // the declineMutator contract: a record made to quiet a warning nobody
    // investigated reads as settled to everyone after, so it must not work
    val swallowed = listOf(ExclusionAudit.Swallowed("com.example.gen.Foo", "com.example.gen.*"))

    val declined = ExclusionAudit.applyDeclines(swallowed, mapOf("com.example.gen.*" to "   "))

    assertEquals(swallowed, declined.reported, "a blank reason must suppress nothing")
    assertEquals(listOf("com.example.gen.*"), declined.blankGlobs)
    assertEquals(emptyList<String>(), declined.staleGlobs, "a blank decline still matched a class")
    assertTrue(
      ExclusionAudit.blankDeclineWarning("encoding", declined.blankGlobs)!!
        .contains("suppress nothing"),
      "the empty decline must be named"
    )
  }

  @Test
  fun `a decline that matches nothing is reported as deletable`() {
    // declines go stale like baseline rows: the exclusion it argued about is gone
    val declined = ExclusionAudit.applyDeclines(
      listOf(ExclusionAudit.Swallowed("com.example.gen.Foo", "com.example.gen.*")),
      mapOf(
        "com.example.gen.*" to "generated bindings",
        "com.example.retired.*" to "a glob that no longer swallows anything",
      )
    )

    assertEquals(emptyList<ExclusionAudit.Swallowed>(), declined.reported)
    assertEquals(listOf("com.example.retired.*"), declined.staleGlobs)
    assertTrue(
      ExclusionAudit.staleDeclineWarning("encoding", declined.staleGlobs)!!.contains("delete them"),
      "a stale decline must be named as deletable"
    )
  }

  @Test
  fun `declines key on the glob as written, not on the classes it swallows`() {
    // the record argues about an exclusion, so a glob that swallows fifty classes is
    // one decision, not fifty — and a decline naming a class rather than the glob
    // that swallowed it matches nothing and says so
    val swallowed = List(50) { i -> ExclusionAudit.Swallowed("com.example.gen.C$i", "com.example.gen.*") }

    val byGlob = ExclusionAudit.applyDeclines(swallowed, mapOf("com.example.gen.*" to "generated"))
    assertEquals(emptyList<ExclusionAudit.Swallowed>(), byGlob.reported)

    val byClass = ExclusionAudit.applyDeclines(swallowed, mapOf("com.example.gen.C0" to "generated"))
    assertEquals(50, byClass.reported.size, "a class-keyed record must not suppress its glob")
    assertEquals(listOf("com.example.gen.C0"), byClass.staleGlobs)
  }

  @Test
  fun `a class a sibling suite mutates is the partition working, not a finding`() {
    // the targeting policy excludes "classes owned by another suite"; the audit
    // must read that handoff as ownership, or a partitioned repo drowns in
    // advisories naming its own deliberate structure
    writeClass("com.example.decoding.Decoder")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.decoding.*"),
      siblingScopes = listOf(
        ExclusionAudit.SuiteScope(
          targetGlobs = listOf("com.example.decoding.*"),
          excludedGlobs = listOf("com.example.*Test*"),
        )
      ),
    )

    assertTrue(swallowed.isEmpty(), swallowed.toString())
  }

  @Test
  fun `a class every suite excludes has no owner and stays a finding`() {
    // ownership must be effective: a sibling whose targets match but whose own
    // exclusions also swallow the class is not mutating it either, and the class
    // sits outside the ratchet everywhere
    writeClass("com.example.decoding.LegacyDecoder")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.decoding.*"),
      siblingScopes = listOf(
        ExclusionAudit.SuiteScope(
          targetGlobs = listOf("com.example.decoding.*"),
          excludedGlobs = listOf("com.example.decoding.Legacy*"),
        )
      ),
    )

    assertEquals(
      listOf(ExclusionAudit.Swallowed("com.example.decoding.LegacyDecoder", "com.example.decoding.*")),
      swallowed
    )
  }

  @Test
  fun `sibling ownership uses PIT glob semantics like every other match here`() {
    // the sibling's targets are the same glob language as the suite's own: star
    // spans package separators, so a sibling targeting an Impl-star family owns
    // the class and its star-matched variants
    writeClass("com.example.ManagerImpl")
    writeClass("com.example.ManagerImplState")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.ManagerImpl*"),
      siblingScopes = listOf(
        ExclusionAudit.SuiteScope(
          targetGlobs = listOf("com.example.ManagerImpl*"),
          excludedGlobs = emptyList(),
        )
      ),
    )

    assertTrue(swallowed.isEmpty(), swallowed.toString())
  }

  @Test
  fun `a secondary top-level class declared in a test file is test-owned, not swallowed`() {
    // FooTests.java can declare 'class RecordingHelper' beside its test class; the
    // compiled RecordingHelper.class has no RecordingHelper.java anywhere, so the
    // exact-file check misread it as a swallowed production class. Ownership is
    // declared in the same package's test sources.
    writeClass("com.example.RecordingHelper")
    val testFile = File(testSrcDir, "com/example/CodecTests.java")
    testFile.parentFile.mkdirs()
    testFile.writeText(
      """
        package com.example;

        class CodecTests {}

        final class RecordingHelper {}
      """.trimIndent()
    )

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.Recording*"),
    )
    assertTrue(swallowed.isEmpty(), "test-declared secondary class read as production: $swallowed")
  }

  @Test
  fun `an inner class in a test file owns nothing top-level`() {
    // an indented 'class Helper' inside a test class compiles to CodecTests${'$'}Helper,
    // so it cannot be the owner of a top-level Helper.class — a whitespace-tolerant
    // anchor silently exempted genuine production classes on the strength of an
    // inner helper sharing the name
    writeClass("com.example.RecordingHelper")
    val testFile = File(testSrcDir, "com/example/CodecTests.java")
    testFile.parentFile.mkdirs()
    testFile.writeText(
      """
        package com.example;

        class CodecTests {
            private static final class RecordingHelper {}
        }
      """.trimIndent()
    )

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.Recording*"),
    )
    assertEquals(listOf("com.example.RecordingHelper"), swallowed.map { it.binaryName })
  }

  @Test
  fun `a same-line annotation on a test declaration still counts as ownership`() {
    writeClass("com.example.RecordingHelper")
    val testFile = File(testSrcDir, "com/example/CodecTests.java")
    testFile.parentFile.mkdirs()
    testFile.writeText(
      """
        package com.example;

        class CodecTests {}

        @SuppressWarnings("unused") final class RecordingHelper {}
      """.trimIndent()
    )

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.Recording*"),
    )
    assertTrue(swallowed.isEmpty(), "annotated test declaration read as production: $swallowed")
  }

  @Test
  fun `a swallowed production class is still reported when no test source declares it`() {
    // the control for the declaration scan: the same shape with no declaring test
    // file anywhere stays a finding
    writeClass("com.example.RecordingHelper")
    val testFile = File(testSrcDir, "com/example/CodecTests.java")
    testFile.parentFile.mkdirs()
    testFile.writeText("package com.example;\n\nclass CodecTests {}\n")

    val swallowed = audit(
      targetGlobs = listOf("com.example.*"),
      excludedGlobs = listOf("com.example.Recording*"),
    )
    assertEquals(listOf("com.example.RecordingHelper"), swallowed.map { it.binaryName })
  }
}
