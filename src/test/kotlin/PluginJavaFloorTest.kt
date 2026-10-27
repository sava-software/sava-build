import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * The plugin's Java 21 floor, on the jar this build publishes for its own tests. Consumers load
 * the plugin in their own Gradle daemon, so every class must be Java 21 bytecode, compiled against
 * the Java 21 API, and the module metadata must say so, whatever JDK built it. Only three lines of
 * build.gradle.kts hold the floor since the daemon pin went (gradle/gradle-daemon-jvm.properties,
 * which made a 21 javac enforce it by construction): javac's '--release 21', the Kotlin JVM target
 * and kotlinc's '-Xjdk-release=21'. The first two show in the jar; the kotlinc flag only in the
 * script, so its test is a tripwire on the line itself: deleting it or commenting it out with '//'
 * fails the test, where the rest of the check would stay green and publish a Java 25 plugin under
 * a 21.x version. A line kept but made ineffective (inside a block comment, under a block that no
 * longer applies to the main compilation, ahead of a later '-Xjdk-release' that overrides it)
 * passes: the flag's effect has no witness short of compiling a call into a newer API, and such an
 * edit is deliberate, not a slip.
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
  fun `the Kotlin compiler is held to the Java 21 API`() {
    // The JVM target caps the bytecode and '--release 21' what javac sees; only this flag keeps
    // kotlinc from compiling a call into a newer JDK's API (Stream.gather, Process.waitFor(Duration))
    // into Java 21 bytecode that fails at run time on a Java 21 daemon.
    val build = savaBuildRootFile("build.gradle.kts").readText()
    // On a line that is not a '//' comment, wherever on it: a one-line block spells it too.
    val live = Regex("""^(?!\s*//).*\bfreeCompilerArgs\.add\("-Xjdk-release=21"\)""", RegexOption.MULTILINE)
    assertTrue(live.containsMatchIn(build), "build.gradle.kts no longer passes -Xjdk-release=21 to kotlinc")
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
