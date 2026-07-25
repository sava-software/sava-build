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
  private val ARITHMETIC = setOf(
      "add", "subtract", "multiply", "divide", "remainder", "mod", "modPow", "modInverse",
      "negate", "abs", "min", "max", "pow", "gcd", "sqrt",
      "and", "or", "xor", "not", "andNot", "shiftLeft", "shiftRight",
  )

  /// Result of scanning one suite: how many mutated classes call each
  /// candidate's arithmetic, keyed by mutator name.
  data class Finding(val mutator: String, val label: String, val classCount: Int, val callCount: Int)

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

  /// PIT globs: `*` spans package separators, `?` is one character.
  private fun globToRegex(glob: String): Regex {
    val pattern = buildString {
      glob.forEach { c ->
        when (c) {
          '*' -> append(".*")
          '?' -> append('.')
          else -> append(Regex.escape(c.toString()))
        }
      }
    }
    return Regex(pattern)
  }

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
