import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Baseline matching, retagging, schema migration, labels, and record updates. */
class HardeningRatchetFunctionalTest : HardeningRatchetFunctionalFixture() {

  @Test
  fun `a line move churns nothing and the failure diff carries descriptions`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    // legacy five-field row: the line field is metadata, and the key is line-less
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

    // the mutant moved from line 10 to 12: same key, so nothing is fresh and nothing
    // is stale — the run passes with no prune-candidate preview at all; only the line-drift
    // advisory asks for a re-read, since the recorded anchor no longer matches
    val moved = runner("pitestEncodingVerify", "-PlistUnkilled").build().output
    assertTrue(
        moved.contains("1 row / 1 unique key — 1 '# untriaged'"),
        "per-label count missing:\n$moved",
    )
    assertTrue(moved.contains("pitest 'encoding' unkilled:"), "-PlistUnkilled listing missing:\n$moved")
    val expectedDriftAdvisory =
      """
      pitest baseline 'encoding': line drift detected for 1 accepted key; no recorded '# line' tag names an observed unkilled-mutant line:
        com.example.Codec,encode,MathMutator,SURVIVED # recorded tag line(s): 10; unmatched observed line(s): 12
        Review: Re-read the README acceptance argument; the accepted code may have moved, or a new mutant may sit under an old acceptance (the same-key swap).
        Remedy if the argument still applies: Run :pitestEncodingBaselineRetag — it rewrites only matched line metadata, preserves every accepted row, including unmatched licensed-engine evidence, and refuses any fresh gated row.
      """.trimIndent()
    assertTrue(
      moved.contains(expectedDriftAdvisory) &&
          moved.contains("1 line-drifted baseline key"),
      "line-drift advisory missing:\n$moved"
    )
    // the XML description carries the line the key no longer does
    assertTrue(moved.contains("line 12: Replaced Shift Left with Shift Right"), "XML description missing:\n$moved")
    assertFalse(moved.contains("refresh with"), "a line move must not ask for a refresh:\n$moved")
    assertFalse(moved.contains("moved line only"), "the drift-tolerance machinery is retired:\n$moved")

