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
        "empty-accepted-record+empty-timeout-record",
        "local-repository-resolution",
        "local-repository-cache+local-repository-notice",
      ),
      inspection.findings.map { it.family },
    )
    assertEquals(listOf(4, 7, 11), inspection.findings.map { it.lineNumber })
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
        "installed-help-output+installed-template-output",
        "installed-template-diff+template-sync-contract",
        "certification-contract+baseline-writer-contract",
      ),
      inspection.findings.map { it.family },
    )
    assertEquals(listOf(3, 6, 9), inspection.findings.map { it.lineNumber })
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
      listOf("installed-template-output+installed-template-diff+task-output-granularity"),
      inspection.findings.map { it.family },
    )
    assertEquals(listOf(3), inspection.findings.map { it.lineNumber })
    assertEquals(3, inspection.findings.single().rules.size)
  }

  @Test
  fun `generic task-result pointer is clean while copied output granularity is reported`() {
    listOf("those", "both", "the", "these").forEach { determiner ->
      val generic = inspectOutside(
        """
        # Agents

        After a template move, run `:core:hardeningAgentTemplateDiff` and
        `:core:hardeningAgentProseAudit`, then act on everything $determiner tasks report.

        ${HardeningAgentTemplateBlock.BLOCK_START}
        - Shared generated rule.
        ${HardeningAgentTemplateBlock.BLOCK_END}
        """.trimIndent()
      )
      assertTrue(generic.isClean, "$determiner: ${generic.findings}")
    }

    val singular = inspectOutside(
      """
      # Agents

      Run `:core:hardeningAgentTemplateDiff`, then act on its report.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )
    assertTrue(singular.isClean, singular.findings.toString())

    listOf("everything it reports", "what it reports", "whatever it reports").forEach { result ->
      val pronoun = inspectOutside(
        """
        # Agents

        Run `:core:hardeningAgentTemplateDiff` and act on $result.

        ${HardeningAgentTemplateBlock.BLOCK_START}
        - Shared generated rule.
        ${HardeningAgentTemplateBlock.BLOCK_END}
        """.trimIndent()
      )
      assertTrue(pronoun.isClean, "$result: ${pronoun.findings}")
    }

    val granular = inspectOutside(
      """
      # Agents

      After the audits, act on each changed bullet and resolve every prose candidate it names.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )
    assertEquals(listOf("task-output-granularity"), granular.findings.map { it.family })

    val copiedContractThenPointer = inspectOutside(
      """
      # Agents

      `hardeningAgentTemplateDiff` reports each changed bullet; run the prose audit and
      act on everything both tasks report.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )
    assertEquals(
      listOf("installed-template-diff"),
      copiedContractThenPointer.findings.map { it.family },
    )
  }

  @Test
  fun `one same-line passage is reported once even when two task families match`() {
    val inspection = inspectOutside(
      """
      # Agents

      `hardeningAgentTemplate` emits the body and `hardeningAgentTemplateDiff` reports its diff.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(1, inspection.findings.size, inspection.findings.toString())
    assertEquals(
      "installed-template-output+installed-template-diff",
      inspection.findings.single().family,
    )
    assertEquals(2, inspection.findings.single().rules.size)
    val warning = HardeningAgentProsePolicy.warning(inspection, ":core:hardeningHelp")
    assertEquals(1, Regex("AGENTS\\.md:3").findAll(warning).count(), warning)
    assertTrue(warning.contains("installed-template-output: \""), warning)
    assertTrue(warning.contains("installed-template-diff: \""), warning)
  }

  @Test
  fun `one wrapped paragraph is one passage while retaining every matched rule`() {
    val inspection = inspectOutside(
      """
      # Agents

      `hardeningAgentTemplate` emits the body, and on the next wrapped line
      `hardeningAgentTemplateDiff` reports its diff while `agentsTemplateInSync`
      fails when the digest changes.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(1, inspection.findings.size, inspection.findings.toString())
    assertEquals(
      "installed-template-output+installed-template-diff+template-sync-contract",
      inspection.findings.single().family,
    )
    assertEquals(3, inspection.findings.single().rules.size)
    assertEquals(3, inspection.findings.single().lineNumber)
  }

  @Test
  fun `sibling plugin dsl and settings mechanics are candidates`() {
    val inspection = inspectOutside(
      """
      # Agents

      JDK provisioning is automatic through the Foojay resolver.

      Use `declineSeedCorpus` / `declineMutator` with a measured reason.

      `excludedClasses` filters matching production classes, and the mutation register
      block creates its PIT task.

      `savaBuildLocalRepo` is a settings-level property of the installed plugin, so
      `settings.gradle.kts` needs no editing either way.

      ${HardeningAgentTemplateBlock.BLOCK_START}
      - Shared generated rule.
      ${HardeningAgentTemplateBlock.BLOCK_END}
      """.trimIndent()
    )

    assertEquals(
      listOf(
        "sibling-plugin-contract",
        "hardening-dsl-contract",
        "hardening-dsl-contract",
        "local-repository-settings-contract",
      ),
      inspection.findings.map { it.family },
    )
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

      The repo applies `software.sava.build.feature.jdk-provisioning` in settings.
      Every fuzz target declares `seedCorpus` with a repository-owned corpus directory.
      The config suite's `excludedClasses` list contains generated DTOs owned elsewhere.

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
    assertTrue(warning.contains("[empty-accepted-record: \"nothing unkilled"), warning)
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
