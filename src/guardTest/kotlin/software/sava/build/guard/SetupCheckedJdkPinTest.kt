package software.sava.build.guard

import java.nio.file.Files
import java.nio.file.Path
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
    val action = Files.readString(Path.of(".github/actions/setup-checked-jdk/action.yml"))
    assertEquals(1, count(action, pin),
      "oracle-actions/setup-java moved: read its action.yml for the JDK it runs Download.java on, point " +
        "downloader-jdk.sh at that JDK, then update this pin")
    val downloader = Files.readString(Path.of(".github/actions/setup-checked-jdk/downloader-jdk.sh"))
    // The script's own assignments, not the prose around them, which names the variables too.
    assertTrue(downloader.contains("home=\"\${JAVA_HOME_21_X64:-}\"") && downloader.contains("home=\"\${JAVA_HOME_21_arm64:-}\""),
      "downloader-jdk.sh no longer checks the JDK oracle-actions/setup-java v1.5.0 downloads with")
    assertEquals(1, count(action, "distrust.sh\""), "the wrapper no longer distrusts the roots in the OS store")
    assertEquals(1, count(action, "downloader-jdk.sh\""), "the wrapper no longer checks the runner JDK before the download")
    assertEquals(1, count(action, "release-floor.sh\" \"\$RELEASE\""), "the wrapper no longer refuses a release below the floor")
    assertEquals(1, count(action, "check.sh\" \"\$JAVA_HOME\""), "the wrapper no longer checks the installed JDK")
    // Distrust, the runner-JDK check, the install, the installed-JDK check, in that order.
    val order = listOf("distrust.sh\"", "downloader-jdk.sh\"", pin, "check.sh\" \"\$JAVA_HOME\"").map { action.indexOf(it) }
    assertEquals(order.sorted(), order, "the wrapper must distrust and check the runner JDK before the install, and check the installed JDK after it")
    // The website is a host name by the one rule, spelled in the wrapper and in the root action.
    val hostName = "^[A-Za-z0-9.-]+$"
    assertTrue(action.contains(hostName) && Files.readString(Path.of("action.yml")).contains(hostName),
      "the wrapper and the root action must check the download site against the same host-name rule")
  }

  @Test
  fun `the floor refused by name is the release check-sh compiles with`() {
    val check = Files.readString(Path.of(".github/actions/check-jdk-trust/check.sh"))
    val floor = Files.readString(Path.of(".github/actions/check-jdk-trust/release-floor.sh"))
    assertTrue(check.contains(" --release 21 "), "check.sh no longer compiles at --release 21: move release-floor.sh's floor with it")
    assertTrue(floor.contains("\nfloor=21\n"), "release-floor.sh's floor is not the 21 check.sh compiles with")
  }

  @Test
  fun `the root action installs both JDKs through the same pin with the same checks around them`() {
    val root = Files.readString(Path.of("action.yml"))
    assertEquals(2, count(root, pin),
      "action.yml installs Gradle's JDK and the build's through the pinned oracle-actions/setup-java; a bump " +
        "moves with the wrapper's pin and this test")
    assertEquals(1, count(root, "distrust-compromised-roots/distrust.sh\""), "action.yml no longer distrusts the roots in the OS store")
    assertEquals(1, count(root, "setup-checked-jdk/downloader-jdk.sh\""), "action.yml no longer checks the runner JDK before the downloads")
    assertEquals(2, count(root, "check-jdk-trust/check.sh\" \"\$JAVA_HOME\""), "action.yml no longer checks both installed JDKs")
    assertEquals(1, count(root, "release-floor.sh\" \"\$JAVA_VERSION\" \"\$GRADLE_JAVA_VERSION\""),
      "action.yml no longer refuses a version below the floor for both JDKs")
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
