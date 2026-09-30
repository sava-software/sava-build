package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class RecompileSourceStampTest {

  @TempDir
  lateinit var projectDir: File

  private val stamp get() = File(projectDir, "build/mutation-recompile/sources.sha256")
  private val pending get() = File(projectDir, "build/mutation-recompile/sources.pending")
  private val classes get() = File(projectDir, "build/mutation-classes")
  private val classFiles get() = classes.walkTopDown().filter(File::isFile).toList()

  private fun source(name: String, text: String): File =
    File(projectDir, "src/main/java/$name").also { it.parentFile.mkdirs(); it.writeText(text) }

  private fun classFile(name: String, bytes: String): File =
    File(classes, name).also { it.parentFile.mkdirs(); it.writeText(bytes) }

  private fun compile(sources: List<File>) {
    RecompileSourceStamp.begin(stamp, pending, projectDir, sources)
    RecompileSourceStamp.publish(stamp, pending, projectDir, sources, classFiles)
  }

  @Test
  fun `a stamp names the sources read and the class tree written, and exists only after a still compile`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")

    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    assertNull(RecompileSourceStamp.read(stamp))
    val record = pending.readLines()
    assertEquals("${ProcessHandle.current().pid()}\t${RecompileSourceStamp.fingerprint(projectDir, listOf(a))}", record[0])
    assertEquals(2, record.size, "one attribute line per source file:\n${pending.readText()}")
    assertTrue(record[1].startsWith("src/main/java/A.java\t${a.length()}\t"), record[1])
    RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

    val published = requireNotNull(RecompileSourceStamp.read(stamp)) { "no stamp was published" }
    assertEquals(RecompileSourceStamp.fingerprint(projectDir, listOf(a)), published.sourcesSha256)
    assertEquals(PitestEvidence.fingerprint(projectDir, listOf(classes)), published.classesSha256)
    assertTrue(RecompileSourceStamp.matches(stamp, projectDir, listOf(a), classFiles))
    assertFalse(pending.exists(), "the pending record is consumed")
  }

  @Test
  fun `begin withdraws the published stamp`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    compile(listOf(a))
    assertNotNull(RecompileSourceStamp.read(stamp))

    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))

    assertNull(RecompileSourceStamp.read(stamp))
    assertTrue(pending.isFile)
  }

  @Test
  fun `an edit between the two readings leaves no stamp and withdraws any other`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    a.writeText("class A { int n; }")
    // Another invocation published in between; this execution's classes are what is on disk.
    stamp.writeText("sources\t${"a".repeat(64)}\nclasses\t${"b".repeat(64)}\n")

    RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

    assertNull(RecompileSourceStamp.read(stamp))
    assertFalse(stamp.exists())
    assertFalse(pending.exists())
  }

  @Test
  fun `an edit that is undone before publish is still declined`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    val modified = Files.getLastModifiedTime(a.toPath())
    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    // The bytes read the same again, as after a stash and its pop; the file does not.
    a.writeText("class A { int n; }")
    a.writeText("class A {}")
    Files.setLastModifiedTime(a.toPath(), FileTime.fromMillis(modified.toMillis() + 5_000))

    RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

    assertNull(RecompileSourceStamp.read(stamp))
    assertFalse(pending.exists())
  }

  @Test
  fun `an undo that restores the bytes, the size and the modification time is still declined`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    val changed = runCatching { Files.getAttribute(a.toPath(), "unix:ctime") }.getOrNull()
    assumeTrue(changed != null, "no inode change time here")
    val modified = Files.getLastModifiedTime(a.toPath())
    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    // In place, so the file key survives too; the inode change time is what moves. Its
    // clock is coarse on some filesystems, so the edit repeats until it has moved.
    val deadline = System.nanoTime() + 5_000_000_000L
    do {
      a.writeText("class B {}")
      a.writeText("class A {}")
      Files.setLastModifiedTime(a.toPath(), modified)
      if (Files.getAttribute(a.toPath(), "unix:ctime") != changed) break
      Thread.sleep(2)
    } while (System.nanoTime() < deadline)
    assumeTrue(Files.getAttribute(a.toPath(), "unix:ctime") != changed, "the change time never moved")

    RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

    assertNull(RecompileSourceStamp.read(stamp))
  }

  @Test
  fun `a record another process wrote is declined, removed and withdraws the stamp`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    compile(listOf(a))
    val own = requireNotNull(RecompileSourceStamp.read(stamp))
    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    val ownRecord = pending.readText()
    listOf(
      // A second invocation's begin over the same build directory: same reading, pid 1.
      "1" + ownRecord.substring(ownRecord.indexOf('\t')),
      // A record from before the process id was part of it.
      ownRecord.substring(ownRecord.indexOf('\t') + 1),
      "",
    ).forEach { foreign ->
      pending.writeText(foreign)
      stamp.writeText("sources\t${own.sourcesSha256}\nclasses\t${own.classesSha256}\n")

      RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

      assertNull(RecompileSourceStamp.read(stamp), foreign)
      assertFalse(stamp.exists(), foreign)
      assertFalse(pending.exists(), "the other compile's record is removed so that it declines too: $foreign")
    }
  }

  @Test
  fun `a record a clean removed declines the publish and withdraws the stamp`() {
    val a = source("A.java", "class A {}")
    classFile("A.class", "A-bytes")
    RecompileSourceStamp.begin(stamp, pending, projectDir, listOf(a))
    assertTrue(pending.delete())
    stamp.writeText("sources\t${"a".repeat(64)}\nclasses\t${"b".repeat(64)}\n")

    RecompileSourceStamp.publish(stamp, pending, projectDir, listOf(a), classFiles)

    assertNull(RecompileSourceStamp.read(stamp))
    assertFalse(stamp.exists())
  }

  @Test
  fun `a published stamp stops matching when a source or class changes appears or disappears`() {
    val a = source("A.java", "class A {}")
    val aClass = classFile("A.class", "A-bytes")
    compile(listOf(a))

    a.writeText("class A {} // comment")
    assertFalse(RecompileSourceStamp.matches(stamp, projectDir, listOf(a), classFiles), "source edited")
    a.writeText("class A {}")
    val b = source("B.java", "class B {}")
    assertFalse(RecompileSourceStamp.matches(stamp, projectDir, listOf(a, b), classFiles), "source added")
    assertFalse(RecompileSourceStamp.matches(stamp, projectDir, emptyList(), classFiles), "source removed")
    assertTrue(RecompileSourceStamp.matches(stamp, projectDir, listOf(a), classFiles), "restored")
    aClass.writeText("A-bytes-from-another-compile")
    assertFalse(RecompileSourceStamp.matches(stamp, projectDir, listOf(a), classFiles), "class rewritten")
    aClass.writeText("A-bytes")
    classFile("B.class", "B-bytes")
    assertFalse(RecompileSourceStamp.matches(stamp, projectDir, listOf(a), classFiles), "class added")
  }

  @Test
  fun `anything but two digests is no stamp`() {
    stamp.parentFile.mkdirs()
    listOf(
      "not a digest\n",
      "sources\t${"a".repeat(64)}\n",
      "sources\t${"a".repeat(64)}\nclasses\tnot-a-digest\n",
      "sources\t${"a".repeat(64)}\nclasses\t${"b".repeat(64)}\nextra\tfield\n",
    ).forEach { text ->
      stamp.writeText(text)
      assertNull(RecompileSourceStamp.read(stamp), text)
    }
  }
}
