package software.sava.build.hardening

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSuccessResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/** One configured hardening project's contribution to the Gradle-root inventory. */
internal data class CertificationAggregateProjectRegistration(
  val projectPath: String,
  val projectDirectory: File,
  val receiptFile: File,
  val runningFile: File,
  val suites: List<String>,
) {
  init {
    requireCertificationAggregateProjectPath(projectPath)
    require(suites == suites.sorted()) {
      "certification suite inventory for '$projectPath' is not sorted"
    }
    require(suites.distinct().size == suites.size) {
      "certification suite inventory for '$projectPath' contains duplicates"
    }
    suites.forEach { suite ->
      require(HardeningNames.isSafeName(suite)) {
        "certification suite name '$suite' in '$projectPath' is unsafe"
      }
    }
    val projectRoot = normalizedAbsolutePath(projectDirectory)
    val receipt = normalizedAbsolutePath(receiptFile)
    val running = normalizedAbsolutePath(runningFile)
    require(receipt.startsWith(projectRoot)) {
      "certification receipt for '$projectPath' is outside its configured project directory: $receipt"
    }
    require(running.startsWith(projectRoot)) {
      "certification running sentinel for '$projectPath' is outside its configured project directory: $running"
    }
    require(receipt != running) {
      "certification receipt and running sentinel for '$projectPath' resolve to the same path"
    }
  }

  fun normalized(): CertificationAggregateProjectRegistration = copy(
    projectDirectory = normalizedAbsolutePath(projectDirectory).toFile(),
    receiptFile = normalizedAbsolutePath(receiptFile).toFile(),
    runningFile = normalizedAbsolutePath(runningFile).toFile(),
  )
}

/** One strict child receipt and the suites for which it is authoritative. */
internal data class CertificationAggregateProjectReceipt(
  val projectPath: String,
  val receiptPath: String,
  val receiptSha256: String,
  val suites: List<String>,
) {
  init {
    requireCertificationAggregateProjectPath(projectPath)
    requireCertificationAggregateManifestPath(receiptPath)
    require(RECEIPT_SHA256.matches(receiptSha256)) {
      "invalid child certification receipt SHA-256 for '$projectPath'"
    }
    require(suites == suites.sorted()) {
      "aggregate suite inventory for '$projectPath' is not sorted"
    }
    require(suites.distinct().size == suites.size) {
      "aggregate suite inventory for '$projectPath' contains duplicates"
    }
    suites.forEach { suite ->
      require(HardeningNames.isSafeName(suite)) {
        "aggregate suite name '$suite' in '$projectPath' is unsafe"
      }
    }
  }
}

/** Canonical inventory of the child receipts published by this Gradle-root invocation. */
internal data class HardeningCertificationAggregateManifest(
  val sessionId: String,
  val gradleRootDirectory: String,
  val pluginSha256: String,
  val projects: List<CertificationAggregateProjectReceipt>,
) {
  init {
    require(UUID.fromString(sessionId).toString() == sessionId) {
      "invalid root certification session '$sessionId'"
    }
    requireNormalizedAbsoluteManifestPath("Gradle root directory", gradleRootDirectory)
    require(PLUGIN_SHA256.matches(pluginSha256)) {
      "invalid aggregate hardening plugin identity"
    }
    require(projects.isNotEmpty()) {
      "root certification inventory contains no registered hardening projects"
    }
    require(projects == projects.sortedBy { it.projectPath }) {
      "aggregate project inventory is not sorted"
    }
    require(projects.map { it.projectPath }.distinct().size == projects.size) {
      "aggregate project inventory contains duplicate Gradle paths"
    }
    require(projects.map { it.receiptPath }.distinct().size == projects.size) {
      "aggregate project inventory contains duplicate receipt paths"
    }
  }

  val suiteCount: Int
    get() = projects.sumOf { it.suites.size }

  fun render(): String = buildString {
    appendLine("schema\t$SCHEMA")
    appendLine("scope\t$SCOPE")
    appendLine("rootProjectPath\t:")
    appendLine("gradleRootDirectory\t$gradleRootDirectory")
    appendLine("session\t$sessionId")
    appendLine("mode\tfresh-full-strict")
    appendLine("pluginSha256\t$pluginSha256")
    appendLine("projectCount\t${projects.size}")
    appendLine("suiteCount\t$suiteCount")
    appendLine(PROJECT_COLUMNS)
    projects.forEach { project ->
      appendLine(
        listOf(
          "project",
          project.projectPath,
          project.receiptPath,
          project.receiptSha256,
          project.suites.size,
        ).joinToString("\t")
      )
    }
    appendLine(SUITE_COLUMNS)
    projects.forEach { project ->
      project.suites.forEach { suite ->
        appendLine("suite\t${project.projectPath}\t$suite")
      }
    }
  }

  companion object {
    const val SCHEMA = "1"
    const val SCOPE = "current-gradle-root"
    private const val PROJECT_COLUMNS =
      "projectColumns\tprojectPath\treceiptPath\treceiptSha256\tsuiteCount"
    private const val SUITE_COLUMNS = "suiteColumns\tprojectPath\tsuite"
  }
}

