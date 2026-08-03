#!/usr/bin/env bash
# Run every registered consumer fuzz target against this sava-build checkout.
#
# Release evidence is stored in an immutable run directory below
# build/hardening/local-fuzz-runs. The canonical receipt is only an atomic
# pointer; starting another release attempt changes it to in_progress before
# any preflight or publication, so an interrupted rerun cannot leave an older
# pass looking current.
set -euo pipefail

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd -P)
manifest="$sava_build_dir/tools/fleet-manifest.txt"
local_repo="$sava_build_dir/build/sava-test-repo"
canonical_receipt="$sava_build_dir/build/hardening/local-fuzz-receipt.json"
ordinary_receipt="$sava_build_dir/build/hardening/local-fuzz-latest.json"
plugin_expected_slug="sava-software/sava-build"
resolution_notice="resolved every 'software.sava.build*' plugin to 0.0.0-test"

usage() {
  cat <<'EOF'
Usage:
  tools/local-fuzz.sh [--seconds <positive-int>] [--receipt <path>] [<consumer-dir> ...]
  tools/local-fuzz.sh --release --seconds <positive-int> [--receipt <path>]
  tools/local-fuzz.sh --verify-receipt <path>
  tools/local-fuzz.sh --self-test

Ordinary no-argument mode runs available manifest siblings and defaults to 60
seconds per target. It may fall back to discovered fuzz<Target> tasks for an
older consumer contract, or run help when no target exists.

Release mode requires an explicit bounded budget, the complete clean fleet,
matching GitHub remotes, fuzzAll, and at least one registered target per repo.
It retains and hashes the publish log, consumer logs, and plugin-generated
local-fuzz.tsv receipts in a run-specific evidence bundle.
EOF
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

require_jq() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "local-fuzz: jq is required for evidence bundles" >&2
    exit 2
  fi
}

manifest_slugs() {
  awk 'NF && $1 !~ /^#/ { print $1 }' "$manifest"
}

origin_slug() {
  local url=$1
  url=$(printf '%s\n' "$url" | sed 's/[.]git$//')
  case "$url" in
    git@github.com:*) printf '%s\n' "$url" | sed 's|^git@github.com:||' ;;
    https://github.com/*) printf '%s\n' "$url" | sed 's|^https://github.com/||' ;;
    ssh://git@github.com/*) printf '%s\n' "$url" | sed 's|^ssh://git@github.com/||' ;;
    *) printf '%s\n' "" ;;
  esac
}

registered_fuzz_tasks() {
  awk '
    $1 !~ /^-/ {
      name=$1
      sub(/^.*:/, "", name)
      if (name !~ /^fuzz./ || name == "fuzzAll" || name == "fuzzAllPreflight" ||
          name == "fuzzWorkflowInSync" ||
          name ~ /Minimize$/ || name ~ /SeedLenCheck$/) next
      print name
    }
  ' "$1" | sort -u
}

has_fuzz_all() {
  awk '
    {
      name=$1
      sub(/^.*:/, "", name)
      if (name == "fuzzAll") found=1
    }
    END { exit found ? 0 : 1 }
  ' "$1"
}

