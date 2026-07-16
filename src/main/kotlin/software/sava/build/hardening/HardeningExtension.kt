package software.sava.build.hardening

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for the 'software.sava.build.feature.hardening' convention plugin: PIT
 * mutation testing and Jazzer coverage-guided fuzzing for hand-picked classes whose
 * correctness is critical (encoders, wire formats, anything that moves money).
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
}
