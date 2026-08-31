package software.sava.build.hardening.task

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider
import software.sava.build.hardening.HardeningExecutionLock
import software.sava.build.hardening.HardeningFuzzSession
import software.sava.build.hardening.MAX_FUZZ_RECEIPT_EXECUTIONS
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Coverage-guided Jazzer execution with an explicit writable local corpus. */
@UntrackedTask(because = "Fuzzing must execute for the requested wall-clock budget whenever selected")
abstract class FuzzRunTask : JavaExec() {

  @get:Input
  abstract val targetName: Property<String>

  @get:Input
  abstract val targetClass: Property<String>

  @get:Input
  abstract val maxFuzzTimeSeconds: Property<Int>

  @get:Input
  abstract val campaignProjectPath: Property<String>

  @get:Input
  @get:Optional
  abstract val maxLen: Property<Int>

  @get:LocalState
  abstract val localCorpus: DirectoryProperty

  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val seedCorpus: DirectoryProperty

  @get:ServiceReference("hardeningFuzzSession")
  abstract val fuzzSession: Property<HardeningFuzzSession>

  @get:ServiceReference("hardeningFuzzExecutionSlots")
  abstract val executionSlots: Property<HardeningExecutionLock>

  private val commandLineProvider = FuzzRunCommandLineProvider(
    targetClass, maxFuzzTimeSeconds, maxLen, localCorpus, seedCorpus
  )

  init {
    // Keep the execution guarantee next to the JavaExec type so future registration
    // paths cannot accidentally restore Gradle output reuse.
    outputs.upToDateWhen { false }
    mainClass.convention("com.code_intelligence.jazzer.Jazzer")
    jvmArguments.convention(JAZZER_JVM_ARGUMENTS)
    argumentProviders.add(commandLineProvider)
  }

  @TaskAction
  override fun exec() {
    val session = fuzzSession.get()
    val projectPath = campaignProjectPath.get()
    val aggregateCampaign = try {
      session.requireRunnable(projectPath)
    } catch (failure: IllegalStateException) {
      throw GradleException(failure.message.orEmpty(), failure)
    }
    val budget = maxFuzzTimeSeconds.get()
    if (budget <= 0) {
      throw GradleException("fuzz '${targetName.get()}': maxFuzzTime must be positive, was $budget")
    }
    validateMaxLen(targetName.get(), maxLen.orNull)
    FuzzCorpusPaths.prepareRun(
      local = localCorpus.get().asFile,
      seed = seedCorpus.orNull?.asFile,
    )
    // Capture the native driver's terminal count directly from the live child
    // streams while forwarding every byte unchanged. A mutable Gradle log is not
    // evidence: it can be truncated, replaced, or contain output from other tasks.
    val originalStandardOutput: OutputStream = getStandardOutput() ?: System.out
    val originalErrorOutput: OutputStream = getErrorOutput() ?: System.err
    val executionCount = FuzzExecutionCountCapture(originalStandardOutput, originalErrorOutput)
    standardOutput = executionCount.standardOutput
    errorOutput = executionCount.errorOutput
    try {
      super.exec()
    } finally {
      standardOutput = originalStandardOutput
      errorOutput = originalErrorOutput
      executionCount.finish()
    }
    // A consumer may configure this public JavaExec with ignoreExitValue=true.
    // Campaign completion is stricter than that compatibility knob: a non-zero
    // Jazzer exit is never a completed fuzz observation and must not earn a receipt.
    executionResult.get().assertNormalExitValue()
    val target = targetName.get()
    if (aggregateCampaign) {
      session.recordCompleted(projectPath, target, executionCount.requireUniquePositive(target))
    }
  }
}

/**
 * Staged libFuzzer merge followed by a guarded commit into the checked-in corpus.
 * The task remains [JavaExec], so existing consumer customization keeps working.
 */
@UntrackedTask(because = "Corpus minimization is an explicitly requested source-tree rewrite")
abstract class FuzzMinimizeTask : JavaExec() {

  @get:Input
  abstract val targetName: Property<String>

  @get:Input
  abstract val targetClass: Property<String>

  @get:Input
  @get:Optional
  abstract val maxLen: Property<Int>

