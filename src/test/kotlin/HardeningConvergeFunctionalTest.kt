import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
import java.io.File

/**
 * Functional test for 'pitestConverge' / 'pitestConvergeSnapshot' and
 * 'pitestMutatorTrial': fabricates PIT reports and excludes the real PIT executions
 * with '-x' (unlike pitestModeSnapshot, these aggregates hard-depend on the run
 * tasks), so the snapshot/diff/tabulation logic runs against known statuses without
 * resolving or running PIT.
 */
class HardeningConvergeFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private val mathMutator = "org.pitest.mutationtest.engine.gregor.mutators.MathMutator"
  private val boundaryMutator = "org.pitest.mutationtest.engine.gregor.mutators.ConditionalsBoundaryMutator"

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-converge-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }

        hardening {
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec")
            targetTests = "com.example.*Test*"
          }
          mutation.register("parsing") {
            targetClasses = listOf("com.example.Parser")
            targetTests = "com.example.*Test*"
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun writeReport(reportDirName: String, vararg csvRows: String) {
    val reportDir = File(fixtureDir, "build/reports/pitest/$reportDirName")
    reportDir.mkdirs()
    reportDir.resolve("mutations.csv").writeText(
      if (csvRows.isEmpty()) "" else csvRows.joinToString("\n", postfix = "\n")
    )
  }

  private fun writeEvidence(
    reportDirName: String,
    scope: String = PitestEvidence.FULL_SCOPE,
    historyAssisted: Boolean = false,
  ) {
    val report = File(fixtureDir, "build/reports/pitest/$reportDirName/mutations.csv")
    report.parentFile.resolve(".evidence.tsv").writeText(
      PitestEvidence(
        suite = reportDirName,
        invocationId = "fixture-invocation",
        pitestVersion = "fixture-pit",
        junitPluginVersion = "fixture-junit",
        pluginSha256 = "fixture-plugin",
        identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
        javaVersion = "fixture-java",
        sourceSha256 = "fixture-source",
        classesSha256 = "fixture-classes",
        classpathSha256 = "fixture-classpath",
        toolClasspathSha256 = "fixture-tool-classpath",
        configurationSha256 = "fixture-configuration",
        reportSha256 = PitestEvidence.sha256(report),
        scope = scope,
        historyAssisted = historyAssisted,
      ).render()
    )
  }

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--stacktrace")

  private fun snapshotRun() = runner(
    "pitestConvergeSnapshot", "-x", "pitestEncoding", "-x", "pitestParsing"
  )

  private fun convergeRun() = runner(
    "pitestConverge", "-x", "pitestConvergeSnapshot",
    "-x", "pitestEncodingConvergeRound2", "-x", "pitestParsingConvergeRound2"
  )

  @Test
  fun `converge snapshots round one, then classifies benign and boundary flips`() {
    writeFixture()
    // round one: two sibling mutants share the encode coordinate with split statuses
    writeReport(
      "encoding",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,KILLED,none",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$boundaryMutator,decode,30,KILLED,com.example.CodecTest"
    )
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")

    val snapshot = snapshotRun().build()
    assertTrue(snapshot.output.contains("snapshotted 2 round-one report(s)"), snapshot.output)
    assertTrue(File(fixtureDir, "build/pitest-converge/round1/encoding.csv").isFile, "round-one stash missing")
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding").exists(), "round-one reports must be cleared")

    // round two, same statuses — the sibling rows swap CSV order, which only the
    // sorted-multiset comparison reads as unchanged
    writeReport(
      "encoding",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,KILLED,none",
      "Codec.java,com.example.Codec,$boundaryMutator,decode,30,KILLED,com.example.CodecTest"
    )
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    val converged = convergeRun().build()
    assertTrue(
      converged.output.contains("2 suite(s) converged — zero per-mutant status flips"),
      converged.output
    )

    // KILLED -> TIMED_OUT stays on the detected side: reported, but it cannot move
    // the ratchet, so the build passes
    writeReport(
      "encoding",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,KILLED,none",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$boundaryMutator,decode,30,TIMED_OUT,none"
    )
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    val benign = convergeRun().build()
    assertTrue(
      benign.output.contains("1 flip(s), none crossing the unkilled boundary"),
      benign.output
    )
    assertTrue(
      benign.output.contains("com.example.Codec,decode,30,ConditionalsBoundaryMutator — KILLED -> TIMED_OUT"),
      "per-mutant flip line missing:\n" + benign.output
    )

    // Gated presence stays true while the gated MULTISET grows: the killed sibling
    // now survives beside the always-surviving one. A presence-only comparison called
    // this benign even though the ratchet needs a second accepted row.
    writeReport(
      "encoding",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$boundaryMutator,decode,30,KILLED,com.example.CodecTest"
    )
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    val siblingGrowth = convergeRun().buildAndFail()
    assertTrue(
      siblingGrowth.output.contains("KILLED/SURVIVED -> SURVIVED/SURVIVED  ** crosses the unkilled boundary **"),
      "gated sibling-count growth was not classified as a boundary flip:\n" + siblingGrowth.output
    )

    // KILLED -> SURVIVED crosses the unkilled boundary: the wandering kill count the
    // task exists to catch, so it fails and names the row
    writeReport(
      "encoding",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,KILLED,none",
      "Codec.java,com.example.Codec,$mathMutator,encode,12,SURVIVED,none",
      "Codec.java,com.example.Codec,$boundaryMutator,decode,30,SURVIVED,none"
    )
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    val boundary = convergeRun().buildAndFail()
    assertTrue(
      boundary.output.contains("1 flip(s) cross the unkilled boundary"),
      boundary.output
    )
    assertTrue(
      boundary.output.contains("KILLED -> SURVIVED  ** crosses the unkilled boundary **"),
      "boundary marker missing:\n" + boundary.output
    )
    assertTrue(boundary.output.contains("-PunionMutationBaseline"), boundary.output)
  }

  @Test
  fun `a real converge invocation runs both rounds around the snapshot`() {
    // the -x fabrication in the other tests bypasses the graph; this pins it: every
    // suite's round one feeds the snapshot, and every round two follows it
    writeFixture()
    val plan = runner("pitestConverge", "--dry-run").build().output
    val order = listOf(
      ":pitestEncoding SKIPPED",
      ":pitestParsing SKIPPED",
      ":pitestConvergeSnapshot SKIPPED",
      ":pitestEncodingConvergeRound2 SKIPPED",
      ":pitestParsingConvergeRound2 SKIPPED",
      ":pitestConverge SKIPPED"
    )
    val positions = order.map { step ->
      val at = plan.indexOf(step)
      assertTrue(at >= 0, "$step missing from the converge plan:\n$plan")
      at
    }
    assertTrue(positions == positions.sorted(), "converge plan out of order:\n$plan")

    val trialPlan = runner("pitestMutatorTrial", "--dry-run").build().output
    assertTrue(trialPlan.contains(":pitestEncodingMutatorTrial SKIPPED"), trialPlan)
    assertTrue(trialPlan.contains(":pitestParsingMutatorTrial SKIPPED"), trialPlan)
  }

  @Test
  fun `converge snapshot refuses history-assisted invocations and missing reports`() {
    writeFixture()
    File(fixtureDir, "arcmutate-licence.txt").writeText("licence\n")

    // Two assisted runs agree by construction, so the snapshot refuses concrete
    // report evidence rather than inferring provenance from this checkout's current
    // licence setting.
    writeReport("encoding", "")
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val assisted = snapshotRun().buildAndFail()
    assertTrue(
      assisted.output.contains("round one is arcmutate-history-assisted"),
      assisted.output
    )
    assertTrue(assisted.output.contains("-PnoMutationHistory"), assisted.output)

    // The report's own manifest remains authoritative when a stale/tampered producer
    // omitted the matching marker.
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").delete()
    writeEvidence("encoding", historyAssisted = true)
    val markerlessAssisted = snapshotRun().buildAndFail()
    assertTrue(
      markerlessAssisted.output.contains("round one is arcmutate-history-assisted"),
      markerlessAssisted.output,
    )

    // the escape hatch reaches the per-suite report check, which names the first
    // suite missing its round-one report
    File(fixtureDir, "build/reports/pitest").deleteRecursively()
    val missing = runner(
      "pitestConvergeSnapshot", "-PnoMutationHistory", "-x", "pitestEncoding", "-x", "pitestParsing"
    ).buildAndFail()
    assertTrue(missing.output.contains("no round-one report for 'encoding'"), missing.output)
  }

  @Test
  fun `scoped reports cannot certify converge or the aggregate quality gate`() {
    writeFixture()
    writeReport("encoding", "Codec.java,com.example.Codec,$mathMutator,encode,8,KILLED,com.example.CodecTest")
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    File(fixtureDir, "build/reports/pitest/encoding/.scoped").writeText("com.example.Codec\n")

    val converge = snapshotRun().buildAndFail()
    assertTrue(converge.output.contains("scoped population cannot prove suite convergence"), converge.output)
    assertTrue(converge.output.contains("without -PmutateOnly"), converge.output)

    val encodingReport = File(fixtureDir, "build/reports/pitest/encoding/mutations.csv")
    encodingReport.parentFile.resolve(".scoped").delete()
    writeEvidence("encoding", scope = "com.example.Codec")
    val markerlessScoped = snapshotRun().buildAndFail()
    assertTrue(
      markerlessScoped.output.contains("evidence manifest says the round-one report is scoped"),
      markerlessScoped.output,
    )
    assertTrue(encodingReport.isFile, "markerless scoped evidence cleared its report")

    // Exclude the aggregate's expensive dependencies: this isolates the aggregate
    // task's own certification guard, which must still fail rather than print green.
    val gate = runner(
      "qualityGate", "-PmutateOnly=com.example.Codec",
      "-x", "test", "-x", "agentsTemplateInSync", "-x", "pitestEncoding", "-x", "pitestParsing"
    ).buildAndFail()
    assertTrue(gate.output.contains("qualityGate cannot certify a scoped mutation population"), gate.output)
  }

  @Test
  fun `converge snapshot validates every suite before replacing or clearing anything`() {
    writeFixture()
    writeReport("encoding", "Codec.java,com.example.Codec,$mathMutator,encode,8,KILLED,com.example.CodecTest")
    writeReport("parsing", "Parser.java,com.example.Parser,$mathMutator,parse,8,KILLED,com.example.ParserTest")
    val parsingDir = File(fixtureDir, "build/reports/pitest/parsing")
    parsingDir.resolve(".running").writeText("")
    val prior = File(fixtureDir, "build/pitest-converge/round1/prior.txt").apply {
      parentFile.mkdirs()
      writeText("prior snapshot")
    }

    val failed = snapshotRun().buildAndFail()

    assertTrue(failed.output.contains("'parsing' round-one report was left"), failed.output)
    assertEquals("prior snapshot", prior.readText(), "validation destroyed the prior snapshot")
    assertTrue(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "validation of a later suite deleted the earlier suite report",
    )
    assertTrue(parsingDir.resolve("mutations.csv").isFile)
  }

  @Test
  fun `mutator trial demands candidates and tabulates fired suites against silent ones`() {
    writeFixture()

    val unset = runner(
      "pitestMutatorTrial", "-x", "pitestEncodingMutatorTrial", "-x", "pitestParsingMutatorTrial"
    ).buildAndFail()
    assertTrue(unset.output.contains("needs -PtrialMutators="), unset.output)

    // encoding fired: TIMED_OUT counts as detected alongside KILLED, SURVIVED and
    // NO_COVERAGE as unkilled, and the per-mutator tally is keyed by simple name
    writeReport(
      "encoding-trial",
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.experimental.AlphaMutator,encode,10,KILLED,com.example.CodecTest",
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.experimental.AlphaMutator,encode,14,TIMED_OUT,none",
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.experimental.AlphaMutator,decode,20,SURVIVED,none",
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.experimental.BetaMutator,decode,25,NO_COVERAGE,none"
    )
    val trialArgs = arrayOf(
      "pitestMutatorTrial", "-PtrialMutators=EXPERIMENTAL_ALPHA,EXPERIMENTAL_BETA",
      "-x", "pitestEncodingMutatorTrial", "-x", "pitestParsingMutatorTrial"
    )
    val trial = runner(*trialArgs).build()
    assertTrue(
      trial.output.contains("pitestMutatorTrial 'EXPERIMENTAL_ALPHA,EXPERIMENTAL_BETA': fired in 1 of 2 suite(s)"),
      trial.output
    )
    assertTrue(
      trial.output.contains("4 generated — 2 killed by existing tests, 2 unkilled (AlphaMutator x3, BetaMutator x1)"),
      "fired tabulation missing:\n" + trial.output
    )
    assertTrue(
      trial.output.contains("0 generated (no report — cannot fire here, or the run failed above)"),
      "missing-report note absent:\n" + trial.output
    )
    assertTrue(trial.output.contains("Enable only what fires"), trial.output)

    // an empty report is a zero-fire observation, not a failed run: no missing-report note
    writeReport("parsing-trial")
    val zeroFire = runner(*trialArgs).build()
    assertTrue(
      zeroFire.output.contains("Configuration cache entry reused."),
      "pitestMutatorTrial did not reuse the fixture's configuration cache:\n" + zeroFire.output,
    )
    assertTrue(Regex("parsing\\s+0 generated\\n").containsMatchIn(zeroFire.output), zeroFire.output)
    assertFalse(
      zeroFire.output.contains("0 generated (no report"),
      "an empty report must not read as a missing one:\n" + zeroFire.output
    )
  }
}
