package software.sava.build.publish

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/**
 * Uploads a staged Maven-layout repository to GitHub Packages, one file at a time.
 *
 * Gradle's own Maven publisher uploads an MD5 and a SHA-1 checksum beside every file and
 * offers no way to switch MD5 off (only SHA-256 and SHA-512 are optional), so a chosen set
 * has to be fed by hand. Only the checksum extensions in [checksums] are stored beside each
 * file, SHA-1 and SHA-256 by default, never MD5, and never a checksum of a signature.
 * GitHub Packages answers a .md5, .sha1 or .sha256 request for any stored file with a digest
 * it computes itself, and a .sha512 request only when one was uploaded, so [checksums]
 * changes what is stored rather than what a resolver can fetch. A checksum only catches a
 * corrupt download: whoever can replace a file can replace its checksum, and the .asc
 * signatures and the build-provenance attestation are what a consumer trusts.
 * maven-metadata.xml is never uploaded; GitHub Packages regenerates it from its own version
 * records. A file the registry already holds fails the upload, as a published version is
 * immutable there, unless it answers a retry: then the attempt that failed in transit had
 * stored it.
 */
@UntrackedTask(because = "Uploads to an external service")
abstract class GitHubPackagesUploadTask : DefaultTask() {

  /** A Maven-layout repository, as 'publishAllPublicationsToSavaCentralStagingRepository' writes. */
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val stagingDirectory: DirectoryProperty

  /** The registry's Maven root, e.g. https://maven.pkg.github.com/sava-software/sava-build. */
  @get:Input
  abstract val baseUrl: Property<String>

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  /** Checksum file extensions uploaded beside each file, SHA-1 and SHA-256 by default; MD5 never is. */
  @get:Input
  abstract val checksums: SetProperty<String>

  /**
   * Base delay of the retry backoff, in millis. Production keeps the 1s default; tests
   * uploading to a mock registry shrink it so the suite carries no real waits.
   */
  @get:Internal
  abstract val retryDelayMillis: Property<Long>

  init {
    checksums.convention(setOf("sha1", "sha256"))
    retryDelayMillis.convention(1_000L)
  }

  @TaskAction
  fun upload() {
    val user = username.orNull
      ?: error("GitHub Packages username is missing; set the publishing username Gradle property.")
    val pass = password.orNull
      ?: error("GitHub Packages password is missing; set the publishing password Gradle property.")
    val root = stagingDirectory.get().asFile.toPath()
    val kept = checksums.get()
    val files = Files.walk(root).use { stream ->
      stream.filter { Files.isRegularFile(it) }.map { root.relativize(it) }.toList()
    }.filter { publishes(it.fileName.toString(), kept) }.sortedWith(UPLOAD_ORDER)
    check(files.isNotEmpty()) { "Nothing to upload under $root; stage the publications first." }

    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(UTF_8))
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
    val base = baseUrl.get().trimEnd('/')
    for (relative in files) {
      val path = relative.joinToString("/")
      val request = HttpRequest.newBuilder(URI("$base/$path"))
        .header("Authorization", "Basic $token")
        .header("Content-Type", "application/octet-stream")
        .timeout(Duration.ofMinutes(10))
        .PUT(HttpRequest.BodyPublishers.ofFile(root.resolve(relative)))
        .build()
      val (response, retried) = send(client, request, path)
      if (response.statusCode() == 409 && retried) {
        logger.warn("GitHub Packages already holds {}, stored by the attempt that failed in transit.", path)
        continue
      }
      check(response.statusCode() != 409) {
        "GitHub Packages already holds $path (HTTP 409): a published version is immutable, " +
          "so a release cannot be uploaded twice."
      }
      check(response.statusCode() in 200..299) {
        "GitHub Packages rejected $path with HTTP ${response.statusCode()}: ${response.body()}"
      }
      logger.info("Uploaded {}", path)
    }
    logger.lifecycle("Uploaded {} files to {}.", files.size, base)
  }

  /**
   * Retries transient failures (an I/O error, a 5xx, 408 or 429) with a linear backoff, and
   * says whether the response answers a retry.
   */
  private fun send(client: HttpClient, request: HttpRequest, path: String): Pair<HttpResponse<String>, Boolean> {
    var attempt = 1
    while (true) {
      val response = try {
        client.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (e: IOException) {
        if (attempt >= MAX_ATTEMPTS) throw e
        logger.warn("GitHub Packages upload of {} failed ({}); retrying...", path, e.message)
        null
      }
      if (response != null) {
        if (!isTransient(response.statusCode()) || attempt >= MAX_ATTEMPTS) {
          return response to (attempt > 1)
        }
        logger.warn(
          "GitHub Packages upload of {} attempt {} returned HTTP {}; retrying...",
          path, attempt, response.statusCode()
        )
      }
      Thread.sleep(retryDelayMillis.get() * attempt)
      attempt++
    }
  }

  companion object {
    private const val MAX_ATTEMPTS = 3
    private val CHECKSUM_EXTENSIONS = listOf("md5", "sha1", "sha256", "sha512")
    private val NEVER_UPLOADED = setOf("md5")

    /** A 5xx, a request timeout (408) or throttling (429). */
    private fun isTransient(status: Int) = status >= 500 || status == 408 || status == 429

    /**
     * Whether a staged file is uploaded: everything except maven-metadata, a checksum whose
     * extension is not in [checksums] or is MD5, and any checksum of a signature.
     */
    fun publishes(fileName: String, checksums: Set<String>): Boolean {
      if (fileName.startsWith("maven-metadata")) {
        return false
      }
      val checksum = CHECKSUM_EXTENSIONS.firstOrNull { fileName.endsWith(".$it") } ?: return true
      return checksum !in NEVER_UPLOADED && checksum in checksums &&
        !fileName.removeSuffix(".$checksum").endsWith(".asc")
    }

    /**
     * One version directory at a time, its POM first, so a failure leaves at most one version
     * partly uploaded; a checksum sorts after its file.
     */
    private val UPLOAD_ORDER = compareBy<Path>(
      { it.parent?.toString() ?: "" },
      { !it.fileName.toString().endsWith(".pom") },
      { it.toString() },
    )
  }
}
