package software.sava.build.hardening

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Baseline rewrites are atomic: content lands in a sibling temp file that is
 * moved over the target, so an interrupted refresh (a killed daemon, a stopped
 * task, a crash mid-write) leaves the previous baseline intact instead of a
 * truncated file the next verify reads as an empty ratchet
 * (casebook: the baseline truncated mid-write).
 * A filesystem that cannot atomically replace a sibling target is refused; silently
 * weakening the record commit is not an acceptable compatibility fallback.
 *
 * This is safe across process death (a kill, a stopped task, a dead daemon): the
 * rename is atomic and the surviving kernel keeps the page cache coherent. It is not
 * a power-loss guarantee — neither the temp contents nor the directory entry are
 * fsynced, so a crash could leave an atomically-renamed but still-empty file. The
 * threat model is interruption, not power loss, so that trade is deliberate.
 */
internal object BaselineFiles {

  /** `null` content is a checked deletion in the same rollback-capable transaction. */
  data class Write(val target: File, val content: String?)

  /**
   * Exact recursive byte/inventory snapshot for a prepared read/then-write boundary.
   * Symbolic links and other non-regular entries are refused rather than represented
   * incompletely.
   */
  data class TreeSnapshot(
    val rootPath: String,
    val entries: List<String>,
  )

  data class EmptyAcceptedRecordRemoval(
    val baselineRemoved: Boolean,
    val orphanVersionStampRemoved: Boolean,
    val orphanToolchainRecordRemoved: Boolean,
  )

  private data class PreviousFile(
    val target: File,
    val content: ByteArray?,
    val posixPermissions: Set<PosixFilePermission>?,
  )

