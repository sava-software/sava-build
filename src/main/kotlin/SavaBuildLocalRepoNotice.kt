import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import software.sava.build.hardening.HardeningOptionNames
import software.sava.build.hardening.HardeningPluginIdentity
import software.sava.build.hardening.HardeningPluginIdentityService
import software.sava.build.hardening.PitestEvidence
import java.io.File
import javax.inject.Inject

/**
 * Announces, once per build, that 'software.sava.build*' plugins were resolved from a
 * local sava-build checkout rather than from a published release.
 *
 * The consumer-side 'pluginManagement' snippet (README) redirects every id to the
 * '0.0.0-test' module when '-PsavaBuildLocalRepo' is set, so the versions in the
 * consumer's 'plugins {}' block are silently ignored — and the publish that feeds that
 * repo is manual, so the plugin actually running can be arbitrarily older than the
 * checkout it was built from.
 *
 * The notice used to be a 'logger.warn' inside that snippet, which put it in the
 * settings script: with the configuration cache enabled the script is skipped on a hit,
 * so the line printed on the first run and then went quiet — silent in exactly the two
 * cases worth warning about (a forgotten publish changes nothing, so the entry is
 * reused; and switching back to an existing local-repo entry re-uses it too). A
 * dataflow action is stored in the cache entry and replayed on a hit, so this fires on
 * every build. The source-provenance sidecar is frozen with the JAR when settings apply
 * and re-read by that action, while its recorded UTC timestamp is aged at execution.
 * Its identity is a snapshot at publication, not a claim about the publisher checkout's
 * later state.
 */
abstract class SavaBuildLocalRepoNoticePlugin @Inject constructor(
  private val flowScope: FlowScope
) : Plugin<Settings> {

  override fun apply(settings: Settings) {
    // Settings is the earliest common application boundary. Project convention
    // plugins consume this value later; a direct feature-plugin application falls
    // back to registering the same service from its own code source.
    val pluginIdentity = HardeningPluginIdentity.capture(SavaBuildLocalRepoNoticePlugin::class.java)
    val localRepo = settings.providers.gradleProperty(LOCAL_REPO_PROPERTY)
      .orNull?.takeIf { it.isNotBlank() }
    val repoDir = localRepo?.let { settings.settingsDir.resolve(it).absolutePath }
    val repoArtifact = repoDir?.let {
      File(
        it,
        "software/sava/sava-build/$TEST_VERSION/sava-build-$TEST_VERSION.jar",
      ).absoluteFile.normalize()
    }
    val repoProvenance = repoArtifact?.let(SavaBuildLocalPublicationProvenance::sidecarFor)
    val repoArtifactSha256 = when {
      repoArtifact == null -> HardeningPluginIdentityService.NO_LOCAL_ARTIFACT
      repoArtifact.isFile -> PitestEvidence.sha256(repoArtifact)
      else -> HardeningPluginIdentityService.MISSING_LOCAL_ARTIFACT
    }
    settings.gradle.sharedServices.registerIfAbsent(
      HardeningPluginIdentityService.SERVICE_NAME,
      HardeningPluginIdentityService::class.java,
    ) {
      parameters.applicationPluginSha256.set(pluginIdentity.sha256)
      parameters.localRepoArtifactPath.set(
        repoArtifact?.absolutePath ?: HardeningPluginIdentityService.NO_LOCAL_ARTIFACT)
      parameters.applicationLocalRepoArtifactSha256.set(repoArtifactSha256)
    }
    if (localRepo == null) return
    // Resolved here, against the consumer's settings dir, so the action reports the
    // same directory the 'pluginManagement' repository declaration resolves a relative
    // property value to.
    val configuredRepoDir = checkNotNull(repoDir)
    val configuredRepoArtifact = checkNotNull(repoArtifact)
    val configuredRepoProvenance = checkNotNull(repoProvenance)
    var localPublicationProvenance: SavaBuildLocalPublicationProvenance? = null
    if (configuredRepoArtifact.isFile) {
      repoArtifactSha256.also { sha256 ->
        if (sha256 != pluginIdentity.sha256) {
          throw GradleException(
            "sava-build: the loaded local plugin ${pluginIdentity.sha256} does not match " +
              "the configured local-repo artifact $sha256 at $configuredRepoArtifact; re-run with " +
              "--refresh-dependencies after publishing the static $TEST_VERSION coordinate"
          )
        }
      }
      if (!configuredRepoProvenance.isFile) {
        throw GradleException(
          "sava-build: local publication provenance is missing at " +
            "$configuredRepoProvenance; re-run sava-build's '$PUBLISH_TASK'"
        )
      }
      localPublicationProvenance = readLocalPublicationProvenance(configuredRepoProvenance)
      if (localPublicationProvenance.jarSha256 != repoArtifactSha256) {
        throw GradleException(
          "sava-build: local publication provenance claims JAR SHA-256 " +
            "${localPublicationProvenance.jarSha256}, but the configured local-repo artifact is " +
            "$repoArtifactSha256 at $configuredRepoArtifact; re-run sava-build's '$PUBLISH_TASK'"
        )
      }
    }
    flowScope.always(SavaBuildLocalRepoNotice::class.java) {
      parameters.localRepo.set(configuredRepoDir)
      parameters.pluginCodePath.set(pluginIdentity.codePath.absolutePath)
      parameters.repoArtifactPath.set(configuredRepoArtifact.absolutePath)
      parameters.repoProvenancePath.set(configuredRepoProvenance.absolutePath)
      parameters.applicationPluginSha256.set(pluginIdentity.sha256)
      parameters.applicationRepoArtifactSha256.set(repoArtifactSha256)
      parameters.applicationRepoProvenanceSha256.set(
        localPublicationProvenance?.let { PitestEvidence.sha256(configuredRepoProvenance) }
          ?: NO_LOCAL_PROVENANCE
      )
      parameters.applicationRepoProvenance.set(
        localPublicationProvenance?.render() ?: NO_LOCAL_PROVENANCE
      )
    }
  }

  companion object {

    const val LOCAL_REPO_PROPERTY: String = HardeningOptionNames.SAVA_BUILD_LOCAL_REPO

    const val TEST_VERSION: String = "0.0.0-test"

    const val PUBLISH_TASK: String = "publishSavaBuildTestPublicationToSavaTestRepoRepository"

    const val NO_LOCAL_PROVENANCE: String = "none"
  }
}

