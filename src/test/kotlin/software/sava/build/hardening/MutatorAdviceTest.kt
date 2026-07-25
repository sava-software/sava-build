package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files

/// Fixture: real arithmetic, compiled to real bytecode. The scanner reads the
/// constant pool of these very classes, so the test exercises the same path a
/// build does rather than a hand-rolled byte array that could drift from what
/// javac and kotlinc actually emit.
@Suppress("unused")
internal class MutatorAdviceMoneyFixture {
  fun fee(a: BigDecimal, b: BigDecimal): BigDecimal = a.multiply(b).subtract(BigDecimal.ONE)
  fun mask(a: BigInteger, b: BigInteger): BigInteger = a.and(b).shiftLeft(2)
}

/// Fixture: touches both types but never their arithmetic. Counting this class
/// would make the advice cry wolf on every codebase that merely formats or
/// compares a `BigDecimal`, which is nearly all of them.
@Suppress("unused")
internal class MutatorAdviceFormatOnlyFixture {
  fun render(a: BigDecimal): String = a.toPlainString()
  fun same(a: BigDecimal, b: BigDecimal): Boolean = a.compareTo(b) == 0
  fun text(a: BigInteger): String = a.toString(16)
}

/// Unit tests for the mutator-blindness scan. In-process and pure: the
/// functional tests reach the advice through a forked build's log, which cannot
/// distinguish "counted the right classes" from "counted anything at all".
class MutatorAdviceTest {

  private val classesDir: File =
      File(MutatorAdviceMoneyFixture::class.java.protectionDomain.codeSource.location.toURI())

  private val moneyGlob = "software.sava.build.hardening.MutatorAdviceMoneyFixture"
  private val allFixtures = "software.sava.build.hardening.MutatorAdvice*Fixture"

  @Test
  fun `arithmetic on both types is reported when neither mutator is enabled`() {
    val findings = MutatorAdvice.scan(classesDir, listOf(moneyGlob), emptyList(), "STRONGER")
        .associateBy { it.mutator }

    assertEquals(setOf("EXPERIMENTAL_BIG_DECIMAL", "EXPERIMENTAL_BIG_INTEGER"), findings.keys)
    assertEquals(1, findings.getValue("EXPERIMENTAL_BIG_DECIMAL").classCount)
    assertEquals(1, findings.getValue("EXPERIMENTAL_BIG_INTEGER").classCount)
    // multiply + subtract, and + shiftLeft
    assertEquals(2, findings.getValue("EXPERIMENTAL_BIG_DECIMAL").callCount)
    assertEquals(2, findings.getValue("EXPERIMENTAL_BIG_INTEGER").callCount)
  }

  @Test
  fun `an enabled mutator is not advised again`() {
    val findings = MutatorAdvice.scan(
        classesDir, listOf(moneyGlob), emptyList(), "STRONGER,EXPERIMENTAL_BIG_DECIMAL")

    assertEquals(listOf("EXPERIMENTAL_BIG_INTEGER"), findings.map { it.mutator })

    assertTrue(
        MutatorAdvice.scan(
            classesDir, listOf(moneyGlob), emptyList(),
            "STRONGER, EXPERIMENTAL_BIG_DECIMAL , EXPERIMENTAL_BIG_INTEGER").isEmpty(),
        "surrounding whitespace in the mutators string must not hide an enabled mutator"
    )
  }

  @Test
  fun `formatting and comparison are not arithmetic`() {
    val findings = MutatorAdvice.scan(
        classesDir,
        listOf("software.sava.build.hardening.MutatorAdviceFormatOnlyFixture"),
        emptyList(),
        "STRONGER"
    )
    assertTrue(findings.isEmpty(), "toPlainString/compareTo/toString give the mutators nothing to rewrite: $findings")
  }

  @Test
  fun `exclusions win over targets`() {
    assertEquals(
        2,
        MutatorAdvice.scan(classesDir, listOf(allFixtures), emptyList(), "STRONGER").size,
        "both candidates are reported when nothing is excluded"
    )
    assertTrue(
        MutatorAdvice.scan(classesDir, listOf(allFixtures), listOf(allFixtures), "STRONGER").isEmpty(),
        "an excluded class is not mutated, so its arithmetic must not be advised"
    )
  }

