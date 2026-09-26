package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Largest integer represented exactly by jq/JSON's IEEE-754 number model. */
internal const val MAX_FUZZ_RECEIPT_EXECUTIONS: Long = 9_007_199_254_740_991L

/** A campaign state record is one short line; anything longer is not this campaign's. */
private const val MAX_FUZZ_STATE_RECORD_BYTES = 256L

/**
 * Invocation-local proof that every target named by a project's [fuzzAll] campaign
 * actually completed. The service deliberately does nothing for standalone fuzz
 * target executions, preserving their ordinary developer-loop behavior.
 */
abstract class HardeningFuzzSession :
  BuildService<BuildServiceParameters.None>, AutoCloseable {

  data class CompletedCampaign(
    val sessionId: String,
    val executionsByTarget: Map<String, Long>,
  ) {
    val targets: Set<String> get() = executionsByTarget.keys
    val totalExecutions: Long get() = executionsByTarget.entries.fold(0L) { total, (target, count) ->
      val sum = try {
        Math.addExact(total, count)
      } catch (overflow: ArithmeticException) {
        throw IllegalStateException(
          "fuzzAll execution-count total overflowed while adding '$target' ($count)", overflow)
      }
      check(sum <= MAX_FUZZ_RECEIPT_EXECUTIONS) {
        "fuzzAll execution-count total exceeds JSON's exact-integer boundary " +
          "$MAX_FUZZ_RECEIPT_EXECUTIONS while adding '$target' ($count)"
      }
      sum
    }
  }

  /** Where a campaign keeps its state record, relative to a trusted root. */
  private data class CampaignFiles(
    val trustedProjectDirectory: File,
    val running: File,
  )

  private val registry = FuzzCampaignRegistry()
  private val fileLocks = FuzzCampaignFileLocks()
  private val campaignFiles = linkedMapOf<String, CampaignFiles>()

  fun activate(
    projectPath: String,
    expectedTargets: Collection<String>,
    lockFile: File,
    trustedProjectDirectory: File,
    runningFile: File,
  ): String {
    fileLocks.acquire(projectPath, lockFile)
    val sessionId = registry.activate(projectPath, expectedTargets)
    synchronized(campaignFiles) {
      campaignFiles[projectPath] = CampaignFiles(trustedProjectDirectory, runningFile)
    }
    return sessionId
  }

  /** Keeps a live campaign's first target failure for its refused record; see [close]. */
  fun recordFailure(projectPath: String, target: String, reason: String) =
    registry.recordFailure(projectPath, target, reason)

  fun refuse(
    projectPath: String,
    expectedTargets: Collection<String>,
    reason: String,
  ) = registry.refuse(projectPath, expectedTargets, reason)

  /** Returns false for a standalone task and throws for a refused aggregate campaign. */
  fun requireRunnable(projectPath: String): Boolean = registry.requireRunnable(projectPath)

  fun ownsCampaign(projectPath: String): Boolean = fileLocks.isHeld(projectPath)

  /** Returns false when this is an ordinary target run outside an active campaign. */
  fun recordCompleted(projectPath: String, target: String, executions: Long): Boolean =
    registry.recordCompleted(projectPath, target, executions)

  internal fun recordObservation(
    projectPath: String,
    target: String,
    executions: Long,
    observation: FuzzTargetObservation,
  ): Boolean = registry.recordObservation(projectPath, target, executions, observation)

  internal fun requireObservations(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): Map<String, FuzzTargetObservation> = registry.requireObservations(projectPath, expectedTargets)

  fun requireCompleted(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): CompletedCampaign = registry.requireCompleted(projectPath, expectedTargets)

  /**
   * Gradle skips `fuzzAllComplete` once a target fails, so the end of the build is the one
   * point that sees every campaign. A campaign whose state record still holds its own
   * `starting` or `session` state never published a receipt: it gets the retained `refused`
   * record, and the prior receipt stays beside it. A campaign that published, or already
   * recorded a refusal, is left alone. This runs before the ownership locks are released,
   * so no later campaign can own the record being replaced. A process that is killed never
   * gets here and leaves `session`, which still marks an incomplete attempt.
   */
  override fun close() {
    val failures = mutableListOf<Throwable>()
    try {
      synchronized(campaignFiles) { campaignFiles.toMap() }.forEach { (projectPath, files) ->
        try {
          refuseUnfinished(projectPath, files)
        } catch (failure: Exception) {
          failures += failure
        }
      }
    } finally {
      try {
        fileLocks.close()
      } catch (failure: Exception) {
        failures += failure
      }
    }
    failures.firstOrNull()?.let { first ->
      failures.drop(1).forEach(first::addSuppressed)
      throw first
    }
  }

  private fun refuseUnfinished(projectPath: String, files: CampaignFiles) {
    if (!fileLocks.isHeld(projectPath)) return
    val sessionId = registry.sessionId(projectPath) ?: return
    BaselineFiles.requireRegularFileOrMissing(files.trustedProjectDirectory, files.running)
    if (!files.running.isFile || files.running.length() > MAX_FUZZ_STATE_RECORD_BYTES) return
    val state = files.running.readText()
    if (state != "session\t$sessionId\n" && state != "starting\t$sessionId\n") return
    val reason = registry.incompleteReason(projectPath) ?: return
    // The owned marker is intact and already keeps the receipt historical, so a failed
    // write leaves both as they are rather than deleting last-known-success evidence.
    BaselineFiles.writeAtomically(
      files.trustedProjectDirectory,
      files.running,
      "refused\t${reason.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')}\n",
    )
  }
}

