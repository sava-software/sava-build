import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import software.sava.build.attest.AttestationVerificationCache
import software.sava.build.attest.SavaAttestationsExtension
import software.sava.build.attest.VerifySavaAttestationsTask

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
savaAttestations.cosignExecutable.convention("cosign")
savaAttestations.cosignImage.convention(providers.gradleProperty("savaCosignImage").orElse(""))
savaAttestations.githubToken.convention(
  providers.environmentVariable("GITHUB_TOKEN")
    .orElse(providers.gradleProperty("savaGithubPackagesPassword"))
    .orElse("")
)

val resolvedRuntimeArtifacts = configurations.getByName("runtimeClasspath")
  .incoming.artifacts.resolvedArtifacts

// Shared across all projects of the build so overlapping classpaths verify each
// artifact digest only once per invocation.
val attestationCache = gradle.sharedServices.registerIfAbsent(
  "savaAttestationVerificationCache",
  AttestationVerificationCache::class.java
) {}

tasks.register<VerifySavaAttestationsTask>("verifySavaAttestations") {
  group = "verification"
  description = "Verifies GitHub build-provenance attestations of resolved sava dependencies"
  artifactGroups = resolvedRuntimeArtifacts.map { results ->
    results.mapNotNull { result ->
      val id = result.id.componentIdentifier
      if (id is ModuleComponentIdentifier) result.file.absolutePath to id.group else null
    }.toMap()
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
