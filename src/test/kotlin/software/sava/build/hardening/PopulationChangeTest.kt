package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The population comparison over status tallies. Seeded [Random] for the silence
 * invariant, as in [BaselineEngineTest]: a notice that fires when no class moved is the
 * line nobody reads.
 */
class PopulationChangeTest {

  private val classes = listOf("com.example.A", "com.example.B", "com.example.C")
  private val methods = listOf("m1", "m2", "m3")
  private val mutators = listOf("MathMutator", "IncrementsMutator")
  private val statuses = listOf("KILLED", "SURVIVED", "NO_COVERAGE", "TIMED_OUT")
  private val stash = ".pitest-history/s.statuses"
  private val writtenAt = Instant.parse("2026-10-09T19:06:42Z")

  private fun randomTally(rnd: Random): Map<String, Map<String, Int>> = buildMap {
    repeat(rnd.nextInt(1, 8)) {
      put(
        "${classes.random(rnd)},${methods.random(rnd)},${mutators.random(rnd)}",
        statuses.filter { rnd.nextBoolean() }.ifEmpty { listOf("KILLED") }.associateWith { rnd.nextInt(1, 4) },
      )
    }
  }

  @Test
  fun `a population whose classes kept their counts is silent, however its mutants moved`() {
    repeat(500) { seed ->
      val rnd = Random(seed)
      val tally = randomTally(rnd)
      assertNull(PopulationChange.notice("pitest 's'", tally, tally, stash, writtenAt), "seed $seed: same tally")
      // Each class's mutants regrouped under one method and one status: per-class counts
      // are unchanged, so nothing moved at the grain the notice reports.
      val regrouped = tally.entries
          .groupBy({ it.key.substringBefore(',') }, { it.value.values.sum() })
          .mapKeys { (cls, _) -> "$cls,m9,MathMutator" }
          .mapValues { (_, counts) -> mapOf("KILLED" to counts.sum()) }
      assertNull(PopulationChange.notice("pitest 's'", tally, regrouped, stash, writtenAt), "seed $seed: regrouped")
    }
  }

  @Test
  fun `every status counts, and the largest move comes first`() {
    val previous = mapOf(
      "com.example.A,m1,MathMutator" to mapOf("KILLED" to 3, "SURVIVED" to 1),
      "com.example.B,m1,MathMutator" to mapOf("TIMED_OUT" to 2),
      "com.example.C,m2,IncrementsMutator" to mapOf("NO_COVERAGE" to 1),
    )
    val current = mapOf(
      "com.example.A,m1,MathMutator" to mapOf("KILLED" to 1),
      "com.example.B,m3,MathMutator" to mapOf("KILLED" to 2),
      "com.example.D,m1,MathMutator" to mapOf("KILLED" to 2),
    )
    assertEquals(
      listOf(
        PopulationChange.ClassChange("com.example.A", 4, 1),
        PopulationChange.ClassChange("com.example.D", 0, 2),
        PopulationChange.ClassChange("com.example.C", 1, 0),
      ),
      PopulationChange.changes(previous, current),
    )
  }

  @Test
  fun `the size of the move ranks a class, not the size of the class`() {
    // Six large classes lose one mutant each and a small one loses all forty: the small
    // class is the narrowing to read first, and ranking by size would bury it in the rest.
    // The large classes arrive in reverse name order, so equal moves are ranked by name,
    // not by arrival.
    val previous = (6 downTo 1).associate { "com.example.Big$it,m1,MathMutator" to mapOf("KILLED" to 500) } +
        mapOf("com.example.Small,m1,MathMutator" to mapOf("KILLED" to 40))
    val current = (6 downTo 1).associate { "com.example.Big$it,m1,MathMutator" to mapOf("KILLED" to 499) }
    val notice = PopulationChange.notice("pitest 's'", previous, current, stash, writtenAt)!!
    assertEquals(
      listOf(
        "  com.example.Small 40 -> 0",
        "  com.example.Big1 500 -> 499",
        "  com.example.Big2 500 -> 499",
        "  com.example.Big3 500 -> 499",
        "  com.example.Big4 500 -> 499",
        "  and 2 more class(es)",
      ),
      notice.lines().drop(1).take(6),
      notice,
    )
  }

