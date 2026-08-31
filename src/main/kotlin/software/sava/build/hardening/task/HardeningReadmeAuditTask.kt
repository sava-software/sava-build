package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningReadmePolicy

/** Non-failing migration audit for one project's mutation-evidence README. */
@UntrackedTask(because = "Consumer README migration debt must be inspected whenever its gate runs")
abstract class HardeningReadmeAuditTask : DefaultTask() {

  @get:Input
  abstract val hardeningProjectPath: Property<String>

  @get:Input
  abstract val helpTaskPath: Property<String>

  @get:Internal
  abstract val projectDirectory: DirectoryProperty

  @get:Internal
  abstract val readmeFile: RegularFileProperty

  @get:ServiceReference("hardeningAdvisoryLog")
  abstract val advisoryLog: Property<HardeningAdvisoryLog>

  @TaskAction
  fun auditReadme() {
    val projectDir = projectDirectory.get().asFile
    val readme = readmeFile.get().asFile
    BaselineFiles.requireRegularFileOrMissing(projectDir, readme)
    if (!readme.isFile) return

    val inspection = HardeningReadmePolicy.inspect(readme.readText())
    if (inspection.isClean) {
      logger.info("hardeningReadmeAudit: $readme contains no migration-policy findings")
      return
    }

    logger.warn(HardeningReadmePolicy.warning(
      "config/pitest/README.md",
      inspection,
      helpTaskPath.get(),
    ))
    val scope = (if (hardeningProjectPath.get() == ":") "" else "${hardeningProjectPath.get()} ") +
      "config/pitest/README.md"
    if (inspection.sourceLocators.isNotEmpty()) {
      advisoryLog.get().record(
        scope,
        counted(inspection.sourceLocators.size, "likely source-line locator"),
      )
    }
    if (inspection.inheritedScaffolds.isNotEmpty()) {
      advisoryLog.get().record(
        scope,
        counted(inspection.inheritedScaffolds.size, "inherited scaffold-mechanics passage"),
      )
    }
  }

  private fun counted(count: Int, singular: String, plural: String = "${singular}s"): String =
    "$count ${if (count == 1) singular else plural}"
}
