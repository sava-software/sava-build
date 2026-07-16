# sava-build

Shared [Gradle convention plugins](https://docs.gradle.org/current/samples/sample_convention_plugins.html),
a composite GitHub Action, and reusable GitHub workflows used by
[sava-software](https://github.com/sava-software) projects.

- [Consumer Setup](#consumer-setup)
- [Plugins](#plugins)
- [Configuration](#configuration)
- [Publishing](#publishing)
- [GitHub Workflows & Action](#github-workflows--action)
- [Developing sava-build](#developing-sava-build)

## Consumer Setup

### Credentials

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope
to access dependencies hosted on the GitHub Package Repository, and add it to
`$HOME/.gradle/gradle.properties`:

```properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

In CI these are passed as the `ORG_GRADLE_PROJECT_savaGithubPackagesUsername` / `..Password`
environment variables, which Gradle maps to the properties automatically. If no credentials are
found the GitHub Packages repositories are skipped with a warning.

### settings.gradle.kts

```kotlin
rootProject.name = "my-project"

pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orNull?.takeIf { it.isNotBlank() }
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orNull?.takeIf { it.isNotBlank() }
    if (gprUser != null && gprToken != null) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
}

plugins {
  id("software.sava.build") version "<version>"
  // Optional: auto-provision JDKs (foojay). The version is required — every settings
  // plugin id resolves its own marker from the repositories.
  id("software.sava.build.feature.jdk-provisioning") version "<version>"
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
```

Each Java module lives in a sub-directory containing `src/main/java/module-info.java` and is
discovered automatically; nested locations can be registered explicitly with
`module("path/to/dir") { artifact = "artifact-id" }`.

### gradle/sava.properties

Project defaults read by the convention plugins. Values must be unquoted; every key can be
overridden on the command line with `-P<key>=<value>`.

| Key | Default | Used for |
|---|---|---|
| `solanaBOMVersion` | *(required)* | Version of `software.sava:solana-version-catalog` used for consistent dependency resolution |
| `javaVersion` | `25` | Java toolchain language version |
| `javaVendor` | `ORACLE` | Java toolchain vendor |
| `extraGithubPackageRepos` | *(empty)* | Additional GitHub Packages repositories, comma-separated `owner/repo` entries or full URLs |
| `productDescription` | *(empty)* | Published POM description |
| `orgName` | `Sava Software` | Published POM organization name |
| `orgPathSegment` | `sava-software` | GitHub org used in published POM URLs |
| `developerName` / `developerId` / `developerEmail` | Jim / jpe7s / jpe7s.salt188@passfwd.com | Published POM developer info |

### gradle/modules.properties

Maps Java module names of external dependencies to their Maven coordinates for
[java-module-dependencies](https://github.com/gradlex-org/java-module-dependencies), e.g.:

```properties
software.sava.core=software.sava:sava-core
org.postgresql.jdbc=org.postgresql:postgresql
```

## Plugins

### Settings plugins

| Plugin | Description |
|---|---|
| `software.sava.build` | Entry point. Applies centralized repositories and module discovery (`javaModules {}`). Includes the `:aggregation` project when `gradle/aggregation/build.gradle.kts` exists ([Publishing](#publishing)). |
| `software.sava.build.feature.jdk-provisioning` | Auto-provisions JDK toolchains via the [foojay resolver](https://github.com/gradle/foojay-toolchains). Separate from the entry point so provisioning (and its network access) stays opt-in. |
| `software.sava.build.feature-jdk-provisioning` | **Deprecated** alias for the above. |
| `software.sava.build.version-catalog` | Standalone: exposes the solana version catalog as `savaCatalog` without the rest of the conventions. |
| `software.sava.build.base.repositories` | Centralized dependency repositories: Maven Central plus sava GitHub Packages (and `extraGithubPackageRepos`). Project-level `repositories {}` blocks are rejected (`FAIL_ON_PROJECT_REPOS`). Applied by `software.sava.build`. |

### Project plugins

`software.sava.build.java-module` is the aggregate applied to every module via `javaModules {}`;
it composes the `base.*`, `feature.*`, and `check.*` plugins below. The `feature.jlink`,
`feature.jmh`, `feature.hardening`, `feature.publish-maven-central`, and `modules.*`
plugins are applied per project as needed — no version required, they resolve from the settings classpath.

| Plugin | Description |
|---|---|
| `software.sava.build.java-module` | Java library with modules: dependency rules, versioning, compilation, testing, javadoc, publishing, and dependency checks. |
| `software.sava.build.feature.jlink` | jlink images built by invoking the toolchain JDK's `jlink` directly, with service binding and unsigned-jar tolerance. Configured via `jlinkApplication {}`; adds `image`, `imageRun`, and `imageModules` tasks with output under `build/images/<applicationName>`. |
| `software.sava.build.feature.publish` | Maven publishing with sources/javadoc jars, POM metadata from [sava.properties](#gradlesavaproperties), optional GPG signing, and the `savaGithubPackagesPublish` repository. Applied by `java-module`. |
| `software.sava.build.feature.publish-maven-central` | Maven Central publishing for the `:aggregation` project: stages, bundles (`zipCentralPortalDeployment`), and uploads (`publishCentralPortalDeployment`) deployments straight to the [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/). The `nmcpAggregation` configuration and `publishAggregationToCentralPortal` task from the retired [nmcp](https://github.com/GradleUp/nmcp) plugin remain as deprecated aliases. |
| `software.sava.build.feature.jmh` | [JMH](https://github.com/melix/jmh-gradle-plugin) benchmarking conventions for standalone benchmark builds: quick-look run defaults (1 fork, 5×1s warmup, 8×1s measurement, fail-on-error), a `jmh` task that is never skipped as `UP-TO-DATE`, per-run results archived timestamped under `<project>/jmh-results/` — outside `build/`, so `clean` keeps measurement history — with `results.txt` re-rendered after each run as the newest-wins merge of all archived runs (subset runs converge on a full scoreboard; delete archive files to drop stale rows), and service-replicating JVM flags (compact object headers, generational ZGC, pinned pre-touched 2g heap, `-XX:+PerfDisableSharedMem`) — override wholesale with `jmh { jvmArgsAppend.set(...) }`. Every default is overridable per invocation: `-PjmhFork`, `-PjmhIncludes=<regex>[,...]`, `-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`, `-PjmhTimeOnIteration`, `-PjmhFailOnError`, and `-PjmhJvmArgsAppend="<flag> <flag>..."` (replaces the service flag list wholesale). Decision-grade comparisons need 3+ forks and isolation from other load. Leaves the toolchain to the consuming build (benchmark harnesses often pin bespoke JDKs). |
| `software.sava.build.feature.hardening` | [PIT](https://pitest.org) mutation testing and [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) coverage-guided fuzzing for hand-picked, correctness-critical classes, configured via `hardening {}`: each `mutation` suite (`targetClasses`, `targetTests`, optional `mutators`/`threads`) adds a `pitest<Name>` task reporting HTML/CSV under `build/reports/pitest/<name>`; each `fuzz` target (a class with `public static void fuzzerTestOneInput(byte[])`, no Jazzer imports needed) adds a `fuzz<Name>` task (`-PmaxFuzzTime=<seconds>`, default 60; optional `maxLen` caps libFuzzer input length for targets whose cost grows super-linearly with input size) whose corpus persists under `build/fuzz/<name>-corpus`, run with JVM args pre-authorizing Jazzer's dynamic agent, Unsafe usage, and native access. Each tool consumes the main and test sources recompiled into one plain, module-info-free classpath root: `compileForPitest` at `mutationBytecodeRelease` into `build/mutation-classes` and `compileForFuzz` at `bytecodeRelease` into `build/fuzz-classes` (both default 25; lower one when a tool's bundled ASM lags the toolchain's class-file version); the mutation directory name deliberately avoids the string "pitest", which PIT silently drops from classpath roots. Tool versions overridable via `pitestVersion`, `pitestJunit5PluginVersion`, and `jazzerVersion`. |
| `software.sava.build.modules.postgresql` | Opt-in [extra-java-module-info](https://github.com/gradlex-org/extra-java-module-info) patch converting the PostgreSQL JDBC driver into an explicit module (required for jlink). |
| `software.sava.build.modules.gcp-kms` | Opt-in module patches for the Google Cloud KMS client and its non-modular transitive dependencies. |
| `software.sava.build.base.dependency-rules` | Consistent resolution against the solana version catalog BOM. |
| `software.sava.build.base.version` | Sets the project version from `-Pversion`. |
| `software.sava.build.feature.compile` | Java toolchain from `javaVersion` / `javaVendor`. |
| `software.sava.build.feature.test` | JUnit test logging and strict test-dependency analysis. |
| `software.sava.build.check.attestations` | `verifySavaAttestations` task: verifies the GitHub build-provenance attestations of resolved sava dependencies (sha256 lookup in the org attestation store, cosign verification against the reusable publish workflow's identity), plus their sources/javadoc jars and the sava-build plugin jar in use (attested by `gradle_plugin_publish.yml`). Missing attestations warn until `savaAttestations.requireAttestations = true`; failed verifications always fail. Configure via `savaAttestations {}`; needs a `cosign` executable or a Docker image passed as `-PsavaCosignImage=...`. Applied by `java-module`; not part of `check` (requires network). |
| `software.sava.build.feature.javadoc` | Lenient javadoc (`Xdoclint:none`, HTML5). |
| `software.sava.build.check.dependencies` | [Dependency analysis](https://github.com/autonomousapps/dependency-analysis-gradle-plugin) and module-directive scope checks wired into `check`. |

## Configuration

Build-level Gradle properties (typically passed by the [workflows](#github-workflows--action)):

| Property | Description |
|---|---|
| `-Pversion` | Version to build/publish (the git tag in CI). |
| `-Psign=true` | Enables artifact signing; keys come from the `GPG_PUBLISH_SECRET` / `GPG_PUBLISH_PHRASE` environment variables. |
| `-PjavaVersion` | Overrides the toolchain version from `sava.properties`. |
| `-PmavenCentralExcludeChecksums=md5,sha1` | Drops checksum files from the Maven Central bundle. |

Publishing credentials:

| Credential | Description |
|---|---|
| `savaGithubPackagesPublishUsername` / `..Password` | GitHub Packages repository to publish to (write token). |
| `MAVEN_CENTRAL_TOKEN` / `MAVEN_CENTRAL_SECRET` (env) | [Central Portal](https://central.sonatype.org/) publishing token. |

## Publishing

Library repositories create `gradle/aggregation/build.gradle.kts`, which pulls the
`:aggregation` project into the build:

```kotlin
plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  // 'nmcpAggregation(...)' still works as a deprecated alias.
  centralPortalAggregation(project(":my-module"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(":my-module:publishMavenJavaPublicationToSavaGithubPackagesRepository")
}
```

- `./gradlew :aggregation:publishCentralPortalDeployment` — bundle and upload to Maven Central
  (`publishAggregationToCentralPortal` is a deprecated alias).
- `./gradlew :aggregation:publishToGitHubPackages` — publish to GitHub Packages.

Repositories that publish nothing (services) simply omit the aggregation build file.

## GitHub Workflows & Action

The composite action ([action.yml](action.yml)) checks out the repository, sets up Gradle, and
provisions JDKs. It derives the Java version from the branch (`java-21`) or tag (`21.0.3`) name,
falling back to `default-java-version`, and exposes it as the `java-version` output.

Reusable workflows (all use the composite action and the repository variables
`JAVA_VERSION`, `JDK_SRC`, and `GRADLE_JAVA_VERSION`):

| Workflow | Purpose |
|---|---|
| [check-pr.yml](.github/workflows/check-pr.yml) | `gradlew check` on pull requests (skips release-please and dependabot PRs). |
| [build.yml](.github/workflows/build.yml) | `gradlew check` on push. |
| [publish.yml](.github/workflows/publish.yml) | Check, publish to Maven Central and GitHub Packages, attest build provenance. |
| [publish-gh.yml](.github/workflows/publish-gh.yml) | Check, publish to GitHub Packages only, attest build provenance. |

Example caller:

```yaml
name: Publish Release

on:
  push:
    tags:
      - '[0-9]*.[0-9]*.[0-9]*'

jobs:
  publish:
    uses: sava-software/sava-build/.github/workflows/publish.yml@main
    secrets: inherit
    permissions:
      contents: read
      packages: write
      id-token: write
      attestations: write
      artifact-metadata: write
```

Secrets: `READ_SAVA_PACKAGES` (read token for dependencies), `GPG_PUBLISH_SECRET` /
`GPG_PUBLISH_PHRASE` (signing), and `MAVEN_CENTRAL_TOKEN` / `MAVEN_CENTRAL_SECRET`
(Central Portal). Published-package attestations can be verified with the
`verifySavaAttestations` task ([check.attestations](#project-plugins)), which covers the
resolved library jars, their sources/javadoc jars, and the sava-build plugin jar itself.

## Verifying sava artifacts

Two independent trust roots cover published sava artifacts; using both means an attacker
has to compromise two separate systems:

1. **Build provenance (GitHub / Sigstore)** — the `verifySavaAttestations` task
   ([check.attestations](#project-plugins)) proves the exact bytes came out of the
   expected GitHub workflow.
2. **PGP signatures (sava release key)** — every artifact is signed with:

   ```
   pub  ed25519 2025-06-13  jpe7s <jpe7s.salt188@passfwd.com>
        01870AD9C9DFBB1F3502D06FB89447F3AD5E2ABF
   ```

Signature checking is deliberately **not** a convention plugin: Gradle's built-in
[dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
enforces signatures at resolution time — before any resolved code (including settings
plugins like sava-build itself) can run — and it is configured by a file Gradle reads
directly, on purpose outside the reach of plugins. To adopt it in a consumer repository:

```bash
./gradlew --write-verification-metadata pgp,sha256 build
```

then review the generated `gradle/verification-metadata.xml` and trust the sava key for
sava artifacts, e.g.:

```xml
<trusted-key id="01870AD9C9DFBB1F3502D06FB89447F3AD5E2ABF" group="^software[.]sava($|([.].*))" regex="true"/>
```

The build classpath of a consumer repository resolves roughly the same third-party
plugins as sava-build itself, so this repository's
[gradle/verification-metadata.xml](gradle/verification-metadata.xml) `<trusted-keys>`
section is a reviewed starting point for those entries. Keep the generated sha256 entries
for unsigned artifacts, and regenerate with the same command after dependency bumps.

## Developing sava-build

To modify and test the convention plugins against a project that uses them, insert
`pluginManagement { includeBuild("../sava-build") }` at the top of that project's
`settings.gradle.kts` (adjusting the relative path to your checkout), then reload the project.
`sava-build` will show up next to the project in the IDE workspace and plugin changes apply on
the next build.

Verify changes with:

```shell
./gradlew check
```

This also runs TestKit smoke tests that configure a minimal consumer fixture against the
checkout. When changing dependencies, regenerate the
[verification metadata](gradle/verification-metadata.xml):

```shell
./gradlew --write-verification-metadata pgp,sha256 check generatePrecompiledScriptPluginAccessors
```
