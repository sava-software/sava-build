package software.sava.build.hardening

/**
 * Conservative migration audit for repository-owned prose outside the generated
 * hardening block in a consumer `AGENTS.md`.
 *
 * This deliberately matches field-observed descriptions of installed plugin behavior
 * and narrowly pairs known plugin/DSL nouns with contract verbs. It does not match
 * arbitrary words such as "fails" or "writes": a free-form prose linter would confuse
 * repository-specific code and workflow facts with copied hardening mechanics.
 */
internal object HardeningAgentProsePolicy {

  internal data class RuleEvidence(
    val family: String,
    val lineNumber: Int,
    val matchedPhrase: String,
  )

  internal data class Finding(
    val rules: List<RuleEvidence>,
    val lineNumber: Int,
    val excerpt: String,
  ) {
    val family: String
      get() = rules.map { it.family }.distinct().joinToString("+")
  }

  internal data class Inspection(val findings: List<Finding>) {
    val isClean: Boolean
      get() = findings.isEmpty()
  }

  private data class Signature(
    val family: String,
    val matcher: Regex,
    val accepts: (String, MatchResult) -> Boolean = { _, _ -> true },
  )

  private data class LocatedMatch(
    val signature: Signature,
    val match: MatchResult,
    val evidence: SourceEvidence,
  )

  private data class Paragraph(val lines: List<HardeningAgentTemplateBlock.NumberedLine>) {
    private val normalizedPassage = NormalizedPassage.from(lines)

    val normalized: String = normalizedPassage.text

    val auditText: String = maskGenericTaskResultPointers(normalized)

    fun evidence(range: IntRange): SourceEvidence = normalizedPassage.evidence(range)

    fun line(lineNumber: Int): HardeningAgentTemplateBlock.NumberedLine =
      lines.first { it.lineNumber == lineNumber }
  }

  private data class SourceEvidence(val lineNumber: Int, val phrase: String)

  /**
   * Normalization is intentionally lossy for matching (case, Markdown decoration,
   * and wrapped whitespace do not affect a signature), but diagnostics must quote
   * source text rather than the normalized or masked matcher input. Retain the source
   * offset for every normalized character so a match can be projected back onto the
   * original paragraph.
   */
  private data class NormalizedPassage(
    val text: String,
    val source: String,
    val sourceOffsets: IntArray,
    val sourceLines: List<SourceLine>,
  ) {
    fun evidence(range: IntRange): SourceEvidence {
      require(!range.isEmpty() && sourceOffsets.isNotEmpty()) {
        "a normalized prose match must contain source text"
      }
      val matchStart = sourceOffsets[range.first]
      val matchEnd = sourceOffsets[range.last]
      val sourceLine = sourceLines.first { matchEnd in it.startOffset..it.endOffset }
      val phraseStart = maxOf(matchStart, sourceLine.startOffset)
      val phrase = source.substring(phraseStart, matchEnd + 1).trim()
      return SourceEvidence(sourceLine.lineNumber, phrase)
    }

    companion object {
      fun from(lines: List<HardeningAgentTemplateBlock.NumberedLine>): NormalizedPassage {
        val structuralLines = lines.map { it.lineNumber to structuralLine(it.text) }
        val source = structuralLines.joinToString("\n") { (_, text) -> text }
        var nextOffset = 0
        val sourceLines = structuralLines.map { (lineNumber, text) ->
          val startOffset = nextOffset
          val endOffset = startOffset + text.length
          nextOffset = endOffset + 1
          SourceLine(lineNumber, startOffset, endOffset)
        }
        val normalized = StringBuilder(source.length)
        val offsets = mutableListOf<Int>()
        var pendingWhitespaceOffset: Int? = null

        source.forEachIndexed { index, char ->
          when {
            char == '`' || char == '*' || char == '_' -> Unit
            char.isWhitespace() -> {
              if (normalized.isNotEmpty() && pendingWhitespaceOffset == null) {
                pendingWhitespaceOffset = index
              }
            }
            else -> {
              pendingWhitespaceOffset?.let { whitespaceOffset ->
                normalized.append(' ')
                offsets += whitespaceOffset
              }
              pendingWhitespaceOffset = null
              normalized.append(char.lowercaseChar())
              offsets += index
            }
          }
        }
        return NormalizedPassage(
          normalized.toString(),
          source,
          offsets.toIntArray(),
          sourceLines,
        )
      }
    }
  }

  private data class SourceLine(
    val lineNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
  )

