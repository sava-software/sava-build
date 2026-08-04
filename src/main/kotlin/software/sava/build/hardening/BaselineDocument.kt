package software.sava.build.hardening

/**
 * One accepted-baseline document, including the material around its mutant rows.
 *
 * The unversioned form is the N-1 input format: it may contain either legacy
 * five-field rows or the current line-less rows understood by [BaselineNotes]. The
 * explicit current form starts with [CURRENT_HEADER]. The marker is deliberately a
 * non-comment line: an N-1 reader diagnoses it as malformed instead of silently
 * ignoring a schema it cannot understand.
 *
 * Entries retain source order and spelling. In particular, duplicate rows remain
 * duplicate rows, while comment, blank, and malformed lines are represented rather
 * than discarded. Callers that rewrite rows should use
 * [rewriteRowsPreservingNonRows]; choosing a row-only renderer is therefore an
 * explicit decision rather than the document API's default.
 */
internal class BaselineDocument private constructor(
  val schemaState: SchemaState,
  val schemaMetadata: SchemaMetadata?,
  val entries: List<Entry>,
  private val original: String,
  private val lineSeparator: String,
  private val terminatedWithNewline: Boolean,
) {

  enum class SchemaState {
    /** The unmarked form read by the previous release. */
    UNVERSIONED_N_MINUS_ONE,

    /** The explicitly marked schema emitted by [renderCurrent]. */
    CURRENT,
  }

  data class SchemaMetadata(
    val version: String,
    val raw: String,
    val lineNumber: Int,
  )

  sealed interface Entry {
    val raw: String
    val lineNumber: Int

    /** A well-formed accepted-mutant row. */
    data class Row(
      val value: BaselineNotes.Row,
      override val raw: String,
      override val lineNumber: Int,
    ) : Entry

    /** A whole-line `#` comment, including any indentation and spelling. */
    data class Comment(
      override val raw: String,
      override val lineNumber: Int,
    ) : Entry

    /** An empty or whitespace-only line, retained verbatim. */
    data class Blank(
      override val raw: String,
      override val lineNumber: Int,
    ) : Entry

    /**
     * A non-comment line that is not a baseline row. Existing checks diagnose these
     * separately; retaining it here prevents a migration or rewrite from silently
     * deleting evidence it did not understand.
     */
    data class MalformedRow(
      override val raw: String,
      override val lineNumber: Int,
    ) : Entry
  }

  data class Transition(
    val content: String,
    val from: SchemaState,
    val to: SchemaState,
    val changed: Boolean,
    val canonicalizedRows: Int,
  )

  val rowEntries: List<Entry.Row> = entries.filterIsInstance<Entry.Row>()
  val rows: List<BaselineNotes.Row> = rowEntries.map { it.value }
  val comments: List<Entry.Comment> = entries.filterIsInstance<Entry.Comment>()
  val blankLines: List<Entry.Blank> = entries.filterIsInstance<Entry.Blank>()
  val malformedRows: List<Entry.MalformedRow> = entries.filterIsInstance<Entry.MalformedRow>()

  /** The source text exactly as parsed, useful for no-op detection. */
  fun renderOriginal(): String = original

  /**
   * Adds the current schema marker and canonicalizes valid rows through
   * [BaselineNotes], while preserving every comment and blank line in its original
   * position. A malformed row prevents the migration: carrying it into a marked
   * document would make the schema claim stronger than the contents.
   */
  fun migrateToCurrent(): Transition {
    requireNoMalformedRows("migrate to accepted-baseline schema $CURRENT_SCHEMA")
    val canonicalized = rowEntries.count { BaselineNotes.render(it.value) != it.raw }
    val content = render(
        SchemaState.CURRENT,
        entries.map { entry ->
          if (entry is Entry.Row) BaselineNotes.render(entry.value) else entry.raw
        })
    return Transition(
        content = content,
        from = schemaState,
        to = SchemaState.CURRENT,
        changed = content != original,
        canonicalizedRows = canonicalized,
    )
  }

  /** The explicit current-schema text; equivalent to [migrateToCurrent]'s content. */
  fun renderCurrent(): String = migrateToCurrent().content

  /**
   * Removes only the schema marker. All other lines keep their source spelling and
   * order, providing the rollback artifact readable by the N-1 plugin.
   */
  fun downgradeToUnversioned(): Transition {
    val content = render(SchemaState.UNVERSIONED_N_MINUS_ONE, entries.map { it.raw })
    return Transition(
        content = content,
        from = schemaState,
        to = SchemaState.UNVERSIONED_N_MINUS_ONE,
        changed = content != original,
        canonicalizedRows = 0,
    )
  }

  /** The N-1 rollback text; equivalent to [downgradeToUnversioned]'s content. */
  fun renderDowngraded(): String = downgradeToUnversioned().content

  /**
   * Replaces row slots in order, preserving every non-row entry verbatim. Additional
   * rows are inserted after the last original row; if there was no row, they follow
   * the existing comments and blank lines. Fewer replacements remove only row slots.
   * The replacement list remains a list, so same-key siblings are never deduplicated.
   *
   * [targetSchema] defaults to the document's current state. This lets reader-capable
   * releases preserve unversioned files until the deliberate fleet migration, while
   * writers keep an already-migrated document explicitly versioned.
   */
  fun rewriteRowsPreservingNonRows(
    replacementRows: List<BaselineNotes.Row>,
    targetSchema: SchemaState = schemaState,
  ): String {
    if (targetSchema == SchemaState.CURRENT) {
      requireNoMalformedRows("write accepted-baseline schema $CURRENT_SCHEMA")
    }
    val replacements = replacementRows.map(BaselineNotes::render)
    val lastRow = entries.indexOfLast { it is Entry.Row }
    var replacementIndex = 0
    val rewritten = buildList {
      entries.forEachIndexed { entryIndex, entry ->
        if (entry is Entry.Row) {
          if (replacementIndex < replacements.size) add(replacements[replacementIndex++])
          if (entryIndex == lastRow) {
            while (replacementIndex < replacements.size) add(replacements[replacementIndex++])
          }
        } else {
          add(entry.raw)
        }
      }
      if (lastRow < 0) addAll(replacements)
    }
    return render(targetSchema, rewritten)
  }

  private fun requireNoMalformedRows(operation: String) {
    require(malformedRows.isEmpty()) {
      val detail = malformedRows.joinToString(", ") { "line ${it.lineNumber}" }
      "cannot $operation: malformed baseline row(s) at $detail"
    }
  }

  private fun render(targetSchema: SchemaState, contentLines: List<String>): String {
    val lines = buildList {
      if (targetSchema == SchemaState.CURRENT) add(CURRENT_HEADER)
      addAll(contentLines)
    }
    if (lines.isEmpty()) return ""
    val content = lines.joinToString(lineSeparator)
    return if (terminatedWithNewline) content + lineSeparator else content
  }

  companion object {
    const val CURRENT_SCHEMA = "1"
    const val HEADER_NAME = "!sava-hardening-baseline-schema"
    const val CURRENT_HEADER = "$HEADER_NAME,$CURRENT_SCHEMA"

    private val HEADER = Regex("""^${Regex.escape(HEADER_NAME)},([^,\s]+)$""")

    fun parse(text: String): BaselineDocument {
      val lineSeparator = when {
        "\r\n" in text -> "\r\n"
        '\r' in text && '\n' !in text -> "\r"
        else -> "\n"
      }
      val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
      val terminatedWithNewline = normalized.endsWith('\n')
      val lines = if (normalized.isEmpty()) {
        emptyList()
      } else {
        val split = normalized.split('\n')
        if (terminatedWithNewline) split.dropLast(1) else split
      }

      var metadata: SchemaMetadata? = null
      val entries = buildList {
        lines.forEachIndexed { index, line ->
          val lineNumber = index + 1
          val trimmed = line.trim()
          if (trimmed.startsWith(HEADER_NAME)) {
            val match = HEADER.matchEntire(trimmed)
            require(match != null) {
              "malformed accepted-baseline schema header at line $lineNumber: '$line'"
            }
            val version = match.groupValues[1]
            require(version == CURRENT_SCHEMA) {
              "unsupported accepted-baseline schema '$version' at line $lineNumber " +
                  "(expected '$CURRENT_SCHEMA')"
            }
            require(metadata == null) {
              "duplicate accepted-baseline schema header at line $lineNumber " +
                  "(first declared at line ${metadata!!.lineNumber})"
            }
            require(lineNumber == 1) {
              "accepted-baseline schema header must be the first line, was line $lineNumber"
            }
            metadata = SchemaMetadata(version, line, lineNumber)
          } else {
            add(when {
              line.isBlank() -> Entry.Blank(line, lineNumber)
              line.trimStart().startsWith("#") -> Entry.Comment(line, lineNumber)
              BaselineNotes.malformed(line) -> Entry.MalformedRow(line, lineNumber)
              else -> Entry.Row(BaselineNotes.parse(line), line, lineNumber)
            })
          }
        }
      }
      return BaselineDocument(
          schemaState = if (metadata == null) {
            SchemaState.UNVERSIONED_N_MINUS_ONE
          } else {
            SchemaState.CURRENT
          },
          schemaMetadata = metadata,
          entries = entries,
          original = text,
          lineSeparator = lineSeparator,
          terminatedWithNewline = terminatedWithNewline,
      )
    }
  }
}
