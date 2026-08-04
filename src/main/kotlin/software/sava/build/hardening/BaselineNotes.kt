package software.sava.build.hardening

/**
 * The single place that knows a baseline row's format, so the accepted-row parser, the
 * verify / `Debt` per-label breakdowns, and `pitestModeCompare`'s insurance writes can
 * never drift on where a note begins, how a family label is read out of one, or which
 * key a row compares by.
 *
 * A baseline line is `<key> [# <label> [(<carry/flip detail>)]] [# line <N>[, <M>...]]`
 * where the key is the line-less `class,method,mutator,STATUS`. Line numbers are
 * metadata, not identity — the trailing `# line` comment is a triage pointer kept for
 * the line-drift advisory, exactly the audited-timeout convention — so editing above a
 * mutated method can never churn the ratchet. The note begins at the first `#`; the
 * family label is that note with the leading `#` and any trailing parenthetical
 * stripped, so `# race guard (carried across …)` reads as `race guard`.
 *
 * Legacy five-field rows (`class,method,<line>,mutator,STATUS`, the pre-line-less
 * format) still parse: the numeric line field is demoted to recorded-line metadata and
 * the row compares by its line-less key. Any baseline write emits the current format,
 * so a legacy file migrates on its next baseline-rewriting refresh.
 */
internal object BaselineNotes {

  /** One parsed baseline row: its line-less key, its note, and its recorded lines. */
  data class Row(val key: String, val note: String?, val recordedLines: List<Int>)

  /**
   * The literal persistence marker written by `pitestModeCompareUnion`.
   * Insurance is a recorded observation, not something inferred from a row merely
   * existing at the right key: an unmarked acceptance can satisfy today's multiset
   * gate and still be removed by a later prune. Keep recognition here so mode compare,
   * prune, and the dead-row sweep cannot assign different meaning to the same note.
   */
  private const val FLIP_INSURANCE_MARKER = "flip insurance"

  fun hasFlipInsurance(note: String?): Boolean =
      note?.contains(FLIP_INSURANCE_MARKER) == true

  /**
   * Adds persistent flip evidence without replacing the row's existing family label or
   * note. A bare row receives the canonical machine-written note; an already-triaged
   * row keeps its note verbatim as the prefix and gains an additional parenthetical.
   * Callers select rows deterministically and preserve their recorded-line metadata.
   */
  fun withFlipInsurance(note: String?, detail: String): String = when {
    hasFlipInsurance(note) -> checkNotNull(note)
    note == null -> "# flip insurance ($detail)"
    else -> "$note (flip insurance: $detail)"
  }

  // Trailing '# line 61' / '# lines 61, 93' / '# lines 61/93' comment. Anchored to the
  // end of the line so a label containing the word 'line' cannot be misread as a tag.
  private val LINE_TAG = Regex("""#\s*lines?\s+\d+(?:\s*[,/]\s*\d+)*\s*$""")

  /** Parses one non-comment baseline line into its [Row]. */
  fun parse(line: String): Row {
    val tagMatch = LINE_TAG.find(line)
    val beforeTag = if (tagMatch == null) line else line.substring(0, tagMatch.range.first)
    val tagLines = tagMatch?.value?.let { tag ->
      Regex("""\d+""").findAll(tag).map { it.value.toInt() }.toList()
    }.orEmpty()
    val hash = beforeTag.indexOf('#')
    val rawKey = (if (hash < 0) beforeTag else beforeTag.substring(0, hash)).trim()
    val note = if (hash < 0) null else beforeTag.substring(hash).trim().ifEmpty { null }
    val (key, legacyLine) = normalize(rawKey)
    return Row(key, note, legacyLine?.let { listOf(it) } ?: tagLines)
  }

  /**
   * The line-less key a raw row coordinate compares by, plus the legacy row's line
   * field when the coordinate carries one. Legacy rows are recognized by shape — five
   * fields with a numeric third — which no line-less row can have (a method name is
   * never numeric); anything else compares as written, spacing normalized per field.
   */
  fun normalize(rawKey: String): Pair<String, Int?> {
    val fields = rawKey.split(',').map { it.trim() }
    val legacyLine = if (fields.size == 5) fields[2].toIntOrNull() else null
    return if (legacyLine != null) {
      "${fields[0]},${fields[1]},${fields[3]},${fields[4]}" to legacyLine
    } else {
      fields.joinToString(",") to null
    }
  }

  /**
   * A line whose key part cannot be a baseline row: after stripping the trailing
   * `# line` tag and the note, the coordinate must be `class,method,mutator,STATUS`
   * or the legacy five-field form with a numeric line third, every field non-empty.
   * A malformed row parses into a key that matches no mutant, reads as an unmatched
   * removal candidate, and is then silently dropped by the next refresh — diagnosing
   * the shape is the same job the timeout membership's malformed-row check does, so
   * the two files cannot disagree on whether the tool argues with a row or quietly
   * ignores it.
   */
  fun malformed(line: String): Boolean {
    val tagMatch = LINE_TAG.find(line)
    val beforeTag = if (tagMatch == null) line else line.substring(0, tagMatch.range.first)
    val hash = beforeTag.indexOf('#')
    val rawKey = (if (hash < 0) beforeTag else beforeTag.substring(0, hash)).trim()
    val fields = rawKey.split(',').map { it.trim() }
    return when (fields.size) {
      4 -> fields.any { it.isEmpty() }
      5 -> fields[2].toIntOrNull() == null || fields.any { it.isEmpty() }
      else -> true
    }
  }

