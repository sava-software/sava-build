import software.sava.build.hardening.BaselineEngine
import software.sava.build.hardening.BaselineDocument
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.BaselineNotes
import software.sava.build.hardening.BaselineWriteOperation
import software.sava.build.hardening.CommittedMutationProvenance
import software.sava.build.hardening.ExclusionAudit
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningAgentTemplateBlock
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningFuzzSession
import software.sava.build.hardening.HardeningNames
import software.sava.build.hardening.HardeningHelpTask
import software.sava.build.hardening.HardeningOperationCompletionTask
import software.sava.build.hardening.HardeningOperationRequestTask
import software.sava.build.hardening.HardeningOperationSession
import software.sava.build.hardening.HardeningRepositoryCheckCoordinator
import software.sava.build.hardening.HardeningOptionNames
import software.sava.build.hardening.HardeningPluginIdentityGuard
import software.sava.build.hardening.HardeningPluginIdentityService
import software.sava.build.hardening.HardeningTemplateDigest
import software.sava.build.hardening.HardeningToolDefaults
import software.sava.build.hardening.HardeningWriteRequest
import software.sava.build.hardening.Mutant
import software.sava.build.hardening.MutantStatus
import software.sava.build.hardening.knownInvalidExecutionClosure
import software.sava.build.hardening.MutationToolchainRecord
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.HardeningExecutionLock
import software.sava.build.hardening.PreparedMutationWrite
import software.sava.build.hardening.PrunePreviewHistory
import software.sava.build.hardening.PrunePreviewObservation
import software.sava.build.hardening.PrunePreviewTransition
import software.sava.build.hardening.PrunePreviewTransitionKind
import software.sava.build.hardening.ProjectWriteOperation
import software.sava.build.hardening.RecordedLineMetadata
import software.sava.build.hardening.TimeoutAudit
import software.sava.build.hardening.qualifiedHardeningTaskPath
import software.sava.build.hardening.task.FuzzMinimizeTask
import software.sava.build.hardening.task.FuzzRunTask
import software.sava.build.hardening.task.HardeningAgentTemplateDiffTask
import software.sava.build.hardening.task.HardeningCertifyAllTask
import software.sava.build.hardening.task.HardeningCertificationPreflightTask
import software.sava.build.hardening.task.HardeningCertificationTask
import software.sava.build.hardening.task.PitestConvergeTask
import software.sava.build.hardening.task.PitestDebtTask
import software.sava.build.hardening.task.PitestDiagnosticTask
import software.sava.build.hardening.task.PitestEvidenceSpec
import software.sava.build.hardening.task.PitestEvidenceValidationTask
import software.sava.build.hardening.task.PitestModeCommitTask
import software.sava.build.hardening.task.PitestExecTask
import software.sava.build.hardening.task.PitestMutatorTrialTask
import software.sava.build.hardening.task.PitestRunTask
import software.sava.build.hardening.task.PitestVerifyTask
import software.sava.build.hardening.task.TimeoutAuditPreflightTask
import software.sava.build.hardening.task.certificationRetryGuidance
import software.sava.build.hardening.task.generatedReportRetryGuidance
import software.sava.build.hardening.task.workflowRetryGuidance

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

// Writer properties were presence-based and therefore too easy to trigger
// accidentally. Refuse every old spelling during configuration, before PIT or any
// task graph can touch a committed record; named tasks are the sole write surface.
val removedWriterProperties = HardeningOptionNames.removedWriterProperties.filter {
  providers.gradleProperty(it).isPresent
}
if (removedWriterProperties.isNotEmpty()) {
  throw GradleException(HardeningOptionNames.removedWriterMessage(removedWriterProperties))
}

// Arcmutate incremental analysis ("history"): the licensed engine decides which
// per-mutant results can be reused across runs. Treat that decision as an optimisation,
// never as a fresh-observation guarantee — in particular, changed consumer tests have
// been observed alongside a reused status. Open-source PIT accepts the history flags
// but cannot honour them — its only registered history factory throws — so the licence
// certificate controls whether com.arcmutate:base is part of the PIT toolchain. Keep
// that classpath decision independent from reuse:
// ArcMutate's base plugin can change the effective mutant population even with history
// disabled. '-PnoMutationHistory' therefore suppresses only the history feature and
// its input/output files; it must not silently switch a licensed run back to a different
// open-source-PIT population.
// Certification is a fresh observation by definition. Whether this invocation is a
// certification is decided at execution time by hardeningCertifyPreflight's build
// service, so aliases, abbreviations and aggregate tasks cannot accidentally retain
// history merely because their command-line spelling differs.
val arcMutateProjectLicence = layout.projectDirectory.file("arcmutate-licence.txt")
val arcMutateRootLicence = rootProject.layout.projectDirectory.file("arcmutate-licence.txt")
val arcMutateLicencePresent = arcMutateProjectLicence.asFile.isFile ||
    arcMutateRootLicence.asFile.isFile
val isolateMutantsRequested = providers
    .gradleProperty(HardeningOptionNames.ISOLATE_MUTANTS)
    .map { true }
    .orElse(false)
val mutationHistoryDisabledForExecution = providers
    .gradleProperty(HardeningOptionNames.NO_MUTATION_HISTORY)
    .orElse(providers.gradleProperty(HardeningOptionNames.STRICT_TIMEOUT_AUDIT))
    .map { true }
    .orElse(false)
val mutationHistoryDisabledForNormalPitest = mutationHistoryDisabledForExecution
    .zip(isolateMutantsRequested) { explicitlyDisabled, isolated ->
      explicitlyDisabled || isolated
    }

val pitest = configurations.create("pitest") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("org.pitest:pitest-command-line:${hardening.pitestVersion.get()}"))
    add(project.dependencies.create("org.pitest:pitest-junit5-plugin:${hardening.pitestJunit5PluginVersion.get()}"))
    if (arcMutateLicencePresent) {
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
  val mutateOnly = providers.gradleProperty(HardeningOptionNames.MUTATE_ONLY)
  val isolateMutants = isolateMutantsRequested
  doFirst {
    if (mutateOnly.isPresent) {
      throw GradleException(
          "qualityGate cannot certify a scoped mutation population " +
              "(-PmutateOnly=${mutateOnly.get()}" +
              (if (isolateMutants.get()) ", -P${HardeningOptionNames.ISOLATE_MUTANTS}" else "") +
              "). Run the gate without scoped flags so every registered suite measures its full population."
      )
    }
    if (isolateMutants.get()) {
      throw GradleException(
          "qualityGate cannot certify -P${HardeningOptionNames.ISOLATE_MUTANTS}; " +
              "isolated execution is diagnostic, not full-population evidence."
      )
    }
  }
}

// A release-grade gate is intentionally separate from the permissive developer gate.
// Its preflight is a dependency of every PIT execution selected for certification, so
// a record-writing task or scoped property is refused before it can run or change evidence.
val hardeningCertificationSession = gradle.sharedServices.registerIfAbsent(
    "hardeningCertificationSession", HardeningCertificationSession::class
) {}
val hardeningOperationSession = gradle.sharedServices.registerIfAbsent(
    "hardeningOperationSession", HardeningOperationSession::class
) {}
val hardeningFuzzSession = gradle.sharedServices.registerIfAbsent(
    "hardeningFuzzSession", HardeningFuzzSession::class
) {}
val hardeningRepositoryCheckCoordinator = gradle.sharedServices.registerIfAbsent(
    "hardeningRepositoryCheckCoordinator", HardeningRepositoryCheckCoordinator::class
) {}
// One end-of-build service shared across every project: it keeps non-failing reviewer
// stops visible and rolls up only the certification receipts this invocation published.
val hardeningAdvisoryLog = gradle.sharedServices.registerIfAbsent(
    "hardeningAdvisoryLog", HardeningAdvisoryLog::class
) {}
val hardeningHelpSuiteNames = objects.listProperty<String>()
val hardeningHelpFuzzTargetNames = objects.listProperty<String>()
tasks.register<HardeningHelpTask>("hardeningHelp") {
  group = "help"
  description = "Prints the installed hardening task and Gradle-property surface."
  suiteNames.set(hardeningHelpSuiteNames)
  fuzzTargetNames.set(hardeningHelpFuzzTargetNames)
}
val certificationReceiptFile =
    layout.projectDirectory.file(".pitest-history/pitest-certification.tsv")
val certificationReceiptRunning =
    layout.projectDirectory.file(".pitest-history/pitest-certification.running")
val certificationReceiptLock =
    layout.projectDirectory.file(".pitest-history/pitest-certification.lock")
// One-release transition cleanup. A legacy build-output receipt must not remain
// plausible beside the durable machine-local evidence, including when buildDirectory
// is configured outside the checkout.
val legacyCertificationReceiptFile =
    layout.buildDirectory.file("hardening/pitest-certification.tsv")
val legacyCertificationReceiptRunning =
    layout.buildDirectory.file("hardening/pitest-certification.running")
// Freeze the bytes which applied this convention now. The settings plugin normally
// registered the shared identity first; this registration is the direct-feature-plugin
// fallback. The path remains only so execution boundaries can detect replacement.
val hardeningImplementationCode =
    File(PitestEvidence::class.java.protectionDomain.codeSource.location.toURI())
val hardeningImplementationSha256AtProjectApplication =
    PitestEvidence.fingerprintTree(hardeningImplementationCode)
private val hardeningPluginIdentityService = gradle.sharedServices.registerIfAbsent(
    HardeningPluginIdentityService.SERVICE_NAME, HardeningPluginIdentityService::class
) {
  parameters.applicationPluginSha256.set(hardeningImplementationSha256AtProjectApplication)
  parameters.localRepoArtifactPath.set(HardeningPluginIdentityService.NO_LOCAL_ARTIFACT)
  parameters.applicationLocalRepoArtifactSha256.set(HardeningPluginIdentityService.NO_LOCAL_ARTIFACT)
}
val hardeningExpectedPluginSha256: Provider<String> =
    hardeningPluginIdentityService.map { it.parameters.applicationPluginSha256.get() }
val hardeningLocalRepoArtifactPath: Provider<String> =
    hardeningPluginIdentityService.map { it.parameters.localRepoArtifactPath.get() }
val hardeningExpectedLocalRepoArtifactSha256: Provider<String> =
    hardeningPluginIdentityService.map { it.parameters.applicationLocalRepoArtifactSha256.get() }
val hardeningCertifyPreflight =
    tasks.register<HardeningCertificationPreflightTask>("hardeningCertifyPreflight") {
  description = "Internal to hardeningCertify: refuses flags that make the run partial or state-changing."
  // `clean hardeningCertify` is the most useful cold certification. Without an
  // explicit edge Gradle may run this otherwise-independent preflight first and
  // then let clean erase its execution sentinel.
  mustRunAfter("clean")
  val certificationSession = hardeningCertificationSession
  usesService(certificationSession)
  this.certificationSession.set(certificationSession)
  certificationProjectDirectory.set(layout.projectDirectory)
  receiptFile.set(certificationReceiptFile)
  runningFile.set(certificationReceiptRunning)
  lockFile.set(certificationReceiptLock)
  legacyBuildDirectory.set(layout.buildDirectory)
  legacyReceiptFile.set(legacyCertificationReceiptFile)
  legacyRunningFile.set(legacyCertificationReceiptRunning)
  certificationPluginCode.from(hardeningImplementationCode)
  expectedPluginSha256.set(hardeningExpectedPluginSha256)
  localRepoArtifactPath.set(hardeningLocalRepoArtifactPath)
  expectedLocalRepoArtifactSha256.set(hardeningExpectedLocalRepoArtifactSha256)
  hardeningProjectPath.set(project.path)
  presentForbiddenProperties.set(HardeningOptionNames.certificationForbiddenProperties.filter {
    providers.gradleProperty(it).isPresent
  })
  excludedTaskNames.set(gradle.startParameter.excludedTaskNames.sorted())
}

// `hardeningCertify` depends on both this preflight and `qualityGate`; dependency
// siblings are otherwise unordered, and even `test`'s compile/resource prerequisites
// could fail before the new attempt's durable sentinel was published. An ordering rule adds no
// preflight to ordinary task graphs, but when certification selected it, every task in
// this project (except the intentionally earlier clean) starts only after ownership and
// incomplete-attempt state are established.
tasks.configureEach {
  if (name != "clean" && name != "hardeningCertifyPreflight" &&
      name != "hardeningCertifyAll") {
    mustRunAfter(hardeningCertifyPreflight)
  }
}

// One cheap, committed-file check per suite feeds this certification barrier. Every
// certifying PIT task is ordered after the barrier, so a fleet-wide cause-category
// migration fails before the first expensive mutation run rather than one suite at a
// time after its report has already been produced.
val hardeningCertifyTimeoutAuditPreflight =
    tasks.register("hardeningCertifyTimeoutAuditPreflight") {
  description = "Internal to hardeningCertify: validates every committed timeout audit before PIT."
  dependsOn(hardeningCertifyPreflight)
  mustRunAfter(hardeningCertifyPreflight)
}

val certificationSuiteNames = mutableListOf<String>()
val hardeningCertify = tasks.register<HardeningCertificationTask>("hardeningCertify") {
  group = "verification"
  description = "Fresh, full, strict mutation certification; writes .pitest-history/pitest-certification.tsv."
  dependsOn(qualityGate)
  dependsOn(hardeningCertifyPreflight)
  dependsOn(hardeningCertifyTimeoutAuditPreflight)
  val certificationSession = hardeningCertificationSession
  val advisoryLog = hardeningAdvisoryLog
  usesService(certificationSession)
  usesService(advisoryLog)
  this.certificationSession.set(certificationSession)
  hardeningProjectPath.set(project.path)
  certificationProjectDirectory.set(layout.projectDirectory)
  certificationPluginCode.from(hardeningImplementationCode)
  expectedPluginSha256.set(hardeningExpectedPluginSha256)
  localRepoArtifactPath.set(hardeningLocalRepoArtifactPath)
  expectedLocalRepoArtifactSha256.set(hardeningExpectedLocalRepoArtifactSha256)
  val reportRoot = layout.buildDirectory.dir("reports/pitest")
  val receiptFile = certificationReceiptFile
  val receiptRunning = certificationReceiptRunning
  val configDir = layout.projectDirectory.dir("config/pitest")
  val certifiedProjectDirectory = layout.projectDirectory.asFile
  val certifiedProjectPath = project.path
  val certificationTaskPath = qualifiedHardeningTaskPath(certifiedProjectPath, "hardeningCertify")
  val receiptRetry = certificationRetryGuidance(certifiedProjectPath)
  val pitVersion = hardening.pitestVersion
  val certifiedSuiteNames = certificationSuiteNames
  fun markCertificationIncomplete(
    failure: Throwable,
    receiptReplacementAttempted: Boolean = false,
    previousReceipt: ByteArray? = null,
  ) {
    try {
      if (!certificationSession.get().ownsCertification(certifiedProjectPath)) return
      val reason = (failure.message ?: failure::class.java.simpleName)
          .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
      if (receiptReplacementAttempted) {
        BaselineFiles.restoreReceiptSnapshotUnderIncompleteMarker(
            certifiedProjectDirectory,
            receiptFile.asFile,
            receiptRunning.asFile,
            "refused\t$reason\n",
            previousReceipt,
        )
      } else {
        BaselineFiles.preserveReceiptUnderIncompleteMarker(
            certifiedProjectDirectory,
            receiptFile.asFile,
            receiptRunning.asFile,
            "refused\t$reason\n",
        )
      }
    } catch (stateFailure: Exception) {
      failure.addSuppressed(stateFailure)
    }
  }
  doFirst {
    val session = certificationSession.get()
    val sessionId = session.sessionId(certifiedProjectPath)
    val runningFile = receiptRunning.asFile
    val expectedSentinel = sessionId?.let { "session\t$it\n" }
    if (!session.isActive(certifiedProjectPath) ||
        !session.ownsCertification(certifiedProjectPath) ||
        expectedSentinel == null || !runningFile.isFile ||
        runningFile.readText() != expectedSentinel) {
      val failure = GradleException(
          "hardeningCertify: certification preflight does not own the exact durable " +
              "session sentinel; refusing to reuse or replace certification evidence" +
              receiptRetry)
      markCertificationIncomplete(failure)
      throw failure
    }
  }
  doLast {
    var previousReceipt: ByteArray? = null
    var receiptReplacementAttempted = false
    try {
    val sessionId = certificationSession.get().sessionId(certifiedProjectPath)
        ?: throw GradleException(
            "hardeningCertify: certification session is not active" + receiptRetry)
    val finalProjectIdentity = try {
      certificationSession.get().requireFinalProjectIdentity(certifiedProjectPath)
    } catch (e: IllegalStateException) {
      throw GradleException("hardeningCertify: ${e.message}" + receiptRetry, e)
    }
    val receiptRows = mutableListOf<String>()
    BaselineFiles.requireDirectoryOrMissing(certifiedProjectDirectory, configDir.asFile)
    certifiedSuiteNames.sorted().forEach { suiteName ->
      val certificationBaselineRebaseTaskPath = qualifiedHardeningTaskPath(
          certifiedProjectPath,
          "pitest${suiteName.replaceFirstChar(Char::uppercase)}BaselineRebase",
      )
      val suiteDir = reportRoot.get().asFile.resolve(suiteName)
      val report = suiteDir.resolve("mutations.csv")
      val manifest = suiteDir.resolve(".evidence.tsv")
      if (!report.isFile || !manifest.isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' has no completed report/evidence pair — " +
                "the receipt cannot reuse a partial or prior suite observation" + receiptRetry)
      }
      if (suiteDir.resolve(".running").isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report was left by an interrupted or failed PIT run" +
                receiptRetry)
      }
      if (suiteDir.resolve(".scoped").isFile || suiteDir.resolve(".history-assisted").isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report markers say it is scoped or history-assisted, " +
                "not a fresh full observation" + receiptRetry)
      }
      val evidence = try {
        PitestEvidence.parse(manifest.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "hardeningCertify: invalid evidence for '$suiteName': ${e.message}" + receiptRetry, e)
      }
      if (evidence.scope != PitestEvidence.FULL_SCOPE || evidence.historyAssisted) {
        throw GradleException(
            "hardeningCertify: '$suiteName' is not a fresh full observation " +
                "(scope=${evidence.scope}, historyAssisted=${evidence.historyAssisted})" +
                receiptRetry)
      }
      if (evidence.reportSha256 != PitestEvidence.sha256(report)) {
        throw GradleException(
            "hardeningCertify: '$suiteName' report changed after its evidence manifest was written" +
                receiptRetry)
      }
      val completedToolchainFile = suiteDir.resolve(".toolchain.tsv")
      val completedToolchain = if (!completedToolchainFile.isFile) {
        throw GradleException(
            "hardeningCertify: '$suiteName' has no completed mutation-toolchain record" +
                receiptRetry)
      } else try {
        MutationToolchainRecord.parse(completedToolchainFile.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "hardeningCertify: invalid completed mutation-toolchain record for '$suiteName': " +
                "${e.message}" + receiptRetry, e)
      }
      if (completedToolchain.identitySha256 != evidence.mutationToolchainSha256) {
        throw GradleException(
            "hardeningCertify: '$suiteName' mutation-toolchain record does not match completed " +
                "evidence" + receiptRetry)
      }
      val baseline = configDir.file("$suiteName-accepted.csv").asFile
      val timeouts = configDir.file("$suiteName-timeouts.csv").asFile
      val stamp = configDir.file("$suiteName-pitest-version").asFile
      val toolchainStamp = configDir.file("$suiteName-pitest-toolchain.tsv").asFile
      listOf(baseline, timeouts, stamp, toolchainStamp, configDir.file("README.md").asFile)
          .forEach { BaselineFiles.requireRegularFileOrMissing(certifiedProjectDirectory, it) }
      val hasCommittedRecord = baseline.isFile || timeouts.isFile
      val committedProvenance = CommittedMutationProvenance.classify(
          hasCommittedRecord,
          stamp.takeIf { it.isFile }?.readText(),
          toolchainStamp.takeIf { it.isFile }?.readText(),
      )
      committedProvenance.malformedPitVersion?.let { detail ->
        throw GradleException(
            "hardeningCertify: malformed committed PIT-version stamp for '$suiteName': $detail" +
                receiptRetry)
      }
      committedProvenance.malformedToolchain?.let { detail ->
        throw GradleException(
            "hardeningCertify: invalid committed mutation-toolchain record for '$suiteName': " +
                "$detail" + receiptRetry)
      }
      val recordedPitVersion = committedProvenance.pitVersion
      val recordedToolchain = committedProvenance.toolchain
      if (hasCommittedRecord &&
          recordedPitVersion != null && recordedPitVersion != pitVersion.get()) {
        throw GradleException(
            "hardeningCertify: '$suiteName' committed records are not stamped for PIT " +
                "${pitVersion.get()}" + receiptRetry)
      }
      if (committedProvenance.orphan) {
        throw GradleException(
            "hardeningCertify: '$suiteName' has mutation-provenance sidecar(s) but no accepted " +
                "or timeout record; reconcile the orphan state with " +
                certificationBaselineRebaseTaskPath +
                receiptRetry)
      }
      if (committedProvenance.torn) {
        throw GradleException(
            "hardeningCertify: '$suiteName' committed mutation provenance is torn — exactly one " +
                "of ${stamp.name} and ${toolchainStamp.name} exists; repair it with " +
                certificationBaselineRebaseTaskPath +
                receiptRetry)
      }
      if (committedProvenance.disagreement) {
        throw GradleException(
            "hardeningCertify: '$suiteName' committed mutation provenance disagrees between " +
                "${stamp.name} and ${toolchainStamp.name}; repair it with " +
                certificationBaselineRebaseTaskPath +
                receiptRetry)
      }
      if (recordedToolchain != null &&
          recordedToolchain.identitySha256 != evidence.mutationToolchainSha256) {
        throw GradleException(
            "hardeningCertify: '$suiteName' committed mutation toolchain differs from the fresh run; " +
                "review it with $certificationBaselineRebaseTaskPath" +
                receiptRetry)
      }
      val recordProvenance = when {
        recordedPitVersion != null -> recordedPitVersion
        hasCommittedRecord -> "legacy-unversioned"
        else -> "no-record"
      }
      val recordToolchainProvenance = when {
        recordedToolchain != null -> recordedToolchain.identitySha256
        hasCommittedRecord -> "legacy-toolchain-unbound"
        else -> "no-record"
      }
      // The report and compiled/configured inputs prove what PIT observed; these
      // committed files prove what made that observation acceptable. Keep their
      // digest in every suite row too: the schema-7 receipt's project-level Git
      // identity binds the checkout, while this field names the exact baseline,
      // timeout membership, causes, and PIT-version provenance used by the suite.
      val recordInputsSha256 = PitestEvidence.mutationRecordFingerprint(
          certifiedProjectDirectory, configDir.asFile, suiteName)
      try {
        certificationSession.get().requireVerified(
            certifiedProjectPath, suiteName, evidence, recordInputsSha256)
      } catch (e: IllegalStateException) {
        throw GradleException("hardeningCertify: ${e.message}" + receiptRetry, e)
      }
      receiptRows.add(
          listOf(
              "suite", suiteName, evidence.invocationId, evidence.reportSha256,
              evidence.sourceSha256, evidence.classesSha256, evidence.configurationSha256,
              evidence.pitestVersion, evidence.pluginSha256, evidence.toolClasspathSha256,
              evidence.mutationToolchainSha256, recordInputsSha256, recordProvenance,
              recordToolchainProvenance,
          ).joinToString("\t"))
    }
    val receipt = buildString {
      appendLine("schema\t7")
      appendLine("project\t$certifiedProjectPath")
      appendLine("session\t$sessionId")
      appendLine("mode\tfresh-full-strict")
      appendLine("gitState\t${finalProjectIdentity.git.state.receiptValue}")
      appendLine("gitCommit\t${finalProjectIdentity.git.commit}")
      appendLine("gitTree\t${finalProjectIdentity.git.tree}")
      appendLine("gitStatusSha256\t${finalProjectIdentity.git.statusSha256}")
      appendLine("gitProjectDirectory\t${finalProjectIdentity.git.projectDirectory}")
      appendLine("pluginSha256\t${finalProjectIdentity.pluginSha256}")
      appendLine(
          "suiteColumns\tname\tinvocation\treportSha256\tsourceSha256\tclassesSha256\t" +
              "configurationSha256\tpitestVersion\tpluginSha256\ttoolClasspathSha256\t" +
              "mutationToolchainSha256\trecordInputsSha256\trecordPitestVersion\t" +
              "recordMutationToolchainSha256")
      receiptRows.forEach(::appendLine)
    }
    val runningFile = receiptRunning.asFile
    val expectedSentinel = "session\t$sessionId\n"
    if (!certificationSession.get().ownsCertification(certifiedProjectPath) ||
        !runningFile.isFile || runningFile.readText() != expectedSentinel) {
      BaselineFiles.requireRegularFileOrMissing(
          certifiedProjectDirectory, receiptFile.asFile)
      throw GradleException(
          "hardeningCertify: certification ownership sentinel changed before receipt publication" +
              receiptRetry)
    }
    previousReceipt = BaselineFiles.readRegularFileSnapshot(
        certifiedProjectDirectory, receiptFile.asFile)
    receiptReplacementAttempted = true
    BaselineFiles.writeAtomically(
        certifiedProjectDirectory, receiptFile.asFile, receipt)
    BaselineFiles.requireRegularFileOrMissing(certifiedProjectDirectory, runningFile)
    if (!runningFile.isFile || runningFile.readText() != expectedSentinel) {
      throw GradleException(
          "hardeningCertify: certification ownership sentinel changed during receipt publication" +
              receiptRetry)
    }
    BaselineFiles.deleteIfExists(runningFile)
    val publishedReceipt = receiptFile.asFile.absoluteFile.normalize().path
    advisoryLog.get().recordCertification(
        certifiedProjectPath, certifiedSuiteNames.size, publishedReceipt)
    val suiteNoun = if (certifiedSuiteNames.size == 1) "suite" else "suites"
    logger.lifecycle(
        "$certificationTaskPath: ${certifiedSuiteNames.size} $suiteNoun certified; " +
            "receipt: $publishedReceipt")
    } catch (failure: Exception) {
      markCertificationIncomplete(failure, receiptReplacementAttempted, previousReceipt)
      throw failure
    }
  }
}

// A plain `:a:hardeningCertify :b:hardeningCertify` invocation retains Gradle's
// fail-fast semantics unless the caller supplies --continue. This root anchor gives
// consumers a configuration-cache-safe installed alternative: project certifications
// are sibling finalizers, so one project's failure does not stop the remaining
// projects, while the original failure still makes the aggregate build fail.
val hardeningCertifyAll = rootProject.tasks.maybeCreate(
    "hardeningCertifyAll", HardeningCertifyAllTask::class.java)
hardeningCertifyAll.group = "verification"
hardeningCertifyAll.description =
    "Certifies every project using sava hardening; sibling projects continue after a failure."
hardeningCertifyAll.excludedTaskNames.set(gradle.startParameter.excludedTaskNames.sorted())
hardeningCertifyAll.finalizedBy(hardeningCertify)

// `mustRunAfter` only orders tasks within one project. PIT and corpus-rewrite tasks
// retain an exclusive build-wide slot so mutation timeout evidence and source writes
// are not load-dependent. Fuzz exploration has a separate, explicitly bounded pool:
// release owners can trade wall time for throughput, while the configured width and
// every achieved execution count are bound into the campaign receipt.
val hardeningExecutionLock = gradle.sharedServices.registerIfAbsent(
    "hardeningExecutionLock", HardeningExecutionLock::class
) {
  maxParallelUsages.set(1)
}
val maxParallelFuzzTargetsRaw =
    providers.gradleProperty(HardeningOptionNames.MAX_PARALLEL_FUZZ_TARGETS).orElse("1")
val maxParallelFuzzTargets = maxParallelFuzzTargetsRaw.map { raw ->
  raw.toIntOrNull()?.takeIf { value ->
    Regex("[1-9][0-9]*").matches(raw) && value > 0
  } ?: 1
}
val hardeningFuzzExecutionSlots = gradle.sharedServices.registerIfAbsent(
    "hardeningFuzzExecutionSlots", HardeningExecutionLock::class
) {
  maxParallelUsages.set(maxParallelFuzzTargets)
}

// The agent-instructions template in HARDENING.md is copied exactly into a bounded
// block in each consuming repo's AGENTS.md; repository-specific facts stay outside.
// Legacy adapted copies drift silently until their next normal sync. A template change
// is invisible from inside the repos it obligates. The plugin carries a digest of the
// current template block; this check fails until the repo's AGENTS.md contains one
// valid bounded block and a marker acknowledging that digest. The marker is an
// acknowledgment, not a checksum of the local block: update it only after re-diffing
// the block against the template
// — a changed bullet may mean new code, not just new prose. A repo without an
// AGENTS.md is warned, not failed: the adoption checklist owns creating the file;
// this task owns keeping it current.
val renderedHardeningAgentTemplate = HardeningTemplateDigest.TEMPLATE.lineSequence()
    .joinToString("\n") { it.removePrefix("> ") }
HardeningAgentTemplateBlock.requireCanonicalHeadingFree(
    renderedHardeningAgentTemplate.lineSequence().toList())
