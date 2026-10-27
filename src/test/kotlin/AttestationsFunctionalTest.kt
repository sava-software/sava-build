import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test of 'software.sava.build.check.attestations': a fixture project resolves
 * a fake software.sava artifact from a local Maven repository, the task digests it, asks a
 * mock GitHub attestations API for bundles, and verifies them via a stub cosign executable.
 */
class AttestationsFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  /** The arguments the stub cosign was called with, one per line, across every call. */
  private val cosignArgs get() = File(fixtureDir, "cosign-args.txt")

  private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--no-watch-fs", "--stacktrace")

  private fun writeFixture(apiPort: Int, extraConfig: String = "") {
    val repoDir = File(fixtureDir, "repo/software/sava/fake-lib/1.0.0")
    repoDir.mkdirs()
    File(repoDir, "fake-lib-1.0.0.jar").writeText("not a real jar, but it has a digest")
    File(repoDir, "fake-lib-1.0.0.pom").writeText(
      """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>software.sava</groupId>
          <artifactId>fake-lib</artifactId>
          <version>1.0.0</version>
        </project>
      """.trimIndent() + "\n"
    )
    // The stub records each call it gets as one line, the arguments tab-separated (a path holds
    // no tab), for the tests that read what the task tells cosign; the task keeps cosign's own
    // output to itself.
    val cosignStub = File(fixtureDir, "cosign-stub.sh")
    cosignStub.writeText("#!/bin/sh\nprintf '%s\\t' \"$@\" >> \"${cosignArgs.absolutePath}\"\nprintf '\\n' >> \"${cosignArgs.absolutePath}\"\necho \"stub-cosign: $@\"\nexit 0\n")
    cosignStub.setExecutable(true)

    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "attest-smoke"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.check.attestations")
        }

        repositories {
          maven { url = uri("${File(fixtureDir, "repo").toURI()}") }
        }

        dependencies {
          implementation("software.sava:fake-lib:1.0.0")
        }

        savaAttestations {
          cosignExecutable = "${cosignStub.absolutePath}"
          githubToken = "test-token"
          // Off by default so artifact counts stay focused on the fixture dependency;
          // the plugin-jar test overrides it.
          verifyBuildPlugin = false
          $extraConfig
        }

        tasks.named<software.sava.build.attest.VerifySavaAttestationsTask>("verifySavaAttestations") {
          apiBaseUrl = "http://127.0.0.1:$apiPort"
        }
      """.trimIndent() + "\n"
    )
  }

  @Test
  fun `verifies resolved sava artifacts against the attestation store`() {
    val attestationRequest = AtomicReference<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      attestationRequest.set(
        exchange.requestURI.path + "|" + exchange.requestHeaders.getFirst("Authorization")
      )
      val body = """{"attestations":[{"bundle":{"mock":"sigstore-bundle"}}]}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      writeFixture(server.address.port)
      val (result, _) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").build()
      }

      assertTrue(result.output.contains("fake-lib-1.0.0.jar: VERIFIED"), result.output)
      assertTrue(result.output.contains("verified=1 missing=0 failed=0"), result.output)
      val (path, authorization) = attestationRequest.get().split("|")
      assertTrue(path.startsWith("/orgs/sava-software/attestations/sha256:"), path)
      assertTrue(authorization == "Bearer test-token", authorization)
      // The identity cosign is told to require for the library, as the stub recorded that call (the
      // plugin jar, verified first when verifyBuildPlugin is on, has an identity of its own): a
      // library's attestation is signed by whichever of the two reusable publish workflows released
      // it, and by nothing else.
      val calls = (cosignArgs.takeIf { it.exists() }?.readLines() ?: emptyList()).map { it.split('\t') }
      val args = calls.firstOrNull { call -> call.any { it.endsWith("fake-lib-1.0.0.jar") } } ?: emptyList()
      val flag = args.indexOf("--certificate-identity-regexp")
      assertTrue(flag >= 0 && flag + 1 < args.size, "no cosign call over fake-lib-1.0.0.jar with a --certificate-identity-regexp value: $calls")
      val identity = args[flag + 1]
      val signer = Regex(identity)
      val workflows = "https://github.com/sava-software/sava-build/.github/workflows/"
      for (accepted in listOf("publish.yml@refs/heads/main", "publish-gh.yml@refs/heads/main", "publish.yml@refs/tags/25.4.3")) {
        assertTrue(signer.containsMatchIn(workflows + accepted), "$identity must accept $accepted")
      }
      for (refused in listOf("publish-other.yml@refs/heads/main", "build.yml@refs/heads/main", "gradle_plugin_publish.yml@refs/tags/21.7.0")) {
        assertTrue(!signer.containsMatchIn(workflows + refused), "$identity must refuse $refused")
      }
      assertTrue(!signer.containsMatchIn("https://github.com/someone-else/sava-build/.github/workflows/publish.yml@refs/heads/main"), identity)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `warns by default when a sava artifact has no attestation`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      exchange.sendResponseHeaders(404, -1)
      exchange.close()
    }
    server.start()
    try {
      writeFixture(server.address.port)
      val (result, _) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").build()
      }

      assertTrue(result.output.contains("fake-lib-1.0.0.jar: NO ATTESTATION"), result.output)
      assertTrue(result.output.contains("Tolerated because"), result.output)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `verifies the sava-build plugin jar it is running`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      val body = """{"attestations":[{"bundle":{"mock":"sigstore-bundle"}}]}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      writeFixture(server.address.port, extraConfig = "verifyBuildPlugin = true")
      val (result, _) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").build()
      }

      assertTrue(result.output.contains("sava-build-$savaBuildTestRepoVersion.jar: VERIFIED"), result.output)
      assertTrue(result.output.contains("fake-lib-1.0.0.jar: VERIFIED"), result.output)
      assertTrue(result.output.contains("verified=2 missing=0 failed=0"), result.output)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `verifies each artifact only once across projects sharing a dependency`() {
    val apiCalls = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      apiCalls.incrementAndGet()
      val body = """{"attestations":[{"bundle":{"mock":"sigstore-bundle"}}]}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      writeFixture(server.address.port)
      // Turn the single-project fixture into two subprojects with the same dependency.
      File(fixtureDir, "settings.gradle.kts").appendText("include(\":a\")\ninclude(\":b\")\n")
      val rootBuild = File(fixtureDir, "build.gradle.kts").readText()
      File(fixtureDir, "build.gradle.kts").writeText("")
      for (module in listOf("a", "b")) {
        File(fixtureDir, module).mkdirs()
        File(fixtureDir, "$module/build.gradle.kts").writeText(rootBuild)
      }
      val (result, reused) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").build()
      }

      assertEquals(2, apiCalls.get(), "expected one shared-digest lookup in each build")
      assertTrue(result.output.contains("fake-lib-1.0.0.jar: VERIFIED (already verified in this build)"), result.output)
      assertTrue(reused.output.contains("fake-lib-1.0.0.jar: VERIFIED (already verified in this build)"), reused.output)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `explains how to provide cosign when it cannot be started`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      val body = """{"attestations":[{"bundle":{"mock":"sigstore-bundle"}}]}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
      writeFixture(server.address.port, extraConfig = "cosignExecutable = \"/no/such/cosign\"")
      val (result, reused) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").buildAndFail()
      }

      assertTrue(result.output.contains("Could not run '/no/such/cosign'"), result.output)
      assertTrue(result.output.contains("-PsavaCosignImage="), result.output)
      assertTrue(reused.output.contains("Could not run '/no/such/cosign'"), reused.output)
      assertTrue(reused.output.contains("-PsavaCosignImage="), reused.output)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `fails on a missing attestation when attestations are required`() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/orgs/sava-software/attestations/") { exchange ->
      exchange.requestBody.readAllBytes()
      exchange.sendResponseHeaders(404, -1)
      exchange.close()
    }
    server.start()
    try {
      writeFixture(server.address.port, extraConfig = "requireAttestations = true")
      val (result, reused) = assertConfigurationCacheRoundTrip {
        runner("verifySavaAttestations").buildAndFail()
      }

      assertTrue(result.output.contains("No attestation found for: fake-lib-1.0.0.jar"), result.output)
      assertTrue(reused.output.contains("No attestation found for: fake-lib-1.0.0.jar"), reused.output)
    } finally {
      server.stop(0)
    }
  }
}
