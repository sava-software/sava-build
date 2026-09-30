package software.sava.build.hardening

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where two checkouts that share one mutation class tree contend for it.
 *
 * This lock is named after the tree, so checkouts that share a relocated build
 * directory contend on one file and checkouts with their own trees do not, and it lives
 * in the Gradle user home, which no `clean` reaches. It is one of the two locks
 * `lockMutationClasses` takes: invocations that share a checkout but not a Gradle user
 * home meet on the other, under the checkout.
 */
object MutationClassTreeLock {

  /**
   * The lock file for [classTree] under [gradleUserHome]; the directory is created on
   * acquire. Throws [IOException] when the tree's canonical path cannot be resolved,
   * because a lock named after another spelling of the path guards nothing.
   */
  @Throws(IOException::class)
  fun file(gradleUserHome: File, classTree: File): File {
    val canonical = classTree.canonicalPath
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(canonical.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
    return File(gradleUserHome, "sava-hardening/locks/mutation-classes-$digest.lock")
  }
}
