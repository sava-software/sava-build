import software.sava.build.hardening.BaselineFiles
import software.sava.build.hardening.BaselineNotes
import software.sava.build.hardening.ExclusionAudit
import software.sava.build.hardening.HardeningAdvisoryLog
import software.sava.build.hardening.HardeningExtension
import software.sava.build.hardening.HardeningTemplateDigest
import software.sava.build.hardening.HardeningToolDefaults
import software.sava.build.hardening.MutatorAdvice
import software.sava.build.hardening.TimeoutAudit
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
    // outputs are recompiled from source instead. Read the *configured* source-set
    // classpath, never the live 'compileTestJava' task's property: the whitebox JPMS
    // test plugin rewrites that task's classpath while the task executes, so reading
    // it here hands this recompile an emptied classpath on exactly the runs where
    // compileTestJava had work to do, while an up-to-date run leaves it intact — a
    // first-run-only failure that vanishes on retry (casebook: the recompile that
    // only failed when another compile ran).
    val ownBuildDir = layout.buildDirectory.get().asFile.absolutePath + File.separator
    classpath = sourceSets.test.get().compileClasspath.filter { !it.absolutePath.startsWith(ownBuildDir) }
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

// End-of-build summary for the verify tasks' advisory findings. The advisories never
// fail the build by design, but across a full gate a warning from the third of a dozen
// suites sits hundreds of lines above the last output — and the gate is the only place
// these checks run (CI's 'check' has no mutation suites). One service per build, shared
// across projects, so the summary prints once no matter how many modules ran suites.
val hardeningAdvisoryLog = gradle.sharedServices.registerIfAbsent(
    "hardeningAdvisoryLog", HardeningAdvisoryLog::class
) {}

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
    // sorted multisets per key rather than single values. Converge deliberately KEEPS
    // the line in its key while the baseline and modeCompare dropped it: both rounds
    // run identical code, so lines cannot churn here, and the finer key localizes a
    // flip to the exact mutant instead of a sibling group.
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
    // Line-less keys, like the baseline itself: the two modes ran the same code, so
    // per-key status multisets compare cleanly without lines, and an insurance row
    // written here is a row the verify's comparison must recognize.
    fun statuses(csv: File): Map<String, List<String>> = csv.readLines()
        .mapNotNull { line ->
          val parts = line.split(',')
          if (parts.size < 6) null
          else listOf(parts[1], parts[3], parts[2].substringAfterLast('.')).joinToString(",") to parts[5]
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
      // Baseline rows parsed as an ordered LIST (BaselineNotes, both formats): a
      // duplicate key is a sibling mutant, and the set this used to collapse into
      // silently deduped siblings on the union write — a shrink outside prune's rules.
      val baselineFile = baselineDir.file("$suiteName-accepted.csv").asFile
      val acceptedRows: MutableList<BaselineNotes.Row> = if (baselineFile.isFile) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { BaselineNotes.parse(it) }
            .toMutableList()
      } else {
        mutableListOf()
      }
      // Counts, not membership: sibling mutants share the line-less key, and the
      // verify compares multisets — insurance must match its arithmetic or a key
      // "already insured" by one row still fails the next gate verify on the
      // surfaced twin.
      val acceptedCounts: MutableMap<String, Int> = mutableMapOf()
      acceptedRows.forEach { acceptedCounts.merge(it.key, 1, Int::plus) }
      // Observed lines per gated row across every mode's snapshot, so an insurance
      // row lands carrying the '# line' tag the verify's drift advisory reads —
      // an untagged row would put its whole key on the advisory's partial-tag
      // fallback path, weakening the row-level check for its siblings too.
      val rowLines: Map<String, Set<Int>> = if (!unionFlips) emptyMap() else modes.flatMap { label ->
        snapshotRoot.get().asFile.resolve("$label/$suiteName.csv").readLines().mapNotNull { line ->
          val parts = line.split(',')
          if (parts.size < 6 || parts[5] !in gated) null
          else listOf(parts[1], parts[3], parts[2].substringAfterLast('.'), parts[5])
              .joinToString(",") to parts[4].toIntOrNull()
        }
      }.groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.filterNotNull().toSet() }
      var unionedHere = false
      val keys = perMode.values.flatMap { it.keys }.toSortedSet()
      keys.forEach { key ->
        val byMode = perMode.mapValues { (_, m) -> m[key] ?: emptyList() }
        if (byMode.values.distinct().size > 1) {
          // Crossing is a count question, not a presence question, for the same
          // reason insurance is: siblings share the line-less key, so one sibling
          // flipping KILLED -> SURVIVED beside an always-surviving twin changes the
          // gated sub-multiset (1 -> 2) without changing gated presence — and the
          // gate verify fails on the second copy. Presence-based crossing read
          // exactly that as "benign — cannot move the ratchet".
          val crossed = byMode.values
              .map { statusList -> statusList.filter { it in gated }.sorted() }
              .distinct().size > 1
          val detail = modes.joinToString(", ") { label ->
            "$label=${byMode.getValue(label).ifEmpty { listOf("absent") }.joinToString("/")}"
          }
          if (!crossed) {
            benignFlips++
            logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (benign — cannot move the ratchet)")
          } else {
            // Per gated status the baseline needs as many rows as the widest mode
            // observed: a compound condition's siblings flip together under load,
            // and one row cannot insure two of them (casebook: the union write that
            // deduped siblings — the same set-shaped reasoning, in the decision
            // rather than the write).
            val neededRows = byMode.values
                .map { statusList -> statusList.filter { it in gated }.groupingBy { it }.eachCount() }
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, counts) -> counts.max() }
                .toSortedMap()
                .map { (status, needed) -> "$key,$status" to needed }
            when {
              neededRows.all { (row, needed) -> (acceptedCounts[row] ?: 0) >= needed } -> {
                insuredFlips++
                logger.lifecycle("pitestModeCompare '$suiteName': $key — $detail (already insured in the baseline)")
              }
              unionFlips -> {
                neededRows.forEach { (row, needed) ->
                  repeat(needed - (acceptedCounts[row] ?: 0)) {
                    acceptedCounts.merge(row, 1, Int::plus)
                    acceptedRows.add(
                        BaselineNotes.Row(row, "# flip insurance ($detail)", rowLines[row].orEmpty().sorted()))
                    unionedNow.add("$suiteName: $row")
                  }
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
        // every pre-existing row rewritten verbatim (duplicates included), the added
        // insurance rows appended in key order
        BaselineFiles.writeAtomically(
            baselineFile,
            acceptedRows.sortedBy { it.key }.joinToString("\n", postfix = "\n") { BaselineNotes.render(it) })
      }
      // HARDENING.md's sweep: accepted rows unkilled in *no* snapshotted mode are
      // widening the gate for nothing. Report only — removal is a judgment call, and
      // insurance that outlived its cause has a casebook entry of its own.
      val unkilledAnywhere = perMode.values.flatMap { modeStatuses ->
        modeStatuses.flatMap { (key, statusList) -> statusList.filter { it in gated }.map { "$key,$it" } }
      }.toSet()
      acceptedRows.filter { it.key !in unkilledAnywhere }.sortedBy { it.key }.forEach { row ->
        deadRows.add("$suiteName: ${BaselineNotes.render(row.key, row.note, emptyList())}")
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

// Format-only baseline migration: parse each suite's accepted baseline and
// re-render it in the current row format — legacy five-field rows become
// line-less keys with '# line' tags. No report, no PIT run, no stamping:
// identity is preserved by construction (parse/render round-trips the key,
// note and recorded lines), so this cannot change what any verify compares —
// it only respells the record. The refresh flags migrate too, but they need a
// green mutation run, and update needs a *solo* run or it drops flip-insurance
// rows reading TIMED_OUT under load; this task removes that hazard from fleet
// migration entirely. Comment and blank lines pass through verbatim.
val migrateMutationBaselines = tasks.register("migrateMutationBaselines") {
  group = "verification"
  description = "Re-renders every suite's accepted baseline in the current line-less row format; needs no mutation run."
  val names = convergeSuiteNames
  val baselineDir = layout.projectDirectory.dir("config/pitest")
  doLast {
    names.forEach { suiteName ->
      val file = baselineDir.file("$suiteName-accepted.csv").asFile
      if (!file.isFile) {
        return@forEach
      }
      val original = file.readText()
      var migrated = 0
      val rendered = original.split("\n").map { line ->
        if (line.isBlank() || line.trimStart().startsWith("#")) {
          line
        } else {
          val out = BaselineNotes.render(BaselineNotes.parse(line))
          if (out != line) migrated++
          out
        }
      }
      // reassemble exactly (split preserves a trailing empty segment for the
      // final newline), so an already-current file is byte-identical and skipped
      val content = rendered.joinToString("\n")
      if (content == original) {
        logger.lifecycle("pitest baseline '$suiteName': already in the current format")
      } else {
        BaselineFiles.writeAtomically(file, content)
        logger.lifecycle(
            "pitest baseline '$suiteName': migrated $migrated row(s) to the line-less format")
      }
    }
  }
}

// Registered fuzz harnesses live inside the packages they exercise, so a
// package-wildcard suite would otherwise mutate the harness itself. Harness
// mutants are categorically noise — a mutated harness weakens the fuzzer, it can
// never be product risk — so every registered target's class (plus its nested
// classes; lambdas compile into the class itself) is excluded from every suite in
// the module automatically, closing the silent gap when a suite registration
// predates the module's first harness and carries no hand-written '*Fuzz*' row.
val fuzzHarnessExcludes = objects.listProperty<String>()
hardening.fuzz.all {
  // orElse keeps a misconfigured target's blast radius local: targetClass has no
  // convention, and an absent value propagated through the zip would drop the
  // whole --excludedClasses argument — taking every suite's own *Test* exclusions
  // with it, so PIT would mutate the test classes and read as a debt explosion.
  // The target's own fuzz task still fails at execution, which is where the
  // mistake belongs.
  fuzzHarnessExcludes.addAll(targetClass.map { listOf(it, "$it\$*") }.orElse(emptyList()))
}

// A corpus that replays in `check` but whose target never joins the weekly soak
// reads as covered while exploring nothing new — the same silent-gap class as a
// suite registration missing its harness exclusion. If the repo carries a fuzz
// workflow, every registered target's task must appear in it; without one the
// check stays quiet (adopting the soak is the repo's call, and HARDENING.md's
// "The weekly soak" section is the nudge).
val fuzzTargetNames = objects.setProperty<String>()
hardening.fuzz.all {
  fuzzTargetNames.add(name)
}
val fuzzWorkflowInSync = tasks.register("fuzzWorkflowInSync") {
  group = "verification"
  description = "Fails when a weekly fuzz workflow exists but names a registered fuzz target nowhere."
  val workflowFile = rootProject.layout.projectDirectory.file(".github/workflows/fuzz.yml").asFile
  val names = fuzzTargetNames
  val taskPathPrefix = path.substringBeforeLast(':')
  inputs.files(workflowFile)
  doLast {
    if (!workflowFile.isFile) {
      return@doLast
    }
    val text = workflowFile.readText()
    // word-boundary, not substring: 'fuzzWs' must not pass on 'fuzzWsFraming'. A
    // mention inside a yaml comment satisfies the check deliberately — the escape
    // hatch for a target kept out of the soak on purpose, whose comment then holds
    // the reason next to the task list it is absent from.
    val missing = names.get().sorted()
        .map { "fuzz" + it.replaceFirstChar(Char::uppercase) }
        .filterNot { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(text) }
    if (missing.isNotEmpty()) {
      throw GradleException(
          "fuzzWorkflowInSync: $workflowFile runs a weekly soak but names ${missing.size} registered " +
              "fuzz target(s) nowhere — its corpus replays in check, but the target itself is never " +
              "fuzzed, which reads as covered while exploring nothing. Add to the soak's gradle " +
              "invocation:\n" + missing.joinToString("\n") { "  $taskPathPrefix:$it" } +
              "\nor, to keep a target out of the soak deliberately, name it in a yaml comment with " +
              "the reason."
      )
    }
  }
}
tasks.named("check") { dependsOn(fuzzWorkflowInSync) }

// Every suite's mutation scope, keyed by suite name: each suite's exclusion
// audit subtracts the classes its siblings actually mutate, so the targeting
// policy's "owned by another suite" handoffs read as ownership rather than
// swallowed production classes *(casebook: the partition the audit called a
// hole)*. Values are providers; by execution time every suite is registered.
val suiteTargetGlobs = objects.mapProperty<String, List<String>>()
val suiteExcludedGlobs = objects.mapProperty<String, List<String>>()

hardening.mutation.all {
  val suite = this
  suite.mutators.convention("STRONGER")
  suite.threads.convention(4)
  // PIT's own defaults; see MutationSuite.timeoutFactor for tuning guidance
  suite.timeoutFactor.convention(1.25)
  suite.timeoutConst.convention(4000L)
  suite.excludedClasses.convention(emptyList())
  // the registration's own exclusions plus every registered fuzz harness; feeds
  // both the PIT argument and the mutator-blindness scan so the two cannot drift
  val allExcludedClasses = suite.excludedClasses.zip(fuzzHarnessExcludes) { excluded, harnesses ->
    (excluded + harnesses).distinct()
  }
  suiteTargetGlobs.put(suite.name, suite.targetClasses)
  suiteExcludedGlobs.put(suite.name, allExcludedClasses)

  // Mutation ratchet: after each 'pitest<Name>' run, diff the unkilled mutants
  // (SURVIVED and NO_COVERAGE) against the checked-in baseline at
  // 'config/pitest/<name>-accepted.csv' and fail on anything new. A fresh
  // mutant must be killed with a test or knowingly accepted by re-running with
  // '-PupdateMutationBaseline' and documenting the reason (see HARDENING.md).
  val pitestTaskName = "pitest" + suite.name.replaceFirstChar(Char::uppercase)
  val suiteName = suite.name
  // computed once and shared by every advisory-recording task in the suite: the
  // end-of-build summary groups findings by this string, so two drifting copies
  // would split one suite's findings across two scope headings
  val suiteAdvisoryScope = (if (project.path == ":") "" else "${project.path} ") + "pitest '$suiteName'"
  val verify = tasks.register("${pitestTaskName}Verify") {
    group = "verification"
    description = "Fails when the '$suiteName' PIT run left unkilled mutants missing from config/pitest/$suiteName-accepted.csv."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val xmlProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.xml")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val timeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val update = providers.gradleProperty("updateMutationBaseline").isPresent
    val union = providers.gradleProperty("unionMutationBaseline").isPresent
    val prune = providers.gradleProperty("pruneMutationBaseline").isPresent
    val listUnkilled = providers.gradleProperty("listUnkilled").isPresent
    val initTimeoutAudit = providers.gradleProperty("initTimeoutAudit").isPresent
    val strictTimeoutAudit = providers.gradleProperty("strictTimeoutAudit").isPresent
    val statusStashFile = layout.projectDirectory.file(".pitest-history/$suiteName.statuses").asFile
    val timeoutQuietFile = layout.projectDirectory.file(".pitest-history/$suiteName.timeout-quiet").asFile
    val toolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val pitToolVersion = hardening.pitestVersion
    // captured locally so the doLast lambda does not hold the script instance
    val historyAssisted = mutationHistory
    val advisoryLog = hardeningAdvisoryLog
    val advisoryScope = suiteAdvisoryScope
    usesService(advisoryLog)
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
      // The mutant population is a function of PIT itself (whose default version rides
      // plugin bumps via Dependabot), so the baseline record is only comparable to runs
      // from the version that wrote it. The recorded version lives in
      // config/pitest/<suite>-pitest-version — per suite, because the record it certifies
      // is per-suite: one shared file would lift every suite's refusal at the first
      // refresh, silently certifying the rest against a version that never wrote them.
      // A mismatch warns on a checking run and refuses a writing one — reading a
      // possibly-divergent result is a judgment call, writing the record with one is
      // not. No file means a record predating this check, adopted when the next
      // baseline write *succeeds*: the stamp lands with the write it describes, never
      // ahead of it, so a refresh that dies mid-path cannot leave a stamp vouching
      // for a record it never rewrote. -PinitTimeoutAudit is refused across a bump
      // like the baseline flags — the timeout population is just as version-dependent
      // — but never stamps: it writes the timeout set, not the baseline, and its
      // stamp would silently vouch for a baseline some older PIT wrote.
      val currentPit = pitToolVersion.get()
      val recordedPit = toolVersionFile.takeIf { it.isFile }?.readText()?.trim()
      val writingRecord = update || union || prune || initTimeoutAudit
      if (recordedPit != null && recordedPit != currentPit) {
        if (writingRecord) {
          throw GradleException(
              "pitest '$suiteName': the baseline record was written by PIT $recordedPit but this run used " +
                  "PIT $currentPit — refusing to rewrite the record across a tool bump, whose population " +
                  "churn would be indistinguishable from code churn. To bump deliberately: set " +
                  "config/pitest/$suiteName-pitest-version to $currentPit, then refresh the suite and read the " +
                  "resulting churn as a real population diff, not noise."
          )
        }
        val versionWarning = "pitest '$suiteName': baseline record written by PIT $recordedPit, this run " +
            "used PIT $currentPit — population differences may be the tool, not the code, and the " +
            "record-writing flags refuse until config/pitest/$suiteName-pitest-version is updated deliberately"
        logger.warn(versionWarning)
        advisoryLog.get().record(advisoryScope, "baseline written by PIT $recordedPit, ran $currentPit")
      }
      // Called by each baseline-writing path at its successful end. A no-op rewrite
      // (prune dropped nothing, union added nothing) stamps too: the path just
      // verified the committed record against a report this PIT produced, which is
      // the comparability the stamp asserts.
      fun stampToolVersion() {
        if (recordedPit == null) {
          BaselineFiles.writeAtomically(toolVersionFile, currentPit + "\n")
        }
      }
      // The delete-side counterpart, for a refresh path that ends with no baseline
      // file: unless an audited timeout set remains for the stamp to certify, it is
      // retired with the record — "no record and an empty record must read the same
      // way" extends to the stamp, and an orphan stamp would refuse a future first
      // refresh across a PIT bump citing a baseline that no longer exists.
      fun stampOrRetireToolVersion() {
        if (timeoutsFile.isFile) {
          stampToolVersion()
        } else if (toolVersionFile.isFile) {
          toolVersionFile.delete()
          logger.lifecycle(
              "pitest baseline '$suiteName': ${toolVersionFile.name} removed with the record it certified")
        }
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
      // needing exactly that. The XML report carries it; keyed like the baseline rows
      // (line-less), with each mutant's line folded into the description text, since
      // the key no longer carries it.
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
              text("mutatedClass"), text("mutatedMethod"),
              text("mutator").substringAfterLast('.')
          ).joinToString(",")
          val description = "line ${text("lineNumber")}: ${text("description")}"
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
      // identical (class, method, mutator, status) keys — one per operand, branch
      // direction, or occurrence in the method. Collapsing them to a set once let a
      // killed sibling regress to SURVIVED invisibly, absorbed by its already-accepted
      // twin's row. All comparisons below are multiset comparisons for the same reason.
      //
      // The key is line-less on purpose — line numbers are metadata, not identity
      // (the audited-timeout convention, extended to the baseline): editing above a
      // mutated method can never churn the ratchet, at the documented price that a
      // new mutant of an already-accepted key is visible only as a count change.
      // Observed lines ride alongside for line tags, sibling hints, and the
      // line-drift advisory.
      val currentWithLines = rows.mapNotNull { parts ->
        if (parts[5] !in gated) {
          null
        } else {
          "${parts[1]},${parts[3]},${parts[2].substringAfterLast('.')},${parts[5]}" to parts[4]
        }
      }
      val current = currentWithLines.map { it.first }.sorted()
      val currentLines: Map<String, List<String>> =
          currentWithLines.groupBy({ it.first }, { it.second })
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
        // the row's key is line-less, but the survivor's own observed lines are in the
        // report — the hint stays per-line, since that is where the disambiguation lives
        if (!row.endsWith(",SURVIVED")) return ""
        val parts = row.split(',')
        val siblings = currentLines[row].orEmpty().distinct().flatMap { line ->
          detectedSiblings["${parts[0]},${parts[1]},$line,${mutatorFamily(parts[2])}"].orEmpty()
        }
        if (siblings.isEmpty()) return ""
        return " [detected sibling at this line: ${siblings.distinct().joinToString("; ")}]"
      }
      if (listUnkilled && current.isNotEmpty()) {
        // the sibling hint does the coordinate disambiguation the CSV cannot: for a
        // survivor at a coordinate whose twin was detected, it names the twin's killer,
        // so the triager reads the surviving direction off the build output instead of
        // reconstructing it from mutations.xml (casebook: the sibling guessed wrong
        // three times)
        logger.lifecycle(
            "pitest '$suiteName' unkilled:\n" +
                current.joinToString("\n") { row -> "  $row${describe(row)}${siblingHint(row)}" }
        )
      }

      // A scoped run mutated a hand-picked subset: its report is an iteration aid,
      // not evidence about the suite. List what is still unkilled inside the scope
      // and stop — no ratchet, no status stash, and certainly no baseline writes.
      val scopedMarkerFile = csv.parentFile.resolve(".scoped")
      if (scopedMarkerFile.isFile) {
        val scope = scopedMarkerFile.readText().trim()
        // prune and the timeout-audit seed included: the early return below already
        // keeps them from touching anything, but silently no-opping a requested
        // refresh (or seeding an audited set from a hand-picked subset's timeouts)
        // reads as work that happened — refuse them the same way as update and union.
        if (update || union || prune || initTimeoutAudit) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot refresh the baseline or seed the timeout audit. " +
                  "Re-run $pitestTaskName without -PmutateOnly first."
          )
        }
        // Certifying flags are refused for the same reason in the other direction:
        // the checks they strengthen are skipped entirely on a scoped report, so a
        // green run would read as a certification of the suite when nothing was
        // certified at all. (-PnoDriftTolerance used to sit beside this flag; the
        // line-less key retired it — there is no drift tolerance left to disable.)
        if (strictTimeoutAudit) {
          throw GradleException(
              "pitest '$suiteName': the report was produced with -PmutateOnly=$scope — a partial " +
                  "population cannot be certified, and the certifying checks are skipped on a scoped " +
                  "report. Re-run $pitestTaskName without -PmutateOnly first."
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
      // label for seeded debt; refreshes seed it on every new row) and a trailing
      // '# line' tag (metadata for triage and the line-drift advisory, never identity).
      // Notes are stripped for comparison, preserved across the refresh flags, and
      // counted per label — so triage state lives on the row it describes and stays a
      // number the build reports, not prose that drifts. Rows are parsed as an ordered
      // LIST of (key, note, lines): duplicate keys are sibling mutants and each keeps
      // its own note, which a note map keyed by row text used to collapse.
      val acceptedRows: List<BaselineNotes.Row> = if (baselineFile.exists()) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { BaselineNotes.parse(it) }
      } else {
        emptyList()
      }
      val accepted: List<String> = acceptedRows.map { it.key }
      // The line-less 'class,method,mutator' coordinate — the audited-timeout key —
      // and the set of them that read TIMED_OUT this run. Shared rather than
      // recomputed per call site: prune keeps these rows and the verify's stale-entry
      // hint promises exactly that ("prune keeps them"), so the two must decide
      // membership identically — a promise that holds only by coincidence when each
      // site carries its own copy of the key shape.
      fun mutantCoordinate(parts: List<String>) =
          "${parts[1]},${parts[3]},${parts[2].substringAfterLast('.')}"
      val timedOutCoordinatesNow = rows.filter { it[5] == "TIMED_OUT" }.map(::mutantCoordinate).toSet()
      // Per-label breakdown so triage state is a number the build prints (BaselineNotes
      // owns the label semantics: carry/flip parentheticals stripped, unlabeled rows —
      // which predate seeding — named rather than folded into a bucket).
      BaselineNotes.summarize(acceptedRows.mapNotNull { it.note }, acceptedRows.count { it.note == null })
          ?.let { logger.lifecycle("pitest baseline '$suiteName': ${accepted.size} rows — $it") }
      // A family label is a pointer to its argument in config/pitest/README.md (the rule
      // and its message live in BaselineNotes, so this and 'Debt' resolve labels the same
      // way). Warned rather than failed, mirroring the scaffolding check: the gap may
      // predate this check, and a fresh '-PunionModeFlips' row legitimately lands before
      // its README criterion is written.
      val undocumentedLabels = BaselineNotes.undocumentedLabels(acceptedRows.mapNotNull { it.note }) {
        readmeFile.takeIf { it.isFile }?.readText() ?: ""
      }
      if (undocumentedLabels.isNotEmpty()) {
        logger.warn(BaselineNotes.undocumentedLabelWarning(suiteName, undocumentedLabels))
        advisoryLog.get().record(advisoryScope, "${undocumentedLabels.size} undocumented family label(s)")
      }

      // Timed-out drift vs the previous run. TIMED_OUT counts as detected, but the
      // benign flavour (KILLED<->TIMED_OUT under load) and the dangerous one
      // (SURVIVED->TIMED_OUT: a mutant nobody killed now reads as detected purely
      // because its tests ran slowly) look identical in a single report. Comparing
      // against the last run's statuses names each newcomer's origin.
      //
      // Compared as per-coordinate *counts*, never as sets of coordinates. The
      // coordinate is line-less, so one key routinely holds several mutants at once —
      // an accepted survivor and an audited timeout among them. Set logic asks "is
      // this key timed out now, and did it hold a survivor before", which such a key
      // answers yes to on every run, including the ones where nothing moved at all;
      // a reviewer-stop that fires forever is one nobody reads. A flip is a key whose
      // timeout count rose *and* whose survivor count fell.
      val statusStash = statusStashFile
      run {
        val coordinate = ::mutantCoordinate
        fun tally(pairs: List<Pair<String, String>>): Map<String, Map<String, Int>> = pairs
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, statuses) -> statuses.groupingBy { it }.eachCount() }
        val previous = if (statusStash.isFile) {
          tally(statusStash.readLines().mapNotNull { line ->
            val sep = line.lastIndexOf(',')
            if (sep < 0) null else line.substring(0, sep) to line.substring(sep + 1)
          })
        } else {
          emptyMap()
        }
        val stashRows = rows.filter { it[5] == "TIMED_OUT" || it[5] == "SURVIVED" }
        val current = tally(stashRows.map { coordinate(it) to it[5] })
        if (previous.isNotEmpty()) {
          fun delta(key: String, status: String) =
              (current[key]?.get(status) ?: 0) - (previous[key]?.get(status) ?: 0)
          val fromSurvived = mutableListOf<String>()
          var newlyTimedOut = 0
          var resolved = 0
          for (key in previous.keys + current.keys) {
            val timedOut = delta(key, "TIMED_OUT")
            when {
              // gained a timeout and lost a survivor: the one flavour that can hide
              // an unkilled mutant behind the watchdog
              timedOut > 0 && delta(key, "SURVIVED") < 0 -> fromSurvived += key
              // gained timeouts with its survivors intact — the extra timeouts came
              // from mutants that were already detected (KILLED<->TIMED_OUT); summed
              // so the drift line counts mutants, not keys
              timedOut > 0 -> newlyTimedOut += timedOut
              timedOut < 0 -> resolved -= timedOut
            }
          }
          if (fromSurvived.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${fromSurvived.size} coordinate(s) flipped SURVIVED -> TIMED_OUT — " +
                    "a mutant nobody killed now reads as detected, likely load-slowed tests rather than " +
                    "new kills; do not refresh them out:\n" +
                    fromSurvived.sorted().joinToString("\n") { "  $it" }
            )
            advisoryLog.get().record(advisoryScope, "${fromSurvived.size} SURVIVED -> TIMED_OUT flip(s)")
          }
          if (newlyTimedOut > 0 || resolved > 0) {
            logger.lifecycle(
                "pitest '$suiteName': timed-out drift vs previous run — " +
                    "$newlyTimedOut newly timed out (previously detected), $resolved no longer; load-dependent"
            )
          }
        }
        statusStash.parentFile.mkdirs()
        statusStash.writeText(
            stashRows.joinToString("\n", postfix = "\n") { "${coordinate(it)},${it[5]}" }
        )
      }

      // The refresh flavours answer different questions and are mutually exclusive —
      // the audit seed included, which writes a file the same way the baseline
      // flavours do. Checked before any of them writes (the seed below is the first),
      // so a refused combination leaves nothing half done.
      if (listOf(update, union, prune, initTimeoutAudit).count { it } > 1) {
        throw GradleException(
            "pass at most one of -PupdateMutationBaseline, -PunionMutationBaseline, " +
                "-PpruneMutationBaseline, -PinitTimeoutAudit — they answer different questions (see HARDENING.md)."
        )
      }

      // The audited set. For a timed-out mutant the watchdog observed slowness, not
      // wrongness — the ratchet cannot see a weakened covering assertion behind it,
      // so "N timed out (load-dependent)" in the summary must be an audited
      // membership rather than a count. 'config/pitest/<suite>-timeouts.csv' holds
      // one 'class,method,mutator' per row ('#' comments allowed) — line-less so
      // drift cannot churn membership; the suite README carries each member's line
      // and structural cause. Warning-level by design: load can time out any mutant
      // on any run and both flavours are still detection, but an unaudited newcomer
      // is a change to stop on, not noise to absorb. Absent file, absent check —
      // adoption is per-repo.
      fun auditKey(parts: List<String>) = "${parts[1]},${parts[3]},${parts[2].substringAfterLast('.')}"
      val timedOutByAuditKey = rows.filter { it[5] == "TIMED_OUT" }.groupBy(::auditKey)
      if (initTimeoutAudit) {
        // Seeds the mechanical half of adoption — the membership rows — from this
        // run's report, mirroring '-PupdateMutationBaseline' seeding '# untriaged':
        // the tool writes what it can derive, the warnings that follow drive the half
        // that needs a person (the causes). Refused once the file exists: a second
        // seed would be a rewrite, and the audit's whole point is that membership
        // changes one reviewed row at a time.
        if (timeoutsFile.isFile) {
          throw GradleException(
              "pitest '$suiteName': ${timeoutsFile.name} already exists — -PinitTimeoutAudit seeds a new " +
                  "audited set only. For a new timeout, paste the row the verify prints and write its " +
                  "cause in config/pitest/README.md."
          )
        }
        // Also refused with nothing timed out: an empty file would activate the audit
        // while telling its adopter to write causes for zero members, and the flag is
        // only ever pointed at by a summary that reported timeouts — a run where they
        // vanished is the load-dependence the line-less key exists to absorb, not a
        // population to certify. Arming a never-timed-out suite is a different intent
        // with a different mechanism (a hand-committed comments-only file), so the
        // refusal names it instead of reading as "empty sets are forbidden".
        if (timedOutByAuditKey.isEmpty()) {
          throw GradleException(
              "pitest '$suiteName': no timed-out mutants in this run's report — nothing to seed. " +
                  "Timeouts are load-dependent; re-run $pitestTaskName under the conditions whose " +
                  "summary reported them (see HARDENING.md, the audited-timeout bullet). To merely arm " +
                  "the audit for a suite that has never timed out, commit ${timeoutsFile.name} with " +
                  "only '#' comment lines — the suite's first timeout then warns as the reviewer-stop " +
                  "it is."
          )
        }
        val seeded = timedOutByAuditKey.keys.sorted().joinToString("\n") { key ->
          val lines = timedOutByAuditKey.getValue(key).map { it[4] }.distinct()
          "$key # line${if (lines.size > 1) "s" else ""} ${lines.joinToString(", ")}"
        }
        BaselineFiles.writeAtomically(
            timeoutsFile,
            "# Audited TIMED_OUT set for the '$suiteName' suite (HARDENING.md, the audited-timeout\n" +
                "# bullet): one line-less 'class,method,mutator' member per row. A timeout detects\n" +
                "# slowness, not wrongness, so the ratchet cannot see a weakened covering assertion\n" +
                "# behind one; each member's structural cause belongs in config/pitest/README.md.\n" +
                seeded + "\n"
        )
        logger.lifecycle(
            "pitest '$suiteName': seeded ${timedOutByAuditKey.size} audited-timeout member(s) into " +
                "${timeoutsFile.name} — now write each member's structural cause in config/pitest/README.md"
        )
      }
      if (timeoutsFile.isFile) {
        // Normalize per field, not just per line: 'Codec, encode, MathMutator' is the
        // spacing a person writes for readability, and against a key built without
        // spaces it would match nothing — earning a permanent 'not in the audited set'
        // warning AND a 'matches no mutant' notice, with nothing on screen naming the
        // spaces as the cause. A membership file the tool silently disagrees with is
        // worse than no membership file. The same doctrine names a row with the wrong
        // field count as what it is: diagnosed as malformed and excluded from every
        // other check, instead of surfacing as a baffling 'matches no mutant' that
        // sends the reader hunting for a moved mutant. Parsing and both static
        // warnings live in TimeoutAudit, shared with 'Debt', so the two tasks can
        // never disagree about what a membership file says.
        val membership = TimeoutAudit.parse(timeoutsFile.readLines())
        val malformed = membership.malformed
        // Recorded as an advisory only when it stays one: under -PstrictTimeoutAudit
        // this finding (and the unaudited newcomer below) becomes the failure itself,
        // and the summary's "none failed the build" must stay true.
        TimeoutAudit.malformedWarning(suiteName, timeoutsFile.name, malformed)?.let {
          logger.warn(it)
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${malformed.size} malformed audit row(s)")
          }
        }
        val members = membership.members
        val unaudited = rows.filter { it[5] == "TIMED_OUT" && auditKey(it) !in members }
        if (unaudited.isNotEmpty()) {
          // rows print paste-ready: the membership key verbatim, the line riding in a
          // '#' comment — pasting the printed row into the set must satisfy the check,
          // never trip the stale-member notice
          logger.warn(
              "pitest '$suiteName': ${unaudited.size} timed-out mutant(s) not in the audited set " +
                  "(${timeoutsFile.name}) — a new timeout hides a weakened-assertion blind spot " +
                  "behind \"detected\"; identify the structural cause (the removed loop exit, the " +
                  "reversed cursor), add the row below to the set and the cause to " +
                  "config/pitest/README.md:\n" +
                  unaudited.joinToString("\n") { "  ${auditKey(it)} # line ${it[4]}" }
          )
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${unaudited.size} unaudited timeout(s)")
          }
        }
        val allKeys = rows.map(::auditKey).toSet()
        val staleMembers = members.filterNot { it in allKeys }
        if (staleMembers.isNotEmpty()) {
          // Warn-level like the other membership findings: 'retire or fix' is a
          // reviewer-stop exactly as much as a missing cause, and warn is what feeds
          // the end-of-build advisory summary.
          logger.warn(
              "pitest '$suiteName': ${staleMembers.size} audited-timeout row(s) match no mutant in " +
                  "this run's report — the code moved or the mutator set changed; retire or fix:\n" +
                  staleMembers.sorted().joinToString("\n") { "  $it" }
          )
          advisoryLog.get().record(advisoryScope, "${staleMembers.size} stale audit row(s)")
        }
        val liveMembers = members - staleMembers.toSet()
        // The '# line' comment the seed writes (and the paste-ready row carries) is
        // the anchor its README cause argues about, and the doctrine's "re-read the
        // cause when that code changes" was purely social: the key going stale only
        // notices a *method* disappearing, never the code moving within one. The
        // report holds the observed side of that comparison, so make it: a member
        // whose observed timeout lines all differ from its recorded ones is exactly
        // the cause-vs-code divergence a reviewer should re-read. Disjointness, not
        // inequality — a new sibling line next to a recorded one is the line-less
        // key's stated no-warning resolution (TimeoutAudit.lineDrift).
        val drifted = TimeoutAudit.lineDrift(
            membership.recordedLines,
            timedOutByAuditKey.filterKeys { it in members }
                .mapValues { (_, timedOut) -> timedOut.mapNotNull { it[4].toIntOrNull() }.toSet() }
        )
        if (drifted.isNotEmpty()) {
          logger.warn(TimeoutAudit.lineDriftWarning(suiteName, timeoutsFile.name, drifted))
          advisoryLog.get().record(advisoryScope, "${drifted.size} line-drifted audit row(s)")
        }
        // The check the set was missing: membership was validated against ALL mutants,
        // so a member whose mutants exist but never time out — a key pasted from the
        // wrong report, or a timeout the tests since learned to kill — was accepted
        // forever. A single-run "did not time out" is exactly the KILLED<->TIMED_OUT
        // load flip the line-less key exists to absorb, so the signal is consecutive
        // quiet runs: the counter persists in .pitest-history/ (machine-local, like
        // the status stash) and resets whenever the member times out again. Three
        // quiet runs mirrors the flip-family retirement criterion ("3 quiet
        // modeCompare cycles"); a member that only times out under gate load will be
        // reset by gate runs and nagged only during long solo streaks — the notice
        // says so rather than presuming retirement.
        run {
          // The counter advances per fresh report, not per invocation: the verify
          // runs standalone against the existing report ('finalizedBy', not a
          // dependency), so re-running it to exercise its checks would otherwise
          // manufacture quiet evidence from a single mutation run. The stash's first
          // line fingerprints the report; on a match the stored counts replay
          // untouched. Timestamp over content hash on purpose — a fresh PIT run with
          // identical results IS a fresh quiet observation.
          val reportFingerprint = "# report ${csv.lastModified()},${csv.length()}"
          val previousLines = if (timeoutQuietFile.isFile) timeoutQuietFile.readLines() else emptyList()
          val sameReport = previousLines.firstOrNull() == reportFingerprint
          val previousQuiet = previousLines.filterNot { it.startsWith("#") }.mapNotNull { line ->
            val sep = line.lastIndexOf(',')
            if (sep < 0) null else line.substring(0, sep) to (line.substring(sep + 1).toIntOrNull() ?: 0)
          }.toMap()
          // Live members only, which also means a stale member's count is dropped,
          // not frozen: staleness says the code moved (or the mutator set changed),
          // and quiet evidence about the old method body must be re-measured from
          // zero if the mutant returns, not carried across the change. The cost is
          // two extra runs of patience; the alternative is a retirement nudge argued
          // from code that no longer exists.
          val quietRuns = liveMembers.associateWith { member ->
            when {
              member in timedOutByAuditKey -> 0
              sameReport -> previousQuiet[member] ?: 0
              else -> (previousQuiet[member] ?: 0) + 1
            }
          }
          BaselineFiles.writeAtomically(
              timeoutQuietFile,
              quietRuns.entries.sortedBy { it.key }
                  .joinToString("\n", prefix = "$reportFingerprint\n", postfix = "\n") { "${it.key},${it.value}" }
          )
          // Derived from the counts, so a same-report re-run reprints it — like every
          // other audit advisory, which are all recomputed from the report rather
          // than remembering they already printed. Warn-level like its siblings
          // (stale rows, missing causes): the retirement criterion family is one
          // tier, and warn is what feeds the end-of-build advisory summary — a gate
          // runs the quiet notice as many hundred lines up-screen as any other.
          val settled = quietRuns.filterValues { it >= 3 }
          if (settled.isNotEmpty()) {
            logger.warn(
                "pitest '$suiteName': ${settled.size} audited-timeout member(s) have not timed out in 3+ " +
                    "consecutive mutation runs — if a member only times out under gate load this is normal " +
                    "on solo streaks; otherwise its tests now detect the mutant outright, and the member " +
                    "(with its README cause) can be retired:\n" +
                    settled.entries.sortedBy { it.key }
                        .joinToString("\n") { "  ${it.key} (quiet for ${it.value} runs)" }
            )
            advisoryLog.get().record(advisoryScope, "${settled.size} quiet audited-timeout member(s)")
          }
        }
        // A member is only half audited without its cause: the row makes the set
        // machine-checked, the README argument is what a reviewer actually reads.
        // Mirrors the family-label rule (a label is a pointer to its README argument)
        // at the same advisory level; the matching rule lives in TimeoutAudit, shared
        // with 'Debt'.
        //
        // Live members only: a stale member is already being told to retire, and
        // asking for the cause of a row that should be deleted is two instructions
        // pulling opposite ways for one row.
        val undocumented = TimeoutAudit.undocumentedCauses(liveMembers) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (undocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(suiteName, undocumented))
          if (!strictTimeoutAudit) {
            advisoryLog.get().record(advisoryScope, "${undocumented.size} audited timeout(s) without a README cause")
          }
        }
        // Opt-in escalation for certifying runs:
        // by default every audit finding is advisory (load can time out any mutant on
        // any run), but on a run whose purpose is certification, an unaudited
        // newcomer, a row the tool cannot parse, or a member whose cause was never
        // written is exactly what the run exists to stop on — the doctrine admits a
        // newcomer only with its cause written, so a cause-less member is an
        // unfinished admission, not hygiene; row-then-cause is a legitimate sequence
        // *between* certifications, not during one. Hygiene findings (stale members,
        // quiet streaks, drifted lines) stay advisory even here.
        if (strictTimeoutAudit && (unaudited.isNotEmpty() || malformed.isNotEmpty() || undocumented.isNotEmpty())) {
          throw GradleException(
              "pitest '$suiteName': -PstrictTimeoutAudit — ${unaudited.size} unaudited timed-out mutant(s), " +
                  "${malformed.size} malformed membership row(s), and ${undocumented.size} audited member(s) " +
                  "without a README cause; see the warnings above. Paste the printed row(s) into " +
                  "${timeoutsFile.name} and write each cause in config/pitest/README.md."
          )
        }
      } else if (timedOutByAuditKey.isNotEmpty()) {
        // A suite carrying timeouts with no audited set is running with the blind
        // spot the audit exists for, and nothing on screen said the feature exists —
        // it was discoverable only by reading HARDENING.md. Advisory nudge normally;
        // under the strict flag an unadopted timeout-carrying suite is an unaudited
        // newcomer by definition.
        val hint =
            "pitest '$suiteName': ${rows.count { it[5] == "TIMED_OUT" }} timed-out mutant(s) and no audited " +
                "set — a timeout detects slowness, not wrongness, so the ratchet cannot see a weakened " +
                "covering assertion behind one. Adopt the audit with -PinitTimeoutAudit (seeds " +
                "config/pitest/${timeoutsFile.name} from this run), then write each member's structural " +
                "cause in config/pitest/README.md."
        if (strictTimeoutAudit) {
          throw GradleException(hint)
        }
        logger.lifecycle(hint)
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
        fun coordinate(key: String) = key.substringBeforeLast(',')
        val timedOutCoordinates = timedOutCoordinatesNow
        val unkilledCoordinates = current.map(::coordinate).toSet()
        val budget = current.groupingBy { it }.eachCount().toMutableMap()
        val kept = mutableListOf<BaselineNotes.Row>()
        val keptUnmatched = mutableListOf<Pair<BaselineNotes.Row, String>>()
        val droppedRows = mutableListOf<BaselineNotes.Row>()
        for (row in acceptedRows) {
          val remaining = budget[row.key] ?: 0
          if (remaining > 0) {
            budget[row.key] = remaining - 1
            kept.add(row)
          } else if (coordinate(row.key) in timedOutCoordinates) {
            kept.add(row)
            keptUnmatched.add(row to "TIMED_OUT this run (load-dependent)")
          } else if (coordinate(row.key) in unkilledCoordinates) {
            kept.add(row)
            keptUnmatched.add(row to "coordinate unkilled at another status (flip pending triage)")
          } else {
            droppedRows.add(row)
          }
        }
        val keptDetail = if (keptUnmatched.isEmpty()) "" else
          "\n  kept ${keptUnmatched.size} unmatched row(s):\n" +
              keptUnmatched.joinToString("\n") { (row, why) -> "  ${BaselineNotes.render(row)} — $why" }
        if (droppedRows.isEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped nothing — every row matches this run$keptDetail")
        } else if (kept.isEmpty()) {
          // every row dropped: remove the file rather than leave a one-newline
          // husk — no record and an empty record must read the same way
          baselineFile.delete()
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped every row since killed — baseline file removed:\n" +
                  droppedRows.joinToString("\n") { row -> "  ${BaselineNotes.render(row)}${describe(row.key)}" }
          )
        } else {
          // kept rows are re-rendered, which also migrates a legacy five-field file to
          // the line-less format (the legacy line field becomes a '# line' tag)
          BaselineFiles.writeAtomically(
              baselineFile, kept.joinToString("\n", postfix = "\n") { BaselineNotes.render(it) })
          logger.lifecycle(
              "pitest baseline '$suiteName': prune dropped ${droppedRows.size} row(s) since killed " +
                  "(baseline now ${kept.size}):\n" +
                  droppedRows.joinToString("\n") { row -> "  ${BaselineNotes.render(row)}${describe(row.key)}" } +
                  keptDetail
          )
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
        return@doLast
      }
      if (update) {
        // Full rewrite from this run's report. A dropped row's '# note' must not
        // vanish silently; with line-less keys only one relationship needs a carry:
        //
        //   status flip — same class,method,mutator, different status (NO_COVERAGE ->
        //                 SURVIVED once a test reaches the method). Carried marked for
        //                 re-reading: a reason written for an unreached mutant is not
        //                 automatically a reason once a test can observe it.
        //
        // Line shifts no longer exist as churn — lines are metadata, so an edit above
        // a mutated method changes nothing here but the '# line' tags, and the
        // shift-pairing, bare-row-pairing and pairing-outlier machinery that used to
        // police that carry is gone with the churn it policed.
        //
        // Within a key, accepted rows are assigned to this run's mutants by LINE
        // AFFINITY first — a pair whose '# line' tag names the mutant's observed line
        // is its row — then by file order. So when a noted sibling was killed, the
        // note that drops is its own, not whichever row came first; without line
        // evidence the assignment is arbitrary, which is the documented same-key
        // blind spot, not a bug to police.
        val pairIdxByKey = HashMap<String, MutableList<Int>>()
        acceptedRows.forEachIndexed { idx, row -> pairIdxByKey.getOrPut(row.key) { mutableListOf() }.add(idx) }
        val chosenIdx = mutableSetOf<Int>()
        // per copy of each key: the observed line (null when unparsable) and the
        // assigned accepted row, filled by the two passes below
        class Copy(val key: String, val line: Int?) {
          var pair: Int? = null
        }
        val copies = currentLines.keys.sorted().flatMap { key ->
          currentLines.getValue(key).map { it.toIntOrNull() }.sortedWith(nullsLast(naturalOrder()))
              .map { Copy(key, it) }
        }
        for (copy in copies) {
          val pairs = pairIdxByKey[copy.key] ?: continue
          val line = copy.line ?: continue
          val hit = pairs.firstOrNull { line in acceptedRows[it].recordedLines }
          if (hit != null) {
            copy.pair = hit
            chosenIdx.add(hit)
            pairs.remove(hit)
          }
        }
        for (copy in copies) {
          if (copy.pair != null) continue
          val pairs = pairIdxByKey[copy.key] ?: continue
          if (pairs.isEmpty()) continue
          val idx = pairs.removeAt(0)
          copy.pair = idx
          chosenIdx.add(idx)
        }
        val droppedIdx = acceptedRows.indices.filter { it !in chosenIdx }
        // note-carrying flip pool: dropped rows that carry a note, each consumed at
        // most once, matched by coordinate (key minus status)
        val flipPool = droppedIdx.filter { acceptedRows[it].note != null }.toMutableList()
        val carriedIdx = mutableSetOf<Int>()
        var flipped = 0
        var seeded = 0
        val written = copies.map { copy ->
          val lineTag = copy.line?.let { listOf(it) } ?: emptyList()
          val match = copy.pair
          if (match != null) {
            return@map BaselineNotes.render(copy.key, acceptedRows[match].note, lineTag)
          }
          val coordinate = copy.key.substringBeforeLast(',')
          val flip = flipPool.firstOrNull { acceptedRows[it].key.substringBeforeLast(',') == coordinate }
          if (flip != null) {
            flipPool.remove(flip)
            carriedIdx.add(flip)
            flipped++
            val from = acceptedRows[flip].key.substringAfterLast(',')
            val to = copy.key.substringAfterLast(',')
            return@map BaselineNotes.render(
                copy.key, "${acceptedRows[flip].note} (carried across $from -> $to)", lineTag)
          }
          // A genuinely new key — or a new sibling mutant at an accepted key — arrives
          // as explicit debt, never as a bare row: triage means replacing this label,
          // so the baseline itself always says which rows are argued and which are
          // waiting. A surfaced sibling seeds '# untriaged' too: its twin's argument
          // was written for the mutants it had, not for one more.
          seeded++
          BaselineNotes.render(copy.key, "# untriaged", lineTag)
        }
        // A refresh with nothing unkilled writes no record: an empty (or newly
        // created, one-newline) baseline file reads as an armed-but-empty record
        // where there is no record at all, and clutters fully-detected suites.
        if (copies.isEmpty()) {
          if (baselineFile.isFile) {
            baselineFile.delete()
            logger.lifecycle("pitest baseline '$suiteName': nothing unkilled — baseline file removed")
          } else {
            logger.lifecycle("pitest baseline '$suiteName': nothing unkilled — no baseline to write")
          }
        } else {
          BaselineFiles.writeAtomically(baselineFile, written.joinToString("\n", postfix = "\n"))
          logger.lifecycle(
              "pitest baseline '$suiteName': wrote ${copies.size} accepted entries" +
                  (if (seeded == 0) "" else " ($seeded new row(s) seeded '# untriaged')") +
                  (if (flipped == 0) "" else " ($flipped note(s) carried across a status flip — re-check them)")
          )
        }
        if (droppedIdx.isNotEmpty()) {
          // The silent half of the refresh footgun: a full update rewrites from this one
          // run, so a flip-insurance union (detected today, survived under other load)
          // vanishes without a trace unless it is named here. Notes get the same
          // treatment: a note still in the carry pool after the rewrite is an
          // acceptance argument that just left the baseline — name its fate per row,
          // because a lost note that prints identically to a carried one is still
          // silent (casebook: the note the line shift dropped).
          fun rowFate(idx: Int): String = when {
            acceptedRows[idx].note == null -> ""
            idx in carriedIdx -> " — note carried"
            else -> " — note dropped with the row"
          }
          val lostCount = droppedIdx.count { acceptedRows[it].note != null && it !in carriedIdx }
          logger.lifecycle(
              "pitest baseline '$suiteName': dropped ${droppedIdx.size} row(s) not unkilled this run:\n" +
                  droppedIdx.joinToString("\n") { idx ->
                    "  ${BaselineNotes.render(acceptedRows[idx])}${describe(acceptedRows[idx].key)}${rowFate(idx)}"
                  } +
                  (if (lostCount == 0) "" else
                      "\n  $lostCount note(s) dropped with their rows — re-home the acceptance argument by hand if it still applies") +
                  "\n  a dropped flip-insurance union (see config/pitest/README.md) must be " +
                  "re-added with -PunionMutationBaseline once observed to flip again"
          )
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
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
          // multiset union: per key, the larger of the two occurrence counts —
          // existing rows keep their notes and line tags verbatim, added copies land
          // bare with this run's observed line
          val pairsByKey = acceptedRows.groupBy { it.key }
          val currentCounts = current.groupingBy { it }.eachCount()
          val linePool = HashMap<String, ArrayDeque<Int>>()
          currentLines.forEach { (key, lines) ->
            linePool[key] = ArrayDeque(lines.mapNotNull { it.toIntOrNull() }.sorted())
          }
          var total = 0
          val merged = (pairsByKey.keys + currentCounts.keys).sorted().flatMap { key ->
            val existing = pairsByKey[key].orEmpty()
            val extra = maxOf(0, (currentCounts[key] ?: 0) - existing.size)
            existing.forEach { linePool[key]?.removeFirstOrNull() }
            total += existing.size + extra
            existing.map { BaselineNotes.render(it) } +
                List(extra) {
                  BaselineNotes.render(
                      key, null, linePool[key]?.removeFirstOrNull()?.let { listOf(it) } ?: emptyList())
                }
          }
          BaselineFiles.writeAtomically(baselineFile, merged.joinToString("\n", postfix = "\n"))
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${added.size} entries (baseline now $total):\n" +
                  added.joinToString("\n") { row -> "  $row${describe(row)}" }
          )
        }
        if (baselineFile.isFile) stampToolVersion() else stampOrRetireToolVersion()
        return@doLast
      }
      val fresh = multisetDiff(current, accepted)
      val stale = multisetDiff(accepted, current)
      // Line-drift advisory: an unkilled mutant at a line no row's '# line' tag
      // names is a population the acceptance argument may no longer describe —
      // either the anchor moved, or a same-key swap slid a new mutant under an old
      // acceptance. Row-level where the data supports it (every row of the key
      // tagged, observed count matching the row count): the baseline's multiset
      // already fails a genuinely new sibling as a count change, so unlike the
      // audited-timeout sets there is no new-sibling quiet case to preserve, and
      // any unrecorded line under matched counts is worth a re-read. Partial tags
      // or skewed counts fall back to the audit's key-level disjointness. Advisory
      // only, never a failure: lines are metadata, and the next refresh rewrites
      // the tags (BaselineNotes.lineDrift owns the semantics).
      run {
        val observed = currentLines
            .mapValues { (_, lines) -> lines.mapNotNull { it.toIntOrNull() } }
        val drifted = BaselineNotes.lineDrift(acceptedRows, observed)
        if (drifted.isNotEmpty()) {
          logger.warn(
              "pitest baseline '$suiteName': ${drifted.size} accepted key(s) unkilled at line(s) " +
                  "no row's '# line' tag names — the code the acceptance argues about has moved, or a " +
                  "new mutant sits under an old acceptance (the same-key swap); re-read the README " +
                  "argument, then let the next refresh rewrite the tag:\n" +
                  drifted.entries.sortedBy { it.key }.joinToString("\n") { (key, lines) ->
                    val (recordedLines, observedLines) = lines
                    "  $key # line(s) ${recordedLines.sorted().joinToString(", ")} -> " +
                        "unrecorded ${observedLines.sorted().joinToString(", ")}"
                  }
          )
          advisoryLog.get().record(advisoryScope, "${drifted.size} line-drifted baseline key(s)")
        }
      }
      // Two situations produce paired stale + "new" rows and are classified rather
      // than lumped, because they call for different responses:
      //
      //   newly covered     — same class,method,mutator, different status (typically
      //                       NO_COVERAGE -> SURVIVED): a test newly reached the
      //                       mutant. A triage item — kill it or accept it with a
      //                       reason — never a refresh.
      //   surfaced sibling  — a "new" row identical to an accepted row: the key now
      //                       holds more unkilled sibling mutants than the baseline
      //                       has rows. Either pre-existing debt made visible (a
      //                       set-based baseline upgraded, a compound condition's
      //                       operands) or a genuinely NEW mutant landing at an
      //                       accepted key — the one shape the line-less key cannot
      //                       tell apart (HARDENING.md names this blind spot); the
      //                       report's line numbers say which.
      //
      // A stale row is consumed once it pairs, so several new rows cannot all claim
      // the same counterpart and report a churn that did not happen. Line shifts no
      // longer exist as a category: lines are not identity, so editing above a
      // mutated method produces no fresh rows at all.
      val acceptedRowTexts = accepted.toSet()
      val unpairedStale = stale.toMutableList()
      val newlyCoveredPairs = mutableListOf<Pair<String, String>>()
      val surfacedSiblings = mutableListOf<String>()
      for (row in fresh.sorted()) {
        val flip = unpairedStale.firstOrNull {
          it.substringBeforeLast(',') == row.substringBeforeLast(',') && it != row
        }
        if (flip != null) {
          unpairedStale.remove(flip)
          newlyCoveredPairs.add(row to flip)
          continue
        }
        if (row in acceptedRowTexts) {
          surfacedSiblings.add(row)
        }
      }
      val newlyCoveredFrom = newlyCoveredPairs.toMap(mutableMapOf())
      val surfacedSiblingTexts = surfacedSiblings.toSet()
      val unexplained = fresh.size - newlyCoveredPairs.size - surfacedSiblings.size
      fun freshHint(row: String): String = when {
        row in surfacedSiblingTexts ->
          " (shares an accepted key — sibling debt surfaced, or a NEW mutant at that key; check the line)"
        newlyCoveredFrom.containsKey(row) ->
          " (newly covered — was ${newlyCoveredFrom.getValue(row).substringAfterLast(',')}; triage, not a refresh)"
        else -> ""
      }
      // representative note per key, for listings that print baseline rows
      val noteByKey = acceptedRows.filter { it.note != null }.associateBy({ it.key }, { it.note })
      if (stale.isNotEmpty()) {
        // A stale-looking row whose coordinate read TIMED_OUT this run is neither
        // killed nor moved — it is the load-dependent detection the TIMED_OUT
        // doctrine warns about, and prune deliberately keeps such rows. Counting it
        // here both contradicted the SURVIVED -> TIMED_OUT warning printed above and
        // recommended a refresh that would be a no-op for it (casebook: the
        // limbsLength flapper told to prune itself). Reported separately instead,
        // and excluded from the refresh hint.
        // the same set prune keeps, so this hint and prune cannot disagree
        val (staleTimedOut, staleGone) =
            stale.partition { it.substringBeforeLast(',') in timedOutCoordinatesNow }
        if (staleGone.isNotEmpty()) {
          // Point at prune, not update: when the only news is *fewer* survivors, the
          // shrink-only refresh is the always-safe direction — it cannot bake in a
          // coin-flip from this one run, which is exactly what recommending a full
          // rewrite here used to invite. With new rows present that case still wants
          // update — after the new rows are triaged, since they may be newly covered
          // or surfaced siblings, where update-before-triage is exactly the
          // laundering the ratchet exists to prevent.
          val direction = if (fresh.isEmpty()) "-PpruneMutationBaseline (shrink-only; nothing new to bake in)"
          else "-PupdateMutationBaseline after the new rows below are triaged"
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleGone.size} stale entries (since killed) — refresh with $direction")
        }
        if (staleTimedOut.isNotEmpty()) {
          logger.lifecycle(
              "pitest baseline '$suiteName': ${staleTimedOut.size} baseline row(s) read TIMED_OUT this run — " +
                  "load-dependent detection, not a kill; no refresh needed (prune keeps them):\n" +
                  staleTimedOut.joinToString("\n") {
                    "  ${BaselineNotes.render(it, noteByKey[it], emptyList())}"
                  })
        }
      }
      if (fresh.isNotEmpty()) {
        // Split the report: the two statuses need opposite responses, and saying so
        // here saves re-deriving it from the raw rows.
        val freshByStatus = fresh.groupBy { it.substringAfterLast(',') }
        val detail = buildString {
          freshByStatus["NO_COVERAGE"]?.let {
            append("\n  ${it.size} NO_COVERAGE — no test reaches these; mechanical work, ")
            append("and never acceptable as \"equivalent\" since the behaviour was never observed:\n")
            append(it.joinToString("\n") { row -> "    $row${freshHint(row)}${describe(row)}" })
          }
          freshByStatus["SURVIVED"]?.let {
            append("\n  ${it.size} SURVIVED — a test ran these and could not tell the difference; ")
            append("strengthen the assertion or triage for equivalence:\n")
            append(it.joinToString("\n") { row -> "    $row${freshHint(row)}${describe(row)}${siblingHint(row)}" })
          }
          // The churn tally answers the question the per-row hints cannot: is the whole
          // set accounted for? Refreshing is only safe when nothing is unexplained and
          // nothing was newly covered.
          append("\n  churn: ${newlyCoveredPairs.size} newly covered, ")
          if (surfacedSiblings.isNotEmpty()) append("${surfacedSiblings.size} surfaced sibling(s), ")
          append("$unexplained unexplained (of ${fresh.size} new; ${stale.size} stale)")
          if (newlyCoveredPairs.isNotEmpty()) {
            append("\n  ${newlyCoveredPairs.size} row(s) are newly covered rather than new code: a test now ")
            append("reaches them, so they are triage (kill or accept with a reason), not a refresh")
          }
          if (surfacedSiblings.isNotEmpty()) {
            append("\n  ${surfacedSiblings.size} row(s) share an accepted key: pre-existing sibling debt ")
            append("surfaced by the multiset comparison, or a genuinely new mutant landing at an accepted ")
            append("key — the line-less key's documented blind spot; read the report's line numbers before ")
            append("accepting")
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
    val readmeFile = layout.projectDirectory.file("config/pitest/README.md").asFile
    val debtTimeoutsFile = layout.projectDirectory.file("config/pitest/$suiteName-timeouts.csv").asFile
    val debtToolVersionFile = layout.projectDirectory.file("config/pitest/$suiteName-pitest-version").asFile
    val debtPitVersion = hardening.pitestVersion
    val debtClassesDir = mutationClassesDir
    val debtTargets = suite.targetClasses
    val debtExcludes = allExcludedClasses
    val debtTestSourceDirs = sourceSets.test.get().java.srcDirs
    val debtSiblingTargets = suiteTargetGlobs
    val debtSiblingExcludes = suiteExcludedGlobs
    val debtDeclinedExclusions = suite.declinedExclusionAudits
    doLast {
      // Committed-files-only, like the audit's static half below — which makes Debt
      // (and therefore the fleet canary) the place a plugin release that bumps PIT
      // surfaces per consumer repo, before anyone reads tool churn as code churn.
      val recordedPit = debtToolVersionFile.takeIf { it.isFile }?.readText()?.trim()
      if (recordedPit != null && recordedPit != debtPitVersion.get()) {
        logger.warn(
            "pitest '$suiteName': baseline record written by PIT $recordedPit, this plugin runs " +
                "PIT ${debtPitVersion.get()} — population differences may be the tool, not the code; " +
                "re-baseline deliberately (config/pitest/$suiteName-pitest-version, then refresh the suite)"
        )
      }
      // The audited-timeout set's static half, shared with the verify (TimeoutAudit):
      // row shape and cause presence read committed files only, so a pasted member or
      // a fresh README cause is confirmed here in seconds instead of after the next
      // mutation run. The mutant-facing checks (unaudited newcomers, stale members,
      // quiet streaks) need a report and stay in the verify — which also means no
      // staleness is known here, so every well-formed member is asked for its cause.
      // Before the debt tally, not after: a fully-detected suite has no debt to print
      // (the early return below) but its audited set is exactly as checkable. Plain
      // warnings, no advisory-log entries: the end-of-build summary exists for the
      // gate's scroll problem, and these lines end a short interactive run.
      if (debtTimeoutsFile.isFile) {
        val membership = TimeoutAudit.parse(debtTimeoutsFile.readLines())
        TimeoutAudit.malformedWarning(suiteName, debtTimeoutsFile.name, membership.malformed)
            ?.let { logger.warn(it) }
        val undocumented = TimeoutAudit.undocumentedCauses(membership.members) {
          readmeFile.takeIf { it.isFile }?.readText() ?: ""
        }
        if (undocumented.isNotEmpty()) {
          logger.warn(TimeoutAudit.undocumentedCauseWarning(suiteName, undocumented))
        }
      }

      // The exclusion audit's static half, mirroring the timeout audit's: the
      // policy is pure given recompiled classes, so when a prior run left
      // build/mutation-classes behind it is checkable here without a mutation
      // run — which is what puts it in front of the fleet canary, whose Debt
      // runs are where a plugin release meets real consumer globs. Its in-run
      // half only fired inside a real 'pitest<Suite>' execution, which the
      // canary never does, and that blind spot shipped a release *(casebook:
      // the partition the audit called a hole)*. Absent classes stay silent:
      // nothing was scanned, and silence means "unscanned", never "clean" —
      // and like the report above, the classes may be stale; both caveats ride
      // the same rerun hint.
      val debtClasses = debtClassesDir.get().asFile
      if (debtClasses.isDirectory) {
        val siblingScopes = debtSiblingTargets.get()
            .filterKeys { it != suiteName }
            .map { (sibling, targets) ->
              ExclusionAudit.SuiteScope(targets, debtSiblingExcludes.get()[sibling].orEmpty())
            }
        val swallowed = ExclusionAudit.swallowedProductionClasses(
            debtClasses, debtTargets.get(), debtExcludes.get(), debtTestSourceDirs, siblingScopes
        )
        val declined = ExclusionAudit.applyDeclines(swallowed, debtDeclinedExclusions.get())
        ExclusionAudit.warning(suiteName, declined.reported)?.let { logger.warn(it) }
        ExclusionAudit.staleDeclineWarning(suiteName, declined.staleGlobs)?.let { logger.warn(it) }
        ExclusionAudit.blankDeclineWarning(suiteName, declined.blankGlobs)?.let { logger.warn(it) }
      }

      fun tally(pairs: List<Pair<String, String>>): Map<String, Pair<Int, Int>> = pairs
          .groupBy({ it.first }, { it.second })
          .mapValues { (_, statuses) ->
            statuses.count { it == "SURVIVED" } to statuses.count { it == "NO_COVERAGE" }
          }

      // BaselineNotes handles both formats: line-less rows and legacy five-field ones
      val baselinePairs = if (baselineFile.exists()) {
        baselineFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { BaselineNotes.parse(it).key.split(',') }
            .filter { it.size >= 4 }
            .map { it[0] to it.last() }
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
      // The report is a snapshot, not live state: name its age so numbers from a
      // run made before the current change are not read as current.
      val age = if (reportPairs == null) "" else {
        val minutes = (System.currentTimeMillis() - csv.lastModified()) / 60_000
        if (minutes < 2) "" else ", ${minutes}m old — rerun $pitestTaskName if stale"
      }
      // Label breakdown from the baseline (one read, the shared BaselineNotes parse):
      // triaged-accepted rows carry a family label, seeded debt reads '# untriaged',
      // and unlabeled rows predate seeding.
      val baselineRows = if (!baselineFile.exists()) emptyList()
      else baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
      val baselineNotes = baselineRows.mapNotNull { BaselineNotes.parse(it).note }
      val labelBreakdown = BaselineNotes.summarize(baselineNotes, baselineRows.size - baselineNotes.size)
          ?.let { "\n  baseline labels: $it" } ?: ""
      logger.lifecycle(
          "pitest '$suiteName' debt ($source$age) — $totalSurvived survived, $totalNoCoverage no_coverage " +
              "across ${debt.size} class(es):\n" + lines.joinToString("\n") + labelBreakdown
      )
      // The same pointer check the verify runs, after the breakdown rather than before
      // it: this task is where a triager reads the counts and picks the next cluster, so
      // a label resolving to nothing is named at the moment it would be acted on — a
      // count is what makes a mistyped label look like finished triage.
      val undocumentedLabels = BaselineNotes.undocumentedLabels(baselineNotes) {
        readmeFile.takeIf { it.isFile }?.readText() ?: ""
      }
      if (undocumentedLabels.isNotEmpty()) {
        logger.warn(BaselineNotes.undocumentedLabelWarning(suiteName, undocumentedLabels))
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
    val excludedClassesArg = allExcludedClasses.map { excluded ->
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
    // Mutator-blindness advice. A mutant that is never generated cannot
    // survive, so a suite whose subject is BigDecimal/BigInteger math can sit
    // green for years with that math unmutated and nothing anywhere says so.
    // Runs after compileForPitest (a dependency of this task), reads the very
    // classes about to be mutated, and only speaks when the matching mutator is
    // absent — so it goes quiet the moment the gap is closed or measured.
    // Plain values only: the configuration cache cannot serialize the script.
    val adviceClassesDir = mutationClassesDir
    val adviceTargets = suite.targetClasses
    val adviceExcludes = allExcludedClasses
    val adviceMutators = suite.mutators
    val adviceDeclined = suite.declinedMutators
    val adviceTrialTask = path.substringBeforeLast(':') + ":pitestMutatorTrial"
    // the mutation recompile contains the test sources too (they share the mutated
    // packages), so the audit tells production from prey the same way the
    // mutated-fakes warning does: by whether the source sits under a test src dir
    val adviceTestSourceDirs = sourceSets.test.get().java.srcDirs
    val adviceSiblingTargets = suiteTargetGlobs
    val adviceSiblingExcludes = suiteExcludedGlobs
    val adviceDeclinedExclusions = suite.declinedExclusionAudits
    val adviceAdvisoryLog = hardeningAdvisoryLog
    val adviceAdvisoryScope = suiteAdvisoryScope
    usesService(adviceAdvisoryLog)
    doFirst {
      val classesDir = adviceClassesDir.get().asFile
      // No recompiled classes means nothing was scanned, and "found nothing" would
      // then wrongly read as "the declines have no subject left".
      if (classesDir.isDirectory) {
        // The inverse of the mutated-fakes warning: a production class matched by an
        // exclusion glob leaves the population silently — the globs and the sources
        // they protect must define the same set.
        val siblingScopes = adviceSiblingTargets.get()
            .filterKeys { it != suiteName }
            .map { (sibling, targets) ->
              ExclusionAudit.SuiteScope(targets, adviceSiblingExcludes.get()[sibling].orEmpty())
            }
        val swallowed = ExclusionAudit.swallowedProductionClasses(
            classesDir, adviceTargets.get(), adviceExcludes.get(), adviceTestSourceDirs, siblingScopes
        )
        // Deliberate opt-outs are the one exclusion category the scan cannot derive
        // — "generated bindings" is a judgment, not a property of the globs — so a
        // suite argues them with declineExclusionAudit and they leave the report.
        // The records keep earning themselves: a blank reason suppresses nothing and
        // is named, and one that stops matching is named as deletable.
        val declined = ExclusionAudit.applyDeclines(swallowed, adviceDeclinedExclusions.get())
        ExclusionAudit.warning(suiteName, declined.reported)?.let {
          logger.warn(it)
          // recorded like every other warn-level advisory, so it reaches the
          // end-of-build summary instead of scrolling past mid-build
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope,
              "${declined.reported.size} production class(es) swallowed by excludedClasses"
          )
        }
        ExclusionAudit.staleDeclineWarning(suiteName, declined.staleGlobs)?.let {
          logger.warn(it)
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope, "${declined.staleGlobs.size} stale exclusion decline(s)")
        }
        ExclusionAudit.blankDeclineWarning(suiteName, declined.blankGlobs)?.let {
          logger.warn(it)
          adviceAdvisoryLog.get().record(
              adviceAdvisoryScope, "${declined.blankGlobs.size} exclusion decline(s) without a reason")
        }
        val advice = MutatorAdvice.advise(
            MutatorAdvice.scan(
                classesDir,
                adviceTargets.get(),
                adviceExcludes.get(),
                adviceMutators.get(),
            ),
            adviceMutators.get(),
            adviceDeclined.get(),
        )
        advice.findings.forEach { finding ->
          logger.warn(
              "pitest '$suiteName': ${finding.classCount} mutated class(es) call ${finding.label} arithmetic " +
                  "(${finding.callCount} call site(s)), which the enabled mutator set cannot mutate — " +
                  "those computations are currently unmutated, not proven.\n" +
                  "  measure it: ./gradlew $adviceTrialTask -PtrialMutators=${finding.mutator}\n" +
                  "  then enable what fires (mutators = \"...,${finding.mutator}\") and record the numbers, " +
                  "or record the measured decision not to: " +
                  "declineMutator(\"${finding.mutator}\", \"what the trial generated, and why it was not worth it\")."
          )
        }
        // A suppression that has outlived its subject is worse than none: it reads as
        // a settled decision about code that no longer exists.
        advice.staleDeclines.forEach { stale ->
          logger.warn(
              "pitest '$suiteName': the recorded decline of ${stale.mutator} is stale — ${stale.why}."
          )
        }
      }
    }
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

  // libFuzzer silently truncates any input longer than max_len when loading a corpus:
  // a fuzz run explores a clipped copy of an oversized seed, and the minimize merge
  // re-hashes the clip — adopting it under a hash name and deleting the named original,
  // which quietly degrades what the seed pins (casebook: the seed clipped by its own
  // max_len). Both consumers refuse up front instead; the fix is a one-line maxLen bump
  // or a deliberate re-minimization of the seed.
  val seedLenCheck = tasks.register("fuzz" + target.name.replaceFirstChar(Char::uppercase) + "SeedLenCheck") {
    description = "Internal to the '${target.name}' fuzz tasks: refuses seeds larger than the target's maxLen."
    val targetName = target.name
    val maxLen = target.maxLen
    val seedCorpus = target.seedCorpus
    val localCorpusDir = layout.buildDirectory.dir("fuzz/${target.name}-corpus").get().asFile
    val adoptLocalCorpus = providers.gradleProperty("adoptLocalCorpus").isPresent
    doLast {
      val cap = maxLen.orNull ?: return@doLast
      fun oversizedIn(dir: File?) =
          dir?.listFiles()?.filter { it.isFile && it.length() > cap }.orEmpty().sortedBy { it.name }
      val committed = oversizedIn(seedCorpus.orNull?.asFile)
      val local = if (adoptLocalCorpus) oversizedIn(localCorpusDir) else emptyList()
      if (committed.isEmpty() && local.isEmpty()) return@doLast
      val listing = (committed.map { "  ${it.name} (${it.length()} bytes, committed)" } +
          local.map { "  ${it.name} (${it.length()} bytes, local corpus)" }).joinToString("\n")
      throw GradleException(
          "fuzz '$targetName': ${committed.size + local.size} seed(s) exceed maxLen=$cap. libFuzzer " +
              "truncates oversized inputs on load — a fuzz run would explore a clipped copy, and a merge " +
              "would adopt the clip under a hash name and delete the named original:\n$listing\n" +
              "Raise the target's maxLen to cover its largest committed seed, or shrink the seed deliberately.")
    }
  }

  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase)) {
    group = "verification"
    description = "Coverage-guided fuzzing of the '${target.name}' target with Jazzer; -PmaxFuzzTime=<seconds> (default 60)."
    dependsOn(seedLenCheck)
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
  // inputs arrive under libFuzzer's hash names — the seedLenCheck above keeps
  // truncation from forging a "new" input out of an oversized named seed.
  tasks.register<JavaExec>("fuzz" + target.name.replaceFirstChar(Char::uppercase) + "Minimize") {
    group = "verification"
    description = "Minimizes the '${target.name}' seed corpus with libFuzzer -merge=1; -PadoptLocalCorpus also folds in inputs found by local fuzz runs."
    dependsOn(seedLenCheck)
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
  // A target with no seedCorpus generates no replay test, so nothing it has
  // ever found is re-run by 'check' and 'fuzz<Target>Minimize' fails when
  // reached — all of it silently, which is the one failure mode this whole
  // replay mechanism exists to prevent. Name them instead, unless the repo has
  // recorded a measured decision (declineSeedCorpus) — a blank reason records
  // nothing and so suppresses nothing.
  val corpusless = hardening.fuzz
      .filter { it.seedCorpus.orNull == null && it.declinedSeedCorpus.orNull.isNullOrBlank() }
      .map { it.name }
      .sorted()
  // Declines rot too: a target that has since gained a corpus, or that records a
  // reason-less decline, is carrying a suppression that argues for nothing.
  val staleDeclines = hardening.fuzz.mapNotNull { target ->
    val reason = target.declinedSeedCorpus.orNull ?: return@mapNotNull null
    when {
      reason.isBlank() ->
        target.name to "it carries no reason, so it suppresses nothing — record why no corpus is needed, or drop it"
      target.seedCorpus.orNull != null ->
        target.name to "the target now declares a seedCorpus, so the decline contradicts it"
      else -> null
    }
  }.sortedBy { it.first }
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
  // Declared as an input so adding or removing a corpus-less target re-runs this
  // task and re-prints. The consequence is deliberate: on an unchanged incremental
  // build the task is up to date and says nothing, so the advice lands when the
  // configuration changes and on a fresh checkout, not on every build. A warning
  // repeated into an unchanged build is how people learn to skim warnings.
  inputs.property("corpusless", corpusless.joinToString("|"))
  inputs.property("staleCorpusDeclines", staleDeclines.joinToString("|") { "${it.first}=${it.second}" })
  doLast {
    corpusless.forEach { name ->
      logger.warn(
          "fuzz target '$name' declares no seedCorpus: no replay test is generated, so nothing this " +
              "harness finds is re-run by 'check', and 'fuzz${name.replaceFirstChar(Char::uppercase)}Minimize' " +
              "will fail when reached.\n" +
              "  fix: seedCorpus = layout.projectDirectory.dir(\"src/test/resources/fuzz/$name\") " +
              "(a corpus under the test resources resolves hermetically), then commit at least one seed.\n" +
              "  A corpus is where a finding lands, so this holds even where a mutator reaches the format " +
              "from scratch — if it genuinely does not, record why: declineSeedCorpus(\"...\")."
      )
    }
    staleDeclines.forEach { (name, why) ->
      logger.warn("fuzz target '$name': the recorded seedCorpus decline is stale — $why.")
    }
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
          |format: `class,method,mutator,STATUS` — line numbers are metadata, carried as
          |a trailing `# line N` tag every refresh rewrites, so editing above a mutated
          |method churns nothing. Full policy — the three legal outcomes for a new
          |survivor, determinism requirements, targeting rules — lives in sava-build's
          |`HARDENING.md`.
          |
          |Never refresh with `-PupdateMutationBaseline` just to make the build pass:
          |kill the mutant, refactor it out of existence, or record its equivalence
          |reason below. A failure classifies each new row (`newly covered` vs shares an
          |accepted key vs unexplained) and closes with a churn tally: a newly covered
          |row is triage, not a refresh, and identical rows are sibling mutants of one
          |compound condition — the comparison is a multiset, so never hand-dedupe the
          |CSV. A row sharing an accepted key may also be a genuinely new mutant
          |inheriting the key's acceptance (the line-less key's documented blind spot);
          |read the report's line numbers before accepting.
          |
          |A baseline row may carry a `# note` before its line tag — `# untriaged` is
          |the conventional label for seeded debt. Notes are preserved across
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
        |  4. for suites whose summary reports timed-out mutants, seed the audited set:
        |       ./gradlew pitest<Suite> -PinitTimeoutAudit — then write each member's
        |       structural cause in config/pitest/README.md (HARDENING.md, audited-timeout bullet)
        |  5. copy the agent-instructions template from HARDENING.md into AGENTS.md with:
        |       <!-- hardening-template sha256:$digest -->
        |  6. decide who owns the pre-release qualityGate run, and record it in AGENTS.md
        |  7. fuzz targets with a seedCorpus get a generated replay test automatically;
        |       document seed provenance in a README next to (never inside) the corpus dir
        |  8. optional: hardening.generateTestSupport = true generates shared socket/
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
