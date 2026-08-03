import software.sava.build.hardening.BaselineEngine
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.BaselineNotes
import software.sava.build.hardening.ExclusionAudit
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningNames
import software.sava.build.hardening.HardeningTemplateDigest
import software.sava.build.hardening.HardeningToolDefaults
import software.sava.build.hardening.Mutant
import software.sava.build.hardening.MutantStatus
import software.sava.build.hardening.MutatorAdvice
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.PitestExecutionLock
import software.sava.build.hardening.TimeoutAudit
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

plugins {
  id("java")
}

// PIT mutation testing and Jazzer coverage-guided fuzzing with explicit production ownership,
// configured via the 'hardening' extension. The main and test sources are recompiled into
// one plain classpath root per tool, without module-info: 'compileForPitest' at
// 'hardening.mutationBytecodeRelease' into 'build/mutation-classes', and 'compileForFuzz'
// at 'hardening.bytecodeRelease' into 'build/fuzz-classes'. The releases exist to be
// lowered when a tool's bundled ASM lags the toolchain's class-file version. PIT silently
// discards classpath roots whose path contains the string "pitest" — never rename its
// directory to anything containing it.

// Default tool versions come from gradle/libs.versions.toml via the generated
// HardeningToolDefaults, so Dependabot can keep them current.
val hardening = extensions.create<HardeningExtension>("hardening")
// Follow the consuming build instead of assuming Sava's current JDK. This makes the
// standalone plugin usable on Java 17/21 while retaining an explicit override for a
// tool whose bundled ASM lags the selected toolchain.
hardening.bytecodeRelease.convention(
    javaToolchains.compilerFor(java.toolchain).map { it.metadata.languageVersion.asInt() })
hardening.mutationBytecodeRelease.convention(hardening.bytecodeRelease)
hardening.pitestVersion.convention(HardeningToolDefaults.PITEST)
hardening.pitestJunit5PluginVersion.convention(HardeningToolDefaults.PITEST_JUNIT5_PLUGIN)
hardening.jazzerVersion.convention(HardeningToolDefaults.JAZZER)
hardening.arcmutateBaseVersion.convention(HardeningToolDefaults.ARCMUTATE_BASE)
hardening.generateTestSupport.convention(false)
hardening.testSupportPackage.convention("software.sava.hardening.support")
hardening.testSupportExcludes.convention(emptyList())
hardening.recompileExcludes.convention(emptyList())

// Arcmutate incremental analysis ("history"): reuses per-mutant results across runs
// when neither the mutated class nor its covering tests changed. Open-source PIT
// accepts the history flags but cannot honour them — its only registered history
// factory throws — so everything below keys off the licence certificate: without an
// 'arcmutate-licence.txt' at the project or root-project directory, no dependency is
// added and no flags are passed, and PIT runs exactly as open source.
// '-PnoMutationHistory' forces a from-scratch run with the licence present.
// Certification is a fresh observation by definition. Whether this invocation is a
// certification is decided at execution time by hardeningCertifyPreflight's build
// service, so aliases, abbreviations and aggregate tasks cannot accidentally retain
// history merely because their command-line spelling differs.
val mutationHistoryAvailable = (layout.projectDirectory.file("arcmutate-licence.txt").asFile.isFile ||
    rootProject.layout.projectDirectory.file("arcmutate-licence.txt").asFile.isFile) &&
    !providers.gradleProperty("noMutationHistory").isPresent

val pitest = configurations.create("pitest") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("org.pitest:pitest-command-line:${hardening.pitestVersion.get()}"))
    add(project.dependencies.create("org.pitest:pitest-junit5-plugin:${hardening.pitestJunit5PluginVersion.get()}"))
    if (mutationHistoryAvailable) {
      add(project.dependencies.create("com.arcmutate:base:${hardening.arcmutateBaseVersion.get()}"))
    }
  }
}
val jazzer = configurations.create("jazzer") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("com.code-intelligence:jazzer:${hardening.jazzerVersion.get()}"))
  }
}

val mutationClassesDir = layout.buildDirectory.dir("mutation-classes")
val fuzzClassesDir = layout.buildDirectory.dir("fuzz-classes")

fun registerRecompile(taskName: String, tool: String, destination: Provider<Directory>, release: Provider<Int>) =
  tasks.register<JavaCompile>(taskName) {
    description = "Compiles the main and test sources to bytecode $tool can read."
    // custom JavaCompile tasks default to the daemon's JVM, not the project toolchain,
    // and the daemon may run on a JDK too old for 'release'
    javaCompiler.convention(javaToolchains.compilerFor(java.toolchain))
    source(sourceSets.main.get().java, sourceSets.test.get().java)
    exclude("**/module-info.java")
    // lazy: the consuming build script sets the extension after the plugin applies
    val recompileExcludes = hardening.recompileExcludes
    exclude { element -> !element.isDirectory && element.file.name in recompileExcludes.get() }
    modularity.inferModulePath = false
    // dependency jars only — including other projects' — while this project's own
    // outputs are recompiled from source instead. Read the *configured* source-set
    // classpath, never the live 'compileTestJava' task's property: the whitebox JPMS
    // test plugin rewrites that task's classpath while the task executes, so reading
    // it here hands this recompile an emptied classpath on exactly the runs where
    // compileTestJava had work to do, while an up-to-date run leaves it intact — a
    // first-run-only failure that vanishes on retry (casebook: the recompile that
    // only failed when another compile ran).
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = sourceSets.test.get().compileClasspath.filter { !it.absolutePath.startsWith(ownBuildDir) }
    destinationDirectory = destination
    options.release = release
  }

val compileForPitest = registerRecompile(
    "compileForPitest", "PIT", mutationClassesDir, hardening.mutationBytecodeRelease)
val compileForFuzz = registerRecompile(
    "compileForFuzz", "Jazzer", fuzzClassesDir, hardening.bytecodeRelease)

// The 'is this change safe' gate: 'test' plus every registered mutation suite,
// each finalized by its baseline verification (see the ratchet below).
val qualityGate = tasks.register("qualityGate") {
  group = "verification"
  description = "Unit tests plus every PIT suite with mutation-baseline verification."
  dependsOn(tasks.named("test"))
  val mutateOnly = providers.gradleProperty("mutateOnly")
  doFirst {
    if (mutateOnly.isPresent) {
      throw GradleException(
          "qualityGate cannot certify a scoped mutation population (-PmutateOnly=${mutateOnly.get()}). " +
              "Run the gate without -PmutateOnly so every registered suite measures its full population."
      )
    }
  }
}

// A release-grade gate is intentionally separate from the permissive developer gate.
// Its preflight is a dependency of every PIT execution selected for certification, so
// a record-writing/scoped flag is refused before it can run or change evidence.
val hardeningCertificationSession = gradle.sharedServices.registerIfAbsent(
    "hardeningCertificationSession", HardeningCertificationSession::class
) {}
val certificationReceiptFile = layout.buildDirectory.file("hardening/pitest-certification.tsv")
val certificationReceiptRunning = layout.buildDirectory.file("hardening/pitest-certification.running")
val hardeningCertifyPreflight = tasks.register("hardeningCertifyPreflight") {
  description = "Internal to hardeningCertify: refuses flags that make the run partial or state-changing."
  // `clean hardeningCertify` is the most useful cold certification. Without an
  // explicit edge Gradle may run this otherwise-independent preflight first and
  // then let clean erase its execution sentinel.
  mustRunAfter("clean")
  val certificationSession = hardeningCertificationSession
  usesService(certificationSession)
  // Script-level declarations read from locals, never from the action: a lambda that
  // touches one captures the precompiled script object, which the configuration cache
  // cannot serialize.
  val receiptFile = certificationReceiptFile
  val receiptRunning = certificationReceiptRunning
  val certifiedProjectPath = project.path
  val excludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  val forbidden = listOf(
      "mutateOnly",
      "updateMutationBaseline",
      "unionMutationBaseline",
      "pruneMutationBaseline",
      "initTimeoutAudit",
      "unionModeFlips",
      "trialMutators",
      "pitestMode",
  ).associateWith { providers.gradleProperty(it) }
  doLast {
    // Invalidate any prior receipt before this invocation can consume or mutate PIT
    // evidence. The sentinel deliberately survives every failure/interruption and is
    // retired only after the new receipt commits atomically.
    receiptFile.get().asFile.delete()
    receiptRunning.get().asFile.also {
      it.parentFile.mkdirs()
      it.writeText("starting\n")
    }
    val present = forbidden.filterValues { it.isPresent }.keys.sorted()
    val excluded = excludedTaskNames
    if (present.isNotEmpty() || excluded.isNotEmpty()) {
      throw GradleException(
          "hardeningCertify is observation-only and full-population; remove " +
              buildList {
                if (present.isNotEmpty()) add("incompatible flag(s): " + present.joinToString(", ") { "-P$it" })
                if (excluded.isNotEmpty()) add("task exclusion(s): " + excluded.joinToString(", ") { "-x $it" })
              }.joinToString("; "))
    }
    val sessionId = certificationSession.get().activate(certifiedProjectPath)
    receiptRunning.get().asFile.writeText("session\t$sessionId\n")
  }
}

val certificationSuiteNames = mutableListOf<String>()
val hardeningCertify = tasks.register("hardeningCertify") {
  group = "verification"
  description = "Fresh, full, strict mutation certification; writes build/hardening/pitest-certification.tsv."
  dependsOn(qualityGate)
  dependsOn(hardeningCertifyPreflight)
  val certificationSession = hardeningCertificationSession
  usesService(certificationSession)
  val reportRoot = layout.buildDirectory.dir("reports/pitest")
  val receiptFile = certificationReceiptFile
  val receiptRunning = certificationReceiptRunning
  val configDir = layout.projectDirectory.dir("config/pitest")
  val certifiedProjectPath = project.path
  val pitVersion = hardening.pitestVersion
  val certifiedSuiteNames = certificationSuiteNames
  doFirst {
    if (!certificationSession.get().isActive(certifiedProjectPath) || !receiptRunning.get().asFile.isFile) {
      receiptFile.get().asFile.delete()
      receiptRunning.get().asFile.also {
        it.parentFile.mkdirs()
        if (!it.isFile) it.writeText("invalid\n")
      }
      throw GradleException(
          "hardeningCertify: certification preflight did not run; refusing to reuse an earlier receipt")
    }
  }
  doLast {
    val sessionId = certificationSession.get().sessionId(certifiedProjectPath)
        ?: throw GradleException("hardeningCertify: certification session is not active")
    val receiptRows = mutableListOf<String>()
    certifiedSuiteNames.sorted().forEach { suiteName ->
      val suiteDir = reportRoot.get().asFile.resolve(suiteName)
      val report = suiteDir.resolve("mutations.csv")
      val manifest = suiteDir.resolve(".evidence.tsv")
      if (!report.isFile || !manifest.isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' has no completed report/evidence pair — " +
                "run the full suite in this certification invocation")
      }
      if (suiteDir.resolve(".running").isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report was left by an interrupted or failed PIT run")
      }
      if (suiteDir.resolve(".scoped").isFile || suiteDir.resolve(".history-assisted").isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report markers say it is scoped or history-assisted, " +
                "not a fresh full observation")
      }
      val evidence = try {
        PitestEvidence.parse(manifest.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException("hardeningCertify: invalid evidence for '$suiteName': ${e.message}", e)
      }
      if (evidence.scope != PitestEvidence.FULL_SCOPE || evidence.historyAssisted) {
        throw GradleException(
            "hardeningCertify: '$suiteName' is not a fresh full observation " +
                "(scope=${evidence.scope}, historyAssisted=${evidence.historyAssisted})")
      }
      if (evidence.reportSha256 != PitestEvidence.sha256(report)) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report changed after its evidence manifest was written")
      }
      try {
        certificationSession.get().requireVerified(certifiedProjectPath, suiteName, evidence)
      } catch (e: IllegalStateException) {
        throw GradleException("hardeningCertify: ${e.message}", e)
      }
      val baseline = configDir.file("$suiteName-accepted.csv").asFile
      val timeouts = configDir.file("$suiteName-timeouts.csv").asFile
      val stamp = configDir.file("$suiteName-pitest-version").asFile
      val recordedPitVersion = stamp.takeIf { it.isFile }?.readText()?.trim()
      if ((baseline.isFile || timeouts.isFile) &&
          recordedPitVersion != null && recordedPitVersion != pitVersion.get()) {
        throw GradleException(
            "hardeningCertify: '$suiteName' committed records are not stamped for PIT ${pitVersion.get()}")
      }
      val hasCommittedRecord = baseline.isFile || timeouts.isFile
      val recordProvenance = when {
        recordedPitVersion != null -> recordedPitVersion
        hasCommittedRecord -> "legacy-unversioned"
        else -> "no-record"
      }
      // The report and compiled/configured inputs prove what PIT observed; these
      // committed files prove what made that observation acceptable. Keep their
      // digest in the inner receipt too, rather than relying only on the fleet
      // wrapper's clean Git commit to bind the baseline, timeout membership, causes,
      // and PIT-version provenance.
      val recordInputsSha256 = PitestEvidence.fingerprint(
          configDir.asFile,
          listOf(
              baseline,
              timeouts,
              stamp,
              configDir.file("README.md").asFile,
          ).filter { it.isFile },
      )
      if ((baseline.isFile || timeouts.isFile) && recordedPitVersion == null) {
        logger.lifecycle(
            "hardeningCertify: '$suiteName' committed record is legacy-unversioned; " +
                "the fresh PIT ${pitVersion.get()} report verified its allowlist, and the next " +
                "deliberate record write will adopt a version stamp")
      }
      receiptRows.add(
          listOf(
              "suite", suiteName, evidence.invocationId, evidence.reportSha256,
              evidence.sourceSha256, evidence.classesSha256, evidence.configurationSha256,
              evidence.pitestVersion, evidence.pluginSha256, evidence.toolClasspathSha256,
              recordInputsSha256, recordProvenance,
          ).joinToString("\t"))
    }
    val receipt = buildString {
      appendLine("schema\t4")
      appendLine("project\t$certifiedProjectPath")
      appendLine("session\t$sessionId")
      appendLine("mode\tfresh-full-strict")
      appendLine(
          "columns\tsuite\tname\tinvocation\treportSha256\tsourceSha256\tclassesSha256\t" +
              "configurationSha256\tpitestVersion\tpluginSha256\ttoolClasspathSha256\t" +
              "recordInputsSha256\trecordPitestVersion")
      receiptRows.forEach(::appendLine)
    }
    BaselineFiles.writeAtomically(receiptFile.get().asFile, receipt)
    receiptRunning.get().asFile.delete()
    logger.lifecycle(
        "hardeningCertify: ${certifiedSuiteNames.size} suite(s) certified; receipt: ${receiptFile.get().asFile}")
  }
}

// End-of-build summary for the verify tasks' advisory findings. The advisories never
// fail the build by design, but across a full gate a warning from the third of a dozen
// suites sits hundreds of lines above the last output — and the gate is the only place
// these checks run (CI's 'check' has no mutation suites). One service per build, shared
// across projects, so the summary prints once no matter how many modules ran suites.
val hardeningAdvisoryLog = gradle.sharedServices.registerIfAbsent(
    "hardeningAdvisoryLog", HardeningAdvisoryLog::class
) {}

// `mustRunAfter` only orders tasks within one project. This service is registered on
// the shared Gradle instance, so PIT worker pools are serialized across every module
// selected in the build as well.
val pitestExecutionLock = gradle.sharedServices.registerIfAbsent(
    "hardeningPitestExecutionLock", PitestExecutionLock::class
) {
  maxParallelUsages.set(1)
}

// The agent-instructions template in HARDENING.md is copied (adapted) into each
// consuming repo's AGENTS.md, and prose copies drift silently — a template change is
// invisible from inside the repos it obligates. The plugin carries a digest of the
// current template block; this check fails until the repo's AGENTS.md contains a
// marker acknowledging that digest. The marker is an acknowledgment, not a checksum
// of the local block: update it only after re-diffing the block against the template
// — a changed bullet may mean new code, not just new prose. A repo without an
// AGENTS.md is warned, not failed: the adoption checklist owns creating the file;
// this task owns keeping it current.
val agentsTemplateInSync = tasks.register("agentsTemplateInSync") {
  group = "verification"
  description = "Fails when AGENTS.md has not acknowledged the current agent-instructions template in HARDENING.md."
  val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
  val expected = HardeningTemplateDigest.SHA256_12
  val templateTask = if (project.path == ":") "hardeningAgentTemplate" else "${project.path}:hardeningAgentTemplate"
  // Set when a build resolves an unreleased sava-build checkout (the fleet canary's
  // '-PsavaBuildLocalRepo'). A stale marker under that flag is the expected state,
  // not a defect: the repo acknowledges the digest of a RELEASED plugin, and this
  // checkout's digest has not shipped yet. Failing here forced repos to acknowledge
  // unreleased digests ahead of the release — which then wedged their 'check'
  // against every published plugin until the release landed and the pin was bumped.
  val validatingUnreleased = providers.gradleProperty("savaBuildLocalRepo").isPresent
  val advisoryLog = hardeningAdvisoryLog
  val advisoryScope = (if (project.path == ":") "" else "${project.path} ") + "agentsTemplateInSync"
  usesService(advisoryLog)
  inputs.files(agentsDoc)
  inputs.property("templateDigest", expected)
  inputs.property("validatingUnreleased", validatingUnreleased)
  doLast {
    if (!agentsDoc.isFile) {
      logger.warn(
          "agentsTemplateInSync: no AGENTS.md at $agentsDoc — copy the agent-instructions " +
              "template printed by './gradlew $templateTask' and add:\n" +
              "  <!-- hardening-template sha256:$expected -->"
      )
      advisoryLog.get().record(advisoryScope, "AGENTS.md is missing")
      return@doLast
    }
    val doc = agentsDoc.readText()
    if (doc.contains("hardening-template sha256:$expected")) {
      return@doLast
    }
    val stale = Regex("hardening-template sha256:([0-9a-f]+)").find(doc)
    if (stale != null && validatingUnreleased) {
      logger.warn(
          "agentsTemplateInSync: AGENTS.md acknowledges template digest ${stale.groupValues[1]}; this " +
              "unreleased checkout's is $expected — the marker dance lands with the release that ships " +
              "this digest, not before it. When bumping the plugin past that release, re-diff the " +
              "AGENTS.md hardening block against './gradlew $templateTask', then update the marker to:\n" +
              "  <!-- hardening-template sha256:$expected -->"
      )
      advisoryLog.get().record(
          advisoryScope, "AGENTS.md acknowledges an older hardening template during unreleased validation")
      return@doLast
    }
    throw GradleException(
        if (stale == null) {
          "AGENTS.md has no 'hardening-template' marker. Diff its hardening block against " +
              "the exact template printed by './gradlew $templateTask', sync or act on what " +
              "differs, then add:\n  <!-- hardening-template sha256:$expected -->"
        } else {
          "The shared agent-instructions template changed since this repo's AGENTS.md last " +
          "acknowledged it (marker ${stale.groupValues[1]}, current $expected). Re-diff the " +
              "AGENTS.md hardening block against './gradlew $templateTask' — a " +
              "changed bullet may need code, not just prose — then update the marker to:\n" +
              "  <!-- hardening-template sha256:$expected -->"
        }
    )
  }
}
tasks.register("hardeningAgentTemplate") {
  group = "help"
  description = "Prints the exact agent-instructions template carried by this plugin version."
  doLast {
    logger.quiet(HardeningTemplateDigest.TEMPLATE)
    logger.quiet("<!-- hardening-template sha256:${HardeningTemplateDigest.SHA256_12} -->")
  }
}
tasks.named("check") { dependsOn(agentsTemplateInSync) }
qualityGate.configure { dependsOn(agentsTemplateInSync) }

// Serialize the PIT suites: each already runs its own worker pool, and
// concurrent suites contend for the same cores without finishing sooner.
var previousPitestTask: String? = null
var previousRound2Task: String? = null

// Convergence support (HARDENING.md "A wandering kill count"): 'pitestConverge' runs
// every suite twice in one invocation — snapshotting and clearing the reports between
// rounds, since Gradle would otherwise serve the second run from the first — and diffs
// per-mutant statuses. Two runs can match in total while disagreeing about which
// mutants died; only the per-mutant diff names what moved.
val convergeSuiteNames = mutableListOf<String>()
val convergeSnapshotDir = layout.buildDirectory.dir("pitest-converge/round1")
val pitestConvergeSnapshot = tasks.register("pitestConvergeSnapshot") {
  description = "Internal to pitestConverge: snapshots round-one PIT reports and clears them."
  mustRunAfter(hardeningCertifyPreflight)
  val certificationSession = hardeningCertificationSession
  usesService(certificationSession)
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = convergeSnapshotDir
  val names = convergeSuiteNames
  val convergenceProjectPath = project.path
  doLast {
    if (certificationSession.get().isActive(convergenceProjectPath)) {
      throw GradleException(
          "pitestConverge cannot run inside hardeningCertify: convergence's unverified round two would " +
              "replace the strict-verified report the certification receipt must bind. Run the two " +
              "workflows in separate Gradle invocations.")
    }
    data class ConvergeSnapshotInput(
      val suiteName: String,
      val reportDir: File,
      val csv: File,
    )
    // Validate every suite before replacing the prior snapshot, copying a partial
    // successor, or clearing any canonical report. A later failed/interrupted suite
    // must not destroy the earlier suites' only round-one evidence.
    val reports = reportsRoot.get().asFile
    val inputs = names.sorted().map { suiteName ->
      val reportDir = reports.resolve(suiteName)
      val csv = reportDir.resolve("mutations.csv")
      if (!csv.isFile) {
        throw GradleException("pitestConverge: no round-one report for '$suiteName' at $csv")
      }
      val evidenceFile = reportDir.resolve(".evidence.tsv")
      val evidence = if (evidenceFile.isFile) try {
        PitestEvidence.parse(evidenceFile.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException("pitestConverge: invalid round-one evidence for '$suiteName': ${e.message}", e)
      } else null
      if (reportDir.resolve(".history-assisted").isFile || evidence?.historyAssisted == true) {
        throw GradleException(
            "pitestConverge proves nothing when '$suiteName' round one is arcmutate-history-assisted — " +
                "two assisted runs agree by construction. Re-run with -PnoMutationHistory.")
      }
      if (reportDir.resolve(".running").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-one report was left by an interrupted or " +
                "failed run — a partial population cannot anchor the diff. Re-run the suites."
        )
      }
      if (reportDir.resolve(".scoped").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-one report was produced with -PmutateOnly — " +
                "a scoped population cannot prove suite convergence. Re-run without -PmutateOnly."
        )
      }
      if (evidence != null && evidence.scope != PitestEvidence.FULL_SCOPE) {
        throw GradleException(
            "pitestConverge: the '$suiteName' evidence manifest says the round-one report is " +
                "scoped (${evidence.scope}) even though its marker is missing — a partial population " +
                "cannot prove suite convergence. Re-run without -PmutateOnly."
        )
      }
      ConvergeSnapshotInput(suiteName, reportDir, csv)
    }
    val snapshot = snapshotRoot.get().asFile
    snapshot.deleteRecursively()
    snapshot.mkdirs()
    inputs.forEach { input ->
      input.csv.copyTo(snapshot.resolve("${input.suiteName}.csv"))
    }
    // Copy every suite successfully before clearing any canonical report.
    inputs.forEach { it.reportDir.deleteRecursively() }
    logger.lifecycle("pitestConverge: snapshotted ${names.size} round-one report(s), reports cleared for round two")
  }
}
val pitestConverge = tasks.register("pitestConverge") {
  group = "verification"
  description = "Runs every PIT suite twice and diffs per-mutant statuses; a wandering kill count is a defect to chase, not re-ratchet past."
  dependsOn(pitestConvergeSnapshot)
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = convergeSnapshotDir
  val names = convergeSuiteNames
  doLast {
    val gated = MutantStatus.entries.filter { it.gated }.mapTo(HashSet()) { it.name }
    // Rows can share a (class,method,line,mutator) key, so statuses are compared as
    // sorted multisets per key rather than single values. Converge deliberately KEEPS
    // the line in its key while the baseline and modeCompare dropped it: both rounds
    // run identical code, so lines cannot churn here, and the finer key localizes a
    // flip to the exact mutant instead of a sibling group.
    fun statuses(csv: File): Map<String, List<String>> = Mutant.parseReport(csv.readLines())
        .groupBy({ it.lineFullKey }, { it.rawStatus })
        .mapValues { (_, statusList) -> statusList.sorted() }
    var boundaryFlips = 0
    var benignFlips = 0
    names.forEach { suiteName ->
      val round1 = statuses(snapshotRoot.get().asFile.resolve("$suiteName.csv"))
      val round2Csv = reportsRoot.get().asFile.resolve("$suiteName/mutations.csv")
      if (round2Csv.parentFile.resolve(".running").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-two report was left by an interrupted or " +
                "failed run — a partial population would read as mass flips. Re-run pitestConverge."
        )
      }
      if (round2Csv.parentFile.resolve(".scoped").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-two report was produced with -PmutateOnly — " +
                "a scoped population cannot prove suite convergence. Re-run without -PmutateOnly."
        )
      }
      val round2 = statuses(round2Csv)
      (round1.keys + round2.keys).sorted().forEach { key ->
        val before = round1[key] ?: emptyList()
        val after = round2[key] ?: emptyList()
        if (before != after) {
          // Crossing is a gated-submultiset comparison, not a presence check:
          // SURVIVED<->NO_COVERAGE changes the status-specific baseline, and a
          // [KILLED,SURVIVED] -> [SURVIVED,SURVIVED] sibling flip grows its count
          // without changing gated presence. Both can move the ratchet.
          val crossed = before.filter { it in gated } != after.filter { it in gated }
          if (crossed) boundaryFlips++ else benignFlips++
          logger.lifecycle(
              "pitestConverge '$suiteName': $key — ${before.joinToString("/")} -> ${after.joinToString("/")}" +
                  (if (crossed) "  ** crosses the unkilled boundary **" else "")
          )
        }
      }
    }
    if (boundaryFlips == 0 && benignFlips == 0) {
      logger.lifecycle(
          "pitestConverge: ${names.size} suite(s) converged — zero per-mutant status flips " +
              "(run-to-run only; solo-vs-gate load flips need pitestModeSnapshot/pitestModeCompare)"
      )
    } else if (boundaryFlips == 0) {
      logger.lifecycle(
          "pitestConverge: $benignFlips flip(s), none crossing the unkilled boundary " +
              "(e.g. KILLED<->TIMED_OUT under load) — the ratchet cannot move"
      )
    } else {
      throw GradleException(
          "pitestConverge: $boundaryFlips flip(s) cross the unkilled boundary — a wandering " +
              "kill count is a defect to chase before refreshing any baseline. Known causes " +
              "and the diagnosis order are in HARDENING.md ('A wandering kill count'); union " +
              "a row with -PunionMutationBaseline only once observed to flip in both directions."
      )
    }
  }
}
// If certification is combined with either the public aggregate or its directly
// selectable snapshot component, let convergence's explicit session refusal run
// before hardeningCertify can consider writing a receipt.
hardeningCertify.configure { mustRunAfter(pitestConverge, pitestConvergeSnapshot) }