  /** The trailing `# line` tag for [lines], or the empty string for none. */
  fun renderLineTag(lines: Collection<Int>): String {
    if (lines.isEmpty()) return ""
    val sorted = lines.toSortedSet()
    return " # line${if (sorted.size > 1) "s" else ""} ${sorted.joinToString(", ")}"
  }

  /** The written form of a row: key, note, line tag — the current format. */
  fun render(key: String, note: String?, lines: Collection<Int>): String =
      key + (note?.let { " $it" } ?: "") + renderLineTag(lines)

  fun render(row: Row): String = render(row.key, row.note, row.recordedLines)

  /**
   * The line-drift check, row-level where the data supports it: for each key in
   * [observed] (its unkilled mutants' lines, one entry per mutant), the recorded
   * side is the union of that key's rows' `# line` tags. When every row of the key
   * carries a tag AND the observed count equals the row count, any observed line
   * outside the recorded set is reported — the baseline's multiset already fails a
   * genuinely new sibling as a count change, so the timeout audit's
   * quiet-on-new-sibling resolution is not needed here, and an unrecorded line
   * under matched counts is exactly a moved anchor or a same-key swap. With
   * partial tags or skewed counts the check falls back to the audit's key-level
   * disjointness (the count skew is already failing the build or printing the
   * prune-candidate preview; double-reporting it as drift would misname it).
   *
   * Returns key -> (recorded lines, unmatched observed lines); keys with no
   * recorded lines never take part.
   */
  fun lineDrift(
    rows: List<Row>,
    observed: Map<String, List<Int>>,
  ): Map<String, Pair<Set<Int>, Set<Int>>> {
    val byKey = rows.groupBy { it.key }
    return observed.entries.mapNotNull { (key, observedLines) ->
      val keyRows = byKey[key] ?: return@mapNotNull null
      val recorded = keyRows.flatMap { it.recordedLines }.toSet()
      if (recorded.isEmpty() || observedLines.isEmpty()) return@mapNotNull null
      val rowLevel = keyRows.all { it.recordedLines.isNotEmpty() } && observedLines.size == keyRows.size
      val unmatched = if (rowLevel) {
        observedLines.filterNot { it in recorded }.toSet()
      } else {
        // the audited-timeout resolution, literally shared so the two advisories
        // cannot drift apart on what this fallback means
        TimeoutAudit.disjointDrift(recorded, observedLines.toSet())
      }
      if (unmatched.isEmpty()) null else key to (recorded to unmatched)
    }.toMap()
  }

  /** The family label of a note: `# race guard (carried across …)` -> `race guard`. */
  fun labelOf(note: String): String =
      note.removePrefix("#").trim().substringBefore(" (").trim()

  /** The label a refresh seeds on a new row; it argues nothing, so it needs no section. */
  private const val UNTRIAGED = "untriaged"

  /**
   * The family labels in [notes] with no `# <label>` mention in the module's
   * `config/pitest/README.md`, in first-seen order. A label is a pointer to its
   * argument: a typo silently opens a bucket of its own and a deleted section orphans
   * the rows that cite it, and neither is visible in a per-label count — `3 '# race
   * gaurd family'` reads like triage. [readme] supplies that file's text and is called
   * only when there is a label to resolve, so a baseline of unlabeled or `# untriaged`
   * rows costs no read. Owned here rather than at the call sites so the verify and
   * `Debt` can never disagree about which labels resolve.
   */
  fun undocumentedLabels(notes: List<String>, readme: () -> String): List<String> {
    val labels = notes.map { labelOf(it) }.distinct().filter { it != UNTRIAGED }
    if (labels.isEmpty()) return emptyList()
    val text = readme()
    return labels.filterNot { text.contains("# $it") }
  }

  /** The warning naming [undocumented] labels; callers pass a non-empty list. */
  fun undocumentedLabelWarning(suiteName: String, undocumented: Collection<String>): String =
      "pitest baseline '$suiteName': label(s) with no argument in config/pitest/README.md — " +
          undocumented.joinToString(", ") { "'# $it'" } +
          " — document the family there, or fix the label if it is a typo"

  /**
   * A per-label count summary — `13 '# untriaged', 20 '# race guard family', 5
   * unlabeled` — one count per family label sorted by descending count, unlabeled rows
   * named last. Returns null only when the baseline is empty (no notes, no rows), so a
   * baseline that is entirely pre-seeding still prints `N unlabeled` rather than nothing.
   * [notes] holds one entry per labeled row (leading `#` included); [unlabeled] counts
   * the rows with no note.
   */
  fun summarize(notes: List<String>, unlabeled: Int): String? {
    if (notes.isEmpty() && unlabeled == 0) return null
    val labelPart = notes.groupingBy { labelOf(it) }.eachCount().entries
        .sortedByDescending { it.value }
        .joinToString(", ") { (label, count) -> "$count '# $label'" }
    val unlabeledPart = if (unlabeled == 0) "" else "$unlabeled unlabeled"
    return listOf(labelPart, unlabeledPart).filter { it.isNotEmpty() }.joinToString(", ")
  }
}
