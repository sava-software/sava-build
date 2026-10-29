package software.sava.build.verification

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers [VerificationPolicyTask] on the root project, once per build; the settings plugin
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
  }
}

/**
 * Makes a project's `check` and `qualityGate` depend on the root's [VerificationPolicyTask],
 * so the policy is guarded wherever CI runs `check`, as sava's docs-in-sync rule is. The two
 * are matched by name, so no other task is realized to ask for its name.
 */
class VerificationPolicyCheckPlugin : Plugin<Project> {

  override fun apply(project: Project) {
    project.tasks.named { it == "check" || it == "qualityGate" }.configureEach {
      dependsOn(":${VerificationPolicyTask.TASK_NAME}")
    }
  }
}
