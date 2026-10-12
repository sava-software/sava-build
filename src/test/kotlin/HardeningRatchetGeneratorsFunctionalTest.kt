import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Fuzz corpora, replay and support generators, and PIT source recompilation. */
class HardeningRatchetGeneratorsFunctionalTest : HardeningRatchetFunctionalFixture() {

  @Test
  fun `a committed seed larger than maxLen is refused, never truncated`() {
    // libFuzzer clips oversized inputs on load: a fuzz run explores a truncated copy,
    // and the minimize merge re-hashes the clip — adopting it hash-named and deleting
    // the named original. Both tasks must refuse before Jazzer runs, naming only the
    // offending seed, and the committed corpus must be untouched.
    val scenariosRoot = fixtureDir
    writeFixture(codecMaxLen = 16)
    val corpus = File(fixtureDir, "src/test/resources/fuzz/codec").apply { mkdirs() }
    corpus.resolve("named-probe").writeText("x".repeat(64))
    corpus.resolve("small-seed").writeText("ok")

    val refused = runner("fuzzCodecSeedLenCheck").buildAndFail().output
    assertTrue(refused.contains("1 seed(s) exceed maxLen=16"), refused)
    assertTrue(refused.contains("named-probe (64 bytes, committed)"), refused)
    assertFalse(refused.contains("small-seed"), "the in-cap seed must not be named:\n$refused")
    assertTrue(refused.contains("delete the named original"), refused)

    assertEquals("x".repeat(64), corpus.resolve("named-probe").readText(), "the corpus must be untouched")
    assertEquals(listOf("named-probe", "small-seed"), corpus.listFiles()!!.map { it.name }.sorted())

    // both consumers gate on the check (dry-run: the fixture cannot compile the
    // generated junit replay sources, and no Jazzer download belongs in this test)
    val fuzzPlan = runner("fuzzCodec", "--dry-run").build().output
    assertTrue(fuzzPlan.contains(":fuzzCodecSeedLenCheck SKIPPED"), fuzzPlan)
    val minimizePlan = runner("fuzzCodecMinimize", "--dry-run").build().output
    assertTrue(minimizePlan.contains(":fuzzCodecSeedLenCheck SKIPPED"), minimizePlan)

    // An uncapped target has nothing to refuse — the check is inert, not a new
    // demand. Use a distinct build so the assertion does not depend on Gradle noticing
    // an in-place script rewrite before it looks up the prior configuration-cache entry.
    fixtureDir = scenariosRoot.resolve("uncapped").apply { mkdirs() }
    enableTestKitConfigurationCache(fixtureDir)
    writeFixture()
    val uncapped = runner("fuzzCodecSeedLenCheck").build()
    assertFalse(uncapped.output.contains("FAILED"), uncapped.output)
  }

  @Test
  fun `an oversized local-corpus input is refused only when adoption is requested`() {
    // Local fuzz-run finds live under build/ and are not merge sources by default, so
    // an oversized one is ignorable noise — until '-PadoptLocalCorpus' makes it a
    // source, at which point the same truncation hazard applies and the check must
    // name it with its origin.
    writeFixture(codecMaxLen = 16)
    File(fixtureDir, "src/test/resources/fuzz/codec").apply { mkdirs() }
      .resolve("small-seed").writeText("ok")
    File(fixtureDir, "build/fuzz/codec-corpus").apply { mkdirs() }
      .resolve("local-probe").writeText("y".repeat(48))

    val ignored = runner("fuzzCodecSeedLenCheck").build()
    assertFalse(ignored.output.contains("FAILED"), ignored.output)

    val refused = runner("fuzzCodecSeedLenCheck", "-PadoptLocalCorpus").buildAndFail().output
    assertTrue(refused.contains("1 seed(s) exceed maxLen=16"), refused)
    assertTrue(refused.contains("local-probe (48 bytes, local corpus)"), refused)
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
    // one dynamic test per seed, named after the seed file, so CI output names a failing seed
    assertTrue(resourceBased.contains("@TestFactory"), resourceBased)
    assertTrue(resourceBased.contains("Stream<DynamicTest> replaysSeedCorpus()"), resourceBased)
    assertTrue(resourceBased.contains("DynamicTest.dynamicTest(") &&
        resourceBased.contains("seed.getFileName().toString()"), resourceBased)
    assertFalse(resourceBased.contains("@Test\n"), resourceBased)
    assertFalse(resourceBased.contains(fixtureDir.absolutePath), "resource corpus must not bake an absolute path:\n$resourceBased")

    val pathBased = generatedRoot.resolve("OutsideFuzzSeedReplayTest.java").readText()
    assertTrue(pathBased.contains("Files.isDirectory"), pathBased)
    assertTrue(pathBased.contains("assertFalse(seeds.isEmpty()"), pathBased)
  }