    // Prune remains shrink-only in identity, but it is broader than metadata-only
    // acknowledgement. It must therefore print the evidence it is about to erase;
    // Retag is the task to prefer when the reviewed transition is only a line move.
    val pruned = baselinePruneRunner().build().output
    assertEquals(
      "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12\n",
      baselineFile().readText(),
    )
    val expectedPruneNotice =
      """
      pitest baseline 'encoding': line drift detected for 1 accepted key; no recorded '# line' tag names an observed unkilled-mutant line:
        com.example.Codec,encode,MathMutator,SURVIVED # recorded tag line(s): 10; unmatched observed line(s): 12
        Review: Re-read the README acceptance argument; the accepted code may have moved, or a new mutant may sit under an old acceptance (the same-key swap).
        Pre-write notice: The selected :pitestEncodingBaselinePrune also applies its reviewed removals. If only metadata should change, use :pitestEncodingBaselineRetag instead.
      """.trimIndent()
    assertTrue(
      pruned.contains(expectedPruneNotice),
      "prune refreshed line metadata without first reporting the drift:\n$pruned",
    )
    assertTrue(pruned.contains("prune dropped nothing and refreshed 1 line tag(s)"), pruned)
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("unmatched observed line(s):"),
      "prune did not clear the line-drift advisory:\n$settled",
    )

    // a genuinely new mutant still fails, tallied and described
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,33,SURVIVED,none",
      ),
      """
        <mutation status="SURVIVED" detected="false">
          <sourceFile>Codec.java</sourceFile>
          <mutatedClass>com.example.Codec</mutatedClass>
          <mutatedMethod>decode</mutatedMethod>
          <lineNumber>33</lineNumber>
          <mutator>org.pitest.mutationtest.engine.gregor.mutators.MathMutator</mutator>
          <description>Replaced integer addition with subtraction</description>
        </mutation>
      """.trimIndent()
    )
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")
    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(output.contains("1 unkilled mutant(s) not in the accepted baseline"), output)
    assertTrue(
      output.contains("This [history] result is check-only") &&
          output.contains("pitestEncoding -PnoMutationHistory"),
      "an assisted fresh-mutant failure invited a record edit:\n$output",
    )
    assertTrue(
      output.contains("churn: 0 newly covered, 1 unexplained (of 1 new; 0 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(output.contains("line 33: Replaced integer addition with subtraction"), "description missing:\n$output")
  }

  @Test
  fun `an unseeded gate names update as the first complete baseline write`() {
    writeFixture()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"
      ),
      "",
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("pitestEncodingBaselineUpdate to seed config/pitest/encoding-accepted.csv") &&
          output.contains("from this first complete report"),
      output,
    )
    assertFalse(
      output.contains("after documenting each acceptance run :pitestEncodingBaselineUnion"),
      output,
    )
  }

  @Test
  fun `retag clears reviewed drift without removing unmatched evidence`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val drifted = "com.example.Codec,encode,MathMutator,SURVIVED"
    val subsumed = "com.example.Codec,decode,MathMutator,SURVIVED"
    baselineFile().writeText(
      "$drifted # live family # line 10\n" +
          "$subsumed # licensed subsumption # line 30\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"
      ),
      ""
    )

    val before = runner("pitestEncodingVerify").build().output
    assertTrue(
      before.contains("recorded tag line(s): 10; unmatched observed line(s): 12"),
      before,
    )
    assertTrue(
      before.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          before.contains("$subsumed # licensed subsumption # line 30"),
      before,
    )
    assertTrue(before.contains("pitestEncodingBaselineRetag"), before)

    val retagged = baselineRetagRunner().build().output
    val expectedRetagNotice =
      """
      pitest baseline 'encoding': line drift detected for 1 accepted key; no recorded '# line' tag names an observed unkilled-mutant line:
        com.example.Codec,encode,MathMutator,SURVIVED # recorded tag line(s): 10; unmatched observed line(s): 12
        Review: Re-read the README acceptance argument; the accepted code may have moved, or a new mutant may sit under an old acceptance (the same-key swap).
        Pre-write notice: :pitestEncodingBaselineRetag is the explicit metadata-only acknowledgement; inspect the resulting diff.
      """.trimIndent()
    assertTrue(
      retagged.contains(expectedRetagNotice) &&
          retagged.contains("retag refreshed 1 matched row line tag(s)") &&
          retagged.contains("preserved all 2 accepted row(s), including unmatched evidence"),
      retagged,
    )
    assertFalse(
      retagged.contains("line-drifted baseline key"),
      "a successful retag was reprinted as unresolved advisory debt:\n$retagged",
    )
    assertEquals(
      "$drifted # live family # line 12\n" +
          "$subsumed # licensed subsumption # line 30\n",
      baselineFile().readText(),
    )

    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(settled.contains("unmatched observed line(s):"), settled)
    assertTrue(
      settled.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          settled.contains("$subsumed # licensed subsumption # line 30"),
      "retag must not remove or hide the unmatched candidate:\n$settled",
    )
  }

  @Test
  fun `retag reports ambiguous moved sibling assignment and retains every row`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val key = "com.example.Codec,encode,MathMutator,SURVIVED"
    baselineFile().writeText("$key # first argument # line 10\n$key # second argument # line 20\n")
    writeReport(listOf(
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,KILLED,com.example.CodecTest",
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,50,SURVIVED,none",
    ), "")

    val output = baselineRetagRunner().build().output
    assertTrue(output.contains("ambiguous same-key sibling fallback for 1 key(s)") &&
        output.contains("paired by nearest recorded line, ties in file order") &&
        output.contains("this does not establish physical mutant identity") &&
        output.contains("Review the README pointers and resulting line tags"), output)
    assertTrue(output.contains(
        "The fallback-paired rows at 1 of these key(s) carry different family labels"), output)
    assertTrue(output.substringAfterLast("hardening: ")
        .contains("1 ambiguous retag sibling key(s), 1 with differing labels"), output)
    assertEquals("$key # first argument # line 10\n$key # second argument # line 50\n",
        baselineFile().readText(),
        "the single surviving copy pairs with the row whose recorded line is nearest (20, not 10)")
  }

  @Test
  fun `rebase and union hand remaining line drift to retag after successful writes`() {
    // Rebase changes provenance while retaining acceptance capacity. A matching
    // line-less key can therefore keep its old tag after the fresh run: print the
    // same Retag handoff only after that write succeeds, not as a pre-write claim.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10\n",
    )
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator # cause:liveness line 12\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.encode\n\n`Codec.encode`: removing progress strands the loop.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
      ),
      "",
    )

    // Seed the quiet counter, then make Rebase's new PIT observation differ. The
    // reset notice is still observation-derived and must print for that real reset.
    runner("pitestEncoding").build()
    File(fixtureDir, "src/main/java/com/example/FakePit.java")
      .appendText("\n// changed timeout-retirement inputs\n")

    val rebased = runner("pitestEncodingBaselineRebase").build().output
    val rebaseWriteAt = rebased.indexOf("BaselineRebase retained all 1 accepted row(s)")
    val rebaseDriftAt = rebased.indexOf("line drift detected for 1 accepted key")
    assertTrue(
      rebaseWriteAt >= 0 && rebaseWriteAt < rebaseDriftAt &&
          rebased.contains("timeout-retirement execution inputs changed") &&
          rebased.contains("the quiet-run counter resets this run") &&
          rebased.contains("Run :pitestEncodingBaselineRetag") &&
          rebased.contains("1 line-drifted baseline key"),
      "successful Rebase hid its remaining Retag handoff:\n$rebased",
    )
    assertEquals(
      "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10\n",
      baselineFile().readText(),
      "Rebase must retain the line metadata it tells the operator to review",
    )

    // Union retains the old tag too. Its fresh observation has the same inputs, so
    // it repeats the outstanding handoff without inventing a quiet-counter reset.
    val union = baselineUnionRunner().build().output
    val unionWriteAt = union.indexOf("union added nothing new")
    val unionDriftAt = union.indexOf("line drift detected for 1 accepted key")
    assertTrue(
      unionWriteAt >= 0 && unionWriteAt < unionDriftAt &&
          union.contains("Run :pitestEncodingBaselineRetag") &&
          union.contains("1 line-drifted baseline key"),
      "successful Union hid its remaining Retag handoff:\n$union",
    )
    assertFalse(
      union.contains("timeout-retirement execution inputs changed"),
      "a same-input writer invented a quiet-counter reset:\n$union",
    )

    val retagged = baselineRetagRunner().build().output
    assertTrue(retagged.contains("retag refreshed 1 matched row line tag(s)"), retagged)
    assertFalse(
      retagged.contains("line-drifted baseline key"),
      "successful Retag was presented as still owing Retag:\n$retagged",
    )
  }

  @Test
  fun `retag refuses fresh debt before promising a pre-write drift diff`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val accepted = "com.example.Codec,encode,MathMutator,SURVIVED # family # line 10\n"
    baselineFile().writeText(accepted)
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,33,SURVIVED,none",
      ),
      "",
    )

    val refused = baselineRetagRunner().buildAndFail().output
    assertTrue(refused.contains("refusing :pitestEncodingBaselineRetag"), refused)
    assertFalse(
      refused.contains("Pre-write notice:") || refused.contains("inspect the resulting diff"),
      "a refused retag promised a diff it did not write:\n$refused",
    )
    assertEquals(accepted, baselineFile().readText())
  }

  @Test
  fun `a regressed sibling mutant is flagged even at an accepted coordinate`() {
    // Two mutants share one (class, method, line, mutator) coordinate — a compound
    // condition's operands. One is accepted; when the other regresses from killed to
    // survived, the row TEXT already exists in the baseline, and only multiset
    // comparison notices the second copy.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,12,RemoveConditionalMutator_EQUAL_IF,SURVIVED\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_ELSE,encode,12,KILLED,com.example.CodecTest.[engine:junit-jupiter]/[class:com.example.CodecTest]/[method:encodesTheBoundary()]",
        // a DynamicTest killer, as the generated seed replay tests report since 423c900
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_ORDER_IF,encode,12,KILLED,com.example.CodecSeedReplayTest.[engine:junit-jupiter]/[class:com.example.CodecSeedReplayTest]/[test-factory:replaysSeedCorpus()]/[dynamic-test:#2]"
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("1 unkilled mutant(s) not in the accepted baseline"),
      "the regressed sibling was absorbed by its accepted twin:\n$output"
    )
    // the killed sibling at the same coordinate names its test, so the survivor's
    // branch direction can be inferred
    assertTrue(
      output.contains("detected sibling at this line: RemoveConditionalMutator_EQUAL_ELSE KILLED by encodesTheBoundary; " +
          "RemoveConditionalMutator_ORDER_IF KILLED by replaysSeedCorpus#2"),
      "sibling hint missing or a dynamic-test killer printed bare:\n$output"
    )
  }

  @Test
  fun `the listUnkilled listing carries the detected-sibling hint`() {
    // The hint names the killed twin's test at a survivor's coordinate — and it must
    // appear on the -PlistUnkilled surface, where triage actually reads rows, not only
    // on ratchet failures (casebook: the sibling guessed wrong three times).
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,RemoveConditionalMutator_EQUAL_IF,SURVIVED # untriaged\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_ELSE,encode,12,KILLED,com.example.CodecTest.[engine:junit-jupiter]/[class:com.example.CodecTest]/[method:encodesTheBoundary()]"
      ),
      """
        <mutation status="SURVIVED" detected="false">
          <sourceFile>Codec.java</sourceFile>
          <mutatedClass>com.example.Codec</mutatedClass>
          <mutatedMethod>encode</mutatedMethod>
          <lineNumber>12</lineNumber>
          <mutator>org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF</mutator>
          <description>removed conditional - replaced equality check with true</description>
        </mutation>
      """.trimIndent()
    )

    val output = runner("pitestEncodingVerify", "-PlistUnkilled").build().output
    assertTrue(output.contains("pitest 'encoding' unkilled:"), "-PlistUnkilled listing missing:\n$output")
    assertTrue(
      output.contains("detected sibling at this line: RemoveConditionalMutator_EQUAL_ELSE KILLED by encodesTheBoundary"),
      "sibling hint missing from the listUnkilled listing:\n$output"
    )
  }

  @Test
  fun `duplicate sibling rows in the baseline are matched per copy`() {
    // Both siblings accepted as two identical rows: a report with both must pass,
    // and an update must preserve both copies.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,RemoveConditionalMutator_EQUAL_IF,SURVIVED\n" +
          "com.example.Codec,encode,12,RemoveConditionalMutator_EQUAL_IF,SURVIVED\n"
    )
    val siblingRow =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_IF,encode,12,SURVIVED,none"
    writeReport(listOf(siblingRow, siblingRow), "")

    runner("pitestEncodingVerify").build()

    baselineUpdateRunner().build()
    val rows = baselineFile().readLines().filter { it.isNotBlank() }
    assertEquals(2, rows.size, "the refresh collapsed sibling rows:\n$rows")
  }

  @Test
  fun `a newly covered mutant is triage, not churn`() {
    // Status changed at one coordinate: a test now reaches a mutant that was
    // previously unreached. That looks like one-stale-one-new in a raw diff but is
    // the opposite of refresh material — refreshing would launder a fresh survivor
    // into the baseline.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,12,MathMutator,NO_COVERAGE\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("(newly covered — was NO_COVERAGE; triage, not a refresh)"),
      "newly-covered classification missing:\n$output"
    )
    assertTrue(
      output.contains("churn: 1 newly covered, 0 unexplained (of 1 new; 1 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(
      output.contains("newly covered rather than new code"),
      "triage-not-refresh hint missing:\n$output"
    )
  }

  @Test
  fun `a uniform shift with mixed statuses is a non-event`() {
    // The old drift-collision scenario — a +5 shift landing a SURVIVED row on
    // the exact line where a NO_COVERAGE row sat — needed a dedicated classifier
    // when lines were identity. Line-less keys dissolve it: both keys are unchanged,
    // so there is nothing to classify and nothing to refresh.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,230,MathMutator,SURVIVED\n" +
          "com.example.Codec,encode,235,MathMutator,NO_COVERAGE\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,235,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,240,NO_COVERAGE,none"
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertFalse(output.contains("unkilled mutant(s) not in the accepted baseline"), output)
    assertFalse(output.contains("stale entries"), "a pure move must not read as stale:\n$output")
    assertFalse(output.contains("newly covered — was"), "flip reading applied to a pure move:\n$output")
    // Both status-specific keys moved (230 -> 235 and 235 -> 240): advisory only.
    assertTrue(
      output.contains("line drift detected for 2 accepted keys") &&
          output.contains(
            "com.example.Codec,encode,MathMutator,NO_COVERAGE # " +
                "recorded tag line(s): 235; unmatched observed line(s): 240"
          ) &&
          output.contains(
            "com.example.Codec,encode,MathMutator,SURVIVED # " +
                "recorded tag line(s): 230; unmatched observed line(s): 235"
          ),
      "line-drift advisory missing:\n$output"
    )
  }

  @Test
  fun `malformed baseline rows are named and block a refresh`() {
    // A wrong-field-count row parses into a key no mutant can match: it read as
    // "since killed" and the next refresh silently dropped it — the timeout
    // membership's malformed-row diagnosis, applied to the file it always
    // should have covered too.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # line 10\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    bindLegacyFixtureRecord()
    val baselineBefore = baselineFile().readText() + "com.example.Codec,encode\n"
    baselineFile().writeText(baselineBefore)

    val checking = runner("pitestEncodingVerify").build().output
    assertTrue(
      checking.contains("1 malformed row(s) in encoding-accepted.csv"),
      "malformed row not named:\n$checking"
    )
    // Debt diagnoses the same row on the same terms — it is the quick read-only
    // view of these files — and its label breakdown excludes it, so the
    // two surfaces report the same row count
    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains("1 malformed row(s) in encoding-accepted.csv"),
      "Debt did not name the malformed row:\n$debt"
    )
    assertTrue(checking.contains("  com.example.Codec,encode"), checking)
    assertFalse(
      checking.contains("BaselinePrune classifier marks exactly these row(s)"),
      "a malformed row still read as a prune candidate:\n$checking"
    )

    val refused = rawBaselinePruneRunner().buildAndFail().output
    assertTrue(refused.contains("Fix the row shape first"), refused)
    assertEquals(baselineBefore, baselineFile().readText(), "a refresh dropped the malformed row")

    val refusedRetag = baselineRetagRunner().buildAndFail().output
    assertTrue(refusedRetag.contains("Fix the row shape first"), refusedRetag)
    assertEquals(baselineBefore, baselineFile().readText(), "retag dropped the malformed row")
  }

  @Test
  fun `a baseline line range is not folded into its label and blocks writers`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance # line 10\n"
    )
    File(fixtureDir, "config/pitest/README.md").writeText("# flip insurance\n\nMeasured mode flip.\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,10,SURVIVED,none"
      ),
      ""
    )
    bindLegacyFixtureRecord()
    val baselineBefore =
      "com.example.Codec,encode,MathMutator,SURVIVED " +
          "# flip insurance # lines 10-30 # line 10\n"
    baselineFile().writeText(baselineBefore)
    val checking = runner("pitestEncodingVerify").build().output
    assertTrue(
      checking.contains("1 accepted row(s) carry invalid diagnostic line metadata") &&
          checking.contains("invalid line metadata '# lines 10-30'") &&
          checking.contains("ranges and out-of-range numbers are not valid"),
      "the accepted range did not receive a targeted diagnostic:\n$checking"
    )
    assertFalse(
      checking.contains("label(s) with no argument"),
      "the invalid range leaked into the flip-insurance label:\n$checking"
    )

    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains("1 accepted row(s) in encoding-accepted.csv carry invalid diagnostic line metadata"),
      "Debt did not diagnose the same range:\n$debt"
    )

    val refused = baselineRetagRunner().buildAndFail().output
    assertTrue(refused.contains("fix the invalid diagnostic line metadata"), refused)
    assertEquals(baselineBefore, baselineFile().readText(), "Retag rewrote invalid metadata")
  }

  @Test
  fun `an indented comment is prose not a phantom row and survives a rewrite`() {
    // '  # ...' passed the column-0 comment filter and parsed as a row matching
    // nothing — phantom since-killed debt. Recognized as a comment now; and since
    // BaselineDocument keeps it in place while replacing only the row slot.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "  # hand-written context for the row below\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n"
    )
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )

    val checking = runner("pitestEncodingVerify").build().output
    assertFalse(
      checking.contains("BaselinePrune classifier marks exactly these row(s)"),
      "an indented comment read as a phantom row:\n$checking"
    )

    val updated = baselineUpdateRunner().build().output
    assertFalse(updated.contains("do not survive"), updated)
    assertEquals(
      listOf(
        "  # hand-written context for the row below",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 10",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the rewrite dropped non-row evidence"
    )
  }

  @Test
  fun `a sibling of an accepted identical row is surfaced, not unexplained`() {
    // Upgrading a set-based baseline materializes sibling mutants the old comparison
    // collapsed: a "new" row identical to an accepted row is pre-existing debt made
    // visible, and the failure must say so instead of reporting it unexplained.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,10,MathMutator,SURVIVED\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("(shares an accepted key — sibling debt surfaced, or a NEW mutant at that key; check the line)"),
      "sibling hint missing:\n$output"
    )
    assertTrue(
      output.contains("churn: 0 newly covered, 1 surfaced sibling(s), 0 unexplained (of 1 new; 0 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(output.contains("the line-less key's documented blind spot"), output)
  }

  @Test
  fun `an update carries a note across a status flip, marked with the flip`() {
    // Accepting a newly covered mutant goes through pitestEncodingBaselineUpdate: the old
    // NO_COVERAGE row is dropped and a SURVIVED row written at the same coordinate.
    // The dropped row's note must travel — marked with the flip it crossed, because an
    // acceptance written for an unreached mutant deserves a re-read once a test can
    // observe its behaviour. A row whose status did not change keeps its note verbatim.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,NO_COVERAGE # unreachable without a decoder fixture\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = baselineUpdateRunner().build().output
    // both report mutants share the SURVIVED key: line affinity hands the
    // '# untriaged' pair (recorded at 20) to the line-20 mutant, and the line-12
    // mutant inherits the dropped NO_COVERAGE row's note via the flip carry — the
    // carried note lands exactly where its mutant was recorded
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # unreachable without a decoder fixture (carried across NO_COVERAGE -> SURVIVED) # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 20",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("1 note(s) carried across a status flip"), output)
    // the dropped listing names the note's fate, so a carried note reads as such
    assertTrue(output.contains("status changed; acceptance note carried"), output)
    assertFalse(output.contains("note dropped with the row"), output)

    // idempotent: a second update with no flips leaves both notes untouched
    baselineUpdateRunner().build()
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # unreachable without a decoder fixture (carried across NO_COVERAGE -> SURVIVED) # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 20",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
  }

  @Test
  fun `a line shift needs no carry — an update only rewrites the tags`() {
    // Editing above a mutated method used to drop the old line's row and write the
    // new line's, with a whole carry apparatus keeping the note attached (casebook:
    // the note the line shift dropped). With line-less keys the shifted mutant IS
    // its accepted row: an update finds exact matches, keeps every note verbatim —
    // labeled, '# untriaged' and bare alike — and only the '# line' tags change.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # sibling operand, same documented family\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,decode,40,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,13,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,21,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,41,SURVIVED,none",
      ),
      ""
    )

    val output = baselineUpdateRunner().build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # sibling operand, same documented family # line 13",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 21",
        "com.example.Codec,decode,MathMutator,SURVIVED # line 41",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    val expectedUpdateNotice =
      """
      pitest baseline 'encoding': line drift detected for 2 accepted keys; no recorded '# line' tag names an observed unkilled-mutant line:
        com.example.Codec,decode,MathMutator,SURVIVED # recorded tag line(s): 40; unmatched observed line(s): 41
        com.example.Codec,encode,MathMutator,SURVIVED # recorded tag line(s): 12, 20; unmatched observed line(s): 13, 21
        Review: Re-read the README acceptance argument; the accepted code may have moved, or a new mutant may sit under an old acceptance (the same-key swap).
        Pre-write notice: The selected :pitestEncodingBaselineUpdate is a complete report rewrite. If only metadata should change, use :pitestEncodingBaselineRetag instead.
      """.trimIndent()
    assertTrue(
      output.contains(expectedUpdateNotice),
      "update refreshed line metadata without first reporting the drift:\n$output",
    )
    assertTrue(output.contains("wrote 3 accepted entries"), output)
    // nothing was dropped, seeded or flipped: a shift is no longer churn at all
    assertFalse(output.contains("seeded '# untriaged'"), output)
    assertFalse(output.contains("carried across a status flip"), output)
    assertFalse(output.contains("dropped"), output)

    // idempotent: a second update rewrites the identical file
    baselineUpdateRunner().build()
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # sibling operand, same documented family # line 13",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 21",
        "com.example.Codec,decode,MathMutator,SURVIVED # line 41",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
  }

  @Test
  fun `a new sibling at an accepted key is seen by its count and seeded untriaged`() {
    // The line-less key's honest residue: a new mutant of an accepted key is visible
    // only as a count change. The multiset comparison must fail on the extra copy
    // (never absorb it), and an update must seed it '# untriaged' — an accepted
    // twin's argument was written for the mutants it had, not for one more.
    // Previously unlabeled rows stay bare through the same refresh.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,13,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,30,SURVIVED,none",
      ),
      ""
    )

    val failed = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      failed.contains("churn: 0 newly covered, 1 surfaced sibling(s), 0 unexplained (of 1 new; 0 stale)"),
      "the extra copy must fail the ratchet:\n$failed"
    )

    val output = baselineUpdateRunner().build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # line 13",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 20",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 30",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("1 new row(s) seeded '# untriaged'"), output)
  }

  @Test
  fun `a same-key swap is invisible — the documented blind spot, held deliberately`() {
    // Kill one mutant and introduce a new one at the same class/method/mutator/status
    // in one change, and the multiset is unchanged: the new mutant silently inherits
    // the old row's acceptance. This was only ever heuristically covered when lines
    // were identity (the PAIRING OUTLIER dominant-delta scan); the line-less key
    // trades the heuristic for an explicitly documented hole (HARDENING.md). This
    // test pins the trade so a future change to it is deliberate: the verify passes,
    // and the only trace is the line-drift advisory when the recorded anchor no
    // longer matches any observed line.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # boundary equivalence # line 150\n")
    // the line-150 mutant was killed; an unrelated MathMutator survivor appeared at 157
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,157,SURVIVED,none"),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertFalse(output.contains("unkilled mutant(s) not in the accepted baseline"), output)
    assertTrue(
      output.contains("line drift detected for 1 accepted key") &&
          output.contains("recorded tag line(s): 150; unmatched observed line(s): 157"),
      "the swap's only trace is the line-drift advisory:\n$output"
    )
  }

  @Test
  fun `union tags an added smaller-line sibling with its own line`() {
    // Blind oldest-first pool consumption handed the existing row the new
    // sibling's smaller line: the appended row was tagged with the existing
    // mutant's line, the sibling's real line was recorded nowhere, and the next
    // verify raised the same-key-swap advisory against a file union itself wrote.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # race guard # line 40\n")
    fun mutant(line: Int) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,SURVIVED,none"
    writeReport(listOf(mutant(10), mutant(40)), "")

    val union = baselineUnionRunner().build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # race guard # line 40",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the existing row must consume its own tagged line; the added row takes the unclaimed one"
    )

    // the rewritten file describes this run completely: no drift advisory
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("unmatched observed line(s):"),
      "union armed the same-key-swap advisory it exists to avoid:\n$settled"
    )
  }

  @Test
  fun `a multi-line insurance tag cannot steal the only copy of a sibling's exact line`() {
    // Flip-insurance rows name every line their family was observed at, so a
    // greedy first-match walk let the insurance row consume the single pool copy
    // of a line its sibling tags exactly — the robbed sibling fell to blind
    // oldest-first consumption, ate the genuinely new smallest line, and the
    // appended row was tagged with an old mutant's line while the new line went
    // recorded nowhere. Maximum exact-line assignment gives the narrow sibling
    // its line and leaves the insurance row its other anchor.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED, solo=KILLED) # lines 20, 40\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # line 20\n"
    )
    fun mutant(line: Int) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$line,SURVIVED,none"
    writeReport(listOf(mutant(10), mutant(20), mutant(40)), "")

    val union = baselineUnionRunner().build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED, solo=KILLED) # lines 20, 40",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 20",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the appended row must take the genuinely unclaimed line"
    )

    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("unmatched observed line(s):"),
      "union armed the same-key-swap advisory it exists to avoid:\n$settled"
    )
  }

  @Test
  fun `a coordinate holding both a survivor and a timeout is not a flip every run`() {
    // The status stash is keyed line-lessly, so an accepted survivor and an audited
    // timeout routinely share one coordinate. Set logic ("timed out now, survived
    // before") reports that key as a SURVIVED -> TIMED_OUT flip on every run
    // forever, including runs where nothing moved — which is how the one advisory
    // that is meant to stop a reviewer becomes the one they filter out.
    writeFixture()
    val mutator = "org.pitest.mutationtest.engine.gregor.mutators.MathMutator"
    // one key, three mutants: the audited timeout at line 12, two accepted
    // survivors at 20 and 24 — exactly the shape json-iterator's baselines carry
    fun report(vararg statusesByLine: Pair<Int, String>) = writeReport(
      statusesByLine.map { (line, status) ->
        "Codec.java,com.example.Codec,$mutator,encode,$line,$status," +
            (if (status == "KILLED") "com.example.CodecTest" else "none")
      },
      ""
    )
    val stable = arrayOf(12 to "TIMED_OUT", 20 to "SURVIVED", 24 to "SURVIVED")
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # line 20\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # line 24\n"
    )

    val flip = "flipped SURVIVED -> TIMED_OUT"
    // first run seeds the stash; the second and third compare against a run that
    // was identical, so nothing moved and nothing may be reported
    report(*stable)
    runner("pitestEncodingVerify").build()
    repeat(2) {
      report(*stable)
      val steady = runner("pitestEncodingVerify").build().output
      assertFalse(steady.contains(flip), "an unchanged population reported a flip:\n$steady")
      assertFalse(
        steady.contains("timed-out drift vs previous run"),
        "an unchanged population reported drift:\n$steady"
      )
    }

    // the real signal still fires at the same key: a survivor became a timeout, so
    // the timeout count rose while the survivor count fell
    report(12 to "TIMED_OUT", 20 to "TIMED_OUT", 24 to "SURVIVED")
    val real = runner("pitestEncodingVerify").build().output
    assertTrue(real.contains("1 coordinate(s) $flip"), "a real flip went unreported:\n$real")
    assertTrue(
      real.contains("  com.example.Codec,encode,MathMutator"),
      "the flipped coordinate was not named:\n$real"
    )

    // and the benign flavour stays out of the warning: KILLED mutants timing out
    // leave the survivor count alone, so they are drift, not a flip — counted as
    // mutants, not keys, so two new timeouts at one key read as 2
    report(12 to "TIMED_OUT", 20 to "TIMED_OUT", 24 to "SURVIVED", 30 to "TIMED_OUT", 31 to "TIMED_OUT")
    val benign = runner("pitestEncodingVerify").build().output
    assertFalse(benign.contains(flip), "a KILLED -> TIMED_OUT move reported as a flip:\n$benign")
    assertTrue(
      benign.contains("2 newly timed out (previously detected)"),
      "the benign flavour was not counted as mutant drift:\n$benign"
    )
  }

  @Test
  fun `a killed sibling's note dies with its own row, by line affinity`() {
    // Two same-key rows, one noted; the noted mutant (recorded at line 12) was
    // killed. Within a key the update assigns accepted rows by line affinity, so the
    // surviving line-20 mutant takes the pair recorded at 20 and the noted line-12
    // pair drops — with its note's fate named. Without recorded lines the assignment
    // would be file-order-arbitrary; the tags written by an update or a green prune
    // are what keep this determinate.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # killed since; this note must not travel # line 12\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # line 20\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = baselineUpdateRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    // the loss is loud: the dropped listing names the note's fate and counts it
    assertTrue(output.contains("— note dropped with the row"), output)
    assertTrue(output.contains("1 note(s) dropped with their rows"), output)
    assertFalse(output.contains("— note carried"), output)
  }

  @Test
  fun `a mutant that moved methods is unexplained, not a shift`() {
    // An extract-method refactor changes the pairing key: the stale row's mutant now
    // lives in a different method, so it must land in the unexplained tally for
    // re-triage at its new home — pairing it as a shift would advise a refresh that
    // skips the re-triage.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,10,MathMutator,SURVIVED\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encodeChecked,12,SURVIVED,none"),
      ""
    )

    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(
      output.contains("churn: 0 newly covered, 1 unexplained"),
      "moved-method row must be unexplained:\n$output"
    )
    assertFalse(
      output.contains("newly covered — was"),
      "must not pair across methods as a flip:\n$output"
    )
  }

  @Test
  fun `a reviewed method rename uses union then prune without automatic argument carry`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,10,MathMutator,SURVIVED # encoding family\n"
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "# encoding family\n\nThe reviewed encoding property is unchanged by a pure method rename.\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encodeChecked,12,SURVIVED,none",
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator," +
            "newHelper,30,SURVIVED,none"
      ),
      ""
    )
    bindLegacyFixtureRecord()

    val gate = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      gate.contains("For an intentional same-suite key move or method rename") &&
          gate.contains("pitestEncodingBaselineUnion is phase one") &&
          gate.contains("only onto a generated row that review proves is its replacement") &&
          gate.contains("leave every other new row '# untriaged'") &&
          gate.contains("ordinary fresh preview") &&
          gate.contains("BaselineRetag changes line metadata, not keys") &&
          gate.contains("do not substitute the complete-rewrite BaselineUpdate"),
      "a concurrent fresh/stale gate did not explain the reviewed re-key flow:\n$gate"
    )
    assertTrue(
      gate.contains("No removal task is eligible while this report also contains fresh gated rows") &&
          gate.contains("Immediate next action: resolve every new row") &&
          gate.contains("do not run Prune or Retag while they remain gated"),
      "a concurrent fresh/stale gate did not suppress removal guidance:\n$gate",
    )
    assertFalse(
      gate.contains("./gradlew :pitestEncoding -PnoMutationHistory") ||
          gate.contains("./gradlew :pitestEncodingBaselinePrune"),
      "a concurrent fresh/stale gate advertised a removal command:\n$gate",
    )
    assertFalse(
      gate.contains("label(s) with no argument"),
      "the seeded source argument was reported as orphaned:\n$gate"
    )

    val union = baselineUnionRunner().build().output
    assertTrue(union.contains("union added 2 entries"), union)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # encoding family # line 10",
        "com.example.Codec,encodeChecked,MathMutator,SURVIVED # untriaged # line 12",
        "com.example.Codec,newHelper,IncrementsMutator,SURVIVED # untriaged # line 30"
      ),
      baselineFile().readLines(),
      "Union must not infer that the old method's argument belongs to either new key"
    )

    // This edit represents the review the tool cannot perform: the source change proves
    // which argument belongs at the new key before the old key is retired.
    baselineFile().writeText(
      baselineFile().readText().replace(
        "encodeChecked,MathMutator,SURVIVED # untriaged # line 12",
        "encodeChecked,MathMutator,SURVIVED # encoding family # line 12"
      )
    )

    assertTrue(
      baselineFile().readText().contains(
        "newHelper,IncrementsMutator,SURVIVED # untriaged # line 30"),
      "reviewing the rename carried its argument onto unrelated fresh debt"
    )
    // The unrelated survivor is killed independently before the second observation.
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encodeChecked,12,SURVIVED,none",
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator," +
            "newHelper,30,KILLED,com.example.CodecTest"
      ),
      ""
    )

    val preview = runner("pitestEncoding", "-PnoMutationHistory").build().output
    assertTrue(
      preview.contains("2 rows / 2 unique keys are unmatched by this run") &&
          preview.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          preview.contains(
            "com.example.Codec,encode,MathMutator,SURVIVED # encoding family # line 10") &&
          preview.contains(
            "com.example.Codec,newHelper,IncrementsMutator,SURVIVED # untriaged # line 30"),
      "the second fresh observation did not expose the complete pre-Prune decision:\n$preview"
    )

    val prune = baselinePruneRunner().build().output
    assertTrue(prune.contains("prune dropped 2 rows / 2 unique keys"), prune)
    assertTrue(
      prune.contains("com.example.Codec,encode,MathMutator,SURVIVED # encoding family # line 10"),
      prune
    )
    assertEquals(
      listOf(
        "com.example.Codec,encodeChecked,MathMutator,SURVIVED # encoding family # line 12"
      ),
      baselineFile().readLines(),
      "Prune must retain the reviewed argument at the generated new key"
    )

    val settled = runner("pitestEncoding").build().output
    assertFalse(settled.contains("label(s) with no argument"), settled)
  }

  @Test
  fun `union appends without dropping, idempotently, and update names what it drops`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,decode,5,MathMutator,SURVIVED # retired decoder family\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none"),
      ""
    )

    val unboundGate = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      unboundGate.contains("pitestEncodingBaselineRebase") &&
          unboundGate.contains("preserves every old row") &&
          unboundGate.contains("binds the reviewed provenance"),
      "an unbound record was given a writer that must refuse it:\n$unboundGate",
    )
    assertFalse(
      unboundGate.contains("after documenting each acceptance run :pitestEncodingBaselineUnion"),
      unboundGate,
    )

    // Bind the old row against the helper's empty gated observation. The next gate
    // is the ordinary current-provenance path, so its displayed Union command must
    // execute directly rather than relying on a hidden Rebase in the runner helper.
    bindLegacyFixtureRecord()
    val gate = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      gate.contains("after documenting each acceptance run :pitestEncodingBaselineUnion") &&
          gate.contains("without removing unmatched evidence") &&
          gate.contains("BaselineUpdate is a complete report rewrite, not remediation"),
      "incremental gate did not point at the additive writer:\n$gate",
    )

    val beforeRetag = baselineFile().readText()
    val refusedRetag = baselineRetagRunner().buildAndFail().output
    assertTrue(
      refusedRetag.contains("refusing :pitestEncodingBaselineRetag") &&
          refusedRetag.contains("1 gated mutant(s)") &&
          refusedRetag.contains("line metadata cannot hide fresh debt"),
      refusedRetag,
    )
    assertEquals(beforeRetag, baselineFile().readText(), "a refused retag changed accepted identity")

    val union = baselineUnionRunner().build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    // the rewrite migrates the legacy row; the added row remains explicit triage debt
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # retired decoder family # line 5",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12"
      ),
      baselineFile().readLines(),
      "union must keep the absent row, its note, and append the new row in sorted order"
    )

    val idempotent = baselineUnionRunner().build()
    assertTrue(idempotent.output.contains("union added nothing new"), idempotent.output)

    val update = baselineUpdateRunner().build()
    assertTrue(update.output.contains("removed or transitioned 1 row(s)"), update.output)
    assertTrue(update.output.contains("com.example.Codec,decode,MathMutator,SURVIVED # retired decoder family # line 5"), update.output)
    assertTrue(update.output.contains("coordinate absent from this PIT report"), update.output)
    assertTrue(update.output.contains("not an observed kill"), update.output)
    assertFalse(
      update.output.contains("pitestEncodingBaselineUnion"),
      "an unrelated removal advertised flip-insurance union:\n${update.output}",
    )
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12"),
      baselineFile().readLines(),
      "update rewrites from this run only"
    )
  }

  @Test
  fun `an update seeds new rows untriaged and never relabels accepted or sibling rows`() {
    // A genuinely new coordinate enters the baseline as explicit debt, never bare —
    // triage means replacing the seeded label. Pre-existing rows keep their state:
    // a labeled row keeps its label (surfaced sibling copies included, since notes
    // are keyed by row text), and an existing bare row stays bare rather than
    // being retroactively branded debt it may not be.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # race guard family\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,33,SURVIVED,none",
      ),
      ""
    )

    val output = baselineUpdateRunner().build().output
    // line affinity keeps '# race guard family' on its recorded line-12 mutant; the
    // second line-12 mutant is a new sibling and seeds '# untriaged' (its twin's
    // argument was written for the mutants it had), as does the new decode key
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # race guard family # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 20",
        "com.example.Codec,decode,MathMutator,SURVIVED # untriaged # line 33",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("2 new row(s) seeded '# untriaged'"), output)
    // the interrupted-refresh guard: the atomic write leaves no temp file behind
    assertFalse(File(baselineFile().parentFile, "${baselineFile().name}.tmp").exists())

    // idempotent: a second update seeds nothing and changes nothing
    val second = baselineUpdateRunner().build().output
    assertFalse(second.contains("seeded '# untriaged'"), second)
  }

  @Test
  fun `the drift advisory is row-level - a sibling at an unrecorded line is named`() {
    // Key-level disjointness kept this quiet: one sibling still sits at a recorded
    // line, so the set intersects. But counts match and every row is tagged, and the
    // second mutant sits at a line no tag names — the same-key swap's exact shape.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # boundary pair # line 53\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # boundary pair # line 92\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,53,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,157,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("line drift detected for 1 accepted key") &&
          output.contains(
              "recorded tag line(s): 53, 92; unmatched observed line(s): 157"),
      "row-level drift advisory missing:\n$output"
    )
  }

  @Test
  fun `baseline schema migration is explicit idempotent and reversible`() {
    // Format-only: no report, no PIT run. Migration stamps schema 1 and canonicalizes
    // N-1 rows while comments, blanks, ordering, and duplicate siblings survive.
    // Downgrade then removes only the marker, producing an N-1-readable rollback.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "# seeded 2026-07-27; see README\n" +
          "\n" +
          "com.example.Codec,encode,12,MathMutator,SURVIVED # race guard family\n" +
          "  # duplicate sibling evidence follows\n" +
          "com.example.Codec,encode,20,MathMutator,NO_COVERAGE\n" +
          "com.example.Codec,encode,20,MathMutator,NO_COVERAGE\n"
    )

    val output = runner("migrateMutationBaselines").build().output
    assertTrue(
      output.contains("migrated to accepted-baseline schema 1; canonicalized 3 row(s)"),
      output,
    )
    val migrated =
      "!sava-hardening-baseline-schema,1\n" +
          "# seeded 2026-07-27; see README\n" +
          "\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # race guard family # line 12\n" +
          "  # duplicate sibling evidence follows\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # line 20\n" +
          "com.example.Codec,encode,MathMutator,NO_COVERAGE # line 20\n"
    assertEquals(migrated, baselineFile().readText())

    // idempotent, and byte-identical files are not rewritten
    val second = runner("migrateMutationBaselines").build().output
    assertTrue(
      second.contains("Configuration cache entry reused."),
      "migrateMutationBaselines did not reuse the fixture's configuration cache:\n$second",
    )
    assertTrue(second.contains("already at accepted-baseline schema 1"), second)
    assertEquals(migrated, baselineFile().readText())

    val rollback = runner("downgradeMutationBaselines").build().output
    assertTrue(rollback.contains("removed accepted-baseline schema marker for N-1 rollback"), rollback)
    assertEquals(migrated.substringAfter('\n'), baselineFile().readText())

    val already = runner("downgradeMutationBaselines").build().output
    assertTrue(already.contains("already unversioned (N-1-readable)"), already)
  }

  @Test
  fun `every accepted-baseline reader refuses an unknown schema`() {
    writeFixture()
    val killed =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,10,KILLED,com.example.CodecTest"
    writeReport(listOf(killed), "")
    modeSnapshot("solo")
    writeReport(listOf(killed), "")
    modeSnapshot("gate")
    writeReport(listOf(killed), "")

    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "!sava-hardening-baseline-schema,2\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n",
    )

    listOf(
      listOf("pitestEncodingVerify"),
      listOf("pitestEncodingDebt"),
      listOf("pitestModeCompare"),
      listOf("migrateMutationBaselines"),
      listOf("downgradeMutationBaselines"),
    ).forEach { arguments ->
      val output = runner(*arguments.toTypedArray()).buildAndFail().output
      assertTrue(
        output.contains("unsupported accepted-baseline schema '2'"),
        "${arguments.first()} interpreted an unknown baseline schema:\n$output",
      )
    }
  }

  @Test
  fun `malformed duplicate and misplaced schema headers are loud`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val row = "com.example.Codec,encode,MathMutator,SURVIVED # line 10"
    val invalid = mapOf(
      "malformed accepted-baseline schema header" to
          "!sava-hardening-baseline-schema 1\n$row\n",
      "duplicate accepted-baseline schema header" to
          "!sava-hardening-baseline-schema,1\n!sava-hardening-baseline-schema,1\n$row\n",
      "schema header must be the first line" to
          "# evidence\n!sava-hardening-baseline-schema,1\n$row\n",
    )
    invalid.forEach { (diagnosis, content) ->
      baselineFile().writeText(content)
      val output = runner("migrateMutationBaselines").buildAndFail().output
      assertTrue(output.contains(diagnosis), "missing '$diagnosis':\n$output")
      assertEquals(content, baselineFile().readText(), "a refused migration changed the baseline")
    }
  }

  @Test
  fun `ordinary rewrites preserve legacy schema while new baselines start current`() {
    writeFixture()
    val survivor =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,10,SURVIVED,none"
    writeReport(listOf(survivor), "")

    baselineUpdateRunner().build()
    assertEquals(
      listOf(
        "!sava-hardening-baseline-schema,1",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10",
      ),
      baselineFile().readLines(),
      "a newly created baseline was not explicitly versioned",
    )

    val legacy =
      "# repository evidence\n" +
          "\n" +
          "com.example.Codec,encode,10,MathMutator,SURVIVED # accepted family\n"
    baselineFile().writeText(legacy)
    baselineUpdateRunner().build()
    assertEquals(
      "# repository evidence\n" +
          "\n" +
          "com.example.Codec,encode,MathMutator,SURVIVED # accepted family # line 10\n",
      baselineFile().readText(),
      "an ordinary rewrite stamped or discarded material from an existing N-1 document",
    )
  }

  @Test
  fun `update union and prune preserve comments blanks and duplicate row slots`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val evidenceBefore = "# before\n\n"
    val evidenceBetween = "  # between siblings\n"
    val evidenceAfter = "\n# after\n"
    baselineFile().writeText(
      evidenceBefore +
          "com.example.Codec,encode,MathMutator,SURVIVED # first # line 10\n" +
          evidenceBetween +
          "com.example.Codec,encode,MathMutator,SURVIVED # second # line 20\n" +
          evidenceAfter,
    )
    fun survivor(line: Int) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,$line,SURVIVED,none"
    fun assertEvidenceSurvived(operation: String) {
      val text = baselineFile().readText()
      assertTrue(text.startsWith(evidenceBefore), "$operation moved or dropped leading evidence:\n$text")
      assertTrue(text.contains(evidenceBetween), "$operation dropped inter-row evidence:\n$text")
      assertTrue(text.endsWith(evidenceAfter), "$operation moved or dropped trailing evidence:\n$text")
    }

    writeReport(listOf(survivor(10), survivor(20)), "")
    baselineUpdateRunner().build()
    assertEvidenceSurvived("update")
    assertEquals(2, baselineFile().readLines().count { it.startsWith("com.example.Codec,") })

    writeReport(listOf(survivor(10), survivor(20), survivor(30)), "")
    baselineUnionRunner().build()
    assertEvidenceSurvived("union")
    assertEquals(3, baselineFile().readLines().count { it.startsWith("com.example.Codec,") })

    writeReport(listOf(survivor(10), survivor(20)), "")
    baselinePruneRunner().build()
    assertEvidenceSurvived("prune")
    assertEquals(2, baselineFile().readLines().count { it.startsWith("com.example.Codec,") })
  }

  @Test
  fun `a refresh with nothing unkilled writes no baseline file`() {
    // The fees wart: a zero-row update used to create a one-newline file, arming an
    // empty record where there was no record at all. Nothing to write means no file
    // — and a baseline whose every row was killed is removed, not left as a husk.
    writeFixture()
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest"),
      ""
    )

    val output = baselineUpdateRunner().build().output
    assertTrue(output.contains("nothing unkilled — no baseline to write"), output)
    assertFalse(baselineFile().exists(), "update created a baseline with nothing to record")

    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 10\n")
    val removed = baselineUpdateRunner().build().output
    assertTrue(removed.contains("nothing unkilled — baseline file removed"), removed)
    assertFalse(baselineFile().exists(), "an emptied baseline must be removed, not left as a husk")

    // prune behaves the same when it drops every row
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 10\n")
    val pruned = baselinePruneRunner().build().output
    assertTrue(pruned.contains("prune dropped every row unmatched by this run — baseline file removed"), pruned)
    assertFalse(baselineFile().exists(), "prune must remove an emptied baseline")

    // and none of these no-record runs may leave a version stamp behind
    assertFalse(
      File(fixtureDir, "config/pitest/encoding-pitest-version").isFile,
      "a refresh that ends with no record must not stamp"
    )
  }

  @Test
  fun `the version stamp retires with the record unless an audited set remains`() {
    // The stamp certifies the records in config/pitest. When a refresh ends with no
    // baseline file and no audited timeout set, an orphan stamp would refuse a
    // future first refresh across a PIT bump citing a baseline that no longer
    // exists — "no record and an empty record must read the same way" extends to
    // the stamp. An audited timeout set is still a record, so the stamp stays.
    writeFixture()
    val toolVersionFile = File(fixtureDir, "config/pitest/encoding-pitest-version")
    val toolchainFile = File(fixtureDir, "config/pitest/encoding-pitest-toolchain.tsv")
    baselineFile().parentFile.mkdirs()

    // a real write lands the stamp with the record
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    baselineUpdateRunner().build()
    assertTrue(baselineFile().isFile && toolVersionFile.isFile, "stamp must land with the write")

    // the suite goes fully detected: the baseline is removed and the stamp with it
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest"),
      ""
    )
    val retired = baselineUpdateRunner().build().output
    assertTrue(retired.contains("nothing unkilled — baseline file removed"), retired)
    assertTrue(
      retired.contains(
        "removed orphan mutation provenance: encoding-pitest-version, " +
            "encoding-pitest-toolchain.tsv",
      ),
      retired,
    )
    assertFalse(toolVersionFile.isFile, "orphan stamp left behind an emptied record")
    assertFalse(toolchainFile.isFile, "orphan toolchain stamp left behind an emptied record")

    // with an audited timeout set present, the stamp still has a record to certify
    File(fixtureDir, "config/pitest/encoding-timeouts.csv")
      .writeText("com.example.Codec,encode,MathMutator # cause:liveness line 12\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode`: the removed loop exit cannot make progress.\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    baselineUpdateRunner().build()
    assertTrue(toolVersionFile.isFile, "stamp must land with the write")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest"),
      ""
    )
    val kept = baselineUpdateRunner().build().output
    assertTrue(kept.contains("nothing unkilled — baseline file removed"), kept)
    assertTrue(toolVersionFile.isFile, "the audited timeout set is a record; its stamp must stay")
    assertTrue(toolchainFile.isFile, "the audited timeout set lost its toolchain stamp")
  }

  @Test
  fun `the verify prints a per-label baseline breakdown`() {
    // Triage state is a number the build prints: one count per label, with carry
    // markers and flip details stripped ('# race guard family (carried across ...)'
    // still counts as 'race guard family'), and bare rows named as
    // unlabeled rather than silently folded into a bucket.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,decode,33,MathMutator,SURVIVED # race guard family (carried across NO_COVERAGE -> SURVIVED)\n" +
          "com.example.Codec,decode,41,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,33,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,41,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains(
          "4 rows / 2 unique keys — 2 '# untriaged', 1 '# race guard family', 1 unlabeled"),
      output
    )
    assertTrue(output.contains("1 unlabeled row — triage state unknown"), output)
    assertTrue(output.contains("1 unlabeled baseline row(s) with unknown triage state"), output)
  }

  @Test
  fun `the verify names an all-unlabeled baseline instead of staying silent`() {
    // Bare rows can be argued acceptances or unfinished work. Neither their age nor
    // their triage state can be inferred from the missing label, even with a README.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    File(fixtureDir, "config/pitest/README.md")
        .writeText("# Triage\n\nCodec.encode has legacy acceptance arguments.\n")
    val originalBaseline = baselineFile().readText()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertTrue(output.contains("2 rows / 1 unique key — 2 unlabeled"), output)
    assertTrue(output.contains("2 unlabeled rows — triage state unknown"), output)
    assertTrue(output.contains("2 unlabeled baseline row(s) with unknown triage state"), output)
    assertFalse(output.contains("2 '# untriaged'"), output)

    val debt = runner("pitestEncodingDebt", "-PstrictTimeoutAudit").build().output
    assertTrue(debt.contains("baseline labels: 2 unlabeled"), debt)
    assertTrue(debt.contains("2 unlabeled rows — triage state unknown"), debt)
    assertFalse(debt.contains("2 '# untriaged'"), debt)
    assertEquals(originalBaseline, baselineFile().readText(), "read-only warnings must preserve bare rows")
  }

  @Test
  fun `the verify warns when a family label resolves to no README section`() {
    // A label is a pointer to its argument, and a per-label count cannot tell a typo
    // from triage — '1 race gaurd family' reads like closed work. Resolving each label
    // against the README is what makes the difference visible.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED # race guard family\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val warned = runner("pitestEncodingVerify").build().output
    // only the family label: '# untriaged' is the seeded-debt convention and argues
    // nothing, so it is never expected to have a section of its own
    assertTrue(
      warned.contains("config/pitest/README.md (a mention under a heading containing 'histor' or 'retired' does not count) — '# race guard family' — document the family"),
      warned
    )

    File(fixtureDir, "config/pitest/README.md").writeText(
      "# Triage\n\nRows labelled `# race guard family` are …\n"
    )
    val quiet = runner("pitestEncodingVerify").build().output
    assertFalse(quiet.contains("label(s) with no argument"), quiet)
    assertFalse(quiet.contains("triage state unknown"), quiet)
  }

  @Test
  fun `the debt listing resolves labels against the README too`() {
    // Debt is where a triager reads the per-label counts and picks the next cluster, so
    // an unresolvable label is named there as well as in the verify — same rule, one
    // implementation, so the two cannot disagree about which labels resolve.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # capacity hint\n" +
          "com.example.Codec,decode,33,MathMutator,NO_COVERAGE # untriaged\n"
    )

    val output = runner("pitestEncodingDebt").build().output
    assertTrue(output.contains("baseline labels: 1 '# capacity hint', 1 '# untriaged'"), output)
    assertTrue(
      output.contains("config/pitest/README.md (a mention under a heading containing 'histor' or 'retired' does not count) — '# capacity hint' — document the family"),
      output
    )

    File(fixtureDir, "config/pitest/README.md").writeText("# capacity hint\n\nGrowth arithmetic …\n")
    val quiet = runner("pitestEncodingDebt").build().output
    assertFalse(quiet.contains("label(s) with no argument"), quiet)
    assertFalse(quiet.contains("triage state unknown"), quiet)
  }
}
