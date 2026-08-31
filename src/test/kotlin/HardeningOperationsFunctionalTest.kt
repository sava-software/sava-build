import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.BaselineDocument
import software.sava.build.hardening.HardeningHelpText
import software.sava.build.hardening.HardeningOptionNames
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class HardeningOperationsFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun writeFixture(taskProducedToolClasspath: Boolean = false) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-operations-smoke-test"
      """.trimIndent() + "\n"
    )
    val pitestClasspathConfiguration = if (taskProducedToolClasspath) {
      """
        val preparePitTool by tasks.registering(Sync::class) {
          from(sourceSets["main"].output)
          into(layout.buildDirectory.dir("fake-pit-tool"))
        }
        val fakePitToolClasspath = files(layout.buildDirectory.dir("fake-pit-tool")) {
          builtBy(preparePitTool)
        }

        tasks.named<JavaExec>("pitestEncoding") {
          classpath = fakePitToolClasspath
        }
      """.trimIndent()
    } else {
      """
        tasks.named<JavaExec>("pitestEncoding") {
          classpath = sourceSets["main"].output
        }
      """.trimIndent()
    }
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }

        hardening {
          mutation.register("encoding") {
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
          }
        }

        tasks.named<JavaExec>("pitestEncoding") {
          mainClass = "com.example.FakePit"
        }

        $pitestClasspathConfiguration
      """.trimIndent() + "\n"
    )
    val fake = File(fixtureDir, "src/main/java/com/example/FakePit.java")
    fake.parentFile.mkdirs()
    fake.writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.StandardOpenOption;

        public final class FakePit {
          public static void main(String[] args) throws Exception {
            System.out.print("PIT >> INFO : MINION : duplicate-from-fake\n");
            System.out.print("PIT >> INFO : MINION : duplicate-from-fake\n");
            System.err.print("PIT >> INFO : MINION : duplicate-from-fake\n");
            Path capture = Path.of("build/fake-pit");
            Files.createDirectories(capture);
            Files.writeString(capture.resolve("runs.txt"), "run\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.writeString(capture.resolve("args.txt"), String.join("\n", args) + "\n");
            Path statusFile = Path.of("fake-pit-status.txt");
            String status = Files.isRegularFile(statusFile)
                ? Files.readString(statusFile).trim()
                : "SURVIVED";
            String reportDir = null;
            for (String arg : args) {
              if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
            }
            Path report = Path.of(reportDir);
            Files.createDirectories(report);
            Files.writeString(report.resolve("mutations.csv"),
                "FakePit.java,com.example.FakePit," +
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                "main,12," + status + "," +
                (status.equals("KILLED") ? "com.example.FakePitTest" : "none") + "\n");
          }
        }
      """.trimIndent() + "\n"
    )
    // Presence activates the licensed history policy; the fake task classpath is
    // consumer-overridden, so model the effective ArcMutate artifact with its Maven
    // identity marker while FakePit remains the launched main class.
    File(fixtureDir, "arcmutate-licence.txt")
      .writeText("expires=31/12/2999\ntype=OSSS\nfixture=licence-marker\n")
    File(
      fixtureDir,
      "src/main/resources/META-INF/maven/com.arcmutate/base/pom.properties",
    ).apply {
      parentFile.mkdirs()
      writeText("groupId=com.arcmutate\nartifactId=base\nversion=1.7.2\n")
    }
  }

  private fun disableArcMutate() {
    File(fixtureDir, "arcmutate-licence.txt").delete()
    File(
      fixtureDir,
      "src/main/resources/META-INF/maven/com.arcmutate/base/pom.properties",
    ).delete()
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--configuration-cache", "--stacktrace")

  private fun acceptedBaseline(schemaMarked: Boolean = true): Pair<File, String> {
    val file = File(fixtureDir, "config/pitest/encoding-accepted.csv")
    file.parentFile.mkdirs()
    val row = "com.example.FakePit,main,MathMutator,SURVIVED # line 12\n"
    val content = if (schemaMarked) BaselineDocument.CURRENT_HEADER + "\n" + row else row
    file.writeText(content)
    return file to content
  }

  private fun adoptExistingRecordWithRebase() {
    runner("pitestEncodingBaselineRebase").build()
    File(fixtureDir, "build/fake-pit/runs.txt").delete()
  }

  @Test
  fun `installed help exposes read-only and writer workflows plus removed property mappings`() {
    writeFixture()

    val output = runner("hardeningHelp").build().output

    listOf(
      "hardeningAgentTemplate",
      "hardeningAgentTemplateDiff",
      "agentsTemplateInSync",
      "hardeningInit",
      "pitestEncoding",
      "pitestEncodingVerify",
      "pitestEncodingDebt",
      "pitestEncodingDiagnostic",
      "pitestEncodingBaselineRebase",
      "pitestEncodingBaselineUpdate",
      "pitestEncodingBaselineUnion",
      "pitestEncodingBaselineRetag",
      "pitestEncodingBaselinePrune",
      "pitestEncodingTimeoutAuditInit",
      "pitestModeCompareUnion",
      "migrateMutationBaselines",
      "downgradeMutationBaselines",
      "mutationOwnershipAudit",
      "hardeningReadmeAudit",
      "hardeningAgentProseAudit",
      ":hardeningCertifyAll",
    ).forEach { task -> assertTrue(output.contains(task), "missing $task:\n$output") }
    fun assertSingleEntry(task: String, purpose: String) {
      val entry = Regex(
        "(?m)^  ${Regex.escape(task)}\\s{2,}${Regex.escape(purpose)}$"
      )
      assertEquals(1, entry.findAll(output).count(), "missing or duplicate '$task' entry:\n$output")
    }
    assertSingleEntry(
      "hardeningAgentTemplate",
      "print the installed bounded agent-instructions template unquoted",
    )
    assertSingleEntry(
      "hardeningAgentTemplateDiff",
      "compare the bounded local block; normalizes one uniform Markdown '> ' quote layer",
    )
    assertSingleEntry(
      "agentsTemplateInSync",
      "check the installed template acknowledgment; used by check and qualityGate",
    )
    assertSingleEntry(
      "hardeningReadmeAudit",
      "advise on README source coordinates and inherited scaffold mechanics; used by check and qualityGate",
    )
    assertSingleEntry(
      "hardeningAgentProseAudit",
      "advise on copied plugin mechanics in root AGENTS.md prose; used by check and qualityGate",
    )
    assertSingleEntry(
      "hardeningInit",
      "scaffold config/pitest/README.md and the .pitest-history/ ignore rule",
    )
    assertSingleEntry(
      ":hardeningCertifyAll",
      "certify every hardening project; sibling projects continue after failure",
    )
    assertSingleEntry(
      "pitestEncoding",
      "run the suite's normal PIT mutation workflow",
    )
    assertSingleEntry(
      "pitestEncodingVerify",
      "check a completed report against the suite ratchet; scoped reports remain diagnostic",
    )
    val readOnlySection = Regex(
      "(?ms)^Read-only and certification workflows:\n(.*?)(?=^Repository scaffolding \\(may write files\\):$)"
    ).find(output)?.groupValues?.get(1)
    assertTrue(readOnlySection != null, "missing read-only workflow section:\n$output")
    listOf(
      "hardeningAgentTemplate",
      "hardeningAgentTemplateDiff",
      "agentsTemplateInSync",
      "hardeningReadmeAudit",
      "hardeningAgentProseAudit",
      ":hardeningCertifyAll",
      "pitestEncoding",
      "pitestEncodingVerify",
    ).forEach { task ->
      assertTrue(readOnlySection!!.contains(task), "$task is outside the read-only section:\n$output")
    }
    assertFalse(
      readOnlySection!!.contains("hardeningInit"),
      "hardeningInit must not be presented as read-only:\n$output",
    )
    val scaffoldingSection = Regex(
      "(?ms)^Repository scaffolding \\(may write files\\):\n(.*?)(?=^Accepted-baseline document lifecycle)"
    ).find(output)?.groupValues?.get(1)
    assertTrue(scaffoldingSection != null, "missing repository-scaffolding section:\n$output")
    assertTrue(
      scaffoldingSection!!.contains("hardeningInit"),
      "hardeningInit is outside the repository-scaffolding section:\n$output",
    )
    assertEquals(1, Regex("(?m)^[ \\t]+pitestEncodingDebt\\s+").findAll(output).count(), output)
    assertTrue(
      output.indexOf("pitestEncodingDebt") >
          output.indexOf("Read-only and certification workflows:") &&
          output.indexOf("pitestEncodingDebt") <
          output.indexOf("Accepted-baseline document lifecycle"),
      output,
    )
    assertTrue(
      output.contains("remove schema 1 from substantive baselines; empty placeholders stay absent"),
      output,
    )
    assertTrue(output.contains("Removed writer properties (refused since sava-build 21.5.22)"), output)
    assertTrue(output.contains("-PupdateMutationBaseline") &&
        output.contains("use pitest<Suite>BaselineUpdate"), output)
    assertTrue(output.contains("Named tasks are the only supported committed-file write interface"), output)
    assertTrue(
      output.contains("-PnoMutationHistory") &&
          output.contains("required when an ordinary run supports any accepted-baseline or timeout-audit decision"),
      output,
    )
    assertTrue(
      output.contains("-PisolateMutants") &&
          output.contains("requires -PmutateOnly and disables history"),
      output,
    )

    val reused = runner("hardeningHelp").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
  }

  @Test
  fun `installed help separates long generated task names from their descriptions`() {
    val output = HardeningHelpText.render(listOf("valuationManager"), emptyList())

    assertTrue(
      Regex(
        "(?m)^[ \\t]+pitestValuationManager\\s{2,}" +
            "run the suite's normal PIT mutation workflow$"
      ).containsMatchIn(output),
      output,
    )
    assertTrue(
      Regex(
        "(?m)^[ \\t]+pitestValuationManagerVerify\\s{2,}" +
            "check a completed report against the suite ratchet; scoped reports remain diagnostic$"
      ).containsMatchIn(output),
      output,
    )
    assertTrue(
      Regex(
        "(?m)^[ \\t]+pitestValuationManagerDebt\\s{2,}" +
            "inspect committed records and latest full-report debt without running PIT$"
      ).containsMatchIn(output),
      output,
    )
    assertTrue(
      output.contains("pitestValuationManagerTimeoutAuditInit  seed the suite timeout audit"),
      output,
    )
    assertFalse(output.contains("TimeoutAuditInitseed"), output)
  }

  @Test
  fun `every removed writer property is refused before PIT or a record write`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline()
    val runs = File(fixtureDir, "build/fake-pit/runs.txt")
    runner("pitestEncoding").build()
    runner("pitestEncoding").build()
    val cached = runner("pitestEncoding").build().output
    assertTrue(cached.contains("Reusing configuration cache"), cached)
    val runsBeforeRefusals = runs.readText()

    HardeningOptionNames.removedWriterTaskByProperty.forEach { (property, replacement) ->
      val spelling = if (property == HardeningOptionNames.UPDATE_MUTATION_BASELINE) {
        "-P$property=false"
      } else {
        "-P$property"
      }
      val output = runner("pitestEncoding", spelling).buildAndFail().output
      assertFalse(output.contains("Reusing configuration cache"), output)
      assertTrue(output.contains("writer properties were removed in sava-build 21.5.22"), output)
      assertTrue(output.contains("-P$property -> $replacement"), output)
      assertTrue(output.contains("only supported committed-file write interface"), output)
      assertEquals(runsBeforeRefusals, runs.readText(), "a refused property reached PIT")
      assertEquals(before, baseline.readText())
    }
  }

  @Test
  fun `canonical update runs fresh and creates a schema-marked baseline`() {
    writeFixture(taskProducedToolClasspath = true)

    val result = runner("clean", "pitestEncodingBaselineUpdate").build()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").readText()
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()

    assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    assertTrue(result.output.contains(":preparePitTool"), result.output)
    assertTrue(result.output.contains("selected baseline update"), result.output)
    assertTrue(baseline.startsWith(BaselineDocument.CURRENT_HEADER + "\n"), baseline)
    assertTrue(
      baseline.contains("com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12"),
      baseline,
    )
    assertFalse(args.contains("arcmutate_history"), args)
    assertFalse(args.contains("--historyInputLocation"), args)
    assertFalse(args.contains("--historyOutputLocation"), args)
    assertTrue(args.contains("--projectBase=${fixtureDir.canonicalPath}"), args)

    val generatedTool = File(fixtureDir, "build/fake-pit-tool")
    val transition = runner("clean", "pitestEncodingBaselineUpdate").build().output
    assertTrue(transition.contains("Reusing configuration cache"), transition)
    val reusedWriter = runner("clean", "pitestEncodingBaselineUpdate").build().output
    assertTrue(reusedWriter.contains("Reusing configuration cache"), reusedWriter)
    assertTrue(reusedWriter.contains(":preparePitTool"), reusedWriter)
    assertFalse(reusedWriter.contains(":preparePitTool UP-TO-DATE"), reusedWriter)
    assertTrue(
      File(generatedTool, "com/example/FakePit.class").isFile,
      "the reused named-writer graph did not restore its task-produced PIT classpath",
    )

    runner("pitestEncoding").build()
    val assisted = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertTrue(assisted.contains("--features=+arcmutate_history"), assisted)
    assertTrue(assisted.contains("--historyOutputLocation"), assisted)
  }

  @Test
  fun `named writer recaptures report and source after the dependency validator`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        val tamperWriterEvidence = tasks.register("tamperWriterEvidence") {
          dependsOn("pitestEncodingEvidenceValidate")
          val report = layout.buildDirectory.file("reports/pitest/encoding/mutations.csv")
          val source = layout.projectDirectory.file("src/main/java/com/example/FakePit.java")
          doLast {
            report.get().asFile.apply {
              writeText(readText().replace(",SURVIVED,", ",KILLED,"))
            }
            source.asFile.appendText("\n// changed after evidence validation\n")
          }
        }
        tasks.named("pitestEncodingVerify") {
          dependsOn(tamperWriterEvidence)
        }
      """.trimIndent() + "\n",
    )

    val output = runner("pitestEncodingBaselineUpdate").buildAndFail().output
    val config = File(fixtureDir, "config/pitest")

    assertTrue(output.contains(":tamperWriterEvidence"), output)
    assertTrue(output.contains("inputs changed after evidence validation"), output)
    assertTrue(output.contains("reportSha256"), output)
    assertTrue(output.contains("sourceSha256"), output)
    assertFalse(
      config.resolve("encoding-accepted.csv").exists(),
      "stale final evidence wrote an accepted baseline",
    )
    assertFalse(
      config.resolve("encoding-pitest-version").exists(),
      "stale final evidence wrote PIT provenance",
    )
    assertFalse(
      config.resolve("encoding-pitest-toolchain.tsv").exists(),
      "stale final evidence wrote mutation-toolchain provenance",
    )
  }

  @Test
  fun `Debt refuses orphan provenance left without a committed record`() {
    writeFixture()
    runner("pitestEncodingBaselineUpdate").build()
    val config = File(fixtureDir, "config/pitest")
    assertTrue(config.resolve("encoding-accepted.csv").delete())
    assertTrue(config.resolve("encoding-pitest-version").isFile)
    assertTrue(config.resolve("encoding-pitest-toolchain.tsv").isFile)

    val output = runner("pitestEncodingDebt").buildAndFail().output

    assertTrue(output.contains("mutation-provenance sidecar(s) exist without"), output)
    assertTrue(output.contains("pitestEncodingBaselineRebase"), output)

    File(fixtureDir, "fake-pit-status.txt").writeText("KILLED\n")
    val repaired = runner("pitestEncodingBaselineRebase").build().output
    assertTrue(
      repaired.contains(
        "removed orphan mutation provenance: encoding-pitest-version, " +
            "encoding-pitest-toolchain.tsv",
      ),
      repaired,
    )
    assertTrue(repaired.contains("no provenance files written"), repaired)
    assertFalse(config.resolve("encoding-pitest-version").exists())
    assertFalse(config.resolve("encoding-pitest-toolchain.tsv").exists())
  }

  @Test
  fun `first baseline write refuses before writing when a provenance target cannot commit`() {
    writeFixture()
    val config = File(fixtureDir, "config/pitest").apply { mkdirs() }
    val blockedVersion = config.resolve("encoding-pitest-version").apply {
      mkdirs()
      resolve("keep").writeText("directory makes the version target unwritable\n")
    }
    val baseline = config.resolve("encoding-accepted.csv")
    val toolchain = config.resolve("encoding-pitest-toolchain.tsv")

    runner("pitestEncodingBaselineUpdate").buildAndFail()

    assertFalse(baseline.exists(), "failed provenance write left a new accepted baseline")
    assertFalse(toolchain.exists(), "failed provenance write left an orphan toolchain sidecar")
    assertTrue(blockedVersion.resolve("keep").isFile, "prevalidation damaged the blocking fixture")
  }

  @Test
  fun `canonical rebase preserves legacy evidence and adopts toolchain provenance`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained argument # line 99\n",
      )
    }

    val cold = runner("pitestEncodingBaselineRebase").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected baseline provenance rebase"), cold.output)
    assertTrue(cold.output.contains("provenance rebase preserved 1 old row(s) and added 1"), cold.output)
    assertTrue(
      cold.output.contains(
        "com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12"
      ),
      cold.output,
    )
    assertTrue(
      cold.output.contains(
        "BaselineRebase wrote encoding-accepted.csv, encoding-pitest-version, and " +
            "encoding-pitest-toolchain.tsv",
      ),
      cold.output,
    )
    val rebound = baseline.readText()
    assertTrue(rebound.contains("# retained argument # line 99"), rebound)
    assertTrue(
      rebound.contains("com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12"),
      rebound,
    )
    val version = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    assertTrue(version.isFile, "rebase did not bind the N-1 PIT stamp")
    assertTrue(toolchain.isFile, "rebase did not bind the portable mutation toolchain")
    assertTrue(toolchain.readText().contains("arcMutateLicenceExpires\t2999-12-31"), toolchain.readText())
    val before = baseline.readBytes()

    val reused = runner("pitestEncodingBaselineRebase").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("retained all 2 accepted row(s)"), reused)
    assertTrue(
      reused.contains(
        "encoding-accepted.csv unchanged; wrote encoding-pitest-version and " +
            "encoding-pitest-toolchain.tsv",
      ),
      reused,
    )
    assertTrue(before.contentEquals(baseline.readBytes()), "fixed-point rebase rewrote accepted evidence")
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)
  }

  @Test
  fun `rebase reruns PIT after an identical history-free observation`() {
    writeFixture()
    adoptExistingRecordWithRebase()

    val observed = runner("pitestEncoding", "-PnoMutationHistory").build()
    assertEquals(TaskOutcome.SUCCESS, observed.task(":pitestEncoding")?.outcome, observed.output)

    val rebased = runner(
      "pitestEncodingBaselineRebase",
      "-PnoMutationHistory",
    ).build()
    assertEquals(TaskOutcome.SUCCESS, rebased.task(":pitestEncoding")?.outcome, rebased.output)
    assertEquals(
      listOf("run", "run"),
      File(fixtureDir, "build/fake-pit/runs.txt").readLines(),
      "the writer reused the immediately preceding history-free report instead of running PIT",
    )
  }

  @Test
  fun `certification and rebase refuse unfinished timeout causes before PIT`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        hardening {
          mutation.register("decoding") {
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
          }
        }

        tasks.named<JavaExec>("pitestDecoding") {
          mainClass = "com.example.FakePit"
          classpath = sourceSets["main"].output
        }
      """.trimIndent() + "\n",
    )
    val config = File(fixtureDir, "config/pitest").apply { mkdirs() }
    val decodingTimeouts = config.resolve("decoding-timeouts.csv")
    decodingTimeouts.writeText(
      "com.example.FakePit,main,MathMutator # cause:untriaged line 12\n" +
          "com.example.Other,wait,VoidMethodCallMutator # cause:liveness line 44\n" +
          "com.example.Broken,onlyTwo\n",
    )
    config.resolve("README.md").writeText(
      "# FakePit timeout\n\n`FakePit.main`: the removed exit cannot make progress.\n",
    )
    val runs = File(fixtureDir, "build/fake-pit/runs.txt")
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")

    val certify = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(certify.contains("committed timeout audit is not ready"), certify)
    assertTrue(certify.contains("cause:untriaged has not been reviewed"), certify)
    assertTrue(certify.contains("Evidence: 1 malformed membership row"), certify)
    assertTrue(certify.contains("1 member without a README cause"), certify)
    assertTrue(certify.contains("Execution: PIT has not run"), certify)
    assertTrue(certify.contains("Run :pitestDecodingDebt"), certify)
    assertTrue(certify.contains("Run :pitestDecoding -PnoMutationHistory"), certify)
    assertTrue(certify.contains("run :hardeningCertify in a new Gradle invocation"), certify)
    assertTrue(certify.contains("receipt is project-atomic"), certify)
    assertFalse(
      runs.exists(),
      "certification ran its first suite before finding later-suite static timeout debt",
    )
    assertFalse(receipt.exists(), "failed static preflight retained a certification receipt")
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").isFile,
      "failed static preflight did not leave the invalidation sentinel",
    )

    val reusedCertify = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(reusedCertify.contains("Reusing configuration cache"), reusedCertify)
    assertFalse(runs.exists(), "reused certification preflight reached PIT")

    val continued = runner("clean", "hardeningCertify", "--continue").buildAndFail().output
    assertTrue(continued.contains("committed timeout audit is not ready"), continued)
    assertFalse(runs.exists(), "--continue let another certification suite reach PIT")

    decodingTimeouts.writeText(
      "com.example.FakePit,main,MathMutator # cause:liveness line 12\n",
    )
    val timeouts = config.resolve("encoding-timeouts.csv")
    timeouts.writeText(
      "com.example.FakePit,main,MathMutator # cause:untriaged line 12\n",
    )
    val rebase = runner("pitestEncodingBaselineRebase").buildAndFail().output
    assertTrue(rebase.contains("committed timeout audit is not ready"), rebase)
    assertTrue(rebase.contains("PIT has not run"), rebase)
    assertFalse(runs.exists(), "BaselineRebase reached PIT before its static timeout preflight")

    val reusedRebase = runner("pitestEncodingBaselineRebase").buildAndFail().output
    assertTrue(reusedRebase.contains("Reusing configuration cache"), reusedRebase)
    assertFalse(runs.exists(), "reused BaselineRebase preflight reached PIT")

    File(fixtureDir, "fake-pit-status.txt").writeText("KILLED\n")
    val ordinary = runner("pitestEncoding", "-PnoMutationHistory").build()
    assertTrue(ordinary.output.contains(":pitestEncoding"), ordinary.output)
    assertEquals(listOf("run"), runs.readLines())
    val ordinaryArgs = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(ordinaryArgs.contains("arcmutate_history"), ordinaryArgs)
    assertFalse(ordinaryArgs.contains("--historyInputLocation"), ordinaryArgs)
    assertFalse(ordinaryArgs.contains("--historyOutputLocation"), ordinaryArgs)
    assertTrue(runs.delete())

    val strict = runner("pitestEncoding", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(strict.contains("committed timeout audit is not ready"), strict)
    assertTrue(strict.contains("PIT has not run"), strict)
    assertTrue(strict.contains("pitestEncoding -PnoMutationHistory"), strict)
    assertFalse(runs.exists(), "-PstrictTimeoutAudit reached PIT before static validation")

    timeouts.writeText(
      "com.example.FakePit,main,MathMutator # cause:liveness line 12\n",
    )
    val strictFresh = runner("pitestEncoding", "-PstrictTimeoutAudit").build()
    assertTrue(strictFresh.output.contains(":pitestEncoding"), strictFresh.output)
    val strictArgs = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(strictArgs.contains("arcmutate_history"), strictArgs)
    assertFalse(strictArgs.contains("--historyInputLocation"), strictArgs)
    assertFalse(strictArgs.contains("--historyOutputLocation"), strictArgs)
    assertEquals(listOf("run"), runs.readLines())
    assertTrue(runs.delete())

    val repaired = runner("pitestEncodingBaselineRebase").build()
    assertTrue(repaired.output.contains("selected baseline provenance rebase"), repaired.output)
    assertEquals(listOf("run"), runs.readLines())
  }

  @Test
  fun `continued suite writers isolate a timeout preflight failure`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        hardening {
          mutation.register("decoding") {
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
          }
        }

        tasks.named<JavaExec>("pitestDecoding") {
          mainClass = "com.example.FakePit"
          classpath = sourceSets["main"].output
        }
      """.trimIndent() + "\n",
    )
    val config = File(fixtureDir, "config/pitest").apply { mkdirs() }
    val encodingBaseline = config.resolve("encoding-accepted.csv").apply {
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.FakePit,main,MathMutator,SURVIVED # line 12\n",
      )
    }
    config.resolve("encoding-timeouts.csv").writeText(
      "com.example.FakePit,main,MathMutator # cause:untriaged line 12\n",
    )
    val decodingBaseline = config.resolve("decoding-accepted.csv").apply {
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.FakePit,main,MathMutator,SURVIVED # line 12\n",
      )
    }
    val encodingBefore = encodingBaseline.readBytes()

    val output = runner(
      "pitestEncodingBaselineRebase",
      "pitestDecodingBaselineRebase",
      "--continue",
    ).buildAndFail().output

    assertTrue(output.contains("committed timeout audit is not ready"), output)
    assertTrue(output.contains("pitest baseline 'decoding': BaselineRebase"), output)
    assertTrue(
      config.resolve("decoding-pitest-version").isFile &&
          config.resolve("decoding-pitest-toolchain.tsv").isFile,
      "the independent valid rebase did not finish under --continue:\n$output",
    )
    assertTrue(decodingBaseline.isFile)
    assertTrue(encodingBefore.contentEquals(encodingBaseline.readBytes()))
    assertFalse(config.resolve("encoding-pitest-version").exists())
    assertFalse(config.resolve("encoding-pitest-toolchain.tsv").exists())
    assertEquals(listOf("run"), File(fixtureDir, "build/fake-pit/runs.txt").readLines())

    val reused = runner(
      "pitestEncodingBaselineRebase",
      "pitestDecodingBaselineRebase",
      "--continue",
    ).buildAndFail().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("committed timeout audit is not ready"), reused)
    assertTrue(reused.contains("pitest baseline 'decoding': BaselineRebase"), reused)
    assertEquals(
      listOf("run", "run"),
      File(fixtureDir, "build/fake-pit/runs.txt").readLines(),
      "reused graph did not isolate and rerun the valid suite writer",
    )
    assertFalse(config.resolve("encoding-pitest-version").exists())
    assertFalse(config.resolve("encoding-pitest-toolchain.tsv").exists())
  }

  @Test
  fun `named writer preserves committed POSIX modes and creates readable sidecars`() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
    writeFixture()
    val (baseline, _) = acceptedBaseline()
    val baselineMode = PosixFilePermissions.fromString("rw-r--r--")
    Files.setPosixFilePermissions(baseline.toPath(), baselineMode)
    val ordinary = baseline.parentFile.resolve("ordinary-file")
    Files.createFile(ordinary.toPath())
    val newFileMode = Files.getPosixFilePermissions(ordinary.toPath())
    Files.delete(ordinary.toPath())

    runner("pitestEncodingBaselineRebase").build()

    assertEquals(baselineMode, Files.getPosixFilePermissions(baseline.toPath()))
    listOf(
      baseline.parentFile.resolve("encoding-pitest-version"),
      baseline.parentFile.resolve("encoding-pitest-toolchain.tsv"),
    ).forEach { sidecar ->
      assertTrue(sidecar.isFile, "named writer did not create ${sidecar.name}")
      assertEquals(
        newFileMode,
        Files.getPosixFilePermissions(sidecar.toPath()),
        "named writer created ${sidecar.name} with a private temp-file mode",
      )
    }
  }

  @Test
  fun `canonical union runs cold and reused without dropping an absent row`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99\n",
      )
    }
    // Bind the legacy row without adding the current coordinate; the union below
    // then exercises a distinct, already-adopted toolchain transition.
    File(fixtureDir, "fake-pit-status.txt").writeText("KILLED\n")
    adoptExistingRecordWithRebase()
    File(fixtureDir, "fake-pit-status.txt").writeText("SURVIVED\n")

    val cold = runner("pitestEncodingBaselineUnion").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected baseline union"), cold.output)
    assertTrue(cold.output.contains("union added 1 entries"), cold.output)
    assertTrue(cold.output.contains("seeded '# untriaged'"), cold.output)
    assertTrue(
      cold.output.contains(
        "com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12"
      ),
      cold.output,
    )
    assertEquals(
      listOf(
        BaselineDocument.CURRENT_HEADER,
        "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99",
        "com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12",
      ),
      baseline.readLines(),
    )

    val before = baseline.readText()
    val transition = runner("pitestEncodingBaselineUnion").build().output
    assertTrue(transition.contains("Reusing configuration cache"), transition)
    assertTrue(transition.contains("union added nothing new"), transition)
    val reused = runner("pitestEncodingBaselineUnion").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("union added nothing new"), reused)
    assertEquals(before, baseline.readText())
    assertEquals(3, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)
    assertFalse(args.contains("--historyInputLocation"), args)
    assertFalse(args.contains("--historyOutputLocation"), args)
  }

  @Test
  fun `canonical retag runs cold and reused without dropping an absent row`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.FakePit,main,MathMutator,SURVIVED # live family # line 10\n" +
            "# preserved baseline context\n\n" +
            "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99\n",
      )
    }
    adoptExistingRecordWithRebase()

    val cold = runner("pitestEncodingBaselineRetag").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected baseline line-tag refresh"), cold.output)
    assertTrue(
      cold.output.contains("intentionally does not reuse an existing report") &&
          cold.output.contains("one new full, unscoped, history-free PIT observation") &&
          cold.output.contains(":pitestEncoding -PnoMutationHistory"),
      cold.output,
    )
    assertTrue(cold.output.contains("retag refreshed 1 matched row line tag(s)"), cold.output)
    assertTrue(cold.output.contains("including unmatched evidence"), cold.output)
    assertEquals(
      BaselineDocument.CURRENT_HEADER + "\n" +
          "com.example.FakePit,main,MathMutator,SURVIVED # live family # line 12\n" +
          "# preserved baseline context\n\n" +
          "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99\n",
      baseline.readText(),
    )

    val before = baseline.readText()
    val transition = runner("pitestEncodingBaselineRetag").build().output
    assertTrue(transition.contains("Reusing configuration cache"), transition)
    assertTrue(transition.contains("retag changed nothing"), transition)
    val reused = runner("pitestEncodingBaselineRetag").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("retag changed nothing"), reused)
    assertEquals(before, baseline.readText())
    assertEquals(3, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)
    assertFalse(args.contains("--historyInputLocation"), args)
    assertFalse(args.contains("--historyOutputLocation"), args)
  }

  @Test
  fun `canonical prune runs cold and reused while applying only its reviewed shrink`() {
    writeFixture()
    val (baseline, _) = acceptedBaseline()
    baseline.appendText(
      "com.example.Removed,oldMethod,MathMutator,SURVIVED # stale row # line 99\n",
    )
    adoptExistingRecordWithRebase()

    // Prune is deliberately a third observation: establish two completed,
    // matching read-only previews before selecting the destructive workflow.
    runner("pitestEncoding", "-PnoMutationHistory").build()
    runner("pitestEncoding", "-PnoMutationHistory").build()

    val cold = runner("pitestEncodingBaselinePrune").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected baseline prune"), cold.output)
    assertTrue(cold.output.contains("prune dropped 1 row(s)"), cold.output)
    assertEquals(
      BaselineDocument.CURRENT_HEADER + "\n" +
          "com.example.FakePit,main,MathMutator,SURVIVED # line 12\n",
      baseline.readText(),
    )

    val before = baseline.readText()
    val transition = runner("pitestEncodingBaselinePrune").build().output
    assertTrue(transition.contains("Reusing configuration cache"), transition)
    assertTrue(transition.contains("prune dropped nothing"), transition)
    val reused = runner("pitestEncodingBaselinePrune").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("prune dropped nothing"), reused)
    assertEquals(before, baseline.readText())
    assertEquals(5, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)
    assertFalse(args.contains("--historyInputLocation"), args)
    assertFalse(args.contains("--historyOutputLocation"), args)
  }

  @Test
  fun `canonical timeout audit init runs cold and reused from a task-produced PIT tool`() {
    writeFixture(taskProducedToolClasspath = true)
    File(fixtureDir, "fake-pit-status.txt").writeText("TIMED_OUT\n")
    val timeouts = File(fixtureDir, "config/pitest/encoding-timeouts.csv")

    val cold = runner("clean", "pitestEncodingTimeoutAuditInit").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains(":preparePitTool"), cold.output)
    assertTrue(cold.output.contains("selected timeout-audit initialization"), cold.output)
    assertTrue(cold.output.contains("seeded 1 audited-timeout member(s)"), cold.output)
    assertTrue(
      timeouts.readText().contains(
        "com.example.FakePit,main,MathMutator # cause:untriaged line 12"
      ),
      timeouts.readText(),
    )
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)

    val version = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    fun resetCommittedRecord(message: String) {
      assertTrue(timeouts.delete(), "fixture could not reset the seeded set $message")
      assertTrue(version.delete(), "fixture could not reset the PIT stamp $message")
      assertTrue(toolchain.delete(), "fixture could not reset the toolchain stamp $message")
    }
    resetCommittedRecord("for cache reuse")
    val transition = runner("clean", "pitestEncodingTimeoutAuditInit").build().output
    assertTrue(transition.contains("Reusing configuration cache"), transition)
    resetCommittedRecord("after graph transition")
    val reused = runner("clean", "pitestEncodingTimeoutAuditInit").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains(":preparePitTool"), reused)
    assertFalse(reused.contains(":preparePitTool UP-TO-DATE"), reused)
    assertTrue(timeouts.isFile, "the reused writer graph did not seed the timeout audit")
    assertEquals(1, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
  }

  @Test
  fun `canonical mode compare union runs cold and reused against fresh snapshots`() {
    writeFixture()
    disableArcMutate()
    val (baseline, _) = acceptedBaseline()
    val status = File(fixtureDir, "fake-pit-status.txt")
    adoptExistingRecordWithRebase()

    status.writeText("KILLED\n")
    runner("pitestEncoding").build()
    runner("pitestModeSnapshot", "-PpitestMode=solo").build()

    status.writeText("SURVIVED\n")
    runner("pitestEncoding").build()
    runner("pitestModeSnapshot", "-PpitestMode=gate").build()

    val cold = runner("pitestModeCompareUnion").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected mode-flip insurance union"), cold.output)
    assertTrue(cold.output.contains("flip insurance written"), cold.output)
    assertTrue(
      baseline.readText().contains("# flip insurance (gate=SURVIVED, solo=KILLED) # line 12"),
      baseline.readText(),
    )

    val before = baseline.readText()
    val reused = runner("pitestModeCompareUnion").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("already insured"), reused)
    assertEquals(before, baseline.readText())
  }

  @Test
  fun `writer conflicts partial scope and exclusions fail before PIT starts`() {
    writeFixture()
    val (baseline, baselineBefore) = acceptedBaseline()
    val runs = File(fixtureDir, "build/fake-pit/runs.txt")

    val twoTasks = runner(
      "pitestEncodingBaselineUpdate",
      "pitestEncodingBaselinePrune",
    ).buildAndFail().output
    assertTrue(twoTasks.contains("hardening writer conflict"), twoTasks)
    assertFalse(runs.exists(), "conflicting writer preflights must run before PIT")

    val scoped = runner(
      "pitestEncodingBaselineUpdate",
      "-PmutateOnly=com.example.FakePit",
    ).buildAndFail().output
    assertTrue(scoped.contains("requires full, unscoped evidence"), scoped)
    assertFalse(runs.exists(), "scoped writer refusal must run before PIT")

    val scopedRetag = runner(
      "pitestEncodingBaselineRetag",
      "-PmutateOnly=com.example.FakePit",
    ).buildAndFail().output
    assertTrue(scopedRetag.contains("requires full, unscoped evidence"), scopedRetag)
    assertFalse(runs.exists(), "scoped retag refusal must run before PIT")

    val excluded = runner(
      "pitestEncodingBaselineUpdate",
      "-x",
      "pitestEncodingVerify",
    ).buildAndFail().output
    assertTrue(excluded.contains("cannot prove its complete task graph"), excluded)
    assertFalse(runs.exists(), "excluded writer graph must run before PIT")
    assertEquals(baselineBefore, baseline.readText(), "an excluded verify changed the baseline")
  }

  @Test
  fun `canonical writer refuses a skipped PIT instead of reusing an older report`() {
    writeFixture()
    disableArcMutate()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named("pitestEncoding") {
          val skipPit = providers.gradleProperty("skipPit").isPresent
          onlyIf { !skipPit }
        }
      """.trimIndent() + "\n",
    )
    val (baseline, _) = acceptedBaseline()
    baseline.appendText(
      "com.example.Removed,oldMethod,MathMutator,SURVIVED # stale row # line 99\n",
    )
    runner("pitestEncoding").build()
    val before = baseline.readText()

    val failed = runner(
      "pitestEncodingBaselinePrune",
      "-PskipPit",
    ).buildAndFail().output

    assertTrue(failed.contains("named baseline writer cannot reuse an older report"), failed)
    assertEquals(before, baseline.readText(), "a skipped PIT let prune consume an older report")
    assertEquals(
      1,
      File(fixtureDir, "build/fake-pit/runs.txt").readLines().size,
      "the skipped writer unexpectedly executed PIT",
    )
  }

  @Test
  fun `canonical writer refuses a skipped verify instead of reporting a green write`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named("pitestEncodingVerify") {
          val skipVerify = providers.gradleProperty("skipVerify").isPresent
          onlyIf { !skipVerify }
        }
      """.trimIndent() + "\n",
    )

    val failed = runner(
      "pitestEncodingBaselineUpdate",
      "-PskipVerify",
    ).buildAndFail().output

    assertTrue(failed.contains("not consumed"), failed)
    assertTrue(
      File(fixtureDir, "build/fake-pit/runs.txt").isFile,
      "fixture did not execute PIT before skipping verification",
    )
    assertEquals(before, baseline.readText(), "a skipped verify changed the baseline")
  }

  @Test
  fun `conflicting canonical writers poison continued execution before PIT or baseline mutation`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline()
    val runs = File(fixtureDir, "build/fake-pit/runs.txt")

    val output = runner(
      "pitestEncodingBaselineUpdate",
      "pitestEncodingBaselinePrune",
      "--continue",
    ).buildAndFail().output

    assertTrue(output.contains("hardening writer conflict"), output)
    assertTrue(output.contains("poisoned by an earlier conflict"), output)
    assertFalse(runs.exists(), "a poisoned writer invocation must refuse before starting FakePit")
    assertEquals(before, baseline.readText(), "a continued conflict must not rewrite the baseline")
  }

  @Test
  fun `excluding a canonical suite preflight cannot turn its public writer into a green no-op`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline()

    val output = runner(
      "pitestEncodingBaselineUpdate",
      "-x",
      "pitestEncodingBaselineUpdatePreflight",
    ).buildAndFail().output

    assertTrue(output.contains("did not complete its required workflow"), output)
    assertTrue(
      File(fixtureDir, "build/fake-pit/runs.txt").isFile,
      "this fixture must reach the public task's postcondition after its ordinary PIT dependency",
    )
    assertEquals(before, baseline.readText(), "an excluded preflight must not select a writer")
  }

  @Test
  fun `excluding either schema preflight refuses before touching its baseline`() {
    writeFixture()
    val (baseline, unversioned) = acceptedBaseline(schemaMarked = false)

    val migrate = runner(
      "migrateMutationBaselines",
      "-x",
      "migrateMutationBaselinesPreflight",
    ).buildAndFail().output
    assertTrue(migrate.contains("expected ':' to select SCHEMA_MIGRATE"), migrate)
    assertEquals(unversioned, baseline.readText())

    val current = BaselineDocument.CURRENT_HEADER + "\n" + unversioned
    baseline.writeText(current)
    val downgrade = runner(
      "downgradeMutationBaselines",
      "-x",
      "downgradeMutationBaselinesPreflight",
    ).buildAndFail().output
    assertTrue(downgrade.contains("expected ':' to select SCHEMA_DOWNGRADE"), downgrade)
    assertEquals(current, baseline.readText())
  }

  @Test
  fun `migration removes empty accepted placeholders and preserves timeout provenance`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText("  \n\t\n")
    }
    val stamp = File(fixtureDir, "config/pitest/encoding-pitest-version").apply {
      writeText("fixture-pit\n")
    }
    val timeouts = File(fixtureDir, "config/pitest/encoding-timeouts.csv")

    val orphaned = runner("migrateMutationBaselines").build().output

    assertTrue(
      orphaned.contains("removed empty accepted-baseline placeholder and its orphan PIT-version stamp"),
      orphaned,
    )
    assertFalse(baseline.exists(), "migration retained a whitespace-only accepted baseline")
    assertFalse(stamp.exists(), "migration retained a stamp with no accepted or timeout record")

    baseline.writeText("\n \n")
    stamp.writeText("fixture-pit\n")
    val timeoutBefore =
      "# audited set remains unversioned\n" +
          "com.example.FakePit,main,MathMutator # line 12\n"
    timeouts.writeText(timeoutBefore)

    val audited = runner("migrateMutationBaselines").build().output

    assertTrue(audited.contains("removed empty accepted-baseline placeholder"), audited)
    assertFalse(
      audited.contains("and its orphan PIT-version stamp"),
      "a timeout-backed PIT stamp was described as orphaned:\n$audited",
    )
    assertFalse(baseline.exists(), "migration retained a whitespace-only accepted baseline")
    assertEquals("fixture-pit\n", stamp.readText(), "timeout provenance stamp was removed")
    assertEquals(timeoutBefore, timeouts.readText(), "migration rewrote the timeout audit set")
  }

  @Test
  fun `downgrade refuses unsupported schema content without changing the file`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
    }
    val before =
      BaselineDocument.CURRENT_HEADER + "\n" +
          "com.example.FakePit,main,MathMutator,SURVIVED # line 12\n" +
          "unsupported,row\n"
    baseline.writeText(before)

    val output = runner("downgradeMutationBaselines").buildAndFail().output

    assertTrue(
      output.contains("cannot downgrade accepted-baseline schema") &&
          output.contains("malformed baseline row(s) at line 3"),
      output,
    )
    assertEquals(before, baseline.readText(), "a refused downgrade changed the schema-1 document")
  }

  @Test
  fun `migration and downgrade conflict stays atomic under continue`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline(schemaMarked = false)

    val output = runner(
      "migrateMutationBaselines",
      "downgradeMutationBaselines",
      "--continue",
    ).buildAndFail().output

    assertTrue(output.contains("hardening writer conflict"), output)
    assertTrue(output.contains("poisoned by an earlier conflict"), output)
    assertEquals(before, baseline.readText(), "a poisoned schema invocation must write no file")
  }

  @Test
  fun `project and suite writers arbitrate before either can mutate under continue`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline(schemaMarked = false)

    val output = runner(
      "migrateMutationBaselines",
      "pitestEncodingBaselineUpdate",
      "--continue",
    ).buildAndFail().output

    assertTrue(output.contains("hardening writer conflict"), output)
    assertTrue(output.contains("poisoned by an earlier conflict"), output)
    assertFalse(
      File(fixtureDir, "build/fake-pit/runs.txt").exists(),
      "all selected writer preflights must arbitrate before PIT",
    )
    assertEquals(before, baseline.readText(), "cross-surface conflict must write no baseline")
  }

  @Test
  fun `late JavaExec classpath customization remains evidence-bound across cold and reused verify`() {
    writeFixture()
    acceptedBaseline()

    runner("pitestEncoding").build()
    val evidence = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    val recorded = evidence.readText()

    val cold = runner("pitestEncodingVerify").build().output
    assertFalse(cold.contains("Reusing configuration cache"), cold)
    assertEquals(recorded, evidence.readText())

    val reused = runner("pitestEncodingVerify").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertEquals(recorded, evidence.readText())
    assertEquals(
      1,
      File(fixtureDir, "build/fake-pit/runs.txt").readLines().size,
      "standalone evidence verification must not execute PIT",
    )
  }

  @Test
  fun `typed PIT keeps minion filtering after a configuration cache round trip`() {
    writeFixture()
    acceptedBaseline()

    val cold = runner("pitestEncoding").build().output
    assertEquals(1, cold.lineSequence().count { it.contains("duplicate-from-fake") }, cold)
    assertTrue(cold.contains("suppressed 2 repeated minion log line(s)"), cold)

    val transition = runner("pitestEncoding").build().output
    assertTrue(
      transition.contains("Reusing configuration cache"),
      "PIT evidence creation invalidated the invariant configuration-cache graph:\n$transition",
    )
    assertEquals(1, transition.lineSequence().count { it.contains("duplicate-from-fake") }, transition)
    assertTrue(transition.contains("suppressed 2 repeated minion log line(s)"), transition)

    val reused = runner("pitestEncoding").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertEquals(1, reused.lineSequence().count { it.contains("duplicate-from-fake") }, reused)
    assertTrue(reused.contains("suppressed 2 repeated minion log line(s)"), reused)
  }

  @Test
  fun `typed PIT refuses configured versus effective ArcMutate classpath drift`() {
    val scenariosRoot = fixtureDir
    fixtureDir = scenariosRoot.resolve("missing-base").apply { mkdirs() }
    enableTestKitConfigurationCache(fixtureDir)
    writeFixture()
    val marker = File(
      fixtureDir,
      "src/main/resources/META-INF/maven/com.arcmutate/base/pom.properties",
    )
    assertTrue(marker.delete())

    val missingBase = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      missingBase.contains("configured ArcMutate activation (true) disagrees") &&
          missingBase.contains("no base artifact"),
      missingBase,
    )
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding/.running").exists())

    // Exercise the inverse mismatch in a fresh checkout, keeping this assertion about
    // the typed task's guard rather than configuration invalidation after the resource
    // and licence transitions needed to construct the opposite state.
    fixtureDir = scenariosRoot.resolve("hidden-base").apply { mkdirs() }
    enableTestKitConfigurationCache(fixtureDir)
    writeFixture()
    assertTrue(File(fixtureDir, "arcmutate-licence.txt").delete())
    val hiddenBase = runner("pitestEncoding").buildAndFail().output
    assertTrue(hiddenBase.contains("configured ArcMutate activation (false) disagrees"), hiddenBase)
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding/.running").exists())
  }

  @Test
  fun `typed PIT refuses a consumer working directory override`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named<JavaExec>("pitestEncoding") {
          workingDir(layout.buildDirectory)
        }
      """.trimIndent() + "\n",
    )

    val failed = runner("pitestEncoding").buildAndFail().output

    assertTrue(failed.contains("hardening owns workingDir"), failed)
    assertTrue(failed.contains("certificate lookup remain provenance-bound"), failed)
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding/.running").exists())
  }

  @Test
  fun `standalone evidence consumers schedule a task-produced custom PIT classpath`() {
    writeFixture(taskProducedToolClasspath = true)
    disableArcMutate()
    acceptedBaseline()

    runner("pitestEncoding").build()
    val generatedTool = File(fixtureDir, "build/fake-pit-tool")
    val generatedClass = File(generatedTool, "com/example/FakePit.class")
    assertTrue(generatedClass.isFile)

    val fullReport = File(fixtureDir, "build/reports/pitest/encoding/mutations.csv")
    val fullBeforeScopedSnapshot = fullReport.readText()
    val scopedSnapshot = runner(
      "pitestModeSnapshot",
      "-PpitestMode=scoped",
      "-PmutateOnly=com.example.FakePit",
    ).buildAndFail().output
    assertTrue(scopedSnapshot.contains("cannot consume a scoped mutation population"), scopedSnapshot)
    assertEquals(
      fullBeforeScopedSnapshot,
      fullReport.readText(),
      "scoped mode-snapshot refusal consumed the preserved full report",
    )

    generatedTool.deleteRecursively()
    val verify = runner("pitestEncodingVerify").build().output
    assertTrue(verify.contains(":preparePitTool"), verify)
    assertTrue(generatedClass.isFile, "standalone verify must restore its task-produced tool input")

    generatedTool.deleteRecursively()
    val snapshot = runner(
      "pitestModeSnapshot",
      "-PpitestMode=solo",
    ).build().output
    assertTrue(snapshot.contains(":preparePitTool"), snapshot)
    assertTrue(generatedClass.isFile, "mode snapshot must restore its task-produced tool input")
    assertEquals(
      1,
      File(fixtureDir, "build/fake-pit/runs.txt").readLines().size,
      "standalone verification and snapshot must not execute PIT",
    )
  }
}
