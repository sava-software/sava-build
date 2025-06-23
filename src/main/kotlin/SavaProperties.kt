import org.gradle.api.Project
import java.util.Properties

fun Project.javaVersion(defaultValue: String) = savaProperty("javaVersion", defaultValue)
fun Project.solanaBOMVersion() = savaProperty("solanaBOMVersion")
fun Project.productDescription() = savaProperty("productDescription")

private fun Project.savaProperty(name: String, defaultValue: String = ""): String {
    @Suppress("UnstableApiUsage")
    val savaProperties = isolated.rootProject.projectDirectory.file("gradle/sava.properties")
    val properties = Properties().also {
        it.load(providers.fileContents(savaProperties).asText.get().reader())
    }
    return properties[name]?.toString() ?: defaultValue
}
