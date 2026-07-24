package software.sava.build.hardening

/**
 * The single place that knows a baseline row's `# note` format, so the accepted-row
 * parser and the verify / `Debt` per-label breakdowns can never drift on where a note
 * begins or how a family label is read out of one.
 *
 * A baseline line is `<coordinate> [# <label> [(<carry/flip detail>)]]`. The note
 * begins at the first `#`; the family label is that note with the leading `#` and any
 * trailing parenthetical stripped, so `# race guard (carried across …)` reads as
 * `race guard` — the parenthetical is a carry marker, not part of the label.
 */
internal object BaselineNotes {

  /** The note portion of a line (leading `#` included, trimmed), or null if unlabeled. */
  fun noteOf(line: String): String? {
    val hash = line.indexOf('#')
    return if (hash < 0) null else line.substring(hash).trim()
  }

  /** The coordinate portion of a line, with any note stripped. */
  fun rowOf(line: String): String {
    val hash = line.indexOf('#')
    return (if (hash < 0) line else line.substring(0, hash)).trim()
  }

  /** The family label of a note: `# race guard (carried across …)` -> `race guard`. */
  fun labelOf(note: String): String =
      note.removePrefix("#").trim().substringBefore(" (").trim()

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