reject_symlink_components() {
  local target=$1 label=$2 probe
  case "$target" in
    /*) probe=$target ;;
    *) probe="$PWD/$target" ;;
  esac
  while [ "$probe" != "/" ]; do
    if [ -L "$probe" ]; then
      echo "local-fuzz: refusing symlinked $label component: $probe" >&2
      return 1
    fi
    probe=$(dirname "$probe")
  done
}

prepare_pointer_destination() {
  local raw=$1 raw_parent base physical_parent rel
  case "$raw" in /*) ;; *) raw="$PWD/$raw" ;; esac
  raw_parent=$(dirname "$raw")
  base=$(basename "$raw")
  case "$base" in
    ''|.|..) echo "local-fuzz: invalid receipt filename '$base'" >&2; return 1 ;;
  esac
  reject_symlink_components "$raw_parent" "receipt directory" || return 1
  mkdir -p "$raw_parent"
  physical_parent=$(cd "$raw_parent" && pwd -P)
  receipt_path="$physical_parent/$base"
  receipt_parent=$physical_parent
  reject_symlink_components "$receipt_path" "receipt" || return 1
  case "$receipt_path" in
    */local-fuzz-runs/*)
      echo "local-fuzz: --receipt must name a pointer outside immutable run bundles" >&2
      return 1
      ;;
  esac
  case "$receipt_path" in
    "$sava_build_dir"/*)
      rel=$(printf '%s\n' "$receipt_path" | sed "s|^$sava_build_dir/||")
      if ! git -C "$sava_build_dir" check-ignore -q -- "$rel"; then
        echo "local-fuzz: receipt inside the plugin checkout must be git-ignored: $rel" >&2
        return 1
      fi
      ;;
  esac
  if [ -e "$receipt_path" ] && ! jq -e '
      (.schema == 1 and (.mode == "ordinary" or .mode == "release")) or
      (.schema == 2 and .kind == "local-fuzz-pointer")
    ' "$receipt_path" >/dev/null 2>&1; then
    echo "local-fuzz: refusing to replace a file that is not local-fuzz evidence: $receipt_path" >&2
    return 1
  fi
}

write_pointer() {
  local result=$1 receipt_hash=$2 tmp generated_at
  reject_symlink_components "$receipt_path" "receipt" || return 1
  if [ "$result" != in_progress ] &&
      ! jq -e --arg run_id "$run_id" \
        '.schema == 2 and .kind == "local-fuzz-pointer" and .run_id == $run_id' \
        "$receipt_path" >/dev/null 2>&1; then
    echo "local-fuzz: run $run_id was superseded; refusing to replace the newer pointer" >&2
    return 1
  fi
  generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  tmp=$(mktemp "$receipt_parent/.local-fuzz-pointer.XXXXXX")
  jq -n \
    --arg mode "$receipt_mode" --arg result "$result" --arg generated_at "$generated_at" \
    --arg run_id "$run_id" --arg bundle "$bundle_rel" \
    --arg receipt_sha256 "$receipt_hash" --arg plugin_sha "$plugin_sha" \
    '{schema:2,kind:"local-fuzz-pointer",mode:$mode,result:$result,
      generated_at:$generated_at,run_id:$run_id,plugin_sha:$plugin_sha,
      bundle:$bundle,receipt_sha256:$receipt_sha256}' > "$tmp"
  mv "$tmp" "$receipt_path"
}

validate_aggregate_receipt() {
  local file=$1 expected_seconds=$2
  awk -F '\t' -v expected="$expected_seconds" '
    $1 == "schema" {
      schemaRows++; if (NF != 2 || $2 != "1") invalid=1; next
    }
    $1 == "project" {
      projectRows++; if (NF != 2 || $2 == "") invalid=1; next
    }
    $1 == "maxFuzzTimeSeconds" {
      budgetRows++; if (NF != 2 || $2 != expected) invalid=1; next
    }
    $1 == "target" {
      targets++
      if (NF != 2 || $2 !~ /^fuzz./ || seen[$2]++) invalid=1
      next
    }
    NF > 0 { invalid=1 }
    END {
      if (invalid || schemaRows != 1 || projectRows != 1 || budgetRows != 1) exit 1
      print targets + 0
    }
  ' "$file"
}

aggregate_target_names() {
  awk -F '\t' '$1 == "target" { print $2 }' "$1" | sort -u
}

resolve_receipt() {
  local requested=$1 input_parent pointer_bundle expected target
  pointer_run_id=""
  pointer_plugin_sha=""
  case "$requested" in /*) ;; *) requested="$PWD/$requested" ;; esac
  reject_symlink_components "$requested" "receipt" || return 1
  if [ ! -f "$requested" ]; then
    echo "local-fuzz: no certification receipt at $requested" >&2
    return 1
  fi
  if jq -e '.schema == 2 and .kind == "local-fuzz-pointer"' "$requested" >/dev/null 2>&1; then
    if ! jq -e '
        .mode == "release" and .result == "passed" and
        (.run_id | test("^run[.][A-Za-z0-9]+$")) and
        .bundle == ("local-fuzz-runs/" + .run_id + "/receipt.json") and
        (.plugin_sha | test("^[0-9a-f]{40}$")) and
        (.receipt_sha256 | test("^[0-9a-f]{64}$"))
      ' "$requested" >/dev/null; then
      echo "local-fuzz: canonical pointer is not a completed passing release run: $requested" >&2
      return 1
    fi
    input_parent=$(cd "$(dirname "$requested")" && pwd -P)
    pointer_bundle=$(jq -r '.bundle' "$requested")
    target="$input_parent/$pointer_bundle"
    reject_symlink_components "$target" "bundle receipt" || return 1
    if [ ! -f "$target" ]; then
      echo "local-fuzz: pointed-to bundle receipt is missing: $target" >&2
      return 1
    fi
    expected=$(jq -r '.receipt_sha256' "$requested")
    if [ "$(sha256_file "$target")" != "$expected" ]; then
      echo "local-fuzz: bundle receipt hash does not match the canonical pointer: $target" >&2
      return 1
    fi
    pointer_run_id=$(jq -r '.run_id' "$requested")
    pointer_plugin_sha=$(jq -r '.plugin_sha' "$requested")
    resolved_receipt=$target
  else
    resolved_receipt=$requested
  fi
  bundle_dir=$(cd "$(dirname "$resolved_receipt")" && pwd -P)
}

verify_artifact() {
  local relative=$1 expected=$2 label=$3 path
  path="$bundle_dir/$relative"
  reject_symlink_components "$path" "$label" || return 1
  if [ ! -f "$path" ]; then
    echo "local-fuzz: missing $label: $path" >&2
    return 1
  fi
  if [ "$(sha256_file "$path")" != "$expected" ]; then
    echo "local-fuzz: $label hash mismatch: $path" >&2
    return 1
  fi
}

verify_receipt() {
  local requested=$1 certified_sha certified_tree actual_tree plugin_origin
  local manifest_count receipt_count unexpected changed slug log_file log_hash
  local aggregate_file aggregate_hash aggregate_targets actual_targets
  local recorded_names actual_names
  local repo recorded_sha recorded_origin current_sha current_origin
  require_jq
  resolve_receipt "$requested" || return 1
  if ! jq -e '
      .schema == 2 and .kind == "local-fuzz-receipt" and
      .mode == "release" and .result == "passed" and
      (.run_id | test("^run[.][A-Za-z0-9]+$")) and
      (.seconds_per_target | type == "number" and . > 0 and . <= 2147483647) and
      (.plugin.sha | test("^[0-9a-f]{40}$")) and
      (.plugin.tree | test("^[0-9a-f]{40,64}$")) and
      (.plugin.origin | type == "string" and length > 0) and
      .plugin.dirty_before == false and .plugin.dirty_after == false and
      (.manifest_sha256 | test("^[0-9a-f]{64}$")) and
      .preflight_file == "preflight.tsv" and
      (.preflight_sha256 | test("^[0-9a-f]{64}$")) and
      .publish_log_file == "plugin-publish.log" and
      (.publish_output_sha256 | test("^[0-9a-f]{64}$")) and
      (.repositories | type == "array" and length > 0) and
      all(.repositories[];
        .result == "passed" and .dirty_before == false and .dirty_after == false and
        (.sha | test("^[0-9a-f]{40}$")) and
        (.path | type == "string") and
        (.origin | type == "string" and length > 0) and
        .task_mode == "aggregate" and
        (.tasks | type == "array" and any(.[]; . == "fuzzAll")) and
        (.registered_targets | type == "array" and length > 0) and
        (.log_file | test("^logs/[A-Za-z0-9._-]+[.]log$")) and
        (.output_sha256 | test("^[0-9a-f]{64}$")) and
        (.aggregates | type == "array" and length > 0) and
        ([.aggregates[].target_count] | add > 0) and
        (([.aggregates[].targets[]] | unique) == (.registered_targets | unique)) and
        all(.aggregates[];
          (.path | test("^aggregates/[A-Za-z0-9._/-]+[.]tsv$")) and
          (.sha256 | test("^[0-9a-f]{64}$")) and
          (.target_count | type == "number" and . >= 0) and
          (.targets | type == "array") and (.targets | length) == .target_count and
          all(.targets[]; type == "string" and test("^fuzz.")) and
          ((.targets | unique | length) == (.targets | length))))
    ' "$resolved_receipt" >/dev/null; then
    echo "local-fuzz: receipt is not a successful strict evidence bundle: $resolved_receipt" >&2
    return 1
  fi
  if [ -n "$pointer_run_id" ] &&
      { [ "$(jq -r '.run_id' "$resolved_receipt")" != "$pointer_run_id" ] ||
        [ "$(jq -r '.plugin.sha' "$resolved_receipt")" != "$pointer_plugin_sha" ]; }; then
    echo "local-fuzz: pointer identity does not match its bundle receipt" >&2
    return 1
  fi

  certified_sha=$(jq -r '.plugin.sha' "$resolved_receipt")
  certified_tree=$(jq -r '.plugin.tree' "$resolved_receipt")
  if ! git -C "$sava_build_dir" cat-file -e "$certified_sha^{commit}" 2>/dev/null; then
    echo "local-fuzz: certified plugin commit $certified_sha is not in this checkout" >&2
    return 1
  fi
  actual_tree=$(git -C "$sava_build_dir" rev-parse "$certified_sha^{tree}")
  if [ "$actual_tree" != "$certified_tree" ]; then
    echo "local-fuzz: receipt tree $certified_tree does not belong to $certified_sha" >&2
    return 1
  fi
  if ! git -C "$sava_build_dir" merge-base --is-ancestor "$certified_sha" HEAD; then
    echo "local-fuzz: certified commit $certified_sha is not an ancestor of HEAD" >&2
    return 1
  fi
  if [ -n "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" ]; then
    echo "local-fuzz: current plugin checkout is dirty; it cannot match release evidence" >&2
    return 1
  fi
  plugin_origin=$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)
  if [ "$plugin_origin" != "$(jq -r '.plugin.origin' "$resolved_receipt")" ] ||
      [ "$(origin_slug "$plugin_origin")" != "$plugin_expected_slug" ]; then
    echo "local-fuzz: plugin origin changed after certification: $plugin_origin" >&2
    return 1
  fi
  unexpected=""
  while IFS= read -r changed; do
    case "$changed" in
      .release-please-manifest.json|CHANGELOG.md) ;;
      *)
        if [ -n "$unexpected" ]; then unexpected="$unexpected
$changed"; else unexpected=$changed; fi
        ;;
    esac
  done < <(git -C "$sava_build_dir" diff --name-only "$certified_sha"..HEAD --)
  if [ -n "$unexpected" ]; then
    echo "local-fuzz: non-release-metadata files changed after certification:" >&2
    while IFS= read -r changed; do printf '  %s\n' "$changed" >&2; done <<< "$unexpected"
    return 1
  fi

  if [ "$(jq -r '.manifest_sha256' "$resolved_receipt")" != "$(sha256_file "$manifest")" ]; then
    echo "local-fuzz: fleet manifest changed after certification" >&2
    return 1
  fi
  manifest_count=$(manifest_slugs | wc -l | tr -d ' ')
  receipt_count=$(jq '.repositories | length' "$resolved_receipt")
  if [ "$manifest_count" != "$receipt_count" ]; then
    echo "local-fuzz: receipt covers $receipt_count repos; manifest requires $manifest_count" >&2
    return 1
  fi
  while IFS= read -r slug; do
    if ! jq -e --arg slug "$slug" '[.repositories[] | select(.slug == $slug)] | length == 1' \
        "$resolved_receipt" >/dev/null; then
      echo "local-fuzz: receipt does not contain exactly one record for $slug" >&2
      return 1
    fi
  done < <(manifest_slugs)

  verify_artifact "preflight.tsv" "$(jq -r '.preflight_sha256' "$resolved_receipt")" \
    "preflight inventory" || return 1
  verify_artifact "plugin-publish.log" "$(jq -r '.publish_output_sha256' "$resolved_receipt")" \
    "plugin publish log" || return 1
  while IFS=$'\t' read -r log_file log_hash; do
    verify_artifact "$log_file" "$log_hash" "consumer fuzz log" || return 1
  done < <(jq -r '.repositories[] | [.log_file,.output_sha256] | @tsv' "$resolved_receipt")
  while IFS=$'\t' read -r aggregate_file aggregate_hash aggregate_targets; do
    verify_artifact "$aggregate_file" "$aggregate_hash" "inner fuzz receipt" || return 1
    actual_targets=$(validate_aggregate_receipt "$bundle_dir/$aggregate_file" \
      "$(jq -r '.seconds_per_target' "$resolved_receipt")") || {
      echo "local-fuzz: invalid inner fuzz receipt: $bundle_dir/$aggregate_file" >&2
      return 1
    }
    if [ "$actual_targets" != "$aggregate_targets" ]; then
      echo "local-fuzz: inner fuzz receipt target count changed: $bundle_dir/$aggregate_file" >&2
      return 1
    fi
    actual_names=$(aggregate_target_names "$bundle_dir/$aggregate_file")
    recorded_names=$(jq -r --arg path "$aggregate_file" \
      '.repositories[].aggregates[] | select(.path == $path) | .targets[]' "$resolved_receipt")
    if [ "$actual_names" != "$recorded_names" ]; then
      echo "local-fuzz: inner fuzz receipt target names changed: $bundle_dir/$aggregate_file" >&2
      return 1
    fi
  done < <(jq -r '.repositories[].aggregates[] | [.path,.sha256,.target_count] | @tsv' \
    "$resolved_receipt")

  while IFS=$'\t' read -r slug repo recorded_sha recorded_origin; do
    if [ ! -e "$repo" ]; then
      echo "local-fuzz: NOTE $slug checkout unavailable; retained artifacts were verified" >&2
      continue
    fi
    if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      echo "local-fuzz: current checkout for $slug is not a Git worktree: $repo" >&2
      return 1
    fi
    current_sha=$(git -C "$repo" rev-parse HEAD)
    current_origin=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
    if [ "$current_sha" != "$recorded_sha" ]; then
      echo "local-fuzz: $slug moved from certified $recorded_sha to $current_sha" >&2
      return 1
    fi
    if [ "$current_origin" != "$recorded_origin" ] ||
        [ "$(origin_slug "$current_origin")" != "$slug" ]; then
      echo "local-fuzz: $slug origin changed after certification: $current_origin" >&2
      return 1
    fi
    if [ -n "$(git -C "$repo" status --porcelain --untracked-files=all)" ]; then
      echo "local-fuzz: $slug checkout is dirty after certification" >&2
      return 1
    fi
  done < <(jq -r '.repositories[] | [.slug,.path,.sha,.origin] | @tsv' "$resolved_receipt")

  echo "local-fuzz: verified $receipt_count-repo fuzz bundle for plugin $certified_sha"
}

self_test() {
  local fixture aggregate targets slugs basenames count
  fixture=$(mktemp)
  aggregate=$(mktemp)
  trap 'rm -f "$fixture" "$aggregate"' RETURN
  printf '%s\n' \
    "fuzzAll - Runs every target" \
    "fuzzAllPreflight - Invalidates old receipts" \
    "fuzzCodec - Runs codec" \
    ":wire:fuzzWire - Runs wire" \
    "fuzzCodecMinimize - Minimizes codec" \
    "fuzzWireSeedLenCheck - Checks seeds" \
    "fuzzWorkflowInSync - Compatibility" > "$fixture"
  has_fuzz_all "$fixture" || { echo "local-fuzz self-test: fuzzAll not found" >&2; return 1; }
  targets=$(registered_fuzz_tasks "$fixture")
  if [ "$targets" != "fuzzCodec
fuzzWire" ]; then
    echo "local-fuzz self-test: target parser produced: $targets" >&2
    return 1
  fi
  printf '%s\n' \
    "schema	1" \
    "project	:" \
    "maxFuzzTimeSeconds	17" \
    "target	fuzzCodec" \
    "target	fuzzWire" > "$aggregate"
  count=$(validate_aggregate_receipt "$aggregate" 17)
  [ "$count" = 2 ] || { echo "local-fuzz self-test: aggregate count was $count" >&2; return 1; }
  if validate_aggregate_receipt "$aggregate" 18 >/dev/null 2>&1; then
    echo "local-fuzz self-test: wrong budget was accepted" >&2
    return 1
  fi
  printf 'target\tfuzzCodec\n' >> "$aggregate"
  if validate_aggregate_receipt "$aggregate" 17 >/dev/null 2>&1; then
    echo "local-fuzz self-test: duplicate target evidence was accepted" >&2
    return 1
  fi
  [ "$(origin_slug git@github.com:sava-software/sava.git)" = "sava-software/sava" ] || return 1
  slugs=$(manifest_slugs)
  [ "$(printf '%s\n' "$slugs" | sort | uniq -d | wc -l | tr -d ' ')" = 0 ] || {
    echo "local-fuzz self-test: duplicate manifest slug" >&2; return 1;
  }
  basenames=$(printf '%s\n' "$slugs" | sed 's|.*/||')
  [ "$(printf '%s\n' "$basenames" | sort | uniq -d | wc -l | tr -d ' ')" = 0 ] || {
    echo "local-fuzz self-test: sibling checkout basename collision" >&2; return 1;
  }
  echo "local-fuzz: self-test passed"
}

