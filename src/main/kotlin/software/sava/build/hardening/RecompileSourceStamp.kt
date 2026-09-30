package software.sava.build.hardening

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * What the PIT recompile says about its own work: the fingerprint of the source files one
 * clean, complete execution read, and of the class tree it left behind, published beside
 * that tree.
 *
 * A class fingerprint is a statement about the sources only while the classes were
 * compiled from them, and nothing downstream can check that by looking at the classes.
 * Gradle has called this task UP-TO-DATE over an edited file *(casebook: the green run
 * against stale classes)*, and excluding the task leaves the previous tree in place by
 * design. The stamp is read from disk, not from Gradle's view of the inputs, so it is the
 * one witness that does not share that blind spot. It names the class tree as well as the
 * sources because two invocations can share one build directory: a stamp that only said
 * "these sources" could be published by one compile and then sit beside classes another
 * compile wrote a moment later.
 *
 * It is taken in two steps so that it exists only after a compile during which the
 * sources held still: [begin] withdraws the published stamp and records what is about to
 * be read, [publish] promotes that record only if the same files still read the same.
 * The record holds each file's size, modification time, inode change time and file key
 * beside the content fingerprint, so an edit that javac read and that was undone before
 * [publish] (a stash and its pop, a checkout and back, a copy that preserves the
 * modification time) still declines: the bytes match again, the timestamps and inodes
 * do not. A spurious touch costs one recompile, never an answer.
 * The record also names the process that wrote it, because a second invocation's
 * [begin] over the same build directory replaces it with a reading of its own: a
 * [publish] that finds a record it did not write declines, removes the record so that
 * the other compile declines too, and withdraws any stamp, since two compiles that
 * overlapped may both have written into the tree. A failed, interrupted or raced compile
 * therefore leaves no stamp, and no stamp means nothing is vouched for.
 */
internal object RecompileSourceStamp {

  /** Both fingerprints are [PitestEvidence.fingerprint] values, relative to the project. */
  internal data class Stamp(val sourcesSha256: String, val classesSha256: String)

  private val SHA256 = Regex("[0-9a-f]{64}")

  fun fingerprint(projectDirectory: File, files: Iterable<File>): String =
      PitestEvidence.fingerprint(projectDirectory, files)

  /** The published stamp, or null when no clean compile has published one. */
  fun read(stamp: File): Stamp? {
    val lines = try {
      stamp.takeIf(File::isFile)?.readLines() ?: return null
    } catch (_: IOException) {
      return null // withdrawn between the existence check and the read: no stamp
    }
    val fields = lines.filter { it.isNotEmpty() }.associate { line ->
      val split = line.indexOf('\t')
      if (split <= 0) return null
      line.substring(0, split) to line.substring(split + 1)
    }
    val sources = fields["sources"]?.takeIf(SHA256::matches) ?: return null
    val classes = fields["classes"]?.takeIf(SHA256::matches) ?: return null
    return if (fields.size == 2) Stamp(sources, classes) else null
  }

  /**
   * Whether [classFiles] were compiled from exactly these source bytes: the published
   * stamp names both the sources on disk and the class tree on disk.
   */
  fun matches(stamp: File, projectDirectory: File, sources: Iterable<File>, classFiles: Iterable<File>): Boolean {
    val published = read(stamp) ?: return false
    return published.sourcesSha256 == fingerprint(projectDirectory, sources) &&
        published.classesSha256 == fingerprint(projectDirectory, classFiles)
  }

  /** Before the compiler reads anything. */
  fun begin(stamp: File, pending: File, projectDirectory: File, sources: Iterable<File>) {
    Files.deleteIfExists(stamp.toPath())
    pending.parentFile.mkdirs()
    pending.writeText(record(projectDirectory, sources) ?: "")
  }

  /**
   * After the compiler succeeded: publish only what both readings agree on, naming the
   * class tree as it is now. Declining also withdraws any stamp published in between,
   * because the classes on disk are this execution's and no other stamp describes them.
   */
  fun publish(
    stamp: File,
    pending: File,
    projectDirectory: File,
    sources: Iterable<File>,
    classFiles: Iterable<File>,
  ) {
    val before = try {
      pending.takeIf(File::isFile)?.readText()
    } catch (_: IOException) {
      null
    }
    Files.deleteIfExists(pending.toPath())
    val now = record(projectDirectory, sources)
    if (before == null || now == null || before != now) {
      Files.deleteIfExists(stamp.toPath())
      return
    }
    val sourcesSha256 = now.lineSequence().first().substringAfter('\t')
    BaselineFiles.writeAtomically(
      stamp,
      "sources\t$sourcesSha256\nclasses\t${fingerprint(projectDirectory, classFiles)}\n",
    )
  }

  /**
   * One reading of the sources: the writing process, the content fingerprint, then every
   * file's name, size, modification time, change time and file key. Null when a file
   * cannot be read, which declines the publish like any other difference.
   */
  private fun record(projectDirectory: File, sources: Iterable<File>): String? {
    val files = sources.asSequence().flatMap { entry ->
      when {
        entry.isFile -> sequenceOf(entry)
        entry.isDirectory -> entry.walkTopDown().filter(File::isFile)
        else -> emptySequence()
      }
    }.toList()
    val attributes = try {
      files.map { file ->
        val name = runCatching { file.relativeTo(projectDirectory).invariantSeparatorsPath }
            .getOrElse { file.absoluteFile.invariantSeparatorsPath }
        val path = file.toPath()
        val read = Files.readAttributes(path, BasicFileAttributes::class.java)
        // The inode change time cannot be restored by a copy that preserves the
        // modification time; where the platform has none, the other fields stand alone.
        val changed = try {
          (Files.getAttribute(path, "unix:ctime") as? FileTime)?.to(TimeUnit.NANOSECONDS)
        } catch (_: UnsupportedOperationException) {
          null
        } catch (_: IllegalArgumentException) {
          null
        }
        "$name\t${read.size()}\t${read.lastModifiedTime().to(TimeUnit.NANOSECONDS)}\t" +
            "${changed ?: ""}\t${read.fileKey()}"
      }
    } catch (_: IOException) {
      return null
    }
    return "${thisProcess()}\t${fingerprint(projectDirectory, files)}\n" +
        attributes.sorted().joinToString("") { "$it\n" }
  }

  /**
   * Both actions of one task execution run in the build's own JVM, so the process id is
   * the one token they share without a carrier; a concurrent invocation is another
   * process, and a process that is gone publishes nothing.
   */
  private fun thisProcess(): String = ProcessHandle.current().pid().toString()
}
