val (gprUser, gprToken) = githubPackagesCredentials()

dependencyResolutionManagement {
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
        "https://maven.pkg.github.com/sava-software/anchor-src-gen",
        "https://maven.pkg.github.com/sava-software/anchor-programs",
        "https://maven.pkg.github.com/sava-software/ravina",
        "https://maven.pkg.github.com/sava-software/idl-clients",
        "https://maven.pkg.github.com/glamsystems/ix-proxy",
        "https://maven.pkg.github.com/glamsystems/glam-sdk-java"
      )
      githubPackageUrls.forEach { repoUrl ->
        maven {
          url = uri(repoUrl)
          credentials {
            username = gprUser
            password = gprToken
          }
        }
      }
    }
  }
}
