import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
import java.io.File
import java.util.UUID

/**
 * Functional test for the mutation ratchet and the generators: fabricates PIT
 * reports (CSV + XML) so 'pitest<Suite>Verify' and the baseline flags can be exercised
 * without resolving or running PIT itself.
 */
class HardeningRatchetFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun writeFixture(
    generateTestSupport: Boolean = false,
    testSupportExcludes: List<String> = emptyList(),
    testSupportPackage: String? = null,
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
    extraSuites: String = "",
    beforeHardening: String = "",
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

$beforeHardening
        hardening {
          $releaseLine
          generateTestSupport = $generateTestSupport
          ${if (testSupportPackage == null) "" else "testSupportPackage = \"$testSupportPackage\""}
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

  /**
   * These ratchet tests fabricate CSVs rather than executing PIT. Snapshot first
   * through the plugin's legacy N-1 path, then attach deterministic provenance to
   * the stashed fixture so mode-compare's record-writing path can be exercised.
   * Real current-input validation is covered by HardeningToolExecFunctionalTest.
   */
  private fun modeSnapshot(label: String): BuildResult {
    val result = runner("pitestModeSnapshot", "-PpitestMode=$label").build()
    val snapshotDir = File(fixtureDir, "build/pitest-modes/$label")
    snapshotDir.listFiles { file -> file.isFile && file.extension == "csv" }.orEmpty()
        .forEach { report ->
          report.parentFile.resolve("${report.nameWithoutExtension}.evidence.tsv").writeText(
              PitestEvidence(
                  suite = report.nameWithoutExtension,
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
                  configurationSha256 = "fixture-configuration",
                  reportSha256 = PitestEvidence.sha256(report),
                  scope = PitestEvidence.FULL_SCOPE,
                  historyAssisted = false,
              ).render())
        }
    return result
  }

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

    // Prune remains shrink-only in identity, but refreshes metadata for rows it
    // matched to this run. That gives the advisory a safe clearing operation even
    // when its candidate preview is empty.
    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      "com.example.Codec,encode,MathMutator,SURVIVED # untriaged # line 12\n",
      baselineFile().readText(),
    )
    assertTrue(pruned.contains("prune dropped nothing and refreshed 1 line tag(s)"), pruned)
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("no row's '# line' tag names"),
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
    val output = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(output.contains("1 unkilled mutant(s) not in the accepted baseline"), output)
    assertTrue(
      output.contains("churn: 0 newly covered, 1 unexplained (of 1 new; 0 stale)"),
      "churn tally missing:\n$output"
    )
    assertTrue(output.contains("line 33: Replaced integer addition with subtraction"), "description missing:\n$output")
  }

  @Test
  fun `an unmatched row is previewed as a conditional prune candidate`() {
    // A single pass where an accepted row is absent cannot prove why: stable
    // removal and an uninsured solo-vs-gate flip have the same report shape. The
    // check names the exact row prune would remove, but does not prescribe it.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText("com.example.Codec,decode,30,MathMutator,SURVIVED\n")
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,KILLED,com.example.CodecTest.roundTrips"),
      ""
    )

    val output = runner("pitestEncodingVerify").build().output
    assertTrue(
      output.contains("1 row(s) are unmatched by this run") &&
          output.contains("pitestEncodingBaselinePrune would remove exactly these candidate row(s)") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # line 30"),
      "conditional candidate preview missing or did not name the exact row:\n$output"
    )
    assertFalse(
      output.contains("refresh with") || output.contains("since killed"),
      "one report must not claim a cause or prescribe a destructive refresh:\n$output"
    )
    assertTrue(
      output.contains("One fresh run cannot distinguish stable removal from an uninsured load- or mode-dependent flip"),
      "the preview did not explain what one report cannot prove:\n$output",
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
  fun `prune refuses a pending status flip before changing the baseline`() {
    // Shrink-only cannot mean gate-free: this run has a current SURVIVED row where
    // only NO_COVERAGE was accepted. The keep plan protects the old row as a pending
    // flip, but that does not accept the current status; prune must fail before it
    // drops the unrelated since-killed row or rewrites legacy line metadata.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val before =
      "com.example.Codec,encode,10,MathMutator,SURVIVED # untriaged\n" +
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").buildAndFail().output
    assertTrue(output.contains("refusing pitestEncodingBaselinePrune"), output)
    assertTrue(output.contains("1 gated mutant(s)"), output)
    assertTrue(output.contains("com.example.Codec,decode,IncrementsMutator,SURVIVED"), output)
    assertEquals(before, baselineFile().readText(), "a refused prune changed the baseline")
  }

  @Test
  fun `a flip-insured key is kept by prune and excluded from the candidate preview`() {
    // Flip-insurance rows record an OBSERVED flap: on a run where the mutant reads
    // killed, the row is not stale — pruning it would fail the next solo run with an
    // unexplained survivor, and the old stale hint used to recommend exactly that. The
    // marker rides in the note or its parenthetical ('flip insurance', the wording
    // -PunionModeFlips writes), and the row leaves by its written removal criterion,
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
      output.contains("1 row(s) are unmatched by this run") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # since killed # line 30"),
      "the unmarked row must still appear in the prune-candidate preview:\n$output"
    )
    assertTrue(
      output.contains("1 flip-insured row(s) unmatched at their own status this run") &&
          output.contains("com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family"),
      "insured flap not reported as such:\n$output"
    )

    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # handled-flag family (flip insurance: gate=KILLED, solo=SURVIVED) # line 10"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(pruned.contains("flip insurance at this key"), pruned)
    assertTrue(pruned.contains("prune dropped 1 row(s)"), pruned)

    // with only the insured row left, the run reports the flap alone — no removal candidate
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("would remove exactly these candidate row(s)"),
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

    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # keep-first # line 10",
        "com.example.Codec,encode,MathMutator,SURVIVED # keep-last # line 30",
      ),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
    assertTrue(output.contains("# since killed # line 20"), output)
    assertFalse(output.contains("flip pending triage"), "same-status siblings misread as a cross-status flip:\n$output")

    // rerunning against the same report is now a no-op — the counts agree
    val settled = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,NO_COVERAGE # unreachable claim # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() }
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
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
      hinted.contains("1 row(s) are unmatched by this run") &&
          hinted.contains("com.example.Codec,encode,MathMutator,SURVIVED # second # line 24"),
      "the excess row must be named as the exact candidate:\n$hinted"
    )
    assertTrue(hinted.contains("1 baseline row(s) read TIMED_OUT this run"), hinted)

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # first # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "one timeout budget must keep exactly one row:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
    assertTrue(output.contains("kept 1 unmatched row(s)"), output)
    assertTrue(output.contains("TIMED_OUT this run (load-dependent)"), output)
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # slow guard # line 24"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the budget must follow the timed-out line, not file order:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
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
      .dropWhile { !it.contains("read TIMED_OUT this run") }.take(2).joinToString("\n")
    assertTrue(
      timedOutHint.contains("com.example.Codec,encode,MathMutator,NO_COVERAGE # b # line 24"),
      "the hint must name the affine row as kept:\n$hinted"
    )
    assertTrue(
      hinted.contains("1 row(s) are unmatched by this run") &&
          hinted.contains("com.example.Codec,encode,MathMutator,SURVIVED # a # line 20"),
      "the unmatched row must be named as the exact candidate:\n$hinted"
    )

    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,NO_COVERAGE # newly hung"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the killed-line row must not outrank the bare row for the timeout budget:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
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

    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
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
    assertTrue(output.contains("TIMED_OUT this run (load-dependent)"), output)
  }

  @Test
  fun `the refresh flags are mutually exclusive`() {
    writeFixture()
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    // a refused combination must consume nothing: the stash rewrite used to land
    // before the refusal, so the refused run spent the drift comparison's
    // previous state and the next legitimate run compared against the refusal
    val stash = File(fixtureDir, ".pitest-history/encoding.statuses")
    stash.parentFile.mkdirs()
    val stashBefore = "# stash format 2\ncom.example.Codec,decode,IncrementsMutator,SURVIVED\n"
    stash.writeText(stashBefore)
    val output = runner("pitestEncodingVerify", "-PpruneMutationBaseline", "-PupdateMutationBaseline")
      .buildAndFail().output
    assertTrue(output.contains("pass at most one of"), output)
    assertEquals(stashBefore, stash.readText(), "a refused combination consumed the drift stash:\n$output")

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
  fun `an interrupted run's report is refused as evidence`() {
    // The '.running' sentinel is written before PIT starts and cleared only after
    // a clean exit. PIT writes the CSV incrementally, so a crashed or interrupted
    // run leaves a partial file that looks complete — and the verify runs as the
    // failed task's finalizer, so without the sentinel a same-invocation
    // '-PpruneMutationBaseline' rewrites the baseline from whatever fraction of
    // the population PIT reached before dying.
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

    val pruneRefused = runner("pitestEncodingVerify", "-PpruneMutationBaseline").buildAndFail().output
    assertTrue(pruneRefused.contains("interrupted or failed run"), pruneRefused)
    assertEquals(baselineBefore, baselineFile().readText(), "a partial report pruned the baseline")
  }

  @Test
  fun `invalid statuses and malformed report rows fail before a baseline writer`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore = "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n"
    baselineFile().writeText(baselineBefore)

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,RUN_ERROR,none"),
      ""
    )
    val invalid = runner("pitestEncodingVerify", "-PpruneMutationBaseline").buildAndFail().output
    assertTrue(invalid.contains("not valid completed evidence"), invalid)
    assertTrue(invalid.contains("RUN_ERROR x1"), invalid)
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
    assertTrue(debt.contains("baseline (current report invalid)"), debt)
    assertTrue(debt.contains("1 survived"), debt)

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10"),
      ""
    )
    val malformed = runner("pitestEncodingVerify", "-PupdateMutationBaseline").buildAndFail().output
    assertTrue(malformed.contains("1 malformed CSV row(s)"), malformed)
    assertTrue(malformed.contains("incomplete population is not evidence"), malformed)
    assertEquals(baselineBefore, baselineFile().readText(), "a malformed report rewrote the baseline")
    assertFalse(
      File(fixtureDir, "config/pitest/encoding-pitest-version").exists(),
      "a refused report stamped the baseline"
    )
  }

  @Test
  fun `a history-assisted report cannot refresh, and the summary tag reads the marker`() {
    // Reused results are not observation: the checking path may read them (the
    // '[history]' tag names the reuse, from the report's own marker rather than
    // this invocation's configuration), but a baseline refresh or audit seed
    // written from them certifies numbers the run never earned.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore = "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n"
    baselineFile().writeText(baselineBefore)
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").writeText("")

    val checking = runner("pitestEncodingVerify").build().output
    assertTrue(checking.contains(" [history]"), "the summary tag must read the marker:\n$checking")

    val refused = runner("pitestEncodingVerify", "-PupdateMutationBaseline").buildAndFail().output
    assertTrue(refused.contains("history-assisted"), refused)
    assertTrue(refused.contains("-PnoMutationHistory"), refused)
    assertEquals(baselineBefore, baselineFile().readText(), "a reused report refreshed the baseline")

    // without the marker the same report is a full run: no tag, refresh allowed
    File(fixtureDir, "build/reports/pitest/encoding/.history-assisted").delete()
    val full = runner("pitestEncodingVerify").build().output
    assertFalse(full.contains(" [history]"), "tag printed without the marker:\n$full")
  }

  @Test
  fun `malformed baseline rows are named and block a refresh`() {
    // A wrong-field-count row parses into a key no mutant can match: it read as
    // "since killed" and the next refresh silently dropped it — the timeout
    // membership's malformed-row diagnosis, applied to the file it always
    // should have covered too.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val baselineBefore =
      "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n" +
          "com.example.Codec,encode\n"
    baselineFile().writeText(baselineBefore)
    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10,SURVIVED,none"),
      ""
    )

    val checking = runner("pitestEncodingVerify").build().output
    assertTrue(
      checking.contains("1 malformed row(s) in encoding-accepted.csv"),
      "malformed row not named:\n$checking"
    )
    // Debt diagnoses the same row on the same terms — it is the fleet canary's
    // whole view of these files — and its label breakdown excludes it, so the
    // two surfaces report the same row count
    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains("1 malformed row(s) in encoding-accepted.csv"),
      "Debt did not name the malformed row:\n$debt"
    )
    assertTrue(checking.contains("  com.example.Codec,encode"), checking)
    assertFalse(
      checking.contains("would remove exactly these candidate row(s)"),
      "a malformed row still read as a prune candidate:\n$checking"
    )

    val refused = runner("pitestEncodingVerify", "-PpruneMutationBaseline").buildAndFail().output
    assertTrue(refused.contains("Fix the row shape first"), refused)
    assertEquals(baselineBefore, baselineFile().readText(), "a refresh dropped the malformed row")
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
      checking.contains("would remove exactly these candidate row(s)"),
      "an indented comment read as a phantom row:\n$checking"
    )

    val updated = runner("pitestEncodingVerify", "-PupdateMutationBaseline").build().output
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
          unadopted.contains("pitestEncodingTimeoutAuditInit"),
      "adoption hint missing:\n$unadopted"
    )
    // the nudge prints the member rows paste-ready: a load-dependent timeout may not
    // reproduce for a later -PinitTimeoutAudit run, and without the rows here the
    // coordinate that timed out is recoverable only from the daemon log
    assertTrue(
      unadopted.contains("  com.example.Codec,encode,MathMutator # line 12") &&
          unadopted.contains("  com.example.Codec,encode,IncrementsMutator # line 30"),
      "adoption hint rows not paste-ready:\n$unadopted"
    )

    // the paste round trip: the nudged rows, written as the membership file, must arm
    // the audit — no adoption nudge, no unaudited-newcomer warning, no stale notice
    timeoutsFile.writeText(
      "com.example.Codec,encode,MathMutator # line 12\n" +
          "com.example.Codec,encode,IncrementsMutator # line 30\n"
    )
    val adopted = runner("pitestEncodingVerify").build().output
    assertFalse(
      adopted.contains("no audited set") || adopted.contains("not in the audited set") ||
          adopted.contains("match no mutant"),
      "pasted nudge rows did not arm the audit cleanly:\n$adopted"
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
    // a stale template marker under the canary's own local-repo flag -> 'marker dance'
    File(fixtureDir, "AGENTS.md").writeText("# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n")
    writeReport(
      listOf(
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,TIMED_OUT,none",
        // an unaudited timed-out newcomer -> 'not in the audited set'
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.IncrementsMutator,encode,30,TIMED_OUT,none",
        "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.InvertNegsMutator,decode,40,SURVIVED,none",
      ),
      ""
    )

    // every finding is advisory, so the run passes — the advisory summary at the
    // end of the build supplies the pattern's 'advisory finding' alternation, the
    // Debt task rides along for the fragments only its static halves emit, and
    // agentsTemplateInSync runs under the canary's flag for the marker-dance one
    val output = runner(
      "pitestEncodingVerify", "pitestEncodingDebt", "pitestDeclinesDebt",
      "agentsTemplateInSync", "-PsavaBuildLocalRepo=unreleased-checkout"
    ).build().output

    // Debt deliberately soft-fails an unusable current report so it remains a
    // triage surface. The fleet canary must nevertheless reprint both the generic
    // fallback warning and the parser's reason; otherwise an ordinary sweep turns
    // a corrupt report into an invisible green observation.
    writeReport(listOf("Codec.java,com.example.Codec,broken"), "")
    val invalidDebtOutput = runner("pitestEncodingDebt").build().output
    val allCanaryOutput = output + invalidDebtOutput
    pattern.split('|').forEach { fragment ->
      assertTrue(
        allCanaryOutput.contains(fragment),
        "canary pattern fragment '$fragment' matches nothing — reworded warning?\n$allCanaryOutput"
      )
    }

    // The canary's reprint_findings rides two-space-indented payload rows along
    // with their matched header — the paste-ready member rows and marker lines
    // the headers tell a person to act on. Pin both sides of that contract: the
    // script's indent rule verbatim (edit the awk and this names the line), and
    // the plugin's listing indentation, by replaying the same rule over this
    // run's real output and finding the rows the headers promise.
    assertTrue(
      script.contains("keep == 1 && /^  / { print; next }"),
      "fleet-canary.sh's reprint indent rule moved — update this pin and the replay below together"
    )
    val reprint = buildString {
      var keep = false
      val headerRegex = Regex(pattern)
      output.lineSequence().forEach { line ->
        when {
          headerRegex.containsMatchIn(line) -> {
            appendLine(line)
            keep = true
          }
          keep && line.startsWith("  ") -> appendLine(line)
          else -> keep = false
        }
      }
    }
    assertTrue(
      reprint.contains("\n  com.example.Codec,encode,IncrementsMutator # line 30"),
      "the unaudited-set warning's paste-ready row no longer rides the reprint — " +
          "listing indentation changed from two spaces?\n$reprint"
    )
    assertTrue(
      reprint.contains("\n  <!-- hardening-template sha256:"),
      "the marker-dance payload line no longer rides the reprint:\n$reprint"
    )
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
      output.contains("1 row(s) are unmatched by this run") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # line 40"),
      "unmatched row not previewed exactly:\n$output"
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
      audited.contains("not in the audited set") || audited.contains("pitestEncodingTimeoutAuditInit"),
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
  fun `the deep leg filter matches every stash-cycle message`() {
    // The canary's deep leg greps run output with deep_pattern — message-text
    // coupling on the same terms as findings_pattern, but for the stash-cycle
    // lines only two consecutive real runs can provoke, so the reprint-filter
    // test cannot cover them in its single run. Provokes all four across two
    // verify runs (the reset notice only prints against a legacy stash, which
    // that run then cannot also compare) and holds the script's own pattern to
    // the combined output — reword one of the messages and this names the line.
    val script = File(savaBuildTestProperty("savaBuild.root"), "tools/fleet-canary.sh").readText()
    val pattern = Regex("(?m)^deep_pattern='([^']+)'").find(script)?.groupValues?.get(1)
      ?: error("deep_pattern line not found in tools/fleet-canary.sh")

    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # line 10\n" +
          "com.example.Codec,encode,IncrementsMutator,NO_COVERAGE # line 15\n" +
          "com.example.Codec,decode,IncrementsMutator,SURVIVED # line 40\n"
    )
    val stash = File(fixtureDir, ".pitest-history/encoding.statuses")
    stash.parentFile.mkdirs()
    stash.writeText("com.example.Codec,encode,10,MathMutator,SURVIVED\n")
    fun mutant(method: String, mutator: String, line: Int, status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.$mutator,$method,$line,$status,none"
    // run 1: the legacy stash provokes the reset notice
    writeReport(
      listOf(
        mutant("encode", "MathMutator", 10, "SURVIVED"),
        mutant("encode", "IncrementsMutator", 15, "NO_COVERAGE"),
        mutant("decode", "IncrementsMutator", 40, "SURVIVED"),
      ),
      ""
    )
    val first = runner("pitestEncodingVerify").build().output
    // run 2: the encode survivor became a timeout (the flip warning), the
    // never-reached mutant became one too (the NO_COVERAGE flip warning), and a
    // fresh key timed out with its survivors intact (the drift line)
    writeReport(
      listOf(
        mutant("encode", "MathMutator", 10, "TIMED_OUT"),
        mutant("encode", "IncrementsMutator", 15, "TIMED_OUT"),
        mutant("decode", "IncrementsMutator", 40, "SURVIVED"),
        mutant("decode", "InvertNegsMutator", 44, "TIMED_OUT"),
      ),
      ""
    )
    val combined = first + runner("pitestEncodingVerify").build().output
    pattern.split('|').forEach { fragment ->
      assertTrue(
        combined.contains(fragment),
        "deep pattern fragment '$fragment' matches nothing — reworded message?\n$combined"
      )
    }
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
    assertFalse(flipped.contains("previously detected"), "a never-detected mutant claimed as detected:\n$flipped")
    assertFalse(flipped.contains("flipped SURVIVED"), flipped)
    assertTrue(flipped.contains("NO_COVERAGE -> TIMED_OUT flip(s)"), "advisory summary missing:\n$flipped")
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

    val union = runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # race guard # line 40",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 10",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the existing row must consume its own tagged line; the added row takes the unclaimed one"
    )

    // the rewritten file describes this run completely: no drift advisory
    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("no row's '# line' tag names"),
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

    val union = runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertTrue(union.output.contains("union added 1 entries"), union.output)
    assertEquals(
      listOf(
        "com.example.Codec,encode,MathMutator,SURVIVED # flip insurance (gate=SURVIVED, solo=KILLED) # lines 20, 40",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 20",
        "com.example.Codec,encode,MathMutator,SURVIVED # line 10",
      ),
      baselineFile().readLines().filter { it.isNotBlank() },
      "the appended row must take the genuinely unclaimed line"
    )

    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(
      settled.contains("no row's '# line' tag names"),
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
      unadopted.contains("no audited") && unadopted.contains("pitestEncodingTimeoutAuditInit"),
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
      output.contains("hardening: 3 advisory finding(s) across 1 suite(s)") &&
          output.contains(
            "pitest 'encoding': report has no completed-run evidence manifest, " +
                "1 unaudited timeout(s), 1 stale audit row(s)"),
      "advisory summary missing:\n$output"
    )

    // Fixing the timeout set removes those two findings. The fabricated report still
    // deliberately lacks a completed-run evidence manifest, so only that migration
    // advisory remains.
    timeoutsFile.writeText("com.example.Codec,encode,MathMutator\n")
    File(fixtureDir, "config/pitest/README.md")
      .writeText("`Codec.encode` (MathMutator): the estimate crawls, never fails.\n")
    val clean = runner("pitestEncodingVerify").build().output
    assertTrue(
      clean.contains("hardening: 1 advisory finding(s) across 1 suite(s)") &&
          clean.contains("pitest 'encoding': report has no completed-run evidence manifest") &&
          !clean.contains("unaudited timeout") && !clean.contains("stale audit row"),
      "timeout advisories survived a clean timeout audit:\n$clean"
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
    assertTrue(update.output.contains("pitestEncodingBaselineUnion"), update.output)
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
  fun `legacy suite refresh and mode insurance cannot write the same baseline`() {
    writeFixture()
    baselineFile().parentFile.mkdirs()
    val before =
      "com.example.Codec,decode,MathMutator,SURVIVED # reviewed fixture # line 50\n"
    baselineFile().writeText(before)
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")

    // Give mode insurance a real row to add if it were allowed to reach its writer.
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")
    // Give the suite update a different real rewrite to make either ordering
    // observably destructive. Under --continue both consumers must independently
    // reject the shared legacy selection before either commits a record.
    writeReport(listOf(mutant("SURVIVED")), "")

    val output = runner(
      "pitestEncodingVerify",
      "pitestModeCompare",
      "-PupdateMutationBaseline",
      "-PunionModeFlips",
      "--continue",
    ).buildAndFail().output

    val refusal =
      "legacy hardening writers are mutually exclusive: do not combine " +
          "-PupdateMutationBaseline with -PunionModeFlips"
    assertTrue(output.contains(refusal), output)
    assertEquals(
      before,
      baselineFile().readText(),
      "combined legacy writer families changed the baseline under --continue",
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

    val union = runner("pitestModeCompare", "-PunionModeFlips").build().output
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
      verified.contains("would remove exactly these candidate row(s)"),
      "a killed observation offered persistent flip insurance as a prune candidate:\n$verified",
    )
    assertTrue(
      verified.contains("1 flip-insured row(s) unmatched at their own status this run"),
      verified,
    )

    val beforePrune = baselineFile().readText()
    val pruned = runner("pitestEncodingVerify", "-PpruneMutationBaseline").build().output
    assertEquals(beforePrune, baselineFile().readText(), "prune removed or rewrote persistent insurance")
    assertTrue(pruned.contains("prune dropped nothing"), pruned)
    assertTrue(pruned.contains("flip insurance at this key"), pruned)
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
    // modeCompare's -PunionModeFlips rewrite is a baseline writer like the
    // verify's refresh flags: a malformed row would be silently dropped (refused
    // instead), and comment lines survive the row-slot rewrite.
    writeFixture()
    baselineFile().parentFile.mkdirs()
    baselineFile().writeText(
      "  # context for the row below\n" +
          "com.example.Codec,decode,IncrementsMutator,SURVIVED # line 50\n" +
          "com.example.Codec,encode\n"
    )
    fun mutant(status: String) =
      "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")

    val refused = runner("pitestModeCompare", "-PunionModeFlips").buildAndFail().output
    assertTrue(refused.contains("1 malformed row(s)"), refused)
    assertTrue(refused.contains("Fix the row shape first"), refused)

    // with the malformed row fixed, the union writes without disturbing the comment
    baselineFile().writeText(
      "  # context for the row below\n" +
          "com.example.Codec,decode,IncrementsMutator,SURVIVED # line 50\n"
    )
    val unioned = runner("pitestModeCompare", "-PunionModeFlips").build().output
    assertTrue(unioned.contains("flip insurance written"), unioned)
    assertFalse(unioned.contains("do not survive"), unioned)
    assertTrue(
      baselineFile().readText().contains("  # context for the row below\n"),
      "the insurance rewrite dropped its comment:\n${baselineFile().readText()}"
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
    val union = runner("pitestModeCompare", "-PunionModeFlips").build()
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

    val union = runner("pitestModeCompare", "-PunionModeFlips").build()
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
    assertEquals("prior snapshot", prior.readText(), "validation destroyed the prior snapshot")
    assertTrue(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "validation of a later suite deleted the earlier suite report",
    )
    assertTrue(parsingDir.resolve("mutations.csv").isFile)
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

    assertTrue(failed.contains("parsing-accepted.csv carries 1 malformed row(s)"), failed)
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
    fun mutant(status: String) =
      "Codec.java,com.example.Codec," +
          "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
          "encode,12,$status," +
          (if (status == "KILLED") "com.example.CodecTest" else "none")
    writeReport(listOf(mutant("KILLED")), "")
    modeSnapshot("solo")
    writeReport(listOf(mutant("SURVIVED")), "")
    modeSnapshot("gate")
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.named("pitestModeCompare") {
          val skipModeCompare = providers.gradleProperty("skipModeCompare").isPresent
          onlyIf { !skipModeCompare }
        }
      """.trimIndent() + "\n",
    )
    val baseline = baselineFile()
    val stamp = File(fixtureDir, "config/pitest/encoding-pitest-version")

    val failed = runner(
      "pitestModeCompareUnion",
      "-PskipModeCompare",
    ).buildAndFail().output

    assertTrue(failed.contains("not consumed"), failed)
    assertFalse(baseline.exists(), "a skipped compare wrote its prepared baseline")
    assertFalse(stamp.exists(), "a skipped compare wrote its PIT-version stamp")
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

    val failed = runner("generateFuzzReplayTests").buildAndFail().output

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
  fun `test support generates all helpers only when enabled and honors its package`() {
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

    writeFixture(generateTestSupport = true, testSupportPackage = "com.example.hardening.support")
    runner("generateHardeningTestSupport").build()
    val customDir = File(
      fixtureDir, "build/generated-sources/hardening-support/java/com/example/hardening/support")
    expected.forEach { name ->
      assertTrue(customDir.resolve("$name.java").isFile, "$name.java not generated in custom package")
      assertTrue(
        customDir.resolve("$name.java").readText().contains("package com.example.hardening.support;"),
        "$name.java retained the foreign default package")
    }
    val preservedSupport = customDir.resolve("Ports.java").readText()

    writeFixture(generateTestSupport = true, testSupportPackage = "not-a-package")
    val invalid = runner("generateHardeningTestSupport").buildAndFail().output
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
  fun `the source generators store a configuration cache entry`() {
    // Consumers run with the configuration cache on, and a task whose execution-time
    // lambda reaches a script-level helper cannot be serialized — the whole build fails
    // with "cannot serialize Gradle script object references", not just the task. It is
    // invisible to every other test here because they never pass the flag, and invisible
    // to the disabled path too: both generators are registered, realized as test
    // sources, and stored either way, so a repo that generates nothing still pays for
    // the capture. Both are covered because both have taken this defect: validating a
    // name from a 'doLast'/'doFirst' reads naturally and captures the whole script.
    listOf(false, true).forEach { enabled ->
      writeFixture(generateTestSupport = enabled)
      val tasks = arrayOf("generateHardeningTestSupport", "generateFuzzReplayTests")
      val stored = runner(*tasks, "--configuration-cache").build().output
      assertFalse(stored.contains("problems were found storing the configuration cache"), stored)
      assertFalse(stored.contains("cannot serialize Gradle script object references"), stored)

      // Without reuse the assertion above proves only that one run tolerated the flag.
      val reused = runner(*tasks, "--configuration-cache").build().output
      assertTrue(reused.contains("Reusing configuration cache"), reused)
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

    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
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
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
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
    runner("pitestEncodingVerify", "-PupdateMutationBaseline").build()
    assertEvidenceSurvived("update")
    assertEquals(2, baselineFile().readLines().count { it.startsWith("com.example.Codec,") })

    writeReport(listOf(survivor(10), survivor(20), survivor(30)), "")
    runner("pitestEncodingVerify", "-PunionMutationBaseline").build()
    assertEvidenceSurvived("union")
    assertEquals(3, baselineFile().readLines().count { it.startsWith("com.example.Codec,") })

    writeReport(listOf(survivor(10), survivor(20)), "")
    runner("pitestEncodingVerify", "-PpruneMutationBaseline").build()
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
