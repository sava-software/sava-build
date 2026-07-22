import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningTemplateDigest
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
hardening.arcmutateBaseVersion.convention(HardeningToolDefaults.ARCMUTATE_BASE)
hardening.generateTestSupport.convention(false)
hardening.testSupportExcludes.convention(emptyList())

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
    val listUnkilled = providers.gradleProperty("listUnkilled").isPresent
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

      val current = rows.mapNotNull { parts ->
        if (parts[5] !in gated) {
          null
        } else {
          // class,method,line,mutator,status — line numbers churn on refactors;
          // refresh the baseline when they do
          "${parts[1]},${parts[3]},${parts[4]},${parts[2].substringAfterLast('.')},${parts[5]}"
        }
      }.toSortedSet()
      if (listUnkilled && current.isNotEmpty()) {
        logger.lifecycle(
            "pitest '$suiteName' unkilled:\n" +
                current.joinToString("\n") { row -> "  $row${describe(row)}" }
        )
      }
      // A baseline row may carry a trailing '# note' ('# untriaged' is the conventional
      // label for seeded debt). Notes are stripped for comparison, preserved across both
      // refresh flags, and the untriaged count is printed — so triage state lives on the
      // row it describes and stays a number the build reports, not prose that drifts.
      val annotations = mutableMapOf<String, String>()
      val accepted: Set<String> = if (baselineFile.exists()) {
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
            .toSet()
      } else {
        emptySet()
      }
      fun baselineLine(row: String) = annotations[row]?.let { "$row $it" } ?: row
      val untriaged = accepted.count { annotations[it]?.contains("untriaged", ignoreCase = true) == true }
      if (untriaged > 0) {
        logger.lifecycle("pitest baseline '$suiteName': ${accepted.size} rows, $untriaged marked '# untriaged'")
      }
      if (update) {
        val dropped = accepted - current
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(current.joinToString("\n", postfix = "\n") { baselineLine(it) })
        logger.lifecycle("pitest baseline '$suiteName': wrote ${current.size} accepted entries")
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
        val added = current - accepted
        if (added.isEmpty()) {
          logger.lifecycle("pitest baseline '$suiteName': union added nothing new")
        } else {
          val merged = (accepted + current).toSortedSet()
          baselineFile.parentFile.mkdirs()
          baselineFile.writeText(merged.joinToString("\n", postfix = "\n") { baselineLine(it) })
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${added.size} entries (baseline now ${merged.size}):\n" +
                  added.joinToString("\n") { row -> "  $row${describe(row)}" }
          )
        }
        return@doLast
      }
      val fresh = current - accepted
      val stale = accepted - current
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

      val unpairedStale = stale.toMutableSet()
      val shiftedFrom = mutableMapOf<String, String>()
      val newlyCoveredFrom = mutableMapOf<String, String>()
      for (row in fresh.sorted()) {
        val sameLine = unpairedStale.firstOrNull {
          rowKey(it) == rowKey(row) && rowLine(it) == rowLine(row) && rowStatus(it) != rowStatus(row)
        }
        if (sameLine != null) {
          unpairedStale.remove(sameLine)
          newlyCoveredFrom[row] = sameLine
          continue
        }
        val moved = unpairedStale.firstOrNull {
          rowKey(it) == rowKey(row) && rowLine(it) != rowLine(row) && rowStatus(it) == rowStatus(row)
        }
        if (moved != null) {
          unpairedStale.remove(moved)
          shiftedFrom[row] = moved
        }
      }
      val unexplained = fresh.size - shiftedFrom.size - newlyCoveredFrom.size
      fun shiftHint(row: String): String = when {
        shiftedFrom.containsKey(row) -> " (shifted from line ${rowLine(shiftedFrom.getValue(row))})"
        newlyCoveredFrom.containsKey(row) ->
          " (newly covered — was ${rowStatus(newlyCoveredFrom.getValue(row))} at this line; triage, not a refresh)"
        else -> ""
      }
      if (stale.isNotEmpty()) {
        logger.lifecycle("pitest baseline '$suiteName': ${stale.size} stale entries (since killed or moved) — refresh with -PupdateMutationBaseline")
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
            append(it.joinToString("\n") { row -> "    $row${shiftHint(row)}${describe(row)}" })
          }
          // The churn tally answers the question the per-row hints cannot: is the whole
          // set accounted for? Refreshing is only safe when nothing is unexplained and
          // nothing was newly covered.
          append("\n  churn: ${shiftedFrom.size} shifted, ${newlyCoveredFrom.size} newly covered, ")
          append("$unexplained unexplained (of ${fresh.size} new; ${stale.size} stale)")
          if (unexplained == 0 && newlyCoveredFrom.isEmpty() && shiftedFrom.isNotEmpty()) {
            append("\n  every new row is a shifted counterpart and nothing is unexplained — line churn; ")
            append("confirm the pairings above, then refresh with -PupdateMutationBaseline")
          } else if (newlyCoveredFrom.isNotEmpty()) {
            append("\n  ${newlyCoveredFrom.size} row(s) are newly covered rather than moved: a test now reaches ")
            append("them, so they are triage (kill or accept with a reason), not a refresh")
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
  qualityGate.configure { dependsOn(pitestTaskName) }
  convergeSuiteNames.add(suiteName)
  pitestConvergeSnapshot.configure { dependsOn(pitestTaskName) }
  // ordered, not depended on: a combined '<suites> pitestModeSnapshot' invocation must
  // not stash before the runs finish — or clear a report the verify finalizer still reads
  pitestModeSnapshot.configure { mustRunAfter(pitestTaskName, "${pitestTaskName}Verify") }

  // Shared JavaExec configuration for the ratchet run, the converge second round, and
  // the mutator trial (which redirects the report and swaps the mutator set).
  fun pitestExec(
      reportSubdir: String,
      mutatorsSource: Provider<String>,
      withHistory: Boolean
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
    val targetClassesArg = suite.targetClasses.map { "--targetClasses=" + it.joinToString(",") }
    // a map lambda returning null leaves the provider absent, dropping the argument
    val excludedClassesArg = suite.excludedClasses.map { excluded ->
      if (excluded.isEmpty()) null else "--excludedClasses=" + excluded.joinToString(",")
    }
    val targetTestsArg = suite.targetTests.map { "--targetTests=$it" }
    val mutatorsArg = mutatorsSource.map { "--mutators=$it" }
    val threadsArg = suite.threads.map { "--threads=$it" }
    val sourceDirsArg = "--sourceDirs=" + layout.projectDirectory.dir("src/main/java").asFile.absolutePath
    val reportDirArg = "--reportDir=" + layout.buildDirectory.dir("reports/pitest/$reportSubdir").get().asFile.absolutePath
    // Incremental analysis: one rolling history file per suite, deliberately outside
    // build/ so 'clean' does not erase the accumulated results, and git-ignored
    // because it is machine-local state. Input and output are the same file; on the
    // first run the input does not exist yet and PIT starts fresh. The lifecycle line
    // keeps reuse honest — with history active a fast run is expected, so the log
    // must say why, and the pre-release gate re-earns its numbers with
    // '-PnoMutationHistory'.
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
    // A zero-fire trial is a result, not a failure: PIT exits non-zero when the mutator
    // set generates nothing, and the aggregate reads a missing report as zero fired.
    isIgnoreExitValue = true
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
    pitestExec("$suiteName-trial", trialMutatorsProperty, withHistory = false).invoke(this)
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
          |reason below. Line numbers are part of the baseline key, so edits to a
          |mutated file shift entries — the verify task classifies each new row
          |(`shifted` vs `newly covered` vs unexplained) and prints a churn tally;
          |refresh only when nothing is newly covered and nothing is unexplained,
          |and confirm the pairings first. A newly covered row is triage, not churn.
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
