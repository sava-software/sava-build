package software.sava.build.hardening

import org.gradle.api.GradleException
import javax.lang.model.SourceVersion

/**
 * Validates user-controlled names before they become filesystem paths or generated
 * Java declarations. These helpers live in compiled plugin code so task actions can
 * call them without retaining a precompiled-script instance in the configuration
 * cache.
 */
object HardeningNames {

  private val safeName = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")

  fun isSafeName(name: String): Boolean = name.matches(safeName)

  fun requireSafeName(kind: String, name: String): String {
    if (!isSafeName(name)) {
      throw GradleException(
          "$kind name '$name' is unsafe — use a simple name which starts and ends with a letter or " +
              "digit and contains only letters, digits, '.', '_' or '-'")
    }
    return name
  }

  /**
   * Rejects the characters that would let any value forge the evidence encoding.
   *
   * The canonical configuration text is one `key=value` line per setting, so a
   * value carrying a newline writes a line of its own choosing into it. Two
   * genuinely different suite configurations can then render identically:
   * `targetTests` ending `|\nexcludedTestClasses=y` beside the record `com.ZTest`
   * produces the same three lines as `targetTests` ending `|` beside the single
   * record `y\nexcludedTestClasses=com.ZTest` — while PIT is handed different
   * arguments and only the first suite actually excludes `com.ZTest`. One
   * `configurationSha256`, two runs, so a report from either validates as evidence
   * for the other. A carriage return does the same to a reader splitting on line
   * breaks, and NUL truncates the value for anything reading it as a C string.
   *
   * Rejected rather than escaped: nothing legitimate spells a class-name pattern
   * with a line break in it, and escaping would change the canonical text for every
   * value that does not need it.
   */
  fun requireSingleLineValue(kind: String, value: String): String {
    val offending = value.filter { it == '\n' || it == '\r' || it == '\u0000' }
    if (offending.isNotEmpty()) {
      throw GradleException(
          "$kind cannot contain a line break or NUL: the report's configuration evidence is one " +
              "line per setting, so such a value can write a line of its own and make two " +
              "different suite configurations hash the same — " + renderForMessage(value))
    }
    return value
  }

  private fun renderForMessage(value: String): String = "'" + value
      .replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\u0000", "\\0") + "'"

  /**
   * One entry of a comma-joined field in that same text.
   *
   * Where [requireSingleLineValue] stops a value writing a whole line, this stops
   * one writing a neighbouring entry: a list is rendered by joining its elements
   * with commas, so `["a,b"]` and `["a", "b"]` produce the identical line while
   * every consumer that reads the elements individually — the ownership and
   * exclusion audits, the recompile's own exclusion matching — sees two different
   * configurations.
   *
   * Rejected rather than encoded unambiguously. Length-prefixing the way
   * `minionJvmArgs` is would be the general fix, but it would change the rendered
   * text for every value that never needed it, and so every recorded
   * `configurationSha256` in every consumer.
   */
  fun requireEncodableListEntry(kind: String, value: String): String {
    if (value.isBlank()) {
      throw GradleException(
          "$kind entries cannot be blank: an entry that renders as nothing leaves a list holding " +
              "one indistinguishable from an empty list, which are different configurations")
    }
    if (',' in value) {
      throw GradleException(
          "$kind cannot contain a comma: the report's configuration evidence joins this field's " +
              "entries with commas, so this entry cannot be told apart from two entries spelled " +
              "either side of it — " + renderForMessage(value))
    }
    return requireSingleLineValue(kind, value)
  }

  /**
   * Validates one `excludeTestClass` glob, at whichever boundary reaches it first.
   *
   * The rules follow from how these records travel: several are joined into PIT's
   * one comma-separated `--excludedTestClasses` argument, so a comma inside a record
   * silently becomes two globs and a blank record an empty one. The same joining
   * builds the evidence configuration text, and there the corruption is worse than a
   * wrong run — the records `a` and `b` and the single record `a,b` render to the
   * identical line, so two different suite configurations would share one
   * `configurationSha256` and a report made under either would validate as evidence
   * for the other.
   *
   * That is why this is not left to the command line alone. The evidence spec tasks
   * hash the configuration without assembling a command, so a check that lives only
   * at the PIT boundary is a check the evidence path skips.
   *
   * Nothing beyond this is checked. PIT's glob grammar is its own, and a plugin-side
   * opinion about which spellings are sensible would be a second grammar to keep in
   * step with it.
   */
  fun requireTestExclusionGlob(glob: String): String {
    if (glob.isBlank()) {
      throw GradleException("excludeTestClass globs must not be blank")
    }
    return requireEncodableListEntry("excludeTestClass glob", glob)
  }

  fun requireJavaQualifiedName(kind: String, name: String, requirePackage: Boolean = false): String {
    if (!SourceVersion.isName(name) || (requirePackage && '.' !in name)) {
      throw GradleException(
          "$kind '$name' must be a " +
              (if (requirePackage) "dotted Java package name" else "qualified Java name"))
    }
    return name
  }
}
