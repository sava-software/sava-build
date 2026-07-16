import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Configuration-phase smoke test for 'software.sava.build.feature.hardening': a plain
 * java consumer applies the plugin via 'pluginManagement { includeBuild(...) }',
 * registers a mutation suite and a fuzz target, and verifies the generated task wiring
 * without resolving the PIT/Jazzer dependencies.
 */
class HardeningFeatureSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture(
    hardeningSettings: List<String>,
    expectedMutationRelease: Int,
    expectedFuzzRelease: Int
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "hardening-smoke-test"
      """.trimIndent() + "\n"
    )
    val settings = hardeningSettings.joinToString("") { "\n          $it" }
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }

        hardening {$settings
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec", "com.example.Checksum")
            targetTests = "com.example.*Test*"
          }
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            maxLen = 256
          }
          fuzz.register("plain") {
            targetClass = "com.example.PlainFuzz"
          }
        }

        tasks.register("verifyHardeningConventions") {
          val mutationRelease = tasks.named<JavaCompile>("compileForPitest").flatMap { it.options.release }
          val fuzzRelease = tasks.named<JavaCompile>("compileForFuzz").flatMap { it.options.release }
          val pitestArgs = tasks.named<JavaExec>("pitestEncoding")
            .map { task -> task.argumentProviders.flatMap { it.asArguments() } }
          val fuzzArgs = tasks.named<JavaExec>("fuzzCodec")
            .map { task -> task.argumentProviders.flatMap { it.asArguments() } }
          val fuzzJvmArgs = tasks.named<JavaExec>("fuzzCodec").map { it.jvmArgs ?: listOf() }
          val plainFuzzArgs = tasks.named<JavaExec>("fuzzPlain")
            .map { task -> task.argumentProviders.flatMap { it.asArguments() } }
          doLast {
            check(mutationRelease.get() == $expectedMutationRelease) { "unexpected mutation release: " + mutationRelease.get() }
            check(fuzzRelease.get() == $expectedFuzzRelease) { "unexpected fuzz release: " + fuzzRelease.get() }
            val pit = pitestArgs.get()
            check(pit.any { it == "--targetClasses=com.example.Codec,com.example.Checksum" }) { "targetClasses: " + pit }
            check(pit.any { it == "--targetTests=com.example.*Test*" }) { "targetTests: " + pit }
            check(pit.any { it == "--mutators=STRONGER" }) { "mutators: " + pit }
            check(pit.any { it == "--threads=4" }) { "threads: " + pit }
            check(pit.any { it.startsWith("--classPath=") && it.contains("mutation-classes") }) { "classPath: " + pit }
            val fuzz = fuzzArgs.get()
            check(fuzz.any { it == "--target_class=com.example.CodecFuzz" }) { "target_class: " + fuzz }
            check(fuzz.any { it == "-max_total_time=60" }) { "max_total_time: " + fuzz }
            check(fuzz.any { it == "-max_len=256" }) { "max_len: " + fuzz }
            // the corpus directory must stay the last (positional) argument
            check(fuzz.last().endsWith("codec-corpus")) { "corpus: " + fuzz }
            check(fuzzJvmArgs.get().contains("-XX:+EnableDynamicAgentLoading")) { "jvmArgs: " + fuzzJvmArgs.get() }
            val plain = plainFuzzArgs.get()
            check(plain.none { it.startsWith("-max_len=") }) { "unexpected max_len: " + plain }
            check(plain.last().endsWith("plain-corpus")) { "corpus: " + plain }
          }
        }
      """.trimIndent() + "\n"
    )
  }

  @Test
  fun `hardening feature registers pitest and fuzz tasks with default releases`() {
    writeFixture(emptyList(), expectedMutationRelease = 25, expectedFuzzRelease = 25)

    val result = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("verifyHardeningConventions", "tasks", "--group=verification", "--stacktrace")
      .build()

    assertTrue(result.output.contains("pitestEncoding"), "pitestEncoding task missing:\n" + result.output)
    assertTrue(result.output.contains("fuzzCodec"), "fuzzCodec task missing:\n" + result.output)
    assertFalse(result.output.contains("FAILED"), result.output)

    val override = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("help", "-PmaxFuzzTime=15", "--stacktrace")
      .build()
    assertFalse(override.output.contains("FAILED"), override.output)
  }

  @Test
  fun `per-tool bytecode releases diverge independently`() {
    // contrived divergent values, not a recommended configuration: distinct releases
    // prove 'mutationBytecodeRelease' drives compileForPitest while 'bytecodeRelease'
    // drives compileForFuzz, which identical values could not distinguish from a
    // cross-wiring bug
    writeFixture(
      listOf("bytecodeRelease = 21", "mutationBytecodeRelease = 25"),
      expectedMutationRelease = 25,
      expectedFuzzRelease = 21
    )

    val result = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("verifyHardeningConventions", "--stacktrace")
      .build()
    assertFalse(result.output.contains("FAILED"), result.output)
  }
}
