package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the audited-timeout membership format. In-process and pure, like
 * [BaselineNotesTest]: the parse edge cases below (comment-only lines, trailing
 * commas, duplicate rows) are impractical to stage through a TestKit fixture, and the
 * functional tests already prove the verify and `Debt` reach this logic.
 */
class TimeoutAuditTest {

  @Test
  fun `parse strips comments, trims per field, and drops blank lines`() {
    val membership = TimeoutAudit.parse(listOf(
      "# header comment",
      "",
      "com.example.Codec,encode,MathMutator # line 12",
      "com.example.Codec, decode , IncrementsMutator",
    ))
    assertEquals(
      setOf("com.example.Codec,encode,MathMutator", "com.example.Codec,decode,IncrementsMutator"),
      membership.members
    )
    assertEquals(emptyList<String>(), membership.malformed)
  }

  @Test
  fun `parse splits off rows without exactly three non-empty fields as malformed`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator",
      "com.example.Codec,encode",
      "com.example.Codec,encode,MathMutator,extra",
      "com.example.Codec,encode,",
    ))
    assertEquals(setOf("com.example.Codec,encode,MathMutator"), membership.members)
    assertEquals(
      listOf(
        "com.example.Codec,encode",
        "com.example.Codec,encode,MathMutator,extra",
        "com.example.Codec,encode,",
      ),
      membership.malformed
    )
  }

  @Test
  fun `parse collapses duplicate rows to one member`() {
    // a twice-pasted row is one member: the verify's set checks and Debt's cause
    // count must agree on that, so the collapse happens here, not per caller
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator",
      "com.example.Codec, encode, MathMutator # spacing is readability, not identity",
    ))
    assertEquals(setOf("com.example.Codec,encode,MathMutator"), membership.members)
  }

  @Test
  fun `parse keeps the lines a row's comment names, united across duplicates`() {
    // the key is line-less on purpose, but the 'line N' tag after the cause is the
    // anchor the README cause argues about — kept per member for the drift check
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,encode,MathMutator # lines 12, 30",
      "com.example.Codec,encode,MathMutator # line 45 — the recorded benign flapper",
      "com.example.Codec,decode,IncrementsMutator # removed loop exit, no anchor named",
      "com.example.Codec,strip,MathMutator",
    ))
    assertEquals(mapOf("com.example.Codec,encode,MathMutator" to setOf(12, 30, 45)), membership.recordedLines)
  }

  @Test
  fun `parse reads slash-separated line lists, the hand-written shape shipped in consumer files`() {
    // the seed writes commas, but committed rows in the wild say '# lines 137/141';
    // keeping only the first number would read the second line's timeout as drift
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,await,VoidMethodCallMutator # lines 137/141",
      "com.example.Codec,call,RemoveConditionalMutator_EQUAL_IF # lines 35/39, load flip",
    ))
    assertEquals(
      mapOf(
        "com.example.Codec,await,VoidMethodCallMutator" to setOf(137, 141),
        "com.example.Codec,call,RemoveConditionalMutator_EQUAL_IF" to setOf(35, 39),
      ),
      membership.recordedLines
    )
  }

  @Test
  fun `a timeout line range is invalid rather than partially parsed`() {
    val member = "com.example.Codec,await,VoidMethodCallMutator"
    listOf("786-800", "786–800", "786—800", "786−800", "786..800", "786 to 800")
        .forEach { value ->
          val membership = TimeoutAudit.parse(listOf(
            "$member # cause:liveness lines $value",
          ))

          assertEquals(setOf(member), membership.members, value)
          assertFalse(membership.recordedLines.containsKey(member), value)
          assertEquals(TimeoutAudit.CauseCategory.LIVENESS, membership.causeCategories[member], value)
          assertTrue(membership.causeFindings.isEmpty(), value)
          val finding = membership.lineMetadataFindings.single()
          assertEquals(member, finding.member, value)
          assertTrue(finding.detail.contains("invalid line metadata 'lines $value'"), finding.detail)
          assertTrue(
            finding.detail.contains("'line N' or 'lines N, M' after the cause classification"),
            finding.detail,
          )
          assertTrue(
            finding.detail.contains("remove the optional tag when no exact observation exists"),
            finding.detail,
          )
        }
  }

  @Test
  fun `the first timeout line token decides metadata before later prose ranges`() {
    val validFirst = "com.example.Codec,await,VoidMethodCallMutator"
    val invalidFirst = "com.example.Codec,decode,MathMutator"
    val membership = TimeoutAudit.parse(listOf(
      "$validFirst # cause:liveness line 12 # see README lines 40-50",
      "$invalidFirst # cause:liveness lines 70-80 # observed later at line 75",
    ))

    assertEquals(setOf(12), membership.recordedLines[validFirst])
    assertFalse(membership.recordedLines.containsKey(invalidFirst))
    assertEquals(
      listOf(invalidFirst),
      membership.lineMetadataFindings.map { it.member },
    )
    assertTrue(
      membership.lineMetadataFindings.single().detail
          .contains("invalid line metadata 'lines 70-80'"),
    )
  }

  @Test
  fun `invalid line metadata stays separate from cause findings and overflow does not throw`() {
    val resource = "com.example.Codec,grow,MathMutator"
    val overflow = "com.example.Codec,wait,MathMutator"
    val tooLarge = "999999999999999999999999"
    val membership = TimeoutAudit.parse(listOf(
      "$resource # cause:resource lines 10-20",
      "$overflow # cause:liveness line $tooLarge",
    ))

    assertEquals(1, membership.causeFindings.size)
    val resourceFinding = membership.causeFindings.single { it.member == resource }
    assertTrue(resourceFinding.detail.contains("cause:resource terminates"))
    assertFalse(resourceFinding.detail.contains("line metadata"))
    assertEquals(2, membership.lineMetadataFindings.size)
    val resourceMetadata = membership.lineMetadataFindings.single { it.member == resource }
    assertTrue(resourceMetadata.detail.contains("invalid line metadata 'lines 10-20'"))
    val overflowFinding = membership.lineMetadataFindings.single { it.member == overflow }
    assertTrue(overflowFinding.detail.contains("invalid line metadata 'line $tooLarge'"))
    assertFalse(membership.recordedLines.containsKey(resource))
    assertFalse(membership.recordedLines.containsKey(overflow))

    assertEquals(
      listOf(overflowFinding),
      TimeoutAudit.lineMetadataFindings(membership, listOf(overflow)),
    )
    val warning = TimeoutAudit.lineMetadataWarning(
      "encoding", "encoding-timeouts.csv", membership.lineMetadataFindings)
    assertEquals(
      "pitest 'encoding': invalid optional line metadata in encoding-timeouts.csv — " +
          "2 audited-timeout members:\n" +
          "  Evidence: Invalid metadata by line-less member follows.\n" +
          "    $resource # ${resourceMetadata.detail}\n" +
          "    $overflow # ${overflowFinding.detail}\n" +
          "  Review: Optional line metadata supplies no source-line diagnostic evidence. It " +
          "does not change membership or cause classification, block strict " +
          "certification, or change timeout-retirement eligibility.\n" +
          "  Remedy: Use 'line N' or 'lines N, M' after the cause classification, or remove " +
          "the optional tag when no exact observation exists.",
      warning,
    )
  }

  @Test
  fun `only liveness is an admissible audited-timeout cause classification`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,loop,MathMutator # cause:liveness line 12",
      "com.example.Codec,grow,MathMutator # cause:resource line 20",
      "com.example.Codec,slow,MathMutator # cause:harness line 25",
      "com.example.Codec,pending,MathMutator # cause:untriaged line 30",
      "com.example.Codec,legacy,MathMutator # line 40",
      "com.example.Codec,typo,MathMutator # cause:nontermination line 50",
    ))

    assertEquals(
      TimeoutAudit.CauseCategory.LIVENESS,
      membership.causeCategories.getValue("com.example.Codec,loop,MathMutator")
    )
    assertEquals(
      TimeoutAudit.CauseCategory.HARNESS,
      membership.causeCategories.getValue("com.example.Codec,slow,MathMutator")
    )
    assertEquals(
      setOf(
        "com.example.Codec,grow,MathMutator",
        "com.example.Codec,slow,MathMutator",
        "com.example.Codec,pending,MathMutator",
        "com.example.Codec,legacy,MathMutator",
        "com.example.Codec,typo,MathMutator",
      ),
      membership.causeFindings.mapTo(linkedSetOf()) { it.member }
    )
    assertTrue(
      membership.causeFindings.single { it.member == "com.example.Codec,slow,MathMutator" }
          .detail.contains("finite covering-path/watchdog race"),
    )
  }

  @Test
  fun `liveness classification does not depend on diagnostic line metadata`() {
    val member = "com.example.Codec,loop,MathMutator"
    val membership = TimeoutAudit.parse(listOf(
      "$member # cause:liveness removed loop exit",
    ))

    assertEquals(TimeoutAudit.CauseCategory.LIVENESS, membership.causeCategories[member])
    assertEquals(emptyList<TimeoutAudit.CauseFinding>(), membership.causeFindings)
  }

  @Test
  fun `cause warning classifies liveness by the mutated path rather than a fixture escape`() {
    val warning = TimeoutAudit.causeFindingWarning(
      "encoding",
      "encoding-timeouts.csv",
      listOf(
        TimeoutAudit.CauseFinding(
          "com.example.Codec,loop,MathMutator",
          "cause:untriaged has not been reviewed",
        )
      ),
    )

    assertEquals(
      "pitest 'encoding': 1 audited-timeout member lacks an admissible cause classification " +
          "in encoding-timeouts.csv:\n" +
          "  Evidence: Cause findings by line-less member follow.\n" +
          "    com.example.Codec,loop,MathMutator # cause:untriaged has not been reviewed\n" +
          "  Review: Use 'cause:liveness' only when the mutated path has no path-owned finite " +
          "completion guarantee after deterministic seams/budgets are exhausted. A fixture " +
          "safety exit does not demote it.\n" +
          "  Remedy: Classify each member accurately: 'cause:resource' requires either a " +
          "deterministic resource-contract test/fix or a stable SURVIVED equivalence argument; " +
          "'cause:harness' records a reviewed finite covering-path/watchdog race while it is " +
          "being repaired; and 'cause:untriaged' is unfinished. All three are non-certifying.",
      warning,
    )
    assertFalse(warning.contains("only when the mutant cannot complete"), warning)
  }

  @Test
  fun `conflicting cause classifications fail the member instead of using file order`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,wait,MathMutator # cause:liveness line 12",
      "com.example.Codec,wait,MathMutator # cause:resource line 30",
    ))

    assertEquals(emptyMap<String, TimeoutAudit.CauseCategory>(), membership.causeCategories)
    assertEquals(1, membership.causeFindings.size)
    assertTrue(membership.causeFindings.single().detail.contains("conflicting"))
  }

  @Test
  fun `conflicting classifications on one row cannot hide behind the first token`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,wait,MathMutator # cause:liveness cause:resource line 12",
    ))

    assertEquals(emptyMap<String, TimeoutAudit.CauseCategory>(), membership.causeCategories)
    assertEquals(
      "conflicting cause categories: liveness, resource",
      membership.causeFindings.single().detail,
    )
  }

  @Test
  fun `cause findings can be scoped to live members`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,live,MathMutator # cause:liveness line 12",
      "com.example.Codec,stale,MathMutator # cause:untriaged line 30",
    ))

    assertEquals(
      emptyList<TimeoutAudit.CauseFinding>(),
      TimeoutAudit.causeFindings(
        membership, listOf("com.example.Codec,live,MathMutator"))
    )
  }

  @Test
  fun `report findings share timeout matching across normal and provenance refusal paths`() {
    val membership = TimeoutAudit.parse(listOf(
      "com.example.Codec,wait,MathMutator # cause:liveness line 12",
      "com.example.Codec,gone,IncrementsMutator # cause:untriaged line 30",
    ))
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,12,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.VoidMethodCallMutator,other,44,TIMED_OUT,none",
    ))

    val findings = TimeoutAudit.reportFindings(rows, membership) {
      "## Codec.wait\n\n`Codec.wait`: the removed signal strands the waiter.\n"
    }

    assertEquals(
      setOf(
        "com.example.Codec,wait,MathMutator",
        "com.example.Codec,other,VoidMethodCallMutator",
      ),
      findings.timedOutByKey.keys,
    )
    assertEquals(
      listOf("com.example.Codec,other,VoidMethodCallMutator"),
      findings.unaudited.map { it.coordinate },
    )
    assertEquals(setOf("com.example.Codec,gone,IncrementsMutator"), findings.staleMembers)
    assertEquals(setOf("com.example.Codec,wait,MathMutator"), findings.liveMembers)
    assertTrue(findings.causeFindings.isEmpty())
    assertTrue(findings.undocumented.isEmpty())
  }

  @Test
  fun `member population detail preserves line status and same-line multiplicity`() {
    val member = "com.example.Codec,wait,MathMutator"
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,12,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,12,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,20,KILLED,CodecTest",
      "Codec.java,com.example.Codec,x.IncrementsMutator,other,30,TIMED_OUT,none",
    ))

    val populations = TimeoutAudit.memberPopulations(
      rows,
      setOf(member, "com.example.Codec,other,IncrementsMutator"),
    )

    assertEquals(
      listOf(
        TimeoutAudit.MemberPopulation(
          member,
          3,
          listOf(
            TimeoutAudit.PopulationObservation(12, "TIMED_OUT", 2),
            TimeoutAudit.PopulationObservation(20, "KILLED", 1),
          ),
        ),
      ),
      populations,
    )
    val detail = TimeoutAudit.memberPopulationDetail("encoding", populations)!!
    assertEquals(
      "pitest 'encoding': 1 audited-timeout key covers multiple mutant copies in the " +
          "current full report:\n" +
          "  Evidence: Every key has 2+ physical TIMED_OUT mutants; current copies at each " +
          "line-less key follow.\n" +
          "    $member — 3 mutants:\n" +
          "      line 12 TIMED_OUT x2\n" +
          "      line 20 KILLED x1\n" +
          "  Review: Cause is key-level. Inspect keys with multiple TIMED_OUT siblings; " +
          "KILLED and other " +
          "non-timeout siblings are context, not proof of a mixed timeout cause.",
      detail,
    )
  }

  @Test
  fun `member population detail collapses keys with only one timeout copy`() {
    val expandedMember = "com.example.Codec,wait,MathMutator"
    val collapsedMember = "com.example.Codec,read,IncrementsMutator"
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,12,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,20,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,30,KILLED,CodecTest",
      "Codec.java,com.example.Codec,x.IncrementsMutator,read,40,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.IncrementsMutator,read,50,KILLED,CodecTest",
    ))
    val populations = TimeoutAudit.memberPopulations(
      rows,
      setOf(expandedMember, collapsedMember),
    )

    val detail = TimeoutAudit.memberPopulationDetail("encoding", populations)!!

    assertTrue(
      detail.contains("1 key with 2+ physical TIMED_OUT mutants is expanded below; " +
        "1 additional key with exactly 1 physical TIMED_OUT mutant plus non-timeout siblings is collapsed."),
      detail,
    )
    assertTrue(detail.contains("$expandedMember — 3 mutants"), detail)
    assertTrue(detail.contains("line 12 TIMED_OUT x1") && detail.contains("line 20 TIMED_OUT x1"), detail)
    assertFalse(detail.contains(collapsedMember), detail)
    assertFalse(detail.contains("line 40 TIMED_OUT") || detail.contains("line 50 KILLED"), detail)
  }

  @Test
  fun `member population detail reduces all single-timeout sibling keys to one aggregate`() {
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,12,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,20,KILLED,CodecTest",
      "Codec.java,com.example.Codec,x.IncrementsMutator,read,30,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.IncrementsMutator,read,40,SURVIVED,none",
    ))
    val populations = TimeoutAudit.memberPopulations(
      rows,
      setOf(
        "com.example.Codec,wait,MathMutator",
        "com.example.Codec,read,IncrementsMutator",
      ),
    )

    val detail = TimeoutAudit.memberPopulationDetail("encoding", populations)!!

    assertTrue(
      detail.contains("2 keys each have exactly 1 physical TIMED_OUT mutant plus non-timeout siblings"),
      detail,
    )
    assertFalse(detail.contains("com.example.Codec,"), detail)
    assertFalse(detail.contains("line 12") || detail.contains("line 30"), detail)
  }

  @Test
  fun `timeout candidates separate physical instances from line-less rows`() {
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,30,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,10,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,30,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.IncrementsMutator,other,44,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,20,KILLED,CodecTest",
    ))

    val candidates = TimeoutAudit.timeoutCandidates(rows)

    assertEquals(4, candidates.instanceCount)
    assertEquals(2, candidates.keyCount)
    assertEquals(
      "4 physical TIMED_OUT mutant instances across 2 line-less keys",
      TimeoutAudit.timeoutCandidateCount(candidates),
    )
    assertEquals(
      "1 physical TIMED_OUT mutant instance across 1 line-less key",
      TimeoutAudit.timeoutCandidateCount(TimeoutAudit.timeoutCandidates(listOf(rows.first()))),
    )
    assertEquals(
      listOf(
        TimeoutAudit.MemberPopulation(
          "com.example.Codec,other,IncrementsMutator",
          1,
          listOf(TimeoutAudit.PopulationObservation(44, "TIMED_OUT", 1)),
        ),
        TimeoutAudit.MemberPopulation(
          "com.example.Codec,wait,MathMutator",
          3,
          listOf(
            TimeoutAudit.PopulationObservation(10, "TIMED_OUT", 1),
            TimeoutAudit.PopulationObservation(30, "TIMED_OUT", 2),
          ),
        ),
      ),
      candidates.populations,
    )
    assertEquals(
      "com.example.Codec,other,IncrementsMutator # cause:untriaged line 44\n" +
          "com.example.Codec,wait,MathMutator # cause:untriaged lines 10, 30",
      TimeoutAudit.timeoutCandidateRows(candidates),
    )

    val detail = TimeoutAudit.timeoutCandidateDetail(candidates)
    assertFalse(detail.contains("# observed: line 44 TIMED_OUT x1"), detail)
    assertTrue(detail.contains("# observed: line 10 TIMED_OUT x1"), detail)
    assertTrue(detail.contains("# observed: line 30 TIMED_OUT x2"), detail)
    assertFalse(detail.contains("KILLED"), detail)
    val parsedDetail = TimeoutAudit.parse(detail.lines())
    assertEquals(
      setOf(
        "com.example.Codec,other,IncrementsMutator",
        "com.example.Codec,wait,MathMutator",
      ),
      parsedDetail.members,
    )
    assertTrue(parsedDetail.malformed.isEmpty(), detail)
  }

  @Test
  fun `unaudited warning renders claim evidence review and remedy exactly`() {
    val unaudited = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,wait,30,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,10,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,wait,30,TIMED_OUT,none",
    ))

    val warning = TimeoutAudit.unauditedWarning(
      "encoding",
      "encoding-timeouts.csv",
      unaudited,
      "1.25.9",
      2.0,
      1500L,
      "\nThis [history] result is check-only.",
    )

    assertEquals(
      "pitest 'encoding': 3 physical TIMED_OUT mutant instances across 1 line-less key not in " +
          "the audited set " +
          "(encoding-timeouts.csv):\n" +
          "  Evidence: One paste-ready, fail-closed draft row per line-less key follows; nested " +
          "'# observed' comments preserve line/status multiplicity.\n" +
          "    com.example.Codec,wait,MathMutator # cause:untriaged lines 10, 30\n" +
          "      # observed: line 10 TIMED_OUT x1\n" +
          "      # observed: line 30 TIMED_OUT x2\n" +
          "  Review: Identify the structural cause (for example, a removed loop exit or " +
          "reversed cursor). A timeout can hide a weakened-assertion blind spot behind " +
          "\"detected\".\n" +
          "  Watchdog context: Configured watchdog " +
          "(audited PIT 1.25.9): round(testDurationMs × 2.0) + 1500 ms. PIT CSV lacks the active " +
          "covering test and its recorded duration, so no per-mutant budget can be calculated.\n" +
          "  Remedy: Replace each deliberate cause:untriaged placeholder with the reviewed " +
          "classification. Write the structural argument in config/pitest/README.md before " +
          "committing it. " +
          "The placeholder is non-certifying; only cause:liveness may remain in a certifying " +
          "audited set.\n" +
          "This [history] result is check-only.",
      warning,
    )
  }

  @Test
  fun `provenance previews render coordinates before review and withheld remedy`() {
    val unaudited = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.VoidMethodCallMutator,other,44,TIMED_OUT,none",
    ))
    val stale = listOf("com.example.Codec,gone,IncrementsMutator")

    assertEquals(
      "pitest 'encoding': the current full report contains 1 physical TIMED_OUT mutant instance " +
          "across 1 line-less key outside encoding-timeouts.csv, and committed mutation " +
          "provenance is invalid:\n" +
          "  Evidence: Triage-only draft rows follow, one per line-less key; nested '# observed' " +
          "comments preserve line/status multiplicity.\n" +
          "    com.example.Codec,other,VoidMethodCallMutator # cause:untriaged line 44\n" +
          "  Review: The population is not bound to valid committed provenance.\n" +
          "  Watchdog context: Configured watchdog (audited PIT 1.25.9): " +
          "round(testDurationMs × 2.0) + 1500 ms. PIT CSV lacks the active covering test and its " +
          "recorded duration, so no per-mutant budget can be calculated.\n" +
          "  Remedy: Retain these candidates, repair or rebase provenance, and obtain a fresh " +
          "full observation. Do not add or classify them until that observation confirms them.",
      TimeoutAudit.unauditedProvenancePreview(
        "encoding", "encoding-timeouts.csv", unaudited, "1.25.9", 2.0, 1500L),
    )
    assertEquals(
      "pitest 'encoding': provenance-blocked stale-membership preview — the current full " +
          "report does not contain 1 audited-timeout row:\n" +
          "  Evidence: Committed coordinates absent from the current population follow.\n" +
          "    com.example.Codec,gone,IncrementsMutator\n" +
          "  Review: Committed mutation provenance is invalid, so this absence cannot authorize " +
          "record changes.\n" +
          "  Remedy: Retain these candidates for triage; do not retire or rewrite them until " +
          "provenance is repaired/rebased and a fresh full observation confirms the absence.",
      TimeoutAudit.staleProvenancePreview("encoding", stale),
    )
  }

  @Test
  fun `stale warning sorts coordinates and uses grammatical plurals`() {
    assertEquals(
      "pitest 'encoding': 2 audited-timeout rows match no mutant in this run's report:\n" +
          "  Evidence: Committed coordinates absent from the current population follow.\n" +
          "    a.A,first,MathMutator\n" +
          "    b.B,second,IncrementsMutator\n" +
          "  Review: The code may have moved, or the mutator set may have changed.\n" +
          "  Remedy: Retire or fix each stale row.",
      TimeoutAudit.staleWarning(
        "encoding",
        listOf("b.B,second,IncrementsMutator", "a.A,first,MathMutator"),
      ),
    )
  }

  @Test
  fun `watchdog context prints configured arithmetic without inventing a budget`() {
    val context = TimeoutAudit.watchdogFormulaContext("1.25.9", 2.0, 1500L)

    assertTrue(context.contains("round(testDurationMs × 2.0) + 1500 ms"), context)
    assertTrue(context.contains("audited PIT 1.25.9"), context)
    assertTrue(context.contains("no per-mutant budget can be calculated"), context)

    val unaudited = TimeoutAudit.watchdogFormulaContext("1.26.0", 1.5, 4000L)
    assertTrue(unaudited.contains("has not audited"), unaudited)
    assertTrue(unaudited.contains("timeoutFactor=1.5, timeoutConst=4000 ms"), unaudited)
    assertFalse(unaudited.contains("round("), unaudited)
  }

  @Test
  fun `member population detail omits single mutants and multi-mutant keys without a timeout`() {
    val rows = Mutant.parseReport(listOf(
      "Codec.java,com.example.Codec,x.MathMutator,one,10,TIMED_OUT,none",
      "Codec.java,com.example.Codec,x.MathMutator,done,20,KILLED,CodecTest",
      "Codec.java,com.example.Codec,x.MathMutator,done,30,SURVIVED,none",
    ))
    val members = setOf(
      "com.example.Codec,one,MathMutator",
      "com.example.Codec,done,MathMutator",
    )

    assertTrue(TimeoutAudit.memberPopulations(rows, members).isEmpty())
    assertNull(TimeoutAudit.memberPopulationDetail("encoding", emptyList()))
  }

  @Test
  fun `malformedWarning names the file and the offending rows, or is null`() {
    assertNull(TimeoutAudit.malformedWarning("encoding", "encoding-timeouts.csv", emptyList()))

    val warning = TimeoutAudit.malformedWarning(
      "encoding", "encoding-timeouts.csv", listOf("com.example.Codec,encode")
    )
    assertEquals(
      "pitest 'encoding': 1 malformed row in encoding-timeouts.csv:\n" +
          "  Evidence: Unparseable rows follow.\n" +
          "    com.example.Codec,encode\n" +
          "  Review: Expected 'class,method,mutator' with exactly three non-empty fields; " +
          "'#' comments are allowed.\n" +
          "  Remedy: Fix each malformed row; until fixed, it matches no mutant.",
      warning,
    )
  }

  @Test
  fun `a cause needs the class and the method together, not the method alone`() {
    val members = setOf("com.example.Codec,handle,MathMutator")
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) { "handlers handle every message here" }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "`Codec.handle` (MathMutator): drains the queue." }
    )
  }

  @Test
  fun `a nested class matches under its source or binary name`() {
    val members = setOf("com.example.Outer\$Inner,handle,MathMutator")
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "`Outer.Inner.handle`: retries until the clock moves." }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) { "Outer\$Inner handle: retries until the clock moves." }
    )
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) { "handle appears without either class name" }
    )
  }

  @Test
  fun `a cause needs the class and the method in one section, as whole words`() {
    // whole-file substring matching was trivially satisfied: 'run' sits inside
    // "rerun", and a sibling member's cause already names the class — so a class
    // with two audited methods passed as fully documented with one cause written
    val members = setOf("com.example.Notifier,run,MathMutator")
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        "`Notifier.queueResponse`: drains the queue.\n\nRerun the suite to see it."
      }
    )
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        // class and method both present, but under different headings — the class
        // mention belongs to another member's section, the whole-file leak in small
        "## Notifier queueing\n`Notifier.queueResponse`: drains the queue.\n\n" +
            "## Scheduling\nThe run loop parks forever."
      }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) {
        // one house style: an intro line naming Class.method, bullets in the same block
        "Both are in `Notifier.run`, the drain loop:\n- the removed exit parks the drain\n"
      }
    )
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) {
        // the other measured house style: the section intro names the class, each
        // method argued in its own paragraph below — one section, several paragraphs
        "## Notifier pass\n\n`Notifier` drains on a worker thread.\n\n" +
            "**run** — the removed exit parks the drain loop forever.\n"
      }
    )
  }

  @Test
  fun `a constructor member's cause resolves despite word boundaries failing at angle brackets`() {
    // '\b<init>\b' can never match 'Handler.<init>' in prose: a word boundary needs
    // a word char adjacent to each angle bracket, and '.' or a space is not one —
    // whole-word matching must use lookarounds, or every constructor member in a
    // shipped audit set reads as cause-less and fails certification
    val members = setOf("com.example.ExponentialBackoffErrorHandler,<init>,ConditionalsBoundaryMutator")
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(members) {
        "`ExponentialBackoffErrorHandler.<init>:14` — the measured load flip; the\nconstructor hang is only observable as a timeout.\n"
      }
    )
    // still whole-word: 'init' alone must not satisfy '<init>'
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        "`ExponentialBackoffErrorHandler` init logic crawls under load.\n"
      }
    )
    // stricter than '\b' on purpose: a word char glued to the bracket is not a
    // mention — '\b' would have accepted 'pre<init>' (word->non-word is a boundary)
    assertEquals(
      members.toList(),
      TimeoutAudit.undocumentedCauses(members) {
        "`ExponentialBackoffErrorHandler` pre<init> staging crawls under load.\n"
      }
    )
  }

  @Test
  fun `the readme is not read when there is no member to resolve`() {
    assertEquals(
      emptyList<String>(),
      TimeoutAudit.undocumentedCauses(emptySet()) { error("readme read with no members") }
    )
  }

  @Test
  fun `the cause warning sorts members under a paste-ready prefix`() {
    val warning = TimeoutAudit.undocumentedCauseWarning(
      "encoding", listOf("b.B,m,MathMutator", "a.A,m,MathMutator")
    )
    assertEquals(
      "pitest 'encoding': missing README cause for 2 audited-timeout members:\n" +
          "  Evidence: Undocumented line-less members follow.\n" +
          "    cause? a.A,m,MathMutator\n" +
          "    cause? b.B,m,MathMutator\n" +
          "  Review: Each member's class and method must appear together in one " +
          "config/pitest/README.md section.\n" +
          "  Remedy: Write the structural cause there (HARDENING.md, the audited-set bullet).",
      warning,
    )
  }

  @Test
  fun `a fenced code block's hash lines do not split the section`() {
    // '#' at column 0 inside a ``` fence is code, not a heading: a README quoting
    // a shell command or properties snippet used to split its section mid-fence,
    // and every cause argued below the fence read as undocumented — failing
    // -PstrictTimeoutAudit over formatting.
    val readme = """
      ## Codec timeouts

      ```shell
      # reproduce the hang
      ./gradlew pitestEncoding
      ```

      The encode loop's exit mutation hangs Codec.encode under MathMutator.
    """.trimIndent()

    val undocumented = TimeoutAudit.undocumentedCauses(
      listOf("com.example.Codec,encode,MathMutator")
    ) { readme }
    assertEquals(emptyList<String>(), undocumented, "the fence split the section")
  }

  @Test
  fun `a tilde fence neutralizes hash lines like a backtick fence`() {
    val readme = """
      ## Codec timeouts

      ~~~properties
      # threads=4
      ~~~

      The encode loop's exit mutation hangs Codec.encode under MathMutator.
    """.trimIndent()

    val undocumented = TimeoutAudit.undocumentedCauses(
      listOf("com.example.Codec,encode,MathMutator")
    ) { readme }
    assertEquals(emptyList<String>(), undocumented, "the tilde fence split the section")
  }

  @Test
  fun `a mention only inside a fenced block still documents its member`() {
    // fence CONTENT is kept — a snippet may legitimately carry the member mention
    // its section argues with; only the heading grammar is neutralized
    val readme = """
      ## Timeouts

      ```
      # Codec.encode under MathMutator loops forever
      ```
    """.trimIndent()

    val undocumented = TimeoutAudit.undocumentedCauses(
      listOf("com.example.Codec,encode,MathMutator")
    ) { readme }
    assertEquals(emptyList<String>(), undocumented, "fence content was discarded")
  }
}
