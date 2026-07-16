import groovy.json.JsonSlurper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID

plugins {
  `kotlin-dsl`
  id("maven-publish")
  id("signing")
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

// ./gradlew --write-verification-metadata pgp,sha256 check generatePrecompiledScriptPluginAccessors
dependencies {
  // https://github.com/autonomousapps/dependency-analysis-gradle-plugin
  // https://plugins.gradle.org/plugin/com.autonomousapps.dependency-analysis
  // https://mvnrepository.com/artifact/com.autonomousapps.dependency-analysis/com.autonomousapps.dependency-analysis.gradle.plugin
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:3.17.0")

  // https://github.com/gradle/foojay-toolchains
  // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  // https://github.com/melix/jmh-gradle-plugin
  // https://plugins.gradle.org/plugin/me.champeau.jmh
  implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.3")
  // https://github.com/gradlex-org/java-module-dependencies
  implementation("org.gradlex:java-module-dependencies:1.13.1")
  // https://github.com/gradlex-org/java-module-testing
  implementation("org.gradlex:java-module-testing:1.8.1")
  // https://github.com/gradlex-org/jvm-dependency-conflict-resolution
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.5")
  // https://github.com/gradlex-org/extra-java-module-info
  implementation("org.gradlex:extra-java-module-info:1.14.2")

  testImplementation(gradleTestKit())
  // https://github.com/junit-team/junit-framework
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  // The smoke tests run TestKit builds of fixture projects that consume this
  // checkout via 'pluginManagement { includeBuild(...) }'.
  systemProperty("savaBuild.root", layout.projectDirectory.asFile.absolutePath)
  // TestKit builds the convention plugins outside this build's task graph, so
  // declare their sources as inputs to re-run tests when they change.
  inputs.dir("src/main/kotlin")
}

repositories {
  gradlePluginPortal()
}

gradlePlugin {
  plugins {
    // Named after the plugin id so the marker publication/task names match the
    // 'publishSoftware.sava.build.*' pattern of the precompiled script plugins.
    register("software.sava.build.feature-jdk-provisioning") {
      id = name
      displayName = "Deprecated alias for software.sava.build.feature.jdk-provisioning"
      implementationClass = "SavaJdkProvisioningAliasPlugin"
    }
  }
}

java {
  withJavadocJar()
  withSourcesJar()
}

// Keep in sync with the signing setup in
// src/main/kotlin/software.sava.build.feature.publish.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself).
val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
signing {
  sign(publishing.publications)
  useInMemoryPgpKeys(signingKey, signingPassphrase)
}
tasks.withType<Sign>().configureEach { enabled = publishSigningEnabled }

// Only publish markers for plugins that consumers request by id in a settings 'plugins {}'
// block: 'software.sava.build' (its marker task has no trailing '.', so it never matches the
// filter below), 'software.sava.build.feature.jdk-provisioning', and its deprecated alias.
// All other convention plugins are applied through those, or are resolved from the settings
// classpath, and need no marker.
val publishedMarkerTaskPrefixes = setOf(
  "publishSoftware.sava.build.feature.jdk-provisioningPluginMarker",
  "publishSoftware.sava.build.feature-jdk-provisioningPluginMarker"
)
tasks.named { name ->
  name.startsWith("publishSoftware.sava.build.") && publishedMarkerTaskPrefixes.none(name::startsWith)
}.configureEach {
  enabled = false
}
tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    "publishPluginMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.buildPluginMarkerMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.build.feature.jdk-provisioningPluginMarkerMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.build.feature-jdk-provisioningPluginMarkerMavenPublicationToSavaGithubPackagesRepository"
  )
}

// Allow callers to drop selected checksum files (e.g. md5, sha1, sha256, sha512) from the
// Maven Central deployment bundle via '-PmavenCentralExcludeChecksums=md5,sha1'.
val mavenCentralExcludeChecksums = providers.gradleProperty("mavenCentralExcludeChecksums")
  .map { value -> value.split(",").map(String::trim).filter(String::isNotEmpty) }
  .getOrElse(emptyList())

// Central Portal validation wants md5/sha1 and Gradle prefers sha512; sha256 files and
// checksums of .asc signatures only inflate the deployment file count against Maven
// Central's publishing limits. Restore them with '-PmavenCentralPublishAllChecksums=true'.
val defaultChecksumExcludes =
  if (providers.gradleProperty("mavenCentralPublishAllChecksums").getOrElse("false").toBoolean()) emptyList()
  else listOf("**/*.sha256", "**/*.asc.md5", "**/*.asc.sha1", "**/*.asc.sha512")

val centralStagingDir = layout.buildDirectory.dir("central-portal-staging")

publishing {
  repositories {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      // https://docs.gradle.org/current/samples/sample_publishing_credentials.html
      credentials(PasswordCredentials::class)
    }
    maven {
      name = "savaCentralStaging"
      url = uri(centralStagingDir.get().asFile)
    }
  }
}

// --- In-house Central Portal deployment. Keep in sync with
// src/main/kotlin/software.sava.build.feature.publish.gradle.kts and
// software.sava.build.feature.publish-maven-central.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself). ---

