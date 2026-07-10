package software.sava.build.attest

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Verifies the GitHub build-provenance attestations of resolved sava dependencies.
 *
 * Every sava library release is attested by 'actions/attest' in the reusable publish
 * workflow, producing a Sigstore bundle stored in GitHub's attestation store keyed by the
 * artifact's sha256 digest. This task digests each matching jar on the runtime classpath,
 * fetches its attestation bundles from the GitHub API, and verifies each bundle with
 * cosign (a local executable, or a Docker image when [cosignImage] is set).
 */
@UntrackedTask(because = "Verifies against an external attestation store")
abstract class VerifySavaAttestationsTask : DefaultTask() {

  /** Resolved artifact absolute path to its Maven group id. */
  @get:Internal
  abstract val artifactGroups: MapProperty<String, String>

  /** Resolved sources/javadoc artifact absolute path to its Maven group id. Only
   *  queried (and therefore only resolved) when [verifyDocumentation] is set. */
  @get:Internal
  abstract val documentationArtifacts: MapProperty<String, String>

  @get:Input
  abstract val verifyDocumentation: Property<Boolean>

  /** The sava-build plugin jar this build is running, when it could be located. */
  @get:Internal
  abstract val buildPluginJar: org.gradle.api.file.RegularFileProperty

  @get:Input
  abstract val verifyBuildPlugin: Property<Boolean>

  @get:Input
  abstract val buildPluginIdentityRegexp: Property<String>

  /** Maven group ids to verify; other artifacts are ignored. */
  @get:Input
  abstract val groups: SetProperty<String>

  @get:Input
  abstract val organization: Property<String>

  @get:Input
  abstract val certificateIdentityRegexp: Property<String>

  @get:Input
  abstract val requireAttestations: Property<Boolean>

  @get:Input
  abstract val cosignExecutable: Property<String>

  @get:Input
  @get:Optional
  abstract val cosignImage: Property<String>

  @get:Internal
  abstract val githubToken: Property<String>

  @get:Input
  abstract val apiBaseUrl: Property<String>

  @get:Internal
  abstract val workDirectory: DirectoryProperty

  /** Deduplicates verification of artifacts shared across project classpaths. */
  @get:Internal
  abstract val verificationCache: Property<AttestationVerificationCache>

  @get:javax.inject.Inject
  protected abstract val execOperations: ExecOperations

  @get:javax.inject.Inject
  protected abstract val fileSystemOperations: FileSystemOperations

  init {
    organization.convention("sava-software")
    apiBaseUrl.convention("https://api.github.com")
    cosignExecutable.convention("cosign")
    requireAttestations.convention(false)
    verifyDocumentation.convention(true)
    verifyBuildPlugin.convention(true)
    buildPluginIdentityRegexp.convention(
      "^https://github\\.com/sava-software/sava-build/\\.github/workflows/gradle_plugin_publish\\.yml@refs/"
    )
  }

  @TaskAction
  fun verify() {
    val groupSet = groups.get()
    fun matching(artifacts: Map<String, String>): List<File> = artifacts
      .filterValues { it in groupSet }
      .keys.map(::File)
      .filter { it.isFile && it.name.endsWith(".jar") }
      .sortedBy { it.name }

    val libraryIdentity = certificateIdentityRegexp.get()
    // File to the certificate identity its attestation must carry.
    val jars = linkedMapOf<File, String>()
    if (verifyBuildPlugin.get()) {
      buildPluginJar.orNull?.asFile?.let { jars[it] = buildPluginIdentityRegexp.get() }
    }
    matching(artifactGroups.get()).forEach { jars[it] = libraryIdentity }
    if (verifyDocumentation.get()) {
      matching(documentationArtifacts.get()).forEach { jars[it] = libraryIdentity }
    }
    if (jars.isEmpty()) {
      logger.lifecycle("No resolved dependencies match groups {}; nothing to verify.", groupSet)
      return
    }

    val workDir = workDirectory.get().asFile
    fileSystemOperations.delete { delete(workDir) }
    workDir.mkdirs()

    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
    val verified = mutableListOf<String>()
    val missing = mutableListOf<String>()
    val failed = mutableListOf<String>()

    val cache = verificationCache.get()
    for ((jar, identityRegexp) in jars) {
      val digest = sha256(jar)
      val (outcome, fromCache) = cache.memoize("$digest:$identityRegexp") {
        verifyJar(client, workDir, jar, digest, identityRegexp)
      }
      val suffix = if (fromCache) " (already verified in this build)" else ""
      when (outcome) {
        AttestationOutcome.VERIFIED -> {
          logger.lifecycle("{}: VERIFIED{}", jar.name, suffix)
          verified += jar.name
        }
        AttestationOutcome.MISSING -> {
          logger.warn("{}: NO ATTESTATION (sha256:{}){}", jar.name, digest, suffix)
          missing += jar.name
        }
        AttestationOutcome.FAILED -> {
          logger.lifecycle("{}: VERIFICATION FAILED{}", jar.name, suffix)
          failed += jar.name
        }
      }
    }

    logger.lifecycle(
      "Attestations: verified={} missing={} failed={}",
      verified.size, missing.size, failed.size
    )
    check(failed.isEmpty()) {
      "Attestation verification failed for: ${failed.joinToString(", ")}"
    }
    if (missing.isNotEmpty()) {
      check(!requireAttestations.get()) {
        "No attestation found for: ${missing.joinToString(", ")}. " +
          "Set 'savaAttestations.requireAttestations = false' to tolerate unattested releases."
      }
      logger.warn(
        "No attestation found for: {}. Tolerated because 'savaAttestations.requireAttestations' is false.",
        missing.joinToString(", ")
      )
    }
  }

