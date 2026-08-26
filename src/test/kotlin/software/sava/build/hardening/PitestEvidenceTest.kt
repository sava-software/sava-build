package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.hardening.task.HardeningCertificationTask
import java.io.File
import java.nio.file.Files

class PitestEvidenceTest {

  @TempDir
  lateinit var tempDir: File

  private fun evidence() = PitestEvidence(
      suite = "encoding",
      invocationId = "run-123",
      pitestVersion = "1.25.8",
      junitPluginVersion = "1.2.3",
      pluginSha256 = "plugin",
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = "25",
      sourceSha256 = "source",
      classesSha256 = "classes",
      classpathSha256 = "classpath",
      toolClasspathSha256 = "tool-classpath",
      mutationToolchainSha256 = "mutation-toolchain",
      configurationSha256 = "config",
      reportSha256 = "report",
      scope = PitestEvidence.FULL_SCOPE,
      historyAssisted = false,
  )

  @Test
  fun `manifest round trips deterministically`() {
    val first = evidence().render()
    val parsed = PitestEvidence.parse(first)

    assertEquals(evidence(), parsed)
    assertEquals(first, parsed.render())
  }

  @Test
  fun `manifest refuses missing duplicate unknown and future fields`() {
    val rendered = evidence().render()
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered.lineSequence().filterNot { it.startsWith("reportSha256\t") }.joinToString("\n"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered + "suite\tagain\n")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered + "surprise\tvalue\n")
    }
    assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.parse(rendered.replace("schema\t3", "schema\t4"))
    }
  }

  @Test
  fun `N-1 evidence parses as legacy toolchain identity and must be refreshed`() {
    val schema2 = evidence().render()
      .replace("schema\t3", "schema\t2")
      .lineSequence()
      .filterNot { it.startsWith("mutationToolchainSha256\t") }
      .joinToString("\n", postfix = "\n")

    val parsed = PitestEvidence.parse(schema2)

    assertEquals(PitestEvidence.LEGACY_MUTATION_TOOLCHAIN, parsed.mutationToolchainSha256)
    assertTrue(
      parsed.differences(evidence()).any { it.startsWith("mutationToolchainSha256:") },
      parsed.differences(evidence()).toString(),
    )
  }

  @Test
  fun `file fingerprint is path and content stable but change sensitive`() {
    val a = File(tempDir, "src/A.java").also { it.parentFile.mkdirs(); it.writeText("class A {}") }
    val b = File(tempDir, "src/B.java").also { it.writeText("class B {}") }
    val first = PitestEvidence.fingerprint(tempDir, listOf(a, b))

    assertEquals(first, PitestEvidence.fingerprint(tempDir, listOf(b, a)), "input order must not matter")
    b.writeText("class B { int n; }")
    assertTrue(first != PitestEvidence.fingerprint(tempDir, listOf(a, b)), "content change was invisible")
    b.writeText("class B {}")
    val moved = File(tempDir, "other/B.java").also { it.parentFile.mkdirs(); b.copyTo(it) }
    assertTrue(first != PitestEvidence.fingerprint(tempDir, listOf(a, moved)), "path change was invisible")
  }

  @Test
  fun `differences name every mismatched provenance field`() {
    val expected = evidence().copy(sourceSha256 = "new-source", scope = "com.example.Codec")
    val differences = evidence().differences(expected)

    assertTrue(differences.any { it.startsWith("sourceSha256:") }, differences.toString())
    assertTrue(differences.any { it.startsWith("scope:") }, differences.toString())
  }

  @Test
  fun `input identity ignores observation ids but binds every stable input`() {
    val identity = evidence().inputIdentitySha256()

    assertEquals(
      identity,
      evidence().copy(invocationId = "another-run", reportSha256 = "another-report")
        .inputIdentitySha256(),
    )
    assertTrue(identity != evidence().copy(pluginSha256 = "changed-plugin").inputIdentitySha256())
    assertTrue(identity != evidence().copy(sourceSha256 = "changed-source").inputIdentitySha256())
    assertTrue(identity != evidence().copy(configurationSha256 = "changed-config").inputIdentitySha256())
    assertTrue(identity != evidence().copy(mutationToolchainSha256 = "changed-toolchain").inputIdentitySha256())
    assertTrue(identity != evidence().copy(scope = "com.example.Codec").inputIdentitySha256())
  }

  @Test
  fun `timeout retirement identity ignores plugin and observation bytes but binds remaining evidence inputs`() {
    val identity = evidence().timeoutRetirementInputIdentitySha256()

    assertEquals(
      identity,
      evidence().copy(
        invocationId = "another-run",
        reportSha256 = "another-report",
        pluginSha256 = "another-plugin",
      ).timeoutRetirementInputIdentitySha256(),
    )
    listOf(
      evidence().copy(pitestVersion = "changed-pit"),
      evidence().copy(junitPluginVersion = "changed-junit"),
      evidence().copy(identitySchema = "changed-identity"),
      evidence().copy(javaVersion = "changed-java"),
      evidence().copy(sourceSha256 = "changed-source"),
      evidence().copy(classesSha256 = "changed-classes"),
      evidence().copy(classpathSha256 = "changed-classpath"),
      evidence().copy(toolClasspathSha256 = "changed-tool-classpath"),
      evidence().copy(mutationToolchainSha256 = "changed-mutation-toolchain"),
      evidence().copy(configurationSha256 = "changed-config"),
      evidence().copy(scope = "com.example.Codec"),
      evidence().copy(historyAssisted = true),
    ).forEach { changed ->
      assertTrue(identity != changed.timeoutRetirementInputIdentitySha256(), changed.toString())
    }
  }

  @Test
  fun `certification project evidence treats the Java runtime as project-wide`() {
    val java25 = HardeningCertificationTask.ProjectEvidence.from(evidence())
    val java21 = HardeningCertificationTask.ProjectEvidence.from(
      evidence().copy(javaVersion = "21"),
    )

    assertEquals(listOf("javaVersion"), java25.differences(java21))
  }

  @Test
  fun `certification projects must share one application-time plugin identity`() {
    val identities = CertificationPluginIdentities()
    identities.requireExpected(":legacy", "a".repeat(64))
    identities.register(":core", "a".repeat(64))
    identities.register(":core", "a".repeat(64))
    identities.register(":http", "a".repeat(64))

    val conflict = assertThrows(IllegalStateException::class.java) {
      identities.register(":google", "b".repeat(64))
    }

    assertTrue(conflict.message.orEmpty().contains(":google"), conflict.message)
    assertTrue(conflict.message.orEmpty().contains(":legacy"), conflict.message)
  }

  @Test
  fun `mutation record fingerprint refuses a linked config tree`() {
    val project = tempDir.resolve("project").apply { mkdirs() }
    val external = tempDir.resolve("external/pitest").apply { mkdirs() }
    external.resolve("encoding-accepted.csv").writeText("accepted outside checkout\n")
    Files.createSymbolicLink(project.resolve("config").toPath(), external.parentFile.toPath())

    val failure = assertThrows(IllegalArgumentException::class.java) {
      PitestEvidence.mutationRecordFingerprint(
          project, project.resolve("config/pitest"), "encoding")
    }

    assertTrue(failure.message.orEmpty().contains("symbolic-link component"), failure.message)
  }

  @Test
  fun `different file sets cannot share a fingerprint through NUL bytes in content`() {
    // The entries were framed with a NUL between the name, the content and the next
    // entry, which is unambiguous only while content cannot contain NUL — and this
    // hashes compiled class files, which routinely do. One file `a` holding
    // `X\0b\0Y` fed the digest exactly the bytes two files `a`=`X` and `b`=`Y` feed
    // it, so two different file sets shared a fingerprint.
    val one = File(tempDir, "one").apply { mkdirs() }
    val two = File(tempDir, "two").apply { mkdirs() }
    File(one, "a").writeBytes("X\u0000b\u0000Y".toByteArray(Charsets.ISO_8859_1))
    File(two, "a").writeBytes("X".toByteArray())
    File(two, "b").writeBytes("Y".toByteArray())

    assertNotEquals(
      PitestEvidence.fingerprint(one, listOf(File(one, "a"))),
      PitestEvidence.fingerprint(two, listOf(File(two, "a"), File(two, "b"))),
    )
  }

  @Test
  fun `a fingerprint still distinguishes name changes and content changes`() {
    // The framing change must not have flattened what the hash is actually for.
    val dir = File(tempDir, "fp").apply { mkdirs() }
    val a = File(dir, "a").apply { writeText("X") }
    val base = PitestEvidence.fingerprint(dir, listOf(a))

    a.writeText("Y")
    val contentChanged = PitestEvidence.fingerprint(dir, listOf(a))
    assertNotEquals(base, contentChanged, "a content change did not move the fingerprint")

    a.writeText("X")
    val renamed = File(dir, "b").apply { writeText("X") }
    assertNotEquals(
      base, PitestEvidence.fingerprint(dir, listOf(renamed)),
      "a rename with identical content did not move the fingerprint",
    )
    // ...and is still order-independent, which is the property the separate order
    // hashes exist to complement.
    assertEquals(
      PitestEvidence.fingerprint(dir, listOf(a, renamed)),
      PitestEvidence.fingerprint(dir, listOf(renamed, a)),
    )
  }

  @Test
  fun `hashing text refuses ill-formed UTF-16 rather than folding it onto another value`() {
    // The one place the canonical text stops being characters and becomes bytes, and
    // the one place two texts can become one input. String.toByteArray substitutes
    // '?' for an unpaired surrogate — and '?' is PIT's single-character wildcard, so
    // `Gen?` and `Gen\uD800` are different class-name patterns with the same bytes.
    // Every per-field validator runs upstream of this and inspects the String, so
    // none of them can see it.
    assertArrayEquals(
      "Gen?".toByteArray(Charsets.UTF_8),
      "Gen\uD800".toByteArray(Charsets.UTF_8),
      "the lenient encoding this guards against no longer collapses; the guard can be reconsidered",
    )
    listOf("Gen\uD800", "Gen\uDC00", "a\uDBFFb").forEach { illFormed ->
      assertThrows(IllegalArgumentException::class.java, {
        PitestEvidence.sha256(illFormed)
      }, "ill-formed '$illFormed' was hashed")
    }
    // Well-formed text — including a real surrogate pair — hashes exactly as before.
    // Compared against the ByteArray overload, so this exercises the encoder rather
    // than calling the same String overload twice.
    assertEquals(
      PitestEvidence.sha256("Gen?".toByteArray(Charsets.UTF_8)),
      PitestEvidence.sha256("Gen?"),
    )
    PitestEvidence.sha256("emoji \uD83D\uDE00 pair")
  }

  @Test
  fun `a file cannot stand in for a directory tree, which needs no preimage search`() {
    // The two branches wrote different encodings into one untagged field, and the
    // file branch hashes arbitrary bytes — so a file need only *hold* the directory
    // branch's digest input. No search is involved. An empty file and an empty
    // directory collided outright.
    val emptyDir = File(tempDir, "emptydir").apply { mkdirs() }
    val emptyFile = File(tempDir, "emptyfile").apply { writeBytes(ByteArray(0)) }
    assertNotEquals(
      PitestEvidence.fingerprintTree(emptyDir),
      PitestEvidence.fingerprintTree(emptyFile),
    )

    val dir = File(tempDir, "dir").apply { mkdirs() }
    File(dir, "a").writeText("X")
    // int32be(1) || "a" || SHA-256("X") — exactly what the directory branch digests.
    val forged = File(tempDir, "forged").apply {
      writeBytes(
        byteArrayOf(0, 0, 0, 1) + "a".toByteArray(Charsets.UTF_8) +
          java.security.MessageDigest.getInstance("SHA-256").digest("X".toByteArray(Charsets.UTF_8))
      )
    }
    assertNotEquals(
      PitestEvidence.fingerprintTree(dir),
      PitestEvidence.fingerprintTree(forged),
      "a file holding the tree encoding still impersonates the tree",
    )
    // The tag has to sit outside the hash: an in-digest prefix is copyable by the
    // same trick, since the file branch's input is whatever the file says it is.
    // Only the directory branch is tagged: the file branch is the published one —
    // consumer receipts and local-fuzz.sh's 64-hex format check read it raw.
    assertTrue(PitestEvidence.fingerprintTree(dir).startsWith("tree:"))
    assertTrue(Regex("^[0-9a-f]{64}$").matches(PitestEvidence.fingerprintTree(forged)))
  }

  @Test
  fun `symlink aliases are distinct logical entries, independent of listing order`() {
    // Two earlier shapes each got half of this wrong. Deduping on the lexically
    // normalized path dropped a genuinely different file whose `..` crossed a
    // symlink; deduping on the canonical path erased a real alias — a tree holding
    // `a` plus symlink `b -> a` exposes two classpath names and must not fingerprint
    // like a tree holding `a` alone. Entries are now the (relative name, content
    // digest) pair: exact duplicates collapse, aliases survive, and sorting by the
    // pair makes the result independent of arrival order.
    val root = File(tempDir, "root").apply { mkdirs() }
    val real = File(root, "a").apply { writeText("X") }
    val alias = File(root, "b")
    try {
      java.nio.file.Files.createSymbolicLink(alias.toPath(), real.toPath())
    } catch (e: Exception) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable: $e")
    }

    assertEquals(
      PitestEvidence.fingerprint(root, listOf(real, alias)),
      PitestEvidence.fingerprint(root, listOf(alias, real)),
      "the result depended on the order the aliases were listed in",
    )
    assertNotEquals(
      PitestEvidence.fingerprint(root, listOf(real)),
      PitestEvidence.fingerprint(root, listOf(real, alias)),
      "an alias exposes an additional classpath name and must move the fingerprint",
    )
    // An exact duplicate listing of one entry is not an alias and still collapses.
    assertEquals(
      PitestEvidence.fingerprint(root, listOf(real)),
      PitestEvidence.fingerprint(root, listOf(real, real)),
    )
  }
}
