package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for the baseline note format. In-process and pure, unlike the TestKit
 * functional tests that reach this logic through a forked build's log output: the
 * cases below (a note that is never read, tie ordering, a label whose parenthesis is
 * not a carry marker) are impractical to stage through a build fixture.
 */
class BaselineNotesTest {

  @Test
  fun `a note begins at the first hash and the row is what precedes it`() {
    assertNull(BaselineNotes.noteOf("com.example.Codec,encode,12,MathMutator,SURVIVED"))
    assertEquals("com.example.Codec,encode,12,MathMutator,SURVIVED",
        BaselineNotes.rowOf("com.example.Codec,encode,12,MathMutator,SURVIVED"))

    val line = "com.example.Codec,encode,12,MathMutator,SURVIVED # race guard"
    assertEquals("# race guard", BaselineNotes.noteOf(line))
    assertEquals("com.example.Codec,encode,12,MathMutator,SURVIVED", BaselineNotes.rowOf(line))

    // surrounding whitespace is not part of either half; internal spacing survives,
    // and labelOf normalizes it away downstream
    assertEquals("#   race guard", BaselineNotes.noteOf("  row   #   race guard   "))
    assertEquals("row", BaselineNotes.rowOf("  row   #   race guard   "))
    assertEquals("race guard", BaselineNotes.labelOf("#   race guard"))

    // only the FIRST hash splits: a note may contain further hashes
    assertEquals("# see #42 for the argument", BaselineNotes.noteOf("row # see #42 for the argument"))
    assertEquals("row", BaselineNotes.rowOf("row # see #42 for the argument"))
  }

  @Test
  fun `a label drops the hash and any trailing carry parenthetical`() {
    assertEquals("race guard", BaselineNotes.labelOf("# race guard"))
    assertEquals("untriaged", BaselineNotes.labelOf("# untriaged"))
    assertEquals("race guard",
        BaselineNotes.labelOf("# race guard (carried across NO_COVERAGE -> SURVIVED)"))
    assertEquals("flip insurance",
        BaselineNotes.labelOf("# flip insurance (gate=SURVIVED, solo=KILLED)"))
    // the carry marker is delimited by ' (' — a parenthesis inside the label itself
    // (no preceding space) is part of the family name, not a marker to strip
    assertEquals("guard(inner)", BaselineNotes.labelOf("# guard(inner)"))
  }

  @Test
  fun `summarize counts per label, descending, with unlabeled rows named last`() {
    assertNull(BaselineNotes.summarize(emptyList(), 0), "an empty baseline has nothing to say")

    // a baseline predating label seeding still prints a number rather than nothing
    assertEquals("5 unlabeled", BaselineNotes.summarize(emptyList(), 5))

    assertEquals("2 '# race guard'",
        BaselineNotes.summarize(listOf("# race guard", "# race guard"), 0))

    assertEquals(
        "3 '# race guard', 1 '# untriaged', 2 unlabeled",
        BaselineNotes.summarize(
            listOf("# untriaged", "# race guard", "# race guard", "# race guard"), 2)
    )

    // carried notes fold into their family: the parenthetical is not a separate label
    assertEquals(
        "2 '# race guard'",
        BaselineNotes.summarize(
            listOf("# race guard", "# race guard (carried across NO_COVERAGE -> SURVIVED)"), 0)
    )
  }

  @Test
  fun `labels tied on count keep first-seen order`() {
    // stable ordering keeps the breakdown from reshuffling between runs on a tie,
    // which would read as churn in a build log diff
    assertEquals(
        "1 '# alpha', 1 '# beta'",
        BaselineNotes.summarize(listOf("# alpha", "# beta"), 0)
    )
    assertEquals(
        "1 '# beta', 1 '# alpha'",
        BaselineNotes.summarize(listOf("# beta", "# alpha"), 0)
    )
  }

  @Test
  fun `an undocumented label is named and a documented one is silent`() {
    val readme = "## Triaged\n\n# race guard\n\nThe argument for the family.\n"

    assertNull(BaselineNotes.undocumentedLabelWarning("encoding", listOf("# race guard")) { readme })

    val warning = BaselineNotes.undocumentedLabelWarning("encoding", listOf("# race gaurd")) { readme }
    assertEquals(
        "pitest baseline 'encoding': label(s) with no argument in config/pitest/README.md — " +
            "'# race gaurd' — document the family there, or fix the label if it is a typo",
        warning,
        "a typo must be named rather than silently opening a bucket of its own"
    )

    // several undocumented labels are named once each, in first-seen order
    val many = BaselineNotes.undocumentedLabelWarning(
        "encoding", listOf("# alpha", "# beta", "# alpha")) { readme }
    assertEquals(
        "pitest baseline 'encoding': label(s) with no argument in config/pitest/README.md — " +
            "'# alpha', '# beta' — document the family there, or fix the label if it is a typo",
        many
    )

    // a carried note resolves against its family's section, not its full text
    assertNull(
        BaselineNotes.undocumentedLabelWarning(
            "encoding", listOf("# race guard (carried across NO_COVERAGE -> SURVIVED)")) { readme }
    )
  }

  @Test
  fun `the README is not read when no label needs resolving`() {
    // the resolve is lazy by contract: a baseline of unlabeled or seeded rows must
    // cost no file read, which only a direct call can observe
    var reads = 0
    val readme = { reads++; "" }

    assertNull(BaselineNotes.undocumentedLabelWarning("encoding", emptyList(), readme))
    assertEquals(0, reads, "an empty baseline must not read the README")

    assertNull(BaselineNotes.undocumentedLabelWarning("encoding", listOf("# untriaged"), readme))
    assertEquals(0, reads, "seeded debt argues nothing and needs no section")

    BaselineNotes.undocumentedLabelWarning("encoding", listOf("# race guard", "# other"), readme)
    assertEquals(1, reads, "the README must be read once per call, not once per label")
  }
}
