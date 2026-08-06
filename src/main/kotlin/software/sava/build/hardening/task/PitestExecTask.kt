package software.sava.build.hardening.task

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.BaselineWriteOperation
import software.sava.build.hardening.ExclusionAudit
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.HardeningExecutionLock
import software.sava.build.hardening.HardeningOperationSession
import software.sava.build.hardening.MutatorAdvice
import software.sava.build.hardening.MutationToolchainRecord
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.PitestEvidenceSnapshot
import software.sava.build.hardening.PitestEvidenceSnapshotInput
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

internal const val PITEST_COVERAGE_TEST_COST_ADVISORY_MILLIS = 250L

internal fun shouldAdvisePitestCoverageTestCost(durationMillis: Long): Boolean =
  durationMillis >= PITEST_COVERAGE_TEST_COST_ADVISORY_MILLIS

/**
 * Common typed PIT process with execution-time lifecycle and evidence ownership.
 *
 * The task remains a [JavaExec], preserving the public task API consumers already
 * configure. All values read by [exec] are managed properties or named build-service
 * references; no execution action reaches through `Task.project` or a script object.
 */
@DisableCachingByDefault(because = "Abstract process base; concrete PIT tasks are always untracked")
abstract class PitestExecTask : JavaExec() {

  /**
   * Stable managed view of the effective JavaExec tool classpath. JavaExec replaces
   * its backing collection on `classpath = ...`; evidence recomputers must retain a
   * stable object while still observing that late compatibility customization.
   */
  @get:Internal
  abstract val effectiveToolClasspath: ConfigurableFileCollection

  @get:Input
  abstract val suiteName: Property<String>

  @get:Input
  abstract val targetClasses: ListProperty<String>

  @get:Input
  abstract val excludedClasses: ListProperty<String>

  @get:Input
  abstract val targetTests: Property<String>

  @get:Input
  abstract val mutators: Property<String>

  @get:Input
  abstract val threads: Property<Int>

  @get:Input
  abstract val minionJvmArgs: ListProperty<String>

  @get:Input
  abstract val timeoutFactor: Property<Double>

  @get:Input
  abstract val timeoutConst: Property<Long>

  @get:Input
  abstract val outputFormats: ListProperty<String>

  @get:Input
  abstract val timestampedReports: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val mutateOnly: Property<String>

  /** Classes and dependencies handed to PIT's `--classPath`, not PIT's own jars. */
  @get:Classpath
  abstract val applicationClasspath: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceDirectories: ConfigurableFileCollection

  /** The report is deliberately regenerated on every invocation. */
  @get:OutputDirectory
  abstract val reportDirectory: DirectoryProperty

  @get:LocalState
  abstract val historyFile: RegularFileProperty

  @get:Input
  abstract val historyRequested: Property<Boolean>

  @get:Input
  abstract val historyLicensed: Property<Boolean>

  @get:Input
  abstract val historyExplicitlyDisabled: Property<Boolean>

  @get:Input
  abstract val enforceExit: Property<Boolean>

  @get:Input
  abstract val bindSuiteEvidence: Property<Boolean>

  @get:Input
  abstract val certifyingProjectPath: Property<String>

  /** Root used to normalize paths in the evidence fingerprints. */
  @get:Internal
  abstract val evidenceProjectDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val evidenceSourceFiles: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val evidenceClassFiles: ConfigurableFileCollection

  /** Runtime resources/dependencies, excluding the recompiled class root. */
  @get:Classpath
  abstract val evidenceClasspath: ConfigurableFileCollection

  /** The jar or classes directory containing this hardening implementation. */
  @get:Classpath
  abstract val evidencePluginCode: ConfigurableFileCollection

  @get:Input
  abstract val pitestVersion: Property<String>

  @get:Input
  abstract val junitPluginVersion: Property<String>

  @get:Input
  abstract val arcMutateBaseVersion: Property<String>

  @get:Input
  abstract val mutationBytecodeRelease: Property<Int>

  @get:Input
  abstract val recompileExcludes: ListProperty<String>

  /** Named references replace script-captured service providers in task actions. */
  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  @get:ServiceReference("hardeningOperationSession")
  abstract val operationSession: Property<HardeningOperationSession>