  // These signatures come from adoption prose that review already identified as a
  // second account of installed behavior. Keep additions evidence-backed and narrow:
  // this is a migration advisory, not a grammar-based implementation of Rule B.
  private val signatures = listOf(
    Signature(
      "empty-accepted-record",
      regex(
        """nothing unkilled""" + sentenceSpan(120) +
          """\bno (?:accepted )?(?:baseline )?file\b"""
      ),
    ),
    Signature(
      "empty-timeout-record",
      regex(
        """no (?:suite )?times? out""" + sentenceSpan(120) +
          """\bso\b""" + sentenceSpan(80) +
          """\bno\b""" + sentenceSpan(60) + """timeouts?\.csv"""
      ),
    ),
    Signature(
      "empty-timeout-record",
      regex("""timeout audit is armed""" + sentenceSpan(30) + """not active"""),
    ),
    Signature(
      "local-repository-resolution",
      regex(
        """(?:savabuildlocalrepo|the property|while set)""" + sentenceSpan(240) +
          """(?:adds """ + sentenceSpan(40) + """pluginmanagement|""" +
          """rewrites every """ + sentenceSpan(100) + """plugin id|""" +
          """every software\.sava\.build""" + sentenceSpan(100) +
          """plugin id resolves """ + sentenceSpan(100) + """0\.0\.0-test|""" +
          """pinned versions? (?:are|is) ignored)"""
      ),
    ),
    Signature(
      "local-repository-settings-contract",
      regex(
        """(?:savabuildlocalrepo|local-repo(?:sitory)? redirect)[^.!?]{0,220}(?:""" +
          """settings-level property|property of the installed plugin|""" +
          """settings\.gradle\.kts (?:needs?|requires?) no (?:edit|editing|change|changes)|""" +
          """no (?:edit|editing|change|changes) (?:is|are) (?:needed|required) in settings\.gradle\.kts)"""
      ),
    ),
    Signature(
      "local-repository-cache",
      regex(
        """(?:silently keeps resolving """ + sentenceSpan(80) +
          """previously published jar|gradle does re-read file:? repositories """ +
          sentenceSpan(80) + """each resolution|""" +
          """republished 0\.0\.0-test is picked up immediately|""" +
          """publish is not automatic""" + sentenceSpan(120) +
          """(?:sava-build|savabuildlocalrepo|0\.0\.0-test)""" + sentenceSpan(180) +
          """publish task re-?run""" + sentenceSpan(120) +
          """before this build sees it)"""
      ),
    ),
    Signature(
      "local-repository-notice",
      regex(
        """(?:end-of-build notice|plugin announces? local-repo resolution|build that prints no notice)""" +
          sentenceSpan(220) +
          """(?:last-publish|sha-?256|configuration-cache|0\.0\.0-test|every such build)"""
      ),
    ),
    Signature(
      "task-output-granularity",
      regex(
        """(?:hardeningagenttemplatediff|the (?:template )?diff (?:task|audit))""" +
          """(?:'s)?(?: (?:task|output))?\s+""" +
          """(?:emits?|prints?|reports?|returns?|names?)\b""" +
          """[^.!?]{0,30}\b(?:each|every|one) changed bullet\b|""" +
          """(?:hardeningagentproseaudit|the prose audit)""" +
          """(?:'s)?(?: (?:task|output))?\s+""" +
          """(?:emits?|prints?|reports?|returns?|names?)\b""" +
          """[^.!?]{0,30}\b(?:each|every|one) (?:prose )?candidate\b"""
      ),
    ),
    Signature(
      "installed-help-output",
      regex(
        """hardeninghelp[^.!?]{0,100}""" +
          """\b(?:prints?|reports?|lists?)\b[^.!?]{0,100}""" +
          """\b(?:tasks?|properties|workflows?|outputs?|surface|projects?|suites?)\b|""" +
          """hardeninghelp output[^.!?]{0,100}\b(?:same|uniform|project)\b"""
      ),
    ),
    Signature(
      "installed-template-output",
      regex(
        """hardeningagenttemplate(?!diff)(?:'s)?(?: (?:task|output))?\s+""" +
          """(?:prints?|reports?|emits?)\b|""" +
          """print the installed version with(?:[^.!?]|\.(?!\s)){0,180}""" +
          """hardeningagenttemplate(?!diff)"""
      ),
    ),
    Signature(
      "installed-template-diff",
      regex(
          """hardeningagenttemplatediff(?:'s)?(?: (?:task|output))?\s+""" +
          """(?:diffs?|compares?|normalizes?|reports?)\b|""" +
          """re-?diff (?:it|this|the (?:bounded )?block)""" +
          """(?:[^.!?]|\.(?!\s)){0,80}(?:with|using)""" +
          """(?:[^.!?]|\.(?!\s)){0,180}hardeningagenttemplatediff"""
      ),
    ),
    Signature(
      "template-sync-contract",
      regex(
        """agentstemplateinsync(?:'s)?(?: (?:task|gate))?\s+(?:""" +
          """fails?\s+(?:when|until)|warns?\s+when|""" +
          """gates?\b|requires?\b|runs?\s+(?:inside|as part of)|(?:is\s+)?wired\s+into)"""
      ),
    ),
    Signature(
      "certification-contract",
      regex(
        """hardeningcertify(?:'s)?(?: (?:task|workflow|receipt))?\s+""" +
          """(?:writes?|publishes?|invalidates?|re-?executes?|refuses?|disables?|covers?|""" +
          """receipts?\s+(?:is|are|covers?))\b"""
      ),
    ),
    Signature(
      "baseline-writer-contract",
      regex(
        """(?:pitest(?:<suite>|[a-z0-9]+)?baseline|baseline)""" +
          """(?:rebase|update|union|retag|prune)""" +
          """(?:'s)?(?: (?:task|writer))?\s+""" +
          """(?:writes?|rewrites?|preserves?|removes?|appends?|seeds?|stamps?|refuses?|requires?)"""
      ),
    ),
    Signature(
      "hardening-dsl-contract",
      regex(
        """(?:declineseedcorpus|declinemutator|declineexclusionaudit|excludetestclass)""" +
          """(?:\s*/\s*(?:declineseedcorpus|declinemutator|declineexclusionaudit|excludetestclass))*""" +
          """[^.!?]{0,100}(?:requires?|accepts?|takes?|must have|with)""" +
          """\s+(?:an?\s+)?(?:measured|non-?blank|written|documented|explicit|reason-bearing)?\s*reason\b|""" +
          """(?:declineseedcorpus|declinemutator|declineexclusionaudit|excludetestclass)""" +
          """[^.!?]{0,140}\b(?:suppresses?|disables?|omits?|skips?|becomes? a no-op|is a no-op)\b"""
      ),
    ),
    Signature(
      "hardening-dsl-contract",
      regex(
        """(?:hardening dsl|(?:the )?(?:hardening )?plugin|""" +
          """software\.sava\.build\.feature\.hardening)""" +
          """[^.!?]{0,80}\b(?:requires?|uses?|accepts?|configures?|populates?)\b""" +
          """[^.!?]{0,80}\b(?:seedcorpus|excludedclasses|targetclasses|targettests|""" +
          """excludedtestclasses)\b|""" +
          """(?:the )?hardening plugin's\s+""" +
          """(?:seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses)""" +
          """(?: (?:property|setting|value|list|patterns?))?\s+""" +
          """(?:is (?:required|optional)|defaults? to|accepts?|filters?|removes?|""" +
          """excludes?|selects?|registers?|creates?)\b|""" +
          """within (?:the )?hardening plugin,\s+""" +
          """(?:seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses)""" +
          """(?: (?:property|setting|value|list|patterns?))?\s+""" +
          """(?:is (?:required|optional)|defaults? to|accepts?|filters?|removes?|""" +
          """excludes?|selects?|registers?|creates?)\b"""
      ),
    ),
    Signature(
      "hardening-dsl-contract",
      regex(
          """(?:^|[.!?]\s+|;\s+|,\s+(?:and|while)\s+)(?:the )?""" +
          """(?:seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses)""" +
          """(?: (?:property|setting|value|list|patterns?))?\s+(?:""" +
          """is (?:required|optional)|defaults? to|accepts?|filters?|removes?|""" +
          """excludes?|selects?|registers?|creates?)\b|""" +
          """(?:^|[.!?]\s+|;\s+|,\s+(?:and|while)\s+)(?:a |the )?""" +
          """(?:mutation|fuzz)(?: suite| target)? """ +
          """(?:(?:register|registration)(?: block)?|block)""" +
          """[^.!?]{0,80}\b(?:adds?|creates?|registers?|generates?|wires?)\b""" +
          """[^.!?]{0,60}\b(?:pit |gradle |verification |fuzz )?tasks?\b"""
      ),
      accepts = { text, match -> !isRepositoryLocalDslAssertion(text, match) },
    ),
    Signature(
      "sibling-plugin-contract",
      regex(
        """(?:software\.sava\.build\.(?!feature\.hardening)[a-z0-9_.-]+)(?: plugin)?\s+""" +
          """(?:is automatic|happens? automatically|automatically (?:provisions?|downloads?|""" +
          """resolves?|selects?|configures?)|defaults? to|falls? back|requires?|provides?|""" +
          """registers?|creates?)\b|""" +
          """(?:jdk[- ]provisioning|foojay(?: resolver)?)\s+(?:is automatic|""" +
          """happens? automatically|automatically (?:provisions?|downloads?|resolves?|""" +
          """selects?|configures?)|defaults? to|falls? back|requires?|provides?|""" +
          """registers?|creates?)\b"""
      ),
    ),
  )

