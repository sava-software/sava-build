import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningAgentProseAuditFunctionalTest {

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

  private fun writeRootFixture(agents: String) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-agent-prose-audit-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "AGENTS.md").writeText(agents)
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--stacktrace")

  @Test
  fun `outside plugin mechanics are a line-addressed non-failing advisory`() {
    writeRootFixture(
      """
      # Agents

      $BLOCK_START
      - Shared generated rule.
      $BLOCK_END

      A suite with nothing unkilled has no accepted file at all.
      <!-- hardening-template sha256:0123456789ab -->
      """.trimIndent() + "\n"
    )

    val output = runner("hardeningAgentProseAudit").build().output

    assertTrue(output.contains("1 likely copied plugin-mechanics passage"), output)
    assertTrue(output.contains("AGENTS.md:7"), output)
    assertTrue(output.contains("Disposition: This is a non-failing migration advisory"), output)
    assertTrue(output.contains("repository AGENTS.md: 1 likely copied plugin-mechanics passage"), output)
    assertTrue(output.contains("BUILD SUCCESSFUL"), output)
  }

  @Test
  fun `the generated block is outside the prose audit`() {
    writeRootFixture(
      """
      # Agents

      $BLOCK_START
      - A suite with nothing unkilled has no accepted file at all.
      $BLOCK_END
      <!-- hardening-template sha256:0123456789ab -->
      """.trimIndent() + "\n"
    )

    val output = runner("hardeningAgentProseAudit").build().output

    assertFalse(output.contains("likely copied plugin-mechanics"), output)
    assertFalse(output.contains("advisory finding"), output)
    assertTrue(output.contains("BUILD SUCCESSFUL"), output)
  }

  @Test
  fun `a missing acknowledgment is left to the template sync gate`() {
    writeRootFixture(
      """
      # Agents

      $BLOCK_START
      - Shared generated rule.
      $BLOCK_END

      A suite with nothing unkilled has no accepted file at all.
      """.trimIndent() + "\n"
    )

    val output = runner("hardeningAgentProseAudit").build().output

    assertFalse(output.contains("likely copied plugin-mechanics"), output)
    assertFalse(output.contains("advisory finding"), output)
    assertTrue(output.contains("BUILD SUCCESSFUL"), output)
  }

  @Test
  fun `repository audit reports once when every applying project selects it`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-agent-prose-audit-repository-scope-test"
        include("a", "b")
      """.trimIndent() + "\n"
    )
    val moduleBuild =
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }
      """.trimIndent() + "\n"
    listOf("a", "b").forEach { name ->
      File(fixtureDir, name).apply { mkdirs() }
        .resolve("build.gradle.kts").writeText(moduleBuild)
    }
    File(fixtureDir, "AGENTS.md").writeText(
      """
      # Agents

      $BLOCK_START
      - Shared generated rule.
      $BLOCK_END

      `agentsTemplateInSync` fails when the installed digest changes.
      <!-- hardening-template sha256:0123456789ab -->
      """.trimIndent() + "\n"
    )

    val output = runner("hardeningAgentProseAudit", "--parallel").build().output
    val warnings = output.split(
      "hardeningAgentProseAudit: AGENTS.md carries 1 likely copied plugin-mechanics passage"
    ).size - 1

    assertTrue(warnings == 1, "repository warning printed $warnings times:\n$output")
    assertTrue(output.contains("hardening: 1 advisory finding across 1 scope"), output)
    assertTrue(output.contains("> Task :a:hardeningAgentProseAudit"), output)
    assertTrue(output.contains("> Task :b:hardeningAgentProseAudit"), output)
  }
}
