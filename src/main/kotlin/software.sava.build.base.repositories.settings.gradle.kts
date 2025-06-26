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
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/json-iterator")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/sava")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/solana-programs")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/solana-web2")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/anchor-src-gen")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/anchor-programs")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/ravina")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
    maven {
      url = uri("https://maven.pkg.github.com/glamsystems/ix-proxy")
      credentials {
        username = gprUser.get()
        password = gprToken.get()
      }
    }
  }
}
