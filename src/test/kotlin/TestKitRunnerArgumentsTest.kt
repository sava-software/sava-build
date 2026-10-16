import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every TestKit argument list passes `--no-watch-fs`. The fixtures edit and delete files
 * between builds and rely on Gradle noticing; with macOS `fseventsd` backlogged, the TestKit
 * daemon's watcher missed those changes, reporting a deleted output UP-TO-DATE or building
 * without a fixture edit (2026-10-16: one to three different tests failed per full check, and
 * all passed with `--no-watch-fs`). Without watching, Gradle checks the files at the start of
 * each build.
 *
 * This is a text check, not a parser. It reads every `.withArguments(` under src/test/kotlin
 * with its parenthesised arguments, and every file that calls `GradleRunner.create(`; comments
 * and string literals count as code. It catches a new runner written the way every runner here
 * is, a dotted `.withArguments(...)` chain, and a runner file that never mentions the flag. A
 * runner that sets its arguments inside a scope function in a file that passes the flag
 * elsewhere gets past it.
 */
class TestKitRunnerArgumentsTest {

  private val testSources = File(savaBuildTestProperty("savaBuild.root"), "src/test/kotlin")

  private fun sources(): List<File> = testSources.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name != "TestKitRunnerArgumentsTest.kt" }
      .toList()

  @Test
  fun `every TestKit argument list turns file-system watching off`() {
    val argumentLists = sources().flatMap { file ->
      val text = file.readText()
      Regex("""\.withArguments\(""").findAll(text).map { match ->
        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
        "${file.name}:$line" to argumentsAt(text, match.range.last)
      }.toList()
    }
    assertTrue(argumentLists.isNotEmpty(), "found no .withArguments( under $testSources")
    assertEquals(
      emptyList<String>(),
      argumentLists.filterNot { (_, arguments) -> arguments != null && "\"--no-watch-fs\"" in arguments }
          .map { it.first },
      "argument lists without --no-watch-fs (or without a closing parenthesis)",
    )
  }

  @Test
  fun `every file that creates a TestKit runner passes the flag`() {
    val runnerFiles = sources().filter { "GradleRunner.create(" in it.readText() }
    assertTrue(runnerFiles.isNotEmpty(), "found no GradleRunner.create( under $testSources")
    assertEquals(
      emptyList<String>(),
      runnerFiles.filterNot { "\"--no-watch-fs\"" in it.readText() }.map { it.name },
      "files that create a GradleRunner and never pass --no-watch-fs",
    )
  }

  /** The text between the parenthesis at [open] and the one that closes it; null if none does. */
  private fun argumentsAt(text: String, open: Int): String? {
    var depth = 0
    for (i in open until text.length) {
      when (text[i]) {
        '(' -> depth++
        ')' -> if (--depth == 0) return text.substring(open + 1, i)
      }
    }
    return null
  }
}
