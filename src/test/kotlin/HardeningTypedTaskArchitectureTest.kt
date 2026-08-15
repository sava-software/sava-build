import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HardeningTypedTaskArchitectureTest {

  private val script = File(
    savaBuildTestProperty("savaBuild.root"),
    "src/main/kotlin/software.sava.build.feature.hardening.gradle.kts",
  ).readText()

  @Test
  fun `evidence revalidation follows the normal PIT task launcher`() {
    assertTrue(
      script.contains("task.javaLauncher.convention(defaultPitestJavaLauncher)"),
      "the consuming project toolchain should remain the PIT task convention",
    )
    assertTrue(
      script.contains(
        "spec.javaLauncher.set(evidencePitestTask.javaLauncher)"
      ),
      "typed evidence validators are not wired to the effective normal-task launcher",
    )
    assertTrue(
      script.contains("spec.toolClasspath.from(evidencePitestTask.effectiveToolClasspath)"),
      "typed evidence validators are not wired to the effective normal-task classpath",
    )
    assertTrue(
      script.contains("spec.verbosity.set(evidencePitestTask.verbosity)"),
      "typed evidence validators are not wired to late normal-task verbosity customization",
    )
    assertTrue(
      script.contains("classpath = evidencePitestTask.effectiveToolClasspath") &&
          script.contains("javaLauncher.set(evidencePitestTask.javaLauncher)") &&
          script.contains("verbosity.set(evidencePitestTask.verbosity)"),
      "diagnostic/convergence/trial tasks do not follow the normal task's supported process overrides",
    )
    val trialBlock = script
      .substringAfter("tasks.register<PitestMutatorTrialTask>(trialTaskName)")
      .substringBefore("pitestMutatorTrial.configure")
    assertTrue(
      trialBlock.contains("mirrorNormalPitestProcess()") &&
          trialBlock.contains("verbosity.set(evidencePitestTask.verbosity)"),
      "mutator trials are not pinned to the normal suite's supported process overrides",
    )
    assertFalse(
      script.contains("providers.provider { pitestRun.get().effectiveToolClasspath }"),
      "a revalidation surface reintroduced the provider-backed classpath that resolves at cache store",
    )
    assertFalse(
      script.contains("if (evidenceManifestFile.get().asFile.isFile)"),
      "a generated evidence manifest must not select the configuration-cache task graph",
    )
    assertTrue(
      script.contains("verify.configure { dependsOn(verifyEvidenceValidation) }"),
      "ordinary verification no longer has invariant execution-time evidence validation",
    )
    assertTrue(
      script.contains("pitestModeSnapshot.configure { dependsOn(modeSnapshotEvidenceValidation) }"),
      "mode snapshots no longer have invariant execution-time evidence validation",
    )
    assertTrue(
      script.contains("tasks.register<HardeningCertificationTask>(\"hardeningCertify\")"),
      "final certification freshness is no longer owned by the typed certification task",
    )
  }
}
