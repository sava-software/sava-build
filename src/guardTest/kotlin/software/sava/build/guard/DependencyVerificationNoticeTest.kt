package software.sava.build.guard

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The three scripts sava-build's root action and the setup-checked-jdk action run around the JDK
 * installs, exercised as the actions run them: notice.sh over fixture projects (the file, the
 * property in its spellings, the element in its spellings), release-floor.sh over versions, and
 * downloader-jdk.sh over the two variables, handing a stub home to check.sh.
 */
class DependencyVerificationNoticeTest {

  @TempDir
  lateinit var project: File

  private val actions = Path.of(".github/actions").toAbsolutePath()

  private class Run(val code: Int, val out: String)

  @BeforeEach
  fun bashOnly() {
    assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the actions run bash")
  }

  private fun run(script: String, vararg args: String, env: Map<String, String?> = emptyMap()): Run {
    val builder = ProcessBuilder(listOf("bash", actions.resolve(script).toString()) + args)
      .directory(project)
      .redirectErrorStream(true)
    env.forEach { (k, v) -> if (v == null) builder.environment().remove(k) else builder.environment()[k] = v }
    val process = builder.start()
    val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
    return Run(process.waitFor(), out)
  }

  private fun metadata(text: String) {
    File(project, "gradle").mkdirs()
    File(project, "gradle/verification-metadata.xml").writeText(text)
  }

  private fun properties(text: String) = File(project, "gradle.properties").writeText(text)

  private fun notice(): Run = run("dependency-verification/notice.sh")

  private val signaturesOn = "<configuration><verify-metadata>true</verify-metadata><verify-signatures>true</verify-signatures></configuration>\n"

  @Test
  fun `notice warns without the file and is quiet with a verifying one`() {
    val missing = notice()
    assertEquals(0, missing.code, missing.out)
    assertTrue(missing.out.contains("::warning::No gradle/verification-metadata.xml"), missing.out)
    metadata(signaturesOn)
    val verifying = notice()
    assertEquals(0, verifying.code, verifying.out)
    assertEquals("", verifying.out)
  }

  @Test
  fun `notice reads the element as Gradle does, without case, whitespace and comments`() {
    metadata("<configuration>\n  <!-- <verify-signatures>true</verify-signatures> was the bootstrap -->\n  <verify-signatures>\n    TRUE\n  </verify-signatures>\n</configuration>\n")
    assertEquals("", notice().out)
    metadata("<configuration><verify-metadata>true</verify-metadata><!-- <verify-signatures>true</verify-signatures> --></configuration>\n")
    assertTrue(notice().out.contains("verifies checksums but not signatures"), "a commented-out element does not count")
    // verify-metadata governs the metadata files only, so false there is no warning.
    metadata("<configuration><verify-metadata>false</verify-metadata><verify-signatures>true</verify-signatures></configuration>\n")
    assertEquals("", notice().out)
  }

  @Test
  fun `notice reads the property as java util Properties does, by its last definition`() {
    metadata(signaturesOn)
    for (off in listOf("org.gradle.dependency.verification=OFF\n", "org.gradle.dependency.verification off\n",
      "  systemProp.org.gradle.dependency.verification : Lenient\r\n", "org.gradle.dependency.verification=strict\norg.gradle.dependency.verification=off\n")) {
      properties(off)
      val out = notice().out
      assertTrue(out.contains("sets verification off or lenient"), "$off -> $out")
    }
    for (on in listOf("org.gradle.dependency.verification=off\norg.gradle.dependency.verification=strict\n",
      "org.gradle.dependency.verification.other=off\n", "org.gradle.dependency.verification=strict\n",
      "ORG.GRADLE.DEPENDENCY.VERIFICATION=off\n")) {
      properties(on)
      assertEquals("", notice().out, on)
    }
  }

  @Test
  fun `release-floor refuses a leading number below the floor and leaves the rest to setup-java`() {
    // The floor as the script spells it, so a move of the floor moves this test with it.
    val script = "check-jdk-trust/release-floor.sh"
    val floor = releaseFloor()
    val below = floor - 1
    val above = floor + 4
    // Pairs of a label and a version; an empty label names no input.
    for (refused in listOf(listOf("", "$below"), listOf("", "${below}u"), listOf("", "$below.0.2"), listOf("", "$above", "", "$below-ea"), listOf("", "${floor - 4}"))) {
      val run = run(script, *refused.toTypedArray())
      assertEquals(1, run.code, "$refused -> ${run.out}")
      assertTrue(run.out.contains("::error::Java version '") && run.out.contains("is below $floor"), run.out)
      assertEquals(1, run.out.lines().count { it.startsWith("::error::") }, run.out)
      assertFalse(run.out.contains("it comes from"), run.out)
    }
    for (accepted in listOf(listOf("", "$floor"), listOf("", "$above", "", "$above"), listOf("", "loom"), listOf("", ""), emptyList())) {
      val run = run(script, *accepted.toTypedArray())
      assertEquals(0, run.code, "$accepted -> ${run.out}")
      assertEquals("", run.out)
    }
    // The one error line names the input a version comes from when the caller labels it; a label
    // may hold '=' or anything else, since nothing is parsed out of it.
    val named = run(script, "build", "$above", "gradle-java-version (vars.GRADLE_JAVA_VERSION)", "$below")
    assertEquals(1, named.code, named.out)
    assertEquals(
      "::error::Java version '$below' is below $floor, the floor of the trust check compiled with the installed JDK (check-jdk-trust); it comes from gradle-java-version (vars.GRADLE_JAVA_VERSION).\n",
      named.out
    )
    val equalsInLabel = run(script, "javaVersion (gradle/sava.properties: javaVersion=NN)", "$below")
    assertEquals(1, equalsInLabel.code, equalsInLabel.out)
    assertTrue(equalsInLabel.out.endsWith("; it comes from javaVersion (gradle/sava.properties: javaVersion=NN).\n"), equalsInLabel.out)
    // An odd argument count is a caller's mistake, refused as such.
    val odd = run(script, "$floor")
    assertEquals(2, odd.code, odd.out)
    assertTrue(odd.out.contains("pairs of a label and a version"), odd.out)
  }

  @Test
  fun `downloader-jdk fails closed without the variables and hands the home it finds to check-sh`() {
    val unset = mapOf<String, String?>("JAVA_HOME_21_X64" to null, "JAVA_HOME_21_arm64" to null)
    val neither = run("setup-checked-jdk/downloader-jdk.sh", env = unset)
    assertEquals(1, neither.code, neither.out)
    assertTrue(neither.out.contains("neither JAVA_HOME_21_X64 nor JAVA_HOME_21_arm64"), neither.out)
    // A home that exists but is no JDK reaches check.sh, whose own refusal names it.
    val stub = File(project, "stub-jdk").apply { mkdirs() }
    val handed = run("setup-checked-jdk/downloader-jdk.sh", env = unset + ("JAVA_HOME_21_arm64" to stub.path))
    assertEquals(2, handed.code, handed.out)
    assertTrue(handed.out.contains("no JDK at '${stub.path}'"), handed.out)
    assertFalse(handed.out.contains("neither JAVA_HOME_21_X64"), handed.out)
  }
}
