package software.sava.build.hardening

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/** Names of the Gradle properties understood or explicitly retired by hardening. */
internal object HardeningOptionNames {
  data class Descriptor(
    val name: String,
    val value: String?,
    val purpose: String,
  )

  const val ADOPT_LOCAL_CORPUS = "adoptLocalCorpus"
  const val INIT_TIMEOUT_AUDIT = "initTimeoutAudit"
  const val ISOLATE_MUTANTS = "isolateMutants"
  const val LIST_UNKILLED = "listUnkilled"
  const val MAX_FUZZ_TIME = "maxFuzzTime"
  const val MAX_PARALLEL_FUZZ_TARGETS = "maxParallelFuzzTargets"
  const val MUTATE_ONLY = "mutateOnly"
  const val NO_MUTATION_HISTORY = "noMutationHistory"
  const val PITEST_MODE = "pitestMode"
  const val PRUNE_MUTATION_BASELINE = "pruneMutationBaseline"
  const val SAVA_BUILD_LOCAL_REPO = "savaBuildLocalRepo"
  const val STRICT_TIMEOUT_AUDIT = "strictTimeoutAudit"
  const val TRIAL_MUTATORS = "trialMutators"
  const val UNION_MODE_FLIPS = "unionModeFlips"
  const val UNION_MUTATION_BASELINE = "unionMutationBaseline"
  const val UPDATE_MUTATION_BASELINE = "updateMutationBaseline"

  val removedWriterTaskByProperty = linkedMapOf(
    UPDATE_MUTATION_BASELINE to "pitest<Suite>BaselineUpdate",
    UNION_MUTATION_BASELINE to "pitest<Suite>BaselineUnion",
    PRUNE_MUTATION_BASELINE to "pitest<Suite>BaselinePrune",
    INIT_TIMEOUT_AUDIT to "pitest<Suite>TimeoutAuditInit",
    UNION_MODE_FLIPS to "pitestModeCompareUnion",
  )

  val removedWriterProperties: Set<String> = removedWriterTaskByProperty.keys.toSet()

  fun removedWriterMessage(present: Collection<String>): String {
    val replacements = present.toSortedSet().joinToString { property ->
      "-P$property -> ${checkNotNull(removedWriterTaskByProperty[property])}"
    }
    return "state-changing hardening writer properties were removed in sava-build 21.5.22 " +
        "and are refused: $replacements. Use the named task; it is the only supported " +
        "committed-file write interface."
  }

  val certificationForbiddenProperties = listOf(
    ISOLATE_MUTANTS,
    MUTATE_ONLY,
    TRIAL_MUTATORS,
    PITEST_MODE,
  )

  /** One inventory for `hardeningHelp`, validation, and documentation tests. */
  val descriptors = listOf(
    Descriptor(ADOPT_LOCAL_CORPUS, null,
        "include build/fuzz local findings when minimizing a committed corpus"),
    Descriptor(ISOLATE_MUTANTS, null,
        "run one mutant per PIT unit to diagnose inter-mutant contamination; " +
            "requires -PmutateOnly and disables history"),
    Descriptor(LIST_UNKILLED, null,
        "print current unkilled mutants with PIT descriptions"),
    Descriptor(MAX_FUZZ_TIME, "seconds",
        "set the local fuzz campaign budget (default 60)"),
    Descriptor(MAX_PARALLEL_FUZZ_TARGETS, "count",
        "bound concurrent fuzz targets (default 1; recorded in aggregate evidence)"),
    Descriptor(MUTATE_ONLY, "class-glob[,class-glob]",
        "run a deliberately scoped mutation-development iteration"),
    Descriptor(NO_MUTATION_HISTORY, null,
        "disable ArcMutate result reuse without changing the licensed PIT population; " +
            "required when an ordinary run supports any accepted-baseline or timeout-audit decision"),
    Descriptor(PITEST_MODE, "label",
        "label a pitestModeSnapshot observation"),
    Descriptor(SAVA_BUILD_LOCAL_REPO, "directory",
        "resolve an unpublished sava-build test publication (settings-level)"),
    Descriptor(STRICT_TIMEOUT_AUDIT, null,
        "escalate incomplete timeout-audit evidence"),
    Descriptor(TRIAL_MUTATORS, "mutator[,mutator]",
        "select candidates for pitestMutatorTrial"),
  )

