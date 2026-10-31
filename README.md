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
environment variables, which Gradle maps to the properties automatically. The full Sava settings
plugin skips GitHub Packages repositories with a warning when credentials are absent. A standalone
build applying a plugin from that repository cannot resolve it without credentials, so the
hardening-only example below fails fast instead.

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

### Standalone hardening-only project

The hardening plugin does not require the Sava settings plugin, module conventions,
Solana BOM, or a `software.sava.*` package. An unrelated Java build resolves its
versioned marker directly from Sava's GitHub Packages repository. Put that authenticated
repository in `pluginManagement` using the credentials described above:

```kotlin
// settings.gradle.kts
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull
      ?.takeIf { it.isNotBlank() }
      ?: error("savaGithubPackagesUsername is required to resolve sava-build")
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull
      ?.takeIf { it.isNotBlank() }
      ?: error("savaGithubPackagesPassword is required to resolve sava-build")
    maven {
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials {
        username = gprUser
        password = gprToken
      }
    }
  }
}
```

Then apply only the Java and hardening plugins. PIT's tool classpath includes its JUnit 5
integration, and every registered seed corpus produces a generated Jupiter replay test. A
standalone JUnit Platform build must supply compatible Platform/engine versions and select
`useJUnitPlatform()`; the example uses Jupiter, which is specifically required whenever replay
tests are generated:

```kotlin
// build.gradle.kts
plugins {
  java
  id("software.sava.build.feature.hardening") version "<sava-build-version>"
}

repositories { mavenCentral() }

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:<junit-version>")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:<junit-platform-version>")
}

tasks.test { useJUnitPlatform() }

hardening {
  mutation.register("core") {
    targetClasses = listOf("com.acme.product.*")
    targetTests = "com.acme.product.*Test*"
    // Optional when PIT itself diagnoses child/minion memory pressure:
    // each entry must begin with '-' and contain no whitespace, braces,
    // '#', quotes, or backslashes.
    // minionJvmArgs = listOf("-Xmx1g")
    // Optional: keep a test out of PIT's selection while it still runs under
    // 'test'. Kills come only from targetTests, so say why in the reason.
    // excludeTestClass("com.acme.product.ScriptTests", "spawns a git subprocess per test")
  }
  fuzz.register("parser") {
    targetClass = "com.acme.product.ParserFuzz"
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/parser")
  }
  // Optional generated helpers should live in a package the adopter owns.
  testSupportPackage = "com.acme.testing.hardening"
}
```

The tool-bytecode releases default to the consuming Java toolchain rather than Sava's
toolchain; lower `bytecodeRelease` or `mutationBytecodeRelease` only if a bundled tool
cannot yet read that class-file version. Generated corpus replay and shared-support
sources require Java 17 or newer. Run `./gradlew :hardeningInit`, complete its ownership
and baseline checklist, then run the template task on exactly one project that applies
the plugin (for example, `./gradlew :module:hardeningAgentTemplate`, or
`./gradlew :hardeningAgentTemplate` when the root project owns hardening) and copy the
short agent-instructions block it prints into `AGENTS.md` before treating
`hardeningCertify` as a release gate. Nothing checks that copy; re-copy it when the
printed block changes.

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

### Upgrading the plugin

Treat the installed plugin as the task authority: on an upgrade, run the applying
project's `hardeningHelp` (for example, `./gradlew :module:hardeningHelp`) for the
installed task surface, and re-copy the block printed by its project-qualified
`hardeningAgentTemplate` into `AGENTS.md` when it changed. Nothing gates `check` on
that copy. In a multi-project build, keep the task names project-qualified so one
chosen owner reports the installed version's guidance.

Run that project's `savaBuildIdentity` (for example,
`./gradlew :module:savaBuildIdentity`) to inspect the loaded plugin coordinates, code
path, SHA-256, and local validation state without running PIT. Use this identity when
checking a published upgrade: a version pin alone does not prove what Gradle loaded.
The `local override` state is `inactive` when `savaBuildLocalRepo` is unset or blank,
`not verified` when it is configured without a verified local resolution, and
`verified resolved local test publication` when the configured artifact matches the
loaded local publication. These states do not attest to the validity or provenance of
a published artifact; read the loaded coordinates and SHA-256 separately.
An empty `-PsavaBuildLocalRepo=` clears an inherited override in the consumer setup
shown below.

An upgrade owes the project's mutation suites once, at the unpushed range's pre-push
gate, unless the notes of every release between the two pins state that neither the
PIT task nor what PIT is given changed; HARDENING.md (Lifecycle) holds the rule.
Completed reports and certification receipts bind the plugin binary that wrote them,
so those written under the previous plugin stay historical and the next release
certifies fresh under the new one; a verify refuses a report whose recorded plugin
build is not the loaded one.

## Plugins

### Settings plugins

