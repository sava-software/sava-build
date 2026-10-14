package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The commit checks, each named for the release-please behaviour it mirrors. */
class GuardCommitsTest {

  @Test
  fun `an unparseable conventional commit since the manifest tag fails the run by name`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val bad = r.commit(BAD_BODY)
    r.commit("feat: fine\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "::error::release-please cannot parse $bad ")
    assertMatch(out, "unexpected token '\\(' at 4:29, valid tokens \\[\\)\\]")
    assertMatch(out, "checked 2 commit\\(s\\) since tag 1\\.0\\.0 \\(manifest version 1\\.0\\.0\\): 1 unparseable")
  }

  @Test
  fun `commits before the tag are not read, and a clean range passes`() {
    val r = Repo()
    r.commit(BAD_BODY)
    r.git("tag", "1.0.0")
    r.commit("fix: later\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "checked 1 commit\\(s\\) since tag 1\\.0\\.0")
  }

  @Test
  fun `include-v-in-tag and the component spelling resolve the base`() {
    val r = Repo(mapOf("include-v-in-tag" to true))
    r.commit("chore: start\n")
    r.git("tag", "v2.3.4")
    r.commit(BAD_BODY)
    r.configure("2.3.4")
    assertMatch(r.run().out, "since tag v2\\.3\\.4")
    r.git("tag", "p-v2.3.5")
    r.commit("feat: after\n")
    r.configure("2.3.5", mapOf("include-component-in-tag" to true))
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "since tag p-v2\\.3\\.5")
  }

  @Test
  fun `without a tag the last commits are checked like a first release, with a notice`() {
    val r = Repo()
    r.commit("Initial import\n")
    r.commit(BAD_BODY)
    r.configure("0.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "::notice::no release tag found for manifest version 0\\.0\\.0; checking the last 2 commit")
  }

  @Test
  fun `a message that is not a conventional commit is a notice, as release-please ignores it`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("Merge branch feature\n")
    r.commit("Revert \"fix: x\"\n\nThis reverts commit abc.\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "release-please will not read [0-9a-f]{7} \\(not a conventional commit\\): Merge branch feature")
    assertMatch(out, "::notice::release-please will not read 2 of the 2 commit\\(s\\)")
  }

  @Test
  fun `the grammar, not a hand-written pattern, decides what counts as a conventional header`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    // a type with a digit: the pattern alone would call this a notice; the grammar reads the header
    r.commit("fix2(core): header the pattern would reject\n\nBody(with(nested)) parens.\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "::error::")
  }

  @Test
  fun `an empty scope is the one header the grammar rejects but release-please drops with its notes`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("fix()!: empty scope\n\nBody(with(nested)) parens.\n\nBREAKING CHANGE: yes.\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "\"fix\\(\\)!: empty scope\"")
  }

  @Test
  fun `a wrapped call shows the line end as LF in the parser position`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("fix: wrapped\n\nCompare with\nfoo(a,\nb).\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "unexpected token '<LF>'")
    assertMatch(out, "While this run is red the release PR is not refreshed")
  }

