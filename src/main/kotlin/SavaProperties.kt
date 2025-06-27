import org.gradle.api.Project
import java.util.Properties

fun Project.javaVersion(defaultValue: String) = savaProperty("javaVersion", defaultValue)
fun Project.solanaBOMVersion() = savaProperty("solanaBOMVersion")

fun Project.orgName(defaultValue: String) = savaProperty("orgName", defaultValue)
fun Project.orgPathSegment(defaultValue: String) = savaProperty("orgPathSegment", defaultValue)
fun Project.productDescription() = savaProperty("productDescription")
fun Project.developerName(defaultValue: String) = savaProperty("developerName", defaultValue)
fun Project.developerId(defaultValue: String) = savaProperty("developerId", defaultValue)
fun Project.developerEmail(defaultValue: String) = savaProperty("developerEmail", defaultValue)

private fun Project.savaProperty(name: String, defaultValue: String = ""): String {
    @Suppress("UnstableApiUsage")
    val savaProperties = isolated.rootProject.projectDirectory.file("gradle/sava.properties")
    val properties = Properties().also {
        it.load(providers.fileContents(savaProperties).asText.get().reader())
    }
    return properties[name]?.toString() ?: defaultValue
}
