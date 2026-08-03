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

  @Volatile
  private var activeSessionId: String? = null
  private val attempts = mutableMapOf<SuiteKey, Attempt>()
  private val verified = mutableMapOf<SuiteKey, VerifiedEvidence>()

  @Synchronized
  fun activate(): String {
    val current = activeSessionId
    if (current != null) return current
    return UUID.randomUUID().toString().also { activeSessionId = it }
  }

  fun isActive(): Boolean = activeSessionId != null

  fun sessionId(): String? = activeSessionId

  @Synchronized
  fun startAttempt(projectPath: String, suite: String, invocationId: String) {
    val key = SuiteKey(projectPath, suite)
    attempts[key] = Attempt(invocationId, null)
    verified.remove(key)
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
        activeSessionId.orEmpty(), evidence.invocationId, evidence.reportSha256,
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
  fun recordVerified(projectPath: String, suite: String, evidence: PitestEvidence) {
    val session = activeSessionId ?: return
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
  }

  @Synchronized
  fun requireVerified(
    projectPath: String,
    suite: String,
    evidence: PitestEvidence,
  ): VerifiedEvidence {
    val session = activeSessionId ?: throw IllegalStateException(
        "certification preflight did not activate an execution session")
    val expected = VerifiedEvidence(
        session, evidence.invocationId, evidence.reportSha256,
        PitestEvidence.sha256(evidence.render()))
    val actual = verified[SuiteKey(projectPath, suite)] ?: throw IllegalStateException(
        "'$suite' has no PIT execution plus successful verification recorded in this certification invocation")
    check(actual == expected) {
      "'$suite' evidence on disk does not match the PIT execution and verification recorded in this " +
          "certification invocation"
    }
    return actual
  }
}
