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

  @Test
  fun `the fleet canary resolution needle matches the notice`() {
    // tools/fleet-canary.sh fails a green consumer build that does not print this
    // line: a settings snippet predating -PsavaBuildLocalRepo ignores the property
    // and resolves the released plugin — green output that canaries nothing. The
    // needle is deliberately coupled to the notice's wording; this pins it so a
    // reworded notice fails here instead of making the canary flag every healthy
    // repo. Asserted in both directions, because a needle matching a non-resolving
    // build would silence the check just as thoroughly.
    val script = File(savaBuildTestProperty("savaBuild.root"), "tools/fleet-canary.sh").readText()
    val needle = Regex("(?m)^resolution_notice=\"([^\"]+)\"").find(script)?.groupValues?.get(1)
      ?: error("resolution_notice line not found in tools/fleet-canary.sh")

    writeFixture()
    val resolving = runBuild("help", "-PsavaBuildLocalRepo=$localRepo")
    assertTrue(
      resolving.output.contains(needle),
      "canary needle '$needle' missing from a resolving build:\n${resolving.output}"
    )
    val published = runBuild("help")
    assertFalse(
      published.output.contains(needle),
      "canary needle '$needle' matches a build that resolved nothing locally:\n${published.output}"
    )
  }
}
