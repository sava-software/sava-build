package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the baseline note format. In-process and pure, unlike the TestKit
 * functional tests that reach this logic through a forked build's log output: the
 * cases below (a note that is never read, tie ordering, a label whose parenthesis is
 * not a carry marker) are impractical to stage through a build fixture.
 */
class BaselineNotesTest {

  @Test
  fun `a note begins at the first hash and the key is what precedes it`() {
    val bare = BaselineNotes.parse("com.example.Codec,encode,MathMutator,SURVIVED")
    assertEquals("com.example.Codec,encode,MathMutator,SURVIVED", bare.key)
    assertNull(bare.note)
    assertEquals(emptyList<Int>(), bare.recordedLines)

    val noted = BaselineNotes.parse("com.example.Codec,encode,MathMutator,SURVIVED # race guard")
    assertEquals("com.example.Codec,encode,MathMutator,SURVIVED", noted.key)
    assertEquals("# race guard", noted.note)

    // surrounding whitespace is not part of either half; internal spacing survives,
    // and labelOf normalizes it away downstream
    val spaced = BaselineNotes.parse("  a,b,c,d   #   race guard   ")
    assertEquals("a,b,c,d", spaced.key)
    assertEquals("#   race guard", spaced.note)
    assertEquals("race guard", BaselineNotes.labelOf(spaced.note!!))

    // only the FIRST hash splits key from note: a note may contain further hashes
    val inner = BaselineNotes.parse("a,b,c,d # see #42 for the argument")
    assertEquals("a,b,c,d", inner.key)
    assertEquals("# see #42 for the argument", inner.note)

    // per-field spacing is readability, not identity
    assertEquals("a,b,c,d", BaselineNotes.parse("a, b,  c , d").key)
  }

  @Test
  fun `a trailing line tag is metadata, split off the note`() {
    val tagged = BaselineNotes.parse("a,b,MathMutator,SURVIVED # race guard # line 45")
    assertEquals("a,b,MathMutator,SURVIVED", tagged.key)
    assertEquals("# race guard", tagged.note)
    assertEquals(listOf(45), tagged.recordedLines)

    // plural, comma- or slash-separated — the audited-timeout comment conventions
    assertEquals(listOf(61, 93), BaselineNotes.parse("a,b,c,d # lines 61, 93").recordedLines)
    assertEquals(listOf(137, 141), BaselineNotes.parse("a,b,c,d # lines 137/141").recordedLines)

    // a tag with no label note: the note stays null rather than reading '# line…'
    val tagOnly = BaselineNotes.parse("a,b,c,d # line 12")
    assertNull(tagOnly.note)
    assertEquals(listOf(12), tagOnly.recordedLines)

    // the tag is anchored to the end: 'line' inside a note is prose, not a tag
    val prose = BaselineNotes.parse("a,b,c,d # line 12 moved the guard")
    assertEquals("# line 12 moved the guard", prose.note)
    assertEquals(emptyList<Int>(), prose.recordedLines)
  }

