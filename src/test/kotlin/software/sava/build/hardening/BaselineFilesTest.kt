package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Unit tests for the atomic baseline write. The interruption guarantee itself cannot be
 * asserted in-process (it needs a killed daemon mid-write), so what is pinned here is
 * everything observable: the target's content after the move, that no temp file is left
 * behind for the next verify to read, and that a replace is whole rather than an
 * overlay of the previous, longer content.
 */
class BaselineFilesTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `a write creates missing parents and leaves no temp file behind`() {
    val target = File(tempDir, "config/pitest/encoding-accepted.csv")

    BaselineFiles.writeAtomically(target, "com.example.Codec,encode,12,MathMutator,SURVIVED\n")

    assertEquals("com.example.Codec,encode,12,MathMutator,SURVIVED\n", target.readText())
    assertEquals(
        listOf("encoding-accepted.csv"),
        target.parentFile.listFiles()!!.map { it.name },
        "the sibling temp file must not survive the move"
    )
  }

  @Test
  fun `replacing a longer baseline leaves no tail of the previous content`() {
    // the failure this guards against is a partial overlay reading as a valid, shorter
    // baseline — a truncated ratchet that still parses
    val target = File(tempDir, "encoding-accepted.csv")
    val long = (1..20).joinToString("\n", postfix = "\n") { "com.example.Codec,encode,$it,MathMutator,SURVIVED" }
    BaselineFiles.writeAtomically(target, long)
    assertEquals(20, target.readLines().size)

    BaselineFiles.writeAtomically(target, "com.example.Codec,decode,3,MathMutator,NO_COVERAGE\n")

    assertEquals(
        listOf("com.example.Codec,decode,3,MathMutator,NO_COVERAGE"),
        target.readLines(),
        "the replacement must be whole, not an overlay"
    )
    assertFalse(File(target.parentFile, "${target.name}.tmp").exists(), "temp file left behind")
  }

  @Test
  fun `an empty write is honoured rather than skipped`() {
    // a prune that drops every row writes an empty baseline; silently keeping the old
    // file would leave the ratchet gated on rows the run proved gone
    val target = File(tempDir, "encoding-accepted.csv")
    BaselineFiles.writeAtomically(target, "stale\n")

    BaselineFiles.writeAtomically(target, "")

    assertTrue(target.isFile, "the target must still exist")
    assertEquals("", target.readText())
  }

  @Test
  fun `empty accepted record deletion also retires only an orphan PIT stamp`() {
    val baseline = File(tempDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText("${BaselineDocument.CURRENT_HEADER}\n\n")
    }
    val timeouts = File(baseline.parentFile, "encoding-timeouts.csv")
    val stamp = File(baseline.parentFile, "encoding-pitest-version").apply { writeText("1.20.3\n") }

    val removed = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(baseline, timeouts, stamp)

    assertTrue(removed.baselineRemoved)
    assertTrue(removed.orphanVersionStampRemoved)
    assertFalse(baseline.exists())
    assertFalse(stamp.exists())

    baseline.writeText("\n")
    timeouts.writeText("com.example.Codec,encode,MathMutator\n")
    stamp.writeText("1.20.3\n")
    val audited = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(baseline, timeouts, stamp)

    assertTrue(audited.baselineRemoved)
    assertFalse(audited.orphanVersionStampRemoved)
    assertTrue(stamp.isFile, "the timeout audit still needs PIT-version provenance")
  }

  @Test
  fun `empty accepted record deletion refuses rows comments and malformed evidence`() {
    val baseline = File(tempDir, "encoding-accepted.csv")
    val timeouts = File(tempDir, "encoding-timeouts.csv")
    val stamp = File(tempDir, "encoding-pitest-version")

    listOf(
      "com.example.Codec,encode,MathMutator,SURVIVED\n",
      "# reviewed empty suite\n",
      "not,a,baseline\n",
    ).forEach { content ->
      baseline.writeText(content)
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(baseline, timeouts, stamp)
      }
      assertTrue(refusal.message.orEmpty().contains("refusing to delete non-empty accepted baseline"))
      assertEquals(content, baseline.readText())
    }
  }

  @Test
  fun `concurrent writers stage independently and publish only complete content`() {
    val target = File(tempDir, "encoding-accepted.csv")
    val payloads = (0 until 24).map { writer ->
      buildString {
        repeat(128) { row -> appendLine("com.example.C$writer,m$row,MathMutator,SURVIVED") }
      }
    }
    val ready = CountDownLatch(payloads.size)
    val start = CountDownLatch(1)
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())
    val writers = payloads.map { payload ->
      thread(start = true) {
        ready.countDown()
        start.await()
        try {
          BaselineFiles.writeAtomically(target, payload)
        } catch (t: Throwable) {
          failures.add(t)
        }
      }
    }

    ready.await()
    start.countDown()
    writers.forEach(Thread::join)

    assertTrue(failures.isEmpty(), "concurrent writer failure(s): $failures")
    assertTrue(target.readText() in payloads, "target contains a partial or foreign payload")
    assertTrue(
        target.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        "staging files leaked: ${target.parentFile.listFiles().orEmpty().map { it.name }}")
  }

  @Test
  fun `checked recursive deletion removes links without traversing their targets`() {
    val external = tempDir.resolve("external").apply { mkdirs() }
    val evidence = external.resolve("keep.txt").apply { writeText("keep") }
    val managed = tempDir.resolve("managed").apply { mkdirs() }
    Files.createSymbolicLink(managed.resolve("outside").toPath(), external.toPath())
    managed.resolve("local.txt").writeText("drop")

    assertTrue(BaselineFiles.deleteRecursivelyIfExists(managed))

    assertFalse(managed.exists())
    assertEquals("keep", evidence.readText(), "recursive cleanup must not follow a symlink")
  }
}
