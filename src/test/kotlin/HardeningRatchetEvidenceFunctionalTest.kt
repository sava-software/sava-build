import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PrunePreviewState
import java.io.File
import java.nio.file.Files

/** Report validity, exclusion audits, and committed mutation provenance. */
class HardeningRatchetEvidenceFunctionalTest : HardeningRatchetFunctionalFixture() {

  @Test
  fun `a scoped report is read-only evidence`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,12,MathMutator,SURVIVED\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.scoped").writeText("com.example.Codec\n")

    // the ratchet is skipped: an in-scope survivor is listed, not failed
    val output = runner("pitestEncodingVerify").build().output
    assertTrue(output.contains("SCOPED run"), "scoped notice missing:\n$output")
    assertTrue(output.contains("1 unkilled in scope"), "scoped listing missing:\n$output")

    // The summary is printed before the scoped ratchet short-circuit. Keep the
    // caveat on the count itself, so it cannot be mistaken for the suite-wide
    // timeout membership just because the later SCOPED notice scrolled away.
    val scopedReport = File(fixtureDir, "build/reports/pitest-scoped/encoding")
    scopedReport.mkdirs()
    scopedReport.resolve("mutations.csv").writeText(
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,TIMED_OUT,none\n",
    )
    scopedReport.resolve("mutations.xml").writeText("<mutations/>\n")
    scopedReport.resolve(".scoped").writeText("com.example.Codec\n")
    val timedScoped = runner(
      "pitestEncodingVerify", "-PmutateOnly=com.example.Codec",
    ).build().output
    assertTrue(
      timedScoped.contains(
        "1 timed out (scoped selected population; not comparable to the suite audit)"),
      "scoped timeout summary looked suite-wide:\n$timedScoped",
    )

