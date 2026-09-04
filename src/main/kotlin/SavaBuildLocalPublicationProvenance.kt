import software.sava.build.hardening.PitestEvidence
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Source snapshot at publication recorded beside the mutable `0.0.0-test` plugin JAR.
 *
 * The local publication deliberately keeps this data outside the JAR: rebuilding the
 * same plugin sources at a newer commit must retain reproducible plugin bytes while
 * still recording the checkout observed when publication completed. The JAR digest
 * joins the two files and makes a stale or partially replaced pair unusable.
 */
internal data class SavaBuildLocalPublicationProvenance(
  val gitState: GitState,
  val gitCommit: String,
  val gitTree: String,
  val gitStatusSha256: String,
  val sourceStateSha256: String,
  val publishedAtUtc: Instant,
  val jarSha256: String,
) {

  enum class GitState(val value: String) {
    CLEAN("clean"),
    DIRTY("dirty"),
  }

  init {
    require(gitCommit.matches(gitObjectPattern)) {
      "local-publication gitCommit is not a full lowercase Git object id"
    }
    require(gitTree.matches(gitObjectPattern)) {
      "local-publication gitTree is not a full lowercase Git object id"
    }
    require(gitCommit.length == gitTree.length) {
      "local-publication commit and tree use different Git object formats"
    }
    require(gitStatusSha256.matches(sha256Pattern)) {
      "local-publication gitStatusSha256 is not a lowercase SHA-256"
    }
    require(jarSha256.matches(sha256Pattern)) {
      "local-publication jarSha256 is not a lowercase SHA-256"
    }
    require(sourceStateSha256.matches(sha256Pattern)) {
      "local-publication sourceStateSha256 is not a lowercase SHA-256"
    }
    when (gitState) {
      GitState.CLEAN -> require(gitStatusSha256 == EMPTY_STATUS_SHA256) {
        "clean local-publication provenance must bind the empty Git porcelain status"
      }
      GitState.DIRTY -> require(gitStatusSha256 != EMPTY_STATUS_SHA256) {
        "dirty local-publication provenance must bind a non-empty Git porcelain status"
      }
    }
  }

  fun render(): String = buildString {
    appendLine("schema\t$SCHEMA")
    appendLine("gitState\t${gitState.value}")
    appendLine("gitCommit\t$gitCommit")
    appendLine("gitTree\t$gitTree")
    appendLine("gitStatusSha256\t$gitStatusSha256")
    appendLine("sourceStateSha256\t$sourceStateSha256")
    appendLine("publishedAtUtc\t$publishedAtUtc")
    appendLine("jarSha256\t$jarSha256")
  }

  fun describeSourceSnapshotAtPublication(): String =
    "source snapshot at publication: commit $gitCommit; tree $gitTree; " +
      "${gitState.value} worktree; source-state SHA-256 $sourceStateSha256"

  companion object {
    const val SCHEMA: Int = 2
    const val SIDECAR_SUFFIX: String = "-provenance.tsv"
    val EMPTY_STATUS_SHA256: String = PitestEvidence.sha256(byteArrayOf())

    private val gitObjectPattern = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun sidecarFor(jar: File): File {
      require(jar.name.endsWith(".jar")) { "local plugin artifact is not a JAR: $jar" }
      return jar.resolveSibling(jar.name.removeSuffix(".jar") + SIDECAR_SUFFIX)
    }

    fun read(file: File): SavaBuildLocalPublicationProvenance {
      val text = Files.readString(file.toPath(), StandardCharsets.UTF_8)
      return parse(text)
    }

    fun parse(text: String): SavaBuildLocalPublicationProvenance {
      require('\r' !in text) { "local-publication provenance must use LF line endings" }
      require(text.endsWith('\n')) {
        "local-publication provenance must end with a newline"
      }
      val lines = text.split('\n').dropLast(1)
      require(lines.size == 8) {
        "local-publication provenance must contain exactly 8 rows, found ${lines.size}"
      }

      fun value(row: Int, key: String): String {
        val fields = lines[row].split('\t')
        require(fields.size == 2 && fields[0] == key && fields[1].isNotEmpty()) {
          "local-publication provenance row ${row + 1} must be '$key\\t<value>'"
        }
        return fields[1]
      }

      val schema = value(0, "schema")
      require(schema == SCHEMA.toString()) {
        "unsupported local-publication provenance schema '$schema'"
      }
      val stateValue = value(1, "gitState")
      val state = GitState.entries.singleOrNull { it.value == stateValue }
        ?: throw IllegalArgumentException(
          "local-publication gitState must be clean or dirty, found '$stateValue'"
        )
      val publishedValue = value(6, "publishedAtUtc")
      val publishedAt = try {
        Instant.parse(publishedValue)
      } catch (failure: DateTimeParseException) {
        throw IllegalArgumentException(
          "local-publication publishedAtUtc is not a UTC instant: '$publishedValue'",
          failure,
        )
      }
      require(publishedAt.toString() == publishedValue) {
        "local-publication publishedAtUtc is not in canonical UTC form: '$publishedValue'"
      }

      return SavaBuildLocalPublicationProvenance(
        gitState = state,
        gitCommit = value(2, "gitCommit"),
        gitTree = value(3, "gitTree"),
        gitStatusSha256 = value(4, "gitStatusSha256"),
        sourceStateSha256 = value(5, "sourceStateSha256"),
        publishedAtUtc = publishedAt,
        jarSha256 = value(7, "jarSha256"),
      ).also { parsed ->
        require(parsed.render() == text) {
          "local-publication provenance is not in canonical schema-$SCHEMA form"
        }
      }
    }
  }
}
