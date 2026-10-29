import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.sava.build.verification.VerificationPolicyRules

/**
 * This repository's own policy keeps the rules its settings plugin enforces in every consumer:
 * the guard is applied here through the rules object, since a build cannot apply the plugin it
 * is compiling to itself.
 */
class VerificationPolicySelfTest {

  @Test
  fun `this repository's policy keeps the rules`() {
    val root = savaBuildRootFile(VerificationPolicyRules.METADATA).parentFile.parentFile.canonicalFile
    val standalone = VerificationPolicyRules.standaloneBuilds(root)
    // One added later brings files the rules read, its gradle.properties and its symlinks:
    // declare them in build.gradle.kts's rootFilesReadByTests beside the policy files.
    assertEquals(emptyList<String>(), standalone, "no standalone build shares this policy")
    assertEquals(emptyList<String>(), VerificationPolicyRules.problems(root, standalone))
  }
}
