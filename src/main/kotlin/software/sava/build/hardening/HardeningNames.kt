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

  fun requireJavaQualifiedName(kind: String, name: String, requirePackage: Boolean = false): String {
    if (!SourceVersion.isName(name) || (requirePackage && '.' !in name)) {
      throw GradleException(
          "$kind '$name' must be a " +
              (if (requirePackage) "dotted Java package name" else "qualified Java name"))
    }
    return name
  }
}
