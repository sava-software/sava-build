package software.sava.build.hardening

import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

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

  private val gitObjectPattern = Regex("[0-9a-f]{40}")

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

  /**
   * A clean porcelain status can conceal ignored files and index flags such as
   * assume-unchanged. Bind every optional mutation-record input directly to the
   * immutable tree captured in [identity]: presence must agree in both directions,
   * and present files must have the same Git-normalized blob identity.
   *
   * Dirty local certifications already state that the tree is not reproducible, and
   * Git-unavailable certifications make no tree claim, so callers invoke this only
   * for [CertificationGitIdentity.State.CLEAN].
   */
  fun requireRecordFilesMatchTree(
    projectDirectory: File,
    identity: CertificationGitIdentity,
    recordFiles: Iterable<File>,
    execOperations: ExecOperations,
  ) {
    check(identity.state == CertificationGitIdentity.State.CLEAN) {
      "mutation-record tree verification requires a clean captured Git identity"
    }
    val candidates = recordFiles
      .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
    if (candidates.isEmpty()) return
    candidates.forEach { candidate ->
      try {
        BaselineFiles.requireRegularFileOrMissing(projectDirectory, candidate)
      } catch (e: IllegalArgumentException) {
        throw IllegalStateException(e.message, e)
      }
    }

    val rootOutput = checkNotNull(git(
      projectDirectory,
      execOperations,
      "rev-parse", "--show-toplevel",
    )) {
      "Git worktree root became unavailable while checking mutation-record inputs"
    }
    val root = File(rootOutput.toString(Charsets.UTF_8).trimEnd('\n', '\r'))
    check(root.isAbsolute && root.isDirectory) {
      "Git returned an invalid worktree root while checking mutation-record inputs: $root"
    }
    val currentProjectDirectory = relativeProjectDirectory(projectDirectory, root)
    check(currentProjectDirectory == identity.projectDirectory) {
      "Git project directory changed while checking mutation-record inputs: " +
        "captured=${identity.projectDirectory}, current=$currentProjectDirectory"
    }
    // Git 2.41 added --attr-source. Prefer the captured tree's attributes, but retain
    // the existing best-effort Git contract on older installations: a clean capture's
    // working attributes are still safer than either raw-byte comparison (which rejects
    // legitimate CRLF/filter checkouts) or silently skipping the content check.
    val capturedAttributesSupported = git(
      root,
      execOperations,
      "--attr-source=${identity.tree}", "rev-parse", "--git-dir",
    ) != null

    val rootPath = root.toPath().toAbsolutePath().normalize()
    val candidatePaths = candidates.associateWith { candidate ->
      val path = candidate.toPath().toAbsolutePath().normalize()
      check(path.startsWith(rootPath)) {
        "mutation-record input is outside the Git worktree: $candidate"
      }
      rootPath.relativize(path).joinToString("/") { it.toString() }
    }
    val treeOutput = checkNotNull(git(
      root,
      execOperations,
      *(listOf(
        "--literal-pathspecs", "ls-tree", "-z", "--full-tree", identity.tree, "--",
      ) + candidatePaths.values).toTypedArray(),
    )) {
      "captured Git tree became unreadable while checking mutation-record inputs"
    }
    val treeEntries = parseTreeEntries(treeOutput)
    val unexpected = treeEntries.keys - candidatePaths.values.toSet()
    check(unexpected.isEmpty()) {
      "captured Git tree returned unexpected mutation-record path(s): ${unexpected.sorted()}"
    }

    val findings = buildList {
      candidatePaths.forEach { (candidate, gitPath) ->
        val localPresent = Files.isRegularFile(
          candidate.toPath().toAbsolutePath().normalize(),
          LinkOption.NOFOLLOW_LINKS,
        )
        val entry = treeEntries[gitPath]
        when {
          localPresent && entry == null ->
            add("present locally but absent from captured tree: $gitPath")
          !localPresent && entry != null ->
            add("missing locally but present in captured tree: $gitPath")
          entry != null && !entry.isRegularBlob ->
            add("captured entry is not a regular file (${entry.mode} ${entry.type}): $gitPath")
          localPresent && entry != null -> {
            val hashArguments = buildList {
              if (capturedAttributesSupported) add("--attr-source=${identity.tree}")
              addAll(listOf("hash-object", "--path=$gitPath", "--", candidate.absolutePath))
            }
            val localObject = git(
              root,
              execOperations,
              *hashArguments.toTypedArray(),
            )?.toString(Charsets.UTF_8)?.trimEnd('\n', '\r')
            check(localObject != null && localObject.matches(gitObjectPattern)) {
              "Git could not hash mutation-record input against captured attributes: $gitPath"
            }
            if (localObject != entry.objectId) {
              add("Git-normalized content differs from captured tree: $gitPath")
            }
          }
        }
      }
    }.sorted()
    check(findings.isEmpty()) {
      "clean Git certification cannot bind mutation-record inputs to its captured tree:\n" +
        findings.joinToString("\n") { "  $it" }
    }
  }

  /**
   * Returns the Git-relative path of each present, untracked record file ignored
   * by Git.
   *
   * This is an early writer diagnostic, not the integrity boundary: clean
   * certification still compares every record directly with the captured tree.
   * Git-unavailable workspaces return no findings, and a tracked file is never
   * warned merely because an ignore pattern would also match its path.
   */
  fun ignoredUntrackedRecordFiles(
    projectDirectory: File,
    recordFiles: Iterable<File>,
    execOperations: ExecOperations,
  ): List<String> {
    val rootOutput = git(
      projectDirectory,
      execOperations,
      "rev-parse", "--show-toplevel",
    ) ?: return emptyList()
    val root = File(rootOutput.toString(Charsets.UTF_8).trimEnd('\n', '\r'))
    if (!root.isAbsolute || !root.isDirectory) return emptyList()
    val rootPath = root.toPath().toAbsolutePath().normalize()

    return recordFiles
        .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
        .mapNotNull { candidate ->
          val path = candidate.toPath().toAbsolutePath().normalize()
          if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
              !path.startsWith(rootPath)) return@mapNotNull null
          val gitPath = rootPath.relativize(path).joinToString("/") { it.toString() }
          val tracked = git(
            root,
            execOperations,
            "--literal-pathspecs", "ls-files", "--error-unmatch", "--", gitPath,
          ) != null
          if (tracked) return@mapNotNull null
          // Verbose mode also succeeds for a negated rule and prints that rule,
          // even though the path is not ignored. Quiet mode has the boolean
          // semantics we need. The warning tells operators how to inspect the
          // deciding rule without leaking a machine-local global-excludes path.
          val ignored = git(
            root,
            execOperations,
            "check-ignore", "-q", "--no-index", "--", gitPath,
          ) != null
          if (!ignored) return@mapNotNull null
          gitPath
        }
        .sorted()
  }

  private data class TreeEntry(val mode: String, val type: String, val objectId: String) {
    val isRegularBlob: Boolean get() = type == "blob" && (mode == "100644" || mode == "100755")
  }

  private fun parseTreeEntries(output: ByteArray): Map<String, TreeEntry> {
    if (output.isEmpty()) return emptyMap()
    val entries = linkedMapOf<String, TreeEntry>()
    output.toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty).forEach { record ->
      val tab = record.indexOf('\t')
      check(tab > 0) { "captured Git tree returned a malformed entry" }
      val header = record.substring(0, tab).split(' ')
      check(header.size == 3 && header[2].matches(gitObjectPattern)) {
        "captured Git tree returned a malformed entry header"
      }
      val path = record.substring(tab + 1)
      check(entries.put(path, TreeEntry(header[0], header[1], header[2])) == null) {
        "captured Git tree returned duplicate path '$path'"
      }
    }
    return entries
  }

  private data class Revision(val root: File, val commit: String, val tree: String)

  private fun revision(projectDirectory: File, execOperations: ExecOperations): Revision? {
    val output = git(
      projectDirectory,
      execOperations,
      "rev-parse", "--show-toplevel", "HEAD", "HEAD^{tree}",
    ) ?: return null
    val lines = output.toString(Charsets.UTF_8).trimEnd('\n', '\r').lines()
    if (lines.size != 3 || !lines[1].matches(gitObjectPattern) ||
      !lines[2].matches(gitObjectPattern)) return null
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
