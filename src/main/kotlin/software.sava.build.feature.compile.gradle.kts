plugins {
  id("java")
}

val jlv = JavaLanguageVersion.of(javaVersion("24"))

java {
  toolchain.languageVersion = jlv
}
