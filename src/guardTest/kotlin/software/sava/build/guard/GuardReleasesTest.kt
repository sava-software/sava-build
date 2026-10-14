package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The release pull request checks: what release-please would tag, before it tags it. */
class GuardReleasesTest {

  private fun evilSections(version: String) =
    "* handle `<details><summary>$version</summary></details><details><summary>x: 1.0.0</summary></details>`"

  private fun overflowTo(ref: String) =
    ":robot: I have created a release *beep* *boop*\n---\n\n\nThis release is too large to preview in the pull " +
      "request body. View the full release notes here: https://github.com/o/r/blob/$ref/release-notes.md\n\n---\nfooter"

  @Test
  fun `a merged release pull request release-please would tag with the manifest's plain version passes`() {
    val r = released()
    FakeGitHub(r, listOf(pull(7, "25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "checked the releases release-please would cut: 1 from merged and 0 from open release pull request\\(s\\)")
      assertTrue(gh.calls().any { Regex("^/repos/o/r/pulls\\?state=closed&base=main&").containsMatchIn(it) }, gh.calls().joinToString("\n"))
    }
  }

  @Test
  fun `without TARGET_BRANCH, the branch the run is on is the target`() {
    val r = released()
    FakeGitHub(r, listOf(pull(8, "25.12.1", title = "chore(trunk): release 25.12.1",
      head = "release-please--branches--trunk--components--p", base = "trunk")), branch = "trunk").use { gh ->
      val (code, out) = r.run(gh.env + mapOf("TARGET_BRANCH" to "", "GITHUB_REF_NAME" to "trunk"))
      assertEquals(0, code, out)
      assertMatch(out, "1 from merged")
    }
    // TARGET_BRANCH, the repository's default branch, wins over the branch the run is on.
    FakeGitHub(r, listOf(pull(8, "25.12.1"))).use { gh ->
      val winner = r.run(gh.env + mapOf("TARGET_BRANCH" to "main", "GITHUB_REF_NAME" to "trunk"))
      assertEquals(0, winner.code, winner.out)
      assertTrue(gh.calls().none { it.contains("base=trunk") }, gh.calls().joinToString("\n"))
    }
  }

  @Test
  fun `with neither TARGET_BRANCH nor GITHUB_REF_NAME there is no target branch, and the run fails`() {
    val r = released()
    FakeGitHub(r, listOf(pull(40, "25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env + mapOf("TARGET_BRANCH" to "", "GITHUB_REF_NAME" to ""))
      assertEquals(1, code, out)
      assertMatch(out, "::error::could not work out the releases release-please would cut: .*no target branch")
    }
  }

  @Test
  fun `a title whose version is not X_Y_Z fails the run before release-please can tag it`() {
    val r = released()
    FakeGitHub(r, listOf(pull(9, "25.12.1", title = "chore(main): release 25.12.1-\$(id)"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #9 is about to be tagged, but release-please would tag it " +
        "\"25\\.12\\.1-\\$\\(id\\)\", which is not a plain X\\.Y\\.Z version, and not \"25\\.12\\.1\"")
      assertMatch(out, "Nothing has been tagged")
    }
  }

  @Test
  fun `a body holding the markup of a release section fails, whatever version it names`() {
    // Contributor commit text reaches the notes; with two sections release-please tags the body's.
    for (version in listOf("999.0.0", "9.9.9-\$(id)")) {
      val r = released()
      FakeGitHub(r, listOf(pull(10, "25.12.1", body = releaseBody("25.12.1", evilSections(version))))).use { gh ->
        val (code, out) = r.run(gh.env)
        assertEquals(1, code, out)
        assertMatch(out, "::error::merged release pull request #10 is about to be tagged, but its body holds the markup " +
          "of a release section \\(\"<details\"\\)")
      }
    }
  }

  @Test
  fun `escaped markup that release-please decodes into a section is refused in every spelling`() {
    for (markup in listOf("&lt;details&gt;", "&LT;summary", "&#60;details", "&#x3c;details", "&#X003C;/details", "</SUMMARY>")) {
      val r = released()
      FakeGitHub(r, listOf(pull(11, "25.12.1", body = releaseBody("25.12.1", "* see $markup x")))).use { gh ->
        val (code, out) = r.run(gh.env)
        assertEquals(1, code, out)
        assertMatch(out, "its body holds the markup of a release section")
      }
    }
    // Angle brackets that make no section are fine: generics in a subject, say.
    val r = released()
    FakeGitHub(r, listOf(pull(12, "25.12.1", body = releaseBody("25.12.1", "* support Map<String, List<Object>> & <b>bold</b>")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
    }
  }

  @Test
  fun `a body with the overflow message is refused, wherever it points`() {
    for (ref in listOf("$HEAD--release-notes", "pull/123/head", "main")) {
      val r = released()
      FakeGitHub(r, listOf(pull(13, "25.12.1", body = overflowTo(ref)))).use { gh ->
        val (code, out) = r.run(gh.env)
        assertEquals(1, code, out)
        assertMatch(out, "::error::merged release pull request #13 is about to be tagged, but its body carries release-please's " +
          "overflow message, so release-please reads the notes, and with them the release sections and version, from " +
          "https://github\\.com/o/r/blob/${Regex.escape(ref)}/release-notes\\.md")
      }
    }
    // Anywhere in the body, as release-please matches it, and with any character for the
    // message's period, as release-please's unescaped pattern has it.
    for (notes in listOf(
      "* This release is too large to preview in the pull request body. View the full release notes here: https://github.com/o/r/blob/main/release-notes.md",
      "* This release is too large to preview in the pull request bodyX View the full release notes here: https://github.com/o/r/blob/main/release-notes.md",
      "* This release is too large to preview in the pull request body\u0085 View the full release notes here: https://github.com/o/r/blob/main/release-notes.md",
    )) {
      val r = released()
      FakeGitHub(r, listOf(pull(14, "25.12.1", body = releaseBody("25.12.1", notes)))).use { gh ->
        val (code, out) = r.run(gh.env)
        assertEquals(1, code, out)
        assertMatch(out, "its body carries release-please's overflow message")
      }
    }
    // The body release-please writes on overflow is the message alone, with no delimiter line:
    // refused as such, before the delimiters are looked for. Open, it is a warning.
    val only = "This release is too large to preview in the pull request body. View the full release notes here: " +
      "https://github.com/o/r/blob/$HEAD--release-notes/release-notes.md"
    val merged = released()
    FakeGitHub(merged, listOf(pull(15, "25.12.1", body = only))).use { gh ->
      val (code, out) = merged.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #15 is about to be tagged, but its body carries release-please's overflow message")
      assertNoMatch(out, "left alone")
      assertMatch(out, "1 from merged")
    }
    val open = released(version = "25.12.0")
    FakeGitHub(open, listOf(pull(16, "25.12.1", body = only, open = true))).use { gh ->
      val (code, out) = open.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::warning::open release pull request #16: once merged, its body carries release-please's overflow message")
      assertMatch(out, "1 from open")
    }
  }

  @Test
  fun `the manifest at the release's own commit decides, not a checkout that predates the merge`() {
    // A re-run of an older release run checks out a commit before the release pull request merged.
    val stale = released(version = "25.12.0")
    FakeGitHub(stale, listOf(pull(28, "25.12.1")),
      files = mapOf("$MERGE|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val fine = stale.run(gh.env)
      assertEquals(0, fine.code, fine.out)
    }
    val edited = released(version = "25.12.0")
    FakeGitHub(edited, listOf(pull(29, "25.12.0")),
      files = mapOf("$MERGE|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val older = edited.run(gh.env)
      assertEquals(1, older.code, older.out)
      assertMatch(older.out, "#29 is about to be tagged, but release-please would tag it \"25\\.12\\.0\", which is not \"25\\.12\\.1\"")
    }
  }

  @Test
  fun `tags are spelled as release-please spells them, settings inherited from the top level included`() {
    val v = released(mapOf("include-v-in-tag" to true))
    FakeGitHub(v, listOf(pull(14, "25.12.1"))).use { gh ->
      val withV = v.run(gh.env)
      assertEquals(1, withV.code, withV.out)
      assertMatch(withV.out, "would tag it \"v25\\.12\\.1\", which is not a plain X\\.Y\\.Z version")
    }
    val c = released(mapOf("include-component-in-tag" to true))
    FakeGitHub(c, listOf(pull(15, "25.12.1"))).use { gh ->
      val withComponent = c.run(gh.env)
      assertEquals(1, withComponent.code, withComponent.out)
      assertMatch(withComponent.out, "would tag it \"p-25\\.12\\.1\"")
    }
  }

  @Test
  fun `release-please's own defaults put the component and a v into the tag, which the guard refuses`() {
    val r = released(mapOf("include-v-in-tag" to Unset, "include-component-in-tag" to Unset))
    FakeGitHub(r, listOf(pull(26, "25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "would tag it \"p-v25\\.12\\.1\", which is not a plain X\\.Y\\.Z version")
    }
  }

  @Test
  fun `release-please picks the pull requests by its configured labels, every one required`() {
    val r = released(mapOf("label" to "release-ready,approved"))
    FakeGitHub(r, listOf(
      pull(16, "25.12.1", title = "chore(main): release 25.12.1-\$(id)", labels = listOf("release-ready", "approved")),
      pull(17, "25.12.1", title = "chore(main): release 25.12.1-\$(id)", labels = listOf("release-ready")),
      pull(18, "25.12.1", title = "chore(main): release 25.12.1-\$(id)"),
    )).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "merged release pull request #16 is about to be tagged")
      assertNoMatch(out, "#17|#18")
      assertMatch(out, "1 from merged")
    }
  }

  @Test
  fun `the listing goes as deep as release-please reads, 200 merged pull requests, and no deeper`() {
    val r = released()
    FakeGitHub(r, filler(1000, 1100, merged = false) + filler(3000, 149) +
      pull(19, "25.12.1", title = "chore(main): release 25.12.1-\$(id)")).use { gh ->
      val found = r.run(gh.env)
      assertEquals(1, found.code, found.out)
      assertMatch(found.out, "merged release pull request #19 is about to be tagged")
    }
    // 50 unmerged first, so the 201st merged pull request shares a page with the 200th.
    FakeGitHub(r, filler(1000, 50, merged = false) + filler(3000, 200) +
      pull(20, "25.12.1", title = "chore(main): release 25.12.1-\$(id)")).use { gh ->
      val past = r.run(gh.env)
      assertEquals(0, past.code, past.out)
      assertMatch(past.out, "0 from merged")
      assertTrue(gh.calls().none { Regex("state=closed&.*&page=4$").containsMatchIn(it) }, gh.calls().joinToString("\n"))
    }
    // 200 merged pull requests filling whole pages end the listing before the next page.
    FakeGitHub(r, filler(3000, 200) + pull(41, "25.12.1", title = "chore(main): release 25.12.1-\$(id)")).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "0 from merged")
      assertTrue(gh.calls().none { Regex("state=closed&.*&page=3$").containsMatchIn(it) }, gh.calls().joinToString("\n"))
    }
  }

  @Test
  fun `an open release pull request that would be refused once merged is a warning, an unlabelled one is ignored`() {
    // On main the manifest still holds the last release; the open pull request's head holds the next.
    val r = released(version = "25.12.0")
    val head = mapOf("$HEAD_SHA|.release-please-manifest.json" to manifestOf("25.12.1"))
    FakeGitHub(r, listOf(pull(32, "25.12.1", open = true)), files = head).use { gh ->
      val legit = r.run(gh.env)
      assertEquals(0, legit.code, legit.out)
      assertNoMatch(legit.out, "::warning::")
      assertMatch(legit.out, "1 from open")
    }
    FakeGitHub(r, listOf(
      pull(21, "25.12.1", title = "chore(main): release 25.12.1-\$(id)", open = true),
      pull(22, "25.12.1", title = "chore(main): release 25.12.1-\$(id)", open = true, labels = emptyList(), head = "feature"),
    ), files = head).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::warning::open release pull request #21: once merged, release-please would tag it \"25\\.12\\.1-\\$\\(id\\)\"")
      assertNoMatch(out, "#22")
    }
  }

  @Test
  fun `a GitHub API failure fails the run rather than skipping the check`() {
    val r = released()
    FakeGitHub(r, emptyList(), fail = 502).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::could not read from GitHub \\(.*\\); re-run the job")
    }
  }

  @Test
  fun `a pull request release-please would not tag is left alone, with a notice saying why`() {
    val r = released()
    FakeGitHub(r, listOf(
      pull(23, "25.12.1", title = "Release everything"),
      pull(24, "25.12.1", head = "feature"),
      pull(25, "25.12.1", head = "release-please--branches--main--components--q"),
      pull(26, "25.12.1", body = "no delimiter here"),
      // An autorelease branch name whose version release-please cannot parse names no branch.
      pull(30, "25.12.1", head = "release-v1"),
      // No section in the body: release-please tags the title's version without looking at the
      // branch's component, so this one is checked, and passes.
      pull(27, "25.12.1", head = "release-please--branches--main--components--q",
        body = ":robot: I have created a release *beep* *boop*\n---\n\nplain notes\n\n---\nfooter"),
    )).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::notice::merged release pull request #23 is left alone: its title \"Release everything\" does not match")
      assertMatch(out, "::notice::merged release pull request #24 is left alone: its head branch \"feature\" is not a release-please branch name")
      assertMatch(out, "::notice::merged release pull request #25 is left alone: its head branch's component \"q\" is not the configured component \"p\"")
      assertMatch(out, "::notice::merged release pull request #26 is left alone: its body has no '---' line")
      assertMatch(out, "::notice::merged release pull request #30 is left alone: its head branch \"release-v1\" is not a release-please branch name")
      assertNoMatch(out, "#27")
      assertMatch(out, "1 from merged")
    }
  }

  @Test
  fun `section markup is refused before the title or branch could leave the pull request alone`() {
    // With sections in the body release-please reads the version from them, whatever the
    // branch's component; with a title it cannot read it tags nothing, but the guard does not
    // predict that far once the markup is there.
    val r = released()
    FakeGitHub(r, listOf(
      pull(28, "25.12.1", head = "release-please--branches--main--components--q", body = releaseBody("25.12.1", evilSections("9.9.9"))),
      pull(29, "25.12.1", title = "Release everything", body = releaseBody("25.12.1", evilSections("9.9.9"))),
    )).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #28 is about to be tagged, but its body holds the markup of a release section")
      assertMatch(out, "::error::merged release pull request #29 is about to be tagged, but its body holds the markup of a release section")
      assertNoMatch(out, "left alone")
    }
  }

  @Test
  fun `a pull-request-title-pattern, the package setting first, is read as release-please reads it`() {
    for (config in listOf<Map<String, Any?>>(
      mapOf("pull-request-title-pattern" to "release\${component}: \${version}"),
      mapOf("packages" to mapOf("." to mapOf("pull-request-title-pattern" to "release\${component}: \${version}")),
        "pull-request-title-pattern" to "chore\${scope}: release\${component} \${version}"),
    )) {
      val ok = released(config)
      FakeGitHub(ok, listOf(pull(24, "25.12.1", title = "release: 25.12.1"))).use { gh ->
        val good = ok.run(gh.env)
        assertEquals(0, good.code, good.out)
        assertMatch(good.out, "1 from merged")
      }
      val bad = released(config)
      FakeGitHub(bad, listOf(pull(25, "25.12.1", title = "release: 25.12.1-\$(id)"))).use { gh ->
        val evil = bad.run(gh.env)
        assertEquals(1, evil.code, evil.out)
        assertMatch(evil.out, "#25 is about to be tagged, but release-please would tag it \"25\\.12\\.1-\\$\\(id\\)\"")
      }
    }
  }

  @Test
  fun `a grouped title names no version, so the body's single section does, as release-please reads it`() {
    // A grouped release pull request's branch names no component, which release-please
    // matches only against a package without one.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    r.commit("fix: later\n")
    r.configure("25.12.1", mapOf("package-name" to Unset))
    FakeGitHub(r, listOf(pull(27, "25.12.1", title = "chore: release main", head = "release-please--branches--main"),
      pull(28, "25.12.1", title = "chore: release main", head = "release-please--branches--main",
        body = releaseBody("9.9.9")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertNoMatch(out, "#27 is about to be tagged")
      assertMatch(out, "#28 is about to be tagged, but release-please would tag it \"9\\.9\\.9\", which is not \"25\\.12\\.1\"")
      assertMatch(out, "2 from merged")
    }
  }

  @Test
  fun `a title version release-please cannot read makes release-please fail, which is reported as such`() {
    val r = released()
    FakeGitHub(r, listOf(pull(30, "25.12.1", title = "chore(main): release 1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #30 is about to be tagged, but release-please fails on its title " +
        "\\(unable to parse version string: 1\\)")
    }
    val open = released(version = "25.12.0")
    FakeGitHub(open, listOf(pull(31, "25.12.1", title = "chore(main): release 1", open = true))).use { gh ->
      val warned = open.run(gh.env)
      assertEquals(0, warned.code, warned.out)
      assertMatch(warned.out, "::warning::open release pull request #31: once merged, release-please fails on its title")
    }
  }

  @Test
  fun `a merge commit with no readable manifest names the pull request, without asking for a re-run`() {
    val r = released()
    FakeGitHub(r, listOf(pull(36, "25.12.1")), files = mapOf("$MERGE|.release-please-manifest.json" to null)).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release pull request #36's merge commit has no readable \\.release-please-manifest\\.json")
      assertNoMatch(out, "re-run the job")
    }
    FakeGitHub(r, listOf(pull(37, "25.12.1")), files = mapOf("$MERGE|.release-please-manifest.json" to "{ nope")).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "#37's merge commit has no readable \\.release-please-manifest\\.json \\(.*is not JSON")
    }
    val open = released(version = "25.12.0")
    FakeGitHub(open, listOf(pull(39, "25.12.1", open = true)),
      files = mapOf("$HEAD_SHA|.release-please-manifest.json" to null)).use { gh ->
      val unread = open.run(gh.env)
      assertEquals(0, unread.code, unread.out)
      assertMatch(unread.out, "::warning::open release pull request #39 not checked: \\.release-please-manifest\\.json at its head could not be read")
      assertNoMatch(unread.out, "would tag it")
    }
  }

  @Test
  fun `a rate-limited or failing API asks for a re-run, on every read or at a merge commit's manifest`() {
    val r = released()
    // A redirect is not followed either, since the token would travel with it.
    for (options in listOf<Pair<Int?, Map<String, Int>>>(
      403 to emptyMap(),
      null to mapOf("$MERGE|.release-please-manifest.json" to 403),
      null to mapOf("$MERGE|.release-please-manifest.json" to 502),
      null to mapOf("$MERGE|.release-please-manifest.json" to 302),
    )) {
      FakeGitHub(r, listOf(pull(46, "25.12.1")), fail = options.first, failing = options.second).use { gh ->
        val (code, out) = r.run(gh.env)
        assertEquals(1, code, out)
        assertMatch(out, "::error::could not read from GitHub \\(.*HTTP (403|502|302).*\\); re-run the job")
        assertNoMatch(out, "no readable|does not exist")
      }
    }
  }

  @Test
  fun `two merged release pull requests are checked and reported each, and the manifest read once per commit`() {
    val r = released()
    FakeGitHub(r, listOf(pull(45, "25.12.1", merge = "e".repeat(40)),
      pull(46, "25.12.1", title = "chore(main): release 25.12.1-\$(id)"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release pull request #45's merge commit has no readable \\.release-please-manifest\\.json \\(.*does not exist on \"e{40}\"")
      assertNoMatch(out, "#45 is about to be tagged")
      assertMatch(out, "::error::merged release pull request #46 is about to be tagged, but release-please would tag it \"25\\.12\\.1-\\$\\(id\\)\"")
      assertMatch(out, "2 from merged")
      assertEquals(1, gh.calls().count { it.endsWith("ref=$MERGE") }, gh.calls().joinToString("\n"))
    }
  }

  @Test
  fun `skip-github-release means release-please tags nothing, so nothing is checked`() {
    val r = released(mapOf("skip-github-release" to true))
    FakeGitHub(r, listOf(pull(47, "25.12.1", title = "chore(main): release 25.12.1-\$(id)"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release pull requests not checked: skip-github-release is set")
      assertMatch(out, "0 from merged and 0 from open")
    }
  }

  @Test
  fun `without GITHUB_REPOSITORY the releases are not checked, and the run says so`() {
    val r = released()
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "releases not checked: GITHUB_REPOSITORY is not set")
  }
}
