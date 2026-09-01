package software.sava.build.hardening

/**
 * The last completed prune-candidate preview for one project-local PIT suite.
 *
 * Candidate rows are a sorted multiset: identical siblings remain separate entries.
 * The format is deliberately strict because this machine-local state participates in
 * a later baseline-writer refusal. A format bump is the compatibility fence whenever
 * candidate classification or canonical row rendering changes.
 */
internal data class PrunePreviewState(
  val inputIdentitySha256: String,
  val mutationRecordFingerprint: String,
  val lastInvocationId: String,
  val matchingObservations: Int,
  val qualifies: Boolean,
  val candidates: List<String>,
) {

  init {
    require(SHA256.matches(inputIdentitySha256)) { "invalid prune-preview input identity" }
    require(SHA256.matches(mutationRecordFingerprint)) {
      "invalid prune-preview mutation-record fingerprint"
    }
    require(lastInvocationId.isNotBlank() && lastInvocationId.none { it == '\n' || it == '\r' }) {
      "invalid prune-preview invocation id"
    }
    require(matchingObservations >= 0) { "negative prune-preview observation count" }
    require((qualifies && matchingObservations >= 1) || (!qualifies && matchingObservations == 0)) {
      "eligible prune-preview observation state must have observations; " +
          "ineligible state must have none"
    }
    require(candidates == candidates.sorted()) { "prune-preview candidates are not sorted" }
    require(candidates.all { it.isNotBlank() && '\n' !in it && '\r' !in it && !it.startsWith("#") }) {
      "invalid prune-preview candidate row"
    }
  }

  fun render(): String = buildString {
    appendLine(FORMAT_HEADER)
    appendLine("# inputs $inputIdentitySha256")
    appendLine("# records $mutationRecordFingerprint")
    appendLine("# last invocation $lastInvocationId")
    appendLine("# matching observations $matchingObservations")
    appendLine("$OBSERVATION_ELIGIBLE_PREFIX$qualifies")
    candidates.forEach(::appendLine)
  }

  companion object {
    // Format 2 is the observation-origin compatibility fence. Format 1 could count
    // routine certification observations toward the destructive prune prerequisite,
    // so its otherwise matching machine-local state must restart under the writer
    // that distinguishes deliberate history-free previews from certification.
    const val FORMAT_HEADER = "# prune preview format 2"
    internal const val LEGACY_FORMAT_1_HEADER = "# prune preview format 1"
    internal const val OBSERVATION_ELIGIBLE_PREFIX = "# observation eligible "
    private const val LEGACY_QUALIFIES_PREFIX = "# qualifies "
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun parse(text: String): PrunePreviewState = parse(text, FORMAT_HEADER)

    /**
     * Format 1 is read only at the format-2 migration boundary. Its strict fields,
     * especially the last invocation id, let the transition kernel refuse to count
     * a typed revalidation of the same pre-upgrade report as a new preview.
     */
    internal fun parseLegacyFormat1(text: String): PrunePreviewState =
      parse(text, LEGACY_FORMAT_1_HEADER)

    private fun parse(text: String, expectedHeader: String): PrunePreviewState {
      val lines = text.lines().let { all ->
        if (all.lastOrNull().isNullOrEmpty()) all.dropLast(1) else all
      }
      require(lines.size >= 6) { "prune-preview state has fewer than six header lines" }
      require(lines[0] == expectedHeader) { "unsupported prune-preview format" }

      fun field(index: Int, prefix: String): String {
        val line = lines[index]
        require(line.startsWith(prefix)) { "prune-preview line ${index + 1} must start '$prefix'" }
        return line.removePrefix(prefix)
      }

      val observations = field(4, "# matching observations ").toIntOrNull()
          ?: throw IllegalArgumentException("invalid prune-preview observation count")
      val eligibilityPrefix = if (expectedHeader == LEGACY_FORMAT_1_HEADER) {
        LEGACY_QUALIFIES_PREFIX
      } else {
        OBSERVATION_ELIGIBLE_PREFIX
      }
      val qualifiesText = field(5, eligibilityPrefix)
      require(qualifiesText == "true" || qualifiesText == "false") {
        "prune-preview observation eligibility must be true or false"
      }
      return PrunePreviewState(
          inputIdentitySha256 = field(1, "# inputs "),
          mutationRecordFingerprint = field(2, "# records "),
          lastInvocationId = field(3, "# last invocation "),
          matchingObservations = observations,
          qualifies = qualifiesText.toBooleanStrict(),
          candidates = lines.drop(6),
      )
    }
  }
}

