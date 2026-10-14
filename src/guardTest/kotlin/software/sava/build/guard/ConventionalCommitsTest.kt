package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The grammar against the golden corpus recorded from release-please 17.6.0's own parser
 * (`@conventional-commits/parser` 0.4.1 driven by release-please's splitMessages and
 * toConventionalChangelogFormat): every commit message of the sava repositories, reduced
 * to its distinct shapes, plus hand-written edge cases. For each message the corpus holds
 * the parts release-please splits it into and, per part, the message the parser throws or
 * the header and Release-As footer release-please reads.
 */
class ConventionalCommitsTest {

  @Test
  fun `every corpus message splits and parses as release-please's parser did`() {
    val corpus = javaClass.getResourceAsStream("parser-corpus.json")!!.use { Json.parse(String(it.readAllBytes())) }
    var parts = 0
    val mismatches = mutableListOf<String>()
    for (entry in Json.asArray(corpus, "corpus").map { Json.asObject(it, "entry") }) {
      val message = entry["message"] as String
      val expectedParts = Json.asArray(entry["parts"], "parts").map { Json.asObject(it, "part") }
      val actualParts = ConventionalCommits.splitMessages(message)
      if (actualParts.size != expectedParts.size) {
        mismatches.add("${entry["repo"]} ${entry["sha"]}: split into ${actualParts.size} parts, release-please ${expectedParts.size}")
        continue
      }
      for ((i, expected) in expectedParts.withIndex()) {
        parts++
        val text = ConventionalCommits.jsTrim(actualParts[i])
        var error: String? = null
        var header: String? = null
        var releaseAs: String? = null
        try {
          val parsed = ConventionalCommits.parse(text)
          header = parsed.header()
          releaseAs = parsed.releaseAs()
        } catch (e: ConventionalCommits.ParseException) {
          error = e.message
        }
        if (text != expected["text"] || error != expected["error"] || header != expected["header"] ||
          releaseAs != expected["releaseAs"]) {
          mismatches.add("${entry["repo"]} ${entry["sha"]} part $i: expected error=${expected["error"]} header=${expected["header"]} " +
            "releaseAs=${expected["releaseAs"]}, got error=$error header=$header releaseAs=$releaseAs")
        }
      }
    }
    assertTrue(parts > 250, "corpus holds $parts parts")
    assertEquals(emptyList<String>(), mismatches)
  }

  @Test
  fun `the one-line form shows line ends as LF and collapses whitespace, as the action's log did`() {
    assertEquals("unexpected token '<LF>' at 4:7, valid tokens [)]",
      CommitRange.oneLine("unexpected token '\n' at 4:7, valid tokens [)]"))
    assertEquals("a<LF> b", CommitRange.oneLine(" a\r\n\t b "))
  }

  @Test
  fun `jsTrim strips what JavaScript strips and nothing else`() {
    assertEquals("x", ConventionalCommits.jsTrim("﻿ \t  x　\n"))
    assertEquals("x\u0085", ConventionalCommits.jsTrim(" x\u0085 "))
  }

  @Test
  fun `the Release-As footer is the first footer so named, read as its last text node`() {
    assertEquals("1.2.3", ConventionalCommits.parse("fix: x\n\nBody.\n\nrelease-AS: 1.2.3\nRelease-As: 2.0.0\n").releaseAs())
    assertEquals("continued", ConventionalCommits.parse("fix: x\n\nRelease-As: 1.2.3\n  continued\n").releaseAs())
    assertNull(ConventionalCommits.parse("fix: x\n\nRelease-As: 1.2.3\n\nMore body.\n").releaseAs())
    assertEquals("fix(core)!: x", ConventionalCommits.parse("fix(core)!: x\n\nRelease-As: 1.2.3\n").header())
  }

  @Test
  fun `a footer that resembles a commit is read as one, so a Release-As on its continuation line counts first`() {
    // release-please re-reads every footer with a type token as a commit, continuation lines
    // included, and lists those commits before the message's own.
    assertEquals("99.0.0", ConventionalCommits.parse("fix: typo\n\nSigned-off-by: C <c@example.com>\n Release-As: 99.0.0\n").releaseAs())
    assertEquals("9.9.9", ConventionalCommits.parse("fix: x\n\nRelease-As: 1.2.3\n Release-As: 9.9.9\n").releaseAs())
    assertEquals("4.0.0", ConventionalCommits.parse("fix: x\n\nCo-authored-by: A <a@b>\n Release-As: 4.0.0\nRelease-As: 3.0.0\n").releaseAs())
    assertEquals("8.0.0", ConventionalCommits.parse("fix: x\n\nA: b\n c: d\n  Release-As: 8.0.0\n").releaseAs())
    // A " #" separator is left out of the re-read text, and a BREAKING CHANGE token has no type to re-read.
    assertNull(ConventionalCommits.parse("fix: x\n\nFixes #12\n Release-As: 5.0.0\n").releaseAs())
    assertNull(ConventionalCommits.parse("fix: x\n\nBREAKING CHANGE: y\n Release-As: 7.0.0\n").releaseAs())
  }

  @Test
  fun `a footer re-read as a commit past the guard's limit refuses the message rather than losing its Release-As`() {
    // release-please reads the footer as a commit with a RELEASE AS note; the guard stops at
    // its own limit and must not report the message as read with no footer.
    val message = "fix: contributed\n\nSigned-off-by: a\n" + " line\n".repeat(2_100) + " Release-As: 93.0.0\n"
    val refused = assertThrows(ConventionalCommits.ParseException::class.java) { ConventionalCommits.parse(message) }
    assertTrue(refused.message!!.contains("which this guard does not parse"), refused.message)
    // Below the limit the nested footer is read, as release-please reads it.
    assertEquals("93.0.0", ConventionalCommits.parse("fix: contributed\n\nSigned-off-by: a\n" + " line\n".repeat(100) +
      " Release-As: 93.0.0\n").releaseAs())
  }

  @Test
  fun `a header the grammar rejects is still conventional to the guard when it looks like one`() {
    assertTrue(Guard.conventionalHeader("fix()!: empty scope"))
    assertTrue(Guard.conventionalHeader("fix2(core): digits"))
    assertTrue(!Guard.conventionalHeader("Merge branch feature"))
  }
}
