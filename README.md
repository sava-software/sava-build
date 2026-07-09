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
  id("software.sava.build.feature.jdk-provisioning") // optional: auto-provision JDKs (foojay)
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
| `software.sava.build` | Entry point. Applies Develocity build scans, centralized repositories, and module discovery (`javaModules {}`). Includes the `:aggregation` project when `gradle/aggregation/build.gradle.kts` exists ([Publishing](#publishing)). |
| `software.sava.build.feature.jdk-provisioning` | Auto-provisions JDK toolchains via the [foojay resolver](https://github.com/gradle/foojay-toolchains). Separate from the entry point so provisioning (and its network access) stays opt-in. |
| `software.sava.build.feature-jdk-provisioning` | **Deprecated** alias for the above. |
| `software.sava.build.version-catalog` | Standalone: exposes the solana version catalog as `savaCatalog` without the rest of the conventions. |
| `software.sava.build.base.repositories` | Centralized dependency repositories: Maven Central plus sava GitHub Packages (and `extraGithubPackageRepos`). Project-level `repositories {}` blocks are rejected (`FAIL_ON_PROJECT_REPOS`). Applied by `software.sava.build`. |
| `software.sava.build.report.develocity` | Build scan configuration; local builds publish only with `--scan`. Applied by `software.sava.build`. |

### Project plugins

`software.sava.build.java-module` is the aggregate applied to every module via `javaModules {}`;
it composes the `base.*`, `feature.*`, and `check.*` plugins below. The `feature.jlink`,
`feature.publish-maven-central`, and `modules.*` plugins are applied per project as needed —
no version required, they resolve from the settings classpath.

| Plugin | Description |
|---|---|
| `software.sava.build.java-module` | Java library with modules: dependency rules, versioning, compilation, testing, javadoc, publishing, and dependency checks. |
| `software.sava.build.feature.jlink` | [jlink images](https://github.com/iherasymenko/jlink-gradle-plugin) with service binding and unsigned-jar tolerance. Adds `image`, `imageRun`, `imageModules`, ... tasks. |
| `software.sava.build.feature.publish` | Maven publishing with sources/javadoc jars, POM metadata from [sava.properties](#gradlesavaproperties), optional GPG signing, and the `savaGithubPackagesPublish` repository. Applied by `java-module`. |
| `software.sava.build.feature.publish-maven-central` | Maven Central bundling via [nmcp](https://github.com/GradleUp/nmcp) for the `:aggregation` project. |
| `software.sava.build.modules.postgresql` | Opt-in [extra-java-module-info](https://github.com/gradlex-org/extra-java-module-info) patch converting the PostgreSQL JDBC driver into an explicit module (required for jlink). |
| `software.sava.build.modules.gcp-kms` | Opt-in module patches for the Google Cloud KMS client and its non-modular transitive dependencies. |
| `software.sava.build.base.dependency-rules` | Consistent resolution against the solana version catalog BOM. |
| `software.sava.build.base.version` | Sets the project version from `-Pversion`. |
| `software.sava.build.feature.compile` | Java toolchain from `javaVersion` / `javaVendor`. |
| `software.sava.build.feature.test` | JUnit test logging and strict test-dependency analysis. |
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
  nmcpAggregation(project(":my-module"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(":my-module:publishMavenJavaPublicationToSavaGithubPackagesRepository")
}
```

- `./gradlew :aggregation:publishAggregationToCentralPortal` — bundle and upload to Maven Central.
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
(Central Portal). Published-package attestations can be verified with
[scripts/verify-package-attestations.sh](scripts/verify-package-attestations.sh).

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
