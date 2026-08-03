package software.sava.build.hardening

/**
 * The baseline state machine's pure transition kernels, extracted from the verify
 * task's `doLast` so they can be unit- and property-tested without a Gradle build.
 * The fleet review's central diagnosis was that ~40% of post-release incidents
 * lived in transition logic whose only test seam was a forked TestKit build
 * asserting on log substrings; every function here is a pure value-in/value-out
 * port of that logic, byte-identical in behavior, with the Gradle task reduced to
 * reading files, calling these, writing files, and printing.
 *
 * Nothing here reads a file, prints, or throws for policy reasons: refusals and
 * message texts stay in the task, which the functional tests (and the fleet
 * canary's message-coupled reprint patterns) continue to pin.
 */
internal object BaselineEngine {

  /** One observed mutant copy, paired at most once with one accepted sibling row. */
  private data class ObservedCopy(
    val line: Int?,
    val rowIndex: Int?,
  )

  /**
   * Assigns observed copies to accepted rows at one key. A deterministic maximum
   * bipartite match consumes every exact line affinity that can coexist — a row
   * carrying several historical anchors cannot steal the sole anchor of a narrower
   * sibling. Copies still unpaired then consume rows in a stable partition: rows
   * that name any currently observed line first, then bare or wholly stale rows,
   * with file order preserved inside both groups. That second phase is the
   * intentional moved-anchor fallback: a row whose tag names no live line is the
   * killed sibling before a duplicate live anchor is. Keeping this allocator
   * shared by planning and rewriting prevents either surface from selecting a
   * different same-key sibling when recorded line tags repeat.
   */
  private fun assignObservedCopies(
    acceptedRows: List<BaselineNotes.Row>,
    rowIndices: List<Int>,
    observedLines: List<String>,
  ): List<ObservedCopy> {
    class MutableCopy(val line: Int?) {
      var rowIndex: Int? = null
    }
    val copies = observedLines
        .map { MutableCopy(it.toIntOrNull()) }
        .sortedWith(compareBy(nullsLast(naturalOrder<Int>())) { it.line })
    val rowToCopy = HashMap<Int, Int>()
    fun augment(copyIndex: Int, visitedRows: MutableSet<Int>): Boolean {
      val line = copies[copyIndex].line ?: return false
      // Prefer a free exact row before displacing an earlier copy. Besides doing
      // less work, this keeps equal-line siblings in file order, so a rewrite is
      // a fixed point even when several observed copies share the same line.
      for (rowIndex in rowIndices) {
        if (line !in acceptedRows[rowIndex].recordedLines || rowIndex in visitedRows) continue
        if (rowIndex !in rowToCopy) {
          visitedRows.add(rowIndex)
          rowToCopy[rowIndex] = copyIndex
          copies[copyIndex].rowIndex = rowIndex
          return true
        }
      }
      for (rowIndex in rowIndices) {
        if (line !in acceptedRows[rowIndex].recordedLines || !visitedRows.add(rowIndex)) continue
        val incumbent = rowToCopy.getValue(rowIndex)
        if (augment(incumbent, visitedRows)) {
          rowToCopy[rowIndex] = copyIndex
          copies[copyIndex].rowIndex = rowIndex
          return true
        }
      }
      return false
    }
    copies.indices.forEach { augment(it, HashSet()) }

    val liveLines = observedLines.mapNotNullTo(HashSet()) { it.toIntOrNull() }
    val unmatchedRows = rowIndices
        .filter { it !in rowToCopy }
        .sortedBy { rowIndex ->
          if (acceptedRows[rowIndex].recordedLines.any { it in liveLines }) 0 else 1
        }
        .toMutableList()
    for (copy in copies) {
      if (copy.rowIndex != null || unmatchedRows.isEmpty()) continue
      copy.rowIndex = unmatchedRows.removeAt(0)
    }
    return copies.map { ObservedCopy(it.line, it.rowIndex) }
  }

