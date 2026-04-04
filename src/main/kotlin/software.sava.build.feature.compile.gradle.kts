import java.util.*

plugins {
  id("java")
}

val languageVersion = JavaLanguageVersion.of(javaVersion("25"))

@Suppress("UnstableApiUsage")
val vendor = JvmVendorSpec.of(javaVendor("ORACLE").uppercase(Locale.ENGLISH))

java {
  toolchain.languageVersion = languageVersion
  toolchain.vendor = vendor
}
