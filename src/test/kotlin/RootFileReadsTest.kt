import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every read of a repository file in the tests goes through savaBuildRootFile, which holds the
 * read to the paths build.gradle.kts declares as inputs of ':test' (rootFilesReadByTests), the
 * sources a test asserts over among them. A text check, like TestKitRunnerArgumentsTest, over
 * the test sources, which are declared too: the root property is read in no file but the one
 * declaring that function, and no bare `File("...")` or `Path("...")`, nor a `Files` read of a
 * bare `Path.of("...")`, names a relative path, which the test task's working directory, the
 * checkout, would resolve around the declaration. `Path.of` and `Paths.get` are scanned as reads
 * only, and raw strings not at all: the fixtures here embed Java sources and build scripts that
 * spell such paths, and symlink targets that are relative by nature, none a read of the checkout.
 * The checkout itself, savaBuildCheckout, runs git and gives nothing else out, so it needs no
 * rule. Only this file is out of the scan, told by its declaration at the start of a line; the
 * fixtures file is exempt from the one rule its declarations must break, the read of the property.
 * GuardFileReadsTest is the same check over src/guardTest. A path built at run time rather than
 * spelled, or read through an API other than Path, Files and File, is not seen.
 */
class RootFileReadsTest {

  private val self = Regex("""^class RootFileReadsTest\b""", RegexOption.MULTILINE)

  private val accessor = Regex("""^fun savaBuildRootFile\(""", RegexOption.MULTILINE)

  private val rawString = Regex("\"\"\"[\\s\\S]*?\"\"\"")

  /** The test sources but this one, read once, their raw strings left out. */
  private val sources: List<Pair<String, String>> by lazy {
    savaBuildRootFile("src/test/kotlin").walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .map { it.name to it.readText() }
      .filter { (_, text) -> !self.containsMatchIn(text) }
      .map { (name, text) -> name to text.replace(rawString, "") }
      .sortedBy { it.first }.toList()
  }

  private fun offenders(rule: (String) -> Boolean): List<String> = sources.filter { (_, text) -> rule(text) }.map { it.first }

  @Test
  fun `the checkout root is read only by the fixture functions`() {
    // The read of the property, not its name, which a comment may mention; the fixtures file
    // declares the functions that read it.
    val read = Regex("""(savaBuildTestProperty|System\.getProperty)\("savaBuild\.root"\)""")
    assertEquals(
      emptyList<String>(),
      offenders { !accessor.containsMatchIn(it) && read.containsMatchIn(it) },
      "test sources reading the savaBuild.root property: read a repository file through savaBuildRootFile",
    )
  }

  @Test
  fun `no test names a relative path to a bare File or Path`() {
    // `File("x")` or `Path("x")`, not `savaBuildRootFile("x")` or `File(dir, "x")`, and an empty
    // literal is no path; a `Path.of("x")` or `Paths.get("x")` where a Files method reads it.
    val reads = "readString|readAllLines|readAllBytes|lines|newBufferedReader|newInputStream|exists|notExists|" +
      "isRegularFile|isDirectory|list|walk|size|getLastModifiedTime|readSymbolicLink|newDirectoryStream|copy|readAttributes"
    val bare = Regex(
      """(?<![A-Za-z])(File|Path)\("(?!["/])""" +
        """|Files\.($reads)\(\s*(Path\.of|Paths\.get)\("(?!["/])"""
    )
    assertEquals(
      emptyList<String>(),
      offenders { bare.containsMatchIn(it) },
      "test sources constructing a path from a bare relative literal: read a repository file through savaBuildRootFile",
    )
  }
}
