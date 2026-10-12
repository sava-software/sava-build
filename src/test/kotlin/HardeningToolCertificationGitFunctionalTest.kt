import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.io.File

/** Git provenance and source/record input validation for clean certification. */
class HardeningToolCertificationGitFunctionalTest : HardeningToolExecFixture() {

  @Test
  fun `certification binds a clean Git commit and remains visibly stale after HEAD advances`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (certifiedCommit, certifiedTree) = initializeGitFixture()

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val receiptAtA = receipt.readText()
    val statusAtA = git("status", "--porcelain=v1", "--untracked-files=all")
    assertTrue(receiptAtA.contains("schema\t8\n"), receiptAtA)
    assertTrue(receiptAtA.contains("gitState\tclean\n"), "status=$statusAtA\n$receiptAtA")
    assertTrue(receiptAtA.contains("gitCommit\t$certifiedCommit\n"), receiptAtA)
    assertTrue(receiptAtA.contains("gitTree\t$certifiedTree\n"), receiptAtA)
    assertTrue(
      receiptAtA.contains("gitStatusSha256\t${PitestEvidence.sha256(byteArrayOf())}\n"),
      receiptAtA,
    )
    assertTrue(receiptAtA.contains("gitProjectDirectory\t.\n"), receiptAtA)
    assertTrue(statusAtA.isEmpty(), "${certified.output}\n$statusAtA")

