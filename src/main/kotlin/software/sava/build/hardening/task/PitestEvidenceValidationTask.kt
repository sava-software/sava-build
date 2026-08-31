package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.HardeningOperationSession
import software.sava.build.hardening.HardeningPluginIdentityGuard
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.CertificationGitIdentity
import software.sava.build.hardening.CertificationGitIdentityCapture
import software.sava.build.hardening.MutationToolchainRecord
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.PitestEvidenceSnapshot
import software.sava.build.hardening.PitestEvidenceSnapshotInput
import software.sava.build.hardening.ProjectWriteOperation
import software.sava.build.hardening.qualifiedHardeningTaskPath
import java.io.File
import javax.inject.Inject
import java.time.Clock
import java.time.LocalDate

/** One installed authority for restarting an atomic, project-scoped certification receipt. */
internal fun certificationRetryGuidance(projectPath: String): String {
  val taskPath = if (projectPath == ":") ":hardeningCertify" else "$projectPath:hardeningCertify"
  return "\n  Retry: after resolving the condition above, run $taskPath in a new Gradle " +
    "invocation. Its receipt is project-atomic: every suite in this project re-executes in " +
    "that invocation; completed receipts from other projects remain independent."
}

/** Owns and invalidates durable certification state before any expensive PIT task runs. */
@UntrackedTask(because = "Certification must invalidate prior evidence on every invocation")
abstract class HardeningCertificationPreflightTask : DefaultTask() {
  @get:Internal abstract val certificationProjectDirectory: DirectoryProperty
  @get:Internal abstract val receiptFile: RegularFileProperty
  @get:Internal abstract val runningFile: RegularFileProperty
  @get:Internal abstract val lockFile: RegularFileProperty
  @get:Internal abstract val legacyBuildDirectory: DirectoryProperty
  @get:Internal abstract val legacyReceiptFile: RegularFileProperty
  @get:Internal abstract val legacyRunningFile: RegularFileProperty
  @get:Classpath abstract val certificationPluginCode: ConfigurableFileCollection
  @get:Input abstract val expectedPluginSha256: Property<String>
  @get:Input abstract val localRepoArtifactPath: Property<String>
  @get:Input abstract val expectedLocalRepoArtifactSha256: Property<String>
  @get:Input abstract val hardeningProjectPath: Property<String>
  @get:Input abstract val presentForbiddenProperties: ListProperty<String>
  @get:Input abstract val excludedTaskNames: ListProperty<String>

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  @get:Inject
  protected abstract val execOperations: ExecOperations

