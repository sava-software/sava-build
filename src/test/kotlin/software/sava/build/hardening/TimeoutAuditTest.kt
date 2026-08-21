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
    assertTrue(warning.contains("2 audited-timeout member(s) carry invalid optional line metadata"))
    assertTrue(warning.contains("does not change membership or cause classification"))
    assertTrue(warning.contains("block strict certification"))
    assertTrue(warning.contains("change timeout-retirement eligibility"))
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

    assertTrue(warning.contains("no path-owned finite completion guarantee"), warning)
    assertTrue(warning.contains("a fixture safety exit does not demote it"), warning)
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
    assertTrue(detail.contains("$member — 3 mutants"), detail)
    assertTrue(detail.contains("line 12 TIMED_OUT x2"), detail)
    assertTrue(detail.contains("line 20 KILLED x1"), detail)
    assertTrue(detail.contains("non-timeout siblings are context, not proof"), detail)
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
    assertTrue(warning!!.contains("1 malformed row(s) in encoding-timeouts.csv"), warning)
    assertTrue(warning.contains("  com.example.Codec,encode"), warning)
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
    assertTrue(warning.contains("2 audited-timeout member(s)"), warning)
    assertTrue(
      warning.indexOf("cause? a.A,m,MathMutator") < warning.indexOf("cause? b.B,m,MathMutator"),
      warning
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