  /** Both input and deliberate output; untracked because this task rewrites it. */
  @get:Internal
  abstract val seedCorpus: DirectoryProperty

  @get:LocalState
  abstract val stagingCorpus: DirectoryProperty

  @get:LocalState
  abstract val localCorpus: DirectoryProperty

  @get:Input
  abstract val adoptLocalCorpus: Property<Boolean>

  @get:ServiceReference("hardeningExecutionLock")
  abstract val executionLock: Property<HardeningExecutionLock>

  private val commandLineProvider = FuzzMinimizeCommandLineProvider(
    targetClass, maxLen, stagingCorpus, seedCorpus, localCorpus, adoptLocalCorpus
  )

  init {
    // Corpus minimization is an explicit execution request even when its declared
    // inputs are byte-identical to the previous invocation.
    outputs.upToDateWhen { false }
    mainClass.convention("com.code_intelligence.jazzer.Jazzer")
    jvmArguments.convention(JAZZER_JVM_ARGUMENTS)
    adoptLocalCorpus.convention(false)
    argumentProviders.add(commandLineProvider)
  }

  @TaskAction
  override fun exec() {
    val target = targetName.get()
    validateMaxLen(target, maxLen.orNull)
    val corpus = seedCorpus.orNull?.asFile ?: throw GradleException(
      "fuzz target '$target' declares no seedCorpus — nothing to minimize into. " +
        "Commit a seed corpus first (see HARDENING.md 'Fuzzing')."
    )
    val paths = FuzzCorpusPaths.validateForMinimize(
      seed = corpus,
      staging = stagingCorpus.get().asFile,
      local = localCorpus.get().asFile,
    )
    if (!FuzzCorpusPaths.hasFlatRegularFile(paths.seed)) {
      throw GradleException(
        "fuzz target '$target': seed corpus at $corpus is missing or empty — " +
          "a merge cannot start from nothing."
      )
    }

    FuzzCorpusPaths.recreateEmptyStaging(paths)
    super.exec()
    // Never let JavaExec's mutable ignoreExitValue setting turn a failed merge into
    // permission to commit its partial staging directory into the source tree.
    executionResult.get().assertNormalExitValue()

    val stats = try {
      FuzzCorpusCommit.commit(paths.staging.toFile(), paths.seed.toFile())
    } catch (failure: Exception) {
      throw GradleException(
        "fuzz '$target': ${failure.message} Staging output: ${paths.staging}; " +
          "the corpus commit did not complete cleanly. The cause states whether it was " +
          "refused, rolled back, or retained an explicitly named recovery backup.",
        failure,
      )
    }
    logger.lifecycle(
      "fuzz '$target': corpus minimized ${stats.beforeFiles} -> ${stats.afterFiles} file(s) " +
        "(${stats.beforeBytes} -> ${stats.afterBytes} bytes) at $corpus — " +
        "${stats.adoptedFiles} newly adopted, ${stats.removedFiles} redundant removed, " +
        "surviving seeds keep their names. Review the diff before committing; update the " +
        "provenance README next to the corpus."
    )
  }
}

/**
 * Live parser for libFuzzer's canonical successful terminal line:
 * `Done N runs in S second(s)`. Each child stream is forwarded byte-for-byte and
 * buffered only until its next newline; the retained evidence is the parsed count in
 * the invocation-local build service, never a mutable output file.
 */
internal class FuzzExecutionCountCapture(
  standardDelegate: OutputStream,
  errorDelegate: OutputStream,
) {
  private val observations = FuzzExecutionCountObservations()
  private val standardCapture = LineCaptureOutputStream(standardDelegate, observations::observe)
  private val errorCapture = LineCaptureOutputStream(errorDelegate, observations::observe)

  val standardOutput: OutputStream = standardCapture
  val errorOutput: OutputStream = errorCapture

  fun finish() {
    standardCapture.finish()
    errorCapture.finish()
  }

  fun requireUniquePositive(target: String): Long = observations.requireUniquePositive(target)
}

private class FuzzExecutionCountObservations {
  private val terminalCounts = mutableListOf<Long?>()

