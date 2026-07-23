import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningTemplateDigest
import software.sava.build.hardening.HardeningToolDefaults
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
hardening.arcmutateBaseVersion.convention(HardeningToolDefaults.ARCMUTATE_BASE)
hardening.generateTestSupport.convention(false)
hardening.testSupportExcludes.convention(emptyList())
hardening.recompileExcludes.convention(emptyList())

// Arcmutate incremental analysis ("history"): reuses per-mutant results across runs
// when neither the mutated class nor its covering tests changed. Open-source PIT
// accepts the history flags but cannot honour them — its only registered history
// factory throws — so everything below keys off the licence certificate: without an
// 'arcmutate-licence.txt' at the project or root-project directory, no dependency is
// added and no flags are passed, and PIT runs exactly as open source.
// '-PnoMutationHistory' forces a from-scratch run with the licence present; the
// pre-release quality gate is expected to use it (see HARDENING.md).
val mutationHistory = (layout.projectDirectory.file("arcmutate-licence.txt").asFile.isFile ||
    rootProject.layout.projectDirectory.file("arcmutate-licence.txt").asFile.isFile) &&
    !providers.gradleProperty("noMutationHistory").isPresent

val pitest = configurations.create("pitest") {
  isCanBeConsumed = false
  defaultDependencies {
    add(project.dependencies.create("org.pitest:pitest-command-line:${hardening.pitestVersion.get()}"))
    add(project.dependencies.create("org.pitest:pitest-junit5-plugin:${hardening.pitestJunit5PluginVersion.get()}"))
    if (mutationHistory) {
      add(project.dependencies.create("com.arcmutate:base:${hardening.arcmutateBaseVersion.get()}"))
    }
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
    // lazy: the consuming build script sets the extension after the plugin applies
    val recompileExcludes = hardening.recompileExcludes
    exclude { element -> !element.isDirectory && element.file.name in recompileExcludes.get() }
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

// The 'is this change safe' gate: 'test' plus every registered mutation suite,
// each finalized by its baseline verification (see the ratchet below).
val qualityGate = tasks.register("qualityGate") {
  group = "verification"
  description = "Unit tests plus every PIT suite with mutation-baseline verification."
  dependsOn(tasks.named("test"))
}

// The agent-instructions template in HARDENING.md is copied (adapted) into each
// consuming repo's AGENTS.md, and prose copies drift silently — a template change is
// invisible from inside the repos it obligates. The plugin carries a digest of the
// current template block; this check fails until the repo's AGENTS.md contains a
// marker acknowledging that digest. The marker is an acknowledgment, not a checksum
// of the local block: update it only after re-diffing the block against the template
// — a changed bullet may mean new code, not just new prose. A repo without an
// AGENTS.md is warned, not failed: the adoption checklist owns creating the file;
// this task owns keeping it current.
val agentsTemplateInSync = tasks.register("agentsTemplateInSync") {
  group = "verification"
  description = "Fails when AGENTS.md has not acknowledged the current agent-instructions template in HARDENING.md."
  val agentsDoc = rootProject.layout.projectDirectory.file("AGENTS.md").asFile
  val expected = HardeningTemplateDigest.SHA256_12
  inputs.files(agentsDoc)
  inputs.property("templateDigest", expected)
  doLast {
    if (!agentsDoc.isFile) {
      logger.warn(
          "agentsTemplateInSync: no AGENTS.md at $agentsDoc — copy the agent-instructions " +
              "template from sava-build's HARDENING.md ('Adopting in a new repo') and add:\n" +
              "  <!-- hardening-template sha256:$expected -->"
      )
      return@doLast
    }
    val doc = agentsDoc.readText()
    if (doc.contains("hardening-template sha256:$expected")) {
      return@doLast
    }
    val stale = Regex("hardening-template sha256:([0-9a-f]+)").find(doc)
    throw GradleException(
        if (stale == null) {
          "AGENTS.md has no 'hardening-template' marker. Diff its hardening block against the " +
              "agent-instructions template in sava-build's HARDENING.md, sync or act on what " +
              "differs, then add:\n  <!-- hardening-template sha256:$expected -->"
        } else {
          "The shared agent-instructions template changed since this repo's AGENTS.md last " +
              "acknowledged it (marker ${stale.groupValues[1]}, current $expected). Re-diff the " +
              "AGENTS.md hardening block against the template in sava-build's HARDENING.md — a " +
              "changed bullet may need code, not just prose — then update the marker to:\n" +
              "  <!-- hardening-template sha256:$expected -->"
        }
    )
  }
}
tasks.named("check") { dependsOn(agentsTemplateInSync) }
qualityGate.configure { dependsOn(agentsTemplateInSync) }

// Serialize the PIT suites: each already runs its own worker pool, and
// concurrent suites contend for the same cores without finishing sooner.
var previousPitestTask: String? = null
var previousRound2Task: String? = null

// Convergence support (HARDENING.md "A wandering kill count"): 'pitestConverge' runs
// every suite twice in one invocation — snapshotting and clearing the reports between
// rounds, since Gradle would otherwise serve the second run from the first — and diffs
// per-mutant statuses. Two runs can match in total while disagreeing about which
// mutants died; only the per-mutant diff names what moved.
val convergeSuiteNames = mutableListOf<String>()
val convergeSnapshotDir = layout.buildDirectory.dir("pitest-converge/round1")
val pitestConvergeSnapshot = tasks.register("pitestConvergeSnapshot") {
  description = "Internal to pitestConverge: snapshots round-one PIT reports and clears them."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = convergeSnapshotDir
  val historyAssisted = mutationHistory
  val names = convergeSuiteNames
  doLast {
    if (historyAssisted) {
      throw GradleException(
          "pitestConverge proves nothing with arcmutate history active — two assisted runs " +
              "agree by construction. Re-run with -PnoMutationHistory."
      )
    }
    val snapshot = snapshotRoot.get().asFile
    snapshot.deleteRecursively()
    snapshot.mkdirs()
    names.forEach { suiteName ->
      val csv = reportsRoot.get().asFile.resolve("$suiteName/mutations.csv")
      if (!csv.isFile) {
        throw GradleException("pitestConverge: no round-one report for '$suiteName' at $csv")
      }
      csv.copyTo(snapshot.resolve("$suiteName.csv"))
      reportsRoot.get().asFile.resolve(suiteName).deleteRecursively()
    }
    logger.lifecycle("pitestConverge: snapshotted ${names.size} round-one report(s), reports cleared for round two")
  }
}
val pitestConverge = tasks.register("pitestConverge") {
  group = "verification"
  description = "Runs every PIT suite twice and diffs per-mutant statuses; a wandering kill count is a defect to chase, not re-ratchet past."
  dependsOn(pitestConvergeSnapshot)
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = convergeSnapshotDir
  val names = convergeSuiteNames
  doLast {
    val gated = setOf("SURVIVED", "NO_COVERAGE")
    // Rows can share a (class,method,line,mutator) key, so statuses are compared as
    // sorted multisets per key rather than single values.
    fun statuses(csv: File): Map<String, List<String>> = csv.readLines()
        .mapNotNull { line ->
          val parts = line.split(',')
          if (parts.size < 6) null
          else listOf(parts[1], parts[3], parts[4], parts[2].substringAfterLast('.')).joinToString(",") to parts[5]
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, statusList) -> statusList.sorted() }
    var boundaryFlips = 0
    var benignFlips = 0
    names.forEach { suiteName ->
      val round1 = statuses(snapshotRoot.get().asFile.resolve("$suiteName.csv"))
      val round2 = statuses(reportsRoot.get().asFile.resolve("$suiteName/mutations.csv"))
      (round1.keys + round2.keys).sorted().forEach { key ->
        val before = round1[key] ?: emptyList()
        val after = round2[key] ?: emptyList()
        if (before != after) {
          // only flips crossing the unkilled boundary can move the ratchet
          val crossed = before.any { it in gated } != after.any { it in gated }
          if (crossed) boundaryFlips++ else benignFlips++
          logger.lifecycle(
              "pitestConverge '$suiteName': $key — ${before.joinToString("/")} -> ${after.joinToString("/")}" +
                  (if (crossed) "  ** crosses the unkilled boundary **" else "")
          )
        }
      }
    }
    if (boundaryFlips == 0 && benignFlips == 0) {
      logger.lifecycle(
          "pitestConverge: ${names.size} suite(s) converged — zero per-mutant status flips " +
              "(run-to-run only; solo-vs-gate load flips need pitestModeSnapshot/pitestModeCompare)"
      )
    } else if (boundaryFlips == 0) {
      logger.lifecycle(
          "pitestConverge: $benignFlips flip(s), none crossing the unkilled boundary " +
              "(e.g. KILLED<->TIMED_OUT under load) — the ratchet cannot move"
      )
    } else {
      throw GradleException(
          "pitestConverge: $boundaryFlips flip(s) cross the unkilled boundary — a wandering " +
              "kill count is a defect to chase before refreshing any baseline. Known causes " +
              "and the diagnosis order are in HARDENING.md ('A wandering kill count'); union " +
              "a row with -PunionMutationBaseline only once observed to flip in both directions."
      )
    }
  }
}

// Mutator trial (HARDENING.md "The mutator set bounds what the ratchet can see"):
// 'pitestMutatorTrial -PtrialMutators=EXPERIMENTAL_X[,...]' runs every suite with only
// the candidate mutators and tabulates what fired, so "enable only what fires" is one
// invocation instead of a hand-kept table of per-suite runs and count diffs.
val trialMutatorsProperty = providers.gradleProperty("trialMutators")
var previousTrialTask: String? = null
val pitestMutatorTrial = tasks.register("pitestMutatorTrial") {
  group = "verification"
  description = "Runs every PIT suite with only the -PtrialMutators candidates and tabulates what fired; enable per suite only what fires, and record the numbers."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val names = convergeSuiteNames
  val trial = trialMutatorsProperty
  doLast {
    val candidates = trial.orNull ?: throw GradleException(
        "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only, not the suites' existing sets"
    )
    var fired = 0
    val width = names.maxOf { it.length } + 2
    val lines = names.sorted().map { name ->
      val csv = reportsRoot.get().asFile.resolve("$name-trial/mutations.csv")
      val rows = if (csv.isFile) csv.readLines().mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6) null else parts
      } else emptyList()
      if (rows.isEmpty()) {
        "  ${name.padEnd(width)}0 generated" +
            (if (csv.isFile) "" else " (no report — cannot fire here, or the run failed above)")
      } else {
        fired++
        val byStatus = rows.groupingBy { it[5] }.eachCount()
        val detected = (byStatus["KILLED"] ?: 0) + (byStatus["TIMED_OUT"] ?: 0)
        val unkilled = (byStatus["SURVIVED"] ?: 0) + (byStatus["NO_COVERAGE"] ?: 0)
        val perMutator = rows.groupingBy { it[2].substringAfterLast('.') }.eachCount()
            .entries.sortedBy { it.key }.joinToString(", ") { "${it.key} x${it.value}" }
        "  ${name.padEnd(width)}${rows.size} generated — $detected killed by existing tests, $unkilled unkilled ($perMutator)"
      }
    }
    logger.lifecycle(
        "pitestMutatorTrial '$candidates': fired in $fired of ${names.size} suite(s)\n" +
            lines.joinToString("\n") +
            "\nEnable only what fires: add the mutator to those suites' 'mutators' and record the " +
            "numbers in config/pitest/README.md (HARDENING.md 'The mutator set bounds what the ratchet can see')."
    )
  }
}

