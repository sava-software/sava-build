package software.sava.build.hardening

import org.gradle.api.services.BuildServiceParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the certification session writes, and leaves alone, when the build ends. */
class HardeningCertificationSessionCloseTest {

  @TempDir
  lateinit var project: File

  private val history get() = File(project, ".pitest-history").apply { mkdirs() }
  private val lock get() = File(history, "pitest-certification.lock")
  private val receipt get() = File(history, "pitest-certification.tsv")
  private val running get() = File(history, "pitest-certification.running")

  private fun session() = object : HardeningCertificationSession() {
    override fun getParameters(): BuildServiceParameters.None =
      throw UnsupportedOperationException("unused by the certification session")
  }

  private fun activate(session: HardeningCertificationSession, suites: List<String> = listOf("wire", "codec")) =
    session.activate(":", "plugin", lock, project, running, suites)

  private fun evidence(suite: String, invocationId: String) = PitestEvidence(
    suite = suite,
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
    reportSha256 = "report-$suite",
    scope = PitestEvidence.FULL_SCOPE,
    historyAssisted = false,
  )

  @Test
  fun `an unfinished attempt becomes refused naming every unverified suite and the receipt stays`() {
    receipt.writeText("last success\n")
    val session = session()
    val sessionId = activate(session)
    running.writeText("session\t$sessionId\n")
    // codec ran and verified; wire started PIT and never completed it
    val codec = evidence("codec", "run-codec")
    session.startAttempt(":", "codec", "run-codec")
    session.recordCompleted(":", "codec", codec)
    session.recordVerified(":", "codec", codec, "records")
    session.startAttempt(":", "wire", "run-wire")

    session.close()

    assertEquals(
      "refused\thardeningCertify ended without publishing its receipt; " +
        "no verified fresh observation in this invocation for: wire\n",
      running.readText(),
    )
    assertEquals("last success\n", receipt.readText())
  }

  @Test
  fun `a published, refused, foreign or oversized record is left alone`() {
    val published = session()
    activate(published)
    published.close()
    assertFalse(running.exists(), "a published attempt has no record to replace")

    listOf(
      "refused\thardeningCertify: clean Git certification cannot bind 1 source input(s)\n",
      "session\tsome-other-attempt\n",
    ).forEach { record ->
      val session = session()
      activate(session)
      running.writeText(record)
      session.close()
      assertEquals(record, running.readText())
    }

    val oversized = session()
    val sessionId = activate(oversized)
    val record = "session\t$sessionId\n" + "x".repeat(1_000)
    running.writeText(record)
    oversized.close()
    assertEquals(record, running.readText())
  }

  @Test
  fun `an activation that names no state record writes nothing`() {
    val session = session()
    // the published three-argument activation keeps its old contract: no record to finalize
    session.activate(":", "plugin", lock)
    running.writeText("session\t${session.sessionId(":")}\n")
    session.close()
    assertEquals("session\t${session.sessionId(":")}\n", running.readText())
  }

  @Test
  fun `the ownership lock is released even when the record cannot be written`() {
    receipt.writeText("last success\n")
    val session = session()
    val sessionId = activate(session)
    running.writeText("session\t$sessionId\n")
    history.setWritable(false)
    try {
      assertThrows(Exception::class.java) { session.close() }
    } finally {
      history.setWritable(true)
    }

    assertEquals("session\t$sessionId\n", running.readText(), "the intact marker is kept")
    assertEquals("last success\n", receipt.readText(), "the receipt is not deleted")
    val next = session()
    activate(next)
    next.close()
  }
}