  @Test
  fun `a line range is split from the label but remains invalid metadata`() {
    val source =
        "a,b,MathMutator,SURVIVED # flip insurance # lines 786-800"
    val ranged = BaselineNotes.parse(source)

    assertEquals("# flip insurance", ranged.note)
    assertEquals("flip insurance", BaselineNotes.labelOf(ranged.note!!))
    assertTrue(BaselineNotes.hasFlipInsurance(ranged.note))
    assertEquals(emptyList<Int>(), ranged.recordedLines)
    assertEquals("# lines 786-800", ranged.invalidLineMetadata)
    assertEquals(
        emptyList<String>(),
        BaselineNotes.undocumentedLabels(listOf(ranged.note!!)) { "# flip insurance\n" },
    )

    val document = BaselineDocument.parse("$source\n")
    assertEquals(listOf(1), document.invalidLineMetadataRows.map { it.lineNumber })
    assertEquals(source, BaselineNotes.render(ranged), "read-only rendering must retain the bad suffix")
    val refusal = assertThrows(IllegalArgumentException::class.java) {
      document.rewriteRowsPreservingNonRows(document.rows)
    }
    assertTrue(refusal.message.orEmpty().contains("invalid line metadata '# lines 786-800'"))
    assertTrue(
      refusal.message.orEmpty().contains(
        "'# line N' or '# lines N, M' as the trailing accepted-row tag"),
    )
    assertTrue(
      refusal.message.orEmpty().contains(
        "remove the optional tag when no exact observation exists"),
    )
    assertTrue(refusal.message.orEmpty().contains("ranges and out-of-range numbers are not valid"))

    val clean = BaselineDocument.parse("a,b,MathMutator,SURVIVED\n")
    val invalidReplacement = BaselineNotes.Row(
        "a,b,MathMutator,SURVIVED", "# flip insurance", emptyList(), "# lines 1-2")
    val replacementRefusal = assertThrows(IllegalArgumentException::class.java) {
      clean.rewriteRowsPreservingNonRows(listOf(invalidReplacement))
    }
    assertTrue(replacementRefusal.message.orEmpty().contains("replacement 1 invalid line metadata"))

    val current = "${BaselineDocument.CURRENT_HEADER}\n$source\n"
    assertEquals(
        "$source\n",
        BaselineDocument.parse(current).renderDowngraded(),
        "raw-preserving downgrade must remain available",
    )

    val followedByValid = BaselineNotes.parse(
        "a,b,MathMutator,SURVIVED # flip insurance # lines 786-800 # line 790",
    )
    assertEquals("# flip insurance", followedByValid.note)
    assertEquals(listOf(790), followedByValid.recordedLines)
    assertEquals("# lines 786-800", followedByValid.invalidLineMetadata)
    assertEquals("flip insurance", BaselineNotes.labelOf(followedByValid.note!!))
    val mixedDocument = BaselineDocument.parse(
        "a,b,MathMutator,SURVIVED # flip insurance # lines 786-800 # line 790\n",
    )
    val mixedRefusal = assertThrows(IllegalArgumentException::class.java) {
      mixedDocument.rewriteRowsPreservingNonRows(mixedDocument.rows)
    }
    assertTrue(mixedRefusal.message.orEmpty().contains("invalid line metadata '# lines 786-800'"))
  }

  @Test
  fun `range spellings and overflowing line numbers remain invalid metadata`() {
    listOf(
      "10-30",
      "10–30",
      "10—30",
      "10−30",
      "10..30",
      "10 to 30",
      "10-20, 30-40",
      "10, 20-30",
    ).forEach { value ->
      val row = BaselineNotes.parse("a,b,c,d # family # lines $value")
      assertEquals("# family", row.note, value)
      assertEquals(emptyList<Int>(), row.recordedLines, value)
      assertEquals("# lines $value", row.invalidLineMetadata, value)
    }

    val overflow = "999999999999999999999999"
    val row = BaselineNotes.parse("a,b,c,d # family # line $overflow")
    assertEquals("# family", row.note)
    assertEquals(emptyList<Int>(), row.recordedLines)
    assertEquals("# line $overflow", row.invalidLineMetadata)
  }

  @Test
  fun `a legacy five-field row normalizes to its line-less key`() {
    val legacy = BaselineNotes.parse("com.example.Codec,encode,12,MathMutator,SURVIVED # race guard")
    assertEquals("com.example.Codec,encode,MathMutator,SURVIVED", legacy.key)
    assertEquals("# race guard", legacy.note)
    assertEquals(listOf(12), legacy.recordedLines, "the legacy line field demotes to metadata")

    // recognition is by shape — five fields, numeric third; nothing else normalizes
    assertEquals("a,b,notaline,d,e", BaselineNotes.parse("a,b,notaline,d,e").key)
  }

