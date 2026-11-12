import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every fixture run comes from the shared helpers in SavaBuildTestFixtures.kt, which pass
 * `--no-watch-fs` (their KDoc says why), hold a fixture to the fixture policy, and hand a test a
 * FixtureRunner, which can run a build and cannot repoint, re-argue or rehome one. So no other
 * source file of the tests names TestKit's own runner at all, by call, by reference, by type or
 * under an import alias. The fixtures that run outside the fixture policy are the ones named
 * here, and no fixture script takes a configuration out of verification.
 *
 * This is a text check, not a parser. It reads every Kotlin and Java file under src/test,
 * wherever it sits, except this file, and knows each by its path; comments and string literals
 * count as code, which is what lets it read the fixture scripts the tests write. Reflection gets
 * past it. What a caller passes as arguments is the helpers' to refuse (FixtureVerificationTest).
 */
class TestKitRunnerArgumentsTest {

  private val testSources = savaBuildRootFile("src/test")
  private val helper = "kotlin/SavaBuildTestFixtures.kt"
  private val self = "kotlin/TestKitRunnerArgumentsTest.kt"

  /** Every source file of the tests but this one, by its path under src/test. */
  private fun sources(): Map<String, File> = testSources.walkTopDown()
    .filter { it.isFile && it.extension in setOf("kt", "kts", "java") }
    .associateBy { it.relativeTo(testSources).invariantSeparatorsPath }
    .minus(self)

  /** The files, by path, whose text contains [text]. */
  private fun filesNaming(text: String): Set<String> = sources().filterValues { text in it.readText() }.keys

  @Test
  fun `the scan reads the tests' sources`() {
    val sources = sources()
    assertTrue(helper in sources, sources.keys.toString())
    assertTrue(testSources.resolve(self).isFile, "this test is not at $self")
    assertTrue(sources.size > 50, "found few sources under $testSources")
  }

  @Test
  fun `no test but the shared helpers holds a TestKit runner, or wraps one`() {
    assertEquals(setOf(helper), filesNaming("GradleRunner"), "TestKit's runner named outside the helpers")
    assertEquals(setOf(helper), filesNaming("FixtureRunner("), "a FixtureRunner made outside the helpers")
  }

  @Test
  fun `the one runner the helpers make turns file-system watching off`() {
    val made = testSources.resolve(helper).readLines().filter { "withArguments(" in it }
    assertEquals(1, made.size, made.toString())
    assertTrue("\"--no-watch-fs\"" in made.single(), made.single())
  }

  @Test
  fun `only the classes named here run outside the fixture policy`() {
    val ownPolicy = setOf("kotlin/KeyStatusFunctionalTest.kt", "kotlin/VerificationPolicyFunctionalTest.kt")
    // The two FixtureVerification tests use the runner to show what it refuses and what such a
    // fixture can and cannot reach. FixtureVerification.kt names it in KDoc only.
    assertEquals(
      ownPolicy + helper + "kotlin/FixtureVerification.kt" + "kotlin/FixtureVerificationFunctionalTest.kt" + "kotlin/FixtureVerificationTest.kt",
      filesNaming("savaBuildRunnerWithOwnPolicy"),
    )
    assertEquals(ownPolicy + "kotlin/FixtureVerificationFunctionalTest.kt", filesNaming("fixturePolicy = false"))
    // and nothing but the helper makes such a runner without the strict build before it
    assertEquals(setOf(helper, "kotlin/FixtureVerificationTest.kt"), filesNaming("ownPolicyRunner("))
  }

  @Test
  fun `no fixture script takes a configuration out of verification`() {
    // The helpers' KDoc names the call; no script a test writes makes it.
    assertEquals(setOf(helper), filesNaming("disableDependencyVerification"))
  }

  @Test
  fun `the fixtures call the helper`() {
    // Not vacuous: the runners exist and go through it.
    assertTrue(filesNaming("savaBuildRunner(").size > 20, "found few callers of savaBuildRunner under $testSources")
  }
}
