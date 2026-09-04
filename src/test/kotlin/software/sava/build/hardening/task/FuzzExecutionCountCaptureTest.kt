package software.sava.build.hardening.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class FuzzExecutionCountCaptureTest {

  @Test
  fun `standalone capture retains and forwards both streams byte exactly`() {
    val standard = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val retainedStandard = ByteArrayOutputStream()
    val retainedError = ByteArrayOutputStream()
    val capture = FuzzExecutionCountCapture(
      standard,
      error,
      retainedStandard,
      retainedError,
    )

    capture.standardOutput.write("ordinary stdout\n".toByteArray())
    capture.errorOutput.write("native prelude\nDone 12".toByteArray())
    capture.errorOutput.write("345 runs in 7 second(s)\n".toByteArray())
    capture.close()

    assertEquals("ordinary stdout\n", standard.toString(Charsets.UTF_8))
    assertEquals(
      "native prelude\nDone 12345 runs in 7 second(s)\n",
      error.toString(Charsets.UTF_8),
    )
    assertEquals(standard.toByteArray().toList(), retainedStandard.toByteArray().toList())
    assertEquals(error.toByteArray().toList(), retainedError.toByteArray().toList())
    assertEquals(12345L, capture.requireUniquePositive("codec"))
  }

  @Test
  fun `concise capture retains raw streams and emits only native lifecycle progress`() {
    val standard = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val retainedStandard = ByteArrayOutputStream()
    val retainedError = ByteArrayOutputStream()
    val progress = mutableListOf<String>()
    val capture = FuzzExecutionCountCapture(
      standard,
      error,
      retainedStandard,
      retainedError,
      forwardAll = false,
      conciseProgress = progress::add,
    )
    val standardRaw = buildString {
      append("ordinary stdout\n")
      append("#1\tINITED cov: 3 ft: 4\n")
      append("#2\tNEW cov: 4 ft: 5\n")
    }
    val errorRaw = buildString {
      append("#4\tpulse  cov: 4 ft: 5\r\n")
      append("#8\tREDUCE cov: 4 ft: 5\n")
      append("#16\tDONE cov: 4 ft: 5\n")
      append("Done 16 runs in 7 second(s)\n")
      append("unterminated diagnostic tail")
    }

    capture.standardOutput.write(standardRaw.toByteArray())
    capture.errorOutput.write(errorRaw.take(11).toByteArray())
    capture.errorOutput.write(errorRaw.drop(11).toByteArray())
    capture.close()

    assertEquals("", standard.toString(Charsets.UTF_8))
    assertEquals("", error.toString(Charsets.UTF_8))
    assertEquals(standardRaw.toByteArray().toList(), retainedStandard.toByteArray().toList())
    assertEquals(errorRaw.toByteArray().toList(), retainedError.toByteArray().toList())
    assertEquals(
      listOf(
        "#1\tINITED cov: 3 ft: 4",
        "#4\tpulse  cov: 4 ft: 5",
        "#16\tDONE cov: 4 ft: 5",
      ),
      progress,
    )
    assertEquals(16L, capture.requireUniquePositive("codec"))
  }

  @Test
  fun `missing ambiguous and non-positive terminal counts are refused`() {
    fun failure(vararg lines: String): String {
      val capture = FuzzExecutionCountCapture(ByteArrayOutputStream(), ByteArrayOutputStream())
      lines.forEach { capture.errorOutput.write(it.toByteArray()) }
      capture.close()
      return assertThrows(GradleException::class.java) {
        capture.requireUniquePositive("codec")
      }.message.orEmpty()
    }

    assertTrue(failure("no summary\n").contains("emitted no terminal"))
    assertTrue(
      failure(
        "Done 10 runs in 1 second(s)\n",
        "Done 11 runs in 1 second(s)\n",
      ).contains("ambiguous"),
    )
    assertTrue(failure("Done 0 runs in 1 second(s)\n").contains("must be positive"))
    assertTrue(
      failure("Done 9007199254740992 runs in 1 second(s)\n")
        .contains("exact-integer boundary"),
    )
    assertTrue(
      failure("Done 999999999999999999999999 runs in 1 second(s)\n")
        .contains("exceeds a signed 64-bit integer"),
    )
  }

  @Test
  fun `closing capture attempts both retained streams and preserves failures`() {
    class FailingCloseOutput(private val label: String) : ByteArrayOutputStream() {
      var closeAttempted = false

      override fun close() {
        closeAttempted = true
        throw IOException("$label close failed")
      }
    }

    val retainedStandard = FailingCloseOutput("stdout")
    val retainedError = FailingCloseOutput("stderr")
    val capture = FuzzExecutionCountCapture(
      ByteArrayOutputStream(),
      ByteArrayOutputStream(),
      retainedStandard,
      retainedError,
      forwardAll = false,
    )
    capture.standardOutput.write("partial stdout".toByteArray())
    capture.errorOutput.write("partial stderr".toByteArray())

    val failure = assertThrows(IOException::class.java, capture::close)

    assertTrue(retainedStandard.closeAttempted)
    assertTrue(retainedError.closeAttempted)
    assertEquals("stdout close failed", failure.message)
    assertEquals(listOf("stderr close failed"), failure.suppressed.map { it.message })
    assertEquals("partial stdout", retainedStandard.toString(Charsets.UTF_8))
    assertEquals("partial stderr", retainedError.toString(Charsets.UTF_8))
  }

  @Test
  fun `retained write failure is remembered while the child stream keeps draining`() {
    class FailingWriteOutput : OutputStream() {
      var writes = 0
      var closeAttempted = false

      override fun write(value: Int) {
        writes++
        throw IOException("retained write failed")
      }

      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        writes++
        throw IOException("retained write failed")
      }

      override fun close() {
        closeAttempted = true
      }
    }

    val forwarded = ByteArrayOutputStream()
    val retained = FailingWriteOutput()
    val capture = FuzzExecutionCountCapture(
      standardDelegate = forwarded,
      errorDelegate = ByteArrayOutputStream(),
      retainedStandardOutput = retained,
      retainedErrorOutput = ByteArrayOutputStream(),
    )

    capture.standardOutput.write("first chunk\n".toByteArray())
    capture.standardOutput.write("Done 37 runs in 2 second(s)\n".toByteArray())
    capture.standardOutput.flush()

    val failure = assertThrows(IOException::class.java, capture::close)
    assertEquals("retained write failed", failure.message)
    assertEquals(1, retained.writes, "a broken sink must not be retried on the pipe thread")
    assertTrue(retained.closeAttempted)
    assertEquals(
      "first chunk\nDone 37 runs in 2 second(s)\n",
      forwarded.toString(Charsets.UTF_8),
    )
    assertEquals(37L, capture.requireUniquePositive("codec"))
  }

  @Test
  fun `per-stream forwarding is independent and caller delegates remain open`() {
    class CloseTrackingOutput : ByteArrayOutputStream() {
      var closeAttempted = false

      override fun close() {
        closeAttempted = true
        super.close()
      }
    }

    val standard = CloseTrackingOutput()
    val error = ByteArrayOutputStream()
    val retainedStandard = ByteArrayOutputStream()
    val retainedError = ByteArrayOutputStream()
    val capture = FuzzExecutionCountCapture(
      standardDelegate = standard,
      errorDelegate = error,
      retainedStandardOutput = retainedStandard,
      retainedErrorOutput = retainedError,
      forwardAll = false,
      forwardStandard = true,
      forwardError = false,
    )

    capture.standardOutput.write("configured stdout\n".toByteArray())
    capture.errorOutput.write("quiet stderr\nDone 9 runs in 1 second(s)\n".toByteArray())
    capture.close()

    assertEquals("configured stdout\n", standard.toString(Charsets.UTF_8))
    assertFalse(standard.closeAttempted, "the capture must not take ownership of caller output")
    assertEquals("", error.toString(Charsets.UTF_8))
    assertFalse(retainedStandard.toString(Charsets.UTF_8).isEmpty())
    assertTrue(retainedError.toString(Charsets.UTF_8).contains("quiet stderr"))
    assertEquals(9L, capture.requireUniquePositive("codec"))
  }
}
