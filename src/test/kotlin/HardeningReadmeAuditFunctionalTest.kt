import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningReadmeAuditFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun writeFixture(readme: String? = null) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-readme-audit-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }

        hardening {
          mutation.register("encoding") {
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
          }
        }
      """.trimIndent() + "\n"
    )
    readme?.let { content ->
      File(fixtureDir, "config/pitest/README.md").also { it.parentFile.mkdirs() }
        .writeText(content)
    }
  }

  private fun run(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--stacktrace")
    .build()

  @Test
  fun `audit reports both migration categories without failing`() {
    writeFixture(
      """
      # Local mutation evidence

      Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the run.
      `Codec.encode:219` is the accepted semantic branch.
      """.trimIndent() + "\n"
    )

    val output = run("hardeningReadmeAudit").output

    assertTrue(output.contains("1 likely source-line locator"), output)
    assertTrue(output.contains("1 inherited scaffold-mechanics passage"), output)
    assertTrue(output.contains("README.md:4  `Codec.encode:219`"), output)
    assertTrue(output.contains("Disposition: This is a non-failing migration advisory"), output)
    assertTrue(output.contains("BUILD SUCCESSFUL"), output)
    assertTrue(output.contains("2 advisory findings across 1 scope"), output)
  }

  @Test
  fun `clean and missing READMEs are silent`() {
    writeFixture("# Local evidence\n\n`Codec.encode` checks the empty-input branch.\n")
    val clean = run("hardeningReadmeAudit").output
    assertFalse(clean.contains("migration advisory"), clean)
    assertFalse(clean.contains("advisory finding"), clean)

    File(fixtureDir, "config/pitest/README.md").delete()
    val missing = run("hardeningReadmeAudit").output
    assertFalse(missing.contains("migration advisory"), missing)
    assertFalse(missing.contains("advisory finding"), missing)
  }

  @Test
  fun `normal gates and suite verify select the project audit once`() {
    writeFixture()
    listOf("check", "qualityGate", "pitestEncodingVerify").forEach { selected ->
      val output = run(selected, "--dry-run").output
      assertEqualsOnce(output, ":hardeningReadmeAudit SKIPPED")
    }
  }

  private fun assertEqualsOnce(output: String, expected: String) {
    assertTrue(output.contains(expected), "missing '$expected':\n$output")
    assertTrue(
      output.indexOf(expected) == output.lastIndexOf(expected),
      "duplicate '$expected':\n$output",
    )
  }
}
