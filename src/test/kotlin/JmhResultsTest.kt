import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.jmh.JmhResults
import java.io.File

/** JMH's text table as the feature reads it; the real runs are in JmhFeatureRunTest. */
class JmhResultsTest {

  @TempDir
  lateinit var dir: File

  @Test
  fun `the merge keeps every parameter column and mode, N-A where a row lacks one, newest row winning per key`() {
    val older = File(dir, "results-20260101-000000.txt").apply {
      writeText(
        """
        Benchmark              (document)   Mode  Cnt    Score   Error  Units
        SourceBench.bytes         twitter   avgt   24  479.822 ± 1.000  us/op
        SourceBench.bytes          solana   avgt   24    5.000 ± 0.100  us/op
        SourceBench.bytes          solana  thrpt   24    0.200 ± 0.001  ops/us
        """.trimIndent() + "\n"
      )
    }
    val newer = File(dir, "results-20260102-000000.txt").apply {
      writeText(
        """
        Benchmark           (shape)  (document)   Mode  Cnt    Score   Error  Units
        EscapeBench.escape     flat         N/A   avgt   24    1.500 ± 0.010  us/op
        EscapeBench.escape   a flat         N/A   avgt   24    1.600 ± 0.010  us/op
        EscapeBench.escape              twitter  thrpt   24    1.700 ± 0.010  ops/us
        SourceBench.bytes       N/A     twitter   avgt   24  400.000 ± 2.000  us/op
        OkBench.probe           N/A         N/A  thrpt         9.000          ops/s
        Odd.m                x  b=y           z  thrpt   24    1.000 ± 0.010  ops/us
        Odd.m                     x      y  b=z  thrpt   24    2.000 ± 0.010  ops/us
        Tiny.m                  N/A         N/A   avgt   24  ≈ 10⁻⁹          s/op
        Tiny.m:gc.count         N/A         N/A   avgt           ≈ 0         counts
        """.trimIndent() + "\n"
      )
    }
    val merged = JmhResults.merge(listOf(older, newer))!!
    val lines = merged.lines().filter { it.isNotBlank() }
    assertEquals("Benchmark           (document)  (shape)   Mode  Cnt    Score    Error   Units", lines[0])
    assertEquals(11, lines.size - 1, merged)
    assertTrue(lines.any { Regex("^Tiny\\.m\\s+N/A\\s+N/A\\s+avgt\\s+24\\s+≈ 10⁻⁹\\s+s/op$").matches(it) }, "an approximate score stays one cell:\n$merged")
    assertTrue(lines.any { Regex("^Tiny\\.m:gc\\.count\\s+N/A\\s+N/A\\s+avgt\\s+≈ 0\\s+counts$").matches(it) }, "an approximate score without a count:\n$merged")
    // Keys compare field by field, so parameter values holding any separator stay distinct rows.
    val a = JmhResults.Key("B.m", "thrpt", mapOf("a" to "x\u0000b\u0001y", "b" to "z"))
    val b = JmhResults.Key("B.m", "thrpt", mapOf("a" to "x", "b" to "y\u0000b\u0001z"))
    assertTrue(a.compareTo(b) != 0 && a != b)
    assertTrue(lines.any { Regex("^EscapeBench\\.escape\\s+twitter\\s+thrpt\\s+24\\s+1\\.700\\s+± 0\\.010\\s+ops/us$").matches(it) }, "a blank cell is an empty value, kept:\n$merged")
    assertEquals(2, lines.count { it.startsWith("Odd.m") }, "parameter values that would collide as text stay two rows:\n$merged")
    assertTrue(lines.any { Regex("^SourceBench\\.bytes\\s+twitter\\s+N/A\\s+avgt\\s+24\\s+400\\.000\\s+± 2\\.000\\s+us/op$").matches(it) }, "newest twitter row wins:\n$merged")
    assertTrue(lines.any { Regex("^SourceBench\\.bytes\\s+solana\\s+N/A\\s+avgt\\s+24\\s+5\\.000\\s+± 0\\.100\\s+us/op$").matches(it) }, merged)
    assertTrue(lines.any { Regex("^SourceBench\\.bytes\\s+solana\\s+N/A\\s+thrpt\\s+24\\s+0\\.200\\s+± 0\\.001\\s+ops/us$").matches(it) }, "each mode keeps its row:\n$merged")
    assertTrue(lines.any { Regex("^EscapeBench\\.escape\\s+N/A\\s+flat\\s+avgt\\s+24\\s+1\\.500\\s+± 0\\.010\\s+us/op$").matches(it) }, merged)
    assertTrue(lines.any { Regex("^EscapeBench\\.escape\\s+N/A\\s+a flat\\s+avgt\\s+24\\s+1\\.600\\s+± 0\\.010\\s+us/op$").matches(it) }, "a value with a space stays whole:\n$merged")
    assertTrue(lines.any { Regex("^OkBench\\.probe\\s+N/A\\s+N/A\\s+thrpt\\s+9\\.000\\s+ops/s$").matches(it) }, merged)
    assertNull(JmhResults.merge(listOf(File(dir, "results-20260103-000000.txt").apply { writeText("Benchmark  Mode  Cnt  Score   Error  Units\n") })))
  }
}
