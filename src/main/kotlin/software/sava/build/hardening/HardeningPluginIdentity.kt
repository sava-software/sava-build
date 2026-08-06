package software.sava.build.hardening

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

/**
 * Identity of the plugin bytes which applied the hardening convention.
 *
 * The digest is captured while the plugin is being applied. Keeping the path as well
 * lets execution-time boundaries prove that those bytes were not replaced after the
 * application-time identity had been chosen.
 */
internal data class HardeningPluginIdentity(
  val codePath: File,
  val sha256: String,
) {
  companion object {

    fun capture(owner: Class<*>): HardeningPluginIdentity {
      val location = owner.protectionDomain.codeSource?.location
        ?: error("cannot locate hardening plugin code for ${owner.name}")
      val codePath = File(location.toURI()).absoluteFile.normalize()
      return HardeningPluginIdentity(codePath, PitestEvidence.fingerprintTree(codePath))
    }
  }
}

/** Earliest plugin identity shared by settings and every project in one Gradle build. */
internal abstract class HardeningPluginIdentityService :
  BuildService<HardeningPluginIdentityService.Parameters> {

  interface Parameters : BuildServiceParameters {
    val applicationPluginSha256: Property<String>
    val localRepoArtifactPath: Property<String>
    val applicationLocalRepoArtifactSha256: Property<String>
  }

  companion object {
    const val SERVICE_NAME: String = "savaBuildHardeningPluginIdentity"
    const val NO_LOCAL_ARTIFACT: String = "none"
    const val MISSING_LOCAL_ARTIFACT: String = "missing"
  }
}

/** Shared task-boundary validation for loaded code and the mutable static local artifact. */
internal object HardeningPluginIdentityGuard {

  fun requireUnchanged(
    codePath: File,
    expectedPluginSha256: String,
    localRepoArtifactPath: String,
    expectedLocalRepoArtifactSha256: String,
    context: String,
  ) {
    val currentPluginSha256 = PitestEvidence.fingerprintTree(codePath)
    check(currentPluginSha256 == expectedPluginSha256) {
      "$context: hardening plugin code changed after plugin application " +
        "($expectedPluginSha256 -> $currentPluginSha256 at $codePath)"
    }
    if (localRepoArtifactPath == HardeningPluginIdentityService.NO_LOCAL_ARTIFACT) return
    val localArtifact = File(localRepoArtifactPath)
    val currentLocalSha256 = if (localArtifact.isFile) {
      PitestEvidence.sha256(localArtifact)
    } else {
      HardeningPluginIdentityService.MISSING_LOCAL_ARTIFACT
    }
    check(currentLocalSha256 == expectedLocalRepoArtifactSha256) {
      "$context: local plugin artifact changed after plugin application " +
        "($expectedLocalRepoArtifactSha256 -> $currentLocalSha256 at $localArtifact)"
    }
  }
}

/** Mutable registry kept behind the certification build service's synchronization. */
internal class CertificationPluginIdentities {
  private val byProject = linkedMapOf<String, String>()

  fun register(projectPath: String, sha256: String) {
    byProject[projectPath]?.let { previous ->
      check(previous == sha256) {
        "certification project '$projectPath' changed hardening plugin identity " +
          "after preflight ($previous -> $sha256)"
      }
      return
    }
    val conflict = byProject.entries.firstOrNull { it.value != sha256 }
    check(conflict == null) {
      "certification projects do not share one hardening plugin identity: " +
        "'$projectPath' uses $sha256 while '${conflict!!.key}' uses ${conflict.value}"
    }
    byProject[projectPath] = sha256
  }

  fun requireExpected(projectPath: String, sha256: String) {
    // Older external wiring may still call the one-argument certification-session
    // activation API. Bind it at the first finalization boundary instead of turning
    // an additive provenance check into a source-compatible but behavioral break.
    if (projectPath !in byProject) {
      register(projectPath, sha256)
      return
    }
    check(byProject[projectPath] == sha256) {
      "certification project '$projectPath' did not retain its application-time " +
        "hardening plugin identity $sha256"
    }
  }
}
