package software.sava.build.hardening

import org.gradle.api.services.BuildServiceParameters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the fuzz session writes, and leaves alone, when the build ends. */
class HardeningFuzzSessionCloseTest {

  @TempDir
  lateinit var project: File

  private val history get() = File(project, ".pitest-history").apply { mkdirs() }
  private val lock get() = File(history, "local-fuzz.lock")
  private val receipt get() = File(history, "local-fuzz.tsv")
  private val running get() = File(history, "local-fuzz.running")

  private fun session() = object : HardeningFuzzSession() {
    // close() never reads parameters, and None has no public instance to return
    override fun getParameters(): BuildServiceParameters.None =
      throw UnsupportedOperationException("unused by the fuzz session")
  }

  private fun activate(session: HardeningFuzzSession, targets: List<String> = listOf("codec", "plain")) =
    session.activate(":", targets, lock, project, running)

  @Test
  fun `a session record left by a failed target becomes refused and the receipt stays`() {
    receipt.writeText("last success\n")
    val session = session()
    val sessionId = activate(session)
    running.writeText("session\t$sessionId\n")
    session.recordFailure(":", "codec", "crashed\twith\ndetail")

    session.close()

    assertEquals("refused\tfuzzCodec failed: crashed with detail\n", running.readText())
    assertEquals("last success\n", receipt.readText())
  }

  @Test
  fun `a starting record left by an interrupted campaign names the missing targets`() {
    val session = session()
    val sessionId = activate(session)
    running.writeText("starting\t$sessionId\n")

    session.close()

    assertEquals(
      "refused\tfuzzAll did not complete every configured target; missing: fuzzCodec, fuzzPlain\n",
      running.readText(),
    )
  }

  @Test
  fun `a published, refused, foreign or oversized record is left alone`() {
    val published = session()
    activate(published)
    published.close()
    assertFalse(running.exists(), "a published campaign has no record to replace")

    listOf(
      "refused\tfuzzAll: campaign ownership sentinel changed before receipt publication\n",
      "session\tsome-other-campaign\n",
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
  fun `the ownership lock is released even when the record cannot be written`() {
    receipt.writeText("last success\n")
    val session = session()
    val sessionId = activate(session)
    running.writeText("session\t$sessionId\n")
    session.recordFailure(":", "codec", "crashed")
    // A read-only history directory makes the atomic write fail at its staging file.
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
