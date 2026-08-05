package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CertificationGitIdentityTest {

  @Test
  fun `final validation refuses Git state or revision changes`() {
    val clean = identity(CertificationGitIdentity.State.CLEAN)
    assertDoesNotThrow { CertificationGitIdentity.requireUnchanged(clean, clean) }

    assertThrows(IllegalStateException::class.java) {
      CertificationGitIdentity.requireUnchanged(
        clean,
        identity(
          CertificationGitIdentity.State.DIRTY,
          statusSha256 = "1".repeat(64),
        ),
      )
    }
    assertThrows(IllegalStateException::class.java) {
      CertificationGitIdentity.requireUnchanged(
        clean,
        identity(
          CertificationGitIdentity.State.CLEAN,
          commit = "2".repeat(40),
          tree = "3".repeat(40),
        ),
      )
    }
  }

  private fun identity(
    state: CertificationGitIdentity.State,
    commit: String = "0".repeat(40),
    tree: String = "1".repeat(40),
    statusSha256: String = CertificationGitIdentity.EMPTY_STATUS_SHA256,
  ) = CertificationGitIdentity(
    state = state,
    commit = commit,
    tree = tree,
    statusSha256 = statusSha256,
    projectDirectory = ".",
  )
}
