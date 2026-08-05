#!/usr/bin/env bash
# Bridges reviewed local adoption evidence into the Git history that creates and
# publishes a release. The committed file is an owner attestation: it names the
# projects whose local adoption passes were reviewed and binds that decision to the
# exact candidate commit, tree, and plugin JAR. The optional strict fleet/fuzz runners
# remain available for unusually broad changes, but are not a release prerequisite.
set -euo pipefail
export LC_ALL=C

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd -P)
release_manifest="$sava_build_dir/.release-please-manifest.json"
fleet_manifest="$sava_build_dir/tools/fleet-manifest.txt"
attestations_dir="$sava_build_dir/release-attestations"
fleet_verifier="$sava_build_dir/tools/fleet-canary.sh"
fuzz_verifier="$sava_build_dir/tools/local-fuzz.sh"
default_fleet_pointer="$sava_build_dir/build/hardening/fleet-canary-receipt.json"
default_fuzz_pointer="$sava_build_dir/build/hardening/local-fuzz-receipt.json"
expected_origin_slug="sava-software/sava-build"
release_attestation_self_test_cleanup_path=""

usage() {
  cat <<'EOF'
Usage:
  tools/release-attestation.sh create-reviewed <version> --candidate <commit> --plugin-jar <path> --adoption <github-org/repo> [--adoption <github-org/repo> ...]
  tools/release-attestation.sh create <version> [--fleet-pointer <path>] [--fuzz-pointer <path>]
  tools/release-attestation.sh verify <version>
  tools/release-attestation.sh verify-tag <version>
  tools/release-attestation.sh verify-built-jar <version> <jar>
  tools/release-attestation.sh verify-pending-release
  tools/release-attestation.sh --self-test

`create-reviewed` records the already-reviewed local adoption cohort and hashes the
exact candidate JAR those passes exercised. `create` remains available when an owner deliberately
chooses the optional full-fleet/fuzz receipt path. Commit the generated file to the
Release Please PR. Tag creation and publication then validate the candidate identity
and refuse a rebuilt JAR whose bytes differ from the reviewed candidate.
EOF
}

sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk 'NR == 1 { print $1; exit }'
  else
    shasum -a 256 | awk 'NR == 1 { print $1; exit }'
  fi
}

sha256_file() {
  sha256_stream < "$1"
}

require_tools() {
  local tool
  for tool in git jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "release-attestation: $tool is required" >&2
      return 1
    fi
  done
}

require_version() {
  if ! [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "release-attestation: version must be a numeric x.y.z value: '$1'" >&2
    return 1
  fi
}

manifest_version() {
  jq -er '.["."] | select(type == "string")' "$release_manifest"
}

origin_slug() {
  local value=$1
  value=$(printf '%s\n' "$value" | sed 's/[.]git$//')
  case "$value" in
    git@github.com:*) printf '%s\n' "$value" | sed 's|^git@github.com:||' ;;
    https://github.com/*) printf '%s\n' "$value" | sed 's|^https://github.com/||' ;;
    ssh://git@github.com/*) printf '%s\n' "$value" | sed 's|^ssh://git@github.com/||' ;;
    *) printf '%s\n' "$value" ;;
  esac
}

