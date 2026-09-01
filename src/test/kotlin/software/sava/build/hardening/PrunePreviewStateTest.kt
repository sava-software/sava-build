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

    val rendered = state.render()
    assertTrue(rendered.contains("${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}true"))
    assertFalse(rendered.contains("# qualifies "))
    assertEquals(state, PrunePreviewState.parse(rendered))
    assertEquals(2, PrunePreviewState.parse(rendered).candidates.count { it == rowA })
    assertThrows(IllegalArgumentException::class.java) {
      PrunePreviewState.parse(
          rendered.replace(
              "${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}true",
              "${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}maybe",
          ),
      )
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
  fun `same invocation remains a replay across every reset dimension`() {
    val first = PrunePreviewHistory.observe(null, observation("one"))
    val established = PrunePreviewHistory.observe(first.state.render(), observation("two"))
    assertEquals(2, established.state.matchingObservations)

    val replays = listOf(
        observation("two", inputs = "c".repeat(64)),
        observation("two", records = "d".repeat(64)),
        observation("two", candidates = listOf(rowA, rowB)),
        observation("two", qualifies = false),
        observation(
            "two",
            candidates = listOf(rowB),
            qualifies = false,
            inputs = "c".repeat(64),
            records = "d".repeat(64),
        ),
    )

    replays.forEach { replay ->
      val transition = PrunePreviewHistory.observe(established.state.render(), replay)
      assertEquals(PrunePreviewTransitionKind.SAME_INVOCATION, transition.kind)
      assertEquals(established.state, transition.state)
      assertFalse(transition.writerAuthorized)
    }
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
    assertEquals(0, malformed.state.matchingObservations)
    assertFalse(malformed.state.qualifies)
    assertTrue(malformed.malformedDetail!!.isNotBlank())
  }

  @Test
  fun `legacy replay resets uncounted before two deliberate previews accumulate`() {
    val legacy = PrunePreviewState(
        "a".repeat(64),
        "b".repeat(64),
        "legacy-certification-observation",
        2,
        true,
        listOf(rowA),
    ).render().replaceFirst(
        PrunePreviewState.FORMAT_HEADER,
        PrunePreviewState.LEGACY_FORMAT_1_HEADER,
    ).replaceFirst(
        "${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}true",
        "# qualifies true",
    )

    val reset = PrunePreviewHistory.observe(
        legacy, observation("legacy-certification-observation"))
    assertEquals(PrunePreviewTransitionKind.MALFORMED_RESET, reset.kind)
    assertEquals(0, reset.state.matchingObservations)
    assertFalse(reset.state.qualifies)
    assertFalse(reset.writerAuthorized)
    assertTrue(reset.state.render().startsWith("${PrunePreviewState.FORMAT_HEADER}\n"))
    assertTrue(reset.malformedDetail!!.contains("unsupported prune-preview format"))

    val replay = PrunePreviewHistory.observe(
        reset.state.render(), observation("legacy-certification-observation"))
    assertEquals(PrunePreviewTransitionKind.SAME_INVOCATION, replay.kind)
    assertEquals(0, replay.state.matchingObservations)
    assertFalse(replay.state.qualifies)

    val deliberateFirst = PrunePreviewHistory.observe(
        replay.state.render(), observation("deliberate-one"))
    assertEquals(PrunePreviewTransitionKind.AFTER_INELIGIBLE, deliberateFirst.kind)
    assertEquals(1, deliberateFirst.state.matchingObservations)
    assertTrue(deliberateFirst.state.qualifies)
    assertFalse(deliberateFirst.writerAuthorized)

    val deliberateSecond = PrunePreviewHistory.observe(
        deliberateFirst.state.render(), observation("deliberate-two"))
    assertEquals(PrunePreviewTransitionKind.MATCHED, deliberateSecond.kind)
    assertEquals(2, deliberateSecond.state.matchingObservations)
    assertTrue(deliberateSecond.state.qualifies)
    assertFalse(
        deliberateSecond.writerAuthorized,
        "two deliberate previews must complete before a later writer starts",
    )

    val distinctAtMigration = PrunePreviewHistory.observe(
        legacy, observation("distinct-fresh-observation"))
    assertEquals(PrunePreviewTransitionKind.MALFORMED_RESET, distinctAtMigration.kind)
    assertEquals(1, distinctAtMigration.state.matchingObservations)
    assertTrue(distinctAtMigration.state.qualifies)

    val untrustedLegacy = legacy.replace(
        "# last invocation legacy-certification-observation",
        "# last invocation ",
    )
    val untrustedReset = PrunePreviewHistory.observe(
        untrustedLegacy, observation("distinct-but-unprovable"))
    assertEquals(PrunePreviewTransitionKind.MALFORMED_RESET, untrustedReset.kind)
    assertEquals(0, untrustedReset.state.matchingObservations)
    assertFalse(untrustedReset.state.qualifies)
  }
}