  @get:ServiceReference("hardeningExecutionLock")
  abstract val executionLock: Property<HardeningExecutionLock>

  private val commandLineProvider = PitestCommandLineProvider(
    applicationClasspath,
    targetClasses,
    mutateOnly,
    excludedClasses,
    targetTests,
    sourceDirectories,
    reportDirectory,
    evidenceProjectDirectory,
    mutators,
    outputFormats,
    timestampedReports,
    threads,
    minionJvmArgs,
    timeoutFactor,
    timeoutConst,
    historyFile,
  )

  init {
    mainClass.convention("org.pitest.mutationtest.commandline.MutationCoverageReport")
    outputFormats.convention(listOf("HTML", "XML", "CSV"))
    timestampedReports.convention(false)
    historyRequested.convention(true)
    historyLicensed.convention(false)
    historyExplicitlyDisabled.convention(false)
    enforceExit.convention(true)
    bindSuiteEvidence.convention(true)
    isIgnoreExitValue = true
    argumentProviders.add(commandLineProvider)
  }

  override fun setClasspath(classpath: FileCollection): JavaExec {
    val result = super.setClasspath(classpath)
    effectiveToolClasspath.setFrom(classpath)
    return result
  }

  override fun classpath(vararg paths: Any?): JavaExec {
    val result = super.classpath(*paths)
    effectiveToolClasspath.setFrom(super.getClasspath())
    return result
  }

  /** Flavor-specific refusal or cleanup that must happen before any marker is touched. */
  protected open fun beforeAttempt() = Unit

  /**
   * Flavor-specific work that must see the attempt sentinel but precede the PIT
   * process. A failure here deliberately leaves `.running`, so a verify finalizer
   * cannot mistake an older report for evidence from this invocation.
   */
  protected open fun afterAttemptStarted() = Unit

  /** Advice that is meaningful only after PIT produced a complete successful report. */
  protected open fun afterSuccessfulAttempt(
    slowestTestName: String?,
    slowestTestDurationMillis: Long?,
  ) = Unit

  @TaskAction
  override fun exec() {
    // Resolve and validate the effective engine before touching the attempt
    // sentinel. An expired, malformed, or ambiguous certificate must not leave an
    // older report looking like an interrupted current run.
    val initialToolchain = mutationToolchainRecord()
    beforeAttempt()

    val historyActive = historyActiveNow()
    // Freeze this invocation's decision before JavaExec realizes its argument
    // providers. The same value owns the command line, evidence manifest, and
    // completion markers even if another task publishes build-service state later.
    commandLineProvider.useHistory(historyActive)

    // Gradle's default process streams are represented as null after a
    // configuration-cache round trip. Their documented effective destinations are
    // the console streams; make that fallback explicit before crossing Kotlin's
    // non-null boundary so minion deduplication remains active on a cache hit.
    val originalStandardOutput: OutputStream = getStandardOutput() ?: System.out
    val originalErrorOutput: OutputStream = getErrorOutput() ?: System.err
    val filters = MinionOutputFilters(originalStandardOutput, originalErrorOutput)
    standardOutput = filters.standardOutput
    errorOutput = filters.errorOutput

    var attempt: PitestAttempt? = null
    var outputSummary: PitestOutputSummary? = null
    try {
      attempt = beginAttempt(historyActive, initialToolchain)
      afterAttemptStarted()
      super.exec()
    } finally {
      standardOutput = originalStandardOutput
      errorOutput = originalErrorOutput
      val summary = filters.closeAndSummarize()
      outputSummary = summary
      if (summary.suppressedMinionLines > 0) {
        logger.lifecycle(
          "pitest: suppressed ${summary.suppressedMinionLines} repeated minion log line(s) — " +
              "first occurrence of each is above"
        )
      }
    }

    val result = executionResult.get()
    if (enforceExit.get()) result.assertNormalExitValue()
    if (result.exitValue != 0) return
    completeAttempt(checkNotNull(attempt), historyActive)
    afterSuccessfulAttempt(
      outputSummary?.slowestTest?.name,
      outputSummary?.slowestTest?.durationMillis,
    )
  }

