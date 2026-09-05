import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.PitestEvidence
import java.io.File

/**
 * Real Test workers signal completion to fake PIT processes. Every invocation removes
 * those signals and forces the Test tasks to execute, including configuration-cache
 * reuse: an old successful test output cannot accidentally satisfy the phase boundary.
 */
class HardeningCertificationOrderingFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val suites = listOf("first", "second")
  private val ordinaryTests = listOf("test", "contractTest")

  @Test
  fun `direct certification finishes ordinary tests before serial PIT on cold and reused configuration`() {
    val projects = listOf("root")
    writeFixture(projects)

    assertConfigurationCacheRoundTrip {
      resetEvents()
      runner(":hardeningCertify").build().also { result ->
        assertCompletedPhases(result, projects)
        assertChildReceipt("root", result)
        assertFalse(fixtureDir.resolve(".pitest-history/pitest-certification-all.tsv").exists())
      }
    }
  }

  @Test
  fun `root certification finishes every selected project test before any PIT with configuration cache`() {
    val projects = listOf("a", "b")
    writeFixture(projects)

    assertConfigurationCacheRoundTrip {
      resetEvents()
      runner(":hardeningCertifyAll").build().also { result ->
        assertCompletedPhases(result, projects)
        projects.forEach { assertChildReceipt(it, result) }
        val manifest = fixtureDir.resolve(".pitest-history/pitest-certification-all.tsv")
        assertTrue(manifest.isFile, result.output)
        val contents = manifest.readText()
        assertTrue(contents.contains("projectCount\t2\n"), contents)
        assertTrue(contents.contains("suiteCount\t4\n"), contents)
        val childRows = contents.lineSequence().filter { it.startsWith("project\t") }
          .map { it.split('\t') }.toList()
        assertEquals(listOf(":a", ":b"), childRows.map { it[1] }, contents)
        childRows.forEach { row ->
          assertEquals(5, row.size, contents)
          assertEquals("2", row[4], contents)
          assertTrue(row[2].startsWith("root:"), contents)
          assertEquals(
            PitestEvidence.sha256(fixtureDir.resolve(row[2].removePrefix("root:"))),
            row[3],
            contents,
          )
        }
        assertEquals(
          listOf("suite\t:a\tfirst", "suite\t:a\tsecond", "suite\t:b\tfirst", "suite\t:b\tsecond"),
          contents.lineSequence().filter { it.startsWith("suite\t") }.toList(),
          contents,
        )
        assertFalse(fixtureDir.resolve(".pitest-history/pitest-certification-all.running").exists())
      }
    }
  }

  @Test
  fun `standalone scoped PIT does not schedule ordinary tests on cold or reused configuration`() {
    writeFixture(listOf("root"))

    assertConfigurationCacheRoundTrip {
      resetEvents()
      runner(
        ":pitestFirst",
        "-PmutateOnly=com.example.First",
        "-PfixtureStandalonePit",
      ).build().also { result ->
        assertEquals(TaskOutcome.SUCCESS, result.task(":pitestFirst")?.outcome, result.output)
        ordinaryTests.forEach { name ->
          assertTrue(result.task(":$name") == null, "standalone PIT scheduled $name:\n${result.output}")
          assertFalse(eventDirectory().resolve("root-$name.done").exists(), result.output)
        }
        assertEquals(listOf("pit-start root:first", "pit-end root:first"), events(), result.output)
        assertFalse(fixtureDir.resolve(".pitest-history/pitest-certification.tsv").exists())
      }
    }
  }

  @Test
  fun `failed ordinary tests refuse their receipt while aggregate can certify a healthy sibling`() {
    writeFixture(listOf("a", "b"))

    val result = runner(":hardeningCertifyAll", "--continue", "-PfixtureFailTest=a-test").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.task(":a:test")?.outcome, result.output)
    assertFalse(projectDirectory("a").resolve(".pitest-history/pitest-certification.tsv").exists())
    assertChildReceipt("b", result)
    assertEquals(TaskOutcome.SUCCESS, result.task(":b:pitestFirst")?.outcome, result.output)
    assertEquals(TaskOutcome.SUCCESS, result.task(":b:pitestSecond")?.outcome, result.output)
    assertFalse(fixtureDir.resolve(".pitest-history/pitest-certification-all.tsv").exists())
    assertTrue(fixtureDir.resolve(".pitest-history/pitest-certification-all.running").isFile)
  }

  private fun writeFixture(projects: List<String>) {
    enableTestKitConfigurationCache(fixtureDir)
    val singleProject = projects == listOf("root")
    fixtureDir.resolve("settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-certification-ordering"
        ${if (singleProject) "" else "include(\"a\", \"b\")"}
      """.trimIndent() + "\n",
    )
    if (!singleProject) fixtureDir.resolve("build.gradle.kts").writeText("")
    val expectedMarkers = projects.flatMap { project ->
      ordinaryTests.map { name -> "$project-$name" }
    }.joinToString(",")

    projects.forEach { project ->
      val directory = projectDirectory(project).apply { mkdirs() }
      directory.resolve("build.gradle.kts").writeText(
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

          val orderingEvents = rootProject.layout.projectDirectory.dir(".ordering-events").asFile
          val failingTest = providers.gradleProperty("fixtureFailTest").orElse("")
          tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            outputs.upToDateWhen { false }
            systemProperty("ordering.events", orderingEvents.absolutePath)
            systemProperty("ordering.marker", "$project-" + name)
            systemProperty("ordering.fail", failingTest.get())
          }
          // Registered after the plugin: order the selected lazy Test collection,
          // not merely the Java plugin's conventional task named 'test'.
          val contractTest = tasks.register<Test>("contractTest") {
            testClassesDirs = sourceSets["test"].output.classesDirs
            classpath = sourceSets["test"].runtimeClasspath
          }
          tasks.named("qualityGate") { dependsOn(contractTest) }

          hardening {
            mutation.register("first") {
              targetClasses = listOf("com.example.First", "com.example.FakePit")
              targetTests = "com.example.*Test"
            }
            mutation.register("second") {
              targetClasses = listOf("com.example.Second")
              targetTests = "com.example.*Test"
            }
          }

          val standalone = providers.gradleProperty("fixtureStandalonePit").isPresent
          listOf("pitestFirst", "pitestSecond").forEach { name ->
            tasks.named<JavaExec>(name) {
              mainClass = "com.example.FakePit"
              classpath = sourceSets["main"].output
              environment(
                "ORDERING_EVENTS" to orderingEvents.absolutePath,
                "ORDERING_PROJECT" to "$project",
                "ORDERING_EXPECTED_TESTS" to if (standalone) "" else "$expectedMarkers",
              )
            }
          }
        """.trimIndent() + "\n",
      )
      val main = directory.resolve("src/main/java/com/example").apply { mkdirs() }
      listOf("First", "Second").forEach { name ->
        main.resolve("$name.java").writeText(
          "package com.example;\npublic final class $name { public static int value() { return 1; } }\n",
        )
      }
      main.resolve("FakePit.java").writeText(
        """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardOpenOption;

          public final class FakePit {
            public static void main(String[] args) throws Exception {
              Path events = Path.of(System.getenv("ORDERING_EVENTS"));
              Files.createDirectories(events);
              String expectedTests = System.getenv("ORDERING_EXPECTED_TESTS");
              if (!expectedTests.isEmpty()) {
                for (String marker : expectedTests.split(",")) {
                  if (!Files.isRegularFile(events.resolve(marker + ".done"))) {
                    throw new IllegalStateException("PIT began before ordinary test completed: " + marker);
                  }
                }
              }
              String reportDir = null;
              for (String arg : args) {
                if (arg.startsWith("--reportDir=")) reportDir = arg.substring("--reportDir=".length());
              }
              Path report = Path.of(reportDir);
              String suite = report.getFileName().toString();
              String identity = System.getenv("ORDERING_PROJECT") + ":" + suite;
              Path lock = events.resolve("pit.lock");
              try {
                Files.writeString(lock, identity, StandardOpenOption.CREATE_NEW);
              } catch (java.nio.file.FileAlreadyExistsException overlap) {
                throw new IllegalStateException("PIT processes overlapped: " + identity, overlap);
              }
              try {
                Files.writeString(events.resolve("events.txt"), "pit-start " + identity + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Files.createDirectories(report);
                String type = suite.equals("first") ? "First" : "Second";
                Files.writeString(report.resolve("mutations.csv"),
                    type + ".java,com.example." + type + "," +
                    "org.pitest.mutationtest.engine.gregor.mutators.MathMutator," +
                    "value,2,KILLED,com.example.OrderingTest\n");
                Files.writeString(events.resolve("events.txt"), "pit-end " + identity + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              } finally {
                Files.deleteIfExists(lock);
              }
            }
          }
        """.trimIndent() + "\n",
      )
      val test = directory.resolve("src/test/java/com/example/OrderingTest.java")
      test.parentFile.mkdirs()
      test.writeText(
        """
          package com.example;

          import java.nio.file.Files;
          import java.nio.file.Path;
          import java.nio.file.StandardOpenOption;
          import org.junit.jupiter.api.AfterAll;
          import org.junit.jupiter.api.Test;
          import static org.junit.jupiter.api.Assertions.assertEquals;
          import static org.junit.jupiter.api.Assertions.assertNotEquals;

          public final class OrderingTest {
            @Test void exercisesBothClasses() {
              assertEquals(1, First.value());
              assertEquals(1, Second.value());
              assertNotEquals(System.getProperty("ordering.fail"), System.getProperty("ordering.marker"),
                  "fixture ordinary test failure");
            }

            @AfterAll static void completed() throws Exception {
              Path events = Path.of(System.getProperty("ordering.events"));
              Files.createDirectories(events);
              String marker = System.getProperty("ordering.marker");
              Files.writeString(events.resolve("events.txt"), "test-done " + marker + "\n",
                  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
              Files.writeString(events.resolve(marker + ".done"), "completed\n",
                  StandardOpenOption.CREATE_NEW);
            }
          }
        """.trimIndent() + "\n",
      )
    }
    initializeGitFixture()
  }

  private fun assertCompletedPhases(result: BuildResult, projects: List<String>) {
    val expectedTests = projects.flatMap { project -> ordinaryTests.map { "$project-$it" } }.toSet()
    val observed = events()
    val firstPit = observed.indexOfFirst { it.startsWith("pit-start ") }
    assertEquals(expectedTests.size, firstPit, "PIT started before all Test workers finished: $observed")
    assertEquals(expectedTests, observed.take(firstPit).map { it.removePrefix("test-done ") }.toSet())
    val intervals = observed.drop(firstPit)
    val expectedSuites = projects.flatMap { project -> suites.map { "$project:$it" } }.toSet()
    assertEquals(expectedSuites.size * 2, intervals.size, "missing or duplicate PIT process: $observed")
    intervals.chunked(2).forEach { interval ->
      assertTrue(interval[0].startsWith("pit-start "), observed.toString())
      assertEquals("pit-end " + interval[0].removePrefix("pit-start "), interval[1], observed.toString())
    }
    assertEquals(expectedSuites, intervals.filterIndexed { index, _ -> index % 2 == 0 }
      .map { it.removePrefix("pit-start ") }.toSet())
    assertFalse(eventDirectory().resolve("pit.lock").exists(), observed.toString())
    projects.forEach { project ->
      val prefix = if (project == "root") "" else ":$project"
      ordinaryTests.forEach { name ->
        assertEquals(TaskOutcome.SUCCESS, result.task("$prefix:$name")?.outcome, result.output)
      }
      listOf("pitestFirst", "pitestSecond", "hardeningCertify").forEach { name ->
        assertEquals(TaskOutcome.SUCCESS, result.task("$prefix:$name")?.outcome, result.output)
      }
    }
  }

  private fun assertChildReceipt(project: String, result: BuildResult) {
    val receipt = projectDirectory(project).resolve(".pitest-history/pitest-certification.tsv")
    assertTrue(receipt.isFile, result.output)
    val contents = receipt.readText()
    assertTrue(contents.contains("gitState\tclean\n"), contents)
    assertEquals(suites, contents.lineSequence().filter { it.startsWith("suite\t") }
      .map { it.split('\t')[1] }.toList(), contents)
    assertFalse(projectDirectory(project).resolve(".pitest-history/pitest-certification.running").exists())
  }

  private fun eventDirectory(): File = fixtureDir.resolve(".ordering-events")

  private fun events(): List<String> = eventDirectory().resolve("events.txt").readLines()

  private fun resetEvents() {
    eventDirectory().listFiles().orEmpty().forEach { file ->
      assertTrue(file.isFile && file.delete(), "could not reset fixture event: $file")
    }
  }

  private fun projectDirectory(project: String): File =
    if (project == "root") fixtureDir else fixtureDir.resolve(project)

  private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--configuration-cache", "--no-parallel", "--max-workers=4", "--stacktrace")

  private fun initializeGitFixture() {
    fixtureDir.resolve(".gitignore").writeText(".gradle/\nbuild/\n.pitest-history/\n.ordering-events/\n")
    val emptyTemplate = fixtureDir.resolve(".empty-git-template").apply { mkdirs() }
    git("-c", "init.templateDir=${emptyTemplate.absolutePath}", "init", "--quiet", "--initial-branch=main")
    git("add", "-A")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "ordering fixture",
    )
  }

  private fun git(vararg arguments: String) {
    val process = ProcessBuilder(listOf("git", "-C", fixtureDir.absolutePath) + arguments)
      .redirectErrorStream(true)
      .apply {
        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
        environment()["GIT_TERMINAL_PROMPT"] = "0"
      }.start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }
}
