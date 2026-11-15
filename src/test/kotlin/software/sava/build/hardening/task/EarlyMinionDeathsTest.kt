package software.sava.build.hardening.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.HardeningToolDefaults
import java.io.File

/**
 * The log lines are PIT 1.30.2's own. The mutation-phase ones are as the plugin retained
 * them from a gate that was hit, on JDK 25, whose time stamp carries U+202F before AM/PM;
 * the coverage-phase ones are from a reproduction with a listener holding PIT's port.
 */
class EarlyMinionDeathsTest {

  @TempDir
  lateinit var directory: File

  private val neverHeard =
    "5:56:33 PM PIT >> SEVERE : Minion did not start or died during analysis. This may " +
      "indicate an issue in your environment such as insufficient memory."
  private val runErrorWarning = "5:56:33 PM PIT >> WARNING : Minion exited abnormally due to RUN_ERROR"
  private val timedOutWarning = "5:50:52 PM PIT >> WARNING : Minion exited abnormally due to TIMED_OUT"
  private val ordinary = listOf(
    "5:41:02 PM PIT >> INFO : Verbose logging is disabled. If you encounter a problem, please enable it before reporting an issue.",
    "5:41:03 PM PIT >> INFO : Created 129 mutation test units in pre scan",
    "5:41:04 PM PIT >> INFO : Sending 212 test classes to minion",
    "5:41:20 PM PIT >> INFO : MINION : 5:41:20 PM PIT >> INFO : Checking environment",
    "5:42:11 PM PIT >> INFO : Calculated coverage in 67 seconds.",
  )
  private val completed = "5:58:40 PM PIT >> INFO : Completed in 1056 seconds"

  private val coverageDeath = listOf(
    "9:41:03 AM PIT >> INFO : Verbose logging is disabled. If you encounter a problem, please enable it before reporting an issue.",
    "9:41:03 AM PIT >> INFO : Created 6 mutation test units in pre scan",
    "9:41:13 AM PIT >> INFO : MINION : 9:41:13 AM PIT >> SEVERE : Error calculating coverage. Process will exit.",
    "9:41:13 AM PIT >> INFO : MINION : org.pitest.util.PitError: Read timed out",
    "9:41:13 AM PIT >> INFO : MINION : \tat org.pitest.util.Unchecked.translateCheckedException(Unchecked.java:20)",
    "9:41:13 AM PIT >> INFO : MINION : \tat org.pitest.coverage.execute.CoverageMinion.main(CoverageMinion.java:70)",
    "9:41:13 AM PIT >> INFO : MINION : Caused by: java.net.SocketTimeoutException: Read timed out",
    "9:41:28 AM PIT >> SEVERE : Coverage generator Minion exited abnormally due to MINION_DIED",
    "Exception in thread \"main\" org.pitest.util.PitError: Coverage generation minion exited abnormally! (MINION_DIED)",
  )

  private fun row(line: Int, status: String, killer: String = "none") =
    "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status,$killer"

  private var files = 0

  /** Each report and each log is a file of its own: one fixture must never overwrite another. */
  private fun report(vararg rows: String): File =
    directory.resolve("mutations-${++files}.csv").apply { writeText(rows.joinToString("\n", postfix = "\n")) }

  private fun noReport(): File = directory.resolve("no-report-${++files}.csv")

  private fun log(lines: List<String>): File =
    directory.resolve("pitest-${++files}.stderr.log").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

  private fun classify(exitValue: Int, report: File, log: File) =
    EarlyMinionDeaths.classify(exitValue, report, log)

  @Test
  fun `one minion PIT never heard from is one SEVERE line, one warning and one RUN_ERROR row`() {
    val death = classify(
      0,
      report(row(10, "KILLED", "com.example.CodecTest"), row(12, "RUN_ERROR"), row(14, "SURVIVED")),
      log(ordinary + neverHeard + runErrorWarning + completed),
    )

    assertEquals(EarlyMinionDeath.Mutation(listOf("line 2: ${row(12, "RUN_ERROR")}")), death)
    assertEquals("mutation", death!!.phase)
    assertTrue(death.description.startsWith("1 minion(s) ended before PIT heard from them"), death.description)
  }

  @Test
  fun `several deaths count as long as each row has its own pair of lines, beside ordinary timeouts`() {
    // The shape of the gate that was hit: two deaths, three timeouts, uncovered mutants.
    val death = classify(
      0,
      report(
        row(10, "KILLED", "com.example.CodecTest"), row(11, "TIMED_OUT"), row(12, "RUN_ERROR"),
        row(13, "NO_COVERAGE"), row(14, "RUN_ERROR"), row(15, "SURVIVED"),
      ),
      log(ordinary + timedOutWarning + timedOutWarning + timedOutWarning +
        neverHeard + runErrorWarning + neverHeard + runErrorWarning + completed),
    )

    assertEquals(2, (death as EarlyMinionDeath.Mutation).rows.size)

    val five = (1..5).map { row(it, "RUN_ERROR") }
    val fiveDeaths = (1..5).flatMap { listOf(neverHeard, runErrorWarning) }
    assertEquals(5, (classify(0, report(*five.toTypedArray()), log(ordinary + fiveDeaths))
        as EarlyMinionDeath.Mutation).rows.size)
  }

