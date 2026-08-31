package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningAgentProsePolicy
import software.sava.build.hardening.HardeningAgentTemplateBlock
import software.sava.build.hardening.HardeningRepositoryCheckCoordinator

/** Non-failing migration audit for repository-owned prose in the root AGENTS.md. */
@UntrackedTask(because = "Consumer AGENTS.md migration debt must be inspected whenever its gate runs")
abstract class HardeningAgentProseAuditTask : DefaultTask() {

  @get:Input
  abstract val helpTaskPath: Property<String>

  @get:Input
  abstract val repositoryCheckKey: Property<String>

  @get:Internal
  abstract val agentsFile: RegularFileProperty

  @get:ServiceReference("hardeningAdvisoryLog")
  abstract val advisoryLog: Property<HardeningAdvisoryLog>

  @get:ServiceReference("hardeningRepositoryCheckCoordinator")
  abstract val repositoryCheckCoordinator: Property<HardeningRepositoryCheckCoordinator>

  @TaskAction
  fun auditLocalProse() {
    val agents = agentsFile.get().asFile
    if (!repositoryCheckCoordinator.get().claim(
        "hardeningAgentProseAudit",
        agents.absoluteFile.normalize().path,
        repositoryCheckKey.get(),
        "consumer-prose",
      )) {
      logger.info("hardeningAgentProseAudit: repository-scoped check already ran in this build")
      return
    }
    if (!agents.isFile) return

    val lines = agents.readLines()
    val templateInspection = try {
      HardeningAgentTemplateBlock.inspect(lines)
    } catch (_: HardeningAgentTemplateBlock.Invalid) {
      // Boundary and marker failures already have one authoritative owner. Guessing the
      // shared-body extent here would make the policy audit report the generated rules.
      logger.info(
        "hardeningAgentProseAudit: AGENTS.md has no auditable bounded block; " +
          "agentsTemplateInSync owns its structural result"
      )
      return
    }
    if (templateInspection.marker == null) {
      logger.info(
        "hardeningAgentProseAudit: AGENTS.md has no template acknowledgment; " +
          "agentsTemplateInSync owns its structural result"
      )
      return
    }
    val parsed = try {
      HardeningAgentTemplateBlock.parse(lines)
    } catch (_: HardeningAgentTemplateBlock.Invalid) {
      // Boundary and marker failures already have one authoritative owner. Guessing the
      // shared-body extent here would make the policy audit report the generated rules.
      logger.info(
        "hardeningAgentProseAudit: AGENTS.md has no auditable bounded block; " +
          "agentsTemplateInSync owns its structural result"
      )
      return
    }
    val inspection = HardeningAgentProsePolicy.inspect(parsed.outsideLines)
    if (inspection.isClean) {
      logger.info(
        "hardeningAgentProseAudit: AGENTS.md local prose contains no known " +
          "plugin-mechanics migration findings"
      )
      return
    }

    logger.warn(HardeningAgentProsePolicy.warning(inspection, helpTaskPath.get()))
    val count = inspection.findings.size
    advisoryLog.get().record(
      "repository AGENTS.md",
      "$count likely copied plugin-mechanics ${if (count == 1) "passage" else "passages"}",
    )
  }
}
