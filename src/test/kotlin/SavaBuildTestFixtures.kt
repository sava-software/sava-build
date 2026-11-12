import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File

val savaBuildTestRepoVersion: String = savaBuildTestProperty("savaBuild.testRepo.version")

/**
 * The Maven repository the fixtures resolve the plugin from: this test JVM's signed copy of the
 * local repo this build publishes (the 'savaBuildTest' publication), which a fixture's policy
 * accepts where it refuses the unsigned original (FixtureVerification).
 */
val savaBuildTestRepo: File get() = FixtureVerification.pluginRepository

/**
 * A file of the plugin's test publication by extension (its jar, module metadata or POM), in the
 * test repo or in a copy of it a test made.
 */
fun savaBuildTestArtifact(extension: String, repo: File = savaBuildTestRepo): File =
  repo.resolve("software/sava/sava-build/$savaBuildTestRepoVersion/sava-build-$savaBuildTestRepoVersion.$extension")

/** The Gradle property through which a fixture's settings script learns where [savaBuildTestRepo] is. */
const val SAVA_BUILD_TEST_REPO_PROPERTY = "savaBuildFixtureRepository"

/**
 * pluginManagement block for TestKit fixture settings scripts: resolves every
 * 'software.sava.build*' plugin id from [savaBuildTestRepo], with gradlePluginPortal serving
 * the plugin's own dependencies. Fixtures used to includeBuild this checkout instead, but an
 * included build recompiles the plugin inside the first TestKit daemon and serializes parallel
 * test forks on this checkout's build locks.
 *
 * The block names the repository by a Gradle property, which the runners pass on every
 * invocation, and not by its path: the path differs between the test JVMs of one run, and a
 * settings script that spelled it would be a different script in each, compiled again whenever
 * a test class ran in the other one.
 */
val savaBuildPluginManagement: String by lazy {
  "pluginManagement { repositories { maven(url = providers.gradleProperty(\"$SAVA_BUILD_TEST_REPO_PROPERTY\").get()); gradlePluginPortal() }; " +
    "resolutionStrategy.eachPlugin { if (requested.id.id.startsWith(\"software.sava.build\")) { " +
    "useModule(\"software.sava:sava-build:$savaBuildTestRepoVersion\") } } }"
}

/**
 * A fixture run as the helpers configured it. The build it runs, its arguments and its Gradle
 * user home are settled before a test sees it, and what is left to the test is to run it, in an
 * environment or with the plugin-under-test classpath if it wants. TestKit's own `GradleRunner`
 * would let a test point the run at another build, replace its arguments or move its Gradle user
 * home after the helper had checked them, so no test holds one: TestKitRunnerArgumentsTest keeps
 * the name to this file. [ready] is checked again at each run, since a test may edit the fixture
 * between making the runner and running it.
 */
class FixtureRunner internal constructor(
  private val runner: GradleRunner,
  /** A variable no run of this runner carries, whatever environment a test gives it. */
  private val withheld: Set<String>,
  private val ready: () -> Unit,
) {

  /** The arguments the run will carry, the helper's own included. */
  val arguments: List<String> get() = runner.arguments

  /** A copy of the environment the run will have; null for the test JVM's own. */
  val environment: Map<String, String>? get() = runner.environment?.toMap()

  fun build(): BuildResult = ready().let { runner.build() }

  fun buildAndFail(): BuildResult = ready().let { runner.buildAndFail() }

  fun withEnvironment(environment: Map<String, String>): FixtureRunner =
    FixtureRunner(runner.withEnvironment(environment - withheld), withheld, ready)

  fun withPluginClasspath(): FixtureRunner = FixtureRunner(runner.withPluginClasspath(), withheld, ready)

  fun forwardOutput(): FixtureRunner = FixtureRunner(runner.forwardOutput(), withheld, ready)
}

/**
 * A runner over the fixture build at [projectDir] with [arguments], for every fixture but the
 * few [savaBuildRunnerWithOwnPolicy] is for. The fixture runs under the fixture policy, so what
 * it downloads is verified before it runs (FixtureVerification): the policy is installed when
 * the fixture is set up, by [enableTestKitConfigurationCache] or [FixtureVerification.install],
 * before a fixture commits its tree and before its first build, and a fixture without it is
 * refused here, and again when it runs, rather than run unverified. The mode is passed as
 * `strict` on the command line, which no property a fixture writes overrides, and only the
 * options [ALLOWED_OPTION] lists are taken. A fixture script could still exempt a configuration
 * of its own (`disableDependencyVerification`); TestKitRunnerArgumentsTest keeps that word out of
 * the test sources.
 */
