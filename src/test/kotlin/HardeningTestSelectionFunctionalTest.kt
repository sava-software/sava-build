import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import software.sava.build.hardening.PitestEvidence

/**
 * `excludeTestClass` against real PIT.
 *
 * Every other test of this feature substitutes a fake PIT, which can only show that
 * an argument was assembled. The claim the feature actually makes is about two
 * different runners disagreeing — the class still runs under `test`, and does not
 * run under mutation analysis — and nothing short of running both proves it.
 */
class HardeningTestSelectionFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  /** Written by the excluded test's body, so "did it run" is a fact about execution. */
  private fun marker() = File(fixtureDir, "slow-test-ran.txt")

  /**
   * Written by the excluded class's static initializer, which is the sharp case: PIT
   * attributes class-init coverage to whichever test triggered initialization and does not
   * re-run `<clinit>` between a class's mutants, so a fixture built there can hold a
   * previous mutant's output.
   *
   * Initialization, not loading — the two are distinct JVM phases and only the second
   * runs `<clinit>`. A body marker that stays absent shows the test was not selected;
   * this shows the fixtures it builds at class-init never ran, which is the part the
   * issue is about.
   */
  private fun classInitMarker() = File(fixtureDir, "slow-test-initialized.txt")

  @BeforeEach
  fun setUp() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun writeFixture(records: String) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-test-selection"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories { mavenCentral() }

        dependencies {
          testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
          testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
        }

        tasks.test { useJUnitPlatform() }

        hardening {
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec")
            targetTests = "com.example.*Test*"
            $records
          }
        }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "src/main/java/com/example").mkdirs()
    File(fixtureDir, "src/main/java/com/example/Codec.java").writeText(
      """
        package com.example;

        public final class Codec {
          public static int scale(int v) {
            return v > 3 ? v * 2 : v + 1;
          }
        }
      """.trimIndent() + "\n"
    )
    val testDir = File(fixtureDir, "src/test/java/com/example").apply { mkdirs() }
    File(testDir, "CodecTest.java").writeText(
      """
        package com.example;

        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;

        class CodecTest {
          @Test void scales() {
            assertEquals(8, Codec.scale(4));
            assertEquals(3, Codec.scale(2));
          }
        }
      """.trimIndent() + "\n"
    )
    // Matches targetTests, and touches Codec in a static initializer so PIT's
    // coverage phase attributes the class to it — the shape the feature exists for.
    // Its body records that it ran, in the pinned working directory both runners use.
    File(testDir, "SlowScriptTest.java").writeText(
      """
        package com.example;

        import org.junit.jupiter.api.Test;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import static org.junit.jupiter.api.Assertions.assertTrue;

        class SlowScriptTest {

          private static final int FIXTURE = load();

          private static int load() {
            try {
              Files.writeString(Path.of("slow-test-initialized.txt"), "initialized\n");
            } catch (java.io.IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
            return Codec.scale(4);
          }

          @Test void slow() throws Exception {
            Files.writeString(Path.of("slow-test-ran.txt"), "ran\n");
            assertTrue(FIXTURE > 0);
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--stacktrace")
    .withPluginClasspath()
    .forwardOutput()

  @Test
  fun `an excluded test runs under test and not under PIT, and PIT keeps the same mutants`() {
    // Baseline: no record. PIT selects the class and runs it.
    writeFixture(records = "")
    runner("cleanTest", "test").build()
    assertTrue(marker().isFile, "the test did not run under 'test' at all")
    assertTrue(classInitMarker().isFile, "the class was never initialized under 'test'")
    marker().delete()
    classInitMarker().delete()

    // The ratchet has no baseline to gate against and is not what is under test, so
    // it is excluded and the PIT run itself is required to succeed. Reading the
    // report after a failed build would accept a run that never happened: the
    // previous scenario's XML would still be on disk, and an absent marker — the
    // assertion that matters — is exactly what an early failure also produces.
    val withoutRecord = runPitest()
    assertTrue(
      marker().isFile,
      "PIT did not run the covering test even without a record, so the test proves nothing",
    )
    assertTrue(
      classInitMarker().isFile,
      "PIT never initialized the class even without a record, so the <clinit> half proves nothing",
    )
    val baselineOutcomes = mutationOutcomes()
    marker().delete()
    classInitMarker().delete()

    // With the record: same fixture, one line added.
    writeFixture(
      records = """excludeTestClass("com.example.SlowScriptTest", "spawns a subprocess per test")""",
    )
    runner("cleanTest", "test").build()
    assertTrue(marker().isFile, "the record must not change what 'test' runs")
    assertTrue(classInitMarker().isFile, "the record must not change what 'test' initializes")
    marker().delete()
    classInitMarker().delete()

    val withRecord = runPitest()
    assertNotEquals(
      withoutRecord, withRecord,
      "both scenarios read the same PIT invocation, so the report was never refreshed",
    )
    assertFalse(
      marker().isFile,
      "the excluded test still ran under PIT:\n" + reportText(),
    )
    // The half the issue is actually about: not merely that the body was skipped, but
    // that the class was never initialized, so nothing it builds at class-init can
    // survive into a later mutant's run.
    assertFalse(
      classInitMarker().isFile,
      "the excluded class was still initialized under PIT, so its <clinit> fixtures still run",
    )
    // Removing a test that killed nothing must not move the score. If this ever
    // differs, the exclusion took a killer with it and the feature's whole premise
    // — subtract cost, not coverage — needs re-reading before anyone relies on it.
    assertTrue(
      baselineOutcomes.isNotEmpty(),
      "no mutants were produced, so the outcome comparison is vacuous",
    )
    assertTrue(
      baselineOutcomes == mutationOutcomes(),
      "the exclusion changed the mutation outcomes:\n" +
        "  without: $baselineOutcomes\n  with:    ${mutationOutcomes()}",
    )
  }

  /**
   * Runs PIT to success and returns the invocation id it recorded, so a caller can
   * prove the report it then reads came from this run and not the one before it.
   */
  private fun runPitest(): String {
    runner("pitestEncoding", "-x", "pitestEncodingVerify").build()
    val evidence = File(fixtureDir, "build/reports/pitest/encoding/.evidence.tsv")
    assertTrue(evidence.isFile, "PIT recorded no evidence, so the run cannot be identified")
    return PitestEvidence.parse(evidence.readText()).invocationId
  }

  private fun reportFile() =
    File(fixtureDir, "build/reports/pitest/encoding/mutations.xml")

  private fun reportText() = reportFile().takeIf { it.isFile }?.readText().orEmpty()

  /**
   * Each mutant's full coordinate and status, sorted.
   *
   * Class, method, line and mutator do not identify a mutant: overloads share a
   * name and one line can carry several mutants from the same mutator, which this
   * fixture's ternary does. Two runs that swapped a KILLED and a SURVIVED between
   * such siblings would then sort to the same list and compare equal — the exact
   * regression this comparison is here to catch. `methodDescription` separates the
   * overloads and the bytecode indexes separate the siblings.
   */
  private fun mutationOutcomes(): List<String> =
    Regex("<mutation [^>]*status='([A-Z_]+)'[^>]*>(.*?)</mutation>", RegexOption.DOT_MATCHES_ALL)
      .findAll(reportText())
      .map { match ->
        val body = match.groupValues[2]
        fun field(name: String): String {
          val value = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)
          // A field PIT stopped emitting would silently drop out of the identity and
          // quietly re-admit the collision this method exists to rule out.
          return requireNotNull(value) { "PIT report carried no <$name>:\n$body" }
        }
        listOf(
          field("mutatedClass"), field("mutatedMethod"), field("methodDescription"),
          field("lineNumber"), field("mutator"), field("indexes").filterNot(Char::isWhitespace),
          match.groupValues[1],
        ).joinToString(",")
      }
      .sorted()
      .toList()
}
