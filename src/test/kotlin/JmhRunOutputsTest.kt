import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.jmh.JmhRunOutputs
import java.io.File

/** The run's outputs under --jmhArgs: the plugin's own reading of -rff, -rf and -o, or the task's properties. */
class JmhRunOutputsTest {

  private val workingDir = File("/work")
  private val results = File("/work/build/results/jmh/results.txt")

  @Test
  fun `without --jmhArgs the task's properties stand, text when no format is set`() {
    val outputs = JmhRunOutputs.of(null, results, null, null, workingDir)
    assertEquals(results, outputs.results)
    assertTrue(outputs.textFormat)
    assertEquals("txt", outputs.archiveExtension)
    assertNull(outputs.report)
    val json = JmhRunOutputs.of("", results, "JSON", File("/work/report.txt"), workingDir)
    assertFalse(json.textFormat)
    assertEquals("json", json.archiveExtension)
    assertEquals(File("/work/report.txt"), json.report)
  }

  @Test
  fun `-rff, -rf and -o override the properties, relative paths against the working directory`() {
    val outputs = JmhRunOutputs.of("-rff other/bench.json  -rf json -o report.txt -f 2", results, "text", null, workingDir)
    assertEquals(File("/work/other/bench.json"), outputs.results)
    assertEquals("json", outputs.format)
    assertFalse(outputs.textFormat)
    assertEquals(File("/work/report.txt"), outputs.report)
    val absolute = JmhRunOutputs.of("-rff /elsewhere/b.txt", results, "text", null, workingDir)
    assertEquals(File("/elsewhere/b.txt"), absolute.results)
    assertTrue(absolute.textFormat)
  }

  @Test
  fun `an option without a value or followed by another option overrides nothing, and the last value wins`() {
    assertEquals(emptyMap<String, String>(), JmhRunOutputs.overrides("-rff -rf"))
    assertEquals(mapOf("-rf" to "csv"), JmhRunOutputs.overrides("-rf json -rf csv"))
    assertEquals(mapOf("-o" to "out.txt"), JmhRunOutputs.overrides("-l -o out.txt"))
    assertEquals(emptyMap<String, String>(), JmhRunOutputs.overrides("-rff"))
  }

  @Test
  fun `the string is trimmed and split as the plugin does it, on ASCII whitespace alone`() {
    // Java's String.trim and a \s+ split: a no-break space is part of its token, for the plugin
    // (which appends the token to JMH's command line as it is) and so for JMH.
    assertEquals(mapOf("-rf" to "json", "-rff" to "bench.json\u00A0"), JmhRunOutputs.overrides(" -rf json -rff bench.json\u00A0 "))
    assertEquals(emptyMap<String, String>(), JmhRunOutputs.overrides("\u00A0-rff bench.json"))
    assertEquals(mapOf("-rff" to "a.txt"), JmhRunOutputs.overrides("\t-rff\ta.txt\n"))
  }

  @Test
  fun `the archive extension follows the plugin's naming of the results file`() {
    assertEquals("txt", JmhRunOutputs.of("-rf TEXT", results, null, null, workingDir).archiveExtension)
    assertEquals("latex", JmhRunOutputs.of("-rf LATEX", results, null, null, workingDir).archiveExtension)
    assertEquals("scsv", JmhRunOutputs.of("-rf SCSV", results, null, null, workingDir).archiveExtension)
  }
}
