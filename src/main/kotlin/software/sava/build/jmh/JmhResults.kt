package software.sava.build.jmh

import java.io.File

/**
 * JMH's text result table as 'software.sava.build.feature.jmh' reads it: the rows with their
 * parameter columns, and the merge of archived runs.
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
}
