import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Functional test for the mutation ratchet and the generators: fabricates PIT
 * reports (CSV + XML) so 'pitest<Suite>Verify' and the baseline flags can be exercised
 * without resolving or running PIT itself.
 */
class HardeningRatchetFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private fun writeFixture(
    generateTestSupport: Boolean = false,
    testSupportExcludes: List<String> = emptyList(),
    recompileExcludes: List<String> = emptyList(),
    // the fuzz targets emit generated junit test sources; omit them when a test
    // actually compiles the fixture (the fixture declares no junit dependency)
    registerFuzz: Boolean = true,
    // caps the 'codec' target when a test exercises the oversized-seed refusal
    codecMaxLen: Int? = null,
    // adds a third target with no corpus, optionally carrying a recorded decline,
    // for the corpus-less advice and its staleness check
    corpusless: Boolean = false,
    corpuslessDecline: String? = null,
    corpuslessAlsoDeclaresCorpus: Boolean = false,
    // pin the recompile's bytecode target when a test actually runs it: the fixture
    // sets no toolchain, so the recompile runs on the daemon JDK, which this build
    // pins to 21 via gradle/gradle-daemon-jvm.properties
    bytecodeRelease: Int? = null,
    // the exclusion-audit tests widen the 'encoding' suite and give it globs to
    // swallow with; extraSuites appends sibling registrations verbatim
    encodingTargets: List<String> = listOf("com.example.Codec"),
    encodingExcludes: List<String> = emptyList(),
    extraSuites: String = ""
  ) {
    val releaseLine = if (bytecodeRelease != null) "bytecodeRelease = $bytecodeRelease" else ""
    val fuzzBlock = if (registerFuzz) {
      """
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/codec")
            ${if (codecMaxLen != null) "maxLen = $codecMaxLen" else ""}
          }
          fuzz.register("outside") {
            targetClass = "com.example.OutsideFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/outside")
          }
          ${if (corpusless) {
            """
            fuzz.register("bare") {
              targetClass = "com.example.BareFuzz"
              ${if (corpuslessAlsoDeclaresCorpus) "seedCorpus = layout.projectDirectory.dir(\"src/test/resources/fuzz/codec\")" else ""}
              ${if (corpuslessDecline != null) "declineSeedCorpus(\"$corpuslessDecline\")" else ""}
            }
            """.trimIndent()
          } else ""}
      """.trimIndent()
    } else {
      ""
    }
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

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
          $releaseLine
          generateTestSupport = $generateTestSupport
          testSupportExcludes = listOf(${testSupportExcludes.joinToString(", ") { "\"$it\"" }})
          recompileExcludes = listOf(${recompileExcludes.joinToString(", ") { "\"$it\"" }})
          mutation.register("encoding") {
            targetClasses = listOf(${encodingTargets.joinToString(", ") { "\"$it\"" }})
            ${if (encodingExcludes.isEmpty()) "" else "excludedClasses = listOf(${encodingExcludes.joinToString(", ") { "\"$it\"" }})"}
            targetTests = "com.example.*Test*"
          }
