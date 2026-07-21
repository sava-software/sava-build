package software.sava.build.hardening

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for the 'software.sava.build.feature.hardening' convention plugin: PIT
 * mutation testing and Jazzer coverage-guided fuzzing for the classes whose correctness
 * is critical (encoders, wire formats, anything that moves money). Target suites by
 * package wildcard with explicit exclusions rather than allowlist, so new classes are
 * mutated by default — see HARDENING.md for the policy and the mutation-baseline
 * ratchet that every 'pitest<Name>' task is verified against.
 *
 * Each [mutation] suite adds a 'pitest<Name>' task; each [fuzz] target adds a
 * 'fuzz<Name>' task. Both consume classes recompiled at [bytecodeRelease] by the shared
 * 'compileForPitest' task, because PIT and Jazzer bundle ASM releases that lag new
 * class-file versions.
 */
abstract class HardeningExtension @Inject constructor(objects: ObjectFactory) {

  /** Bytecode release the sources are recompiled to for the tools (default 25). Lower it
   *  if a tool's bundled ASM lags the class-file version the main compilation produces.
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

  /** Version of arcmutate's base plugin ('com.arcmutate:base'), which provides PIT
   *  incremental analysis. Only resolved when an 'arcmutate-licence.txt' is present at
   *  the project or root-project directory — without one the dependency is never added
   *  and PIT runs exactly as open source. */
  abstract val arcmutateBaseVersion: Property<String>

  /** Each suite adds a 'pitest<Name>' task reporting to 'build/reports/pitest/<name>'. */
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

  /** PIT glob matching the test classes to run, e.g. "com.example.codec.*Test*". */
  abstract val targetTests: Property<String>

  /** PIT mutator group (default "STRONGER"). */
  abstract val mutators: Property<String>

  /** PIT worker threads (default 4). */
  abstract val threads: Property<Int>
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
}
