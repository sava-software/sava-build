package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningCertificationAggregateManifestTest {

  @TempDir
  lateinit var temporaryDirectory: File

  @Test
  fun `manifest renders the exact project and suite inventory canonically`() {
    val root = temporaryDirectory.resolve("gradle-root").apply { mkdirs() }
    val manifest = HardeningCertificationAggregateManifest(
      SESSION_ID,
      normalizedPath(root),
      PLUGIN_SHA,
      listOf(
        CertificationAggregateProjectReceipt(
          ":alpha",
          "root:alpha/.pitest-history/pitest-certification.tsv",
          "a".repeat(64),
          listOf("accounts", "rpc"),
        ),
        CertificationAggregateProjectReceipt(
          ":zeta",
          "root:zeta/.pitest-history/pitest-certification.tsv",
          "b".repeat(64),
          listOf("server"),
        ),
      ),
    )

    assertEquals(2, manifest.projects.size)
    assertEquals(3, manifest.suiteCount)
    assertEquals(
      """
        schema	1
        scope	current-gradle-root
        rootProjectPath	:
        gradleRootDirectory	${normalizedPath(root)}
        session	$SESSION_ID
        mode	fresh-full-strict
        pluginSha256	$PLUGIN_SHA
        projectCount	2
        suiteCount	3
        projectColumns	projectPath	receiptPath	receiptSha256	suiteCount
        project	:alpha	root:alpha/.pitest-history/pitest-certification.tsv	${"a".repeat(64)}	2
        project	:zeta	root:zeta/.pitest-history/pitest-certification.tsv	${"b".repeat(64)}	1
        suiteColumns	projectPath	suite
        suite	:alpha	accounts
        suite	:alpha	rpc
        suite	:zeta	server
      """.trimIndent() + "\n",
      manifest.render(),
    )
  }

  @Test
  fun `manifest construction rejects ambiguous inventory and paths`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val beta = CertificationAggregateProjectReceipt(
      ":beta",
      "root:beta/receipt.tsv",
      "b".repeat(64),
      listOf("second"),
    )
    val alpha = CertificationAggregateProjectReceipt(
      ":alpha",
      "root:alpha/receipt.tsv",
      "a".repeat(64),
      listOf("first"),
    )

    assertThrows(IllegalArgumentException::class.java) {
      HardeningCertificationAggregateManifest(
        SESSION_ID,
        normalizedPath(root),
        PLUGIN_SHA,
        listOf(beta, alpha),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      CertificationAggregateProjectReceipt(
        ":bad",
        "root:child/../receipt.tsv",
        "c".repeat(64),
        listOf("suite"),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      CertificationAggregateProjectReceipt(
        ":bad",
        "absolute:relative/receipt.tsv",
        "c".repeat(64),
        listOf("suite"),
      )
    }
  }

  private fun normalizedPath(file: File): String =
    file.toPath().toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')

  private companion object {
    const val SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
    val PLUGIN_SHA = "f".repeat(64)
  }
}
