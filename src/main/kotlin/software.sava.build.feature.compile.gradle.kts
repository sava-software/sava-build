plugins {
  id("java")
}

val languageVersion = JavaLanguageVersion.of(javaVersion("25"))!!

java {
  toolchain.languageVersion = languageVersion
  toolchain.vendor = JvmVendorSpec.ORACLE
}
