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
sources require Java 17 or newer. Run `./gradlew hardeningInit`, complete its ownership
and baseline checklist, then run `./gradlew hardeningAgentTemplate` and copy the exact
agent-instructions block printed by the installed plugin before treating
`hardeningCertify` as a release gate. The task also prints the matching digest marker;
there is no Git-tag lookup and no dependency on the moving `main` documentation.

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
| `software.sava.build.feature.hardening` | Registers configured [PIT](https://pitest.org) mutation suites, [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz targets, baseline diagnostics and writers, release certification, and optional generated test support through `hardening {}`. It is package-agnostic and works with open-source PIT; an applicable ArcMutate licence is optional. See the [standalone example](#standalone-hardening-only-project), run `./gradlew hardeningHelp` for the installed version's tasks and options, and use [HARDENING.md](HARDENING.md) for policy. |
| `software.sava.build.modules.postgresql` | Opt-in [extra-java-module-info](https://github.com/gradlex-org/extra-java-module-info) patch converting the PostgreSQL JDBC driver into an explicit module (required for jlink). |
| `software.sava.build.modules.gcp-kms` | Opt-in module patches for the Google Cloud KMS client and its non-modular transitive dependencies. |
| `software.sava.build.base.dependency-rules` | Consistent resolution against the solana version catalog BOM. |
| `software.sava.build.base.version` | Sets the project version from `-Pversion`. |
| `software.sava.build.feature.compile` | Java toolchain from `javaVersion` / `javaVendor`. |
| `software.sava.build.feature.test` | JUnit test logging and strict test-dependency analysis. |
| `software.sava.build.check.attestations` | `verifySavaAttestations` task: verifies the GitHub build-provenance attestations of resolved sava dependencies (sha256 lookup in the org attestation store, cosign verification against the reusable publish workflow's identity), plus their sources/javadoc jars and the sava-build plugin jar in use (attested by `gradle_plugin_publish.yml`). Missing attestations warn until `savaAttestations.requireAttestations = true`; failed verifications always fail. Configure via `savaAttestations {}`; needs a `cosign` executable or a Docker image passed as `-PsavaCosignImage=...`. Applied by `java-module`; not part of `check` (requires network). |
| `software.sava.build.feature.javadoc` | Lenient javadoc (`Xdoclint:none`, HTML5). |
| `software.sava.build.check.dependencies` | [Dependency analysis](https://github.com/autonomousapps/dependency-analysis-gradle-plugin) and module-directive scope checks wired into `check`. |

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

Every build that resolves plugins from the local repo also says so, once, at the end:

```
sava-build: this build resolved every 'software.sava.build*' plugin to 0.0.0-test from
the local repo /…/build/sava-test-repo (last publish 3 min ago), NOT the versions in
the plugins block.
```

That notice comes from the plugin itself (`SavaBuildLocalRepoNoticePlugin`, applied by
`software.sava.build`), as a dataflow action rather than a line in the consumer's
settings script — a settings script is skipped on a configuration cache hit, which
silenced the warning in exactly the cases worth warning about: a forgotten publish
changes nothing, so the entry is reused, and switching back into local-repo mode reuses
an existing entry too. The publish age is read when the action runs, so it is never a
value cached from an earlier build.

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

### Pre-release fleet certification

This section is the detailed operational contract for releasing `sava-build` itself;
[HARDENING.md](HARDENING.md) states the portable evidence policy used by consumers.

The quick canary stays useful during development:

```shell
tools/fleet-canary.sh                     # locally available manifest siblings
tools/fleet-canary.sh --deep              # also run annotated mutation cycles
tools/fleet-canary.sh ../sava ../ravina:pitestCalls
```

Those ordinary modes intentionally skip unavailable manifest siblings and permit an
explicit subset. They are observations, not release certification. On a clean candidate
commit with every manifest repo checked out as a sibling, run the strict forms instead:

```shell
tools/fleet-canary.sh --release
tools/fleet-canary.sh --verify-receipt build/hardening/fleet-canary-receipt.json

tools/local-fuzz.sh --release --seconds 900
tools/local-fuzz.sh --verify-receipt build/hardening/local-fuzz-receipt.json
```

`--release` preflights the entire roster before publishing the test plugin and refuses a
missing repo, mismatched GitHub remote, or dirty plugin or consumer worktree (linked
worktrees included). Starting a run immediately changes the canonical receipt to an
`in_progress` pointer, invalidating an older pass. A completed pointer names an immutable
`build/hardening/<name>-runs/run.*/` bundle containing the machine-readable receipt,
preflight inventory, plugin-publish log, one log per consumer, and copies of the inner
`pitest-certification.tsv` or `local-fuzz.tsv` evidence, plus the exact published
`0.0.0-test` plugin JAR. The receipt binds and hashes those files together with the plugin
commit/tree/origin, manifest digest, each consumer's commit/origin, exact tasks, and the
fuzz budget. Every inner receipt's loaded-plugin hash must equal the retained JAR hash, so
all consumers resolving the same stale binary cannot agree their way to green. Keep the selected run directories with
the release record, but do not commit them into the tree they certify.

The build-free verification commands rehash every retained file and, when a recorded
checkout is still available, require its current commit, origin, and clean state to match.
They report the exact number of consumer checkouts revalidated and refuse full verification
when that number is zero; unavailable individual checkouts remain named skips whose retained
artifacts are still checked.
They accept only subsequent Release Please changes to `CHANGELOG.md` and
`.release-please-manifest.json`; re-run both certifications after any other candidate,
fixture, workflow, or policy change.

The functional tests exercise synthetic fixtures; real baselines, audited timeout sets,
README causes, task registration, and settings snippets have historically supplied a
new post-release surprise one repo at a time. The canary publishes `0.0.0-test` and,
under `-PsavaBuildLocalRepo`, runs lightweight debt and template checks in ordinary
mode. Release mode requires every consumer's `hardeningCertify`, which freshly executes
all registered mutation suites and writes provenance-bound per-project evidence; the
retained certifications must exactly cover every project that exposed that task. Both
release runners use `--configuration-cache` for discovery and execution, making cache
serialization part of the real-consumer canary rather than only a synthetic fixture test.
`--deep` remains an explicit repeated diagnostic, not a release soak or prerequisite.
The separate local fuzz pass uses the plugin's `fuzzAll` aggregate; release mode requires
both that aggregate and a nonempty registered target set in every consumer. Only ordinary
diagnostic mode may fall back to discovered `fuzz<Target>` tasks (or `help` for a targetless
repo). `--continue` lets independent targets finish after one fails.
Neither release check trusts a merely green consumer build: each requires the
`0.0.0-test` resolution notice, so an obsolete settings snippet cannot silently test
the published plugin instead.

Hardening advisories are evidence to review, not text to lose in a green log. Strict
canary mode therefore fails when it sees one; after inspecting every reprinted payload,
rerun with `--allow-advisories` to record that acknowledgement. A stale agent-template
marker is expected while testing an unreleased digest, for example, but it still belongs
in the release record. Any consumer shape the fixtures missed earns a focused functional
test before release.

The roster is [tools/fleet-manifest.txt](tools/fleet-manifest.txt). Scheduled GitHub fuzz
workflows are outside the plugin contract; release evidence comes from the explicit
local fuzz command and receipt, not from waiting for a schedule or soak window.

The full receipts remain machine-local, but certification is now observable on the tag and
publish paths. After Release Please has prepared the version metadata, check out that clean
release branch and create the compact, versioned owner attestation from the two canonical
pointers:

```shell
version=$(jq -r '.["."]' .release-please-manifest.json)
tools/release-attestation.sh create "$version"
git add "release-attestations/$version.json"
```

Commit only that generated file alongside Release Please's `CHANGELOG.md` and manifest
changes. It contains candidate identity and receipt hashes, not the receipt bundles,
absolute paths, or credentials. The full immutable bundles still need to be retained with
the release record. From that clean release commit, exercise the same production gate the
tag workflow will call before allowing Release Please to proceed:

```shell
tools/release-attestation.sh verify "$version"
tools/release-attestation.sh verify-pending-release
```

The script's hermetic self-test drives successful and failing `create`, `verify`,
`verify-pending-release`, and `verify-tag` paths; this clean-checkout invocation is the
real repository rehearsal and remains a release blocker until the fleet and fuzz receipts
exist. The Release Please workflow refuses to create a pending version's tag
without this committed attestation, and the tag-triggered publish workflow verifies it
again against the exact tag checkout. Any source, fixture, workflow, policy, or fleet-roster
change after certification invalidates the record and requires both strict runs again.
