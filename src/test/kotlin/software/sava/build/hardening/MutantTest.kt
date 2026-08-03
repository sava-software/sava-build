package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the one place PIT's CSV is interpreted. Every casebook incident
 * of the "interpreted a mutant differently at two sites" class reduces to a fact
 * about this type — the column split, the status vocabulary, or a key
 * derivation — so each fact is pinned here once instead of implicitly at every
 * former parts[] site.
 */
class MutantTest {

  private val row =
    "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
        "encode,12,KILLED,com.example.CodecTest.[engine:junit-jupiter]/[method:testEncode(int, int)]"

  @Test
  fun `columns split from the front — a killing test with commas cannot shift the status`() {
    val mutant = Mutant.parse(row)!!
    assertEquals("com.example.Codec", mutant.className)
    assertEquals("encode", mutant.method)
    assertEquals("KILLED", mutant.rawStatus)
    assertEquals(MutantStatus.KILLED, mutant.status)
    // the killer keeps its commas — rejoined, never indexed from the end
    assertEquals(
      "com.example.CodecTest.[engine:junit-jupiter]/[method:testEncode(int, int)]",
      mutant.killerText
    )
  }

  @Test
  fun `key derivations are line-less except where lines are deliberately identity`() {
    val mutant = Mutant.parse(row)!!
    assertEquals("com.example.Codec,encode,MathMutator", mutant.coordinate)
    assertEquals("com.example.Codec,encode,MathMutator,KILLED", mutant.baselineKey)
    // converge deliberately keeps the line: both rounds run identical code
    assertEquals("com.example.Codec,encode,12,MathMutator", mutant.lineFullKey)
  }

  @Test
  fun `mutator family strips the IF-ELSE style suffix so cross-pairs match`() {
    val mutant = Mutant.parse(
      "C.java,com.example.C,org.pitest...RemoveConditionalMutator_EQUAL_IF,m,5,SURVIVED,none")!!
    assertEquals("RemoveConditionalMutator_EQUAL_IF", mutant.mutatorSimpleName)
    assertEquals("RemoveConditionalMutator", mutant.mutatorFamily)
    assertEquals("com.example.C,m,5,RemoveConditionalMutator", mutant.familyLineKey)
  }

  @Test
  fun `status semantics partition exactly — detected, gated, neither`() {
    assertTrue(MutantStatus.KILLED.detected && !MutantStatus.KILLED.gated)
    assertTrue(MutantStatus.TIMED_OUT.detected && !MutantStatus.TIMED_OUT.gated)
    assertTrue(MutantStatus.SURVIVED.gated && !MutantStatus.SURVIVED.detected)
    assertTrue(MutantStatus.NO_COVERAGE.gated && !MutantStatus.NO_COVERAGE.detected)
    MutantStatus.entries.forEach { status ->
      assertTrue(!(status.detected && status.gated), "$status is both detected and gated")
    }
    assertEquals(2, MutantStatus.entries.count { it.detected })
    assertEquals(2, MutantStatus.entries.count { it.gated })
  }

  @Test
  fun `an unknown status is carried verbatim and reads neither detected nor gated`() {
    val mutant = Mutant.parse("C.java,com.example.C,org.pitest.M,m,5,FUTURE_STATUS,none")!!
    assertNull(mutant.status)
    assertEquals("FUTURE_STATUS", mutant.rawStatus)
    assertTrue(!mutant.detected && !mutant.gated)
  }

  @Test
  fun `an unparsable line rides raw — metadata, never a zero`() {
    val mutant = Mutant.parse("C.java,com.example.C,org.pitest.M,m,none,SURVIVED")!!
    assertEquals("none", mutant.lineText)
    assertNull(mutant.line)
    assertEquals("com.example.C,m,none,M", mutant.lineFullKey)
  }

  @Test
  fun `a short row is no row`() {
    assertNull(Mutant.parse("C.java,com.example.C,org.pitest.M,m,5"))
    assertNull(Mutant.parse(""))
  }
}
