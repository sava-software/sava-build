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
   * A clean receipt claims that its sources are the captured tree's. Source inputs whose
   * bytes are not bound by that tree (ignored files under the source roots, or symlinks
   * that lead to them) make the claim false without dirtying Git status, so a clean
   * certification or fuzz campaign refuses them. Outputs under [buildDirectory] are
   * generated from tracked inputs and are exempt.
   *
   * Two things are checked per input: its logical name must be the tree's (an entry, an
   * entry beneath a pinned submodule's own tree, or a name reached through a tracked
   * symlink), and the file its bytes were read from, resolved by the operating system
   * exactly as the fingerprint read it, must be a tracked regular blob. Content equality
   * and index flags such as assume-unchanged are the owner's own doing and are outside
   * this check, as they are outside the evidence identity generally.
   * Callers invoke this only for [CertificationGitIdentity.State.CLEAN].
   */
  fun requireSourceInputsInTree(
    projectDirectory: File,
    identity: CertificationGitIdentity,
    sourceFiles: Iterable<File>,
    buildDirectory: File?,
    execOperations: ExecOperations,
    context: String,
  ) {
    check(identity.state == CertificationGitIdentity.State.CLEAN) {
      "source-input tree verification requires a clean captured Git identity"
    }
    val buildPath = buildDirectory?.absoluteFile?.toPath()?.normalize()
    val candidates = sourceFiles.asSequence()
      .flatMap { entry ->
        when {
          entry.isFile -> sequenceOf(entry)
          entry.isDirectory -> walkFiles(entry)
          else -> emptySequence()
        }
      }
      // Logical names, deliberately: the fingerprint records each input under the name
      // it was reached by, so an ignored symlink to a tracked file is still an input no
      // clean checkout has. A repository's own `.git` entries are never inputs.
      .map { it.absoluteFile.toPath().normalize() }
      .filter { path -> path.none { it.toString() == ".git" } }
      .filter { buildPath == null || !it.startsWith(buildPath) }
      .distinct()
      .toList()
    if (candidates.isEmpty()) return

    val rootOutput = checkNotNull(git(
      projectDirectory,
      execOperations,
      "rev-parse", "--show-toplevel",
    )) {
      "Git worktree root became unavailable while checking $context source inputs"
    }
    val root = File(rootOutput.toString(Charsets.UTF_8).trimEnd('\n', '\r'))
    check(root.isAbsolute && root.isDirectory) {
      "Git returned an invalid worktree root while checking $context source inputs: $root"
    }
    val currentProjectDirectory = relativeProjectDirectory(projectDirectory, root)
    check(currentProjectDirectory == identity.projectDirectory) {
      "Git project directory changed while checking $context source inputs: " +
        "captured=${identity.projectDirectory}, current=$currentProjectDirectory"
    }
    val rootPath = checkNotNull(runCatching { root.canonicalFile.toPath() }.getOrNull()) {
      "Git worktree root could not be resolved while checking $context source inputs: $root"
    }
    // The worktree root as the build names it, possibly through a workspace alias such
    // as /var -> /private/var. Resolving that alias here, once, is the only symlink
    // resolution the logical names get: a directory link inside the worktree stays in
    // the name, because the fingerprint recorded the input under it.
    val projectPath = projectDirectory.absoluteFile.toPath().normalize()
    val projectDepth = if (identity.projectDirectory == ".") 0 else identity.projectDirectory.count { it == '/' } + 1
    val logicalRoot = (0 until projectDepth).fold(projectPath) { directory, _ -> directory.parent ?: directory }
    check(runCatching { logicalRoot.toRealPath() }.getOrNull() == rootPath) {
      "project directory $projectPath does not sit ${identity.projectDirectory} below the Git worktree " +
        "root $rootPath by name while checking $context source inputs; reach it by its real path"
    }
    // One query for the whole immutable tree: a source set can hold thousands of files,
    // and the captured tree is what the receipt's commit claims to reproduce.
    val treeOutput = checkNotNull(git(
      root,
      execOperations,
      "ls-tree", "-r", "-z", "--full-tree", identity.tree,
    )) {
      "captured Git tree became unreadable while checking $context source inputs"
    }
    val rootTree = CapturedTree(root, parseTreeEntries(treeOutput))
    val capturedTrees = HashMap<String, CapturedTree?>()
    // A pinned submodule lists as one gitlink; its contents are bound by the tree of the
    // commit the gitlink names. The query must answer from the submodule's own
    // repository: a directory with no checkout of its own would let ordinary Git
    // discovery answer from the superproject instead.
    fun submoduleTree(worktree: File, pinned: String): CapturedTree? {
      if (worktree.path in capturedTrees) return capturedTrees[worktree.path]
      return capturedTrees.getOrPut(worktree.path) {
        val toplevel = git(worktree, execOperations, "rev-parse", "--show-toplevel")
          ?.toString(Charsets.UTF_8)?.trimEnd('\n', '\r')
          ?.let { runCatching { File(it).canonicalFile.toPath() }.getOrNull() }
        val own = toplevel != null && toplevel == runCatching { worktree.canonicalFile.toPath() }.getOrNull()
        if (!own) {
          null
        } else {
          git(worktree, execOperations, "ls-tree", "-r", "-z", "--full-tree", pinned)
            ?.let { CapturedTree(worktree, parseTreeEntries(it)) }
        }
      }
    }
    /** The tree entry at a root-relative path, descending through pinned submodules. */
    fun entryOf(tree: CapturedTree, path: String): TreeEntry? {
      tree.entries[path]?.let { return it }
      val (prefix, link) = tree.gitlinks.entries.firstOrNull { path.startsWith(it.key + "/") } ?: return null
      val sub = submoduleTree(tree.worktree.resolve(prefix), link.objectId) ?: return null
      return entryOf(sub, path.removePrefix("$prefix/"))
    }
    fun gitPathOf(path: java.nio.file.Path): String? =
      if (path.startsWith(rootPath)) rootPath.relativize(path).joinToString("/") { it.toString() } else null
    val findings = candidates.mapNotNull { path ->
      val gitPath = if (path.startsWith(logicalRoot)) {
        logicalRoot.relativize(path).joinToString("/") { it.toString() }
      } else {
        return@mapNotNull "$path (outside the Git worktree)"
      }
      // The bytes came from wherever the operating system took the name, links included.
      val real = runCatching { path.toRealPath() }.getOrNull()
        ?: return@mapNotNull "$gitPath (unresolvable link)"
      val realGitPath = gitPathOf(real) ?: return@mapNotNull "$gitPath -> $real (outside the Git worktree)"
      val bytesBound = entryOf(rootTree, realGitPath)?.let { it.type == "blob" && it.mode != SYMLINK_MODE } ?: false
      val nameBound = entryOf(rootTree, gitPath) != null ||
        generateSequence(gitPath.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "") }
          .takeWhile { it.isNotEmpty() }
          .any { entryOf(rootTree, it)?.mode == SYMLINK_MODE }
      when {
        bytesBound && nameBound -> null
        realGitPath == gitPath -> gitPath
        else -> "$gitPath -> $realGitPath"
      }
    }.sorted()
    check(findings.isEmpty()) {
      "clean Git $context cannot bind ${findings.size} source input(s) absent from its captured " +
        "tree (ignored files under the source roots, or links to them):\n" +
        findings.joinToString("\n") { "  $it" } +
        "\nCommit them, move them outside the source roots, or generate them under the build directory."
    }
  }

  /** Files beneath a directory input, following directory links once each so a cycle ends. */
  private fun walkFiles(directory: File): Sequence<File> {
    val entered = HashSet<java.nio.file.Path>()
    return directory.walkTopDown()
      .onEnter { entry -> runCatching { entered.add(entry.toPath().toRealPath()) }.getOrDefault(false) }
      .filter(File::isFile)
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

  /**
   * Validates that generated durable evidence stays machine-local. Unlike
   * [ignoredUntrackedRecordFiles], this checks paths which do not exist yet so an
   * expensive certification cannot finish before discovering that its receipt dirties
   * the checkout. A non-Git workspace has no clean-tree claim and therefore returns no
   * findings.
   */
  fun machineLocalStateFindings(
    projectDirectory: File,
    stateFiles: Iterable<File>,
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

    return buildList {
      stateFiles
        .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
        .forEach { candidate ->
          val path = candidate.toPath().toAbsolutePath().normalize()
          if (!path.startsWith(rootPath)) {
            add("machine-local certification state is outside the Git worktree: $path")
            return@forEach
          }
          val gitPath = rootPath.relativize(path).joinToString("/") { it.toString() }
          val tracked = git(
            root,
            execOperations,
            "--literal-pathspecs", "ls-files", "--error-unmatch", "--", gitPath,
          ) != null
          if (tracked) {
            add("machine-local certification state is tracked by Git: $gitPath")
            return@forEach
          }
          val ignored = git(
            root,
            execOperations,
            "check-ignore", "-q", "--no-index", "--", gitPath,
          ) != null
          if (!ignored) add("machine-local certification state is not Git-ignored: $gitPath")
        }
    }.sorted()
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

  private class CapturedTree(val worktree: File, val entries: Map<String, TreeEntry>) {
    val gitlinks: Map<String, TreeEntry> = entries.filterValues { it.type == "commit" }
  }

  private const val SYMLINK_MODE = "120000"

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
