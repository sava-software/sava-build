import org.gradle.api.logging.StandardOutputListener
import software.sava.build.jmh.JmhResults
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
  id("java")
  id("me.champeau.jmh")
}

// Benchmarks are measurements, not build artifacts: never let Gradle skip
// them as UP-TO-DATE because a results file from a prior run exists.
tasks.named<me.champeau.jmh.JMHTask>("jmh") {
  outputs.upToDateWhen { false }
  // The plugin rewrites its results file with only the benchmarks the run
  // selected, which would make a '-PjmhIncludes' subset run discard every
  // other benchmark's numbers. Instead, each run that writes a row has its
  // table archived timestamped under <project>/jmh-results/ — measurement
  // history, kept outside build/ so 'clean' cannot erase it — and results.txt
  // is then re-rendered as the merge of every archived run, chronological, so
  // the newest row wins per benchmark, mode and parameter values, under a header
  // that keeps JMH's parameter columns. Delete archive files to drop stale rows.
  val archiveRoot = layout.projectDirectory.dir("jmh-results")
  // The run's console output, captured here while it still prints live: JMH runs
  // with its own fail-on-error off (unless -PjmhFailFast=true), so every benchmark
  // runs, and the output says which attempts failed (a '<failure>' in a warmup or
  // measurement iteration, a fork that died or could not start). Those attempts'
  // rows, a late failure's short row included, are left out of the archive (in
  // the default text format; another format is archived whole), the passing
  // benchmarks' rows are kept, and the task fails once the run is over (a warning
  // under -PjmhFailOnError=false). The output is saved beside the results file,
  // never under jmh-results/, whose .txt files are the archives the merge reads.
  val console = StringBuilder()
  val consoleListener = StandardOutputListener { console.append(it) }
  val failOnError = providers.gradleProperty("jmhFailOnError").map(String::toBoolean).orElse(true)
  // JMH ends a run that only lists (its -l, -lp, -lprof and -lrf flags, which the
  // plugin's jmhOptions and --jmhArgs can pass) before it writes any results, and
  // a results file this run did not rewrite is the previous scoreboard, not a run
  // to archive: the file's state before the run is kept beside the task and compared.
  val resultsBefore = temporaryDir.resolve("results-before.txt")
  fun stateOf(results: File): String =
    if (results.isFile) "${results.lastModified()} ${results.length()}" else "absent"
  doFirst {
    console.setLength(0)
    logging.addStandardOutputListener(consoleListener)
    resultsBefore.parentFile.mkdirs()
    resultsBefore.writeText(stateOf(resultsFile.get().asFile))
  }
  doLast {
    logging.removeStandardOutputListener(consoleListener)
    val results = resultsFile.get().asFile
    // The run's report: the file JMH wrote when the plugin's humanOutputFile (its -o) is set,
    // since JMH then prints nothing to the console, otherwise the capture.
    val report = humanOutputFile.orNull?.asFile?.takeIf { it.isFile }?.readText() ?: console.toString()
    val output = results.resolveSibling("output.txt")
    output.parentFile.mkdirs()
    output.writeText(report)
    val written = results.isFile && stateOf(results) != resultsBefore.takeIf { it.isFile }?.readText()
    val textFormat = results.extension == "txt"
    val attempts = if (written) JmhResults.attempts(report) else emptyList()
    val failed = attempts.filter { it.failure != null }
    val archiveDir = archiveRoot.asFile
    fun archives(): List<File> = (archiveDir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.extension == "txt" }
        .sortedBy { it.name } // timestamped names: lexicographic == chronological
    // Merging only understands the default text format. Re-rendered after every run that
    // wrote results, archived or not, so a run whose every attempt failed, or one whose
    // failures could not be read, leaves the scoreboard in results.txt and not JMH's table;
    // with no archived row to show, results.txt keeps its header alone.
    fun renderScoreboard() {
      if (!textFormat) return
      val archives = archives()
      val merged = JmhResults.merge(archives)
      if (merged != null) {
        results.writeText(merged)
        logger.lifecycle("JMH results merged from {} archived runs into {}", archives.size, results)
      } else {
        val header = results.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("Benchmark") }
        results.writeText((header ?: "Benchmark  Mode  Cnt  Score  Error  Units") + "\n")
      }
    }
    val fails = failOnError.get()
    // A run that wrote results but whose report shows no benchmark section (JMH's verbosity
    // SILENT, or its output redirected somewhere the feature cannot read) cannot be checked
    // for failures, even when its table has no row: nothing of it is archived.
    if (written && attempts.isEmpty()) {
      renderScoreboard()
      val message = "JMH wrote results but its report shows no benchmark run, so the feature cannot tell which " +
        "benchmarks failed (JMH's verbosity SILENT, its output redirected, or a log format the feature does not read?): the run is not archived. " +
        "The report the feature read is at $output"
      if (fails) throw GradleException(message) else logger.warn(message)
      return@doLast
    }
    if (written && textFormat && failed.isNotEmpty()) {
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
    if (written && (if (textFormat) JmhResults.parse(results.readText()).rows.isNotEmpty() else attempts.any { it.hasResult })) {
      archiveDir.mkdirs()
      // The names must stay chronological, since the merge below sorts by them:
      // a run in the same second as the newest archive, or after the clock has
      // stepped back, takes the second after that archive's instead of its own
      // (compared at whole seconds, the names' precision).
      val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
      val prefix = "${results.nameWithoutExtension}-"
      val suffix = ".${results.extension}"
      val newest = (archiveDir.listFiles() ?: emptyArray())
          .map { it.name }
          .filter { it.startsWith(prefix) && it.endsWith(suffix) }
          .mapNotNull { runCatching { LocalDateTime.parse(it.removePrefix(prefix).removeSuffix(suffix), stamp) }.getOrNull() }
          .maxOrNull()
      var at = LocalDateTime.now().withNano(0)
      if (newest != null && !at.isAfter(newest)) {
        at = newest.plusSeconds(1)
      }
      results.copyTo(archiveDir.resolve("$prefix${at.format(stamp)}$suffix"))
    }
    if (written) renderScoreboard()
    if (failed.isNotEmpty()) {
      val message = JmhResults.failureMessage(attempts, output, fails, filtered = textFormat)
      if (fails) throw GradleException(message) else logger.warn(message)
    }
  }
}

