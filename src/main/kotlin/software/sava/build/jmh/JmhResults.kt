package software.sava.build.jmh

import java.io.File

/**
 * JMH's text result table and console output, as 'software.sava.build.feature.jmh' reads them:
 * the table's rows with their parameter columns, the merge of archived runs, and the run's
 * console sections, which say which benchmark attempts failed.
 */
object JmhResults {

  /** JMH's short mode labels, the table's Mode column, by the long label its console prints. */
  private val MODES = mapOf(
    "Throughput, ops/time" to "thrpt",
    "Average time, time/op" to "avgt",
    "Sampling time" to "sample",
    "Single shot invocation time" to "ss",
  )

  /**
   * A data row of the text table. [name] is the benchmark as JMH densified it for the table
   * (the shared package prefix dropped or abbreviated), with a ':label' suffix on a secondary
   * result; [params] holds the row's parameter values by name, without the 'N/A' cells JMH
   * prints where a row lacks a parameter another row has.
   */
  data class Row(
    val name: String,
    val params: Map<String, String>,
    val mode: String,
    val cnt: String,
    val score: String,
    val error: String,
    val units: String,
  ) {
    /** The primary benchmark name, without a secondary result's ':label'. */
    val base: String get() = name.substringBefore(':')

    /** What newest-wins merges on: name, mode and the parameter values, whatever column order an archive used. */
    val key: Key get() = Key(name, mode, params)

    /** The parameters as JMH's '# Parameters:' line prints them, keys in order, for pairing a row with its run. */
    val paramsText: String get() = params.toSortedMap().entries.joinToString(", ") { "${it.key} = ${it.value}" }
  }

  /** A row's identity across archives; ordered by name, then parameters, then mode, for the merged table. */
  data class Key(val name: String, val mode: String, val params: Map<String, String>) : Comparable<Key> {
    override fun compareTo(other: Key): Int {
      name.compareTo(other.name).let { if (it != 0) return it }
      val mine = params.toSortedMap().entries.toList()
      val theirs = other.params.toSortedMap().entries.toList()
      for (i in 0 until minOf(mine.size, theirs.size)) {
        mine[i].key.compareTo(theirs[i].key).let { if (it != 0) return it }
        mine[i].value.compareTo(theirs[i].value).let { if (it != 0) return it }
      }
      mine.size.compareTo(theirs.size).let { if (it != 0) return it }
      return mode.compareTo(other.mode)
    }
  }

  data class Table(val paramNames: List<String>, val rows: List<Row>)

  /** The header's parameter columns: each '(name)' token and the column it ends at, the field's right edge. */
  private class Header(val names: List<String>, val ends: List<Int>)

  private val token = Regex("""\S+""")

  /**
   * Parses a text table. The header line names the parameter columns, '(name)' each, between
   * 'Benchmark' and 'Mode'. JMH right-aligns each parameter header and its values to the same
   * column, so a value is the text between the previous column's right edge and its own,
   * which keeps a value that contains spaces whole. After the parameters, a row holds the
   * mode, the count, the score, the error after a '±', and the units; JMH omits the count for
   * single-iteration runs and the error whenever the count is below 3. Lines that are not
   * rows are skipped.
   */
  fun parse(text: String): Table {
    var header = Header(emptyList(), emptyList())
    val rows = mutableListOf<Row>()
    for (line in text.lineSequence()) {
      rows += parseRow(line, header) { header = it } ?: continue
    }
    return Table(header.names, rows)
  }

  private fun parseRow(line: String, header: Header, onHeader: (Header) -> Unit): Row? {
    if (line.isBlank()) return null
    val tokens = token.findAll(line).toList()
    if (tokens[0].value == "Benchmark") {
      val columns = tokens.drop(1).takeWhile { it.value.length > 2 && it.value.startsWith("(") && it.value.endsWith(")") }
      onHeader(Header(columns.map { it.value.substring(1, it.value.length - 1) }, columns.map { it.range.last + 1 }))
      return null
    }
    val name = tokens[0]
    var from = name.range.last + 1
    val params = LinkedHashMap<String, String>()
    header.names.forEachIndexed { i, column ->
      val to = minOf(header.ends[i], line.length)
      if (to <= from) return null
      // A blank cell is an empty parameter value; only 'N/A' marks a parameter the row lacks.
      val value = line.substring(from, to).trim()
      if (value != "N/A") params[column] = value
      from = to
    }
    val rest = token.findAll(line.substring(minOf(from, line.length))).map { it.value }.toList()
        // JMH prints a score below 0.0005 as '≈ 10⁻ⁿ' or '≈ 0': one score, two tokens.
        .fold(mutableListOf<String>()) { acc, t -> if (acc.lastOrNull() == "≈") acc[acc.size - 1] = "≈ $t" else acc += t; acc }
    if (rest.size < 3 || rest[0] !in MODES.values) return null
    val mid = rest.subList(1, rest.size - 1)
    val pm = mid.indexOf("±")
    val (cnt, score, error) = if (pm >= 0) {
      Triple(if (pm > 1) mid[0] else "", mid[pm - 1], "± ${mid[pm + 1]}")
    } else {
      Triple(if (mid.size > 1) mid[0] else "", mid.last(), "")
    }
    return Row(name.value, params, rest[0], cnt, score, error, rest.last())
  }

