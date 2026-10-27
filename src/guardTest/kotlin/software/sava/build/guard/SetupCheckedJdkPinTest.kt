package software.sava.build.guard

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * setup-checked-jdk checks the JDK that oracle-actions/setup-java downloads with before letting it
 * download, and that JDK is the action's own choice: JAVA_HOME_21_X64, or JAVA_HOME_21_arm64, at
 * the pinned commit (its action.yml, "Run action with Java 21"; earlier releases used 17 and 11).
 * downloader-jdk.sh mirrors the choice by hand, so a bump of the pin, by dependabot or anyone, is
 * taken only after reading the new action.yml and moving the script with it, then this test. The
 * PR check skips dependabot's pull requests, so for those this test fires on main after the merge,
 * where a red check holds release-please until the script and the pin agree again. The root action
 * (action.yml) installs twice through the same pin with the wrapper's scripts around the installs,
 * run from its own checkout so that a caller pinned by SHA stays pinned; it is held to the same pin,
 * the same scripts, to running the OS-store and runner-JDK checks before its checkout, and to a
 * check.sh after each install under the install's own condition. The Java floor both actions refuse
 * by name (release-floor.sh) is the --release check.sh compiles with.
 */
class SetupCheckedJdkPinTest {

  private val pin = "oracle-actions/setup-java@fff43251af9936a0e6a4d5d0946e14f1680e9b6b"

  private fun count(text: String, needle: String) = text.split(needle).size - 1

  @Test
  fun `oracle-actions setup-java is pinned at the commit whose downloader JDK the script checks`() {
    val action = Files.readString(guardFile(".github/actions/setup-checked-jdk/action.yml"))
    assertEquals(1, count(action, pin),
      "oracle-actions/setup-java moved: read its action.yml for the JDK it runs Download.java on, point " +
        "downloader-jdk.sh at that JDK, then update this pin")
    val downloader = Files.readString(guardFile(".github/actions/setup-checked-jdk/downloader-jdk.sh"))
    // The script's own assignments, not the prose around them, which names the variables too.
    assertTrue(downloader.contains("home=\"\${JAVA_HOME_21_X64:-}\"") && downloader.contains("home=\"\${JAVA_HOME_21_arm64:-}\""),
      "downloader-jdk.sh no longer checks the JDK oracle-actions/setup-java v1.5.0 downloads with")
    assertEquals(1, count(action, "distrust.sh\""), "the wrapper no longer distrusts the roots in the OS store")
    assertEquals(1, count(action, "downloader-jdk.sh\""), "the wrapper no longer checks the runner JDK before the download")
    assertEquals(1, count(action, "input-shape.sh\" --all \\\n"), "the wrapper no longer refuses a malformed site or release, or one below the floor, by name")
    assertTrue(action.contains("(vars.JDK_SRC)\" \"\$WEBSITE\" \\\n") && action.contains("as the step names it)\" \"\$RELEASE\"\n"),
      "the wrapper must pass its website and release to input-shape.sh, each after the label of the input it comes from")
    assertEquals(1, count(action, "check.sh\" \"\$JAVA_HOME\""), "the wrapper no longer checks the installed JDK")
    // The shape check, distrust, the runner-JDK check, the install, the installed-JDK check, in that order.
    val order = listOf("input-shape.sh\"", "distrust.sh\"", "downloader-jdk.sh\"", pin, "check.sh\" \"\$JAVA_HOME\"").map { action.indexOf(it) }
    assertEquals(order.sorted(), order, "the wrapper must distrust and check the runner JDK before the install, and check the installed JDK after it")
    // The site is a host name and the release a single release by one rule each, spelled once, in
    // input-shape.sh, which the wrapper and the root action both call and neither restates.
    val root = Files.readString(guardFile("action.yml"))
    val shape = Files.readString(guardFile(".github/actions/check-jdk-trust/input-shape.sh"))
    assertTrue(shape.contains("site_re='^[A-Za-z0-9.-]+$'") && shape.contains("release_re='^[A-Za-z0-9._-]+$'"),
      "input-shape.sh must spell the host-name and single-release rules")
    for ((name, text) in listOf("the wrapper" to action, "the root action" to root)) {
      assertTrue(!text.contains("[A-Za-z0-9.-]+") && !text.contains("[A-Za-z0-9._-]+"), "$name must not restate input-shape.sh's rules")
    }
  }

  @Test
  fun `the self-test runs the root action from the checkout with two different versions at or above the floor`() {
    val workflow = Files.readString(guardFile(".github/workflows/action_self_test.yml"))
    assertTrue(workflow.contains("uses: ./\n"), "action_self_test.yml must run the root action from the checkout")
    // Each version is one job-level literal, read on its own line, not from a comment quoting a
    // value, and the install takes both from there.
    fun version(name: String) = Regex("^\\s+$name: '(\\d+)'$", RegexOption.MULTILINE).find(workflow)?.groupValues?.get(1)?.toInt()
      ?: error("action_self_test.yml sets no numeric $name")
    val build = version("BUILD_JDK")
    val gradle = version("GRADLE_JDK")
    assertTrue(workflow.contains("default-java-version: \${{ env.BUILD_JDK }}") && workflow.contains("gradle-java-version: \${{ env.GRADLE_JDK }}"),
      "action_self_test.yml must pass BUILD_JDK and GRADLE_JDK to the root action")
    // The compile floor, read from release-floor.sh as the test above does; whether the download
    // site still serves a version is the workflow's own run to tell, which is why it also runs on
    // a schedule.
    val floor = releaseFloor()
    assertTrue(build >= floor && gradle >= floor && build != gradle,
      "the self-test must install two different versions at or above the floor $floor, not $build and $gradle")
    assertTrue(workflow.contains("\n  schedule:\n"),
      "action_self_test.yml must also run on a schedule: a version the download site stops serving fails it")
  }

