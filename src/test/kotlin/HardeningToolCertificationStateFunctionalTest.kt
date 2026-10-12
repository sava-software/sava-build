import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PrunePreviewState
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Durable certification state, ownership sentinels, and completion boundaries. */
class HardeningToolCertificationStateFunctionalTest : HardeningToolExecFixture() {

  @Test
  fun `durable certification survives clean and retires the configured legacy location`() {
    writeFixture(
      buildTail =
        "layout.buildDirectory.set(layout.projectDirectory.dir(\"external-build\"))",
    )
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val legacyReceipt = File(fixtureDir, "external-build/hardening/pitest-certification.tsv").apply {
      parentFile.mkdirs()
      writeText("legacy receipt\n")
    }
    val legacyRunning = File(fixtureDir, "external-build/hardening/pitest-certification.running").apply {
      writeText("legacy interruption\n")
    }

    val migrated = runner("hardeningCertify").build()

    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val certifiedBytes = receipt.readBytes()
    assertTrue(
      migrated.output.contains("removed superseded legacy build-output certification state") &&
          migrated.output.contains(".pitest-history/pitest-certification.tsv") &&
          migrated.output.contains(legacyReceipt.absolutePath) &&
          migrated.output.contains(legacyRunning.absolutePath),
      "legacy certification migration was silent:\n${migrated.output}",
    )
    assertFalse(legacyReceipt.exists(), "legacy build-output receipt survived certification")
    assertFalse(legacyRunning.exists(), "legacy build-output sentinel survived certification")
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.running").exists(),
      "successful certification retained its durable sentinel",
    )

    runner("clean").build()

