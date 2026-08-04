package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.UUID

/**
 * Execution-time proof that a certification receipt describes work completed in
 * this Gradle invocation. Task-name inspection cannot see aliases, abbreviations,
 * or an aggregate which depends on `hardeningCertify`; this service is activated
 * by the certification preflight after Gradle has resolved the real task graph.
 */
abstract class HardeningCertificationSession : BuildService<BuildServiceParameters.None> {

  data class VerifiedEvidence(
    val sessionId: String,
    val invocationId: String,
    val reportSha256: String,
    val evidenceSha256: String,
  )

  private data class SuiteKey(val projectPath: String, val suite: String)

  private data class Attempt(val invocationId: String, val completed: VerifiedEvidence?)

  // The service is Gradle-wide, but certification is a project-scoped workflow. A
  // single active bit made :a:hardeningCertify silently disable history and enable
  // strict verification in an unrelated :b:pitestFoo selected in the same graph.
  private val activeSessions = mutableMapOf<String, String>()
  private val attempts = mutableMapOf<SuiteKey, Attempt>()
  private val verified = mutableMapOf<SuiteKey, VerifiedEvidence>()
  private val verifiedRecordInputs = mutableMapOf<SuiteKey, String>()
  private val revalidated = mutableMapOf<SuiteKey, VerifiedEvidence>()

  @Synchronized
  fun activate(projectPath: String): String {
    val current = activeSessions[projectPath]
    if (current != null) return current
    return UUID.randomUUID().toString().also { activeSessions[projectPath] = it }
  }

  @Synchronized
  fun isActive(projectPath: String): Boolean = projectPath in activeSessions

  @Synchronized
  fun sessionId(projectPath: String): String? = activeSessions[projectPath]

  @Synchronized
  fun startAttempt(projectPath: String, suite: String, invocationId: String) {
    val key = SuiteKey(projectPath, suite)
    attempts[key] = Attempt(invocationId, null)
    verified.remove(key)
    verifiedRecordInputs.remove(key)
    revalidated.remove(key)
  }

  @Synchronized
  fun recordCompleted(projectPath: String, suite: String, evidence: PitestEvidence) {
    val key = SuiteKey(projectPath, suite)
    val attempt = attempts[key] ?: throw IllegalStateException(
        "PIT suite '$projectPath:$suite' completed without an attempt in this Gradle invocation")
    check(attempt.invocationId == evidence.invocationId) {
      "PIT suite '$projectPath:$suite' completed evidence for a different invocation"
    }
    val run = VerifiedEvidence(
        activeSessions[projectPath].orEmpty(), evidence.invocationId, evidence.reportSha256,
        PitestEvidence.sha256(evidence.render()))
    attempts[key] = attempt.copy(completed = run)
  }

  /**
   * Refuses a writer only when this build actually attempted the suite and failed
   * before committing evidence. With no attempt, the supported direct-verify/N-1
   * migration path remains available.
   */
  @Synchronized
  fun requireNoIncompleteAttempt(projectPath: String, suite: String, evidence: PitestEvidence?) {
    val attempt = attempts[SuiteKey(projectPath, suite)] ?: return
    requireMatchingCompletedAttempt(projectPath, suite, attempt, evidence)
  }

  /**
   * Stronger writer proof used by the discoverable baseline tasks. Unlike the N-1
   * compatibility path above, a canonical task depends on PIT and promises a fresh
   * observation, so an absent attempt is itself a refusal rather than permission to
   * consume a matching report left by an earlier Gradle invocation.
   */
  @Synchronized
  fun requireCompletedAttempt(projectPath: String, suite: String, evidence: PitestEvidence?) {
    val attempt = attempts[SuiteKey(projectPath, suite)] ?: throw IllegalStateException(
        "PIT suite '$projectPath:$suite' did not start in this Gradle invocation; " +
            "a named baseline writer cannot reuse an older report")
    requireMatchingCompletedAttempt(projectPath, suite, attempt, evidence)
  }