  @Test
  fun `more deaths than the backstop allows are not an early death to repeat`() {
    val six = (1..6).map { row(it, "RUN_ERROR") }
    val sixDeaths = (1..6).flatMap { listOf(neverHeard, runErrorWarning) }

    assertNull(classify(0, report(*six.toTypedArray()), log(ordinary + sixDeaths)))
  }

  @Test
  fun `a RUN_ERROR that the log does not account for row by row is not an early death`() {
    val oneRow = report(row(12, "RUN_ERROR"), row(14, "KILLED", "com.example.CodecTest"))

    // The control: this report with exactly its own pair of lines is an early death, so
    // each refusal below is owed to the one thing it adds or leaves out.
    assertEquals(1, (classify(0, oneRow, log(ordinary + neverHeard + runErrorWarning + completed))
        as EarlyMinionDeath.Mutation).rows.size)
    // PIT's worker reported RUN_ERROR itself: no line in the parent's log at all.
    assertNull(classify(0, oneRow, log(ordinary + completed)))
    // A minion that caught its own failure: the warning without the SEVERE line.
    assertNull(classify(0, oneRow, log(ordinary + runErrorWarning + completed)))
    // One death and one self-reported failure in the same run.
    val twoRows = report(row(12, "RUN_ERROR"), row(13, "RUN_ERROR"))
    assertNull(classify(0, twoRows, log(ordinary + neverHeard + runErrorWarning + runErrorWarning)))
    // Two SEVERE lines for one row.
    assertNull(classify(0, oneRow, log(ordinary + neverHeard + neverHeard + runErrorWarning)))
    // A minion that connected and then died is reported with its own warning.
    assertNull(classify(0, oneRow, log(ordinary +
      "5:56:30 PM PIT >> WARNING : Error while watching child process" +
      neverHeard + runErrorWarning)))
    assertNull(classify(0, oneRow, log(ordinary +
      "5:56:30 PM PIT >> WARNING : interrupted while waiting for child process" +
      neverHeard + runErrorWarning)))
  }

  @Test
  fun `any other row that is not valid evidence, or not a row, rules the attempt out`() {
    val lines = log(ordinary + neverHeard + runErrorWarning)

    assertEquals(1, (classify(0, report(row(12, "RUN_ERROR"), row(13, "NO_COVERAGE")), lines)
        as EarlyMinionDeath.Mutation).rows.size)
    assertNull(classify(0, report(row(12, "RUN_ERROR"), row(13, "MEMORY_ERROR")), lines))
    assertNull(classify(0, report(row(12, "RUN_ERROR"), row(13, "FUTURE_STATUS")), lines))
    assertNull(classify(0, report(row(12, "RUN_ERROR"), "not,a,row"), lines))
    assertNull(classify(0, report(row(12, "RUN_ERROR"),
      "Codec.java,com.example.Codec,org.pitest.M,encode,,RUN_ERROR,none"), lines))
    // No RUN_ERROR at all: a clean run that happens to carry the words.
    assertNull(classify(0, report(row(12, "KILLED", "com.example.CodecTest")), lines))
  }

  @Test
  fun `neither a minion nor a test can write one of PIT's own lines`() {
    val oneRow = report(row(12, "RUN_ERROR"))
    val relayedForgery = "5:41:30 PM PIT >> INFO : MINION : $neverHeard"
    val relayedWarning = "5:41:30 PM PIT >> INFO : MINION : $runErrorWarning"
    val verboseRelay = "stderr  : $neverHeard"
    val verboseWarning = "stderr  : $runErrorWarning"

    // Test output relayed from the coverage minion, and a verbose relay, are not PIT's.
    assertNull(classify(0, oneRow, log(ordinary + relayedForgery + relayedWarning)))
    assertNull(classify(0, oneRow, log(ordinary + verboseRelay + verboseWarning)))
    // Nor can test output veto a real death by printing the words of another failure.
    val death = classify(0, oneRow, log(ordinary +
      "5:41:30 PM PIT >> INFO : MINION : WARNING : Error while watching child process" +
      "5:41:31 PM PIT >> INFO : MINION : Error while watching child process" +
      relayedForgery + neverHeard + runErrorWarning))
    assertEquals(1, (death as EarlyMinionDeath.Mutation).rows.size)
  }

  @Test
  fun `a log that cannot be decoded, or is not there, never throws`() {
    val oneRow = report(row(12, "RUN_ERROR"))
    val bytes = (ordinary.joinToString("\n", postfix = "\n")).toByteArray() +
      "5:41:30 PM PIT >> INFO : MINION : caf".toByteArray() + byteArrayOf(0xE9.toByte(), 0xFF.toByte(), '\n'.code.toByte()) +
      "$neverHeard\n$runErrorWarning\n".toByteArray()
    val undecodable = directory.resolve("pitest.stderr.log").apply { writeBytes(bytes) }

    assertEquals(1, (classify(0, oneRow, undecodable) as EarlyMinionDeath.Mutation).rows.size)
    assertNull(classify(0, oneRow, directory.resolve("absent.log")))
    assertNull(classify(0, directory.resolve("absent.csv"), log(ordinary + neverHeard + runErrorWarning)))
  }

