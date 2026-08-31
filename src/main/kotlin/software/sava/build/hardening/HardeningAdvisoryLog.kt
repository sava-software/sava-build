package software.sava.build.hardening

import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Collects the verify tasks' advisory findings and completed project-scoped certification
 * receipts, then reprints both as build-wide summaries when the build ends.
 *
 * The advisories this collects never fail a build by design — load can time out any
 * mutant, a cause can legitimately land after its row — but "advisory" was becoming
 * "invisible": a full gate runs a dozen suites, so a warning from the third one sits
 * several hundred lines above the last line of output, and full certification is the
 * only place these checks run at all (CI runs 'check', which has no mutation suites in
 * it). A reviewer-stop nobody scrolls back to is not a stop.
 *
 * Implemented as a build service closed at the end of the build rather than as a
 * dataflow action, because a service is registered once per build no matter how many
 * projects apply the plugin — the same registration from a project plugin would queue
 * one summary per project.
 */
abstract class HardeningAdvisoryLog : BuildService<BuildServiceParameters.None>, AutoCloseable {

  private data class Advisory(val scope: String, val summary: String)
  private data class Certification(
    val projectPath: String,
    val suiteCount: Int,
    val receiptPath: String,
  )

  private val advisories = ConcurrentLinkedQueue<Advisory>()
  private val certifications = ConcurrentHashMap<String, Certification>()

  /**
   * Records one finding. [scope] identifies the repository, project, suite, or other
   * hardening scope it came from; [summary] is a short noun phrase, not the full
   * advice — the full text has already been logged where the finding was made.
   */
  fun record(scope: String, summary: String) {
    advisories.add(Advisory(scope, summary))
  }

  /** Records a receipt only after its project has published it and cleared its sentinel. */
  fun recordCertification(projectPath: String, suiteCount: Int, receiptPath: String) {
    certifications[projectPath] = Certification(projectPath, suiteCount, receiptPath)
  }

  override fun close() {
    val logger = Logging.getLogger(HardeningAdvisoryLog::class.java)
    val found = advisories.toList()
    if (found.isNotEmpty()) {
      val byScope = found.groupBy { it.scope }.toSortedMap()
      logger.warn(
        buildString {
          append(
            "hardening: ${counted(found.size, "advisory finding")} across " +
              "${counted(byScope.size, "scope")} — none failed the build; each is printed in " +
              "full above, next to the scope that found it:"
          )
          byScope.forEach { (scope, entries) ->
            append("\n  $scope: ${entries.joinToString(", ") { it.summary }}")
          }
        }
      )
    }

    val published = certifications.values.sortedBy { it.projectPath }
    if (published.isNotEmpty()) {
      val totalSuites = published.sumOf { it.suiteCount }
      logger.lifecycle(
        buildString {
          append(
            "hardeningCertify: ${counted(published.size, "project-scoped receipt")} published " +
              "by this Gradle invocation; ${counted(totalSuites, "suite")} certified total:"
          )
          published.forEach { certification ->
            append(
              "\n  ${certification.projectPath} — " +
                "${counted(certification.suiteCount, "suite")} — ${certification.receiptPath}"
            )
          }
        }
      )
    }
  }

  private fun counted(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
}