/** Invocation-local state and task outcomes for one Gradle-root aggregate. */
abstract class HardeningCertificationAggregateSession :
  BuildService<HardeningCertificationAggregateSession.Parameters>,
  OperationCompletionListener,
  AutoCloseable {

  interface Parameters : BuildServiceParameters {
    val buildPath: Property<String>
    val aggregateTaskPath: Property<String>
  }

  private enum class AttemptState { STARTED, AUTHORIZED, REJECTED }

  private data class Attempt(
    val sessionId: String,
    var state: AttemptState,
  )

  private val registry = CertificationAggregateRegistry()
  private val fileLocks = CertificationAggregateFileLocks()
  private val attempts = mutableMapOf<String, Attempt>()
  private var aggregateAnchorSucceeded: Boolean? = null
  private val expectedChildTaskPaths = linkedMapOf<String, String>()
  private val childTaskSucceeded = linkedMapOf<String, Boolean>()

  @Synchronized
  override fun onFinish(event: FinishEvent) {
    val task = event as? TaskFinishEvent ?: return
    val taskPath = task.descriptor.taskPath
    if (taskPath == parameters.aggregateTaskPath.get()) {
      aggregateAnchorSucceeded = task.result is TaskSuccessResult
      if (aggregateAnchorSucceeded == false) {
        attempts.values.forEach { attempt ->
          if (attempt.state != AttemptState.REJECTED) {
            attempt.state = AttemptState.REJECTED
          }
        }
      }
      return
    }
    val projectPath = expectedChildTaskPaths[taskPath] ?: return
    childTaskSucceeded[projectPath] = task.result is TaskSuccessResult
  }

  @Synchronized
  internal fun start(gradleRootDirectory: File, lockFile: File): String {
    val rootKey = normalizedAbsolutePath(gradleRootDirectory).toString()
    check(rootKey !in attempts) {
      "hardeningCertifyAll aggregate selection ran more than once"
    }
    return try {
      fileLocks.acquire(rootKey, lockFile)
      UUID.randomUUID().toString().also { sessionId ->
        attempts[rootKey] = Attempt(sessionId, AttemptState.STARTED)
      }
    } catch (failure: Throwable) {
      attempts[rootKey] = Attempt(UUID.randomUUID().toString(), AttemptState.REJECTED)
      throw failure
    }
  }

  @Synchronized
  internal fun activate(
    gradleRootDirectory: File,
    pluginSha256: String,
    projects: Collection<CertificationAggregateProjectRegistration>,
  ): String {
    val rootKey = normalizedAbsolutePath(gradleRootDirectory).toString()
    val attempt = attempts[rootKey] ?: error(
      "hardeningCertifyAll cannot activate without its selection boundary")
    check(attempt.state == AttemptState.STARTED && fileLocks.isHeld(rootKey)) {
      "hardeningCertifyAll cannot activate an unowned or rejected aggregate"
    }
    return try {
      val normalizedProjects = projects.map(CertificationAggregateProjectRegistration::normalized)
      val taskPaths = normalizedProjects.associate { registration ->
        aggregateBuildTreeTaskPath(registration.projectPath) to registration.projectPath
      }
      check(taskPaths.size == normalizedProjects.size) {
        "hardeningCertifyAll registered duplicate child certification task paths"
      }
      registry.activate(
        gradleRootDirectory,
        attempt.sessionId,
        pluginSha256,
        normalizedProjects,
      )
      expectedChildTaskPaths.clear()
      expectedChildTaskPaths.putAll(taskPaths)
      childTaskSucceeded.clear()
      attempt.state = AttemptState.AUTHORIZED
      attempt.sessionId
    } catch (failure: Throwable) {
      attempt.state = AttemptState.REJECTED
      throw failure
    }
  }

  private fun aggregateBuildTreeTaskPath(projectPath: String): String {
    val localTaskPath =
      if (projectPath == ":") ":hardeningCertify" else "$projectPath:hardeningCertify"
    val buildPath = parameters.buildPath.get()
    return if (buildPath == ":") localTaskPath else "$buildPath$localTaskPath"
  }

  @Synchronized
  fun reject(gradleRootDirectory: File) {
    val rootKey = normalizedAbsolutePath(gradleRootDirectory).toString()
    attempts.getOrPut(rootKey) {
      Attempt(UUID.randomUUID().toString(), AttemptState.REJECTED)
    }.state = AttemptState.REJECTED
  }

  @Synchronized
  fun aggregateMayPublish(gradleRootDirectory: File): Boolean =
    attempts[normalizedAbsolutePath(gradleRootDirectory).toString()]?.state ==
      AttemptState.AUTHORIZED

  @Synchronized
  fun aggregateWasRejected(gradleRootDirectory: File): Boolean =
    attempts[normalizedAbsolutePath(gradleRootDirectory).toString()]?.state ==
      AttemptState.REJECTED

  @Synchronized
  fun aggregateAnchorCompletedSuccessfully(): Boolean = aggregateAnchorSucceeded == true

  @Synchronized
  fun aggregateAnchorFailed(): Boolean = aggregateAnchorSucceeded == false

  @Synchronized
  fun unsuccessfulProjectTaskPaths(gradleRootDirectory: File): List<String> =
    registry.registeredProjects(gradleRootDirectory)
      .map(CertificationAggregateProjectRegistration::projectPath)
      .filter { childTaskSucceeded[it] != true }

  @Synchronized
  fun sessionId(gradleRootDirectory: File): String? =
    attempts[normalizedAbsolutePath(gradleRootDirectory).toString()]?.sessionId

  fun ownsAggregate(gradleRootDirectory: File): Boolean =
    fileLocks.isHeld(normalizedAbsolutePath(gradleRootDirectory).toString())

  /** Returns false for direct project certification outside an authorized aggregate. */
  @Synchronized
  internal fun recordPublished(
    gradleRootDirectory: File,
    projectPath: String,
    projectDirectory: File,
    receiptFile: File,
    suites: Collection<String>,
    pluginSha256: String,
    childSessionId: String,
  ): Boolean {
    if (!aggregateMayPublish(gradleRootDirectory)) return false
    return registry.recordPublished(
      gradleRootDirectory,
      projectPath,
      projectDirectory,
      receiptFile,
      suites,
      pluginSha256,
      childSessionId,
    )
  }

  internal fun prepareManifest(gradleRootDirectory: File): PreparedCertificationAggregate =
    registry.prepareManifest(gradleRootDirectory)

  internal fun requireReceiptsUnchanged(prepared: PreparedCertificationAggregate) =
    registry.requireReceiptsUnchanged(prepared)

  internal fun registeredProjects(
    gradleRootDirectory: File,
  ): List<CertificationAggregateProjectRegistration> =
    registry.registeredProjects(gradleRootDirectory)

  override fun close() = fileLocks.close()
}

