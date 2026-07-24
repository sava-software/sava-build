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
    testSupportExcludes: List<String> = emptyList(),
    recompileExcludes: List<String> = emptyList(),
    // the fuzz targets emit generated junit test sources; omit them when a test
    // actually compiles the fixture (the fixture declares no junit dependency)
    registerFuzz: Boolean = true,
    // caps the 'codec' target when a test exercises the oversized-seed refusal
    codecMaxLen: Int? = null,
    // pin the recompile's bytecode target when a test actually runs it: the fixture
    // sets no toolchain, so the recompile runs on the daemon JDK, which this build
    // pins to 21 via gradle/gradle-daemon-jvm.properties
    bytecodeRelease: Int? = null
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
      """.trimIndent()
    } else {
      ""
    }
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
          $releaseLine
          generateTestSupport = $generateTestSupport
          testSupportExcludes = listOf(${testSupportExcludes.joinToString(", ") { "\"$it\"" }})
          recompileExcludes = listOf(${recompileExcludes.joinToString(", ") { "\"$it\"" }})
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec")
            targetTests = "com.example.*Test*"
          }
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

    // pure drift passes on its own; the strict flag restores the failing diff
    val tolerated = runner("pitestEncodingVerify", "-PlistUnkilled").build().output
    assertTrue(
      tolerated.contains("1 row(s) moved line only"),
      "drift-tolerance notice missing:\n$tolerated"
    )

    val result = runner("pitestEncodingVerify", "-PlistUnkilled", "-PnoDriftTolerance").buildAndFail()
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
    // with new rows present, the stale hint must point at update-after-triage,
    // never at prune (which would drop the shifted row's old line without
    // writing the new one)
    assertTrue(
      output.contains("refresh with -PupdateMutationBaseline after the new rows below are triaged"),
      "mixed-case stale hint missing:\n$output"
    )
    assertFalse(output.contains("-PpruneMutationBaseline"), "must not recommend prune with new rows present:\n$output")
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

    // and neither refresh flavour may consume it
    val refused = runner("pitestEncodingVerify", "-PupdateMutationBaseline").buildAndFail().output
    assertTrue(
      refused.contains("cannot refresh the baseline"),
      "scoped refresh was not refused:\n$refused"
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
          "com.example.Codec,encode,14,MathMutator,SURVIVED\n" +
          "com.example.Codec,decode,30,MathMutator,SURVIVED # since killed\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,14,TIMED_OUT,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,10,MathMutator,SURVIVED # untriaged",
        "com.example.Codec,encode,12,MathMutator,NO_COVERAGE # unreachable claim",
        "com.example.Codec,encode,14,MathMutator,SURVIVED",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
    assertTrue(output.contains("com.example.Codec,decode,30,MathMutator,SURVIVED # since killed"), output)
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
      output.contains("(sibling of an accepted identical row — surfaced by the multiset comparison; pre-existing debt, not a regression)"),
      "sibling hint missing:\n$output"
    )
    assertTrue(
      output.contains("churn: 0 shifted, 0 newly covered, 1 surfaced sibling(s), 0 unexplained (of 1 new; 0 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(output.contains("pre-existing debt made visible"), output)
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
    assertEquals(
      listOf(
        "com.example.Codec,encode,12,MathMutator,SURVIVED # unreachable without a decoder fixture (carried across NO_COVERAGE -> SURVIVED)",
        "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged",
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
        "com.example.Codec,encode,12,MathMutator,SURVIVED # unreachable without a decoder fixture (carried across NO_COVERAGE -> SURVIVED)",
        "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
  }

  @Test
  fun `an update carries a note across a pure line shift, verbatim`() {
    // Editing above a mutated method shifts every row below it: the refresh drops
    // the old line's row and writes the new line's, and the note used to vanish
    // with the dropped row (casebook: the note the line shift dropped). It must
    // follow the shifted row verbatim — nothing about the mutant changed, so
    // unlike a status flip there is nothing to flag for re-reading — while a row
    // with an exact match keeps its note where it is.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # sibling operand, same documented family\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,13,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,13,MathMutator,SURVIVED # sibling operand, same documented family",
        "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("1 note(s) carried across a line shift"), output)
    assertFalse(output.contains("carried across a status flip"), output)
    assertTrue(output.contains("— note carried"), output)
    assertFalse(output.contains("note dropped with the row"), output)

    // idempotent: a second update finds exact rows and keeps both notes verbatim
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertEquals(
      listOf(
        "com.example.Codec,encode,13,MathMutator,SURVIVED # sibling operand, same documented family",
        "com.example.Codec,encode,20,MathMutator,SURVIVED # untriaged",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
  }

  @Test
  fun `a killed row's note does not migrate to a surviving sibling line`() {
    // The shift carry pairs dropped notes against *fresh* rows only, mirroring the
    // ratchet's shift classifier. A killed row leaves no fresh counterpart, so its
    // note dies with it instead of silently relabelling an unrelated survivor at
    // another line of the same method.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # killed since; this note must not travel\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,20,MathMutator,SURVIVED"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    // the loss is loud: the dropped listing names the note's fate and counts it
    assertTrue(output.contains("— note dropped with the row"), output)
    assertTrue(output.contains("1 note(s) dropped with their rows"), output)
    assertFalse(output.contains("— note carried"), output)
  }

  @Test
  fun `a killed row's note does not ride a surfaced sibling to another line`() {
    // The sharp edge of the shift carry: a killed row still reads SURVIVED in the
    // baseline, so it shares the class/method/mutator/status key with a live survivor
    // at another line. If that survivor's coordinate also holds a surfaced sibling — a
    // fresh row that exactly duplicates an accepted row, the pre-existing debt the
    // multiset comparison exposes — the extra fresh copy must NOT let the killed row's
    // note migrate onto it. The ratchet classifies surfaced siblings out before its
    // shift check; the carry must too, or the very migration it exists to prevent slips
    // through the duplicate.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,12,MathMutator,SURVIVED # killed since; this note must not travel\n" +
          "com.example.Codec,encode,20,MathMutator,SURVIVED\n"
    )
    // line 12's mutant is gone (killed); line 20 now reports the sibling twice
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,20,SURVIVED,none",
      ),
      ""
    )

    val output = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,20,MathMutator,SURVIVED",
        "com.example.Codec,encode,20,MathMutator,SURVIVED",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertFalse(output.contains("carried across a line shift"), output)
    assertTrue(output.contains("— note dropped with the row"), output)
    assertTrue(output.contains("1 note(s) dropped with their rows"), output)
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
      output.contains("churn: 0 shifted, 0 newly covered, 1 unexplained"),
      "moved-method row must be unexplained:\n$output"
    )
    assertFalse(output.contains("shifted from line 10"), "must not pair across methods:\n$output")
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
}