  @Synchronized
  fun observe(line: String) {
    val match = TERMINAL_COUNT.matchEntire(line.removeSuffix("\r")) ?: return
    terminalCounts += match.groupValues[1].toLongOrNull()
  }

  @Synchronized
  fun requireUniquePositive(target: String): Long {
    if (terminalCounts.isEmpty()) {
      throw GradleException(
        "fuzz '$target': successful Jazzer execution emitted no terminal " +
          "'Done N runs in S second(s)' count — refusing campaign receipt",
      )
    }
    if (terminalCounts.size != 1) {
      throw GradleException(
        "fuzz '$target': successful Jazzer execution emitted ${terminalCounts.size} terminal " +
          "run counts — refusing ambiguous campaign receipt",
      )
    }
    val count = terminalCounts.single() ?: throw GradleException(
      "fuzz '$target': terminal Jazzer execution count exceeds a signed 64-bit integer",
    )
    if (count <= 0) {
      throw GradleException(
        "fuzz '$target': terminal Jazzer execution count must be positive, was $count",
      )
    }
    if (count > MAX_FUZZ_RECEIPT_EXECUTIONS) {
      throw GradleException(
        "fuzz '$target': terminal Jazzer execution count $count exceeds JSON's " +
          "exact-integer boundary $MAX_FUZZ_RECEIPT_EXECUTIONS",
      )
    }
    return count
  }

  private companion object {
    val TERMINAL_COUNT = Regex("Done ([0-9]+) runs in [0-9]+ second\\(s\\)")
  }
}

private class LineCaptureOutputStream(
  private val delegate: OutputStream,
  private val observe: (String) -> Unit,
) : OutputStream() {
  private val line = ByteArrayOutputStream()
  private var overflow = false

  @Synchronized
  override fun write(value: Int) {
    delegate.write(value)
    accept(value)
  }

  @Synchronized
  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    delegate.write(bytes, offset, length)
    for (index in offset until offset + length) accept(bytes[index].toInt() and 0xff)
  }

  @Synchronized
  override fun flush() {
    delegate.flush()
  }

  @Synchronized
  fun finish() {
    if (line.size() > 0 && !overflow) observe(line.toString(Charsets.UTF_8))
    line.reset()
    overflow = false
  }

  private fun accept(value: Int) {
    if (value == '\n'.code) {
      if (!overflow) observe(line.toString(Charsets.UTF_8))
      line.reset()
      overflow = false
    } else if (!overflow) {
      if (line.size() < MAX_CAPTURED_LINE_BYTES) line.write(value) else overflow = true
    }
  }

  private companion object {
    const val MAX_CAPTURED_LINE_BYTES = 4096
  }
}

internal data class FuzzCorpusCommitStats(
  val beforeFiles: Int,
  val afterFiles: Int,
  val beforeBytes: Long,
  val afterBytes: Long,
  val adoptedFiles: Int,
  val removedFiles: Int,
)

internal data class ValidatedFuzzCorpusPaths(
  val seed: Path,
  val staging: Path,
  val local: Path,
)

/**
 * Path validation and checked deletion for the writable parts of a fuzz merge.
 *
 * Validation happens before the old staging directory is removed. In particular, a
 * spelling alias, parent/child overlap, or symbolic link cannot turn staging cleanup
 * into a deletion under the seed or local corpus.
 */
internal object FuzzCorpusPaths {
  fun prepareRun(local: File, seed: File?) {
    rejectSelectedPathSymlinks(listOfNotNull(local.toPath(), seed?.toPath()))
    val resolvedLocal = resolveWithoutSymbolicLinks(local.toPath())
    val resolvedSeed = seed?.let { resolveWithoutSymbolicLinks(it.toPath()) }
    if (resolvedSeed != null) {
      require(
        !resolvedLocal.startsWith(resolvedSeed) && !resolvedSeed.startsWith(resolvedLocal)
      ) {
        "writable local and committed seed corpus directories must not overlap: " +
          "$resolvedLocal, $resolvedSeed"
      }
      if (Files.exists(resolvedLocal, NO_FOLLOW) && Files.exists(resolvedSeed, NO_FOLLOW)) {
        require(!Files.isSameFile(resolvedLocal, resolvedSeed)) {
          "writable local and committed seed corpus directories resolve to the same location: " +
            resolvedLocal
        }
      }
      rejectSymbolicLinkEntries("seed", resolvedSeed)
    }
    rejectSymbolicLinkEntries("local", resolvedLocal)
    Files.createDirectories(resolvedLocal)
    check(Files.isDirectory(resolvedLocal, NO_FOLLOW) && !Files.isSymbolicLink(resolvedLocal)) {
      "failed to create an ordinary writable local corpus at $resolvedLocal"
    }
  }