absolute_file() {
  local requested=$1 parent base
  case "$requested" in /*) ;; *) requested="$PWD/$requested" ;; esac
  parent=$(cd "$(dirname "$requested")" && pwd -P) || return 1
  base=$(basename "$requested")
  printf '%s/%s\n' "$parent" "$base"
}

require_clean_checkout() {
  local status
  status=$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)
  if [ -n "$status" ]; then
    echo "release-attestation: sava-build checkout must be clean before reading release evidence:" >&2
    printf '%s\n' "$status" >&2
    return 1
  fi
}

manifest_repository_count() {
  awk 'NF && $1 !~ /^#/ { count++ } END { print count + 0 }' "$fleet_manifest"
}

# Print a compact, trusted projection of one canonical pointer and its immutable
# bundle receipt. The full runner verifier executes first; this function only binds
# the fields the committed release attestation needs.
verified_pointer_summary() {
  local leg=$1 pointer_kind=$2 receipt_kind=$3 verifier=$4 requested=$5
  local pointer pointer_before pointer_after pointer_json bundle receipt expected
  local receipt_before receipt_after receipt_json
  pointer=$(absolute_file "$requested") || return 1
  if [ ! -f "$pointer" ] || [ -L "$pointer" ]; then
    echo "release-attestation: missing or symlinked $leg pointer: $pointer" >&2
    return 1
  fi
  pointer_before=$(sha256_file "$pointer") || return 1
  # The caller guards this whole function with `|| return 1`, which disables
  # errexit inside it on Bash 3.2. Every delegated verifier must be explicit.
  if ! "$verifier" --verify-receipt "$pointer" >&2; then
    echo "release-attestation: $leg receipt verifier rejected $pointer" >&2
    return 1
  fi
  pointer_after=$(sha256_file "$pointer") || return 1
  if [ "$pointer_before" != "$pointer_after" ]; then
    echo "release-attestation: $leg pointer changed during verification: $pointer" >&2
    return 1
  fi
  pointer_json=$(<"$pointer")
  if ! jq -e --arg kind "$pointer_kind" '
      .schema == 2 and .kind == $kind and .mode == "release" and .result == "passed" and
      (.run_id | test("^run[.][A-Za-z0-9]+$")) and
      (.plugin_sha | test("^[0-9a-f]{40}$")) and
      (.receipt_sha256 | test("^[0-9a-f]{64}$")) and
      (.bundle | type == "string" and length > 0)
    ' <<< "$pointer_json" >/dev/null; then
    echo "release-attestation: invalid completed $leg release pointer: $pointer" >&2
    return 1
  fi
  bundle=$(jq -r '.bundle' <<< "$pointer_json")
  case "$bundle" in
    ''|/*)
      echo "release-attestation: invalid $leg bundle path: $bundle" >&2
      return 1
      ;;
  esac
  case "/$bundle/" in
    */../*|*/./*|*//* )
      echo "release-attestation: $leg bundle escapes its pointer directory: $bundle" >&2
      return 1
      ;;
  esac
  receipt="$(dirname "$pointer")/$bundle"
  if [ ! -f "$receipt" ] || [ -L "$receipt" ]; then
    echo "release-attestation: missing or symlinked $leg bundle receipt: $receipt" >&2
    return 1
  fi
  receipt_before=$(sha256_file "$receipt") || return 1
  expected=$(jq -r '.receipt_sha256' <<< "$pointer_json")
  if [ "$receipt_before" != "$expected" ]; then
    echo "release-attestation: $leg bundle receipt hash does not match its pointer" >&2
    return 1
  fi
  receipt_json=$(<"$receipt")
  receipt_after=$(sha256_file "$receipt") || return 1
  if [ "$receipt_before" != "$receipt_after" ]; then
    echo "release-attestation: $leg bundle receipt changed while being projected" >&2
    return 1
  fi
  if ! jq -e --arg kind "$receipt_kind" '
      .schema == 3 and .kind == $kind and .mode == "release" and .result == "passed" and
      (.run_id | test("^run[.][A-Za-z0-9]+$")) and
      (.plugin.sha | test("^[0-9a-f]{40}$")) and
      (.plugin.tree | test("^[0-9a-f]{40}$")) and
      (.plugin.origin | type == "string" and length > 0) and
      .plugin.dirty_before == false and .plugin.dirty_after == false and
      (.plugin.test_jar_sha256 | test("^[0-9a-f]{64}$")) and
      (.manifest_sha256 | test("^[0-9a-f]{64}$")) and
      (.repositories | type == "array" and length > 0) and
      all(.repositories[];
        (.slug | type == "string" and length > 0) and
        (.sha | test("^[0-9a-f]{40}$")) and
        (.origin | type == "string" and length > 0)) and
      (([.repositories[].slug] | unique | length) == (.repositories | length))
    ' <<< "$receipt_json" >/dev/null; then
    echo "release-attestation: invalid $leg bundle receipt: $receipt" >&2
    return 1
  fi
  if [ "$(jq -r '.run_id' <<< "$pointer_json")" != "$(jq -r '.run_id' <<< "$receipt_json")" ] ||
      [ "$(jq -r '.plugin_sha' <<< "$pointer_json")" != "$(jq -r '.plugin.sha' <<< "$receipt_json")" ]; then
    echo "release-attestation: $leg pointer identity does not match its bundle receipt" >&2
    return 1
  fi
  jq -cn --argjson receipt "$receipt_json" \
    --arg pointer_sha256 "$pointer_before" --arg receipt_sha256 "$receipt_before" \
    '{run_id:$receipt.run_id,pointer_sha256:$pointer_sha256,receipt_sha256:$receipt_sha256,
      plugin_sha:$receipt.plugin.sha,plugin_tree:$receipt.plugin.tree,
      plugin_origin:$receipt.plugin.origin,plugin_jar_sha256:$receipt.plugin.test_jar_sha256,
      manifest_sha256:$receipt.manifest_sha256,repository_count:($receipt.repositories|length),
      repository_identity:($receipt.repositories | map({slug,sha,origin}) | sort_by(.slug)),
      advisories_acknowledged:($receipt.advisories_acknowledged // false),
      seconds_per_target:($receipt.seconds_per_target // 0)}'
}

validate_candidate() {
  local commit=$1 tree=$2 actual_tree
  if ! git -C "$sava_build_dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
    echo "release-attestation: certified candidate commit is not in this checkout: $commit" >&2
    return 1
  fi
  actual_tree=$(git -C "$sava_build_dir" rev-parse "$commit^{tree}")
  if [ "$actual_tree" != "$tree" ]; then
    echo "release-attestation: candidate tree does not belong to commit $commit" >&2
    return 1
  fi
  if ! git -C "$sava_build_dir" merge-base --is-ancestor "$commit" HEAD; then
    echo "release-attestation: certified candidate $commit is not an ancestor of HEAD" >&2
    return 1
  fi
}

validate_release_diff() {
  local commit=$1 version=$2 require_attestation=$3
  local relative="release-attestations/$version.json" path seen_attestation=false
  while IFS= read -r -d '' path; do
    case "$path" in
      CHANGELOG.md|.release-please-manifest.json) ;;
      "$relative")
        if ! $require_attestation; then
          echo "release-attestation: $relative already differs from the certified candidate" >&2
          return 1
        fi
        seen_attestation=true
        ;;
      *)
        echo "release-attestation: non-release file changed after candidate review: $path" >&2
        return 1
        ;;
    esac
  done < <(git -C "$sava_build_dir" diff --name-only -z "$commit"..HEAD --)
  if $require_attestation && ! $seen_attestation; then
    echo "release-attestation: committed attestation is not newer than certified candidate $commit" >&2
    return 1
  fi
}

create_reviewed_attestation() {
  local version=$1 candidate_commit=$2 requested_jar=$3
  shift 3
  local output tmp candidate_tree candidate_origin jar before after reviewed adoption duplicates
  require_version "$version"
  if [ "$(manifest_version)" != "$version" ]; then
    echo "release-attestation: release manifest does not name version $version" >&2
    return 1
  fi
  output="$attestations_dir/$version.json"
  if [ -e "$output" ] || [ -L "$output" ]; then
    echo "release-attestation: refusing to overwrite existing release record: $output" >&2
    return 1
  fi
  require_clean_checkout
  if ! [[ "$candidate_commit" =~ ^[0-9a-f]{40}$ ]] ||
      ! git -C "$sava_build_dir" cat-file -e "$candidate_commit^{commit}" 2>/dev/null; then
    echo "release-attestation: reviewed candidate is not a full commit in this checkout: $candidate_commit" >&2
    return 1
  fi
  candidate_tree=$(git -C "$sava_build_dir" rev-parse "$candidate_commit^{tree}") || return 1
  validate_candidate "$candidate_commit" "$candidate_tree"
  validate_release_diff "$candidate_commit" "$version" false
  candidate_origin=$(origin_slug "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)")
  if [ "$candidate_origin" != "$expected_origin_slug" ]; then
    echo "release-attestation: candidate origin is '$candidate_origin', expected '$expected_origin_slug'" >&2
    return 1
  fi
  if [ "$#" -eq 0 ]; then
    echo "release-attestation: create-reviewed requires at least one --adoption github-org/repo" >&2
    return 1
  fi
  for adoption in "$@"; do
    if ! [[ "$adoption" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]]; then
      echo "release-attestation: invalid reviewed adoption slug: '$adoption'" >&2
      return 1
    fi
  done
  duplicates=$(printf '%s\n' "$@" | sort | uniq -d)
  if [ -n "$duplicates" ]; then
    echo "release-attestation: duplicate reviewed adoption slug(s):" >&2
    printf '%s\n' "$duplicates" >&2
    return 1
  fi
  reviewed=$(printf '%s\n' "$@" | jq -Rsc 'split("\n") | map(select(length > 0)) | sort') || return 1

  jar=$(absolute_file "$requested_jar") || return 1
  if [ ! -f "$jar" ] || [ -L "$jar" ]; then
    echo "release-attestation: missing or symlinked reviewed plugin JAR: $jar" >&2
    return 1
  fi
  before=$(sha256_file "$jar") || return 1
  require_clean_checkout
  after=$(sha256_file "$jar") || return 1
  if [ "$before" != "$after" ]; then
    echo "release-attestation: reviewed plugin JAR changed during attestation: $jar" >&2
    return 1
  fi

  if [ ! -d "$attestations_dir" ] || [ -L "$attestations_dir" ]; then
    echo "release-attestation: missing regular release-attestations directory" >&2
    return 1
  fi
  tmp=$(mktemp "$attestations_dir/.release-attestation.XXXXXX")
  trap 'rm -f "$tmp"' RETURN
  jq -nS \
    --arg version "$version" --arg commit "$candidate_commit" --arg tree "$candidate_tree" \
    --arg origin "$candidate_origin" --arg plugin_hash "$after" \
    --argjson repositories "$reviewed" \
    '{schema:2,kind:"sava-build-release-attestation",version:$version,
      candidate:{commit:$commit,tree:$tree,origin:$origin,plugin_jar_sha256:$plugin_hash},
      review:{kind:"reviewed-local-adoption-passes",repositories:$repositories}}' > "$tmp"
  chmod 0644 "$tmp"
  if [ -e "$output" ] || [ -L "$output" ]; then
    echo "release-attestation: output appeared during creation; refusing overwrite" >&2
    return 1
  fi
  mv "$tmp" "$output"
  trap - RETURN
  echo "release-attestation: wrote $output"
  echo "release-attestation: commit it to the Release Please PR with only release metadata changes"
}

create_attestation() {
  local version=$1 fleet_pointer=$2 fuzz_pointer=$3 output tmp
  local fleet fuzz candidate_commit candidate_tree candidate_origin plugin_hash manifest_hash
  local repo_count fleet_count fuzz_count fuzz_seconds advisories inventory_hash
  require_version "$version"
  if [ "$(manifest_version)" != "$version" ]; then
    echo "release-attestation: release manifest does not name version $version" >&2
    return 1
  fi
  output="$attestations_dir/$version.json"
  if [ -e "$output" ] || [ -L "$output" ]; then
    echo "release-attestation: refusing to overwrite existing release record: $output" >&2
    return 1
  fi
  require_clean_checkout
  fleet=$(verified_pointer_summary fleet fleet-canary-pointer fleet-canary-receipt \
      "$fleet_verifier" "$fleet_pointer") || return 1
  fuzz=$(verified_pointer_summary fuzz local-fuzz-pointer local-fuzz-receipt \
      "$fuzz_verifier" "$fuzz_pointer") || return 1
  require_clean_checkout

  candidate_commit=$(jq -r '.plugin_sha' <<< "$fleet")
  candidate_tree=$(jq -r '.plugin_tree' <<< "$fleet")
  candidate_origin=$(origin_slug "$(jq -r '.plugin_origin' <<< "$fleet")")
  plugin_hash=$(jq -r '.plugin_jar_sha256' <<< "$fleet")
  manifest_hash=$(jq -r '.manifest_sha256' <<< "$fleet")
  if [ "$candidate_commit" != "$(jq -r '.plugin_sha' <<< "$fuzz")" ] ||
      [ "$candidate_tree" != "$(jq -r '.plugin_tree' <<< "$fuzz")" ] ||
      [ "$candidate_origin" != "$(origin_slug "$(jq -r '.plugin_origin' <<< "$fuzz")")" ] ||
      [ "$plugin_hash" != "$(jq -r '.plugin_jar_sha256' <<< "$fuzz")" ] ||
      [ "$manifest_hash" != "$(jq -r '.manifest_sha256' <<< "$fuzz")" ] ||
      [ "$(jq -cS '.repository_identity' <<< "$fleet")" != \
        "$(jq -cS '.repository_identity' <<< "$fuzz")" ]; then
    echo "release-attestation: fleet and fuzz receipts certify different candidates" >&2
    return 1
  fi
  if [ "$candidate_origin" != "$expected_origin_slug" ]; then
    echo "release-attestation: certified origin is '$candidate_origin', expected '$expected_origin_slug'" >&2
    return 1
  fi
  validate_candidate "$candidate_commit" "$candidate_tree"
  validate_release_diff "$candidate_commit" "$version" false
  if [ "$(sha256_file "$fleet_manifest")" != "$manifest_hash" ]; then
    echo "release-attestation: fleet manifest changed after certification" >&2
    return 1
  fi
  repo_count=$(manifest_repository_count)
  fleet_count=$(jq -r '.repository_count' <<< "$fleet")
  fuzz_count=$(jq -r '.repository_count' <<< "$fuzz")
  if [ "$repo_count" -le 0 ] || [ "$fleet_count" != "$repo_count" ] || [ "$fuzz_count" != "$repo_count" ]; then
    echo "release-attestation: receipt repository counts do not match the current manifest" >&2
    return 1
  fi
  fuzz_seconds=$(jq -r '.seconds_per_target' <<< "$fuzz")
  case "$fuzz_seconds" in ''|*[!0-9]*|0) echo "release-attestation: invalid fuzz budget: $fuzz_seconds" >&2; return 1 ;; esac
  advisories=$(jq -r '.advisories_acknowledged' <<< "$fleet")
  inventory_hash=$(jq -cS '.repository_identity' <<< "$fleet" | sha256_stream)

  if [ ! -d "$attestations_dir" ] || [ -L "$attestations_dir" ]; then
    echo "release-attestation: missing regular release-attestations directory" >&2
    return 1
  fi
  tmp=$(mktemp "$attestations_dir/.release-attestation.XXXXXX")
  trap 'rm -f "$tmp"' RETURN
  jq -nS \
    --arg version "$version" --arg commit "$candidate_commit" --arg tree "$candidate_tree" \
    --arg origin "$candidate_origin" --arg plugin_hash "$plugin_hash" \
    --arg manifest_hash "$manifest_hash" --argjson repo_count "$repo_count" \
    --arg inventory_hash "$inventory_hash" \
    --arg fleet_run "$(jq -r '.run_id' <<< "$fleet")" \
    --arg fleet_pointer_hash "$(jq -r '.pointer_sha256' <<< "$fleet")" \
    --arg fleet_receipt_hash "$(jq -r '.receipt_sha256' <<< "$fleet")" \
    --argjson advisories "$advisories" \
    --arg fuzz_run "$(jq -r '.run_id' <<< "$fuzz")" \
    --arg fuzz_pointer_hash "$(jq -r '.pointer_sha256' <<< "$fuzz")" \
    --arg fuzz_receipt_hash "$(jq -r '.receipt_sha256' <<< "$fuzz")" \
    --argjson fuzz_seconds "$fuzz_seconds" \
    '{schema:1,kind:"sava-build-release-attestation",version:$version,
      candidate:{commit:$commit,tree:$tree,origin:$origin,
        plugin_jar_sha256:$plugin_hash,fleet_manifest_sha256:$manifest_hash,
        repository_count:$repo_count,consumer_inventory_sha256:$inventory_hash},
      fleet:{run_id:$fleet_run,pointer_sha256:$fleet_pointer_hash,
        receipt_sha256:$fleet_receipt_hash,advisories_acknowledged:$advisories},
      fuzz:{run_id:$fuzz_run,pointer_sha256:$fuzz_pointer_hash,
        receipt_sha256:$fuzz_receipt_hash,seconds_per_target:$fuzz_seconds}}' > "$tmp"
  if [ -e "$output" ] || [ -L "$output" ]; then
    echo "release-attestation: output appeared during creation; refusing overwrite" >&2
    return 1
  fi
  mv "$tmp" "$output"
  trap - RETURN
  echo "release-attestation: wrote $output"
  echo "release-attestation: commit it to the Release Please PR with only release metadata changes"
}

attestation_schema_valid() {
  local file=$1 version=$2 schema
  schema=$(jq -r '.schema // empty' "$file") || return 1
  case "$schema" in
    1)
      jq -e --arg version "$version" --arg origin "$expected_origin_slug" '
        keys == ["candidate","fleet","fuzz","kind","schema","version"] and
        .schema == 1 and .kind == "sava-build-release-attestation" and .version == $version and
        (.candidate | keys == ["commit","consumer_inventory_sha256","fleet_manifest_sha256","origin","plugin_jar_sha256","repository_count","tree"]) and
        (.candidate.commit | test("^[0-9a-f]{40}$")) and
        (.candidate.tree | test("^[0-9a-f]{40}$")) and .candidate.origin == $origin and
        (.candidate.plugin_jar_sha256 | test("^[0-9a-f]{64}$")) and
        (.candidate.consumer_inventory_sha256 | test("^[0-9a-f]{64}$")) and
        (.candidate.fleet_manifest_sha256 | test("^[0-9a-f]{64}$")) and
        (.candidate.repository_count | type == "number" and . > 0 and floor == .) and
        (.fleet | keys == ["advisories_acknowledged","pointer_sha256","receipt_sha256","run_id"]) and
        (.fleet.run_id | test("^run[.][A-Za-z0-9]+$")) and
        (.fleet.pointer_sha256 | test("^[0-9a-f]{64}$")) and
        (.fleet.receipt_sha256 | test("^[0-9a-f]{64}$")) and
        (.fleet.advisories_acknowledged | type == "boolean") and
        (.fuzz | keys == ["pointer_sha256","receipt_sha256","run_id","seconds_per_target"]) and
        (.fuzz.run_id | test("^run[.][A-Za-z0-9]+$")) and
        (.fuzz.pointer_sha256 | test("^[0-9a-f]{64}$")) and
        (.fuzz.receipt_sha256 | test("^[0-9a-f]{64}$")) and
        (.fuzz.seconds_per_target | type == "number" and . > 0 and floor == .)
      ' "$file" >/dev/null
      ;;
    2)
      jq -e --arg version "$version" --arg origin "$expected_origin_slug" '
        keys == ["candidate","kind","review","schema","version"] and
        .schema == 2 and .kind == "sava-build-release-attestation" and .version == $version and
        (.candidate | keys == ["commit","origin","plugin_jar_sha256","tree"]) and
        (.candidate.commit | test("^[0-9a-f]{40}$")) and
        (.candidate.tree | test("^[0-9a-f]{40}$")) and .candidate.origin == $origin and
        (.candidate.plugin_jar_sha256 | test("^[0-9a-f]{64}$")) and
        (.review | keys == ["kind","repositories"]) and
        .review.kind == "reviewed-local-adoption-passes" and
        (.review.repositories | type == "array" and length > 0) and
        all(.review.repositories[];
          type == "string" and test("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) and
        ((.review.repositories | unique | length) == (.review.repositories | length)) and
        (.review.repositories == (.review.repositories | sort))
      ' "$file" >/dev/null
      ;;
    *) return 1 ;;
  esac
}

verify_attestation() {
  local version=$1 file relative mode commit tree count schema reviewed_count
  require_version "$version"
  require_clean_checkout
  if [ "$(manifest_version)" != "$version" ]; then
    echo "release-attestation: release manifest does not name version $version" >&2
    return 1
  fi
  relative="release-attestations/$version.json"
  file="$sava_build_dir/$relative"
  if [ ! -f "$file" ] || [ -L "$file" ]; then
    echo "release-attestation: missing regular release record: $relative" >&2
    return 1
  fi
  mode=$(git -C "$sava_build_dir" ls-files -s -- "$relative" | awk 'NR == 1 { print $1 }')
  if [ "$mode" != "100644" ]; then
    echo "release-attestation: release record is not a tracked regular file: $relative" >&2
    return 1
  fi
  if ! attestation_schema_valid "$file" "$version"; then
    echo "release-attestation: invalid release record schema: $relative" >&2
    return 1
  fi
  require_clean_checkout
  commit=$(jq -r '.candidate.commit' "$file")
  tree=$(jq -r '.candidate.tree' "$file")
  validate_candidate "$commit" "$tree"
  validate_release_diff "$commit" "$version" true
  schema=$(jq -r '.schema' "$file")
  if [ "$schema" = 1 ]; then
    if [ "$(sha256_file "$fleet_manifest")" != "$(jq -r '.candidate.fleet_manifest_sha256' "$file")" ]; then
      echo "release-attestation: current fleet manifest does not match the certified manifest" >&2
      return 1
    fi
    count=$(manifest_repository_count)
    if [ "$count" != "$(jq -r '.candidate.repository_count' "$file")" ]; then
      echo "release-attestation: current fleet roster size does not match the release record" >&2
      return 1
    fi
    echo "release-attestation: version $version binds optional fleet/fuzz receipts to candidate $commit"
  else
    reviewed_count=$(jq -r '.review.repositories | length' "$file")
    echo "release-attestation: version $version binds $reviewed_count reviewed local adoption pass(es) to candidate $commit"
  fi
}

verify_tag() {
  local version=$1 tagged head
  require_version "$version"
  if ! git -C "$sava_build_dir" show-ref --verify --quiet "refs/tags/$version"; then
    echo "release-attestation: release tag does not exist: $version" >&2
    return 1
  fi
  tagged=$(git -C "$sava_build_dir" rev-list -n 1 "$version")
  head=$(git -C "$sava_build_dir" rev-parse HEAD)
  if [ "$tagged" != "$head" ]; then
    echo "release-attestation: tag $version points to $tagged, checkout is $head" >&2
    return 1
  fi
  verify_attestation "$version"
}

verify_built_jar() {
  local version=$1 requested=$2 jar before after expected
  require_version "$version"
  jar=$(absolute_file "$requested") || return 1
  if [ ! -f "$jar" ] || [ -L "$jar" ]; then
    echo "release-attestation: missing or symlinked built plugin JAR: $jar" >&2
    return 1
  fi
  before=$(sha256_file "$jar") || return 1
  # Re-check the exact tagged checkout after the build, then prove that the artifact
  # about to be published is the same deterministic JAR the local adoption passes
  # reviewed. Hash twice so a concurrent replacement cannot pass unnoticed.
  verify_tag "$version"
  expected=$(jq -r '.candidate.plugin_jar_sha256' \
    "$attestations_dir/$version.json") || return 1
  after=$(sha256_file "$jar") || return 1
  if [ "$before" != "$after" ]; then
    echo "release-attestation: built plugin JAR changed during verification: $jar" >&2
    return 1
  fi
  if [ "$after" != "$expected" ]; then
    echo "release-attestation: built plugin JAR does not match the certified candidate: $jar" >&2
    echo "release-attestation: expected $expected, found $after" >&2
    return 1
  fi
  echo "release-attestation: built plugin JAR matches the certified candidate: $after"
}

verify_pending_release() {
  local version tagged
  require_clean_checkout
  version=$(manifest_version)
  require_version "$version"
  if git -C "$sava_build_dir" show-ref --verify --quiet "refs/tags/$version"; then
    tagged=$(git -C "$sava_build_dir" rev-list -n 1 "$version")
    if ! git -C "$sava_build_dir" merge-base --is-ancestor "$tagged" HEAD; then
      echo "release-attestation: existing tag $version is not an ancestor of HEAD: $tagged" >&2
      return 1
    fi
    echo "release-attestation: $version is already tagged; no pending release gate"
    return 0
  fi
  echo "release-attestation: tag $version is pending; requiring its committed reviewed release attestation"
  verify_attestation "$version"
}

self_test() {
  local fixture script fixture_script candidate candidate_tree manifest_hash jar_hash
  local fleet_receipt fuzz_receipt fleet_pointer fuzz_pointer fleet_hash fuzz_hash output
  local divergent valid_attestation verifier_log expected_verifier_calls actual_verifier_calls
  local valid_fleet_receipt valid_fuzz_receipt valid_fleet_pointer valid_fuzz_pointer failure_log
  local GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
  export GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM
  require_tools
  release_attestation_self_test_cleanup_path=$(
    mktemp -d "${TMPDIR:-/tmp}/release-attestation-self-test.XXXXXX"
  )
  fixture=$release_attestation_self_test_cleanup_path
  trap '
    trap - RETURN EXIT
    case "$(basename "$release_attestation_self_test_cleanup_path")" in
      release-attestation-self-test.*)
        rm -rf -- "$release_attestation_self_test_cleanup_path" || true
        ;;
      *)
        echo "release-attestation self-test: refusing unsafe cleanup path $release_attestation_self_test_cleanup_path" >&2
        ;;
    esac
  ' RETURN EXIT
  fixture=$(cd "$fixture" && pwd -P)
  script="$sava_build_dir/tools/release-attestation.sh"
  mkdir -p "$fixture/tools" "$fixture/release-attestations" "$fixture/build/hardening/fleet-runs/run.A" \
    "$fixture/build/hardening/fuzz-runs/run.B"
  cp "$script" "$fixture/tools/release-attestation.sh"
  fixture_script="$fixture/tools/release-attestation.sh"
  chmod +x "$fixture_script"
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    '[ "$1" = --verify-receipt ]' '[ -f "$2" ]' \
    'case "$(basename "$0")" in' \
    '  fleet-canary.sh) kind=fleet-canary-pointer ;;' \
    '  local-fuzz.sh) kind=local-fuzz-pointer ;;' \
    '  *) exit 1 ;;' \
    'esac' \
    'jq -e --arg kind "$kind" ".kind == \$kind" "$2" >/dev/null' \
    'printf "%s\t%s\n" "$kind" "$2" >> "${RELEASE_ATTESTATION_SELF_TEST_VERIFIER_LOG:?}"' \
    'if [ "${RELEASE_ATTESTATION_SELF_TEST_REJECT_KIND:-}" = "$kind" ]; then' \
    '  echo "stub verifier: injected $kind rejection" >&2' \
    '  exit 23' \
    'fi' \
    'if [ "${RELEASE_ATTESTATION_SELF_TEST_DIRTY_KIND:-}" = "$kind" ]; then' \
    '  printf "injected verifier mutation\n" >> "${RELEASE_ATTESTATION_SELF_TEST_DIRTY_FILE:?}"' \
    'fi' \
    'echo "stub verifier: retained artifacts and checkout revalidation passed"' \
    > "$fixture/tools/fleet-canary.sh"
  cp "$fixture/tools/fleet-canary.sh" "$fixture/tools/local-fuzz.sh"
  chmod +x "$fixture/tools/fleet-canary.sh" "$fixture/tools/local-fuzz.sh"
  printf '%s\n' 'sava-software/example' > "$fixture/tools/fleet-manifest.txt"
  printf '%s\n' '{".":"1.0.0"}' > "$fixture/.release-please-manifest.json"
  printf '%s\n' '# Release attestations' > "$fixture/release-attestations/README.md"
  printf '%s\n' 'build/' > "$fixture/.gitignore"
  printf '%s\n' '# Changelog' > "$fixture/CHANGELOG.md"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git init -q
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test add .
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm candidate
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git tag 1.0.0
  )
  "$fixture_script" verify-pending-release >/dev/null
  candidate=$(git -C "$fixture" rev-parse HEAD)
  candidate_tree=$(git -C "$fixture" rev-parse HEAD^{tree})
  manifest_hash=$(sha256_file "$fixture/tools/fleet-manifest.txt")
  jar_hash=$(printf 'plugin jar' | sha256_stream)
  fleet_receipt="$fixture/build/hardening/fleet-runs/run.A/receipt.json"
  fuzz_receipt="$fixture/build/hardening/fuzz-runs/run.B/receipt.json"
  jq -n --arg commit "$candidate" --arg tree "$candidate_tree" --arg jar "$jar_hash" \
    --arg manifest "$manifest_hash" \
    '{schema:3,kind:"fleet-canary-receipt",mode:"release",result:"passed",run_id:"run.A",
      plugin:{sha:$commit,tree:$tree,origin:"git@github.com:sava-software/sava-build.git",
        dirty_before:false,dirty_after:false,test_jar_sha256:$jar},manifest_sha256:$manifest,
      advisories_acknowledged:false,
      repositories:[{slug:"sava-software/example",sha:$commit,
        origin:"git@github.com:sava-software/example.git"}]}' > "$fleet_receipt"
  jq -n --arg commit "$candidate" --arg tree "$candidate_tree" --arg jar "$jar_hash" \
    --arg manifest "$manifest_hash" \
    '{schema:3,kind:"local-fuzz-receipt",mode:"release",result:"passed",run_id:"run.B",
      seconds_per_target:17,
      plugin:{sha:$commit,tree:$tree,origin:"https://github.com/sava-software/sava-build.git",
        dirty_before:false,dirty_after:false,test_jar_sha256:$jar},manifest_sha256:$manifest,
      repositories:[{slug:"sava-software/example",sha:$commit,
        origin:"git@github.com:sava-software/example.git"}]}' > "$fuzz_receipt"
  fleet_hash=$(sha256_file "$fleet_receipt")
  fuzz_hash=$(sha256_file "$fuzz_receipt")
  fleet_pointer="$fixture/build/hardening/fleet-canary-receipt.json"
  fuzz_pointer="$fixture/build/hardening/local-fuzz-receipt.json"
  verifier_log="$fixture/build/hardening/verifier-calls.tsv"
  export RELEASE_ATTESTATION_SELF_TEST_VERIFIER_LOG="$verifier_log"
  jq -n --arg hash "$fleet_hash" --arg commit "$candidate" \
    '{schema:2,kind:"fleet-canary-pointer",mode:"release",result:"passed",run_id:"run.A",
      plugin_sha:$commit,bundle:"fleet-runs/run.A/receipt.json",receipt_sha256:$hash}' > "$fleet_pointer"
  jq -n --arg hash "$fuzz_hash" --arg commit "$candidate" \
    '{schema:2,kind:"local-fuzz-pointer",mode:"release",result:"passed",run_id:"run.B",
      plugin_sha:$commit,bundle:"fuzz-runs/run.B/receipt.json",receipt_sha256:$hash}' > "$fuzz_pointer"
  valid_fleet_receipt=$(<"$fleet_receipt")
  valid_fuzz_receipt=$(<"$fuzz_receipt")
  valid_fleet_pointer=$(<"$fleet_pointer")
  valid_fuzz_pointer=$(<"$fuzz_pointer")
  expected_verifier_calls=$(printf 'fleet-canary-pointer\t%s\nlocal-fuzz-pointer\t%s' \
    "$fleet_pointer" "$fuzz_pointer")
  printf '%s\n' '{".":"1.0.1"}' > "$fixture/.release-please-manifest.json"
  printf '%s\n' '# Changelog' '' '## 1.0.1' > "$fixture/CHANGELOG.md"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add CHANGELOG.md .release-please-manifest.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'release metadata'
  )
  output="$fixture/release-attestations/1.0.1.json"
  failure_log="$fixture/build/hardening/release-attestation-failure.log"
  expect_create_failure() {
    local label=$1 expected=${2:-} fleet_input=${3:-$fleet_pointer} fuzz_input=${4:-$fuzz_pointer}
    local failure_output
    if "$fixture_script" create 1.0.1 \
        --fleet-pointer "$fleet_input" --fuzz-pointer "$fuzz_input" \
        > "$failure_log" 2>&1; then
      echo "release-attestation self-test: $label was accepted" >&2
      return 1
    fi
    if [ -e "$output" ] || [ -L "$output" ]; then
      echo "release-attestation self-test: $label left a release record" >&2
      return 1
    fi
    failure_output=$(<"$failure_log")
    if [ -n "$expected" ]; then
      case "$failure_output" in
        *"$expected"*) ;;
        *)
          echo "release-attestation self-test: $label failed for the wrong reason" >&2
          printf '%s\n' "$failure_output" >&2
          return 1
          ;;
      esac
    fi
  }
  expect_cli_failure() {
    local label=$1 expected=$2 failure_output
    shift 2
    if "$@" > "$failure_log" 2>&1; then
      echo "release-attestation self-test: $label was accepted" >&2
      return 1
    fi
    failure_output=$(<"$failure_log")
    case "$failure_output" in
      *"$expected"*) ;;
      *)
        echo "release-attestation self-test: $label failed for the wrong reason" >&2
        printf '%s\n' "$failure_output" >&2
        return 1
        ;;
    esac
  }
  restore_evidence() {
    printf '%s\n' "$valid_fleet_receipt" > "$fleet_receipt"
    printf '%s\n' "$valid_fuzz_receipt" > "$fuzz_receipt"
    printf '%s\n' "$valid_fleet_pointer" > "$fleet_pointer"
    printf '%s\n' "$valid_fuzz_pointer" > "$fuzz_pointer"
  }
  refresh_pointer_hash() {
    local receipt_file=$1 pointer_file=$2 pointer_json=$3 refreshed_hash
    refreshed_hash=$(sha256_file "$receipt_file") || return 1
    jq --arg hash "$refreshed_hash" '.receipt_sha256 = $hash' \
      <<< "$pointer_json" > "$pointer_file"
  }
  divergent=$(GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -C "$fixture" -c user.name=SelfTest -c user.email=self@test \
    commit-tree "$candidate_tree" -m divergent)
  git -C "$fixture" tag 1.0.1 "$divergent"
  if "$fixture_script" verify-pending-release >/dev/null 2>&1; then
    echo "release-attestation self-test: divergent existing tag suppressed the gate" >&2
    return 1
  fi
  git -C "$fixture" tag -d 1.0.1 >/dev/null
  if "$fixture_script" verify-pending-release >/dev/null 2>&1; then
    echo "release-attestation self-test: pending release passed without a record" >&2
    return 1
  fi

  : > "$verifier_log"
  printf '%s\n' dirty > "$fixture/untracked-before-create.txt"
  expect_create_failure "dirty checkout before create" "checkout must be clean"
  if [ -s "$verifier_log" ]; then
    echo "release-attestation self-test: dirty preflight invoked a receipt verifier" >&2
    return 1
  fi
  unlink "$fixture/untracked-before-create.txt"

  local pointer_link="$fixture/build/hardening/fleet-pointer-link.json"
  ln -s "$(basename "$fleet_pointer")" "$pointer_link"
  expect_create_failure "symlinked fleet pointer" "missing or symlinked fleet pointer" "$pointer_link"
  unlink "$pointer_link"

  local traversal_pointer="$fixture/build/hardening/fleet-traversal-pointer.json"
  jq '.bundle = "fleet-runs/run.A/../run.A/receipt.json"' \
    <<< "$valid_fleet_pointer" > "$traversal_pointer"
  expect_create_failure "traversing fleet bundle" \
    "fleet bundle escapes its pointer directory" "$traversal_pointer"

  local bad_hash_pointer="$fixture/build/hardening/fleet-bad-hash-pointer.json"
  jq '.receipt_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
    <<< "$valid_fleet_pointer" > "$bad_hash_pointer"
  expect_create_failure "mismatched fleet receipt hash" \
    "fleet bundle receipt hash does not match its pointer" "$bad_hash_pointer"

  local receipt_link="$fixture/build/hardening/fleet-runs/run.A/receipt-link.json"
  local receipt_link_pointer="$fixture/build/hardening/fleet-receipt-link-pointer.json"
  ln -s receipt.json "$receipt_link"
  jq '.bundle = "fleet-runs/run.A/receipt-link.json"' \
    <<< "$valid_fleet_pointer" > "$receipt_link_pointer"
  expect_create_failure "symlinked fleet bundle receipt" \
    "missing or symlinked fleet bundle receipt" "$receipt_link_pointer"
  unlink "$receipt_link"

  export RELEASE_ATTESTATION_SELF_TEST_REJECT_KIND=fleet-canary-pointer
  expect_create_failure "rejected fleet receipt verification" "fleet receipt verifier rejected"
  unset RELEASE_ATTESTATION_SELF_TEST_REJECT_KIND
  export RELEASE_ATTESTATION_SELF_TEST_REJECT_KIND=local-fuzz-pointer
  expect_create_failure "rejected fuzz receipt verification" "fuzz receipt verifier rejected"
  unset RELEASE_ATTESTATION_SELF_TEST_REJECT_KIND

  : > "$verifier_log"
  export RELEASE_ATTESTATION_SELF_TEST_DIRTY_KIND=local-fuzz-pointer
  export RELEASE_ATTESTATION_SELF_TEST_DIRTY_FILE="$fixture/untracked-during-create.txt"
  expect_create_failure "checkout dirtied during receipt verification" "checkout must be clean"
  unset RELEASE_ATTESTATION_SELF_TEST_DIRTY_KIND RELEASE_ATTESTATION_SELF_TEST_DIRTY_FILE
  unlink "$fixture/untracked-during-create.txt"
  actual_verifier_calls=$(<"$verifier_log")
  if [ "$actual_verifier_calls" != "$expected_verifier_calls" ]; then
    echo "release-attestation self-test: post-verifier clean guard ran before both verifiers" >&2
    return 1
  fi

  jq '.plugin.origin = "https://github.com/example/not-sava-build.git"' \
    <<< "$valid_fleet_receipt" > "$fleet_receipt"
  jq '.plugin.origin = "https://github.com/example/not-sava-build.git"' \
    <<< "$valid_fuzz_receipt" > "$fuzz_receipt"
  refresh_pointer_hash "$fleet_receipt" "$fleet_pointer" "$valid_fleet_pointer"
  refresh_pointer_hash "$fuzz_receipt" "$fuzz_pointer" "$valid_fuzz_pointer"
  expect_create_failure "wrong certified plugin origin" "certified origin is"
  restore_evidence

  jq '.manifest_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
    <<< "$valid_fleet_receipt" > "$fleet_receipt"
  jq '.manifest_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"' \
    <<< "$valid_fuzz_receipt" > "$fuzz_receipt"
  refresh_pointer_hash "$fleet_receipt" "$fleet_pointer" "$valid_fleet_pointer"
  refresh_pointer_hash "$fuzz_receipt" "$fuzz_pointer" "$valid_fuzz_pointer"
  expect_create_failure "wrong certified fleet manifest" "fleet manifest changed after certification"
  restore_evidence

  jq --arg sha "$candidate" \
    '.repositories += [{slug:"sava-software/second",sha:$sha,
      origin:"git@github.com:sava-software/second.git"}]' \
    <<< "$valid_fleet_receipt" > "$fleet_receipt"
  jq --arg sha "$candidate" \
    '.repositories += [{slug:"sava-software/second",sha:$sha,
      origin:"git@github.com:sava-software/second.git"}]' \
    <<< "$valid_fuzz_receipt" > "$fuzz_receipt"
  refresh_pointer_hash "$fleet_receipt" "$fleet_pointer" "$valid_fleet_pointer"
  refresh_pointer_hash "$fuzz_receipt" "$fuzz_pointer" "$valid_fuzz_pointer"
  expect_create_failure "receipt roster count mismatch" \
    "receipt repository counts do not match the current manifest"
  restore_evidence

  jq --arg sha "1111111111111111111111111111111111111111" \
    '.repositories[0].sha = $sha' "$fuzz_receipt" > "$fuzz_receipt.tmp"
  mv "$fuzz_receipt.tmp" "$fuzz_receipt"
  fuzz_hash=$(sha256_file "$fuzz_receipt")
  jq --arg hash "$fuzz_hash" '.receipt_sha256 = $hash' "$fuzz_pointer" > "$fuzz_pointer.tmp"
  mv "$fuzz_pointer.tmp" "$fuzz_pointer"
  expect_create_failure "mismatched consumer inventories" \
    "fleet and fuzz receipts certify different candidates"
  restore_evidence
  : > "$verifier_log"
  "$fixture_script" create 1.0.1 --fleet-pointer "$fleet_pointer" --fuzz-pointer "$fuzz_pointer"
  [ -f "$output" ] || { echo "release-attestation self-test: create wrote no record" >&2; return 1; }
  if "$fixture_script" create 1.0.1 --fleet-pointer "$fleet_pointer" \
      --fuzz-pointer "$fuzz_pointer" >/dev/null 2>&1; then
    echo "release-attestation self-test: create overwrote a release record" >&2
    return 1
  fi
  actual_verifier_calls=$(<"$verifier_log")
  if [ "$actual_verifier_calls" != "$expected_verifier_calls" ]; then
    echo "release-attestation self-test: create did not invoke each full verifier exactly once" >&2
    printf 'expected:\n%s\nactual:\n%s\n' "$expected_verifier_calls" "$actual_verifier_calls" >&2
    return 1
  fi
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm attestation
  )
  printf '%s\n' dirty > "$fixture/untracked-before-verify.txt"
  expect_cli_failure "dirty checkout before verify" "checkout must be clean" \
    "$fixture_script" verify 1.0.1
  unlink "$fixture/untracked-before-verify.txt"
  "$fixture_script" verify 1.0.1 >/dev/null
  "$fixture_script" verify-pending-release >/dev/null
  valid_attestation=$(<"$output")

  chmod +x "$output"
  (
    cd "$fixture"
    git add release-attestations/1.0.1.json
    git -c user.name=SelfTest -c user.email=self@test commit -qm 'executable attestation'
  )
  expect_cli_failure "executable committed attestation" \
    "release record is not a tracked regular file" "$fixture_script" verify 1.0.1
  chmod -x "$output"
  (
    cd "$fixture"
    git add release-attestations/1.0.1.json
    git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore attestation mode'
  )
  "$fixture_script" verify 1.0.1 >/dev/null

  jq '.unexpected = true' "$output" > "$output.tmp"
  mv "$output.tmp" "$output"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'malformed attestation'
  )
  expect_cli_failure "malformed committed attestation" "invalid release record schema" \
    "$fixture_script" verify 1.0.1
  printf '%s\n' "$valid_attestation" > "$output"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore attestation'
  )
  "$fixture_script" verify 1.0.1 >/dev/null
  git -C "$fixture" tag 1.0.1
  "$fixture_script" verify-tag 1.0.1 >/dev/null
  "$fixture_script" verify-pending-release >/dev/null
  local built_jar="$fixture/build/libs/sava-build.jar"
  mkdir -p "$(dirname "$built_jar")"
  printf '%s' 'plugin jar' > "$built_jar"
  "$fixture_script" verify-built-jar 1.0.1 "$built_jar" >/dev/null
  printf '%s' 'different plugin jar' > "$built_jar"
  expect_cli_failure "built JAR differs from certified candidate" \
    "built plugin JAR does not match the certified candidate" \
    "$fixture_script" verify-built-jar 1.0.1 "$built_jar"
  unlink "$built_jar"
  expect_cli_failure "built JAR is missing" "missing or symlinked built plugin JAR" \
    "$fixture_script" verify-built-jar 1.0.1 "$built_jar"
  printf '%s' 'plugin jar' > "$built_jar.real"
  ln -s "$(basename "$built_jar.real")" "$built_jar"
  expect_cli_failure "built JAR is a symlink" "missing or symlinked built plugin JAR" \
    "$fixture_script" verify-built-jar 1.0.1 "$built_jar"
  unlink "$built_jar"
  printf '%s\n' dirty > "$fixture/untracked-before-pending-verify.txt"
  expect_cli_failure "dirty already-tagged pending-release checkout" "checkout must be clean" \
    "$fixture_script" verify-pending-release
  unlink "$fixture/untracked-before-pending-verify.txt"
  printf '%s\n' '# Changelog' '' '## 1.0.1' '' 'release note correction' > "$fixture/CHANGELOG.md"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add CHANGELOG.md
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'allowed release metadata'
  )
  "$fixture_script" verify 1.0.1 >/dev/null
  if "$fixture_script" verify-tag 1.0.1 >/dev/null 2>&1; then
    echo "release-attestation self-test: a tag pointing behind HEAD was accepted" >&2
    return 1
  fi
  printf '%s\n' 'post-certification source change' > "$fixture/source.txt"
  (
    cd "$fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add source.txt
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm source
  )
  if "$fixture_script" verify 1.0.1 >/dev/null 2>&1; then
    echo "release-attestation self-test: post-certification source change was accepted" >&2
    return 1
  fi

  # The normal release path records the already-reviewed local adoption cohort and
  # the exact candidate JAR. Keep this fixture independent from the optional legacy
  # fleet/fuzz receipt path above so neither mode can make the other's assertions pass.
  local reviewed_fixture="$fixture/reviewed" reviewed_script reviewed_candidate reviewed_tree
  local reviewed_divergent
  local reviewed_jar reviewed_output valid_reviewed_attestation
  mkdir -p "$reviewed_fixture/tools" "$reviewed_fixture/release-attestations" \
    "$reviewed_fixture/build/libs"
  cp "$script" "$reviewed_fixture/tools/release-attestation.sh"
  reviewed_script="$reviewed_fixture/tools/release-attestation.sh"
  chmod +x "$reviewed_script"
  printf '%s\n' '{".":"1.0.0"}' > "$reviewed_fixture/.release-please-manifest.json"
  printf '%s\n' '# Release attestations' > "$reviewed_fixture/release-attestations/README.md"
  printf '%s\n' 'build/' > "$reviewed_fixture/.gitignore"
  printf '%s\n' '# Changelog' > "$reviewed_fixture/CHANGELOG.md"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git init -q
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git remote add origin git@github.com:sava-software/sava-build.git
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test add .
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm candidate
  )
  reviewed_candidate=$(git -C "$reviewed_fixture" rev-parse HEAD)
  reviewed_tree=$(git -C "$reviewed_fixture" rev-parse 'HEAD^{tree}')
  reviewed_divergent=$(GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
    git -C "$reviewed_fixture" -c user.name=SelfTest -c user.email=self@test \
    commit-tree "$reviewed_tree" -m divergent)
  printf '%s\n' '{".":"1.0.1"}' > "$reviewed_fixture/.release-please-manifest.json"
  printf '%s\n' '# Changelog' '' '## 1.0.1' > "$reviewed_fixture/CHANGELOG.md"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add CHANGELOG.md .release-please-manifest.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'release metadata'
  )
  reviewed_jar="$reviewed_fixture/build/libs/sava-build-1.0.1.jar"
  printf '%s' 'plugin jar' > "$reviewed_jar"
  reviewed_output="$reviewed_fixture/release-attestations/1.0.1.json"

  expect_cli_failure "reviewed attestation without an adoption" \
    "requires at least one --adoption" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar"
  expect_cli_failure "short reviewed candidate" "not a full commit" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "${reviewed_candidate:0:12}" \
      --plugin-jar "$reviewed_jar" --adoption sava-software/sava
  expect_cli_failure "non-ancestor reviewed candidate" "not an ancestor" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_divergent" \
      --plugin-jar "$reviewed_jar" --adoption sava-software/sava
  expect_cli_failure "invalid reviewed adoption slug" "invalid reviewed adoption slug" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption not-a-slug
  expect_cli_failure "duplicate reviewed adoption" "duplicate reviewed adoption slug" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption sava-software/sava \
      --adoption sava-software/sava
  expect_cli_failure "missing reviewed plugin JAR" "missing or symlinked reviewed plugin JAR" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar.missing" --adoption sava-software/sava
  ln -s "$(basename "$reviewed_jar")" "$reviewed_jar.link"
  expect_cli_failure "symlinked reviewed plugin JAR" "missing or symlinked reviewed plugin JAR" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar.link" --adoption sava-software/sava
  unlink "$reviewed_jar.link"
  git -C "$reviewed_fixture" remote set-url origin git@github.com:example/not-sava-build.git
  expect_cli_failure "wrong reviewed candidate origin" "candidate origin is" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption sava-software/sava
  git -C "$reviewed_fixture" remote set-url origin git@github.com:sava-software/sava-build.git
  "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
    --plugin-jar "$reviewed_jar" --adoption sava-software/ravina \
    --adoption sava-software/sava >/dev/null
  jq -e --arg candidate "$reviewed_candidate" --arg tree "$reviewed_tree" --arg jar "$jar_hash" '
    .schema == 2 and .candidate.commit == $candidate and
    .candidate.tree == $tree and
    .candidate.plugin_jar_sha256 == $jar and
    .review.kind == "reviewed-local-adoption-passes" and
    .review.repositories == ["sava-software/ravina","sava-software/sava"]
  ' "$reviewed_output" >/dev/null
  expect_cli_failure "reviewed attestation overwrite" "refusing to overwrite existing release record" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption sava-software/sava
  valid_reviewed_attestation=$(<"$reviewed_output")
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm attestation
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  "$reviewed_script" verify-pending-release >/dev/null
  jq '.review.repositories += [.review.repositories[0]]' \
    "$reviewed_output" > "$reviewed_output.tmp"
  mv "$reviewed_output.tmp" "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'duplicate review record'
  )
  expect_cli_failure "duplicate adoption in committed review" "invalid release record schema" \
    "$reviewed_script" verify 1.0.1
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore reviewed attestation'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  jq '.review.repositories |= reverse' "$reviewed_output" > "$reviewed_output.tmp"
  mv "$reviewed_output.tmp" "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'unsorted review record'
  )
  expect_cli_failure "unsorted adoption in committed review" "invalid release record schema" \
    "$reviewed_script" verify 1.0.1
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore sorted review record'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  printf '%s\n' 'post-review source change' > "$reviewed_fixture/source.txt"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add source.txt
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'post-review source'
  )
  expect_cli_failure "post-review source change" "non-release file changed after candidate review" \
    "$reviewed_script" verify 1.0.1
  unlink "$reviewed_fixture/source.txt"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git add -u source.txt
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'remove post-review source'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  git -C "$reviewed_fixture" tag 1.0.1
  "$reviewed_script" verify-tag 1.0.1 >/dev/null
  "$reviewed_script" verify-built-jar 1.0.1 "$reviewed_jar" >/dev/null
  printf '%s' 'different reviewed jar' > "$reviewed_jar"
  expect_cli_failure "reviewed built JAR differs" \
    "built plugin JAR does not match the certified candidate" \
    "$reviewed_script" verify-built-jar 1.0.1 "$reviewed_jar"
  echo "release-attestation: self-test passed"
}

require_tools
command_name=${1:-}
case "$command_name" in
  create-reviewed)
    shift
    version=${1:-}
    [ -n "$version" ] || { usage >&2; exit 2; }
    shift
    candidate=""
    plugin_jar=""
    adoptions=()
    adoption_count=0
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --candidate) shift; [ "$#" -gt 0 ] || { usage >&2; exit 2; }; candidate=$1 ;;
        --plugin-jar) shift; [ "$#" -gt 0 ] || { usage >&2; exit 2; }; plugin_jar=$1 ;;
        --adoption)
          shift
          [ "$#" -gt 0 ] || { usage >&2; exit 2; }
          adoptions+=("$1")
          adoption_count=$((adoption_count + 1))
          ;;
        *) echo "release-attestation: unknown create-reviewed option: $1" >&2; usage >&2; exit 2 ;;
      esac
      shift
    done
    [ -n "$candidate" ] && [ -n "$plugin_jar" ] || { usage >&2; exit 2; }
    if [ "$adoption_count" -eq 0 ]; then
      echo "release-attestation: create-reviewed requires at least one --adoption github-org/repo" >&2
      exit 2
    fi
    create_reviewed_attestation "$version" "$candidate" "$plugin_jar" "${adoptions[@]}"
    ;;
  create)
    shift
    version=${1:-}
    [ -n "$version" ] || { usage >&2; exit 2; }
    shift
    fleet_pointer=$default_fleet_pointer
    fuzz_pointer=$default_fuzz_pointer
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --fleet-pointer) shift; [ "$#" -gt 0 ] || { usage >&2; exit 2; }; fleet_pointer=$1 ;;
        --fuzz-pointer) shift; [ "$#" -gt 0 ] || { usage >&2; exit 2; }; fuzz_pointer=$1 ;;
        *) echo "release-attestation: unknown create option: $1" >&2; usage >&2; exit 2 ;;
      esac
      shift
    done
    create_attestation "$version" "$fleet_pointer" "$fuzz_pointer"
    ;;
  verify)
    [ "$#" -eq 2 ] || { usage >&2; exit 2; }
    verify_attestation "$2"
    ;;
  verify-tag)
    [ "$#" -eq 2 ] || { usage >&2; exit 2; }
    verify_tag "$2"
    ;;
  verify-built-jar)
    [ "$#" -eq 3 ] || { usage >&2; exit 2; }
    verify_built_jar "$2" "$3"
    ;;
  verify-pending-release)
    [ "$#" -eq 1 ] || { usage >&2; exit 2; }
    verify_pending_release
    ;;
  --self-test)
    [ "$#" -eq 1 ] || { usage >&2; exit 2; }
    self_test
    ;;
  --help|-h) usage ;;
  *) usage >&2; exit 2 ;;
esac
