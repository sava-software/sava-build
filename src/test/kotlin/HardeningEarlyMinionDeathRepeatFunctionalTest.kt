import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.task.EarlyMinionDeaths
import java.io.File

/**
 * The one automatic repeat of a PIT attempt whose minion never reached PIT. The fake PIT
 * prints PIT's own lines for that death on its first run, or on every run, and records
 * what each run was given. The repeat is switched on the task, as the plugin does from
 * the host, so these tests say the same thing on macOS and on Linux.
 */
class HardeningEarlyMinionDeathRepeatFunctionalTest : HardeningToolExecFixture() {

  private fun repeat(enabled: Boolean, extra: String = "") = """
    tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
      rerunEarlyMinionDeath.set($enabled)
      $extra
    }
  """.trimIndent()

  private val reportDir get() = File(fixtureDir, "build/reports/pitest/encoding")
  private val keptDir get() = File(fixtureDir, "build/reports/pitest-rerun/encoding")
  private val attemptsDir get() = File(fixtureDir, "build/fake-pit-attempts")
  private val marker get() = reportDir.resolve(".rerun-after-early-minion-death")

  private fun mode(value: String) {
    File(fixtureDir, "fake-pit-mode.txt").writeText(value)
    attemptsDir.deleteRecursively()
  }

  private fun pitRuns(): Int = attemptsDir.resolve("count").readText().trim().toInt()

  private fun invocation(run: Int): String =
    attemptsDir.resolve("invocation-$run.txt").readText().trim()

  private fun evidence(): PitestEvidence =
    PitestEvidence.parse(reportDir.resolve(".evidence.tsv").readText())

  @Test
  fun `a minion PIT never heard from in the mutation phase is repeated once and only the repeat is evidence`() {
    writeFixture(buildTail = repeat(true))
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    mode("early-death-then-ok")

    val output = runner("pitestEncoding").build().output

    assertEquals(2, pitRuns(), output)
    assertTrue(
      output.contains("pitest 'encoding': PIT's first attempt is discarded and the suite runs once more.") &&
          output.contains("1 minion(s) ended before PIT heard from them") &&
          output.contains("Nothing from that attempt is evidence; kept as diagnostics only:") &&
          output.contains("The log does not prove that cause") &&
          output.contains("first-attempt rows marked RUN_ERROR:") &&
          output.contains("line 1: Codec.java,com.example.Codec,"),
      output,
    )
    // One row in the build's advisories, and nothing asking for another run.
    assertEquals(
      1,
      occurrences(output, "PIT ran twice: the first attempt was discarded after 1 early minion death(s)"),
      output,
    )
    assertFalse(output.contains("repeat once"), output)

    // The report, its evidence and the marker are the second attempt's alone.
    assertTrue(reportDir.resolve("mutations.csv").readText().contains(",KILLED,"), output)
    assertFalse(reportDir.resolve("mutations.csv").readText().contains("RUN_ERROR"), output)
    assertFalse(reportDir.resolve(".running").exists(), output)
    assertNotEquals(invocation(1), invocation(2))
    assertEquals(invocation(2), evidence().invocationId)
    assertTrue(marker.readText().contains("invocation\t${invocation(2)}\n"), marker.readText())
    assertTrue(marker.readText().contains("phase\tmutation\n"), marker.readText())
    assertFalse(reportDir.resolve("pitest.stderr.log").readText().contains("Minion did not start"), output)

    // The first attempt is kept, outside the report directory, as it was.
    assertTrue(keptDir.resolve("pitest.stderr.log").readText().contains(
      "PIT >> SEVERE : Minion did not start or died during analysis"), output)
    assertTrue(keptDir.resolve("pitest.stdout.log").isFile, output)
    assertTrue(keptDir.resolve("mutations.csv").readText().contains(",RUN_ERROR,"), output)
    assertTrue(output.contains("build/reports/pitest-rerun/encoding/pitest.stderr.log"), output)

    // The next ordinary run replaces the report, so what described the old one goes too.
    mode("ok")
    val next = runner("pitestEncoding").build().output
    assertFalse(next.contains("runs once more"), next)
    assertFalse(marker.exists(), next)
    assertFalse(keptDir.resolve("pitest.stderr.log").exists(), next)
    assertFalse(keptDir.resolve("pitest.stdout.log").exists(), next)
    assertFalse(keptDir.resolve("mutations.csv").exists(), next)
  }

