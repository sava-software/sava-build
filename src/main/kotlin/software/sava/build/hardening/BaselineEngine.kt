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
    val ambiguousFallback: Boolean,
    /** Paired in the fallback phase at all, ambiguous or not, rather than by line affinity. */
    val fallbackPaired: Boolean,
  )

  private class MutableCopy(val line: Int?) {
    var rowIndex: Int? = null
    var ambiguousFallback = false
    var fallbackPaired = false
  }

  /**
   * Assigns observed copies to accepted rows at one key. A deterministic maximum
   * bipartite match consumes every exact line affinity that can coexist — a row
   * carrying several historical anchors cannot steal the sole anchor of a narrower
   * sibling. Copies still unpaired then consume rows in a stable partition: rows
   * that name any currently observed line first, then bare or wholly stale rows.
   * Inside each group the pairing prefers the row whose recorded line is nearest the
   * copy's, ties in file order (see [nearestPairing]), so a block of siblings that
   * moved together keeps its order and a sibling that moved alone, by less than the
   * gap to its neighbours, follows its own line rather than its file position. That
   * second phase is the intentional moved-anchor fallback: a row whose tag names no
   * live line is the deterministic absent-sibling preference before a duplicate live
   * anchor. It is uniquely attributable only when the anchor itself is unique, which
   * is why Retag and Prune name the keys they decided this way, and those whose rows
   * carry different labels.
   * Keeping this allocator shared by planning and rewriting prevents either surface
   * from selecting a different same-key sibling when recorded line tags repeat.
   */
  private fun assignObservedCopies(
    acceptedRows: List<BaselineNotes.Row>,
    rowIndices: List<Int>,
    observedLines: List<String>,
  ): List<ObservedCopy> {
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
    val (liveFirst, rest) = rowIndices
        .filter { it !in rowToCopy }
        .partition { rowIndex -> acceptedRows[rowIndex].recordedLines.any { it in liveLines } }
    var remainingRows = liveFirst.size + rest.size
    val anchorsRepeat = anchorsRepeat(acceptedRows, rowIndices)
    for (group in listOf(liveFirst, rest)) {
      val pending = copies.filter { it.rowIndex == null }
      if (group.isEmpty() || pending.isEmpty()) continue
      // Diagnostic only: with several remaining rows, or with rows that share an anchor,
      // so that line affinity itself had to choose between siblings, metadata cannot
      // identify the physical sibling; the pairing below is a preference, not identity.
      val ambiguous = remainingRows > 1 || anchorsRepeat
      for ((copyIndex, rowIndex) in nearestPairing(acceptedRows, group, pending)) {
        pending[copyIndex].rowIndex = rowIndex
        pending[copyIndex].ambiguousFallback = ambiguous
        pending[copyIndex].fallbackPaired = true
      }
      remainingRows -= group.size
    }
    return copies.map { ObservedCopy(it.line, it.rowIndex, it.ambiguousFallback, it.fallbackPaired) }
  }

  /** Whether two rows at one key record the same line, so an exact match had to choose. */
  private fun anchorsRepeat(acceptedRows: List<BaselineNotes.Row>, rowIndices: List<Int>): Boolean =
      rowIndices.flatMap { acceptedRows[it].recordedLines }.groupingBy { it }.eachCount().any { it.value > 1 }

  /**
   * Cost of pairing a row with a copy: the squared distance from the row's nearest
   * recorded line. Squared rather than absolute: when every sibling moved by the same
   * amount, every pairing has the same absolute total and only the tie-break would
   * decide, while the squared total is smallest for the pairing that keeps the recorded
   * order (idl-src-gen f8df935a3 crossed two labels under an absolute cost). A copy
   * whose line did not parse carries no position: it is free for a bare row and costs a
   * tagged row as much as a bare row would, so tagged rows keep the copies with lines.
   */
  private fun pairingCost(row: BaselineNotes.Row, line: Int?): Long = when {
    row.recordedLines.isEmpty() -> if (line == null) 0L else BARE_ROW_COST
    line == null -> BARE_ROW_COST
    else -> row.recordedLines.minOf { val distance = kotlin.math.abs(it.toLong() - line); distance * distance }
  }

  /** Above any sum of [EXHAUSTIVE_PAIRING_LIMIT] squared line distances. */
  private const val BARE_ROW_COST = 1L shl 50
  private const val EXHAUSTIVE_PAIRING_LIMIT = 8

  /**
   * Pairs rows of one partition group with leftover copies so that the sum of squared
   * distances between each row's nearest recorded line and its copy's line is minimal; ties keep
   * file order (rows in file order, copies in line order, first minimum found wins), so
   * a block of siblings that moved together keeps its order, and a rewrite is a fixed
   * point. A bare row costs more than any distance, so it pairs last. Beyond
   * [EXHAUSTIVE_PAIRING_LIMIT] rows or copies the search would not be cheap, and the
   * pairing falls back to file order against line order, the rule this replaced.
   */
  private fun nearestPairing(
    acceptedRows: List<BaselineNotes.Row>,
    group: List<Int>,
    pending: List<MutableCopy>,
  ): List<Pair<Int, Int>> {
    val pairs = minOf(group.size, pending.size)
    if (group.size > EXHAUSTIVE_PAIRING_LIMIT || pending.size > EXHAUSTIVE_PAIRING_LIMIT) {
      return (0 until pairs).map { it to group[it] }
    }
    val cost = Array(group.size) { r -> LongArray(pending.size) { c -> pairingCost(acceptedRows[group[r]], pending[c].line) } }
    var best: IntArray? = null
    var bestCost = Long.MAX_VALUE
    val chosen = IntArray(group.size) { -1 }
    val used = BooleanArray(pending.size)
    fun search(row: Int, assigned: Int, soFar: Long) {
      if (soFar >= bestCost) return
      if (assigned == pairs) { best = chosen.copyOf(); bestCost = soFar; return }
      if (row == group.size) return
      // this row takes a copy, copies in line order
      for (c in pending.indices) {
        if (used[c]) continue
        used[c] = true; chosen[row] = c
        search(row + 1, assigned + 1, soFar + cost[row][c])
        used[c] = false; chosen[row] = -1
      }
      // or this row stays unpaired, when enough rows remain for the copies
      if (group.size - row - 1 >= pairs - assigned) search(row + 1, assigned, soFar)
    }
    search(0, 0, 0L)
    val result = checkNotNull(best)
    return group.indices.filter { result[it] >= 0 }.map { r -> result[r] to group[r] }
  }

  /**
   * Whether the rows a fallback chose among at one key carry different family labels:
   * the one case where the choice changes what a row claims, since equal labels make the
   * siblings interchangeable for the reader. The candidates are every row at the key
   * that no exact line match consumed, paired by the fallback or left behind by it; when
   * rows at the key repeat an anchor, the exact matches chose between siblings too, so
   * every row is a candidate.
   */
  private fun fallbackLabelConflict(
    acceptedRows: List<BaselineNotes.Row>,
    rowIndices: List<Int>,
    copies: List<ObservedCopy>,
  ): Boolean {
    if (copies.none { it.ambiguousFallback }) return false
    val exactRows = if (anchorsRepeat(acceptedRows, rowIndices)) emptySet() else
      copies.filter { !it.fallbackPaired }.mapNotNullTo(HashSet()) { it.rowIndex }
    return rowIndices.filter { it !in exactRows }
        .map { BaselineNotes.labelOf(acceptedRows[it].note ?: "") }
        .distinct().size > 1
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
   * The row-level keep plan read by BOTH prune and the check path's candidate preview —
   * one allocator, so the preview names exactly the rows prune would keep and drop.
   * Per accepted row, in this order:
   *
   *  - [Disposition.MATCHED] — holds part of its key's surviving budget: line
   *    affinity first (a row whose `# line` tag names an observed unkilled line is
   *    the preferred assignment, unique only when that anchor is unique), then rows
   *    naming a live line before stale or bare rows, nearest recorded line within
   *    each group and file order on ties, the update refresh's own rule.
   *  - [Disposition.INSURED] — a row at a flip-insured key, kept unconditionally
   *    and decided BEFORE the timeout budget: an insured row spending that budget
   *    vouched for nobody and pushed the sibling the timeout could actually be
   *    hiding past the budget.
   *  - [Disposition.TIMEOUT] — holds part of its coordinate's timeout budget: at
   *    most as many rows as mutants actually timed out there. Affine rows first (a
   *    `# line` tag naming a timed-out line), bare rows in file order, anti-affine
   *    rows last (a tag naming a KILLED line is the strongest deterministic drop
   *    preference available, although duplicate same-line siblings remain ambiguous).
   *  - [Disposition.FLIP] — consumed an *unmatched* different-status counterpart
   *    at its coordinate (the pairing the verify classifies as "newly covered").
   *  - [Disposition.DROP] — unmatched by this run and therefore a prune candidate.
   *    One observation alone cannot distinguish stable removal from an uninsured
   *    load- or mode-dependent status flip; that judgment belongs to the caller.
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
        .filter { BaselineNotes.hasFlipInsurance(it.note) }
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
    val sourceRowIndices: List<Int>,
    /**
     * Keys where the fallback, not line affinity, decided which kept sibling got which
     * line, or where a dropped row shares its recorded line with a kept one, so that line
     * affinity chose between same-anchor siblings in file order.
     */
    val ambiguousFallbackKeys: List<String> = emptyList(),
    /** The ambiguous keys whose rows so chosen among carry different family labels. */
    val differingLabelFallbackKeys: List<String> = emptyList(),
  )

  /**
   * Renders a prune after its caller has proved that the proposed kept multiset
   * accepts the complete current gated population. Rows matched at their own key
   * receive this run's observed line, assigned by recorded-line affinity first and
   * the nearest-line fallback second — the same sibling rule used by [updateRewrite]. Unmatched
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
    val ambiguousFallbackKeys = mutableListOf<String>()
    val differingLabelFallbackKeys = mutableListOf<String>()
    val matchedByKey = acceptedRows.indices
        .filter { keepPlan[it] == Disposition.MATCHED }
        .groupBy { acceptedRows[it].key }
    val rowsByKey = acceptedRows.indices.groupBy { acceptedRows[it].key }
    for ((key, matchedRows) in matchedByKey) {
      // The allocation the keep plan made, over every row at the key: the fallback that
      // chose which sibling to keep and which to drop is the one to report, and a re-run
      // over the kept rows alone could only look unanimous.
      val rowIndices = rowsByKey.getValue(key)
      val copies = assignObservedCopies(acceptedRows, rowIndices, currentLines[key].orEmpty())
      val paired = copies.mapNotNull { it.rowIndex }
      require(paired.size == copies.size && paired.toSet() == matchedRows.toSet()) {
        "prune matched ${matchedRows.size} row(s) at '$key' but observed ${copies.size} current copy/copies"
      }
      if (copies.any { it.ambiguousFallback }) {
        ambiguousFallbackKeys.add(key)
        if (fallbackLabelConflict(acceptedRows, rowIndices, copies)) differingLabelFallbackKeys.add(key)
      }
      // A dropped row that records the same line as a kept one lost to it in file order
      // at the exact phase, with no copy left for a fallback to flag (two same-line
      // siblings, one killed: the recordFailedPing shape). That choice is not identity
      // either, and it changes what the kept row claims when the labels differ. A kept
      // twin with the same note is a byte-identical row: whichever one goes, the written
      // rows are the same, so there is nothing to re-read (8 of 10 such drops in fleet
      // history were identical rows).
      for (dropped in rowIndices.filter { keepPlan[it] == Disposition.DROP }) {
        val twins = matchedRows.filter { kept ->
          acceptedRows[kept].note != acceptedRows[dropped].note &&
            acceptedRows[kept].recordedLines.any { it in acceptedRows[dropped].recordedLines }
        }
        if (twins.isEmpty()) continue
        if (key !in ambiguousFallbackKeys) ambiguousFallbackKeys.add(key)
        val labels = (twins + dropped).map { BaselineNotes.labelOf(acceptedRows[it].note ?: "") }.distinct()
        if (labels.size > 1 && key !in differingLabelFallbackKeys) differingLabelFallbackKeys.add(key)
      }
      copies.forEach { copy ->
        refreshedLines[checkNotNull(copy.rowIndex)] = copy.line?.let(::listOf).orEmpty()
      }
    }
    var refreshedLineTags = 0
    val sourceRowIndices = acceptedRows.indices.filter { keepPlan[it] != Disposition.DROP }
    val written = sourceRowIndices.map { index ->
      val row = acceptedRows[index]
      val lines = if (keepPlan[index] == Disposition.MATCHED) {
        refreshedLines.getValue(index)
      } else {
        row.recordedLines
      }
      if (lines != row.recordedLines) refreshedLineTags++
      BaselineNotes.render(row.key, row.note, lines)
    }
    return PruneRewrite(
        written, refreshedLineTags, sourceRowIndices, ambiguousFallbackKeys, differingLabelFallbackKeys)
  }

  /** Every accepted row retained in place, with current lines assigned only to matched rows. */
  data class RetagRewrite(
    val written: List<String>,
    val refreshedLineTags: Int,
    val sourceRowIndices: List<Int>,
    val ambiguousFallbackKeys: List<String>,
    /** The ambiguous keys whose fallback-paired rows carry different family labels. */
    val differingLabelFallbackKeys: List<String> = emptyList(),
  )

  /**
   * `pitest<Suite>BaselineRetag`: acknowledge reviewed line drift without changing
   * baseline identity or multiplicity. Current copies consume accepted rows through
   * the shared maximum-affinity allocator. A matched row receives the observed line;
   * every unmatched row keeps its parsed note and recorded lines unchanged. The
   * caller must first prove that every current gated copy is already accepted; this
   * kernel repeats that invariant so a future caller cannot silently omit the guard.
   * The transition can therefore neither accept fresh debt nor hide it behind a
   * metadata rewrite.
   */
  fun retagRewrite(
    acceptedRows: List<BaselineNotes.Row>,
    currentLines: Map<String, List<String>>,
  ): RetagRewrite {
    val acceptedCounts = acceptedRows.groupingBy { it.key }.eachCount()
    val excess = currentLines.entries
        .filter { (key, lines) -> lines.size > (acceptedCounts[key] ?: 0) }
        .sortedBy { it.key }
    require(excess.isEmpty()) {
      "retag cannot rewrite line metadata while the current population contains " +
          "unaccepted copies: " + excess.joinToString { (key, lines) ->
            "$key x${lines.size - (acceptedCounts[key] ?: 0)}"
          }
    }
    val refreshedLines = HashMap<Int, List<Int>>()
    val ambiguousFallbackKeys = mutableListOf<String>()
    val differingLabelFallbackKeys = mutableListOf<String>()
    acceptedRows.indices.groupBy { acceptedRows[it].key }.forEach { (key, rowIndices) ->
      val copies = assignObservedCopies(acceptedRows, rowIndices, currentLines[key].orEmpty())
      if (copies.any { it.ambiguousFallback }) {
        ambiguousFallbackKeys.add(key)
        if (fallbackLabelConflict(acceptedRows, rowIndices, copies)) differingLabelFallbackKeys.add(key)
      }
      copies.forEach { copy ->
        copy.rowIndex?.let { rowIndex ->
          refreshedLines[rowIndex] = copy.line?.let(::listOf).orEmpty()
        }
      }
    }
    var refreshedLineTags = 0
    val written = acceptedRows.indices.map { index ->
      val row = acceptedRows[index]
      val lines = refreshedLines[index] ?: row.recordedLines
      if (lines != row.recordedLines) refreshedLineTags++
      BaselineNotes.render(row.key, row.note, lines)
    }
    return RetagRewrite(
        written, refreshedLineTags, acceptedRows.indices.toList(),
        ambiguousFallbackKeys, differingLabelFallbackKeys)
  }

  /**
   * The drift comparison's outcome: dangerous flips by origin, and every other
   * timeout-count delta keyed by the line-less coordinate that changed. The maps
   * retain multiplicity so output can name a new sibling even when that coordinate
   * is already present in the audited-timeout set.
   */
  data class Drift(
    val fromSurvived: List<String>,
    val fromNoCoverage: List<String>,
    val positiveTimedOutByCoordinate: Map<String, Int>,
    val newlyTimedOutByCoordinate: Map<String, Int>,
    val firstObservedByCoordinate: Map<String, Int>,
    val resolvedByCoordinate: Map<String, Int>,
  ) {
    val newlyTimedOut: Int
      get() = newlyTimedOutByCoordinate.values.sum()

    val firstObserved: Int
      get() = firstObservedByCoordinate.values.sum()

    val resolved: Int
      get() = resolvedByCoordinate.values.sum()
  }

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
    val positiveTimedOut = linkedMapOf<String, Int>()
    val newlyTimedOut = linkedMapOf<String, Int>()
    val firstObserved = linkedMapOf<String, Int>()
    val resolved = linkedMapOf<String, Int>()
    for (key in previous.keys + current.keys) {
      val timedOut = delta(key, "TIMED_OUT")
      if (timedOut > 0) {
        positiveTimedOut[key] = timedOut
        val survivedDrop = delta(key, "SURVIVED") < 0
        val noCoverageDrop = delta(key, "NO_COVERAGE") < 0
        when {
          survivedDrop || noCoverageDrop -> {
            if (survivedDrop) fromSurvived += key
            if (noCoverageDrop) fromNoCoverage += key
          }
          key in previous &&
              previous.getValue(key).keys.any { it == "KILLED" || it == "TIMED_OUT" } ->
            newlyTimedOut[key] = timedOut
          else -> firstObserved[key] = timedOut
        }
      } else if (timedOut < 0) {
        resolved[key] = -timedOut
      }
    }
    return Drift(
        fromSurvived,
        fromNoCoverage,
        positiveTimedOut,
        newlyTimedOut,
        firstObserved,
        resolved,
    )
  }

  /** A report-driven rewrite plus protected rows: the lines and every named outcome. */
  data class UpdateRewrite(
    val written: List<String>,
    val copies: Int,
    val seeded: Int,
    val flipped: Int,
    val droppedIdx: List<Int>,
    val carriedIdx: Set<Int>,
    val preservedTimeoutIdx: Set<Int>,
    val preservedInsuredIdx: Set<Int>,
    val sourceRowIndices: List<Int?>,
  )

  /**
   * `pitest<Suite>BaselineUpdate`: rewrite from this run's report. Within a key,
   * accepted rows are assigned to this run's mutants by line affinity first, then by
   * nearest recorded line (least squared distance) with file order on ties; a dropped
   * row's note carries across a status flip at the same
   * coordinate (consumed once, marked for re-reading); every remaining new copy
   * seeds `# untriaged`. Rows protected by this run's timeout budget or persistent
   * flip insurance remain verbatim: neither watchdog detection nor the
   * absent/other-status side of an observed mode flip proves that its accepted row
   * has gone away.
   * A pending different-status [Disposition.FLIP] is deliberately not persistent —
   * update resolves that reviewed transition through the note-carry path, whereas a
   * shrink-only prune cannot add the current status and therefore keeps it pending.
   */
  fun updateRewrite(
    acceptedRows: List<BaselineNotes.Row>,
    currentLines: Map<String, List<String>>,
    keepPlan: List<Disposition>,
  ): UpdateRewrite {
    require(acceptedRows.size == keepPlan.size) {
      "update keep plan has ${keepPlan.size} dispositions for ${acceptedRows.size} rows"
    }
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
    val plannedMatchedIdx = acceptedRows.indices.filterTo(HashSet()) {
      keepPlan[it] == Disposition.MATCHED
    }
    require(chosenIdx == plannedMatchedIdx) {
      "update assignment selected ${chosenIdx.sorted()} but its keep plan matched " +
          "${plannedMatchedIdx.sorted()}"
    }
    val preservedTimeoutIdx = acceptedRows.indices.filterTo(linkedSetOf()) {
      keepPlan[it] == Disposition.TIMEOUT
    }
    val preservedInsuredIdx = acceptedRows.indices.filterTo(linkedSetOf()) {
      keepPlan[it] == Disposition.INSURED
    }
    val preservedIdx = preservedTimeoutIdx + preservedInsuredIdx
    val droppedIdx = acceptedRows.indices.filter { it !in chosenIdx && it !in preservedIdx }
    val flipPool = droppedIdx.filter {
      keepPlan[it] == Disposition.FLIP && acceptedRows[it].note != null
    }
    // A status change still has line evidence. Allocate every unpaired current copy
    // to old-status rows with the same maximum exact-affinity matcher used within a
    // key, rather than carrying notes by file order across distinguishable siblings.
    val flipByCopy = HashMap<Int, Int>()
    copies.indices.filter { copies[it].pair == null }
        .groupBy { copies[it].key.substringBeforeLast(',') }
        .forEach { (coordinate, copyIndices) ->
          val candidateRows = flipPool.filter {
            acceptedRows[it].key.substringBeforeLast(',') == coordinate
          }
          if (candidateRows.isEmpty()) return@forEach
          val copiesByLine = copyIndices.groupBy { copies[it].line }
              .mapValues { (_, indices) -> ArrayDeque(indices) }
          assignObservedCopies(
              acceptedRows,
              candidateRows,
              copyIndices.map { copies[it].line?.toString().orEmpty() },
          ).forEach { assignment ->
            val copyIndex = copiesByLine.getValue(assignment.line).removeFirst()
            assignment.rowIndex?.let { flipByCopy[copyIndex] = it }
          }
        }
    val carriedIdx = mutableSetOf<Int>()
    var flipped = 0
    var seeded = 0
    data class PlannedRow(val key: String, val rendered: String, val sourceRowIndex: Int?)
    val observedRows = copies.mapIndexed { copyIndex, copy ->
      val lineTag = copy.line?.let { listOf(it) } ?: emptyList()
      val match = copy.pair
      if (match != null) {
        return@mapIndexed PlannedRow(
            copy.key,
            BaselineNotes.render(copy.key, acceptedRows[match].note, lineTag),
            match,
        )
      }
      val flip = flipByCopy[copyIndex]
      if (flip != null) {
        carriedIdx.add(flip)
        flipped++
        val from = acceptedRows[flip].key.substringAfterLast(',')
        val to = copy.key.substringAfterLast(',')
        return@mapIndexed PlannedRow(
            copy.key,
            BaselineNotes.render(
                copy.key, "${acceptedRows[flip].note} (carried across $from -> $to)", lineTag),
            flip,
        )
      }
      seeded++
      PlannedRow(copy.key, BaselineNotes.render(copy.key, "# untriaged", lineTag), null)
    }
    val protectedRows = preservedIdx.sorted().map { index ->
      acceptedRows[index].let { PlannedRow(it.key, BaselineNotes.render(it), index) }
    }
    require(carriedIdx == flipPool.toSet()) {
      "update status-flip assignment carried ${carriedIdx.sorted()} but its keep plan selected " +
          "${flipPool.sorted()}"
    }
    // Existing evidence stays in original row order so its document slot and nearby
    // prose remain stable. Genuinely new rows append deterministically. Repeating the
    // update therefore remains a fixed point without globally sorting old rows.
    val planned = (observedRows + protectedRows)
    val ordered = planned.filter { it.sourceRowIndex != null }
        .sortedBy { checkNotNull(it.sourceRowIndex) } +
        planned.filter { it.sourceRowIndex == null }
            .sortedWith(compareBy<PlannedRow>({ it.key }, { it.rendered }))
    return UpdateRewrite(
        ordered.map { it.rendered },
        copies.size,
        seeded,
        flipped,
        droppedIdx,
        carriedIdx,
        preservedTimeoutIdx,
        preservedInsuredIdx,
        ordered.map { it.sourceRowIndex },
    )
  }

  /** An append-only union: the rows it adds and the merged file it writes. */
  data class UnionMerge(
    val added: List<String>,
    val merged: List<String>,
    val total: Int,
    val sourceRowIndices: List<Int?>,
  )

  /**
   * `pitest<Suite>BaselineUnion`: multiset union — per key, the larger of the two
   * occurrence counts. Existing rows keep their notes and tags verbatim and
   * consume observed copies through the same maximum exact-affinity assignment as
   * prune and update; only rows with no possible exact match use the moved-anchor
   * fallback, where rows still naming a live line precede stale rows. Added copies
   * land `# untriaged` with the genuinely unclaimed observed lines, so append-only
   * union cannot silently turn a newly observed mutant into an argued acceptance.
   */
  fun unionMerge(
    acceptedRows: List<BaselineNotes.Row>,
    current: List<String>,
    currentLines: Map<String, List<String>>,
  ): UnionMerge = unionMerge(acceptedRows, current, currentLines, "# untriaged")

  /**
   * A provenance transition is deliberately non-destructive: retain every old
   * accepted row and seed every missing current copy for review. Like direct
   * additive Union, new rows are visibly untriaged; Rebase additionally binds the
   * reviewed toolchain transition.
   */
  fun rebaseMerge(
    acceptedRows: List<BaselineNotes.Row>,
    current: List<String>,
    currentLines: Map<String, List<String>>,
  ): UnionMerge = unionMerge(acceptedRows, current, currentLines, "# untriaged")

  private fun unionMerge(
    acceptedRows: List<BaselineNotes.Row>,
    current: List<String>,
    currentLines: Map<String, List<String>>,
    addedNote: String?,
  ): UnionMerge {
    val added = multisetDiff(current, acceptedRows.map { it.key })
    if (added.isEmpty()) {
      return UnionMerge(emptyList(), emptyList(), acceptedRows.size, emptyList())
    }
    val rowIndicesByKey = acceptedRows.indices.groupBy { acceptedRows[it].key }
    val currentCounts = current.groupingBy { it }.eachCount()
    val additions = currentCounts.keys.sorted().flatMap { key ->
      val rowIndices = rowIndicesByKey[key].orEmpty()
      val extra = maxOf(0, currentCounts.getValue(key) - rowIndices.size)
      val unclaimed = ArrayDeque(
          assignObservedCopies(acceptedRows, rowIndices, currentLines[key].orEmpty())
              .filter { it.rowIndex == null }
              .map { it.line })
      List(extra) {
        BaselineNotes.render(
            key, addedNote, unclaimed.removeFirstOrNull()?.let { listOf(it) } ?: emptyList())
      }
    }
    // Union and provenance rebase are append-only document transitions. Keeping
    // every existing row in its original slot is stronger than retaining its bytes:
    // BaselineDocument leaves intervening comments fixed, so sorting old rows here
    // would silently move those comments onto different mutants.
    val merged = acceptedRows.map(BaselineNotes::render) + additions
    return UnionMerge(
        added,
        merged,
        merged.size,
        acceptedRows.indices.map { it as Int? } + List(additions.size) { null },
    )
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
