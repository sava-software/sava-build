package software.sava.build.hardening

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for the 'software.sava.build.feature.hardening' convention plugin: PIT
 * mutation testing and Jazzer coverage-guided fuzzing. Every production class is
 * explicitly owned by a mutation suite or an argued decline; target suites by package
 * wildcard with explicit exclusions rather than allowlist, so new classes are mutated
 * by default — see HARDENING.md for the policy and the mutation-baseline ratchet that
 * every 'pitest<Name>' task is verified against.
 *
 * Each [mutation] suite adds a 'pitest<Name>' task; each [fuzz] target adds a
 * 'fuzz<Name>' task. PIT consumes classes recompiled by 'compileForPitest' at
 * [mutationBytecodeRelease], while Jazzer consumes the separate 'compileForFuzz'
 * output at [bytecodeRelease]. Both recompiles exist because the tools can bundle ASM
 * releases that lag new class-file versions.
 */
abstract class HardeningExtension @Inject constructor(objects: ObjectFactory) {

  /** Bytecode release the sources are recompiled to for the tools (defaults to the
   *  consuming project's Java toolchain). Lower it if a tool's bundled ASM lags the
   *  class-file version the main compilation produces.
   *  The recompile stays even at the toolchain's version: it also strips module-info and
   *  merges the main and test classes into one plain classpath root for the tools. */
  abstract val bytecodeRelease: Property<Int>

  /** Bytecode release for the classes PIT mutates (defaults to [bytecodeRelease]). Set it
   *  separately when the PIT in use reads newer class files than Jazzer does — e.g. a
   *  locally built PIT snapshot with a current ASM. */
  abstract val mutationBytecodeRelease: Property<Int>

  abstract val pitestVersion: Property<String>
  abstract val pitestJunit5PluginVersion: Property<String>
  abstract val jazzerVersion: Property<String>

  /** Version of arcmutate's base plugin ('com.arcmutate:base'), which provides PIT's
   *  licensed plugin set, including incremental analysis. Resolved whenever an
   *  'arcmutate-licence.txt' is present at the project or root-project directory, even
   *  when history reuse is disabled, so a fresh run keeps the licensed mutant
   *  population. Licensed provenance currently audits ArcMutate 1.7.2 and refuses a
   *  different configured/effective version until its lookup contract is reviewed.
   *  Without a licence the dependency is never added and PIT runs exactly as open source. */
  abstract val arcmutateBaseVersion: Property<String>

  /** Generate the shared test-support sources — ConcurrencyHarness, Ports.freePort,
   *  LoopbackHttpServer (a scripted raw-socket HTTP server for transport paths and
   *  status-boundary guards), ManualScheduledExecutor (a deterministic clock-advance
   *  scheduler), RecordingExecutor, and JulRecorder — into the test source set, in
   *  [testSupportPackage]. Off by default;
   *  JulRecorder needs 'java.logging' readable from the test module. Generated rather than
   *  published so the helpers compile inside the consuming repo's own test module —
   *  visible on the module path and PIT's class path alike, with no dependency wiring.
   *  See HARDENING.md 'Shared test scaffolding (generated)'. */
  abstract val generateTestSupport: Property<Boolean>

  /** Package for generated shared test-support classes. Defaults to
   *  `software.sava.hardening.support` for compatibility; unrelated projects should
   *  set a package they own to avoid split-package and namespace collisions. */
  abstract val testSupportPackage: Property<String>

  /**
   * Source FILE NAMES excluded from the PIT/Jazzer recompiles (e.g. "Integ.java"
   * for git-ignored scratch files: present on a dev machine, absent in CI — the
   * exclusion restores parity, and the mutation suite's excludedClasses already
   * keeps them out of the mutant population).
   */
  abstract val recompileExcludes: ListProperty<String>

  /** Simple class names to omit from [generateTestSupport] — e.g. "JulRecorder" in a
   *  repo whose test module cannot read 'java.logging'. Empty by default. */
  abstract val testSupportExcludes: ListProperty<String>