  @Test
  fun `a coverage minion PIT never heard from is repeated once`() {
    writeFixture(buildTail = repeat(true))
    mode("coverage-death-then-ok")

    val output = runner("pitestEncoding").build().output

    assertEquals(2, pitRuns(), output)
    assertTrue(
      output.contains("PIT's first attempt is discarded and the suite runs once more.") &&
          output.contains("The coverage minion ended before PIT heard from it") &&
          output.contains("PIT ran twice: the first attempt was discarded after an early coverage-minion death"),
      output,
    )
    assertFalse(output.contains("first-attempt rows marked RUN_ERROR"), output)
    // The first attempt failed without a report, and that failure is not the task's.
    assertFalse(output.contains("failed attempt raw logs"), output)
    assertEquals(invocation(2), evidence().invocationId)
    assertTrue(marker.readText().contains("phase\tcoverage\n"), marker.readText())
    assertTrue(keptDir.resolve("pitest.stderr.log").readText().contains(
      "Coverage generator Minion exited abnormally due to MINION_DIED"), output)
    assertFalse(keptDir.resolve("mutations.csv").exists(), output)

    // The same from a reused configuration cache: the task's own state is all it needs.
    mode("coverage-death-then-ok")
    val reused = runner("pitestEncoding").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertEquals(2, pitRuns(), reused)
    assertTrue(reused.contains("PIT's first attempt is discarded and the suite runs once more."), reused)
  }

  @Test
  fun `the plugin switches the repeat on from the host, and only on macOS`() {
    // No fixture override: the wiring's own convention decides.
    writeFixture()
    mode("early-death-then-ok")
    val repeats = EarlyMinionDeaths.hostReruns(System.getProperty("os.name"))

    val run = runner("pitestEncoding")
    val output = if (repeats) run.build().output else run.buildAndFail().output

    assertEquals(if (repeats) 2 else 1, pitRuns(), output)
    assertEquals(repeats, output.contains("PIT's first attempt is discarded and the suite runs once more."), output)
    assertEquals(!repeats, output.contains("repeat once; a quiet machine is not required"), output)
  }

  @Test
  fun `a repeat that cannot start is a failure that says PIT ran once, not a repeat`() {
    // A source the recompile read changes while the first attempt runs.
    writeFixture(buildTail = repeat(true))
    mode("early-death-edits-input-then-ok")
    val edited = runner("pitestEncoding").buildAndFail().output

    assertEquals(1, pitRuns(), edited)
    assertTrue(
      edited.contains("compileForPitest has published no stamp naming the sources and classes now on disk") &&
          edited.contains("pitest 'encoding': the plugin's one automatic repeat did not get as far as a " +
              "finished PIT process, so PIT ran once. The first attempt ended with an early minion death " +
              "in the mutation phase and was set aside; its logs are kept, as diagnostics only, in") &&
          edited.contains("pitest 'encoding': no PIT report at"),
      edited,
    )
    // Nothing announced or counted a second run, and nothing says a report is a repeat.
    assertFalse(edited.contains("runs once more"), edited)
    assertFalse(edited.contains("PIT ran twice"), edited)
    assertFalse(edited.contains("automatic repeat:"), edited)
    assertFalse(marker.exists(), edited)
    assertTrue(reportDir.resolve(".running").isFile, edited)
    assertTrue(keptDir.resolve("mutations.csv").readText().contains(",RUN_ERROR,"), edited)
    assertTrue(keptDir.resolve("pitest.stderr.log").isFile, edited)
  }

