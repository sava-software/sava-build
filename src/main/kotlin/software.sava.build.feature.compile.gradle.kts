plugins {
  id("java")
}

val languageVersion = JavaLanguageVersion.of(javaVersion("25"))!!

java {
  toolchain.languageVersion = languageVersion
  @Suppress("UnstableApiUsage")
  toolchain.vendor = JvmVendorSpec.JETBRAINS
}