  /** Multiset difference: the elements of [a] left after each match in [b] consumes one. */
  fun multisetDiff(a: List<String>, b: List<String>): List<String> {
    val remaining = b.groupingBy { it }.eachCount().toMutableMap()
    return a.filter { row ->
      val n = remaining[row] ?: 0
      if (n > 0) {
        remaining[row] = n - 1
        false
      } else {
        true
      }
    }
  }

  /** A row's disposition in the keep plan. */
  enum class Disposition { MATCHED, INSURED, TIMEOUT, FLIP, DROP }

  /**
   * The row-level keep plan read by BOTH prune and the check path's stale hint —
   * one allocator, so the hint's "prune keeps them" and "refresh with prune" name
   * exactly the rows prune keeps and drops. Per accepted row, in this order:
   *
   *  - [Disposition.MATCHED] — holds part of its key's surviving budget: line
   *    affinity first (a row whose `# line` tag names an observed unkilled line is
   *    that mutant's row), then file order, the update refresh's own rule.
   *  - [Disposition.INSURED] — a row at a flip-insured key, kept unconditionally
   *    and decided BEFORE the timeout budget: an insured row spending that budget
   *    vouched for nobody and pushed the sibling the timeout could actually be
   *    hiding past the budget.
   *  - [Disposition.TIMEOUT] — holds part of its coordinate's timeout budget: at
   *    most as many rows as mutants actually timed out there. Affine rows first (a
   *    `# line` tag naming a timed-out line), bare rows in file order, anti-affine
   *    rows last (a tag naming a KILLED line is provably the killed mutant's row).
   *  - [Disposition.FLIP] — consumed an *unmatched* different-status counterpart
   *    at its coordinate (the pairing the verify classifies as "newly covered").
   *  - [Disposition.DROP] — matches nothing: since killed.
   *
   * [currentLines] maps each unkilled key to its observed line strings (possibly
   * unparsable); [timedOutLinesByCoordinate] and [killedLinesByCoordinate] map the
   * line-less coordinate to the report's per-mutant lines for that status — the
   * list SIZE is the budget, the parsed values feed affinity.
   */
  fun keepPlan(
    acceptedRows: List<BaselineNotes.Row>,
    currentLines: Map<String, List<String>>,
    timedOutLinesByCoordinate: Map<String, List<Int?>>,
    killedLinesByCoordinate: Map<String, List<Int?>>,
  ): List<Disposition> {
    fun coordinate(key: String) = key.substringBeforeLast(',')
    val dispositions = arrayOfNulls<Disposition>(acceptedRows.size)
    for ((key, keyIndices) in acceptedRows.indices.groupBy { acceptedRows[it].key }) {
      assignObservedCopies(acceptedRows, keyIndices, currentLines[key].orEmpty())
          .mapNotNull { it.rowIndex }
          .forEach { dispositions[it] = Disposition.MATCHED }
    }
    val flipInsuredKeys = acceptedRows
        .filter { it.note?.contains("flip insurance") == true }
        .mapTo(HashSet()) { it.key }
    for (index in acceptedRows.indices) {
      if (dispositions[index] == null && acceptedRows[index].key in flipInsuredKeys) {
        dispositions[index] = Disposition.INSURED
      }
    }
    for ((coord, keyIndices) in acceptedRows.indices
        .filter { dispositions[it] == null }
        .groupBy { coordinate(acceptedRows[it].key) }) {
      val timedOutHere = timedOutLinesByCoordinate[coord] ?: continue
      var remaining = timedOutHere.size
      val timedOutLines = timedOutHere.filterNotNull().toSet()
      val killedLines = killedLinesByCoordinate[coord].orEmpty().filterNotNull().toSet()
      val (affine, rest) = keyIndices.partition { index ->
        acceptedRows[index].recordedLines.any { it in timedOutLines }
      }
      val (antiAffine, bare) = rest.partition { index ->
        acceptedRows[index].recordedLines.any { it in killedLines }
      }
      for (index in affine + bare + antiAffine) {
        if (remaining == 0) break
        dispositions[index] = Disposition.TIMEOUT
        --remaining
      }
    }
    val rowsPerKey = acceptedRows.groupingBy { it.key }.eachCount()
    val flipCounterparts = HashMap<String, MutableMap<String, Int>>()
    for ((key, lines) in currentLines) {
      val excess = lines.size - (rowsPerKey[key] ?: 0)
      if (excess > 0) {
        flipCounterparts.getOrPut(coordinate(key)) { HashMap() }[key.substringAfterLast(',')] = excess
      }
    }
    for (index in acceptedRows.indices) {
      if (dispositions[index] != null) continue
      val coord = coordinate(acceptedRows[index].key)
      val rowStatus = acceptedRows[index].key.substringAfterLast(',')
      val byStatus = flipCounterparts[coord]
      val status = byStatus?.keys?.sorted()?.firstOrNull { it != rowStatus && byStatus.getValue(it) > 0 }
      if (status != null) {
        val n = byStatus.getValue(status)
        if (n == 1) byStatus.remove(status) else byStatus[status] = n - 1
        dispositions[index] = Disposition.FLIP
      } else {
        dispositions[index] = Disposition.DROP
      }
    }
    return dispositions.map { checkNotNull(it) }
  }

