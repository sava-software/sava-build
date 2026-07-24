import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Functional test for 'hardeningInit', the one-shot adoption scaffolding:
 * writes config/pitest/README.md and the .gitignore line only where they are absent,
 * never overwrites what exists, and prints the adoption checklist with the current
 * template digest.
 */
class HardeningInitFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "hardening-init-smoke-test"
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

  private fun runner(): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments("hardeningInit", "--stacktrace")

  @Test
  fun `a fresh repo gets the README, the gitignore line, and the checklist, idempotently`() {
    writeFixture()
    val readme = File(fixtureDir, "config/pitest/README.md")
    val gitignore = File(fixtureDir, ".gitignore")

    val first = runner().build()
    assertTrue(first.output.contains("hardeningInit: wrote"), first.output)
    assertTrue(readme.isFile, "README not scaffolded")
    val readmeText = readme.readText()
    assertTrue(readmeText.startsWith("# Mutation-testing baseline & triage policy"), readmeText)
    assertTrue(readmeText.contains("## Untriaged debt"), readmeText)
    assertTrue(readmeText.contains("## Triaged equivalent mutants (accepted with reasons)"), readmeText)
    assertTrue(first.output.contains("appended .pitest-history/ to"), first.output)
    assertTrue(gitignore.readText().contains("\n.pitest-history/\n"), gitignore.readText())
    assertTrue(first.output.contains("remaining adoption steps"), first.output)
    // the checklist hands over the acknowledgment marker agentsTemplateInSync expects
    assertTrue(
      Regex("<!-- hardening-template sha256:[0-9a-f]{12} -->").containsMatchIn(first.output),
      "digest marker missing from the checklist:\n" + first.output
    )

    // a second run changes nothing: same README bytes, no duplicated ignore line
    val readmeBefore = readme.readText()
    val gitignoreBefore = gitignore.readText()
    val second = runner().build()
    assertTrue(second.output.contains("exists — left untouched"), second.output)
    assertTrue(second.output.contains(".gitignore already covers .pitest-history/"), second.output)
    assertEquals(readmeBefore, readme.readText(), "README must never be rewritten")
    assertEquals(gitignoreBefore, gitignore.readText(), "the ignore line must not be appended twice")
  }

  @Test
  fun `existing files are preserved and the gitignore append never splices a line`() {
    writeFixture()
    File(fixtureDir, "config/pitest").mkdirs()
    File(fixtureDir, "config/pitest/README.md").writeText("custom policy\n")
    // no trailing newline: the append must supply the separator or the comment would
    // splice onto the last entry
    File(fixtureDir, ".gitignore").writeText("build/")

    val result = runner().build()
    assertTrue(result.output.contains("exists — left untouched"), result.output)
    assertEquals("custom policy\n", File(fixtureDir, "config/pitest/README.md").readText())
    assertEquals(
      "build/\n\n# arcmutate incremental-analysis history (machine-local, written by the hardening plugin)\n.pitest-history/\n",
      File(fixtureDir, ".gitignore").readText()
    )
  }
}