val hardeningAgentTemplateDiff = tasks.register<HardeningAgentTemplateDiffTask>(
    "hardeningAgentTemplateDiff"
) {
  group = "help"
  description = "Diffs the bounded AGENTS.md hardening block against the installed template; normalizes one uniform Markdown '> ' quote layer."
  agentsFile.set(rootProject.layout.projectDirectory.file("AGENTS.md"))
  installedTemplate.set(renderedHardeningAgentTemplate)
  repositoryCheckKey.set(HardeningTemplateDigest.SHA256_12)
  repositoryCheckCoordinator.set(hardeningRepositoryCheckCoordinator)
  usesService(hardeningRepositoryCheckCoordinator)
}
val agentsTemplateInSync = tasks.register("agentsTemplateInSync") {
  group = "verification"
  description = "Checks the root AGENTS.md bounded acknowledgment of the installed agent-instructions template."
  val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
  val expected = HardeningTemplateDigest.SHA256_12
  val templateTask = if (project.path == ":") ":hardeningAgentTemplate" else "${project.path}:hardeningAgentTemplate"
  val templateDiffTask = if (project.path == ":") {
    ":hardeningAgentTemplateDiff"
  } else {
    "${project.path}:hardeningAgentTemplateDiff"
  }
  // Set when a build resolves an unreleased sava-build checkout through
  // '-PsavaBuildLocalRepo'. A stale marker under that flag is the expected state,
  // not a defect: the repo acknowledges the digest of a RELEASED plugin, and this
  // checkout's digest has not shipped yet. Failing here forced repos to acknowledge
  // unreleased digests ahead of the release — which then wedged their 'check'
  // against every published plugin until the release landed and the pin was bumped.
  // A deliberate RC-adoption change may prepare the new block and marker now, but
  // that consumer commit must land only with or after the release pin it acknowledges.
  val validatingUnreleased =
      providers.gradleProperty(HardeningOptionNames.SAVA_BUILD_LOCAL_REPO).isPresent
  val advisoryLog = hardeningAdvisoryLog
  val advisoryScope = "repository AGENTS.md"
  val repositoryCoordinator = hardeningRepositoryCheckCoordinator
  usesService(advisoryLog)
  usesService(repositoryCoordinator)
  inputs.files(agentsDoc)
  inputs.property("templateDigest", expected)
  inputs.property("validatingUnreleased", validatingUnreleased)
  doLast {
    if (!repositoryCoordinator.get().claim(
        "agentsTemplateInSync",
        agentsDoc.absoluteFile.normalize().path,
        expected,
        if (validatingUnreleased) "unreleased" else "published",
      )) {
      logger.info("agentsTemplateInSync: repository-scoped check already ran in this build")
      return@doLast
    }
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
    val docLines = doc.lines()
    val inspection = try {
      HardeningAgentTemplateBlock.inspect(docLines)
    } catch (invalid: HardeningAgentTemplateBlock.Invalid) {
      throw GradleException("agentsTemplateInSync: ${invalid.message}", invalid)
    }
    val currentMarker = inspection.marker?.digest == expected
    val hasAnyBoundary = inspection.hasAnyBoundary
    if (currentMarker || hasAnyBoundary) {
      try {
        HardeningAgentTemplateBlock.parse(docLines)
      } catch (invalid: HardeningAgentTemplateBlock.Invalid) {
        throw GradleException("agentsTemplateInSync: ${invalid.message}", invalid)
      }
    }
    if (currentMarker) return@doLast
    val boundaryStart = HardeningAgentTemplateDiffTask.BLOCK_START
    val boundaryEnd = HardeningAgentTemplateDiffTask.BLOCK_END
    val boundaryMigration = if (hasAnyBoundary) {
      ""
    } else {
      HardeningAgentTemplateBlock.boundaryMigrationGuidance()
    }
    val stale = inspection.marker
    if (stale != null && validatingUnreleased) {
      val boundaryMigrationNotice = if (boundaryMigration.isEmpty()) {
        ""
      } else {
        "\n  Remedy prerequisite:\n" + boundaryMigration.trimEnd().prependIndent("    ")
      }
      logger.warn(
          "agentsTemplateInSync: unreleased template digest differs from AGENTS.md.\n" +
              "  AGENTS.md marker: ${stale.digest}\n" +
              "  Unreleased checkout: $expected\n" +
              "  Review: The marker normally lands with the release that ships this digest, not " +
              "before it; local candidate validation alone does not require a consumer change." +
              boundaryMigrationNotice +
              "\n  Remedy for deliberate RC adoption: Run './gradlew $templateDiffTask', review " +
              "or act on the bounded AGENTS.md hardening block, and stage the marker with the new " +
              "plugin pin.\n" +
              "  Landing condition: Do not land that consumer commit while it still resolves " +
              "the older published plugin.\n" +
              "  Remedy otherwise: When bumping past the release, run './gradlew $templateDiffTask', " +
              "review or act on the diff, and update the marker to:\n" +
              "    <!-- hardening-template sha256:$expected -->"
      )
      advisoryLog.get().record(
          advisoryScope, "AGENTS.md acknowledges an older hardening template during unreleased validation")
      return@doLast
    }
    throw GradleException(
        if (stale == null) {
          "AGENTS.md has no 'hardening-template' marker. $boundaryMigration" +
              "Run './gradlew $templateDiffTask', " +
              "sync or act on what " +
              "differs, then add:\n  <!-- hardening-template sha256:$expected -->"
        } else {
          "The shared agent-instructions template changed since this repo's AGENTS.md last " +
          "acknowledged it (marker ${stale.digest}, current $expected). $boundaryMigration" +
              "Run './gradlew $templateDiffTask' to compare its explicitly bounded hardening block " +
              "with the installed template. A " +
              "changed bullet may need code, not just prose — then update the marker to:\n" +
              "  <!-- hardening-template sha256:$expected -->"
        }
    )
  }
}
tasks.register("hardeningAgentTemplate") {
  group = "help"
  description = "Prints the bounded, unquoted agent-instructions template carried by this plugin version."
  doLast {
    logger.quiet(HardeningAgentTemplateDiffTask.BLOCK_START)
    logger.quiet(
        HardeningTemplateDigest.TEMPLATE.lineSequence()
            .joinToString("\n") { it.removePrefix("> ") })
    logger.quiet(HardeningAgentTemplateDiffTask.BLOCK_END)
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
  val convergenceRetryAction = "run " +
      "${qualifiedHardeningTaskPath(convergenceProjectPath, "pitestConverge")} from the start"
  val incompleteReportRetry = "\n  " + generatedReportRetryGuidance(
      retryAction = convergenceRetryAction,
      workflowDescription = "convergence",
  )
  val invalidReportClosure = knownInvalidExecutionClosure(
      "\n  " + workflowRetryGuidance(convergenceRetryAction))
  val convergenceMutateOnly = providers.gradleProperty(HardeningOptionNames.MUTATE_ONLY)
  doLast {
    convergenceMutateOnly.orNull?.trim()?.takeIf(String::isNotEmpty)?.let { scope ->
      throw GradleException(
          "pitestConverge cannot consume a scoped mutation population " +
              "(-PmutateOnly=$scope). Re-run without -PmutateOnly.")
    }
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
      val evidenceFile: File?,
      val toolchainFile: File?,
    )
    // Validate every suite before replacing the prior snapshot, copying a partial
    // successor, or clearing any canonical report. A later failed/interrupted suite
    // must not destroy the earlier suites' only round-one evidence.
    val reports = reportsRoot.get().asFile
    val inputs = names.sorted().map { suiteName ->
      val reportDir = reports.resolve(suiteName)
      val csv = reportDir.resolve("mutations.csv")
      if (reportDir.resolve(".running").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-one report was left by an interrupted or " +
                "failed run — a partial population cannot anchor the diff." +
                incompleteReportRetry
        )
      }
      if (!csv.isFile) {
        throw GradleException(
            "pitestConverge: no round-one report for '$suiteName' at $csv." +
                incompleteReportRetry)
      }
      val evidenceFile = reportDir.resolve(".evidence.tsv")
      val toolchainFile = reportDir.resolve(".toolchain.tsv")
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
      if (evidence != null &&
          evidence.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
        val toolchain = if (!toolchainFile.isFile) {
          throw GradleException(
              "pitestConverge: '$suiteName' round-one evidence has no mutation-toolchain record")
        } else try {
          MutationToolchainRecord.parse(toolchainFile.readText())
        } catch (e: IllegalArgumentException) {
          throw GradleException(
              "pitestConverge: invalid round-one mutation-toolchain record for '$suiteName': ${e.message}", e)
        }
        if (toolchain.identitySha256 != evidence.mutationToolchainSha256) {
          throw GradleException(
              "pitestConverge: '$suiteName' round-one mutation-toolchain record does not match evidence")
        }
      }
      try {
        Mutant.parseReport(csv.readLines(), invalidReportClosure)
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "pitestConverge: '$suiteName' round-one report is invalid:\n${e.message}", e)
      }
      ConvergeSnapshotInput(
          suiteName,
          reportDir,
          csv,
          evidenceFile.takeIf(File::isFile),
          toolchainFile.takeIf(File::isFile),
      )
    }
    val snapshot = snapshotRoot.get().asFile
    BaselineFiles.deleteRecursivelyIfExists(snapshot)
    snapshot.mkdirs()
    inputs.forEach { input ->
      input.csv.copyTo(snapshot.resolve("${input.suiteName}.csv"))
      input.evidenceFile?.copyTo(snapshot.resolve("${input.suiteName}.evidence.tsv"))
      input.toolchainFile?.copyTo(snapshot.resolve("${input.suiteName}.toolchain.tsv"))
    }
    // Copy every suite successfully before clearing any canonical report.
    inputs.forEach { BaselineFiles.deleteRecursivelyIfExists(it.reportDir) }
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
  val convergenceProjectPath = project.path
  val convergenceRetryAction = "run " +
      "${qualifiedHardeningTaskPath(convergenceProjectPath, "pitestConverge")} from the start"
  val incompleteReportRetry = "\n  " + generatedReportRetryGuidance(
      retryAction = convergenceRetryAction,
      workflowDescription = "convergence",
  )
  val invalidReportClosure = knownInvalidExecutionClosure(
      "\n  " + workflowRetryGuidance(convergenceRetryAction))
  doLast {
    val gated = MutantStatus.entries.filter { it.gated }.mapTo(HashSet()) { it.name }
    // Rows can share a (class,method,line,mutator) key, so statuses are compared as
    // sorted multisets per key rather than single values. Converge deliberately KEEPS
    // the line in its key while the baseline and modeCompare dropped it: both rounds
    // run identical code, so lines cannot churn here, and the finer key localizes a
    // flip to the exact mutant instead of a sibling group.
    fun statuses(csv: File, suiteName: String, round: String): Map<String, List<String>> {
      val rows = try {
        Mutant.parseReport(csv.readLines(), invalidReportClosure)
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "pitestConverge: '$suiteName' $round report is invalid:\n${e.message}", e)
      }
      return rows.groupBy({ it.lineFullKey }, { it.rawStatus })
          .mapValues { (_, statusList) -> statusList.sorted() }
    }
    var boundaryFlips = 0
    var benignFlips = 0
    names.forEach { suiteName ->
      val round1Csv = snapshotRoot.get().asFile.resolve("$suiteName.csv")
      if (!round1Csv.isFile) {
        throw GradleException(
            "pitestConverge: no round-one snapshot for '$suiteName' at $round1Csv." +
                incompleteReportRetry)
      }
      val round1 = statuses(round1Csv, suiteName, "round-one")
      val round2Csv = reportsRoot.get().asFile.resolve("$suiteName/mutations.csv")
      if (round2Csv.parentFile.resolve(".running").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-two report was left by an interrupted or " +
                "failed run — a partial population would read as mass flips." +
                incompleteReportRetry
        )
      }
      if (!round2Csv.isFile) {
        throw GradleException(
            "pitestConverge: no round-two report for '$suiteName' at $round2Csv." +
                incompleteReportRetry)
      }
      if (round2Csv.parentFile.resolve(".scoped").isFile) {
        throw GradleException(
            "pitestConverge: the '$suiteName' round-two report was produced with -PmutateOnly — " +
                "a scoped population cannot prove suite convergence. Re-run without -PmutateOnly."
        )
      }
      val round1EvidenceFile = snapshotRoot.get().asFile.resolve("$suiteName.evidence.tsv")
      val round2EvidenceFile = round2Csv.parentFile.resolve(".evidence.tsv")
      val round1Evidence = round1EvidenceFile.takeIf(File::isFile)?.let { file ->
        try {
          PitestEvidence.parse(file.readText())
        } catch (e: IllegalArgumentException) {
          throw GradleException(
              "pitestConverge: invalid round-one evidence for '$suiteName': ${e.message}", e)
        }
      }
      val round2Evidence = round2EvidenceFile.takeIf(File::isFile)?.let { file ->
        try {
          PitestEvidence.parse(file.readText())
        } catch (e: IllegalArgumentException) {
          throw GradleException(
              "pitestConverge: invalid round-two evidence for '$suiteName': ${e.message}", e)
        }
      }
      if ((round1Evidence == null) != (round2Evidence == null)) {
        throw GradleException(
            "pitestConverge: '$suiteName' mixes a legacy round with a provenance-bound round; " +
                "run both rounds again under this plugin")
      }
      if (round1Evidence != null && round2Evidence != null) {
        fun stableEvidence(evidence: PitestEvidence) = listOf(
            evidence.pitestVersion,
            evidence.junitPluginVersion,
            evidence.pluginSha256,
            evidence.identitySchema,
            evidence.javaVersion,
            evidence.sourceSha256,
            evidence.classesSha256,
            evidence.classpathSha256,
            evidence.toolClasspathSha256,
            evidence.mutationToolchainSha256,
            evidence.configurationSha256,
            evidence.scope,
            evidence.historyAssisted.toString(),
        )
        if (stableEvidence(round1Evidence) != stableEvidence(round2Evidence)) {
          throw GradleException(
              "pitestConverge: '$suiteName' rounds do not describe identical code, tools, " +
                  "classpath, and suite configuration")
        }
        if (round1Evidence.reportSha256 != PitestEvidence.sha256(round1Csv) ||
            round2Evidence.reportSha256 != PitestEvidence.sha256(round2Csv)) {
          throw GradleException(
              "pitestConverge: '$suiteName' report changed after its round evidence was recorded")
        }
        val round2ToolchainFile = round2Csv.parentFile.resolve(".toolchain.tsv")
        if (round2Evidence.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
          val round2Toolchain = if (!round2ToolchainFile.isFile) {
            throw GradleException(
                "pitestConverge: '$suiteName' round-two evidence has no mutation-toolchain record")
          } else try {
            MutationToolchainRecord.parse(round2ToolchainFile.readText())
          } catch (e: IllegalArgumentException) {
            throw GradleException(
                "pitestConverge: invalid round-two mutation-toolchain record for '$suiteName': ${e.message}", e)
          }
          if (round2Toolchain.identitySha256 != round2Evidence.mutationToolchainSha256) {
            throw GradleException(
                "pitestConverge: '$suiteName' round-two mutation-toolchain record does not match evidence")
          }
        }
      } else {
        logger.warn(
            "pitestConverge: '$suiteName' rounds have no completed-run evidence — " +
                "legacy diagnostic accepted, but it is not provenance-bound")
      }
      val round2 = statuses(round2Csv, suiteName, "round-two")
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
              "a row with pitest<Suite>BaselineUnion only once observed to flip in both directions."
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
val trialMutatorsProperty = providers.gradleProperty(HardeningOptionNames.TRIAL_MUTATORS)
var previousTrialTask: String? = null
val pitestMutatorTrial = tasks.register("pitestMutatorTrial") {
  group = "verification"
  description = "Runs every PIT suite with only the -PtrialMutators candidates and tabulates what fired; enable per suite only what fires, and record the numbers."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val names = convergeSuiteNames
  val trial = trialMutatorsProperty
  val trialProjectPath = project.path
  val trialRetryAction = "run " +
      "${qualifiedHardeningTaskPath(trialProjectPath, "pitestMutatorTrial")} again with the " +
      "same -PtrialMutators candidates"
  val incompleteReportRetry = "\n  " + generatedReportRetryGuidance(
      retryAction = trialRetryAction,
      workflowDescription = "trial",
  )
  val invalidReportClosure = knownInvalidExecutionClosure(
      "\n  " + workflowRetryGuidance(trialRetryAction))
  doLast {
    val candidates = trial.orNull ?: throw GradleException(
        "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only, not the suites' existing sets"
    )
    var fired = 0
    val width = names.maxOf { it.length } + 2
    val lines = names.sorted().map { name ->
      val csv = reportsRoot.get().asFile.resolve("$name-trial/mutations.csv")
      // A CSV still carrying the '.running' sentinel is a crashed or interrupted
      // trial's partial population — tabulating it prints half numbers the task
      // then tells the user to record in config/pitest/README.md. Deliberately do
      // not refuse a marker without a CSV: PIT's candidate-cannot-fire exit is a
      // tolerated non-zero and leaves exactly that state, so the aggregate reports
      // it as zero generated. Distinguishing another pre-report failure requires
      // PIT's preceding output, not inference from this intentionally ambiguous marker.
      if (csv.isFile && csv.parentFile.resolve(".running").isFile) {
        throw GradleException(
            "pitestMutatorTrial: the '$name' trial output was left by an interrupted or failed " +
                "run — a partial report must not be recorded as trial evidence." +
                incompleteReportRetry
        )
      }
      val rows = if (csv.isFile) {
        try {
          Mutant.parseReport(csv.readLines(), invalidReportClosure)
        } catch (e: IllegalArgumentException) {
          throw GradleException(
              "pitestMutatorTrial: '$name' trial report is invalid:\n${e.message}", e)
        }
      } else {
        emptyList()
      }
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
//   ./gradlew pitestModeCompareUnion          # writes reviewed flip insurance
val pitestModesRoot = layout.buildDirectory.dir("pitest-modes")
val pitestModeProperty = providers.gradleProperty(HardeningOptionNames.PITEST_MODE)
val pitestModeSnapshot = tasks.register("pitestModeSnapshot") {
  group = "verification"
  description = "Stashes the current PIT reports as -PpitestMode=<label> for pitestModeCompare, then clears them."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val mode = pitestModeProperty
  val modeProjectPath = project.path
  val modeRetryAction = "re-run every suite in this mode, then run " +
      "${qualifiedHardeningTaskPath(modeProjectPath, "pitestModeSnapshot")} with the same " +
      "-PpitestMode label"
  val incompleteReportRetry = "\n  " + generatedReportRetryGuidance(
      retryAction = modeRetryAction,
      workflowDescription = "mode run and snapshot",
  )
  val invalidReportClosure = knownInvalidExecutionClosure(
      "\n  " + workflowRetryGuidance(modeRetryAction))
  val snapshotMutateOnly = providers.gradleProperty(HardeningOptionNames.MUTATE_ONLY)
  doLast {
    snapshotMutateOnly.orNull?.trim()?.takeIf(String::isNotEmpty)?.let { scope ->
      throw GradleException(
          "pitestModeSnapshot cannot consume a scoped mutation population " +
              "(-PmutateOnly=$scope). Re-run without -PmutateOnly.")
    }
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
      val toolchainFile: File?,
    )
    // Validate the whole fleet before mutating either the destination or any source
    // report. Otherwise a bad later suite leaves a partial snapshot and has already
    // deleted the earlier suites' only reports.
    val inputs = names.sorted().map { suiteName ->
      val reportDir = reportsRoot.get().asFile.resolve(suiteName)
      val csv = reportDir.resolve("mutations.csv")
      if (reportDir.resolve(".running").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report was left by an interrupted or failed run — " +
                "a partial population is not an observation of this mode." +
                incompleteReportRetry
        )
      }
      if (!csv.isFile) {
        throw GradleException(
            "pitestModeSnapshot: no report for '$suiteName' at $csv — run every suite in the mode " +
                "being labeled first; a partial snapshot would diff a suite against its absence." +
                incompleteReportRetry
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
      val toolchainFile = reportDir.resolve(".toolchain.tsv")
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
        if (evidence.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
          val toolchain = if (!toolchainFile.isFile) {
            throw GradleException(
                "pitestModeSnapshot: '$suiteName' modern evidence has no completed " +
                    "mutation-toolchain record at $toolchainFile")
          } else try {
            MutationToolchainRecord.parse(toolchainFile.readText())
          } catch (e: IllegalArgumentException) {
            throw GradleException(
                "pitestModeSnapshot: invalid mutation-toolchain record for '$suiteName': ${e.message}", e)
          }
          if (toolchain.identitySha256 != evidence.mutationToolchainSha256) {
            throw GradleException(
                "pitestModeSnapshot: '$suiteName' mutation-toolchain record does not match its evidence")
          }
        }
      } else {
        logger.warn(
            "pitestModeSnapshot: '$suiteName' has no completed-run evidence manifest — " +
                "legacy snapshot accepted for N-1 migration; re-run under this plugin before unioning flips")
      }
      try {
        Mutant.parseReport(csv.readLines(), invalidReportClosure)
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "pitestModeSnapshot: '$suiteName' report is invalid for mode '$label':\n${e.message}",
            e,
        )
      }
      SnapshotInput(
          suiteName,
          reportDir,
          csv,
          evidenceFile.takeIf(File::isFile),
          toolchainFile.takeIf(File::isFile),
      )
    }
    val dest = destPath.toFile()
    BaselineFiles.deleteRecursivelyIfExists(dest)
    dest.mkdirs()
    inputs.forEach { input ->
      input.csv.copyTo(dest.resolve("${input.suiteName}.csv"))
      input.evidenceFile?.copyTo(dest.resolve("${input.suiteName}.evidence.tsv"))
      input.toolchainFile?.copyTo(dest.resolve("${input.suiteName}.toolchain.tsv"))
    }
    // Copy every suite successfully before clearing any canonical report.
    inputs.forEach { BaselineFiles.deleteRecursivelyIfExists(it.reportDir) }
    logger.lifecycle(
        "pitestModeSnapshot: ${names.size} report(s) stashed as '$label'; reports cleared so the " +
            "next mode's run cannot be served from these"
    )
  }
}
private val pitestModeCompareUnionPreflight = tasks.register<HardeningOperationRequestTask>(
    "pitestModeCompareUnionPreflight") {
  description = "Internal to pitestModeCompareUnion: selects one fresh mode-insurance write."
  hardeningProjectPath.set(project.path)
  request.set(HardeningWriteRequest.MODE_FLIP_INSURANCE)
  excludedTaskNames.set(gradle.startParameter.excludedTaskNames.sorted())
  operationSession.set(hardeningOperationSession)
  certificationSession.set(hardeningCertificationSession)
  usesService(hardeningOperationSession)
  usesService(hardeningCertificationSession)
  mustRunAfter(hardeningCertifyPreflight)
}
val pitestModeCompare = tasks.register("pitestModeCompare") {
  group = "verification"
  description = "Diffs per-mutant statuses across pitestModeSnapshot labels; fails on uninsured unkilled-boundary flips (pitestModeCompareUnion writes insurance) and sweeps for accepted rows unkilled in no mode."
  mustRunAfter(pitestModeSnapshot)
  mustRunAfter(pitestModeCompareUnionPreflight)
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val operationSession = hardeningOperationSession
  val modeCompareProjectPath = project.path
  val modeSnapshotTaskPath =
      qualifiedHardeningTaskPath(modeCompareProjectPath, "pitestModeSnapshot")
  val modeCompareTaskPath = qualifiedHardeningTaskPath(modeCompareProjectPath, "pitestModeCompare")
  val modeCompareUnionTaskPath =
      qualifiedHardeningTaskPath(modeCompareProjectPath, "pitestModeCompareUnion")
  val modeCompareExcludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  usesService(operationSession)
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  val modeCompareProjectDirectory = layout.projectDirectory.asFile
  doLast {
    val unionFlips = operationSession.get().modeFlipInsuranceRequested(modeCompareProjectPath)
    if (unionFlips && modeCompareExcludedTaskNames.isNotEmpty()) {
      throw GradleException(
          "pitestModeCompare: a mode-insurance write cannot prove its complete task graph with " +
              "task exclusion(s): " +
              modeCompareExcludedTaskNames.joinToString { "-x $it" })
    }
    // A union is prepared here and committed by a later typed task. Bind both the
    // exact snapshot inventory/bytes and every committed mutation-record input the
    // decision can read; current-checkout evidence alone cannot detect a valid but
    // different snapshot replacing the one that produced the prepared bytes.
    BaselineFiles.requireDirectoryOrMissing(modeCompareProjectDirectory, baselineDir.asFile)
    (names.flatMap { suiteName ->
      listOf(
          baselineDir.file("$suiteName-accepted.csv").asFile,
          baselineDir.file("$suiteName-timeouts.csv").asFile,
          baselineDir.file("$suiteName-pitest-version").asFile,
          baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile,
      )
    } + baselineDir.file("README.md").asFile).forEach {
      BaselineFiles.requireRegularFileOrMissing(modeCompareProjectDirectory, it)
    }
    val preparedReadTrees = if (unionFlips) listOf(
      BaselineFiles.snapshotTree(snapshotRoot.get().asFile),
      BaselineFiles.snapshotTree(baselineDir.asFile),
    ) else emptyList()
    val gated = MutantStatus.entries.filter { it.gated }.mapTo(HashSet()) { it.name }
    val modes = snapshotRoot.get().asFile.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
        ?: emptyList()
    if (modes.size < 2) {
      throw GradleException(
          "pitestModeCompare needs at least two labeled snapshots under ${snapshotRoot.get().asFile} " +
              "(found: ${if (modes.isEmpty()) "none" else modes.joinToString()}). Run the suites and " +
              "'$modeSnapshotTaskPath -PpitestMode=<label>' once per mode — e.g. quiet suites as 'solo', " +
              "then under qualityGate as 'gate'."
      )
    }
    // Line-less keys, like the baseline itself: the two modes ran the same code, so
    // per-key status multisets compare cleanly without lines, and an insurance row
    // written here is a row the verify's comparison must recognize.
    fun modeRows(csv: File, suiteName: String, label: String): List<Mutant> {
      val invalidExecutionRetry = knownInvalidExecutionClosure(
        "\n  " + workflowRetryGuidance(
          retryAction = "re-run all suites in mode '$label', run $modeSnapshotTaskPath " +
              "-PpitestMode=$label, then run $modeCompareTaskPath",
        ),
      )
      return try {
        Mutant.parseReport(csv.readLines(), invalidExecutionRetry)
      } catch (e: IllegalArgumentException) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' snapshot '$label' is invalid:\n${e.message}", e)
      }
    }
    var benignFlips = 0
    var insuredFlips = 0
    val uninsured = mutableListOf<String>()
    val annotatedNow = mutableListOf<String>()
    val addedNow = mutableListOf<String>()
    val deadRows = mutableListOf<String>()
    data class PendingModeWrite(val file: File, val content: String)
    // A mode comparison is a project-wide decision. Compute and validate every
    // suite before committing any file so one bad later snapshot cannot leave an
    // earlier baseline partially insured by a failed invocation.
    val pendingModeWrites = mutableListOf<PendingModeWrite>()
    names.forEach { suiteName ->
      val modeBaselineRebaseTaskPath = qualifiedHardeningTaskPath(
          modeCompareProjectPath,
          "pitest${suiteName.replaceFirstChar(Char::uppercase)}BaselineRebase",
      )
      val evidenceByMode = modes.mapNotNull { label ->
        val file = snapshotRoot.get().asFile.resolve("$label/$suiteName.evidence.tsv")
        if (!file.isFile) null else {
          val evidence = PitestEvidence.parse(file.readText())
          if (evidence.suite != suiteName || evidence.scope != PitestEvidence.FULL_SCOPE ||
              evidence.historyAssisted) {
            throw GradleException(
                "pitestModeCompare: '$suiteName' snapshot '$label' evidence is not a fresh full " +
                    "observation of that suite (suite=${evidence.suite}, scope=${evidence.scope}, " +
                    "historyAssisted=${evidence.historyAssisted})")
          }
          label to evidence
        }
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
              evidence.mutationToolchainSha256,
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
          if (evidence.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
            val toolchainFile = snapshotRoot.get().asFile
                .resolve("$label/$suiteName.toolchain.tsv")
            val toolchain = if (!toolchainFile.isFile) {
              throw GradleException(
                  "pitestModeCompare: '$suiteName' snapshot '$label' has modern evidence but no " +
                      "mutation-toolchain record")
            } else try {
              MutationToolchainRecord.parse(toolchainFile.readText())
            } catch (e: IllegalArgumentException) {
              throw GradleException(
                  "pitestModeCompare: '$suiteName' snapshot '$label' has invalid mutation-toolchain " +
                      "record: ${e.message}", e)
            }
            if (toolchain.identitySha256 != evidence.mutationToolchainSha256) {
              throw GradleException(
                  "pitestModeCompare: '$suiteName' snapshot '$label' mutation-toolchain record " +
                      "does not match its evidence")
            }
          }
        }
      } else if (unionFlips) {
        throw GradleException(
            "pitestModeCompare: refusing a mode-insurance write for legacy '$suiteName' snapshots without " +
                "completed-run provenance; capture both modes again under this plugin")
      }
      val modePitVersion = evidenceByMode.values.firstOrNull()?.pitestVersion
      val modeMutationToolchain = evidenceByMode.values.firstOrNull()?.mutationToolchainSha256
      val modeToolVersionFile = baselineDir.file("$suiteName-pitest-version").asFile
      val modeToolchainFile = baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile
      val mutationRecordExists = baselineDir.file("$suiteName-accepted.csv").asFile.isFile ||
          baselineDir.file("$suiteName-timeouts.csv").asFile.isFile
      val modeProvenance = CommittedMutationProvenance.classify(
          mutationRecordExists,
          modeToolVersionFile.takeIf { it.isFile }?.readText(),
          modeToolchainFile.takeIf { it.isFile }?.readText(),
      )
      modeProvenance.malformedPitVersion?.let { detail ->
        throw GradleException(
            "pitestModeCompare: '$suiteName' committed PIT-version stamp is invalid: $detail")
      }
      modeProvenance.malformedToolchain?.let { detail ->
        throw GradleException(
            "pitestModeCompare: '$suiteName' committed mutation-toolchain record is invalid: $detail")
      }
      val recordedModeToolchain = modeProvenance.toolchain
      if (modeProvenance.orphan) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' has orphan mutation-provenance sidecar(s) but no " +
                "accepted or timeout record; run $modeBaselineRebaseTaskPath to reconcile " +
                "the orphan state before interpreting mode results")
      }
      if (modeProvenance.torn) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' committed mutation provenance is torn — exactly " +
                "one of ${modeToolVersionFile.name} and ${modeToolchainFile.name} exists; run " +
                "$modeBaselineRebaseTaskPath before " +
                "interpreting mode results")
      }
      if (modeProvenance.disagreement) {
        throw GradleException(
            "pitestModeCompare: '$suiteName' committed provenance disagrees: " +
                "${modeToolVersionFile.name} says PIT ${modeProvenance.pitVersion}, but " +
                "${modeToolchainFile.name} says PIT " +
                "${checkNotNull(recordedModeToolchain).pitestVersion}; run " +
                "$modeBaselineRebaseTaskPath before " +
                "interpreting mode results")
      }
      if (unionFlips) {
        if (modeMutationToolchain == PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
          throw GradleException(
              "pitestModeCompare: refusing a mode-insurance write for '$suiteName' snapshots with " +
              "legacy-unbound mutation-toolchain evidence; capture every mode again")
        }
        if (modeProvenance.legacyUnbound) {
          throw GradleException(
              "pitestModeCompare: '$suiteName' has a legacy-unbound committed mutation record; " +
                  "run $modeBaselineRebaseTaskPath against a " +
                  "reviewed fresh observation before writing mode insurance")
        }
        if (modePitVersion != null && modeProvenance.pitVersion != null &&
            modeProvenance.pitVersion != modePitVersion) {
          throw GradleException(
              "pitestModeCompare: '$suiteName' snapshots use PIT $modePitVersion but " +
                  "${modeToolVersionFile.name} records ${modeProvenance.pitVersion} — " +
                  "run $modeBaselineRebaseTaskPath before " +
                  "writing across the tool-version boundary")
        }
        if (modeMutationToolchain != null && recordedModeToolchain != null &&
            recordedModeToolchain.identitySha256 != modeMutationToolchain) {
          throw GradleException(
              "pitestModeCompare: '$suiteName' snapshots use mutation toolchain " +
                  "$modeMutationToolchain but ${modeToolchainFile.name} records " +
                  "${recordedModeToolchain.identitySha256}; run $modeBaselineRebaseTaskPath first")
        }
      }
      val modeRowsByLabel = modes.associateWith { label ->
        val csv = snapshotRoot.get().asFile.resolve("$label/$suiteName.csv")
        if (!csv.isFile) {
          throw GradleException(
              "pitestModeCompare: snapshot '$label' has no '$suiteName' report — the suite set " +
                  "changed since it was taken; re-run that mode and re-snapshot"
          )
        }
        modeRows(csv, suiteName, label)
      }
      val perMode = modeRowsByLabel.mapValues { (_, rows) ->
        rows.groupBy({ it.coordinate }, { it.rawStatus })
            .mapValues { (_, statusList) -> statusList.sorted() }
      }
      // Baseline rows are an ordered LIST: a duplicate key is a sibling mutant, and
      // the set this used to collapse into silently deduped siblings on the union
      // write — a shrink outside prune's rules. BaselineDocument owns schema
      // validation and retains every comment and blank line for the union writer.
      // Its rows still use BaselineNotes for the N-1 and current row spellings, so
      // mode compare and verify cannot disagree about row identity.
      val baselineFile = baselineDir.file("$suiteName-accepted.csv").asFile
      val baselineExisted = baselineFile.isFile
      val baselineDocument = BaselineDocument.parse(
          if (baselineExisted) baselineFile.readText() else "")
      val malformedRows = baselineDocument.malformedRows.map { it.raw }
      if (malformedRows.isNotEmpty()) {
        logger.warn(
            "pitestModeCompare: ${malformedRows.size} malformed row(s) in ${baselineFile.name} — " +
                "expected 'class,method,mutator,STATUS [# note] [# line N]'; the verify names these " +
                "too, and no row-slot rewrite can interpret them:\n" +
                malformedRows.joinToString("\n") { "  $it" }
        )
        if (unionFlips) {
          throw GradleException(
              "pitestModeCompare: ${baselineFile.name} carries ${malformedRows.size} malformed row(s) " +
                  "(listed above) — pitestModeCompareUnion refuses uninterpretable content. " +
                  "Fix the row shape first."
          )
        }
      }
      val invalidLineMetadataRows = baselineDocument.invalidLineMetadataRows
      if (invalidLineMetadataRows.isNotEmpty()) {
        val details = invalidLineMetadataRows.joinToString("\n") { row ->
          "  line ${row.lineNumber}: ${row.raw}\n    " +
              RecordedLineMetadata.acceptedInvalidDetail(
                  checkNotNull(row.value.invalidLineMetadata))
        }
        logger.warn(
            "pitestModeCompare: ${invalidLineMetadataRows.size} accepted row(s) in " +
                "${baselineFile.name} carry invalid diagnostic line metadata. The key still " +
                "compares, but a range is not evidence that every line in the span was observed:\n" +
                details)
        if (unionFlips) {
          throw GradleException(
              "pitestModeCompare: pitestModeCompareUnion refuses to rewrite invalid line " +
                  "metadata; list the exact observed mutant lines first.")
        }
      }
      val acceptedRows: MutableList<BaselineNotes.Row> = baselineDocument.rows.toMutableList()
      // Counts, not membership: sibling mutants share the line-less key, and the
      // verify compares multisets — insurance must match its arithmetic or a key
      // "already insured" by one row still fails the next gate verify on the
      // surfaced twin.
      val acceptedCounts: MutableMap<String, Int> = mutableMapOf()
      acceptedRows.forEach { acceptedCounts.merge(it.key, 1, Int::plus) }
      // Persistent insurance is literal evidence on the row, not an inference from
      // baseline multiplicity. A plain acceptance can cover today's widest mode and
      // still be offered as a prune candidate on tomorrow's killed read; only the
      // shared marker BaselineEngine.keepPlan recognizes survives that transition.
      val insuredCounts: MutableMap<String, Int> = mutableMapOf()
      acceptedRows.filter { BaselineNotes.hasFlipInsurance(it.note) }
          .forEach { insuredCounts.merge(it.key, 1, Int::plus) }
      // Observed lines per gated row across every mode's snapshot, so an insurance
      // row lands carrying the '# line' tag the verify's drift advisory reads —
      // an untagged row would put its whole key on the advisory's partial-tag
      // fallback path, weakening the row-level check for its siblings too.
      val rowLines: Map<String, Set<Int>> = if (!unionFlips) emptyMap() else
        modeRowsByLabel.values.flatten()
            .mapNotNull { if (!it.gated) null else it.baselineKey to it.line }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, lines) -> lines.filterNotNull().toSet() }
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
            val missingInsurance = neededRows.associate { (row, needed) ->
              row to maxOf(0, needed - (insuredCounts[row] ?: 0))
            }
            val multiplicityCovered = neededRows.all { (row, needed) ->
              (acceptedCounts[row] ?: 0) >= needed
            }
            when {
              missingInsurance.values.all { it == 0 } -> {
                insuredFlips++
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (already insured in the baseline)")
              }
              unionFlips -> {
                var annotatedHere = 0
                var addedHere = 0
                neededRows.forEach { (row, needed) ->
                  var missing = maxOf(0, needed - (insuredCounts[row] ?: 0))
                  // File order is the deterministic sibling tie-breaker. Annotating
                  // preserves each selected row's family note and line tag; only a
                  // true multiplicity shortfall appends a new row.
                  val unmarkedExisting = acceptedRows.indices.filter { index ->
                    acceptedRows[index].key == row &&
                        !BaselineNotes.hasFlipInsurance(acceptedRows[index].note)
                  }
                  unmarkedExisting.take(missing).forEach { index ->
                    val existing = acceptedRows[index]
                    val annotated = existing.copy(
                        note = BaselineNotes.withFlipInsurance(existing.note, detail))
                    acceptedRows[index] = annotated
                    insuredCounts.merge(row, 1, Int::plus)
                    annotatedNow.add("$suiteName: ${BaselineNotes.render(annotated)}")
                    annotatedHere++
                    missing--
                  }
                  repeat(missing) {
                    acceptedCounts.merge(row, 1, Int::plus)
                    insuredCounts.merge(row, 1, Int::plus)
                    val added = BaselineNotes.Row(
                        row,
                        BaselineNotes.withFlipInsurance(null, detail),
                        rowLines[row].orEmpty().sorted(),
                    )
                    acceptedRows.add(added)
                    addedNow.add("$suiteName: ${BaselineNotes.render(added)}")
                    addedHere++
                  }
                }
                unionedHere = true
                logger.lifecycle(
                    "pitestModeCompare '$suiteName': $key — $detail (flip insurance prepared: " +
                        "$annotatedHere existing row(s) annotated, $addedHere row(s) added)")
              }
              multiplicityCovered -> {
                val missing = missingInsurance.values.sum()
                uninsured.add(
                    "$suiteName: $key — $detail — baseline multiplicity covers this flip, but " +
                        "$missing required row(s) have no literal 'flip insurance' marker; " +
                  "run $modeCompareUnionTaskPath to annotate the existing row(s) " +
                        "without adding duplicates")
              }
              else -> uninsured.add("$suiteName: $key — $detail")
            }
          }
        }
      }
      if (unionedHere) {
        // Existing rows stay in file order and retain their key, family note, and line
        // tags; deterministic covered rows gain only the insurance parenthetical, and
        // true multiplicity shortfalls append rows in key order. BaselineDocument
        // replaces only row slots, so comments and blank evidence remain byte-for-byte.
        val targetSchema = if (baselineExisted) baselineDocument.schemaState
        else BaselineDocument.SchemaState.CURRENT
        val rendered = baselineDocument.rewriteRowsPreservingOrigins(
            acceptedRows.mapIndexed { index, row ->
              BaselineDocument.RowReplacement(
                  row,
                  index.takeIf { it < baselineDocument.rows.size },
              )
            },
            targetSchema,
        )
        val baselineWrite = PendingModeWrite(
            baselineFile,
            if (!baselineExisted && rendered.isNotEmpty() && !rendered.endsWith("\n")) "$rendered\n"
            else rendered)
        if (!mutationRecordExists && modePitVersion != null) {
          pendingModeWrites += PendingModeWrite(modeToolVersionFile, "$modePitVersion\n")
          val snapshotToolchain = modes.asSequence()
              .map { label -> snapshotRoot.get().asFile.resolve("$label/$suiteName.toolchain.tsv") }
              .firstOrNull(File::isFile)
              ?: throw GradleException(
                  "pitestModeCompare: '$suiteName' cannot stamp a new record without snapshot " +
                      "mutation-toolchain provenance")
          pendingModeWrites += PendingModeWrite(modeToolchainFile, snapshotToolchain.readText())
        }
        // The baseline is the logical commit marker for a new record. Sidecars go
        // first so process death leaves an orphan state that every reader refuses.
        pendingModeWrites += baselineWrite
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
        val ordered = indices.sortedBy { BaselineNotes.hasFlipInsurance(acceptedRows[it].note) }
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
          deadCandidates.partition { BaselineNotes.hasFlipInsurance(acceptedRows[it].note) }
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
        "${uninsured.size} uninsured boundary flip(s), ${annotatedNow.size} existing row(s) annotated, " +
        "${addedNow.size} union row(s) added, " +
        "$insuredFlips already insured, $benignFlips benign (e.g. KILLED<->TIMED_OUT)"
    if (uninsured.isNotEmpty()) {
      throw GradleException(
          summary + ":\n" + uninsured.joinToString("\n") { "  $it" } +
              "\nA row that differs between modes belongs in persistent baseline insurance " +
              "(HARDENING.md 'TIMED_OUT is detected...'): run $modeCompareUnionTaskPath. " +
              "It annotates already-covered rows before adding only a true " +
              "multiplicity shortfall, so the literal 'flip insurance' evidence survives prune."
      )
    }
    if (unionFlips) {
      preparedReadTrees.forEach { snapshot ->
        val differences = BaselineFiles.treeDifferences(snapshot)
        if (differences.isNotEmpty()) {
          throw GradleException(
              "pitestModeCompare: inputs changed while comparison was running — refusing to " +
                  "prepare mode insurance from ${snapshot.rootPath}:\n" +
                  differences.joinToString("\n") { "  $it" })
        }
      }
      try {
        operationSession.get().prepareProjectWrites(
            modeCompareProjectPath,
            ProjectWriteOperation.MODE_FLIP_INSURANCE,
            pendingModeWrites.map {
              PreparedMutationWrite(it.file.absolutePath, it.content)
            },
            preparedReadTrees,
        )
      } catch (e: IllegalArgumentException) {
        throw GradleException("pitestModeCompare: ${e.message}", e)
      }
      logger.lifecycle(
          "pitestModeCompare: prepared ${pendingModeWrites.size} baseline/provenance " +
              "file(s) for final current-checkout validation")
    }
    logger.lifecycle(summary)
  }
}
private val pitestModeCompareCommit = tasks.register<PitestModeCommitTask>(
    "pitestModeCompareCommit") {
  description = "Internal: finally revalidates and commits prepared mode-flip insurance."
  hardeningProjectPath.set(project.path)
  projectDirectory.set(layout.projectDirectory)
  snapshotRoot.set(pitestModesRoot)
  operationSession.set(hardeningOperationSession)
  usesService(hardeningOperationSession)
  dependsOn(pitestModeCompareUnionPreflight, pitestModeCompare)
}
private val pitestModeCompareUnion = tasks.register<HardeningOperationCompletionTask>("pitestModeCompareUnion") {
  group = "verification"
  description = "Diffs labeled modes and writes reviewed flip-insurance annotations from fresh provenance."
  hardeningProjectPath.set(project.path)
  request.set(HardeningWriteRequest.MODE_FLIP_INSURANCE)
  operationSession.set(hardeningOperationSession)
  usesService(hardeningOperationSession)
  dependsOn(pitestModeCompareUnionPreflight)
  dependsOn(pitestModeCompareCommit)
}

