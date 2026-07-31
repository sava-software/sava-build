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
  fun `parse keeps the lines a row's comment names, united across duplicates`() {
    // the key is line-less on purpose, but the '# line N' the seed writes is the
    // anchor the README cause argues about — kept per member for the drift check
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator # lines 12, 30",
      "com.example.Codec,encode,MathMutator # line 45 — the recorded benign flapper",
      "com.example.Codec,decode,IncrementsMutator # removed loop exit, no anchor named",
      "com.example.Codec,strip,MathMutator",
    ))
    assertEquals(mapOf("com.example.Codec,encode,MathMutator" to setOf(12, 30, 45)), membership.recordedLines)
  }

  @Test
  fun `parse reads slash-separated line lists, the hand-written shape shipped in consumer files`() {
    // the seed writes commas, but committed rows in the wild say '# lines 137/141';
    // keeping only the first number would read the second line's timeout as drift
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,await,VoidMethodCallMutator # lines 137/141",
      "com.example.Codec,call,RemoveConditionalMutator_EQUAL_IF # lines 35/39, load flip",
    ))
    assertEquals(
      mapOf(
        "com.example.Codec,await,VoidMethodCallMutator" to setOf(137, 141),
        "com.example.Codec,call,RemoveConditionalMutator_EQUAL_IF" to setOf(35, 39),
      ),
      membership.recordedLines
    )
  }

  @Test
  fun `line drift fires only when observed and recorded lines are disjoint`() {
    val recorded = mapOf(
      "com.example.Codec,encode,MathMutator" to setOf(12, 30),
      "com.example.Codec,decode,IncrementsMutator" to setOf(44),
    )
    // overlap on 30: a new sibling line next to a recorded one is the line-less
    // key's stated no-warning resolution
    assertEquals(
      emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
      TimeoutAudit.lineDrift(recorded, mapOf("com.example.Codec,encode,MathMutator" to setOf(30, 99)))
    )
    // fully disjoint: the anchor the cause argues about has moved
    assertEquals(
      mapOf("com.example.Codec,encode,MathMutator" to (setOf(12, 30) to setOf(99))),
      TimeoutAudit.lineDrift(recorded, mapOf("com.example.Codec,encode,MathMutator" to setOf(99)))
    )
    // a member with no recorded anchor, or no observation this run, takes no part
    assertEquals(
      emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
      TimeoutAudit.lineDrift(recorded, mapOf("com.example.Codec,gone,MathMutator" to setOf(7)))
    )
    assertEquals(
      emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
      TimeoutAudit.lineDrift(recorded, emptyMap())
    )
  }

  @Test
  fun `the drift warning prints recorded and observed lines per member`() {
    val warning = TimeoutAudit.lineDriftWarning(
      "encoding", "encoding-timeouts.csv",
      mapOf("com.example.Codec,encode,MathMutator" to (setOf(30, 12) to setOf(99))),
    )
    assertTrue(warning.contains("1 audited-timeout member(s) timed out at line(s)"), warning)
    assertTrue(warning.contains("encoding-timeouts.csv"), warning)
    assertTrue(
      warning.contains("  com.example.Codec,encode,MathMutator # line(s) 12, 30 -> observed 99"),
      warning
    )
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
  fun `a cause needs the class and the method in one paragraph, as whole words`() {
    // whole-file substring matching was trivially satisfied: 'run' sits inside
    // "rerun", and a sibling member's cause already names the class — so a class
    // with two audited methods passed as fully documented with one cause written
    val members = setOf("com.example.Notifier,run,MathMutator")
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        "`Notifier.queueResponse`: drains the queue.\n\nRerun the suite to see it."
      }
    )
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        // class and method both present, but never in the same paragraph
        "`Notifier.queueResponse`: drains the queue.\n\nThe run loop parks forever."
      }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) {
        // the house style: one intro line naming Class.method, bullets below in the
        // same blank-line-delimited paragraph
        "Both are in `Notifier.run`, the drain loop:\n- the removed exit parks the drain\n"
      }
    )
  }

  @Test
  fun `a constructor member's cause resolves despite word boundaries failing at angle brackets`() {
    // '\b<init>\b' can never match 'Handler.<init>' in prose: a word boundary needs
    // a word char adjacent to each angle bracket, and '.' or a space is not one —
    // whole-word matching must use lookarounds, or every constructor member in a
    // shipped audit set reads as cause-less and fails certification
    val members = setOf("com.example.ExponentialBackoffErrorHandler,<init>,ConditionalsBoundaryMutator")
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) {
        "`ExponentialBackoffErrorHandler.<init>:14` — the measured load flip; the\nconstructor hang is only observable as a timeout.\n"
      }
    )
    // still whole-word: 'init' alone must not satisfy '<init>'
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        "`ExponentialBackoffErrorHandler` init logic crawls under load.\n"
      }
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
