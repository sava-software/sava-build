import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Synthetic PIT reports and baseline workflows shared by the ratchet feature tests.
 * JUnit initializes the inherited temporary directory and configuration cache setup
 * for each test instance.
 */
abstract class HardeningRatchetFunctionalFixture {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  protected fun writeFixture(
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

  protected fun writeReport(csvRows: List<String>, xmlMutations: String) {
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

  protected fun baselineFile() = File(fixtureDir, "config/pitest/encoding-accepted.csv")

  protected fun writeProductionClassSource(binaryName: String) {
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

  protected fun runner(vararg args: String): GradleRunner = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments(*args, "--no-watch-fs", "--stacktrace")

  /**
   * Most transition tests exercise row allocation, not legacy adoption. Bind an
   * existing fixture record through the public safe migration path first, using an
   * empty observation so rebase cannot add or remove a row, then restore the report
   * the actual writer is meant to consume.
   */
  protected fun bindLegacyFixtureRecord() {
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
  protected fun baselineUpdateRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineUpdate", "clearFakePitEvidence", *args)
  }

  protected fun baselineUnionRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineUnion", "clearFakePitEvidence", *args)
  }

  protected fun rawBaselinePruneRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselinePrune", "clearFakePitEvidence", *args)
  }

  /**
   * Successful prune fixtures establish the two completed matching previews the
   * public writer now requires. Refusal tests use [rawBaselinePruneRunner] so they
   * can exercise the missing/mismatched-evidence boundary itself.
   */
  protected fun baselinePruneRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    repeat(2) {
      runner("pitestEncoding", "-PnoMutationHistory", *args).build()
    }
    return rawBaselinePruneRunner(*args)
  }

  protected fun baselineRetagRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingBaselineRetag", "clearFakePitEvidence", *args)
  }

  protected fun timeoutAuditInitRunner(vararg args: String): GradleRunner {
    bindLegacyFixtureRecord()
    return runner("pitestEncodingTimeoutAuditInit", "clearFakePitEvidence", *args)
  }

  protected fun modeCompareUnionRunner(vararg args: String) =
    runner("pitestModeCompareUnion", *args)

  /**
   * These ratchet tests fabricate CSVs rather than executing PIT. Snapshot first
   * through the plugin's legacy N-1 path, then attach deterministic provenance to
   * the stashed fixture so mode-compare's record-writing path can be exercised.
   * Real current-input validation is covered by HardeningToolMutationEvidenceFunctionalTest.
   */
  protected fun modeSnapshot(label: String): BuildResult {
    bindLegacyFixtureRecord()
    // Produce the same modern evidence/toolchain pair as a real mode run. The
    // ratchet finalizer is excluded because these fixtures deliberately stage
    // opposite statuses that one baseline cannot accept simultaneously.
    runner("pitestEncoding", "-x", "pitestEncodingVerify").build()
    return runner("pitestModeSnapshot", "-PpitestMode=$label").build()
  }
}
