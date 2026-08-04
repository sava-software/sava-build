import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.BaselineDocument
import software.sava.build.hardening.HardeningOptionNames
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
  fun `installed help exposes canonical writers and removed property mappings`() {
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
    assertTrue(output.contains("Removed writer properties (refused since sava-build 21.5.22)"), output)
    assertTrue(output.contains("-PupdateMutationBaseline") &&
        output.contains("use pitest<Suite>BaselineUpdate"), output)
    assertTrue(output.contains("Named tasks are the only supported committed-file write interface"), output)

    val reused = runner("hardeningHelp").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
  }

  @Test
  fun `every removed writer property is refused before PIT or a record write`() {
    writeFixture()
    val (baseline, before) = acceptedBaseline()
    runner("hardeningHelp").build()
    val cached = runner("hardeningHelp").build().output
    assertTrue(cached.contains("Reusing configuration cache"), cached)

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
      assertFalse(File(fixtureDir, "build/fake-pit/runs.txt").exists())
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

    val generatedTool = File(fixtureDir, "build/fake-pit-tool")
    val transition = runner("clean", "pitestEncodingBaselineUpdate").build().output
    assertTrue(transition.contains(".evidence.tsv' has been created"), transition)
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
  fun `canonical union runs cold and reused without dropping an absent row`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        BaselineDocument.CURRENT_HEADER + "\n" +
            "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99\n",
      )
    }

    val cold = runner("pitestEncodingBaselineUnion").build()
    assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
    assertTrue(cold.output.contains("selected baseline union"), cold.output)
    assertTrue(cold.output.contains("union added 1 entries"), cold.output)
    assertEquals(
      listOf(
        BaselineDocument.CURRENT_HEADER,
        "com.example.FakePit,main,MathMutator,SURVIVED # line 12",
        "com.example.Removed,oldMethod,MathMutator,SURVIVED # retained # line 99",
      ),
      baseline.readLines(),
    )

    val before = baseline.readText()
    val transition = runner("pitestEncodingBaselineUnion").build().output
    assertTrue(transition.contains(".evidence.tsv' has been created"), transition)
    assertTrue(transition.contains("union added nothing new"), transition)
    val reused = runner("pitestEncodingBaselineUnion").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("union added nothing new"), reused)
    assertEquals(before, baseline.readText())
    assertEquals(3, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
  }

  @Test
  fun `canonical prune runs cold and reused while applying only its reviewed shrink`() {
    writeFixture()
    val (baseline, _) = acceptedBaseline()
    baseline.appendText(
      "com.example.Removed,oldMethod,MathMutator,SURVIVED # stale row # line 99\n",
    )

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
    assertTrue(transition.contains(".evidence.tsv' has been created"), transition)
    assertTrue(transition.contains("prune dropped nothing"), transition)
    val reused = runner("pitestEncodingBaselinePrune").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertTrue(reused.contains("prune dropped nothing"), reused)
    assertEquals(before, baseline.readText())
    assertEquals(3, File(fixtureDir, "build/fake-pit/runs.txt").readLines().size)
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
      timeouts.readText().contains("com.example.FakePit,main,MathMutator # line 12"),
      timeouts.readText(),
    )
    val args = File(fixtureDir, "build/fake-pit/args.txt").readText()
    assertFalse(args.contains("arcmutate_history"), args)

    assertTrue(timeouts.delete(), "fixture could not reset the seeded set for cache reuse")
    val transition = runner("clean", "pitestEncodingTimeoutAuditInit").build().output
    assertTrue(transition.contains(".evidence.tsv' has been created"), transition)
    assertTrue(timeouts.delete(), "fixture could not reset the seeded set after graph transition")
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
    File(fixtureDir, "arcmutate-licence.txt").delete()
    val (baseline, _) = acceptedBaseline()
    val status = File(fixtureDir, "fake-pit-status.txt")

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
