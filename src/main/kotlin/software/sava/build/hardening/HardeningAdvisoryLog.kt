package software.sava.build.hardening

import org.gradle.api.logging.Logging
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Collects the verify tasks' advisory findings and reprints them as one summary when the
 * build ends.
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

  private val advisories = ConcurrentLinkedQueue<Advisory>()

  /**
   * Records one finding. [scope] identifies the suite it came from (project path plus
   * suite name); [summary] is a short noun phrase, not the full advice — the full text
   * has already been logged where the finding was made.
   */
  fun record(scope: String, summary: String) {
    advisories.add(Advisory(scope, summary))
  }

  override fun close() {
    val found = advisories.toList()
    if (found.isEmpty()) {
      return
    }
    val byScope = found.groupBy { it.scope }.toSortedMap()
    Logging.getLogger(HardeningAdvisoryLog::class.java).warn(
      buildString {
        append(
          "hardening: ${found.size} advisory finding(s) across ${byScope.size} suite(s) — none failed the " +
            "build; each is printed in full above, next to the suite that found it:"
        )
        byScope.forEach { (scope, entries) ->
          append("\n  $scope: ${entries.joinToString(", ") { it.summary }}")
        }
      }
    )
  }
}