// Mutator trial (HARDENING.md "The mutator set bounds what the ratchet can see"):
// 'pitestMutatorTrial -PtrialMutators=EXPERIMENTAL_X[,...]' runs every suite with only
// the candidate mutators and tabulates what fired, so "enable only what fires" is one
// invocation instead of a hand-kept table of per-suite runs and count diffs.
val trialMutatorsProperty = providers.gradleProperty("trialMutators")
var previousTrialTask: String? = null
val pitestMutatorTrial = tasks.register("pitestMutatorTrial") {
  group = "verification"
  description = "Runs every PIT suite with only the -PtrialMutators candidates and tabulates what fired; enable per suite only what fires, and record the numbers."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val names = convergeSuiteNames
  val trial = trialMutatorsProperty
  doLast {
    val candidates = trial.orNull ?: throw GradleException(
        "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only, not the suites' existing sets"
    )
    var fired = 0
    val width = names.maxOf { it.length } + 2
    val lines = names.sorted().map { name ->
      val csv = reportsRoot.get().asFile.resolve("$name-trial/mutations.csv")
      // a CSV still carrying the '.running' sentinel is a crashed or interrupted
      // trial's partial population — tabulating it prints half numbers the task
      // then tells the user to record in config/pitest/README.md
      if (csv.isFile && csv.parentFile.resolve(".running").isFile) {
        throw GradleException(
            "pitestMutatorTrial: the '$name' trial report was left by an interrupted or failed " +
                "run — partial numbers must not be recorded. Re-run the trial."
        )
      }
      val rows = if (csv.isFile) Mutant.parseReport(csv.readLines()) else emptyList()
      if (rows.isEmpty()) {
        "  ${name.padEnd(width)}0 generated" +
            (if (csv.isFile) "" else " (no report — cannot fire here, or the run failed above)")
      } else {
        fired++
        val detected = rows.count { it.detected }
        val unkilled = rows.count { it.gated }
        val perMutator = rows.groupingBy { it.mutatorSimpleName }.eachCount()
            .entries.sortedBy { it.key }.joinToString(", ") { "${it.key} x${it.value}" }
        "  ${name.padEnd(width)}${rows.size} generated — $detected killed by existing tests, $unkilled unkilled ($perMutator)"
      }
    }
    logger.lifecycle(
        "pitestMutatorTrial '$candidates': fired in $fired of ${names.size} suite(s)\n" +
            lines.joinToString("\n") +
            "\nEnable only what fires: add the mutator to those suites' 'mutators' and record the " +
            "numbers in config/pitest/README.md (HARDENING.md 'The mutator set bounds what the ratchet can see')."
    )
  }
}

// Solo-vs-gate comparison (HARDENING.md "A wandering kill count"): pitestConverge's two
// rounds share one quiet invocation, so a zero-flip converge proves run-to-run
// determinism only — the load-dependent TIMED_OUT flips appear between a quiet run and
// a 'qualityGate' run, and that comparison was the last hand-run step. These two tasks
// script it: stash each mode's reports under a label, then diff per-mutant statuses
// across the labels.
//
//   ./gradlew <every pitest suite> pitestModeSnapshot -PpitestMode=solo -PnoMutationHistory
//   ./gradlew qualityGate pitestModeSnapshot -PpitestMode=gate -PnoMutationHistory
//   ./gradlew pitestModeCompare              # -PunionModeFlips writes the flip insurance
val pitestModesRoot = layout.buildDirectory.dir("pitest-modes")
val pitestModeProperty = providers.gradleProperty("pitestMode")
val pitestModeSnapshot = tasks.register("pitestModeSnapshot") {
  group = "verification"
  description = "Stashes the current PIT reports as -PpitestMode=<label> for pitestModeCompare, then clears them."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val mode = pitestModeProperty
  doLast {
    val label = mode.orNull ?: throw GradleException(
        "pitestModeSnapshot needs -PpitestMode=<label> naming how the suites just ran (e.g. solo, gate)"
    )
    HardeningNames.requireSafeName("pitestModeSnapshot label", label)
    val root = snapshotRoot.get().asFile.toPath().toAbsolutePath().normalize()
    val destPath = root.resolve(label).normalize()
    if (destPath.parent != root) {
      throw GradleException("pitestModeSnapshot: '-PpitestMode=$label' resolves outside $root")
    }
    data class SnapshotInput(
      val suiteName: String,
      val reportDir: File,
      val csv: File,
      val evidenceFile: File?,
    )
    // Validate the whole fleet before mutating either the destination or any source
    // report. Otherwise a bad later suite leaves a partial snapshot and has already
    // deleted the earlier suites' only reports.
    val inputs = names.sorted().map { suiteName ->
      val reportDir = reportsRoot.get().asFile.resolve(suiteName)
      val csv = reportDir.resolve("mutations.csv")
      if (!csv.isFile) {
        throw GradleException(
            "pitestModeSnapshot: no report for '$suiteName' at $csv — run every suite in the mode " +
                "being labeled first; a partial snapshot would diff a suite against its absence"
        )
      }
      if (reportDir.resolve(".running").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report was left by an interrupted or failed run — " +
                "a partial population is not an observation of this mode. Re-run the suites."
        )
      }
      if (reportDir.resolve(".history-assisted").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report is arcmutate-history-assisted — a reused " +
                "status is not an observation of this mode. Re-run the suites with -PnoMutationHistory."
        )
      }
      if (reportDir.resolve(".scoped").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report was produced with -PmutateOnly — a partial " +
                "population is not an observation of this mode. Re-run the suites without -PmutateOnly."
        )
      }
      val evidenceFile = reportDir.resolve(".evidence.tsv")
      if (evidenceFile.isFile) {
        val evidence = try {
          PitestEvidence.parse(evidenceFile.readText())
        } catch (e: IllegalArgumentException) {
          throw GradleException("pitestModeSnapshot: invalid evidence for '$suiteName': ${e.message}", e)
        }
        if (evidence.scope != PitestEvidence.FULL_SCOPE || evidence.historyAssisted) {
          throw GradleException(
              "pitestModeSnapshot: the '$suiteName' evidence manifest says the report is " +
                  (if (evidence.historyAssisted) "history-assisted" else "scoped (${evidence.scope})") +
                  " even though its marker is missing — it is not a fresh full observation")
        }
      } else {
        logger.warn(
            "pitestModeSnapshot: '$suiteName' has no completed-run evidence manifest — " +
                "legacy snapshot accepted for N-1 migration; re-run under this plugin before unioning flips")
      }
      SnapshotInput(suiteName, reportDir, csv, evidenceFile.takeIf(File::isFile))
    }
    val dest = destPath.toFile()
    dest.deleteRecursively()
    dest.mkdirs()
    inputs.forEach { input ->
      input.csv.copyTo(dest.resolve("${input.suiteName}.csv"))
      input.evidenceFile?.copyTo(dest.resolve("${input.suiteName}.evidence.tsv"))
    }
    // Copy every suite successfully before clearing any canonical report.
    inputs.forEach { it.reportDir.deleteRecursively() }
    logger.lifecycle(
        "pitestModeSnapshot: ${names.size} report(s) stashed as '$label'; reports cleared so the " +
            "next mode's run cannot be served from these"
    )
  }
}
val pitestModeCompare = tasks.register("pitestModeCompare") {
  group = "verification"
  description = "Diffs per-mutant statuses across pitestModeSnapshot labels; fails on uninsured unkilled-boundary flips (-PunionModeFlips writes the insurance) and sweeps for accepted rows unkilled in no mode."
  mustRunAfter(pitestModeSnapshot)
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val unionFlips = providers.gradleProperty("unionModeFlips").isPresent
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  doLast {
    val gated = MutantStatus.entries.filter { it.gated }.mapTo(HashSet()) { it.name }
    val modes = snapshotRoot.get().asFile.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
        ?: emptyList()
    if (modes.size < 2) {
      throw GradleException(
          "pitestModeCompare needs at least two labeled snapshots under ${snapshotRoot.get().asFile} " +
              "(found: ${if (modes.isEmpty()) "none" else modes.joinToString()}). Run the suites and " +
              "'pitestModeSnapshot -PpitestMode=<label>' once per mode — e.g. quiet suites as 'solo', " +
              "then under qualityGate as 'gate'."
      )
    }
    // Line-less keys, like the baseline itself: the two modes ran the same code, so
    // per-key status multisets compare cleanly without lines, and an insurance row
    // written here is a row the verify's comparison must recognize.
    fun statuses(csv: File): Map<String, List<String>> = Mutant.parseReport(csv.readLines())
        .groupBy({ it.coordinate }, { it.rawStatus })
        .mapValues { (_, statusList) -> statusList.sorted() }
    var benignFlips = 0
    var insuredFlips = 0
    val uninsured = mutableListOf<String>()
    val unionedNow = mutableListOf<String>()
    val deadRows = mutableListOf<String>()
    names.forEach { suiteName ->
      val evidenceByMode = modes.mapNotNull { label ->
        val file = snapshotRoot.get().asFile.resolve("$label/$suiteName.evidence.tsv")
        if (file.isFile) label to PitestEvidence.parse(file.readText()) else null
      }.toMap()
      if (evidenceByMode.isNotEmpty() && evidenceByMode.size != modes.size) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' mixes provenance-bound and legacy snapshots — " +
                "clear build/pitest-modes and capture every mode again")
      }
      if (evidenceByMode.isNotEmpty()) {
        val stableFingerprints = evidenceByMode.mapValues { (_, evidence) ->
          listOf(
              evidence.pitestVersion,
              evidence.junitPluginVersion,
              evidence.pluginSha256,
              evidence.identitySchema,
              evidence.javaVersion,
              evidence.sourceSha256,
              evidence.classesSha256,
              evidence.classpathSha256,
              evidence.toolClasspathSha256,
              evidence.configurationSha256,
              evidence.scope,
              evidence.historyAssisted.toString(),
          )
        }
        if (stableFingerprints.values.distinct().size != 1) {
          throw GradleException(
              "pitestModeCompare: '$suiteName' snapshots do not describe the same code, tools, " +
                  "classpath, and suite configuration; mode flips cannot be separated from build churn:\n" +
                  stableFingerprints.entries.joinToString("\n") { (label, values) ->
                    "  $label: ${PitestEvidence.sha256(values.joinToString("\n"))}"
                  })
        }
        modes.forEach { label ->
          val csv = snapshotRoot.get().asFile.resolve("$label/$suiteName.csv")
          val evidence = evidenceByMode.getValue(label)
          if (evidence.reportSha256 != PitestEvidence.sha256(csv)) {
            throw GradleException(
                "pitestModeCompare: '$suiteName' snapshot '$label' changed after its evidence was recorded")
          }
        }
      } else if (unionFlips) {
        throw GradleException(
            "pitestModeCompare: refusing -PunionModeFlips for legacy '$suiteName' snapshots without " +
                "completed-run provenance; capture both modes again under this plugin")
      }
      val modePitVersion = evidenceByMode.values.firstOrNull()?.pitestVersion
      val modeToolVersionFile = baselineDir.file("$suiteName-pitest-version").asFile
      if (unionFlips && modePitVersion != null && modeToolVersionFile.isFile &&
          modeToolVersionFile.readText().trim() != modePitVersion) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' snapshots use PIT $modePitVersion but " +
                "${modeToolVersionFile.name} records ${modeToolVersionFile.readText().trim()} — " +
                "refusing to rewrite across the tool-version boundary")
      }
      val perMode = modes.associateWith { label ->
        val csv = snapshotRoot.get().asFile.resolve("$label/$suiteName.csv")
        if (!csv.isFile) {
          throw GradleException(
              "pitestModeCompare: snapshot '$label' has no '$suiteName' report — the suite set " +
                  "changed since it was taken; re-run that mode and re-snapshot"
          )
        }
        statuses(csv)
      }
      // Baseline rows parsed as an ordered LIST (BaselineNotes, both formats): a
      // duplicate key is a sibling mutant, and the set this used to collapse into
      // silently deduped siblings on the union write — a shrink outside prune's rules.
      // Malformed rows and comment lines are diagnosed on the verify's terms — the
      // shared BaselineNotes shape check, the same message — so the two surfaces
      // cannot give contradictory instructions about one row, and the union write
      // below cannot silently drop what it never parsed.
      val baselineFile = baselineDir.file("$suiteName-accepted.csv").asFile
      val baselineLines = if (baselineFile.isFile) baselineFile.readLines() else emptyList()
      val baselineCommentLines = baselineLines.filter {
        it.isNotBlank() && it.trimStart().startsWith("#")
      }
      val baselineRowLines = baselineLines.filter {
        it.isNotBlank() && !it.trimStart().startsWith("#")
      }
      val malformedRows = baselineRowLines.filter { BaselineNotes.malformed(it) }
      if (malformedRows.isNotEmpty()) {
        logger.warn(
            "pitestModeCompare: ${malformedRows.size} malformed row(s) in ${baselineFile.name} — " +
                "expected 'class,method,mutator,STATUS [# note] [# line N]'; the verify names these " +
                "too, and a rewrite would silently drop them:\n" +
                malformedRows.joinToString("\n") { "  $it" }
        )
        if (unionFlips) {
          throw GradleException(
              "pitestModeCompare: ${baselineFile.name} carries ${malformedRows.size} malformed row(s) " +
                  "(listed above) — the -PunionModeFlips rewrite would silently drop them. " +
                  "Fix the row shape first."
          )
        }
      }
      val acceptedRows: MutableList<BaselineNotes.Row> = baselineRowLines
          .filterNot { BaselineNotes.malformed(it) }
          .map { BaselineNotes.parse(it) }
          .toMutableList()
      // Counts, not membership: sibling mutants share the line-less key, and the
      // verify compares multisets — insurance must match its arithmetic or a key
      // "already insured" by one row still fails the next gate verify on the
      // surfaced twin.
      val acceptedCounts: MutableMap<String, Int> = mutableMapOf()
      acceptedRows.forEach { acceptedCounts.merge(it.key, 1, Int::plus) }
      // Observed lines per gated row across every mode's snapshot, so an insurance
      // row lands carrying the '# line' tag the verify's drift advisory reads —
      // an untagged row would put its whole key on the advisory's partial-tag
      // fallback path, weakening the row-level check for its siblings too.
      val rowLines: Map<String, Set<Int>> = if (!unionFlips) emptyMap() else modes.flatMap { label ->
        Mutant.parseReport(snapshotRoot.get().asFile.resolve("$label/$suiteName.csv").readLines())
            .mapNotNull { if (!it.gated) null else it.baselineKey to it.line }
      }.groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.filterNotNull().toSet() }
      var unionedHere = false
      val keys = perMode.values.flatMap { it.keys }.toSortedSet()
      keys.forEach { key ->
        val byMode = perMode.mapValues { (_, m) -> m[key] ?: emptyList() }
        if (byMode.values.distinct().size > 1) {
          // Crossing is a count question, not a presence question, for the same
          // reason insurance is: siblings share the line-less key, so one sibling
          // flipping KILLED -> SURVIVED beside an always-surviving twin changes the
          // gated sub-multiset (1 -> 2) without changing gated presence — and the
          // gate verify fails on the second copy. Presence-based crossing read
          // exactly that as "benign — cannot move the ratchet".
          val crossed = byMode.values
              .map { statusList -> statusList.filter { it in gated }.sorted() }
              .distinct().size > 1
          val detail = modes.joinToString(", ") { label ->
            "$label=${byMode.getValue(label).ifEmpty { listOf("absent") }.joinToString("/")}"
          }
          if (!crossed) {
            benignFlips++
            logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (benign — cannot move the ratchet)")
          } else {
            // Per gated status the baseline needs as many rows as the widest mode
            // observed: a compound condition's siblings flip together under load,
            // and one row cannot insure two of them (casebook: the union write that
            // deduped siblings — the same set-shaped reasoning, in the decision
            // rather than the write).
            val neededRows = byMode.values
                .map { statusList -> statusList.filter { it in gated }.groupingBy { it }.eachCount() }
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, counts) -> counts.max() }
                .toSortedMap()
                .map { (status, needed) -> "$key,$status" to needed }
            when {
              neededRows.all { (row, needed) -> (acceptedCounts[row] ?: 0) >= needed } -> {
                insuredFlips++
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (already insured in the baseline)")
              }
              unionFlips -> {
                neededRows.forEach { (row, needed) ->
                  repeat(needed - (acceptedCounts[row] ?: 0)) {
                    acceptedCounts.merge(row, 1, Int::plus)
                    acceptedRows.add(
                        BaselineNotes.Row(row, "# flip insurance ($detail)", rowLines[row].orEmpty().sorted()))
                    unionedNow.add("$suiteName: $row")
                  }
                }
                unionedHere = true
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (flip insurance written)")
              }
              else -> uninsured.add("$suiteName: $key — $detail")
            }
          }
        }
      }
      if (unionedHere) {
        // every pre-existing row rewritten verbatim (duplicates included), the added
        // insurance rows appended in key order. Comment lines do not survive this
        // rewrite any more than the verify's — the same loudness applies (the one
        // writer the warnDroppedComments doctrine used to miss).
        if (baselineCommentLines.isNotEmpty()) {
          logger.warn(
              "pitestModeCompare: ${baselineCommentLines.size} comment line(s) in ${baselineFile.name} " +
                  "do not survive the insurance rewrite (migrateMutationBaselines preserves them) — " +
                  "move durable prose to config/pitest/README.md or a row's '# note':\n" +
                  baselineCommentLines.joinToString("\n") { "  $it" }
          )
        }
        BaselineFiles.writeAtomically(
            baselineFile,
            acceptedRows.sortedBy { it.key }.joinToString("\n", postfix = "\n") { BaselineNotes.render(it) })
        if (!modeToolVersionFile.isFile && modePitVersion != null) {
          BaselineFiles.writeAtomically(modeToolVersionFile, "$modePitVersion\n")
        }
      }
      // HARDENING.md's sweep: accepted rows unkilled in *no* snapshotted mode are
      // widening the gate for nothing. Report only — removal is a judgment call, and
      // insurance that outlived its cause has a casebook entry of its own.
      // A row is also accounted for by its coordinate's TIMED_OUT budget — the
      // verify's keep-plan discipline, not a presence check, and the budget is the
      // MIN across modes: a mutant that timed out in every mode is one physical
      // mutant, so it vouches for one row, not one row per mode (a per-mode budget
      // with a shared accounted-set let one slow mutant exempt a pile). Plain rows
      // claim the budget before insured rows, mirroring the keep plan's
      // insurance-never-spends-the-budget rule; insured rows past the budget are
      // exactly the "insurance that outlived its cause" this sweep exists to name.
      // Maximum live multiplicity per baseline key across modes. A Set answers only
      // whether one sibling survived and used to let one live mutant vouch for every
      // duplicate accepted row at that key. Plain rows claim live capacity before
      // flip-insurance rows so surplus insurance is the row reported as outliving its
      // cause.
      val liveBudget = HashMap<String, Int>()
      perMode.values.forEach { modeStatuses ->
        val counts = modeStatuses.flatMap { (key, statusList) ->
          statusList.filter { it in gated }.map { "$key,$it" }
        }.groupingBy { it }.eachCount()
        counts.forEach { (key, count) -> liveBudget.merge(key, count, ::maxOf) }
      }
      val liveAccounted = HashSet<Int>()
      acceptedRows.indices.groupBy { acceptedRows[it].key }.forEach { (key, indices) ->
        val ordered = indices.sortedBy { acceptedRows[it].note?.contains("flip insurance") == true }
        ordered.take(liveBudget[key] ?: 0).forEach(liveAccounted::add)
      }
      val deadCandidates = acceptedRows.indices.filterNot { it in liveAccounted }
      val timeoutBudget = HashMap<String, Int>()
      perMode.values.forEachIndexed { modeIndex, modeStatuses ->
        val counts = HashMap<String, Int>()
        modeStatuses.forEach { (coord, statusList) ->
          val timedOut = statusList.count { it == "TIMED_OUT" }
          if (timedOut > 0) counts[coord] = timedOut
        }
        if (modeIndex == 0) {
          timeoutBudget.putAll(counts)
        } else {
          timeoutBudget.keys.retainAll(counts.keys)
          timeoutBudget.replaceAll { coord, budget -> minOf(budget, counts.getValue(coord)) }
        }
      }
      // ROW-level insurance for the ordering, unlike prune's key-level keep: the
      // question here is which row the timeout most plausibly explains, and the
      // row carrying the insurance note has its own removal criterion — this
      // sweep naming it — while its bare sibling has no other story. Key-level
      // partitioning read every sibling of an insured row as insured and handed
      // the budget straight back to the insurance row.
      val timeoutAccounted = HashSet<Int>()
      val (insuredCandidates, plainCandidates) =
          deadCandidates.partition { acceptedRows[it].note?.contains("flip insurance") == true }
      for (index in plainCandidates + insuredCandidates) {
        val coord = acceptedRows[index].key.substringBeforeLast(',')
        val remaining = timeoutBudget[coord] ?: 0
        if (remaining > 0) {
          timeoutBudget[coord] = remaining - 1
          timeoutAccounted.add(index)
        }
      }
      deadCandidates.filterNot { it in timeoutAccounted }
          .sortedBy { acceptedRows[it].key }
          .forEach { index ->
            val row = acceptedRows[index]
            deadRows.add("$suiteName: ${BaselineNotes.render(row.key, row.note, emptyList())}")
          }
    }
    if (deadRows.isNotEmpty()) {
      logger.lifecycle(
          "pitestModeCompare: ${deadRows.size} accepted row(s) unkilled in no snapshotted mode — " +
              "widening the gate for nothing; re-measure before removing:\n" +
              deadRows.joinToString("\n") { "  $it" }
      )
    }
    val summary = "pitestModeCompare (${modes.joinToString(" vs ")}): " +
        "${uninsured.size} uninsured boundary flip(s), ${unionedNow.size} unioned now, " +
        "$insuredFlips already insured, $benignFlips benign (e.g. KILLED<->TIMED_OUT)"
    if (uninsured.isNotEmpty()) {
      throw GradleException(
          summary + ":\n" + uninsured.joinToString("\n") { "  $it" } +
              "\nA row that differs between modes belongs in the baseline (HARDENING.md " +
              "'TIMED_OUT is detected...'): re-run with -PunionModeFlips to write the union with " +
              "a '# flip insurance' note, or union by hand."
      )
    }
    logger.lifecycle(summary)
  }
}

// Format-only baseline migration: parse each suite's accepted baseline and
// re-render it in the current row format — legacy five-field rows become
// line-less keys with '# line' tags. No report, no PIT run, no stamping:
// identity is preserved by construction (parse/render round-trips the key,
// note and recorded lines), so this cannot change what any verify compares —
// it only respells the record. Refresh flags migrate whenever they write, but they need a
// green mutation run, and update needs a *solo* run or it drops flip-insurance
// rows reading TIMED_OUT under load; this task removes that hazard from fleet
// migration entirely. Comment and blank lines pass through verbatim.
val migrateMutationBaselines = tasks.register("migrateMutationBaselines") {
  group = "verification"
  description = "Re-renders every suite's accepted baseline in the current line-less row format; needs no mutation run."
  val names = convergeSuiteNames
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  doLast {
    names.forEach { suiteName ->
      val file = baselineDir.file("$suiteName-accepted.csv").asFile
      if (!file.isFile) {
        return@forEach
      }
      val original = file.readText()
      var migrated = 0
      val rendered = original.split("\n").map { line ->
        if (line.isBlank() || line.trimStart().startsWith("#")) {
          line
        } else {
          val out = BaselineNotes.render(BaselineNotes.parse(line))
          if (out != line) migrated++
          out
        }
      }
      // reassemble exactly (split preserves a trailing empty segment for the
      // final newline), so an already-current file is byte-identical and skipped
      val content = rendered.joinToString("\n")
      if (content == original) {
        logger.lifecycle("pitest baseline '$suiteName': already in the current format")
      } else {
        BaselineFiles.writeAtomically(file, content)
        logger.lifecycle(
            "pitest baseline '$suiteName': migrated $migrated row(s) to the line-less format")
      }
    }
  }
}

// Registered fuzz harnesses live inside the packages they exercise, so a
// package-wildcard suite would otherwise mutate the harness itself. Harness
// mutants are categorically noise — a mutated harness weakens the fuzzer, it can
// never be product risk — so every registered target's class (plus its nested
// classes; lambdas compile into the class itself) is excluded from every suite in
// the module automatically, closing the silent gap when a suite registration
// predates the module's first harness and carries no hand-written '*Fuzz*' row.
val fuzzHarnessExcludes = objects.listProperty<String>()
hardening.fuzz.all {
  HardeningNames.requireSafeName("fuzz target", name)
  // orElse keeps a misconfigured target's blast radius local: targetClass has no
  // convention, and an absent value propagated through the zip would drop the
  // whole --excludedClasses argument — taking every suite's own *Test* exclusions
  // with it, so PIT would mutate the test classes and read as a debt explosion.
  // The target's own fuzz task still fails at execution, which is where the
  // mistake belongs.
  fuzzHarnessExcludes.addAll(targetClass.map { listOf(it, "$it\$*") }.orElse(emptyList()))
}

