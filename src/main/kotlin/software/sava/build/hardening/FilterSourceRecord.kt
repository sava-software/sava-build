package software.sava.build.hardening

import java.io.File

/**
 * Fingerprint of every file under the source roots PIT is given (`--sourceDirs`),
 * recorded beside a completed report. ArcMutate's `@Generated` filter reads those files,
 * so under ArcMutate identical classes show an identical population only while this part
 * of the source text is byte-identical too. The Java sources outside the roots, the tests
 * the recompile also compiles, are vouched for by their classes alone.
 *
 * A sidecar rather than a manifest field for the same reason as [UncompiledSourceRecord]:
 * `sourceSha256` already covers these files in every exact comparison, so this record only
 * splits that fingerprint for the kept-report rule. It is written whether or not ArcMutate
 * is active, so a report's record set does not depend on the toolchain, and read only when
 * it is.
 */
internal data class FilterSourceRecord(
  val invocationId: String,
  val filterSourceSha256: String,
) {

  fun render(): String = buildString {
    appendLine("schema\t$SCHEMA")
    appendLine("invocation\t$invocationId")
    appendLine("filterSourceSha256\t$filterSourceSha256")
  }

  companion object {
    /** Leaf name beside a report's `.evidence.tsv`. */
    const val FILE_NAME = ".filter-sources.tsv"
    const val SCHEMA = "1"
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val fields = listOf("schema", "invocation", "filterSourceSha256")

    /** Strict like the manifest it sits beside: exact fields, once each, in order. */
    fun parse(text: String): FilterSourceRecord {
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
      require(SHA256.matches(values[2])) { "filterSourceSha256 is not a SHA-256" }
      return FilterSourceRecord(values[1], values[2])
    }

    /**
     * Every regular file under [sourceDirectories], fingerprinted as the evidence itself
     * is. A root that does not exist contributes nothing, so a project without the tree
     * records the empty fingerprint rather than failing to record.
     */
    fun fingerprint(projectDirectory: File, sourceDirectories: Iterable<File>): String =
      PitestEvidence.fingerprint(projectDirectory, sourceDirectories)
  }
}
