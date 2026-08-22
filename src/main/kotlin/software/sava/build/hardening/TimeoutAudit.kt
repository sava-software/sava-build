package software.sava.build.hardening

/**
 * The single place that knows the audited-timeout membership file's row format
 * (`config/pitest/<suite>-timeouts.csv`: line-less `class,method,mutator` keys with
 * backward-compatible `# cause:<category>` and diagnostic line metadata), so the verify
 * and `pitest<Suite>Debt` can never drift on what parses, what is malformed, which
 * cause classes are admissible, or which README causes resolve.
 *
 * This is intentionally not an accepted-baseline [BaselineDocument]. Timeout audit
 * sets were introduced with this stable line-less three-field identity; unlike the
 * accepted baseline, they never had a five-field-to-line-less transition that an N-1
 * reader could misinterpret. Reusing the accepted-baseline marker would therefore
 * invent a format break rather than guard one. Any future incompatible timeout-set
 * change needs its own marker, N-1 reader, migration, and rollback contract.
 *
 * The audit's static parsing and cause checks live here, which is what lets `Debt`
 * answer "does the tool agree with my pasted row and my README cause?" in seconds
 * instead of after the next mutation run. The shared [reportFindings] projection
 * also keeps the normal verifier and its pre-provenance-refusal diagnostic path on
 * one interpretation of unaudited/stale members. Stateful quiet streaks stay in the
 * verifier. `# line` comments deliberately do
 * not authorize or identify a mutant: source formatting and unrelated insertions
 * must not invalidate an audited cause. PIT's CSV cannot provide a stable finer
 * identity, so same-key siblings remain an explicit limitation until a separate,
 * versioned semantic-anchor format exists.
 */
internal object TimeoutAudit {

  /**
   * A timeout row's reviewed explanation class. Only [LIVENESS] is admissible as
   * watchdog detection: [RESOURCE] still terminates and needs a contract-first
   * disposition, [HARNESS] records a reviewed finite path whose covering test races
   * the watchdog, and [UNTRIAGED] is the seeder's explicit unfinished state. The
   * latter three remain findings and can never certify.
   */
  enum class CauseCategory(val token: String) {
    LIVENESS("liveness"),
    RESOURCE("resource"),
    HARNESS("harness"),
    UNTRIAGED("untriaged");

    companion object {
      fun fromToken(token: String): CauseCategory? = entries.singleOrNull { it.token == token }
    }
  }

  data class CauseFinding(
    val member: String,
    val detail: String,
  )

  /** Optional diagnostic source-line metadata that could not be interpreted exactly. */
  data class LineMetadataFinding(
    val member: String,
    val detail: String,
  )

  /**
   * Well-formed, de-duplicated `class,method,mutator` members, the rows that failed
   * to parse, and each member's recorded lines — the numbers a row's `# line 12` /
   * `# lines 12, 30` / `# lines 12/30` comment names, absent when the comment
   * names none. Those values are diagnostic metadata for human review, never
   * membership identity or authorization.
   */
  data class Membership(
    val members: Set<String>,
    val malformed: List<String>,
    val recordedLines: Map<String, Set<Int>>,
    val causeCategories: Map<String, CauseCategory>,
    val causeFindings: List<CauseFinding>,
    val lineMetadataFindings: List<LineMetadataFinding>,
  )

  /**
   * Report-dependent audit state derived from one parsed membership document and
   * one completed PIT population. Keeping this matching here prevents the normal
   * verifier and its fail-closed provenance path from disagreeing about which
   * timeout is new, stale, or still eligible for a cause check.
   */
  data class ReportFindings(
    val timedOutByKey: Map<String, List<Mutant>>,
    val unaudited: List<Mutant>,
    val staleMembers: Set<String>,
    val liveMembers: Set<String>,
    val causeFindings: List<CauseFinding>,
    val lineMetadataFindings: List<LineMetadataFinding>,
    val undocumented: List<String>,
    val multiMutantMembers: List<MemberPopulation>,
  )

  /**
   * One report's observable population under an audited line-less key. [observations]
   * keeps line, status, and multiplicity separate: two same-line mutants are still two
   * copies, while a `KILLED` sibling must not be presented as another timeout cause.
   */
  data class MemberPopulation(
    val member: String,
    val mutantCount: Int,
    val observations: List<PopulationObservation>,
  )