  fun inspect(outsideLines: List<HardeningAgentTemplateBlock.NumberedLine>): Inspection {
    val paragraphs = paragraphs(outsideLines)
    val passages = paragraphs.mapNotNull { paragraph ->
      val matches = signatures.mapNotNull { signature ->
        signature.matcher.findAll(paragraph.auditText)
          .firstOrNull { match -> signature.accepts(paragraph.auditText, match) }
          ?.let { match ->
          LocatedMatch(signature, match, paragraph.evidence(match.range))
        }
      }.sortedBy { it.match.range.first }
      if (matches.isEmpty()) return@mapNotNull null

      // One Markdown paragraph or list item is one review passage even when several
      // rules match on different wrapped source lines. Retain every rule and phrase.
      // Anchor at evidence from the actual match, never at an earlier noun-only mention.
      val lineNumber = matches.minOf { it.evidence.lineNumber }
      val line = paragraph.line(lineNumber)
      Finding(
        matches.map { located ->
          RuleEvidence(
            located.signature.family,
            located.evidence.lineNumber,
            located.evidence.phrase,
          )
        }.distinct(),
        line.lineNumber,
        excerpt(line.text),
      )
    }
    return Inspection(passages)
  }

  fun warning(inspection: Inspection, helpTaskPath: String): String {
    require(!inspection.isClean) { "clean AGENTS.md prose has no audit warning" }
    val count = inspection.findings.size
    val noun = if (count == 1) "passage" else "passages"
    val details = inspection.findings.joinToString("\n") { finding ->
      val rules = finding.rules.joinToString("\n") { rule ->
        "      AGENTS.md:${rule.lineNumber}  " +
          "[${rule.family}: \"${rule.matchedPhrase}\"]"
      }
      "    Passage: ${finding.excerpt}\n$rules"
    }
    return "hardeningAgentProseAudit: AGENTS.md carries $count likely copied " +
      "plugin-mechanics $noun outside the generated hardening block.\n" +
      "  Candidate passages:\n$details\n" +
      "  Review: Local prose may name a project-qualified task and say when repository " +
      "policy invokes it. Task output, pass/fail or warning conditions, refusals, " +
      "normalization, and fallback behavior belong to $helpTaskPath. The bounded " +
      "generated block is deliberately excluded from this audit.\n" +
      "  Remedy: Preserve repository-local ownership, measurements, reasons, provenance, " +
      "and invocation timing; replace copied mechanics with a pointer to $helpTaskPath.\n" +
      "  Disposition: This is a non-failing migration advisory."
  }

