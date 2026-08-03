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
