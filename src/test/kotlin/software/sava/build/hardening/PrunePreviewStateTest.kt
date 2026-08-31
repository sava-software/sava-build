package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrunePreviewStateTest {

  private val rowA = "com.example.Codec,decode,MathMutator,SURVIVED # family A # line 30"
  private val rowB = "com.example.Codec,encode,IncrementsMutator,NO_COVERAGE # family B # line 40"

  private fun observation(
    invocation: String,
    candidates: List<String> = listOf(rowA),
    qualifies: Boolean = true,
    inputs: String = "a".repeat(64),
    records: String = "b".repeat(64),
  ) = PrunePreviewObservation(inputs, records, invocation, qualifies, candidates)

  @Test
  fun `format round trips a sorted candidate multiset including duplicate siblings`() {
    val state = PrunePreviewState(
        "a".repeat(64),
        "b".repeat(64),
        "invocation-1",
        2,
        true,
        listOf(rowA, rowA, rowB).sorted(),
    )

    assertEquals(state, PrunePreviewState.parse(state.render()))
    assertEquals(2, PrunePreviewState.parse(state.render()).candidates.count { it == rowA })
    assertThrows(IllegalArgumentException::class.java) {
      PrunePreviewState.parse(state.render().replace("# qualifies true", "# qualifies maybe"))
    }
  }

  @Test
  fun `same invocation cannot advance and the third distinct match authorizes a writer`() {
    val first = PrunePreviewHistory.observe(null, observation("one"))
    assertEquals(PrunePreviewTransitionKind.FIRST, first.kind)
    assertEquals(1, first.state.matchingObservations)

    val replay = PrunePreviewHistory.observe(first.state.render(), observation("one"))
    assertEquals(PrunePreviewTransitionKind.SAME_INVOCATION, replay.kind)
    assertEquals(1, replay.state.matchingObservations)
    assertFalse(replay.writerAuthorized)

    val second = PrunePreviewHistory.observe(first.state.render(), observation("two"))
    assertEquals(PrunePreviewTransitionKind.MATCHED, second.kind)
    assertEquals(2, second.state.matchingObservations)
    assertFalse(second.writerAuthorized, "the second preview must complete before a writer starts")

    val writer = PrunePreviewHistory.observe(second.state.render(), observation("three"))
    assertEquals(PrunePreviewTransitionKind.MATCHED, writer.kind)
    assertEquals(3, writer.state.matchingObservations)
    assertTrue(writer.writerAuthorized)
  }

  @Test
  fun `candidate drift is multiset aware and resets matching evidence`() {
    val previous = PrunePreviewHistory.observe(
        null, observation("one", listOf(rowA, rowA)))
    val current = PrunePreviewHistory.observe(
        previous.state.render(), observation("two", listOf(rowA, rowB)))

    assertEquals(PrunePreviewTransitionKind.MISMATCH, current.kind)
    assertEquals(listOf(rowB), current.added)
    assertEquals(listOf(rowA), current.removed)
    assertEquals(1, current.state.matchingObservations)
    assertFalse(current.writerAuthorized)
  }

  @Test
  fun `fresh gated debt breaks the sequence and the next eligible run starts over`() {
    val first = PrunePreviewHistory.observe(null, observation("one"))
    val second = PrunePreviewHistory.observe(first.state.render(), observation("two"))
    val ineligible = PrunePreviewHistory.observe(
        second.state.render(), observation("three", qualifies = false))

    assertEquals(PrunePreviewTransitionKind.INELIGIBLE_RESET, ineligible.kind)
    assertEquals(0, ineligible.state.matchingObservations)
    assertFalse(ineligible.state.qualifies)

    val restarted = PrunePreviewHistory.observe(
        ineligible.state.render(), observation("four"))
    assertEquals(PrunePreviewTransitionKind.AFTER_INELIGIBLE, restarted.kind)
    assertEquals(1, restarted.state.matchingObservations)
  }

  @Test
  fun `empty observations replace a prior candidate set`() {
    val previous = PrunePreviewHistory.observe(null, observation("one"))
    val empty = PrunePreviewHistory.observe(
        previous.state.render(), observation("two", emptyList()))

    assertEquals(PrunePreviewTransitionKind.MISMATCH, empty.kind)
    assertEquals(emptyList<String>(), empty.added)
    assertEquals(listOf(rowA), empty.removed)
    assertEquals(emptyList<String>(), empty.state.candidates)
  }

  @Test
  fun `input record and malformed state each reset without inheriting counts`() {
    val first = PrunePreviewHistory.observe(null, observation("one"))
    val inputReset = PrunePreviewHistory.observe(
        first.state.render(), observation("two", inputs = "c".repeat(64)))
    assertEquals(PrunePreviewTransitionKind.INPUT_RESET, inputReset.kind)
    assertEquals(1, inputReset.state.matchingObservations)

    val recordReset = PrunePreviewHistory.observe(
        first.state.render(), observation("two", records = "d".repeat(64)))
    assertEquals(PrunePreviewTransitionKind.RECORD_RESET, recordReset.kind)
    assertEquals(1, recordReset.state.matchingObservations)

    val malformed = PrunePreviewHistory.observe("not state\n", observation("two"))
    assertEquals(PrunePreviewTransitionKind.MALFORMED_RESET, malformed.kind)
    assertEquals(1, malformed.state.matchingObservations)
    assertTrue(malformed.malformedDetail!!.isNotBlank())
  }
}
