import software.sava.build.publish.GitHubPackagesUploadTask

plugins {
  id("java-base")
  id("maven-publish")
  id("signing")
}

val orgName = orgName("Sava Software")
val orgPathSegment = orgPathSegment("sava-software")

@Suppress("UnstableApiUsage")
val productName = isolated.rootProject.name
val developerName = developerName("Jim")
val developerId = developerId("jpe7s")
val developerEmail = developerEmail("jpe7s.salt188@passfwd.com")

@Suppress("UnstableApiUsage")
val licenseName = providers.fileContents(isolated.rootProject.projectDirectory.file("LICENSE"))
  .asText.map { it.lines().first().trim() }
val vcs = "https://github.com/${orgPathSegment}/${productName}"

// Keep the signing setup in sync with the root build.gradle.kts, which duplicates it
// because it cannot apply the convention plugins it produces.
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()

val centralStagingDir = layout.buildDirectory.dir("central-portal-staging")

// publish module with sources and Javadoc
plugins.withId("java") {
  java {
    withJavadocJar()
    withSourcesJar()
  }
}

// publish platform and catalog in one component
plugins.withId("java-platform") {
  plugins.withId("version-catalog") {
    // All libraries defined in the BOM scope (api) are also included in the catalog
    configurations.named("versionCatalog") { extendsFrom(configurations["api"]) }
    // The catalog is added as an additional variant to the 'javaPlatform' component that is published
    val javaPlatform = components["javaPlatform"] as AdhocComponentWithVariants
    javaPlatform.addVariantsFromConfiguration(configurations["versionCatalogElements"]) { }
    publishing.publications.withType<MavenPublication>().configureEach {
      pom.packaging = "pom" // Ensure this is the pom, not toml, to conform to Maven BOM standards
    }
  }
}

signing {
  sign(publishing.publications)
  useInMemoryPgpKeys(signingKey, signingPassphrase)
}

tasks.withType<Sign>().configureEach { enabled = publishSigningEnabled }

publishing {
  publications.withType<MavenPublication>().configureEach {
    versionMapping {
      allVariants { fromResolutionResult() }
    }
  }

  publications {
    register<MavenPublication>("mavenJava") {
      plugins.withId("java") {
        from(components["java"])
      }
      plugins.withId("java-platform") {
        from(components["javaPlatform"])
      }
    }
    withType<MavenPublication>().configureEach {
      pom {
        name = project.name
        description = productDescription()
        url = vcs
        licenses {
          license {
            name = licenseName
            url = "$vcs/blob/main/LICENSE"
          }
        }
        developers {
          developer {
            name = developerName
            id = developerId
            email = developerEmail
            organization = orgName
            organizationUrl = "https://github.com/${orgPathSegment}"
          }
        }
        scm {
          connection = "scm:git:git@github.com:${orgPathSegment}/${productName}.git"
          developerConnection = "scm:git:ssh@github.com:${orgPathSegment}/${productName}.git"
          url = vcs
        }
      }
    }
  }

  repositories {
    maven {
      name = "savaCentralStaging"
      url = uri(centralStagingDir.get().asFile)
    }
  }
}

// GitHub Packages stores a SHA-1 and a SHA-256 checksum beside each file, no MD5, and no
// checksum of a signature. Gradle's own publisher uploads MD5 beside every file with no way
// to switch it off, so the staged publications are uploaded by hand, from the same
// repository the Central bundle is built from. maven-metadata.xml is left to the registry,
// which regenerates it. Central keeps MD5 and SHA-1 because it requires them. The
// aggregation's 'publishToGitHubPackages' runs this for every published module; the name
// differs so that running 'publishToGitHubPackages' from the root, unqualified, still
// reaches only the aggregation and never uploads an unpublished module.
val uploadToGitHubPackages = tasks.register<GitHubPackagesUploadTask>("uploadToGitHubPackages") {
  group = "publishing"
  description = "Uploads the staged publications to GitHub Packages with SHA-1 and SHA-256 checksums"
  dependsOn("publishAllPublicationsToSavaCentralStagingRepository")
  stagingDirectory = centralStagingDir
  // A test points this at a mock registry; a release always targets this product's own.
  baseUrl = providers.gradleProperty("savaGithubPackagesPublishUrl")
    .orElse("https://maven.pkg.github.com/${orgPathSegment}/${productName}")
  username = providers.gradleProperty("savaGithubPackagesPublishUsername")
  password = providers.gradleProperty("savaGithubPackagesPublishPassword")
}

// 'publish' uploaded to GitHub Packages while it was a Maven repository here, and still does.
tasks.named("publish") { dependsOn(uploadToGitHubPackages) }

// Deprecated: the names of the tasks the 'savaGithubPackagesPublish' Maven repository gave
// this project before sava-build 21.6.0. Aggregation scripts written for those versions
// register their own 'publishToGitHubPackages' on top of them, and keep working unchanged.
for (legacyName in listOf(
  "publishMavenJavaPublicationToSavaGithubPackagesPublishRepository",
  "publishAllPublicationsToSavaGithubPackagesPublishRepository",
)) {
  tasks.register(legacyName) {
    group = "publishing"
    description = "Deprecated alias for uploadToGitHubPackages"
    dependsOn(uploadToGitHubPackages)
  }
}

// --- Central Portal staging, consumed by the
// 'software.sava.build.feature.publish-maven-central' aggregation. ---

// The staging repository accumulates whatever was published before, so wipe it first to
// keep the deployment bundle limited to the publications of this build invocation.
val cleanSavaCentralStaging = tasks.register<Delete>("cleanSavaCentralStaging") {
  delete(centralStagingDir)
}
tasks.withType<PublishToMavenRepository>().configureEach {
  if (name.endsWith("ToSavaCentralStagingRepository")) {
    dependsOn(cleanSavaCentralStaging)
  }
}

val savaCentralStagingElements = configurations.consumable("savaCentralStagingElements") {
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named("sava-central-staging"))
  }
}
artifacts {
  add(savaCentralStagingElements.name, centralStagingDir) {
    builtBy("publishAllPublicationsToSavaCentralStagingRepository")
  }
}
