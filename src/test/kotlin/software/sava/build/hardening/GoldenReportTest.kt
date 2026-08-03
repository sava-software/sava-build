package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Mutant interpretation pinned against a REAL PIT report (the fleet deep leg's
 * json-iterator 'util' run — see golden-reports/PROVENANCE.txt), then the whole
 * report driven through the baseline engine. The recurring release-failure class
 * was "something was interpreted incorrectly wrt mutants", discovered one
 * consumer incident at a time; genuine PIT output exercised end-to-end is the
 * fixture shape that catches the next such misreading before a release does.
 */
class GoldenReportTest {

  private val report: List<Mutant> by lazy {
    val url = checkNotNull(javaClass.getResource("/golden-reports/json-iterator-util-mutations.csv")) {
      "golden report missing from test resources"
    }
    Mutant.parseReport(File(url.toURI()).readLines())
  }

  @Test
  fun `every real report row parses — none silently dropped`() {
    assertEquals(394, report.size, "the real report's row count is the corpus canary")
  }

  @Test
  fun `every real status is in the vocabulary and every line is numeric`() {
    val unknown = report.filter { it.status == null }.map { it.rawStatus }.distinct()
    assertTrue(unknown.isEmpty(), "unrecognized statuses in real PIT output: $unknown")
    val unparsable = report.filter { it.line == null }
    assertTrue(unparsable.isEmpty(), "non-numeric line fields in real PIT output: $unparsable")
  }

  @Test
  fun `key derivations are well-formed over the whole real population`() {
    report.forEach { mutant ->
      assertEquals(2, mutant.coordinate.count { it == ',' }, mutant.coordinate)
      assertEquals(3, mutant.baselineKey.count { it == ',' }, mutant.baselineKey)
      assertTrue(mutant.baselineKey.endsWith(",${mutant.rawStatus}"), mutant.baselineKey)
      // a killed row always names its killer in real output; the killer's commas
      // must never bleed into the parsed columns
      if (mutant.status == MutantStatus.KILLED) {
        assertTrue(mutant.killerText.isNotBlank(), "killed without a killer: $mutant")
      }
    }
  }

  @Test
  fun `the real report drives the engine to a self-consistent baseline`() {
    // update from the real run, then hold the written baseline against the same
    // run: everything must match — the verify-green-on-own-report law, on real
    // data instead of a synthetic fixture
    val currentLines: Map<String, List<String>> = report
      .filter { it.gated }
      .groupBy({ it.baselineKey }, { it.lineText })
    val rewrite = BaselineEngine.updateRewrite(emptyList(), currentLines)
    assertEquals(43, rewrite.copies, "the real run's gated population")
    val written = rewrite.written.map { BaselineNotes.parse(it) }

    val timedOutLines = report.filter { it.status == MutantStatus.TIMED_OUT }
      .groupBy({ it.coordinate }, { it.line })
    val killedLines = report.filter { it.status == MutantStatus.KILLED }
      .groupBy({ it.coordinate }, { it.line })
    val plan = BaselineEngine.keepPlan(written, currentLines, timedOutLines, killedLines)
    assertEquals(written.size, plan.size, "one disposition per row — an empty plan is not a green plan")
    assertTrue(
      plan.all { it == BaselineEngine.Disposition.MATCHED },
      "a baseline written from the real run must match the run that wrote it"
    )

    // and the multisets agree exactly — the ratchet reads this run as green
    val fresh = BaselineEngine.multisetDiff(
      report.filter { it.gated }.map { it.baselineKey }.sorted(),
      written.map { it.key }
    )
    assertTrue(fresh.isEmpty(), "fresh rows against a baseline the same run wrote: $fresh")
  }

  @Test
  fun `the real timeout population fits the audit key shape`() {
    val timedOut = report.filter { it.status == MutantStatus.TIMED_OUT }
    assertEquals(2, timedOut.size, "the deep-leg run's timeout population")
    timedOut.forEach { mutant ->
      assertTrue(TimeoutAudit.parse(listOf(mutant.coordinate)).malformed.isEmpty()) {
        "a real mutant's coordinate must paste into the audited set verbatim: ${mutant.coordinate}"
      }
    }
  }
}