  private fun paragraphs(
    lines: List<HardeningAgentTemplateBlock.NumberedLine>,
  ): List<Paragraph> {
    val result = mutableListOf<Paragraph>()
    val current = mutableListOf<HardeningAgentTemplateBlock.NumberedLine>()
    var previousLineNumber: Int? = null
    var fence: Fence? = null

    fun flush() {
      if (current.isNotEmpty()) result += Paragraph(current.toList())
      current.clear()
    }

    lines.forEach { line ->
      if (previousLineNumber != null && line.lineNumber != previousLineNumber!! + 1) flush()
      previousLineNumber = line.lineNumber
      val structural = structuralLine(line.text)
      val activeFence = fence
      if (activeFence != null) {
        if (activeFence.closes(structural)) fence = null
        return@forEach
      }
      Fence.opening(structural)?.let { opening ->
        flush()
        fence = opening
        return@forEach
      }
      if (structural.isBlank()) {
        flush()
      } else {
        val standalone = isHeading(structural) || isTableRow(structural)
        if ((isListItem(structural) || standalone) && current.isNotEmpty()) flush()
        current += line
        if (standalone) flush()
      }
    }
    flush()
    return result
  }

  private data class Fence(val marker: Char, val length: Int) {
    fun closes(line: String): Boolean =
      line.takeWhile { it == marker }.length >= length && line.dropWhile { it == marker }.isBlank()

