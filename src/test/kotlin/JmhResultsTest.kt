import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.jmh.JmhResults
import software.sava.build.jmh.JmhResults.Attempt
import java.io.File

/** JMH's text table and console output as the feature reads them; the real runs are in JmhFeatureRunTest. */
class JmhResultsTest {

  @TempDir
  lateinit var dir: File

  @Test
  fun `dense names follow JMH, stripping a shared package, abbreviating a partly shared one, keeping unshared ones`() {
    assertEquals(mapOf("probe.OkBench.probe" to "OkBench.probe"), JmhResults.denseNames(listOf("probe.OkBench.probe")))
    assertEquals(
      mapOf("probe.OkBench.probe" to "OkBench.probe", "probe.LateFailBench.probe" to "LateFailBench.probe"),
      JmhResults.denseNames(listOf("probe.OkBench.probe", "probe.LateFailBench.probe")),
    )
    assertEquals(
      mapOf("com.example.foo.X.m" to "c.e.foo.X.m", "com.example.bar.Y.m" to "c.e.bar.Y.m"),
      JmhResults.denseNames(listOf("com.example.foo.X.m", "com.example.bar.Y.m")),
    )
    assertEquals(
      mapOf("other.DeepBench.probe" to "other.DeepBench.probe", "probe.OkBench.probe" to "probe.OkBench.probe"),
      JmhResults.denseNames(listOf("other.DeepBench.probe", "probe.OkBench.probe")),
    )
    // The first name's own lowercase run sets whether a cut prefix is abbreviated: order matters, as in JMH.
    assertEquals(
      mapOf("probe.sub.ABench.m" to "p.sub.ABench.m", "probe.ZBench.m" to "p.ZBench.m"),
      JmhResults.denseNames(listOf("probe.sub.ABench.m", "probe.ZBench.m")),
    )
    assertEquals(
      mapOf("probe.ZBench.m" to "ZBench.m", "probe.sub.ABench.m" to "sub.ABench.m"),
      JmhResults.denseNames(listOf("probe.ZBench.m", "probe.sub.ABench.m")),
    )
  }

  private fun section(benchmark: String, mode: String = "Throughput, ops/time", measurement: String = "4 iterations, 100 ms each", body: String) =
    listOf("# JMH version: 1.37", "# Warmup: 1 iterations, 100 ms each", "# Measurement: $measurement", "# Benchmark mode: $mode", "# Benchmark: $benchmark", body)
      .joinToString("\n") + "\n\n"