  @Test
  fun `render writes key, note, then line tag, and round-trips through parse`() {
    assertEquals("a,b,c,SURVIVED", BaselineNotes.render("a,b,c,SURVIVED", null, emptyList()))
    assertEquals("a,b,c,SURVIVED # race guard", BaselineNotes.render("a,b,c,SURVIVED", "# race guard", emptyList()))
    assertEquals("a,b,c,SURVIVED # race guard # line 45",
        BaselineNotes.render("a,b,c,SURVIVED", "# race guard", listOf(45)))
    assertEquals("a,b,c,SURVIVED # lines 45, 61",
        BaselineNotes.render("a,b,c,SURVIVED", null, listOf(61, 45)))

    val rendered = BaselineNotes.render("a,b,c,SURVIVED", "# race guard", listOf(45))
    val reparsed = BaselineNotes.parse(rendered)
    assertEquals("a,b,c,SURVIVED", reparsed.key)
    assertEquals("# race guard", reparsed.note)
    assertEquals(listOf(45), reparsed.recordedLines)

    // a legacy row re-rendered IS the migration: line field becomes a tag
    val migrated = BaselineNotes.parse("com.example.Codec,encode,12,MathMutator,SURVIVED # race guard")
    assertEquals("com.example.Codec,encode,MathMutator,SURVIVED # race guard # line 12",
        BaselineNotes.render(migrated))
  }

  @Test
  fun `population summary distinguishes physical sibling rows from unique keys`() {
    assertEquals(
        "4 rows / 2 unique keys",
        BaselineNotes.populationSummary(
            listOf(
                "a,b,c,SURVIVED",
                "a,b,c,SURVIVED",
                "a,b,c,SURVIVED",
                "x,y,z,NO_COVERAGE",
            )),
    )
    assertEquals(
        "1 row / 1 unique key",
        BaselineNotes.populationSummary(listOf("a,b,c,SURVIVED")),
    )
    assertEquals("0 rows / 0 unique keys", BaselineNotes.populationSummary(emptyList()))
  }

  @Test
  fun `line drift is row-level when every row is tagged and counts match`() {
    fun row(key: String, line: Int?) =
        BaselineNotes.Row(key, null, line?.let { listOf(it) } ?: emptyList())
    val key = "a,b,MathMutator,SURVIVED"

    // two tagged siblings, one observed at an unrecorded line: the multiset already
    // fails a NEW sibling as a count change, so under matched counts an unrecorded
    // line is a moved anchor or a same-key swap — reported even though the other
    // observed line matches (the case key-level disjointness kept quiet)
    val drifted = BaselineNotes.lineDrift(
        listOf(row(key, 53), row(key, 92)),
        mapOf(key to listOf(53, 157)))
    assertEquals(mapOf(key to (setOf(53, 92) to setOf(157))), drifted)

    // fully recorded: quiet
    assertEquals(
        emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
        BaselineNotes.lineDrift(listOf(row(key, 53), row(key, 92)), mapOf(key to listOf(92, 53))))

    // a key with no recorded lines never takes part
    assertEquals(
        emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
        BaselineNotes.lineDrift(listOf(row(key, null)), mapOf(key to listOf(157))))
  }

  @Test
  fun `line drift falls back to key-level disjointness on partial tags or count skew`() {
    fun row(key: String, line: Int?) =
        BaselineNotes.Row(key, null, line?.let { listOf(it) } ?: emptyList())
    val key = "a,b,MathMutator,SURVIVED"

    // one sibling untagged: the row-level reading has no data for it, so only full
    // disjointness reports — one matching line keeps the key quiet
    assertEquals(
        emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
        BaselineNotes.lineDrift(listOf(row(key, 53), row(key, null)), mapOf(key to listOf(53, 157))))

    // count skew (an extra observed sibling): the multiset failure already owns the
    // story; drift only reports when nothing matches at all
    assertEquals(
        emptyMap<String, Pair<Set<Int>, Set<Int>>>(),
        BaselineNotes.lineDrift(listOf(row(key, 53)), mapOf(key to listOf(53, 157))))
    assertEquals(
        mapOf(key to (setOf(53) to setOf(157, 160))),
        BaselineNotes.lineDrift(listOf(row(key, 53)), mapOf(key to listOf(157, 160))))
  }

  @Test
  fun `a label drops the hash and any trailing carry parenthetical`() {
    assertEquals("race guard", BaselineNotes.labelOf("# race guard"))
    assertEquals("untriaged", BaselineNotes.labelOf("# untriaged"))
    assertEquals("race guard",
        BaselineNotes.labelOf("# race guard (carried across NO_COVERAGE -> SURVIVED)"))
    assertEquals("flip insurance",
        BaselineNotes.labelOf("# flip insurance (gate=SURVIVED, solo=KILLED)"))
    // the carry marker is delimited by ' (' — a parenthesis inside the label itself
    // (no preceding space) is part of the family name, not a marker to strip
    assertEquals("guard(inner)", BaselineNotes.labelOf("# guard(inner)"))
  }

