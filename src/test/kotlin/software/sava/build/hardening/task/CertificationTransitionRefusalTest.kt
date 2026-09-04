package software.sava.build.hardening.task

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CertificationTransitionRefusalTest {

  @Test
  fun `transition report names reviewed observation and exact writer`() {
    val report = renderCertificationTransitionRefusal(listOf(
      CertificationTransitionFinding(
        projectPath = ":ravina-solana",
        suiteName = "epoch",
        kind = CertificationPreflightFindingKind.TRANSITION,
        reasons = listOf("committed PIT version 1.25.9 differs from configured PIT 1.30.0"),
        pitestTaskPath = ":ravina-solana:pitestEpoch",
        baselineRebaseTaskPath = ":ravina-solana:pitestEpochBaselineRebase",
      ),
    ))

    assertTrue(report.contains("1 suite requiring a reviewed BaselineRebase"), report)
    assertTrue(
      report.contains("observe: :ravina-solana:pitestEpoch -PnoMutationHistory") &&
        report.contains("writer: :ravina-solana:pitestEpochBaselineRebase") &&
        report.contains("expected adoption stopping point"),
      report,
    )
  }

  @Test
  fun `path blocker does not authorize an unrelated rebase`() {
    val report = renderCertificationTransitionRefusal(listOf(
      CertificationTransitionFinding(
        projectPath = ":ravina-solana",
        suiteName = "epoch",
        kind = CertificationPreflightFindingKind.BLOCKER,
        reasons = listOf("committed mutation provenance path is invalid"),
        pitestTaskPath = null,
        baselineRebaseTaskPath = null,
      ),
    ))

    assertTrue(report.contains("1 configuration/path blocker"), report)
    assertTrue(report.contains("does not authorize a BaselineRebase writer"), report)
    assertFalse(report.contains("writer: "), report)
    assertFalse(report.contains("expected adoption stopping point"), report)
  }
}
