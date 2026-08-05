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

  private val registry = FuzzCampaignRegistry()
  private val fileLocks = FuzzCampaignFileLocks()

  fun activate(
    projectPath: String,
    expectedTargets: Collection<String>,
    lockFile: File,
  ): String {
    fileLocks.acquire(projectPath, lockFile)
    return registry.activate(projectPath, expectedTargets)
  }

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

  fun requireCompleted(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): CompletedCampaign = registry.requireCompleted(projectPath, expectedTargets)

  override fun close() = fileLocks.close()
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
    var refusalReason: String? = null,
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