internal data class PreparedCertificationAggregate(
  val rootKey: String,
  val sessionId: String,
  val manifest: HardeningCertificationAggregateManifest,
  internal val publishedByProject: Map<String, CertificationAggregateRegistry.PublishedReceipt>,
)

/** Directly testable receipt-callback registry behind the Gradle-managed service. */
internal class CertificationAggregateRegistry {
  internal data class PublishedReceipt(
    val sha256: String,
    val childSessionId: String,
  )

  private data class Aggregate(
    val rootDirectory: File,
    val sessionId: String,
    val pluginSha256: String,
    val projects: Map<String, CertificationAggregateProjectRegistration>,
    val published: MutableMap<String, PublishedReceipt> = linkedMapOf(),
  )

  private val aggregates = mutableMapOf<String, Aggregate>()

  @Synchronized
  fun activate(
    gradleRootDirectory: File,
    sessionId: String,
    pluginSha256: String,
    projects: Collection<CertificationAggregateProjectRegistration>,
  ) {
    require(UUID.fromString(sessionId).toString() == sessionId) {
      "invalid root certification session '$sessionId'"
    }
    require(PLUGIN_SHA256.matches(pluginSha256)) {
      "invalid aggregate hardening plugin identity"
    }
    val normalizedProjects = projects.map(CertificationAggregateProjectRegistration::normalized)
    require(normalizedProjects.isNotEmpty()) {
      "hardeningCertifyAll has no registered hardening project inventory"
    }
    require(normalizedProjects.map { it.projectPath }.distinct().size == normalizedProjects.size) {
      "hardeningCertifyAll project inventory contains duplicate Gradle paths"
    }
    require(normalizedProjects.map { it.receiptFile }.distinct().size == normalizedProjects.size) {
      "hardeningCertifyAll project inventory contains duplicate receipt paths"
    }
    require(normalizedProjects.map { it.runningFile }.distinct().size == normalizedProjects.size) {
      "hardeningCertifyAll project inventory contains duplicate running-sentinel paths"
    }
    val root = normalizedAbsolutePath(gradleRootDirectory).toFile()
    normalizedProjects.forEach { project ->
      val paths = listOf(
        "project directory" to project.projectDirectory,
        "receipt" to project.receiptFile,
        "running sentinel" to project.runningFile,
      )
      paths.forEach { (kind, path) ->
        val normalizedPath = normalizedAbsolutePath(path)
        require(normalizedPath.startsWith(root.toPath())) {
          "hardeningCertifyAll project '${project.projectPath}' has a $kind outside the " +
            "Gradle root '$root': $normalizedPath"
        }
        try {
          BaselineFiles.requireNoSymbolicLinkComponents(root, path)
        } catch (failure: IllegalArgumentException) {
          throw IllegalArgumentException(
            "hardeningCertifyAll project '${project.projectPath}' has a $kind that is not " +
              "physically contained by the Gradle root '$root': ${failure.message}",
            failure,
          )
        }
      }
    }
    val rootKey = root.path
    check(rootKey !in aggregates) {
      "hardeningCertifyAll aggregate was activated more than once"
    }
    val inventory = normalizedProjects.sortedBy { it.projectPath }.associateBy { it.projectPath }
    aggregates[rootKey] = Aggregate(root, sessionId, pluginSha256, inventory)
  }

