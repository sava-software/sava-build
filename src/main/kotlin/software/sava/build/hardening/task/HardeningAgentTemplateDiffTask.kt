package software.sava.build.hardening.task

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import software.sava.build.hardening.HardeningAgentTemplateBlock
import software.sava.build.hardening.HardeningRepositoryCheckCoordinator
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Prints a review-only diff from the consumer's bounded hardening instructions to
 * the exact template carried by the installed plugin.
 *
 * The digest marker deliberately acknowledges judgment rather than checksumming the
 * block, so this task never edits AGENTS.md and never decides that a prose difference
 * is acceptable. Explicit boundary comments are required: legacy consumers place the
 * digest before or after the block and may substantially adapt it, so guessing from
 * headings, bullet text, or marker proximity would compare the wrong instructions
 * while looking authoritative.
 */
@UntrackedTask(because = "A template review must print its result on every invocation")
abstract class HardeningAgentTemplateDiffTask : DefaultTask() {

  @get:Internal
  abstract val agentsFile: RegularFileProperty

  @get:Input
  abstract val installedTemplate: Property<String>

  @get:Input
  abstract val repositoryCheckKey: Property<String>

  @get:ServiceReference("hardeningRepositoryCheckCoordinator")
  abstract val repositoryCheckCoordinator: Property<HardeningRepositoryCheckCoordinator>

  @get:Inject
  protected abstract val execOperations: ExecOperations

  @TaskAction
  fun printDiff() {
    val agents = agentsFile.get().asFile
    if (!repositoryCheckCoordinator.get().claim(
        "hardeningAgentTemplateDiff",
        agents.absoluteFile.normalize().path,
        repositoryCheckKey.get(),
        "review",
      )) {
      logger.info("hardeningAgentTemplateDiff: repository-scoped check already ran in this build")
      return
    }
    if (!agents.isFile) {
      val templateTaskPath = path.substringBeforeLast(':') + ":hardeningAgentTemplate"
      throw GradleException(
        "hardeningAgentTemplateDiff: ${agents.absolutePath} does not exist; run " +
          "$templateTaskPath and add its bounded block first."
      )
    }

    val parsed = try {
      HardeningAgentTemplateBlock.parse(agents.readLines())
    } catch (invalid: HardeningAgentTemplateBlock.Invalid) {
      throw GradleException("hardeningAgentTemplateDiff: ${invalid.message}", invalid)
    }
    val acknowledged = parsed.lines.joinToString("\n").trimEnd() + "\n"
    val installed = installedTemplate.get().trimEnd() + "\n"

    val oldFile = temporaryDir.resolve("acknowledged-template.txt")
    val newFile = temporaryDir.resolve("installed-template.txt")
    oldFile.writeText(acknowledged)
    newFile.writeText(installed)
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result = execOperations.exec {
      workingDir(temporaryDir)
      commandLine(
        "git", "diff", "--no-index", "--no-ext-diff", "--no-color",
        "--src-prefix=acknowledged/", "--dst-prefix=installed/", "--",
        oldFile.name, newFile.name,
      )
      standardOutput = stdout
      errorOutput = stderr
      isIgnoreExitValue = true
    }
    when (result.exitValue) {
      0 -> logger.lifecycle(
        "hardeningAgentTemplateDiff: the bounded AGENTS.md block matches the installed template."
      )
      1 -> logger.quiet(stdout.toString(Charsets.UTF_8).trimEnd())
      else -> throw GradleException(
        "hardeningAgentTemplateDiff: git diff failed (exit ${result.exitValue}): " +
          stderr.toString(Charsets.UTF_8).trim()
      )
    }
  }

  companion object {
    const val BLOCK_START = HardeningAgentTemplateBlock.BLOCK_START
    const val BLOCK_END = HardeningAgentTemplateBlock.BLOCK_END
  }
}
