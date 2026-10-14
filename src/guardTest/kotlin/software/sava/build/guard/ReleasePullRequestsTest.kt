package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The pieces of release-please the release check ports, each against release-please's own behaviour. */
class ReleasePullRequestsTest {

  @Test
  fun `the title pattern is generated as release-please generates it`() {
    val default = ReleasePullRequests.parseTitle("chore(main): release 25.12.1", null, false)!!
    assertEquals("25.12.1", default.version())
    assertEquals("main", default.branch())
    assertEquals("", default.component())
    val component = ReleasePullRequests.parseTitle("chore: release p 1.2.3-rc.1", null, false)!!
    assertEquals("p", component.component())
    assertEquals("1.2.3-rc.1", component.version())
    assertEquals("1.2.3", ReleasePullRequests.parseTitle("chore: release v1.2.3", null, false)!!.version())
    assertNull(ReleasePullRequests.parseTitle("Release everything", null, false))
    assertNull(ReleasePullRequests.parseTitle("chore(main): release 25.12.1\nmore", null, false))
    // Only the first occurrence of a bracket is escaped, as in release-please.
    val custom = ReleasePullRequests.parseTitle("[release] p: 1.0.0", "[release]\${component}: \${version}", false)!!
    assertEquals("1.0.0", custom.version())
    assertEquals("p", custom.component())
    val grouped = ReleasePullRequests.parseTitle("chore: release main", "chore: release \${branch}", false)!!
    assertNull(grouped.version())
    assertEquals("main", grouped.branch())
    // JavaScript's '.' takes U+0085, which Java's does not.
    assertEquals("25.12.1-x\u0085y", ReleasePullRequests.parseTitle("chore(main): release 25.12.1-x\u0085y", null, false)!!.version())
  }

  @Test
  fun `a release-please branch name yields its component, any other name nothing`() {
    assertEquals("p", ReleasePullRequests.branchComponent("release-please--branches--main--components--p"))
    assertEquals("", ReleasePullRequests.branchComponent("release-please--branches--main"))
    assertEquals("g", ReleasePullRequests.branchComponent("release-please--branches--main--groups--g"))
    // The autorelease spelling is never read into a release-please--branches name.
    assertEquals("p-v1.0.0", ReleasePullRequests.branchComponent("release-please--branches--main--components--p-v1.0.0"))
    assertEquals("p", ReleasePullRequests.branchComponent("release-p-v1.2.3"))
    assertEquals("", ReleasePullRequests.branchComponent("release-v1.2.3"))
    // An autorelease name whose version release-please cannot parse names no branch to it.
    assertNull(ReleasePullRequests.branchComponent("release-v1"))
    assertNull(ReleasePullRequests.branchComponent("release-p-v2"))
    // JavaScript's '.' takes U+0085 in a branch name as in a title.
    assertEquals("p\u0085x", ReleasePullRequests.branchComponent("release-please--branches--main--components--p\u0085x"))
    assertEquals("p", ReleasePullRequests.branchComponent("release-please/branches/main/components/p"))
    assertEquals("", ReleasePullRequests.branchComponent("release-please/branches/main"))
    assertNull(ReleasePullRequests.branchComponent("feature"))
    assertNull(ReleasePullRequests.branchComponent("release-please--branch--main"))
  }

  @Test
  fun `a version is the first X_Y_Z in the text, with its pre-release and build, as release-please parses it`() {
    assertEquals("25.12.1", ReleasePullRequests.parseVersion("25.12.1"))
    assertEquals("25.12.1-\$(id)", ReleasePullRequests.parseVersion("25.12.1-\$(id)"))
    // The build part runs to the end of the line, as release-please's regex has it.
    assertEquals("1.2.3-rc.1+build.5 trailing", ReleasePullRequests.parseVersion("v1.2.3-rc.1+build.5 trailing"))
    assertEquals("7.0.0", ReleasePullRequests.parseVersion("version 007.0.0 please"))
    // An empty build part is dropped, as release-please's toString drops it.
    assertEquals("1.2.3", ReleasePullRequests.parseVersion("1.2.3+"))
    assertThrows(IllegalArgumentException::class.java) { ReleasePullRequests.parseVersion("1") }
  }

  @Test
  fun `the body splits at its delimiters as release-please splits it`() {
    val parts = ReleasePullRequests.splitBody(releaseBody("1.2.3"))!!
    assertEquals(":robot: I have created a release *beep* *boop*", parts.header())
    assertEquals("\n\n## [1.2.3](https://github.com/o/r/compare/1.0.0...1.2.3) (2026-10-13)\n\n\n### Bug Fixes\n\n* later\n", parts.content())
    assertEquals("This PR was generated with [Release Please](https://github.com/googleapis/release-please).", parts.footer())
    assertNull(ReleasePullRequests.splitBody("no delimiter"))
    // With one delimiter the last line stands in for the second, as in release-please.
    val single = ReleasePullRequests.splitBody("head\r\n---\r\ncontent\r\nmore")!!
    assertEquals("content", single.content())
    assertEquals("", single.footer())
    // A delimiter on the last line leaves nothing, as JavaScript's slice has it.
    val last = ReleasePullRequests.splitBody("Release notes below\n---")!!
    assertEquals("Release notes below", last.header())
    assertEquals("", last.content())
    assertEquals("", last.footer())
  }

  @Test
  fun `the section markup pattern catches tags and their escaped spellings only`() {
    for (markup in listOf("<details>", "</Details>", "<SUMMARY class=x>", "&lt;details", "&LT;summary", "&#60;details",
      "&#0060;/details", "&#x3c;summary", "&#X003C;details", "&lt;/summary")) {
      assertEquals(true, ReleasePullRequests.SECTION_MARKUP.matcher("* see $markup here").find(), markup)
    }
    for (text in listOf("* Map<String, List<Object>>", "* a <b>bold</b> detail", "* details & summary", "* &lt;b&gt; details")) {
      assertEquals(false, ReleasePullRequests.SECTION_MARKUP.matcher(text).find(), text)
    }
  }

  @Test
  fun `encodeURIComponent keeps what JavaScript keeps`() {
    assertEquals("a-b_c.d!e~f*g'h(i)j", GitHub.encode("a-b_c.d!e~f*g'h(i)j"))
    assertEquals("%2F%3F%26%3D%2B%20%C3%A9", GitHub.encode("/?&=+ é"))
  }

  @Test
  fun `the JSON reader reads what the guard meets and refuses the rest`() {
    val value = Json.asObject(Json.parse("{\"a\": [1, 2.5, -3e2, true, null, \"s\\u0041\\n\"], \"b\": {}}"), "test")
    assertEquals(listOf(1L, 2.5, -300.0, true, null, "sA\n"), value["a"])
    assertEquals(emptyMap<String, Any>(), value["b"])
    for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "nul", "\"unterminated", "1 2", "{\"a\":1}x")) {
      assertThrows(IllegalArgumentException::class.java, { Json.parse(bad) }, bad)
    }
  }
}
