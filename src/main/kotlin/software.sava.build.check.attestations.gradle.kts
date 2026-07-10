import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Provider
import software.sava.build.attest.AttestationVerificationCache
import software.sava.build.attest.SavaAttestationsExtension
import software.sava.build.attest.VerifySavaAttestationsTask
import java.io.File

plugins {
  id("java")
}

// Standalone verification of the GitHub build-provenance attestations of resolved sava
// dependencies (./gradlew verifySavaAttestations). Not wired into 'check': it needs
// network access to the GitHub attestation store and a cosign executable or image.

val savaAttestations = extensions.create<SavaAttestationsExtension>("savaAttestations")
savaAttestations.organization.convention("sava-software")
savaAttestations.groups.convention(setOf("software.sava"))
// Sava libraries are published and attested by the reusable publish workflow; for jobs
// of a reusable workflow the certificate identity references the called workflow.
savaAttestations.certificateIdentityRegexp.convention(
  "^https://github\\.com/sava-software/sava-build/\\.github/workflows/publish\\.yml@refs/"
)
// Warn-only until all published sava releases carry attestations; flip to true once the
// migration is complete so missing attestations fail the build.
savaAttestations.requireAttestations.convention(false)
savaAttestations.verifyDocumentation.convention(true)
savaAttestations.verifyBuildPlugin.convention(true)
// sava-build itself is published (and attested) by its own workflow, not the reusable one.
savaAttestations.buildPluginIdentityRegexp.convention(
  "^https://github\\.com/sava-software/sava-build/\\.github/workflows/gradle_plugin_publish\\.yml@refs/"
)
savaAttestations.cosignExecutable.convention("cosign")
savaAttestations.cosignImage.convention(providers.gradleProperty("savaCosignImage").orElse(""))
savaAttestations.githubToken.convention(
  providers.environmentVariable("GITHUB_TOKEN")
    .orElse(providers.gradleProperty("savaGithubPackagesPassword"))
    .orElse("")
)

val resolvedRuntimeArtifacts = configurations.getByName("runtimeClasspath")
  .incoming.artifacts.resolvedArtifacts

// Sources and javadoc jars are attested alongside the main jars; re-select the
// documentation variants of the runtime classpath (leniently: not every dependency
// publishes them). Resolution only happens if the task runs with 'verifyDocumentation'.
fun documentationArtifacts(docsType: String) = configurations.getByName("runtimeClasspath")
  .incoming.artifactView {
    withVariantReselection()
    lenient(true)
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
      attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(docsType))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
  }.artifacts.resolvedArtifacts

val resolvedSourcesArtifacts = documentationArtifacts(DocsType.SOURCES)
val resolvedJavadocArtifacts = documentationArtifacts(DocsType.JAVADOC)

// Shared across all projects of the build so overlapping classpaths verify each
// artifact digest only once per invocation.
val attestationCache = gradle.sharedServices.registerIfAbsent(
  "savaAttestationVerificationCache",
  AttestationVerificationCache::class.java
) {}

// Only parameters may be referenced inside these provider lambdas: they are serialized
// into the task state, and referencing a script-level property would capture the script
// object, which the configuration cache cannot serialize.
fun byGroup(artifacts: Provider<Set<ResolvedArtifactResult>>): Provider<Map<String, String>> =
  artifacts.map { results ->
    results.mapNotNull { result ->
      val id = result.id.componentIdentifier
      if (id is ModuleComponentIdentifier) result.file.absolutePath to id.group else null
    }.toMap()
  }

tasks.register<VerifySavaAttestationsTask>("verifySavaAttestations") {
  group = "verification"
  description = "Verifies GitHub build-provenance attestations of resolved sava dependencies"
  artifactGroups = byGroup(resolvedRuntimeArtifacts)
  documentationArtifacts = byGroup(resolvedSourcesArtifacts)
    .zip(byGroup(resolvedJavadocArtifacts)) { sources, javadoc -> sources + javadoc }
  verifyDocumentation = savaAttestations.verifyDocumentation
  verifyBuildPlugin = savaAttestations.verifyBuildPlugin
  buildPluginIdentityRegexp = savaAttestations.buildPluginIdentityRegexp
  // The jar this task's own implementation was loaded from IS the sava-build plugin in
  // use; in a composite build it is locally built and reports as unattested.
  SavaAttestationsExtension::class.java.protectionDomain.codeSource?.location?.let { location ->
    val pluginJar = File(location.toURI())
    if (pluginJar.isFile && pluginJar.name.endsWith(".jar")) {
      buildPluginJar = pluginJar
    }
  }
  groups = savaAttestations.groups
  organization = savaAttestations.organization
  certificateIdentityRegexp = savaAttestations.certificateIdentityRegexp
  requireAttestations = savaAttestations.requireAttestations
  cosignExecutable = savaAttestations.cosignExecutable
  cosignImage = savaAttestations.cosignImage
  githubToken = savaAttestations.githubToken
  workDirectory = layout.buildDirectory.dir("sava-attestations")
  verificationCache = attestationCache
  usesService(attestationCache)
}
