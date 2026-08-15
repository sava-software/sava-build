package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Execution-time proof that a certification receipt describes work completed in
 * this Gradle invocation. Task-name inspection cannot see aliases, abbreviations,
 * or an aggregate which depends on `hardeningCertify`; this service is activated
 * by the certification preflight after Gradle has resolved the real task graph.
 */
abstract class HardeningCertificationSession :
  BuildService<BuildServiceParameters.None>, AutoCloseable {

  data class VerifiedEvidence(
    val sessionId: String,
    val invocationId: String,
    val reportSha256: String,
    val evidenceSha256: String,
  )

  internal data class FinalProjectIdentity(
    val git: CertificationGitIdentity,
    val pluginSha256: String,
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
  private val finalProjectIdentities = mutableMapOf<String, FinalProjectIdentity>()
  private val pluginIdentities = CertificationPluginIdentities()
  private val fileLocks = CertificationFileLocks()

  /** Retains the published service API for non-certifying third-party task wiring. */
  @Synchronized
  fun activate(projectPath: String): String = activateSession(projectPath)

  @Synchronized
  fun activate(projectPath: String, pluginSha256: String): String {
    pluginIdentities.register(projectPath, pluginSha256)
    return activateSession(projectPath)
  }

  @Synchronized
  fun activate(projectPath: String, pluginSha256: String, lockFile: File): String {
    fileLocks.acquire(projectPath, lockFile)
    pluginIdentities.register(projectPath, pluginSha256)
    return activateSession(projectPath)
  }

  fun ownsCertification(projectPath: String): Boolean = fileLocks.isHeld(projectPath)

  private fun activateSession(projectPath: String): String {
    val current = activeSessions[projectPath]
    if (current != null) return current
    finalProjectIdentities.remove(projectPath)
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

  @Synchronized
  internal fun recordFinalProjectIdentity(
    projectPath: String,
    before: CertificationGitIdentity,
    after: CertificationGitIdentity,
    pluginBeforeSha256: String,
    pluginAfterSha256: String,
  ) {
    check(activeSessions.containsKey(projectPath)) {
      "certification preflight did not activate an execution session"
    }
    pluginIdentities.requireExpected(projectPath, pluginBeforeSha256)
    CertificationGitIdentity.requireUnchanged(before, after)
    check(pluginBeforeSha256 == pluginAfterSha256) {
      "hardening plugin code changed during final certification validation"
    }
    finalProjectIdentities[projectPath] = FinalProjectIdentity(after, pluginAfterSha256)
  }

  @Synchronized
  internal fun requireFinalProjectIdentity(projectPath: String): FinalProjectIdentity =
    finalProjectIdentities[projectPath] ?: throw IllegalStateException(
      "final Git/plugin identity was not captured in this certification invocation")

  override fun close() = fileLocks.close()
}

/**
 * Cross-process ownership for a project's durable certification state. Gradle build
 * services coordinate only one invocation; the OS lock prevents two daemons from
 * replacing each other's receipt or completion sentinel. The zero-byte lock file is
 * retained so every opener contends on the same inode.
 */
private class CertificationFileLocks : AutoCloseable {
  private data class HeldLock(
    val path: String,
    val channel: FileChannel,
    val lock: FileLock,
  )

  private val held = linkedMapOf<String, HeldLock>()

  @Synchronized
  fun acquire(projectPath: String, lockFile: File) {
    val normalized = lockFile.toPath().toAbsolutePath().normalize()
    held[projectPath]?.let { current ->
      check(current.path == normalized.toString()) {
        "hardeningCertify for '$projectPath' changed its ownership-lock path"
      }
      return
    }
    val channel = FileChannel.open(
      normalized,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
    val lock = try {
      channel.tryLock()
    } catch (_: OverlappingFileLockException) {
      null
    } catch (failure: Throwable) {
      try {
        channel.close()
      } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
      }
      throw failure
    }
    if (lock == null) {
      channel.close()
      throw IllegalStateException(
        "another hardeningCertify invocation owns '$projectPath' via $normalized; " +
          "wait for it to finish before replacing its certification evidence"
      )
    }
    held[projectPath] = HeldLock(normalized.toString(), channel, lock)
  }

  @Synchronized
  fun isHeld(projectPath: String): Boolean = projectPath in held

  @Synchronized
  override fun close() {
    val failures = mutableListOf<Exception>()
    held.values.toList().asReversed().forEach { entry ->
      try {
        entry.lock.release()
      } catch (failure: Exception) {
        failures += failure
      }
      try {
        entry.channel.close()
      } catch (failure: Exception) {
        failures += failure
      }
    }
    held.clear()
    if (failures.isNotEmpty()) {
      val failure = IllegalStateException(
        "could not release hardeningCertify ownership lock")
      failures.forEach(failure::addSuppressed)
      throw failure
    }
  }
}
