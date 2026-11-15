package software.sava.build.hardening.task

import software.sava.build.hardening.Mutant
import java.io.File

/**
 * PIT release whose minion-death reporting [EarlyMinionDeaths] was read against. The rule
 * counts on one SEVERE line, one WARNING line and one RUN_ERROR row per minion PIT never
 * heard from, and on a coverage minion PIT accepted never ending in MINION_DIED; another
 * release may keep the wording and change either. Re-audit before changing.
 */
internal const val EARLY_MINION_DEATH_AUDITED_PITEST = "1.30.2"

/**
 * A PIT attempt that ended because a minion's process was gone before PIT's parent heard
 * from it. On macOS that is what a port collision looks like: PIT listens on a wildcard
 * socket at a port the system picks, the system can pick one another program holds on
 * 127.0.0.1, and the minion's connection to `localhost` then reaches that program. The
 * log shows the shape, not the cause: a minion killed before it connected looks the same
 * in the mutation phase.
 */
internal sealed interface EarlyMinionDeath {
  /** One sentence for the notice that the attempt is being replaced. */
  val description: String

  /** One clause for the end-of-build advisory row. */
  val advisory: String

  /** Value recorded in the rerun marker. */
  val phase: String

  /** The coverage minion connected somewhere, received no arguments and timed out reading. */
  data object Coverage : EarlyMinionDeath {
    override val description: String =
      "The coverage minion ended before PIT heard from it: its own read timed out and PIT " +
        "reported MINION_DIED without writing a report"
    override val advisory: String = "an early coverage-minion death"
    override val phase: String = "coverage"
  }

  /** [rows] are the report rows PIT marked RUN_ERROR, numbered as in the report. */
  data class Mutation(val rows: List<String>) : EarlyMinionDeath {
    override val description: String =
      "${rows.size} minion(s) ended before PIT heard from them ('Minion did not start or died " +
        "during analysis'), each leaving one RUN_ERROR row"
    override val advisory: String = "${rows.size} early minion death(s)"
    override val phase: String = "mutation"
  }
}

/**
 * Decides from what one finished PIT process left behind whether it was an
 * [EarlyMinionDeath]. Pure and fail-safe: anything it does not recognise, a PIT whose
 * wording changed included, is "no", and the caller then treats the attempt exactly as it
 * would have without this class.
 *
 * Only PIT's own stderr is read. PIT's logger writes there, and a line counts as PIT's own
 * ("a parent line") only when the text after the FIRST `PIT >> ` begins with the level and
 * the message. A coverage minion's stderr is relayed there too, but behind
 * `PIT >> INFO : MINION : `, so neither a minion nor a test can write a parent line. A
 * mutation minion's output is relayed, in verbose modes only, to PIT's stdout behind
 * `stdout  : ` or `stderr  : `; stdout is never read, and a line with either prefix is
 * refused as a second guard. Message substrings are matched, never the time stamp (whose
 * spelling depends on the JDK), a line number or a source file name.
 */
internal object EarlyMinionDeaths {

  /**
   * A backstop against repeating a run in which minions die en masse (a launch that fails
   * for every unit satisfies the per-death equalities too). It does not bound cost; the
   * single rerun does.
   */
  const val MAX_MUTATION_DEATHS = 5

  /**
   * The verbosities at which PIT prints the WARNING and INFO lines the rule needs. The
   * quiet ones drop them, so a run at one of those is never repeated.
   */
  private val readableVerbosities = setOf(
    HardeningCommandLines.PitestVerbosity.DEFAULT,
    "NO_SPINNER",
    "VERBOSE",
    HardeningCommandLines.PitestVerbosity.VERBOSE_NO_SPINNER,
  )

  private const val PARENT_MARK = "PIT >> "
  private const val RELAYED_MINION = "INFO : MINION : "
  private const val MINION_NEVER_HEARD = "SEVERE : Minion did not start or died during analysis"
  private const val MINION_RUN_ERROR = "WARNING : Minion exited abnormally due to RUN_ERROR"
  private const val CONNECTED_THEN_DIED = "WARNING : Error while watching child process"
  private const val WATCH_INTERRUPTED = "WARNING : interrupted while waiting for child process"
  private const val COVERAGE_MINION_DIED =
    "SEVERE : Coverage generator Minion exited abnormally due to MINION_DIED"
  private const val COVERAGE_MINION_FAILED = "Error calculating coverage. Process will exit."
  private const val MINION_READ_TIMED_OUT = "PitError: Read timed out"
  private const val SENDING = "INFO : Sending "
  private const val TESTS_SENT = "test classes to minion"
  private const val RUN_ERROR = "RUN_ERROR"

