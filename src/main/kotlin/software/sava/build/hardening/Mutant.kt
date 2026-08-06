package software.sava.build.hardening

/**
 * PIT's status vocabulary with the three independent semantics the ratchet
 * reasons about, encoded ONCE. "Detected" is deliberately NARROWER than PIT's own
 * scoring: only KILLED and TIMED_OUT (a timed-out mutant was caught,
 * load-dependently), where PIT also scores NON_VIABLE and EQUIVALENT as detected.
 * This plugin reports those terminal outcomes without scoring them, so its percent
 * can sit below PIT's summary. PIT also scores error statuses, but this plugin
 * refuses them as incomplete evidence instead of calculating a percentage from a
 * failed experiment.
 * "Gated" is the ratchet's unkilled population — the statuses a baseline row may
 * carry. [validEvidence] is deliberately independent of both: NON_VIABLE and
 * EQUIVALENT are completed, interpretable outcomes even though the plugin does
 * not score them as detected, while an error or unfinished status cannot certify
 * a report or justify shrinking a baseline.
 *
 * A status this enum does not know is a PIT upgrade talking past the plugin —
 * [Mutant.status] reads null and [Mutant.parseReport] refuses the report, because
 * the old behavior (an unknown string silently falling into "neither" buckets at
 * every call site independently) is exactly the misinterpretation class this
 * type exists to end.
 */
internal enum class MutantStatus(
  val detected: Boolean,
  val gated: Boolean,
  val validEvidence: Boolean,
) {
  KILLED(detected = true, gated = false, validEvidence = true),
  TIMED_OUT(detected = true, gated = false, validEvidence = true),
  SURVIVED(detected = false, gated = true, validEvidence = true),
  NO_COVERAGE(detected = false, gated = true, validEvidence = true),
  // PIT scores these as detected. The hardening percentage intentionally does
  // not, but they are terminal outcomes and therefore valid report evidence.
  NON_VIABLE(detected = false, gated = false, validEvidence = true),
  EQUIVALENT(detected = false, gated = false, validEvidence = true),
  // PIT's detected flag is not an evidence-validity flag: these outcomes say the
  // mutation experiment failed or never completed, not that a test proved it.
  MEMORY_ERROR(detected = false, gated = false, validEvidence = false),
  RUN_ERROR(detected = false, gated = false, validEvidence = false),
  NOT_STARTED(detected = false, gated = false, validEvidence = false),
  STARTED(detected = false, gated = false, validEvidence = false);

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
  val validEvidence: Boolean get() = status?.validEvidence == true
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

    /**
     * Parses a complete PIT report, failing closed on a row or status that cannot
     * certify the mutation population. [parse] stays nullable for single-row callers;
     * a report must never silently turn an unparsable physical row into a smaller
     * mutant population.
     */
    fun parseReport(csvLines: List<String>): List<Mutant> {
      val malformed = mutableListOf<Pair<Int, String>>()
      val parsed = csvLines.mapIndexedNotNull { index, line ->
        val mutant = parse(line)
        if (mutant == null || mutant.sourceFile.isBlank() || mutant.className.isBlank() ||
            mutant.mutator.isBlank() || mutant.method.isBlank() || mutant.line == null ||
            mutant.rawStatus.isBlank()) {
          malformed.add(index + 1 to line)
          null
        } else {
          Triple(index + 1, line, mutant)
        }
      }
      if (malformed.isNotEmpty()) {
        throw IllegalArgumentException(
            "PIT report contains ${malformed.size} malformed CSV row(s); an incomplete " +
                "population is not evidence:\n" +
                malformed.joinToString("\n") { (lineNumber, line) -> "  line $lineNumber: $line" }
        )
      }
      val invalid = parsed.filterNot { (_, _, mutant) -> mutant.validEvidence }
      if (invalid.isNotEmpty()) {
        val statuses = invalid.groupingBy { (_, _, mutant) -> mutant.rawStatus }.eachCount().entries
            .sortedBy { it.key }
            .joinToString(", ") { (status, count) -> "$status x$count" }
        val runErrorHint = if (invalid.any { (_, _, mutant) -> mutant.rawStatus == "RUN_ERROR" }) {
          "\nFor RUN_ERROR, inspect PIT's preceding output before re-running. If PIT says a " +
              "minion failed to start/died or reports insufficient memory, that is process " +
              "failure rather than a mutant verdict; reduce suite threads or configure " +
              "the suite's evidence-bound minionJvmArgs (for example, -Xmx1g)."
        } else ""
        throw IllegalArgumentException(
            "PIT report contains status(es) that are not valid completed evidence: $statuses:\n" +
                invalid.joinToString("\n") { (lineNumber, line, _) ->
                  "  line $lineNumber: $line"
                } + "\n" +
                "Runtime errors, unfinished mutations, and unknown PIT statuses cannot certify " +
                "the ratchet or any record writer." + runErrorHint
        )
      }
      return parsed.map { (_, _, mutant) -> mutant }
    }
  }
}
