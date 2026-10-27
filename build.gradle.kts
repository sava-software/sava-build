import groovy.json.JsonSlurper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

private data class LocalPublicationSourceSnapshot(
  val gitState: String,
  val gitCommit: String,
  val gitTree: String,
  val gitStatusSha256: String,
  val sourceStateSha256: String,
)

private class LocalPublicationSourceSnapshots {
    fun capture(checkout: File): LocalPublicationSourceSnapshot {
      val revision = git(checkout, "rev-parse", "HEAD", "HEAD^{tree}")
        .toString(Charsets.UTF_8)
        .trimEnd('\n', '\r')
        .lines()
      check(revision.size == 2 && revision.all { it.matches(GIT_OBJECT) } &&
          revision[0].length == revision[1].length) {
        "local publication did not resolve a full Git commit/tree identity: $revision"
      }
      val status = git(
        checkout,
        "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=none",
      )
      return LocalPublicationSourceSnapshot(
        gitState = if (status.isEmpty()) "clean" else "dirty",
        gitCommit = revision[0],
        gitTree = revision[1],
        gitStatusSha256 = sha256(status),
        sourceStateSha256 = sourceStateSha256(checkout),
      )
    }

    /** Hash every tracked or non-ignored untracked path in this trusted local checkout. */
    private fun sourceStateSha256(checkout: File): String {
      val paths = git(checkout, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
        .toString(Charsets.UTF_8)
        .split('\u0000')
        .filter(String::isNotEmpty)
        .sorted()
      val digest = MessageDigest.getInstance("SHA-256")
      digest.update("sava-build-local-source-state-v1\u0000".toByteArray(Charsets.UTF_8))
      paths.forEach { relative ->
        val path = checkout.toPath().resolve(relative)
        updateLengthFramed(digest, relative.toByteArray(Charsets.UTF_8))
        when {
          !Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> digest.update('m'.code.toByte())
          Files.isSymbolicLink(path) -> {
            digest.update('l'.code.toByte())
            updateLengthFramed(
              digest,
              Files.readSymbolicLink(path).toString().toByteArray(Charsets.UTF_8),
            )
          }
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
            digest.update('f'.code.toByte())
            updateLengthFramed(digest, fileSha256(path.toFile()))
          }
          else -> error("local publication cannot content-hash Git path '$relative'")
        }
      }
      return hex(digest.digest())
    }

    private fun updateLengthFramed(digest: MessageDigest, bytes: ByteArray) {
      digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
      digest.update(bytes)
    }

    private val GIT_OBJECT = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")

    private fun git(checkout: File, vararg arguments: String): ByteArray {
      val process = ProcessBuilder(listOf("git") + arguments)
        .directory(checkout)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .apply { environment()["NO_DNA"] = "1" }
        .start()
      val output = process.inputStream.readBytes()
      val exit = process.waitFor()
      check(exit == 0) {
        "local publication could not capture Git provenance: " +
          (listOf("git") + arguments).joinToString(" ") + " exited $exit"
      }
      return output
    }

    private fun sha256(bytes: ByteArray): String =
      hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fileSha256(file: File): ByteArray {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      }
      return digest.digest()
    }

    private fun hex(bytes: ByteArray): String =
      bytes.joinToString("") { "%02x".format(it) }
  }

