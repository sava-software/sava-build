package software.sava.build.verification

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers [VerificationPolicyTask] and [VerificationKeyStatusTask] on the root project, once per build; the settings plugin
 * applies it there and [VerificationPolicyCheckPlugin] everywhere. The root is the one project
 * every build has, and the policy is repository-wide, so the task needs no `base` plugin on the
 * root and nothing a module could apply twice.
 */
class VerificationPolicyRootPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    require(project == project.rootProject) {
      "VerificationPolicyRootPlugin can be applied only to the Gradle root project"
    }
    project.tasks.register(VerificationPolicyTask.TASK_NAME, VerificationPolicyTask::class.java) {
      group = "verification"
      description = "Fails when the dependency verification policy breaks the fleet's rules."
      // Gradle reads the policy from the settings directory, the root project's unless the
      // settings script relocates it.
      rootDirectory.set(project.layout.settingsDirectory)
    }
    // Network-bound and outside check: the release checklist and a policy edit run it by name.
    project.tasks.register(VerificationKeyStatusTask.TASK_NAME, VerificationKeyStatusTask::class.java) {
      group = "verification"
      description = "Asks the key servers whether a key the dependency verification policy trusts was revoked or has expired."
      rootDirectory.set(project.layout.settingsDirectory)
      servers.set(
        project.providers.gradleProperty("savaBuildKeyServers")
          .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
          .orElse(KeyStatus.DEFAULT_SERVERS)
      )
      corroborationSource.set(project.providers.gradleProperty("savaBuildCorroborationSource").orElse(KeyStatus.GRADLE_POLICY))
      receipt.set(project.layout.buildDirectory.file("reports/verification/key-status.tsv"))
    }
  }
}

/**
 * Makes a project's `check`, `qualityGate` and `image` depend on the root's
 * [VerificationPolicyTask], so the policy is guarded wherever CI runs `check`, as sava's
 * docs-in-sync rule is, and wherever a Dockerfile runs the jlink `image` task alone, where a
 * build context that lost the policy files would otherwise build an unverified image with no
 * word while CI stays green. The three are matched by name, so no other task is realized to
 * ask for its name.
 */
class VerificationPolicyCheckPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    project.tasks.named { it == "check" || it == "qualityGate" || it == "image" }.configureEach {
      dependsOn(":${VerificationPolicyTask.TASK_NAME}")
    }
  }
}