// Local fuzzing is the canonical execution path. The aggregate is derived directly
// from the registered targets, so it cannot drift the way a hand-written scheduled
// workflow task list can. A successful local run writes a small receipt; the fleet
// wrapper adds repository SHAs and collects those receipts for release review.
val fuzzTargetNames = objects.setProperty<String>()
val maxFuzzTime = providers.gradleProperty("maxFuzzTime").orElse("60")
val localFuzzReceiptFile = layout.buildDirectory.file("hardening/local-fuzz.tsv")
val localFuzzReceiptRunning = layout.buildDirectory.file("hardening/local-fuzz.running")
val localFuzzPluginCode = File(PitestEvidence::class.java.protectionDomain.codeSource.location.toURI())
val localFuzzPluginSha256 = PitestEvidence.fingerprintTree(localFuzzPluginCode)
val validateFuzzBudget = tasks.register("validateFuzzBudget") {
  description = "Internal to local fuzz tasks: validates -PmaxFuzzTime as bounded positive seconds."
  val budget = maxFuzzTime
  inputs.property("maxFuzzTime", budget)
  doLast {
    val raw = budget.get()
    val seconds = raw.toLongOrNull()
    if (!Regex("[1-9][0-9]*").matches(raw) || seconds == null || seconds > Int.MAX_VALUE) {
      throw GradleException(
          "-PmaxFuzzTime must be positive whole seconds without leading zeros, up to " +
              "${Int.MAX_VALUE}; was '$raw' (0 is libFuzzer's run-forever sentinel)")
    }
  }
}
val fuzzAllPreflight = tasks.register("fuzzAllPreflight") {
  description = "Internal to fuzzAll: invalidates earlier aggregate evidence before targets run."
  mustRunAfter("clean")
  val receipt = localFuzzReceiptFile
  val running = localFuzzReceiptRunning
  doLast {
    receipt.get().asFile.delete()
    running.get().asFile.also {
      it.parentFile.mkdirs()
      it.writeText("running\n")
    }
  }
}
validateFuzzBudget.configure { mustRunAfter(fuzzAllPreflight) }
val fuzzAll = tasks.register("fuzzAll") {
  group = "verification"
  description = "Runs every registered fuzz target locally; -PmaxFuzzTime=<seconds> applies to each target."
  dependsOn(validateFuzzBudget)
  dependsOn(fuzzAllPreflight)
  val names = fuzzTargetNames
  val maxTime = maxFuzzTime
  val pluginSha256 = localFuzzPluginSha256
  val receiptProjectPath = project.path
  val receiptFileProvider = localFuzzReceiptFile
  val receiptRunning = localFuzzReceiptRunning
  doFirst {
    if (!receiptRunning.get().asFile.isFile) {
      receiptFileProvider.get().asFile.delete()
      throw GradleException("fuzzAll: preflight did not run; refusing to retain an earlier receipt")
    }
  }
  doLast {
    val receipt = buildString {
      appendLine("schema\t2")
      appendLine("project\t$receiptProjectPath")
      appendLine("pluginSha256\t$pluginSha256")
      appendLine("maxFuzzTimeSeconds\t${maxTime.get()}")
      names.get().sorted().forEach { target ->
        appendLine("target\tfuzz${target.replaceFirstChar(Char::uppercase)}")
      }
    }
    val receiptFile = receiptFileProvider.get().asFile
    BaselineFiles.writeAtomically(receiptFile, receipt)
    receiptRunning.get().asFile.delete()
    logger.lifecycle("fuzzAll: ${names.get().size} local target(s) completed; receipt: $receiptFile")
  }
}
hardening.fuzz.all {
  fuzzTargetNames.add(name)
  val fuzzTaskName = "fuzz" + name.replaceFirstChar(Char::uppercase)
  fuzzAll.configure { dependsOn(fuzzTaskName) }
}

// Compatibility task for consumers that still mention the old workflow check. It no
// longer inspects `.github/workflows/fuzz.yml`: scheduled GitHub fuzzing is optional,
// and `fuzzAll` is the non-drifting local replacement.
val fuzzWorkflowInSync = tasks.register("fuzzWorkflowInSync") {
  group = "verification"
  description = "Deprecated compatibility task; scheduled fuzz workflows are optional. Use fuzzAll locally."
  doLast {
    logger.lifecycle("fuzzWorkflowInSync: scheduled fuzz workflows are optional; run fuzzAll locally")
  }
}

// Every suite's mutation scope, keyed by suite name: each suite's exclusion
// audit subtracts the classes its siblings actually mutate, so the targeting
// policy's "owned by another suite" handoffs read as ownership rather than
// swallowed production classes *(casebook: the partition the audit called a
// hole)*. Values are providers; by execution time every suite is registered.
val suiteTargetGlobs = objects.mapProperty<String, List<String>>()
val suiteExcludedGlobs = objects.mapProperty<String, List<String>>()
val suiteDeclinedExclusions = objects.mapProperty<String, Map<String, String>>()

// Whole-population ownership closes the gap a per-suite exclusion audit cannot see:
// production that no target glob selects at all. It is a dedicated task for the
// developer loop and a mandatory leg of strict certification; ordinary qualityGate
// remains focused on the suites a project has already adopted.
val mutationOwnershipAudit = tasks.register("mutationOwnershipAudit") {
  group = "verification"
  description = "Fails when a compiled production class has no mutation-suite owner or argued exclusion decline."
  dependsOn(compileForPitest)
  val classesDir = mutationClassesDir
  val targets = suiteTargetGlobs
  val exclusions = suiteExcludedGlobs
  val declines = suiteDeclinedExclusions
  val ownershipTestSourceDirs = sourceSets.test.get().java.srcDirs
  doLast {
    val production = ExclusionAudit.productionClassNames(classesDir.get().asFile, ownershipTestSourceDirs)
    val scopes = targets.get().entries.sortedBy { it.key }.map { (suiteName, globs) ->
      ExclusionAudit.OwnershipScope(suiteName, globs, exclusions.get()[suiteName].orEmpty())
    }
    val explicitDeclines = declines.get().entries.sortedBy { it.key }.flatMap { (suiteName, records) ->
      records.entries.sortedBy { it.key }.map { (glob, reason) ->
        ExclusionAudit.ExplicitDecline(suiteName, glob, reason)
      }
    }
    val coverage = ExclusionAudit.ownershipCoverage(production, scopes, explicitDeclines)
    val problems = buildList {
      if (coverage.uncovered.isNotEmpty()) {
        add("${coverage.uncovered.size} production class(es) have no effective mutation-suite owner:")
        coverage.uncovered.forEach { uncovered ->
          val why = if (uncovered.excludedBy.isEmpty()) "matched by no suite target" else
            "excluded by " + uncovered.excludedBy.joinToString { "${it.suiteName}:${it.glob}" }
          add("  ${uncovered.binaryName} — $why")
        }
      }
      if (coverage.blankDeclines.isNotEmpty()) {
        add("${coverage.blankDeclines.size} ownership decline(s) have no reason:")
        coverage.blankDeclines.forEach { add("  ${it.suiteName}:${it.glob}") }
      }
      if (coverage.staleDeclines.isNotEmpty()) {
        add("${coverage.staleDeclines.size} ownership decline(s) no longer waive an unowned class:")
        coverage.staleDeclines.forEach { add("  ${it.suiteName}:${it.glob}") }
      }
    }
    if (problems.isNotEmpty()) {
      throw GradleException(
          "mutationOwnershipAudit: the production mutation population is incomplete:\n" +
              problems.joinToString("\n") +
              "\nTarget each class in a suite, or exclude it from a matched target and record " +
              "declineExclusionAudit with the measured reason/correctness owner.")
    }
    logger.lifecycle(
        "mutationOwnershipAudit: ${coverage.owned.size} production class(es) owned, " +
            "${coverage.declined.size} explicitly declined")
  }
}
hardeningCertify.configure { dependsOn(mutationOwnershipAudit) }