  @TaskAction
  fun startCertification() {
    val projectDirectory = certificationProjectDirectory.get().asFile
    val receipt = receiptFile.get().asFile
    val running = runningFile.get().asFile
    val lock = lockFile.get().asFile
    val historyDirectory = receipt.parentFile
    val projectPath = hardeningProjectPath.get()

    try {
      BaselineFiles.requireDirectoryOrMissing(projectDirectory, historyDirectory)
      listOf(receipt, running, lock).forEach {
        BaselineFiles.requireRegularFileOrMissing(projectDirectory, it)
      }
      val stateFindings = CertificationGitIdentityCapture.machineLocalStateFindings(
        projectDirectory,
        listOf(receipt, running, lock),
        execOperations,
      )
      if (stateFindings.isNotEmpty()) {
        throw IllegalStateException(
          "durable certification state must be machine-local before PIT runs:\n" +
            stateFindings.joinToString("\n") { "  $it" } +
            "\nRun ${qualifiedHardeningTaskPath(projectPath, "hardeningInit")} or add " +
            ".pitest-history/ to the worktree's Git ignore rules."
        )
      }
      historyDirectory.mkdirs()
      BaselineFiles.requireDirectoryOrMissing(projectDirectory, historyDirectory)
      listOf(receipt, running, lock).forEach {
        BaselineFiles.requireRegularFileOrMissing(projectDirectory, it)
      }

      val expectedPlugin = expectedPluginSha256.get()
      val session = certificationSession.get()
      val sessionId = session.activate(projectPath, expectedPlugin, lock)

      // Ownership comes first. A competing process must not invalidate evidence or
      // overwrite the sentinel belonging to the process that holds the lock.
      BaselineFiles.deleteIfExists(receipt)
      BaselineFiles.writeAtomically(projectDirectory, running, "session\t$sessionId\n")

      // Remove the one-generation build-output location. buildDirectory is
      // configurable and may intentionally be outside the checkout, so confine this
      // cleanup to the exact configured root rather than projectDirectory.
      val legacyRoot = legacyBuildDirectory.get().asFile
      val legacyReceipt = legacyReceiptFile.get().asFile
      val legacyRunning = legacyRunningFile.get().asFile
      BaselineFiles.requireDirectoryOrMissing(legacyRoot)
      listOf(legacyReceipt, legacyRunning).forEach {
        BaselineFiles.requireRegularFileOrMissing(legacyRoot, it)
      }
      val removedLegacyState = buildList {
        if (BaselineFiles.deleteIfExists(legacyReceipt)) add(legacyReceipt)
        if (BaselineFiles.deleteIfExists(legacyRunning)) add(legacyRunning)
      }
      if (removedLegacyState.isNotEmpty()) {
        logger.lifecycle(
          "hardeningCertify: removed superseded legacy build-output certification state; " +
            "replacement evidence is published only after success at " +
            "${receipt.relativeTo(projectDirectory).invariantSeparatorsPath}:\n" +
            removedLegacyState.joinToString("\n") { "  removed ${it.absolutePath}" } +
            "\n  Migration notice; not an advisory finding."
        )
      }

      val forbidden = presentForbiddenProperties.get().sorted()
      val excluded = excludedTaskNames.get().sorted()
      if (forbidden.isNotEmpty() || excluded.isNotEmpty()) {
        throw IllegalStateException(
          "hardeningCertify is observation-only and full-population; remove " +
            buildList {
              if (forbidden.isNotEmpty()) {
                add("incompatible flag(s): " + forbidden.joinToString(", ") { "-P$it" })
              }
              if (excluded.isNotEmpty()) {
                add("task exclusion(s): " + excluded.joinToString(", ") { "-x $it" })
              }
            }.joinToString("; ")
        )
      }
      try {
        HardeningPluginIdentityGuard.requireUnchanged(
          certificationPluginCode.singleFile,
          expectedPlugin,
          localRepoArtifactPath.get(),
          expectedLocalRepoArtifactSha256.get(),
          "hardeningCertify: project '$projectPath' before preflight",
        )
      } catch (failure: IllegalStateException) {
        throw IllegalStateException(
          "${failure.message}; refusing mixed plugin bytes before PIT", failure)
      }
    } catch (failure: Exception) {
      val session = certificationSession.get()
      if (session.ownsCertification(projectPath)) {
        try {
          BaselineFiles.requireRegularFileOrMissing(projectDirectory, receipt)
          BaselineFiles.deleteIfExists(receipt)
          BaselineFiles.requireRegularFileOrMissing(projectDirectory, running)
          val reason = (failure.message ?: failure::class.java.simpleName)
            .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
          BaselineFiles.writeAtomically(projectDirectory, running, "refused\t$reason\n")
        } catch (stateFailure: Exception) {
          failure.addSuppressed(stateFailure)
        }
      }
      throw GradleException(
        "hardeningCertify: ${failure.message}" + certificationRetryGuidance(projectPath),
        failure,
      )
    }
  }
}

/** Managed inputs for execution-time revalidation of one mutation suite. */
abstract class PitestEvidenceSpec @Inject constructor(private val specName: String) : Named {
  @Internal
  override fun getName(): String = specName

  @get:Input abstract val suiteName: Property<String>
  @get:Internal abstract val projectPath: Property<String>
  @get:Internal abstract val projectDirectory: DirectoryProperty
  @get:Internal abstract val reportDirectory: DirectoryProperty
  @get:Internal abstract val scopedReportDirectory: DirectoryProperty
  @get:Input @get:Optional abstract val mutateOnly: Property<String>
  // These collections intentionally are not task inputs. The task is untracked and
  // must inspect the manifest before realizing any of them; marking them @Classpath
  // would resolve PIT before the action and break the N-1 no-manifest path.
  @get:Internal abstract val pluginCode: ConfigurableFileCollection
  @get:Input abstract val expectedPluginSha256: Property<String>
  @get:Input abstract val localRepoArtifactPath: Property<String>
  @get:Input abstract val expectedLocalRepoArtifactSha256: Property<String>
  @get:Internal abstract val sourceFiles: ConfigurableFileCollection
  @get:Internal abstract val classFiles: ConfigurableFileCollection
  @get:Internal abstract val runtimeClasspath: ConfigurableFileCollection
  @get:Internal abstract val toolClasspath: ConfigurableFileCollection
  @get:Nested abstract val javaLauncher: Property<JavaLauncher>

