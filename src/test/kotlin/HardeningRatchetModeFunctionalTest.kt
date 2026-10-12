import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File
import java.util.UUID

/** Mode snapshots, comparison, insurance, and atomic record validation. */
class HardeningRatchetModeFunctionalTest : HardeningRatchetFunctionalFixture() {

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
    val solo = modeSnapshot("solo")
    assertTrue(solo.output.contains("stashed as 'solo'"), solo.output)
    assertTrue(File(fixtureDir, "build/pitest-modes/solo/encoding.csv").isFile, "solo snapshot missing")
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding").exists(), "reports must be cleared")

    // mode 'gate': the same mutant survives — an unkilled-boundary flip
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )
    modeSnapshot("gate")

    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertTrue(compare.output.contains("gate=SURVIVED, solo=KILLED"), compare.output)
    assertTrue(compare.output.contains("pitestModeCompareUnion"), compare.output)

    val union = modeCompareUnionRunner().build()
    assertTrue(union.output.contains("flip insurance written"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # stale insurance # line 50",
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED, solo=KILLED) # line 12"
      ),
      baselineFile().readLines(),
      "union must append the flip row with its evidence note and keep existing rows"
    )

    val insured = runner("pitestModeCompare").build()
    assertTrue(insured.output.contains("already insured in the baseline"), insured.output)
    assertTrue(insured.output.contains("0 uninsured boundary flip(s)"), insured.output)
    assertTrue(
      insured.output.contains("com.example.Codec,decode,MathMutator,SURVIVED # stale insurance"),
      "dead-row sweep missing:\n" + insured.output
    )
  }

  @Test
  fun `mode union annotates a covered bare row and prune keeps the persistent insurance`() {
    // Keep the snapshot's fixture provenance and the verify task's configured tool
    // version identical so the final record-writing prune exercises insurance, not
    // the independent cross-version refusal.
    writeFixture(beforeHardening = "hardening.pitestVersion.set(\"fixture-pit\")")
    baselineFile().parentFile.mkdirs()
    val bareRow = "com.example.Codec,encode,MathMutator,SURVIVED # line 12"
    baselineFile().writeText("$bareRow\n")
    fun mutant(status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")

    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val compare = runner("pitestModeCompare").buildAndFail().output
    assertTrue(compare.contains("1 uninsured boundary flip(s)"), compare)
    assertTrue(
      compare.contains("baseline multiplicity covers this flip") &&
          compare.contains("no literal 'flip insurance' marker") &&
          compare.contains("annotate the existing row(s) without adding duplicates"),
      "a covered but unmarked row was treated as persistent insurance:\n$compare",
    )
    assertFalse(compare.contains("already insured in the baseline"), compare)

    val union = modeCompareUnionRunner().build().output
    assertTrue(
      union.contains("1 existing row(s) annotated, 0 row(s) added"),
      "the union did not annotate the covered row in place:\n$union",
    )
    val insuredRow =
      "com.example.Codec,encode,MathMutator,SURVIVED " +
          "# flip insurance (gate=SURVIVED, solo=KILLED) # line 12"
    assertEquals(
      listOf(insuredRow),
      baselineFile().readLines(),
      "annotation must preserve the row and its line tag without adding a sibling",
    )

    writeReport(listOf(mutant("KILLED")), "")
    val verified = runner("pitestEncodingVerify").build().output
    assertFalse(
      verified.contains("BaselinePrune classifier marks exactly these row(s)"),
      "a killed observation offered persistent flip insurance as a prune candidate:\n$verified",
    )
    assertTrue(
      verified.contains("1 flip-insured row(s) unmatched at their own status this run"),
      verified,
    )

    val beforePrune = baselineFile().readText()
    val pruned = baselinePruneRunner().build().output
    assertEquals(beforePrune, baselineFile().readText(), "prune removed or rewrote persistent insurance")
    assertTrue(pruned.contains("prune dropped nothing"), pruned)
    assertTrue(pruned.contains("flip insurance at this key"), pruned)
  }

  @Test
  fun `read-only mode comparison refuses torn and malformed committed provenance`() {
    writeFixture(beforeHardening = "hardening.pitestVersion.set(\"fixture-pit\")")
    baselineFile().apply {
      parentFile.mkdirs()
      writeText("com.example.Codec,encode,MathMutator,SURVIVED # line 12\n")
    }
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")
    val before = baselineFile().readText()
    val config = File(fixtureDir, "config/pitest")
    val version = config.resolve("encoding-pitest-version")
    val toolchain = config.resolve("encoding-pitest-toolchain.tsv")
    val toolchainBytes = toolchain.readBytes()
    assertTrue(toolchain.delete())

    val torn = runner("pitestModeCompare").buildAndFail().output
    assertTrue(torn.contains("committed mutation provenance is torn"), torn)
    assertTrue(torn.contains("before interpreting mode results"), torn)
    assertEquals(before, baselineFile().readText(), "torn provenance changed mode insurance")

    toolchain.writeBytes(toolchainBytes)
    version.writeText("fixture-pit\n\n")
    val malformed = runner("pitestModeCompare").buildAndFail().output

    assertTrue(malformed.contains("committed PIT-version stamp is invalid"), malformed)
    assertTrue(malformed.contains("at most one trailing LF"), malformed)
    assertEquals(before, baselineFile().readText(), "malformed provenance changed mode insurance")
  }

  @Test
  fun `one slow mutant vouches for one row in the sweep, and never for the insured one`() {
    // The dead-row sweep's timeout budget is the MIN across modes — a mutant that
    // timed out in every mode is one physical mutant, so it accounts for one row,
    // not one per mode — and plain rows claim it before insured rows (the keep
    // plan's insurance-never-spends-the-budget rule): the insured row past the
    // budget is exactly the "insurance that outlived its cause" the sweep names.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=KILLED, solo=SURVIVED)\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # plain sibling\n"
    )
    fun mutant(line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    // both modes: one TIMED_OUT mutant + one KILLED at the coordinate, nothing gated
    writeReport(listOf(mutant(12, "TIMED_OUT"), mutant(20, "KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant(12, "TIMED_OUT"), mutant(20, "KILLED")), "")
    modeSnapshot("gate")

    val compare = runner("pitestModeCompare").build().output
    assertTrue(
      compare.contains("1 accepted row(s) unkilled in no snapshotted mode"),
      "the single timeout budget must exempt exactly one row:\n$compare"
    )
    assertTrue(
      compare.contains("com.example.Codec,encode,MathMutator,SURVIVED # flip insurance"),
      "the swept row must be the insured one — plain rows claim the budget first:\n$compare"
    )
  }

  @Test
  fun `mode sweep accounts for live siblings by multiplicity`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # plain sibling\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (old load flip)\n"
    )
    val survivor =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"
    writeReport(listOf(survivor), "")
    modeSnapshot("solo")
    writeReport(listOf(survivor), "")
    modeSnapshot("gate")

    val compare = runner("pitestModeCompare").build().output
    assertTrue(
      compare.contains("1 accepted row(s) unkilled in no snapshotted mode"),
      "one survivor incorrectly vouched for two accepted rows:\n$compare")
    assertTrue(
      compare.contains("# flip insurance (old load flip)"),
      "the surplus insurance row should be swept before its plain sibling:\n$compare")
  }

  @Test
  fun `mode compare preserves comments and diagnoses malformed rows on verify's terms`() {
    // pitestModeCompareUnion is a baseline writer like the suite writer tasks: a
    // malformed row would be silently dropped (refused
    // instead), and comment lines survive the row-slot rewrite.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val validBaseline =
      "  # context for the row below\n" +
          "com.example.Codec,decode,IncrementsMutator,SURVIVED # line 50\n"
    baselineFile().writeText(validBaseline)
    fun mutant(status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    // Bind the valid legacy record before deliberately corrupting it. Rebase is
    // required for provenance adoption and correctly refuses malformed input.
    bindLegacyFixtureRecord()
    baselineFile().writeText(validBaseline + "com.example.Codec,encode\n")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val refused = modeCompareUnionRunner().buildAndFail().output
    assertTrue(refused.contains("1 malformed row(s)"), refused)
    assertTrue(refused.contains("Fix the row shape first"), refused)

    // with the malformed row fixed, the union writes without disturbing the comment
    baselineFile().writeText(validBaseline)
    val unioned = modeCompareUnionRunner().build().output
    assertTrue(unioned.contains("flip insurance written"), unioned)
    assertFalse(unioned.contains("do not survive"), unioned)
    assertTrue(
      baselineFile().readText().contains("  # context for the row below\n"),
      "the insurance rewrite dropped its comment:\n${baselineFile().readText()}"
    )
  }

  @Test
  fun `mode compare warns on invalid line metadata and union refuses it`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val validBaseline =
      "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance # line 790\n"
    baselineFile().writeText(validBaseline)
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,790,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")

    writeReport(listOf(mutant("KILLED")), "")
    bindLegacyFixtureRecord()
    val invalidBaseline =
      "com.example.Codec,encode,MathMutator,SURVIVED " +
          "# flip insurance # lines 786-800 # line 790\n"
    baselineFile().writeText(invalidBaseline)
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val warning = runner("pitestModeCompare").build().output
    assertTrue(
      warning.contains("1 accepted row(s) in encoding-accepted.csv carry invalid diagnostic line metadata") &&
          warning.contains("invalid line metadata '# lines 786-800'"),
      warning,
    )

    val refusal = modeCompareUnionRunner().buildAndFail().output
    assertTrue(refusal.contains("pitestModeCompareUnion refuses to rewrite invalid line metadata"), refusal)
    assertEquals(invalidBaseline, baselineFile().readText(), "mode insurance rewrote invalid metadata")
  }

  @Test
  fun `flip insurance counts siblings — one row cannot insure two flipped twins`() {
    // Two sibling mutants share the line-less key and flip together under load. The
    // verify compares multisets, so per gated status the baseline needs as many rows
    // as the widest mode observed: reading a single existing row as "already
    // insured" would fail the next gate verify on the surfaced twin — the same
    // set-shaped reasoning the union write was cured of (casebook: the union write
    // that deduped siblings), in the decision rather than the write.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # earlier insurance # line 12\n")

    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,KILLED,com.example.CodecTest",
      ),
      ""
    )
    modeSnapshot("solo")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,SURVIVED,none",
      ),
      ""
    )
    modeSnapshot("gate")

    // one accepted row, two flipped siblings: NOT insured
    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertFalse(compare.output.contains("(already insured in the baseline)"), compare.output)

    // the union first annotates the existing row, then writes exactly the true
    // multiplicity shortfall
    val union = modeCompareUnionRunner().build()
    assertTrue(
      union.output.contains("1 existing row(s) annotated, 1 row(s) added"),
      union.output,
    )
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED " +
            "# earlier insurance (flip insurance: gate=SURVIVED/SURVIVED, solo=KILLED/KILLED) # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED/SURVIVED, solo=KILLED/KILLED) # lines 12, 40",
      ),
      baselineFile().readLines(),
      "union must top the key up to the widest mode's sibling count"
    )

    // and with counts matched, the same snapshots read as insured
    val insuredNow = runner("pitestModeCompare").build()
    assertTrue(insuredNow.output.contains("already insured in the baseline"), insuredNow.output)
    assertTrue(insuredNow.output.contains("0 uninsured boundary flip(s)"), insuredNow.output)
  }

  @Test
  fun `a sibling flip beside an always-surviving twin crosses the boundary`() {
    // One sibling flips KILLED (solo) -> SURVIVED (gate) while its twin survives in
    // both modes. Gated PRESENCE is identical across modes, so a presence-based
    // crossing check read this as benign — but the gated sub-multiset went 1 -> 2,
    // and the gate verify fails on the second copy against a one-row baseline.
    // Under line-full keys these were two keys and each crossed on its own; the
    // line-less merge made crossing a count question, like insurance.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # the always-surviving twin # line 40\n")

    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,SURVIVED,none",
      ),
      ""
    )
    modeSnapshot("solo")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,SURVIVED,none",
      ),
      ""
    )
    modeSnapshot("gate")

    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertFalse(
      compare.output.contains("benign — cannot move the ratchet"),
      "a gated count change must not read as benign:\n" + compare.output
    )

    val union = modeCompareUnionRunner().build()
    assertTrue(
      union.output.contains("1 existing row(s) annotated, 1 row(s) added"),
      union.output,
    )
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED " +
            "# the always-surviving twin " +
            "(flip insurance: gate=SURVIVED/SURVIVED, solo=KILLED/SURVIVED) # line 40",
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED/SURVIVED, solo=KILLED/SURVIVED) # lines 12, 40",
      ),
      baselineFile().readLines(),
      "union must top the key up to the gate mode's sibling count"
    )
  }

  @Test
  fun `mode snapshot refuses partial, unlabeled, or history-assisted reports`() {
    writeFixture()
    val unlabeled = runner("pitestModeSnapshot").buildAndFail()
    assertTrue(unlabeled.output.contains("needs -PpitestMode="), unlabeled.output)

    val missing = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail()
    assertTrue(missing.output.contains("no report for 'encoding'"), missing.output)
    assertTrue(missing.output.contains("run :pitestModeSnapshot with the same"), missing.output)
    assertTrue(missing.output.contains("sufficient closure"), missing.output)

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val assisted = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail()
    assertTrue(assisted.output.contains("history-assisted"), assisted.output)
    assertTrue(assisted.output.contains("-PnoMutationHistory"), assisted.output)

    val report = File(fixtureDir, "build/reports/pitest/encoding/mutations.csv")
    val evidenceFile = report.parentFile.resolve(".evidence.tsv")
    report.parentFile.resolve(".history-assisted").delete()
    fun evidence(scope: String, historyAssisted: Boolean) = PitestEvidence(
      suite = "encoding",
      invocationId = UUID.randomUUID().toString(),
      pitestVersion = "fixture-pit",
      junitPluginVersion = "fixture-junit",
      pluginSha256 = "fixture-plugin",
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = "fixture-java",
      sourceSha256 = "fixture-source",
      classesSha256 = "fixture-classes",
      classpathSha256 = "fixture-classpath",
      toolClasspathSha256 = "fixture-tool-classpath",
      mutationToolchainSha256 = "fixture-mutation-toolchain",
      configurationSha256 = "fixture-configuration",
      reportSha256 = PitestEvidence.sha256(report),
      scope = scope,
      historyAssisted = historyAssisted,
    ).render()
    evidenceFile.writeText(evidence("com.example.Codec", false))
    val markerlessScoped = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail().output
    assertTrue(markerlessScoped.contains("evidence manifest says the report is scoped"), markerlessScoped)
    assertTrue(report.isFile, "markerless scoped evidence cleared its report")

    evidenceFile.writeText(evidence(PitestEvidence.FULL_SCOPE, true))
    val markerlessHistory = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail().output
    assertTrue(
      markerlessHistory.contains("evidence manifest says the report is history-assisted"),
      markerlessHistory,
    )
    assertTrue(report.isFile, "markerless history evidence cleared its report")

    val single = runner("pitestModeCompare").buildAndFail()
    assertTrue(single.output.contains("at least two labeled snapshots"), single.output)
  }

  @Test
  fun `mode snapshot rejects traversal labels before deleting anything`() {
    writeFixture()
    val sentinel = File(fixtureDir, "build/preserve.txt").apply {
      parentFile.mkdirs()
      writeText("preserve")
    }

    val failed = runner("pitestModeSnapshot", "-PpitestMode=..").buildAndFail().output

    assertTrue(failed.contains("pitestModeSnapshot label name '..' is unsafe"), failed)
    assertTrue(sentinel.isFile, "snapshot label escaped its root:\n$failed")
    assertEquals("preserve", sentinel.readText())
  }

  @Test
  fun `mode snapshot validates every suite before replacing or clearing anything`() {
    writeFixture(
      extraSuites = """
        mutation.register("parsing") {
          targetClasses = listOf("com.example.Parser")
          targetTests = "com.example.*Test*"
        }
      """.trimIndent(),
    )
    val row =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,KILLED,com.example.CodecTest"
    writeReport(listOf(row), "")
    val parsingDir = File(fixtureDir, "build/reports/pitest/parsing").apply { mkdirs() }
    parsingDir.resolve("mutations.csv").writeText(row.replace("Codec", "Parser") + "\n")
    parsingDir.resolve(".running").writeText("")
    val prior = File(fixtureDir, "build/pitest-modes/solo/prior.txt").apply {
      parentFile.mkdirs()
      writeText("prior snapshot")
    }

    val failed = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail().output

    assertTrue(failed.contains("'parsing' report was left by an interrupted or failed run"), failed)
    assertTrue(
      failed.contains("re-run every suite in this mode") &&
          failed.contains("run :pitestModeSnapshot with the same -PpitestMode label") &&
          failed.contains("successful, clean mode run and snapshot") &&
          failed.contains("creates no persistent mutation-record debt") &&
          failed.contains("does not diagnose the earlier failure's cause"),
      failed,
    )
    assertFalse(failed.contains("Certification:"), failed)
    assertEquals("prior snapshot", prior.readText(), "validation destroyed the prior snapshot")
    assertTrue(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "validation of a later suite deleted the earlier suite report",
    )
    assertTrue(parsingDir.resolve("mutations.csv").isFile)

    assertTrue(parsingDir.resolve("mutations.csv").delete())
    val markerOnly = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail().output
    assertTrue(markerOnly.contains("'parsing' report was left by an interrupted or failed run"), markerOnly)
    assertTrue(markerOnly.contains("run :pitestModeSnapshot with the same -PpitestMode label"), markerOnly)
    assertEquals("prior snapshot", prior.readText(), "marker-only failure replaced the prior snapshot")

    parsingDir.resolve(".running").delete()
    parsingDir.resolve("mutations.csv").writeText(
      row.replace("Codec", "Parser").replace(",KILLED,", ",RUN_ERROR,") + "\n"
    )
    val invalid = runner("pitestModeSnapshot", "-PpitestMode=solo").buildAndFail().output
    assertTrue(
      invalid.contains("pitestModeSnapshot: 'parsing' report is invalid for mode 'solo'") &&
          invalid.contains("RUN_ERROR x1") &&
          invalid.contains("run :pitestModeSnapshot with the same -PpitestMode label") &&
          invalid.contains("history-free, full unscoped run") &&
          invalid.contains("Continued invalid outcomes warrant investigation") &&
          invalid.contains("sufficient closure") &&
          invalid.contains("creates no persistent mutation-record debt"),
      invalid,
    )
    assertFalse(invalid.contains("hardeningCertify"), invalid)
    assertFalse(invalid.contains("incomplete generated report"), invalid)
    assertEquals("prior snapshot", prior.readText(), "invalid status replaced the prior snapshot")
    assertTrue(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "invalid later suite cleared the earlier suite report",
    )
  }

  @Test
  fun `mode insurance validates every suite before committing any baseline or stamp`() {
    writeFixture(
      extraSuites = """
        mutation.register("parsing") {
          targetClasses = listOf("com.example.Parser")
          targetTests = "com.example.*Test*"
        }
      """.trimIndent(),
    )
    fun mutant(source: String, className: String, method: String, line: Int, status: String) =
      "$source.java,com.example.$className," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "$method,$line,$status," +
          (if (status == "KILLED") "com.example.${className}Test" else "none")
    fun writeParsingReport(status: String) {
      val reportDir = File(fixtureDir, "build/reports/pitest/parsing").apply { mkdirs() }
      reportDir.resolve("mutations.csv").writeText(
        mutant("Parser", "Parser", "parse", 20, status) + "\n",
      )
      reportDir.resolve("mutations.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n</mutations>\n",
      )
    }

    writeReport(listOf(mutant("Codec", "Codec", "encode", 12, "KILLED")), "")
    writeParsingReport("KILLED")
    modeSnapshot("solo")
    writeReport(listOf(mutant("Codec", "Codec", "encode", 12, "SURVIVED")), "")
    writeParsingReport("SURVIVED")
    modeSnapshot("gate")

    val encodingBaseline = baselineFile()
    val encodingStamp = File(fixtureDir, "config/pitest/encoding-pitest-version")
    assertFalse(encodingBaseline.exists())
    assertFalse(encodingStamp.exists())
    val parsingBaseline = File(fixtureDir, "config/pitest/parsing-accepted.csv").apply {
      parentFile.mkdirs()
      writeText("com.example.Parser,parse\n")
    }
    val parsingBefore = parsingBaseline.readText()

    val failed = runner("pitestModeCompareUnion").buildAndFail().output

    assertTrue(
      failed.contains("legacy 'parsing' snapshot") &&
          failed.contains("without completed-run provenance"),
      failed,
    )
    assertFalse(
      encodingBaseline.exists(),
      "a later suite failure committed the earlier suite's prepared baseline:\n$failed",
    )
    assertFalse(
      encodingStamp.exists(),
      "a later suite failure committed the earlier suite's prepared PIT-version stamp:\n$failed",
    )
    assertEquals(parsingBefore, parsingBaseline.readText())
  }

  @Test
  fun `mode insurance completion refuses a skipped compare task`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named("pitestModeCompare") {
          val skipModeCompare = providers.gradleProperty("skipModeCompare").isPresent
          onlyIf { !skipModeCompare }
        }
      """.trimIndent() + "\n",
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")
    val baseline = baselineFile()
    val stamp = File(fixtureDir, "config/pitest/encoding-pitest-version")

    val failed = runner(
      "pitestModeCompareUnion",
      "-PskipModeCompare",
    ).buildAndFail().output

    assertTrue(failed.contains("comparison task did not complete"), failed)
    assertFalse(baseline.exists(), "a skipped compare wrote its prepared baseline")
    assertFalse(stamp.exists(), "a skipped compare wrote its PIT-version stamp")
  }

  @Test
  fun `mode insurance completion refuses a skipped final validator`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named("pitestModeCompareCommit") {
          val skipModeCommit = providers.gradleProperty("skipModeCommit").isPresent
          onlyIf { !skipModeCommit }
        }
      """.trimIndent() + "\n",
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")
    val baseline = baselineFile()
    val version = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")

    val failed = runner(
      "pitestModeCompareUnion",
      "-PskipModeCommit",
    ).buildAndFail().output

    assertTrue(failed.contains("not consumed"), failed)
    assertFalse(baseline.exists(), "a skipped final validator wrote the baseline")
    assertFalse(version.exists(), "a skipped final validator wrote the PIT stamp")
    assertFalse(toolchain.exists(), "a skipped final validator wrote the toolchain stamp")
  }

  @Test
  fun `mode insurance refuses snapshots from an older checkout before writing`() {
    writeFixture()
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    File(fixtureDir, "src/main/java/com/example/Codec.java")
      .appendText("\n// checkout moved after the observations\n")
    val baseline = baselineFile()
    val version = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")

    val failed = runner("pitestModeCompareUnion").buildAndFail().output

    assertTrue(
      failed.contains("no longer matches the current checkout") && failed.contains("sourceSha256"),
      failed,
    )
    assertFalse(baseline.exists(), "stale snapshots wrote a baseline")
    assertFalse(version.exists(), "stale snapshots wrote a PIT-version stamp")
    assertFalse(toolchain.exists(), "stale snapshots wrote a toolchain stamp")
  }

  @Test
  fun `mode insurance revalidates after comparison immediately before commit`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        val codecForModeTamper = layout.projectDirectory.file("src/main/java/com/example/Codec.java")
        val tamperAfterModeCompare = tasks.register("tamperAfterModeCompare") {
          dependsOn("pitestModeCompare")
          doLast {
            codecForModeTamper.asFile.appendText("\n// changed after comparison\n")
          }
        }
        tasks.named("pitestModeCompareCommit") {
          dependsOn(tamperAfterModeCompare)
        }
      """.trimIndent() + "\n",
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val failed = runner("pitestModeCompareUnion").buildAndFail().output

    assertTrue(failed.contains(":pitestModeCompare"), failed)
    assertTrue(failed.contains(":tamperAfterModeCompare"), failed)
    assertTrue(failed.contains(":pitestModeCompareCommit FAILED"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertFalse(baselineFile().exists(), "final-input drift committed mode insurance")
  }

  @Test
  fun `mode insurance binds the exact snapshot bytes that produced its prepared write`() {
    writeFixture()
    val buildFile = File(fixtureDir, "build.gradle.kts")
    buildFile.writeText("import java.security.MessageDigest\n" + buildFile.readText())
    buildFile.appendText(
      """

        val gateCsvForModeReplacement =
          layout.buildDirectory.file("pitest-modes/gate/encoding.csv")
        val gateEvidenceForModeReplacement =
          layout.buildDirectory.file("pitest-modes/gate/encoding.evidence.tsv")
        val replaceModeSnapshotAfterCompare = tasks.register("replaceModeSnapshotAfterCompare") {
          dependsOn("pitestModeCompare")
          doLast {
            val csv = gateCsvForModeReplacement.get().asFile
            csv.writeText(csv.readText().replace(",SURVIVED,", ",KILLED,"))
            val sha = MessageDigest.getInstance("SHA-256").digest(csv.readBytes())
              .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val evidence = gateEvidenceForModeReplacement.get().asFile
            evidence.writeText(
              evidence.readLines().joinToString("\n", postfix = "\n") { line ->
                if (line.startsWith("reportSha256\t")) "reportSha256\t" + sha else line
              }
            )
          }
        }
        tasks.named("pitestModeCompareCommit") {
          dependsOn(replaceModeSnapshotAfterCompare)
        }
      """.trimIndent() + "\n",
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val failed = runner("pitestModeCompareUnion").buildAndFail().output

    assertTrue(failed.contains("inputs changed after comparison"), failed)
    assertTrue(failed.contains("encoding.csv"), failed)
    assertFalse(baselineFile().exists(), "replacement snapshot committed stale mode insurance")
  }

  @Test
  fun `mode insurance refuses a committed-record preimage changed after comparison`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        val baselineForModeTamper =
          layout.projectDirectory.file("config/pitest/encoding-accepted.csv")
        val tamperModeRecordAfterCompare = tasks.register("tamperModeRecordAfterCompare") {
          dependsOn("pitestModeCompare")
          doLast {
            baselineForModeTamper.asFile.apply {
              parentFile.mkdirs()
              writeText("!sava-hardening-baseline-schema,1\n" +
                "com.example.Concurrent,changed,MathMutator,SURVIVED # line 7\n")
            }
          }
        }
        tasks.named("pitestModeCompareCommit") {
          dependsOn(tamperModeRecordAfterCompare)
        }
      """.trimIndent() + "\n",
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val failed = runner("pitestModeCompareUnion").buildAndFail().output

    assertTrue(failed.contains("inputs changed after comparison"), failed)
    assertTrue(failed.contains("encoding-accepted.csv"), failed)
    assertTrue(
      baselineFile().readText().contains("com.example.Concurrent,changed"),
      "final commit overwrote the concurrent baseline preimage",
    )
  }
}
