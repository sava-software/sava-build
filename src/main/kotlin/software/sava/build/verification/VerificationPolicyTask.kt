package software.sava.build.verification

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Fails when a repository's dependency verification policy breaks the fleet's rules.
 *
 * `gradle/verification-metadata.xml` is a reviewed trust policy, and Gradle's
 * `--write-verification-metadata` output is only a candidate for it. A regeneration can widen
 * trust (it promotes artifact-level keys to group-wide rules), narrow it (a version attribute
 * or a per-artifact key that fails the next bump), write ignored-key fallbacks for keys it
 * could not find, replace a standalone build's symlinks with diverging copies, and it drops
 * XML comments. This task checks the policy's invariants, [VerificationPolicyRules], so none
 * of that can land unseen. It reads the checkout as it is, the committed files and the direct
 * child directories' settings scripts: a verification mode passed on the command line or set
 * in `~/.gradle` is beyond its reach. A repository with no policy, and no keyring, list or
 * declaration (strict in the root `gradle.properties`) of one, is left to the CI notice; any
 * of those without the file is a policy deleted or never committed, and fails.
 */
@UntrackedTask(because = "Checks committed policy files and produces nothing")
abstract class VerificationPolicyTask : DefaultTask() {

  /**
   * The settings directory, which Gradle reads the policy from and which holds the standalone
   * builds; the task discovers those when it runs, so nothing about them is stored.
   */
  @get:Internal
  abstract val rootDirectory: DirectoryProperty

  @TaskAction
  fun check() {
    val root = rootDirectory.get().asFile
    val problems = VerificationPolicyRules.problems(root, VerificationPolicyRules.standaloneBuilds(root))
    if (problems.isNotEmpty()) {
      throw GradleException(
        "Dependency verification policy problems (${VerificationPolicyRules.README} explains the rules):\n" +
          problems.joinToString("\n") { "  - $it" }
      )
    }
  }

  companion object {
    const val TASK_NAME = "verificationPolicy"
  }
}