  init {
    check(descriptors.map { it.name }.toSet().size == descriptors.size) {
      "hardening Gradle-property inventory contains duplicate names"
    }
    check(descriptors.none { it.name in removedWriterProperties }) {
      "removed hardening writer properties must not remain in the active option inventory"
    }
    check(removedWriterTaskByProperty.size == 5) {
      "hardening writer tombstone inventory must name all five removed properties"
    }
  }
}

/** The one record-writing interpretation applied by a suite verification. */
internal enum class BaselineWriteOperation {
  CHECK,
  REBASE,
  UPDATE,
  UNION,
  PRUNE,
  INIT_TIMEOUT_AUDIT,
}

internal enum class ProjectWriteOperation(val description: String) {
  MODE_FLIP_INSURANCE("mode-compare:union-flips"),
  SCHEMA_MIGRATE("schema:migrate"),
  SCHEMA_DOWNGRADE("schema:downgrade"),
}

/** One path/content pair prepared in memory for a final typed mutation-record commit. */
internal data class PreparedMutationWrite(
  val targetPath: String,
  val content: String,
)

/** Bytes to write plus every filesystem tree the decision read. */
internal data class PreparedMutationCommit(
  val writes: List<PreparedMutationWrite>,
  val readTrees: List<BaselineFiles.TreeSnapshot>,
)

/** A discoverable task's state-changing request. */
internal enum class HardeningWriteRequest(
  val baselineOperation: BaselineWriteOperation?,
  val projectOperation: ProjectWriteOperation?,
  val displayName: String,
) {
  BASELINE_REBASE(BaselineWriteOperation.REBASE, null, "baseline provenance rebase"),
  BASELINE_UPDATE(BaselineWriteOperation.UPDATE, null, "baseline update"),
  BASELINE_UNION(BaselineWriteOperation.UNION, null, "baseline union"),
  BASELINE_PRUNE(BaselineWriteOperation.PRUNE, null, "baseline prune"),
  TIMEOUT_AUDIT_INIT(
      BaselineWriteOperation.INIT_TIMEOUT_AUDIT, null, "timeout-audit initialization"),
  MODE_FLIP_INSURANCE(
      null, ProjectWriteOperation.MODE_FLIP_INSURANCE, "mode-flip insurance union"),
  SCHEMA_MIGRATE(null, ProjectWriteOperation.SCHEMA_MIGRATE, "baseline schema migration"),
  SCHEMA_DOWNGRADE(null, ProjectWriteOperation.SCHEMA_DOWNGRADE, "baseline schema downgrade"),
}

/**
 * Mutable state shared only inside one Gradle invocation. Dedicated writer-task
 * preflights request an operation before PIT starts; the suite's verify finalizer
 * reads the same request after PIT completes. This is deliberately separate from
 * configuration-time task-name inspection, which cannot reliably model task
 * abbreviations or aggregates.
 */
internal class HardeningOperationRegistry {
  private data class SuiteKey(val projectPath: String, val suite: String)

  private val suiteOperations = mutableMapOf<SuiteKey, BaselineWriteOperation>()
  private val consumedSuiteOperations = mutableMapOf<SuiteKey, BaselineWriteOperation>()
  private val projectOperations = mutableMapOf<String, ProjectWriteOperation>()
  private val consumedProjectOperations = mutableMapOf<String, ProjectWriteOperation>()
  private val preparedProjectWrites = mutableMapOf<String, PreparedMutationCommit>()
  private val poisonedProjects = mutableMapOf<String, String>()

  private fun requireHealthy(projectPath: String) {
    poisonedProjects[projectPath]?.let { conflict ->
      throw IllegalArgumentException(
          "hardening writer state for '$projectPath' is poisoned by an earlier conflict: $conflict")
    }
  }

