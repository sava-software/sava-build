package software.sava.build.hardening

/**
 * Structural parsing shared by the AGENTS.md template diff and acknowledgment gate.
 *
 * The digest remains an acknowledgment rather than a checksum of consumer prose. This
 * parser recognizes markers and boundaries only outside Markdown code, enforces the
 * supported legacy quote layer, and checks one strong misplaced-boundary signal: the
 * canonical shared template is a list, so an ATX heading belongs outside the block.
 */
internal object HardeningAgentTemplateBlock {

  const val BLOCK_START = "<!-- hardening-template block:start -->"
  const val BLOCK_END = "<!-- hardening-template block:end -->"

  data class Parsed(val lines: List<String>)

  data class Marker(
    val digest: String,
    val line: Int,
    internal val index: Int,
  )

  data class Inspection(
    val marker: Marker?,
    val hasAnyBoundary: Boolean,
  )

  private data class AtxHeading(
    val line: Int,
    val text: String,
  )

  class Invalid(message: String) : IllegalArgumentException(message)

  fun inspect(lines: List<String>): Inspection {
    val scanned = scan(lines)
    return Inspection(
      marker = requireSingleMarker(scanned.markers),
      hasAnyBoundary = scanned.starts.isNotEmpty() || scanned.ends.isNotEmpty(),
    )
  }

  fun parse(lines: List<String>): Parsed {
    val scanned = scan(lines)
    val starts = scanned.starts
    val ends = scanned.ends
    if (starts.size != 1 || ends.size != 1 || starts.single().index >= ends.single().index) {
      throw Invalid(boundaryFailure())
    }

    val start = starts.single()
    val end = ends.single()
    if (start.quoted != end.quoted) {
      throw Invalid(
        "the hardening template boundaries mix quoted and unquoted presentation; " +
          "quote both boundary lines or neither."
      )
    }
    requireSingleMarker(scanned.markers)?.let { marker ->
      if (marker.index in (start.index + 1)..<end.index) {
        throw Invalid(
          "the hardening-template digest marker is inside the bounded block at " +
            "AGENTS.md line ${marker.line}; keep the marker outside the boundary pair."
        )
      }
    }
    val block = lines.subList(start.index + 1, end.index)
    // The migration guidance deliberately gives consumers unquoted, copy-ready
    // boundary lines. Legacy bodies may still carry the uniform Markdown quote
    // layer used before 21.5.25, so infer that layer from the body when the new
    // boundaries are unquoted. Quoted boundaries remain an explicit requirement
    // that the complete body use the same presentation.
    val quoteNormalized = normalizeQuoteLayer(
      block,
      expectedQuoted = if (start.quoted) true else null,
    )
    val leadingBlankCount = quoteNormalized.indexOfFirst { !it.isBlank() }.let { first ->
      if (first < 0) quoteNormalized.size else first
    }
    val normalized = quoteNormalized
      .drop(leadingBlankCount)
      .dropLastWhile(String::isBlank)
    if (normalized.isEmpty()) {
      throw Invalid("the bounded hardening template block in AGENTS.md is empty.")
    }

    val normalizedStartLine = start.index + 2 + leadingBlankCount
    firstAtxHeading(normalized, normalizedStartLine)?.let { heading ->
      throw Invalid(
        "the bounded hardening template block contains a Markdown ATX heading at " +
          "AGENTS.md line ${heading.line}: ${heading.text.trim()}. The shared template is " +
          "bullets-only; move $BLOCK_END before repository-specific headings and facts."
      )
    }
    return Parsed(normalized)
  }

  /** Refuses a future canonical template heading before it can invalidate every consumer. */
  fun requireCanonicalHeadingFree(lines: List<String>) {
    firstAtxHeading(lines, 1)?.let { heading ->
      throw IllegalStateException(
        "the installed hardening agent template must remain bullets-only; found a Markdown " +
          "ATX heading at template line ${heading.line}: ${heading.text.trim()}"
      )
    }
  }

  fun boundaryFailure(): String =
    "AGENTS.md must contain exactly one ordered boundary pair around only the hardening " +
      "template block. Wrap the existing adapted block between these exact lines:\n" +
      "  $BLOCK_START\n  ... existing adapted hardening block ...\n  $BLOCK_END\n" +
      "Place the start immediately before the first shared hardening rule and the end " +
      "immediately after the last; keep the digest marker and all repository-specific " +
      "facts outside that pair. This check will not guess the block from headings or " +
      "digest-marker placement. " + canonicalFinalLayoutGuidance()

  fun boundaryMigrationGuidance(): String =
    "Before running the diff, wrap the existing adapted hardening block between these " +
      "exact lines:\n  $BLOCK_START\n  ... existing adapted hardening block ...\n" +
      "  $BLOCK_END\nPlace the start immediately before the first shared hardening rule and " +
      "the end immediately after the last; keep the digest marker and all " +
      "repository-specific facts outside that pair. " + canonicalFinalLayoutGuidance() + "\n"

  private fun canonicalFinalLayoutGuidance(): String =
    "hardeningAgentTemplate emits the canonical final order as $BLOCK_START, the shared " +
      "body, $BLOCK_END, then one digest marker. If a legacy digest marker exists, replace " +
      "or remove that line after review; do not append the emitted marker while leaving the " +
      "legacy copy."

  private data class PresentedLine(
    val content: String,
    val structural: String?,
    val quoted: Boolean,
  )

  private data class Fence(
    val marker: Char,
    val length: Int,
    val quoted: Boolean,
  )

