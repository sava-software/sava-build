plugins {
  id("java-base")
  id("maven-publish")
  id("signing")
  id("com.gradleup.nmcp")
}

val orgName = orgName("Sava Software")
val orgPathSegment = orgPathSegment("sava-software")

@Suppress("UnstableApiUsage")
val productName = isolated.rootProject.name
val developerName = developerName("Jim")
val developerId = developerId("jpe7s")
val developerEmail = developerEmail("jpe7s.salt188@passfwd.com")

@Suppress("UnstableApiUsage")
val licenseName =
  providers.fileContents(isolated.rootProject.projectDirectory.file("LICENSE")).asText.map { it.lines().first().trim() }
val vcs = "https://github.com/${orgPathSegment}/${productName}"

val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()

// publish module with sources and javadoc
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
      pom.packaging = "pom" // ensure this is pom, not toml, to conform to Maven BOM standards
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
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/${orgPathSegment}/${productName}")
      // https://docs.gradle.org/current/samples/sample_publishing_credentials.html
      credentials(PasswordCredentials::class)
    }
  }
}