  /**
   * Refuses a path whose existing lexical path contains a symbolic-link component.
   * Mutation records are intentionally checkout-local; following a linked `config/`
   * directory would make both reads and commits operate on state outside that boundary.
   */
  fun requireNoSymbolicLinkComponents(trustedRoot: File, target: File) {
    val root = trustedRoot.toPath().toAbsolutePath().normalize()
    val absolute = target.toPath().toAbsolutePath().normalize()
    require(absolute.startsWith(root)) {
      "mutation-record path escapes trusted project directory '$root': $absolute"
    }
    var current = root
    for (name in root.relativize(absolute)) {
      current = current.resolve(name)
      require(!Files.isSymbolicLink(current)) {
        "mutation-record path contains symbolic-link component '$current': $absolute"
      }
      if (current != absolute && Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          "mutation-record ancestor is not a directory: $current"
        }
      }
    }
  }

  /** Refuses anything except a missing path or a non-link regular-file leaf. */
  fun requireRegularFileOrMissing(target: File) {
    val path = target.toPath().toAbsolutePath().normalize()
    require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      "mutation-record path is not a regular file: $path"
    }
  }

  /** Checkout-confined form used before reading or writing committed records. */
  fun requireRegularFileOrMissing(trustedRoot: File, target: File) {
    requireNoSymbolicLinkComponents(trustedRoot, target)
    requireRegularFileOrMissing(target)
  }

  /** Checked no-follow snapshot for a small receipt or mutation-record file. */
  fun readRegularFileSnapshot(trustedRoot: File, target: File): ByteArray? {
    requireRegularFileOrMissing(trustedRoot, target)
    val path = target.toPath().toAbsolutePath().normalize()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    return Files.newByteChannel(
      path,
      setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
    ).use { channel ->
      ByteArrayOutputStream().use { output ->
        Channels.newInputStream(channel).copyTo(output)
        output.toByteArray()
      }
    }
  }

  /** Refuses anything except a missing path or a non-link directory leaf. */
  fun requireDirectoryOrMissing(target: File) {
    val path = target.toPath().toAbsolutePath().normalize()
    require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      "mutation-record path is not a directory: $path"
    }
  }

  /** Checkout-confined form used before traversing a committed-record directory. */
  fun requireDirectoryOrMissing(trustedRoot: File, target: File) {
    requireNoSymbolicLinkComponents(trustedRoot, target)
    requireDirectoryOrMissing(target)
  }

  /** Checked deletion for state files whose continued presence changes semantics. */
  fun deleteIfExists(target: File): Boolean {
    requireRegularFileOrMissing(target)
    return Files.deleteIfExists(target.toPath())
  }

  /**
   * Canonicalizes a whitespace-only accepted-baseline placeholder to absence and
   * retires its PIT-version stamp and mutation-toolchain record unless a timeout audit
   * still needs that provenance.
   * The content check is repeated immediately before deletion so the task does not
   * act only on its earlier project-wide preflight parse. Like the other baseline
   * writers, this assumes one Gradle writer owns the checkout; the read/delete pair
   * is not a cross-process compare-and-delete primitive.
   */
  fun deleteSemanticallyEmptyAcceptedRecord(
    baseline: File,
    timeouts: File,
    pitestVersionStamp: File,
    pitestToolchainRecord: File,
  ): EmptyAcceptedRecordRemoval {
    listOf(baseline, timeouts, pitestVersionStamp, pitestToolchainRecord)
        .forEach(::requireRegularFileOrMissing)
    require(baseline.isFile) { "accepted baseline does not exist: $baseline" }
    require(!BaselineDocument.parse(baseline.readText()).hasSubstantiveContent) {
      "refusing to delete non-empty accepted baseline: $baseline"
    }
    val baselineRemoved = deleteIfExists(baseline)
    val recordRetired = !timeouts.isFile
    val versionStampRemoved = recordRetired && deleteIfExists(pitestVersionStamp)
    val toolchainRecordRemoved = recordRetired && deleteIfExists(pitestToolchainRecord)
    return EmptyAcceptedRecordRemoval(
      baselineRemoved,
      versionStampRemoved,
      toolchainRecordRemoved,
    )
  }

  /** Checkout-confined empty-record canonicalization used by schema writers. */
  fun deleteSemanticallyEmptyAcceptedRecord(
    trustedRoot: File,
    baseline: File,
    timeouts: File,
    pitestVersionStamp: File,
    pitestToolchainRecord: File,
  ): EmptyAcceptedRecordRemoval {
    listOf(baseline, timeouts, pitestVersionStamp, pitestToolchainRecord)
        .forEach { requireRegularFileOrMissing(trustedRoot, it) }
    return deleteSemanticallyEmptyAcceptedRecord(
        baseline, timeouts, pitestVersionStamp, pitestToolchainRecord)
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

  /**
   * Captures directories and regular-file bytes without following links. Symbolic
   * links and other non-regular entries are refused. Directory entries matter: adding
   * an otherwise empty mode must invalidate a decision derived from the earlier mode set.
   */
  fun snapshotTree(root: File): TreeSnapshot {
    val absoluteRoot = root.toPath().toAbsolutePath().normalize()
    if (!Files.exists(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
      return TreeSnapshot(absoluteRoot.toString(), listOf("MISSING"))
    }
    val paths = if (Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
      Files.walk(absoluteRoot).use { stream -> stream.sorted().toList() }
    } else {
      listOf(absoluteRoot)
    }
    val entries = paths.map { path ->
      val relative = if (path == absoluteRoot) "." else
        absoluteRoot.relativize(path).toString().replace(File.separatorChar, '/')
      when {
        Files.isSymbolicLink(path) -> throw IllegalArgumentException(
          "mutation-record tree contains symbolic link '$path'; prepared writes require regular files")
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "D\t$relative"
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
          "F\t$relative\t${PitestEvidence.sha256(path.toFile())}"
        else -> throw IllegalArgumentException(
          "mutation-record tree contains unsupported filesystem entry '$path'")
      }
    }
    return TreeSnapshot(absoluteRoot.toString(), entries)
  }

  fun treeDifferences(snapshot: TreeSnapshot): List<String> {
    val current = snapshotTree(File(snapshot.rootPath))
    if (current.entries == snapshot.entries) return emptyList()
    val recorded = snapshot.entries.toSet()
    val now = current.entries.toSet()
    return buildList {
      (recorded - now).sorted().forEach { add("removed or changed: $it") }
      (now - recorded).sorted().forEach { add("added or changed: $it") }
    }
  }

  fun writeAtomically(target: File, content: String) {
    writeBytesAtomically(target, content.toByteArray(Charsets.UTF_8))
  }

  /** Checkout-confined atomic write for committed mutation records. */
  fun writeAtomically(trustedRoot: File, target: File, content: String) {
    requireRegularFileOrMissing(trustedRoot, target)
    writeAtomically(target, content)
  }

  /**
   * Keeps a last-success receipt only when an incomplete-attempt marker can be
   * made durable beside it. Callers must already own the corresponding process
   * lock. If a missing, replaced, or unwritable marker cannot be restored, the
   * receipt is deleted as the fail-closed fallback so no failed attempt can leave
   * apparently current evidence behind.
   */
  fun preserveReceiptUnderIncompleteMarker(
    trustedRoot: File,
    receipt: File,
    marker: File,
    markerContent: String,
  ) {
    try {
      requireRegularFileOrMissing(trustedRoot, marker)
      writeAtomically(trustedRoot, marker, markerContent)
    } catch (markerFailure: Exception) {
      try {
        requireRegularFileOrMissing(trustedRoot, receipt)
        deleteIfExists(receipt)
      } catch (receiptFailure: Exception) {
        markerFailure.addSuppressed(receiptFailure)
      }
      throw markerFailure
    }
  }

  /**
   * Restores the exact receipt bytes which preceded a failed replacement, but only
   * after the incomplete marker above is durable. `null` means the attempt began
   * without a prior receipt and therefore removes any partially published successor.
   * If marker publication fails, [preserveReceiptUnderIncompleteMarker] deletes the
   * current receipt and this method deliberately does not recreate the old one.
   */
  fun restoreReceiptSnapshotUnderIncompleteMarker(
    trustedRoot: File,
    receipt: File,
    marker: File,
    markerContent: String,
    previousReceipt: ByteArray?,
  ) {
    preserveReceiptUnderIncompleteMarker(trustedRoot, receipt, marker, markerContent)
    requireRegularFileOrMissing(trustedRoot, receipt)
    if (previousReceipt == null) {
      deleteIfExists(receipt)
    } else {
      writeBytesAtomically(receipt, previousReceipt)
    }
  }

  /**
   * Exception-transactional multi-file commit; null content is a deletion. Existing
   * non-regular targets are rejected before any target changes. Each rename remains individually
   * atomic; if any later write throws, restoration of every earlier target is
   * attempted byte-for-byte (or removed when it did not exist). A rollback failure is
   * attached to the original exception and leaves the cross-checked record invalid.
   * No filesystem offers a portable atomic rename across several paths, so process
   * death can still land between files; write ordering keeps each intermediate state
   * conservative or fail-closed.
   */
  fun writeAllAtomically(writes: List<Write>) {
    val normalized = writes.map { it.target.toPath().toAbsolutePath().normalize() }
    require(normalized.distinct().size == normalized.size) {
      "multi-file mutation commit names the same target more than once"
    }
    writes.forEach { requireRegularFileOrMissing(it.target) }
    val previous = writes.map { write ->
      val existed = write.target.isFile
      PreviousFile(
        write.target,
        write.target.takeIf { existed }?.readBytes(),
        write.target.takeIf { existed }?.let(::posixPermissions),
      )
    }
    var committed = 0
    try {
      writes.forEach { write ->
        if (write.content == null) deleteIfExists(write.target)
        else writeAtomically(write.target, write.content)
        committed++
      }
    } catch (failure: Exception) {
      previous.take(committed).asReversed().forEach { prior ->
        try {
          if (prior.content == null) deleteIfExists(prior.target)
          else writeBytesAtomically(prior.target, prior.content, prior.posixPermissions)
        } catch (rollbackFailure: Exception) {
          failure.addSuppressed(rollbackFailure)
        }
      }
      throw failure
    }
  }

  /** Checkout-confined multi-file commit for paired mutation-record state. */
  fun writeAllAtomically(trustedRoot: File, writes: List<Write>) {
    writes.forEach { requireRegularFileOrMissing(trustedRoot, it.target) }
    writeAllAtomically(writes)
  }

  private fun writeBytesAtomically(
    target: File,
    content: ByteArray,
    restoredPosixPermissions: Set<PosixFilePermission>? = null,
  ) {
    requireRegularFileOrMissing(target)
    // An atomic rename publishes the staging file's inode, including its mode. Keep
    // an existing record's POSIX permissions rather than replacing 0644 with the
    // JDK's intentionally private createTempFile default (normally 0600). A rollback
    // that recreates an earlier deletion supplies the removed inode's mode explicitly.
    val publishedPosixPermissions = restoredPosixPermissions ?: target
      .takeIf(File::isFile)
      ?.let(::posixPermissions)
    target.parentFile.mkdirs()
    requireDirectoryOrMissing(target.parentFile)
    requireRegularFileOrMissing(target)
    // A fixed `<target>.tmp` races across Gradle processes: one writer can move the
    // shared temp file while another is still about to replace the target. Keep the
    // staging file beside the target for the atomic rename, but make it unique to
    // this writer. Files.createTempFile deliberately uses 0600 on POSIX; create a
    // unique ordinary file instead so a new committed record receives the same
    // readable, umask-governed mode as any normal file created in this directory.
    val tmp = createSiblingStagingFile(target.parentFile.toPath()).toFile()
    try {
      tmp.writeBytes(content)
      // Apply a read-only or otherwise restrictive target mode only after staging
      // the complete bytes; the writer must not need that published mode to include
      // OWNER_WRITE merely to prepare the replacement.
      publishedPosixPermissions?.let { Files.setPosixFilePermissions(tmp.toPath(), it) }
      Files.move(
        tmp.toPath(), target.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      // Normally the move consumed the temp. Clean up only the unique file owned by
      // this invocation if writing or moving failed.
      Files.deleteIfExists(tmp.toPath())
    }
  }

  /** Returns a defensive copy on POSIX and `null` on providers without POSIX modes. */
  private fun posixPermissions(file: File): Set<PosixFilePermission>? {
    val path = file.toPath()
    val view = Files.getFileAttributeView(
        path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return null
    return view.readAttributes().permissions().toSet()
  }

  /**
   * Creates a collision-resistant ordinary sibling file. Unlike createTempFile, the
   * provider's normal create-file mode and umask apply; this is also portable to
   * providers that expose no POSIX attribute view.
   */
  private fun createSiblingStagingFile(parent: Path): Path {
    repeat(16) {
      val candidate = parent.resolve(".sava-hardening-${UUID.randomUUID()}.tmp")
      try {
        return Files.createFile(candidate)
      } catch (_: FileAlreadyExistsException) {
        // An adversarial collision is harmless; choose a fresh unique sibling.
      }
    }
    throw IllegalStateException("could not allocate a unique mutation-record staging file in $parent")
  }
}
