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
  // other benchmark's numbers. Instead, each run's raw output is archived
  // timestamped under <project>/jmh-results/ — measurement history, kept
  // outside build/ so 'clean' cannot erase it — and results.txt is
  // re-rendered as the merge of every archived run, chronological, so the
  // newest row wins per benchmark. Delete archive files to drop stale rows.
  val archiveRoot = layout.projectDirectory.dir("jmh-results")
  doLast {
    val results = resultsFile.get().asFile
    if (results.isFile) {
      val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      val archiveDir = archiveRoot.asFile
      archiveDir.mkdirs()
      results.copyTo(
          archiveDir.resolve("${results.nameWithoutExtension}-$stamp.${results.extension}"),
          overwrite = true
      )
      // Merging only understands the default text format.
      if (results.extension == "txt") {
        val archives = (archiveDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "txt" }
            .sortedBy { it.name } // timestamped names: lexicographic == chronological
        // token layout: name [param...] mode [cnt] score [± error] units —
        // JMH omits Cnt for single-iteration runs and Error whenever cnt < 3.
        // Rows key on name plus parameter values so @Param variants do not
        // overwrite each other.
        val benchModes = setOf("avgt", "thrpt", "sample", "ss")
        val rows = sortedMapOf<String, List<String>>()
        for (file in archives) {
          for (line in file.readLines()) {
            if (line.isBlank() || line.startsWith("Benchmark")) {
              continue
            }
            val tokens = line.trim().split(Regex("\\s+"))
            val modeIdx = tokens.indexOfFirst { it in benchModes }
            if (modeIdx < 1 || tokens.size < modeIdx + 3) {
              continue
            }
            val key = tokens.subList(0, modeIdx).joinToString("  ")
            val mid = tokens.subList(modeIdx + 1, tokens.size - 1)
            val pm = mid.indexOf("±")
            val (cnt, score, error) = if (pm >= 0) {
              Triple(if (pm > 1) mid[0] else "", mid[pm - 1], "± ${mid[pm + 1]}")
            } else {
              Triple(if (mid.size > 1) mid[0] else "", mid.last(), "")
            }
            rows[key] = listOf(key, tokens[modeIdx], cnt, score, error, tokens.last())
          }
        }
        if (rows.isNotEmpty()) {
          val cells = mutableListOf(listOf("Benchmark", "Mode", "Cnt", "Score", "Error", "Units"))
          cells.addAll(rows.values)
          val widths = IntArray(6) { column -> cells.maxOf { it[column].length } }
          results.writeText(
              cells.joinToString(separator = "\n", postfix = "\n") { row ->
                row.withIndex().joinToString(separator = "  ") { (column, cell) ->
                  if (column == 0) cell.padEnd(widths[0]) else cell.padStart(widths[column])
                }.trimEnd()
              }
          )
          logger.lifecycle("JMH results merged from {} archived runs into {}", archives.size, results)
        }
      }
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
  // Fail the run on the first benchmark error instead of reporting partial
  // results: @Setup cross-checks are treated as hard failures.
  failOnError = providers.gradleProperty("jmhFailOnError").map(String::toBoolean).orElse(true)
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
