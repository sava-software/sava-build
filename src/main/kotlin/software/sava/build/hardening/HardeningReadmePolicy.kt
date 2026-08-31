package software.sava.build.hardening

/** Read-only migration audit for consumer `config/pitest/README.md` prose. */
internal object HardeningReadmePolicy {

  internal enum class FindingKind {
    SOURCE_LOCATOR,
    INHERITED_SCAFFOLD,
  }

  internal data class Finding(
    val kind: FindingKind,
    val lineNumber: Int,
    val matchedText: String,
    val excerpt: String,
  )

  internal data class Inspection(val findings: List<Finding>) {
    val sourceLocators: List<Finding>
      get() = findings.filter { it.kind == FindingKind.SOURCE_LOCATOR }

    val inheritedScaffolds: List<Finding>
      get() = findings.filter { it.kind == FindingKind.INHERITED_SCAFFOLD }

    val isClean: Boolean
      get() = findings.isEmpty()
  }

  private data class ScaffoldSignature(
    val family: String,
    val needle: String,
    val lineAnchor: String,
  )

  // Exact, stable clauses emitted by hardeningInit in 21.5.9 through 21.5.28.
  // Match known ancestry, not vaguely similar consumer prose: this advisory must
  // not become a prose-style checker or a second implementation of task semantics.
  private val obsoleteScaffoldSignatures = listOf(
    ScaffoldSignature(
      "ratchet-overview",
      "Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the",
      "Each `pitest<Suite>` run is finalized",
    ),
    ScaffoldSignature(
      "baseline-refresh",
      "Never refresh with `-PupdateMutationBaseline` just to make the build pass",
      "Never refresh with `-PupdateMutationBaseline`",
    ),
    ScaffoldSignature(
      "baseline-notes",
      "A baseline row may carry a trailing `# note`",
      "A baseline row may carry a trailing `# note`",
    ),
    ScaffoldSignature(
      "baseline-notes",
      "A baseline row may carry a `# note` before its line tag",
      "A baseline row may carry a `# note` before its line tag",
    ),
    ScaffoldSignature(
      "structured-evidence",
      "The CSV files beside this document are structured evidence. Preserve row identity",
      "The CSV files beside this document are structured evidence",
    ),
    ScaffoldSignature(
      "structured-evidence",
      "When timeout verification prints paste-ready membership rows",
      "When timeout verification prints paste-ready",
    ),
    ScaffoldSignature(
      "structured-evidence",
      "An ArcMutate `[history]` report is check-only",
      "An ArcMutate `[history]` report is check-only",
    ),
    ScaffoldSignature(
      "accepted-family",
      "For every accepted family, explain the local structural reason and quote the",
      "For every accepted family, explain the local structural reason",
    ),
    ScaffoldSignature(
      "timeout-causes",
      "For every audited timeout row, replace the seeded `cause:untriaged` comment category",
      "For every audited timeout row, replace the seeded",
    ),
  )

  private val explicitLineLocator = Regex(
    """(?i)\blines?\s+\d{1,6}(?:\s*(?:[-–—/,]|\band\b)\s*\d{1,6})*"""
  )
  private val colonLocator = Regex(""":\d{2,5}(?:(?:[-/,])\d{2,5})*""")
  private val backtickedIdentifier = Regex("""`[A-Za-z_$<>][A-Za-z0-9_$<>]*`""")
  private val mutationVocabulary = Regex(
    """(?:Mutator|ConditionalsBoundary|VoidMethodCall|NullReturnVals|Boolean(?:True|False)ReturnVals|Math|Increments|EQUAL_|ORDER_)"""
  )
  private val commonPortLabels = setOf("host", "hostname", "localhost", "port", "server")

  fun inspect(text: String): Inspection {
    val lines = text.lines()
    val sourceLocators = lines.flatMapIndexed { index, line ->
      sourceLocators(line).map { matched ->
        Finding(
          FindingKind.SOURCE_LOCATOR,
          index + 1,
          matched.value,
          excerpt(line),
        )
      }
    }

    val normalized = normalizeProse(text)
    val inherited = obsoleteScaffoldSignatures
      .filter { normalized.contains(it.needle) }
      .groupBy { it.family }
      .values
      .map { matchingFamily ->
        val signature = matchingFamily.first()
        val lineIndex = lines.indexOfFirst { it.contains(signature.lineAnchor) }
        val sourceLine = lines.getOrElse(lineIndex.coerceAtLeast(0)) { signature.lineAnchor }
        Finding(
          FindingKind.INHERITED_SCAFFOLD,
          if (lineIndex >= 0) lineIndex + 1 else 1,
          signature.lineAnchor,
          excerpt(sourceLine),
        )
      }

    // Kotlin's sort is stable: ordering only by README line retains left-to-right
    // source order for several coordinates on one roster line.
    return Inspection((sourceLocators + inherited).sortedBy { it.lineNumber })
  }