  @Test
  fun `a nested commit block is read from its own header, not the blank line before it`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val outer = r.commit("feat: outer\n\nBEGIN_NESTED_COMMIT\nfix(core): inner\n\n" +
      "Arrays.compare(a.toByteArray(), b.toByteArray()).\nEND_NESTED_COMMIT\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "cannot parse $outer .*\"fix\\(core\\): inner\"")
  }

  @Test
  fun `on a release-merge run the nearest earlier tag is the base, read locally`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("feat: in the release\n")
    r.commit("chore(main): release 1.1.0\n")
    r.configure("1.1.0")
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "checked 2 commit\\(s\\) since nearest tag 1\\.0\\.0 \\(manifest version 1\\.1\\.0 has no tag yet\\)")
  }

  @Test
  fun `a squash message is split like release-please splits it, and each part is parsed`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val squash = r.commit("feat: first part\n\nfix(core): second part\n\n" +
      "Arrays.compare(a.toByteArray(), b.toByteArray()).\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "cannot parse $squash .*\"fix\\(core\\): second part\"")
  }

  @Test
  fun `a later commit with a Restates footer acknowledges the dropped one, and every footer counts`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val bad1 = r.commit(BAD_BODY)
    val bad2 = r.commit(BAD_BODY.replace("order PublicKey", "order Hash"))
    r.commit("fix(core)!: order PublicKey and Hash by unsigned bytes\n\nRestated.\n\n" +
      "BREAKING CHANGE: ordering changed.\n\nRestates: ${bad1.substring(0, 7)}\nRestates: ${bad2.substring(0, 7)}\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "\\(restated by a later commit\\): fix\\(core\\)!: order PublicKey")
    assertMatch(out, "\\(restated by a later commit\\): fix\\(core\\)!: order Hash")
  }

  @Test
  fun `a Restates footer on a commit release-please cannot read acknowledges nothing`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val bad = r.commit(BAD_BODY)
    // the carrier fails to parse for the same reason, so its restatement never reaches the notes
    r.commit("fix(core)!: restated\n\nStill(with(nested)) parens.\n\nRestates: ${bad.substring(0, 7)}\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "cannot parse $bad ")
    assertMatch(out, "2 unparseable conventional commit\\(s\\)")
  }

  @Test
  fun `with exclude-paths, a commit whose files all sit under them is skipped like release-please skips it`() {
    val r = Repo(mapOf("packages" to mapOf("." to mapOf("exclude-paths" to listOf("ix-mapper-ts")))))
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit(BAD_BODY, mapOf("ix-mapper-ts/a.ts" to "x")) // excluded
    val empty = r.commit("fix: restate nothing\n\nRestates: 0000000\n", emptyMap()) // lists no file: excluded too
    r.configure("1.0.0")
    val first = r.run()
    assertEquals(0, first.code, first.out)
    assertMatch(first.out, "2 excluded by exclude-paths")
    assertMatch(first.out, "release-please excludes ${empty.substring(0, 7)} ")
    val kept = r.commit(BAD_BODY, mapOf("ix-mapper-ts/b.ts" to "x", "src/C.java" to "y")) // one file outside
    val second = r.run()
    assertEquals(1, second.code, second.out)
    assertMatch(second.out, "cannot parse $kept ")
    assertMatch(second.out, "release-please excludes an empty commit")
  }

  @Test
  fun `an empty exclude-paths list still excludes an empty commit, as release-please does`() {
    val r = Repo(mapOf("packages" to mapOf("." to mapOf("exclude-paths" to emptyList<String>()))))
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val bad = r.commit(BAD_BODY)
    r.commit("fix: restate\n\nRestates: ${bad.substring(0, 7)}\n", emptyMap()) // empty: excluded, acknowledges nothing
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "1 excluded by exclude-paths")
    assertMatch(out, "cannot parse $bad ")
  }

  @Test
  fun `a carriage return in a commit subject cannot start a workflow command line`() {
    // The runner reads step output line by line and ends a line at a lone CR as well.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("update docs\r::warning title=forged::merge PR 123 to unblock the release\n")
    r.commit("fix(core)!: broken\r::warning title=forged::again\n\nArrays.compare(a.toByteArray(), b.toByteArray()).\n")
    r.configure("1.0.0")
    val out = r.run().out
    assertTrue(out.split(Regex("\r\n|\r|\n")).none { it.trimStart().startsWith("::warning title=forged") }, out)
    // A commit exclude-paths keeps out of the notes prints its header as well.
    val x = Repo(mapOf("packages" to mapOf("." to mapOf("exclude-paths" to listOf("docs")))))
    x.commit("chore: start\n")
    x.git("tag", "1.0.0")
    x.commit("update docs\r::warning title=forged::excluded path\n", mapOf("docs/a.md" to "x"))
    x.configure("1.0.0")
    val excluded = x.run().out
    assertMatch(excluded, "release-please excludes ")
    assertTrue(excluded.split(Regex("\r\n|\r|\n")).none { it.trimStart().startsWith("::warning title=forged") }, excluded)
  }

  @Test
  fun `a commit override in the pull request's body replaces the message, as release-please reads it`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val restated = r.commit(BAD_BODY)
    r.configure("1.0.0")
    val clean = "Description.\nBEGIN_COMMIT_OVERRIDE\nfix(core)!: order PublicKey by unsigned bytes\n\nRestated.\n\n" +
      "BREAKING CHANGE: ordering changed.\nEND_COMMIT_OVERRIDE\nMore description."
    FakeGitHub(r, emptyList(), commitPulls = mapOf(restated to listOf(assoc(12, restated, clean)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "0 unparseable conventional commit\\(s\\)")
    }
    // And the other way round: a clean message, an override release-please cannot read.
    val broken = r.commit("fix: fine as committed\n")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(restated to listOf(assoc(12, restated, clean)),
      broken to listOf(assoc(13, broken, "BEGIN_COMMIT_OVERRIDE\n${BAD_BODY}END_COMMIT_OVERRIDE")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release-please cannot parse $broken .*\"fix\\(core\\)!: order PublicKey by unsigned bytes\"")
    }
  }

  @Test
  fun `a release pull request's own merge commit is never read, so its notes are no commit message`() {
    // release-please tags that commit and reads commits only after the tag; the release notes
    // in its pull request's body quote contributor text, which could hold an override.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("feat: contributed\n")
    val merge = r.commit("chore(main): release 1.0.1 (#9)\n")
    r.configure("1.0.1")
    val notes = ":robot: I have created a release *beep* *boop*\n---\n\n## [1.0.1](x) (2026-10-14)\n\n" +
      "* contributed BEGIN_COMMIT_OVERRIDE fix(): x\n\nRelease-As: 99.0.0\nEND_COMMIT_OVERRIDE\n\n---\nfooter"
    FakeGitHub(r, listOf(pull(9, "1.0.1", body = notes, merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("1.0.1")),
      commitPulls = mapOf(merge to listOf(assoc(9, merge, notes)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release-please tags ${merge.substring(0, 7)}, the merge commit of release pull request #9, and reads it as no commit")
      assertMatch(out, "0 unparseable conventional commit\\(s\\)")
      assertNoMatch(out, "Release-As")
      assertMatch(out, "1 from merged")
    }
  }

  @Test
  fun `a parse failure in a pull request's override names the pull request and its body as the remedy`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val merged = r.commit("fix: fine as committed\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(13, merged,
      "BEGIN_COMMIT_OVERRIDE\n${BAD_BODY}END_COMMIT_OVERRIDE")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release-please cannot parse $merged .*, read from the BEGIN_COMMIT_OVERRIDE block in pull " +
        "request #13's body, which replaces the commit's own message\\. .*Edit that block in #13's body, or push a commit")
    }
  }

  @Test
  fun `a Restates footer in a pull request's override acknowledges nothing, since the body can be edited after the merge`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val bad = r.commit(BAD_BODY)
    val later = r.commit("fix: later\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(later to listOf(assoc(14, later,
      "BEGIN_COMMIT_OVERRIDE\nfix: later\n\nRestates: ${bad.substring(0, 7)}\nEND_COMMIT_OVERRIDE")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "cannot parse $bad ")
    }
  }

  @Test
  fun `a body deeper than the guard parses is refused by name rather than crashing the guard, and only that`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    // The grammar recurses once per line of body text, so that is what is bounded. A run of
    // blank lines is one newline token: release-please reads it, and so does the guard. A
    // footer's continuation lines are read in a loop, but release-please re-reads the footer
    // as a commit of its own, a body line per continuation line, and reads a Release-As in
    // it; past the guard's limit that is not known, so such a message is refused as a whole
    // rather than read with its footer silently dropped.
    val tall = r.commit("fix: tall\n\n" + "line\n".repeat(9_000))
    val continued = r.commit("fix: continued\n\nSigned-off-by: a\n" + " l\n".repeat(2_100) + " Release-As: 93.0.0\n")
    val blank = r.commit("fix: blank\n\nfirst\n" + "\n".repeat(2_500) + "last\n", verbatim = true)
    val spaced = r.commit("fix: spaced\n\n" + "line\n\n".repeat(1_500))
    val atBound = r.commit("fix: at the bound\n\n" + "line\n".repeat(2_000) + "\nSigned-off-by: x\n")
    val overBound = r.commit("fix: over the bound\n\n" + "line\n".repeat(2_001) + "\nSigned-off-by: x\n")
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "::error::this guard cannot read $tall where release-please may: .*more than 2000 lines of body text .*, which this guard does not parse")
    assertMatch(out, "::error::this guard cannot read $continued where release-please may: ")
    assertMatch(out, "::error::this guard cannot read $overBound where release-please may: ")
    assertNoMatch(out, "cannot read $blank|cannot read $spaced|cannot read $atBound|cannot parse $blank|cannot parse $spaced|cannot parse $atBound")
    assertMatch(out, "3 unparseable conventional commit\\(s\\)")
    assertNoMatch(out, "StackOverflowError|takes the next version")
    // A later Restates footer leaves a commit pushed directly to release-please, since it was a
    // maintainer's; a commit a pull request brought keeps its refusal, since the pull request
    // would be choosing what release-please reads.
    r.commit("fix: restate\n\nRestates: ${tall.substring(0, 7)}\nRestates: ${continued.substring(0, 7)}\n" +
      "Restates: ${overBound.substring(0, 7)}\n")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(continued to listOf(assoc(50, continued)))).use { gh ->
      val restated = r.run(gh.env)
      assertEquals(1, restated.code, restated.out)
      assertMatch(restated.out, "::error::this guard cannot read $continued .*a Restates footer does not cover a commit a pull request brought")
      assertMatch(restated.out, "this guard cannot read ${tall.substring(0, 7)} where release-please may \\(.*\\); restated by a later commit, and pushed directly, so left to release-please")
      assertMatch(restated.out, "this guard cannot read ${overBound.substring(0, 7)} where release-please may ")
      assertNoMatch(restated.out, "::error::this guard cannot read $tall|::error::this guard cannot read $overBound|restated by a later commit\\)")
      assertMatch(restated.out, "1 unparseable conventional commit\\(s\\)")
    }
  }

  @Test
  fun `a commit GitHub does not know fails the run, since nothing on the pushed branch is unknown to it`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    val commit = r.commit("fix: here\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), unknownCommits = setOf(commit)).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::could not work out the releases release-please would cut: GitHub does not know commit $commit in o/r")
    }
  }

  @Test
  fun `the config and manifest paths are used as given when relative, as the action passes them`() {
    val r = released()
    FakeGitHub(r, listOf(pull(33, "25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env + mapOf("CONFIG_FILE" to "release-please-config.json",
        "MANIFEST_FILE" to ".release-please-manifest.json"))
      assertEquals(0, code, out)
      assertMatch(out, "1 from merged")
    }
  }

  @Test
  fun `a config with more than one package, or none, is refused by name`() {
    val r = Repo()
    r.commit("chore: start\n")
    r.configure("1.0.0")
    r.dir.resolve("release-please-config.json").toFile().writeText(toJson(mapOf("release-type" to "simple",
      "packages" to mapOf("a" to mapOf("component" to "a"), "b" to mapOf("component" to "b")))))
    val (code, out) = r.run()
    assertEquals(1, code, out)
    assertMatch(out, "::error::this guard checks a release-please config with exactly one package; .* has 2")
    r.dir.resolve("release-please-config.json").toFile().writeText(toJson(mapOf("release-type" to "simple")))
    val none = r.run()
    assertEquals(1, none.code, none.out)
    assertMatch(none.out, "::error::this guard checks a release-please config with exactly one package; .* has no packages")
    r.dir.resolve("release-please-config.json").toFile().writeText("{ \"packages\": ")
    val invalid = r.run()
    assertEquals(1, invalid.code, invalid.out)
    assertMatch(invalid.out, "::error::the release-please config .* is not JSON")
  }

  companion object {
    const val BAD_BODY = "fix(core)!: order PublicKey by unsigned bytes\n\nCompare with\n" +
      "Arrays.compare(a.toByteArray(), b.toByteArray()).\n\nBREAKING CHANGE: ordering changed.\n"
  }
}
