import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every TestKit runner comes from savaBuildRunner, which passes `--no-watch-fs` (its KDoc says
 * why), and no test replaces a runner's arguments afterwards: `withArguments` sets the whole list,
 * so a second call would drop what the helper passed.
 *
 * This is a text check, not a parser. It reads every `.kt` under src/test/kotlin except this file;
 * comments and string literals count as code. It catches a runner made, or an argument list set,
 * the way every one here was before the helper; a call through an alias or reflection gets past
 * it.
 */
class TestKitRunnerArgumentsTest {

  private val testSources = savaBuildRootFile("src/test/kotlin")

  private fun sources(): List<File> = testSources.walkTopDown()
    .filter { it.isFile && it.extension == "kt" && it.name != "TestKitRunnerArgumentsTest.kt" }
    .toList()

  /** The files, by name, with a line that contains [call]. */
  private fun filesCalling(call: String): Map<String, List<Int>> = sources()
    .associate { file -> file.name to file.readLines().withIndex().filter { call in it.value }.map { it.index + 1 } }
    .filterValues { it.isNotEmpty() }

  @Test
  fun `the shared helper is the one place a TestKit runner is made`() {
    assertEquals(mapOf("SavaBuildTestFixtures.kt" to 1), filesCalling("GradleRunner.create(").mapValues { it.value.size })
  }

  @Test
  fun `the shared helper is the one place a runner's arguments are set, and it turns file-system watching off`() {
    val sites = filesCalling(".withArguments(")
    assertEquals(setOf("SavaBuildTestFixtures.kt"), sites.keys, "argument lists set outside the helper: $sites")
    val helper = testSources.resolve("SavaBuildTestFixtures.kt").readLines()
    sites.getValue("SavaBuildTestFixtures.kt").forEach { line ->
      assertTrue("\"--no-watch-fs\"" in helper[line - 1], "SavaBuildTestFixtures.kt:$line sets arguments without --no-watch-fs")
    }
  }

  @Test
  fun `the fixtures call the helper`() {
    // Not vacuous: the runners exist and go through it.
    assertTrue(filesCalling("savaBuildRunner(").size > 20, "found few callers of savaBuildRunner under $testSources")
  }
}