  @get:Input abstract val pitestVersion: Property<String>
  @get:Input abstract val junitPluginVersion: Property<String>
  @get:Input abstract val arcMutateBaseVersion: Property<String>
  @get:Input abstract val arcMutateLicensed: Property<Boolean>
  @get:Input abstract val targetClasses: ListProperty<String>
  @get:Input abstract val excludedClasses: ListProperty<String>
  @get:Input abstract val targetTests: Property<String>
  @get:Input abstract val excludedTestClasses: ListProperty<String>
  @get:Input abstract val mainClass: Property<String>
  @get:Input abstract val mutators: Property<String>
  @get:Input abstract val threads: Property<Int>
  @get:Input abstract val minionJvmArgs: ListProperty<String>
  @get:Input abstract val timeoutFactor: Property<Double>
  @get:Input abstract val timeoutConst: Property<Long>
  @get:Input abstract val mutationUnitSize: Property<Int>
  @get:Input abstract val verbosity: Property<String>
  @get:Input abstract val mutationBytecodeRelease: Property<Int>
  @get:Input abstract val recompileExcludes: ListProperty<String>

  init {
    mutationUnitSize.convention(0)
    mainClass.convention(PitestEvidenceSnapshot.DEFAULT_MAIN_CLASS)
    verbosity.convention(HardeningCommandLines.PitestVerbosity.DEFAULT)
  }

  fun capture(recorded: PitestEvidence, useRecordedReportHash: Boolean): PitestEvidence {
    val reportDir = selectedReportDirectory()
    val report = reportDir.resolve("mutations.csv")
    val scope = reportDir.resolve(".scoped").takeIf { it.isFile }
      ?.readText()?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }
    return capture(recorded, report, useRecordedReportHash, scope,
      reportDir.resolve(".history-assisted").isFile)
  }

  fun captureSnapshot(recorded: PitestEvidence, report: File): PitestEvidence =
    capture(recorded, report, false, recorded.scope, recorded.historyAssisted)

  /** Recaptures every input while binding the exact report bytes the caller parsed. */
  fun captureFinal(
    recorded: PitestEvidence,
    reportSha256: String,
    scope: String,
    historyAssisted: Boolean,
  ): PitestEvidence = capture(
    recorded,
    reportSha256,
    scope,
    historyAssisted,
  )

  /** Captures only the effective mutation engine/licence identity for read-only Debt. */
  internal fun captureMutationToolchain(): MutationToolchainRecord = MutationToolchainRecord.capture(
    pitestVersion = pitestVersion.get(),
    junitPluginVersion = junitPluginVersion.get(),
    toolClasspath = toolClasspath.files,
    arcMutateBaseVersion = arcMutateBaseVersion.get(),
    arcMutateEnabled = arcMutateLicensed.get(),
    reportDirectory = selectedReportDirectory(),
    projectBaseDirectory = projectDirectory.get().asFile,
    lookupStartDirectory = projectDirectory.get().asFile,
    observationDate = LocalDate.now(Clock.systemUTC()),
  )

  internal fun selectedReportDirectory(): File = PitestReportDirectories.select(
    reportDirectory.get().asFile,
    scopedReportDirectory.get().asFile,
    mutateOnly.orNull,
  )

  private fun capture(
    recorded: PitestEvidence,
    report: File,
    useRecordedReportHash: Boolean,
    scope: String,
    historyAssisted: Boolean,
  ): PitestEvidence = capture(
    recorded,
    if (useRecordedReportHash) recorded.reportSha256 else PitestEvidence.sha256(report),
    scope,
    historyAssisted,
  )

  private fun capture(
    recorded: PitestEvidence,
    reportSha256: String,
    scope: String,
    historyAssisted: Boolean,
  ): PitestEvidence {
    requirePluginCodeUnchanged()
    val toolchain = captureMutationToolchain()
    return PitestEvidenceSnapshot.capture(PitestEvidenceSnapshotInput(
      suite = suiteName.get(),
      invocationId = recorded.invocationId,
      pitestVersion = pitestVersion.get(),
      junitPluginVersion = junitPluginVersion.get(),
      javaVersion = javaLauncher.get().metadata.javaRuntimeVersion,
      projectDirectory = projectDirectory.get().asFile,
      pluginCode = pluginCode.singleFile,
      sourceFiles = sourceFiles.files,
      classFiles = classFiles.files,
      runtimeClasspath = runtimeClasspath.files,
      toolClasspath = toolClasspath.files,
      mutationToolchainSha256 = toolchain.identitySha256,
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
    ), minionJvmArgs.get(), expectedPluginSha256.get(), mutationUnitSize.get(), verbosity.get(),
      excludedTestClasses.get(), mainClass.get())
  }

  internal fun requirePluginCodeUnchanged() {
    val code = pluginCode.singleFile
    try {
      HardeningPluginIdentityGuard.requireUnchanged(
        code,
        expectedPluginSha256.get(),
        localRepoArtifactPath.get(),
        expectedLocalRepoArtifactSha256.get(),
        "mutation evidence validation",
      )
    } catch (e: IllegalStateException) {
      throw GradleException(
        "${e.message}; refusing evidence from mixed plugin bytes", e)
    }
  }
}

