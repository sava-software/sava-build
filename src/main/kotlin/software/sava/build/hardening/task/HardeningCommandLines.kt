package software.sava.build.hardening.task

import java.io.File

/** Pure command-line assembly kept outside Gradle task actions for direct testing. */
internal object HardeningCommandLines {

  internal object PitestVerbosity {
    const val DEFAULT = "DEFAULT"
    const val VERBOSE_NO_SPINNER = "VERBOSE_NO_SPINNER"

    private val supported = setOf(
      "SILENT",
      "QUIET",
      "QUIET_WITH_PROGRESS",
      DEFAULT,
      "NO_SPINNER",
      VERBOSE_NO_SPINNER,
      "VERBOSE",
    )

    fun normalize(value: String): String {
      val normalized = value.trim().uppercase()
      require(normalized in supported) {
        "unsupported PIT verbosity '$value' (expected ${supported.sorted().joinToString()})"
      }
      return normalized
    }
  }

  data class Pitest(
    val applicationClasspath: List<File>,
    val targetClasses: List<String>,
    val mutateOnly: String?,
    val excludedClasses: List<String>,
    val targetTests: String,
    val excludedTestClasses: List<String> = emptyList(),
    val sourceDirectories: List<File>,
    val reportDirectory: File,
    val projectBaseDirectory: File,
    val mutators: String,
    val outputFormats: List<String>,
    val timestampedReports: Boolean,
    val threads: Int,
    val minionJvmArgs: List<String>,
    val timeoutFactor: String,
    val timeoutConst: Long,
    val historyActive: Boolean,
    val historyFile: File,
    val mutationUnitSize: Int = 0,
    val verbosity: String = PitestVerbosity.DEFAULT,
  )

  fun pitest(spec: Pitest): List<String> = buildList {
    add("--classPath=" + spec.applicationClasspath.joinToString(",") { it.absolutePath })
    val scopedTargets = spec.mutateOnly?.trim().orEmpty()
    add(
      "--targetClasses=" +
        if (scopedTargets.isNotEmpty()) scopedTargets else spec.targetClasses.joinToString(",")
    )
    if (spec.excludedClasses.isNotEmpty()) {
      add("--excludedClasses=" + spec.excludedClasses.joinToString(","))
    }
    // Command-boundary defence for the same encoding hazard the suite already
    // checked. Not a return of the glob-grammar opinion this deliberately does not
    // hold: a line break or NUL is broken in a process argument whatever PIT's
    // grammar makes of the rest.
    add("--targetTests=" +
      software.sava.build.hardening.HardeningNames.requireSingleLineValue(
        "targetTests", spec.targetTests))
    // Emitted only when non-empty, like --excludedClasses above: an unconditional
    // empty argument would change every existing suite's command line and, through
    // the configuration text, its recorded evidence identity.
    if (spec.excludedTestClasses.isNotEmpty()) {
      add("--excludedTestClasses=" +
        spec.excludedTestClasses.joinToString(",", transform = ::requireTestExclusionGlob))
    }
    add("--sourceDirs=" + spec.sourceDirectories.joinToString(",") { it.absolutePath })
    add("--reportDir=${spec.reportDirectory.absolutePath}")
    add("--projectBase=${spec.projectBaseDirectory.absolutePath}")
    add("--mutators=${spec.mutators}")
    add("--outputFormats=${spec.outputFormats.joinToString(",")}")
    add("--timestampedReports=${spec.timestampedReports}")
    add("--threads=${spec.threads}")
    if (spec.minionJvmArgs.isNotEmpty()) {
      add("--jvmArgs=" + spec.minionJvmArgs.joinToString(",", transform = ::encodePitestJvmArg))
    }
    add("--timeoutFactor=${spec.timeoutFactor}")
    add("--timeoutConst=${spec.timeoutConst}")
    val verbosity = PitestVerbosity.normalize(spec.verbosity)
    if (verbosity != PitestVerbosity.DEFAULT) {
      add("--verbosity=$verbosity")
    }
    require(spec.mutationUnitSize >= 0) { "PIT mutation unit size must not be negative" }
    if (spec.mutationUnitSize > 0) {
      require(scopedTargets.isNotEmpty()) {
        "isolated PIT mutation units require a nonblank mutateOnly scope"
      }
      require(!spec.historyActive) {
        "isolated PIT mutation units require history-free execution"
      }
      add("--mutationUnitSize=${spec.mutationUnitSize}")
    }
    if (spec.historyActive) {
      if (spec.historyFile.isFile) {
        add("--historyInputLocation=${spec.historyFile.absolutePath}")
      }
      add("--historyOutputLocation=${spec.historyFile.absolutePath}")
      add("--features=+arcmutate_history")
    }
  }

  /**
   * The same rule the suite's records already passed, applied again at the boundary
   * that builds the argument. Kept as its own check rather than trusted upstream: a
   * later caller assembling this command from somewhere other than a registered
   * suite would otherwise corrupt the argument silently.
   */
  private fun requireTestExclusionGlob(glob: String): String =
    software.sava.build.hardening.HardeningNames.requireTestExclusionGlob(glob)

  /** PIT uses commas as child-argument separators and braces to quote embedded commas. */
  private fun encodePitestJvmArg(argument: String): String {
    require(argument.isNotBlank()) { "PIT minion JVM arguments must not be blank" }
    require(argument.none { it == '\u0000' || it == '\n' || it == '\r' }) {
      "PIT minion JVM arguments must be single-line values"
    }
    require(argument.none(Char::isWhitespace)) {
      "PIT minion JVM arguments cannot contain whitespace because PIT writes each value " +
        "unquoted to a Java argument file: $argument"
    }
    require(argument.startsWith('-')) {
      "PIT minion JVM arguments must be JVM options beginning with '-': $argument"
    }
    require('{' !in argument && '}' !in argument) {
      "PIT minion JVM arguments cannot contain braces (reserved by PIT's comma parser): $argument"
    }
    require(argument.none { it == '#' || it == '\'' || it == '"' || it == '\\' }) {
      "PIT minion JVM arguments cannot contain #, quotes, or backslashes because Java " +
        "argument files interpret them as syntax: $argument"
    }
    return if (',' in argument) "{$argument}" else argument
  }

  data class FuzzRun(
    val targetClass: String,
    val maxTotalTimeSeconds: Int,
    val maxLen: Int?,
    val localCorpus: File,
    val seedCorpus: File?,
  )

  fun fuzzRun(spec: FuzzRun): List<String> = buildList {
    add("--target_class=${spec.targetClass}")
    add("-max_total_time=${spec.maxTotalTimeSeconds}")
    spec.maxLen?.let { add("-max_len=$it") }
    add(spec.localCorpus.absolutePath)
    spec.seedCorpus?.let { add(it.absolutePath) }
  }

  data class FuzzMinimize(
    val targetClass: String,
    val maxLen: Int?,
    val stagingCorpus: File,
    val seedCorpus: File,
    val localCorpus: File?,
  )

  fun fuzzMinimize(spec: FuzzMinimize): List<String> = buildList {
    add("--target_class=${spec.targetClass}")
    add("-merge=1")
    spec.maxLen?.let { add("-max_len=$it") }
    add(spec.stagingCorpus.absolutePath)
    add(spec.seedCorpus.absolutePath)
    spec.localCorpus?.let { add(it.absolutePath) }
  }
}
