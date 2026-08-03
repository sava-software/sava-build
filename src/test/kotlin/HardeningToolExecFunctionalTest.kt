import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
import java.io.File

/**
 * Functional test for the execution-time plumbing around the tool JavaExec
 * tasks: the minion-line filter (dedup across both streams, summary and buffered-tail
 * survival on a failing exit), the '.scoped' marker lifecycle, and the corpus
 * minimize's hash-based adoption. The fixture hijacks each task's mainClass to a
 * fixture-compiled fake ('FakePit' / 'FakeMerge') and replaces its classpath, so the
 * plugin's own doFirst/doLast logic runs for real with no PIT or Jazzer involved.
 */
class HardeningToolExecFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  /**
   * [moneyMath] adds a real 'com.example.Codec' whose BigDecimal/BigInteger arithmetic
   * the blind-spot scan is meant to find — the other tests here fake PIT and never need
   * the class to exist. [declineLines] is DSL spliced into the suite, so the recorded
   * decline travels the same path a consuming repo's would.
   */
  private fun writeFixture(
    moneyMath: Boolean = false,
    declineLines: String = "",
    buildTail: String = "",
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-tool-exec-smoke-test"
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

        dependencies {
          // hardeningCertify includes the real test lifecycle; generated corpus
          // replay tests therefore need the API a real consumer already carries.
          testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
          testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
        }

        tasks.test {
          useJUnitPlatform()
        }

        hardening {
          // No bytecode override: the recompiles must follow this generic fixture's
          // Java 21 TestKit daemon rather than assuming Sava's Java 25.
          // generated replay tests need junit and the harness classes, neither of
          // which this fixture declares
          recompileExcludes = listOf("CodecFuzzSeedReplayTest.java", "HollowFuzzSeedReplayTest.java")
          mutation.register("encoding") {
            // The fixture's FakePit/FakeMerge classes are production binaries too;
            // strict ownership certification requires every compiled production
            // class to belong to a suite, even though the fake ignores PIT targeting.
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
            $declineLines
          }
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/codec")
          }
          fuzz.register("plain") {
            targetClass = "com.example.PlainFuzz"
          }
          fuzz.register("hollow") {
            targetClass = "com.example.HollowFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/hollow")
          }
        }

        tasks.named<JavaExec>("pitestEncoding") {
          mainClass = "com.example.FakePit"
          classpath = sourceSets["main"].output
        }
        listOf("fuzzCodecMinimize", "fuzzPlainMinimize", "fuzzHollowMinimize").forEach { name ->
          tasks.named<JavaExec>(name) {
            mainClass = "com.example.FakeMerge"
            classpath = sourceSets["main"].output
            // the Jazzer pre-authorizations include flags newer than this fixture's JDK
            jvmArgs = listOf<String>()
          }
        }
        tasks.named<JavaExec>("fuzzPlain") {
          mainClass = "com.example.FakeFuzz"
          classpath = files(layout.buildDirectory.dir("fuzz-classes"))
          jvmArgs = listOf<String>()
        }

