import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Behavioural smoke test for the mutation ratchet and the generators: fabricates PIT
 * reports (CSV + XML) so 'pitest<Suite>Verify' and the baseline flags can be exercised
 * without resolving or running PIT itself.
 */
class HardeningRatchetSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture(
    generateTestSupport: Boolean = false,
    testSupportExcludes: List<String> = emptyList()
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "hardening-ratchet-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }

        hardening {
          generateTestSupport = $generateTestSupport
          testSupportExcludes = listOf(${testSupportExcludes.joinToString(", ") { "\"$it\"" }})
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec")
            targetTests = "com.example.*Test*"
          }
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/codec")
          }
          fuzz.register("outside") {
            targetClass = "com.example.OutsideFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/outside")
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun writeReport(csvRows: List<String>, xmlMutations: String) {
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    reportDir.mkdirs()
    reportDir.resolve("mutations.csv").writeText(csvRows.joinToString("\n", postfix = "\n"))
    reportDir.resolve("mutations.xml").writeText(
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n$xmlMutations\n</mutations>\n"
    )
  }

  private fun baselineFile() = File(fixtureDir, "config/pitest/encoding-accepted.csv")

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--stacktrace")

  @Test
  fun `ratchet failure carries descriptions, shift pairing, and the unkilled listing`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,10,MathMutator,SURVIVED # untriaged\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      """
        <mutation status="SURVIVED" detected="false">
          <sourceFile>Codec.java</sourceFile>
          <mutatedClass>com.example.Codec</mutatedClass>
          <mutatedMethod>encode</mutatedMethod>
          <lineNumber>12</lineNumber>
          <mutator>org.pitest.mutationtest.engine.gregor.mutators.MathMutator</mutator>
          <description>Replaced Shift Left with Shift Right</description>
        </mutation>
      """.trimIndent()
    )

    val result = runner("pitestEncodingVerify", "-PlistUnkilled").buildAndFail()
    val output = result.output
    assertTrue(output.contains("1 rows, 1 marked '# untriaged'"), "untriaged count missing:\n$output")
    assertTrue(output.contains("pitest 'encoding' unkilled:"), "-PlistUnkilled listing missing:\n$output")
    assertTrue(output.contains("(shifted from line 10)"), "shift pairing missing:\n$output")
    assertTrue(output.contains("Replaced Shift Left with Shift Right"), "XML description missing:\n$output")
    assertTrue(
      output.contains("every new row is a shifted counterpart"),
      "all-shifted hint missing:\n$output"
    )
    assertTrue(
      output.contains("churn: 1 shifted, 0 newly covered, 0 unexplained"),
      "churn tally missing:\n$output"
    )
  }

  @Test
  fun `a newly covered mutant is triage, not line churn`() {
    // Same line, status changed: a test now reaches a mutant that was previously
    // unreached. That looks like churn in a raw diff but is the opposite — refreshing
    // would launder a fresh survivor into the baseline.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,NO_COVERAGE\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("(newly covered — was NO_COVERAGE at this line; triage, not a refresh)"),
      "newly-covered classification missing:\n$output"
    )
    assertTrue(
      output.contains("churn: 0 shifted, 1 newly covered, 0 unexplained"),
      "churn tally missing:\n$output"
    )
    // the stale SURVIVED row at another line must not be claimed as this row's origin
    assertFalse(output.contains("shifted from line 20"), "misclaimed a shift:\n$output")
    assertFalse(
      output.contains("every new row is a shifted counterpart"),
      "must not advise a refresh:\n$output"
    )
  }

  @Test
  fun `union appends without dropping, idempotently, and update names what it drops`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,decode,5,MathMutator,SURVIVED # untriaged flip insurance\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )

    val union = runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,decode,5,MathMutator,SURVIVED # untriaged flip insurance",
        "com.example.Codec,encode,12,MathMutator,SURVIVED"
      ),
      baselineFile().readLines(),
      "union must keep the absent row, its note, and append the new row in sorted order"
    )

    val idempotent = runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertTrue(idempotent.output.contains("union added nothing new"), idempotent.output)

    val update = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertTrue(update.output.contains("dropped 1 row(s) not unkilled this run"), update.output)
    assertTrue(update.output.contains("com.example.Codec,decode,5,MathMutator,SURVIVED # untriaged flip insurance"), update.output)
    assertTrue(update.output.contains("-PunionMutationBaseline"), update.output)
    assertEquals(
      listOf("com.example.Codec,encode,12,MathMutator,SURVIVED"),
      baselineFile().readLines(),
      "update rewrites from this run only"
    )
  }

  @Test
  fun `mode compare finds load flips, unions them once, and sweeps dead rows`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    // a row no snapshotted mode reports as unkilled — the dead-row sweep must name it
    baselineFile().writeText("com.example.Codec,decode,50,MathMutator,SURVIVED # stale insurance\n")

    // mode 'solo': the mutant is killed
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest"),
      ""
    )
    val solo = runner("pitestModeSnapshot", "-PpitestMode=solo").build()
    assertTrue(solo.output.contains("stashed as 'solo'"), solo.output)
    assertTrue(File(fixtureDir, "build/pitest-modes/solo/encoding.csv").isFile, "solo snapshot missing")
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding").exists(), "reports must be cleared")

    // mode 'gate': the same mutant survives — an unkilled-boundary flip
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )
    runner("pitestModeSnapshot", "-PpitestMode=gate").build()

    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertTrue(compare.output.contains("gate=SURVIVED, solo=KILLED"), compare.output)
    assertTrue(compare.output.contains("-PunionModeFlips"), compare.output)

    val union = runner("pitestModeCompare", "-PunionModeFlips").build()
    assertTrue(union.output.contains("flip insurance written"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,decode,50,MathMutator,SURVIVED # stale insurance",
        "com.example.Codec,encode,12,MathMutator,SURVIVED # flip insurance (gate=SURVIVED, solo=KILLED)"
      ),
      baselineFile().readLines(),
      "union must append the flip row with its evidence note and keep existing rows"
    )

    val insured = runner("pitestModeCompare").build()
    assertTrue(insured.output.contains("already insured in the baseline"), insured.output)
    assertTrue(insured.output.contains("0 uninsured boundary flip(s)"), insured.output)
    assertTrue(
      insured.output.contains("com.example.Codec,decode,50,MathMutator,SURVIVED # stale insurance"),
      "dead-row sweep missing:\n" + insured.output
    )
  }

  @Test
  fun `mode snapshot refuses partial, unlabeled, or history-assisted reports`() {
    writeFixture()
    val unlabeled = runner("pitestModeSnapshot").buildAndFail()
    assertTrue(unlabeled.output.contains("needs -PpitestMode="), unlabeled.output)

    val missing = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail()
    assertTrue(missing.output.contains("no report for 'encoding'"), missing.output)

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val assisted = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail()
    assertTrue(assisted.output.contains("history-assisted"), assisted.output)
    assertTrue(assisted.output.contains("-PnoMutationHistory"), assisted.output)

    val single = runner("pitestModeCompare").buildAndFail()
    assertTrue(single.output.contains("at least two labeled snapshots"), single.output)
  }

  @Test
  fun `replay tests resolve resource corpora on the classpath and guard against rot`() {
    writeFixture()
    File(fixtureDir, "src/test/resources/fuzz/codec").mkdirs()
    File(fixtureDir, "src/test/resources/fuzz/codec/seed1").writeText("seed")
    File(fixtureDir, "corpus/outside").mkdirs()
    File(fixtureDir, "corpus/outside/seed1").writeText("seed")

    val result = runner("generateFuzzReplayTests").build()
    assertFalse(result.output.contains("FAILED"), result.output)

    val generatedRoot = File(fixtureDir, "build/generated-sources/fuzz-replay/java/com/example")
    val resourceBased = generatedRoot.resolve("CodecFuzzSeedReplayTest.java").readText()
    assertTrue(resourceBased.contains("getResource(\"/fuzz/codec\")"), resourceBased)
    assertTrue(resourceBased.contains("Files::isRegularFile"), resourceBased)
    assertTrue(resourceBased.contains("assertFalse(seeds.isEmpty()"), resourceBased)
    assertFalse(resourceBased.contains(fixtureDir.absolutePath), "resource corpus must not bake an absolute path:\n$resourceBased")

    val pathBased = generatedRoot.resolve("OutsideFuzzSeedReplayTest.java").readText()
    assertTrue(pathBased.contains("Files.isDirectory"), pathBased)
    assertTrue(pathBased.contains("assertFalse(seeds.isEmpty()"), pathBased)
  }

  @Test
  fun `test support generates the five helpers only when enabled`() {
    writeFixture(generateTestSupport = true)
    val result = runner("generateHardeningTestSupport", "tasks", "--group=verification").build()
    assertFalse(result.output.contains("FAILED"), result.output)
    assertTrue(result.output.contains("pitestMutatorTrial"), "pitestMutatorTrial task missing:\n" + result.output)
    assertTrue(result.output.contains("pitestConverge"), "pitestConverge task missing:\n" + result.output)

    val supportDir = File(fixtureDir, "build/generated-sources/hardening-support/java/software/sava/hardening/support")
    val expected = listOf("Ports", "LoopbackHttpServer", "ManualScheduledExecutor", "RecordingExecutor", "JulRecorder")
    expected.forEach { name ->
      assertTrue(supportDir.resolve("$name.java").isFile, "$name.java not generated")
    }

    writeFixture(generateTestSupport = true, testSupportExcludes = listOf("JulRecorder"))
    val excluded = runner("generateHardeningTestSupport").build()
    assertFalse(excluded.output.contains("FAILED"), excluded.output)
    assertFalse(supportDir.resolve("JulRecorder.java").isFile, "JulRecorder.java should be excluded")
    (expected - "JulRecorder").forEach { name ->
      assertTrue(supportDir.resolve("$name.java").isFile, "$name.java should survive the exclusion")
    }

    writeFixture(generateTestSupport = false)
    val disabled = runner("generateHardeningTestSupport").build()
    assertFalse(disabled.output.contains("FAILED"), disabled.output)
    expected.forEach { name ->
      assertFalse(supportDir.resolve("$name.java").isFile, "$name.java should be cleared when disabled")
    }
  }
}
