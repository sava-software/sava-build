import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The local-repo notice: a consumer resolving 'software.sava.build*' plugins from a
 * local checkout is told so on every build.
 *
 * The configuration cache case is the point of the feature, not an edge of it. The
 * notice began as a 'logger.warn' in the consumer's settings script, where a cache hit
 * skips it entirely — so it printed on the run that populated the entry and stayed
 * quiet afterwards, including on the forgotten-publish run it exists to catch. Asserting
 * only a first run would pass against that bug.
 */
class LocalRepoNoticeFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private fun writeFixture() {
    // Consumers enable the configuration cache in gradle.properties rather than per
    // invocation; the fixture mirrors that, and the reuse test below also passes the
    // flag so the run it asserts on is unambiguous.
    File(fixtureDir, "gradle.properties").writeText(
      "org.gradle.configuration-cache=true\norg.gradle.caching=true\n"
    )
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "local-repo-notice"
      """.trimIndent() + "\n"
    )
  }

  private fun runBuild(vararg arguments: String): BuildResult = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments)
    .build()

  private val localRepo: String = savaBuildTestProperty("savaBuild.testRepo")

  @Test
  fun `notice survives configuration cache reuse`() {
    writeFixture()
    val first = runBuild("help", "--configuration-cache", "-PsavaBuildLocalRepo=$localRepo")
    assertTrue(first.output.contains("resolved every 'software.sava.build*' plugin to $savaBuildTestRepoVersion"), first.output)
    assertTrue(first.output.contains("last publish"), first.output)

    val second = runBuild("help", "--configuration-cache", "-PsavaBuildLocalRepo=$localRepo")
    // Without the reuse the second run proves nothing: it would just be a second miss.
    assertTrue(second.output.contains("Reusing configuration cache"), second.output)
    assertTrue(second.output.contains("resolved every 'software.sava.build*' plugin to $savaBuildTestRepoVersion"), second.output)
  }

  @Test
  fun `notice names a local repo that was never published to`() {
    writeFixture()
    val unpublished = File(fixtureDir, "never-published").absolutePath
    val result = runBuild("help", "-PsavaBuildLocalRepo=$unpublished")
    assertTrue(result.output.contains("NO $savaBuildTestRepoVersion PUBLISH FOUND THERE"), result.output)
  }

  @Test
  fun `published resolution stays quiet`() {
    writeFixture()
    val result = runBuild("help")
    assertFalse(result.output.contains("software.sava.build*"), result.output)
  }
}
