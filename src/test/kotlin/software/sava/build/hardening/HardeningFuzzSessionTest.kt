package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardeningFuzzSessionTest {

  private val session = FuzzCampaignRegistry()

  @Test
  fun `standalone target completion is ignored`() {
    assertFalse(session.recordCompleted(":", "codec"))
    assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":", listOf("codec"))
    }
  }

  @Test
  fun `campaign requires its exact inventory to complete`() {
    val sessionId = session.activate(":consumer", listOf("plain", "codec"))

    assertTrue(session.recordCompleted(":consumer", "codec"))
    val incomplete = assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":consumer", listOf("codec", "plain"))
    }
    assertTrue(incomplete.message.orEmpty().contains("missing: fuzzPlain"))

    assertTrue(session.recordCompleted(":consumer", "plain"))
    val completed = session.requireCompleted(":consumer", listOf("plain", "codec"))
    assertEquals(sessionId, completed.sessionId)
    assertEquals(setOf("codec", "plain"), completed.targets)
  }

  @Test
  fun `campaign rejects unknown completions and inventory drift`() {
    session.activate(":", listOf("codec"))

    assertThrows(IllegalStateException::class.java) {
      session.recordCompleted(":", "plain")
    }
    assertThrows(IllegalStateException::class.java) {
      session.requireCompleted(":", listOf("codec", "plain"))
    }
  }

  @Test
  fun `zero-target campaign is valid`() {
    val sessionId = session.activate(":empty", emptyList())

    val completed = session.requireCompleted(":empty", emptyList())

    assertEquals(sessionId, completed.sessionId)
    assertTrue(completed.targets.isEmpty())
  }
}