  fun validateForMinimize(seed: File, staging: File, local: File): ValidatedFuzzCorpusPaths {
    rejectSelectedPathSymlinks(listOf(seed.toPath(), staging.toPath(), local.toPath()))
    val named = listOf(
      "seed" to resolveWithoutSymbolicLinks(seed.toPath()),
      "staging" to resolveWithoutSymbolicLinks(staging.toPath()),
      "local" to resolveWithoutSymbolicLinks(local.toPath()),
    )
    for (leftIndex in named.indices) {
      for (rightIndex in leftIndex + 1 until named.size) {
        val (leftLabel, left) = named[leftIndex]
        val (rightLabel, right) = named[rightIndex]
        require(!left.startsWith(right) && !right.startsWith(left)) {
          "fuzz corpus directories must not overlap: $leftLabel=$left, $rightLabel=$right"
        }
        if (Files.exists(left, NO_FOLLOW) && Files.exists(right, NO_FOLLOW)) {
          require(!Files.isSameFile(left, right)) {
            "fuzz corpus directories resolve to the same location: " +
              "$leftLabel=$left, $rightLabel=$right"
          }
        }
      }
    }
    named.forEach { (label, path) -> rejectSymbolicLinkEntries(label, path) }
    return ValidatedFuzzCorpusPaths(named[0].second, named[1].second, named[2].second)
  }

  fun recreateEmptyStaging(paths: ValidatedFuzzCorpusPaths) {
    val current = validateForMinimize(
      paths.seed.toFile(), paths.staging.toFile(), paths.local.toFile()
    )
    require(current == paths) {
      "fuzz corpus paths changed after validation: expected $paths, found $current"
    }
    checkedDeleteTree(paths.staging)
    Files.createDirectories(paths.staging)
    check(Files.isDirectory(paths.staging, NO_FOLLOW) && !Files.isSymbolicLink(paths.staging)) {
      "failed to create an ordinary staging directory at ${paths.staging}"
    }
    check(Files.newDirectoryStream(paths.staging).use { !it.iterator().hasNext() }) {
      "new staging directory is not empty: ${paths.staging}"
    }
  }

  fun hasFlatRegularFile(directory: Path): Boolean {
    if (!Files.isDirectory(directory, NO_FOLLOW) || Files.isSymbolicLink(directory)) return false
    return Files.newDirectoryStream(directory).use { entries ->
      var found = false
      entries.forEach { entry ->
        require(!Files.isSymbolicLink(entry)) {
          "symbolic-link corpus entry is not allowed: $entry"
        }
        require(Files.isRegularFile(entry, NO_FOLLOW)) {
          "corpus entries must be ordinary files: $entry"
        }
        found = true
      }
      found
    }
  }

  internal fun resolveWithoutSymbolicLinks(input: Path): Path {
    val absolute = input.toAbsolutePath().normalize()
    var existing = absolute
    while (!Files.exists(existing, NO_FOLLOW)) {
      existing = existing.parent ?: throw IllegalArgumentException("path has no existing root: $input")
    }
    require(!Files.isSymbolicLink(existing)) {
      "symbolic-link path component is not allowed: $existing"
    }
    if (existing != absolute) require(Files.isDirectory(existing, NO_FOLLOW)) {
      "non-directory path component is not allowed: $existing"
    }
    val realExisting = existing.toRealPath()
    return realExisting.resolve(existing.relativize(absolute)).normalize()
  }