// Solo-vs-gate comparison (HARDENING.md "A wandering kill count"): pitestConverge's two
// rounds share one quiet invocation, so a zero-flip converge proves run-to-run
// determinism only — the load-dependent TIMED_OUT flips appear between a quiet run and
// a 'qualityGate' run, and that comparison was the last hand-run step. These two tasks
// script it: stash each mode's reports under a label, then diff per-mutant statuses
// across the labels.
//
//   ./gradlew <every pitest suite> pitestModeSnapshot -PpitestMode=solo -PnoMutationHistory
//   ./gradlew qualityGate pitestModeSnapshot -PpitestMode=gate -PnoMutationHistory
//   ./gradlew pitestModeCompare              # -PunionModeFlips writes the flip insurance
val pitestModesRoot = layout.buildDirectory.dir("pitest-modes")
val pitestModeSnapshot = tasks.register("pitestModeSnapshot") {
  group = "verification"
  description = "Stashes the current PIT reports as -PpitestMode=<label> for pitestModeCompare, then clears them."
  val reportsRoot = layout.buildDirectory.dir("reports/pitest")
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val mode = providers.gradleProperty("pitestMode")
  doLast {
    val label = mode.orNull ?: throw GradleException(
        "pitestModeSnapshot needs -PpitestMode=<label> naming how the suites just ran (e.g. solo, gate)"
    )
    if (!label.matches(Regex("[A-Za-z0-9._-]+"))) {
      throw GradleException("pitestModeSnapshot: '-PpitestMode=$label' — use letters, digits, '.', '_' or '-'")
    }
    val dest = snapshotRoot.get().asFile.resolve(label)
    dest.deleteRecursively()
    dest.mkdirs()
    names.forEach { suiteName ->
      val reportDir = reportsRoot.get().asFile.resolve(suiteName)
      val csv = reportDir.resolve("mutations.csv")
      if (!csv.isFile) {
        throw GradleException(
            "pitestModeSnapshot: no report for '$suiteName' at $csv — run every suite in the mode " +
                "being labeled first; a partial snapshot would diff a suite against its absence"
        )
      }
      if (reportDir.resolve(".history-assisted").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report is arcmutate-history-assisted — a reused " +
                "status is not an observation of this mode. Re-run the suites with -PnoMutationHistory."
        )
      }
      if (reportDir.resolve(".scoped").isFile) {
        throw GradleException(
            "pitestModeSnapshot: the '$suiteName' report was produced with -PmutateOnly — a partial " +
                "population is not an observation of this mode. Re-run the suites without -PmutateOnly."
        )
      }
      csv.copyTo(dest.resolve("$suiteName.csv"))
      reportDir.deleteRecursively()
    }
    logger.lifecycle(
        "pitestModeSnapshot: ${names.size} report(s) stashed as '$label'; reports cleared so the " +
            "next mode's run cannot be served from these"
    )
  }
}
val pitestModeCompare = tasks.register("pitestModeCompare") {
  group = "verification"
  description = "Diffs per-mutant statuses across pitestModeSnapshot labels; fails on uninsured unkilled-boundary flips (-PunionModeFlips writes the insurance) and sweeps for accepted rows unkilled in no mode."
  mustRunAfter(pitestModeSnapshot)
  val snapshotRoot = pitestModesRoot
  val names = convergeSuiteNames
  val unionFlips = providers.gradleProperty("unionModeFlips").isPresent
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  doLast {
    val gated = setOf("SURVIVED", "NO_COVERAGE")
    val modes = snapshotRoot.get().asFile.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
        ?: emptyList()
    if (modes.size < 2) {
      throw GradleException(
          "pitestModeCompare needs at least two labeled snapshots under ${snapshotRoot.get().asFile} " +
              "(found: ${if (modes.isEmpty()) "none" else modes.joinToString()}). Run the suites and " +
              "'pitestModeSnapshot -PpitestMode=<label>' once per mode — e.g. quiet suites as 'solo', " +
              "then under qualityGate as 'gate'."
      )
    }
    fun statuses(csv: File): Map<String, List<String>> = csv.readLines()
        .mapNotNull { line ->
          val parts = line.split(',')
          if (parts.size < 6) null
          else listOf(parts[1], parts[3], parts[4], parts[2].substringAfterLast('.')).joinToString(",") to parts[5]
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, statusList) -> statusList.sorted() }
    var benignFlips = 0
    var insuredFlips = 0
    val uninsured = mutableListOf<String>()
    val unionedNow = mutableListOf<String>()
    val deadRows = mutableListOf<String>()
    names.forEach { suiteName ->
      val perMode = modes.associateWith { label ->
        val csv = snapshotRoot.get().asFile.resolve("$label/$suiteName.csv")
        if (!csv.isFile) {
          throw GradleException(
              "pitestModeCompare: snapshot '$label' has no '$suiteName' report — the suite set " +
                  "changed since it was taken; re-run that mode and re-snapshot"
          )
        }
        statuses(csv)
      }
      // Baseline rows + notes: insurance checks, union writes, and the dead-row sweep
      // all preserve the trailing '# note' convention.
      val baselineFile = baselineDir.file("$suiteName-accepted.csv").asFile
      val annotations = mutableMapOf<String, String>()
      val accepted = if (baselineFile.isFile) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
              val hash = line.indexOf('#')
              if (hash < 0) line.trim()
              else {
                val row = line.substring(0, hash).trim()
                annotations[row] = line.substring(hash).trim()
                row
              }
            }
            .toMutableSet()
      } else {
        mutableSetOf()
      }
      var unionedHere = false
      val keys = perMode.values.flatMap { it.keys }.toSortedSet()
      keys.forEach { key ->
        val byMode = perMode.mapValues { (_, m) -> m[key] ?: emptyList() }
        if (byMode.values.distinct().size > 1) {
          val crossed = byMode.values.map { statusList -> statusList.any { it in gated } }.distinct().size > 1
          val detail = modes.joinToString(", ") { label ->
            "$label=${byMode.getValue(label).ifEmpty { listOf("absent") }.joinToString("/")}"
          }
          if (!crossed) {
            benignFlips++
            logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (benign — cannot move the ratchet)")
          } else {
            // one canonical baseline row per gated status this key was observed with
            val gatedRows = byMode.values.flatten().filter { it in gated }.distinct().sorted()
                .map { status -> "$key,$status" }
            when {
              gatedRows.all { it in accepted } -> {
                insuredFlips++
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (already insured in the baseline)")
              }
              unionFlips -> {
                gatedRows.filter { it !in accepted }.forEach { row ->
                  accepted.add(row)
                  annotations[row] = "# flip insurance ($detail)"
                  unionedNow.add("$suiteName: $row")
                }
                unionedHere = true
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (flip insurance written)")
              }
              else -> uninsured.add("$suiteName: $key — $detail")
            }
          }
        }
      }
      if (unionedHere) {
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(accepted.toSortedSet().joinToString("\n", postfix = "\n") { row ->
          annotations[row]?.let { "$row $it" } ?: row
        })
      }
      // HARDENING.md's sweep: accepted rows unkilled in *no* snapshotted mode are
      // widening the gate for nothing. Report only — removal is a judgment call, and
      // insurance that outlived its cause has a casebook entry of its own.
      val unkilledAnywhere = perMode.values.flatMap { modeStatuses ->
        modeStatuses.flatMap { (key, statusList) -> statusList.filter { it in gated }.map { "$key,$it" } }
      }.toSet()
      (accepted - unkilledAnywhere).sorted().forEach { row ->
        deadRows.add("$suiteName: $row${annotations[row]?.let { " $it" } ?: ""}")
      }
    }
    if (deadRows.isNotEmpty()) {
      logger.lifecycle(
          "pitestModeCompare: ${deadRows.size} accepted row(s) unkilled in no snapshotted mode — " +
              "widening the gate for nothing; re-measure before removing:\n" +
              deadRows.joinToString("\n") { "  $it" }
      )
    }
    val summary = "pitestModeCompare (${modes.joinToString(" vs ")}): " +
        "${uninsured.size} uninsured boundary flip(s), ${unionedNow.size} unioned now, " +
        "$insuredFlips already insured, $benignFlips benign (e.g. KILLED<->TIMED_OUT)"
    if (uninsured.isNotEmpty()) {
      throw GradleException(
          summary + ":\n" + uninsured.joinToString("\n") { "  $it" } +
              "\nA row that differs between modes belongs in the baseline (HARDENING.md " +
              "'TIMED_OUT is detected...'): re-run with -PunionModeFlips to write the union with " +
              "a '# flip insurance' note, or union by hand."
      )
    }
    logger.lifecycle(summary)
  }
}