release_mode=false
seconds=60
seconds_set=false
receipt_set=false
receipt_path=""
verify_path=""
self_test_requested=false
consumer_count=0
consumer_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --release) release_mode=true ;;
    --seconds)
      shift
      [ "$#" -gt 0 ] || { echo "local-fuzz: --seconds requires a value" >&2; exit 2; }
      seconds=$1
      seconds_set=true
      ;;
    --receipt)
      shift
      [ "$#" -gt 0 ] || { echo "local-fuzz: --receipt requires a path" >&2; exit 2; }
      receipt_path=$1
      receipt_set=true
      ;;
    --verify-receipt)
      shift
      [ "$#" -gt 0 ] || { echo "local-fuzz: --verify-receipt requires a path" >&2; exit 2; }
      verify_path=$1
      ;;
    --self-test) self_test_requested=true ;;
    --help|-h) usage; exit 0 ;;
    --*) echo "local-fuzz: unknown option $1" >&2; usage >&2; exit 2 ;;
    *) consumer_args+=("$1"); consumer_count=$((consumer_count + 1)) ;;
  esac
  shift
done

if $self_test_requested; then
  if $release_mode || $seconds_set || $receipt_set || [ -n "$verify_path" ] ||
      [ "$consumer_count" -ne 0 ]; then
    echo "local-fuzz: --self-test combines with no other option" >&2
    exit 2
  fi
  self_test
  exit $?