  private fun verifyJar(
    client: HttpClient,
    workDir: File,
    jar: File,
    digest: String,
    identityRegexp: String
  ): AttestationOutcome {
    val bundles = fetchAttestationBundles(client, digest)
    if (bundles.isEmpty()) {
      return AttestationOutcome.MISSING
    }
    // cosign reads both files relative to the work directory, which doubles as the
    // Docker bind mount when a cosign image is configured.
    val jarCopy = File(workDir, jar.name)
    jar.copyTo(jarCopy, overwrite = true)
    val ok = bundles.withIndex().any { (index, bundle) ->
      val bundleFile = File(workDir, "${jar.name}.bundle.$index.sigstore.json")
      bundleFile.writeText(bundle)
      cosignVerify(workDir, jarCopy.name, bundleFile.name, identityRegexp)
    }
    return if (ok) AttestationOutcome.VERIFIED else AttestationOutcome.FAILED
  }

  /** Returns the Sigstore bundle JSON documents attested for the given digest. */
  private fun fetchAttestationBundles(client: HttpClient, digest: String): List<String> {
    val uri = URI("${apiBaseUrl.get()}/orgs/${organization.get()}/attestations/sha256:$digest")
    val request = HttpRequest.newBuilder(uri)
      .header("Accept", "application/vnd.github+json")
      .header("X-GitHub-Api-Version", "2022-11-28")
      .apply {
        githubToken.orNull?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
      }
      .timeout(Duration.ofSeconds(30))
      .GET()
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 404) return emptyList()
    check(response.statusCode() in 200..299) {
      "GitHub attestations API returned HTTP ${response.statusCode()}: ${response.body()}"
    }
    val attestations = (JsonSlurper().parseText(response.body()) as Map<*, *>)["attestations"]
      as? List<*> ?: return emptyList()
    return attestations.filterIsInstance<Map<*, *>>().mapNotNull { attestation ->
      val bundle = attestation["bundle"]
      val bundleUrl = attestation["bundle_url"] as? String
      when {
        bundle != null -> JsonOutput.toJson(bundle)
        bundleUrl != null -> fetchRemoteBundle(client, bundleUrl)
        else -> null
      }
    }
  }

  private fun fetchRemoteBundle(client: HttpClient, url: String): String? {
    val response = client.send(
      HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    if (response.statusCode() !in 200..299) {
      logger.warn("Could not download attestation bundle from {} (HTTP {})", url, response.statusCode())
      return null
    }
    return try {
      // Validate it is plain JSON; GitHub serves some remote bundles Snappy-compressed,
      // which this task does not decode — scripts/verify-package-attestations.sh does.
      JsonSlurper().parseText(response.body())
      response.body()
    } catch (e: Exception) {
      logger.warn("Attestation bundle at {} is not plain JSON (Snappy-compressed?); skipping.", url)
      null
    }
  }

  private fun cosignVerify(workDir: File, jarName: String, bundleName: String, identityRegexp: String): Boolean {
    val cosignArgs = listOf(
      "verify-blob-attestation",
      "--new-bundle-format",
      "--bundle", bundleName,
      "--certificate-identity-regexp", identityRegexp,
      "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
      "--type", "slsaprovenance1",
      jarName
    )
    val image = cosignImage.orNull?.takeIf { it.isNotEmpty() }
    val output = ByteArrayOutputStream()
    // With ignoreExitValue set, exec only throws when the process cannot start at all.
    val result = try {
      execOperations.exec {
        workingDir(workDir)
        if (image != null) {
          commandLine(
            listOf(
              "docker", "run", "--rm",
              "-v", "${workDir.absolutePath}:/work", "-w", "/work",
              "--entrypoint", "cosign", image
            ) + cosignArgs
          )
        } else {
          commandLine(listOf(cosignExecutable.get()) + cosignArgs)
        }
        standardOutput = output
        errorOutput = output
        isIgnoreExitValue = true
      }
    } catch (e: Exception) {
      val hint = if (image != null) {
        "Could not run Docker to verify attestations with image '$image'. Is Docker installed and running?"
      } else {
        "Could not run '${cosignExecutable.get()}' to verify attestations. Install cosign " +
          "(https://docs.sigstore.dev/cosign/system_config/installation/), or use a Docker " +
          "image instead via '-PsavaCosignImage=...' or 'savaAttestations.cosignImage'."
      }
      throw org.gradle.api.GradleException(hint, e)
    }
    val log = output.toString().trim()
    if (result.exitValue == 0) {
      logger.info("cosign: {}", log)
      return true
    }
    // Docker reserves 125 (docker run failed), 126 (not executable) and 127 (not found);
    // report those as a tooling problem, not as a failed artifact verification.
    if (image != null && result.exitValue in 125..127) {
      throw org.gradle.api.GradleException(
        "Docker could not run cosign image '$image' (exit ${result.exitValue}): $log"
      )
    }
    logger.lifecycle("cosign failed for {} with {}:\n{}", jarName, bundleName, log)
    return false
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}