  /** The rows a green prune writes, and how many matched rows received new line metadata. */
  data class PruneRewrite(
    val written: List<String>,
    val refreshedLineTags: Int,
  )

  /**
   * Renders a prune after its caller has proved that the proposed kept multiset
   * accepts the complete current gated population. Rows matched at their own key
   * receive this run's observed line, assigned by recorded-line affinity first and
   * file order second — the same sibling rule used by [updateRewrite]. Unmatched
   * timeout, pending-flip, and flip-insurance rows have no current observation at
   * their own key, so their recorded lines remain intact. Dropped rows are omitted.
   *
   * The green-population precondition makes the number of current copies at each
   * matched key exactly the number of [Disposition.MATCHED] rows there. Keeping the
   * check here too prevents a future caller from silently discarding or inventing a
   * line assignment.
   */
  fun pruneRewrite(
    acceptedRows: List<BaselineNotes.Row>,
    keepPlan: List<Disposition>,
    currentLines: Map<String, List<String>>,
  ): PruneRewrite {
    require(acceptedRows.size == keepPlan.size) {
      "prune keep plan has ${keepPlan.size} dispositions for ${acceptedRows.size} rows"
    }
    val refreshedLines = HashMap<Int, List<Int>>()
    val matchedByKey = acceptedRows.indices
        .filter { keepPlan[it] == Disposition.MATCHED }
        .groupBy { acceptedRows[it].key }
    for ((key, rowIndices) in matchedByKey) {
      val copies = assignObservedCopies(acceptedRows, rowIndices, currentLines[key].orEmpty())
      require(copies.size == rowIndices.size) {
        "prune matched ${rowIndices.size} row(s) at '$key' but observed ${copies.size} current copy/copies"
      }
      copies.forEach { copy ->
        refreshedLines[checkNotNull(copy.rowIndex)] = copy.line?.let(::listOf).orEmpty()
      }
    }
    var refreshedLineTags = 0
    val written = acceptedRows.indices.mapNotNull { index ->
      if (keepPlan[index] == Disposition.DROP) return@mapNotNull null
      val row = acceptedRows[index]
      val lines = if (keepPlan[index] == Disposition.MATCHED) {
        refreshedLines.getValue(index)
      } else {
        row.recordedLines
      }
      if (lines != row.recordedLines) refreshedLineTags++
      BaselineNotes.render(row.key, row.note, lines)
    }
    return PruneRewrite(written, refreshedLineTags)
  }

  /** The drift comparison's outcome: dangerous flips by origin, and the benign tallies. */
  data class Drift(
    val fromSurvived: List<String>,
    val fromNoCoverage: List<String>,
    val newlyTimedOut: Int,
    val firstObserved: Int,
    val resolved: Int,
  )

