package software.sava.build.jmh

import me.champeau.jmh.JMHTask
import me.champeau.jmh.JmhParameters
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.StandardOutputListener
import org.gradle.api.provider.Provider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The conventions 'software.sava.build.feature.jmh' lays over the JMH Gradle plugin: the run
 * defaults of the 'jmh' extension, each overridable per invocation by the Gradle property of the
 * same camel-cased name, and the 'jmh' task's archive of every run that wrote a row. Nothing of
 * the feature outside this file names a type of the plugin, so the feature links with or without
 * the plugin on its classpath: JmhPluginRequirement loads this file's classes once the plugin is there.
 */
internal object JmhConventions {

  fun configure(project: Project) {
    // Gradle Actions take their target as the receiver here (the kotlin-dsl plugin's SAM-with-receiver).
    project.tasks.named("jmh", JMHTask::class.java) { configureRun(project, this) }
    project.extensions.configure("jmh", Action<JmhParameters> { configureDefaults(project, this) })
  }

  private fun configureRun(project: Project, task: JMHTask) {
    // Benchmarks are measurements, not build artifacts: never let Gradle skip
    // them as UP-TO-DATE because a results file from a prior run exists.
    task.outputs.upToDateWhen { false }
    // The plugin rewrites its results file with only the benchmarks the run
    // selected, which would make a '-PjmhIncludes' subset run discard every
    // other benchmark's numbers. Instead, each run that writes a row has its
    // table archived timestamped under <project>/jmh-results/ — measurement
    // history, kept outside build/ so 'clean' cannot erase it — and the task's
    // results file is then re-rendered as the merge of every archived run,
    // chronological, so the newest row wins per benchmark, mode and parameter
    // values, under a header that keeps JMH's parameter columns (in the default
    // text format: a run in another format is archived whole and its file left as
    // JMH wrote it). Delete archive files to drop stale rows.
    val archiveDir = project.layout.projectDirectory.dir("jmh-results").asFile
    // JMH's working directory, against which a relative path in --jmhArgs resolves.
    val workingDir = project.layout.projectDirectory.asFile
    // The run's console output, captured here while it still prints live: JMH runs
    // with its own fail-on-error off (unless -PjmhFailFast=true), so every benchmark
    // runs, and the output says which attempts failed (a '<failure>' in a warmup or
    // measurement iteration, a fork that died or could not start). Those attempts'
    // rows, a late failure's short row included, are left out of the archive (in
    // the default text format; another format is archived whole), the passing
    // benchmarks' rows are kept, and the task fails once the run is over (a warning
    // under -PjmhFailOnError=false). The output is saved as output.txt beside the
    // task's results file, never under jmh-results/, whose stamped copies of that
    // file are the archives the merge reads.
    val console = StringBuilder()
    val consoleListener = StandardOutputListener { console.append(it) }
    val failOnError = project.providers.gradleProperty("jmhFailOnError").map(String::toBoolean).orElse(true)
    // JMH ends a run that only lists (its -l, -lp, -lprof and -lrf flags, which the
    // plugin's jmhOptions and --jmhArgs can pass) before it writes any results, and
    // a results file this run did not rewrite is the previous scoreboard, not a run
    // to archive: the file's state before the run is kept beside the task and compared.
    // The file, its format and the report are the run's (JmhRunOutputs): --jmhArgs may
    // have moved them with -rff, -rf or -o, which the plugin lets replace its own values.
    val resultsBefore = task.temporaryDir.resolve("results-before.txt")
    fun JMHTask.runOutputs(): JmhRunOutputs =
      JmhRunOutputs.of(jmhArgs.orNull, resultsFile.get().asFile, resultFormat.orNull, humanOutputFile.orNull?.asFile, workingDir)
    task.doFirst {
      console.setLength(0)
      logging.addStandardOutputListener(consoleListener)
      resultsBefore.parentFile.mkdirs()
      resultsBefore.writeText(stateOf((this as JMHTask).runOutputs().results))
    }
    task.doLast {
      logging.removeStandardOutputListener(consoleListener)
      val run = this as JMHTask
      archiveRun(run.logger, run.resultsFile.get().asFile, run.runOutputs(), console, resultsBefore, archiveDir, failOnError.get())
    }
  }