  @Synchronized
  fun recordPublished(
    gradleRootDirectory: File,
    projectPath: String,
    projectDirectory: File,
    receiptFile: File,
    suites: Collection<String>,
    pluginSha256: String,
    childSessionId: String,
  ): Boolean {
    val aggregate = aggregates[normalizedAbsolutePath(gradleRootDirectory).toString()] ?: return false
    val expected = aggregate.projects[projectPath] ?: throw IllegalStateException(
      "hardeningCertify for unregistered project '$projectPath' completed inside hardeningCertifyAll")
    check(pluginSha256 == aggregate.pluginSha256) {
      "hardeningCertify project '$projectPath' published with a different plugin identity"
    }
    check(normalizedAbsolutePath(projectDirectory).toFile() == expected.projectDirectory) {
      "hardeningCertify project '$projectPath' changed its configured project directory"
    }
    check(normalizedAbsolutePath(receiptFile).toFile() == expected.receiptFile) {
      "hardeningCertify project '$projectPath' published an unexpected receipt path"
    }
    val actualSuites = suites.toList()
    check(actualSuites == actualSuites.sorted() && actualSuites.distinct().size == actualSuites.size) {
      "hardeningCertify project '$projectPath' published a non-canonical suite inventory"
    }
    check(actualSuites == expected.suites) {
      "hardeningCertify project '$projectPath' published a different suite inventory: " +
        "expected ${expected.suites.joinToString()}, found ${actualSuites.joinToString()}"
    }
    val sha256 = currentReceiptSha256(expected, aggregate.pluginSha256, childSessionId)
    check(aggregate.published.putIfAbsent(
        projectPath,
        PublishedReceipt(sha256, childSessionId),
      ) == null) {
      "hardeningCertify project '$projectPath' published more than once in one aggregate invocation"
    }
    return true
  }

