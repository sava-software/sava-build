package software.sava.build.guard

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The reusable job that runs the jlink functional tests on a JDK without jmods, held to the
 * checkout and Gradle setup pins the root action uses, to a SHA pin on actions/setup-java, its one
 * install, to one actions/cache pin for its restore and save, to saving from main only, to
 * refusing a bad version by name through input-shape.sh before that install downloads, since the
 * action's own message names no input, and to telling the test JVM to hold the premise; and its
 * three callers, every check of the plugin, to calling it.
 */
class JlinkWithoutJmodsWorkflowTest {

  private val workflow = Files.readString(guardFile(".github/workflows/gradle_plugin_jlink_without_jmods.yml"))

  private fun pins(text: String, action: String): Set<String> =
    Regex("uses: " + Regex.escape(action) + "@([0-9a-f]{40})").findAll(text).map { it.groupValues[1] }.toSet()

  @Test
  fun `the job runs on the root action's checkout and Gradle setup pins`() {
    val root = Files.readString(guardFile("action.yml"))
    for (action in listOf("actions/checkout", "gradle/actions/setup-gradle")) {
      val here = pins(workflow, action)
      assertEquals(1, here.size, "$action pinned once, by SHA, in the jmods-less workflow: $here")
      assertEquals(pins(root, action), here, "$action pinned as the root action pins it")
    }
    assertEquals(1, pins(workflow, "actions/setup-java").size, "actions/setup-java, the one install, pinned once by SHA")
  }

  @Test
  fun `the cache is restored and saved under one pin and saved from main only`() {
    val restore = pins(workflow, "actions/cache/restore")
    assertEquals(1, restore.size, "one actions/cache/restore pin: $restore")
    assertEquals(restore, pins(workflow, "actions/cache/save"), "actions/cache/save at its restore's pin")
    assertTrue(workflow.contains("      - name: Save TestKit caches\n        if: github.ref == 'refs/heads/main'\n"),
      "the save must run on main only: a pull request's or a tag's entry is scoped to it")
  }

  @Test
  fun `the version is refused by name through input-shape-sh before the download`() {
    val refuse = workflow.indexOf("input-shape.sh --all \"\" \"\" \"gradle-java-version (vars.GRADLE_JAVA_VERSION)\" \"\$GRADLE_JAVA_VERSION\"")
    val install = workflow.indexOf("uses: actions/setup-java@")
    assertTrue(refuse >= 0, "the job must run input-shape.sh --all, with an empty site label, over the Gradle version")
    assertTrue(install > refuse, "the refusal must come before the actions/setup-java install")
  }

  @Test
  fun `the test JVM is told to hold the premise`() {
    assertTrue(workflow.contains("          SAVA_BUILD_EXPECT_NO_JMODS: '1'\n"),
      "the jlink tests step must set SAVA_BUILD_EXPECT_NO_JMODS, which JlinkFeatureFunctionalTest holds the test JVM to")
  }

  @Test
  fun `every check of the plugin calls the job`() {
    for (caller in listOf("gradle_plugin_build.yml", "gradle_plugin_check_pr.yml", "gradle_plugin_release_check.yml")) {
      val text = Files.readString(guardFile(".github/workflows/$caller"))
      assertTrue(text.contains("\n    uses: ./.github/workflows/gradle_plugin_jlink_without_jmods.yml\n"), "$caller must call the jmods-less job")
    }
  }
}
