package software.sava.build.hardening

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/// A suite's mutant population against the observation its status stash holds, by class.
///
/// `Debt` and the prune previews read unkilled rows, the ownership audit sees whole
/// classes, and the run-to-run drift follows timeouts, so a suite that stops mutating
/// code it used to (a method exclusion, `avoidCallsTo`, a mutator or filter change)
/// narrows unseen as long as the mutants it lost were killed. Counting every status per
/// class makes that visible: removed code explains a drop, and a drop it does not
/// explain is HARDENING.md's "faster without getting narrower" with its missing input.
/// The input is the verify's status stash, which holds every status of the last fresh,
/// full observation a verify stashed. Pure given its inputs.
object PopulationChange {

  /// One class whose mutant count moved between the two runs.
  data class ClassChange(val className: String, val before: Int, val after: Int)

  /// How many moved classes the notice names before counting the rest.
  const val NAMED_CLASSES = 5

  private val STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  /// The classes whose mutant count moved between two status tallies keyed by line-less
  /// coordinate (`class,method,mutator`), every status counted: largest move first, then
  /// by name. Empty when every class kept its count, however its mutants moved between
  /// methods or statuses.
  @JvmStatic
  fun changes(
    previous: Map<String, Map<String, Int>>,
    current: Map<String, Map<String, Int>>,
  ): List<ClassChange> {
    val before = byClass(previous)
    val after = byClass(current)
    return (before.keys + after.keys)
        .map { ClassChange(it, before[it] ?: 0, after[it] ?: 0) }
        .filter { it.before != it.after }
        .sortedWith(compareByDescending<ClassChange> { abs(it.after - it.before) }.thenBy { it.className })
  }

  /// The notice naming the suite's total against the stashed one, when [stash] was last
  /// written, and the classes that moved most; null when no class moved. Any verify that
  /// reaches the stash rewrites it, a re-verify of an unchanged report included, so the
  /// time is not necessarily when PIT last ran.
  @JvmStatic
  fun notice(
    scope: String,
    previous: Map<String, Map<String, Int>>,
    current: Map<String, Map<String, Int>>,
    stash: String,
    stashWrittenAt: Instant,
  ): String? {
    val moved = changes(previous, current)
    if (moved.isEmpty()) return null
    val before = total(previous)
    val after = total(current)
    val stashed = "$stash (written ${STAMP.format(stashWrittenAt)})"
    val header = when {
      after < before -> "$after mutants, ${before - after} fewer than the $before in $stashed; the classes that moved:"
      after > before -> "$after mutants, ${after - before} more than the $before in $stashed; the classes that moved:"
      else -> "$after mutants, as many as $stashed, but the classes moved:"
    }
    val rest = moved.size - NAMED_CLASSES
    return buildString {
      append("$scope: $header")
      moved.take(NAMED_CLASSES).forEach { append("\n  ${it.className} ${it.before} -> ${it.after}") }
      if (rest > 0) append("\n  and $rest more class(es)")
      if (moved.any { it.after < it.before }) {
        append("\n  A drop that removed code does not explain means the suite got narrower: an exclusion, " +
            "avoidCallsTo, or a mutator or filter change.")
      }
    }
  }

  private fun byClass(tally: Map<String, Map<String, Int>>): Map<String, Int> = tally.entries
      .groupingBy { it.key.substringBefore(',') }
      .fold(0) { sum, entry -> sum + entry.value.values.sum() }

  private fun total(tally: Map<String, Map<String, Int>>): Int = tally.values.sumOf { it.values.sum() }
}