  @Test
  fun `replay generation rejects duplicate harness classes before replacing output`() {
    writeFixture()
    runner("generateFuzzReplayTests").build()
    val replay = File(
      fixtureDir,
      "build/generated-sources/fuzz-replay/java/com/example/CodecFuzzSeedReplayTest.java",
    )
    val preserved = replay.readText()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        hardening.fuzz.register("codecCopy") {
          targetClass = "com.example.CodecFuzz"
          seedCorpus = layout.projectDirectory.dir("corpus/codec-copy")
        }
      """.trimIndent() + "\n"
    )

    val failed = runner("generateFuzzReplayTests", "--no-configuration-cache").buildAndFail().output

    assertTrue(failed.contains("corpus-backed targets share a targetClass"), failed)
    assertTrue(failed.contains("com.example.CodecFuzz (codec, codecCopy)"), failed)
    assertEquals(preserved, replay.readText(), "duplicate replay targets erased the last good output")
  }

  @Test
  fun `a corpus-less target is named until the decision is recorded, and the record itself expires`() {
    // The advice fires: no corpus means a finding has nowhere to land.
    writeFixture(corpusless = true)
    File(fixtureDir, "src/test/resources/fuzz/codec").mkdirs()
    File(fixtureDir, "src/test/resources/fuzz/codec/seed1").writeText("seed")
    File(fixtureDir, "corpus/outside").mkdirs()
    File(fixtureDir, "corpus/outside/seed1").writeText("seed")

    val named = runner("generateFuzzReplayTests").build().output
    assertTrue(named.contains("fuzz target 'bare' declares no seedCorpus"), named)
    assertTrue(named.contains("declineSeedCorpus"), "the advice must name its own escape hatch:\n$named")

    // Recorded with an argument: silent. This is the whole point -- a decision that
    // has been made and written down stops being a question.
    writeFixture(corpusless = true, corpuslessDecline = "trialed 2026-07-25: every prefix is valid and no finding has ever landed")
    val declined = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
    assertFalse(declined.contains("fuzz target 'bare' declares no seedCorpus"), declined)
    assertFalse(declined.contains("is stale"), declined)

    // Recorded with nothing: an argument-free suppression is not one.
    writeFixture(corpusless = true, corpuslessDecline = "   ")
    val blank = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
    assertTrue(blank.contains("fuzz target 'bare' declares no seedCorpus"), blank)
    assertTrue(blank.contains("the recorded seedCorpus decline is stale"), blank)
    assertTrue(blank.contains("carries no reason"), blank)

    // Outlived its subject: the target gained a corpus, so the decline argues for
    // nothing and must not sit on as a fossil.
    writeFixture(
      corpusless = true,
      corpuslessDecline = "trialed 2026-07-25: every prefix is valid",
      corpuslessAlsoDeclaresCorpus = true,
    )
    val contradicted = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
    assertTrue(contradicted.contains("the recorded seedCorpus decline is stale"), contradicted)
    assertTrue(contradicted.contains("contradicts"), contradicted)
  }

  @Test
  fun `test support generates all helpers only when enabled and honors its package`() {
    // Each independent configuration gets its own checkout. Rewriting one build script
    // through several same-task configuration-cache entries couples the final package
    // assertion to configuration invalidation rather than generator behavior.
    // Keep only the transitions whose behavior is under test in the same checkout:
    // enabled -> disabled must clear, and valid -> invalid must preserve.
    val fixtureRoot = fixtureDir
    fun selectFixture(name: String) {
      fixtureDir = fixtureRoot.resolve(name).apply { mkdirs() }
      enableTestKitConfigurationCache(fixtureDir)
    }

    selectFixture("default-and-disabled")
    writeFixture(generateTestSupport = true)
    val result = runner("generateHardeningTestSupport", "tasks", "--group=verification").build()
    assertFalse(result.output.contains("FAILED"), result.output)
    assertTrue(result.output.contains("pitestMutatorTrial"), "pitestMutatorTrial task missing:\n" + result.output)
    assertTrue(result.output.contains("pitestConverge"), "pitestConverge task missing:\n" + result.output)

    val supportDir = File(fixtureDir, "build/generated-sources/hardening-support/java/software/sava/hardening/support")
    val expected = listOf(
      "ConcurrencyHarness", "Ports", "LoopbackHttpServer", "ManualScheduledExecutor",
      "RecordingExecutor", "JulRecorder")
    expected.forEach { name ->
      assertTrue(supportDir.resolve("$name.java").isFile, "$name.java not generated")
    }

    writeFixture(generateTestSupport = false)
    val disabled = runner("generateHardeningTestSupport").build()
    assertFalse(disabled.output.contains("FAILED"), disabled.output)
    expected.forEach { name ->
      assertFalse(supportDir.resolve("$name.java").isFile, "$name.java should be cleared when disabled")
    }

    selectFixture("excluded")
    writeFixture(generateTestSupport = true, testSupportExcludes = listOf("JulRecorder"))
    val excluded = runner("generateHardeningTestSupport").build()
    assertFalse(excluded.output.contains("FAILED"), excluded.output)
    val excludedSupportDir = File(
      fixtureDir, "build/generated-sources/hardening-support/java/software/sava/hardening/support")
    assertFalse(
      excludedSupportDir.resolve("JulRecorder.java").isFile,
      "JulRecorder.java should be excluded",
    )
    (expected - "JulRecorder").forEach { name ->
      assertTrue(
        excludedSupportDir.resolve("$name.java").isFile,
        "$name.java should survive the exclusion",
      )
    }

    selectFixture("custom-and-invalid")
    writeFixture(generateTestSupport = true, testSupportPackage = "com.example.hardening.support")
    val custom = runner("generateHardeningTestSupport").build()
    val customDir = File(
      fixtureDir, "build/generated-sources/hardening-support/java/com/example/hardening/support")
    expected.forEach { name ->
      assertTrue(
        customDir.resolve("$name.java").isFile,
        "$name.java not generated in custom package:\n${custom.output}",
      )
      assertTrue(
        customDir.resolve("$name.java").readText().contains("package com.example.hardening.support;"),
        "$name.java retained the foreign default package")
    }
    val preservedSupport = customDir.resolve("Ports.java").readText()

    writeFixture(generateTestSupport = true, testSupportPackage = "not-a-package")
    val invalid = runner("generateHardeningTestSupport", "--no-configuration-cache")
        .buildAndFail().output
    assertTrue(
      invalid.contains(
        "hardening.testSupportPackage 'not-a-package' must be a dotted Java package name"),
      invalid)
    assertEquals(
      preservedSupport,
      customDir.resolve("Ports.java").readText(),
      "invalid support package erased the last good generated tree",
    )
  }

  @Test
  fun `source generators honor hardening configuration after early task realization`() {
    writeFixture(
      generateTestSupport = true,
      testSupportPackage = "com.example.late.support",
      beforeHardening = """
        tasks.named("generateHardeningTestSupport").get()
        tasks.named("generateFuzzReplayTests").get()
      """.trimIndent(),
    )
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        sourceSets.named("test") {
          resources.setSrcDirs(listOf("custom-test-resources"))
        }
        hardening.fuzz.named("codec") {
          seedCorpus = layout.projectDirectory.dir("custom-test-resources/fuzz/codec")
        }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "custom-test-resources/fuzz/codec").mkdirs()

    val generated = runner(
      "generateHardeningTestSupport", "generateFuzzReplayTests", "--configuration-cache").build()

    assertTrue(
      File(
        fixtureDir,
        "build/generated-sources/hardening-support/java/com/example/late/support/Ports.java",
      ).isFile,
      generated.output,
    )
    val replay = File(
      fixtureDir,
      "build/generated-sources/fuzz-replay/java/com/example/CodecFuzzSeedReplayTest.java",
    ).readText()
    assertTrue(replay.contains("getResource(\"/fuzz/codec\")"), replay)
    assertFalse(replay.contains(fixtureDir.absolutePath), replay)
    assertTrue(
      File(
        fixtureDir,
        "build/generated-sources/fuzz-replay/java/com/example/CodecFuzzSeedReplayTest.java",
      ).isFile,
      generated.output,
    )
    val reused = runner(
      "generateHardeningTestSupport", "generateFuzzReplayTests", "--configuration-cache").build()
    assertTrue(reused.output.contains("Reusing configuration cache"), reused.output)
  }

  @Test
  fun `disabled source generators store a configuration cache entry`() {
    assertSourceGeneratorsStoreConfigurationCache(generateTestSupport = false)
  }

  @Test
  fun `enabled source generators store a configuration cache entry`() {
    assertSourceGeneratorsStoreConfigurationCache(generateTestSupport = true)
  }

  @Test
  fun `recompileExcludes drops a named source file from PIT's recompile`() {
    // A git-ignored scratch file is a parity hazard: present on one machine, absent on
    // another, it puts a different class on PIT's recompiled root per checkout.
    // recompileExcludes drops it by file name — from the recompile only, not the
    // project's own build, so the class file lands under build/classes as usual.
    val srcDir = File(fixtureDir, "src/main/java/com/example").apply { mkdirs() }
    srcDir.resolve("Codec.java").writeText("package com.example;\npublic final class Codec {}\n")
    srcDir.resolve("Scratch.java").writeText("package com.example;\npublic final class Scratch {}\n")
    val recompiled = File(fixtureDir, "build/mutation-classes/com/example")

    // excluded: PIT's recompiled root carries Codec but not the scratch file...
    writeFixture(
      recompileExcludes = listOf("Scratch.java"),
      registerFuzz = false,
      bytecodeRelease = 21,
      fakePit = false,
    )
    val ok = runner("compileForPitest").build()
    assertFalse(ok.output.contains("FAILED"), ok.output)
    assertTrue(recompiled.resolve("Codec.class").isFile, "Codec.class not on PIT's recompiled root:\n${ok.output}")
    assertFalse(recompiled.resolve("Scratch.class").isFile, "Scratch.class must be excluded:\n${ok.output}")
    // ...while the ordinary build still compiles it — the exclusion is scoped to PIT
    assertTrue(
      File(fixtureDir, "build/classes/java/main/com/example/Scratch.class").isFile,
      "recompileExcludes must not touch the project's own compile:\n${ok.output}"
    )

    // without the exclusion the recompile carries the scratch class too — proof it was
    // genuinely in the source set, dropped only by the name filter
    writeFixture(registerFuzz = false, bytecodeRelease = 21, fakePit = false)
    runner("compileForPitest", "--no-configuration-cache").build()
    assertTrue(recompiled.resolve("Scratch.class").isFile, "Scratch.class should return once un-excluded")
  }

  private fun assertSourceGeneratorsStoreConfigurationCache(generateTestSupport: Boolean) {
    // Consumers run with the configuration cache on, and a task whose execution-time
    // lambda reaches a script-level helper cannot be serialized — the whole build fails
    // with "cannot serialize Gradle script object references", not just the task. It is
    // invisible to every other test here because they never pass the flag, and invisible
    // to the disabled path too: both generators are registered, realized as test
    // sources, and stored either way, so a repo that generates nothing still pays for
    // the capture. Both are covered because both have taken this defect: validating a
    // name from a 'doLast'/'doFirst' reads naturally and captures the whole script.
    writeFixture(generateTestSupport = generateTestSupport)
    val tasks = arrayOf("generateHardeningTestSupport", "generateFuzzReplayTests")
    val stored = runner(*tasks, "--configuration-cache").build().output
    assertFalse(stored.contains("problems were found storing the configuration cache"), stored)
    assertFalse(stored.contains("cannot serialize Gradle script object references"), stored)

    // Without reuse the assertion above proves only that one run tolerated the flag.
    val reused = runner(*tasks, "--configuration-cache").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
  }
}
