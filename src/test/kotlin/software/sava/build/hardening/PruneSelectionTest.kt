package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PruneSelectionTest {

  private val first = "com.example.Codec,encode,MathMutator,SURVIVED"
  private val second = "com.example.Codec,decode,VoidMethodCallMutator,NO_COVERAGE"
  private val third = "com.example.Codec,flush,VoidMethodCallMutator,SURVIVED"

  @Test
  fun `selection accepts unique bare keys with comments and blank lines`() {
    val selection = PruneSelection.parse("# reviewed retirements\r\n$first\r\n\r\n  # retained evidence is not selected\n$second\n")

    assertEquals(setOf(first, second), selection.keys)
    assertEquals(selection.keys.sorted(), selection.keys.toList())
  }

  @Test
  fun `empty duplicate noncanonical annotated line-full and invalid-status keys are refused`() {
    val invalid = listOf(
        "", "\n # no selected keys\n", "$first\n$first\n",
        "$first # retired", "$first # line 30",
        "com.example.Codec,encode,30,MathMutator,SURVIVED",
        "com.example.Codec, encode,MathMutator,SURVIVED",
        "com.example.Codec,,MathMutator,SURVIVED",
        "com.example.*,encode,MathMutator,SURVIVED",
        "com.example.Codec,encode,MathMutator,KILLED",
        "com.example.Codec,encode,MathMutator,TIMED_OUT",
        "com.example.Codec,encode,MathMutator,UNKNOWN",
        "com.example.Codec,encode,MathMutator,SURVIVED,2",
    )
    invalid.forEach { text ->
      assertThrows(IllegalArgumentException::class.java, { PruneSelection.parse(text) }, text)
    }
  }

  @Test
  fun `whole-key selection preserves unselected candidates protected rows and duplicate multiplicity`() {
    val rows = listOf(
        "$first # first accepted reason # line 10",
        "$second # unlicensed-only evidence # line 20",
        "$first # different accepted reason # line 30",
        "$third # active acceptance # line 40",
        "$second # unlicensed-only evidence # line 20",
    ).map(BaselineNotes::parse)
    val dispositions = listOf(
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.DROP,
        BaselineEngine.Disposition.MATCHED,
        BaselineEngine.Disposition.DROP,
    )

    val plan = PruneSelection.parse(first).plan(rows, dispositions)

    assertEquals(listOf(0, 2), plan.removedRowIndices)
    assertEquals(listOf(1, 3, 4), plan.retainedRowIndices)
    assertEquals(listOf(second, third, second), plan.retainedRowIndices.map { rows[it].key })
  }

  @Test
  fun `every protected sibling disposition refuses retirement despite distinct notes and line tags`() {
    val rows = listOf(
        "$first # reviewed killed row # line 10",
        "$first # unrelated evidence # line 20",
    ).map(BaselineNotes::parse)

    BaselineEngine.Disposition.entries.filter { it != BaselineEngine.Disposition.DROP }.forEach { disposition ->
      val refusal = assertThrows(IllegalArgumentException::class.java) {
        PruneSelection.parse(first).plan(rows, listOf(BaselineEngine.Disposition.DROP, disposition))
      }
      assertTrue(refusal.message.orEmpty().contains("same-key sibling retirement is ambiguous"))
      assertTrue(refusal.message.orEmpty().contains("1 candidate row(s), 1 protected row(s)"))
    }
  }

  @Test
  fun `identical sibling spelling does not bypass a protected same-key row`() {
    val rows = listOf(first, first).map(BaselineNotes::parse)

    assertThrows(IllegalArgumentException::class.java) {
      PruneSelection.parse(first).plan(
          rows, listOf(BaselineEngine.Disposition.DROP, BaselineEngine.Disposition.MATCHED))
    }
  }

  @Test
  fun `missing keys noncandidate keys and malformed plan sizes are refused`() {
    val selection = PruneSelection.parse(first)
    assertThrows(IllegalArgumentException::class.java) {
      selection.plan(listOf(BaselineNotes.parse(second)), listOf(BaselineEngine.Disposition.DROP))
    }
    assertThrows(IllegalArgumentException::class.java) {
      selection.plan(listOf(BaselineNotes.parse(first)), listOf(BaselineEngine.Disposition.MATCHED))
    }
    assertThrows(IllegalArgumentException::class.java) {
      selection.plan(listOf(BaselineNotes.parse(first)), emptyList())
    }
  }
}
