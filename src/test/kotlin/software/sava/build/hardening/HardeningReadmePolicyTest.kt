package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardeningReadmePolicyTest {

  @Test
  fun `source locator audit includes inline shorthand and fenced rosters`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      # Accepted

      `Codec.encode:219` is the semantic branch under review.
      The nine mixing statements on lines 339-347 are equivalent.

      ```text
      run:75/79/85 ORDER_IF; :149 VoidMethodCallMutator
      ```
      """.trimIndent()
    )

    assertEquals(
      listOf(":219", "lines 339-347", ":75/79/85", ":149"),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(listOf(3, 4, 7, 7), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `structured tags dates versions clocks network ports ratios and map values are excluded`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      com.example.Codec,encode,MathMutator,SURVIVED # line 206
      The literal `# lines 206, 219` describes structured row metadata.
      Released 21.5.28 on 2026-08-25 at 17:48:20.
      Probe https://localhost:8443/a, 127.0.0.1:8899, [::1]:8080, and example.com:443.
      Configuration uses server:8080, port:8899 and dataSlice{offset:0,length:13}.
      A ratio is 1:100; `:10` is a clock-origin shorthand, not a source coordinate.
      """.trimIndent()
    )

    assertTrue(inspection.sourceLocators.isEmpty(), inspection.sourceLocators.toString())
  }

  @Test
  fun `known historical scaffold clauses are matched by family after line reflow`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Each `pitest<Suite>` run is finalized by
      `pitest<Suite>Verify`, which diffs the run.

      Never refresh with `-PupdateMutationBaseline` just to make the build pass.

      A baseline row may carry a trailing `# note` before metadata.
      A baseline row may carry a `# note` before its line tag too.

      The CSV files beside this document are structured evidence. Preserve row identity,
      multiplicity, schema markers, and ordering. An ArcMutate `[history]` report is check-only.

      For every accepted family, explain the local structural reason and quote the label.

      For every audited timeout row, replace the seeded `cause:untriaged` comment category.
      """.trimIndent()
    )

    assertEquals(6, inspection.inheritedScaffolds.size)
    assertEquals(6, inspection.inheritedScaffolds.map { it.lineNumber }.distinct().size)
  }

  @Test
  fun `current local evidence scaffold is clean`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      # Mutation hardening evidence

      This file contains repository-specific evidence and decisions only.
      Keep all prose and inline, fenced, or tabular coordinate rosters source-line-free;
      retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`.

      ## Accepted mutants

      For every accepted family, record its exact label, local structural reason,
      property, independent oracle, and invalidation condition. Name the class,
      method, and semantic branch, and omit source line numbers.
      """.trimIndent()
    )

    assertTrue(inspection.isClean, inspection.findings.toString())
  }

  @Test
  fun `warning is structured and explicitly non-failing`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the run.
      `Codec.encode:219` is accepted.
      """.trimIndent()
    )

    val warning = HardeningReadmePolicy.warning(
      "config/pitest/README.md",
      inspection,
      ":codec:hardeningHelp",
    )
    assertTrue(warning.contains("1 likely source-line locator"), warning)
    assertTrue(warning.contains("1 inherited scaffold-mechanics passage"), warning)
    assertTrue(warning.contains("Source-locator candidates:\n    README.md:2"), warning)
    assertTrue(warning.contains("Inherited scaffold mechanics:\n    README.md:1"), warning)
    assertTrue(warning.contains("Inline, fenced, and tabular coordinate rosters"), warning)
    assertTrue(warning.contains(":codec:hardeningHelp"), warning)
    assertTrue(warning.contains("non-failing migration advisory"), warning)
    assertFalse(warning.contains("Run pitest"), warning)
  }
}
