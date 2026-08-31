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
    val lineAnchor: Regex,
  )

  private data class Paragraph(val lines: List<HardeningAgentTemplateBlock.NumberedLine>) {
    val normalized: String = lines.joinToString(" ") { normalizeLine(it.text) }
      .replace(WHITESPACE, " ")
      .trim()

    val auditText: String = maskGenericTaskResultPointers(normalized)

    fun anchoredLine(anchor: Regex): HardeningAgentTemplateBlock.NumberedLine =
      lines.firstOrNull { anchor.containsMatchIn(normalizeLine(it.text)) } ?: lines.first()
  }

  // These signatures come from adoption prose that review already identified as a
  // second account of installed behavior. Keep additions evidence-backed and narrow:
  // this is a migration advisory, not a grammar-based implementation of Rule B.
  private val signatures = listOf(
    Signature(
      "empty-accepted-record",
      regex("""nothing unkilled.{0,120}\bno (?:accepted )?(?:baseline )?file\b"""),
      regex("""nothing unkilled|no (?:accepted )?(?:baseline )?file"""),
    ),
    Signature(
      "empty-timeout-record",
      regex("""no (?:suite )?times? out.{0,120}\bso\b.{0,80}\bno\b.{0,60}timeouts?\.csv"""),
      regex("""times? out|timeouts?\.csv"""),
    ),
    Signature(
      "empty-timeout-record",
      regex("""timeout audit is armed.{0,30}not active"""),
      regex("""timeout audit is armed"""),
    ),
    Signature(
      "local-repository-resolution",
      regex(
        """(?:savabuildlocalrepo|the property|while set).{0,240}""" +
          """(?:adds .{0,40}pluginmanagement|rewrites every .{0,100}plugin id|""" +
          """every software\.sava\.build.{0,100}plugin id resolves .{0,100}0\.0\.0-test|""" +
          """pinned versions? (?:are|is) ignored)"""
      ),
      regex("""savabuildlocalrepo|pluginmanagement|software\.sava\.build|pinned versions?"""),
    ),
    Signature(
      "local-repository-settings-contract",
      regex(
        """(?:savabuildlocalrepo|local-repo(?:sitory)? redirect)[^.!?]{0,220}(?:""" +
          """settings-level property|property of the installed plugin|""" +
          """settings\.gradle\.kts (?:needs?|requires?) no (?:edit|editing|change|changes)|""" +
          """no (?:edit|editing|change|changes) (?:is|are) (?:needed|required) in settings\.gradle\.kts)"""
      ),
      regex("""savabuildlocalrepo|local-repo(?:sitory)? redirect|settings\.gradle\.kts"""),
    ),
    Signature(
      "local-repository-cache",
      regex(
        """(?:silently keeps resolving .{0,80}previously published jar|""" +
          """gradle does re-read file:? repositories .{0,80}each resolution|""" +
          """republished 0\.0\.0-test is picked up immediately|""" +
          """publish is not automatic.{0,120}(?:sava-build|savabuildlocalrepo|0\.0\.0-test)""" +
          """.{0,180}publish task re-?run.{0,120}before this build sees it)"""
      ),
      regex(
        """silently keeps resolving|gradle does re-read|republished 0\.0\.0-test|""" +
          """publish is not automatic"""
      ),
    ),
    Signature(
      "local-repository-notice",
      regex(
        """(?:end-of-build notice|plugin announces? local-repo resolution|build that prints no notice)""" +
          """.{0,220}(?:last-publish|sha-?256|configuration-cache|0\.0\.0-test|every such build)"""
      ),
      regex("""end-of-build notice|announces? local-repo resolution|prints no notice"""),
    ),
    Signature(
      "task-output-granularity",
      regex(
        """acts? on (?:each|every) changed bullet|""" +
          """resolves? (?:each|every) prose candidate (?:it|the (?:audit|task)) names?"""
      ),
      regex("""acts? on (?:each|every) changed bullet|resolves? (?:each|every) prose candidate"""),
    ),
    Signature(
      "installed-help-output",
      regex(
        """hardeninghelp[^.!?]{0,100}""" +
          """\b(?:prints?|reports?|lists?)\b[^.!?]{0,100}""" +
          """\b(?:tasks?|properties|workflows?|outputs?|surface|projects?|suites?)\b|""" +
          """hardeninghelp output[^.!?]{0,100}\b(?:same|uniform|project)\b"""
      ),
      regex("""hardeninghelp"""),
    ),
    Signature(
      "installed-template-output",
      regex(
        """hardeningagenttemplate(?!diff).{0,120}""" +
          """\b(?:prints?|reports?|emits?)\b|""" +
          """print the installed version with.{0,180}hardeningagenttemplate(?!diff)"""
      ),
      regex("""print the installed version|hardeningagenttemplate(?!diff)"""),
    ),
    Signature(
      "installed-template-diff",
      regex(
        """hardeningagenttemplatediff.{0,120}""" +
          """\b(?:diffs?|compares?|normalizes?|reports?)\b|""" +
          """re-?diff (?:it|this|the (?:bounded )?block).{0,80}(?:with|using)""" +
          """.{0,180}hardeningagenttemplatediff"""
      ),
      regex(
        """re-?diff (?:it|this|the (?:bounded )?block)|hardeningagenttemplatediff"""
      ),
    ),
    Signature(
      "template-sync-contract",
      regex(
        """agentstemplateinsync.{0,200}(?:fails?\s+(?:when|until)|warns?\s+when|""" +
          """gates?\b|requires?\b|runs?\s+(?:inside|as part of)|(?:is\s+)?wired\s+into)"""
      ),
      regex("""agentstemplateinsync"""),
    ),
    Signature(
      "certification-contract",
      regex(
        """hardeningcertify.{0,220}(?:writes?|publishes?|invalidates?|re-?executes?|""" +
          """refuses?|disables?|receipts?\s+(?:is|are|covers?))"""
      ),
      regex("""hardeningcertify"""),
    ),
    Signature(
      "baseline-writer-contract",
      regex(
        """(?:pitest(?:<suite>|[a-z0-9]+)?baseline|baseline)""" +
          """(?:rebase|update|union|retag|prune).{0,180}""" +
          """(?:writes?|rewrites?|preserves?|removes?|appends?|seeds?|stamps?|refuses?|requires?)"""
      ),
      regex("""(?:pitest(?:<suite>|[a-z0-9]+)?baseline|baseline)(?:rebase|update|union|retag|prune)"""),
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
      regex("""declineseedcorpus|declinemutator|declineexclusionaudit|excludetestclass"""),
    ),
    Signature(
      "hardening-dsl-contract",
      regex(
        """(?:seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses)""" +
          """[^.!?]{0,120}\b(?:is (?:required|optional)|defaults? to|accepts?|filters?|removes?|""" +
          """excludes?|selects?|registers?|creates?)\b|""" +
          """(?:mutation|fuzz)(?: suite| target)? (?:register|registration|block)""" +
          """[^.!?]{0,120}\b(?:adds?|creates?|registers?|generates?|wires?)\b"""
      ),
      regex(
        """seedcorpus|excludedclasses|targetclasses|targettests|excludedtestclasses|""" +
          """(?:mutation|fuzz)(?: suite| target)? (?:register|registration|block)"""
      ),
    ),
    Signature(
      "sibling-plugin-contract",
      regex(
        """(?:software\.sava\.build\.(?!feature\.hardening)[a-z0-9_.-]+|""" +
          """jdk[- ]provisioning|foojay(?: resolver)?)""" +
          """[^.!?]{0,180}\b(?:is automatic|happens? automatically|automatically (?:provisions?|""" +
          """downloads?|resolves?|selects?|configures?)|defaults? to|falls? back|""" +
          """requires?|provides?|registers?|creates?)\b"""
      ),
      regex(
        """software\.sava\.build\.(?!feature\.hardening)[a-z0-9_.-]+|""" +
          """jdk[- ]provisioning|foojay(?: resolver)?"""
      ),
    ),
  )

  fun inspect(outsideLines: List<HardeningAgentTemplateBlock.NumberedLine>): Inspection {
    val paragraphs = paragraphs(outsideLines)
    val passages = paragraphs.mapNotNull { paragraph ->
      val matches = signatures.mapNotNull { signature ->
        signature.matcher.find(paragraph.auditText)?.let { match -> signature to match }
      }.sortedBy { (_, match) -> match.range.first }
      if (matches.isEmpty()) return@mapNotNull null

      // One Markdown paragraph or list item is one review passage even when several
      // rules match on different wrapped source lines. Retain every rule and phrase,
      // and anchor the warning at the earliest line that contains a matching noun.
      val line = matches.map { (signature, _) -> paragraph.anchoredLine(signature.lineAnchor) }
        .minBy { it.lineNumber }
      Finding(
        matches.map { (signature, match) ->
          RuleEvidence(signature.family, matchedPhrase(match.value))
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
      val rules = finding.rules.joinToString("; ") { rule ->
        "${rule.family}: \"${rule.matchedPhrase}\""
      }
      "    AGENTS.md:${finding.lineNumber}  [$rules] ${finding.excerpt}"
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

  private fun normalizeLine(raw: String): String = structuralLine(raw)
    .replace(MARKDOWN_DECORATION, "")
    .lowercase()

  private fun excerpt(line: String): String {
    val compact = line.trim().replace(WHITESPACE, " ")
    return if (compact.length <= 180) compact else compact.take(177) + "..."
  }

  private fun matchedPhrase(value: String): String {
    val compact = value.trim().replace(WHITESPACE, " ")
    return if (compact.length <= 96) compact
    else compact.take(45) + " ... " + compact.takeLast(46)
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
        match.value.replace(REPORT_WORD, "result")
      }
    }

  private fun regex(pattern: String) = Regex(pattern)

  private val WHITESPACE = Regex("""\s+""")
  private val MARKDOWN_DECORATION = Regex("""[`*_]+""")
  private val LIST_ITEM = Regex("""^(?:[-+*]|\d+[.)])\s+""")
  private val HEADING = Regex("""^#{1,6}\s+""")
  private val REPORT_WORD = Regex("""\breports?\b""")
  private val GENERIC_TASK_RESULT_POINTERS = listOf(
    Regex(
      """\bacts? on everything (?:those|both|the|these) tasks? reports?\b"""
    ),
    Regex("""\bacts? on (?:everything in )?its reports?\b"""),
    Regex("""\bacts? on (?:everything|what|whatever) (?:it|they) reports?\b"""),
  )
}