/**
 * Cross-process ownership for aggregate evidence. Gradle build services coordinate only
 * tasks in one invocation; retaining the OS lock until build-service close prevents an
 * older campaign from publishing over (or clearing the sentinel of) a later campaign.
 * The zero-byte lock file is intentionally retained so concurrent openers always contend
 * on the same inode.
 */
internal class FuzzCampaignFileLocks : AutoCloseable {
  private data class HeldLock(
    val path: String,
    val channel: FileChannel,
    val lock: FileLock,
  )

  private val held = linkedMapOf<String, HeldLock>()

  @Synchronized
  fun acquire(projectPath: String, lockFile: File) {
    val normalized = lockFile.toPath().toAbsolutePath().normalize()
    val current = held[projectPath]
    if (current != null) {
      check(current.path == normalized.toString()) {
        "fuzzAll campaign for '$projectPath' changed its ownership-lock path"
      }
      return
    }
    val channel = FileChannel.open(
      normalized,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
    val lock = try {
      channel.tryLock()
    } catch (_: OverlappingFileLockException) {
      null
    }
    if (lock == null) {
      channel.close()
      throw IllegalStateException(
        "another fuzzAll campaign owns '$projectPath' via $normalized; " +
          "wait for it to finish before replacing its release evidence"
      )
    }
    held[projectPath] = HeldLock(normalized.toString(), channel, lock)
  }

  @Synchronized
  fun isHeld(projectPath: String): Boolean = projectPath in held

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
      val failure = IllegalStateException("could not release fuzzAll campaign ownership lock")
      failures.forEach(failure::addSuppressed)
      throw failure
    }
  }
}

/** Pure state machine behind the Gradle-managed service, kept directly unit-testable. */
internal class FuzzCampaignRegistry {
  private data class Campaign(
    val sessionId: String,
    val expectedTargets: Set<String>,
    val executionsByTarget: MutableMap<String, Long> = linkedMapOf(),
    val observationsByTarget: MutableMap<String, FuzzTargetObservation> = linkedMapOf(),
    var refusalReason: String? = null,
    var firstFailure: String? = null,
  )

  private val campaigns = mutableMapOf<String, Campaign>()

  @Synchronized
  fun activate(projectPath: String, expectedTargets: Collection<String>): String {
    val expected = expectedTargets.toSortedSet()
    val current = campaigns[projectPath]
    if (current != null) {
      check(current.expectedTargets == expected) {
        "fuzzAll campaign for '$projectPath' was activated with a different target inventory"
      }
      return current.sessionId
    }
    return UUID.randomUUID().toString().also { sessionId ->
      campaigns[projectPath] = Campaign(sessionId, expected)
    }
  }

  @Synchronized
  fun refuse(projectPath: String, expectedTargets: Collection<String>, reason: String) {
    require(reason.isNotBlank()) { "fuzzAll refusal reason must not be blank" }
    val expected = expectedTargets.toSortedSet()
    val campaign = campaigns.getOrPut(projectPath) {
      Campaign(UUID.randomUUID().toString(), expected)
    }
    check(campaign.expectedTargets == expected) {
      "fuzzAll campaign for '$projectPath' was refused with a different target inventory"
    }
    campaign.refusalReason = reason
  }

