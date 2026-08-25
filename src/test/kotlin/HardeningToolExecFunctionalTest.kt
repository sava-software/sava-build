import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

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
    pluginManagement: String = savaBuildPluginManagement,
    projectRepositories: String = "repositories { mavenCentral() }",
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $pluginManagement

        rootProject.name = "hardening-tool-exec-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        $projectRepositories

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
        listOf("fuzzCodec", "fuzzPlain", "fuzzHollow").forEach { name ->
          tasks.named<JavaExec>(name) {
            mainClass = "com.example.FakeFuzz"
            classpath = files(layout.buildDirectory.dir("fuzz-classes"))
            jvmArgs = listOf<String>()
          }
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
            if (mode.equals("below-cost-threshold") || mode.equals("at-cost-threshold") ||
                mode.equals("slow-fail")) {
              int millis = mode.equals("below-cost-threshold") ? 249 :
                  (mode.equals("at-cost-threshold") ? 250 : 416);
              String slowest = "Slowest test ([engine:junit-jupiter]/" +
                  "[class:com.example.CodecTest]/[method:roundTrip()]) took " + millis + " ms";
              System.out.println("12:00:00 PIT >> INFO : " + slowest);
              System.out.println("> " + slowest);
            }
            if (mode.equals("raw-options")) {
              System.out.println(
                  "PIT >> FINEST : ReportOptions [... arcmutateMissing=true ...]");
            }
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
            Files.writeString(dir.resolve("arguments.txt"), String.join("\n", args) + "\n");
            if (mode.equals("zero-fire")) {
              System.exit(2);
            }
            if (mode.equals("fail-before-report")) {
              System.err.print("failed before report");
              System.exit(4);
            }
            String status = mode.equals("timeout") ? "TIMED_OUT" :
                (mode.equals("survive") ? "SURVIVED" : "KILLED");
            Files.writeString(dir.resolve("mutations.csv"),
                "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12," +
                    status + ",com.example.CodecTest\n");
            if (mode.equals("mutate-plugin")) {
              Files.writeString(Path.of(System.getenv("FIXTURE_PLUGIN_ARTIFACT")),
                  "replaced while PIT was running",
                  StandardOpenOption.APPEND);
            }
            if (mode.equals("mutate-input")) {
              Files.writeString(Path.of("src/main/java/com/example/FakePit.java"),
                  "\n// changed while PIT was running\n", StandardOpenOption.APPEND);
            }
            if (mode.equals("tamper-certification-sentinel")) {
              Files.writeString(
                  Path.of(".pitest-history/pitest-certification.running"), "tampered\n");
            }
            if (mode.equals("fail") || mode.equals("slow-fail")) {
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
            if (mode.equals("fail")) System.exit(5);
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
            String target = null;
            for (String arg : args) {
              if (!arg.startsWith("-")) corpus = arg;
              if (arg.startsWith("--target_class=")) {
                target = arg.substring("--target_class=".length());
              }
            }
            if (corpus == null || !Files.isDirectory(Path.of(corpus))) {
              throw new IllegalStateException("writable corpus was not prepared: " + corpus);
            }
            System.out.println("fixture fuzz executed");
            Path modeFile = Path.of("fake-fuzz-mode.txt");
            String mode = Files.exists(modeFile) ? Files.readString(modeFile).trim() : "ok";
            if (mode.equals("fail") && target != null && target.endsWith("PlainFuzz")) {
              System.exit(4);
            }
            long executions = target != null && target.endsWith("CodecFuzz") ? 101L :
                target != null && target.endsWith("HollowFuzz") ? 202L : 303L;
            if (target != null && target.endsWith("PlainFuzz")) {
              if (mode.equals("missing")) return;
              if (mode.equals("zero")) executions = 0L;
              if (mode.equals("ambiguous")) {
                System.err.println("Done 1 runs in 1 second(s)");
              }
            }
            System.err.println("Done " + executions + " runs in 1 second(s)");
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun enableFakeArcMutate() {
    File(fixtureDir, "arcmutate-licence.txt")
      .writeText("expires=31/12/2999\ntype=OSSS\nfixture=licence-marker\n")
    File(
      fixtureDir,
      "src/main/resources/META-INF/maven/com.arcmutate/base/pom.properties",
    ).apply {
      parentFile.mkdirs()
      writeText("groupId=com.arcmutate\nartifactId=base\nversion=1.7.1\n")
    }
  }

  private fun writeSeedCorpus() {
    val corpus = File(fixtureDir, "corpus/codec").apply { mkdirs() }
    corpus.resolve("seedA").writeText("alpha")
    corpus.resolve("seedB").writeText("beta-longer")
  }

  private fun initializeGitFixture(extraIgnoreRules: List<String> = emptyList()): Pair<String, String> {
    File(fixtureDir, ".gitignore").writeText(
      (listOf(".gradle/", "build/", ".pitest-history/") + extraIgnoreRules)
        .joinToString("\n", postfix = "\n")
    )
    val emptyTemplate = File(fixtureDir, ".empty-git-template").apply { mkdirs() }
    git("-c", "init.templateDir=${emptyTemplate.absolutePath}", "init", "--quiet", "--initial-branch=main")
    git("add", "-A")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "fixture",
    )
    return git("rev-parse", "HEAD") to git("rev-parse", "HEAD^{tree}")
  }

  private fun git(vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", fixtureDir.absolutePath) + arguments)
      .redirectErrorStream(true)
      .apply {
        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
        environment()["GIT_TERMINAL_PROMPT"] = "0"
      }
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")} failed:\n$output")
    return output.trim()
  }

  /**
   * Keeps the real PIT tool configuration on the fake process classpath so this one
   * fixture can observe the two ArcMutate levers independently. The fake emits a
   * second killed mutant when com.arcmutate:base is absent: that makes an accidental
   * tool-classpath change observable as the population drift it causes in a real PIT
   * engine, while keeping every variant acceptable to the ratchet.
   */
  private fun writeHistoryClasspathProbeFixture() {
    writeFixture(
      buildTail = """
        tasks.named<JavaExec>("pitestEncoding") {
          classpath = files(sourceSets["main"].output, configurations["pitest"])
        }
      """.trimIndent()
    )
    File(fixtureDir, "src/main/java/com/example/FakePit.java").writeText(
      """
        package com.example;

        import java.io.File;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.Arrays;
        import java.util.List;
        import java.util.regex.Pattern;

        public final class FakePit {
          public static void main(String[] args) throws Exception {
            List<String> classpath = Arrays.stream(
                    System.getProperty("java.class.path").split(
                        Pattern.quote(File.pathSeparator)))
                .map(Path::of)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
            boolean hasArcMutateBase = classpath.stream()
                .anyMatch(name -> name.startsWith("base-") && name.endsWith(".jar"));

            Path captureDir = Path.of("build/fake-pit-probe");
            Files.createDirectories(captureDir);
            Files.writeString(captureDir.resolve("args.txt"), String.join("\n", args) + "\n");
            Files.writeString(captureDir.resolve("classpath.txt"),
                String.join("\n", classpath) + "\n");

            String reportDir = null;
            for (String arg : args) {
              if (arg.startsWith("--reportDir=")) {
                reportDir = arg.substring("--reportDir=".length());
              }
            }
            Path dir = Path.of(reportDir);
            Files.createDirectories(dir);
            String report =
                "FakePit.java,com.example.FakePit," +
                    "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                    "main,12,KILLED,com.example.FakePitTest\n";
            if (!hasArcMutateBase) {
              report +=
                  "FakePit.java,com.example.FakePit," +
                      "org.pitest.mutationtest.engine.gregor.mutators.returns.BooleanFalseReturnValsMutator," +
                      "main,13,KILLED,com.example.FakePitTest\n";
            }
            Files.writeString(dir.resolve("mutations.csv"), report);
          }
        }
      """.trimIndent() + "\n"
    )
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "arcmutate-licence.txt")
      .writeText("expires=31/12/2999\ntype=OSSS\nfixture=licence-marker\n")
  }

  /**
   * Reproduces the cold-cache shape used by modular multi-project consumers. The
   * extra-module-info plugin transforms the producer's project JAR on the consumer's
   * runtime classpath. It is runtime-only so compileForPitest does not schedule its
   * producer as a compile dependency. During configuration-cache storage that JAR is
   * deliberately absent: the PIT/evidence graph itself must schedule ':library:jar'.
   */
  private fun writeModularProjectDependencyFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-modular-project-dependency-smoke-test"
        include("library", "consumer")
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "library/build.gradle.kts").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          plugins {
            `java-library`
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "library/src/main/java/module-info.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText("module com.example.library { exports com.example.library; }\n")
    }
    File(fixtureDir, "library/src/main/java/com/example/library/Library.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.library;

          public final class Library {
            public static int value() { return 7; }
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/build.gradle.kts").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          // The dependency is already on the sava-build plugin's implementation
          // classpath; applying it this way avoids a second plugin-marker resolution.
          pluginManager.apply("org.gradlex.extra-java-module-info")

          repositories {
            mavenCentral()
          }

          dependencies {
            runtimeOnly(project(":library"))
          }

          hardening {
            mutation.register("encoding") {
              targetClasses = listOf("com.example.*")
              targetTests = "com.example.*Test*"
            }
          }

          tasks.named<JavaExec>("pitestEncoding") {
            mainClass = "com.example.consumer.FakePit"
            classpath = sourceSets["main"].output
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/module-info.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          module com.example.consumer {
            exports com.example.consumer;
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/com/example/consumer/Consumer.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.consumer;

          public final class Consumer {
            public static int value() { return 7; }
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "consumer/src/main/java/com/example/consumer/FakePit.java").also { file ->
      file.parentFile.mkdirs()
      file.writeText(
        """
          package com.example.consumer;

          import java.nio.file.Files;
          import java.nio.file.Path;

          public final class FakePit {
            public static void main(String[] args) throws Exception {
              String slowest = "Slowest test ([engine:junit-jupiter]/" +
                  "[class:com.example.consumer.ConsumerTest]/" +
                  "[method:roundTrip(java.lang.String)]) took 416 ms";
              System.out.println("12:00:00 PIT >> INFO : " + slowest);
              System.out.println("> " + slowest);
              String reportDir = null;
              for (String arg : args) {
                if (arg.startsWith("--reportDir=")) {
                  reportDir = arg.substring("--reportDir=".length());
                }
              }
              Path dir = Path.of(reportDir);
              Files.createDirectories(dir);
              Files.writeString(dir.resolve("mutations.csv"),
                  "Consumer.java,com.example.consumer.Consumer," +
                      "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                      "value,7,KILLED,com.example.consumer.ConsumerTest\\n");
            }
          }
        """.trimIndent() + "\n"
      )
    }
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

  private fun runnerWithoutConfigurationCache(vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(*args, "--no-configuration-cache", "--stacktrace")

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
    val fullReportDir = File(fixtureDir, "build/reports/pitest/encoding")
    assertEquals(
      3,
      occurrences(fullReportDir.resolve("pitest.stdout.log").readText(), "common noise"),
      "raw stdout must retain console-deduplicated minion lines",
    )
    assertEquals(
      2,
      occurrences(fullReportDir.resolve("pitest.stderr.log").readText(), "common noise"),
      "raw stderr must retain console-deduplicated minion lines",
    )
    // the verify finalizer read the fake's report as a full, unscoped run
    assertTrue(ok.output.contains("pitest 'encoding': 1/1 detected (100%)"), ok.output)

    fun reportSnapshot(dir: File): Map<String, List<Byte>> = dir.walkTopDown()
      .filter(File::isFile)
      .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }
    val fullBeforeScoped = reportSnapshot(fullReportDir)

    val scopedReportDir = File(fixtureDir, "build/reports/pitest-scoped/encoding")
    val marker = scopedReportDir.resolve(".scoped")
    val mode = File(fixtureDir, "fake-pit-mode.txt")
    mode.writeText("survive")
    val scoped = runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    mode.delete()
    assertEquals("com.example.Codec\n", marker.readText(), "scoped marker not written")
    assertTrue(scoped.output.contains("SCOPED run (-PmutateOnly=com.example.Codec)"), scoped.output)
    assertTrue(scopedReportDir.resolve("mutations.csv").isFile, "scoped CSV missing")
    assertTrue(scopedReportDir.resolve(".evidence.tsv").isFile, "scoped evidence missing")
    assertTrue(scopedReportDir.resolve(".toolchain.tsv").isFile, "scoped toolchain missing")
    assertEquals(
      fullBeforeScoped,
      reportSnapshot(fullReportDir),
      "a successful scoped run replaced full-population evidence",
    )
    val scopedCsv = scopedReportDir.resolve("mutations.csv")
    assertTrue(
      fullReportDir.resolve("mutations.csv").setLastModified(scopedCsv.lastModified() - 2_000L),
      "could not make the full-report age ordering deterministic",
    )
    val debt = runner("pitestEncodingDebt").build().output
    assertTrue(
      debt.contains("latest full report (newer scoped diagnostic excluded)") &&
          debt.contains("debt: none"),
      "Debt did not identify the excluded newer scoped survivor or read the full report:\n$debt",
    )

    val wrongScope = runner(
      "pitestEncodingVerify",
      "-PmutateOnly=com.example.Other",
    ).buildAndFail().output
    assertTrue(
      wrongScope.contains(
        "requested -PmutateOnly=com.example.Other, but the last scoped report was produced with " +
          "-PmutateOnly=com.example.Codec"
      ),
      wrongScope,
    )

    val convergeScoped = runner(
      "pitestEncodingConvergeRound2",
      "-PmutateOnly=com.example.Codec",
    ).buildAndFail().output
    assertTrue(
      convergeScoped.contains("pitestConverge cannot run with -PmutateOnly=com.example.Codec"),
      convergeScoped,
    )
    assertEquals(
      fullBeforeScoped,
      reportSnapshot(fullReportDir),
      "a refused scoped convergence round changed full-population evidence",
    )

    val blankScope = runner("pitestEncoding", "-PmutateOnly=").buildAndFail().output
    assertTrue(blankScope.contains("-PmutateOnly requires a nonblank class glob"), blankScope)
    assertEquals(
      fullBeforeScoped,
      reportSnapshot(fullReportDir),
      "a refused blank scope changed full-population evidence",
    )

    mode.writeText("slow-fail")
    runner("pitestEncoding", "-PmutateOnly=com.example.Codec").buildAndFail()
    assertEquals(
      fullBeforeScoped,
      reportSnapshot(fullReportDir),
      "a failed scoped run changed full-population evidence",
    )
    assertFalse(marker.exists(), "a failed scoped attempt retained an older scope marker")
    assertTrue(
      scopedReportDir.resolve(".running").isFile,
      "a failed scoped attempt exposed its older report",
    )
    mode.delete()

    runner("pitestEncoding").build()
    assertFalse(
      fullReportDir.resolve(".scoped").exists(),
      "an unscoped report must never carry a scoped marker",
    )
    assertFalse(
      marker.exists(),
      "a later unscoped run must not revive an invalidated scoped diagnostic",
    )
  }

  @Test
  fun `PIT attempt cleanup removes only decision-grade leaves and truncates retained logs`() {
    writeFixture()
    runner("pitestEncoding").build()

    val report = File(fixtureDir, "build/reports/pitest/encoding")
    report.resolve("mutations.xml").writeText("stale XML")
    report.resolve("index.html").writeText("stale HTML index")
    report.resolve("pitest.stdout.log").writeText("stale stdout")
    report.resolve("pitest.stderr.log").writeText("stale stderr")
    report.resolve("custom/deep-report.html").apply {
      parentFile.mkdirs()
      writeText("consumer-owned")
    }

    runner("pitestEncoding").build()

    assertFalse(report.resolve("mutations.xml").exists(), "stale decision-grade XML survived")
    assertFalse(report.resolve("index.html").exists(), "stale decision-grade HTML index survived")
    assertFalse(report.resolve("pitest.stdout.log").readText().contains("stale stdout"))
    assertFalse(report.resolve("pitest.stderr.log").readText().contains("stale stderr"))
    assertEquals("consumer-owned", report.resolve("custom/deep-report.html").readText())
    assertTrue(report.resolve("mutations.csv").isFile)
    assertTrue(report.resolve(".evidence.tsv").isFile)
    assertFalse(report.resolve(".running").exists())
  }

  @Test
  fun `a process failure before report creation cannot expose the previous report`() {
    writeFixture()
    runner("pitestEncoding").build()

    val report = File(fixtureDir, "build/reports/pitest/encoding")
    report.resolve("mutations.xml").writeText("stale XML")
    report.resolve("index.html").writeText("stale HTML index")
    File(fixtureDir, "fake-pit-mode.txt").writeText("fail-before-report\n")

    val failed = runner("pitestEncoding").buildAndFail().output

    assertTrue(failed.contains("failed attempt raw logs"), failed)
    assertTrue(report.resolve(".running").isFile, "failed attempt exposed old evidence")
    listOf("mutations.csv", "mutations.xml", "index.html", ".evidence.tsv", ".toolchain.tsv")
      .forEach { stale -> assertFalse(report.resolve(stale).exists(), "stale $stale survived") }
    assertTrue(report.resolve("pitest.stderr.log").readText().endsWith("failed before report"))
  }

  @Test
  fun `diagnostic PIT is verbose history-free isolated and cannot replace ordinary evidence`() {
    writeFixture()
    runner("pitestEncoding").build()
    val ordinary = File(fixtureDir, "build/reports/pitest/encoding")
    fun decisionSnapshot(): Map<String, List<Byte>> =
      listOf("mutations.csv", ".evidence.tsv", ".toolchain.tsv")
        .associateWith { ordinary.resolve(it).readBytes().toList() }
    val before = decisionSnapshot()

    val diagnostic = runner("pitestEncodingDiagnostic").build()
    val report = File(fixtureDir, "build/reports/pitest-diagnostic/encoding")
    val arguments = report.resolve("arguments.txt").readLines()

    assertTrue("--verbosity=VERBOSE_NO_SPINNER" in arguments, arguments.toString())
    assertFalse(arguments.any { it.startsWith("--history") })
    assertFalse(arguments.contains("--features=+arcmutate_history"))
    assertTrue(report.resolve("mutations.csv").isFile)
    assertTrue(report.resolve("pitest.stdout.log").isFile)
    assertTrue(report.resolve("pitest.stderr.log").isFile)
    assertFalse(report.resolve(".evidence.tsv").exists())
    assertFalse(report.resolve(".toolchain.tsv").exists())
    assertFalse(report.resolve(".running").exists())
    assertEquals(before, decisionSnapshot(), "diagnosis replaced ordinary suite evidence")
    assertTrue(
      diagnostic.output.contains("VERBOSE_NO_SPINNER diagnostic") &&
          diagnostic.output.contains("isolated output cannot support") &&
          diagnostic.output.contains(report.resolve("pitest.stdout.log").absolutePath) &&
          !diagnostic.output.contains("pitest 'encoding': 1/1 detected"),
      diagnostic.output,
    )
    assertFalse(diagnostic.output.contains("arcmutateMissing"), diagnostic.output)

    runner(
      "pitestEncodingDiagnostic",
      "-PmutateOnly=com.example.Codec",
    ).build()
    val scopedReport = File(fixtureDir, "build/reports/pitest-diagnostic-scoped/encoding")
    assertEquals("com.example.Codec\n", scopedReport.resolve(".scoped").readText())
    assertTrue(scopedReport.resolve("pitest.stdout.log").isFile)
    assertEquals(before, decisionSnapshot(), "scoped diagnosis replaced ordinary evidence")
    val scopedRepeat = runner(
      "pitestEncodingDiagnostic",
      "-PmutateOnly=com.example.Codec",
    ).build()
    assertTrue(scopedRepeat.output.contains("Configuration cache entry reused."), scopedRepeat.output)
  }

  @Test
  fun `licensed diagnostic distinguishes PIT html promotion from ArcMutate activation`() {
    writeFixture()
    enableFakeArcMutate()
    File(fixtureDir, "fake-pit-mode.txt").writeText("raw-options\n")

    val diagnostic = runner("pitestEncodingDiagnostic").build()
    val clarification =
      "With audited PIT 1.25.9, its raw arcmutateMissing field controls only the HTML promotion"
    val raw = "ReportOptions [... arcmutateMissing=true ...]"

    assertTrue(
      diagnostic.output.contains(
        "licensed ArcMutate base 1.7.1 was validated on the effective tool classpath"
      ) && diagnostic.output.contains(clarification),
      diagnostic.output,
    )
    assertTrue(diagnostic.output.indexOf(clarification) < diagnostic.output.indexOf(raw), diagnostic.output)
    val retained = File(
      fixtureDir,
      "build/reports/pitest-diagnostic/encoding/pitest.stdout.log",
    ).readText()
    assertTrue(retained.contains(raw), retained)
    assertFalse(retained.contains(clarification), retained)
  }

  @Test
  fun `mutator trial follows the normal suite process customization`() {
    writeFixture(
      buildTail =
        """
          tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
            verbosity.set("NO_SPINNER")
          }
        """.trimIndent(),
    )

    runner(
      "pitestEncodingMutatorTrial",
      "-PtrialMutators=EXPERIMENTAL_BIG_INTEGER",
    ).build()

    val report = File(fixtureDir, "build/reports/pitest/encoding-trial")
    val arguments = report.resolve("arguments.txt").readLines()
    assertTrue(
      "--mutators=EXPERIMENTAL_BIG_INTEGER" in arguments,
      "trial did not use the candidate mutator set: $arguments",
    )
    assertTrue(
      "--verbosity=NO_SPINNER" in arguments,
      "trial did not inherit the normal task's evidence-bound verbosity: $arguments",
    )
    assertFalse(arguments.any { it.startsWith("--history") })
    assertTrue(
      report.resolve("mutations.csv").isFile,
      "normal-task main/classpath did not reach FakePit",
    )
    assertFalse(report.resolve(".evidence.tsv").exists())
    assertFalse(report.resolve(".toolchain.tsv").exists())

    val repeated = runner(
      "pitestEncodingMutatorTrial",
      "-PtrialMutators=EXPERIMENTAL_BIG_INTEGER",
    ).build()
    assertTrue(repeated.output.contains("Configuration cache entry reused."), repeated.output)

    File(fixtureDir, "fake-pit-mode.txt").writeText("zero-fire\n")
    val zeroFire = runner(
      "pitestMutatorTrial",
      "-PtrialMutators=EXPERIMENTAL_BIG_INTEGER",
    ).build()
    assertTrue(
      zeroFire.output.contains(
        "tolerated non-zero exit 2; for a mutator trial a candidate set that cannot fire " +
          "is an expected cause. Raw logs",
      ),
      zeroFire.output,
    )
    assertTrue(
      zeroFire.output.contains("fired in 0 of 1 suite(s)") &&
          zeroFire.output.contains("0 generated (no report — cannot fire here, or the run failed above)"),
      zeroFire.output,
    )
    assertTrue(
      zeroFire.output.contains(report.resolve("pitest.stdout.log").absolutePath) &&
          zeroFire.output.contains(report.resolve("pitest.stderr.log").absolutePath),
      zeroFire.output,
    )
    assertFalse(zeroFire.output.contains("failed attempt raw logs"), zeroFire.output)
  }

  @Test
  fun `diagnostic report paths cannot be redirected onto decision-grade evidence`() {
    writeFixture(
      buildTail =
        """
          if (providers.gradleProperty("redirectDiagnostic").isPresent) {
            tasks.named<software.sava.build.hardening.task.PitestDiagnosticTask>(
                "pitestEncodingDiagnostic") {
              reportDirectory.set(layout.buildDirectory.dir("reports/pitest/encoding"))
            }
          }
        """.trimIndent(),
    )
    runner("pitestEncoding").build()
    val ordinary = File(fixtureDir, "build/reports/pitest/encoding")
    val before = listOf("mutations.csv", ".evidence.tsv", ".toolchain.tsv")
      .associateWith { ordinary.resolve(it).readBytes().toList() }

    val refused = runner("pitestEncodingDiagnostic", "-PredirectDiagnostic").buildAndFail().output

    assertTrue(
      refused.contains("cannot be changed") || refused.contains("cannot change"),
      refused,
    )
    assertEquals(
      before,
      before.keys.associateWith { ordinary.resolve(it).readBytes().toList() },
      "refused diagnostic redirection changed ordinary evidence",
    )
  }

  @Test
  fun `diagnostic PIT refuses customization that weakens its non-evidence invariants`() {
    writeFixture(
      buildTail =
        """
          tasks.named<software.sava.build.hardening.task.PitestDiagnosticTask>(
              "pitestEncodingDiagnostic") {
            historyRequested.set(true)
            bindSuiteEvidence.set(true)
            enforceExit.set(false)
            diagnosticMode.set(false)
            verbosity.set("DEFAULT")
          }
        """.trimIndent(),
    )

    val report = File(fixtureDir, "build/reports/pitest-diagnostic/encoding")
    val preserved = report.resolve("preserved.txt").apply {
      parentFile.mkdirs()
      writeText("prior diagnostic\n")
    }
    val failed = runner("pitestEncodingDiagnostic").buildAndFail().output

    assertTrue(failed.contains("diagnostic safety invariant(s) were overridden"), failed)
    listOf(
      "historyRequested must be false",
      "bindSuiteEvidence must be false",
      "enforceExit must be true",
      "diagnosticMode must be true",
      "verbosity must be VERBOSE_NO_SPINNER",
    ).forEach { finding -> assertTrue(failed.contains(finding), failed) }
    assertEquals(
      "prior diagnostic\n",
      preserved.readText(),
      "refused diagnostic changed its prior isolated output",
    )
  }

  @Test
  fun `unmanaged PIT arguments are refused before report evidence is touched`() {
    writeFixture(
      buildTail =
        """
          if (providers.gradleProperty("unmanagedPitArgs").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") {
              args("--excludedMethods=hiddenOverride")
            }
          }
          if (providers.gradleProperty("unmanagedPitProvider").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") {
              argumentProviders.add(object : org.gradle.process.CommandLineArgumentProvider {
                override fun asArguments(): Iterable<String> =
                  listOf("--excludedMethods=hiddenProviderOverride")
              })
            }
          }
        """.trimIndent(),
    )
    runner("pitestEncoding").build()

    val report = File(fixtureDir, "build/reports/pitest/encoding")
    val protectedFiles = listOf(
      "mutations.csv",
      ".evidence.tsv",
      ".toolchain.tsv",
      "arguments.txt",
    )
    val before = protectedFiles.associateWith { report.resolve(it).readBytes().toList() }

    listOf("unmanagedPitArgs", "unmanagedPitProvider").forEach { property ->
      val refused = runner(
        "pitestEncoding",
        "-P$property",
        "--no-configuration-cache",
      ).buildAndFail().output
      assertTrue(
        refused.contains("direct JavaExec args/argumentProviders are not supported") &&
            refused.contains("first-class typed, evidence-bound plugin property"),
        refused,
      )
      assertEquals(
        before,
        protectedFiles.associateWith { report.resolve(it).readBytes().toList() },
        "$property changed prior decision-grade output before refusal",
      )
      assertFalse(
        report.resolve(".running").exists(),
        "$property reached the PIT attempt lifecycle before refusal",
      )
    }
  }

  @Test
  fun `a fuzz target execution is configuration-cache clean`() {
    writeFixture()

    val first = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(first.output.contains("fixture fuzz executed"), first.output)
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
      "a standalone fuzz target must not create an aggregate receipt",
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.running").exists(),
      "a standalone fuzz target must not activate an aggregate campaign",
    )

    val second = runner("fuzzPlain", "-PmaxFuzzTime=1").build()
    assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
    assertTrue(second.output.contains("fixture fuzz executed"), second.output)
  }

  @Test
  fun `fuzzAll attests every configured target completed in this invocation`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val result = runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")

    assertEquals(3, occurrences(result.output, "fixture fuzz executed"), result.output)
    assertTrue(receipt.isFile, "fuzzAll did not write its receipt:\n${result.output}")
    assertEquals(
      listOf(
        "schema\t4",
        "project\t:",
        "pluginSha256\t" + receipt.readLines().single { it.startsWith("pluginSha256\t") }
          .substringAfter('\t'),
        "maxFuzzTimeSeconds\t1",
        "maxParallelTargets\t1",
        "totalExecutions\t606",
        "target\tfuzzCodec\t101",
        "target\tfuzzHollow\t202",
        "target\tfuzzPlain\t303",
      ),
      receipt.readLines(),
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.running").exists(),
      "successful campaign retained its running sentinel",
    )
  }

  @Test
  fun `fuzzAll invalid parallel width invalidates old evidence and retains its refusal`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv").apply {
      parentFile.mkdirs()
      writeText("prior completed campaign\n")
    }
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")

    val failed = runner(
      "fuzzAll",
      "-PmaxFuzzTime=1",
      "-PmaxParallelFuzzTargets=0",
    ).buildAndFail()

    assertTrue(
      failed.output.contains(
        "-PmaxParallelFuzzTargets must be positive whole targets without leading zeros",
      ),
      "invalid parallel width was misreported:\n${failed.output}",
    )
    assertFalse(receipt.exists(), "invalid settings retained an older successful receipt")
    assertTrue(running.isFile, "invalid settings did not retain the refusal sentinel")
    assertTrue(
      running.readText().startsWith("refused\t") &&
          running.readText().contains("-PmaxParallelFuzzTargets must be positive whole targets"),
      "invalid settings retained the wrong campaign state:\n${running.readText()}",
    )
  }

  @Test
  fun `fuzzAll refuses missing ambiguous and zero live execution counts`() {
    listOf(
      "missing" to "emitted no terminal",
      "ambiguous" to "ambiguous campaign receipt",
      "zero" to "must be positive",
    ).forEach { (mode, message) ->
      writeFixture()
      writeSeedCorpus()
      File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
      File(fixtureDir, "fake-fuzz-mode.txt").writeText("$mode\n")

      val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()

      assertTrue(failed.output.contains(message), "$mode failure was misreported:\n${failed.output}")
      assertFalse(
        File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
        "$mode execution evidence earned a fuzzAll receipt",
      )
      assertTrue(
        File(fixtureDir, ".pitest-history/local-fuzz.running").isFile,
        "$mode execution evidence did not retain the invalidation sentinel",
      )
    }
  }

  @Test
  fun `fuzzAll cannot attest a failed target when JavaExec ignores its exit value`() {
    writeFixture(buildTail = """
      tasks.named<JavaExec>("fuzzPlain") {
        isIgnoreExitValue = true
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("fail\n")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()

    assertTrue(failed.output.contains("non-zero exit value 4"), failed.output)
    assertFalse(
      File(fixtureDir, ".pitest-history/local-fuzz.tsv").exists(),
      "a failed target earned a fuzzAll receipt",
    )
    assertTrue(
      File(fixtureDir, ".pitest-history/local-fuzz.running").isFile,
      "a failed campaign did not retain its invalidation sentinel",
    )
  }

  @Test
  fun `fuzzAll refuses every exclusion before children while standalone exclusions preserve evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")

    receipt.parentFile.mkdirs()
    receipt.writeText("stale successful receipt\n")
    running.writeText("stale running sentinel\n")
    val excludedTarget = runner(
      "fuzzAll", "-PmaxFuzzTime=1", "-x", "fuzzPlain",
    ).buildAndFail()
    assertTrue(
      excludedTarget.output.contains("fuzzAll requires its complete task graph"),
      excludedTarget.output,
    )
    assertTrue(excludedTarget.output.contains("-x fuzzPlain"), excludedTarget.output)
    assertFalse(receipt.exists(), "excluded target retained a stale fuzzAll receipt")
    assertTrue(running.isFile, "excluded target did not leave an incomplete-campaign sentinel")

    receipt.writeText("another stale successful receipt\n")
    assertTrue(running.delete(), "could not model a prior completed campaign")
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("fail\n")
    val excludedPreflight = runner(
      "fuzzAll", "-PmaxFuzzTime=1", "-x", "fuzzAllPreflight",
    ).buildAndFail()
    assertTrue(
      excludedPreflight.output.contains("fuzzAll requires its complete task graph"),
      excludedPreflight.output,
    )
    assertTrue(excludedPreflight.output.contains("-x fuzzAllPreflight"), excludedPreflight.output)
    assertFalse(
      excludedPreflight.output.contains("fixture fuzz executed"),
      "excluded preflight allowed a target to run before invalidation:\n${excludedPreflight.output}",
    )
    assertFalse(receipt.exists(), "excluded preflight retained a stale fuzzAll receipt")
    assertTrue(running.isFile, "excluded preflight did not publish a running sentinel")
    assertTrue(
      running.readText().startsWith("refused\t") &&
          running.readText().contains("-x fuzzAllPreflight"),
      "excluded preflight did not replace the stale sentinel:\n${running.readText()}",
    )

    receipt.writeText("valid aggregate evidence untouched by standalone work\n")
    assertTrue(running.delete(), "could not model a completed aggregate before standalone work")
    File(fixtureDir, "fake-fuzz-mode.txt").delete()
    val standalone = runner(
      "fuzzPlain", "-PmaxFuzzTime=1", "-x", "fuzzAllPreflight",
    ).build()
    assertTrue(standalone.output.contains("fixture fuzz executed"), standalone.output)
    assertEquals(
      "valid aggregate evidence untouched by standalone work\n",
      receipt.readText(),
      "a standalone target invalidated unrelated aggregate evidence",
    )
    assertFalse(running.exists(), "a standalone target activated an aggregate campaign")

    val excludedInternals = runner(
      "fuzzAll", "-PmaxFuzzTime=1",
      "-x", "fuzzAllPreflight", "-x", "validateFuzzBudget",
    ).buildAndFail()
    assertTrue(
      excludedInternals.output.contains("fuzzAll requires its complete task graph"),
      excludedInternals.output,
    )
    assertFalse(
      excludedInternals.output.contains("fixture fuzz executed"),
      "excluding both old internal boundaries allowed a child to run:\n${excludedInternals.output}",
    )
    assertFalse(receipt.exists(), "excluded internal tasks retained stale aggregate evidence")
    assertTrue(running.isFile, "excluded internal tasks did not leave a refusal sentinel")
  }

  @Test
  fun `cold and standalone evidence graphs schedule transformed runtime project jars`() {
    writeModularProjectDependencyFixture()
    val libraryJar = File(fixtureDir, "library/build/libs/library.jar")
    assertFalse(libraryJar.exists(), "fixture producer JAR unexpectedly existed before the cold build")

    val result = runner(":consumer:pitestEncoding", "--warning-mode=fail").build()

    assertTrue(libraryJar.isFile, "producer task did not create the transformed project dependency JAR")
    assertTrue(
      result.output.contains("> Task :library:jar"),
      "PIT did not schedule the runtime-only project JAR producer:\n${result.output}",
    )
    assertTrue(result.output.contains("Configuration cache entry stored."), result.output)
    assertTrue(
      File(fixtureDir, "consumer/build/reports/pitest/encoding/.evidence.tsv").isFile,
      "PIT execution did not commit evidence:\n${result.output}",
    )
    assertTrue(
      result.output.contains(
        ":consumer pitest 'encoding': slowest PIT coverage-phase test " +
            "'[engine:junit-jupiter]/[class:com.example.consumer.ConsumerTest]/" +
            "[method:roundTrip(java.lang.String)]' took 416 ms",
      ),
      "the slow-test advisory was not project-qualified or lost the test identity:\n${result.output}",
    )
    assertTrue(
      result.output.contains("advisory threshold 250 ms") &&
          result.output.contains("does not prove the test covers a target mutant") &&
          result.output.contains("or prescribe a remedy") &&
          result.output.contains("when it does cover mutated code"),
      "the advisory did not explain the measurement's boundary:\n${result.output}",
    )
    assertTrue(
      result.output.contains("hardening: 1 advisory finding(s) across 1 scope(s)") &&
          result.output.contains(
            ":consumer pitest 'encoding': slowest PIT coverage-phase test took 416 ms",
          ),
      "duplicate PIT summary lines produced missing or duplicate advisory findings:\n${result.output}",
    )

    val reused = runner(":consumer:pitestEncoding", "--warning-mode=fail").build()
    assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
    assertTrue(
      reused.output.contains(
        ":consumer pitest 'encoding': slowest PIT coverage-phase test " +
            "'[engine:junit-jupiter]/[class:com.example.consumer.ConsumerTest]/" +
            "[method:roundTrip(java.lang.String)]' took 416 ms",
      ),
      "the advisory disappeared after a configuration-cache round trip:\n${reused.output}",
    )

    assertTrue(libraryJar.delete(), "could not remove the runtime-only JAR before standalone verify")
    val verify = runnerWithoutConfigurationCache(
      ":consumer:pitestEncodingVerify",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "standalone verify did not rebuild its runtime-only evidence input")
    assertTrue(
      verify.output.contains("> Task :library:jar"),
      "standalone verify did not schedule the runtime-only JAR producer:\n${verify.output}",
    )

    assertTrue(libraryJar.delete(), "could not remove the runtime-only JAR before mode snapshot")
    val snapshot = runnerWithoutConfigurationCache(
      ":consumer:pitestModeSnapshot",
      "-PpitestMode=standalone",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "standalone mode snapshot did not rebuild its evidence input")
    assertTrue(
      snapshot.output.contains("> Task :library:jar"),
      "standalone mode snapshot did not schedule the runtime-only JAR producer:\n${snapshot.output}",
    )
    assertTrue(snapshot.output.contains("stashed as 'standalone'"), snapshot.output)
  }

  @Test
  fun `certification declares transformed classpaths before its task action`() {
    writeModularProjectDependencyFixture()
    val libraryJar = File(fixtureDir, "library/build/libs/library.jar")
    assertFalse(libraryJar.exists(), "fixture producer JAR unexpectedly existed before certification")

    // Gradle 9.7 still emits this as a deprecation; --warning-mode=fail gives the
    // fixture Gradle 10's effective fail-closed boundary before a 10.x RC exists.
    val certified = runnerWithoutConfigurationCache(
      ":consumer:hardeningCertify",
      "--warning-mode=fail",
    ).build()
    assertTrue(libraryJar.isFile, "certification did not build its transformed runtime input")
    assertTrue(certified.output.contains("> Task :library:jar"), certified.output)
    assertTrue(
      certified.output.contains("> Task :consumer:hardeningCertify"),
      certified.output,
    )
    assertFalse(
      certified.output.contains(
        "Querying the output of an artifact transform from a task action without declaring it " +
          "as a task input has been deprecated.",
      ),
      "hardeningCertify resolved an undeclared transform output from its task action:\n" +
        certified.output,
    )
    assertTrue(
      File(fixtureDir, "consumer/.pitest-history/pitest-certification.tsv").isFile,
      "certification did not publish a receipt:\n${certified.output}",
    )
  }

  @Test
  fun `noMutationHistory and certification retain the licensed PIT tool population`() {
    writeHistoryClasspathProbeFixture()
    val evidenceFile = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    val reportFile = File(fixtureDir, "build/reports/pitest/encoding/mutations.csv")
    val argsFile = File(fixtureDir, "build/fake-pit-probe/args.txt")
    val classpathFile = File(fixtureDir, "build/fake-pit-probe/classpath.txt")

    val assisted = runner("clean", "pitestEncoding").build()
    val assistedEvidence = PitestEvidence.parse(evidenceFile.readText())
    val assistedArgs = argsFile.readLines()
    val assistedClasspath = classpathFile.readLines()
    val assistedPopulation = reportFile.readLines().size
    assertTrue(
      assistedClasspath.any { it.startsWith("base-") && it.endsWith(".jar") },
      "licensed ordinary PIT did not carry com.arcmutate:base:\n${assistedClasspath.joinToString("\n")}",
    )
    assertTrue(assistedArgs.contains("--features=+arcmutate_history"), assistedArgs.toString())
    assertTrue(assistedArgs.any { it.startsWith("--historyOutputLocation=") }, assistedArgs.toString())
    assertTrue(assistedEvidence.historyAssisted, assisted.output)
    assertEquals(1, assistedPopulation, "the classpath-sensitive population probe did not activate")

    val assistedDebt = runner("pitestEncodingDebt").build().output
    assertTrue(
      assistedDebt.contains("latest full [history] report (read-only preview)") &&
          assistedDebt.contains(
            "pitestEncoding -PnoMutationHistory before any accepted-baseline or timeout-audit decision",
          ),
      "Debt presented a real assisted PIT report as decision evidence:\n$assistedDebt",
    )

    val strictAgainstAssisted = runner("pitestEncodingVerify", "-PstrictTimeoutAudit")
      .buildAndFail().output
    assertTrue(
      strictAgainstAssisted.contains("strict timeout audit refuses a history-assisted report") &&
          strictAgainstAssisted.contains("pitestEncoding -PstrictTimeoutAudit") &&
          strictAgainstAssisted.contains("disables history automatically"),
      strictAgainstAssisted,
    )

    val fresh = runner("clean", "pitestEncoding", "-PnoMutationHistory").build()
    val freshEvidence = PitestEvidence.parse(evidenceFile.readText())
    val freshArgs = argsFile.readLines()
    val freshClasspath = classpathFile.readLines()
    val freshPopulation = reportFile.readLines().size
    assertTrue(
      freshClasspath.any { it.startsWith("base-") && it.endsWith(".jar") },
      "-PnoMutationHistory dropped com.arcmutate:base from the PIT tool classpath:\n" +
          freshClasspath.joinToString("\n"),
    )
    assertFalse(freshArgs.any { it.startsWith("--history") }, freshArgs.toString())
    assertFalse(freshArgs.contains("--features=+arcmutate_history"), freshArgs.toString())
    assertFalse(freshEvidence.historyAssisted, fresh.output)
    assertEquals(assistedClasspath, freshClasspath, "the flag changed the licensed PIT tool classpath")
    assertEquals(assistedPopulation, freshPopulation, "the flag changed the mutation population")
    assertEquals(
      assistedEvidence.toolClasspathSha256,
      freshEvidence.toolClasspathSha256,
      "the flag changed the evidence-bound PIT tool classpath",
    )

    val strict = runner("clean", "pitestEncoding", "-PstrictTimeoutAudit").build()
    val strictEvidence = PitestEvidence.parse(evidenceFile.readText())
    val strictArgs = argsFile.readLines()
    val strictClasspath = classpathFile.readLines()
    val strictPopulation = reportFile.readLines().size
    assertFalse(strictArgs.any { it.startsWith("--history") }, strictArgs.toString())
    assertFalse(strictArgs.contains("--features=+arcmutate_history"), strictArgs.toString())
    assertFalse(strictEvidence.historyAssisted, strict.output)
    assertEquals(freshClasspath, strictClasspath, "strict audit changed the licensed PIT tool classpath")
    assertEquals(freshPopulation, strictPopulation, "strict audit changed the mutation population")
    assertEquals(
      freshEvidence.toolClasspathSha256,
      strictEvidence.toolClasspathSha256,
      "strict audit bound a different PIT tool classpath into its evidence",
    )

    val certified = runner("clean", "hardeningCertify").build()
    val certifiedEvidence = PitestEvidence.parse(evidenceFile.readText())
    val certifiedArgs = argsFile.readLines()
    val certifiedClasspath = classpathFile.readLines()
    val certifiedPopulation = reportFile.readLines().size
    assertFalse(certifiedArgs.any { it.startsWith("--history") }, certifiedArgs.toString())
    assertFalse(certifiedArgs.contains("--features=+arcmutate_history"), certifiedArgs.toString())
    assertFalse(certifiedEvidence.historyAssisted, certified.output)
    assertEquals(freshClasspath, certifiedClasspath, "fresh workflows used different PIT tool classpaths")
    assertEquals(freshPopulation, certifiedPopulation, "fresh workflows observed different populations")
    assertEquals(
      freshEvidence.toolClasspathSha256,
      certifiedEvidence.toolClasspathSha256,
      "fresh workflows bound different PIT tool classpaths into their evidence",
    )
  }

  @Test
  fun `isolated mutation units are scoped history-free diagnostics`() {
    writeHistoryClasspathProbeFixture()
    val fullReportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val scopedReportDir = File(fixtureDir, "build/reports/pitest-scoped/encoding")
    val argsFile = File(fixtureDir, "build/fake-pit-probe/args.txt")

    runner("pitestEncoding").build()
    val fullSnapshot = listOf("mutations.csv", ".evidence.tsv", ".toolchain.tsv")
      .associateWith { fullReportDir.resolve(it).readBytes().toList() }

    val scopedControl = runner(
      "pitestEncoding",
      "-PmutateOnly=com.example.FakePit",
      "-PnoMutationHistory",
    ).build()
    val controlArgs = argsFile.readLines()
    val controlEvidence = PitestEvidence.parse(
      scopedReportDir.resolve(".evidence.tsv").readText(),
    )
    assertFalse(controlArgs.any { it.startsWith("--history") }, controlArgs.toString())
    assertFalse(controlArgs.contains("--features=+arcmutate_history"), controlArgs.toString())
    assertFalse(controlArgs.any { it.startsWith("--mutationUnitSize=") }, controlArgs.toString())
    assertEquals("com.example.FakePit", controlEvidence.scope)
    assertFalse(controlEvidence.historyAssisted)
    assertTrue(scopedControl.output.contains("SCOPED run"), scopedControl.output)

    val isolated = runner(
      "pitestEncoding",
      "-PmutateOnly=com.example.FakePit",
      "-PisolateMutants",
    ).build()
    val isolatedArgs = argsFile.readLines()
    val isolatedEvidence = PitestEvidence.parse(
      scopedReportDir.resolve(".evidence.tsv").readText(),
    )

    assertTrue(isolatedArgs.contains("--mutationUnitSize=1"), isolatedArgs.toString())
    assertFalse(isolatedArgs.any { it.startsWith("--history") }, isolatedArgs.toString())
    assertFalse(isolatedArgs.contains("--features=+arcmutate_history"), isolatedArgs.toString())
    assertEquals("com.example.FakePit", isolatedEvidence.scope)
    assertFalse(isolatedEvidence.historyAssisted)
    assertTrue(
      isolated.output.contains("one-mutant-per-unit diagnostic") &&
          isolated.output.contains("cannot support a record decision"),
      isolated.output,
    )
    fullSnapshot.forEach { (name, bytes) ->
      assertEquals(bytes, fullReportDir.resolve(name).readBytes().toList(), name)
    }

    val reused = runner(
      "pitestEncoding",
      "-PmutateOnly=com.example.FakePit",
      "-PisolateMutants",
    ).build()
    assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)

    val ordinaryValidation = runner(
      "pitestEncodingVerify",
      "-PmutateOnly=com.example.FakePit",
    ).buildAndFail().output
    assertTrue(
      ordinaryValidation.contains("configurationSha256"),
      "isolated evidence revalidated as an ordinary scoped run:\n$ordinaryValidation",
    )

    val missingScope = runner("pitestEncoding", "-PisolateMutants").buildAndFail().output
    assertTrue(missingScope.contains("-PisolateMutants requires -PmutateOnly"), missingScope)
    assertFalse(fullReportDir.resolve(".running").exists(), "refused isolation started a full attempt")
    fullSnapshot.forEach { (name, bytes) ->
      assertEquals(bytes, fullReportDir.resolve(name).readBytes().toList(), name)
    }
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
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(evidence.isFile, "completed PIT evidence missing:\n${certified.output}")
    assertTrue(receipt.isFile, "certification receipt missing:\n${certified.output}")
    val receiptText = receipt.readText()
    assertTrue(receiptText.contains("schema\t6"), receiptText)
    assertTrue(receiptText.contains("session\t"), receiptText)
    assertTrue(receiptText.contains("gitState\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitCommit\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitTree\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitStatusSha256\tunavailable"), receiptText)
    assertTrue(receiptText.contains("gitProjectDirectory\tunavailable"), receiptText)
    assertTrue(Regex("(?m)^pluginSha256\\t[0-9a-f]{64}$").containsMatchIn(receiptText), receiptText)
    assertTrue(receiptText.contains("toolClasspathSha256"), receiptText)
    assertTrue(receiptText.contains("mutationToolchainSha256"), receiptText)
    assertTrue(receiptText.contains("recordInputsSha256"), receiptText)
    assertTrue(receiptText.contains("recordPitestVersion"), receiptText)
    assertTrue(receiptText.contains("recordMutationToolchainSha256"), receiptText)
    assertTrue(receiptText.contains("mode\tfresh-full-strict"), receiptText)
    assertTrue(receiptText.contains("suite\tencoding"), receiptText)
    assertTrue(
      receiptText.contains("\tlegacy-unversioned\tlegacy-toolchain-unbound\n"),
      receiptText,
    )
    val columns = receiptText.lineSequence().first { it.startsWith("columns\t") }.split('\t')
    val suiteRow = receiptText.lineSequence().first { it.startsWith("suite\tencoding\t") }.split('\t')
    val recordInputsIndex = columns.indexOf("recordInputsSha256") - 1
    val suitePluginIndex = columns.indexOf("pluginSha256") - 1
    val topLevelPlugin = receiptText.lineSequence()
      .single { it.startsWith("pluginSha256\t") }.substringAfter('\t')
    assertEquals(
      topLevelPlugin,
      suiteRow[suitePluginIndex],
      "top-level plugin identity diverged from the suite evidence",
    )
    val configDir = File(fixtureDir, "config/pitest")
    assertEquals(
      PitestEvidence.fingerprint(
        configDir,
        listOf(
          File(configDir, "encoding-accepted.csv"),
          File(configDir, "encoding-timeouts.csv"),
          File(configDir, "encoding-pitest-version"),
          File(configDir, "encoding-pitest-toolchain.tsv"),
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

    val transition = runner("clean", "hardeningCertify").build()
    assertTrue(
      transition.output.contains("Reusing configuration cache"),
      "completed evidence creation invalidated the invariant configuration-cache graph:\n${transition.output}",
    )
    val reused = runner("clean", "hardeningCertify").build()
    assertTrue(reused.output.contains("Reusing configuration cache"), reused.output)

    File(fixtureDir, "src/main/java/com/example/Codec.java").appendText("\n// source changed after PIT\n")
    val stale = runner("pitestEncodingVerify").buildAndFail().output
    assertTrue(stale.contains("completed report evidence no longer matches the current build"), stale)
    assertTrue(stale.contains("sourceSha256"), stale)
  }

  @Test
  fun `durable certification survives clean and retires the configured legacy location`() {
    writeFixture(
      buildTail =
        "layout.buildDirectory.set(layout.projectDirectory.dir(\"external-build\"))",
    )
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val legacyReceipt = File(fixtureDir, "external-build/hardening/pitest-certification.tsv").apply {
      parentFile.mkdirs()
      writeText("legacy receipt\n")
    }
    val legacyRunning = File(fixtureDir, "external-build/hardening/pitest-certification.running").apply {
      writeText("legacy interruption\n")
    }

    val migrated = runner("hardeningCertify").build()

    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val certifiedBytes = receipt.readBytes()
    assertTrue(
      migrated.output.contains("removed superseded legacy build-output certification state") &&
          migrated.output.contains(".pitest-history/pitest-certification.tsv") &&
          migrated.output.contains(legacyReceipt.absolutePath) &&
          migrated.output.contains(legacyRunning.absolutePath),
      "legacy certification migration was silent:\n${migrated.output}",
    )
    assertFalse(legacyReceipt.exists(), "legacy build-output receipt survived certification")
    assertFalse(legacyRunning.exists(), "legacy build-output sentinel survived certification")
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.running").exists(),
      "successful certification retained its durable sentinel",
    )

    runner("clean").build()

    assertTrue(receipt.isFile, "clean erased durable certification evidence")
    assertEquals(certifiedBytes.toList(), receipt.readBytes().toList())
  }

  @Test
  fun `ordinary quality gate failure cannot precede certification invalidation`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(receipt.isFile)

    File(fixtureDir, "build.gradle.kts").appendText(
      """

      tasks.named("test") {
        doLast { throw GradleException("deliberate ordinary test failure") }
      }
      """.trimIndent() + "\n",
    )
    val failed = runnerWithoutConfigurationCache("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("deliberate ordinary test failure"), failed)
    assertFalse(receipt.exists(), "test failed before invalidating the prior certification")
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").isFile,
      "failed certification did not retain its durable invalidation sentinel",
    )
  }

  @Test
  fun `certification refuses an unignored durable state path before PIT`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    initializeGitFixture()
    File(fixtureDir, ".gitignore").writeText(".gradle/\nbuild/\n")
    git("add", ".gitignore")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "remove machine-local ignore",
    )

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("durable certification state must be machine-local"), failed)
    assertTrue(failed.contains("is not Git-ignored"), failed)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").exists(),
      "PIT ran before ignore refusal",
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.running").exists())
  }

  @Test
  fun `certification refuses a linked durable state directory before PIT`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val target = File(fixtureDir, "linked-history-target").apply { mkdirs() }
    Files.createSymbolicLink(
      File(fixtureDir, ".pitest-history").toPath(),
      target.toPath(),
    )

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("symbolic-link component"), failed)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").exists(),
      "PIT ran before linked-state refusal",
    )
  }

  @Test
  fun `a non-owner certification cannot replace durable evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val history = File(fixtureDir, ".pitest-history").apply { mkdirs() }
    val receipt = history.resolve("pitest-certification.tsv").apply {
      writeText("receipt published by the owning process\n")
    }
    val ownerBytes = receipt.readBytes()
    val lockFile = history.resolve("pitest-certification.lock")

    FileChannel.open(
      lockFile.toPath(),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use {
        val failed = runner("hardeningCertify").buildAndFail().output
        assertTrue(failed.contains("another hardeningCertify invocation owns"), failed)
      }
    }

    assertEquals(ownerBytes.toList(), receipt.readBytes().toList())
    assertFalse(history.resolve("pitest-certification.running").exists())
  }

  @Test
  fun `certification refuses a changed durable ownership sentinel`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-pit-mode.txt").writeText("tamper-certification-sentinel\n")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("does not own the exact durable session sentinel"), failed)
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertTrue(File(fixtureDir, ".pitest-history/pitest-certification.running").isFile)
  }

  @Test
  fun `certification binds a clean Git commit and remains visibly stale after HEAD advances`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (certifiedCommit, certifiedTree) = initializeGitFixture()

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val receiptAtA = receipt.readText()
    val statusAtA = git("status", "--porcelain=v1", "--untracked-files=all")
    assertTrue(receiptAtA.contains("schema\t6\n"), receiptAtA)
    assertTrue(receiptAtA.contains("gitState\tclean\n"), "status=$statusAtA\n$receiptAtA")
    assertTrue(receiptAtA.contains("gitCommit\t$certifiedCommit\n"), receiptAtA)
    assertTrue(receiptAtA.contains("gitTree\t$certifiedTree\n"), receiptAtA)
    assertTrue(
      receiptAtA.contains("gitStatusSha256\t${PitestEvidence.sha256(byteArrayOf())}\n"),
      receiptAtA,
    )
    assertTrue(receiptAtA.contains("gitProjectDirectory\t.\n"), receiptAtA)
    assertTrue(statusAtA.isEmpty(), "${certified.output}\n$statusAtA")

    File(fixtureDir, "post-certification.txt").writeText("advance the clean checkout\n")
    git("add", "post-certification.txt")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "advance after certification",
    )
    val currentCommit = git("rev-parse", "HEAD")
    val currentTree = git("rev-parse", "HEAD^{tree}")
    assertFalse(currentCommit == certifiedCommit)
    assertFalse(currentTree == certifiedTree)
    assertTrue(git("status", "--porcelain=v1", "--untracked-files=all").isEmpty())
    assertEquals(receiptAtA, receipt.readText(), "advancing HEAD rewrote the prior certification receipt")
    assertTrue(receipt.readText().contains("gitCommit\t$certifiedCommit\n"))
    assertFalse(receipt.readText().contains("gitCommit\t$currentCommit\n"))
  }

  @Test
  fun `certification records an explicit dirty Git state without refusing local use`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (commit, tree) = initializeGitFixture()
    File(fixtureDir, "dirty-marker.txt").writeText("deliberately uncommitted\n")

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()

    assertTrue(receipt.contains("gitState\tdirty\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$commit\n"), receipt)
    assertTrue(receipt.contains("gitTree\t$tree\n"), receipt)
    assertTrue(
      Regex("(?m)^gitStatusSha256\\t(?!${PitestEvidence.sha256(byteArrayOf())}$)[0-9a-f]{64}$")
        .containsMatchIn(receipt),
      receipt,
    )
    assertTrue(certified.output.contains("suite(s) certified"), certified.output)
  }

  @Test
  fun `clean certification refuses ignored record inputs absent from the Git tree`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest/README.md").also { file ->
      file.parentFile.mkdirs()
      file.writeText("# Ignored mutation rationale\n")
    }
    initializeGitFixture(listOf("config/pitest/README.md"))
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "the ignored record did not reproduce Git's false-clean state",
    )

    val failed = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      failed.contains("clean Git certification cannot bind mutation-record inputs to its captured tree") &&
          failed.contains("present locally but absent from captured tree") &&
          failed.contains("config/pitest/README.md"),
      failed,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "a rejected ignored record left a certification receipt",
    )
  }

  @Test
  fun `rebase warns when Git ignores its provenance and clean certification still refuses`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    File(configDir, "encoding-accepted.csv")
      .writeText("!sava-hardening-baseline-schema,1\n")
    initializeGitFixture(listOf(
      "config/pitest/*-pitest-version",
      "config/pitest/*-pitest-toolchain.tsv",
    ))

    val rebase = runner("pitestEncodingBaselineRebase").build().output
    val pitVersion = File(configDir, "encoding-pitest-version")
    val toolchain = File(configDir, "encoding-pitest-toolchain.tsv")
    assertTrue(pitVersion.isFile && toolchain.isFile, rebase)
    assertTrue(
      rebase.contains("wrote mutation-record input(s) that Git ignores") &&
          rebase.contains("ordinary git status can hide them") &&
          rebase.contains("clean hardeningCertify will refuse") &&
          rebase.contains("encoding-pitest-version") &&
          rebase.contains("encoding-pitest-toolchain.tsv"),
      rebase,
    )
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "ignored provenance did not reproduce the false-clean porcelain state",
    )

    val refused = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      refused.contains("clean Git certification cannot bind mutation-record inputs to its captured tree") &&
          refused.contains("present locally but absent from captured tree") &&
          refused.contains("encoding-pitest-version") &&
          refused.contains("encoding-pitest-toolchain.tsv"),
      refused,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "ignored provenance left a clean certification receipt",
    )

    git("add", "-f", "config/pitest/encoding-pitest-version",
      "config/pitest/encoding-pitest-toolchain.tsv")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "track mutation provenance",
    )
    val trackedCommit = git("rev-parse", "HEAD")
    val trackedRebase = runner("pitestEncodingBaselineRebase").build().output
    assertFalse(
      trackedRebase.contains("wrote mutation-record input(s) that Git ignores"),
      trackedRebase,
    )
    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()
    assertTrue(certified.output.contains("suite(s) certified"), certified.output)
    assertTrue(receipt.contains("gitState\tclean\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$trackedCommit\n"), receipt)
  }

  @Test
  fun `rebase does not warn for provenance exempted by negated ignore rules`() {
    writeFixture()
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    File(configDir, "encoding-accepted.csv")
      .writeText("!sava-hardening-baseline-schema,1\n")
    initializeGitFixture(listOf(
      "config/pitest/*-pitest-version",
      "config/pitest/*-pitest-toolchain.tsv",
      "!config/pitest/encoding-pitest-version",
      "!config/pitest/encoding-pitest-toolchain.tsv",
    ))

    val rebase = runner("pitestEncodingBaselineRebase").build().output
    assertFalse(rebase.contains("wrote mutation-record input(s) that Git ignores"), rebase)
    val status = git("status", "--porcelain=v1", "--untracked-files=all")
    assertTrue(
      status.contains("config/pitest/encoding-pitest-version"),
      "the negated rule did not expose the new provenance to Git",
    )
    assertTrue(
      status.contains("config/pitest/encoding-pitest-toolchain.tsv"),
      "the negated rule did not expose the new toolchain provenance to Git",
    )
  }

  @Test
  fun `clean certification detects record changes hidden by Git index flags`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val recordReadme = File(fixtureDir, "config/pitest/README.md").also { file ->
      file.parentFile.mkdirs()
      file.writeText("# Reviewed mutation rationale\n")
    }
    initializeGitFixture()

    git("update-index", "--assume-unchanged", "config/pitest/README.md")
    recordReadme.writeText("# Locally changed rationale hidden from porcelain status\n")
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "assume-unchanged did not reproduce Git's false-clean content state",
    )
    val changed = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      changed.contains("Git-normalized content differs from captured tree") &&
          changed.contains("config/pitest/README.md"),
      changed,
    )

    recordReadme.writeText("# Reviewed mutation rationale\n")
    git("update-index", "--no-assume-unchanged", "config/pitest/README.md")
    git("update-index", "--skip-worktree", "config/pitest/README.md")
    assertTrue(recordReadme.delete(), "failed to delete the skip-worktree record fixture")
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "skip-worktree did not reproduce Git's false-clean missing-file state",
    )
    val missing = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      missing.contains("missing locally but present in captured tree") &&
          missing.contains("config/pitest/README.md"),
      missing,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "a hidden record change left a certification receipt",
    )
  }

  @Test
  fun `zero-suite certification still binds top-level plugin and Git identity`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-zero-suite-smoke-test"
      """.trimIndent() + "\n",
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n",
    )
    val (commit, tree) = initializeGitFixture()

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()

    assertTrue(receipt.contains("schema\t6\n"), receipt)
    assertTrue(receipt.contains("gitState\tclean\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$commit\n"), receipt)
    assertTrue(receipt.contains("gitTree\t$tree\n"), receipt)
    assertTrue(Regex("(?m)^pluginSha256\\t[0-9a-f]{64}$").containsMatchIn(receipt), receipt)
    assertFalse(receipt.lineSequence().any { it.startsWith("suite\t") }, receipt)
    assertTrue(certified.output.contains("0 suite(s) certified"), certified.output)
  }

  @Test
  fun `PIT evidence creation and removal do not invalidate the configuration cache`() {
    writeFixture()
    // `check` replays every declared corpus. Seed both corpus-backed fixture targets
    // so this test isolates configuration-graph stability instead of failing the
    // deliberately strict generated replay contract.
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val cold = runner("check").build().output
    assertFalse(cold.contains("Reusing configuration cache"), cold)

    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val evidence = reportDir.resolve(".evidence.tsv").apply {
      parentFile.mkdirs()
      writeText("fixture evidence whose contents are irrelevant to the check graph\n")
    }
    val toolchain = reportDir.resolve(".toolchain.tsv").apply {
      writeText("fixture toolchain whose contents are irrelevant to the check graph\n")
    }
    val afterCreation = runner("check").build().output
    assertTrue(
      afterCreation.contains("Reusing configuration cache"),
      "creating PIT evidence selected a different configuration graph:\n$afterCreation",
    )

    assertTrue(evidence.delete(), "fixture could not remove its evidence manifest")
    assertTrue(toolchain.delete(), "fixture could not remove its toolchain manifest")
    val afterRemoval = runner("check").build().output
    assertTrue(
      afterRemoval.contains("Reusing configuration cache"),
      "removing PIT evidence selected a different configuration graph:\n$afterRemoval",
    )
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
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")

    assertTrue(failed.contains("inputs changed after verification"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertFalse(receipt.exists(), "stale-input certification left a passing receipt")
  }

  @Test
  fun `certification refuses suites that observed different project-wide toolchains`() {
    writeFixture(
      buildTail = """
        hardening {
          mutation.register("decoding") {
            targetClasses = listOf("com.example.*")
            targetTests = "com.example.*Test*"
          }
        }
        tasks.named<JavaExec>("pitestDecoding") {
          mainClass = "com.example.FakePit"
          classpath = files(sourceSets["main"].output, layout.projectDirectory.dir("extra-pit-tool"))
        }
      """.trimIndent(),
    )
    File(fixtureDir, "extra-pit-tool").apply { mkdirs() }
      .resolve("fixture-marker.txt").writeText("suite-specific tool input\n")
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner("clean", "hardeningCertify").buildAndFail().output
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")

    assertTrue(failed.contains("suites do not describe one project-wide tree"), failed)
    assertTrue(failed.contains("'encoding'") && failed.contains("'decoding'"), failed)
    assertTrue(failed.contains("toolClasspathSha256"), failed)
    assertTrue(failed.contains("mutationToolchainSha256"), failed)
    assertFalse(receipt.exists(), "mixed-tree certification left a passing receipt")
  }

  @Test
  fun `certification refuses committed records changed after suite verification`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        val acceptedRecordAfterVerify =
          layout.projectDirectory.file("config/pitest/encoding-accepted.csv")
        tasks.register("tamperRecordAfterVerify") {
          mustRunAfter("pitestEncodingVerify")
          doLast {
            acceptedRecordAfterVerify.asFile.apply {
              parentFile.mkdirs()
              writeText("!sava-hardening-baseline-schema,1\n" +
                "com.example.Concurrent,changed,MathMutator,SURVIVED # line 7\n")
            }
          }
        }
        tasks.named("hardeningCertify") {
          dependsOn("tamperRecordAfterVerify")
        }
      """.trimIndent() + "\n",
    )

    val failed = runner("hardeningCertify").buildAndFail().output
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")

    assertTrue(
      failed.contains("committed mutation records changed after successful verification"),
      failed,
    )
    assertFalse(receipt.exists(), "record-tampered certification left a passing receipt")
  }

  @Test
  fun `certification preflight rejects scoped flags before PIT executes`() {
    writeFixture()

    val refused = runner("hardeningCertify", "-PmutateOnly=com.example.Codec").buildAndFail().output
    assertTrue(refused.contains("hardeningCertify is observation-only and full-population"), refused)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "PIT ran before certification preflight refused the scoped flag")

    val isolated = runner("hardeningCertify", "-PisolateMutants").buildAndFail().output
    assertTrue(isolated.contains("incompatible flag(s): -PisolateMutants"), isolated)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "PIT ran before certification preflight refused isolated execution",
    )
  }

  @Test
  fun `diagnostic PIT refuses a certification graph before touching diagnostic output`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val diagnosticReport = File(fixtureDir, "build/reports/pitest-diagnostic/encoding")
    val preserved = diagnosticReport.resolve("preserved.txt").apply {
      parentFile.mkdirs()
      writeText("prior diagnostic\n")
    }
    val failed = runner(
      "hardeningCertify",
      "pitestEncodingDiagnostic",
    ).buildAndFail().output

    assertTrue(
      failed.contains("verbose diagnostics cannot run inside hardeningCertify") &&
          failed.contains("separate Gradle invocations"),
      failed,
    )
    assertEquals(
      "prior diagnostic\n",
      preserved.readText(),
      "certification-graph refusal changed prior diagnostic output",
    )
  }

  @Test
  fun `baseline writers reject isolated execution before PIT or record changes`() {
    writeFixture()
    val baseline = File(fixtureDir, "config/pitest/encoding-accepted.csv")
    val before = "!sava-hardening-baseline-schema,1\n"
    baseline.parentFile.mkdirs()
    baseline.writeText(before)

    val refused = runner(
      "pitestEncodingBaselineRebase",
      "-PisolateMutants",
    ).buildAndFail().output

    assertTrue(
      refused.contains("requires full, unscoped evidence; remove -PisolateMutants"),
      refused,
    )
    assertEquals(before, baseline.readText())
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile,
      "PIT ran before the writer refused isolated execution",
    )
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
    enableFakeArcMutate()

    runner("pitestEncoding").build()
    val evidence = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    assertTrue(evidence.readText().contains("historyAssisted\ttrue"), evidence.readText())

    val throughAbbreviatedAlias = runner("releaseG").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(receipt.isFile, "alias did not produce a certification receipt:\n${throughAbbreviatedAlias.output}")
    assertTrue(receipt.readText().contains("session\t"), receipt.readText())
    assertTrue(evidence.readText().contains("historyAssisted\tfalse"), evidence.readText())

    val excluded = runner("releaseG", "-x", "pitestEncodingVerify").buildAndFail().output
    assertTrue(excluded.contains("task exclusion(s): -x pitestEncodingVerify"), excluded)
    assertFalse(receipt.exists(), "a refused certification left its prior receipt looking current")
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").isFile,
      "a refused certification must retain its invalidation sentinel")

    File(fixtureDir, "fake-pit-mode.txt").writeText("timeout\n")
    val strict = runner("releaseG").buildAndFail().output
    assertTrue(
      strict.contains("physical TIMED_OUT mutant instance(s) across 1 line-less key(s)") &&
          strict.contains("and no audited set"),
      strict,
    )
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
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(receipt.isFile)

    val skipped = runner("releaseGate", "-PskipPit").buildAndFail().output
    assertTrue(
      skipped.contains("without completing PIT in this invocation") ||
          skipped.contains("no PIT execution plus successful verification recorded"),
      skipped)
    assertFalse(receipt.exists(), "failed certification retained a prior receipt")
    assertTrue(File(fixtureDir, ".pitest-history/pitest-certification.running").isFile)

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
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertTrue(File(fixtureDir, ".pitest-history/pitest-certification.running").isFile)
  }

  @Test
  fun `certification refuses a directly selected convergence round two before writing a receipt`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner("hardeningCertify", "pitestEncodingConvergeRound2").buildAndFail().output

    assertTrue(failed.contains("pitestConverge cannot run inside hardeningCertify"), failed)
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertTrue(File(fixtureDir, ".pitest-history/pitest-certification.running").isFile)
  }

  @Test
  fun `certification state is isolated between subprojects`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    enableFakeArcMutate()

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
    assertTrue(File(fixtureDir, "a/.pitest-history/pitest-certification.tsv").isFile)
    assertFalse(File(fixtureDir, "b/.pitest-history/pitest-certification.tsv").exists())
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
  fun `PIT refuses a plugin code path replaced after application`() {
    val privateRepo = fixtureDir.resolve("private-test-repo")
    check(File(savaBuildTestProperty("savaBuild.testRepo")).copyRecursively(privateRepo))
    val fixturePlugin = privateRepo.resolve(
      "software/sava/sava-build/$savaBuildTestRepoVersion/" +
        "sava-build-$savaBuildTestRepoVersion.jar"
    )
    val escapedRepo = privateRepo.absolutePath.replace("\\", "\\\\")
    val privatePluginManagement =
      "pluginManagement { repositories { maven(url = \"$escapedRepo\"); gradlePluginPortal() }; " +
        "resolutionStrategy.eachPlugin { if (requested.id.id.startsWith(\"software.sava.build\")) { " +
        "useModule(\"software.sava:sava-build:$savaBuildTestRepoVersion\") } } }\n" +
        "plugins { id(\"software.sava.build\") version \"$savaBuildTestRepoVersion\" }"
    val escapedPlugin = fixturePlugin.absolutePath.replace("\\", "\\\\")
    writeFixture(
      buildTail =
        """
          tasks.named<JavaExec>("pitestEncoding") {
            environment("FIXTURE_PLUGIN_ARTIFACT", "$escapedPlugin")
          }
        """.trimIndent(),
      pluginManagement = privatePluginManagement,
      projectRepositories = "",
    )
    File(fixtureDir, "fake-pit-mode.txt").writeText("mutate-plugin\n")
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner(
      "hardeningCertify", "--refresh-dependencies", "-PsavaBuildLocalRepo=$privateRepo",
    ).buildAndFail().output
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")

    assertTrue(failed.contains("local plugin artifact changed after plugin application"), failed)
    assertTrue(failed.contains("refusing evidence from mixed plugin bytes"), failed)
    assertFalse(reportDir.resolve(".evidence.tsv").exists(), "mixed plugin bytes committed evidence")
    assertTrue(reportDir.resolve(".running").isFile, "mixed plugin bytes exposed the report")
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists(),
      "mixed plugin bytes committed a certification receipt",
    )
  }

  @Test
  fun `malformed attempt state is refused before any report leaf is changed`() {
    writeFixture()
    val reportDir = File(fixtureDir, "build/reports/pitest/encoding")
    val staleCsv = reportDir.resolve("mutations.csv").apply {
      parentFile.mkdirs()
      writeText("stale report\n")
    }
    val staleLog = reportDir.resolve("pitest.stdout.log").apply { writeText("stale log\n") }
    reportDir.resolve(".evidence.tsv/blocked").apply {
      parentFile.mkdirs()
      writeText("not a replaceable evidence file")
    }

    val failed = runner("pitestEncoding").buildAndFail().output

    assertFalse(
      reportDir.resolve(".running").exists(),
      "pre-attempt validation partially started a report lifecycle:\n$failed",
    )
    assertTrue(
      reportDir.resolve(".evidence.tsv/blocked").isFile,
      "pre-attempt validation changed the malformed leaf it refused",
    )
    assertEquals("stale report\n", staleCsv.readText(), "atomic validation deleted the old CSV")
    assertEquals("stale log\n", staleLog.readText(), "atomic validation truncated the old log")
    assertFalse(reportDir.resolve("arguments.txt").exists(), "PIT ran after pre-attempt refusal")
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
    val scenariosRoot = fixtureDir
    fun writeScenario(name: String, moneyMath: Boolean, declineLines: String = "") {
      fixtureDir = scenariosRoot.resolve(name).apply { mkdirs() }
      enableTestKitConfigurationCache(fixtureDir)
      writeFixture(moneyMath = moneyMath, declineLines = declineLines)
    }

    writeScenario("advised", moneyMath = true)
    val advised = runner("pitestEncoding").build().output
    assertTrue(advised.contains("call BigDecimal arithmetic"), "advice did not fire:\n" + advised)
    assertTrue(advised.contains("call BigInteger arithmetic"), advised)
    assertTrue(advised.contains("declineMutator("), "the advice must name its own escape hatch:\n" + advised)

    // Recorded with its measurement: quiet, and only for the mutator it names.
    writeScenario(
      "declined",
      moneyMath = true,
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "trialed 2026-07-25: generated 0")""",
    )
    runner("pitestEncoding").build()
    val declined = runner("pitestEncoding").build().output
    assertTrue(declined.contains("Configuration cache entry reused."), declined)
    assertFalse(declined.contains("call BigDecimal arithmetic"), "the decline did not reach the advice:\n" + declined)
    assertTrue(
      declined.contains("call BigInteger arithmetic"),
      "a decline must suppress its own mutator only:\n" + declined
    )
    assertFalse(declined.contains("is stale"), "a decline with a live subject is not stale:\n" + declined)

    // An argument-free decline suppresses nothing and reports itself, so it cannot be
    // used to quiet a warning nobody investigated.
    writeScenario(
      "blank",
      moneyMath = true,
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "   ")""",
    )
    val blank = runner("pitestEncoding").build().output
    assertTrue(blank.contains("call BigDecimal arithmetic"), blank)
    assertTrue(blank.contains("the recorded decline of EXPERIMENTAL_BIG_DECIMAL is stale"), blank)
    assertTrue(blank.contains("carries no reason"), blank)

    // The subject disappears (no money math left) and the decline says so rather than
    // sitting on as a settled decision about deleted code.
    writeScenario(
      "subject-gone",
      moneyMath = false,
      declineLines = """declineMutator("EXPERIMENTAL_BIG_DECIMAL", "trialed 2026-07-25: generated 0")""",
    )
    val subjectGone = runner("pitestEncoding").build().output
    assertTrue(subjectGone.contains("the recorded decline of EXPERIMENTAL_BIG_DECIMAL is stale"), subjectGone)
    assertTrue(subjectGone.contains("no longer suppresses anything"), subjectGone)
  }

  @Test
  fun `coverage-phase cost advice is quiet below its threshold and inclusive at the boundary`() {
    writeFixture()
    val mode = File(fixtureDir, "fake-pit-mode.txt")

    mode.writeText("below-cost-threshold\n")
    val below = runner("pitestEncoding").build().output
    assertFalse(
      below.contains("slowest PIT coverage-phase test"),
      "a 249 ms coverage-phase test crossed the 250 ms advisory threshold:\n$below",
    )

    mode.writeText("at-cost-threshold\n")
    val boundary = runner("pitestEncoding").build().output
    assertTrue(boundary.contains("Configuration cache entry reused."), boundary)
    assertTrue(
      boundary.contains(
        "pitest 'encoding': slowest PIT coverage-phase test " +
            "'[engine:junit-jupiter]/[class:com.example.CodecTest]/[method:roundTrip()]' " +
            "took 250 ms",
      ) && boundary.contains("hardening: 1 advisory finding(s) across 1 scope(s)"),
      "the inclusive 250 ms boundary did not produce exactly one advisory:\n$boundary",
    )
  }

  @Test
  fun `a failing PIT exit still flushes the summary and buffered tail, and cannot rewrite the marker`() {
    writeFixture()
    // leave a scoped marker behind, then fail an unscoped run: the deferred exit must
    // re-raise after the filters close but before the marker update
    runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    val marker = File(fixtureDir, "build/reports/pitest-scoped/encoding/.scoped")
    assertTrue(marker.isFile, "precondition: scoped marker missing")

    File(fixtureDir, "fake-pit-mode.txt").writeText("slow-fail")
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
    assertFalse(
      failed.output.contains("slowest PIT coverage-phase test"),
      "a failed PIT attempt emitted cost advice from incomplete evidence:\n${failed.output}",
    )
    val failedReport = File(fixtureDir, "build/reports/pitest/encoding")
    assertTrue(
      failed.output.contains("failed attempt raw logs") &&
          failed.output.contains(failedReport.resolve("pitest.stdout.log").absolutePath) &&
          failed.output.contains(failedReport.resolve("pitest.stderr.log").absolutePath),
      failed.output,
    )
    assertEquals(
      3,
      occurrences(failedReport.resolve("pitest.stdout.log").readText(), "common noise"),
    )
    assertEquals(
      2,
      occurrences(failedReport.resolve("pitest.stderr.log").readText(), "common noise"),
    )
    assertTrue(
      failedReport.resolve("pitest.stderr.log").readText().endsWith("partial tail before crash"),
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
  fun `minimize cannot commit a failed merge when JavaExec ignores its exit value`() {
    writeFixture(buildTail = """
      tasks.named<JavaExec>("fuzzCodecMinimize") {
        isIgnoreExitValue = true
      }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "fake-merge-mode.txt").writeText("fail\n")
    val corpus = File(fixtureDir, "corpus/codec")

    val failed = runner("fuzzCodecMinimize").buildAndFail()

    assertTrue(failed.output.contains("non-zero exit value 5"), failed.output)
    assertEquals("alpha", corpus.resolve("seedA").readText())
    assertEquals("beta-longer", corpus.resolve("seedB").readText())
    assertFalse(corpus.resolve("9f8e7d").exists(), "a failed merge committed its partial staging")
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

  @Test
  fun `fuzz targets honor configurable build-wide slots across projects with cache reuse`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "parallel-fuzz-lock-smoke-test"
        include("b")
      """.trimIndent() + "\n"
    )
    fun writeProject(projectDir: File, targets: List<String>) {
      projectDir.mkdirs()
      val projectLabel = if (projectDir == fixtureDir) "root" else projectDir.name
      val registrations = targets.joinToString("\n") { target ->
        """
          fuzz.register("$target") {
            targetClass = "com.example.${target.replaceFirstChar(Char::uppercase)}Fuzz"
          }
        """.trimIndent()
      }
      val taskWiring = targets.joinToString("\n") { target ->
        val taskName = "fuzz" + target.replaceFirstChar(Char::uppercase)
        """
          tasks.named<JavaExec>("$taskName") {
            mainClass = "com.example.FakeParallelFuzz"
            classpath = sourceSets["main"].output
            jvmArgs = listOf(
              "-DparallelFuzzSlotDir=${fixtureDir.resolve("parallel-fuzz-slots").absolutePath}",
              "-DparallelFuzzName=$projectLabel:$taskName",
              "-DparallelFuzzCapacity=" +
                  providers.gradleProperty("maxParallelFuzzTargets").orElse("1").get(),
            )
          }
        """.trimIndent()
      }
      File(projectDir, "build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          repositories { mavenCentral() }

          hardening {
            $registrations
          }

          $taskWiring
        """.trimIndent() + "\n"
      )
      val source = File(projectDir, "src/main/java/com/example/FakeParallelFuzz.java")
      source.parentFile.mkdirs()
      source.writeText(
        """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardOpenOption;

          public final class FakeParallelFuzz {
            public static void main(String[] args) throws Exception {
              Path stateDir = Path.of(System.getProperty("parallelFuzzSlotDir"));
              Path events = stateDir.resolveSibling("parallel-fuzz.events");
              Path overlapSeen = stateDir.resolveSibling("parallel-fuzz-overlap-seen");
              String name = System.getProperty("parallelFuzzName");
              int capacity = Integer.parseInt(System.getProperty("parallelFuzzCapacity"));
              Files.createDirectories(stateDir);
              Path counterLock = stateDir.resolve("counter.lock");
              Path counter = stateDir.resolve("active");
              int activeAfterStart;
              try (var channel = java.nio.channels.FileChannel.open(counterLock,
                  StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                  var ignored = channel.lock()) {
                int active = Files.exists(counter)
                    ? Integer.parseInt(Files.readString(counter).trim()) : 0;
                activeAfterStart = active + 1;
                Files.writeString(counter, Integer.toString(activeAfterStart),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                if (activeAfterStart >= 2) {
                  Files.writeString(overlapSeen, "observed\n",
                      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
              }
              Files.writeString(events, "start " + name + "\n",
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              try {
                if (activeAfterStart > capacity) {
                  throw new IllegalStateException(
                      "fuzz concurrency exceeded " + capacity + ": " + activeAfterStart);
                }
                Thread.sleep(600);
              } finally {
                Files.writeString(events, "end " + name + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                try (var channel = java.nio.channels.FileChannel.open(counterLock,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                  int active = Integer.parseInt(Files.readString(counter).trim());
                  Files.writeString(counter, Integer.toString(active - 1),
                      StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
              }
              System.err.println("Done 17 runs in 1 second(s)");
            }
          }
        """.trimIndent() + "\n"
      )
    }
    writeProject(fixtureDir, listOf("alpha", "beta"))
    writeProject(File(fixtureDir, "b"), listOf("gamma"))

    fun runAndAssert(capacity: Int, expectReuse: Boolean) {
      val eventsFile = File(fixtureDir, "parallel-fuzz.events")
      if (eventsFile.exists()) assertTrue(eventsFile.delete(), "could not reset fuzz event log")
      val overlapSeen = File(fixtureDir, "parallel-fuzz-overlap-seen")
      if (overlapSeen.exists()) assertTrue(overlapSeen.delete(), "could not reset overlap marker")
      File(fixtureDir, "parallel-fuzz-slots").deleteRecursively()
      val result = runner(
        ":fuzzAlpha",
        ":fuzzBeta",
        ":b:fuzzGamma",
        "-PmaxParallelFuzzTargets=$capacity",
        "--parallel",
        "--max-workers=4",
      ).build()

      assertFalse(result.output.contains("fuzz concurrency exceeded"), result.output)
      if (expectReuse) assertTrue(result.output.contains("Configuration cache entry reused."), result.output)
      val events = eventsFile.readLines()
      assertEquals(6, events.size, "three fuzz tasks must record complete intervals: $events")
      var active = 0
      var observedMaximum = 0
      events.forEach { event ->
        if (event.startsWith("start ")) {
          active++
          observedMaximum = maxOf(observedMaximum, active)
        } else {
          assertTrue(event.startsWith("end "), events.toString())
          active--
        }
        assertTrue(active in 0..capacity, "configured fuzz capacity $capacity was exceeded: $events")
      }
      assertEquals(0, active, events.toString())
      assertEquals(capacity, observedMaximum, "configured fuzz capacity was not observed: $events")
      assertEquals(capacity > 1, overlapSeen.isFile, "overlap marker disagreed with capacity $capacity")
      assertEquals(
        setOf("root:fuzzAlpha", "root:fuzzBeta", "b:fuzzGamma"),
        events.filter { it.startsWith("start ") }.map { it.removePrefix("start ") }.toSet(),
      )
    }

    runAndAssert(capacity = 1, expectReuse = false)
    runAndAssert(capacity = 2, expectReuse = false)
    runAndAssert(capacity = 2, expectReuse = true)
  }

  @Test
  fun `typed PIT tasks share the build-wide execution lock across parallel projects`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "parallel-pitest-lock-smoke-test"
        include("a", "b")
      """.trimIndent() + "\n"
    )
    listOf("a", "b").forEach { projectName ->
      val projectDir = File(fixtureDir, projectName).apply { mkdirs() }
      File(projectDir, "build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.hardening")
          }

          repositories { mavenCentral() }

          hardening {
            mutation.register("encoding") {
              targetClasses = listOf("com.example.*")
              targetTests = "com.example.*Test*"
            }
          }

          tasks.named<JavaExec>("pitestEncoding") {
            mainClass = "com.example.FakePit"
            classpath = sourceSets["main"].output
            jvmArgs(
              "-DparallelPitLock=${fixtureDir.resolve("parallel-pit.lock").absolutePath}",
              "-DparallelPitProject=$projectName",
            )
          }
        """.trimIndent() + "\n"
      )
      val source = File(projectDir, "src/main/java/com/example/FakePit.java")
      source.parentFile.mkdirs()
      source.writeText(
        """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardOpenOption;

          public final class FakePit {
            public static void main(String[] args) throws Exception {
              Path lock = Path.of(System.getProperty("parallelPitLock"));
              Path events = lock.resolveSibling("parallel-pit.events");
              String project = System.getProperty("parallelPitProject");
              try {
                Files.writeString(lock, project, StandardOpenOption.CREATE_NEW);
              } catch (java.nio.file.FileAlreadyExistsException overlap) {
                throw new IllegalStateException("parallel PIT overlap in " + project, overlap);
              }
              Files.writeString(events, "start " + project + " " + System.nanoTime() + "\n",
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              try {
                Thread.sleep(600);
                String reportDir = null;
                for (String arg : args) {
                  if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
                }
                Path report = Path.of(reportDir);
                Files.createDirectories(report);
                Files.writeString(report.resolve("mutations.csv"),
                    "FakePit.java,com.example.FakePit," +
                    "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                    "main,12,KILLED,com.example.FakePitTest\n");
              } finally {
                Files.writeString(events, "end " + project + " " + System.nanoTime() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.deleteIfExists(lock);
              }
            }
          }
        """.trimIndent() + "\n"
      )
    }

    val result = runner(
      ":a:pitestEncoding",
      ":b:pitestEncoding",
      "--parallel",
      "--max-workers=4",
    ).build()

    assertFalse(result.output.contains("parallel PIT overlap"), result.output)
    val events = File(fixtureDir, "parallel-pit.events").readLines()
    assertEquals(4, events.size, "each typed task must record one non-overlapping interval: $events")
    assertTrue(events[0].startsWith("start "), events.toString())
    assertTrue(events[1].startsWith("end "), events.toString())
    assertTrue(events[2].startsWith("start "), events.toString())
    assertTrue(events[3].startsWith("end "), events.toString())
  }

  @Test
  fun `a test-selection record reaches PIT, and an unargued one fails before it can`() {
    // Selection itself is proved against real PIT in HardeningTestSelectionFunctionalTest.
    // What this owns is the plumbing either side of it: that a record travels task
    // configuration and the configuration cache onto the command line, and that a
    // record without an argument stops the build rather than logging about it.
    val scenariosRoot = fixtureDir
    fun writeScenario(name: String, declineLines: String = "") {
      fixtureDir = scenariosRoot.resolve(name).apply { mkdirs() }
      enableTestKitConfigurationCache(fixtureDir)
      writeFixture(declineLines = declineLines)
    }

    // Nothing recorded: the argument must be absent entirely, not empty.
    writeScenario("none")
    val none = runner("pitestEncoding", "--info").build().output
    assertFalse(none.contains("--excludedTestClasses="), "an empty removal reached PIT:\n" + none)

    // Recorded with its reason, and surviving the configuration cache.
    writeScenario(
      "removed",
      declineLines = """excludeTestClass("com.example.ScriptTests", "spawns a subprocess per test")""",
    )
    runner("pitestEncoding").build()
    val removed = runner("pitestEncoding", "--info").build().output
    assertTrue(removed.contains("Configuration cache entry reused."), removed)
    assertTrue(
      removed.contains("--excludedTestClasses=com.example.ScriptTests"),
      "the record did not reach PIT's command line:\n" + removed,
    )

    // Several records reach PIT as one sorted, comma-joined argument, so what is
    // sent depends on the set rather than on the order they were registered in.
    writeScenario(
      "several",
      declineLines =
        """excludeTestClass("com.example.ScriptTests", "spawns a subprocess per test")
           excludeTestClass("com.example.LiveTests", "needs live credentials")""",
    )
    val several = runner("pitestEncoding", "--info").build().output
    assertTrue(
      several.contains("--excludedTestClasses=com.example.LiveTests,com.example.ScriptTests"),
      "records did not reach PIT as one sorted argument:\n" + several,
    )

    // No reason: the build stops. An advisory cannot carry this, because advisories
    // never fail a build by design — a reason checked only there is not required.
    writeScenario("blank", declineLines = """excludeTestClass("com.example.ScriptTests", "   ")""")
    val blank = runner("pitestEncoding").buildAndFail().output
    assertTrue(blank.contains("excludeTestClass record(s) carry no reason"), blank)
    assertTrue(blank.contains("com.example.ScriptTests"), blank)
    // PIT records every argument it was handed on startup, so the absence of that
    // file is the proof: an argument missing from non---info output would show the
    // same thing whether PIT never ran or merely ran unlogged.
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/arguments.txt").exists(),
      "the run started despite an unargued record:\n" + blank,
    )
  }

  @Test
  fun `a test-selection glob cannot be put on the task behind the reason requirement`() {
    // The reason is enforced where the DSL's records become globs, so the task
    // property is the way around it: set it late, skip the ratchet, and a green run
    // and its evidence exist with nothing anywhere saying why those tests were not
    // allowed to kill anything. The property is locked for that reason, and this is
    // what stops the lock being dropped as redundant.
    writeFixture(
      buildTail = """
        tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
          excludedTestClasses.add("com.example.ScriptTests")
        }
      """.trimIndent(),
    )

    val refused = runner("pitestEncoding").buildAndFail().output
    assertTrue(
      refused.contains("cannot be changed any further"),
      "a glob was accepted straight onto the task, bypassing the reason:\n" + refused,
    )
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/arguments.txt").exists(),
      "the run started with an unargued glob:\n" + refused,
    )
  }

  @Test
  fun `a test-selection glob cannot be put on the validator to make stale evidence pass`() {
    // The run task's lock has its own test. This one is the spec side, and the attack
    // is narrower than "set a spec property": setting only the verify spec makes
    // verification fail on a configuration mismatch anyway. The bypass that would
    // actually work is on the validator — record a report under one record set,
    // change the suite, then put the OLD value back on
    // pitestEncodingEvidenceValidate so the recorded configuration matches again and
    // a stale report is accepted as current. Without the lock that succeeds.
    writeFixture(
      declineLines = """
        if (providers.gradleProperty("excludeScriptTests").isPresent) {
          excludeTestClass("com.example.ScriptTests", "spawns a subprocess per test")
        }
      """.trimIndent(),
      buildTail = """
        if (providers.gradleProperty("forgeValidatorEvidence").isPresent) {
          tasks.named<software.sava.build.hardening.task.PitestEvidenceValidationTask>(
              "pitestEncodingEvidenceValidate") {
            evidence.excludedTestClasses.set(emptyList<String>())
          }
        }
      """.trimIndent(),
    )

    // A report recorded with no record at all.
    runner("pitestEncoding").build()

    // The suite now declares one, so the report on disk is stale. Re-pointing the
    // validator at the old value is what the lock has to refuse.
    val forged = runner(
      "pitestEncodingEvidenceValidate",
      "-PexcludeScriptTests=true",
      "-PforgeValidatorEvidence=true",
    ).buildAndFail().output
    assertTrue(
      forged.contains("cannot be changed any further"),
      "the validator accepted a hand-set configuration, which is how a stale report " +
        "is made to look current:\n" + forged,
    )

    // Control: without the override the same stale report is refused on its
    // configuration, so the run above failed for the lock and not for that.
    val honest = runner("pitestEncodingEvidenceValidate", "-PexcludeScriptTests=true")
      .buildAndFail().output
    assertTrue(honest.contains("configurationSha256: recorded="), honest)
  }

  @Test
  fun `a test-selection record is part of the recorded configuration identity`() {
    // Everything varies through Gradle properties so the build script's bytes never
    // change between runs. build.gradle.kts is itself fingerprinted into
    // sourceSha256, so a test that edited it to add a record would invalidate the
    // report whatever the exclusion did, and would prove nothing about this setting.
    writeFixture(
      declineLines = """
        if (providers.gradleProperty("excludeScriptTests").isPresent) {
          excludeTestClass(
            "com.example.ScriptTests",
            providers.gradleProperty("exclusionReason").getOrElse("spawns a subprocess per test"),
          )
        }
        providers.gradleProperty("commaGlob").orNull?.let { excludeTestClass(it, "malformed on purpose") }
      """.trimIndent(),
    )
    val evidenceFile = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    fun runPitest(vararg args: String): PitestEvidence {
      runner("pitestEncoding", *args).build()
      return PitestEvidence.parse(evidenceFile.readText())
    }

    val none = runPitest()
    val recorded = runPitest("-PexcludeScriptTests=true")

    assertNotEquals(
      none.configurationSha256, recorded.configurationSha256,
      "a removal that changes which tests can kill a mutant left the configuration identical",
    )
    // Nothing else may have moved, or the assertion above is about the wrong field.
    assertEquals(none.sourceSha256, recorded.sourceSha256, "the sources changed between runs")
    assertEquals(none.classesSha256, recorded.classesSha256, "the classes changed between runs")
    assertEquals(none.classpathSha256, recorded.classpathSha256, "the classpath changed between runs")

    // The reason is not part of what PIT did, so it is not part of the identity:
    // rewording one under the same glob must not restart a certification streak.
    val reworded = runPitest("-PexcludeScriptTests=true", "-PexclusionReason=restated, same removal")
    assertEquals(
      recorded.configurationSha256, reworded.configurationSha256,
      "rewording a record's reason changed the recorded configuration identity",
    )

    // Dropping the record returns to the identity the suite had before it existed —
    // byte for byte, which is what makes the empty case compatible with a report
    // recorded before this setting was available.
    assertEquals(
      none.configurationSha256, runPitest().configurationSha256,
      "removing the record did not restore the pre-record configuration identity",
    )

    // The validator's half, which hashing alone cannot show. The report on disk was
    // made without the record; the build now declares it, and nothing has re-run PIT
    // or touched a file. Revalidating must refuse the report, and must refuse it for
    // the configuration rather than for anything that came along with the edit.
    val stale = runner("pitestEncodingEvidenceValidate", "-PexcludeScriptTests=true")
      .buildAndFail().output
    assertTrue(
      stale.contains("configurationSha256: recorded="),
      "a report made under a different record set was not refused on its configuration:\n" + stale,
    )
    assertFalse(
      stale.contains("sourceSha256: recorded="),
      "the sources moved too, so this proves nothing about the exclusion:\n" + stale,
    )

    // A malformed glob has to be refused on the evidence path too, not only where
    // the command line is built. Records 'a' and 'b' and the single record 'a,b'
    // render to the same canonical configuration line, so a comma reaching the hash
    // would let two different suite configurations share one identity — and this
    // task never assembles a PIT command, so the command-boundary check cannot
    // catch it. Without the shared-provider validation this run goes green.
    val comma = runner("pitestEncodingEvidenceValidate", "-PcommaGlob=com.example.A,com.example.B")
      .buildAndFail().output
    assertTrue(
      comma.contains("cannot contain a comma"),
      "an evidence-only task accepted a glob that corrupts the configuration text:\n" + comma,
    )

    // And a newline, which forges a whole line of that text rather than one entry
    // of one line: a record ending 'y\nexcludedTestClasses=...' renders the same as
    // a targetTests that opened the line and a separate record that closed it, so
    // two suites that hand PIT different arguments would share one identity.
    val newline = runner(
      "pitestEncodingEvidenceValidate", "-PcommaGlob=y\nexcludedTestClasses=com.example.Z")
      .buildAndFail().output
    assertTrue(
      newline.contains("cannot contain a line break or NUL"),
      "an evidence-only task accepted a glob that can forge a configuration line:\n" + newline,
    )
  }
}
