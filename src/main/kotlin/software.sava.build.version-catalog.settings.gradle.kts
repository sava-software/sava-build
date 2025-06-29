val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
  .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_savaGithubPackagesUsername"))
  .orElse("")
val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
  .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_savaGithubPackagesPassword"))
  .orElse("")

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    mavenCentral()
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    gradlePluginPortal()
  }
  versionCatalogs {
    create("savaCatalog") {
      from("software.sava:solana-version-catalog:${solanaBOMVersion()}")
    }
  }
}
