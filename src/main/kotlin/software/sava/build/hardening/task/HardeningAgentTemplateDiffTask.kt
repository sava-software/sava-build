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
      throw GradleException(
        "hardeningAgentTemplateDiff: ${agents.absolutePath} does not exist; run " +
          "hardeningAgentTemplate and add its bounded block first."
      )
    }

    val lines = agents.readLines()
    fun boundaryText(line: String): String = line.trim().let {
      if (it.startsWith(">")) it.removePrefix(">").trim() else it
    }
    val starts = lines.indices.filter { boundaryText(lines[it]) == BLOCK_START }
    val ends = lines.indices.filter { boundaryText(lines[it]) == BLOCK_END }
    if (starts.size != 1 || ends.size != 1 || starts.single() >= ends.single()) {
      throw GradleException(
        "hardeningAgentTemplateDiff: AGENTS.md must contain exactly one ordered " +
          "boundary pair around only the hardening template block. Wrap the existing " +
          "adapted block between these exact lines:\n  $BLOCK_START\n" +
          "  ... existing adapted hardening block ...\n  $BLOCK_END\n" +
          "This task will not guess the block from headings or digest-marker placement."
      )
    }

    val block = lines.subList(starts.single() + 1, ends.single())
    if (block.all(String::isBlank)) {
      throw GradleException(
        "hardeningAgentTemplateDiff: the bounded hardening template block in AGENTS.md is empty."
      )
    }
    val nonBlank = block.filterNot(String::isBlank)
    val quotedLines = nonBlank.count { it.startsWith(">") }
    val acknowledged = when (quotedLines) {
      0 -> block
      nonBlank.size -> block.map { line ->
        when {
          line == ">" -> ""
          line.startsWith("> ") -> line.removePrefix("> ")
          line.startsWith(">") -> line.removePrefix(">")
          else -> line
        }
      }
      else -> throw GradleException(
        "hardeningAgentTemplateDiff: the bounded block mixes quoted and unquoted nonblank " +
          "lines. Quote every line or none; refusing to normalize an ambiguous block."
      )
    }.joinToString("\n").trimEnd() + "\n"
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
    const val BLOCK_START = "<!-- hardening-template block:start -->"
    const val BLOCK_END = "<!-- hardening-template block:end -->"
  }
}