val cleanSavaCentralStaging = tasks.register<Delete>("cleanSavaCentralStaging") {
  delete(centralStagingDir)
}
tasks.withType<PublishToMavenRepository>().configureEach {
  if (name.endsWith("ToSavaCentralStagingRepository")) {
    dependsOn(cleanSavaCentralStaging)
  }
}

val zipCentralPortalDeployment = tasks.register<Zip>("zipCentralPortalDeployment") {
  group = "publishing"
  description = "Zips the staged publications into a Central Portal deployment bundle"
  dependsOn("publishAllPublicationsToSavaCentralStagingRepository")
  from(centralStagingDir)
  exclude("**/maven-metadata.xml*")
  defaultChecksumExcludes.forEach { pattern ->
    exclude(pattern)
  }
  mavenCentralExcludeChecksums.forEach { extension ->
    exclude("**/*.$extension")
  }
  destinationDirectory = layout.buildDirectory.dir("central-portal")
  archiveFileName = "deployment.zip"
}

// Resolved eagerly: computing this with provider 'map' lambdas can capture the script
// instance in the task state, which the configuration cache cannot serialize.
val centralDeploymentVersion = providers.gradleProperty("version").getOrElse("")
val centralDeploymentName = if (centralDeploymentVersion.isEmpty()) "sava-build" else "sava-build $centralDeploymentVersion"

val publishCentralPortalDeployment = tasks.register<CentralPortalUpload>("publishCentralPortalDeployment") {
  group = "publishing"
  description = "Uploads the Central Portal deployment bundle (USER_MANAGED: review and release in the portal UI)"
  bundle = zipCentralPortalDeployment.flatMap { it.archiveFile }
  username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
  password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
  deploymentName = centralDeploymentName
}

// Releases a validated USER_MANAGED deployment without the portal UI:
//   ./gradlew releaseCentralPortalDeployment -PcentralPortalDeploymentId=<id>
tasks.register<CentralPortalRelease>("releaseCentralPortalDeployment") {
  group = "publishing"
  description = "Publishes a validated Central Portal deployment ('-PcentralPortalDeploymentId=...')"
  deploymentId = providers.gradleProperty("centralPortalDeploymentId")
  username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
  password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
}

// Deprecated alias, named after the task the nmcp plugin used to provide, so existing
// workflows keep working; invoke 'publishCentralPortalDeployment' instead.
tasks.register("publishAggregationToCentralPortal") {
  group = "publishing"
  description = "Deprecated alias for publishCentralPortalDeployment"
  dependsOn(publishCentralPortalDeployment)
}

// Inline duplicate of software.sava.build.publish.CentralPortalUploadTask: this build
// compiles that class, so it cannot use it in its own build script.
// https://central.sonatype.org/publish/publish-portal-api/
abstract class CentralPortalUpload : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val bundle: RegularFileProperty

  @get:Internal
  abstract val username: Property<String>

  @get:Internal
  abstract val password: Property<String>

  @get:Input
  abstract val publishingType: Property<String>

  @get:Input
  abstract val deploymentName: Property<String>

  @get:Input
  abstract val baseUrl: Property<String>

  @get:Input
  abstract val awaitValidation: Property<Boolean>

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
      + "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
    val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    val name = URLEncoder.encode(deploymentName.get(), Charsets.UTF_8)
    val uri = URI("${baseUrl.get()}/upload?publishingType=$type&name=$name")
    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
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
      when (state) {
        "FAILED" ->
          error("Central Portal deployment $deploymentId failed validation: $detail\nDrop it at $PORTAL_UI")

        "VALIDATED" if type == "USER_MANAGED" -> {
          logger.lifecycle("Deployment {} passed validation; review and publish it at $PORTAL_UI", deploymentId)
          return
        }

        "PUBLISHING", "PUBLISHED" -> {
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
      delayMillis = (delayMillis * 3 / 2).coerceAtMost(20_000L)
    }
  }

  private fun fetchState(client: HttpClient, request: HttpRequest): Pair<String, String> {
    val response = try {
      client.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: IOException) {
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

  companion object {
    const val PORTAL_UI = "https://central.sonatype.com/publishing/deployments"
  }
}

// Inline duplicate of software.sava.build.publish.CentralPortalReleaseTask (see above).
abstract class CentralPortalRelease : DefaultTask() {

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

    val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
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

val vcs = "https://github.com/sava-software/sava-build"
publishing.publications.withType<MavenPublication>().configureEach {
  pom {
    name = project.name
    description = "Sava Gradle Conventions"
    url = vcs
    licenses {
      license {
        name = "Apache License"
        url = "$vcs/blob/main/LICENSE"
      }
    }
    developers {
      developer {
        name = "Jim"
        id = "jpe7s"
        email = "jpe7s.salt188@passfwd.com"
        organization = "Sava Software"
        organizationUrl = "https://github.com/sava-software"
      }
    }
    scm {
      connection = "scm:git:git@github.com:sava-software/sava-build.git"
      developerConnection = "scm:git:ssh@github.com:sava-software/sava-build.git"
      url = vcs
    }
  }
}
