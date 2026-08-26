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
import java.nio.file.Files
import java.util.UUID

/**
 * Functional test for the mutation ratchet and the generators: fabricates PIT
 * reports (CSV + XML) so 'pitest<Suite>Verify' and the named baseline writers can be exercised
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
    fakePit: Boolean = true,
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

        ${if (fakePit) {
          """
          tasks.named<JavaExec>("pitestEncoding") {
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
          tasks.register<Delete>("clearFakePitEvidence") {
            delete(
              layout.buildDirectory.file("reports/pitest/encoding/.evidence.tsv"),
              layout.buildDirectory.file("reports/pitest/encoding/.toolchain.tsv"),
              layout.buildDirectory.file("reports/pitest/encoding/.evidence-invocation"),
            )
            mustRunAfter(
              "pitestEncodingBaselineUpdate",
              "pitestEncodingBaselineUnion",
              "pitestEncodingBaselineRetag",
              "pitestEncodingBaselinePrune",
              "pitestEncodingTimeoutAuditInit",
            )
          }
          """.trimIndent()
        } else ""}
      """.trimIndent() + "\n"
    )
    if (fakePit) {
      File(fixtureDir, "src/main/java/com/example/FakePit.java").apply {
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
              if (reportDir == null) {
                throw new IllegalArgumentException("missing --reportDir");
              }
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
  }

  private fun writeReport(csvRows: List<String>, xmlMutations: String) {
    val csv = csvRows.joinToString("\n", postfix = "\n")
    val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations>\n$xmlMutations\n</mutations>\n"
    listOf(
      File(fixtureDir, "build/reports/pitest/encoding"),
      File(fixtureDir, "fixture-pit-report"),
    ).forEach { reportDir ->
      reportDir.mkdirs()
      reportDir.resolve("mutations.csv").writeText(csv)
      reportDir.resolve("mutations.xml").writeText(xml)
    }
  }

  private fun baselineFile() = File(fixtureDir, "config/pitest/encoding-accepted.csv")

  private fun writeProductionClassSource(binaryName: String) {
    require('$' !in binaryName) { "fixture helper supports top-level classes only: $binaryName" }
    val packageName = binaryName.substringBeforeLast('.', "")
    val simpleName = binaryName.substringAfterLast('.')
    val relative = binaryName.replace('.', '/') + ".java"
    File(fixtureDir, "src/main/java/$relative").apply {
      parentFile.mkdirs()
      writeText(buildString {
        if (packageName.isNotEmpty()) appendLine("package $packageName;")
        appendLine("public final class $simpleName {}")
      })
    }
  }

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(*args, "--stacktrace")

  /**
   * Most transition tests exercise row allocation, not legacy adoption. Bind an
   * existing fixture record through the public safe migration path first, using an
   * empty observation so rebase cannot add or remove a row, then restore the report
   * the actual writer is meant to consume.
   */
  private fun bindLegacyFixtureRecord() {
    val config = File(fixtureDir, "config/pitest")
    val recordExists = baselineFile().isFile || config.resolve("encoding-timeouts.csv").isFile
    val version = config.resolve("encoding-pitest-version")
    val toolchain = config.resolve("encoding-pitest-toolchain.tsv")
    if (!recordExists || version.isFile || toolchain.isFile) return

    val reportDirs = listOf(
      File(fixtureDir, "build/reports/pitest/encoding"),
      File(fixtureDir, "fixture-pit-report"),
    )
    val saved = reportDirs.flatMap { dir ->
      listOf("mutations.csv", "mutations.xml").map { name ->
        val file = dir.resolve(name)
        file to file.takeIf(File::isFile)?.readBytes()
      }
    }
    writeReport(
      listOf(
        "Bootstrap.java,com.example.ProvenanceBootstrap," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "bootstrap,1,KILLED,com.example.ProvenanceBootstrapTest",
      ),
      "",
    )
    runner("pitestEncodingBaselineRebase").build()
    saved.forEach { (file, bytes) ->
      if (bytes == null) file.delete() else file.writeBytes(bytes)
    }
    File(fixtureDir, "build/reports/pitest/encoding").let { reportDir ->
      listOf(".evidence.tsv", ".toolchain.tsv", ".evidence-invocation").forEach {
        reportDir.resolve(it).delete()
      }
    }
  }

  /**
   * The public named workflow runs the typed PIT task, but the fixture replaces
   * PIT's main class with FakePit. It copies the staged synthetic report while the
   * real task lifecycle creates current-invocation evidence for the writer.
   */
  private fun baselineUpdateRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineUpdate", "clearFakePitEvidence", *args)
  }

  private fun baselineUnionRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineUnion", "clearFakePitEvidence", *args)
  }

  private fun baselinePruneRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselinePrune", "clearFakePitEvidence", *args)
  }

  private fun baselineRetagRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineRetag", "clearFakePitEvidence", *args)
  }

  private fun timeoutAuditInitRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingTimeoutAuditInit", "clearFakePitEvidence", *args)
  }

  private fun modeCompareUnionRunner(vararg args: String) =
    runner("pitestModeCompareUnion", *args)

  /**
   * These ratchet tests fabricate CSVs rather than executing PIT. Snapshot first
   * through the plugin's legacy N-1 path, then attach deterministic provenance to
   * the stashed fixture so mode-compare's record-writing path can be exercised.
   * Real current-input validation is covered by HardeningToolExecFunctionalTest.
   */
  private fun modeSnapshot(label: String): BuildResult {
    bindLegacyFixtureRecord()
    // Produce the same modern evidence/toolchain pair as a real mode run. The
    // ratchet finalizer is excluded because these fixtures deliberately stage
    // opposite statuses that one baseline cannot accept simultaneously.
    runner("pitestEncoding", "-x", "pitestEncodingVerify").build()
    return runner("pitestModeSnapshot", "-PpitestMode=$label").build()
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
          moved.contains("# line(s) 10 -> unrecorded 12") &&
          moved.contains("1 line-drifted baseline key(s)"),
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
    assertTrue(
      pruned.contains("pre-write signal") &&
          pruned.contains("# line(s) 10 -> unrecorded 12") &&
          pruned.contains("pitestEncodingBaselineRetag"),
      "prune refreshed line metadata without first reporting the drift:\n$pruned",
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
      output.contains("after documenting each acceptance run pitestEncodingBaselineUnion"),
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
    assertTrue(before.contains("# line(s) 10 -> unrecorded 12"), before)
    assertTrue(
      before.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          before.contains("$subsumed # licensed subsumption # line 30"),
      before,
    )
    assertTrue(before.contains("pitestEncodingBaselineRetag"), before)

    val retagged = baselineRetagRunner().build().output
    assertTrue(
      retagged.contains("pre-write signal") &&
          retagged.contains("# line(s) 10 -> unrecorded 12") &&
          retagged.contains("retag refreshed 1 matched row line tag(s)") &&
          retagged.contains("preserved all 2 accepted row(s), including unmatched evidence"),
      retagged,
    )
    assertFalse(
      retagged.contains("line-drifted baseline key(s)"),
      "a successful retag was reprinted as unresolved advisory debt:\n$retagged",
    )
    assertEquals(
      "$drifted # live family # line 12\n" +
          "$subsumed # licensed subsumption # line 30\n",
      baselineFile().readText(),
    )

    val settled = runner("pitestEncodingVerify").build().output
    assertFalse(settled.contains("no row's '# line' tag names"), settled)
    assertTrue(
      settled.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          settled.contains("$subsumed # licensed subsumption # line 30"),
      "retag must not remove or hide the unmatched candidate:\n$settled",
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
    assertTrue(refused.contains("refusing pitestEncodingBaselineRetag"), refused)
    assertFalse(
      refused.contains("pre-write signal") || refused.contains("inspect the resulting diff"),
      "a refused retag promised a diff it did not write:\n$refused",
    )
    assertEquals(accepted, baselineFile().readText())
  }

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
      output.contains("1 row(s) are unmatched by this run") &&
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
          output.contains("one eligible observation only if review confirms the relevant solo/gate load context") &&
          output.contains("at least two distinct, matching fresh full history-free previews") &&
          output.contains("does not persist or infer that reviewed load context") &&
          output.contains("./gradlew :pitestEncoding -PnoMutationHistory --console=plain") &&
          output.contains("./gradlew :pitestEncodingBaselinePrune --console=plain") &&
          output.contains(":pitestEncodingBaselinePrune performs another fresh full history-free write-boundary run"),
      "the preview did not explain what one report cannot prove:\n$output",
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

    baselineUpdateRunner().build()
    val rows = baselineFile().readLines().filter { it.isNotBlank() }
    assertEquals(2, rows.size, "the refresh collapsed sibling rows:\n$rows")
  }

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

    val output = baselinePruneRunner().buildAndFail().output
    assertTrue(output.contains("refusing pitestEncodingBaselinePrune"), output)
    assertTrue(output.contains("1 gated mutant(s)"), output)
    assertTrue(output.contains("com.example.Codec,decode,IncrementsMutator,SURVIVED"), output)
    assertFalse(
      output.contains("pre-write signal"),
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
      output.contains("1 row(s) are unmatched by this run") &&
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
    assertTrue(pruned.contains("prune dropped 1 row(s)"), pruned)

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
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
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

    val output = baselinePruneRunner().build().output
    assertEquals(
      listOf("com.example.Codec,encode,MathMutator,SURVIVED # first # line 20"),
      baselineFile().readLines().filter { it.isNotBlank() },
      "one timeout budget must keep exactly one row:\n$output"
    )
    assertTrue(output.contains("prune dropped 1 row(s)"), output)
    assertTrue(output.contains("kept 1 unmatched row(s)"), output)
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

    assertEquals(baselineBefore, baselineFile().readText(), "a partial report pruned the baseline")
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
    val invalid = baselinePruneRunner().buildAndFail().output
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

    writeReport(
      listOf("Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,10"),
      ""
    )
    val malformed = baselineUpdateRunner().buildAndFail().output
    assertTrue(malformed.contains("1 malformed CSV row(s)"), malformed)
    assertTrue(malformed.contains("incomplete population is not evidence"), malformed)
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
    val statusBefore = statusStash.readBytes()
    val quietBefore = quietStash.readBytes()

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

    val refused = baselinePruneRunner().buildAndFail().output
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
    assertTrue(
      output.contains("BaselineUpdate is a complete report rewrite") &&
          output.contains("pre-write signal") &&
          output.contains("# line(s) 12, 20 -> unrecorded 13, 21") &&
          output.contains("# line(s) 40 -> unrecorded 41") &&
          output.contains("pitestEncodingBaselineRetag"),
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
        "3 physical TIMED_OUT mutant instance(s) across 1 line-less key(s) not in the audited set " +
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
    // The pasted member is live, not stale, but its seeded category remains an
    // explicit reviewer-stop until a person classifies the cause.
    assertTrue(
      pasted.contains("cause:untriaged has not been reviewed") &&
          pasted.contains("1 audited-timeout row(s) match no mutant"),
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
    assertTrue(
      unadopted.contains(
        "4 physical TIMED_OUT mutant instance(s) across 2 line-less key(s) and no audited set",
      ) &&
          unadopted.contains("pitestEncodingTimeoutAuditInit"),
      "adoption hint missing:\n$unadopted"
    )
    // the nudge prints the member rows paste-ready: a load-dependent timeout may not
    // reproduce for a later pitestEncodingTimeoutAuditInit run, and without the rows here the
    // coordinate that timed out is recoverable only from the daemon log
    assertTrue(
      unadopted.contains("  com.example.Codec,encode,MathMutator # cause:untriaged line 12") &&
          unadopted.contains(
            "  com.example.Codec,encode,IncrementsMutator # cause:untriaged lines 30, 50",
          ) &&
          unadopted.contains("# observed: line 30 TIMED_OUT x2") &&
          unadopted.contains("# observed: line 50 TIMED_OUT x1") &&
          unadopted.contains("round(testDurationMs × 2.0) + 1500 ms") &&
          unadopted.contains("no per-mutant budget can be calculated"),
      "adoption hint rows not paste-ready:\n$unadopted"
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
    val timeouts = File(fixtureDir, "config/pitest/encoding-timeouts.csv")
    timeouts.writeText(
      "com.example.Codec,decode,IncrementsMutator # cause:untriaged line 30\n",
    )
    File(fixtureDir, "config/pitest/README.md").writeText(
      "## Codec.decode\n\n`Codec.decode`: the reversed cursor never reaches its exit.\n",
    )
    writeReport(
      listOf(
        "Codec.java,com.example.Codec," +
            "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
            "encode,12,TIMED_OUT,none",
      ),
      "",
    )
    // A fresh completed report remains valid evidence about its own timeout
    // population even though the independent committed provenance pair is torn.
    // Print that reviewer-stop, then retain the provenance refusal.
    val torn = runner("pitestEncoding").buildAndFail().output
    assertTrue(torn.contains("committed mutation provenance is torn"), torn)
    assertTrue(
      torn.contains(
        "current full report contains 1 physical TIMED_OUT mutant instance(s) across " +
            "1 line-less key(s) outside",
      ) &&
          torn.contains("com.example.Codec,encode,MathMutator # cause:untriaged line 12") &&
          !torn.contains("# observed: line 12 TIMED_OUT x1") &&
          torn.contains("no per-mutant budget can be calculated") &&
          torn.contains("Retain these candidates for triage") &&
          torn.contains("do not add or classify them until provenance is repaired/rebased") &&
          torn.contains("cause:untriaged has not been reviewed") &&
          torn.contains("do not retire or rewrite them until provenance is repaired/rebased"),
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
          scopedTorn.contains("this is a scoped mutation report"),
      "scoped torn provenance did not explain the unavailable report audit:\n$scopedTorn",
    )
    assertFalse(
      scopedTorn.contains("physical TIMED_OUT mutant instance(s)") ||
          scopedTorn.contains("current full report contains") ||
          scopedTorn.contains("audited-timeout row(s) match no mutant"),
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
      output.contains("1 row(s) are unmatched by this run") &&
          output.contains("com.example.Codec,decode,MathMutator,SURVIVED # line 40"),
      "unmatched row not previewed exactly:\n$output"
    )
    assertTrue(
      output.contains("1 baseline row(s) read TIMED_OUT this run") &&
          output.contains("preserved by this run's timeout budget, not killed") &&
          output.contains("does not prove benign load or that the acceptance argument still holds") &&
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
    assertTrue(
      output.contains("2 timed out (watchdog detection; not a cause diagnosis)") &&
          output.contains("1 unaudited timeout key(s) also have accepted SURVIVED/NO_COVERAGE row(s)") &&
          output.contains(
            "2 physical TIMED_OUT mutant instance(s) across 1 line-less key(s) remain " +
                "unaudited, 1 overlapping line-less key(s) with accepted baseline row(s)",
          ) &&
          output.contains("accepted: com.example.Codec,encode,MathMutator,SURVIVED") &&
          output.contains("timeout candidate: com.example.Codec,encode,12,MathMutator") &&
          output.contains("timeout candidate: com.example.Codec,encode,20,MathMutator") &&
          output.contains("cannot prove whether the timeout is the accepted mutant or a sibling"),
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
        "2 physical TIMED_OUT mutant instance(s) across 1 line-less key(s) remain " +
            "unaudited, 1 overlapping line-less key(s) with accepted baseline row(s)",
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
        output.contains("audited-timeout member(s) have not timed out in 3+"),
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

    val quietNotice = "audited-timeout member(s) have not timed out in 3+"
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
        assertTrue(output.contains("1 quiet audited-timeout member(s)"), output)
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
        it.contains("audited-timeout member(s) carry invalid optional line metadata")
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
    val quietNotice = "audited-timeout member(s) have not timed out in 3+"
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
    assertTrue(
      third.contains(quietNotice) &&
          third.contains(
            "com.example.Codec,encode,MathMutator " +
                "(quiet for 3 runs; latest fresh report KILLED x1)"
          ),
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
          !changed.contains("audited-timeout member(s) have not timed out in 3+"),
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
      second.contains("audited-timeout member(s) have not timed out in 3+"),
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
        val quietNotice = "1 audited-timeout member(s) have not timed out in 3+"
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
    assertTrue(stale.contains("match no mutant"), "stale member not warned:\n$stale")

    encodeReport()
    val returned = runner("pitestEncoding").build().output
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
        "-PstrictTimeoutAudit — 1 physical TIMED_OUT mutant instance(s) across " +
            "1 line-less key(s) remain unaudited",
      ) &&
          failed.contains("1 malformed membership row(s)"),
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
      causeless.contains("2 audited member(s) without a README cause"),
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
        "4 audited-timeout member(s) lack an admissible cause classification") &&
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
        "-PstrictTimeoutAudit found 0 malformed membership row(s), 4 inadmissible or " +
            "unfinished cause classification(s)") &&
          strictDebt.contains("Debt has checked committed files only") &&
          strictDebt.contains("report-dependent strict checks require a full pitestEncoding") &&
          strictDebt.contains("This Debt invocation did not run PIT") &&
          strictDebt.contains("audited-timeout key(s) cover multiple mutant copies") &&
          strictDebt.contains("line 44 TIMED_OUT x1") &&
          strictDebt.contains("line 52 KILLED x1") &&
          strictDebt.contains("unverified read-only prior-report preview") &&
          strictDebt.contains("pitestEncoding -PnoMutationHistory") &&
          strictDebt.contains("1 survived, 0 no_coverage"),
      "strict Debt silently remained an advisory-only preview:\n$strictDebt",
    )
    val strict = runner("pitestEncoding", "-PstrictTimeoutAudit").buildAndFail().output
    assertTrue(
      strict.contains(
        "4 inadmissible or unfinished cause classification(s)") &&
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
          killedSibling.contains("physical TIMED_OUT mutant instance(s)"),
      "a valid same-key KILLED sibling was misclassified as a mixed timeout cause:\n$killedSibling",
    )
    assertTrue(
      killedSibling.contains("audited-timeout key(s) cover multiple mutant copies") &&
          killedSibling.contains("com.example.Codec,wait,VoidMethodCallMutator — 2 mutants") &&
          killedSibling.contains("line 44 TIMED_OUT x1") &&
          killedSibling.contains("line 52 KILLED x1") &&
          killedSibling.contains("non-timeout siblings are context, not proof"),
      "verify did not expose the key-level mutant population:\n$killedSibling",
    )
    val debtPopulation = runner("pitestEncodingDebt").build().output
    assertTrue(
      debtPopulation.contains("audited-timeout key(s) cover multiple mutant copies") &&
          debtPopulation.contains("line 44 TIMED_OUT x1") &&
          debtPopulation.contains("line 52 KILLED x1"),
      "Debt did not expose the same key-level mutant population:\n$debtPopulation",
    )

    val strictDebtClean = runner(
      "pitestEncodingDebt", "-PstrictTimeoutAudit",
    ).build().output
    assertTrue(
      strictDebtClean.contains("-PstrictTimeoutAudit committed-file preview is clean") &&
          strictDebtClean.contains("Report-dependent strict checks require a full pitestEncoding") &&
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
      output.contains("hardening: 5 advisory finding(s) across 1 scope(s)") &&
          output.contains(
            "pitest 'encoding': report has no completed-run evidence manifest, " +
                "committed record is legacy-unversioned, committed record is " +
                "legacy-toolchain-unbound, 1 physical TIMED_OUT mutant instance(s) across " +
                "1 line-less key(s) remain unaudited, 1 stale audit row(s)"),
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
      clean.contains("hardening: 3 advisory finding(s) across 1 scope(s)") &&
          clean.contains("pitest 'encoding': report has no completed-run evidence manifest") &&
          !clean.contains("remain unaudited") && !clean.contains("stale audit row"),
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
      preview.contains("2 row(s) are unmatched by this run") &&
          preview.contains("pitestEncodingBaselinePrune classifier marks exactly these row(s)") &&
          preview.contains(
            "com.example.Codec,encode,MathMutator,SURVIVED # encoding family # line 10") &&
          preview.contains(
            "com.example.Codec,newHelper,IncrementsMutator,SURVIVED # untriaged # line 30"),
      "the second fresh observation did not expose the complete pre-Prune decision:\n$preview"
    )

    val prune = baselinePruneRunner().build().output
    assertTrue(prune.contains("prune dropped 2 row(s)"), prune)
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
      unboundGate.contains("after documenting each acceptance run pitestEncodingBaselineUnion"),
      unboundGate,
    )

    // Bind the old row against the helper's empty gated observation. The next gate
    // is the ordinary current-provenance path, so its displayed Union command must
    // execute directly rather than relying on a hidden Rebase in the runner helper.
    bindLegacyFixtureRecord()
    val gate = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      gate.contains("after documenting each acceptance run pitestEncodingBaselineUnion") &&
          gate.contains("without removing unmatched evidence") &&
          gate.contains("BaselineUpdate is a complete report rewrite, not remediation"),
      "incremental gate did not point at the additive writer:\n$gate",
    )

    val beforeRetag = baselineFile().readText()
    val refusedRetag = baselineRetagRunner().buildAndFail().output
    assertTrue(
      refusedRetag.contains("refusing pitestEncodingBaselineRetag") &&
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

  @Test
  fun `a committed seed larger than maxLen is refused, never truncated`() {
    // libFuzzer clips oversized inputs on load: a fuzz run explores a truncated copy,
    // and the minimize merge re-hashes the clip — adopting it hash-named and deleting
    // the named original. Both tasks must refuse before Jazzer runs, naming only the
    // offending seed, and the committed corpus must be untouched.
    val scenariosRoot = fixtureDir
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

    // An uncapped target has nothing to refuse — the check is inert, not a new
    // demand. Use a distinct build so the assertion does not depend on Gradle noticing
    // an in-place script rewrite before it looks up the prior configuration-cache entry.
    fixtureDir = scenariosRoot.resolve("uncapped").apply { mkdirs() }
    enableTestKitConfigurationCache(fixtureDir)
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

    val failed = runner("generateFuzzReplayTests", "--no-configuration-cache").buildAndFail().output

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
    val declined = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
    assertFalse(declined.contains("fuzz target 'bare' declares no seedCorpus"), declined)
    assertFalse(declined.contains("is stale"), declined)

    // Recorded with nothing: an argument-free suppression is not one.
    writeFixture(corpusless = true, corpuslessDecline = "   ")
    val blank = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
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
    val contradicted = runner("generateFuzzReplayTests", "--no-configuration-cache").build().output
    assertTrue(contradicted.contains("the recorded seedCorpus decline is stale"), contradicted)
    assertTrue(contradicted.contains("contradicts"), contradicted)
  }

  @Test
  fun `test support generates all helpers only when enabled and honors its package`() {
    // Each independent configuration gets its own checkout. Rewriting one build script
    // through several same-task configuration-cache entries couples the final package
    // assertion to configuration invalidation rather than generator behavior.
    // Keep only the transitions whose behavior is under test in the same checkout:
    // enabled -> disabled must clear, and valid -> invalid must preserve.
    val fixtureRoot = fixtureDir
    fun selectFixture(name: String) {
      fixtureDir = fixtureRoot.resolve(name).apply { mkdirs() }
      enableTestKitConfigurationCache(fixtureDir)
    }

    selectFixture("default-and-disabled")
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

    writeFixture(generateTestSupport = false)
    val disabled = runner("generateHardeningTestSupport").build()
    assertFalse(disabled.output.contains("FAILED"), disabled.output)
    expected.forEach { name ->
      assertFalse(supportDir.resolve("$name.java").isFile, "$name.java should be cleared when disabled")
    }

    selectFixture("excluded")
    writeFixture(generateTestSupport = true, testSupportExcludes = listOf("JulRecorder"))
    val excluded = runner("generateHardeningTestSupport").build()
    assertFalse(excluded.output.contains("FAILED"), excluded.output)
    val excludedSupportDir = File(
      fixtureDir, "build/generated-sources/hardening-support/java/software/sava/hardening/support")
    assertFalse(
      excludedSupportDir.resolve("JulRecorder.java").isFile,
      "JulRecorder.java should be excluded",
    )
    (expected - "JulRecorder").forEach { name ->
      assertTrue(
        excludedSupportDir.resolve("$name.java").isFile,
        "$name.java should survive the exclusion",
      )
    }

    selectFixture("custom-and-invalid")
    writeFixture(generateTestSupport = true, testSupportPackage = "com.example.hardening.support")
    val custom = runner("generateHardeningTestSupport").build()
    val customDir = File(
      fixtureDir, "build/generated-sources/hardening-support/java/com/example/hardening/support")
    expected.forEach { name ->
      assertTrue(
        customDir.resolve("$name.java").isFile,
        "$name.java not generated in custom package:\n${custom.output}",
      )
      assertTrue(
        customDir.resolve("$name.java").readText().contains("package com.example.hardening.support;"),
        "$name.java retained the foreign default package")
    }
    val preservedSupport = customDir.resolve("Ports.java").readText()

    writeFixture(generateTestSupport = true, testSupportPackage = "not-a-package")
    val invalid = runner("generateHardeningTestSupport", "--no-configuration-cache")
        .buildAndFail().output
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
  fun `disabled source generators store a configuration cache entry`() {
    assertSourceGeneratorsStoreConfigurationCache(generateTestSupport = false)
  }

  @Test
  fun `enabled source generators store a configuration cache entry`() {
    assertSourceGeneratorsStoreConfigurationCache(generateTestSupport = true)
  }

  private fun assertSourceGeneratorsStoreConfigurationCache(generateTestSupport: Boolean) {
    // Consumers run with the configuration cache on, and a task whose execution-time
    // lambda reaches a script-level helper cannot be serialized — the whole build fails
    // with "cannot serialize Gradle script object references", not just the task. It is
    // invisible to every other test here because they never pass the flag, and invisible
    // to the disabled path too: both generators are registered, realized as test
    // sources, and stored either way, so a repo that generates nothing still pays for
    // the capture. Both are covered because both have taken this defect: validating a
    // name from a 'doLast'/'doFirst' reads naturally and captures the whole script.
    writeFixture(generateTestSupport = generateTestSupport)
    val tasks = arrayOf("generateHardeningTestSupport", "generateFuzzReplayTests")
    val stored = runner(*tasks, "--configuration-cache").build().output
    assertFalse(stored.contains("problems were found storing the configuration cache"), stored)
    assertFalse(stored.contains("cannot serialize Gradle script object references"), stored)

    // Without reuse the assertion above proves only that one run tolerated the flag.
    val reused = runner(*tasks, "--configuration-cache").build().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
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
    writeFixture(
      recompileExcludes = listOf("Scratch.java"),
      registerFuzz = false,
      bytecodeRelease = 21,
      fakePit = false,
    )
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
    writeFixture(registerFuzz = false, bytecodeRelease = 21, fakePit = false)
    runner("compileForPitest", "--no-configuration-cache").build()
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
