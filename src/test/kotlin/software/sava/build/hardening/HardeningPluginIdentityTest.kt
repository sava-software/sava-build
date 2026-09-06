package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningPluginIdentityTest {

  @TempDir
  lateinit var tempDir: File

  @Test
  fun `local artifact status requires the loaded bytes rather than property-shaped paths`() {
    val loadedCode = tempDir.resolve("cache/sava-build.jar").apply {
      parentFile.mkdirs()
      writeText("published bytes")
    }
    val sameBytes = tempDir.resolve("local/software/sava/sava-build/0.0.0-test/sava-build-0.0.0-test.jar")
      .apply {
        parentFile.mkdirs()
        writeText("published bytes")
      }
    val differentBytes = tempDir.resolve("other/sava-build-0.0.0-test.jar").apply {
      parentFile.mkdirs()
      writeText("candidate bytes")
    }
    val identity = HardeningPluginIdentity(loadedCode, PitestEvidence.sha256(loadedCode))

    assertTrue(identity.matchesLoadedArtifact(sameBytes))
    assertFalse(identity.matchesLoadedArtifact(differentBytes))
    assertFalse(identity.matchesLoadedArtifact(tempDir.resolve("missing.jar")))
  }

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