  /**
   * Timed-out drift between two per-coordinate status tallies (every status
   * stashed, KILLED included). A key gaining timeouts while losing SURVIVED or
   * NO_COVERAGE is the dangerous flavour, each origin named (a key can lose
   * both); with its unkilled population intact it is "previously detected" only
   * when the previous tally actually held a detected read (KILLED or TIMED_OUT);
   * otherwise it is a first observation — a coordinate the stash never saw, a new
   * sibling at a gated-only key, or a key whose only prior reads were PIT's
   * not-counted-as-detected statuses.
   */
  fun driftCompare(
    previous: Map<String, Map<String, Int>>,
    current: Map<String, Map<String, Int>>,
  ): Drift {
    fun delta(key: String, status: String) =
        (current[key]?.get(status) ?: 0) - (previous[key]?.get(status) ?: 0)
    val fromSurvived = mutableListOf<String>()
    val fromNoCoverage = mutableListOf<String>()
    var newlyTimedOut = 0
    var firstObserved = 0
    var resolved = 0
    for (key in previous.keys + current.keys) {
      val timedOut = delta(key, "TIMED_OUT")
      if (timedOut > 0) {
        val survivedDrop = delta(key, "SURVIVED") < 0
        val noCoverageDrop = delta(key, "NO_COVERAGE") < 0
        when {
          survivedDrop || noCoverageDrop -> {
            if (survivedDrop) fromSurvived += key
            if (noCoverageDrop) fromNoCoverage += key
          }
          key in previous &&
              previous.getValue(key).keys.any { it == "KILLED" || it == "TIMED_OUT" } ->
            newlyTimedOut += timedOut
          else -> firstObserved += timedOut
        }
      } else if (timedOut < 0) {
        resolved -= timedOut
      }
    }
    return Drift(fromSurvived, fromNoCoverage, newlyTimedOut, firstObserved, resolved)
  }

  /** A full-rewrite refresh: the written lines plus everything the task must name. */
  data class UpdateRewrite(
    val written: List<String>,
    val copies: Int,
    val seeded: Int,
    val flipped: Int,
    val droppedIdx: List<Int>,
    val carriedIdx: Set<Int>,
  )

  /**
   * `-PupdateMutationBaseline`: rewrite from this run's report. Within a key,
   * accepted rows are assigned to this run's mutants by line affinity first, then
   * file order; a dropped row's note carries across a status flip at the same
   * coordinate (consumed once, marked for re-reading); every remaining new copy
   * seeds `# untriaged`.
   */
  fun updateRewrite(
    acceptedRows: List<BaselineNotes.Row>,
    currentLines: Map<String, List<String>>,
  ): UpdateRewrite {
    val rowIndicesByKey = acceptedRows.indices.groupBy { acceptedRows[it].key }
    data class Copy(val key: String, val line: Int?, val pair: Int?)
    val copies = currentLines.keys.sorted().flatMap { key ->
      assignObservedCopies(
          acceptedRows,
          rowIndicesByKey[key].orEmpty(),
          currentLines.getValue(key),
      ).map { Copy(key, it.line, it.rowIndex) }
    }
    val chosenIdx = copies.mapNotNullTo(mutableSetOf()) { it.pair }
    val droppedIdx = acceptedRows.indices.filter { it !in chosenIdx }
    val flipPool = droppedIdx.filter { acceptedRows[it].note != null }.toMutableList()
    val carriedIdx = mutableSetOf<Int>()
    var flipped = 0
    var seeded = 0
    val written = copies.map { copy ->
      val lineTag = copy.line?.let { listOf(it) } ?: emptyList()
      val match = copy.pair
      if (match != null) {
        return@map BaselineNotes.render(copy.key, acceptedRows[match].note, lineTag)
      }
      val coordinate = copy.key.substringBeforeLast(',')
      val flip = flipPool.firstOrNull { acceptedRows[it].key.substringBeforeLast(',') == coordinate }
      if (flip != null) {
        flipPool.remove(flip)
        carriedIdx.add(flip)
        flipped++
        val from = acceptedRows[flip].key.substringAfterLast(',')
        val to = copy.key.substringAfterLast(',')
        return@map BaselineNotes.render(
            copy.key, "${acceptedRows[flip].note} (carried across $from -> $to)", lineTag)
      }
      seeded++
      BaselineNotes.render(copy.key, "# untriaged", lineTag)
    }
    return UpdateRewrite(written, copies.size, seeded, flipped, droppedIdx, carriedIdx)
  }

