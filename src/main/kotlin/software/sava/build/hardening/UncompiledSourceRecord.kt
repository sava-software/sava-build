package software.sava.build.hardening

import java.io.File

/**
 * Fingerprint of the evidence sources the PIT recompile does not compile, recorded beside
 * a completed report: build scripts, resources, a prune-selection file, `module-info.java`
 * and anything `recompileExcludes` keeps out.
 *
 * [PitestEvidence.sourceSha256] covers these together with the Java sources, so by itself
 * it cannot say which kind of file moved. A class fingerprint can vouch for the compiled
 * sources and for nothing else, so a report may outlive a source edit only when this part
 * is byte-identical. It is a sidecar rather than a manifest field because it is not
 * identity: every exact comparison already covers these files through `sourceSha256`.
 *
 * The file set is derived, never listed, and both the PIT task and the validator derive
 * it through [fingerprint]: a file that appears, changes or disappears moves the value,
 * which is how a prune selection supplied to only one of the two invocations is refused.
 */
internal data class UncompiledSourceRecord(
  val invocationId: String,
  val uncompiledSourceSha256: String,
) {

  fun render(): String = buildString {
    appendLine("schema\t$SCHEMA")
    appendLine("invocation\t$invocationId")
    appendLine("uncompiledSourceSha256\t$uncompiledSourceSha256")
  }

  companion object {
    /** Leaf name beside a report's `.evidence.tsv`. */
    const val FILE_NAME = ".uncompiled-sources.tsv"
    const val SCHEMA = "1"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val fields = listOf("schema", "invocation", "uncompiledSourceSha256")

    /** Strict like the manifest it sits beside: exact fields, once each, in order. */
    fun parse(text: String): UncompiledSourceRecord {
      val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
      require(lines.size == fields.size) { "expected ${fields.size} fields, found ${lines.size}" }
      val values = lines.mapIndexed { index, line ->
        val split = line.indexOf('\t')
        require(split > 0 && line.substring(0, split) == fields[index]) {
          "line ${index + 1} is not '${fields[index]}<TAB>value'"
        }
        line.substring(split + 1)
      }
      require(values[0] == SCHEMA) { "unsupported schema '${values[0]}' (expected $SCHEMA)" }
      require(values[1].isNotBlank()) { "blank invocation" }
      require(SHA256.matches(values[2])) { "uncompiledSourceSha256 is not a SHA-256" }
      return UncompiledSourceRecord(values[1], values[2])
    }

    /**
     * [evidenceSources] minus [recompiledSources], fingerprinted as the evidence itself is.
     * Both sides are realized here, once, so the difference is taken over one view.
     */
    fun fingerprint(
      projectDirectory: File,
      evidenceSources: Iterable<File>,
      recompiledSources: Iterable<File>,
    ): String {
      val compiled = recompiledSources.mapTo(hashSetOf()) { it.absoluteFile.normalize() }
      val uncompiled = evidenceSources.asSequence().flatMap { entry ->
        when {
          entry.isFile -> sequenceOf(entry)
          entry.isDirectory -> entry.walkTopDown().filter(File::isFile)
          else -> emptySequence()
        }
      }.filter { it.absoluteFile.normalize() !in compiled }.toList()
      return PitestEvidence.fingerprint(projectDirectory, uncompiled)
    }
  }
}
