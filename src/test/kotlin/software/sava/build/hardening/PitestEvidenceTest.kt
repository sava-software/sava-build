package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.task.HardeningCertificationTask
import java.io.File
import java.nio.file.Files

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
      mutationToolchainSha256 = "mutation-toolchain",
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
      PitestEvidence.parse(rendered.replace("schema\t3", "schema\t4"))
    }
  }

  @Test
  fun `N-1 evidence parses as legacy toolchain identity and must be refreshed`() {
    val schema2 = evidence().render()
      .replace("schema\t3", "schema\t2")
      .lineSequence()
      .filterNot { it.startsWith("mutationToolchainSha256\t") }
      .joinToString("\n", postfix = "\n")

    val parsed = PitestEvidence.parse(schema2)

    assertEquals(PitestEvidence.LEGACY_MUTATION_TOOLCHAIN, parsed.mutationToolchainSha256)
    assertTrue(
      parsed.differences(evidence()).any { it.startsWith("mutationToolchainSha256:") },
      parsed.differences(evidence()).toString(),
    )
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

  @Test
  fun `input identity ignores observation ids but binds every stable input`() {
    val identity = evidence().inputIdentitySha256()

    assertEquals(
      identity,
      evidence().copy(invocationId = "another-run", reportSha256 = "another-report")
        .inputIdentitySha256(),
    )
    assertTrue(identity != evidence().copy(pluginSha256 = "changed-plugin").inputIdentitySha256())
    assertTrue(identity != evidence().copy(sourceSha256 = "changed-source").inputIdentitySha256())
    assertTrue(identity != evidence().copy(configurationSha256 = "changed-config").inputIdentitySha256())
    assertTrue(identity != evidence().copy(mutationToolchainSha256 = "changed-toolchain").inputIdentitySha256())
    assertTrue(identity != evidence().copy(scope = "com.example.Codec").inputIdentitySha256())
  }

  @Test
  fun `certification project evidence treats the Java runtime as project-wide`() {
    val java25 = HardeningCertificationTask.ProjectEvidence.from(evidence())
    val java21 = HardeningCertificationTask.ProjectEvidence.from(
      evidence().copy(javaVersion = "21"),
    )

    assertEquals(listOf("javaVersion"), java25.differences(java21))
  }

  @Test
  fun `certification projects must share one application-time plugin identity`() {
    val identities = CertificationPluginIdentities()
    identities.requireExpected(":legacy", "a".repeat(64))
    identities.register(":core", "a".repeat(64))
    identities.register(":core", "a".repeat(64))
    identities.register(":http", "a".repeat(64))

    val conflict = assertThrows(IllegalStateException::class.java) {
      identities.register(":google", "b".repeat(64))
    }

    assertTrue(conflict.message.orEmpty().contains(":google"), conflict.message)
    assertTrue(conflict.message.orEmpty().contains(":legacy"), conflict.message)
  }

  @Test
  fun `mutation record fingerprint refuses a linked config tree`() {
    val project = tempDir.resolve("project").apply { mkdirs() }
    val external = tempDir.resolve("external/pitest").apply { mkdirs() }
    external.resolve("encoding-accepted.csv").writeText("accepted outside checkout\n")
    Files.createSymbolicLink(project.resolve("config").toPath(), external.parentFile.toPath())

    val failure = assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.mutationRecordFingerprint(
          project, project.resolve("config/pitest"), "encoding")
    }

    assertTrue(failure.message.orEmpty().contains("symbolic-link component"), failure.message)
  }
}
