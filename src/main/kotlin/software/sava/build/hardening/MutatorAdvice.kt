package software.sava.build.hardening

import java.io.DataInputStream
import java.io.File

/// Detects arithmetic a suite's enabled mutators cannot see.
///
/// `STRONGER` mutates bytecode arithmetic operators, so `BigDecimal` and
/// `BigInteger` math — ordinary method calls — is invisible to it: a suite can
/// report a healthy detection rate while every fee, price, and balance
/// computation in it is unmutated. Nothing fails, because a mutant that is
/// never generated cannot survive. That silence is the problem: the blind spot
/// has to be looked for, and no one looks for what they do not know is there.
///
/// So each `pitest<Suite>` run scans the classes it is about to mutate for
/// arithmetic calls on those types and, when the matching arcmutate mutator is
/// not enabled, prints the trial command. The advice is self-extinguishing —
/// enable the mutator (or record a measured decision not to) and it goes quiet.
///
/// Deliberately narrow. `EXPERIMENTAL_NAKED_RECEIVER` is not advised here:
/// fluent APIs are near-universal in Java, so it would fire on nearly every
/// suite, and its population can be large enough that adopting it is a real
/// judgment call rather than an obvious win — advice that always fires is
/// noise, and noise is what stops the useful case from being read.
object MutatorAdvice {

  /// An arcmutate mutator and the receiver type whose arithmetic it rewrites.
  private data class Candidate(val mutator: String, val owner: String, val label: String)

  private val CANDIDATES = listOf(
      Candidate("EXPERIMENTAL_BIG_DECIMAL", "java/math/BigDecimal", "BigDecimal"),
      Candidate("EXPERIMENTAL_BIG_INTEGER", "java/math/BigInteger", "BigInteger"),
  )

  /// Arithmetic and bitwise operations these mutators swap for a sibling
  /// (add<->subtract, multiply<->divide, and<->or, ...). Restricting the scan
  /// to these keeps the advice honest: a class that merely formats a
  /// `BigDecimal` has nothing for the mutator to rewrite and must not be
  /// counted, or the advice cries wolf and stops being read.
  ///
  /// This is a heuristic, not proof. The match is on owner plus method name,
  /// so an overload arcmutate may not rewrite — `divide(BigDecimal, int,
  /// RoundingMode)`, `sqrt`, `gcd` — still counts, and the advice can point at
  /// a suite where the trial then generates nothing. That direction is the
  /// cheap one: the answer is a recorded decline carrying the measurement,
  /// which is a better artifact than the silence it replaces. Erring the other
  /// way would hide exactly the money math this exists to find.
  private val ARITHMETIC = setOf(
      "add", "subtract", "multiply", "divide", "remainder", "mod", "modPow", "modInverse",
      "negate", "abs", "min", "max", "pow", "gcd", "sqrt",
      "and", "or", "xor", "not", "andNot", "shiftLeft", "shiftRight",
  )

  /// Result of scanning one suite: how many mutated classes call each
  /// candidate's arithmetic, keyed by mutator name.
  data class Finding(val mutator: String, val label: String, val classCount: Int, val callCount: Int)

  /// A recorded decline that no longer suppresses anything, with the sentence
  /// explaining why it can go. Declines rot the way baseline rows do: the
  /// mutator gets enabled, or the arithmetic it would have rewritten is
  /// deleted, and what is left reads as a settled decision about code that no
  /// longer exists.
  data class StaleDecline(val mutator: String, val why: String)

  /// What a suite should print: findings still worth advising, and declines
  /// that have outlived their subject.
  data class Advice(val findings: List<Finding>, val staleDeclines: List<StaleDecline>)

  /// Filters [findings] by the suite's recorded declines and reports the
  /// declines that have gone stale. Pure, so the policy — what a decline
  /// suppresses, and when it stops being one — is unit-testable without a
  /// build.
  ///
  /// A blank reason suppresses nothing. A suppression is only worth as much as
  /// the argument attached to it: an empty one cannot be distinguished later
  /// from an oversight, so the advice keeps firing and the decline itself is
  /// reported.
  @JvmStatic
  fun advise(
      findings: List<Finding>,
      enabledMutators: String,
      declined: Map<String, String>,
  ): Advice {
    val enabled = enabledMutators.split(',').map { it.trim() }.toSet()
    val advised = findings.filterNot { finding ->
      declined[finding.mutator]?.isNotBlank() == true
    }
    val stale = declined.mapNotNull { (mutator, reason) ->
      val why = when {
        reason.isBlank() ->
          "the decline carries no reason, so it suppresses nothing — record what the trial " +
              "measured, or drop the decline"
        enabled.contains(mutator) ->
          "the mutator is enabled on this suite, so the decline contradicts it"
        // Checked before the subject test below, which would otherwise report a
        // decline of an un-advised mutator as though its arithmetic had been
        // deleted. `NAKED_RECEIVER` is the case that matters: the policy tells you
        // to trial it, this scan deliberately does not advise it, and a decline
        // recorded here would warn forever with no way to silence it — while a
        // typo would be diagnosed as a subject that never existed.
        CANDIDATES.none { it.mutator == mutator } ->
          "the blind-spot scan does not advise $mutator (only " +
              CANDIDATES.joinToString(" and ") { it.mutator } +
              "), so there is nothing here for the decline to suppress — check the spelling, and " +
              "record trials for other mutators where the suite's numbers live, not as a decline"
        findings.none { it.mutator == mutator } ->
          "nothing here is left for it to mutate, so the decline no longer suppresses anything"
        else -> null
      }
      why?.let { StaleDecline(mutator, it) }
    }
    return Advice(advised, stale)
  }

