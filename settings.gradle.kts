rootProject.name = "sava-build"

pluginManagement {
  val nmcpVersionProp = providers.gradleProperty("nmcpVersion").orNull
  if (nmcpVersionProp.isNullOrBlank()) {
    error("Missing required Gradle property 'nmcpVersion'.")
  }
  plugins {
    id("com.gradleup.nmcp") version nmcpVersionProp
    id("com.gradleup.nmcp.aggregation") version nmcpVersionProp
  }
}
