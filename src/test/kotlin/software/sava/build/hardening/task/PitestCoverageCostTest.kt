package software.sava.build.hardening.task

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PitestCoverageCostTest {
  private val name = "[engine:junit-jupiter]/[class:example.CodecTest]/" +
    "[test-template:roundTrip(java.lang.String, int)]/[test-template-invocation:#2]"
  private val qualifiedName = "example.CodecTest.$name"

  private fun row(killer: String, status: String = "KILLED") =
    "Codec.java,example.Codec,example.MathMutator,encode,12,$status,$killer"

  @Test
  fun `full qualified JUnit identity establishes a recorded kill including commas and invocation`() {
    assertTrue(pitestSlowTestHasRecordedKill(name, listOf(row(qualifiedName))))
    assertTrue(pitestSlowTestHasRecordedKill(name, listOf(row(name))))
  }

  @Test
  fun `similar names and other invocation or class identities do not establish a kill`() {
    listOf(
      "example.CodecTest",
      qualifiedName.replace("#2", "#1"),
      qualifiedName.replace("int)", "long)"),
      "other.CodecTest.$name",
      qualifiedName + "suffix",
      "",
    ).forEach { killer ->
      assertFalse(pitestSlowTestHasRecordedKill(name, listOf(row(killer))), killer)
    }
    assertFalse(pitestSlowTestHasRecordedKill("roundTrip", listOf(row("roundTrip"))))
  }

  @Test
  fun `only a killed row in a wholly valid report can add the advisory`() {
    listOf("SURVIVED", "NO_COVERAGE", "TIMED_OUT", "RUN_ERROR").forEach { status ->
      assertFalse(pitestSlowTestHasRecordedKill(name, listOf(row(qualifiedName, status))), status)
    }
    assertFalse(pitestSlowTestHasRecordedKill(name, listOf(row(qualifiedName), "truncated,row")))
    assertFalse(pitestSlowTestHasRecordedKill(name, listOf(row(qualifiedName), row("", "RUN_ERROR"))))
    assertFalse(pitestSlowTestHasRecordedKill(name, emptyList()))
  }
}
