package software.sava.build.hardening.task

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class FuzzExecutionCountCaptureTest {

  @Test
  fun `captures one fragmented terminal count while forwarding both streams unchanged`() {
    val standard = ByteArrayOutputStream()
    val error = ByteArrayOutputStream()
    val capture = FuzzExecutionCountCapture(standard, error)

    capture.standardOutput.write("ordinary stdout\n".toByteArray())
    capture.errorOutput.write("native prelude\nDone 12".toByteArray())
    capture.errorOutput.write("345 runs in 7 second(s)\n".toByteArray())
    capture.finish()

    assertEquals("ordinary stdout\n", standard.toString(Charsets.UTF_8))
    assertEquals(
      "native prelude\nDone 12345 runs in 7 second(s)\n",
      error.toString(Charsets.UTF_8),
    )
    assertEquals(12345L, capture.requireUniquePositive("codec"))
  }

  @Test
  fun `missing ambiguous and non-positive terminal counts are refused`() {
    fun failure(vararg lines: String): String {
      val capture = FuzzExecutionCountCapture(ByteArrayOutputStream(), ByteArrayOutputStream())
      lines.forEach { capture.errorOutput.write(it.toByteArray()) }
      capture.finish()
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
}
