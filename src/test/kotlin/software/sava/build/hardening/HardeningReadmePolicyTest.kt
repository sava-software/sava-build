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
  fun `source locator audit follows a wrapped Java owner onto the next line`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      The acceptance belongs to `IncidentIoConfig${'$'}Parser.test`
      :219 and still exercises the delegate.

      The branch in com.example.Codec.decode
      > `:341/347` remains equivalent.

      The method `parse`
      :412 is the semantic branch under review.

      `parseProperties`
      :517 is the final delegate site.

      The same acceptance is owned by **IncidentIoConfig${'$'}Parser.parse**
      :619 after Markdown emphasis is removed.

      [parseProperties](#parser-method)
      :620 is linked from the evidence index.

      __Codec.decode__
      :621 remains the branch under review.

      **[`Parser.decode`](#decode-branch)**
      :622 keeps nested Markdown from hiding the owner.

      **Parser.decode**:623 and [Codec.encode](#encode-branch):624 remain source sites.
      """.trimIndent()
    )

    assertEquals(
      listOf(
        ":219", ":341/347", ":412", ":517", ":619", ":620", ":621", ":622", ":623", ":624",
      ),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(
      listOf(2, 5, 8, 11, 14, 17, 20, 23, 25, 25),
      inspection.sourceLocators.map { it.lineNumber },
    )
  }

  @Test
  fun `source locator audit follows an earlier same-line backticked method`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      chunk formation makes `batchTableTasks` never return. `:119` (the outer
      chunk-loop bound is the semantic branch under review).
      """.trimIndent()
    )

    assertEquals(listOf(":119"), inspection.sourceLocators.map { it.matchedText })
    assertEquals(listOf(1), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `source locator audit recognizes lower camel and common bare method owners`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      removed loop exits (queueBatchable:150 — the chunk-loop exit starves downstream)
      run:642 arithmetic stalls progression and :664 removes the final exit.
      indexes:213 leaks the read lock; lambda:635 loses the supplier result.
      """.trimIndent()
    )

    assertEquals(
      listOf(":150", ":642", ":664", ":213", ":635"),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(listOf(1, 2, 2, 3, 3), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `all lowercase owners survive Markdown reflow and earlier prose`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      **indexes**:213 leaks the read lock.
      [lambda](#supplier):635 loses the supplier result.
      `indexes`
      :214 is the second lock site.
      The `indexes` loop never returns at `:215`.
      """.trimIndent()
    )

    assertEquals(
      listOf(":213", ":635", ":214", ":215"),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(listOf(1, 2, 4, 5), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `configuration keys are not mistaken for lower camel method owners`() {
    val inspection = HardeningReadmePolicy.inspect(
      "Configuration keys retryDelay:250, batchSize:100, and maxLen:128 are not source owners."
    )

    assertTrue(inspection.sourceLocators.isEmpty(), inspection.sourceLocators.toString())
  }

  @Test
  fun `configuration list context follows only its entries and source vocabulary wins`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Configuration keys:

      retryDelay:250
      batchSize:100
      maxLen:128

      Configuration properties:
      ```yaml
      pollDelay:300
      requestSize:200
      ```

      Configuration keys are parsed by method parseProperties:404.
      """.trimIndent()
    )

    assertEquals(listOf(":404"), inspection.sourceLocators.map { it.matchedText })
    assertEquals(listOf(13), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `configuration and source ownership are decided at each owner`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Configuration keys retryDelay:250; Parser.parse:404 remains the source delegate.
      The method parse:405 yields status:200, errorCode:500, server:8080, and java:21.
      The method timeout:250 and method status:201 are real source methods.
      """.trimIndent()
    )

    assertEquals(
      listOf(":404", ":405", ":250", ":201"),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `configuration headings carry across blank separated entries until real prose`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      ## Configuration

      retryDelay:250

      batchSize:100

      ## Mutation evidence
      Parser.parse:404 remains the source delegate.

      Configuration defaults:
      ```properties
      server:8080

      errorCode:500
      ```
      run:405 is outside the closed configuration fence.
      """.trimIndent()
    )

    assertEquals(
      listOf(":404", ":405"),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `Java generic owners are source coordinates but task templates are not`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      `Parser<T>.parse:219` is the source branch.
      **Parser<T>.decode**:220 is emphasized.
      Parser<T>.encode
      :221 follows Markdown reflow.
      The `Parser<T>.apply` branch never returns at `:222`.

      `pitest<Suite>`:150 names generated task notation, not a Java owner.
      `pitest<Suite>`
      :151 remains task notation after Markdown reflow.
      """.trimIndent()
    )

    assertEquals(
      listOf(":219", ":220", ":221", ":222"),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(listOf(1, 2, 4, 5), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `every pitest placeholder is task notation while Java generics remain source owners`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      pitest<Config>:150 and `pitest<AnyPlaceholder>Verify`:151 are task notation.
      **pitest<Scope>Debt**:152, pitest<suite-name>:153, and `pitest<*>`:154 are task notation.
      Parser<T>.parse:219 and `Codec<K>.decode`:220 are Java source owners.
      """.trimIndent()
    )

    assertEquals(
      listOf(":219", ":220"),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `Java metadata and full URLs stay clean while explicit methods win`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Build with `java:21`, `jdk`:21, java:22, and jvm:17.
      See https://example.com/this/is/a/very/long/documentation/path/status:404 then method parse:405.
      The method timeout:250 and method version:30 remain source branches.
      The method server
      :8080 is named despite sharing a port-shaped label.
      """.trimIndent()
    )

    assertEquals(
      listOf(":405", ":250", ":30", ":8080"),
      inspection.sourceLocators.map { it.matchedText },
    )
    assertEquals(listOf(2, 3, 3, 5), inspection.sourceLocators.map { it.lineNumber })
  }

  @Test
  fun `URI numeric components stay clean while source coordinates in links remain visible`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      Contact mailto:12345 and resolve urn:isbn:12345.
      See https://example.com/docs/(archived)/status:404 and ftp://example.com/errorCode:500.
      Read [release notes](notes/(archived)/status:404) and [source](Parser.parse:219).
      The real method Parser.parse:220 remains visible after those links.
      """.trimIndent()
    )

    assertEquals(listOf(":219", ":220"), inspection.sourceLocators.map { it.matchedText })
  }

  @Test
  fun `earlier source owners do not authorize metadata shorthand`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      The `batchTableTasks` loop never returns. `:119` is the outer bound.
      The `indexes` loop never returns at `:215`.
      The `indexes` sample records a timeout of `:250`.
      The `indexes` timeout remains `:251` even beside ORDER_IF mutation prose.
      run:642 uses a timeout of `:250` but reaches :664 when the loop exits.
      The `indexes` result is displayed in UTC at `:30`.
      """.trimIndent()
    )

    assertEquals(
      listOf(":119", ":215", ":642"),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `Markdown list and heading owners survive line wrapping`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      - `indexes`
        :213 leaks the read lock.
      ## `parseProperties`
      :404 is the parser branch.
      1. **lambda**
         :635 loses the supplier result.
      """.trimIndent()
    )

    assertEquals(
      listOf(":213", ":404", ":635"),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `one digit full coordinates and explicit source locator forms are audited`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      run:7 and `Parser.test:1` are full coordinates.
      **indexes**:9 is another full coordinate, while bare `:7` is not.
      Review line(s) 7, line 8, lines 9 and 10, L11, and L12-L13.
      Source links https://example.com/source#L14 and [linked source](File.java#L15) still decay.
      """.trimIndent()
    )

    assertEquals(
      listOf(
        ":7", ":1", ":9", "line(s) 7", "line 8", "lines 9 and 10", "L11", "L12", "L13",
        "L14", "L15",
      ),
      inspection.sourceLocators.map { it.matchedText },
    )
  }

  @Test
  fun `blockquote markers and generic task notation are not coordinate owners`() {
    val inspection = HardeningReadmePolicy.inspect(
      """
      > :119 is a quoted release-note label, not a source coordinate.
      `pitest<Suite>`:150 names generated task notation, not a Java owner.
      `pitest<Suite>`
      :151 remains task notation after Markdown reflow.
      > `:152` is another quoted label.
      """.trimIndent()
    )

    assertTrue(inspection.sourceLocators.isEmpty(), inspection.sourceLocators.toString())
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
      Labels remain `UTC`:30, `version`:30, `timeout`:250, and `Duration`:500.
      Bare forms UTC:30, version:30, timeout:250, Duration:500, and RFC:3339 are metadata.
      Configuration keys retryDelay:250, batchSize:100, and maxLen:128 are not source owners.

      Released version
      :30 with the ordinary notes.
      Probe example.com
      :443 only when the service is available.
      The ratio is
      :100 in this illustrative notation.
      `port`
      :8080 in the wrapped configuration table.
      `version`
      :30 in the release index.
      `UTC`
      :30 in the clock display.
      `timeout`
      :250 in the configuration table.
      **Duration**
      :500 in the metrics legend.
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
