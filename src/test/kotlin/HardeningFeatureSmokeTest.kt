import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Configuration-phase smoke test for 'software.sava.build.feature.hardening': a plain
 * java consumer applies the plugin resolved from the local test repo,
 * registers a mutation suite and a fuzz target, and verifies the generated task wiring
 * without resolving the PIT/Jazzer dependencies.
 */
class HardeningFeatureSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private fun writeFixture(
    hardeningSettings: List<String>,
    expectedMutationRelease: Int,
    expectedFuzzRelease: Int
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

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
          mutation.register("tuned") {
            targetClasses = listOf("com.example.Tuned")
            // own exclusions must survive ahead of the appended fuzz harnesses
            excludedClasses = listOf("com.example.*Test*")
            targetTests = "com.example.*Test*"
            timeoutFactor = 2.5
            timeoutConst = 10000L
          }
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            maxLen = 256
          }
          fuzz.register("plain") {
            targetClass = "com.example.PlainFuzz"
          }
          // no targetClass: must contribute nothing to the suites' exclusions
          // instead of turning the whole --excludedClasses argument absent
          fuzz.register("unset") {
          }
        }

        tasks.register("verifyHardeningConventions") {
          val mutationRelease = tasks.named<JavaCompile>("compileForPitest").flatMap { it.options.release }
          val fuzzRelease = tasks.named<JavaCompile>("compileForFuzz").flatMap { it.options.release }
          val pitestArgs = tasks.named<JavaExec>("pitestEncoding")
            .map { task -> task.argumentProviders.flatMap { it.asArguments() } }
          val tunedArgs = tasks.named<JavaExec>("pitestTuned")
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
            // PIT's own timeout defaults ride along unless the suite tunes them
            check(pit.any { it == "--timeoutFactor=1.25" }) { "timeoutFactor default: " + pit }
            check(pit.any { it == "--timeoutConst=4000" }) { "timeoutConst default: " + pit }
            // every registered fuzz harness is auto-excluded from every suite — exact
            // class plus its nested classes — so a package-wildcard suite never
            // mutates the harness that exercises it
            check(pit.any { it == "--excludedClasses=com.example.CodecFuzz,com.example.CodecFuzz$*,com.example.PlainFuzz,com.example.PlainFuzz$*" }) { "harness auto-exclusion: " + pit }
            val tuned = tunedArgs.get()
            check(tuned.any { it == "--timeoutFactor=2.5" }) { "timeoutFactor override: " + tuned }
            check(tuned.any { it == "--timeoutConst=10000" }) { "timeoutConst override: " + tuned }
            // a suite's own exclusions come first, harnesses appended after
            check(tuned.any { it == "--excludedClasses=com.example.*Test*,com.example.CodecFuzz,com.example.CodecFuzz$*,com.example.PlainFuzz,com.example.PlainFuzz$*" }) { "own + harness exclusions: " + tuned }
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
    assertTrue(result.output.contains("pitestEncodingVerify"), "pitestEncodingVerify task missing:\n" + result.output)
    assertTrue(result.output.contains("qualityGate"), "qualityGate task missing:\n" + result.output)
    assertTrue(result.output.contains("fuzzCodec"), "fuzzCodec task missing:\n" + result.output)
    assertFalse(result.output.contains("FAILED"), result.output)

    // the ratchet without a PIT report fails fast with a pointer to the run task
    val verifyWithoutReport = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("pitestEncodingVerify", "--stacktrace")
      .buildAndFail()
    assertTrue(
      verifyWithoutReport.output.contains("no PIT report"),
      "expected missing-report failure:\n" + verifyWithoutReport.output
    )

    val override = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("help", "-PmaxFuzzTime=15", "--stacktrace")
      .build()
    assertFalse(override.output.contains("FAILED"), override.output)
  }

  @Test
  fun `a fuzz workflow missing a registered target fails the sync check`() {
    // A corpus that replays in check while its target never joins the weekly soak
    // reads as covered while exploring nothing new. No workflow at all stays quiet
    // (adopting the soak is the repo's call); an existing workflow must name every
    // registered target's task.
    writeFixture(emptyList(), expectedMutationRelease = 25, expectedFuzzRelease = 25)

    val without = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzWorkflowInSync", "--stacktrace").build()
    assertFalse(without.output.contains("FAILED"), without.output)

    // 'fuzzPlainExtra' must not satisfy 'fuzzPlain': word-boundary matching, so a
    // target whose name prefixes another cannot pass on the longer name's mention
    val workflow = File(fixtureDir, ".github/workflows/fuzz.yml")
    workflow.parentFile.mkdirs()
    workflow.writeText("run: ./gradlew --continue fuzzCodec fuzzUnset fuzzPlainExtra\n")

    val missing = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzWorkflowInSync", "--stacktrace").buildAndFail()
    assertTrue(
      missing.output.contains("names 1 registered") && missing.output.contains(":fuzzPlain"),
      "missing target not named with a paste-ready task path:\n" + missing.output
    )

    // the documented escape hatch: a commented mention carrying the reason keeps a
    // target out of the soak deliberately, and legibly
    workflow.appendText("# fuzzPlain stays out of the soak: replay-only regression corpus\n")
    val complete = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzWorkflowInSync", "--stacktrace").build()
    assertFalse(complete.output.contains("FAILED"), complete.output)
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
