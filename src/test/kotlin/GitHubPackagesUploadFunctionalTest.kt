import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end test of the GitHub Packages upload: a library project stages its publications
 * through 'software.sava.build.feature.publish' and a mock registry records what the
 * aggregation project's task, the one the release workflows invoke, uploads for it.
 */
class GitHubPackagesUploadFunctionalTest {

  private companion object {
    const val CURRENT_AGGREGATION = """
      plugins {
        id("software.sava.build.feature.publish-maven-central")
      }

      dependencies {
        centralPortalAggregation(project(":lib"))
      }
    """

    /** Every file of the library's 1.2.3 publication with a SHA-1 and a SHA-256 checksum beside it. */
    fun expectedUploads(library: String): Set<String> {
      val dir = "/sava-software/packages-smoke/software/sava/test/$library/1.2.3/"
      return listOf("pom", "jar", "module").map { "$library-1.2.3.$it" }
        .plus(listOf("sources", "javadoc").map { "$library-1.2.3-$it.jar" })
        .flatMap { listOf(it, "$it.sha1", "$it.sha256") }
        .map { dir + it }
        .toSet()
    }
  }

  @TempDir
  lateinit var fixtureDir: File

  @BeforeEach
  fun enableConfigurationCacheForFixture() {
    enableTestKitConfigurationCache(fixtureDir)
  }

  private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--stacktrace")

