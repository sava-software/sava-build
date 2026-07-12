import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Configuration-phase smoke test for 'software.sava.build.feature.jmh': a plain
 * java consumer applies the plugin via 'pluginManagement { includeBuild(...) }'
 * and lists tasks, catching plugin wiring or champeau-plugin API breakage
 * without resolving benchmark dependencies.
 */
class JmhFeatureSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  @Test
  fun `jmh feature configures benchmark task and service jvm args`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "jmh-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.jmh")
        }

        repositories {
          mavenCentral()
        }

        // Surface the convention's wiring at configuration time. Expected
        // values arrive as 'expected*' properties so one task body verifies
        // both the defaults and the '-Pjmh*' override runs.
        tasks.register("verifyJmhConventions") {
          val forks = jmh.fork
          val included = jmh.includes
          val warmups = jmh.warmupIterations
          val measures = jmh.iterations
          val warmupTime = jmh.warmup
          val measureTime = jmh.timeOnIteration
          val failsOnError = jmh.failOnError
          val jmhArgs = jmh.jvmArgsAppend
          val expectedForks = providers.gradleProperty("expectedForks").map(String::toInt).orElse(1)
          val expectedIncludes = providers.gradleProperty("expectedIncludes")
            .map { it.split(',') }.orElse(listOf<String>())
          val expectedWarmups = providers.gradleProperty("expectedWarmups").map(String::toInt).orElse(5)
          val expectedMeasures = providers.gradleProperty("expectedMeasures").map(String::toInt).orElse(8)
          val expectedWarmupTime = providers.gradleProperty("expectedWarmupTime").orElse("1s")
          val expectedMeasureTime = providers.gradleProperty("expectedMeasureTime").orElse("1s")
          val expectedFailOnError = providers.gradleProperty("expectedFailOnError").map(String::toBoolean).orElse(true)
          val expectedArgs = providers.gradleProperty("expectedArgs").map { it.split(' ') }
          doLast {
            check(forks.get() == expectedForks.get()) { "unexpected forks: " + forks.get() }
            check(included.get() == expectedIncludes.get()) { "unexpected includes: " + included.get() }
            check(warmups.get() == expectedWarmups.get()) { "unexpected warmupIterations: " + warmups.get() }
            check(measures.get() == expectedMeasures.get()) { "unexpected iterations: " + measures.get() }
            check(warmupTime.get() == expectedWarmupTime.get()) { "unexpected warmup: " + warmupTime.get() }
            check(measureTime.get() == expectedMeasureTime.get()) { "unexpected timeOnIteration: " + measureTime.get() }
            check(failsOnError.get() == expectedFailOnError.get()) { "unexpected failOnError: " + failsOnError.get() }
            val args = jmhArgs.get()
            if (expectedArgs.isPresent) {
              check(args == expectedArgs.get()) { "unexpected jvmArgsAppend: " + args }
            } else {
              check(args.contains("-XX:+UseZGC")) { "missing ZGC flag: " + args }
              check(args.contains("-XX:+UseCompactObjectHeaders")) { "missing compact headers flag: " + args }
            }
          }
        }
      """.trimIndent() + "\n"
    )

    val defaults = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("verifyJmhConventions", "tasks", "--group=jmh", "--stacktrace")
      .build()

    assertTrue(defaults.output.contains("jmh"), "jmh task group missing:\n" + defaults.output)
    assertFalse(defaults.output.contains("FAILED"), defaults.output)

    // Command-line overrides flow through to the extension; jmhJvmArgsAppend
    // replaces the service flag list wholesale.
    GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(
        "verifyJmhConventions", "--stacktrace",
        "-PjmhFork=3", "-PexpectedForks=3",
        "-PjmhIncludes=enumValues, kindDispatch", "-PexpectedIncludes=enumValues|kindDispatch",
        "-PjmhWarmupIterations=2", "-PexpectedWarmups=2",
        "-PjmhIterations=4", "-PexpectedMeasures=4",
        "-PjmhWarmup=500ms", "-PexpectedWarmupTime=500ms",
        "-PjmhTimeOnIteration=2s", "-PexpectedMeasureTime=2s",
        "-PjmhFailOnError=false", "-PexpectedFailOnError=false",
        "-PjmhJvmArgsAppend=-XX:+UseG1GC -Xmx4g", "-PexpectedArgs=-XX:+UseG1GC -Xmx4g"
      )
      .build()
  }
}
