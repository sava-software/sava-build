import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Smoke tests that configure a minimal consumer project against this checkout via
 * 'pluginManagement { includeBuild(...) }' — the same way consumer repositories do.
 * They only exercise the configuration phase (no dependency resolution), so they
 * need no credentials and catch plugin wiring or Gradle API breakage before a release.
 */
class SavaBuildConventionsSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture(
    savaProperties: String? = null,
    aggregationStub: Boolean = false,
    settingsSuffix: String = ""
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        plugins {
          id("software.sava.build")
          id("software.sava.build.feature.jdk-provisioning")
        }

        rootProject.name = "smoke-test"

        javaModules {
          directory(".") {
            group = "software.sava.test"
            plugin("software.sava.build.java-module")
          }
        }
      """.trimIndent() + "\n" + settingsSuffix
    )
    File(fixtureDir, "lib/src/main/java").mkdirs()
    File(fixtureDir, "lib/src/main/java/module-info.java")
      .writeText("module software.sava.test.lib {}\n")
    if (savaProperties != null) {
      File(fixtureDir, "gradle").mkdirs()
      File(fixtureDir, "gradle/sava.properties").writeText(savaProperties)
    }
    if (aggregationStub) {
      File(fixtureDir, "gradle/aggregation").mkdirs()
      File(fixtureDir, "gradle/aggregation/build.gradle.kts").writeText("")
    }
  }

  private fun runBuild(vararg arguments: String): BuildResult = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments)
    .build()

  @Test
  fun `configures with defaults when sava properties file is absent`() {
    writeFixture()
    val result = runBuild("projects")
    assertTrue(result.output.contains("Project ':lib'"), result.output)
    assertFalse(result.output.contains(":aggregation"), result.output)
  }

  @Test
  fun `includes aggregation project when its build file exists`() {
    writeFixture(savaProperties = "solanaBOMVersion=25.24.3\n", aggregationStub = true)
    val result = runBuild("projects")
    assertTrue(result.output.contains("Project ':aggregation'"), result.output)
  }

  @Test
  fun `deprecated jdk-provisioning alias still applies and warns`() {
    writeFixture(
      settingsSuffix = "apply(plugin = \"software.sava.build.feature-jdk-provisioning\")\n"
    )
    val result = runBuild("help")
    assertTrue(
      result.output.contains("'software.sava.build.feature-jdk-provisioning' is deprecated"),
      result.output
    )
  }
}