/**
 * Prints the local-repo notice at the end of the build. Registered by
 * [SavaBuildLocalRepoNoticePlugin]; see its documentation for why this is a dataflow
 * action rather than a line in the settings script.
 */
class SavaBuildLocalRepoNotice : FlowAction<SavaBuildLocalRepoNotice.Parameters> {

  interface Parameters : FlowParameters {

    /** Absolute path of the local Maven repository plugin ids were redirected to. */
    @get:Input
    val localRepo: Property<String>

    /** Code source used by the plugin classloader when the settings plugin applied. */
    @get:Input
    val pluginCodePath: Property<String>

    /** Main local-repository artifact expected to contain those same plugin bytes. */
    @get:Input
    val repoArtifactPath: Property<String>

    /** Source-provenance sidecar beside [repoArtifactPath]. */
    @get:Input
    val repoProvenancePath: Property<String>

    /** SHA-256 frozen when this plugin was applied (or restored from the cache entry). */
    @get:Input
    val applicationPluginSha256: Property<String>

    /** Application-time repository SHA, or the missing-artifact sentinel. */
    @get:Input
    val applicationRepoArtifactSha256: Property<String>

    /** SHA-256 of the canonical provenance sidecar frozen at plugin application. */
    @get:Input
    val applicationRepoProvenanceSha256: Property<String>

    /** Canonical provenance fields frozen at plugin application for cache replay. */
    @get:Input
    val applicationRepoProvenance: Property<String>
  }

