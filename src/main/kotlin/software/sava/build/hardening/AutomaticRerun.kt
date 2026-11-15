package software.sava.build.hardening

import java.io.File

/**
 * What a PIT task leaves beside a report that is its second attempt: the first ended with
 * a minion PIT never heard from and the task replaced it (see `EarlyMinionDeaths`). The
 * marker is machine-local and outside the evidence. It exists so a refusal of this report
 * does not tell anyone to "repeat once" a run the plugin has already repeated.
 *
 * It carries the second attempt's invocation and is honoured only while that invocation
 * is the one the report directory names, so a marker can never describe a report other
 * than the one it was written for. Every attempt clears it with the report's other
 * leaves; the repeat writes its own just before its PIT process starts.
 */
internal data class AutomaticRerun(
  val invocationId: String,
  /** `coverage` or `mutation`: the phase in which the first attempt's minion died. */
  val phase: String,
  /** Where the first attempt's logs, and its report if it wrote one, are kept. */
  val firstAttemptDirectory: String,
) {

  /** Sentences for a refusal of the report this marker sits beside. */
  val note: String
    get() = "This is already the plugin's one automatic repeat: the first attempt ended with " +
      "an early minion death in the $phase phase and was discarded. Its logs are kept, as " +
      "diagnostics only, in $firstAttemptDirectory. Read both attempts' logs before any " +
      "retry: two invalid attempts in a row are a recurrence to investigate, not a reason " +
      "to keep repeating."

  fun render(): String =
    "invocation\t$invocationId\nphase\t$phase\nfirstAttempt\t$firstAttemptDirectory\n"

  companion object {
    const val FILE_NAME = ".rerun-after-early-minion-death"

    fun write(reportDirectory: File, rerun: AutomaticRerun) {
      BaselineFiles.writeAtomically(reportDirectory.resolve(FILE_NAME), rerun.render())
    }

    /**
     * The marker beside the report in [reportDirectory] when it describes that report, else
     * null: no marker, an unreadable one, one written for another invocation, or a report
     * that is no longer the one its evidence names.
     */
    fun forCurrentReport(reportDirectory: File): AutomaticRerun? = try {
      read(reportDirectory)?.takeIf { it.invocationId == currentInvocation(reportDirectory) }
    } catch (_: Exception) {
      null
    }

    private fun read(reportDirectory: File): AutomaticRerun? {
      val marker = reportDirectory.resolve(FILE_NAME)
      if (!marker.isFile) return null
      val fields = marker.readLines()
        .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }
        .associate { (name, value) -> name to value }
      return AutomaticRerun(
        invocationId = fields["invocation"]?.takeIf { it.isNotBlank() } ?: return null,
        phase = fields["phase"]?.takeIf { it == "coverage" || it == "mutation" } ?: return null,
        firstAttemptDirectory = fields["firstAttempt"]?.takeIf { it.isNotBlank() } ?: return null,
      )
    }

    /**
     * The invocation of the report in [reportDirectory]: the completed evidence's, when the
     * report beside it is still the one that evidence was written for, or the one an
     * attempt that did not complete left behind.
     */
    private fun currentInvocation(reportDirectory: File): String? {
      val evidenceFile = reportDirectory.resolve(".evidence.tsv")
      if (evidenceFile.isFile) {
        val evidence = PitestEvidence.parse(evidenceFile.readText())
        val report = reportDirectory.resolve("mutations.csv")
        return evidence.invocationId.takeIf {
          report.isFile && PitestEvidence.sha256(report) == evidence.reportSha256
        }
      }
      val started = reportDirectory.resolve(".evidence-invocation")
      return started.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }
  }
}