  private fun historyActiveNow(): Boolean =
      historyRequested.get() &&
      historyLicensed.get() &&
      !historyExplicitlyDisabled.get() &&
      operationSession.get().suiteOperation(
        certifyingProjectPath.get(), suiteName.get()
      ) == BaselineWriteOperation.CHECK &&
      !certificationSession.get().isActive(certifyingProjectPath.get())

  private fun beginAttempt(
    historyActive: Boolean,
    toolchain: MutationToolchainRecord,
  ): PitestAttempt {
    val suite = suiteName.get()
    val reportDir = reportDirectory.get().asFile
    if (historyActive) {
      val history = historyFile.get().asFile
      history.parentFile.mkdirs()
      logger.lifecycle("pitest '$suite': arcmutate history active — $history")
    }

    val invocationId = UUID.randomUUID().toString()
    val bindsEvidence = bindSuiteEvidence.get()
    if (bindsEvidence) {
      certificationSession.get().startAttempt(certifyingProjectPath.get(), suite, invocationId)
    }

    reportDir.mkdirs()
    reportDir.resolve(RUNNING_MARKER).writeText("")
    if (!bindsEvidence) return PitestAttempt(invocationId, null, toolchain)

    Files.deleteIfExists(reportDir.resolve(EVIDENCE_FILE).toPath())
    Files.deleteIfExists(reportDir.resolve(TOOLCHAIN_FILE).toPath())
    reportDir.resolve(EVIDENCE_INVOCATION_FILE).writeText("$invocationId\n")
    return PitestAttempt(
      invocationId,
      evidenceSnapshot(
        invocationId = invocationId,
        reportSha256 = "",
        scope = currentScope(),
        historyAssisted = historyActive,
        mutationToolchainSha256 = toolchain.identitySha256,
      ),
      toolchain,
    )
  }

  private fun completeAttempt(attempt: PitestAttempt, historyActive: Boolean) {
    val suite = suiteName.get()
    val reportDir = reportDirectory.get().asFile
    val scope = currentScope()
    val scopedMarker = reportDir.resolve(SCOPED_MARKER)
    var completedEvidence: PitestEvidence? = null

    if (bindSuiteEvidence.get()) {
      val report = reportDir.resolve(REPORT_FILE)
      if (!report.isFile) {
        throw GradleException(
          "pitest '$suite' exited successfully but wrote no CSV report at $report — " +
            "cannot create completed-run evidence"
        )
      }
      val before = attempt.preRunEvidence ?: throw GradleException(
        "pitest '$suite' has no pre-run evidence fingerprint — refusing to certify its report"
      )
      val completedToolchain = mutationToolchainRecord()
      val after = evidenceSnapshot(
        invocationId = before.invocationId,
        reportSha256 = "",
        scope = scope,
        historyAssisted = historyActive,
        mutationToolchainSha256 = completedToolchain.identitySha256,
      )
      val changedInputs = before.differences(after)
      if (changedInputs.isNotEmpty()) {
        throw GradleException(
          "pitest '$suite': evidence inputs changed while PIT was running — refusing to commit " +
            "completed evidence; re-run against a stable checkout:\n" +
            changedInputs.joinToString("\n") { "  $it" }
        )
      }
      check(attempt.preRunToolchain == completedToolchain) {
        "pitest '$suite' mutation-toolchain record changed without changing its identity"
      }
      completedEvidence = before.copy(reportSha256 = PitestEvidence.sha256(report))
    }

    if (scope == PitestEvidence.FULL_SCOPE) {
      Files.deleteIfExists(scopedMarker.toPath())
    } else {
      scopedMarker.writeText("$scope\n")
    }
    val historyMarker = reportDir.resolve(HISTORY_MARKER)
    if (historyActive) historyMarker.writeText("") else Files.deleteIfExists(historyMarker.toPath())

    completedEvidence?.let { evidence ->
      val invocationFile = reportDir.resolve(EVIDENCE_INVOCATION_FILE)
      check(invocationFile.readText().trim() == evidence.invocationId) {
        "pitest '$suite' invocation marker changed while PIT was running"
      }
      val completedToolchain = attempt.preRunToolchain
      check(completedToolchain.identitySha256 == evidence.mutationToolchainSha256) {
        "pitest '$suite' mutation-toolchain record disagrees with completed evidence"
      }
      BaselineFiles.writeAtomically(reportDir.resolve(TOOLCHAIN_FILE), completedToolchain.render())
      BaselineFiles.writeAtomically(reportDir.resolve(EVIDENCE_FILE), evidence.render())
      certificationSession.get().recordCompleted(certifyingProjectPath.get(), suite, evidence)
      Files.deleteIfExists(invocationFile.toPath())
    }

    // A report becomes consumable only after every completion marker and evidence
    // manifest are durable. Any failure above deliberately leaves this sentinel.
    Files.deleteIfExists(reportDir.resolve(RUNNING_MARKER).toPath())
  }