    File(fixtureDir, "post-certification.txt").writeText("advance the clean checkout\n")
    git("add", "post-certification.txt")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "advance after certification",
    )
    val currentCommit = git("rev-parse", "HEAD")
    val currentTree = git("rev-parse", "HEAD^{tree}")
    assertFalse(currentCommit == certifiedCommit)
    assertFalse(currentTree == certifiedTree)
    assertTrue(git("status", "--porcelain=v1", "--untracked-files=all").isEmpty())
    assertEquals(receiptAtA, receipt.readText(), "advancing HEAD rewrote the prior certification receipt")
    assertTrue(receipt.readText().contains("gitCommit\t$certifiedCommit\n"))
    assertFalse(receipt.readText().contains("gitCommit\t$currentCommit\n"))
  }

  @Test
  fun `clean certification refuses an ignored source before PIT and records why`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    initializeGitFixture(listOf("Integ.java"))
    runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
    val lastSuccess = receipt.readBytes()
    File(fixtureDir, "build/reports/pitest").deleteRecursively()
    File(fixtureDir, "src/test/java/com/example/Integ.java")
      .writeText("package com.example; final class Integ {}\n")
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))

    val refused = runner("hardeningCertify").buildAndFail()

    assertEquals(TaskOutcome.FAILED, refused.task(":hardeningCertifyPreflight")?.outcome, refused.output)
    assertTrue(refused.task(":pitestEncoding") == null, refused.output)
    assertFalse(File(fixtureDir, "build/reports/pitest/encoding/mutations.csv").exists(), refused.output)
    assertTrue(
      refused.output.contains("clean Git certification cannot bind 1 source input(s) absent from its captured tree") &&
        refused.output.contains("  src/test/java/com/example/Integ.java") &&
        refused.output.contains("run the certification from a detached worktree of this commit"),
      refused.output,
    )
    val running = File(fixtureDir, ".pitest-history/pitest-certification.running").readText()
    assertTrue(
      running.startsWith("refused\t") &&
        running.contains("cannot bind 1 source input(s)") &&
        running.contains("src/test/java/com/example/Integ.java") &&
        running.endsWith("\n") && running.count { it == '\n' } == 1 && running.count { it == '\t' } == 1,
      running,
    )
    assertArrayEquals(lastSuccess, receipt.readBytes(), "a refused attempt rewrote the last receipt")
  }

  @Test
  fun `clean certification refuses ignored source inputs under the source roots`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (commit, _) = initializeGitFixture(listOf("Integ.java", "alias.txt", ".sub-source/"))
    val ignoredSource = File(fixtureDir, "src/main/java/com/example/Integ.java")
    ignoredSource.writeText("package com.example; final class Integ {}\n")
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))

    val refused = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      refused.contains("clean Git certification cannot bind 1 source input(s) absent from its captured tree") &&
        refused.contains("  src/main/java/com/example/Integ.java") &&
        refused.contains("Commit them, move them to a source set the hardening plugin never reads (a git-ignored " +
          "scratch test suite; HARDENING.md, The class path is PIT's world), or generate them under the build directory") &&
        refused.contains("run the certification from a detached worktree of this commit " +
          "(git worktree add --detach <dir> $commit)") &&
        refused.contains("the receipt and every other .pitest-history/ file that run writes stay in that worktree") &&
        refused.contains("The .running marker left beside the receipt is the retained record of this refusal"),
      refused,
    )
    assertFalse(File(fixtureDir, ".pitest-history/pitest-certification.tsv").exists(), refused)
    // the message's claim about the marker, checked against the marker
    assertTrue(
      File(fixtureDir, ".pitest-history/pitest-certification.running").readText().startsWith("refused\t"),
      refused,
    )
    assertTrue(ignoredSource.delete())

    // An ignored symlink to a tracked file is refused under its own name: the
    // fingerprint records the alias, which no clean checkout has.
    val alias = File(fixtureDir, "src/main/resources/alias.txt")
    alias.parentFile.mkdirs()
    java.nio.file.Files.createSymbolicLink(alias.toPath(), java.nio.file.Paths.get("../../../build.gradle.kts"))
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))
    val aliasRefused = runner("hardeningCertify").buildAndFail().output
    assertTrue(
      aliasRefused.contains("cannot bind 1 source input(s)") &&
        aliasRefused.contains("  src/main/resources/alias.txt"),
      aliasRefused,
    )
    assertTrue(alias.delete())

    // A tracked resource inside a pinned submodule is committed content, not a leak.
    val subSource = File(fixtureDir, ".sub-source").apply { mkdirs() }
    git(subSource, "init", "--quiet", "--initial-branch=main")
    subSource.resolve("inside.txt").writeText("committed in the submodule\n")
    git(subSource, "add", "inside.txt")
    git(
      subSource, "-c", "user.name=Hardening Fixture", "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "submodule",
    )
    git("-c", "protocol.file.allow=always", "submodule", "add", "--quiet", "./.sub-source", "src/main/resources/sub")
    git(
      "-c", "user.name=Hardening Fixture", "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "add submodule",
    )
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))
    assertTrue(File(fixtureDir, "src/main/resources/sub/inside.txt").isFile)

    val certified = runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()
    assertTrue(
      receipt.contains("schema\t8\n") && receipt.contains("gitState\tclean\n"),
      "$receipt\n${certified.output}",
    )
  }

  @Test
  fun `clean certification resolves tracked symlinks and submodule trees before accepting inputs`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    // Tracked content a tracked symlink may point at, and an ignored place it must not.
    File(fixtureDir, "notes").mkdirs()
    File(fixtureDir, "notes/tracked.txt").writeText("tracked referent\n")
    File(fixtureDir, "scratch").mkdirs()
    File(fixtureDir, "scratch/secret.txt").writeText("ignored referent\n")
    val resources = File(fixtureDir, "src/main/resources").apply { mkdirs() }
    java.nio.file.Files.createSymbolicLink(
      resources.resolve("tracked-link").toPath(), java.nio.file.Paths.get("../../../notes/tracked.txt"),
    )
    java.nio.file.Files.createSymbolicLink(
      resources.resolve("leak").toPath(), java.nio.file.Paths.get("../../../scratch/secret.txt"),
    )
    // A tracked directory link, a file reached through it, and a link whose `..` only
    // resolves through that directory link: lexical path arithmetic gets the last one wrong.
    java.nio.file.Files.createSymbolicLink(
      resources.resolve("notes-link").toPath(), java.nio.file.Paths.get("../../../notes"),
    )
    java.nio.file.Files.createSymbolicLink(
      resources.resolve("sneaky").toPath(), java.nio.file.Paths.get("notes-link/../scratch/secret.txt"),
    )
    // An ignored directory link to tracked content: the bytes are the tree's, but the
    // name the fingerprint recorded exists in no clean checkout.
    java.nio.file.Files.createSymbolicLink(
      resources.resolve("ignored-alias").toPath(), java.nio.file.Paths.get("../../../notes"),
    )
    initializeGitFixture(listOf("scratch/", ".sub-source/", "ignored-alias"))
    // A pinned submodule whose own .gitignore hides a file from both repositories' status.
    val subSource = File(fixtureDir, ".sub-source").apply { mkdirs() }
    git(subSource, "init", "--quiet", "--initial-branch=main")
    subSource.resolve(".gitignore").writeText("ignored.txt\n")
    subSource.resolve("inside.txt").writeText("committed in the submodule\n")
    git(subSource, "add", ".gitignore", "inside.txt")
    git(
      subSource, "-c", "user.name=Hardening Fixture", "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "submodule",
    )
    git("-c", "protocol.file.allow=always", "submodule", "add", "--quiet", "./.sub-source", "src/main/resources/sub")
    git(
      "-c", "user.name=Hardening Fixture", "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "add submodule",
    )
    File(fixtureDir, "src/main/resources/sub/ignored.txt").writeText("ignored inside the submodule\n")
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))

    assertEquals("ignored referent\n", File(fixtureDir, "src/main/resources/sneaky").readText())
    val refused = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      refused.contains("cannot bind 4 source input(s)") &&
        refused.contains("  src/main/resources/ignored-alias/tracked.txt -> notes/tracked.txt") &&
        refused.contains("  src/main/resources/leak -> scratch/secret.txt") &&
        refused.contains("  src/main/resources/sneaky -> scratch/secret.txt") &&
        refused.contains("  src/main/resources/sub/ignored.txt") &&
        !refused.contains("tracked-link") && !refused.contains("notes-link") &&
        !refused.contains("inside.txt"),
      refused,
    )

    assertTrue(File(fixtureDir, "src/main/resources/sub/ignored.txt").delete())
    assertTrue(File(fixtureDir, "src/main/resources/ignored-alias").delete())
    git("rm", "--quiet", "src/main/resources/leak", "src/main/resources/sneaky")
    git(
      "-c", "user.name=Hardening Fixture", "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "drop the leak",
    )
    assertEquals("", git("status", "--porcelain=v1", "--untracked-files=all"))
    val certified = runner("hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()
    assertTrue(receipt.contains("gitState\tclean\n"), "$receipt\n${certified.output}")
  }

  @Test
  fun `certification records an explicit dirty Git state without refusing local use`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val (commit, tree) = initializeGitFixture()
    File(fixtureDir, "dirty-marker.txt").writeText("deliberately uncommitted\n")

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()

    assertTrue(receipt.contains("gitState\tdirty\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$commit\n"), receipt)
    assertTrue(receipt.contains("gitTree\t$tree\n"), receipt)
    assertTrue(
      Regex("(?m)^gitStatusSha256\\t(?!${PitestEvidence.sha256(byteArrayOf())}$)[0-9a-f]{64}$")
        .containsMatchIn(receipt),
      receipt,
    )
    assertTrue(certified.output.contains("1 suite certified"), certified.output)
  }

  @Test
  fun `clean certification refuses ignored record inputs absent from the Git tree`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    File(fixtureDir, "config/pitest/README.md").also { file ->
      file.parentFile.mkdirs()
      file.writeText("# Ignored mutation rationale\n")
    }
    initializeGitFixture(listOf("config/pitest/README.md"))
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "the ignored record did not reproduce Git's false-clean state",
    )

    val failed = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      failed.contains("clean Git certification cannot bind mutation-record inputs to its captured tree") &&
          failed.contains("present locally but absent from captured tree") &&
          failed.contains("config/pitest/README.md"),
      failed,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "a rejected ignored record left a certification receipt",
    )
  }

  @Test
  fun `rebase warns when Git ignores its provenance and clean certification still refuses`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    File(configDir, "encoding-accepted.csv")
      .writeText("!sava-hardening-baseline-schema,1\n")
    initializeGitFixture(listOf(
      "config/pitest/*-pitest-version",
      "config/pitest/*-pitest-toolchain.tsv",
    ))

    val rebase = runner("pitestEncodingBaselineRebase").build().output
    val pitVersion = File(configDir, "encoding-pitest-version")
    val toolchain = File(configDir, "encoding-pitest-toolchain.tsv")
    assertTrue(pitVersion.isFile && toolchain.isFile, rebase)
    assertTrue(
      rebase.contains("wrote mutation-record input(s) that Git ignores") &&
          rebase.contains("ordinary git status can hide them") &&
          rebase.contains("clean hardeningCertify will refuse") &&
          rebase.contains("encoding-pitest-version") &&
          rebase.contains("encoding-pitest-toolchain.tsv"),
      rebase,
    )
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "ignored provenance did not reproduce the false-clean porcelain state",
    )

    val refused = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      refused.contains("clean Git certification cannot bind mutation-record inputs to its captured tree") &&
          refused.contains("present locally but absent from captured tree") &&
          refused.contains("encoding-pitest-version") &&
          refused.contains("encoding-pitest-toolchain.tsv"),
      refused,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "ignored provenance left a clean certification receipt",
    )

    git("add", "-f", "config/pitest/encoding-pitest-version",
      "config/pitest/encoding-pitest-toolchain.tsv")
    git(
      "-c", "core.hooksPath=/dev/null",
      "-c", "user.name=Hardening Fixture",
      "-c", "user.email=hardening-fixture@example.invalid",
      "commit", "--quiet", "-m", "track mutation provenance",
    )
    val trackedCommit = git("rev-parse", "HEAD")
    val trackedRebase = runner("pitestEncodingBaselineRebase").build().output
    assertFalse(
      trackedRebase.contains("wrote mutation-record input(s) that Git ignores"),
      trackedRebase,
    )
    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()
    assertTrue(certified.output.contains("1 suite certified"), certified.output)
    assertTrue(receipt.contains("gitState\tclean\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$trackedCommit\n"), receipt)
  }

  @Test
  fun `rebase does not warn for provenance exempted by negated ignore rules`() {
    writeFixture()
    val configDir = File(fixtureDir, "config/pitest").apply { mkdirs() }
    File(configDir, "encoding-accepted.csv")
      .writeText("!sava-hardening-baseline-schema,1\n")
    initializeGitFixture(listOf(
      "config/pitest/*-pitest-version",
      "config/pitest/*-pitest-toolchain.tsv",
      "!config/pitest/encoding-pitest-version",
      "!config/pitest/encoding-pitest-toolchain.tsv",
    ))

    val rebase = runner("pitestEncodingBaselineRebase").build().output
    assertFalse(rebase.contains("wrote mutation-record input(s) that Git ignores"), rebase)
    val status = git("status", "--porcelain=v1", "--untracked-files=all")
    assertTrue(
      status.contains("config/pitest/encoding-pitest-version"),
      "the negated rule did not expose the new provenance to Git",
    )
    assertTrue(
      status.contains("config/pitest/encoding-pitest-toolchain.tsv"),
      "the negated rule did not expose the new toolchain provenance to Git",
    )
  }

  @Test
  fun `clean certification detects record changes hidden by Git index flags`() {
    writeFixture()
    writeSeedCorpus()
    File(fixtureDir, "corpus/hollow").apply { mkdirs() }.resolve("seed").writeText("hollow")
    val recordReadme = File(fixtureDir, "config/pitest/README.md").also { file ->
      file.parentFile.mkdirs()
      file.writeText("# Reviewed mutation rationale\n")
    }
    initializeGitFixture()

    git("update-index", "--assume-unchanged", "config/pitest/README.md")
    recordReadme.writeText("# Locally changed rationale hidden from porcelain status\n")
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "assume-unchanged did not reproduce Git's false-clean content state",
    )
    val changed = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      changed.contains("Git-normalized content differs from captured tree") &&
          changed.contains("config/pitest/README.md"),
      changed,
    )

    recordReadme.writeText("# Reviewed mutation rationale\n")
    git("update-index", "--no-assume-unchanged", "config/pitest/README.md")
    git("update-index", "--skip-worktree", "config/pitest/README.md")
    assertTrue(recordReadme.delete(), "failed to delete the skip-worktree record fixture")
    assertTrue(
      git("status", "--porcelain=v1", "--untracked-files=all").isEmpty(),
      "skip-worktree did not reproduce Git's false-clean missing-file state",
    )
    val missing = runner("clean", "hardeningCertify").buildAndFail().output
    assertTrue(
      missing.contains("missing locally but present in captured tree") &&
          missing.contains("config/pitest/README.md"),
      missing,
    )
    assertFalse(
      File(fixtureDir, ".pitest-history/pitest-certification.tsv").isFile,
      "a hidden record change left a certification receipt",
    )
  }

  @Test
  fun `zero-suite certification still binds top-level plugin and Git identity`() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-zero-suite-smoke-test"
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
    val (commit, tree) = initializeGitFixture()

    val certified = runner("clean", "hardeningCertify").build()
    val receipt = File(fixtureDir, ".pitest-history/pitest-certification.tsv").readText()

    assertTrue(receipt.contains("schema\t8\n"), receipt)
    assertTrue(receipt.contains("gitState\tclean\n"), receipt)
    assertTrue(receipt.contains("gitCommit\t$commit\n"), receipt)
    assertTrue(receipt.contains("gitTree\t$tree\n"), receipt)
    assertTrue(Regex("(?m)^pluginSha256\\t[0-9a-f]{64}$").containsMatchIn(receipt), receipt)
    assertEquals(
      listOf("suiteColumns") + certificationSuiteColumns,
      receipt.lineSequence().single { it.startsWith("suiteColumns\t") }.split('\t'),
      "zero-suite receipt did not retain its self-describing suite schema",
    )
    assertFalse(receipt.lineSequence().any { it.startsWith("suite\t") }, receipt)
    assertTrue(certified.output.contains("0 suites certified"), certified.output)
    val receiptPath = File(fixtureDir, ".pitest-history/pitest-certification.tsv")
        .canonicalPath
    assertTrue(
      certified.output.contains(
        "hardeningCertify: 1 project-scoped receipt published by this Gradle invocation; " +
          "0 suites certified total:\n  : — 0 suites — $receiptPath",
      ),
      certified.output,
    )
  }
}
