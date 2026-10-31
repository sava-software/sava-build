package software.sava.build.verification

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.time.Instant

/**
 * Asks the key servers what they hold today for every key the dependency verification policy
 * trusts, and fails when a key trusted for every version has been revoked. Gradle checks neither
 * revocation nor expiry, and the committed keyring cannot carry a revocation certificate, so
 * nothing else in a build can notice either; the policy's reviewer learns it here and answers
 * with the README's revocation case: exact versions for the releases signed before the date, a
 * line among the keys that must not vouch, and a rule for the publisher's new key.
 *
 * Network-bound, so outside `check`: the release checklist runs it beside `hardeningCertify`,
 * and a policy edit runs it beside `check resolveVerificationTools`. An expired key warns rather
 * than fails, since publishers do sign with expired keys and Gradle accepts those signatures; a
 * key no server holds warns, since the policy then authenticated it from a publisher's own file.
 * A key a server left undecided, by not answering or answering with something other than a key
 * or a 404, fails the task: a question not answered is not a pass.
 * The receipt is `build/reports/verification/key-status.tsv`.
 */
@UntrackedTask(because = "Queries key servers; their answer is not a function of any input")
abstract class VerificationKeyStatusTask : DefaultTask() {

  /** The settings directory, which holds the policy. */
  @get:Internal
  abstract val rootDirectory: DirectoryProperty

  /** Key server base URLs; the Gradle property `savaBuildKeyServers` (comma-separated) overrides the two defaults, for a test or a mirror. */
  @get:Internal
  abstract val servers: ListProperty<String>

  @get:OutputFile
  abstract val receipt: RegularFileProperty

  @TaskAction
  fun check() {
    val metadata = rootDirectory.get().asFile.resolve(VerificationPolicyRules.METADATA)
    if (!metadata.isFile) {
      logger.lifecycle("verificationKeyStatus: no ${VerificationPolicyRules.METADATA}, nothing to check")
      receipt.get().asFile.apply { parentFile.mkdirs(); writeText("") }
      return
    }
    if (servers.get().isEmpty()) throw GradleException("savaBuildKeyServers names no key server; leave the property unset for the two defaults")
    val now = Instant.now()
    val keys = KeyStatus.trustedKeys(metadata)
    val findings = KeyStatus.findings(keys, servers.get(), KeyStatus.HttpFetcher(), now)
    receipt.get().asFile.apply { parentFile.mkdirs(); writeText(KeyStatus.receipt(findings, now)) }
    val failing = findings.filter { it.fails }
    val warning = findings.filter { it.warns }
    logger.lifecycle(
      "verificationKeyStatus: ${findings.size} trusted key(s) checked on ${servers.get().joinToString(" and ")}: " +
        "${findings.count { it.state == KeyStatus.State.CURRENT }} current, ${findings.count { it.state == KeyStatus.State.EXPIRED }} expired, " +
        "${findings.count { it.state == KeyStatus.State.REVOKED }} revoked, ${findings.count { it.state == KeyStatus.State.ABSENT }} on no server, " +
        "${findings.count { it.state == KeyStatus.State.UNDECIDED }} undecided; receipt ${receipt.get().asFile}"
    )
    warning.forEach { logger.warn("verificationKeyStatus: ${KeyStatus.describe(it)}") }
    if (failing.isNotEmpty()) {
      val revoked = failing.filter { it.state == KeyStatus.State.REVOKED }
      val undecided = failing.filter { it.state == KeyStatus.State.UNDECIDED }
      throw GradleException(
        listOfNotNull(
          revoked.takeIf { it.isNotEmpty() }?.let {
            "Revoked key(s) trusted for every version (${VerificationPolicyRules.README} Key status, says what to do):\n" + it.joinToString("\n") { f -> "  - ${KeyStatus.describe(f)}" }
          },
          undecided.takeIf { it.isNotEmpty() }?.let {
            "Key(s) whose status a key server left undecided (run again when the servers answer; -PsavaBuildKeyServers names others):\n" + it.joinToString("\n") { f -> "  - ${KeyStatus.describe(f)}" }
          },
        ).joinToString("\n")
      )
    }
  }

  companion object {
    const val TASK_NAME = "verificationKeyStatus"
  }
}