  private fun currentScope(): String =
    mutateOnly.orNull?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }

  private fun evidenceSnapshot(
    invocationId: String,
    reportSha256: String,
    scope: String,
    historyAssisted: Boolean,
    mutationToolchainSha256: String,
  ): PitestEvidence {
    return PitestEvidenceSnapshot.capture(PitestEvidenceSnapshotInput(
      suite = suiteName.get(),
      invocationId = invocationId,
      pitestVersion = pitestVersion.get(),
      junitPluginVersion = junitPluginVersion.get(),
      javaVersion = javaLauncher.get().metadata.javaRuntimeVersion,
      projectDirectory = evidenceProjectDirectory.get().asFile,
      pluginCode = evidencePluginCode.singleFile,
      sourceFiles = evidenceSourceFiles.files,
      classFiles = evidenceClassFiles.files,
      runtimeClasspath = evidenceClasspath.files,
      toolClasspath = effectiveToolClasspath.files,
      mutationToolchainSha256 = mutationToolchainSha256,
      targetClasses = targetClasses.get(),
      excludedClasses = excludedClasses.get(),
      targetTests = targetTests.get(),
      mutators = mutators.get(),
      threads = threads.get(),
      timeoutFactor = timeoutFactor.get(),
      timeoutConst = timeoutConst.get(),
      mutationBytecodeRelease = mutationBytecodeRelease.get(),
      recompileExcludes = recompileExcludes.get(),
      reportSha256 = reportSha256,
      scope = scope,
      historyAssisted = historyAssisted,
    ), minionJvmArgs.get())
  }

  private data class PitestAttempt(
    val invocationId: String,
    val preRunEvidence: PitestEvidence?,
    val preRunToolchain: MutationToolchainRecord,
  )

  private fun mutationToolchainRecord(): MutationToolchainRecord {
    val projectDirectory = evidenceProjectDirectory.get().asFile
    val lookupStart = workingDir
    require(lookupStart.canonicalFile == projectDirectory.canonicalFile) {
      "pitest '${suiteName.get()}': hardening owns workingDir $projectDirectory so PIT project-base " +
          "and any ArcMutate certificate lookup remain provenance-bound; was $lookupStart"
    }
    return MutationToolchainRecord.capture(
      pitestVersion = pitestVersion.get(),
      junitPluginVersion = junitPluginVersion.get(),
      toolClasspath = effectiveToolClasspath.files,
      arcMutateBaseVersion = arcMutateBaseVersion.get(),
      arcMutateEnabled = historyLicensed.get(),
      reportDirectory = reportDirectory.get().asFile,
      projectBaseDirectory = projectDirectory,
      lookupStartDirectory = lookupStart,
      observationDate = LocalDate.now(Clock.systemUTC()),
    )
  }

  private companion object {
    const val REPORT_FILE = "mutations.csv"
    const val RUNNING_MARKER = ".running"
    const val SCOPED_MARKER = ".scoped"
    const val HISTORY_MARKER = ".history-assisted"
    const val EVIDENCE_FILE = ".evidence.tsv"
    const val TOOLCHAIN_FILE = ".toolchain.tsv"
    const val EVIDENCE_INVOCATION_FILE = ".evidence-invocation"
  }
}

/** Normal ratcheted PIT execution. */
@UntrackedTask(because = "Mutation testing must make a fresh attempt whenever selected")
abstract class PitestRunTask : PitestExecTask() {