fi
if [ -n "$verify_path" ]; then
  if $release_mode || $seconds_set || $receipt_set || [ "$consumer_count" -ne 0 ]; then
    echo "local-fuzz: --verify-receipt combines with no run options or repo arguments" >&2
    exit 2
  fi
  verify_receipt "$verify_path"
  exit $?
fi

case "$seconds" in
  ''|*[!0-9]*|0|0*)
    echo "local-fuzz: --seconds must be a positive whole number without leading zeros" >&2
    exit 2
    ;;
esac
digits=$(printf '%s' "$seconds" | wc -c | tr -d ' ')
if [ "$digits" -gt 10 ] || { [ "$digits" -eq 10 ] && [ "$seconds" -gt 2147483647 ]; }; then
  echo "local-fuzz: --seconds must not exceed 2147483647" >&2
  exit 2
fi
if $release_mode; then
  if [ ! -x "$sava_build_dir/gradlew" ]; then
    preflight_failed="$preflight_failed sava-build(gradlew_not_executable)"
  fi
  receipt_mode=release
  if ! $seconds_set; then
    echo "local-fuzz: --release requires an explicit --seconds budget" >&2
    exit 2
  fi
  if [ "$consumer_count" -ne 0 ]; then
    echo "local-fuzz: --release always covers the complete manifest" >&2
    exit 2
  fi
  if ! $receipt_set; then receipt_path=$canonical_receipt; fi
