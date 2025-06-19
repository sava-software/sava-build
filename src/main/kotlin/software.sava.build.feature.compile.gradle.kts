plugins {
  id("java")
}

@Suppress("UnstableApiUsage")
val javaVersion = providers.fileContents(isolated.rootProject.projectDirectory.file("gradle/java-version.txt")).asText

val jlv = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").orElse(javaVersion).get().trim())

java {
  toolchain.languageVersion = jlv
}