hardening.mutation.all {
  val suite = this
  HardeningNames.requireSafeName("mutation suite", suite.name)
  suite.mutators.convention("STRONGER")
  suite.threads.convention(4)
  // PIT's own defaults; see MutationSuite.timeoutFactor for tuning guidance
  suite.timeoutFactor.convention(1.25)
  suite.timeoutConst.convention(4000L)
  suite.excludedClasses.convention(emptyList())
  // the registration's own exclusions plus every registered fuzz harness; feeds
  // both the PIT argument and the mutator-blindness scan so the two cannot drift
  val allExcludedClasses = suite.excludedClasses.zip(fuzzHarnessExcludes) { excluded, harnesses ->
    (excluded + harnesses).distinct()
  }
  suiteTargetGlobs.put(suite.name, suite.targetClasses)
  suiteExcludedGlobs.put(suite.name, allExcludedClasses)
  suiteDeclinedExclusions.put(suite.name, suite.declinedExclusionAudits)

  // Mutation ratchet: after each 'pitest<Name>' run, diff the unkilled mutants
  // (SURVIVED and NO_COVERAGE) against the checked-in baseline at
  // 'config/pitest/<name>-accepted.csv' and fail on anything new. A fresh
  // mutant must be killed with a test or knowingly accepted by re-running with
  // '-PupdateMutationBaseline' and documenting the reason (see HARDENING.md).
  val pitestTaskName = "pitest" + suite.name.replaceFirstChar(Char::uppercase)
  val suiteName = suite.name
  // Inputs bound into the completed-report manifest. A direct verify is allowed to
  // reuse a report only while these fingerprints still match; changing source,
  // compiled classes, dependencies, suite targeting, or tool/plugin bytes makes the
  // old report stale instead of silently authoritative.
  val evidenceProjectDir = layout.projectDirectory.asFile
  val evidenceSourceFiles = files(
      sourceSets.main.get().allSource,
      sourceSets.test.get().allSource,
      layout.projectDirectory.file("build.gradle.kts"),
      rootProject.layout.projectDirectory.file("settings.gradle.kts"),
  )
  val evidenceClassFiles = fileTree(mutationClassesDir)
  val pitestBuildDirPath = layout.buildDirectory.get().asFile.absolutePath + File.separator
  val pitestResourceDirs = files(
      sourceSets.main.get().output.resourcesDir!!,
      sourceSets.test.get().output.resourcesDir!!,
  )
  // Mirror the JavaExec classpath exactly: processed resources and dependencies,
  // excluding this project's conventional class outputs (the recompiled root above
  // replaces them). Hashing those unused outputs made a later ordinary `test`
  // compilation invalidate evidence for bytes PIT never loaded.
  val evidenceClasspathFiles = files(
      pitestResourceDirs,
      configurations["testRuntimeClasspath"],
  ).filter { file ->
    pitestResourceDirs.any { it.absolutePath == file.absolutePath } ||
        !file.absolutePath.startsWith(pitestBuildDirPath)
  }
  val evidencePluginCode = File(PitestEvidence::class.java.protectionDomain.codeSource.location.toURI())
  val evidenceJavaLauncher = javaToolchains.launcherFor(java.toolchain)
  // Hoisted so the two helpers below read only locals. Both run from inside task
  // actions and argument providers, and a lambda that touches a script-level
  // declaration — the 'hardening' extension, the 'pitest' configuration accessor,
  // 'project' — captures the precompiled script object, which the configuration
  // cache cannot serialize. That failure is not scoped to the offending task: it
  // takes the whole PIT surface down in any consumer with the cache on, which is
  // every consumer whose 'check' stores an entry.
  val evidencePitestVersion = hardening.pitestVersion
  val evidenceJunitPluginVersion = hardening.pitestJunit5PluginVersion
  val evidenceMutationRelease = hardening.mutationBytecodeRelease
  val evidenceRecompileExcludes = hardening.recompileExcludes
  val evidenceToolFiles = files(pitest)
  val evidenceTargetClasses = suite.targetClasses
  val evidenceExcludedClasses = allExcludedClasses
  val evidenceTargetTests = suite.targetTests
  val evidenceMutators = suite.mutators
  val evidenceThreads = suite.threads
  val evidenceTimeoutFactor = suite.timeoutFactor
  val evidenceTimeoutConst = suite.timeoutConst
  val evidenceConfigurationText = providers.provider {
    buildString {
      appendLine("targetClasses=${evidenceTargetClasses.get().sorted().joinToString(",")}")
      appendLine("excludedClasses=${evidenceExcludedClasses.get().sorted().joinToString(",")}")
      appendLine("targetTests=${evidenceTargetTests.get()}")
      appendLine("mutators=${evidenceMutators.get()}")
      appendLine("threads=${evidenceThreads.get()}")
      appendLine("timeoutFactor=${evidenceTimeoutFactor.get()}")
      appendLine("timeoutConst=${evidenceTimeoutConst.get()}")
      appendLine("mutationBytecodeRelease=${evidenceMutationRelease.get()}")
      appendLine("recompileExcludes=${evidenceRecompileExcludes.get().sorted().joinToString(",")}")
      // File content is fingerprinted separately, but classpath order changes JVM
      // shadowing semantics and therefore belongs to the suite configuration too.
      appendLine(
          "classpathOrderSha256=" + PitestEvidence.sha256(
              evidenceClasspathFiles.files.joinToString("\n") { it.absoluteFile.normalize().path }))
      appendLine(
          "toolClasspathOrderSha256=" + PitestEvidence.sha256(
              evidenceToolFiles.files.joinToString("\n") { it.absoluteFile.normalize().path }))
    }
  }
  fun evidenceSnapshot(
    invocationId: String,
    reportSha256: String,
    scope: String,
    historyAssisted: Boolean,
    javaVersion: String,
  ) = PitestEvidence(
      suite = suiteName,
      invocationId = invocationId,
      pitestVersion = evidencePitestVersion.get(),
      junitPluginVersion = evidenceJunitPluginVersion.get(),
      pluginSha256 = PitestEvidence.fingerprintTree(evidencePluginCode),
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = javaVersion,
      sourceSha256 = PitestEvidence.fingerprint(evidenceProjectDir, evidenceSourceFiles.files),
      classesSha256 = PitestEvidence.fingerprint(evidenceProjectDir, evidenceClassFiles.files),
      classpathSha256 = PitestEvidence.fingerprint(evidenceProjectDir, evidenceClasspathFiles.files),
      toolClasspathSha256 = PitestEvidence.fingerprint(evidenceProjectDir, evidenceToolFiles.files),
      configurationSha256 = PitestEvidence.sha256(evidenceConfigurationText.get()),
      reportSha256 = reportSha256,
      scope = scope,
      historyAssisted = historyAssisted,
  )
  val evidenceProjectPath = project.path
  val evidenceCertificationSession = hardeningCertificationSession
  val evidenceCertificationRunning = certificationReceiptRunning
  val evidenceReportFile = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
  val evidenceManifestFile = layout.buildDirectory.file("reports/pitest/$suiteName/.evidence.tsv")

  // Recompute live evidence only after every dependency has completed. A Provider
  // captured by an aggregate task is realized while Gradle stores the configuration
  // cache, before generated sources and compileForPitest exist, and therefore freezes
  // a SHA-256(empty) classes fingerprint into the cache entry.
  hardeningCertify.configure {
    doFirst {
      if (!evidenceCertificationSession.get().isActive(evidenceProjectPath) ||
          !evidenceCertificationRunning.get().asFile.isFile) return@doFirst
      val report = evidenceReportFile.get().asFile
      val reportDir = report.parentFile
      val manifest = evidenceManifestFile.get().asFile
      if (!report.isFile || !manifest.isFile ||
          reportDir.resolve(".running").isFile ||
          reportDir.resolve(".scoped").isFile ||
          reportDir.resolve(".history-assisted").isFile) return@doFirst
      val recorded = try {
        PitestEvidence.parse(manifest.readText())
      } catch (_: IllegalArgumentException) {
        return@doFirst
      }
      // The main certification action owns the specific diagnostic. Do not let
      // self-reported reuse/scope values become the expected current state in this
      // freshness comparison.
      if (recorded.scope != PitestEvidence.FULL_SCOPE || recorded.historyAssisted) return@doFirst
      val current = evidenceSnapshot(
          invocationId = recorded.invocationId,
          // The main certification action owns the more specific report-tamper error.
          reportSha256 = recorded.reportSha256,
          scope = recorded.scope,
          historyAssisted = recorded.historyAssisted,
          javaVersion = evidenceJavaLauncher.get().metadata.javaRuntimeVersion,
      )
      val differences = recorded.differences(current)
      if (differences.isNotEmpty()) {
        throw GradleException(
            "hardeningCertify: '$suiteName' inputs changed after verification — refusing to " +
                "commit a stale receipt:\n" + differences.joinToString("\n") { "  $it" })
      }
    }
  }
  val evidenceMode = pitestModeProperty
  pitestModeSnapshot.configure {
    doFirst {
      val label = evidenceMode.orNull ?: return@doFirst
      if (!HardeningNames.isSafeName(label)) return@doFirst
      val report = evidenceReportFile.get().asFile
      val reportDir = report.parentFile
      val manifest = evidenceManifestFile.get().asFile
      if (!report.isFile || !manifest.isFile ||
          reportDir.resolve(".running").isFile ||
          reportDir.resolve(".scoped").isFile ||
          reportDir.resolve(".history-assisted").isFile) return@doFirst
      val recorded = try {
        PitestEvidence.parse(manifest.readText())
      } catch (_: IllegalArgumentException) {
        return@doFirst
      }
      if (recorded.scope != PitestEvidence.FULL_SCOPE || recorded.historyAssisted) return@doFirst
      val current = evidenceSnapshot(
          invocationId = recorded.invocationId,
          reportSha256 = PitestEvidence.sha256(report),
          scope = recorded.scope,
          historyAssisted = recorded.historyAssisted,
          javaVersion = evidenceJavaLauncher.get().metadata.javaRuntimeVersion,
      )
      val differences = recorded.differences(current)
      if (differences.isNotEmpty()) {
        throw GradleException(
            "pitestModeSnapshot: '$suiteName' report/evidence pair no longer matches the " +
                "current build:\n" + differences.joinToString("\n") { "  $it" })
      }
    }
  }
  // computed once and shared by every advisory-recording task in the suite: the
  // end-of-build summary groups findings by this string, so two drifting copies
  // would split one suite's findings across two scope headings
  val suiteAdvisoryScope = (if (project.path == ":") "" else "${project.path} ") + "pitest '$suiteName'"
  val verify = tasks.register("${pitestTaskName}Verify") {
    group = "verification"
    description = "Fails when the '$suiteName' PIT run left unkilled mutants missing from config/pitest/$suiteName-accepted.csv."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val xmlProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.xml")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val timeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val update = providers.gradleProperty("updateMutationBaseline").isPresent
    val union = providers.gradleProperty("unionMutationBaseline").isPresent
    val prune = providers.gradleProperty("pruneMutationBaseline").isPresent
    val listUnkilled = providers.gradleProperty("listUnkilled").isPresent
    val initTimeoutAudit = providers.gradleProperty("initTimeoutAudit").isPresent
    val strictTimeoutAuditRequested = providers.gradleProperty("strictTimeoutAudit").isPresent
    val statusStashFile = layout.projectDirectory.file(".pitest-history/$suiteName.statuses").asFile
    val timeoutQuietFile = layout.projectDirectory.file(".pitest-history/$suiteName.timeout-quiet").asFile
    val toolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val pitToolVersion = hardening.pitestVersion
    // captured locally so the doLast lambda does not hold the script instance
    val advisoryLog = hardeningAdvisoryLog
    val certificationSession = hardeningCertificationSession
    val advisoryScope = suiteAdvisoryScope
    usesService(advisoryLog)
    usesService(certificationSession)
    // Resolved at configuration time so the scaffolding check below can ask whether a
    // mutated class is one of this project's own test sources.
    val testSourceDirs = sourceSets.test.get().java.srcDirs
    doLast {
      val certificationActive = certificationSession.get().isActive(evidenceProjectPath)
      val strictTimeoutAudit = strictTimeoutAuditRequested || certificationActive
      val csv = csvProvider.get().asFile
      // The '.running' sentinel is written before PIT starts and cleared only
      // after a clean exit, so a report left by a crashed or interrupted run —
      // PIT writes the CSV incrementally, so a partial file looks complete —
      // is refused as evidence instead of read as a smaller population. Without
      // it, this verify runs as the failed task's finalizer and a same-invocation
      // '-PpruneMutationBaseline' rewrites the baseline from whatever fraction of
      // the population PIT reached before dying.
      if (csv.parentFile.resolve(".running").isFile && csv.exists()) {
        throw GradleException(
            "pitest '$suiteName': the report at ${csv.parentFile} was left by an interrupted or " +
                "failed run — a partial population is not evidence, for the ratchet or for any " +
                "refresh flag. Re-run $pitestTaskName."
        )
      }
      if (!csv.exists()) {
        // As a finalizer this also fires when the pitest task itself just failed,
        // in which case the missing report is a symptom — don't let this message
        // bury the real error printed above it. A PIT MINION_DIED / coverage
        // socket-timeout failure is a known transient: re-run the suite.
        throw GradleException(
            "no PIT report at $csv — either $pitestTaskName has not run, or it just " +
                "failed before writing one (its error is above this; MINION_DIED " +
                "coverage failures are transient — re-run the suite). If the output " +
                "above lacks the cause, the daemon log keeps the minion's stack trace: " +
                "~/.gradle/daemon/<version>/daemon-<pid>.out.log"
        )
      }
      val writingRecord = update || union || prune || initTimeoutAudit
      val completedEvidenceFile = csv.parentFile.resolve(".evidence.tsv")
      var verifiedEvidence: PitestEvidence? = null
      if (!completedEvidenceFile.isFile) {
        val message = "pitest '$suiteName': report has no completed-run evidence manifest at " +
            "$completedEvidenceFile — it may predate this plugin or be a detached/stale report; " +
            "run $pitestTaskName to bind it to the current code and configuration"
        // N-1 migration path: old reports have no manifest. A writer may consume one
        // only when every visible source/config input predates the report; this keeps
        // existing checked-out reports usable for one upgrade while refusing the
        // common stale-report footgun immediately. Certification never accepts the
        // heuristic — a fresh run earns a real manifest.
        val newerInputs = evidenceSourceFiles.files.filter { it.isFile && it.lastModified() > csv.lastModified() }
        if (certificationActive || (writingRecord && newerInputs.isNotEmpty())) {
          throw GradleException(
              message + if (newerInputs.isEmpty()) "" else
                  "\n  newer input(s):\n" + newerInputs.sortedBy { it.path }
                      .joinToString("\n") { "  $it" })
        }
        logger.warn(message)
        advisoryLog.get().record(advisoryScope, "report has no completed-run evidence manifest")
        if (writingRecord) {
          try {
            certificationSession.get().requireNoIncompleteAttempt(
                evidenceProjectPath, suiteName, null)
          } catch (e: IllegalStateException) {
            throw GradleException("pitest '$suiteName': ${e.message}", e)
          }
        }
      } else {
        val recordedEvidence = try {
          PitestEvidence.parse(completedEvidenceFile.readText())
        } catch (e: IllegalArgumentException) {
          throw GradleException(
              "pitest '$suiteName': malformed completed-run evidence manifest at $completedEvidenceFile — " +
                  "${e.message}; re-run $pitestTaskName", e)
        }
        val scopeMarker = csv.parentFile.resolve(".scoped")
        val expectedEvidence = evidenceSnapshot(
            invocationId = recordedEvidence.invocationId,
            reportSha256 = PitestEvidence.sha256(csv),
            scope = scopeMarker.takeIf { it.isFile }?.readText()?.trim().orEmpty()
                .ifEmpty { PitestEvidence.FULL_SCOPE },
            historyAssisted = csv.parentFile.resolve(".history-assisted").isFile,
            javaVersion = evidenceJavaLauncher.get().metadata.javaRuntimeVersion,
        )
        val differences = recordedEvidence.differences(expectedEvidence)
        if (differences.isNotEmpty()) {
          throw GradleException(
              "pitest '$suiteName': completed report evidence no longer matches the current build — " +
                  "a stale report cannot verify or rewrite mutation state; re-run $pitestTaskName:\n" +
                  differences.joinToString("\n") { "  $it" })
        }
        verifiedEvidence = recordedEvidence
        if (writingRecord) {
          try {
            certificationSession.get().requireNoIncompleteAttempt(
                evidenceProjectPath, suiteName, recordedEvidence)
          } catch (e: IllegalStateException) {
            throw GradleException("pitest '$suiteName': ${e.message}", e)
          }
        }
      }
      // The mutant population is a function of PIT itself (whose default version rides
      // plugin bumps via Dependabot), so the baseline record is only comparable to runs
      // from the version that wrote it. The recorded version lives in
      // config/pitest/<suite>-pitest-version — per suite, because the record it certifies
      // is per-suite: one shared file would lift every suite's refusal at the first
      // refresh, silently certifying the rest against a version that never wrote them.
      // A mismatch warns on a checking run and refuses a writing one — reading a
      // possibly-divergent result is a judgment call, writing the record with one is
      // not. No file means a record predating this check, adopted when the next
      // baseline write *succeeds*: the stamp lands with the write it describes, never
      // ahead of it, so a refresh that dies mid-path cannot leave a stamp vouching
      // for a record it never rewrote. -PinitTimeoutAudit is refused across a bump
      // like the baseline flags — the timeout population is just as version-dependent
      // — but never stamps: it writes the timeout set, not the baseline, and its
      // stamp would silently vouch for a baseline some older PIT wrote.
      val currentPit = pitToolVersion.get()
      val recordedPit = toolVersionFile.takeIf { it.isFile }?.readText()?.trim()
      if (recordedPit != null && recordedPit != currentPit) {
        if (writingRecord || certificationActive) {
          throw GradleException(
              "pitest '$suiteName': the baseline record was written by PIT $recordedPit but this run used " +
                  "PIT $currentPit — refusing to rewrite the record across a tool bump, whose population " +
                  "churn would be indistinguishable from code churn. To bump deliberately: set " +
                  "config/pitest/$suiteName-pitest-version to $currentPit, then refresh the suite and read the " +
                  "resulting churn as a real population diff, not noise."
          )
        }
        val versionWarning = "pitest '$suiteName': baseline record written by PIT $recordedPit, this run " +
            "used PIT $currentPit — population differences may be the tool, not the code, and the " +
            "record-writing flags refuse until config/pitest/$suiteName-pitest-version is updated deliberately"
        logger.warn(versionWarning)
        advisoryLog.get().record(advisoryScope, "baseline written by PIT $recordedPit, ran $currentPit")
      }
      // Called by each baseline-writing path at its successful end. A no-op rewrite
      // (prune dropped nothing, union added nothing) stamps too: the path just
      // verified the committed record against a report this PIT produced, which is
      // the comparability the stamp asserts.
      fun stampToolVersion() {
        if (recordedPit == null) {
          BaselineFiles.writeAtomically(toolVersionFile, currentPit + "\n")
        }
      }
      // The delete-side counterpart, for a refresh path that ends with no baseline
      // file: unless an audited timeout set remains for the stamp to certify, it is
      // retired with the record — "no record and an empty record must read the same
      // way" extends to the stamp, and an orphan stamp would refuse a future first
      // refresh across a PIT bump citing a baseline that no longer exists.
      fun stampOrRetireToolVersion() {
        if (timeoutsFile.isFile) {
          stampToolVersion()
        } else if (toolVersionFile.isFile) {
          toolVersionFile.delete()
          logger.lifecycle(
              "pitest baseline '$suiteName': ${toolVersionFile.name} removed with the record it certified")
        }
      }
      // One interpretation of the report: every column split, key derivation, and
      // status semantic lives on Mutant/MutantStatus — the casebook's recurring
      // incident class is a mutant read differently at two sites, and a per-site
      // parts[] index is how that class reproduces.
      val rows = Mutant.parseReport(csv.readLines())

      val byStatus = rows.groupingBy { it.rawStatus }.eachCount()
      val total = rows.size
      // TIMED_OUT counts as detected, the same as PIT's own summary — a mutant that
      // hangs the suite was caught. Reported separately because that detection is
      // load-dependent: the same row can read SURVIVED when the suite runs alone.
      val timedOut = byStatus["TIMED_OUT"] ?: 0
      val detected = rows.count { it.detected }
      // Rounded down deliberately: a coverage figure should never read better than it
      // is, so 441/498 is 88% here and not 89%. PIT's own summary line rounds, so this
      // can sit one point below it — the counts either side of the slash are the same.
      val percent = if (total == 0) 0 else detected * 100 / total
      val split = buildList {
        MutantStatus.entries.filter { it.gated }.forEach { s ->
          byStatus[s.name]?.let { add("$it ${s.name.lowercase()}") }
        }
        if (timedOut > 0) add("$timedOut timed out (load-dependent)")
        // Valid terminal outcomes the plugin deliberately does not score as
        // detected (NON_VIABLE and EQUIVALENT) stay visible in the summary.
        // Error, unfinished and unknown statuses were rejected by parseReport:
        // they cannot certify either this summary or a record writer.
        byStatus.keys
            .filterNot { MutantStatus.of(it)?.let { s -> s.detected || s.gated } == true }
            .sorted()
            .forEach { s -> add("${byStatus.getValue(s)} ${s.lowercase()} (not counted as detected)") }
      }
      // With incremental analysis some of these statuses were reused, not
      // re-earned this run — the tag keeps the two kinds of number distinct.
      // Read from the report's own '.history-assisted' marker, not the
      // configuration-time flag: the report on disk may predate this
      // invocation's settings in either direction (a '-PnoMutationHistory'
      // rerun after an assisted one, or the reverse), and the tag describes
      // the REPORT.
      val historyAssistedReport = csv.parentFile.resolve(".history-assisted").isFile
      logger.lifecycle(
          "pitest '$suiteName': $detected/$total detected ($percent%)" +
              (if (split.isEmpty()) "" else " — ${split.joinToString(", ")}") +
              (if (historyAssistedReport) " [history]" else "")
      )

      // A suite whose exclusions miss a test-source class mutates its own scaffolding:
      // the population inflates and the survivors are triaged as if they were production
      // code. Shared fakes are named for their role (RecordingFoo, StubFoo, FooDriftCheck)
      // so a '*Test*' exclusion does not match them. Warned rather than failed: an
      // existing repo upgrading the plugin has these accepted in its baseline already.
      val scaffolding = rows.asSequence()
          .map { it.className }
          .distinct()
          .filter { fqcn ->
            val relative = fqcn.substringBefore('$').replace('.', '/') + ".java"
            testSourceDirs.any { dir -> dir.resolve(relative).isFile }
          }
          .sorted()
          .toList()
      if (scaffolding.isNotEmpty()) {
        logger.warn(
            "pitest '$suiteName': mutating ${scaffolding.size} test-source class(es) — " +
                "add to excludedClasses:\n" + scaffolding.joinToString("\n") { "  $it" }
        )
        advisoryLog.get().record(
            advisoryScope, "${scaffolding.size} mutated test-source class(es)")
      }

      // PIT's CSV omits the mutation description — which sub-condition of a line was hit,
      // which direction a conditional was forced — and triaging an unkilled row keeps
      // needing exactly that. The XML report carries it; keyed like the baseline rows
      // (line-less), with each mutant's line folded into the description text, since
      // the key no longer carries it.
      val descriptions: Map<String, String> by lazy {
        val xml = xmlProvider.get().asFile
        if (!xml.isFile) return@lazy emptyMap()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(xml)
        val mutations = doc.getElementsByTagName("mutation")
        val collected = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until mutations.length) {
          val mutation = mutations.item(i) as org.w3c.dom.Element
          fun text(tag: String) = mutation.getElementsByTagName(tag).item(0)?.textContent ?: ""
          val key = listOf(
              text("mutatedClass"), text("mutatedMethod"),
              text("mutator").substringAfterLast('.')
          ).joinToString(",")
          val description = "line ${text("lineNumber")}: ${text("description")}"
          // keyed both with and without status, so a row still annotates when its
          // status differs from the XML the descriptions came from
          collected.getOrPut("$key,${mutation.getAttribute("status")}") { mutableListOf() }.add(description)
          collected.getOrPut(key) { mutableListOf() }.add(description)
        }
        collected.mapValues { (_, all) -> all.distinct().joinToString(" | ") }
      }
      fun describe(row: String) =
          (descriptions[row] ?: descriptions[row.substringBeforeLast(',')])?.let { " — $it" } ?: ""

      // Kept as a LIST, not a set: a compound condition yields several mutants with
      // identical (class, method, mutator, status) keys — one per operand, branch
      // direction, or occurrence in the method. Collapsing them to a set once let a
      // killed sibling regress to SURVIVED invisibly, absorbed by its already-accepted
      // twin's row. All comparisons below are multiset comparisons for the same reason.
      //
      // The key is line-less on purpose — line numbers are metadata, not identity
      // (the audited-timeout convention, extended to the baseline): editing above a
      // mutated method can never churn the ratchet, at the documented price that a
      // new mutant of an already-accepted key is visible only as a count change.
      // Observed lines ride alongside for line tags, sibling hints, and the
      // line-drift advisory.
      val currentWithLines = rows.mapNotNull { mutant ->
        if (!mutant.gated) null else mutant.baselineKey to mutant.lineText
      }
      val current = currentWithLines.map { it.first }.sorted()
      val currentLines: Map<String, List<String>> =
          currentWithLines.groupBy({ it.first }, { it.second })
      fun multisetDiff(a: List<String>, b: List<String>): List<String> =
          BaselineEngine.multisetDiff(a, b)

      // Same-line siblings of the same mutator FAMILY that ARE detected
      // disambiguate a survivor's direction: the survivor is the opposite branch or
      // operand of whatever the killing test pinned (see HARDENING.md on triaging
      // RemoveConditional pairs). Family = the name before the _EQUAL_IF/_ORDER_ELSE
      // style suffix, so the IF/ELSE cross-pair is matched too.
      fun mutatorFamily(mutator: String) = mutator.substringBefore('_')
      val detectedSiblings: Map<String, List<String>> = rows
          .filter { it.detected }
          .groupBy(
              { it.familyLineKey },
              {
                val test = Regex("method:([^(\\]]+)").find(it.killerText)?.groupValues?.get(1)
                if (it.status == MutantStatus.KILLED && test != null) {
                  "${it.mutatorSimpleName} KILLED by $test"
                } else {
                  "${it.mutatorSimpleName} ${it.rawStatus}"
                }
              }
          )
      fun siblingHint(row: String): String {
        // the row's key is line-less, but the survivor's own observed lines are in the
        // report — the hint stays per-line, since that is where the disambiguation lives
        if (!row.endsWith(",SURVIVED")) return ""
        val parts = row.split(',')
        val siblings = currentLines[row].orEmpty().distinct().flatMap { line ->
          detectedSiblings["${parts[0]},${parts[1]},$line,${mutatorFamily(parts[2])}"].orEmpty()
        }
        if (siblings.isEmpty()) return ""
        return " [detected sibling at this line: ${siblings.distinct().joinToString("; ")}]"
      }
      if (listUnkilled && current.isNotEmpty()) {
        // the sibling hint does the coordinate disambiguation the CSV cannot: for a
        // survivor at a coordinate whose twin was detected, it names the twin's killer,
        // so the triager reads the surviving direction off the build output instead of
        // reconstructing it from mutations.xml (casebook: the sibling guessed wrong
        // three times)
        logger.lifecycle(
            "pitest '$suiteName' unkilled:\n" +
                current.joinToString("\n") { row -> "  $row${describe(row)}${siblingHint(row)}" }
        )
      }

      // A scoped run mutated a hand-picked subset: its report is an iteration aid,
      // not evidence about the suite. List what is still unkilled inside the scope
      // and stop — no ratchet, no status stash, and certainly no baseline writes.
      val scopedMarkerFile = csv.parentFile.resolve(".scoped")
      if (scopedMarkerFile.isFile) {
        val scope = scopedMarkerFile.readText().trim()
        // prune and the timeout-audit seed included: the early return below already
        // keeps them from touching anything, but silently no-opping a requested
        // refresh (or seeding an audited set from a hand-picked subset's timeouts)
        // reads as work that happened — refuse them the same way as update and union.
        if (update || union || prune || initTimeoutAudit) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot refresh the baseline or seed the timeout audit. " +
                  "Re-run $pitestTaskName without -PmutateOnly first."
          )
        }
        // Certifying flags are refused for the same reason in the other direction:
        // the checks they strengthen are skipped entirely on a scoped report, so a
        // green run would read as a certification of the suite when nothing was
        // certified at all. (-PnoDriftTolerance used to sit beside this flag; the
        // line-less key retired it — there is no drift tolerance left to disable.)
        if (strictTimeoutAudit) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot be certified, and the certifying checks are skipped on a scoped " +
                  "report. Re-run $pitestTaskName without -PmutateOnly first."
          )
        }
        logger.lifecycle(
            "pitest '$suiteName': SCOPED run (-PmutateOnly=$scope) — ratchet skipped; " +
                (if (current.isEmpty()) "nothing unkilled in scope"
                else "${current.size} unkilled in scope:\n" +
                    current.joinToString("\n") { row -> "  $row${describe(row)}${siblingHint(row)}" })
        )
        return@doLast
      }
      // A history-assisted report is reuse, not observation — the same reason
      // pitestModeSnapshot refuses to stash one as a mode's evidence. The checking
      // path may read it (the summary's '[history]' tag names the reuse), but the
      // record-writing flags may not: a baseline refresh or an audit seed written
      // from reused results certifies numbers this run never earned.
      if (historyAssistedReport && (update || union || prune || initTimeoutAudit)) {
        throw GradleException(
            "pitest '$suiteName': the report is arcmutate-history-assisted — reused results are " +
                "not observation, and a baseline refresh or audit seed needs a full run. " +
                "Re-run $pitestTaskName with -PnoMutationHistory first."
        )
      }
      // A baseline row may carry a trailing '# note' ('# untriaged' is the conventional
      // label for seeded debt; refreshes seed it on every new row) and a trailing
      // '# line' tag (metadata for triage and the line-drift advisory, never identity).
      // Notes are stripped for comparison, preserved across the refresh flags, and
      // counted per label — so triage state lives on the row it describes and stays a
      // number the build reports, not prose that drifts. Rows are parsed as an ordered
      // LIST of (key, note, lines): duplicate keys are sibling mutants and each keeps
      // its own note, which a note map keyed by row text used to collapse.
      // Comment lines are recognized after trimming — an INDENTED '# ...' line used
      // to parse as a phantom row that matched nothing and read as since-killed —
      // and a malformed row is named instead of silently becoming a key no mutant
      // can match (the timeout membership's malformed-row diagnosis, applied to the
      // file it always should have covered too).
      val baselineLines = if (baselineFile.exists()) baselineFile.readLines() else emptyList()
      val baselineCommentLines = baselineLines.filter {
        it.isNotBlank() && it.trimStart().startsWith("#")
      }
      val baselineRowLines = baselineLines.filter {
        it.isNotBlank() && !it.trimStart().startsWith("#")
      }
      val malformedBaselineRows = baselineRowLines.filter { BaselineNotes.malformed(it) }
      if (malformedBaselineRows.isNotEmpty()) {
        logger.warn(
            "pitest baseline '$suiteName': ${malformedBaselineRows.size} malformed row(s) in " +
                "${baselineFile.name} — expected 'class,method,mutator,STATUS [# note] [# line N]' " +
                "(legacy five-field rows still parse); a malformed row matches no mutant, reads as " +
                "since killed, and a refresh would silently drop it:\n" +
                malformedBaselineRows.joinToString("\n") { "  $it" }
        )
        // recorded only when it stays advisory: on a refresh run the same finding
        // becomes the failure below, and the end-of-build summary's "none failed
        // the build" must stay true (the strict-flag sites' own rule)
        if (!(update || union || prune)) {
          advisoryLog.get().record(advisoryScope, "${malformedBaselineRows.size} malformed baseline row(s)")
        } else {
          throw GradleException(
              "pitest baseline '$suiteName': ${baselineFile.name} carries " +
                  "${malformedBaselineRows.size} malformed row(s) (listed above) — a refresh rewrite " +
                  "would silently drop them. Fix the row shape first."
          )
        }
      }
      val acceptedRows: List<BaselineNotes.Row> = baselineRowLines
          .filterNot { BaselineNotes.malformed(it) }
          .map { BaselineNotes.parse(it) }
      val accepted: List<String> = acceptedRows.map { it.key }
      // The line-less 'class,method,mutator' coordinate is Mutant.coordinate —
      // one derivation for the audited-timeout key, prune's TIMED_OUT keep, the
      // stale-entry hint, and the drift stash, so no two sites can carry their
      // own copy of the key shape and drift apart.
      // Per-label breakdown so triage state is a number the build prints (BaselineNotes
      // owns the label semantics: carry/flip parentheticals stripped, unlabeled rows —
      // which predate seeding — named rather than folded into a bucket).
      BaselineNotes.summarize(acceptedRows.mapNotNull { it.note }, acceptedRows.count { it.note == null })
          ?.let { logger.lifecycle("pitest baseline '$suiteName': ${accepted.size} rows — $it") }
      // A family label is a pointer to its argument in config/pitest/README.md (the rule
      // and its message live in BaselineNotes, so this and 'Debt' resolve labels the same
      // way). Warned rather than failed, mirroring the scaffolding check: the gap may
      // predate this check, and a fresh '-PunionModeFlips' row legitimately lands before
      // its README criterion is written.
      val undocumentedLabels = BaselineNotes.undocumentedLabels(acceptedRows.mapNotNull { it.note }) {
        readmeFile.takeIf { it.isFile }?.readText() ?: ""
      }
      if (undocumentedLabels.isNotEmpty()) {
        logger.warn(BaselineNotes.undocumentedLabelWarning(suiteName, undocumentedLabels))
        advisoryLog.get().record(advisoryScope, "${undocumentedLabels.size} undocumented family label(s)")
      }

      // The refresh flavours answer different questions and are mutually exclusive —
      // the audit seed included, which writes a file the same way the baseline
      // flavours do. Checked before the drift stash below and before any flavour
      // writes (the seed is the first), so a refused combination leaves nothing
      // half done AND consumes nothing: the stash rewrite used to land before
      // this refusal, so a refused invocation still spent the drift comparison's
      // previous state, and the next legitimate run compared against the refused
      // run instead of the last meaningful one.
      if (listOf(update, union, prune, initTimeoutAudit).count { it } > 1) {
        throw GradleException(
            "pass at most one of -PupdateMutationBaseline, -PunionMutationBaseline, " +
                "-PpruneMutationBaseline, -PinitTimeoutAudit — they answer different questions (see HARDENING.md)."
        )
      }

      // Timed-out drift vs the previous run. TIMED_OUT counts as detected, but the
      // benign flavour (KILLED<->TIMED_OUT under load) and the dangerous ones
      // (SURVIVED->TIMED_OUT: a mutant nobody killed now reads as detected purely
      // because its tests ran slowly; NO_COVERAGE->TIMED_OUT: a mutant no test had
      // even reached reads as detected because a newly covering test hangs) look
      // identical in a single report. Comparing against the last run's statuses
      // names each newcomer's origin — so the stash must carry every status, not
      // just SURVIVED: an origin the stash omits is an origin the comparison
      // silently misreads (NO_COVERAGE omitted -> dangerous read as benign; KILLED
      // omitted -> benign read as a first observation).
      //
      // Compared as per-coordinate *counts*, never as sets of coordinates. The
      // coordinate is line-less, so one key routinely holds several mutants at once —
      // an accepted survivor and an audited timeout among them. Set logic asks "is
      // this key timed out now, and did it hold a survivor before", which such a key
      // answers yes to on every run, including the ones where nothing moved at all;
      // a reviewer-stop that fires forever is one nobody reads. A flip is a key whose
      // timeout count rose *and* whose survivor count fell.
      val statusStash = statusStashFile
      run {
        fun tally(pairs: List<Pair<String, String>>): Map<String, Map<String, Int>> = pairs
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, statuses) -> statuses.groupingBy { it }.eachCount() }
        // A stash written by a pre-line-less plugin carries five-field entries
        // (class,method,line,mutator,STATUS); read against the four-field coordinate,
        // nothing matches, and every comparison silently degenerates — each current
        // timeout reads "newly timed out", each old entry "no longer", none of it
        // real. Announce the reset instead: a migration that silences (or garbles) a
        // check for exactly one run hides its own regression from the person doing
        // the migration (casebook: the flip that fired forever).
        // Format 2 stashes EVERY status: an origin the stash omits is an origin
        // the comparison silently misreads. NO_COVERAGE omitted read the dangerous
        // never-reached flip as benign; KILLED omitted made a benign flap at a
        // fully-killed key read as a coordinate "first observed". The header line
        // is the format's identity — a headerless stash (or a five-field one) was
        // written by an earlier plugin, and its comparison would silently
        // degenerate exactly one way or another, so it resets with a notice
        // instead.
        val stashFormatHeader = "# stash format 2"
        val stashLines = if (statusStash.isFile) statusStash.readLines() else emptyList()
        val stashEntries = stashLines
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .mapNotNull { line ->
              val sep = line.lastIndexOf(',')
              if (sep < 0) null else line.substring(0, sep) to line.substring(sep + 1)
            }
        val staleFormat = stashEntries.isNotEmpty() &&
            (stashLines.firstOrNull() != stashFormatHeader ||
                stashEntries.any { (coord, _) -> coord.count { it == ',' } != 2 })
        if (staleFormat) {
          logger.lifecycle(
              "pitest '$suiteName': status stash predates the current stash format — " +
                  "run-to-run drift comparison resets this run and resumes on the next"
          )
        }
        val previous = if (staleFormat) emptyMap() else tally(stashEntries)
        val current = tally(rows.map { it.coordinate to it.rawStatus })
        if (previous.isNotEmpty()) {
          // classification semantics live in BaselineEngine.driftCompare: the
          // dangerous flavours name each lost-unkilled origin (a key can lose
          // both), "previously detected" requires an actual prior detected read,
          // and everything else is a first observation
          val (fromSurvived, fromNoCoverage, newlyTimedOut, firstObserved, resolved) =
              BaselineEngine.driftCompare(previous, current)
          if (fromSurvived.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${fromSurvived.size} coordinate(s) flipped SURVIVED -> TIMED_OUT — " +
                    "a mutant nobody killed now reads as detected, likely load-slowed tests rather than " +
                    "new kills; do not refresh them out:\n" +
                    fromSurvived.sorted().joinToString("\n") { "  $it" }
            )
            advisoryLog.get().record(advisoryScope, "${fromSurvived.size} SURVIVED -> TIMED_OUT flip(s)")
          }
          if (fromNoCoverage.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${fromNoCoverage.size} coordinate(s) flipped NO_COVERAGE -> TIMED_OUT — " +
                    "a mutant no test had reached now reads as detected because a newly covering test " +
                    "hangs on it; kill it with a fast test or audit the timeout, do not refresh it out:\n" +
                    fromNoCoverage.sorted().joinToString("\n") { "  $it" }
            )
            advisoryLog.get().record(advisoryScope, "${fromNoCoverage.size} NO_COVERAGE -> TIMED_OUT flip(s)")
          }
          if (newlyTimedOut > 0 || firstObserved > 0 || resolved > 0) {
            logger.lifecycle(
                "pitest '$suiteName': timed-out drift vs previous run — " +
                    "$newlyTimedOut newly timed out (previously detected), $firstObserved first observed " +
                    "(no prior detected read), $resolved no longer; load-dependent"
            )
          }
        }
        BaselineFiles.writeAtomically(
            statusStash,
            rows.joinToString("\n", prefix = "$stashFormatHeader\n", postfix = "\n") {
              "${it.coordinate},${it.rawStatus}"
            }
        )
      }

      // The audited set. For a timed-out mutant the watchdog observed slowness, not
      // wrongness — the ratchet cannot see a weakened covering assertion behind it,
      // so "N timed out (load-dependent)" in the summary must be an audited
      // membership rather than a count. 'config/pitest/<suite>-timeouts.csv' holds
      // one 'class,method,mutator' per row ('#' comments allowed) — line-less so
      // drift cannot churn membership; the suite README carries each member's line
      // and structural cause. Warning-level by design: load can time out any mutant
      // on any run and both flavours are still detection, but an unaudited newcomer
      // is a change to stop on, not noise to absorb. Absent file, absent check —
      // adoption is per-repo.
      val timedOutByAuditKey = rows.filter { it.status == MutantStatus.TIMED_OUT }.groupBy { it.coordinate }
      // One membership row per key, sibling lines collapsed into the '# line' comment —
      // the shape the seeder writes, the unaudited warning prints, and a hand paste must
      // satisfy verbatim. Every surface that offers a row to paste renders it here.
      fun pasteReadyMemberRows(indent: String) = timedOutByAuditKey.keys.sorted().joinToString("\n") { key ->
        val lines = timedOutByAuditKey.getValue(key).map { it.lineText }.distinct()
        "$indent$key # line${if (lines.size > 1) "s" else ""} ${lines.joinToString(", ")}"
      }
      if (initTimeoutAudit) {
        // Seeds the mechanical half of adoption — the membership rows — from this
        // run's report, mirroring '-PupdateMutationBaseline' seeding '# untriaged':
        // the tool writes what it can derive, the warnings that follow drive the half
        // that needs a person (the causes). Refused once the file exists: a second
        // seed would be a rewrite, and the audit's whole point is that membership
        // changes one reviewed row at a time.
        if (timeoutsFile.isFile) {
          throw GradleException(
              "pitest '$suiteName': ${timeoutsFile.name} already exists — -PinitTimeoutAudit seeds a new " +
                  "audited set only. For a new timeout, paste the row the verify prints and write its " +
                  "cause in config/pitest/README.md."
          )
        }
        // Also refused with nothing timed out: an empty file would activate the audit
        // while telling its adopter to write causes for zero members, and the flag is
        // only ever pointed at by a summary that reported timeouts — a run where they
        // vanished is the load-dependence the line-less key exists to absorb, not a
        // population to certify. Arming a never-timed-out suite is a different intent
        // with a different mechanism (a hand-committed comments-only file), so the
        // refusal names it instead of reading as "empty sets are forbidden".
        if (timedOutByAuditKey.isEmpty()) {
          throw GradleException(
              "pitest '$suiteName': no timed-out mutants in this run's report — nothing to seed. " +
                  "Timeouts are load-dependent; re-run $pitestTaskName under the conditions whose " +
                  "summary reported them (see HARDENING.md, the audited-timeout bullet). To merely arm " +
                  "the audit for a suite that has never timed out, commit ${timeoutsFile.name} with " +
                  "only '#' comment lines — the suite's first timeout then warns as the reviewer-stop " +
                  "it is."
          )
        }
        val seeded = pasteReadyMemberRows("")
        BaselineFiles.writeAtomically(
            timeoutsFile,
            "# Audited TIMED_OUT set for the '$suiteName' suite (HARDENING.md, the audited-timeout\n" +
                "# bullet): one line-less 'class,method,mutator' member per row. A timeout detects\n" +
                "# slowness, not wrongness, so the ratchet cannot see a weakened covering assertion\n" +
                "# behind one; each member's structural cause belongs in config/pitest/README.md.\n" +
                seeded + "\n"
        )
        logger.lifecycle(
            "pitest '$suiteName': seeded ${timedOutByAuditKey.size} audited-timeout member(s) into " +
                "${timeoutsFile.name} — now write each member's structural cause in config/pitest/README.md"
        )
      }
      if (timeoutsFile.isFile) {
        // Normalize per field, not just per line: 'Codec, encode, MathMutator' is the
        // spacing a person writes for readability, and against a key built without
        // spaces it would match nothing — earning a permanent 'not in the audited set'
        // warning AND a 'matches no mutant' notice, with nothing on screen naming the
        // spaces as the cause. A membership file the tool silently disagrees with is
        // worse than no membership file. The same doctrine names a row with the wrong
        // field count as what it is: diagnosed as malformed and excluded from every
        // other check, instead of surfacing as a baffling 'matches no mutant' that
        // sends the reader hunting for a moved mutant. Parsing and both static
        // warnings live in TimeoutAudit, shared with 'Debt', so the two tasks can
        // never disagree about what a membership file says.
        val membership = TimeoutAudit.parse(timeoutsFile.readLines())
        val malformed = membership.malformed
        // Recorded as an advisory only when it stays one: under -PstrictTimeoutAudit
        // this finding (and the unaudited newcomer below) becomes the failure itself,
        // and the summary's "none failed the build" must stay true.
        TimeoutAudit.malformedWarning(suiteName, timeoutsFile.name, malformed)?.let {
          logger.warn(it)
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${malformed.size} malformed audit row(s)")
          }
        }
        val members = membership.members
        val unaudited = rows.filter { it.status == MutantStatus.TIMED_OUT && it.coordinate !in members }
        if (unaudited.isNotEmpty()) {
          // rows print paste-ready: the membership key verbatim, the line riding in a
          // '#' comment — pasting the printed row into the set must satisfy the check,
          // never trip the stale-member notice
          logger.warn(
              "pitest '$suiteName': ${unaudited.size} timed-out mutant(s) not in the audited set " +
                  "(${timeoutsFile.name}) — a new timeout hides a weakened-assertion blind spot " +
                  "behind \"detected\"; identify the structural cause (the removed loop exit, the " +
                  "reversed cursor), add the row below to the set and the cause to " +
                  "config/pitest/README.md:\n" +
                  unaudited.joinToString("\n") { "  ${it.coordinate} # line ${it.lineText}" }
          )
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${unaudited.size} unaudited timeout(s)")
          }
        }
        val allKeys = rows.mapTo(HashSet()) { it.coordinate }
        val staleMembers = members.filterNot { it in allKeys }
        if (staleMembers.isNotEmpty()) {
          // Warn-level like the other membership findings: 'retire or fix' is a
          // reviewer-stop exactly as much as a missing cause, and warn is what feeds
          // the end-of-build advisory summary.
          logger.warn(
              "pitest '$suiteName': ${staleMembers.size} audited-timeout row(s) match no mutant in " +
                  "this run's report — the code moved or the mutator set changed; retire or fix:\n" +
                  staleMembers.sorted().joinToString("\n") { "  $it" }
          )
          advisoryLog.get().record(advisoryScope, "${staleMembers.size} stale audit row(s)")
        }
        val liveMembers = members - staleMembers.toSet()
        // The '# line' comment the seed writes (and the paste-ready row carries) is
        // the anchor its README cause argues about, and the doctrine's "re-read the
        // cause when that code changes" was purely social: the key going stale only
        // notices a *method* disappearing, never the code moving within one. The
        // report holds the observed side of that comparison, so make it: a member
        // whose observed timeout lines all differ from its recorded ones is exactly
        // the cause-vs-code divergence a reviewer should re-read. Disjointness, not
        // inequality — a new sibling line next to a recorded one is the line-less
        // key's stated no-warning resolution (TimeoutAudit.lineDrift).
        val drifted = TimeoutAudit.lineDrift(
            membership.recordedLines,
            timedOutByAuditKey.filterKeys { it in members }
                .mapValues { (_, timedOut) -> timedOut.mapNotNull { it.line }.toSet() }
        )
        if (drifted.isNotEmpty()) {
          logger.warn(TimeoutAudit.lineDriftWarning(suiteName, timeoutsFile.name, drifted))
          advisoryLog.get().record(advisoryScope, "${drifted.size} line-drifted audit row(s)")
        }
        // The check the set was missing: membership was validated against ALL mutants,
        // so a member whose mutants exist but never time out — a key pasted from the
        // wrong report, or a timeout the tests since learned to kill — was accepted
        // forever. A single-run "did not time out" is exactly the KILLED<->TIMED_OUT
        // load flip the line-less key exists to absorb, so the signal is consecutive
        // quiet runs: the counter persists in .pitest-history/ (machine-local, like
        // the status stash) and resets whenever the member times out again. Three
        // quiet runs mirrors the flip-family retirement criterion ("3 quiet
        // modeCompare cycles"); a member that only times out under gate load will be
        // reset by gate runs and nagged only during long solo streaks — the notice
        // says so rather than presuming retirement.
        run {
          // The counter advances per fresh report, not per invocation: the verify
          // runs standalone against the existing report ('finalizedBy', not a
          // dependency), so re-running it to exercise its checks would otherwise
          // manufacture quiet evidence from a single mutation run. The stash's first
          // line fingerprints the report; on a match the stored counts replay
          // untouched. Timestamp over content hash on purpose — a fresh PIT run with
          // identical results IS a fresh quiet observation.
          val reportFingerprint = "# report ${csv.lastModified()},${csv.length()}"
          val previousLines = if (timeoutQuietFile.isFile) timeoutQuietFile.readLines() else emptyList()
          val sameReport = previousLines.firstOrNull() == reportFingerprint
          val previousQuiet = previousLines.filterNot { it.startsWith("#") }.mapNotNull { line ->
            val sep = line.lastIndexOf(',')
            if (sep < 0) null else line.substring(0, sep) to (line.substring(sep + 1).toIntOrNull() ?: 0)
          }.toMap()
          // Live members only, which also means a stale member's count is dropped,
          // not frozen: staleness says the code moved (or the mutator set changed),
          // and quiet evidence about the old method body must be re-measured from
          // zero if the mutant returns, not carried across the change. The cost is
          // two extra runs of patience; the alternative is a retirement nudge argued
          // from code that no longer exists.
          val quietRuns = liveMembers.associateWith { member ->
            when {
              member in timedOutByAuditKey -> 0
              sameReport -> previousQuiet[member] ?: 0
              else -> (previousQuiet[member] ?: 0) + 1
            }
          }
          BaselineFiles.writeAtomically(
              timeoutQuietFile,
              quietRuns.entries.sortedBy { it.key }
                  .joinToString("\n", prefix = "$reportFingerprint\n", postfix = "\n") { "${it.key},${it.value}" }
          )
          // Derived from the counts, so a same-report re-run reprints it — like every
          // other audit advisory, which are all recomputed from the report rather
          // than remembering they already printed. Warn-level like its siblings
          // (stale rows, missing causes): the retirement criterion family is one
          // tier, and warn is what feeds the end-of-build advisory summary — a gate
          // runs the quiet notice as many hundred lines up-screen as any other.
          val settled = quietRuns.filterValues { it >= 3 }
          if (settled.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${settled.size} audited-timeout member(s) have not timed out in 3+ " +
                    "consecutive mutation runs — if a member only times out under gate load this is normal " +
                    "on solo streaks; otherwise its tests now detect the mutant outright, and the member " +
                    "(with its README cause) can be retired:\n" +
                    settled.entries.sortedBy { it.key }
                        .joinToString("\n") { "  ${it.key} (quiet for ${it.value} runs)" }
            )
            advisoryLog.get().record(advisoryScope, "${settled.size} quiet audited-timeout member(s)")
          }
        }
        // A member is only half audited without its cause: the row makes the set
        // machine-checked, the README argument is what a reviewer actually reads.
        // Mirrors the family-label rule (a label is a pointer to its README argument)
        // at the same advisory level; the matching rule lives in TimeoutAudit, shared
        // with 'Debt'.
        //
        // Live members only: a stale member is already being told to retire, and
        // asking for the cause of a row that should be deleted is two instructions
        // pulling opposite ways for one row.
        val undocumented = TimeoutAudit.undocumentedCauses(liveMembers) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (undocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(suiteName, undocumented))
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${undocumented.size} audited timeout(s) without a README cause")
          }
        }
        // Opt-in escalation for certifying runs:
        // by default every audit finding is advisory (load can time out any mutant on
        // any run), but on a run whose purpose is certification, an unaudited
        // newcomer, a row the tool cannot parse, or a member whose cause was never
        // written is exactly what the run exists to stop on — the doctrine admits a
        // newcomer only with its cause written, so a cause-less member is an
        // unfinished admission, not hygiene; row-then-cause is a legitimate sequence
        // *between* certifications, not during one. Hygiene findings (stale members,
        // quiet streaks, drifted lines) stay advisory even here.
        if (strictTimeoutAudit && (unaudited.isNotEmpty() || malformed.isNotEmpty() || undocumented.isNotEmpty())) {
          throw GradleException(
              "pitest '$suiteName': -PstrictTimeoutAudit — ${unaudited.size} unaudited timed-out mutant(s), " +
                  "${malformed.size} malformed membership row(s), and ${undocumented.size} audited member(s) " +
                  "without a README cause; see the warnings above. Paste the printed row(s) into " +
                  "${timeoutsFile.name} and write each cause in config/pitest/README.md."
          )
        }
      } else if (timedOutByAuditKey.isNotEmpty()) {
        // A suite carrying timeouts with no audited set is running with the blind
        // spot the audit exists for, and nothing on screen said the feature exists —
        // it was discoverable only by reading HARDENING.md. Advisory nudge normally;
        // under the strict flag an unadopted timeout-carrying suite is an unaudited
        // newcomer by definition.
        // The rows print paste-ready alongside the flag: a timeout is load-dependent, so
        // by the time anyone acts on this nudge the next run may hold a clean report —
        // -PinitTimeoutAudit then rightly refuses to seed from it, and without the rows
        // here the coordinate that timed out is recoverable only from the daemon log.
        val hint =
            "pitest '$suiteName': ${rows.count { it.status == MutantStatus.TIMED_OUT }} timed-out mutant(s) and no audited " +
                "set — a timeout detects slowness, not wrongness, so the ratchet cannot see a weakened " +
                "covering assertion behind one. Adopt the audit with -PinitTimeoutAudit (seeds " +
                "config/pitest/${timeoutsFile.name} from this run), or paste the row(s) below — " +
                "load-dependent timeouts may not reproduce for a later seeding run:\n" +
                pasteReadyMemberRows("  ") + "\n" +
                "then write each member's structural cause in config/pitest/README.md."
        if (strictTimeoutAudit) {
          throw GradleException(hint)
        }
        logger.lifecycle(hint)
      }

      // Row-level keep plan, computed once and read by BOTH prune and the check
      // path's stale hint: each surface prints row identities, so deciding from
      // two allocators (the hint used to budget in baseline-file order over key
      // strings while prune budgeted affinity-first over rows) let the hint
      // promise "prune keeps them" about a row prune then dropped. The plan's
      // dispositions and their ordering rules live in BaselineEngine.keepPlan —
      // the pure, unit- and property-tested transition core; this task only
      // feeds it the report's per-coordinate timed-out and killed lines.
      val keepPlan: List<BaselineEngine.Disposition> = BaselineEngine.keepPlan(
          acceptedRows,
          currentLines,
          timedOutByAuditKey.mapValues { (_, mutants) -> mutants.map { it.line } },
          rows.filter { it.status == MutantStatus.KILLED }.groupBy({ it.coordinate }, { it.line }),
      )
      // A refresh rewrite emits rows only: '#' comment lines and blank lines do
      // not survive it. migrateMutationBaselines is the one path that preserves
      // them, so a rewrite must at least be LOUD about the prose it is about to
      // drop — every writer below calls this before touching the file.
      fun warnDroppedComments() {
        if (baselineCommentLines.isEmpty()) return
        logger.warn(
            "pitest baseline '$suiteName': ${baselineCommentLines.size} comment line(s) in " +
                "${baselineFile.name} do not survive a refresh rewrite (migrateMutationBaselines " +
                "preserves them) — move durable prose to config/pitest/README.md or a row's " +
                "'# note':\n" + baselineCommentLines.joinToString("\n") { "  $it" }
        )
        advisoryLog.get().record(
            advisoryScope, "${baselineCommentLines.size} comment line(s) dropped by a rewrite")
      }
      if (prune) {
        // Shrink-only identity refresh: drop baseline rows matching nothing this run
        // and add no rows. Matched rows do refresh their '# line' metadata from this
        // report, which is safe because lines are not identity and gives the advisory
        // a clearing operation that cannot accept a mutant. Unmatched timeout, flip,
        // and insurance keeps retain their old lines because this run did not observe
        // them at their own key. What is kept and what drops is the keep plan above,
        // verbatim — the stale hint reads the same plan, so the two cannot disagree.
        val kept = mutableListOf<BaselineNotes.Row>()
        val keptUnmatched = mutableListOf<Pair<BaselineNotes.Row, String>>()
        val droppedRows = mutableListOf<BaselineNotes.Row>()
        for ((rowIndex, row) in acceptedRows.withIndex()) {
          when (keepPlan[rowIndex]) {
            BaselineEngine.Disposition.MATCHED -> kept.add(row)
            BaselineEngine.Disposition.TIMEOUT -> {
              kept.add(row)
              keptUnmatched.add(row to "TIMED_OUT this run (load-dependent)")
            }
            BaselineEngine.Disposition.FLIP -> {
              kept.add(row)
              keptUnmatched.add(row to "coordinate unkilled at another status (flip pending triage)")
            }
            BaselineEngine.Disposition.INSURED -> {
              kept.add(row)
              keptUnmatched.add(row to "flip insurance at this key (remove by its written criterion, not a refresh)")
            }
            BaselineEngine.Disposition.DROP -> droppedRows.add(row)
          }
        }
        // Prune is shrink-only, not gate-free. Validate the complete current gated
        // multiset against the exact rows the prune proposes BEFORE touching the
        // baseline: a fresh row (including a SURVIVED<->NO_COVERAGE status change)
        // must fail here and leave the previous record byte-for-byte intact.
        val unacceptedAfterPrune = multisetDiff(current, kept.map { it.key })
        if (unacceptedAfterPrune.isNotEmpty()) {
          throw GradleException(
              "pitest baseline '$suiteName': refusing -PpruneMutationBaseline — the report has " +
                  "${unacceptedAfterPrune.size} gated mutant(s) the proposed pruned baseline would not " +
                  "accept, so this is not a green shrink-only transition; no baseline changes were made:\n" +
                  unacceptedAfterPrune.joinToString("\n") { "  $it" }
          )
        }
        val pruneRewrite = BaselineEngine.pruneRewrite(acceptedRows, keepPlan, currentLines)
        val rowSpellingsChanged = pruneRewrite.written != baselineRowLines
        val keptDetail = if (keptUnmatched.isEmpty()) "" else
          "\n  kept ${keptUnmatched.size} unmatched row(s):\n" +
              keptUnmatched.joinToString("\n") { (row, why) -> "  ${BaselineNotes.render(row)} — $why" }
        val refreshedDetail = if (pruneRewrite.refreshedLineTags == 0) "" else
          "; refreshed ${pruneRewrite.refreshedLineTags} kept row line tag(s) from this run"
        if (droppedRows.isEmpty() && !rowSpellingsChanged) {
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped nothing — every row matches this run$keptDetail")
        } else if (kept.isEmpty()) {
          // every row dropped: remove the file rather than leave a one-newline
          // husk — no record and an empty record must read the same way
          warnDroppedComments()
          baselineFile.delete()
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped every row since killed — baseline file removed:\n" +
                  droppedRows.joinToString("\n") { row -> "  ${BaselineNotes.render(row)}${describe(row.key)}" }
          )
        } else {
          // Kept rows are re-rendered even when only a matched row's line metadata
          // changed. This also migrates a legacy five-field file to the line-less
          // format (the legacy line field becomes a '# line' tag).
          warnDroppedComments()
          BaselineFiles.writeAtomically(
              baselineFile, pruneRewrite.written.joinToString("\n", postfix = "\n"))
          if (droppedRows.isEmpty()) {
            val action = if (pruneRewrite.refreshedLineTags == 0) {
              "re-rendered ${kept.size} row(s) in the current format"
            } else {
              "refreshed ${pruneRewrite.refreshedLineTags} line tag(s) from this run"
            }
            logger.lifecycle(
                "pitest baseline '$suiteName': prune dropped nothing and $action$keptDetail")
          } else {
            logger.lifecycle(
                "pitest baseline '$suiteName': prune dropped ${droppedRows.size} row(s) since killed " +
                    "(baseline now ${kept.size}$refreshedDetail):\n" +
                    droppedRows.joinToString("\n") { row -> "  ${BaselineNotes.render(row)}${describe(row.key)}" } +
                    keptDetail
            )
          }
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
        return@doLast
      }
      if (update) {
        // Full rewrite from this run's report. A dropped row's '# note' must not
        // vanish silently; with line-less keys only one relationship needs a carry:
        //
        //   status flip — same class,method,mutator, different status (NO_COVERAGE ->
        //                 SURVIVED once a test reaches the method). Carried marked for
        //                 re-reading: a reason written for an unreached mutant is not
        //                 automatically a reason once a test can observe it.
        //
        // Line shifts no longer exist as churn — lines are metadata, so an edit above
        // a mutated method changes nothing here but the '# line' tags, and the
        // shift-pairing, bare-row-pairing and pairing-outlier machinery that used to
        // police that carry is gone with the churn it policed.
        //
        // Within a key, accepted rows are assigned to this run's mutants by LINE
        // AFFINITY first — a pair whose '# line' tag names the mutant's observed line
        // is its row — then by file order. So when a noted sibling was killed, the
        // note that drops is its own, not whichever row came first; without line
        // evidence the assignment is arbitrary, which is the documented same-key
        // blind spot, not a bug to police.
        // pairing, note carry, and seeding live in BaselineEngine.updateRewrite;
        // this task renders its result and names each dropped row's fate below
        val rewrite = BaselineEngine.updateRewrite(acceptedRows, currentLines)
        val droppedIdx = rewrite.droppedIdx
        val carriedIdx = rewrite.carriedIdx
        // A refresh with nothing unkilled writes no record: an empty (or newly
        // created, one-newline) baseline file reads as an armed-but-empty record
        // where there is no record at all, and clutters fully-detected suites.
        if (rewrite.copies == 0) {
          if (baselineFile.isFile) {
            warnDroppedComments()
            baselineFile.delete()
            logger.lifecycle("pitest baseline '$suiteName': nothing unkilled — baseline file removed")
          } else {
            logger.lifecycle("pitest baseline '$suiteName': nothing unkilled — no baseline to write")
          }
        } else {
          warnDroppedComments()
          BaselineFiles.writeAtomically(baselineFile, rewrite.written.joinToString("\n", postfix = "\n"))
          logger.lifecycle(
              "pitest baseline '$suiteName': wrote ${rewrite.copies} accepted entries" +
                  (if (rewrite.seeded == 0) "" else " (${rewrite.seeded} new row(s) seeded '# untriaged')") +
                  (if (rewrite.flipped == 0) "" else " (${rewrite.flipped} note(s) carried across a status flip — re-check them)")
          )
        }
        if (droppedIdx.isNotEmpty()) {
          // The silent half of the refresh footgun: a full update rewrites from this one
          // run, so a flip-insurance union (detected today, survived under other load)
          // vanishes without a trace unless it is named here. Notes get the same
          // treatment: a note still in the carry pool after the rewrite is an
          // acceptance argument that just left the baseline — name its fate per row,
          // because a lost note that prints identically to a carried one is still
          // silent (casebook: the note the line shift dropped).
          fun rowFate(idx: Int): String = when {
            acceptedRows[idx].note == null -> ""
            idx in carriedIdx -> " — note carried"
            else -> " — note dropped with the row"
          }
          val lostCount = droppedIdx.count { acceptedRows[it].note != null && it !in carriedIdx }
          logger.lifecycle(
              "pitest baseline '$suiteName': dropped ${droppedIdx.size} row(s) not unkilled this run:\n" +
                  droppedIdx.joinToString("\n") { idx ->
                    "  ${BaselineNotes.render(acceptedRows[idx])}${describe(acceptedRows[idx].key)}${rowFate(idx)}"
                  } +
                  (if (lostCount == 0) "" else
                      "\n  $lostCount note(s) dropped with their rows — re-home the acceptance argument by hand if it still applies") +
                  "\n  a dropped flip-insurance union (see config/pitest/README.md) must be " +
                  "re-added with -PunionMutationBaseline once observed to flip again"
          )
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
        return@doLast
      }
      if (union) {
        // Append-only refresh for flip families (HARDENING.md: union only rows observed
        // to flip). Adds this run's unkilled rows in canonical form without dropping
        // baseline rows that happened to be detected this run — a full
        // '-PupdateMutationBaseline' there would bake in this run's coin-flips and start
        // refresh ping-pong.
        // the merge — per-key max counts, existing rows verbatim after maximum
        // exact-line affinity and the live-anchor/file-order fallback, added copies
        // bare with the genuinely unclaimed lines — lives in BaselineEngine.unionMerge
        val merge = BaselineEngine.unionMerge(acceptedRows, current, currentLines)
        if (merge.added.isEmpty()) {
          logger.lifecycle("pitest baseline '$suiteName': union added nothing new")
        } else {
          warnDroppedComments()
          BaselineFiles.writeAtomically(baselineFile, merge.merged.joinToString("\n", postfix = "\n"))
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${merge.added.size} entries (baseline now ${merge.total}):\n" +
                  merge.added.joinToString("\n") { row -> "  $row${describe(row)}" }
          )
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
        return@doLast
      }
      val fresh = multisetDiff(current, accepted)
      val stale = multisetDiff(accepted, current)
      // Line-drift advisory: an unkilled mutant at a line no row's '# line' tag
      // names is a population the acceptance argument may no longer describe —
      // either the anchor moved, or a same-key swap slid a new mutant under an old
      // acceptance. Row-level where the data supports it (every row of the key
      // tagged, observed count matching the row count): the baseline's multiset
      // already fails a genuinely new sibling as a count change, so unlike the
      // audited-timeout sets there is no new-sibling quiet case to preserve, and
      // any unrecorded line under matched counts is worth a re-read. Partial tags
      // or skewed counts fall back to the audit's key-level disjointness. Advisory
      // only, never a failure: lines are metadata, and a green prune or a full
      // update rewrites matched tags (BaselineNotes.lineDrift owns the semantics).
      run {
        val observed = currentLines
            .mapValues { (_, lines) -> lines.mapNotNull { it.toIntOrNull() } }
        val drifted = BaselineNotes.lineDrift(acceptedRows, observed)
        if (drifted.isNotEmpty()) {
          logger.warn(
              "pitest baseline '$suiteName': ${drifted.size} accepted key(s) unkilled at line(s) " +
                  "no row's '# line' tag names — the code the acceptance argues about has moved, or a " +
                  "new mutant sits under an old acceptance (the same-key swap); re-read the README " +
                  "argument, then use a green -PpruneMutationBaseline to rewrite the matched tag " +
                  "without widening the baseline:\n" +
                  drifted.entries.sortedBy { it.key }.joinToString("\n") { (key, lines) ->
                    val (recordedLines, observedLines) = lines
                    "  $key # line(s) ${recordedLines.sorted().joinToString(", ")} -> " +
                        "unrecorded ${observedLines.sorted().joinToString(", ")}"
                  }
          )
          advisoryLog.get().record(advisoryScope, "${drifted.size} line-drifted baseline key(s)")
        }
      }
      // Two situations produce paired stale + "new" rows and are classified rather
      // than lumped, because they call for different responses:
      //
      //   newly covered     — same class,method,mutator, different status (typically
      //                       NO_COVERAGE -> SURVIVED): a test newly reached the
      //                       mutant. A triage item — kill it or accept it with a
      //                       reason — never a refresh.
      //   surfaced sibling  — a "new" row identical to an accepted row: the key now
      //                       holds more unkilled sibling mutants than the baseline
      //                       has rows. Either pre-existing debt made visible (a
      //                       set-based baseline upgraded, a compound condition's
      //                       operands) or a genuinely NEW mutant landing at an
      //                       accepted key — the one shape the line-less key cannot
      //                       tell apart (HARDENING.md names this blind spot); the
      //                       report's line numbers say which.
      //
      // A stale row is consumed once it pairs, so several new rows cannot all claim
      // the same counterpart and report a churn that did not happen. Line shifts no
      // longer exist as a category: lines are not identity, so editing above a
      // mutated method produces no fresh rows at all.
      // pairing and counting live in BaselineEngine.classifyChurn — a stale row is
      // consumed once it pairs, so several fresh rows cannot all claim one counterpart
      val churn = BaselineEngine.classifyChurn(fresh, stale, accepted.toSet())
      val newlyCoveredPairs = churn.newlyCoveredPairs
      val surfacedSiblings = churn.surfacedSiblings
      val newlyCoveredFrom = newlyCoveredPairs.toMap(mutableMapOf())
      val surfacedSiblingTexts = surfacedSiblings.toSet()
      val unexplained = churn.unexplained
      fun freshHint(row: String): String = when {
        row in surfacedSiblingTexts ->
          " (shares an accepted key — sibling debt surfaced, or a NEW mutant at that key; check the line)"
        newlyCoveredFrom.containsKey(row) ->
          " (newly covered — was ${newlyCoveredFrom.getValue(row).substringAfterLast(',')}; triage, not a refresh)"
        else -> ""
      }
      if (stale.isNotEmpty()) {
        // The stale entries classified from the keep plan — the SAME row-level
        // assignment prune executes, so the hint's "prune keeps them" and "refresh
        // with prune" name exactly the rows prune keeps and drops (two independent
        // allocators disagreed at cross-status coordinates: the hint budgeted in
        // file order over key strings, prune affinity-first over rows, and the
        // hint promised a keep prune then reneged on — casebook: the stale hint
        // that named the wrong flag). Rows a flip counterpart explains are neither
        // hinted since-killed nor claimed kept: the fresh side's "newly covered"
        // pairing already names them as triage.
        //   timeout — the load-dependent detection the TIMED_OUT doctrine warns
        //             about; counting it as since-killed contradicted the
        //             SURVIVED -> TIMED_OUT warning printed above (casebook: the
        //             limbsLength flapper told to prune itself)
        //   insured — the flap its insurance note records: the mutant reads
        //             killed on this run and survives on another; the hint used
        //             to name it and prune then dropped it, failing the next solo
        //             run with an unexplained survivor
        val staleTimedOut = acceptedRows.indices.filter { keepPlan[it] == BaselineEngine.Disposition.TIMEOUT }
        val staleInsured = acceptedRows.indices.filter { keepPlan[it] == BaselineEngine.Disposition.INSURED }
        val staleGone = acceptedRows.indices.filter { keepPlan[it] == BaselineEngine.Disposition.DROP }
        if (staleGone.isNotEmpty()) {
          // Point at prune, not update: when the only news is *fewer* survivors, the
          // shrink-only refresh is the always-safe direction — it cannot bake in a
          // coin-flip from this one run, which is exactly what recommending a full
          // rewrite here used to invite. With new rows present that case still wants
          // update — after the new rows are triaged, since they may be newly covered
          // or surfaced siblings, where update-before-triage is exactly the
          // laundering the ratchet exists to prevent.
          val direction = if (fresh.isEmpty()) "-PpruneMutationBaseline (shrink-only; nothing new to bake in)"
          else "-PupdateMutationBaseline after the new rows below are triaged"
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleGone.size} stale entries (since killed) — refresh with $direction")
        }
        if (staleInsured.isNotEmpty()) {
          // "unmatched at their own status", not "read killed": the insured
          // disposition precedes the flip step, so a row here may also have a
          // live different-status mutant at its coordinate — that one did not
          // read killed, it moved (newly covered), and the concurrent ratchet
          // failure names it as exactly that.
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleInsured.size} flip-insured row(s) unmatched at their " +
                  "own status this run — a killed read is the flap the insurance records, a " +
                  "different-status read is newly covered (named in the failure detail); either way no " +
                  "refresh: prune keeps them, and the row leaves by its written removal criterion:\n" +
                  staleInsured.joinToString("\n") {
                    "  ${BaselineNotes.render(acceptedRows[it])}"
                  })
        }
        if (staleTimedOut.isNotEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleTimedOut.size} baseline row(s) read TIMED_OUT this run — " +
                  "load-dependent detection, not a kill; no refresh needed (prune keeps them):\n" +
                  staleTimedOut.joinToString("\n") {
                    "  ${BaselineNotes.render(acceptedRows[it])}"
                  })
        }
      }
      if (fresh.isNotEmpty()) {
        // Split the report: the two statuses need opposite responses, and saying so
        // here saves re-deriving it from the raw rows.
        val freshByStatus = fresh.groupBy { it.substringAfterLast(',') }
        val detail = buildString {
          freshByStatus["NO_COVERAGE"]?.let {
            append("\n  ${it.size} NO_COVERAGE — no test reaches these; mechanical work, ")
            append("and never acceptable as \"equivalent\" since the behaviour was never observed:\n")
            append(it.joinToString("\n") { row -> "    $row${freshHint(row)}${describe(row)}" })
          }
          freshByStatus["SURVIVED"]?.let {
            append("\n  ${it.size} SURVIVED — a test ran these and could not tell the difference; ")
            append("strengthen the assertion or triage for equivalence:\n")
            append(it.joinToString("\n") { row -> "    $row${freshHint(row)}${describe(row)}${siblingHint(row)}" })
          }
          // The churn tally answers the question the per-row hints cannot: is the whole
          // set accounted for? Refreshing is only safe when nothing is unexplained and
          // nothing was newly covered.
          append("\n  churn: ${newlyCoveredPairs.size} newly covered, ")
          if (surfacedSiblings.isNotEmpty()) append("${surfacedSiblings.size} surfaced sibling(s), ")
          append("$unexplained unexplained (of ${fresh.size} new; ${stale.size} stale)")
          if (newlyCoveredPairs.isNotEmpty()) {
            append("\n  ${newlyCoveredPairs.size} row(s) are newly covered rather than new code: a test now ")
            append("reaches them, so they are triage (kill or accept with a reason), not a refresh")
          }
          if (surfacedSiblings.isNotEmpty()) {
            append("\n  ${surfacedSiblings.size} row(s) share an accepted key: pre-existing sibling debt ")
            append("surfaced by the multiset comparison, or a genuinely new mutant landing at an accepted ")
            append("key — the line-less key's documented blind spot; read the report's line numbers before ")
            append("accepting")
          }
        }
        throw GradleException(
            "pitest '$suiteName': ${fresh.size} unkilled mutant(s) not in the accepted baseline:" +
                detail +
                "\nKill them with tests, or accept knowingly by re-running with -PupdateMutationBaseline " +
                "and documenting the reason (see HARDENING.md). If this suite has never been seeded, " +
                "-PupdateMutationBaseline creates config/pitest/$suiteName-accepted.csv."
        )
      }
      if (certificationActive) {
        val evidence = verifiedEvidence ?: throw GradleException(
            "pitest '$suiteName': certification requires completed evidence generated in this invocation")
        try {
          certificationSession.get().recordVerified(evidenceProjectPath, suiteName, evidence)
        } catch (e: IllegalStateException) {
          throw GradleException("pitest '$suiteName': ${e.message}", e)
        }
      }
    }
  }
  hardeningCertify.configure { mustRunAfter(verify) }
  tasks.register("${pitestTaskName}Debt") {
    group = "verification"
    description = "Prints the '$suiteName' unkilled-mutant debt grouped by class, largest first, with the baseline delta."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val debtTimeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val debtToolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val debtPitVersion = hardening.pitestVersion
    val debtClassesDir = mutationClassesDir
    val debtTargets = suite.targetClasses
    val debtExcludes = allExcludedClasses
    val debtTestSourceDirs = sourceSets.test.get().java.srcDirs
    val debtSiblingTargets = suiteTargetGlobs
    val debtSiblingExcludes = suiteExcludedGlobs
    val debtDeclinedExclusions = suite.declinedExclusionAudits
    doLast {
      // Committed-files-only, like the audit's static half below — which makes Debt
      // (and therefore the fleet canary) the place a plugin release that bumps PIT
      // surfaces per consumer repo, before anyone reads tool churn as code churn.
      val recordedPit = debtToolVersionFile.takeIf { it.isFile }?.readText()?.trim()
      if (recordedPit != null && recordedPit != debtPitVersion.get()) {
        logger.warn(
            "pitest '$suiteName': baseline record written by PIT $recordedPit, this plugin runs " +
                "PIT ${debtPitVersion.get()} — population differences may be the tool, not the code; " +
                "re-baseline deliberately (config/pitest/$suiteName-pitest-version, then refresh the suite)"
        )
      }
      // The audited-timeout set's static half, shared with the verify (TimeoutAudit):
      // row shape and cause presence read committed files only, so a pasted member or
      // a fresh README cause is confirmed here in seconds instead of after the next
      // mutation run. The mutant-facing checks (unaudited newcomers, stale members,
      // quiet streaks) need a report and stay in the verify — which also means no
      // staleness is known here, so every well-formed member is asked for its cause.
      // Before the debt tally, not after: a fully-detected suite has no debt to print
      // (the early return below) but its audited set is exactly as checkable. Plain
      // warnings, no advisory-log entries: the end-of-build summary exists for the
      // gate's scroll problem, and these lines end a short interactive run.
      if (debtTimeoutsFile.isFile) {
        val membership = TimeoutAudit.parse(debtTimeoutsFile.readLines())
        TimeoutAudit.malformedWarning(suiteName, debtTimeoutsFile.name, membership.malformed)
            ?.let { logger.warn(it) }
        val undocumented = TimeoutAudit.undocumentedCauses(membership.members) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (undocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(suiteName, undocumented))
        }
      }

      // The exclusion audit's static half, mirroring the timeout audit's: the
      // policy is pure given recompiled classes, so when a prior run left
      // build/mutation-classes behind it is checkable here without a mutation
      // run — which is what puts it in front of the fleet canary, whose Debt
      // runs are where a plugin release meets real consumer globs. Its in-run
      // half only fired inside a real 'pitest<Suite>' execution, which the
      // canary never does, and that blind spot shipped a release *(casebook:
      // the partition the audit called a hole)*. Absent classes stay silent:
      // nothing was scanned, and silence means "unscanned", never "clean" —
      // and like the report above, the classes may be stale; both caveats ride
      // the same rerun hint.
      val debtClasses = debtClassesDir.get().asFile
      if (debtClasses.isDirectory) {
        val siblingScopes = debtSiblingTargets.get()
            .filterKeys { it != suiteName }
            .map { (sibling, targets) ->
              ExclusionAudit.SuiteScope(targets, debtSiblingExcludes.get()[sibling].orEmpty())
            }
        val swallowed = ExclusionAudit.swallowedProductionClasses(
            debtClasses, debtTargets.get(), debtExcludes.get(), debtTestSourceDirs, siblingScopes
        )
        val declined = ExclusionAudit.applyDeclines(swallowed, debtDeclinedExclusions.get())
        ExclusionAudit.warning(suiteName, declined.reported)?.let { logger.warn(it) }
        ExclusionAudit.staleDeclineWarning(suiteName, declined.staleGlobs)?.let { logger.warn(it) }
        ExclusionAudit.blankDeclineWarning(suiteName, declined.blankGlobs)?.let { logger.warn(it) }
      }

      fun tally(pairs: List<Pair<String, String>>): Map<String, Pair<Int, Int>> = pairs
          .groupBy({ it.first }, { it.second })
          .mapValues { (_, statuses) ->
            statuses.count { it == "SURVIVED" } to statuses.count { it == "NO_COVERAGE" }
          }

      // BaselineNotes handles both formats: line-less rows and legacy five-field ones.
      // Malformed rows are named on the verify's terms and excluded from both the
      // debt tally and the label breakdown below, so the two surfaces cannot report
      // different row counts for one file — and Debt is the fleet canary's whole
      // view of these files, so the diagnosis reaches pre-release review.
      val baselineRowLines = if (baselineFile.exists()) {
        baselineFile.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
      } else {
        emptyList()
      }
      val malformedRows = baselineRowLines.filter { BaselineNotes.malformed(it) }
      if (malformedRows.isNotEmpty()) {
        logger.warn(
            "pitest '$suiteName': ${malformedRows.size} malformed row(s) in ${baselineFile.name} — " +
                "expected 'class,method,mutator,STATUS [# note] [# line N]'; a malformed row matches " +
                "no mutant, and a refresh would silently drop it:\n" +
                malformedRows.joinToString("\n") { "  $it" }
        )
      }
      val wellFormedRowLines = baselineRowLines.filterNot { BaselineNotes.malformed(it) }
      val baselinePairs = wellFormedRowLines
          .map { BaselineNotes.parse(it).key.split(',') }
          .filter { it.size >= 4 }
          .map { it[0] to it.last() }
      val csv = csvProvider.get().asFile
      var invalidReport = false
      // scoped and interrupted-run reports both fall back to the baseline: a
      // partial population under-counts debt exactly like a hand-picked one. An
      // error/unfinished report is equally unusable as a tally, but Debt remains
      // the read-only triage surface after a failed PIT run: name every offending
      // mutant, then fall back to the committed baseline instead of disabling the
      // next diagnostic the failure calls for.
      val reportPairs = if (csv.isFile &&
          !csv.parentFile.resolve(".scoped").isFile &&
          !csv.parentFile.resolve(".running").isFile) {
        try {
          Mutant.parseReport(csv.readLines())
              .filter { it.gated }
              .map { it.className to it.rawStatus }
        } catch (e: IllegalArgumentException) {
          invalidReport = true
          logger.warn(
              "pitest '$suiteName' debt: current report is not valid completed evidence; " +
                  "falling back to the committed baseline for the tally:\n${e.message}")
          null
        }
      } else {
        null
      }
      val source = when {
        reportPairs != null -> "current report"
        invalidReport -> "baseline (current report invalid)"
        else -> "baseline (no full report present)"
      }
      val debt = tally(reportPairs ?: baselinePairs)
      val baselineDebt = tally(baselinePairs)
      if (debt.isEmpty()) {
        logger.lifecycle("pitest '$suiteName' debt: none — nothing unkilled in the $source")
        return@doLast
      }
      val lines = debt.entries
          .sortedByDescending { it.value.first + it.value.second }
          .map { (fqcn, counts) ->
            val (survived, noCoverage) = counts
            val base = baselineDebt[fqcn]
            val delta = if (reportPairs == null || base == null && survived + noCoverage == 0) ""
            else {
              val d = (survived + noCoverage) - ((base?.first ?: 0) + (base?.second ?: 0))
              when {
                d < 0 -> "  ($d vs baseline)"
                d > 0 -> "  (+$d vs baseline)"
                else -> ""
              }
            }
            "  %4d survived  %4d no_coverage  %s%s".format(survived, noCoverage, fqcn, delta)
          }
      val totalSurvived = debt.values.sumOf { it.first }
      val totalNoCoverage = debt.values.sumOf { it.second }
      // The report is a snapshot, not live state: name its age so numbers from a
      // run made before the current change are not read as current.
      val age = if (reportPairs == null) "" else {
        val minutes = (System.currentTimeMillis() - csv.lastModified()) / 60_000
        if (minutes < 2) "" else ", ${minutes}m old — rerun $pitestTaskName if stale"
      }
      // Label breakdown from the baseline (the well-formed rows parsed above):
      // triaged-accepted rows carry a family label, seeded debt reads '# untriaged',
      // and unlabeled rows predate seeding.
      val baselineNotes = wellFormedRowLines.mapNotNull { BaselineNotes.parse(it).note }
      val labelBreakdown = BaselineNotes.summarize(baselineNotes, wellFormedRowLines.size - baselineNotes.size)
          ?.let { "\n  baseline labels: $it" } ?: ""
      logger.lifecycle(
          "pitest '$suiteName' debt ($source$age) — $totalSurvived survived, $totalNoCoverage no_coverage " +
              "across ${debt.size} class(es):\n" + lines.joinToString("\n") + labelBreakdown
      )
      // The same pointer check the verify runs, after the breakdown rather than before
      // it: this task is where a triager reads the counts and picks the next cluster, so
      // a label resolving to nothing is named at the moment it would be acted on — a
      // count is what makes a mistyped label look like finished triage.
      val undocumentedLabels = BaselineNotes.undocumentedLabels(baselineNotes) {
        readmeFile.takeIf { it.isFile }?.readText() ?: ""
      }
      if (undocumentedLabels.isNotEmpty()) {
        logger.warn(BaselineNotes.undocumentedLabelWarning(suiteName, undocumentedLabels))
      }
    }
  }

  qualityGate.configure { dependsOn(pitestTaskName) }
  convergeSuiteNames.add(suiteName)
  certificationSuiteNames.add(suiteName)
  pitestConvergeSnapshot.configure { dependsOn(pitestTaskName) }
  // ordered, not depended on: a combined '<suites> pitestModeSnapshot' invocation must
  // not stash before the runs finish — or clear a report the verify finalizer still reads
  pitestModeSnapshot.configure { mustRunAfter(pitestTaskName, "${pitestTaskName}Verify") }

  // Shared JavaExec configuration for the ratchet run, the converge second round, and
  // the mutator trial (which redirects the report and swaps the mutator set).
  // Minion-side test failures repeat once per mutant that reruns the test, burying
  // the useful output under identical stack traces. First occurrence passes through;
  // repeats are counted and summarized after the run.
  //
  // Both of the process's streams are filtered: PIT logs through java.util.logging,
  // whose default console handler writes to *stderr*, so a stdout-only filter sees
  // none of the minion chatter it exists to collapse. The two streams share the
  // seen-set and the counter — a repeat is a repeat whichever stream carried it —
  // and Gradle pumps each on its own reader thread, hence the concurrent state; the
  // partial-line buffer stays per-stream so interleaved writes cannot splice.
  class MinionLineFilter(
      private val delegate: OutputStream,
      private val seen: MutableSet<String>,
      private val suppressed: AtomicInteger
  ) : OutputStream() {
    // (constructed only through MinionFilters, which owns the shared state)
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
      buffer.write(b)
      if (b == '\n'.code) {
        flushLine()
      }
    }

    private fun flushLine() {
      val line = buffer.toString(Charsets.UTF_8)
      buffer.reset()
      if (line.contains("PIT >> INFO : MINION :")) {
        val content = line.substringAfter("MINION :").trim()
        if (!seen.add(content)) {
          suppressed.incrementAndGet()
          return
        }
      }
      delegate.write(line.toByteArray())
    }

    override fun flush() {
      delegate.flush()
    }

    override fun close() {
      if (buffer.size() > 0) {
        flushLine()
      }
      delegate.flush()
    }
  }

  // One filter per process stream, sharing the seen-set and counter (a repeat
  // is a repeat whichever stream carried it); created at execution time so the
  // configuration cache never sees a live stream.
  class MinionFilters {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val suppressed = AtomicInteger()
    val out = MinionLineFilter(System.out, seen, suppressed)
    val err = MinionLineFilter(System.err, seen, suppressed)

    fun closeAndCount(): Int {
      out.close()
      err.close()
      return suppressed.get()
    }
  }

  fun pitestExec(
      reportSubdir: String,
      mutatorsSource: Provider<String>,
      withHistory: Boolean,
      // when false (the mutator trial) a non-zero exit is a tolerated result, not a
      // failure; otherwise doLast re-raises it after the filters are closed
      enforceExit: Boolean = true
  ): JavaExec.() -> Unit = {
    dependsOn(compileForPitest)
    javaLauncher.convention(evidenceJavaLauncher)
    usesService(pitestExecutionLock)
    usesService(hardeningCertificationSession)
    val historyLicensed = mutationHistoryAvailable
    // This edge is inert in ordinary runs (preflight is not scheduled), but when
    // any alias/abbreviation reaches hardeningCertify it orders activation before
    // every PIT flavour without making normal qualityGate depend on certification.
    mustRunAfter(hardeningCertifyPreflight)
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    classpath = pitest
    // the module test plumbing patches resources into the module instead of exposing
    // them on testRuntimeClasspath, so the tools get the processed resource dirs
    // explicitly
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    val classPathArg = files(
      mutationClassesDir,
      evidenceClasspathFiles,
    ).elements.map { locations ->
      "--classPath=" + locations
          .map { it.asFile.absolutePath }
          .joinToString(",")
    }
    // '-PmutateOnly=<glob[,glob]>' narrows the mutated classes for a fast
    // kill-and-rerun iteration loop. The report it produces is partial, so the
    // run stamps a '.scoped' marker and every baseline-touching consumer
    // (verify's ratchet, refresh, union, mode snapshots) refuses to treat it
    // as evidence. Tests still run in full: coverage targeting is unchanged.
    val mutateOnly = providers.gradleProperty("mutateOnly")
    val targetClassesArg = mutateOnly.map { "--targetClasses=$it" }
        .orElse(suite.targetClasses.map { "--targetClasses=" + it.joinToString(",") })
    // a map lambda returning null leaves the provider absent, dropping the argument
    val excludedClassesArg = allExcludedClasses.map { excluded ->
      if (excluded.isEmpty()) null else "--excludedClasses=" + excluded.joinToString(",")
    }
    val targetTestsArg = suite.targetTests.map { "--targetTests=$it" }
    val mutatorsArg = mutatorsSource.map { "--mutators=$it" }
    val threadsArg = suite.threads.map { "--threads=$it" }
    val timeoutFactorArg = suite.timeoutFactor.map { "--timeoutFactor=$it" }
    val timeoutConstArg = suite.timeoutConst.map { "--timeoutConst=$it" }
    val sourceDirsArg = "--sourceDirs=" + layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    val reportDirArg = "--reportDir=" + layout.buildDirectory.dir("reports/pitest/$reportSubdir").get().asFile.absolutePath
    // Incremental analysis: one rolling history file per suite, deliberately outside
    // build/ so 'clean' does not erase the accumulated results, and git-ignored
    // because it is machine-local state. Input and output are the same file; on the
    // first run the input does not exist yet and PIT starts fresh. The lifecycle line
    // keeps reuse honest — with history active a fast run is expected, so the log
    // must say why; hardeningCertify disables history automatically and re-earns
    // every status.
    val scopedMarker = layout.buildDirectory.file("reports/pitest/$reportSubdir/.scoped")
    val bindsSuiteEvidence = reportSubdir == suiteName
    val completedEvidence = layout.buildDirectory.file("reports/pitest/$reportSubdir/.evidence.tsv")
    val evidenceInvocation = layout.buildDirectory.file("reports/pitest/$reportSubdir/.evidence-invocation")
    // Defer PIT's non-zero exit to doLast so a failed run still closes the minion
    // filters — otherwise the exec action throws, doLast is skipped, and the
    // suppressed-count summary and any buffered partial line (now including stderr,
    // where the last bytes before a crash live) are lost.
    isIgnoreExitValue = true
    // holder so doFirst can hand the execution-time filters to doLast without
    // the configuration cache trying to serialize a live stream
    // Same capture hazard as the evidence helpers: 'hardeningCertificationSession' and
    // 'project' are script-level, so the actions below read these locals instead. A
    // build-service Provider serializes; a script object does not, and 'Task.project'
    // at execution time is refused outright.
    val certificationSession = hardeningCertificationSession
    val certifyingProjectPath = project.path
    val minionFilters = AtomicReference<MinionFilters?>()
    val preRunEvidence = AtomicReference<PitestEvidence?>()
    val historyForAttempt = AtomicReference<Boolean?>()
    // The '.running' sentinel: written before PIT starts, cleared only after a
    // clean exit (below the assert, like the scope marker). PIT writes the CSV
    // incrementally, so a crashed or interrupted run leaves a partial report
    // that LOOKS complete — and the verify runs as this task's finalizer, so
    // without the sentinel a same-invocation refresh flag rewrites the baseline
    // from whatever fraction of the population PIT reached before dying.
    val runningMarker = layout.buildDirectory.file("reports/pitest/$reportSubdir/.running")
    val historyFile = layout.projectDirectory.file(".pitest-history/${suite.name}.hist").asFile
    fun historyActiveNow() = withHistory && historyLicensed &&
        !certificationSession.get().isActive(certifyingProjectPath)
    doFirst {
      this as JavaExec
      // the default (null) standard output and error both forward to the console; the
      // filters keep that destination while deduplicating repeated minion log lines
      val filters = MinionFilters()
      standardOutput = filters.out
      errorOutput = filters.err
      minionFilters.set(filters)
      val historyActive = historyActiveNow()
      historyForAttempt.set(historyActive)
      if (historyActive) {
        historyFile.parentFile.mkdirs()
        logger.lifecycle("pitest '$suiteName': arcmutate history active — $historyFile")
      }
      val invocationId = UUID.randomUUID().toString()
      if (bindsSuiteEvidence) {
        // Record the attempt before touching any sentinel/evidence path. If a
        // filesystem or later doFirst action fails, a record writer in the finalizer
        // cannot fall back to an older completed report from disk.
        certificationSession.get().startAttempt(certifyingProjectPath, suiteName, invocationId)
      }
      runningMarker.get().asFile.also {
        it.parentFile.mkdirs()
        it.writeText("")
      }
      if (bindsSuiteEvidence) {
        completedEvidence.get().asFile.delete()
        evidenceInvocation.get().asFile.writeText(invocationId + "\n")
        val scope = mutateOnly.orNull?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }
        preRunEvidence.set(evidenceSnapshot(
            invocationId = invocationId,
            reportSha256 = "",
            scope = scope,
            historyAssisted = historyActive,
            javaVersion = javaLauncher.get().metadata.javaRuntimeVersion,
        ))
      }
    }
    doLast {
      // Close the filters first, before the deferred failure is re-raised, so the
      // summary and any buffered tail survive a failing run.
      minionFilters.get()?.let { filters ->
        val suppressed = filters.closeAndCount()
        if (suppressed > 0) {
          logger.lifecycle(
              "pitest: suppressed $suppressed repeated minion log line(s) — " +
                  "first occurrence of each is above"
          )
        }
      }
      // Re-raise PIT's non-zero exit here (deferred by isIgnoreExitValue above). A
      // failed run is not evidence, so its markers must not be rewritten — the
      // marker updates stay below the assert, and the '.running' sentinel above
      // survives a failure (or a hard interruption, which skips this doLast
      // entirely) so every report-facing consumer refuses the partial report.
      // Non-enforced flavours (the mutator trial: a zero-fire exit is a result)
      // return instead of asserting, for the same reason — an exit code this task
      // deliberately tolerates is still not a completed observation, so the
      // sentinel stays and any partial CSV the failure left is refused rather
      // than tabulated.
      if (enforceExit) executionResult.get().assertNormalExitValue()
      if (executionResult.get().exitValue != 0) return@doLast
      val marker = scopedMarker.get().asFile
      val historyActive = historyForAttempt.get() ?: historyActiveNow()
      val scope = mutateOnly.orNull?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }
      var completedRunEvidence: PitestEvidence? = null
      if (bindsSuiteEvidence) {
        val report = marker.parentFile.resolve("mutations.csv")
        if (!report.isFile) {
          throw GradleException(
              "pitest '$suiteName' exited successfully but wrote no CSV report at $report — " +
                  "cannot create completed-run evidence")
        }
        val before = preRunEvidence.get() ?: throw GradleException(
            "pitest '$suiteName' has no pre-run evidence fingerprint — refusing to certify its report")
        val after = evidenceSnapshot(
            invocationId = before.invocationId,
            reportSha256 = "",
            scope = scope,
            historyAssisted = historyActive,
            javaVersion = javaLauncher.get().metadata.javaRuntimeVersion,
        )
        val changedInputs = before.differences(after)
        if (changedInputs.isNotEmpty()) {
          throw GradleException(
              "pitest '$suiteName': evidence inputs changed while PIT was running — refusing to commit " +
                  "completed evidence; re-run against a stable checkout:\n" +
                  changedInputs.joinToString("\n") { "  $it" })
        }
        completedRunEvidence = before.copy(reportSha256 = PitestEvidence.sha256(report))
      }
      if (scope == PitestEvidence.FULL_SCOPE) marker.delete() else marker.writeText(scope + "\n")
      // A history-assisted report is reuse, not observation; the marker lets
      // pitestModeSnapshot and the verify's record-writing flags refuse it. Kept
      // in lockstep with the report like the scope marker — written when history
      // was active, DELETED when it was not, so a '-PnoMutationHistory' rerun
      // does not inherit the previous run's marker and read as reuse forever.
      val historyMarker = marker.parentFile.resolve(".history-assisted")
      if (historyActive) historyMarker.writeText("") else historyMarker.delete()
      if (bindsSuiteEvidence) {
        val invocationFile = evidenceInvocation.get().asFile
        val evidence = completedRunEvidence ?: error("completed PIT evidence was not assembled")
        check(invocationFile.readText().trim() == evidence.invocationId) {
          "pitest '$suiteName' invocation marker changed while PIT was running"
        }
        BaselineFiles.writeAtomically(completedEvidence.get().asFile, evidence.render())
        certificationSession.get().recordCompleted(certifyingProjectPath, suiteName, evidence)
        invocationFile.delete()
      }
      // The report becomes consumable only after every completion marker and, for a
      // normal suite run, its bound evidence manifest are durable. If any step above
      // throws, retain `.running` so the verify finalizer cannot treat the half-committed
      // report as a record-writing input.
      runningMarker.get().asFile.delete()
    }
    argumentProviders.add {
      buildList {
        add(classPathArg.get())
        add(targetClassesArg.get())
        excludedClassesArg.orNull?.let(::add)
        add(targetTestsArg.get())
        add(sourceDirsArg)
        add(reportDirArg)
        add(mutatorsArg.get())
        add("--outputFormats=HTML,XML,CSV")
        add("--timestampedReports=false")
        add(threadsArg.get())
        add(timeoutFactorArg.get())
        add(timeoutConstArg.get())
        if (historyActiveNow()) {
          if (historyFile.isFile) {
            add("--historyInputLocation=" + historyFile.absolutePath)
          }
          add("--historyOutputLocation=" + historyFile.absolutePath)
          add("--features=+arcmutate_history")
        }
      }
    }
  }

  val configurePitestExec = pitestExec(suiteName, suite.mutators, withHistory = true)

  val runAfter = previousPitestTask
  previousPitestTask = pitestTaskName
  tasks.register<JavaExec>(pitestTaskName) {
    finalizedBy(verify)
    runAfter?.let { mustRunAfter(it) }
    group = "verification"
    description = "PIT mutation testing of the '${suite.name}' classes against their tests."
    // Mutator-blindness advice. A mutant that is never generated cannot
    // survive, so a suite whose subject is BigDecimal/BigInteger math can sit
    // green for years with that math unmutated and nothing anywhere says so.
    // Runs after compileForPitest (a dependency of this task), reads the very
    // classes about to be mutated, and only speaks when the matching mutator is
    // absent — so it goes quiet the moment the gap is closed or measured.
    // Plain values only: the configuration cache cannot serialize the script.
    val adviceClassesDir = mutationClassesDir
    val adviceTargets = suite.targetClasses
    val adviceExcludes = allExcludedClasses
    val adviceMutators = suite.mutators
    val adviceDeclined = suite.declinedMutators
    val adviceTrialTask = path.substringBeforeLast(':') + ":pitestMutatorTrial"
    // the mutation recompile contains the test sources too (they share the mutated
    // packages), so the audit tells production from prey the same way the
    // mutated-fakes warning does: by whether the source sits under a test src dir
    val adviceTestSourceDirs = sourceSets.test.get().java.srcDirs
    val adviceSiblingTargets = suiteTargetGlobs
    val adviceSiblingExcludes = suiteExcludedGlobs
    val adviceDeclinedExclusions = suite.declinedExclusionAudits
    val adviceAdvisoryLog = hardeningAdvisoryLog
    val adviceAdvisoryScope = suiteAdvisoryScope
    usesService(adviceAdvisoryLog)
    doFirst {
      val classesDir = adviceClassesDir.get().asFile
      // No recompiled classes means nothing was scanned, and "found nothing" would
      // then wrongly read as "the declines have no subject left".
      if (classesDir.isDirectory) {
        // The inverse of the mutated-fakes warning: a production class matched by an
        // exclusion glob leaves the population silently — the globs and the sources
        // they protect must define the same set.
        val siblingScopes = adviceSiblingTargets.get()
            .filterKeys { it != suiteName }
            .map { (sibling, targets) ->
              ExclusionAudit.SuiteScope(targets, adviceSiblingExcludes.get()[sibling].orEmpty())
            }
        val swallowed = ExclusionAudit.swallowedProductionClasses(
            classesDir, adviceTargets.get(), adviceExcludes.get(), adviceTestSourceDirs, siblingScopes
        )
        // Deliberate opt-outs are the one exclusion category the scan cannot derive
        // — "generated bindings" is a judgment, not a property of the globs — so a
        // suite argues them with declineExclusionAudit and they leave the report.
        // The records keep earning themselves: a blank reason suppresses nothing and
        // is named, and one that stops matching is named as deletable.
        val declined = ExclusionAudit.applyDeclines(swallowed, adviceDeclinedExclusions.get())
        ExclusionAudit.warning(suiteName, declined.reported)?.let {
          logger.warn(it)
          // recorded like every other warn-level advisory, so it reaches the
          // end-of-build summary instead of scrolling past mid-build
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope,
              "${declined.reported.size} production class(es) swallowed by excludedClasses"
          )
        }
        ExclusionAudit.staleDeclineWarning(suiteName, declined.staleGlobs)?.let {
          logger.warn(it)
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope, "${declined.staleGlobs.size} stale exclusion decline(s)")
        }
        ExclusionAudit.blankDeclineWarning(suiteName, declined.blankGlobs)?.let {
          logger.warn(it)
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope, "${declined.blankGlobs.size} exclusion decline(s) without a reason")
        }
        val advice = MutatorAdvice.advise(
            MutatorAdvice.scan(
                classesDir,
                adviceTargets.get(),
                adviceExcludes.get(),
                adviceMutators.get(),
            ),
            adviceMutators.get(),
            adviceDeclined.get(),
        )
        advice.findings.forEach { finding ->
          logger.warn(
              "pitest '$suiteName': ${finding.classCount} mutated class(es) call ${finding.label} arithmetic " +
                  "(${finding.callCount} call site(s)), which the enabled mutator set cannot mutate — " +
                  "those computations are currently unmutated, not proven.\n" +
                  "  measure it: ./gradlew $adviceTrialTask -PtrialMutators=${finding.mutator}\n" +
                  "  then enable what fires (mutators = \"...,${finding.mutator}\") and record the numbers, " +
                  "or record the measured decision not to: " +
                  "declineMutator(\"${finding.mutator}\", \"what the trial generated, and why it was not worth it\")."
          )
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope,
              "${finding.classCount} class(es) use unmutated ${finding.label} arithmetic")
        }
        // A suppression that has outlived its subject is worse than none: it reads as
        // a settled decision about code that no longer exists.
        advice.staleDeclines.forEach { stale ->
          logger.warn(
              "pitest '$suiteName': the recorded decline of ${stale.mutator} is stale — ${stale.why}."
          )
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope, "stale ${stale.mutator} mutator decline")
        }
      }
    }
    // Configure after the advice action: Gradle's doFirst prepends, so pitestExec's
    // attempt/sentinel action runs first and a failure in advice cannot expose stale
    // evidence to the verify finalizer.
    configurePitestExec()
  }

  // The converge second round: same run, no ratchet finalizer, ordered after the
  // snapshot cleared round one's reports.
  val round2Name = "${pitestTaskName}ConvergeRound2"
  val round2After = previousRound2Task
  previousRound2Task = round2Name
  tasks.register<JavaExec>(round2Name) {
    description = "Internal to pitestConverge: second '${suite.name}' PIT run for the per-mutant diff."
    mustRunAfter(pitestConvergeSnapshot)
    round2After?.let { mustRunAfter(it) }
    configurePitestExec()
    val certificationSession = hardeningCertificationSession
    val convergenceProjectPath = project.path
    // Added after pitestExec: doFirst prepends, so this refusal runs before the
    // attempt marker, report sentinel, or process stream is touched.
    doFirst {
      if (certificationSession.get().isActive(convergenceProjectPath)) {
        throw GradleException(
            "pitestConverge cannot run inside hardeningCertify: convergence's unverified round two would " +
                "replace the strict-verified report the certification receipt must bind. Run the two " +
                "workflows in separate Gradle invocations.")
      }
    }
  }
  hardeningCertify.configure { mustRunAfter(round2Name) }
  pitestConverge.configure { dependsOn(round2Name) }

  // The mutator-trial run: only the candidate mutators, no ratchet, no history, and a
  // report directory of its own so the suite's real report and baseline are untouched.
  val trialTaskName = "${pitestTaskName}MutatorTrial"
  val trialAfter = previousTrialTask
  previousTrialTask = trialTaskName
  tasks.register<JavaExec>(trialTaskName) {
    description = "Internal to pitestMutatorTrial: '${suite.name}' with only the -PtrialMutators candidates."
    trialAfter?.let { mustRunAfter(it) }
    val trialReportDir = layout.buildDirectory.dir("reports/pitest/$suiteName-trial")
    // captured locally so the doFirst lambda does not hold the script instance
    val trialMutators = trialMutatorsProperty
    // A zero-fire trial is a result, not a failure: PIT exits non-zero when the mutator
    // set generates nothing, and the aggregate reads a missing report as zero fired —
    // so enforceExit stays off (pitestExec already runs with isIgnoreExitValue).
    pitestExec("$suiteName-trial", trialMutatorsProperty, withHistory = false, enforceExit = false).invoke(this)
    // Registered AFTER pitestExec on purpose: doFirst PREPENDS, so this wipe runs
    // before the exec's '.running' sentinel write — registered the other way
    // around, the wipe erased the freshly written sentinel and trial runs were
    // the one pitestExec flavour with no interruption protection at all.
    doFirst {
      if (!trialMutators.isPresent) {
        throw GradleException(
            "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only " +
                "(e.g. EXPERIMENTAL_NAKED_RECEIVER), not the suite's existing set"
        )
      }
      // A failed run writes no report; without this delete it would read as the
      // previous trial's numbers.
      trialReportDir.get().asFile.deleteRecursively()
    }
  }
  pitestMutatorTrial.configure { dependsOn(trialTaskName) }
}

