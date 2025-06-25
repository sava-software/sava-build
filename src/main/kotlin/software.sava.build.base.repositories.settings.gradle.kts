dependencyResolutionManagement {
  @Suppress("UnstableApiUsage")
  repositories {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-version-catalog")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/json-iterator")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-programs")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/solana-web2")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/anchor-src-gen")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/anchor-programs")
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/ravina")
      credentials(PasswordCredentials::class)
    }
    mavenCentral()
  }
}
