import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

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
    // The standalone fixture runs on this build's Java 21 TestKit daemon. Defaults
    // follow that consumer toolchain instead of assuming Sava's Java 25.
    writeFixture(emptyList(), expectedMutationRelease = 21, expectedFuzzRelease = 21)

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
  fun `unsafe suite names and replay target classes are rejected before path mutation`() {
    writeFixture(
      listOf(
        """mutation.register("..") { targetClasses = listOf("com.example.*"); targetTests = "com.example.*Test*" }"""
      ),
      expectedMutationRelease = 21,
      expectedFuzzRelease = 21,
    )
    val unsafeSuite = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("help", "--stacktrace").buildAndFail().output
    assertTrue(unsafeSuite.contains("mutation suite name '..' is unsafe"), unsafeSuite)

    writeFixture(
      listOf(
        """fuzz.register("escape") { targetClass = "../Escape"; seedCorpus = layout.projectDirectory.dir("corpus/escape") }"""
      ),
      expectedMutationRelease = 21,
      expectedFuzzRelease = 21,
    )
    val sentinel = File(fixtureDir, "build/generated-sources/fuzz-replay/java/preserve.txt").apply {
      parentFile.mkdirs()
      writeText("preserve")
    }
    val unsafeClass = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("generateFuzzReplayTests", "--stacktrace").buildAndFail().output
    assertTrue(
      unsafeClass.contains("fuzz target 'escape' targetClass '../Escape' must be a dotted Java package name"),
      unsafeClass)
    assertTrue(sentinel.isFile, "invalid targetClass deleted the generator output before validation")
  }

  @Test
  fun `scheduled fuzz workflows are optional and local fuzz aggregate cannot drift`() {
    writeFixture(emptyList(), expectedMutationRelease = 21, expectedFuzzRelease = 21)

    val without = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzWorkflowInSync", "--stacktrace").build()
    assertFalse(without.output.contains("FAILED"), without.output)
    assertTrue(without.output.contains("scheduled fuzz workflows are optional"), without.output)

    // An incomplete old workflow remains irrelevant: local `fuzzAll` derives its
    // dependencies from the registered targets rather than parsing hand-kept YAML.
    val workflow = File(fixtureDir, ".github/workflows/fuzz.yml")
    workflow.parentFile.mkdirs()
    workflow.writeText("run: ./gradlew --continue fuzzCodec fuzzUnset fuzzPlainExtra\n")

    val optional = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzWorkflowInSync", "--stacktrace").build()
    assertFalse(optional.output.contains("FAILED"), optional.output)

    val tasks = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("tasks", "--all", "--stacktrace").build().output
    assertTrue(tasks.contains("fuzzAll - Runs every registered fuzz target locally"), tasks)
    val plan = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzAll", "--dry-run", "--stacktrace").build().output
    listOf(":fuzzCodec SKIPPED", ":fuzzPlain SKIPPED", ":fuzzUnset SKIPPED").forEach { task ->
      assertTrue(plan.contains(task), "fuzzAll omitted $task:\n$plan")
    }

    val oldReceipt = File(fixtureDir, "build/hardening/local-fuzz.tsv").apply {
      parentFile.mkdirs()
      writeText("old success\n")
    }
    GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzAll", "-PmaxFuzzTime=0", "--stacktrace").buildAndFail()
    assertFalse(oldReceipt.exists(), "invalid fuzzAll budget retained an earlier success receipt")
    assertTrue(
      File(fixtureDir, "build/hardening/local-fuzz.running").isFile,
      "invalid fuzzAll budget did not leave its incomplete-campaign sentinel")

    // A later failed aggregate must invalidate an earlier success receipt before any
    // target starts; otherwise the old TSV can be mistaken for the failed campaign.
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        listOf("fuzzCodec", "fuzzPlain", "fuzzUnset").forEach { taskName ->
          tasks.named(taskName) {
            doFirst { throw GradleException("fixture fuzz failure") }
          }
        }
      """.trimIndent() + "\n"
    )
    oldReceipt.writeText("old success\n")
    GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("fuzzAll", "--stacktrace").buildAndFail()
    assertFalse(oldReceipt.exists(), "failed fuzzAll retained an earlier success receipt")
    assertTrue(
      File(fixtureDir, "build/hardening/local-fuzz.running").isFile,
      "failed fuzzAll did not leave its incomplete-campaign sentinel")

    listOf("0", "00", "abc", "2147483648").forEach { invalid ->
      val refused = GradleRunner.create().withProjectDir(fixtureDir)
        .withArguments("validateFuzzBudget", "-PmaxFuzzTime=$invalid", "--stacktrace")
        .buildAndFail().output
      assertTrue(refused.contains("0 is libFuzzer's run-forever sentinel"), refused)
    }
  }

  @Test
  fun `fuzzAll records a valid zero-target campaign`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-zero-fuzz-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n"
    )

    val result = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments(
        "clean", "fuzzAll", "generateFuzzReplayTests", "-PmaxFuzzTime=1",
        "--configuration-cache", "--stacktrace")
      .build()
    val receipt = File(fixtureDir, "build/hardening/local-fuzz.tsv")

    assertTrue(result.output.contains("fuzzAll: 0 local target(s) completed"), result.output)
    assertTrue(receipt.isFile, "zero-target campaign did not write a receipt")
    val receiptText = receipt.readText()
    assertTrue(receiptText.contains("schema\t2"), receiptText)
    assertTrue(
      Regex("(?m)^pluginSha256\\t[0-9a-f]{64}$").containsMatchIn(receiptText),
      "zero-target receipt did not bind the loaded plugin binary:\n$receiptText",
    )
    assertTrue(receiptText.contains("maxFuzzTimeSeconds\t1"), receiptText)
    assertFalse(receiptText.contains("target\t"), receiptText)
    assertFalse(
      File(fixtureDir, "build/hardening/local-fuzz.running").exists(),
      "successful zero-target campaign retained its running sentinel")
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