  @Test
  fun `summarize counts per label, descending, with unlabeled rows named last`() {
    assertNull(BaselineNotes.summarize(emptyList(), 0), "an empty baseline has nothing to say")

    // a baseline predating label seeding still prints a number rather than nothing
    assertEquals("5 unlabeled", BaselineNotes.summarize(emptyList(), 5))

    assertEquals("2 '# race guard'",
        BaselineNotes.summarize(listOf("# race guard", "# race guard"), 0))

    assertEquals(
        "3 '# race guard', 1 '# untriaged', 2 unlabeled",
        BaselineNotes.summarize(
            listOf("# untriaged", "# race guard", "# race guard", "# race guard"), 2)
    )

    // carried notes fold into their family: the parenthetical is not a separate label
    assertEquals(
        "2 '# race guard'",
        BaselineNotes.summarize(
            listOf("# race guard", "# race guard (carried across NO_COVERAGE -> SURVIVED)"), 0)
    )
  }

  @Test
  fun `labels tied on count keep first-seen order`() {
    // stable ordering keeps the breakdown from reshuffling between runs on a tie,
    // which would read as churn in a build log diff
    assertEquals(
        "1 '# alpha', 1 '# beta'",
        BaselineNotes.summarize(listOf("# alpha", "# beta"), 0)
    )
    assertEquals(
        "1 '# beta', 1 '# alpha'",
        BaselineNotes.summarize(listOf("# beta", "# alpha"), 0)
    )
  }

  @Test
  fun `an undocumented label is named and a documented one is silent`() {
    val readme = "## Triaged\n\n# race guard\n\nThe argument for the family.\n"

    assertEquals(emptyList<String>(), BaselineNotes.undocumentedLabels(listOf("# race guard")) { readme })

    val undocumented = BaselineNotes.undocumentedLabels(listOf("# race gaurd")) { readme }
    assertEquals(
        listOf("race gaurd"), undocumented,
        "a typo must be named rather than silently opening a bucket of its own"
    )
    assertEquals(
        "pitest baseline 'encoding': label(s) with no argument in config/pitest/README.md — " +
            "'# race gaurd' — document the family there, or fix the label if it is a typo",
        BaselineNotes.undocumentedLabelWarning("encoding", undocumented)
    )

    // several undocumented labels are named once each, in first-seen order
    val many = BaselineNotes.undocumentedLabels(listOf("# alpha", "# beta", "# alpha")) { readme }
    assertEquals(listOf("alpha", "beta"), many)
    assertEquals(
        "pitest baseline 'encoding': label(s) with no argument in config/pitest/README.md — " +
            "'# alpha', '# beta' — document the family there, or fix the label if it is a typo",
        BaselineNotes.undocumentedLabelWarning("encoding", many)
    )

    // a carried note resolves against its family's section, not its full text
    assertEquals(
        emptyList<String>(),
        BaselineNotes.undocumentedLabels(
            listOf("# race guard (carried across NO_COVERAGE -> SURVIVED)")) { readme }
    )
  }

  @Test
  fun `the README is not read when no label needs resolving`() {
    // the resolve is lazy by contract: a baseline of unlabeled or seeded rows must
    // cost no file read, which only a direct call can observe
    var reads = 0
    val readme = { reads++; "" }

    assertEquals(emptyList<String>(), BaselineNotes.undocumentedLabels(emptyList(), readme))
    assertEquals(0, reads, "an empty baseline must not read the README")

    assertEquals(emptyList<String>(), BaselineNotes.undocumentedLabels(listOf("# untriaged"), readme))
    assertEquals(0, reads, "seeded debt argues nothing and needs no section")

    BaselineNotes.undocumentedLabels(listOf("# race guard", "# other"), readme)
    assertEquals(1, reads, "the README must be read once per call, not once per label")
  }
}
