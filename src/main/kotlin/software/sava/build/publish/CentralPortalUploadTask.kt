package software.sava.build.publish

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import groovy.json.JsonSlurper
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Uploads a deployment bundle (a zip of one or more publications in Maven repository
 * layout) to the Maven Central Portal publisher API, then polls the deployment status
 * until validation passes (USER_MANAGED) or publishing has started (AUTOMATIC).
 * https://central.sonatype.org/publish/publish-portal-api/
 */
@UntrackedTask(because = "Uploads to an external service")
abstract class CentralPortalUploadTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val bundle: RegularFileProperty

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  /** "USER_MANAGED" (review and release in the portal UI) or "AUTOMATIC". */
  @get:Input
  abstract val publishingType: Property<String>

  /** Deployment name shown in the portal UI. */
  @get:Input
  abstract val deploymentName: Property<String>

  @get:Input
  abstract val baseUrl: Property<String>

  /** Set to false to return right after the upload without confirming validation. */
  @get:Input
  abstract val awaitValidation: Property<Boolean>

  /** How long to wait for the deployment to leave the pending/validating states. */
  @get:Input
  abstract val validationTimeoutSeconds: Property<Long>

  init {
    publishingType.convention("USER_MANAGED")
    baseUrl.convention("https://central.sonatype.com/api/v1/publisher")
    awaitValidation.convention(true)
    validationTimeoutSeconds.convention(600L)
  }

  @TaskAction
  fun upload() {
    val user = username.orNull
      ?: error("Central Portal username is missing; set the MAVEN_CENTRAL_TOKEN environment variable.")
    val pass = password.orNull
      ?: error("Central Portal password is missing; set the MAVEN_CENTRAL_SECRET environment variable.")
    val type = publishingType.get()
    check(type == "USER_MANAGED" || type == "AUTOMATIC") {
      "Invalid publishingType '$type'. Must be USER_MANAGED or AUTOMATIC."
    }

    val bundleFile = bundle.get().asFile
    val boundary = "sava-${UUID.randomUUID()}"
    val head = ("--$boundary\r\n"
      + "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundleFile.name}\"\r\n"
      + "Content-Type: application/octet-stream\r\n\r\n").toByteArray(UTF_8)
    val tail = "\r\n--$boundary--\r\n".toByteArray(UTF_8)

    val name = URLEncoder.encode(deploymentName.get(), UTF_8)
    val uri = URI("${baseUrl.get()}/upload?publishingType=$type&name=$name")
    // https://central.sonatype.org/publish/generate-portal-token/
    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(UTF_8))
    val request = HttpRequest.newBuilder(uri)
      .header("Authorization", "Bearer $token")
      .header("Content-Type", "multipart/form-data; boundary=$boundary")
      .timeout(Duration.ofMinutes(10))
      .POST(HttpRequest.BodyPublishers.ofByteArrays(listOf(head, bundleFile.readBytes(), tail)))
      .build()

    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()
    // The upload is the flakiest step of a release; retry transient failures. The portal
    // is also known to intermittently return 401 'Invalid token', so 401 is retryable.
    var response = client.send(request, HttpResponse.BodyHandlers.ofString())
    var attempt = 1
    while (response.statusCode() !in 200..299 && response.statusCode() != 404 && attempt < 3) {
      logger.warn(
        "Central Portal upload attempt {} failed with HTTP {}; retrying...",
        attempt, response.statusCode()
      )
      Thread.sleep(1_000L * attempt)
      response = client.send(request, HttpResponse.BodyHandlers.ofString())
      attempt++
    }
    check(response.statusCode() in 200..299) {
      "Central Portal upload failed with HTTP ${response.statusCode()}: ${response.body()}"
    }
    val deploymentId = response.body().trim().removeSurrounding("\"")
    logger.lifecycle("Created Central Portal deployment {} ({}).", deploymentId, type)

    if (awaitValidation.get()) {
      awaitValidation(client, token, deploymentId, type)
    } else {
      logger.lifecycle("Validation not awaited; check $PORTAL_UI")
    }
  }

  private fun awaitValidation(client: HttpClient, token: String, deploymentId: String, type: String) {
    val statusUri = URI("${baseUrl.get()}/status?id=$deploymentId")
    val request = HttpRequest.newBuilder(statusUri)
      .header("Authorization", "Bearer $token")
      .timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()

    val deadline = System.nanoTime() + Duration.ofSeconds(validationTimeoutSeconds.get()).toNanos()
    var delayMillis = 2_000L
    var consecutiveAuthFailures = 0
    while (true) {
      val (state, detail) = fetchState(client, request)
      // The portal intermittently returns 401 'Invalid token' for valid credentials, so
      // only repeated auth failures are treated as real.
      if (state == "AUTH_FAILED") {
        check(++consecutiveAuthFailures < 3) {
          "Central Portal rejected the credentials 3 times in a row: $detail"
        }
      } else {
        consecutiveAuthFailures = 0
      }
      when {
        state == "FAILED" ->
          error("Central Portal deployment $deploymentId failed validation: $detail\nDrop it at $PORTAL_UI")
        state == "VALIDATED" && type == "USER_MANAGED" -> {
          logger.lifecycle("Deployment {} passed validation; review and publish it at $PORTAL_UI", deploymentId)
          return
        }
        state == "PUBLISHING" || state == "PUBLISHED" -> {
          logger.lifecycle("Deployment {} passed validation and is {}.", deploymentId, state.lowercase())
          return
        }
      }
      val remainingNanos = deadline - System.nanoTime()
      if (remainingNanos < delayMillis * 1_000_000L) {
        error(
          "Central Portal deployment $deploymentId was still '$state' after " +
            "${validationTimeoutSeconds.get()}s; check $PORTAL_UI " +
            "(raise 'validationTimeoutSeconds' on this task if validation is just slow)."
        )
      }
      logger.lifecycle(
        "Deployment {} is '{}', checking again in {}s ({}s left)...",
        deploymentId, state, delayMillis / 1000, remainingNanos / 1_000_000_000L
      )
      Thread.sleep(delayMillis)
      // Back off to spare the API on long validations without delaying short ones.
      delayMillis = (delayMillis * 3 / 2).coerceAtMost(20_000L)
    }
  }

  /** Returns the deployment state and the raw status body for error reporting. */
  private fun fetchState(client: HttpClient, request: HttpRequest): Pair<String, String> {
    val response = try {
      client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
      // Transient network failures should not fail a release mid-poll.
      logger.warn("Central Portal status check failed ({}); retrying...", e.message)
      return "UNAVAILABLE" to ""
    }
    val code = response.statusCode()
    check(code != 404) {
      "Central Portal does not know the deployment (HTTP 404): ${response.body()}"
    }
    if (code == 401 || code == 403) {
      logger.warn("Central Portal status check returned HTTP {}; retrying...", code)
      return "AUTH_FAILED" to response.body()
    }
    if (code !in 200..299) {
      logger.warn("Central Portal status check returned HTTP {}; retrying...", code)
      return "UNAVAILABLE" to ""
    }
    val body = response.body()
    val fields = JsonSlurper().parseText(body) as Map<*, *>
    return (fields["deploymentState"] as? String ?: "UNKNOWN") to body
  }

  private companion object {
    const val PORTAL_UI = "https://central.sonatype.com/publishing/deployments"
  }
}
