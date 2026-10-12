import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Project isolation, aggregate certification receipts, and completion authorization. */
class HardeningToolCertificationAggregateFunctionalTest : HardeningToolExecFixture() {

  private fun writeCompositeAggregateSelectorFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "main"
        includeBuild("included")
      """.trimIndent() + "\n",
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n",
    )

    val included = File(fixtureDir, "included").apply { mkdirs() }
    included.resolve("settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "included"
      """.trimIndent() + "\n",
    )
    included.resolve("build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n",
    )
  }

  private fun aggregateProjectRows(manifest: String): List<List<String>> =
    manifest.lineSequence()
      .filter { it.startsWith("project\t") }
      .map { row ->
        row.split('\t').also { columns ->
          assertEquals(5, columns.size, "invalid aggregate project row: $row")
        }
      }
      .toList()

  @Test
  fun `zero-suite aggregate publishes and replaces a prior manifest with configuration cache`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-zero-suite-aggregate-smoke-test"
      """.trimIndent() + "\n",
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n",
    )
    initializeGitFixture()
    val manifest = File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").apply {
      parentFile.mkdirs()
      writeText("prior aggregate success\n")
    }

    val first = runner(":hardeningCertifyAll").build()
    val manifestText = manifest.readText()

    assertTrue(manifestText.contains("projectCount\t1\n"), manifestText)
    assertTrue(manifestText.contains("suiteCount\t0\n"), manifestText)
    assertTrue(manifestText.lineSequence().any { it.startsWith("project\t:\troot:") })
    assertFalse(manifestText.lineSequence().any { it.startsWith("suite\t") })
    assertFalse(manifest.readText() == "prior aggregate success\n")
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification-all.running").exists())
    assertTrue(first.output.contains("1 project(s) and 0 suite(s) certified"), first.output)

    val second = runner(":hardeningCertifyAll").build()
    assertTrue(
      second.output.contains("Configuration cache entry reused."),
      "zero-suite aggregate did not reuse its configuration graph:\n${second.output}",
    )
    assertTrue(manifest.readText().contains("suiteCount\t0\n"))
  }

  @Test
  fun `qualified aggregate intent does not leak into another project`() {
    writeFixture(settingsTail = "include(\"a\", \"b\")")
    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(sharedBuild)
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
    }
    File(fixtureDir, "b/fake-pit-mode.txt").writeText("run-error\n")

    val failed = runner(
      ":a:pitestConverge",
      ":b:pitestEncoding",
      "-PnoMutationHistory",
    ).buildAndFail().output

    assertTrue(failed.contains("RUN_ERROR x1"), failed)
    assertTrue(
      failed.contains(
        "Retry: in a new Gradle invocation, run :b:pitestEncoding " +
            "-PnoMutationHistory without -PmutateOnly.",
      ),
      failed,
    )
    assertFalse(
      failed.contains("run :b:pitestConverge from the start"),
      "project :a aggregate selection changed project :b retry guidance:\n$failed",
    )
  }

  @Test
  fun `certification state is isolated between subprojects`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    enableFakeArcMutate()

    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(
        sharedBuild + if (name == "b") {
          """

            tasks.named("pitestEncoding") {
              mustRunAfter(":a:hardeningCertifyPreflight")
            }
          """.trimIndent() + "\n"
        } else {
          ""
        }
      )
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
      File(fixtureDir, "corpus").copyRecursively(subproject.resolve("corpus"))
    }
    val result = runner(":a:hardeningCertify", ":b:pitestEncoding").build()
    val aEvidence = PitestEvidence.parse(
      File(fixtureDir, "a/build/reports/pitest/encoding/.evidence.tsv").readText())
    val bEvidence = PitestEvidence.parse(
      File(fixtureDir, "b/build/reports/pitest/encoding/.evidence.tsv").readText())

    assertFalse(aEvidence.historyAssisted, result.output)
    assertTrue(bEvidence.historyAssisted, result.output)
    assertTrue(File(fixtureDir, "a/.pitest-history/pitest-certification.tsv").isFile)
    assertFalse(File(fixtureDir, "b/.pitest-history/pitest-certification.tsv").exists())
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").exists(),
      "direct project certification must not publish a Gradle-root aggregate manifest",
    )
    val aReceipt = File(fixtureDir, "a/.pitest-history/pitest-certification.tsv")
        .canonicalPath
    assertTrue(
      result.output.contains(
        "hardeningCertify: 1 project-scoped receipt published by this Gradle invocation; " +
          "1 suite certified total:\n  :a — 1 suite — $aReceipt",
      ),
      "the roll-up claimed an unselected project or omitted the completed receipt:\n${result.output}",
    )
    assertFalse(result.output.contains("\n  :b —"), result.output)
  }

  @Test
  fun `root certification aggregate continues sibling projects and preserves failure`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    File(fixtureDir, "build.gradle.kts").writeText("")
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(
        sharedBuild + if (name == "b") {
          """

            tasks.named("pitestEncoding") {
              mustRunAfter(":a:pitestEncodingVerify")
            }
          """.trimIndent() + "\n"
        } else {
          ""
        },
      )
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
      File(fixtureDir, "corpus").copyRecursively(subproject.resolve("corpus"))
    }
    initializeGitFixture()
    val initial = runner(":hardeningCertifyAll").build()
    val aggregateManifest =
        File(fixtureDir, ".pitest-history/pitest-certification-all.tsv")
    val aggregateRunning =
        File(fixtureDir, ".pitest-history/pitest-certification-all.running")
    assertTrue(aggregateManifest.isFile, "aggregate manifest was not published:\n${initial.output}")
    assertFalse(aggregateRunning.exists(), "successful aggregate retained its running sentinel")
    val initialManifestBytes = aggregateManifest.readBytes()
    val initialManifestText = initialManifestBytes.toString(Charsets.UTF_8)
    val initialProjects = aggregateProjectRows(initialManifestText)
    assertEquals(listOf(":a", ":b"), initialProjects.map { it[1] })
    assertTrue(initialManifestText.contains("suiteCount\t2\n"), initialManifestText)
    assertEquals(
      listOf("suite\t:a\tencoding", "suite\t:b\tencoding"),
      initialManifestText.lineSequence().filter { it.startsWith("suite\t") }.toList(),
    )
    initialProjects.forEach { project ->
      assertEquals("1", project[4])
      assertTrue(project[2].startsWith("root:"), project[2])
      val childReceipt = fixtureDir.resolve(project[2].removePrefix("root:"))
      assertEquals(PitestEvidence.sha256(childReceipt), project[3])
    }
    val aReceipt = File(fixtureDir, "a/.pitest-history/pitest-certification.tsv")
    val aLastSuccess = aReceipt.readBytes()
    File(fixtureDir, "a/fake-pit-mode.txt").writeText("timeout\n")

    val failed = runner(":hardeningCertifyAll").buildAndFail()

    assertTrue(
      failed.output.contains(
        "no audited set covers 1 physical TIMED_OUT mutant instance across 1 line-less key",
      ),
      failed.output,
    )
    assertEquals(
      aLastSuccess.toList(),
      aReceipt.readBytes().toList(),
      "failing project destroyed its last successful receipt",
    )
    assertTrue(
      File(fixtureDir, "a/.pitest-history/pitest-certification.running").readText()
        .startsWith("refused\t"),
      "failing project did not retain its running sentinel",
    )
    assertTrue(
      File(fixtureDir, "b/.pitest-history/pitest-certification.tsv").isFile,
      "sibling project was blocked after another aggregate finalizer failed:\n${failed.output}",
    )
    assertFalse(
      File(fixtureDir, "b/.pitest-history/pitest-certification.running").exists(),
      "successful sibling retained its running sentinel",
    )
    assertTrue(
      failed.output.contains(":b:hardeningCertify: 1 suite certified"),
      "successful sibling did not finish visibly:\n${failed.output}",
    )
    assertArrayEquals(
      initialManifestBytes,
      aggregateManifest.readBytes(),
      "failing aggregate replaced its last successful root manifest",
    )
    assertTrue(aggregateRunning.isFile, "failing aggregate did not retain its root sentinel")
    val bReceipt = File(fixtureDir, "b/.pitest-history/pitest-certification.tsv")
    val bFirstSuccess = bReceipt.readBytes()

    assertTrue(File(fixtureDir, "a/fake-pit-mode.txt").delete())
    val retried = runner(":hardeningCertifyAll").build()
    assertTrue(
      retried.output.contains("Configuration cache entry reused."),
      "root aggregate did not reuse its configuration graph:\n${retried.output}",
    )
    assertEquals(TaskOutcome.SUCCESS, retried.task(":a:pitestEncoding")?.outcome, retried.output)
    assertEquals(TaskOutcome.SUCCESS, retried.task(":b:pitestEncoding")?.outcome, retried.output)
    assertFalse(
      aLastSuccess.contentEquals(aReceipt.readBytes()),
      "aggregate retry reused the failing project's preserved receipt",
    )
    assertFalse(
      bFirstSuccess.contentEquals(bReceipt.readBytes()),
      "aggregate retry reused the sibling project's earlier receipt",
    )
    assertFalse(File(fixtureDir, "a/.pitest-history/pitest-certification.running").exists())
    assertFalse(aggregateRunning.exists(), "successful aggregate retry retained its root sentinel")
    assertFalse(
      initialManifestBytes.contentEquals(aggregateManifest.readBytes()),
      "successful aggregate retry reused the prior root manifest",
    )
    aggregateProjectRows(aggregateManifest.readText()).forEach { project ->
      val childReceipt = fixtureDir.resolve(project[2].removePrefix("root:"))
      assertEquals(PitestEvidence.sha256(childReceipt), project[3])
    }
  }

  @Test
  fun `root transition preflight consolidates every required rebase before PIT`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    File(fixtureDir, "build.gradle.kts").writeText("")
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(sharedBuild)
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
      File(fixtureDir, "corpus").copyRecursively(subproject.resolve("corpus"))
      subproject.resolve("config/pitest/encoding-accepted.csv").also { baseline ->
        baseline.parentFile.mkdirs()
        baseline.writeText("!sava-hardening-baseline-schema,1\n")
      }
    }

    runner(
      ":a:pitestEncodingBaselineRebase",
      ":b:pitestEncodingBaselineRebase",
    ).build()
    listOf("a", "b").forEach { name ->
      val config = File(fixtureDir, "$name/config/pitest")
      val pitVersion = config.resolve("encoding-pitest-version")
      val toolchain = config.resolve("encoding-pitest-toolchain.tsv")
      val priorVersion = pitVersion.readText().trim()
      if (name == "a") {
        val transitionVersion = "0.0.0-preflight-fixture"
        pitVersion.writeText("$transitionVersion\n")
        toolchain.writeText(
          toolchain.readText().replace(
            "pitest\t$priorVersion\n",
            "pitest\t$transitionVersion\n",
          ),
        )
      } else {
        toolchain.writeText(
          toolchain.readText().replace(
            Regex("(?m)^junitPlugin\\t[^\\r\\n]+$"),
            "junitPlugin\t0.0.0-preflight-fixture",
          ),
        )
      }
      assertTrue(
        File(fixtureDir, "$name/build/reports/pitest").deleteRecursively(),
        "fixture could not clear prior PIT output for $name",
      )
    }

    val failed = runner(":hardeningCertifyAll", "--continue").buildAndFail()

    assertEquals(
      TaskOutcome.FAILED,
      failed.task(":hardeningCertifyAllPreflight")?.outcome,
      failed.output,
    )
    assertTrue(failed.task(":hardeningCertifyAll") == null, failed.output)
    listOf("a", "b").forEach { name ->
      assertTrue(failed.task(":$name:pitestEncoding") == null, failed.output)
      assertTrue(failed.task(":$name:hardeningCertify") == null, failed.output)
      assertFalse(
        File(fixtureDir, "$name/build/reports/pitest/encoding/mutations.csv").exists(),
        "PIT ran for $name after the root transition refusal",
      )
      assertFalse(
        File(fixtureDir, "$name/.pitest-history/pitest-certification.running").exists(),
        "child certification started for $name after the root transition refusal",
      )
      assertTrue(
        failed.output.contains(":$name :: encoding") &&
          failed.output.contains("writer: :$name:pitestEncodingBaselineRebase"),
        failed.output,
      )
    }
    assertTrue(
      failed.output.contains(
        "mutation-transition preflight found 2 suites requiring a reviewed " +
          "BaselineRebase before certification; the aggregate did not start child PIT or " +
          "invoke a baseline writer",
      ) && failed.output.contains("expected adoption stopping point"),
      failed.output,
    )
    assertTrue(
      failed.output.contains(
        "reason: committed PIT version 0.0.0-preflight-fixture differs from configured PIT",
      ) && failed.output.contains(
        "reason: committed PIT JUnit plugin 0.0.0-preflight-fixture differs from configured plugin",
      ),
      failed.output,
    )
    assertEquals(
      2,
      failed.output
        .substringAfter("* What went wrong:")
        .substringBefore("* Try:")
        .lineSequence()
        .count { it.trimStart().startsWith("writer: ") },
      failed.output,
    )
    val aggregateRunning =
      File(fixtureDir, ".pitest-history/pitest-certification-all.running")
    assertTrue(aggregateRunning.isFile, "root refusal record was not retained")
    assertTrue(aggregateRunning.readText().startsWith("refused\tmutation-transition preflight"))
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").exists())

    val repeated = runner(":hardeningCertifyAll", "--continue").buildAndFail()
    assertTrue(repeated.output.contains("Configuration cache entry reused."), repeated.output)
    assertTrue(repeated.task(":a:pitestEncoding") == null, repeated.output)
    assertTrue(repeated.task(":b:pitestEncoding") == null, repeated.output)
  }

  @Test
  fun `root certification aggregate refuses task exclusions that erase its project proofs`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\")\n")
    val child = File(fixtureDir, "a").apply { mkdirs() }
    child.resolve("build.gradle.kts").writeText(File(fixtureDir, "build.gradle.kts").readText())
    File(fixtureDir, "src").copyRecursively(child.resolve("src"))
    File(fixtureDir, "corpus").copyRecursively(child.resolve("corpus"))

    val failed = runner(":hardeningCertifyAll", "-x", "hardeningCertify").buildAndFail().output

    assertTrue(
      failed.contains(
        "cannot certify the exact registered aggregate inventory with task exclusion(s): " +
          "-x hardeningCertify",
      ),
      failed,
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertFalse(File(fixtureDir, "a/.pitest-history/pitest-certification.tsv").exists())
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").exists())
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification-all.running").isFile,
      "excluded aggregate did not durably invalidate any prior root manifest",
    )

    val bypass = runner(
      ":hardeningCertifyAll",
      "-x",
      "hardeningCertifyAllPreflight",
      "--continue",
    ).buildAndFail()
    assertTrue(
      bypass.output.contains(
        "cannot certify the exact registered aggregate inventory with task exclusion(s): " +
          "-x hardeningCertifyAllPreflight",
      ),
      bypass.output,
    )
    assertEquals(
      TaskOutcome.FAILED,
      bypass.task(":hardeningCertifyAllSelected")?.outcome,
      bypass.output,
    )
    assertTrue(bypass.task(":hardeningCertifyAll") == null, bypass.output)
    assertTrue(bypass.task(":a:pitestEncoding") == null, bypass.output)
  }

  @Test
  fun `aggregate completion task refuses direct selection without an authorized attempt`() {
    writeFixture()

    val failed = runner(":hardeningCertifyAllComplete").buildAndFail().output

    assertTrue(
      failed.contains("internal completion task") &&
        failed.contains("run :hardeningCertifyAll instead"),
      failed,
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").exists())
  }

  @Test
  fun `aggregate lock conflict preserves the owners manifest and sentinel`() {
    writeFixture()
    val history = File(fixtureDir, ".pitest-history").apply { mkdirs() }
    val manifest = history.resolve("pitest-certification-all.tsv").apply {
      writeText("aggregate receipt published by the owning process\n")
    }
    val ownerBytes = manifest.readBytes()
    val lockFile = history.resolve("pitest-certification-all.lock")

    FileChannel.open(
      lockFile.toPath(),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use {
        val failed = runner(":hardeningCertifyAll").buildAndFail().output
        assertTrue(failed.contains("another hardeningCertifyAll invocation owns"), failed)
      }
    }

    assertArrayEquals(ownerBytes, manifest.readBytes())
    assertFalse(
      history.resolve("pitest-certification-all.running").exists(),
      "non-owner overwrote the aggregate owner's sentinel",
    )
  }

  @Test
  fun `consumer doLast failure on aggregate anchor cannot publish a manifest`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-anchor-do-last-smoke-test"
      """.trimIndent() + "\n",
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
        tasks.named("hardeningCertifyAll") {
          doLast {
            throw GradleException("custom aggregate anchor doLast failure")
          }
        }
      """.trimIndent() + "\n",
    )
    initializeGitFixture()
    val manifest = File(
      fixtureDir,
      ".pitest-history/pitest-certification-all.tsv",
    ).apply {
      parentFile.mkdirs()
      writeText("prior aggregate success\n")
    }
    val priorManifest = manifest.readBytes()

    val failed = runner(":hardeningCertifyAll", "--continue").buildAndFail().output

    assertTrue(failed.contains("custom aggregate anchor doLast failure"), failed)
    assertTrue(
      failed.contains("aggregate publication skipped because the root anchor failed"),
      failed,
    )
    assertArrayEquals(
      priorManifest,
      manifest.readBytes(),
      "a consumer action failed after authorization but aggregate publication replaced prior proof",
    )
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification-all.running").isFile,
      "a post-authorization anchor failure did not preserve aggregate ineligibility",
    )
  }

  @Test
  fun `consumer doLast failure on child certification cannot publish an aggregate manifest`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-child-do-last-smoke-test"
      """.trimIndent() + "\n",
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
        tasks.named("hardeningCertify") {
          doLast {
            throw GradleException("custom child certification doLast failure")
          }
        }
      """.trimIndent() + "\n",
    )
    initializeGitFixture()
    val manifest = File(
      fixtureDir,
      ".pitest-history/pitest-certification-all.tsv",
    ).apply {
      parentFile.mkdirs()
      writeText("prior aggregate success\n")
    }
    val priorManifest = manifest.readBytes()

    val failed = runner(":hardeningCertifyAll", "--continue").buildAndFail().output

    assertTrue(failed.contains("custom child certification doLast failure"), failed)
    assertTrue(
      failed.contains("project certification task(s) failed or were skipped: :"),
      failed,
    )
    assertArrayEquals(
      priorManifest,
      manifest.readBytes(),
      "a child task failed after receipt recording but aggregate publication replaced prior proof",
    )
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification-all.running").isFile,
      "a post-record child failure did not preserve aggregate ineligibility",
    )
  }

  @Test
  fun `root certification aggregate refuses configuration on demand under a durable sentinel`() {
    writeFixture(projectRepositories = "")
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    File(fixtureDir, "build.gradle.kts").writeText("")
    val child = File(fixtureDir, "a").apply { mkdirs() }
    child.resolve("build.gradle.kts").writeText(sharedBuild)
    File(fixtureDir, "src").copyRecursively(child.resolve("src"))
    File(fixtureDir, "corpus").copyRecursively(child.resolve("corpus"))
    val settings = File(fixtureDir, "settings.gradle.kts")
    settings.writeText(
      settings.readText()
        .replace(
          "rootProject.name =",
          "plugins { id(\"software.sava.build\") }\n\nrootProject.name =",
        ) + "\ninclude(\"a\")\n"
    )
    initializeGitFixture()

    val failed = runner(
      ":hardeningCertifyAll",
      "--configure-on-demand",
      "--continue",
    ).buildAndFail().output

    assertTrue(
      failed.contains("configuration-on-demand can omit hardening projects"),
      failed,
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification-all.tsv").exists())
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification-all.running").isFile,
      "configuration-on-demand refusal did not invalidate prior aggregate evidence",
    )
  }

  @Test
  fun `certification closes with one sorted cross-project receipt roll-up`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "settings.gradle.kts").appendText("\ninclude(\"a\", \"b\")\n")
    enableFakeArcMutate()

    val sharedBuild = File(fixtureDir, "build.gradle.kts").readText()
    listOf("a", "b").forEach { name ->
      val subproject = File(fixtureDir, name).apply { mkdirs() }
      subproject.resolve("build.gradle.kts").writeText(sharedBuild)
      File(fixtureDir, "src").copyRecursively(subproject.resolve("src"))
      File(fixtureDir, "corpus").copyRecursively(subproject.resolve("corpus"))
    }
    val aReceipt = File(fixtureDir, "a/.pitest-history/pitest-certification.tsv")
        .canonicalPath
    val bReceipt = File(fixtureDir, "b/.pitest-history/pitest-certification.tsv")
        .canonicalPath
    val header =
      "hardeningCertify: 2 project-scoped receipts published by this Gradle invocation; " +
        "2 suites certified total:"

    fun assertRollUp(output: String) {
      assertEquals(1, output.split(header).size - 1, output)
      assertTrue(
        output.contains(":a:hardeningCertify: 1 suite certified; receipt: $aReceipt") &&
          output.contains(":b:hardeningCertify: 1 suite certified; receipt: $bReceipt"),
        "the immediate receipt lines were ambiguous or ungrammatical:\n$output",
      )
      val aRow = output.indexOf("  :a — 1 suite — $aReceipt")
      val bRow = output.indexOf("  :b — 1 suite — $bReceipt")
      assertTrue(aRow >= 0 && bRow > aRow, "the project receipt roll-up was missing or unsorted:\n$output")
    }

    val first = runner(":b:hardeningCertify", ":a:hardeningCertify").build()
    assertTrue(File(aReceipt).isFile && File(bReceipt).isFile, first.output)
    assertEquals(TaskOutcome.SUCCESS, first.task(":a:pitestEncoding")?.outcome, first.output)
    assertEquals(TaskOutcome.SUCCESS, first.task(":b:pitestEncoding")?.outcome, first.output)
    assertRollUp(first.output)

    val reused = runner(":b:hardeningCertify", ":a:hardeningCertify").build()
    assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
    assertEquals(TaskOutcome.SUCCESS, reused.task(":a:pitestEncoding")?.outcome, reused.output)
    assertEquals(TaskOutcome.SUCCESS, reused.task(":b:pitestEncoding")?.outcome, reused.output)
    assertRollUp(reused.output)
  }
}