  private data class Boundary(val index: Int, val quoted: Boolean)

  private data class Scanned(
    val starts: List<Boundary>,
    val ends: List<Boundary>,
    val markers: List<Marker>,
  )

  private fun scan(lines: List<String>): Scanned {
    val starts = mutableListOf<Boundary>()
    val ends = mutableListOf<Boundary>()
    val markers = mutableListOf<Marker>()
    var fence: Fence? = null
    lines.forEachIndexed { index, rawLine ->
      val presented = present(rawLine)
      val line = presented.structural ?: return@forEachIndexed
      val activeFence = fence
      if (activeFence != null) {
        if (isFenceClose(line, presented.quoted, activeFence)) fence = null
        return@forEachIndexed
      }
      openingFence(line, presented.quoted)?.let { opening ->
        fence = opening
        return@forEachIndexed
      }
      when (line) {
        BLOCK_START -> starts += Boundary(index, presented.quoted)
        BLOCK_END -> ends += Boundary(index, presented.quoted)
        else -> {
          val marker = MARKER.matchEntire(line)
          if (marker != null) {
            markers += Marker(marker.groupValues[1], index + 1, index)
          } else if (line.startsWith("<!-- hardening-template sha256:")) {
            throw Invalid(
              "malformed hardening-template digest marker at AGENTS.md line ${index + 1}; " +
                "expected '<!-- hardening-template sha256:<12 lowercase hex> -->'."
            )
          }
        }
      }
    }
    return Scanned(starts, ends, markers)
  }

  private fun requireSingleMarker(markers: List<Marker>): Marker? {
    if (markers.size > 1) {
      throw Invalid(
        "AGENTS.md contains ${markers.size} hardening-template digest markers at lines " +
          markers.joinToString(", ") { it.line.toString() } + "; keep exactly one."
      )
    }
    return markers.singleOrNull()
  }

  private fun normalizeQuoteLayer(
    block: List<String>,
    expectedQuoted: Boolean?,
  ): List<String> {
    var inferredQuoted = expectedQuoted
    var fence: Fence? = null
    block.forEach { rawLine ->
      if (rawLine.isBlank()) return@forEach
      val presented = present(rawLine)
      val bodyQuoted = inferredQuoted ?: presented.quoted.also { inferredQuoted = it }
      if (bodyQuoted && !presented.quoted) throw mixedQuoteFailure()
      val structural = presented.structural
      val activeFence = fence
      if (activeFence != null) {
        if (structural != null && isFenceClose(structural, presented.quoted, activeFence)) {
          fence = null
        }
        return@forEach
      }
      if (!bodyQuoted && presented.quoted) throw mixedQuoteFailure()
      if (structural != null) {
        openingFence(structural, presented.quoted)?.let { fence = it }
      }
    }
    return if (inferredQuoted == true) {
      block.map { rawLine -> if (rawLine.isBlank()) "" else present(rawLine).content }
    } else {
      block
    }
  }

  private fun mixedQuoteFailure() = Invalid(
    "the bounded block mixes quoted and unquoted nonblank lines outside fenced code. " +
      "Quote every line or none; refusing to normalize an ambiguous block."
  )

  /** Removes only Markdown presentation that can precede a structural token. */
  private fun present(rawLine: String): PresentedLine {
    val outerIndent = leadingSpaces(rawLine)
    if (outerIndent >= 4 || rawLine.startsWith('\t')) {
      return PresentedLine(rawLine, null, false)
    }
    var line = rawLine.drop(outerIndent)
    var quoted = false
    if (line.startsWith(">") &&
      (line.length == 1 || line[1] == ' ' || line[1] == '\t')) {
      quoted = true
      line = line.drop(1)
      if (line.startsWith(" ")) line = line.drop(1)
    }
    val content = line
    val contentIndent = leadingSpaces(content)
    val structural = if (contentIndent >= 4 || content.startsWith('\t')) {
      null
    } else {
      content.drop(contentIndent).trimEnd()
    }
    return PresentedLine(content, structural, quoted)
  }

  private fun leadingSpaces(line: String): Int =
    line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }

  private fun firstAtxHeading(lines: List<String>, firstSourceLine: Int): AtxHeading? {
    var fence: Fence? = null
    lines.forEachIndexed { index, line ->
      val activeFence = fence
      if (activeFence != null) {
        if (isFenceClose(line, false, activeFence)) fence = null
        return@forEachIndexed
      }
      openingFence(line, false)?.let { opening ->
        fence = opening
        return@forEachIndexed
      }
      if (ATX_HEADING.matches(line)) {
        return AtxHeading(firstSourceLine + index, line)
      }
    }
    return null
  }

  private fun openingFence(line: String, quoted: Boolean): Fence? {
    val match = FENCE_OPEN.matchEntire(line) ?: return null
    val token = match.groupValues[1]
    val remainder = match.groupValues[2]
    if (token.first() == '`' && remainder.contains('`')) return null
    return Fence(token.first(), token.length, quoted)
  }

  private fun isFenceClose(line: String, quoted: Boolean, fence: Fence): Boolean {
    if (quoted != fence.quoted) return false
    val marker = Regex.escape(fence.marker.toString())
    return Regex("^ {0,3}$marker{${fence.length},}[ \\t]*$").matches(line)
  }

  private val FENCE_OPEN = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
  private val ATX_HEADING = Regex("^ {0,3}#{1,6}(?:[ \\t]+.*)?$")
  private val MARKER = Regex("^<!-- hardening-template sha256:([0-9a-f]{12}) -->$")
}
