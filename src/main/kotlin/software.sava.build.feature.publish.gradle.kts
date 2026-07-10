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
      name = "savaGithubPackagesPublish"
      url = uri("https://maven.pkg.github.com/${orgPathSegment}/${productName}")
      // https://docs.gradle.org/current/samples/sample_publishing_credentials.html
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaCentralStaging"
      url = uri(centralStagingDir.get().asFile)
    }
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
