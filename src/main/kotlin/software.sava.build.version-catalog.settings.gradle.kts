val (gprUser, gprToken) = githubPackagesCredentials()


dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  @Suppress("UnstableApiUsage")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    if (!gprUser.isNullOrBlank() && !gprToken.isNullOrBlank()) {
      maven {
        url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    } else {
      logger.warn(
        "GitHub Packages repository for the sava version catalog disabled: set the Gradle " +
          "properties 'savaGithubPackagesUsername' and 'savaGithubPackagesPassword' " +
          "(or the ORG_GRADLE_PROJECT_savaGithubPackages* environment variables) to enable it."
      )
    }
  }
  versionCatalogs {
    create("savaCatalog") {
      from("software.sava:solana-version-catalog:${solanaBOMVersion()}")
    }
  }
}
