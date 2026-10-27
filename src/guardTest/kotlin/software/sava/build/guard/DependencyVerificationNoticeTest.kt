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
  fun `release-floor refuses a leading number below 21 and leaves the rest to setup-java`() {
    for (refused in listOf(listOf("17"), listOf("17u"), listOf("17.0.2"), listOf("25", "17-ea"), listOf("20"))) {
      val run = run("check-jdk-trust/release-floor.sh", *refused.toTypedArray())
      assertEquals(1, run.code, "$refused -> ${run.out}")
      assertTrue(run.out.contains("::error::Java version '") && run.out.contains("is below 21"), run.out)
    }
    for (accepted in listOf(listOf("21"), listOf("25", "25"), listOf("loom"), listOf(""), emptyList())) {
      val run = run("check-jdk-trust/release-floor.sh", *accepted.toTypedArray())
      assertEquals(0, run.code, "$accepted -> ${run.out}")
      assertEquals("", run.out)
    }
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