$extraSuites
$fuzzBlock
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
    // is stale — the run passes with no refresh hint at all; only the line-drift
    // advisory asks for a re-read, since the recorded anchor no longer matches
    val moved = runner("pitestEncodingVerify", "-PlistUnkilled").build().output
    assertTrue(moved.contains("1 rows — 1 '# untriaged'"), "per-label count missing:\n$moved")
    assertTrue(moved.contains("pitest 'encoding' unkilled:"), "-PlistUnkilled listing missing:\n$moved")
    assertTrue(
      moved.contains("1 accepted key(s) unkilled at line(s) no row's '# line' tag names") &&
          moved.contains("# line(s) 10 -> unrecorded 12"),
      "line-drift advisory missing:\n$moved"
    )
    // the XML description carries the line the key no longer does
    assertTrue(moved.contains("line 12: Replaced Shift Left with Shift Right"), "XML description missing:\n$moved")
    assertFalse(moved.contains("refresh with"), "a line move must not ask for a refresh:\n$moved")
    assertFalse(moved.contains("moved line only"), "the drift-tolerance machinery is retired:\n$moved")

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
    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(output.contains("1 unkilled mutant(s) not in the accepted baseline"), output)
    assertTrue(
      output.contains("churn: 0 newly covered, 1 unexplained (of 1 new; 0 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(output.contains("line 33: Replaced integer addition with subtraction"), "description missing:\n$output")
  }

  @Test
  fun `the stale hint recommends the shrink-only refresh when nothing is new`() {
    // A pass that killed baseline rows leaves stale entries and nothing fresh:
    // the always-safe direction is prune, and recommending update here used to
    // invite baking a single run's coin-flips into the record.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,decode,30,MathMutator,SURVIVED\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest.roundTrips"),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("refresh with -PpruneMutationBaseline (shrink-only; nothing new to bake in)"),
      "shrink-case stale hint missing:\n$output"
    )
    assertFalse(
      output.contains("refresh with -PupdateMutationBaseline"),
      "must not recommend the full rewrite when nothing is new:\n$output"
    )
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
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.RemoveConditionalMutator_EQUAL_ELSE,encode,12,KILLED,com.example.CodecTest.[engine:junit-jupiter]/[class:com.example.CodecTest]/[method:encodesTheBoundary()]"
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
      output.contains("detected sibling at this line: RemoveConditionalMutator_EQUAL_ELSE KILLED by encodesTheBoundary"),
      "sibling hint missing:\n$output"
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

    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    val rows = baselineFile().readLines().filter { it.isNotBlank() }
    assertEquals(2, rows.size, "the refresh collapsed sibling rows:\n$rows")
  }

  @Test
  fun `a scoped report cannot touch the baseline`() {
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

    // and no refresh flavour may consume it — prune and the timeout-audit seed
    // included, which the early return would otherwise silently no-op while the
    // user believes they ran
    for (flag in listOf("-PupdateMutationBaseline", "-PunionMutationBaseline", "-PpruneMutationBaseline", "-PinitTimeoutAudit")) {
      val refused = runner("pitestEncodingVerify", flag).buildAndFail().output
      assertTrue(
        refused.contains("cannot refresh the baseline"),
        "scoped refresh was not refused for $flag:\n$refused"
      )
    }

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
    // the SURVIVED key still intersects nothing recorded (230 -> 235): advisory only
    assertTrue(
      output.contains("unkilled at line(s) no row's '# line' tag names"),
      "line-drift advisory missing:\n$output"
    )
  }

  @Test
  fun `prune drops only since-killed rows and keeps flip-protected ones`() {
    // The shrink-only refresh: rows matching this run stay (notes intact), rows whose
    // mutants are gone are dropped, and two unmatched classes are kept anyway — a
    // TIMED_OUT coordinate (load-dependent detection, not a kill) and a coordinate
    // still unkilled at another status (a coverage flip the ratchet must triage).
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,10,MathMutator,SURVIVED # untriaged\n" +
          "com.example.Codec,encode,12,MathMutator,NO_COVERAGE # unreachable claim\n" +
          "com.example.Codec,decode,30,MathMutator,SURVIVED # since killed\n" +
          "com.example.Codec,decode,40,IncrementsMutator,NO_COVERAGE\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,decode,40,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    // the rewrite migrates the legacy rows: line fields become '# line' tags
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 10",
        "com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim # line 12",
        "com.example.Codec,decode,IncrementsMutator,NO_COVERAGE # line 40",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
    assertTrue(output.contains("com.example.Codec,decode,MathMutator,SURVIVED # since killed # line 30"), output)
    // the NO_COVERAGE row whose coordinate timed out is kept as load-dependent
    // detection; the one whose coordinate survives at another status is a pending flip
    assertTrue(output.contains("TIMED_OUT this run (load-dependent)"), output)
    assertTrue(output.contains("flip pending triage"), output)
  }

  @Test
  fun `the refresh flags are mutually exclusive`() {
    writeFixture()
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline", "-PupdateMutationBaseline")
      .buildAndFail().output
    assertTrue(output.contains("pass at most one of"), output)

    // the audit seed is a refresh flavour too — it writes a file the same way — and
    // the refusal must land before either flag does any work
    val seedCombo = runner("pitestEncodingVerify", "-PupdateMutationBaseline", "-PinitTimeoutAudit")
      .buildAndFail().output
    assertTrue(seedCombo.contains("pass at most one of"), seedCombo)
    assertFalse(
      File(fixtureDir, "config/pitest/encoding-timeouts.csv").exists(),
      "refused combination still seeded the audit:\n$seedCombo"
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
    // Accepting a newly covered mutant goes through -PupdateMutationBaseline: the old
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
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
    assertTrue(output.contains("— note carried"), output)
    assertFalse(output.contains("note dropped with the row"), output)

    // idempotent: a second update with no flips leaves both notes untouched
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # line 41",
        "com.example.Codec,encode,MathMutator,SURVIVED # sibling operand, same documented family # line 13",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 21",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("wrote 3 accepted entries"), output)
    // nothing was dropped, seeded or flipped: a shift is no longer churn at all
    assertFalse(output.contains("seeded '# untriaged'"), output)
    assertFalse(output.contains("carried across a status flip"), output)
    assertFalse(output.contains("dropped"), output)

    // idempotent: a second update rewrites the identical file
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # line 41",
        "com.example.Codec,encode,MathMutator,SURVIVED # sibling operand, same documented family # line 13",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 21",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
  }

  @Test
  fun `a new sibling at an accepted key is seen by its count and seeded untriaged`() {
    // The line-less key's honest residue: a new mutant of an accepted key is visible
    // only as a count change. The multiset comparison must fail on the extra copy
    // (never absorb it), and an update must seed it '# untriaged' — an accepted
    // twin's argument was written for the mutants it had, not for one more. Bare
    // pre-seeding rows stay bare through the same refresh.
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
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
      output.contains("unkilled at line(s) no row's '# line' tag names") &&
          output.contains("# line(s) 150 -> unrecorded 157"),
      "the swap's only trace is the line-drift advisory:\n$output"
    )
  }

  @Test
  fun `the timed-out audited set warns on newcomers and notices stale members`() {
    // TIMED_OUT is detected, but the watchdog observed slowness, not wrongness: the
    // ratchet cannot see a weakened covering assertion behind a timeout, so the
    // summary's load-dependent count is only trustworthy as an audited membership.
    // A timed-out mutant missing from <suite>-timeouts.csv is a reviewer-stop
    // warning; a member matching no mutant at all is retirement hygiene. Both are
    // advisory — neither may fail the build, since load can time out any mutant.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText(
      "# structural causes live in config/pitest/README.md\n" +
          "com.example.Codec,encode,MathMutator # removed loop exit\n" +
          "com.example.Codec,gone,MathMutator\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,40,KILLED,com.example.CodecTest",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    // the printed row is the membership key verbatim with the line in a '#' comment
    val printedRow = "com.example.Codec,encode,IncrementsMutator # line 30"
    assertTrue(
      output.contains("1 timed-out mutant(s) not in the audited set (encoding-timeouts.csv)") &&
          output.contains(printedRow),
      "unaudited timeout not warned paste-ready:\n$output"
    )
    assertFalse(
      output.contains("MathMutator # line 12"),
      "audited member wrongly listed:\n$output"
    )
    assertTrue(
      output.contains("1 audited-timeout row(s) match no mutant") &&
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
    // stale members print comment-stripped and indented; the pre-existing 'gone' row
    // still reports, so absence must be asserted for this key specifically
    assertFalse(
      pasted.contains("  com.example.Codec,encode,IncrementsMutator"),
      "pasted printed row read as stale:\n$pasted"
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
    // nudged (advisory, naming the seed flag) rather than silent: the feature used
    // to be discoverable only by reading HARDENING.md
    timeoutsFile.delete()
    val unadopted = runner("pitestEncodingVerify").build().output
    assertFalse(
      unadopted.contains("not in the audited set"),
      "audit warning without a membership file:\n$unadopted"
    )
    assertTrue(
      unadopted.contains("2 timed-out mutant(s) and no audited set") &&
          unadopted.contains("-PinitTimeoutAudit"),
      "adoption hint missing:\n$unadopted"
    )
  }

  @Test
  fun `the fleet canary reprint filter matches every warning it canaries`() {
    // tools/fleet-canary.sh reprints hardening warnings from consumer build output by
    // grepping with a fixed pattern — deliberately coupled to the messages' wording,
    // since the script cannot see log levels through Gradle's plain console. This
    // provokes every canaried warning in one verify run and greps the output with the
    // script's own pattern, so rewording a message fails here instead of silently
    // dropping the warning from the canary's reprint.
    val script = File(savaBuildTestProperty("savaBuild.root"), "tools/fleet-canary.sh").readText()
    val pattern = Regex("(?m)^findings_pattern='([^']+)'").find(script)?.groupValues?.get(1)
      ?: error("findings_pattern line not found in tools/fleet-canary.sh")

    // targets widened and given a glob to swallow with: a production class the
    // exclusion drops -> 'swallowed by excludedClasses', from the Debt task's
    // static half (the audit reads class-file names, not bytecode)
    writeFixture(
      encodingTargets = listOf("com.example.*"),
      encodingExcludes = listOf("com.example.Swallowed*"),
      // a record that argues for nothing -> 'match no swallowed', and one with no
      // reason -> 'suppress nothing' (which also leaves its class in the report)
      extraSuites = """
          mutation.register("declines") {
            targetClasses = listOf("com.example.*")
            excludedClasses = listOf("com.example.Swallowed*")
            targetTests = "com.example.*Test*"
            declineExclusionAudit("com.example.Retired*", "a glob that swallows nothing")
            declineExclusionAudit("com.example.Swallowed*", "")
          }
      """.trimIndent()
    )
    File(fixtureDir, "build/mutation-classes/com/example/SwallowedHelper.class").also {
      it.parentFile.mkdirs()
      it.writeBytes(byteArrayOf(1))
    }
    baselineFile().parentFile.mkdirs()
    // an accepted row whose family label has no README section -> 'no argument in config'
    baselineFile().writeText("com.example.Codec,decode,40,InvertNegsMutator,SURVIVED # mystery family\n")
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").writeText(
      "com.example.Codec,encode,MathMutator\n" + // cause never written -> 'appear nowhere'
          "com.example.Codec,gone,MathMutator\n" + // stale member -> 'match no mutant'
          "com.example.Codec,encode\n" // two fields -> 'malformed row'
    )
    File(fixtureDir, "config/pitest/README.md").writeText("# Baseline\n\nNo causes or labels yet.\n")
    // a stale tool-version record -> 'written by PIT'
    File(fixtureDir, "config/pitest/encoding-pitest-version").writeText("0.0.0-stale\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        // an unaudited timed-out newcomer -> 'not in the audited set'
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.InvertNegsMutator,decode,40,SURVIVED,none",
      ),
      ""
    )

    // all seven findings are advisory, so the run passes — the advisory summary at
    // the end of the build supplies the pattern's 'advisory finding' alternation,
    // and the Debt task rides along for the fragments only its static halves emit
    val output = runner("pitestEncodingVerify", "pitestEncodingDebt", "pitestDeclinesDebt").build().output
    pattern.split('|').forEach { fragment ->
      assertTrue(
        output.contains(fragment),
        "canary pattern fragment '$fragment' matches nothing — reworded warning?\n$output"
      )
    }
  }

  @Test
  fun `the exclusion audit reads partition handoffs as ownership, statically in Debt`() {
    // Two suites partition com.example.*: 'encoding' hands decoding.* to its
    // sibling, which is ownership, not a swallow — while a glob nothing else
    // targets ('Legacy*') is a genuine hole and must stay a finding. Exercised
    // through the Debt task because that is the fleet canary's whole view of
    // consumer globs: the audit's in-run half only fires inside a real pitest
    // execution, which the canary never performs (casebook: the partition the
    // audit called a hole). Fabricated empty class files are a scanned
    // population — the audit reads names, never bytecode.
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
    listOf("com/example/decoding/Decoder", "com/example/LegacyCodec").forEach { path ->
      File(fixtureDir, "build/mutation-classes/$path.class").also {
        it.parentFile.mkdirs()
        it.writeBytes(byteArrayOf(1))
      }
    }

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
    // the half the fleet canary can execute.
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
    listOf("com/example/gen/Binding", "com/example/LegacyCodec").forEach { path ->
      File(fixtureDir, "build/mutation-classes/$path.class").also {
        it.parentFile.mkdirs()
        it.writeBytes(byteArrayOf(1))
      }
    }

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
  fun `the tool version is part of the record`() {
    // The mutant population is a function of PIT itself, and the default PIT version
    // rides plugin bumps — so a baseline is only comparable to runs from the version
    // that wrote it. A mismatch warns on a checking run (population churn may be the
    // tool, not the code) and refuses a record-writing one: reading a possibly-
    // divergent result is a judgment call, writing the record with one is not.
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

    // no record yet: a refresh adopts by stamping the current version
    val refreshed = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertFalse(refreshed.output.contains("written by PIT"), refreshed.output)
    val stamped = toolVersionFile.readText().trim()
    assertTrue(stamped.isNotEmpty() && stamped.first().isDigit(), "stamped version looks wrong: '$stamped'")

    // matching record: checking and writing runs both stay quiet
    val clean = runner("pitestEncodingVerify").build()
    assertFalse(clean.output.contains("written by PIT"), clean.output)

    toolVersionFile.writeText("0.0.0-stale\n")

    // mismatch: a checking run warns but passes
    val checked = runner("pitestEncodingVerify").build()
    assertTrue(
      checked.output.contains("baseline record written by PIT 0.0.0-stale, this run used PIT $stamped"),
      "mismatch warning missing:\n" + checked.output
    )

    // mismatch: a record-writing run refuses, naming the deliberate-bump path
    val refused = runner("pitestEncodingVerify", "-PupdateMutationBaseline").buildAndFail()
    assertTrue(
      refused.output.contains("refusing to rewrite the record across a tool bump") &&
          refused.output.contains("set config/pitest/encoding-pitest-version to $stamped"),
      "refusal missing or unactionable:\n" + refused.output
    )
    assertEquals("0.0.0-stale", toolVersionFile.readText().trim(), "refused run must not restamp")
  }

  @Test
  fun `the version stamp lands only with a successful baseline write`() {
    // The stamp asserts "this record is comparable to runs of PIT X", so it lands at
    // the successful end of the write it describes: a record-writing run that fails
    // mid-path must not stamp, and '-PinitTimeoutAudit' — which writes the timeout
    // set, not the baseline — must never stamp at all, else seeding the audit on a
    // suite whose baseline predates a PIT bump would silence the mismatch warning
    // for a record the current tool never wrote.
    writeFixture()
    val toolVersionFile = File(fixtureDir, "config/pitest/encoding-pitest-version")

    // a record-writing flag that fails mid-path (the empty-seed refusal) leaves no stamp
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,50,KILLED,com.example.CodecTest",
      ),
      ""
    )
    runner("pitestEncodingVerify", "-PinitTimeoutAudit").buildAndFail()
    assertFalse(toolVersionFile.isFile, "failed record-writing run must not stamp the tool version")

    // a *successful* seed still leaves no stamp: init writes the timeout set, not the baseline
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
      ),
      ""
    )
    val seeded = runner("pitestEncodingVerify", "-PinitTimeoutAudit").build().output
    assertTrue(seeded.contains("seeded 1 audited-timeout member(s)"), "seed did not run:\n$seeded")
    assertFalse(toolVersionFile.isFile, "-PinitTimeoutAudit must not vouch for a baseline it did not write")

    // yet init is still refused across a bump, like every record-writing flag:
    // the timeout population is just as version-dependent as the baseline's
    toolVersionFile.writeText("0.0.0-stale\n")
    File(fixtureDir, "config/pitest/encoding-timeouts.csv").delete()
    val refused = runner("pitestEncodingVerify", "-PinitTimeoutAudit").buildAndFail().output
    assertTrue(
      refused.contains("refusing to rewrite the record across a tool bump"),
      "init not refused across a bump:\n$refused"
    )
  }

  @Test
  fun `a stale baseline row that timed out this run is not killed-or-moved`() {
    // A baseline SURVIVED row whose mutant reads TIMED_OUT this run is the
    // load-dependent detection the TIMED_OUT doctrine warns about — prune keeps it,
    // so counting it in "stale entries (since killed or moved)" both contradicted
    // the drift warning and recommended a refresh that is a no-op for it. The
    // refresh hint must count only rows that are genuinely gone.
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
      output.contains("1 stale entries (since killed)"),
      "genuinely gone row not counted:\n$output"
    )
    assertTrue(
      output.contains("1 baseline row(s) read TIMED_OUT this run") &&
          output.contains("no refresh needed (prune keeps them)") &&
          output.contains("com.example.Codec,encode,MathMutator,SURVIVED"),
      "timed-out flip not reported separately:\n$output"
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
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
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
      warned.contains("1 audited-timeout member(s) whose class and method appear nowhere together in config/pitest/README.md") &&
          warned.contains("cause? com.example.Codec,encode,MathMutator"),
      "unwritten cause not noticed:\n$warned"
    )

    readme.writeText("# Baseline\n\n`Codec.encode:12` (MathMutator): the inflated estimate crawls, never fails.\n")
    val documented = runner("pitestEncodingVerify").build().output
    assertFalse(
      documented.contains("audited-timeout member(s) whose class and method"),
      "documented cause still noticed:\n$documented"
    )
  }

  @Test
  fun `the cause check requires the class name next to the method name`() {
    // Method-only matching was trivially satisfied: most dispatch members are named
    // 'handle', which appears in any README that mentions handlers at all — prose
    // about a different class entirely passed as this member's cause.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
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
  fun `an audited member timing out away from its recorded line is drift, not silence`() {
    // The '# line' comment is the anchor the README cause argues about, and
    // "re-read the cause when that code changes" was purely social: the key only
    // goes stale when the method disappears, never when the code moves within one.
    // The report holds the observed side, so the verify compares — disjointness
    // only, since a new sibling line next to a recorded one is the line-less key's
    // stated no-warning resolution.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator # line 12\n")
    fun report(vararg lines: Int) = writeReport(
      lines.map {
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,$it,TIMED_OUT,none"
      },
      ""
    )

    report(99)
    val drifted = runner("pitestEncodingVerify").build().output
    assertTrue(
      drifted.contains("1 audited-timeout member(s) timed out at line(s) their row's comment does not name") &&
          drifted.contains("  com.example.Codec,encode,MathMutator # line(s) 12 -> observed 99"),
      "drifted anchor not warned:\n$drifted"
    )
    assertTrue(
      drifted.contains("1 line-drifted audit row(s)"),
      "drift missing from the advisory summary:\n$drifted"
    )

    // a sibling line landing next to a still-live recorded one draws no warning
    report(12, 99)
    val sibling = runner("pitestEncodingVerify").build().output
    assertFalse(
      sibling.contains("line-drifted") || sibling.contains("comment does not name"),
      "sibling line next to the recorded anchor read as drift:\n$sibling"
    )

    // a row whose comment names no line recorded no anchor: nothing to drift from
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator # removed loop exit\n")
    report(99)
    val anchorless = runner("pitestEncodingVerify").build().output
    assertFalse(
      anchorless.contains("line-drifted") || anchorless.contains("comment does not name"),
      "anchorless member read as drift:\n$anchorless"
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
      output.contains("1 malformed row(s) in encoding-timeouts.csv") &&
          output.contains("expected 'class,method,mutator'") &&
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
  fun `-PinitTimeoutAudit seeds the audited set from the report and refuses to reseed`() {
    // Adoption's mechanical half is derivable from the report the tool already has;
    // hand-transcribing members from mutations.xml is exactly the kind of work
    // '-PupdateMutationBaseline' exists to avoid for baselines. Seeding writes the
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

    val seeded = runner("pitestEncodingVerify", "-PinitTimeoutAudit").build().output
    assertTrue(
      seeded.contains("seeded 2 audited-timeout member(s) into encoding-timeouts.csv"),
      "seed summary missing:\n$seeded"
    )
    val written = timeoutsFile.readText()
    // sibling timeouts of one member collapse to one row, both observed lines kept
    assertTrue(
      written.contains("com.example.Codec,encode,MathMutator # lines 12, 30") &&
          written.contains("com.example.Codec,decode,IncrementsMutator # line 44"),
      "seeded membership wrong:\n$written"
    )
    assertFalse(written.contains("KILLED") || written.contains("decode,MathMutator"), "non-timeout seeded:\n$written")

    // the freshly seeded set satisfies its own audit
    val audited = runner("pitestEncodingVerify").build().output
    assertFalse(
      audited.contains("not in the audited set") || audited.contains("-PinitTimeoutAudit"),
      "seeded set did not satisfy the audit:\n$audited"
    )

    // membership changes one reviewed row at a time after adoption: no reseeding
    val refused = runner("pitestEncodingVerify", "-PinitTimeoutAudit").buildAndFail().output
    assertTrue(
      refused.contains("encoding-timeouts.csv already exists"),
      "reseed not refused:\n$refused"
    )
  }

  @Test
  fun `-PinitTimeoutAudit refuses a report with nothing timed out`() {
    // An empty seed would activate the audit while telling its adopter to write
    // causes for zero members — the flag is pointed at by a summary that reported
    // timeouts, and a run where they vanished is load noise, not a population.
    writeFixture()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,decode,50,KILLED,com.example.CodecTest",
      ),
      ""
    )

    val refused = runner("pitestEncodingVerify", "-PinitTimeoutAudit").buildAndFail().output
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
  fun `a member that stops timing out is noticed after three quiet runs`() {
    // Membership is validated against all mutants, so a member whose mutants exist
    // but never time out — pasted from the wrong report, or a timeout the tests
    // since learned to kill — was accepted forever. One quiet run is just the
    // KILLED<->TIMED_OUT load flip; the notice waits for the flip-family retirement
    // criterion (3 quiet cycles) and resets whenever the member times out again.
    writeFixture()
    val timeoutsFile = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeoutsFile.parentFile.mkdirs()
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
    fun report(status: String) = writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,$status," +
            (if (status == "KILLED") "com.example.CodecTest" else "none"),
      ),
      ""
    )

    report("KILLED")
    val quietNotice = "audited-timeout member(s) have not timed out in 3+"
    // a fresh PIT run rewrites the report; the content here is identical, so the test
    // advances the timestamp explicitly rather than racing the filesystem clock
    val reportCsv = File(fixtureDir, "build/reports/pitest/encoding/mutations.csv")
    fun freshen() = reportCsv.setLastModified(reportCsv.lastModified() + 1_000)

    // the counter is keyed to the report's fingerprint: standalone verify re-runs of
    // one unchanged report are one observation, not manufactured quiet evidence
    repeat(3) {
      val rerun = runner("pitestEncodingVerify").build().output
      assertFalse(rerun.contains(quietNotice), "an unchanged report advanced the quiet streak:\n$rerun")
      // a quiet member is not the stale case: its mutant is present, just detected
      assertFalse(rerun.contains("match no mutant"), "quiet member misread as stale:\n$rerun")
    }
    freshen()
    val second = runner("pitestEncodingVerify").build().output
    assertFalse(second.contains(quietNotice), "notice fired before the third quiet report:\n$second")
    freshen()
    val third = runner("pitestEncodingVerify").build().output
    assertTrue(
      third.contains(quietNotice) &&
          third.contains("com.example.Codec,encode,MathMutator (quiet for 3 runs)"),
      "third quiet report not noticed:\n$third"
    )
    // the retirement criterion family is one advisory tier: like its siblings the
    // notice feeds the end-of-build summary, or a gate scrolls it off screen
    assertTrue(
      third.contains("1 quiet audited-timeout member(s)"),
      "quiet streak missing from the advisory summary:\n$third"
    )

    // same report, re-run: the counts replay and so does the notice — like every
    // other audit advisory it is derived from the report, not from having printed
    val replay = runner("pitestEncodingVerify").build().output
    assertTrue(replay.contains(quietNotice), "replayed evidence lost the notice:\n$replay")

    // a timeout resets the streak — the notice must go quiet again for 3 more runs
    report("TIMED_OUT")
    val reset = runner("pitestEncodingVerify").build().output
    assertFalse(reset.contains(quietNotice), "timeout did not reset the quiet streak:\n$reset")
    report("KILLED")
    val afterReset = runner("pitestEncodingVerify").build().output
    assertFalse(afterReset.contains(quietNotice), "streak not restarted from zero:\n$afterReset")
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
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
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
    runner("pitestEncodingVerify").build()
    encodeReport()
    runner("pitestEncodingVerify").build()

    staleReport()
    val stale = runner("pitestEncodingVerify").build().output
    assertTrue(stale.contains("match no mutant"), "stale member not warned:\n$stale")

    encodeReport()
    val returned = runner("pitestEncodingVerify").build().output
    assertFalse(
      returned.contains("audited-timeout member(s) have not timed out in 3+"),
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
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\ncom.example.Codec,encode\n")
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
      failed.contains("-PstrictTimeoutAudit — 1 unaudited timed-out mutant(s)") &&
          failed.contains("1 malformed membership row(s)"),
      "strict run did not fail on the unaudited newcomer and the malformed row:\n$failed"
    )
    // the escalated findings are the failure, not advisories: the end-of-build
    // summary opens with "none failed the build", which must stay true
    assertFalse(
      failed.contains("unaudited timeout(s)") || failed.contains("malformed audit row(s)") ||
          failed.contains("audited timeout(s) without a README cause"),
      "strict-escalated finding also recorded as an advisory:\n$failed"
    )

    // a member admitted without its README cause is an unfinished admission, not
    // hygiene — the doctrine admits a newcomer only with its cause written, so the
    // certifying run stops on it too; row-then-cause is a legitimate sequence
    // between certifications, not during one
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator\ncom.example.Codec,decode,IncrementsMutator\n"
    )
    val causeless = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      causeless.contains("2 audited member(s) without a README cause"),
      "strict run did not fail on the unwritten causes:\n$causeless"
    )

    // with the causes written, a fully audited set passes strict even with hygiene
    // findings outstanding (the stale member below stays advisory)
    timeoutsFile.appendText("com.example.Codec,gone,MathMutator\n")
    File(fixtureDir, "config/pitest/README.md").writeText(
      "`Codec.encode` (MathMutator): the inflated estimate crawls, never fails.\n\n" +
          "`Codec.decode` (IncrementsMutator): the reversed cursor re-reads forever.\n"
    )
    val strictClean = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").build().output
    assertTrue(
      strictClean.contains("match no mutant"),
      "hygiene finding expected for the stale member:\n$strictClean"
    )

    // an unadopted timeout-carrying suite is an unaudited newcomer by definition
    timeoutsFile.delete()
    val unadopted = runner("pitestEncodingVerify", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      unadopted.contains("no audited") && unadopted.contains("-PinitTimeoutAudit"),
      "strict run did not fail on the unadopted suite:\n$unadopted"
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
      output.contains("hardening: 2 advisory finding(s) across 1 suite(s)") &&
          output.contains("pitest 'encoding': 1 unaudited timeout(s), 1 stale audit row(s)"),
      "advisory summary missing:\n$output"
    )

    // a clean run must not print an empty summary
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the estimate crawls, never fails.\n")
    val clean = runner("pitestEncodingVerify").build().output
    assertFalse(clean.contains("advisory finding"), "summary printed with nothing to say:\n$clean")
  }

  @Test
  fun `a killed sibling's note dies with its own row, by line affinity`() {
    // Two same-key rows, one noted; the noted mutant (recorded at line 12) was
    // killed. Within a key the update assigns accepted rows by line affinity, so the
    // surviving line-20 mutant takes the pair recorded at 20 and the noted line-12
    // pair drops — with its note's fate named. Without recorded lines the assignment
    // would be file-order-arbitrary; the tags every refresh writes are what keep
    // this determinate.
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
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
    // the rewrite migrates the legacy row; the added row lands bare with its line tag
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # untriaged flip insurance # line 5",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 12"
      ),
      baselineFile().readLines(),
      "union must keep the absent row, its note, and append the new row in sorted order"
    )

    val idempotent = runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertTrue(idempotent.output.contains("union added nothing new"), idempotent.output)

    val update = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertTrue(update.output.contains("dropped 1 row(s) not unkilled this run"), update.output)
    assertTrue(update.output.contains("com.example.Codec,decode,MathMutator,SURVIVED # untriaged flip insurance # line 5"), update.output)
    assertTrue(update.output.contains("-PunionMutationBaseline"), update.output)
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # line 12"),
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
    runner("pitestModeSnapshot", "-PpitestMode=solo").build()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,SURVIVED,none",
      ),
      ""
    )
    runner("pitestModeSnapshot", "-PpitestMode=gate").build()

    // one accepted row, two flipped siblings: NOT insured
    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertFalse(compare.output.contains("(already insured in the baseline)"), compare.output)

    // the union writes exactly the shortfall, keeping the existing row verbatim
    val union = runner("pitestModeCompare", "-PunionModeFlips").build()
    assertTrue(union.output.contains("flip insurance written"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # earlier insurance # line 12",
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
    runner("pitestModeSnapshot", "-PpitestMode=solo").build()
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,40,SURVIVED,none",
      ),
      ""
    )
    runner("pitestModeSnapshot", "-PpitestMode=gate").build()

    val compare = runner("pitestModeCompare").buildAndFail()
    assertTrue(compare.output.contains("1 uninsured boundary flip(s)"), compare.output)
    assertFalse(
      compare.output.contains("benign — cannot move the ratchet"),
      "a gated count change must not read as benign:\n" + compare.output
    )

    val union = runner("pitestModeCompare", "-PunionModeFlips").build()
    assertTrue(union.output.contains("flip insurance written"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # the always-surviving twin # line 40",
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
  fun `a committed seed larger than maxLen is refused, never truncated`() {
    // libFuzzer clips oversized inputs on load: a fuzz run explores a truncated copy,
    // and the minimize merge re-hashes the clip — adopting it hash-named and deleting
    // the named original. Both tasks must refuse before Jazzer runs, naming only the
    // offending seed, and the committed corpus must be untouched.
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

    // an uncapped target has nothing to refuse — the check is inert, not a new demand
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
    assertFalse(resourceBased.contains(fixtureDir.absolutePath), "resource corpus must not bake an absolute path:\n$resourceBased")

    val pathBased = generatedRoot.resolve("OutsideFuzzSeedReplayTest.java").readText()
    assertTrue(pathBased.contains("Files.isDirectory"), pathBased)
    assertTrue(pathBased.contains("assertFalse(seeds.isEmpty()"), pathBased)
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
    val declined = runner("generateFuzzReplayTests").build().output
    assertFalse(declined.contains("fuzz target 'bare' declares no seedCorpus"), declined)
    assertFalse(declined.contains("is stale"), declined)

    // Recorded with nothing: an argument-free suppression is not one.
    writeFixture(corpusless = true, corpuslessDecline = "   ")
    val blank = runner("generateFuzzReplayTests").build().output
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
    val contradicted = runner("generateFuzzReplayTests").build().output
    assertTrue(contradicted.contains("the recorded seedCorpus decline is stale"), contradicted)
    assertTrue(contradicted.contains("contradicts"), contradicted)
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
    writeFixture(recompileExcludes = listOf("Scratch.java"), registerFuzz = false, bytecodeRelease = 21)
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
    writeFixture(registerFuzz = false, bytecodeRelease = 21)
    runner("compileForPitest").build()
    assertTrue(recompiled.resolve("Scratch.class").isFile, "Scratch.class should return once un-excluded")
  }

  @Test
  fun `an update seeds new rows untriaged and never relabels accepted or sibling rows`() {
    // A genuinely new coordinate enters the baseline as explicit debt, never bare —
    // triage means replacing the seeded label. Pre-existing rows keep their state:
    // a labeled row keeps its label (surfaced sibling copies included, since notes
    // are keyed by row text), and a bare pre-seeding row stays bare rather than
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    // line affinity keeps '# race guard family' on its recorded line-12 mutant; the
    // second line-12 mutant is a new sibling and seeds '# untriaged' (its twin's
    // argument was written for the mutants it had), as does the new decode key
    assertEquals(
      listOf(
        "com.example.Codec,decode,MathMutator,SURVIVED # untriaged # line 33",
        "com.example.Codec,encode,MathMutator,SURVIVED # race guard family # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 20",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("2 new row(s) seeded '# untriaged'"), output)
    // the interrupted-refresh guard: the atomic write leaves no temp file behind
    assertFalse(File(baselineFile().parentFile, "${baselineFile().name}.tmp").exists())

    // idempotent: a second update seeds nothing and changes nothing
    val second = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
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
      output.contains("unkilled at line(s) no row's '# line' tag names") &&
          output.contains("# line(s) 53, 92 -> unrecorded 157"),
      "row-level drift advisory missing:\n$output"
    )
  }

  @Test
  fun `migrateMutationBaselines respells a legacy file and touches nothing else`() {
    // Format-only: no report, no PIT run — parse and re-render, comments preserved,
    // identity untouched. The refresh flags migrate too, but they need a green
    // mutation run; this path exists so fleet migration needs neither.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "# seeded 2026-07-27; see README\n" +
          "com.example.Codec,encode,12,MathMutator,SURVIVED # race guard family\n" +
          "com.example.Codec,encode,20,MathMutator,NO_COVERAGE\n"
    )

    val output = runner("migrateMutationBaselines").build().output
    assertTrue(output.contains("migrated 2 row(s) to the line-less format"), output)
    assertEquals(
      listOf(
        "# seeded 2026-07-27; see README",
        "com.example.Codec,encode,MathMutator,SURVIVED # race guard family # line 12",
        "com.example.Codec,encode,MathMutator,NO_COVERAGE # line 20",
      ),
      baselineFile().readLines()
    )

    // idempotent, and byte-identical files are not rewritten
    val second = runner("migrateMutationBaselines").build().output
    assertTrue(second.contains("already in the current format"), second)
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

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertTrue(output.contains("nothing unkilled — no baseline to write"), output)
    assertFalse(baselineFile().exists(), "update created a baseline with nothing to record")

    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 10\n")
    val removed = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertTrue(removed.contains("nothing unkilled — baseline file removed"), removed)
    assertFalse(baselineFile().exists(), "an emptied baseline must be removed, not left as a husk")

    // prune behaves the same when it drops every row
    baselineFile().writeText("com.example.Codec,encode,MathMutator,SURVIVED # since killed # line 10\n")
    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertTrue(pruned.contains("prune dropped every row since killed — baseline file removed"), pruned)
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
    baselineFile().parentFile.mkdirs()

    // a real write lands the stamp with the record
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertTrue(baselineFile().isFile && toolVersionFile.isFile, "stamp must land with the write")

    // the suite goes fully detected: the baseline is removed and the stamp with it
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest"),
      ""
    )
    val retired = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertTrue(retired.contains("nothing unkilled — baseline file removed"), retired)
    assertTrue(retired.contains("encoding-pitest-version removed with the record it certified"), retired)
    assertFalse(toolVersionFile.isFile, "orphan stamp left behind an emptied record")

    // with an audited timeout set present, the stamp still has a record to certify
    File(fixtureDir, "config/pitest/encoding-timeouts.csv")
      .writeText("com.example.Codec,encode,MathMutator # line 12\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertTrue(toolVersionFile.isFile, "stamp must land with the write")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest"),
      ""
    )
    val kept = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertTrue(kept.contains("nothing unkilled — baseline file removed"), kept)
    assertTrue(toolVersionFile.isFile, "the audited timeout set is a record; its stamp must stay")
  }

  @Test
  fun `the verify prints a per-label baseline breakdown`() {
    // Triage state is a number the build prints: one count per label, with carry
    // markers and flip details stripped ('# race guard family (carried across ...)'
    // still counts as 'race guard family'), and pre-seeding bare rows named as
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
      output.contains("4 rows — 2 '# untriaged', 1 '# race guard family', 1 unlabeled"),
      output
    )
  }

  @Test
  fun `the verify names an all-unlabeled baseline instead of staying silent`() {
    // A baseline that predates label seeding carries no notes, but it is exactly the
    // one worth nudging — so the summary still prints, naming every row unlabeled
    // rather than skipping the line and hiding that nothing is triaged.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(output.contains("2 rows — 2 unlabeled"), output)
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
      warned.contains("config/pitest/README.md — '# race guard family' — document the family"),
      warned
    )

    File(fixtureDir, "config/pitest/README.md").writeText(
      "# Triage\n\nRows labelled `# race guard family` are …\n"
    )
    val quiet = runner("pitestEncodingVerify").build().output
    assertFalse(quiet.contains("label(s) with no argument"), quiet)
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
      output.contains("config/pitest/README.md — '# capacity hint' — document the family"),
      output
    )

    File(fixtureDir, "config/pitest/README.md").writeText("# capacity hint\n\nGrowth arithmetic …\n")
    val quiet = runner("pitestEncodingDebt").build().output
    assertFalse(quiet.contains("label(s) with no argument"), quiet)
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
      output.contains("1 malformed row(s) in encoding-timeouts.csv"),
      "malformed row not named:\n$output"
    )
    assertTrue(
      output.contains("cause? com.example.Codec,encode,MathMutator"),
      "missing cause not named:\n$output"
    )

    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the estimate crawls, never fails.\n")
    val quiet = runner("pitestEncodingDebt").build().output
    assertFalse(
      quiet.contains("malformed") || quiet.contains("cause?"),
      "clean audit still warned:\n$quiet"
    )
  }
}