private class WriteLocalPublicationProvenance(
  private val checkoutPath: String,
  private val sourceJarPath: String,
  private val repositoryJarPath: String,
  private val provenancePath: String,
) : Action<Task>, java.io.Serializable {

  override fun execute(task: Task) {
    val checkout = File(checkoutPath)
    val sourceJar = File(sourceJarPath)
    val repoJar = File(repositoryJarPath)
    val provenance = File(provenancePath)
    check(sourceJar.isFile) { "local publication source JAR is missing: $sourceJar" }
    check(repoJar.isFile) { "local publication repository JAR is missing: $repoJar" }

    val sourceJarSha256 = sha256(sourceJar)
    val repoJarSha256 = sha256(repoJar)
    check(sourceJarSha256 == repoJarSha256) {
      "local publication repository JAR does not match its source JAR " +
        "($sourceJarSha256 != $repoJarSha256): $repoJar"
    }
    val sourceSnapshot = LocalPublicationSourceSnapshots().capture(checkout)
    val provenanceText = buildString {
      appendLine("schema\t2")
      appendLine("gitState\t${sourceSnapshot.gitState}")
      appendLine("gitCommit\t${sourceSnapshot.gitCommit}")
      appendLine("gitTree\t${sourceSnapshot.gitTree}")
      appendLine("gitStatusSha256\t${sourceSnapshot.gitStatusSha256}")
      appendLine("sourceStateSha256\t${sourceSnapshot.sourceStateSha256}")
      appendLine("publishedAtUtc\t${Instant.now()}")
      appendLine("jarSha256\t$repoJarSha256")
    }

    val staging = provenance.parentFile.resolve(".${provenance.name}.${UUID.randomUUID()}.tmp")
    try {
      Files.createDirectories(provenance.parentFile.toPath())
      Files.writeString(
        staging.toPath(),
        provenanceText,
        Charsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
      )
      Files.move(
        staging.toPath(),
        provenance.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(staging.toPath())
    }
    task.logger.lifecycle(
      "Local publication provenance: {} (source snapshot at publication: commit {}; {} worktree; " +
        "source-state SHA-256 {}; JAR SHA-256 {})",
      provenance,
      sourceSnapshot.gitCommit,
      sourceSnapshot.gitState,
      sourceSnapshot.sourceStateSha256,
      repoJarSha256,
    )
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return hex(digest.digest())
  }

  private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

private class InvalidateLocalPublicationProvenance(
  private val provenancePath: String,
) : Action<Task>, java.io.Serializable {

  override fun execute(task: Task) {
    val provenance = File(provenancePath)
    Files.deleteIfExists(provenance.toPath())
    task.logger.info(
      "Invalidated local publication provenance before replacing the static Maven coordinate: {}",
      provenance,
    )
  }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

plugins {
  `kotlin-dsl`
  id("maven-publish")
  id("signing")
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

// ./gradlew --write-verification-metadata pgp,sha256 check generatePrecompiledScriptPluginAccessors
dependencies {
  // https://github.com/autonomousapps/dependency-analysis-gradle-plugin
  // https://plugins.gradle.org/plugin/com.autonomousapps.dependency-analysis
  // https://mvnrepository.com/artifact/com.autonomousapps.dependency-analysis/com.autonomousapps.dependency-analysis.gradle.plugin
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:3.19.2")

  // https://github.com/gradle/foojay-toolchains
  // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  // https://github.com/melix/jmh-gradle-plugin
  // https://plugins.gradle.org/plugin/me.champeau.jmh
  implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.3")
  // https://github.com/gradlex-org/java-module-dependencies
  implementation("org.gradlex:java-module-dependencies:1.13.2")
  // https://github.com/gradlex-org/java-module-testing
  implementation("org.gradlex:java-module-testing:1.8.1")
  // https://github.com/gradlex-org/jvm-dependency-conflict-resolution
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.5")
  // https://github.com/gradlex-org/extra-java-module-info
  implementation("org.gradlex:extra-java-module-info:1.14.2")

  testImplementation(gradleTestKit())
  // https://github.com/junit-team/junit-framework
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  // KGP 2.4.10 creates this isolated publishing-helper configuration with BC 1.84.
  // Constraints preserve its defaultDependencies (adding dependencies would suppress them).
  // Remove this floor when Gradle's bundled Kotlin tooling supplies BC >= 1.86.
  constraints {
    val bouncyCastleVersion = "1.86"
    add("kotlinBouncyCastleConfiguration", "org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")
    add("kotlinBouncyCastleConfiguration", "org.bouncycastle:bcutil-jdk18on:$bouncyCastleVersion")
    add("kotlinBouncyCastleConfiguration", "org.bouncycastle:bcpkix-jdk18on:$bouncyCastleVersion")
    add("kotlinBouncyCastleConfiguration", "org.bouncycastle:bcpg-jdk18on:$bouncyCastleVersion")
  }
}

// The default tool versions of the hardening feature live in gradle/libs.versions.toml
// (where Dependabot can update them) and are baked into the plugin as constants here,
// because precompiled script plugins run in consumer builds that cannot see this
// build's version catalog.
val libsCatalog = the<VersionCatalogsExtension>().named("libs")
val hardeningToolVersions = listOf(
  "PITEST" to "pitest",
  "PITEST_JUNIT5_PLUGIN" to "pitest-junit5-plugin",
  "JAZZER" to "jazzer",
  "ARCMUTATE_BASE" to "arcmutate-base"
).associate { (constant, alias) ->
  constant to libsCatalog.findVersion(alias)
    .orElseThrow { IllegalStateException("Version '$alias' is missing from gradle/libs.versions.toml") }
    .requiredVersion
}

val generateHardeningToolDefaults = tasks.register("generateHardeningToolDefaults") {
  description = "Generates HardeningToolDefaults.kt from the versions in gradle/libs.versions.toml"
  // locals so the lambda below does not capture the script instance, which the
  // configuration cache cannot serialize
  val versions = hardeningToolVersions
  inputs.property("versions", versions)
  val outputDir = layout.buildDirectory.dir("generated/sources/hardening-tool-defaults/kotlin")
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("software/sava/build/hardening/HardeningToolDefaults.kt").asFile
    file.parentFile.mkdirs()
    file.writeText(buildString {
      appendLine("// Generated by the 'generateHardeningToolDefaults' task; edit gradle/libs.versions.toml instead.")
      appendLine("package software.sava.build.hardening")
      appendLine()
      appendLine("internal object HardeningToolDefaults {")
      versions.forEach { (constant, version) ->
        appendLine("  const val $constant = \"$version\"")
      }
      appendLine("}")
    })
  }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateHardeningToolDefaults)

// The agent-instructions template in HARDENING.md is copied into each consuming repo's
// AGENTS.md. Baking its '> ' blockquote lines into the plugin lets 'hardeningAgentTemplate'
// print the block that matches the installed version rather than moving main.
val generateHardeningTemplateDigest = tasks.register("generateHardeningTemplateDigest") {
  description = "Generates HardeningTemplateDigest.kt from the agent-instructions template in HARDENING.md"
  val docFile = layout.projectDirectory.file("HARDENING.md").asFile
  inputs.file(docFile)
  val outputDir = layout.buildDirectory.dir("generated/sources/hardening-template-digest/kotlin")
  outputs.dir(outputDir)
  doLast {
    val lines = docFile.readLines()
    val start = lines.indexOfFirst { it.trim() == "## Agent instructions template" }
    check(start >= 0) { "HARDENING.md has no '## Agent instructions template' section" }
    val template = lines.drop(start + 1)
      .takeWhile { !it.startsWith("## ") }
      .filter { it.startsWith(">") }
      .joinToString("\n") { it.trimEnd() }
    check(template.isNotEmpty()) { "the agent-instructions template block in HARDENING.md is empty" }
    val templateLiteral = buildString {
      append('"')
      template.forEach { char ->
        when (char) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          '$' -> append("\\$")
          else -> append(char)
        }
      }
      append('"')
    }
    val file = outputDir.get().file("software/sava/build/hardening/HardeningTemplateDigest.kt").asFile
    file.parentFile.mkdirs()
    file.writeText(buildString {
      appendLine("// Generated by the 'generateHardeningTemplateDigest' task; edit HARDENING.md instead.")
      appendLine("package software.sava.build.hardening")
      appendLine()
      appendLine("internal object HardeningTemplateDigest {")
      appendLine("  const val TEMPLATE = $templateLiteral")
      appendLine("}")
    })
  }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateHardeningTemplateDigest)

// The smoke tests run TestKit builds of fixture projects that resolve this plugin
// from the local Maven repo below (fed by the 'savaBuildTest' publication). Resolving
// a published jar instead of an includeBuild of this checkout keeps the parallel test
// forks from contending on this build's file locks, and stops the first TestKit daemon
// from recompiling the whole plugin from source.
val savaTestRepoPublication = "savaBuildTest"
val savaTestRepoVersion = "0.0.0-test"
val savaTestRepoDir = layout.buildDirectory.dir("sava-test-repo")
// Task names Gradle derives from the publication name; kept in one place so renaming the
// publication cannot silently detach the signing and publish-task filters below from it.
private val savaTestRepoPublicationSuffix = savaTestRepoPublication.replaceFirstChar(Char::uppercase) + "Publication"
val savaTestRepoSignTask = "sign$savaTestRepoPublicationSuffix"
val savaTestRepoPublishPrefix = "publish${savaTestRepoPublicationSuffix}To"
val savaTestRepoPublishTask = "${savaTestRepoPublishPrefix}SavaTestRepoRepository"

// tools/local-fuzz.sh: run by localFuzzSelfTest below, and read by LocalRepoNoticeFunctionalTest.
val localFuzzScriptPath = "tools/local-fuzz.sh"
val localFuzzScript = layout.projectDirectory.file(localFuzzScriptPath)

// The repository files a test task reads, by path from the root (a file, or a directory read
// whole), declared in one list per task: a tree over the root holding just those is an input of
// the task, hashed by content under each path, and the list is passed to the tests, whose
// accessor (savaBuildRootFile under src/test, guardFile under src/guardTest) refuses a read of a
// path that is not on it, so a new read declares itself here or fails instead of leaving the task
// up to date after an edit to the file it checks; a scan test in each source set holds every read
// to the accessor. Sources a test asserts over are declared too: the test needs their text as an
// input, and the classes compiled from them may not change when the text does. Gradle's default
// excludes (.gitignore, .DS_Store, .git and the like) apply to the tree and win over the includes:
// such a file under a declared directory is not fingerprinted here, and no test reads one.
fun Test.declareReads(property: String, paths: List<String>) {
  val rootDir = layout.projectDirectory.asFile
  inputs.files(fileTree(layout.projectDirectory) { include(paths.flatMap { listOf(it, "$it/**") }) })
    .withPropertyName(property)
    .withPathSensitivity(PathSensitivity.RELATIVE)
  jvmArgumentProviders.add(objects.newInstance<DeclaredReads>().apply {
    this.name = property
    this.paths = paths
    this.root = layout.projectDirectory
  })
  // A declared path that does not exist fails here by name, where the tree would leave it out and
  // the first test to open it would fail in its own way.
  doFirst {
    for (path in paths) {
      check(File(rootDir, path).exists()) { "$path, declared among the files the tests read ($property in build.gradle.kts), does not exist" }
    }
  }
}

// :test, through savaBuildRootFile.
//  HARDENING.md: AgentsTemplateSyncFunctionalTest (the agent instructions template section) and
//    HardeningDocumentationBoundaryTest, which reads README.md and HARDENING_CASEBOOK.md with it.
//  arcmutate-licence.txt: ArcMutateLicenceTest; the repository intentionally carries the public,
//    package-scoped OSS certificate, and the test ensures no private subscription URL sits beside it.
//  tools/local-fuzz.sh: LocalRepoNoticeFunctionalTest, the resolution notice's needle.
//  The hardening script plugin: HardeningTypedTaskArchitectureTest, its wiring as spelled.
//  src/test/kotlin: TestKitRunnerArgumentsTest and RootFileReadsTest, over the test sources.
val rootFilesReadByTests = listOf(
  "HARDENING.md",
  "README.md",
  "HARDENING_CASEBOOK.md",
  "arcmutate-licence.txt",
  localFuzzScriptPath,
  "src/main/kotlin/software.sava.build.feature.hardening.gradle.kts",
  "src/test/kotlin"
)

// guardTest, through guardFile: the actions whole, docs included, whose programs and scripts the
// tests compile and run (TrustedChainsTest, DependencyVerificationNoticeTest, SetupCheckedJdkPinTest,
// ReleaseFloor); the root action and the two workflows whose pins and versions they hold
// (SetupCheckedJdkPinTest, ReleasePleaseActionPinTest); the README, whose floor sentence the pin
// test holds with the actions' prose; and the guard test sources (GuardFileReadsTest).
val guardFilesReadByTests = listOf(
  ".github/actions",
  "action.yml",
  ".github/workflows/release-please.yml",
  ".github/workflows/action_self_test.yml",
  "README.md",
  "src/guardTest/kotlin"
)

tasks.test {
  useJUnitPlatform()
  // Each concurrent fixture build needs a TestKit daemon. Keep a conservative
  // default, and benchmark on the target runner before increasing it. Smaller
  // functional-test classes let these workers share the work more evenly.
  maxParallelForks = providers.gradleProperty("savaBuildTestForks").map { value ->
    value.toIntOrNull()?.takeIf { it > 0 }
      ?: throw GradleException("savaBuildTestForks must be a positive integer, got '$value'")
  }.getOrElse(2)
  dependsOn(savaTestRepoPublishTask)
  // The paths handed to the tests are absolute, so they are passed through an argument
  // provider that declares them as relocatable inputs instead of as 'systemProperty'
  // values. A system property is hashed verbatim, which would pin this task's build
  // cache key to this checkout's location and make ':test' unshareable across machines.
  jvmArgumentProviders.add(
    objects.newInstance<SavaBuildTestArguments>().apply {
      // The repository files the tests read through the root are declared by declareReads
      // below, which passes the root too; no fixture consumes the checkout as an included
      // build anymore.
      // Re-run when the published plugin changes. Maven metadata and the local
      // provenance sidecar carry a fresh publication timestamp on every publish, so
      // neither belongs in the test/cache identity; the JAR bytes already bind the
      // plugin behavior exercised by these tests.
      testRepoFiles.from(fileTree(savaTestRepoDir) {
        exclude("**/maven-metadata.*", "**/*-provenance.tsv")
      })
      testRepoDir = savaTestRepoDir
      testRepoVersion = savaTestRepoVersion
      // The Java 21 floor as the compile tasks hold it, for PluginJavaFloorTest: javac's release
      // and kotlinc's JVM target and free arguments, read from the tasks' options rather than from
      // this script's text, so a rewording here re-runs nothing and a rewiring of the floor
      // (another release, the flag added some other way or dropped) changes an input.
      // A floor wired some other way (a toolchain, no jvmTarget) leaves these unset; -1 and an
      // empty string pass through so the floor test fails by name, not the task before it runs.
      javaRelease = tasks.compileJava.flatMap { it.options.release }.orElse(-1)
      kotlinJvmTarget = tasks.compileKotlin.flatMap { it.compilerOptions.jvmTarget }.map { it.target }.orElse("")
      // Only the -Xjdk-release entries, which is all the test reads: a free argument carrying a
      // path would otherwise pin the cache key to this checkout, which the comment above forbids.
      kotlinJdkReleaseArgs = tasks.compileKotlin.flatMap { it.compilerOptions.freeCompilerArgs }
        .map { args -> args.filter { it.startsWith("-Xjdk-release=") } }
    }
  )
  declareReads("savaBuild.rootFiles", rootFilesReadByTests)
}

// The composite actions under .github/actions carry Java programs: a composite action runs
// without a build, which rules out Kotlin and this plugin's jar, so each action compiles its
// own source directory with javac on the JDK its workflow set up (Java 21, the oldest a sava
// workflow runs them on) and runs it. This build compiles each directory on its own the same
// way, with every lint as an error, and tests them under check from the Kotlin tests in
// src/guardTest.
val actionSourceSets = listOf(
  "releasePleaseGuard" to "release-please-guard",
  "forkPrGuard" to "fork-pr-guard",
  "checkJdkTrust" to "check-jdk-trust",
)
  .map { (name, action) ->
    val sourceSet = sourceSets.create(name) {
      java.setSrcDirs(listOf(layout.projectDirectory.dir(".github/actions/$action/src")))
      resources.setSrcDirs(emptyList<File>())
    }
    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
      options.release = 21
      options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    sourceSet
  }
val guardTest = sourceSets.create("guardTest") {
  actionSourceSets.forEach { actionSourceSet ->
    compileClasspath += actionSourceSet.output
    runtimeClasspath += actionSourceSet.output
  }
}
dependencies {
  "guardTestImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
  "guardTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
val guardTestTask = tasks.register<Test>("guardTest") {
  description = "Tests the Java programs the composite actions under .github/actions run."
  group = "verification"
  useJUnitPlatform()
  testClassesDirs = guardTest.output.classesDirs
  classpath = guardTest.runtimeClasspath
  declareReads("savaBuild.guardFiles", guardFilesReadByTests)
}
tasks.check { dependsOn(guardTestTask) }

// The shell scripts the composite actions under .github/actions and the tools run: a syntax error
// there would otherwise surface only on a runner. One 'bash -n' per file, since bash parses only
// its first operand and binds the rest to $1 onwards.
val shellSyntax = tasks.register<Exec>("shellSyntax") {
  group = "verification"
  description = "Checks the syntax of the shell scripts under .github/actions and tools."
  val scripts = files(
    layout.projectDirectory.dir(".github/actions").asFileTree.matching { include("**/*.sh") },
    layout.projectDirectory.dir("tools").asFileTree.matching { include("*.sh") }
  )
  inputs.files(scripts).withPropertyName("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
  executable = "sh"
  argumentProviders.add {
    listOf("-c", "for script; do bash -n -- \"\$script\" || exit; done", "shellSyntax") +
      scripts.files.sorted().map { it.absolutePath }
  }
}
tasks.check { dependsOn(shellSyntax) }

// Local fuzz evidence replaced scheduled fuzz workflows, so its self-test belongs on the
// ordinary plugin check.
val localFuzzSelfTest = tasks.register<Exec>("localFuzzSelfTest") {
  group = "verification"
  description = "Runs the local fuzz evidence runner's hermetic self-test."
  dependsOn(shellSyntax)
  inputs.file(localFuzzScript)
  // Direct execution also checks the executable bit and shebang, as CI did before.
  commandLine(localFuzzScript.asFile.absolutePath, "--self-test")
}
tasks.named("check") {
  dependsOn(localFuzzSelfTest)
}

// Passes the fixture paths to the test JVM as '-D' arguments while declaring them with
// path sensitivity that ignores where this checkout lives: the test repo is hashed by its
// Maven layout relative to the repo root (the repository files the tests read, and the root
// itself, come from declareReads, with the task).
abstract class SavaBuildTestArguments : CommandLineArgumentProvider {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val testRepoFiles: ConfigurableFileCollection

  @get:Input
  abstract val testRepoVersion: Property<String>

  @get:Internal
  abstract val testRepoDir: DirectoryProperty

  @get:Input
  abstract val javaRelease: Property<Int>

  @get:Input
  abstract val kotlinJvmTarget: Property<String>

  @get:Input
  abstract val kotlinJdkReleaseArgs: ListProperty<String>

  override fun asArguments(): Iterable<String> = listOf(
    "-DsavaBuild.testRepo=${testRepoDir.get().asFile.absolutePath}",
    "-DsavaBuild.testRepo.version=${testRepoVersion.get()}",
    "-DsavaBuild.javaRelease=${javaRelease.get()}",
    "-DsavaBuild.kotlinJvmTarget=${kotlinJvmTarget.get()}",
    // Tab-separated: a compiler argument holds no tab.
    "-DsavaBuild.kotlinJdkReleaseArgs=${kotlinJdkReleaseArgs.get().joinToString("\t")}"
  )
}

// The paths a test task declares it reads, under their property, and the root the tests resolve
// them against, the latter an internal so the task's cache key does not pin this checkout's location.
abstract class DeclaredReads : CommandLineArgumentProvider {

  @get:Input
  abstract val name: Property<String>

  @get:Input
  abstract val paths: ListProperty<String>

  @get:Internal
  abstract val root: DirectoryProperty

  override fun asArguments(): Iterable<String> = listOf(
    "-D${name.get()}=${paths.get().joinToString(",")}",
    "-DsavaBuild.root=${root.get().asFile.absolutePath}"
  )
}

repositories {
  gradlePluginPortal()
}

gradlePlugin {
  plugins {
    // Named after the plugin id so the marker publication/task names match the
    // 'publishSoftware.sava.build.*' pattern of the precompiled script plugins.
    register("software.sava.build.feature-jdk-provisioning") {
      id = name
      displayName = "Deprecated alias for software.sava.build.feature.jdk-provisioning"
      implementationClass = "SavaJdkProvisioningAliasPlugin"
    }
  }
}

java {
  withJavadocJar()
  withSourcesJar()
}

// Consumers load this plugin in their own Gradle daemon, so its floor must not follow the JDK
// this build runs on: Java 21 bytecode, compiled against the Java 21 API.
tasks.withType<JavaCompile>().configureEach { options.release = 21 }
kotlin.compilerOptions {
  jvmTarget = JvmTarget.JVM_21
  freeCompilerArgs.add("-Xjdk-release=21")
}

// Keep in sync with the signing setup in
// src/main/kotlin/software.sava.build.feature.publish.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself).
val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
signing {
  sign(publishing.publications)
  useInMemoryPgpKeys(signingKey, signingPassphrase)
}
// The test-repo publication is never signed: the release workflow's check step runs
// with '-Psign=true' but without the GPG environment, and 'check' publishes it. The name
// is derived from the publication so renaming it cannot silently re-enable signing --
// which would surface only as a confusing missing-GPG-key failure during a release.
tasks.withType<Sign>().configureEach {
  enabled = publishSigningEnabled && name != savaTestRepoSignTask
}

// Markers are published for EVERY plugin id. A marker used to exist only for ids
// consumers request in a settings 'plugins {}' block, on the theory that project-level
// conventions always ride the settings classpath — but a standalone consumer build (a
// sibling 'jmh/' directory applying 'software.sava.build.feature.jmh') resolves by
// id + version with no settings plugin in sight, and the missing marker forced every
// such build into a hand-written 'useModule' with its own version pin to bump each
// release. Markers are one tiny POM each; a partial allowlist was the foot-gun.
// The test publication only feeds the local test repo, and the test repo only receives
// that publication.
tasks.named { name ->
  (name.startsWith(savaTestRepoPublishPrefix) && name != savaTestRepoPublishTask) ||
    (name.endsWith("ToSavaTestRepoRepository") && !name.startsWith(savaTestRepoPublishPrefix))
}.configureEach {
  enabled = false
}

// Allow callers to drop further checksum files from the Maven Central deployment bundle
// via '-PmavenCentralExcludeChecksums=<extension>,...'; Central rejects one without .md5 and .sha1.
val mavenCentralExcludeChecksums = providers.gradleProperty("mavenCentralExcludeChecksums")
  .map { value -> value.split(",").map(String::trim).filter(String::isNotEmpty) }
  .getOrElse(emptyList())

// Central requires .md5 and .sha1 and validates nothing else. No resolver fetches a
// .sha256 or .sha512 sidecar in its default configuration (Gradle asks only for .sha1, and
// only to match a copy it already caches; Maven Resolver asks for SHA-1, then MD5), and .asc
// signatures need no checksums at all; every published file counts against Maven Central's
// monthly publishing limits. Restore the rest with '-PmavenCentralPublishAllChecksums=true'.
val defaultChecksumExcludes =
  if (providers.gradleProperty("mavenCentralPublishAllChecksums").getOrElse("false").toBoolean()) emptyList()
  else listOf("**/*.sha256", "**/*.sha512", "**/*.asc.md5", "**/*.asc.sha1")

val centralStagingDir = layout.buildDirectory.dir("central-portal-staging")

publishing {
  repositories {
    maven {
      name = "savaCentralStaging"
      url = uri(centralStagingDir.get().asFile)
    }
    maven {
      name = "savaTestRepo"
      url = uri(savaTestRepoDir.get().asFile)
    }
  }
  publications {
    // Feeds the local repo the functional-test fixtures resolve the plugin from;
    // never published anywhere else (see the task filter above).
    register<MavenPublication>(savaTestRepoPublication) {
      from(components["java"])
      version = savaTestRepoVersion
    }
  }
}

// The static local coordinate intentionally keeps reproducible plugin bytes: a newer
// source commit can legitimately produce the same JAR. Record the source identity in
// an out-of-band sidecar after Maven publication instead of embedding it in that JAR.
// This one local task always executes so an UP-TO-DATE byte-identical publication still
// advances the source provenance an adopter sees.
val savaTestRepoSourceJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val savaTestRepoMainArtifact = savaTestRepoDir.map { repo ->
  repo.file(
    "software/sava/sava-build/$savaTestRepoVersion/" +
      "sava-build-$savaTestRepoVersion.jar"
  )
}
val savaTestRepoProvenance = savaTestRepoDir.map { repo ->
  repo.file(
    "software/sava/sava-build/$savaTestRepoVersion/" +
      "sava-build-$savaTestRepoVersion-provenance.tsv"
  )
}
val savaBuildCheckout = layout.projectDirectory.asFile
tasks.named<PublishToMavenRepository>(savaTestRepoPublishTask) {
  outputs.file(savaTestRepoProvenance)
  doNotTrackState("Local source provenance is refreshed after every successful publication")
  // If Maven fails after replacing only part of the static coordinate, no stale
  // sidecar may remain to make that partial repository look attributable.
  doFirst(
    InvalidateLocalPublicationProvenance(
      provenancePath = savaTestRepoProvenance.get().asFile.absolutePath,
    )
  )
  doLast(
    WriteLocalPublicationProvenance(
      checkoutPath = savaBuildCheckout.absolutePath,
      sourceJarPath = savaTestRepoSourceJar.get().asFile.absolutePath,
      repositoryJarPath = savaTestRepoMainArtifact.get().asFile.absolutePath,
      provenancePath = savaTestRepoProvenance.get().asFile.absolutePath,
    )
  )
}

// --- In-house Central Portal deployment. Keep in sync with
// src/main/kotlin/software.sava.build.feature.publish.gradle.kts and
// software.sava.build.feature.publish-maven-central.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself). ---

val cleanSavaCentralStaging = tasks.register<Delete>("cleanSavaCentralStaging") {
  delete(centralStagingDir)
}
tasks.withType<PublishToMavenRepository>().configureEach {
  if (name.endsWith("ToSavaCentralStagingRepository")) {
    dependsOn(cleanSavaCentralStaging)
  }
}

val zipCentralPortalDeployment = tasks.register<Zip>("zipCentralPortalDeployment") {
  group = "publishing"
  description = "Zips the staged publications into a Central Portal deployment bundle"
  dependsOn("publishAllPublicationsToSavaCentralStagingRepository")
  from(centralStagingDir)
  exclude("**/maven-metadata.xml*")
  defaultChecksumExcludes.forEach { pattern ->
    exclude(pattern)
  }
  mavenCentralExcludeChecksums.forEach { extension ->
    exclude("**/*.$extension")
  }
  destinationDirectory = layout.buildDirectory.dir("central-portal")
  archiveFileName = "deployment.zip"
}

// Resolved eagerly: computing this with provider 'map' lambdas can capture the script
// instance in the task state, which the configuration cache cannot serialize.
val centralDeploymentVersion = providers.gradleProperty("version").getOrElse("")
val centralDeploymentName = if (centralDeploymentVersion.isEmpty()) "sava-build" else "sava-build $centralDeploymentVersion"

val publishCentralPortalDeployment = tasks.register<CentralPortalUpload>("publishCentralPortalDeployment") {
  group = "publishing"
  description = "Uploads the Central Portal deployment bundle (USER_MANAGED: review and release in the portal UI)"
  bundle = zipCentralPortalDeployment.flatMap { it.archiveFile }
  username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
  password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
  deploymentName = centralDeploymentName
}

// Releases a validated USER_MANAGED deployment without the portal UI:
//   ./gradlew releaseCentralPortalDeployment -PcentralPortalDeploymentId=<id>
tasks.register<CentralPortalRelease>("releaseCentralPortalDeployment") {
  group = "publishing"
  description = "Publishes a validated Central Portal deployment ('-PcentralPortalDeploymentId=...')"
  deploymentId = providers.gradleProperty("centralPortalDeploymentId")
  username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
  password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
}

// Deprecated alias, named after the task the nmcp plugin used to provide, so existing
// workflows keep working; invoke 'publishCentralPortalDeployment' instead.
tasks.register("publishAggregationToCentralPortal") {
  group = "publishing"
  description = "Deprecated alias for publishCentralPortalDeployment"
  dependsOn(publishCentralPortalDeployment)
}

// sava-build's own publications (the module and every plugin marker) reach GitHub Packages
// the way the convention plugins publish a consumer's: from the staging repository the
// Central bundle is also built from, with SHA-1 and SHA-256 checksums and no MD5. The test
// publication never reaches it; its staging task is disabled above.
tasks.register<GitHubPackagesUpload>("publishToGitHubPackages") {
  group = "publishing"
  description = "Uploads the staged publications to GitHub Packages with SHA-1 and SHA-256 checksums"
  dependsOn("publishAllPublicationsToSavaCentralStagingRepository")
  stagingDirectory = centralStagingDir
  baseUrl = providers.gradleProperty("savaGithubPackagesPublishUrl")
    .orElse("https://maven.pkg.github.com/sava-software/sava-build")
  username = providers.gradleProperty("savaGithubPackagesUsername")
  password = providers.gradleProperty("savaGithubPackagesPassword")
}

// Inline duplicate of software.sava.build.publish.GitHubPackagesUploadTask: this build
// compiles that class, so it cannot use it in its own build script.
@UntrackedTask(because = "Uploads to an external service")
abstract class GitHubPackagesUpload : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val stagingDirectory: DirectoryProperty

  @get:Input
  abstract val baseUrl: Property<String>

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  abstract val checksums: SetProperty<String>

  @get:Internal
  abstract val retryDelayMillis: Property<Long>

  init {
    checksums.convention(setOf("sha1", "sha256"))
    retryDelayMillis.convention(1_000L)
  }

  @TaskAction
  fun upload() {
    val user = username.orNull
      ?: error("GitHub Packages username is missing; set the publishing username Gradle property.")
    val pass = password.orNull
      ?: error("GitHub Packages password is missing; set the publishing password Gradle property.")
    val root = stagingDirectory.get().asFile.toPath()
    val kept = checksums.get()
    // One version directory at a time, its POM first, so a failure leaves at most one
    // version partly uploaded; a checksum sorts after its file. Built here rather than held
    // in a field: the configuration cache cannot serialize a comparator lambda as task state.
    val uploadOrder = compareBy<Path>(
      { it.parent?.toString() ?: "" },
      { !it.fileName.toString().endsWith(".pom") },
      { it.toString() },
    )
    val files = Files.walk(root).use { stream ->
      stream.filter { Files.isRegularFile(it) }.map { root.relativize(it) }.toList()
    }.filter { publishes(it.fileName.toString(), kept) }.sortedWith(uploadOrder)
    check(files.isNotEmpty()) { "Nothing to upload under $root; stage the publications first." }

    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
    val base = baseUrl.get().trimEnd('/')
    for (relative in files) {
      val path = relative.joinToString("/")
      val request = HttpRequest.newBuilder(URI("$base/$path"))
        .header("Authorization", "Basic $token")
        .header("Content-Type", "application/octet-stream")
        .timeout(Duration.ofMinutes(10))
        .PUT(HttpRequest.BodyPublishers.ofFile(root.resolve(relative)))
        .build()
      val (response, retried) = send(client, request, path)
      if (response.statusCode() == 409 && retried) {
        logger.warn("GitHub Packages already holds {}, stored by the attempt that failed in transit.", path)
        continue
      }
      check(response.statusCode() != 409) {
        "GitHub Packages already holds $path (HTTP 409): a published version is immutable, " +
          "so a release cannot be uploaded twice."
      }
      check(response.statusCode() in 200..299) {
        "GitHub Packages rejected $path with HTTP ${response.statusCode()}: ${response.body()}"
      }
      logger.info("Uploaded {}", path)
    }
    logger.lifecycle("Uploaded {} files to {}.", files.size, base)
  }

  private fun send(client: HttpClient, request: HttpRequest, path: String): Pair<HttpResponse<String>, Boolean> {
    var attempt = 1
    while (true) {
      val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (e: IOException) {
        if (attempt >= 3) throw e
        logger.warn("GitHub Packages upload of {} failed ({}); retrying...", path, e.message)
        null
      }
      if (response != null) {
        val status = response.statusCode()
        if (status < 500 && status != 408 && status != 429 || attempt >= 3) {
          return response to (attempt > 1)
        }
        logger.warn(
          "GitHub Packages upload of {} attempt {} returned HTTP {}; retrying...",
          path, attempt, response.statusCode()
        )
      }
      Thread.sleep(retryDelayMillis.get() * attempt)
      attempt++
    }
  }

  private fun publishes(fileName: String, checksums: Set<String>): Boolean {
    if (fileName.startsWith("maven-metadata")) {
      return false
    }
    val checksum = listOf("md5", "sha1", "sha256", "sha512").firstOrNull { fileName.endsWith(".$it") }
      ?: return true
    return checksum != "md5" && checksum in checksums &&
      !fileName.removeSuffix(".$checksum").endsWith(".asc")
  }
}

// Inline duplicate of software.sava.build.publish.CentralPortalUploadTask: this build
// compiles that class, so it cannot use it in its own build script.
// https://central.sonatype.org/publish/publish-portal-api/
abstract class CentralPortalUpload : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val bundle: RegularFileProperty

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  abstract val publishingType: Property<String>

  @get:Input
  abstract val deploymentName: Property<String>

  @get:Input
  abstract val baseUrl: Property<String>

  @get:Input
  abstract val awaitValidation: Property<Boolean>

  @get:Input
  abstract val validationTimeoutSeconds: Property<Long>

  init {
    publishingType.convention("USER_MANAGED")
    baseUrl.convention("https://central.sonatype.com/api/v1/publisher")
    awaitValidation.convention(true)
    validationTimeoutSeconds.convention(600L)
  }

  @TaskAction
  fun upload() {
    val user = username.orNull
      ?: error("Central Portal username is missing; set the MAVEN_CENTRAL_TOKEN environment variable.")
    val pass = password.orNull
      ?: error("Central Portal password is missing; set the MAVEN_CENTRAL_SECRET environment variable.")
    val type = publishingType.get()
    check(type == "USER_MANAGED" || type == "AUTOMATIC") {
      "Invalid publishingType '$type'. Must be USER_MANAGED or AUTOMATIC."
    }

    val bundleFile = bundle.get().asFile
    val boundary = "sava-${UUID.randomUUID()}"
    val head = ("--$boundary\r\n"
      + "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundleFile.name}\"\r\n"
      + "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
    val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    val name = URLEncoder.encode(deploymentName.get(), Charsets.UTF_8)
    val uri = URI("${baseUrl.get()}/upload?publishingType=$type&name=$name")
    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
    val request = HttpRequest.newBuilder(uri)
      .header("Authorization", "Bearer $token")
      .header("Content-Type", "multipart/form-data; boundary=$boundary")
      .timeout(Duration.ofMinutes(10))
      .POST(HttpRequest.BodyPublishers.ofByteArrays(listOf(head, bundleFile.readBytes(), tail)))
      .build()

    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
    // The upload is the flakiest step of a release; retry transient failures. The portal
    // is also known to intermittently return 401 'Invalid token', so 401 is retryable.
    var response = client.send(request, HttpResponse.BodyHandlers.ofString())
    var attempt = 1
    while (response.statusCode() !in 200..299 && response.statusCode() != 404 && attempt < 3) {
      logger.warn(
        "Central Portal upload attempt {} failed with HTTP {}; retrying...",
        attempt, response.statusCode()
      )
      Thread.sleep(1_000L * attempt)
      response = client.send(request, HttpResponse.BodyHandlers.ofString())
      attempt++
    }
    check(response.statusCode() in 200..299) {
      "Central Portal upload failed with HTTP ${response.statusCode()}: ${response.body()}"
    }
    val deploymentId = response.body().trim().removeSurrounding("\"")
    logger.lifecycle("Created Central Portal deployment {} ({}).", deploymentId, type)

    if (awaitValidation.get()) {
      awaitValidation(client, token, deploymentId, type)
    } else {
      logger.lifecycle("Validation not awaited; check $PORTAL_UI")
    }
  }

  private fun awaitValidation(client: HttpClient, token: String, deploymentId: String, type: String) {
    val statusUri = URI("${baseUrl.get()}/status?id=$deploymentId")
    val request = HttpRequest.newBuilder(statusUri)
      .header("Authorization", "Bearer $token")
      .timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()

    val deadline = System.nanoTime() + Duration.ofSeconds(validationTimeoutSeconds.get()).toNanos()
    var delayMillis = 2_000L
    var consecutiveAuthFailures = 0
    while (true) {
      val (state, detail) = fetchState(client, request)
      // The portal intermittently returns 401 'Invalid token' for valid credentials, so
      // only repeated auth failures are treated as real.
      if (state == "AUTH_FAILED") {
        check(++consecutiveAuthFailures < 3) {
          "Central Portal rejected the credentials 3 times in a row: $detail"
        }
      } else {
        consecutiveAuthFailures = 0
      }
      when (state) {
        "FAILED" ->
          error("Central Portal deployment $deploymentId failed validation: $detail\nDrop it at $PORTAL_UI")

        "VALIDATED" if type == "USER_MANAGED" -> {
          logger.lifecycle("Deployment {} passed validation; review and publish it at $PORTAL_UI", deploymentId)
          return
        }

        "PUBLISHING", "PUBLISHED" -> {
          logger.lifecycle("Deployment {} passed validation and is {}.", deploymentId, state.lowercase())
          return
        }
      }
      val remainingNanos = deadline - System.nanoTime()
      if (remainingNanos < delayMillis * 1_000_000L) {
        error(
          "Central Portal deployment $deploymentId was still '$state' after " +
            "${validationTimeoutSeconds.get()}s; check $PORTAL_UI " +
            "(raise 'validationTimeoutSeconds' on this task if validation is just slow)."
        )
      }
      logger.lifecycle(
        "Deployment {} is '{}', checking again in {}s ({}s left)...",
        deploymentId, state, delayMillis / 1000, remainingNanos / 1_000_000_000L
      )
      Thread.sleep(delayMillis)
      delayMillis = (delayMillis * 3 / 2).coerceAtMost(20_000L)
    }
  }

  private fun fetchState(client: HttpClient, request: HttpRequest): Pair<String, String> {
    val response = try {
      client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
      logger.warn("Central Portal status check failed ({}); retrying...", e.message)
      return "UNAVAILABLE" to ""
    }
    val code = response.statusCode()
    check(code != 404) {
      "Central Portal does not know the deployment (HTTP 404): ${response.body()}"
    }
    if (code == 401 || code == 403) {
      logger.warn("Central Portal status check returned HTTP {}; retrying...", code)
      return "AUTH_FAILED" to response.body()
    }
    if (code !in 200..299) {
      logger.warn("Central Portal status check returned HTTP {}; retrying...", code)
      return "UNAVAILABLE" to ""
    }
    val body = response.body()
    val fields = JsonSlurper().parseText(body) as Map<*, *>
    return (fields["deploymentState"] as? String ?: "UNKNOWN") to body
  }

  companion object {
    const val PORTAL_UI = "https://central.sonatype.com/publishing/deployments"
  }
}

// Inline duplicate of software.sava.build.publish.CentralPortalReleaseTask (see above).
abstract class CentralPortalRelease : DefaultTask() {

  @get:Input
  abstract val deploymentId: Property<String>

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  abstract val baseUrl: Property<String>

  init {
    baseUrl.convention("https://central.sonatype.com/api/v1/publisher")
  }

  @TaskAction
  fun release() {
    val id = deploymentId.orNull
      ?: error("Missing deployment id; pass '-PcentralPortalDeploymentId=<id>'.")
    val user = username.orNull
      ?: error("Central Portal username is missing; set the MAVEN_CENTRAL_TOKEN environment variable.")
    val pass = password.orNull
      ?: error("Central Portal password is missing; set the MAVEN_CENTRAL_SECRET environment variable.")

    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
    val request = HttpRequest.newBuilder(URI("${baseUrl.get()}/deployment/$id"))
      .header("Authorization", "Bearer $token")
      .timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) {
      "Central Portal deployment publish failed with HTTP ${response.statusCode()}: ${response.body()}"
    }
    logger.lifecycle(
      "Deployment {} is publishing; progress at https://central.sonatype.com/publishing/deployments",
      id
    )
  }
}

val vcs = "https://github.com/sava-software/sava-build"
publishing.publications.withType<MavenPublication>().configureEach {
  pom {
    name = project.name
    description = "Sava Gradle Conventions"
    url = vcs
    licenses {
      license {
        name = "Apache License"
        url = "$vcs/blob/main/LICENSE"
      }
    }
    developers {
      developer {
        name = "Jim"
        id = "jpe7s"
        email = "jpe7s.salt188@passfwd.com"
        organization = "Sava Software"
        organizationUrl = "https://github.com/sava-software"
      }
    }
    scm {
      connection = "scm:git:git@github.com:sava-software/sava-build.git"
      developerConnection = "scm:git:ssh@github.com:sava-software/sava-build.git"
      url = vcs
    }
  }
}
