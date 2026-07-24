package software.sava.build.hardening

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

  fun writeAtomically(target: File, content: String) {
    target.parentFile.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(content)
    try {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
