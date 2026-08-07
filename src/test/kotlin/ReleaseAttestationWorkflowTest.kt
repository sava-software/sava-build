import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseAttestationWorkflowTest {

  private val projectRoot = File(savaBuildTestProperty("savaBuild.root"))

  private fun workflow(name: String): String =
    projectRoot.resolve(".github/workflows/$name").readText()

  private val readme: String
    get() = projectRoot.resolve("README.md").readText()

  private val attestationScript: String
    get() = projectRoot.resolve("tools/release-attestation.sh").readText()

  @Test
  fun `tag creation verifies the actual Release Please target's attestation`() {
    val release = workflow("release-gradle-plugin-please.yml")
    val gate = release.indexOf("tools/release-attestation.sh verify-pending-release")
    val tagCreation = release.indexOf("uses: ./.github/workflows/release-please.yml")

    assertTrue(release.contains("ref: \${{ github.event.workflow_run.head_sha }}"), release)
    assertTrue(release.contains("fetch-depth: 0"), release)
    assertTrue(release.contains("needs: release-attestation"), release)
    assertTrue(gate >= 0 && tagCreation > gate, release)
    assertTrue(
      attestationScript.contains(
        "rev-list HEAD -- \\\n    .release-please-manifest.json",
      ),
      attestationScript,
    )
    assertTrue(
      attestationScript.contains("ls-tree \"\$release_commit\" -- \"\$relative\""),
      attestationScript,
    )
    assertTrue(
      attestationScript.contains("target_blob") && attestationScript.contains("head_blob"),
      attestationScript,
    )
    assertTrue(
      attestationScript.contains("if [ \"\$release_commit\" != \"\$head\" ]"),
      attestationScript,
    )
    assertTrue(
      attestationScript.contains(
        "verify_attestation \"\$version\" || return 1\n  verify_release_target \"\$version\"",
      ),
      attestationScript,
    )
    assertTrue(
      attestationScript.contains("[ \"\$release_parent\" != \"\$candidate\" ]"),
      attestationScript,
    )
  }

  @Test
  fun `Release Please pull requests require the reviewed record before merge`() {
    val check = workflow("gradle_plugin_check_pr.yml")

    assertTrue(check.contains("name: Release Attestation"), check)
    assertTrue(check.contains("contents: read"), check)
    assertTrue(check.contains("fetch-depth: 0"), check)
    assertTrue(check.contains("github.event.pull_request.base.sha"), check)
    assertTrue(check.contains("base=\"\${{ github.event.pull_request.base.sha }}\""), check)
    assertTrue(check.contains("base_version="), check)
    assertTrue(check.contains("if [ \"\$version\" = \"\$base_version\" ]"), check)
    assertTrue(check.contains("relative=\"release-attestations/\$version.json\""), check)
    listOf("seen_changelog", "seen_manifest", "seen_attestation").forEach { required ->
      assertTrue(check.contains(required), check)
    }
    assertTrue(check.contains("candidate=\$(jq -er '.candidate.commit"), check)
    assertTrue(check.contains("if [ \"\$candidate\" != \"\$base\" ]"), check)
    assertTrue(check.contains("Unexpected Release Please PR path"), check)
    assertTrue(
      check.contains("tools/release-attestation.sh verify \"\$version\""),
      check,
    )
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
  fun `release owner classifies compatibility and feature evidence before merge`() {
    val create = readme.indexOf("tools/release-attestation.sh create-reviewed \"\$version\"")
    val verify = readme.indexOf("tools/release-attestation.sh verify \"\$version\"")

    assertTrue(readme.contains("--candidate \"\$candidate\""), readme)
    assertTrue(readme.contains("--plugin-jar \"\$reviewed_jar\""), readme)
    assertTrue(readme.contains("--review-basis consumer-feature"), readme)
    assertTrue(readme.contains("--certification-only-adoption \"\$idl_src_gen_checkout\""), readme)
    assertTrue(readme.contains("--feature-adoption \"\$ravina_checkout\""), readme)
    assertTrue(readme.contains("never count every certified repository"), readme)
    assertTrue(readme.contains("post-merge workflow gate"), readme)
    assertTrue(create >= 0 && verify > create, readme)
  }

  @Test
  fun `release workflows verify the owner record without rerunning aggregate campaigns`() {
    listOf("release-gradle-plugin-please.yml", "gradle_plugin_publish.yml").forEach { name ->
      val contents = workflow(name)
      assertTrue(!contents.contains("local-fuzz.sh --release"), contents)
    }
  }
}