  /** Root of the mutation recompile whose production classes are about to be scanned. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val adviceClassesDirectory: ConfigurableFileCollection

  /** Source roots used to distinguish production classes from recompiled test scaffolding. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val adviceTestSourceDirectories: ConfigurableFileCollection

  @get:Input
  abstract val adviceSiblingTargets: MapProperty<String, List<String>>

  @get:Input
  abstract val adviceSiblingExcludes: MapProperty<String, List<String>>

  @get:Input
  abstract val adviceDeclinedMutators: MapProperty<String, String>

  @get:Input
  abstract val adviceDeclinedExclusions: MapProperty<String, String>

  @get:Input
  abstract val adviceTrialTaskPath: Property<String>

  @get:Input
  abstract val adviceAdvisoryScope: Property<String>

  @get:ServiceReference("hardeningAdvisoryLog")
  abstract val advisoryLog: Property<HardeningAdvisoryLog>

  init {
    adviceSiblingTargets.convention(emptyMap())
    adviceSiblingExcludes.convention(emptyMap())
    adviceDeclinedMutators.convention(emptyMap())
    adviceDeclinedExclusions.convention(emptyMap())
  }

  override fun afterAttemptStarted() {
    val classesDir = adviceClassesDirectory.singleFile
    // No recompiled classes means nothing was scanned. Treating that as a clean
    // scan would wrongly report every recorded decline as stale.
    if (!classesDir.isDirectory) return

    val suite = suiteName.get()
    val siblingExcludes = adviceSiblingExcludes.get()
    val siblingScopes = adviceSiblingTargets.get()
      .filterKeys { it != suite }
      .map { (sibling, targets) ->
        ExclusionAudit.SuiteScope(targets, siblingExcludes[sibling].orEmpty())
      }
    val swallowed = ExclusionAudit.swallowedProductionClasses(
      classesDir,
      targetClasses.get(),
      excludedClasses.get(),
      adviceTestSourceDirectories.files,
      siblingScopes,
    )
    val declined = ExclusionAudit.applyDeclines(swallowed, adviceDeclinedExclusions.get())
    val scope = adviceAdvisoryScope.get()

    ExclusionAudit.warning(suite, declined.reported)?.let { warning ->
      logger.warn(warning)
      advisoryLog.get().record(
        scope,
        "${declined.reported.size} production class(es) swallowed by excludedClasses",
      )
    }
    ExclusionAudit.staleDeclineWarning(suite, declined.staleGlobs)?.let { warning ->
      logger.warn(warning)
      advisoryLog.get().record(scope, "${declined.staleGlobs.size} stale exclusion decline(s)")
    }
    ExclusionAudit.blankDeclineWarning(suite, declined.blankGlobs)?.let { warning ->
      logger.warn(warning)
      advisoryLog.get().record(
        scope,
        "${declined.blankGlobs.size} exclusion decline(s) without a reason",
      )
    }

    val enabledMutators = mutators.get()
    val advice = MutatorAdvice.advise(
      MutatorAdvice.scan(
        classesDir,
        targetClasses.get(),
        excludedClasses.get(),
        enabledMutators,
      ),
      enabledMutators,
      adviceDeclinedMutators.get(),
    )
    advice.findings.forEach { finding ->
      logger.warn(
        "pitest '$suite': ${finding.classCount} mutated class(es) call ${finding.label} arithmetic " +
          "(${finding.callCount} call site(s)), which the enabled mutator set cannot mutate — " +
          "those computations are currently unmutated, not proven.\n" +
          "  measure it: ./gradlew ${adviceTrialTaskPath.get()} " +
          "-PtrialMutators=${finding.mutator}\n" +
          "  then enable what fires (mutators = \"...,${finding.mutator}\") and record the numbers, " +
          "or record the measured decision not to: " +
          "declineMutator(\"${finding.mutator}\", \"what the trial generated, and why it was not worth it\").",
      )
      advisoryLog.get().record(
        scope,
        "${finding.classCount} class(es) use unmutated ${finding.label} arithmetic",
      )
    }
    advice.staleDeclines.forEach { stale ->
      logger.warn(
        "pitest '$suite': the recorded decline of ${stale.mutator} is stale — ${stale.why}.",
      )
      advisoryLog.get().record(scope, "stale ${stale.mutator} mutator decline")
    }
  }

  override fun afterSuccessfulAttempt(
    slowestTestName: String?,
    slowestTestDurationMillis: Long?,
  ) {
    val name = slowestTestName ?: return
    val durationMillis = slowestTestDurationMillis ?: return
    if (!shouldAdvisePitestCoverageTestCost(durationMillis)) return

    val scope = adviceAdvisoryScope.get()
    logger.warn(
      "$scope: slowest PIT coverage-phase test '$name' took $durationMillis ms " +
          "(advisory threshold ${PITEST_COVERAGE_TEST_COST_ADVISORY_MILLIS} ms) — potential " +
          "repeated harness cost. This measurement does not prove the test covers a target mutant " +
          "or prescribe a remedy; when it does cover mutated code, PIT can repay its wall-clock " +
          "work across those mutants and produce load-dependent TIMED_OUT results."
    )
    advisoryLog.get().record(
      scope,
      "slowest PIT coverage-phase test took $durationMillis ms",
    )
  }
}

/** Second convergence observation; never valid inside the certification graph. */
@UntrackedTask(because = "Convergence compares two fresh observations")
abstract class PitestConvergeTask : PitestExecTask() {
  override fun beforeAttempt() {
    if (certificationSession.get().isActive(certifyingProjectPath.get())) {
      throw GradleException(
        "pitestConverge cannot run inside hardeningCertify: convergence's unverified round two would " +
          "replace the strict-verified report the certification receipt must bind. Run the two " +
          "workflows in separate Gradle invocations."
      )
    }
  }
}

/** Candidate-mutator measurement: isolated report, no history, and a tolerated zero-fire exit. */
@UntrackedTask(because = "A mutator trial must execute whenever selected")
abstract class PitestMutatorTrialTask : PitestExecTask() {
  init {
    historyRequested.convention(false)
    enforceExit.convention(false)
    bindSuiteEvidence.convention(false)
  }