  private fun writeFixture(aggregation: String = CURRENT_AGGREGATION, libraries: List<String> = listOf("lib")) {
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "packages-smoke"

        ${libraries.joinToString("\n        ") { "include(\":$it\")" }}
        include(":agg")
      """.trimIndent() + "\n"
    )
    for (library in libraries) {
      File(fixtureDir, library).mkdirs()
      File(fixtureDir, "$library/build.gradle.kts").writeText(
        """
          plugins {
            java
            id("software.sava.build.feature.publish")
          }

          group = "software.sava.test"
          version = "1.2.3"

          tasks.named<software.sava.build.publish.GitHubPackagesUploadTask>("uploadToGitHubPackages") {
            retryDelayMillis = 5L
          }
        """.trimIndent() + "\n"
      )
      val sourceDir = File(fixtureDir, "$library/src/main/java/software/sava/test/$library")
      sourceDir.mkdirs()
      File(sourceDir, "Placeholder.java").writeText(
        """
          package software.sava.test.$library;

          /** Placeholder library class. */
          public final class Placeholder {
            private Placeholder() {
            }
          }
        """.trimIndent() + "\n"
      )
    }
    File(fixtureDir, "agg").mkdirs()
    File(fixtureDir, "agg/build.gradle.kts").writeText(aggregation.trimIndent() + "\n")
  }

  /**
   * Records every request; the first one fails so the retry is exercised. With [lostResponse]
   * the first file is stored before its response fails, so its retry meets a conflict.
   */
  private class Registry(private val conflict: Boolean, private val lostResponse: Boolean = false) : AutoCloseable {
    val puts = CopyOnWriteArrayList<String>()
    val bodies = ConcurrentHashMap<String, ByteArray>()
    val methods: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val authorizations: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val calls = AtomicInteger()
    @Volatile private var lostPath: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
      server.createContext("/") { exchange ->
        val body = exchange.requestBody.readAllBytes()
        methods.add(exchange.requestMethod)
        authorizations.add(exchange.requestHeaders.getFirst("Authorization") ?: "")
        val path = exchange.requestURI.path
        val status = when {
          calls.incrementAndGet() == 1 -> {
            if (lostResponse) {
              lostPath = path
              puts.add(path)
              bodies[path] = body
            }
            503
          }
          conflict || path == lostPath -> 409
          else -> {
            puts.add(path)
            bodies[path] = body
            201
          }
        }
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
      server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}/sava-software/packages-smoke"

    override fun close() = server.stop(0)
  }

  private fun upload(registry: Registry, task: String = ":agg:publishToGitHubPackages"): GradleRunner = runner(
    task,
    "-PsavaGithubPackagesPublishUrl=${registry.url}",
    "-PsavaGithubPackagesPublishUsername=gh-user",
    "-PsavaGithubPackagesPublishPassword=gh-token",
  )

  @Test
  fun `uploads the artifacts with a SHA-1 and a SHA-256 checksum each and no MD5`() {
    writeFixture()
    Registry(conflict = false).use { registry ->
      val (cold, reused) = assertConfigurationCacheRoundTrip { upload(registry).build() }

      assertEquals(TaskOutcome.SUCCESS, cold.task(":lib:uploadToGitHubPackages")?.outcome, cold.output)
      val dir = "/sava-software/packages-smoke/software/sava/test/lib/1.2.3/"
      val expected = expectedUploads("lib")
      assertEquals(expected, registry.puts.toSet())
      assertEquals(expected.size * 2, registry.puts.size, "each build uploads every file exactly once: ${registry.puts}")
      assertTrue(registry.puts.first().endsWith(".pom"), "the POM goes first: ${registry.puts.first()}")
      assertEquals(setOf("PUT"), registry.methods)
      val token = Base64.getEncoder().encodeToString("gh-user:gh-token".toByteArray())
      assertEquals(setOf("Basic $token"), registry.authorizations)

      // The checksums Gradle staged describe the bytes that were uploaded.
      val jar = registry.bodies.getValue(dir + "lib-1.2.3.jar")
      for ((extension, algorithm) in listOf("sha1" to "SHA-1", "sha256" to "SHA-256")) {
        val digest = MessageDigest.getInstance(algorithm).digest(jar).joinToString("") { "%02x".format(it) }
        assertEquals(digest, String(registry.bodies.getValue(dir + "lib-1.2.3.jar.$extension")).trim(), algorithm)
      }

      assertTrue(cold.output.contains("Uploaded ${expected.size} files"), cold.output)
      assertTrue(reused.output.contains("Uploaded ${expected.size} files"), reused.output)
    }
  }

  @Test
  fun `a retry that finds its file stored by the failed attempt carries on`() {
    writeFixture()
    Registry(conflict = false, lostResponse = true).use { registry ->
      val result = upload(registry).build()
      assertEquals(expectedUploads("lib"), registry.puts.toSet())
      assertEquals(registry.puts.size, registry.puts.toSet().size, "nothing stored twice: ${registry.puts}")
      assertTrue(result.output.contains("stored by the attempt that failed in transit"), result.output)
    }
  }

  @Test
  fun `an aggregation script written before 21_6_0 keeps working through the deprecated task names`() {
    writeFixture(
      """
        plugins {
          id("software.sava.build.feature.publish-maven-central")
        }

        dependencies {
          nmcpAggregation(project(":lib"))
        }

        tasks.register("publishToGitHubPackages") {
          group = "publishing"
          dependsOn(":lib:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository")
        }
      """
    )
    Registry(conflict = false).use { registry ->
      val result = upload(registry).build()
      assertEquals(TaskOutcome.SUCCESS, result.task(":lib:uploadToGitHubPackages")?.outcome, result.output)
      assertEquals(expectedUploads("lib"), registry.puts.toSet())
      assertTrue(result.output.contains("Deprecated: ") && result.output.contains("registers its own"), result.output)
    }
  }

  @Test
  fun `a module's publish still uploads to GitHub Packages`() {
    writeFixture()
    Registry(conflict = false).use { registry ->
      val result = upload(registry, ":lib:publish").build()
      assertEquals(TaskOutcome.SUCCESS, result.task(":lib:uploadToGitHubPackages")?.outcome, result.output)
      assertEquals(expectedUploads("lib"), registry.puts.toSet())
    }
  }

  @Test
  fun `running the task unqualified never uploads a module the aggregation leaves out`() {
    writeFixture(
      """
        plugins {
          id("software.sava.build.feature.publish-maven-central")
        }

        dependencies {
          nmcpAggregation(project(":lib"))
        }
      """,
      listOf("lib", "tool"),
    )
    Registry(conflict = false).use { registry ->
      val result = upload(registry, "publishToGitHubPackages").build()
      assertEquals(expectedUploads("lib"), registry.puts.toSet())
      assertEquals(null, result.task(":tool:uploadToGitHubPackages"), result.output)
    }
  }

  @Test
  fun `an aggregation that aggregates nothing fails instead of uploading nothing`() {
    writeFixture(
      """
        plugins {
          id("software.sava.build.feature.publish-maven-central")
        }
      """
    )
    Registry(conflict = false).use { registry ->
      val result = upload(registry).buildAndFail()
      assertTrue(result.output.contains("No project to publish"), result.output)
      assertTrue(registry.puts.isEmpty(), "${registry.puts}")
    }
  }

  @Test
  fun `a version the registry already holds fails the upload`() {
    writeFixture()
    Registry(conflict = true).use { registry ->
      val result = upload(registry).buildAndFail()
      assertTrue(result.output.contains("HTTP 409"), result.output)
      assertTrue(result.output.contains("immutable"), result.output)
    }
  }
}
