# Release attestations

Each `<version>.json` file is the release owner's compact, committed attestation
that the named `sava-build` candidate's exact JAR passed the listed, locally
reviewed consumer adoptions. It records candidate identity, JAR hash, and the
reviewed repository names without claiming that another aggregate fleet run occurred.

Create a record only after the local passes have been reviewed, their exact
`0.0.0-test` JAR retained, and the Release Please version metadata is present:

```shell
version=$(jq -r '.["."]' .release-please-manifest.json)
tools/release-attestation.sh create-reviewed "$version" \
  --candidate <final-reviewed-main-commit> \
  --plugin-jar <retained-reviewed-0.0.0-test-jar> \
  --adoption <github-org/repo> [--adoption <github-org/repo> ...]
git add "release-attestations/$version.json"
```

The release commit may differ from the certified candidate only by
`CHANGELOG.md`, `.release-please-manifest.json`, and that version's new
attestation. The Release Please tag path verifies the pending record; the
tag-triggered publish path verifies it again against the exact tag checkout.

These records are a forgetfulness, stale-candidate, and wrong-artifact gate, not
a substitute for reviewer judgment or GitHub's artifact provenance attestation.
The optional strict fleet/fuzz receipt mode remains available through the legacy
`create` command when a change genuinely warrants that additional experiment.
