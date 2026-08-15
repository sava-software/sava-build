package software.sava.build.hardening.task

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the causal operation failure while retaining cleanup diagnostics. */
internal fun retainPrimaryFailure(primary: Throwable?, next: Throwable): Throwable =
  primary?.also { it.addSuppressed(next) } ?: next

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
  private val coverageStats: PitestCoverageStats,
  /** Raw per-stream output retained before console-only minion deduplication. */
  private val retained: OutputStream? = null,
) : OutputStream() {

  private val buffer = ByteArrayOutputStream()

  override fun write(value: Int) {
    retained?.write(value)
    buffer.write(value)
    if (value == '\n'.code) flushLine()
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    retained?.write(bytes, offset, length)
    for (index in offset until offset + length) {
      val value = bytes[index].toInt()
      buffer.write(value)
      if (value == '\n'.code) flushLine()
    }
  }

  override fun flush() {
    retained?.flush()
    delegate.flush()
  }

  override fun close() {
    var failure: Throwable? = null
    fun attempt(operation: () -> Unit) {
      try {
        operation()
      } catch (next: Throwable) {
        failure = retainPrimaryFailure(failure, next)
      }
    }
    if (buffer.size() > 0) attempt(::flushLine)
    attempt { retained?.close() }
    // The destinations belong to Gradle (and are normally System.out/System.err).
    // Closing this filter must never close them for later tasks.
    attempt(delegate::flush)
    failure?.let { throw it }
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
      delegate.write(line.toByteArray(Charsets.UTF_8))
      return
    }
    coverageStats.accept(line)
    delegate.write(line.toByteArray(Charsets.UTF_8))
  }

  private companion object {
    const val MINION_PREFIX = "PIT >> INFO : MINION :"
    const val MINION_MARKER = "MINION :"
  }
}

internal data class PitestSlowestTest(
  val name: String,
  val durationMillis: Long,
)

internal data class PitestOutputSummary(
  val suppressedMinionLines: Int,
  val slowestTest: PitestSlowestTest?,
)

/**
 * Reads PIT's coverage-phase summary without retaining the full child-process output.
 *
 * PIT can print the same summary line twice (the timestamped live line and its later
 * `> ...` replay), and stdout/stderr are pumped concurrently. Keeping only the maximum
 * makes capture idempotent and thread-safe while still tolerating either stream.
 */
internal class PitestCoverageStats {
  private var slowestTest: PitestSlowestTest? = null

  @Synchronized
  fun accept(line: String) {
    val match = SLOWEST_TEST.find(line) ?: return
    val candidate = PitestSlowestTest(
      name = match.groupValues[1].trim(),
      durationMillis = match.groupValues[2].toLongOrNull() ?: return,
    )
    val current = slowestTest
    if (current == null || candidate.durationMillis > current.durationMillis ||
        (candidate.durationMillis == current.durationMillis && candidate.name < current.name)) {
      slowestTest = candidate
    }
  }

  @Synchronized
  fun snapshot(): PitestSlowestTest? = slowestTest

  private companion object {
    // Description names can themselves contain parentheses, so the name capture is
    // deliberately greedy up to PIT's final `) took N ms` delimiter. Restrict the
    // prefix to PIT's parent INFO line or its later `> ...` stats replay: arbitrary
    // test/minion output can contain the same words and must not forge an advisory.
    val SLOWEST_TEST = Regex(
      """(?:^.*PIT >> INFO : |^\s*>\s*)Slowest test \((.+)\) took (\d+) ms\s*$"""
    )
  }
}

/** One execution's pair of PIT stream filters. This object never enters task state. */
internal class MinionOutputFilters(
  standardOutput: OutputStream,
  errorOutput: OutputStream,
  retainedStandardOutput: OutputStream? = null,
  retainedErrorOutput: OutputStream? = null,
) {
  private val seen = ConcurrentHashMap.newKeySet<String>()
  private val suppressed = AtomicInteger()
  private val coverageStats = PitestCoverageStats()

  val standardOutput: OutputStream =
    MinionLineFilter(
      standardOutput, seen, suppressed, coverageStats, retainedStandardOutput)
  val errorOutput: OutputStream =
    MinionLineFilter(
      errorOutput, seen, suppressed, coverageStats, retainedErrorOutput)

  fun closeAndSummarize(): PitestOutputSummary {
    var failure: Throwable? = null
    listOf(standardOutput, errorOutput).forEach { output ->
      try {
        output.close()
      } catch (next: Throwable) {
        failure = retainPrimaryFailure(failure, next)
      }
    }
    failure?.let { throw it }
    return PitestOutputSummary(suppressed.get(), coverageStats.snapshot())
  }
}
