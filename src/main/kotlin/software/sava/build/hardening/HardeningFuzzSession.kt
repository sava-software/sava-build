package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.UUID

/**
 * Invocation-local proof that every target named by a project's [fuzzAll] campaign
 * actually completed. The service deliberately does nothing for standalone fuzz
 * target executions, preserving their ordinary developer-loop behavior.
 */
abstract class HardeningFuzzSession : BuildService<BuildServiceParameters.None> {

  data class CompletedCampaign(
    val sessionId: String,
    val targets: Set<String>,
  )

  private val registry = FuzzCampaignRegistry()

  fun activate(projectPath: String, expectedTargets: Collection<String>): String =
    registry.activate(projectPath, expectedTargets)

  /** Returns false when this is an ordinary target run outside an active campaign. */
  fun recordCompleted(projectPath: String, target: String): Boolean =
    registry.recordCompleted(projectPath, target)

  fun requireCompleted(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): CompletedCampaign = registry.requireCompleted(projectPath, expectedTargets)
}

/** Pure state machine behind the Gradle-managed service, kept directly unit-testable. */
internal class FuzzCampaignRegistry {
  private data class Campaign(
    val sessionId: String,
    val expectedTargets: Set<String>,
    val completedTargets: MutableSet<String> = linkedSetOf(),
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
  fun recordCompleted(projectPath: String, target: String): Boolean {
    val campaign = campaigns[projectPath] ?: return false
    check(target in campaign.expectedTargets) {
      "fuzz target '$projectPath:$target' completed outside the active fuzzAll inventory"
    }
    campaign.completedTargets.add(target)
    return true
  }

  @Synchronized
  fun requireCompleted(
    projectPath: String,
    expectedTargets: Collection<String>,
  ): HardeningFuzzSession.CompletedCampaign {
    val expected = expectedTargets.toSortedSet()
    val campaign = campaigns[projectPath] ?: throw IllegalStateException(
      "fuzzAll preflight did not activate a campaign in this Gradle invocation"
    )
    check(campaign.expectedTargets == expected) {
      "fuzzAll target inventory changed after preflight: expected " +
        "${campaign.expectedTargets.joinToString()}, found ${expected.joinToString()}"
    }
    val missing = campaign.expectedTargets - campaign.completedTargets
    check(missing.isEmpty()) {
      "fuzzAll did not complete every configured target in this Gradle invocation; missing: " +
        missing.joinToString { "fuzz${it.replaceFirstChar(Char::uppercase)}" }
    }
    return HardeningFuzzSession.CompletedCampaign(
      campaign.sessionId,
      campaign.completedTargets.toSortedSet(),
    )
  }
}