  fun warning(readmePath: String, inspection: Inspection, helpTaskPath: String): String {
    require(!inspection.isClean) { "a clean README has no audit warning" }
    val locators = inspection.sourceLocators
    val scaffolds = inspection.inheritedScaffolds
    fun counted(count: Int, singular: String, plural: String = "${singular}s") =
      "$count ${if (count == 1) singular else plural}"
    fun details(title: String, findings: List<Finding>): String =
      if (findings.isEmpty()) "" else {
        "\n  $title:\n" + findings.joinToString("\n") { finding ->
          "    README.md:${finding.lineNumber}  ${finding.excerpt}"
        }
      }

    val claims = buildList {
      if (locators.isNotEmpty()) add(counted(locators.size, "likely source-line locator"))
      if (scaffolds.isNotEmpty()) add(counted(scaffolds.size, "inherited scaffold-mechanics passage"))
    }
    return "hardeningReadmeAudit: $readmePath carries ${claims.joinToString(" and ")}." +
      details("Source-locator candidates", locators) +
      details("Inherited scaffold mechanics", scaffolds) +
      "\n  Review: This README owns repository-local ownership, measurements, arguments, " +
      "and provenance. Installed task behavior belongs to $helpTaskPath. Inline, fenced, " +
      "and tabular coordinate rosters are README prose, not protected mutation membership." +
      "\n  Remedy: Remove source coordinates while preserving the class, method, semantic " +
      "branch, mutator, and meaningful xN multiplicity. Rewrite or remove inherited " +
      "task-semantics passages; consult $helpTaskPath for installed behavior." +
      "\n  Disposition: This is a non-failing migration advisory."
  }

  private fun sourceLocators(line: String): List<MatchResult> {
    val explicit = explicitLineLocator.findAll(line).filterNot { match ->
      isStructuredLineTag(line, match.range.first)
    }
    val colonMatches = colonLocator.findAll(line).toList()
    // An excluded ratio/clock/port is not context that can authorize a later
    // shorthand `:NN` on the same line.
    val hasFullCoordinate = colonMatches.any { match ->
      leftIdentifier(line, match.range.first).isNotEmpty() &&
        isSourceColonCandidate(line, match, supportsShorthand = false)
    }
    val supportsShorthand = hasFullCoordinate ||
      backtickedIdentifier.containsMatchIn(line) || mutationVocabulary.containsMatchIn(line)
    val colon = colonMatches.asSequence().filter { match ->
      isSourceColonCandidate(line, match, supportsShorthand)
    }
    return (explicit + colon).sortedBy { it.range.first }.toList()
  }

  private fun isStructuredLineTag(line: String, start: Int): Boolean {
    val prefix = line.substring(0, start)
    if (prefix.trimEnd().endsWith("#")) return true
    val hash = prefix.lastIndexOf('#')
    return hash >= 0 && prefix.substring(0, hash).count { it == ',' } >= 2
  }

  private fun isSourceColonCandidate(
    line: String,
    match: MatchResult,
    supportsShorthand: Boolean,
  ): Boolean {
    val start = match.range.first
    if (start > 0 && line[start - 1].isDigit()) return false // clock or ratio
    if (insideBraces(line, start)) return false // JSON/properties examples

    val token = leftIdentifier(line, start)
    if (token.isEmpty()) return supportsShorthand
    val lower = token.lowercase()
    if (lower in commonPortLabels) return false
    if (looksLikeUriOrNetworkPort(line, start, token)) return false
    return true
  }

  private fun leftIdentifier(line: String, colon: Int): String {
    var start = colon
    while (start > 0 && line[start - 1] in IDENTIFIER_CHARS) start--
    return line.substring(start, colon)
  }

  private fun insideBraces(line: String, offset: Int): Boolean {
    var depth = 0
    line.take(offset).forEach { char ->
      when (char) {
        '{' -> depth++
        '}' -> if (depth > 0) depth--
      }
    }
    return depth > 0
  }

  private fun looksLikeUriOrNetworkPort(line: String, colon: Int, token: String): Boolean {
    val tokenStart = colon - token.length
    val contextStart = (tokenStart - 32).coerceAtLeast(0)
    val context = line.substring(contextStart, colon)
    if (context.contains("://")) return true
    if (Regex("""\d{1,3}(?:\.\d{1,3}){3}""").matches(token)) return true
    if (token.startsWith("[") || token.endsWith("]")) return true
    // DNS names are lowercase in consumer examples; Java class/method coordinates
    // contain an uppercase class segment or '$'.
    if (token.contains('.') && token == token.lowercase() &&
      Regex("""[a-z0-9-]+(?:\.[a-z0-9-]+)+""").matches(token)) return true
    return false
  }

  private fun normalizeProse(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

  private fun excerpt(line: String): String {
    val compact = line.trim().replace(Regex("""\s+"""), " ")
    return if (compact.length <= 180) compact else compact.take(177) + "..."
  }

  private val IDENTIFIER_CHARS =
    (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '$', '.', '<', '>', '[', ']')).toSet()
}