fun savaBuildRunner(projectDir: File, vararg arguments: String): FixtureRunner = strictRunner(projectDir, null, arguments)

private fun strictRunner(projectDir: File, gradleUserHome: File?, arguments: Array<out String>): FixtureRunner {
  refuseUnlistedOptions(arguments)
  val installed = {
    check(FixtureVerification.installedIn(projectDir)) {
      "$projectDir does not hold the fixture policy: set the fixture up with enableTestKitConfigurationCache " +
        "(or FixtureVerification.install) before its first build, and leave gradle/verification-* and the " +
        "verification mode in gradle.properties as installed"
    }
    // Gradle reads the policy of the root build, and without a settings script here it would look
    // for a root, and its policy, in the directories above.
    check(SETTINGS_SCRIPTS.any { File(projectDir, it).isFile }) {
      "$projectDir has no settings script, so it is not the root of the build Gradle would run: " +
        "write the fixture's settings before making or running its runner"
    }
  }
  installed()
  val runner = runner(projectDir, *arguments, "--dependency-verification", "strict")
  if (gradleUserHome == null) {
    return FixtureRunner(runner, emptySet(), installed)
  }
  // The own-policy fixtures' home: its daemons serve their offline runs too, and a daemon keeps
  // a read-only cache it started with, so no build there starts with one.
  return FixtureRunner(
    runner.withTestKitDir(gradleUserHome).withEnvironment(System.getenv() - OWN_POLICY_WITHHELD), OWN_POLICY_WITHHELD, installed,
  )
}

private val SETTINGS_SCRIPTS = listOf("settings.gradle.kts", "settings.gradle")

/** What [savaBuildRunnerWithOwnPolicy] adds to a run: refusals are reported, and nothing is downloaded. */
val OWN_POLICY_ARGUMENTS = listOf("--dependency-verification", "lenient", "--offline")

/**
 * The variable that names a second, read-only dependency cache for Gradle to resolve from. A run
 * outside the fixture policy does not carry it: such a cache holds what no strict build of the
 * fixtures put there.
 */
const val READ_ONLY_CACHE_VARIABLE = "GRADLE_RO_DEP_CACHE"

private val OWN_POLICY_WITHHELD = setOf(READ_ONLY_CACHE_VARIABLE)

/**
 * A runner for a fixture whose subject is a dependency verification policy of its own, written,
 * broken and deleted by the test, which the fixture policy cannot stand in for. Such a fixture
 * verifies nothing: it reports refusals instead of failing on them (`--dependency-verification
 * lenient`, since its policy refuses the plugin's test copy). So it must not be a build that
 * downloads. It runs `--offline`, in a Gradle user home of its own
 * ([FixtureVerification.ownPolicyGradleHome]) that only a strict build downloads into: one strict
 * build of this fixture shape, in this JVM, resolves there first what such a fixture asks of a
 * remote repository ([ownPolicyPrimer]), and anything else it asks of one is missing offline: no
 * build in that home, the strict one included, carries [READ_ONLY_CACHE_VARIABLE]. A file
 * repository is read as it is, here as in any fixture: the plugin's test copy is one, and
 * whatever else a test writes into one is the test's own. TestKitRunnerArgumentsTest names the
 * classes that may call this.
 */
fun savaBuildRunnerWithOwnPolicy(projectDir: File, vararg arguments: String): FixtureRunner {
  refuseUnlistedOptions(arguments)
  primeOwnPolicyFixtures()
  return ownPolicyRunner(projectDir, arguments)
}

/**
 * The runner [savaBuildRunnerWithOwnPolicy] returns once the Gradle user home is primed. It is
 * that function's to make; FixtureVerificationTest reads what it would carry without running it.
 */
