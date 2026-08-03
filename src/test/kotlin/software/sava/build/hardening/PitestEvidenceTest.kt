package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PitestEvidenceTest {

  @TempDir
  lateinit var tempDir: File

  private fun evidence() = PitestEvidence(
      suite = "encoding",
      invocationId = "run-123",
      pitestVersion = "1.25.8",
      junitPluginVersion = "1.2.3",
      pluginSha256 = "plugin",
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = "25",
      sourceSha256 = "source",
      classesSha256 = "classes",
      classpathSha256 = "classpath",
      toolClasspathSha256 = "tool-classpath",
      configurationSha256 = "config",
      reportSha256 = "report",
      scope = PitestEvidence.FULL_SCOPE,
      historyAssisted = false,
  )

  @Test
  fun `manifest round trips deterministically`() {
    val first = evidence().render()
    val parsed = PitestEvidence.parse(first)

    assertEquals(evidence(), parsed)
    assertEquals(first, parsed.render())
  }

  @Test
  fun `manifest refuses missing duplicate unknown and future fields`() {
    val rendered = evidence().render()
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered.lineSequence().filterNot { it.startsWith("reportSha256\t") }.joinToString("\n"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered + "suite\tagain\n")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered + "surprise\tvalue\n")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered.replace("schema\t2", "schema\t3"))
    }
  }

  @Test
  fun `file fingerprint is path and content stable but change sensitive`() {
    val a = File(tempDir, "src/A.java").also { it.parentFile.mkdirs(); it.writeText("class A {}") }
    val b = File(tempDir, "src/B.java").also { it.writeText("class B {}") }
    val first = PitestEvidence.fingerprint(tempDir, listOf(a, b))

    assertEquals(first, PitestEvidence.fingerprint(tempDir, listOf(b, a)), "input order must not matter")
    b.writeText("class B { int n; }")
    assertTrue(first != PitestEvidence.fingerprint(tempDir, listOf(a, b)), "content change was invisible")
    b.writeText("class B {}")
    val moved = File(tempDir, "other/B.java").also { it.parentFile.mkdirs(); b.copyTo(it) }
    assertTrue(first != PitestEvidence.fingerprint(tempDir, listOf(a, moved)), "path change was invisible")
  }

  @Test
  fun `differences name every mismatched provenance field`() {
    val expected = evidence().copy(sourceSha256 = "new-source", scope = "com.example.Codec")
    val differences = evidence().differences(expected)

    assertTrue(differences.any { it.startsWith("sourceSha256:") }, differences.toString())
    assertTrue(differences.any { it.startsWith("scope:") }, differences.toString())
  }
}