$buildTail
      """.trimIndent() + "\n"
    )
    val srcDir = File(fixtureDir, "src/main/java/com/example").apply { mkdirs() }
    val testSrcDir = File(fixtureDir, "src/test/java/com/example").apply { mkdirs() }
    listOf("CodecFuzz", "HollowFuzz").forEach { className ->
      testSrcDir.resolve("$className.java").writeText(
        """
          package com.example;

          public final class $className {
            public static void fuzzerTestOneInput(byte[] data) {}
          }
        """.trimIndent() + "\n"
      )
    }
    // Real arithmetic on both candidate types, so the scan reads a genuine constant
    // pool rather than a fixture that only claims to have money math. Both types are
    // present because a decline must suppress its own mutator and leave the other
    // one speaking.
    File(srcDir, "Codec.java").let { codec ->
      if (moneyMath) {
        codec.writeText(
          """
            package com.example;

            import java.math.BigDecimal;
            import java.math.BigInteger;

            public final class Codec {

              public static BigDecimal fee(BigDecimal rate, BigDecimal amount) {
                return rate.multiply(amount).subtract(BigDecimal.ONE);
              }

              public static BigInteger mask(BigInteger flags, BigInteger bits) {
                return flags.and(bits).shiftLeft(2);
              }
            }
          """.trimIndent() + "\n"
        )
      } else {
        codec.delete()
      }
    }
    // Prints the repeated minion chatter the filter exists to collapse — split across
    // stdout and stderr, since the shared seen-set is the point — then writes the CSV
    // report the verify finalizer needs. 'fail' mode additionally leaves a partial
    // stderr line unterminated and exits non-zero.
    srcDir.resolve("FakePit.java").writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.StandardOpenOption;

        public final class FakePit {
          public static void main(String[] args) throws Exception {
            String mode = "ok";
            Path modeFile = Path.of("fake-pit-mode.txt");
            if (Files.exists(modeFile)) mode = Files.readString(modeFile).trim();
            for (int i = 0; i < 3; i++) System.out.println("PIT >> INFO : MINION : common noise");
            System.out.println("PIT >> INFO : MINION : stdout-only detail");
            System.out.println("plain duplicate");
            System.out.println("plain duplicate");
            for (int i = 0; i < 2; i++) System.err.println("PIT >> INFO : MINION : common noise");
            System.err.println("PIT >> INFO : MINION : stderr-only detail");
            String reportDir = null;
            for (String arg : args) {
              if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
            }
            Path dir = Path.of(reportDir);
            Files.createDirectories(dir);
            String status = mode.equals("timeout") ? "TIMED_OUT" : "KILLED";
            Files.writeString(dir.resolve("mutations.csv"),
                "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12," +
                    status + ",com.example.CodecTest\n");
            if (mode.equals("mutate-input")) {
              Files.writeString(Path.of("src/main/java/com/example/FakePit.java"),
                  "\n// changed while PIT was running\n", StandardOpenOption.APPEND);
            }
            if (mode.equals("fail")) {
              System.err.print("partial tail before crash");
              System.exit(3);
            }
          }
        }
      """.trimIndent() + "\n"
    )
    // Simulates libFuzzer -merge=1: the positional arguments are the staging output
    // followed by the source corpora. Writes one input whose bytes match seedA (so the
    // named seed survives) and one novel input; 'empty' mode writes nothing.
    srcDir.resolve("FakeMerge.java").writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.ArrayList;
        import java.util.List;

        public final class FakeMerge {
          public static void main(String[] args) throws Exception {
            String mode = "adopt";
            Path modeFile = Path.of("fake-merge-mode.txt");
            if (Files.exists(modeFile)) mode = Files.readString(modeFile).trim();
            List<String> dirs = new ArrayList<>();
            for (String arg : args) {
              if (!arg.startsWith("-")) dirs.add(arg);
            }
            Path staging = Path.of(dirs.get(0));
            Path seeds = Path.of(dirs.get(1));
            if (mode.equals("empty")) return;
            Files.write(staging.resolve("1a2b3c"), Files.readAllBytes(seeds.resolve("seedA")));
            Files.writeString(staging.resolve("9f8e7d"), "novel");
            if (dirs.size() == 3) {
              try (var localFiles = Files.list(Path.of(dirs.get(2)))) {
                for (Path local : localFiles.sorted().toList()) {
                  Files.write(staging.resolve("feedface"), Files.readAllBytes(local));
                }
              }
            }
          }
        }
      """.trimIndent() + "\n"
    )
    srcDir.resolve("FakeFuzz.java").writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;

        public final class FakeFuzz {
          public static void main(String[] args) throws Exception {
            String corpus = null;
            for (String arg : args) {
              if (!arg.startsWith("-")) corpus = arg;
            }
            if (corpus == null || !Files.isDirectory(Path.of(corpus))) {
              throw new IllegalStateException("writable corpus was not prepared: " + corpus);
            }
            System.out.println("fixture fuzz executed");
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun writeSeedCorpus() {
    val corpus = File(fixtureDir, "corpus/codec").apply { mkdirs() }
    corpus.resolve("seedA").writeText("alpha")
    corpus.resolve("seedB").writeText("beta-longer")
  }

  /**
   * Every invocation here runs with the configuration cache on, because consumers do
   * and because this class is the only place the real tool tasks execute. Two defect
   * shapes hide from a class that omits the flag, and both have shipped: an
   * execution-time lambda that reaches a script-level declaration cannot be
   * serialized, and 'Task.project' read from a task action is refused outright.
   * Neither is scoped to the offending task — they fail the consumer's whole build,
   * and a green run without the flag says nothing about either.
   */
  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--configuration-cache", "--stacktrace")

  private fun occurrences(haystack: String, needle: String) = haystack.split(needle).size - 1

  @Test
  fun `the minion filter dedups across both streams and the scoped marker tracks mutateOnly`() {
    writeFixture()

    val ok = runner("pitestEncoding").build()
    // 5 'common noise' lines went out (3 stdout, 2 stderr); one survives, whichever
    // stream carried it first — the seen-set is shared
    assertEquals(1, occurrences(ok.output, "common noise"), "cross-stream dedup broken:\n" + ok.output)
    assertEquals(1, occurrences(ok.output, "stdout-only detail"), ok.output)
    assertEquals(1, occurrences(ok.output, "stderr-only detail"), ok.output)
    // non-minion lines are never deduplicated, however repetitive
    assertEquals(2, occurrences(ok.output, "plain duplicate"), "non-minion lines must pass through:\n" + ok.output)
    assertTrue(
      ok.output.contains("suppressed 4 repeated minion log line(s)"),
      "suppression summary missing:\n" + ok.output
    )
    // the verify finalizer read the fake's report as a full, unscoped run
    assertTrue(ok.output.contains("pitest 'encoding': 1/1 detected (100%)"), ok.output)

    val marker = File(fixtureDir, "build/reports/pitest/encoding/.scoped")
    val scoped = runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    assertEquals("com.example.Codec\n", marker.readText(), "scoped marker not written")
    assertTrue(scoped.output.contains("SCOPED run (-PmutateOnly=com.example.Codec)"), scoped.output)

    runner("pitestEncoding").build()
    assertFalse(marker.exists(), "an unscoped run must clear the scoped marker")
  }

  @Test
  fun `a fuzz target execution is configuration-cache clean`() {
    writeFixture()

    val first = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(first.output.contains("fixture fuzz executed"), first.output)

    val second = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
    assertTrue(second.output.contains("fixture fuzz executed"), second.output)
  }

  @Test
  fun `certification writes bound evidence and stale source cannot reuse the report`() {
    writeFixture(moneyMath = true)
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest").mkdirs()
    File(fixtureDir, "config/pitest/encoding-accepted.csv").writeText(
      "com.example.Codec,encode,MathMutator,SURVIVED # legacy accepted row\n")

    val certified = runner("clean", "hardeningCertify").build()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val evidence = reportDir.resolve(".evidence.tsv")
    val receipt = File(fixtureDir, "build/hardening/pitest-certification.tsv")
    assertTrue(evidence.isFile, "completed PIT evidence missing:\n${certified.output}")
    assertTrue(receipt.isFile, "certification receipt missing:\n${certified.output}")
    val receiptText = receipt.readText()
    assertTrue(receiptText.contains("schema\t4"), receiptText)
    assertTrue(receiptText.contains("session\t"), receiptText)
    assertTrue(receiptText.contains("toolClasspathSha256"), receiptText)
    assertTrue(receiptText.contains("recordInputsSha256"), receiptText)
    assertTrue(receiptText.contains("recordPitestVersion"), receiptText)
    assertTrue(receiptText.contains("mode\tfresh-full-strict"), receiptText)
    assertTrue(receiptText.contains("suite\tencoding"), receiptText)
    assertTrue(receiptText.contains("\tlegacy-unversioned\n"), receiptText)
    val columns = receiptText.lineSequence().first { it.startsWith("columns\t") }.split('\t')
    val suiteRow = receiptText.lineSequence().first { it.startsWith("suite\tencoding\t") }.split('\t')
    val recordInputsIndex = columns.indexOf("recordInputsSha256") - 1
    val configDir = File(fixtureDir, "config/pitest")
    assertEquals(
      PitestEvidence.fingerprint(
        configDir,
        listOf(
          File(configDir, "encoding-accepted.csv"),
          File(configDir, "encoding-timeouts.csv"),
          File(configDir, "encoding-pitest-version"),
          File(configDir, "README.md"),
        ).filter { it.isFile },
      ),
      suiteRow[recordInputsIndex],
      "certification receipt did not bind the committed records that decided the gate",
    )
    assertTrue(certified.output.contains("committed record is legacy-unversioned"), certified.output)
    val recordedEvidence = PitestEvidence.parse(evidence.readText())
    assertEquals(
      PitestEvidence.fingerprint(fixtureDir, listOf(File(fixtureDir, "build/mutation-classes"))),
      recordedEvidence.classesSha256,
      "certification compared against a pre-build classes fingerprint",
    )
    assertFalse(
      recordedEvidence.classesSha256 == PitestEvidence.sha256(""),
      "compiled classes were recorded as SHA-256(empty)",
    )

    val reused = runner("clean", "hardeningCertify").build()
    assertTrue(reused.output.contains("Reusing configuration cache"), reused.output)

    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// source changed after PIT\n")
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("completed report evidence no longer matches the current build"), stale)
    assertTrue(stale.contains("sourceSha256"), stale)
  }

  @Test
  fun `mode snapshot refuses completed evidence after its inputs change`() {
    writeFixture()
    runner("pitestEncoding").build()

    File(fixtureDir, "src/main/java/com/example/FakePit.java")
        .appendText("\n// changed after the completed PIT observation\n")
    val stale = runner("pitestModeSnapshot", "-PpitestMode=stale").buildAndFail().output

    assertTrue(stale.contains("report/evidence pair no longer matches the current build"), stale)
    assertTrue(stale.contains("sourceSha256"), stale)
  }

  @Test
  fun `certification refuses inputs changed after suite verification`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        tasks.register("tamperAfterVerify") {
          mustRunAfter("pitestEncodingVerify")
          doLast {
            file("src/main/java/com/example/FakePit.java")
              .appendText("\n// changed after verification\n")
          }
        }
        tasks.named("hardeningCertify") {
          dependsOn("tamperAfterVerify")
        }
      """.trimIndent() + "\n"
    )

    val failed = runner("hardeningCertify").buildAndFail().output
    val receipt = File(fixtureDir, "build/hardening/pitest-certification.tsv")

    assertTrue(failed.contains("inputs changed after verification"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertFalse(receipt.exists(), "stale-input certification left a passing receipt")
  }

  @Test
  fun `certification preflight rejects scoped flags before PIT executes`() {
    writeFixture()

    val refused = runner("hardeningCertify", "-PmutateOnly=com.example.Codec").buildAndFail().output
    assertTrue(refused.contains("hardeningCertify is observation-only and full-population"), refused)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "PIT ran before certification preflight refused the scoped flag")
  }

  @Test
  fun `certification aliases activate strict verification and reject task exclusions`() {
    val alias = """
      tasks.register("releaseGate") {
        dependsOn("hardeningCertify")
      }
    """.trimIndent()
    writeFixture(buildTail = alias)
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "arcmutate-licence.txt").writeText("fixture licence marker\n")

    runner("pitestEncoding").build()
    val evidence = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    assertTrue(evidence.readText().contains("historyAssisted\ttrue"), evidence.readText())

    val throughAbbreviatedAlias = runner("releaseG").build()
    val receipt = File(fixtureDir, "build/hardening/pitest-certification.tsv")
    assertTrue(receipt.isFile, "alias did not produce a certification receipt:\n${throughAbbreviatedAlias.output}")
    assertTrue(receipt.readText().contains("session\t"), receipt.readText())
    assertTrue(evidence.readText().contains("historyAssisted\tfalse"), evidence.readText())

    val excluded = runner("releaseG", "-x", "pitestEncodingVerify").buildAndFail().output
    assertTrue(excluded.contains("task exclusion(s): -x pitestEncodingVerify"), excluded)
    assertFalse(receipt.exists(), "a refused certification left its prior receipt looking current")
    assertTrue(
      File(fixtureDir, "build/hardening/pitest-certification.running").isFile,
      "a refused certification must retain its invalidation sentinel")

    File(fixtureDir, "fake-pit-mode.txt").writeText("timeout\n")
    val strict = runner("releaseG").buildAndFail().output
    assertTrue(strict.contains("timed-out mutant(s) and no audited set"), strict)
    assertFalse(receipt.exists(), "strict verification failure produced a receipt")
  }

  @Test
  fun `certification refuses a skipped PIT even when matching evidence already exists`() {
    writeFixture(buildTail = """
      tasks.register("releaseGate") { dependsOn("hardeningCertify") }
      tasks.named("pitestEncoding") {
        val skipPit = providers.gradleProperty("skipPit").isPresent
        onlyIf { !skipPit }
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    runner("releaseGate").build()
    val receipt = File(fixtureDir, "build/hardening/pitest-certification.tsv")
    assertTrue(receipt.isFile)

    val skipped = runner("releaseGate", "-PskipPit").buildAndFail().output
    assertTrue(
      skipped.contains("without completing PIT in this invocation") ||
          skipped.contains("no PIT execution plus successful verification recorded"),
      skipped)
    assertFalse(receipt.exists(), "failed certification retained a prior receipt")
    assertTrue(File(fixtureDir, "build/hardening/pitest-certification.running").isFile)

    val reused = runner("releaseGate", "-PskipPit").buildAndFail().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
  }

  @Test
  fun `certification refuses convergence before an unverified round two can replace its evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner("hardeningCertify", "pitestConverge").buildAndFail().output

    assertTrue(failed.contains("pitestConverge cannot run inside hardeningCertify"), failed)
    assertTrue(failed.contains("separate Gradle invocations"), failed)
    assertFalse(File(fixtureDir, "build/hardening/pitest-certification.tsv").exists())
    assertTrue(File(fixtureDir, "build/hardening/pitest-certification.running").isFile)
  }

  @Test
  fun `certification refuses a directly selected convergence round two before writing a receipt`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner("hardeningCertify", "pitestEncodingConvergeRound2").buildAndFail().output

    assertTrue(failed.contains("pitestConverge cannot run inside hardeningCertify"), failed)
    assertFalse(File(fixtureDir, "build/hardening/pitest-certification.tsv").exists())
    assertTrue(File(fixtureDir, "build/hardening/pitest-certification.running").isFile)
  }

  @Test
  fun `certification state is isolated between subprojects`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    File(fixtureDir, "arcmutate-licence.txt").writeText("fixture licence marker\n")

    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(
        sharedBuild + if (name == "b") {
          """

            tasks.named("pitestEncoding") {
              mustRunAfter(":a:hardeningCertifyPreflight")
            }
          """.trimIndent() + "\n"
        } else {
          ""
        }
      )
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
      File(fixtureDir, "corpus").copyRecursively(subproject.resolve("corpus"))
    }

    val result = runner(":a:hardeningCertify", ":b:pitestEncoding").build()
    val aEvidence = PitestEvidence.parse(
      File(fixtureDir, "a/build/reports/pitest/encoding/.evidence.tsv").readText())
    val bEvidence = PitestEvidence.parse(
      File(fixtureDir, "b/build/reports/pitest/encoding/.evidence.tsv").readText())

    assertFalse(aEvidence.historyAssisted, result.output)
    assertTrue(bEvidence.historyAssisted, result.output)
    assertTrue(File(fixtureDir, "a/build/hardening/pitest-certification.tsv").isFile)
    assertFalse(File(fixtureDir, "b/build/hardening/pitest-certification.tsv").exists())
  }

  @Test
  fun `PIT refuses to commit evidence when an input changes during execution`() {
    writeFixture()
    File(fixtureDir, "fake-pit-mode.txt").writeText("mutate-input\n")

    val failed = runner("pitestEncoding").buildAndFail().output
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    assertTrue(failed.contains("evidence inputs changed while PIT was running"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), "drifting inputs committed evidence")
    assertTrue(reportDir.resolve(".running").isFile, "drifting inputs exposed the report")
  }

  @Test
  fun `evidence commit failure keeps the report behind the running sentinel`() {
    writeFixture()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    reportDir.resolve(".evidence.tsv/blocked").apply {
      parentFile.mkdirs()
      writeText("not a replaceable evidence file")
    }

    val failed = runner("pitestEncoding").buildAndFail().output

    assertTrue(reportDir.resolve(".running").isFile, "evidence failure exposed the report:\n$failed")
    assertTrue(
      failed.contains("partial population is not evidence") ||
          failed.contains(".evidence.tsv"),
      failed)
  }

  @Test
  fun `a recorded decline silences the blind-spot advice through a real pitest run`() {
    // The unit tests own the policy; this owns the plumbing — that the suite's
    // declinedMutators actually reach the advice through task configuration and the
    // configuration cache. Without it, the feature's headline claim ("record the
    // decision and it goes quiet") is asserted nowhere against a running build.
    writeFixture(moneyMath = true)
    val advised = runner("pitestEncoding").build().output
    assertTrue(advised.contains("call BigDecimal arithmetic"), "advice did not fire:\n" + advised)
    assertTrue(advised.contains("call BigInteger arithmetic"), advised)
    assertTrue(advised.contains("declineMutator("), "the advice must name its own escape hatch:\n" + advised)

    // Recorded with its measurement: quiet, and only for the mutator it names.
    writeFixture(
      moneyMath = true,
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "trialed 2026-07-25: generated 0")""",
    )
    val declined = runner("pitestEncoding").build().output
    assertFalse(declined.contains("call BigDecimal arithmetic"), "the decline did not reach the advice:\n" + declined)
    assertTrue(
      declined.contains("call BigInteger arithmetic"),
      "a decline must suppress its own mutator only:\n" + declined
    )
    assertFalse(declined.contains("is stale"), "a decline with a live subject is not stale:\n" + declined)

    // An argument-free decline suppresses nothing and reports itself, so it cannot be
    // used to quiet a warning nobody investigated.
    writeFixture(
      moneyMath = true,
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "   ")""",
    )
    val blank = runner("pitestEncoding").build().output
    assertTrue(blank.contains("call BigDecimal arithmetic"), blank)
    assertTrue(blank.contains("the recorded decline of EXPERIMENTAL_BIG_DECIMAL is stale"), blank)
    assertTrue(blank.contains("carries no reason"), blank)

    // The subject disappears (no money math left) and the decline says so rather than
    // sitting on as a settled decision about deleted code.
    writeFixture(
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "trialed 2026-07-25: generated 0")""",
    )
    val subjectGone = runner("pitestEncoding").build().output
    assertTrue(subjectGone.contains("the recorded decline of EXPERIMENTAL_BIG_DECIMAL is stale"), subjectGone)
    assertTrue(subjectGone.contains("no longer suppresses anything"), subjectGone)
  }

  @Test
  fun `a failing PIT exit still flushes the summary and buffered tail, and cannot rewrite the marker`() {
    writeFixture()
    // leave a scoped marker behind, then fail an unscoped run: the deferred exit must
    // re-raise after the filters close but before the marker update
    runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    val marker = File(fixtureDir, "build/reports/pitest/encoding/.scoped")
    assertTrue(marker.isFile, "precondition: scoped marker missing")

    File(fixtureDir, "fake-pit-mode.txt").writeText("fail")
    val failed = runner("pitestEncoding").buildAndFail()
    assertTrue(failed.output.contains("non-zero exit value 3"), failed.output)
    assertTrue(
      failed.output.contains("suppressed 4 repeated minion log line(s)"),
      "summary lost on a failing run:\n" + failed.output
    )
    assertTrue(
      failed.output.contains("partial tail before crash"),
      "buffered partial line lost on a failing run:\n" + failed.output
    )
    assertEquals(
      "com.example.Codec\n", marker.readText(),
      "a failed run is not evidence and must not touch the scoped marker"
    )
  }

  @Test
  fun `minimize adopts novel inputs, keeps surviving seed names, and drops redundant seeds`() {
    writeFixture()
    writeSeedCorpus()

    val result = runner("fuzzCodecMinimize").build()
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "surviving seed must keep its committed name")
    assertEquals("novel", corpus.resolve("9f8e7d").readText(), "novel input not adopted")
    assertFalse(corpus.resolve("seedB").exists(), "redundant seed not removed")
    assertTrue(result.output.contains("corpus minimized 2 -> 2 file(s)"), result.output)
    assertTrue(result.output.contains("1 newly adopted, 1 redundant removed"), result.output)
  }

  @Test
  fun `adoptLocalCorpus adds the local finds as a merge source only when requested`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "build/fuzz/codec-corpus").apply { mkdirs() }
      .resolve("local-find").writeText("from-local")
    val corpus = File(fixtureDir, "corpus/codec")

    // without the flag the local dir is never passed to the merge, however non-empty
    runner("fuzzCodecMinimize").build()
    assertFalse(corpus.resolve("feedface").exists(), "local corpus must not be a source by default")

    // reset the corpus (the first merge dropped seedB) and adopt deliberately
    corpus.resolve("9f8e7d").delete()
    writeSeedCorpus()
    val adopted = runner("fuzzCodecMinimize", "-PadoptLocalCorpus").build()
    assertEquals("from-local", corpus.resolve("feedface").readText(), "local find not folded into the merge")
    assertTrue(adopted.output.contains("2 newly adopted"), adopted.output)
  }

  @Test
  fun `minimize refuses an empty merge, a missing corpus, and an undeclared one`() {
    writeFixture()
    writeSeedCorpus()

    // an empty merge result must never replace the committed corpus
    File(fixtureDir, "fake-merge-mode.txt").writeText("empty")
    val empty = runner("fuzzCodecMinimize").buildAndFail()
    assertTrue(empty.output.contains("the merge produced an empty corpus — refusing to touch"), empty.output)
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "committed corpus touched by a refused merge")
    assertEquals("beta-longer", corpus.resolve("seedB").readText(), "committed corpus touched by a refused merge")

    val undeclared = runner("fuzzPlainMinimize").buildAndFail()
    assertTrue(
      undeclared.output.contains("declares no seedCorpus — nothing to minimize into"),
      undeclared.output
    )

    val missing = runner("fuzzHollowMinimize").buildAndFail()
    assertTrue(
      missing.output.contains("missing or empty — a merge cannot start from nothing"),
      missing.output
    )
  }
}