else
  receipt_mode=ordinary
  if ! $receipt_set; then receipt_path=$ordinary_receipt; fi
fi
require_jq

plan_file=$(mktemp)
execution_plan=$(mktemp)
records_file=$(mktemp)
out_file=$(mktemp)
aggregate_records_file=$(mktemp)
trap 'rm -f "$plan_file" "$execution_plan" "$records_file" "$out_file" "$aggregate_records_file"' EXIT

if [ "$consumer_count" -eq 0 ]; then
  while read -r slug _rest; do
    case "$slug" in ''|\#*) continue ;; esac
    repo="$sava_build_dir/../$(basename "$slug")"
    if [ ! -d "$repo" ] && ! $release_mode; then
      echo "local-fuzz: SKIP $slug — no sibling checkout at $repo" >&2
      continue
    fi
    printf '%s\t%s\n' "$slug" "$repo" >> "$plan_file"
  done < "$manifest"
else
  for repo in "${consumer_args[@]}"; do
    slug=$(basename "$repo")
    if git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      remote=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
      detected_slug=$(origin_slug "$remote")
      if [ -n "$detected_slug" ]; then slug=$detected_slug; fi
    fi
    printf '%s\t%s\n' "$slug" "$repo" >> "$plan_file"
  done
fi
[ -s "$plan_file" ] || { echo "local-fuzz: nothing to run" >&2; exit 2; }

