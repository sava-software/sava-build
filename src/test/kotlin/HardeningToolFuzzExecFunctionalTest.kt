import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** Fuzz execution receipts, corpus adoption, and build-wide execution slots. */
class HardeningToolFuzzExecFunctionalTest : HardeningToolExecFixture() {

  private fun fuzzAttemptDirectories(target: String): List<File> {
    val directory = File(fixtureDir, "build/reports/fuzz/$target")
    return directory.listFiles()
      .orEmpty()
      .filter { it.isDirectory && it.name.startsWith("attempt-") }
      .sortedBy { it.name }
  }

  @Test
  fun `a fuzz target execution is configuration-cache clean`() {
    writeFixture()

    val first = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(first.output.contains("fixture fuzz executed"), first.output)
    val firstAttempt = fuzzAttemptDirectories("plain").single()
    val stdoutLog = firstAttempt.resolve("jazzer.stdout.log")
    val stderrLog = firstAttempt.resolve("jazzer.stderr.log")
    assertTrue(stdoutLog.readText().contains("fixture fuzz executed"), stdoutLog.readText())
    assertTrue(stderrLog.readText().contains("\tNEW "), stderrLog.readText())
    stdoutLog.appendText("stale stdout bytes\n")
    stderrLog.appendText("stale stderr bytes\n")
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
      "a standalone fuzz target must not create an aggregate receipt",
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.running").exists(),
      "a standalone fuzz target must not activate an aggregate campaign",
    )