    assertTrue(receipt.isFile, "clean erased durable certification evidence")
    assertEquals(certifiedBytes.toList(), receipt.readBytes().toList())
  }

  @Test
  fun `ordinary quality gate failure preserves last success under the running sentinel`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    assertTrue(receipt.isFile)
    val priorReceipt = receipt.readBytes()

    File(fixtureDir, "build.gradle.kts").appendText(
      """

      tasks.named("test") {
        doLast { throw GradleException("deliberate ordinary test failure") }
      }
      """.trimIndent() + "\n",
    )
    val failed = runnerWithoutConfigurationCache("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("deliberate ordinary test failure"), failed)
    assertEquals(
      priorReceipt.toList(),
      receipt.readBytes().toList(),
      "failed certification destroyed the last successful receipt",
    )
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").readText().startsWith(
        "refused\thardeningCertify ended without publishing its receipt; " +
          "no verified fresh observation in this invocation for: encoding"),
      "failed certification did not retain its durable invalidation sentinel",
    )
  }

  @Test
  fun `routine certification does not advance prune preview state`() {
    writeFixture(moneyMath = true)
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        "com.example.Codec,gone,MathMutator,SURVIVED # reviewed removal candidate # line 7\n",
      )
    }
    runner("pitestEncodingBaselineRebase").build()
    val previewState = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    assertFalse(previewState.exists(), "rebase unexpectedly created prune-preview state")

    val preview = runner("pitestEncoding", "-PnoMutationHistory").build()
    assertEquals(TaskOutcome.SUCCESS, preview.task(":pitestEncoding")?.outcome, preview.output)
    assertTrue(previewState.isFile, "history-free observation did not persist prune state")
    val previewBytes = previewState.readBytes()
    val previewRecord = PrunePreviewState.parse(previewState.readText())
    assertEquals(1, previewRecord.matchingObservations, preview.output)

    val first = runner("hardeningCertify").build()
    val second = runner("hardeningCertify").build()

    listOf(first.output, second.output).forEach { output ->
      assertTrue(
        output.contains(
          "This certification observation does not advance prune-preview state; " +
              "release proof is not implicit preparation for a destructive baseline write.",
        ),
        output,
      )
    }
    assertArrayEquals(
      previewBytes,
      previewState.readBytes(),
      "two routine certifications changed an existing prune-preview sequence",
    )
    assertEquals(1, PrunePreviewState.parse(previewState.readText()).matchingObservations)

    val replay = runner("pitestEncodingVerify").build().output
    assertArrayEquals(
      previewBytes,
      previewState.readBytes(),
      "standalone Verify recycled certification evidence into a prune preview",
    )
    assertTrue(
      replay.contains(
        "This revalidated prior-report preview does not advance prune-preview state; " +
            "replaying Verify is not another PIT observation.",
      ),
      replay,
    )
  }

  @Test
  fun `legacy state and a newer certification still require two post-migration previews`() {
    writeFixture(moneyMath = true)
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest/encoding-accepted.csv").apply {
      parentFile.mkdirs()
      writeText(
        "com.example.Codec,gone,MathMutator,SURVIVED # reviewed removal candidate # line 7\n",
      )
    }
    runner("pitestEncodingBaselineRebase").build()

    // P1 represents the last report referenced by a format-1 stash. Format 1 may
    // also claim two observations because routine certification used to advance it;
    // neither claim is trusted across the origin-semantics format boundary.
    runner("pitestEncoding", "-PnoMutationHistory").build()
    val previewState = File(fixtureDir, ".pitest-history/encoding.prune-previews")
    val p1 = PrunePreviewState.parse(previewState.readText())
    val legacy = p1.copy(matchingObservations = 2, qualifies = true).render().replaceFirst(
      PrunePreviewState.FORMAT_HEADER,
      PrunePreviewState.LEGACY_FORMAT_1_HEADER,
    ).replaceFirst(
      "${PrunePreviewState.OBSERVATION_ELIGIBLE_PREFIX}true",
      "# qualifies true",
    )
    previewState.writeText(legacy)

    // C1 produces a newer full report, but certification and a later standalone
    // typed revalidation of that report must both leave the legacy stash untouched.
    val certification = runner("hardeningCertify").build().output
    assertTrue(
      certification.contains(
        "This certification observation does not advance prune-preview state; " +
            "release proof is not implicit preparation for a destructive baseline write.",
      ),
      certification,
    )
    assertEquals(legacy, previewState.readText())

    val replay = runner("pitestEncodingVerify").build().output
    assertTrue(
      replay.contains(
        "This revalidated prior-report preview does not advance prune-preview state; " +
            "replaying Verify is not another PIT observation.",
      ),
      replay,
    )
    assertEquals(legacy, previewState.readText())

    // P2 is the first trusted post-migration observation. The writer's next fresh
    // run may become observation two, but it must refuse because two previews were
    // not complete before that destructive workflow began. A later writer is the
    // third comparison and may apply the reviewed deletion.
    runner("pitestEncoding", "-PnoMutationHistory").build()
    val p2 = PrunePreviewState.parse(previewState.readText())
    assertEquals(1, p2.matchingObservations)
    assertTrue(p2.qualifies)

    val refused = runner("pitestEncodingBaselinePrune").buildAndFail().output
    assertTrue(
      refused.contains("advances the stored sequence to 2 matching observation(s)") &&
          refused.contains("were not complete before this destructive workflow began"),
      refused,
    )
    assertTrue(
      File(fixtureDir, "config/pitest/encoding-accepted.csv").isFile,
      "the second post-migration observation deleted before review",
    )
    assertEquals(2, PrunePreviewState.parse(previewState.readText()).matchingObservations)

    val applied = runner("pitestEncodingBaselinePrune").build().output
    assertTrue(applied.contains("prune dropped every row unmatched by this run"), applied)
    assertFalse(
      File(fixtureDir, "config/pitest/encoding-accepted.csv").exists(),
      "the reviewed third comparison did not apply the candidate deletion",
    )
  }

  @Test
  fun `certification refuses an unignored durable state path before PIT`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    initializeGitFixture()
    File(fixtureDir, ".gitignore").writeText(".gradle/\nbuild/\n")
    git("add", ".gitignore")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "remove machine-local ignore",
    )

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("durable certification state must be machine-local"), failed)
    assertTrue(failed.contains("is not Git-ignored"), failed)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").exists(),
      "PIT ran before ignore refusal",
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists())
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.running").exists())
  }

  @Test
  fun `certification refuses a linked durable state directory before PIT`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val target = File(fixtureDir, "linked-history-target").apply { mkdirs() }
    Files.createSymbolicLink(
      File(fixtureDir, ".pitest-history").toPath(),
      target.toPath(),
    )

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("symbolic-link component"), failed)
    assertFalse(
      File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").exists(),
      "PIT ran before linked-state refusal",
    )
  }

  @Test
  fun `a non-owner certification cannot replace durable evidence`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val history = File(fixtureDir, ".pitest-history").apply { mkdirs() }
    val receipt = history.resolve("pitest-certification.tsv").apply {
      writeText("receipt published by the owning process\n")
    }
    val ownerBytes = receipt.readBytes()
    val lockFile = history.resolve("pitest-certification.lock")

    FileChannel.open(
      lockFile.toPath(),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use {
        val failed = runner("hardeningCertify").buildAndFail().output
        assertTrue(failed.contains("another hardeningCertify invocation owns"), failed)
      }
    }

    assertEquals(ownerBytes.toList(), receipt.readBytes().toList())
    assertFalse(history.resolve("pitest-certification.running").exists())
  }

  @Test
  fun `certification restores a deleted ownership sentinel over last success`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val priorReceipt = receipt.readBytes()
    File(fixtureDir, "fake-pit-mode.txt").writeText("delete-certification-sentinel\n")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("does not own the exact durable session sentinel"), failed)
    assertProjectAtomicCertificationRetry(failed)
    assertArrayEquals(
      priorReceipt,
      receipt.readBytes(),
      "deleted sentinel exposed or destroyed the last successful receipt",
    )
    val running = File(fixtureDir, ".pitest-history/pitest-certification.running")
    assertTrue(running.isFile, "deleted certification sentinel was not restored")
    assertTrue(running.readText().startsWith("refused\t"), running.readText())
  }

  @Test
  fun `certification replaces a tampered ownership sentinel over last success`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val priorReceipt = receipt.readBytes()
    File(fixtureDir, "fake-pit-mode.txt").writeText("tamper-certification-sentinel\n")

    val failed = runner("hardeningCertify").buildAndFail().output

    assertTrue(failed.contains("does not own the exact durable session sentinel"), failed)
    assertArrayEquals(priorReceipt, receipt.readBytes())
    val running = File(fixtureDir, ".pitest-history/pitest-certification.running")
    assertTrue(running.isFile)
    assertTrue(running.readText().startsWith("refused\t"), running.readText())
  }

  @Test
  fun `fuzz completion restores a deleted sentinel over last success`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val priorReceipt = receipt.readBytes()
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("delete-fuzz-sentinel\n")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail().output

    assertTrue(failed.contains("campaign ownership sentinel changed"), failed)
    assertArrayEquals(
      priorReceipt,
      receipt.readBytes(),
      "deleted fuzz sentinel exposed or destroyed the last successful receipt",
    )
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")
    assertTrue(running.isFile, "deleted fuzz sentinel was not restored")
    // fuzzAllComplete's own reason survives: the build-end refusal must not replace it
    assertTrue(
      running.readText().startsWith("refused\t") &&
        running.readText().contains("campaign ownership sentinel changed"),
      running.readText(),
    )
  }

  @Test
  fun `fuzz completion replaces a tampered sentinel over last success`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    runner("fuzzAll", "-PmaxFuzzTime=1").build()
    val receipt = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
    val priorReceipt = receipt.readBytes()
    File(fixtureDir, "fake-fuzz-mode.txt").writeText("tamper-fuzz-sentinel\n")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail().output

    assertTrue(failed.contains("campaign ownership sentinel changed"), failed)
    assertArrayEquals(priorReceipt, receipt.readBytes())
    val running = File(fixtureDir, ".pitest-history/local-fuzz.running")
    assertTrue(running.isFile)
    // fuzzAllComplete's own reason survives: the build-end refusal must not replace it
    assertTrue(
      running.readText().startsWith("refused\t") &&
        running.readText().contains("campaign ownership sentinel changed"),
      running.readText(),
    )
  }

  @Test
  fun `a source that appears after PIT is refused at the final boundary and recorded`() {
    writeFixture(buildTail = """
      val dropScratch = tasks.register("dropScratch") {
        val scratch = layout.projectDirectory.file("src/test/java/com/example/Integ.java")
        mustRunAfter("pitestEncodingVerify")
        doLast { scratch.asFile.writeText("package com.example; final class Integ {}\n") }
      }
      tasks.named("hardeningCertify") { dependsOn(dropScratch) }
    """.trimIndent())
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    initializeGitFixture(listOf("Integ.java"))
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))

    val refused = runner("hardeningCertify").buildAndFail()

    assertEquals(TaskOutcome.SUCCESS, refused.task(":pitestEncoding")?.outcome, refused.output)
    assertEquals(TaskOutcome.FAILED, refused.task(":hardeningCertify")?.outcome, refused.output)
    assertTrue(File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").isFile, refused.output)
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists(), refused.output)
    val running = File(fixtureDir, ".pitest-history/pitest-certification.running").readText()
    assertTrue(
      running.startsWith("refused\thardeningCertify: clean Git certification cannot bind 1 source input(s)") &&
        running.contains("src/test/java/com/example/Integ.java"),
      running,
    )
  }

  @Test
  fun `an attempt whose PIT failed ends refused naming the suites it did not verify`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "fake-pit-mode.txt").writeText("fail\n")

    val failed = runner("hardeningCertify").buildAndFail()

    assertEquals(TaskOutcome.FAILED, failed.task(":pitestEncoding")?.outcome, failed.output)
    assertTrue(failed.task(":hardeningCertify") == null, failed.output)
    assertEquals(
      "refused\thardeningCertify ended without publishing its receipt; " +
        "no verified fresh observation in this invocation for: encoding\n",
      File(fixtureDir, ".pitest-history/pitest-certification.running").readText(),
    )
  }
}