// Identity-preserving accepted-baseline migration: stamp the explicit current
// schema and render legacy five-field rows as line-less keys with '# line' tags.
// Whitespace-only placeholders canonicalize to absence, matching the ordinary
// writers; timeout audit sets keep their separate stable unversioned format. No
// report, no PIT run, no stamping of the PIT version: row identity is preserved by
// construction and substantive comment/blank evidence passes through verbatim.
// Ordinary report-driven writers deliberately preserve an existing document's
// schema state; fleet migration is explicit, reversible, and independent of
// mutation-run load.
private fun registerSchemaOperationPreflight(
    taskName: String,
    requestValue: HardeningWriteRequest,
) = tasks.register<HardeningOperationRequestTask>(taskName) {
  description = "Internal baseline-schema writer preflight."
  hardeningProjectPath.set(project.path)
  request.set(requestValue)
  excludedTaskNames.set(gradle.startParameter.excludedTaskNames.sorted())
  operationSession.set(hardeningOperationSession)
  certificationSession.set(hardeningCertificationSession)
  usesService(hardeningOperationSession)
  usesService(hardeningCertificationSession)
  mustRunAfter(hardeningCertifyPreflight)
}
private val migrateMutationBaselinesPreflight = registerSchemaOperationPreflight(
    "migrateMutationBaselinesPreflight", HardeningWriteRequest.SCHEMA_MIGRATE)
private val downgradeMutationBaselinesPreflight = registerSchemaOperationPreflight(
    "downgradeMutationBaselinesPreflight", HardeningWriteRequest.SCHEMA_DOWNGRADE)
pitestModeCompare.configure {
  mustRunAfter(migrateMutationBaselinesPreflight, downgradeMutationBaselinesPreflight)
}
val migrateMutationBaselines = tasks.register("migrateMutationBaselines") {
  group = "verification"
  description = "Stamps substantive accepted baselines as schema 1, canonicalizes rows, and removes empty placeholders."
  dependsOn(migrateMutationBaselinesPreflight)
  mustRunAfter(
      pitestModeCompareUnionPreflight,
      migrateMutationBaselinesPreflight,
      downgradeMutationBaselinesPreflight)
  val names = convergeSuiteNames
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  val schemaProjectDirectory = layout.projectDirectory.asFile
  val operationSession = hardeningOperationSession
  val schemaProjectPath = project.path
  val excludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  usesService(operationSession)
  doLast {
    try {
      operationSession.get().requireProjectOperation(
          schemaProjectPath, ProjectWriteOperation.SCHEMA_MIGRATE)
    } catch (e: IllegalArgumentException) {
      throw GradleException("migrateMutationBaselines: ${e.message}", e)
    }
    if (excludedTaskNames.isNotEmpty()) {
      throw GradleException(
          "migrateMutationBaselines: refusing task exclusion(s): " +
              excludedTaskNames.joinToString { "-x $it" })
    }
    BaselineFiles.requireDirectoryOrMissing(schemaProjectDirectory, baselineDir.asFile)
    names.flatMap { suiteName ->
      listOf(
          baselineDir.file("$suiteName-accepted.csv").asFile,
          baselineDir.file("$suiteName-timeouts.csv").asFile,
          baselineDir.file("$suiteName-pitest-version").asFile,
          baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile,
      )
    }.forEach { BaselineFiles.requireRegularFileOrMissing(schemaProjectDirectory, it) }
    // Parse every suite before touching any file. One unknown schema or malformed
    // row therefore refuses the project migration as a unit instead of leaving the
    // alphabetically earlier suites stamped and the later ones unversioned.
    val migrations = names.mapNotNull { suiteName ->
      val file = baselineDir.file("$suiteName-accepted.csv").asFile
      if (!file.isFile) null
      else {
        val document = BaselineDocument.parse(file.readText())
        Triple(
            suiteName,
            file,
            if (document.hasSubstantiveContent) document.migrateToCurrent() else null)
      }
    }
    migrations.forEach { (suiteName, file, migration) ->
      if (migration == null) {
        val removed = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(
            schemaProjectDirectory,
            file,
            baselineDir.file("$suiteName-timeouts.csv").asFile,
            baselineDir.file("$suiteName-pitest-version").asFile,
            baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile)
        logger.lifecycle(
            "pitest baseline '$suiteName': removed empty accepted-baseline placeholder" +
                (if (removed.orphanVersionStampRemoved) " and its orphan PIT-version stamp" else "") +
                (if (removed.orphanToolchainRecordRemoved) " and its orphan mutation-toolchain record" else ""))
      } else if (!migration.changed) {
        logger.lifecycle(
            "pitest baseline '$suiteName': already at accepted-baseline schema " +
                BaselineDocument.CURRENT_SCHEMA)
      } else {
        BaselineFiles.writeAtomically(schemaProjectDirectory, file, migration.content)
        logger.lifecycle(
            "pitest baseline '$suiteName': migrated to accepted-baseline schema " +
                "${BaselineDocument.CURRENT_SCHEMA}; canonicalized ${migration.canonicalizedRows} row(s)")
      }
    }
  }
}