| Plugin | Description |
|---|---|
| `software.sava.build` | Entry point. Applies centralized repositories, module discovery (`javaModules {}`), and build-wide dependency analysis, including the root `buildHealth` aggregate. Includes the `:aggregation` project when `gradle/aggregation/build.gradle.kts` exists ([Publishing](#publishing)). |
| `software.sava.build.feature.jdk-provisioning` | Auto-provisions JDK toolchains via the [foojay resolver](https://github.com/gradle/foojay-toolchains). Separate from the entry point so provisioning (and its network access) stays opt-in. |
| `software.sava.build.feature-jdk-provisioning` | **Deprecated** alias for the above. |
| `software.sava.build.version-catalog` | Standalone: exposes the solana version catalog as `savaCatalog` without the rest of the conventions. |
| `software.sava.build.base.repositories` | Centralized dependency repositories: Maven Central plus sava GitHub Packages (and `extraGithubPackageRepos`). Project-level `repositories {}` blocks are rejected (`FAIL_ON_PROJECT_REPOS`). Applied by `software.sava.build`. |

### Project plugins

`software.sava.build.java-module` is the aggregate applied to every module via `javaModules {}`;
it composes the `base.*`, `feature.*`, and `check.*` plugins below. The `feature.jlink`,
`feature.jmh`, `feature.hardening`, `feature.publish-maven-central`, and `modules.*`
plugins are applied per project as needed. When the `software.sava.build` settings plugin is
present they resolve from its settings classpath and need no project-level version; a standalone
project must request its plugin by an explicit version, as in the hardening-only example above.

| Plugin | Description |
|---|---|
| `software.sava.build.java-module` | Java library with modules: dependency rules, versioning, compilation, testing, javadoc, publishing, and dependency checks. |
| `software.sava.build.feature.jlink` | jlink images built by invoking the toolchain JDK's `jlink` directly: signed jars are linked with their signature files dropped (`--ignore-signing-information`), and there is no service binding unless `bindServices = true` (`--bind-services` would add some forty JDK modules, javac, jshell and jlink among them, to a plain service image, cannot link from a JDK without jmods, and fails on an automatic module among the providers it reaches). Name the providers an application loads at run time in `addModules` (`jdk.localedata`, `jdk.charsets`, `jdk.zipfs`, `jdk.crypto.cryptoki`, an SLF4J provider, a driver declared `requires static`), and the JDK tools and agents it needs (`jdk.jfr`, `jdk.jdwp.agent` for `-agentlib:jdwp`, `jdk.attach`); `jdk.crypto.ec` below Java 22, `jdk.random` on Java 17 to 22, `jdk.localedata` with `includeLocales` and `java.logging` with `generateCdsArchive` are linked on their own. Configured via `jlinkApplication {}`; adds `image`, `imageRun`, and `imageModules` tasks with output under `build/images/<applicationName>`; on a Java 21 or newer toolchain `image` lists the module-path modules it left out and, until `bindServices` is set either way, what service binding would have linked, withholding `true` where binding would reach a module jlink cannot link. |
| `software.sava.build.feature.publish` | Maven publishing with sources/javadoc jars, POM metadata from [sava.properties](#gradlesavaproperties), optional GPG signing, and the `uploadToGitHubPackages` task (see [Publishing](#publishing)). Applied by `java-module`. |
| `software.sava.build.feature.publish-maven-central` | Maven Central publishing for the `:aggregation` project: stages, bundles (`zipCentralPortalDeployment`), and uploads (`publishCentralPortalDeployment`) deployments straight to the [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/), and registers `publishToGitHubPackages` over the same projects. The `nmcpAggregation` configuration and `publishAggregationToCentralPortal` task from the retired [nmcp](https://github.com/GradleUp/nmcp) plugin remain as deprecated aliases. |
| `software.sava.build.feature.jmh` | [JMH](https://github.com/melix/jmh-gradle-plugin) benchmarking conventions for standalone benchmark builds: quick-look run defaults (1 fork, 5×1s warmup, 8×1s measurement, fail-on-error), a `jmh` task that is never skipped as `UP-TO-DATE`, per-run results archived timestamped under `<project>/jmh-results/` — outside `build/`, so `clean` keeps measurement history — with `results.txt` re-rendered after each run as the newest-wins merge of all archived runs (subset runs converge on a full scoreboard; delete archive files to drop stale rows), and service-replicating JVM flags (compact object headers, generational ZGC, pinned pre-touched 2g heap, `-XX:+PerfDisableSharedMem`) — override wholesale with `jmh { jvmArgsAppend.set(...) }`. Every default is overridable per invocation: `-PjmhFork`, `-PjmhIncludes=<regex>[,...]`, `-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`, `-PjmhTimeOnIteration`, `-PjmhFailOnError`, and `-PjmhJvmArgsAppend="<flag> <flag>..."` (replaces the service flag list wholesale). Decision-grade comparisons need 3+ forks and isolation from other load. Leaves the toolchain to the consuming build (benchmark harnesses often pin bespoke JDKs). |
| `software.sava.build.feature.hardening` | Registers configured [PIT](https://pitest.org) mutation suites, [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets, baseline diagnostics and writers, release certification, and optional generated test support through `hardening {}`. It is package-agnostic and works with open-source PIT; an applicable ArcMutate licence is optional. See the [standalone example](#standalone-hardening-only-project), run `./gradlew :module:hardeningHelp` for the installed version's tasks and options (or `./gradlew :hardeningHelp` when the root project owns the plugin), and use [HARDENING.md](HARDENING.md) for policy. |
| `software.sava.build.modules.postgresql` | Opt-in [extra-java-module-info](https://github.com/gradlex-org/extra-java-module-info) patch converting the PostgreSQL JDBC driver into an explicit module (required for jlink). |
| `software.sava.build.modules.gcp-kms` | Opt-in module patches for the Google Cloud KMS client and its non-modular transitive dependencies. |
| `software.sava.build.base.dependency-rules` | Consistent resolution against the solana version catalog BOM. |
| `software.sava.build.base.version` | Sets the project version from `-Pversion`. |
| `software.sava.build.feature.compile` | Java toolchain from `javaVersion` / `javaVendor`. |
| `software.sava.build.feature.test` | JUnit test logging and strict test-dependency analysis. |
| `software.sava.build.check.attestations` | `verifySavaAttestations` task: verifies the GitHub build-provenance attestations of resolved sava dependencies (sha256 lookup in the org attestation store, cosign verification against the identity of the reusable publish workflow that released them, `publish.yml` or `publish-gh.yml`), plus their sources/javadoc jars and the sava-build plugin jar in use (attested by `gradle_plugin_publish.yml`). Missing attestations warn until `savaAttestations.requireAttestations = true`; failed verifications always fail. Configure via `savaAttestations {}`; needs a `cosign` executable or a Docker image passed as `-PsavaCosignImage=...`. Applied by `java-module`; not part of `check` (requires network). |
| `software.sava.build.feature.javadoc` | Lenient javadoc (`Xdoclint:none`, HTML5). |
| `software.sava.build.check.dependencies` | Per-project [dependency analysis](https://github.com/autonomousapps/dependency-analysis-gradle-plugin) (`projectHealth`) and module-directive scope checks wired into `check`; the `software.sava.build` settings entry point supplies the root `buildHealth` aggregate. |

The hardening plugin is not restricted to Sava package names. Any Java project can
register its own production namespaces, mutation suites, exclusions, and fuzz targets.
The ArcMutate certificate committed at this repository's root is optional and applies
only to eligible public `software.sava.*` projects—not GLAM, private `idl-src-gen`, or
unrelated adopters. It is not packaged into the plugin or copied into consumers. An
eligible repository normally activates it by deliberately placing the certificate at
that repository's root; a project-directory certificate can instead activate one module,
with the nearest ancestry certificate becoming effective. Every other adopter uses the
same process with open-source PIT.

## Configuration

Build-level Gradle properties (typically passed by the [workflows](#github-workflows--action)):

| Property | Description |
|---|---|
| `-Pversion` | Version to build/publish (the git tag in CI). |
| `-Psign=true` | Enables artifact signing; keys come from the `GPG_PUBLISH_SECRET` / `GPG_PUBLISH_PHRASE` environment variables. |
| `-PjavaVersion` | Overrides the toolchain version from `sava.properties`. |
| `-PmavenCentralExcludeChecksums=<ext>,...` | Drops further checksum files from the Maven Central bundle. The bundle already leaves out `.sha256`, `.sha512` and checksums of signatures, keeping the `.md5` and `.sha1` Central requires; `-PmavenCentralPublishAllChecksums=true` restores them. |

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
```

- `./gradlew :aggregation:publishCentralPortalDeployment` — bundle and upload to Maven Central
  (`publishAggregationToCentralPortal` is a deprecated alias).
- `./gradlew :aggregation:publishToGitHubPackages` — publish to GitHub Packages.

Both tasks come from the plugins, over the projects declared with `centralPortalAggregation`
(a module that is published to GitHub Packages only is declared there too; nothing reaches
Central until `publishCentralPortalDeployment` runs). An aggregation that declares no project
fails `publishToGitHubPackages` rather than passing with nothing uploaded.
`software.sava.build.feature.publish` registers `uploadToGitHubPackages` on each library
project (a module's `publish` runs it too), and the aggregation's task runs it for every
aggregated project; the name differs so that an unqualified `publishToGitHubPackages` still
reaches only the aggregation. The upload stores each staged file with a SHA-1 and a SHA-256
checksum beside it, no MD5, and no checksum of a signature; the task's `checksums` property
changes the set, except that MD5 is never sent. Gradle's own Maven publisher is not used for
GitHub Packages because it uploads MD5 beside every file and cannot be told not to. GitHub
Packages answers a `.md5`, `.sha1` or `.sha256` request for any stored file with a digest it
computes itself (and `.sha512` only when one was uploaded), so the set changes what is
stored, not what Gradle, Maven Resolver or coursier can fetch. A checksum only catches a
corrupt download; the `.asc` signatures and the build-provenance attestation are what a
consumer trusts. `maven-metadata.xml` is not uploaded (the registry regenerates it from its
own version records), and a version the registry already holds fails the upload, since
GitHub Packages versions are immutable. Maven Central still receives MD5 and SHA-1, which it
requires, through the deployment bundle.
`-PsavaGithubPackagesPublishUrl` points the upload at another registry, which the
functional tests use.

Aggregation scripts written before sava-build 21.6.0 register their own
`publishToGitHubPackages` on top of `:<module>:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository`.
They keep working unchanged, and upload the same way: those task names remain as deprecated
aliases of `uploadToGitHubPackages`, and the plugin then leaves `publishToGitHubPackages` to
the script, with a deprecation warning. To migrate, declare every module the script's task
publishes with `centralPortalAggregation(project(...))`, then delete the registration. A
script that does not apply `publish-maven-central` depends on
`:<module>:uploadToGitHubPackages` instead.

Repositories that publish nothing (services) simply omit the aggregation build file.

## GitHub Workflows & Action

The composite action ([action.yml](action.yml)) checks out the repository, sets up Gradle, and
provisions JDKs. It derives the Java version from the branch (`java-21`) or tag (`21.0.3`) name,
falling back to `default-java-version`, and exposes it as the `java-version` output. On a tag
ref it first refuses any tag that is not a plain `X.Y.Z` version, whichever workflow calls it,
then, after checkout, any tag other than the version the tagged commit's
`.release-please-manifest.json` names, when the repository has one, and takes the tag's major
as the Java version. A pull
request from a fork receives no configuration variables, so there it falls back to
`javaVersion` in `gradle/sava.properties` (which a repository taking outside pull requests must
set) for both JDKs and to `oracle.com` as the download site.

It closes the eMudhra incident (CVE-2026-91403) around the JDKs. Before the checkout, on a
Linux runner, it distrusts the compromised intermediates' roots in the OS trust store git
fetches over, and it checks the runner JDK that `oracle-actions/setup-java` downloads with
(`JAVA_HOME_21_X64` or `JAVA_HOME_21_arm64`; a runner without one, or whose JDK still trusts
the chains, as a macOS image does until its Temurin 21 is patched, fails here; a Windows runner
is unverified).
After each install it checks the installed JDK. Java 21 is the floor of the versions it
installs, since that check compiles with the installed JDK. Its
last step warns when the repository has no `gradle/verification-metadata.xml`, or one that does
not verify both checksums and signatures (see [Verifying sava artifacts](#verifying-sava-artifacts)).
The reusable `release-please.yml` installs through `.github/actions/setup-checked-jdk@main`,
which follows this repository's `main` whatever ref a caller names for the workflow, and now
needs `JAVA_VERSION` and `JDK_SRC` set (an empty value is refused by name). Out of reach of all
this: the runner fetches the actions themselves at job setup, before any step and so over the
unfixed store, and Node-based actions such as `setup-gradle` use Node's bundled store.
`action_self_test.yml` runs the action from this repository with two different JDK versions on
every change to the action files and weekly, the path a run whose two versions agree skips.
Every check of the plugin, on a push to `main`, on a pull request and on a release tag, runs a
second job on a Temurin JDK, which ships no jmods, for the jlink tests alone
(`gradle_plugin_jlink_without_jmods.yml`): the paths the image task takes on such a toolchain,
where jlink cannot link jdk.jlink, never run on the Oracle JDK the rest of the check uses.

Reusable workflows (all use the composite action and the repository variables
`JAVA_VERSION`, `JDK_SRC`, and `GRADLE_JAVA_VERSION`):

| Workflow | Purpose |
|---|---|
| [check-pr.yml](.github/workflows/check-pr.yml) | `gradlew check` on pull requests (skips release-please and dependabot PRs). A pull request from a fork receives no secrets, so it builds offline from `main`'s Gradle cache, and the [fork-pr-guard](.github/actions/fork-pr-guard) action fails it if it changes Gradle scripts, the wrapper, `gradle/`, version catalogs, release-please configuration, `.github/`, or a `requires` directive on a module outside the JDK in `module-info.java` (read by a Java program the action compiles, tested by `guardTest`). |
| [build.yml](.github/workflows/build.yml) | `gradlew check` on push. |
| [publish.yml](.github/workflows/publish.yml) | Check, publish to Maven Central and GitHub Packages, attest build provenance. |
| [publish-gh.yml](.github/workflows/publish-gh.yml) | Check, publish to GitHub Packages only, attest build provenance. |

Both publishing workflows run only for a tag that is a plain `X.Y.Z` version and, in a
release-please repository, the version the tagged commit's manifest names (pass `manifest-file`
when release-please.yml is given one), which no edit made while the tag was cut can change.
The release-please guard reads a release pull request before Release Please reads it again, so
someone able to edit that pull request in between could still have a tag and a GitHub release
created; this check keeps such a tag from being published. The guard also reads the commit
overrides in the merged pull requests' bodies before Release Please does, so a merged pull
request's author editing one in between could steer the version of the release pull request
Release Please then writes; the guard's version check (a release pull request's version must
be the one its commits give, up to its merge for a merged one, under `always-bump-patch`, `release-as` or a `Release-As`
footer, the cases it computes) refuses that pull request on its next run. Every expression reaches their scripts through environment
variables, since release-please takes the tag from the merged release PR's title. Each runs `check` through
[release-check.yml](.github/workflows/release-check.yml), a separate workflow passed only
`READ_SAVA_PACKAGES`, so no publishing secret is in its secrets context. GitHub's docs say
Actions "scrubs secrets from memory that are not referenced in the workflow", so a test job
inside the publishing workflow might still reach the signing key. The publish job then
builds again without running tests and without the Gradle cache (`cache-disabled`,
`--no-build-cache`), and its checkout persists no git credential for later steps
(`persist-credentials`). The plugin's own release does the same through
[gradle_plugin_release_check.yml](.github/workflows/gradle_plugin_release_check.yml).

For this repository, [Gradle Dependency Submission](.github/workflows/gradle_plugin_dependency_submission.yml)
reports resolved build and test dependencies and the Gradle version to GitHub on pushes to `main`
for Dependabot vulnerability alerts. A read-only job generates and checks the snapshot with
strict dependency verification; a separate job receives `contents: write` only to submit
that run's artifact, without checking out or executing the build. This does not enable
submission in the reusable workflows or composite action used by consumer repositories.

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
directly, on purpose outside the reach of plugins. What the policy cannot cover: the wrapper
distribution, which `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`
pins separately, JDK downloads, GitHub Actions and shell downloads. A verified artifact is
the publisher's, not necessarily free of vulnerabilities: keep patching dependencies.

### The policy and what it trusts

A repository's policy is `gradle/verification-metadata.xml`, with the public keys it names
in `gradle/verification-keyring.keys` and the version-scoped list below, all under the root
`gradle/`, and `org.gradle.dependency.verification=strict` in the root `gradle.properties`:
strict is Gradle's default while the file is present and does nothing without it, so the
line changes nothing for Gradle and declares the policy to [the guard](#the-guard), which
then fails `check` should the file go missing. Gradle reads only the invoking build's
`gradle/` directory, so a standalone build in the repository, a directory with a settings
script of its own such as sava's `jmh/` and `soak/`, holds symlinks to the metadata and the
keyring; an included build is covered by the build that includes it. A Docker image build
copies the policy files together, and a deny-by-default `.gitignore` needs allow-rules for
all three and the `gradle/verification/` directory, or git drops the policy without a word:
CI then warns, and the guard fails `check` through the declaration and through any of the
files that survived.

The policy trusts, and nothing else:

- **Publisher keys**, each for the group or module it signs and for no version, so a release
  signed by the same key passes without an edit. Confirm a fingerprint from the publisher
  (its KEYS file, README or site, or the key registered on the GitHub account of one of its
  maintainers), never from a key server alone. A key whose publisher publishes no fingerprint
  stays version-free only where gradle/gradle's own policy trusts it for the same group, and
  the header of the list below names it as trusted on that, a note the guard does not read;
  with neither source the key goes on the list. The sava key is trusted for `software.sava`
  and its subgroups:

  ```xml
  <trusted-key id="01870AD9C9DFBB1F3502D06FB89447F3AD5E2ABF" group="^software[.]sava($|([.].*))" regex="true"/>
  ```

- **Keys listed in `gradle/verification/version-scoped-keys.txt`**, one key per line with
  the reason, trusted only at the exact versions in the policy: keys listed as
  unauthenticated, the PIT and ArcMutate signers and any library signer that neither source
  above authenticates, and keys listed as expired, DSA-1024 or a retired release manager's,
  which signed old releases in the graph and must not vouch for new ones. A bump under a key
  listed as unauthenticated moves the version after the release is checked, unless its line
  gives a reason to stop; a new release under a key that must not vouch is a reason to stop
  and find out why an old key signed it. Every repository with a policy has the file,
  comments alone when no key is version-scoped.
- **SHA-256 checksums** for artifacts that are not signed, such as the jmh plugin, the
  `org.gradle.kotlin.kotlin-dsl` plugin marker the wrapper embeds (its pin moves with a
  wrapper bump) and the antlr libraries PIT depends on, each reviewed before it is pinned.
- Key servers are off (`<key-servers enabled="false"/>`), so keys come only from the
  committed keyring; `verify-metadata` and `verify-signatures` are true and `keyring-format`
  armored; there are no ignored keys and no `trusted-artifacts` exemptions.

Start a repository from the keys this repository, sava and ravina trust, never from a bulk
copy of a policy, since every graph differs: ArcMutate appears only where
`arcmutate-licence.txt` exists, and a cold cache fetches BOM and parent POMs a warm one has.

### Adopting and updating

A new policy is the three files and the declaration, committed together, with allow-rules
in a deny-by-default `.gitignore`; start it from the fleet's keys as above. For a new
policy and for every change to one:

1. Run the change through every build of the repository; CI runs only the root one:

   ```bash
   ./gradlew --no-configuration-cache check resolveVerificationTools
   ```

   Without the configuration cache: a reused entry resolves nothing, and so verifies nothing.
   `resolveVerificationTools`, registered by the [hardening feature](#project-plugins) in
   each hardened project, resolves the PIT, ArcMutate and Jazzer artifacts that only the
   mutation and fuzz tasks resolve, so `check` covers them without a campaign; without it a
   repository first meets an unverified tool artifact at its pre-push mutation gate or a fuzz
   run. A repository with no hardened project runs `check` alone. ArcMutate is on that
   classpath only where `arcmutate-licence.txt` is present, so run this step on a licensed
   machine, or the ArcMutate rule goes unexercised until a licensed `pitest<Suite>` gate
   refuses the bump. Run a standalone build's own tasks too (`./gradlew -p jmh jmhJar`, in
   sava).

2. A "Dependency verification failed" report names each refused artifact and the reason.
   - **A bump under a key listed as unauthenticated** (the PIT and ArcMutate signers, a
     library signer with no publisher source): check the release, then change the `version`
     on that key's rule, unless its line gives a reason to stop. Finding the publisher source
     later widens the rule to the group or module the key signs and takes the key off the
     list, in one edit, unless the key is also one that must not vouch.
   - **A new release signed by a key that must not vouch**: stop; do not extend its
     version. An existing old release that a dependency change brings into the graph under
     one of these keys can be added as another exact version once checked.
   - **A new signing key**: confirm the fingerprint from the publisher, then add a
     `trusted-key` scoped to the publisher's group or module, and its public key to the
     keyring. With no publisher source, a key that gradle/gradle's own policy trusts for the
     same group gets the same rule and a note in the list's header; a key with neither gets
     a rule at the exact version and a line in the list.
   - **An unsigned artifact**: review it, then pin its SHA-256 in a `component` entry.

3. Gradle can draft these entries. Run it online, on a clean tree, from the checkout root
   (from a standalone build it replaces the keyring symlink with a copy; with `--offline`
   Gradle 9.8.2 pinned POMs whose cached signatures it did not consult), then keep only what
   you reviewed:

   ```bash
   ./gradlew --no-configuration-cache --write-verification-metadata pgp,sha256 --export-keys \
     build resolveVerificationTools
   ```

   The result is a candidate, never the reviewed policy. On a tool bump it widens the
   version-scoped key to every version, and it can narrow a publisher key to the one version
   it saw, as a rule with a version or as a per-artifact `<pgp>` entry; it writes an
   `<ignored-keys>` fallback, with checksums, for a key it cannot find, after which that
   signature is never checked; it never removes a stale entry; and it may consult a key
   server while writing, so authenticate any key it adds. `--export-keys` writes only the
   keys the resulting metadata references, so an export after a prune drops the pruned
   entries' keys. The committed files should be Gradle's own output for the graph, so that a
   regeneration with no dependency change is a no-op and any diff is a change to review.

4. A warm cache can hide parent-POM metadata that a cold one fetches. Validate a new parent
   or BOM once with an empty `GRADLE_USER_HOME`: copy only the `org.gradle.java.*` lines of
   your `gradle.properties`, and pass package credentials as `ORG_GRADLE_PROJECT_*`
   environment variables. Offline use needs an online run first; cached signature files
   alone do not prove an offline run can resolve them.

5. Sync IntelliJ as well, with download sources on. It resolves artifacts through the same
   policy that no command-line build does: the Gradle sources zip, which Gradle Inc.'s
   publishing key (`EA96F38569C044AAEF7FCF732887F479B0B9771A`, signing subkey
   `51FBF517CE6D6B80`) covers under a `gradle:gradle` rule, Groovy's sources and module
   under the `org.apache.groovy` key, and unsigned plugin sources jars, pinned with their
   plugin. A refusal shows in the sync output; the IDE reloads only after a build-script
   change that is not a comment, and failures land in
   `~/.gradle/daemon/<version>/daemon-<pid>.out.log`.

### The guard

The settings plugin guards the policy: `verificationPolicy`, registered on the root project
with every project's `check` and `qualityGate` depending on it, fails when the policy breaks
the rules above. Each of the four settings must be one
active `<configuration>` element written exactly; there are no ignored keys,
`trusted-artifacts` exemptions, per-artifact `<pgp>` entries, `md5` or `sha1` pins, or
`component` pins of `0.0.0-test`, which a regeneration run with `-PsavaBuildLocalRepo`
writes; every `trusted-key` rule names a group, trusts no file, and uses no regex but the
one Gradle writes for a group of at least two segments and its subgroups; a key listed in
`version-scoped-keys.txt` is trusted only at exact versions and an unlisted key never at a
version; a listed key no rule uses is a stale line; the root `gradle.properties` carries the
declaration, which a standalone build's own line never stands in for, and no committed
`gradle.properties`, in the root or in a standalone build, relaxes the mode, with or without
a policy; a keyring, a version-scoped list, a standalone
build's links or the declaration without the policy file is a policy deleted or never
committed and fails, while a repository with no policy and none of those is left to the CI
notice; and a standalone build, a direct child
directory with a settings script of its own, included by the root or not, holds the two
files as symlinks to the root's, since a build invoked directly reads only its own
`gradle/`; a child holding a `.git` entry is another checkout, not a build of this one. A
standalone build that applies the settings plugin itself gets a guard of its own, which
follows its policy link into its parent directory's build, runs the rules there and reports
them as the parent's; a link anywhere else, a dangling one, or a linker that guard would
never discover is refused. The
task reads the checkout as it is, the committed files and the direct child directories'
settings scripts, so a scratch project inside the root is seen too unless it is a clone with
a `.git` of its own; a mode passed on the command line or set in `~/.gradle` is beyond it,
as is the configuration cache, which Gradle reuses across `--dependency-verification`
changes. CI warns, through sava-build's root action, when a repository has no policy or a
committed `gradle.properties` relaxes the mode, on any plugin version; the no-policy warning
becomes an error once the fleet carries policies. A repository whose existing policy breaks
the rules fails `check` on adoption:
bring the policy into line first, starting from the fleet's keys.

### Unpublished sava-build

The `0.0.0-test` artifacts that `-PsavaBuildLocalRepo` resolves are unsigned local build
output, so a strict policy refuses them at settings-plugin resolution. Pass
`--dependency-verification lenient` with the property, for that flow only; it reports the
refusals instead of failing, and `tools/local-fuzz.sh` passes it for every consumer run and
fails the run if anything else is refused. Never add trust for `0.0.0-test` to a policy, and
never relax `org.gradle.dependency.verification` in a committed or a user-wide
`gradle.properties`: the first `verificationPolicy` refuses, the second relaxes every build
on the machine where no guard can see it; the committed value is `strict`, the policy's
declaration. See [Local adoption and releasing](#local-adoption-and-releasing).

### Engine controls

`tools/VerificationControls.java` exercises Gradle's verification engine itself, with
disposable keys, artifacts and a loopback repository: checksum and signature refusals,
untrusted and missing keys, key scoping, artifact-level pins, policy ownership in composite
builds, and refusal before settings code runs. Re-run it after a Gradle wrapper bump, from
any checkout, with a JDK 25 or newer, GnuPG and that checkout's wrapper distribution
installed:

```bash
java --source 25 tools/VerificationControls.java --checkout <checkout> --work-dir <new directory>
```

It uses private Gradle and GnuPG homes (the GnuPG home under `/tmp`, where an agent socket
path fits the limit), passes child processes an allow-listed environment, reads no
credentials, writes no shared cache, and takes about a minute. It tests the engine, not a
repository's policy; `verificationPolicy` and the builds above do that.

## Developing sava-build

To drive a plugin change from a consumer project, publish this checkout to its local
Maven test repo and point the consumer at it. **The publish is not automatic**, and a
forgotten one is invisible in the consumer's output — it just keeps resolving the
previously published jar — so chain the two rather than remembering the first:

```shell
(cd <sava-build> && ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository) \
  && ./gradlew check -PsavaBuildLocalRepo=<sava-build>/build/sava-test-repo --dependency-verification lenient
```

Run from the consumer's directory. The `0.0.0-test` publication is unsigned, so a consumer
that enforces [dependency verification](#verifying-sava-artifacts) refuses it under the
strict default; `--dependency-verification lenient` reports it instead of failing. Pass the
flag on every command that carries the property, and on no other; with the property in
`~/.gradle/gradle.properties`, that is every command until it is removed. A republished
`0.0.0-test` is picked up immediately (`file:` repositories are re-read on each resolution),
so the chained form makes the stale case unreachable instead of merely detectable. In a
composite (a `jmh/` that `includeBuild`s its root), prefer an absolute property value: a
relative one is resolved against each build's own settings dir, so the builds would read two
different repos — and the notice reports the dir the build that registered it resolved,
which need not be the one that served the plugins.

Lenient mode demotes every refusal to a report, so read the "Dependency verification failed"
report of each run: anything refused other than `0.0.0-test` is a refusal to settle before
pushing, as `tools/local-fuzz.sh` does for every consumer run, failing the consumer on it
and naming the refused modules. A refusal of a third-party artifact that only the unreleased
plugin pulls in means the consumer's policy lags the plugin's dependencies: trust that
artifact or key in the consumer first. A run that reuses a configuration-cache entry reports
nothing, at execution time too, since Gradle reuses the stored resolution. The chained
command above configures afresh, because the publication's provenance sidecar changes with
every publish and invalidates the entry (Gradle attributes the change to the property); a
`check` repeated without the publish, the second half of the chain alone, replays it and
prints nothing, so pass `--no-configuration-cache` to such a run, as the script's discovery
run does. The script also fails a consumer that verifies its dependencies yet reports no
refusal of `0.0.0-test`, in either of its runs. Never set
`org.gradle.dependency.verification` in a user-wide `gradle.properties`, and never commit
trust for `0.0.0-test` to a consumer's policy: a policy that trusts unsigned local output is
no longer a record of reviewed trust.

Do not republish the static `0.0.0-test` coordinate while a consumer build is running.
The plugin freezes both the loaded code SHA-256 and the configured local-repository JAR
SHA-256 when settings apply, checks them again at evidence boundaries, and refuses PIT,
fuzz, and certification evidence if either path changes. After Maven publication succeeds,
the publish task writes a strict out-of-band provenance TSV beside the JAR. It binds that
JAR digest to the commit and tree, clean/dirty state and Git-status digest, a SHA-256 over
the names, kinds, and contents of every tracked and non-ignored untracked worktree path,
and UTC publication time without making reproducible JAR bytes vary. The task always
executes, so a newer source commit receives a fresh provenance record even when its
packaged bytes are identical. This is the
source snapshot observed as publication completed; Gradle's normal inputs remain responsible
for constructing the JAR, and the sidecar does not claim that the checkout was locked for the
whole build.
Start a new consumer invocation after every publish.

When a handoff or a stale-coordinate diagnosis calls for `--refresh-dependencies`, treat
that invocation as a transport refresh, not a configuration-cache reuse probe. Gradle
does not reuse an existing configuration-cache entry while that flag is present; repeat
the same task graph without it twice when needed — the second no-refresh run must report
that the configuration cache was reused.

Every build that resolves plugins from the local repo also says so, once, at the end:

```
sava-build: this build resolved every 'software.sava.build*' plugin to 0.0.0-test from
the local repo /…/build/sava-test-repo (published 2026-09-02T12:34:56Z (3 min ago);
source snapshot at publication: commit 012345…cdef; tree fedcba…543210; clean worktree; source-state SHA-256
789abc…456def; application-time SHA-256 abcdef…012345), NOT the versions in the plugins block.
```

That notice comes from the plugin itself (`SavaBuildLocalRepoNoticePlugin`, applied by
`software.sava.build`), as a dataflow action rather than a line in the consumer's
settings script — a settings script is skipped on a configuration cache hit, which
silenced the warning in exactly the cases worth warning about: a forgotten publish
changes nothing, so the entry is reused, and switching back into local-repo mode reuses
an existing entry too. The sidecar content is frozen when settings apply and its UTC
timestamp is aged when the action runs. A same-JAR republish changes the sidecar, invalidates
the consumer's configuration-cache entry, and reports the new source snapshot at publication,
including its commit and complete source-state digest. This snapshot records the checkout
observed when the publication completed; it does not claim that checkout is still unchanged
or that it was locked throughout artifact construction. The sidecar does not identify an
exact producer-checkout path, so the notice deliberately does not
guess one from the local Maven repository layout or turn later checkout drift into an artifact
provenance failure. The application-time SHA names
the bytes loaded when the settings plugin applied; a changed local artifact, sidecar, or
JAR/sidecar pairing makes the build fail instead of silently reporting mixed provenance.

The canonical consumer-side block, for a `settings.gradle.kts` `pluginManagement {}`
(copy it whole — the property belongs in `~/.gradle/gradle.properties` or on the CLI,
never hardcoded in the file):

```kotlin
pluginManagement {
  // Point '-PsavaBuildLocalRepo=<sava-build>/build/sava-test-repo' (or set it in
  // ~/.gradle/gradle.properties) at a local sava-build checkout to build against an
  // unpublished plugin change; sava-build publishes that repo with
  //   ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository
  // and every id below then resolves to the 0.0.0-test module regardless of the
  // version the plugins block requests — which the plugin announces at the end of
  // each build, so this block stays silent. The useModule call also bypasses plugin
  // markers, which the test repo does not contain. Pass '--dependency-verification
  // lenient' with the property, on every command: the test repo's artifacts are
  // unsigned, so a strict verification policy refuses them.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    resolutionStrategy.eachPlugin {
      if (requested.id.id.startsWith("software.sava.build")) {
        useModule("software.sava:sava-build:0.0.0-test")
      }
    }
  }
  repositories {
    if (savaBuildLocalRepo != null) {
      maven(url = savaBuildLocalRepo)
    }
    gradlePluginPortal()
    mavenCentral()
  }
}
```

When done, drop the property and the flag; the unset path is the normal published
resolution.

Verify changes with:

```shell
./gradlew check
```

The [CodeQL Advanced workflow](.github/workflows/codeql.yml) scans GitHub Actions and
compiles the Kotlin convention plugins for analysis on pushes and pull requests to
`main`, weekly, and on manual dispatch. Its compilation keeps dependency verification
strict and disables build/configuration caches and Kotlin incremental compilation so
CodeQL observes the sources being compiled. The scan uses the repository's `JDK_SRC` and
`GRADLE_JAVA_VERSION` variables, matching the Gradle build workflow.

The `check` task also runs TestKit functional tests against a local publication of the
checkout. Tests use fresh fixture directories and two workers by default. The larger
functional suites are split by feature so both workers can stay busy. To compare worker
counts on a particular machine, force just the test task to execute with, for example,
`./gradlew test --rerun -PsavaBuildTestForks=4`; compare with the default two workers using
the same warm caches. Extra workers also need extra TestKit daemons and memory.

The plugin check workflows cache TestKit's separate dependency and compiled-script caches
under `build/tmp/test/work/.gradle-test-kit/caches`, in addition to the outer build's
`setup-gradle` cache. Only successful main builds save TestKit caches; PRs restore them.
Fixture outputs, daemon state, file locks, and configuration-cache encryption keys are
not retained. `check` also runs the local fuzz runner's syntax check and self-test, and
`guardTest`, the tests of the Java programs the composite actions under `.github/actions`
run, each action's source directory compiled on its own at Java 21 with every lint as an
error (the actions compile the same directories without the lints, so a lint a newer JDK
adds cannot fail every repository's release run).

When changing dependencies, regenerate the
[verification metadata](gradle/verification-metadata.xml):

```shell
./gradlew --no-configuration-cache --write-verification-metadata pgp,sha256 --export-keys \
  check generatePrecompiledScriptPluginAccessors
```

The result is a candidate, never the policy: review it against
[Verifying sava artifacts](#verifying-sava-artifacts), whose rules this repository's own
policy keeps (`VerificationPolicySelfTest` runs them against it, since the build cannot apply
the plugin it compiles to itself), and validate it as [Adopting and
updating](#adopting-and-updating) step 1 says, without the configuration cache: a reused entry
resolves nothing, and so verifies nothing. What a regeneration cannot know about this policy,
which it strips of comments on every write:

- The committed files are byte-identical to Gradle's output for the current graph, so a
  regeneration with no dependency change is a no-op; a diff is a change to review. Run it
  online: with `--offline`, Gradle 9.8.2 wrote SHA-256 pins for POMs whose cached signatures
  it did not consult. Regeneration never prunes a stale entry, and `--export-keys` writes
  every key Gradle met while verifying, referenced by a rule or not (a key in the keyring
  grants nothing; the trust is the metadata's), so an export after a prune drops the keys
  only the pruned entries used; prune by hand against a from-scratch regeneration, and
  confirm the keyring's key set afterwards.
- The unsigned `org.gradle.kotlin.kotlin-dsl` plugin marker is pinned by SHA-256 at the
  version the wrapper embeds (9.7.1 took 6.7.3, 9.8.x takes 6.7.6): a wrapper bump moves it,
  failing with "Dependency verification failed ... kotlin-dsl.gradle.plugin-<v>.pom", so
  replace that pin in the commit that bumps `distributionSha256Sum`.
- `org.gradle:github-dependency-graph-gradle-plugin` is resolved only by the
  dependency-submission workflow's init script, which no command-line regeneration sees:
  keep its `trusted-key` rule and its key in the keyring, and validate a change with the
  strict dependency-graph generation that workflow runs as well as with `check`.
- `org.jetbrains.kotlinx` is trusted group-wide on purpose: `kotlin-gradle-plugin` floats to
  new patch releases, and a cold cache fetches BOM and parent POMs that a warm-cache
  regeneration narrows away. Publisher keys are version-free for the same reason, so a
  `dependency-analysis-gradle-plugin` bump that moves `caffeine` or `jspecify` needs no edit.
- The keys in [gradle/verification/version-scoped-keys.txt](gradle/verification/version-scoped-keys.txt)
  are trusted only at the exact versions in the policy, each for the reason the file gives:
  expired keys, DSA-1024 keys and retired release managers' keys that signed old releases in
  the graph and must not vouch for new ones. A regeneration that widens one of them, or that
  narrows a publisher key to the one version it saw, is a change to refuse. The file's header
  names the gson key, trusted for its group on gradle/gradle's corroboration alone.
- IntelliJ's sync, with download sources on, resolves artifacts no command-line build does:
  the Gradle sources zip, which Gradle Inc.'s publishing key covers, Groovy's sources under
  its group key, and the jmh plugin's unsigned sources jar, pinned with the plugin.

The root build constrains Kotlin's isolated Bouncy Castle publishing-helper dependencies
to at least 1.86, addressing [the name-constraints bypass](https://github.com/advisories/GHSA-9pwp-9qqc-pr26)
and [the lazy ASN.1 depth-limit bypass](https://github.com/advisories/GHSA-qp49-qgx5-5m26).
These constraints preserve Kotlin's default helper dependencies and do not add dependencies
to the published convention plugins. Remove them when Gradle's bundled Kotlin tooling
supplies Bouncy Castle 1.86 or newer.

The separate [Kotlin cache advisory](https://github.com/advisories/GHSA-r937-wjx7-w2jp)
affects KAPT's incremental-cache deserialization, as shown by the
[upstream fix](https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6).
This build does not apply KAPT or configure annotation processors. Keep the alert open
pending patched tooling compatible with Gradle's `kotlin-dsl` plugin; overriding Kotlin
independently leaves Gradle's expected tooling combination. Reassess this exposure if
KAPT is introduced or the Kotlin tooling changes.

### Local adoption and releasing

The release proof for `sava-build` combines deliberate local adoption passes with the
plugin's own functional tests. Across a release, consumer passes can exercise real baselines,
timeout audits, configuration-cache graphs, fuzz targets, and repository-specific
conventions. A clean exact-byte hardening certification proves that graph passed; it does not by itself
prove that consumer exercised the behavior changed in this release. Re-running every
historical checkout as one final fleet is an
optional diagnostic, not a tag or publication prerequisite.

Consumers validate an unreleased plugin through the committed `-PsavaBuildLocalRepo`
support in their settings: publish with
`./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository`, point the property at
`build/sava-test-repo` (with `--dependency-verification lenient` in a consumer that verifies
dependencies; see Developing sava-build), and confirm the build's own notice names the expected
source snapshot at publication, tree state, and application-time SHA-256. Consumer project receipts
(`.pitest-history/pitest-certification.tsv`) and a successful `:hardeningCertifyAll`
Gradle-root manifest (`.pitest-history/pitest-certification-all.tsv`) remain machine-local
hardening evidence for their own repositories; a release does not collect them. The root
manifest hashes the strict child receipts published by that invocation and lists their exact
project/suite inventory. It verifies child task outcomes and receipt/session identity and
rehashes the receipt files around atomic manifest replacement. It does not recapture a common
source state across projects and does not claim independent or included builds elsewhere in
the repository. A recorded PIT, PIT-JUnit, or ArcMutate activation/base mismatch is discovered
first by the aggregate's read-only transition preflight. Its single report is an expected
candidate-adoption stop, not permission to ignore certification: review the named history-free
observations, run the exact listed `pitest<Suite>BaselineRebase` writers, review and commit
those transition records, then rerun the aggregate and fuzzing before declaring adoption
clean. Apply the normal
`software.sava.build` settings entry point when an empty root must still expose the aggregate
anchor under configuration-on-demand; standalone hardening-only builds should run the
aggregate with `--no-configure-on-demand`. Treat the aggregate's selection,
transition-preflight, and completion tasks as internal implementation boundaries.

Releasing is Release Please plus the ordinary check:

1. Land changes on `main`; a green `Gradle Check` lets Release Please open or refresh its
   draft release pull request (`always-bump-patch`), once the commit guard below passes.
2. Review the CHANGELOG, mark the pull request ready, and squash-merge it. While the
   commit guard is red the pull request is not refreshed: restate the flagged commit first,
   then merge, or the commits pushed since the run went red are missing from the notes.
3. The merge is tagged by Release Please, and the tag-triggered workflow re-runs `check`
   without the Gradle build cache in a separate workflow that holds no publishing secret,
   then, in a job that runs no tests and uses no Gradle cache, publishes to GitHub Packages
   and attests build provenance with GitHub's native `actions/attest` — verifiable by
   consumers through the `software.sava.build.check.attestations` feature or
   `gh attestation verify`.

Release Please reads commits through the GitHub API and drops, at debug level, every
message its parser cannot read, `BREAKING CHANGE` footer included, while the run stays
green: sava 1d846b0 was released without its breaking note because a body line beginning
with `Arrays.compare(a.toByteArray(), b.toByteArray())` was read as a `type(scope)`
header. So the reusable [release-please.yml](.github/workflows/release-please.yml) first
checks out the branch with full history, sets up the repository's JDK, and runs
[release-please-guard](.github/actions/release-please-guard): a Java program the action
compiles there, since a composite action runs without a build, which rules out Kotlin and
the plugin jar. It parses every commit since the manifest version's tag (on a release-merge
run, before that tag exists, since the nearest earlier tag in the configured spelling) with
a port of the grammar Release Please uses (`@conventional-commits/parser` 0.4.1, as
release-please 17.6, the version `release-please-action` bundles, drives it; bump the port
with the action, against the golden corpus under `src/guardTest/resources`, which was
recorded from the JavaScript original over every commit message of the sava repositories),
split the way Release Please splits a squashed message, and fails the run naming any commit
whose header line parses but whose message does not, with the parser's own position.
Messages whose header is not a conventional commit (merges, imports, git's default
`Revert "..."` subject) are notices only, since Release Please ignores them by design; a
commit that `exclude-paths` keeps out of the notes, an empty commit included, is skipped as
Release Please skips it. The remedy for a flagged commit already on `main` is a commit that
restates its header, body and footers in parseable form and ends with the footer
`Restates: <sha>` (an empty commit, unless `exclude-paths` is configured, which excludes
empty commits); the guard then reports the original as a notice. The guard parses no more
than 2,000 lines of body text (Release Please's parser fails at a few thousand), in a message
or in a footer Release Please re-reads as a commit; past that it refuses the commit, since
Release Please may read on, a `Release-As` footer included: a commit pushed directly takes
the `Restates` remedy, one a pull request brought needs a shorter message in that pull
request's `BEGIN_COMMIT_OVERRIDE` block instead. The guard reads each commit
as Release Please reads it: with the pull request the commit belongs to (asked of GitHub in
one GraphQL query per hundred commits), whose `BEGIN_COMMIT_OVERRIDE` block, when its body
carries one, replaces the commit message.

A `Release-As` footer sets the next version outright, and Release Please reads it from any
commit since the last release, a squash-merged pull request's included, from a pull
request's commit override as much as from the commit itself, and from a footer's
continuation line as much as from a footer of its own, taking the newest. The guard finds
the footer Release Please would take (the config's `release-as` wins over every footer) in
the commits as Release Please reads them: a commit pushed to the branch directly sets the
version, and one that belongs to a pull request fails the run, since a pull request may not
choose the version. The remedy is a newer commit pushed directly with the intended footer
(an empty commit will do, unless `exclude-paths` is configured, which excludes empty
commits), or `release-as` in the config. A footer that is not a plain `X.Y.Z` version, or
one Release Please cannot read at all, is a warning before Release Please acts on it.

The guard also checks the release Release Please is about to cut from each release pull
request, since the publishing workflows refuse a tag that is not a plain `X.Y.Z` version.
Release Please tags a merged release pull request with the version in its title, unless its
body parses into more than one release section, when the body's version wins, and it reads
the body from a branch file instead when the body carries its overflow message; the sections
are HTML `details` elements found by an HTML parser, after a parse, serialize and parse again
that decodes escaped markup. Rather than predict that parse, the guard refuses a body whose
notes hold the markup a section is made of, as a tag or in any escaped spelling, or the
overflow message, and then requires the title's version (the body's single section's, for a
grouped title that names none) to be a plain `X.Y.Z` version, the one the pull request
wrote into the manifest at its own commit, and the one the commits give (those up to its merge,
under the config as of its merge, for a merged one): the config's
`release-as`, else the `Release-As` footer Release Please takes, else, under
`always-bump-patch`, the patch bump of the last release (under another versioning strategy,
or before a first release, that last part goes unchecked). A commit the guard would not read
to its end may carry the footer Release Please takes: one pushed directly leaves that part
unchecked, while a pull request's commit that carries or may carry it, with no footer the
guard did read deciding the version first, refuses the release, since a pull request may not
choose the version. A merged release pull request about to be tagged
fails the run; an open one, checked as if merged, is a warning, so Release Please keeps
running and can rewrite it once the source is fixed. A release pull request merged before
Release Please refreshed it for a newer footer or config is refused the same way, since its
title names a version its own commits do not give: remove the label, and Release Please
builds the next one afresh. A merged release pull request whose merge commit the checkout
does not have (the merge landed after the checkout fetched, or sits on no branch) is refused
too, since the commits it was built from cannot be read from there: run the workflow from the
branch's current head once it has the merge. A body that overflows GitHub's limit
is refused too, a legitimate one included; the remedy is to edit the merged pull request's
body and re-run. The `guardTest` suite builds scratch repositories for each of these rules
and stands in for the GitHub API with a local server; it runs under `check`.

Batch fixes and release on a cadence, not per fix: every release is re-adopted across
the fleet, so each one starts about ten adoption passes.

Report a plugin defect or a consumer workaround upstream, with the exact resolved plugin
JAR SHA-256 and the consumer's starting and final commits. A defect is a wrong or
irreproducible receipt, a refused build, lost data, a HARDENING.md promise contradicted
by behaviour, or a misread mutant. Everything else an adoption teaches — hazards, false
leads, local rules — stays in that repo's own notes; sava-build pulls from those notes
when a rule proves general, so an adoption pass owes no list of gaps.

The local fuzz runner remains available when a change warrants a cross-repository campaign:

```shell
tools/local-fuzz.sh --seconds 121 --parallel-targets 4 ../ravina
```

Scheduled GitHub fuzz workflows are likewise outside the plugin contract.
