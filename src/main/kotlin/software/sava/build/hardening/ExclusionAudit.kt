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

  /// A named suite for a whole-population ownership check. [suiteName] should be
  /// unique in the population being checked (a Gradle task path is a good id in a
  /// multi-project build). A class is owned only when a target matches and no
  /// exclusion does — the same effective-ownership rule [SuiteScope] uses.
  data class OwnershipScope(
    val suiteName: String,
    val targetGlobs: List<String>,
    val excludedGlobs: List<String>,
  )

  /// A deliberate ownership opt-out. It is intentionally tied to a suite and
  /// one of that suite's effective exclusion globs: an arbitrary waiver for a
  /// never-targeted class would make an allowlist hole read as a decision. To
  /// decline such a class, first put it in a target universe, exclude it, and
  /// record why correctness is carried elsewhere.
  data class ExplicitDecline(
    val suiteName: String,
    val glob: String,
    val reason: String,
  )

  data class ExclusionMatch(val suiteName: String, val glob: String)

  data class OwnedClass(val binaryName: String, val suiteNames: List<String>)

  data class DeclinedClass(val binaryName: String, val declines: List<ExplicitDecline>)

  /// A production class with no effective owner and no argued decline. An empty
  /// [excludedBy] means no suite target matched at all; otherwise the entries name
  /// the first effective exclusion in every suite whose target did match.
  data class UncoveredClass(val binaryName: String, val excludedBy: List<ExclusionMatch>)

  /// A complete, deterministic partition of the supplied production population.
  /// [staleDeclines] matched no currently-unowned excluded class; [blankDeclines]
  /// matched or not, but cannot suppress anything without an argument.
  data class OwnershipCoverage(
    val owned: List<OwnedClass>,
    val declined: List<DeclinedClass>,
    val uncovered: List<UncoveredClass>,
    val staleDeclines: List<ExplicitDecline>,
    val blankDeclines: List<ExplicitDecline>,
  )

  /// Enumerates compiled production binary classes, including nested, anonymous,
  /// and compiler-generated member classes. Test ownership follows source
  /// ownership: `Foo$Inner.class` is test-owned when `Foo.java` is under a test
  /// source root, and a nested class of a secondary top-level test declaration is
  /// test-owned too. JVM metadata classes have no mutable behavior and are omitted.
  ///
  /// This is the filesystem boundary for [ownershipCoverage]; the coverage
  /// decision itself stays pure and can be tested from class-name fixtures.
  @JvmStatic
  fun productionClassNames(
      classesDir: File,
      testSourceDirs: Collection<File>,
  ): List<String> {
    if (!classesDir.isDirectory) return emptyList()
    val packageSources = HashMap<String, List<String>>()
    return binaryClassNames(classesDir)
        .filterNot(::isJvmMetadataClass)
        .filterNot { isTestOwned(it, testSourceDirs, packageSources) }
        .distinct()
        .sorted()
        .toList()
  }

  /// Partitions [productionClasses] into effective suite ownership, explicit
  /// declines, and uncovered classes. Matching uses PIT's glob grammar. A decline
  /// is effective only for an otherwise-unowned class whose target matched in the
  /// named suite and whose *first* matching exclusion is the decline's exact glob;
  /// this mirrors [swallowedProductionClasses] and [applyDeclines], including
  /// overlapping-exclusion order. Thus a broad allowlist cannot hide a class that
  /// no suite targets, and a decline becomes stale as soon as a sibling owns the
  /// class.
  @JvmStatic
  fun ownershipCoverage(
      productionClasses: Collection<String>,
      scopes: List<OwnershipScope>,
      declines: List<ExplicitDecline> = emptyList(),
  ): OwnershipCoverage {
    data class CompiledScope(
      val name: String,
      val targets: List<Regex>,
      val exclusions: List<Pair<String, Regex>>,
    )

    val compiledScopes = scopes.map { scope ->
      CompiledScope(
        scope.suiteName,
        scope.targetGlobs.map(::globToRegex),
        scope.excludedGlobs.map { it to globToRegex(it) },
      )
    }
    val declineKeys = declines.map { ExclusionMatch(it.suiteName, it.glob) }
    val hitDeclines = HashSet<Int>()
    val owned = ArrayList<OwnedClass>()
    val declined = ArrayList<DeclinedClass>()
    val uncovered = ArrayList<UncoveredClass>()

    productionClasses.asSequence()
        .filterNot(::isJvmMetadataClass)
        .distinct()
        .sorted()
        .forEach { binaryName ->
          val owners = compiledScopes.asSequence()
              .filter { scope ->
                scope.targets.any { it.matches(binaryName) } &&
                    scope.exclusions.none { (_, regex) -> regex.matches(binaryName) }
              }
              .map { it.name }
              .distinct()
              .sorted()
              .toList()
          if (owners.isNotEmpty()) {
            owned += OwnedClass(binaryName, owners)
            return@forEach
          }

          val blockers = compiledScopes.mapNotNull { scope ->
            if (scope.targets.none { it.matches(binaryName) }) return@mapNotNull null
            scope.exclusions.firstOrNull { (_, regex) -> regex.matches(binaryName) }
                ?.let { (glob, _) -> ExclusionMatch(scope.name, glob) }
          }.distinct()
              .sortedWith(compareBy(ExclusionMatch::suiteName, ExclusionMatch::glob))

          val argued = declines.withIndex().mapNotNull { (index, decline) ->
            if (declineKeys[index] !in blockers) return@mapNotNull null
            hitDeclines += index
            decline.takeIf { it.reason.isNotBlank() }
          }.sortedWith(
              compareBy(ExplicitDecline::suiteName, ExplicitDecline::glob, ExplicitDecline::reason))

          if (argued.isNotEmpty()) {
            declined += DeclinedClass(binaryName, argued)
          } else {
            uncovered += UncoveredClass(binaryName, blockers)
          }
        }

    val stale = declines.withIndex().filterNot { it.index in hitDeclines }.map { it.value }
        .sortedWith(
            compareBy(ExplicitDecline::suiteName, ExplicitDecline::glob, ExplicitDecline::reason))
    val blank = declines.filter { it.reason.isBlank() }
        .sortedWith(
            compareBy(ExplicitDecline::suiteName, ExplicitDecline::glob, ExplicitDecline::reason))
    return OwnershipCoverage(owned, declined, uncovered, stale, blank)
  }

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
    val swallowed = binaryClassNames(classesDir)
        .filterNot(::isJvmMetadataClass)
        .mapNotNull { binaryName ->
          if (included.none { it.matches(binaryName) }) return@mapNotNull null
          val hit = excluded.firstOrNull { (_, regex) -> regex.matches(binaryName) }
              ?: return@mapNotNull null
          if (isTestOwned(binaryName, testSourceDirs, packageSources)) return@mapNotNull null
          // handed off, not swallowed: a sibling suite that would actually mutate
          // this class (its targets match, its own exclusions do not) owns it
          if (siblings.any { (targets, excludes) ->
                targets.any { it.matches(binaryName) } && excludes.none { it.matches(binaryName) }
              }) return@mapNotNull null
          Swallowed(binaryName, hit.first)
        }
        .toList()

    // Keep the historical one-decision-per-source signal when an outer and its
    // nested classes are swallowed by the same glob. Crucially, a nested class
    // remains visible when only it matches, when it hits a different exclusion,
    // or when a sibling owns the outer but not the nested binary class.
    val swallowedKeys = swallowed.mapTo(HashSet()) { it.binaryName to it.glob }
    return swallowed.filterNot { candidate ->
      enclosingBinaryNames(candidate.binaryName)
          .any { outer -> (outer to candidate.glob) in swallowedKeys }
    }.sortedBy { it.binaryName }
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

  private fun binaryClassNames(classesDir: File): Sequence<String> = classesDir.walkTopDown()
      .asSequence()
      .filter { it.isFile && it.name.endsWith(".class") }
      .map { file ->
        file.relativeTo(classesDir).invariantSeparatorsPath
            .removeSuffix(".class")
            .replace('/', '.')
      }

  private fun isJvmMetadataClass(binaryName: String): Boolean =
      binaryName == "module-info" || binaryName == "package-info" ||
          binaryName.endsWith(".module-info") || binaryName.endsWith(".package-info")

  /// The binary names that can own [binaryName]'s source, exact name first.
  /// Exact-first matters because `$` is legal in a top-level Java identifier:
  /// `Foo$Bar.java` owns `Foo$Bar.class`, while absent that file the same binary
  /// spelling ordinarily means member `Bar` in `Foo.java`.
  private fun sourceOwnerCandidates(binaryName: String): Sequence<String> = sequence {
    val packageName = binaryName.substringBeforeLast('.', "")
    var simpleName = binaryName.substringAfterLast('.')
    while (true) {
      yield(if (packageName.isEmpty()) simpleName else "$packageName.$simpleName")
      val separator = simpleName.lastIndexOf('$')
      if (separator < 0) break
      simpleName = simpleName.substring(0, separator)
    }
  }

  private fun enclosingBinaryNames(binaryName: String): Sequence<String> =
      sourceOwnerCandidates(binaryName).drop(1)

  private fun isTestOwned(
      binaryName: String,
      testSourceDirs: Collection<File>,
      packageSources: MutableMap<String, List<String>>,
  ): Boolean = sourceOwnerCandidates(binaryName).any { sourceOwner ->
    val sourceRelative = sourceOwner.replace('.', '/') + ".java"
    testSourceDirs.any { dir -> dir.resolve(sourceRelative).isFile } ||
        declaredInTestSource(sourceOwner, testSourceDirs, packageSources)
  }

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
