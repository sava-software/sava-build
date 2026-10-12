import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** Evidence validation, strict certification workflows, and PIT customization contracts. */
class HardeningToolEvidenceContractFunctionalTest : HardeningToolExecFixture() {

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
    assertProjectAtomicCertificationRetry(failed)
    assertFalse(receipt.exists(), "stale-input certification left a passing receipt")
  }

  @Test
  fun `certification evidence validation recommends only the project-atomic retry`() {
    writeFixture(
      buildTail = """
        val sourceToChangeBeforeEvidenceValidation =
          layout.projectDirectory.file("src/main/java/com/example/FakePit.java")
        tasks.register("changeSourceAfterPit") {
          mustRunAfter("pitestEncoding")
          doLast {
            sourceToChangeBeforeEvidenceValidation.asFile.appendText(
              "\n// changed before evidence validation\n"
            )
          }
        }
        tasks.named("pitestEncodingEvidenceValidate") {
          dependsOn("changeSourceAfterPit")
        }
      """.trimIndent(),
    )
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("completed report evidence no longer matches the current build"), failed)
    assertTrue(failed.contains("sourceSha256"), failed)
    assertProjectAtomicCertificationRetry(failed)
    assertFalse(
      failed.contains("Retry: run :pitestEncoding in a new Gradle invocation"),
      "certification evidence validator recommended a one-suite repair:\n$failed",
    )
  }

  @Test
  fun `known invalid status during certification recommends only the project-atomic retry`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-pit-mode.txt").writeText("run-error\n")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(
      failed.contains("RUN_ERROR x1") &&
          failed.contains("RUN_ERROR alone diagnoses neither load nor memory") &&
          failed.contains("non-recurring known runtime or unfinished outcome") &&
          failed.contains("history-free, full unscoped run") &&
          failed.contains("Continued invalid outcomes warrant investigation even when their " +
              "coordinates move"),
      failed,
    )
    assertProjectAtomicCertificationRetry(failed)
    assertFalse(
      failed.contains("run :pitestEncoding") || failed.contains("-PnoMutationHistory"),
      "certification invalid-status refusal recommended a standalone suite retry:\n$failed",
    )
  }

  @Test
  fun `ordinary ratchet refusal during certification recommends the project-atomic retry`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-pit-mode.txt").writeText("survive\n")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("unkilled mutant(s) not in the accepted baseline"), failed)
    assertProjectAtomicCertificationRetry(failed)
    assertFalse(
      failed.contains("Retry: run :pitestEncoding in a new Gradle invocation"),
      "certification ratchet refusal recommended a one-suite repair:\n$failed",
    )
  }

  @Test
  fun `known invalid status follows the explicitly requested aggregate workflow`() {
    writeFixture()
    File(fixtureDir, "fake-pit-mode.txt").writeText("run-error\n")

    val converge = runner("pitestConverge").buildAndFail().output
    assertTrue(
      converge.contains("RUN_ERROR x1") &&
          converge.contains("run :pitestConverge from the start") &&
          converge.contains("non-recurring known runtime or unfinished outcome") &&
          converge.contains("sufficient closure"),
      converge,
    )
    assertFalse(
      converge.contains("run :pitestEncoding") ||
          converge.contains("hardeningCertify") ||
          converge.contains("replaces the incomplete generated report"),
      converge,
    )

    val mode = runner(
      "pitestEncoding", "pitestModeSnapshot", "-PpitestMode=solo", "-PnoMutationHistory"
    ).buildAndFail().output
    assertTrue(
      mode.contains("RUN_ERROR x1") &&
          mode.contains("re-run every suite in mode 'solo'") &&
          mode.contains(":pitestModeSnapshot -PpitestMode=solo") &&
          mode.contains("non-recurring known runtime or unfinished outcome") &&
          mode.contains("sufficient closure"),
      mode,
    )
    assertFalse(
      mode.contains("hardeningCertify") ||
          mode.contains("run :pitestEncoding") ||
          mode.contains("replaces the incomplete generated report"),
      mode,
    )
  }

  @Test
  fun `certification receipt refusal restarts the project when completed evidence disappears`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "build.gradle.kts").appendText(
      """

        val completedEvidenceToRemove =
          layout.buildDirectory.file("reports/pitest/encoding/.evidence.tsv")
        tasks.register("removeCompletedEvidenceAfterVerify") {
          mustRunAfter("pitestEncodingVerify")
          doLast {
            check(completedEvidenceToRemove.get().asFile.delete())
          }
        }
        tasks.named("hardeningCertify") {
          dependsOn("removeCompletedEvidenceAfterVerify")
        }
      """.trimIndent() + "\n"
    )

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("has no completed report/evidence pair"), failed)
    assertProjectAtomicCertificationRetry(failed)
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists(),
      "missing-evidence certification left a passing receipt",
    )
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
    assertProjectAtomicCertificationRetry(failed)
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
    val priorReceipt = receipt.readBytes()

    val excluded = runner("releaseG", "-x", "pitestEncodingVerify").buildAndFail().output
    assertTrue(excluded.contains("task exclusion(s): -x pitestEncodingVerify"), excluded)
    assertEquals(priorReceipt.toList(), receipt.readBytes().toList())
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").isFile,
      "a refused certification must retain its invalidation sentinel")

    File(fixtureDir, "fake-pit-mode.txt").writeText("timeout\n")
    val strict = runner("releaseG").buildAndFail().output
    assertTrue(
      strict.contains(
        "no audited set covers 1 physical TIMED_OUT mutant instance across 1 line-less key"
      ) &&
          strict.contains("  Evidence:") && strict.contains("  Review:") &&
          strict.contains("  Watchdog context:") && strict.contains("  Remedy:"),
      strict,
    )
    assertEquals(
      priorReceipt.toList(),
      receipt.readBytes().toList(),
      "strict verification failure destroyed the last successful receipt",
    )
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
    val priorReceipt = receipt.readBytes()

    val skipped = runner("releaseGate", "-PskipPit").buildAndFail().output
    assertTrue(
      skipped.contains("without completing PIT in this invocation") ||
          skipped.contains("no PIT execution plus successful verification recorded"),
      skipped)
    assertEquals(priorReceipt.toList(), receipt.readBytes().toList())
    assertTrue(File(fixtureDir, ".pitest-history/pitest-certification.running").isFile)

    val reused = runner("releaseGate", "-PskipPit").buildAndFail().output
    assertTrue(reused.contains("Reusing configuration cache"), reused)
    assertEquals(priorReceipt.toList(), receipt.readBytes().toList())
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
  fun `PIT arguments with no evidence field cannot be changed behind the identity`() {
    // Each of these reaches PIT's command line and has no line of its own in the
    // configuration text, so a late change alters the run while the recorded identity
    // stays byte-identical. --classPath is the sharpest: the identity binds a
    // parallel file collection, not the one PIT is handed.
    listOf(
      "applicationClasspath.from(files(\"extra\"))",
      "sourceDirectories.from(files(\"gen\"))",
      "outputFormats.set(listOf(\"CSV\"))",
      "timestampedReports.set(true)",
    ).forEach { mutation ->
      writeFixture(
        buildTail = """
          tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
            $mutation
          }
        """.trimIndent(),
      )
      val refused = runner("pitestEncoding").buildAndFail().output
      assertTrue(
        refused.contains("cannot be changed any further"),
        "'$mutation' was accepted behind an unchanged evidence identity:\n" + refused,
      )
    }

    // reportDirectory is locked too, on the run task: it was advertised as
    // customizable and does not work, because the ratchet reads a fixed path, so a
    // relocated report reads as a run that never happened.
    writeFixture(
      buildTail = """
        tasks.named<software.sava.build.hardening.task.PitestRunTask>("pitestEncoding") {
          reportDirectory.set(layout.buildDirectory.dir("reports/relocated"))
        }
      """.trimIndent(),
    )
    assertTrue(
      runner("pitestEncoding").buildAndFail().output.contains("cannot be changed any further"),
      "the report directory can still be relocated out from under the ratchet",
    )

    // A configureEach registered before this plugin applies runs BEFORE the task's own
    // configuration, so locking alone would pin whatever it left behind. The wiring
    // sets rather than adds for exactly that reason, and this is what says so.
    writeFixture(
      // Registered from settings, so it is added before `plugins {}` applies the
      // plugin and therefore runs before the task's own configuration. Untyped and
      // reflective because the plugin's classes are not on the settings classpath.
      settingsTail = """
        gradle.allprojects {
          tasks.configureEach {
            if (name != "pitestEncoding") return@configureEach
            val task = this
            (task.javaClass.getMethod("getApplicationClasspath").invoke(task)
              as org.gradle.api.file.ConfigurableFileCollection).from("smuggled")
            (task.javaClass.getMethod("getSourceDirectories").invoke(task)
              as org.gradle.api.file.ConfigurableFileCollection).from("smuggled-src")
            @Suppress("UNCHECKED_CAST")
            (task.javaClass.getMethod("getOutputFormats").invoke(task)
              as org.gradle.api.provider.ListProperty<String>).set(listOf("CSV"))
            @Suppress("UNCHECKED_CAST")
            (task.javaClass.getMethod("getTimestampedReports").invoke(task)
              as org.gradle.api.provider.Property<Boolean>).set(true)
          }
        }
      """.trimIndent(),
    )
    val preloaded = runner("pitestEncoding", "--info").build().output
    assertFalse(
      preloaded.contains("smuggled"),
      "a pre-registration configureEach survived into PIT's classpath:\n" + preloaded,
    )
    assertFalse(
      preloaded.contains("smuggled-src"),
      "a pre-registration configureEach survived into --sourceDirs:\n" + preloaded,
    )
    assertTrue(preloaded.contains("--outputFormats=HTML,XML,CSV"), preloaded)
    assertTrue(preloaded.contains("--timestampedReports=false"), preloaded)
  }

  @Test
  fun `master JVM configuration the evidence does not record is refused`() {
    // A `-Dmode=b` given to a main class that reads it produces a different report
    // from `-Dmode=a`, and neither the arguments nor the properties appear in the
    // identity — so one report would validate under the other's. Refused rather than
    // recorded: JavaExec relocates a `-D` from jvmArgs into systemProperties while
    // assembling the process, so the value read before a run and after it are
    // different shapes and binding it made the guard fire on its own bookkeeping.
    listOf("jvmArgs(\"-Dmode=b\")", "systemProperty(\"mode\", \"b\")").forEach { mutation ->
      writeFixture(
        buildTail = """
          tasks.named<JavaExec>("pitestEncoding") { $mutation }
        """.trimIndent(),
      )
      val refused = runner("pitestEncoding").buildAndFail().output
      assertTrue(
        refused.contains("the PIT master JVM carries configuration the evidence does not record"),
        "'$mutation' reached the PIT process unrecorded:\n" + refused,
      )
    }
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