hardening.mutation.all {
  val suite = this
  suite.mutators.convention("STRONGER")
  suite.threads.convention(4)
  // PIT's own defaults; see MutationSuite.timeoutFactor for tuning guidance
  suite.timeoutFactor.convention(1.25)
  suite.timeoutConst.convention(4000L)
  suite.excludedClasses.convention(emptyList())

  // Mutation ratchet: after each 'pitest<Name>' run, diff the unkilled mutants
  // (SURVIVED and NO_COVERAGE) against the checked-in baseline at
  // 'config/pitest/<name>-accepted.csv' and fail on anything new. A fresh
  // mutant must be killed with a test or knowingly accepted by re-running with
  // '-PupdateMutationBaseline' and documenting the reason (see HARDENING.md).
  val pitestTaskName = "pitest" + suite.name.replaceFirstChar(Char::uppercase)
  val suiteName = suite.name
  val verify = tasks.register("${pitestTaskName}Verify") {
    group = "verification"
    description = "Fails when the '$suiteName' PIT run left unkilled mutants missing from config/pitest/$suiteName-accepted.csv."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val xmlProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.xml")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val update = providers.gradleProperty("updateMutationBaseline").isPresent
    val union = providers.gradleProperty("unionMutationBaseline").isPresent
    val prune = providers.gradleProperty("pruneMutationBaseline").isPresent
    val listUnkilled = providers.gradleProperty("listUnkilled").isPresent
    val noDriftTolerance = providers.gradleProperty("noDriftTolerance").isPresent
    val statusStashFile = layout.projectDirectory.file(".pitest-history/$suiteName.statuses").asFile
    // captured locally so the doLast lambda does not hold the script instance
    val historyAssisted = mutationHistory
    // Resolved at configuration time so the scaffolding check below can ask whether a
    // mutated class is one of this project's own test sources.
    val testSourceDirs = sourceSets.test.get().java.srcDirs
    doLast {
      val csv = csvProvider.get().asFile
      if (!csv.exists()) {
        // As a finalizer this also fires when the pitest task itself just failed,
        // in which case the missing report is a symptom — don't let this message
        // bury the real error printed above it. A PIT MINION_DIED / coverage
        // socket-timeout failure is a known transient: re-run the suite.
        throw GradleException(
            "no PIT report at $csv — either $pitestTaskName has not run, or it just " +
                "failed before writing one (its error is above this; MINION_DIED " +
                "coverage failures are transient — re-run the suite). If the output " +
                "above lacks the cause, the daemon log keeps the minion's stack trace: " +
                "~/.gradle/daemon/<version>/daemon-<pid>.out.log"
        )
      }
      val gated = setOf("SURVIVED", "NO_COVERAGE")
      // Status is field 5 (0-based); the trailing killing-test field can itself contain
      // commas, so counting back from the end is not safe.
      val rows = csv.readLines().mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6) null else parts
      }

      val byStatus = rows.groupingBy { it[5] }.eachCount()
      val total = rows.size
      // TIMED_OUT counts as detected, the same as PIT's own summary — a mutant that
      // hangs the suite was caught. Reported separately because that detection is
      // load-dependent: the same row can read SURVIVED when the suite runs alone.
      val timedOut = byStatus["TIMED_OUT"] ?: 0
      val detected = (byStatus["KILLED"] ?: 0) + timedOut
      // Rounded down deliberately: a coverage figure should never read better than it
      // is, so 441/498 is 88% here and not 89%. PIT's own summary line rounds, so this
      // can sit one point below it — the counts either side of the slash are the same.
      val percent = if (total == 0) 0 else detected * 100 / total
      val split = buildList {
        gated.forEach { s -> byStatus[s]?.let { add("$it ${s.lowercase()}") } }
        if (timedOut > 0) add("$timedOut timed out (load-dependent)")
        // Anything else (RUN_ERROR, MEMORY_ERROR, ...) is neither detected nor
        // gated here — usually a load-dependent flake, but it lowers the detected
        // count, so the summary must account for it or the number reads as a
        // regression with no visible cause.
        (byStatus.keys - gated - setOf("KILLED", "TIMED_OUT")).sorted().forEach { s ->
          add("${byStatus.getValue(s)} ${s.lowercase()} (not counted as detected)")
        }
      }
      logger.lifecycle(
          "pitest '$suiteName': $detected/$total detected ($percent%)" +
              (if (split.isEmpty()) "" else " — ${split.joinToString(", ")}") +
              // With incremental analysis some of these statuses were reused, not
              // re-earned this run — the marker keeps the two kinds of number distinct.
              (if (historyAssisted) " [history]" else "")
      )

      // A suite whose exclusions miss a test-source class mutates its own scaffolding:
      // the population inflates and the survivors are triaged as if they were production
      // code. Shared fakes are named for their role (RecordingFoo, StubFoo, FooDriftCheck)
      // so a '*Test*' exclusion does not match them. Warned rather than failed: an
      // existing repo upgrading the plugin has these accepted in its baseline already.
      val scaffolding = rows.asSequence()
          .map { it[1] }
          .distinct()
          .filter { fqcn ->
            val relative = fqcn.substringBefore('$').replace('.', '/') + ".java"
            testSourceDirs.any { dir -> dir.resolve(relative).isFile }
          }
          .sorted()
          .toList()
      if (scaffolding.isNotEmpty()) {
        logger.warn(
            "pitest '$suiteName': mutating ${scaffolding.size} test-source class(es) — " +
                "add to excludedClasses:\n" + scaffolding.joinToString("\n") { "  $it" }
        )
      }

      // PIT's CSV omits the mutation description — which sub-condition of a line was hit,
      // which direction a conditional was forced — and triaging an unkilled row keeps
      // needing exactly that. The XML report carries it; keyed like the baseline rows.
      val descriptions: Map<String, String> by lazy {
        val xml = xmlProvider.get().asFile
        if (!xml.isFile) return@lazy emptyMap()
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(xml)
        val mutations = doc.getElementsByTagName("mutation")
        val collected = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until mutations.length) {
          val mutation = mutations.item(i) as org.w3c.dom.Element
          fun text(tag: String) = mutation.getElementsByTagName(tag).item(0)?.textContent ?: ""
          val key = listOf(
              text("mutatedClass"), text("mutatedMethod"), text("lineNumber"),
              text("mutator").substringAfterLast('.')
          ).joinToString(",")
          val description = text("description")
          // keyed both with and without status, so a row still annotates when its
          // status differs from the XML the descriptions came from
          collected.getOrPut("$key,${mutation.getAttribute("status")}") { mutableListOf() }.add(description)
          collected.getOrPut(key) { mutableListOf() }.add(description)
        }
        collected.mapValues { (_, all) -> all.distinct().joinToString(" | ") }
      }
      fun describe(row: String) =
          (descriptions[row] ?: descriptions[row.substringBeforeLast(',')])?.let { " — $it" } ?: ""

      // Kept as a LIST, not a set: a compound condition yields several mutants with
      // identical (class, method, line, mutator) coordinates — one per operand or
      // branch direction. Collapsing them to a set once let a killed sibling regress
      // to SURVIVED invisibly, absorbed by its already-accepted twin's row. All
      // comparisons below are multiset comparisons for the same reason.
      val current = rows.mapNotNull { parts ->
        if (parts[5] !in gated) {
          null
        } else {
          // class,method,line,mutator,status — line numbers churn on refactors;
          // refresh the baseline when they do
          "${parts[1]},${parts[3]},${parts[4]},${parts[2].substringAfterLast('.')},${parts[5]}"
        }
      }.sorted()
      fun multisetDiff(a: List<String>, b: List<String>): List<String> {
        val remaining = b.groupingBy { it }.eachCount().toMutableMap()
        return a.filter { row ->
          val n = remaining[row] ?: 0
          if (n > 0) {
            remaining[row] = n - 1
            false
          } else {
            true
          }
        }
      }

      // Same-line siblings of the same mutator FAMILY that ARE detected
      // disambiguate a survivor's direction: the survivor is the opposite branch or
      // operand of whatever the killing test pinned (see HARDENING.md on triaging
      // RemoveConditional pairs). Family = the name before the _EQUAL_IF/_ORDER_ELSE
      // style suffix, so the IF/ELSE cross-pair is matched too.
      fun mutatorFamily(mutator: String) = mutator.substringBefore('_')
      val detectedSiblings: Map<String, List<String>> = rows
          .filter { it[5] == "KILLED" || it[5] == "TIMED_OUT" }
          .groupBy(
              { "${it[1]},${it[3]},${it[4]},${mutatorFamily(it[2].substringAfterLast('.'))}" },
              {
                val killer = it.drop(6).joinToString(",")
                val test = Regex("method:([^(\\]]+)").find(killer)?.groupValues?.get(1)
                val mutator = it[2].substringAfterLast('.')
                if (it[5] == "KILLED" && test != null) "$mutator KILLED by $test" else "$mutator ${it[5]}"
              }
          )
      fun siblingHint(row: String): String {
        if (!row.endsWith(",SURVIVED")) return ""
        val parts = row.split(',')
        val siblings = detectedSiblings["${parts[0]},${parts[1]},${parts[2]},${mutatorFamily(parts[3])}"]
            ?: return ""
        return " [detected sibling at this line: ${siblings.distinct().joinToString("; ")}]"
      }
      if (listUnkilled && current.isNotEmpty()) {
        logger.lifecycle(
            "pitest '$suiteName' unkilled:\n" +
                current.joinToString("\n") { row -> "  $row${describe(row)}" }
        )
      }

      // A scoped run mutated a hand-picked subset: its report is an iteration aid,
      // not evidence about the suite. List what is still unkilled inside the scope
      // and stop — no ratchet, no status stash, and certainly no baseline writes.
      val scopedMarkerFile = csv.parentFile.resolve(".scoped")
      if (scopedMarkerFile.isFile) {
        val scope = scopedMarkerFile.readText().trim()
        if (update || union) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot refresh the baseline. Re-run $pitestTaskName without -PmutateOnly first."
          )
        }
        logger.lifecycle(
            "pitest '$suiteName': SCOPED run (-PmutateOnly=$scope) — ratchet skipped; " +
                (if (current.isEmpty()) "nothing unkilled in scope"
                else "${current.size} unkilled in scope:\n" +
                    current.joinToString("\n") { row -> "  $row${describe(row)}${siblingHint(row)}" })
        )
        return@doLast
      }
      // A baseline row may carry a trailing '# note' ('# untriaged' is the conventional
      // label for seeded debt). Notes are stripped for comparison, preserved across both
      // refresh flags, and the untriaged count is printed — so triage state lives on the
      // row it describes and stays a number the build reports, not prose that drifts.
      val annotations = mutableMapOf<String, String>()
      // a list, preserving duplicate rows: identical coordinates hold one row per
      // sibling mutant (see the multiset note above)
      val accepted: List<String> = if (baselineFile.exists()) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
              val hash = line.indexOf('#')
              if (hash < 0) line.trim()
              else {
                val row = line.substring(0, hash).trim()
                annotations[row] = line.substring(hash).trim()
                row
              }
            }
      } else {
        emptyList()
      }
      fun baselineLine(row: String) = annotations[row]?.let { "$row $it" } ?: row
      val untriaged = accepted.count { annotations[it]?.contains("untriaged", ignoreCase = true) == true }
      if (untriaged > 0) {
        logger.lifecycle("pitest baseline '$suiteName': ${accepted.size} rows, $untriaged marked '# untriaged'")
      }

      // Timed-out drift vs the previous run. TIMED_OUT counts as detected, but the
      // benign flavour (KILLED<->TIMED_OUT under load) and the dangerous one
      // (SURVIVED->TIMED_OUT: a mutant nobody killed now reads as detected purely
      // because its tests ran slowly) look identical in a single report. Comparing
      // against the last run's statuses names each newcomer's origin.
      val statusStash = statusStashFile
      run {
        fun coordinate(parts: List<String>) =
            "${parts[1]},${parts[3]},${parts[4]},${parts[2].substringAfterLast('.')}"
        val previous = if (statusStash.isFile) {
          statusStash.readLines().mapNotNull { line ->
            val sep = line.lastIndexOf(',')
            if (sep < 0) null else line.substring(0, sep) to line.substring(sep + 1)
          }.groupBy({ it.second }, { it.first })
        } else {
          emptyMap()
        }
        val nowTimedOut = rows.filter { it[5] == "TIMED_OUT" }.map(::coordinate).toSet()
        if (previous.isNotEmpty()) {
          val prevTimedOut = previous["TIMED_OUT"].orEmpty().toSet()
          val prevSurvived = previous["SURVIVED"].orEmpty().toSet()
          val fromSurvived = nowTimedOut.intersect(prevSurvived)
          val newlyTimedOut = nowTimedOut - prevTimedOut - prevSurvived
          val resolved = prevTimedOut - nowTimedOut
          if (fromSurvived.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${fromSurvived.size} previously SURVIVED mutant(s) now TIMED_OUT — " +
                    "likely load-slowed tests reading as detection, not new kills; do not refresh them out:\n" +
                    fromSurvived.sorted().joinToString("\n") { "  $it" }
            )
          }
          if (newlyTimedOut.isNotEmpty() || resolved.isNotEmpty()) {
            logger.lifecycle(
                "pitest '$suiteName': timed-out drift vs previous run — " +
                    "${newlyTimedOut.size} newly timed out (previously detected), ${resolved.size} no longer; load-dependent"
            )
          }
        }
        statusStash.parentFile.mkdirs()
        statusStash.writeText(
            rows.filter { it[5] == "TIMED_OUT" || it[5] == "SURVIVED" }
                .joinToString("\n", postfix = "\n") { "${coordinate(it)},${it[5]}" }
        )
      }

      if (listOf(update, union, prune).count { it } > 1) {
        throw GradleException(
            "pass at most one of -PupdateMutationBaseline, -PunionMutationBaseline, " +
                "-PpruneMutationBaseline — they answer different questions (see HARDENING.md)."
        )
      }
      if (prune) {
        // Shrink-only refresh: drop baseline rows matching nothing this run, add or
        // rewrite nothing. This is the one direction a refresh is always safe in —
        // shrinking the baseline is an improvement, and no coin-flip from this run can
        // be baked in. Two classes of unmatched row are kept anyway: rows whose
        // coordinate TIMED_OUT this run (load-dependent detection, not a kill —
        // pruning it starts the refresh ping-pong the TIMED_OUT doctrine warns about),
        // and rows whose coordinate still holds an unkilled mutant at a different
        // status (a coverage flip pending triage — pruning the stale side would erase
        // the pairing the newly-covered classifier explains it with).
        fun coordinate(row: String) = row.substringBeforeLast(',')
        val timedOutCoordinates = rows.filter { it[5] == "TIMED_OUT" }
            .map { "${it[1]},${it[3]},${it[4]},${it[2].substringAfterLast('.')}" }
            .toSet()
        val unkilledCoordinates = current.map(::coordinate).toSet()
        val budget = current.groupingBy { it }.eachCount().toMutableMap()
        val kept = mutableListOf<String>()
        val keptUnmatched = mutableListOf<Pair<String, String>>()
        val droppedRows = mutableListOf<String>()
        for (row in accepted) {
          val remaining = budget[row] ?: 0
          if (remaining > 0) {
            budget[row] = remaining - 1
            kept.add(row)
          } else if (coordinate(row) in timedOutCoordinates) {
            kept.add(row)
            keptUnmatched.add(row to "TIMED_OUT this run (load-dependent)")
          } else if (coordinate(row) in unkilledCoordinates) {
            kept.add(row)
            keptUnmatched.add(row to "coordinate unkilled at another status (flip pending triage)")
          } else {
            droppedRows.add(row)
          }
        }
        val keptDetail = if (keptUnmatched.isEmpty()) "" else
          "\n  kept ${keptUnmatched.size} unmatched row(s):\n" +
              keptUnmatched.joinToString("\n") { (row, why) -> "  ${baselineLine(row)} — $why" }
        if (droppedRows.isEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped nothing — every row matches this run$keptDetail")
        } else {
          baselineFile.writeText(kept.joinToString("\n", postfix = "\n") { baselineLine(it) })
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped ${droppedRows.size} row(s) since killed or moved " +
                  "(baseline now ${kept.size}):\n" +
                  droppedRows.joinToString("\n") { row -> "  ${baselineLine(row)}${describe(row)}" } +
                  keptDetail
          )
        }
        return@doLast
      }
      if (update) {
        val dropped = multisetDiff(accepted, current)
        // A status flip at one coordinate — NO_COVERAGE -> SURVIVED once a test reaches
        // the line, SURVIVED -> NO_COVERAGE when its covering test goes away — drops one
        // row and writes another, and the dropped row's '# note' used to vanish with it.
        // Carry the note onto the rewritten row, marked with the flip it crossed: the
        // acceptance argument travels, but flagged for re-reading — a reason written for
        // an unreached mutant is not automatically a reason once a test can observe it.
        val flippedNotes = mutableMapOf<String, MutableList<Pair<String, String>>>()
        for (row in dropped) {
          val note = annotations[row] ?: continue
          val coordinate = row.substringBeforeLast(',')
          flippedNotes.getOrPut(coordinate) { mutableListOf() }
              .add(row.substringAfterLast(',') to note)
        }
        var carried = 0
        val written = current.map { row ->
          annotations[row]?.let { return@map "$row $it" }
          val candidates = flippedNotes[row.substringBeforeLast(',')]
          if (candidates.isNullOrEmpty()) row
          else {
            val (oldStatus, note) = candidates.removeFirst()
            carried++
            "$row $note (carried across $oldStatus -> ${row.substringAfterLast(',')})"
          }
        }
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(written.joinToString("\n", postfix = "\n"))
        logger.lifecycle(
            "pitest baseline '$suiteName': wrote ${current.size} accepted entries" +
                (if (carried == 0) "" else " ($carried note(s) carried across a status flip — re-check them)")
        )
        if (dropped.isNotEmpty()) {
          // The silent half of the refresh footgun: a full update rewrites from this one
          // run, so a flip-insurance union (detected today, survived under other load)
          // vanishes without a trace unless it is named here.
          logger.lifecycle(
              "pitest baseline '$suiteName': dropped ${dropped.size} row(s) not unkilled this run:\n" +
                  dropped.joinToString("\n") { row -> "  ${baselineLine(row)}${describe(row)}" } +
                  "\n  a dropped flip-insurance union (see config/pitest/README.md) must be " +
                  "re-added with -PunionMutationBaseline once observed to flip again"
          )
        }
        return@doLast
      }
      if (union) {
        // Append-only refresh for flip families (HARDENING.md: union only rows observed
        // to flip). Adds this run's unkilled rows in canonical form without dropping
        // baseline rows that happened to be detected this run — a full
        // '-PupdateMutationBaseline' there would bake in this run's coin-flips and start
        // refresh ping-pong.
        val added = multisetDiff(current, accepted)
        if (added.isEmpty()) {
          logger.lifecycle("pitest baseline '$suiteName': union added nothing new")
        } else {
          // multiset union: per coordinate, the larger of the two occurrence counts
          val acceptedCounts = accepted.groupingBy { it }.eachCount()
          val currentCounts = current.groupingBy { it }.eachCount()
          val merged = (acceptedCounts.keys + currentCounts.keys).sorted().flatMap { row ->
            List(maxOf(acceptedCounts[row] ?: 0, currentCounts[row] ?: 0)) { row }
          }
          baselineFile.parentFile.mkdirs()
          baselineFile.writeText(merged.joinToString("\n", postfix = "\n") { baselineLine(it) })
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${added.size} entries (baseline now ${merged.size}):\n" +
                  added.joinToString("\n") { row -> "  $row${describe(row)}" }
          )
        }
        return@doLast
      }
      val fresh = multisetDiff(current, accepted)
      val stale = multisetDiff(accepted, current)
      // Two situations produce paired stale + "new" rows and look alike in a raw diff,
      // but call for opposite responses, so they are classified rather than lumped:
      //
      //   shifted        — same status, different line: editing a mutated file moved it.
      //                    Confirm the pairings and refresh.
      //   newly covered  — same line, different status (typically NO_COVERAGE ->
      //                    SURVIVED): a test newly reached the mutant. That is a triage
      //                    item — kill it or accept it with a reason — never a refresh.
      //
      // A stale row is consumed once it pairs, so several new rows cannot all claim the
      // same counterpart and report a churn that did not happen.
      fun rowKey(row: String) = row.split(',').let { Triple(it[0], it[1], it[3]) }
      fun rowLine(row: String) = row.split(',')[2]
      fun rowStatus(row: String) = row.substringAfterLast(',')

      val unpairedStale = stale.toMutableList()
      // pair counts are tracked as lists, not maps: duplicate sibling rows may each
      // pair with their own stale counterpart, and a map would collapse them
      val shiftPairs = mutableListOf<Pair<String, String>>()
      val newlyCoveredPairs = mutableListOf<Pair<String, String>>()
      // A "new" row identical to an accepted row is not new code and not churn: the
      // coordinate holds more sibling mutants than the baseline has rows, which is
      // what upgrading a set-based (pre-multiset) baseline materializes — pre-existing
      // debt made visible, not a regression. Classified so the upgrade does not read
      // as unexplained (casebook: the sibling absorbed by its accepted twin).
      val acceptedRowTexts = accepted.toSet()
      val surfacedSiblings = mutableListOf<String>()
      for (row in fresh.sorted()) {
        val sameLine = unpairedStale.firstOrNull {
          rowKey(it) == rowKey(row) && rowLine(it) == rowLine(row) && rowStatus(it) != rowStatus(row)
        }
        if (sameLine != null) {
          // a same-coordinate status flip outranks the sibling reading: with a stale
          // row to pair, "newly covered" explains both rows; "sibling" explains one
          unpairedStale.remove(sameLine)
          newlyCoveredPairs.add(row to sameLine)
          continue
        }
        if (row in acceptedRowTexts) {
          surfacedSiblings.add(row)
          continue
        }
        val moved = unpairedStale.firstOrNull {
          rowKey(it) == rowKey(row) && rowLine(it) != rowLine(row) && rowStatus(it) == rowStatus(row)
        }
        if (moved != null) {
          unpairedStale.remove(moved)
          shiftPairs.add(row to moved)
        }
      }
      val shiftedFrom = shiftPairs.toMap(mutableMapOf())
      val newlyCoveredFrom = newlyCoveredPairs.toMap(mutableMapOf())
      val surfacedSiblingTexts = surfacedSiblings.toSet()
      val unexplained = fresh.size - shiftPairs.size - newlyCoveredPairs.size - surfacedSiblings.size

      // Line numbers are metadata, not identity. When every new row is a pure line
      // shift AND the per-(class, method, mutator, status) population is unchanged,
      // nothing moved but text: pass with a notice instead of demanding the
      // refresh dance after every edit above a mutated method. '-PnoDriftTolerance'
      // restores the strict behaviour for certifying runs.
      fun lineless(rowList: List<String>) = rowList
          .map { row -> row.split(',').let { "${it[0]},${it[1]},${it[3]},${it[4]}" } }
          .groupingBy { it }.eachCount()
      val pureShift = fresh.isNotEmpty() &&
          unexplained == 0 && newlyCoveredPairs.isEmpty() && shiftPairs.size == fresh.size
      val populationUnchanged = pureShift && lineless(current) == lineless(accepted)
      if (populationUnchanged && !noDriftTolerance) {
        logger.lifecycle(
            "pitest baseline '$suiteName': ${shiftPairs.size} row(s) moved line only — same mutants, same " +
                "statuses, same counts per method. Passing; refresh with -PupdateMutationBaseline when convenient."
        )
        return@doLast
      }
      fun shiftHint(row: String): String = when {
        row in surfacedSiblingTexts ->
          " (sibling of an accepted identical row — surfaced by the multiset comparison; pre-existing debt, not a regression)"
        shiftedFrom.containsKey(row) -> " (shifted from line ${rowLine(shiftedFrom.getValue(row))})"
        newlyCoveredFrom.containsKey(row) ->
          " (newly covered — was ${rowStatus(newlyCoveredFrom.getValue(row))} at this line; triage, not a refresh)"
        else -> ""
      }
      if (stale.isNotEmpty()) {
        // Point at prune, not update: when the only news is *fewer* survivors, the
        // shrink-only refresh is the always-safe direction — it cannot bake in a
        // coin-flip from this one run, which is exactly what recommending a full
        // rewrite here used to invite. Update stays the answer when rows also need
        // rewriting (a status flip), and that path is reported separately below.
        // Only the pure-shrink case gets the prune recommendation. With new rows
        // present the stale ones are usually their moved counterparts, and prune
        // would drop the old line without writing the new one — leaving the shift
        // unexplained. That case still wants update — after the new rows are
        // triaged, since they may also be newly covered or surfaced siblings,
        // where update-before-triage is exactly the laundering the ratchet exists
        // to prevent.
        val direction = if (fresh.isEmpty()) "-PpruneMutationBaseline (shrink-only; nothing new to bake in)"
        else "-PupdateMutationBaseline after the new rows below are triaged"
        logger.lifecycle(
            "pitest baseline '$suiteName': ${stale.size} stale entries (since killed or moved) — refresh with $direction")
      }
      if (fresh.isNotEmpty()) {
        // Split the report: the two statuses need opposite responses, and saying so
        // here saves re-deriving it from the raw rows.
        val freshByStatus = fresh.groupBy { it.substringAfterLast(',') }
        val detail = buildString {
          freshByStatus["NO_COVERAGE"]?.let {
            append("\n  ${it.size} NO_COVERAGE — no test reaches these; mechanical work, ")
            append("and never acceptable as \"equivalent\" since the behaviour was never observed:\n")
            append(it.joinToString("\n") { row -> "    $row${shiftHint(row)}${describe(row)}" })
          }
          freshByStatus["SURVIVED"]?.let {
            append("\n  ${it.size} SURVIVED — a test ran these and could not tell the difference; ")
            append("strengthen the assertion or triage for equivalence:\n")
            append(it.joinToString("\n") { row -> "    $row${shiftHint(row)}${describe(row)}${siblingHint(row)}" })
          }
          // The churn tally answers the question the per-row hints cannot: is the whole
          // set accounted for? Refreshing is only safe when nothing is unexplained and
          // nothing was newly covered.
          append("\n  churn: ${shiftPairs.size} shifted, ${newlyCoveredPairs.size} newly covered, ")
          if (surfacedSiblings.isNotEmpty()) append("${surfacedSiblings.size} surfaced sibling(s), ")
          append("$unexplained unexplained (of ${fresh.size} new; ${stale.size} stale)")
          if (populationUnchanged) {
            append("\n  every new row is a shifted counterpart and nothing is unexplained — pure line churn, ")
            append("failing only because -PnoDriftTolerance is active; confirm the pairings above, then ")
            append("refresh with -PupdateMutationBaseline")
          } else if (unexplained == 0 && newlyCoveredPairs.isEmpty() && shiftPairs.isNotEmpty()) {
            append("\n  every new row is a shifted counterpart and nothing is unexplained, but the per-method ")
            append("population changed — kills mixed with drift; confirm the pairings above, then refresh ")
            append("with -PupdateMutationBaseline")
          } else if (newlyCoveredPairs.isNotEmpty()) {
            append("\n  ${newlyCoveredPairs.size} row(s) are newly covered rather than moved: a test now reaches ")
            append("them, so they are triage (kill or accept with a reason), not a refresh")
          }
          if (surfacedSiblings.isNotEmpty()) {
            append("\n  ${surfacedSiblings.size} row(s) are sibling mutants of accepted identical rows, surfaced ")
            append("by the multiset comparison (upgrading a set-based baseline materializes these): pre-existing ")
            append("debt made visible — kill, or accept into the documented family")
          }
        }
        throw GradleException(
            "pitest '$suiteName': ${fresh.size} unkilled mutant(s) not in the accepted baseline:" +
                detail +
                "\nKill them with tests, or accept knowingly by re-running with -PupdateMutationBaseline " +
                "and documenting the reason (see HARDENING.md). If this suite has never been seeded, " +
                "-PupdateMutationBaseline creates config/pitest/$suiteName-accepted.csv."
        )
      }
    }
  }
  tasks.register("${pitestTaskName}Debt") {
    group = "verification"
    description = "Prints the '$suiteName' unkilled-mutant debt grouped by class, largest first, with the baseline delta."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    doLast {
      fun tally(pairs: List<Pair<String, String>>): Map<String, Pair<Int, Int>> = pairs
          .groupBy({ it.first }, { it.second })
          .mapValues { (_, statuses) ->
            statuses.count { it == "SURVIVED" } to statuses.count { it == "NO_COVERAGE" }
          }

      val baselinePairs = if (baselineFile.exists()) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.substringBefore('#').trim().split(',') }
            .filter { it.size >= 5 }
            .map { it[0] to it[4] }
      } else {
        emptyList()
      }
      val csv = csvProvider.get().asFile
      val reportPairs = if (csv.isFile && !csv.parentFile.resolve(".scoped").isFile) {
        csv.readLines()
            .map { it.split(',') }
            .filter { it.size >= 6 && (it[5] == "SURVIVED" || it[5] == "NO_COVERAGE") }
            .map { it[1] to it[5] }
      } else {
        null
      }
      val source = if (reportPairs != null) "current report" else "baseline (no full report present)"
      val debt = tally(reportPairs ?: baselinePairs)
      val baselineDebt = tally(baselinePairs)
      if (debt.isEmpty()) {
        logger.lifecycle("pitest '$suiteName' debt: none — nothing unkilled in the $source")
        return@doLast
      }
      val lines = debt.entries
          .sortedByDescending { it.value.first + it.value.second }
          .map { (fqcn, counts) ->
            val (survived, noCoverage) = counts
            val base = baselineDebt[fqcn]
            val delta = if (reportPairs == null || base == null && survived + noCoverage == 0) ""
            else {
              val d = (survived + noCoverage) - ((base?.first ?: 0) + (base?.second ?: 0))
              when {
                d < 0 -> "  ($d vs baseline)"
                d > 0 -> "  (+$d vs baseline)"
                else -> ""
              }
            }
            "  %4d survived  %4d no_coverage  %s%s".format(survived, noCoverage, fqcn, delta)
          }
      val totalSurvived = debt.values.sumOf { it.first }
      val totalNoCoverage = debt.values.sumOf { it.second }
      logger.lifecycle(
          "pitest '$suiteName' debt ($source) — $totalSurvived survived, $totalNoCoverage no_coverage " +
              "across ${debt.size} class(es):\n" + lines.joinToString("\n")
      )
    }
  }

  qualityGate.configure { dependsOn(pitestTaskName) }
  convergeSuiteNames.add(suiteName)
  pitestConvergeSnapshot.configure { dependsOn(pitestTaskName) }
  // ordered, not depended on: a combined '<suites> pitestModeSnapshot' invocation must
  // not stash before the runs finish — or clear a report the verify finalizer still reads
  pitestModeSnapshot.configure { mustRunAfter(pitestTaskName, "${pitestTaskName}Verify") }

  // Shared JavaExec configuration for the ratchet run, the converge second round, and
  // the mutator trial (which redirects the report and swaps the mutator set).
  // Minion-side test failures repeat once per mutant that reruns the test, burying
  // the useful output under identical stack traces. First occurrence passes through;
  // repeats are counted and summarized after the run.
  //
  // Both of the process's streams are filtered: PIT logs through java.util.logging,
  // whose default console handler writes to *stderr*, so a stdout-only filter sees
  // none of the minion chatter it exists to collapse. The two streams share the
  // seen-set and the counter — a repeat is a repeat whichever stream carried it —
  // and Gradle pumps each on its own reader thread, hence the concurrent state; the
  // partial-line buffer stays per-stream so interleaved writes cannot splice.
  class MinionLineFilter(
      private val delegate: OutputStream,
      private val seen: MutableSet<String>,
      private val suppressed: AtomicInteger
  ) : OutputStream() {
    // (constructed only through MinionFilters, which owns the shared state)
    private val buffer = ByteArrayOutputStream()

    override fun write(b: Int) {
      buffer.write(b)
      if (b == '\n'.code) {
        flushLine()
      }
    }

    private fun flushLine() {
      val line = buffer.toString(Charsets.UTF_8)
      buffer.reset()
      if (line.contains("PIT >> INFO : MINION :")) {
        val content = line.substringAfter("MINION :").trim()
        if (!seen.add(content)) {
          suppressed.incrementAndGet()
          return
        }
      }
      delegate.write(line.toByteArray())
    }

    override fun flush() {
      delegate.flush()
    }

    override fun close() {
      if (buffer.size() > 0) {
        flushLine()
      }
      delegate.flush()
    }
  }

  // One filter per process stream, sharing the seen-set and counter (a repeat
  // is a repeat whichever stream carried it); created at execution time so the
  // configuration cache never sees a live stream.
  class MinionFilters {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val suppressed = AtomicInteger()
    val out = MinionLineFilter(System.out, seen, suppressed)
    val err = MinionLineFilter(System.err, seen, suppressed)

    fun closeAndCount(): Int {
      out.close()
      err.close()
      return suppressed.get()
    }
  }

  fun pitestExec(
      reportSubdir: String,
      mutatorsSource: Provider<String>,
      withHistory: Boolean,
      // when false (the mutator trial) a non-zero exit is a tolerated result, not a
      // failure; otherwise doLast re-raises it after the filters are closed
      enforceExit: Boolean = true
  ): JavaExec.() -> Unit = {
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
    // '-PmutateOnly=<glob[,glob]>' narrows the mutated classes for a fast
    // kill-and-rerun iteration loop. The report it produces is partial, so the
    // run stamps a '.scoped' marker and every baseline-touching consumer
    // (verify's ratchet, refresh, union, mode snapshots) refuses to treat it
    // as evidence. Tests still run in full: coverage targeting is unchanged.
    val mutateOnly = providers.gradleProperty("mutateOnly")
    val targetClassesArg = mutateOnly.map { "--targetClasses=$it" }
        .orElse(suite.targetClasses.map { "--targetClasses=" + it.joinToString(",") })
    // a map lambda returning null leaves the provider absent, dropping the argument
    val excludedClassesArg = suite.excludedClasses.map { excluded ->
      if (excluded.isEmpty()) null else "--excludedClasses=" + excluded.joinToString(",")
    }
    val targetTestsArg = suite.targetTests.map { "--targetTests=$it" }
    val mutatorsArg = mutatorsSource.map { "--mutators=$it" }
    val threadsArg = suite.threads.map { "--threads=$it" }
    val timeoutFactorArg = suite.timeoutFactor.map { "--timeoutFactor=$it" }
    val timeoutConstArg = suite.timeoutConst.map { "--timeoutConst=$it" }
    val sourceDirsArg = "--sourceDirs=" + layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    val reportDirArg = "--reportDir=" + layout.buildDirectory.dir("reports/pitest/$reportSubdir").get().asFile.absolutePath
    // Incremental analysis: one rolling history file per suite, deliberately outside
    // build/ so 'clean' does not erase the accumulated results, and git-ignored
    // because it is machine-local state. Input and output are the same file; on the
    // first run the input does not exist yet and PIT starts fresh. The lifecycle line
    // keeps reuse honest — with history active a fast run is expected, so the log
    // must say why, and the pre-release gate re-earns its numbers with
    // '-PnoMutationHistory'.
    val scopedMarker = layout.buildDirectory.file("reports/pitest/$reportSubdir/.scoped")
    // Defer PIT's non-zero exit to doLast so a failed run still closes the minion
    // filters — otherwise the exec action throws, doLast is skipped, and the
    // suppressed-count summary and any buffered partial line (now including stderr,
    // where the last bytes before a crash live) are lost.
    isIgnoreExitValue = true
    // holder so doFirst can hand the execution-time filters to doLast without
    // the configuration cache trying to serialize a live stream
    val minionFilters = AtomicReference<MinionFilters?>()
    doFirst {
      this as JavaExec
      // the default (null) standard output and error both forward to the console; the
      // filters keep that destination while deduplicating repeated minion log lines
      val filters = MinionFilters()
      standardOutput = filters.out
      errorOutput = filters.err
      minionFilters.set(filters)
    }
    doLast {
      // Close the filters first, before the deferred failure is re-raised, so the
      // summary and any buffered tail survive a failing run.
      minionFilters.get()?.let { filters ->
        val suppressed = filters.closeAndCount()
        if (suppressed > 0) {
          logger.lifecycle(
              "pitest: suppressed $suppressed repeated minion log line(s) — " +
                  "first occurrence of each is above"
          )
        }
      }
      // Re-raise PIT's non-zero exit here (deferred by isIgnoreExitValue above). A
      // failed run is not evidence, so its scope marker must not be rewritten — the
      // marker update stays below the assert.
      if (enforceExit) executionResult.get().assertNormalExitValue()
      val marker = scopedMarker.get().asFile
      if (mutateOnly.isPresent) marker.writeText(mutateOnly.get() + "\n") else marker.delete()
    }
    val historyActive = withHistory && mutationHistory
    val historyFile = layout.projectDirectory.file(".pitest-history/${suite.name}.hist").asFile
    if (historyActive) {
      doFirst {
        historyFile.parentFile.mkdirs()
        logger.lifecycle("pitest '$suiteName': arcmutate history active — $historyFile")
      }
      // A history-assisted report is reuse, not observation; the marker lets
      // pitestModeSnapshot refuse to stash one as a mode's evidence.
      val markerDir = layout.buildDirectory.dir("reports/pitest/$reportSubdir")
      doLast {
        markerDir.get().asFile.resolve(".history-assisted").writeText("")
      }
    }
    argumentProviders.add {
      buildList {
        add(classPathArg.get())
        add(targetClassesArg.get())
        excludedClassesArg.orNull?.let(::add)
        add(targetTestsArg.get())
        add(sourceDirsArg)
        add(reportDirArg)
        add(mutatorsArg.get())
        add("--outputFormats=HTML,XML,CSV")
        add("--timestampedReports=false")
        add(threadsArg.get())
        add(timeoutFactorArg.get())
        add(timeoutConstArg.get())
        if (historyActive) {
          if (historyFile.isFile) {
            add("--historyInputLocation=" + historyFile.absolutePath)
          }
          add("--historyOutputLocation=" + historyFile.absolutePath)
          add("--features=+arcmutate_history")
        }
      }
    }
  }

  val configurePitestExec = pitestExec(suiteName, suite.mutators, withHistory = true)

  val runAfter = previousPitestTask
  previousPitestTask = pitestTaskName
  tasks.register<JavaExec>(pitestTaskName) {
    finalizedBy(verify)
    runAfter?.let { mustRunAfter(it) }
    group = "verification"
    description = "PIT mutation testing of the '${suite.name}' classes against their tests."
    configurePitestExec()
  }

  // The converge second round: same run, no ratchet finalizer, ordered after the
  // snapshot cleared round one's reports.
  val round2Name = "${pitestTaskName}ConvergeRound2"
  val round2After = previousRound2Task
  previousRound2Task = round2Name
  tasks.register<JavaExec>(round2Name) {
    description = "Internal to pitestConverge: second '${suite.name}' PIT run for the per-mutant diff."
    mustRunAfter(pitestConvergeSnapshot)
    round2After?.let { mustRunAfter(it) }
    configurePitestExec()
  }
  pitestConverge.configure { dependsOn(round2Name) }

  // The mutator-trial run: only the candidate mutators, no ratchet, no history, and a
  // report directory of its own so the suite's real report and baseline are untouched.
  val trialTaskName = "${pitestTaskName}MutatorTrial"
  val trialAfter = previousTrialTask
  previousTrialTask = trialTaskName
  tasks.register<JavaExec>(trialTaskName) {
    description = "Internal to pitestMutatorTrial: '${suite.name}' with only the -PtrialMutators candidates."
    trialAfter?.let { mustRunAfter(it) }
    val trialReportDir = layout.buildDirectory.dir("reports/pitest/$suiteName-trial")
    // captured locally so the doFirst lambda does not hold the script instance
    val trialMutators = trialMutatorsProperty
    doFirst {
      if (!trialMutators.isPresent) {
        throw GradleException(
            "pitestMutatorTrial needs -PtrialMutators=<MUTATOR[,...]> — candidates only " +
                "(e.g. EXPERIMENTAL_NAKED_RECEIVER), not the suite's existing set"
        )
      }
      // A failed run writes no report; without this delete it would read as the
      // previous trial's numbers.
      trialReportDir.get().asFile.deleteRecursively()
    }
    // A zero-fire trial is a result, not a failure: PIT exits non-zero when the mutator
    // set generates nothing, and the aggregate reads a missing report as zero fired —
    // so enforceExit stays off (pitestExec already runs with isIgnoreExitValue).
    pitestExec("$suiteName-trial", trialMutatorsProperty, withHistory = false, enforceExit = false).invoke(this)
  }
  pitestMutatorTrial.configure { dependsOn(trialTaskName) }
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

  // libFuzzer's '-merge=1' copies into the first (output) directory only the inputs
  // that add coverage, smallest first — corpus dedup as a task. By default the only
  // source is the committed seed corpus (pure dedup); '-PadoptLocalCorpus' adds
  // whatever local 'fuzz<Target>' runs accumulated under build/ as a second source,
  // folding locally found interesting inputs into the committed set — a deliberate
  // adoption (it can be megabytes of hash-named files), not a side effect of dedup.
  // The merge writes into a fresh staging dir and the seed corpus is replaced only
  // from a non-empty result, so a failed merge can never wipe a committed corpus.
  // Seeds whose content survives keep their committed file name (corpora name seeds
  // meaningfully — an account address, a minimized finding); only genuinely new
  // inputs arrive under libFuzzer's hash names.
  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase) + "Minimize") {
    group = "verification"
    description = "Minimizes the '${target.name}' seed corpus with libFuzzer -merge=1; -PadoptLocalCorpus also folds in inputs found by local fuzz runs."
    dependsOn(compileForFuzz)
    dependsOn(tasks.named("processResources"), tasks.named("processTestResources"))
    mainClass = "com.code_intelligence.jazzer.Jazzer"
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = jazzer + files(fuzzClassesDir) +
        files(sourceSets.main.get().output.resourcesDir!!, sourceSets.test.get().output.resourcesDir!!) +
        configurations["testRuntimeClasspath"].filter {
          !it.absolutePath.startsWith(ownBuildDir)
        }
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow"
    )
    val targetName = target.name
    val seedCorpus = target.seedCorpus
    val stagingDir = layout.buildDirectory.dir("fuzz/${target.name}-minimized").get().asFile
    val localCorpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    val minimizeTargetClassArg = target.targetClass.map { "--target_class=$it" }
    val minimizeMaxLenArg = target.maxLen.map { "-max_len=$it" }
    val adoptLocalCorpus = providers.gradleProperty("adoptLocalCorpus").isPresent
    doFirst {
      val corpus = seedCorpus.orNull?.asFile ?: throw GradleException(
          "fuzz target '$targetName' declares no seedCorpus — nothing to minimize into. " +
              "Commit a seed corpus first (see HARDENING.md 'Fuzzing').")
      if (corpus.listFiles()?.any { it.isFile } != true) {
        throw GradleException(
            "fuzz target '$targetName': seed corpus at $corpus is missing or empty — a merge cannot start from nothing.")
      }
      stagingDir.deleteRecursively()
      stagingDir.mkdirs()
    }
    argumentProviders.add {
      buildList {
        add(minimizeTargetClassArg.get())
        add("-merge=1")
        minimizeMaxLenArg.orNull?.let(::add)
        add(stagingDir.absolutePath)
        add(seedCorpus.get().asFile.absolutePath)
        if (adoptLocalCorpus && localCorpusDir.listFiles()?.any { it.isFile } == true) {
          add(localCorpusDir.absolutePath)
        }
      }
    }
    doLast {
      val corpus = seedCorpus.get().asFile
      val merged = stagingDir.listFiles()?.filter { it.isFile }.orEmpty()
      if (merged.isEmpty()) {
        throw GradleException(
            "fuzz '$targetName': the merge produced an empty corpus — refusing to touch $corpus. " +
                "Staging output: $stagingDir; the committed seed corpus is unchanged.")
      }
      fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
          .digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
      val before = corpus.listFiles()?.filter { it.isFile }.orEmpty()
      val beforeBytes = before.sumOf { it.length() }
      val originalByHash = before.associateBy(::sha256)
      val keep = mutableSetOf<File>()
      var adopted = 0
      for (file in merged) {
        val original = originalByHash[sha256(file)]
        if (original != null) {
          keep.add(original)
        } else {
          file.copyTo(corpus.resolve(file.name), overwrite = true)
          adopted++
        }
      }
      val removed = before.filterNot { it in keep }
      removed.forEach { it.delete() }
      val afterFiles = corpus.listFiles()?.filter { it.isFile }.orEmpty()
      logger.lifecycle(
          "fuzz '$targetName': corpus minimized ${before.size} -> ${afterFiles.size} file(s) " +
              "($beforeBytes -> ${afterFiles.sumOf { it.length() }} bytes) at $corpus — " +
              "$adopted newly adopted, ${removed.size} redundant removed, surviving seeds keep their names. " +
              "Review the diff before committing; update the provenance README next to the corpus.")
    }
  }
}

