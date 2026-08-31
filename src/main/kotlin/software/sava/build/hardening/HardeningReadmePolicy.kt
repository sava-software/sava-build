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
    """(?i)\bline(?:\(s\)|s)?\s+\d{1,6}(?:\s*(?:[-–—/,]|\band\b)\s*\d{1,6})*"""
  )
  private val sourceLLocator = Regex("""\bL\d{1,6}\b""")
  private val colonLocator = Regex(""":\d{1,5}(?:(?:[-/,])\d{1,5})*""")
  private val wrappedCoordinateOwner = Regex(
    """`?[A-Za-z_$][A-Za-z0-9_$<>]*(?:[.$][A-Za-z_$<][A-Za-z0-9_$<>]*)+`?[),;:]?$"""
  )
  private val sourceOwnerVocabulary = Regex("""(?i)\b(?:class|constructor|method|mutator|branch|site)\b""")
  private val simpleCoordinateOwner = Regex("""`?([A-Za-z_$][A-Za-z0-9_$<>]*)`?[),;:]?$""")
  private val backtickedIdentifier = Regex("""`([A-Za-z_$][A-Za-z0-9_.$<>]*)`""")
  private val taskTemplateNotation = Regex("""(?i)\bpitest<[^>\r\n]+>""")
  private val taskTemplateAtCoordinateEnd = Regex(
    """(?i)\bpitest<[^>\r\n]+>[A-Za-z0-9_$]*(?:[`*_~)\]]+)?\s*$"""
  )
  private val mutationVocabulary = Regex(
    """(?:Mutator|ConditionalsBoundary|VoidMethodCall|NullReturnVals|Boolean(?:True|False)ReturnVals|Math|Increments|EQUAL_|ORDER_)"""
  )
  private val commonPortLabels = setOf("host", "hostname", "localhost", "port", "server")
  private val configurationKeyContext = Regex(
    """(?i)\b(?:configuration|config)\s+(?:keys?|fields?|properties|settings?|options?)\b|""" +
      """\b(?:property|setting|option)\s+(?:keys?|fields?|values?)\b"""
  )
  private val configurationListHeader = Regex(
    """(?i)^(?:the\s+)?(?:configuration|config)(?:\s+(?:keys?|fields?|properties|""" +
      """settings?|options?|defaults?))?(?:\s+(?:are|include|follow))?\s*:?\s*$"""
  )
  private val configurationListEntry = Regex(
    """^\s*`?[A-Za-z_$][A-Za-z0-9_.$-]*`?\s*:\s*\d{1,5}""" +
      """(?:\s*[,;|]\s*`?[A-Za-z_$][A-Za-z0-9_.$-]*`?\s*:\s*\d{1,5})*""" +
      """\s*`?\s*(?:#.*)?$"""
  )
  private val knownAbsoluteUri = Regex(
    """(?i)\b(?:https?|ftp|file|git|jar|jdbc|ldap|ldaps|mailto|news|s3|ssh|tel|urn):[^\s`]+"""
  )
  private val shorthandMetadataVocabulary = Regex(
    """(?i)\b(?:clock|duration|error\s*code|errorcode|http|java|jdk|jre|jvm|port|""" +
      """ratio|server|status|time|timeout|utc|version)\b"""
  )

  fun inspect(text: String): Inspection {
    val lines = text.lines()
    val configurationListLines = configurationListLines(lines)
    val sourceLocators = lines.flatMapIndexed { index, line ->
      val configurationContext = configurationListLines[index] ||
        configurationKeyContext.containsMatchIn(line)
      sourceLocators(line, lines.getOrNull(index - 1), configurationContext).map { matched ->
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
      "branch, mutator, and meaningful xN/×N multiplicity. Rewrite or remove inherited " +
      "task-semantics passages; consult $helpTaskPath for installed behavior." +
      "\n  Disposition: This is a non-failing migration advisory."
  }

  private fun sourceLocators(
    line: String,
    previousLine: String?,
    configurationContext: Boolean,
  ): List<MatchResult> {
    val explicit = (explicitLineLocator.findAll(line) + sourceLLocator.findAll(line)).filterNot { match ->
      isStructuredLineTag(line, match.range.first, match.value)
    }
    val colonMatches = colonLocator.findAll(line).toList()
    val fullCoordinates = colonMatches.filter { match ->
      leftIdentifier(line, match.range.first).isNotEmpty() &&
        isSourceColonCandidate(
          line,
          match,
          supportsShorthand = false,
          wrappedOwner = false,
          configurationContext = configurationContext,
        )
    }
    val colon = colonMatches.asSequence().filter { match ->
      val wrappedOwner = leftIdentifier(line, match.range.first).isEmpty() &&
        hasWrappedCoordinateOwner(line, match.range.first, previousLine)
      val earlierBacktickedOwner = hasEarlierBacktickedSourceOwner(
        line,
        match.range.first,
        configurationContext,
      )
      val earlierCoordinate = hasLocalEarlierCoordinate(line, match, fullCoordinates)
      val nearbyMutation = hasNearbyMutationVocabulary(line, match.range.first)
      isSourceColonCandidate(
        line,
        match,
        earlierCoordinate || nearbyMutation || wrappedOwner || earlierBacktickedOwner,
        wrappedOwner,
        configurationContext,
      )
    }
    return (explicit + colon).sortedBy { it.range.first }.toList()
  }

  private fun hasLocalEarlierCoordinate(
    line: String,
    current: MatchResult,
    fullCoordinates: List<MatchResult>,
  ): Boolean {
    val previous = fullCoordinates.lastOrNull { it.range.last < current.range.first } ?: return false
    val intervening = line.substring(previous.range.last + 1, current.range.first)
    return intervening.length <= 180 &&
      intervening.none { it == '.' || it == '!' || it == '?' } &&
      !shorthandMetadataVocabulary.containsMatchIn(intervening)
  }

  private fun hasNearbyMutationVocabulary(line: String, colon: Int): Boolean {
    val sentenceStart = line.substring(0, colon).indexOfLast { it == '.' || it == '!' || it == '?' }
    val sentenceEnd = line.indexOfAny(charArrayOf('.', '!', '?'), colon + 1)
      .let { if (it < 0) line.length else it }
    val start = maxOf(sentenceStart + 1, colon - 80)
    val end = minOf(sentenceEnd, colon + 100)
    return mutationVocabulary.containsMatchIn(line.substring(start, end))
  }

  private fun isInsideNonSourceUriComponent(line: String, offset: Int): Boolean {
    if (knownAbsoluteUri.findAll(line).any { offset in it.range }) return true
    val destinationStart = line.lastIndexOf("](", offset).takeIf { it >= 0 } ?: return false
    val destinationEnd = line.indexOf(')', offset + 1)
    if (destinationEnd < offset) return false
    val destinationPrefix = line.substring(destinationStart + 2, offset)
    return destinationPrefix.contains('/') || destinationPrefix.startsWith('#')
  }

  /**
   * Carry a configuration-list heading only across its entries. A blank line may
   * separate the heading from the first entry, and a fenced block remains active
   * until its closing fence; ordinary prose terminates an unfenced list.
   */
  private fun configurationListLines(lines: List<String>): BooleanArray {
    val result = BooleanArray(lines.size)
    var active = false
    var fenced = false
    lines.forEachIndexed { index, line ->
      val trimmed = structuralMarkdown(line)
      if (configurationListHeader.matches(trimmed)) {
        active = true
        fenced = false
        return@forEachIndexed
      }
      if (!active) return@forEachIndexed

      if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
        if (fenced) {
          active = false
          fenced = false
        } else {
          fenced = true
        }
        return@forEachIndexed
      }
      if (trimmed.isEmpty()) return@forEachIndexed
      if (configurationListEntry.matches(trimmed)) {
        result[index] = true
        return@forEachIndexed
      }
      if (!fenced) active = false
    }
    return result
  }

  /**
   * Markdown reflow can put a Java owner at the end of one line and its `:NNN`
   * locator at the start of the next. Only carry context across one adjacent line,
   * require the coordinate to be the first substantive token, and require a
   * class/method-shaped owner or a source-shaped standalone identifier. Those
   * constraints keep clocks, ratios, versions, ports, and ordinary prose out of
   * the audit.
   */
  private fun hasWrappedCoordinateOwner(
    line: String,
    colon: Int,
    previousLine: String?,
  ): Boolean {
    if (previousLine.isNullOrBlank()) return false
    val prefix = line.substring(0, colon)
    if (prefix.any { it !in WRAPPED_PREFIX_CHARS }) return false

    val previous = normalizeWrappedOwnerMarkdown(
      structuralMarkdown(previousLine).removeSuffix(".").trimEnd()
    )
    val shapedOwner = wrappedCoordinateOwner.find(previous)?.value
    val simpleOwner = simpleCoordinateOwner.find(previous)
    val simpleName = simpleOwner?.groupValues?.get(1)
    val standaloneSimpleOwner = simpleOwner?.value == previous
    return (shapedOwner != null && !isTaskTemplateNotation(shapedOwner) &&
      shapedOwner.any { it.isUpperCase() || it == '$' || it == '<' }) ||
      (simpleName != null &&
        (sourceOwnerVocabulary.containsMatchIn(previous) ||
          (simpleName.lowercase() !in commonNonSourceLabels &&
            standaloneSimpleOwner && looksLikeSimpleSourceOwner(simpleName))))
  }

  /** Strip only common wrappers around the final owner token; leave prose untouched. */
  private fun normalizeWrappedOwnerMarkdown(line: String): String {
    var normalized = line
    while (true) {
      val wrapper = markdownLinkAtEnd.find(normalized) ?: markdownOwnerWrappers
        .firstNotNullOfOrNull { emphasis -> emphasis.find(normalized) }
        ?: break
      normalized = normalized.replaceRange(
        wrapper.range,
        wrapper.groupValues[1] + wrapper.groupValues[2],
      )
    }
    return normalized
  }

  private fun structuralMarkdown(raw: String): String {
    var line = raw.trim()
    while (line.startsWith('>')) line = line.drop(1).trimStart()
    line = line.replaceFirst(Regex("""^#{1,6}\s+"""), "")
    line = line.replaceFirst(Regex("""^(?:[-+*]|\d+[.)])\s+"""), "")
    return line.trim()
  }

  private fun looksLikeSimpleSourceOwner(name: String): Boolean {
    if (isTaskTemplateNotation(name)) return false
    if (name.any { it == '$' }) return true
    val letters = name.filter { it.isLetter() }
    return letters.isNotEmpty() && !letters.all { it.isUpperCase() }
  }

  private fun isStructuredLineTag(line: String, start: Int, matchedText: String): Boolean {
    val prefix = line.substring(0, start)
    if (prefix.trimEnd().endsWith("#") && matchedText.startsWith("line", ignoreCase = true)) {
      return true
    }
    val hash = prefix.lastIndexOf('#')
    return hash >= 0 && prefix.substring(0, hash).count { it == ',' } >= 2
  }

  private fun isSourceColonCandidate(
    line: String,
    match: MatchResult,
    supportsShorthand: Boolean,
    wrappedOwner: Boolean,
    configurationContext: Boolean,
  ): Boolean {
    val start = match.range.first
    if (start > 0 && line[start - 1].isDigit()) return false // clock or ratio
    if (isTaskTemplateCoordinate(line, start)) return false
    if (insideBraces(line, start)) return false // JSON/properties examples
    if (isInsideNonSourceUriComponent(line, start)) return false
    val oneDigit = match.value.drop(1).takeWhile(Char::isDigit).length == 1

    val token = leftIdentifier(line, start)
    if (token.isEmpty()) {
      val owner = immediateCoordinateOwner(line, start)
      if (owner != null) {
        val ownerStart = ownerStartBefore(line, start, owner)
        if (isTaskTemplateNotation(owner) ||
          isClearlyNonSourceOwner(owner, line, ownerStart, configurationContext)) return false
        return supportsShorthand || looksLikeInlineSourceOwner(owner) ||
          hasExplicitSourceOwner(line, ownerStart) ||
          immediateBacktickedOwner.containsMatchIn(line.substring(0, start))
      }
      if (!wrappedOwner && hasLocalShorthandMetadata(line, start)) return false
      if (oneDigit && !wrappedOwner) return false
      return supportsShorthand
    }
    if (isTaskTemplateNotation(token)) return false
    val ownerStart = start - token.length
    if (isClearlyNonSourceOwner(token, line, ownerStart, configurationContext)) return false
    if (looksLikeUriOrNetworkPort(line, start, token)) return false
    // After the explicit non-source filters, any Java-identifier owner is a
    // coordinate. Method names may be all-lowercase (`indexes`, `lambda`) just as
    // legitimately as lowerCamelCase; requiring visual camel humps silently loses
    // ordinary prose locators.
    return true
  }

  private fun looksLikeInlineSourceOwner(owner: String): Boolean {
    if (isTaskTemplateNotation(owner)) return false
    if (owner.any { it == '.' || it == '$' }) return true
    val letters = owner.filter(Char::isLetter)
    return letters.isNotEmpty() && !letters.all(Char::isUpperCase)
  }

  private fun hasEarlierBacktickedSourceOwner(
    line: String,
    colon: Int,
    configurationContext: Boolean,
  ): Boolean {
    val match = backtickedIdentifier.findAll(line.substring(0, colon)).lastOrNull() ?: return false
    val owner = match.groupValues[1]
    val intervening = line.substring(match.range.last + 1, colon)
    return intervening.length <= 160 &&
      !shorthandMetadataVocabulary.containsMatchIn(intervening) &&
      !isClearlyNonSourceOwner(owner, line, match.range.first, configurationContext) &&
      looksLikeInlineSourceOwner(owner)
  }

  private fun isTaskTemplateNotation(owner: String): Boolean =
    taskTemplateNotation.containsMatchIn(owner)

  private fun isTaskTemplateCoordinate(line: String, colon: Int): Boolean =
    taskTemplateAtCoordinateEnd.containsMatchIn(line.substring(0, colon))

  private fun immediateCoordinateOwner(line: String, colon: Int): String? {
    val rawPrefix = line.substring(0, colon)
    if (rawPrefix.lastOrNull()?.isWhitespace() == true) return null
    val prefix = normalizeWrappedOwnerMarkdown(rawPrefix)
    return coordinateOwnerAtEnd.find(prefix)?.groupValues?.get(1)
  }

  private fun ownerStartBefore(line: String, colon: Int, owner: String): Int {
    val start = line.substring(0, colon).lowercase().lastIndexOf(owner.lowercase())
    return if (start >= 0) start else colon
  }

  private fun hasExplicitSourceOwner(line: String, ownerStart: Int): Boolean {
    if (ownerStart <= 0) return false
    val prefix = line.substring(0, ownerStart)
    val previousCoordinateEnd = colonLocator.findAll(prefix).lastOrNull()?.range?.last ?: -1
    val punctuationEnd = prefix.indexOfLast { it == '.' || it == ';' || it == '!' || it == '?' }
    val localPrefix = prefix.substring(maxOf(previousCoordinateEnd, punctuationEnd) + 1)
    return sourceOwnerVocabulary.containsMatchIn(localPrefix)
  }

  private fun hasLocalShorthandMetadata(line: String, colon: Int): Boolean {
    val prefix = line.substring(0, colon)
    val previousCoordinateEnd = colonLocator.findAll(prefix).lastOrNull()?.range?.last ?: -1
    val punctuationEnd = prefix.indexOfLast { it == '.' || it == ';' || it == '!' || it == '?' }
    return shorthandMetadataVocabulary.containsMatchIn(
      prefix.substring(maxOf(previousCoordinateEnd, punctuationEnd) + 1)
    )
  }

  private fun looksLikeQualifiedJavaOwner(owner: String): Boolean =
    owner.any { it == '.' || it == '$' || it == '<' } &&
      owner.any { it.isUpperCase() || it == '$' || it == '<' }

  private fun isClearlyNonSourceOwner(
    owner: String,
    line: String,
    ownerStart: Int,
    configurationContext: Boolean,
  ): Boolean {
    if (hasExplicitSourceOwner(line, ownerStart)) return false
    if (owner.lowercase() in commonNonSourceLabels) return true
    if (configurationContext && !looksLikeQualifiedJavaOwner(owner)) return true
    val letters = owner.filter(Char::isLetter)
    return letters.isNotEmpty() && letters.all(Char::isUpperCase) &&
      !mutationVocabulary.containsMatchIn(owner) &&
      !hasExplicitSourceOwner(line, ownerStart)
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
    if (isInsideNonSourceUriComponent(line, colon)) return true
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
  private val WRAPPED_PREFIX_CHARS = setOf(' ', '\t', '>', '|', '-', '+', '*', '`', '_', '~')
  private val commonNonSourceLabels = commonPortLabels +
    setOf(
      "ratio", "time", "version", "utc", "gmt", "clock", "timeout", "duration",
      "java", "jdk", "jre", "jvm", "status", "statuscode", "httpstatus",
      "errorcode", "exitcode", "responsecode", "code",
    )
  private val markdownLinkAtEnd = Regex("""\[([^]\n]+)]\([^\n]+\)([),;:]?)$""")
  private val markdownOwnerWrappers = listOf(
    Regex("""\*\*(.+?)\*\*([),;:]?)$"""),
    Regex("""__(.+?)__([),;:]?)$"""),
    Regex("""~~(.+?)~~([),;:]?)$"""),
    Regex("""\*(.+?)\*([),;:]?)$"""),
    Regex("""_(.+?)_([),;:]?)$"""),
  )
  private val coordinateOwnerAtEnd = Regex(
    """`?([A-Za-z_$][A-Za-z0-9_$<>]*(?:[.$][A-Za-z_$<][A-Za-z0-9_$<>]*)*)`?[),;:]?$"""
  )
  private val immediateBacktickedOwner = Regex(
    """`[A-Za-z_$][A-Za-z0-9_$<>]*(?:[.$][A-Za-z_$<][A-Za-z0-9_$<>]*)*`\s*$"""
  )
}