  private fun poison(projectPath: String, conflict: String): Nothing {
    poisonedProjects.putIfAbsent(projectPath, conflict)
    throw IllegalArgumentException("hardening writer conflict for '$projectPath': $conflict")
  }

  @Synchronized
  fun reject(projectPath: String, reason: String): Nothing {
    requireHealthy(projectPath)
    poison(projectPath, reason)
  }

  @Synchronized
  fun requestSuite(
    projectPath: String,
    suite: String,
    operation: BaselineWriteOperation,
  ) {
    require(operation != BaselineWriteOperation.CHECK) { "CHECK is not a state-changing request" }
    requireHealthy(projectPath)
    projectOperations[projectPath]?.let { existing ->
      poison(projectPath, "$existing and $suite:$operation")
    }
    val key = SuiteKey(projectPath, suite)
    val previous = suiteOperations[key]
    if (previous != null && previous != operation) {
      poison(projectPath, "$suite:$previous and $suite:$operation")
    }
    suiteOperations.putIfAbsent(key, operation)
  }

  @Synchronized
  fun suiteOperation(projectPath: String, suite: String): BaselineWriteOperation =
    requireHealthy(projectPath).let {
      suiteOperations[SuiteKey(projectPath, suite)] ?: BaselineWriteOperation.CHECK
    }

  @Synchronized
  fun requestModeFlipInsurance(projectPath: String) {
    requestProject(projectPath, ProjectWriteOperation.MODE_FLIP_INSURANCE)
  }

  @Synchronized
  fun modeFlipInsuranceRequested(projectPath: String): Boolean =
    requireHealthy(projectPath).let {
      projectOperations[projectPath] == ProjectWriteOperation.MODE_FLIP_INSURANCE
    }

  @Synchronized
  fun requestProject(projectPath: String, operation: ProjectWriteOperation) {
    requireHealthy(projectPath)
    val suiteRequests = suiteOperations.filterKeys { it.projectPath == projectPath }
    if (suiteRequests.isNotEmpty()) {
      poison(
          projectPath,
          suiteRequests.entries.joinToString { "${it.key.suite}:${it.value}" } + " and $operation")
    }
    val previous = projectOperations[projectPath]
    if (previous != null && previous != operation) {
      poison(projectPath, "$previous and $operation")
    }
    projectOperations.putIfAbsent(projectPath, operation)
  }

  @Synchronized
  fun projectOperation(projectPath: String): ProjectWriteOperation? =
    requireHealthy(projectPath).let { projectOperations[projectPath] }

  @Synchronized
  fun requireSuiteOperation(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) {
    requireHealthy(projectPath)
    val actual = suiteOperations[SuiteKey(projectPath, suite)]
    require(actual == expected) {
      "expected '$projectPath:$suite' to select $expected, but invocation state is " +
          (actual?.toString() ?: "read-only")
    }
  }

  @Synchronized
  fun recordSuiteConsumed(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) {
    require(expected != BaselineWriteOperation.CHECK) {
      "CHECK is not a state-changing operation to consume"
    }
    requireHealthy(projectPath)
    val key = SuiteKey(projectPath, suite)
    val selected = suiteOperations[key]
    require(selected == expected) {
      "cannot record '$projectPath:$suite' consuming $expected: selected operation is " +
          (selected?.toString() ?: "read-only")
    }
    val previous = consumedSuiteOperations[key]
    require(previous == null || previous == expected) {
      "'$projectPath:$suite' already consumed $previous, cannot also consume $expected"
    }
    consumedSuiteOperations[key] = expected
  }

  @Synchronized
  fun requireSuiteConsumed(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) {
    requireHealthy(projectPath)
    val actual = consumedSuiteOperations[SuiteKey(projectPath, suite)]
    require(actual == expected) {
      "expected '$projectPath:$suite' to consume $expected, but invocation state is " +
          (actual?.toString() ?: "not consumed")
    }
  }