internal fun ownPolicyRunner(projectDir: File, arguments: Array<out String>): FixtureRunner =
  FixtureRunner(
    runner(projectDir, *arguments, *OWN_POLICY_ARGUMENTS.toTypedArray())
      .withTestKitDir(FixtureVerification.ownPolicyGradleHome)
      .withEnvironment(System.getenv() - OWN_POLICY_WITHHELD),
    OWN_POLICY_WITHHELD,
  ) {}

/**
 * The options a test may pass a fixture run, beside task names and option values, which carry no
 * leading dash: project properties, the system properties the fixtures set, and the Gradle and
 * task options they use. An option is listed once a test needs it and it cannot change which
 * build runs, whether or how that build verifies, or which Gradle user home it resolves in.
 * Everything else is refused, however Gradle would let it be spelled (it takes a long option
 * with one dash too), so `--dependency-verification`, `--write-verification-metadata`,
 * `--export-keys`, `--project-dir`, `--init-script`, `--gradle-user-home` and their short forms
 * do not get past for want of a pattern.
 */
private val ALLOWED_OPTION = Regex(
  // a project property, whatever its value holds (one test passes a line break in it)
  """-P[\s\S]+""" +
    """|-Dorg\.gradle\.java\.installations\.[a-z-]+=.*""" +
    "|-[xq]" +
    "|--(stacktrace|info|quiet|warn|continue|dry-run|parallel|no-parallel|configure-on-demand" +
    "|refresh-dependencies|configuration-cache|no-configuration-cache|all|task)" +
    // the last is the jmh task's own option, which carries JMH's command line
    "|--(max-workers|warning-mode|console|group|jmhArgs)=.+"
)

private fun refuseUnlistedOptions(arguments: Array<out String>) {
  val refused = arguments.filter { it.startsWith("-") && !ALLOWED_OPTION.matches(it) }
  check(refused.isEmpty()) {
    "a fixture run cannot pass $refused: the runner sets the verification mode, the build and the Gradle user home, " +
      "and takes only the options SavaBuildTestFixtures.kt lists (ALLOWED_OPTION)"
  }
}

private val ownPolicyFixturesPrimed = java.util.concurrent.atomic.AtomicBoolean()

/**
 * The strict build that fills the own-policy fixtures' Gradle user home: the fixture shape those
 * classes use, the settings plugin over a `java` project, through `check`, written into [dir]. It
 * is the only kind of build that downloads into that home, so what an offline fixture finds
 * there was verified when it arrived.
 */
internal fun ownPolicyPrimer(dir: File): FixtureRunner {
  enableTestKitConfigurationCache(dir)
  File(dir, "settings.gradle.kts").writeText(
    "$savaBuildPluginManagement\n\nplugins {\n  id(\"software.sava.build\")\n}\n\nrootProject.name = \"own-policy-primer\"\n"
  )
  File(dir, "build.gradle.kts").writeText("plugins {\n  java\n}\n")
  return strictRunner(dir, FixtureVerification.ownPolicyGradleHome, arrayOf("check"))
}

/** Runs [ownPolicyPrimer] once per test JVM, before the first own-policy fixture runs. */
private fun primeOwnPolicyFixtures() = synchronized(ownPolicyFixturesPrimed) {
  if (!ownPolicyFixturesPrimed.get()) {
    val dir = java.nio.file.Files.createTempDirectory("sava-build-own-policy-primer").toFile()
    try {
      ownPolicyPrimer(dir).build()
    } finally {
      dir.deleteRecursively()
    }
    ownPolicyFixturesPrimed.set(true)
  }
}

/**
 * The one place a TestKit runner is made, with `--no-watch-fs`. The fixtures edit and delete
 * files between builds and rely on Gradle noticing; with macOS `fseventsd` backlogged, the
 * TestKit daemon's watcher missed those changes, reporting a deleted output UP-TO-DATE or
 * building without a fixture edit (2026-10-16: one to three different tests failed per full
 * check, and all passed with `--no-watch-fs`). Without watching, Gradle checks the files at the
 * start of each build.
 */
private fun runner(projectDir: File, vararg arguments: String): GradleRunner =
  GradleRunner.create().withProjectDir(projectDir)
    .withArguments(*arguments, "-P$SAVA_BUILD_TEST_REPO_PROPERTY=${savaBuildTestRepo.absolutePath}", "--no-watch-fs")