  /**
   * The newest-wins merge of archived tables, given in chronological order: one row per
   * benchmark, mode and parameter values, from the newest archive that has it, under a header
   * that keeps every parameter column the archives used, first seen first, with 'N/A' where a
   * row lacks one, as JMH's own summary prints. Null when no archive holds a row.
   */
  fun merge(archives: List<File>): String? {
    val rows = sortedMapOf<Key, Row>()
    val paramNames = LinkedHashSet<String>()
    for (archive in archives) {
      val table = parse(archive.readText())
      paramNames += table.paramNames
      table.rows.forEach { rows[it.key] = it }
    }
    if (rows.isEmpty()) return null
    val names = paramNames.toList()
    val cells = mutableListOf(listOf("Benchmark") + names.map { "($it)" } + listOf("Mode", "Cnt", "Score", "Error", "Units"))
    rows.values.forEach { row ->
      cells += listOf(row.name) + names.map { row.params[it] ?: "N/A" } + listOf(row.mode, row.cnt, row.score, row.error, row.units)
    }
    val widths = IntArray(cells[0].size) { column -> cells.maxOf { it[column].length } }
    return cells.joinToString(separator = "\n", postfix = "\n") { row ->
      row.withIndex().joinToString(separator = "  ") { (column, cell) ->
        if (column == 0) cell.padEnd(widths[0]) else cell.padStart(widths[column])
      }.trimEnd()
    }
  }

  /**
   * One benchmark run from the console output: a '# Benchmark:' section, with the mode its
   * preamble names ('# Benchmark mode:', as the table's Mode column spells it; null for a
   * label the feature does not know), its '# Parameters:' when the benchmark has any,
   * [failure] when the section shows a failed iteration (warmup or measurement, '<failure>'
   * on the iteration's line or pushed onto its own by the benchmark's output), a VM that
   * exited early ('<failure: ...>'), or a fork that died or could not be started (those
   * markers land on the iteration's line too), and [hasResult] when JMH produced a result
   * for it, which is a row in the table: a 'Result' block, or in single-shot and sampling
   * modes, whose block needs three samples, a measured iteration; a fork that died or
   * exited early discards the benchmark's results, so none. A section that measured without
   * a result or a failure, under a measurement count above zero, is a failure the feature
   * does not recognise, and is reported as one rather than passed. [paramsText] is the
   * '# Parameters:' line's content as printed, which pairs the run with its table row.
   */
  data class Attempt(
    val benchmark: String,
    val mode: String?,
    val params: Map<String, String>,
    val failure: String?,
    val hasResult: Boolean,
    val paramsText: String = params.entries.joinToString(", ") { "${it.key} = ${it.value}" },
  ) {
    val label: String
      get() = (if (params.isEmpty()) benchmark else benchmark + params.entries.joinToString(prefix = " (", postfix = ")") { "${it.key} = ${it.value}" }) +
        (if (mode == null) "" else " [$mode]")
  }

  private val iterationLine = Regex("""^(# Warmup )?Iteration\s+(\d+):\s?(.*)$""")
  private val debugPrefix = Regex("""^\S+ \[[A-Z]+\] \[([^\]]+)\] """)
  private val forkMarkers = listOf("<failure:", "<forked VM failed", "<failed to invoke the VM", "<host VM has been interrupted", "<binary link had failed")