// A committed corpus that only runs when someone remembers to fuzz is a directory of
// files, not a regression suite. For every fuzz target with a seedCorpus, a replay test
// is generated into the test source set: each seed runs through the harness inside
// 'test' (and therefore 'check'), on the module path like any other test — and under
// PIT the replay participates as a killer. No hand-written replay class per harness,
// and no way to forget one.
val generateFuzzReplayTests = tasks.register("generateFuzzReplayTests") {
  description = "Generates seed-corpus replay tests for fuzz targets that declare a seedCorpus."
  val outputDir = layout.buildDirectory.dir("generated-sources/fuzz-replay/java")
  outputs.dir(outputDir)
  // configuration-time snapshot of plain values, so the configuration cache can serialize
  val testResourceDirs = sourceSets.test.get().resources.srcDirs.toList()
  val targets = hardening.fuzz.mapNotNull { target ->
    val corpus = target.seedCorpus.orNull?.asFile ?: return@mapNotNull null
    // A corpus under the test resources is resolved as a classpath resource — hermetic
    // under any working directory or test-distribution scheme. Anything else falls back
    // to its absolute path, which is regenerated every build so it stays machine-correct.
    val resourcePath = testResourceDirs.firstNotNullOfOrNull { dir ->
      val relative = corpus.relativeToOrNull(dir)
      if (relative == null || relative.path.startsWith("..")) null else relative.invariantSeparatorsPath
    }
    listOf(target.name, target.targetClass.get(), corpus.absolutePath, resourcePath ?: "")
  }
  inputs.property("targets", targets.map { it.joinToString("|") })
  doLast {
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    dir.mkdirs()
    targets.forEach { (name, fqcn, corpusPath, resourcePath) ->
      val pkg = fqcn.substringBeforeLast('.')
      val simple = fqcn.substringAfterLast('.')
      val className = simple + "SeedReplayTest"
      val source = dir.resolve(pkg.replace('.', '/')).resolve("$className.java")
      source.parentFile.mkdirs()
      val resolveCorpus = if (resourcePath.isNotEmpty()) {
        """
        |    final var url = $className.class.getResource("/$resourcePath");
        |    org.junit.jupiter.api.Assertions.assertNotNull(url, "seed corpus missing from test resources: /$resourcePath");
        |    final var corpus = java.nio.file.Path.of(url.toURI());
        """.trimMargin()
      } else {
        """
        |    final var corpus = java.nio.file.Path.of("${corpusPath.replace("\\", "\\\\")}");
        |    org.junit.jupiter.api.Assertions.assertTrue(
        |        java.nio.file.Files.isDirectory(corpus), "seed corpus missing: " + corpus);
        """.trimMargin()
      }
      source.writeText(
          """
          |package $pkg;
          |
          |import org.junit.jupiter.api.Test;
          |
          |/// Generated by the sava-build hardening plugin ('generateFuzzReplayTests'): replays
          |/// the committed '$name' seed corpus through the harness inside 'check', so the
          |/// corpus cannot rot between fuzz runs — an emptied corpus fails, not passes.
          |/// Regenerated every build; do not edit. Seed provenance belongs in a README next
          |/// to (never inside) the corpus directory, where a file would itself become a seed.
          |final class $className {
          |
          |  @Test
          |  void replaysSeedCorpus() throws Exception {
          |$resolveCorpus
          |    try (final var files = java.nio.file.Files.list(corpus)) {
          |      final var seeds = files.filter(java.nio.file.Files::isRegularFile).sorted().toList();
          |      org.junit.jupiter.api.Assertions.assertFalse(seeds.isEmpty(), "empty seed corpus: " + corpus);
          |      for (final var seed : seeds) {
          |        $simple.fuzzerTestOneInput(java.nio.file.Files.readAllBytes(seed));
          |      }
          |    }
          |  }
          |}
          |""".trimMargin()
      )
    }
    if (targets.isNotEmpty()) {
      logger.info("generateFuzzReplayTests: ${targets.size} replay test(s) generated")
    }
  }
}
sourceSets.test {
  java.srcDir(generateFuzzReplayTests)
}

