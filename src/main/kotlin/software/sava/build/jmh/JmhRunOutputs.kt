package software.sava.build.jmh

import java.io.File

/**
 * Where a 'jmh' run writes its results and its report, and in which format, once the task's
 * '--jmhArgs' option has had its say. The JMH Gradle plugin passes the extension's resultFormat
 * as '-rf', the resultsFile as '-rff' and a set humanOutputFile as '-o', then lets --jmhArgs
 * replace the value of any option already present and append the rest, so a run under
 * '--jmhArgs=-rff other.json -rf json' writes elsewhere, and in another format, than the task's
 * properties say. The archive step reads these, never the properties alone.
 */
internal data class JmhRunOutputs(val results: File, val format: String, val report: File?) {

  val textFormat: Boolean get() = format.equals("text", ignoreCase = true)

  /**
   * The archive's extension for this format, as the plugin names its results file: txt for text,
   * otherwise the format's lower-case name (json, csv, scsv, latex).
   */
  val archiveExtension: String get() = if (textFormat) "txt" else format.lowercase()

  companion object {
    private val OVERRIDABLE = setOf("-rff", "-rf", "-o")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The run's outputs: [jmhArgs] (the task's --jmhArgs, space separated) over the task's
     * [resultsFile], [resultFormat] (the plugin's convention, text, when unset) and
     * [humanOutputFile]; a relative path in --jmhArgs is JMH's, resolved against the forked JVM's
     * [workingDir].
     */
    fun of(jmhArgs: String?, resultsFile: File, resultFormat: String?, humanOutputFile: File?, workingDir: File): JmhRunOutputs {
      val overrides = overrides(jmhArgs)
      return JmhRunOutputs(
        results = overrides["-rff"]?.let { resolve(it, workingDir) } ?: resultsFile,
        format = overrides["-rf"] ?: resultFormat ?: "text",
        report = overrides["-o"]?.let { resolve(it, workingDir) } ?: humanOutputFile,
      )
    }

    /**
     * The last value --jmhArgs gives each of the three options, read as the plugin reads the
     * string: trimmed of ASCII whitespace alone (Java's String.trim, so a Unicode space stays part
     * of its token, for the plugin and for JMH alike), split on ASCII whitespace, the token after
     * an option its value unless it starts with '-'.
     */
    internal fun overrides(jmhArgs: String?): Map<String, String> {
      val tokens = jmhArgs?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }?.split(WHITESPACE) ?: return emptyMap()
      val values = HashMap<String, String>()
      tokens.forEachIndexed { index, token ->
        if (token in OVERRIDABLE) {
          val value = tokens.getOrNull(index + 1)
          if (value != null && !value.startsWith("-")) values[token] = value
        }
      }
      return values
    }

    /**
     * [path] as the forked JVM resolves it: as it is when absolute, otherwise against [workingDir]
     * through java.nio, so that a rooted path without a drive ('\bench\x.txt' on Windows) goes to
     * the working directory's drive root, where JMH writes it, not under the directory.
     */
    private fun resolve(path: String, workingDir: File): File = workingDir.toPath().resolve(path).toFile()
  }
}
