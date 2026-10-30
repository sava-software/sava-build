rootProject.name = "sava-build"

// The build's own dependency repository, declared in settings as the plugin's
// base.repositories settings convention does for consumers, rather than in build.gradle.kts;
// this build cannot apply its own conventions to itself. The mode makes a project-level
// repositories block fail instead of silently taking precedence.
dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  @Suppress("UnstableApiUsage")
  repositories {
    gradlePluginPortal()
  }
}
