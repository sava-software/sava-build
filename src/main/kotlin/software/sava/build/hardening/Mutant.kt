package software.sava.build.hardening

/**
 * PIT's status vocabulary with the two semantic partitions the ratchet reasons
 * about, encoded ONCE. "Detected" is PIT's own accounting (a timed-out mutant was
 * caught, load-dependently); "gated" is the ratchet's unkilled population — the
 * statuses a baseline row may carry. Everything else (errors, non-viable,
 * scheduling states) is neither: it lowers the detected count and the summary
 * names it, but it can never enter a baseline or claim a keep.
 *
 * A status this enum does not know is a PIT upgrade talking past the plugin —
 * [Mutant.status] reads null and the verify names it loudly, because the old
 * behavior (an unknown string silently falling into "neither" buckets at every
 * call site independently) is exactly the misinterpretation class this type
 * exists to end.
 */
internal enum class MutantStatus(val detected: Boolean, val gated: Boolean) {
  KILLED(detected = true, gated = false),
  TIMED_OUT(detected = true, gated = false),
  SURVIVED(detected = false, gated = true),
  NO_COVERAGE(detected = false, gated = true),
  NON_VIABLE(detected = false, gated = false),
  MEMORY_ERROR(detected = false, gated = false),
  RUN_ERROR(detected = false, gated = false),
  NOT_STARTED(detected = false, gated = false),
  STARTED(detected = false, gated = false);

  companion object {
    fun of(raw: String): MutantStatus? = entries.firstOrNull { it.name == raw }
  }
}

/**
 * One mutant observation from PIT's CSV report — the single place its columns are
 * split and its identities derived. The fleet casebook's recurring incident class
 * is a mutant interpreted differently at two sites (line-in-identity vs metadata,
 * sets vs multisets, per-site status strings, hand-rebuilt keys); every consumer
 * now reads the SAME fields and the SAME key derivations:
 *
 *  - [coordinate] — line-less `class,method,mutatorSimpleName`: the audited-timeout
 *    key and the keep plan's coordinate. Line numbers are metadata, not identity.
 *  - [baselineKey] — `coordinate,STATUS`: the accepted-baseline row key.
 *  - [lineFullKey] — `class,method,line,mutatorSimpleName`: pitestConverge's key,
 *    deliberately line-FULL — both rounds run identical code, so lines cannot
 *    churn there and the finer key localizes a flip to the exact mutant.
 *  - [familyLineKey] — `class,method,line,mutatorFamily`: the sibling-hint key,
 *    matching a survivor to detected same-line relatives across a mutator
 *    family's IF/ELSE-style suffix pair.
 *
 * [lineText] stays raw: line affinity compares observed text against recorded
 * tags, and an unparsable field must flow through unchanged, not become a zero.
 * The killing-test column can itself contain commas, so [killerText] is
 * everything after the status column rejoined — never index from the end.
 */
internal data class Mutant(
  val sourceFile: String,
  val className: String,
  val mutator: String,
  val method: String,
  val lineText: String,
  val rawStatus: String,
  val killerText: String,
) {
  val status: MutantStatus? get() = MutantStatus.of(rawStatus)
  val detected: Boolean get() = status?.detected == true
  val gated: Boolean get() = status?.gated == true
  val line: Int? get() = lineText.toIntOrNull()
  val mutatorSimpleName: String get() = mutator.substringAfterLast('.')

  /** The name before the `_EQUAL_IF`/`_ORDER_ELSE` style suffix, so cross-pairs match. */
  val mutatorFamily: String get() = mutatorSimpleName.substringBefore('_')

  val coordinate: String get() = "$className,$method,$mutatorSimpleName"
  val baselineKey: String get() = "$coordinate,$rawStatus"
  val lineFullKey: String get() = "$className,$method,$lineText,$mutatorSimpleName"
  val familyLineKey: String get() = "$className,$method,$lineText,$mutatorFamily"

  companion object {
    /** CSV columns: file,class,mutator,method,line,status[,killingTest...]. */
    fun parse(csvLine: String): Mutant? {
      val parts = csvLine.split(',')
      if (parts.size < 6) return null
      return Mutant(
        sourceFile = parts[0],
        className = parts[1],
        mutator = parts[2],
        method = parts[3],
        lineText = parts[4],
        rawStatus = parts[5],
        killerText = parts.drop(6).joinToString(","),
      )
    }

    fun parseReport(csvLines: List<String>): List<Mutant> = csvLines.mapNotNull(::parse)
  }
}