  data class PopulationObservation(
    val line: Int,
    val status: String,
    val copies: Int,
  )

  /**
   * Physical timeout observations grouped by the line-less membership key a reviewer
   * can actually commit. [instanceCount] deliberately remains separate from
   * [keyCount]: several physical mutants may need one cause classification, while
   * their lines and multiplicity remain diagnostic evidence rather than identity.
   */
  data class TimeoutCandidates(
    val populations: List<MemberPopulation>,
  ) {
    val instanceCount: Int get() = populations.sumOf { it.mutantCount }
    val keyCount: Int get() = populations.size
  }

  private val CAUSE_COMMENT = Regex("""\bcause\s*:\s*([A-Za-z][A-Za-z0-9_-]*)\b""")

  /** PIT release whose float-rounding watchdog strategy was inspected; re-audit before moving. */
  private const val WATCHDOG_FORMULA_AUDITED_PITEST = "1.25.9"

  private data class ParsedRow(
    val key: String,
    val recordedLines: List<Int>,
    val causeTokens: List<String>,
    val invalidLineMetadata: String?,
  )

  /**
   * Parses membership rows: `#` comments stripped, each field trimmed (spacing is
   * readability, not identity), blank lines dropped, and rows without exactly three
   * non-empty fields split off as malformed rather than left to surface as members
   * matching no mutant. Duplicate rows collapse to one member here, so every caller
   * counts a twice-pasted row the same way — once (their recorded lines union).
   *
   * The comment is not all thrown away: the key is line-less on purpose, but the
   * `line N` tag the seed writes after its cause (and the paste-ready row carries)
   * remains useful as a human triage pointer, so it is retained as diagnostic
   * metadata per member.
   */
  fun parse(lines: List<String>): Membership {
    val rows = lines.map { line ->
      val comment = line.substringAfter('#', "")
      val key = line.substringBefore('#').split(',').joinToString(",") { it.trim() }
      val lineMetadata = RecordedLineMetadata.timeoutComment(comment)
      ParsedRow(
          key,
          lineMetadata.lines,
          CAUSE_COMMENT.findAll(comment).map { it.groupValues[1] }.toList(),
          lineMetadata.invalid,
      )
    }.filter { it.key.isNotEmpty() }
    val (memberRows, malformedRows) = rows.partition { row ->
      val fields = row.key.split(',')
      fields.size == 3 && fields.none { it.isEmpty() }
    }
    val recordedLines = memberRows
        .filter { it.recordedLines.isNotEmpty() }
        .groupBy({ it.key }, { it.recordedLines })
        .mapValues { (_, recorded) -> recorded.flatten().toSet() }
    val categories = linkedMapOf<String, CauseCategory>()
    val causeFindings = mutableListOf<CauseFinding>()
    val lineMetadataFindings = mutableListOf<LineMetadataFinding>()
    memberRows.groupBy { it.key }.toSortedMap().forEach { (member, copies) ->
      val findingDetails = mutableListOf<String>()
      val invalidMetadata = copies.mapNotNull { it.invalidLineMetadata }.distinct()
      if (invalidMetadata.isNotEmpty()) {
        lineMetadataFindings += LineMetadataFinding(
            member,
            invalidMetadata.joinToString("; ") { RecordedLineMetadata.timeoutInvalidDetail(it) },
        )
      }
      val tokens = copies.flatMap { it.causeTokens }.distinct()
      when {
        tokens.isEmpty() -> findingDetails +=
            "missing cause:liveness/resource/harness/untriaged"
        tokens.size > 1 -> findingDetails +=
            "conflicting cause categories: ${tokens.sorted().joinToString(", ")}"
        else -> {
          val token = tokens.single()
          val category = CauseCategory.fromToken(token)
          if (category == null) {
            findingDetails += "unknown cause category '$token'"
          } else {
            categories[member] = category
            when (category) {
              CauseCategory.LIVENESS -> Unit
              CauseCategory.RESOURCE -> findingDetails +=
                  "cause:resource terminates and needs a deterministic contract-first disposition, " +
                      "not watchdog detection"
              CauseCategory.HARNESS -> findingDetails +=
                  "cause:harness is a finite covering-path/watchdog race that must be repaired " +
                      "before certification"
              CauseCategory.UNTRIAGED -> findingDetails +=
                  "cause:untriaged has not been reviewed"
            }
          }
        }
      }
      if (findingDetails.isNotEmpty()) {
        causeFindings += CauseFinding(member, findingDetails.joinToString("; "))
      }
    }
    return Membership(
        memberRows.map { it.key }.toSet(),
        malformedRows.map { it.key },
        recordedLines,
        categories,
        causeFindings,
        lineMetadataFindings,
    )
  }

