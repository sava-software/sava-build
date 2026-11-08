import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.jmh.JmhPluginRequirement
import software.sava.build.jmh.JmhToolDefaults
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Runs JMH for real through 'software.sava.build.feature.jmh'. Under the convention's default
 * failOnError, a benchmark that errors must fail the 'jmh' task once the run is over, whichever
 * way it errors: a trial @Setup that throws, a fork that dies before it measures anything, and
 * an iteration @Setup that throws after measurements were posted, which posts a short row that
 * must stay off the scoreboard while the passing benchmarks' rows reach it. The archive under
 * jmh-results/ must hold exactly the runs that wrote a row, under names that stay chronological,
 * and the merged scoreboard must keep JMH's parameter columns. The harness version the
 * convention holds must be the one the benchmark classpath resolves, and every graph here runs
 * with --warning-mode=fail, so a deprecation anywhere in the feature's graph fails the build.
 *
 * jmh-gradle-plugin 0.7.3 and earlier passed the plugin's failOnError to JMH as '-foe 1', which
 * JMH reads as false; 0.7.4 passes 'true', which -PjmhFailFast=true relies on. The runs here hold
 * that through any later plugin bump: the fixture declares the plugin with the declaration the
 * feature prints, at the version the feature is compiled against, and the feature applies it, the
 * path a benchmark build takes, so every run here goes through the feature's own application.
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
        import java.util.TimeZone

        plugins {
          java
          // The declaration the feature prints: the plugin is applied by the feature, as in a benchmark build.
          ${JmhPluginRequirement.declaration()}
          id("software.sava.build.feature.jmh")
        }

        repositories {
          mavenCentral()
        }

        // -PfixtureResultsFile=<path under build/> moves the task's results file, as a build's jmh block may.
        providers.gradleProperty("fixtureResultsFile").orNull?.let { path ->
          (extensions.getByName("jmh") as me.champeau.jmh.JmhParameters).resultsFile.set(layout.buildDirectory.file(path))
        }

        // -PfixtureZone=<id> runs the jmh task with that zone as the build JVM's default (a daemon
        // keeps its own whatever the JVM arguments say), so a stamp taken in local time shows;
        // the default is put back once the task is done. The actions capture the zone alone:
        // a script property would drag the script object into the configuration cache.
        providers.gradleProperty("fixtureZone").orNull?.let { zone ->
          tasks.named("jmh") {
            doFirst {
              TimeZone.setDefault(TimeZone.getTimeZone(zone))
              println("JMH_ZONE=" + TimeZone.getDefault().id)
            }
            doLast {
              TimeZone.setDefault(null)
            }
          }
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
    File(benchmarks, "ParamBench.java").writeText(
      """
        package probe;

        import org.openjdk.jmh.annotations.Benchmark;
        import org.openjdk.jmh.annotations.Param;
        import org.openjdk.jmh.annotations.Scope;
        import org.openjdk.jmh.annotations.Setup;
        import org.openjdk.jmh.annotations.State;

        // Two parameter values, of which one fails its trial @Setup.
        @State(Scope.Benchmark)
        public class ParamBench {
          @Param({"alpha", "beta"})
          public String kind;

          @Setup
          public void setup() {
            if ("beta".equals(kind)) {
              throw new IllegalStateException("ParamBench: beta disagrees");
            }
          }

          @Benchmark
          public int probe() {
            return kind.length();
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

  private val output: File get() = File(fixtureDir, "build/results/jmh/output.txt")

  private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  /** The stamped archives of [name]'s series in [extension], in name order: the files that file's merge reads. */
  private fun archivedRuns(name: String = "results", extension: String = "txt"): List<File> {
    val archived = Regex(Regex.escape("$name-") + """\d{8}-\d{6}""" + Regex.escape(".$extension"))
    return (archiveDir.listFiles() ?: emptyArray()).filter { archived.matches(it.name) }.sortedBy { it.name }
  }

  private val failedAtEnd = "so the jmh task fails now that the run is over"

  /** The task failed once the run was over, from the feature and not from JMH's own stop, naming the attempt. */
  private fun assertFailedAtEnd(result: BuildResult, attempt: String) {
    assertEquals(TaskOutcome.FAILED, result.task(":jmh")?.outcome, result.output)
    assertTrue(result.output.contains(failedAtEnd), result.output)
    assertTrue(result.output.contains(attempt), "expected '$attempt' in:\n" + result.output)
    assertFalse(result.output.contains("fail on error was requested"), result.output)
  }

  private fun rowOf(name: String, vararg params: String): Regex =
    Regex("^" + Regex.escape(name) + params.joinToString(separator = "") { "\\s+" + Regex.escape(it) } + "\\s+thrpt\\s", RegexOption.MULTILINE)

  @Test
  fun `a benchmark that errors fails the jmh task at the end of the run, however it errors`() {
    // Before any archive exists: JMH's verbosity SILENT prints no benchmark section, so the run's
    // failures cannot be read. The task fails, nothing is archived, and results.txt keeps no row
    // of JMH's table, the late failure's short one included.
    val silent = runner(*jmhRun("OkBench,LateFailBench", iterations = 4), smallHeap, "--jmhArgs=-v SILENT").buildAndFail()
    assertEquals(TaskOutcome.FAILED, silent.task(":jmh")?.outcome, silent.output)
    assertTrue(silent.output.contains("the feature cannot tell which benchmarks failed"), silent.output)
    assertFalse(archiveDir.exists(), "a run whose report cannot be read archives nothing")
    assertTrue(results.readLines().none { it.contains("thrpt") }, "no row may survive a run the feature could not check:\n" + results.readText())

    // A trial @Setup that throws: JMH, its own fail-on-error off, reports the failure and goes
    // on; the feature fails the task once the run is over, and nothing is archived since no row
    // survived (JMH wrote the table's header and no row).
    val setupFailed = runner(*jmhRun("SetupFailBench"), smallHeap).buildAndFail()
    assertFailedAtEnd(setupFailed, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    assertTrue(setupFailed.output.contains("<failure>"), setupFailed.output)
    assertTrue(setupFailed.output.contains("SetupFailBench: variants disagree"), setupFailed.output)
    assertFalse(archiveDir.exists(), "a run without a surviving row must archive nothing")
    assertTrue(output.readText().contains("SetupFailBench: variants disagree"), "the run's output must be saved:\n" + output.readText())

    // Under the convention's warmup iterations a trial @Setup throws in warmup iteration 1,
    // which JMH prints as '# Warmup Iteration   1: <failure>'; the feature reads that too.
    val warmFailed = runner("jmh", "-PjmhIncludes=SetupFailBench", "-PjmhWarmupIterations=1", "-PjmhIterations=1", "-PjmhTimeOnIteration=100ms", "-PjmhFork=1", smallHeap).buildAndFail()
    assertFailedAtEnd(warmFailed, "probe.SetupFailBench.probe [thrpt]: failed in warmup iteration 1; it posted no row")
    assertTrue(warmFailed.output.contains("# Warmup Iteration   1: <failure>"), warmFailed.output)
    assertFalse(archiveDir.exists(), "a run without a surviving row must archive nothing")

    // A passing benchmark ahead of the erroring one: both run, the task fails at the end, the
    // passing benchmark's row is archived and on the scoreboard, the failed one posted none.
    val mixed = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap).buildAndFail()
    assertFailedAtEnd(mixed, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    assertTrue(mixed.output.contains("Result \"probe.OkBench.probe\":"), mixed.output)
    assertTrue(mixed.output.contains("JMH results merged from 1 archived runs"), mixed.output)
    val mixedArchive = archivedRuns().single().readText()
    assertTrue(rowOf("OkBench.probe").containsMatchIn(mixedArchive), mixedArchive)
    assertFalse(mixedArchive.contains("SetupFailBench"), mixedArchive)
    assertTrue(rowOf("OkBench.probe").containsMatchIn(results.readText()), results.readText())

    // A fork that dies before measuring: no <failure> line, only the dead fork's exit code.
    val forkDied = runner(*jmhRun("OkBench"), "-PjmhJvmArgsAppend=-XX:+NoSuchFlagForProbe").buildAndFail()
    assertFailedAtEnd(forkDied, "probe.OkBench.probe [thrpt]: forked VM failed with exit code 1; it posted no row")
    assertTrue(forkDied.output.contains("<forked VM failed with exit code"), forkDied.output)
    assertFalse(forkDied.output.contains("<failure>"), forkDied.output)
    assertEquals(1, archivedRuns().size, "a run without a surviving row must archive nothing: " + archivedRuns())

    // An iteration @Setup that throws after two measurement iterations posted numbers: JMH prints
    // a Result and posts a short row (Cnt 2 of 4), which the feature leaves out of the archive
    // while the passing benchmark's row goes in; the task fails at the end.
    val lateFailed = runner(*jmhRun("OkBench,LateFailBench", iterations = 4), smallHeap).buildAndFail()
    assertFailedAtEnd(lateFailed, "probe.LateFailBench.probe [thrpt]: failed in iteration 3; its row is left out of the archive")
    assertTrue(Regex("Iteration\\s+2:\\s+[0-9]").containsMatchIn(lateFailed.output), "two iterations should have posted numbers first:\n" + lateFailed.output)
    assertTrue(lateFailed.output.contains("Result \"probe.LateFailBench.probe\":"), lateFailed.output)
    assertEquals(2, archivedRuns().size, archivedRuns().toString())
    val lateArchive = archivedRuns().last().readText()
    assertTrue(rowOf("OkBench.probe").containsMatchIn(lateArchive), lateArchive)
    assertFalse(lateArchive.contains("LateFailBench"), lateArchive)
    assertFalse(results.readText().contains("LateFailBench"), results.readText())

    // -PjmhFailFast=true asks JMH itself to stop at the first error: the task fails in its
    // action, so the passing benchmark's score survives in the console alone and nothing is
    // archived or saved.
    val fast = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap, "-PjmhFailFast=true").buildAndFail()
    assertEquals(TaskOutcome.FAILED, fast.task(":jmh")?.outcome, fast.output)
    assertTrue(fast.output.contains("fail on error was requested"), fast.output)
    assertTrue(fast.output.contains("Result \"probe.OkBench.probe\":"), fast.output)
    assertFalse(fast.output.contains(failedAtEnd), fast.output)
    assertEquals(2, archivedRuns().size, "a fail-fast run archives nothing: " + archivedRuns())
  }

  @Test
  fun `the archive holds exactly the runs that wrote a row, under chronological names, with the parameter columns`() {
    // The default JVM flag list (compact object headers, generational ZGC, a pre-touched 2g
    // heap) boots the fork on this JDK, and the run's row reaches the merged scoreboard. The
    // build JVM runs this task fourteen hours off UTC, so the UTC check below has teeth.
    val passed = runner(*jmhRun("OkBench"), "-PfixtureZone=Etc/GMT-14").build()
    assertEquals(TaskOutcome.SUCCESS, passed.task(":jmh")?.outcome, passed.output)
    assertTrue(passed.output.contains("JMH_ZONE=Etc/GMT-14"), "the fixture's zone did not take in the build JVM:\n" + passed.output)
    assertTrue(passed.output.contains("JMH results merged from 1 archived runs"), passed.output)
    assertEquals(1, archivedRuns().size, archivedRuns().toString())
    // The stamp is UTC: within minutes of the UTC clock, where the zone the run just had would
    // put a local-time stamp fourteen hours out.
    val firstStamp = LocalDateTime.parse(archivedRuns().single().name.removePrefix("results-").removeSuffix(".txt"), stamp)
    assertTrue(Duration.between(firstStamp, LocalDateTime.now(ZoneOffset.UTC)).abs() < Duration.ofMinutes(10), "a stamp off UTC: " + archivedRuns())
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

    // A run whose every attempt failed archives nothing, and the scoreboard is re-rendered from
    // the archive, not left as the emptied table JMH wrote.
    val failed = runner(*jmhRun("SetupFailBench"), smallHeap).buildAndFail()
    assertFailedAtEnd(failed, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    assertEquals(1, archivedRuns().size, "a run without a surviving row must archive nothing: " + archivedRuns())
    assertEquals(scoreboard, results.readText())

    // A SILENT run whose every benchmark failed writes a table with no row and no report to
    // read: it is refused like any unreadable run, a warning under -PjmhFailOnError=false,
    // and the scoreboard stands.
    val silentFailed = runner(*jmhRun("SetupFailBench"), smallHeap, "--jmhArgs=-v SILENT").buildAndFail()
    assertEquals(TaskOutcome.FAILED, silentFailed.task(":jmh")?.outcome, silentFailed.output)
    assertTrue(silentFailed.output.contains("the feature cannot tell which benchmarks failed"), silentFailed.output)
    val silentTolerated = runner(*jmhRun("SetupFailBench"), smallHeap, "--jmhArgs=-v SILENT", "-PjmhFailOnError=false").build()
    assertEquals(TaskOutcome.SUCCESS, silentTolerated.task(":jmh")?.outcome, silentTolerated.output)
    assertTrue(silentTolerated.output.contains("the feature cannot tell which benchmarks failed"), silentTolerated.output)
    assertEquals(1, archivedRuns().size, "an unreadable run must archive nothing: " + archivedRuns())
    assertEquals(scoreboard, results.readText())

    // Archive names must stay chronological whatever the clock does: with an archive named an
    // hour ahead in place, the next run takes the second after it rather than its own second.
    val ahead = LocalDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0)
    File(archiveDir, "results-${ahead.format(stamp)}.txt").writeText("Benchmark  Mode  Cnt  Score   Error  Units\n")
    // A hand-kept table beside the archives, named after the results file but not stamped, is not
    // an archive: the merge leaves it out, where it would sort after every stamp and win.
    val handKept = File(archiveDir, "results-baseline.txt")
    handKept.writeText("Benchmark      Mode  Cnt      Score   Error  Units\nOkBench.probe  thrpt    8  999999.000 ± 0.001  ops/s\n")

    // With fail-on-error off for the run, the same erroring benchmark beside a passing one lets
    // the task pass with a warning naming it: the passing row is archived, the failed one
    // posted none, and the merge keeps the earlier row.
    val tolerated = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap, "-PjmhFailOnError=false").build()
    assertEquals(TaskOutcome.SUCCESS, tolerated.task(":jmh")?.outcome, tolerated.output)
    assertTrue(tolerated.output.contains("<failure>"), tolerated.output)
    assertTrue(tolerated.output.contains("JMH: 1 of 2 benchmark runs failed, and the jmh task passes under -PjmhFailOnError=false"), tolerated.output)
    assertTrue(tolerated.output.contains("probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row"), tolerated.output)
    assertFalse(tolerated.output.contains(failedAtEnd), tolerated.output)
    assertFalse(tolerated.output.contains("fail on error was requested"), tolerated.output)
    assertTrue(tolerated.output.contains("JMH results merged from 3 archived runs"), tolerated.output)
    val runs = archivedRuns()
    assertEquals(3, runs.size, runs.toString())
    assertEquals("results-${ahead.plusSeconds(1).format(stamp)}.txt", runs.last().name, runs.toString())
    assertTrue(rowOf("OkBench.probe").containsMatchIn(runs.last().readText()), runs.last().readText())
    assertFalse(runs.last().readText().contains("SetupFailBench"), runs.last().readText())
    assertFalse(results.readText().contains("999999"), "the hand-kept table reached the scoreboard:\n" + results.readText())
    assertTrue(handKept.isFile, "the hand-kept table must be left alone")

    // A parameterised benchmark with one failing value, tolerated: its passing row is archived
    // under JMH's '(kind)' column, the failing value is named, and the merged scoreboard keeps
    // the column, N/A for the rows that lack it, under the next chronological name.
    val params = runner(*jmhRun("ParamBench"), smallHeap, "-PjmhFailOnError=false").build()
    assertEquals(TaskOutcome.SUCCESS, params.task(":jmh")?.outcome, params.output)
    assertTrue(params.output.contains("probe.ParamBench.probe (kind = beta) [thrpt]: failed in iteration 1; it posted no row"), params.output)
    val fourRuns = archivedRuns()
    assertEquals(4, fourRuns.size, fourRuns.toString())
    assertEquals("results-${ahead.plusSeconds(2).format(stamp)}.txt", fourRuns.last().name, fourRuns.toString())
    val paramArchive = fourRuns.last().readText()
    assertTrue(Regex("^Benchmark\\s+\\(kind\\)\\s+Mode").containsMatchIn(paramArchive), paramArchive)
    assertTrue(rowOf("ParamBench.probe", "alpha").containsMatchIn(paramArchive), paramArchive)
    assertFalse(paramArchive.contains("beta"), paramArchive)
    val merged = results.readText()
    assertTrue(Regex("^Benchmark\\s+\\(kind\\)\\s+Mode").containsMatchIn(merged), merged)
    assertTrue(rowOf("OkBench.probe", "N/A").containsMatchIn(merged), merged)
    assertTrue(rowOf("ParamBench.probe", "alpha").containsMatchIn(merged), merged)
    assertFalse(merged.contains("beta"), merged)
  }

  @Test
  fun `a run --jmhArgs sends elsewhere is checked but not archived, and another format into the task's file archives whole`() {
    // -rff elsewhere, with -o, both relative (JMH's, against the project directory): JMH writes the
    // table and the report there and prints nothing. The failing benchmark still fails the task at
    // the end, the report is saved beside the task's results file, and the table is left where it
    // went, as JMH wrote it: neither archived nor merged, the task's own file untouched. JMH
    // creates neither file's directory, so the build that redirects them does.
    val elsewhere = File(fixtureDir, "elsewhere/bench.txt").apply { parentFile.mkdirs() }
    val report = File(fixtureDir, "elsewhere/report.txt")
    val redirected = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap, "--jmhArgs=-rff elsewhere/bench.txt -o elsewhere/report.txt").buildAndFail()
    assertFailedAtEnd(redirected, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    assertTrue(report.isFile) { "JMH's report must have gone to -o: " + (report.parentFile.listFiles() ?: emptyArray()).map { it.name } }
    assertTrue(report.readText().contains("<failure>")) { "JMH's report went to -o: " + report.readText() }
    assertEquals(report.readText(), output.readText(), "the report is saved beside the task's results file")
    assertFalse(results.exists(), "the task's own results file must not be written by a redirected run")
    assertTrue(rowOf("OkBench.probe").containsMatchIn(elsewhere.readText()), "the table is left as JMH wrote it: " + elsewhere.readText())
    assertFalse(redirected.output.contains("JMH results merged"), redirected.output)
    assertEquals(emptyList<String>(), (archiveDir.listFiles() ?: emptyArray()).map { it.name }, "a redirected run is not archived")

    // A redirected run with a late failure: the short row stays in the table where -rff sent it,
    // and the failure says so.
    val redirectedLate = runner(*jmhRun("OkBench,LateFailBench", iterations = 4), smallHeap, "--jmhArgs=-rff elsewhere/late.txt").buildAndFail()
    assertFailedAtEnd(redirectedLate, "probe.LateFailBench.probe [thrpt]: failed in iteration 3; its row stays in the table, which is left as JMH wrote it where -rff sent it")
    assertTrue(File(fixtureDir, "elsewhere/late.txt").readText().contains("LateFailBench"), "the redirected table is left as JMH wrote it")

    // -rff /dev/null discards the table: no file is rewritten, yet the run is a run, so its
    // failures are reported from the report; a run into something that is not a regular file is
    // checked whatever its flags say (JMH may read them otherwise than the feature), so a SILENT
    // run and a listing run there are both refused as unreadable.
    val discarded = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap, "--jmhArgs=-rff /dev/null").buildAndFail()
    assertFailedAtEnd(discarded, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    val discardedSilent = runner(*jmhRun("SetupFailBench"), smallHeap, "--jmhArgs=-rff /dev/null -v SILENT").buildAndFail()
    assertEquals(TaskOutcome.FAILED, discardedSilent.task(":jmh")?.outcome, discardedSilent.output)
    assertTrue(discardedSilent.output.contains("the feature cannot tell which benchmarks failed"), discardedSilent.output)
    val discardedListing = runner("jmh", "--jmhArgs=-rff /dev/null -l").buildAndFail()
    assertEquals(TaskOutcome.FAILED, discardedListing.task(":jmh")?.outcome, discardedListing.output)
    assertTrue(discardedListing.output.contains("a listing or help run into a results file that is not a regular file"), discardedListing.output)
    // A value-taking option without its value swallows the token after it: here JMH reads '-e -o'
    // as an exclude regex and prints the report to the console, while the feature's reading of
    // --jmhArgs names the -o file of an earlier run. That file was not rewritten, so the console,
    // and the failure in it, decide the run.
    val reported = runner(*jmhRun("OkBench"), smallHeap, "--jmhArgs=-rff /dev/null -o elsewhere/rep.txt").build()
    assertEquals(TaskOutcome.SUCCESS, reported.task(":jmh")?.outcome, reported.output)
    assertTrue(File(fixtureDir, "elsewhere/rep.txt").readText().contains("OkBench"), "the first run's report went to -o")
    val staleReport = runner(*jmhRun("OkBench,SetupFailBench"), smallHeap, "--jmhArgs=-rff /dev/null -e -o elsewhere/rep.txt").buildAndFail()
    assertFailedAtEnd(staleReport, "probe.SetupFailBench.probe [thrpt]: failed in iteration 1; it posted no row")
    assertFalse(File(fixtureDir, "elsewhere/rep.txt").readText().contains("SetupFailBench"), "the earlier report was left alone")
    assertFalse(results.exists(), "the task's own results file must not be written by a discarded run")
    assertEquals(0, archivedRuns().size, "a discarded run is not archived: " + archivedRuns())

    // A results file or a report under jmh-results/ would be taken for an archive: refused before JMH runs.
    for (named in listOf("-rff jmh-results/results-20261108-120000.txt", "-o jmh-results/results-20990101-000000.txt")) {
      val intoArchives = runner(*jmhRun("OkBench"), smallHeap, "--jmhArgs=$named").buildAndFail()
      assertEquals(TaskOutcome.FAILED, intoArchives.task(":jmh")?.outcome, intoArchives.output)
      assertTrue(intoArchives.output.contains("which holds the jmh task's archives: send it elsewhere"), intoArchives.output)
      assertFalse(intoArchives.output.contains("# JMH version"), "JMH must not have run:\n" + intoArchives.output)
    }
    assertFalse(File(archiveDir, "results-20261108-120000.txt").exists() || File(archiveDir, "results-20990101-000000.txt").exists(), "nothing may be written under jmh-results/")

    // -rff naming the task's own results file, by another path, is no redirection.
    val explicit = runner(*jmhRun("OkBench"), smallHeap, "--jmhArgs=-rff build/results/jmh/results.txt").build()
    assertEquals(TaskOutcome.SUCCESS, explicit.task(":jmh")?.outcome, explicit.output)
    assertTrue(explicit.output.contains("JMH results merged from 1 archived runs"), explicit.output)
    assertEquals(1, archivedRuns().size, archivedRuns().toString())
    // Nor is a hard link to it (one inode, two paths: the same file by Files.isSameFile, not by path).
    val hardLink = File(fixtureDir, "elsewhere/hard.txt")
    java.nio.file.Files.createLink(hardLink.toPath(), results.toPath())
    val linked = runner(*jmhRun("OkBench"), smallHeap, "--jmhArgs=-rff elsewhere/hard.txt").build()
    assertEquals(TaskOutcome.SUCCESS, linked.task(":jmh")?.outcome, linked.output)
    assertTrue(linked.output.contains("JMH results merged from 2 archived runs"), linked.output)
    assertEquals(2, archivedRuns().size, archivedRuns().toString())
    assertEquals(results.readText(), hardLink.readText())

    // -rf json into the task's results file: another format, archived whole under the file's whole
    // name and the format's extension, the file left as JMH wrote it, and no text scoreboard
    // rendered over it.
    val json = runner(*jmhRun("OkBench"), smallHeap, "--jmhArgs=-rf json").build()
    assertEquals(TaskOutcome.SUCCESS, json.task(":jmh")?.outcome, json.output)
    assertTrue(results.readText().trimStart().startsWith("["), "results.txt holds JMH's JSON: " + results.readText().take(80))
    val jsonArchives = archivedRuns(name = "results.txt", extension = "json")
    assertEquals(1, jsonArchives.size, (archiveDir.listFiles() ?: emptyArray()).map { it.name }.toString())
    assertEquals(results.readText(), jsonArchives.single().readText())
    assertFalse(json.output.contains("JMH results merged"), json.output)
    assertEquals(2, archivedRuns().size, "no text archive from a JSON run: " + archivedRuns())
    // A late failure in another format: the run is archived whole, its short entry included, and the failure says so.
    val jsonLate = runner(*jmhRun("OkBench,LateFailBench", iterations = 4), smallHeap, "--jmhArgs=-rf json").buildAndFail()
    assertFailedAtEnd(jsonLate, "probe.LateFailBench.probe [thrpt]: failed in iteration 3; its row stays in the archive (only the text result format is filtered)")
    assertEquals(2, archivedRuns(name = "results.txt", extension = "json").size, (archiveDir.listFiles() ?: emptyArray()).map { it.name }.toString())

    // Another results file in the text format, results.log, has a series of its own under its whole
    // name: its scoreboard merges that series alone, and results.txt's series stands untouched.
    val log = File(fixtureDir, "build/results/jmh/results.log")
    val moved = runner(*jmhRun("OkBench,ParamBench"), smallHeap, "-PjmhFailOnError=false", "-PfixtureResultsFile=results/jmh/results.log").build()
    assertEquals(TaskOutcome.SUCCESS, moved.task(":jmh")?.outcome, moved.output)
    assertTrue(moved.output.contains("JMH results merged from 1 archived runs into ${log.canonicalPath}"), moved.output)
    assertEquals(1, archivedRuns(name = "results.log").size, (archiveDir.listFiles() ?: emptyArray()).map { it.name }.toString())
    assertTrue(rowOf("ParamBench.probe", "alpha").containsMatchIn(log.readText()), log.readText())
    assertFalse(results.readText().contains("ParamBench"), "results.txt must not take results.log's rows: " + results.readText())
    assertEquals(2, archivedRuns().size, "results.txt's series must stand: " + archivedRuns())
    // Nor does results.log.txt share results.log's series: its extension is the format's, but the
    // rest of its name holds a dot, so the whole name is kept.
    val logTxt = File(fixtureDir, "build/results/jmh/results.log.txt")
    val movedAgain = runner(*jmhRun("OkBench"), smallHeap, "-PfixtureResultsFile=results/jmh/results.log.txt").build()
    assertEquals(TaskOutcome.SUCCESS, movedAgain.task(":jmh")?.outcome, movedAgain.output)
    assertTrue(movedAgain.output.contains("JMH results merged from 1 archived runs into ${logTxt.canonicalPath}"), movedAgain.output)
    assertEquals(1, archivedRuns(name = "results.log.txt").size, (archiveDir.listFiles() ?: emptyArray()).map { it.name }.toString())
    assertFalse(logTxt.readText().contains("ParamBench"), "results.log.txt must not take results.log's rows: " + logTxt.readText())
    assertEquals(1, archivedRuns(name = "results.log").size, "results.log's series must stand: " + (archiveDir.listFiles() ?: emptyArray()).map { it.name })
    // Nor does results.TXT share results.txt's: another file where the file system tells case apart,
    // so the extension counts as the format's only in the plugin's own spelling.
    val upper = runner(*jmhRun("OkBench"), smallHeap, "-PfixtureResultsFile=results/jmh/results.TXT").build()
    assertEquals(TaskOutcome.SUCCESS, upper.task(":jmh")?.outcome, upper.output)
    assertEquals(1, archivedRuns(name = "results.TXT").size, (archiveDir.listFiles() ?: emptyArray()).map { it.name }.toString())
    assertEquals(2, archivedRuns().size, "results.txt's series must stand: " + archivedRuns())
  }

  @Test
  fun `the benchmark classpath resolves the default harness version, overridable`() {
    val byDefault = runner("printJmhCore").build()
    assertTrue(byDefault.output.contains("JMH_CORE=jmh-core-${JmhToolDefaults.JMH}.jar"), byDefault.output)

    val overridden = runner("printJmhCore", "-PjmhVersion=1.36").build()
    assertTrue(overridden.output.contains("JMH_CORE=jmh-core-1.36.jar"), overridden.output)
  }
}
