package software.sava.build.hardening

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Baseline rewrites are atomic: content lands in a sibling temp file that is
 * moved over the target, so an interrupted refresh (a killed daemon, a stopped
 * task, a crash mid-write) leaves the previous baseline intact instead of a
 * truncated file the next verify reads as an empty ratchet
 * (casebook: the baseline truncated mid-write).
 * Falls back to a plain move on filesystems without atomic move.
 *
 * This is safe across process death (a kill, a stopped task, a dead daemon): the
 * rename is atomic and the surviving kernel keeps the page cache coherent. It is not
 * a power-loss guarantee — neither the temp contents nor the directory entry are
 * fsynced, so a crash could leave an atomically-renamed but still-empty file. The
 * threat model is interruption, not power loss, so that trade is deliberate.
 */
internal object BaselineFiles {

  data class EmptyAcceptedRecordRemoval(
    val baselineRemoved: Boolean,
    val orphanVersionStampRemoved: Boolean,
  )

  /** Checked deletion for state files whose continued presence changes semantics. */
  fun deleteIfExists(target: File): Boolean = Files.deleteIfExists(target.toPath())

  /**
   * Canonicalizes a whitespace-only accepted-baseline placeholder to absence and
   * retires its PIT-version stamp unless a timeout audit still needs that provenance.
   * The content check is repeated immediately before deletion so the task does not
   * act only on its earlier project-wide preflight parse. Like the other baseline
   * writers, this assumes one Gradle writer owns the checkout; the read/delete pair
   * is not a cross-process compare-and-delete primitive.
   */
  fun deleteSemanticallyEmptyAcceptedRecord(
    baseline: File,
    timeouts: File,
    pitestVersionStamp: File,
  ): EmptyAcceptedRecordRemoval {
    require(baseline.isFile) { "accepted baseline does not exist: $baseline" }
    require(!BaselineDocument.parse(baseline.readText()).hasSubstantiveContent) {
      "refusing to delete non-empty accepted baseline: $baseline"
    }
    val baselineRemoved = deleteIfExists(baseline)
    val stampRemoved = !timeouts.isFile && deleteIfExists(pitestVersionStamp)
    return EmptyAcceptedRecordRemoval(baselineRemoved, stampRemoved)
  }

  /**
   * Checked, non-symlink-following deletion for an explicitly scoped task directory.
   * A symlink at any level is deleted as a link; its target is never traversed.
   */
  fun deleteRecursivelyIfExists(target: File): Boolean {
    val root = target.toPath()
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        Files.delete(file)
        return FileVisitResult.CONTINUE
      }

      override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
        if (exc != null) throw exc
        Files.delete(dir)
        return FileVisitResult.CONTINUE
      }
    })
    return true
  }

  fun writeAtomically(target: File, content: String) {
    target.parentFile.mkdirs()
    // A fixed `<target>.tmp` races across Gradle processes: one writer can move the
    // shared temp file while another is still about to replace the target. Keep the
    // staging file beside the target for the atomic rename, but make it unique to
    // this writer.
    val tmp = Files.createTempFile(target.parentFile.toPath(), "${target.name}.", ".tmp").toFile()
    try {
      tmp.writeText(content)
      try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      // Normally the move consumed the temp. Clean up only the unique file owned by
      // this invocation if writing or moving failed.
      Files.deleteIfExists(tmp.toPath())
    }
  }
}
