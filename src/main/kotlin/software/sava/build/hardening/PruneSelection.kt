package software.sava.build.hardening

/**
 * A reviewed subset of prune candidates, addressed only by line-less acceptance
 * keys. Selecting a key retires all of its accepted rows or refuses: notes and
 * diagnostic line tags never select one same-key sibling over another.
 *
 * This is a narrowing plan, not deletion authority. The caller must still prove
 * the full population, provenance, and matching fresh preview sequence, binding
 * the exact selector-file bytes to that sequence and its final write boundary.
 */
internal class PruneSelection private constructor(val keys: Set<String>) {

  data class Plan(
    val removedRowIndices: List<Int>,
    val retainedRowIndices: List<Int>,
  )

  fun plan(
    acceptedRows: List<BaselineNotes.Row>,
    keepPlan: List<BaselineEngine.Disposition>,
  ): Plan {
    require(acceptedRows.size == keepPlan.size) {
      "prune selection has ${acceptedRows.size} accepted rows but ${keepPlan.size} dispositions"
    }
    val rowsByKey = acceptedRows.indices.groupBy { acceptedRows[it].key }
    val missing = keys.filter { it !in rowsByKey }
    require(missing.isEmpty()) {
      "prune selection names keys absent from the accepted baseline:\n" +
          missing.joinToString("\n") { "  $it" }
    }
    val protectedKeys = keys.filter { key ->
      rowsByKey.getValue(key).any { keepPlan[it] != BaselineEngine.Disposition.DROP }
    }
    require(protectedKeys.isEmpty()) {
      "prune selection refuses keys whose accepted rows are not all eligible candidates; " +
          "same-key sibling retirement is ambiguous and labels or line tags are not mutant identity:\n" +
          protectedKeys.joinToString("\n") { key ->
            val indices = rowsByKey.getValue(key)
            val candidates = indices.count { keepPlan[it] == BaselineEngine.Disposition.DROP }
            "  $key: $candidates candidate row(s), ${indices.size - candidates} protected row(s)"
          }
    }
    return Plan(
        removedRowIndices = acceptedRows.indices.filter { acceptedRows[it].key in keys },
        retainedRowIndices = acceptedRows.indices.filter { acceptedRows[it].key !in keys },
    )
  }

  companion object {
    /**
     * One unique canonical `class,method,mutator,STATUS` key per line. Blanks and
     * whole-line comments are allowed; row notes, line tags, globs, legacy line
     * fields, counts, and duplicate keys are not a sibling-selection language.
     */
    fun parse(text: String): PruneSelection {
      val keys = sortedSetOf<String>()
      text.lineSequence().forEachIndexed { index, raw ->
        val key = raw.trim()
        if (key.isEmpty() || key.startsWith('#')) return@forEachIndexed
        val fields = key.split(',')
        require(fields.size == 4 && fields.all { field ->
          field.isNotEmpty() && field.none { it.isWhitespace() || it.isISOControl() || it in "#*?" }
        }) {
          "invalid prune selection at line ${index + 1}: expected a bare canonical " +
              "class,method,mutator,STATUS key; labels, line tags, globs, and line-full rows are refused"
        }
        require(MutantStatus.of(fields[3])?.gated == true) {
          "invalid prune selection status '${fields[3]}' at line ${index + 1}; " +
              "only accepted-baseline SURVIVED or NO_COVERAGE keys may be selected"
        }
        require(keys.add(key)) {
          "duplicate prune selection key at line ${index + 1}: $key; " +
              "a key selects all its eligible accepted rows, not a sibling count"
        }
      }
      require(keys.isNotEmpty()) {
        "prune selection is empty; name at least one reviewed line-less key " +
            "(an empty selection never means prune every candidate)"
      }
      return PruneSelection(keys.toSet())
    }
  }
}