hardening.fuzz.all {
  val target = this

  // libFuzzer silently truncates any input longer than max_len when loading a corpus:
  // a fuzz run explores a clipped copy of an oversized seed, and the minimize merge
  // re-hashes the clip — adopting it under a hash name and deleting the named original,
  // which quietly degrades what the seed pins (casebook: the seed clipped by its own
  // max_len). Both consumers refuse up front instead; the fix is a one-line maxLen bump
  // or a deliberate re-minimization of the seed.
  val seedLenCheck = tasks.register("fuzz" + target.name.replaceFirstChar(Char::uppercase) + "SeedLenCheck") {
    description = "Internal to the '${target.name}' fuzz tasks: refuses seeds larger than the target's maxLen."
    val targetName = target.name
    val maxLen = target.maxLen
    val seedCorpus = target.seedCorpus
    val localCorpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    val adoptLocalCorpus = providers.gradleProperty("adoptLocalCorpus").isPresent
    doLast {
      val cap = maxLen.orNull ?: return@doLast
      if (cap <= 0) {
        throw GradleException("fuzz '$targetName': maxLen must be positive, was $cap")
      }
      fun oversizedIn(dir: File?) =
          dir?.listFiles()?.filter { it.isFile && it.length() > cap }.orEmpty().sortedBy { it.name }
      val committed = oversizedIn(seedCorpus.orNull?.asFile)
      val local = if (adoptLocalCorpus) oversizedIn(localCorpusDir) else emptyList()
      if (committed.isEmpty() && local.isEmpty()) return@doLast
      val listing = (committed.map { "  ${it.name} (${it.length()} bytes, committed)" } +
          local.map { "  ${it.name} (${it.length()} bytes, local corpus)" }).joinToString("\n")
      throw GradleException(
          "fuzz '$targetName': ${committed.size + local.size} seed(s) exceed maxLen=$cap. libFuzzer " +
              "truncates oversized inputs on load — a fuzz run would explore a clipped copy, and a merge " +
              "would adopt the clip under a hash name and delete the named original:\n$listing\n" +
              "Raise the target's maxLen to cover its largest committed seed, or shrink the seed deliberately.")
    }
  }

  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase)) {
    group = "verification"
    description = "Coverage-guided fuzzing of the '${target.name}' target with Jazzer; -PmaxFuzzTime=<seconds> (default 60)."
    mustRunAfter(fuzzAllPreflight)
    dependsOn(seedLenCheck)
    dependsOn(validateFuzzBudget)
    // Jazzer gets its own recompile: it may not read the class files
    // 'mutationBytecodeRelease' targets.
    dependsOn(compileForFuzz)
    mainClass = "com.code_intelligence.jazzer.Jazzer"
    // Jazzer only instruments classes on the JVM classpath, not its '--cp' argument.
    // The recompiled root stands in for this project's class outputs; dependency jars
    // and the processed resource dirs (patched into the module rather than exposed on
    // testRuntimeClasspath) ride along so the target's collaborators resolve at run
    // time.
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }
    // Jazzer loads its agent dynamically and its driver uses Unsafe and native
    // libraries; pre-authorize them so runs are not buried in JDK warnings.
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
    val corpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    doFirst {
      corpusDir.mkdirs()
    }
    val targetClassArg = target.targetClass.map { "--target_class=$it" }
    // locals so the lambda below does not capture the script instance, which the
    // configuration cache cannot serialize
    val maxFuzzTimeArg = maxFuzzTime.map { "-max_total_time=$it" }
    val maxLenArg = target.maxLen.map { "-max_len=$it" }
    // committed seeds are passed as a trailing read-only corpus: libFuzzer replays every
    // input from every listed dir but only writes newly interesting ones to the first
    val seedCorpusDir = target.seedCorpus.map { it.asFile.absolutePath }
    argumentProviders.add {
      buildList {
        add(targetClassArg.get())
        add(maxFuzzTimeArg.get())
        maxLenArg.orNull?.let(::add)
        add(corpusDir.absolutePath)
        seedCorpusDir.orNull?.let(::add)
      }
    }
  }

  // libFuzzer's '-merge=1' copies into the first (output) directory only the inputs
  // that add coverage, smallest first — corpus dedup as a task. By default the only
  // source is the committed seed corpus (pure dedup); '-PadoptLocalCorpus' adds
  // whatever local 'fuzz<Target>' runs accumulated under build/ as a second source,
  // folding locally found interesting inputs into the committed set — a deliberate
  // adoption (it can be megabytes of hash-named files), not a side effect of dedup.
  // The merge writes into a fresh staging dir and the seed corpus is replaced only
  // from a non-empty result, so a failed merge can never wipe a committed corpus.
  // Seeds whose content survives keep their committed file name (corpora name seeds
  // meaningfully — an account address, a minimized finding); only genuinely new
  // inputs arrive under libFuzzer's hash names — the seedLenCheck above keeps
  // truncation from forging a "new" input out of an oversized named seed.
  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase) + "Minimize") {
    group = "verification"
    description = "Minimizes the '${target.name}' seed corpus with libFuzzer -merge=1; -PadoptLocalCorpus also folds in inputs found by local fuzz runs."
    dependsOn(seedLenCheck)
    dependsOn(compileForFuzz)
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    mainClass = "com.code_intelligence.jazzer.Jazzer"
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
    val targetName = target.name
    val seedCorpus = target.seedCorpus
    val stagingDir = layout.buildDirectory.dir("fuzz/${target.name}-minimized").get().asFile
    val localCorpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    val minimizeTargetClassArg = target.targetClass.map { "--target_class=$it" }
    val minimizeMaxLenArg = target.maxLen.map { "-max_len=$it" }
    val adoptLocalCorpus = providers.gradleProperty("adoptLocalCorpus").isPresent
    doFirst {
      val corpus = seedCorpus.orNull?.asFile ?: throw GradleException(
          "fuzz target '$targetName' declares no seedCorpus — nothing to minimize into. " +
              "Commit a seed corpus first (see HARDENING.md 'Fuzzing').")
      if (corpus.listFiles()?.any { it.isFile } != true) {
        throw GradleException(
            "fuzz target '$targetName': seed corpus at $corpus is missing or empty — a merge cannot start from nothing.")
      }
      stagingDir.deleteRecursively()
      stagingDir.mkdirs()
    }
    argumentProviders.add {
      buildList {
        add(minimizeTargetClassArg.get())
        add("-merge=1")
        minimizeMaxLenArg.orNull?.let(::add)
        add(stagingDir.absolutePath)
        add(seedCorpus.get().asFile.absolutePath)
        if (adoptLocalCorpus && localCorpusDir.listFiles()?.any { it.isFile } == true) {
          add(localCorpusDir.absolutePath)
        }
      }
    }
    doLast {
      val corpus = seedCorpus.get().asFile
      val merged = stagingDir.listFiles()?.filter { it.isFile }.orEmpty()
      if (merged.isEmpty()) {
        throw GradleException(
            "fuzz '$targetName': the merge produced an empty corpus — refusing to touch $corpus. " +
                "Staging output: $stagingDir; the committed seed corpus is unchanged.")
      }
      fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
          .digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
      val before = corpus.listFiles()?.filter { it.isFile }.orEmpty()
      val beforeBytes = before.sumOf { it.length() }
      val originalByHash = before.associateBy(::sha256)
      val keep = mutableSetOf<File>()
      var adopted = 0
      for (file in merged) {
        val original = originalByHash[sha256(file)]
        if (original != null) {
          keep.add(original)
        } else {
          file.copyTo(corpus.resolve(file.name), overwrite = true)
          adopted++
        }
      }
      val removed = before.filterNot { it in keep }
      removed.forEach { it.delete() }
      val afterFiles = corpus.listFiles()?.filter { it.isFile }.orEmpty()
      logger.lifecycle(
          "fuzz '$targetName': corpus minimized ${before.size} -> ${afterFiles.size} file(s) " +
              "($beforeBytes -> ${afterFiles.sumOf { it.length() }} bytes) at $corpus — " +
              "$adopted newly adopted, ${removed.size} redundant removed, surviving seeds keep their names. " +
              "Review the diff before committing; update the provenance README next to the corpus.")
    }
  }
}

