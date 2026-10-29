import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * resolveVerificationTools resolves the PIT, ArcMutate and Jazzer artifacts that only the
 * mutation and fuzz tasks resolve, so a dependency-verification regeneration and its
 * validation cover them without a campaign: each hardened project resolves its own two
 * configurations, under the configuration cache and in parallel, and an emptied
 * configuration fails the task instead of leaving its tools unverified.
 */
class ResolveVerificationToolsFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private fun writeFixture(vararg projects: String) {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "resolve-verification-tools"
        ${projects.joinToString("\n        ") { "include(\"$it\")" }}
      """.trimIndent() + "\n"
    )
    val build = """
      plugins {
        java
        id("software.sava.build.feature.hardening")
      }
    """.trimIndent() + "\n"
    if (projects.isEmpty()) {
      File(fixtureDir, "build.gradle.kts").writeText(build)
    } else {
      projects.forEach { File(fixtureDir, it).mkdirs(); File(fixtureDir, "$it/build.gradle.kts").writeText(build) }
    }
  }

  private fun run(vararg arguments: String): BuildResult = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--no-watch-fs")
    .build()

  @Test
  fun `resolves both tool configurations, and is configuration-cache safe`() {
    writeFixture()
    val first = run("resolveVerificationTools")
    assertEquals(TaskOutcome.SUCCESS, first.task(":resolveVerificationTools")?.outcome, first.output)
    assertTrue(Regex("Resolved pitest: [1-9][0-9]* artifacts").containsMatchIn(first.output), first.output)
    assertTrue(Regex("Resolved jazzer: [1-9][0-9]* artifacts").containsMatchIn(first.output), first.output)
    // A reused entry replays the stored artifact set and resolves nothing, which is why the
    // README's validation runs without the configuration cache; only the outcome is asserted.
    val second = run("resolveVerificationTools")
    assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
    assertEquals(TaskOutcome.SUCCESS, second.task(":resolveVerificationTools")?.outcome, second.output)
  }

  @Test
  fun `each hardened project resolves its own configurations in parallel`() {
    writeFixture("one", "two")
    val result = run("resolveVerificationTools", "--parallel")
    listOf(":one", ":two").forEach {
      assertEquals(TaskOutcome.SUCCESS, result.task("$it:resolveVerificationTools")?.outcome, result.output)
    }
    assertEquals(2, Regex("Resolved pitest: [1-9][0-9]* artifacts").findAll(result.output).count(), result.output)
  }

  @Test
  fun `an emptied tool configuration fails the task`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """
        configurations.named("jazzer") { withDependencies { clear() } }
      """.trimIndent() + "\n"
    )
    val result = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("resolveVerificationTools", "--no-watch-fs")
      .buildAndFail()
    assertTrue(result.output.contains("resolved no artifacts for its 'jazzer' configuration"), result.output)
  }
}
