import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Exercise the installed settings plugins against HTTP repositories, including skipped lookups. */
class RepositoryRoutingFunctionalTest {
  @TempDir
  lateinit var fixtureDir: File

  @Test
  fun `built in repositories skip foreign groups while Central and extra repositories remain available`() {
    RepositoryServer().use { server ->
      server.module("/central", "software.sava", "public-lib")
      server.module("/gpr/sava-software/sava", "software.sava", "private-lib")
      server.module("/gpr/glamsystems/private", "systems.glam", "private-lib")
      server.module("/gpr/glamsystems/private", "org.example", "third-party")
      writeFixture(server, "software.sava.build.base.repositories", listOf(
        "software.sava:public-lib:1.0",
        "software.sava:private-lib:1.0",
        "systems.glam:private-lib:1.0",
        "org.example:third-party:1.0",
      ))

      assertConfigurationCacheRoundTrip { runBuild() }

      val requests = server.requests.toList()
      assertTrue(requests.any { it == "/central/software/sava/public-lib/1.0/public-lib-1.0.jar" }, requests.toString())
      assertTrue(requests.any { it.startsWith("/gpr/sava-software/sava/software/sava/private-lib/") }, requests.toString())
      assertTrue(requests.any { it.startsWith("/gpr/glamsystems/private/systems/glam/") }, requests.toString())
      assertTrue(requests.any { it.startsWith("/gpr/glamsystems/private/org/example/") }, requests.toString())
      assertFalse(requests.any {
        it.startsWith("/gpr/sava-software/") &&
            (it.contains("/systems/glam/") || it.contains("/org/example/"))
      }, requests.toString())
      assertFalse(requests.any { it.startsWith("/gpr/") && it.contains("/public-lib/") }, requests.toString())
    }
  }

  @Test
  fun `standalone catalog repository serves only its catalog module`() {
    RepositoryServer().use { server ->
      server.catalog("/gpr/sava-software/solana-version-catalog")
      server.module("/fallback", "software.sava", "other-lib")
      writeFixture(server, "software.sava.build.version-catalog",
        listOf("software.sava:other-lib:1.0"), fallbackRepository = true)

      assertConfigurationCacheRoundTrip { runBuild() }

      val requests = server.requests.toList()
      assertTrue(requests.any { it.endsWith("/solana-version-catalog-1.0.toml") }, requests.toString())
      assertTrue(requests.any { it == "/fallback/software/sava/other-lib/1.0/other-lib-1.0.jar" }, requests.toString())
      assertFalse(requests.any { it.startsWith("/gpr/") && it.contains("/other-lib/") }, requests.toString())
    }
  }

  private fun writeFixture(
    server: RepositoryServer,
    plugin: String,
    modules: List<String>,
    fallbackRepository: Boolean = false,
  ) {
    enableTestKitConfigurationCache(fixtureDir)
    fixtureDir.resolve("gradle.properties").appendText(
      "\nsavaGithubPackagesUsername=fixture\nsavaGithubPackagesPassword=fixture\n" +
          "solanaBOMVersion=1.0\nextraGithubPackageRepos=glamsystems/private\n")
    fixtureDir.resolve("settings.gradle.kts").writeText("""
      $savaBuildPluginManagement
      plugins { id("$plugin") }
      rootProject.name = "repository-routing"
      dependencyResolutionManagement.repositories.withType<org.gradle.api.artifacts.repositories.MavenArtifactRepository>().forEach { repository ->
        val route = when (repository.url.host) {
          "repo.maven.apache.org" -> "/central"
          "plugins.gradle.org" -> "/portal"
          "maven.pkg.github.com" -> "/gpr" + repository.url.path
          else -> error("Unexpected repository: " + repository.url)
        }
        repository.url = uri("${server.url}" + route)
        repository.isAllowInsecureProtocol = true
      }
      ${if (fallbackRepository) """
        dependencyResolutionManagement.repositories.maven {
          url = uri("${server.url}/fallback")
          isAllowInsecureProtocol = true
        }
      """ else ""}
    """.trimIndent() + "\n")
    fixtureDir.resolve("build.gradle.kts").writeText("""
      val fixtureDependencies = configurations.dependencyScope("fixtureDependencies")
      val fixtureClasspath = configurations.resolvable("fixtureClasspath") {
        extendsFrom(fixtureDependencies.get())
        attributes {
          attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
          attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
          attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
      }
      dependencies {
        ${modules.joinToString("\n") { "fixtureDependencies(\"$it\")" }}
      }
      tasks.register("resolveFixture") {
        inputs.files(fixtureClasspath)
        doLast {
          check(inputs.files.files.size == ${modules.size})
        }
      }
    """.trimIndent() + "\n")
  }

  private fun runBuild() = savaBuildRunner(fixtureDir, "resolveFixture", "--stacktrace").build()

  private class RepositoryServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val contents = ConcurrentHashMap<String, ByteArray>()
    val requests = ConcurrentLinkedQueue<String>()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
      server.createContext("/") { exchange ->
        val request = exchange.requestURI.path
        requests.add(request)
        val content = contents[request]
        if (content == null) {
          exchange.sendResponseHeaders(404, -1)
        } else if (exchange.requestMethod == "HEAD") {
          exchange.responseHeaders.add("Content-Length", content.size.toString())
          exchange.sendResponseHeaders(200, -1)
        } else {
          exchange.sendResponseHeaders(200, content.size.toLong())
          exchange.responseBody.use { it.write(content) }
        }
        exchange.close()
      }
      server.start()
    }

    fun module(repository: String, group: String, module: String) {
      val prefix = "$repository/${group.replace('.', '/')}/$module/1.0/$module-1.0"
      contents["$prefix.pom"] = """
        <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>
        <artifactId>$module</artifactId><version>1.0</version></project>
      """.trimIndent().toByteArray()
      contents["$prefix.jar"] = "$group:$module:1.0".toByteArray()
    }

    fun catalog(repository: String) {
      val prefix = "$repository/software/sava/solana-version-catalog/1.0/solana-version-catalog-1.0"
      contents["$prefix.pom"] = """
        <project><modelVersion>4.0.0</modelVersion><groupId>software.sava</groupId>
        <artifactId>solana-version-catalog</artifactId><version>1.0</version><packaging>pom</packaging>
        <!-- do_not_remove: published-with-gradle-metadata --></project>
      """.trimIndent().toByteArray()
      contents["$prefix.module"] = """
        {"formatVersion":"1.1", "component":{"group":"software.sava","module":"solana-version-catalog","version":"1.0"},
         "variants":[{"name":"versionCatalogElements","attributes":{"org.gradle.category":"platform","org.gradle.usage":"version-catalog"},
          "files":[{"name":"solana-version-catalog-1.0.toml","url":"solana-version-catalog-1.0.toml"}]}]}
      """.trimIndent().toByteArray()
      contents["$prefix.toml"] = "[versions]\nfixture = \"1.0\"\n".toByteArray()
    }

    override fun close() = server.stop(0)
  }
}
