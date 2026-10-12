import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.BaselineNotes
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.PrunePreviewState
import java.io.File

/** Prune preview eligibility, selective deletion, and sibling allocation. */
class HardeningRatchetPruneFunctionalTest : HardeningRatchetFunctionalFixture() {

  @Test
  fun `an unmatched row is previewed as a conditional prune candidate`() {
    // A single pass where an accepted row is absent cannot prove why: stable
    // removal and an uninsured solo-vs-gate flip have the same report shape. The
    // check names the exact current prune candidate and gives a conditional evidence
    // workflow without treating this observation as deletion authorization.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,decode,30,MathMutator,SURVIVED\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest.roundTrips"),
      ""
    )
    bindLegacyFixtureRecord()

    val output = runner("pitestEncoding").build().output
    assertTrue(
      output.contains("1 row / 1 unique key is unmatched by this run") &&
          output.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # line 30"),
      "conditional candidate preview missing or did not name the exact row:\n$output"
    )
    assertFalse(
      output.contains("refresh with") || output.contains("since killed"),
      "one report must not claim a cause or prescribe a destructive refresh:\n$output"
    )
    assertTrue(
      output.contains("One fresh history-free absence preview cannot distinguish stable removal from an uninsured load- or mode-dependent flip") &&
          output.contains("persisted exact-candidate sequence now has 1 matching distinct observation(s)") &&
          output.contains("at least two distinct, matching fresh full history-free previews") &&
          output.contains("does not persist or infer that reviewed load context") &&
          output.contains("./gradlew :pitestEncoding -PnoMutationHistory --console=plain") &&
          output.contains(
              "Do not run :pitestEncodingBaselinePrune until two completed previews match.") &&
          output.contains(":pitestEncodingBaselinePrune performs another fresh full history-free write-boundary run"),
      "the preview did not explain what one report cannot prove:\n$output",
    )
    assertFalse(
      output.contains("./gradlew :pitestEncodingBaselinePrune --console=plain"),
      "the first preview exposed the destructive command before two observations matched:\n$output",
    )
    assertFalse(
      output.contains("cannot qualify as fresh full history-free absence evidence"),
      "a current fresh full report was demoted to a non-observation:\n$output",
    )

    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val evidenceFile = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    evidenceFile.writeText(
      evidenceFile.readText().replace("historyAssisted\tfalse", "historyAssisted\ttrue"),
    )
    val assisted = runner("pitestEncodingVerify").build().output
    assertTrue(
      assisted.contains("This [history] preview cannot qualify as fresh full history-free absence evidence") &&
          assisted.contains("at least two distinct, matching fresh full history-free previews") &&
          assisted.contains("./gradlew :pitestEncoding -PnoMutationHistory --console=plain"),
      "an assisted prune preview invited a record edit:\n$assisted",
    )
  }

  @Test
  fun `legacy qualifying prune state stays read only until a fresh preview resets it`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val candidate =
        "com.example.Codec,decode,MathMutator,SURVIVED # family A # line 30"
    baselineFile().writeText(
        "$candidate\n" +
            "com.example.Codec,encode,IncrementsMutator,SURVIVED # family B # line 40\n",
    )
    fun mutant(method: String, line: Int, mutator: String, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.$mutator," +
          "$method,$line,$status," + if (status == "KILLED") "com.example.CodecTest" else "none"
    writeReport(
        listOf(
            mutant("decode", 30, "MathMutator", "KILLED"),
            mutant("encode", 40, "IncrementsMutator", "SURVIVED"),
        ),
        "",
    )
    bindLegacyFixtureRecord()

    runner("pitestEncoding", "-PnoMutationHistory").build()
    val stateFile = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    val current = PrunePreviewState.parse(stateFile.readText())
    val legacy = current.copy(
        matchingObservations = 2,
        qualifies = true,
    ).render().replaceFirst(
        PrunePreviewState.FORMAT_HEADER,
        PrunePreviewState.LEGACY_FORMAT_1_HEADER,
    ).replaceFirst(
        "${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}true",
        "# qualifies true",
    )
    stateFile.writeText(legacy)

    // Revalidating prior output is read-only, including at the format boundary.
    // Migration belongs to the next PIT attempt so an older report can never seed
    // destructive evidence merely because Verify was selected again.
    val replayOutput = runner("pitestEncodingVerify").build().output
    assertTrue(
        replayOutput.contains(
            "This revalidated prior-report preview does not advance prune-preview state; " +
                "replaying Verify is not another PIT observation."),
        replayOutput,
    )
    assertEquals(legacy, stateFile.readText())

    // Repetition stays byte-for-byte read-only.
    runner("pitestEncodingVerify").build()
    assertEquals(legacy, stateFile.readText())

    val firstOutput = runner("pitestEncoding", "-PnoMutationHistory").build().output
    val first = PrunePreviewState.parse(stateFile.readText())
    assertTrue(
        firstOutput.contains(
            "prune-preview state was missing, malformed, or written by an older format") &&
            firstOutput.contains("matching observations reset this run") &&
            firstOutput.contains("not an advisory finding: unsupported prune-preview format"),
        firstOutput,
    )
    assertEquals(1, first.matchingObservations)
    assertTrue(first.qualifies)
    assertEquals(listOf(candidate), first.candidates)
    assertTrue(stateFile.readText().startsWith("${PrunePreviewState.FORMAT_HEADER}\n"))

    val secondOutput = runner("pitestEncoding", "-PnoMutationHistory").build().output
    val second = PrunePreviewState.parse(stateFile.readText())
    assertTrue(
        secondOutput.contains("matches 2 distinct fresh full history-free observation(s)"),
        secondOutput,
    )
    assertEquals(2, second.matchingObservations)
    assertTrue(second.qualifies)
  }

  @Test
  fun `standalone verify does not create missing prune preview state from prior evidence`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
        "com.example.Codec,decode,MathMutator,SURVIVED # reviewed # line 30\n",
    )
    writeReport(
        listOf(
            "Codec.java,com.example.Codec," +
                "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                "decode,30,KILLED,com.example.CodecTest",
        ),
        "",
    )
    bindLegacyFixtureRecord()

    runner("pitestEncoding", "-PnoMutationHistory").build()
    val stateFile = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    assertTrue(stateFile.delete(), "fixture did not remove the existing preview state")

    val replay = runner("pitestEncodingVerify").build().output

    assertFalse(stateFile.exists(), "standalone Verify recreated prune-preview state")
    assertTrue(
        replay.contains(
            "This revalidated prior-report preview does not advance prune-preview state; " +
                "replaying Verify is not another PIT observation."),
        replay,
    )
  }

  @Test
  fun `prune previews persist exact multiset drift and suppress invocation replay`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val rowA = "com.example.Codec,decode,MathMutator,SURVIVED # family A # line 30"
    val rowB = "com.example.Codec,encode,IncrementsMutator,SURVIVED # family B # line 40"
    baselineFile().writeText("$rowA\n$rowB\n")
    fun mutant(method: String, line: Int, mutator: String, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.$mutator," +
          "$method,$line,$status," + if (status == "KILLED") "com.example.CodecTest" else "none"
    writeReport(
        listOf(
            mutant("decode", 30, "MathMutator", "KILLED"),
            mutant("encode", 40, "IncrementsMutator", "SURVIVED"),
        ),
        "",
    )
    bindLegacyFixtureRecord()

    val first = runner("pitestEncoding", "-PnoMutationHistory").build().output
    val stateFile = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    val firstState = PrunePreviewState.parse(stateFile.readText())
    assertTrue(first.contains("stored prune-candidate observation 1 of 2"), first)
    assertEquals(1, firstState.matchingObservations)
    assertEquals(listOf(rowA), firstState.candidates)

    runner("pitestEncodingVerify").build()
    assertEquals(
        firstState,
        PrunePreviewState.parse(stateFile.readText()),
        "re-verifying one completed report manufactured another preview",
    )

    val second = runner("pitestEncoding", "-PnoMutationHistory").build().output
    assertTrue(second.contains("matches 2 distinct fresh full history-free observation(s)"), second)
    assertEquals(2, PrunePreviewState.parse(stateFile.readText()).matchingObservations)

    writeReport(
        listOf(
            mutant("decode", 30, "MathMutator", "KILLED"),
            mutant("encode", 40, "IncrementsMutator", "KILLED"),
        ),
        "",
    )
    val wandered = runner("pitestEncoding", "-PnoMutationHistory").build().output
    val reset = PrunePreviewState.parse(stateFile.readText())
    assertTrue(
        wandered.contains("prune-candidate preview differs from the previous eligible") &&
            wandered.contains("2 rows / 2 unique keys now, 1 row / 1 unique key previously") &&
            wandered.contains("added: $rowB") &&
            wandered.contains("two-matching-preview requirement is not met") &&
            wandered.contains("prune-candidate row change(s) vs previous eligible preview"),
        wandered,
    )
    assertEquals(1, reset.matchingObservations)
    assertEquals(listOf(rowA, rowB).sorted(), reset.candidates)
  }

  @Test
  fun `prune refuses until two prior matching previews and its third run also matches`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val before = "com.example.Codec,decode,MathMutator,SURVIVED # reviewed # line 30\n"
    baselineFile().writeText(before)
    writeReport(
        listOf(
            "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
                "MathMutator,decode,30,KILLED,com.example.CodecTest",
        ),
        "",
    )
    bindLegacyFixtureRecord()

    val first = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(first.contains("only one qualifying candidate observation is stored"), first)
    assertEquals(before, baselineFile().readText())

    val second = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(
        second.contains("advances the stored sequence to 2 matching observation(s)") &&
            second.contains("were not complete before this destructive workflow began"),
        second,
    )
    assertEquals(before, baselineFile().readText())

    val third = rawBaselinePruneRunner().build().output
    assertTrue(third.contains("prune dropped every row unmatched by this run"), third)
    assertFalse(baselineFile().exists(), "authorized prune left the deleted baseline row on disk")
  }

  @Test
  fun `prune names the fallback that chose which sibling to drop and its differing labels`() {
    // Two rows with different labels at one key and a single surviving copy that matches
    // neither recorded line: the keep plan's fallback decides which row stays, and the
    // prune must say so, since the kept row alone would look unanimous.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    baselineFile().writeText("$key # first argument # line 10\n$key # second argument # line 20\n")
    writeReport(
        listOf(
            "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
                "MathMutator,encode,50,SURVIVED,none",
        ),
        "",
    )
    bindLegacyFixtureRecord()
    rawBaselinePruneRunner().buildAndFail()
    rawBaselinePruneRunner().buildAndFail()

    val output = rawBaselinePruneRunner().build().output
    assertTrue(
        output.contains("prune pairs kept siblings by fallback for 1 key(s)") &&
            output.contains("paired by nearest recorded line, ties in file order") &&
            output.contains("The fallback-paired rows at 1 of these key(s) carry different family labels") &&
            output.contains("  $key"),
        output,
    )
    assertTrue(
        output.substringAfterLast("hardening: ").contains("1 ambiguous prune sibling key(s), 1 with differing labels"),
        output,
    )
    assertEquals("$key # second argument # line 50\n", baselineFile().readText(),
        "the row whose recorded line is nearest keeps the copy; the other is dropped")
  }

  @Test
  fun `selective prune refuses mixed siblings even with distinct labels and line tags`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val key = "com.example.Codec,decode,MathMutator,SURVIVED"
    val before = "$key # reviewed retirement # line 10\n" +
        "$key # keep this sibling # line 20\n"
    baselineFile().writeText(before)
    File(fixtureDir, "prune.keys").writeText("$key\n")
    writeReport(listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,decode,10,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,decode,20,SURVIVED,none",
    ), "")
    val output = rawBaselinePruneRunner("-PpruneBaselineKeys=prune.keys").buildAndFail().output
    assertTrue(output.contains("same-key sibling retirement is ambiguous"), output)
    assertTrue(output.contains("1 candidate row(s), 1 protected row(s)"), output)
    assertEquals(before, baselineFile().readText())
    assertFalse(File(fixtureDir, ".pitest-history/encoding.prune-previews").exists())
  }

  @Test
  fun `selective prune still rejects unselected candidate drift and fresh gated debt`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val selected = "com.example.Codec,decode,MathMutator,SURVIVED"
    val unselected = "com.example.Codec,encode,MathMutator,SURVIVED"
    val before = "$selected # reviewed # line 10\n$unselected # retained # line 20\n"
    baselineFile().writeText(before)
    File(fixtureDir, "prune.keys").writeText("$selected\n")
    fun report(unselectedStatus: String, newDebt: Boolean = false) = writeReport(buildList {
      add("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
          "MathMutator,decode,10,KILLED,com.example.CodecTest")
      add("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
          "MathMutator,encode,20,$unselectedStatus," +
          if (unselectedStatus == "KILLED") "com.example.CodecTest" else "none")
      if (newDebt) add("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
          "MathMutator,fresh,30,SURVIVED,none")
    }, "")
    report("SURVIVED")
    bindLegacyFixtureRecord()
    repeat(2) {
      runner("pitestEncoding", "-PnoMutationHistory", "-PpruneBaselineKeys=prune.keys").build()
    }
    report("KILLED")
    val drift = rawBaselinePruneRunner("-PpruneBaselineKeys=prune.keys").buildAndFail().output
    assertTrue(drift.contains("current candidate multiset differs"), drift)
    assertTrue(drift.contains("added: $unselected"), drift)
    assertEquals(before, baselineFile().readText())

    report("KILLED", newDebt = true)
    val debt = rawBaselinePruneRunner("-PpruneBaselineKeys=prune.keys").buildAndFail().output
    assertTrue(debt.contains("not a green shrink-only transition"), debt)
    assertTrue(debt.contains("com.example.Codec,fresh,MathMutator,SURVIVED"), debt)
    assertEquals(before, baselineFile().readText())
  }

  @Test
  fun `omitting selective prune option cannot promote subset previews to full deletion`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val key = "com.example.Codec,decode,MathMutator,SURVIVED"
    val before = "$key # reviewed # line 10\n" +
        "com.example.Codec,old,MathMutator,SURVIVED # unlicensed retained # line 20\n"
    baselineFile().writeText(before)
    // Already part of main.allSource and the resource classpath even without the
    // option: only the explicit selection-presence fence distinguishes this mode.
    val selectorPath = "src/main/resources/prune.keys"
    File(fixtureDir, selectorPath).apply {
      parentFile.mkdirs()
      writeText("$key\n")
    }
    writeReport(listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,decode,10,KILLED,com.example.CodecTest",
    ), "")
    bindLegacyFixtureRecord()
    repeat(2) {
      runner("pitestEncoding", "-PnoMutationHistory", "-PpruneBaselineKeys=$selectorPath").build()
    }
    val evidenceFile = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    val previewInputs = PitestEvidence.parse(evidenceFile.readText()).inputIdentitySha256()
    val output = rawBaselinePruneRunner().buildAndFail().output
    assertEquals(previewInputs, PitestEvidence.parse(evidenceFile.readText()).inputIdentitySha256(),
        "ordinary input identity changed; this fixture must specifically exercise the selection-presence fence")
    assertTrue(output.contains("execution-input identity changed and reset the sequence"), output)
    assertEquals(before, baselineFile().readText())
  }

  @Test
  fun `suite-scoped selective prune takes gate-load previews that the solo writer accepts`() {
    // Under qualityGate every suite verifies; the unscoped selector would ask the
    // sibling 'decoding' baseline for keys only 'encoding' holds.
    writeFixture(
      registerFuzz = false,
      extraSuites = """
          mutation.register("decoding") {
            targetClasses = listOf("com.example.decoding.*")
            targetTests = "com.example.*Test*"
          }
      """.trimIndent(),
    )
    File(fixtureDir, "build.gradle.kts").appendText(
      """
      tasks.named<JavaExec>("pitestDecoding") {
        classpath = sourceSets.main.get().output
        mainClass.set("com.example.FakePit")
        environment(
          "FIXTURE_PIT_REPORT",
          layout.projectDirectory.dir("fixture-pit-report-decoding").asFile.absolutePath,
        )
      }
      """.trimIndent() + "\n"
    )
    listOf(
      File(fixtureDir, "build/reports/pitest/decoding"),
      File(fixtureDir, "fixture-pit-report-decoding"),
    ).forEach { reportDir ->
      reportDir.mkdirs()
      reportDir.resolve("mutations.csv").writeText(
        "Decoder.java,com.example.decoding.Decoder,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,decode,5,KILLED,com.example.DecoderTest\n")
      reportDir.resolve("mutations.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n\n</mutations>\n")
    }
    baselineFile().parentFile.mkdirs()
    val selected = "com.example.Codec,decode,MathMutator,SURVIVED"
    val unselected = "com.example.Codec,encode,MathMutator,SURVIVED # retained # line 20"
    baselineFile().writeText("$selected # reviewed # line 10\n$unselected\n")
    File(fixtureDir, "prune.keys").writeText("$selected\n")
    writeReport(listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,decode,10,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "MathMutator,encode,20,SURVIVED,none",
    ), "")
    bindLegacyFixtureRecord()
    val previews = File(fixtureDir, ".pitest-history/encoding.prune-previews")

    val both = runner("help", "-PpruneBaselineKeys=prune.keys", "-PpruneBaselineKeys.encoding=prune.keys")
        .buildAndFail().output
    assertTrue(both.contains("cannot be combined with suite-scoped selections"), both)

    val misspelt = runner("pitestEncoding", "-PnoMutationHistory", "-PpruneBaselineKeys.encodng=prune.keys")
        .buildAndFail().output
    assertTrue(misspelt.contains("-PpruneBaselineKeys.encodng names no mutation suite of project :"), misspelt)
    assertFalse(previews.exists(), "a refused selection advanced prune-preview state")

    val otherSuite = rawBaselinePruneRunner("-PpruneBaselineKeys.decoding=prune.keys").buildAndFail().output
    assertTrue(otherSuite.contains("remove -PpruneBaselineKeys.decoding"), otherSuite)

    val first = runner("qualityGate", "-PnoMutationHistory", "-PpruneBaselineKeys.encoding=prune.keys")
        .build().output
    assertTrue(first.contains("pitest baseline 'encoding': selective prune preview"), first)
    assertFalse(first.contains("pitest baseline 'decoding': selective prune preview"), first)
    assertTrue(
      first.contains("./gradlew :qualityGate -PnoMutationHistory '-PpruneBaselineKeys.encoding=prune.keys'"),
      first,
    )
    val second = runner("qualityGate", "-PnoMutationHistory", "-PpruneBaselineKeys.encoding=prune.keys")
        .build().output
    assertTrue(second.contains("prune-candidate preview matches 2 distinct"), second)

    // The file, not its spelling, is bound: the unscoped solo writer continues the sequence.
    rawBaselinePruneRunner("-PpruneBaselineKeys=prune.keys").build()
    assertEquals("$unselected\n", baselineFile().readText())

    val unscopedGate = runner("qualityGate", "-PnoMutationHistory", "-PpruneBaselineKeys=prune.keys")
        .buildAndFail().output
    assertTrue(unscopedGate.contains("keys absent from the accepted baseline"), unscopedGate)
    assertTrue(
      unscopedGate.contains("./gradlew :qualityGate -PnoMutationHistory '-PpruneBaselineKeys.<suite>=prune.keys'"),
      unscopedGate,
    )
  }

  @Test
  fun `prune groups duplicate rows and possible locations without collapsing its multiset`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val survivedKey = "com.example.Codec,decode,MathMutator,SURVIVED"
    val noCoverageKey = "com.example.Codec,encode,NullReturnValsMutator,NO_COVERAGE"
    val survivedRow = "$survivedKey # untriaged # line 99"
    val noCoverageRow = "$noCoverageKey # untriaged # line 88"
    baselineFile().writeText(
        List(4) { survivedRow }.joinToString("\n", postfix = "\n") +
            List(3) { noCoverageRow }.joinToString("\n", postfix = "\n"),
    )

    fun csv(method: String, line: Int, mutator: String, status: String): String =
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
            "$mutator,$method,$line,$status," +
            if (status == "KILLED") "com.example.CodecTest" else "none"
    fun xml(method: String, line: Int, mutator: String, status: String, description: String) =
        """
        <mutation status="$status" detected="${status == "KILLED"}">
          <sourceFile>Codec.java</sourceFile>
          <mutatedClass>com.example.Codec</mutatedClass>
          <mutatedMethod>$method</mutatedMethod>
          <lineNumber>$line</lineNumber>
          <mutator>org.pitest.mutationtest.engine.gregor.mutators.$mutator</mutator>
          <description>$description</description>
        </mutation>
        """.trimIndent()
    writeReport(
        listOf(
            csv("decode", 30, "MathMutator", "SURVIVED"),
            csv("decode", 30, "MathMutator", "KILLED"),
            csv("decode", 31, "MathMutator", "KILLED"),
            csv("decode", 31, "MathMutator", "KILLED"),
            csv("decode", 32, "MathMutator", "KILLED"),
            csv("encode", 40, "NullReturnValsMutator", "NO_COVERAGE"),
            csv("encode", 41, "NullReturnValsMutator", "KILLED"),
            csv("encode", 42, "NullReturnValsMutator", "KILLED"),
        ),
        listOf(
            xml("decode", 30, "MathMutator", "SURVIVED", "live survived sibling"),
            xml("decode", 30, "MathMutator", "KILLED", "same-line killed sibling"),
            xml("decode", 31, "MathMutator", "KILLED", "first killed possibility"),
            xml("decode", 31, "MathMutator", "KILLED", "first killed possibility"),
            xml("decode", 32, "MathMutator", "KILLED", "second killed possibility"),
            xml("encode", 40, "NullReturnValsMutator", "NO_COVERAGE", "live uncovered sibling"),
            xml("encode", 41, "NullReturnValsMutator", "KILLED", "third killed possibility"),
            xml("encode", 42, "NullReturnValsMutator", "KILLED", "fourth killed possibility"),
        ).joinToString("\n"),
    )
    bindLegacyFixtureRecord()

    fun assertGrouped(output: String) {
      assertTrue(output.contains("3 × $survivedRow"), output)
      assertTrue(output.contains("2 × $noCoverageRow"), output)
      assertTrue(
          output.contains("possible report locations grouped by line-less key:") &&
              output.contains("$survivedKey (3 physical rows):") &&
              output.contains("$noCoverageKey (2 physical rows):"),
          output,
      )
      listOf(
          "1 × SURVIVED — line 30: live survived sibling",
          "1 × KILLED — line 30: same-line killed sibling",
          "2 × KILLED — line 31: first killed possibility",
          "1 × KILLED — line 32: second killed possibility",
          "1 × NO_COVERAGE — line 40: live uncovered sibling",
          "1 × KILLED — line 41: third killed possibility",
          "1 × KILLED — line 42: fourth killed possibility",
      ).forEach { location ->
        assertEquals(
            1,
            Regex(Regex.escape(location)).findAll(output).count(),
            "location should print once in a shared key block:\n$output",
        )
      }
    }

    val first = runner("pitestEncoding", "-PnoMutationHistory").build().output
    assertTrue(first.contains("7 rows / 2 unique keys — 7 '# untriaged'"), first)
    assertTrue(first.contains("5 rows / 2 unique keys are unmatched by this run"), first)
    assertTrue(first.contains("stored prune-candidate observation 1 of 2 (5 rows / 2 unique keys)"), first)
    assertGrouped(first)
    val previewState = PrunePreviewState.parse(
        File(fixtureDir, ".pitest-history/encoding.prune-previews").readText(),
    )
    assertEquals(5, previewState.candidates.size, "diagnostic grouping collapsed preview authority")
    assertEquals(3, previewState.candidates.count { BaselineNotes.parse(it).key == survivedKey })
    assertEquals(2, previewState.candidates.count { BaselineNotes.parse(it).key == noCoverageKey })

    val second = runner("pitestEncoding", "-PnoMutationHistory").build().output
    assertTrue(
        second.contains("observation(s) (5 rows / 2 unique keys)"),
        second,
    )
    val applied = rawBaselinePruneRunner().build().output
    assertTrue(
        applied.contains(
            "prune dropped 5 rows / 2 unique keys unmatched by this run " +
                "(baseline now 2 rows / 2 unique keys"),
        applied,
    )
    assertGrouped(applied)
    val remaining = baselineFile().readLines().filter { it.isNotBlank() }
    assertEquals(2, remaining.size)
    assertEquals(setOf(survivedKey, noCoverageKey), remaining.map { BaselineNotes.parse(it).key }.toSet())
  }

  @Test
  fun `fresh gated debt breaks matching prune-preview state`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val before = "com.example.Codec,decode,MathMutator,SURVIVED # reviewed # line 30\n"
    baselineFile().writeText(before)
    fun report(includeFresh: Boolean) = writeReport(
        buildList {
          add(
              "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
                  "MathMutator,decode,30,KILLED,com.example.CodecTest")
          if (includeFresh) {
            add(
                "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
                    "IncrementsMutator,encode,40,SURVIVED,none")
          }
        },
        "",
    )
    report(includeFresh = false)
    bindLegacyFixtureRecord()
    repeat(2) { runner("pitestEncoding", "-PnoMutationHistory").build() }

    report(includeFresh = true)
    val gated = runner("pitestEncoding", "-PnoMutationHistory").buildAndFail().output
    val stateFile = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    val reset = PrunePreviewState.parse(stateFile.readText())
    assertTrue(gated.contains("resets prune-preview matching state"), gated)
    assertFalse(reset.qualifies)
    assertEquals(0, reset.matchingObservations)

    report(includeFresh = false)
    val refused = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(refused.contains("only one qualifying candidate observation is stored"), refused)
    assertEquals(before, baselineFile().readText())
  }

  @Test
  fun `prune refuses a pending status flip before changing the baseline`() {
    // Shrink-only cannot mean gate-free: this run has a current SURVIVED row where
    // only NO_COVERAGE was accepted. The keep plan protects the old row as a pending
    // flip, but that does not accept the current status; prune must fail before it
    // drops the unrelated since-killed row or rewrites legacy line metadata.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val before =
      "com.example.Codec,encode,9,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,encode,12,MathMutator,NO_COVERAGE # unreachable claim\n" +
          "com.example.Codec,decode,30,MathMutator,SURVIVED # since killed\n" +
          "com.example.Codec,decode,40,IncrementsMutator,NO_COVERAGE\n"
    baselineFile().writeText(before)
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,40,SURVIVED,none",
      ),
      ""
    )

    val output = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(output.contains("refusing :pitestEncodingBaselinePrune"), output)
    assertTrue(output.contains("1 gated mutant(s)"), output)
    assertTrue(output.contains("com.example.Codec,decode,IncrementsMutator,SURVIVED"), output)
    assertFalse(
      output.contains("Pre-write notice:"),
      "a refused prune promised a line-tag rewrite it did not perform:\n$output",
    )
    assertEquals(before, baselineFile().readText(), "a refused prune changed the baseline")
  }

  @Test
  fun `a flip-insured key is kept by prune and excluded from the candidate preview`() {
    // Flip-insurance rows record an OBSERVED flap: on a run where the mutant reads
    // killed, the row is not stale — pruning it would fail the next solo run with an
    // unexplained survivor, and the old stale hint used to recommend exactly that. The
    // marker rides in the note or its parenthetical ('flip insurance', the wording
    // pitestModeCompareUnion writes), and the row leaves by its written removal criterion,
    // never by refresh.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family (flip insurance: gate=KILLED, solo=SURVIVED) # line 10\n" +
          "com.example.Codec,decode,MathMutator,SURVIVED # since killed # line 30\n"
    )
    fun mutant(method: String, line: Int) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,$method,$line,KILLED,com.example.CodecTest"
    writeReport(listOf(mutant("encode", 10), mutant("decode", 30)), "")

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("1 row / 1 unique key is unmatched by this run") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # since killed # line 30"),
      "the unmarked row must still appear in the prune-candidate preview:\n$output"
    )
    assertTrue(
      output.contains("1 flip-insured row(s) unmatched at their own status this run") &&
          output.contains("com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family"),
      "insured flap not reported as such:\n$output"
    )

    val pruned = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family (flip insurance: gate=KILLED, solo=SURVIVED) # line 10"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(pruned.contains("flip insurance at this key"), pruned)
    assertTrue(pruned.contains("prune dropped 1 row / 1 unique key"), pruned)

    val updated = baselineUpdateRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family (flip insurance: gate=KILLED, solo=SURVIVED) # line 10"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "a report-driven update deleted persistent flip insurance:\n$updated",
    )
    assertTrue(updated.contains("1 flip-insurance row(s) preserved"), updated)

    // with only the insured row left, the run reports the flap alone — no removal candidate
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("BaselinePrune classifier marks exactly these row(s)"),
      "an insured flap alone must not become a prune candidate:\n$settled"
    )
    assertTrue(settled.contains("1 flip-insured row(s) unmatched at their own status this run"), settled)
  }

  @Test
  fun `insurance at a key covers its uninsured siblings`() {
    // A flappy compound condition flaps as a family, and which sibling reads killed
    // on a given run is itself load-dependent — so the marker is key-level: one
    // insured row keeps its bare twin from being pruned on the twin's unlucky run.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family # line 10\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family (flip insurance: gate=KILLED, solo=SURVIVED) # line 20\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(10, "KILLED"), mutant(20, "SURVIVED")), "")

    val pruned = baselinePruneRunner().build().output
    assertEquals(
      2,
      baselineFile().readLines().filter { it.isNotBlank() }.size,
      "the bare twin of an insured key was pruned:\n$pruned"
    )
    assertTrue(pruned.contains("flip insurance at this key"), pruned)
  }

  @Test
  fun `prune shrinks a key with killed siblings and drops the killed line's row`() {
    // The verify's stale count is a multiset comparison, so a key holding three
    // accepted rows against two unkilled mutants has one stale row — and the stale
    // hint names prune. Prune must agree: before this, the excess row satisfied the
    // cross-status keep through its own same-status siblings and prune reported
    // "dropped nothing — every row matches" (observed against a real baseline,
    // 13 rows vs 12).
    writeFixture()
    baselineFile().parentFile.mkdirs()
    // the killed mutant's row sits in the MIDDLE of the file and its neighbours are
    // tagged to the surviving lines: file-order budgeting would drop line 30's row
    // and leave a kept tag pointing at the killed line 20
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # keep-first # line 10\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 20\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # keep-last # line 30\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(10, "SURVIVED"), mutant(20, "KILLED"), mutant(30, "SURVIVED")), "")

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # keep-first # line 10",
        "com.example.Codec,encode,MathMutator,SURVIVED # keep-last # line 30",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row / 1 unique key"), output)
    assertTrue(output.contains("# since killed # line 20"), output)
    assertFalse(output.contains("flip pending triage"), "same-status siblings misread as a cross-status flip:\n$output")

    // rerunning against the same report is now a no-op — the counts agree
    val settled = baselinePruneRunner().build().output
    assertTrue(settled.contains("prune dropped nothing"), settled)
  }

  @Test
  fun `a killed row at a status-heterogeneous key is dropped, not kept as a flip`() {
    // A coordinate can legitimately hold rows at two statuses — a SURVIVED sibling
    // beside a NO_COVERAGE one. When the survivor is killed, nothing flipped: the
    // NO_COVERAGE mutant is matched by its own row. A coordinate-level status check
    // let that matched mutant vouch for the killed row — prune reported "dropped
    // nothing" while the old preview's independent allocator named the row as a
    // drop. The keep must demand an unmatched counterpart, consumed per kept
    // row: the verify's own newly-covered pairing.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 10\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim # line 20\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,NO_COVERAGE,none",
      ),
      ""
    )

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row / 1 unique key"), output)
    assertTrue(output.contains("# since killed # line 10"), output)
    assertFalse(output.contains("flip pending triage"), "a matched mutant vouched for a killed sibling:\n$output")
  }

  @Test
  fun `one timed-out sibling cannot vouch for two killed rows at its coordinate`() {
    // The TIMED_OUT keep is a budget, not a status: N mutants timed out at a
    // coordinate can hide at most N rows behind the watchdog. A presence check
    // kept every unmatched row at the coordinate — one audited permanent timeout
    // vouched for arbitrarily many genuinely killed acceptances, prune reported
    // "dropped nothing", and dead rows accumulated (the same hole-shape the
    // pending-flip keep one branch below was refitted for).
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # first # line 20\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # second # line 24\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(12, "TIMED_OUT"), mutant(20, "KILLED"), mutant(24, "KILLED")), "")

    // the candidate preview budgets identically: one unmatched row reads
    // TIMED_OUT-kept and the excess is the exact row prune would remove
    val hinted = runner("pitestEncodingVerify").build().output
    assertTrue(
      hinted.contains("1 row / 1 unique key is unmatched by this run") &&
          hinted.contains("com.example.Codec,encode,MathMutator,SURVIVED # second # line 24"),
      "the excess row must be named as the exact candidate:\n$hinted"
    )
    assertTrue(hinted.contains("1 baseline row(s) protected by same-key timeout budget"), hinted)
    val summary = hinted.substringAfterLast("hardening: ")
    assertTrue(summary.contains("1 baseline prune candidate row(s)") &&
        summary.contains("1 baseline row(s) protected by same-key timeout budget"), hinted)
    assertFalse(hinted.contains("baseline row(s) read TIMED_OUT"), hinted)

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # first # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "one timeout budget must keep exactly one row:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row / 1 unique key"), output)
    assertTrue(output.contains("kept 1 row / 1 unique key unmatched"), output)
    assertTrue(
      output.contains("preserved by this run's TIMED_OUT budget") &&
          output.contains("same-mutant versus sibling identity remains ambiguous"),
      output,
    )

    val updated = baselineUpdateRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # first # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "a report-driven update deleted the row budgeted to this run's timeout:\n$updated",
    )
    assertTrue(updated.contains("1 accepted timeout row(s) preserved"), updated)
  }

  @Test
  fun `the timeout budget keeps the row whose line tag actually timed out`() {
    // Which rows hold the timeout budget follows line affinity, the survivor
    // budget's own assignment rule: with the killed mutant's row first in the
    // file, file order alone would keep the killed row and leave its tag naming
    // a killed line, while the row that actually sits behind the watchdog drops.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 20\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # slow guard # line 24\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(20, "KILLED"), mutant(24, "TIMED_OUT")), "")

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # slow guard # line 24"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the budget must follow the timed-out line, not file order:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row / 1 unique key"), output)
    assertTrue(output.contains("# since killed # line 20"), output)
  }

  @Test
  fun `the candidate preview names the same rows prune keeps at a cross-status coordinate`() {
    // The preview and prune read one keep plan. Two independent allocators once
    // disagreed exactly here: the old preview budgeted the coordinate's timeout in
    // baseline-file order over key strings while prune budgeted affinity-first over
    // rows, so they named different siblings.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # a # line 20\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # b # line 24\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(20, "KILLED"), mutant(24, "TIMED_OUT")), "")

    val hinted = runner("pitestEncodingVerify").build().output
    val timedOutHint = hinted.lineSequence()
      .dropWhile { !it.contains("protected by same-key timeout budget") }.take(2).joinToString("\n")
    assertTrue(
      timedOutHint.contains("com.example.Codec,encode,MathMutator,NO_COVERAGE # b # line 24"),
      "the hint must name the affine row as kept:\n$hinted"
    )
    assertTrue(
      hinted.contains("1 row / 1 unique key is unmatched by this run") &&
          hinted.contains("com.example.Codec,encode,MathMutator,SURVIVED # a # line 20"),
      "the unmatched row must be named as the exact candidate:\n$hinted"
    )

    val pruned = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,NO_COVERAGE # b # line 24"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "prune must keep the row the hint named:\n$pruned"
    )
  }

  @Test
  fun `a tag naming a killed line demotes its row for the timeout budget`() {
    // The mirror of line affinity: a row whose '# line' tag names a line KILLED
    // this run is provably the killed mutant's row, so it takes the timeout
    // budget last — file order alone handed it the budget (it sat first) and
    // dropped the bare sibling whose mutant was the one actually hanging.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 20\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # newly hung\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(20, "KILLED"), mutant(50, "TIMED_OUT")), "")

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,NO_COVERAGE # newly hung"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the killed-line row must not outrank the bare row for the timeout budget:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row / 1 unique key"), output)
    assertTrue(output.contains("# since killed # line 20"), output)
  }

  @Test
  fun `an insured row does not spend the timeout budget its sibling needs`() {
    // The insurance keep is unconditional, so it must not consume the timeout
    // budget: greedily assigning the coordinate's only timeout to the insured
    // row (file order, no usable tags) vouched for nobody — the insured row was
    // kept anyway — and pushed the different-status sibling, the one row that
    // timeout could actually be hiding, past the budget and into a drop the old
    // presence check never made.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=KILLED, solo=SURVIVED)\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant(20, "KILLED"), mutant(50, "TIMED_OUT")), "")

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=KILLED, solo=SURVIVED)",
        "com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the timeout budget must reach the uninsured sibling:\n$output"
    )
    assertTrue(output.contains("prune dropped nothing"), output)
    assertTrue(output.contains("flip insurance at this key"), output)
    assertTrue(
      output.contains("preserved by this run's TIMED_OUT budget") &&
          output.contains("same-mutant versus sibling identity remains ambiguous"),
      output,
    )
  }
}
