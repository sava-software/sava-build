plugins {
  id("java")
}

val jlv = JavaLanguageVersion.of(javaVersion("25"))

java {
  toolchain.languageVersion = jlv
}