    // the certifying flag is refused too: its checks are skipped entirely on a
    // scoped report, so a green run would certify nothing while reading as a
    // certification of the suite
    val refused = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      refused.contains("cannot be certified"),
      "scoped certification was not refused:\n$refused"
    )
  }

  @Test
  fun `an interrupted run's report is refused as evidence`() {
    // The '.running' sentinel is written before PIT starts and cleared only after
    // a clean exit. PIT writes the CSV incrementally, so a crashed or interrupted
    // run leaves a partial file that looks complete — and the verify runs as the
    // failed task's finalizer, so without the sentinel its writer implementation
    // could consume whatever fraction of the population PIT reached before dying.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore =
      "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n" +
          "com.example.Codec,decode,IncrementsMutator,SURVIVED # line 40\n"
    baselineFile().writeText(baselineBefore)
    // the partial report: only one of the two accepted mutants was reached
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.running").writeText("")

    val refused = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(refused.contains("interrupted or failed run"), refused)
    assertTrue(
      refused.contains("Evidence: a partial population is not evidence") &&
          refused.contains("Retry: run :pitestEncoding in a new Gradle invocation") &&
          refused.contains("Closure for a failed attempt: after resolving the preceding failure") &&
          refused.contains("successful, clean rerun of the intended workflow") &&
          refused.contains("replaces the incomplete generated report") &&
          refused.contains("creates no persistent mutation-record debt") &&
          refused.contains("rerun does not diagnose the earlier failure's cause"),
      refused,
    )
    assertFalse(
      refused.contains("Certification:"),
      "an ordinary verify run was incorrectly presented as an active certification:\n$refused",
    )

    assertEquals(baselineBefore, baselineFile().readText(), "a partial report pruned the baseline")
  }

  @Test
  fun `a missing report names invalid-outcome closure and retained diagnostics`() {
    writeFixture()

    val taskHelp = runner("help", "--task", "pitestEncodingVerify").build().output
    assertTrue(
      taskHelp.contains("Checks the 'encoding' PIT report against its ratchet") &&
          taskHelp.contains("scoped reports remain read-only diagnostics") &&
          !taskHelp.contains("Fails when the 'encoding' PIT run"),
      taskHelp,
    )

    val refused = runner("pitestEncodingVerify").buildAndFail().output

    assertTrue(refused.contains("pitest 'encoding': no PIT report at"), refused)
    assertTrue(
      refused.contains("Cause: :pitestEncoding either has not run or failed before writing one") &&
          refused.contains("failed execution such as MINION_DIED") &&
          refused.contains("Retry: run :pitestEncoding in a new Gradle invocation") &&
          refused.contains("Closure for a failed attempt") &&
          refused.contains("successful, clean rerun of the intended workflow") &&
          refused.contains("replaces the incomplete generated report") &&
          refused.contains("creates no persistent mutation-record debt") &&
          refused.contains("successful rerun does not diagnose the earlier failure's cause") &&
          refused.contains("Diagnostics:") &&
          refused.contains("~/.gradle/daemon/<version>/daemon-<pid>.out.log"),
      refused,
    )
  }

  @Test
  fun `invalid statuses and malformed report rows fail before a baseline writer`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore = "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n"
    baselineFile().writeText(baselineBefore)
    bindLegacyFixtureRecord()
    val versionBefore = File(fixtureDir, "config/pitest/encoding-pitest-version").readText()
    val toolchainBefore = File(
      fixtureDir,
      "config/pitest/encoding-pitest-toolchain.tsv",
    ).readText()

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,RUN_ERROR,none"),
      ""
    )
    val invalid = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(invalid.contains("not valid completed evidence"), invalid)
    assertTrue(invalid.contains("RUN_ERROR x1"), invalid)
    assertTrue(
      invalid.contains("RUN_ERROR alone diagnoses neither load nor memory") &&
          invalid.contains("does not justify changing suite threads or heap") &&
          invalid.contains("repeat once on a quiet machine") &&
          invalid.contains("only when PIT's preceding output explicitly diagnoses"),
      invalid,
    )
    assertTrue(
      invalid.contains("non-recurring known runtime or unfinished outcome") &&
          invalid.contains("a later clean, history-free, full unscoped run") &&
          invalid.contains("is sufficient closure") &&
          invalid.contains("creates no persistent mutation-record debt") &&
          invalid.contains("does not diagnose the earlier failure's cause") &&
          invalid.contains("Continued invalid outcomes warrant investigation even when their " +
              "coordinates move"),
      invalid,
    )
    assertTrue(
      invalid.contains("in a new Gradle invocation, run :pitestEncoding -PnoMutationHistory") &&
          invalid.contains("without -PmutateOnly"),
      invalid,
    )
    assertFalse(invalid.contains("hardeningCertify") || invalid.contains("project-atomic"), invalid)
    assertTrue(
      invalid.contains(
        "line 1: Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,RUN_ERROR,none"
      ),
      "invalid-status failure did not retain the mutant coordinate:\n$invalid",
    )
    assertEquals(baselineBefore, baselineFile().readText(), "an error result pruned accepted debt")

    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(debt.contains("falling back to the committed baseline"), debt)
    assertTrue(debt.contains("RUN_ERROR x1"), debt)
    assertTrue(debt.contains("baseline (full report invalid)"), debt)
    assertTrue(debt.contains("1 survived"), debt)
    assertTrue(
      debt.contains("non-recurring known runtime or unfinished outcome") &&
          debt.contains("run :pitestEncoding -PnoMutationHistory without -PmutateOnly"),
      debt,
    )
    assertFalse(
      debt.contains("hardeningCertify") || debt.contains("project-atomic"),
      "read-only debt fallback recommended an unrelated certification workflow:\n$debt",
    )

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10"),
      ""
    )
    val malformed = baselineUpdateRunner().buildAndFail().output
    assertTrue(malformed.contains("1 malformed CSV row(s)"), malformed)
    assertTrue(malformed.contains("incomplete population is not evidence"), malformed)
    assertFalse(
      malformed.contains("mutation-record debt") || malformed.contains("project-atomic"),
      "a malformed CSV was misclassified as a retryable runtime outcome:\n$malformed",
    )
    assertEquals(baselineBefore, baselineFile().readText(), "a malformed report rewrote the baseline")
    assertEquals(
      versionBefore,
      File(fixtureDir, "config/pitest/encoding-pitest-version").readText(),
      "a refused report changed the PIT provenance",
    )
    assertEquals(
      toolchainBefore,
      File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv").readText(),
      "a refused report changed the mutation-toolchain provenance",
    )
  }

  @Test
  fun `a history-assisted report is tagged and read-only`() {
    // Reused results are not observation: the checking path may read them (the
    // '[history]' tag names the reuse from the report's own marker rather than
    // this invocation's configuration. Named writers run PIT afresh and are
    // covered separately.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore = "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n"
    baselineFile().writeText(baselineBefore)
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 10\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "`com.example.Codec.encode`: removing progress makes the production path non-terminating.\n",
    )
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val statusStash = File(fixtureDir, ".pitest-history/encoding.statuses").apply {
      parentFile.mkdirs()
      writeText(
        "# stash format 2\n" +
            "com.example.Codec,encode,MathMutator,TIMED_OUT\n",
      )
    }
    val quietStash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet").apply {
      writeText(
        "# timeout quiet format 3\n" +
            "# inputs ${"a".repeat(64)}\n" +
            "# invocation old-format-observation\n" +
          "com.example.Codec,encode,MathMutator,2\n",
      )
    }
    val pruneStash = File(fixtureDir, ".pitest-history/encoding.prune-previews").apply {
      writeText(
          PrunePreviewState(
              "a".repeat(64),
              "b".repeat(64),
              "prior-prune-observation",
              2,
              true,
              listOf("com.example.Codec,decode,MathMutator,SURVIVED # reviewed"),
          ).render(),
      )
    }
    val statusBefore = statusStash.readBytes()
    val quietBefore = quietStash.readBytes()
    val pruneBefore = pruneStash.readBytes()

    val checking = runner("pitestEncodingVerify").build().output
    assertTrue(checking.contains(" [history]"), "the summary tag must read the marker:\n$checking")
    assertTrue(
      checking.contains("history-assisted results are a read-only preview") &&
          checking.contains("pitestEncoding -PnoMutationHistory"),
      "the assisted report did not prohibit record decisions:\n$checking",
    )
    assertFalse(
      checking.contains("timed-out drift vs previous run") ||
          checking.contains("have not timed out in 3+") ||
          checking.contains("can be retired"),
      "assisted statuses still produced timeout-transition decision evidence:\n$checking",
    )

    assertEquals(baselineBefore, baselineFile().readText(), "checking reused evidence changed the baseline")
    assertTrue(statusBefore.contentEquals(statusStash.readBytes()), "assisted evidence replaced the status stash")
    assertTrue(quietBefore.contentEquals(quietStash.readBytes()), "assisted evidence advanced the quiet stash")
    assertTrue(pruneBefore.contentEquals(pruneStash.readBytes()), "assisted evidence advanced prune previews")

    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains("latest full [history] report (read-only preview)") &&
          debt.contains("pitestEncoding -PnoMutationHistory before any accepted-baseline or timeout-audit decision"),
      "Debt presented assisted statuses as current decision evidence:\n$debt",
    )

    val strict = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      strict.contains("strict timeout audit refuses a history-assisted report") &&
          strict.contains("pitestEncoding -PstrictTimeoutAudit") &&
          strict.contains("disables history automatically"),
      "strict audit accepted reused timeout statuses:\n$strict",
    )
    assertFalse(
      strict.contains("before changing any accepted-baseline or timeout-audit record"),
      "strict refusal printed a weaker non-strict remedy too:\n$strict",
    )
    assertTrue(statusBefore.contentEquals(statusStash.readBytes()), "strict audit replaced the status stash")
    assertTrue(quietBefore.contentEquals(quietStash.readBytes()), "strict audit advanced the quiet stash")
    assertTrue(pruneBefore.contentEquals(pruneStash.readBytes()), "strict audit advanced prune previews")

    // without the marker the same report is a full run: ordinary checking is allowed
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").delete()
    val legacyFull = runner("pitestEncodingVerify").build().output
    assertFalse(legacyFull.contains(" [history]"), "tag printed without the marker:\n$legacyFull")
    assertTrue(
      legacyFull.contains("status stash predates the current stash format"),
      "pre-fix status evidence was not reset:\n$legacyFull",
    )
    assertFalse(
      legacyFull.contains("timeout-retirement stash uses an older compatibility format"),
      "legacy evidence advanced or rewrote timeout-retirement state:\n$legacyFull",
    )
    assertTrue(quietBefore.contentEquals(quietStash.readBytes()), "legacy evidence rewrote the quiet stash")

    val full = runner("pitestEncoding").build().output
    assertTrue(
      full.contains("timeout-retirement stash uses an older compatibility format"),
      "older quiet evidence was not reset:\n$full",
    )
    assertTrue(statusStash.readText().startsWith("# stash format 3\n"), statusStash.readText())
    assertTrue(quietStash.readText().startsWith("# timeout quiet format 4\n"), quietStash.readText())
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,1"),
      "the first fresh report inherited two assisted quiet observations:\n${quietStash.readText()}",
    )
  }

  @Test
  fun `the exclusion audit reads partition handoffs as ownership, statically in Debt`() {
    // Two suites partition com.example.*: 'encoding' hands decoding.* to its
    // sibling, which is ownership, not a swallow — while a glob nothing else
    // targets ('Legacy*') is a genuine hole and must stay a finding. Exercised
    // through the Debt task because that is the quick read-only view of consumer
    // globs: the audit's in-run half only fires inside a real pitest execution
    // (casebook: the partition the
    // audit called a hole). Compile real production sources because
    // compileForPitest owns and may recreate build/mutation-classes before the
    // static Debt audit reads it; the audit itself reads names, never bytecode.
    writeFixture(
      encodingTargets = listOf("com.example.*"),
      encodingExcludes = listOf("com.example.decoding.*", "com.example.Legacy*"),
      extraSuites = """
          mutation.register("decoding") {
            targetClasses = listOf("com.example.decoding.*")
            targetTests = "com.example.*Test*"
          }
      """.trimIndent()
    )
    listOf("com.example.decoding.Decoder", "com.example.LegacyCodec")
        .forEach(::writeProductionClassSource)
    runner("compileForPitest").build()

    val output = runner("pitestEncodingDebt").build().output
    assertTrue(
      output.contains("com.example.LegacyCodec (glob 'com.example.Legacy*')"),
      "orphaned exclusion not reported:\n$output"
    )
    assertFalse(
      output.contains("com.example.decoding.Decoder"),
      "sibling-owned class reported as swallowed:\n$output"
    )

    // the sibling's own Debt sees no finding either: it mutates decoding.*, and
    // the class it does not target is not its problem
    val sibling = runner("pitestDecodingDebt").build().output
    assertFalse(
      sibling.contains("swallowed by excludedClasses"),
      "sibling suite reported a finding it does not own:\n$sibling"
    )
  }

  @Test
  fun `a declined exclusion argues its opt-out away, and the record keeps earning itself`() {
    // The third exclusion category the targeting policy endorses and the scan cannot
    // derive: generated bindings, vendored code, a live-credential main. Measured
    // across the fleet, leaving it underivable meant ~1600 advisory lines from one
    // suite's generated package every run — the corrosion the audit's own casebook
    // entry warns about, one category over. Exercised through Debt because that is
    // the read-only half a diagnostic sweep can execute.
    writeFixture(
      encodingTargets = listOf("com.example.*"),
      encodingExcludes = listOf("com.example.gen.*", "com.example.Legacy*"),
      extraSuites = """
          mutation.register("declining") {
            targetClasses = listOf("com.example.*")
            excludedClasses = listOf("com.example.gen.*", "com.example.Legacy*", "com.example.Codec")
            targetTests = "com.example.*Test*"
            declineExclusionAudit(
              "com.example.gen.*",
              "generated bindings; their generator's own suites carry them"
            )
            declineExclusionAudit("com.example.retired.*", "a glob that swallows nothing here")
          }
      """.trimIndent()
    )
    listOf("com.example.gen.Binding", "com.example.LegacyCodec")
        .forEach(::writeProductionClassSource)
    runner("compileForPitest").build()

    // the undeclining suite reports both; the declining one reports only the
    // orphan, and is told its unused record is deletable
    val undeclined = runner("pitestEncodingDebt").build().output
    assertTrue(undeclined.contains("com.example.gen.Binding"), undeclined)
    assertTrue(undeclined.contains("com.example.LegacyCodec"), undeclined)

    val declined = runner("pitestDecliningDebt").build().output
    assertFalse(
      declined.contains("com.example.gen.Binding"),
      "an argued decline must take its classes out of the report:\n$declined"
    )
    assertTrue(
      declined.contains("com.example.LegacyCodec"),
      "an undeclined glob must still be reported:\n$declined"
    )
    assertTrue(
      declined.contains("declineExclusionAudit record(s) match no swallowed") &&
          declined.contains("com.example.retired.*"),
      "the unused record must be named as deletable:\n$declined"
    )
    // the remedy list names the mechanism, so the advisory teaches its own escape
    assertTrue(declined.contains("declineExclusionAudit(\"<glob>\""), declined)
  }

  @Test
  fun `the external mutation toolchain is part of the record`() {
    // The population is a function of PIT, its plugins, and the ArcMutate licence.
    // Checking may inspect a fresh observation across that boundary, but ordinary
    // writers must refuse it; only the preservation-first Rebase transition may
    // replace both provenance sidecars after review.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,12,MathMutator,SURVIVED\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
      ),
      ""
    )
    val toolVersionFile = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchainFile = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")

    // A legacy record cannot be adopted destructively. The fixture helper performs
    // the same explicit Rebase an adopting consumer must run.
    val refreshed = baselineUpdateRunner().build()
    assertFalse(refreshed.output.contains("written by PIT"), refreshed.output)
    val stamped = toolVersionFile.readText().trim()
    assertTrue(stamped.isNotEmpty() && stamped.first().isDigit(), "stamped version looks wrong: '$stamped'")
    val toolchainBefore = toolchainFile.readText()

    // Matching provenance: checking stays quiet even though this fixture removes
    // report-local evidence after each synthetic writer.
    val clean = runner("pitestEncodingVerify").build()
    assertFalse(clean.output.contains("written by PIT"), clean.output)

    File(fixtureDir, "build.gradle.kts").appendText(
      "\nhardening.pitestVersion.set(\"0.0.0-new\")\n",
    )

    // A history-free ordinary observation is permitted and names both boundaries.
    val checked = runner("pitestEncoding", "-PnoMutationHistory").build()
    assertTrue(
      checked.output.contains("baseline record written by PIT $stamped, this run used PIT 0.0.0-new") &&
          checked.output.contains("mutation toolchain changed since the committed record"),
      "mismatch warning missing:\n" + checked.output
    )

    // An ordinary writer refuses without changing either sidecar.
    val refused = baselineUpdateRunner().buildAndFail()
    assertTrue(
      refused.output.contains("refusing to rewrite the record across a tool bump") &&
          refused.output.contains("pitestEncodingBaselineRebase"),
      "refusal missing or unactionable:\n" + refused.output
    )
    assertEquals(stamped, toolVersionFile.readText().trim(), "refused run changed the PIT stamp")
    assertEquals(toolchainBefore, toolchainFile.readText(), "refused run changed the toolchain stamp")

    val rebased = runner("pitestEncodingBaselineRebase").build().output
    assertTrue(rebased.contains("baseline provenance"), rebased)
    assertEquals("0.0.0-new", toolVersionFile.readText().trim())
    assertFalse(toolchainBefore == toolchainFile.readText(), "Rebase did not replace toolchain identity")
  }

  @Test
  fun `baseline rebase repairs torn and malformed provenance without dropping debt`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val original = "com.example.Codec,encode,MathMutator,SURVIVED # accepted # line 12\n"
    baselineFile().writeText(original)
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,SURVIVED,none",
      ),
      "",
    )
    baselineUpdateRunner().build()
    val version = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    assertTrue(version.isFile && toolchain.isFile)

    assertTrue(toolchain.delete())
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,TIMED_OUT,none",
      ),
      "",
    )
    val missingAudit = runner("pitestEncoding").buildAndFail().output
    val expectedMissingAuditProvenance =
      """
      pitest 'encoding': no audited set covers 1 physical TIMED_OUT mutant instance across 1 line-less key, and committed mutation provenance is invalid:
        Evidence: Triage-only draft rows follow, one per line-less key; nested '# observed' comments preserve line/status multiplicity.
          com.example.Codec,encode,MathMutator # cause:untriaged line 12
        Review: The population is not bound to valid committed provenance.
        Watchdog context: Configured watchdog (audited PIT 1.30.0): round(testDurationMs × 1.25) + 4000 ms. PIT CSV lacks the active covering test and its recorded duration, so no per-mutant budget can be calculated.
        Remedy: Retain these candidates, repair or rebase provenance, and obtain a fresh full observation. Do not seed, add, or classify them until that observation confirms them.
      """.trimIndent()
    assertTrue(
      missingAudit.contains(expectedMissingAuditProvenance),
      "torn provenance hid the missing-audit evidence or its safe remedy:\n$missingAudit",
    )
    val timeouts = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeouts.writeText(
      "com.example.Codec,decode,IncrementsMutator # cause:untriaged line 30\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.decode\n\n`Codec.decode`: the reversed cursor never reaches its exit.\n",
    )
    // A fresh completed report remains valid evidence about its own timeout
    // population even though the independent committed provenance pair is torn.
    // Print that reviewer-stop, then retain the provenance refusal.
    val torn = runner("pitestEncoding").buildAndFail().output
    assertTrue(torn.contains("committed mutation provenance is torn"), torn)
    assertTrue(
      torn.contains(
        "current full report contains 1 physical TIMED_OUT mutant instance across " +
            "1 line-less key outside",
      ) &&
          torn.contains("com.example.Codec,encode,MathMutator # cause:untriaged line 12") &&
          !torn.contains("# observed: line 12 TIMED_OUT x1") &&
          torn.contains("no per-mutant budget can be calculated") &&
          torn.contains("Remedy: Retain these candidates, repair or rebase provenance") &&
          torn.contains("Do not add or classify them until that observation confirms them") &&
          torn.contains("cause:untriaged has not been reviewed") &&
          torn.contains("do not retire or rewrite them until provenance is repaired/rebased") &&
          torn.contains("then remove the membership line by hand"),
      "torn provenance hid the fresh report's unaudited timeout:\n$torn",
    )
    assertFalse(torn.contains("add the row below"), torn)
    assertTrue(torn.contains("complete record written by a pre-sidecar release"), torn)
    assertTrue(torn.contains("interrupted newer write"), torn)
    assertFalse(torn.contains("failed/incomplete write"), torn)

    // The reserved-value collision this used to probe with -PmutateOnly=full is now
    // refused where the scope is normalized, rather than defended one consumer at a
    // time: `scope` is the only field carrying mutateOnly into the evidence, so a run
    // narrowed to a glob spelled "full" recorded itself as a full-population run and
    // lost the out-of-band .scoped marker with it.
    val reserved = runner("pitestEncoding", "-PmutateOnly=full").buildAndFail().output
    assertTrue(
      reserved.contains("that is the scope a full-population run records"),
      "a scoped run spelled as the sentinel was accepted:\n$reserved",
    )

    val scopedTorn = runner(
      "pitestEncoding",
      // Still a genuinely scoped run: partial evidence must not satisfy the
      // suite-wide timeout audit, whatever the glob is spelled.
      "-PmutateOnly=com.example.Codec",
    ).buildAndFail().output
    assertTrue(
      scopedTorn.contains("report-dependent timeout membership findings were not evaluated") &&
          scopedTorn.contains("Evidence: This is a scoped mutation report.") &&
          scopedTorn.contains(
            "Remedy: Repair or rebase provenance, then run :pitestEncoding " +
                "without -PmutateOnly.",
          ),
      "scoped torn provenance did not explain the unavailable report audit:\n$scopedTorn",
    )
    assertFalse(
      scopedTorn.contains("physical TIMED_OUT mutant instance") ||
          scopedTorn.contains("current full report contains") ||
          scopedTorn.contains("audited-timeout row matches no mutant"),
      "a scoped report produced suite-wide timeout membership findings:\n$scopedTorn",
    )

    // The static committed-file half is even cheaper and has no dependence on the
    // report or provenance. Debt must print it before the same fatal sidecar check.
    timeouts.writeText(
      "com.example.Codec,encode,MathMutator # cause:untriaged line 12\n",
    )
    val tornDebt = runner("pitestEncodingDebt").buildAndFail().output
    assertTrue(tornDebt.contains("committed mutation provenance is torn"), tornDebt)
    assertTrue(
      tornDebt.contains("cause:untriaged has not been reviewed"),
      "torn provenance hid committed timeout classification debt:\n$tornDebt",
    )

    timeouts.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: the removed exit cannot make progress.\n",
    )
    val repairedTorn = runner("pitestEncodingBaselineRebase").build().output
    assertTrue(repairedTorn.contains("will repair the pair"), repairedTorn)
    assertTrue(version.isFile && toolchain.isFile)
    assertEquals(original, baselineFile().readText(), "repair changed accepted debt")

    toolchain.writeText("not a toolchain record\n")
    val malformed = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(malformed.contains("malformed committed mutation-toolchain record"), malformed)
    val malformedDebt = runner("pitestEncodingDebt").buildAndFail().output
    assertTrue(malformedDebt.contains("malformed committed mutation-toolchain record"), malformedDebt)
    val repairedMalformed = runner("pitestEncodingBaselineRebase").build().output
    assertTrue(repairedMalformed.contains("will replace malformed"), repairedMalformed)
    assertTrue(toolchain.readText().startsWith("schema\t1\n"))
    assertEquals(original, baselineFile().readText(), "malformed-provenance repair changed accepted debt")
  }

  @Test
  fun `debt surfaces a committed mutation toolchain that differs from the current one`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # accepted # line 12\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,SURVIVED,none",
      ),
      "",
    )
    baselineUpdateRunner().build()
    val toolchain = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    toolchain.writeText(
      toolchain.readText().replace(
          Regex("(?m)^toolClasspathSha256\\t[0-9a-f]{64}$"),
          "toolClasspathSha256\t" + "0".repeat(64),
      ),
    )

    val debt = runner("pitestEncodingDebt").build().output

    assertTrue(debt.contains("committed mutation toolchain differs from the current"), debt)
  }

  @Test
  fun `committed record readers refuse a symlinked config tree`() {
    writeFixture()
    val external = fixtureDir.resolve("external-config/pitest").apply { mkdirs() }
    val accepted = external.resolve("encoding-accepted.csv").apply {
      writeText("com.example.Codec,encode,MathMutator,SURVIVED # accepted # line 12\n")
    }
    Files.createSymbolicLink(
        fixtureDir.resolve("config").toPath(), external.parentFile.toPath())
    val before = accepted.readText()

    val debt = runner("pitestEncodingDebt").buildAndFail().output

    assertTrue(debt.contains("symbolic-link component"), debt)
    assertEquals(before, accepted.readText(), "a linked record outside the checkout was changed")
  }

  @Test
  fun `mutation provenance lands only with a successful timeout record write`() {
    // A timeout set is a population-dependent committed mutation record too. Its
    // provenance must land atomically with a successful seed, never when a later
    // ratchet check refuses the staged transition.
    writeFixture()
    val toolVersionFile = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchainFile = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")

    // The task can stage a real timeout and still fail later on fresh accepted debt.
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,50,SURVIVED,none",
      ),
      ""
    )
    timeoutAuditInitRunner().buildAndFail()
    assertFalse(timeoutsFile.isFile, "failed initialization committed its staged timeout set")
    assertFalse(toolVersionFile.isFile, "failed initialization stamped the PIT version")
    assertFalse(toolchainFile.isFile, "failed initialization stamped the toolchain")

    // A successful timeout-only record binds both provenance sidecars.
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )
    val seeded = timeoutAuditInitRunner().build().output
    assertTrue(seeded.contains("seeded 1 audited-timeout member(s)"), "seed did not run:\n$seeded")
    assertTrue(timeoutsFile.isFile, "successful initialization wrote no timeout record")
    assertTrue(toolVersionFile.isFile, "timeout-only record has no PIT provenance")
    assertTrue(toolchainFile.isFile, "timeout-only record has no toolchain provenance")
  }
}
