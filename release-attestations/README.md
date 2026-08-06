# Release attestations

Each `<version>.json` file is the release owner's compact, committed attestation
for one named `sava-build` candidate and exact JAR. Schema 4 gives every derived
consumer certification one of two owner-reviewed roles: `feature-path` or
`certification-only`.

An exact-byte entry proves that the consumer certified the candidate bytes. It
does not imply that the consumer exercised every behavior changed in the release.
The historical schema-2 and schema-3 formats remain verifiable.

Create a record only after the local passes have been reviewed, their completed
`build/hardening/pitest-certification.tsv` receipts remain in clean consumer
checkouts, their exact `0.0.0-test` JAR is retained, and the Release Please version
metadata is present. Multi-project checkouts may contribute multiple receipts; every
schema-6 receipt must bind a clean Git commit/tree that still equals the checkout,
and its project-level plugin hash and every suite row must name the retained JAR's
SHA-256. Zero-suite project receipts are retained, but each repository must contain
at least one positively certified suite.

Choose the review basis deliberately:

- `consumer-feature` requires at least one `--feature-adoption`. Use this when a
  consumer exercised the release's changed behavior.
- `plugin-only` refuses feature adoptions and permits an empty consumer list. Use it
  when plugin-owned tests carry the changed-feature proof; optional supplied consumers
  are exact-byte certification only.
- `certification-only` also refuses feature adoptions. Use it only when exact-byte
  hardening certification is the intended proof; at least one consumer is required.

For the current consumer-feature shape, Ravina exercised the ArcMutate-history path
and private idl-src-gen confirmed the exact candidate bytes without ArcMutate history:

```shell
version=$(jq -r '.["."]' .release-please-manifest.json)
tools/release-attestation.sh create-reviewed "$version" \
  --candidate <final-reviewed-main-commit> \
  --plugin-jar <retained-reviewed-0.0.0-test-jar> \
  --review-basis consumer-feature \
  --certification-only-adoption <path-to-clean-idl-src-gen-checkout> \
  --feature-adoption <path-to-clean-ravina-checkout>
git add "release-attestations/$version.json"
```

Each adoption argument must be the canonical, symlink-free absolute path to the
consumer's Git worktree root. Do not use literal `.` or `..` components, repeated
separators, or a trailing separator; those forms contain traversal or empty path
components and are deliberately refused. `--adoption` remains a deprecated alias
for `--certification-only-adoption`.

Both adoption flags derive and validate the same receipts. The flag records the
owner's narrower judgment about what the pass proved. `create-reviewed` fails on
absent, incomplete, malformed, mixed, or stale certification evidence; a dirty
checkout; a non-GitHub or duplicate origin; and symlinked or escaping evidence paths.
It reads every checkout and receipt twice so changes between the two complete
observations are refused.

The repository inventory is exact about the receipts it found; it is not a
build-root discovery oracle. A checkout can contain independent Gradle roots that
cannot discover one another, including several receipts whose Gradle project is `:`.
The release owner must compare the recorded relative receipt paths/projects with the
intended adoption scope and must not name a checkout whose required build root was
never certified.

The release commit may differ from the certified candidate only by
`CHANGELOG.md`, `.release-please-manifest.json`, and that version's new
attestation. The Release Please tag path verifies the pending record; the
tag-triggered publish path verifies it again against the exact tag checkout.

Verification reports the number of exact-byte-certified consumer checkouts and how
many are owner-reviewed feature-path consumers. These records are a forgetfulness,
stale-candidate, wrong-artifact, and honest over-claim gate, not a substitute for
reviewer judgment or GitHub's artifact provenance attestation. A certification
receipt binds the exact plugin bytes observed by PIT to the exact clean consumer
revision; only the `feature-path` role says that repository exercised the changed
behavior.
