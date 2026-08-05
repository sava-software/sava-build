package software.sava.build.hardening

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/** Git identity observed at the final certification boundary. */
internal data class CertificationGitIdentity(
  val state: State,
  val commit: String,
  val tree: String,
  val statusSha256: String,
  val projectDirectory: String,
) {
  enum class State(val receiptValue: String) {
    CLEAN("clean"),
    DIRTY("dirty"),
    UNAVAILABLE("unavailable"),
  }

  init {
    if (state == State.UNAVAILABLE) {
      require(commit == UNAVAILABLE && tree == UNAVAILABLE &&
        statusSha256 == UNAVAILABLE && projectDirectory == UNAVAILABLE)
    } else {
      require(commit.matches(gitObjectPattern)) { "Git commit is not a full 40-hex object id" }
      require(tree.matches(gitObjectPattern)) { "Git tree is not a full 40-hex object id" }
      require(statusSha256.matches(sha256Pattern)) { "Git status digest is not SHA-256" }
      require(projectDirectory == "." ||
        (projectDirectory.isNotEmpty() && !projectDirectory.startsWith("/") &&
          projectDirectory.split('/').none { it.isEmpty() || it == "." || it == ".." })) {
        "Git-relative project directory is not normalized: '$projectDirectory'"
      }
      require('\t' !in projectDirectory && '\n' !in projectDirectory && '\r' !in projectDirectory) {
        "Git-relative project directory contains a TSV delimiter"
      }
      if (state == State.CLEAN) {
        require(statusSha256 == EMPTY_STATUS_SHA256) {
          "a clean Git identity must bind the empty porcelain status"
        }
      } else {
        require(statusSha256 != EMPTY_STATUS_SHA256) {
          "a dirty Git identity must bind a non-empty porcelain status"
        }
      }
    }
  }

  companion object {
    const val UNAVAILABLE = "unavailable"
    val EMPTY_STATUS_SHA256: String = PitestEvidence.sha256(byteArrayOf())
    private val gitObjectPattern = Regex("[0-9a-f]{40}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun unavailable() = CertificationGitIdentity(
      State.UNAVAILABLE,
      UNAVAILABLE,
      UNAVAILABLE,
      UNAVAILABLE,
      UNAVAILABLE,
    )

    fun requireUnchanged(
      before: CertificationGitIdentity,
      after: CertificationGitIdentity,
    ) {
      check(before == after) {
        "Git worktree identity changed during final certification validation: " +
          "before=$before, after=$after"
      }
    }
  }
}

/**
 * Read-only Git inspection through Gradle's execution service. Command failure means
 * Git provenance is unavailable, not that ordinary local certification is forbidden.
 */
internal object CertificationGitIdentityCapture {

  fun capture(projectDirectory: File, execOperations: ExecOperations): CertificationGitIdentity {
    val firstRevision = revision(projectDirectory, execOperations)
      ?: return CertificationGitIdentity.unavailable()
    val status = git(
      projectDirectory,
      execOperations,
      "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none",
    ) ?: return CertificationGitIdentity.unavailable()
    val secondRevision = revision(projectDirectory, execOperations)
      ?: return CertificationGitIdentity.unavailable()
    val secondStatus = git(
      projectDirectory,
      execOperations,
      "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none",
    ) ?: return CertificationGitIdentity.unavailable()
    check(firstRevision == secondRevision) {
      "Git HEAD/tree changed while hardeningCertify inspected the final worktree identity"
    }
    check(status.contentEquals(secondStatus)) {
      "Git index/worktree status changed while hardeningCertify inspected the final identity"
    }

    val relativeProject = relativeProjectDirectory(projectDirectory, firstRevision.root)
      ?: return CertificationGitIdentity.unavailable()
    val statusSha256 = PitestEvidence.sha256(secondStatus)
    return CertificationGitIdentity(
      state = if (secondStatus.isEmpty()) CertificationGitIdentity.State.CLEAN
        else CertificationGitIdentity.State.DIRTY,
      commit = firstRevision.commit,
      tree = firstRevision.tree,
      statusSha256 = statusSha256,
      projectDirectory = relativeProject,
    )
  }

  private data class Revision(val root: File, val commit: String, val tree: String)

  private fun revision(projectDirectory: File, execOperations: ExecOperations): Revision? {
    val output = git(
      projectDirectory,
      execOperations,
      "rev-parse", "--show-toplevel", "HEAD", "HEAD^{tree}",
    ) ?: return null
    val lines = output.toString(Charsets.UTF_8).trimEnd('\n', '\r').lines()
    if (lines.size != 3 || !lines[1].matches(Regex("[0-9a-f]{40}")) ||
      !lines[2].matches(Regex("[0-9a-f]{40}"))) return null
    val root = File(lines[0])
    if (!root.isAbsolute || !root.isDirectory) return null
    return Revision(root, lines[1], lines[2])
  }

  private fun relativeProjectDirectory(projectDirectory: File, root: File): String? {
    val rootPath = runCatching { root.canonicalFile.toPath() }.getOrNull() ?: return null
    val projectPath = runCatching { projectDirectory.canonicalFile.toPath() }.getOrNull() ?: return null
    if (!projectPath.startsWith(rootPath)) return null
    val relative = rootPath.relativize(projectPath).joinToString("/") { it.toString() }
    val normalized = relative.ifEmpty { "." }
    if (normalized != "." && normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
      return null
    }
    if ('\t' in normalized || '\n' in normalized || '\r' in normalized) return null
    return normalized
  }

  private fun git(
    projectDirectory: File,
    execOperations: ExecOperations,
    vararg arguments: String,
  ): ByteArray? {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val result = try {
      execOperations.exec {
        workingDir(projectDirectory)
        commandLine(listOf("git", "--no-optional-locks", "-C", projectDirectory.absolutePath) + arguments)
        standardOutput = stdout
        errorOutput = stderr
        isIgnoreExitValue = true
      }
    } catch (_: Exception) {
      return null
    }
    return stdout.toByteArray().takeIf { result.exitValue == 0 }
  }
}
