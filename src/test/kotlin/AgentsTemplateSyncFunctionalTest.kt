import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Functional test for 'agentsTemplateInSync': fixture repos with a missing,
 * marker-less, stale, and current AGENTS.md exercise the acknowledgment check without
 * resolving any tool dependencies. The expected digest is recomputed here from
 * HARDENING.md with the generator's own algorithm, so drift on either side of the
 * generator/task contract fails this test before it fails every consuming repo.
 */
class AgentsTemplateSyncFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  // Mirrors 'generateHardeningTemplateDigest' in sava-build's build.gradle.kts: only
  // the '>' blockquote lines of the template section are hashed, trailing whitespace
  // stripped, first 12 hex chars of the SHA-256.
  private val expectedTemplate: String = run {
    val lines = File(savaBuildTestProperty("savaBuild.root"), "HARDENING.md").readLines()
    val start = lines.indexOfFirst { it.trim() == "## Agent instructions template" }
    check(start >= 0) { "HARDENING.md has no '## Agent instructions template' section" }
    lines.drop(start + 1)
      .takeWhile { !it.startsWith("## ") }
      .filter { it.startsWith(">") }
      .joinToString("\n") { it.trimEnd() }
  }
  private val expectedDigest: String = run {
    MessageDigest.getInstance("SHA-256")
      .digest(expectedTemplate.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
      .take(12)
  }

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "agents-template-sync-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--stacktrace")

  @Test
  fun `missing, marker-less, and stale AGENTS_md warn, fail, and fail naming both digests`() {
    writeFixture()

    // no AGENTS.md: the adoption checklist owns creating the file, so this warns with
    // the marker to add instead of failing
    val missing = runner("agentsTemplateInSync").build()
    assertTrue(missing.output.contains("no AGENTS.md"), missing.output)
    assertTrue(missing.output.contains("hardeningAgentTemplate"), missing.output)
    assertTrue(
      missing.output.contains("<!-- hardening-template sha256:$expectedDigest -->"),
      "the warning must hand over the exact marker line:\n" + missing.output
    )

    // an AGENTS.md that never acknowledged the template
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    agentsDoc.writeText("# Agents\n\nHardening prose copied by hand, no marker.\n")
    val unmarked = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(unmarked.output.contains("has no 'hardening-template' marker"), unmarked.output)
    assertTrue(unmarked.output.contains("sha256:$expectedDigest"), unmarked.output)

    // a stale acknowledgment names both digests so the triager sees that the shared
    // template moved, not their local prose
    agentsDoc.writeText("# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n")
    val stale = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      stale.output.contains("marker 000000000000, current $expectedDigest"),
      "expected the stale/current digest pair:\n" + stale.output
    )
  }

  @Test
  fun `template task prints the exact version-matched baked template`() {
    writeFixture()

    val printed = runner("hardeningAgentTemplate").build().output

    assertTrue(printed.contains(expectedTemplate), printed)
    assertTrue(printed.contains("<!-- hardening-template sha256:$expectedDigest -->"), printed)
    assertFalse(printed.contains("github.com/sava-software/sava-build/blob/main"), printed)
    assertTrue(
      printed.contains("Consumer hardening notes contain only local ownership") &&
          printed.contains("AGENTS.md` may carry this exact generated"),
      "the version-matched template must distinguish pinned AGENTS instructions from consumer notes:\n$printed",
    )
    assertTrue(
      printed.contains("A mutant is a question, not a specification") &&
          printed.contains("Property: ... | Oracle: ... | Outcome:") &&
          printed.contains("fails against") && printed.contains("the unmutated code") &&
          printed.contains("never embed PIT coordinates or line numbers"),
      "the version-matched template must require a contract-first mutation decision at handoff:\n$printed",
    )
    assertFalse(printed.contains("Migration is one-way"), printed)
    assertFalse(printed.contains("pitest ≥"), printed)
  }

  @Test
  fun `a stale acknowledgment warns instead of failing when validating an unreleased checkout`() {
    // Local candidate adoption builds consumers against this checkout via
    // -PsavaBuildLocalRepo, and this checkout's digest has not shipped: a marker
    // acknowledging the released digest is the expected state there, not a defect.
    // Failing forced repos to acknowledge unreleased digests ahead of the release,
    // which wedged their 'check' against every published plugin until the release
    // landed and the pin was bumped.
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    agentsDoc.writeText("# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n")

    val advisory = runner("agentsTemplateInSync", "-PsavaBuildLocalRepo=unreleased-checkout").build()
    assertTrue(
      advisory.output.contains("the marker dance lands with the release that ships this digest"),
      "a stale marker under the local-candidate flag must warn, not fail:\n" + advisory.output
    )
    assertTrue(
      advisory.output.contains("If this is deliberate RC adoption") &&
          advisory.output.contains("do not land that consumer commit while it still resolves the older published plugin"),
      "the advisory must distinguish ordinary candidate validation from a staged RC-adoption change:\n" + advisory.output,
    )
    assertTrue(advisory.output.contains("sha256:$expectedDigest"), advisory.output)

    // a marker-less AGENTS.md is an unadopted repo, not a pending marker dance —
    // the flag does not soften that failure
    agentsDoc.writeText("# Agents\n\nNo marker.\n")
    val unmarked = runner("agentsTemplateInSync", "-PsavaBuildLocalRepo=unreleased-checkout").buildAndFail()
    assertTrue(unmarked.output.contains("has no 'hardening-template' marker"), unmarked.output)
  }

  @Test
  fun `a current acknowledgment passes and the check gates both verification entry points`() {
    writeFixture()
    File(fixtureDir, "AGENTS.md").writeText(
      "# Agents\n\nAdapted hardening block.\n\n<!-- hardening-template sha256:$expectedDigest -->\n"
    )

    val current = runner("agentsTemplateInSync").build()
    assertFalse(current.output.contains("no AGENTS.md"), current.output)
    assertFalse(current.output.contains("FAILED"), current.output)

    val check = runner("check", "--dry-run").build()
    assertTrue(check.output.contains(":agentsTemplateInSync"), "check must gate on it:\n" + check.output)
    val gate = runner("qualityGate", "--dry-run").build()
    assertTrue(gate.output.contains(":agentsTemplateInSync"), "qualityGate must gate on it:\n" + gate.output)
  }

  @Test
  fun `a subproject's task checks the root AGENTS_md, not the subproject's own`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "agents-template-sync-smoke-test"
        include("lib")
      """.trimIndent() + "\n"
    )
    val lib = File(fixtureDir, "lib")
    lib.mkdirs()
    File(lib, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }
      """.trimIndent() + "\n"
    )
    // the current acknowledgment lives at the root; the subproject's own AGENTS.md is
    // stale — only reading the root document lets this pass
    File(fixtureDir, "AGENTS.md").writeText(
      "# Agents\n\n<!-- hardening-template sha256:$expectedDigest -->\n"
    )
    File(lib, "AGENTS.md").writeText(
      "# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n"
    )

    val result = runner(":lib:agentsTemplateInSync").build()
    assertFalse(result.output.contains("no AGENTS.md"), result.output)
    assertFalse(result.output.contains("FAILED"), result.output)
  }
}
