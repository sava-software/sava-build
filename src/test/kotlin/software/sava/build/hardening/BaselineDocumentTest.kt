package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineDocumentTest {

  private val first = "com.example.Codec,encode,MathMutator,SURVIVED"
  private val second = "com.example.Codec,decode,VoidMethodCallMutator,NO_COVERAGE"

  @Test
  fun `unversioned N minus one documents retain rows comments blanks and duplicates`() {
    val source = buildString {
      appendLine("# repository-specific evidence")
      appendLine()
      appendLine("com.example.Codec,encode,41,MathMutator,SURVIVED # race guard")
      appendLine(second)
      appendLine(second)
      appendLine("  # indented evidence")
    }

    val document = BaselineDocument.parse(source)

    assertEquals(BaselineDocument.SchemaState.UNVERSIONED_N_MINUS_ONE, document.schemaState)
    assertNull(document.schemaMetadata)
    assertEquals(3, document.rows.size)
    assertEquals(first, document.rows[0].key)
    assertEquals(listOf(41), document.rows[0].recordedLines)
    assertEquals(listOf(second, second), document.rows.drop(1).map { it.key })
    assertEquals(
        listOf("# repository-specific evidence", "  # indented evidence"),
        document.comments.map { it.raw })
    assertEquals(listOf(""), document.blankLines.map { it.raw })
    assertTrue(document.malformedRows.isEmpty())
    assertEquals(source, document.renderOriginal())
  }

  @Test
  fun `migration adds a marker canonicalizes rows and preserves non-row entries`() {
    val source = buildString {
      appendLine("# keep this preamble")
      appendLine()
      appendLine("com.example.Codec,encode,41,MathMutator,SURVIVED # race guard")
      appendLine("  $second  ")
      appendLine(second)
    }
    val expected = buildString {
      appendLine(BaselineDocument.CURRENT_HEADER)
      appendLine("# keep this preamble")
      appendLine()
      appendLine("$first # race guard # line 41")
      appendLine(second)
      appendLine(second)
    }

    val migration = BaselineDocument.parse(source).migrateToCurrent()

    assertEquals(BaselineDocument.SchemaState.UNVERSIONED_N_MINUS_ONE, migration.from)
    assertEquals(BaselineDocument.SchemaState.CURRENT, migration.to)
    assertTrue(migration.changed)
    assertEquals(2, migration.canonicalizedRows)
    assertEquals(expected, migration.content)

    val migrated = BaselineDocument.parse(migration.content)
    assertEquals(BaselineDocument.SchemaState.CURRENT, migrated.schemaState)
    assertEquals(BaselineDocument.CURRENT_SCHEMA, migrated.schemaMetadata?.version)
    assertEquals(1, migrated.schemaMetadata?.lineNumber)
    assertEquals(3, migrated.rows.size, "same-key siblings must not be deduplicated")
    assertEquals(expected, migrated.renderCurrent(), "the canonical current form is a fixed point")
  }

  @Test
  fun `unknown malformed and duplicate schema declarations are hard refusals`() {
    val unknown = assertThrows(IllegalArgumentException::class.java) {
      BaselineDocument.parse("${BaselineDocument.HEADER_NAME},2\n$first\n")
    }
    assertTrue(unknown.message.orEmpty().contains("unsupported accepted-baseline schema '2'"))

    val malformed = assertThrows(IllegalArgumentException::class.java) {
      BaselineDocument.parse("${BaselineDocument.HEADER_NAME} 1\n$first\n")
    }
    assertTrue(malformed.message.orEmpty().contains("malformed accepted-baseline schema header"))

    val duplicate = assertThrows(IllegalArgumentException::class.java) {
      BaselineDocument.parse(
          "${BaselineDocument.CURRENT_HEADER}\n# evidence\n${BaselineDocument.CURRENT_HEADER}\n$first\n")
    }
    assertTrue(duplicate.message.orEmpty().contains("duplicate accepted-baseline schema header"))
    assertTrue(duplicate.message.orEmpty().contains("first declared at line 1"))

    val misplaced = assertThrows(IllegalArgumentException::class.java) {
      BaselineDocument.parse("# evidence\n${BaselineDocument.CURRENT_HEADER}\n$first\n")
    }
    assertTrue(misplaced.message.orEmpty().contains("schema header must be the first line"))
  }

  @Test
  fun `the explicit marker is visible to the N minus one row reader`() {
    assertFalse(BaselineDocument.CURRENT_HEADER.startsWith("#"))
    assertTrue(
        BaselineNotes.malformed(BaselineDocument.CURRENT_HEADER),
        "an older reader must diagnose the marker instead of treating it as an ignored comment")
  }

  @Test
  fun `downgrade removes only the marker and is readable by the N minus one parser`() {
    val unversioned = buildString {
      appendLine("# keep this comment")
      appendLine()
      appendLine("  $first  ")
      appendLine(first)
      appendLine("  # keep this indented comment too")
    }
    val current = BaselineDocument.CURRENT_HEADER + "\n" + unversioned

    val document = BaselineDocument.parse(current)
    assertEquals(current, document.renderOriginal(), "parse alone must never normalize a current document")
    val downgrade = document.downgradeToUnversioned()

    assertEquals(BaselineDocument.SchemaState.CURRENT, downgrade.from)
    assertEquals(BaselineDocument.SchemaState.UNVERSIONED_N_MINUS_ONE, downgrade.to)
    assertTrue(downgrade.changed)
    assertEquals(0, downgrade.canonicalizedRows)
    assertEquals(unversioned, downgrade.content)

    val rolledBack = BaselineDocument.parse(downgrade.content)
    assertEquals(BaselineDocument.SchemaState.UNVERSIONED_N_MINUS_ONE, rolledBack.schemaState)
    assertEquals(listOf(first, first), rolledBack.rows.map { it.key })
    assertEquals(
        listOf("# keep this comment", "  # keep this indented comment too"),
        rolledBack.comments.map { it.raw })
  }

  @Test
  fun `malformed content is retained but cannot be blessed by the current schema`() {
    val source = "# evidence\nnot,a,baseline\n$first\n"
    val document = BaselineDocument.parse(source)

    assertEquals(listOf("not,a,baseline"), document.malformedRows.map { it.raw })
    assertEquals(listOf(2), document.malformedRows.map { it.lineNumber })
    assertEquals(source, document.renderDowngraded())

    val refusal = assertThrows(IllegalArgumentException::class.java) {
      document.renderCurrent()
    }
    assertTrue(refusal.message.orEmpty().contains("malformed baseline row(s) at line 2"))

    val replacement = BaselineNotes.Row(second, "# untriaged", listOf(73))
    assertEquals(
        "# evidence\nnot,a,baseline\n$second # untriaged # line 73\n",
        document.rewriteRowsPreservingNonRows(listOf(replacement)))
  }

  @Test
  fun `row rewrites preserve non-row order and duplicate replacement rows`() {
    val source = buildString {
      appendLine(BaselineDocument.CURRENT_HEADER)
      appendLine("# before")
      appendLine(first)
      appendLine("  # between")
      appendLine(second)
      appendLine()
      appendLine("# after")
    }
    val replacements = listOf(
        BaselineNotes.Row(second, "# first replacement", listOf(10)),
        BaselineNotes.Row(first, null, listOf(20)),
        BaselineNotes.Row(first, null, listOf(20)),
    )

    val rewritten = BaselineDocument.parse(source)
        .rewriteRowsPreservingNonRows(replacements)

    assertEquals(
        buildString {
          appendLine(BaselineDocument.CURRENT_HEADER)
          appendLine("# before")
          appendLine("$second # first replacement # line 10")
          appendLine("  # between")
          appendLine("$first # line 20")
          appendLine("$first # line 20")
          appendLine()
          appendLine("# after")
        },
        rewritten)
    assertEquals(
        listOf(second, first, first),
        BaselineDocument.parse(rewritten).rows.map { it.key })
  }

  @Test
  fun `line endings survive migration and rollback`() {
    val legacy = "# evidence\r\ncom.example.Codec,encode,41,MathMutator,SURVIVED\r\n"
    val current = BaselineDocument.parse(legacy).renderCurrent()

    assertEquals(
        "${BaselineDocument.CURRENT_HEADER}\r\n# evidence\r\n$first # line 41\r\n",
        current)
    assertEquals(
        "# evidence\r\n$first # line 41\r\n",
        BaselineDocument.parse(current).renderDowngraded())
  }
}
