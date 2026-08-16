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
import software.sava.build.hardening.HardeningPluginIdentityGuard
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

/** PIT release whose arcmutateMissing meaning was audited; re-audit before changing. */
private const val ARCMUTATE_MISSING_AUDITED_PIT = "1.25.9"

internal fun shouldAdvisePitestCoverageTestCost(durationMillis: Long): Boolean =
  durationMillis >= PITEST_COVERAGE_TEST_COST_ADVISORY_MILLIS

/**
 * Common typed PIT process with execution-time lifecycle and evidence ownership.
 *
 * The task remains a [JavaExec] for the supported launcher, tool-classpath, and
 * main-class compatibility surface. PIT arguments themselves are owned by typed,
 * evidence-bound properties; arbitrary direct arguments/providers are refused.
 * All values read by [exec] are managed properties or named build-service references;
 * no execution action reaches through `Task.project` or a script object.
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

  /** Zero for normal PIT batching; one only for the scoped contamination diagnostic. */
  @get:Input
  abstract val mutationUnitSize: Property<Int>

  @get:Input
  abstract val outputFormats: ListProperty<String>

  @get:Input
  abstract val timestampedReports: Property<Boolean>

  /** PIT process logging level. DEFAULT is omitted from the command line and evidence text. */
  @get:Input
  abstract val verbosity: Property<String>

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

  /**
   * Scoped iteration output is isolated from the suite's full-population evidence.
   * This remains internal because [reportDirectory] is the compatibility surface
   * consumers may customize; plugin wiring supplies the isolated location.
   */
  @get:Internal
  abstract val scopedReportDirectory: DirectoryProperty

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

  /** True only for the isolated, non-evidence diagnostic task family. */
  @get:Input
  abstract val diagnosticMode: Property<Boolean>

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

  /** SHA captured when the hardening convention was applied, before task execution. */
  @get:Input
  abstract val expectedPluginSha256: Property<String>

  @get:Input
  abstract val localRepoArtifactPath: Property<String>

  @get:Input
  abstract val expectedLocalRepoArtifactSha256: Property<String>

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
    scopedReportDirectory,
    evidenceProjectDirectory,
    mutators,
    outputFormats,
    timestampedReports,
    verbosity,
    threads,
    minionJvmArgs,
    timeoutFactor,
    timeoutConst,
    mutationUnitSize,
    historyFile,
  )

  init {
    mainClass.convention("org.pitest.mutationtest.commandline.MutationCoverageReport")
    outputFormats.convention(listOf("HTML", "XML", "CSV"))
    timestampedReports.convention(false)
    verbosity.convention(HardeningCommandLines.PitestVerbosity.DEFAULT)
    historyRequested.convention(true)
    historyLicensed.convention(false)
    historyExplicitlyDisabled.convention(false)
    mutationUnitSize.convention(0)
    enforceExit.convention(true)
    bindSuiteEvidence.convention(true)
    diagnosticMode.convention(false)
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

  /** Optional task-family context for a non-zero exit that this task deliberately tolerates. */
  protected open fun toleratedNonZeroExitContext(): String? = null

  @TaskAction
  override fun exec() {
    if (unmanagedPitArgumentsPresent()) {
      throw GradleException(
        "pitest '${suiteName.get()}': direct JavaExec args/argumentProviders are not supported — " +
          "they bypass typed PIT configuration and its evidence identity. Configure " +
          "hardening.mutation instead; a missing PIT option needs a first-class typed, " +
          "evidence-bound plugin property."
      )
    }
    requirePluginCodeUnchanged("pitest '${suiteName.get()}' before execution")
    // Validate before the report lifecycle starts. Command-line assembly and
    // evidence capture repeat this normalization at their own trust boundaries.
    HardeningCommandLines.PitestVerbosity.normalize(verbosity.get())
    // Resolve and validate the effective engine before touching the attempt
    // sentinel. An expired, malformed, or ambiguous certificate must not leave an
    // older report looking like an interrupted current run.
    val initialToolchain = mutationToolchainRecord()
    beforeAttempt()
    if (diagnosticMode.get()) {
      initialToolchain.arcMutateBaseVersion?.let { baseVersion ->
        // The toolchain capture above is the activation and licence boundary. Make
        // that distinction visible before PIT's poorly named raw field can send an
        // incident investigation in the wrong direction.
        val rawFieldMeaning = if (
          initialToolchain.pitestVersion == ARCMUTATE_MISSING_AUDITED_PIT
        ) {
          " With audited PIT $ARCMUTATE_MISSING_AUDITED_PIT, its raw " +
            "arcmutateMissing field controls only the HTML promotion."
        } else ""
        logger.lifecycle(
          "pitest '${suiteName.get()}': licensed ArcMutate base $baseVersion was validated " +
            "on the effective tool classpath; that captured toolchain is sava-build's " +
            "ArcMutate activation identity.$rawFieldMeaning"
        )
      }
    }

    val historyActive = historyActiveNow()
    // Freeze this invocation's decision before JavaExec realizes its argument
    // providers. The same value owns the command line, evidence manifest, and
    // completion markers even if another task publishes build-service state later.
    commandLineProvider.useHistory(historyActive)

    var attempt: PitestAttempt? = null
    var outputSummary: PitestOutputSummary? = null
    var executionFailure: Throwable? = null
    var attemptLogs: PitestAttemptLogs? = null
    var attemptLogsPrinted = false
    fun printAttemptLogs(disposition: AttemptLogDisposition, exitValue: Int? = null) {
      if (attemptLogsPrinted) return
      val logs = attemptLogs ?: return
      attemptLogsPrinted = true
      val prefix = when (disposition) {
        AttemptLogDisposition.FAILED -> "failed attempt raw logs"
        AttemptLogDisposition.TOLERATED_NON_ZERO -> buildString {
          append("tolerated non-zero exit ")
          append(requireNotNull(exitValue))
          toleratedNonZeroExitContext()?.let { context ->
            append("; ")
            append(context)
          }
          append(". Raw logs")
        }
      }
      logger.lifecycle(
        "pitest '${suiteName.get()}': $prefix (diagnostic only; not evidence): " +
          "${logs.standardOutput}, ${logs.errorOutput}"
      )
    }
    fun printFailureLogs() = printAttemptLogs(AttemptLogDisposition.FAILED)
    try {
      attempt = beginAttempt(historyActive, initialToolchain)
      val logs = PitestAttemptLogs(currentReportDirectory())
      attemptLogs = logs
      // Gradle's default process streams are represented as null after a
      // configuration-cache round trip. Their documented effective destinations are
      // the console streams; make that fallback explicit before crossing Kotlin's
      // non-null boundary so minion deduplication remains active on a cache hit.
      val originalStandardOutput: OutputStream = getStandardOutput() ?: System.out
      val originalErrorOutput: OutputStream = getErrorOutput() ?: System.err
      val retainedStandardOutput = logs.standardOutput.outputStream()
      var retainedErrorOutput: OutputStream? = null
      val filters = try {
        retainedErrorOutput = logs.errorOutput.outputStream()
        MinionOutputFilters(
          originalStandardOutput,
          originalErrorOutput,
          retainedStandardOutput,
          retainedErrorOutput,
        )
      } catch (failure: Throwable) {
        listOfNotNull(retainedStandardOutput, retainedErrorOutput).forEach { output ->
          try {
            output.close()
          } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
          }
        }
        throw failure
      }
      standardOutput = filters.standardOutput
      errorOutput = filters.errorOutput
      if (diagnosticMode.get()) {
        logger.lifecycle(
          "pitest '${suiteName.get()}': verbose diagnostic raw logs (not mutation evidence): " +
            "${logs.standardOutput}, ${logs.errorOutput}"
        )
      }
      try {
        afterAttemptStarted()
        super.exec()
      } catch (failure: Throwable) {
        executionFailure = failure
        throw failure
      } finally {
        standardOutput = originalStandardOutput
        errorOutput = originalErrorOutput
        var postExecutionFailure: Throwable? = executionFailure
        try {
          outputSummary = filters.closeAndSummarize()
        } catch (closeFailure: Throwable) {
          postExecutionFailure = retainPrimaryFailure(postExecutionFailure, closeFailure)
        }
        val summary = outputSummary
        if (summary != null && summary.suppressedMinionLines > 0) {
          logger.lifecycle(
            "pitest: suppressed ${summary.suppressedMinionLines} repeated minion log line(s) — " +
                "first occurrence of each is above"
          )
        }
        try {
          requirePluginCodeUnchanged("pitest '${suiteName.get()}' after execution")
        } catch (identityFailure: Throwable) {
          postExecutionFailure = retainPrimaryFailure(postExecutionFailure, identityFailure)
        }
        if (executionFailure == null) {
          postExecutionFailure?.let { throw it }
        }
      }
    } catch (failure: Throwable) {
      printFailureLogs()
      throw failure
    }

    val result = executionResult.get()
    if (result.exitValue != 0) {
      if (enforceExit.get()) {
        printFailureLogs()
        result.assertNormalExitValue()
      } else {
        printAttemptLogs(AttemptLogDisposition.TOLERATED_NON_ZERO, result.exitValue)
      }
      return
    }
    try {
      completeAttempt(checkNotNull(attempt), historyActive)
      afterSuccessfulAttempt(
        outputSummary?.slowestTest?.name,
        outputSummary?.slowestTest?.durationMillis,
      )
    } catch (failure: Throwable) {
      printFailureLogs()
      throw failure
    }
  }

  private fun historyActiveNow(): Boolean =
      historyRequested.get() &&
      historyLicensed.get() &&
      !historyExplicitlyDisabled.get() &&
      mutationUnitSize.get() == 0 &&
      operationSession.get().suiteOperation(
        certifyingProjectPath.get(), suiteName.get()
      ) == BaselineWriteOperation.CHECK &&
      !certificationSession.get().isActive(certifyingProjectPath.get())

  private fun beginAttempt(
    historyActive: Boolean,
    toolchain: MutationToolchainRecord,
  ): PitestAttempt {
    val suite = suiteName.get()
    val reportDir = currentReportDirectory()
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

    prepareAttemptDirectory(reportDir)
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
    val reportDir = currentReportDirectory()
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
    PitestReportDirectories.normalizedScope(mutateOnly.orNull) ?: PitestEvidence.FULL_SCOPE

  protected fun currentReportDirectory(): File = PitestReportDirectories.select(
    reportDirectory.get().asFile,
    scopedReportDirectory.get().asFile,
    mutateOnly.orNull,
  )

  /** Direct PIT arguments/providers would bypass managed configuration and evidence identity. */
  private fun unmanagedPitArgumentsPresent(): Boolean =
    getArgs().isNotEmpty() || argumentProviders.any { it !== commandLineProvider }

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
    ), minionJvmArgs.get(), expectedPluginSha256.get(), mutationUnitSize.get(), verbosity.get())
  }

  private fun requirePluginCodeUnchanged(context: String) {
    val code = evidencePluginCode.singleFile
    try {
      HardeningPluginIdentityGuard.requireUnchanged(
        code,
        expectedPluginSha256.get(),
        localRepoArtifactPath.get(),
        expectedLocalRepoArtifactSha256.get(),
        context,
      )
    } catch (e: IllegalStateException) {
      throw GradleException(
        "${e.message}; refusing evidence from mixed plugin bytes", e)
    }
  }

  private data class PitestAttempt(
    val invocationId: String,
    val preRunEvidence: PitestEvidence?,
    val preRunToolchain: MutationToolchainRecord,
  )

  private enum class AttemptLogDisposition {
    FAILED,
    TOLERATED_NON_ZERO,
  }

  private data class PitestAttemptLogs(
    val standardOutput: File,
    val errorOutput: File,
  ) {
    constructor(reportDirectory: File) : this(
      reportDirectory.resolve(STANDARD_OUTPUT_LOG),
      reportDirectory.resolve(ERROR_OUTPUT_LOG),
    )
  }

  /**
   * Invalidates only leaves whose continued presence can be mistaken for this
   * attempt's decision-grade output. The report directory is a public JavaExec
   * customization surface, so unrelated/deep HTML content is never recursively
   * deleted here.
   */
  private fun prepareAttemptDirectory(reportDirectory: File) {
    BaselineFiles.requireDirectoryOrMissing(reportDirectory)
    if (!reportDirectory.isDirectory && !reportDirectory.mkdirs()) {
      throw GradleException("pitest '${suiteName.get()}': cannot create report directory $reportDirectory")
    }
    val staleLeaves = listOf(
      REPORT_FILE,
      XML_REPORT_FILE,
      HTML_INDEX_FILE,
      RUNNING_MARKER,
      SCOPED_MARKER,
      HISTORY_MARKER,
      EVIDENCE_FILE,
      TOOLCHAIN_FILE,
      EVIDENCE_INVOCATION_FILE,
      STANDARD_OUTPUT_LOG,
      ERROR_OUTPUT_LOG,
    ).map(reportDirectory::resolve)
    // Validate the complete set before deleting any leaf so a linked/non-regular
    // artifact cannot produce a half-invalidated attempt directory.
    staleLeaves.forEach(BaselineFiles::requireRegularFileOrMissing)
    staleLeaves.forEach(BaselineFiles::deleteIfExists)
  }

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
      reportDirectory = currentReportDirectory(),
      projectBaseDirectory = projectDirectory,
      lookupStartDirectory = lookupStart,
      observationDate = LocalDate.now(Clock.systemUTC()),
    )
  }

  private companion object {
    const val REPORT_FILE = "mutations.csv"
    const val XML_REPORT_FILE = "mutations.xml"
    const val HTML_INDEX_FILE = "index.html"
    const val RUNNING_MARKER = ".running"
    const val SCOPED_MARKER = ".scoped"
    const val HISTORY_MARKER = ".history-assisted"
    const val EVIDENCE_FILE = ".evidence.tsv"
    const val TOOLCHAIN_FILE = ".toolchain.tsv"
    const val EVIDENCE_INVOCATION_FILE = ".evidence-invocation"
    const val STANDARD_OUTPUT_LOG = "pitest.stdout.log"
    const val ERROR_OUTPUT_LOG = "pitest.stderr.log"
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

  override fun beforeAttempt() {
    super.beforeAttempt()
    val unitSize = mutationUnitSize.get()
    if (unitSize !in 0..1) {
      throw GradleException(
        "pitest '${suiteName.get()}': unsupported diagnostic mutation unit size $unitSize"
      )
    }
    if (unitSize == 1) {
      val scope = PitestReportDirectories.normalizedScope(mutateOnly.orNull)
        ?: throw GradleException(
          "pitest '${suiteName.get()}': -PisolateMutants requires " +
            "-PmutateOnly=<class-glob>; isolated execution is a scoped diagnostic, " +
            "not full-population evidence"
        )
      logger.lifecycle(
        "pitest '${suiteName.get()}': one-mutant-per-unit diagnostic for $scope — " +
          "history disabled; result is scoped and cannot support a record decision"
      )
    }
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
    PitestReportDirectories.normalizedScope(mutateOnly.orNull)?.let { scope ->
      throw GradleException(
        "pitestConverge cannot run with -PmutateOnly=$scope: convergence requires two " +
          "full-population observations. Run the scoped diagnostic and convergence in separate " +
          "Gradle invocations."
      )
    }
    if (certificationSession.get().isActive(certifyingProjectPath.get())) {
      throw GradleException(
        "pitestConverge cannot run inside hardeningCertify: convergence's unverified round two would " +
          "replace the strict-verified report the certification receipt must bind. Run the two " +
          "workflows in separate Gradle invocations."
      )
    }
  }
}

