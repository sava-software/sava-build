plugins {
  `kotlin-dsl`
  id("maven-publish")
  id("signing")
  id("com.gradleup.nmcp") version "1.1.0"
  id("com.gradleup.nmcp.aggregation") version "1.1.0"
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

dependencies {
  // https://github.com/autonomousapps/dependency-analysis-gradle-plugin
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:2.19.0")
  // https://github.com/iherasymenko/jlink-gradle-plugin
  implementation("com.github.iherasymenko.jlink:jlink-plugin:0.7")
  // https://docs.gradle.com/develocity/gradle-plugin/current/
  implementation("com.gradle:develocity-gradle-plugin:4.1.1")
  // https://github.com/GradleUp/nmcp
  implementation("com.gradleup.nmcp:nmcp:1.1.0")
  // https://github.com/gradle/foojay-toolchains
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  // https://github.com/gradlex-org/java-module-dependencies
  implementation("org.gradlex:java-module-dependencies:1.9.2")
  // https://github.com/gradlex-org/java-module-testing
  implementation("org.gradlex:java-module-testing:1.7")
  // https://github.com/gradlex-org/jvm-dependency-conflict-resolution
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.4")
  // https://github.com/gradlex-org/extra-java-module-info
  implementation("org.gradlex:extra-java-module-info:1.13.1")
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

tasks.named { it.startsWith("publishSoftware.sava.build.") }.configureEach {
  // Do not publish marker for plugins that are not the main 'software.sava.build' plugin
  enabled = false
}
tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    "publishPluginMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.buildPluginMarkerMavenPublicationToSavaGithubPackagesRepository"
  )
}

nmcpAggregation {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
    password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
    publishingType = "USER_MANAGED"
  }
}
dependencies {
  nmcpAggregation(project(path))
}

publishing {
  repositories {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      // https://docs.gradle.org/current/samples/sample_publishing_credentials.html
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