plugin_sha=$(git -C "$sava_build_dir" rev-parse HEAD)
plugin_tree=$(git -C "$sava_build_dir" rev-parse 'HEAD^{tree}')
plugin_origin=$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)
plugin_status_before=$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)
plugin_dirty_before=false
if [ -n "$plugin_status_before" ]; then plugin_dirty_before=true; fi

prepare_pointer_destination "$receipt_path"
if ! $release_mode && [ "$receipt_path" = "$canonical_receipt" ]; then
  echo "local-fuzz: ordinary mode cannot replace the canonical release pointer" >&2
  exit 2
fi
runs_parent="$receipt_parent/local-fuzz-runs"
reject_symlink_components "$runs_parent" "fuzz run directory"
mkdir -p "$runs_parent"
run_dir=$(mktemp -d "$runs_parent/run.XXXXXX")
run_id=$(basename "$run_dir")
bundle_rel="local-fuzz-runs/$run_id/receipt.json"
write_pointer "in_progress" ""
logs_dir="$run_dir/logs"
aggregates_dir="$run_dir/aggregates"
mkdir -p "$logs_dir" "$aggregates_dir"
plugin_publish_log="$run_dir/plugin-publish.log"
preflight_file="$run_dir/preflight.tsv"

printf 'slug\tpath\tsha\torigin\tdirty\tresult\n' > "$preflight_file"
preflight_failed=""
if $release_mode; then
  if [ "$(origin_slug "$plugin_origin")" != "$plugin_expected_slug" ]; then
    preflight_failed="$preflight_failed sava-build(remote_mismatch)"
  fi
  if $plugin_dirty_before; then
    printf '%s\n' "$plugin_status_before" > "$run_dir/plugin-dirty.txt"
    preflight_failed="$preflight_failed sava-build(dirty)"
  fi
  while IFS=$'\t' read -r slug repo; do
    repo_sha=""; remote=""; dirty=false; result=passed
    if [ ! -d "$repo" ]; then
      result=missing
    elif [ ! -f "$repo/gradlew" ]; then
      result=no_gradlew
    elif [ ! -x "$repo/gradlew" ]; then
      result=gradlew_not_executable
    elif ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      result=not_git
    else
      repo_sha=$(git -C "$repo" rev-parse HEAD)
      remote=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
      if [ -n "$(git -C "$repo" status --porcelain --untracked-files=all)" ]; then dirty=true; fi
      if [ "$(origin_slug "$remote")" != "$slug" ]; then result=remote_mismatch
      elif $dirty; then result=dirty
      fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$slug" "$repo" "$repo_sha" "$remote" "$dirty" "$result" \
      >> "$preflight_file"
    if [ "$result" = passed ]; then
      printf '%s\t%s\t%s\t%s\n' "$slug" "$repo" "$repo_sha" "$remote" >> "$execution_plan"
    else
      preflight_failed="$preflight_failed $slug($result)"
    fi
  done < "$plan_file"
  if [ -n "$preflight_failed" ]; then
    write_pointer "failed" ""
    echo "local-fuzz: release preflight FAILED:$preflight_failed" >&2
    echo "local-fuzz: evidence: $run_dir" >&2
    exit 1
  fi
else
  cp "$plan_file" "$execution_plan"
fi

echo "local-fuzz: publishing 0.0.0-test from $sava_build_dir at $plugin_sha"
if ! (cd "$sava_build_dir" && ./gradlew --console=plain \
    publishSavaBuildTestPublicationToSavaTestRepoRepository) > "$plugin_publish_log" 2>&1; then
  cat "$plugin_publish_log"
  write_pointer "failed" ""
  echo "local-fuzz: local plugin publication failed; evidence: $run_dir" >&2
  exit 1
fi
if [ "$(git -C "$sava_build_dir" rev-parse HEAD)" != "$plugin_sha" ] ||
    [ "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)" != "$plugin_origin" ] ||
    [ "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" != "$plugin_status_before" ]; then
  write_pointer "failed" ""
  echo "local-fuzz: plugin checkout changed during local publication; evidence: $run_dir" >&2
  exit 1
fi

