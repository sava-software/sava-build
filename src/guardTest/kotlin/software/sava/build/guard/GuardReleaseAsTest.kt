package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Release-As rule: the footer release-please would take must come from a direct push. */
class GuardReleaseAsTest {

  private fun repoWithRelease(): Repo {
    val r = Repo()
    r.commit("chore: start\n")
    r.git("tag", "1.0.0")
    return r
  }

  @Test
  fun `a Release-As footer on a commit a pull request merged fails the run naming the pull request`() {
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n\nRelease-As: 99.0.0\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(12, merged)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release-please takes the next version, version \"99\\.0\\.0\", from the Release-As footer " +
        "of ${merged.substring(0, 7)} \\(\"99\\.0\\.0\"\\), which belongs to pull request #12: a pull request may not choose the version")
      assertMatch(out, "Push a commit with the Release-As footer you want to the target branch directly")
    }
  }

  @Test
  fun `the same footer on a commit pushed directly passes, and says so`() {
    val r = repoWithRelease()
    val direct = r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("1.0.0")
    FakeGitHub(r, emptyList()).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release-please takes the next version, version \"2\\.0\\.0\", from the Release-As footer of " +
        "${direct.substring(0, 7)} \\(\"2\\.0\\.0\"\\), which was pushed directly")
      assertNoMatch(out, "::error::")
    }
  }

  @Test
  fun `the newest footer wins, as release-please takes the first it finds`() {
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n\nRelease-As: 99.0.0\n")
    val direct = r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(12, merged)))).use { gh ->
      val overridden = r.run(gh.env)
      assertEquals(0, overridden.code, overridden.out)
      assertMatch(overridden.out, "the Release-As footer of ${merged.substring(0, 7)} \\(\"99\\.0\\.0\"\\) is overridden by " +
        "the newer one of ${direct.substring(0, 7)}")
    }
    // The other way round, the pull request's footer is the newest and is what release-please takes.
    val later = r.commit("feat: contributed again\n\nRelease-As: 98.0.0\n")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(12, merged)), later to listOf(assoc(13, later)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*from the Release-As footer of ${later.substring(0, 7)} .*which belongs to pull request #13")
      assertMatch(out, "the Release-As footer of ${merged.substring(0, 7)} .* is overridden by the newer one of ${later.substring(0, 7)}")
    }
  }

  @Test
  fun `a footer in a pull request's commit override is read as release-please reads it, and refused`() {
    // release-please replaces the commit message with the BEGIN_COMMIT_OVERRIDE block of the
    // pull request it reads the commit with.
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n")
    r.configure("1.0.0")
    val override = "Some description.\n\nBEGIN_COMMIT_OVERRIDE\nfeat: contributed\n\nRelease-As: 99.0.0\nEND_COMMIT_OVERRIDE\n"
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(12, merged, override)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::release-please takes the next version, version \"99\\.0\\.0\", from the Release-As footer " +
        "of ${merged.substring(0, 7)} \\(\"99\\.0\\.0\", from the commit override in pull request #12's body\\), which " +
        "belongs to pull request #12")
    }
  }

  @Test
  fun `the merge pull request is picked wherever GitHub lists it, when it alone merged there`() {
    // A closed pull request the commit also belongs to is listed first; the one whose merge
    // commit it is wins, as in release-please, so its override, not the first's, is read.
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(
      assoc(30, null, "BEGIN_COMMIT_OVERRIDE\nfeat: x\n\nRelease-As: 95.0.0\nEND_COMMIT_OVERRIDE"),
      assoc(31, merged, "BEGIN_COMMIT_OVERRIDE\nfeat: y\n\nRelease-As: 94.0.0\nEND_COMMIT_OVERRIDE"),
    ))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*version \"94\\.0\\.0\".*from the commit override in pull request #31's body.*belongs to pull request #31")
    }
  }

  @Test
  fun `a labelled merged pull request release-please does not read as a release is read like any other`() {
    // The release label alone does not make release-please tag a merge commit: without a
    // delimiter line in the body it passes the pull request over, and reads its commit, and
    // the commit override in its body, when building the next release.
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n")
    r.configure("1.0.0")
    val override = "No delimiter here.\nBEGIN_COMMIT_OVERRIDE\nfeat: x\n\nRelease-As: 90.0.0\nEND_COMMIT_OVERRIDE"
    FakeGitHub(r, listOf(pull(41, "1.0.1", title = "feat: contributed", body = override, head = "feature", merge = merged)),
      commitPulls = mapOf(merged to listOf(assoc(41, merged, override)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*version \"90\\.0\\.0\".*from the commit override in pull request #41's body")
      assertMatch(out, "::notice::merged release pull request #41 is left alone: its body has no '---' line")
      assertNoMatch(out, "reads it as no commit")
    }
  }

  @Test
  fun `the merge pull request is matched within a page of commit-batch-size, as release-please pages its history`() {
    // Two commits, both listing pull requests #30 and #31, of which #31 names the newer commit
    // as its merge commit: in one page of ten the merge commit is claimed twice, so the first
    // pull request is read; in pages of one it is claimed once, so #31 is.
    val r = repoWithRelease()
    val older = r.commit("feat: older\n")
    val newer = r.commit("feat: newer\n")
    val pulls = listOf(assoc(30, null, "BEGIN_COMMIT_OVERRIDE\nfeat: x\n\nRelease-As: 92.0.0\nEND_COMMIT_OVERRIDE"),
      assoc(31, newer, "BEGIN_COMMIT_OVERRIDE\nfeat: y\n\nRelease-As: 91.0.0\nEND_COMMIT_OVERRIDE"))
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(older to pulls, newer to pulls)).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*version \"92\\.0\\.0\".*from the commit override in pull request #30's body")
    }
    r.dir.resolve("release-please-config.json").toFile().writeText(toJson(mapOf("release-type" to "simple",
      "include-v-in-tag" to false, "include-component-in-tag" to false, "commit-batch-size" to 1,
      "packages" to mapOf("." to mapOf("package-name" to "p")))))
    FakeGitHub(r, emptyList(), commitPulls = mapOf(older to pulls, newer to pulls)).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*version \"91\\.0\\.0\".*from the commit override in pull request #31's body")
    }
  }

  @Test
  fun `the override comes from the pull request release-please picks for the commit`() {
    // A commit a pull request brought through a merge commit is read with that pull request,
    // the first it belongs to, though its merge commit is another; a commit two pull requests
    // claim as their merge commit is read with the first of them.
    val r = repoWithRelease()
    val branchCommit = r.commit("feat: on the branch\n")
    val shared = r.commit("feat: shared merge commit\n")
    r.configure("1.0.0")
    val override = "BEGIN_COMMIT_OVERRIDE\nfeat: x\n\nRelease-As: 97.0.0\nEND_COMMIT_OVERRIDE"
    FakeGitHub(r, emptyList(), commitPulls = mapOf(
      branchCommit to listOf(assoc(20, "f".repeat(40), override)),
      shared to listOf(assoc(21, shared, "BEGIN_COMMIT_OVERRIDE\nfeat: y\n\nRelease-As: 96.0.0\nEND_COMMIT_OVERRIDE"),
        assoc(22, shared, override)),
    )).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::.*version \"96\\.0\\.0\".*from the commit override in pull request #21's body.*belongs to pull request #21")
      assertMatch(out, "the Release-As footer of ${branchCommit.substring(0, 7)} \\(\"97\\.0\\.0\", from the commit override in " +
        "pull request #20's body\\) is overridden by the newer one of ${shared.substring(0, 7)}")
    }
  }

  @Test
  fun `a footer release-please does not read sets nothing, a dropped commit's or a body line`() {
    val r = repoWithRelease()
    // In the body, before more paragraphs: body text to the grammar, not a footer.
    val body = r.commit("feat: contributed\n\nRelease-As: 99.0.0\n\nMore text after.\n")
    // On a commit release-please drops, the footer goes with it.
    val dropped = r.commit("fix(core)!: broken\n\nArrays.compare(a.toByteArray(), b.toByteArray()).\n\nRelease-As: 98.0.0\n")
    r.commit("fix: restate\n\nRestates: ${dropped.substring(0, 7)}\n")
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), commitPulls = mapOf(body to listOf(assoc(12, body)), dropped to listOf(assoc(13, dropped)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertNoMatch(out, "Release-As footer")
    }
  }

  @Test
  fun `release-as in the config wins, so no footer is checked`() {
    val r = repoWithRelease()
    val merged = r.commit("feat: contributed\n\nRelease-As: 99.0.0\n")
    r.configure("1.0.0", mapOf("release-as" to "3.0.0"))
    FakeGitHub(r, emptyList(), commitPulls = mapOf(merged to listOf(assoc(12, merged)))).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "release-as is set to \"3\\.0\\.0\" in .*release-please-config\\.json: release-please takes the next " +
        "version from there and reads no Release-As footer")
    }
  }

  @Test
  fun `without GITHUB_REPOSITORY the footer's origin is not checked, with a warning`() {
    val r = repoWithRelease()
    val direct = r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("1.0.0")
    val (code, out) = r.run()
    assertEquals(0, code, out)
    assertMatch(out, "::warning::the Release-As footer of ${direct.substring(0, 7)} \\(\"2\\.0\\.0\"\\) sets the next version, " +
      "but whether ${direct.substring(0, 7)} was pushed directly or belongs to a pull request is not checked")
  }

  @Test
  fun `a footer that is not a plain version, or none at all, is a warning before release-please reads it`() {
    val r = repoWithRelease()
    r.commit("chore: release\n\nRelease-As: v2.0.0-rc1\n", emptyMap())
    r.configure("1.0.0")
    FakeGitHub(r, emptyList()).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::warning::the Release-As footer of [0-9a-f]{7} \\(\"v2\\.0\\.0-rc1\"\\) is read by release-please as " +
        "version \"2\\.0\\.0-rc1\", not a plain X\\.Y\\.Z version")
    }
    r.commit("chore: release\n\nRelease-As: soon\n", emptyMap())
    FakeGitHub(r, emptyList()).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(0, code, out)
      assertMatch(out, "::warning::the Release-As footer of [0-9a-f]{7} \\(\"soon\"\\) names a version this guard refuses " +
        "\\(unable to parse version string: soon, on which release-please fails\\)")
    }
  }

  @Test
  fun `the pull request lookup failing asks for a re-run`() {
    val r = repoWithRelease()
    r.commit("chore: release 2.0.0\n\nRelease-As: 2.0.0\n", emptyMap())
    r.configure("1.0.0")
    FakeGitHub(r, emptyList(), fail = 502).use { gh ->
      val (code, out) = r.run(gh.env)
      assertEquals(1, code, out)
      assertMatch(out, "::error::could not read from GitHub \\(.*HTTP 502.*\\); re-run the job\\.")
    }
  }
}
