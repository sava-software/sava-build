package software.sava.build.hardening

import org.gradle.api.Action
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FuzzSourceIdentityTest {

  @TempDir
  lateinit var projectDirectory: File

  @Test
  fun `unavailable Git still captures source content and permits unchanged local runs`() {
    val source = projectDirectory.resolve("Harness.java").apply { writeText("before") }
    val before = FuzzSourceIdentity.capture(projectDirectory, listOf(source), unavailableGit)
    val repeated = FuzzSourceIdentity.capture(projectDirectory, listOf(source), unavailableGit)

    assertEquals(CertificationGitIdentity.unavailable(), before.git)
    assertDoesNotThrow { FuzzSourceIdentity.requireUnchanged(before, repeated) }

    source.writeText("after")
    val after = FuzzSourceIdentity.capture(projectDirectory, listOf(source), unavailableGit)
    assertNotEquals(before.sourceSha256, after.sourceSha256)
    assertThrows(IllegalStateException::class.java) {
      FuzzSourceIdentity.requireUnchanged(before, after)
    }
  }

  @Test
  fun `changed dirty bytes cannot hide behind an unchanged Git status digest`() {
    val source = projectDirectory.resolve("Harness.java").apply { writeText("first dirty content") }
    val dirtyGit = CertificationGitIdentity(
      CertificationGitIdentity.State.DIRTY,
      "1".repeat(40),
      "2".repeat(40),
      "3".repeat(64),
      ".",
    )
    val before = FuzzSourceIdentity(PitestEvidence.fingerprint(projectDirectory, listOf(source)), dirtyGit)
    source.writeText("other dirty content")
    val after = FuzzSourceIdentity(PitestEvidence.fingerprint(projectDirectory, listOf(source)), dirtyGit)

    assertEquals(before.git, after.git)
    val failure = assertThrows(IllegalStateException::class.java) {
      FuzzSourceIdentity.requireUnchanged(before, after)
    }
    assertTrue(failure.message.orEmpty().contains("fuzz source identity changed"))
  }

  @Test
  fun `provenance changes are refused even when selected source bytes match`() {
    val before = FuzzSourceIdentity(
      "a".repeat(64),
      CertificationGitIdentity(
        CertificationGitIdentity.State.CLEAN,
        "1".repeat(40),
        "2".repeat(40),
        CertificationGitIdentity.EMPTY_STATUS_SHA256,
        ".",
      ),
    )
    val after = before.copy(git = before.git.copy(commit = "3".repeat(40)))
    assertThrows(IllegalStateException::class.java) {
      FuzzSourceIdentity.requireUnchanged(before, after)
    }
  }

  @Test
  fun `mutable logs outside selected source inventory do not change source identity`() {
    val source = projectDirectory.resolve("Harness.java").apply { writeText("source") }
    val before = FuzzSourceIdentity.capture(projectDirectory, listOf(source), unavailableGit)
    projectDirectory.resolve("jazzer.stderr.log").writeText("new diagnostic output")
    val after = FuzzSourceIdentity.capture(projectDirectory, listOf(source), unavailableGit)
    assertEquals(before, after)
  }

  private val unavailableGit = object : ExecOperations {
    override fun exec(action: Action<in ExecSpec>): ExecResult =
      throw UnsupportedOperationException("Git unavailable in this fixture")

    override fun javaexec(action: Action<in JavaExecSpec>): ExecResult =
      throw UnsupportedOperationException("No child processes in this fixture")
  }
}