// One-shot adoption scaffolding (HARDENING.md 'Adopting in a new repo'): the pieces that
// are pure transcription. Never overwrites anything that exists.
tasks.register("hardeningInit") {
  group = "verification"
  description = "Scaffolds config/pitest/README.md, git-ignores .pitest-history/, and prints the adoption checklist."
  val readme = layout.projectDirectory.file("config/pitest/README.md").asFile
  val gitignore = rootProject.layout.projectDirectory.file(".gitignore").asFile
  val digest = HardeningTemplateDigest.SHA256_12
  doLast {
    if (readme.isFile) {
      logger.lifecycle("hardeningInit: $readme exists — left untouched")
    } else {
      readme.parentFile.mkdirs()
      readme.writeText(
          """
          |# Mutation-testing baseline & triage policy
          |
          |Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
          |run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
          |baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
          |format: `class,method,line,mutator,status`. Full policy — the three legal
          |outcomes for a new survivor, determinism requirements, targeting rules —
          |lives in sava-build's `HARDENING.md`.
          |
          |Never refresh with `-PupdateMutationBaseline` just to make the build pass:
          |kill the mutant, refactor it out of existence, or record its equivalence
          |reason below. Pure line drift (every new row a same-status shift of a
          |stale one, populations unchanged) passes on its own with a notice —
          |refresh at a convenient moment. Anything else fails with a per-row
          |classification (`shifted` vs `newly covered` vs unexplained) and a churn
          |tally: a newly covered row is triage, not churn, and identical rows are
          |sibling mutants of one compound condition — the comparison is a
          |multiset, so never hand-dedupe the CSV.
          |
          |A baseline row may carry a trailing `# note` — `# untriaged` is the
          |conventional label for seeded debt. Notes are preserved across
          |`-PupdateMutationBaseline` / `-PunionMutationBaseline` rewrites, and the
          |verify task counts rows marked `# untriaged` so the debt stays a printed
          |number, not prose.
          |
          |## Untriaged debt
          |
          |A first baseline seeded from the pre-existing survivor population is triage
          |debt made explicit, not acceptance. List it here until each key is killed,
          |refactored away, or moved below with a reason.
          |
          |## Triaged equivalent mutants (accepted with reasons)
          |
          |Group by the principle that makes them equivalent (see the recurring families
          |in HARDENING.md); the baseline CSVs carry the exact keys.
          |
          |Shrinking a baseline is always an improvement; growing one requires a reason
          |here.
          |""".trimMargin()
      )
      logger.lifecycle("hardeningInit: wrote $readme")
    }
    val ignoreLine = ".pitest-history/"
    if (gitignore.isFile && gitignore.readText().contains(ignoreLine)) {
      logger.lifecycle("hardeningInit: .gitignore already covers $ignoreLine")
    } else {
      gitignore.appendText((if (gitignore.isFile && !gitignore.readText().endsWith("\n")) "\n" else "") +
          "\n# arcmutate incremental-analysis history (machine-local, written by the hardening plugin)\n$ignoreLine\n")
      logger.lifecycle("hardeningInit: appended $ignoreLine to $gitignore")
    }
    logger.lifecycle(
        """
        |hardeningInit: remaining adoption steps (HARDENING.md 'Adopting in a new repo'):
        |  1. register mutation suites (wildcard targets + exclusions) and fuzz targets
        |  2. pin any unseeded randomness in the test suite
        |  3. seed each baseline: ./gradlew pitest<Suite> -PupdateMutationBaseline
        |  4. copy the agent-instructions template from HARDENING.md into AGENTS.md with:
        |       <!-- hardening-template sha256:$digest -->
        |  5. decide who owns the pre-release qualityGate run, and record it in AGENTS.md
        |  6. fuzz targets with a seedCorpus get a generated replay test automatically;
        |       document seed provenance in a README next to (never inside) the corpus dir
        |  7. optional: hardening.generateTestSupport = true generates shared socket/
        |       scheduler/logging test helpers (HARDENING.md 'Shared test scaffolding')
        |""".trimMargin()
    )
  }
}

