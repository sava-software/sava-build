import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.HardeningOptionNames
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

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

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
    assertTrue(readmeText.startsWith("# Mutation hardening evidence"), readmeText)
    assertTrue(readmeText.contains("repository-specific evidence and decisions only"), readmeText)
    assertTrue(readmeText.contains("./gradlew :hardeningHelp"), readmeText)
    assertTrue(
      readmeText.contains("inline, fenced, or tabular coordinate rosters source-line-free") &&
          readmeText.contains("meaningful multiplicity as `xN`") &&
          readmeText.contains("typographic `×N` is equivalent"),
      readmeText,
    )
    assertTrue(readmeText.contains("## Untriaged debt"), readmeText)
    assertTrue(readmeText.contains("## Accepted mutants"), readmeText)
    val normalizedReadme = readmeText.replace(Regex("\\s+"), " ")
    assertTrue(
      normalizedReadme.contains("Name the class, method, and semantic branch") &&
          normalizedReadme.contains("omit source line numbers") &&
          normalizedReadme.contains("Every row retained in an accepted CSV remains active") &&
          normalizedReadme.contains("pitest<Suite>BaselinePrune"),
      readmeText,
    )
    assertTrue(
      normalizedReadme.contains("record its exact `# <label>`, local structural reason") &&
          normalizedReadme.contains("property, independent oracle") &&
          normalizedReadme.contains("condition that would make the acceptance invalid"),
      readmeText,
    )
    assertTrue(readmeText.contains("## Audited timeout causes"), readmeText)
    assertTrue(
      normalizedReadme.contains("record the class and method, the observed local behavior") &&
          normalizedReadme.contains("deterministic seams or budgets tried") &&
          normalizedReadme.contains("fixture safety bound") &&
          normalizedReadme.contains("measured structural cause"),
      readmeText,
    )
    (HardeningOptionNames.removedWriterProperties.map { "-P$it" } + listOf(
      "Preserve row identity",
      "named writer tasks",
      "<suite>-timeouts.csv",
      "ArcMutate `[history]` report",
      "cause:liveness",
      "cause:harness",
      "duration * timeoutFactor + timeoutConst",
      "sole transient locators",
      "full update",
      "green prune",
    )).forEach { copiedMechanic ->
      assertFalse(
        readmeText.contains(copiedMechanic),
        "seeded consumer evidence copied plugin mechanics '$copiedMechanic':\n$readmeText",
      )
    }
    assertTrue(first.output.contains("appended .pitest-history/ to"), first.output)
    assertTrue(gitignore.readText().contains("\n.pitest-history/\n"), gitignore.readText())
    assertTrue(first.output.contains("remaining adoption steps"), first.output)
    // the checklist hands over the acknowledgment marker agentsTemplateInSync expects
    assertTrue(
      Regex("<!-- hardening-template sha256:[0-9a-f]{12} -->").containsMatchIn(first.output),
      "digest marker missing from the checklist:\n" + first.output
    )
    assertTrue(
      first.output.contains("bounded agent-instructions template") &&
          first.output.contains("./gradlew :pitest<Suite>BaselineUpdate") &&
          first.output.contains("./gradlew :pitest<Suite>TimeoutAuditInit") &&
          first.output.contains("./gradlew :hardeningAgentTemplate") &&
          first.output.contains("./gradlew :hardeningAgentTemplateDiff") &&
          first.output.contains("pre-release :hardeningCertify run") &&
          first.output.contains("local :fuzzAll -PmaxFuzzTime") &&
          first.output.contains("hardeningAgentTemplateDiff") &&
          first.output.contains("review-only diff") &&
          first.output.contains("never edits AGENTS.md"),
      "the checklist did not make the bounded upgrade diff first-class:\n${first.output}",
    )

    // a second run changes nothing: same README bytes, no duplicated ignore line
    val readmeBefore = readme.readText()
    val gitignoreBefore = gitignore.readText()
    val second = runner().build()
    assertTrue(second.output.contains("exists — left untouched"), second.output)
    assertTrue(second.output.contains(".gitignore already covers .pitest-history/"), second.output)
    assertTrue(
      second.output.contains("Configuration cache entry reused."),
      "hardeningInit did not reuse the fixture's configuration cache:\n" + second.output,
    )
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
      "build/\n\n# machine-local hardening state (PIT history and release evidence)\n.pitest-history/\n",
      File(fixtureDir, ".gitignore").readText()
    )
  }
}