  /**
   * Reject links in the caller-selected portion of a path while allowing a platform
   * alias above the paths' common workspace ancestor (macOS `/var` -> `/private/var`,
   * for example). Canonicalization below still makes spelling aliases compare equal.
   */
  internal fun rejectSelectedPathSymlinks(inputs: List<Path>) {
    require(inputs.isNotEmpty()) { "no fuzz corpus paths were supplied" }
    val absolute = inputs.map { it.toAbsolutePath().normalize() }
    var common = absolute.first()
    while (absolute.any { !it.startsWith(common) }) {
      common = common.parent ?: throw IllegalArgumentException(
        "fuzz corpus paths have no common filesystem ancestor: ${absolute.joinToString()}")
    }
    absolute.forEach { selected ->
      var current = common
      if (Files.exists(current, NO_FOLLOW)) {
        require(!Files.isSymbolicLink(current)) {
          "symbolic-link path component is not allowed: $current"
        }
      }
      for (component in common.relativize(selected)) {
        current = current.resolve(component)
        if (!Files.exists(current, NO_FOLLOW)) break
        require(!Files.isSymbolicLink(current)) {
          "symbolic-link path component is not allowed: $current"
        }
        if (current != selected) require(Files.isDirectory(current, NO_FOLLOW)) {
          "non-directory path component is not allowed: $current"
        }
      }
    }
  }

  internal fun rejectSymbolicLinkEntries(label: String, root: Path) {
    if (!Files.exists(root, NO_FOLLOW)) return
    require(Files.isDirectory(root, NO_FOLLOW) && !Files.isSymbolicLink(root)) {
      "$label corpus path is not an ordinary directory: $root"
    }
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) {
          "symbolic-link entry is not allowed in the $label corpus: $dir"
        }
        return FileVisitResult.CONTINUE
      }

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) {
          "symbolic-link entry is not allowed in the $label corpus: $file"
        }
        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
    })
  }

  internal fun checkedDeleteTree(root: Path) {
    if (!Files.exists(root, NO_FOLLOW)) return
    resolveWithoutSymbolicLinks(root)
    rejectSymbolicLinkEntries("deletion", root)
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) {
          "refusing to delete symbolic-link entry: $file"
        }
        Files.delete(file)
        check(!Files.exists(file, NO_FOLLOW)) { "failed to delete $file" }
        return FileVisitResult.CONTINUE
      }

      override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc

      override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
        if (exc != null) throw exc
        require(!Files.isSymbolicLink(dir)) { "refusing to delete symbolic-link directory: $dir" }
        Files.delete(dir)
        check(!Files.exists(dir, NO_FOLLOW)) { "failed to delete $dir" }
        return FileVisitResult.CONTINUE
      }
    })
    check(!Files.exists(root, NO_FOLLOW)) { "failed to delete directory tree $root" }
  }
}

/** Content-addressed commit step kept independent from Gradle for deterministic tests. */
internal object FuzzCorpusCommit {
  fun commit(staging: File, corpus: File): FuzzCorpusCommitStats {
    val roots = validateCommitRoots(staging.toPath(), corpus.toPath())
    val staged = snapshotDirectory(roots.first, "staging")
    require(staged.isNotEmpty()) {
      "the merge produced an empty corpus — refusing to touch ${roots.second}."
    }
    val before = snapshotDirectory(roots.second, "seed")
    require(before.isNotEmpty()) { "the committed seed corpus is empty: ${roots.second}" }
    val plan = replacementPlan(staged, before)
    return executePlan(roots.first, roots.second, staged, before, plan)
  }

  private data class Fingerprint(val size: Long, val sha256: String)

  private data class CorpusFile(
    val name: String,
    val path: Path,
    val fingerprint: Fingerprint,
  )

  private data class PlannedFile(
    val name: String,
    val source: CorpusFile,
    val preservesOriginal: Boolean,
  )

