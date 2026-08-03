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
/// Exclusions also partition — the targeting policy's "owned by another
/// suite" — and that category is subtracted via [SuiteScope] rather than
/// reported, so the advisory names holes, not handoffs.
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

  /// A sibling suite's mutation scope. The targeting policy's partition —
  /// "classes owned by another suite" — is a legitimate exclusion category, so
  /// a class some sibling actually mutates is the partition working, not a
  /// hole. Ownership must be *effective*: the sibling's own exclusions are part
  /// of the scope, because a class every suite excludes has no owner anywhere
  /// and must stay a finding in each suite that swallows it *(casebook: the
  /// partition the audit called a hole)*.
  data class SuiteScope(val targetGlobs: List<String>, val excludedGlobs: List<String>)

  @JvmStatic
  fun swallowedProductionClasses(
      classesDir: File,
      targetGlobs: List<String>,
      excludedGlobs: List<String>,
      testSourceDirs: Collection<File>,
      siblingScopes: List<SuiteScope> = emptyList(),
  ): List<Swallowed> {
    if (!classesDir.isDirectory) return emptyList()
    val included = targetGlobs.map(::globToRegex)
    val excluded = excludedGlobs.map { it to globToRegex(it) }
    val siblings = siblingScopes.map { scope ->
      scope.targetGlobs.map(::globToRegex) to scope.excludedGlobs.map(::globToRegex)
    }
    // one read of each package's test sources per audit, shared by every candidate
    // in that package — the declaration scan below runs only for actual swallow
    // candidates, so the common all-green run costs nothing beyond the exact-file
    // existence checks
    val packageSources = HashMap<String, List<String>>()
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
          // handed off, not swallowed: a sibling suite that would actually mutate
          // this class (its targets match, its own exclusions do not) owns it
          if (siblings.any { (targets, excludes) ->
                targets.any { it.matches(binaryName) } && excludes.none { it.matches(binaryName) }
              }) return@mapNotNull null
          // A secondary top-level class declared in a differently-named test file
          // (FooTests.java carrying 'class Helper') has no Helper.java anywhere,
          // so the exact-file check above misreads it as production. It is
          // test-owned iff some .java in the same package under a test source dir
          // declares it AT COLUMN 0 — top-level declarations always are, while an
          // inner 'class Helper' is indented and compiles to Outer$Helper, so it
          // can never own a top-level Helper.class. Checked last, only for actual
          // swallow candidates: the declaration scan reads source text, and the
          // common all-green run must not pay for it.
          if (declaredInTestSource(binaryName, testSourceDirs, packageSources)) return@mapNotNull null
          Swallowed(binaryName, hit.first)
        }
        .toList()
        .sortedBy { it.binaryName }
  }

  /// What a suite's recorded declines did to its swallowed set: the classes still
  /// worth reporting, the declines that argued for nothing, and the declines whose
  /// reason was left blank.
  data class Declined(
    val reported: List<Swallowed>,
    val staleGlobs: List<String>,
    val blankGlobs: List<String>,
  )

  /// Applies a suite's `declineExclusionAudit` records to [swallowed]. A glob
  /// declined with a written reason takes its classes out of the report — the
  /// deliberate-opt-out category the scan cannot derive, argued instead. A blank
  /// reason suppresses nothing and is named: a decline recorded to quiet a warning
  /// nobody investigated is the failure the advisory exists to surface. A decline
  /// matching no swallowed class is named too, on the same terms as a stale mutator
  /// decline — the exclusion it argued about is gone, and the argument should go
  /// with it.
  @JvmStatic
  fun applyDeclines(swallowed: List<Swallowed>, declines: Map<String, String>): Declined {
    if (declines.isEmpty()) return Declined(swallowed, emptyList(), emptyList())
    val argued = declines.filterValues { it.isNotBlank() }.keys
    val blank = declines.filterValues { it.isBlank() }.keys
    val hitGlobs = swallowed.mapTo(mutableSetOf()) { it.glob }
    return Declined(
        swallowed.filterNot { it.glob in argued },
        declines.keys.filterNot { it in hitGlobs }.sorted(),
        blank.sorted(),
    )
  }

  /// The warning naming declines that argued for nothing; null when every decline
  /// still matches a swallowed class.
  @JvmStatic
  fun staleDeclineWarning(suiteName: String, staleGlobs: List<String>): String? =
      if (staleGlobs.isEmpty()) null else
        "pitest '$suiteName': ${staleGlobs.size} declineExclusionAudit record(s) match no swallowed " +
            "production class — the exclusion they argued about no longer swallows anything; delete them:\n" +
            staleGlobs.joinToString("\n") { "  $it" }

  /// The warning naming declines recorded without a reason; null when every decline
  /// carries one. Their classes stay in the report — the decline suppresses nothing.
  @JvmStatic
  fun blankDeclineWarning(suiteName: String, blankGlobs: List<String>): String? =
      if (blankGlobs.isEmpty()) null else
        "pitest '$suiteName': ${blankGlobs.size} declineExclusionAudit record(s) carry no reason and " +
            "therefore suppress nothing — say what the classes are and what carries their correctness " +
            "instead of the ratchet, or drop the record:\n" +
            blankGlobs.joinToString("\n") { "  $it" }

  /// The warning naming [swallowed] classes; null when there is nothing to say.
  @JvmStatic
  fun warning(suiteName: String, swallowed: List<Swallowed>): String? =
      if (swallowed.isEmpty()) null
      else "pitest '$suiteName': ${swallowed.size} production class(es) swallowed by excludedClasses — " +
          "not mutated, not counted, and not missed by anything:\n" +
          swallowed.joinToString("\n") { "  ${it.binaryName} (glob '${it.glob}')" } + "\n" +
          "Rename the class, narrow the glob, keep the source out of the recompile via " +
          "recompileExcludes, or — when the exclusion is a deliberate opt-out (generated " +
          "bindings, vendored code, a live-credential main) — record the argument with " +
          "declineExclusionAudit(\"<glob>\", \"<what these are, and what carries their " +
          "correctness instead>\")."

  // PIT-glob parsing lives in PitGlobs, shared with MutatorAdvice so the two
  // scans can never disagree on which classes a glob selects.
  private fun globToRegex(glob: String): Regex = PitGlobs.toRegex(glob)

  // Column-0 anchored: top-level declarations start the line, an inner class's
  // indentation excludes it (it compiles to Outer$Inner and owns nothing
  // top-level), and a javadoc's ' * class Helper' never reaches the keyword
  // (the modifier group cannot consume '*'). Same-line annotations are allowed
  // as tokens so '@SuppressWarnings("x") final class Helper' still counts.
  private fun declaredInTestSource(
      binaryName: String,
      testSourceDirs: Collection<File>,
      packageSources: MutableMap<String, List<String>>,
  ): Boolean {
    val simpleName = binaryName.substringAfterLast('.')
    val packagePath = binaryName.substringBeforeLast('.', "").replace('.', '/')
    // token separators are same-line whitespace only: '\s+' spanning newlines let
    // a column-0 word line re-admit the indented inner declaration below it, and
    // a declaration whose modifiers wrap still matches — the 'class <Name>' line
    // itself starts at column 0
    val declaration = Regex(
        "(?m)^(?:(?:@\\w+(?:\\([^)]*\\))?|[\\w-]+)[ \\t]+)*(?:class|interface|enum|record)[ \\t]+" +
            "${Regex.escape(simpleName)}\\b")
    val sources = packageSources.getOrPut(packagePath) {
      testSourceDirs.flatMap { dir ->
        val packageDir = if (packagePath.isEmpty()) dir else dir.resolve(packagePath)
        packageDir.listFiles { file -> file.isFile && file.name.endsWith(".java") }
            ?.map { it.readText() }
            .orEmpty()
      }
    }
    return sources.any { declaration.containsMatchIn(it) }
  }
}
