# Changelog

## [21.5.14](https://github.com/sava-software/sava-build/compare/21.5.13...21.5.14) (2026-07-24)


### Features

* **hardening:** add comprehensive smoke tests for hardening plugin behaviors ([489b24a](https://github.com/sava-software/sava-build/commit/489b24a19129e98b897e001cc11806dc386901db))
* **hardening:** add unit tests for atomic writes and baseline parsing ([cdabf62](https://github.com/sava-software/sava-build/commit/cdabf62a7c210722b115ce21a5e75c346919a978))
* **publish:** add retryDelayMillis support for configurable retries ([8646d14](https://github.com/sava-software/sava-build/commit/8646d146d4dba54836d2b5912e7aa25c1b710ddd))

## [21.5.13](https://github.com/sava-software/sava-build/compare/21.5.12...21.5.13) (2026-07-24)


### Features

* **hardening:** add smoke test for detected-sibling hint in unkilled listing ([f9ca45a](https://github.com/sava-software/sava-build/commit/f9ca45a9f95f5d2b54f2fd48a47adff379627aee))
* **hardening:** warn on unresolved labels in baseline and debt listing ([77ff89b](https://github.com/sava-software/sava-build/commit/77ff89b254c4ed76100c469c4f9f2f6e3bfd3105))


### Bug Fixes

* **hardening:** read configured classpath to fix inconsistent recompile failures ([e7356ac](https://github.com/sava-software/sava-build/commit/e7356acdc03f07fdbe0cb14f6fbc9844f601077a))

## [21.5.12](https://github.com/sava-software/sava-build/compare/21.5.11...21.5.12) (2026-07-24)


### Features

* **hardening:** centralize and streamline baseline note parsing ([91f0b20](https://github.com/sava-software/sava-build/commit/91f0b20686233ba9d510d1437a59e0836431721e))
* **hardening:** introduce atomic baseline writes to prevent corruption ([9186caa](https://github.com/sava-software/sava-build/commit/9186caae965b3c3eef23f1902ecb4fad79f82026))

## [21.5.11](https://github.com/sava-software/sava-build/compare/21.5.10...21.5.11) (2026-07-24)


### Features

* **hardening:** carry notes across line shifts, drop orphaned ones ([469740c](https://github.com/sava-software/sava-build/commit/469740c6ea2cf7913659b21f07f1df14e4fed985))
* **hardening:** enforce maxLen check on corpus seeds, prevent truncation ([418eadc](https://github.com/sava-software/sava-build/commit/418eadcc232935f8323683bef92a33ae5438b571))


### Build System

* **deps:** bump actions/checkout from 7.0.0 to 7.0.1 ([#64](https://github.com/sava-software/sava-build/issues/64)) ([421b207](https://github.com/sava-software/sava-build/commit/421b2074861b530d576fccea84fbabaf12dbb37b))

## [21.5.10](https://github.com/sava-software/sava-build/compare/21.5.9...21.5.10) (2026-07-23)


### Features

* **hardening:** note-carrying refresh, prune flag, surfaced-sibling classifier, corpus minimize ([4b6decc](https://github.com/sava-software/sava-build/commit/4b6deccf02a920ab18ca6c5fee271cbe4eb773b3))

## [21.5.9](https://github.com/sava-software/sava-build/compare/21.5.8...21.5.9) (2026-07-23)


### Features

* **hardening:** Add repetive tasks needed for adopting the hardening process. ([767b5d5](https://github.com/sava-software/sava-build/commit/767b5d5e15f511e1fc2b6f1e1d3545eb10f0da0e))
* **hardening:** improve tracking status flips. ([2607369](https://github.com/sava-software/sava-build/commit/2607369586e49248b9a2bcf5e012bc1fa06a47b6))
* **hardening:** Knobs to tune timeouts. Improve context. ([63d0f95](https://github.com/sava-software/sava-build/commit/63d0f95659b4162944bd2b0a9bc7278a3d024e3d))


### Bug Fixes

* **hardening:** close minion filters even when a pitest run fails ([d8c269d](https://github.com/sava-software/sava-build/commit/d8c269dd1586a37e740d57a2a890aff0a55f6a5c))
* **hardening:** collapse repeated minion log lines on stderr ([ad5d8af](https://github.com/sava-software/sava-build/commit/ad5d8af64b398827fa1a499fd2dcf75652b2c30d))


### Documentation

* **hardening:** add six casebook entries from the http-servers adoption ([a6de477](https://github.com/sava-software/sava-build/commit/a6de47793624edf1730da649f4c0063a225c60fd))
* **hardening:** Add strategy for when a full sweep is not feasable. ([11180bd](https://github.com/sava-software/sava-build/commit/11180bd690a036539a9b53a697d5ad048d67dbe3))
* **hardening:** scope the "never settles" doctrine to live causes ([d4833a9](https://github.com/sava-software/sava-build/commit/d4833a992cb3bdff1ab36854058f8bbcfc8d58bd))

## [21.5.8](https://github.com/sava-software/sava-build/compare/21.5.7...21.5.8) (2026-07-21)


### Build System

* **hardening:** Fail downstream builds if an agent does not sync AGENTS.md template with HARDENING.md updates. ([bb108bc](https://github.com/sava-software/sava-build/commit/bb108bca90646971b8303437d267efdf1794a268))


### Documentation

* **hardening:** Clarify when to run the full quality gate. ([9427b44](https://github.com/sava-software/sava-build/commit/9427b442a10723b6fcb8773d6d29409ec7f42e94))
* **hardening:** General improvements to the process. ([504369a](https://github.com/sava-software/sava-build/commit/504369ad582ebe27078cb4d8f6d44d5ef86d3b7e))
* **hardening:** Prepare for arcmutate incremental analysis, unlocked with OSS license. ([bf41545](https://github.com/sava-software/sava-build/commit/bf41545199ea26b7e7c0a27b1a96f56ab9ec89f0))
* **hardening:** record traps found in a full adoption pass ([d487796](https://github.com/sava-software/sava-build/commit/d4877963ae7927a25d9cfea40e92c39ab82faf6c))
* **hardening:** Separate the rules from the cases that lead to them to keep the HARDENING.md context lean. ([e0b15b2](https://github.com/sava-software/sava-build/commit/e0b15b2f4b53e025c008a1a73c7d55fd79d83f51))

## [21.5.7](https://github.com/sava-software/sava-build/compare/21.5.6...21.5.7) (2026-07-20)


### Bug Fixes

* **hardening:** Update pitest version to 1.25.8 ([b5eb31e](https://github.com/sava-software/sava-build/commit/b5eb31ecc15b245155a56ca14dc2141234795280))

## [21.5.6](https://github.com/sava-software/sava-build/compare/21.5.5...21.5.6) (2026-07-18)


### Features

* **build:** add qualityGate task and mutation-baseline ratchet ([a0a3927](https://github.com/sava-software/sava-build/commit/a0a39276a7526062400048ff7d73b691223729b9))


### Build System

* **deps:** bump actions/attest from 4.1.1 to 4.2.0 ([#58](https://github.com/sava-software/sava-build/issues/58)) ([f983cf7](https://github.com/sava-software/sava-build/commit/f983cf74be710d150cdf3761879eb5991706698c))
* **deps:** bump com.autonomousapps:dependency-analysis-gradle-plugin ([#56](https://github.com/sava-software/sava-build/issues/56)) ([ca13a2f](https://github.com/sava-software/sava-build/commit/ca13a2f0fe08371ae8a6ccf7cf058b35b08a3ac2))

## [21.5.5](https://github.com/sava-software/sava-build/compare/21.5.4...21.5.5) (2026-07-17)


### Features

* **build:** add support for excluded classes in PIT mutation testing ([e89b69f](https://github.com/sava-software/sava-build/commit/e89b69fd8edaac90d2155fb9c72fbf51a950da44))

## [21.5.4](https://github.com/sava-software/sava-build/compare/21.5.3...21.5.4) (2026-07-16)


### Features

* **build:** improve resource handling for PIT and Jazzer tasks ([146e54d](https://github.com/sava-software/sava-build/commit/146e54d033e1d1dcfe06e168b5c9099dee0180b8))

## [21.5.3](https://github.com/sava-software/sava-build/compare/21.5.2...21.5.3) (2026-07-16)


### Features

* **build:** add hardening plugin for mutation testing and fuzzing ([e33fbf4](https://github.com/sava-software/sava-build/commit/e33fbf472058d3ff5d56a343412e4122cdae5233))
* **build:** enhance fuzz testing with maxLen and JVM args support ([7f67935](https://github.com/sava-software/sava-build/commit/7f67935cb4cd8fb924dc6d1770b246f79df68589))
* **build:** improve JMH result parsing to support @Param variants ([b3af996](https://github.com/sava-software/sava-build/commit/b3af996e7bfda4f7c4876c67d2c9af32043720d8))


### Build System

* **deps:** bump org.junit.jupiter:junit-jupiter from 6.1.1 to 6.1.2 ([#54](https://github.com/sava-software/sava-build/issues/54)) ([919b01a](https://github.com/sava-software/sava-build/commit/919b01a45138ed9e1725fd9581a5d49dd44db58d))

## [21.5.2](https://github.com/sava-software/sava-build/compare/21.5.1...21.5.2) (2026-07-12)


### Features

* **build:** add JMH benchmarking plugin and smoke tests ([900d730](https://github.com/sava-software/sava-build/commit/900d730e5e9f344cf625da45c43c16fb611817ef))


### Chores

* **build:** update verification metadata for jmh plugin sources jar ([2b7df49](https://github.com/sava-software/sava-build/commit/2b7df49ca5eb56b2cd39a81908a7678fd8ae02d6))

## [21.5.1](https://github.com/sava-software/sava-build/compare/21.5.0...21.5.1) (2026-07-11)


### ⚠ BREAKING CHANGES

* **build:** The `com.gradleup.nmcp` plugin and its aggregation tasks are no longer supported. Consumers must migrate to the in-house Central Portal deployment pipeline tasks.
* **attestations:** Consumers must adopt the extended `software.sava.build.check.attestations` plugin configuration for verifying sava-build plugin jars and documentation artifacts. Use the new `verifyBuildPlugin` and `verifyDocumentation` options to enable or customize behavior.
* **build:** Projects using the `java-module` plugin will now include `software.sava.build.check.attestations` by default. Missing attestations warn unless explicitly required with `savaAttestations.requireAttestations = true`. Configure cosign using Gradle properties or Docker image.

### Features

* **attestations:** add support for plugin and documentation jar verification ([d8bfda0](https://github.com/sava-software/sava-build/commit/d8bfda00d141e4dbaf9bc2ebe0a3291b70439412))
* **build:** add `verifySavaAttestations` task to validate artifact provenance ([562e916](https://github.com/sava-software/sava-build/commit/562e916bee5839bbe2ac4fc266078d8997e2452f))
* **build:** add PGP keyring for artifact signature verification ([1f8e390](https://github.com/sava-software/sava-build/commit/1f8e39007e1c8f6a7cedfb5bffd9a576337950f8))
* **build:** remove nmcp pipeline and references across build scripts ([0cbab81](https://github.com/sava-software/sava-build/commit/0cbab81cfed928bae3eff59fe87fef60ef343841))
* **build:** trim checksum files to meet Central's publishing limits ([9945420](https://github.com/sava-software/sava-build/commit/9945420ab2357182c63ac53516c14e45ca8cb636))


### Chores

* add gradle/verification-keyring.gpg to gitignore ([17b5f16](https://github.com/sava-software/sava-build/commit/17b5f162972b5a9d87e56e17e7bfb7443aca3496))

## [21.5.0](https://github.com/sava-software/sava-build/compare/21.4.3...21.5.0) (2026-07-10)


### ⚠ BREAKING CHANGES

* **build:** The `com.gradleup.nmcp` plugin has been replaced by a custom implementation. Consumer projects must update to `publishCentralPortalDeployment` and `releaseCentralPortalDeployment` tasks.
* **jlink:** The `com.github.iherasymenko.jlink` plugin has been removed. Consumer projects must migrate to the new `software.sava.build.feature.jlink`. No build script changes are required if using the `jlinkApplication` block.

### Features

* **build:** replace nmcp pipeline with custom Central Portal deployment ([ff5abd0](https://github.com/sava-software/sava-build/commit/ff5abd0ff53b051bded7bc399ec42df220d5a826))
* **jlink:** replace deprecated plugin with in-house implementation ([289b614](https://github.com/sava-software/sava-build/commit/289b614efeaa40e31f79d511db2e6b7bc1b6e258))


### Build System

* **deps:** bump org.junit.jupiter:junit-jupiter from 5.13.4 to 6.1.1 ([#51](https://github.com/sava-software/sava-build/issues/51)) ([2e13e79](https://github.com/sava-software/sava-build/commit/2e13e794640697ba9ec2078345d1de6126cd5fa3))


### Chores

* release 21.5.0 ([a5d932c](https://github.com/sava-software/sava-build/commit/a5d932c60604e0ca84a3145174e3327e83e2ff9c))


### Documentation

* **build:** clarify requirements for JDK provisioning plugin ([21a3a22](https://github.com/sava-software/sava-build/commit/21a3a22c5c902427fd754b1e904cc827c8dcf700))

## [21.4.3](https://github.com/sava-software/sava-build/compare/21.4.2...21.4.3) (2026-07-09)


### ⚠ BREAKING CHANGES

* **jdk-provisioning:** Consumer projects must update to `software.sava.build.feature.jdk-provisioning`. The alias plugin is deprecated and will be removed in a future version.

### Features

* **jdk-provisioning:** add build plugins, tests, and deprecate alias ([70195e4](https://github.com/sava-software/sava-build/commit/70195e4fb70c0c0cbe1b1257a2d0bbb8087d4f2a))


### Bug Fixes

* **build:** simplify GitHub Packages credential resolution ([11ffbe1](https://github.com/sava-software/sava-build/commit/11ffbe1d54a38fccb49008a4b993b6ba3e109c99))


### Build System

* **deps:** bump actions/attest from 4.1.0 to 4.1.1 ([#38](https://github.com/sava-software/sava-build/issues/38)) ([4b383be](https://github.com/sava-software/sava-build/commit/4b383bef6fa403377d4a61909b216eee19e7cfd1))
* **deps:** bump com.autonomousapps:dependency-analysis-gradle-plugin ([#37](https://github.com/sava-software/sava-build/issues/37)) ([954aa9f](https://github.com/sava-software/sava-build/commit/954aa9fda5525b2e0801c7b95df22e9bd9e0efcf))
* **deps:** bump com.autonomousapps:dependency-analysis-gradle-plugin ([#48](https://github.com/sava-software/sava-build/issues/48)) ([2944c17](https://github.com/sava-software/sava-build/commit/2944c178a7bf6599633483a49a666b8410c66d9f))
* **deps:** bump com.gradle:develocity-gradle-plugin ([#41](https://github.com/sava-software/sava-build/issues/41)) ([c2d9ec0](https://github.com/sava-software/sava-build/commit/c2d9ec05e4b27c1c3acacb8b76f63f76eaabdd8e))
* **deps:** bump docker/build-push-action ([#45](https://github.com/sava-software/sava-build/issues/45)) ([6d7462b](https://github.com/sava-software/sava-build/commit/6d7462b3108e551b0e9e02abb2e94632ca266fee))
* **deps:** bump docker/login-action in /.github/actions/docker-setup ([#47](https://github.com/sava-software/sava-build/issues/47)) ([8bf6f5f](https://github.com/sava-software/sava-build/commit/8bf6f5fdbd43c1486fc08a1bf9ab9f637578b705))
* **deps:** bump docker/metadata-action ([#44](https://github.com/sava-software/sava-build/issues/44)) ([3e99b6e](https://github.com/sava-software/sava-build/commit/3e99b6e1563c76eb3ca1e7fa4d15841910f4da5d))
* **deps:** bump docker/setup-buildx-action ([#46](https://github.com/sava-software/sava-build/issues/46)) ([c732b14](https://github.com/sava-software/sava-build/commit/c732b14ea78d79b570cd237e0ef017cb49b37bfc))
* **deps:** bump docker/setup-qemu-action ([#49](https://github.com/sava-software/sava-build/issues/49)) ([92f2c3e](https://github.com/sava-software/sava-build/commit/92f2c3eee79b2500b39f81d59acb4c64026a6f3f))
* **deps:** bump org.gradlex:extra-java-module-info from 1.14 to 1.14.2 ([#36](https://github.com/sava-software/sava-build/issues/36)) ([d82a82e](https://github.com/sava-software/sava-build/commit/d82a82e4fc1a2a95724d722596f0ad9c90e0b845))
* **deps:** bump org.gradlex:java-module-dependencies ([#43](https://github.com/sava-software/sava-build/issues/43)) ([7cbcfa7](https://github.com/sava-software/sava-build/commit/7cbcfa70317661dc325b8d1384fade1bd530ad9d))


### Chores

* **build:** update Gradle wrapper to v9.6.1 ([22e252c](https://github.com/sava-software/sava-build/commit/22e252cd53c52c99bf4eeb802d203aa0a159c4e9))

## [21.4.2](https://github.com/sava-software/sava-build/compare/21.4.1...21.4.2) (2026-06-22)


### Bug Fixes

* **build:** add trusted artifact regex for Gradle source distribution ([d3f2ad9](https://github.com/sava-software/sava-build/commit/d3f2ad98c107b49df5539c24a275ad521ab5d03f))


### Chores

* **build:** update trusted keys and cleanup verification metadata ([74e37d6](https://github.com/sava-software/sava-build/commit/74e37d6f52ab42293235e7b4fcfae39f48cb4cdf))

## [21.4.1](https://github.com/sava-software/sava-build/compare/21.4.0...21.4.1) (2026-06-22)


### Features

* **workflows:** add build provenance attestation to publish workflows ([4eb1b44](https://github.com/sava-software/sava-build/commit/4eb1b4404901fb014194e8f414295825f24cd875))
* **workflows:** make attest subject path configurable in publish-gh workflow ([ba42494](https://github.com/sava-software/sava-build/commit/ba42494dd755f0a83533a2896705386819652489))


### Build System

* **deps:** bump actions/attest-build-provenance from 2.4.0 to 4.1.0 ([#31](https://github.com/sava-software/sava-build/issues/31)) ([9b8fda6](https://github.com/sava-software/sava-build/commit/9b8fda647d4736f62ee4196e07a527e1f6b778e5))
* **deps:** bump actions/checkout from 6.0.3 to 7.0.0 ([#32](https://github.com/sava-software/sava-build/issues/32)) ([39ce6a5](https://github.com/sava-software/sava-build/commit/39ce6a5e123aed6fb9835c61a2c3e563c53e871b))
* **deps:** bump com.gradle:develocity-gradle-plugin ([#29](https://github.com/sava-software/sava-build/issues/29)) ([6c90fd1](https://github.com/sava-software/sava-build/commit/6c90fd1ad48ed9bee0cdd5d48e55758a8f742e9f))
* **deps:** bump org.gradlex:java-module-dependencies ([#34](https://github.com/sava-software/sava-build/issues/34)) ([8617f60](https://github.com/sava-software/sava-build/commit/8617f60adb6a6712c908c8c6fb053fbaf6594da6))


### Chores

* **build:** update Gradle wrapper to v9.6.0 and verification metadata ([4bd9b45](https://github.com/sava-software/sava-build/commit/4bd9b454615c6763cfdbf662e1851eba80aefae6))
* **build:** update trusted key metadata in verification file ([06487cc](https://github.com/sava-software/sava-build/commit/06487cc1a6eff9545b1d8f6b8dcc355018d83b5e))
* **workflows:** grant artifact-metadata permission and update attest action ([03ac143](https://github.com/sava-software/sava-build/commit/03ac143e6eb2909e08e84969b5d67ed1f965fa54))
* **workflows:** pin attest-build-provenance action version ([a7bdcc4](https://github.com/sava-software/sava-build/commit/a7bdcc420fb82cc1a456f52d6197a908981801ca))
* **workflows:** remove unused comment in attest-build-provenance steps ([ff579ad](https://github.com/sava-software/sava-build/commit/ff579ad8460c4e8e11564d7b9bf7e6a92fcdae14))

## [21.4.0](https://github.com/sava-software/sava-build/compare/21.3.15...21.4.0) (2026-06-18)


### Features

* **build:** add dependency verification metadata and checksum exclusion ([aa0827e](https://github.com/sava-software/sava-build/commit/aa0827e9f8bc1a0940441b86a082cdeedaa5a6d4))
* **workflows:** add publish workflow for GitHub Packages ([455066b](https://github.com/sava-software/sava-build/commit/455066b80cae77d618ecfceed10d5601e0986cb2))
* **workflows:** add secrets for publishing and extend repository list ([9d11eba](https://github.com/sava-software/sava-build/commit/9d11eba2c4bf08bceb077c2ed731ee58ef0f95eb))
* **workflows:** add support for Docker target build stage input ([2f22c7e](https://github.com/sava-software/sava-build/commit/2f22c7e93267c5a127543fed90ae06bc68cba427))


### Bug Fixes

* **workflows:** update Gradle publish task for GitHub Packages ([68ead6d](https://github.com/sava-software/sava-build/commit/68ead6d4e3890719a2467e3b7f5759851322a8ea))


### Build System

* **deps:** bump gradle/actions from 6.1.1 to 6.2.0 ([#28](https://github.com/sava-software/sava-build/issues/28)) ([68b2026](https://github.com/sava-software/sava-build/commit/68b2026c593d07650253168794095646c9771722))


### Chores

* **build:** update verification metadata for Gradle and new plugin ([00a33e2](https://github.com/sava-software/sava-build/commit/00a33e2cecfa5cd218c43927abd941aa06846326))
* **build:** update verification metadata for new trusted keys and artifacts ([0719b24](https://github.com/sava-software/sava-build/commit/0719b24fdcdb7cd42232dc5fe4230b8e10a10a16))
* release 21.4.0 ([e9fb27d](https://github.com/sava-software/sava-build/commit/e9fb27dcf7dc183d0b987e413b01859ddb40d2d0))
* **workflows:** remove CodeQL workflow configuration ([0562d0a](https://github.com/sava-software/sava-build/commit/0562d0a72638912d807e6e8d4b6e3df5f97693d7))
* **workflows:** update PR check to exclude dependabot user ([ae36de5](https://github.com/sava-software/sava-build/commit/ae36de53ad464cc1b394193d3177933a15e43534))


### CI

* **dependabot:** switch update schedule to daily ([b55b9fc](https://github.com/sava-software/sava-build/commit/b55b9fc3eab73d70299fea0288e7413698961eb2))

## [21.3.15](https://github.com/sava-software/sava-build/compare/21.3.14...21.3.15) (2026-06-01)


### Bug Fixes

* **workflows:** update Gradle publish step and refine secrets usage ([909e680](https://github.com/sava-software/sava-build/commit/909e680c96145ff082954d36fe0e999f1fd56b23))

## [21.3.14](https://github.com/sava-software/sava-build/compare/21.3.13...21.3.14) (2026-06-01)


### CI

* **workflows:** revert release-please action version ([757dd79](https://github.com/sava-software/sava-build/commit/757dd79097a93466014f73879d2dbbfec4db60cf))
* **workflows:** update release-please action to latest commit ([a202300](https://github.com/sava-software/sava-build/commit/a2023008138719f25f790a9d61f665a9e9d14056))
* **workflows:** update workflows with refined credentials and repository config ([62ff619](https://github.com/sava-software/sava-build/commit/62ff619312276461f8ff225ca24076d7e28e37ca))

## [21.3.13](https://github.com/sava-software/sava-build/compare/21.3.12...21.3.13) (2026-05-30)


### Refactors

* **dependency-rules:** remove unused mergeJar entry for jsr305 ([ecb392a](https://github.com/sava-software/sava-build/commit/ecb392a11fe6566c0d3ce20d299d85ab73b0f40c))

## [21.3.12](https://github.com/sava-software/sava-build/compare/21.3.11...21.3.12) (2026-05-28)


### CI

* **actions:** add shared GitHub Actions for Docker and GCP setup ([74a30dd](https://github.com/sava-software/sava-build/commit/74a30ddbefdcdc6778eb5944045ab42cb25d473c))
* **workflows:** add Gradle PR check workflow ([c8bba13](https://github.com/sava-software/sava-build/commit/c8bba13ca717e6f8c888130bdfc070506dd8c6e8))
* **workflows:** add permissions block to release-please workflow ([971bba7](https://github.com/sava-software/sava-build/commit/971bba7855835a90880123da9c9ba003a85c1d3e))
* **workflows:** add shared release-please workflow. ([0c181d1](https://github.com/sava-software/sava-build/commit/0c181d1cad62e02fcef7ada5cb5556143d5dc47c))
* **workflows:** optimize concurrency groups and remove redundant comments ([2a5d0d9](https://github.com/sava-software/sava-build/commit/2a5d0d91e4c4a44cb787d63f74240f44a3ef1709))
* **workflows:** re-add shared publish and build workflows for Gradle ([35d34ce](https://github.com/sava-software/sava-build/commit/35d34ce4067f6cac345af7f79b26cf9025528ea3))
* **workflows:** remove redundant version comments in GitHub workflows ([17ba211](https://github.com/sava-software/sava-build/commit/17ba2117ddc141134cce1559c25330f15cc93310))
* **workflows:** update release workflow to use shared configuration ([7368e85](https://github.com/sava-software/sava-build/commit/7368e8510a3737ec8e249efa7a38593a108968f6))

## [21.3.11](https://github.com/sava-software/sava-build/compare/21.3.10...21.3.11) (2026-05-18)


### Build System

* update dependency versions for gradle plugins ([f49db39](https://github.com/sava-software/sava-build/commit/f49db39587155823ae361ed81b8480b2d1120e50))


### Chores

* **build:** configure changelog sections in release-please ([c00baf4](https://github.com/sava-software/sava-build/commit/c00baf40767fb9346828d2dc5c0847d887e8422a))


### CI

* replace workflows and refactor Gradle actions ([158eab2](https://github.com/sava-software/sava-build/commit/158eab218c52b9dde4b818f2a12f3a37a5fa3087))
* **workflows:** add CodeQL analysis workflow ([a11a662](https://github.com/sava-software/sava-build/commit/a11a662e767b88ac2cd68da1fede66ed7fcab872))
* **workflows:** enhance CodeQL workflow with matrix support ([7e763aa](https://github.com/sava-software/sava-build/commit/7e763aa72a33c6d5ec59191efbd13b429ecbee02))
* **workflows:** rename CodeQL workflow to 'CodeQL Advanced' ([8b69747](https://github.com/sava-software/sava-build/commit/8b69747c01ca40c0672e083208d92e37ddf60d38))
* **workflows:** update Gradle assemble task in CodeQL workflow ([158616d](https://github.com/sava-software/sava-build/commit/158616d7248d5284e971c287dcf55fa3666c6064))
