# Changelog

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