// Shared socket/concurrency/logging test helpers, generated on request
// ('hardening.generateTestSupport = true') instead of published: a handful of small
// classes is not worth a dependency in every consuming repo's test module, and generating
// them means they compile inside that module — visible on the module path and PIT's class
// path alike. See HARDENING.md 'Shared test scaffolding (generated)'.
val generateHardeningTestSupport = tasks.register("generateHardeningTestSupport") {
  description = "Generates the shared test-support sources when hardening.generateTestSupport is true."
  val outputDir = layout.buildDirectory.dir("generated-sources/hardening-support/java")
  outputs.dir(outputDir)
  val enabled = hardening.generateTestSupport
  val excludes = hardening.testSupportExcludes
  inputs.property("enabled", enabled)
  inputs.property("excludes", excludes)
  doLast {
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    if (!enabled.get()) {
      dir.mkdirs()
      return@doLast
    }
    val excluded = excludes.get().toSet()
    val pkgDir = dir.resolve("software/sava/hardening/support")
    pkgDir.mkdirs()
    // each helper is skippable by simple name — 'JulRecorder' cannot compile in a test
    // module that does not read 'java.logging'
    fun generate(className: String, source: () -> String) {
      if (className !in excluded) {
        pkgDir.resolve("$className.java").writeText(source())
      }
    }
    generate("Ports") { """
        |package software.sava.hardening.support;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |public final class Ports {
        |
        |  /// An ephemeral port that was free at probe time. Socket tests should bind it on
        |  /// localhost and connect to 127.0.0.1 explicitly — never "localhost", whose ::1
        |  /// resolution can reach another JVM's wildcard bind on the same port number.
        |  public static int freePort() {
        |    try (final var socket = new java.net.ServerSocket(0)) {
        |      return socket.getLocalPort();
        |    } catch (final java.io.IOException e) {
        |      throw new java.io.UncheckedIOException(e);
        |    }
        |  }
        |
        |  private Ports() {
        |  }
        |}
        |""".trimMargin() }
    generate("RecordingExecutor") { """
        |package software.sava.hardening.support;
        |
        |import java.util.concurrent.Executor;
        |import java.util.concurrent.atomic.AtomicInteger;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Delegates while counting dispatches: turns "wire-invisible" executor configuration
        |/// into an assertable property (see HARDENING.md's test conventions).
        |public final class RecordingExecutor implements Executor {
        |
        |  private final Executor delegate;
        |  private final AtomicInteger dispatches = new AtomicInteger();
        |
        |  public RecordingExecutor(final Executor delegate) {
        |    this.delegate = delegate;
        |  }
        |
        |  @Override
        |  public void execute(final Runnable command) {
        |    dispatches.incrementAndGet();
        |    delegate.execute(command);
        |  }
        |
        |  public int dispatches() {
        |    return dispatches.get();
        |  }
        |}
        |""".trimMargin() }
    generate("ConcurrencyHarness") { """
        |package software.sava.hardening.support;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Deterministic sequencing for concurrency tests, distilled from the mutation
        |/// campaigns (see HARDENING.md's concurrency conventions): poll observable state
        |/// instead of sleeping on timing guesses, assert timing only as a lower bound
        |/// (machine load can lengthen an interval but never shorten it), and always
        |/// bound joins so a hung thread fails the test instead of the build.
        |/// Framework-neutral: failures throw AssertionError, which every test engine
        |/// reports as a failure.
        |public final class ConcurrencyHarness {
        |
        |  /// Polls the condition roughly every millisecond, failing after ~5 seconds.
        |  /// Use an observable side effect (a recorded call, a volatile flag, a thread
        |  /// state) as the condition — never a sleep of a guessed length.
        |  public static void awaitTrue(final String what, final java.util.function.BooleanSupplier condition) throws InterruptedException {
        |    for (int i = 0; i < 5_000; ++i) {
        |      if (condition.getAsBoolean()) {
        |        return;
        |      }
        |      Thread.sleep(1);
        |    }
        |    throw new AssertionError("timed out awaiting " + what);
        |  }
        |
        |  /// Polls until the thread reaches one of the given states — WAITING for an
        |  /// unbounded condition await, TIMED_WAITING for a bounded one. This is how a
        |  /// test proves "the worker is parked" before poking it, instead of sleeping
        |  /// and hoping.
        |  public static void awaitState(final Thread thread, final Thread.State... states) throws InterruptedException {
        |    awaitTrue(thread.getName() + " in " + java.util.Arrays.toString(states), () -> {
        |      final Thread.State current = thread.getState();
        |      for (final Thread.State state : states) {
        |        if (current == state) {
        |          return true;
        |        }
        |      }
        |      return false;
        |    });
        |  }
        |
        |  /// Joins with a bound; a still-alive thread is interrupted (so the test JVM
        |  /// can exit) and the test fails with the caller's explanation.
        |  public static void joinOrFail(final Thread thread, final long millis, final String what) throws InterruptedException {
        |    thread.join(millis);
        |    if (thread.isAlive()) {
        |      thread.interrupt();
        |      throw new AssertionError(what + " (thread '" + thread.getName() + "' still alive after " + millis + "ms)");
        |    }
        |  }
        |
        |  private ConcurrencyHarness() {
        |  }
        |}
        |""".trimMargin() }
    generate("JulRecorder") { """
        |package software.sava.hardening.support;
        |
        |import java.text.MessageFormat;
        |import java.util.ArrayList;
        |import java.util.List;
        |import java.util.logging.Handler;
        |import java.util.logging.Level;
        |import java.util.logging.LogRecord;
        |import java.util.logging.Logger;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// Captures records published to a JUL logger while attached; use try-with-resources so
        |/// the handler always detaches. Needs 'java.logging' readable from the test module.
        |///
        |/// While attached the logger is forced to {@link Level#ALL} with parent handlers
        |/// detached, and both are restored on close. Without that, attaching to a logger a repo
        |/// silenced in test setup — a common pattern, and the one this replaces — would capture
        |/// nothing at all, and anything the logger did publish would still reach the console.
        |public final class JulRecorder implements AutoCloseable {
        |
        |  private final Logger logger;
        |  private final Handler handler;
        |  private final Level previousLevel;
        |  private final boolean previousUseParentHandlers;
        |  private final List<LogRecord> records = new ArrayList<>();
        |
        |  private JulRecorder(final Logger logger) {
        |    this.logger = logger;
        |    this.previousLevel = logger.getLevel();
        |    this.previousUseParentHandlers = logger.getUseParentHandlers();
        |    this.handler = new Handler() {
        |      @Override
        |      public void publish(final LogRecord record) {
        |        synchronized (records) {
        |          records.add(record);
        |        }
        |      }
        |
        |      @Override
        |      public void flush() {
        |      }
        |
        |      @Override
        |      public void close() {
        |      }
        |    };
        |    logger.setLevel(Level.ALL);
        |    logger.setUseParentHandlers(false);
        |    logger.addHandler(handler);
        |  }
        |
        |  public static JulRecorder attach(final String loggerName) {
        |    return new JulRecorder(Logger.getLogger(loggerName));
        |  }
        |
        |  public static JulRecorder attach(final Class<?> loggerClass) {
        |    return attach(loggerClass.getName());
        |  }
        |
        |  /// @return a snapshot of the records captured so far.
        |  public List<LogRecord> records() {
        |    synchronized (records) {
        |      return List.copyOf(records);
        |    }
        |  }
        |
        |  /// Each record as a handler would render it. Services commonly log '{0}' style
        |  /// patterns, so the values worth asserting on live in the record's parameters rather
        |  /// than in its raw message — asserting against {@link LogRecord#getMessage()} alone
        |  /// silently never matches them.
        |  public List<String> messages() {
        |    final var formatted = new ArrayList<String>();
        |    for (final var record : records()) {
        |      formatted.add(format(record));
        |    }
        |    return List.copyOf(formatted);
        |  }
        |
        |  /// @return whether any captured record contains {@code fragment} once formatted.
        |  public boolean logged(final String fragment) {
        |    for (final var message : messages()) {
        |      if (message != null && message.contains(fragment)) {
        |        return true;
        |      }
        |    }
        |    return false;
        |  }
        |
        |  private static String format(final LogRecord record) {
        |    final var message = record.getMessage();
        |    final var parameters = record.getParameters();
        |    if (message == null || parameters == null || parameters.length == 0) {
        |      return message;
        |    }
        |    try {
        |      return MessageFormat.format(message, parameters);
        |    } catch (final IllegalArgumentException e) {
        |      return message;
        |    }
        |  }
        |
        |  @Override
        |  public void close() {
        |    logger.removeHandler(handler);
        |    logger.setLevel(previousLevel);
        |    logger.setUseParentHandlers(previousUseParentHandlers);
        |  }
        |}
        |""".trimMargin() }
    generate("LoopbackHttpServer") { """
        |package software.sava.hardening.support;
        |
        |import java.io.ByteArrayOutputStream;
        |import java.io.IOException;
        |import java.io.InputStream;
        |import java.io.UncheckedIOException;
        |import java.net.InetAddress;
        |import java.net.ServerSocket;
        |import java.nio.charset.StandardCharsets;
        |import java.util.concurrent.BlockingQueue;
        |import java.util.concurrent.LinkedBlockingQueue;
        |import java.util.concurrent.TimeUnit;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// A scripted loopback HTTP server serving exactly the bytes you enqueue — including
        |/// what a well-behaved server library refuses to produce (a 199 status, a truncated
        |/// header block), which is the scaffolding for transport paths and status-boundary
        |/// guards otherwise accepted as unreachable in-harness. One enqueued response serves
        |/// one connection, then the connection closes; requests are recorded verbatim.
        |/// Binds the loopback address explicitly — connect to "127.0.0.1", never "localhost",
        |/// whose ::1 resolution can reach another JVM's wildcard bind on the same port.
        |public final class LoopbackHttpServer implements AutoCloseable {
        |
        |  private final ServerSocket serverSocket;
        |  private final Thread acceptor;
        |  private final BlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();
        |  private final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        |
        |  private LoopbackHttpServer(final ServerSocket serverSocket) {
        |    this.serverSocket = serverSocket;
        |    this.acceptor = new Thread(this::serve, "loopback-http-" + serverSocket.getLocalPort());
        |    this.acceptor.setDaemon(true);
        |  }
        |
        |  public static LoopbackHttpServer start() {
        |    try {
        |      final var server = new LoopbackHttpServer(new ServerSocket(0, 16, InetAddress.getLoopbackAddress()));
        |      server.acceptor.start();
        |      return server;
        |    } catch (final IOException e) {
        |      throw new UncheckedIOException(e);
        |    }
        |  }
        |
        |  public int port() {
        |    return serverSocket.getLocalPort();
        |  }
        |
        |  public String baseUri() {
        |    return "http://127.0.0.1:" + port();
        |  }
        |
        |  /// Queues one raw response, served verbatim (ISO-8859-1 bytes) to the next connection.
        |  public LoopbackHttpServer enqueue(final String rawResponse) {
        |    responses.add(rawResponse.getBytes(StandardCharsets.ISO_8859_1));
        |    return this;
        |  }
        |
        |  /// Convenience: a minimal well-formed 'Connection: close' response.
        |  public LoopbackHttpServer enqueue(final int status, final String body) {
        |    final var bytes = body.getBytes(StandardCharsets.UTF_8);
        |    return enqueue("HTTP/1.1 " + status + " Status\r\nContent-Length: " + bytes.length
        |        + "\r\nConnection: close\r\n\r\n" + new String(bytes, StandardCharsets.ISO_8859_1));
        |  }
        |
        |  /// The next recorded request — start line, headers, and any Content-Length body,
        |  /// verbatim — or null if none arrives within the timeout.
        |  public String takeRequest(final long timeout, final TimeUnit unit) throws InterruptedException {
        |    return requests.poll(timeout, unit);
        |  }
        |
        |  private void serve() {
        |    while (!serverSocket.isClosed()) {
        |      try (final var socket = serverSocket.accept()) {
        |        requests.add(readRequest(socket.getInputStream()));
        |        final var response = responses.poll(30, TimeUnit.SECONDS);
        |        if (response == null) {
        |          continue; // nothing scripted: the dropped connection is itself a test input
        |        }
        |        socket.getOutputStream().write(response);
        |        socket.getOutputStream().flush();
        |      } catch (final IOException | InterruptedException | RuntimeException e) {
        |        return; // closed, or the harness is broken enough that hanging would hide it
        |      }
        |    }
        |  }
        |
        |  private static String readRequest(final InputStream in) throws IOException {
        |    final var head = new ByteArrayOutputStream();
        |    int matched = 0;
        |    for (int b; matched < 4 && (b = in.read()) >= 0; ) {
        |      head.write(b);
        |      matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : (b == '\r' ? 1 : 0);
        |    }
        |    var request = head.toString(StandardCharsets.ISO_8859_1);
        |    final int contentLength = contentLength(request);
        |    if (contentLength > 0) {
        |      request += new String(in.readNBytes(contentLength), StandardCharsets.ISO_8859_1);
        |    }
        |    return request;
        |  }
        |
        |  private static int contentLength(final String head) {
        |    for (final var line : head.split("\r\n")) {
        |      final int colon = line.indexOf(':');
        |      if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
        |        return Integer.parseInt(line.substring(colon + 1).trim());
        |      }
        |    }
        |    return 0;
        |  }
        |
        |  @Override
        |  public void close() {
        |    try {
        |      serverSocket.close();
        |    } catch (final IOException e) {
        |      // closing anyway
        |    }
        |    acceptor.interrupt();
        |  }
        |}
        |""".trimMargin() }
    generate("ManualScheduledExecutor") { """
        |package software.sava.hardening.support;
        |
        |import java.time.Duration;
        |import java.util.ArrayList;
        |import java.util.Collection;
        |import java.util.List;
        |import java.util.PriorityQueue;
        |import java.util.concurrent.Callable;
        |import java.util.concurrent.Delayed;
        |import java.util.concurrent.Executors;
        |import java.util.concurrent.Future;
        |import java.util.concurrent.FutureTask;
        |import java.util.concurrent.RejectedExecutionException;
        |import java.util.concurrent.ScheduledExecutorService;
        |import java.util.concurrent.ScheduledFuture;
        |import java.util.concurrent.TimeUnit;
        |
        |/// Generated by the sava-build hardening plugin; regenerated every build, do not edit.
        |///
        |/// A deterministic, single-threaded ScheduledExecutorService for clock-seam tests:
        |/// tasks run on the caller's thread only when the test advances the fake clock past
        |/// their trigger, so pacing, backoff and reconnect choreography become exact
        |/// functions of the delays requested — no real waits, nothing for PIT to multiply.
        |/// The clock starts at a non-zero origin so "timestamp mutated to 0" mutants stay
        |/// observable. Not thread-safe by design: determinism is the point.
        |public final class ManualScheduledExecutor implements ScheduledExecutorService {
        |
        |  private static final long ORIGIN_NANOS = 1_000_000_000L;
        |
        |  private final PriorityQueue<ManualTask<?>> tasks = new PriorityQueue<>();
        |  private long nowNanos = ORIGIN_NANOS;
        |  private long sequence;
        |  private boolean shutdown;
        |
        |  public long nowNanos() {
        |    return nowNanos;
        |  }
        |
        |  /// Runs every task due within the duration in trigger order, advancing the clock
        |  /// to each task's trigger as it runs (periodic tasks re-fire as many times as
        |  /// fit); ends with the clock at now + duration.
        |  ///
        |  /// @return the number of task executions
        |  public int advance(final Duration duration) {
        |    final long target = nowNanos + duration.toNanos();
        |    int executed = 0;
        |    for (var task = tasks.peek(); task != null && task.triggerNanos <= target; task = tasks.peek()) {
        |      tasks.poll();
        |      if (task.isCancelled()) {
        |        continue;
        |      }
        |      nowNanos = Math.max(nowNanos, task.triggerNanos);
        |      task.execute();
        |      ++executed;
        |    }
        |    nowNanos = target;
        |    return executed;
        |  }
        |
        |  /// Runs tasks due at the current instant without advancing the clock.
        |  public int runDue() {
        |    return advance(Duration.ZERO);
        |  }
        |
        |  /// @return scheduled tasks not yet run or cancelled.
        |  public int pending() {
        |    return (int) tasks.stream().filter(task -> !task.isCancelled()).count();
        |  }
        |
        |  private <V> ManualTask<V> enqueue(final Callable<V> callable, final long delayNanos, final long periodNanos) {
        |    if (shutdown) {
        |      throw new RejectedExecutionException("ManualScheduledExecutor is shut down");
        |    }
        |    final var task = new ManualTask<V>(callable, nowNanos + Math.max(0L, delayNanos), periodNanos);
        |    tasks.add(task);
        |    return task;
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        |    return enqueue(Executors.callable(command), unit.toNanos(delay), 0L);
        |  }
        |
        |  @Override
        |  public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
        |    return enqueue(callable, unit.toNanos(delay), 0L);
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
        |    if (period <= 0) {
        |      throw new IllegalArgumentException("period must be positive");
        |    }
        |    return enqueue(Executors.callable(command), unit.toNanos(initialDelay), unit.toNanos(period));
        |  }
        |
        |  @Override
        |  public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
        |    if (delay <= 0) {
        |      throw new IllegalArgumentException("delay must be positive");
        |    }
        |    return enqueue(Executors.callable(command), unit.toNanos(initialDelay), -unit.toNanos(delay));
        |  }
        |
        |  @Override
        |  public void execute(final Runnable command) {
        |    enqueue(Executors.callable(command), 0L, 0L);
        |  }
        |
        |  @Override
        |  public Future<?> submit(final Runnable task) {
        |    return enqueue(Executors.callable(task), 0L, 0L);
        |  }
        |
        |  @Override
        |  public <T> Future<T> submit(final Runnable task, final T result) {
        |    return enqueue(Executors.callable(task, result), 0L, 0L);
        |  }
        |
        |  @Override
        |  public <T> Future<T> submit(final Callable<T> task) {
        |    return enqueue(task, 0L, 0L);
        |  }
        |
        |  @Override
        |  public void shutdown() {
        |    shutdown = true;
        |  }
        |
        |  @Override
        |  public List<Runnable> shutdownNow() {
        |    shutdown = true;
        |    final var drained = new ArrayList<Runnable>(tasks);
        |    tasks.clear();
        |    return drained;
        |  }
        |
        |  @Override
        |  public boolean isShutdown() {
        |    return shutdown;
        |  }
        |
        |  @Override
        |  public boolean isTerminated() {
        |    return shutdown && tasks.isEmpty();
        |  }
        |
        |  @Override
        |  public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        |    return isTerminated();
        |  }
        |
        |  // Blocking multi-submit has no deterministic single-threaded semantics: the tasks
        |  // could only run when the clock advances, which the blocked caller cannot do.
        |
        |  @Override
        |  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  @Override
        |  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit) {
        |    throw new UnsupportedOperationException("blocking multi-submit cannot be deterministic here");
        |  }
        |
        |  private final class ManualTask<V> extends FutureTask<V> implements ScheduledFuture<V> {
        |
        |    private final long periodNanos; // 0 one-shot, >0 fixed-rate, <0 fixed-delay
        |    private final long seq;
        |    private long triggerNanos;
        |
        |    private ManualTask(final Callable<V> callable, final long triggerNanos, final long periodNanos) {
        |      super(callable);
        |      this.triggerNanos = triggerNanos;
        |      this.periodNanos = periodNanos;
        |      this.seq = sequence++;
        |    }
        |
        |    private void execute() {
        |      if (periodNanos == 0L) {
        |        run();
        |      } else if (runAndReset()) {
        |        triggerNanos = periodNanos > 0L ? triggerNanos + periodNanos : nowNanos - periodNanos;
        |        tasks.add(this);
        |      }
        |    }
        |
        |    @Override
        |    public long getDelay(final TimeUnit unit) {
        |      return unit.convert(triggerNanos - nowNanos, TimeUnit.NANOSECONDS);
        |    }
        |
        |    @Override
        |    public int compareTo(final Delayed other) {
        |      if (other instanceof ManualScheduledExecutor.ManualTask<?> task) {
        |        final int byTrigger = Long.compare(triggerNanos, task.triggerNanos);
        |        return byTrigger != 0 ? byTrigger : Long.compare(seq, task.seq);
        |      }
        |      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        |    }
        |  }
        |}
        |""".trimMargin() }
  }
}
sourceSets.test {
  java.srcDir(generateHardeningTestSupport)
}
