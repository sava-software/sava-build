package software.sava.build.hardening

/**
 * The single place that knows the audited-timeout membership file's row format
 * (`config/pitest/<suite>-timeouts.csv`: line-less `class,method,mutator` keys, `#`
 * comments allowed), so the verify and `pitest<Suite>Debt` can never drift on what
 * parses, what is malformed, or which causes resolve.
 *
 * Only the audit's *static* half lives here — checks that read committed files and
 * nothing else — which is what lets `Debt` answer "does the tool agree with my pasted
 * row and my README cause?" in seconds instead of after the next mutation run. The
 * mutant-facing checks (unaudited newcomers, stale members, quiet streaks) need a
 * report and stay in the verify.
 */
internal object TimeoutAudit {

  /** Well-formed, de-duplicated `class,method,mutator` members, and the rows that failed to parse. */
  data class Membership(val members: Set<String>, val malformed: List<String>)

  /**
   * Parses membership rows: `#` comments stripped, each field trimmed (spacing is
   * readability, not identity), blank lines dropped, and rows without exactly three
   * non-empty fields split off as malformed rather than left to surface as members
   * matching no mutant. Duplicate rows collapse to one member here, so every caller
   * counts a twice-pasted row the same way — once.
   */
  fun parse(lines: List<String>): Membership {
    val rows = lines
        .map { line -> line.substringBefore('#').split(',').joinToString(",") { it.trim() } }
        .filter { it.isNotEmpty() }
    val (members, malformed) = rows.partition { row ->
      val fields = row.split(',')
      fields.size == 3 && fields.none { it.isEmpty() }
    }
    return Membership(members.toSet(), malformed)
  }

  /** The malformed-row warning, or null when every row parses. */
  fun malformedWarning(suiteName: String, fileName: String, malformed: List<String>): String? =
      if (malformed.isEmpty()) null else
        "pitest '$suiteName': ${malformed.size} malformed row(s) in $fileName — expected " +
            "'class,method,mutator' (three fields, '#' comments allowed); these match nothing until fixed:\n" +
            malformed.joinToString("\n") { "  $it" }

  /**
   * The [members] whose structural cause was never written: matched by the simple
   * class name AND the method name both appearing in the README — method-only
   * matching was trivially satisfied, since most dispatch members are named `handle`.
   * Nested classes match under either their source (`Outer.Inner`) or binary
   * (`Outer$Inner`) name. [readme] is called only when there is a member to resolve.
   */
  fun undocumentedCauses(members: Collection<String>, readme: () -> String): List<String> {
    if (members.isEmpty()) return emptyList()
    val text = readme()
    return members.filter { member ->
      val fields = member.split(',')
      val simpleClass = fields[0].substringAfterLast('.')
      !(text.contains(fields[1]) &&
          (text.contains(simpleClass) || text.contains(simpleClass.replace('$', '.'))))
    }
  }

  /** The warning naming [undocumented] members; callers pass a non-empty list. */
  fun undocumentedCauseWarning(suiteName: String, undocumented: Collection<String>): String =
      "pitest '$suiteName': ${undocumented.size} audited-timeout member(s) whose class and method " +
          "appear nowhere together in config/pitest/README.md — the structural cause belongs there " +
          "(HARDENING.md, the audited-set bullet):\n" +
          undocumented.sorted().joinToString("\n") { "  cause? $it" }
}
