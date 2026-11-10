val (gprUser, gprToken) = githubPackagesCredentials()

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  @Suppress("UnstableApiUsage")
  repositories {
    mavenCentral()
    if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
      val savaGithubPackageUrls = setOf(
        "https://maven.pkg.github.com/sava-software/solana-version-catalog",
        "https://maven.pkg.github.com/sava-software/json-iterator",
        "https://maven.pkg.github.com/sava-software/sava",
        "https://maven.pkg.github.com/sava-software/ravina",
        "https://maven.pkg.github.com/sava-software/idl-clients",
        "https://maven.pkg.github.com/sava-software/http-servers",
        "https://maven.pkg.github.com/sava-software/incident-client"
      )
      val githubPackageUrls = savaGithubPackageUrls + extraGithubPackageRepos()
      githubPackageUrls.forEach { repoUrl ->
        maven {
          url = uri(repoUrl)
          credentials {
            username = gprUser
            password = gprToken
          }
          if (repoUrl in savaGithubPackageUrls) {
            // Public Sava artifacts remain eligible for Maven Central above. Extra
            // repositories may host other groups (for example systems.glam).
            content { includeGroup("software.sava") }
          }
        }
      }
    } else {
      logger.warn(
        "GitHub Packages repositories disabled: set the Gradle properties " +
          "'savaGithubPackagesUsername' and 'savaGithubPackagesPassword' " +
          "(or the ORG_GRADLE_PROJECT_savaGithubPackages* environment variables) to enable them. " +
          "Dependencies hosted on GitHub Packages will fail to resolve."
      )
    }
  }
}
