val gprUser = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orElse("")
val gprToken = providers.gradleProperty("gpr.token").orElse(providers.environmentVariable("GITHUB_TOKEN")).orElse("")

dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    mavenCentral()
//    maven {
//      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
//      credentials {
//        username = gprUser.get()
//        password = gprToken.get()
//      }
//    }
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
  }
}
