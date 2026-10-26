import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end test of 'software.sava.build.feature.jlink': builds a real runtime image
 * with the JDK's jlink from a minimal modular application and runs it. Uses a standalone
 * fixture (no javaModules) so no dependency resolution against remote repositories occurs.
 * The extension properties mirror the consumer repositories' 'jlinkApplication' blocks.
 */
class JlinkFeatureFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  /**
   * The fixture, which generates a CDS archive and compresses as the consumers' jlinkApplication
   * blocks do. With [bindServices] null the build leaves the property alone, as a consumer that
   * has not decided does; set, it writes the property. [more] are further jlinkApplication lines,
   * [script] is appended to the build script.
   */
  private fun writeFixture(bindServices: Boolean? = null, more: List<String> = emptyList(), script: String = "") {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "jlink-smoke"
        include("extra")
      """.trimIndent() + "\n"
    )
    val chosen = (if (bindServices == null) emptyList() else listOf("bindServices = $bindServices")) + more
    val decided = chosen.joinToString("") { "$it\n          " }
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          id("software.sava.build.feature.jlink")
        }

        // On the module path, required by nothing, providing a service java.base uses: what
        // service binding links and the default leaves out.
        dependencies {
          runtimeOnly(project(":extra"))
        }

        jlinkApplication {
          applicationName = "jlink-smoke"
          mainClass = "software.sava.test.jlink.Main"
          mainModule = "software.sava.test.jlink"
          ${decided}noHeaderFiles = true
          noManPages = true
          generateCdsArchive = true
          compress = "zip-6"
          stripDebug = false
          vm = "server"
        }
      """.trimIndent() + "\n" + script
    )
    val sourceDir = File(fixtureDir, "src/main/java/software/sava/test/jlink")
    sourceDir.mkdirs()
    File(fixtureDir, "src/main/java/module-info.java")
      .writeText("module software.sava.test.jlink {}\n")
    File(sourceDir, "Main.java").writeText(
      """
        package software.sava.test.jlink;

        public final class Main {
          public static void main(String[] args) {
            System.out.println("jlink smoke ok");
          }
        }
      """.trimIndent() + "\n"
    )
    val extraDir = File(fixtureDir, "extra/src/main/java/software/sava/test/extra")
    extraDir.mkdirs()
    File(fixtureDir, "extra/build.gradle.kts").writeText("plugins { id(\"java\") }\n")
    File(fixtureDir, "extra/src/main/java/module-info.java").writeText(
      """
        module software.sava.test.extra {
          provides java.util.spi.ToolProvider with software.sava.test.extra.Extra;
        }
      """.trimIndent() + "\n"
    )
    File(extraDir, "Extra.java").writeText(
      """
        package software.sava.test.extra;

        import java.io.PrintWriter;
        import java.util.spi.ToolProvider;

        public final class Extra implements ToolProvider {
          @Override
          public String name() {
            return "extra";
          }

          @Override
          public int run(PrintWriter out, PrintWriter err, String... args) {
            return 0;
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runBuild(vararg arguments: String): BuildResult = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--no-watch-fs", "--stacktrace")
    .build()

  private val imageDir get() = File(fixtureDir, "build/images/jlink-smoke")

  /** The MODULES line of the image's release file, as a set. */
  private fun linkedModules(): Set<String> = File(imageDir, "release").readLines()
    .first { it.startsWith("MODULES=") }
    .removePrefix("MODULES=").trim('"').split(' ').filter { it.isNotEmpty() }.toSet()

  private fun assertImageLayout(result: BuildResult) {
    val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    // Consumer Dockerfiles and scripts depend on this layout.
    assertTrue(File(imageDir, "bin/java$executableSuffix").isFile, "missing bin/java in $imageDir")
    assertTrue(File(imageDir, "bin/jlink-smoke").isFile, "missing generated launcher in $imageDir")
    assertTrue(result.output.contains("jlink smoke ok"), result.output)
    assertTrue(result.output.contains("software.sava.test.jlink"), result.output)
  }

  private val leftOutNotice =
    "jlink left 1 module-path module(s) out of jlink-smoke: software.sava.test.extra (service provider). " +
      "No linked module requires them at link time"

  private val bindingNotice = "Service binding (bindServices = true) would have linked "

  @Test
  fun `image task links a runnable image with the expected layout`() {
    writeFixture()
    val (result, reused) = assertConfigurationCacheRoundTrip {
      runBuild("imageRun", "imageModules")
    }
    assertImageLayout(result)
    assertImageLayout(reused)

    // Without service binding the image holds the application's module graph and nothing else;
    // java.logging joins it because the fixture generates a CDS archive, whose class list names
    // java.util.logging classes (without the module jlink prints a warning per class), and on an
    // older toolchain, which is this test's JVM since the fixture sets none, the providers java.base
    // cannot do without: SunEC before Java 22 and the java.util.random algorithms on 17 to 22.
    val expected = mutableSetOf("java.base", "java.logging", "software.sava.test.jlink")
    val feature = Runtime.version().feature()
    if (feature < 22) expected += "jdk.crypto.ec"
    if (feature in 17..22) expected += "jdk.random"
    assertEquals(expected, linkedModules())
    assertFalse(result.output.contains("[warning][cds]"), result.output)
    // The provider on the module path is reported, not silently dropped, and so is what binding
    // would have linked: the provider itself and the JDK's own providers.
    assertTrue(result.output.contains(leftOutNotice), result.output)
    val binding = result.output.lines().single { it.startsWith(bindingNotice) }
    assertTrue(binding.contains(" more module(s) into jlink-smoke: software.sava.test.extra (module path), "), binding)
    assertTrue(binding.contains("jdk.localedata"), binding)
    assertTrue(binding.contains(", and ") && binding.contains(" module(s) they require. "), binding)
    // The way out named depends on whether this JVM, the toolchain, could link jdk.jlink at all.
    val ending = if (File(System.getProperty("java.home"), "jmods").isDirectory) {
      "setting jlinkApplication.bindServices (false once they are named, or true) ends this notice."
    } else {
      "(not jdk.jlink: this toolchain has no jmods, so jlink cannot link it, and bindServices = true would); " +
        "setting jlinkApplication.bindServices = false once they are named ends this notice."
    }
    assertTrue(binding.endsWith(ending), binding)
  }

  @Test
  fun `bindServices links the providers and reports nothing left out`() {
    // Binding pulls jdk.jlink in through java.base's ToolProvider service, which jlink can link
    // only from a JDK that ships jmods (Temurin 24 and later do not).
    assumeTrue(File(System.getProperty("java.home"), "jmods").isDirectory, "the daemon JDK has no jmods")
    writeFixture(bindServices = true)
    val result = runBuild("imageRun", "imageModules")
    assertImageLayout(result)
    val linked = linkedModules()
    assertTrue(linked.containsAll(setOf("software.sava.test.extra", "jdk.jlink", "jdk.localedata")), linked.toString())
    assertFalse(result.output.contains("jlink left "), result.output)
    assertFalse(result.output.contains(bindingNotice), result.output)
    // Silence because the report ran and found nothing, not because it failed to run: a report JVM
    // that did not start or exited non-zero is a warning on a build that still succeeds.
    assertFalse(result.output.contains("Could not report the modules left out"), result.output)
  }

  @Test
  fun `an explicit bindServices = false keeps the left-out notice and ends the binding one`() {
    writeFixture(bindServices = false, more = listOf("includeLocales = listOf(\"en\")"))
    val result = runBuild("imageRun", "imageModules")
    assertImageLayout(result)
    val linked = linkedModules()
    assertFalse(linked.contains("software.sava.test.extra"), linked.toString())
    // --include-locales needs jdk.localedata, which binding used to bring; the task links it itself.
    assertTrue(linked.contains("jdk.localedata"), linked.toString())
    assertTrue(result.output.contains(leftOutNotice), result.output)
    assertFalse(result.output.contains(bindingNotice), result.output)
  }

  @Test
  fun `limitModules narrows the binding notice to what jlink could observe`() {
    // jlink below 24 resolves --limit-modules over the module path alone and does not find java.base
    // unless the JDK's jmods are on it; the test JVM is the toolchain, since the fixture sets none.
    assumeTrue(Runtime.version().feature() >= 24, "jlink below 24 cannot limit modules without the JDK's jmods on the module path")
    writeFixture(more = listOf("limitModules = listOf(\"java.base\", \"software.sava.test.extra\")"))
    val result = runBuild("imageRun", "imageModules")
    assertImageLayout(result)
    assertEquals(setOf("java.base", "java.logging", "software.sava.test.jlink"), linkedModules())
    assertTrue(result.output.contains(leftOutNotice), result.output)
    // Within the limit, binding reaches the provider on the module path and none of the JDK's,
    // jdk.jlink among them, so the way out named does not depend on the toolchain having jmods.
    val binding = result.output.lines().single { it.startsWith(bindingNotice) }
    assertEquals(
      "${bindingNotice}1 more module(s) into jlink-smoke: software.sava.test.extra (module path). " +
        "Name the ones the application needs in jlinkApplication.addModules; setting " +
        "jlinkApplication.bindServices (false once they are named, or true) ends this notice.",
      binding
    )
  }

  @Test
  fun `the image follows the toolchain files a link reads as they stand`() {
    // release names the JDK's version; cacerts and tzdb.dat are what a link from a JDK without jmods
    // copies from the installation itself (jlink's upgrade_files_java.base.conf), so an OS trust-store
    // update under such a JDK changes the image without touching release. The toolchain is this JVM.
    writeFixture(
      script = """
        tasks.register("imageInputs") {
          // Realised at configuration time, a local so the action captures plain strings, not the script.
          val imageInputs = tasks.named("image").get().inputs.files.files.map { it.absolutePath }
          doLast { imageInputs.forEach { println("image input: " + it) } }
        }
      """.trimIndent() + "\n"
    )
    val result = runBuild("imageInputs")
    val listed = result.output.lines().filter { it.startsWith("image input: ") }
      .map { File(it.removePrefix("image input: ")).canonicalPath }.toSet()
    val home = File(System.getProperty("java.home"))
    for (name in listOf("release", "lib/security/cacerts", "lib/tzdb.dat")) {
      assertTrue(File(home, name).canonicalPath in listed, "$name of $home not among the image inputs:\n${listed.joinToString("\n")}")
    }
  }
}
