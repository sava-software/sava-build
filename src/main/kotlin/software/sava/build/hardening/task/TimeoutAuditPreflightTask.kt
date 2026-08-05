package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.BaselineWriteOperation
import software.sava.build.hardening.HardeningCertificationSession
import software.sava.build.hardening.HardeningOperationSession
import software.sava.build.hardening.TimeoutAudit

/**
 * Cheap committed-file half of the strict timeout audit.
 *
 * Certification and provenance rebase run this before PIT so a migration-only
 * classification defect cannot consume a full mutation observation and then discard
 * it. Mutant-facing checks still belong to the report verifier.
 */
@UntrackedTask(because = "Strict timeout metadata must be inspected before every mutation run that depends on it")
abstract class TimeoutAuditPreflightTask : DefaultTask() {

  @get:Input
  abstract val suiteName: Property<String>

  @get:Input
  abstract val hardeningProjectPath: Property<String>

  @get:Input
  abstract val strictRequested: Property<Boolean>

  @get:Internal
  abstract val projectDirectory: DirectoryProperty

  @get:Internal
  abstract val timeoutsFile: RegularFileProperty

  @get:Internal
  abstract val readmeFile: RegularFileProperty

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  @get:ServiceReference("hardeningOperationSession")
  abstract val operationSession: Property<HardeningOperationSession>

  @TaskAction
  fun validateCommittedAudit() {
    val suite = suiteName.get()
    val projectPath = hardeningProjectPath.get()
    val operation = operationSession.get().suiteOperation(projectPath, suite)
    val certificationActive = certificationSession.get().isActive(projectPath)
    val strict = strictRequested.get() ||
      certificationActive ||
      operation == BaselineWriteOperation.REBASE
    if (!strict) return

    val projectDir = projectDirectory.get().asFile
    val timeouts = timeoutsFile.get().asFile
    val readme = readmeFile.get().asFile
    listOf(timeouts, readme).forEach {
      BaselineFiles.requireRegularFileOrMissing(projectDir, it)
    }
    if (!timeouts.isFile) return

    val membership = TimeoutAudit.parse(timeouts.readLines())
    val malformed = membership.malformed
    val causeFindings = TimeoutAudit.causeFindings(membership, membership.members)
    val undocumented = TimeoutAudit.undocumentedCauses(membership.members) {
      readme.takeIf { it.isFile }?.readText() ?: ""
    }

    TimeoutAudit.malformedWarning(suite, timeouts.name, malformed)
      ?.let { logger.warn(it) }
    if (causeFindings.isNotEmpty()) {
      logger.warn(TimeoutAudit.causeFindingWarning(suite, timeouts.name, causeFindings))
    }
    if (undocumented.isNotEmpty()) {
      logger.warn(TimeoutAudit.undocumentedCauseWarning(suite, undocumented))
    }
    if (malformed.isNotEmpty() || causeFindings.isNotEmpty() || undocumented.isNotEmpty()) {
      val pitestTask = "pitest" + suite.replaceFirstChar(Char::uppercase)
      val reason =
        "pitest '$suite': committed timeout audit is not ready for a strict mutation run — " +
          "${malformed.size} malformed membership row(s), ${causeFindings.size} inadmissible or " +
          "unfinished cause classification(s), and ${undocumented.size} member(s) without a " +
          "README cause. Run ${pitestTask}Debt for the same read-only detail; classify and " +
          "document every committed member, or use an ordinary $pitestTask observation to " +
          "prove and remove stale rows, before retrying. PIT has not run."
      if (certificationActive || operation == BaselineWriteOperation.REBASE) {
        try {
          operationSession.get().reject(projectPath, reason)
        } catch (rejected: IllegalArgumentException) {
          throw GradleException(reason, rejected)
        }
      }
      throw GradleException(reason)
    }
  }
}