  private fun configureDefaults(project: Project, jmh: JmhParameters) {
    val providers = project.providers
    fun stringProperty(name: String, defaultValue: String): Provider<String> =
      providers.gradleProperty(name).orElse(defaultValue)
    fun intProperty(name: String, defaultValue: Int): Provider<Int> =
      providers.gradleProperty(name).map(String::toInt).orElse(defaultValue)

    // Every default below can be overridden per invocation with the Gradle
    // property of the same camel-cased name, e.g.:
    //   ../gradlew jmh -PjmhFork=3 -PjmhIncludes=enumValues,kindDispatch

    // The JMH harness version, resolved for jmh-core and the bytecode generator:
    // The default comes from gradle/libs.versions.toml. A harness change is a
    // measurement change, so a consumer records in its benchmark notes
    // where the rows archived under <project>/jmh-results/ move from one harness
    // to the other; the override re-measures against the old one.
    jmh.jmhVersion.set(stringProperty("jmhVersion", JmhToolDefaults.JMH))
    // Quick-look default; decision-grade comparisons need '-PjmhFork=3' or
    // more — single-fork scores swing 10-20% on JIT inlining luck alone — and
    // isolation from other load.
    jmh.fork.set(intProperty("jmhFork", 1))
    // Benchmark-name regex filter, comma separated: -PjmhIncludes=enumValues,kindDispatch
    // Joined into one alternation because the champeau plugin passes the
    // includes list to JMH comma-joined as a single regex, where a literal
    // comma matches nothing.
    jmh.includes.addAll(
      providers.gradleProperty("jmhIncludes")
        .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
        .map { if (it.isEmpty()) it else listOf(it.joinToString("|")) }
        .getOrElse(emptyList())
    )
    jmh.warmupIterations.set(intProperty("jmhWarmupIterations", 5))
    jmh.warmup.set(stringProperty("jmhWarmup", "1s"))
    // Enough measurement iterations that a single noisy iteration (thermal or
    // background activity) cannot dominate the reported confidence interval.
    jmh.iterations.set(intProperty("jmhIterations", 8))
    jmh.timeOnIteration.set(stringProperty("jmhTimeOnIteration", "1s"))
    // JMH's own stop at the first benchmark error, -PjmhFailFast=true: JMH then
    // exits non-zero before it writes a results file, so the task fails ahead of
    // the archive step above, which archives nothing and saves no output. Off by
    // default: the feature lets every benchmark run and fails the task at the end
    // (-PjmhFailOnError, above). (Plugin 0.7.3 and earlier passed this to JMH as
    // '-foe 1', which JMH reads as false, so no failure ever reached the task;
    // 0.7.4 passes 'true'.)
    jmh.failOnError.set(providers.gradleProperty("jmhFailFast").map(String::toBoolean).orElse(false))
    // Replicate the long-running-service JVM sava deployments run: compact
    // object headers (product since JDK 25), generational ZGC, a pinned
    // pre-touched heap, and no hsperfdata jitter. '-PjmhJvmArgsAppend=...'
    // (space separated) replaces this list wholesale; benchmark projects can
    // instead append via 'jmh { jvmArgsAppend.addAll(...) }'.
    jmh.jvmArgsAppend.set(
      providers.gradleProperty("jmhJvmArgsAppend")
        .map { it.split(' ').map(String::trim).filter(String::isNotEmpty) }
        .orElse(
          listOf(
            "-XX:+UseCompactObjectHeaders",
            "-Xms2g", "-Xmx2g",
            "-XX:+AlwaysPreTouch",
            "-XX:+PerfDisableSharedMem",
            "-XX:+UseZGC"
          )
        )
    )
  }
}

private fun stateOf(results: File): String =
  if (results.isFile) "${results.lastModified()} ${results.length()}" else "absent"

private fun sameFile(left: File, right: File): Boolean = left.canonicalFile == right.canonicalFile

/**
 * The archive step of a finished 'jmh' run: saves the run's report, keeps the passing rows of
 * a run that wrote results, archives the table and re-renders the scoreboard, then fails the
 * task for the attempts that failed (a warning when [fails] is false). The archive and the
 * scoreboard are [taskResults]', the task's results file: a run that --jmhArgs sent elsewhere
 * with -rff is checked and reported like any other, but its table is left where it went, as
 * JMH wrote it, since an archive keyed by the file's name would join or shadow the series of
 * a file that shares it.
 */