  @Synchronized
  fun prepareManifest(gradleRootDirectory: File): PreparedCertificationAggregate {
    val rootKey = normalizedAbsolutePath(gradleRootDirectory).toString()
    val aggregate = aggregates[rootKey] ?: throw IllegalStateException(
      "hardeningCertifyAll did not activate an aggregate in this Gradle invocation")
    val missing = aggregate.projects.keys - aggregate.published.keys
    check(missing.isEmpty()) {
      "hardeningCertifyAll did not publish every registered project receipt in this Gradle " +
        "invocation; missing: ${missing.sorted().joinToString()}"
    }
    val published = aggregate.published.toSortedMap()
    aggregate.projects.forEach { (projectPath, project) ->
      val recorded = published.getValue(projectPath)
      val current = currentReceiptSha256(
        project,
        aggregate.pluginSha256,
        recorded.childSessionId,
      )
      check(current == recorded.sha256) {
        "hardeningCertify receipt for '$projectPath' changed after child publication " +
          "(${recorded.sha256} -> $current)"
      }
    }
    val manifest = HardeningCertificationAggregateManifest(
      aggregate.sessionId,
      normalizedManifestAbsolutePath(aggregate.rootDirectory),
      aggregate.pluginSha256,
      aggregate.projects.values.map { project ->
        CertificationAggregateProjectReceipt(
          project.projectPath,
          certificationAggregateManifestPath(aggregate.rootDirectory, project.receiptFile),
          published.getValue(project.projectPath).sha256,
          project.suites,
        )
      },
    )
    return PreparedCertificationAggregate(rootKey, aggregate.sessionId, manifest, published)
  }

  @Synchronized
  fun requireReceiptsUnchanged(prepared: PreparedCertificationAggregate) {
    val aggregate = aggregates[prepared.rootKey] ?: throw IllegalStateException(
      "hardeningCertifyAll aggregate disappeared before manifest publication")
    check(aggregate.sessionId == prepared.sessionId) {
      "hardeningCertifyAll aggregate session changed before manifest publication"
    }
    aggregate.projects.forEach { (projectPath, project) ->
      val recorded = prepared.publishedByProject.getValue(projectPath)
      val current = currentReceiptSha256(
        project,
        aggregate.pluginSha256,
        recorded.childSessionId,
      )
      check(current == recorded.sha256) {
        "hardeningCertify receipt for '$projectPath' changed during aggregate publication " +
          "(${recorded.sha256} -> $current)"
      }
    }
  }

  @Synchronized
  fun registeredProjects(
    gradleRootDirectory: File,
  ): List<CertificationAggregateProjectRegistration> =
    aggregates[normalizedAbsolutePath(gradleRootDirectory).toString()]
      ?.projects
      ?.values
      ?.toList()
      ?: throw IllegalStateException(
        "hardeningCertifyAll did not activate a registered project inventory")

  private fun currentReceiptSha256(
    project: CertificationAggregateProjectRegistration,
    pluginSha256: String,
    childSessionId: String,
  ): String {
    check(BaselineFiles.readRegularFileSnapshot(
        project.projectDirectory,
        project.runningFile,
      ) == null) {
      "hardeningCertify for '${project.projectPath}' retained its incomplete-attempt sentinel"
    }
    val bytes = BaselineFiles.readRegularFileSnapshot(
      project.projectDirectory,
      project.receiptFile,
    ) ?: throw IllegalStateException(
      "hardeningCertify for '${project.projectPath}' published no child receipt")
    inspectCertificationChildReceipt(bytes, project, pluginSha256, childSessionId)
    return PitestEvidence.sha256(bytes)
  }
}