  /** Cause-classification findings for [members], preserving parser diagnostics. */
  fun causeFindings(membership: Membership, members: Collection<String>): List<CauseFinding> {
    val selected = members.toSet()
    return membership.causeFindings.filter { it.member in selected }
  }

  /** Invalid optional line metadata for [members], separate from cause validity. */
  fun lineMetadataFindings(
    membership: Membership,
    members: Collection<String>,
  ): List<LineMetadataFinding> {
    val selected = members.toSet()
    return membership.lineMetadataFindings.filter { it.member in selected }
  }

  /** Combines a trusted completed report with committed timeout membership. */
  fun reportFindings(
    rows: List<Mutant>,
    membership: Membership,
    readme: () -> String,
  ): ReportFindings {
    val timedOutByKey = rows
        .filter { it.status == MutantStatus.TIMED_OUT }
        .groupBy { it.coordinate }
    val unaudited = timedOutByKey
        .filterKeys { it !in membership.members }
        .values
        .flatten()
    val allKeys = rows.mapTo(HashSet()) { it.coordinate }
    val staleMembers = membership.members.filterNotTo(linkedSetOf()) { it in allKeys }
    val liveMembers = membership.members - staleMembers
    return ReportFindings(
        timedOutByKey = timedOutByKey,
        unaudited = unaudited,
        staleMembers = staleMembers,
        liveMembers = liveMembers,
        causeFindings = causeFindings(membership, liveMembers),
        lineMetadataFindings = lineMetadataFindings(membership, liveMembers),
        undocumented = undocumentedCauses(liveMembers, readme),
        multiMutantMembers = memberPopulations(rows, liveMembers),
    )
  }

  /**
   * Audited keys whose current report contains several mutant copies and at least
   * one timeout. Cause classification is key-level, so these are the populations a
   * reviewer must inspect for distinct timeout causes. Non-timeout siblings stay in
   * the projection because their presence is useful context, but do not themselves
   * prove a mixed timeout cause.
   */
  fun memberPopulations(
    rows: List<Mutant>,
    members: Collection<String>,
  ): List<MemberPopulation> {
    val selected = members.toSet()
    return populations(rows.filter { it.coordinate in selected })
        .filter { population ->
          population.mutantCount > 1 &&
              population.observations.any { it.status == MutantStatus.TIMED_OUT.name }
        }
  }

  /** All physical `TIMED_OUT` rows, grouped by their committable line-less key. */
  fun timeoutCandidates(rows: Collection<Mutant>): TimeoutCandidates {
    val timedOut = rows.filter { it.status == MutantStatus.TIMED_OUT }
    return TimeoutCandidates(populations(timedOut))
  }

