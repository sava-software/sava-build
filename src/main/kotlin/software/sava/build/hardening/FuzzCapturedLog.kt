package software.sava.build.hardening

/**
 * One raw Jazzer stream retained for a fuzz-target observation.
 *
 * The digest and byte count are produced while the stream is copied to disk, so
 * recording an observation never has to read a potentially very large log again.
 */
internal data class FuzzCapturedLog(
  val path: String,
  val bytes: Long,
  val sha256: String,
)