/** Verbose, history-free PIT diagnosis isolated from every decision-grade report. */
@UntrackedTask(because = "PIT diagnostics must execute whenever selected")
abstract class PitestDiagnosticTask : PitestExecTask() {
  init {
    historyRequested.convention(false)
    bindSuiteEvidence.convention(false)
    diagnosticMode.convention(true)
    verbosity.convention(HardeningCommandLines.PitestVerbosity.VERBOSE_NO_SPINNER)
  }

  override fun beforeAttempt() {
    val diagnosticVerbosity =
      HardeningCommandLines.PitestVerbosity.normalize(verbosity.get())
    val overriddenInvariants = buildList {
      if (historyRequested.get()) add("historyRequested must be false")
      if (bindSuiteEvidence.get()) add("bindSuiteEvidence must be false")
      if (!enforceExit.get()) add("enforceExit must be true")
      if (!diagnosticMode.get()) add("diagnosticMode must be true")
      if (diagnosticVerbosity != HardeningCommandLines.PitestVerbosity.VERBOSE_NO_SPINNER) {
        add("verbosity must be ${HardeningCommandLines.PitestVerbosity.VERBOSE_NO_SPINNER}")
      }
    }
    if (overriddenInvariants.isNotEmpty()) {
      throw GradleException(
        "pitest '${suiteName.get()}': diagnostic safety invariant(s) were overridden: " +
          overriddenInvariants.joinToString("; ")
      )
    }
    if (certificationSession.get().isActive(certifyingProjectPath.get())) {
      throw GradleException(
        "pitest '${suiteName.get()}': verbose diagnostics cannot run inside hardeningCertify; " +
          "run the diagnostic and certification in separate Gradle invocations"
      )
    }
    logger.lifecycle(
      "pitest '${suiteName.get()}': VERBOSE_NO_SPINNER diagnostic — history disabled; " +
        "isolated output cannot support baseline, timeout-audit, mode, or certification decisions"
    )
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
    // The common attempt lifecycle clears every known decision-grade leaf without
    // recursively deleting consumer-added report content.
  }