/**
 * Verify remains script-wired, but its final write boundary owns a managed evidence
 * specification so it can recapture the same inputs as the standalone validator.
 */
@UntrackedTask(because = "Mutation verification and record writes must inspect current evidence on every run")
abstract class PitestVerifyTask @Inject constructor(
  objects: org.gradle.api.model.ObjectFactory,
) : DefaultTask() {
  @get:Nested
  val finalEvidence: PitestEvidenceSpec =
    objects.newInstance(PitestEvidenceSpec::class.java, "suite")

  @get:Inject
  protected abstract val execOperations: ExecOperations

  internal fun ignoredUntrackedRecordFiles(
    projectDirectory: File,
    recordFiles: Iterable<File>,
  ): List<String> = CertificationGitIdentityCapture.ignoredUntrackedRecordFiles(
      projectDirectory,
      recordFiles,
      execOperations,
  )
}

/** Read-only committed-debt surface with current mutation-toolchain comparison. */
@UntrackedTask(because = "Debt must compare committed provenance with the current toolchain on every run")
abstract class PitestDebtTask @Inject constructor(
  objects: org.gradle.api.model.ObjectFactory,
) : DefaultTask() {
  @get:Nested
  val currentEvidence: PitestEvidenceSpec =
    objects.newInstance(PitestEvidenceSpec::class.java, "suite")

  /** Escalates committed-file timeout findings without turning Debt into a PIT run. */
  @get:Input
  abstract val strictTimeoutAudit: Property<Boolean>
}

/**
 * Current-evidence revalidation. An N-1 snapshot reaches the action but returns before
 * reading any collection, so its compatibility path never resolves PIT. A PIT task
 * created in the same invocation is independently bound before/after execution and
 * records the same proof in the shared session.
 */
@UntrackedTask(because = "Evidence freshness must be observed whenever a current report is reused")
abstract class PitestEvidenceValidationTask @Inject constructor(objects: org.gradle.api.model.ObjectFactory) : DefaultTask() {
  @get:Nested val evidence: PitestEvidenceSpec = objects.newInstance(PitestEvidenceSpec::class.java, "suite")
  @get:Input abstract val diagnosticPrefix: Property<String>
  @get:Input abstract val standaloneRetry: Property<String>
  @get:Input abstract val fullEvidenceOnly: Property<Boolean>

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  init {
    fullEvidenceOnly.convention(false)
  }

  private fun retryGuidance(): String =
    if (certificationSession.get().isActive(evidence.projectPath.get())) {
      certificationRetryGuidance(evidence.projectPath.get())
    } else {
      "\n  Retry: ${standaloneRetry.get()}"
    }

