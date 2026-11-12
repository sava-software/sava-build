import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.jmh.JmhPluginRequirement
import software.sava.build.jmh.JmhToolDefaults
import java.io.File

/**
 * 'software.sava.build.feature.jmh' configures the JMH Gradle plugin (me.champeau.jmh) but does
 * not ship it: the published module declares no dependency on the plugin, a consumer that never
 * benchmarks never resolves it, and a benchmark build declares it in the plugins block that
 * declares the feature's version, with the declaration the guard prints. The feature then applies
 * the plugin, from a settings classpath declaration as from a standalone build's own
 * (JmhFeatureSmokeTest, JmhFeatureRunTest), and refuses, by name, a plugin that is missing,
 * declared where its classes cannot see it (the alternative is a NoClassDefFoundError from the
 * conventions, or Gradle's "plugin not found" for an id the consumer never wrote), or older than
 * the one the feature is compiled against. Every graph here runs with --warning-mode=fail, so a
 * deprecation on the feature's own application path fails the success case.
 */
class JmhPluginIsolationFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun runner(vararg arguments: String): FixtureRunner = savaBuildRunner(fixtureDir, *arguments, "--stacktrace", "--warning-mode=fail")

  /** The fixture's settings script: the test repo, the given settings plugin lines, and a tail. */
  private fun writeSettings(name: String, vararg plugins: String, tail: String = "") {
    val block = if (plugins.isEmpty()) "" else plugins.joinToString("\n", "plugins {\n", "\n}\n\n") { "  $it" }
    File(fixtureDir, "settings.gradle.kts").writeText("$savaBuildPluginManagement\n\n${block}rootProject.name = \"$name\"\n$tail")
  }

  /** A java project applying the feature, with the given plugin lines before it. */
  private fun writeBuild(vararg plugins: String, tail: String = "") {
    val lines = (listOf("java") + plugins + "id(\"software.sava.build.feature.jmh\")").joinToString("\n") { "  $it" }
    File(fixtureDir, "build.gradle.kts").writeText("plugins {\n$lines\n}\n$tail")
  }

  /** The build failed on the guard's message that starts with [phrase], printing the declaration, and never on a missing class. */
  private fun assertRefused(result: BuildResult, phrase: String, vararg alsoPrinted: String) {
    assertTrue(result.output.contains(phrase), result.output)
    assertTrue(result.output.contains(JmhPluginRequirement.declaration()), result.output)
    for (text in alsoPrinted) assertTrue(result.output.contains(text), result.output)
    assertFalse(result.output.contains("NoClassDefFoundError"), result.output)
  }

  @Test
  fun `the published module declares no dependency on the JMH Gradle plugin`() {
    val module = savaBuildTestArtifact("module").readText()
    val pom = savaBuildTestArtifact("pom").readText()
    // A dependency the module keeps, so an empty or differently shaped file cannot pass.
    assertTrue(module.contains("\"module\": \"java-module-dependencies\""), module)
    assertTrue(pom.contains("<artifactId>java-module-dependencies</artifactId>"), pom)
    assertFalse(module.contains("me.champeau.jmh"), "the module metadata declares the JMH Gradle plugin:\n$module")
    assertFalse(pom.contains("me.champeau.jmh"), "the POM declares the JMH Gradle plugin:\n$pom")
  }

  @Test
  fun `a settings consumer resolves no JMH artifact onto its settings classpath`() {
    writeSettings(
      "no-jmh", "id(\"software.sava.build\")",
      tail = "\n// Every component the settings plugins block resolved, on one line the test reads.\n" +
        "println(\"SETTINGS_CLASSPATH=\" + buildscript.configurations.getByName(\"classpath\")" +
        ".incoming.resolutionResult.allComponents.joinToString(\",\") { it.id.displayName })\n",
    )
    val result = runner("help").build()
    val classpath = result.output.lineSequence().firstOrNull { it.startsWith("SETTINGS_CLASSPATH=") }
      ?: error("no SETTINGS_CLASSPATH line:\n${result.output}")
    assertTrue(classpath.contains("software.sava:sava-build:$savaBuildTestRepoVersion"), classpath)
    assertTrue(classpath.contains("org.gradlex:java-module-dependencies:"), classpath)
    assertFalse(classpath.contains("me.champeau.jmh"), "the settings classpath resolves the JMH Gradle plugin: $classpath")
    assertFalse(classpath.contains("org.openjdk.jmh"), "the settings classpath resolves the JMH harness: $classpath")
  }

  @Test
  fun `the feature applies a JMH plugin declared on the settings classpath`() {
    writeSettings("jmh-from-settings", "id(\"software.sava.build\")", JmhPluginRequirement.declaration())
    writeBuild(
      tail = """

        // Values the plugin leaves unset (fork) or empty (jvmArgsAppend) and only the feature's
        // conventions fill, read at configuration time and checked at execution.
        tasks.register("verifyJmhWired") {
          val forks = jmh.fork
          val jvmArgs = jmh.jvmArgsAppend
          doLast {
            check(forks.get() == 1) { "unexpected fork: " + forks.get() }
            check(jvmArgs.get().contains("-XX:+UseZGC")) { "missing ZGC flag: " + jvmArgs.get() }
          }
        }
      """.trimIndent() + "\n",
    )
    val (cold, reused) = assertConfigurationCacheRoundTrip { runner("verifyJmhWired").build() }
    assertEquals(TaskOutcome.SUCCESS, cold.task(":verifyJmhWired")?.outcome, cold.output)
    assertEquals(TaskOutcome.SUCCESS, reused.task(":verifyJmhWired")?.outcome, reused.output)
    // The floor read the version on this path: the warning for an unreadable one is absent.
    assertFalse(cold.output.contains("could not read the version of the JMH Gradle plugin"), cold.output)
  }

  @Test
  fun `the feature refuses to run without the JMH plugin and names the declaration`() {
    writeSettings("jmh-missing")
    writeBuild()
    val result = runner("help").buildAndFail()
    assertRefused(
      result,
      "software.sava.build.feature.jmh configures the JMH Gradle plugin (me.champeau.jmh) but does not ship it, and none is declared where the feature can see it",
      JmhPluginRequirement.dependency(),
    )
    // What an apply of the id by the feature itself would have said instead.
    assertFalse(result.output.contains("Plugin with id '${JmhPluginRequirement.PLUGIN_ID}' not found"), result.output)
  }

  @Test
  fun `the feature refuses a JMH plugin declared below its classpath`() {
    // The feature comes from the settings classpath; the plugin is declared in the project's own
    // plugins block, a classloader below it, which Gradle can apply but the feature cannot see.
    writeSettings("jmh-below", "id(\"software.sava.build\")")
    writeBuild(JmhPluginRequirement.declaration())
    val result = runner("help").buildAndFail()
    assertRefused(
      result,
      "on this project's classpath but not on the one the feature loads from",
      JmhPluginRequirement.dependency(),
    )
  }

  @Test
  fun `the feature refuses a JMH plugin older than the one it is compiled against`() {
    // 0.7.3 hands failOnError to JMH as '-foe 1', which JMH reads as false: the floor the feature's
    // former implementation dependency held through conflict resolution, now held by the guard.
    val older = "0.7.3"
    assertTrue((JmhPluginRequirement.compareToCompiledAgainst(older) ?: 0) < 0, "fixture version is not older than ${JmhToolDefaults.JMH_GRADLE_PLUGIN}")
    writeSettings("jmh-older")
    writeBuild("id(\"me.champeau.jmh\") version \"$older\" apply false")
    val refusal = "is compiled and tested against ${JmhToolDefaults.JMH_GRADLE_PLUGIN} and the plugin on its classpath is $older, which the feature refuses"
    assertRefused(runner("help").buildAndFail(), refusal, JmhPluginRequirement.dependency())
    // The same floor on the settings classpath, where the plugin is declared beside the settings plugin.
    writeSettings("jmh-older-settings", "id(\"software.sava.build\")", "id(\"me.champeau.jmh\") version \"$older\" apply false")
    writeBuild()
    assertRefused(runner("help").buildAndFail(), refusal)
  }
}
