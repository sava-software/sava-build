import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A fixture under the fixture policy refuses what nothing vouches for. The plugin under test and
 * its dependencies resolve, the first from the signed copy of the test publication and the rest
 * under the committed rules, and a module the fixture serves itself resolves when the run's key
 * signed it for one of the local groups and is refused otherwise: with no signature, with one that
 * no longer matches the bytes, and in a group the key is not trusted for. Each refusal is Gradle's
 * own, before the artifact reaches any classpath. A fixture outside the fixture policy verifies
 * nothing, so it fetches nothing: offline, in a Gradle user home of its own, it finds only what a
 * strict build put there, and reads a file repository of its own as it is.
 */
class FixtureVerificationFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val repository get() = File(fixtureDir, "repo")

  @BeforeEach
  fun setUp() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun module(group: String, name: String): File {
    val version = File(repository, "${group.replace('.', '/')}/$name/1.0").apply { mkdirs() }
    File(version, "$name-1.0.pom").writeText(
      "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>" +
        "<artifactId>$name</artifactId><version>1.0</version></project>\n"
    )
    return File(version, "$name-1.0.jar").apply { writeText("$group:$name:1.0") }
  }

  private fun writeFixture(dependency: String) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "fixture-verification"

        dependencyResolutionManagement {
          repositories { maven { url = uri("repo") } }
        }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        val probe = configurations.create("probe")
        dependencies { probe("$dependency") }
        tasks.register("resolveProbe") {
          val files = probe.incoming.files
          doLast { files.files.forEach { println("RESOLVED " + it.name) } }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner() = savaBuildRunner(fixtureDir, "resolveProbe", "--stacktrace")

  private fun assertRefused(result: BuildResult, artifact: String) {
    assertTrue(result.output.contains("Dependency verification failed"), result.output)
    assertTrue(result.output.contains(artifact), result.output)
    assertFalse(result.output.contains("RESOLVED"), result.output)
  }

  @Test
  fun `the plugin and a local module signed by the run's key resolve under the strict policy`() {
    module("org.example", "signed")
    FixtureVerification.signRepository(repository)
    writeFixture("org.example:signed:1.0")
    val (cold, reused) = assertConfigurationCacheRoundTrip { runner().build() }
    assertTrue(cold.output.contains("RESOLVED signed-1.0.jar"), cold.output)
    assertTrue(reused.output.contains("RESOLVED signed-1.0.jar"), reused.output)
    assertFalse(cold.output.contains("Dependency verification failed"), cold.output)
  }

  @Test
  fun `a local module with no signature is refused`() {
    module("org.example", "unsigned")
    writeFixture("org.example:unsigned:1.0")
    assertRefused(runner().buildAndFail(), "unsigned-1.0.jar (org.example:unsigned:1.0)")
  }

  @Test
  fun `a local module edited after it was signed is refused, and resolves once signed again`() {
    val jar = module("org.example", "edited")
    FixtureVerification.signRepository(repository)
    jar.appendText(" and more")
    writeFixture("org.example:edited:1.0")
    assertRefused(runner().buildAndFail(), "edited-1.0.jar (org.example:edited:1.0)")
    FixtureVerification.sign(jar)
    assertTrue(runner().build().output.contains("RESOLVED edited-1.0.jar"))
  }

  /** The fixture as an own-policy class has it: nothing of the fixture policy, and no declaration. */
  private fun withoutFixturePolicy() {
    File(fixtureDir, "gradle").deleteRecursively()
    enableTestKitConfigurationCache(fixtureDir, fixturePolicy = false)
  }

  @Test
  fun `a fixture outside the fixture policy runs in a Gradle user home of its own, where it cannot fetch what no strict build resolved`() {
    // A strict fixture resolves the module, so TestKit's shared Gradle user home holds it.
    writeFixture("net.sf.jopt-simple:jopt-simple:5.0.4")
    assertTrue(runner().build().output.contains("RESOLVED jopt-simple-5.0.4.jar"))
    // An own-policy fixture does not look there. It runs offline in its own Gradle user home,
    // which only the strict primer downloads into, and the primer never asked for this module.
    withoutFixturePolicy()
    File(fixtureDir, "settings.gradle.kts").appendText("println(\"GRADLE_USER_HOME=\" + gradle.gradleUserHomeDir)\n")
    val result = savaBuildRunnerWithOwnPolicy(fixtureDir, "resolveProbe", "--stacktrace").buildAndFail()
    assertTrue(result.output.contains("GRADLE_USER_HOME=${FixtureVerification.ownPolicyGradleHome.absolutePath}\n"), result.output)
    assertTrue(result.output.contains("net.sf.jopt-simple:jopt-simple:5.0.4"), result.output)
    assertTrue(result.output.contains("available for offline mode"), result.output)
    assertFalse(result.output.contains("RESOLVED"), result.output)
  }

  @Test
  fun `a fixture outside the fixture policy reads a file repository as it is`() {
    // Where the own-policy runner's guarantee ends: offline cuts off remote repositories, not
    // what the test itself wrote beside the build, and nothing verifies that there.
    module("org.example", "unsigned")
    withoutFixturePolicy()
    writeFixture("org.example:unsigned:1.0")
    val result = savaBuildRunnerWithOwnPolicy(fixtureDir, "resolveProbe", "--stacktrace").build()
    assertTrue(result.output.contains("RESOLVED unsigned-1.0.jar"), result.output)
  }

  @Test
  fun `the run's key vouches for nothing outside the local groups`() {
    module("com.elsewhere", "foreign")
    FixtureVerification.signRepository(repository)
    writeFixture("com.elsewhere:foreign:1.0")
    assertRefused(runner().buildAndFail(), "foreign-1.0.jar (com.elsewhere:foreign:1.0)")
  }
}