  override fun beforeAttempt() {
    if (!mutators.isPresent || mutators.get().isBlank()) {
      throw GradleException(
        "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only " +
          "(e.g. EXPERIMENTAL_NAKED_RECEIVER), not the suite's existing set"
      )
    }
    // A failed trial writes no complete report. Refuse to tabulate an earlier run.
    val report = reportDirectory.get().asFile
    BaselineFiles.deleteRecursivelyIfExists(report)
  }
}

private class PitestCommandLineProvider(
  private val applicationClasspath: ConfigurableFileCollection,
  private val targetClasses: ListProperty<String>,
  private val mutateOnly: Property<String>,
  private val excludedClasses: ListProperty<String>,
  private val targetTests: Property<String>,
  private val sourceDirectories: ConfigurableFileCollection,
  private val reportDirectory: DirectoryProperty,
  private val projectBaseDirectory: DirectoryProperty,
  private val mutators: Property<String>,
  private val outputFormats: ListProperty<String>,
  private val timestampedReports: Property<Boolean>,
  private val threads: Property<Int>,
  private val minionJvmArgs: ListProperty<String>,
  private val timeoutFactor: Property<Double>,
  private val timeoutConst: Property<Long>,
  private val historyFile: RegularFileProperty,
) : CommandLineArgumentProvider {

  private var historyActiveForExecution = false

  fun useHistory(active: Boolean) {
    historyActiveForExecution = active
  }

  override fun asArguments(): Iterable<String> {
    return HardeningCommandLines.pitest(
      HardeningCommandLines.Pitest(
        applicationClasspath = applicationClasspath.files.toList(),
        targetClasses = targetClasses.get(),
        mutateOnly = mutateOnly.orNull,
        excludedClasses = excludedClasses.get(),
        targetTests = targetTests.get(),
        sourceDirectories = sourceDirectories.files.toList(),
        reportDirectory = reportDirectory.get().asFile,
        projectBaseDirectory = projectBaseDirectory.get().asFile,
        mutators = mutators.get(),
        outputFormats = outputFormats.get(),
        timestampedReports = timestampedReports.get(),
        threads = threads.get(),
        minionJvmArgs = minionJvmArgs.get(),
        timeoutFactor = timeoutFactor.get().toString(),
        timeoutConst = timeoutConst.get(),
        historyActive = historyActiveForExecution,
        historyFile = historyFile.get().asFile,
      )
    )
  }
}