  @Test
  fun `a first attempt that cannot be set aside stands as a failed one and says where its files are`() {
    writeFixture(buildTail = repeat(true))
    mode("early-death-blocks-keep-file-then-ok")

    val output = runner("pitestEncoding").buildAndFail().output

    assertEquals(1, pitRuns(), output)
    assertTrue(
      output.contains("pitest 'encoding': the first attempt ended with an early minion death, but it " +
          "could not be set aside for a repeat") &&
          output.contains("so PIT ran once and the attempt stands as a failed one.") &&
          output.contains("pitest-rerun/encoding: [pitest.stderr.log, pitest.stdout.log]; left in") &&
          output.contains("pitest/encoding: [mutations.csv].") &&
          output.contains("first-attempt rows marked RUN_ERROR:") &&
          output.contains("the report at") && output.contains("was left by an interrupted or failed run"),
      output,
    )
    assertFalse(output.contains("runs once more"), output)
    assertFalse(output.contains("PIT ran twice"), output)
    assertFalse(marker.exists(), output)
    assertTrue(reportDir.resolve(".running").isFile, output)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), output)
  }

  @Test
  fun `the history file goes back even when the first attempt cannot be set aside at all`() {
    writeFixture(buildTail = repeat(true))
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    enableFakeArcMutate()
    val history = File(fixtureDir, ".pitest-history/encoding.hist")
    history.apply { parentFile.mkdirs() }.writeText("as it was before")
    mode("early-death-blocks-keep-directory-then-ok")

    val output = runner("pitestEncoding").buildAndFail().output

    assertEquals(1, pitRuns(), output)
    assertTrue(output.contains("could not be set aside for a repeat"), output)
    assertTrue(output.contains("cannot create"), output)
    // The first attempt rewrote it; no later run may read that.
    assertEquals("as it was before", history.readText(), output)
    assertFalse(File(fixtureDir, "build/tmp/pitestEncoding/history-before-first-attempt").exists(), output)
  }

  @Test
  fun `a repeat whose process cannot be started is not a second PIT run`() {
    // The task's environment is the one thing about the PIT process a build may set, and it
    // is turned into strings when the process is started: the fixture fails there, as a
    // process that cannot be launched does, once the first attempt has left its flag.
    writeFixture(buildTail = """
      class RefusesLaunch(private val flag: java.io.File) : java.io.Serializable {
        override fun toString(): String {
          check(!flag.exists()) { "fixture: the PIT process could not be started" }
          return "ok"
        }
      }
      tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
        rerunEarlyMinionDeath.set(true)
        environment("FIXTURE_LAUNCH", RefusesLaunch(layout.buildDirectory.file("fake-pit-attempts/refuse-launch").get().asFile))
      }
    """.trimIndent())
    mode("early-death-refuses-relaunch-then-ok")

    val output = runner("pitestEncoding").buildAndFail().output

    assertEquals(1, pitRuns(), output)
    assertTrue(
      output.contains("fixture: the PIT process could not be started") &&
          output.contains("pitest 'encoding': the plugin's one automatic repeat did not get as far as a " +
              "finished PIT process, so PIT ran once.") &&
          output.contains("pitest 'encoding': no PIT report at"),
      output,
    )
    // No marker, so nothing afterwards calls this two invalid attempts.
    assertFalse(marker.exists(), output)
    assertFalse(output.contains("This is already the plugin's one automatic repeat"), output)
    assertFalse(output.contains("this failure is in the plugin's one automatic repeat"), output)
    assertFalse(output.contains("PIT ran twice"), output)
  }

  @Test
  fun `a second early death in the mutation phase fails as an invalid run and says it was the repeat`() {
    writeFixture(buildTail = repeat(true))
    mode("early-death-always")

    val output = runner("pitestEncoding").buildAndFail().output

    assertEquals(2, pitRuns(), "a failed repeat must not be repeated:\n$output")
    assertTrue(
      output.contains("PIT's first attempt is discarded and the suite runs once more.") &&
          output.contains("not valid completed evidence") &&
          output.contains("RUN_ERROR x1") &&
          output.contains("This is already the plugin's one automatic repeat: the first attempt ended " +
              "with an early minion death in the mutation phase and was discarded.") &&
          output.contains("build/reports/pitest-rerun/encoding. Read both attempts' logs before any " +
              "retry: two invalid attempts in a row are a recurrence to investigate, not a reason " +
              "to keep repeating."),
      output,
    )
    assertFalse(output.contains("repeat once; a quiet machine is not required"), output)
    // Both attempts' logs are there to read.
    assertTrue(reportDir.resolve("pitest.stderr.log").readText().contains("Minion did not start"), output)
    assertTrue(keptDir.resolve("pitest.stderr.log").readText().contains("Minion did not start"), output)

    // Debt reads the same report later and must not ask for a third run either.
    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(debt.contains("This is already the plugin's one automatic repeat"), debt)
    assertFalse(debt.contains("repeat once; a quiet machine is not required"), debt)
  }

  @Test
  fun `a second early death in the coverage phase fails the task and says it was the repeat`() {
    writeFixture(buildTail = repeat(true))
    mode("coverage-death-always")

    val output = runner("pitestEncoding").buildAndFail().output

    assertEquals(2, pitRuns(), "a failed repeat must not be repeated:\n$output")
    assertTrue(
      output.contains("non-zero exit value 1") &&
          output.contains("pitest 'encoding': this failure is in the plugin's one automatic repeat. The " +
              "first attempt ended with an early minion death in the coverage phase and was set aside") &&
          output.contains("Read both attempts' logs before running the suite again.") &&
          output.contains("pitest 'encoding': no PIT report at") &&
          output.contains("This is already the plugin's one automatic repeat") &&
          output.contains("Retry: run :pitestEncoding in a new Gradle invocation"),
      output,
    )
    assertEquals(1, occurrences(output, "failed attempt raw logs"), output)
    assertTrue(reportDir.resolve(".running").isFile, output)
  }

  @Test
  fun `nothing is repeated where the task is not switched on or the run is not one the rule covers`() {
    fun assertSingleFailedRun(output: String, what: String) {
      assertEquals(1, pitRuns(), "$what:\n$output")
      assertFalse(output.contains("runs once more"), "$what:\n$output")
    }

    // Off, as on a host that is not macOS: today's refusal, word for word.
    writeFixture(buildTail = repeat(false))
    mode("early-death-then-ok")
    val off = runner("pitestEncoding").buildAndFail().output
    assertSingleFailedRun(off, "switched off")
    assertTrue(off.contains("RUN_ERROR x1") && off.contains("repeat once; a quiet machine is not required"), off)
    assertFalse(marker.exists(), off)
    assertFalse(keptDir.exists(), off)

    // A scoped run is an iteration diagnostic.
    writeFixture(buildTail = repeat(true))
    mode("early-death-then-ok")
    assertSingleFailedRun(
      runner("pitestEncoding", "-PmutateOnly=com.example.Codec").buildAndFail().output, "scoped")

    // A verbosity at which PIT does not print the lines the rule reads.
    writeFixture(buildTail = repeat(true, """verbosity.set("QUIET")"""))
    mode("early-death-then-ok")
    assertSingleFailedRun(runner("pitestEncoding").buildAndFail().output, "quiet")

    // A PIT release the rule was not read against.
    writeFixture(buildTail = repeat(true, """pitestVersion.set("1.30.1")"""))
    mode("early-death-then-ok")
    assertSingleFailedRun(runner("pitestEncoding").buildAndFail().output, "another PIT release")

    // A RUN_ERROR that PIT's log does not put down to a minion it never heard from.
    writeFixture(buildTail = repeat(true))
    mode("run-error")
    val unexplained = runner("pitestEncoding").buildAndFail().output
    assertFalse(unexplained.contains("runs once more"), unexplained)
    assertTrue(unexplained.contains("repeat once; a quiet machine is not required"), unexplained)
  }

  @Test
  fun `certification binds the repeat and nothing of the first attempt`() {
    writeFixture(moneyMath = true, buildTail = repeat(true))
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest").mkdirs()
    File(fixtureDir, "config/pitest/encoding-accepted.csv").writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # legacy accepted row\n")
    val classTreeAdvice = "which the enabled mutator set cannot mutate"
    val once = occurrences(runner("pitestEncoding").build().output, classTreeAdvice)
    assertTrue(once > 0, "the fixture's class tree must draw advice for this to mean anything")
    File(fixtureDir, "fake-pit-mode.txt").writeText("early-death-then-ok")

    val output = runner("clean", "hardeningCertify").build().output

    assertEquals(2, pitRuns(), output)
    // What is said about the class tree before PIT starts is said once, not once per attempt.
    assertEquals(once, occurrences(output, classTreeAdvice), output)
    assertTrue(output.contains("PIT's first attempt is discarded and the suite runs once more."), output)
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()
    assertTrue(receipt.contains("suite\tencoding"), receipt)
    assertTrue(receipt.contains(invocation(2)), receipt)
    assertFalse(receipt.contains(invocation(1)), receipt)
    assertEquals(invocation(2), evidence().invocationId)
    assertTrue(receipt.contains(evidence().reportSha256), receipt)
    assertFalse(evidence().historyAssisted, output)
    assertTrue(keptDir.resolve("mutations.csv").readText().contains(",RUN_ERROR,"), output)
  }

  @Test
  fun `the repeat reads the history the first attempt read, never what it wrote`() {
    writeFixture(buildTail = repeat(true))
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    enableFakeArcMutate()
    val history = File(fixtureDir, ".pitest-history/encoding.hist")
    val copyTakenAside = File(fixtureDir, "build/tmp/pitestEncoding/history-before-first-attempt")
    fun read(run: Int) = attemptsDir.resolve("history-read-$run.txt").readText()

    // A history from an earlier run: the first attempt rewrites it, the repeat gets it back.
    history.apply { parentFile.mkdirs() }.writeText("as it was before")
    mode("early-death-then-ok")
    val restored = runner("pitestEncoding").build().output
    assertEquals(2, pitRuns(), restored)
    assertEquals("as it was before", read(1), restored)
    assertEquals("as it was before", read(2), restored)
    assertEquals("written by run 2", history.readText(), restored)
    assertTrue(evidence().historyAssisted, restored)
    assertFalse(copyTakenAside.exists(), restored)

    // No history yet: the repeat must not be handed the first attempt's.
    history.delete()
    mode("early-death-then-ok")
    val absent = runner("pitestEncoding").build().output
    assertEquals(2, pitRuns(), absent)
    assertEquals("<none>", read(1), absent)
    assertEquals("<none>", read(2), absent)
    assertEquals("written by run 2", history.readText(), absent)

    // A coverage-phase death ends PIT before it opens the history for writing.
    history.writeText("as it was before")
    mode("coverage-death-then-ok")
    val untouched = runner("pitestEncoding").build().output
    assertEquals(2, pitRuns(), untouched)
    assertEquals("as it was before", read(1), untouched)
    assertEquals("as it was before", read(2), untouched)
    assertEquals("written by run 2", history.readText(), untouched)
    assertFalse(File(fixtureDir, ".pitest-history/encoding.hist.restore").exists(), untouched)
    assertFalse(copyTakenAside.exists(), untouched)
  }
}