  @Test
  fun `a drop names both totals, the stash and when it was written, and says what it means`() {
    val previous = mapOf(
      "com.example.A,m1,MathMutator" to mapOf("KILLED" to 3),
      "com.example.B,m1,MathMutator" to mapOf("KILLED" to 1),
    )
    val current = mapOf(
      "com.example.A,m1,MathMutator" to mapOf("KILLED" to 1),
      "com.example.B,m1,MathMutator" to mapOf("KILLED" to 1),
    )
    assertEquals(
      "pitest 'sdk': 2 mutants, 2 fewer than the 4 in .pitest-history/sdk.statuses (written 2026-10-09 19:06 UTC); " +
          "the classes that moved:\n" +
          "  com.example.A 3 -> 1\n" +
          "  A drop that removed code does not explain means the suite got narrower: an exclusion, " +
          "avoidCallsTo, or a mutator or filter change.",
      PopulationChange.notice("pitest 'sdk'", previous, current, ".pitest-history/sdk.statuses", writtenAt),
    )
  }

  @Test
  fun `growth alone carries no narrowing sentence, and an even total still names the shift`() {
    val previous = mapOf("com.example.A,m1,MathMutator" to mapOf("KILLED" to 2))
    val grown = mapOf("com.example.A,m1,MathMutator" to mapOf("KILLED" to 3))
    val growth = PopulationChange.notice("pitest 's'", previous, grown, stash, writtenAt)!!
    assertTrue(growth.startsWith("pitest 's': 3 mutants, 1 more than the 2 in $stash (written "), growth)
    assertFalse(growth.contains("got narrower"), growth)

    // A stash holding only its header is a population of zero, not a missing base.
    val fromNothing = PopulationChange.notice("pitest 's'", emptyMap(), grown, stash, writtenAt)!!
    assertTrue(fromNothing.startsWith("pitest 's': 3 mutants, 3 more than the 0 in $stash (written "), fromNothing)

    val shifted = mapOf(
      "com.example.A,m1,MathMutator" to mapOf("KILLED" to 1),
      "com.example.B,m1,MathMutator" to mapOf("KILLED" to 1),
    )
    val even = PopulationChange.notice("pitest 's'", previous, shifted, stash, writtenAt)!!
    assertTrue(
      even.startsWith(
        "pitest 's': 2 mutants, as many as $stash (written 2026-10-09 19:06 UTC), but the classes moved:\n" +
            "  com.example.A 2 -> 1\n  com.example.B 0 -> 1"),
      even,
    )
    assertTrue(even.contains("got narrower"), even)
  }

  @Test
  fun `the notice names five classes and counts the rest`() {
    val previous = (1..7).associate { "com.example.K$it,m1,MathMutator" to mapOf("KILLED" to it + 1) }
    val current = (1..7).associate { "com.example.K$it,m1,MathMutator" to mapOf("KILLED" to 1) }
    val notice = PopulationChange.notice("pitest 's'", previous, current, stash, writtenAt)!!
    val named = notice.lines().filter { it.matches(Regex("  com\\.example\\.K\\d \\d+ -> 1")) }
    assertEquals(
      listOf("K7", "K6", "K5", "K4", "K3"),
      named.map { it.trim().substringAfter("com.example.").substringBefore(' ') },
      notice,
    )
    assertTrue(notice.contains("\n  and 2 more class(es)"), notice)

    // Exactly five moved: nothing is left to count.
    val five = PopulationChange.notice("pitest 's'", previous.minus("com.example.K1,m1,MathMutator")
        .minus("com.example.K2,m1,MathMutator"), current.minus("com.example.K1,m1,MathMutator")
        .minus("com.example.K2,m1,MathMutator"), stash, writtenAt)!!
    assertFalse(five.contains("more class(es)"), five)
  }
}