// A committed corpus that only runs when someone remembers to fuzz is a directory of
// files, not a regression suite. For every fuzz target with a seedCorpus, a replay test
// is generated into the test source set: each seed runs through the harness inside
// 'test' (and therefore 'check'), on the module path like any other test — and under
// PIT the replay participates as a killer. No hand-written replay class per harness,
// and no way to forget one.
//
// Keep the model in managed Properties populated per target. The generator task may
// legitimately be realized by another plugin before the consumer's hardening block;
// iterating the extension in the task's configuration action would then freeze an
// empty/default model and silently omit targets added later.
val replayTargetNames = objects.listProperty<String>().convention(emptyList())
val replayTargetClasses = objects.listProperty<String>().convention(emptyList())
val replayCorpusPaths = objects.listProperty<String>().convention(emptyList())
val replayResourcePaths = objects.listProperty<String>().convention(emptyList())
val replayCorpusDeclines = objects.listProperty<String>().convention(emptyList())
// Keep this collection live through the rest of consumer configuration. Snapshotting
// srcDirs here misses a custom test-resources directory added after the plugin (or
// after eager task realization) and bakes its corpus as an absolute machine path.
val replayTestResourceDirs = sourceSets.test.get().resources.sourceDirectories.elements
hardening.fuzz.configureEach {
  val replaySeedCorpus = seedCorpus
  replayTargetNames.add(name)
  // Preserve an unset targetClass as an explicit model value. Adding the absent
  // Property directly makes the whole ListProperty absent, so Gradle's generic
  // task-input validation fires before our target-specific, path-safe diagnostic.
  replayTargetClasses.add(targetClass.orElse(""))
  replayCorpusPaths.add(replaySeedCorpus.map { it.asFile.absolutePath }.orElse(""))
  replayResourcePaths.add(replaySeedCorpus.zip(replayTestResourceDirs) { directory, resourceDirs ->
    resourceDirs.firstNotNullOfOrNull { resourceDir ->
      val relative = directory.asFile.relativeToOrNull(resourceDir.asFile)
      if (relative == null || relative.path.startsWith("..")) null else relative.invariantSeparatorsPath
    } ?: ""
  }.orElse(""))
  replayCorpusDeclines.add(declinedSeedCorpus.orElse(""))
}
val generateFuzzReplayTests = tasks.register("generateFuzzReplayTests") {
  description = "Generates seed-corpus replay tests for fuzz targets that declare a seedCorpus."
  val outputDir = layout.buildDirectory.dir("generated-sources/fuzz-replay/java")
  val advisoryLog = hardeningAdvisoryLog
  val advisoryScope = (if (project.path == ":") "" else "${project.path} ") + "fuzz corpus"
  usesService(advisoryLog)
  outputs.dir(outputDir)
  val targetNames = replayTargetNames
  val targetClasses = replayTargetClasses
  val corpusPaths = replayCorpusPaths
  val resourcePaths = replayResourcePaths
  val corpusDeclines = replayCorpusDeclines
  inputs.property("targetNames", targetNames)
  inputs.property("targetClasses", targetClasses)
  inputs.property("corpusPaths", corpusPaths)
  inputs.property("resourcePaths", resourcePaths)
  inputs.property("corpusDeclines", corpusDeclines)
  // Gradle may discard stale declared outputs before running task actions. Validate
  // the complete model in the execution predicate so malformed configuration cannot
  // erase the last good generated tree before doLast gets a chance to object.
  onlyIf("all corpus-backed fuzz targets have safe Java class names") {
    val columns = listOf(
        targetNames.get(), targetClasses.get(), corpusPaths.get(), resourcePaths.get(), corpusDeclines.get())
    check(columns.map(List<*>::size).distinct().size == 1) {
      "fuzz replay target model columns have different lengths"
    }
    columns.first().indices.filter { columns[2][it].isNotEmpty() }.forEach { index ->
      HardeningNames.requireJavaQualifiedName(
          "fuzz target '${columns[0][index]}' targetClass", columns[1][index], requirePackage = true)
    }
    val duplicateClasses = columns.first().indices.filter { columns[2][it].isNotEmpty() }
        .groupBy { columns[1][it] }
        .filterValues { indices -> indices.size > 1 }
    if (duplicateClasses.isNotEmpty()) {
      throw GradleException(
          "generateFuzzReplayTests: corpus-backed targets share a targetClass and would overwrite " +
              "one generated replay test: " + duplicateClasses.entries.sortedBy { it.key }
                  .joinToString("; ") { (targetClass, indices) ->
                    "$targetClass (${indices.map { columns[0][it] }.sorted().joinToString(", ")})"
                  })
    }
    true
  }
  doLast {
    data class ReplayTarget(
      val name: String,
      val targetClass: String,
      val corpusPath: String,
      val resourcePath: String,
      val decline: String,
    )
    val columns = listOf(
        targetNames.get(), targetClasses.get(), corpusPaths.get(), resourcePaths.get(), corpusDeclines.get())
    check(columns.map(List<*>::size).distinct().size == 1) {
      "fuzz replay target model columns have different lengths"
    }
    val configuredTargets = columns.first().indices.map { index ->
      ReplayTarget(
          columns[0][index], columns[1][index], columns[2][index], columns[3][index], columns[4][index])
    }
    // A target with no seedCorpus generates no replay test, so nothing it has ever
    // found is re-run by check. A nonblank decline is the only intentional opt-out.
    val corpusless = configuredTargets
        .filter { it.corpusPath.isEmpty() && it.decline.isBlank() }
        .map { it.name }
        .sorted()
    // Declines rot too: a target that has since gained a corpus, or that records a
    // reason-less decline, is carrying a suppression that argues for nothing.
    val staleDeclines = configuredTargets.mapNotNull { target ->
      when {
        target.decline.isEmpty() -> null
        target.decline.isBlank() -> target.name to
            "it carries no reason, so it suppresses nothing — record why no corpus is needed, or drop it"
        target.corpusPath.isNotEmpty() -> target.name to
            "the target now declares a seedCorpus, so the decline contradicts it"
        else -> null
      }
    }.sortedBy { it.first }
    // Validate every target before replacing the output tree. A malformed class name
    // is a configuration error, not permission to erase the last good generated tests.
    val validatedTargets = configuredTargets.filter { it.corpusPath.isNotEmpty() }.map { target ->
      target.copy(targetClass = HardeningNames.requireJavaQualifiedName(
          "fuzz target '${target.name}' targetClass", target.targetClass, requirePackage = true))
    }
    check(validatedTargets.map { it.targetClass }.distinct().size == validatedTargets.size) {
      "corpus-backed fuzz target classes must be unique"
    }
    corpusless.forEach { name ->
      logger.warn(
          "fuzz target '$name' declares no seedCorpus: no replay test is generated, so nothing this " +
              "harness finds is re-run by 'check', and 'fuzz${name.replaceFirstChar(Char::uppercase)}Minimize' " +
              "will fail when reached.\n" +
              "  fix: seedCorpus = layout.projectDirectory.dir(\"src/test/resources/fuzz/$name\") " +
              "(a corpus under the test resources resolves hermetically), then commit at least one seed.\n" +
              "  A corpus is where a finding lands, so this holds even where a mutator reaches the format " +
              "from scratch — if it genuinely does not, record why: declineSeedCorpus(\"...\")."
      )
      advisoryLog.get().record(advisoryScope, "fuzz target '$name' declares no seedCorpus")
    }
    staleDeclines.forEach { (name, why) ->
      logger.warn("fuzz target '$name': the recorded seedCorpus decline is stale — $why.")
      advisoryLog.get().record(advisoryScope, "fuzz target '$name' has a stale seedCorpus decline")
    }
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    dir.mkdirs()
    validatedTargets.forEach { target ->
      val name = target.name
      val fqcn = target.targetClass
      val corpusPath = target.corpusPath
      val resourcePath = target.resourcePath
      val pkg = fqcn.substringBeforeLast('.')
      val simple = fqcn.substringAfterLast('.')
      val className = simple + "SeedReplayTest"
      val source = dir.resolve(pkg.replace('.', '/')).resolve("$className.java")
      source.parentFile.mkdirs()
      val resolveCorpus = if (resourcePath.isNotEmpty()) {
        """
        |    final var url = $className.class.getResource("/$resourcePath");
        |    org.junit.jupiter.api.Assertions.assertNotNull(url, "seed corpus missing from test resources: /$resourcePath");
        |    final var corpus = java.nio.file.Path.of(url.toURI());
        """.trimMargin()
      } else {
        """
        |    final var corpus = java.nio.file.Path.of("${corpusPath.replace("\\", "\\\\")}");
        |    org.junit.jupiter.api.Assertions.assertTrue(
        |        java.nio.file.Files.isDirectory(corpus), "seed corpus missing: " + corpus);
        """.trimMargin()
      }
      source.writeText(
          """
          |package $pkg;
          |
          |import org.junit.jupiter.api.Test;
          |
          |/// Generated by the sava-build hardening plugin ('generateFuzzReplayTests'): replays
          |/// the committed '$name' seed corpus through the harness inside 'check', so the
          |/// corpus cannot rot between fuzz runs — an emptied corpus fails, not passes.
          |/// Regenerated every build; do not edit. Seed provenance belongs in a README next
          |/// to (never inside) the corpus directory, where a file would itself become a seed.
          |final class $className {
          |
          |  @Test
          |  void replaysSeedCorpus() throws Exception {
          |$resolveCorpus
          |    try (final var files = java.nio.file.Files.list(corpus)) {
          |      final var seeds = files.filter(java.nio.file.Files::isRegularFile).sorted().toList();
          |      org.junit.jupiter.api.Assertions.assertFalse(seeds.isEmpty(), "empty seed corpus: " + corpus);
          |      for (final var seed : seeds) {
          |        $simple.fuzzerTestOneInput(java.nio.file.Files.readAllBytes(seed));
          |      }
          |    }
          |  }
          |}
          |""".trimMargin()
      )
    }
    if (validatedTargets.isNotEmpty()) {
      logger.info("generateFuzzReplayTests: ${validatedTargets.size} replay test(s) generated")
    }
  }
}
sourceSets.test {
  java.srcDir(generateFuzzReplayTests)
}