  /**
   * Each suite adds a `pitest<Name>` task. Full reports use
   * `build/reports/pitest/<name>`; `-PmutateOnly` uses the isolated
   * `build/reports/pitest-scoped/<name>` iteration tree.
   */
  val mutation: NamedDomainObjectContainer<MutationSuite> = objects.domainObjectContainer(MutationSuite::class.java)

  /** Each target adds a 'fuzz<Name>' task with its corpus persisted under 'build/fuzz/<name>-corpus'. */
  val fuzz: NamedDomainObjectContainer<FuzzTarget> = objects.domainObjectContainer(FuzzTarget::class.java)
}

/** One PIT run: the classes to mutate and the tests expected to kill the mutants. */
abstract class MutationSuite @Inject constructor(private val name: String) : Named {

  override fun getName(): String = name

  /** Fully qualified class names (or PIT globs) to mutate. */
  abstract val targetClasses: ListProperty<String>

  /** Fully qualified class names (or PIT globs) excluded from mutation — e.g. test
   *  classes living inside a targeted package glob, which would otherwise be mutated
   *  themselves (assertion-removal mutants in tests survive and corrupt the score).
   *  Empty by default. */
  abstract val excludedClasses: ListProperty<String>

  /** PIT glob, or comma-separated globs, matching the test classes to run — e.g.
   *  "com.example.codec.*Test*" or "com.example.a.*Test*,com.example.b.*Test*".
   *  A `~`-prefixed entry is a raw regex, which can omit a class through a negative
   *  lookahead; [excludeTestClass] is the spelling that has to say why. */
  abstract val targetTests: Property<String>

  /**
   * Test classes removed from [targetTests]' selection, keyed by PIT glob with the
   * reason. Empty by default. Set through [excludeTestClass].
   */
  abstract val excludedTestClasses: MapProperty<String, String>

  /**
   * Removes the test classes [glob] matches from PIT's test selection, while
   * leaving them to run under `test`.
   *
   * [targetTests] selects positively — one glob or a comma-separated list of them —
   * so the conventional `*Test*` selects every
   * test class in its packages. Usually that is what you want, and the cheaper
   * lever is to narrow it — but narrowing has a failure mode a widened glob does
   * not: kills come only from `targetTests`, so a killing test outside the pattern
   * is invisible and its mutants are recorded `NO_COVERAGE`. A suite that cannot
   * see its own coverage reports debt it does not have, and the fix is to widen
   * again. Where both are true at once — the wide glob is needed for an honest
   * measurement, and one class inside it must not run per mutant — this is the
   * only structured, reason-bearing way to say so.
   *
   * The cases are specific, and none of them is "this test is slow". PIT orders a
   * mutant's covering tests by recorded time less a direct-hit bonus and stops at
   * the first kill, so a slow test sits last in the list and is reached only when
   * every faster covering test has already failed to kill. Expect no speed-up from
   * a record on a class that is not killing anything; what it removes for certain
   * is one execution in the coverage phase, which runs every selected test once.
   *
   * The real case is correctness. A test whose fixtures are built in a static
   * initializer cannot be trusted here at all: PIT attributes class-init coverage
   * to whichever test triggered initialization, and the mutation phase reuses one
   * JVM across a class's mutants without re-running `<clinit>`, so the fixture can
   * hold a previous mutant's output. A subprocess or network driver is the weaker
   * case — its coverage-phase cost is real, and the record is insurance against the
   * per-mutant cost that arrives once survivors start reaching it. Neither is a
   * reason to stop running the test — only to stop running it *here*.
   *
   * [reason] is required for the same purpose it is on [declineExclusionAudit]: a
   * removal that is not argued is indistinguishable from an oversight, and this
   * one is invisible from the report — the mutants simply have one fewer test that
   * might have killed them. There is no mechanical category to derive here the way
   * there is for excluded production classes (test roots, fuzz harnesses, sibling
   * ownership); every test-selection removal is a cost-against-value judgment, so
   * the argument lives at the call site rather than in a separate decline.
   *
   * Not the only way to omit a test: a `~`-prefixed [targetTests] is a raw regex,
   * which PIT supports, and a negative lookahead drops a class from the selection.
   * What that spelling has no room for is the structure — the omission is fused into
   * the positive selector rather than enumerated beside it, so there is no list of
   * what a suite removed and nowhere for a reason to live. This is the subtraction
   * that is separable and argued; it is not a claim that no other is expressible.
   *
   * Required literally: a blank [reason] fails the build before PIT starts, rather
   * than being reported the way a blank decline is. A decline can be made a no-op
   * because suppression is all it does; this glob reaches PIT's command line
   * whatever is written beside it, so the only moment to insist is before the run.
   *
   * Nothing checks afterwards whether a record still earns its place. That is a
   * question about which tests would actually run, which needs JUnit discovery —
   * compiled class names cannot tell a live test from a helper or from a class
   * whose last `@Test` was deleted, so a check built on them would be confidently
   * wrong about a record that removes nothing. The globs are bound into the
   * report's configuration evidence, so what a run was measured under is recorded
   * even though no advisory reviews it.
   *
   * Only the globs are: [reason] is not part of what PIT did, so rewording one
   * leaves `configurationSha256` alone. That does not preserve a completed report,
   * which also binds the build script's own bytes — it means the rewording is not
   * the thing that invalidated it.
   */
  fun excludeTestClass(glob: String, reason: String) {
    excludedTestClasses.put(glob, reason)
  }

