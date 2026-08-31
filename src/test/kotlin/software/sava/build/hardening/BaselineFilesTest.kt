package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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
  fun `atomic replace preserves an existing POSIX file mode`() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
    val target = File(tempDir, "encoding-accepted.csv").apply { writeText("old\n") }
    val expected = PosixFilePermissions.fromString("rw-r--r--")
    Files.setPosixFilePermissions(target.toPath(), expected)

    BaselineFiles.writeAtomically(target, "new\n")

    assertEquals("new\n", target.readText())
    assertEquals(expected, Files.getPosixFilePermissions(target.toPath()))

    val readOnly = PosixFilePermissions.fromString("r--r--r--")
    Files.setPosixFilePermissions(target.toPath(), readOnly)
    BaselineFiles.writeAtomically(target, "newer\n")
    assertEquals("newer\n", target.readText())
    assertEquals(readOnly, Files.getPosixFilePermissions(target.toPath()))
  }

  @Test
  fun `a new atomic file uses the provider's normal readable creation mode`() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
    val parent = File(tempDir, "config/pitest").apply { mkdirs() }
    val ordinary = parent.resolve("ordinary-file")
    Files.createFile(ordinary.toPath())
    val expected = Files.getPosixFilePermissions(ordinary.toPath())
    Files.delete(ordinary.toPath())
    val target = parent.resolve("encoding-accepted.csv")

    BaselineFiles.writeAtomically(target, "new record\n")

    val actual = Files.getPosixFilePermissions(target.toPath())
    assertEquals(expected, actual, "atomic creation must follow normal create-file and umask semantics")
    assertTrue(PosixFilePermission.OWNER_READ in actual, "the creating user cannot read the new record")
    assertTrue(PosixFilePermission.OWNER_WRITE in actual, "the creating user cannot update the new record")
    assertTrue(actual.none { it.name.endsWith("EXECUTE") }, "a mutation record became executable")
  }

  @Test
  fun `an empty write is honoured rather than skipped`() {
    // The low-level single-file primitive must not silently skip empty content. Record
    // writers that canonicalize an empty baseline to absence use a null multi-file write.
    val target = File(tempDir, "encoding-accepted.csv")
    BaselineFiles.writeAtomically(target, "stale\n")

    BaselineFiles.writeAtomically(target, "")

    assertTrue(target.isFile, "the target must still exist")
    assertEquals("", target.readText())
  }

  @Test
  fun `last-success receipt is preserved only beneath a restored incomplete marker`() {
    val receipt = File(tempDir, ".pitest-history/receipt.tsv").apply {
      parentFile.mkdirs()
      writeText("last success\n")
    }
    val marker = receipt.parentFile.resolve("receipt.running")

    BaselineFiles.preserveReceiptUnderIncompleteMarker(
      tempDir,
      receipt,
      marker,
      "refused\tsentinel disappeared\n",
    )

    assertEquals("last success\n", receipt.readText())
    assertEquals("refused\tsentinel disappeared\n", marker.readText())
  }

  @Test
  fun `receipt path interference cannot prevent restoring the incomplete marker`() {
    val receipt = File(tempDir, ".pitest-history/receipt.tsv").apply { mkdirs() }
    val marker = receipt.parentFile.resolve("receipt.running")

    BaselineFiles.preserveReceiptUnderIncompleteMarker(
      tempDir,
      receipt,
      marker,
      "refused\treceipt changed\n",
    )

    assertTrue(receipt.isDirectory)
    assertEquals("refused\treceipt changed\n", marker.readText())
  }

  @Test
  fun `failed receipt publication restores exact prior bytes beneath its marker`() {
    val receipt = File(tempDir, ".pitest-history/receipt.tsv").apply {
      parentFile.mkdirs()
      writeText("new attempt\n")
    }
    val marker = receipt.parentFile.resolve("receipt.running")
    val prior = byteArrayOf(0, 1, 2, 127, -1)

    BaselineFiles.restoreReceiptSnapshotUnderIncompleteMarker(
      tempDir,
      receipt,
      marker,
      "refused\tpost-publication check\n",
      prior,
    )

    assertArrayEquals(prior, receipt.readBytes())
    assertEquals("refused\tpost-publication check\n", marker.readText())
  }

  @Test
  fun `failed first receipt publication removes its successor beneath its marker`() {
    val receipt = File(tempDir, ".pitest-history/receipt.tsv").apply {
      parentFile.mkdirs()
      writeText("new attempt\n")
    }
    val marker = receipt.parentFile.resolve("receipt.running")

    BaselineFiles.restoreReceiptSnapshotUnderIncompleteMarker(
      tempDir,
      receipt,
      marker,
      "refused\tpost-publication check\n",
      null,
    )

    assertFalse(receipt.exists())
    assertEquals("refused\tpost-publication check\n", marker.readText())
  }

  @Test
  fun `unrestorable incomplete marker deletes the receipt fail closed`() {
    val receipt = File(tempDir, ".pitest-history/receipt.tsv").apply {
      parentFile.mkdirs()
      writeText("last success\n")
    }
    val marker = receipt.parentFile.resolve("receipt.running").apply { mkdirs() }

    assertThrows(Exception::class.java) {
      BaselineFiles.preserveReceiptUnderIncompleteMarker(
        tempDir,
        receipt,
        marker,
        "refused\tunwritable marker\n",
      )
    }

    assertFalse(receipt.exists(), "unmarked failed attempt left apparently current evidence")
    assertTrue(marker.isDirectory, "fallback followed or replaced a non-regular marker")
  }

  @Test
  fun `a multi-file write rolls every earlier target back when a later path fails`() {
    val existing = File(tempDir, "record/version").apply {
      parentFile.mkdirs()
      writeText("old version\n")
    }
    val newlyCreated = File(tempDir, "record/toolchain")
    val blockingParent = File(tempDir, "not-a-directory").apply { writeText("block") }
    val impossible = File(blockingParent, "timeouts.csv")

    assertThrows(Exception::class.java) {
      BaselineFiles.writeAllAtomically(listOf(
        BaselineFiles.Write(existing, "new version\n"),
        BaselineFiles.Write(newlyCreated, "new toolchain\n"),
        BaselineFiles.Write(impossible, "new timeout set\n"),
      ))
    }

    assertEquals("old version\n", existing.readText(), "an overwritten target was not restored")
    assertFalse(newlyCreated.exists(), "a newly-created earlier target survived rollback")
    assertEquals("block", blockingParent.readText())
    assertTrue(
      tempDir.walkTopDown().none { it.name.endsWith(".tmp") },
      "multi-file rollback leaked a staging file",
    )
  }

  @Test
  fun `rollback of a committed deletion restores its POSIX file mode`() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
    val deletedFirst = File(tempDir, "record/accepted.csv").apply {
      parentFile.mkdirs()
      writeText("old accepted record\n")
    }
    val expected = PosixFilePermissions.fromString("rw-r-----")
    Files.setPosixFilePermissions(deletedFirst.toPath(), expected)
    val blockingParent = File(tempDir, "not-a-directory").apply { writeText("block") }
    val impossible = File(blockingParent, "toolchain.tsv")

    assertThrows(Exception::class.java) {
      BaselineFiles.writeAllAtomically(listOf(
        BaselineFiles.Write(deletedFirst, null),
        BaselineFiles.Write(impossible, "new toolchain\n"),
      ))
    }

    assertEquals("old accepted record\n", deletedFirst.readText())
    assertEquals(expected, Files.getPosixFilePermissions(deletedFirst.toPath()))
  }

  @Test
  fun `empty accepted record deletion also retires only orphan mutation provenance`() {
    val baseline = File(tempDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText("${BaselineDocument.CURRENT_HEADER}\n\n")
    }
    val timeouts = File(baseline.parentFile, "encoding-timeouts.csv")
    val stamp = File(baseline.parentFile, "encoding-pitest-version").apply { writeText("1.20.3\n") }
    val toolchain = File(baseline.parentFile, "encoding-pitest-toolchain.tsv").apply {
      writeText("fixture toolchain\n")
    }

    val removed = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(
      baseline,
      timeouts,
      stamp,
      toolchain,
    )

    assertTrue(removed.baselineRemoved)
    assertTrue(removed.orphanVersionStampRemoved)
    assertTrue(removed.orphanToolchainRecordRemoved)
    assertFalse(baseline.exists())
    assertFalse(stamp.exists())
    assertFalse(toolchain.exists())

    baseline.writeText("\n")
    timeouts.writeText("com.example.Codec,encode,MathMutator\n")
    stamp.writeText("1.20.3\n")
    toolchain.writeText("fixture toolchain\n")
    val audited = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(
      baseline,
      timeouts,
      stamp,
      toolchain,
    )

    assertTrue(audited.baselineRemoved)
    assertFalse(audited.orphanVersionStampRemoved)
    assertFalse(audited.orphanToolchainRecordRemoved)
    assertTrue(stamp.isFile, "the timeout audit still needs PIT-version provenance")
    assertTrue(toolchain.isFile, "the timeout audit still needs mutation-toolchain provenance")
  }

  @Test
  fun `orphan mutation provenance sidecars are reported independently`() {
    val baseline = File(tempDir, "encoding-accepted.csv").apply { writeText("\n") }
    val timeouts = File(tempDir, "encoding-timeouts.csv")
    val stamp = File(tempDir, "encoding-pitest-version")
    val toolchain = File(tempDir, "encoding-pitest-toolchain.tsv").apply {
      writeText("fixture toolchain\n")
    }

    val removed = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(
      baseline,
      timeouts,
      stamp,
      toolchain,
    )

    assertTrue(removed.baselineRemoved)
    assertFalse(removed.orphanVersionStampRemoved)
    assertTrue(removed.orphanToolchainRecordRemoved)
    assertFalse(toolchain.exists())
  }

  @Test
  fun `empty accepted record deletion refuses rows comments and malformed evidence`() {
    val baseline = File(tempDir, "encoding-accepted.csv")
    val timeouts = File(tempDir, "encoding-timeouts.csv")
    val stamp = File(tempDir, "encoding-pitest-version")
    val toolchain = File(tempDir, "encoding-pitest-toolchain.tsv")

    listOf(
      "com.example.Codec,encode,MathMutator,SURVIVED\n",
      "# reviewed empty suite\n",
      "not,a,baseline\n",
    ).forEach { content ->
      baseline.writeText(content)
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(baseline, timeouts, stamp, toolchain)
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

  @Test
  fun `record paths refuse a symbolic link below the trusted project boundary`() {
    val project = tempDir.resolve("project").apply { mkdirs() }
    val external = tempDir.resolve("external").apply { mkdirs() }
    Files.createSymbolicLink(project.resolve("config").toPath(), external.toPath())
    val target = project.resolve("config/pitest/encoding-accepted.csv")

    val failure = assertThrows(IllegalArgumentException::class.java) {
      BaselineFiles.requireRegularFileOrMissing(project, target)
    }

    assertTrue(failure.message.orEmpty().contains("symbolic-link component"), failure.message)
    assertFalse(external.resolve("pitest/encoding-accepted.csv").exists())
  }

  @Test
  fun `record paths cannot escape the trusted project boundary lexically`() {
    val project = tempDir.resolve("project").apply { mkdirs() }
    val target = project.resolve("../external/encoding-accepted.csv")

    val failure = assertThrows(IllegalArgumentException::class.java) {
      BaselineFiles.requireRegularFileOrMissing(project, target)
    }

    assertTrue(failure.message.orEmpty().contains("escapes trusted project directory"), failure.message)
  }
}