  /** An append-only union: the rows it adds and the merged file it writes. */
  data class UnionMerge(
    val added: List<String>,
    val merged: List<String>,
    val total: Int,
  )

  /**
   * `-PunionMutationBaseline`: multiset union — per key, the larger of the two
   * occurrence counts. Existing rows keep their notes and tags verbatim and
   * consume observed copies through the same maximum exact-affinity assignment as
   * prune and update; only rows with no possible exact match use the moved-anchor
   * fallback, where rows still naming a live line precede stale rows. Added copies
   * land bare with the genuinely unclaimed observed lines.
   */
  fun unionMerge(
    acceptedRows: List<BaselineNotes.Row>,
    current: List<String>,
    currentLines: Map<String, List<String>>,
  ): UnionMerge {
    val added = multisetDiff(current, acceptedRows.map { it.key })
    if (added.isEmpty()) return UnionMerge(emptyList(), emptyList(), acceptedRows.size)
    val rowIndicesByKey = acceptedRows.indices.groupBy { acceptedRows[it].key }
    val currentCounts = current.groupingBy { it }.eachCount()
    var total = 0
    val merged = (rowIndicesByKey.keys + currentCounts.keys).sorted().flatMap { key ->
      val rowIndices = rowIndicesByKey[key].orEmpty()
      val existing = rowIndices.map { acceptedRows[it] }
      val extra = maxOf(0, (currentCounts[key] ?: 0) - existing.size)
      val unclaimed = ArrayDeque(
          assignObservedCopies(acceptedRows, rowIndices, currentLines[key].orEmpty())
              .filter { it.rowIndex == null }
              .map { it.line })
      total += existing.size + extra
      existing.map { BaselineNotes.render(it) } +
          List(extra) {
            BaselineNotes.render(
                key, null, unclaimed.removeFirstOrNull()?.let { listOf(it) } ?: emptyList())
          }
    }
    return UnionMerge(added, merged, total)
  }

  /** The check path's account of fresh rows: what pairs, what surfaces, what is unexplained. */
  data class Churn(
    val newlyCoveredPairs: List<Pair<String, String>>,
    val surfacedSiblings: List<String>,
    val unexplained: Int,
  )

  /**
   * Pairs fresh rows with stale counterparts at the same coordinate (newly
   * covered — a status flip, never a refresh), names fresh rows identical to an
   * accepted row (surfaced sibling debt, or a genuinely new mutant at that key —
   * the line-less key's documented blind spot), and counts the rest unexplained.
   * A stale row is consumed once it pairs, so several fresh rows cannot all claim
   * the same counterpart.
   */
  fun classifyChurn(
    fresh: List<String>,
    stale: List<String>,
    acceptedRowTexts: Set<String>,
  ): Churn {
    val unpairedStale = stale.toMutableList()
    val newlyCoveredPairs = mutableListOf<Pair<String, String>>()
    val surfacedSiblings = mutableListOf<String>()
    for (row in fresh.sorted()) {
      val flip = unpairedStale.firstOrNull {
        it.substringBeforeLast(',') == row.substringBeforeLast(',') && it != row
      }
      if (flip != null) {
        unpairedStale.remove(flip)
        newlyCoveredPairs.add(row to flip)
        continue
      }
      if (row in acceptedRowTexts) {
        surfacedSiblings.add(row)
      }
    }
    return Churn(
        newlyCoveredPairs,
        surfacedSiblings,
        fresh.size - newlyCoveredPairs.size - surfacedSiblings.size,
    )
  }
}
