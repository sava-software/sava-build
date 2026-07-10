package software.sava.build.publish

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64

/**
 * Publishes a validated USER_MANAGED Central Portal deployment, replacing the manual
 * "Publish" click in the portal UI.
 * https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment
 */
@UntrackedTask(because = "Triggers an action on an external service")
abstract class CentralPortalReleaseTask : DefaultTask() {

  @get:Input
  abstract val deploymentId: Property<String>

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  abstract val baseUrl: Property<String>

  init {
    baseUrl.convention("https://central.sonatype.com/api/v1/publisher")
  }

  @TaskAction
  fun release() {
    val id = deploymentId.orNull
      ?: error("Missing deployment id; pass '-PcentralPortalDeploymentId=<id>'.")
    val user = username.orNull
      ?: error("Central Portal username is missing; set the MAVEN_CENTRAL_TOKEN environment variable.")
    val pass = password.orNull
      ?: error("Central Portal password is missing; set the MAVEN_CENTRAL_SECRET environment variable.")

    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(UTF_8))
    val request = HttpRequest.newBuilder(URI("${baseUrl.get()}/deployment/$id"))
      .header("Authorization", "Bearer $token")
      .timeout(Duration.ofSeconds(30))
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) {
      "Central Portal deployment publish failed with HTTP ${response.statusCode()}: ${response.body()}"
    }
    logger.lifecycle(
      "Deployment {} is publishing; progress at https://central.sonatype.com/publishing/deployments",
      id
    )
  }
}
