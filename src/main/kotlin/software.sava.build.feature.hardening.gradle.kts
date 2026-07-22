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
      logger.lifecycle("pitestConverge: ${names.size} suite(s) converged — zero per-mutant status flips")
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
                "coverage failures are transient — re-run the suite)"
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
              text("mutator").substringAfterLast('.'), mutation.getAttribute("status")
          ).joinToString(",")
          collected.getOrPut(key) { mutableListOf() }.add(text("description"))
        }
        collected.mapValues { (_, all) -> all.distinct().joinToString(" | ") }
      }
      fun describe(row: String) = descriptions[row]?.let { " — $it" } ?: ""

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
      if (update) {
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(current.joinToString("\n", postfix = "\n"))
        logger.lifecycle("pitest baseline '$suiteName': wrote ${current.size} accepted entries")
        return@doLast
      }
      val accepted = if (baselineFile.exists()) {
        baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
      } else {
        emptySet()
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
          baselineFile.writeText(merged.joinToString("\n", postfix = "\n"))
          logger.lifecycle(
              "pitest baseline '$suiteName': union added ${added.size} entries (baseline now ${merged.size}):\n" +
                  added.joinToString("\n") { row -> "  $row${describe(row)}" }
          )
        }
        return@doLast
      }
      val fresh = current - accepted
      val stale = accepted - current
      // Line numbers are part of the baseline key, so editing a mutated file shows up as
      // paired stale + "new" rows. Pair them mechanically: same class, method and mutator
      // with only the line differing is almost always the old row shifted, not a new
      // mutant — but confirm before refreshing.
      fun shiftHint(row: String): String {
        val parts = row.split(',')
        val match = stale.firstOrNull { staleRow ->
          val staleParts = staleRow.split(',')
          staleParts[0] == parts[0] && staleParts[1] == parts[1] &&
              staleParts[3] == parts[3] && staleParts[2] != parts[2]
        }
        return match?.let { " (likely shifted from line ${it.split(',')[2]})" } ?: ""
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
          if (fresh.all { shiftHint(it).isNotEmpty() }) {
            append("\n  every new row pairs with a stale one — likely refactor line churn; ")
            append("confirm the pairings above, then refresh with -PupdateMutationBaseline")
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

  // Shared JavaExec configuration for the ratchet run and the converge second round.
  val configurePitestExec: JavaExec.() -> Unit = {
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
    // Incremental analysis: one rolling history file per suite, deliberately outside
    // build/ so 'clean' does not erase the accumulated results, and git-ignored
    // because it is machine-local state. Input and output are the same file; on the
    // first run the input does not exist yet and PIT starts fresh. The lifecycle line
    // keeps reuse honest — with history active a fast run is expected, so the log
    // must say why, and the pre-release gate re-earns its numbers with
    // '-PnoMutationHistory'.
    val historyActive = mutationHistory
    val historyFile = layout.projectDirectory.file(".pitest-history/${suite.name}.hist").asFile
    if (historyActive) {
      doFirst {
        historyFile.parentFile.mkdirs()
        logger.lifecycle("pitest '$suiteName': arcmutate history active — $historyFile")
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
  val targets = hardening.fuzz.mapNotNull { target ->
    val corpus = target.seedCorpus.orNull?.asFile ?: return@mapNotNull null
    Triple(target.name, target.targetClass.get(), corpus.absolutePath)
  }
  inputs.property("targets", targets.map { (name, fqcn, corpus) -> "$name|$fqcn|$corpus" })
  doLast {
    val dir = outputDir.get().asFile
    dir.deleteRecursively()
    dir.mkdirs()
    targets.forEach { (name, fqcn, corpusPath) ->
      val pkg = fqcn.substringBeforeLast('.')
      val simple = fqcn.substringAfterLast('.')
      val className = simple + "SeedReplayTest"
      val source = dir.resolve(pkg.replace('.', '/')).resolve("$className.java")
      source.parentFile.mkdirs()
      source.writeText(
          """
          |package $pkg;
          |
          |import org.junit.jupiter.api.Test;
          |
          |/// Generated by the sava-build hardening plugin ('generateFuzzReplayTests'): replays
          |/// the committed '$name' seed corpus through the harness inside 'check', so the
          |/// corpus cannot rot between fuzz runs. Regenerated every build; do not edit.
          |final class $className {
          |
          |  @Test
          |  void replaysSeedCorpus() throws Exception {
          |    final var corpus = java.nio.file.Path.of("${corpusPath.replace("\\", "\\\\")}");
          |    org.junit.jupiter.api.Assertions.assertTrue(
          |        java.nio.file.Files.isDirectory(corpus), "seed corpus missing: " + corpus);
          |    try (final var files = java.nio.file.Files.list(corpus)) {
          |      for (final var file : files.sorted().toList()) {
          |        $simple.fuzzerTestOneInput(java.nio.file.Files.readAllBytes(file));
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
          |mutated file shift entries — the verify task labels likely shifts; confirm
          |them before refreshing.
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
        |""".trimMargin()
    )
  }
}
