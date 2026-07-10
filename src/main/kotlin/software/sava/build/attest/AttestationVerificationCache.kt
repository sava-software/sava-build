package software.sava.build.attest

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

enum class AttestationOutcome { VERIFIED, MISSING, FAILED }

/**
 * Memoizes attestation verification outcomes by artifact digest for the duration of one
 * build invocation, so overlapping classpaths of different projects verify each artifact
 * only once.
 */
abstract class AttestationVerificationCache : BuildService<BuildServiceParameters.None> {

  private val outcomes = ConcurrentHashMap<String, AttestationOutcome>()

  /** Returns the outcome for [digest], computing it with [verify] on first use, and
   *  whether it came from the cache. */
  fun memoize(digest: String, verify: () -> AttestationOutcome): Pair<AttestationOutcome, Boolean> {
    var fromCache = true
    val outcome = outcomes.computeIfAbsent(digest) {
      fromCache = false
      verify()
    }
    return outcome to fromCache
  }
}
