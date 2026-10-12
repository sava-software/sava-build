import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** PIT execution diagnostics, output filtering, and build-wide execution locking. */
class HardeningToolExecFunctionalTest : HardeningToolExecFixture() {

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
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("pitestEncoding").build()

    val report = File(fixtureDir, "build/reports/pitest/encoding")
    listOf(".uncompiled-sources.tsv", ".filter-sources.tsv")
      .forEach { sidecar -> assertTrue(report.resolve(sidecar).isFile, "the completed run wrote no $sidecar") }
    report.resolve("mutations.xml").writeText("stale XML")
    report.resolve("index.html").writeText("stale HTML index")
    File(fixtureDir, "fake-pit-mode.txt").writeText("fail-before-report\n")

    val failed = runner("pitestEncoding").buildAndFail().output

    assertTrue(failed.contains("failed attempt raw logs"), failed)
    assertTrue(report.resolve(".running").isFile, "failed attempt exposed old evidence")
    listOf("mutations.csv", "mutations.xml", "index.html", ".evidence.tsv", ".toolchain.tsv",
        ".uncompiled-sources.tsv", ".filter-sources.tsv")
      .forEach { stale -> assertFalse(report.resolve(stale).exists(), "stale $stale survived") }
    assertTrue(report.resolve("pitest.stderr.log").readText().endsWith("failed before report"))

    val certificationFailed = runner("hardeningCertify").buildAndFail().output
    assertProjectAtomicCertificationRetry(certificationFailed)
    assertFalse(
      certificationFailed.contains("Retry: run :pitestEncoding in a new Gradle invocation"),
      "failed certification recommended a one-suite repair:\n$certificationFailed",
    )
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
      "With audited PIT 1.30.0, its raw arcmutateMissing field controls only the HTML promotion"
    val raw = "ReportOptions [... arcmutateMissing=true ...]"

    assertTrue(
      diagnostic.output.contains(
        "licensed ArcMutate base 1.7.2 was validated on the effective tool classpath"
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
    // Intentional marker-only exception: the tolerated candidate-cannot-fire exit returns
    // before report completion. Its raw output, not `.running` alone, distinguishes this
    // zero-fire result from another failure before a CSV exists.
    assertTrue(report.resolve(".running").isFile, "zero-fire marker was unexpectedly cleared")
    assertFalse(report.resolve("mutations.csv").isFile, "zero-fire trial unexpectedly wrote a report")
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
          // Master-JVM settings PIT forwards to the minions, none of them in the evidence.
          if (providers.gradleProperty("reservedSystemProperty").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { systemProperty("user.language", "tr") }
          }
          if (providers.gradleProperty("jvmArgumentProvider").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") {
              jvmArgumentProviders.add(org.gradle.process.CommandLineArgumentProvider { listOf("-Dmode=b") })
            }
          }
          if (providers.gradleProperty("characterEncoding").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { defaultCharacterEncoding = "ISO-8859-1" }
          }
          if (providers.gradleProperty("allJvmArgs").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { allJvmArgs = listOf("-Duser.language=tr") }
          }
          // A Set binds the Iterable overload, which JavaExec routes past the List one.
          if (providers.gradleProperty("allJvmArgsIterable").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { setAllJvmArgs(setOf("-Duser.language=tr")) }
          }
          if (providers.gradleProperty("reservedSystemPropertyMap").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { systemProperties(mapOf("java.io.tmpdir" to "/tmp/pit")) }
          }
          if (providers.gradleProperty("reservedSystemPropertySetter").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { systemProperties = mapOf("file.encoding" to "ISO-8859-1") }
          }
          if (providers.gradleProperty("environmentEntry").isPresent) {
            tasks.named<JavaExec>("pitestEncoding") { environment("FIXTURE_UNBOUND", "1") }
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

    val masterJvmRefusals = mapOf(
      "reservedSystemProperty" to "reserved system properties=[user.language]",
      "reservedSystemPropertyMap" to "reserved system properties=[java.io.tmpdir]",
      "reservedSystemPropertySetter" to "reserved system properties=[file.encoding]",
      "jvmArgumentProvider" to "jvmArgumentProviders=1",
      "characterEncoding" to "defaultCharacterEncoding=ISO-8859-1",
      "allJvmArgs" to "allJvmArgs=[-Duser.language=tr]",
      "allJvmArgsIterable" to "allJvmArgs=[-Duser.language=tr]",
    )
    (listOf("unmanagedPitArgs", "unmanagedPitProvider") + masterJvmRefusals.keys).forEach { property ->
      val refused = runner("pitestEncoding", "-P$property").buildAndFail().output
      val expectedRefusal = masterJvmRefusals[property]
      if (expectedRefusal == null) {
        assertTrue(
          refused.contains("direct JavaExec args/argumentProviders are not supported") &&
              refused.contains("first-class typed, evidence-bound plugin property"),
          refused,
        )
      } else {
        assertTrue(
          refused.contains("the PIT master JVM carries configuration the evidence does not record") &&
              refused.contains(expectedRefusal),
          "$property:\n$refused",
        )
      }
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

    // The environment is inherited by the minions and recorded by nothing; it stays a
    // documented limit rather than a refusal, because it has no unconfigured value.
    runner("pitestEncoding", "-PenvironmentEntry").build()
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
      ) && boundary.contains("hardening: 1 advisory finding across 1 scope"),
      "the inclusive 250 ms boundary did not produce exactly one advisory:\n$boundary",
    )
  }

  @Test
  fun `an unattributed slow coverage test stays diagnostic and out of the advisory summary`() {
    writeFixture()
    File(fixtureDir, "fake-pit-mode.txt").writeText("unattributed-slow-test\n")

    val ordinary = runner("pitestEncoding").build().output
    assertTrue(ordinary.contains("Slowest test ("), "PIT timing was lost:\n$ordinary")
    assertFalse(ordinary.contains("slowest PIT coverage-phase test"), ordinary)
    // This fixture also has a legitimate missing-fuzz-corpus advisory. Only the
    // unattributed timing must stay out of the summary; other advice must survive.
    assertFalse(ordinary.contains("slowest PIT coverage-phase test took"), ordinary)
    val raw = File(fixtureDir, "build/reports/pitest/encoding/pitest.stdout.log").readText()
    assertTrue(raw.contains("Slowest test (") && raw.contains("took 416 ms"), raw)

    val detailed = runner("pitestEncoding", "--info").build().output
    assertTrue(detailed.contains("slowest PIT coverage-phase test"), detailed)
    assertTrue(detailed.contains("this does not establish whether it covers mutated code"), detailed)
    assertFalse(detailed.contains("slowest PIT coverage-phase test took"), detailed)
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
            // Through the environment, not the master JVM's own options: those are
            // refused because the evidence does not record them, so a run configured
            // with one could validate under another's identity.
            environment(
              "PARALLEL_PIT_LOCK" to "${fixtureDir.resolve("parallel-pit.lock").absolutePath}",
              "PARALLEL_PIT_PROJECT" to "$projectName",
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
              Path lock = Path.of(System.getenv("PARALLEL_PIT_LOCK"));
              Path events = lock.resolveSibling("parallel-pit.events");
              String project = System.getenv("PARALLEL_PIT_PROJECT");
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
}