fun stringProperty(name: String, defaultValue: String): Provider<String> =
  providers.gradleProperty(name).orElse(defaultValue)

fun intProperty(name: String, defaultValue: Int): Provider<Int> =
  providers.gradleProperty(name).map(String::toInt).orElse(defaultValue)

// Every default below can be overridden per invocation with the Gradle
// property of the same camel-cased name, e.g.:
//   ../gradlew jmh -PjmhFork=3 -PjmhIncludes=enumValues,kindDispatch
jmh {
  // The JMH harness version, resolved for jmh-core and the bytecode generator:
  // 1.37, the plugin's own default since 0.7.4, and 1.36 before this. A harness
  // change is a measurement change, so a consumer records in its benchmark notes
  // where the rows archived under <project>/jmh-results/ move from one harness
  // to the other; the override re-measures against the old one.
  jmhVersion = stringProperty("jmhVersion", "1.37")
  // Quick-look default; decision-grade comparisons need '-PjmhFork=3' or
  // more — single-fork scores swing 10-20% on JIT inlining luck alone — and
  // isolation from other load.
  fork = intProperty("jmhFork", 1)
  // Benchmark-name regex filter, comma separated: -PjmhIncludes=enumValues,kindDispatch
  // Joined into one alternation because the champeau plugin passes the
  // includes list to JMH comma-joined as a single regex, where a literal
  // comma matches nothing.
  includes.addAll(
      providers.gradleProperty("jmhIncludes")
          .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
          .map { if (it.isEmpty()) it else listOf(it.joinToString("|")) }
          .getOrElse(emptyList())
  )
  warmupIterations = intProperty("jmhWarmupIterations", 5)
  warmup = stringProperty("jmhWarmup", "1s")
  // Enough measurement iterations that a single noisy iteration (thermal or
  // background activity) cannot dominate the reported confidence interval.
  iterations = intProperty("jmhIterations", 8)
  timeOnIteration = stringProperty("jmhTimeOnIteration", "1s")
  // JMH's own stop at the first benchmark error, -PjmhFailFast=true: JMH then
  // exits non-zero before it writes a results file, so the task fails ahead of
  // the archive step above, which archives nothing and saves no output. Off by
  // default: the feature lets every benchmark run and fails the task at the end
  // (-PjmhFailOnError, above). (Plugin 0.7.3 and earlier passed this to JMH as
  // '-foe 1', which JMH reads as false, so no failure ever reached the task;
  // 0.7.4 passes 'true'.)
  failOnError = providers.gradleProperty("jmhFailFast").map(String::toBoolean).orElse(false)
  // Replicate the long-running-service JVM sava deployments run: compact
  // object headers (product since JDK 25), generational ZGC, a pinned
  // pre-touched heap, and no hsperfdata jitter. '-PjmhJvmArgsAppend=...'
  // (space separated) replaces this list wholesale; benchmark projects can
  // instead append via 'jmh { jvmArgsAppend.addAll(...) }'.
  jvmArgsAppend.set(
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