// One-shot adoption scaffolding (HARDENING.md 'Adopting in a new repo'): the pieces that
// are pure transcription. Never overwrites anything that exists.
tasks.register("hardeningInit") {
  group = "verification"
  description = "Scaffolds config/pitest/README.md, git-ignores .pitest-history/, and prints the adoption checklist."
  val readme = layout.projectDirectory.file("config/pitest/README.md").asFile
  val gitignore = rootProject.layout.projectDirectory.file(".gitignore").asFile
  val digest = HardeningTemplateDigest.SHA256_12
  doLast {
    if (readme.isFile) {
      logger.lifecycle("hardeningInit: $readme exists — left untouched")
    } else {
      readme.parentFile.mkdirs()
      readme.writeText(
          """
          |# Mutation-testing baseline & triage policy
          |
          |Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
          |run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
          |baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
          |format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
          |a trailing `# line N` tag, so editing above a mutated method churns nothing.
          |A full update refreshes every tag; a green prune refreshes matched retained
          |rows even when it drops nothing. Unions and format-only migration preserve
          |existing tags. Full policy — the three legal outcomes for a new
          |survivor, determinism requirements, targeting rules — lives in sava-build's
          |`HARDENING.md`.
          |
          |Never refresh with `-PupdateMutationBaseline` just to make the build pass:
          |kill the mutant, refactor it out of existence, or record its equivalence
          |reason below. A failure classifies each new row (`newly covered` vs shares an
          |accepted key vs unexplained) and closes with a churn tally: a newly covered
          |row is triage, not a refresh, and identical rows are sibling mutants of one
          |compound condition — the comparison is a multiset, so never hand-dedupe the
          |CSV. A row sharing an accepted key may also be a genuinely new mutant
          |inheriting the key's acceptance (the line-less key's documented blind spot);
          |read the report's line numbers before accepting.
          |
          |A baseline row may carry a `# note` before its line tag — `# untriaged` is
          |the conventional label for seeded debt. Notes are preserved across
          |`-PupdateMutationBaseline` / `-PunionMutationBaseline` rewrites, and the
          |verify task counts rows marked `# untriaged` so the debt stays a printed
          |number, not prose.
          |
          |## Untriaged debt
          |
          |A first baseline seeded from the pre-existing survivor population is triage
          |debt made explicit, not acceptance. List it here until each key is killed,
          |refactored away, or moved below with a reason.
          |
          |## Triaged equivalent mutants (accepted with reasons)
          |
          |Group by the principle that makes them equivalent (see the recurring families
          |in HARDENING.md); the baseline CSVs carry the exact keys.
          |
          |Shrinking a baseline is always an improvement; growing one requires a reason
          |here.
          |""".trimMargin()
      )
      logger.lifecycle("hardeningInit: wrote $readme")
    }
    val ignoreLine = ".pitest-history/"
    if (gitignore.isFile && gitignore.readText().contains(ignoreLine)) {
      logger.lifecycle("hardeningInit: .gitignore already covers $ignoreLine")
    } else {
      gitignore.appendText((if (gitignore.isFile && !gitignore.readText().endsWith("\n")) "\n" else "") +
          "\n# optional ArcMutate history (machine-local when an applicable licence is present)\n$ignoreLine\n")
      logger.lifecycle("hardeningInit: appended $ignoreLine to $gitignore")
    }
    logger.lifecycle(
        """
        |hardeningInit: remaining adoption steps (HARDENING.md 'Adopting in a new repo'):
        |  1. register mutation suites (wildcard targets + exclusions) and every
        |       meaningful fuzz target; zero fuzz targets is valid when the repo records why
        |  2. pin any unseeded randomness in the test suite
        |  3. seed each baseline: ./gradlew pitest<Suite> -PupdateMutationBaseline
        |  4. for suites whose summary reports timed-out mutants, seed the audited set:
        |       ./gradlew pitest<Suite> -PinitTimeoutAudit — then write each member's
        |       structural cause in config/pitest/README.md (HARDENING.md, audited-timeout bullet)
        |  5. run ./gradlew hardeningAgentTemplate and copy that exact version-matched
        |       agent-instructions template into AGENTS.md with:
        |       <!-- hardening-template sha256:$digest -->
        |  6. decide who owns the pre-release hardeningCertify run, and record it in AGENTS.md
        |  7. fuzz targets with a seedCorpus get a generated replay test automatically;
        |       document seed provenance in a README next to (never inside) the corpus dir
        |  8. own an explicit local fuzzAll -PmaxFuzzTime=<seconds> release budget;
        |       scheduled GitHub fuzz workflows are optional and are not certification
        |  9. optional: hardening.generateTestSupport = true generates shared socket/
        |       scheduler/logging test helpers; unrelated adopters should also set
        |       hardening.testSupportPackage to a package they own
        |       (HARDENING.md 'Shared test scaffolding')
        |""".trimMargin()
    )
  }
}