  @Synchronized
  fun requireProjectOperation(projectPath: String, expected: ProjectWriteOperation) {
    requireHealthy(projectPath)
    val actual = projectOperations[projectPath]
    require(actual == expected) {
      "expected '$projectPath' to select $expected, but invocation state is " +
          (actual?.toString() ?: "read-only")
    }
  }

  @Synchronized
  fun recordProjectConsumed(projectPath: String, expected: ProjectWriteOperation) {
    requireHealthy(projectPath)
    val selected = projectOperations[projectPath]
    require(selected == expected) {
      "cannot record '$projectPath' consuming $expected: selected operation is " +
          (selected?.toString() ?: "read-only")
    }
    val previous = consumedProjectOperations[projectPath]
    require(previous == null || previous == expected) {
      "'$projectPath' already consumed $previous, cannot also consume $expected"
    }
    consumedProjectOperations[projectPath] = expected
  }

  @Synchronized
  fun prepareProjectWrites(
    projectPath: String,
    expected: ProjectWriteOperation,
    writes: List<PreparedMutationWrite>,
    readTrees: List<BaselineFiles.TreeSnapshot>,
  ) {
    requireHealthy(projectPath)
    require(projectOperations[projectPath] == expected) {
      "cannot prepare '$projectPath' writes for $expected: selected operation is " +
          (projectOperations[projectPath]?.toString() ?: "read-only")
    }
    require(projectPath !in preparedProjectWrites) {
      "'$projectPath' already prepared a mutation-record commit in this invocation"
    }
    require(readTrees.map { it.rootPath }.distinct().size == readTrees.size) {
      "'$projectPath' prepared duplicate mutation-record read roots"
    }
    preparedProjectWrites[projectPath] = PreparedMutationCommit(
        writes.toList(), readTrees.map { it.copy(entries = it.entries.toList()) })
  }

  @Synchronized
  fun requirePreparedProjectWrites(
    projectPath: String,
    expected: ProjectWriteOperation,
  ): PreparedMutationCommit {
    requireHealthy(projectPath)
    require(projectOperations[projectPath] == expected) {
      "expected '$projectPath' to select $expected before committing prepared writes"
    }
    return preparedProjectWrites[projectPath]?.let { prepared ->
      prepared.copy(
          writes = prepared.writes.toList(),
          readTrees = prepared.readTrees.map { it.copy(entries = it.entries.toList()) },
      )
    } ?: throw IllegalArgumentException(
        "expected '$projectPath' to prepare $expected, but the comparison task did not complete")
  }

  @Synchronized
  fun requireProjectConsumed(projectPath: String, expected: ProjectWriteOperation) {
    requireHealthy(projectPath)
    val actual = consumedProjectOperations[projectPath]
    require(actual == expected) {
      "expected '$projectPath' to consume $expected, but invocation state is " +
          (actual?.toString() ?: "not consumed")
    }
  }

  @Synchronized
  fun hasStateChangingOperation(projectPath: String): Boolean =
    requireHealthy(projectPath).let {
      suiteOperations.keys.any { it.projectPath == projectPath } ||
          projectPath in projectOperations
    }

  @Synchronized
  fun descriptions(projectPath: String): List<String> = buildList {
    requireHealthy(projectPath)
    suiteOperations.entries
      .filter { it.key.projectPath == projectPath }
      .sortedBy { it.key.suite }
      .forEach { add("${it.key.suite}:${it.value.name.lowercase()}") }
    projectOperations[projectPath]?.let { add(it.description) }
  }
}

/** Gradle-wide invocation service wrapping [HardeningOperationRegistry]. */
abstract class HardeningOperationSession : BuildService<BuildServiceParameters.None> {
  private val registry = HardeningOperationRegistry()

  internal fun requestSuite(
    projectPath: String,
    suite: String,
    operation: BaselineWriteOperation,
  ) = registry.requestSuite(projectPath, suite, operation)

  internal fun suiteOperation(projectPath: String, suite: String): BaselineWriteOperation =
    registry.suiteOperation(projectPath, suite)

