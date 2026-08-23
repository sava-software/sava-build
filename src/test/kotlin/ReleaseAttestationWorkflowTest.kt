import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseAttestationWorkflowTest {

  private val projectRoot = File(savaBuildTestProperty("savaBuild.root"))

  private fun workflow(name: String): String =
    projectRoot.resolve(".github/workflows/$name").readText()

  private val readme: String
    get() = projectRoot.resolve("README.md").readText()

  private val releasePleaseConfig: String
    get() = projectRoot.resolve("release-please-config.json").readText()

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
  fun `ready Release Please pull requests require the reviewed record before merge`() {
    val check = workflow("gradle_plugin_check_pr.yml")
    val checkout = check.indexOf("uses: actions/checkout@")
    val attestationPath = check.indexOf("relative=\"release-attestations/\$version.json\"")
    val verification = check.indexOf("tools/release-attestation.sh verify \"\$version\"")

    assertTrue(releasePleaseConfig.contains("\"draft-pull-request\": true"), releasePleaseConfig)
    assertTrue(check.contains("branches: [ main ]"), check)
    assertTrue(
      check.contains("types: [ opened, synchronize, reopened, ready_for_review ]"),
      check,
    )
    assertTrue(check.contains("contents: read"), check)
    assertTrue(check.contains("fetch-depth: 0"), check)
    assertTrue(check.contains("github.event.pull_request.base.sha"), check)
    assertTrue(check.contains("base=\"\${{ github.event.pull_request.base.sha }}\""), check)
    assertTrue(check.contains("base_version="), check)
    assertTrue(check.contains("if [ \"\$version\" = \"\$base_version\" ]"), check)
    assertTrue(
      check.contains(
        "- name: Await ready-for-review\n" +
            "        if: \${{ github.event.pull_request.draft }}",
      ),
      check,
    )
    assertTrue(checkout >= 0 && attestationPath > checkout, check)
    listOf("seen_changelog", "seen_manifest", "seen_attestation").forEach { required ->
      assertTrue(check.contains(required), check)
    }
    assertTrue(check.contains("candidate=\$(jq -er '.candidate.commit"), check)
    assertTrue(check.contains("if [ \"\$candidate\" != \"\$base\" ]"), check)
    assertTrue(check.contains("Unexpected Release Please PR path"), check)
    assertTrue(verification > attestationPath, check)
  }

  @Test
  fun `Release Please bot refreshes stage without satisfying the required context`() {
    val check = workflow("gradle_plugin_check_pr.yml")
    val jobStart = check.indexOf("  release-attestation:")
    val runsOn = check.indexOf("    runs-on:", jobStart)
    val jobHeader = check.substring(jobStart, runsOn)
    val steps = check.indexOf("    steps:", runsOn)
    val jobBlock = check.substring(jobStart, check.indexOf("\n  check:", steps))
    val environment = check.substring(runsOn, steps)
    val stagingStep = check.indexOf(
      "- name: Await reviewed attestation after Release Please refresh",
    )
    val checkout = check.indexOf("uses: actions/checkout@")
    val verification = check.indexOf(
      "- name: Verify reviewed release attestation in proposed release tree",
    )
    val stagingPredicate = Regex(
      """github\.event\.pull_request\.draft == false\s*&&\s*""" +
          """github\.event\.action == 'synchronize'\s*&&\s*""" +
          """github\.actor == 'sava-release-please\[bot]'\s*&&\s*""" +
          """github\.event\.pull_request\.user\.login == 'sava-release-please\[bot]'""",
    )
    val stagingName = Regex(
      stagingPredicate.pattern +
          """\)\s*&&\s*'Release Attestation \(staging\)'""",
    )
    val stagingEnvironment = Regex(
      stagingPredicate.pattern + """\)\s*&&\s*'true'\s*\|\|\s*'false'""",
    )
    val verificationGuard =
      "if: \${{ github.event.pull_request.draft == false && " +
          "env.RELEASE_PLEASE_BOT_REFRESH != 'true' }}"

    assertTrue(jobStart >= 0 && runsOn > jobStart, check)
    assertTrue(jobHeader.contains("'Release Attestation (staging)'"), jobHeader)
    assertTrue(jobHeader.contains("'Release Attestation (draft)'"), jobHeader)
    assertTrue(jobHeader.contains("'Release Attestation'"), jobHeader)
    assertTrue(!Regex("(?m)^    if:").containsMatchIn(jobBlock), jobBlock)
    assertTrue(!check.contains("\n    name: Release Attestation\n"), check)
    assertTrue(stagingPredicate.findAll(check).count() == 2, check)
    assertTrue(stagingName.containsMatchIn(jobHeader), jobHeader)
    assertTrue(environment.contains("RELEASE_PLEASE_BOT_REFRESH: >-"), environment)
    assertTrue(stagingEnvironment.containsMatchIn(environment), environment)
    assertTrue(
      check.contains(
        "- name: Await reviewed attestation after Release Please refresh\n" +
            "        if: \${{ env.RELEASE_PLEASE_BOT_REFRESH == 'true' }}",
      ),
      check,
    )
    assertTrue(check.split(verificationGuard).size - 1 == 2, check)
    assertTrue(
      check.contains("- $verificationGuard\n        uses: actions/checkout@"),
      check,
    )
    assertTrue(
      check.contains(
        "- name: Verify reviewed release attestation in proposed release tree\n" +
            "        $verificationGuard",
      ),
      check,
    )
    assertTrue(stagingStep >= 0 && checkout > stagingStep && verification > checkout, check)
    assertTrue(
      check.contains("does not satisfy the required Release Attestation context"),
      check,
    )
    assertTrue(readme.contains("Release Attestation (staging)"), readme)
    assertTrue(readme.contains("does not verify the tree"), readme)
    assertTrue(readme.contains("mark it ready afterward"), readme)
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
