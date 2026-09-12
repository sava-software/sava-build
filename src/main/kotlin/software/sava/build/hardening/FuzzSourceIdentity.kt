package software.sava.build.hardening

import org.gradle.process.ExecOperations
import java.io.File

/**
 * Target-boundary observation of the selected source files and checkout provenance.
 * The source digest covers file names and bytes, including dirty local content;
 * the Git status digest describes status only. Neither is a complete build-input
 * snapshot or a claim that source files could not change between observations.
 */
internal data class FuzzSourceIdentity(
  val sourceSha256: String,
  val git: CertificationGitIdentity,
) {
  init {
    require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) {
      "fuzz source fingerprint is not SHA-256"
    }
  }

  companion object {
    fun capture(
      projectDirectory: File,
      sourceFiles: Iterable<File>,
      execOperations: ExecOperations,
    ): FuzzSourceIdentity {
      val gitBefore = CertificationGitIdentityCapture.capture(projectDirectory, execOperations)
      val sourceSha256 = PitestEvidence.fingerprint(projectDirectory, sourceFiles)
      val gitAfter = CertificationGitIdentityCapture.capture(projectDirectory, execOperations)
      check(gitBefore == gitAfter) {
        "fuzz source Git provenance changed while capturing source identity"
      }
      return FuzzSourceIdentity(sourceSha256, gitAfter)
    }

    fun requireUnchanged(before: FuzzSourceIdentity, after: FuzzSourceIdentity) {
      check(before == after) {
        "fuzz source identity changed during target execution; refusing a mixed-source receipt"
      }
    }
  }
}
