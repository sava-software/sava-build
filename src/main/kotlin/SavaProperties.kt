import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import java.util.*

fun Project.javaVersion(defaultValue: String) = savaProperty("javaVersion", defaultValue)
fun Project.javaVendor(defaultValue: String) = savaProperty("javaVendor", defaultValue)
fun Project.solanaBOMVersion() = savaProperty("solanaBOMVersion")
fun Settings.solanaBOMVersion() = savaProperty("solanaBOMVersion")

// Shared GitHub Packages credentials resolution for settings scripts.
// gradleProperty also covers the ORG_GRADLE_PROJECT_savaGithubPackages* environment
// variables set by the reusable workflows: Gradle maps them to properties automatically.
fun Settings.githubPackagesCredentials(): Pair<String?, String?> {
  val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull
  val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull
  return gprUser to gprToken
}

// Additional GitHub Packages repositories, from the 'extraGithubPackageRepos' property
// (gradle/sava.properties or -P): comma-separated 'owner/repo' entries or full URLs.
fun Settings.extraGithubPackageRepos(): Set<String> =
  savaProperty("extraGithubPackageRepos")
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { if (it.startsWith("https://")) it else "https://maven.pkg.github.com/$it" }
    .toSet()

fun Project.orgName(defaultValue: String) = savaProperty("orgName", defaultValue)
fun Project.orgPathSegment(defaultValue: String) = savaProperty("orgPathSegment", defaultValue)
fun Project.productDescription() = savaProperty("productDescription")
fun Project.developerName(defaultValue: String) = savaProperty("developerName", defaultValue)
fun Project.developerId(defaultValue: String) = savaProperty("developerId", defaultValue)
fun Project.developerEmail(defaultValue: String) = savaProperty("developerEmail", defaultValue)

private fun Project.savaProperty(name: String, defaultValue: String = ""): String {
  val gradleProperty = providers.gradleProperty(name)
  if (gradleProperty.isPresent) {
    return gradleProperty.get()
  }
  @Suppress("UnstableApiUsage")
  val savaPropertiesFile = isolated.rootProject.projectDirectory.file("gradle/sava.properties")
  val properties = providers.fileContents(savaPropertiesFile).asText
    .map { content -> Properties().apply { load(content.reader()) } }
  return properties.map { it.getProperty(name, defaultValue) }.getOrElse(defaultValue)
}

private fun Settings.savaProperty(name: String, defaultValue: String = ""): String {
  val gradleProperty = providers.gradleProperty(name)
  if (gradleProperty.isPresent) {
    return gradleProperty.get()
  }
  @Suppress("UnstableApiUsage")
  val savaPropertiesFile = layout.settingsDirectory.file("gradle/sava.properties")
  val properties = providers.fileContents(savaPropertiesFile).asText
    .map { content -> Properties().apply { load(content.reader()) } }
  return properties.map { it.getProperty(name, defaultValue) }.getOrElse(defaultValue)
}
