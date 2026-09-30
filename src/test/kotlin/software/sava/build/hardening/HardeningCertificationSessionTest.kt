package software.sava.build.hardening

import org.gradle.api.services.BuildServiceParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningCertificationSessionTest {

  private fun session() = object : HardeningCertificationSession() {
    override fun getParameters(): BuildServiceParameters.None =
      throw UnsupportedOperationException("no parameters")
  }

  private fun evidence(invocationId: String = "run-1") = PitestEvidence(
      suite = "encoding",
      invocationId = invocationId,
      pitestVersion = "1.30.0",
      junitPluginVersion = "1.2.3",
      pluginSha256 = "plugin",
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = "25",
      sourceSha256 = "source",
      classesSha256 = "classes",
      classpathSha256 = "classpath",
      toolClasspathSha256 = "tool-classpath",
      mutationToolchainSha256 = "mutation-toolchain",
      configurationSha256 = "config",
      reportSha256 = "report",
      scope = PitestEvidence.FULL_SCOPE,
      historyAssisted = false,
  )

  @Test
  fun `an attempt is known to this invocation from the moment PIT starts it`() {
    val session = session()

    assertFalse(session.attemptedThisInvocation(":p", "encoding"))
    session.startAttempt(":p", "encoding", "run-1")
    assertTrue(session.attemptedThisInvocation(":p", "encoding"))
    assertFalse(session.attemptedThisInvocation(":p", "other"))
  }

  @Test
  fun `a kept report satisfies only the read-only check`() {
    val session = session()
    val kept = evidence()
    session.recordRetained(":p", "encoding", kept)

    assertTrue(session.isRetained(":p", "encoding", kept))
    session.requireCurrentOrRetainedEvidence(":p", "encoding", kept)
    assertThrows(IllegalStateException::class.java) {
      session.requireCurrentEvidence(":p", "encoding", kept)
    }
    assertFalse(session.isRetained(":p", "encoding", evidence(invocationId = "run-2")))
    assertThrows(IllegalStateException::class.java) {
      session.requireCurrentOrRetainedEvidence(":p", "encoding", evidence(invocationId = "run-2"))
    }
  }

  @Test
  fun `an exact revalidation still satisfies the read-only check and is not a kept report`() {
    val session = session()
    val current = evidence()
    session.recordRevalidated(":p", "encoding", current)

    session.requireCurrentOrRetainedEvidence(":p", "encoding", current)
    assertFalse(session.isRetained(":p", "encoding", current))
  }

  @Test
  fun `a PIT attempt in this invocation clears and forbids kept reports`() {
    val session = session()
    val kept = evidence()
    session.recordRetained(":p", "encoding", kept)

    session.startAttempt(":p", "encoding", "run-2")

    assertFalse(session.isRetained(":p", "encoding", kept))
    assertThrows(IllegalStateException::class.java) {
      session.requireCurrentOrRetainedEvidence(":p", "encoding", kept)
    }
    assertThrows(IllegalStateException::class.java) {
      session.recordRetained(":p", "encoding", kept)
    }
  }

  @Test
  fun `the mutation class tree has one owner at a time, until that owner's build ends`(@TempDir dir: File) {
    val checkoutLock = File(dir, "checkout/.pitest-history/mutation-recompile.lock")
    val treeLock = MutationClassTreeLock.file(File(dir, "home"), File(dir, "checkout/build/mutation-classes"))
    val first = session()
    val second = session()

    first.lockMutationClasses(":p", checkoutLock, treeLock)
    assertTrue(first.ownsMutationClasses(":p"))
    val refused = assertThrows(IllegalStateException::class.java) {
      second.lockMutationClasses(":p", checkoutLock, treeLock)
    }
    assertTrue(
      refused.message!!.contains("another invocation is using the mutation classes of ':p'"),
      refused.message,
    )
    assertFalse(second.ownsMutationClasses(":p"))
    first.lockMutationClasses(":p", checkoutLock, treeLock) // the owner may ask again
    assertTrue(checkoutLock.isFile && treeLock.isFile, "the lock files are created with their directories")
    assertEquals(
      treeLock,
      MutationClassTreeLock.file(File(dir, "home"), File(dir, "checkout/build/../build/mutation-classes")),
      "the tree lock is named after the canonical tree",
    )

    // Another Gradle user home names another tree lock; the checkout lock still refuses.
    val otherHome = session()
    assertThrows(IllegalStateException::class.java) {
      otherHome.lockMutationClasses(
        ":p", checkoutLock, MutationClassTreeLock.file(File(dir, "other-home"), File(dir, "checkout/build/mutation-classes")))
    }
    // Another checkout sharing the tree names another checkout lock; the tree lock refuses.
    val otherCheckout = session()
    val otherCheckoutLock = File(dir, "other/.pitest-history/mutation-recompile.lock")
    assertThrows(IllegalStateException::class.java) {
      otherCheckout.lockMutationClasses(":p", otherCheckoutLock, treeLock)
    }
    assertFalse(otherCheckout.ownsMutationClasses(":p"))
    // The checkout lock it had taken was given back with the refusal.
    val later = session()
    later.lockMutationClasses(":p", otherCheckoutLock, MutationClassTreeLock.file(File(dir, "home"), File(dir, "other/build/mutation-classes")))
    assertTrue(later.ownsMutationClasses(":p"))
    later.close()

    // Given back before the build ends, another invocation may take it at once.
    first.releaseMutationClasses(":p")
    assertFalse(first.ownsMutationClasses(":p"))
    second.lockMutationClasses(":p", checkoutLock, treeLock)
    assertTrue(second.ownsMutationClasses(":p"))
    second.releaseMutationClasses(":p")
    second.releaseMutationClasses(":p") // nothing held: not an error

    first.lockMutationClasses(":p", checkoutLock, treeLock)
    first.close()
    second.lockMutationClasses(":p", checkoutLock, treeLock)
    second.close()
    otherHome.close()
    otherCheckout.close()
  }

  @Test
  fun `a recompile is checked only once Gradle asked it about its inputs`() {
    val session = session()
    assertFalse(session.mutationRecompileChecked(":p"))
    session.noteMutationRecompileChecked(":p")
    assertTrue(session.mutationRecompileChecked(":p"))
    assertFalse(session.mutationRecompileChecked(":other"))
  }
}