  /// Scans [classesDir] for classes matching the suite's PIT globs and reports
  /// candidates whose arithmetic is called but whose mutator is absent from
  /// [enabledMutators]. Never throws: advice must not be able to fail a build,
  /// so an unreadable or unfamiliar class file is skipped rather than raised.
  @JvmStatic
  fun scan(
      classesDir: File,
      targetGlobs: List<String>,
      excludedGlobs: List<String>,
      enabledMutators: String,
  ): List<Finding> {
    if (!classesDir.isDirectory) return emptyList()
    val enabled = enabledMutators.split(',').map { it.trim() }.toSet()
    val missing = CANDIDATES.filterNot { enabled.contains(it.mutator) }
    if (missing.isEmpty()) return emptyList()

    val included = targetGlobs.map(::globToRegex)
    val excluded = excludedGlobs.map(::globToRegex)
    val classHits = HashMap<String, Int>()
    val callHits = HashMap<String, Int>()

    classesDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .forEach { file ->
          val binaryName = file.relativeTo(classesDir).invariantSeparatorsPath
              .removeSuffix(".class")
              .replace('/', '.')
          if (included.none { it.matches(binaryName) }) return@forEach
          if (excluded.any { it.matches(binaryName) }) return@forEach
          val calls = arithmeticCalls(file) ?: return@forEach
          missing.forEach { candidate ->
            val count = calls[candidate.owner] ?: return@forEach
            if (count > 0) {
              classHits.merge(candidate.mutator, 1, Int::plus)
              callHits.merge(candidate.mutator, count, Int::plus)
            }
          }
        }

    return missing.mapNotNull { candidate ->
      val classes = classHits[candidate.mutator] ?: return@mapNotNull null
      Finding(candidate.mutator, candidate.label, classes, callHits[candidate.mutator] ?: 0)
    }
  }

  // PIT-glob parsing lives in PitGlobs: this scan's private copy lacked the '**.'
  // handling ExclusionAudit documents as necessary, so the same glob selected
  // different classes depending on which advisory read it.
  private fun globToRegex(glob: String): Regex = PitGlobs.toRegex(glob)

  /// Counts arithmetic method references per owner in one class file's constant
  /// pool. Returns null when the file cannot be parsed as a class — advice is
  /// best-effort by construction.
  private fun arithmeticCalls(file: File): Map<String, Int>? = try {
    DataInputStream(file.inputStream().buffered()).use { input ->
      if (input.readInt() != -0x35014542) return null // 0xCAFEBABE
      input.readUnsignedShort() // minor
      input.readUnsignedShort() // major
      val poolCount = input.readUnsignedShort()
      val utf8 = HashMap<Int, String>()
      val classNameIndex = HashMap<Int, Int>()
      val nameAndTypeName = HashMap<Int, Int>()
      val methodRefs = ArrayList<IntArray>()
      var i = 1
      while (i < poolCount) {
        when (input.readUnsignedByte()) {
          1 -> utf8[i] = input.readUTF()
          3, 4, 9, 17, 18 -> input.skipNBytes(4)
          // long and double occupy two constant-pool slots
          5, 6 -> { input.skipNBytes(8); ++i }
          7 -> classNameIndex[i] = input.readUnsignedShort()
          8, 16, 19, 20 -> input.skipNBytes(2)
          10, 11 -> methodRefs.add(intArrayOf(input.readUnsignedShort(), input.readUnsignedShort()))
          12 -> { nameAndTypeName[i] = input.readUnsignedShort(); input.skipNBytes(2) }
          15 -> input.skipNBytes(3)
          else -> return null // unrecognized tag: bail rather than mis-parse the rest
        }
        ++i
      }
      val counts = HashMap<String, Int>()
      methodRefs.forEach { (classIdx, natIdx) ->
        val owner = classNameIndex[classIdx]?.let(utf8::get) ?: return@forEach
        val method = nameAndTypeName[natIdx]?.let(utf8::get) ?: return@forEach
        if (method in ARITHMETIC) counts.merge(owner, 1, Int::plus)
      }
      counts
    }
  } catch (_: Exception) {
    null
  }

  private operator fun IntArray.component1() = this[0]
  private operator fun IntArray.component2() = this[1]
}
