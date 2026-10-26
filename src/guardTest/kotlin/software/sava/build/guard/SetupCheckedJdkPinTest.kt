package software.sava.build.guard

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * setup-checked-jdk checks the JDK that oracle-actions/setup-java downloads with before letting it
 * download, and that JDK is the action's own choice: JAVA_HOME_21_X64, or JAVA_HOME_21_arm64, at
 * the pinned commit (its action.yml, "Run action with Java 21"; earlier releases used 17 and 11).
 * The step mirrors the choice by hand, so a bump of the pin, by dependabot or anyone, is taken
 * only after reading the new action.yml and moving the step with it, then this test. The PR check
 * skips dependabot's pull requests, so for those this test fires on main after the merge, where a
 * red check holds release-please until the step and the pin agree again.
 */
class SetupCheckedJdkPinTest {

  @Test
  fun `oracle-actions setup-java is pinned at the commit whose downloader JDK the action checks`() {
    val action = Files.readString(Path.of(".github/actions/setup-checked-jdk/action.yml"))
    assertTrue(action.contains("oracle-actions/setup-java@fff43251af9936a0e6a4d5d0946e14f1680e9b6b"),
      "oracle-actions/setup-java moved: read its action.yml for the JDK it runs Download.java on, point the " +
        "'Check the runner JDK that downloads' step at that JDK, then update this pin")
    // The step's own assignments, not the prose around them, which names the variables too.
    assertTrue(action.contains("home=\"\${JAVA_HOME_21_X64:-}\"") && action.contains("home=\"\${JAVA_HOME_21_arm64:-}\""),
      "the step no longer checks the JDK oracle-actions/setup-java v1.5.0 downloads with")
  }
}