collect_aggregate_receipts() {
  local slug=$1 repo=$2 output_file=$3 safe_slug=$4
  local source relative relative_dest destination source_hash copy_hash targets target_names running
  local total=0 count=0
  : > "$output_file"
  running=$(find "$repo" -type f -path '*/build/hardening/local-fuzz.running' -print \
    2>/dev/null | sort)
  if [ -n "$running" ]; then
    echo "local-fuzz: $slug retained in-progress fuzz sentinel(s):" >&2
    printf '  %s\n' "$running" >&2
    return 1
  fi
  while IFS= read -r source; do
    reject_symlink_components "$source" "inner fuzz receipt" || return 1
    targets=$(validate_aggregate_receipt "$source" "$seconds") || {
      echo "local-fuzz: invalid inner fuzz receipt: $source" >&2; return 1;
    }
    target_names=$(aggregate_target_names "$source")
    relative=$(printf '%s\n' "$source" | sed "s|^$repo/||")
    if [ "$relative" = "$source" ] || ! printf '%s\n' "$relative" |
        grep -Eq '^[A-Za-z0-9._/-]+$'; then
      echo "local-fuzz: inner fuzz receipt has unsafe path: $source" >&2
      return 1
    fi
    relative_dest="aggregates/$safe_slug/$relative"
    destination="$run_dir/$relative_dest"
    reject_symlink_components "$destination" "retained fuzz receipt" || return 1
    mkdir -p "$(dirname "$destination")"
    source_hash=$(sha256_file "$source")
    cp "$source" "$destination"
    copy_hash=$(sha256_file "$destination")
    if [ "$copy_hash" != "$source_hash" ]; then
      echo "local-fuzz: inner fuzz receipt changed while being retained: $source" >&2
      return 1
    fi
    jq -cn --arg path "$relative_dest" --arg sha256 "$copy_hash" \
      --arg target_names "$target_names" --argjson target_count "$targets" \
      '{path:$path,sha256:$sha256,target_count:$target_count,
        targets:($target_names|split("\n")|map(select(length>0)))}' >> "$output_file"
    total=$((total + targets))
    count=$((count + 1))
  done < <(find "$repo" -type f -path '*/build/hardening/local-fuzz.tsv' -print 2>/dev/null | sort)
  if [ "$count" -eq 0 ]; then
    echo "local-fuzz: $slug produced no aggregate fuzz evidence" >&2
    return 1
  fi
  if $release_mode && [ "$total" -eq 0 ]; then
    echo "local-fuzz: $slug produced only zero-target aggregate evidence" >&2
    return 1
  fi
}

record_repo() {
  local slug=$1 repo=$2 sha=$3 origin=$4 dirty_before=$5 dirty_after=$6
  local tasks=$7 targets=$8 task_mode=$9
  shift 9
  local result=$1 output_hash=$2 log_file=$3 aggregates_file=$4
  jq -cn \
    --arg slug "$slug" --arg path "$repo" --arg sha "$sha" --arg origin "$origin" \
    --argjson dirty_before "$dirty_before" --argjson dirty_after "$dirty_after" \
    --arg tasks "$tasks" --arg targets "$targets" --arg task_mode "$task_mode" \
    --arg result "$result" --arg output_sha256 "$output_hash" --arg log_file "$log_file" \
    --slurpfile aggregates "$aggregates_file" \
    '{slug:$slug,path:$path,sha:$sha,origin:$origin,
      dirty_before:$dirty_before,dirty_after:$dirty_after,
      tasks:($tasks|split("\n")|map(select(length>0))),
      registered_targets:($targets|split("\n")|map(select(length>0))),
      task_mode:$task_mode,result:$result,output_sha256:$output_sha256,
      log_file:$log_file,aggregates:$aggregates}' >> "$records_file"
}

