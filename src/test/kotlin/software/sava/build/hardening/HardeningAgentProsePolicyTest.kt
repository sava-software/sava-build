package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardeningAgentProsePolicyTest {

  @Test
  fun `incident adoption mechanics are reported by passage family`() {
    val inspection = inspectOutside(
      """
      # Agents

      Local hardening records include the accepted baselines. A suite with nothing
      unkilled has no accepted file at all. No suite times out today, so no
      `<suite>-timeouts.csv` exists yet — the timeout audit is armed, not active.

      The property adds that repo to `pluginManagement` and rewrites every
      `software.sava.build*` plugin id to `software.sava:sava-build:0.0.0-test`, so the
      pinned versions are ignored while it is set.

      Every edit needs the publish task re-run, or this build silently keeps resolving
      the previously published jar. Gradle does re-read `file:` repositories on each
      resolution. The end-of-build notice names the last-publish age and SHA-256 on
      every build, including configuration-cache hits.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      <!-- hardening-template sha256:0123456789ab -->
      """.trimIndent()
    )

    assertEquals(
      listOf(
        "empty-accepted-record",
        "empty-timeout-record",
        "local-repository-resolution",
        "local-repository-cache",
        "local-repository-notice",
      ),
      inspection.findings.map { it.family },
    )
    assertEquals(listOf(4, 4, 7, 11, 13), inspection.findings.map { it.lineNumber })
  }

  @Test
  fun `the incident publish wording is reported`() {
    val inspection = inspectOutside(
      """
      # Agents

      The publish is not automatic: every sava-build edit needs the publish task
      re-run before this build sees it.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(listOf("local-repository-cache"), inspection.findings.map { it.family })
    assertEquals(3, inspection.findings.single().lineNumber)
  }

  @Test
  fun `known template task contracts are candidates but invocation policy is not`() {
    val inspection = inspectOutside(
      """
      # Agents

      `hardeningHelp` prints the task and property surface, while
      `hardeningAgentTemplate` prints the operator rules.

      `hardeningAgentTemplateDiff` normalizes one quote layer and reports the body diff.
      `agentsTemplateInSync` fails when the installed digest changes.

      `hardeningCertify` writes one project receipt and re-executes every suite.
      `pitestConfigBaselineRebase` preserves every old row and seeds fresh debt.

      Run `:module:hardeningHelp` after upgrading the plugin.
      Run `:module:hardeningAgentTemplateDiff` on every digest move.
      Run `:module:hardeningCertify` before release and record the local budget.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(
      listOf(
        "installed-help-output",
        "installed-template-output",
        "installed-template-diff",
        "template-sync-contract",
        "certification-contract",
        "baseline-writer-contract",
      ),
      inspection.findings.map { it.family },
    )
  }

  @Test
  fun `incident template print and rediff contract is reported`() {
    val inspection = inspectOutside(
      """
      # Agents

      The block below is generated — print the installed version with
      `./gradlew :incident-core:hardeningAgentTemplate` and re-diff it with
      `./gradlew :incident-core:hardeningAgentTemplateDiff`. After a sava-build upgrade,
      re-diff this block, **act on** each changed bullet, then update the marker.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(
      listOf("installed-template-output", "installed-template-diff"),
      inspection.findings.map { it.family },
    )
    assertEquals(listOf(3, 4), inspection.findings.map { it.lineNumber })
  }

  @Test
  fun `repository ownership measurements reasons provenance and timing are clean`() {
    val inspection = inspectOutside(
      """
      # Agents

      CI runs `check` only. Run `:core:hardeningCertify` before a release, and run
      `:core:hardeningReadmeAudit` before handing off a README edit.

      The config suite owns `Parser`; its latest accepted family is `empty-default` because
      callers distinguish omission from an explicit zero. Receipts are retained under
      `.pitest-history/`, and the current checkout has no timeout membership records.

      Keep the `savaBuildLocalRepo` value in `AGENTS.local.md`, never in tracked settings.
      Consult `:core:hardeningHelp` for installed behavior.
      Read `:core:hardeningHelp`, for what it reports rather than a copy kept here.
      Consult `:core:hardeningHelp` to see what it reports for this installed version.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertTrue(inspection.isClean, inspection.findings.toString())
  }

  @Test
  fun `separate markdown blocks cannot synthesize a task contract`() {
    val inspection = inspectOutside(
      """
      # Agents

      - Run `:core:hardeningCertify` before release.
      - The release script writes the repository-local version file.

      | Local task | Repository fact |
      | --- | --- |
      | `:core:hardeningCertify` | Run before release |

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertTrue(inspection.isClean, inspection.findings.toString())
  }

  @Test
  fun `generated block and fenced command output are excluded while source lines survive`() {
    val text =
      """
      # Agents

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - `agentsTemplateInSync` fails when the installed digest changes.
      - A suite with nothing unkilled has no accepted file.
      ${HardeningAgentTemplateBlock.BLOCK_END}

      ```text
      hardeningHelp prints the installed surface
      ```

      Local note: a suite with nothing unkilled has no accepted file at all.
      """.trimIndent()
    val parsed = HardeningAgentTemplateBlock.parse(text.lines())
    val inspection = HardeningAgentProsePolicy.inspect(parsed.outsideLines)

    assertEquals(1, inspection.findings.size)
    assertEquals("empty-accepted-record", inspection.findings.single().family)
    assertEquals(12, inspection.findings.single().lineNumber)
  }

  @Test
  fun `warning states the allowed boundary and remains advisory`() {
    val inspection = inspectOutside(
      """
      A suite with nothing unkilled has no accepted file at all.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    val warning = HardeningAgentProsePolicy.warning(inspection, ":core:hardeningHelp")
    assertTrue(warning.contains("1 likely copied plugin-mechanics passage"), warning)
    assertTrue(warning.contains("AGENTS.md:1"), warning)
    assertTrue(warning.contains("may name a project-qualified task"), warning)
    assertTrue(warning.contains("bounded generated block is deliberately excluded"), warning)
    assertTrue(warning.contains(":core:hardeningHelp"), warning)
    assertTrue(warning.contains("non-failing migration advisory"), warning)
  }

  private fun inspectOutside(text: String): HardeningAgentProsePolicy.Inspection {
    val parsed = HardeningAgentTemplateBlock.parse(text.lines())
    return HardeningAgentProsePolicy.inspect(parsed.outsideLines)
  }
}
