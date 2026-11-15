rootProject.name = "sava-build"

// The build's own dependency repositories, declared in settings as the plugin's
// base.repositories settings convention does for consumers, rather than in build.gradle.kts;
// this build cannot apply its own conventions to itself. The mode makes a project-level
// repositories block fail instead of silently taking precedence.
dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  @Suppress("UnstableApiUsage")
  repositories {
    // Central first, as in that convention: a project dependency Central hosts is fetched from
    // it directly, not through the Plugin Portal's redirect, and only what Central lacks (the
    // Gradle plugins this build uses as libraries) falls through to the Portal. Plugin
    // resolution is not declared here and still goes through the Portal.
    mavenCentral()
    gradlePluginPortal()
  }
}
