import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** Timeout membership, cause metadata, drift, and retirement observations. */
class HardeningRatchetTimeoutFunctionalTest : HardeningRatchetFunctionalFixture() {

  @Test
  fun `the timed-out audited set warns on newcomers and notices stale members`() {
    // TIMED_OUT is detected, but the watchdog observed slowness, not wrongness: the
    // ratchet cannot see a weakened covering assertion behind a timeout, so the
    // summary's load-dependent count is only trustworthy as an audited membership.
    // A timed-out mutant missing from <suite>-timeouts.csv is a reviewer-stop
    // warning; a member matching no mutant at all is retirement hygiene. Both are
    // advisory — neither may fail the build, since load can time out any mutant.
    writeFixture(
      extraSuites = """
        mutation.named("encoding") {
          timeoutFactor = 2.0
          timeoutConst = 1500L
        }
      """.trimIndent(),
    )
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "# structural causes live in config/pitest/README.md\n" +
          "com.example.Codec,encode,MathMutator # cause:liveness removed loop exit line 12\n" +
          "com.example.Codec,gone,MathMutator # cause:liveness line 99\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,50,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,40,KILLED,com.example.CodecTest",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    // the printed row is the membership key verbatim with the line in a '#' comment
    val printedRow = "com.example.Codec,encode,IncrementsMutator # cause:untriaged lines 30, 50"
    assertTrue(
      output.contains(
        "3 physical TIMED_OUT mutant instances across 1 line-less key not in the audited set " +
            "(encoding-timeouts.csv)",
      ) &&
          output.contains(printedRow) &&
          output.contains("# observed: line 30 TIMED_OUT x2") &&
          output.contains("# observed: line 50 TIMED_OUT x1") &&
          output.contains("paste-ready, fail-closed draft") &&
          output.contains("Replace each deliberate cause:untriaged placeholder") &&
          output.contains("placeholder is non-certifying") &&
          output.contains("only cause:liveness may remain") &&
          output.contains("round(testDurationMs × 2.0) + 1500 ms") &&
          output.contains("no per-mutant budget can be calculated"),
      "unaudited timeout not warned paste-ready:\n$output"
    )
    assertFalse(
      output.contains("MathMutator # line 12"),
      "audited member wrongly listed:\n$output"
    )
    assertTrue(
      output.contains("1 audited-timeout row matches no mutant") &&
          output.contains("com.example.Codec,gone,MathMutator"),
      "stale member not noticed:\n$output"
    )

    // the paste round trip: the printed row, added verbatim, must satisfy the check —
    // and must not trip the stale-member notice
    timeoutsFile.appendText("$printedRow\n")
    val pasted = runner("pitestEncodingVerify").build().output
    assertFalse(
      pasted.contains("not in the audited set"),
      "pasted printed row did not satisfy the audit:\n$pasted"
    )
    // The pasted member is live, not stale, but its seeded category remains an
    // explicit reviewer-stop until a person classifies the cause.
    assertTrue(
      pasted.contains("cause:untriaged has not been reviewed") &&
          pasted.contains("1 audited-timeout row matches no mutant"),
      "pasted printed row lost its classification/staleness distinction:\n$pasted"
    )

    // a member being told to retire is not simultaneously asked for its cause: the
    // two instructions pull opposite ways for one row
    assertFalse(
      pasted.contains("cause? com.example.Codec,gone,MathMutator"),
      "stale member also nagged to document itself:\n$pasted"
    )

    // fields spaced for readability are the same membership: normalizing per field
    // (not just per line) keeps the tool from silently disagreeing with a reasonable
    // hand edit — the alternative is a permanent 'not in the audited set' warning
    // plus a 'matches no mutant' notice, with nothing naming the spaces as the cause
    timeoutsFile.writeText(
      "com.example.Codec, encode, MathMutator  # removed loop exit\n" +
          "com.example.Codec , encode , IncrementsMutator\n"
    )
    val spaced = runner("pitestEncodingVerify").build().output
    assertFalse(
      spaced.contains("not in the audited set"),
      "spaced membership rows not honoured:\n$spaced"
    )
    assertFalse(
      spaced.contains("match no mutant"),
      "spaced membership rows read as stale:\n$spaced"
    )

    // without the file the audit itself is off — but a suite carrying timeouts is
    // running with the exact blind spot the audit exists for, so the absence is
    // nudged (advisory, naming the seed task) rather than silent: the feature used
    // to be discoverable only by reading HARDENING.md
    timeoutsFile.delete()
    val unadopted = runner("pitestEncodingVerify").build().output
    assertFalse(
      unadopted.contains("not in the audited set"),
      "audit warning without a membership file:\n$unadopted"
    )
    val missingAuditEvidence =
      """
      pitest 'encoding': no audited set covers 4 physical TIMED_OUT mutant instances across 2 line-less keys:
        Evidence: One paste-ready draft row per line-less key follows; nested '# observed' comments preserve line/status multiplicity.
          com.example.Codec,encode,IncrementsMutator # cause:untriaged lines 30, 50
            # observed: line 30 TIMED_OUT x2
            # observed: line 50 TIMED_OUT x1
          com.example.Codec,encode,MathMutator # cause:untriaged line 12
        Review: A timeout detects slowness, not wrongness, so the ratchet cannot see a weakened covering assertion behind one. Timeout observations may not reproduce on a later seeding run.
      """.trimIndent()
    assertTrue(
      unadopted.contains(missingAuditEvidence),
      "adoption hint missing:\n$unadopted"
    )
    val missingAuditAction =
      "  Watchdog context: Configured watchdog (audited PIT 1.30.0): " +
          "round(testDurationMs × 2.0) + 1500 ms. PIT CSV lacks the active covering test and " +
          "its recorded duration, so no per-mutant budget can be calculated.\n" +
          "  Remedy: Run :pitestEncodingTimeoutAuditInit (seeds " +
          "config/pitest/encoding-timeouts.csv from this run), or paste the draft rows above. " +
          "Then replace cause:untriaged and write each member's structural argument in " +
          "config/pitest/README.md. The seeded state is intentionally uncertifiable."
    assertTrue(
      unadopted.contains(missingAuditAction),
      "adoption hint review and remedy are not ordered or line-separated:\n$unadopted"
    )

    // the paste round trip: the nudged rows, written as the membership file, must arm
    // the audit — no adoption nudge, no unaudited-newcomer warning, no stale notice
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:untriaged line 12\n" +
          "com.example.Codec,encode,IncrementsMutator # cause:untriaged lines 30, 50\n"
    )
    val adopted = runner("pitestEncodingVerify").build().output
    assertFalse(
      adopted.contains("no audited set") || adopted.contains("not in the audited set") ||
          adopted.contains("match no mutant"),
      "pasted nudge rows did not arm the audit cleanly:\n$adopted"
    )
  }

  @Test
  fun `a stale baseline row that timed out this run is not killed-or-moved`() {
    // A baseline SURVIVED row whose key has a TIMED_OUT copy is protected from
    // deletion, but that arithmetic proves neither benign load nor physical mutant
    // identity. The refresh hint must count only rows that are genuinely gone.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED\n" +
          "com.example.Codec,decode,40,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,40,KILLED,com.example.CodecTest",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("1 row / 1 unique key is unmatched by this run") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # line 40"),
      "unmatched row not previewed exactly:\n$output"
    )
    assertTrue(
      output.contains("1 baseline row(s) protected by same-key timeout budget") &&
          output.contains("This does not identify which physical sibling timed out") &&
          output.contains("or prove benign load or that the acceptance argument still holds") &&
          output.contains("com.example.Codec,encode,MathMutator,SURVIVED"),
      "timed-out flip not reported separately:\n$output"
    )
  }

  @Test
  fun `an unaudited timeout sharing an accepted key is correlated without claiming identity`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # allocation argument # line 12\n" +
          // Syntactically valid but not an accepted-debt status: the correlation
          // must not call this an accepted SURVIVED/NO_COVERAGE row.
          "com.example.Codec,encode,MathMutator,KILLED # stale hand edit # line 30\n",
    )
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "# Armed audited set; this key is deliberately not a member.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,TIMED_OUT,none",
      ),
      "",
    )

    val output = runner("pitestEncodingVerify").build().output
    val overlapClaim =
        "1 unaudited timeout key also has 1 accepted SURVIVED/NO_COVERAGE baseline row"
    val overlapAt = output.indexOf(overlapClaim)
    val overlapEvidenceAt = output.indexOf("  Evidence:", overlapAt)
    val overlapReviewAt = output.indexOf("  Review:", overlapEvidenceAt)
    val overlapRemedyAt = output.indexOf("  Remedy:", overlapReviewAt)
    assertTrue(
          output.contains("2 timed out (watchdog detection; not a cause diagnosis)") &&
          overlapAt >= 0 &&
          output.contains(
            "2 physical TIMED_OUT mutant instances across 1 line-less key remain " +
                "unaudited, 1 overlapping line-less key with 1 accepted baseline row",
          ) &&
          overlapAt < overlapEvidenceAt && overlapEvidenceAt < overlapReviewAt &&
          overlapReviewAt < overlapRemedyAt &&
          output.contains("accepted: com.example.Codec,encode,MathMutator,SURVIVED") &&
          output.contains("timeout candidate: com.example.Codec,encode,12,MathMutator") &&
          output.contains("timeout candidate: com.example.Codec,encode,20,MathMutator") &&
          output.contains("cannot prove whether a timeout is the accepted mutant or a sibling"),
      "accepted/timeout overlap was not correlated:\n$output",
    )
    assertFalse(
      output.contains("accepted: com.example.Codec,encode,MathMutator,KILLED"),
      "the correlation presented a non-gated baseline row as accepted debt:\n$output",
    )
    assertFalse(
      output.contains("cause still requires audit") ||
          output.contains("the same mutant timed out") || output.contains("proves resource"),
      "line-less evidence was presented as physical identity or cause:\n$output",
    )
    val strict = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertFalse(
      strict.contains(
        "2 physical TIMED_OUT mutant instances across 1 line-less key remain " +
            "unaudited, 1 overlapping line-less key with 1 accepted baseline row",
      ),
      "strict-escalated overlap was also recorded as a non-failing advisory:\n$strict",
    )
  }

  @Test
  fun `an audited-timeout member without a README cause gets a notice`() {
    // The CSV row makes the set machine-checked; the README argument is what a
    // reviewer actually reads. Same advisory level and same soft resolution as the
    // family-label rule — matched by the simple class name and the method name both
    // appearing in the README, so it only catches a cause that was never written.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )
    val readme = File(fixtureDir, "config/pitest/README.md")
    readme.writeText("# Baseline\n\nNothing about timeouts yet.\n")

    val warned = runner("pitestEncodingVerify").build().output
    assertTrue(
      warned.contains("missing README cause for 1 audited-timeout member") &&
          warned.contains("cause? com.example.Codec,encode,MathMutator"),
      "unwritten cause not noticed:\n$warned"
    )

    readme.writeText("# Baseline\n\n`Codec.encode` (MathMutator): the inflated estimate crawls, never fails.\n")
    val documented = runner("pitestEncodingVerify").build().output
    assertFalse(
      documented.contains("missing README cause for"),
      "documented cause still noticed:\n$documented"
    )
  }

  @Test
  fun `finite harness findings never become liveness retirement nominations`() {
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:harness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: a finite covering fixture races PIT's watchdog.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,KILLED,com.example.CodecTest",
      ),
      "",
    )

    repeat(3) {
      val output = runner("pitestEncoding").build().output
      assertTrue(output.contains("cause:harness is a finite covering-path/watchdog race"), output)
      assertFalse(
        output.contains("audited-timeout member has not timed out in 3+"),
        "non-certifying harness debt became a retirement nomination:\n$output",
      )
    }
    val stash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet").readText()
    assertFalse(stash.contains("com.example.Codec,encode,MathMutator"), stash)
  }

  @Test
  fun `invalid liveness metadata remains advisory and does not alter retirement`() {
    writeFixture()
    val member = "com.example.Codec,encode,MathMutator"
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("$member # cause:liveness lines 10-30\n")
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,KILLED,com.example.CodecTest",
      ),
      "",
    )

    val quietNotice = "audited-timeout member has not timed out in 3+"
    repeat(3) { index ->
      val output = runner("pitestEncoding").build().output
      assertTrue(output.contains("invalid line metadata 'lines 10-30'"), output)
      assertTrue(
        output.contains("1 audited timeout(s) with invalid line metadata"),
        "invalid timeout metadata was not retained in the advisory summary:\n$output",
      )
      if (index < 2) {
        assertFalse(output.contains(quietNotice), "quiet notice fired before run 3:\n$output")
      } else {
        assertTrue(output.contains(quietNotice), "metadata reset the quiet streak:\n$output")
        assertTrue(output.contains("1 quiet audited-timeout member"), output)
      }
    }
    val stash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet").readText()
    assertTrue(stash.contains("$member,3"), stash)

    val strictDebt = runner("pitestEncodingDebt", "-PstrictTimeoutAudit").build().output
    assertTrue(
      strictDebt.contains("invalid optional line metadata") &&
          strictDebt.contains("-PstrictTimeoutAudit committed-file preview is clean"),
      strictDebt,
    )
    val strict = runner("pitestEncoding", "-PstrictTimeoutAudit").build().output
    assertTrue(strict.contains("invalid optional line metadata"), strict)
    assertEquals(
      1,
      strict.lineSequence().count {
        it.contains(
          "invalid optional line metadata in encoding-timeouts.csv — 1 audited-timeout member"
        )
      },
      "strict execution printed the advisory more than once:\n$strict",
    )
    assertTrue(strict.contains(":pitestEncoding"), strict)
    assertFalse(strict.contains("inadmissible cause classification"), strict)
  }

  @Test
  fun `the cause check requires the class name next to the method name`() {
    // Method-only matching was trivially satisfied: most dispatch members are named
    // 'handle', which appears in any README that mentions handlers at all — prose
    // about a different class entirely passed as this member's cause.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 10\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )
    val readme = File(fixtureDir, "config/pitest/README.md")
    readme.writeText("# Baseline\n\nThe encode path of some other class crawls under this mutation.\n")

    val methodOnly = runner("pitestEncodingVerify").build().output
    assertTrue(
      methodOnly.contains("cause? com.example.Codec,encode,MathMutator"),
      "method-name-only prose passed as a cause:\n$methodOnly"
    )

    readme.writeText("# Baseline\n\n`Codec.encode` (MathMutator): the inflated estimate crawls, never fails.\n")
    val bothNames = runner("pitestEncodingVerify").build().output
    assertFalse(
      bothNames.contains("cause? com.example.Codec"),
      "class-and-method cause still noticed:\n$bothNames"
    )
  }

  @Test
  fun `timeout line metadata never turns source movement into a finding`() {
    // PIT exposes source positions but no formatting-stable per-mutant identity.
    // A line tag is therefore diagnostic only: imports, an inserted method, or a
    // reflow inside the method must not fail or warn in an otherwise valid audit.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator # cause:liveness line 12\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the mutated cursor cannot advance.\n")
    fun report(vararg lines: Int) = writeReport(
      lines.map {
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$it,TIMED_OUT,none"
      },
      ""
    )

    report(99)
    val moved = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertFalse(
      moved.contains("line-drifted") || moved.contains("unreviewed line") ||
          moved.contains("comment does not name"),
      "source movement became a timeout-audit finding:\n$moved"
    )

    // A same-key sibling is the known limitation of the line-less format. Its line
    // cannot be promoted into identity without making formatting a release gate.
    report(12, 99)
    val sibling = runner("pitestEncodingVerify").build().output
    assertFalse(
      sibling.contains("line-drifted") || sibling.contains("comment does not name"),
      "sibling line next to the recorded anchor read as moved-anchor drift:\n$sibling"
    )

    // A line-less liveness row is equally admissible.
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator # cause:liveness removed loop exit\n")
    report(99)
    val anchorless = runner("pitestEncodingVerify").build().output
    assertFalse(
      anchorless.contains("line-drifted") || anchorless.contains("comment does not name"),
      "line-less member produced positional churn:\n$anchorless"
    )
  }

  @Test
  fun `a membership row with the wrong field count is named malformed, not stale`() {
    // A two-field row used to surface as 'matches no mutant' — advice that sends the
    // reader hunting for a moved mutant when the actual problem is the row's shape.
    // The field normalization exists so the tool never silently disagrees with the
    // file; diagnosing the shape finishes that job.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # well-formed, matches the mutant below\n" +
          "com.example.Codec,encode\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("1 malformed row in encoding-timeouts.csv") &&
          output.contains("Expected 'class,method,mutator'") &&
          output.contains("com.example.Codec,encode\n"),
      "malformed row not diagnosed by shape:\n$output"
    )
    assertFalse(
      output.contains("match no mutant"),
      "malformed row misdiagnosed as stale:\n$output"
    )
    assertFalse(
      output.contains("not in the audited set"),
      "well-formed member did not satisfy the audit:\n$output"
    )
  }

  @Test
  fun `timeout audit init seeds the audited set from the report and refuses to reseed`() {
    // Adoption's mechanical half is derivable from the report the tool already has;
    // hand-transcribing members from mutations.xml is exactly the kind of work
    // pitestEncodingBaselineUpdate exists to avoid for baselines. Seeding writes the
    // rows; the cause warnings that follow drive the half that needs a person.
    writeFixture()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,44,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,50,KILLED,com.example.CodecTest",
      ),
      ""
    )
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")

    val seeded = timeoutAuditInitRunner().build().output
    assertTrue(
      seeded.contains("seeded 2 audited-timeout member(s) into encoding-timeouts.csv"),
      "seed summary missing:\n$seeded"
    )
    val written = timeoutsFile.readText()
    assertTrue(
      written.contains("duration * timeoutFactor") &&
          written.contains("+ timeoutConst, it proves nothing") &&
          written.contains("emergency ceiling may coexist with liveness") &&
          written.contains("straight-line path without a loop"),
      "seeded timeout file omitted the pre-classification harness checks:\n$written",
    )
    // sibling timeouts of one member collapse to one row, both observed lines kept
    assertTrue(
      written.contains("com.example.Codec,encode,MathMutator # cause:untriaged lines 12, 30") &&
          written.contains("com.example.Codec,decode,IncrementsMutator # cause:untriaged line 44"),
      "seeded membership wrong:\n$written"
    )
    assertFalse(written.contains("KILLED") || written.contains("decode,MathMutator"), "non-timeout seeded:\n$written")

    // the freshly seeded set satisfies its own audit
    val audited = runner("pitestEncodingVerify").build().output
    assertFalse(
      audited.contains("not in the audited set") || audited.contains("pitestEncodingTimeoutAuditInit"),
      "seeded set did not satisfy the audit:\n$audited"
    )

    // membership changes one reviewed row at a time after adoption: no reseeding
    val refused = timeoutAuditInitRunner().buildAndFail().output
    assertTrue(
      refused.contains("encoding-timeouts.csv already exists"),
      "reseed not refused:\n$refused"
    )
  }

  @Test
  fun `timeout audit init refuses a report with nothing timed out`() {
    // An empty seed would activate the audit while telling its adopter to write
    // causes for zero members — the task is pointed at by a summary that reported
    // timeouts, and a run where they vanished is load noise, not a population.
    writeFixture()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,50,KILLED,com.example.CodecTest",
      ),
      ""
    )

    val refused = timeoutAuditInitRunner().buildAndFail().output
    assertTrue(
      refused.contains("no timed-out mutants in this run's report — nothing to seed"),
      "empty seed not refused:\n$refused"
    )
    // arming a never-timed-out suite is a different intent with a different
    // mechanism; the refusal names it instead of reading as "empty sets forbidden"
    assertTrue(
      refused.contains("commit encoding-timeouts.csv with only '#' comment lines"),
      "arming alternative not named:\n$refused"
    )
    assertFalse(
      File(fixtureDir, "config/pitest/encoding-timeouts.csv").isFile,
      "a refused seed must write nothing"
    )
  }

  @Test
  fun `a legacy line-full stash resets the drift comparison with a notice`() {
    // A stash written by a pre-line-less plugin keys entries as
    // class,method,line,mutator. Read against the line-less coordinate nothing
    // matches, and without the reset every current timeout reads "newly timed out"
    // and every old entry "no longer" — a drift line describing churn that never
    // happened, on exactly the run where the operator is judging the upgrade.
    writeFixture()
    val stash = File(fixtureDir, ".pitest-history/encoding.statuses")
    stash.parentFile.mkdirs()
    stash.writeText(
      "com.example.Codec,encode,12,MathMutator,TIMED_OUT\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # line 20\n")

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("status stash predates the current stash format"),
      "format reset not announced:\n$output"
    )
    assertFalse(output.contains("timed-out drift vs previous run"), "phantom drift reported:\n$output")
    assertFalse(output.contains("flipped SURVIVED -> TIMED_OUT"), "phantom flip reported:\n$output")

    // the stash is rewritten in the current format, so the next run compares for
    // real — quietly, since nothing moved
    val second = runner("pitestEncodingVerify").build().output
    assertFalse(second.contains("predates the current stash format"), "reset announced twice:\n$second")
    assertFalse(second.contains("timed-out drift vs previous run"), "settled run reported drift:\n$second")
  }

  @Test
  fun `a headerless stash from the released plugin resets instead of comparing blind`() {
    // The 21.5.21 stash is already line-less but carries only TIMED_OUT and
    // SURVIVED entries — no NO_COVERAGE, no KILLED. Compared as-is, a
    // NO_COVERAGE -> TIMED_OUT flip on the first run after the upgrade reads
    // benign ("previously detected") — the exact claim the flip warning exists
    // to forbid — with no sign anything degenerated. The format header is the
    // stash's identity: headerless means an earlier plugin wrote it, and the
    // comparison resets with the same notice the line-less migration earned.
    writeFixture()
    val stash = File(fixtureDir, ".pitest-history/encoding.statuses")
    stash.parentFile.mkdirs()
    stash.writeText("com.example.Codec,encode,MathMutator,SURVIVED\n")
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n" +
          "com.example.Codec,encode,IncrementsMutator,NO_COVERAGE # line 24\n"
    )
    fun mutant(mutator: String, line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.$mutator,encode,$line,$status,none"
    writeReport(
      listOf(mutant("MathMutator", 10, "SURVIVED"), mutant("IncrementsMutator", 24, "TIMED_OUT")),
      ""
    )

    val first = runner("pitestEncodingVerify").build().output
    assertTrue(
      first.contains("status stash predates the current stash format"),
      "headerless stash did not reset:\n$first"
    )
    assertFalse(
      first.contains("previously detected") || first.contains("first observed"),
      "the blind window compared anyway:\n$first"
    )
    assertFalse(first.contains("flipped"), "reset run reported a flip:\n$first")

    // the rewritten stash carries the header and every status; the next
    // identical run compares for real, quietly
    val second = runner("pitestEncodingVerify").build().output
    assertFalse(second.contains("predates the current stash format"), "reset announced twice:\n$second")
    assertFalse(second.contains("timed-out drift vs previous run"), "settled run reported drift:\n$second")
  }

  @Test
  fun `a timeout at a fully-killed key is previously detected, not a first observation`() {
    // KILLED is stashed too: a key whose mutants were all killed last run is the
    // most common home of the benign KILLED<->TIMED_OUT flap, and without KILLED
    // entries it is indistinguishable from a coordinate the suite never had —
    // which the drift line then mislabels a first observation, run after run.
    writeFixture()
    fun report(status: String) = writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,$status," +
            (if (status == "KILLED") "com.example.CodecTest" else "none"),
      ),
      ""
    )

    report("KILLED")
    runner("pitestEncodingVerify").build()

    report("TIMED_OUT")
    val flapped = runner("pitestEncodingVerify").build().output
    assertTrue(
      flapped.contains("1 newly timed out (previously detected), 0 first observed"),
      "benign KILLED flap not named as previously detected:\n$flapped"
    )
    assertFalse(flapped.contains("flipped"), "benign flap read as a dangerous flip:\n$flapped")
  }

  @Test
  fun `timeout drift names changed coordinates already covered by line-less audit members`() {
    // Audit membership is line-less. Once a key is present, the unaudited check
    // cannot distinguish its original timeout from another sibling at the same
    // class/method/mutator becoming TIMED_OUT. The status stash still sees the
    // count increase; its default output must name the coordinate, while a genuine
    // KILLED -> TIMED_OUT transition remains on the benign detected side.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,decode,IncrementsMutator,SURVIVED # covered sibling # line 30\n"
    )
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # line 12\n" +
          "com.example.Codec,decode,IncrementsMutator\n"
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "# Codec timeout causes\n\n" +
          "`Codec.encode` (`MathMutator`) loses its loop exit. " +
          "`Codec.decode` (`IncrementsMutator`) reverses its cursor.\n"
    )
    fun mutant(method: String, mutator: String, line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators." +
          "$mutator,$method,$line,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")

    writeReport(
      listOf(
        mutant("encode", "MathMutator", 12, "TIMED_OUT"),
        mutant("encode", "MathMutator", 20, "KILLED"),
        mutant("decode", "IncrementsMutator", 30, "SURVIVED"),
      ),
      "",
    )
    runner("pitestEncodingVerify").build()

    writeReport(
      listOf(
        mutant("encode", "MathMutator", 12, "TIMED_OUT"),
        mutant("encode", "MathMutator", 20, "TIMED_OUT"),
        mutant("decode", "IncrementsMutator", 30, "SURVIVED"),
        mutant("decode", "IncrementsMutator", 40, "TIMED_OUT"),
      ),
      "",
    )
    val output = runner("pitestEncodingVerify").build().output

    assertTrue(
          output.contains("1 newly timed out (previously detected), 1 first observed") &&
          output.contains(
            "com.example.Codec,encode,MathMutator (+1) — " +
                "previously detected (usually KILLED -> TIMED_OUT)") &&
          output.contains(
            "line-less stash cannot identify which 1 of 2 current TIMED_OUT mutant(s) " +
                "are new; all candidates") &&
          output.contains("candidate 1/2: com.example.Codec,encode,12,MathMutator") &&
          output.contains("candidate 2/2: com.example.Codec,encode,20,MathMutator") &&
          output.contains(
            "com.example.Codec,decode,IncrementsMutator (+1) — " +
                "first observed (reviewer-stop; no prior detected read)") &&
          output.contains("newly TIMED_OUT: com.example.Codec,decode,40,IncrementsMutator"),
      "changed audited coordinates and their current line-full candidates were not named:\n$output",
    )
    assertFalse(
      output.contains("not in the audited set"),
      "line-less members were incorrectly treated as unaudited:\n$output",
    )
    assertFalse(
      output.contains("flipped SURVIVED -> TIMED_OUT") ||
          output.contains("flipped NO_COVERAGE -> TIMED_OUT"),
      "aggregate-stable or KILLED timeout drift was promoted to a dangerous flip:\n$output",
    )
  }

  @Test
  fun `a NO_COVERAGE mutant that times out is a dangerous flip, not prior detection`() {
    // The stash carries every gated status: a mutant no test had reached that now
    // reads TIMED_OUT slid behind the watchdog exactly like a survivor — but with
    // only SURVIVED stashed, the comparison read it as the benign KILLED flavour
    // and printed "previously detected" for a mutant that never was.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim # line 50\n")
    fun report(status: String) = writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,50,$status,none"),
      ""
    )

    report("NO_COVERAGE")
    runner("pitestEncodingVerify").build()

    report("TIMED_OUT")
    val flipped = runner("pitestEncodingVerify").build().output
    assertTrue(
      flipped.contains("1 coordinate(s) flipped NO_COVERAGE -> TIMED_OUT"),
      "the never-reached origin must be the dangerous flavour:\n$flipped"
    )
    assertTrue(
      flipped.contains("newly TIMED_OUT: com.example.Codec,encode,50,MathMutator"),
      "dangerous positive delta omitted its line-full observation:\n$flipped",
    )
    assertFalse(flipped.contains("previously detected"), "a never-detected mutant claimed as detected:\n$flipped")
    assertFalse(flipped.contains("flipped SURVIVED"), flipped)
    assertTrue(flipped.contains("NO_COVERAGE -> TIMED_OUT flip(s)"), "advisory summary missing:\n$flipped")
  }

  @Test
  fun `a member that stops timing out is noticed after three quiet runs`() {
    // Membership is validated against all mutants, so a member whose mutants exist
    // but never time out — pasted from the wrong report, or a timeout the tests
    // since learned to kill — was accepted forever. One quiet run is just the
    // KILLED<->TIMED_OUT load flip; the notice waits for the flip-family retirement
    // criterion (3 quiet cycles) and resets whenever the member times out again.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    fun report(status: String) = writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,$status," +
            (if (status == "KILLED") "com.example.CodecTest" else "none"),
      ),
      ""
    )

    report("KILLED")
    val quietNotice = "audited-timeout member has not timed out in 3+"
    runner("pitestEncoding").build()

    // the counter is keyed to the evidence invocation: standalone verify re-runs of
    // one unchanged report are one observation, not manufactured quiet evidence
    repeat(3) {
      val rerun = runner("pitestEncodingVerify").build().output
      assertFalse(rerun.contains(quietNotice), "an unchanged report advanced the quiet streak:\n$rerun")
      // a quiet member is not the stale case: its mutant is present, just detected
      assertFalse(rerun.contains("match no mutant"), "quiet member misread as stale:\n$rerun")
    }
    val second = runner("pitestEncoding").build().output
    assertFalse(second.contains(quietNotice), "notice fired before the third quiet report:\n$second")
    val third = runner("pitestEncoding").build().output
    val quietAt = third.indexOf("1 $quietNotice")
    val quietEvidenceAt = third.indexOf("  Evidence:", quietAt)
    val quietReviewAt = third.indexOf("  Review:", quietEvidenceAt)
    val quietRemedyAt = third.indexOf("  Remedy:", quietReviewAt)
    assertTrue(
      quietAt >= 0 && quietAt < quietEvidenceAt && quietEvidenceAt < quietReviewAt &&
          quietReviewAt < quietRemedyAt &&
          third.contains(
            "com.example.Codec,encode,MathMutator " +
                "(quiet for 3 runs; latest fresh report KILLED x1)"
          ),
      "third quiet report not noticed:\n$third"
    )
    // the retirement criterion family is one advisory tier: like its siblings the
    // notice feeds the end-of-build summary, or a gate scrolls it off screen
    assertTrue(
      third.contains("1 quiet audited-timeout member"),
      "quiet streak missing from the advisory summary:\n$third"
    )

    // same report, re-run: the counts replay and so does the notice — like every
    // other audit advisory it is derived from the report, not from having printed
    val replay = runner("pitestEncodingVerify").build().output
    assertTrue(replay.contains(quietNotice), "replayed evidence lost the notice:\n$replay")

    // a timeout resets the streak — the notice must go quiet again for 3 more runs
    report("TIMED_OUT")
    val reset = runner("pitestEncoding").build().output
    assertFalse(reset.contains(quietNotice), "timeout did not reset the quiet streak:\n$reset")
    report("KILLED")
    val afterReset = runner("pitestEncoding").build().output
    assertFalse(afterReset.contains(quietNotice), "streak not restarted from zero:\n$afterReset")
  }

  @Test
  fun `timeout retirement requires full observations of identical evidence inputs`() {
    writeFixture()
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    configDir.resolve("encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    configDir.resolve("README.md").writeText(
      "## `com.example.Codec.encode`\n\nRemoving progress makes the path non-terminating.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,KILLED,com.example.CodecTest",
      ),
      "",
    )
    val quietStash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet")

    runner("pitestEncoding").build()
    val afterFirstFull = quietStash.readBytes()
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,1"),
      quietStash.readText(),
    )

    runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    assertTrue(
      afterFirstFull.contentEquals(quietStash.readBytes()),
      "a scoped diagnostic advanced timeout-retirement evidence:\n${quietStash.readText()}",
    )

    runner("pitestEncoding").build()
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,2"),
      quietStash.readText(),
    )
    val previousInputIdentity = quietStash.readLines()
      .first { it.startsWith("# inputs ") }
      .removePrefix("# inputs ")

    File(fixtureDir, "src/main/java/com/example/FakePit.java")
      .appendText("\n// changed source inputs\n")
    val changed = runner("pitestEncoding").build().output
    val currentInputIdentity = quietStash.readLines()
      .first { it.startsWith("# inputs ") }
      .removePrefix("# inputs ")
    assertTrue(
      changed.contains("timeout-retirement execution inputs changed") &&
          previousInputIdentity != currentInputIdentity &&
          changed.contains(
            "input identity prefixes ${previousInputIdentity.take(12)} -> " +
                currentInputIdentity.take(12)
          ) &&
          !changed.contains("pluginSha256") &&
          !changed.contains("audited-timeout member has not timed out in 3+"),
      "changed inputs inherited the prior quiet streak:\n$changed",
    )
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,1"),
      "changed inputs did not restart the quiet streak:\n${quietStash.readText()}",
    )

    quietStash.writeText(
      "# timeout quiet format 4\n" +
        "# inputs nope\n" +
        "# invocation old\n" +
        "com.example.Codec,encode,MathMutator,9\n",
    )
    val malformed = runner("pitestEncoding").build().output
    assertTrue(
      malformed.contains("format-4 stash has a missing/malformed input identity") &&
          !malformed.contains("timeout-retirement execution inputs changed"),
      "a malformed format-4 identity was presented as a valid transition:\n$malformed",
    )
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,1"),
      "a malformed identity did not restart the quiet streak:\n${quietStash.readText()}",
    )
  }

  @Test
  fun `a quiet stash without member rows resets without a notice`() {
    // An empty audited set still writes a headers-only stash, and every input change then
    // announced a reset of counts it never held (glam-sdk-java's sdk, 2026-10-16). The
    // notice names dropped counts, so it needs a count to name.
    writeFixture()
    File(fixtureDir, "config/pitest").apply { mkdirs() }
      .resolve("encoding-timeouts.csv").writeText("# no audited members\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,KILLED,com.example.CodecTest",
      ),
      "",
    )
    val quietStash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet")
    fun inputs() = quietStash.readLines().first { it.startsWith("# inputs ") }
    val resetNotice = "the quiet-run counter resets this run"

    runner("pitestEncoding").build()
    assertTrue(quietStash.readLines().filter { it.isNotBlank() }.all { it.startsWith("# ") }, quietStash.readText())
    val before = inputs()
    File(fixtureDir, "src/main/java/com/example/FakePit.java")
      .appendText("\n// changed source inputs\n")
    val changed = runner("pitestEncoding").build().output
    assertTrue(before != inputs(), "the inputs did not change, so nothing was tested:\n${quietStash.readText()}")
    assertFalse(changed.contains(resetNotice), changed)

    // The older-format and malformed-identity resets follow the same rule; each run still
    // rewrites the stash, so the silence is not a skipped observation.
    quietStash.writeText("# timeout quiet format 3\n# inputs ${"a".repeat(64)}\n# invocation old\n")
    val older = runner("pitestEncoding").build().output
    assertFalse(older.contains(resetNotice), older)
    assertEquals("# timeout quiet format 4", quietStash.readLines().first(), quietStash.readText())
    quietStash.writeText("# timeout quiet format 4\n# inputs nope\n# invocation old\n")
    val malformed = runner("pitestEncoding").build().output
    assertFalse(malformed.contains(resetNotice), malformed)
    assertTrue(Regex("# inputs [0-9a-f]{64}").matches(inputs()), quietStash.readText())
  }

  @Test
  fun `timeout retirement streak survives a plugin identity-only change`() {
    writeFixture()
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    configDir.resolve("encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    configDir.resolve("README.md").writeText(
      "## `com.example.Codec.encode`\n\nRemoving progress makes the path non-terminating.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,KILLED,com.example.CodecTest",
      ),
      "",
    )

    runner("pitestEncoding").build()
    val evidence = PitestEvidence.parse(
      File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv").readText(),
    )
    val previousPluginObservation = evidence.copy(
      invocationId = "previous-plugin-observation",
      pluginSha256 = "different-plugin-bytes",
    )
    val quietStash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet")
    quietStash.writeText(
      "# timeout quiet format 4\n" +
          "# inputs ${previousPluginObservation.timeoutRetirementInputIdentitySha256()}\n" +
          "# invocation ${previousPluginObservation.invocationId}\n" +
          "com.example.Codec,encode,MathMutator,1\n",
    )

    val next = runner("pitestEncoding").build().output

    assertFalse(
      next.contains("timeout-retirement execution inputs changed"),
      "plugin identity alone reset the timeout-retirement streak:\n$next",
    )
    assertTrue(
      quietStash.readText().contains("com.example.Codec,encode,MathMutator,2"),
      "plugin identity alone did not preserve the prior quiet observation:\n${quietStash.readText()}",
    )
  }

  @Test
  fun `timeout retirement notice reports the latest mixed status without overstating the streak`() {
    // The quiet counter records only consecutive absence of TIMED_OUT. The separate
    // format-3 status stash already owns last-observed statuses, so the notice should
    // render the current fresh multiset rather than inventing a second history schema
    // or claiming all three observations were kills.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 10\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # mixed sibling # line 20\n"
    )
    val killedOnly = listOf(
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,10,KILLED,com.example.CodecTest"
    )
    val mixed = listOf(
      killedOnly.single(),
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,20,SURVIVED,none",
    )
    writeReport(killedOnly, "")

    runner("pitestEncoding").build()
    val second = runner("pitestEncoding").build().output
    assertFalse(
      second.contains("audited-timeout member has not timed out in 3+"),
      "notice fired before the third observation:\n$second"
    )

    // Change, rather than merely touch, the final observation. This pins that the
    // rendered multiset comes from the latest fresh report instead of the first one
    // that began the quiet streak.
    writeReport(
      mixed,
      ""
    )
    val third = runner("pitestEncoding").build().output
    assertTrue(
      third.contains(
        "com.example.Codec,encode,MathMutator " +
            "(quiet for 3 runs; latest fresh report KILLED x1, SURVIVED x1)"
      ),
      "latest mixed status was not rendered deterministically:\n$third"
    )
    assertFalse(
      third.contains("tests now detect the mutant outright"),
      "quiet evidence was overstated as three killing observations:\n$third"
    )
  }

  @Test
  fun `timeout retirement notices identify projects when suite names are duplicated`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "duplicate-hardening-suite-smoke-test"
        include("a", "b")
      """.trimIndent() + "\n"
    )
    listOf("a", "b").forEach { projectName ->
      val projectDir = File(fixtureDir, projectName).apply { mkdirs() }
      projectDir.resolve("build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          repositories {
            mavenCentral()
          }

          hardening {
            mutation.register("dispatch") {
              targetClasses = listOf("com.example.Codec")
              targetTests = "com.example.*Test*"
            }
          }

          tasks.named<JavaExec>("pitestDispatch") {
            classpath = sourceSets.main.get().output
            mainClass.set("com.example.FakePit")
            // Through the environment: the master JVM's own options are refused
            // because the evidence does not record them.
            environment(
              "FIXTURE_PIT_REPORT",
              layout.projectDirectory.dir("fixture-pit-report").asFile.absolutePath,
            )
          }
          tasks.named<JavaCompile>("compileForPitest") {
            setSource(sourceSets.main.get().java)
            classpath = files()
          }
        """.trimIndent() + "\n"
      )
      projectDir.resolve("config/pitest/dispatch-timeouts.csv").apply {
        parentFile.mkdirs()
        writeText("com.example.Codec,encode,MathMutator # cause:liveness line 12\n")
      }
      projectDir.resolve("config/pitest/README.md").writeText(
        "## `com.example.Codec.encode`\n\nRemoving progress makes the path non-terminating.\n",
      )
      projectDir.resolve(".pitest-history/dispatch.timeout-quiet").apply {
        parentFile.mkdirs()
        writeText("com.example.Codec,encode,MathMutator,2\n")
      }
      projectDir.resolve(".pitest-history/dispatch.statuses").writeText(
        "com.example.Codec,encode,12,MathMutator,KILLED\n"
      )
      projectDir.resolve("fixture-pit-report/mutations.csv").apply {
        parentFile.mkdirs()
        writeText(
          "Codec.java,com.example.Codec," +
              "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
              "encode,12,KILLED,com.example.CodecTest\n"
        )
      }
      projectDir.resolve("fixture-pit-report/mutations.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n</mutations>\n"
      )
      projectDir.resolve("src/main/java/com/example/FakePit.java").apply {
        parentFile.mkdirs()
        writeText(
          """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardCopyOption;

          public final class FakePit {
            public static void main(String[] args) throws Exception {
              Path reportDir = null;
              for (String arg : args) {
                if (arg.startsWith("--reportDir=")) {
                  reportDir = Path.of(arg.substring("--reportDir=".length()));
                }
              }
              if (reportDir == null) throw new IllegalArgumentException("missing --reportDir");
              Files.createDirectories(reportDir);
              Path staged = Path.of(System.getenv("FIXTURE_PIT_REPORT"));
              for (String name : new String[] {"mutations.csv", "mutations.xml"}) {
                Files.copy(staged.resolve(name), reportDir.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING);
              }
            }
          }
          """.trimIndent() + "\n"
        )
      }
    }
    fun runBoth(): String = runner(
      ":a:pitestDispatch",
      ":b:pitestDispatch",
    ).build().output

    val reset = runBoth()
    val resetNotice = "timeout-retirement stash uses an older compatibility format"
    val statusResetNotice = "status stash predates the current stash format"
    assertTrue(
      reset.contains(":a pitest 'dispatch': $resetNotice") &&
          reset.contains(":b pitest 'dispatch': $resetNotice") &&
          reset.contains(":a pitest 'dispatch': $statusResetNotice") &&
          reset.contains(":b pitest 'dispatch': $statusResetNotice"),
      "duplicate-suite reset notices were not project-qualified:\n$reset"
    )

    repeat(2) { observation ->
      val output = runBoth()
      if (observation == 1) {
        val quietNotice = "1 audited-timeout member has not timed out in 3+"
        assertTrue(
          output.contains(":a pitest 'dispatch': $quietNotice") &&
              output.contains(":b pitest 'dispatch': $quietNotice"),
          "duplicate-suite quiet notices were not project-qualified:\n$output"
        )
      }
    }
  }

  @Test
  fun `a stale interlude drops the quiet streak rather than freezing it`() {
    // A member goes stale only when its mutant left the report — the code moved, or
    // the mutator set changed — so quiet evidence about the old method body is
    // re-measured from zero if the mutant returns, not carried across the change.
    // The cost is two extra runs of patience; the alternative is a retirement nudge
    // argued from code that no longer exists.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    fun encodeReport() = writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest",
      ),
      ""
    )
    // the member's mutant is absent entirely: the stale case, not the quiet case
    fun staleReport() = writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,44,KILLED,com.example.CodecTest",
      ),
      ""
    )

    encodeReport()
    runner("pitestEncoding").build()
    encodeReport()
    runner("pitestEncoding").build()

    staleReport()
    val stale = runner("pitestEncoding").build().output
    assertTrue(stale.contains("matches no mutant"), "stale member not warned:\n$stale")

    encodeReport()
    val returned = runner("pitestEncoding").build().output
    assertFalse(
      returned.contains("audited-timeout member has not timed out in 3+"),
      "streak survived the stale interlude:\n$returned"
    )
    val stash = File(fixtureDir, ".pitest-history/encoding.timeout-quiet").readText()
    assertTrue(
      stash.contains("com.example.Codec,encode,MathMutator,1"),
      "quiet count not re-measured from zero after the stale interlude:\n$stash"
    )
  }

  @Test
  fun `-PstrictTimeoutAudit escalates unaudited newcomers to failures`() {
    // Advisory by default (load can time out any mutant), but a certifying run
    // exists to stop on exactly this; the -PnoDriftTolerance precedent. Hygiene
    // findings must not be escalated with it.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    // one audited member, plus a two-field row: malformed is the other finding the
    // strict flag escalates, so it must be excluded from the advisory summary too
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\ncom.example.Codec,encode\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,44,TIMED_OUT,none",
      ),
      ""
    )

    // without the flag: warns, builds
    runner("pitestEncodingVerify").build()
    val failed = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      failed.contains(
        "-PstrictTimeoutAudit refuses certification because it found " +
            "1 physical TIMED_OUT mutant instance across 1 line-less key unaudited, " +
            "1 malformed membership row, 0 inadmissible or unfinished cause classifications, " +
            "1 audited member without a README cause",
      ) &&
          failed.contains("  Evidence: The warnings above list every affected row") &&
          failed.contains("  Review: Only cause:liveness may remain") &&
          failed.contains("  Remedy: For each unaudited candidate"),
      "strict run did not fail on the unaudited newcomer and the malformed row:\n$failed"
    )
    // the escalated findings are the failure, not advisories: the end-of-build
    // summary opens with "none failed the build", which must stay true
    val encodingAdvisorySummary = failed.lineSequence()
        .firstOrNull { it.startsWith("  pitest 'encoding':") }
    assertTrue(
      encodingAdvisorySummary != null,
      "strict run did not print its remaining advisory summary:\n$failed",
    )
    assertFalse(
      encodingAdvisorySummary!!.contains("remain unaudited") ||
          encodingAdvisorySummary.contains("malformed audit row(s)") ||
          encodingAdvisorySummary.contains("audited timeout(s) without a README cause"),
      "strict-escalated finding also recorded as an advisory:\n$failed"
    )

    // a member admitted without its README cause is an unfinished admission, not
    // hygiene — the doctrine admits a newcomer only with its cause written, so the
    // certifying run stops on it too; row-then-cause is a legitimate sequence
    // between certifications, not during one
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n" +
          "com.example.Codec,decode,IncrementsMutator # cause:liveness line 44\n"
    )
    val causeless = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      causeless.contains("2 audited members without README causes"),
      "strict run did not fail on the unwritten causes:\n$causeless"
    )
    assertFalse(
      causeless.contains("paste the printed row(s)") ||
          causeless.contains("remain unaudited"),
      "a cause-only failure reported nonexistent unaudited candidates:\n$causeless",
    )

    // with the causes written, a fully audited set passes strict even with hygiene
    // findings outstanding (the stale member below stays advisory)
    timeoutsFile.appendText("com.example.Codec,gone,MathMutator # cause:liveness line 99\n")
    File(fixtureDir, "config/pitest/README.md").writeText(
      "`Codec.encode` (MathMutator): the inflated estimate crawls, never fails.\n\n" +
          "`Codec.decode` (IncrementsMutator): the reversed cursor re-reads forever.\n"
    )
    val strictClean = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertTrue(
      strictClean.contains("matches no mutant"),
      "hygiene finding expected for the stale member:\n$strictClean"
    )

    // an unadopted timeout-carrying suite is an unaudited newcomer by definition
    timeoutsFile.delete()
    val unadopted = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      unadopted.contains("no audited") && unadopted.contains("pitestEncodingTimeoutAuditInit"),
      "strict run did not fail on the unadopted suite:\n$unadopted"
    )
  }

  @Test
  fun `timeout cause categories distinguish liveness from finite resource work`() {
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:resource line 12\n" +
          "com.example.Codec,decode,IncrementsMutator # cause:untriaged line 30\n" +
          "com.example.Codec,slow,MathMutator # cause:harness line 40\n" +
          "com.example.Codec,wait,VoidMethodCallMutator # line 44\n"
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec causes\n\n" +
          "`Codec.encode`: finite excessive allocation.\n" +
          "`Codec.decode`: not reviewed yet.\n" +
          "`Codec.slow`: a finite covering test races the watchdog under load.\n" +
          "`Codec.wait`: removed loop exit.\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,slow,40,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,44,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,52,KILLED,com.example.CodecTest",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,other,60,SURVIVED,none",
      ),
      ""
    )

    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains(
        "4 audited-timeout members lack an admissible cause classification") &&
          debt.contains("cause:resource terminates") &&
          debt.contains("cause:harness is a finite covering-path/watchdog race") &&
          debt.contains("cause:untriaged has not been reviewed") &&
          debt.contains("missing cause:liveness/resource/harness/untriaged"),
      "Debt did not share the cause-category audit:\n$debt"
    )
    val strictDebt = runner(
      "pitestEncodingDebt", "-PstrictTimeoutAudit",
    ).buildAndFail().output
    assertTrue(
      strictDebt.contains(
        "-PstrictTimeoutAudit refuses the committed-file preview because it found " +
            "0 malformed membership rows, 4 inadmissible or unfinished cause classifications, " +
            "0 members without README causes") &&
          strictDebt.contains("  Evidence: The warnings above list every affected row and member") &&
          strictDebt.contains("this invocation did not run PIT") &&
          strictDebt.contains("  Review: Report-dependent strict findings remain unevaluated") &&
          strictDebt.contains("  Remedy: Resolve each committed-file finding, then run " +
              ":pitestEncoding -PstrictTimeoutAudit") &&
          strictDebt.contains("1 audited-timeout key covers multiple mutant copies") &&
          strictDebt.contains(
            "1 key has exactly 1 physical TIMED_OUT mutant plus non-timeout siblings; " +
                "detailed populations are collapsed"
          ) &&
          strictDebt.contains("unverified read-only prior-report preview") &&
          strictDebt.contains("pitestEncoding -PnoMutationHistory") &&
          strictDebt.contains("1 survived, 0 no_coverage"),
      "strict Debt silently remained an advisory-only preview:\n$strictDebt",
    )
    val strict = runner("pitestEncoding", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      strict.contains(
        "4 inadmissible or unfinished cause classifications") &&
          strict.contains("Only cause:liveness may remain in a certifying audited set") &&
          strict.contains("Keep finite resource/harness work explicit and non-certifying") &&
          strict.contains("do not relabel it as liveness or delete it from one quiet run") &&
          strict.contains("PIT has not run"),
      "strict audit accepted finite or unfinished timeout causes:\n$strict"
    )

    timeoutsFile.writeText(
      "com.example.Codec,decode,IncrementsMutator # cause:liveness line 30\n" +
          "com.example.Codec,wait,VoidMethodCallMutator # cause:liveness line 44\n"
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec causes\n\n" +
          "`Codec.decode`: the reversed cursor can no longer reach its loop exit.\n" +
          "`Codec.wait`: the mutant removes the only wake-up signal.\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,44,TIMED_OUT,none",
      ),
      ""
    )
    val classified = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertFalse(
      classified.contains("cause classification"),
      "classified liveness rows stayed findings:\n$classified"
    )

    // Cause categories authorize timeout evidence, not every sibling result at the
    // line-less key. A finite same-key sibling that is deterministically KILLED does
    // not conflict with the liveness sibling that actually timed out.
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,44,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,52,KILLED,com.example.CodecTest",
      ),
      "",
    )
    val killedSibling = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertFalse(
      killedSibling.contains("cause classification") ||
          killedSibling.contains("physical TIMED_OUT mutant instance"),
      "a valid same-key KILLED sibling was misclassified as a mixed timeout cause:\n$killedSibling",
    )
    assertTrue(
      killedSibling.contains("1 audited-timeout key covers multiple mutant copies") &&
          killedSibling.contains(
            "1 key has exactly 1 physical TIMED_OUT mutant plus non-timeout siblings; " +
                "detailed populations are collapsed"
          ) &&
          killedSibling.contains("non-timeout siblings are context, not proof"),
      "verify did not summarize the non-decision-grade key-level population:\n$killedSibling",
    )
    assertFalse(
      killedSibling.contains("com.example.Codec,wait,VoidMethodCallMutator — 2 mutants") ||
          killedSibling.contains("line 44 TIMED_OUT x1") ||
          killedSibling.contains("line 52 KILLED x1"),
      "verify expanded a key with only one physical timeout:\n$killedSibling",
    )
    val debtPopulation = runner("pitestEncodingDebt").build().output
    assertTrue(
      debtPopulation.contains("1 audited-timeout key covers multiple mutant copies") &&
          debtPopulation.contains(
            "1 key has exactly 1 physical TIMED_OUT mutant plus non-timeout siblings; " +
                "detailed populations are collapsed"
          ),
      "Debt did not summarize the same key-level mutant population:\n$debtPopulation",
    )
    assertFalse(
      debtPopulation.contains("line 44 TIMED_OUT x1") ||
          debtPopulation.contains("line 52 KILLED x1"),
      "Debt expanded a key with only one physical timeout:\n$debtPopulation",
    )

    val strictDebtClean = runner(
      "pitestEncodingDebt", "-PstrictTimeoutAudit",
    ).build().output
    assertTrue(
      strictDebtClean.contains("-PstrictTimeoutAudit committed-file preview is clean") &&
          strictDebtClean.contains("Report-dependent strict checks require a full :pitestEncoding") &&
          strictDebtClean.contains("this Debt invocation did not run PIT"),
      "clean strict Debt did not state its committed-file-only boundary:\n$strictDebtClean",
    )

    // Source lines remain diagnostic. The current format cannot distinguish a
    // same-key sibling without imposing formatting-sensitive authorization, so the
    // documented key-level limitation must remain non-blocking.
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,44,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.VoidMethodCallMutator,wait,52,TIMED_OUT,none",
      ),
      "",
    )
    val sibling = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertFalse(
      sibling.contains("unreviewed line") || sibling.contains("authorized line(s)"),
      "diagnostic source lines became strict authorization:\n$sibling",
    )
  }

  @Test
  fun `advisory findings are summarized at the end of the build`() {
    // The advisories never fail the build, but across a gate a warning from one
    // suite scrolls hundreds of lines off screen — the summary reprints one line
    // per suite at the very end, where it cannot be missed.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("com.example.Codec,gone,MathMutator\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("hardening: 5 advisory findings across 1 scope") &&
          output.contains(
            "pitest 'encoding': report has no completed-run evidence manifest, " +
                "committed record is legacy-unversioned, committed record is " +
                "legacy-toolchain-unbound, 1 physical TIMED_OUT mutant instance across " +
                "1 line-less key remains unaudited, 1 stale audit row"),
      "advisory summary missing:\n$output"
    )

    // Fixing the timeout set removes those two findings. The fabricated report still
    // deliberately lacks a completed-run evidence manifest, so only that migration
    // advisory remains.
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator # cause:liveness line 12\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the estimate crawls, never fails.\n")
    val clean = runner("pitestEncodingVerify").build().output
    assertTrue(
      clean.contains("hardening: 3 advisory findings across 1 scope") &&
          clean.contains("pitest 'encoding': report has no completed-run evidence manifest") &&
          !clean.contains("remain unaudited") && !clean.contains("stale audit row"),
      "timeout advisories survived a clean timeout audit:\n$clean"
    )
  }

  @Test
  fun `the debt task runs the audit's static checks without a report`() {
    // Row shape and cause presence read committed files only, so Debt confirms a
    // pasted member or a fresh README cause in seconds — the alternative was a full
    // mutation run, or hand-rolling the matching rule outside the tool (and drifting
    // from it). Shared via TimeoutAudit, so Debt and the verify cannot disagree.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator\n" +
          "com.example.Codec,decode\n"
    )

    // no report, no baseline: the paste-feedback case, and the early 'debt: none'
    // return must not skip the audit
    val output = runner("pitestEncodingDebt").build().output
    assertTrue(output.contains("debt: none"), output)
    assertTrue(
      output.contains("1 malformed row in encoding-timeouts.csv"),
      "malformed row not named:\n$output"
    )
    assertTrue(
      output.contains("cause? com.example.Codec,encode,MathMutator"),
      "missing cause not named:\n$output"
    )

    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the estimate crawls, never fails.\n")
    val quiet = runner("pitestEncodingDebt").build().output
    assertFalse(
      quiet.contains("malformed") || quiet.contains("cause?") ||
          quiet.contains("cause classification"),
      "clean audit still warned:\n$quiet"
    )
  }
}
