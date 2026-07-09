val (gprUser, gprToken) = githubPackagesCredentials()

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  @Suppress("UnstableApiUsage")
  repositories {
    mavenCentral()
    if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
      val githubPackageUrls = setOf(
        "https://maven.pkg.github.com/sava-software/solana-version-catalog",
        "https://maven.pkg.github.com/sava-software/json-iterator",
        "https://maven.pkg.github.com/sava-software/sava",
        "https://maven.pkg.github.com/sava-software/solana-programs",
        "https://maven.pkg.github.com/sava-software/solana-web2",
        "https://maven.pkg.github.com/sava-software/ravina",
        "https://maven.pkg.github.com/sava-software/idl-clients",
        "https://maven.pkg.github.com/sava-software/http-servers",
        "https://maven.pkg.github.com/sava-software/incident-client"
      ) + extraGithubPackageRepos()
      githubPackageUrls.forEach { repoUrl ->
        maven {
          url = uri(repoUrl)
          credentials {
            username = gprUser
            password = gprToken
          }
        }
      }
    } else {
      logger.warn(
        "GitHub Packages repositories disabled: set the Gradle properties " +
          "'savaGithubPackagesUsername' and 'savaGithubPackagesPassword' " +
          "(or the GITHUB_ACTOR and GITHUB_TOKEN environment variables) to enable them. " +
          "Dependencies hosted on GitHub Packages will fail to resolve."
      )
    }
  }
}
