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

  private companion object {
    const val BLOCK_START = "<!-- hardening-template block:start -->"
    const val BLOCK_END = "<!-- hardening-template block:end -->"
  }

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
  private val expectedPrintedTemplate: String = expectedTemplate.lineSequence()
    .joinToString("\n") { it.removePrefix("> ") }

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
    assertTrue(
      stale.output.contains("hardeningAgentTemplateDiff") &&
          stale.output.contains(BLOCK_START) && stale.output.contains(BLOCK_END),
      "the stale-marker failure must route legacy blocks through the bounded diff migration:\n" +
        stale.output,
    )
    assertTrue(
      stale.output.contains("immediately before the first shared hardening rule") &&
          stale.output.contains("immediately after the last") &&
          stale.output.contains("digest marker and all repository-specific facts outside") &&
          stale.output.contains("hardeningAgentTemplate emits the canonical final order") &&
          stale.output.contains("then one digest marker") &&
          stale.output.contains("replace or remove that line after review") &&
          stale.output.contains("do not append the emitted marker"),
      "the stale-marker failure must explain the semantic boundary placement:\n" + stale.output,
    )
  }

  @Test
  fun `template task prints the version-matched baked template without markdown quote markers`() {
    writeFixture()

    val printed = runner("hardeningAgentTemplate").build().output
    val marker = "<!-- hardening-template sha256:$expectedDigest -->"

    assertTrue(printed.contains(expectedPrintedTemplate), printed)
    assertTrue(
      printed.indexOf(BLOCK_START) < printed.indexOf(expectedPrintedTemplate) &&
          printed.indexOf(expectedPrintedTemplate) < printed.indexOf(BLOCK_END) &&
          printed.indexOf(BLOCK_END) < printed.indexOf(marker),
      "the paste-ready template must emit start, body, end, then digest marker:\n$printed",
    )
    assertFalse(
      printed.lineSequence().any { it.startsWith("> -") || it.startsWith(">   ") },
      printed,
    )
    assertTrue(printed.lineSequence().count { it == marker } == 1, printed)
    assertFalse(printed.contains("github.com/sava-software/sava-build/blob/main"), printed)
    assertTrue(
      printed.contains("Consumer hardening notes contain only local ownership") &&
          printed.contains("AGENTS.md` carries this exact generated") &&
          printed.contains("repository-specific facts outside its bounded block") &&
          printed.contains("hardeningAgentTemplateDiff") &&
          printed.contains("against its explicitly") &&
          printed.contains("bounded block"),
      "the version-matched template must distinguish pinned AGENTS instructions from consumer notes:\n$printed",
    )
    assertTrue(
      printed.contains("A mutant is a question, not a specification") &&
          printed.contains("Property: ... | Oracle: ... | Outcome:") &&
          printed.contains("fails against") && printed.contains("the unmutated code") &&
          printed.contains("never embed PIT coordinates or line numbers"),
      "the version-matched template must require a contract-first mutation decision at handoff:\n$printed",
    )
    assertTrue(
      printed.contains("A `[history]` report may check") &&
          printed.contains("run `pitest<Suite> -PnoMutationHistory` first") &&
          printed.contains("**Record.**") && printed.contains("**Classify.**") &&
          printed.contains("**Disambiguate.**") && printed.contains("**Retire.**") &&
          printed.contains("Only `cause:liveness` certifies") &&
          printed.contains("emergency exit does not demote") &&
          printed.contains("bound claimed") && printed.contains("deterministic oracle") &&
          printed.contains("duration × timeoutFactor + timeoutConst") &&
          printed.contains("otherwise shorten it") &&
          printed.contains("re-observe") && printed.contains("history-free — it contributes") &&
          printed.contains("contributes no cause") &&
          printed.contains("receives the test clock/budget") &&
          printed.contains("check for a synchronous state reader") &&
          printed.contains("not credible liveness evidence") &&
          printed.contains("`cause:harness` are reviewer-stops") &&
          printed.contains("race without authorizing it") &&
          printed.contains("stable `SURVIVED` equivalence argument") &&
          printed.contains("`TIMED_OUT`, never") && printed.contains("`MEMORY_ERROR`") &&
          printed.contains("without relying on PIT test order") &&
          printed.contains("sibling observed `KILLED`") &&
          printed.contains("another valid non-timeout") &&
          printed.contains("does not itself create") && printed.contains("mixed timeout causes") &&
          printed.contains("distinct same-key siblings") &&
          printed.contains("timing out under different cause categories") &&
          printed.contains("repeated fresh history-free non-timeout observations") &&
          printed.contains("lines cannot define identity") &&
          printed.contains("never warns, fails, or requires re-anchoring") &&
          printed.contains("fingerprint change alone does not reset") &&
          printed.contains("timeout-quiet format bump") &&
          printed.contains("reports are previews"),
      "the version-matched template must keep record decisions history-free and timeout evidence observable:\n$printed",
    )
    assertTrue(
      printed.contains("Invalid execution outcomes are not results") &&
          printed.contains("never justifies changing threads or heap") &&
          printed.contains("record load/RSS as context") &&
          printed.contains("Recurrence localizes a repeatable observation, not its cause") &&
          Regex("stable\\s+mutation-unit partition").containsMatchIn(printed) &&
          printed.contains("-PmutateOnly=<class> -PnoMutationHistory") &&
          printed.contains("pitest<Suite>Diagnostic") &&
          printed.contains("separate raw streams establish no total order") &&
          printed.contains("last announced mutation is context, not cause") &&
          Regex("Only a clean fresh full\\s+unscoped run").containsMatchIn(printed),
      "the version-matched template must not turn a repeatable RUN_ERROR coordinate into a cause diagnosis:\n$printed",
    )
    assertFalse(printed.contains("same coordinate is not evidence"), printed)
    assertTrue(
      printed.contains("mutationOwnershipAudit") &&
          printed.contains("whole-population"),
      "the version-matched template must expose the cheap ownership preflight:\n$printed",
    )
    assertFalse(printed.contains("Migration is one-way"), printed)
    assertFalse(printed.contains("pitest ≥"), printed)
  }

  @Test
  fun `template diff accepts bounded quoted or unquoted blocks and never edits AGENTS_md`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")

    fun assertMatches(body: String, quoteBoundaries: Boolean = false) {
      val start = if (quoteBoundaries) "> $BLOCK_START" else BLOCK_START
      val end = if (quoteBoundaries) "> $BLOCK_END" else BLOCK_END
      agentsDoc.writeText(
        "$start\n$body\n$end\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n"
      )
      val before = agentsDoc.readBytes()
      val first = runner("hardeningAgentTemplateDiff").build()
      assertTrue(first.output.contains("matches the installed template"), first.output)
      assertTrue(before.contentEquals(agentsDoc.readBytes()), "the diff task edited AGENTS.md")
      val reused = runner("hardeningAgentTemplateDiff").build()
      assertTrue(reused.output.contains("Reusing configuration cache"), reused.output)
      assertTrue(reused.output.contains("matches the installed template"), reused.output)
    }

    assertMatches(expectedPrintedTemplate)
    assertMatches("\n$expectedPrintedTemplate\n\n")
    // The migration failure prints unquoted boundary lines. They must be usable
    // verbatim around a legacy body that still carries one uniform quote layer.
    assertMatches(expectedTemplate)
    val migratedQuotedBody = runner("agentsTemplateInSync").build()
    assertFalse(
      migratedQuotedBody.output.contains("mixes quoted and unquoted"),
      "the sync gate rejected the copy-ready migration around a quoted legacy body:\n" +
          migratedQuotedBody.output,
    )
    val adapted = expectedPrintedTemplate.replaceFirst(
      "- **Scale verification to the change.**",
      "1. **Configuration-cache reread.**",
    )
    agentsDoc.writeText(
      "$BLOCK_START\n$adapted\n$BLOCK_END\n" +
        "<!-- hardening-template sha256:$expectedDigest -->\n"
    )
    val reread = runner("hardeningAgentTemplateDiff").build()
    assertTrue(reread.output.contains("Reusing configuration cache"), reread.output)
    assertTrue(reread.output.contains("-1. **Configuration-cache reread.**"), reread.output)
    assertTrue(reread.output.contains("+- **Scale verification to the change.**"), reread.output)

    assertMatches(expectedTemplate, quoteBoundaries = true)
    assertMatches(">\n$expectedTemplate\n> ", quoteBoundaries = true)

    agentsDoc.writeText(
      "```markdown\n$BLOCK_START\nexample\n$BLOCK_END\n```\n" +
          "$BLOCK_START\n$expectedPrintedTemplate\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )
    val fencedExample = runner("hardeningAgentTemplateDiff").build()
    assertTrue(
      fencedExample.output.contains("matches the installed template"),
      "fenced boundary examples were treated as live tokens:\n${fencedExample.output}",
    )
  }

  @Test
  fun `template diff prints adapted changes without failing or moving the marker`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    val adapted = expectedPrintedTemplate.replaceFirst(
      "- **Scale verification to the change.**",
      "1. **Local scale verification.**",
    )
    agentsDoc.writeText(
      "<!-- hardening-template sha256:$expectedDigest -->\n" +
        "$BLOCK_START\n$adapted\n$BLOCK_END\n"
    )
    val before = agentsDoc.readBytes()

    val result = runner("hardeningAgentTemplateDiff").build()

    assertTrue(result.output.contains("-1. **Local scale verification.**"), result.output)
    assertTrue(result.output.contains("+- **Scale verification to the change.**"), result.output)
    assertTrue(before.contentEquals(agentsDoc.readBytes()), "the diff task edited AGENTS.md")
  }

  @Test
  fun `misplaced repository headings are refused by both diff and sync`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")

    fun assertDiffRefuses(text: String) {
      agentsDoc.writeText(text)
      val failed = runner("hardeningAgentTemplateDiff").buildAndFail()
      assertTrue(
        failed.output.contains("Markdown ATX heading at AGENTS.md line 4") &&
            failed.output.contains("#### This repository") &&
            failed.output.contains("move $BLOCK_END before repository-specific headings"),
        "a misplaced repository heading was treated as shared template prose:\n${failed.output}",
      )
    }

    assertDiffRefuses(
      "$BLOCK_START\n- Shared rule.\n\n#### This repository\nLocal facts.\n$BLOCK_END\n",
    )
    assertDiffRefuses(
      "> $BLOCK_START\n> - Shared rule.\n>\n> #### This repository\n" +
          "> Local facts.\n> $BLOCK_END\n",
    )

    agentsDoc.writeText(
      "$BLOCK_START\n- Shared rule.\n\n#### This repository\nLocal facts.\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )
    val sync = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      sync.output.contains("Markdown ATX heading at AGENTS.md line 4") &&
          sync.output.contains("move $BLOCK_END before repository-specific headings"),
      "the current marker concealed a misplaced template boundary:\n${sync.output}",
    )
  }

  @Test
  fun `heading-like code inside the bounded block remains reviewable`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    val body =
      """
      - Shared rule.
      ```text
      # fenced code
      > literal prompt
      ```
          #### indented code
      \# escaped hash
      #not-a-heading
      """.trimIndent()
    agentsDoc.writeText(
      "$BLOCK_START\n$body\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )

    val diff = runner("hardeningAgentTemplateDiff").build()
    assertFalse(diff.output.contains("Markdown ATX heading"), diff.output)
    val sync = runner("agentsTemplateInSync").build()
    assertFalse(sync.output.contains("Markdown ATX heading"), sync.output)

    val quotedBody = body.lineSequence().joinToString("\n") { "> $it" }
    agentsDoc.writeText(
      "> $BLOCK_START\n$quotedBody\n> $BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )
    val quotedDiff = runner("hardeningAgentTemplateDiff").build()
    assertFalse(quotedDiff.output.contains("mixes quoted and unquoted"), quotedDiff.output)
    val quotedSync = runner("agentsTemplateInSync").build()
    assertFalse(quotedSync.output.contains("mixes quoted and unquoted"), quotedSync.output)
  }

  @Test
  fun `digest markers are unique real lines outside the bounded block`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")

    agentsDoc.writeText(
      "$BLOCK_START\n- Shared rule.\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n" +
          "<!-- hardening-template sha256:000000000000 -->\n",
    )
    val duplicate = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      duplicate.output.contains("contains 2 hardening-template digest markers") &&
          duplicate.output.contains("keep exactly one"),
      "duplicate real markers were not refused:\n${duplicate.output}",
    )

    agentsDoc.writeText(
      "$BLOCK_START\n- Shared rule.\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n$BLOCK_END\n",
    )
    val insideDiff = runner("hardeningAgentTemplateDiff").buildAndFail()
    assertTrue(
      insideDiff.output.contains("digest marker is inside the bounded block") &&
          insideDiff.output.contains("keep the marker outside"),
      "the diff accepted a digest marker inside its reviewed region:\n${insideDiff.output}",
    )
    val insideSync = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      insideSync.output.contains("digest marker is inside the bounded block"),
      "the sync gate accepted a digest marker inside its reviewed region:\n${insideSync.output}",
    )

    agentsDoc.writeText(
      "```markdown\n> ```\n<!-- hardening-template sha256:$expectedDigest -->\n" +
          "$BLOCK_START\nexample\n$BLOCK_END\n```\n",
    )
    val fencedOnly = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      fencedOnly.output.contains("has no 'hardening-template' marker"),
      "a fenced marker example satisfied the acknowledgment gate:\n${fencedOnly.output}",
    )
  }

  @Test
  fun `a malformed digest marker is a hard sync failure`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    agentsDoc.writeText(
      "$BLOCK_START\n- Shared rule.\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:ABCDEF012345 -->\n",
    )

    val failed = runner("agentsTemplateInSync").buildAndFail()

    assertTrue(
      failed.output.contains("malformed hardening-template digest marker at AGENTS.md line 4") &&
          failed.output.contains("<12 lowercase hex>"),
      "a malformed marker fell through to ordinary missing-marker remediation:\n${failed.output}",
    )
  }

  @Test
  fun `quoted and unquoted boundary lines cannot be mixed`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    agentsDoc.writeText(
      "> $BLOCK_START\n> - Shared rule.\n$BLOCK_END\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )

    val diff = runner("hardeningAgentTemplateDiff").buildAndFail()
    assertTrue(
      diff.output.contains("boundaries mix quoted and unquoted presentation") &&
          diff.output.contains("quote both boundary lines or neither"),
      "the diff accepted a mismatched boundary presentation:\n${diff.output}",
    )
    val sync = runner("agentsTemplateInSync").buildAndFail()
    assertTrue(
      sync.output.contains("boundaries mix quoted and unquoted presentation") &&
          sync.output.contains("quote both boundary lines or neither"),
      "the sync gate accepted a mismatched boundary presentation:\n${sync.output}",
    )
  }

  @Test
  fun `a current marker without boundaries is an explicit migration failure`() {
    writeFixture()
    File(fixtureDir, "AGENTS.md").writeText(
      "# Agents\n\nLegacy adapted block.\n\n" +
          "<!-- hardening-template sha256:$expectedDigest -->\n",
    )

    val failed = runner("agentsTemplateInSync").buildAndFail()

    assertTrue(
      failed.output.contains("must contain exactly one ordered boundary pair") &&
          failed.output.contains(BLOCK_START) && failed.output.contains(BLOCK_END),
      "a current marker concealed a marker-only legacy block:\n${failed.output}",
    )
  }

  @Test
  fun `template diff refuses missing ambiguous reversed empty and mixed quote boundaries`() {
    writeFixture()
    val agentsDoc = File(fixtureDir, "AGENTS.md")
    val missing = runner("hardeningAgentTemplateDiff").buildAndFail()
    assertTrue(missing.output.contains("AGENTS.md does not exist"), missing.output)
    val malformed = listOf(
      "no boundaries\n" to "must contain exactly one ordered boundary pair",
      "$BLOCK_START\nbody\n$BLOCK_START\nbody\n$BLOCK_END\n" to
          "must contain exactly one ordered boundary pair",
      "$BLOCK_END\nbody\n$BLOCK_START\n" to
          "must contain exactly one ordered boundary pair",
      "$BLOCK_START\n\n$BLOCK_END\n" to "bounded hardening template block in AGENTS.md is empty",
      "> $BLOCK_START\n>\n> \n> $BLOCK_END\n" to
          "bounded hardening template block in AGENTS.md is empty",
      "$BLOCK_START\n> quoted\nunquoted\n$BLOCK_END\n" to
          "bounded block mixes quoted and unquoted nonblank lines",
    )

    malformed.forEachIndexed { index, (text, expectedFailure) ->
      agentsDoc.writeText(text)
      val failed = runner("hardeningAgentTemplateDiff").buildAndFail()
      assertTrue(
        failed.output.contains("hardeningAgentTemplateDiff:") &&
            failed.output.contains(expectedFailure),
        "malformed case $index did not fail with task context:\n${failed.output}",
      )
      if (index == 0) {
        assertTrue(
          failed.output.contains(BLOCK_START) && failed.output.contains(BLOCK_END),
          "missing-boundary failure did not print copy-ready boundary lines:\n${failed.output}",
        )
        assertTrue(
          failed.output.contains("immediately before the first shared hardening rule") &&
              failed.output.contains("immediately after the last") &&
              failed.output.contains("digest marker and all repository-specific facts outside") &&
              failed.output.contains("hardeningAgentTemplate emits the canonical final order") &&
              failed.output.contains("then one digest marker") &&
              failed.output.contains("replace or remove that line after review") &&
              failed.output.contains("do not append the emitted marker"),
          "missing-boundary failure did not explain the semantic boundary placement:\n${failed.output}",
        )
      }
    }
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
    agentsDoc.writeText(
      "# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n" +
          "```markdown\n<!-- hardening-template sha256:$expectedDigest -->\n" +
          "$BLOCK_START\nexample\n$BLOCK_END\n```\n",
    )

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
    assertTrue(
      advisory.output.contains("acknowledges template digest 000000000000"),
      "a fenced current-marker example concealed the real stale marker:\n${advisory.output}",
    )

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
      "# Agents\n\n$BLOCK_START\nAdapted hardening block.\n$BLOCK_END\n\n" +
        "<!-- hardening-template sha256:$expectedDigest -->\n"
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
        include("lib", "other")
      """.trimIndent() + "\n"
    )
    val moduleBuild =
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }
      """.trimIndent() + "\n"
    val lib = File(fixtureDir, "lib").apply { mkdirs() }
    File(lib, "build.gradle.kts").writeText(moduleBuild)
    val other = File(fixtureDir, "other").apply { mkdirs() }
    File(other, "build.gradle.kts").writeText(moduleBuild)
    // the current acknowledgment lives at the root; the subproject's own AGENTS.md is
    // stale — only reading the root document lets this pass
    File(fixtureDir, "AGENTS.md").writeText(
      "# Agents\n\n$BLOCK_START\n$expectedPrintedTemplate\n$BLOCK_END\n" +
        "<!-- hardening-template sha256:$expectedDigest -->\n"
    )
    File(lib, "AGENTS.md").writeText(
      "# Agents\n\n<!-- hardening-template sha256:000000000000 -->\n"
    )

    val result = runner(":lib:agentsTemplateInSync").build()
    assertFalse(result.output.contains("no AGENTS.md"), result.output)
    assertFalse(result.output.contains("FAILED"), result.output)
    val diff = runner(":lib:hardeningAgentTemplateDiff").build()
    assertTrue(diff.output.contains("matches the installed template"), diff.output)
    val template = runner(":lib:hardeningAgentTemplate").build()
    assertTrue(
      template.output.lineSequence().count { it == BLOCK_START } == 1 &&
          template.output.lineSequence().count { it == BLOCK_END } == 1,
      "a project-qualified template task must print exactly one bounded block:\n${template.output}",
    )
    assertFalse(
      template.output.contains(":other:hardeningAgentTemplate"),
      "the qualified task unexpectedly selected another hardening project:\n${template.output}",
    )
  }

  @Test
  fun `repository template checks run once across applying subprojects`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "agents-template-repository-scope-test"
        include("a", "b")
      """.trimIndent() + "\n"
    )
    val moduleBuild =
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }
      """.trimIndent() + "\n"
    listOf("a", "b").forEach { name ->
      File(fixtureDir, name).apply { mkdirs() }
        .resolve("build.gradle.kts").writeText(moduleBuild)
    }
    File(fixtureDir, "AGENTS.md").writeText(
      "# Agents\n\nAdapted block without boundaries.\n\n" +
        "<!-- hardening-template sha256:000000000000 -->\n"
    )

    val diff = runner("hardeningAgentTemplateDiff", "--continue", "--parallel")
      .buildAndFail().output
    val failedDiffTasks = diff.lineSequence().count {
      it.startsWith("> Task :") &&
        it.contains(":hardeningAgentTemplateDiff") &&
        it.endsWith(" FAILED")
    }
    assertTrue(
      failedDiffTasks == 1 &&
        diff.contains("> Task :a:hardeningAgentTemplateDiff") &&
        diff.contains("> Task :b:hardeningAgentTemplateDiff"),
      "the repository-scoped diff failure was printed once per applying project:\n$diff",
    )

    fun assertOneAdvisory(output: String) {
      assertTrue(
        output.split("agentsTemplateInSync: AGENTS.md acknowledges template digest").size - 1 == 1,
        "the repository-scoped stale-marker warning was printed more than once:\n$output",
      )
      assertTrue(
        output.contains("hardening: 1 advisory finding(s) across 1 scope(s)") &&
          output.contains("repository AGENTS.md: AGENTS.md acknowledges an older hardening template"),
        "the repository advisory was duplicated or described as suite-scoped:\n$output",
      )
    }

    assertOneAdvisory(
      runner(
        "agentsTemplateInSync", "-PsavaBuildLocalRepo=unreleased-checkout", "--parallel",
      ).build().output,
    )
    val reused = runner(
      "agentsTemplateInSync", "-PsavaBuildLocalRepo=unreleased-checkout", "--parallel",
    ).build().output
    assertTrue(reused.contains("Configuration cache entry reused."), reused)
    assertOneAdvisory(reused)
  }
}