  private val console = section("probe.OkBench.probe", body = """
    # Fork: 1 of 1
    # Warmup Iteration   1: 1600000000.000 ops/s
    Iteration   1: 1646911413.454 ops/s

    Result "probe.OkBench.probe":
      1646911413.454 ops/s
  """.trimIndent()) + section("probe.LateFailBench.probe", body = """
    # Fork: 1 of 1
    # Warmup Iteration   1: 1600000000.000 ops/s
    Iteration   1: 1688782643.908 ops/s
    Iteration   2: 1688782643.908 ops/s
    Iteration   3: <failure>

    java.lang.IllegalStateException: LateFailBench: fails in iteration 3

    Result "probe.LateFailBench.probe":
      1688782643.908 ops/s
  """.trimIndent()) + section("probe.ParamBench.probe", body = """
    # Parameters: (kind = beta, size = 2)

    # Fork: 1 of 1
    # Warmup Iteration   1: <failure>

    java.lang.IllegalStateException: ParamBench: beta/2 disagrees
  """.trimIndent()) + section("probe.NoisyBench.probe", body = """
    # Fork: 1 of 1
    Iteration   1: NoisyBench: loading the fixture
    <failure>

    java.lang.IllegalStateException: NoisyBench: the fixture is wrong
  """.trimIndent()) + section("probe.FlakyBench.probe", body = """
    # Fork: 1 of 3
    Iteration   1: 1000.000 ops/s

    # Fork: 2 of 3
    # Warmup Iteration   1: <failure>

    java.lang.IllegalStateException: FlakyBench: second fork

    # Fork: 3 of 3
    Iteration   1: 1000.000 ops/s

    Result "probe.FlakyBench.probe":
      1000.000 ops/s
  """.trimIndent()) + section("probe.DeadBench.probe", body = """
    # Fork: 1 of 1
    <forked VM failed with exit code 1>
    <stdout last='...'>
  """.trimIndent()) + section("probe.ExitBench.probe", body = """
    # Fork: 1 of 1
    Iteration   1: <failure: VM prematurely exited before JMH had finished with it, explicit System.exit was called?>
  """.trimIndent()) + section("probe.ShotBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: 12.000 us/op
    Iteration   2: 11.000 us/op
  """.trimIndent()) + section("probe.MuteBench.probe", body = """
    # Fork: 1 of 1
    Iteration   1: 5.000 ops/s
  """.trimIndent()) + section("probe.WarmBench.probe", measurement = "<none>", body = """
    # Fork: 1 of 1
    # Warmup Iteration   1: 5.000 ops/s
  """.trimIndent()) + section("probe.OkBench.probe", mode = "Average time, time/op", body = """
    # Fork: 1 of 1
    Iteration   1: 0.600 ns/op

    Result "probe.OkBench.probe":
      0.600 ns/op
  """.trimIndent()) + section("probe.HaltBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: 12.000 us/op
    Iteration   2: <forked VM failed with exit code 1>
    <stdout last='...'>
  """.trimIndent()) + section("probe.HaltBench.probe", body = """
    # Fork: 1 of 1
    Iteration   1: <forked VM failed with exit code 137>
  """.trimIndent()) + section("probe.ExitShotBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: 12.000 us/op
    Iteration   2: <failure: VM prematurely exited before JMH had finished with it, explicit System.exit was called?>
  """.trimIndent()) + section("probe.LateShotBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: 12.000 us/op
    Iteration   2: 11.000 us/op
    Iteration   3: <failure>
  """.trimIndent()) + section("probe.SlowSampleBench.probe", mode = "Sampling time", body = """
    # Fork: 1 of 1
    Iteration   1: n = 1, mean = 153 ms/op
    Iteration   2: n = 1, mean = 154 ms/op
  """.trimIndent()) + section("probe.EmptyParamBench.probe", body = """
    # Parameters: (input = )

    # Fork: 1 of 1
    Iteration   1: 5.000 ops/s

    Result "probe.EmptyParamBench.probe":
      5.000 ops/s
  """.trimIndent()) + section("probe.NoisyShotBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: NoisyShotBench: loading the fixture
    <failure>

    java.lang.IllegalStateException: NoisyShotBench: the fixture is wrong
  """.trimIndent()) + section("probe.NoisySampleBench.probe", mode = "Sampling time", body = """
    # Fork: 1 of 1
    # Warmup Iteration   1: NoisySampleBench: loading
    n = 3, mean = 150 ms/op
    Iteration   1: NoisySampleBench: loading
    <failure>

    java.lang.IllegalStateException: NoisySampleBench: second call
  """.trimIndent()) + section("probe.WarmForkShotBench.probe", mode = "Single shot invocation time", body = """
    # Warmup Fork: 1 of 1
    Iteration   1: 12.000 us/op

    # Fork: 1 of 1
    # Warmup Iteration   1: <failure>

    java.lang.IllegalStateException: WarmForkShotBench: trial setup
  """.trimIndent()) + section("probe.LoggedShotBench.probe", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: LoggedShotBench: starting
    12.000 us/op
    Iteration   2: 11.000 us/op
  """.trimIndent()) + section("probe.EdgeShot.xml", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: <report rows="3"/>
    536.333 us/op
    Iteration   2: <report rows="3"/>
    81.457 us/op
  """.trimIndent()) + section("probe.EdgeShot.blank", mode = "Single shot invocation time", body = """
    # Fork: 1 of 1
    Iteration   1: 
    loaded
    617.291 us/op
    Iteration   2: 
    loaded
    128.417 us/op
  """.trimIndent()) + """
    # Run complete. Total time: 00:00:02

    Benchmark                  (kind)  (size)   Mode  Cnt           Score  Error  Units
    OkBench.probe                 N/A     N/A  thrpt       1646911413.454         ops/s
    Iteration   9: <failure>
  """.trimIndent()

  @Test
  fun `console sections give each attempt its mode, parameters, result and failure, however it failed`() {
    val attempts = JmhResults.attempts(console)
    assertEquals(23, attempts.size, attempts.joinToString("\n"))
    val byLabel = attempts.associateBy { it.label }
    assertEquals(Attempt("probe.OkBench.probe", "thrpt", emptyMap(), null, hasResult = true), byLabel["probe.OkBench.probe [thrpt]"])
    assertEquals(Attempt("probe.LateFailBench.probe", "thrpt", emptyMap(), "failed in iteration 3", hasResult = true), byLabel["probe.LateFailBench.probe [thrpt]"])
    assertEquals(
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "beta", "size" to "2"), "failed in warmup iteration 1", hasResult = false),
      byLabel["probe.ParamBench.probe (kind = beta, size = 2) [thrpt]"],
    )
    assertEquals(Attempt("probe.NoisyBench.probe", "thrpt", emptyMap(), "failed in iteration 1", hasResult = false), byLabel["probe.NoisyBench.probe [thrpt]"])
    assertEquals(Attempt("probe.FlakyBench.probe", "thrpt", emptyMap(), "failed in warmup iteration 1", hasResult = true), byLabel["probe.FlakyBench.probe [thrpt]"])
    assertEquals(Attempt("probe.DeadBench.probe", "thrpt", emptyMap(), "forked VM failed with exit code 1", hasResult = false), byLabel["probe.DeadBench.probe [thrpt]"])
    assertEquals(
      Attempt("probe.ExitBench.probe", "thrpt", emptyMap(), "failure: VM prematurely exited before JMH had finished with it, explicit System.exit was called?", hasResult = false),
      byLabel["probe.ExitBench.probe [thrpt]"],
    )
    // Single-shot mode prints no Result block under three samples, but the measured iterations are its result.
    assertEquals(Attempt("probe.ShotBench.probe", "ss", emptyMap(), null, hasResult = true), byLabel["probe.ShotBench.probe [ss]"])
    // Measured, no result, no failure the feature knows: reported, not passed.
    assertEquals(
      Attempt("probe.MuteBench.probe", "thrpt", emptyMap(), "ended without a result or a failure the feature recognises", hasResult = false),
      byLabel["probe.MuteBench.probe [thrpt]"],
    )
    // A warmup-only run measures nothing and is not a failure.
    assertEquals(Attempt("probe.WarmBench.probe", "thrpt", emptyMap(), null, hasResult = false), byLabel["probe.WarmBench.probe [thrpt]"])
    assertEquals(Attempt("probe.OkBench.probe", "avgt", emptyMap(), null, hasResult = true), byLabel["probe.OkBench.probe [avgt]"])
    // A fork that dies mid-iteration puts its marker on the iteration's line, and JMH discards the
    // benchmark's results, measured shots included.
    assertEquals(Attempt("probe.HaltBench.probe", "ss", emptyMap(), "forked VM failed with exit code 1", hasResult = false), byLabel["probe.HaltBench.probe [ss]"])
    assertEquals(Attempt("probe.HaltBench.probe", "thrpt", emptyMap(), "forked VM failed with exit code 137", hasResult = false), byLabel["probe.HaltBench.probe [thrpt]"])
    assertEquals(
      Attempt("probe.ExitShotBench.probe", "ss", emptyMap(), "failure: VM prematurely exited before JMH had finished with it, explicit System.exit was called?", hasResult = false),
      byLabel["probe.ExitShotBench.probe [ss]"],
    )
    // A single-shot late failure keeps its two samples as a short row, with no Result block.
    assertEquals(Attempt("probe.LateShotBench.probe", "ss", emptyMap(), "failed in iteration 3", hasResult = true), byLabel["probe.LateShotBench.probe [ss]"])
    // Sampling mode prints no Result block under three samples either; its measured iterations are the result.
    assertEquals(Attempt("probe.SlowSampleBench.probe", "sample", emptyMap(), null, hasResult = true), byLabel["probe.SlowSampleBench.probe [sample]"])
    // An empty parameter value prints as 'input = ' and pairs by that text.
    val empty = byLabel["probe.EmptyParamBench.probe (input = ) [thrpt]"]!!
    assertEquals(mapOf("input" to ""), empty.params)
    assertEquals("input = ", empty.paramsText)
    // A shot counts only once its iteration ended without a failure, whatever the benchmark
    // printed on the iteration's line; a warmup fork's shots never count.
    assertEquals(Attempt("probe.NoisyShotBench.probe", "ss", emptyMap(), "failed in iteration 1", hasResult = false), byLabel["probe.NoisyShotBench.probe [ss]"])
    assertEquals(Attempt("probe.NoisySampleBench.probe", "sample", emptyMap(), "failed in iteration 1", hasResult = false), byLabel["probe.NoisySampleBench.probe [sample]"])
    assertEquals(Attempt("probe.WarmForkShotBench.probe", "ss", emptyMap(), "failed in warmup iteration 1", hasResult = false), byLabel["probe.WarmForkShotBench.probe [ss]"])
    assertEquals(Attempt("probe.LoggedShotBench.probe", "ss", emptyMap(), null, hasResult = true), byLabel["probe.LoggedShotBench.probe [ss]"])
    // A shot line holding the benchmark's own output, '<'-prefixed or empty, with the value on the next line, is a measured shot.
    assertEquals(Attempt("probe.EdgeShot.xml", "ss", emptyMap(), null, hasResult = true), byLabel["probe.EdgeShot.xml [ss]"])
    assertEquals(Attempt("probe.EdgeShot.blank", "ss", emptyMap(), null, hasResult = true), byLabel["probe.EdgeShot.blank [ss]"])
    // Under Gradle's --debug the capture carries log prefixes; JMH's lines are the forwarded standard output.
    val debug = JmhResults.attempts(
      "2026-11-05T17:49:09.238+0100 [DEBUG] [org.gradle.internal.x] something\n" +
      "2026-11-05T17:49:09.238+0100 [QUIET] [system.out] # Benchmark mode: Throughput, ops/time\n" +
      "2026-11-05T17:49:09.238+0100 [QUIET] [system.out] # Benchmark: probe.OkBench.probe\n" +
      "2026-11-05T17:49:09.240+0100 [QUIET] [system.out] Iteration   1: <failure>\n" +
      "2026-11-05T17:49:09.241+0100 [QUIET] [system.out] # Run complete. Total time: 00:00:01\n"
    )
    assertEquals(listOf(Attempt("probe.OkBench.probe", "thrpt", emptyMap(), "failed in iteration 1", hasResult = false)), debug)
    assertTrue(JmhResults.attempts("# Benchmark mode: Throughput, ops/time\nResult \"x\":\n").isEmpty())
    val tricky = JmhResults.attempts("# Benchmark: p.B.m\n# Parameters: (text = hello, world, shape = a = b)\nResult \"p.B.m\":\n").single()
    assertEquals(mapOf("text" to "hello, world", "shape" to "a = b"), tricky.params)
    assertEquals("text = hello, world, shape = a = b", tricky.paramsText)
  }

  @Test
  fun `the rows of failed attempts leave the table and the others stay, by dense name, mode and parameters`() {
    val table = """
      Benchmark                  (kind)  (size)   Mode  Cnt           Score            Error  Units
      LateFailBench.probe           N/A     N/A  thrpt    2  1688782643.908                   ops/s
      OkBench.probe                 N/A     N/A  thrpt    4  1751846577.020 ±  287323928.173  ops/s
      OkBench.probe:gc.alloc.rate   N/A     N/A  thrpt    4           0.001 ±           0.001  MB/sec
      OkBench.probe                 N/A     N/A   avgt    2           0.600                   ns/op
      ParamBench.probe            alpha       2  thrpt    4  1110082274.322 ±  150971436.077  ops/s
      ParamBench.probe             beta       2  thrpt    2  1115372775.385                   ops/s
      ParamBench.probe      hello world       2  thrpt    4  1110082274.322 ±  150971436.077  ops/s
      ParamBench.probe                        3  thrpt    4  1110082274.322 ±  150971436.077  ops/s
      ParamBench.probe     a = 1, b = 2       4  thrpt    4  1110082274.322 ±  150971436.077  ops/s
    """.trimIndent() + "\n"
    val attempts = listOf(
      Attempt("probe.OkBench.probe", "thrpt", emptyMap(), null, true),
      Attempt("probe.LateFailBench.probe", "thrpt", emptyMap(), "failed in iteration 3", true),
      Attempt("probe.OkBench.probe", "avgt", emptyMap(), "failed in iteration 3", true),
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "alpha", "size" to "2"), null, true),
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "beta", "size" to "2"), "failed in iteration 3", true),
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "hello world", "size" to "2"), null, true),
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "beta", "size" to "1"), "failed in warmup iteration 1", false),
      // An empty value, and a value holding ', ' and ' = ', pair by the printed parameter text.
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "", "size" to "3"), null, true, paramsText = "kind = , size = 3"),
      Attempt("probe.ParamBench.probe", "thrpt", mapOf("kind" to "a = 1, b = 2", "size" to "4"), "failed in iteration 2", true, paramsText = "kind = a = 1, b = 2, size = 4"),
    )
    val kept = JmhResults.withoutFailedRows(table, attempts)
    val lines = kept.lines().filter { it.isNotBlank() }
    assertEquals(6, lines.size, kept)
    assertTrue(lines.any { Regex("^ParamBench\\.probe\\s+3\\s+thrpt").containsMatchIn(it) }, "the empty value's row stays:\n$kept")
    assertFalse(kept.contains("a = 1, b = 2"), kept)
    assertTrue(lines[0].startsWith("Benchmark"), kept)
    assertTrue(lines.any { it.startsWith("OkBench.probe  ") && it.contains("thrpt") }, kept)
    assertTrue(lines.any { it.startsWith("OkBench.probe:gc.alloc.rate") }, kept)
    assertTrue(lines.any { it.startsWith("ParamBench.probe            alpha") }, kept)
    assertTrue(lines.any { it.startsWith("ParamBench.probe      hello world") }, kept)
    assertFalse(kept.contains("LateFailBench"), kept)
    assertFalse(kept.contains("avgt"), kept)
    assertFalse(Regex("ParamBench\\.probe\\s+beta").containsMatchIn(kept), kept)

    // A result without a row, or a row without a result, is a pairing the feature does not understand.
    val missingRow = assertThrows(IllegalStateException::class.java) {
      JmhResults.withoutFailedRows(table, attempts + Attempt("probe.GhostBench.probe", "thrpt", emptyMap(), null, true))
    }
    assertTrue(missingRow.message!!.contains("GhostBench"), missingRow.message)
    assertThrows(IllegalStateException::class.java) { JmhResults.withoutFailedRows(table, attempts.drop(1)) }
  }

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
