import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.BaselineDocument
import java.io.File

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
            String reportDir = null;
            for (String arg : args) {
              if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
            }
            Path report = Path.of(reportDir);
            Files.createDirectories(report);
            Files.writeString(report.resolve("mutations.csv"),
                "FakePit.java,com.example.FakePit," +
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                "main,12,SURVIVED,none\n");
          }
        }
      """.trimIndent() + "\n"
    )
    // Presence activates the licensed history policy; the fake task classpath is
    // consumer-overridden, so the fixture need not execute ArcMutate itself.
    File(fixtureDir, "arcmutate-licence.txt").writeText("fixture licence marker\n")
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

  @Test
  fun `installed help exposes canonical writers and legacy compatibility aliases`() {
    writeFixture()

    val output = runner("hardeningHelp").build().output

    listOf(
      "pitestEncodingBaselineUpdate",
      "pitestEncodingBaselineUnion",
      "pitestEncodingBaselinePrune",
      "pitestEncodingTimeoutAuditInit",
      "pitestModeCompareUnion",
      "migrateMutationBaselines",
      "downgradeMutationBaselines",
    ).forEach { task -> assertTrue(output.contains(task), "missing $task:\n$output") }
    assertTrue(
      output.contains("-PupdateMutationBaseline") &&
          output.contains("compatibility alias for pitest<Suite>BaselineUpdate"),
      output,
    )
    assertTrue(output.contains("-Pflag=false` is still present"), output)

    val reused = runner("hardeningHelp").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
  }

  @Test
  fun `canonical update runs fresh and creates a schema-marked baseline`() {
    writeFixture()

    val result = runner("pitestEncodingBaselineUpdate").build()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").readText()
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()

    assertTrue(result.output.contains("selected baseline update"), result.output)
    assertTrue(baseline.startsWith(BaselineDocument.CURRENT_HEADER + "\n"), baseline)
    assertTrue(
      baseline.contains("com.example.FakePit,main,MathMutator,SURVIVED # untriaged # line 12"),
      baseline,
    )
    assertFalse(args.contains("arcmutate_history"), args)
    assertFalse(args.contains("--historyInputLocation"), args)
    assertFalse(args.contains("--historyOutputLocation"), args)

    runner("pitestEncoding").build()
    val assisted = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertTrue(assisted.contains("--features=+arcmutate_history"), assisted)
    assertTrue(assisted.contains("--historyOutputLocation"), assisted)
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

    val legacy = runner(
      "pitestEncodingBaselineUpdate",
      "-PupdateMutationBaseline",
    ).buildAndFail().output
    assertTrue(legacy.contains("do not combine a discoverable hardening writer task"), legacy)
    assertFalse(runs.exists(), "legacy/task conflict must run before PIT")

    val scoped = runner(
      "pitestEncodingBaselineUpdate",
      "-PmutateOnly=com.example.FakePit",
    ).buildAndFail().output
    assertTrue(scoped.contains("requires full, unscoped evidence"), scoped)
    assertFalse(runs.exists(), "scoped writer refusal must run before PIT")

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
    File(fixtureDir, "arcmutate-licence.txt").delete()
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
      transition.contains(".evidence.tsv' has been created"),
      "configuration cache did not track the evidence-validation graph transition:\n$transition",
    )
    assertEquals(1, transition.lineSequence().count { it.contains("duplicate-from-fake") }, transition)
    assertTrue(transition.contains("suppressed 2 repeated minion log line(s)"), transition)

    val reused = runner("pitestEncoding").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertEquals(1, reused.lineSequence().count { it.contains("duplicate-from-fake") }, reused)
    assertTrue(reused.contains("suppressed 2 repeated minion log line(s)"), reused)
  }

  @Test
  fun `standalone evidence consumers schedule a task-produced custom PIT classpath`() {
    writeFixture(taskProducedToolClasspath = true)
    File(fixtureDir, "arcmutate-licence.txt").delete()
    acceptedBaseline()

    runner("pitestEncoding").build()
    val generatedTool = File(fixtureDir, "build/fake-pit-tool")
    val generatedClass = File(generatedTool, "com/example/FakePit.class")
    assertTrue(generatedClass.isFile)

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