  @TaskAction
  fun validate() {
    val reportDir = evidence.selectedReportDirectory()
    val report = reportDir.resolve("mutations.csv")
    val manifest = reportDir.resolve(".evidence.tsv")
    if (!report.isFile || !manifest.isFile || reportDir.resolve(".running").isFile) return
    if (fullEvidenceOnly.get() &&
      (reportDir.resolve(".scoped").isFile || reportDir.resolve(".history-assisted").isFile)) return
    val recorded = try {
      PitestEvidence.parse(manifest.readText())
    } catch (_: IllegalArgumentException) {
      return // The consuming task owns the public malformed-manifest diagnostic.
    }
    if (fullEvidenceOnly.get() &&
      (recorded.scope != PitestEvidence.FULL_SCOPE || recorded.historyAssisted)) return
    val toolchainFile = reportDir.resolve(".toolchain.tsv")
    if (recorded.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
      val toolchain = try {
        MutationToolchainRecord.parse(toolchainFile.readText())
      } catch (e: Exception) {
        throw GradleException(
          diagnosticPrefix.get() + "  completed mutation-toolchain record is missing or malformed: " +
            "${e.message}" + retryGuidance(), e)
      }
      if (toolchain.identitySha256 != recorded.mutationToolchainSha256) {
        throw GradleException(
          diagnosticPrefix.get() + "  completed mutation-toolchain record differs from evidence" +
            retryGuidance()
        )
      }
    }
    val expected = evidence.capture(recorded, useRecordedReportHash = false)
    val differences = recorded.differences(expected)
    if (differences.isNotEmpty()) {
      throw GradleException(
        diagnosticPrefix.get() + differences.joinToString("\n") { "  $it" } + retryGuidance()
      )
    }
    certificationSession.get().recordRevalidated(
      evidence.projectPath.get(), evidence.suiteName.get(), recorded
    )
  }
}