  internal fun requestModeFlipInsurance(projectPath: String) =
    registry.requestModeFlipInsurance(projectPath)

  internal fun requestProject(projectPath: String, operation: ProjectWriteOperation) =
    registry.requestProject(projectPath, operation)

  internal fun reject(projectPath: String, reason: String): Nothing =
    registry.reject(projectPath, reason)

  internal fun projectOperation(projectPath: String): ProjectWriteOperation? =
    registry.projectOperation(projectPath)

  internal fun requireSuiteOperation(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) = registry.requireSuiteOperation(projectPath, suite, expected)

  internal fun recordSuiteConsumed(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) = registry.recordSuiteConsumed(projectPath, suite, expected)

  internal fun requireSuiteConsumed(
    projectPath: String,
    suite: String,
    expected: BaselineWriteOperation,
  ) = registry.requireSuiteConsumed(projectPath, suite, expected)

  internal fun requireProjectOperation(projectPath: String, expected: ProjectWriteOperation) =
    registry.requireProjectOperation(projectPath, expected)

  internal fun recordProjectConsumed(projectPath: String, expected: ProjectWriteOperation) =
    registry.recordProjectConsumed(projectPath, expected)

  internal fun prepareProjectWrites(
    projectPath: String,
    expected: ProjectWriteOperation,
    writes: List<PreparedMutationWrite>,
    readTrees: List<BaselineFiles.TreeSnapshot>,
  ) = registry.prepareProjectWrites(projectPath, expected, writes, readTrees)

  internal fun requirePreparedProjectWrites(
    projectPath: String,
    expected: ProjectWriteOperation,
  ): PreparedMutationCommit = registry.requirePreparedProjectWrites(projectPath, expected)

  internal fun requireProjectConsumed(projectPath: String, expected: ProjectWriteOperation) =
    registry.requireProjectConsumed(projectPath, expected)

  internal fun modeFlipInsuranceRequested(projectPath: String): Boolean =
    registry.modeFlipInsuranceRequested(projectPath)

  internal fun hasStateChangingOperation(projectPath: String): Boolean =
    registry.hasStateChangingOperation(projectPath)

  internal fun descriptions(projectPath: String): List<String> = registry.descriptions(projectPath)
}

/**
 * Execution-time preflight for discoverable state-changing tasks.
 *
 * The public writer and the PIT/compare task both depend on this untracked task, and
 * the producer is ordered after it. The request therefore reaches the build service
 * before PIT decides whether history is allowed and before verification chooses a
 * write transition. Keeping this as a typed task avoids reintroducing a script-action
 * capture in the precompiled convention script.
 */
@UntrackedTask(because = "Publishes an invocation-local operation to a build service")
internal abstract class HardeningOperationRequestTask : DefaultTask() {

  @get:Input
  abstract val hardeningProjectPath: Property<String>

  @get:Input
  @get:Optional
  abstract val suiteName: Property<String>

  @get:Input
  abstract val request: Property<HardeningWriteRequest>

  @get:Input
  abstract val presentIncompatibleProperties: ListProperty<String>

  @get:Input
  abstract val excludedTaskNames: ListProperty<String>

  @get:ServiceReference("hardeningOperationSession")
  abstract val operationSession: Property<HardeningOperationSession>

  @get:ServiceReference("hardeningCertificationSession")
  abstract val certificationSession: Property<HardeningCertificationSession>

  init {
    presentIncompatibleProperties.convention(emptyList())
    excludedTaskNames.convention(emptyList())
  }