  @Test
  fun `the floor refused by name is the release check-sh compiles with, as the prose says`() {
    val floor = releaseFloor()
    val check = Files.readString(guardFile(".github/actions/check-jdk-trust/check.sh"))
    assertTrue(check.contains(" --release $floor ") && check.contains("a JDK below $floor"),
      "check.sh no longer compiles at --release $floor, release-floor.sh's floor, or no longer says so: move the two together")
    val floorScript = Files.readString(guardFile(".github/actions/check-jdk-trust/release-floor.sh"))
    assertTrue(floorScript.contains("--release $floor"), "release-floor.sh's own header must name the --release $floor it stands for")
    // The prose that promises the floor moves with it: the root action's description and its two
    // version inputs, the wrapper's step name and release description, the README.
    val root = Files.readString(guardFile("action.yml"))
    val wrapper = Files.readString(guardFile(".github/actions/setup-checked-jdk/action.yml"))
    val readme = Files.readString(guardFile("README.md"))
    assertTrue(
      root.contains("Java $floor is the floor") &&
        root.contains("description: \"Default Java version, $floor or later") &&
        root.contains("description: \"Java version Gradle runs on, $floor or later"),
      "action.yml's description and its two version inputs must name the floor $floor"
    )
    assertTrue(wrapper.contains("a release below $floor") && wrapper.contains("$floor or later"),
      "setup-checked-jdk's step name and release description must name the floor $floor")
    assertTrue(readme.contains("Java $floor is the floor"), "the README must name the floor $floor")
  }

  @Test
  fun `the root action installs both JDKs through the same pin with the same checks around them`() {
    val root = Files.readString(guardFile("action.yml"))
    assertEquals(2, count(root, pin),
      "action.yml installs Gradle's JDK and the build's through the pinned oracle-actions/setup-java; a bump " +
        "moves with the wrapper's pin and this test")
    assertEquals(1, count(root, "distrust-compromised-roots/distrust.sh\""), "action.yml no longer distrusts the roots in the OS store")
    assertEquals(1, count(root, "setup-checked-jdk/downloader-jdk.sh\""), "action.yml no longer checks the runner JDK before the downloads")
    assertEquals(2, count(root, "check-jdk-trust/check.sh\" \"\$JAVA_HOME\""), "action.yml no longer checks both installed JDKs")
    // Twice: by shape before anything runs, for the inputs that are present (--present), and after
    // the checkout for what the version step worked out, which may have come from a fork's
    // gradle/sava.properties, every value required then (--all).
    assertEquals(1, count(root, "input-shape.sh\" --present \\\n"), "action.yml no longer refuses a malformed input before the pre-checkout steps")
    assertEquals(1, count(root, "input-shape.sh\" --all \\\n"), "action.yml no longer refuses a malformed or empty value after the version step")
    val shapeStep = root.indexOf("- name: Refuse a malformed input")
    assertTrue(shapeStep > 0 && shapeStep < root.indexOf("distrust.sh\""), "the inputs must be refused by shape before the pre-checkout steps")
    assertEquals(2, count(root, "(vars.JDK_SRC)\" \"\$JDK_SRC\" \\\n"), "both calls must pass the site after its label")
    assertEquals(2, count(root, "(vars.GRADLE_JAVA_VERSION)\" \"\$GRADLE_JAVA_VERSION\"\n"), "both calls must pass the Gradle version after its label")
    // The build version only once it is worked out: a tag or branch may supersede the input.
    assertTrue(!root.contains("\$DEFAULT_JAVA_VERSION\" \\\n") && root.contains("in gradle/sava.properties)\" \"\$JAVA_VERSION\" \\\n"),
      "the version step, not the shape step, must pass the build version after its label")
    // git fetches the checkout over the OS store, so the distrust and the runner check come first.
    val checkout = root.indexOf("uses: actions/checkout@")
    assertTrue(checkout > 0 && root.indexOf("downloader-jdk.sh\"") < checkout && root.indexOf("distrust.sh\"") < checkout,
      "the OS-store distrust and the runner-JDK check must precede the checkout")
    // Each check.sh follows its own install: pin, check, pin, check, in that order, and the Gradle
    // JDK's install and check share one condition, which the org.gradle.java.home write shares too.
    val installs = Regex.escape(pin).toRegex().findAll(root).map { it.range.first }.toList()
    val checks = Regex.escape("check-jdk-trust/check.sh\" \"\$JAVA_HOME\"").toRegex().findAll(root).map { it.range.first }.toList()
    assertTrue(installs[0] < checks[0] && checks[0] < installs[1] && installs[1] < checks[1],
      "action.yml must check each JDK right after installing it: install, check, install, check")
    val gradleJdkOnly = "if: \${{ steps.java-version.outputs.gradle-java-version != steps.java-version.outputs.java-version }}"
    assertEquals(3, count(root, gradleJdkOnly),
      "the Gradle JDK's install, its check and its org.gradle.java.home write must share the one condition")
    // The install's `if:` follows its `uses:` line and the check's precedes its `run:` line, so both
    // lie between the first pin and the first check; the write's is the one after the check.
    assertEquals(2, count(root.substring(installs[0], checks[0]), gradleJdkOnly),
      "the Gradle JDK's install and its check must both carry the condition")
    assertEquals(1, count(root.substring(checks[0], installs[1]), gradleJdkOnly),
      "the org.gradle.java.home write for the Gradle JDK must carry the condition")
  }
}
