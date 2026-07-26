import java.io.File

/**
 * pluginManagement block for TestKit fixture settings scripts: resolves every
 * 'software.sava.build*' plugin id from the local Maven repo this build publishes
 * (the 'savaBuildTest' publication), with gradlePluginPortal serving the plugin's own
 * dependencies. Fixtures used to includeBuild this checkout instead, but an included
 * build recompiles the plugin inside the first TestKit daemon and serializes parallel
 * test forks on this checkout's build locks.
 */
val savaBuildTestRepoVersion: String = System.getProperty("savaBuild.testRepo.version")

val savaBuildPluginManagement: String = run {
  val repo = File(System.getProperty("savaBuild.testRepo"))
    .absolutePath.replace("\\", "\\\\")
  "pluginManagement { repositories { maven(url = \"$repo\"); gradlePluginPortal() }; " +
    "resolutionStrategy.eachPlugin { if (requested.id.id.startsWith(\"software.sava.build\")) { " +
    "useModule(\"software.sava:sava-build:$savaBuildTestRepoVersion\") } } }"
}
