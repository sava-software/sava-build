package software.sava.build.hardening

import java.io.File

/** A completed child process and the bytes retained while its pipes were drained. */
internal data class FuzzTargetObservation(
  val elapsedMillis: Long,
  val source: FuzzSourceIdentity,
  val attemptDirectory: String,
  val standardLog: FuzzCapturedLog,
  val errorLog: FuzzCapturedLog,
) {
  init {
    require(elapsedMillis >= 0) { "fuzz elapsed time must be nonnegative" }
    HardeningNames.requireSingleLineValue("fuzz attempt directory", attemptDirectory)
    require('\t' !in attemptDirectory) { "fuzz attempt directory cannot contain a TSV delimiter" }
    val directory = File(attemptDirectory)
    require(directory.isAbsolute && directory.normalize().path == attemptDirectory) {
      "fuzz attempt directory must be a normalized absolute path"
    }
    require(standardLog.path == directory.resolve("jazzer.stdout.log").path &&
      errorLog.path == directory.resolve("jazzer.stderr.log").path) {
      "fuzz log paths must identify the stdout and stderr files in this attempt"
    }
  }

  fun receiptRows(target: String): String {
    HardeningNames.requireSingleLineValue("fuzz target", target)
    require('\t' !in target) { "fuzz target cannot contain a TSV delimiter" }
    val git = source.git
    return listOf(
      listOf(
        "targetObservation", target, elapsedMillis.toString(), attemptDirectory,
        standardLog.bytes.toString(), standardLog.sha256,
        errorLog.bytes.toString(), errorLog.sha256,
      ).joinToString("\t"),
      listOf(
        "targetSource", target, source.sourceSha256, git.state.receiptValue,
        git.commit, git.tree, git.statusSha256, git.projectDirectory,
      ).joinToString("\t"),
    ).joinToString("\n", postfix = "\n")
  }
}