  @Test
  fun `a class outside the target globs is not counted`() {
    assertTrue(
        MutatorAdvice.scan(classesDir, listOf("com.example.nothing.*"), emptyList(), "STRONGER").isEmpty()
    )
  }

  @Test
  fun `a recorded decline suppresses its own mutator and nothing else`() {
    val findings = MutatorAdvice.scan(classesDir, listOf(moneyGlob), emptyList(), "STRONGER")
    val advice = MutatorAdvice.advise(
        findings, "STRONGER", mapOf("EXPERIMENTAL_BIG_DECIMAL" to "trialed 2026-07-25: generated 0"))

    assertEquals(listOf("EXPERIMENTAL_BIG_INTEGER"), advice.findings.map { it.mutator })
    assertTrue(advice.staleDeclines.isEmpty(), "a decline with a live subject is not stale: ${advice.staleDeclines}")
  }

  @Test
  fun `a decline without a reason suppresses nothing and is reported`() {
    val findings = MutatorAdvice.scan(classesDir, listOf(moneyGlob), emptyList(), "STRONGER")
    val advice = MutatorAdvice.advise(findings, "STRONGER", mapOf("EXPERIMENTAL_BIG_DECIMAL" to "   "))

    assertEquals(
        setOf("EXPERIMENTAL_BIG_DECIMAL", "EXPERIMENTAL_BIG_INTEGER"),
        advice.findings.map { it.mutator }.toSet(),
        "an argument-free suppression must not silence the advice"
    )
    assertEquals(listOf("EXPERIMENTAL_BIG_DECIMAL"), advice.staleDeclines.map { it.mutator })
    assertTrue(advice.staleDeclines.single().why.contains("no reason"), advice.staleDeclines.single().why)
  }

  @Test
  fun `a decline goes stale when the mutator is enabled`() {
    val findings = MutatorAdvice.scan(classesDir, listOf(moneyGlob), emptyList(), "STRONGER,EXPERIMENTAL_BIG_DECIMAL")
    val advice = MutatorAdvice.advise(
        findings,
        "STRONGER,EXPERIMENTAL_BIG_DECIMAL",
        mapOf("EXPERIMENTAL_BIG_DECIMAL" to "trialed 2026-07-25: generated 0"),
    )

    assertEquals(listOf("EXPERIMENTAL_BIG_DECIMAL"), advice.staleDeclines.map { it.mutator })
    assertTrue(advice.staleDeclines.single().why.contains("contradicts"), advice.staleDeclines.single().why)
  }

  @Test
  fun `a decline goes stale when its arithmetic is gone`() {
    // the format-only fixture gives the mutators nothing, so the scan finds nothing —
    // the shape a decline takes on after the money math it argued about is deleted
    val findings = MutatorAdvice.scan(
        classesDir,
        listOf("software.sava.build.hardening.MutatorAdviceFormatOnlyFixture"),
        emptyList(),
        "STRONGER",
    )
    val advice = MutatorAdvice.advise(
        findings, "STRONGER", mapOf("EXPERIMENTAL_BIG_DECIMAL" to "trialed 2026-07-25: generated 0"))

    assertTrue(advice.findings.isEmpty())
    assertEquals(listOf("EXPERIMENTAL_BIG_DECIMAL"), advice.staleDeclines.map { it.mutator })
    assertTrue(
        advice.staleDeclines.single().why.contains("no longer suppresses anything"),
        advice.staleDeclines.single().why
    )
  }

  @Test
  fun `advice never throws on unreadable input`() {
    val scratch = Files.createTempDirectory("mutator-advice").toFile()
    try {
      scratch.resolve("Garbage.class").writeBytes(byteArrayOf(1, 2, 3))
      scratch.resolve("Empty.class").writeBytes(ByteArray(0))
      scratch.resolve("notes.txt").writeText("not a class")
      assertTrue(MutatorAdvice.scan(scratch, listOf("*"), emptyList(), "STRONGER").isEmpty())
      // a directory that does not exist is a no-op, not a failure
      assertTrue(
          MutatorAdvice.scan(scratch.resolve("absent"), listOf("*"), emptyList(), "STRONGER").isEmpty()
      )
    } finally {
      scratch.deleteRecursively()
    }
  }
}