  private fun populations(rows: Collection<Mutant>): List<MemberPopulation> = rows
      .groupBy { it.coordinate }
      .entries
      .sortedBy { it.key }
      .map { (member, mutants) ->
        val observations = mutants
            .groupingBy { it.line!! to it.rawStatus }
            .eachCount()
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (lineAndStatus, copies) ->
              PopulationObservation(lineAndStatus.first, lineAndStatus.second, copies)
            }
        MemberPopulation(member, mutants.size, observations)
      }

  /** Physical observations and committable keys must never be presented as one count. */
  fun timeoutCandidateCount(candidates: TimeoutCandidates): String =
      "${candidates.instanceCount} physical TIMED_OUT mutant instance(s) across " +
          "${candidates.keyCount} line-less key(s)"

  /** One paste-ready membership row per line-less key, with sorted distinct line tags. */
  fun timeoutCandidateRows(
    candidates: TimeoutCandidates,
    indent: String = "",
  ): String = candidates.populations.joinToString("\n") { population ->
    indent + timeoutCandidateRow(population)
  }

  /**
   * Paste-ready rows plus comment-only physical evidence for keys that collapse
   * multiple instances. Copying the whole block is safe: the parser ignores each
   * nested `# observed` line, while a reviewer can still see same-line copies that
   * the membership row necessarily collapses.
   */
  fun timeoutCandidateDetail(
    candidates: TimeoutCandidates,
    indent: String = "  ",
  ): String = candidates.populations.joinToString("\n") { population ->
    val row = "$indent${timeoutCandidateRow(population)}"
    if (population.mutantCount == 1) {
      row
    } else {
      row + "\n" + population.observations.joinToString("\n") { observation ->
        "$indent  # observed: line ${observation.line} ${observation.status} " +
            "x${observation.copies}"
      }
    }
  }

  private fun timeoutCandidateRow(population: MemberPopulation): String {
    val lines = population.observations.map { it.line }.distinct().sorted()
    return "${population.member} # cause:untriaged " +
        "line${if (lines.size == 1) "" else "s"} ${lines.joinToString(", ")}"
  }

  /**
   * The configured arithmetic is useful incident context, but the CSV has neither
   * the covering test PIT selected nor its recorded duration. Never turn settings
   * alone into a fictional per-mutant budget.
   */
  fun watchdogFormulaContext(
    pitestVersion: String,
    timeoutFactor: Double,
    timeoutConst: Long,
  ): String {
    val configured = if (pitestVersion == WATCHDOG_FORMULA_AUDITED_PITEST) {
      "Configured watchdog (audited PIT $pitestVersion): " +
          "round(testDurationMs × $timeoutFactor) + $timeoutConst ms."
    } else {
      "Configured watchdog inputs for PIT $pitestVersion: timeoutFactor=$timeoutFactor, " +
          "timeoutConst=$timeoutConst ms; this plugin version has not audited that PIT " +
          "release's exact duration conversion."
    }
    return "$configured PIT CSV lacks the active covering test and its recorded duration, " +
        "so no per-mutant budget can be calculated."
  }

  /** Shared Debt/verify rendering for [memberPopulations]. */
  fun memberPopulationDetail(
    suiteName: String,
    populations: Collection<MemberPopulation>,
    reportDescription: String = "current full report",
  ): String? {
    if (populations.isEmpty()) return null
    return "pitest '$suiteName': ${populations.size} audited-timeout key(s) cover multiple " +
        "mutant copies in the $reportDescription. Cause is key-level: inspect every TIMED_OUT " +
        "sibling; KILLED and other non-timeout siblings are context, not proof of a mixed " +
        "timeout cause:\n" + populations.joinToString("\n") { population ->
          "  ${population.member} — ${population.mutantCount} mutants:\n" +
              population.observations.joinToString("\n") { observation ->
                "    line ${observation.line} ${observation.status} x${observation.copies}"
              }
        }
  }

  /** The report-dependent warning for timeouts outside the committed set. */
  fun unauditedWarning(
    suiteName: String,
    fileName: String,
    unaudited: Collection<Mutant>,
    pitestVersion: String,
    timeoutFactor: Double,
    timeoutConst: Long,
    historyDecisionCaveat: String = "",
  ): String {
    val candidates = timeoutCandidates(unaudited)
    return "pitest '$suiteName': ${timeoutCandidateCount(candidates)} not in the audited set " +
          "($fileName) — a new timeout hides a weakened-assertion blind spot " +
          "behind \"detected\"; identify the structural cause (the removed loop exit, the " +
          "reversed cursor). Each key below has one paste-ready, fail-closed draft row. Where " +
          "a key collapses multiple physical instances, nested '# observed' comments preserve " +
          "line/status multiplicity. Replace " +
          "each deliberate cause:untriaged placeholder with the reviewed classification and " +
          "write the structural argument in config/pitest/README.md before committing it. The " +
          "placeholder is non-certifying; only cause:liveness may remain in a certifying " +
          "audited set. ${watchdogFormulaContext(pitestVersion, timeoutFactor, timeoutConst)}\n" +
          timeoutCandidateDetail(candidates) + historyDecisionCaveat
  }

  /**
   * The same coordinates while committed provenance is invalid. This deliberately
   * withholds the normal "add the row" instruction: the population may reflect an
   * unbound tool transition, so it is triage only until rebase and re-observation.
   */
  fun unauditedProvenancePreview(
    suiteName: String,
    fileName: String,
    unaudited: Collection<Mutant>,
    pitestVersion: String,
    timeoutFactor: Double,
    timeoutConst: Long,
    historyDecisionCaveat: String = "",
  ): String {
    val candidates = timeoutCandidates(unaudited)
    return "pitest '$suiteName': the current full report contains " +
          "${timeoutCandidateCount(candidates)} outside $fileName, but committed mutation " +
          "provenance is invalid. Retain " +
          "these candidates for triage; do not add or classify them until provenance is " +
          "repaired/rebased and a fresh full observation confirms them. Each line-less key " +
          "has one draft row. Where a key collapses multiple physical instances, nested " +
          "'# observed' comments retain line/status multiplicity. " +
          "${watchdogFormulaContext(pitestVersion, timeoutFactor, timeoutConst)}\n" +
          timeoutCandidateDetail(candidates) + historyDecisionCaveat
  }

  /** The report-dependent warning for committed members absent from the population. */
  fun staleWarning(
    suiteName: String,
    staleMembers: Collection<String>,
    historyDecisionCaveat: String = "",
  ): String =
      "pitest '$suiteName': ${staleMembers.size} audited-timeout row(s) match no mutant in " +
          "this run's report — the code moved or the mutator set changed; retire or fix:\n" +
          staleMembers.sorted().joinToString("\n") { "  $it" } + historyDecisionCaveat

  /** Stale-member preview that withholds retirement while provenance is invalid. */
  fun staleProvenancePreview(
    suiteName: String,
    staleMembers: Collection<String>,
    historyDecisionCaveat: String = "",
  ): String =
      "pitest '$suiteName': the current full report does not contain " +
          "${staleMembers.size} audited-timeout row(s), but committed mutation provenance is " +
          "invalid. Retain these candidates for triage; do not retire or rewrite them until " +
          "provenance is repaired/rebased and a fresh full observation confirms the absence:\n" +
          staleMembers.sorted().joinToString("\n") { "  $it" } + historyDecisionCaveat

  fun causeFindingWarning(
    suiteName: String,
    fileName: String,
    findings: Collection<CauseFinding>,
  ): String =
      "pitest '$suiteName': ${findings.size} audited-timeout member(s) lack an admissible cause " +
          "classification in $fileName — use 'cause:liveness' only when the mutated path has no " +
          "path-owned finite completion guarantee after deterministic seams/budgets are exhausted " +
          "(a fixture safety exit does not demote it); 'cause:resource' requires either a " +
          "deterministic resource-contract test/fix or a stable SURVIVED equivalence argument, " +
          "'cause:harness' records a reviewed finite covering-path/watchdog race while it is " +
          "being repaired, and 'cause:untriaged' is unfinished; all three are non-certifying:\n" +
          findings.sortedBy { it.member }.joinToString("\n") { "  ${it.member} # ${it.detail}" }

  fun lineMetadataWarning(
    suiteName: String,
    fileName: String,
    findings: Collection<LineMetadataFinding>,
  ): String =
      "pitest '$suiteName': ${findings.size} audited-timeout member(s) carry invalid optional " +
          "line metadata in $fileName. This metadata supplies no source-line diagnostic evidence; " +
          "it does not change membership or cause classification, block strict certification, or " +
          "change timeout-retirement eligibility:\n" +
          findings.sortedBy { it.member }.joinToString("\n") { "  ${it.member} # ${it.detail}" }

  /** The malformed-row warning, or null when every row parses. */
  fun malformedWarning(suiteName: String, fileName: String, malformed: List<String>): String? =
      if (malformed.isEmpty()) null else
        "pitest '$suiteName': ${malformed.size} malformed row(s) in $fileName — expected " +
            "'class,method,mutator' (three fields, '#' comments allowed); these match nothing until fixed:\n" +
            malformed.joinToString("\n") { "  $it" }

  /**
   * The key-level disjointness decision, shared with [BaselineNotes.lineDrift]'s
   * partial-tag/count-skew fallback so the two advisories can never disagree about
   * what "drifted" means at this resolution: the observed lines report only when
   * none of them matches a recorded one — the anchor moved entirely.
   */
  fun disjointDrift(recordedLines: Set<Int>, observedLines: Set<Int>): Set<Int> =
      if (observedLines.any { it in recordedLines }) emptySet() else observedLines

  /**
   * The [members] whose structural cause was never written: matched by the simple
   * class name AND the method name appearing together in one README section, each
   * as a whole word. Method-only matching was trivially satisfied, since most
   * dispatch members are named `handle` — and substring matching over the whole file
   * kept it so (`run` is inside "rerun", and the class name is present wherever a
   * *sibling* member's cause names it). A section is a markdown-heading-delimited
   * block (a headingless README is one section): measured against the fleet's 172
   * audited members, blank-line paragraphs false-flagged 41 documented causes —
   * one consumer's house style names the class in a section's intro and argues each
   * method in its own paragraph below — while heading blocks resolved all 172 and
   * still deny the whole-file leak of a sibling cause in a distant section. Nested
   * classes match under either their source (`Outer.Inner`) or binary
   * (`Outer$Inner`) name. [readme] is called only when there is a member to resolve.
   */
  fun undocumentedCauses(members: Collection<String>, readme: () -> String): List<String> {
    if (members.isEmpty()) return emptyList()
    val sections = neutralizeFences(readme()).split(SECTION_BREAK)
    return members.filter { member ->
      val fields = member.split(',')
      val simpleClass = fields[0].substringAfterLast('.')
      val classPatterns = setOf(simpleClass, simpleClass.replace('$', '.'))
          .map { wholeWord(it) }
      val methodPattern = wholeWord(fields[1])
      sections.none { section ->
        methodPattern.containsMatchIn(section) &&
            classPatterns.any { it.containsMatchIn(section) }
      }
    }
  }

  // split at the heading marker, keeping the heading text with the block it
  // introduces — a section titled after its class documents that class
  private val SECTION_BREAK = Regex("""(?m)^#{1,6}\s""")

  // A '#' at column 0 inside a fenced code block is code, not a heading — a
  // README quoting a shell command or a properties snippet split its section
  // mid-fence, and every cause argued below the fence read as undocumented
  // (failing -PstrictTimeoutAudit). Fenced '#' lines are indented out of the
  // heading grammar; their CONTENT is kept, because a snippet may legitimately
  // carry the member mention its section argues with. Backtick and tilde
  // fences both count — CommonMark treats them identically.
  private fun neutralizeFences(text: String): String {
    var fenced = false
    return text.lineSequence().joinToString("\n") { line ->
      val trimmed = line.trimStart()
      when {
        trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
          fenced = !fenced
          line
        }
        fenced && line.startsWith("#") -> " $line"
        else -> line
      }
    }
  }

  /**
   * Whole-word via lookarounds, not `\b`: a word boundary exists only between a
   * word char and a non-word char, so `\b<init>\b` demands a word char *outside*
   * each angle bracket and can never match `Handler.<init>` in prose. Lookarounds
   * ask the right question — no word char adjacent to the token — which behaves
   * identically for word-edged tokens (`run` still rejects "rerun") and correctly
   * for constructor members. For tokens with non-word edges this is deliberately
   * stricter than `\b`: `pre<init>` is not a mention of `<init>`.
   *
   * Never called with an empty token — `(?<!\w)(?!\w)` would match between any two
   * non-word chars — because members reach [undocumentedCauses] only via [parse],
   * which splits rows with a blank field off as malformed.
   */
  private fun wholeWord(token: String) = Regex("(?<!\\w)${Regex.escape(token)}(?!\\w)")

  /** The warning naming [undocumented] members; callers pass a non-empty list. */
  fun undocumentedCauseWarning(suiteName: String, undocumented: Collection<String>): String =
      "pitest '$suiteName': ${undocumented.size} audited-timeout member(s) whose class and method " +
          "appear nowhere together in config/pitest/README.md — the structural cause belongs there " +
          "(HARDENING.md, the audited-set bullet):\n" +
          undocumented.sorted().joinToString("\n") { "  cause? $it" }
}
