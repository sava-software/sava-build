package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}