  override fun toleratedNonZeroExitContext(): String =
    "for a mutator trial a candidate set that cannot fire is an expected cause"
}

private class PitestCommandLineProvider(
  private val applicationClasspath: ConfigurableFileCollection,
  private val targetClasses: ListProperty<String>,
  private val mutateOnly: Property<String>,
  private val excludedClasses: ListProperty<String>,
  private val targetTests: Property<String>,
  private val sourceDirectories: ConfigurableFileCollection,
  private val reportDirectory: DirectoryProperty,
  private val scopedReportDirectory: DirectoryProperty,
  private val projectBaseDirectory: DirectoryProperty,
  private val mutators: Property<String>,
  private val outputFormats: ListProperty<String>,
  private val timestampedReports: Property<Boolean>,
  private val verbosity: Property<String>,
  private val threads: Property<Int>,
  private val minionJvmArgs: ListProperty<String>,
  private val timeoutFactor: Property<Double>,
  private val timeoutConst: Property<Long>,
  private val mutationUnitSize: Property<Int>,
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
        reportDirectory = PitestReportDirectories.select(
          reportDirectory.get().asFile,
          scopedReportDirectory.get().asFile,
          mutateOnly.orNull,
        ),
        projectBaseDirectory = projectBaseDirectory.get().asFile,
        mutators = mutators.get(),
        outputFormats = outputFormats.get(),
        timestampedReports = timestampedReports.get(),
        verbosity = verbosity.get(),
        threads = threads.get(),
        minionJvmArgs = minionJvmArgs.get(),
        timeoutFactor = timeoutFactor.get().toString(),
        timeoutConst = timeoutConst.get(),
        historyActive = historyActiveForExecution,
        historyFile = historyFile.get().asFile,
        mutationUnitSize = mutationUnitSize.get(),
      )
    )
  }
}

internal object PitestReportDirectories {
  fun normalizedScope(mutateOnly: String?): String? = mutateOnly?.trim()?.also { scope ->
    if (scope.isEmpty()) {
      throw GradleException(
        "-PmutateOnly requires a nonblank class glob; omit the property for a full-population run"
      )
    }
  }

  fun select(full: File, scoped: File, mutateOnly: String?): File =
    if (normalizedScope(mutateOnly) == null) full else scoped
}
