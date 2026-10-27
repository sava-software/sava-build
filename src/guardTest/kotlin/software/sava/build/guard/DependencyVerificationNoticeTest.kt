package software.sava.build.guard

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Two of the scripts sava-build's root action and the setup-checked-jdk action run around the JDK
 * installs, exercised as the actions run them: notice.sh over fixture projects (the file, the
 * property in its spellings, the element in its spellings) and downloader-jdk.sh over the two
 * variables, handing a stub home to check.sh. The shape and floor scripts have InputShapeTest.
 */
class DependencyVerificationNoticeTest {

  @TempDir
  lateinit var project: File

  private val actions = guardFile(".github/actions").toAbsolutePath()

  private fun run(script: String, vararg args: String, env: Map<String, String?> = emptyMap()): Run =
    bash(actions.resolve(script), args.toList(), project, env)

  private fun metadata(text: String) {
    File(project, "gradle").mkdirs()
    File(project, "gradle/verification-metadata.xml").writeText(text)
  }

  private fun properties(text: String) = File(project, "gradle.properties").writeText(text)

  /** notice.sh reads gradle.properties through the JDK it is given: this test JVM's. */
  private fun notice(): Run = run("dependency-verification/notice.sh", env = mapOf("JAVA_HOME" to System.getProperty("java.home")))

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
    // Continuations, escapes and comments as java.util.Properties reads them, which does the reading.
    properties("org.gradle.dependency.verification=\\\n    off\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a continued value is read")
    properties("org.gradle.dependency.verification=\\\n    strict\n")
    assertEquals("", notice().out, "a continued strict value is read")
    properties("org.gradle.dependency.\\\n    verification=off\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a continued key is read")
    properties("org.gradle.dependency.verification=\\\r\n    off\r\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a CRLF continuation is read")
    properties("# verification off for now \\\norg.gradle.dependency.verification=off\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a comment line does not continue")
    properties("foo=bar\\\\\norg.gradle.dependency.verification=off\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "an escaped backslash does not continue")
    properties("org.gradle.dependency.verification=off\\")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a trailing backslash at the end of the file is dropped")
    properties("org.gradle.dependency.verification=\\u006Fff\n")
    assertTrue(notice().out.contains("sets verification off or lenient"), "a unicode escape is read")
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
