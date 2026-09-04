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
exact bounded agent-instructions block printed by the installed plugin before treating
`hardeningCertify` as a release gate. The task also prints the matching digest marker;
there is no Git-tag lookup and no dependency on the moving `main` documentation. On a
later plugin upgrade, run the corresponding project-qualified
`hardeningAgentTemplateDiff`; it prints a read-only textual diff from that bounded local
block to the newly installed template. An unqualified task name can select every
hardening project in a multi-project build and duplicate the output.

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

### Upgrading across hardening template versions

`agentsTemplateInSync` checks the root `AGENTS.md` acknowledgment of the installed
agent-instructions template and is used by `check` and `qualityGate`. Treat the installed
plugin as the task authority: on an upgrade, run the applying project's `hardeningHelp`
(for example, `./gradlew :module:hardeningHelp`), then its project-qualified
`hardeningAgentTemplate` and `hardeningAgentTemplateDiff`. Review or act on the bounded
diff before moving the digest marker, and move the marker in the same commit as the
version pin. In a multi-project build, keep all three task names project-qualified so
one chosen owner reports the installed version's guidance. Template synchronization is
deliberately structural: the plugin does not attempt to judge arbitrary repository prose.

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
| `software.sava.build.feature.jlink` | jlink images built by invoking the toolchain JDK's `jlink` directly, with service binding and unsigned-jar tolerance. Configured via `jlinkApplication {}`; adds `image`, `imageRun`, and `imageModules` tasks with output under `build/images/<applicationName>`. |
| `software.sava.build.feature.publish` | Maven publishing with sources/javadoc jars, POM metadata from [sava.properties](#gradlesavaproperties), optional GPG signing, and the `savaGithubPackagesPublish` repository. Applied by `java-module`. |
| `software.sava.build.feature.publish-maven-central` | Maven Central publishing for the `:aggregation` project: stages, bundles (`zipCentralPortalDeployment`), and uploads (`publishCentralPortalDeployment`) deployments straight to the [Central Portal API](https://central.sonatype.org/publish/publish-portal-api/). The `nmcpAggregation` configuration and `publishAggregationToCentralPortal` task from the retired [nmcp](https://github.com/GradleUp/nmcp) plugin remain as deprecated aliases. |
| `software.sava.build.feature.jmh` | [JMH](https://github.com/melix/jmh-gradle-plugin) benchmarking conventions for standalone benchmark builds: quick-look run defaults (1 fork, 5×1s warmup, 8×1s measurement, fail-on-error), a `jmh` task that is never skipped as `UP-TO-DATE`, per-run results archived timestamped under `<project>/jmh-results/` — outside `build/`, so `clean` keeps measurement history — with `results.txt` re-rendered after each run as the newest-wins merge of all archived runs (subset runs converge on a full scoreboard; delete archive files to drop stale rows), and service-replicating JVM flags (compact object headers, generational ZGC, pinned pre-touched 2g heap, `-XX:+PerfDisableSharedMem`) — override wholesale with `jmh { jvmArgsAppend.set(...) }`. Every default is overridable per invocation: `-PjmhFork`, `-PjmhIncludes=<regex>[,...]`, `-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`, `-PjmhTimeOnIteration`, `-PjmhFailOnError`, and `-PjmhJvmArgsAppend="<flag> <flag>..."` (replaces the service flag list wholesale). Decision-grade comparisons need 3+ forks and isolation from other load. Leaves the toolchain to the consuming build (benchmark harnesses often pin bespoke JDKs). |
| `software.sava.build.feature.hardening` | Registers configured [PIT](https://pitest.org) mutation suites, [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets, baseline diagnostics and writers, release certification, and optional generated test support through `hardening {}`. It is package-agnostic and works with open-source PIT; an applicable ArcMutate licence is optional. See the [standalone example](#standalone-hardening-only-project), run `./gradlew :module:hardeningHelp` for the installed version's tasks and options (or `./gradlew :hardeningHelp` when the root project owns the plugin), and use [HARDENING.md](HARDENING.md) for policy. |
| `software.sava.build.modules.postgresql` | Opt-in [extra-java-module-info](https://github.com/gradlex-org/extra-java-module-info) patch converting the PostgreSQL JDBC driver into an explicit module (required for jlink). |
| `software.sava.build.modules.gcp-kms` | Opt-in module patches for the Google Cloud KMS client and its non-modular transitive dependencies. |
| `software.sava.build.base.dependency-rules` | Consistent resolution against the solana version catalog BOM. |
| `software.sava.build.base.version` | Sets the project version from `-Pversion`. |
| `software.sava.build.feature.compile` | Java toolchain from `javaVersion` / `javaVendor`. |
| `software.sava.build.feature.test` | JUnit test logging and strict test-dependency analysis. |
| `software.sava.build.check.attestations` | `verifySavaAttestations` task: verifies the GitHub build-provenance attestations of resolved sava dependencies (sha256 lookup in the org attestation store, cosign verification against the reusable publish workflow's identity), plus their sources/javadoc jars and the sava-build plugin jar in use (attested by `gradle_plugin_publish.yml`). Missing attestations warn until `savaAttestations.requireAttestations = true`; failed verifications always fail. Configure via `savaAttestations {}`; needs a `cosign` executable or a Docker image passed as `-PsavaCosignImage=...`. Applied by `java-module`; not part of `check` (requires network). |
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

To drive a plugin change from a consumer project, publish this checkout to its local
Maven test repo and point the consumer at it. **The publish is not automatic**, and a
forgotten one is invisible in the consumer's output — it just keeps resolving the
previously published jar — so chain the two rather than remembering the first:

```shell
(cd <sava-build> && ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository) \
  && ./gradlew check -PsavaBuildLocalRepo=<sava-build>/build/sava-test-repo
```

Run from the consumer's directory. A republished `0.0.0-test` is picked up immediately
(`file:` repositories are re-read on each resolution), so the chained form makes the
stale case unreachable instead of merely detectable. In a composite (a `jmh/` that
`includeBuild`s its root), prefer an absolute property value: a relative one is
resolved against each build's own settings dir, so the builds would read two
different repos — and the notice reports the dir the build that registered it
resolved, which need not be the one that served the plugins.

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
  // markers, which the test repo does not contain.
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

When done, drop the property; the unset path is the normal published resolution.

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
`build/sava-test-repo`, and confirm the build's own notice names the expected
source snapshot at publication, tree state, and application-time SHA-256. Consumer project receipts
(`.pitest-history/pitest-certification.tsv`) and a successful `:hardeningCertifyAll`
Gradle-root manifest (`.pitest-history/pitest-certification-all.tsv`) remain machine-local
hardening evidence for their own repositories; a release does not collect them. The root
manifest hashes the strict child receipts published by that invocation and lists their exact
project/suite inventory. It verifies child task outcomes and receipt/session identity and
rehashes the receipt files around atomic manifest replacement. It does not recapture a common
source state across projects and does not claim independent or included builds elsewhere in
the repository. Apply the normal
`software.sava.build` settings entry point when an empty root must still expose the aggregate
anchor under configuration-on-demand; standalone hardening-only builds should run the
aggregate with `--no-configure-on-demand`. Treat the aggregate's selection and completion
tasks as internal implementation boundaries.

Releasing is Release Please plus the ordinary check:

1. Land changes on `main`; a green `Gradle Check` lets Release Please open or refresh its
   draft release pull request (`always-bump-patch`).
2. Review the CHANGELOG, mark the pull request ready, and squash-merge it.
3. The merge is tagged by Release Please, and the tag-triggered workflow re-runs `check`
   without the Gradle build cache, publishes to GitHub Packages, and attests build
   provenance with GitHub's native `actions/attest` — verifiable by consumers through the
   `software.sava.build.check.attestations` feature or `gh attestation verify`.

Each adoption report has two upstream channels. First, report any plugin defect or
consumer workaround immediately. Second, batch reusable rules, hazards, tempting false leads,
process gaps, and paths the pass did not exercise at report-back, with the concrete evidence
that supports them and a proposed destination (normative template, main doctrine, casebook, or
repo-local notes). Always include the exact resolved plugin JAR SHA-256 and the consumer's
starting and final commits. This keeps non-defect learning from disappearing without turning
every observation into a shared-template revision.

The local fuzz runner remains available when a change warrants a cross-repository campaign:

```shell
tools/local-fuzz.sh --seconds 121 --parallel-targets 4 ../ravina
```

Scheduled GitHub fuzz workflows are likewise outside the plugin contract.