  /** Whether this host is one where the collision is known: macOS. */
  fun hostReruns(osName: String?): Boolean = osName?.startsWith("Mac", ignoreCase = true) == true

  /** Whether PIT, at this already normalized verbosity, prints the lines [classify] reads. */
  fun readableVerbosity(normalizedVerbosity: String): Boolean =
    normalizedVerbosity in readableVerbosities

  /**
   * @param exitValue the PIT process's exit value
   * @param report the attempt's `mutations.csv`, which a coverage-phase death never writes
   * @param errorLog the retained, unfiltered copy of the PIT process's stderr
   */
  fun classify(exitValue: Int, report: File, errorLog: File): EarlyMinionDeath? =
    if (exitValue == 0) mutationDeath(report, errorLog) else coverageDeath(report, errorLog)

  /**
   * Every RUN_ERROR row must be one early death and nothing else may have gone wrong:
   * as many "never heard" lines as RUN_ERROR rows, as many RUN_ERROR warnings (a minion
   * that reported its own failure adds a warning without the SEVERE line), no minion that
   * connected and then died, and no other row that is not valid evidence.
   */
  private fun mutationDeath(report: File, errorLog: File): EarlyMinionDeath? {
    if (!report.isFile || !errorLog.isFile) return null
    val runErrors = mutableListOf<String>()
    report.readLines().forEachIndexed { index, line ->
      val mutant = Mutant.parse(line)
      when {
        Mutant.malformedRow(mutant) -> return null
        mutant!!.rawStatus == RUN_ERROR -> runErrors.add("line ${index + 1}: $line")
        mutant.status?.validEvidence != true -> return null
      }
    }
    if (runErrors.isEmpty() || runErrors.size > MAX_MUTATION_DEATHS) return null
    var neverHeard = 0
    var runErrorWarnings = 0
    var other = false
    errorLog.useLines { lines ->
      lines.mapNotNull(::parentText).forEach { text ->
        when {
          text.startsWith(MINION_NEVER_HEARD) -> neverHeard++
          text.startsWith(MINION_RUN_ERROR) -> runErrorWarnings++
          text.startsWith(CONNECTED_THEN_DIED) || text.startsWith(WATCH_INTERRUPTED) -> other = true
        }
      }
    }
    return if (!other && neverHeard == runErrors.size && runErrorWarnings == runErrors.size) {
      EarlyMinionDeath.Mutation(runErrors)
    } else {
      null
    }
  }

  /**
   * Only a listener that stays silent is recognised: the minion's first read then times
   * out. One that closes or answers ends the minion another way (end of stream, a reset, a
   * corrupt stream), and a refused connection is deterministic; none of those is repeated.
   * PIT logs the "Sending" line after writing the coverage arguments to a minion it
   * accepted, and a minion PIT accepted cannot end in MINION_DIED, so its absence is a
   * second guard.
   */
  private fun coverageDeath(report: File, errorLog: File): EarlyMinionDeath? {
    if (report.exists() || !errorLog.isFile) return null
    var minionDied = false
    var minionFailed = false
    var readTimedOut = false
    var testsSent = false
    errorLog.useLines { lines ->
      lines.mapNotNull(::parentText).forEach { text ->
        when {
          text.startsWith(COVERAGE_MINION_DIED) -> minionDied = true
          text.startsWith(SENDING) && text.contains(TESTS_SENT) -> testsSent = true
          text.startsWith(RELAYED_MINION) -> {
            if (text.contains(COVERAGE_MINION_FAILED)) minionFailed = true
            if (text.contains(MINION_READ_TIMED_OUT)) readTimedOut = true
          }
        }
      }
    }
    return if (minionDied && minionFailed && readTimedOut && !testsSent) {
      EarlyMinionDeath.Coverage
    } else {
      null
    }
  }

  /** The text after the first `PIT >> `, or null when the line is not one PIT's logger wrote. */
  private fun parentText(line: String): String? {
    val at = line.indexOf(PARENT_MARK)
    if (at < 0) return null
    if (line.startsWith("stdout  : ") || line.startsWith("stderr  : ")) return null
    return line.substring(at + PARENT_MARK.length)
  }
}