/** Final current-checkout validation and commit for a prepared mode-insurance write. */
@UntrackedTask(because = "Mode insurance must revalidate stored observations at the final write boundary")
abstract class PitestModeCommitTask @Inject constructor(
  objects: org.gradle.api.model.ObjectFactory,
) : DefaultTask() {
  @get:Nested
  val suiteEvidence: NamedDomainObjectContainer<PitestEvidenceSpec> =
    objects.domainObjectContainer(PitestEvidenceSpec::class.java)
  @get:Internal abstract val snapshotRoot: DirectoryProperty
  @get:Internal abstract val projectDirectory: DirectoryProperty
  @get:Input abstract val hardeningProjectPath: Property<String>

  @get:ServiceReference("hardeningOperationSession")
  abstract val operationSession: Property<HardeningOperationSession>

  @TaskAction
  fun validateAndCommit() {
    val projectPath = hardeningProjectPath.get()
    val prepared = try {
      operationSession.get().requirePreparedProjectWrites(
          projectPath, ProjectWriteOperation.MODE_FLIP_INSURANCE)
    } catch (e: IllegalArgumentException) {
      throw GradleException("pitestModeCompare: ${e.message}", e)
    }
    val trustedProject = projectDirectory.get().asFile
    prepared.writes.forEach {
      BaselineFiles.requireRegularFileOrMissing(trustedProject, File(it.targetPath))
    }
    prepared.readTrees.forEach { snapshot ->
      val root = File(snapshot.rootPath).toPath().toAbsolutePath().normalize()
      val trusted = trustedProject.toPath().toAbsolutePath().normalize()
      if (root.startsWith(trusted)) {
        BaselineFiles.requireNoSymbolicLinkComponents(trustedProject, File(snapshot.rootPath))
      }
      val differences = BaselineFiles.treeDifferences(snapshot)
      if (differences.isNotEmpty()) {
        throw GradleException(
          "pitestModeCompare: inputs changed after comparison — refusing to commit stale " +
            "mode insurance from ${snapshot.rootPath}:\n" +
            differences.joinToString("\n") { "  $it" }
        )
      }
    }
    suiteEvidence.sortedBy { it.name }.forEach(::validateSuite)
    BaselineFiles.writeAllAtomically(trustedProject, prepared.writes.map {
      BaselineFiles.Write(File(it.targetPath), it.content)
    })
    operationSession.get().recordProjectConsumed(
        projectPath, ProjectWriteOperation.MODE_FLIP_INSURANCE)
    if (prepared.writes.isNotEmpty()) {
      logger.lifecycle(
          "pitestModeCompare: flip insurance written to ${prepared.writes.size} " +
              "baseline/provenance file(s) after final current-checkout validation")
    }
  }

  private fun validateSuite(evidence: PitestEvidenceSpec) {
    val suite = evidence.suiteName.get()
    val modes = snapshotRoot.get().asFile.listFiles()
      ?.filter(File::isDirectory)?.sortedBy(File::getName).orEmpty()
    modes.forEach { mode ->
      val report = mode.resolve("$suite.csv")
      if (!report.isFile) {
        throw GradleException(
          "pitestModeCompare: snapshot '${mode.name}' lost '$suite.csv' after comparison")
      }
      val manifest = mode.resolve("$suite.evidence.tsv")
      if (!manifest.isFile) {
        throw GradleException(
          "pitestModeCompare: refusing a mode-insurance write for legacy '$suite' snapshot " +
            "'${mode.name}' without completed-run provenance; capture every mode again"
        )
      }
      val recorded = try {
        PitestEvidence.parse(manifest.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException(
          "pitestModeCompare: invalid '$suite' evidence in snapshot '${mode.name}': ${e.message}", e)
      }
      if (recorded.scope != PitestEvidence.FULL_SCOPE || recorded.historyAssisted) {
        throw GradleException(
          "pitestModeCompare: '$suite' snapshot '${mode.name}' is not fresh full evidence"
        )
      }
      val toolchainFile = mode.resolve("$suite.toolchain.tsv")
      val toolchain = if (!toolchainFile.isFile) {
        throw GradleException(
          "pitestModeCompare: '$suite' snapshot '${mode.name}' has no mutation-toolchain record"
        )
      } else try {
        MutationToolchainRecord.parse(toolchainFile.readText())
      } catch (e: IllegalArgumentException) {
        throw GradleException(
          "pitestModeCompare: invalid '$suite' mutation-toolchain record in snapshot " +
            "'${mode.name}': ${e.message}", e)
      }
      if (toolchain.identitySha256 != recorded.mutationToolchainSha256) {
        throw GradleException(
          "pitestModeCompare: '$suite' snapshot '${mode.name}' mutation-toolchain record " +
            "does not match its evidence"
        )
      }
      val current = evidence.captureSnapshot(recorded, report)
      val differences = recorded.differences(current)
      if (differences.isNotEmpty()) {
        throw GradleException(
          "pitestModeCompare: refusing to write '$suite' mode insurance from snapshot " +
            "'${mode.name}' because it no longer matches the current checkout:\n" +
            differences.joinToString("\n") { "  $it" }
        )
      }
    }
  }
}

/** Public certification task's final, task-owned freshness action. */
@UntrackedTask(because = "Certification must fingerprint the final dependency outputs on every invocation")
abstract class HardeningCertificationTask @Inject constructor(objects: org.gradle.api.model.ObjectFactory) : DefaultTask() {
  @get:Nested
  val suiteEvidence: NamedDomainObjectContainer<PitestEvidenceSpec> =
    objects.domainObjectContainer(PitestEvidenceSpec::class.java)
  @get:Internal abstract val certificationProjectDirectory: DirectoryProperty
  @get:Classpath abstract val certificationPluginCode: ConfigurableFileCollection
  // PitestEvidenceSpec keeps these collections @Internal so legacy/no-manifest
  // validators can return without resolving external tools. Certification always
  // recaptures completed evidence, so aggregate every suite's runtime/tool classpath
  // here. This declares artifact-transform outputs before the task action queries the
  // individual specs; Gradle 10 forbids discovering those outputs from the action.
  @get:Classpath abstract val certificationEvidenceClasspaths: ConfigurableFileCollection
  @get:Input abstract val expectedPluginSha256: Property<String>
  @get:Input abstract val localRepoArtifactPath: Property<String>
  @get:Input abstract val expectedLocalRepoArtifactSha256: Property<String>
  @get:Internal abstract val certificationRecordFiles: ConfigurableFileCollection
  @get:Input abstract val hardeningProjectPath: Property<String>

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  @get:Inject
  protected abstract val execOperations: ExecOperations

  private fun retryGuidance(): String =
    certificationRetryGuidance(hardeningProjectPath.get())

  @TaskAction
  fun validateFinalInputs() {
    val projectDirectory = certificationProjectDirectory.get().asFile
    val gitBefore = CertificationGitIdentityCapture.capture(projectDirectory, execOperations)
    if (gitBefore.state == CertificationGitIdentity.State.CLEAN) {
      try {
        CertificationGitIdentityCapture.requireRecordFilesMatchTree(
          projectDirectory,
          gitBefore,
          certificationRecordFiles.files,
          execOperations,
        )
      } catch (e: IllegalStateException) {
        throw GradleException("hardeningCertify: ${e.message}" + retryGuidance(), e)
      }
    }
    val pluginBeforeSha256 = currentPluginSha256("before final certification validation")
    val completedProjectSnapshots = mutableListOf<NamedProjectEvidence>()
    val currentProjectSnapshots = mutableListOf<NamedProjectEvidence>()
    suiteEvidence.sortedBy { it.name }.forEach { evidence ->
      val reportDir = evidence.selectedReportDirectory()
      val report = reportDir.resolve("mutations.csv")
      val manifest = reportDir.resolve(".evidence.tsv")
      if (!report.isFile || !manifest.isFile || reportDir.resolve(".running").isFile ||
        reportDir.resolve(".scoped").isFile || reportDir.resolve(".history-assisted").isFile) return@forEach
      val recorded = try {
        PitestEvidence.parse(manifest.readText())
      } catch (_: IllegalArgumentException) {
        return@forEach // The receipt action owns the specific malformed-evidence error.
      }
      if (recorded.scope != PitestEvidence.FULL_SCOPE || recorded.historyAssisted) return@forEach
      val toolchainFile = reportDir.resolve(".toolchain.tsv")
      if (recorded.mutationToolchainSha256 != PitestEvidence.LEGACY_MUTATION_TOOLCHAIN) {
        val toolchain = try {
          MutationToolchainRecord.parse(toolchainFile.readText())
        } catch (e: Exception) {
          throw GradleException(
            "hardeningCertify: '${evidence.suiteName.get()}' completed mutation-toolchain " +
              "record is missing or malformed: ${e.message}" + retryGuidance(), e)
        }
        if (toolchain.identitySha256 != recorded.mutationToolchainSha256) {
          throw GradleException(
            "hardeningCertify: '${evidence.suiteName.get()}' completed mutation-toolchain " +
              "record differs from evidence" + retryGuidance()
          )
        }
      }
      val current = evidence.capture(recorded, useRecordedReportHash = true)
      val differences = recorded.differences(current)
      if (differences.isNotEmpty()) {
        throw GradleException(
          "hardeningCertify: '${evidence.suiteName.get()}' inputs changed after verification — refusing to " +
            "commit a stale receipt:\n" + differences.joinToString("\n") { "  $it" } +
            retryGuidance()
        )
      }
      val suite = evidence.suiteName.get()
      completedProjectSnapshots += NamedProjectEvidence(suite, ProjectEvidence.from(recorded))
      currentProjectSnapshots += NamedProjectEvidence(suite, ProjectEvidence.from(current))
    }
    requireOneProjectTree("completed evidence", completedProjectSnapshots)
    requireOneProjectTree("current inputs", currentProjectSnapshots)
    val gitAfter = CertificationGitIdentityCapture.capture(projectDirectory, execOperations)
    if (gitAfter.state == CertificationGitIdentity.State.CLEAN) {
      try {
        CertificationGitIdentityCapture.requireRecordFilesMatchTree(
          projectDirectory,
          gitAfter,
          certificationRecordFiles.files,
          execOperations,
        )
      } catch (e: IllegalStateException) {
        throw GradleException("hardeningCertify: ${e.message}" + retryGuidance(), e)
      }
    }
    val pluginAfterSha256 = currentPluginSha256("after final certification validation")
    requirePluginIdentity("completed evidence", completedProjectSnapshots, pluginBeforeSha256)
    requirePluginIdentity("current inputs", currentProjectSnapshots, pluginAfterSha256)
    try {
      certificationSession.get().recordFinalProjectIdentity(
        hardeningProjectPath.get(),
        gitBefore,
        gitAfter,
        pluginBeforeSha256,
        pluginAfterSha256,
      )
    } catch (e: IllegalStateException) {
      throw GradleException("hardeningCertify: ${e.message}" + retryGuidance(), e)
    }
  }

  private fun currentPluginSha256(context: String): String {
    val code = certificationPluginCode.singleFile
    val expected = expectedPluginSha256.get()
    try {
      HardeningPluginIdentityGuard.requireUnchanged(
        code,
        expected,
        localRepoArtifactPath.get(),
        expectedLocalRepoArtifactSha256.get(),
        "hardeningCertify: $context",
      )
    } catch (e: IllegalStateException) {
      throw GradleException(
        "${e.message}; refusing mixed plugin bytes" + retryGuidance(), e)
    }
    return expected
  }

  /**
   * Fields whose inputs are project-wide even though PIT persists them once per suite.
   * Suite targeting and report identity deliberately remain outside this projection.
   */
  internal data class ProjectEvidence(
    val sourceSha256: String,
    val classesSha256: String,
    val javaVersion: String,
    val pitestVersion: String,
    val pluginSha256: String,
    val toolClasspathSha256: String,
    val mutationToolchainSha256: String,
  ) {
    fun differences(reference: ProjectEvidence): List<String> = buildList {
      if (sourceSha256 != reference.sourceSha256) add("sourceSha256")
      if (classesSha256 != reference.classesSha256) add("classesSha256")
      if (javaVersion != reference.javaVersion) add("javaVersion")
      if (pitestVersion != reference.pitestVersion) add("pitestVersion")
      if (pluginSha256 != reference.pluginSha256) add("pluginSha256")
      if (toolClasspathSha256 != reference.toolClasspathSha256) add("toolClasspathSha256")
      if (mutationToolchainSha256 != reference.mutationToolchainSha256) {
        add("mutationToolchainSha256")
      }
    }

    companion object {
      fun from(evidence: PitestEvidence) = ProjectEvidence(
        sourceSha256 = evidence.sourceSha256,
        classesSha256 = evidence.classesSha256,
        javaVersion = evidence.javaVersion,
        pitestVersion = evidence.pitestVersion,
        pluginSha256 = evidence.pluginSha256,
        toolClasspathSha256 = evidence.toolClasspathSha256,
        mutationToolchainSha256 = evidence.mutationToolchainSha256,
      )
    }
  }

  private data class NamedProjectEvidence(val suite: String, val evidence: ProjectEvidence)

  private fun requireOneProjectTree(label: String, snapshots: List<NamedProjectEvidence>) {
    val reference = snapshots.firstOrNull() ?: return
    val conflicts = snapshots.drop(1).mapNotNull { candidate ->
      candidate.evidence.differences(reference.evidence).takeIf(List<String>::isNotEmpty)?.let {
        "'${candidate.suite}' differs from '${reference.suite}' in ${it.joinToString(", ")}"
      }
    }
    if (conflicts.isNotEmpty()) {
      throw GradleException(
        "hardeningCertify: suites do not describe one project-wide tree in $label — " +
          "refusing to commit a mixed-observation receipt:\n" +
          conflicts.joinToString("\n") { "  $it" } + retryGuidance()
      )
    }
  }

  private fun requirePluginIdentity(
    label: String,
    snapshots: List<NamedProjectEvidence>,
    pluginSha256: String,
  ) {
    val mismatches = snapshots.filter { it.evidence.pluginSha256 != pluginSha256 }.map { it.suite }
    if (mismatches.isNotEmpty()) {
      throw GradleException(
        "hardeningCertify: $label for suite(s) ${mismatches.joinToString(", ") { "'$it'" }} " +
          "does not match the project-wide hardening plugin code — refusing to commit a " +
          "mixed-plugin receipt" + retryGuidance()
      )
    }
  }
}