/**
 * Binds the trusted child writer's schema-7 receipt to this invocation and inventory.
 * The child task owns validation of its per-suite evidence fields; the aggregate hashes
 * those exact bytes instead of maintaining a second copy of the child receipt parser.
 */
private fun inspectCertificationChildReceipt(
  bytes: ByteArray,
  expected: CertificationAggregateProjectRegistration,
  expectedPluginSha256: String,
  expectedChildSessionId: String,
) {
  val text = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
  } catch (failure: Exception) {
    throw IllegalStateException(
      "hardeningCertify receipt for '${expected.projectPath}' is not valid UTF-8",
      failure,
    )
  }
  check(text.endsWith('\n') && '\r' !in text && '\u0000' !in text) {
    "hardeningCertify receipt for '${expected.projectPath}' is not canonical line-delimited TSV"
  }
  val lines = text.dropLast(1).split('\n')
  check(lines.size >= CHILD_RECEIPT_FIXED_LINE_COUNT) {
    "hardeningCertify receipt for '${expected.projectPath}' is truncated"
  }

  fun field(index: Int, name: String): String {
    val columns = lines[index].split('\t')
    check(columns.size == 2 && columns[0] == name) {
      "hardeningCertify receipt for '${expected.projectPath}' has invalid '$name' metadata"
    }
    return columns[1]
  }

  check(field(0, "schema") == CHILD_RECEIPT_SCHEMA) {
    "hardeningCertify receipt for '${expected.projectPath}' has unsupported schema"
  }
  check(field(1, "project") == expected.projectPath) {
    "hardeningCertify receipt project does not match registered project '${expected.projectPath}'"
  }
  val childSession = field(2, "session")
  check(runCatching { UUID.fromString(childSession).toString() }.getOrNull() == childSession) {
    "hardeningCertify receipt for '${expected.projectPath}' has an invalid child session"
  }
  check(childSession == expectedChildSessionId) {
    "hardeningCertify receipt for '${expected.projectPath}' belongs to child session " +
      "$childSession instead of the current invocation's $expectedChildSessionId"
  }
  check(field(3, "mode") == "fresh-full-strict") {
    "hardeningCertify receipt for '${expected.projectPath}' is not fresh-full-strict"
  }
  check(field(9, "pluginSha256") == expectedPluginSha256) {
    "hardeningCertify receipt for '${expected.projectPath}' has a different plugin identity"
  }
  val suiteColumns = lines[10].split('\t')
  check(suiteColumns.size >= 2 &&
      suiteColumns[0] == "suiteColumns" && suiteColumns[1] == "name") {
    "hardeningCertify receipt for '${expected.projectPath}' has no suite-name column"
  }

  val suites = lines.drop(CHILD_RECEIPT_FIXED_LINE_COUNT).mapIndexed { offset, line ->
    val columns = line.split('\t')
    check(columns.size >= 2 && columns[0] == "suite") {
      "hardeningCertify receipt for '${expected.projectPath}' has invalid suite row " +
        "${offset + CHILD_RECEIPT_FIXED_LINE_COUNT + 1}"
    }
    columns[1]
  }
  check(suites == expected.suites) {
    "hardeningCertify receipt for '${expected.projectPath}' suite inventory differs from " +
      "the registered inventory: expected ${expected.suites.joinToString()}, " +
      "found ${suites.joinToString()}"
  }
}

/** Cross-process ownership for one Gradle root's aggregate manifest and sentinel. */
internal class CertificationAggregateFileLocks : AutoCloseable {
  private data class HeldLock(
    val channel: FileChannel,
    val lock: FileLock,
  )

  private val held = linkedMapOf<String, HeldLock>()

