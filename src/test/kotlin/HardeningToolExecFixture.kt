import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Shared fake-tool fixture for the execution, evidence, and certification tests.
 * Each test inherits a fresh TempDir and enables the configuration cache before
 * writing its project; no tool process or project state is shared between tests.
 */
abstract class HardeningToolExecFixture {

  @TempDir
  lateinit var fixtureDir: File

  protected val certificationSuiteColumns = listOf(
    "name",
    "invocation",
    "reportSha256",
    "sourceSha256",
    "classesSha256",
    "classpathSha256",
    "configurationSha256",
    "pitestVersion",
    "pluginSha256",
    "toolClasspathSha256",
    "mutationToolchainSha256",
    "recordInputsSha256",
    "recordPitestVersion",
    "recordMutationToolchainSha256",
  )

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
  protected fun writeFixture(
    moneyMath: Boolean = false,
    declineLines: String = "",
    buildTail: String = "",
    pluginManagement: String = savaBuildPluginManagement,
    projectRepositories: String = "repositories { mavenCentral() }",
    settingsTail: String = "",
  ) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $pluginManagement

        rootProject.name = "hardening-tool-exec-smoke-test"

$settingsTail
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
          testImplementation("org.junit.jupiter:junit-jupiter:${savaBuildTestVersion("junit")}")
          testRuntimeOnly("org.junit.platform:junit-platform-launcher:${savaBuildTestVersion("junit")}")
        }

        tasks.test {
          useJUnitPlatform()
        }

        hardening {
          // No bytecode override: the recompiles follow this generic fixture's TestKit
          // daemon, the JVM these tests run on (Java 25 too since the daemon pin went, so
          // they do not tell the convention from a literal release).
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
        import java.nio.file.StandardCopyOption;
        import java.nio.file.StandardOpenOption;

        public final class FakePit {
          public static void main(String[] args) throws Exception {
            String mode = "ok";
            Path modeFile = Path.of("fake-pit-mode.txt");
            if (Files.exists(modeFile)) mode = Files.readString(modeFile).trim();
            // The 'early-death' and 'coverage-death' modes are a PIT whose minion never
            // reaches it: on every run with the '-always' suffix, on the first run only
            // with '-then-ok'. Runs are counted under build/, which no evidence reads.
            boolean countsAttempts = mode.startsWith("early-death") || mode.startsWith("coverage-death");
            int attemptNumber = 1;
            Path attempts = Path.of("build/fake-pit-attempts");
            if (countsAttempts) {
              Files.createDirectories(attempts);
              Path count = attempts.resolve("count");
              if (Files.exists(count)) attemptNumber = Integer.parseInt(Files.readString(count).trim()) + 1;
              Files.writeString(count, Integer.toString(attemptNumber));
            }
            boolean minionDies = countsAttempts && (mode.endsWith("-always") || attemptNumber == 1);
            if (mode.equals("below-cost-threshold") || mode.equals("at-cost-threshold") ||
                mode.equals("slow-fail") || mode.equals("unattributed-slow-test")) {
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
            if (countsAttempts) {
              // What each run was given: its arguments, the invocation the task opened for
              // it, and the history file as it found it, which the mutation phase of a
              // licensed run then rewrites as ArcMutate does.
              Files.writeString(attempts.resolve("arguments-" + attemptNumber + ".txt"), String.join("\n", args) + "\n");
              Path invocation = dir.resolve(".evidence-invocation");
              if (Files.exists(invocation)) {
                Files.copy(invocation, attempts.resolve("invocation-" + attemptNumber + ".txt"), StandardCopyOption.REPLACE_EXISTING);
              }
              String historyInput = null;
              String historyOutput = null;
              for (String arg : args) {
                if (arg.startsWith("--historyInputLocation=")) historyInput = arg.substring("--historyInputLocation=".length());
                if (arg.startsWith("--historyOutputLocation=")) historyOutput = arg.substring("--historyOutputLocation=".length());
              }
              Files.writeString(attempts.resolve("history-read-" + attemptNumber + ".txt"),
                  historyInput == null ? "<none>" : Files.readString(Path.of(historyInput)));
              // Two things that stop a repeat between the attempts: a source the recompile
              // read changes, or the place the first attempt's report is kept is not a file.
              if (attemptNumber == 1 && mode.contains("-edits-input")) {
                Files.writeString(Path.of("src/main/java/com/example/FakePit.java"),
                    "\n// changed while PIT was running\n", StandardOpenOption.APPEND);
              }
              if (attemptNumber == 1 && mode.contains("-blocks-keep-file")) {
                Files.createDirectories(Path.of("build/reports/pitest-rerun/encoding/mutations.csv"));
              }
              // The directory itself cannot be made: its parent is a file.
              if (attemptNumber == 1 && mode.contains("-blocks-keep-directory")) {
                Files.writeString(Path.of("build/reports/pitest-rerun"), "not a directory");
              }
              // A fixture's build script refuses to start the process once this file exists.
              if (attemptNumber == 1 && mode.contains("-refuses-relaunch")) {
                Files.writeString(attempts.resolve("refuse-launch"), "");
              }
              if (mode.startsWith("coverage-death") && minionDies) {
                System.err.println("9:41:13 AM PIT >> INFO : MINION : 9:41:13 AM PIT >> SEVERE : Error calculating coverage. Process will exit.");
                System.err.println("9:41:13 AM PIT >> INFO : MINION : org.pitest.util.PitError: Read timed out");
                System.err.println("9:41:13 AM PIT >> INFO : MINION : Caused by: java.net.SocketTimeoutException: Read timed out");
                System.err.println("9:41:28 AM PIT >> SEVERE : Coverage generator Minion exited abnormally due to MINION_DIED");
                System.err.println("Exception in thread \"main\" org.pitest.util.PitError: Coverage generation minion exited abnormally! (MINION_DIED)");
                System.exit(1);
              }
              if (historyOutput != null) Files.writeString(Path.of(historyOutput), "written by run " + attemptNumber);
              if (minionDies) {
                System.err.println("5:56:33 PM PIT >> SEVERE : Minion did not start or died during analysis. This may indicate an issue in your environment such as insufficient memory.");
                System.err.println("5:56:33 PM PIT >> WARNING : Minion exited abnormally due to RUN_ERROR");
              }
            }
            if (mode.equals("zero-fire")) {
              System.exit(2);
            }
            if (mode.equals("fail-before-report")) {
              System.err.print("failed before report");
              System.exit(4);
            }
            String status = mode.equals("timeout") ? "TIMED_OUT" :
                (mode.equals("survive") ? "SURVIVED" :
                    (mode.equals("run-error") || minionDies ? "RUN_ERROR" : "KILLED"));
            String killer = mode.equals("below-cost-threshold") || mode.equals("at-cost-threshold")
                ? "com.example.CodecTest.[engine:junit-jupiter]/[class:com.example.CodecTest]/[method:roundTrip()]"
                : "com.example.CodecTest";
            Files.writeString(dir.resolve("mutations.csv"),
                "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12," +
                    status + "," + killer + "\n");
            if (mode.equals("mutate-plugin")) {
              Files.writeString(Path.of(System.getenv("FIXTURE_PLUGIN_ARTIFACT")),
                  "replaced while PIT was running",
                  StandardOpenOption.APPEND);
            }
            if (mode.equals("mutate-input")) {
              Files.writeString(Path.of("src/main/java/com/example/FakePit.java"),
                  "\n// changed while PIT was running\n", StandardOpenOption.APPEND);
            }
            if (mode.equals("withdraw-recompile-stamp")) {
              // Another invocation's compile withdrawing the stamp mid-run, with every
              // evidence input left as it was.
              Files.deleteIfExists(Path.of("build/mutation-recompile/sources.sha256"));
            }
            if (mode.equals("tamper-certification-sentinel")) {
              Files.writeString(
                  Path.of(".pitest-history/pitest-certification.running"), "tampered\n");
            }
            if (mode.equals("delete-certification-sentinel")) {
              Files.deleteIfExists(
                  Path.of(".pitest-history/pitest-certification.running"));
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
            System.err.println("#1\tINITED cov: 1 ft: 1");
            System.err.println("#2\tNEW cov: 2 ft: 2");
            System.err.println("#3\tpulse  cov: 2 ft: 2");
            System.err.println("#4\tREDUCE cov: 2 ft: 2");
            Path modeFile = Path.of("fake-fuzz-mode.txt");
            String mode = Files.exists(modeFile) ? Files.readString(modeFile).trim() : "ok";
            if (mode.equals("delete-fuzz-sentinel")) {
              Files.deleteIfExists(Path.of(".pitest-history/local-fuzz.running"));
            }
            if (mode.equals("tamper-fuzz-sentinel")) {
              Files.writeString(Path.of(".pitest-history/local-fuzz.running"), "tampered\n");
            }
            if (mode.equals("change-fuzz-source")) {
              Files.writeString(Path.of("src/main/java/com/example/FakeFuzz.java"),
                  "\n// changed during fuzz execution\n", java.nio.file.StandardOpenOption.APPEND);
            }
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
            System.err.println("#" + executions + "\tDONE cov: 2 ft: 2");
            System.err.println("Done " + executions + " runs in 1 second(s)");
          }
        }
      """.trimIndent() + "\n"
    )
  }

  protected fun enableFakeArcMutate() {
    File(fixtureDir, "arcmutate-licence.txt")
      .writeText("expires=31/12/2999\ntype=OSSS\nfixture=licence-marker\n")
    File(
      fixtureDir,
      "src/main/resources/META-INF/maven/com.arcmutate/base/pom.properties",
    ).apply {
      parentFile.mkdirs()
      writeText("groupId=com.arcmutate\nartifactId=base\nversion=1.7.3\n")
    }
  }

  protected fun writeSeedCorpus() {
    val corpus = File(fixtureDir, "corpus/codec").apply { mkdirs() }
    corpus.resolve("seedA").writeText("alpha")
    corpus.resolve("seedB").writeText("beta-longer")
  }

  protected fun initializeGitFixture(extraIgnoreRules: List<String> = emptyList()): Pair<String, String> {
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

  protected fun git(vararg arguments: String): String = git(fixtureDir, *arguments)

  protected fun git(directory: File, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git", "-C", directory.absolutePath) + arguments)
      .redirectErrorStream(true)
      .apply {
        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
        environment()["GIT_TERMINAL_PROMPT"] = "0"
      }
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(
      0,
      process.waitFor(),
      "git -C ${directory.absolutePath} ${arguments.joinToString(" ")} failed:\n$output",
    )
    return output.trim()
  }

  /**
   * Every invocation here runs with the configuration cache on, because consumers do
   * and because these fixtures execute real tool tasks. Two defect
   * shapes hide from a class that omits the flag, and both have shipped: an
   * execution-time lambda that reaches a script-level declaration cannot be
   * serialized, and 'Task.project' read from a task action is refused outright.
   * Neither is scoped to the offending task — they fail the consumer's whole build,
   * and a green run without the flag says nothing about either.
   */
  protected fun runner(vararg args: String): FixtureRunner = savaBuildRunner(fixtureDir, *args, "--configuration-cache", "--stacktrace")

  protected fun runnerWithoutConfigurationCache(vararg args: String): FixtureRunner =
    savaBuildRunner(fixtureDir, *args, "--no-configuration-cache", "--stacktrace")

  protected fun occurrences(haystack: String, needle: String) = haystack.split(needle).size - 1

  protected fun assertProjectAtomicCertificationRetry(
    output: String,
    taskPath: String = ":hardeningCertify",
  ) {
    assertTrue(
      output.contains("run $taskPath in a new Gradle invocation") &&
          output.contains("receipt is project-atomic") &&
          output.contains("every suite in this project re-executes in that invocation") &&
          output.contains("completed receipts from other projects remain independent"),
      output,
    )
    assertFalse(
      output.contains("run the full suite in this certification invocation"),
      "certification refusal recommended repairing one suite inside its failed invocation:\n$output",
    )
  }
}
