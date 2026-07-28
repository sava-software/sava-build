import org.gradle.api.Plugin
import org.gradle.api.flow.FlowAction
import org.gradle.api.flow.FlowParameters
import org.gradle.api.flow.FlowScope
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
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
 * every build, and it reads the publish timestamp when it runs rather than reporting a
 * value captured at configuration time.
 */
abstract class SavaBuildLocalRepoNoticePlugin @Inject constructor(
  private val flowScope: FlowScope
) : Plugin<Settings> {

  override fun apply(settings: Settings) {
    val localRepo = settings.providers.gradleProperty(LOCAL_REPO_PROPERTY)
      .orNull?.takeIf { it.isNotBlank() } ?: return
    // Resolved here, against the consumer's settings dir, so the action reports the
    // same directory the 'pluginManagement' repository declaration resolves a relative
    // property value to.
    val repoDir = settings.settingsDir.resolve(localRepo).absolutePath
    flowScope.always(SavaBuildLocalRepoNotice::class.java) {
      parameters.localRepo.set(repoDir)
    }
  }

  companion object {

    const val LOCAL_REPO_PROPERTY: String = "savaBuildLocalRepo"

    const val TEST_VERSION: String = "0.0.0-test"

    const val PUBLISH_TASK: String = "publishSavaBuildTestPublicationToSavaTestRepoRepository"
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
  }

  override fun execute(parameters: Parameters) {
    val repo = parameters.localRepo.get()
    val version = SavaBuildLocalRepoNoticePlugin.TEST_VERSION
    // Read now, not at configuration time: on a configuration cache hit the settings
    // script never runs, so a timestamp captured there would be as old as the entry.
    val metadata = File(repo, "software/sava/sava-build/maven-metadata.xml")
    val publishState = if (metadata.isFile) {
      "last publish ${age(System.currentTimeMillis() - metadata.lastModified())} ago"
    } else {
      "NO $version PUBLISH FOUND THERE"
    }
    Logging.getLogger(SavaBuildLocalRepoNotice::class.java).warn(
      "sava-build: this build resolved every 'software.sava.build*' plugin to $version from the local " +
        "repo $repo ($publishState), NOT the versions in the plugins block. The publish is manual: re-run " +
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