  @Test
  fun `a PIT that words its lines differently is not repeated`() {
    val oneRow = report(row(12, "RUN_ERROR"))

    assertNull(classify(0, oneRow, log(ordinary +
      "5:56:33 PM PIT >> SEVERE : Minion failed to start or died while analysing." + runErrorWarning)))
    assertNull(classify(0, oneRow, log(ordinary + neverHeard +
      "5:56:33 PM PIT >> WARNING : Minion exited abnormally because of RUN_ERROR")))
    // Lines written by a consumer's own logging configuration carry no 'PIT >> '.
    assertNull(classify(0, oneRow, log(listOf(
      "SEVERE: Minion did not start or died during analysis. This may indicate an issue",
      "WARNING: Minion exited abnormally due to RUN_ERROR",
    ))))
  }

  @Test
  fun `a coverage minion whose first read timed out before PIT heard from it is an early death`() {
    val death = classify(1, noReport(), log(coverageDeath))

    assertEquals(EarlyMinionDeath.Coverage, death)
    assertEquals("coverage", death!!.phase)
  }

  @Test
  fun `a coverage failure of any other shape is not repeated`() {
    fun without(vararg fragments: String) = coverageDeath.filterNot { line -> fragments.any(line::contains) }
    fun replacing(old: String, new: String) = coverageDeath.map { it.replace(old, new) }

    // A minion that connected to PIT and then failed or died: PIT names another exit code.
    assertNull(classify(1, noReport(), log(replacing("MINION_DIED", "UNKNOWN_ERROR"))))
    // Killed before it connected: no minion lines at all.
    assertNull(classify(1, noReport(), log(without("PIT >> INFO : MINION :"))))
    // The words of a read that timed out, without the minion's own failure line.
    assertNull(classify(1, noReport(), log(without("Error calculating coverage"))))
    // A listener that closes, resets or answers ends the minion another way; a refused
    // connection is deterministic.
    listOf(
      "java.io.EOFException",
      "java.net.SocketException: Connection reset",
      "java.io.StreamCorruptedException: invalid stream header",
      "java.net.ConnectException: Connection refused",
    ).forEach { cause ->
      assertNull(
        classify(1, noReport(), log(replacing("Read timed out", cause))),
        cause,
      )
    }
    // PIT wrote the coverage arguments to a minion it had accepted.
    assertNull(classify(1, noReport(), log(coverageDeath +
      "9:41:04 AM PIT >> INFO : Sending 12 test classes to minion")))
    // The parent's own line is missing, or relayed instead of PIT's.
    assertNull(classify(1, noReport(), log(without("Coverage generator Minion exited"))))
    assertNull(classify(1, noReport(), log(without("Coverage generator Minion exited") +
      "9:41:28 AM PIT >> INFO : MINION : 9:41:28 AM PIT >> SEVERE : Coverage generator Minion exited abnormally due to MINION_DIED")))
    // A report exists, so this is not the phase that writes none.
    assertNull(classify(1, report(row(12, "KILLED", "com.example.CodecTest")), log(coverageDeath)))
    // Exit 0 never is a coverage death, and the mutation rule needs a report.
    assertNull(classify(0, noReport(), log(coverageDeath)))
    assertNull(classify(1, noReport(), directory.resolve("absent.log")))
  }

  @Test
  fun `the host and verbosity gates say where the rule can apply at all`() {
    assertTrue(EarlyMinionDeaths.hostReruns("Mac OS X"))
    assertTrue(EarlyMinionDeaths.hostReruns("macOS"))
    assertFalse(EarlyMinionDeaths.hostReruns("Linux"))
    assertFalse(EarlyMinionDeaths.hostReruns("Windows 11"))
    assertFalse(EarlyMinionDeaths.hostReruns(null))

    listOf("DEFAULT", "NO_SPINNER", "VERBOSE", "VERBOSE_NO_SPINNER").forEach {
      assertTrue(EarlyMinionDeaths.readableVerbosity(it), it)
    }
    // The quiet levels drop the warning and the relayed minion lines the rule reads.
    listOf("QUIET", "QUIET_WITH_PROGRESS", "SILENT").forEach {
      assertFalse(EarlyMinionDeaths.readableVerbosity(it), it)
    }
  }

  @Test
  fun `the rule was read against the PIT release the plugin ships`() {
    assertEquals(
      HardeningToolDefaults.PITEST,
      EARLY_MINION_DEATH_AUDITED_PITEST,
      "The default PIT moved. Before moving EARLY_MINION_DEATH_AUDITED_PITEST, re-read in the " +
        "new release how MutationTestUnit, MutationTestProcess and DefaultCoverageGenerator " +
        "report a minion whose connection never reached PIT: one SEVERE line, one warning and " +
        "one RUN_ERROR row per death, and MINION_DIED only for a coverage minion PIT never accepted.",
    )
  }
}
