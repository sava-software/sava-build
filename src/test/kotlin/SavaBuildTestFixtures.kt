import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

/**
 * pluginManagement block for TestKit fixture settings scripts: resolves every
 * 'software.sava.build*' plugin id from the local Maven repo this build publishes
 * (the 'savaBuildTest' publication), with gradlePluginPortal serving the plugin's own
 * dependencies. Fixtures used to includeBuild this checkout instead, but an included
 * build recompiles the plugin inside the first TestKit daemon and serializes parallel
 * test forks on this checkout's build locks.
 */
val savaBuildTestRepoVersion: String = savaBuildTestProperty("savaBuild.testRepo.version")

val savaBuildPluginManagement: String = run {
  val repo = File(savaBuildTestProperty("savaBuild.testRepo"))
    .absolutePath.replace("\\", "\\\\")
  "pluginManagement { repositories { maven(url = \"$repo\"); gradlePluginPortal() }; " +
    "resolutionStrategy.eachPlugin { if (requested.id.id.startsWith(\"software.sava.build\")) { " +
    "useModule(\"software.sava:sava-build:$savaBuildTestRepoVersion\") } } }"
}

/**
 * The ':test' task supplies these; reading one straight into a non-null 'String' would
 * fail every test in the suite with a bare 'ExceptionInInitializerError' when the class
 * is initialized without them -- which is what running a test from the IDE's own JUnit
 * runner, rather than delegating to Gradle, does.
 */
fun savaBuildTestProperty(name: String): String = System.getProperty(name)
  ?: error("System property '$name' is not set; run the tests through Gradle (./gradlew test).")

/**
 * A repository file, or a directory read whole, that a test reads, by its path under the checkout.
 * The ':test' task declares the paths it passes in 'savaBuild.rootFiles' as inputs
 * (rootFilesReadByTests in build.gradle.kts), sources included, since a test asserting over source
 * text needs the text as an input and not only the classes compiled from it; so an edit under one
 * re-runs the tests, and a path that is neither declared nor under a declared directory is refused
 * here rather than read, since the task would stay up to date after the very edit such a read
 * checks for. RootFileReadsTest holds every read to this function.
 */
fun savaBuildRootFile(path: String): File {
  val declared = savaBuildTestProperty("savaBuild.rootFiles").split(',')
  require(declared.any { it == path || path.startsWith("$it/") }) {
    "$path is not among the files build.gradle.kts declares the tests read through the root " +
      "(rootFilesReadByTests, declared: $declared): add it there, or :test stays up to date when it changes"
  }
  return File(savaBuildTestProperty("savaBuild.root"), path)
}

/**
 * The checkout, for a test that runs git there and for nothing else: it keeps its directory to
 * itself, so a file in it is read through savaBuildRootFile or not at all.
 */
class SavaBuildCheckout internal constructor(private val directory: File) {

  /** Runs git in the checkout and returns its output; a non-zero exit fails with that output. */
  fun git(vararg arguments: String): ByteArray {
    val process = ProcessBuilder(listOf("git") + arguments)
      .directory(directory)
      .redirectErrorStream(true)
      .apply { environment()["NO_DNA"] = "1" }
      .start()
    val output = process.inputStream.readBytes()
    check(process.waitFor() == 0) { output.toString(Charsets.UTF_8) }
    return output
  }
}

fun savaBuildCheckout(): SavaBuildCheckout = SavaBuildCheckout(File(savaBuildTestProperty("savaBuild.root")))

/**
 * Enables the configuration cache for every invocation in a TestKit fixture.
 *
 * Putting the switch in the consumer's `gradle.properties`, rather than selecting
 * a few task names at the runner call site, makes every task graph exercise Gradle's
 * serialization boundary. That is important for convention plugins: a new task
 * action can capture a precompiled-script instance even when the handful of release
 * entry points covered explicitly remain clean.
 */
fun enableTestKitConfigurationCache(fixtureDir: File) {
  File(fixtureDir, "gradle.properties").writeText(
    "org.gradle.configuration-cache=true\n"
  )
}

/**
 * Runs the same TestKit graph once from a cold fixture and once from its stored
 * configuration. The caller retains both results so existing functional assertions
 * can keep inspecting the execution that matters to the feature under test.
 */
fun assertConfigurationCacheRoundTrip(build: () -> BuildResult): Pair<BuildResult, BuildResult> {
  val cold = build()
  assertFalse(
    cold.output.contains("Reusing configuration cache"),
    "expected a cold configuration-cache store, but an entry was reused:\n${cold.output}",
  )
  assertTrue(
    cold.output.contains("Configuration cache entry stored"),
    "cold invocation did not store a configuration-cache entry:\n${cold.output}",
  )

  val reused = build()
  assertTrue(
    reused.output.contains("Reusing configuration cache"),
    "second invocation did not reuse the configuration cache:\n${reused.output}",
  )
  return cold to reused
}
