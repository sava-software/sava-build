import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * End-to-end test of the in-house Central Portal deployment pipeline: a library project
 * stages its publications through 'software.sava.build.feature.publish' and an aggregation
 * project bundles them via 'software.sava.build.feature.publish-maven-central'. Only the
 * upload itself is not exercised (it needs real credentials and an external service).
 */
class CentralPortalBundleSmokeTest {

  @TempDir
  lateinit var fixtureDir: File

  private val savaBuildRoot = File(System.getProperty("savaBuild.root"))
    .absolutePath.replace("\\", "\\\\")

  private fun writeFixture() {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        pluginManagement { includeBuild("$savaBuildRoot") }

        rootProject.name = "central-smoke"

        include(":lib")
        include(":agg")
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "lib").mkdirs()
    File(fixtureDir, "lib/build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.publish")
        }

        group = "software.sava.test"
        version = "1.2.3"
      """.trimIndent() + "\n"
    )
    val sourceDir = File(fixtureDir, "lib/src/main/java/software/sava/test/lib")
    sourceDir.mkdirs()
    File(sourceDir, "Lib.java").writeText(
      """
        package software.sava.test.lib;

        /** Placeholder library class. */
        public final class Lib {
          private Lib() {
          }
        }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "agg").mkdirs()
    // 'nmcpAggregation' is the deprecated alias of 'centralPortalAggregation': existing
    // consumer aggregation projects declare their modules through it and must keep working.
    File(fixtureDir, "agg/build.gradle.kts").writeText(
      """
        plugins {
          id("software.sava.build.feature.publish-maven-central")
        }

        dependencies {
          nmcpAggregation(project(":lib"))
        }
      """.trimIndent() + "\n"
    )
  }

  @Test
  fun `aggregation zips staged publications into a deployment bundle`() {
    writeFixture()
    GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withArguments("--configuration-cache", ":agg:zipCentralPortalDeployment")
      .build()

    val bundle = File(fixtureDir, "agg/build/central-portal/deployment.zip")
    assertTrue(bundle.isFile, "missing deployment bundle at $bundle")

    val entries = ZipFile(bundle).use { zip ->
      zip.entries().asSequence().map { it.name }.toSet()
    }
    val artifactDir = "software/sava/test/lib/1.2.3"
    for (artifact in listOf("lib-1.2.3.jar", "lib-1.2.3.pom", "lib-1.2.3-sources.jar", "lib-1.2.3-javadoc.jar")) {
      assertTrue("$artifactDir/$artifact" in entries, "missing $artifact in $entries")
      // md5/sha1 satisfy Central validation, sha512 is Gradle's preferred checksum;
      // sha256 is trimmed by default to respect Central's publishing file limits.
      for (checksum in listOf("md5", "sha1", "sha512")) {
        assertTrue("$artifactDir/$artifact.$checksum" in entries, "missing $artifact.$checksum in $entries")
      }
      assertFalse("$artifactDir/$artifact.sha256" in entries, "$artifact.sha256 should be trimmed: $entries")
    }
    assertFalse(entries.any { "maven-metadata" in it }, "bundle must not contain maven-metadata files: $entries")
  }

  @Test
  fun `upload task posts the bundle and polls until validation passes`() {
    writeFixture()
    val uploadCalls = AtomicInteger()
    val statusCalls = AtomicInteger()
    val uploadRequest = AtomicReference<Pair<String?, String?>>() // authorization, query
    val releasePath = AtomicReference<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/upload") { exchange ->
      exchange.requestBody.readAllBytes()
      uploadRequest.set(exchange.requestHeaders.getFirst("Authorization") to exchange.requestURI.query)
      if (uploadCalls.incrementAndGet() == 1) {
        // Transient server failure: the upload must be retried.
        exchange.sendResponseHeaders(500, -1)
        exchange.close()
      } else {
        val body = "test-deployment-id".toByteArray()
        exchange.sendResponseHeaders(201, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
    }
    server.createContext("/status") { exchange ->
      exchange.requestBody.readAllBytes()
      val state = if (statusCalls.incrementAndGet() == 1) "VALIDATING" else "VALIDATED"
      val body = """{"deploymentState":"$state"}""".toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.createContext("/deployment") { exchange ->
      exchange.requestBody.readAllBytes()
      releasePath.set(exchange.requestURI.path)
      exchange.sendResponseHeaders(204, -1)
      exchange.close()
    }
    server.start()
    try {
      File(fixtureDir, "agg/build.gradle.kts").appendText(
        """

        tasks.named<software.sava.build.publish.CentralPortalUploadTask>("publishCentralPortalDeployment") {
          baseUrl = "http://127.0.0.1:${server.address.port}"
          username = "portal-user"
          password = "portal-pass"
        }
        tasks.named<software.sava.build.publish.CentralPortalReleaseTask>("releaseCentralPortalDeployment") {
          baseUrl = "http://127.0.0.1:${server.address.port}"
          username = "portal-user"
          password = "portal-pass"
        }
        """.trimIndent() + "\n"
      )
      val result = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments("--configuration-cache", ":agg:publishAggregationToCentralPortal")
        .build()

      val (authorization, query) = uploadRequest.get()
      val expectedToken = Base64.getEncoder().encodeToString("portal-user:portal-pass".toByteArray())
      assertEquals("Bearer $expectedToken", authorization)
      assertEquals("publishingType=USER_MANAGED&name=central-smoke", query)
      assertEquals(2, uploadCalls.get(), "expected the failed upload to be retried once")
      assertTrue(statusCalls.get() >= 2, "expected at least two status polls, got ${statusCalls.get()}")
      assertTrue(result.output.contains("'VALIDATING'"), result.output)
      assertTrue(result.output.contains("passed validation"), result.output)

      val releaseResult = GradleRunner.create()
        .withProjectDir(fixtureDir)
        .withArguments(
          "--configuration-cache",
          ":agg:releaseCentralPortalDeployment",
          "-PcentralPortalDeploymentId=test-deployment-id"
        )
        .build()
      assertEquals("/deployment/test-deployment-id", releasePath.get())
      assertTrue(releaseResult.output.contains("is publishing"), releaseResult.output)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `deprecated nmcp task name aliases the upload task`() {
    writeFixture()
    // Without credentials the upload fails after bundling, proving the alias reaches the
    // upload task; existing workflows invoke this task name. Strip any real credentials
    // from the environment so the task can never attempt an actual upload.
    val environment = System.getenv()
      .filterKeys { it != "MAVEN_CENTRAL_TOKEN" && it != "MAVEN_CENTRAL_SECRET" }
    val result = GradleRunner.create()
      .withProjectDir(fixtureDir)
      .withEnvironment(environment)
      .withArguments("--configuration-cache", ":agg:publishAggregationToCentralPortal")
      .buildAndFail()
    assertTrue(result.output.contains(":agg:publishCentralPortalDeployment"), result.output)
    assertTrue(result.output.contains("MAVEN_CENTRAL_TOKEN"), result.output)
  }
}
