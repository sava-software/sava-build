plugins {
  `kotlin-dsl`
  id("signing")
  id("com.gradle.plugin-publish") version "1.3.1"
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

dependencies {
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:2.18.0")
  implementation("com.github.iherasymenko.jlink:jlink-plugin:0.7")
  implementation("com.gradle:develocity-gradle-plugin:4.0.2")
  implementation("com.gradleup.nmcp:nmcp:0.1.5")
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  implementation("org.gradlex:java-module-dependencies:1.9.2")
  implementation("org.gradlex:java-module-testing:1.7")
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.4")
}

gradlePlugin {
  website = "https://github.com/sava-software/sava-build"
  vcsUrl = "https://github.com/sava-software/sava-build"
  plugins.configureEach {
    displayName = name
    tags = listOf("sava", "conventions", "java")
    // The Gradle Plugin portal requires a separate description for each plugin
    val descriptionFile = layout.projectDirectory.file("src/main/descriptions/${id}.txt")
    val notFoundError = provider { error("File not found ${descriptionFile.asFile.absolutePath}") }
    description = providers.fileContents(descriptionFile).asText.orElse(notFoundError).get().trim()
  }
}

val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
signing { useInMemoryPgpKeys(signingKey, signingPassphrase) }
tasks.withType<Sign>().configureEach { enabled = publishSigningEnabled }

repositories {
  gradlePluginPortal()
}
