package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningPluginIdentityTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `guard binds both loaded code and the mutable local repository artifact`() {
    val code = tempDir.resolve("cache/sava-build.jar").apply {
      parentFile.mkdirs()
      writeText("loaded bytes")
    }
    val localArtifact = tempDir.resolve("repo/sava-build.jar").apply {
      parentFile.mkdirs()
      writeText("loaded bytes")
    }
    val expected = PitestEvidence.sha256(code)
    assertDoesNotThrow {
      HardeningPluginIdentityGuard.requireUnchanged(
        code, expected, localArtifact.absolutePath, expected, "fixture",
      )
    }

    localArtifact.appendText("republished")
    val changedRepository = assertThrows(IllegalStateException::class.java) {
      HardeningPluginIdentityGuard.requireUnchanged(
        code, expected, localArtifact.absolutePath, expected, "fixture",
      )
    }
    assertTrue(
      changedRepository.message.orEmpty().contains("local plugin artifact changed"),
      changedRepository.message,
    )

    code.appendText("reloaded")
    val changedCode = assertThrows(IllegalStateException::class.java) {
      HardeningPluginIdentityGuard.requireUnchanged(
        code,
        expected,
        HardeningPluginIdentityService.NO_LOCAL_ARTIFACT,
        HardeningPluginIdentityService.NO_LOCAL_ARTIFACT,
        "fixture",
      )
    }
    assertTrue(
      changedCode.message.orEmpty().contains("hardening plugin code changed"),
      changedCode.message,
    )
  }
}