private fun archiveRun(
  logger: Logger,
  taskResults: File,
  outputs: JmhRunOutputs,
  captured: CharSequence,
  resultsBefore: File,
  archiveDir: File,
  fails: Boolean,
) {
  val results = outputs.results
  // Whether the run rewrote its results file, read before anything is written beside it: a run
  // that did not (a listing run, JMH's help) has nothing to check or archive, and the previous
  // scoreboard is never acted on.
  val written = results.isFile && stateOf(results) != resultsBefore.takeIf { it.isFile }?.readText()
  // The run's report: the file JMH wrote when its -o is set (the plugin's humanOutputFile, or
  // --jmhArgs), since JMH then prints nothing to the console, otherwise the capture. Saved as
  // output.txt beside the task's results file whichever file the run wrote, under another name
  // when the run's results file is that very file (--jmhArgs=-rff pointing at it).
  val report = outputs.report?.takeIf { it.isFile }?.readText() ?: captured.toString()
  val output = taskResults.resolveSibling("output.txt").let { if (sameFile(it, results)) it.resolveSibling("report.txt") else it }
  output.parentFile.mkdirs()
  output.writeText(report)
  val attempts = JmhResults.attempts(report)
  val failed = attempts.filter { it.failure != null }
  fun fail(message: String) {
    if (fails) throw GradleException(message) else logger.warn(message)
  }
  // A run that wrote results but whose report shows no benchmark section (JMH's verbosity
  // SILENT, or its output redirected somewhere the feature cannot read) cannot be checked
  // for failures, even when its table has no row: nothing of it is archived.
  val unreadable = "JMH wrote results but its report shows no benchmark run, so the feature cannot tell which " +
    "benchmarks failed (JMH's verbosity SILENT, its output redirected, or a log format the feature does not read?): the run is not archived. " +
    "The report the feature read is at $output"
  if (!sameFile(results, taskResults)) {
    // Sent elsewhere with -rff: the attempts the report shows are checked whether or not the file
    // was rewritten (-rff /dev/null discards the table and is a run all the same), and the table,
    // wherever it went, is left as JMH wrote it.
    when {
      attempts.isNotEmpty() -> if (failed.isNotEmpty()) fail(JmhResults.failureMessage(attempts, output, fails, JmhResults.RowFate.LEFT))
      written -> fail(unreadable)
    }
    return
  }
  if (!written) return
  val textFormat = outputs.textFormat
  // The archives of this results file: its name, a yyyyMMdd-HHmmss stamp in UTC, the format's
  // extension. Another file's archives (a renamed resultsFile), another format's and a
  // hand-kept table stay out of the merge, which would otherwise take them in name order.
  val prefix = "${results.nameWithoutExtension}-"
  val suffix = ".${outputs.archiveExtension}"
  val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  fun stampOf(name: String): LocalDateTime? =
    if (name.startsWith(prefix) && name.endsWith(suffix)) {
      runCatching { LocalDateTime.parse(name.removePrefix(prefix).removeSuffix(suffix), stamp) }.getOrNull()
    } else null
  // Each archive with its stamp, in name order (stamped names: lexicographic == chronological).
  fun archives(): List<Pair<File, LocalDateTime>> = (archiveDir.listFiles() ?: emptyArray())
    .filter { it.isFile }
    .mapNotNull { file -> stampOf(file.name)?.let { file to it } }
    .sortedBy { it.first.name }
  // Merging only understands the default text format. Re-rendered after every run that
  // wrote results, archived or not, so a run whose every attempt failed, or one whose
  // failures could not be read, leaves the scoreboard in results.txt and not JMH's table;
  // with no archived row to show, results.txt keeps its header alone.
  fun renderScoreboard() {
    if (!textFormat) return
    val archives = archives().map { it.first }
    val merged = JmhResults.merge(archives)
    if (merged != null) {
      results.writeText(merged)
      logger.lifecycle("JMH results merged from {} archived runs into {}", archives.size, results)
    } else {
      val header = results.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("Benchmark") }
      results.writeText((header ?: "Benchmark  Mode  Cnt  Score  Error  Units") + "\n")
    }
  }
  if (attempts.isEmpty()) {
    renderScoreboard()
    fail(unreadable)
    return
  }
  if (textFormat && failed.isNotEmpty()) {
    val kept = try {
      JmhResults.withoutFailedRows(results.readText(), attempts)
    } catch (e: IllegalStateException) {
      // The run is not archived; the scoreboard is re-rendered so results.txt does not keep
      // JMH's table with the failed attempts' rows. This fails whatever -PjmhFailOnError says.
      renderScoreboard()
      throw GradleException("${e.message}; the run is not archived and the jmh task fails. The run's output is at $output", e)
    }
    results.writeText(kept)
  }
  // A table without a row (every attempt failed) adds nothing to the merge; in another
  // format, which is archived whole, a run archives when an attempt produced a result.
  if (if (textFormat) JmhResults.parse(results.readText()).rows.isNotEmpty() else attempts.any { it.hasResult }) {
    archiveDir.mkdirs()
    // The names must stay chronological, since the merge below sorts by them:
    // a run in the same second as the newest archive, or after the clock has
    // stepped back, takes the second after that archive's instead of its own
    // (compared at whole seconds, the names' precision). The stamp is UTC, so
    // archives from machines in different zones order as the runs did. Archives
    // stamped in local time before this stay ordered through the same clamp on the
    // checkout that holds them; the names carry no zone, so archives combined
    // across checkouts through git interleave for one zone offset after the change.
    val newest = archives().maxOfOrNull { it.second }
    var at = LocalDateTime.now(ZoneOffset.UTC).withNano(0)
    if (newest != null && !at.isAfter(newest)) {
      at = newest.plusSeconds(1)
    }
    results.copyTo(archiveDir.resolve("$prefix${at.format(stamp)}$suffix"))
  }
  renderScoreboard()
  if (failed.isNotEmpty()) {
    fail(JmhResults.failureMessage(attempts, output, fails, if (textFormat) JmhResults.RowFate.FILTERED else JmhResults.RowFate.ARCHIVED))
  }
}
