package software.sava.build.hardening

import java.io.File

/// Names production classes a suite's exclusion globs silently drop from the
/// mutant population.
///
/// Exclusions exist for test sources sharing the mutated packages (`*Test*`,
/// `Stub*`, `Recording*`, fuzz harnesses) — but a glob cannot tell a fixture
/// from a production class that happens to match it, and a production class an
/// exclusion swallows is not mutated, not counted, and not missed by anything:
/// the suite reads green while the class sits outside the ratchet entirely.
/// This scan makes the swallowed set visible, so each member is either renamed,
/// matched by a narrower glob, or deliberately kept out via
/// `recompileExcludes` — the existing channel for sources that must not enter
/// the mutation compile, which also keeps them out of this scan.
///
/// The mutation recompile deliberately contains the test sources too — they
/// share the mutated packages — so "production" is decided by source location,
/// exactly as the mutated-fakes warning decides it: a class whose source file
/// sits under a test source dir is the globs' intended prey and never reported;
/// what remains is production. Pure given its inputs, so the policy is
/// unit-testable without a build.
object ExclusionAudit {

  data class Swallowed(val binaryName: String, val glob: String)

  @JvmStatic
  fun swallowedProductionClasses(
      classesDir: File,
      targetGlobs: List<String>,
      excludedGlobs: List<String>,
      testSourceDirs: Collection<File>,
  ): List<Swallowed> {
    if (!classesDir.isDirectory) return emptyList()
    val included = targetGlobs.map(::globToRegex)
    val excluded = excludedGlobs.map { it to globToRegex(it) }
    return classesDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .mapNotNull { file ->
          val binaryName = file.relativeTo(classesDir).invariantSeparatorsPath
              .removeSuffix(".class")
              .replace('/', '.')
          // nested classes are skipped: when the outer class is swallowed, reporting
          // each inner class repeats one decision per member. A glob matching *only*
          // a nested class (say `*Recording*` on `Foo$RecordingHelper`) is a real
          // exclusion this scan deliberately under-reports — the source file is the
          // unit a rename or recompileExcludes acts on, and that unit is the outer.
          if (binaryName.contains('$')) return@mapNotNull null
          val sourceRelative = binaryName.replace('.', '/') + ".java"
          if (testSourceDirs.any { dir -> dir.resolve(sourceRelative).isFile }) return@mapNotNull null
          if (included.none { it.matches(binaryName) }) return@mapNotNull null
          val hit = excluded.firstOrNull { (_, regex) -> regex.matches(binaryName) }
              ?: return@mapNotNull null
          Swallowed(binaryName, hit.first)
        }
        .toList()
        .sortedBy { it.binaryName }
  }

  /// The warning naming [swallowed] classes; null when there is nothing to say.
  @JvmStatic
  fun warning(suiteName: String, swallowed: List<Swallowed>): String? =
      if (swallowed.isEmpty()) null
      else "pitest '$suiteName': ${swallowed.size} production class(es) swallowed by excludedClasses — " +
          "not mutated, not counted, and not missed by anything:\n" +
          swallowed.joinToString("\n") { "  ${it.binaryName} (glob '${it.glob}')" } + "\n" +
          "Rename the class, narrow the glob, or keep the source out deliberately via recompileExcludes."

  /// PIT globs: `*` spans package separators, `?` is one character, and `**.` is
  /// PIT's zero-or-more-packages marker — `**.Foo` matches `Foo` and `a.b.Foo`,
  /// which plain `*`-expansion cannot express (it would demand at least one
  /// character before the dot).
  private fun globToRegex(glob: String): Regex {
    val pattern = glob.split("**.").joinToString("(.*\\.)?") { part ->
      buildString {
        part.forEach { c ->
          when (c) {
            '*' -> append(".*")
            '?' -> append('.')
            else -> append(Regex.escape(c.toString()))
          }
        }
      }
    }
    return Regex(pattern)
  }
}
