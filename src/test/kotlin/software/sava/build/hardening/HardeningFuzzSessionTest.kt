package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningFuzzSessionTest {

  @TempDir
  lateinit var temporaryDirectory: File

  private val session = FuzzCampaignRegistry()

  @Test
  fun `standalone target completion is ignored`() {
    assertFalse(session.recordCompleted(":", "codec", 17))
    assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":", listOf("codec"))
    }
  }

  @Test
  fun `campaign requires its exact inventory to complete`() {
    val sessionId = session.activate(":consumer", listOf("plain", "codec"))

    assertTrue(session.recordCompleted(":consumer", "codec", 101))
    val incomplete = assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":consumer", listOf("codec", "plain"))
    }
    assertTrue(incomplete.message.orEmpty().contains("missing: fuzzPlain"))

    assertTrue(session.recordCompleted(":consumer", "plain", 202))
    val completed = session.requireCompleted(":consumer", listOf("plain", "codec"))
    assertEquals(sessionId, completed.sessionId)
    assertEquals(setOf("codec", "plain"), completed.targets)
    assertEquals(mapOf("codec" to 101L, "plain" to 202L), completed.executionsByTarget)
    assertEquals(303L, completed.totalExecutions)
  }

  @Test
  fun `campaign rejects unknown completions and inventory drift`() {
    session.activate(":", listOf("codec"))

    assertThrows(IllegalStateException::class.java) {
      session.recordCompleted(":", "plain", 1)
    }
    assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":", listOf("codec", "plain"))
    }
  }

  @Test
  fun `campaign rejects non-positive and duplicate execution counts`() {
    session.activate(":", listOf("codec"))

    assertThrows(IllegalStateException::class.java) {
      session.recordCompleted(":", "codec", 0)
    }
    assertTrue(session.recordCompleted(":", "codec", 123))
    assertThrows(IllegalStateException::class.java) {
      session.recordCompleted(":", "codec", 456)
    }
  }

  @Test
  fun `refused aggregate blocks children while standalone targets remain standalone`() {
    assertFalse(session.requireRunnable(":standalone"))

    session.refuse(":consumer", listOf("codec"), "task exclusion")

    val refusal = assertThrows(IllegalStateException::class.java) {
      session.requireRunnable(":consumer")
    }
    assertTrue(refusal.message.orEmpty().contains("task exclusion"))
    assertThrows(IllegalStateException::class.java) {
      session.recordCompleted(":consumer", "codec", 1)
    }
    assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":consumer", listOf("codec"))
    }
  }

  @Test
  fun `aggregate ownership lock excludes another Gradle process model`() {
    val lockFile = temporaryDirectory.resolve("local-fuzz.lock")
    val first = FuzzCampaignFileLocks()
    val second = FuzzCampaignFileLocks()
    try {
      first.acquire(":", lockFile)
      val overlap = assertThrows(IllegalStateException::class.java) {
        second.acquire(":", lockFile)
      }
      assertTrue(overlap.message.orEmpty().contains("another fuzzAll campaign owns"))

      first.close()
      second.acquire(":", lockFile)
    } finally {
      first.close()
      second.close()
    }
  }

  @Test
  fun `campaign total refuses values JSON cannot represent exactly`() {
    session.activate(":", listOf("codec", "plain"))
    session.recordCompleted(":", "codec", Long.MAX_VALUE)
    session.recordCompleted(":", "plain", 1)

    val completed = session.requireCompleted(":", listOf("codec", "plain"))
    val overflow = assertThrows(IllegalStateException::class.java) {
      completed.totalExecutions
    }
    assertTrue(overflow.message.orEmpty().contains("exact-integer boundary"))
  }

  @Test
  fun `zero-target campaign is valid`() {
    val sessionId = session.activate(":empty", emptyList())

    val completed = session.requireCompleted(":empty", emptyList())

    assertEquals(sessionId, completed.sessionId)
    assertTrue(completed.targets.isEmpty())
  }
}
