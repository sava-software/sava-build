package software.sava.build.hardening

/**
 * One pure interpretation of the paired provenance beside a committed mutation
 * record. Callers own the filesystem reads and policy (advisory, refusal, or the
 * preservation-first Rebase repair); this value prevents Verify and Debt from
 * disagreeing about which on-disk combinations are structurally valid.
 */
internal data class CommittedMutationProvenance(
  val hasRecord: Boolean,
  val pitVersionFilePresent: Boolean,
  val toolchainFilePresent: Boolean,
  val pitVersion: String?,
  val toolchain: MutationToolchainRecord?,
  val malformedPitVersion: String?,
  val malformedToolchain: String?,
) {

  val orphan: Boolean
    get() = !hasRecord && (pitVersionFilePresent || toolchainFilePresent)

  val torn: Boolean
    get() = hasRecord && (pitVersionFilePresent != toolchainFilePresent)

  val legacyUnbound: Boolean
    get() = hasRecord && !pitVersionFilePresent && !toolchainFilePresent

  val disagreement: Boolean
    get() = pitVersion != null && toolchain != null && toolchain.pitestVersion != pitVersion

  companion object {
    fun classify(
      hasRecord: Boolean,
      pitVersionText: String?,
      toolchainText: String?,
    ): CommittedMutationProvenance {
      var malformedPit: String? = null
      val parsedPitVersion = pitVersionText?.let { text ->
        val value = if (text.endsWith('\n')) text.dropLast(1) else text
        if (value.isEmpty() || value.any(Char::isWhitespace) || '\r' in text || '\n' in value) {
          malformedPit =
            "PIT-version stamp must be one nonblank normalized line with at most one trailing LF"
          null
        } else {
          value
        }
      }
      var malformedTool: String? = null
      val parsedToolchain = toolchainText?.let { text ->
        try {
          MutationToolchainRecord.parse(text)
        } catch (e: IllegalArgumentException) {
          malformedTool = e.message ?: e::class.java.simpleName
          null
        }
      }
      return CommittedMutationProvenance(
        hasRecord = hasRecord,
        pitVersionFilePresent = pitVersionText != null,
        toolchainFilePresent = toolchainText != null,
        pitVersion = parsedPitVersion,
        toolchain = parsedToolchain,
        malformedPitVersion = malformedPit,
        malformedToolchain = malformedTool,
      )
    }
  }
}