  /** PIT mutator group (default "STRONGER"). */
  abstract val mutators: Property<String>

  /** PIT worker threads (default 4). */
  abstract val threads: Property<Int>

  /**
   * Arguments passed to PIT's child/minion JVMs, not to the Gradle JavaExec process.
   * Empty by default. A suite whose PIT log diagnoses minion memory pressure can set,
   * for example, `minionJvmArgs = listOf("-Xmx1g")`; the ordered values are bound into
   * the report evidence. Each value must be one JVM option beginning with `-`, without
   * whitespace, braces, `#`, quotes, or backslashes; commas are encoded for PIT's list
   * parser.
   */
  abstract val minionJvmArgs: ListProperty<String>

  /**
   * PIT per-test timeout: allowed time = recorded test time x [timeoutFactor]
   * + [timeoutConst] milliseconds. Defaults mirror PIT's own (1.25 / 4000).
   * Suites whose slowest tests run in milliseconds can cut the constant
   * sharply -- hanging-mutant detections then cost their real bound instead
   * of a four-second flat fee -- but a too-tight value converts load-slowed
   * tests into false TIMED_OUT detections, which is baseline churn. Prefer
   * raising the factor over raising the constant: load inflates a test
   * proportionally to its own runtime.
   */
  abstract val timeoutFactor: Property<Double>
  abstract val timeoutConst: Property<Long>

  /**
   * Mutators trialed against this suite and deliberately left off, keyed by mutator
   * name with the measured reason. Set through [declineMutator].
   */
  abstract val declinedMutators: MapProperty<String, String>

  /**
   * Records a measured decision **not** to enable [mutator] here, silencing the
   * blind-spot advice for it. [reason] carries the measurement -- what the trial
   * generated, and why that was not worth the baseline -- because a suppression
   * whose argument is not written down is indistinguishable from an oversight, and
   * the next reader has to re-run the trial to find out which it was.
   *
   * Only for candidates that were *measured*. A decline recorded to quiet a warning
   * nobody investigated is the failure this whole mechanism exists to surface, and
   * it will read as settled to everyone who comes after. A blank [reason] therefore
   * does not suppress anything: the advice keeps firing and the empty decline is
   * reported.
   *
   * Declines go stale like baseline rows do. Enable the mutator, or remove the
   * arithmetic it would have rewritten, and the decline is reported as deletable
   * rather than sitting on as a fossil.
   */
  fun declineMutator(mutator: String, reason: String) {
    declinedMutators.put(mutator, reason)
  }