    val second = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
    assertTrue(second.output.contains("fixture fuzz executed"), second.output)
    val attempts = fuzzAttemptDirectories("plain")
    assertEquals(2, attempts.size, "each invocation must retain its own raw-log attempt")
    val secondAttempt = attempts.single { it != firstAttempt }
    val secondStdout = secondAttempt.resolve("jazzer.stdout.log")
    val secondStderr = secondAttempt.resolve("jazzer.stderr.log")
    assertTrue(stdoutLog.readText().contains("stale stdout bytes"), stdoutLog.readText())
    assertTrue(stderrLog.readText().contains("stale stderr bytes"), stderrLog.readText())
    assertFalse(secondStdout.readText().contains("stale stdout bytes"), secondStdout.readText())
    assertFalse(secondStderr.readText().contains("stale stderr bytes"), secondStderr.readText())
  }

  @Test
  fun `fuzzAll attests every configured target completed in this invocation`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val result = runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")

    assertTrue(
      result.output.contains(
        "fuzzAll: started 3 target(s) in project ':'; up to 1 concurrently across selected projects",
      ),
      result.output,
    )
    assertFalse(result.output.contains("fixture fuzz executed"), result.output)
    assertFalse(result.output.contains("\tNEW "), result.output)
    assertFalse(result.output.contains("\tREDUCE "), result.output)
    mapOf("fuzzCodec" to 101L, "fuzzHollow" to 202L, "fuzzPlain" to 303L)
        .forEach { (taskName, executions) ->
      assertTrue(result.output.contains(":$taskName: #1\tINITED"), result.output)
      assertTrue(result.output.contains(":$taskName: #3\tpulse"), result.output)
      assertTrue(
        result.output.contains(":$taskName: #$executions\tDONE cov: 2 ft: 2"),
        result.output,
      )
      val target = taskName.removePrefix("fuzz").replaceFirstChar(Char::lowercase)
      val attempt = fuzzAttemptDirectories(target).single()
      val stdout = attempt.resolve("jazzer.stdout.log")
      val stderr = attempt.resolve("jazzer.stderr.log")
      assertTrue(stdout.isFile, "missing retained stdout for $taskName:\n${result.output}")
      assertTrue(stderr.isFile, "missing retained stderr for $taskName:\n${result.output}")
      assertTrue(stdout.readText().contains("fixture fuzz executed"), stdout.readText())
      assertTrue(stderr.readText().contains("\tNEW "), stderr.readText())
      assertTrue(stderr.readText().contains("\tREDUCE "), stderr.readText())
      assertTrue(stderr.readText().contains("Done "), stderr.readText())
    }
    assertTrue(receipt.isFile, "fuzzAll did not write its receipt:\n${result.output}")
    assertEquals(
      listOf(
        "schema\t5",
        "project\t:",
        "pluginSha256\t" + receipt.readLines().single { it.startsWith("pluginSha256\t") }
          .substringAfter('\t'),
        "maxFuzzTimeSeconds\t1",
        "maxParallelTargets\t1",
        "totalExecutions\t606",
        "target\tfuzzCodec\t101",
        "target\tfuzzHollow\t202",
        "target\tfuzzPlain\t303",
      ),
      receipt.readLines().filterNot {
        it.startsWith("targetObservation\t") || it.startsWith("targetSource\t")
      },
    )
    val rows = receipt.readLines().map { it.split('\t') }
    assertEquals(3, rows.count { it[0] == "targetObservation" })
    assertEquals(3, rows.count { it[0] == "targetSource" })
    listOf("codec", "hollow", "plain").forEach { target ->
      val taskName = "fuzz${target.replaceFirstChar(Char::uppercase)}"
      val observation = rows.single { it[0] == "targetObservation" && it[1] == taskName }
      assertEquals(8, observation.size)
      assertTrue(observation[2].toLong() > 0, "elapsed target time must be observed")
      val attempt = fuzzAttemptDirectories(target).single()
      assertEquals(attempt.canonicalPath, File(observation[3]).canonicalPath)
      listOf("jazzer.stdout.log" to 4, "jazzer.stderr.log" to 6).forEach { (name, index) ->
        val log = attempt.resolve(name)
        assertEquals(log.length().toString(), observation[index])
        assertEquals(PitestEvidence.sha256(log), observation[index + 1])
      }
      val source = rows.single { it[0] == "targetSource" && it[1] == taskName }
      assertEquals(8, source.size)
      assertTrue(source[2].matches(Regex("[0-9a-f]{64}")))
      assertEquals(List(5) { "unavailable" }, source.drop(3))
    }
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.running").exists(),
      "successful campaign retained its running sentinel",
    )
  }

  @Test
  fun `fuzzAll refuses source edits during execution and retains prior evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (commit, tree) = initializeGitFixture()
    File(fixtureDir, "src/main/java/com/example/FakeFuzz.java")
      .appendText("\n// dirty before the campaign\n")
    runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val previous = receipt.readBytes()
    receipt.readLines().filter { it.startsWith("targetSource\t") }.forEach { row ->
      assertEquals(listOf("dirty", commit, tree), row.split('\t').subList(3, 6))
    }
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("change-fuzz-source\n")
    val statusBefore = git("status", "--porcelain=v1", "--untracked-files=all")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()

    assertTrue(failed.output.contains("fuzz source identity changed"), failed.output)
    assertTrue(failed.output.contains("Configuration cache entry reused."), failed.output)
    assertArrayEquals(previous, receipt.readBytes())
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")
    assertTrue(
      running.readText().startsWith("refused\t") &&
        running.readText().contains("failed: ") &&
        running.readText().contains("fuzz source identity changed"),
      "a failed target left the campaign state active:\n${running.readText()}",
    )
    assertTrue(listOf("codec", "hollow", "plain").any { fuzzAttemptDirectories(it).size >= 2 })
    assertEquals(statusBefore, git("status", "--porcelain=v1", "--untracked-files=all"))
  }

  @Test
  fun `fuzzAll full-output compatibility flag restores raw console while retaining logs`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val result = runner("fuzzAll", "-PmaxFuzzTime=1", "-PfullFuzzOutput").build()

    assertEquals(3, occurrences(result.output, "fixture fuzz executed"), result.output)
    assertEquals(3, occurrences(result.output, "\tNEW cov: 2 ft: 2"), result.output)
    assertEquals(3, occurrences(result.output, "\tREDUCE cov: 2 ft: 2"), result.output)
    listOf("codec", "hollow", "plain").forEach { target ->
      assertTrue(
        fuzzAttemptDirectories(target).single().resolve("jazzer.stderr.log").isFile,
        "missing retained stderr for $target:\n${result.output}",
      )
    }
  }

  @Test
  fun `fuzzAll invalid parallel width preserves old evidence under its refusal sentinel`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv").apply {
      parentFile.mkdirs()
      writeText("prior completed campaign\n")
    }
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")

    val failed = runner(
      "fuzzAll",
      "-PmaxFuzzTime=1",
      "-PmaxParallelFuzzTargets=0",
    ).buildAndFail()

    assertTrue(
      failed.output.contains(
        "-PmaxParallelFuzzTargets must be positive whole targets without leading zeros",
      ),
      "invalid parallel width was misreported:\n${failed.output}",
    )
    assertEquals(
      "prior completed campaign\n",
      receipt.readText(),
      "invalid settings destroyed the last successful receipt",
    )
    assertTrue(running.isFile, "invalid settings did not retain the refusal sentinel")
    assertTrue(
      running.readText().startsWith("refused\t") &&
          running.readText().contains("-PmaxParallelFuzzTargets must be positive whole targets"),
      "invalid settings retained the wrong campaign state:\n${running.readText()}",
    )
  }

  @Test
  fun `fuzzAll refuses missing ambiguous and zero live execution counts`() {
    listOf(
      "missing" to "emitted no terminal",
      "ambiguous" to "ambiguous campaign receipt",
      "zero" to "must be positive",
    ).forEach { (mode, message) ->
      writeFixture()
      writeSeedCorpus()
      File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
      File(fixtureDir, "fake-fuzz-mode.txt").writeText("$mode\n")

      val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()

      assertTrue(failed.output.contains(message), "$mode failure was misreported:\n${failed.output}")
      assertFalse(
        File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
        "$mode execution evidence earned a fuzzAll receipt",
      )
      val running = File(fixtureDir, ".pitest-history/local-fuzz.running")
      assertTrue(
        running.readText().startsWith("refused\t") && running.readText().contains(message),
        "$mode execution evidence did not retain a refused record:\n${running.readText()}",
      )
    }
  }

  @Test
  fun `fuzzAll cannot attest a failed target when JavaExec ignores its exit value`() {
    writeFixture(buildTail = """
      tasks.named<JavaExec>("fuzzPlain") {
        isIgnoreExitValue = true
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("fail\n")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()
    val attempt = fuzzAttemptDirectories("plain").single()
    val stdoutLog = attempt.resolve("jazzer.stdout.log")
    val stderrLog = attempt.resolve("jazzer.stderr.log")

    assertTrue(failed.output.contains("non-zero exit value 4"), failed.output)
    assertTrue(failed.output.contains("failed; raw logs:"), failed.output)
    assertTrue(failed.output.contains(stdoutLog.absolutePath), failed.output)
    assertTrue(failed.output.contains(stderrLog.absolutePath), failed.output)
    assertTrue(stdoutLog.readText().contains("fixture fuzz executed"), stdoutLog.readText())
    assertTrue(stderrLog.readText().contains("\tREDUCE "), stderrLog.readText())
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
      "a failed target earned a fuzzAll receipt",
    )
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")
    assertTrue(
      running.readText().startsWith("refused\tfuzzPlain failed: ") &&
        running.readText().contains("non-zero exit value 4"),
      "a failed campaign did not retain a refused record:\n${running.readText()}",
    )
  }

  @Test
  fun `fuzzAll refuses every exclusion before children while standalone exclusions preserve evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")

    receipt.parentFile.mkdirs()
    receipt.writeText("stale successful receipt\n")
    running.writeText("stale running sentinel\n")
    val excludedTarget = runner(
      "fuzzAll", "-PmaxFuzzTime=1", "-x", "fuzzPlain",
    ).buildAndFail()
    assertTrue(
      excludedTarget.output.contains("fuzzAll requires its complete task graph"),
      excludedTarget.output,
    )
    assertTrue(excludedTarget.output.contains("-x fuzzPlain"), excludedTarget.output)
    assertEquals(
      "stale successful receipt\n",
      receipt.readText(),
      "excluded target destroyed last-success evidence",
    )
    assertTrue(running.isFile, "excluded target did not leave an incomplete-campaign sentinel")

    receipt.writeText("another stale successful receipt\n")
    assertTrue(running.delete(), "could not model a prior completed campaign")
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("fail\n")
    val excludedPreflight = runner(
      "fuzzAll", "-PmaxFuzzTime=1", "-x", "fuzzAllPreflight",
    ).buildAndFail()
    assertTrue(
      excludedPreflight.output.contains("fuzzAll requires its complete task graph"),
      excludedPreflight.output,
    )
    assertTrue(excludedPreflight.output.contains("-x fuzzAllPreflight"), excludedPreflight.output)
    assertFalse(
      excludedPreflight.output.contains("fixture fuzz executed"),
      "excluded preflight allowed a target to run before invalidation:\n${excludedPreflight.output}",
    )
    assertEquals(
      "another stale successful receipt\n",
      receipt.readText(),
      "excluded preflight destroyed last-success evidence",
    )
    assertTrue(running.isFile, "excluded preflight did not publish a running sentinel")
    assertTrue(
      running.readText().startsWith("refused\t") &&
          running.readText().contains("-x fuzzAllPreflight"),
      "excluded preflight did not replace the stale sentinel:\n${running.readText()}",
    )

    receipt.writeText("valid aggregate evidence untouched by standalone work\n")
    assertTrue(running.delete(), "could not model a completed aggregate before standalone work")
    File(fixtureDir, "fake-fuzz-mode.txt").delete()
    val standalone = runner(
      "fuzzPlain", "-PmaxFuzzTime=1", "-x", "fuzzAllPreflight",
    ).build()
    assertTrue(standalone.output.contains("fixture fuzz executed"), standalone.output)
    assertEquals(
      "valid aggregate evidence untouched by standalone work\n",
      receipt.readText(),
      "a standalone target invalidated unrelated aggregate evidence",
    )
    assertFalse(running.exists(), "a standalone target activated an aggregate campaign")

    val excludedInternals = runner(
      "fuzzAll", "-PmaxFuzzTime=1",
      "-x", "fuzzAllPreflight", "-x", "validateFuzzBudget",
    ).buildAndFail()
    assertTrue(
      excludedInternals.output.contains("fuzzAll requires its complete task graph"),
      excludedInternals.output,
    )
    assertFalse(
      excludedInternals.output.contains("fixture fuzz executed"),
      "excluding both old internal boundaries allowed a child to run:\n${excludedInternals.output}",
    )
    assertEquals(
      "valid aggregate evidence untouched by standalone work\n",
      receipt.readText(),
      "excluded internal tasks destroyed last-success evidence",
    )
    assertTrue(running.isFile, "excluded internal tasks did not leave a refusal sentinel")
  }

  @Test
  fun `clean fuzzAll exempts generated build inputs but refuses ignored source inputs`() {
    writeFixture(buildTail = "sourceSets.main { java.srcDir(layout.buildDirectory.dir(\"generated\")) }")
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "build/generated/Generated.java").apply {
      parentFile.mkdirs()
      writeText("class Generated {}\n")
    }
    initializeGitFixture(listOf("Integ.java"))

    val completed = runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val sourceRows = receipt.readLines().filter { it.startsWith("targetSource\t") }
    assertEquals(3, sourceRows.size, "${receipt.readText()}\n${completed.output}")
    sourceRows.forEach { row -> assertEquals("clean", row.split('\t')[3], row) }

    File(fixtureDir, "src/main/java/com/example/Integ.java")
      .writeText("package com.example; final class Integ {}\n")
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))
    val refused = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail().output
    assertTrue(
      refused.contains("clean Git fuzz campaign cannot bind 1 source input(s) absent from its captured tree") &&
        refused.contains("  src/main/java/com/example/Integ.java") &&
        refused.contains("run the fuzz campaign from a detached worktree of this commit") &&
        refused.contains("(git worktree add --detach <dir> ") &&
        !refused.contains("certify from a detached worktree"),
      refused,
    )
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running").readText()
    assertTrue(
      running.startsWith("refused\tfuzz") &&
        running.contains("failed: clean Git fuzz campaign cannot bind 1 source input(s)") &&
        running.endsWith("\n") && running.count { it == '\n' } == 1,
      running,
    )
    assertEquals(3, receipt.readLines().count { it.startsWith("targetSource\t") }, "the last receipt survives")
  }

  @Test
  fun `minimize adopts novel inputs, keeps surviving seed names, and drops redundant seeds`() {
    writeFixture()
    writeSeedCorpus()

    val result = runner("fuzzCodecMinimize").build()
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "surviving seed must keep its committed name")
    assertEquals("novel", corpus.resolve("9f8e7d").readText(), "novel input not adopted")
    assertFalse(corpus.resolve("seedB").exists(), "redundant seed not removed")
    assertTrue(result.output.contains("corpus minimized 2 -> 2 file(s)"), result.output)
    assertTrue(result.output.contains("1 newly adopted, 1 redundant removed"), result.output)
  }

  @Test
  fun `minimize cannot commit a failed merge when JavaExec ignores its exit value`() {
    writeFixture(buildTail = """
      tasks.named<JavaExec>("fuzzCodecMinimize") {
        isIgnoreExitValue = true
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "fake-merge-mode.txt").writeText("fail\n")
    val corpus = File(fixtureDir, "corpus/codec")

    val failed = runner("fuzzCodecMinimize").buildAndFail()

    assertTrue(failed.output.contains("non-zero exit value 5"), failed.output)
    assertEquals("alpha", corpus.resolve("seedA").readText())
    assertEquals("beta-longer", corpus.resolve("seedB").readText())
    assertFalse(corpus.resolve("9f8e7d").exists(), "a failed merge committed its partial staging")
  }

  @Test
  fun `adoptLocalCorpus adds the local finds as a merge source only when requested`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "build/fuzz/codec-corpus").apply { mkdirs() }
      .resolve("local-find").writeText("from-local")
    val corpus = File(fixtureDir, "corpus/codec")

    // without the flag the local dir is never passed to the merge, however non-empty
    runner("fuzzCodecMinimize").build()
    assertFalse(corpus.resolve("feedface").exists(), "local corpus must not be a source by default")

    // reset the corpus (the first merge dropped seedB) and adopt deliberately
    corpus.resolve("9f8e7d").delete()
    writeSeedCorpus()
    val adopted = runner("fuzzCodecMinimize", "-PadoptLocalCorpus").build()
    assertEquals("from-local", corpus.resolve("feedface").readText(), "local find not folded into the merge")
    assertTrue(adopted.output.contains("2 newly adopted"), adopted.output)
  }

  @Test
  fun `minimize refuses an empty merge, a missing corpus, and an undeclared one`() {
    writeFixture()
    writeSeedCorpus()

    // an empty merge result must never replace the committed corpus
    File(fixtureDir, "fake-merge-mode.txt").writeText("empty")
    val empty = runner("fuzzCodecMinimize").buildAndFail()
    assertTrue(empty.output.contains("the merge produced an empty corpus — refusing to touch"), empty.output)
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "committed corpus touched by a refused merge")
    assertEquals("beta-longer", corpus.resolve("seedB").readText(), "committed corpus touched by a refused merge")

    val undeclared = runner("fuzzPlainMinimize").buildAndFail()
    assertTrue(
      undeclared.output.contains("declares no seedCorpus — nothing to minimize into"),
      undeclared.output
    )

    val missing = runner("fuzzHollowMinimize").buildAndFail()
    assertTrue(
      missing.output.contains("missing or empty — a merge cannot start from nothing"),
      missing.output
    )
  }

  @Test
  fun `fuzz targets honor configurable build-wide slots across projects with cache reuse`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "parallel-fuzz-lock-smoke-test"
        include("b")
      """.trimIndent() + "\n"
    )
    fun writeProject(projectDir: File, targets: List<String>) {
      projectDir.mkdirs()
      val projectLabel = if (projectDir == fixtureDir) "root" else projectDir.name
      val registrations = targets.joinToString("\n") { target ->
        """
          fuzz.register("$target") {
            targetClass = "com.example.${target.replaceFirstChar(Char::uppercase)}Fuzz"
          }
        """.trimIndent()
      }
      val taskWiring = targets.joinToString("\n") { target ->
        val taskName = "fuzz" + target.replaceFirstChar(Char::uppercase)
        """
          tasks.named<JavaExec>("$taskName") {
            mainClass = "com.example.FakeParallelFuzz"
            classpath = sourceSets["main"].output
            jvmArgs = listOf(
              "-DparallelFuzzSlotDir=${fixtureDir.resolve("parallel-fuzz-slots").absolutePath}",
              "-DparallelFuzzName=$projectLabel:$taskName",
              "-DparallelFuzzCapacity=" +
                  providers.gradleProperty("maxParallelFuzzTargets").orElse("1").get(),
            )
          }
        """.trimIndent()
      }
      File(projectDir, "build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          repositories { mavenCentral() }

          hardening {
            $registrations
          }

          $taskWiring
        """.trimIndent() + "\n"
      )
      val source = File(projectDir, "src/main/java/com/example/FakeParallelFuzz.java")
      source.parentFile.mkdirs()
      source.writeText(
        """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardOpenOption;

          public final class FakeParallelFuzz {
            public static void main(String[] args) throws Exception {
              Path stateDir = Path.of(System.getProperty("parallelFuzzSlotDir"));
              Path events = stateDir.resolveSibling("parallel-fuzz.events");
              Path overlapSeen = stateDir.resolveSibling("parallel-fuzz-overlap-seen");
              String name = System.getProperty("parallelFuzzName");
              int capacity = Integer.parseInt(System.getProperty("parallelFuzzCapacity"));
              Files.createDirectories(stateDir);
              Path counterLock = stateDir.resolve("counter.lock");
              Path counter = stateDir.resolve("active");
              int activeAfterStart;
              try (var channel = java.nio.channels.FileChannel.open(counterLock,
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                  var ignored = channel.lock()) {
                int active = Files.exists(counter)
                    ? Integer.parseInt(Files.readString(counter).trim()) : 0;
                activeAfterStart = active + 1;
                Files.writeString(counter, Integer.toString(activeAfterStart),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                if (activeAfterStart >= 2) {
                  Files.writeString(overlapSeen, "observed\n",
                      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
              }
              Files.writeString(events, "start " + name + "\n",
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              try {
                if (activeAfterStart > capacity) {
                  throw new IllegalStateException(
                      "fuzz concurrency exceeded " + capacity + ": " + activeAfterStart);
                }
                Thread.sleep(600);
              } finally {
                Files.writeString(events, "end " + name + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                try (var channel = java.nio.channels.FileChannel.open(counterLock,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                  int active = Integer.parseInt(Files.readString(counter).trim());
                  Files.writeString(counter, Integer.toString(active - 1),
                      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
              }
              System.err.println("Done 17 runs in 1 second(s)");
            }
          }
        """.trimIndent() + "\n"
      )
    }
    writeProject(fixtureDir, listOf("alpha", "beta"))
    writeProject(File(fixtureDir, "b"), listOf("gamma"))

    fun runAndAssert(capacity: Int, expectReuse: Boolean) {
      val eventsFile = File(fixtureDir, "parallel-fuzz.events")
      if (eventsFile.exists()) assertTrue(eventsFile.delete(), "could not reset fuzz event log")
      val overlapSeen = File(fixtureDir, "parallel-fuzz-overlap-seen")
      if (overlapSeen.exists()) assertTrue(overlapSeen.delete(), "could not reset overlap marker")
      File(fixtureDir, "parallel-fuzz-slots").deleteRecursively()
      val result = runner(
        ":fuzzAlpha",
        ":fuzzBeta",
        ":b:fuzzGamma",
        "-PmaxParallelFuzzTargets=$capacity",
        "--parallel",
        "--max-workers=4",
      ).build()

      assertFalse(result.output.contains("fuzz concurrency exceeded"), result.output)
      if (expectReuse) assertTrue(result.output.contains("Configuration cache entry reused."), result.output)
      val events = eventsFile.readLines()
      assertEquals(6, events.size, "three fuzz tasks must record complete intervals: $events")
      var active = 0
      var observedMaximum = 0
      events.forEach { event ->
        if (event.startsWith("start ")) {
          active++
          observedMaximum = maxOf(observedMaximum, active)
        } else {
          assertTrue(event.startsWith("end "), events.toString())
          active--
        }
        assertTrue(active in 0..capacity, "configured fuzz capacity $capacity was exceeded: $events")
      }
      assertEquals(0, active, events.toString())
      assertEquals(capacity, observedMaximum, "configured fuzz capacity was not observed: $events")
      assertEquals(capacity > 1, overlapSeen.isFile, "overlap marker disagreed with capacity $capacity")
      assertEquals(
        setOf("root:fuzzAlpha", "root:fuzzBeta", "b:fuzzGamma"),
        events.filter { it.startsWith("start ") }.map { it.removePrefix("start ") }.toSet(),
      )
    }

    runAndAssert(capacity = 1, expectReuse = false)
    runAndAssert(capacity = 2, expectReuse = false)
    runAndAssert(capacity = 2, expectReuse = true)
  }
}
