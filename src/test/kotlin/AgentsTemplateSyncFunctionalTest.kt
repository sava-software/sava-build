import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Functional test for 'hardeningAgentTemplate': the block it prints is the one baked
 * from this checkout's HARDENING.md, unquoted, between its two boundary comments, and
 * nothing in 'check' depends on a consumer's copy of it.
 */
class AgentsTemplateSyncFunctionalTest {

  private companion object {
    const val BLOCK_START = "<!-- hardening-template block:start -->"
    const val BLOCK_END = "<!-- hardening-template block:end -->"
  }

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  // Mirrors 'generateHardeningTemplateDigest' in sava-build's build.gradle.kts: only
  // the '>' blockquote lines of the template section are baked, trailing whitespace
  // stripped.
  private val expectedTemplate: String = run {
    val lines = File(savaBuildTestProperty("savaBuild.root"), "HARDENING.md").readLines()
    val start = lines.indexOfFirst { it.trim() == "## Agent instructions template" }
    check(start >= 0) { "HARDENING.md has no '## Agent instructions template' section" }
    lines.drop(start + 1)
      .takeWhile { !it.startsWith("## ") }
      .filter { it.startsWith(">") }
      .joinToString("\n") { it.trimEnd() }
  }
  private val expectedPrintedTemplate: String = expectedTemplate.lineSequence()
    .joinToString("\n") { it.removePrefix("> ") }

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "agents-template-sync-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--stacktrace")

  @Test
  fun `template task prints the baked block unquoted between its boundaries`() {
    writeFixture()

    val printed = runner("hardeningAgentTemplate").build().output

    assertTrue(printed.contains(expectedPrintedTemplate), printed)
    assertTrue(
      printed.indexOf(BLOCK_START) < printed.indexOf(expectedPrintedTemplate) &&
          printed.indexOf(expectedPrintedTemplate) < printed.indexOf(BLOCK_END),
      "the paste-ready block must emit start, body, then end:\n$printed",
    )
    assertFalse(printed.lineSequence().any { it.startsWith("> -") || it.startsWith(">   ") }, printed)
    assertFalse(printed.contains("hardening-template sha256:"), printed)
    assertTrue(expectedPrintedTemplate.lineSequence().count() <= 60, "the block is short by design:\n$expectedPrintedTemplate")
    assertTrue(
      printed.contains("A mutant is a question, not a specification") &&
          printed.contains("-PnoMutationHistory") &&
          printed.contains("config/pitest/<suite>-timeouts.csv") &&
          printed.contains("hardeningHelp"),
      printed,
    )
  }

  @Test
  fun `check does not depend on any AGENTS_md copy of the block`() {
    writeFixture()

    val tasks = runner("check", "--dry-run").build().output
    assertFalse(tasks.contains("agentsTemplateInSync"), tasks)
    assertFalse(tasks.contains("hardeningAgentTemplateDiff"), tasks)
    assertEquals(0, tasks.lineSequence().count { it.contains("AGENTS.md") }, tasks)
  }
}