  @Synchronized
  fun acquire(rootKey: String, lockFile: File) {
    check(rootKey !in held) {
      "hardeningCertifyAll aggregate ownership lock was acquired more than once"
    }
    val normalized = normalizedAbsolutePath(lockFile).toString()
    val channel = FileChannel.open(
      Path.of(normalized),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
    val lock = try {
      channel.tryLock()
    } catch (_: OverlappingFileLockException) {
      null
    } catch (failure: Throwable) {
      try {
        channel.close()
      } catch (closeFailure: Throwable) {
        failure.addSuppressed(closeFailure)
      }
      throw failure
    }
    if (lock == null) {
      channel.close()
      throw IllegalStateException(
        "another hardeningCertifyAll invocation owns the aggregate via $normalized; " +
          "wait for it to finish before replacing root certification evidence"
      )
    }
    held[rootKey] = HeldLock(channel, lock)
  }

  @Synchronized
  fun isHeld(rootKey: String): Boolean = rootKey in held

  @Synchronized
  override fun close() {
    val failures = mutableListOf<Exception>()
    held.values.toList().asReversed().forEach { entry ->
      try {
        entry.lock.release()
      } catch (failure: Exception) {
        failures += failure
      }
      try {
        entry.channel.close()
      } catch (failure: Exception) {
        failures += failure
      }
    }
    held.clear()
    if (failures.isNotEmpty()) {
      val failure = IllegalStateException(
        "could not release hardeningCertifyAll aggregate ownership lock")
      failures.forEach(failure::addSuppressed)
      throw failure
    }
  }
}

private val RECEIPT_SHA256 = Regex("[0-9a-f]{64}")
private val PLUGIN_SHA256 = Regex("(?:tree:)?[0-9a-f]{64}")
private const val CHILD_RECEIPT_SCHEMA = "7"
private const val CHILD_RECEIPT_FIXED_LINE_COUNT = 11

private fun normalizedAbsolutePath(file: File): Path =
  file.toPath().toAbsolutePath().normalize()

private fun normalizedManifestAbsolutePath(file: File): String =
  normalizedAbsolutePath(file).toString().replace(File.separatorChar, '/')

private fun certificationAggregateManifestPath(root: File, target: File): String {
  val normalizedRoot = normalizedAbsolutePath(root)
  val normalizedTarget = normalizedAbsolutePath(target)
  require(normalizedTarget.startsWith(normalizedRoot)) {
    "aggregate child receipt is outside the Gradle root: $normalizedTarget"
  }
  val relative = normalizedRoot.relativize(normalizedTarget).toString()
    .replace(File.separatorChar, '/')
  require(relative.isNotEmpty()) {
    "aggregate child receipt cannot be the Gradle root directory"
  }
  return "root:$relative"
}

private fun requireCertificationAggregateManifestPath(value: String) {
  requireCertificationAggregateField("aggregate receipt path", value)
  require(value.startsWith("root:")) {
    "aggregate receipt path must be relative to the Gradle root: '$value'"
  }
  val relative = value.removePrefix("root:")
  require(relative.isNotEmpty() && relative != "." && !relative.startsWith('/')) {
    "aggregate root-relative receipt path is empty or absolute: '$value'"
  }
  val path = Path.of(relative)
  require(!path.isAbsolute &&
      path.normalize().toString().replace(File.separatorChar, '/') == relative &&
      path.none { it.toString() == ".." }) {
    "aggregate root-relative receipt path is not normalized and contained: '$value'"
  }
}

private fun requireNormalizedAbsoluteManifestPath(kind: String, value: String) {
  requireCertificationAggregateField(kind, value)
  val path = Path.of(value)
  require(path.isAbsolute &&
      path.normalize().toString().replace(File.separatorChar, '/') == value) {
    "$kind is not a normalized absolute path: '$value'"
  }
}

private fun requireCertificationAggregateField(kind: String, value: String) {
  require(value.isNotBlank() &&
      value.none { it == '\t' || it == '\n' || it == '\r' || it == '\u0000' }) {
    "$kind must be a nonblank single TSV field"
  }
}

private fun requireCertificationAggregateProjectPath(value: String) {
  requireCertificationAggregateField("Gradle project path", value)
  require(value == ":" ||
      (value.startsWith(':') && value.drop(1).split(':').none(String::isEmpty))) {
    "invalid Gradle project path '$value'"
  }
}
