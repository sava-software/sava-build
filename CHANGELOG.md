# Changelog

## [21.3.16](https://github.com/sava-software/sava-build/compare/21.3.15...21.3.16) (2026-06-14)


### Features

* **workflows:** add support for Docker target build stage input ([2f22c7e](https://github.com/sava-software/sava-build/commit/2f22c7e93267c5a127543fed90ae06bc68cba427))


### Bug Fixes

* **workflows:** update Gradle publish task for GitHub Packages ([68ead6d](https://github.com/sava-software/sava-build/commit/68ead6d4e3890719a2467e3b7f5759851322a8ea))


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