  @Synchronized
  fun recordRevalidated(projectPath: String, suite: String, evidence: PitestEvidence) {
    revalidated[SuiteKey(projectPath, suite)] = VerifiedEvidence(
      "", evidence.invocationId, evidence.reportSha256, PitestEvidence.sha256(evidence.render())
    )
  }

  /** Current evidence is sound when this invocation produced it or a typed validator re-read it. */
  @Synchronized
  fun requireCurrentEvidence(projectPath: String, suite: String, evidence: PitestEvidence) {
    val expected = VerifiedEvidence(
      "", evidence.invocationId, evidence.reportSha256, PitestEvidence.sha256(evidence.render())
    )
    val completed = attempts[SuiteKey(projectPath, suite)]?.completed
    if (completed != null && completed.copy(sessionId = "") == expected) return
    check(revalidated[SuiteKey(projectPath, suite)] == expected) {
      "PIT suite '$projectPath:$suite' current evidence was not revalidated in this Gradle invocation"
    }
  }

  private fun requireMatchingCompletedAttempt(
    projectPath: String,
    suite: String,
    attempt: Attempt,
    evidence: PitestEvidence?,
  ) {
    val completed = attempt.completed ?: throw IllegalStateException(
        "PIT suite '$projectPath:$suite' started but did not complete in this Gradle invocation; " +
            "refusing to rewrite records from an older report")
    check(evidence != null && completed.invocationId == evidence.invocationId &&
        completed.reportSha256 == evidence.reportSha256 &&
        completed.evidenceSha256 == PitestEvidence.sha256(evidence.render())) {
      "PIT suite '$projectPath:$suite' completed different evidence in this Gradle invocation; " +
          "refusing to rewrite records from an older report"
    }
  }

  @Synchronized
  fun recordVerified(
    projectPath: String,
    suite: String,
    evidence: PitestEvidence,
    recordInputsSha256: String,
  ) {
    val session = activeSessions[projectPath] ?: return
    val key = SuiteKey(projectPath, suite)
    val expected = attempts[key]?.completed ?: throw IllegalStateException(
        "certification suite '$projectPath:$suite' was verified without completing PIT in this invocation")
    val actual = VerifiedEvidence(
        session, evidence.invocationId, evidence.reportSha256,
        PitestEvidence.sha256(evidence.render()))
    check(actual == expected) {
      "certification suite '$projectPath:$suite' verified different evidence from the PIT run " +
          "completed in this invocation"
    }
    verified[key] = actual
    verifiedRecordInputs[key] = recordInputsSha256
  }

  @Synchronized
  fun requireVerified(
    projectPath: String,
    suite: String,
    evidence: PitestEvidence,
    recordInputsSha256: String,
  ): VerifiedEvidence {
    val session = activeSessions[projectPath] ?: throw IllegalStateException(
        "certification preflight did not activate an execution session")
    val expected = VerifiedEvidence(
        session, evidence.invocationId, evidence.reportSha256,
        PitestEvidence.sha256(evidence.render()))
    val completed = attempts[SuiteKey(projectPath, suite)]?.completed ?: throw IllegalStateException(
        "'$suite' did not complete its final PIT observation in this certification invocation")
    check(completed == expected) {
      "'$suite' final PIT observation does not match the evidence being certified"
    }
    val actual = verified[SuiteKey(projectPath, suite)] ?: throw IllegalStateException(
        "'$suite' has no PIT execution plus successful verification recorded in this certification invocation")
    check(actual == expected) {
      "'$suite' evidence on disk does not match the PIT execution and verification recorded in this " +
          "certification invocation"
    }
    check(verifiedRecordInputs[SuiteKey(projectPath, suite)] == recordInputsSha256) {
      "'$suite' committed mutation records changed after successful verification in this " +
          "certification invocation"
    }
    return actual
  }
}