  @TaskAction
  fun publishRequest() {
    val projectPath = hardeningProjectPath.get()
    fun refuse(reason: String): Nothing {
      try {
        operationSession.get().reject(projectPath, reason)
      } catch (e: IllegalArgumentException) {
        throw GradleException("${path}: $reason", e)
      }
    }

    val incompatible = presentIncompatibleProperties.get()
    if (incompatible.isNotEmpty()) {
      refuse(
          "${request.get().displayName} requires full, unscoped evidence; remove " +
              incompatible.sorted().joinToString { "-P$it" })
    }
    val exclusions = excludedTaskNames.get()
    if (exclusions.isNotEmpty()) {
      refuse(
          "a baseline-writing workflow cannot prove its complete task graph with " +
              "task exclusion(s): " + exclusions.sorted().joinToString { "-x $it" })
    }

    if (certificationSession.get().isActive(projectPath)) {
      refuse(
          "a state-changing hardening task cannot run inside hardeningCertify; " +
              "run the workflows in separate Gradle invocations")
    }

    val selected = request.get()
    val baselineOperation = selected.baselineOperation
    if (baselineOperation != null) {
      val suite = suiteName.orNull ?: throw GradleException(
          "${path}: ${selected.displayName} has no mutation suite")
      try {
        operationSession.get().requestSuite(projectPath, suite, baselineOperation)
      } catch (e: IllegalArgumentException) {
        throw GradleException("${path}: ${e.message}", e)
      }
    } else {
      try {
        operationSession.get().requestProject(
            projectPath,
            checkNotNull(selected.projectOperation) {
              "${selected.name} has neither a suite nor a project operation"
            },
        )
      } catch (e: IllegalArgumentException) {
        throw GradleException("${path}: ${e.message}", e)
      }
    }
    logger.lifecycle("${path}: selected ${selected.displayName}; record writes require fresh evidence")
  }
}

/**
 * Postcondition on a public writer task. Dependency exclusions cannot turn a named
 * state-changing workflow into a misleading green no-op: the writer succeeds only
 * when its exact request was selected, consumed by the implementation task, and
 * the invocation was never poisoned.
 */
@UntrackedTask(because = "Checks invocation-local writer state after its workflow")
internal abstract class HardeningOperationCompletionTask : DefaultTask() {

  @get:Input
  abstract val hardeningProjectPath: Property<String>

  @get:Input
  @get:Optional
  abstract val suiteName: Property<String>

  @get:Input
  abstract val request: Property<HardeningWriteRequest>

  @get:ServiceReference("hardeningOperationSession")
  abstract val operationSession: Property<HardeningOperationSession>

  @TaskAction
  fun requireCompletedRequest() {
    val selected = request.get()
    try {
      selected.baselineOperation?.let { expected ->
        val suite = suiteName.orNull ?: throw GradleException(
            "${path}: ${selected.displayName} has no mutation suite")
        operationSession.get().requireSuiteConsumed(
            hardeningProjectPath.get(), suite, expected)
      } ?: operationSession.get().requireProjectConsumed(
          hardeningProjectPath.get(), checkNotNull(selected.projectOperation))
    } catch (e: IllegalArgumentException) {
      throw GradleException(
          "${path}: ${selected.displayName} did not complete its required workflow — " +
              "${e.message}", e)
    }
  }
}

