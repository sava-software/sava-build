# Release attestations

Each `<version>.json` file is the release owner's compact, committed attestation
that the named `sava-build` candidate passed both strict local release runners.
It records hashes and candidate identity only; the full immutable fleet and fuzz
receipt bundles remain outside Git and must be retained with the release records.

Create a record only after both release receipts verify and the Release Please
version metadata is present:

```shell
version=$(jq -r '.["."]' .release-please-manifest.json)
tools/release-attestation.sh create "$version"
git add "release-attestations/$version.json"
```

The release commit may differ from the certified candidate only by
`CHANGELOG.md`, `.release-please-manifest.json`, and that version's new
attestation. The Release Please tag path verifies the pending record; the
tag-triggered publish path verifies it again against the exact tag checkout.

These records are a forgetfulness and stale-candidate gate, not a substitute for
the machine-local receipts or GitHub's artifact provenance attestation.
