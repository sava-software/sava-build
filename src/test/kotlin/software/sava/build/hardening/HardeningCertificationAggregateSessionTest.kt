package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HardeningCertificationAggregateSessionTest {

  @TempDir
  lateinit var temporaryDirectory: File

  @Test
  fun `direct child publication outside an aggregate is ignored`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("server"))

    assertFalse(
      CertificationAggregateRegistry().recordPublished(
        root,
        child.projectPath,
        child.projectDirectory,
        child.receiptFile,
        child.suites,
        PLUGIN_SHA,
        CHILD_SESSION,
      )
    )
  }

  @Test
  fun `aggregate requires every callback and renders its exact inventory`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val alpha = child(root.resolve("alpha"), ":alpha", listOf("accounts", "rpc"))
    val beta = child(root.resolve("beta"), ":beta", listOf("server"))
    val registry = activated(root, listOf(beta.registration, alpha.registration))

    publish(registry, root, alpha)
    val missing = assertThrows(IllegalStateException::class.java) {
      registry.prepareManifest(root)
    }
    assertTrue(missing.message.orEmpty().contains("missing: :beta"), missing.message)

    publish(registry, root, beta)
    val manifest = registry.prepareManifest(root).manifest
    assertEquals(listOf(":alpha", ":beta"), manifest.projects.map { it.projectPath })
    assertEquals(3, manifest.suiteCount)
    assertEquals(
      listOf("accounts", "rpc", "server"),
      manifest.projects.flatMap { it.suites },
    )
    manifest.projects.forEach { project ->
      assertTrue(project.receiptSha256.matches(Regex("[0-9a-f]{64}")))
    }
  }

  @Test
  fun `callback is bound to configured project plugin suites path and child session`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("accounts", "server"))

    fun failure(block: (CertificationAggregateRegistry) -> Unit): IllegalStateException =
      assertThrows(IllegalStateException::class.java) {
        block(activated(root, listOf(child.registration)))
      }

    assertTrue(failure { registry ->
      registry.recordPublished(
        root, child.projectPath, child.projectDirectory, child.receiptFile,
        listOf("accounts"), PLUGIN_SHA, CHILD_SESSION)
    }.message.orEmpty().contains("different suite inventory"))
    assertTrue(failure { registry ->
      registry.recordPublished(
        root, child.projectPath, child.projectDirectory, child.receiptFile,
        child.suites, "d".repeat(64), CHILD_SESSION)
    }.message.orEmpty().contains("different plugin identity"))
    assertTrue(failure { registry ->
      registry.recordPublished(
        root, child.projectPath, root.resolve("elsewhere"), child.receiptFile,
        child.suites, PLUGIN_SHA, CHILD_SESSION)
    }.message.orEmpty().contains("configured project directory"))
    assertTrue(failure { registry ->
      registry.recordPublished(
        root, child.projectPath, child.projectDirectory,
        child.projectDirectory.resolve("different.tsv"), child.suites,
        PLUGIN_SHA, CHILD_SESSION)
    }.message.orEmpty().contains("unexpected receipt path"))
    assertTrue(failure { registry ->
      registry.recordPublished(
        root, child.projectPath, child.projectDirectory, child.receiptFile,
        child.suites, PLUGIN_SHA, OTHER_CHILD_SESSION)
    }.message.orEmpty().contains("instead of the current invocation"))
  }

  @Test
  fun `strict child receipt structure is checked before hashing`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("server"))
    val unsupported = child.receiptFile.readText().replace("schema\t7", "schema\t6")
    child.receiptFile.writeText(unsupported)

    val failure = assertThrows(IllegalStateException::class.java) {
      publish(activated(root, listOf(child.registration)), root, child)
    }
    assertTrue(failure.message.orEmpty().contains("unsupported schema"), failure.message)
  }

  @Test
  fun `child receipt identity and inventory are bound before hashing`() {
    val mutations = listOf<(String) -> String>(
      { it.replace("project\t:child", "project\t:other") },
      { it.replace("session\t$CHILD_SESSION", "session\t$OTHER_CHILD_SESSION") },
      { it.replace("mode\tfresh-full-strict", "mode\tpartial") },
      { it.replaceFirst("pluginSha256\t$PLUGIN_SHA", "pluginSha256\t${"d".repeat(64)}") },
      { it.replace("suite\tserver\t", "suite\tother\t") },
    )

    mutations.forEachIndexed { index, mutate ->
      val root = temporaryDirectory.resolve("bound-root-$index").apply { mkdirs() }
      val child = child(root.resolve("child"), ":child", listOf("server"))
      child.receiptFile.writeText(mutate(child.receiptFile.readText()))

      assertThrows(IllegalStateException::class.java) {
        publish(activated(root, listOf(child.registration)), root, child)
      }
    }
  }

  @Test
  fun `aggregate trusts the child writer for per-suite evidence field validation`() {
    val root = temporaryDirectory.resolve("writer-validation-root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("server"))
    child.receiptFile.writeText(
      child.receiptFile.readText().replace("4".repeat(64), "writer-owned-report-field")
    )
    val registry = activated(root, listOf(child.registration))

    publish(registry, root, child)

    assertEquals(PitestEvidence.sha256(child.receiptFile),
      registry.prepareManifest(root).manifest.projects.single().receiptSha256)
  }

  @Test
  fun `aggregate rejects project state outside its Gradle root during activation`() {
    val root = temporaryDirectory.resolve("contained-root").apply { mkdirs() }
    val external = child(
      temporaryDirectory.resolve("external-child"),
      ":external",
      listOf("server"),
    )

    val failure = assertThrows(IllegalArgumentException::class.java) {
      activated(root, listOf(external.registration))
    }

    assertTrue(failure.message.orEmpty().contains("project directory outside the Gradle root"),
      failure.message)
  }

  @Test
  fun `receipt changes before and after preparation are refused`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("server"))
    val registry = activated(root, listOf(child.registration))
    publish(registry, root, child)

    mutateReceipt(child.receiptFile)
    val before = assertThrows(IllegalStateException::class.java) {
      registry.prepareManifest(root)
    }
    assertTrue(before.message.orEmpty().contains("changed after child publication"), before.message)

    val freshRoot = temporaryDirectory.resolve("fresh-root").apply { mkdirs() }
    val fresh = child(freshRoot.resolve("child"), ":child", listOf("server"))
    val freshRegistry = activated(freshRoot, listOf(fresh.registration))
    publish(freshRegistry, freshRoot, fresh)
    val prepared = freshRegistry.prepareManifest(freshRoot)
    mutateReceipt(fresh.receiptFile)
    val during = assertThrows(IllegalStateException::class.java) {
      freshRegistry.requireReceiptsUnchanged(prepared)
    }
    assertTrue(during.message.orEmpty().contains("changed during aggregate publication"), during.message)
  }

  @Test
  fun `incomplete child sentinel prevents aggregate authority`() {
    val root = temporaryDirectory.resolve("root").apply { mkdirs() }
    val child = child(root.resolve("child"), ":child", listOf("server"))
    child.runningFile.writeText("session\t$CHILD_SESSION\n")

    val failure = assertThrows(IllegalStateException::class.java) {
      publish(activated(root, listOf(child.registration)), root, child)
    }
    assertTrue(failure.message.orEmpty().contains("incomplete-attempt sentinel"), failure.message)
  }

  private fun activated(
    root: File,
    registrations: List<CertificationAggregateProjectRegistration>,
  ): CertificationAggregateRegistry = CertificationAggregateRegistry().also { registry ->
    if (registrations.isNotEmpty()) {
      registry.activate(root, ROOT_SESSION, PLUGIN_SHA, registrations)
    }
  }

  private fun publish(
    registry: CertificationAggregateRegistry,
    root: File,
    child: Child,
  ) {
    assertTrue(
      registry.recordPublished(
        root,
        child.projectPath,
        child.projectDirectory,
        child.receiptFile,
        child.suites,
        PLUGIN_SHA,
        CHILD_SESSION,
      )
    )
  }

  private fun child(directory: File, path: String, suites: List<String>): Child {
    directory.mkdirs()
    val receipt = directory.resolve(".pitest-history/pitest-certification.tsv")
    val running = directory.resolve(".pitest-history/pitest-certification.running")
    receipt.parentFile.mkdirs()
    receipt.writeText(receipt(path, suites))
    return Child(path, directory, receipt, running, suites)
  }

  private fun receipt(projectPath: String, suites: List<String>): String = buildString {
    appendLine("schema\t7")
    appendLine("project\t$projectPath")
    appendLine("session\t$CHILD_SESSION")
    appendLine("mode\tfresh-full-strict")
    appendLine("gitState\tdirty")
    appendLine("gitCommit\t${"1".repeat(40)}")
    appendLine("gitTree\t${"2".repeat(40)}")
    appendLine("gitStatusSha256\t${"3".repeat(64)}")
    appendLine("gitProjectDirectory\t.")
    appendLine("pluginSha256\t$PLUGIN_SHA")
    appendLine(
      "suiteColumns\tname\tinvocation\treportSha256\tsourceSha256\tclassesSha256\t" +
        "configurationSha256\tpitestVersion\tpluginSha256\ttoolClasspathSha256\t" +
        "mutationToolchainSha256\trecordInputsSha256\trecordPitestVersion\t" +
        "recordMutationToolchainSha256"
    )
    suites.forEach { suite ->
      appendLine(
        listOf(
          "suite", suite, SUITE_INVOCATION, "4".repeat(64), "5".repeat(64),
          "6".repeat(64), "7".repeat(64), "1.30.0", PLUGIN_SHA,
          "8".repeat(64), "9".repeat(64), "a".repeat(64), "1.30.0",
          "9".repeat(64),
        ).joinToString("\t")
      )
    }
  }

  private fun mutateReceipt(receipt: File) {
    receipt.writeText(
      receipt.readText().replace("gitCommit\t${"1".repeat(40)}", "gitCommit\t${"f".repeat(40)}")
    )
  }

  private data class Child(
    val projectPath: String,
    val projectDirectory: File,
    val receiptFile: File,
    val runningFile: File,
    val suites: List<String>,
  ) {
    val registration = CertificationAggregateProjectRegistration(
      projectPath,
      projectDirectory,
      receiptFile,
      runningFile,
      suites,
    )
  }

  private companion object {
    const val ROOT_SESSION = "123e4567-e89b-12d3-a456-426614174000"
    const val CHILD_SESSION = "123e4567-e89b-12d3-a456-426614174001"
    const val OTHER_CHILD_SESSION = "123e4567-e89b-12d3-a456-426614174002"
    const val SUITE_INVOCATION = "123e4567-e89b-12d3-a456-426614174003"
    val PLUGIN_SHA = "f".repeat(64)
  }
}