/**
 * The ':test' task supplies these; reading one straight into a non-null 'String' would
 * fail every test in the suite with a bare 'ExceptionInInitializerError' when the class
 * is initialized without them -- which is what running a test from the IDE's own JUnit
 * runner, rather than delegating to Gradle, does.
 */
fun savaBuildTestProperty(name: String): String = System.getProperty(name)
  ?: error("System property '$name' is not set; run the tests through Gradle (./gradlew test).")

/** A dependency version supplied by ':test' from the Dependabot-visible version catalog. */
fun savaBuildTestVersion(alias: String): String = savaBuildTestProperty("savaBuild.fixtureVersion.$alias")

/**
 * A repository file, or a directory read whole, that a test reads, by its path under the checkout.
 * The ':test' task declares the paths it passes in 'savaBuild.rootFiles' as inputs
 * (rootFilesReadByTests in build.gradle.kts), sources included, since a test asserting over source
 * text needs the text as an input and not only the classes compiled from it; so an edit under one
 * re-runs the tests, and a path that is neither declared nor under a declared directory is refused
 * here rather than read, since the task would stay up to date after the very edit such a read
 * checks for. RootFileReadsTest holds every read to this function.
 */
fun savaBuildRootFile(path: String): File {
  val declared = savaBuildTestProperty("savaBuild.rootFiles").split(',')
  require(declared.any { it == path || path.startsWith("$it/") }) {
    "$path is not among the files build.gradle.kts declares the tests read through the root " +
      "(rootFilesReadByTests, declared: $declared): add it there, or :test stays up to date when it changes"
  }
  return File(savaBuildTestProperty("savaBuild.root"), path)
}

/**
 * The checkout, for a test that runs git there and for nothing else: it keeps its directory to
 * itself, so a file in it is read through savaBuildRootFile or not at all.
 */
class SavaBuildCheckout internal constructor(private val directory: File) {

  /** Runs git in the checkout and returns its output; a non-zero exit fails with that output. */
  fun git(vararg arguments: String): ByteArray {
    val process = ProcessBuilder(listOf("git") + arguments)
      .directory(directory)
      .redirectErrorStream(true)
      .apply { environment()["NO_DNA"] = "1" }
      .start()
    val output = process.inputStream.readBytes()
    check(process.waitFor() == 0) { output.toString(Charsets.UTF_8) }
    return output
  }
}

fun savaBuildCheckout(): SavaBuildCheckout = SavaBuildCheckout(File(savaBuildTestProperty("savaBuild.root")))

/**
 * Sets a TestKit fixture up: the configuration cache for every invocation, and the fixture
 * policy ([FixtureVerification.install]), which it leaves out for a fixture that writes a policy
 * of its own and runs through [savaBuildRunnerWithOwnPolicy].
 *
 * Putting the switch in the consumer's `gradle.properties`, rather than selecting
 * a few task names at the runner call site, makes every task graph exercise Gradle's
 * serialization boundary. That is important for convention plugins: a new task
 * action can capture a precompiled-script instance even when the handful of release
 * entry points covered explicitly remain clean. The file is written whole, so this is
 * the first thing a fixture's setup does, before anything is committed to a fixture's git tree.
 */
fun enableTestKitConfigurationCache(fixtureDir: File, fixturePolicy: Boolean = true) {
  File(fixtureDir, "gradle.properties").writeText(
    "org.gradle.configuration-cache=true\n"
  )
  if (fixturePolicy) {
    FixtureVerification.install(fixtureDir)
  }
}

/**
 * Runs the same TestKit graph once from a cold fixture and once from its stored
 * configuration. The caller retains both results so existing functional assertions
 * can keep inspecting the execution that matters to the feature under test.
 */
fun assertConfigurationCacheRoundTrip(build: () -> BuildResult): Pair<BuildResult, BuildResult> {
  val cold = build()
  assertFalse(
    cold.output.contains("Reusing configuration cache"),
    "expected a cold configuration-cache store, but an entry was reused:\n${cold.output}",
  )
  assertTrue(
    cold.output.contains("Configuration cache entry stored"),
    "cold invocation did not store a configuration-cache entry:\n${cold.output}",
  )

  val reused = build()
  assertTrue(
    reused.output.contains("Reusing configuration cache"),
    "second invocation did not reuse the configuration cache:\n${reused.output}",
  )
  return cold to reused
}