// Rollback companion for a fleet migration: schema 1 removes only its marker. Rows,
// comments, blank lines, spelling, order, and duplicates remain byte-for-byte so the
// result is readable by the N-1 plugin. Whitespace-only placeholders canonicalize to
// absence in both directions. Future schemas default to refusing downgrade unless
// BaselineDocument explicitly declares their representation lossless for N-1.
val downgradeMutationBaselines = tasks.register("downgradeMutationBaselines") {
  group = "verification"
  description = "Removes schema 1 from substantive baselines for N-1 rollback; empty placeholders remain absent."
  dependsOn(downgradeMutationBaselinesPreflight)
  mustRunAfter(
      pitestModeCompareUnionPreflight,
      migrateMutationBaselinesPreflight,
      downgradeMutationBaselinesPreflight)
  mustRunAfter(migrateMutationBaselines)
  val names = convergeSuiteNames
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  val schemaProjectDirectory = layout.projectDirectory.asFile
  val operationSession = hardeningOperationSession
  val schemaProjectPath = project.path
  val excludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  usesService(operationSession)
  doLast {
    try {
      operationSession.get().requireProjectOperation(
          schemaProjectPath, ProjectWriteOperation.SCHEMA_DOWNGRADE)
    } catch (e: IllegalArgumentException) {
      throw GradleException("downgradeMutationBaselines: ${e.message}", e)
    }
    if (excludedTaskNames.isNotEmpty()) {
      throw GradleException(
          "downgradeMutationBaselines: refusing task exclusion(s): " +
              excludedTaskNames.joinToString { "-x $it" })
    }
    BaselineFiles.requireDirectoryOrMissing(schemaProjectDirectory, baselineDir.asFile)
    names.flatMap { suiteName ->
      listOf(
          baselineDir.file("$suiteName-accepted.csv").asFile,
          baselineDir.file("$suiteName-timeouts.csv").asFile,
          baselineDir.file("$suiteName-pitest-version").asFile,
          baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile,
      )
    }.forEach { BaselineFiles.requireRegularFileOrMissing(schemaProjectDirectory, it) }
    val downgrades = names.mapNotNull { suiteName ->
      val file = baselineDir.file("$suiteName-accepted.csv").asFile
      if (!file.isFile) null
      else {
        val document = BaselineDocument.parse(file.readText())
        Triple(
            suiteName,
            file,
            if (document.hasSubstantiveContent) document.downgradeToUnversioned() else null)
      }
    }
    downgrades.forEach { (suiteName, file, downgrade) ->
      if (downgrade == null) {
        val removed = BaselineFiles.deleteSemanticallyEmptyAcceptedRecord(
            schemaProjectDirectory,
            file,
            baselineDir.file("$suiteName-timeouts.csv").asFile,
            baselineDir.file("$suiteName-pitest-version").asFile,
            baselineDir.file("$suiteName-pitest-toolchain.tsv").asFile)
        logger.lifecycle(
            "pitest baseline '$suiteName': removed empty accepted-baseline placeholder" +
                (if (removed.orphanVersionStampRemoved) " and its orphan PIT-version stamp" else "") +
                (if (removed.orphanToolchainRecordRemoved) " and its orphan mutation-toolchain record" else ""))
      } else if (!downgrade.changed) {
        logger.lifecycle("pitest baseline '$suiteName': already unversioned (N-1-readable)")
      } else {
        BaselineFiles.writeAtomically(schemaProjectDirectory, file, downgrade.content)
        logger.lifecycle(
            "pitest baseline '$suiteName': removed accepted-baseline schema marker for N-1 rollback")
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
// wrapper adds repository SHAs and collects those receipts for release review. The
// receipt and its in-progress sentinel are machine-local campaign state, not build
// outputs: keeping them in the already-ignored `.pitest-history/` directory lets a
// later `clean hardeningCertify` preserve a completed fuzz campaign. Starting the next
// campaign preserves the last completed receipt and writes the sentinel before any
// target can run; receipt plus sentinel is an incomplete current campaign.
val fuzzTargetNames = objects.setProperty<String>()
val maxFuzzTime = providers.gradleProperty(HardeningOptionNames.MAX_FUZZ_TIME).orElse("60")
val localFuzzReceiptFile = layout.projectDirectory.file(".pitest-history/local-fuzz.tsv")
val localFuzzReceiptRunning = layout.projectDirectory.file(".pitest-history/local-fuzz.running")
val localFuzzReceiptLock = layout.projectDirectory.file(".pitest-history/local-fuzz.lock")
// One-release transition cleanup. Old receipts must not remain plausible beside the
// durable contract, and an interrupted old-version campaign must not poison a
// successful new one after its sentinel has moved.
val legacyLocalFuzzReceiptFile = layout.buildDirectory.file("hardening/local-fuzz.tsv")
val legacyLocalFuzzReceiptRunning = layout.buildDirectory.file("hardening/local-fuzz.running")
val localFuzzPluginCode = hardeningImplementationCode
val localFuzzPluginSha256 = hardeningExpectedPluginSha256
val validateFuzzBudget = tasks.register("validateFuzzBudget") {
  description = "Internal to local fuzz tasks: validates time and bounded-parallelism settings."
  val budget = maxFuzzTime
  val parallelism = maxParallelFuzzTargetsRaw
  inputs.property("maxFuzzTime", budget)
  inputs.property("maxParallelFuzzTargets", parallelism)
  doLast {
    val raw = budget.get()
    val seconds = raw.toLongOrNull()
    if (!Regex("[1-9][0-9]*").matches(raw) || seconds == null || seconds > Int.MAX_VALUE) {
      throw GradleException(
          "-PmaxFuzzTime must be positive whole seconds without leading zeros, up to " +
              "${Int.MAX_VALUE}; was '$raw' (0 is libFuzzer's run-forever sentinel)")
    }
    val parallelRaw = parallelism.get()
    val parallel = parallelRaw.toLongOrNull()
    if (!Regex("[1-9][0-9]*").matches(parallelRaw) ||
        parallel == null || parallel > Int.MAX_VALUE) {
      throw GradleException(
          "-PmaxParallelFuzzTargets must be positive whole targets without leading zeros, up to " +
              "${Int.MAX_VALUE}; was '$parallelRaw'")
    }
  }
}
// Retain the internal name for one candidate transition so `-x fuzzAllPreflight`
// cannot become a task-selection error that bypasses fuzzAll's own fail-closed start
// boundary. It is intentionally not part of the aggregate graph anymore.
tasks.register("fuzzAllPreflight") {
  description = "Internal compatibility marker; fuzzAll now owns its durable campaign start boundary."
}
val fuzzAll = tasks.register("fuzzAll") {
  group = "verification"
  description = "Runs every registered fuzz target locally; -PmaxFuzzTime=<seconds> applies per target and -PmaxParallelFuzzTargets=<count> bounds concurrency."
  mustRunAfter("clean")
  val names = fuzzTargetNames
  val maxTime = maxFuzzTime
  val parallelism = maxParallelFuzzTargetsRaw
  val receipt = localFuzzReceiptFile
  val running = localFuzzReceiptRunning
  val ownershipLock = localFuzzReceiptLock
  val legacyReceipt = legacyLocalFuzzReceiptFile
  val legacyRunning = legacyLocalFuzzReceiptRunning
  val legacyBuildDirectory = layout.buildDirectory
  val pluginCode = localFuzzPluginCode
  val expectedPluginSha256 = localFuzzPluginSha256
  val localRepoArtifactPath = hardeningLocalRepoArtifactPath
  val localRepoArtifactSha256 = hardeningExpectedLocalRepoArtifactSha256
  val trustedProjectDirectory = layout.projectDirectory.asFile
  val campaignTargets = fuzzTargetNames
  val campaignProjectPath = project.path
  val excludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  val fuzzSession = hardeningFuzzSession
  inputs.property("maxFuzzTime", maxTime)
  inputs.property("maxParallelFuzzTargets", parallelism)
  usesService(fuzzSession)
  doLast {
    val receiptFile = receipt.asFile
    val runningFile = running.asFile
    val lockFile = ownershipLock.asFile
    val legacyReceiptFile = legacyReceipt.get().asFile
    val legacyRunningFile = legacyRunning.get().asFile
    val legacyBuildDirectoryFile = legacyBuildDirectory.get().asFile
    val expectedTargets = campaignTargets.get()
    val session = fuzzSession.get()
    var ownsCampaign = false
    try {
      val historyDirectory = receiptFile.parentFile
      BaselineFiles.requireDirectoryOrMissing(trustedProjectDirectory, historyDirectory)
      historyDirectory.mkdirs()
      BaselineFiles.requireDirectoryOrMissing(trustedProjectDirectory, historyDirectory)
      BaselineFiles.requireRegularFileOrMissing(trustedProjectDirectory, lockFile)
      val sessionId = session.activate(campaignProjectPath, expectedTargets, lockFile)
      ownsCampaign = true

      // The ownership lock comes first: a rejected concurrent invocation must not
      // invalidate evidence belonging to the campaign that actually owns this checkout.
      // Once owned, mark this attempt before touching optional transition state. Keep
      // the last successful receipt; the running sentinel makes it ineligible as
      // evidence that this newer campaign completed.
      BaselineFiles.requireRegularFileOrMissing(trustedProjectDirectory, receiptFile)
      BaselineFiles.requireRegularFileOrMissing(trustedProjectDirectory, runningFile)
      BaselineFiles.writeAtomically(trustedProjectDirectory, runningFile, "starting\t$sessionId\n")

      val appliedPluginSha256 = expectedPluginSha256.get()
      HardeningPluginIdentityGuard.requireUnchanged(
          pluginCode,
          appliedPluginSha256,
          localRepoArtifactPath.get(),
          localRepoArtifactSha256.get(),
          "fuzzAll before campaign",
      )

      // buildDirectory is configurable and may intentionally live outside the checkout.
      // Confine legacy cleanup to that exact configured root instead of projectDirectory.
      BaselineFiles.requireDirectoryOrMissing(legacyBuildDirectoryFile)
      listOf(legacyReceiptFile, legacyRunningFile).forEach { file ->
        BaselineFiles.requireRegularFileOrMissing(legacyBuildDirectoryFile, file)
      }
      BaselineFiles.deleteIfExists(legacyReceiptFile)
      BaselineFiles.deleteIfExists(legacyRunningFile)

      if (excludedTaskNames.isNotEmpty()) {
        throw IllegalStateException(
            "fuzzAll requires its complete task graph; remove task exclusion(s): " +
                excludedTaskNames.joinToString(", ") { excluded -> "-x $excluded" })
      }
      val rawTime = maxTime.get()
      val seconds = rawTime.toLongOrNull()
      if (!Regex("[1-9][0-9]*").matches(rawTime) ||
          seconds == null || seconds > Int.MAX_VALUE) {
        throw IllegalStateException(
            "-PmaxFuzzTime must be positive whole seconds without leading zeros, up to " +
                "${Int.MAX_VALUE}; was '$rawTime' (0 is libFuzzer's run-forever sentinel)")
      }
      val rawParallelism = parallelism.get()
      val parallelTargets = rawParallelism.toLongOrNull()
      if (!Regex("[1-9][0-9]*").matches(rawParallelism) ||
          parallelTargets == null || parallelTargets > Int.MAX_VALUE) {
        throw IllegalStateException(
            "-PmaxParallelFuzzTargets must be positive whole targets without leading zeros, up to " +
                "${Int.MAX_VALUE}; was '$rawParallelism'")
      }
      BaselineFiles.writeAtomically(
          trustedProjectDirectory, runningFile, "session\t$sessionId\n")
      logger.lifecycle(
          "fuzzAll: started ${names.get().size} target(s), up to $parallelTargets concurrently")
    } catch (failure: Exception) {
      val reason = failure.message ?: failure::class.java.simpleName
      session.refuse(campaignProjectPath, expectedTargets, reason)
      if (ownsCampaign) {
        try {
          BaselineFiles.preserveReceiptUnderIncompleteMarker(
              trustedProjectDirectory,
              receiptFile,
              runningFile,
              "refused\t${reason.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')}\n",
          )
        } catch (stateFailure: Exception) {
          failure.addSuppressed(stateFailure)
        }
      }
      throw GradleException("fuzzAll: $reason", failure)
    }
  }
}
val fuzzAllComplete = tasks.register("fuzzAllComplete") {
  description = "Internal to fuzzAll: publishes evidence after every target completes."
  val names = fuzzTargetNames
  val maxTime = maxFuzzTime
  val parallelism = maxParallelFuzzTargets
  val pluginSha256 = localFuzzPluginSha256
  val receiptProjectPath = project.path
  val receiptFileProvider = localFuzzReceiptFile
  val receiptRunning = localFuzzReceiptRunning
  val trustedProjectDirectory = layout.projectDirectory.asFile
  val pluginCode = localFuzzPluginCode
  val localRepoArtifactPath = hardeningLocalRepoArtifactPath
  val localRepoArtifactSha256 = hardeningExpectedLocalRepoArtifactSha256
  val fuzzSession = hardeningFuzzSession
  usesService(fuzzSession)
  doLast {
    val receiptFile = receiptFileProvider.asFile
    val runningFile = receiptRunning.asFile
    val session = fuzzSession.get()
    var previousReceipt: ByteArray? = null
    var receiptReplacementAttempted = false
    try {
    val appliedPluginSha256 = pluginSha256.get()
    try {
      HardeningPluginIdentityGuard.requireUnchanged(
          pluginCode,
          appliedPluginSha256,
          localRepoArtifactPath.get(),
          localRepoArtifactSha256.get(),
          "fuzzAll after campaign",
      )
    } catch (e: IllegalStateException) {
      throw GradleException(
          "${e.message}; refusing a mixed-plugin receipt", e)
    }
    val completed = try {
      session.requireCompleted(receiptProjectPath, names.get())
    } catch (failure: IllegalStateException) {
      throw GradleException("fuzzAll: ${failure.message}; refusing to write a receipt", failure)
    }
    val totalExecutions = try {
      completed.totalExecutions
    } catch (failure: IllegalStateException) {
      throw GradleException("fuzzAll: ${failure.message}; refusing to write a receipt", failure)
    }
    val receipt = buildString {
      appendLine("schema\t4")
      appendLine("project\t$receiptProjectPath")
      appendLine("pluginSha256\t$appliedPluginSha256")
      appendLine("maxFuzzTimeSeconds\t${maxTime.get()}")
      appendLine("maxParallelTargets\t${parallelism.get()}")
      appendLine("totalExecutions\t$totalExecutions")
      names.get().sorted().forEach { target ->
        appendLine(
            "target\tfuzz${target.replaceFirstChar(Char::uppercase)}\t" +
                completed.executionsByTarget.getValue(target))
      }
    }
    BaselineFiles.requireRegularFileOrMissing(trustedProjectDirectory, runningFile)
    val expectedSentinel = "session\t${completed.sessionId}\n"
    if (!runningFile.isFile || runningFile.readText() != expectedSentinel) {
      throw GradleException(
          "fuzzAll: campaign ownership sentinel changed before receipt publication")
    }
    previousReceipt = BaselineFiles.readRegularFileSnapshot(
        trustedProjectDirectory, receiptFile)
    receiptReplacementAttempted = true
    BaselineFiles.writeAtomically(trustedProjectDirectory, receiptFile, receipt)
    // Publish while the sentinel still exists, then clear it. Interruption can leave
    // receipt+sentinel, which is explicitly not a completed current campaign.
    BaselineFiles.requireRegularFileOrMissing(trustedProjectDirectory, runningFile)
    if (!runningFile.isFile || runningFile.readText() != expectedSentinel) {
      throw GradleException(
          "fuzzAll: campaign ownership sentinel changed during receipt publication")
    }
    BaselineFiles.deleteIfExists(runningFile)
    logger.lifecycle("fuzzAll: ${names.get().size} local target(s) completed; receipt: $receiptFile")
    } catch (failure: Exception) {
      try {
        if (session.ownsCampaign(receiptProjectPath)) {
          val reason = (failure.message ?: failure::class.java.simpleName)
              .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
          if (receiptReplacementAttempted) {
            BaselineFiles.restoreReceiptSnapshotUnderIncompleteMarker(
                trustedProjectDirectory,
                receiptFile,
                runningFile,
                "refused\t$reason\n",
                previousReceipt,
            )
          } else {
            BaselineFiles.preserveReceiptUnderIncompleteMarker(
                trustedProjectDirectory,
                receiptFile,
                runningFile,
                "refused\t$reason\n",
            )
          }
        }
      } catch (stateFailure: Exception) {
        failure.addSuppressed(stateFailure)
      }
      throw failure
    }
  }
}
fuzzAll.configure { finalizedBy(fuzzAllComplete) }
validateFuzzBudget.configure { mustRunAfter(fuzzAll) }
hardening.fuzz.all {
  fuzzTargetNames.add(name)
  hardeningHelpFuzzTargetNames.add(name)
  val fuzzTaskName = "fuzz" + name.replaceFirstChar(Char::uppercase)
  fuzzAllComplete.configure { dependsOn(fuzzTaskName) }
}

// Compatibility task for consumers that still mention the old workflow check. It no
// longer inspects `.github/workflows/fuzz.yml`: scheduled GitHub fuzzing is optional,
// and `fuzzAll` is the non-drifting local replacement.
val fuzzWorkflowInSync = tasks.register("fuzzWorkflowInSync") {
  group = "verification"
  description = "Deprecated compatibility task; scheduled fuzz workflows are optional. Use fuzzAll locally."
  val localFuzzAllTaskPath = qualifiedHardeningTaskPath(project.path, "fuzzAll")
  doLast {
    logger.lifecycle(
        "fuzzWorkflowInSync: scheduled fuzz workflows are optional; run " +
            "$localFuzzAllTaskPath locally")
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
              "declineExclusionAudit with the measured reason/correctness owner. Declines are " +
              "suite-local; overlapping exclusions are attributed to the first matching glob in list order.")
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
  hardeningHelpSuiteNames.add(suite.name)
  suite.mutators.convention("STRONGER")
  suite.threads.convention(4)
  suite.minionJvmArgs.convention(emptyList())
  // PIT's own defaults; see MutationSuite.timeoutFactor for tuning guidance
  suite.timeoutFactor.convention(1.25)
  suite.timeoutConst.convention(4000L)
  suite.excludedClasses.convention(emptyList())
  suite.excludedTestClasses.convention(emptyMap())
  // The one funnel every test-exclusion glob passes through on its way to both the
  // command line and the evidence, and therefore where the reason is enforced. It
  // has to be enforced somewhere that fails: the advisory log never fails a build
  // by design, so a reason checked only there is not required, it is suggested —
  // and a removal nobody argued is indistinguishable from a glob that reached one
  // class further than its author meant.
  //
  // PIT ORs the exclusions, so their order changes nothing about what runs; sorting
  // makes the command line and the evidence configuration text depend on the set
  // alone rather than on registration order.
  val excludedTestGlobs = suite.excludedTestClasses.map { records ->
    val unargued = records.filterValues(String::isBlank).keys.sorted()
    require(unargued.isEmpty()) {
      "pitest '${suite.name}': ${unargued.size} excludeTestClass record(s) carry no reason:\n" +
          unargued.joinToString("\n") { "  $it" } + "\n" +
          "Kills come only from targetTests, so a removal narrows what can kill a mutant and " +
          "leaves nothing in the report to say why. Say what the class is and why mutation " +
          "analysis must not run it, or drop the record."
    }
    // Checked here, not only where the command line is built: the evidence spec
    // tasks hash this configuration without ever assembling a command, and a glob
    // carrying a comma renders into the canonical text indistinguishably from two
    // records spelled separately.
    records.keys.sorted().onEach(HardeningNames::requireTestExclusionGlob)
  }
  // targetTests has no records to funnel through, but it shares the encoding the
  // exclusions can forge, and the two collide as a pair: a newline in either lets
  // one suite's configuration render as another's. Validated once here so the task
  // and the evidence spec below read the same checked value.
  val validatedTargetTests = suite.targetTests.map {
    HardeningNames.requireSingleLineValue("targetTests", it)
  }
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
  // mutant must be killed with a test or knowingly accepted with a documented
  // reason. Existing records use the additive 'pitest<Suite>BaselineUnion'; a
  // first seed or separately reviewed full rewrite uses BaselineUpdate.
  val pitestTaskName = "pitest" + suite.name.replaceFirstChar(Char::uppercase)
  val suiteName = suite.name
  val mutationScopeProperty = providers.gradleProperty(HardeningOptionNames.MUTATE_ONLY)
  val strictTimeoutAuditRequested = providers
      .gradleProperty(HardeningOptionNames.STRICT_TIMEOUT_AUDIT)
      .map { true }
      .orElse(false)
  val timeoutAuditPreflight = tasks.register<TimeoutAuditPreflightTask>(
      "${pitestTaskName}TimeoutAuditPreflight") {
    description = "Internal: validates '$suiteName' committed timeout metadata before a strict PIT run."
    this.suiteName.set(suiteName)
    hardeningProjectPath.set(project.path)
    strictRequested.set(strictTimeoutAuditRequested)
    projectDirectory.set(layout.projectDirectory)
    timeoutsFile.set(layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv"))
    readmeFile.set(layout.projectDirectory.file("config/pitest/README.md"))
    certificationSession.set(hardeningCertificationSession)
    operationSession.set(hardeningOperationSession)
    usesService(hardeningCertificationSession)
    usesService(hardeningOperationSession)
    mustRunAfter(hardeningCertifyPreflight)
  }
  hardeningCertifyTimeoutAuditPreflight.configure { dependsOn(timeoutAuditPreflight) }
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
  val evidencePluginCode = hardeningImplementationCode
  val defaultPitestJavaLauncher = javaToolchains.launcherFor(java.toolchain)
  // Hoisted so the two helpers below read only locals. Both run from inside task
  // actions and argument providers, and a lambda that touches a script-level
  // declaration — the 'hardening' extension, the 'pitest' configuration accessor,
  // 'project' — captures the precompiled script object, which the configuration
  // cache cannot serialize. That failure is not scoped to the offending task: it
  // takes the whole PIT surface down in any consumer with the cache on, which is
  // every consumer whose 'check' stores an entry.
  val evidencePitestVersion = hardening.pitestVersion
  val evidenceJunitPluginVersion = hardening.pitestJunit5PluginVersion
  val evidenceArcMutateBaseVersion = hardening.arcmutateBaseVersion
  val evidenceMutationRelease = hardening.mutationBytecodeRelease
  val evidenceRecompileExcludes = hardening.recompileExcludes
  val evidenceProjectPath = project.path
  val evidencePitestTaskPath = qualifiedHardeningTaskPath(evidenceProjectPath, pitestTaskName)
  val evidenceBaselinePruneTaskPath = "${evidencePitestTaskPath}BaselinePrune"
  val evidenceBaselineRebaseTaskPath = "${evidencePitestTaskPath}BaselineRebase"
  val evidenceBaselineRetagTaskPath = "${evidencePitestTaskPath}BaselineRetag"
  val evidenceBaselineUnionTaskPath = "${evidencePitestTaskPath}BaselineUnion"
  val evidenceBaselineUpdateTaskPath = "${evidencePitestTaskPath}BaselineUpdate"
  val evidenceTimeoutAuditInitTaskPath = "${evidencePitestTaskPath}TimeoutAuditInit"
  val explicitlyRequestedTasks = gradle.startParameter.taskNames.toSet()
  fun workflowTaskRequested(taskName: String): Boolean {
    val qualifiedPath = qualifiedHardeningTaskPath(evidenceProjectPath, taskName)
    return explicitlyRequestedTasks.any { requested ->
      requested == taskName ||
          (if (requested.startsWith(':')) requested else ":$requested") == qualifiedPath
    }
  }
  val convergenceWorkflowRequested =
      workflowTaskRequested("pitestConverge") ||
          workflowTaskRequested("pitestConvergeSnapshot")
  val modeSnapshotWorkflowRequested = workflowTaskRequested("pitestModeSnapshot")
  val requestedModeLabel = pitestModeProperty.orNull ?: "<label>"
  val evidenceCertificationSession = hardeningCertificationSession
  val evidenceReportFile = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
  val evidenceManifestFile = layout.buildDirectory.file("reports/pitest/$suiteName/.evidence.tsv")
  val scopedEvidenceReportFile =
      layout.buildDirectory.file("reports/pitest-scoped/$suiteName/mutations.csv")
  val scopedEvidenceManifestFile =
      layout.buildDirectory.file("reports/pitest-scoped/$suiteName/.evidence.tsv")

  val evidenceMode = pitestModeProperty
  pitestModeSnapshot.configure {
    // Snapshot and verify may reuse an existing report without running PIT first;
    // schedule every project artifact they fingerprint before their actions execute.
    // hardeningCertify already inherits this edge through qualityGate -> pitest*.
    dependsOn(evidenceClasspathFiles)
    doFirst {
      val label = evidenceMode.orNull ?: return@doFirst
      if (!HardeningNames.isSafeName(label)) return@doFirst
      val requestedMutationScope = mutationScopeProperty.orNull?.trim()?.also { scope ->
        if (scope.isEmpty()) {
          throw GradleException(
              "-PmutateOnly requires a nonblank class glob; omit the property for a " +
                  "full-population run")
        }
      }
      val scoped = requestedMutationScope != null
      val report = (if (scoped) scopedEvidenceReportFile else evidenceReportFile).get().asFile
      val reportDir = report.parentFile
      val manifest =
          (if (scoped) scopedEvidenceManifestFile else evidenceManifestFile).get().asFile
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
      try {
        evidenceCertificationSession.get().requireCurrentEvidence(
            evidenceProjectPath, suiteName, recorded)
      } catch (e: IllegalStateException) {
        throw GradleException(
            "pitestModeSnapshot: '$suiteName' report/evidence pair was not validated against the " +
                "current build — ${e.message}", e)
      }
    }
  }
  // computed once and shared by every advisory-recording task in the suite: the
  // end-of-build summary groups findings by this string, so two drifting copies
  // would split one suite's findings across two scope headings
  val suiteAdvisoryScope = (if (project.path == ":") "" else "${project.path} ") + "pitest '$suiteName'"
  val verify = tasks.register<PitestVerifyTask>("${pitestTaskName}Verify") {
    group = "verification"
    description = "Checks the '$suiteName' PIT report against its ratchet; scoped reports remain read-only diagnostics."
    dependsOn(evidenceClasspathFiles)
    val fullCsvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val scopedCsvProvider =
        layout.buildDirectory.file("reports/pitest-scoped/$suiteName/mutations.csv")
    val fullXmlProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.xml")
    val scopedXmlProvider =
        layout.buildDirectory.file("reports/pitest-scoped/$suiteName/mutations.xml")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val timeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val listUnkilled = providers.gradleProperty(HardeningOptionNames.LIST_UNKILLED).isPresent
    val statusStashFile = layout.projectDirectory.file(".pitest-history/$suiteName.statuses").asFile
    val timeoutQuietFile = layout.projectDirectory.file(".pitest-history/$suiteName.timeout-quiet").asFile
    val prunePreviewFile =
        layout.projectDirectory.file(".pitest-history/$suiteName.prune-previews").asFile
    val toolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val toolchainRecordFile =
        layout.projectDirectory.file("config/pitest/$suiteName-pitest-toolchain.tsv").asFile
    val pitToolVersion = hardening.pitestVersion
    // captured locally so the doLast lambda does not hold the script instance
    val advisoryLog = hardeningAdvisoryLog
    val certificationSession = hardeningCertificationSession
    val operationSession = hardeningOperationSession
    val verifyExcludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
    val advisoryScope = suiteAdvisoryScope
    usesService(advisoryLog)
    usesService(certificationSession)
    usesService(operationSession)
    // Resolved at configuration time so the scaffolding check below can ask whether a
    // mutated class is one of this project's own test sources.
    val testSourceDirs = sourceSets.test.get().java.srcDirs
    doLast {
      val writeOperation = operationSession.get().suiteOperation(evidenceProjectPath, suiteName)
      val rebase = writeOperation == BaselineWriteOperation.REBASE
      val update = writeOperation == BaselineWriteOperation.UPDATE
      val union = writeOperation == BaselineWriteOperation.UNION
      val retag = writeOperation == BaselineWriteOperation.RETAG
      val prune = writeOperation == BaselineWriteOperation.PRUNE
      val initTimeoutAudit = writeOperation == BaselineWriteOperation.INIT_TIMEOUT_AUDIT
      val writingRecord = writeOperation != BaselineWriteOperation.CHECK
      val canonicalWriteOperation = operationSession.get().suiteOperation(
          evidenceProjectPath, suiteName)
      if (writingRecord && verifyExcludedTaskNames.isNotEmpty()) {
        throw GradleException(
            "pitest '$suiteName': a baseline write cannot prove its complete task graph with " +
                "task exclusion(s): " +
                verifyExcludedTaskNames.joinToString { "-x $it" })
      }
      val certificationActive = certificationSession.get().isActive(evidenceProjectPath)
      val strictTimeoutAudit = strictTimeoutAuditRequested.get() || certificationActive || rebase
      val requestedMutationScope = mutationScopeProperty.orNull?.trim()?.also { scope ->
        if (scope.isEmpty()) {
          throw GradleException(
              "-PmutateOnly requires a nonblank class glob; omit the property for a " +
                  "full-population run")
        }
      }
      val scoped = requestedMutationScope != null
      val csv = (if (scoped) scopedCsvProvider else fullCsvProvider).get().asFile
      val incompleteReportClosure =
          "\n  Closure for a failed attempt: after resolving the preceding failure, a later " +
              "successful, clean rerun of the intended workflow is sufficient closure and replaces " +
              "the incomplete generated report. That report creates no persistent mutation-record " +
              "debt, and the successful rerun does not diagnose the earlier failure's cause."
      val retryGuidance = when {
        certificationActive -> certificationRetryGuidance(evidenceProjectPath)
        convergenceWorkflowRequested -> "\n  " + workflowRetryGuidance(
            retryAction = "run " +
                "${qualifiedHardeningTaskPath(evidenceProjectPath, "pitestConverge")} from the start",
        )
        modeSnapshotWorkflowRequested -> "\n  " + workflowRetryGuidance(
            retryAction = "re-run every suite in mode '$requestedModeLabel', then run " +
                "${qualifiedHardeningTaskPath(evidenceProjectPath, "pitestModeSnapshot")} " +
                "-PpitestMode=$requestedModeLabel",
        )
        else -> "\n  Retry: run $evidencePitestTaskPath in a new Gradle invocation."
      }
      try {
      val invalidExecutionClosure = knownInvalidExecutionClosure(
          if (!certificationActive && !convergenceWorkflowRequested &&
              !modeSnapshotWorkflowRequested) {
            "\n  Retry: in a new Gradle invocation, run $evidencePitestTaskPath " +
                "-PnoMutationHistory without -PmutateOnly."
          } else {
            retryGuidance
          }
      )
      listOf(
          baselineFile,
          readmeFile,
          timeoutsFile,
          toolVersionFile,
          toolchainRecordFile,
          statusStashFile,
          timeoutQuietFile,
          prunePreviewFile,
      ).forEach { BaselineFiles.requireRegularFileOrMissing(evidenceProjectDir, it) }
      // A consumer may intentionally relocate buildDir outside the checkout. Refuse
      // a linked/non-regular report leaf without applying the committed-record root
      // boundary to generated output.
      BaselineFiles.requireRegularFileOrMissing(csv)
      val committedRecordExisted = baselineFile.isFile || timeoutsFile.isFile
      // The '.running' sentinel is written before PIT starts and cleared only
      // after a clean exit, so a report left by a crashed or interrupted run —
      // PIT writes the CSV incrementally, so a partial file looks complete —
      // is refused as evidence instead of read as a smaller population. Without
      // it, this verify runs as the failed task's finalizer and a same-invocation
      // BaselinePrune rewrites the baseline from whatever fraction of
      // the population PIT reached before dying.
      if (csv.parentFile.resolve(".running").isFile && csv.exists()) {
        throw GradleException(
            "pitest '$suiteName': the report at ${csv.parentFile} was left by an interrupted or " +
                "failed run.\n" +
                "  Evidence: a partial population is not evidence for the ratchet or any " +
                "writer task." + incompleteReportClosure + retryGuidance
        )
      }
      if (!csv.exists()) {
        // As a finalizer this also fires when the pitest task itself just failed,
        // in which case the missing report is a symptom — don't let this message
        // bury the real error printed above it. A PIT MINION_DIED / coverage
        // socket-timeout failure is a known transient: re-run the suite.
        throw GradleException(
            "pitest '$suiteName': no PIT report at $csv.\n" +
                "  Cause: $evidencePitestTaskPath either has not run or failed before writing one; " +
                "its original error is above this message.\n" +
                "  If the preceding output shows a failed execution such as MINION_DIED, " +
                "use the closure condition below." + incompleteReportClosure + retryGuidance +
                "\n  Diagnostics: if the output above lacks the cause, inspect " +
                "~/.gradle/daemon/<version>/daemon-<pid>.out.log for the minion's stack trace."
        )
      }
      val completedEvidenceFile = csv.parentFile.resolve(".evidence.tsv")
      BaselineFiles.requireRegularFileOrMissing(completedEvidenceFile)
      var verifiedEvidence: PitestEvidence? = null
      if (!completedEvidenceFile.isFile) {
        val message = "pitest '$suiteName': report has no completed-run evidence manifest at " +
            "$completedEvidenceFile — it may predate this plugin or be a detached/stale report; " +
            "it must be bound to the current code and configuration."
        // N-1 migration path: old reports have no manifest. A writer may consume one
        // only when every visible source/config input predates the report; this keeps
        // existing checked-out reports usable for one upgrade while refusing the
        // common stale-report footgun immediately. Certification never accepts the
        // heuristic — a fresh run earns a real manifest.
        val newerInputs = evidenceSourceFiles.files.filter { it.isFile && it.lastModified() > csv.lastModified() }
        if (certificationActive || (writingRecord && newerInputs.isNotEmpty())) {
          throw GradleException(
              message + (if (newerInputs.isEmpty()) "" else
                  "\n  newer input(s):\n" + newerInputs.sortedBy { it.path }
                      .joinToString("\n") { "  $it" }) + retryGuidance)
        }
        logger.warn(message + retryGuidance)
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
                  "${e.message}" + retryGuidance, e)
        }
        try {
          certificationSession.get().requireCurrentEvidence(
              evidenceProjectPath, suiteName, recordedEvidence)
        } catch (e: IllegalStateException) {
          throw GradleException(
              "pitest '$suiteName': completed report evidence no longer matches the current build — " +
                  "a stale report cannot verify or rewrite mutation state: " + e.message +
                  retryGuidance, e)
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
      if (canonicalWriteOperation != BaselineWriteOperation.CHECK) {
        try {
          certificationSession.get().requireCompletedAttempt(
              evidenceProjectPath, suiteName, verifiedEvidence)
        } catch (e: IllegalStateException) {
          throw GradleException(
              "pitest '$suiteName': ${canonicalWriteOperation.name.lowercase()} requires a fresh PIT " +
              "observation completed in this Gradle invocation — ${e.message}", e)
        }
      }
      // The dependency validator closes the normal stale-report path, but Gradle
      // does not promise that a dependency is the action immediately preceding this
      // one. Re-read the complete snapshot at the write boundary: another task can
      // legitimately be ordered after EvidenceValidate and before Verify, and a
      // record writer must not bless its changed report/source/classes/toolchain.
      // The report is read once; the exact bytes hashed here are the bytes parsed
      // below, so even a concurrent replacement cannot split the two observations.
      val finalReportLines = if (canonicalWriteOperation != BaselineWriteOperation.CHECK) {
        val evidence = verifiedEvidence ?: throw GradleException(
            "pitest '$suiteName': a named baseline writer has no completed evidence to recapture")
        val reportBytes = csv.readBytes()
        val reportDir = csv.parentFile
        val finalScope = reportDir.resolve(".scoped").takeIf { it.isFile }
            ?.readText()?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }
        val finalHistoryAssisted = reportDir.resolve(".history-assisted").isFile
        val finalSnapshot = try {
          (this as PitestVerifyTask).finalEvidence.captureFinal(
              evidence,
              PitestEvidence.sha256(reportBytes),
              finalScope,
              finalHistoryAssisted,
          )
        } catch (e: Exception) {
          throw GradleException(
              "pitest '$suiteName': could not recapture final writer evidence — ${e.message}", e)
        }
        val differences = evidence.differences(finalSnapshot)
        if (differences.isNotEmpty()) {
          throw GradleException(
              "pitest '$suiteName': inputs changed after evidence validation — refusing to " +
                  "rewrite mutation records from a stale observation:\n" +
                  differences.joinToString("\n") { "  $it" })
        }
        reportBytes.inputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() }
      } else {
        csv.readLines()
      }
      // One interpretation of the report: every column split, key derivation, and
      // status semantic lives on Mutant/MutantStatus. Parse before committed-record
      // provenance so a trustworthy completed report can still name timeout-audit
      // findings when that independent committed metadata is torn or malformed.
      val rows = Mutant.parseReport(finalReportLines, invalidExecutionClosure)
      val timedOutByAuditKey = rows
          .filter { it.status == MutantStatus.TIMED_OUT }
          .groupBy { it.coordinate }
      val timeoutCandidates = TimeoutAudit.timeoutCandidates(rows)
      val configuredPitestVersion = (this as PitestVerifyTask).finalEvidence.pitestVersion.get()
      val configuredTimeoutFactor = (this as PitestVerifyTask).finalEvidence.timeoutFactor.get()
      val configuredTimeoutConst = (this as PitestVerifyTask).finalEvidence.timeoutConst.get()
      val watchdogFormulaContext = TimeoutAudit.watchdogFormulaContext(
          configuredPitestVersion, configuredTimeoutFactor, configuredTimeoutConst)
      val historyAssistedReport = csv.parentFile.resolve(".history-assisted").isFile
      val historyDecisionCaveat = if (historyAssistedReport) {
        "\nThis [history] result is check-only. Run $evidencePitestTaskPath " +
            "-PnoMutationHistory " +
            "before changing any accepted-baseline or timeout-audit record."
      } else {
        ""
      }
      fun missingTimeoutAuditHint(): String =
          "pitest '$suiteName': no audited set covers " +
              "${TimeoutAudit.timeoutCandidateCount(timeoutCandidates)}:\n" +
              "  Evidence: One paste-ready draft row per line-less key follows; nested " +
              "'# observed' comments preserve line/status multiplicity.\n" +
              TimeoutAudit.timeoutCandidateDetail(timeoutCandidates, indent = "    ") + "\n" +
              "  Review: A timeout detects slowness, not wrongness, so the ratchet cannot see a " +
              "weakened covering assertion behind one. Timeout observations may not reproduce " +
              "on a later seeding run.\n" +
              "  Watchdog context: $watchdogFormulaContext\n" +
              "  Remedy: Run $evidenceTimeoutAuditInitTaskPath (seeds " +
              "config/pitest/${timeoutsFile.name} from this run), or paste the draft rows above. " +
              "Then replace cause:untriaged and write each member's structural argument in " +
              "config/pitest/README.md. The seeded state is intentionally uncertifiable." +
              historyDecisionCaveat
      fun missingTimeoutAuditProvenancePreview(): String =
          "pitest '$suiteName': no audited set covers " +
              "${TimeoutAudit.timeoutCandidateCount(timeoutCandidates)}, and committed mutation " +
              "provenance is invalid:\n" +
              "  Evidence: Triage-only draft rows follow, one per line-less key; nested " +
              "'# observed' comments preserve line/status multiplicity.\n" +
              TimeoutAudit.timeoutCandidateDetail(timeoutCandidates, indent = "    ") + "\n" +
              "  Review: The population is not bound to valid committed provenance.\n" +
              "  Watchdog context: $watchdogFormulaContext\n" +
              "  Remedy: Retain these candidates, repair or rebase provenance, and obtain a fresh " +
              "full observation. Do not seed, add, or classify them until that observation " +
              "confirms them." +
              historyDecisionCaveat
      fun unavailableTimeoutMembershipWarning(reason: String): String =
          "pitest '$suiteName': report-dependent timeout membership findings were not " +
              "evaluated before the committed-provenance refusal:\n" +
              "  Evidence: ${reason.replaceFirstChar { it.uppercase() }}.\n" +
              "  Remedy: Repair or rebase provenance, then run $evidencePitestTaskPath " +
              "without -PmutateOnly."
      fun warnTimeoutFindingsBeforeProvenanceRefusal() {
        val fullPopulation = !scoped &&
            verifiedEvidence?.scope == PitestEvidence.FULL_SCOPE
        if (!timeoutsFile.isFile) {
          if (fullPopulation && timedOutByAuditKey.isNotEmpty()) {
            logger.warn(missingTimeoutAuditProvenancePreview())
          } else if (timedOutByAuditKey.isNotEmpty()) {
            val unavailableBecause = if (verifiedEvidence == null) {
              "this report has no completed evidence manifest"
            } else {
              "this is a scoped mutation report"
            }
            logger.warn(unavailableTimeoutMembershipWarning(unavailableBecause))
          }
          return
        }
        val membership = TimeoutAudit.parse(timeoutsFile.readLines())
        TimeoutAudit.malformedWarning(suiteName, timeoutsFile.name, membership.malformed)
            ?.let { logger.warn(it) }
        // These are properties of committed bytes, independent of whether the
        // current population can be compared with them. Report all of them before
        // provenance refuses, just as Debt does.
        val staticCauseFindings = TimeoutAudit.causeFindings(membership, membership.members)
        if (staticCauseFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.causeFindingWarning(
              suiteName, timeoutsFile.name, staticCauseFindings))
        }
        val staticLineMetadataFindings =
            TimeoutAudit.lineMetadataFindings(membership, membership.members)
        if (staticLineMetadataFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.lineMetadataWarning(
              suiteName, timeoutsFile.name, staticLineMetadataFindings))
        }
        val staticUndocumented = TimeoutAudit.undocumentedCauses(membership.members) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (staticUndocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(suiteName, staticUndocumented))
        }
        if (!fullPopulation) {
          val unavailableBecause = if (verifiedEvidence == null) {
            "this report has no completed evidence manifest"
          } else {
            "this is a scoped mutation report"
          }
          logger.warn(unavailableTimeoutMembershipWarning(unavailableBecause))
          return
        }
        val findings = TimeoutAudit.reportFindings(rows, membership) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (findings.unaudited.isNotEmpty()) {
          logger.warn(TimeoutAudit.unauditedProvenancePreview(
              suiteName,
              timeoutsFile.name,
              findings.unaudited,
              configuredPitestVersion,
              configuredTimeoutFactor,
              configuredTimeoutConst,
              historyDecisionCaveat,
          ))
        }
        if (findings.staleMembers.isNotEmpty()) {
          logger.warn(TimeoutAudit.staleProvenancePreview(
              suiteName, findings.staleMembers, historyDecisionCaveat))
        }
      }
      fun refuseCommittedProvenance(message: String): Nothing {
        warnTimeoutFindingsBeforeProvenanceRefusal()
        throw GradleException(message)
      }
      val completedToolchainFile = csv.parentFile.resolve(".toolchain.tsv")
      BaselineFiles.requireRegularFileOrMissing(completedToolchainFile)
      val currentToolchain = when {
        verifiedEvidence == null -> null
        verifiedEvidence.mutationToolchainSha256 == PitestEvidence.LEGACY_MUTATION_TOOLCHAIN -> {
          logger.warn(
              "pitest '$suiteName': completed evidence predates portable mutation-toolchain identity; " +
                  "run $evidencePitestTaskPath before relying on this report")
          advisoryLog.get().record(advisoryScope, "completed evidence has legacy-unbound toolchain identity")
          null
        }
        !completedToolchainFile.isFile -> throw GradleException(
            "pitest '$suiteName': completed evidence names mutation toolchain " +
                "${verifiedEvidence.mutationToolchainSha256} but $completedToolchainFile is missing; " +
                "re-run $evidencePitestTaskPath")
        else -> {
          val parsed = try {
            MutationToolchainRecord.parse(completedToolchainFile.readText())
          } catch (e: IllegalArgumentException) {
            throw GradleException(
                "pitest '$suiteName': malformed completed mutation-toolchain record at " +
                    "$completedToolchainFile — ${e.message}; re-run $evidencePitestTaskPath", e)
          }
          if (parsed.identitySha256 != verifiedEvidence.mutationToolchainSha256) {
            throw GradleException(
                "pitest '$suiteName': completed mutation-toolchain record does not match its evidence " +
                    "manifest; re-run $evidencePitestTaskPath")
          }
          parsed
        }
      }

      // The mutant population is a function of the complete engine, not PIT's
      // version alone. Keep the N-1-readable version stamp, and bind the portable,
      // ordered artifact identity plus ArcMutate certificate in a strict sidecar.
      val currentPit = pitToolVersion.get()
      val committedProvenance = CommittedMutationProvenance.classify(
          committedRecordExisted,
          toolVersionFile.takeIf { it.isFile }?.readText(),
          toolchainRecordFile.takeIf { it.isFile }?.readText(),
      )
      val recordedPit = committedProvenance.pitVersion
      val recordedToolchain = committedProvenance.toolchain
      committedProvenance.malformedPitVersion?.let { detail ->
        if (!rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': malformed committed PIT-version stamp at " +
                  "$toolVersionFile — $detail; repair it with $evidenceBaselineRebaseTaskPath")
        }
        logger.warn(
            "pitest '$suiteName': BaselineRebase will replace malformed committed PIT-version " +
                "provenance after its fresh safe-superset observation — $detail")
      }
      committedProvenance.malformedToolchain?.let { detail ->
        if (!rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': malformed committed mutation-toolchain record at " +
                  "$toolchainRecordFile — $detail; repair it with " +
                  evidenceBaselineRebaseTaskPath)
        }
        logger.warn(
            "pitest '$suiteName': BaselineRebase will replace malformed committed " +
                "mutation-toolchain provenance after its fresh safe-superset observation — $detail")
      }
      if (committedProvenance.disagreement) {
        val disagreement =
            "pitest '$suiteName': committed provenance disagrees: ${toolVersionFile.name} says " +
                "PIT $recordedPit but ${toolchainRecordFile.name} says PIT " +
                "${checkNotNull(recordedToolchain).pitestVersion}"
        if (!rebase) {
          refuseCommittedProvenance(
              "$disagreement; run $evidenceBaselineRebaseTaskPath after review")
        }
        logger.warn("$disagreement; BaselineRebase will replace both after its fresh safe-superset observation")
      }
      if (committedProvenance.torn) {
        val torn =
            "pitest '$suiteName': committed mutation provenance is torn — exactly one of " +
                "${toolVersionFile.name} and ${toolchainRecordFile.name} exists. A complete record " +
                "written by a pre-sidecar release and an interrupted newer write are " +
                "indistinguishable under the current schema"
        if (!rebase) {
          refuseCommittedProvenance(
              "$torn; run $evidenceBaselineRebaseTaskPath to bind/repair this one-sided record.")
        }
        logger.warn("$torn; BaselineRebase will repair the pair after its fresh safe-superset observation")
      }

      fun legacyProvenanceFinding(message: String, summary: String) {
        logger.warn(message)
        advisoryLog.get().record(advisoryScope, summary)
      }
      if (committedProvenance.orphan) {
        val orphanMessage =
            "pitest '$suiteName': mutation-provenance sidecar(s) exist without an accepted or " +
                "timeout record; run $evidenceBaselineRebaseTaskPath to create a safely widened " +
                "record or retire the orphan provenance"
        if ((writingRecord || certificationActive) && !rebase) {
          refuseCommittedProvenance(orphanMessage)
        }
        if (!rebase) {
          legacyProvenanceFinding(orphanMessage, "orphan mutation-provenance sidecar(s)")
        }
      }
      if (committedProvenance.legacyUnbound) {
        legacyProvenanceFinding(
            "pitest '$suiteName': committed mutation record is legacy-unversioned; its PIT version is " +
                "unknown, so a population change cannot be attributed automatically. Review a history-free " +
                "$evidencePitestTaskPath -PnoMutationHistory observation and run " +
                "$evidenceBaselineRebaseTaskPath to bind it.",
            "committed record is legacy-unversioned")
        if (writingRecord && !rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': existing committed record has no PIT provenance; only " +
                  "$evidenceBaselineRebaseTaskPath may adopt it")
        }
      }
      if (committedProvenance.legacyUnbound) {
        legacyProvenanceFinding(
            "pitest '$suiteName': committed mutation record is legacy-toolchain-unbound; ArcMutate " +
                "licence/base or PIT plugin changes cannot be distinguished from code churn. Review a " +
                "history-free $evidencePitestTaskPath -PnoMutationHistory observation and run " +
                "$evidenceBaselineRebaseTaskPath to bind it.",
            "committed record is legacy-toolchain-unbound")
        if (writingRecord && !rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': existing committed record has no mutation-toolchain provenance; only " +
                  "$evidenceBaselineRebaseTaskPath may adopt it")
        }
      }
      if (recordedPit != null && recordedPit != currentPit) {
        if ((writingRecord || certificationActive) && !rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': the baseline record was written by PIT $recordedPit but this run used " +
                  "PIT $currentPit — refusing to rewrite the record across a tool bump, whose population " +
                  "churn would be indistinguishable from code churn. Run " +
                  "$evidenceBaselineRebaseTaskPath after reviewing a history-free " +
                  "$evidencePitestTaskPath -PnoMutationHistory observation; " +
                  "it preserves old evidence and adopts the new provenance only after a successful fresh run."
          )
        }
        if (!rebase) {
          val versionWarning = "pitest '$suiteName': baseline record written by PIT $recordedPit, this run " +
              "used PIT $currentPit — population differences may be the tool, not the code. Review a " +
              "history-free $evidencePitestTaskPath -PnoMutationHistory observation, then run " +
              "$evidenceBaselineRebaseTaskPath; other record-writing tasks refuse the mismatch."
          logger.warn(versionWarning)
          advisoryLog.get().record(advisoryScope, "baseline written by PIT $recordedPit, ran $currentPit")
        }
      }
      if (recordedToolchain != null && currentToolchain != null &&
          recordedToolchain.identitySha256 != currentToolchain.identitySha256) {
        if ((writingRecord || certificationActive) && !rebase) {
          refuseCommittedProvenance(
              "pitest '$suiteName': committed mutation toolchain " +
                  "${recordedToolchain.identitySha256} differs from this run's " +
                  "${currentToolchain.identitySha256}. Review a history-free " +
                  "$evidencePitestTaskPath -PnoMutationHistory population, then run " +
                  "$evidenceBaselineRebaseTaskPath; it preserves old evidence and stamps only after a " +
                  "successful fresh run.")
        }
        if (!rebase) {
          logger.warn(
              "pitest '$suiteName': mutation toolchain changed since the committed record — population " +
                  "differences may be ArcMutate/PIT tooling, not code. Review a history-free " +
                  "$evidencePitestTaskPath -PnoMutationHistory observation, then run " +
                  "$evidenceBaselineRebaseTaskPath; other writers refuse.")
          advisoryLog.get().record(advisoryScope, "committed mutation toolchain differs from this run")
        }
      }

      fun warnIgnoredMutationRecords(recordFiles: Iterable<File>) {
        val ignored = (this as PitestVerifyTask).ignoredUntrackedRecordFiles(
            evidenceProjectDir,
            recordFiles,
        )
        if (ignored.isNotEmpty()) {
          logger.warn(
              "pitest baseline '$suiteName': wrote mutation-record input(s) that Git ignores; " +
                  "ordinary git status can hide them, and a clean hardeningCertify will refuse until " +
                  "they are tracked or the ignore rule is narrowed. Review the matching rules with " +
                  "'git check-ignore -v' (or force-add the reviewed files intentionally), then commit:\n" +
                  ignored.joinToString("\n") { "  $it" })
        }
      }

      // This helper is used only when no baseline bytes change. Transitions below
      // commit sidecars and record content through one rollback-capable plan, ordered
      // so process interruption can leave only a state readers refuse closed.
      fun stampProvenance(force: Boolean = false) {
        val toolchain = currentToolchain ?: throw GradleException(
            "pitest '$suiteName': current completed mutation-toolchain evidence is required to stamp records")
        if (force || !committedRecordExisted) {
          BaselineFiles.writeAllAtomically(evidenceProjectDir, listOf(
            BaselineFiles.Write(toolchainRecordFile, toolchain.render()),
            BaselineFiles.Write(toolVersionFile, currentPit + "\n"),
          ))
          warnIgnoredMutationRecords(listOf(toolchainRecordFile, toolVersionFile))
        }
      }
      fun stampOrRetireProvenance() {
        if (timeoutsFile.isFile) {
          stampProvenance(force = rebase)
        } else {
          val removedFiles = buildList {
            if (toolVersionFile.exists()) add(toolVersionFile.name)
            if (toolchainRecordFile.exists()) add(toolchainRecordFile.name)
          }
          BaselineFiles.writeAllAtomically(evidenceProjectDir, listOf(
            BaselineFiles.Write(toolVersionFile, null),
            BaselineFiles.Write(toolchainRecordFile, null),
          ))
          if (removedFiles.isNotEmpty()) {
            logger.lifecycle(
                "pitest baseline '$suiteName': removed orphan mutation provenance: " +
                    removedFiles.joinToString())
          }
        }
      }
      val byStatus = rows.groupingBy { it.rawStatus }.eachCount()
      val total = rows.size
      // TIMED_OUT counts as detected, the same as PIT's own summary — a mutant that
      // hangs the suite was caught. Reported separately because watchdog detection
      // establishes neither the mutant's cause nor stable physical identity under a
      // line-less key; it can also change with execution conditions.
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
        if (timedOut > 0) {
          add(
              if (scoped) {
                "$timedOut timed out (scoped selected population; not comparable to the suite audit)"
              } else {
                "$timedOut timed out (watchdog detection; not a cause diagnosis)"
              }
          )
        }
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
      logger.lifecycle(
          "pitest '$suiteName': $detected/$total detected ($percent%)" +
              (if (split.isEmpty()) "" else " — ${split.joinToString(", ")}") +
              (if (historyAssistedReport) " [history]" else "")
      )
      if (strictTimeoutAudit && historyAssistedReport) {
        throw GradleException(
            "pitest '$suiteName': strict timeout audit refuses a history-assisted report — cached " +
                "statuses cannot prove the current timeout set. Run $evidencePitestTaskPath " +
                "-PstrictTimeoutAudit; explicit strict mode disables history automatically and " +
                "repeats the audit against fresh evidence.")
      }
      if (historyAssistedReport) {
        logger.warn(
            "pitest '$suiteName': history-assisted results are a read-only preview, not a fresh " +
                "observation. Do not add, remove, or relabel accepted-baseline or " +
                "timeout-audit rows from this report; run $evidencePitestTaskPath -PnoMutationHistory " +
                "first. Run-to-run status, timeout-retirement, and prune-preview stashes stay " +
                "unchanged. This read-only " +
                "preview notice is not an advisory finding.")
      }

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
        val xml = (if (scoped) scopedXmlProvider else fullXmlProvider).get().asFile
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
        if (requestedMutationScope != null && scope != requestedMutationScope) {
          throw GradleException(
              "pitest '$suiteName': requested -PmutateOnly=$requestedMutationScope, but the " +
                  "last scoped report was produced with -PmutateOnly=$scope. Re-run " +
                  "$evidencePitestTaskPath with -PmutateOnly=$requestedMutationScope before verifying it.")
        }
        // Every writer included: the early return below already keeps them from
        // touching anything, but silently no-opping a requested rewrite (or seeding
        // an audited set from a hand-picked subset's timeouts) reads as work that
        // happened. Key this to the shared operation state so a newly registered
        // writer cannot bypass the fail-closed guard by omission.
        if (writingRecord) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot refresh the baseline or seed the timeout audit. " +
                  "Re-run $evidencePitestTaskPath without -PmutateOnly first."
          )
        }
        // Certification paths are refused for the same reason in the other direction:
        // the checks they strengthen are skipped entirely on a scoped report, so a
        // green run would read as a certification of the suite when nothing was
        // certified at all. (-PnoDriftTolerance used to sit beside this flag; the
        // line-less key retired it — there is no drift tolerance left to disable.)
        if (strictTimeoutAudit) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot be certified, and the certifying checks are skipped on a scoped " +
                  "report. Re-run $evidencePitestTaskPath without -PmutateOnly first."
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
      // record-writing tasks may not: a baseline refresh or an audit seed written
      // from reused results certifies numbers this run never earned.
      if (historyAssistedReport && writingRecord) {
        throw GradleException(
            "pitest '$suiteName': the report is arcmutate-history-assisted — reused results are " +
                "not observation, and a baseline refresh or audit seed needs a full run. " +
                "Re-run $evidencePitestTaskPath with -PnoMutationHistory first."
        )
      }
      // A baseline row may carry a trailing '# note' ('# untriaged' is the conventional
      // label for seeded debt; refreshes seed it on every new row) and a trailing
      // '# line' tag (metadata for triage and the line-drift advisory, never identity).
      // Notes are stripped for comparison, preserved across writer operations, and
      // counted per label — so triage state lives on the row it describes and stays a
      // number the build reports, not prose that drifts. Rows are parsed as an ordered
      // LIST of (key, note, lines): duplicate keys are sibling mutants and each keeps
      // its own note, which a note map keyed by row text used to collapse.
      // BaselineDocument validates the explicit schema marker before any reader can
      // interpret rows. It also retains comments and blanks as ordered entries, so
      // every writer below replaces row slots without losing non-row evidence.
      // BaselineNotes still parses both N-1 and current row spellings.
      val baselineExisted = baselineFile.isFile
      val baselineDocument = BaselineDocument.parse(
          if (baselineExisted) baselineFile.readText() else "")
      val baselineRowLines = baselineDocument.rowEntries.map { it.raw }
      val malformedBaselineRows = baselineDocument.malformedRows.map { it.raw }
      if (malformedBaselineRows.isNotEmpty()) {
        logger.warn(
            "pitest baseline '$suiteName': ${malformedBaselineRows.size} malformed row(s) in " +
                "${baselineFile.name} — expected 'class,method,mutator,STATUS [# note] [# line N]' " +
                "(legacy five-field rows still parse); a malformed row matches no mutant, reads as " +
                "no accepted mutant, and blocks every baseline rewrite:\n" +
                malformedBaselineRows.joinToString("\n") { "  $it" }
        )
        // recorded only when it stays advisory: on a refresh run the same finding
        // becomes the failure below, and the end-of-build summary's "none failed
        // the build" must stay true (the strict-flag sites' own rule)
        if (!writingRecord || initTimeoutAudit) {
          advisoryLog.get().record(advisoryScope, "${malformedBaselineRows.size} malformed baseline row(s)")
        } else {
          throw GradleException(
              "pitest baseline '$suiteName': ${baselineFile.name} carries " +
                  "${malformedBaselineRows.size} malformed row(s) (listed above) — a refresh cannot " +
                  "interpret them safely. Fix the row shape first."
          )
        }
      }
      val invalidLineMetadataRows = baselineDocument.invalidLineMetadataRows
      if (invalidLineMetadataRows.isNotEmpty()) {
        val details = invalidLineMetadataRows.joinToString("\n") { row ->
          "  line ${row.lineNumber}: ${row.raw}\n    " +
              RecordedLineMetadata.acceptedInvalidDetail(
                  checkNotNull(row.value.invalidLineMetadata))
        }
        logger.warn(
            "pitest baseline '$suiteName': ${invalidLineMetadataRows.size} accepted row(s) " +
                "carry invalid diagnostic line metadata. Their keys still accept matching " +
                "mutants and the invalid suffix is not part of the family label, but it supplies " +
                "no line-affinity evidence and every row-rewriting baseline writer refuses it:\n$details")
        if (!writingRecord || initTimeoutAudit) {
          advisoryLog.get().record(
              advisoryScope,
              "${invalidLineMetadataRows.size} baseline row(s) with invalid line metadata",
          )
        } else {
          throw GradleException(
              "pitest baseline '$suiteName': fix the invalid diagnostic line metadata listed " +
                  "above before rewriting ${baselineFile.name}.")
        }
      }
      val acceptedRows: List<BaselineNotes.Row> = baselineDocument.rows
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
      // predate this check, and a fresh pitestModeCompareUnion row legitimately lands before
      // its README criterion is written.
      val undocumentedLabels = BaselineNotes.undocumentedLabels(acceptedRows.mapNotNull { it.note }) {
        readmeFile.takeIf { it.isFile }?.readText() ?: ""
      }
      if (undocumentedLabels.isNotEmpty()) {
        logger.warn(BaselineNotes.undocumentedLabelWarning(suiteName, undocumentedLabels))
        advisoryLog.get().record(advisoryScope, "${undocumentedLabels.size} undocumented family label(s)")
      }

      // Timed-out drift vs the previous run. TIMED_OUT counts as detected, but the
      // baseline-benign KILLED<->TIMED_OUT arithmetic and dangerous transitions from
      // SURVIVED/NO_COVERAGE look identical in one report. A line-less key also cannot
      // prove physical mutant identity or cause. Comparing prior status counts
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
        // ArcMutate reuse is a read-only preview, not another observation. Letting a
        // regenerated assisted report replace this stash makes the next fresh run's
        // drift relative to cached statuses and can manufacture or hide a transition.
        if (historyAssistedReport) return@run
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
        // Format 3 stashes EVERY status and, unlike format 2, is known to contain
        // fresh observations only. A pre-format-3 stash may have been replaced by
        // an assisted report, so carrying it across this upgrade would make the
        // next fresh run compare against cached statuses.
        //
        // Every-status storage still matters: an origin the stash omits is an origin
        // the comparison silently misreads. NO_COVERAGE omitted read the dangerous
        // never-reached flip as benign; KILLED omitted made a benign flap at a
        // fully-killed key read as a coordinate "first observed". The header line
        // is the format's identity — a headerless stash (or a five-field one) was
        // written by an earlier plugin, and its comparison would silently
        // degenerate exactly one way or another, so it resets with a notice
        // instead.
        val stashFormatHeader = "# stash format 3"
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
              "$advisoryScope: status stash predates the current stash format — " +
                  "fresh-only run-to-run drift comparison resets this run and resumes on the next " +
                  "(state-reset notice; not an advisory finding)"
          )
        }
        val previous = if (staleFormat) emptyMap() else tally(stashEntries)
        val current = tally(rows.map { it.coordinate to it.rawStatus })
        if (previous.isNotEmpty()) {
          // classification semantics live in BaselineEngine.driftCompare: the
          // dangerous flavours name each lost-unkilled origin (a key can lose
          // both), "previously detected" requires an actual prior detected read,
          // and everything else is a first observation
          val drift = BaselineEngine.driftCompare(previous, current)
          val fromSurvived = drift.fromSurvived
          val fromNoCoverage = drift.fromNoCoverage
          // The stash deliberately has no line identity. Render the current report's
          // line-full observations for every positive timeout delta; if that key
          // already held a timeout, do not pretend to know which current candidate
          // is the newcomer.
          fun positiveDeltaDetails(
            label: String,
            deltas: Map<String, Int>,
          ): List<String> = deltas.entries.sortedBy { it.key }.flatMap { (key, count) ->
            val candidates = rows
                .filter { it.status == MutantStatus.TIMED_OUT && it.coordinate == key }
                .sortedWith(compareBy<Mutant>({ it.line ?: Int.MAX_VALUE }, { it.lineFullKey }))
            val exact = candidates.size == count
            buildList {
              add(
                  "  $key (+$count) — $label; " + if (exact) {
                    "all ${candidates.size} current TIMED_OUT mutant(s) are new:"
                  } else {
                    "the line-less stash cannot identify which $count of ${candidates.size} " +
                        "current TIMED_OUT mutant(s) are new; all candidates:"
                  }
              )
              candidates.forEachIndexed { index, mutant ->
                val candidateLabel = if (exact) "newly TIMED_OUT" else
                  "candidate ${index + 1}/${candidates.size}"
                add("    $candidateLabel: ${mutant.lineFullKey}")
              }
            }
          }
          if (fromSurvived.isNotEmpty()) {
            val details = positiveDeltaDetails(
                "SURVIVED -> TIMED_OUT",
                fromSurvived.associateWith { drift.positiveTimedOutByCoordinate.getValue(it) },
            )
            logger.warn(
                "pitest '$suiteName': ${fromSurvived.size} coordinate(s) flipped SURVIVED -> TIMED_OUT — " +
                    "a line-less key gained TIMED_OUT while its SURVIVED count fell; physical mutant " +
                    "identity is unresolved. This can be " +
                    "resource behavior, a finite harness race, liveness, or a same-key sibling; " +
                    "reconcile the line-full candidates and do not refresh them out:\n" +
                    details.joinToString("\n")
            )
            advisoryLog.get().record(advisoryScope, "${fromSurvived.size} SURVIVED -> TIMED_OUT flip(s)")
          }
          if (fromNoCoverage.isNotEmpty()) {
            val details = positiveDeltaDetails(
                "NO_COVERAGE -> TIMED_OUT",
                fromNoCoverage.associateWith { drift.positiveTimedOutByCoordinate.getValue(it) },
            )
            logger.warn(
                "pitest '$suiteName': ${fromNoCoverage.size} coordinate(s) flipped NO_COVERAGE -> TIMED_OUT — " +
                    "a line-less key gained TIMED_OUT while its NO_COVERAGE count fell. The stash " +
                    "cannot prove physical mutant identity or cause; reconcile the line-full candidates, " +
                    "add a fast oracle or audit the timeout, and do not refresh it out:\n" +
                    details.joinToString("\n")
            )
            advisoryLog.get().record(advisoryScope, "${fromNoCoverage.size} NO_COVERAGE -> TIMED_OUT flip(s)")
          }
          if (drift.newlyTimedOut > 0 || drift.firstObserved > 0 || drift.resolved > 0) {
            // Membership is deliberately line-less, so an already-audited key can
            // gain another timed-out sibling without entering the unaudited list.
            // Keep the baseline-benign KILLED -> TIMED_OUT arithmetic, but name every
            // changed coordinate, its multiplicity, and the current line-full
            // TIMED_OUT candidates in the default output. The stash cannot know
            // which prior line held a status, so when a key already had a timeout
            // every current candidate is printed rather than falsely attributing
            // the increase. Keep these indented lines next to the summary that
            // triggered them so the coordinate is retained in ordinary logs.
            val details = buildList {
              addAll(positiveDeltaDetails(
                  "previously detected (usually KILLED -> TIMED_OUT)",
                  drift.newlyTimedOutByCoordinate,
              ))
              addAll(positiveDeltaDetails(
                  "first observed (reviewer-stop; no prior detected read)",
                  drift.firstObservedByCoordinate,
              ))
              drift.resolvedByCoordinate.entries.sortedBy { it.key }.forEach { (key, count) ->
                add("  no longer TIMED_OUT: $key (-$count)")
              }
            }
            logger.lifecycle(
                "pitest '$suiteName': timed-out drift vs previous run — " +
                    "${drift.newlyTimedOut} newly timed out (previously detected), " +
                    "${drift.firstObserved} first observed (no prior detected read), " +
                    "${drift.resolved} no longer; cause unresolved\n" + details.joinToString("\n")
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
      // so the summary's watchdog-detected count must be an audited
      // membership rather than a count. 'config/pitest/<suite>-timeouts.csv' holds
      // one 'class,method,mutator' per row — line-less so drift cannot churn
      // membership — and the comment carries a machine-readable cause category
      // plus diagnostic source lines for a reviewer's convenience. Lines are not
      // identity or authorization: formatting and unrelated insertions must not
      // invalidate an audited cause.
      // Only cause:liveness is watchdog detection. A finite cause:resource mutant
      // needs a contract-first deterministic disposition; cause:harness honestly
      // records a reviewed finite covering-path/watchdog race while it is repaired;
      // cause:untriaged is the seeder's explicit unfinished state. All but liveness
      // remain non-certifying. The suite README carries the full structural argument.
      // Absent file, absent check — adoption is per-repo.
      var pendingTimeoutAuditContent: String? = null
      if (initTimeoutAudit) {
        // Seeds the mechanical half of adoption — the membership rows — from this
        // run's report, mirroring pitest<Suite>BaselineUpdate seeding '# untriaged':
        // the tool writes what it can derive, the warnings that follow drive the half
        // that needs a person (the causes). The result is intentionally uncertifiable
        // until every row is reviewed. Refused once the file exists: a second
        // seed would be a rewrite, and the audit's whole point is that membership
        // changes one reviewed row at a time.
        if (timeoutsFile.isFile) {
          throw GradleException(
              "pitest '$suiteName': ${timeoutsFile.name} already exists — " +
                  "$evidenceTimeoutAuditInitTaskPath seeds a new " +
                  "audited set only. For a new timeout, paste the row the verify prints and write its " +
                  "cause in config/pitest/README.md."
          )
        }
        // Also refused with nothing timed out: an empty file would activate the audit
        // while telling its adopter to write causes for zero members, and the task is
        // only ever pointed at by a summary that reported timeouts — a run where they
        // vanished is the observation variability the line-less key exists to absorb, not a
        // population to certify. Arming a never-timed-out suite is a different intent
        // with a different mechanism (a hand-committed comments-only file), so the
        // refusal names it instead of reading as "empty sets are forbidden".
        if (timedOutByAuditKey.isEmpty()) {
          throw GradleException(
              "pitest '$suiteName': no timed-out mutants in this run's report — nothing to seed. " +
                  "Timeout observations can change with execution conditions; re-run " +
                  "$evidencePitestTaskPath under the conditions whose " +
                  "summary reported them (see HARDENING.md, the audited-timeout bullet). To merely arm " +
                  "the audit for a suite that has never timed out, commit ${timeoutsFile.name} with " +
                  "only '#' comment lines — the suite's first timeout then warns as the reviewer-stop " +
                  "it is."
          )
        }
        val seeded = TimeoutAudit.timeoutCandidateRows(timeoutCandidates)
        pendingTimeoutAuditContent =
            "# Audited TIMED_OUT set for the '$suiteName' suite (HARDENING.md, the audited-timeout\n" +
                "# bullet): one line-less 'class,method,mutator' member per row. A timeout detects\n" +
                "# slowness, not wrongness. This seeded file cannot certify until every row is\n" +
                "# classified. Replace cause:untriaged with cause:liveness only when the mutated\n" +
                "# path has no path-owned finite completion guarantee after deterministic seams/\n" +
                "# budgets are exhausted. If a fixture bound is the claimed oracle but exceeds PIT's\n" +
                "# duration * timeoutFactor + timeoutConst, it proves nothing: shorten and re-observe.\n" +
                "# A later emergency ceiling may coexist with liveness but cannot prove it. A\n" +
                "# straight-line path without a loop, retry, lock, wait, blocking call, or external\n" +
                "# completion dependency is not credible liveness evidence. A finite\n" +
                "# cause:resource mutant needs a contract-first disposition. Use cause:harness\n" +
                "# only for a demonstrated finite covering-path/watchdog race; it remains\n" +
                "# non-certifying until repaired. Treat '# line' tags as\n" +
                "# diagnostic metadata and write every structural argument in config/pitest/README.md.\n" +
                seeded + "\n"
      }
      if (timeoutsFile.isFile || pendingTimeoutAuditContent != null) {
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
        val membership = TimeoutAudit.parse(
            pendingTimeoutAuditContent?.lineSequence()?.toList() ?: timeoutsFile.readLines())
        fun countedTimeoutNoun(
          count: Int,
          singular: String,
          plural: String = "${singular}s",
        ): String = "$count ${if (count == 1) singular else plural}"
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
        val timeoutFindings = TimeoutAudit.reportFindings(rows, membership) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        TimeoutAudit.memberPopulationDetail(
            suiteName, timeoutFindings.multiMutantMembers)
            ?.let { logger.lifecycle(it) }
        val unaudited = timeoutFindings.unaudited
        if (unaudited.isNotEmpty()) {
          // rows print paste-ready: the membership key verbatim, the line riding in a
          // '#' comment — pasting the printed row into the set must satisfy the check,
          // never trip the stale-member notice
          logger.warn(TimeoutAudit.unauditedWarning(
              suiteName,
              timeoutsFile.name,
              unaudited,
              configuredPitestVersion,
              configuredTimeoutFactor,
              configuredTimeoutConst,
              historyDecisionCaveat,
          ))
          val unauditedCoordinates = unaudited.mapTo(linkedSetOf()) { it.coordinate }
          val acceptedAtUnauditedKeys = acceptedRows
              .filter { row ->
                val status = row.key.substringAfterLast(',')
                (status == "SURVIVED" || status == "NO_COVERAGE") &&
                    row.key.substringBeforeLast(',') in unauditedCoordinates
              }
          if (acceptedAtUnauditedKeys.isNotEmpty()) {
            val overlappingKeys = acceptedAtUnauditedKeys
                .mapTo(sortedSetOf()) { it.key.substringBeforeLast(',') }
            val overlapVerb = if (overlappingKeys.size == 1) "also has" else "also have"
            logger.warn(
                "pitest '$suiteName': " +
                    "${countedTimeoutNoun(overlappingKeys.size, "unaudited timeout key")} " +
                    "$overlapVerb " +
                    "${countedTimeoutNoun(acceptedAtUnauditedKeys.size, "accepted " +
                        "SURVIVED/NO_COVERAGE baseline row")}:\n" +
                    "  Evidence: Accepted rows and current timeout candidates at each overlapping " +
                    "line-less key follow.\n" +
                    overlappingKeys.joinToString("\n") { coordinate ->
                      "    $coordinate\n" +
                          acceptedAtUnauditedKeys
                              .filter { it.key.substringBeforeLast(',') == coordinate }
                              .joinToString("\n") { "      accepted: ${BaselineNotes.render(it)}" } +
                          "\n" + unaudited
                              .filter { it.coordinate == coordinate }
                              .sortedWith(compareBy<Mutant>({ it.line ?: Int.MAX_VALUE }, { it.lineFullKey }))
                              .joinToString("\n") { "      timeout candidate: ${it.lineFullKey}" }
                    } +
                    "\n  Review: A line-less key cannot prove whether a timeout is the accepted " +
                    "mutant or a sibling, so neither benign load nor the continued validity of the " +
                    "acceptance argument follows.\n" +
                    "  Remedy: Reconcile the accepted line tags with every current TIMED_OUT " +
                    "candidate. Investigate resource behavior, a finite harness race, or liveness " +
                    "before changing either record."
            )
          }
          if (!strictTimeoutAudit) {
            val overlapSummary = if (acceptedAtUnauditedKeys.isEmpty()) {
              ""
            } else {
              val overlapCount = acceptedAtUnauditedKeys
                  .mapTo(linkedSetOf()) { it.key.substringBeforeLast(',') }
                  .size
              ", ${countedTimeoutNoun(overlapCount, "overlapping line-less key")} with " +
                  countedTimeoutNoun(acceptedAtUnauditedKeys.size, "accepted baseline row")
            }
            val advisoryCandidates = TimeoutAudit.timeoutCandidates(unaudited)
            val remainVerb = if (advisoryCandidates.instanceCount == 1) "remains" else "remain"
            advisoryLog.get().record(
                advisoryScope,
                "${TimeoutAudit.timeoutCandidateCount(advisoryCandidates)} $remainVerb " +
                    "unaudited$overlapSummary",
            )
          }
        }
        val staleMembers = timeoutFindings.staleMembers
        if (staleMembers.isNotEmpty()) {
          // Warn-level like the other membership findings: 'retire or fix' is a
          // reviewer-stop exactly as much as a missing cause, and warn is what feeds
          // the end-of-build advisory summary.
          logger.warn(TimeoutAudit.staleWarning(
              suiteName, staleMembers, historyDecisionCaveat))
          advisoryLog.get().record(
              advisoryScope,
              countedTimeoutNoun(staleMembers.size, "stale audit row"),
          )
        }
        val liveMembers = timeoutFindings.liveMembers
        val lineMetadataFindings = timeoutFindings.lineMetadataFindings
        if (lineMetadataFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.lineMetadataWarning(
              suiteName, timeoutsFile.name, lineMetadataFindings))
          advisoryLog.get().record(
              advisoryScope,
              "${lineMetadataFindings.size} audited timeout(s) with invalid line metadata",
          )
        }
        val causeFindings = timeoutFindings.causeFindings
        if (causeFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.causeFindingWarning(
              suiteName, timeoutsFile.name, causeFindings))
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(
                advisoryScope,
                "${causeFindings.size} audited timeout(s) without an admissible cause classification")
          }
        }
        // PIT's CSV has no formatting-stable discriminator finer than the line-less
        // class/method/mutator key. `# line` therefore remains display metadata only:
        // making it a gate caused imports, inserted methods, and line reflow to break
        // certification without changing behavior. Same-key siblings are the known
        // limitation of this format and require report-level human review.
        // The check the set was missing: membership was validated against ALL mutants,
        // so a member whose mutants exist but never time out — a key pasted from the
        // wrong report, or a timeout the tests since learned to kill — was accepted
        // forever. A single-run "did not time out" is exactly the KILLED<->TIMED_OUT
        // load flip the line-less key exists to absorb, so the signal is consecutive
        // quiet runs: the counter persists in .pitest-history/ (machine-local, like
        // the status stash) and resets whenever the member times out or the completed
        // evidence inputs change. Three
        // quiet runs mirrors the flip-family retirement criterion ("3 quiet
        // modeCompare cycles"); a member that only times out under gate load will be
        // reset by gate runs and nagged only during long solo streaks — the notice
        // says so rather than presuming retirement.
        run {
          // The same rule as the status stash above: reused statuses are not a fresh
          // quiet observation and must not advance the timeout-retirement nudge.
          if (historyAssistedReport) return@run
          val retirementEvidence = verifiedEvidence ?: return@run
          // A scoped report can diagnose one class, but it cannot prove suite-wide
          // timeout absence. Legacy reports without a completed evidence manifest are
          // rejected above by the nullable return: neither shape advances retirement.
          if (retirementEvidence.scope != PitestEvidence.FULL_SCOPE ||
              retirementEvidence.historyAssisted) return@run
          // The counter advances per completed PIT invocation, not per verify task
          // invocation: verify can run standalone against the existing report
          // ('finalizedBy', not a dependency), so re-running it would otherwise
          // manufacture quiet evidence from a single mutation run. The completed
          // evidence's invocation id identifies that observation; its stable input
          // identity prevents quiet reads from old source/tool/configuration bytes
          // combining with a new run into a false three-run notice.
          // The format is also the semantic compatibility fence. Bump it whenever
          // retirement interpretation or PIT invocation behavior changes in a way
          // not represented by the retirement evidence identity below. The loaded
          // plugin SHA remains bound everywhere decision-grade evidence needs it,
          // but a fingerprint-only transition under unchanged modeled semantics
          // does not erase this advisory streak.
          val quietFormatHeader = "# timeout quiet format 4"
          val currentInputIdentity =
              retirementEvidence.timeoutRetirementInputIdentitySha256()
          val inputFingerprint = "# inputs $currentInputIdentity"
          val observationFingerprint = "# invocation ${retirementEvidence.invocationId}"
          val previousLines = if (timeoutQuietFile.isFile) timeoutQuietFile.readLines() else emptyList()
          val currentQuietFormat = previousLines.firstOrNull() == quietFormatHeader
          if (previousLines.isNotEmpty() && !currentQuietFormat) {
            logger.lifecycle(
                "$advisoryScope: timeout-retirement stash uses an older compatibility format — " +
                    "the quiet-run counter resets this run " +
                    "(state-reset notice; not an advisory finding)")
          }
          val previousInputIdentity = previousLines.getOrNull(1)
              ?.takeIf { it.startsWith("# inputs ") }
              ?.removePrefix("# inputs ")
              ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
          val sameInputs = currentQuietFormat && previousInputIdentity == currentInputIdentity
          if (currentQuietFormat && previousInputIdentity == null) {
            logger.lifecycle(
                "$advisoryScope: timeout-retirement format-4 stash has a missing/malformed " +
                    "input identity — the quiet-run counter resets this run " +
                    "(state-reset notice; not an advisory finding)")
          } else if (currentQuietFormat && !sameInputs) {
            logger.lifecycle(
                "$advisoryScope: timeout-retirement execution inputs changed since this suite's previous " +
                    "fresh observation (input identity prefixes " +
                    "${previousInputIdentity!!.take(12)} -> " +
                    "${currentInputIdentity.take(12)}) — " +
                    "the quiet-run counter resets this run " +
                    "(state-reset notice; not an advisory finding)")
          }
          val sameObservation = sameInputs &&
              previousLines.getOrNull(2) == observationFingerprint
          val previousQuiet = if (sameInputs) {
            previousLines.filterNot { it.startsWith("#") }.mapNotNull { line ->
              val sep = line.lastIndexOf(',')
              if (sep < 0) null else
                line.substring(0, sep) to (line.substring(sep + 1).toIntOrNull() ?: 0)
            }.toMap()
          } else {
            emptyMap()
          }
          // Live members only, which also means a stale member's count is dropped,
          // not frozen: staleness says the code moved (or the mutator set changed),
          // and quiet evidence about the old method body must be re-measured from
          // zero if the mutant returns, not carried across the change. The cost is
          // two extra runs of patience; the alternative is a retirement nudge argued
          // from code that no longer exists.
          // Retirement is a hygiene nomination for otherwise valid audited
          // liveness only. Resource/harness/untriaged rows and liveness rows whose
          // README argument is missing are unfinished findings, not candidates that
          // three quiet runs can legitimize or silently erase. Optional source-line
          // metadata does not authorize the row and therefore does not alter this set.
          val retirementMembers = liveMembers.filterTo(linkedSetOf()) { member ->
            membership.causeCategories[member] == TimeoutAudit.CauseCategory.LIVENESS &&
                member !in timeoutFindings.undocumented
          }
          val quietRuns = retirementMembers.associateWith { member ->
            when {
              member in timedOutByAuditKey -> 0
              sameObservation -> previousQuiet[member] ?: 0
              else -> (previousQuiet[member] ?: 0) + 1
            }
          }
          BaselineFiles.writeAtomically(
              timeoutQuietFile,
              quietRuns.entries.sortedBy { it.key }
                  .joinToString(
                      "\n",
                      prefix = "$quietFormatHeader\n$inputFingerprint\n" +
                          "$observationFingerprint\n",
                      postfix = "\n",
                  ) { "${it.key},${it.value}" }
          )
          // Derived from the counts, so a same-report re-run reprints it — like every
          // other audit advisory, which are all recomputed from the report rather
          // than remembering they already printed. Warn-level like its siblings
          // (stale rows, missing causes): the retirement criterion family is one
          // tier, and warn is what feeds the end-of-build advisory summary — a gate
          // runs the quiet notice as many hundred lines up-screen as any other.
          val settled = quietRuns.filterValues { it >= 3 }
          if (settled.isNotEmpty()) {
            // The format-3 status stash already persists the latest fresh status
            // multiset. Do not duplicate it into the quiet-counter schema: the
            // current rows are the observation that advanced (or replayed) this
            // count, and rendering them here tells KILLED from SURVIVED/NO_COVERAGE
            // without pretending the counter remembers three runs of that status.
            val latestStatuses = rows.groupBy { it.coordinate }.mapValues { (_, mutants) ->
              mutants.groupingBy { it.rawStatus }.eachCount()
            }
            fun latestStatusSummary(member: String): String = latestStatuses[member].orEmpty()
                .entries.sortedBy { it.key }
                .joinToString(", ") { (status, count) -> "$status x$count" }
            val settledVerb = if (settled.size == 1) "has" else "have"
            logger.warn(
                "$advisoryScope: ${countedTimeoutNoun(settled.size, "audited-timeout member")} " +
                    "$settledVerb not timed out in 3+ consecutive mutation runs:\n" +
                    "  Evidence: The latest fresh status is a clue, not a three-run status history.\n" +
                    settled.entries.sortedBy { it.key }
                        .joinToString("\n") {
                          "    ${it.key} (quiet for ${it.value} runs; latest fresh report " +
                              "${latestStatusSummary(it.key)})"
                        } +
                    "\n  Review: A member that only times out under gate load can be quiet on solo " +
                    "streaks.\n" +
                    "  Remedy: Confirm any retirement under the relevant solo and gate load."
            )
            advisoryLog.get().record(
                advisoryScope,
                countedTimeoutNoun(settled.size, "quiet audited-timeout member"),
            )
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
        val undocumented = timeoutFindings.undocumented
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
        // quiet streaks) stay advisory even here.
        if (strictTimeoutAudit &&
            (unaudited.isNotEmpty() || malformed.isNotEmpty() || causeFindings.isNotEmpty() ||
                undocumented.isNotEmpty())) {
          val strictUnaudited = TimeoutAudit.timeoutCandidates(unaudited)
          val strictFindings = buildList {
            if (unaudited.isNotEmpty()) {
              add("${TimeoutAudit.timeoutCandidateCount(strictUnaudited)} unaudited")
            }
            add(countedTimeoutNoun(malformed.size, "malformed membership row"))
            add(countedTimeoutNoun(
                causeFindings.size,
                "inadmissible or unfinished cause classification",
            ))
            add(if (undocumented.size == 1) {
              "1 audited member without a README cause"
            } else {
              "${undocumented.size} audited members without README causes"
            })
          }
          val unauditedRemediation = if (unaudited.isNotEmpty()) {
            "For each unaudited candidate, paste its printed draft row into ${timeoutsFile.name}, " +
                "then replace every deliberate cause:untriaged placeholder with the reviewed cause. "
          } else {
            ""
          }
          throw GradleException(
              "pitest '$suiteName': -PstrictTimeoutAudit refuses certification because it found " +
                  strictFindings.joinToString(", ") + ":\n" +
                  "  Evidence: The warnings above list every affected row, member, and timeout " +
                  "candidate.\n" +
                  "  Review: Only cause:liveness may remain in a certifying audited set. " +
                  "Keep finite resource/harness work explicit and non-certifying while fixing it; do not " +
                  "relabel it as liveness or delete it from one quiet run.\n" +
                  "  Remedy: $unauditedRemediation" +
                  "Finish untriaged or missing " +
                  "classifications, write each structural argument in config/pitest/README.md, and remove a " +
                  "repaired row only after repeated fresh history-free observations under the relevant load."
          )
        }
      } else if (timedOutByAuditKey.isNotEmpty()) {
        // A suite carrying timeouts with no audited set is running with the blind
        // spot the audit exists for, and nothing on screen said the feature exists —
        // it was discoverable only by reading HARDENING.md. Advisory nudge normally;
        // under the strict flag an unadopted timeout-carrying suite is an unaudited
        // newcomer by definition.
        // The rows print paste-ready alongside the named task: a timeout may not reproduce, so
        // by the time anyone acts on this nudge the next run may hold a clean report —
        // TimeoutAuditInit then rightly refuses to seed from it, and without the rows
        // here the coordinate that timed out is recoverable only from the daemon log.
        val hint = missingTimeoutAuditHint()
        if (strictTimeoutAudit) {
          throw GradleException(hint)
        }
        logger.warn(hint)
        val missingAuditVerb = if (timeoutCandidates.instanceCount == 1) "has" else "have"
        advisoryLog.get().record(
            advisoryScope,
            "${TimeoutAudit.timeoutCandidateCount(timeoutCandidates)} $missingAuditVerb no audited set",
        )
      }

      // Row-level keep plan, computed once and read by BOTH prune and the check
      // path's candidate preview: each surface prints row identities, so deciding
      // from two allocators (the old hint budgeted in baseline-file order over key
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
      val fresh = multisetDiff(current, accepted)
      val stale = multisetDiff(accepted, current)
      val pruneCandidateIndices = acceptedRows.indices.filter {
        keepPlan[it] == BaselineEngine.Disposition.DROP
      }
      val pruneCandidates = pruneCandidateIndices
          .map { BaselineNotes.render(acceptedRows[it]) }
          .sorted()

      // A candidate list is not deletion authority until it repeats. Persist the
      // exact DROP multiset — the same row-level plan BaselinePrune consumes — only
      // for provenance-bound, fresh, full, history-free observations made outside
      // routine release certification. Certification may reveal candidates, but it
      // must not silently satisfy a later destructive workflow's repetition gate.
      // Unlike the
      // advisory timeout-retirement identity, this decision identity retains the
      // loaded plugin SHA: a classifier implementation change must reset the chain.
      // The mutation-record fingerprint binds the accepted rows, timeout records,
      // provenance and README argument bytes being reviewed. Ambient solo/gate load
      // is deliberately not inferred; matching state is a mechanical prerequisite,
      // and the output keeps the load-context review human and explicit.
      val prunePreviewProvenanceValid = !committedRecordExisted ||
          (!committedProvenance.orphan &&
              !committedProvenance.legacyUnbound &&
              !committedProvenance.disagreement &&
              !committedProvenance.torn &&
              committedProvenance.malformedPitVersion == null &&
              committedProvenance.malformedToolchain == null &&
              recordedPit == currentPit &&
              recordedToolchain != null &&
              currentToolchain != null &&
              recordedToolchain.identitySha256 == currentToolchain.identitySha256)
      // Typed validation proves that an older report still describes the checkout;
      // it does not turn that report into another observation. Advance destructive
      // prune preparation only when PIT itself completed matching evidence in this
      // Gradle invocation. Writers already require the same proof above; ordinary
      // Verify remains useful and read-only when it merely revalidates prior output.
      val prunePreviewCompletedThisInvocation = verifiedEvidence?.let { evidence ->
        try {
          certificationSession.get().requireCompletedAttempt(
              evidenceProjectPath, suiteName, evidence)
          true
        } catch (_: IllegalStateException) {
          false
        }
      } == true
      val prunePreviewTransition: PrunePreviewTransition? = verifiedEvidence
          ?.takeIf { evidence ->
            (canonicalWriteOperation == BaselineWriteOperation.CHECK || prune) &&
                !certificationActive &&
                prunePreviewCompletedThisInvocation &&
                prunePreviewProvenanceValid &&
                evidence.scope == PitestEvidence.FULL_SCOPE &&
                !evidence.historyAssisted &&
                !historyAssistedReport
          }
          ?.let { evidence ->
            val recordFingerprint = PitestEvidence.mutationRecordFingerprint(
                evidenceProjectDir, baselineFile.parentFile, suiteName)
            val transition = PrunePreviewHistory.observe(
                prunePreviewFile.takeIf(File::isFile)?.readText(),
                PrunePreviewObservation(
                    inputIdentitySha256 = evidence.inputIdentitySha256(),
                    mutationRecordFingerprint = recordFingerprint,
                    invocationId = evidence.invocationId,
                    qualifies = fresh.isEmpty(),
                    candidates = pruneCandidates,
                ),
            )
            BaselineFiles.writeAtomically(
                evidenceProjectDir, prunePreviewFile, transition.state.render())

            fun deltaDetail(): String = buildList {
              transition.added.forEach { add("    added: $it") }
              transition.removed.forEach { add("    removed: $it") }
            }.joinToString("\n")

            when (transition.kind) {
              PrunePreviewTransitionKind.FIRST -> if (pruneCandidates.isNotEmpty()) {
                logger.lifecycle(
                    "pitest baseline '$suiteName': stored prune-candidate observation 1 of 2 " +
                        "(${pruneCandidates.size} row(s)); this is mechanical preview state, not " +
                        "deletion authority or a load-context finding")
              }
              PrunePreviewTransitionKind.MALFORMED_RESET -> logger.lifecycle(
                  "pitest baseline '$suiteName': prune-preview state was missing, malformed, or " +
                      "written by an older format — matching observations reset this run " +
                      "(not an advisory finding${transition.malformedDetail?.let { ": $it" } ?: ""})")
              PrunePreviewTransitionKind.INPUT_RESET -> logger.lifecycle(
                  "pitest baseline '$suiteName': prune-preview execution inputs changed — " +
                      "matching observations reset this run (not an advisory finding)")
              PrunePreviewTransitionKind.RECORD_RESET -> logger.lifecycle(
                  "pitest baseline '$suiteName': reviewed mutation-record bytes changed — " +
                      "prune-preview matching observations reset this run (not an advisory finding)")
              PrunePreviewTransitionKind.INELIGIBLE_RESET -> logger.lifecycle(
                  "pitest baseline '$suiteName': this fresh report contains gated rows absent " +
                      "from the accepted baseline — it resets prune-preview matching state and " +
                      "cannot authorize deletion (not an additional advisory finding)")
              PrunePreviewTransitionKind.AFTER_INELIGIBLE -> if (pruneCandidates.isNotEmpty()) {
                logger.lifecycle(
                    "pitest baseline '$suiteName': stored prune-candidate observation 1 of 2 " +
                        "after an ineligible or origin-reset observation " +
                        "(${pruneCandidates.size} row(s)); deletion " +
                        "remains unauthorized")
              }
              PrunePreviewTransitionKind.SAME_INVOCATION -> Unit
              PrunePreviewTransitionKind.MATCHED -> if (pruneCandidates.isNotEmpty()) {
                logger.lifecycle(
                    "pitest baseline '$suiteName': prune-candidate preview matches " +
                        "${transition.state.matchingObservations} distinct fresh full history-free " +
                        "observation(s) (${pruneCandidates.size} row(s)). This satisfies the " +
                        "mechanical repetition prerequisite once the count reaches 2; confirm the " +
                        "reviewed solo/gate load context and every removal criterion separately.")
              }
              PrunePreviewTransitionKind.MISMATCH -> {
                val previousCount = transition.previous?.candidates?.size ?: 0
                val changes = transition.added.size + transition.removed.size
                logger.warn(
                    "pitest baseline '$suiteName': prune-candidate preview differs from the " +
                        "previous eligible fresh full history-free observation; the " +
                        "two-matching-preview requirement is not met:\n" +
                        "  Evidence: ${pruneCandidates.size} candidate row(s) now, $previousCount " +
                        "previously; exact multiset drift:\n" + deltaDetail() + "\n" +
                        "  Review: The candidate population is wandering. Do not infer stable " +
                        "removal or edit the baseline. The plugin does not infer whether the runs " +
                        "shared the reviewed solo/gate load context.\n" +
                        "  Remedy: Obtain another $evidencePitestTaskPath -PnoMutationHistory " +
                        "preview under the reviewed load context. Two distinct matching completed " +
                        "previews must exist before $evidenceBaselinePruneTaskPath can write.")
                advisoryLog.get().record(
                    advisoryScope,
                    "$changes prune-candidate row change(s) vs previous eligible preview",
                )
              }
            }
            transition
          }
      // Ordinary writers preserve an existing document's schema state. The first
      // writer that creates a baseline starts at the explicit current schema, making
      // new adoption unambiguous without forcing an in-place fleet migration. Row
      // slots are the only material replaced; comments and blanks remain verbatim.
      fun renderBaseline(
        replacementRows: List<BaselineNotes.Row>,
        sourceRowIndices: List<Int?>? = null,
      ): String {
        val targetSchema = if (baselineExisted) baselineDocument.schemaState
        else BaselineDocument.SchemaState.CURRENT
        val rendered = if (sourceRowIndices == null) {
          baselineDocument.rewriteRowsPreservingNonRows(replacementRows, targetSchema)
        } else {
          require(replacementRows.size == sourceRowIndices.size) {
            "pitest baseline '$suiteName': ${replacementRows.size} replacements have " +
                "${sourceRowIndices.size} source slots"
          }
          baselineDocument.rewriteRowsPreservingOrigins(
              replacementRows.zip(sourceRowIndices) { row, source ->
                BaselineDocument.RowReplacement(row, source)
              },
              targetSchema,
          )
        }
        return if (!baselineExisted && rendered.isNotEmpty() && !rendered.endsWith("\n")) {
          "$rendered\n"
        } else {
          rendered
        }
      }

      data class BaselineWritePlan(
        val content: String?,
        val retainedNonRowEvidence: Boolean,
      )

      fun planBaseline(
        replacementRows: List<BaselineNotes.Row>,
        sourceRowIndices: List<Int?>? = null,
      ): BaselineWritePlan {
        val hasNonRowEvidence = baselineDocument.comments.isNotEmpty() ||
            baselineDocument.malformedRows.isNotEmpty()
        if (replacementRows.isEmpty() && !hasNonRowEvidence) {
          return BaselineWritePlan(null, false)
        }
        val content = renderBaseline(replacementRows, sourceRowIndices)
        return BaselineWritePlan(content.takeIf(String::isNotEmpty), content.isNotEmpty())
      }

      fun commitBaselinePlan(plan: BaselineWritePlan, forceProvenance: Boolean = false) {
        val recordWillExist = plan.content != null || timeoutsFile.isFile
        val baselineWrite = BaselineFiles.Write(baselineFile, plan.content)
        val provenanceWrites = mutableListOf<BaselineFiles.Write>()
        val writes = mutableListOf<BaselineFiles.Write>()
        if (recordWillExist && (forceProvenance || !committedRecordExisted)) {
          val toolchain = currentToolchain ?: throw GradleException(
              "pitest '$suiteName': current completed mutation-toolchain evidence is required to stamp records")
          provenanceWrites += BaselineFiles.Write(toolchainRecordFile, toolchain.render())
          provenanceWrites += BaselineFiles.Write(toolVersionFile, currentPit + "\n")
        }
        // A first record puts provenance before content: interruption can leave only
        // orphan sidecars, never a legacy-unbound baseline. An existing-record Rebase
        // puts its safe-superset content first: N-1 sees conservative debt while this
        // plugin refuses the still-old provenance. Deletion removes content first.
        when {
          !recordWillExist -> writes += baselineWrite
          forceProvenance && committedRecordExisted -> {
            writes += baselineWrite
            writes += provenanceWrites
          }
          else -> {
            writes += provenanceWrites
            writes += baselineWrite
          }
        }
        val removedProvenanceFiles = if (recordWillExist) emptyList() else buildList {
          if (toolVersionFile.exists()) add(toolVersionFile.name)
          if (toolchainRecordFile.exists()) add(toolchainRecordFile.name)
        }
        if (!recordWillExist) {
          writes += BaselineFiles.Write(toolchainRecordFile, null)
          writes += BaselineFiles.Write(toolVersionFile, null)
        }
        BaselineFiles.writeAllAtomically(evidenceProjectDir, writes)
        warnIgnoredMutationRecords(
            writes.filter { it.content != null }.map { it.target })
        if (removedProvenanceFiles.isNotEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': removed orphan mutation provenance: " +
                  removedProvenanceFiles.joinToString())
        }
      }

      // A line-drift signal must be observable before any operation that refreshes
      // the tags. Update and Prune are broader reviewed transitions, while Retag is
      // the metadata-only acknowledgement; all three print the pre-write evidence.
      // Rebase and Union preserve existing tags, so an ordinary check still sees the
      // signal after either additive transition.
      val driftedBaselineLines = BaselineNotes.lineDrift(
          acceptedRows,
          currentLines.mapValues { (_, lines) -> lines.mapNotNull { it.toIntOrNull() } },
      )
      fun reportLineDrift(
        followUpLabel: String,
        followUp: String,
        recordOutstanding: Boolean,
      ) {
        if (driftedBaselineLines.isEmpty()) return
        val keyCount = driftedBaselineLines.size
        val keyNoun = if (keyCount == 1) "key" else "keys"
        val coordinates =
            driftedBaselineLines.entries.sortedBy { it.key }.joinToString("\n") { (key, lines) ->
              val (recordedLines, observedLines) = lines
              "  $key # recorded tag line(s): ${recordedLines.sorted().joinToString(", ")}; " +
                  "unmatched observed line(s): ${observedLines.sorted().joinToString(", ")}"
            }
        logger.warn(
            "pitest baseline '$suiteName': line drift detected for $keyCount accepted $keyNoun; " +
                "no recorded " +
                "'# line' tag names an observed unkilled-mutant line:\n" +
                coordinates +
                "\n  Review: Re-read the README acceptance argument; the accepted code may have " +
                "moved, or a new mutant may sit under an old acceptance (the same-key swap)." +
                "\n  $followUpLabel: $followUp"
        )
        if (recordOutstanding) {
          advisoryLog.get().record(
              advisoryScope,
              "$keyCount line-drifted baseline $keyNoun",
          )
        }
      }
      if (prune) {
        // Mechanically shrink-only identity refresh: drop rows unmatched by this run
        // and add no rows. The check path preview names these exact candidates but
        // deliberately does not infer that one absence is stable — the caller must
        // first distinguish removal from an uninsured load/mode flip. Matched rows do
        // refresh their '# line' metadata from this report, which is safe because lines
        // are not identity and cannot accept a mutant. Unmatched timeout, flip, and
        // insurance keeps retain their old lines because this run did not observe them
        // at their own key. What is kept and what drops is the keep plan above verbatim.
        val kept = mutableListOf<BaselineNotes.Row>()
        val keptUnmatched = mutableListOf<Pair<BaselineNotes.Row, String>>()
        val droppedRows = mutableListOf<BaselineNotes.Row>()
        for ((rowIndex, row) in acceptedRows.withIndex()) {
          when (keepPlan[rowIndex]) {
            BaselineEngine.Disposition.MATCHED -> kept.add(row)
            BaselineEngine.Disposition.TIMEOUT -> {
              kept.add(row)
              keptUnmatched.add(
                  row to "preserved by this run's TIMED_OUT budget (not an observed kill; " +
                      "same-mutant versus sibling identity remains ambiguous)")
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
              "pitest baseline '$suiteName': refusing $evidenceBaselinePruneTaskPath — the report has " +
                  "${unacceptedAfterPrune.size} gated mutant(s) the proposed pruned baseline would not " +
                  "accept, so this is not a green shrink-only transition; no baseline changes were made:\n" +
                  unacceptedAfterPrune.joinToString("\n") { "  $it" }
          )
        }
        require(droppedRows.map(BaselineNotes::render).sorted() == pruneCandidates) {
          "pitest baseline '$suiteName': prune writer and persisted candidate preview disagreed"
        }
        if (droppedRows.isNotEmpty()) {
          val transition = prunePreviewTransition ?: throw GradleException(
              "pitest baseline '$suiteName': refusing $evidenceBaselinePruneTaskPath — its " +
                  "${droppedRows.size}-row write-boundary candidate set is not a provenance-bound " +
                  "fresh full history-free preview; no baseline or provenance changes were made.\n" +
                  "  Remedy: Run $evidencePitestTaskPath -PnoMutationHistory until two distinct " +
                  "matching completed previews exist under the reviewed load context, then run " +
                  "$evidenceBaselinePruneTaskPath again.")
          if (!transition.writerAuthorized) {
            val exactDrift = buildList {
              transition.added.forEach { add("    added: $it") }
              transition.removed.forEach { add("    removed: $it") }
            }.joinToString("\n")
            val evidence = when (transition.kind) {
              PrunePreviewTransitionKind.MISMATCH ->
                "the current candidate multiset differs from the previous eligible preview" +
                    (if (exactDrift.isEmpty()) "" else ":\n$exactDrift")
              PrunePreviewTransitionKind.MATCHED ->
                "the current run advances the stored sequence to " +
                    "${transition.state.matchingObservations} matching observation(s), but two " +
                    "matching previews were not complete before this destructive workflow began"
              PrunePreviewTransitionKind.SAME_INVOCATION ->
                "this invocation was already recorded and cannot count as another observation"
              PrunePreviewTransitionKind.INPUT_RESET ->
                "the PIT execution-input identity changed and reset the sequence"
              PrunePreviewTransitionKind.RECORD_RESET ->
                "the reviewed mutation-record identity changed and reset the sequence"
              PrunePreviewTransitionKind.INELIGIBLE_RESET ->
                "this report has fresh gated rows and is not eligible deletion evidence"
              PrunePreviewTransitionKind.MALFORMED_RESET ->
                "the prior state was missing, malformed, or incompatible and reset the sequence"
              PrunePreviewTransitionKind.AFTER_INELIGIBLE,
              PrunePreviewTransitionKind.FIRST ->
                "only one qualifying candidate observation is stored"
            }
            throw GradleException(
                "pitest baseline '$suiteName': refusing $evidenceBaselinePruneTaskPath — " +
                    "two prior distinct matching qualifying previews are required before its own " +
                    "fresh write-boundary run may delete ${droppedRows.size} row(s); no baseline " +
                    "or provenance changes were made:\n" +
                    "  Evidence: $evidence.\n" +
                    "  Review: Matching candidate bytes are a mechanical prerequisite, not proof " +
                    "that the runs shared the relevant solo/gate load context or that each written " +
                    "removal criterion is satisfied.\n" +
                    "  Remedy: Review this completed preview, obtain another " +
                    "$evidencePitestTaskPath -PnoMutationHistory preview if needed, then run " +
                    "$evidenceBaselinePruneTaskPath again only after two completed previews match.")
          }
        }
        reportLineDrift(
            "Pre-write notice",
            "The selected $evidenceBaselinePruneTaskPath also applies its reviewed removals. " +
                "If only metadata should change, use $evidenceBaselineRetagTaskPath instead.",
            recordOutstanding = false)
        val pruneRewrite = BaselineEngine.pruneRewrite(acceptedRows, keepPlan, currentLines)
        val rowSpellingsChanged = pruneRewrite.written != baselineRowLines
        val keptDetail = if (keptUnmatched.isEmpty()) "" else
          "\n  kept ${keptUnmatched.size} unmatched row(s):\n" +
              keptUnmatched.joinToString("\n") { (row, why) -> "  ${BaselineNotes.render(row)} — $why" }
        val refreshedDetail = if (pruneRewrite.refreshedLineTags == 0) "" else
          "; refreshed ${pruneRewrite.refreshedLineTags} kept row line tag(s) from this run"
        var baselineTransitionCommitted = false
        if (droppedRows.isEmpty() && !rowSpellingsChanged) {
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped nothing — every row matches this run$keptDetail")
        } else if (kept.isEmpty()) {
          // If no non-row evidence exists, an empty baseline and no baseline are the
          // same record and the file is removed. Otherwise retain the schema marker,
          // comments, and blanks while removing only the row slots.
          val plan = planBaseline(emptyList())
          commitBaselinePlan(plan)
          baselineTransitionCommitted = true
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped every row unmatched by this run — " +
                  (if (plan.retainedNonRowEvidence) "non-row baseline material preserved" else "baseline file removed") +
                  ":\n" + droppedRows.joinToString("\n") { row ->
                    "  ${BaselineNotes.render(row)}${describe(row.key)}"
                  })
        } else {
          // Kept rows are re-rendered even when only a matched row's line metadata
          // changed. This also migrates a legacy five-field file to the line-less
          // format (the legacy line field becomes a '# line' tag).
          val plan = planBaseline(
              pruneRewrite.written.map(BaselineNotes::parse),
              pruneRewrite.sourceRowIndices.map { it as Int? },
          )
          commitBaselinePlan(plan)
          baselineTransitionCommitted = true
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
                "pitest baseline '$suiteName': prune dropped ${droppedRows.size} row(s) unmatched by this run " +
                    "(baseline now ${kept.size}$refreshedDetail):\n" +
                    droppedRows.joinToString("\n") { row -> "  ${BaselineNotes.render(row)}${describe(row.key)}" } +
                    keptDetail
            )
          }
        }
        if (!baselineTransitionCommitted) {
          if (baselineFile.isFile) stampProvenance() else stampOrRetireProvenance()
        }
        return@doLast
      }
      if (retag) {
        // Line metadata is the accepted baseline's same-key-swap review signal, so
        // an unrelated writer must not silently acknowledge it. Retag is the explicit
        // acknowledgement path: refuse fresh debt, preserve every accepted row and
        // non-row slot, refresh only rows this report actually matches, and leave
        // licensed-engine/subsumed unmatched evidence semantically intact.
        val unacceptedBeforeRetag = multisetDiff(current, acceptedRows.map { it.key })
        if (unacceptedBeforeRetag.isNotEmpty()) {
          throw GradleException(
              "pitest baseline '$suiteName': refusing $evidenceBaselineRetagTaskPath — the report has " +
                  "${unacceptedBeforeRetag.size} gated mutant(s) the current baseline does not accept; " +
                  "line metadata cannot hide fresh debt and no baseline changes were made:\n" +
                  unacceptedBeforeRetag.joinToString("\n") { "  $it" }
          )
        }
        reportLineDrift(
            "Pre-write notice",
            "$evidenceBaselineRetagTaskPath is the explicit metadata-only acknowledgement; " +
                "inspect the resulting diff.",
            recordOutstanding = false)
        val rewrite = BaselineEngine.retagRewrite(acceptedRows, currentLines)
        if (rewrite.refreshedLineTags == 0) {
          logger.lifecycle(
              "pitest baseline '$suiteName': retag changed nothing — every matched row already carries " +
                  "this run's line metadata; ${acceptedRows.size} accepted row(s) preserved")
          if (baselineFile.isFile) stampProvenance() else stampOrRetireProvenance()
        } else {
          val plan = planBaseline(
              rewrite.written.map(BaselineNotes::parse),
              rewrite.sourceRowIndices,
          )
          commitBaselinePlan(plan)
          logger.lifecycle(
              "pitest baseline '$suiteName': retag refreshed ${rewrite.refreshedLineTags} matched " +
                  "row line tag(s); preserved all ${acceptedRows.size} accepted row(s), including " +
                  "unmatched evidence")
        }
        return@doLast
      }
      if (rebase) {
        // A PIT or mutation-toolchain transition is not evidence that any old
        // acceptance disappeared. Preserve the complete existing record and add
        // every missing current gated copy as explicit triage debt. Provenance and
        // the safe-superset record are one exception-transactional commit plan.
        val merge = BaselineEngine.rebaseMerge(acceptedRows, current, currentLines)
        if (merge.added.isNotEmpty()) {
          val plan = planBaseline(
              merge.merged.map(BaselineNotes::parse),
              merge.sourceRowIndices,
          )
          commitBaselinePlan(plan, forceProvenance = true)
          val addedRows = merge.merged.takeLast(merge.added.size)
          logger.lifecycle(
              "pitest baseline '$suiteName': provenance rebase preserved ${acceptedRows.size} old row(s) " +
                  "and added ${merge.added.size} current row(s) seeded '# untriaged' " +
                  "(baseline now ${merge.total}):\n" +
                  addedRows.joinToString("\n") { row ->
                    "  $row${describe(BaselineNotes.parse(row).key)}"
                  })
          logger.lifecycle(
              "pitest baseline '$suiteName': BaselineRebase wrote ${baselineFile.name}, " +
                  "${toolVersionFile.name}, and ${toolchainRecordFile.name}")
        }
        if (merge.added.isEmpty()) {
          if (baselineFile.isFile) stampProvenance(force = true) else stampOrRetireProvenance()
          if (baselineFile.isFile || timeoutsFile.isFile) {
            logger.lifecycle(
                "pitest baseline '$suiteName': BaselineRebase retained all ${acceptedRows.size} " +
                    "accepted row(s); ${baselineFile.name} unchanged; wrote " +
                    "${toolVersionFile.name} and ${toolchainRecordFile.name}")
          } else {
            logger.lifecycle(
                "pitest baseline '$suiteName': BaselineRebase found no accepted or timeout record; " +
                    "${baselineFile.name} absent and no provenance files written")
          }
        }
        return@doLast
      }
      if (update) {
        reportLineDrift(
            "Pre-write notice",
            "The selected $evidenceBaselineUpdateTaskPath is a complete report rewrite. " +
                "If only metadata should change, use $evidenceBaselineRetagTaskPath instead.",
            recordOutstanding = false)
        // Report-driven rewrite plus this run's timeout/flip-insurance keeps. A
        // dropped row's '# note' must not
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
        // Within a key, accepted rows are assigned to this run's mutants by maximum
        // LINE AFFINITY first, then by file order. A unique anchor attributes a row;
        // repeated/overlapping anchors are only a deterministic allocation. Without
        // line evidence the assignment is arbitrary, which is the documented same-key
        // blind spot, not a bug to police.
        // pairing, note carry, and seeding live in BaselineEngine.updateRewrite;
        // this task renders its result and names each dropped row's fate below
        val rewrite = BaselineEngine.updateRewrite(acceptedRows, currentLines, keepPlan)
        val droppedIdx = rewrite.droppedIdx
        val carriedIdx = rewrite.carriedIdx
        // A refresh with nothing unkilled writes no record: an empty (or newly
        // created, one-newline) baseline file reads as an armed-but-empty record
        // where there is no record at all, and clutters fully-detected suites.
        var baselineTransitionCommitted = false
        if (rewrite.written.isEmpty()) {
          if (baselineFile.isFile) {
            val plan = planBaseline(emptyList())
            commitBaselinePlan(plan)
            baselineTransitionCommitted = true
            logger.lifecycle(
                "pitest baseline '$suiteName': nothing unkilled — " +
                    (if (plan.retainedNonRowEvidence) "non-row baseline material preserved"
                    else "baseline file removed"))
          } else {
            logger.lifecycle("pitest baseline '$suiteName': nothing unkilled — no baseline to write")
          }
        } else {
          val plan = planBaseline(
              rewrite.written.map(BaselineNotes::parse),
              rewrite.sourceRowIndices,
          )
          commitBaselinePlan(plan)
          baselineTransitionCommitted = true
          logger.lifecycle(
              "pitest baseline '$suiteName': wrote ${rewrite.written.size} accepted entries " +
                  "from ${rewrite.copies} currently unkilled mutant(s)" +
                  (if (rewrite.preservedTimeoutIdx.isEmpty()) "" else
                    " (${rewrite.preservedTimeoutIdx.size} accepted timeout row(s) preserved)") +
                  (if (rewrite.preservedInsuredIdx.isEmpty()) "" else
                    " (${rewrite.preservedInsuredIdx.size} flip-insurance row(s) preserved)") +
                  (if (rewrite.seeded == 0) "" else " (${rewrite.seeded} new row(s) seeded '# untriaged')") +
                  (if (rewrite.flipped == 0) "" else " (${rewrite.flipped} note(s) carried across a status flip — re-check them)")
          )
        }
        if (droppedIdx.isNotEmpty()) {
          // Notes get the same treatment as every removed row: a note still in the
          // carry pool after the rewrite is an
          // acceptance argument that just left the baseline — name its fate per row,
          // because a lost note that prints identically to a carried one is still
          // silent (casebook: the note the line shift dropped).
          fun removalEvidence(idx: Int): String {
            if (idx in carriedIdx) return "status changed; acceptance note carried to the current row"
            val row = acceptedRows[idx]
            val coordinate = row.key.substringBeforeLast(',')
            val atCoordinate = rows.filter { it.coordinate == coordinate }
            if (atCoordinate.isEmpty()) {
              return "coordinate absent from this PIT report; tool/population change, not an observed kill"
            }
            val killedLines = atCoordinate.filter { it.status == MutantStatus.KILLED }
                .mapNotNullTo(HashSet()) { it.line }
            return if (row.recordedLines.isNotEmpty() && row.recordedLines.any { it in killedLines }) {
              "a KILLED observation shares a recorded-line anchor; duplicate sibling identity may remain ambiguous"
            } else {
              "coordinate present but this row is not tied to a KILLED observation; sibling identity is ambiguous"
            }
          }
          fun rowFate(idx: Int): String = when {
            idx in carriedIdx -> " — ${removalEvidence(idx)}"
            acceptedRows[idx].note == null -> " — ${removalEvidence(idx)}"
            else -> " — note dropped with the row; ${removalEvidence(idx)}"
          }
          val lostCount = droppedIdx.count { acceptedRows[it].note != null && it !in carriedIdx }
          logger.lifecycle(
              "pitest baseline '$suiteName': removed or transitioned ${droppedIdx.size} row(s); " +
                  "each disposition below distinguishes observed kill evidence from missing or ambiguous population evidence:\n" +
                  droppedIdx.joinToString("\n") { idx ->
                    "  ${BaselineNotes.render(acceptedRows[idx])}${describe(acceptedRows[idx].key)}${rowFate(idx)}"
                  } +
                  (if (lostCount == 0) "" else
                      "\n  $lostCount note(s) dropped with their rows — re-home the acceptance argument by hand if it still applies")
          )
        }
        if (!baselineTransitionCommitted) {
          if (baselineFile.isFile) stampProvenance() else stampOrRetireProvenance()
        }
        return@doLast
      }
      if (union) {
        // Append-only acceptance for an existing record. Adds this run's unkilled
        // rows in canonical form without dropping baseline rows absent from the run;
        // mode-flip evidence should normally use pitestModeCompareUnion so the
        // observed statuses are written into the insurance note. The report-driven
        // BaselineUpdate remains a separately reviewed complete rewrite.
        // the merge — per-key max counts, existing rows verbatim after maximum
        // exact-line affinity and the live-anchor/file-order fallback, added copies
        // seeded '# untriaged' with the genuinely unclaimed lines — lives in
        // BaselineEngine.unionMerge
        val merge = BaselineEngine.unionMerge(acceptedRows, current, currentLines)
        if (merge.added.isEmpty()) {
          logger.lifecycle("pitest baseline '$suiteName': union added nothing new")
        } else {
          val plan = planBaseline(
              merge.merged.map(BaselineNotes::parse),
              merge.sourceRowIndices,
          )
          commitBaselinePlan(plan)
          val addedRows = merge.merged.takeLast(merge.added.size)
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${merge.added.size} entries seeded '# untriaged' " +
                  "(baseline now ${merge.total}):\n" +
                  addedRows.joinToString("\n") { row ->
                    "  $row${describe(BaselineNotes.parse(row).key)}"
                  }
          )
        }
        if (merge.added.isEmpty()) {
          if (baselineFile.isFile) stampProvenance() else stampOrRetireProvenance()
        }
        return@doLast
      }
      // Line-drift advisory: an unkilled mutant at a line no row's '# line' tag
      // names is a population the acceptance argument may no longer describe —
      // either the anchor moved, or a same-key swap slid a new mutant under an old
      // acceptance. Row-level where the data supports it (every row of the key
      // tagged, observed count matching the row count): the baseline's multiset
      // already fails a genuinely new sibling as a count change, so unlike the
      // audited-timeout sets there is no new-sibling quiet case to preserve, and
      // any unrecorded line under matched counts is worth a re-read. Partial tags
      // or skewed counts fall back to the audit's key-level disjointness. Advisory
      // only, never a failure: lines are metadata, and the explicit retag writer
      // acknowledges reviewed drift without touching identity or unmatched evidence
      // (BaselineNotes.lineDrift owns the semantics).
      reportLineDrift(
          "Remedy if the argument still applies",
          "Run $evidenceBaselineRetagTaskPath — it rewrites only matched line metadata, preserves " +
              "every accepted row, including unmatched licensed-engine evidence, and refuses any " +
              "fresh gated row.",
          recordOutstanding = true)
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
        // The unmatched entries classified from the keep plan — the SAME row-level
        // classifier prune executes, so the preview names exactly the current
        // candidates (two independent allocators once disagreed at cross-status
        // coordinates; casebook: the stale hint that named the wrong flag). Agreement
        // is necessary but does not authorize the deletion: one fresh run cannot tell
        // stable removal from an uninsured load/mode flip. Rows a live flip counterpart
        // explains are neither candidates nor claimed kept: the fresh side's "newly
        // covered" pairing already names them as triage.
        //   timeout — watchdog detection, whose cause and physical same-key sibling
        //             remain to be reconciled; counting it as a stable removal contradicted the
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
          val candidateHeading = if (fresh.isEmpty()) {
            "The $evidenceBaselinePruneTaskPath classifier marks exactly these row(s) as " +
                "candidates in this preview (no baseline change):"
          } else {
            "these are unmatched candidate row(s) only (preview only; no baseline change). " +
                "No removal task is eligible while this report also contains fresh gated rows:"
          }
          val removalGuidance = if (fresh.isNotEmpty()) {
            "\nThis report also has ${fresh.size} new gated row(s). Immediate next action: " +
                "resolve every new row through the ratchet failure's additive remediation below; " +
                "do not run Prune or Retag while they remain gated. After the gate is clear, obtain " +
                "a new fresh full history-free preview before reconsidering these candidates."
          } else {
            val freshFullHistoryFreePreview = verifiedEvidence?.let { evidence ->
              !certificationActive &&
                  prunePreviewCompletedThisInvocation &&
                  evidence.scope == PitestEvidence.FULL_SCOPE &&
                  !evidence.historyAssisted &&
                  !historyAssistedReport
            } == true
            val currentObservation = when {
              certificationActive ->
                "This certification observation does not advance prune-preview state; " +
                    "release proof is not implicit preparation for a destructive baseline write."
              historyAssistedReport ->
                "This [history] preview cannot qualify as fresh full history-free absence " +
                    "evidence."
              verifiedEvidence != null && !prunePreviewCompletedThisInvocation ->
                "This revalidated prior-report preview does not advance prune-preview state; " +
                    "replaying Verify is not another PIT observation."
              !freshFullHistoryFreePreview ->
                "This unbound preview cannot qualify as fresh full history-free absence " +
                    "evidence."
              else -> {
                val observations = prunePreviewTransition?.state?.matchingObservations ?: 0
                "This preview is fresh, full, and history-free; the persisted exact-candidate " +
                    "sequence now has $observations matching distinct observation(s), subject to " +
                    "review of the relevant solo/gate load context."
              }
            }
            val observations = prunePreviewTransition?.state?.matchingObservations ?: 0
            val nextStep = if (freshFullHistoryFreePreview && observations >= 2) {
              "The mechanical two-preview prerequisite is met. After review confirms the load " +
                  "context, every removal criterion, and the absence of fresh gated rows, run:\n" +
                  "  ./gradlew $evidenceBaselinePruneTaskPath --console=plain\n"
            } else {
              "Obtain the next qualifying preview with:\n" +
                  "  ./gradlew $evidencePitestTaskPath -PnoMutationHistory --console=plain\n" +
                  "Do not run $evidenceBaselinePruneTaskPath until two completed previews match.\n"
            }
            "\n$currentObservation\nEvidence required before deletion: at least two distinct, " +
                "matching fresh full history-free previews under the relevant solo/gate " +
                "conditions, with every listed row absent, plus row-by-row confirmation that " +
                "each written removal criterion is met. The plugin does not persist or infer " +
                "that reviewed load context. $nextStep" +
                "$evidenceBaselinePruneTaskPath performs another fresh full history-free " +
                "write-boundary run and changes the baseline only from that run; review the " +
                "resulting diff."
          }
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleGone.size} row(s) are unmatched by this run; " +
                  "$candidateHeading\n" +
                  staleGone.joinToString("\n") { "  ${BaselineNotes.render(acceptedRows[it])}" } +
                  "\nOne fresh history-free absence preview cannot distinguish stable removal " +
                  "from an uninsured load- or " +
                  "mode-dependent flip. This preview is evidence to investigate, not authorization " +
                  "to shrink the record." + removalGuidance)
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
                  "refresh: prune and update keep them, and the row leaves by its written removal criterion:\n" +
                  staleInsured.joinToString("\n") {
                    "  ${BaselineNotes.render(acceptedRows[it])}"
                  })
        }
        if (staleTimedOut.isNotEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleTimedOut.size} baseline row(s) read TIMED_OUT this run — " +
                  "preserved by this run's timeout budget, not killed; prune and update keep them. " +
                  "That preservation does not prove benign load or that the acceptance argument " +
                  "still holds, because the line-less key may identify this mutant or a sibling:\n" +
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
        val provenanceRequiresRebase =
            committedProvenance.orphan ||
                committedProvenance.legacyUnbound ||
                (recordedPit != null && recordedPit != currentPit) ||
                (recordedToolchain != null && currentToolchain != null &&
                    recordedToolchain.identitySha256 != currentToolchain.identitySha256)
        fun rekeyRemediation(phaseOne: String): String =
          if (keepPlan.none { it == BaselineEngine.Disposition.DROP }) "" else
            " For an intentional same-suite key move or method rename, $phaseOne is phase one: " +
                "review the source change and carry or rewrite an old acceptance argument only " +
                "onto a generated row that review proves is its replacement; leave every other " +
                "new row '# untriaged'. Then run $evidencePitestTaskPath -PnoMutationHistory as an " +
                "ordinary fresh preview and review its full BaselinePrune candidate list. Only " +
                "afterward run $evidenceBaselinePruneTaskPath if those exact candidates remain " +
                "absent and every removal is independently justified. BaselineRetag changes line " +
                "metadata, not keys; do not substitute the complete-rewrite BaselineUpdate."
        val remediation = when {
          provenanceRequiresRebase ->
            "\nKill them with tests, or document each acceptance, then review a fresh " +
                "$evidencePitestTaskPath -PnoMutationHistory observation and run " +
                "$evidenceBaselineRebaseTaskPath. This record is not bound to the current " +
                "PIT/toolchain; Rebase preserves every old row, appends current rows as " +
                "'# untriaged', and binds the reviewed provenance." +
                rekeyRemediation(evidenceBaselineRebaseTaskPath)
          committedRecordExisted && currentToolchain == null ->
            "\nKill them with tests, or document each acceptance, then first run " +
                "$evidencePitestTaskPath -PnoMutationHistory so the current toolchain can be compared " +
                "with the record. If provenance still matches, use " +
                "$evidenceBaselineUnionTaskPath; if it changed, use " +
                "$evidenceBaselineRebaseTaskPath. Neither transition removes old rows." +
                rekeyRemediation(
                    "the applicable $evidenceBaselineUnionTaskPath or " +
                        evidenceBaselineRebaseTaskPath)
          baselineExisted ->
            "\nKill them with tests, or after documenting each acceptance run " +
                "$evidenceBaselineUnionTaskPath; Union appends the current rows as '# untriaged' " +
                "without removing unmatched evidence. BaselineUpdate is a complete report rewrite, " +
                "not remediation for this incremental gate failure (see HARDENING.md)." +
                rekeyRemediation(evidenceBaselineUnionTaskPath)
          else ->
            "\nKill them with tests, or accept knowingly by documenting each reason and running " +
                "$evidenceBaselineUpdateTaskPath to seed config/pitest/$suiteName-accepted.csv " +
                "from this first complete report (see HARDENING.md)."
        }
        throw GradleException(
            "pitest '$suiteName': ${fresh.size} unkilled mutant(s) not in the accepted baseline:" +
                detail + remediation + historyDecisionCaveat
        )
      }
      if (initTimeoutAudit) {
        // A timeout set is a committed mutation record even when the suite has no
        // accepted baseline. Bind the first successful seed to the same PIT and
        // portable toolchain identity as every baseline writer; otherwise the task
        // would create an immediately legacy-unbound record that only Rebase could
        // repair on the next invocation.
        val content = checkNotNull(pendingTimeoutAuditContent) {
          "pitest '$suiteName': timeout-audit initialization reached commit without staged content"
        }
        val writtenRecordFiles = if (!committedRecordExisted) {
          val toolchain = currentToolchain ?: throw GradleException(
              "pitest '$suiteName': current completed mutation-toolchain evidence is required to stamp records")
          // All three files form one logical record. If any rename throws, restore
          // every earlier target rather than leaving an orphan or unbound record.
          BaselineFiles.writeAllAtomically(evidenceProjectDir, listOf(
            BaselineFiles.Write(toolchainRecordFile, toolchain.render()),
            BaselineFiles.Write(toolVersionFile, currentPit + "\n"),
            BaselineFiles.Write(timeoutsFile, content),
          ))
          listOf(toolchainRecordFile, toolVersionFile, timeoutsFile)
        } else {
          BaselineFiles.writeAtomically(evidenceProjectDir, timeoutsFile, content)
          listOf(timeoutsFile)
        }
        logger.lifecycle(
            "pitest '$suiteName': seeded ${timedOutByAuditKey.size} audited-timeout member(s) into " +
                "${timeoutsFile.name} and bound its mutation provenance — this state is intentionally " +
                "uncertifiable; replace every cause:untriaged token and write each member's " +
                "structural argument in config/pitest/README.md")
        warnIgnoredMutationRecords(writtenRecordFiles)
      }
      if (certificationActive) {
        val evidence = verifiedEvidence ?: throw GradleException(
            "pitest '$suiteName': certification requires completed evidence generated in this invocation")
        try {
          certificationSession.get().recordVerified(
              evidenceProjectPath,
              suiteName,
              evidence,
              PitestEvidence.mutationRecordFingerprint(
                  evidenceProjectDir, baselineFile.parentFile, suiteName),
          )
        } catch (e: IllegalStateException) {
          throw GradleException("pitest '$suiteName': ${e.message}", e)
        }
      }
      } catch (failure: Exception) {
        val message = failure.message ?: "pitest '$suiteName': ${failure.javaClass.simpleName}"
        if (!certificationActive || message.contains("receipt is project-atomic")) throw failure
        throw GradleException(message + retryGuidance, failure)
      }
    }
    // Deliberately separate from the verification action: this action is reached only
    // after every selected transition, stamp, audit, and ratchet check above succeeds.
    // A skipped verify or a late failure therefore leaves the request unconsumed and
    // makes the public writer's typed completion task fail closed.
    doLast {
      val selected = operationSession.get().suiteOperation(evidenceProjectPath, suiteName)
      if (selected != BaselineWriteOperation.CHECK) {
        try {
          operationSession.get().recordSuiteConsumed(
              evidenceProjectPath, suiteName, selected)
        } catch (e: IllegalArgumentException) {
          throw GradleException("pitest '$suiteName': ${e.message}", e)
        }
      }
    }
  }
  hardeningCertify.configure { mustRunAfter(verify) }
  val debtTask = tasks.register<PitestDebtTask>("${pitestTaskName}Debt") {
    group = "verification"
    description = "Prints the '$suiteName' unkilled-mutant debt grouped by class, largest first, with the baseline delta."
    strictTimeoutAudit.set(strictTimeoutAuditRequested)
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val scopedCsvProvider =
        layout.buildDirectory.file("reports/pitest-scoped/$suiteName/mutations.csv")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val debtTimeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val debtToolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val debtToolchainFile =
        layout.projectDirectory.file("config/pitest/$suiteName-pitest-toolchain.tsv").asFile
    val debtProjectDirectory = layout.projectDirectory.asFile
    val debtPitVersion = hardening.pitestVersion
    val debtClassesDir = mutationClassesDir
    val debtTargets = suite.targetClasses
    val debtExcludes = allExcludedClasses
    val debtTestSourceDirs = sourceSets.test.get().java.srcDirs
    val debtSiblingTargets = suiteTargetGlobs
    val debtSiblingExcludes = suiteExcludedGlobs
    val debtDeclinedExclusions = suite.declinedExclusionAudits
    val invalidReportClosure = knownInvalidExecutionClosure(
        "\n  Retry: in a new Gradle invocation, run $evidencePitestTaskPath " +
            "-PnoMutationHistory without -PmutateOnly."
    )
    doLast {
      listOf(
          baselineFile,
          readmeFile,
          debtTimeoutsFile,
          debtToolVersionFile,
          debtToolchainFile,
      ).forEach { BaselineFiles.requireRegularFileOrMissing(debtProjectDirectory, it) }
      // This committed-files-only audit is independent of mutation provenance.
      // Run it first so a torn or malformed sidecar cannot hide a malformed,
      // unclassified, or undocumented timeout member behind its own refusal.
      val debtTimeoutMembership = debtTimeoutsFile.takeIf { it.isFile }
          ?.let { TimeoutAudit.parse(it.readLines()) }
      val debtTimeoutMalformed = debtTimeoutMembership?.malformed.orEmpty()
      val debtTimeoutCauseFindings = debtTimeoutMembership
          ?.let { TimeoutAudit.causeFindings(it, it.members) }
          .orEmpty()
      val debtTimeoutLineMetadataFindings = debtTimeoutMembership
          ?.let { TimeoutAudit.lineMetadataFindings(it, it.members) }
          .orEmpty()
      val debtTimeoutUndocumented = debtTimeoutMembership
          ?.let { membership ->
            TimeoutAudit.undocumentedCauses(membership.members) {
              readmeFile.takeIf { it.isFile }?.readText() ?: ""
            }
          }
          .orEmpty()
      debtTimeoutMembership?.let { membership ->
        TimeoutAudit.malformedWarning(suiteName, debtTimeoutsFile.name, debtTimeoutMalformed)
            ?.let { logger.warn(it) }
        if (debtTimeoutCauseFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.causeFindingWarning(
              suiteName, debtTimeoutsFile.name, debtTimeoutCauseFindings))
        }
        if (debtTimeoutLineMetadataFindings.isNotEmpty()) {
          logger.warn(TimeoutAudit.lineMetadataWarning(
              suiteName, debtTimeoutsFile.name, debtTimeoutLineMetadataFindings))
        }
        if (debtTimeoutUndocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(
              suiteName, debtTimeoutUndocumented))
        }
      }
      val strictDebtRequested = (this as PitestDebtTask).strictTimeoutAudit.get()
      val strictDebtFailure = if (strictDebtRequested &&
          (debtTimeoutMalformed.isNotEmpty() || debtTimeoutCauseFindings.isNotEmpty() ||
              debtTimeoutUndocumented.isNotEmpty())) {
        fun countedDebtNoun(
          count: Int,
          singular: String,
          plural: String = "${singular}s",
        ): String = "$count ${if (count == 1) singular else plural}"
        val findings = listOf(
            countedDebtNoun(debtTimeoutMalformed.size, "malformed membership row"),
            countedDebtNoun(
                debtTimeoutCauseFindings.size,
                "inadmissible or unfinished cause classification",
            ),
            if (debtTimeoutUndocumented.size == 1) {
              "1 member without a README cause"
            } else {
              "${debtTimeoutUndocumented.size} members without README causes"
            },
        )
        "pitest '$suiteName' debt: -PstrictTimeoutAudit refuses the committed-file preview " +
            "because it found ${findings.joinToString(", ")}:\n" +
            "  Evidence: The warnings above list every affected row and member. Debt checked " +
            "committed files only; this invocation did not run PIT.\n" +
            "  Review: Report-dependent strict findings remain unevaluated.\n" +
            "  Remedy: Resolve each committed-file finding, then run " +
            "$evidencePitestTaskPath -PstrictTimeoutAudit for the full report-dependent audit."
      } else {
        null
      }
      if (strictDebtRequested && strictDebtFailure == null) {
        logger.lifecycle(
            "pitest '$suiteName' debt: -PstrictTimeoutAudit committed-file preview is clean. " +
                "Report-dependent strict checks require a full $evidencePitestTaskPath " +
                "-PstrictTimeoutAudit run; this Debt invocation did not run PIT.")
      }
      // Committed-files-only, like the timeout audit immediately above — which makes
      // Debt the quick place a plugin release that bumps PIT surfaces per consumer repo,
      // before anyone reads tool churn as code churn.
      val debtHasRecord = baselineFile.isFile || debtTimeoutsFile.isFile
      val debtProvenance = CommittedMutationProvenance.classify(
          debtHasRecord,
          debtToolVersionFile.takeIf { it.isFile }?.readText(),
          debtToolchainFile.takeIf { it.isFile }?.readText(),
      )
      debtProvenance.malformedPitVersion?.let { detail ->
        throw GradleException(
            "pitest '$suiteName' debt: malformed committed PIT-version stamp at " +
                "$debtToolVersionFile — $detail; repair it with $evidenceBaselineRebaseTaskPath")
      }
      debtProvenance.malformedToolchain?.let { detail ->
        throw GradleException(
            "pitest '$suiteName' debt: malformed committed mutation-toolchain record at " +
                "$debtToolchainFile — $detail; repair it with $evidenceBaselineRebaseTaskPath")
      }
      if (debtProvenance.orphan) {
        throw GradleException(
            "pitest '$suiteName' debt: mutation-provenance sidecar(s) exist without an accepted " +
                "or timeout record; repair the orphan state with $evidenceBaselineRebaseTaskPath")
      }
      if (debtProvenance.torn) {
        throw GradleException(
            "pitest '$suiteName' debt: committed mutation provenance is torn — exactly one of " +
                "${debtToolVersionFile.name} and ${debtToolchainFile.name} exists; repair it with " +
                evidenceBaselineRebaseTaskPath)
      }
      if (debtProvenance.disagreement) {
        throw GradleException(
            "pitest '$suiteName' debt: committed provenance disagrees — " +
                "${debtToolVersionFile.name} says PIT ${debtProvenance.pitVersion}, but " +
                "${debtToolchainFile.name} says PIT " +
                "${checkNotNull(debtProvenance.toolchain).pitestVersion}; repair it with " +
                evidenceBaselineRebaseTaskPath)
      }
      if (debtProvenance.legacyUnbound) {
        logger.warn(
            "pitest '$suiteName': committed mutation record is legacy-unversioned; its PIT " +
                "version is unknown — review a history-free $evidencePitestTaskPath " +
                "-PnoMutationHistory observation, then run " +
                evidenceBaselineRebaseTaskPath)
        logger.warn(
            "pitest '$suiteName': committed mutation record is legacy-toolchain-unbound; " +
                "ArcMutate/PIT tool changes cannot be distinguished from code churn — review a " +
                "history-free $evidencePitestTaskPath -PnoMutationHistory observation, then run " +
                evidenceBaselineRebaseTaskPath)
      }
      debtProvenance.toolchain?.let { recordedToolchain ->
        val effectiveToolchain = try {
          (this as PitestDebtTask).currentEvidence.captureMutationToolchain()
        } catch (e: Exception) {
          throw GradleException(
              "pitest '$suiteName' debt: could not identify the current mutation toolchain — " +
                  "${e.message}", e)
        }
        if (recordedToolchain.identitySha256 != effectiveToolchain.identitySha256) {
          logger.warn(
              "pitest '$suiteName': committed mutation toolchain differs from the current " +
                  "PIT/ArcMutate/licence identity — population differences may be tool churn; " +
                  "review a history-free $evidencePitestTaskPath -PnoMutationHistory observation, then run " +
                  evidenceBaselineRebaseTaskPath)
        }
      }
      val recordedPit = debtProvenance.pitVersion
      if (recordedPit != null && recordedPit != debtPitVersion.get()) {
        logger.warn(
            "pitest '$suiteName': baseline record written by PIT $recordedPit, this plugin runs " +
                "PIT ${debtPitVersion.get()} — population differences may be the tool, not the code; " +
                "review a history-free $evidencePitestTaskPath -PnoMutationHistory observation, then run " +
                evidenceBaselineRebaseTaskPath
        )
      }
      // The exclusion audit's static half, mirroring the timeout audit's: the
      // policy is pure given recompiled classes, so when a prior run left
      // build/mutation-classes behind it is checkable here without a mutation
      // run — which puts it in the quick read-only Debt surface where a plugin
      // release meets real consumer globs. Its in-run half only fired inside a
      // real 'pitest<Suite>' execution, and that blind spot shipped a release *(casebook:
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

      // BaselineDocument validates the schema before Debt interprets a row, so a
      // read-only diagnostic cannot report green on a marker that verify would
      // reject. Malformed rows remain named on verify's terms and excluded from
      // both the tally and label breakdown.
      val baselineDocument = BaselineDocument.parse(
          if (baselineFile.isFile) baselineFile.readText() else "")
      val malformedRows = baselineDocument.malformedRows.map { it.raw }
      if (malformedRows.isNotEmpty()) {
        logger.warn(
            "pitest '$suiteName': ${malformedRows.size} malformed row(s) in ${baselineFile.name} — " +
                "expected 'class,method,mutator,STATUS [# note] [# line N]'; a malformed row matches " +
                "no mutant, and blocks every baseline rewrite:\n" +
                malformedRows.joinToString("\n") { "  $it" }
        )
      }
      val invalidLineMetadataRows = baselineDocument.invalidLineMetadataRows
      if (invalidLineMetadataRows.isNotEmpty()) {
        logger.warn(
            "pitest '$suiteName': ${invalidLineMetadataRows.size} accepted row(s) in " +
                "${baselineFile.name} carry invalid diagnostic line metadata. Their keys still " +
                "count in this read-only tally, but ranges are not observed-line evidence and a " +
                "row-rewriting writer will refuse them:\n" +
                invalidLineMetadataRows.joinToString("\n") { row ->
                  "  line ${row.lineNumber}: ${row.raw}\n    " +
                      RecordedLineMetadata.acceptedInvalidDetail(
                          checkNotNull(row.value.invalidLineMetadata))
                })
      }
      val wellFormedRows = baselineDocument.rows
      val baselinePairs = wellFormedRows
          .map { it.key.split(',') }
          .filter { it.size >= 4 }
          .map { it[0] to it.last() }
      val csv = csvProvider.get().asFile
      val scopedCsv = scopedCsvProvider.get().asFile
      BaselineFiles.requireRegularFileOrMissing(csv)
      BaselineFiles.requireRegularFileOrMissing(scopedCsv)
      val historyAssistedDebt = csv.parentFile.resolve(".history-assisted").isFile
      val newerScopedDiagnostic = scopedCsv.isFile &&
          scopedCsv.parentFile.resolve(".scoped").isFile &&
          !scopedCsv.parentFile.resolve(".running").isFile &&
          (!csv.isFile || scopedCsv.lastModified() >= csv.lastModified())
      var invalidReport = false
      // scoped and interrupted-run reports both fall back to the baseline: a
      // partial population under-counts debt exactly like a hand-picked one. An
      // error/unfinished report is equally unusable as a tally, but Debt remains
      // the read-only triage surface after a failed PIT run: name every offending
      // mutant, then fall back to the committed baseline instead of disabling the
      // next diagnostic the failure calls for.
      val reportRows = if (csv.isFile &&
          !csv.parentFile.resolve(".scoped").isFile &&
          !csv.parentFile.resolve(".running").isFile) {
        try {
          Mutant.parseReport(csv.readLines(), invalidReportClosure)
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
      val reportPairs = reportRows
          ?.filter { it.gated }
          ?.map { it.className to it.rawStatus }
      val source = when {
        reportPairs != null && historyAssistedDebt && newerScopedDiagnostic ->
          "latest full [history] report (read-only preview; newer scoped diagnostic excluded)"
        reportPairs != null && historyAssistedDebt ->
          "latest full [history] report (read-only preview)"
        reportPairs != null && newerScopedDiagnostic ->
          "latest full report (newer scoped diagnostic excluded)"
        reportPairs != null -> "latest full report"
        invalidReport && newerScopedDiagnostic ->
          "baseline (full report invalid; newer scoped diagnostic excluded)"
        invalidReport -> "baseline (full report invalid)"
        newerScopedDiagnostic -> "baseline (no full report present; scoped diagnostic excluded)"
        else -> "baseline (no full report present)"
      }
      if (reportRows != null && debtTimeoutMembership != null) {
        TimeoutAudit.memberPopulationDetail(
            suiteName,
            TimeoutAudit.memberPopulations(reportRows, debtTimeoutMembership.members),
            "$source. This is an unverified read-only prior-report preview; run " +
                "$evidencePitestTaskPath -PnoMutationHistory before any timeout-cause decision",
        )?.let { logger.lifecycle(it) }
      }
      val debt = tally(reportPairs ?: baselinePairs)
      val baselineDebt = tally(baselinePairs)
      if (reportPairs != null && historyAssistedDebt) {
        logger.warn(
            "pitest '$suiteName' debt: the current report is history-assisted and check-only — " +
                "run $evidencePitestTaskPath -PnoMutationHistory before any accepted-baseline or " +
                "timeout-audit decision.")
      }
      if (debt.isEmpty()) {
        logger.lifecycle("pitest '$suiteName' debt: none — nothing unkilled in the $source")
        strictDebtFailure?.let { throw GradleException(it) }
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
        if (minutes < 2) "" else if (historyAssistedDebt) {
          ", ${minutes}m old — rerun $evidencePitestTaskPath -PnoMutationHistory before decisions"
        } else {
          ", ${minutes}m old — rerun $evidencePitestTaskPath if stale"
        }
      }
      // Label breakdown from the baseline (the well-formed rows parsed above):
      // triaged-accepted rows carry a family label, seeded debt reads '# untriaged',
      // and unlabeled rows predate seeding.
      val baselineNotes = wellFormedRows.mapNotNull { it.note }
      val labelBreakdown = BaselineNotes.summarize(baselineNotes, wellFormedRows.size - baselineNotes.size)
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
      strictDebtFailure?.let { throw GradleException(it) }
    }
  }

  qualityGate.configure { dependsOn(pitestTaskName) }
  convergeSuiteNames.add(suiteName)
  certificationSuiteNames.add(suiteName)
  pitestConvergeSnapshot.configure {
    dependsOn(pitestTaskName)
    // The PIT task's verification finalizer reads the canonical report. A plain
    // dependency does not order that finalizer before another dependent task, so a
    // multi-suite converge graph could snapshot/clear the report first and make the
    // finalizer fail with "no PIT report". The snapshot is the round boundary: it
    // follows both the producer and its report consumer for every suite.
    mustRunAfter("${pitestTaskName}Verify")
  }
  // ordered, not depended on: a combined '<suites> pitestModeSnapshot' invocation must
  // not stash before the runs finish — or clear a report the verify finalizer still reads
  pitestModeSnapshot.configure { mustRunAfter(pitestTaskName, "${pitestTaskName}Verify") }

  // Keep the public tasks JavaExec-compatible for the explicitly mirrored launcher,
  // tool-classpath, and main-class surface while moving their complete process
  // lifecycle into typed classes. Direct PIT args/providers are refused because they
  // bypass the evidence identity. Configuration here is provider wiring only; task
  // execution no longer reaches through this precompiled script.
  val typedMutateOnly = mutationScopeProperty
  val typedPluginCode = evidencePluginCode

  fun configureTypedPitest(
      task: PitestExecTask,
      reportSubdir: String,
      mutatorsSource: Provider<String>,
      withHistory: Boolean,
      enforceExit: Boolean = true,
      bindSuiteEvidence: Boolean = true,
      isolateScopedReport: Boolean = false,
  ) {
    task.dependsOn(compileForPitest)
    // Runtime-only project artifacts must be scheduled before the task action
    // fingerprints or opens them.
    task.dependsOn(evidenceClasspathFiles)
    task.dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    task.javaLauncher.convention(defaultPitestJavaLauncher)
    task.classpath = pitest
    task.mustRunAfter(hardeningCertifyPreflight)

    task.usesService(hardeningExecutionLock)
    task.usesService(hardeningCertificationSession)
    task.usesService(hardeningOperationSession)
    task.executionLock.set(hardeningExecutionLock)
    task.certificationSession.set(hardeningCertificationSession)
    task.operationSession.set(hardeningOperationSession)

    task.suiteName.set(suiteName)
    task.targetClasses.set(suite.targetClasses)
    task.excludedClasses.set(allExcludedClasses)
    task.targetTests.set(validatedTargetTests)
    task.excludedTestClasses.set(excludedTestGlobs)
    // Locked, unlike its neighbours, because this one carries an invariant the
    // property itself cannot state: the reason is enforced in the provider above.
    // Left settable, a consumer could put a glob straight onto the task, skip the
    // ratchet, and produce a green run and its evidence with nothing recorded
    // anywhere saying why those tests did not get to kill anything.
    task.excludedTestClasses.disallowChanges()
    task.targetTests.disallowChanges()
    task.mutators.set(mutatorsSource)
    task.threads.set(suite.threads)
    task.minionJvmArgs.set(suite.minionJvmArgs)
    task.timeoutFactor.set(suite.timeoutFactor)
    task.timeoutConst.set(suite.timeoutConst)
    task.mutateOnly.set(typedMutateOnly)

    // setFrom, not from: a `configureEach` registered before this plugin applied has
    // already run by now, and `from` would keep whatever it added — the lock below
    // would then pin a value nobody here chose rather than the wiring above.
    task.applicationClasspath.setFrom(mutationClassesDir, evidenceClasspathFiles)
    task.sourceDirectories.setFrom(layout.projectDirectory.dir("src/main/java"))
    task.reportDirectory.set(layout.buildDirectory.dir("reports/pitest/$reportSubdir"))
    task.scopedReportDirectory.set(layout.buildDirectory.dir(
        if (isolateScopedReport) "reports/pitest-scoped/$reportSubdir"
        else "reports/pitest/$reportSubdir"))
    // ArcMutate's fallback certificate lookup begins at the child JVM's working
    // directory. Pin it so the effective resolver and recorded identity cannot be
    // redirected by an ambient/consumer JavaExec default.
    task.workingDir(layout.projectDirectory.asFile)
    task.historyFile.set(layout.projectDirectory.file(".pitest-history/${suite.name}.hist"))
    task.historyRequested.set(withHistory)
    task.historyLicensed.set(arcMutateLicencePresent)
    task.historyExplicitlyDisabled.set(mutationHistoryDisabledForExecution)
    task.enforceExit.set(enforceExit)
    task.bindSuiteEvidence.set(bindSuiteEvidence)

    task.certifyingProjectPath.set(project.path)
    task.evidenceProjectDirectory.set(layout.projectDirectory)
    task.evidenceSourceFiles.from(evidenceSourceFiles)
    task.evidenceClassFiles.from(evidenceClassFiles)
    task.evidenceClasspath.from(evidenceClasspathFiles)
    task.evidencePluginCode.from(typedPluginCode)
    task.expectedPluginSha256.set(hardeningExpectedPluginSha256)
    task.localRepoArtifactPath.set(hardeningLocalRepoArtifactPath)
    task.expectedLocalRepoArtifactSha256.set(hardeningExpectedLocalRepoArtifactSha256)
    task.pitestVersion.set(hardening.pitestVersion)
    task.junitPluginVersion.set(hardening.pitestJunit5PluginVersion)
    task.arcMutateBaseVersion.set(hardening.arcmutateBaseVersion)
    task.mutationBytecodeRelease.set(hardening.mutationBytecodeRelease)
    task.recompileExcludes.set(hardening.recompileExcludes)

    // Set explicitly and then locked, in that order and last. These reach PIT's
    // command line but have no field of their own in the configuration text, so a
    // late change to any alters the run while the evidence identity stays
    // byte-identical. `applicationClasspath` is the sharpest: it becomes --classPath,
    // and the identity binds a parallel file collection rather than the one PIT is
    // handed. Locking serializes nothing, so unlike binding them as new fields it
    // moves no recorded hash.
    //
    // The two scalars are set rather than left on their convention for the same
    // reason the collections use setFrom: a value a `configureEach` put there before
    // this ran would otherwise be what the lock preserves.
    //
    task.outputFormats.set(listOf("HTML", "XML", "CSV"))
    task.timestampedReports.set(false)
    task.applicationClasspath.disallowChanges()
    task.sourceDirectories.disallowChanges()
    task.outputFormats.disallowChanges()
    task.timestampedReports.disallowChanges()
  }

  val runAfter = previousPitestTask
  previousPitestTask = pitestTaskName
  val pitestRun = tasks.register<PitestRunTask>(pitestTaskName) {
    dependsOn(timeoutAuditPreflight)
    finalizedBy(verify)
    runAfter?.let { mustRunAfter(it) }
    group = "verification"
    description = "PIT mutation testing of the '${suite.name}' classes against their tests."
    mustRunAfter(
        hardeningCertifyTimeoutAuditPreflight,
        pitestModeCompareUnionPreflight,
        migrateMutationBaselinesPreflight,
        downgradeMutationBaselinesPreflight)
    configureTypedPitest(
        this,
        suiteName,
        suite.mutators,
        withHistory = true,
        isolateScopedReport = true,
    )
    mutationUnitSize.set(isolateMutantsRequested.map { isolated -> if (isolated) 1 else 0 })
    historyExplicitlyDisabled.set(mutationHistoryDisabledForNormalPitest)

    adviceClassesDirectory.from(mutationClassesDir)
    adviceTestSourceDirectories.from(sourceSets.test.get().java.srcDirs)
    adviceSiblingTargets.set(suiteTargetGlobs)
    adviceSiblingExcludes.set(suiteExcludedGlobs)
    adviceDeclinedMutators.set(suite.declinedMutators)
    adviceDeclinedExclusions.set(suite.declinedExclusionAudits)
    // Locked here rather than in the shared helper, which also configures the
    // diagnostic and converge tasks that set their own report paths afterwards.
    // `reportDirectory` was the one surface advertised as customizable on this task,
    // and it does not work: the ratchet reads a fixed path, so a relocated report
    // reads as a run that never happened. An advertised surface that breaks the gate
    // is worse than no surface, and the diagnostic task already locks its own.
    reportDirectory.disallowChanges()
    adviceTrialTaskPath.set(
        if (project.path == ":") ":pitestMutatorTrial" else "${project.path}:pitestMutatorTrial")
    adviceAdvisoryScope.set(suiteAdvisoryScope)
    usesService(hardeningAdvisoryLog)
    advisoryLog.set(hardeningAdvisoryLog)
  }
  // Realize only the normal typed task object, never either classpath's files. The
  // stable mirror observes later JavaExec `classpath = ...` customization in place;
  // the launcher property likewise follows a later task-level override.
  val evidencePitestTask = pitestRun.get()
  fun PitestExecTask.mirrorNormalPitestProcess() {
    javaLauncher.set(evidencePitestTask.javaLauncher)
    classpath = evidencePitestTask.effectiveToolClasspath
    mainClass.set(evidencePitestTask.mainClass)
    dependsOn(evidencePitestTask.effectiveToolClasspath.buildDependencies)
  }
  fun configureEvidenceSpec(
    spec: PitestEvidenceSpec,
    isolateScopedReport: Boolean,
  ) {
    spec.suiteName.set(suiteName)
    spec.projectPath.set(evidenceProjectPath)
    spec.projectDirectory.set(layout.projectDirectory)
    spec.reportDirectory.set(evidencePitestTask.reportDirectory)
    spec.scopedReportDirectory.set(
        if (isolateScopedReport) evidencePitestTask.scopedReportDirectory
        else evidencePitestTask.reportDirectory)
    spec.mutateOnly.set(typedMutateOnly)
    spec.pluginCode.from(evidencePluginCode)
    spec.expectedPluginSha256.set(hardeningExpectedPluginSha256)
    spec.localRepoArtifactPath.set(hardeningLocalRepoArtifactPath)
    spec.expectedLocalRepoArtifactSha256.set(hardeningExpectedLocalRepoArtifactSha256)
    spec.sourceFiles.from(evidenceSourceFiles)
    spec.classFiles.from(evidenceClassFiles)
    spec.runtimeClasspath.from(evidenceClasspathFiles)
    spec.toolClasspath.from(evidencePitestTask.effectiveToolClasspath)
    spec.javaLauncher.set(evidencePitestTask.javaLauncher)
    spec.pitestVersion.set(evidencePitestVersion)
    spec.junitPluginVersion.set(evidenceJunitPluginVersion)
    spec.arcMutateBaseVersion.set(evidenceArcMutateBaseVersion)
    spec.arcMutateLicensed.set(arcMutateLicencePresent)
    spec.targetClasses.set(suite.targetClasses)
    spec.excludedClasses.set(allExcludedClasses)
    spec.targetTests.set(validatedTargetTests)
    spec.excludedTestClasses.set(excludedTestGlobs)
    // Same lock on the evidence side, and this is the sharper of the two. A verify
    // spec set apart from the task fails on a configuration mismatch anyway; what a
    // settable spec really buys is the reverse — re-pointing the *validator* back at
    // a record set the suite has since changed makes the recorded configuration match
    // again, so a superseded report passes as current evidence.
    spec.excludedTestClasses.disallowChanges()
    spec.targetTests.disallowChanges()
    spec.mutators.set(suite.mutators)
    spec.threads.set(suite.threads)
    spec.minionJvmArgs.set(suite.minionJvmArgs)
    spec.timeoutFactor.set(suite.timeoutFactor)
    spec.timeoutConst.set(suite.timeoutConst)
    if (isolateScopedReport) {
      spec.mutationUnitSize.set(evidencePitestTask.mutationUnitSize)
    } else {
      spec.mutationUnitSize.set(0)
    }
    spec.verbosity.set(evidencePitestTask.verbosity)
    spec.mainClass.set(evidencePitestTask.mainClass)
    spec.mutationBytecodeRelease.set(evidenceMutationRelease)
    spec.recompileExcludes.set(evidenceRecompileExcludes)
  }

  verify.configure {
    configureEvidenceSpec(finalEvidence, isolateScopedReport = true)
  }
  debtTask.configure {
    configureEvidenceSpec(currentEvidence, isolateScopedReport = false)
    dependsOn(evidencePitestTask.effectiveToolClasspath.buildDependencies)
  }

  fun registerEvidenceValidator(name: String, prefix: String, standaloneRetry: String) =
    tasks.register<PitestEvidenceValidationTask>(name) {
      description = "Internal: revalidates current completed evidence for PIT suite '$suiteName'."
      configureEvidenceSpec(evidence, isolateScopedReport = true)
      diagnosticPrefix.set(prefix)
      this.standaloneRetry.set(standaloneRetry)
      certificationSession.set(hardeningCertificationSession)
      usesService(hardeningCertificationSession)
      mustRunAfter(pitestRun)
      // Every fingerprinted producer must exist before the execution-time read. The
      // graph remains invariant when the report has no manifest; dependsOn a file
      // collection follows its build dependencies without opening external PIT jars.
      dependsOn(compileForPitest, evidenceClasspathFiles)
      dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
      dependsOn(evidencePitestTask.effectiveToolClasspath.buildDependencies)
    }

  val verifyEvidenceValidation = registerEvidenceValidator(
      "${pitestTaskName}EvidenceValidate",
      "pitest '$suiteName': completed report evidence no longer matches the current build — " +
          "a stale report cannot verify or rewrite mutation state:\n",
      "run $evidencePitestTaskPath in a new Gradle invocation.")
  val modeSnapshotEvidenceValidation = registerEvidenceValidator(
      "${pitestTaskName}ModeEvidenceValidate",
      "pitestModeSnapshot: '$suiteName' report/evidence pair no longer matches the current build:\n",
      "re-run the affected suite in the intended mode, then run " +
          "${qualifiedHardeningTaskPath(evidenceProjectPath, "pitestModeSnapshot")} with the " +
          "same -PpitestMode label.").also {
    it.configure { fullEvidenceOnly.set(true) }
  }
  pitestModeCompareCommit.configure {
    configureEvidenceSpec(
        suiteEvidence.maybeCreate(suiteName),
        isolateScopedReport = false,
    )
    dependsOn(compileForPitest, evidenceClasspathFiles)
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    dependsOn(evidencePitestTask.effectiveToolClasspath.buildDependencies)
    mustRunAfter(pitestModeCompareUnionPreflight)
  }
  // Keep the graph invariant whether completed evidence exists or not. The validators
  // inspect the manifest at execution time and return before realizing their @Internal
  // file collections on the N-1/no-manifest path. A configuration-time existence
  // branch here made PIT's own creation (and clean's removal) of .evidence.tsv
  // invalidate otherwise reusable configuration-cache entries.
  verify.configure { dependsOn(verifyEvidenceValidation) }
  pitestModeSnapshot.configure { dependsOn(modeSnapshotEvidenceValidation) }
  hardeningCertify.configure {
    configureEvidenceSpec(
        suiteEvidence.maybeCreate(suiteName),
        isolateScopedReport = false,
    )
    certificationEvidenceClasspaths.from(
        evidenceClasspathFiles,
        evidencePitestTask.effectiveToolClasspath,
    )
    certificationRecordFiles.from(
        PitestEvidence.mutationRecordFiles(layout.projectDirectory.dir("config/pitest").asFile, suiteName))
  }

  // Named writer workflows turn the mutually-exclusive baseline transition into
  // task-graph structure. Every selected preflight is ordered before PIT; a conflict
  // poisons the project so `--continue` cannot consume the surviving request. The
  // public completion task and verify's own no-exclusions check additionally make an
  // explicitly excluded preflight a refusal rather than a green no-op or record write.
  val presentWriterIncompatibleProperties = listOf(
      HardeningOptionNames.ISOLATE_MUTANTS,
      HardeningOptionNames.MUTATE_ONLY,
  )
      .filter { providers.gradleProperty(it).isPresent }
  val requestedExcludedTaskNames = gradle.startParameter.excludedTaskNames.sorted()
  val writerSuiteName = suiteName
  fun registerSuiteWriter(
      taskSuffix: String,
      requestValue: HardeningWriteRequest,
      taskDescription: String,
  ) {
    val publicName = "$pitestTaskName$taskSuffix"
    val preflight = tasks.register<HardeningOperationRequestTask>("${publicName}Preflight") {
      description = "Internal to $publicName: selects one full-evidence record transition."
      hardeningProjectPath.set(evidenceProjectPath)
      this.suiteName.set(writerSuiteName)
      request.set(requestValue)
      presentIncompatibleProperties.set(presentWriterIncompatibleProperties)
      excludedTaskNames.set(requestedExcludedTaskNames)
      operationSession.set(hardeningOperationSession)
      certificationSession.set(hardeningCertificationSession)
      usesService(hardeningOperationSession)
      usesService(hardeningCertificationSession)
      mustRunAfter(hardeningCertifyPreflight)
    }
    if (requestValue == HardeningWriteRequest.BASELINE_REBASE) {
      timeoutAuditPreflight.configure { mustRunAfter(preflight) }
    }
    pitestRun.configure { mustRunAfter(preflight) }
    pitestModeCompare.configure { mustRunAfter(preflight) }
    migrateMutationBaselines.configure { mustRunAfter(preflight) }
    downgradeMutationBaselines.configure { mustRunAfter(preflight) }
    tasks.register<HardeningOperationCompletionTask>(publicName) {
      group = "verification"
      description = taskDescription
      hardeningProjectPath.set(evidenceProjectPath)
      this.suiteName.set(writerSuiteName)
      request.set(requestValue)
      operationSession.set(hardeningOperationSession)
      usesService(hardeningOperationSession)
      dependsOn(preflight)
      dependsOn(pitestRun)
      dependsOn(verify)
    }
  }
  registerSuiteWriter(
      "BaselineRebase",
      HardeningWriteRequest.BASELINE_REBASE,
      "Runs '$suiteName' fresh, preserves old evidence, and adopts reviewed PIT/toolchain provenance.")
  registerSuiteWriter(
      "BaselineUpdate",
      HardeningWriteRequest.BASELINE_UPDATE,
      "Runs '$suiteName' fresh and performs a complete report rewrite; may remove unmatched rows while preserving current timeout/flip evidence.")
  registerSuiteWriter(
      "BaselineUnion",
      HardeningWriteRequest.BASELINE_UNION,
      "Runs '$suiteName' fresh and appends only newly observed accepted rows.")
  registerSuiteWriter(
      "BaselineRetag",
      HardeningWriteRequest.BASELINE_RETAG,
      "Runs '$suiteName' fresh and refreshes matched line metadata without adding or removing rows.")
  registerSuiteWriter(
      "BaselinePrune",
      HardeningWriteRequest.BASELINE_PRUNE,
      "Runs '$suiteName' fresh and applies its reviewed shrink-only candidate set.")
  registerSuiteWriter(
      "TimeoutAuditInit",
      HardeningWriteRequest.TIMEOUT_AUDIT_INIT,
      "Runs '$suiteName' fresh and seeds its audited timeout membership.")

  // One-shot verbose diagnosis is deliberately a separate report/evidence world.
  // It shares the suite's actual classes, tests, mutators, launcher and execution
  // lock, but cannot replace or verify any decision-grade observation.
  val diagnosticTaskName = "${pitestTaskName}Diagnostic"
  tasks.register<PitestDiagnosticTask>(diagnosticTaskName) {
    group = "verification"
    description =
      "Runs verbose, history-free '$suiteName' PIT diagnostics without producing mutation evidence."
    configureTypedPitest(
      this,
      suiteName,
      suite.mutators,
      withHistory = false,
      bindSuiteEvidence = false,
      isolateScopedReport = true,
    )
    // Diagnostic output must explain the normal task's actual process, including
    // the late JavaExec customizations the evidence model explicitly supports.
    mirrorNormalPitestProcess()
    reportDirectory.set(layout.buildDirectory.dir("reports/pitest-diagnostic/$suiteName"))
    scopedReportDirectory.set(
      layout.buildDirectory.dir("reports/pitest-diagnostic-scoped/$suiteName"))
    // Unlike the ordinary JavaExec-compatible task, a diagnostic may never be
    // redirected onto decision-grade suite output.
    reportDirectory.disallowChanges()
    scopedReportDirectory.disallowChanges()
    javaLauncher.disallowChanges()
    mainClass.disallowChanges()
    effectiveToolClasspath.disallowChanges()
  }

  // The convergence second round reuses the normal suite report path but has no
  // ratchet finalizer. Its typed preflight refuses certification before touching
  // the attempt sentinel.
  val round2Name = "${pitestTaskName}ConvergeRound2"
  val round2After = previousRound2Task
  previousRound2Task = round2Name
  tasks.register<PitestConvergeTask>(round2Name) {
    description = "Internal to pitestConverge: second '${suite.name}' PIT run for the per-mutant diff."
    mustRunAfter(pitestConvergeSnapshot)
    round2After?.let { mustRunAfter(it) }
    configureTypedPitest(this, suiteName, suite.mutators, withHistory = true)
    mirrorNormalPitestProcess()
    verbosity.set(evidencePitestTask.verbosity)
  }
  hardeningCertify.configure { mustRunAfter(round2Name) }
  pitestConverge.configure { dependsOn(round2Name) }

  // Candidate-mutator measurement uses an isolated report, disables history and
  // completed-suite evidence, and tolerates PIT's zero-fire non-zero exit.
  val trialTaskName = "${pitestTaskName}MutatorTrial"
  val trialAfter = previousTrialTask
  previousTrialTask = trialTaskName
  tasks.register<PitestMutatorTrialTask>(trialTaskName) {
    description = "Internal to pitestMutatorTrial: '${suite.name}' with only the -PtrialMutators candidates."
    trialAfter?.let { mustRunAfter(it) }
    configureTypedPitest(
        this,
        "$suiteName-trial",
        trialMutatorsProperty,
        withHistory = false,
        enforceExit = false,
        bindSuiteEvidence = false,
    )
    // A trial changes the candidate mutator set, not the process used to measure it.
    // Match every supported late suite-task customization so the generated counts
    // can inform a durable human decision about the suite's real configuration.
    mirrorNormalPitestProcess()
    verbosity.set(evidencePitestTask.verbosity)
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
    val adoptLocalCorpus =
        providers.gradleProperty(HardeningOptionNames.ADOPT_LOCAL_CORPUS).isPresent
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

  val fuzzTaskName = "fuzz" + target.name.replaceFirstChar(Char::uppercase)
  tasks.register<FuzzRunTask>(fuzzTaskName) {
    group = "verification"
    description = "Coverage-guided fuzzing of the '${target.name}' target with Jazzer; -PmaxFuzzTime=<seconds> (default 60)."
    mustRunAfter(fuzzAll)
    dependsOn(seedLenCheck)
    dependsOn(validateFuzzBudget)
    dependsOn(compileForFuzz)
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))

    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }

    targetName.set(target.name)
    targetClass.set(target.targetClass)
    maxFuzzTimeSeconds.set(maxFuzzTime.map { it.toInt() })
    campaignProjectPath.set(project.path)
    maxLen.set(target.maxLen)
    localCorpus.set(layout.buildDirectory.dir("fuzz/${target.name}-corpus"))
    seedCorpus.set(target.seedCorpus)
    fuzzSession.set(hardeningFuzzSession)
    usesService(hardeningFuzzSession)
    executionSlots.set(hardeningFuzzExecutionSlots)
    usesService(hardeningFuzzExecutionSlots)
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
  tasks.register<FuzzMinimizeTask>(
      "fuzz" + target.name.replaceFirstChar(Char::uppercase) + "Minimize"
  ) {
    group = "verification"
    description = "Minimizes the '${target.name}' seed corpus with libFuzzer -merge=1; -PadoptLocalCorpus also folds in inputs found by local fuzz runs."
    dependsOn(seedLenCheck)
    dependsOn(compileForFuzz)
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))

    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }

    targetName.set(target.name)
    targetClass.set(target.targetClass)
    maxLen.set(target.maxLen)
    seedCorpus.set(target.seedCorpus)
    stagingCorpus.set(layout.buildDirectory.dir("fuzz/${target.name}-minimized"))
    localCorpus.set(layout.buildDirectory.dir("fuzz/${target.name}-corpus"))
    adoptLocalCorpus.set(
        providers.gradleProperty(HardeningOptionNames.ADOPT_LOCAL_CORPUS).isPresent)
    executionLock.set(hardeningExecutionLock)
    usesService(hardeningExecutionLock)
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
    BaselineFiles.deleteRecursivelyIfExists(dir)
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
  val initProjectDirectory = layout.projectDirectory.asFile
  val gitignore = rootProject.layout.projectDirectory.file(".gitignore").asFile
  val initRootProjectDirectory = rootProject.layout.projectDirectory.asFile
  val digest = HardeningTemplateDigest.SHA256_12
  val initHelpTaskPath = qualifiedHardeningTaskPath(project.path, "hardeningHelp")
  val initTemplateTaskPath = qualifiedHardeningTaskPath(project.path, "hardeningAgentTemplate")
  val initTemplateDiffTaskPath =
      qualifiedHardeningTaskPath(project.path, "hardeningAgentTemplateDiff")
  val initBaselineUpdateTaskPath =
      qualifiedHardeningTaskPath(project.path, "pitest<Suite>BaselineUpdate")
  val initTimeoutAuditTaskPath =
      qualifiedHardeningTaskPath(project.path, "pitest<Suite>TimeoutAuditInit")
  val initCertifyTaskPath = qualifiedHardeningTaskPath(project.path, "hardeningCertify")
  val initFuzzAllTaskPath = qualifiedHardeningTaskPath(project.path, "fuzzAll")
  doLast {
    BaselineFiles.requireRegularFileOrMissing(initProjectDirectory, readme)
    BaselineFiles.requireRegularFileOrMissing(initRootProjectDirectory, gitignore)
    if (readme.isFile) {
      logger.lifecycle("hardeningInit: $readme exists — left untouched")
    } else {
      readme.parentFile.mkdirs()
      BaselineFiles.writeAtomically(initProjectDirectory, readme,
          """
          |# Mutation hardening evidence
          |
          |This file contains repository-specific evidence and decisions only. Run
          |`./gradlew $initHelpTaskPath` for the exact mechanics installed in this checkout,
          |and `./gradlew $initTemplateTaskPath` for the version-matched agent contract.
          |The portable decision policy lives in sava-build's `HARDENING.md`.
          |Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
          |retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`
          |(typographic `×N` is equivalent).
          |
          |## Untriaged debt
          |
          |Record the local owner, measured scope, and retirement plan for every seeded
          |`# untriaged` family.
          |
          |## Accepted mutants
          |
          |For every accepted family, record its exact `# <label>`, local structural reason,
          |property, independent oracle, and the condition that would make the acceptance
          |invalid. Name the class, method, and semantic branch, and omit source line numbers.
          |Keep retired incidents separate from current acceptance evidence.
          |
          |## Audited timeout causes
          |
          |For every audited timeout family, record the class and method, the observed local
          |behavior, deterministic seams or budgets tried, any fixture safety bound, and the
          |measured structural cause. A global statement that the suite is slow is not
          |evidence for an individual mutant.
          |""".trimMargin()
      )
      logger.lifecycle("hardeningInit: wrote $readme")
    }
    val ignoreLine = ".pitest-history/"
    val existingGitignore = gitignore.takeIf(File::isFile)?.readText().orEmpty()
    if (existingGitignore.contains(ignoreLine)) {
      logger.lifecycle("hardeningInit: .gitignore already covers $ignoreLine")
    } else {
      BaselineFiles.writeAtomically(
          initRootProjectDirectory,
          gitignore,
          existingGitignore + (if (existingGitignore.isNotEmpty() && !existingGitignore.endsWith("\n")) "\n" else "") +
              "\n# machine-local hardening state (PIT history and release evidence)\n$ignoreLine\n")
      logger.lifecycle("hardeningInit: appended $ignoreLine to $gitignore")
    }
    logger.lifecycle(
        """
        |hardeningInit: remaining adoption steps (HARDENING.md 'Adopting in a new repo'):
        |  1. register mutation suites (wildcard targets + exclusions) and every
        |       meaningful fuzz target; zero fuzz targets is valid when the repo records why
        |  2. pin any unseeded randomness in the test suite
        |  3. seed each baseline: ./gradlew $initBaselineUpdateTaskPath
        |  4. for suites whose summary reports timed-out mutants, seed the audited set:
        |       ./gradlew $initTimeoutAuditTaskPath — then classify each seeded
        |       cause:untriaged row and write its structural
        |       argument in config/pitest/README.md; seeding deliberately leaves
        |       certification red (HARDENING.md, audited-timeout bullet)
        |  5. run ./gradlew $initTemplateTaskPath and copy that exact version-matched
        |       bounded agent-instructions template into AGENTS.md with:
        |       <!-- hardening-template sha256:$digest -->
        |       On later upgrades run ./gradlew $initTemplateDiffTaskPath before
        |       moving the digest; the task prints a review-only diff and never edits AGENTS.md.
        |  6. decide who owns the pre-release $initCertifyTaskPath run, and record it in AGENTS.md
        |  7. fuzz targets with a seedCorpus get a generated replay test automatically;
        |       document seed provenance in a README next to (never inside) the corpus dir
        |  8. own an explicit local $initFuzzAllTaskPath -PmaxFuzzTime=<seconds> -PmaxParallelFuzzTargets=<count> release budget;
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
      BaselineFiles.deleteRecursivelyIfExists(dir)
      dir.mkdirs()
      return@doLast
    }
    // Validate before deleting the previous output: a malformed package must not turn
    // a configuration error into data loss in the generated-source directory.
    val pkg = HardeningNames.requireJavaQualifiedName(
        "hardening.testSupportPackage", packageName.get(), requirePackage = true)
    val excluded = excludes.get().toSet()
    BaselineFiles.deleteRecursivelyIfExists(dir)
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
