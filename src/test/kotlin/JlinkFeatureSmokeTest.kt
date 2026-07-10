import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * End-to-end test of 'software.sava.build.feature.jlink': builds a real runtime image
 * with the JDK's jlink from a minimal modular application and runs it. Uses a standalone
 * fixture (no javaModules) so no dependency resolution against remote repositories occurs.
 * The extension properties mirror the consumer repositories' 'jlinkApplication' blocks.
 */
class JlinkFeatureSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "jlink-smoke"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          id("software.sava.build.feature.jlink")
        }

        jlinkApplication {
          applicationName = "jlink-smoke"
          mainClass = "software.sava.test.jlink.Main"
          mainModule = "software.sava.test.jlink"
          noHeaderFiles = true
          noManPages = true
          generateCdsArchive = true
          stripDebug = false
          compress = "zip-6"
          vm = "server"
        }
      """.trimIndent() + "\n"
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
  }

  private fun runBuild(vararg arguments: String): BuildResult = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments)
    .build()

  @Test
  fun `image task links a runnable image with the expected layout`() {
    writeFixture()
    val result = runBuild("--configuration-cache", "imageRun", "imageModules")

    val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
    val imageDir = File(fixtureDir, "build/images/jlink-smoke")
    // Consumer Dockerfiles and scripts depend on this layout.
    assertTrue(File(imageDir, "bin/java$executableSuffix").isFile, "missing bin/java in $imageDir")
    assertTrue(File(imageDir, "bin/jlink-smoke").isFile, "missing generated launcher in $imageDir")
    assertTrue(result.output.contains("jlink smoke ok"), result.output)
    assertTrue(result.output.contains("software.sava.test.jlink"), result.output)
  }
}
