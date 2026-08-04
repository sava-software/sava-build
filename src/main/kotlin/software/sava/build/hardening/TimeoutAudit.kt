package software.sava.build.hardening

/**
 * The single place that knows the audited-timeout membership file's row format
 * (`config/pitest/<suite>-timeouts.csv`: line-less `class,method,mutator` keys, `#`
 * comments allowed), so the verify and `pitest<Suite>Debt` can never drift on what
 * parses, what is malformed, or which causes resolve.
 *
 * This is intentionally not an accepted-baseline [BaselineDocument]. Timeout audit
 * sets were introduced with this stable line-less three-field identity; unlike the
 * accepted baseline, they never had a five-field-to-line-less transition that an N-1
 * reader could misinterpret. Reusing the accepted-baseline marker would therefore
 * invent a format break rather than guard one. Any future incompatible timeout-set
 * change needs its own marker, N-1 reader, migration, and rollback contract.
 *
 * Only the audit's *static* half lives here — checks that read committed files and
 * nothing else — which is what lets `Debt` answer "does the tool agree with my pasted
 * row and my README cause?" in seconds instead of after the next mutation run. The
 * mutant-facing checks (unaudited newcomers, stale members, quiet streaks, drifted
 * lines) need a report and stay in the verify — though [lineDrift]'s pure comparison
 * lives here so it can be reasoned about without a TestKit fixture.
 */
internal object TimeoutAudit {

  /**
   * Well-formed, de-duplicated `class,method,mutator` members, the rows that failed
   * to parse, and each member's recorded lines — the numbers a row's `# line 12` /
   * `# lines 12, 30` / `# lines 12/30` comment names, absent when the comment
   * names none.
   */
  data class Membership(
    val members: Set<String>,
    val malformed: List<String>,
    val recordedLines: Map<String, Set<Int>>,
  )

  // Comma or slash between numbers: the seed writes commas, but hand-written rows
  // in shipped consumer files say '# lines 137/141' — a parser keeping only the
  // first number would read the second line's timeout as false drift.
  private val LINE_COMMENT = Regex("""\blines?\s+(\d+(?:\s*[,/]\s*\d+)*)""")

  /**
   * Parses membership rows: `#` comments stripped, each field trimmed (spacing is
   * readability, not identity), blank lines dropped, and rows without exactly three
   * non-empty fields split off as malformed rather than left to surface as members
   * matching no mutant. Duplicate rows collapse to one member here, so every caller
   * counts a twice-pasted row the same way — once (their recorded lines union).
   *
   * The comment is not all thrown away: the key is line-less on purpose, but the
   * `# line N` the seed writes (and the paste-ready row carries) is the anchor the
   * README cause argues about, so it is kept per member for the verify's drift check.
   */
  fun parse(lines: List<String>): Membership {
    val rows = lines.map { line ->
      val key = line.substringBefore('#').split(',').joinToString(",") { it.trim() }
      val recorded = LINE_COMMENT.find(line.substringAfter('#', ""))
          ?.groupValues?.get(1)?.split(',', '/')?.mapNotNull { it.trim().toIntOrNull() }
          .orEmpty()
      key to recorded
    }.filter { (key, _) -> key.isNotEmpty() }
    val (memberRows, malformedRows) = rows.partition { (key, _) ->
      val fields = key.split(',')
      fields.size == 3 && fields.none { it.isEmpty() }
    }
    val recordedLines = memberRows
        .filter { (_, recorded) -> recorded.isNotEmpty() }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, recorded) -> recorded.flatten().toSet() }
    return Membership(
        memberRows.map { it.first }.toSet(),
        malformedRows.map { it.first },
        recordedLines,
    )
  }

  /** The malformed-row warning, or null when every row parses. */
  fun malformedWarning(suiteName: String, fileName: String, malformed: List<String>): String? =
      if (malformed.isEmpty()) null else
        "pitest '$suiteName': ${malformed.size} malformed row(s) in $fileName — expected " +
            "'class,method,mutator' (three fields, '#' comments allowed); these match nothing until fixed:\n" +
            malformed.joinToString("\n") { "  $it" }

  /**
   * Members whose observed timeout lines all differ from the lines their row's
   * comment names — the machine half of "re-read the README cause when the code at
   * that line changes". [recorded] is [Membership.recordedLines]; [observed] maps a
   * member to the lines it timed out at in this run's report, and only members
   * present in both take part: a member without a `# line` comment recorded no
   * anchor, and a member that did not time out this run observed nothing.
   *
   * Disjointness, not inequality, on purpose: a *new* sibling line appearing next to
   * a recorded one is the line-less key's stated resolution (no warning), so drift
   * only fires when no observed line matches any recorded one — the anchor the cause
   * argues about has moved entirely.
   */
  fun lineDrift(
    recorded: Map<String, Set<Int>>,
    observed: Map<String, Set<Int>>,
  ): Map<String, Pair<Set<Int>, Set<Int>>> = observed.entries.mapNotNull { (member, observedLines) ->
    val recordedLines = recorded[member] ?: return@mapNotNull null
    val unmatched = disjointDrift(recordedLines, observedLines)
    if (unmatched.isEmpty()) null else member to (recordedLines to unmatched)
  }.toMap()

  /**
   * The key-level disjointness decision, shared with [BaselineNotes.lineDrift]'s
   * partial-tag/count-skew fallback so the two advisories can never disagree about
   * what "drifted" means at this resolution: the observed lines report only when
   * none of them matches a recorded one — the anchor moved entirely.
   */
  fun disjointDrift(recordedLines: Set<Int>, observedLines: Set<Int>): Set<Int> =
      if (observedLines.any { it in recordedLines }) emptySet() else observedLines

  /** The line-drift warning; callers pass a non-empty [drifted] from [lineDrift]. */
  fun lineDriftWarning(
    suiteName: String,
    fileName: String,
    drifted: Map<String, Pair<Set<Int>, Set<Int>>>,
  ): String =
      "pitest '$suiteName': ${drifted.size} audited-timeout member(s) timed out at line(s) their row's " +
          "comment does not name — the code the README cause argues about has moved; re-read the cause, " +
          "then update the '# line' comment in $fileName:\n" +
          drifted.entries.sortedBy { it.key }.joinToString("\n") { (member, lines) ->
            val (recordedLines, observedLines) = lines
            "  $member # line(s) ${recordedLines.sorted().joinToString(", ")} -> " +
                "observed ${observedLines.sorted().joinToString(", ")}"
          }

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
