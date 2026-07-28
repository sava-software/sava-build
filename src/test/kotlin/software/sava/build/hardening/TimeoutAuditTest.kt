package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the audited-timeout membership format. In-process and pure, like
 * [BaselineNotesTest]: the parse edge cases below (comment-only lines, trailing
 * commas, duplicate rows) are impractical to stage through a TestKit fixture, and the
 * functional tests already prove the verify and `Debt` reach this logic.
 */
class TimeoutAuditTest {

  @Test
  fun `parse strips comments, trims per field, and drops blank lines`() {
    val membership = TimeoutAudit.parse(listOf(
      "# header comment",
      "",
      "com.example.Codec,encode,MathMutator # line 12",
      "com.example.Codec, decode , IncrementsMutator",
    ))
    assertEquals(
      setOf("com.example.Codec,encode,MathMutator", "com.example.Codec,decode,IncrementsMutator"),
      membership.members
    )
    assertEquals(emptyList<String>(), membership.malformed)
  }

  @Test
  fun `parse splits off rows without exactly three non-empty fields as malformed`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator",
      "com.example.Codec,encode",
      "com.example.Codec,encode,MathMutator,extra",
      "com.example.Codec,encode,",
    ))
    assertEquals(setOf("com.example.Codec,encode,MathMutator"), membership.members)
    assertEquals(
      listOf(
        "com.example.Codec,encode",
        "com.example.Codec,encode,MathMutator,extra",
        "com.example.Codec,encode,",
      ),
      membership.malformed
    )
  }

  @Test
  fun `parse collapses duplicate rows to one member`() {
    // a twice-pasted row is one member: the verify's set checks and Debt's cause
    // count must agree on that, so the collapse happens here, not per caller
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator",
      "com.example.Codec, encode, MathMutator # spacing is readability, not identity",
    ))
    assertEquals(setOf("com.example.Codec,encode,MathMutator"), membership.members)
  }

  @Test
  fun `malformedWarning names the file and the offending rows, or is null`() {
    assertNull(TimeoutAudit.malformedWarning("encoding", "encoding-timeouts.csv", emptyList()))

    val warning = TimeoutAudit.malformedWarning(
      "encoding", "encoding-timeouts.csv", listOf("com.example.Codec,encode")
    )
    assertTrue(warning!!.contains("1 malformed row(s) in encoding-timeouts.csv"), warning)
    assertTrue(warning.contains("  com.example.Codec,encode"), warning)
  }

  @Test
  fun `a cause needs the class and the method together, not the method alone`() {
    val members = setOf("com.example.Codec,handle,MathMutator")
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) { "handlers handle every message here" }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "`Codec.handle` (MathMutator): drains the queue." }
    )
  }

  @Test
  fun `a nested class matches under its source or binary name`() {
    val members = setOf("com.example.Outer\$Inner,handle,MathMutator")
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "`Outer.Inner.handle`: retries until the clock moves." }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "Outer\$Inner handle: retries until the clock moves." }
    )
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) { "handle appears without either class name" }
    )
  }

  @Test
  fun `the readme is not read when there is no member to resolve`() {
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(emptySet()) { error("readme read with no members") }
    )
  }

  @Test
  fun `the cause warning sorts members under a paste-ready prefix`() {
    val warning = TimeoutAudit.undocumentedCauseWarning(
      "encoding", listOf("b.B,m,MathMutator", "a.A,m,MathMutator")
    )
    assertTrue(warning.contains("2 audited-timeout member(s)"), warning)
    assertTrue(
      warning.indexOf("cause? a.A,m,MathMutator") < warning.indexOf("cause? b.B,m,MathMutator"),
      warning
    )
  }
}