  override fun execute(parameters: Parameters) {
    val repo = parameters.localRepo.get()
    val version = SavaBuildLocalRepoNoticePlugin.TEST_VERSION
    val expectedSha256 = parameters.applicationPluginSha256.get()
    val pluginCode = File(parameters.pluginCodePath.get())
    val currentPluginSha256 = PitestEvidence.fingerprintTree(pluginCode)
    if (currentPluginSha256 != expectedSha256) {
      throw GradleException(
        "sava-build: loaded local plugin code changed during the build " +
          "($expectedSha256 -> $currentPluginSha256 at $pluginCode)"
      )
    }
    val repoArtifact = File(parameters.repoArtifactPath.get())
    val applicationRepoSha256 = parameters.applicationRepoArtifactSha256.get()
    val currentRepoSha256 = if (repoArtifact.isFile) {
      PitestEvidence.sha256(repoArtifact)
    } else {
      HardeningPluginIdentityService.MISSING_LOCAL_ARTIFACT
    }
    if (currentRepoSha256 != applicationRepoSha256) {
      throw GradleException(
        "sava-build: local plugin artifact changed after plugin application " +
          "($applicationRepoSha256 -> $currentRepoSha256 at $repoArtifact); " +
          "refusing a build whose projects may have resolved different plugin bytes"
      )
    }
    val applicationProvenance = parameters.applicationRepoProvenance.get()
    val expectedProvenance = if (
      applicationProvenance == SavaBuildLocalRepoNoticePlugin.NO_LOCAL_PROVENANCE
    ) {
      null
    } else {
      try {
        SavaBuildLocalPublicationProvenance.parse(applicationProvenance)
      } catch (failure: IllegalArgumentException) {
        throw GradleException(
          "sava-build: cached local publication provenance is invalid: ${failure.message}",
          failure,
        )
      }
    }
    val provenanceFile = File(parameters.repoProvenancePath.get())
    if (expectedProvenance != null) {
      val expectedProvenanceSha256 = parameters.applicationRepoProvenanceSha256.get()
      val currentProvenanceSha256 = if (provenanceFile.isFile) {
        PitestEvidence.sha256(provenanceFile)
      } else {
        HardeningPluginIdentityService.MISSING_LOCAL_ARTIFACT
      }
      if (currentProvenanceSha256 != expectedProvenanceSha256) {
        throw GradleException(
          "sava-build: local publication provenance changed after plugin application " +
            "($expectedProvenanceSha256 -> $currentProvenanceSha256 at $provenanceFile); " +
            "refusing a build whose source identity may not describe the loaded plugin"
        )
      }
      val currentProvenance = readLocalPublicationProvenance(provenanceFile)
      if (currentProvenance != expectedProvenance ||
          currentProvenance.jarSha256 != currentRepoSha256) {
        throw GradleException(
          "sava-build: local publication provenance no longer describes the configured " +
            "local-repo artifact at $repoArtifact"
        )
      }
    }
    val publishState = expectedProvenance?.let { provenance ->
      val elapsed = System.currentTimeMillis() - provenance.publishedAtUtc.toEpochMilli()
      "published ${provenance.publishedAtUtc} (${age(maxOf(0L, elapsed))} ago); " +
        provenance.describeSourceSnapshotAtPublication()
    } ?: "NO $version PUBLISH FOUND THERE"
    Logging.getLogger(SavaBuildLocalRepoNotice::class.java).warn(
      "sava-build: this build resolved every 'software.sava.build*' plugin to $version from the local " +
        "repo $repo ($publishState; application-time SHA-256 $expectedSha256), NOT the versions in the " +
        "plugins block. The publish is manual: re-run " +
        "sava-build's '${SavaBuildLocalRepoNoticePlugin.PUBLISH_TASK}' after every edit, or unset " +
        "-P${SavaBuildLocalRepoNoticePlugin.LOCAL_REPO_PROPERTY} to go back to published resolution."
    )
  }

  private fun age(millis: Long): String {
    val minutes = millis / 60_000
    return when {
      minutes < 60 -> "$minutes min"
      minutes < 1440 -> "${minutes / 60} h ${minutes % 60} min"
      else -> "${minutes / 1440} d ${minutes % 1440 / 60} h"
    }
  }
}

private fun readLocalPublicationProvenance(
  file: File,
): SavaBuildLocalPublicationProvenance = try {
  SavaBuildLocalPublicationProvenance.read(file)
} catch (failure: Exception) {
  throw GradleException(
    "sava-build: local publication provenance is malformed at $file: ${failure.message}; " +
      "re-run sava-build's '${SavaBuildLocalRepoNoticePlugin.PUBLISH_TASK}'",
    failure,
  )
}