/** Stable text assembly for the installed-version help task. */
internal object HardeningHelpText {
  fun render(suiteNames: List<String>, fuzzTargetNames: List<String>): String = buildString {
    val suitePrefixes = suiteNames.sorted().map {
      "pitest" + it.replaceFirstChar(Char::uppercase)
    }
    val fuzzPrefixes = fuzzTargetNames.sorted().map {
      "fuzz" + it.replaceFirstChar(Char::uppercase)
    }
    val suiteDebtTasks = suitePrefixes.map { prefix ->
      "${prefix}Debt" to "inspect committed records and latest full-report debt without running PIT"
    }
    val optionSpellings = HardeningOptionNames.descriptors.map { option ->
      "-P${option.name}" + (option.value?.let { "=<$it>" } ?: "")
    }
    val generatedNames = buildList {
      addAll(suiteDebtTasks.map { it.first })
      suitePrefixes.forEach { prefix ->
        add("${prefix}BaselineRebase")
        add("${prefix}BaselineUpdate")
        add("${prefix}BaselineUnion")
        add("${prefix}BaselinePrune")
        add("${prefix}TimeoutAuditInit")
      }
      add("pitestModeCompareUnion")
      fuzzPrefixes.forEach { prefix ->
        add(prefix)
        add("${prefix}Minimize")
      }
      addAll(optionSpellings)
      addAll(HardeningOptionNames.removedWriterTaskByProperty.keys.map { "-P$it" })
    }
    val generatedColumnWidth = maxOf(
        40,
        (generatedNames.maxOfOrNull { it.length } ?: 0) + 2,
    )
    fun appendGenerated(name: String, purpose: String) {
      append("  ")
      append(name.padEnd(generatedColumnWidth))
      appendLine(purpose)
    }

    appendLine("Hardening tasks (installed plugin version)")
    appendLine()
    appendLine("Read-only and certification workflows:")
    appendLine("  qualityGate                       tests plus every registered mutation suite")
    appendLine("  hardeningCertify                  fresh full release certification")
    appendLine("  pitestConverge                    compare two fresh observations")
    appendLine("  pitestModeSnapshot / pitestModeCompare  compare labeled execution modes")
    appendLine("  pitestMutatorTrial                measure candidate mutators")
    appendLine("  mutationOwnershipAudit            cheap whole-production owner/exclusion preflight")
    appendLine("  hardeningInit / hardeningAgentTemplate / hardeningAgentTemplateDiff")
    appendLine("                                    scaffold and compare local operator rules")
    suiteDebtTasks.forEach { (name, purpose) -> appendGenerated(name, purpose) }
    appendLine()
    appendLine("Accepted-baseline document lifecycle (timeout audit sets retain their stable unversioned format):")
    appendLine("  migrateMutationBaselines          stamp substantive accepted baselines; remove empty placeholders")
    appendLine("  downgradeMutationBaselines        remove schema 1 from substantive baselines; empty placeholders stay absent")
    suitePrefixes.forEach { prefix ->
      appendGenerated(
          "${prefix}BaselineRebase",
          "safely adopt a reviewed PIT/toolchain transition")
      appendGenerated(
          "${prefix}BaselineUpdate",
          "report rewrite; keep current timeout/flip-insurance evidence")
      appendGenerated("${prefix}BaselineUnion", "append newly observed rows as untriaged debt")
      appendGenerated("${prefix}BaselinePrune", "apply the reviewed shrink-only candidate set")
      appendGenerated(
          "${prefix}TimeoutAuditInit",
          "seed the suite timeout audit (uncertifiable until classified)")
    }
    appendGenerated("pitestModeCompareUnion", "annotate/write observed mode-flip insurance")
    if (fuzzTargetNames.isNotEmpty()) {
      appendLine()
      appendLine("Fuzz workflows:")
      appendLine("  fuzzAll                           run every registered local target with explicit budget/concurrency; durable receipt in .pitest-history/")
      fuzzPrefixes.forEach { prefix ->
        appendGenerated(prefix, "run the target")
        appendGenerated("${prefix}Minimize", "minimize its committed corpus")
      }
    }
    appendLine()
    appendLine("Gradle properties:")
    HardeningOptionNames.descriptors.zip(optionSpellings).forEach { (option, spelling) ->
      appendGenerated(spelling, option.purpose)
    }
    appendLine()
    appendLine("Removed writer properties (refused since sava-build 21.5.22):")
    HardeningOptionNames.removedWriterTaskByProperty.forEach { (property, task) ->
      appendGenerated("-P$property", "use $task")
    }
    appendLine("Named tasks are the only supported committed-file write interface.")
  }
}

@UntrackedTask(because = "Prints installed-version task and option documentation")
internal abstract class HardeningHelpTask : DefaultTask() {
  @get:Input
  abstract val suiteNames: ListProperty<String>

  @get:Input
  abstract val fuzzTargetNames: ListProperty<String>

  init {
    suiteNames.convention(emptyList())
    fuzzTargetNames.convention(emptyList())
  }

  @TaskAction
  fun printHelp() {
    logger.quiet(HardeningHelpText.render(suiteNames.get(), fuzzTargetNames.get()))
  }
}
