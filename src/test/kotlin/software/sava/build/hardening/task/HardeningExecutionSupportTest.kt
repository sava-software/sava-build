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
    assertTrue("--features=+arcmutate_history" in arguments)
    assertFalse(arguments.any { it == "--targetClasses=example.*" })

    val fresh = HardeningCommandLines.pitest(spec.copy(mutateOnly = null, historyActive = false))
    assertTrue("--targetClasses=example.*" in fresh)
    assertFalse(fresh.any { it.startsWith("--history") || it == "--features=+arcmutate_history" })
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
        timeoutFactor = "1.25",
        timeoutConst = 4000,
        historyActive = false,
        historyFile = tempDir.resolve("history"),
      )
    )

    assertFalse(arguments.any { it.startsWith("--excludedClasses=") })
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

    assertEquals(1, filters.closeAndCount())
    assertEquals("PIT >> INFO : MINION : common\nplain\n", stdout.toString(Charsets.UTF_8))
    assertEquals("PIT >> INFO : MINION : final tail", stderr.toString(Charsets.UTF_8))
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
