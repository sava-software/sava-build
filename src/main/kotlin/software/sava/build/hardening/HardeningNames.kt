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
   * Validates one `excludeTestClass` glob, at whichever boundary reaches it first.
   *
   * Three rules, each following from how these records travel. They are joined into
   * PIT's one comma-separated `--excludedTestClasses` argument, so a comma inside one
   * silently becomes two globs and a blank one an empty glob. And the same joining
   * builds a line of the evidence configuration text, which is one `key=value` line
   * per setting — so a value carrying a newline writes a line of its own:
   * `targetTests` ending `|\nexcludedTestClasses=y` beside the record `com.ZTest`
   * renders exactly as `targetTests` ending `|` beside the single record
   * `y\nexcludedTestClasses=com.ZTest`, one `configurationSha256` for two suites that
   * hand PIT different arguments.
   *
   * Checked wherever a value arrives rather than at one boundary: the evidence spec
   * tasks hash the configuration without ever assembling a command, so a check living
   * only at the PIT boundary is one the evidence path skips.
   *
   * Nothing beyond this is checked. PIT's glob grammar is its own, and a plugin-side
   * opinion about which spellings are sensible would be a second grammar to keep in
   * step with it.
   */
  fun requireTestExclusionGlob(glob: String): String = requireSingleLineValue(
      "excludeTestClass glob",
      glob.also {
        if (it.isBlank()) {
          throw GradleException("excludeTestClass globs must not be blank")
        }
        if (',' in it) {
          throw GradleException(
              "excludeTestClass glob '$it' cannot contain a comma: PIT separates its exclusion " +
                  "list on commas, so this would become two globs — and the evidence " +
                  "configuration text cannot tell it apart from two records spelled that way")
        }
      })

  /**
   * Rejects the characters that would let a PIT test-selection value forge a line of
   * that text. A carriage return does to a line-splitting reader what a newline does,
   * and NUL truncates the value for anything reading it as a C string.
   *
   * Rejected rather than escaped: nothing legitimate spells a class-name pattern with
   * a line break in it, and escaping would change the canonical text for every value
   * that does not need it.
   */
  fun requireSingleLineValue(kind: String, value: String): String {
    if (value.any { it == '\n' || it == '\r' || it == '\u0000' }) {
      throw GradleException(
          "$kind cannot contain a line break or NUL: the report's configuration evidence is one " +
              "line per setting, so such a value can write a line of its own and make two " +
              "different suite configurations hash the same — '" +
              value.replace("\\", "\\\\").replace("\n", "\\n")
                  .replace("\r", "\\r").replace("\u0000", "\\0") + "'")
    }
    return value
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