  /**
   * Exclusion globs whose swallowed production classes are a deliberate opt-out
   * rather than a hole, keyed by the glob exactly as written in [excludedClasses],
   * with the reason. Set through [declineExclusionAudit].
   */
  abstract val declinedExclusionAudits: MapProperty<String, String>

  /**
   * Records that [glob] excludes production classes **on purpose**, silencing the
   * excluded-production-class advisory for the classes it swallows.
   *
   * The targeting policy endorses three exclusion categories; the audit derives two
   * of them and cannot derive this one. Test and fixture sources are found by source
   * root, and classes owned by a sibling suite are found by comparing suite scopes —
   * but "generated bindings", "vendored code", "an integration main that needs live
   * credentials" is a *judgment*, indistinguishable from a forgotten glob by
   * inspection of the globs alone. So it is written down instead: [reason] says what
   * the classes are and what carries their correctness instead of the ratchet, which
   * is the same argument an accepted baseline row owes.
   *
   * A blank [reason] suppresses nothing and is itself reported — a decline recorded
   * to quiet a warning nobody investigated reads as settled to everyone after, which
   * is the failure this mechanism exists to surface. Declines go stale like baseline
   * rows: when [glob] stops swallowing any production class, it is reported as
   * deletable rather than fossilising.
   */
  fun declineExclusionAudit(glob: String, reason: String) {
    declinedExclusionAudits.put(glob, reason)
  }
}

/** One Jazzer entry point: a class with 'public static void fuzzerTestOneInput(byte[])'.
 *  Keeping the target free of Jazzer imports lets it compile with the regular test sources. */
abstract class FuzzTarget @Inject constructor(private val name: String) : Named {

  override fun getName(): String = name

  /** Fully qualified name of the fuzz target class. */
  abstract val targetClass: Property<String>

  /** Maximum libFuzzer input length in bytes ('-max_len'; unset leaves libFuzzer's
   *  default). Cap it when per-execution cost grows super-linearly with input size and
   *  large inputs reach no coverage small ones cannot — e.g. an O(n²) codec. Oversized
   *  corpus entries from earlier runs are truncated on load, not lost. */
  abstract val maxLen: Property<Int>

  /** Directory of committed seed inputs (one file per input) passed to libFuzzer as a
   *  read-only extra corpus. Essential for structured formats a mutator cannot reach
   *  from scratch (a transaction's header, offsets, and lengths must all agree before
   *  any body-walking code runs); pointless for formats where every prefix is valid
   *  (e.g. a raw codec). Unset leaves the run seedless. Seeds are never mutated in
   *  place — libFuzzer copies newly interesting inputs into the writable corpus. */
  abstract val seedCorpus: DirectoryProperty

  /** Recorded reason this target carries no [seedCorpus]. Set through [declineSeedCorpus]. */
  abstract val declinedSeedCorpus: Property<String>

  /**
   * Records a measured decision that this target needs no committed corpus,
   * silencing the corpus-less advice.
   *
   * Weigh it against both jobs a corpus does, because they are independent and only
   * the first is about the input format. A **bootstrap** corpus buys coverage a
   * mutator would take too long to reach on its own — unnecessary where every prefix
   * is valid. A **regression** corpus is where a finding lands: committed
   * reproducers replayed by `check` are how a fixed crash stays fixed, and that
   * applies whatever the format looks like. "A mutator reaches this from scratch"
   * answers only the first, so it is rarely a sufficient reason on its own.
   *
   * A blank [reason] does not suppress anything. A target that later declares a
   * [seedCorpus] has its decline reported as deletable.
   */
  fun declineSeedCorpus(reason: String) {
    declinedSeedCorpus.set(reason)
  }
}