internal data class PrunePreviewObservation(
  val inputIdentitySha256: String,
  val mutationRecordFingerprint: String,
  val invocationId: String,
  val qualifies: Boolean,
  val candidates: List<String>,
) {
  fun initialState(): PrunePreviewState = PrunePreviewState(
      inputIdentitySha256,
      mutationRecordFingerprint,
      invocationId,
      if (qualifies) 1 else 0,
      qualifies,
      candidates.sorted(),
  )

  /** A reset which deliberately does not count the current report as an observation. */
  fun uncountedState(): PrunePreviewState = PrunePreviewState(
      inputIdentitySha256,
      mutationRecordFingerprint,
      invocationId,
      0,
      qualifies = false,
      candidates = candidates.sorted(),
  )
}

internal enum class PrunePreviewTransitionKind {
  FIRST,
  MALFORMED_RESET,
  INPUT_RESET,
  RECORD_RESET,
  INELIGIBLE_RESET,
  AFTER_INELIGIBLE,
  SAME_INVOCATION,
  MATCHED,
  MISMATCH,
}

internal data class PrunePreviewTransition(
  val kind: PrunePreviewTransitionKind,
  val state: PrunePreviewState,
  val previous: PrunePreviewState?,
  val added: List<String> = emptyList(),
  val removed: List<String> = emptyList(),
  /** Two qualifying previews were already complete before this observation began. */
  val writerAuthorized: Boolean = false,
  val malformedDetail: String? = null,
)

/** Pure transition kernel for the persisted prune-preview sequence. */
internal object PrunePreviewHistory {

  fun observe(
    previousText: String?,
    observation: PrunePreviewObservation,
  ): PrunePreviewTransition {
    val current = observation.copy(candidates = observation.candidates.sorted())
    val parsed = if (previousText == null) null else try {
      PrunePreviewState.parse(previousText)
    } catch (failure: IllegalArgumentException) {
      val legacy = try {
        PrunePreviewState.parseLegacyFormat1(previousText)
      } catch (_: IllegalArgumentException) {
        null
      }
      // A valid format-1 state still carries the report invocation which last
      // advanced it. If the current evidence has that same id, this is merely a
      // typed revalidation of the old completed report, not a new preview. Migrate
      // it to format 2 without counting it. A distinct qualifying invocation may
      // seed observation one. Malformed state cannot prove either fact and resets
      // fail-closed to zero.
      val resetState = if (legacy != null &&
          legacy.lastInvocationId != current.invocationId) {
        current.initialState()
      } else {
        current.uncountedState()
      }
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.MALFORMED_RESET,
          resetState,
          null,
          malformedDetail = failure.message,
      )
    }

    if (parsed == null) {
      return PrunePreviewTransition(PrunePreviewTransitionKind.FIRST, current.initialState(), null)
    }
    // Invocation identity defines one PIT observation. Re-reading it must never
    // become a new observation merely because the surrounding input or reviewed
    // record bytes changed; the next distinct completed PIT invocation owns any
    // resulting reset and may seed the new sequence. Keep this before every reset.
    if (parsed.lastInvocationId == current.invocationId) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.SAME_INVOCATION, parsed, parsed)
    }
    if (parsed.inputIdentitySha256 != current.inputIdentitySha256) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.INPUT_RESET, current.initialState(), parsed)
    }
    if (parsed.mutationRecordFingerprint != current.mutationRecordFingerprint) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.RECORD_RESET, current.initialState(), parsed)
    }
    if (!current.qualifies) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.INELIGIBLE_RESET, current.initialState(), parsed)
    }
    if (!parsed.qualifies) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.AFTER_INELIGIBLE, current.initialState(), parsed)
    }
    val added = BaselineEngine.multisetDiff(current.candidates, parsed.candidates)
    val removed = BaselineEngine.multisetDiff(parsed.candidates, current.candidates)
    if (added.isNotEmpty() || removed.isNotEmpty()) {
      return PrunePreviewTransition(
          PrunePreviewTransitionKind.MISMATCH,
          current.initialState(),
          parsed,
          added = added,
          removed = removed,
      )
    }

    return PrunePreviewTransition(
        PrunePreviewTransitionKind.MATCHED,
        PrunePreviewState(
            current.inputIdentitySha256,
            current.mutationRecordFingerprint,
            current.invocationId,
            parsed.matchingObservations + 1,
            qualifies = true,
            candidates = current.candidates,
        ),
        parsed,
        writerAuthorized = parsed.matchingObservations >= 2,
    )
  }
}