  private fun validateCommitRoots(staging: Path, corpus: Path): Pair<Path, Path> {
    FuzzCorpusPaths.rejectSelectedPathSymlinks(listOf(staging, corpus))
    val resolvedStaging = FuzzCorpusPaths.resolveWithoutSymbolicLinks(staging)
    val resolvedCorpus = FuzzCorpusPaths.resolveWithoutSymbolicLinks(corpus)
    FuzzCorpusPaths.rejectSymbolicLinkEntries("staging", resolvedStaging)
    FuzzCorpusPaths.rejectSymbolicLinkEntries("seed", resolvedCorpus)
    require(
      !resolvedStaging.startsWith(resolvedCorpus) && !resolvedCorpus.startsWith(resolvedStaging)
    ) {
      "staging and seed corpus directories must not overlap: " +
        "$resolvedStaging, $resolvedCorpus"
    }
    if (Files.exists(resolvedStaging, NO_FOLLOW) && Files.exists(resolvedCorpus, NO_FOLLOW)) {
      require(!Files.isSameFile(resolvedStaging, resolvedCorpus)) {
        "staging and seed corpus directories resolve to the same location: $resolvedStaging"
      }
    }
    return resolvedStaging to resolvedCorpus
  }

  private fun snapshotDirectory(directory: Path, label: String): List<CorpusFile> {
    require(Files.isDirectory(directory, NO_FOLLOW) && !Files.isSymbolicLink(directory)) {
      "$label corpus is not an ordinary directory: $directory"
    }
    return Files.newDirectoryStream(directory).use { stream ->
      stream.map { path ->
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NO_FOLLOW)) {
          "$label corpus entries must be ordinary files: $path"
        }
        CorpusFile(path.fileName.toString(), path, fingerprint(path))
      }.sortedBy(CorpusFile::name).toList()
    }
  }

  private fun replacementPlan(staged: List<CorpusFile>, before: List<CorpusFile>): List<PlannedFile> {
    val groups = mutableListOf<MutableList<CorpusFile>>()
    staged.forEach { candidate ->
      val group = groups.firstOrNull { sameContent(it.first(), candidate) }
      if (group == null) groups += mutableListOf(candidate) else group += candidate
    }
    val beforeByName = before.associateBy(CorpusFile::name)
    val result = groups.map { stagedCopies ->
      val representative = stagedCopies.first()
      val originals = before.filter { sameContent(it, representative) }
      if (originals.isNotEmpty()) {
        val original = originals.minBy(CorpusFile::name)
        PlannedFile(original.name, original, preservesOriginal = true)
      } else {
        stagedCopies.forEach { novel ->
          val collision = beforeByName[novel.name]
          require(collision == null) {
            "staged novel seed '${novel.name}' collides with an existing different-content seed; " +
              "refusing to replace ${collision!!.path}"
          }
        }
        val novel = stagedCopies.minBy(CorpusFile::name)
        PlannedFile(novel.name, novel, preservesOriginal = false)
      }
    }.sortedBy(PlannedFile::name)
    require(result.isNotEmpty()) { "the validated replacement plan is empty" }
    require(result.map(PlannedFile::name).distinct().size == result.size) {
      "the validated replacement plan contains duplicate destination names"
    }
    return result
  }

  private fun executePlan(
    staging: Path,
    corpus: Path,
    staged: List<CorpusFile>,
    before: List<CorpusFile>,
    plan: List<PlannedFile>,
  ): FuzzCorpusCommitStats {
    val parent = corpus.parent ?: throw IllegalArgumentException("seed corpus has no parent: $corpus")
    FuzzCorpusPaths.resolveWithoutSymbolicLinks(parent)
    verifyAtomicDirectoryMoves(parent, corpus.fileName.toString())
    val replacement = Files.createTempDirectory(parent, ".${corpus.fileName}.replacement-")
    var replacementExists = true
    try {
      plan.forEach { planned ->
        verifyUnchanged(planned.source)
        val destination = replacement.resolve(planned.name)
        Files.copy(planned.source.path, destination)
        check(Files.isRegularFile(destination, NO_FOLLOW) && !Files.isSymbolicLink(destination)) {
          "copy did not create an ordinary replacement seed: $destination"
        }
        val copied = CorpusFile(planned.name, destination, fingerprint(destination))
        check(copied.fingerprint == planned.source.fingerprint && sameContent(copied, planned.source)) {
          "replacement copy verification failed for ${planned.source.path} -> $destination"
        }
      }
      val desired = plan.associate { it.name to it.source.fingerprint }
      checkManifest(replacement, desired, "complete replacement")
      check(snapshotManifest(snapshotDirectory(staging, "staging")) == snapshotManifest(staged)) {
        "staging corpus changed while the replacement was being built"
      }
      check(snapshotManifest(snapshotDirectory(corpus, "seed")) == snapshotManifest(before)) {
        "seed corpus changed while the replacement was being built"
      }

      swapWithRollback(corpus, replacement, snapshotManifest(before), desired)
      replacementExists = false
      return FuzzCorpusCommitStats(
        beforeFiles = before.size,
        afterFiles = plan.size,
        beforeBytes = before.sumOf { it.fingerprint.size },
        afterBytes = plan.sumOf { it.source.fingerprint.size },
        adoptedFiles = plan.count { !it.preservesOriginal },
        removedFiles = before.size - plan.count(PlannedFile::preservesOriginal),
      )
    } catch (failure: Exception) {
      if (replacementExists && Files.exists(replacement, NO_FOLLOW)) {
        try {
          FuzzCorpusPaths.checkedDeleteTree(replacement)
        } catch (cleanupFailure: Exception) {
          failure.addSuppressed(cleanupFailure)
        }
      }
      throw failure
    }
  }

  private fun swapWithRollback(
    corpus: Path,
    replacement: Path,
    original: Map<String, Fingerprint>,
    desired: Map<String, Fingerprint>,
  ) {
    val parent = corpus.parent
    val backup = unusedSibling(parent, ".${corpus.fileName}.backup-")
    var oldMoved = false
    var newMoved = false
    try {
      atomicMove(corpus, backup)
      oldMoved = true
      checkManifest(backup, original, "seed backup")
      atomicMove(replacement, corpus)
      newMoved = true
      checkManifest(corpus, desired, "committed seed corpus")
    } catch (failure: Exception) {
      val inferredOldMoved = oldMoved || (Files.exists(backup, NO_FOLLOW) && !Files.exists(corpus, NO_FOLLOW))
      if (inferredOldMoved) {
        try {
          rollback(corpus, backup, original, newMoved || Files.exists(corpus, NO_FOLLOW))
        } catch (rollbackFailure: Exception) {
          failure.addSuppressed(rollbackFailure)
          throw IllegalStateException(
            "corpus swap failed and rollback could not be completed; original backup is at $backup",
            failure,
          )
        }
      }
      throw failure
    }

    try {
      FuzzCorpusPaths.checkedDeleteTree(backup)
    } catch (failure: Exception) {
      throw IllegalStateException(
        "corpus replacement succeeded, but checked cleanup of backup $backup failed",
        failure,
      )
    }
    checkManifest(corpus, desired, "committed seed corpus after backup cleanup")
  }

  private fun rollback(
    corpus: Path,
    backup: Path,
    original: Map<String, Fingerprint>,
    newAtCorpus: Boolean,
  ) {
    var quarantine: Path? = null
    if (newAtCorpus && Files.exists(corpus, NO_FOLLOW)) {
      quarantine = unusedSibling(corpus.parent, ".${corpus.fileName}.failed-replacement-")
      atomicMove(corpus, quarantine)
    }
    check(!Files.exists(corpus, NO_FOLLOW)) {
      "cannot roll back while an unverified corpus still exists at $corpus"
    }
    check(Files.isDirectory(backup, NO_FOLLOW) && !Files.isSymbolicLink(backup)) {
      "original corpus backup is missing or unsafe: $backup"
    }
    atomicMove(backup, corpus)
    checkManifest(corpus, original, "rolled-back seed corpus")
    if (quarantine != null) FuzzCorpusPaths.checkedDeleteTree(quarantine)
  }

  private fun verifyAtomicDirectoryMoves(parent: Path, corpusName: String) {
    val probe = Files.createTempDirectory(parent, ".$corpusName.atomic-probe-")
    val moved = unusedSibling(parent, ".$corpusName.atomic-probe-moved-")
    var current = probe
    try {
      atomicMove(probe, moved)
      current = moved
      atomicMove(moved, probe)
      current = probe
    } finally {
      if (Files.exists(current, NO_FOLLOW)) FuzzCorpusPaths.checkedDeleteTree(current)
      if (Files.exists(probe, NO_FOLLOW)) FuzzCorpusPaths.checkedDeleteTree(probe)
      if (Files.exists(moved, NO_FOLLOW)) FuzzCorpusPaths.checkedDeleteTree(moved)
    }
  }

  private fun atomicMove(source: Path, destination: Path) {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    } catch (failure: AtomicMoveNotSupportedException) {
      throw IllegalStateException(
        "filesystem does not support the atomic directory move required for a safe corpus swap: " +
          "$source -> $destination",
        failure,
      )
    }
    check(!Files.exists(source, NO_FOLLOW) && Files.exists(destination, NO_FOLLOW)) {
      "atomic move postcondition failed: $source -> $destination"
    }
  }

  private fun unusedSibling(parent: Path, prefix: String): Path {
    repeat(100) { attempt ->
      val candidate = parent.resolve("$prefix${System.nanoTime()}-$attempt")
      if (!Files.exists(candidate, NO_FOLLOW)) return candidate
    }
    throw IllegalStateException("could not reserve a sibling path under $parent with prefix $prefix")
  }

  private fun checkManifest(directory: Path, expected: Map<String, Fingerprint>, label: String) {
    val actual = snapshotManifest(snapshotDirectory(directory, label))
    check(actual == expected) {
      "$label verification failed at $directory: expected $expected, found $actual"
    }
  }

  private fun snapshotManifest(files: List<CorpusFile>): Map<String, Fingerprint> =
    files.associate { it.name to it.fingerprint }

  private fun verifyUnchanged(file: CorpusFile) {
    check(
      Files.isRegularFile(file.path, NO_FOLLOW) && !Files.isSymbolicLink(file.path) &&
        fingerprint(file.path) == file.fingerprint
    ) { "corpus source changed after validation: ${file.path}" }
  }

  private fun sameContent(left: CorpusFile, right: CorpusFile): Boolean =
    left.fingerprint == right.fingerprint && Files.mismatch(left.path, right.path) == -1L

  private fun fingerprint(path: Path): Fingerprint {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    return Fingerprint(
      Files.size(path),
      digest.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
  }
}

