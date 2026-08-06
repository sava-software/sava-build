package software.sava.build.hardening.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class HardeningExecutionSupportTest {

  @Test
  fun `a mutation scope selects the isolated report tree and blank values are refused`() {
    val full = tempDir.resolve("reports/pitest/encoding")
    val scoped = tempDir.resolve("reports/pitest-scoped/encoding")

    assertEquals(full, PitestReportDirectories.select(full, scoped, null))
    listOf("", "   ").forEach { value ->
      val failure = assertThrows(org.gradle.api.GradleException::class.java) {
        PitestReportDirectories.select(full, scoped, value)
      }
      assertTrue(failure.message.orEmpty().contains("requires a nonblank class glob"))
    }
    assertEquals(
      scoped,
      PitestReportDirectories.select(full, scoped, " com.example.Codec "),
    )
  }

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `PIT arguments preserve scope exclusions and history semantics`() {
    val history = tempDir.resolve("history/encoding.hist").also {
      it.parentFile.mkdirs()
      it.writeText("prior")
    }
    val spec = HardeningCommandLines.Pitest(
      applicationClasspath = listOf(tempDir.resolve("classes"), tempDir.resolve("dep.jar")),
      targetClasses = listOf("example.*"),
      mutateOnly = " example.Codec* ",
      excludedClasses = listOf("example.Fuzz*"),
      targetTests = "example.*Test*",
      sourceDirectories = listOf(tempDir.resolve("src/main/java")),
      reportDirectory = tempDir.resolve("reports/pitest/encoding"),
      projectBaseDirectory = tempDir,
      mutators = "STRONGER",
      outputFormats = listOf("HTML", "XML", "CSV"),
      timestampedReports = false,
      threads = 4,
      minionJvmArgs = listOf("-Xmx1g", "-Dlabels=alpha,beta"),
      timeoutFactor = "1.25",
      timeoutConst = 4000,
      historyActive = true,
      historyFile = history,
    )

    val arguments = HardeningCommandLines.pitest(spec)

    assertTrue("--targetClasses=example.Codec*" in arguments)
    assertTrue("--excludedClasses=example.Fuzz*" in arguments)
    assertTrue("--historyInputLocation=${history.absolutePath}" in arguments)
    assertTrue("--historyOutputLocation=${history.absolutePath}" in arguments)
    assertTrue("--projectBase=${tempDir.absolutePath}" in arguments)
    assertTrue("--jvmArgs=-Xmx1g,{-Dlabels=alpha,beta}" in arguments)
    assertTrue("--features=+arcmutate_history" in arguments)
    assertFalse(arguments.any { it == "--targetClasses=example.*" })

    val fresh = HardeningCommandLines.pitest(spec.copy(mutateOnly = null, historyActive = false))
    assertTrue("--targetClasses=example.*" in fresh)
    assertFalse(fresh.any { it.startsWith("--history") || it == "--features=+arcmutate_history" })

    val isolated = HardeningCommandLines.pitest(
      spec.copy(historyActive = false, mutationUnitSize = 1)
    )
    assertTrue("--mutationUnitSize=1" in isolated)
    assertFalse(arguments.any { it.startsWith("--mutationUnitSize=") })
    assertThrows(IllegalArgumentException::class.java) {
      HardeningCommandLines.pitest(spec.copy(mutationUnitSize = 1))
    }
    listOf(null, "   ").forEach { scope ->
      assertThrows(IllegalArgumentException::class.java) {
        HardeningCommandLines.pitest(
          spec.copy(mutateOnly = scope, historyActive = false, mutationUnitSize = 1)
        )
      }
    }
  }

  @Test
  fun `PIT arguments omit an empty exclusion rather than emitting an empty glob`() {
    val arguments = HardeningCommandLines.pitest(
      HardeningCommandLines.Pitest(
        applicationClasspath = emptyList(),
        targetClasses = listOf("example.Codec"),
        mutateOnly = null,
        excludedClasses = emptyList(),
        targetTests = "example.CodecTest",
        sourceDirectories = emptyList(),
        reportDirectory = tempDir.resolve("report"),
        projectBaseDirectory = tempDir,
        mutators = "DEFAULTS",
        outputFormats = listOf("CSV"),
        timestampedReports = false,
        threads = 1,
        minionJvmArgs = emptyList(),
        timeoutFactor = "1.25",
        timeoutConst = 4000,
        historyActive = false,
        historyFile = tempDir.resolve("history"),
      )
    )

    assertFalse(arguments.any { it.startsWith("--excludedClasses=") })
    assertFalse(arguments.any { it.startsWith("--jvmArgs=") })
  }

  @Test
  fun `PIT minion JVM arguments reject values its parser and argfile cannot preserve`() {
    val base = HardeningCommandLines.Pitest(
      applicationClasspath = emptyList(),
      targetClasses = listOf("example.Codec"),
      mutateOnly = null,
      excludedClasses = emptyList(),
      targetTests = "example.CodecTest",
      sourceDirectories = emptyList(),
      reportDirectory = tempDir.resolve("report"),
      projectBaseDirectory = tempDir,
      mutators = "DEFAULTS",
      outputFormats = listOf("CSV"),
      timestampedReports = false,
      threads = 1,
      minionJvmArgs = emptyList(),
      timeoutFactor = "1.25",
      timeoutConst = 4000,
      historyActive = false,
      historyFile = tempDir.resolve("history"),
    )

    fun assertRejected(argument: String, message: String) {
      val failure = assertThrows(IllegalArgumentException::class.java) {
        HardeningCommandLines.pitest(base.copy(minionJvmArgs = listOf(argument)))
      }
      assertTrue(failure.message.orEmpty().contains(message), failure.message)
    }

    assertRejected("", "must not be blank")
    assertRejected("-Dlabel=alpha beta", "cannot contain whitespace")
    assertRejected("@minion.options", "must be JVM options beginning with '-'")
    assertRejected("-Dvalue={unsafe", "cannot contain braces")
    assertRejected("-Dvalue=unsafe}", "cannot contain braces")
    assertRejected("-Dtag=alpha#beta", "argument files interpret them as syntax")
    assertRejected("-Dtag='alpha'", "argument files interpret them as syntax")
    assertRejected("-Dtag=\"alpha\"", "argument files interpret them as syntax")
    assertRejected("-Dpath=C:\\temp", "argument files interpret them as syntax")
    assertRejected("-Dvalue=alpha\u0000beta", "must be single-line values")
  }

  @Test
  fun `fuzz command lines keep writable corpus first and local adoption conditional`() {
    val local = tempDir.resolve("local")
    val seed = tempDir.resolve("seed")
    val staging = tempDir.resolve("staging")

    assertIterableEquals(
      listOf(
        "--target_class=example.CodecFuzz",
        "-max_total_time=60",
        "-max_len=512",
        local.absolutePath,
        seed.absolutePath,
      ),
      HardeningCommandLines.fuzzRun(
        HardeningCommandLines.FuzzRun("example.CodecFuzz", 60, 512, local, seed)
      )
    )
    assertIterableEquals(
      listOf(
        "--target_class=example.CodecFuzz",
        "-merge=1",
        staging.absolutePath,
        seed.absolutePath,
        local.absolutePath,
      ),
      HardeningCommandLines.fuzzMinimize(
        HardeningCommandLines.FuzzMinimize("example.CodecFuzz", null, staging, seed, local)
      )
    )
  }

  @Test
  fun `minion filter deduplicates across streams and flushes unterminated tails`() {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val filters = MinionOutputFilters(stdout, stderr)

    filters.standardOutput.write("PIT >> INFO : MINION : common\nplain\n".toByteArray())
    filters.errorOutput.write("PIT >> INFO : MINION : common\n".toByteArray())
    filters.errorOutput.write("PIT >> INFO : MINION : final tail".toByteArray())

    val summary = filters.closeAndSummarize()
    assertEquals(1, summary.suppressedMinionLines)
    assertEquals(null, summary.slowestTest)
    assertEquals("PIT >> INFO : MINION : common\nplain\n", stdout.toString(Charsets.UTF_8))
    assertEquals("PIT >> INFO : MINION : final tail", stderr.toString(Charsets.UTF_8))
  }

  @Test
  fun `PIT output captures the slowest coverage-phase test across streams and replay lines`() {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val filters = MinionOutputFilters(stdout, stderr)
    val slower =
      "Slowest test ([engine:junit-jupiter]/[class:example.CodecTest]/" +
          "[method:roundTrip(java.lang.String)]) took 416 ms"

    filters.standardOutput.write(
      "12:00:00 PIT >> INFO : Slowest test (example.FastTest) took 249 ms\n".toByteArray()
    )
    filters.errorOutput.write("12:00:01 PIT >> INFO : ${slower.take(48)}".toByteArray())
    filters.errorOutput.write("${slower.drop(48)}\n".toByteArray())
    // PIT replays its coverage statistics later with a `> ` prefix. Capture one
    // observation rather than treating the replay as a second advisory.
    filters.standardOutput.write("> $slower\n".toByteArray())
    filters.standardOutput.write(
      "PIT >> INFO : MINION : Slowest test (forged.MinionTest) took 9000 ms\n".toByteArray()
    )
    filters.standardOutput.write(
      "12:00:02 PIT >> INFO : Slowest test (overflow.Test) took 999999999999999999999 ms\n"
        .toByteArray()
    )
    filters.standardOutput.write("Slowest test (malformed) took nope ms\n".toByteArray())
    val unterminated = "12:00:03 PIT >> INFO : ${slower.replace("416 ms", "417 ms")}"
    filters.errorOutput.write(unterminated.toByteArray())

    val summary = filters.closeAndSummarize()

    assertEquals(0, summary.suppressedMinionLines)
    assertEquals(
      PitestSlowestTest(
        "[engine:junit-jupiter]/[class:example.CodecTest]/" +
            "[method:roundTrip(java.lang.String)]",
        417,
      ),
      summary.slowestTest,
    )
    assertTrue(stdout.toString(Charsets.UTF_8).contains("> $slower"))
    assertTrue(stderr.toString(Charsets.UTF_8).contains(unterminated))
  }

  @Test
  fun `coverage-phase cost advisory threshold is inclusive`() {
    assertFalse(shouldAdvisePitestCoverageTestCost(249))
    assertTrue(shouldAdvisePitestCoverageTestCost(250))
  }

  @Test
  fun `corpus commit preserves names for surviving content and adopts only new content`() {
    val corpus = tempDir.resolve("corpus").apply { mkdirs() }
    val staging = tempDir.resolve("staging").apply { mkdirs() }
    corpus.resolve("meaningful-name").writeText("keep")
    corpus.resolve("redundant").writeText("drop")
    staging.resolve("hash-of-keep").writeText("keep")
    staging.resolve("new-hash").writeText("new")

    val stats = FuzzCorpusCommit.commit(staging, corpus)

    assertTrue(corpus.resolve("meaningful-name").isFile)
    assertFalse(corpus.resolve("hash-of-keep").exists())
    assertFalse(corpus.resolve("redundant").exists())
    assertEquals("new", corpus.resolve("new-hash").readText())
    assertEquals(2, stats.beforeFiles)
    assertEquals(2, stats.afterFiles)
    assertEquals(1, stats.adoptedFiles)
    assertEquals(1, stats.removedFiles)
  }

  @Test
  fun `corpus commit rejects a novel staged name collision without touching committed seeds`() {
    val corpus = tempDir.resolve("collision-corpus").apply { mkdirs() }
    val staging = tempDir.resolve("collision-staging").apply { mkdirs() }
    corpus.resolve("same-name").writeText("accepted evidence")
    corpus.resolve("other-name").writeText("other evidence")
    staging.resolve("same-name").writeText("novel different evidence")

    val failure = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusCommit.commit(staging, corpus)
    }

    assertTrue(failure.message.orEmpty().contains("collides with an existing different-content seed"))
    assertEquals("accepted evidence", corpus.resolve("same-name").readText())
    assertEquals("other evidence", corpus.resolve("other-name").readText())
    assertEquals(listOf("other-name", "same-name"), corpus.list()!!.sorted())
    assertEquals(
      emptyList<String>(),
      tempDir.list()!!.filter { it.startsWith(".collision-corpus.") },
    )
  }

  @Test
  fun `corpus commit chooses deterministic names for duplicate content`() {
    val corpus = tempDir.resolve("duplicate-corpus").apply { mkdirs() }
    val staging = tempDir.resolve("duplicate-staging").apply { mkdirs() }
    corpus.resolve("z-original").writeText("surviving content")
    corpus.resolve("a-original").writeText("surviving content")
    staging.resolve("renamed-survivor").writeText("surviving content")
    staging.resolve("z-novel").writeText("novel content")
    staging.resolve("a-novel").writeText("novel content")

    val stats = FuzzCorpusCommit.commit(staging, corpus)

    assertEquals(listOf("a-novel", "a-original"), corpus.list()!!.sorted())
    assertEquals("surviving content", corpus.resolve("a-original").readText())
    assertEquals("novel content", corpus.resolve("a-novel").readText())
    assertEquals(2, stats.beforeFiles)
    assertEquals(2, stats.afterFiles)
    assertEquals(1, stats.adoptedFiles)
    assertEquals(1, stats.removedFiles)
  }

  @Test
  fun `minimize paths reject parent child overlap`() {
    val seed = tempDir.resolve("overlap-seed").apply { mkdirs() }
    seed.resolve("seed").writeText("evidence")
    val local = tempDir.resolve("overlap-local")

    val childFailure = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusPaths.validateForMinimize(seed, seed.resolve("staging"), local)
    }
    assertTrue(childFailure.message.orEmpty().contains("must not overlap"))

    val parentFailure = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusPaths.validateForMinimize(seed, tempDir, local)
    }
    assertTrue(parentFailure.message.orEmpty().contains("must not overlap"))
  }

  @Test
  fun `fuzz run refuses to use the committed seed as its writable corpus`() {
    val seed = tempDir.resolve("run-seed").apply { mkdirs() }
    seed.resolve("seed").writeText("evidence")

    val same = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusPaths.prepareRun(seed, seed)
    }
    assertTrue(same.message.orEmpty().contains("must not overlap"))

    val child = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusPaths.prepareRun(seed.resolve("local"), seed)
    }
    assertTrue(child.message.orEmpty().contains("must not overlap"))
    assertEquals("evidence", seed.resolve("seed").readText())
  }

  @Test
  fun `minimize paths reject a symbolic link alias before cleanup`() {
    val seed = tempDir.resolve("symlink-seed").apply { mkdirs() }
    seed.resolve("seed").writeText("evidence")
    val actualStaging = tempDir.resolve("actual-staging").apply { mkdirs() }
    actualStaging.resolve("sentinel").writeText("must remain")
    val stagingAlias = tempDir.resolve("staging-alias")
    try {
      Files.createSymbolicLink(stagingAlias.toPath(), actualStaging.toPath())
    } catch (unsupported: Exception) {
      assumeTrue(false, "symbolic links are unavailable in this test environment: $unsupported")
    }

    val failure = assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusPaths.validateForMinimize(seed, stagingAlias, tempDir.resolve("symlink-local"))
    }

    assertTrue(failure.message.orEmpty().contains("symbolic-link path component"))
    assertEquals("must remain", actualStaging.resolve("sentinel").readText())
  }

  @Test
  fun `empty minimized corpus refuses without touching committed seeds`() {
    val corpus = tempDir.resolve("corpus").apply { mkdirs() }
    val staging = tempDir.resolve("staging").apply { mkdirs() }
    corpus.resolve("seed").writeText("evidence")

    assertThrows(IllegalArgumentException::class.java) {
      FuzzCorpusCommit.commit(staging, corpus)
    }
    assertEquals("evidence", corpus.resolve("seed").readText())
  }
}
