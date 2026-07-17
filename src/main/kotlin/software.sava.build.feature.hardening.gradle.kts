import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningToolDefaults

plugins {
  id("java")
}

// PIT mutation testing and Jazzer coverage-guided fuzzing for hand-picked classes,
// configured via the 'hardening' extension. The main and test sources are recompiled into
// one plain classpath root per tool, without module-info: 'compileForPitest' at
// 'hardening.mutationBytecodeRelease' into 'build/mutation-classes', and 'compileForFuzz'
// at 'hardening.bytecodeRelease' into 'build/fuzz-classes'. The releases exist to be
// lowered when a tool's bundled ASM lags the toolchain's class-file version. PIT silently
// discards classpath roots whose path contains the string "pitest" — never rename its
// directory to anything containing it.

// Default tool versions come from gradle/libs.versions.toml via the generated
// HardeningToolDefaults, so Dependabot can keep them current.
val hardening = extensions.create<HardeningExtension>("hardening")
hardening.bytecodeRelease.convention(25)
hardening.mutationBytecodeRelease.convention(hardening.bytecodeRelease)
hardening.pitestVersion.convention(HardeningToolDefaults.PITEST)
hardening.pitestJunit5PluginVersion.convention(HardeningToolDefaults.PITEST_JUNIT5_PLUGIN)
hardening.jazzerVersion.convention(HardeningToolDefaults.JAZZER)

val pitest = configurations.create("pitest") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("org.pitest:pitest-command-line:${hardening.pitestVersion.get()}"))
    add(project.dependencies.create("org.pitest:pitest-junit5-plugin:${hardening.pitestJunit5PluginVersion.get()}"))
  }
}
val jazzer = configurations.create("jazzer") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("com.code-intelligence:jazzer:${hardening.jazzerVersion.get()}"))
  }
}

val mutationClassesDir = layout.buildDirectory.dir("mutation-classes")
val fuzzClassesDir = layout.buildDirectory.dir("fuzz-classes")

fun registerRecompile(taskName: String, tool: String, destination: Provider<Directory>, release: Provider<Int>) =
  tasks.register<JavaCompile>(taskName) {
    description = "Compiles the main and test sources to bytecode $tool can read."
    // custom JavaCompile tasks default to the daemon's JVM, not the project toolchain,
    // and the daemon may run on a JDK too old for 'release'
    javaCompiler.convention(javaToolchains.compilerFor(java.toolchain))
    source(sourceSets.main.get().java, sourceSets.test.get().java)
    exclude("**/module-info.java")
    modularity.inferModulePath = false
    // dependency jars only — including other projects' — while this project's own
    // outputs are recompiled from source instead
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = files(tasks.named<JavaCompile>("compileTestJava").map { task ->
      task.classpath.filter { !it.absolutePath.startsWith(ownBuildDir) }
    })
    destinationDirectory = destination
    options.release = release
  }

val compileForPitest = registerRecompile(
    "compileForPitest", "PIT", mutationClassesDir, hardening.mutationBytecodeRelease)
val compileForFuzz = registerRecompile(
    "compileForFuzz", "Jazzer", fuzzClassesDir, hardening.bytecodeRelease)

hardening.mutation.all {
  val suite = this
  suite.mutators.convention("STRONGER")
  suite.threads.convention(4)
  suite.excludedClasses.convention(emptyList())
  tasks.register<JavaExec>("pitest" + suite.name.replaceFirstChar(Char::uppercase)) {
    group = "verification"
    description = "PIT mutation testing of the '${suite.name}' classes against their tests."
    dependsOn(compileForPitest)
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    classpath = pitest
    // the module test plumbing patches resources into the module instead of exposing
    // them on testRuntimeClasspath, so the tools get the processed resource dirs
    // explicitly
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
    val mutationClassesPath = mutationClassesDir.get().asFile.absolutePath
    val resourceDirs = files(
      sourceSets.main.get().output.resourcesDir!!,
      sourceSets.test.get().output.resourcesDir!!
    )
    val classPathArg = files(
      mutationClassesDir,
      resourceDirs,
      configurations["testRuntimeClasspath"]
    ).elements.map { locations ->
      "--classPath=" + locations
          .map { it.asFile.absolutePath }
          // keep the recompiled classes, resource dirs, and dependency jars; drop this
          // project's class outputs, which the recompiled root replaces
          .filter { path ->
            path == mutationClassesPath
                || resourceDirs.any { it.absolutePath == path }
                || !path.startsWith(buildDirPath)
          }
          .joinToString(",")
    }
    val targetClassesArg = suite.targetClasses.map { "--targetClasses=" + it.joinToString(",") }
    // a map lambda returning null leaves the provider absent, dropping the argument
    val excludedClassesArg = suite.excludedClasses.map { excluded ->
      if (excluded.isEmpty()) null else "--excludedClasses=" + excluded.joinToString(",")
    }
    val targetTestsArg = suite.targetTests.map { "--targetTests=$it" }
    val mutatorsArg = suite.mutators.map { "--mutators=$it" }
    val threadsArg = suite.threads.map { "--threads=$it" }
    val sourceDirsArg = "--sourceDirs=" + layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    val reportDirArg = "--reportDir=" + layout.buildDirectory.dir("reports/pitest/${suite.name}").get().asFile.absolutePath
    argumentProviders.add {
      buildList {
        add(classPathArg.get())
        add(targetClassesArg.get())
        excludedClassesArg.orNull?.let(::add)
        add(targetTestsArg.get())
        add(sourceDirsArg)
        add(reportDirArg)
        add(mutatorsArg.get())
        add("--outputFormats=HTML,CSV")
        add("--timestampedReports=false")
        add(threadsArg.get())
      }
    }
  }
}

hardening.fuzz.all {
  val target = this
  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase)) {
    group = "verification"
    description = "Coverage-guided fuzzing of the '${target.name}' target with Jazzer; -PmaxFuzzTime=<seconds> (default 60)."
    // Jazzer gets its own recompile: it may not read the class files
    // 'mutationBytecodeRelease' targets.
    dependsOn(compileForFuzz)
    mainClass = "com.code_intelligence.jazzer.Jazzer"
    // Jazzer only instruments classes on the JVM classpath, not its '--cp' argument.
    // The recompiled root stands in for this project's class outputs; dependency jars
    // and the processed resource dirs (patched into the module rather than exposed on
    // testRuntimeClasspath) ride along so the target's collaborators resolve at run
    // time.
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }
    // Jazzer loads its agent dynamically and its driver uses Unsafe and native
    // libraries; pre-authorize them so runs are not buried in JDK warnings.
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
    val corpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    doFirst {
      corpusDir.mkdirs()
    }
    val targetClassArg = target.targetClass.map { "--target_class=$it" }
    // locals so the lambda below does not capture the script instance, which the
    // configuration cache cannot serialize
    val maxFuzzTimeArg = providers.gradleProperty("maxFuzzTime").orElse("60").map { "-max_total_time=$it" }
    val maxLenArg = target.maxLen.map { "-max_len=$it" }
    // committed seeds are passed as a trailing read-only corpus: libFuzzer replays every
    // input from every listed dir but only writes newly interesting ones to the first
    val seedCorpusDir = target.seedCorpus.map { it.asFile.absolutePath }
    argumentProviders.add {
      buildList {
        add(targetClassArg.get())
        add(maxFuzzTimeArg.get())
        maxLenArg.orNull?.let(::add)
        add(corpusDir.absolutePath)
        seedCorpusDir.orNull?.let(::add)
      }
    }
  }
}
