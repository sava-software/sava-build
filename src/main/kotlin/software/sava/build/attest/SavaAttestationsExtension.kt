package software.sava.build.attest

import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

/**
 * Configuration for 'verifySavaAttestations' ('software.sava.build.check.attestations').
 */
abstract class SavaAttestationsExtension {

  /** GitHub organization whose attestation store is queried. */
  abstract val organization: Property<String>

  /** Maven group ids whose resolved artifacts are verified. */
  abstract val groups: SetProperty<String>

  /**
   * Regexp the signing certificate's identity must match. Defaults to the reusable
   * 'publish.yml' workflow of sava-build, which publishes (and attests) every sava
   * library release.
   */
  abstract val certificateIdentityRegexp: Property<String>

  /** Fail when a matching artifact has no attestation (default true). */
  abstract val requireAttestations: Property<Boolean>

  /** cosign executable used to verify attestation bundles (default "cosign"). */
  abstract val cosignExecutable: Property<String>

  /**
   * Docker image providing cosign; when non-empty it is used instead of
   * [cosignExecutable] (default: the 'savaCosignImage' Gradle property).
   */
  abstract val cosignImage: Property<String>

  /**
   * Token for the GitHub API; optional for public repositories, but raises rate limits.
   * Defaults to the GITHUB_TOKEN environment variable or the 'savaGithubPackagesPassword'
   * Gradle property.
   */
  abstract val githubToken: Property<String>
}