  /** The runs the console output shows, in order. A section ends at the next one or at '# Run complete'. */
  fun attempts(console: String): List<Attempt> {
    val out = mutableListOf<Attempt>()
    var pendingMode: String? = null
    var pendingMeasures = true
    var benchmark: String? = null
    var mode: String? = null
    var measures = true
    var params: Map<String, String> = emptyMap()
    var paramsText = ""
    var failure: String? = null
    var forkDied = false
    var iteration = ""
    var measured = false
    var shot = false
    var warmupFork = false
    var resultBlock = false
    fun flush() {
      if (shot) measured = true
      shot = false
      warmupFork = false
      benchmark?.let {
        val hasResult = !forkDied && (resultBlock || ((mode == "ss" || mode == "sample") && measured))
        val outcome = failure ?: if (measures && !hasResult) "ended without a result or a failure the feature recognises" else null
        out += Attempt(it, mode, params, outcome, hasResult, paramsText)
      }
      benchmark = null
      params = emptyMap()
      paramsText = ""
      failure = null
      forkDied = false
      iteration = ""
      measured = false
      resultBlock = false
    }
    for (raw in console.lineSequence()) {
      // Under Gradle's --debug every captured line carries its log prefix; JMH's own lines are
      // the forwarded standard output, Gradle's own logging is dropped.
      val prefixed = debugPrefix.find(raw)
      val line = if (prefixed == null) raw else if (prefixed.groupValues[1] == "system.out") raw.substring(prefixed.range.last + 1) else continue
      when {
        line.startsWith("# Benchmark mode: ") -> pendingMode = MODES[line.removePrefix("# Benchmark mode: ").trim()]
        line.startsWith("# Measurement: ") -> pendingMeasures = !line.contains("<none>")
        line.startsWith("# Benchmark: ") -> {
          flush()
          benchmark = line.removePrefix("# Benchmark: ").trim()
          mode = pendingMode
          measures = pendingMeasures
        }
        line.startsWith("# Run complete") -> flush()
        benchmark == null -> {}
        line.startsWith("# Parameters: ") -> {
          paramsText = line.removePrefix("# Parameters: ").trim().removePrefix("(").removeSuffix(")")
          params = parseParameters(paramsText)
        }
        line.startsWith("Result \"") -> resultBlock = true
        else -> {
          val it = iterationLine.find(line)
          // A measurement iteration's shot counts once the iteration ended without a failure:
          // at the next iteration line, at a fork line or at the section's end. A warmup
          // fork's shots never reach the results (JMH keeps the measured forks' only).
          if (it != null || line.startsWith("# Fork:") || line.startsWith("# Warmup Fork:")) {
            if (shot) measured = true
            shot = false
            if (line.startsWith("# Warmup Fork:")) warmupFork = true else if (line.startsWith("# Fork:")) warmupFork = false
          }
          if (it != null) {
            iteration = (if (it.groupValues[1].isEmpty()) "iteration " else "warmup iteration ") + it.groupValues[2]
            // Whatever shares the iteration's line, the benchmark's own output included: a marker clears the shot below.
            if (!warmupFork && it.groupValues[1].isEmpty()) shot = true
          }
          // The markers can share the iteration's line, which JMH prints without a newline, and
          // a failure ends the iteration it lands in without a result, whatever the benchmark
          // printed on that line.
          val marker = forkMarkers.firstOrNull { line.contains(it) }
          if (marker != null || line.contains("<failure")) shot = false
          if (marker != null) {
            forkDied = true
            if (failure == null) failure = line.substring(line.indexOf(marker) + 1).trim().removeSuffix(">")
          } else if (failure == null && line.contains("<failure")) {
            failure = "failed in $iteration".trimEnd()
          }
        }
      }
    }
    flush()
    return out
  }

  /** 'k1 = v1, k2 = v2', split before each ', name = ' for the label; a value holding that text pairs by [Attempt.paramsText]. */
  private fun parseParameters(inner: String): Map<String, String> {
    if (inner.isBlank()) return emptyMap()
    val params = LinkedHashMap<String, String>()
    for (pair in inner.split(Regex(""", (?=[A-Za-z_$][A-Za-z0-9_$]* = )"""))) {
      val eq = pair.indexOf(" = ")
      if (eq > 0) params[pair.substring(0, eq)] = pair.substring(eq + 3)
    }
    return params
  }

