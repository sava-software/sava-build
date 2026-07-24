import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Functional test for the execution-time plumbing around the tool JavaExec
 * tasks: the minion-line filter (dedup across both streams, summary and buffered-tail
 * survival on a failing exit), the '.scoped' marker lifecycle, and the corpus
 * minimize's hash-based adoption. The fixture hijacks each task's mainClass to a
 * fixture-compiled fake ('FakePit' / 'FakeMerge') and replaces its classpath, so the
 * plugin's own doFirst/doLast logic runs for real with no PIT or Jazzer involved.
 */
class HardeningToolExecFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "hardening-tool-exec-smoke-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }

        repositories {
          mavenCentral()
        }

        hardening {
          // the recompiles run on the daemon JDK (no toolchain in this fixture), which
          // sava-build pins to 21
          bytecodeRelease = 21
          // generated replay tests need junit and the harness classes, neither of
          // which this fixture declares
          recompileExcludes = listOf("CodecFuzzSeedReplayTest.java", "HollowFuzzSeedReplayTest.java")
          mutation.register("encoding") {
            targetClasses = listOf("com.example.Codec")
            targetTests = "com.example.*Test*"
          }
          fuzz.register("codec") {
            targetClass = "com.example.CodecFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/codec")
          }
          fuzz.register("plain") {
            targetClass = "com.example.PlainFuzz"
          }
          fuzz.register("hollow") {
            targetClass = "com.example.HollowFuzz"
            seedCorpus = layout.projectDirectory.dir("corpus/hollow")
          }
        }

        tasks.named<JavaExec>("pitestEncoding") {
          mainClass = "com.example.FakePit"
          classpath = sourceSets["main"].output
        }
        listOf("fuzzCodecMinimize", "fuzzPlainMinimize", "fuzzHollowMinimize").forEach { name ->
          tasks.named<JavaExec>(name) {
            mainClass = "com.example.FakeMerge"
            classpath = sourceSets["main"].output
            // the Jazzer pre-authorizations include flags newer than this fixture's JDK
            jvmArgs = listOf<String>()
          }
        }
      """.trimIndent() + "\n"
    )
    val srcDir = File(fixtureDir, "src/main/java/com/example").apply { mkdirs() }
    // Prints the repeated minion chatter the filter exists to collapse — split across
    // stdout and stderr, since the shared seen-set is the point — then writes the CSV
    // report the verify finalizer needs. 'fail' mode additionally leaves a partial
    // stderr line unterminated and exits non-zero.
    srcDir.resolve("FakePit.java").writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;

        public final class FakePit {
          public static void main(String[] args) throws Exception {
            String mode = "ok";
            Path modeFile = Path.of("fake-pit-mode.txt");
            if (Files.exists(modeFile)) mode = Files.readString(modeFile).trim();
            for (int i = 0; i < 3; i++) System.out.println("PIT >> INFO : MINION : common noise");
            System.out.println("PIT >> INFO : MINION : stdout-only detail");
            System.out.println("plain duplicate");
            System.out.println("plain duplicate");
            for (int i = 0; i < 2; i++) System.err.println("PIT >> INFO : MINION : common noise");
            System.err.println("PIT >> INFO : MINION : stderr-only detail");
            String reportDir = null;
            for (String arg : args) {
              if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
            }
            Path dir = Path.of(reportDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("mutations.csv"),
                "Codec.java,com.example.Codec,org.pitest.mutationtest.engine.gregor.mutators.MathMutator,encode,12,KILLED,com.example.CodecTest\n");
            if (mode.equals("fail")) {
              System.err.print("partial tail before crash");
              System.exit(3);
            }
          }
        }
      """.trimIndent() + "\n"
    )
    // Simulates libFuzzer -merge=1: the positional arguments are the staging output
    // followed by the source corpora. Writes one input whose bytes match seedA (so the
    // named seed survives) and one novel input; 'empty' mode writes nothing.
    srcDir.resolve("FakeMerge.java").writeText(
      """
        package com.example;

        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.util.ArrayList;
        import java.util.List;

        public final class FakeMerge {
          public static void main(String[] args) throws Exception {
            String mode = "adopt";
            Path modeFile = Path.of("fake-merge-mode.txt");
            if (Files.exists(modeFile)) mode = Files.readString(modeFile).trim();
            List<String> dirs = new ArrayList<>();
            for (String arg : args) {
              if (!arg.startsWith("-")) dirs.add(arg);
            }
            Path staging = Path.of(dirs.get(0));
            Path seeds = Path.of(dirs.get(1));
            if (mode.equals("empty")) return;
            Files.write(staging.resolve("1a2b3c"), Files.readAllBytes(seeds.resolve("seedA")));
            Files.writeString(staging.resolve("9f8e7d"), "novel");
            if (dirs.size() == 3) {
              try (var localFiles = Files.list(Path.of(dirs.get(2)))) {
                for (Path local : localFiles.sorted().toList()) {
                  Files.write(staging.resolve("feedface"), Files.readAllBytes(local));
                }
              }
            }
          }
        }
      """.trimIndent() + "\n"
    )
  }

  private fun writeSeedCorpus() {
    val corpus = File(fixtureDir, "corpus/codec").apply { mkdirs() }
    corpus.resolve("seedA").writeText("alpha")
    corpus.resolve("seedB").writeText("beta-longer")
  }

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--stacktrace")

  private fun occurrences(haystack: String, needle: String) = haystack.split(needle).size - 1

  @Test
  fun `the minion filter dedups across both streams and the scoped marker tracks mutateOnly`() {
    writeFixture()

    val ok = runner("pitestEncoding").build()
    // 5 'common noise' lines went out (3 stdout, 2 stderr); one survives, whichever
    // stream carried it first — the seen-set is shared
    assertEquals(1, occurrences(ok.output, "common noise"), "cross-stream dedup broken:\n" + ok.output)
    assertEquals(1, occurrences(ok.output, "stdout-only detail"), ok.output)
    assertEquals(1, occurrences(ok.output, "stderr-only detail"), ok.output)
    // non-minion lines are never deduplicated, however repetitive
    assertEquals(2, occurrences(ok.output, "plain duplicate"), "non-minion lines must pass through:\n" + ok.output)
    assertTrue(
      ok.output.contains("suppressed 4 repeated minion log line(s)"),
      "suppression summary missing:\n" + ok.output
    )
    // the verify finalizer read the fake's report as a full, unscoped run
    assertTrue(ok.output.contains("pitest 'encoding': 1/1 detected (100%)"), ok.output)

    val marker = File(fixtureDir, "build/reports/pitest/encoding/.scoped")
    val scoped = runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    assertEquals("com.example.Codec\n", marker.readText(), "scoped marker not written")
    assertTrue(scoped.output.contains("SCOPED run (-PmutateOnly=com.example.Codec)"), scoped.output)

    runner("pitestEncoding").build()
    assertFalse(marker.exists(), "an unscoped run must clear the scoped marker")
  }

  @Test
  fun `a failing PIT exit still flushes the summary and buffered tail, and cannot rewrite the marker`() {
    writeFixture()
    // leave a scoped marker behind, then fail an unscoped run: the deferred exit must
    // re-raise after the filters close but before the marker update
    runner("pitestEncoding", "-PmutateOnly=com.example.Codec").build()
    val marker = File(fixtureDir, "build/reports/pitest/encoding/.scoped")
    assertTrue(marker.isFile, "precondition: scoped marker missing")

    File(fixtureDir, "fake-pit-mode.txt").writeText("fail")
    val failed = runner("pitestEncoding").buildAndFail()
    assertTrue(failed.output.contains("non-zero exit value 3"), failed.output)
    assertTrue(
      failed.output.contains("suppressed 4 repeated minion log line(s)"),
      "summary lost on a failing run:\n" + failed.output
    )
    assertTrue(
      failed.output.contains("partial tail before crash"),
      "buffered partial line lost on a failing run:\n" + failed.output
    )
    assertEquals(
      "com.example.Codec\n", marker.readText(),
      "a failed run is not evidence and must not touch the scoped marker"
    )
  }

  @Test
  fun `minimize adopts novel inputs, keeps surviving seed names, and drops redundant seeds`() {
    writeFixture()
    writeSeedCorpus()

    val result = runner("fuzzCodecMinimize").build()
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "surviving seed must keep its committed name")
    assertEquals("novel", corpus.resolve("9f8e7d").readText(), "novel input not adopted")
    assertFalse(corpus.resolve("seedB").exists(), "redundant seed not removed")
    assertTrue(result.output.contains("corpus minimized 2 -> 2 file(s)"), result.output)
    assertTrue(result.output.contains("1 newly adopted, 1 redundant removed"), result.output)
  }

  @Test
  fun `adoptLocalCorpus adds the local finds as a merge source only when requested`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "build/fuzz/codec-corpus").apply { mkdirs() }
      .resolve("local-find").writeText("from-local")
    val corpus = File(fixtureDir, "corpus/codec")

    // without the flag the local dir is never passed to the merge, however non-empty
    runner("fuzzCodecMinimize").build()
    assertFalse(corpus.resolve("feedface").exists(), "local corpus must not be a source by default")

    // reset the corpus (the first merge dropped seedB) and adopt deliberately
    corpus.resolve("9f8e7d").delete()
    writeSeedCorpus()
    val adopted = runner("fuzzCodecMinimize", "-PadoptLocalCorpus").build()
    assertEquals("from-local", corpus.resolve("feedface").readText(), "local find not folded into the merge")
    assertTrue(adopted.output.contains("2 newly adopted"), adopted.output)
  }

  @Test
  fun `minimize refuses an empty merge, a missing corpus, and an undeclared one`() {
    writeFixture()
    writeSeedCorpus()

    // an empty merge result must never replace the committed corpus
    File(fixtureDir, "fake-merge-mode.txt").writeText("empty")
    val empty = runner("fuzzCodecMinimize").buildAndFail()
    assertTrue(empty.output.contains("the merge produced an empty corpus — refusing to touch"), empty.output)
    val corpus = File(fixtureDir, "corpus/codec")
    assertEquals("alpha", corpus.resolve("seedA").readText(), "committed corpus touched by a refused merge")
    assertEquals("beta-longer", corpus.resolve("seedB").readText(), "committed corpus touched by a refused merge")

    val undeclared = runner("fuzzPlainMinimize").buildAndFail()
    assertTrue(
      undeclared.output.contains("declares no seedCorpus — nothing to minimize into"),
      undeclared.output
    )

    val missing = runner("fuzzHollowMinimize").buildAndFail()
    assertTrue(
      missing.output.contains("missing or empty — a merge cannot start from nothing"),
      missing.output
    )
  }
}
