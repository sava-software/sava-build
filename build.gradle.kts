plugins {
  `kotlin-dsl`
  id("maven-publish")
  id("signing")
  id("com.gradleup.nmcp") version "0.1.5"
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

dependencies {
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:2.18.0")
  implementation("com.github.iherasymenko.jlink:jlink-plugin:0.7")
  implementation("com.gradle:develocity-gradle-plugin:4.0.2")
  // https://github.com/GradleUp/nmcp
  // https://central.sonatype.com/artifact/com.gradleup.nmcp/com.gradleup.nmcp.gradle.plugin
  implementation("com.gradleup.nmcp:nmcp:0.1.5")
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  implementation("org.gradlex:java-module-dependencies:1.9.2")
  implementation("org.gradlex:java-module-testing:1.7")
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.4")
}

repositories {
  gradlePluginPortal()
}

java {
  withJavadocJar()
  withSourcesJar()
}

val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
signing {
  sign(publishing.publications)
  useInMemoryPgpKeys(signingKey, signingPassphrase)
}
tasks.withType<Sign>().configureEach { enabled = publishSigningEnabled }

// Publish the Jar and the plugin marker for 'software.sava.build' so that it can be used via plugin ID
// https://docs.gradle.org/current/userguide/plugins.html#sec:plugin_markers
tasks.named { it == "zipPluginMavenPublication" }.withType<Zip>().configureEach {
  from(tasks.named<Zip>("zipSoftware.sava.buildPluginMarkerMavenPublication").map { zipTree(it.archiveFile) })
}
tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    "publishPluginMavenPublicationToGithubPackagesRepository",
    "publishSoftware.sava.buildPluginMarkerMavenPublicationToGithubPackagesRepository"
  )
}

nmcp {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
    password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
    publishingType = "USER_MANAGED"
  }
}

publishing {
  repositories {
    maven {
      name = "githubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials(PasswordCredentials::class)
    }
  }
}

val vcs = "https://github.com/sava-software/sava-build"
publishing.publications.withType<MavenPublication>().configureEach {
  pom {
    name = project.name
    description = "Sava Gradle Conventions"
    url = vcs
    licenses {
      license {
        name = "Apache License"
        url = "$vcs/blob/main/LICENSE"
      }
    }
    developers {
      developer {
        name = "Jim"
        id = "jpe7s"
        email = "jpe7s.salt188@passfwd.com"
        organization = "Sava Software"
        organizationUrl = "https://github.com/sava-software"
      }
    }
    scm {
      connection = "scm:git:git@github.com:sava-software/sava-build.git"
      developerConnection = "scm:git:ssh@github.com:sava-software/sava-build.git"
      url = vcs
    }
  }
}