  /**
   * JMH's own shortening of benchmark names for the table (ClassUtils.denseClassNames):
   * the leading all-lowercase segments every name shares are dropped, or abbreviated to
   * their first letter when the first name's own leading lowercase run is longer than the
   * shared prefix, so the result depends on the names' order. JMH computes it over the
   * names of the benchmarks that produced a result, in its result order (mode, benchmark,
   * parameters, which is the console's run order), each with its secondary results'
   * ':label' names; the caller passes the same.
   */
  fun denseNames(names: Collection<String>): Map<String, String> {
    if (names.isEmpty()) return emptyMap()
    var prefix = emptyList<String>()
    var first = true
    var prefixCut = false
    for (name in names) {
      val segments = name.split('.')
      if (first) {
        prefix = segments.takeWhile { it.lowercase() == it }
        first = false
        continue
      }
      var c = 0
      while (c < minOf(prefix.size, segments.size)) {
        val n = segments[c]
        if (n != prefix[c] || n.lowercase() != n) break
        c++
      }
      if (prefix.size != c) prefixCut = true
      prefix = prefix.take(c)
    }
    val replaced = prefix.map { if (prefixCut) it.substring(0, 1) else "" }
    return names.associateWith { name ->
      val segments = name.split('.').toMutableList()
      replaced.forEachIndexed { i, r -> segments[i] = r }
      segments.filter { it.isNotEmpty() }.joinToString(".")
    }
  }

  /**
   * The table without the rows of the attempts that failed. Every attempt with a result must
   * pair with exactly one primary row and every primary row with exactly one such attempt, by
   * dense name, mode and the parameters as JMH prints them, keys in order, so a value holding
   * ', ' or ' = ' or nothing pairs too (a secondary ':label' row follows its primary);
   * otherwise the pairing is not understood and this throws rather than archive a short row.
   * The secondary labels the table carries are added to every benchmark's name for the dense
   * names, as JMH adds each result's own; the two differ only when a benchmark's class and
   * method names are both all lowercase and only some benchmarks carry a label.
   */
  fun withoutFailedRows(table: String, attempts: List<Attempt>): String {
    val withResult = attempts.filter { it.hasResult }
    val parsed = parse(table)
    val labels = parsed.rows.filter { it.name.contains(':') }.map { it.name.substringAfter(':') }.toSet()
    val dense = denseNames(withResult.flatMap { a -> listOf(a.benchmark) + labels.map { "${a.benchmark}:$it" } }.distinct())
    var header = Header(emptyList(), emptyList())
    val kept = mutableListOf<String>()
    val matched = HashMap<Attempt, Int>()
    var primaryRows = 0
    val lines = table.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
    for (line in lines) {
      val row = parseRow(line, header) { header = it }
      if (row == null) {
        kept += line
        continue
      }
      val candidates = withResult.filter { dense[it.benchmark] == row.base && it.paramsText == row.paramsText && (it.mode == null || it.mode == row.mode) }
      check(candidates.size == 1) {
        "JMH table row '${row.name}' ${row.mode} ${row.params} matches ${candidates.size} of the run's attempts with a result " +
          "(${withResult.map { it.label }}): the feature cannot tell which rows the failed attempts posted"
      }
      val attempt = candidates.single()
      if (!row.name.contains(':')) {
        primaryRows++
        matched.merge(attempt, 1, Int::plus)
      }
      if (attempt.failure == null) kept += line
    }
    check(primaryRows == withResult.size && withResult.all { matched[it] == 1 }) {
      "JMH produced a result for ${withResult.size} attempts (${withResult.map { it.label }}) but the table has " +
        "$primaryRows primary rows, matched as ${matched.mapKeys { it.key.label }}: the feature cannot tell which rows the failed attempts posted"
    }
    return kept.joinToString(separator = "\n", postfix = "\n")
  }

  /** What became of a failed attempt's row, named in [failureMessage]. */
  enum class RowFate(val note: String) {
    /** The default text format: the row is left out of the archive. */
    FILTERED("its row is left out of the archive"),
    /** Another format, archived whole. */
    ARCHIVED("its row stays in the archive (only the text result format is filtered)"),
    /** A table --jmhArgs sent elsewhere with -rff, neither archived nor merged. */
    LEFT("its row stays in the table, which is left as JMH wrote it where -rff sent it"),
  }

  /** The task's failure text for a run with failed attempts, or its warning under -PjmhFailOnError=false. */
  fun failureMessage(attempts: List<Attempt>, output: File, fails: Boolean, rowFate: RowFate): String {
    val failed = attempts.filter { it.failure != null }
    val lines = failed.map { "  ${it.label}: ${it.failure}; " + if (it.hasResult) rowFate.note else "it posted no row" }
    val verdict = if (fails) {
      "so the jmh task fails now that the run is over (-PjmhFailOnError=false makes this a warning; -PjmhFailFast=true has JMH stop at the first error)"
    } else {
      "and the jmh task passes under -PjmhFailOnError=false"
    }
    return "JMH: ${failed.size} of ${attempts.size} benchmark runs failed, $verdict:\n" +
      lines.joinToString("\n") + "\nThe run's output is at $output"
  }
}
