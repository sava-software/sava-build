import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Runs JMH for real through 'software.sava.build.feature.jmh'. Under the convention's default
 * failOnError, a benchmark that errors must fail the 'jmh' task whichever way it errors: a
 * trial @Setup that throws, a fork that dies before it measures anything, and an iteration
 * @Setup that throws after measurements were posted, which under a silent harness would leave
 * a short row on the scoreboard. The archive under jmh-results/ must hold exactly the runs that
 * wrote results, under names that stay chronological. The harness version the convention holds
 * must be the one the benchmark classpath resolves, and every graph here runs with
 * --warning-mode=fail, so a deprecation anywhere in the feature's graph fails the build.
 *
 * jmh-gradle-plugin 0.7.3 and earlier passed failOnError to JMH as '-foe 1', which JMH reads as
 * false, so every one of the failing runs here ended BUILD SUCCESSFUL; 0.7.4 passes 'true'. The
 * runs here hold that through any later plugin bump.
 */
class JmhFeatureRunTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun writeFixture() {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "jmh-run-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.jmh")
        }

        repositories {
          mavenCentral()
        }

        // The harness the benchmark classpath resolves, read at execution from the resolved
        // files so the task is configuration-cache clean.
        tasks.register("printJmhCore") {
          val benchmarkRuntime: FileCollection = configurations.getByName("jmhRuntimeClasspath")
          doLast {
            println("JMH_CORE=" + benchmarkRuntime.files.map { it.name }.filter { it.startsWith("jmh-core-") }.joinToString(","))
          }
        }
      """.trimIndent() + "\n"
    )
    val benchmarks = File(fixtureDir, "src/jmh/java/probe").apply { mkdirs() }
    File(benchmarks, "OkBench.java").writeText(
      """
        package probe;

        import org.openjdk.jmh.annotations.Benchmark;

        public class OkBench {
          @Benchmark
          public int probe() {
            return 42;
          }
        }
      """.trimIndent() + "\n"
    )
    File(benchmarks, "SetupFailBench.java").writeText(
      """
        package probe;

        import org.openjdk.jmh.annotations.Benchmark;
        import org.openjdk.jmh.annotations.Scope;
        import org.openjdk.jmh.annotations.Setup;
        import org.openjdk.jmh.annotations.State;

        // Stands in for a @Setup cross-check that finds its variants disagreeing.
        @State(Scope.Benchmark)
        public class SetupFailBench {
          @Setup
          public void setup() {
            throw new IllegalStateException("SetupFailBench: variants disagree");
          }

          @Benchmark
          public int probe() {
            return 42;
          }
        }
      """.trimIndent() + "\n"
    )
    File(benchmarks, "LateFailBench.java").writeText(
      """
        package probe;

        import org.openjdk.jmh.annotations.Benchmark;
        import org.openjdk.jmh.annotations.Level;
        import org.openjdk.jmh.annotations.Scope;
        import org.openjdk.jmh.annotations.Setup;
        import org.openjdk.jmh.annotations.State;

        // Fails in the third measurement iteration, after two posted their numbers.
        @State(Scope.Benchmark)
        public class LateFailBench {
          private int iteration;

          @Setup(Level.Iteration)
          public void setupIteration() {
            if (++iteration == 3) {
              throw new IllegalStateException("LateFailBench: fails in iteration 3");
            }
          }

          @Benchmark
          public int probe() {
            return 42;
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--warning-mode=fail", "--no-watch-fs", "--stacktrace")

  /**
   * A quick run: no warmup, one fork, short iterations. The service JVM flags pre-touch a 2g
   * heap, so every run that is not about them replaces the list with a small heap.
   */
  private fun jmhRun(benchmark: String, iterations: Int = 1, vararg more: String): Array<String> =
    arrayOf(
      "jmh", "-PjmhIncludes=$benchmark", "-PjmhWarmupIterations=0", "-PjmhIterations=$iterations",
      "-PjmhTimeOnIteration=100ms", "-PjmhFork=1", *more,
    )

  private val smallHeap = "-PjmhJvmArgsAppend=-Xmx256m"

  private val archiveDir: File get() = File(fixtureDir, "jmh-results")

  private val results: File get() = File(fixtureDir, "build/results/jmh/results.txt")

  private fun archivedRuns(): List<File> = (archiveDir.listFiles() ?: emptyArray()).filter { it.extension == "txt" }.sortedBy { it.name }

  private fun assertJmhFailed(result: BuildResult) {
    assertEquals(TaskOutcome.FAILED, result.task(":jmh")?.outcome, result.output)
    assertTrue(result.output.contains("fail on error was requested"), result.output)
  }

  @Test
  fun `a benchmark that errors fails the jmh task, however it errors`() {
    // A trial @Setup that throws: JMH reports the failure, honours -foe, and exits non-zero
    // before it writes a results file, so the archive step is never reached. JMH creates the
    // results file, empty, before the run when it was missing, and leaves it so.
    val setupFailed = runner(*jmhRun("SetupFailBench"), smallHeap).buildAndFail()
    assertJmhFailed(setupFailed)
    assertTrue(setupFailed.output.contains("<failure>"), setupFailed.output)
    assertTrue(setupFailed.output.contains("SetupFailBench: variants disagree"), setupFailed.output)
    assertFalse(archiveDir.exists(), "a failed run must archive nothing")
    assertTrue(results.isFile && results.length() == 0L, "JMH leaves a results file it created empty: " + results.isFile)

    // A passing benchmark ahead of the erroring one (JMH runs them in name order): JMH writes
    // its results file only after the last benchmark, so the first error ends the run with the
    // passing benchmark's score in the console output alone, and the task fails.
    val mixed = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap).buildAndFail()
    assertJmhFailed(mixed)
    assertTrue(mixed.output.contains("Result \"probe.OkBench.probe\":"), mixed.output)
    assertTrue(mixed.output.contains("SetupFailBench: variants disagree"), mixed.output)
    assertFalse(archiveDir.exists(), "a failed run must archive nothing")
    assertTrue(results.isFile && results.length() == 0L, "the passing benchmark's score must not reach the results file:\n" + results.readText())

    // A fork that dies before measuring: no <failure> line, only the dead fork's exit code.
    val forkDied = runner(*jmhRun("OkBench"), "-PjmhJvmArgsAppend=-XX:+NoSuchFlagForProbe").buildAndFail()
    assertJmhFailed(forkDied)
    assertTrue(forkDied.output.contains("<forked VM failed with exit code"), forkDied.output)
    assertFalse(forkDied.output.contains("<failure>"), forkDied.output)
    assertFalse(archiveDir.exists(), "a failed run must archive nothing")

    // An iteration @Setup that throws after two measurement iterations posted numbers: a
    // silent harness would have posted a short row (Cnt 2 of 4) and passed.
    val lateFailed = runner(*jmhRun("LateFailBench", iterations = 4), smallHeap).buildAndFail()
    assertJmhFailed(lateFailed)
    assertTrue(lateFailed.output.contains("LateFailBench: fails in iteration 3"), lateFailed.output)
    assertTrue(Regex("Iteration\\s+2:\\s+[0-9]").containsMatchIn(lateFailed.output), "two iterations should have posted numbers first:\n" + lateFailed.output)
    assertTrue(Regex("Iteration\\s+3:\\s+<failure>").containsMatchIn(lateFailed.output), lateFailed.output)
    assertFalse(archiveDir.exists(), "a failed run must archive nothing")
  }

  @Test
  fun `the archive holds exactly the runs that wrote results, under chronological names`() {
    // The default JVM flag list (compact object headers, generational ZGC, a pre-touched 2g
    // heap) boots the fork on this JDK, and the run's row reaches the merged scoreboard.
    val passed = runner(*jmhRun("OkBench")).build()
    assertEquals(TaskOutcome.SUCCESS, passed.task(":jmh")?.outcome, passed.output)
    assertTrue(passed.output.contains("JMH results merged from 1 archived runs"), passed.output)
    assertEquals(1, archivedRuns().size, archivedRuns().toString())
    // JMH prints benchmark names without the package every benchmark shares.
    val okRow = results.readLines().singleOrNull { it.startsWith("OkBench.probe") }
    assertTrue(okRow != null && okRow.contains("thrpt"), "merged scoreboard lacks the OkBench row:\n" + results.readText())
    val scoreboard = results.readText()

    // Plugin 0.7.4's --jmhArgs reaches JMH's list flags, which end the run before JMH writes
    // results: the task passes, and the previous scoreboard must not be archived as a run.
    val listed = runner("jmh", "--jmhArgs=-l").build()
    assertEquals(TaskOutcome.SUCCESS, listed.task(":jmh")?.outcome, listed.output)
    assertTrue(listed.output.contains("probe.OkBench.probe"), listed.output)
    assertFalse(listed.output.contains("JMH results merged"), listed.output)
    assertEquals(1, archivedRuns().size, "a listing run must archive nothing: " + archivedRuns())
    assertEquals(scoreboard, results.readText())

    // A failed run under the default fail-on-error archives nothing, and the scoreboard keeps
    // its rows.
    val failed = runner(*jmhRun("SetupFailBench"), smallHeap).buildAndFail()
    assertJmhFailed(failed)
    assertEquals(1, archivedRuns().size, "a failed run must archive nothing: " + archivedRuns())
    assertEquals(scoreboard, results.readText())

    // Archive names must stay chronological whatever the clock does: with an archive named an
    // hour ahead in place, the next run takes the second after it rather than its own second.
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    val ahead = LocalDateTime.now().plusHours(1).withNano(0)
    File(archiveDir, "results-${ahead.format(stamp)}.txt").writeText("Benchmark  Mode  Cnt  Score   Error  Units\n")

    // With fail-on-error switched off for the run, the same erroring benchmark lets the task
    // pass, as it always did under plugin 0.7.3: JMH reports the failure and writes a results
    // file with no row, which is archived, and the merge keeps the earlier row.
    val tolerated = runner(*jmhRun("SetupFailBench"), smallHeap, "-PjmhFailOnError=false").build()
    assertEquals(TaskOutcome.SUCCESS, tolerated.task(":jmh")?.outcome, tolerated.output)
    assertTrue(tolerated.output.contains("<failure>"), tolerated.output)
    assertFalse(tolerated.output.contains("fail on error was requested"), tolerated.output)
    assertTrue(tolerated.output.contains("JMH results merged from 3 archived runs"), tolerated.output)
    val runs = archivedRuns()
    assertEquals(3, runs.size, runs.toString())
    assertEquals("results-${ahead.plusSeconds(1).format(stamp)}.txt", runs.last().name, runs.toString())
    assertTrue(runs.last().readLines().none { it.contains("thrpt") }, "the erroring run's archive must hold no row:\n" + runs.last().readText())
    assertEquals(okRow, results.readLines().singleOrNull { it.startsWith("OkBench.probe") }, results.readText())

    // With fail-on-error off, a benchmark that fails after two measurement iterations posted
    // numbers passes the task with a short row (Cnt 2 of the 4 requested), which the merge
    // takes as the newest row: nothing on the scoreboard marks it short.
    val lateTolerated = runner(*jmhRun("LateFailBench", iterations = 4), smallHeap, "-PjmhFailOnError=false").build()
    assertEquals(TaskOutcome.SUCCESS, lateTolerated.task(":jmh")?.outcome, lateTolerated.output)
    assertTrue(Regex("Iteration\\s+3:\\s+<failure>").containsMatchIn(lateTolerated.output), lateTolerated.output)
    assertTrue(lateTolerated.output.contains("JMH results merged from 4 archived runs"), lateTolerated.output)
    val fourRuns = archivedRuns()
    assertEquals(4, fourRuns.size, fourRuns.toString())
    assertEquals("results-${ahead.plusSeconds(2).format(stamp)}.txt", fourRuns.last().name, fourRuns.toString())
    val shortRow = Regex("LateFailBench\\.probe\\s+thrpt\\s+2\\s+\\S+")
    assertTrue(shortRow.containsMatchIn(fourRuns.last().readText()), "the archive must hold the short row (Cnt 2 of 4):\n" + fourRuns.last().readText())
    assertTrue(shortRow.containsMatchIn(results.readText()), "the short row must reach the scoreboard:\n" + results.readText())
    // The merge re-pads every column to the widest name, so compare the row's tokens.
    val tokens = { row: String? -> row?.split(Regex("\\s+")) }
    assertEquals(tokens(okRow), tokens(results.readLines().singleOrNull { it.startsWith("OkBench.probe") }), results.readText())
  }

  @Test
  fun `the benchmark classpath resolves the default harness version, overridable`() {
    val byDefault = runner("printJmhCore").build()
    assertTrue(byDefault.output.contains("JMH_CORE=jmh-core-1.37.jar"), byDefault.output)

    val overridden = runner("printJmhCore", "-PjmhVersion=1.36").build()
    assertTrue(overridden.output.contains("JMH_CORE=jmh-core-1.36.jar"), overridden.output)
  }
}
