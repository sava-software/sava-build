package software.sava.build.guard

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every read of a repository file in the guard tests goes through guardFile, which holds the read
 * to the paths build.gradle.kts declares as inputs of guardTest (guardFilesReadByTests). A text
 * check over these sources, which are declared too, the fixtures file among them: no file but
 * this one, told by its declaration at the start of a line, constructs a Path or a File from a
 * bare relative literal, which the working directory would resolve around the declaration. Raw
 * strings are left out of the scan, since they hold fixture content rather than code of the test.
 * RootFileReadsTest is the same check over src/test. A path built at run time rather than
 * spelled, or read through an API other than Path and File, is not seen.
 */
class GuardFileReadsTest {

  @Test
  fun `no guard test names a relative path to a bare Path or File`() {
    // An empty literal is no path at all (the trust test probes check.sh's fallback with one).
    val bare = Regex("""(?<![A-Za-z])(Path\.of|Paths\.get|Path|File)\("(?!["/])""")
    val self = Regex("""^class GuardFileReadsTest\b""", RegexOption.MULTILINE)
    val rawString = Regex("\"\"\"[\\s\\S]*?\"\"\"")
    val offenders = Files.walk(guardFile("src/guardTest/kotlin")).use { paths ->
      paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
        .map { it.fileName.toString() to Files.readString(it) }
        .filter { (_, text) -> !self.containsMatchIn(text) && bare.containsMatchIn(text.replace(rawString, "")) }
        .map { it.first }.sorted().toList()
    }
    assertEquals(
      emptyList<String>(),
      offenders,
      "guard test sources constructing a path from a bare relative literal: read a repository file through guardFile",
    )
  }
}
