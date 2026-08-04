package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLauncher
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.PitestEvidence
import software.sava.build.hardening.PitestEvidenceSnapshot
import software.sava.build.hardening.PitestEvidenceSnapshotInput
import javax.inject.Inject

/** Managed inputs for execution-time revalidation of one mutation suite. */
abstract class PitestEvidenceSpec @Inject constructor(private val specName: String) : Named {
  @Internal
  override fun getName(): String = specName

  @get:Input abstract val suiteName: Property<String>
  @get:Internal abstract val projectPath: Property<String>
  @get:Internal abstract val projectDirectory: DirectoryProperty
  @get:Internal abstract val reportDirectory: DirectoryProperty
  // These collections intentionally are not task inputs. The task is untracked and
  // must inspect the manifest before realizing any of them; marking them @Classpath
  // would resolve PIT before the action and break the N-1 no-manifest path.
  @get:Internal abstract val pluginCode: ConfigurableFileCollection
  @get:Internal abstract val sourceFiles: ConfigurableFileCollection
  @get:Internal abstract val classFiles: ConfigurableFileCollection
  @get:Internal abstract val runtimeClasspath: ConfigurableFileCollection
  @get:Internal abstract val toolClasspath: ConfigurableFileCollection
  @get:Nested abstract val javaLauncher: Property<JavaLauncher>

  @get:Input abstract val pitestVersion: Property<String>
  @get:Input abstract val junitPluginVersion: Property<String>
  @get:Input abstract val targetClasses: ListProperty<String>
  @get:Input abstract val excludedClasses: ListProperty<String>
  @get:Input abstract val targetTests: Property<String>
  @get:Input abstract val mutators: Property<String>
  @get:Input abstract val threads: Property<Int>
  @get:Input abstract val timeoutFactor: Property<Double>
  @get:Input abstract val timeoutConst: Property<Long>
  @get:Input abstract val mutationBytecodeRelease: Property<Int>
  @get:Input abstract val recompileExcludes: ListProperty<String>

  fun capture(recorded: PitestEvidence, useRecordedReportHash: Boolean): PitestEvidence {
    val reportDir = reportDirectory.get().asFile
    val report = reportDir.resolve("mutations.csv")
    val scope = reportDir.resolve(".scoped").takeIf { it.isFile }
      ?.readText()?.trim().orEmpty().ifEmpty { PitestEvidence.FULL_SCOPE }
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
      targetClasses = targetClasses.get(),
      excludedClasses = excludedClasses.get(),
      targetTests = targetTests.get(),
      mutators = mutators.get(),
      threads = threads.get(),
      timeoutFactor = timeoutFactor.get(),
      timeoutConst = timeoutConst.get(),
      mutationBytecodeRelease = mutationBytecodeRelease.get(),
      recompileExcludes = recompileExcludes.get(),
      reportSha256 = if (useRecordedReportHash) recorded.reportSha256 else PitestEvidence.sha256(report),
      scope = scope,
      historyAssisted = reportDir.resolve(".history-assisted").isFile,
    ))
  }
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
  @get:Input abstract val fullEvidenceOnly: Property<Boolean>

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  init {
    fullEvidenceOnly.convention(false)
  }

  @TaskAction
  fun validate() {
    val reportDir = evidence.reportDirectory.get().asFile
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
    val expected = evidence.capture(recorded, useRecordedReportHash = false)
    val differences = recorded.differences(expected)
    if (differences.isNotEmpty()) {
      throw GradleException(
        diagnosticPrefix.get() + differences.joinToString("\n") { "  $it" }
      )
    }
    certificationSession.get().recordRevalidated(
      evidence.projectPath.get(), evidence.suiteName.get(), recorded
    )
  }
}

/** Public certification task's final, task-owned freshness action. */
@UntrackedTask(because = "Certification must fingerprint the final dependency outputs on every invocation")
abstract class HardeningCertificationTask @Inject constructor(objects: org.gradle.api.model.ObjectFactory) : DefaultTask() {
  @get:Nested
  val suiteEvidence: NamedDomainObjectContainer<PitestEvidenceSpec> =
    objects.domainObjectContainer(PitestEvidenceSpec::class.java)

  @TaskAction
  fun validateFinalInputs() {
    suiteEvidence.sortedBy { it.name }.forEach { evidence ->
      val reportDir = evidence.reportDirectory.get().asFile
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
      val current = evidence.capture(recorded, useRecordedReportHash = true)
      val differences = recorded.differences(current)
      if (differences.isNotEmpty()) {
        throw GradleException(
          "hardeningCertify: '${evidence.suiteName.get()}' inputs changed after verification — refusing to " +
            "commit a stale receipt:\n" + differences.joinToString("\n") { "  $it" }
        )
      }
    }
  }
}
