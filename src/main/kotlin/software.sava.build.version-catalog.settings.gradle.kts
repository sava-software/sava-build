val (gprUser, gprToken) = githubPackagesCredentials()


dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    gradlePluginPortal()
    mavenCentral()
    if (gprUser != null && gprToken != null) {
      maven {
        url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
  versionCatalogs {
    create("savaCatalog") {
      from("software.sava:solana-version-catalog:${solanaBOMVersion()}")
    }
  }
}
