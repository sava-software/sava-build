import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
class AttestationsSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

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
    val cosignStub = File(fixtureDir, "cosign-stub.sh")
    cosignStub.writeText("#!/bin/sh\necho \"stub-cosign: $@\"\nexit 0\n")
    cosignStub.setExecutable(true)

    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .build()

      assertTrue(result.output.contains("fake-lib-1.0.0.jar: VERIFIED"), result.output)
      assertTrue(result.output.contains("verified=1 missing=0 failed=0"), result.output)
      val (path, authorization) = attestationRequest.get().split("|")
      assertTrue(path.startsWith("/orgs/sava-software/attestations/sha256:"), path)
      assertTrue(authorization == "Bearer test-token", authorization)
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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .build()

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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .build()

      assertTrue(result.output.contains("sava-build.jar: VERIFIED"), result.output)
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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .build()

      assertEquals(1, apiCalls.get(), "expected one attestation lookup for the shared digest")
      assertTrue(result.output.contains("fake-lib-1.0.0.jar: VERIFIED (already verified in this build)"), result.output)
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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .buildAndFail()

      assertTrue(result.output.contains("Could not run '/no/such/cosign'"), result.output)
      assertTrue(result.output.contains("-PsavaCosignImage="), result.output)
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
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", "verifySavaAttestations")
        .buildAndFail()

      assertTrue(result.output.contains("No attestation found for: fake-lib-1.0.0.jar"), result.output)
    } finally {
      server.stop(0)
    }
  }
}
