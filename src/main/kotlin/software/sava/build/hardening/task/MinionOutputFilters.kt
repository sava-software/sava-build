package software.sava.build.hardening.task

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Collapses repeated PIT minion log lines while preserving every non-minion line.
 *
 * PIT sends minion chatter to both process streams. Two filters therefore share one
 * concurrent seen-set and counter, while each keeps its own partial-line buffer so
 * Gradle's stdout and stderr pump threads cannot splice their writes together.
 */
internal class MinionLineFilter(
  private val delegate: OutputStream,
  private val seen: MutableSet<String>,
  private val suppressed: AtomicInteger,
) : OutputStream() {

  private val buffer = ByteArrayOutputStream()

  override fun write(value: Int) {
    buffer.write(value)
    if (value == '\n'.code) flushLine()
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    for (index in offset until offset + length) write(bytes[index].toInt())
  }

  override fun flush() {
    delegate.flush()
  }

  override fun close() {
    if (buffer.size() > 0) flushLine()
    // The destinations belong to Gradle (and are normally System.out/System.err).
    // Closing this filter must never close them for later tasks.
    delegate.flush()
  }

  private fun flushLine() {
    val line = buffer.toString(Charsets.UTF_8)
    buffer.reset()
    if (line.contains(MINION_PREFIX)) {
      val content = line.substringAfter(MINION_MARKER).trim()
      if (!seen.add(content)) {
        suppressed.incrementAndGet()
        return
      }
    }
    delegate.write(line.toByteArray(Charsets.UTF_8))
  }

  private companion object {
    const val MINION_PREFIX = "PIT >> INFO : MINION :"
    const val MINION_MARKER = "MINION :"
  }
}

/** One execution's pair of PIT stream filters. This object never enters task state. */
internal class MinionOutputFilters(
  standardOutput: OutputStream,
  errorOutput: OutputStream,
) {
  private val seen = ConcurrentHashMap.newKeySet<String>()
  private val suppressed = AtomicInteger()

  val standardOutput: OutputStream = MinionLineFilter(standardOutput, seen, suppressed)
  val errorOutput: OutputStream = MinionLineFilter(errorOutput, seen, suppressed)

  fun closeAndCount(): Int {
    standardOutput.close()
    errorOutput.close()
    return suppressed.get()
  }
}
