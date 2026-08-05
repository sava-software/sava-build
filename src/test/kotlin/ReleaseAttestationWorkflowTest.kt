import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseAttestationWorkflowTest {

  private val projectRoot = File(savaBuildTestProperty("savaBuild.root"))

  private fun workflow(name: String): String =
    projectRoot.resolve(".github/workflows/$name").readText()

  private val readme: String
    get() = projectRoot.resolve("README.md").readText()

  @Test
  fun `tag creation waits for the exact checked commit's release attestation`() {
    val release = workflow("release-gradle-plugin-please.yml")
    val gate = release.indexOf("tools/release-attestation.sh verify-pending-release")
    val tagCreation = release.indexOf("uses: ./.github/workflows/release-please.yml")

    assertTrue(release.contains("ref: \${{ github.event.workflow_run.head_sha }}"), release)
    assertTrue(release.contains("fetch-depth: 0"), release)
    assertTrue(release.contains("needs: release-attestation"), release)
    assertTrue(gate >= 0 && tagCreation > gate, release)
  }

  @Test
  fun `tag publication verifies the exact tag and certified artifact before publishing`() {
    val publish = workflow("gradle_plugin_publish.yml")
    val gate = publish.indexOf("tools/release-attestation.sh verify-tag \"\${GITHUB_REF_NAME}\"")
    val build = publish.indexOf("\n      - name: Check\n")
    val artifactGate = publish.indexOf(
      "tools/release-attestation.sh verify-built-jar \"\${GITHUB_REF_NAME}\" " +
          "\"build/libs/sava-build-\${GITHUB_REF_NAME}.jar\"",
    )
    val publication = publish.indexOf("\n      - name: Github Packages\n")

    assertTrue(publish.contains("fetch-depth: 0"), publish)
    assertTrue(publish.contains("--no-build-cache -Psign=true"), publish)
    assertTrue(
      gate >= 0 && build > gate && artifactGate > build && publication > artifactGate,
      publish,
    )
  }

  @Test
  fun `ordinary plugin checks exercise the attestation gate's self-test`() {
    listOf(
      "gradle_plugin_build.yml",
      "gradle_plugin_check_pr.yml",
      "gradle_plugin_publish.yml",
    ).forEach { name ->
      val contents = workflow(name)
      assertTrue(
        contents.contains("tools/release-attestation.sh --self-test"),
        "$name does not exercise the release attestation self-test:\n$contents",
      )
    }
  }

  @Test
  fun `release owner rehearses the production pending-release gate before tagging`() {
    val create = readme.indexOf("tools/release-attestation.sh create \"\$version\"")
    val verify = readme.indexOf("tools/release-attestation.sh verify \"\$version\"")
    val pending = readme.indexOf("tools/release-attestation.sh verify-pending-release")

    assertTrue(create >= 0 && verify > create && pending > verify, readme)
  }
}
