import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
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

  private fun writeFixture(pluginRepo: File = File(localRepo)) {
    // Consumers enable the configuration cache in gradle.properties rather than per
    // invocation; the fixture mirrors that, and the reuse test below also passes the
    // flag so the run it asserts on is unambiguous.
    File(fixtureDir, "gradle.properties").writeText(
      "org.gradle.configuration-cache=true\norg.gradle.caching=true\n"
    )
    val escapedRepo = pluginRepo.absolutePath.replace("\\", "\\\\")
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement {
          repositories { maven(url = "$escapedRepo"); gradlePluginPortal() }
          resolutionStrategy.eachPlugin {
            if (requested.id.id.startsWith("software.sava.build")) {
              useModule("software.sava:sava-build:$savaBuildTestRepoVersion")
            }
          }
        }

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

  private fun pluginArtifact(repo: File): File = repo.resolve(
    "software/sava/sava-build/$savaBuildTestRepoVersion/" +
      "sava-build-$savaBuildTestRepoVersion.jar"
  )

  private fun copiedLocalRepo(): File {
    val copy = fixtureDir.resolve("private-test-repo")
    check(File(localRepo).copyRecursively(copy)) { "failed to copy the fixture Maven repository" }
    return copy
  }

  @Test
  fun `notice survives configuration cache reuse`() {
    val privateRepo = copiedLocalRepo()
    val expectedSha256 = PitestEvidence.sha256(pluginArtifact(privateRepo))
    writeFixture(privateRepo)
    val first = runBuild(
      "help", "--configuration-cache",
      "-PsavaBuildLocalRepo=$privateRepo",
    )
    assertTrue(first.output.contains("resolved every 'software.sava.build*' plugin to $savaBuildTestRepoVersion"), first.output)
    assertTrue(first.output.contains("last publish"), first.output)
    assertTrue(
      first.output.contains("application-time SHA-256 $expectedSha256"),
      first.output,
    )

    val second = runBuild(
      "help", "--configuration-cache",
      "-PsavaBuildLocalRepo=$privateRepo",
    )
    // Without the reuse the second run proves nothing: it would just be a second miss.
    assertTrue(second.output.contains("Reusing configuration cache"), second.output)
    assertTrue(second.output.contains("resolved every 'software.sava.build*' plugin to $savaBuildTestRepoVersion"), second.output)
    assertTrue(
      second.output.contains("application-time SHA-256 $expectedSha256"),
      second.output,
    )
  }

  @Test
  fun `configuration cache invalidates when a local artifact changes between invocations`() {
    val privateRepo = copiedLocalRepo()
    val artifact = pluginArtifact(privateRepo)
    writeFixture(privateRepo)
    val arguments = arrayOf(
      "help", "--configuration-cache",
      "-PsavaBuildLocalRepo=$privateRepo",
    )

    val stored = runBuild(*arguments)
    assertTrue(stored.output.contains("Configuration cache entry stored"), stored.output)
    artifact.appendText("republished after the configuration-cache entry was stored")
    val republishedSha256 = PitestEvidence.sha256(artifact)

    val refreshed = runBuild(*arguments)

    assertTrue(
      refreshed.output.contains("configuration cache cannot be reused because file") &&
          refreshed.output.contains("sava-build-0.0.0-test.jar' has changed"),
      refreshed.output,
    )
    assertTrue(
      refreshed.output.contains("application-time SHA-256 $republishedSha256"),
      refreshed.output,
    )
    assertFalse(refreshed.output.contains("Reusing configuration cache"), refreshed.output)
  }

  @Test
  fun `notice refuses a local plugin artifact replaced after application`() {
    val privateRepo = copiedLocalRepo()
    val artifact = pluginArtifact(privateRepo)
    writeFixture(privateRepo)
    val escapedArtifact = artifact.absolutePath.replace("\\", "\\\\")
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        val localPluginArtifact = file("$escapedArtifact")
        tasks.register("replaceLocalPlugin") {
          doLast {
            localPluginArtifact.appendText("replaced after configuration")
          }
        }
      """.trimIndent() + "\n"
    )

    val failed = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(
        "replaceLocalPlugin", "--configuration-cache", "--refresh-dependencies",
        "-PsavaBuildLocalRepo=$privateRepo", "--stacktrace",
      )
      .buildAndFail()

    assertTrue(
      failed.output.contains("local plugin artifact changed after plugin application"),
      failed.output,
    )
    assertTrue(
      failed.output.contains("refusing a build whose projects may have resolved different plugin bytes"),
      failed.output,
    )
  }

  @Test
  fun `certification preflight refuses a local artifact replaced during project configuration`() {
    val privateRepo = copiedLocalRepo()
    val artifact = pluginArtifact(privateRepo)
    writeFixture(privateRepo)
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    val escapedArtifact = artifact.absolutePath.replace("\\", "\\\\")
    listOf("a", "b").forEach { name ->
      val project = fixtureDir.resolve(name).apply { mkdirs() }
      project.resolve("build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }
          ${if (name == "a") "file(\"$escapedArtifact\").appendText(\"replaced between projects\")" else ""}
        """.trimIndent() + "\n"
      )
    }

    val failed = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(
        ":a:hardeningCertifyPreflight", ":b:hardeningCertifyPreflight",
        "--configuration-cache", "--refresh-dependencies",
        "-PsavaBuildLocalRepo=$privateRepo", "--stacktrace",
      )
      .buildAndFail()

    assertTrue(
      failed.output.contains("local plugin artifact changed after plugin application"),
      failed.output,
    )
    assertTrue(failed.output.contains("refusing mixed plugin bytes before PIT"), failed.output)
    assertFalse(fixtureDir.resolve("a/build/hardening/pitest-certification.tsv").exists())
    assertFalse(fixtureDir.resolve("b/build/hardening/pitest-certification.tsv").exists())
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
  fun `the local fuzz resolution needle matches the notice`() {
    // local-fuzz refuses a consumer build that does not print this line: an old
    // settings snippet can ignore -PsavaBuildLocalRepo and resolve the released
    // plugin while still producing a green build. Couple the runner's fixed needle
    // to both sides of the notice so a wording change cannot reject healthy local
    // resolutions or accept a published one.
    val script = File(savaBuildTestProperty("savaBuild.root"), "tools/local-fuzz.sh").readText()
    val needle = Regex("(?m)^resolution_notice=\"([^\"]+)\"").find(script)?.groupValues?.get(1)
      ?: error("resolution_notice line not found in tools/local-fuzz.sh")

    writeFixture()
    val resolving = runBuild("help", "-PsavaBuildLocalRepo=$localRepo")
    assertTrue(
      resolving.output.contains(needle),
      "local-fuzz needle '$needle' missing from a resolving build:\n${resolving.output}"
    )
    val published = runBuild("help")
    assertFalse(
      published.output.contains(needle),
      "local-fuzz needle '$needle' matches a build that resolved nothing locally:\n${published.output}"
    )
  }
}
