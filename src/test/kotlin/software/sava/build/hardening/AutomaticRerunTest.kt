package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AutomaticRerunTest {

  @TempDir
  lateinit var reportDirectory: File

  private val rerun = AutomaticRerun(
    invocationId = "3f0c4e8e-6c1b-4f0e-9d55-1f2a7c9b0a11",
    phase = "mutation",
    firstAttemptDirectory = "/work/build/reports/pitest-rerun/encoding",
  )

  private fun marker() = reportDirectory.resolve(AutomaticRerun.FILE_NAME)

  private fun attemptInProgress(invocationId: String) =
    reportDirectory.resolve(".evidence-invocation").writeText("$invocationId\n")

  @Test
  fun `a marker describes a report only while its invocation is the one the directory names`() {
    assertNull(AutomaticRerun.forCurrentReport(reportDirectory), "no marker")

    AutomaticRerun.write(reportDirectory, rerun)
    assertNull(AutomaticRerun.forCurrentReport(reportDirectory), "nothing names the report's invocation")

    attemptInProgress(rerun.invocationId)
    assertEquals(rerun, AutomaticRerun.forCurrentReport(reportDirectory))

    // A marker that outlived its report says nothing about the one that replaced it.
    attemptInProgress("another-invocation")
    assertNull(AutomaticRerun.forCurrentReport(reportDirectory))

    // Completed evidence is what names the report once it exists; unreadable, it names nothing.
    attemptInProgress(rerun.invocationId)
    reportDirectory.resolve(".evidence.tsv").writeText("not an evidence manifest\n")
    assertNull(AutomaticRerun.forCurrentReport(reportDirectory))
  }

  @Test
  fun `a marker that is incomplete or says something else is not honoured`() {
    attemptInProgress(rerun.invocationId)
    listOf(
      "",
      "invocation\t${rerun.invocationId}\nphase\tmutation\n",
      "invocation\t${rerun.invocationId}\nphase\tanother\nfirstAttempt\t/kept\n",
      "invocation\t \nphase\tcoverage\nfirstAttempt\t/kept\n",
      "phase\tcoverage\nfirstAttempt\t/kept\n",
      "no tabs at all\n",
    ).forEach { text ->
      marker().writeText(text)
      assertNull(AutomaticRerun.forCurrentReport(reportDirectory), text)
    }

    marker().writeText("invocation\t${rerun.invocationId}\nphase\tcoverage\nfirstAttempt\t/kept\n")
    assertEquals(
      AutomaticRerun(rerun.invocationId, "coverage", "/kept"),
      AutomaticRerun.forCurrentReport(reportDirectory),
    )
  }

  @Test
  fun `a marker is not honoured for a report other than the one its evidence was written for`() {
    val evidence = PitestEvidence(
      suite = "encoding",
      invocationId = rerun.invocationId,
      pitestVersion = "1.30.2",
      junitPluginVersion = "1.2.3",
      pluginSha256 = "a".repeat(64),
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = "25",
      sourceSha256 = "b".repeat(64),
      classesSha256 = "c".repeat(64),
      classpathSha256 = "d".repeat(64),
      toolClasspathSha256 = "e".repeat(64),
      mutationToolchainSha256 = "f".repeat(64),
      configurationSha256 = "0".repeat(64),
      reportSha256 = PitestEvidence.sha256("the repeat's report\n"),
      scope = PitestEvidence.FULL_SCOPE,
      historyAssisted = false,
    )
    AutomaticRerun.write(reportDirectory, rerun)
    reportDirectory.resolve(".evidence.tsv").writeText(evidence.render())
    val report = reportDirectory.resolve("mutations.csv")

    assertNull(AutomaticRerun.forCurrentReport(reportDirectory), "no report beside the evidence")
    report.writeText("the repeat's report\n")
    assertEquals(rerun, AutomaticRerun.forCurrentReport(reportDirectory))
    report.writeText("a report put there afterwards\n")
    assertNull(AutomaticRerun.forCurrentReport(reportDirectory))
  }

  @Test
  fun `every refusal of the repeat says that it is the repeat`() {
    listOf(
      listOf("C.java,com.example.C,org.pitest.M,m,5,MEMORY_ERROR,none"),
      listOf("C.java,com.example.C,org.pitest.M,m,5,STARTED,none"),
      listOf("not,a,row"),
    ).forEach { rows ->
      val plain = assertThrows(IllegalArgumentException::class.java) { Mutant.parseReport(rows) }.message!!
      assertFalse(plain.contains("automatic repeat"), plain)
      val repeated = assertThrows(IllegalArgumentException::class.java) {
        Mutant.parseReport(rows, automaticRerun = rerun)
      }.message!!
      assertTrue(repeated.startsWith(plain), repeated)
      assertTrue(repeated.endsWith(rerun.note), repeated)
    }
  }

  @Test
  fun `a refused report that is already the repeat is not to be repeated once more`() {
    val rows = listOf("C.java,com.example.C,org.pitest.M,m,5,RUN_ERROR,none")

    val first = assertThrows(IllegalArgumentException::class.java) { Mutant.parseReport(rows) }.message!!
    assertTrue(first.contains("and repeat once; a quiet machine is not required"), first)
    assertFalse(first.contains("automatic repeat"), first)

    val repeated = assertThrows(IllegalArgumentException::class.java) {
      Mutant.parseReport(rows, automaticRerun = rerun)
    }.message!!
    assertFalse(repeated.contains("repeat once"), repeated)
    assertTrue(
      repeated.contains("RUN_ERROR alone diagnoses neither load nor memory") &&
          repeated.contains("This is already the plugin's one automatic repeat: the first attempt " +
              "ended with an early minion death in the mutation phase and was discarded.") &&
          repeated.contains("in /work/build/reports/pitest-rerun/encoding.") &&
          repeated.contains("Read both attempts' logs before any retry: two invalid attempts in a " +
              "row are a recurrence to investigate, not a reason to keep repeating.") &&
          repeated.contains("Tune threads for measured aggregate contention"),
      repeated,
    )
  }
}