  /**
   * Keeps the first target failure of a live campaign. It does not refuse the campaign:
   * the remaining targets still run, and [incompleteReason] reports it at the build end.
   */
  @Synchronized
  fun recordFailure(projectPath: String, target: String, reason: String) {
    val campaign = campaigns[projectPath] ?: return
    if (campaign.firstFailure == null) {
      campaign.firstFailure = "fuzz${target.replaceFirstChar(Char::uppercase)} failed: $reason"
    }
  }

  @Synchronized
  fun sessionId(projectPath: String): String? = campaigns[projectPath]?.sessionId

  /**
   * Why the campaign for [projectPath] ended without a receipt: its refusal, else its
   * first target failure, else the targets it never completed. Null for no campaign.
   */
  @Synchronized
  fun incompleteReason(projectPath: String): String? {
    val campaign = campaigns[projectPath] ?: return null
    campaign.refusalReason?.let { return it }
    campaign.firstFailure?.let { return it }
    val missing = campaign.expectedTargets - campaign.executionsByTarget.keys
    if (missing.isNotEmpty()) {
      return "fuzzAll did not complete every configured target; missing: " +
        missing.joinToString { "fuzz${it.replaceFirstChar(Char::uppercase)}" }
    }
    return "fuzzAll ended before publishing its receipt"
  }

  /** Returns false for a standalone target, true for a live aggregate, and throws if refused. */
  @Synchronized
  fun requireRunnable(projectPath: String): Boolean {
    val campaign = campaigns[projectPath] ?: return false
    campaign.refusalReason?.let { reason ->
      throw IllegalStateException("fuzzAll campaign for '$projectPath' was refused: $reason")
    }
    return true
  }

  @Synchronized
  fun recordCompleted(projectPath: String, target: String, executions: Long): Boolean {
    val campaign = campaigns[projectPath] ?: return false
    campaign.refusalReason?.let { reason ->
      throw IllegalStateException("fuzzAll campaign for '$projectPath' was refused: $reason")
    }
    check(target in campaign.expectedTargets) {
      "fuzz target '$projectPath:$target' completed outside the active fuzzAll inventory"
    }
    check(executions > 0) {
      "fuzz target '$projectPath:$target' recorded a non-positive execution count: $executions"
    }
    check(campaign.executionsByTarget.putIfAbsent(target, executions) == null) {
      "fuzz target '$projectPath:$target' completed more than once in one fuzzAll campaign"
    }
    return true
  }

  @Synchronized
  fun recordObservation(
    projectPath: String,
    target: String,
    executions: Long,
    observation: FuzzTargetObservation,
  ): Boolean {
    if (!recordCompleted(projectPath, target, executions)) return false
    campaigns.getValue(projectPath).observationsByTarget[target] = observation
    return true
  }

  @Synchronized
  fun requireObservations(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): Map<String, FuzzTargetObservation> {
    requireCompleted(projectPath, expectedTargets)
    val campaign = campaigns.getValue(projectPath)
    val missing = campaign.expectedTargets - campaign.observationsByTarget.keys
    check(missing.isEmpty()) {
      "fuzzAll completed target(s) without captured source/log evidence: " +
        missing.joinToString { "fuzz${it.replaceFirstChar(Char::uppercase)}" }
    }
    return campaign.observationsByTarget.toSortedMap()
  }

  @Synchronized
  fun requireCompleted(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): HardeningFuzzSession.CompletedCampaign {
    val expected = expectedTargets.toSortedSet()
    val campaign = campaigns[projectPath] ?: throw IllegalStateException(
      "fuzzAll start boundary did not activate a campaign in this Gradle invocation"
    )
    campaign.refusalReason?.let { reason ->
      throw IllegalStateException("fuzzAll campaign for '$projectPath' was refused: $reason")
    }
    check(campaign.expectedTargets == expected) {
      "fuzzAll target inventory changed after preflight: expected " +
        "${campaign.expectedTargets.joinToString()}, found ${expected.joinToString()}"
    }
    val missing = campaign.expectedTargets - campaign.executionsByTarget.keys
    check(missing.isEmpty()) {
      "fuzzAll did not complete every configured target in this Gradle invocation; missing: " +
        missing.joinToString { "fuzz${it.replaceFirstChar(Char::uppercase)}" }
    }
    return HardeningFuzzSession.CompletedCampaign(
      campaign.sessionId,
      campaign.executionsByTarget.toSortedMap(),
    )
  }
}