// Shared socket/concurrency/logging test helpers, generated on request
// ('hardening.generateTestSupport = true') instead of published: a handful of small
// classes is not worth a dependency in every consuming repo's test module, and generating
// them means they compile inside that module — visible on the module path and PIT's class
// path alike. See HARDENING.md 'Shared test scaffolding (generated)'.
val generateHardeningTestSupport = tasks.register("generateHardeningTestSupport") {
  description = "Generates the shared test-support sources when hardening.generateTestSupport is true."
  val outputDir = layout.buildDirectory.dir("generated-sources/hardening-support/java")
  outputs.dir(outputDir)
  val enabled = hardening.generateTestSupport
  val excludes = hardening.testSupportExcludes
  val packageName = hardening.testSupportPackage
  inputs.property("enabled", enabled)
  inputs.property("excludes", excludes)
  inputs.property("packageName", packageName)
  // Declared outputs may be cleaned before task actions. Validate in the execution
  // predicate so a bad package cannot erase the last good generated support tree
  // before doLast has a chance to object.
  onlyIf("the enabled hardening test-support package is a safe Java package") {
    if (enabled.get()) {
      HardeningNames.requireJavaQualifiedName(
          "hardening.testSupportPackage", packageName.get(), requirePackage = true)
    }
    true
  }
  doLast {
    val dir = outputDir.get().asFile
    val generate = enabled.get()
    if (!generate) {
      dir.deleteRecursively()
      dir.mkdirs()
      return@doLast
    }
    // Validate before deleting the previous output: a malformed package must not turn
    // a configuration error into data loss in the generated-source directory.
    val pkg = HardeningNames.requireJavaQualifiedName(
        "hardening.testSupportPackage", packageName.get(), requirePackage = true)
    val excluded = excludes.get().toSet()
    dir.deleteRecursively()
    val pkgDir = dir.resolve(pkg.replace('.', '/'))
    pkgDir.mkdirs()
    // each helper is skippable by simple name — 'JulRecorder' cannot compile in a test
    // module that does not read 'java.logging'
    fun generate(className: String, source: () -> String) {
      if (className !in excluded) {
        pkgDir.resolve("$className.java").writeText(source())
      }
    }
    generate("Ports") { """
        |package $pkg;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |public final class Ports {
        |
        |  /// An ephemeral port that was free at probe time. Socket tests should bind it on
        |  /// localhost and connect to 127.0.0.1 explicitly — never "localhost", whose ::1
        |  /// resolution can reach another JVM's wildcard bind on the same port number.
        |  public static int freePort() {
        |    try (final var socket = new java.net.ServerSocket(0)) {
        |      return socket.getLocalPort();
        |    } catch (final java.io.IOException e) {
        |      throw new java.io.UncheckedIOException(e);
        |    }
        |  }
        |
        |  private Ports() {
        |  }
        |}
        |""".trimMargin() }
    generate("RecordingExecutor") { """
        |package $pkg;
        |
        |import java.util.concurrent.Executor;
        |import java.util.concurrent.atomic.AtomicInteger;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Delegates while counting dispatches: turns "wire-invisible" executor configuration
        |/// into an assertable property (see HARDENING.md's test conventions).
        |public final class RecordingExecutor implements Executor {
        |
        |  private final Executor delegate;
        |  private final AtomicInteger dispatches = new AtomicInteger();
        |
        |  public RecordingExecutor(final Executor delegate) {
        |    this.delegate = delegate;
        |  }
        |
        |  @Override
        |  public void execute(final Runnable command) {
        |    dispatches.incrementAndGet();
        |    delegate.execute(command);
        |  }
        |
        |  public int dispatches() {
        |    return dispatches.get();
        |  }
        |}
        |""".trimMargin() }
    generate("ConcurrencyHarness") { """
        |package $pkg;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Deterministic sequencing for concurrency tests, distilled from the mutation
        |/// campaigns (see HARDENING.md's concurrency conventions): poll observable state
        |/// instead of sleeping on timing guesses, assert timing only as a lower bound
        |/// (machine load can lengthen an interval but never shorten it), and always
        |/// bound joins so a hung thread fails the test instead of the build.
        |/// Framework-neutral: failures throw AssertionError, which every test engine
        |/// reports as a failure.
        |public final class ConcurrencyHarness {
        |
        |  /// Polls the condition roughly every millisecond, failing after ~5 seconds.
        |  /// Use an observable side effect (a recorded call, a volatile flag, a thread
        |  /// state) as the condition — never a sleep of a guessed length.
        |  public static void awaitTrue(final String what, final java.util.function.BooleanSupplier condition) throws InterruptedException {
        |    for (int i = 0; i < 5_000; ++i) {
        |      if (condition.getAsBoolean()) {
        |        return;
        |      }
        |      Thread.sleep(1);
        |    }
        |    throw new AssertionError("timed out awaiting " + what);
        |  }
        |
        |  /// Polls until the thread reaches one of the given states — WAITING for an
        |  /// unbounded condition await, TIMED_WAITING for a bounded one. This is how a
        |  /// test proves "the worker is parked" before poking it, instead of sleeping
        |  /// and hoping.
        |  public static void awaitState(final Thread thread, final Thread.State... states) throws InterruptedException {
        |    awaitTrue(thread.getName() + " in " + java.util.Arrays.toString(states), () -> {
        |      final Thread.State current = thread.getState();
        |      for (final Thread.State state : states) {
        |        if (current == state) {
        |          return true;
        |        }
        |      }
        |      return false;
        |    });
        |  }
        |
        |  /// Joins with a bound; a still-alive thread is interrupted (so the test JVM
        |  /// can exit) and the test fails with the caller's explanation.
        |  public static void joinOrFail(final Thread thread, final long millis, final String what) throws InterruptedException {
        |    thread.join(millis);
        |    if (thread.isAlive()) {
        |      thread.interrupt();
        |      throw new AssertionError(what + " (thread '" + thread.getName() + "' still alive after " + millis + "ms)");
        |    }
        |  }
        |
        |  private ConcurrencyHarness() {
        |  }
        |}
        |""".trimMargin() }
    generate("JulRecorder") { """
        |package $pkg;
        |
        |import java.text.MessageFormat;
        |import java.util.ArrayList;
        |import java.util.List;
        |import java.util.logging.Handler;
        |import java.util.logging.Level;
        |import java.util.logging.LogRecord;
        |import java.util.logging.Logger;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Captures records published to a JUL logger while attached; use try-with-resources so
        |/// the handler always detaches. Needs 'java.logging' readable from the test module.
        |///
        |/// While attached the logger is forced to {@link Level#ALL} with parent handlers
        |/// detached, and both are restored on close. Without that, attaching to a logger a repo
        |/// silenced in test setup — a common pattern, and the one this replaces — would capture
        |/// nothing at all, and anything the logger did publish would still reach the console.
        |public final class JulRecorder implements AutoCloseable {
        |
        |  private final Logger logger;
        |  private final Handler handler;
        |  private final Level previousLevel;
        |  private final boolean previousUseParentHandlers;
        |  private final List<LogRecord> records = new ArrayList<>();
        |
        |  private JulRecorder(final Logger logger) {
        |    this.logger = logger;
        |    this.previousLevel = logger.getLevel();
        |    this.previousUseParentHandlers = logger.getUseParentHandlers();
        |    this.handler = new Handler() {
        |      @Override
        |      public void publish(final LogRecord record) {
        |        synchronized (records) {
        |          records.add(record);
        |        }
        |      }
        |
        |      @Override
        |      public void flush() {
        |      }
        |
        |      @Override
        |      public void close() {
        |      }
        |    };
        |    logger.setLevel(Level.ALL);
        |    logger.setUseParentHandlers(false);
        |    logger.addHandler(handler);
        |  }
        |
        |  public static JulRecorder attach(final String loggerName) {
        |    return new JulRecorder(Logger.getLogger(loggerName));
        |  }
        |
        |  public static JulRecorder attach(final Class<?> loggerClass) {
        |    return attach(loggerClass.getName());
        |  }
        |
        |  /// @return a snapshot of the records captured so far.
        |  public List<LogRecord> records() {
        |    synchronized (records) {
        |      return List.copyOf(records);
        |    }
        |  }
        |
        |  /// Each record as a handler would render it. Services commonly log '{0}' style
        |  /// patterns, so the values worth asserting on live in the record's parameters rather
        |  /// than in its raw message — asserting against {@link LogRecord#getMessage()} alone
        |  /// silently never matches them.
        |  public List<String> messages() {
        |    final var formatted = new ArrayList<String>();
        |    for (final var record : records()) {
        |      formatted.add(format(record));
        |    }
        |    return List.copyOf(formatted);
        |  }
        |
        |  /// @return whether any captured record contains {@code fragment} once formatted.
        |  public boolean logged(final String fragment) {
        |    for (final var message : messages()) {
        |      if (message != null && message.contains(fragment)) {
        |        return true;
        |      }
        |    }
        |    return false;
        |  }
        |
        |  private static String format(final LogRecord record) {
        |    final var message = record.getMessage();
        |    final var parameters = record.getParameters();
        |    if (message == null || parameters == null || parameters.length == 0) {
        |      return message;
        |    }
        |    try {
        |      return MessageFormat.format(message, parameters);
        |    } catch (final IllegalArgumentException e) {
        |      return message;
        |    }
        |  }
        |
        |  @Override
        |  public void close() {
        |    logger.removeHandler(handler);
        |    logger.setLevel(previousLevel);
        |    logger.setUseParentHandlers(previousUseParentHandlers);
        |  }
        |}
        |""".trimMargin() }
    generate("LoopbackHttpServer") { """
        |package $pkg;
        |
        |import java.io.ByteArrayOutputStream;
        |import java.io.IOException;
        |import java.io.InputStream;
        |import java.io.UncheckedIOException;
        |import java.net.InetAddress;
        |import java.net.ServerSocket;
        |import java.nio.charset.StandardCharsets;
        |import java.util.concurrent.BlockingQueue;
        |import java.util.concurrent.LinkedBlockingQueue;
        |import java.util.concurrent.TimeUnit;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// A scripted loopback HTTP server serving exactly the bytes you enqueue — including
        |/// what a well-behaved server library refuses to produce (a 199 status, a truncated
        |/// header block), which is the scaffolding for transport paths and status-boundary
        |/// guards otherwise accepted as unreachable in-harness. One enqueued response serves
        |/// one connection, then the connection closes; requests are recorded verbatim.
        |/// Binds the loopback address explicitly — connect to "127.0.0.1", never "localhost",
        |/// whose ::1 resolution can reach another JVM's wildcard bind on the same port.
        |public final class LoopbackHttpServer implements AutoCloseable {
        |
        |  private final ServerSocket serverSocket;
        |  private final Thread acceptor;
        |  private final BlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
        |  private final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        |
        |  private LoopbackHttpServer(final ServerSocket serverSocket) {
        |    this.serverSocket = serverSocket;
        |    this.acceptor = new Thread(this::serve, "loopback-http-" + serverSocket.getLocalPort());
        |    this.acceptor.setDaemon(true);
        |  }
        |
        |  public static LoopbackHttpServer start() {
        |    try {
        |      final var server = new LoopbackHttpServer(new ServerSocket(0, 16, InetAddress.getLoopbackAddress()));
        |      server.acceptor.start();
        |      return server;
        |    } catch (final IOException e) {
        |      throw new UncheckedIOException(e);
        |    }
        |  }
        |
        |  public int port() {
        |    return serverSocket.getLocalPort();
        |  }
        |
        |  public String baseUri() {
        |    return "http://127.0.0.1:" + port();
        |  }
        |
        |  /// Queues one raw response, served verbatim (ISO-8859-1 bytes) to the next connection.
        |  public LoopbackHttpServer enqueue(final String rawResponse) {
        |    responses.add(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
        |    return this;
        |  }
        |
        |  /// Convenience: a minimal well-formed 'Connection: close' response.
        |  public LoopbackHttpServer enqueue(final int status, final String body) {
        |    final var bytes = body.getBytes(StandardCharsets.UTF_8);
        |    return enqueue("HTTP/1.1 " + status + " Status\r\nContent-Length: " + bytes.length
        |        + "\r\nConnection: close\r\n\r\n" + new String(bytes, StandardCharsets.ISO_8859_1));
        |  }
        |
        |  /// The next recorded request — start line, headers, and any Content-Length body,
        |  /// verbatim — or null if none arrives within the timeout.
        |  public String takeRequest(final long timeout, final TimeUnit unit) throws InterruptedException {
        |    return requests.poll(timeout, unit);
        |  }
        |
        |  private void serve() {
        |    while (!serverSocket.isClosed()) {
        |      try (final var socket = serverSocket.accept()) {
        |        requests.add(readRequest(socket.getInputStream()));
        |        final var response = responses.poll(30, TimeUnit.SECONDS);
        |        if (response == null) {
        |          continue; // nothing scripted: the dropped connection is itself a test input
        |        }
        |        socket.getOutputStream().write(response);
        |        socket.getOutputStream().flush();
        |      } catch (final IOException | InterruptedException | RuntimeException e) {
        |        return; // closed, or the harness is broken enough that hanging would hide it
        |      }
        |    }
        |  }
        |
        |  private static String readRequest(final InputStream in) throws IOException {
        |    final var head = new ByteArrayOutputStream();
        |    int matched = 0;
        |    for (int b; matched < 4 && (b = in.read()) >= 0; ) {
        |      head.write(b);
        |      matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        |    }
        |    var request = head.toString(StandardCharsets.ISO_8859_1);
        |    final int contentLength = contentLength(request);
        |    if (contentLength > 0) {
        |      request += new String(in.readNBytes(contentLength), StandardCharsets.ISO_8859_1);
        |    }
        |    return request;
        |  }
        |
        |  private static int contentLength(final String head) {
        |    for (final var line : head.split("\r\n")) {
        |      final int colon = line.indexOf(':');
        |      if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
        |        return Integer.parseInt(line.substring(colon + 1).trim());
        |      }
        |    }
        |    return 0;
        |  }
        |
        |  @Override
        |  public void close() {
        |    try {
        |      serverSocket.close();
        |    } catch (final IOException e) {
        |      // closing anyway
        |    }
        |    acceptor.interrupt();
        |  }
        |}
        |""".trimMargin() }
    generate("ManualScheduledExecutor") { """
        |package $pkg;
        |
        |import java.time.Duration;
        |import java.util.ArrayList;
        |import java.util.Collection;
        |import java.util.List;
        |import java.util.PriorityQueue;
        |import java.util.concurrent.Callable;
        |import java.util.concurrent.Delayed;
        |import java.util.concurrent.Executors;
        |import java.util.concurrent.Future;
        |import java.util.concurrent.FutureTask;
        |import java.util.concurrent.RejectedExecutionException;
        |import java.util.concurrent.ScheduledExecutorService;
        |import java.util.concurrent.ScheduledFuture;
        |import java.util.concurrent.TimeUnit;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// A deterministic, single-threaded ScheduledExecutorService for clock-seam tests:
        |/// tasks run on the caller's thread only when the test advances the fake clock past
        |/// their trigger, so pacing, backoff and reconnect choreography become exact
        |/// functions of the delays requested — no real waits, nothing for PIT to multiply.
        |/// The clock starts at a non-zero origin so "timestamp mutated to 0" mutants stay
        |/// observable. Not thread-safe by design: determinism is the point.
        |public final class ManualScheduledExecutor implements ScheduledExecutorService {
        |
        |  private static final long ORIGIN_NANOS = 1_000_000_000L;
        |
        |  private final PriorityQueue<ManualTask<?>> tasks = new PriorityQueue<>();
        |  private long nowNanos = ORIGIN_NANOS;
        |  private long sequence;
        |  private boolean shutdown;
        |
        |  public long nowNanos() {
        |    return nowNanos;
        |  }
        |
        |  /// Runs every task due within the duration in trigger order, advancing the clock
        |  /// to each task's trigger as it runs (periodic tasks re-fire as many times as
        |  /// fit); ends with the clock at now + duration.
        |  ///
        |  /// @return the number of task executions
        |  public int advance(final Duration duration) {
        |    final long target = nowNanos + duration.toNanos();
        |    int executed = 0;
        |    for (var task = tasks.peek(); task != null && task.triggerNanos <= target; task = tasks.peek()) {
        |      tasks.poll();
        |      if (task.isCancelled()) {
        |        continue;
        |      }
        |      nowNanos = Math.max(nowNanos, task.triggerNanos);
        |      task.execute();
        |      ++executed;
        |    }
        |    nowNanos = target;
        |    return executed;
        |  }
        |
        |  /// Runs tasks due at the current instant without advancing the clock.
        |  public int runDue() {
        |    return advance(Duration.ZERO);
        |  }
        |
        |  /// @return scheduled tasks not yet run or cancelled.
        |  public int pending() {
        |    return (int) tasks.stream().filter(task -> !task.isCancelled()).count();
        |  }
        |
        |  private <V> ManualTask<V> enqueue(final Callable<V> callable, final long delayNanos, final long periodNanos) {
        |    if (shutdown) {
        |      throw new RejectedExecutionException("ManualScheduledExecutor is shut down");
        |    }
        |    final var task = new ManualTask<V>(callable, nowNanos + Math.max(0L, delayNanos), periodNanos);
        |    tasks.add(task);
        |    return task;
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        |    return enqueue(Executors.callable(command), unit.toNanos(delay), 0L);
        |  }
        |
        |  @Override
        |  public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
        |    return enqueue(callable, unit.toNanos(delay), 0L);
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        |    if (period <= 0) {
        |      throw new IllegalArgumentException("period must be positive");
        |    }
        |    return enqueue(Executors.callable(command), unit.toNanos(initialDelay), unit.toNanos(period));
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
        |    if (delay <= 0) {
        |      throw new IllegalArgumentException("delay must be positive");
        |    }
        |    return enqueue(Executors.callable(command), unit.toNanos(initialDelay), -unit.toNanos(delay));
        |  }
        |
        |  @Override
        |  public void execute(final Runnable command) {
        |    enqueue(Executors.callable(command), 0L, 0L);
        |  }
        |
        |  @Override
        |  public Future<?> submit(final Runnable task) {
        |    return enqueue(Executors.callable(task), 0L, 0L);
        |  }
        |
        |  @Override
        |  public <T> Future<T> submit(final Runnable task, final T result) {
        |    return enqueue(Executors.callable(task, result), 0L, 0L);
        |  }
        |
        |  @Override
        |  public <T> Future<T> submit(final Callable<T> task) {
        |    return enqueue(task, 0L, 0L);
        |  }
        |
        |  @Override
        |  public void shutdown() {
        |    shutdown = true;
        |  }
        |
        |  @Override
        |  public List<Runnable> shutdownNow() {
        |    shutdown = true;
        |    final var drained = new ArrayList<Runnable>(tasks);
        |    tasks.clear();
        |    return drained;
        |  }
        |
        |  @Override
        |  public boolean isShutdown() {
        |    return shutdown;
        |  }
        |
        |  @Override
        |  public boolean isTerminated() {
        |    return shutdown && tasks.isEmpty();
        |  }
        |
        |  @Override
        |  public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        |    return isTerminated();
        |  }
        |
        |  // Blocking multi-submit has no deterministic single-threaded semantics: the tasks
        |  // could only run when the clock advances, which the blocked caller cannot do.
        |
        |  @Override
        |  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  private final class ManualTask<V> extends FutureTask<V> implements ScheduledFuture<V> {
        |
        |    private final long periodNanos; // 0 one-shot, >0 fixed-rate, <0 fixed-delay
        |    private final long seq;
        |    private long triggerNanos;
        |
        |    private ManualTask(final Callable<V> callable, final long triggerNanos, final long periodNanos) {
        |      super(callable);
        |      this.triggerNanos = triggerNanos;
        |      this.periodNanos = periodNanos;
        |      this.seq = sequence++;
        |    }
        |
        |    private void execute() {
        |      if (periodNanos == 0L) {
        |        run();
        |      } else if (runAndReset()) {
        |        triggerNanos = periodNanos > 0L ? triggerNanos + periodNanos : nowNanos - periodNanos;
        |        tasks.add(this);
        |      }
        |    }
        |
        |    @Override
        |    public long getDelay(final TimeUnit unit) {
        |      return unit.convert(triggerNanos - nowNanos, TimeUnit.NANOSECONDS);
        |    }
        |
        |    @Override
        |    public int compareTo(final Delayed other) {
        |      if (other instanceof ManualScheduledExecutor.ManualTask<?> task) {
        |        final int byTrigger = Long.compare(triggerNanos, task.triggerNanos);
        |        return byTrigger != 0 ? byTrigger : Long.compare(seq, task.seq);
        |      }
        |      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        |    }
        |  }
        |}
        |""".trimMargin() }
  }
}
sourceSets.test {
  java.srcDir(generateHardeningTestSupport)
}
