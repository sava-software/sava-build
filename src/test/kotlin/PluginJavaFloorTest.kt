import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The plugin's Java 21 floor, on the jar this build publishes for its own tests and on the compile
 * tasks that built it. Consumers load the plugin in their own Gradle daemon, so every class must be
 * Java 21 bytecode, compiled against the Java 21 API, and the module metadata must say so, whatever
 * JDK built it. Three settings hold the floor since the daemon pin went
 * (gradle/gradle-daemon-jvm.properties, which made a 21 javac enforce it by construction): javac's
 * release, the Kotlin JVM target and kotlinc's '-Xjdk-release'. The bytecode major shows the first
 * two in the jar; the API floors do not show in bytecode at all (a call into a newer JDK's API,
 * Stream.gather or Process.waitFor(Duration), compiles into Java 21 bytecode that fails at run time
 * on a Java 21 daemon), so they are read from the compile tasks' options, which the build passes to
 * this test as inputs: whatever adds, moves or drops the flag changes what the tasks hold, where a
 * regex over the script's text saw only one spelling of it.
 */
class PluginJavaFloorTest {

  private val artifactDir =
    File(savaBuildTestProperty("savaBuild.testRepo"), "software/sava/sava-build/$savaBuildTestRepoVersion")

  /** Class file major version 65 is Java 21. */
  private val java21 = 65

  @Test
  fun `every class in the published jar is Java 21 bytecode`() {
    val jar = File(artifactDir, "sava-build-$savaBuildTestRepoVersion.jar")
    assertTrue(jar.isFile, "missing $jar")
    val majors = ZipFile(jar).use { zip ->
      zip.entries().asSequence().filter { it.name.endsWith(".class") }.associate { entry ->
        entry.name to zip.getInputStream(entry).use { input ->
          DataInputStream(input).run {
            readInt() // magic
            readUnsignedShort() // minor
            readUnsignedShort()
          }
        }
      }
    }
    assertTrue(majors.size > 100, "expected the plugin's classes in $jar, found ${majors.size}")
    assertEquals(emptyMap<String, Int>(), majors.filterValues { it != java21 }, "classes not compiled for Java 21 (major $java21)")
  }

  @Test
  fun `javac compiles against the Java 21 API`() {
    assertEquals("21", savaBuildTestProperty("savaBuild.javaRelease"), "compileJava's options.release")
  }

  @Test
  fun `the Kotlin compiler is held to the Java 21 bytecode and API`() {
    assertEquals("21", savaBuildTestProperty("savaBuild.kotlinJvmTarget"), "compileKotlin's jvmTarget")
    // Exactly one -Xjdk-release, and it is 21: a second one later in the list would override it.
    val releases = savaBuildTestProperty("savaBuild.kotlinJdkReleaseArgs").split('\t').filter { it.isNotEmpty() }
    assertEquals(listOf("-Xjdk-release=21"), releases, "compileKotlin's -Xjdk-release flags")
  }

  @Test
  fun `the module metadata declares Java 21`() {
    val module = File(artifactDir, "sava-build-$savaBuildTestRepoVersion.module")
    assertTrue(module.isFile, "missing $module")
    val declared = Regex("\"org\\.gradle\\.jvm\\.version\"\\s*:\\s*(\\d+)").findAll(module.readText())
      .map { it.groupValues[1].toInt() }.toSet()
    assertEquals(setOf(21), declared, "org.gradle.jvm.version in $module")
  }
}
