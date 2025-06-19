plugins {
  id("java")
}

@Suppress("UnstableApiUsage")
val javaVersion = providers.fileContents(isolated.rootProject.projectDirectory.file("gradle/java-version.txt")).asText

val jlv = JavaLanguageVersion.of(providers.gradleProperty("javaVersion").orElse(javaVersion).getOrElse("24").trim())

java {
  toolchain.languageVersion = jlv
}