private val NO_FOLLOW = LinkOption.NOFOLLOW_LINKS

private class FuzzRunCommandLineProvider(
  private val targetClass: Property<String>,
  private val maxFuzzTimeSeconds: Property<Int>,
  private val maxLen: Property<Int>,
  private val localCorpus: DirectoryProperty,
  private val seedCorpus: DirectoryProperty,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> = HardeningCommandLines.fuzzRun(
    HardeningCommandLines.FuzzRun(
      targetClass = targetClass.get(),
      maxTotalTimeSeconds = maxFuzzTimeSeconds.get(),
      maxLen = maxLen.orNull,
      localCorpus = localCorpus.get().asFile,
      seedCorpus = seedCorpus.orNull?.asFile,
    )
  )
}

private class FuzzMinimizeCommandLineProvider(
  private val targetClass: Property<String>,
  private val maxLen: Property<Int>,
  private val stagingCorpus: DirectoryProperty,
  private val seedCorpus: DirectoryProperty,
  private val localCorpus: DirectoryProperty,
  private val adoptLocalCorpus: Property<Boolean>,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> {
    val local = localCorpus.get().asFile
    return HardeningCommandLines.fuzzMinimize(
      HardeningCommandLines.FuzzMinimize(
        targetClass = targetClass.get(),
        maxLen = maxLen.orNull,
        stagingCorpus = stagingCorpus.get().asFile,
        seedCorpus = seedCorpus.get().asFile,
        localCorpus = local.takeIf {
          adoptLocalCorpus.get() && it.listFiles()?.any(File::isFile) == true
        },
      )
    )
  }
}

private fun validateMaxLen(targetName: String, maxLen: Int?) {
  if (maxLen != null && maxLen <= 0) {
    throw GradleException("fuzz '$targetName': maxLen must be positive, was $maxLen")
  }
}

private val JAZZER_JVM_ARGUMENTS = listOf(
  "-XX:+EnableDynamicAgentLoading",
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)
