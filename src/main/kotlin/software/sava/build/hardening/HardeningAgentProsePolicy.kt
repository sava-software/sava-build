package software.sava.build.hardening

/**
 * Conservative migration audit for repository-owned prose outside the generated
 * hardening block in a consumer `AGENTS.md`.
 *
 * This deliberately matches field-observed descriptions of installed plugin behavior,
 * not arbitrary words such as "fails" or "writes". A free-form prose linter would
 * confuse repository-specific code and workflow facts with copied hardening mechanics.
 */
internal object HardeningAgentProsePolicy {

  internal data class Finding(
    val family: String,
    val lineNumber: Int,
    val excerpt: String,
  )

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
      "installed-help-output",
      regex(
        """hardeninghelp[^.!?]{0,100}\b(?:prints?|reports?|lists?)\b[^.!?]{0,100}""" +
          """\b(?:tasks?|properties|workflows?|outputs?|surface|projects?|suites?)\b|""" +
          """hardeninghelp output[^.!?]{0,100}\b(?:same|uniform|project)\b"""
      ),
      regex("""hardeninghelp"""),
    ),
    Signature(
      "installed-template-output",
      regex(
        """hardeningagenttemplate(?!diff).{0,120}\b(?:prints?|reports?|emits?)\b|""" +
          """print the installed version with.{0,180}hardeningagenttemplate(?!diff)"""
      ),
      regex("""print the installed version|hardeningagenttemplate(?!diff)"""),
    ),
    Signature(
      "installed-template-diff",
      regex(
        """hardeningagenttemplatediff.{0,120}\b(?:diffs?|compares?|normalizes?|reports?)\b|""" +
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
  )

  fun inspect(outsideLines: List<HardeningAgentTemplateBlock.NumberedLine>): Inspection {
    val paragraphs = paragraphs(outsideLines)
    val findings = paragraphs.flatMap { paragraph ->
      signatures.asSequence()
        .filter { it.matcher.containsMatchIn(paragraph.normalized) }
        .groupBy { it.family }
        .values
        .map { matchingFamily ->
          val signature = matchingFamily.first()
          val line = paragraph.anchoredLine(signature.lineAnchor)
          Finding(signature.family, line.lineNumber, excerpt(line.text))
        }
    }
    // Adjacent paragraphs can contain two versions of one copied contract. Keep each
    // actual passage, but never count several needles in that passage as several debts.
    return Inspection(findings.sortedBy { it.lineNumber })
  }

  fun warning(inspection: Inspection, helpTaskPath: String): String {
    require(!inspection.isClean) { "clean AGENTS.md prose has no audit warning" }
    val count = inspection.findings.size
    val noun = if (count == 1) "passage" else "passages"
    val details = inspection.findings.joinToString("\n") { finding ->
      "    AGENTS.md:${finding.lineNumber}  ${finding.excerpt}"
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

  private fun regex(pattern: String) = Regex(pattern)

  private val WHITESPACE = Regex("""\s+""")
  private val MARKDOWN_DECORATION = Regex("""[`*_]+""")
  private val LIST_ITEM = Regex("""^(?:[-+*]|\d+[.)])\s+""")
  private val HEADING = Regex("""^#{1,6}\s+""")
}