failed=""
while IFS=$'\t' read -r slug repo pre_sha pre_origin; do
  safe_slug=$(printf '%s' "$slug" | tr '/:' '__')
  log_file="logs/$safe_slug.log"
  log_path="$run_dir/$log_file"
  reject_symlink_components "$log_path" "consumer log"
  : > "$log_path"
  : > "$aggregate_records_file"
  repo_result=passed
  repo_sha=$pre_sha
  remote=$pre_origin
  dirty_before=false
  dirty_after=false
  tasks=""
  targets=""
  task_mode=undiscovered
  status_before=""

  if $release_mode; then
    status_before=$(git -C "$repo" status --porcelain --untracked-files=all)
  elif [ ! -d "$repo" ]; then
    repo_result=missing
  elif [ ! -f "$repo/gradlew" ]; then
    repo_result=no_gradlew
  elif ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    repo_result=not_git
  else
    repo_sha=$(git -C "$repo" rev-parse HEAD)
    remote=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
    status_before=$(git -C "$repo" status --porcelain --untracked-files=all)
    if [ -n "$status_before" ]; then dirty_before=true; fi
  fi

  if [ "$repo_result" = passed ]; then
    if ! (cd "$repo" && ./gradlew --console=plain -PsavaBuildLocalRepo="$local_repo" tasks --all) \
        > "$out_file" 2>&1; then
      repo_result=task_discovery_failed
    else
      targets=$(registered_fuzz_tasks "$out_file")
      aggregate_present=false
      if has_fuzz_all "$out_file"; then aggregate_present=true; fi
      if ! grep -qF "$resolution_notice" "$out_file"; then
        repo_result=resolution_missing
      elif $release_mode && ! $aggregate_present; then
        repo_result=fuzz_all_missing
      elif $release_mode && [ -z "$targets" ]; then
        repo_result=no_registered_targets
      elif $aggregate_present; then
        tasks=fuzzAll
        task_mode=aggregate
      elif [ -n "$targets" ]; then
        tasks=$targets
        task_mode=discovered_fallback
      else
        tasks=help
        task_mode=no_targets
      fi
    fi
    cat "$out_file" >> "$log_path"
  fi

  if [ "$repo_result" = passed ]; then
    echo "local-fuzz: $slug@$repo_sha — $task_mode; ${targets:-no registered targets}"
    task_args=()
    while IFS= read -r task; do [ -n "$task" ] && task_args+=("$task"); done <<< "$tasks"
    if ! (cd "$repo" && ./gradlew --console=plain --continue \
        -PsavaBuildLocalRepo="$local_repo" -PmaxFuzzTime="$seconds" "${task_args[@]}") \
        > "$out_file" 2>&1; then
      repo_result=fuzz_failed
    elif ! grep -qF "$resolution_notice" "$out_file"; then
      repo_result=resolution_missing
    fi
    cat "$out_file" >> "$log_path"
  fi

  if git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    current_sha=$(git -C "$repo" rev-parse HEAD)
    current_origin=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
    status_after=$(git -C "$repo" status --porcelain --untracked-files=all)
    if [ -n "$status_after" ]; then dirty_after=true; fi
    if [ "$repo_result" = passed ]; then
      if [ "$current_sha" != "$repo_sha" ]; then repo_result=head_changed
      elif [ "$current_origin" != "$remote" ]; then repo_result=remote_changed
      elif [ "$status_after" != "$status_before" ]; then repo_result=worktree_changed
      elif $release_mode && $dirty_after; then repo_result=dirty_after
      fi
    fi
  elif [ "$repo_result" = passed ]; then
    repo_result=not_git_after
  fi

  if [ "$repo_result" = passed ] && [ "$task_mode" = aggregate ]; then
    if ! collect_aggregate_receipts "$slug" "$repo" "$aggregate_records_file" "$safe_slug"; then
      repo_result=aggregate_evidence_invalid
    fi
  fi
  output_hash=$(sha256_file "$log_path")
  record_repo "$slug" "$repo" "$repo_sha" "$remote" "$dirty_before" "$dirty_after" \
    "$tasks" "$targets" "$task_mode" "$repo_result" "$output_hash" "$log_file" \
    "$aggregate_records_file"
  if [ "$repo_result" != passed ]; then
    failed="$failed $slug($repo_result)"
    echo "local-fuzz: FAILED $slug — $repo_result; log: $log_path" >&2
  fi
done < "$execution_plan"

plugin_dirty_after=false
if [ -n "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" ]; then
  plugin_dirty_after=true
fi
if [ "$(git -C "$sava_build_dir" rev-parse HEAD)" != "$plugin_sha" ] ||
    [ "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)" != "$plugin_origin" ] ||
    [ "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" != "$plugin_status_before" ]; then
  failed="$failed sava-build(changed_during_run)"
fi

receipt_result=passed
[ -z "$failed" ] || receipt_result=failed
receipt_tmp=$(mktemp "$run_dir/.receipt.XXXXXX")
generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
jq -n \
  --arg mode "$receipt_mode" --arg generated_at "$generated_at" --arg run_id "$run_id" \
  --arg result "$receipt_result" --argjson seconds_per_target "$seconds" \
  --arg plugin_sha "$plugin_sha" --arg plugin_tree "$plugin_tree" \
  --arg plugin_origin "$plugin_origin" \
  --argjson plugin_dirty_before "$plugin_dirty_before" \
  --argjson plugin_dirty_after "$plugin_dirty_after" \
  --arg manifest_sha256 "$(sha256_file "$manifest")" \
  --arg preflight_sha256 "$(sha256_file "$preflight_file")" \
  --arg publish_output_sha256 "$(sha256_file "$plugin_publish_log")" \
  --slurpfile repositories "$records_file" \
  '{schema:2,kind:"local-fuzz-receipt",mode:$mode,run_id:$run_id,
    generated_at:$generated_at,result:$result,seconds_per_target:$seconds_per_target,
    plugin:{sha:$plugin_sha,tree:$plugin_tree,origin:$plugin_origin,
      dirty_before:$plugin_dirty_before,dirty_after:$plugin_dirty_after},
    manifest_sha256:$manifest_sha256,
    preflight_file:"preflight.tsv",preflight_sha256:$preflight_sha256,
    publish_log_file:"plugin-publish.log",publish_output_sha256:$publish_output_sha256,
    repositories:$repositories}' > "$receipt_tmp"
mv "$receipt_tmp" "$run_dir/receipt.json"
receipt_hash=$(sha256_file "$run_dir/receipt.json")
write_pointer "$receipt_result" "$receipt_hash"
echo "local-fuzz: evidence bundle: $run_dir"
echo "local-fuzz: receipt pointer: $receipt_path"

if [ -n "$failed" ]; then
  echo "local-fuzz: FAILED:$failed" >&2
  exit 1
fi
echo "local-fuzz: green — all selected repositories completed their registered fuzz targets"