    companion object {
      fun opening(line: String): Fence? {
        val marker = line.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = line.takeWhile { it == marker }.length
        return if (length >= 3) Fence(marker, length) else null
      }
    }
  }

  private fun structuralLine(raw: String): String {
    var line = raw.trimStart()
    if (line.startsWith('>')) line = line.drop(1).trimStart()
    return line
  }

  private fun isListItem(line: String): Boolean = LIST_ITEM.containsMatchIn(line)

  private fun isHeading(line: String): Boolean = HEADING.containsMatchIn(line)

  private fun isTableRow(line: String): Boolean = line.startsWith('|')

  private fun excerpt(line: String): String {
    val compact = line.trim().replace(WHITESPACE, " ")
    return if (compact.length <= 180) compact else compact.take(177) + "..."
  }

  /**
   * A bare DSL property at the start of a sentence can state either installed
   * behavior or repository policy. Keep the latter when the remainder of that same
   * sentence names a local rationale or owner; an explicit plugin owner remains an
   * installed-contract claim.
   */
  private fun isRepositoryLocalDslAssertion(text: String, match: MatchResult): Boolean {
    if (!DSL_PROPERTY.containsMatchIn(match.value)) return false
    val tailStart = match.range.last + 1
    val sentenceEnd = text.indexOfAny(charArrayOf('.', '!', '?'), tailStart)
      .let { if (it < 0) text.length else it }
    val tail = text.substring(tailStart, sentenceEnd)
    if (HARDENING_PLUGIN_OWNER.containsMatchIn(tail)) return false
    return LOCAL_DSL_RATIONALE.containsMatchIn(tail)
  }

  /**
   * A pointer that tells an operator to act on a task's result does not describe that
   * result's shape or contract. Mask only the reporting word inside those generic
   * references before applying task-contract signatures; copied descriptions elsewhere
   * in the same passage remain visible, as does output-granularity wording.
   */
  private fun maskGenericTaskResultPointers(value: String): String =
    GENERIC_TASK_RESULT_POINTERS.fold(value) { masked, pointer ->
      pointer.replace(masked) { match ->
        match.value.replace(RESULT_WORD) { result -> "~".repeat(result.value.length) }
      }
    }

  /** Match wrapped text inside one sentence while still allowing dots in paths and versions. */
  private fun sentenceSpan(maximum: Int): String =
    """(?:[^.!?]|\.(?!\s)){0,$maximum}"""

  private fun regex(pattern: String) = Regex(pattern)

  private val WHITESPACE = Regex("""\s+""")
  private val MARKDOWN_DECORATION = Regex("""[`*_]+""")
  private val LIST_ITEM = Regex("""^(?:[-+*]|\d+[.)])\s+""")
  private val HEADING = Regex("""^#{1,6}\s+""")
  private val DSL_PROPERTY = Regex(
    """\b(?:seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses)\b"""
  )
  private val HARDENING_PLUGIN_OWNER = Regex(
    """\b(?:hardening )?plugin\b|software\.sava\.build\.feature\.hardening"""
  )
  private val LOCAL_DSL_RATIONALE = Regex(
    """\b(?:because|so that|owned by|maintained by|provided by|policy|rationale|reason)\b|""" +
      """\b(?:local|repository|repo|project|module|suite)[- ]owned\b"""
  )
  private val RESULT_WORD = Regex("""\b(?:reports?|prints?)\b""")
  private val GENERIC_TASK_RESULT_POINTERS = listOf(
    Regex(
      """\b(?:acts? on|resolves?) (?:everything|what|whatever) """ +
        """(?:that|this|those|both|the|these) (?:tasks?|audits?|checks?) """ +
        """(?:reports?|prints?)\b"""
    ),
    Regex(
      """\b(?:acts? on|resolves?) (?:everything in )?its (?:reports?|prints?)\b"""
    ),
    Regex(
      """\b(?:acts? on|resolves?) (?:everything|what|whatever) """ +
        """(?:it|they) (?:reports?|prints?)\b"""
    ),
  )
}
