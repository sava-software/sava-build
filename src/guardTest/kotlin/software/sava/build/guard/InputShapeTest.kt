package software.sava.build.guard

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * input-shape.sh, the shape rule sava-build's root action and the setup-checked-jdk action hold
 * their inputs to before anything runs: the site a host name, a release a single feature release
 * number or project name at or above the floor, refused by the label of the input and never by
 * its value; a caller without a site input passes an empty label for it. The floor comes from
 * release-floor.sh, tested here too: pairs of a label and a
 * version, refused by the label when a version is below the floor the script spells.
 */
class InputShapeTest {

  private val script: Path = guardFile(".github/actions/check-jdk-trust/input-shape.sh").toAbsolutePath()

  private val floorScript: Path = guardFile(".github/actions/check-jdk-trust/release-floor.sh").toAbsolutePath()

  private fun run(vararg args: String): Run = bash(script, args.toList())

  private fun floor(vararg args: String): Run = bash(floorScript, args.toList())

  @Test
  fun `input-shape refuses by name what setup-java or the trust check cannot take`() {
    val ok = run("--all", "jdk-src (vars.JDK_SRC)", "oracle.com", "default-java-version (vars.JAVA_VERSION)", "25", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "25")
    assertEquals(0, ok.code, ok.out)
    assertEquals("", ok.out)
    // Under --present an empty value is left for later; under --all it is refused by its label.
    val present = run("--present", "jdk-src (vars.JDK_SRC)", "", "default-java-version (vars.JAVA_VERSION)", "", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "25")
    assertEquals(0, present.code, present.out)
    val all = run("--all", "jdk-src (vars.JDK_SRC)", "", "default-java-version (vars.JAVA_VERSION)", "25")
    assertEquals(1, all.code, all.out)
    assertTrue(all.out.contains("::error::jdk-src (vars.JDK_SRC) is empty"), all.out)
    val emptyVersion = run("--all", "jdk-src (vars.JDK_SRC)", "oracle.com", "default-java-version (vars.JAVA_VERSION)", "")
    assertEquals(1, emptyVersion.code, emptyVersion.out)
    assertEquals("::error::default-java-version (vars.JAVA_VERSION) is empty.\n", emptyVersion.out)
    // A caller without a site input (the jlink job on actions/setup-java) passes an empty label for
    // it; its versions are still held to the rules, and refused by name when empty.
    val noSite = run("--all", "", "", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "25")
    assertEquals(0, noSite.code, noSite.out)
    assertEquals("", noSite.out)
    val noSiteEmpty = run("--all", "", "", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "")
    assertEquals(1, noSiteEmpty.code, noSiteEmpty.out)
    assertEquals("::error::gradle-java-version (vars.GRADLE_JAVA_VERSION) is empty.\n", noSiteEmpty.out)
    // A malformed value is named by its label and never echoed, in either mode.
    for (mode in listOf("--present", "--all")) {
      val site = run(mode, "jdk-src (vars.JDK_SRC)", "oracle.com/x", "default-java-version (vars.JAVA_VERSION)", "25")
      assertEquals(1, site.code, site.out)
      assertTrue(site.out.contains("::error::jdk-src (vars.JDK_SRC) is not a host name.") && !site.out.contains("oracle.com/x"), site.out)
      val version = run(mode, "jdk-src (vars.JDK_SRC)", "oracle.com", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "25 17")
      assertEquals(1, version.code, version.out)
      assertTrue(version.out.contains("::error::gradle-java-version (vars.GRADLE_JAVA_VERSION) is not a single release") && !version.out.contains("25 17"), version.out)
    }
    // The floor, through release-floor.sh, with the same labels.
    val floor = run("--present", "jdk-src (vars.JDK_SRC)", "", "default-java-version (vars.JAVA_VERSION)", "${releaseFloor() - 1}")
    assertEquals(1, floor.code, floor.out)
    assertTrue(floor.out.contains("is below ${releaseFloor()}") && floor.out.contains("it comes from default-java-version (vars.JAVA_VERSION)"), floor.out)
    // Misuse is its own exit.
    assertEquals(2, run("--maybe", "a", "b").code)
    assertEquals(2, run("--all", "jdk-src", "oracle.com", "lonely-label").code)
    assertEquals(2, run("--all", "", "oracle.com", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "25").code, "a site value under an empty label")
  }

  @Test
  fun `release-floor refuses a leading number below the floor and leaves the rest to setup-java`() {
    // The floor as the script spells it, so a move of the floor moves this test with it.
    val floor = releaseFloor()
    val below = floor - 1
    val above = floor + 4
    // Pairs of a label and a version; an empty label names no input.
    for (refused in listOf(listOf("", "$below"), listOf("", "${below}u"), listOf("", "$below.0.2"), listOf("", "$above", "", "$below-ea"), listOf("", "${floor - 4}"))) {
      val run = floor(*refused.toTypedArray())
      assertEquals(1, run.code, "$refused -> ${run.out}")
      assertTrue(run.out.contains("::error::Java version '") && run.out.contains("is below $floor"), run.out)
      assertEquals(1, run.out.lines().count { it.startsWith("::error::") }, run.out)
      assertFalse(run.out.contains("it comes from"), run.out)
    }
    for (accepted in listOf(listOf("", "$floor"), listOf("", "$above", "", "$above"), listOf("", "loom"), listOf("", ""), emptyList())) {
      val run = floor(*accepted.toTypedArray())
      assertEquals(0, run.code, "$accepted -> ${run.out}")
      assertEquals("", run.out)
    }
    // The one error line names the input a version comes from when the caller labels it; a label
    // may hold '=' or anything else, since nothing is parsed out of it.
    val named = floor("build", "$above", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "$below")
    assertEquals(1, named.code, named.out)
    assertEquals(
      "::error::Java version '$below' is below $floor, the floor of the trust check compiled with the installed JDK (check-jdk-trust); it comes from gradle-java-version (vars.GRADLE_JAVA_VERSION).\n",
      named.out
    )
    val equalsInLabel = floor("javaVersion (gradle/sava.properties: javaVersion=NN)", "$below")
    assertEquals(1, equalsInLabel.code, equalsInLabel.out)
    assertTrue(equalsInLabel.out.endsWith("; it comes from javaVersion (gradle/sava.properties: javaVersion=NN).\n"), equalsInLabel.out)
    // An odd argument count is a caller's mistake, refused as such.
    val odd = floor("$floor")
    assertEquals(2, odd.code, odd.out)
    assertTrue(odd.out.contains("pairs of a label and a version"), odd.out)
  }
}
