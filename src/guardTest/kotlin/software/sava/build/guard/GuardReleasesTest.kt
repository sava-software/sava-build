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
    FakeGitHub(edited, listOf(pull(29, "25.12.2")),
      files = mapOf("$MERGE|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val older = edited.run(gh.env)
      assertEquals(1, older.code, older.out)
      assertMatch(older.out, "#29 is about to be tagged, but release-please would tag it \"25\\.12\\.2\", which is not \"25\\.12\\.1\"")
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
      assertNoMatch(out, "::notice::.*#27|::error::.*#27")
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
  fun `a version component past 2^53, which release-please rounds, is refused where it would be tagged, and still names a branch`() {
    // release-please's Number keeps an integer exact up to 2^53: past that it tags a rounded
    // version, which this guard refuses rather than predicts. A branch named with one is still
    // its branch, so the pull request is checked, not left alone.
    val r = released()
    FakeGitHub(r, listOf(pull(32, "25.12.1", title = "chore(main): release 1.0.9007199254740993"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #32 is about to be tagged, but its title names a version this " +
        "guard refuses \\(a version component past 2\\^53 in 1\\.0\\.9007199254740993, which release-please rounds\\)")
    }
    FakeGitHub(r, listOf(pull(33, "25.12.1-\$(id)", head = "release-p-v9007199254740993.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertNoMatch(out, "#33 is left alone")
      assertMatch(out, "::error::merged release pull request #33 is about to be tagged, but release-please would tag it " +
        "\"25\\.12\\.1-\\$\\(id\\)\"")
    }
    // A grouped title names no version, so the body's single section does, and it is refused
    // the same way, naming the pull request.
    val grouped = Repo()
    grouped.commit("chore: start\n")
    grouped.git("tag", "25.12.0")
    grouped.commit("fix: later\n")
    grouped.configure("25.12.1", mapOf("package-name" to Unset))
    FakeGitHub(grouped, listOf(pull(34, "25.12.1", title = "chore: release main", head = "release-please--branches--main",
      body = releaseBody("1.0.9007199254740993")))).use { gh ->
      val (code, out) = grouped.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #34 is about to be tagged, but its body's release section names a " +
        "version this guard refuses \\(a version component past 2\\^53 in 1\\.0\\.9007199254740993.*, which release-please rounds\\)")
    }
    // With a section, release-please leaves a pull request whose branch names another
    // component alone, whatever the section names: so does the guard, before refusing it.
    FakeGitHub(r, listOf(pull(35, "25.12.1", head = "release-please--branches--main--components--q",
      body = releaseBody("1.0.9007199254740993")))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::notice::merged release pull request #35 is left alone: its head branch's component \"q\" is not the " +
        "configured component \"p\"")
      assertMatch(out, "0 from merged")
    }
    // And a title past 2^53 is refused only where release-please would tag it: on a branch
    // that is not release-please's, or one of another component beside a section, the pull
    // request is left alone, as release-please leaves it after rounding the title.
    FakeGitHub(r, listOf(pull(36, "25.12.1", title = "chore(main): release 1.0.9007199254740993", head = "feature"),
      pull(37, "25.12.1", title = "chore(main): release 1.0.9007199254740993",
        head = "release-please--branches--main--components--q"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::notice::merged release pull request #36 is left alone: its head branch \"feature\" is not a " +
        "release-please branch name")
      assertMatch(out, "::notice::merged release pull request #37 is left alone: its head branch's component \"q\" is not the " +
        "configured component \"p\"")
      assertMatch(out, "0 from merged")
    }
    // A section past 2^53 under a title that names a plain version is not read at all:
    // release-please tags the title's version.
    FakeGitHub(r, listOf(pull(38, "25.12.1", body = releaseBody("1.0.9007199254740993"))),
      files = mapOf("$MERGE|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "#38 is about to be tagged")
    }
  }

  @Test
  fun `a version this guard refuses where the expectation comes from refuses the release, rather than leaving it unchecked`() {
    // A direct Release-As past 2^53, or release-as in the config past it: release-please
    // tags whatever the pull request names (its oracle tags 99.0.0 here), and the refused
    // version justifies nothing, so the release is refused rather than left unchecked.
    val r = Repo()
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    val huge = r.commit("chore: release\n\nRelease-As: 9007199254740993.0.0\n", emptyMap())
    val merge = r.commit("chore(main): release 99.0.0 (#86)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(86, "99.0.0", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #86 is about to be tagged, but the Release-As footer release-please " +
        "takes among its commits, that of ${huge.substring(0, 7)}, names a version this guard refuses \\(a version component " +
        "past 2\\^53 in 9007199254740993\\.0\\.0, which release-please rounds\\)")
      assertNoMatch(out, "#86's version is not checked")
    }
    val configured = Repo(mapOf("release-as" to "9007199254740993.0.0"))
    configured.configure("25.12.0")
    configured.commit("chore: start\n", configured.configFiles())
    configured.git("tag", "25.12.0")
    configured.commit("fix: later\n")
    val merge87 = configured.commit("chore(main): release 99.0.0 (#87)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    configured.configure("99.0.0")
    FakeGitHub(configured, listOf(pull(87, "99.0.0", merge = merge87)),
      files = mapOf("$merge87|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = configured.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #87 is about to be tagged, but release-as in " +
        "release-please-config\\.json at ${merge87.substring(0, 7)}\\^1 names a version this guard refuses \\(a version " +
        "component past 2\\^53 in 9007199254740993\\.0\\.0, which release-please rounds\\)")
    }
  }

  @Test
  fun `a manifest version this guard refuses refuses the next release, its tag found or not, rather than reading as none`() {
    // release-please bumps the manifest's version, rounded, whether or not a tag exists for
    // it; what that gives is not predicted here, and a release read as the first would go
    // unchecked.
    val r = Repo()
    r.configure("9007199254740993.0.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "9007199254740993.0.0")
    r.commit("fix: later\n")
    val merge = r.commit("chore(main): release 99.0.0 (#85)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(85, "99.0.0", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #85 is about to be tagged, but the manifest's version " +
        "9007199254740993\\.0\\.0 in \\.release-please-manifest\\.json at ${merge.substring(0, 7)}\\^1, which release-please " +
        "bumps, names a version this guard refuses \\(a version component past 2\\^53 in 9007199254740993\\.0\\.0, which " +
        "release-please rounds\\)")
      assertNoMatch(out, "#85's version is not checked")
    }
    // The same with no tag for the manifest's version and an unrelated one the nearest-tag
    // fallback finds: release-please still bumps the manifest's version.
    val untagged = Repo()
    untagged.configure("9007199254740993.0.0")
    untagged.commit("chore: start\n", untagged.configFiles())
    untagged.git("tag", "9007199254740994.0.0")
    untagged.commit("fix: later\n")
    val merge78 = untagged.commit("chore(main): release 99.0.0 (#78)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    untagged.configure("99.0.0")
    FakeGitHub(untagged, listOf(pull(78, "99.0.0", merge = merge78)),
      files = mapOf("$merge78|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = untagged.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #78 is about to be tagged, but the manifest's version " +
        "9007199254740993\\.0\\.0 in \\.release-please-manifest\\.json at ${merge78.substring(0, 7)}\\^1, which release-please " +
        "bumps, names a version this guard refuses")
      assertNoMatch(out, "#78's version is not checked")
    }
    // A valid manifest version with no tag of its own, beside a stray tag the nearest-tag
    // fallback finds: no reason to refuse; the version goes unchecked, as with no tag at all.
    val stray = Repo()
    stray.configure("25.12.0")
    stray.commit("chore: start\n", stray.configFiles())
    stray.git("tag", "2026.10")
    stray.commit("fix: later\n")
    val merge88 = stray.commit("chore(main): release 25.12.1 (#88)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    stray.configure("25.12.1")
    FakeGitHub(stray, listOf(pull(88, "25.12.1", merge = merge88)),
      files = mapOf("$merge88|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = stray.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "#88's version is not checked against the commits: there is no release to bump from")
      assertNoMatch(out, "names a version this guard refuses")
    }
  }

  @Test
  fun `a missing merge commit is refused only where release-please would tag it`() {
    // With skip-github-release set, or for a release that already exists, release-please tags
    // nothing, so the unread commits do not matter and nothing stands in its way.
    val missing = "e".repeat(40)
    val skipped = released(mapOf("skip-github-release" to true))
    FakeGitHub(skipped, listOf(pull(83, "25.12.1", merge = missing)),
      files = mapOf("$missing|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = skipped.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release pull requests not checked: skip-github-release is set")
      assertNoMatch(out, "is not in this checkout")
    }
    val done = released()
    FakeGitHub(done, listOf(pull(84, "25.12.1", merge = missing)), releases = setOf("25.12.1"),
      files = mapOf("$missing|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = done.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::notice::merged release pull request #84 is already released as \"25\\.12\\.1\"")
      assertNoMatch(out, "is not in this checkout")
    }
  }

  @Test
  fun `a grouped title names no version, so the body's single section does, as release-please reads it`() {
    // A grouped release pull request's branch names no component, which release-please
    // matches only against a package without one.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
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
      assertMatch(out, "::error::merged release pull request #30 is about to be tagged, but its title names a version this " +
        "guard refuses \\(unable to parse version string: 1, on which release-please fails\\)")
    }
    val open = released(version = "25.12.0")
    FakeGitHub(open, listOf(pull(31, "25.12.1", title = "chore(main): release 1", open = true))).use { gh ->
      val warned = open.run(gh.env)
      assertEquals(0, warned.code, warned.out)
      assertMatch(warned.out, "::warning::open release pull request #31: once merged, its title names a version this guard refuses")
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
      assertMatch(out, "::error::merged release pull request #45's merge commit e{7} is not in this checkout")
      assertNoMatch(out, "#45 is about to be tagged")
      assertMatch(out, "::error::merged release pull request #46 is about to be tagged, but release-please would tag it \"25\\.12\\.1-\\$\\(id\\)\"")
      assertMatch(out, "2 from merged")
      assertEquals(1, gh.calls().count { it.endsWith("ref=${gh.merges[46]}") }, gh.calls().joinToString("\n"))
    }
  }

  @Test
  fun `a release pull request's version must be the one the commits give, under always-bump-patch`() {
    // A plain version in the title and the manifest alike, with nothing in the commits up to
    // the merge to justify it: what an override edited after the guard read it, then edited
    // away, leaves. The config and manifest are committed, so the merge reads its own.
    val r = Repo()
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    r.commit("fix: later\n")
    val merge = r.commit("chore(main): release 99.0.0 (#60)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(60, "99.0.0", merge = merge)), files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #60 is about to be tagged, but its version \"99\\.0\\.0\" is not " +
        "\"25\\.12\\.1\", the patch bump of 25\\.12\\.0 that always-bump-patch gives; release-please computed it from commits " +
        "or pull request edits that are not there now")
      assertNoMatch(out, "could not be read|is not in the checkout")
    }
    // A Release-As footer pushed directly before the merge justifies it, as does release-as
    // in the config as of the merge.
    val justified = Repo()
    justified.configure("25.12.0")
    justified.commit("chore: start\n", justified.configFiles())
    justified.git("tag", "25.12.0")
    val carrier = justified.commit("chore: release 99.0.0\n\nRelease-As: 99.0.0\n", emptyMap())
    val merge61 = justified.commit("chore(main): release 99.0.0 (#61)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    justified.configure("99.0.0")
    FakeGitHub(justified, listOf(pull(61, "99.0.0", merge = merge61)),
      files = mapOf("$merge61|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = justified.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "from the Release-As footer of ${carrier.substring(0, 7)} .*, which was pushed directly")
      assertNoMatch(out, "#61 is about to be tagged|could not be read")
    }
    val configured = Repo(mapOf("release-as" to "99.0.0"))
    configured.configure("25.12.0")
    configured.commit("chore: start\n", configured.configFiles())
    configured.git("tag", "25.12.0")
    configured.commit("fix: later\n")
    val merge62 = configured.commit("chore(main): release 99.0.0 (#62)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    configured.configure("99.0.0")
    FakeGitHub(configured, listOf(pull(62, "99.0.0", merge = merge62)),
      files = mapOf("$merge62|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = configured.run(gh.env)
      assertEquals(0, code, out)
      assertNoMatch(out, "#62 is about to be tagged|could not be read")
    }
    // An open pull request lagging a just-pushed footer is a warning, cleared once release-please rewrites it.
    val open = released(version = "25.12.0")
    open.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    FakeGitHub(open, listOf(pull(63, "25.12.1", open = true)),
      files = mapOf("$HEAD_SHA|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = open.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::warning::open release pull request #63: once merged, its version \"25\\.12\\.1\" is not \"2\\.0\\.0\", " +
        "the version the Release-As footer of [0-9a-f]{7} gives; it comes from release-please's last run, or from an " +
        "edit since: a pull request that only predates a newer footer is rewritten by release-please once this run is " +
        "green, an edited title is not")
    }
  }

  @Test
  fun `a merged release pull request whose GitHub release exists is left to release-please to label, with nothing checked`() {
    // A run that released but failed to relabel the pull request leaves it pending:
    // release-please's create fails as a duplicate, it labels the pull request, and the guard
    // must not stand in the way. A tag alone is no release: release-please creates one on it,
    // so that case is checked like any other, and here passes: the commits up to the merge
    // give its version, whatever tag sits on the merge commit itself.
    val r = Repo()
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    r.commit("fix: later\n")
    val merge = r.commit("chore(main): release 25.12.1 (#70)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    r.git("tag", "25.12.1")
    r.configure("25.12.1")
    FakeGitHub(r, listOf(pull(70, "25.12.1", merge = merge)), releases = setOf("25.12.1")).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::notice::merged release pull request #70 is already released as \"25\\.12\\.1\" \\(a GitHub release " +
        "exists for that tag\\): release-please labels it tagged and tags nothing")
      assertMatch(out, "0 from merged")
    }
    FakeGitHub(r, listOf(pull(70, "25.12.1", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "#70 is about to be tagged|could not be read")
    }
  }

  @Test
  fun `a commit the guard could not read, left to release-please, leaves the version unchecked too`() {
    // release-please may read a Release-As footer in it, so a release pull request naming
    // another version than the patch bump may well be right.
    val r = Repo()
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    val deep = r.commit("fix: deep\n\n" + "line\n".repeat(2_500) + "\nRelease-As: 2.0.0\n")
    r.commit("fix: restate\n\nRestates: ${deep.substring(0, 7)}\n")
    val merge = r.commit("chore(main): release 2.0.0 (#80)\n", mapOf(".release-please-manifest.json" to manifestOf("2.0.0")))
    r.configure("2.0.0")
    FakeGitHub(r, listOf(pull(80, "2.0.0", merge = merge)), files = mapOf("$merge|.release-please-manifest.json" to manifestOf("2.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "#80's version is not checked against the commits: ${deep.substring(0, 7)} may carry a Release-As footer " +
        "this guard cannot read")
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "could not be read")
    }
    // A footer the guard did read in a newer commit, before the merge, is what release-please
    // takes, so it is known.
    val carrier = r.commit("chore: release 3.0.0\n\nRelease-As: 3.0.0\n", emptyMap())
    val merge81 = r.commit("chore(main): release 2.0.0 (#81)\n", mapOf(".release-please-manifest.json" to manifestOf("2.0.0")))
    FakeGitHub(r, listOf(pull(81, "2.0.0", merge = merge81)),
      files = mapOf("$merge81|.release-please-manifest.json" to manifestOf("2.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "#81 is about to be tagged, but its version \"2\\.0\\.0\" is not \"3\\.0\\.0\", the version the Release-As " +
        "footer of ${carrier.substring(0, 7)} gives")
    }
  }

  @Test
  fun `a merged release pull request is judged from the commits it was built from, not from what landed after its merge`() {
    // Merged 25.12.1, then a Release-As carrier pushed directly before the tagging run: the
    // release stands, and the carrier sets the next one, as release-please has it.
    val r = Repo()
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    r.commit("fix: in the release\n")
    val merge = r.commit("chore(main): release 25.12.1 (#90)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    val carrier = r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("25.12.1")
    FakeGitHub(r, listOf(pull(90, "25.12.1", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release-please takes the next version, version \"2\\.0\\.0\", from the Release-As footer of ${carrier.substring(0, 7)}")
      assertMatch(out, "release-please tags ${merge.substring(0, 7)}, the merge commit of release pull request #90")
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "#90 is about to be tagged|could not be read")
    }
    // The same through the config: release-as added after the merge applies to the next
    // release, as release-please has it, and release-as removed after the merge leaves the
    // release it produced standing. The merge is judged under the config as of the merge.
    val added = Repo()
    added.configure("25.12.0")
    added.commit("chore: start\n", added.configFiles())
    added.git("tag", "25.12.0")
    added.commit("fix: in the release\n")
    val merge97 = added.commit("chore(main): release 25.12.1 (#97)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    added.configure("25.12.1", mapOf("release-as" to "26.0.0"))
    added.commit("chore: the next release is 26.0.0\n", added.configFiles())
    FakeGitHub(added, listOf(pull(97, "25.12.1", merge = merge97)),
      files = mapOf("$merge97|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = added.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "#97 is about to be tagged|could not be read")
    }
    val removed = Repo()
    removed.configure("25.12.0", mapOf("release-as" to "26.0.0"))
    removed.commit("chore: start\n", removed.configFiles())
    removed.git("tag", "25.12.0")
    removed.commit("fix: in the release\n")
    val merge98 = removed.commit("chore(main): release 26.0.0 (#98)\n", mapOf(".release-please-manifest.json" to manifestOf("26.0.0")))
    removed.configure("26.0.0")
    removed.commit("chore: release-as is done\n", removed.configFiles())
    FakeGitHub(removed, listOf(pull(98, "26.0.0", merge = merge98)),
      files = mapOf("$merge98|.release-please-manifest.json" to manifestOf("26.0.0"))).use { gh ->
      val (code, out) = removed.run(gh.env)
      assertEquals(0, code, out)
      assertNoMatch(out, "#98 is about to be tagged|could not be read")
    }
    // The boundary cuts both ways: a version the pull request's own commits do not give is
    // refused even when a carrier pushed after the merge names it.
    val later = Repo()
    later.configure("25.12.0")
    later.commit("chore: start\n", later.configFiles())
    later.git("tag", "25.12.0")
    later.commit("fix: x\n")
    val merge2 = later.commit("chore(main): release 99.0.0 (#91)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    later.commit("chore: release 99.0.0\n\nRelease-As: 99.0.0\n", emptyMap())
    later.configure("99.0.0")
    FakeGitHub(later, listOf(pull(91, "99.0.0", merge = merge2)),
      files = mapOf("$merge2|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = later.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "#91 is about to be tagged, but its version \"99\\.0\\.0\" is not \"25\\.12\\.1\", the patch bump of 25\\.12\\.0")
    }
    // A tag pushed after the merge is not the base either: the base is the release the
    // manifest named as of the merge.
    val tagged = Repo()
    tagged.configure("25.12.0")
    tagged.commit("chore: start\n", tagged.configFiles())
    tagged.git("tag", "25.12.0")
    tagged.commit("fix: y\n")
    val merge99 = tagged.commit("chore(main): release 25.12.1 (#99)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    tagged.commit("chore: elsewhere\n")
    tagged.git("tag", "25.12.5")
    tagged.configure("25.12.1")
    FakeGitHub(tagged, listOf(pull(99, "25.12.1", merge = merge99)),
      files = mapOf("$merge99|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = tagged.run(gh.env)
      assertEquals(0, code, out)
      assertNoMatch(out, "#99 is about to be tagged|could not be read")
    }
    // A release pull request merged before release-please refreshed it for a newer carrier
    // names a version its own commits do not give, and is refused as such: release-please
    // would tag the stale title.
    val stale = Repo()
    stale.configure("25.12.0")
    stale.commit("chore: start\n", stale.configFiles())
    stale.git("tag", "25.12.0")
    stale.commit("fix: in the release\n")
    val late = stale.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    val merge88 = stale.commit("chore(main): release 25.12.1 (#88)\n", mapOf(".release-please-manifest.json" to manifestOf("25.12.1")))
    stale.configure("25.12.1")
    FakeGitHub(stale, listOf(pull(88, "25.12.1", merge = merge88)),
      files = mapOf("$merge88|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = stale.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "#88 is about to be tagged, but its version \"25\\.12\\.1\" is not \"2\\.0\\.0\", the version the Release-As " +
        "footer of ${late.substring(0, 7)} gives; release-please computed it from commits or pull request edits that are not " +
        "there now, or the pull request was merged before release-please refreshed it")
    }
    // A merge commit the checkout does not have (a run from behind the branch) refuses the
    // release: the commits it was built from, a contributor's footer among them included,
    // cannot be checked from here.
    val elsewhere = released()
    val missing = "e".repeat(40)
    FakeGitHub(elsewhere, listOf(pull(92, "25.12.1", merge = missing)),
      files = mapOf("$missing|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = elsewhere.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #92's merge commit e{7} is not in this checkout, so the commits " +
        "it was built from cannot be checked here, and release-please is about to tag it\\. Nothing has been tagged\\. Run " +
        "this workflow from the branch's current head once it has the merge \\(a push, or a dispatch\\), or remove " +
        "'autorelease: pending' from #92 to skip the release\\.")
      assertNoMatch(out, "#92 is about to be tagged")
    }
    // A config the checkout cannot read at the merge is judged with the config as checked out.
    val uncommitted = Repo()
    uncommitted.commit("chore: start\n")
    uncommitted.git("tag", "25.12.0")
    uncommitted.commit("fix: z\n")
    val merge89 = uncommitted.commit("chore(main): release 25.12.1 (#89)\n")
    uncommitted.configure("25.12.1")
    FakeGitHub(uncommitted, listOf(pull(89, "25.12.1", merge = merge89)),
      files = mapOf("$merge89|.release-please-manifest.json" to manifestOf("25.12.1"))).use { gh ->
      val (code, out) = uncommitted.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "merged release pull request #89's release-please config or manifest at ${merge89.substring(0, 7)}\\^1 could " +
        "not be read \\(.*\\); its version is judged with the config as checked out")
      assertMatch(out, "1 from merged")
    }
  }

  @Test
  fun `a footer a pull request brought is refused inside a merged release pull request's own commits too`() {
    // A contributor's footer that reached the release pull request (say through the override
    // race) must not pass at tagging time because a direct carrier landed after the merge.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
    val contributed = r.commit("feat: contributed\n\nRelease-As: 99.0.0\n")
    val merge = r.commit("chore(main): release 99.0.0 (#93)\n")
    r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(93, "99.0.0", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0")),
      commitPulls = mapOf(contributed to listOf(assoc(12, contributed)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #93 is about to be tagged, but the Release-As footer release-please " +
        "takes among its commits is that of ${contributed.substring(0, 7)}, which belongs to pull request #12: a pull request " +
        "may not choose the version")
    }
  }

  @Test
  fun `a pull request's commit the guard cannot read, inside a merged release's own commits, refuses the release`() {
    // The override race with a body too deep for the guard: outside HEAD's window, the commit
    // is read only for the merged release, where its unread footer still counts as the pull
    // request's.
    val r = Repo(mapOf("commit-search-depth" to 3))
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
    val contributed = r.commit("feat: contributed\n")
    r.commit("fix: one\n")
    r.commit("fix: two\n")
    val merge = r.commit("chore(main): release 99.0.0 (#95)\n")
    r.configure("99.0.0")
    val override = "BEGIN_COMMIT_OVERRIDE\nfeat: contributed\n\n" + "line\n".repeat(2_500) + "\nRelease-As: 99.0.0\nEND_COMMIT_OVERRIDE"
    FakeGitHub(r, listOf(pull(95, "99.0.0", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0")),
      commitPulls = mapOf(contributed to listOf(assoc(12, contributed, override)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #95 is about to be tagged, but a commit among its commits that this " +
        "guard cannot read, ${contributed.substring(0, 7)}, belongs to pull request #12 and may carry the Release-As footer " +
        "release-please takes: a pull request may not choose the version")
    }
  }

  @Test
  fun `a direct commit the guard cannot read hides no pull request's part behind it inside a merged release's commits`() {
    // release-please reads past the guard's limit: a direct commit it could not read may
    // carry no footer, and then the footer release-please took is the older one a pull
    // request brought, read or not. Both sit outside HEAD's window, inside the boundary.
    val r = Repo(mapOf("commit-search-depth" to 3))
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
    val contributed = r.commit("feat: contributed\n")
    r.commit("fix: big\n\n" + "line\n".repeat(2_500))
    val merge = r.commit("chore(main): release 99.0.0 (#96)\n")
    r.commit("fix: one\n")
    r.commit("fix: two\n")
    r.configure("99.0.0")
    val files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))
    val unread = "BEGIN_COMMIT_OVERRIDE\nfeat: contributed\n\n" + "line\n".repeat(2_500) + "\nRelease-As: 99.0.0\nEND_COMMIT_OVERRIDE"
    FakeGitHub(r, listOf(pull(96, "99.0.0", merge = merge)), files = files,
      commitPulls = mapOf(contributed to listOf(assoc(12, contributed, unread)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #96 is about to be tagged, but a commit among its commits that this " +
        "guard cannot read, ${contributed.substring(0, 7)}, belongs to pull request #12 and may carry the Release-As footer " +
        "release-please takes")
      assertNoMatch(out, "#96's version is not checked")
    }
    val read = "BEGIN_COMMIT_OVERRIDE\nfeat: contributed\n\nRelease-As: 99.0.0\nEND_COMMIT_OVERRIDE"
    FakeGitHub(r, listOf(pull(96, "99.0.0", merge = merge)), files = files,
      commitPulls = mapOf(contributed to listOf(assoc(12, contributed, read)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::merged release pull request #96 is about to be tagged, but the Release-As footer release-please " +
        "takes among its commits is that of ${contributed.substring(0, 7)}, which belongs to pull request #12: a pull request " +
        "may not choose the version")
    }
  }

  @Test
  fun `a direct footer behind a direct commit the guard cannot read leaves the version unchecked`() {
    // Either commit's footer is the one release-please takes, and both are the maintainer's.
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
    val carrier = r.commit("chore: release 3.0.0\n\nRelease-As: 3.0.0\n", emptyMap())
    val deep = r.commit("fix: deep\n\n" + "line\n".repeat(2_500))
    r.commit("fix: restate\n\nRestates: ${deep.substring(0, 7)}\n")
    r.configure("2.0.0")
    FakeGitHub(r, listOf(pull(82, "2.0.0")), files = mapOf("$MERGE|.release-please-manifest.json" to manifestOf("2.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "not checked against the commits: ${deep.substring(0, 7)} may carry a Release-As footer this guard cannot read")
      assertNoMatch(out, "footer of ${carrier.substring(0, 7)} gives")
    }
  }

  @Test
  fun `a merged release pull request's commits are read from its merge with the configured depth, not from HEAD's window`() {
    // With commit-search-depth 3, the carrier that set 99.0.0 has left HEAD's window once the
    // release merge and two fixes follow it; release-please read it when it built the pull
    // request, and so must the guard.
    val r = Repo(mapOf("commit-search-depth" to 3))
    r.commit("chore: start\n")
    r.git("tag", "25.12.0")
    r.commit("chore: release 99.0.0\n\nRelease-As: 99.0.0\n", emptyMap())
    r.commit("fix: one\n")
    r.commit("fix: two\n")
    val merge = r.commit("chore(main): release 99.0.0 (#94)\n")
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(94, "99.0.0", merge = merge)),
      files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "checked 3 commit\\(s\\) since")
      assertMatch(out, "1 from merged")
      assertNoMatch(out, "#94 is about to be tagged")
    }
  }

  @Test
  fun `under another versioning strategy, or before a first release, the version is not checked against the commits`() {
    val r = Repo(mapOf("versioning" to "default"))
    r.configure("25.12.0")
    r.commit("chore: start\n", r.configFiles())
    r.git("tag", "25.12.0")
    r.commit("fix: later\n")
    val merge = r.commit("chore(main): release 99.0.0 (#64)\n", mapOf(".release-please-manifest.json" to manifestOf("99.0.0")))
    r.configure("99.0.0")
    FakeGitHub(r, listOf(pull(64, "99.0.0", merge = merge)), files = mapOf("$merge|.release-please-manifest.json" to manifestOf("99.0.0"))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "#64's version is not checked against the commits: versioning is default")
      assertNoMatch(out, "could not be read")
    }
    val first = Repo()
    first.configure("0.0.0")
    first.commit("chore: start\n", first.configFiles())
    first.commit("feat: first\n")
    val merge65 = first.commit("chore(main): release 1.0.0 (#65)\n", mapOf(".release-please-manifest.json" to manifestOf("1.0.0")))
    first.configure("1.0.0")
    FakeGitHub(first, listOf(pull(65, "1.0.0", merge = merge65)), files = mapOf("$merge65|.release-please-manifest.json" to manifestOf("1.0.0"))).use { gh ->
      val (code, out) = first.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "#65's version is not checked against the commits: there is no release to bump from")
      assertNoMatch(out, "could not be read")
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
