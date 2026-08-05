# Release attestations

Each `<version>.json` file is the release owner's compact, committed attestation
that the named `sava-build` candidate's exact JAR passed the listed, locally
reviewed consumer adoptions. New records derive each repository's GitHub origin,
clean commit/tree, certification-receipt hash, certified suites, and loaded plugin
JAR hash from the consumer checkout rather than accepting a repository name as an
owner assertion. The historical schema-2 record remains verifiable.

Create a record only after the local passes have been reviewed, their completed
`build/hardening/pitest-certification.tsv` receipts remain in clean consumer
checkouts, their exact `0.0.0-test` JAR is retained, and the Release Please version
metadata is present. Multi-project checkouts may contribute multiple receipts; every
schema-6 receipt must bind a clean Git commit/tree that still equals the checkout,
and its project-level plugin hash and every suite row must name the retained JAR's
SHA-256. Zero-suite project receipts are retained, but each adopted repository must
contain at least one positively certified suite:

```shell
version=$(jq -r '.["."]' .release-please-manifest.json)
tools/release-attestation.sh create-reviewed "$version" \
  --candidate <final-reviewed-main-commit> \
  --plugin-jar <retained-reviewed-0.0.0-test-jar> \
  --adoption <path-to-clean-consumer-checkout> \
  [--adoption <path-to-another-clean-consumer-checkout> ...]
git add "release-attestations/$version.json"
```

Each `--adoption` argument must be the canonical, symlink-free absolute path to the
consumer's Git worktree root. Do not use literal `.` or `..` components, repeated
separators, or a trailing separator; those forms contain traversal or empty path
components and are deliberately refused.

`create-reviewed` fails on absent, incomplete, malformed, mixed, or stale
certification evidence; a dirty checkout; a non-GitHub or duplicate origin; and
symlinked or escaping evidence paths. It reads every checkout and receipt twice so
changes between the two complete observations are refused.

The record is exact about the receipts it found; it is not a build-root discovery
oracle. A checkout can contain independent Gradle roots that cannot discover one
another, including several receipts whose Gradle project is `:`. The release owner
must compare the recorded relative receipt paths/projects with the intended adoption
scope and must not name a checkout whose required build root was never certified.

The release commit may differ from the certified candidate only by
`CHANGELOG.md`, `.release-please-manifest.json`, and that version's new
attestation. The Release Please tag path verifies the pending record; the
tag-triggered publish path verifies it again against the exact tag checkout.

These records are a forgetfulness, stale-candidate, wrong-artifact, and honest
over-claim gate, not a substitute for reviewer judgment or GitHub's artifact
provenance attestation. The receipt binds the exact plugin bytes observed by PIT to
the exact clean consumer revision recorded in the attestation.
