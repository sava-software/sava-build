package software.sava.build.hardening.task

import software.sava.build.hardening.Mutant
import software.sava.build.hardening.MutantStatus

/**
 * Positive evidence only: a missing killing test says nothing about coverage.
 * PIT 1.30's normal CSV records the first killer, not every covering test. Its
 * slowest-test statistic instead considers every coverage-phase test.
 *
 * PIT-JUnit5 1.2.3 uses the full JUnit UniqueId as Description.name; PIT prefixes
 * Description.testClass when writing its qualified name to the CSV. Match the
 * whole ID, including method arguments and invocation segments, and only strip
 * that known class prefix. Other test-engine naming formats remain diagnostic.
 */
internal fun pitestSlowTestHasRecordedKill(testName: String, csvLines: List<String>): Boolean {
  val testClass = Regex("^\\[engine:[^]\\r\\n]+]/\\[class:([^]\\r\\n]+)]/")
    .find(testName)?.groupValues?.get(1) ?: return false
  val qualifiedName = "$testClass.$testName"
  val mutants = try {
    Mutant.parseReport(csvLines.filter(String::isNotBlank))
  } catch (_: IllegalArgumentException) {
    // The ratchet owns report validation. Incomplete evidence cannot add advice.
    return false
  }
  return mutants.any {
    it.status == MutantStatus.KILLED &&
      (it.killerText == qualifiedName || it.killerText == testName)
  }
}
