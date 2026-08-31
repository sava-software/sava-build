#!/usr/bin/env bash
# Run every registered consumer fuzz target against this sava-build checkout.
#
# Release evidence is stored in an immutable run directory below
# build/hardening/local-fuzz-runs. The canonical receipt is only an atomic
# pointer; starting another release attempt changes it to in_progress before
# any preflight or publication, so an interrupted rerun cannot leave an older
# pass looking current.
set -euo pipefail
export LC_ALL=C

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd -P)
manifest="$sava_build_dir/tools/fleet-manifest.txt"
local_repo="$sava_build_dir/build/sava-test-repo"
published_plugin_jar="$local_repo/software/sava/sava-build/0.0.0-test/sava-build-0.0.0-test.jar"
retained_plugin_jar_file="plugin-0.0.0-test.jar"
canonical_receipt="$sava_build_dir/build/hardening/local-fuzz-receipt.json"
ordinary_receipt="$sava_build_dir/build/hardening/local-fuzz-latest.json"
plugin_expected_slug="sava-software/sava-build"
resolution_notice="resolved every 'software.sava.build*' plugin to 0.0.0-test"

usage() {
  cat <<'EOF'
Usage:
  tools/local-fuzz.sh [--seconds <positive-int>] [--parallel-targets <positive-int>] [--receipt <path>] [<consumer-dir> ...]
  tools/local-fuzz.sh --release --seconds <positive-int> --parallel-targets <positive-int> [--receipt <path>]
  tools/local-fuzz.sh --verify-receipt <path>
  tools/local-fuzz.sh --self-test

Ordinary no-argument mode runs available manifest siblings and defaults to 60
seconds per target with one target at a time. It may fall back to discovered fuzz<Target> tasks for an
older consumer contract, or run help when no target exists.

Release mode requires an explicit bounded budget and concurrency, the complete clean roster,
matching GitHub remotes, fuzzAll, and at least one registered target per repo.
It retains and hashes the publish log, consumer logs, and plugin-generated
local-fuzz.tsv receipts in a run-specific evidence bundle.
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

append_execution_plan_row() {
  local destination=$1 value separator=""
  shift
  for value in "$@"; do
    case "$value" in
      *$'\037'*|*$'\n'*)
        echo "local-fuzz: execution-plan field contains a reserved delimiter" >&2
        return 1
        ;;
    esac
    printf '%s%s' "$separator" "$value" >> "$destination"
    separator=$'\037'
  done
  printf '\n' >> "$destination"
}

read_execution_plan_row() {
  IFS=$'\037' read -r -u 3 "$@"
}

run_consumer_gradle() {
  local gradle_executable=$1
  shift
  if $release_mode || [ "${parallel_targets:-1}" -gt 1 ]; then
    "$gradle_executable" --configuration-cache "$@"
  else
    "$gradle_executable" "$@"
  fi
}

snapshot_published_plugin_jar() {
  local source_before source_after retained_hash destination
  reject_symlink_components "$published_plugin_jar" "published plugin jar" || return 1
  if [ ! -f "$published_plugin_jar" ]; then
    echo "local-fuzz: publication produced no plugin jar at $published_plugin_jar" >&2
    return 1
  fi
  source_before=$(sha256_file "$published_plugin_jar") || return 1
  destination="$run_dir/$retained_plugin_jar_file"
  reject_symlink_components "$destination" "retained published plugin jar" || return 1
  cp "$published_plugin_jar" "$destination"
  reject_symlink_components "$published_plugin_jar" "published plugin jar" || return 1
  source_after=$(sha256_file "$published_plugin_jar") || return 1
  retained_hash=$(sha256_file "$destination") || return 1
  if [ "$source_before" != "$source_after" ] || [ "$source_after" != "$retained_hash" ]; then
    echo "local-fuzz: published plugin jar changed while being retained" >&2
    return 1
  fi
  published_plugin_sha256=$retained_hash
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

# A fixed digest is path-safe without doubling arbitrarily long repository and
# Gradle-project names past the filesystem's per-component limit. Retained inner
# evidence destinations are checked before use, so a duplicate key fails closed.
artifact_key() {
  printf '%s' "$1" | sha256_stream
}

artifact_path_matches_slug() {
  local kind=$1 slug=$2 relative=$3 key
  key=$(artifact_key "$slug")
  case "$relative" in
    "$kind/$key/"*) return 0 ;;
    *) return 1 ;;
  esac
}

log_path_matches_slug() {
  local slug=$1 relative=$2 key
  key=$(artifact_key "$slug")
  [ "$relative" = "logs/$key.log" ]
}

registered_fuzz_tasks() {
  awk '
    function normalize(task) {
      return task ~ /^:/ ? task : ":" task
    }
    / - Coverage-guided fuzzing of .* target with Jazzer;/ {
      task=$0
      sub(/ - Coverage-guided fuzzing of .*$/, "", task)
      print normalize(task)
    }
  ' "$1" | sort -u
}

registered_fuzz_projects() {
  awk '
    function normalize(task) {
      return task ~ /^:/ ? task : ":" task
    }
    / - Runs every registered fuzz target locally;/ {
      task=$0
      sub(/ - Runs every registered fuzz target locally;.*$/, "", task)
      task=normalize(task)
      sub(/:fuzzAll$/, "", task)
      print (task == "" ? ":" : task)
    }
  ' "$1" | sort -u
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
      rel=${receipt_path#"$sava_build_dir"/}
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
  local file=$1 expected_seconds=$2 expected_parallel=$3
  awk -F '\t' -v expected="$expected_seconds" -v expectedParallel="$expected_parallel" '
    function decimalLe(left, right,    pos, ldigit, rdigit) {
      if (length(left) != length(right)) return length(left) < length(right)
      for (pos=1; pos<=length(left); pos++) {
        ldigit=substr(left, pos, 1); rdigit=substr(right, pos, 1)
        if (ldigit != rdigit) return ldigit < rdigit
      }
      return 1
    }
    function withinExactJsonInteger(value) {
      return decimalLe(value, "9007199254740991")
    }
    function decimalAdd(left, right,    carry, li, ri, digit, result) {
      carry=0; result=""; li=length(left); ri=length(right)
      while (li > 0 || ri > 0 || carry > 0) {
        digit=carry
        if (li > 0) digit += substr(left, li--, 1)
        if (ri > 0) digit += substr(right, ri--, 1)
        result=(digit % 10) result
        carry=int(digit / 10)
      }
      return result == "" ? "0" : result
    }
    BEGIN { observedTotal="0" }
    $1 == "schema" {
      schemaRows++; if (NF != 2 || $2 != "4") invalid=1; next
    }
    $1 == "project" {
      projectRows++
      if (NF != 2 || $2 !~ /^:/ || $2 ~ /::/ || ($2 != ":" && $2 ~ /:$/)) invalid=1
      next
    }
    $1 == "maxFuzzTimeSeconds" {
      budgetRows++; if (NF != 2 || $2 != expected) invalid=1; next
    }
    $1 == "maxParallelTargets" {
      parallelRows++; if (NF != 2 || $2 != expectedParallel) invalid=1; next
    }
    $1 == "pluginSha256" {
      pluginRows++
      if (NF != 2 || length($2) != 64 || $2 ~ /[^0-9a-f]/) invalid=1
      next
    }
    $1 == "totalExecutions" {
      executionRows++
      if (NF != 2 || $2 !~ /^(0|[1-9][0-9]*)$/ || !withinExactJsonInteger($2)) invalid=1
      expectedTotal=$2
      next
    }
    $1 == "target" {
      targets++
      if (NF != 3 || $2 !~ /^fuzz./ || seen[$2]++ ||
          $3 !~ /^[1-9][0-9]*$/ || !withinExactJsonInteger($3)) invalid=1
      else observedTotal=decimalAdd(observedTotal, $3)
      next
    }
    NF > 0 { invalid=1 }
    END {
      if (invalid || schemaRows != 1 || projectRows != 1 || budgetRows != 1 || parallelRows != 1 ||
          pluginRows != 1 || executionRows != 1 || observedTotal != expectedTotal) exit 1
      print targets + 0
    }
  ' "$file"
}

aggregate_plugin_sha() {
  awk -F '\t' '$1 == "pluginSha256" { print $2; exit }' "$1"
}

aggregate_target_names() {
  awk -F '\t' '$1 == "target" { print $2 }' "$1" | sort -u
}

aggregate_total_executions() {
  awk -F '\t' '$1 == "totalExecutions" { print $2; exit }' "$1"
}

aggregate_parallel_targets() {
  awk -F '\t' '$1 == "maxParallelTargets" { print $2; exit }' "$1"
}

aggregate_execution_counts_json() {
  awk -F '\t' '$1 == "target" { print $2 "\t" $3 }' "$1" |
    jq -Rsc 'split("\n") | map(select(length > 0) | split("\t")) |
      reduce .[] as $row ({}; . + {($row[0]): ($row[1] | tonumber)})'
}

aggregate_project() {
  awk -F '\t' '$1 == "project" { print $2 }' "$1"
}

release_receipt_schema_valid() {
  jq -e '
    . as $receipt |
    .schema == 3 and .kind == "local-fuzz-receipt" and
    .mode == "release" and .result == "passed" and
    (.run_id | test("^run[.][A-Za-z0-9]+$")) and
    (.seconds_per_target | type == "number" and . > 0 and . <= 2147483647) and
    (.parallel_targets | type == "number" and . > 0 and . <= 2147483647 and floor == .) and
    (.plugin.sha | test("^[0-9a-f]{40}$")) and
    (.plugin.tree | test("^[0-9a-f]{40,64}$")) and
    (.plugin.origin | type == "string" and length > 0) and
    .plugin.test_jar_file == "plugin-0.0.0-test.jar" and
    (.plugin.test_jar_sha256 | test("^[0-9a-f]{64}$")) and
    .plugin.dirty_before == false and .plugin.dirty_after == false and
    (.manifest_sha256 | test("^[0-9a-f]{64}$")) and
    .preflight_file == "preflight.tsv" and
    (.preflight_sha256 | test("^[0-9a-f]{64}$")) and
    .publish_log_file == "plugin-publish.log" and
    (.publish_output_sha256 | test("^[0-9a-f]{64}$")) and
    (.repositories | type == "array" and length > 0) and
    (([.repositories[].log_file] | unique | length) == (.repositories | length)) and
    (([.repositories[].aggregates[].path] | unique | length) ==
      ([.repositories[].aggregates[].path] | length)) and
    all(.repositories[];
      .result == "passed" and .dirty_before == false and .dirty_after == false and
      (.sha | test("^[0-9a-f]{40}$")) and (.path | type == "string") and
      (.origin | type == "string" and length > 0) and
      .task_mode == "aggregate" and
      (.tasks | type == "array" and any(.[]; . == "fuzzAll")) and
      (.registered_projects | type == "array" and length > 0) and
      all(.registered_projects[];
        type == "string" and test("^(:|:[^:\\t\\r\\n]+(:[^:\\t\\r\\n]+)*)$")) and
      ((.registered_projects | unique | length) == (.registered_projects | length)) and
      (.registered_targets | type == "array" and length > 0) and
      all(.registered_targets[];
        type == "string" and test("^:([^:\\t\\r\\n]+:)*fuzz[A-Za-z0-9._-]+$")) and
      ((.registered_targets | unique | length) == (.registered_targets | length)) and
      (.log_file | test("^logs/[A-Za-z0-9._-]+[.]log$")) and
      (.output_sha256 | test("^[0-9a-f]{64}$")) and
      (.aggregates | type == "array" and length > 0) and
      (([.aggregates[].path] | unique | length) == (.aggregates | length)) and
      ([.aggregates[].target_count] | add > 0) and
      (([.aggregates[].project] | sort) == (.registered_projects | sort)) and
      (([.aggregates[] as $aggregate | $aggregate.targets[] |
          if $aggregate.project == ":" then ":" + .
          else $aggregate.project + ":" + . end] | sort) ==
        (.registered_targets | sort)) and
      all(.aggregates[];
        (.project | type == "string" and
          test("^(:|:[^:\\t\\r\\n]+(:[^:\\t\\r\\n]+)*)$")) and
        (.path | test("^aggregates/[A-Za-z0-9._/-]+[.]tsv$")) and
        (.path | split("/") | all(.[]; . != "" and . != "." and . != "..")) and
        (.sha256 | test("^[0-9a-f]{64}$")) and
        (.target_count | type == "number" and . >= 0) and
        (.total_executions | type == "number" and . >= 0 and
          . <= 9007199254740991 and floor == .) and
        (.max_parallel_targets == $receipt.parallel_targets) and
        (.plugin_sha256 | test("^[0-9a-f]{64}$")) and
        .plugin_sha256 == $receipt.plugin.test_jar_sha256 and
        (.targets | type == "array") and (.targets | length) == .target_count and
        all(.targets[]; type == "string" and test("^fuzz.")) and
        ((.targets | unique | length) == (.targets | length)) and
        (.executions_by_target | type == "object") and
        ((.executions_by_target | keys | sort) == (.targets | sort)) and
        all(.executions_by_target[];
          type == "number" and . > 0 and . <= 9007199254740991 and floor == .) and
        (([.executions_by_target[]] | add // 0) == .total_executions)))
  ' "$1" >/dev/null
}

aggregate_evidence_matches() {
  local expected_projects=$1 expected_targets=$2 records=$3 actual_projects actual_targets
  actual_projects=$(jq -sr 'map(.project) | sort | .[]' "$records") || return 1
  actual_targets=$(jq -sr '
    [.[] as $aggregate | $aggregate.targets[] |
      if $aggregate.project == ":" then ":" + .
      else $aggregate.project + ":" + . end] | sort | .[]
  ' "$records") || return 1
  [ "$actual_projects" = "$expected_projects" ] &&
    [ "$actual_targets" = "$expected_targets" ]
}

durable_aggregate_receipts() {
  find "$1" -type f -path '*/.pitest-history/local-fuzz.tsv' -print \
    2>/dev/null | sort
}

aggregate_running_entries() {
  # A directory, FIFO, or symlink at the sentinel name is still in-progress state.
  # Match the exact entry regardless of type and prune it rather than traversing it.
  find "$1" \
    \( -path '*/.pitest-history/local-fuzz.running' -o \
       -path '*/build/hardening/local-fuzz.running' \) -print -prune \
    2>/dev/null | sort
}

legacy_aggregate_receipt_entries() {
  find "$1" -path '*/build/hardening/local-fuzz.tsv' -print -prune \
    2>/dev/null | sort
}

refuse_aggregate_collection_blockers() {
  local repo=$1 slug=$2 entries
  entries=$(aggregate_running_entries "$repo") || return 1
  if [ -n "$entries" ]; then
    echo "local-fuzz: $slug retained in-progress fuzz sentinel(s):" >&2
    printf '  %s\n' "$entries" >&2
    return 1
  fi
  entries=$(legacy_aggregate_receipt_entries "$repo") || return 1
  if [ -n "$entries" ]; then
    echo "local-fuzz: $slug retained obsolete build-directory fuzz receipt(s):" >&2
    printf '  %s\n' "$entries" >&2
    return 1
  fi
}

receipt_log_rows() {
  jq -c '.repositories[] | {slug,log_file,output_sha256}' "$1"
}

receipt_aggregate_rows() {
  jq -c '.repositories[] as $repository | $repository.aggregates[] |
      {slug:$repository.slug,path,sha256,target_count,project,plugin_sha256,
        max_parallel_targets,total_executions,executions_by_target}' "$1"
}

receipt_checkout_rows() {
  jq -c '.repositories[] | {slug,path,sha,origin}' "$1"
}

resolve_receipt() {
  local requested=$1 input_parent pointer_bundle expected target
  local pointer_before pointer_after pointer_json
  pointer_run_id=""
  pointer_plugin_sha=""
  pointer_input_path=""
  pointer_input_sha=""
  resolved_receipt_initial_sha=""
  verified_artifact_paths=()
  verified_artifact_hashes=()
  verified_artifact_labels=()
  case "$requested" in /*) ;; *) requested="$PWD/$requested" ;; esac
  reject_symlink_components "$requested" "receipt" || return 1
  if [ ! -f "$requested" ]; then
    echo "local-fuzz: no certification receipt at $requested" >&2
    return 1
  fi
  pointer_before=$(sha256_file "$requested") || return 1
  pointer_json=$(<"$requested")
  pointer_after=$(sha256_file "$requested") || return 1
  if [ "$pointer_before" != "$pointer_after" ]; then
    echo "local-fuzz: receipt changed while it was being resolved: $requested" >&2
    return 1
  fi
  if jq -e '.schema == 2 and .kind == "local-fuzz-pointer"' <<< "$pointer_json" >/dev/null 2>&1; then
    if ! jq -e '
        .mode == "release" and .result == "passed" and
        (.run_id | test("^run[.][A-Za-z0-9]+$")) and
        .bundle == ("local-fuzz-runs/" + .run_id + "/receipt.json") and
        (.plugin_sha | test("^[0-9a-f]{40}$")) and
        (.receipt_sha256 | test("^[0-9a-f]{64}$"))
      ' <<< "$pointer_json" >/dev/null; then
      echo "local-fuzz: canonical pointer is not a completed passing release run: $requested" >&2
      return 1
    fi
    input_parent=$(cd "$(dirname "$requested")" && pwd -P)
    pointer_bundle=$(jq -r '.bundle' <<< "$pointer_json")
    target="$input_parent/$pointer_bundle"
    reject_symlink_components "$target" "bundle receipt" || return 1
    if [ ! -f "$target" ]; then
      echo "local-fuzz: pointed-to bundle receipt is missing: $target" >&2
      return 1
    fi
    expected=$(jq -r '.receipt_sha256' <<< "$pointer_json")
    if [ "$(sha256_file "$target")" != "$expected" ]; then
      echo "local-fuzz: bundle receipt hash does not match the canonical pointer: $target" >&2
      return 1
    fi
    pointer_run_id=$(jq -r '.run_id' <<< "$pointer_json")
    pointer_plugin_sha=$(jq -r '.plugin_sha' <<< "$pointer_json")
    pointer_input_path=$requested
    pointer_input_sha=$pointer_after
    resolved_receipt=$target
    resolved_receipt_initial_sha=$expected
  else
    resolved_receipt=$requested
    resolved_receipt_initial_sha=$pointer_after
  fi
  bundle_dir=$(cd "$(dirname "$resolved_receipt")" && pwd -P)
}

verification_inputs_unchanged() {
  local current i
  for ((i=0; i<${#verified_artifact_paths[@]}; i++)); do
    verify_artifact_unchanged "${verified_artifact_paths[$i]}" \
      "${verified_artifact_hashes[$i]}" "${verified_artifact_labels[$i]}" || return 1
  done
  reject_symlink_components "$resolved_receipt" "bundle receipt" || return 1
  if [ ! -f "$resolved_receipt" ]; then
    echo "local-fuzz: bundle receipt disappeared during verification: $resolved_receipt" >&2
    return 1
  fi
  current=$(sha256_file "$resolved_receipt") || return 1
  if [ "$current" != "$resolved_receipt_initial_sha" ]; then
    echo "local-fuzz: bundle receipt changed during verification: $resolved_receipt" >&2
    return 1
  fi
  if [ -n "$pointer_input_path" ]; then
    reject_symlink_components "$pointer_input_path" "receipt" || return 1
    if [ ! -f "$pointer_input_path" ]; then
      echo "local-fuzz: canonical pointer disappeared during verification: $pointer_input_path" >&2
      return 1
    fi
    current=$(sha256_file "$pointer_input_path") || return 1
    if [ "$current" != "$pointer_input_sha" ]; then
      echo "local-fuzz: canonical pointer was superseded during verification: $pointer_input_path" >&2
      return 1
    fi
  fi
}

bundle_artifact_path() {
  local relative=$1 label=$2 candidate parent physical_parent
  case "$relative" in
    ''|/*)
      echo "local-fuzz: refusing invalid $label path: $relative" >&2
      return 1
      ;;
  esac
  case "/$relative/" in
    */../*|*/./*|*//*)
      echo "local-fuzz: refusing $label path outside its bundle: $relative" >&2
      return 1
      ;;
  esac
  candidate="$bundle_dir/$relative"
  reject_symlink_components "$candidate" "$label" || return 1
  parent=$(dirname "$candidate")
  if [ -d "$parent" ]; then
    physical_parent=$(cd "$parent" && pwd -P) || return 1
    case "$physical_parent" in
      "$bundle_dir"|"$bundle_dir"/*) ;;
      *)
        echo "local-fuzz: refusing $label path outside its bundle: $relative" >&2
        return 1
        ;;
    esac
  fi
  printf '%s\n' "$candidate"
}

verify_artifact() {
  local relative=$1 expected=$2 label=$3 path actual
  verified_artifact_path=""
  path=$(bundle_artifact_path "$relative" "$label") || return 1
  reject_symlink_components "$path" "$label" || return 1
  if [ ! -f "$path" ]; then
    echo "local-fuzz: missing $label: $path" >&2
    return 1
  fi
  actual=$(sha256_file "$path") || return 1
  if [ "$actual" != "$expected" ]; then
    echo "local-fuzz: $label hash mismatch: $path" >&2
    return 1
  fi
  verified_artifact_path=$path
  verified_artifact_paths+=("$path")
  verified_artifact_hashes+=("$expected")
  verified_artifact_labels+=("$label")
}

verify_artifact_unchanged() {
  local path=$1 expected=$2 label=$3 current
  reject_symlink_components "$path" "$label" || return 1
  if [ ! -f "$path" ]; then
    echo "local-fuzz: $label disappeared during verification: $path" >&2
    return 1
  fi
  current=$(sha256_file "$path") || return 1
  if [ "$current" != "$expected" ]; then
    echo "local-fuzz: $label changed during verification: $path" >&2
    return 1
  fi
}

verify_receipt() {
  local requested=$1 certified_sha certified_tree actual_tree plugin_origin
  local manifest_count receipt_count unexpected changed slug log_file log_hash
  local aggregate_file aggregate_hash aggregate_targets aggregate_path
  local recorded_project actual_project recorded_names actual_names actual_targets
  local recorded_plugin actual_plugin
  local recorded_total actual_total recorded_counts actual_counts
  local repo recorded_sha recorded_origin current_sha current_origin
  local row rows pointer_receipt_run pointer_receipt_plugin recorded_plugin_origin
  local recorded_manifest_hash actual_manifest_hash expected_seconds expected_parallel
  local expected_hash expected_path checkout_status
  local checkout_count=0 unavailable_count=0
  require_jq
  resolve_receipt "$requested" || return 1
  if ! release_receipt_schema_valid "$resolved_receipt"; then
    echo "local-fuzz: receipt is not a successful strict evidence bundle: $resolved_receipt" >&2
    return 1
  fi
  pointer_receipt_run=$(jq -r '.run_id' "$resolved_receipt") || return 1
  pointer_receipt_plugin=$(jq -r '.plugin.sha' "$resolved_receipt") || return 1
  if [ -n "$pointer_run_id" ] &&
      { [ "$pointer_receipt_run" != "$pointer_run_id" ] ||
        [ "$pointer_receipt_plugin" != "$pointer_plugin_sha" ]; }; then
    echo "local-fuzz: pointer identity does not match its bundle receipt" >&2
    return 1
  fi

  certified_sha=$(jq -r '.plugin.sha' "$resolved_receipt") || return 1
  certified_tree=$(jq -r '.plugin.tree' "$resolved_receipt") || return 1
  if ! git -C "$sava_build_dir" cat-file -e "$certified_sha^{commit}" 2>/dev/null; then
    echo "local-fuzz: certified plugin commit $certified_sha is not in this checkout" >&2
    return 1
  fi
  actual_tree=$(git -C "$sava_build_dir" rev-parse "$certified_sha^{tree}") || return 1
  if [ "$actual_tree" != "$certified_tree" ]; then
    echo "local-fuzz: receipt tree $certified_tree does not belong to $certified_sha" >&2
    return 1
  fi
  if ! git -C "$sava_build_dir" merge-base --is-ancestor "$certified_sha" HEAD; then
    echo "local-fuzz: certified commit $certified_sha is not an ancestor of HEAD" >&2
    return 1
  fi
  checkout_status=$(git -C "$sava_build_dir" status --porcelain --untracked-files=all) || return 1
  if [ -n "$checkout_status" ]; then
    echo "local-fuzz: current plugin checkout is dirty; it cannot match release evidence" >&2
    return 1
  fi
  plugin_origin=$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)
  recorded_plugin_origin=$(jq -r '.plugin.origin' "$resolved_receipt") || return 1
  if [ "$plugin_origin" != "$recorded_plugin_origin" ] ||
      [ "$(origin_slug "$plugin_origin")" != "$plugin_expected_slug" ]; then
    echo "local-fuzz: plugin origin changed after certification: $plugin_origin" >&2
    return 1
  fi
  unexpected=""
  rows=$(git -C "$sava_build_dir" diff --name-only "$certified_sha"..HEAD --) || return 1
  if [ -n "$rows" ]; then
    while IFS= read -r changed; do
      case "$changed" in
        .release-please-manifest.json|CHANGELOG.md) ;;
        *)
          if [ -n "$unexpected" ]; then unexpected="$unexpected
$changed"; else unexpected=$changed; fi
          ;;
      esac
    done <<< "$rows"
  fi
  if [ -n "$unexpected" ]; then
    echo "local-fuzz: non-release-metadata files changed after certification:" >&2
    while IFS= read -r changed; do printf '  %s\n' "$changed" >&2; done <<< "$unexpected"
    return 1
  fi

  recorded_manifest_hash=$(jq -r '.manifest_sha256' "$resolved_receipt") || return 1
  actual_manifest_hash=$(sha256_file "$manifest") || return 1
  if [ "$recorded_manifest_hash" != "$actual_manifest_hash" ]; then
    echo "local-fuzz: local-fuzz manifest changed after certification" >&2
    return 1
  fi
  manifest_count=$(manifest_slugs | wc -l | tr -d ' ') || return 1
  receipt_count=$(jq '.repositories | length' "$resolved_receipt") || return 1
  if [ "$manifest_count" != "$receipt_count" ]; then
    echo "local-fuzz: receipt covers $receipt_count repos; manifest requires $manifest_count" >&2
    return 1
  fi
  rows=$(manifest_slugs) || return 1
  if [ -n "$rows" ]; then
    while IFS= read -r slug; do
      if ! jq -e --arg slug "$slug" '[.repositories[] | select(.slug == $slug)] | length == 1' \
          "$resolved_receipt" >/dev/null; then
        echo "local-fuzz: receipt does not contain exactly one record for $slug" >&2
        return 1
      fi
    done <<< "$rows"
  fi

  expected_hash=$(jq -r '.preflight_sha256' "$resolved_receipt") || return 1
  verify_artifact "preflight.tsv" "$expected_hash" \
    "preflight inventory" || return 1
  expected_hash=$(jq -r '.publish_output_sha256' "$resolved_receipt") || return 1
  verify_artifact "plugin-publish.log" "$expected_hash" \
    "plugin publish log" || return 1
  expected_path=$(jq -r '.plugin.test_jar_file' "$resolved_receipt") || return 1
  expected_hash=$(jq -r '.plugin.test_jar_sha256' "$resolved_receipt") || return 1
  verify_artifact "$expected_path" "$expected_hash" \
    "published 0.0.0-test plugin jar" || return 1
  expected_seconds=$(jq -r '.seconds_per_target' "$resolved_receipt") || return 1
  expected_parallel=$(jq -r '.parallel_targets' "$resolved_receipt") || return 1
  rows=$(receipt_log_rows "$resolved_receipt") || return 1
  if [ -n "$rows" ]; then
    while IFS= read -r row; do
    slug=$(jq -r '.slug' <<< "$row") || return 1
    log_file=$(jq -r '.log_file' <<< "$row") || return 1
    log_hash=$(jq -r '.output_sha256' <<< "$row") || return 1
    if ! log_path_matches_slug "$slug" "$log_file"; then
      echo "local-fuzz: consumer log is not bound to repository $slug: $log_file" >&2
      return 1
    fi
    verify_artifact "$log_file" "$log_hash" "consumer fuzz log" || return 1
    done <<< "$rows"
  fi
  rows=$(receipt_aggregate_rows "$resolved_receipt") || return 1
  if [ -n "$rows" ]; then
    while IFS= read -r row; do
    slug=$(jq -r '.slug' <<< "$row") || return 1
    aggregate_file=$(jq -r '.path' <<< "$row") || return 1
    aggregate_hash=$(jq -r '.sha256' <<< "$row") || return 1
    aggregate_targets=$(jq -r '.target_count' <<< "$row") || return 1
    recorded_project=$(jq -r '.project' <<< "$row") || return 1
    recorded_plugin=$(jq -r '.plugin_sha256' <<< "$row") || return 1
    if ! artifact_path_matches_slug "aggregates" "$slug" "$aggregate_file"; then
      echo "local-fuzz: inner fuzz receipt is not bound to repository $slug: $aggregate_file" >&2
      return 1
    fi
    verify_artifact "$aggregate_file" "$aggregate_hash" "inner fuzz receipt" || return 1
    aggregate_path=$verified_artifact_path
    actual_targets=$(validate_aggregate_receipt "$aggregate_path" \
      "$expected_seconds" "$expected_parallel") || {
      echo "local-fuzz: invalid inner fuzz receipt: $aggregate_path" >&2
      return 1
    }
    if [ "$actual_targets" != "$aggregate_targets" ]; then
      echo "local-fuzz: inner fuzz receipt target count changed: $aggregate_path" >&2
      return 1
    fi
    actual_project=$(aggregate_project "$aggregate_path") || return 1
    if [ "$actual_project" != "$recorded_project" ]; then
      echo "local-fuzz: inner fuzz receipt project changed: $aggregate_path" >&2
      return 1
    fi
    actual_plugin=$(aggregate_plugin_sha "$aggregate_path") || return 1
    if [ "$actual_plugin" != "$recorded_plugin" ] ||
        [ "$actual_plugin" != "$expected_hash" ]; then
      echo "local-fuzz: inner fuzz receipt does not describe the retained published plugin jar: $aggregate_path" >&2
      return 1
    fi
    actual_total=$(aggregate_total_executions "$aggregate_path") || return 1
    recorded_total=$(jq -r --arg path "$aggregate_file" \
      '.repositories[].aggregates[] | select(.path == $path) | .total_executions' \
      "$resolved_receipt") || return 1
    if [ "$actual_total" != "$recorded_total" ]; then
      echo "local-fuzz: inner fuzz receipt execution total changed: $aggregate_path" >&2
      return 1
    fi
    actual_counts=$(aggregate_execution_counts_json "$aggregate_path" | jq -cS .) || return 1
    recorded_counts=$(jq -cS --arg path "$aggregate_file" \
      '.repositories[].aggregates[] | select(.path == $path) | .executions_by_target' \
      "$resolved_receipt") || return 1
    if [ "$actual_counts" != "$recorded_counts" ]; then
      echo "local-fuzz: inner fuzz receipt target execution counts changed: $aggregate_path" >&2
      return 1
    fi
    actual_names=$(aggregate_target_names "$aggregate_path") || return 1
    recorded_names=$(jq -r --arg path "$aggregate_file" \
      '.repositories[].aggregates[] | select(.path == $path) | .targets[]' "$resolved_receipt") || return 1
    if [ "$actual_names" != "$recorded_names" ]; then
      echo "local-fuzz: inner fuzz receipt target names changed: $aggregate_path" >&2
      return 1
    fi
    verify_artifact_unchanged "$aggregate_path" "$aggregate_hash" "inner fuzz receipt" || return 1
    done <<< "$rows"
  fi

  rows=$(receipt_checkout_rows "$resolved_receipt") || return 1
  if [ -n "$rows" ]; then
    while IFS= read -r row; do
    slug=$(jq -r '.slug' <<< "$row") || return 1
    repo=$(jq -r '.path' <<< "$row") || return 1
    recorded_sha=$(jq -r '.sha' <<< "$row") || return 1
    recorded_origin=$(jq -r '.origin' <<< "$row") || return 1
    if [ ! -e "$repo" ]; then
      echo "local-fuzz: NOTE $slug checkout unavailable; retained artifacts were verified" >&2
      unavailable_count=$((unavailable_count + 1))
      continue
    fi
    if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      echo "local-fuzz: current checkout for $slug is not a Git worktree: $repo" >&2
      return 1
    fi
    current_sha=$(git -C "$repo" rev-parse HEAD) || return 1
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
    checkout_status=$(git -C "$repo" status --porcelain --untracked-files=all) || return 1
    if [ -n "$checkout_status" ]; then
      echo "local-fuzz: $slug checkout is dirty after certification" >&2
      return 1
    fi
    checkout_count=$((checkout_count + 1))
    done <<< "$rows"
  fi

  verification_inputs_unchanged || return 1
  if [ "$checkout_count" -eq 0 ]; then
    echo "local-fuzz: retained fuzz artifacts for $receipt_count repos verified, but zero consumer checkouts were available; refusing full verification" >&2
    return 1
  fi
  echo "local-fuzz: retained fuzz artifacts verified for $receipt_count repos; revalidated $checkout_count consumer checkout(s), $unavailable_count unavailable; plugin $certified_sha"
}

collect_aggregate_receipts() {
  local slug=$1 repo=$2 output_file=$3 safe_slug=$4
  local expected_projects=$5 expected_targets=$6
  local source relative relative_dest destination source_before_hash source_after_hash copy_hash
  local current_source_hash current_discovered source_index
  local targets target_names project project_key plugin_hash
  local total_executions execution_counts discovered recorded_parallel
  local -a collected_sources=() collected_source_hashes=()
  local total=0 count=0
  : > "$output_file"
  refuse_aggregate_collection_blockers "$repo" "$slug" || return 1
  discovered=$(durable_aggregate_receipts "$repo") || return 1
  if [ -n "$discovered" ]; then
    while IFS= read -r source; do
      reject_symlink_components "$source" "inner fuzz receipt" || return 1
      relative=${source#"$repo"/}
      case "$relative" in "$source"|../*|*/../*)
        echo "local-fuzz: inner fuzz receipt is outside its repository: $source" >&2
        return 1
        ;;
      esac
      source_before_hash=$(sha256_file "$source") || return 1
      # The destination key comes from the project recorded by the producer, but
      # all trusted metadata below is re-read from the retained private copy.
      project=$(aggregate_project "$source") || return 1
      project_key=$(artifact_key "$project") || return 1
      relative_dest="aggregates/$safe_slug/$project_key.tsv"
      destination="$run_dir/$relative_dest"
      reject_symlink_components "$destination" "retained fuzz receipt" || return 1
      if [ -e "$destination" ]; then
        echo "local-fuzz: duplicate aggregate evidence for $slug project $project" >&2
        return 1
      fi
      mkdir -p "$(dirname "$destination")" || return 1
      cp "$source" "$destination" || return 1
      reject_symlink_components "$source" "inner fuzz receipt" || return 1
      source_after_hash=$(sha256_file "$source") || return 1
      copy_hash=$(sha256_file "$destination") || return 1
      if [ "$source_before_hash" != "$source_after_hash" ] ||
          [ "$copy_hash" != "$source_after_hash" ]; then
        echo "local-fuzz: inner fuzz receipt changed while being retained: $source" >&2
        return 1
      fi
      collected_sources+=("$source")
      collected_source_hashes+=("$source_after_hash")
      targets=$(validate_aggregate_receipt "$destination" "$seconds" "$parallel_targets") || {
        echo "local-fuzz: invalid retained inner fuzz receipt: $destination" >&2; return 1;
      }
      project=$(aggregate_project "$destination") || return 1
      plugin_hash=$(aggregate_plugin_sha "$destination") || return 1
      if [ "$plugin_hash" != "$published_plugin_sha256" ]; then
        echo "local-fuzz: $slug fuzzed with plugin $plugin_hash, not published jar $published_plugin_sha256" >&2
        return 1
      fi
      target_names=$(aggregate_target_names "$destination") || return 1
      recorded_parallel=$(aggregate_parallel_targets "$destination") || return 1
      total_executions=$(aggregate_total_executions "$destination") || return 1
      execution_counts=$(aggregate_execution_counts_json "$destination") || return 1
      verify_artifact_unchanged "$destination" "$copy_hash" "retained inner fuzz receipt" || return 1
      jq -cn --arg project "$project" --arg path "$relative_dest" --arg sha256 "$copy_hash" \
        --arg plugin_sha256 "$plugin_hash" \
        --arg target_names "$target_names" --argjson target_count "$targets" \
        --argjson max_parallel_targets "$recorded_parallel" \
        --argjson total_executions "$total_executions" \
        --argjson executions_by_target "$execution_counts" \
        '{project:$project,path:$path,sha256:$sha256,target_count:$target_count,
          max_parallel_targets:$max_parallel_targets,
          plugin_sha256:$plugin_sha256,total_executions:$total_executions,
          executions_by_target:$executions_by_target,
          targets:($target_names|split("\n")|map(select(length>0)))}' >> "$output_file" || return 1
      total=$((total + targets))
      count=$((count + 1))
    done <<< "$discovered"
  fi
  if [ "$count" -eq 0 ]; then
    echo "local-fuzz: $slug produced no aggregate fuzz evidence" >&2
    return 1
  fi
  if $release_mode && [ "$total" -eq 0 ]; then
    echo "local-fuzz: $slug produced only zero-target aggregate evidence" >&2
    return 1
  fi
  if ! aggregate_evidence_matches "$expected_projects" "$expected_targets" "$output_file"; then
    echo "local-fuzz: $slug aggregate receipts do not exactly cover discovered fuzzAll projects and targets" >&2
    return 1
  fi
  # A producer may have started after the discovery scan while its prior durable
  # receipt remained byte-identical. Refuse that historical snapshot before the
  # caller can publish it as evidence for this run.
  refuse_aggregate_collection_blockers "$repo" "$slug" || return 1
  current_discovered=$(durable_aggregate_receipts "$repo") || return 1
  if [ "$current_discovered" != "$discovered" ]; then
    echo "local-fuzz: $slug durable fuzz receipt inventory changed while being retained" >&2
    return 1
  fi
  for ((source_index = 0; source_index < ${#collected_sources[@]}; source_index++)); do
    source=${collected_sources[$source_index]}
    reject_symlink_components "$source" "inner fuzz receipt" || return 1
    current_source_hash=$(sha256_file "$source") || return 1
    if [ "$current_source_hash" != "${collected_source_hashes[$source_index]}" ]; then
      echo "local-fuzz: inner fuzz receipt changed after retention: $source" >&2
      return 1
    fi
  done
  refuse_aggregate_collection_blockers "$repo" "$slug" || return 1
}

self_test() {
  local fixture aggregate records targets projects slugs basenames count
  local path_fixture pointer_file target_receipt stale_file legacy_stale_file
  local running_entry discovered plan_probe stdin_probe symlink_file
  local consumer_args_probe consumer_args_output
  local target_hash long_name key plugin_hash repo_key empty_path_key empty_path_sha
  local plan_rows _stolen probe_slug probe_repo probe_sha probe_origin
  local plugin_checkout consumer_checkout unavailable_checkout manifest_fixture bundle_fixture
  local preflight_file publish_file jar_file log_file aggregate_file
  local empty_path_log_file empty_path_aggregate_file verify_output_file verify_cwd saved_pwd
  local plugin_commit plugin_tree consumer_commit preflight_hash publish_hash
  local log_hash aggregate_hash empty_path_log_hash empty_path_aggregate_hash
  local manifest_hash verify_output saved_function projection producer_failed
  local release_mode=false seconds=17 parallel_targets=1
  local run_dir published_plugin_sha256
  local GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
  export GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM
  require_jq
  # EXIT may run after self_test's locals unwind, so retain this path globally.
  local_fuzz_self_test_cleanup_path=$(
    mktemp -d "${TMPDIR:-/tmp}/local-fuzz-self-test.XXXXXX"
  )
  path_fixture=$local_fuzz_self_test_cleanup_path
  trap '
    trap - RETURN EXIT
    case "$(basename "$local_fuzz_self_test_cleanup_path")" in
      local-fuzz-self-test.*)
        rm -rf -- "$local_fuzz_self_test_cleanup_path" || true
        ;;
      *)
        echo "local-fuzz self-test: refusing unsafe cleanup path $local_fuzz_self_test_cleanup_path" >&2
        ;;
    esac
  ' RETURN EXIT
  path_fixture=$(cd "$path_fixture" && pwd -P)
  local_fuzz_self_test_cleanup_path=$path_fixture
  consumer_args_probe="$path_fixture/consumer-args-probe"
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'for argument in "$@"; do printf "<%s>\n" "$argument"; done' > "$consumer_args_probe"
  chmod +x "$consumer_args_probe"
  consumer_args_output=$(run_consumer_gradle "$consumer_args_probe" --console=plain "space argument")
  if [ "$consumer_args_output" != $'<--console=plain>\n<space argument>' ]; then
    echo "local-fuzz self-test: ordinary consumer arguments were: $consumer_args_output" >&2
    return 1
  fi
  parallel_targets=2
  consumer_args_output=$(run_consumer_gradle "$consumer_args_probe" --console=plain "space argument")
  if [ "$consumer_args_output" != $'<--configuration-cache>\n<--console=plain>\n<space argument>' ]; then
    echo "local-fuzz self-test: parallel ordinary consumer arguments were: $consumer_args_output" >&2
    return 1
  fi
  parallel_targets=1
  release_mode=true
  consumer_args_output=$(run_consumer_gradle "$consumer_args_probe" --console=plain "space argument")
  if [ "$consumer_args_output" != $'<--configuration-cache>\n<--console=plain>\n<space argument>' ]; then
    echo "local-fuzz self-test: release consumer arguments were: $consumer_args_output" >&2
    return 1
  fi
  release_mode=false
  fixture="$path_fixture/fixture"
  aggregate="$path_fixture/aggregate"
  records="$path_fixture/records"
  plan_probe="$path_fixture/execution-plan"
  stdin_probe="$path_fixture/stdin"
  pointer_file="$path_fixture/pointer.json"
  target_receipt="$path_fixture/local-fuzz-runs/run.A/receipt.json"
  stale_file="$path_fixture/repo/module/.pitest-history/local-fuzz.tsv"
  legacy_stale_file="$path_fixture/repo/module/build/hardening/local-fuzz.tsv"
  printf '%s\n' \
    "fuzzAll - Runs every registered fuzz target locally; budget and concurrency are explicit." \
    ":alpha:fuzzAll - Runs every registered fuzz target locally; budget and concurrency are explicit." \
    "api+client:fuzzAll - Runs every registered fuzz target locally; budget and concurrency are explicit." \
    "space project:fuzzAll - Runs every registered fuzz target locally; budget and concurrency are explicit." \
    "fuzzAllPreflight - Internal compatibility marker; fuzzAll owns its campaign start boundary." \
    "fuzzFOO - Coverage-guided fuzzing of the 'FOO' target with Jazzer; bounded." \
    "fuzzFoo - Coverage-guided fuzzing of the 'foo' target with Jazzer; bounded." \
    ":alpha:fuzzCodecMinimize - Coverage-guided fuzzing of the 'codecMinimize' target with Jazzer; bounded." \
    "api+client:fuzzWireSeedLenCheck - Coverage-guided fuzzing of the 'wireSeedLenCheck' target with Jazzer; bounded." \
    "space project:fuzzCodec - Coverage-guided fuzzing of the 'codec' target with Jazzer; bounded." \
    "fuzzCodecMinimize - Minimizes codec" \
    "fuzzWireSeedLenCheck - Checks seeds" \
    "fuzzWorkflowInSync - Compatibility" > "$fixture"
  projects=$(registered_fuzz_projects "$fixture")
  if [ "$projects" != ":
:alpha
:api+client
:space project" ]; then
    echo "local-fuzz self-test: project parser produced: $projects" >&2
    return 1
  fi
  targets=$(registered_fuzz_tasks "$fixture")
  if [ "$targets" != ":alpha:fuzzCodecMinimize
:api+client:fuzzWireSeedLenCheck
:fuzzFOO
:fuzzFoo
:space project:fuzzCodec" ]; then
    echo "local-fuzz self-test: target parser produced: $targets" >&2
    return 1
  fi
  plugin_hash=$(printf '%064d' 0)
  printf '%s\n' \
    "schema	4" \
    "project	:" \
    "maxFuzzTimeSeconds	17" \
    "maxParallelTargets	1" \
    "pluginSha256	$plugin_hash" \
    "totalExecutions	300" \
    "target	fuzzCodec	100" \
    "target	fuzzWire	200" > "$aggregate"
  count=$(validate_aggregate_receipt "$aggregate" 17 1)
  [ "$count" = 2 ] || { echo "local-fuzz self-test: aggregate count was $count" >&2; return 1; }
  [ "$(aggregate_plugin_sha "$aggregate")" = "$plugin_hash" ] || {
    echo "local-fuzz self-test: aggregate plugin identity was not parsed" >&2; return 1;
  }
  if validate_aggregate_receipt "$aggregate" 18 1 >/dev/null 2>&1; then
    echo "local-fuzz self-test: wrong budget was accepted" >&2
    return 1
  fi
  if validate_aggregate_receipt "$aggregate" 17 2 >/dev/null 2>&1; then
    echo "local-fuzz self-test: wrong parallel-target bound was accepted" >&2
    return 1
  fi
  sed 's/totalExecutions\t300/totalExecutions\t301/' "$aggregate" > "$fixture"
  if validate_aggregate_receipt "$fixture" 17 1 >/dev/null 2>&1; then
    echo "local-fuzz self-test: inconsistent execution total was accepted" >&2
    return 1
  fi
  sed 's/target\tfuzzWire\t200/target\tfuzzWire\t0/' "$aggregate" > "$fixture"
  if validate_aggregate_receipt "$fixture" 17 1 >/dev/null 2>&1; then
    echo "local-fuzz self-test: zero target execution count was accepted" >&2
    return 1
  fi
  sed -e 's/totalExecutions\t300/totalExecutions\t9007199254741192/' \
    -e 's/target\tfuzzCodec\t100/target\tfuzzCodec\t9007199254740992/' \
    "$aggregate" > "$fixture"
  if validate_aggregate_receipt "$fixture" 17 1 >/dev/null 2>&1; then
    echo "local-fuzz self-test: inexact JSON execution count was accepted" >&2
    return 1
  fi
  printf 'target\tfuzzCodec\t1\n' >> "$aggregate"
  if validate_aggregate_receipt "$aggregate" 17 1 >/dev/null 2>&1; then
    echo "local-fuzz self-test: duplicate target evidence was accepted" >&2
    return 1
  fi
  printf '%s\n' \
    '{"project":":","targets":["fuzzFOO","fuzzFoo"]}' \
    '{"project":":alpha","targets":["fuzzCodecMinimize"]}' > "$records"
  if aggregate_evidence_matches "$projects" "$targets" "$records"; then
    echo "local-fuzz self-test: missing project evidence was accepted" >&2
    return 1
  fi
  printf '%s\n' \
    '{"project":":api+client","targets":["fuzzWireSeedLenCheck"]}' \
    '{"project":":space project","targets":["fuzzCodec"]}' >> "$records"
  if ! aggregate_evidence_matches "$projects" "$targets" "$records"; then
    echo "local-fuzz self-test: qualified aggregate evidence was rejected" >&2
    return 1
  fi
  printf '%s\n' '{"project":":alpha","targets":["fuzzCodecMinimize"]}' >> "$records"
  if aggregate_evidence_matches "$projects" "$targets" "$records"; then
    echo "local-fuzz self-test: duplicate project evidence was accepted" >&2
    return 1
  fi
  if bundle_artifact_path "../escape" "self-test artifact" >/dev/null 2>&1 ||
      bundle_artifact_path "logs/../../escape" "self-test artifact" >/dev/null 2>&1; then
    echo "local-fuzz self-test: bundle traversal was accepted" >&2
    return 1
  fi
  if [ "$(artifact_key 'a_b/c')" = "$(artifact_key 'a/b_c')" ]; then
    echo "local-fuzz self-test: artifact key collision" >&2
    return 1
  fi
  if artifact_path_matches_slug "aggregates" "a_b/c" \
      "aggregates/$(artifact_key 'a/b_c')/receipt.tsv"; then
    echo "local-fuzz self-test: cross-repository artifact alias was accepted" >&2
    return 1
  fi
  long_name=$(printf '%0200d' 0)
  key=$(artifact_key "$long_name")
  [ "${#key}" = 64 ] || {
    echo "local-fuzz self-test: long artifact key has ${#key} characters" >&2; return 1;
  }
  artifact_path_matches_slug "aggregates" "a_b/c" \
    "aggregates/$(artifact_key 'a_b/c')/receipt.tsv" || {
      echo "local-fuzz self-test: matching repository artifact path was rejected" >&2; return 1;
    }
  log_path_matches_slug "a_b/c" "logs/$(artifact_key 'a_b/c').log" || {
    echo "local-fuzz self-test: matching repository log path was rejected" >&2; return 1;
  }
  if log_path_matches_slug "a_b/c" "logs/$(artifact_key 'a/b_c').log"; then
    echo "local-fuzz self-test: cross-repository log alias was accepted" >&2
    return 1
  fi
  : > "$plan_probe"
  append_execution_plan_row "$plan_probe" one /repo-one "" origin-one
  append_execution_plan_row "$plan_probe" two /repo-two sha-two origin-two
  printf 'stdin may be consumed by a child\n' > "$stdin_probe"
  plan_rows=0
  while read_execution_plan_row probe_slug probe_repo probe_sha probe_origin; do
    if [ "$probe_slug" = one ] &&
        { [ "$probe_repo" != /repo-one ] || [ -n "$probe_sha" ] ||
          [ "$probe_origin" != origin-one ]; }; then
      echo "local-fuzz self-test: execution plan collapsed an empty field" >&2
      return 1
    fi
    IFS= read -r _stolen || true
    plan_rows=$((plan_rows + 1))
  done 3< "$plan_probe" < "$stdin_probe"
  [ "$plan_rows" -eq 2 ] || {
    echo "local-fuzz self-test: execution plan shared stdin and was truncated" >&2; return 1;
  }

  plugin_checkout="$path_fixture/plugin-checkout"
  consumer_checkout="$path_fixture/consumer-checkout"
  unavailable_checkout="$path_fixture/consumer-checkout.unavailable"
  manifest_fixture="$path_fixture/fuzz-manifest.txt"
  bundle_fixture="$path_fixture/local-fuzz-runs/run.A"
  verify_cwd="$path_fixture/cwd"
  empty_path_sha=$(printf '%040d' 1)
  empty_path_key=$(artifact_key "sava-software/unavailable")
  mkdir -p "$plugin_checkout" "$consumer_checkout" "$bundle_fixture/logs" \
    "$(dirname "$stale_file")" "$(dirname "$legacy_stale_file")" \
    "$verify_cwd/$empty_path_sha"
  repo_key=$(artifact_key "sava-software/example")
  mkdir -p "$bundle_fixture/aggregates/$repo_key" \
    "$bundle_fixture/aggregates/$empty_path_key"

  printf 'plugin source\n' > "$plugin_checkout/source.txt"
  git -C "$plugin_checkout" init -q
  git -C "$plugin_checkout" add source.txt
  git -C "$plugin_checkout" -c user.name='Runner Self Test' \
    -c user.email='runner-self-test@example.invalid' -c commit.gpgSign=false \
    commit -qm 'plugin fixture'
  git -C "$plugin_checkout" remote add origin git@github.com:sava-software/sava-build.git
  plugin_commit=$(git -C "$plugin_checkout" rev-parse HEAD)
  plugin_tree=$(git -C "$plugin_checkout" rev-parse 'HEAD^{tree}')

  printf 'consumer source\n' > "$consumer_checkout/source.txt"
  git -C "$consumer_checkout" init -q
  git -C "$consumer_checkout" add source.txt
  git -C "$consumer_checkout" -c user.name='Runner Self Test' \
    -c user.email='runner-self-test@example.invalid' -c commit.gpgSign=false \
    commit -qm 'consumer fixture'
  git -C "$consumer_checkout" remote add origin git@github.com:sava-software/example.git
  consumer_commit=$(git -C "$consumer_checkout" rev-parse HEAD)

  printf '%s\n' sava-software/example sava-software/unavailable > "$manifest_fixture"
  preflight_file="$bundle_fixture/preflight.tsv"
  publish_file="$bundle_fixture/plugin-publish.log"
  jar_file="$bundle_fixture/plugin-0.0.0-test.jar"
  log_file="$bundle_fixture/logs/$repo_key.log"
  empty_path_log_file="$bundle_fixture/logs/$empty_path_key.log"
  aggregate_file="$bundle_fixture/aggregates/$repo_key/root.tsv"
  empty_path_aggregate_file="$bundle_fixture/aggregates/$empty_path_key/root.tsv"
  printf 'preflight\n' > "$preflight_file"
  printf 'publish\n' > "$publish_file"
  printf 'plugin jar\n' > "$jar_file"
  printf 'consumer fuzz log\n' > "$log_file"
  printf 'unavailable consumer fuzz log\n' > "$empty_path_log_file"
  plugin_hash=$(sha256_file "$jar_file")
  printf 'schema\t4\nproject\t:\nmaxFuzzTimeSeconds\t17\nmaxParallelTargets\t1\npluginSha256\t%s\ntotalExecutions\t100\ntarget\tfuzzCodec\t100\n' \
    "$plugin_hash" > "$aggregate_file"
  cp "$aggregate_file" "$empty_path_aggregate_file"
  preflight_hash=$(sha256_file "$preflight_file")
  publish_hash=$(sha256_file "$publish_file")
  log_hash=$(sha256_file "$log_file")
  empty_path_log_hash=$(sha256_file "$empty_path_log_file")
  aggregate_hash=$(sha256_file "$aggregate_file")
  empty_path_aggregate_hash=$(sha256_file "$empty_path_aggregate_file")
  manifest_hash=$(sha256_file "$manifest_fixture")

  jq -n --arg plugin_commit "$plugin_commit" --arg plugin_tree "$plugin_tree" \
    --arg plugin_hash "$plugin_hash" --arg manifest_hash "$manifest_hash" \
    --arg preflight_hash "$preflight_hash" --arg publish_hash "$publish_hash" \
    --arg repo_key "$repo_key" --arg consumer_path "$consumer_checkout" \
    --arg consumer_commit "$consumer_commit" --arg log_hash "$log_hash" \
    --arg aggregate_hash "$aggregate_hash" \
    --arg empty_path_key "$empty_path_key" --arg empty_path_sha "$empty_path_sha" \
    --arg empty_path_log_hash "$empty_path_log_hash" \
    --arg empty_path_aggregate_hash "$empty_path_aggregate_hash" \
    '{schema:3,kind:"local-fuzz-receipt",mode:"release",result:"passed",run_id:"run.A",
      seconds_per_target:17,parallel_targets:1,
      plugin:{sha:$plugin_commit,tree:$plugin_tree,
        origin:"git@github.com:sava-software/sava-build.git",dirty_before:false,dirty_after:false,
        test_jar_file:"plugin-0.0.0-test.jar",test_jar_sha256:$plugin_hash},
      manifest_sha256:$manifest_hash,preflight_file:"preflight.tsv",preflight_sha256:$preflight_hash,
      publish_log_file:"plugin-publish.log",publish_output_sha256:$publish_hash,
      repositories:[{slug:"sava-software/example",path:$consumer_path,
        sha:$consumer_commit,origin:"git@github.com:sava-software/example.git",
        dirty_before:false,dirty_after:false,result:"passed",task_mode:"aggregate",tasks:["fuzzAll"],
        registered_projects:[":"],registered_targets:[":fuzzCodec"],
        log_file:("logs/"+$repo_key+".log"),output_sha256:$log_hash,
        aggregates:[{project:":",path:("aggregates/"+$repo_key+"/root.tsv"),sha256:$aggregate_hash,
          target_count:1,max_parallel_targets:1,total_executions:100,executions_by_target:{fuzzCodec:100},
          plugin_sha256:$plugin_hash,targets:["fuzzCodec"]}]},
        {slug:"sava-software/unavailable",path:"",sha:$empty_path_sha,
          origin:"git@github.com:sava-software/unavailable.git",
          dirty_before:false,dirty_after:false,result:"passed",task_mode:"aggregate",tasks:["fuzzAll"],
          registered_projects:[":"],registered_targets:[":fuzzCodec"],
          log_file:("logs/"+$empty_path_key+".log"),output_sha256:$empty_path_log_hash,
          aggregates:[{project:":",path:("aggregates/"+$empty_path_key+"/root.tsv"),
            sha256:$empty_path_aggregate_hash,target_count:1,max_parallel_targets:1,total_executions:100,
            executions_by_target:{fuzzCodec:100},plugin_sha256:$plugin_hash,
            targets:["fuzzCodec"]}]}]}' > "$target_receipt"
  release_receipt_schema_valid "$target_receipt" || {
    echo "local-fuzz self-test: realistic release receipt schema was rejected" >&2; return 1;
  }
  jq --arg bad "$(printf '%064d' 1)" '.repositories[0].aggregates[0].plugin_sha256=$bad' \
    "$target_receipt" > "$fixture"
  if release_receipt_schema_valid "$fixture"; then
    echo "local-fuzz self-test: stale consumer plugin identity was accepted" >&2
    return 1
  fi
  jq '.repositories[0].registered_projects=[]' "$target_receipt" > "$fixture"
  if release_receipt_schema_valid "$fixture"; then
    echo "local-fuzz self-test: structurally incomplete receipt was accepted" >&2
    return 1
  fi
  jq '.repositories[0].aggregates[0].total_executions=101' \
    "$target_receipt" > "$fixture"
  if release_receipt_schema_valid "$fixture"; then
    echo "local-fuzz self-test: inconsistent outer execution total was accepted" >&2
    return 1
  fi
  cp "$aggregate_file" "$stale_file"
  printf 'legacy stale\n' > "$legacy_stale_file"
  discovered=$(durable_aggregate_receipts "$path_fixture/repo")
  if [ "$discovered" != "$stale_file" ]; then
    echo "local-fuzz self-test: durable aggregate discovery produced: $discovered" >&2
    return 1
  fi
  if refuse_aggregate_collection_blockers "$path_fixture/repo" self-test \
      >/dev/null 2>&1; then
    echo "local-fuzz self-test: obsolete build-directory receipt was collectable" >&2
    return 1
  fi
  rm -f "$legacy_stale_file"
  mkdir "$legacy_stale_file"
  if refuse_aggregate_collection_blockers "$path_fixture/repo" self-test \
      >/dev/null 2>&1; then
    echo "local-fuzz self-test: non-regular legacy receipt was collectable" >&2
    return 1
  fi
  rmdir "$legacy_stale_file"
  running_entry="$path_fixture/repo/module/.pitest-history/local-fuzz.running"
  printf 'session\tself-test\n' > "$running_entry"
  if refuse_aggregate_collection_blockers "$path_fixture/repo" self-test \
      >/dev/null 2>&1; then
    echo "local-fuzz self-test: regular running sentinel was ignored" >&2
    return 1
  fi
  if ! cmp -s "$stale_file" "$aggregate_file"; then
    echo "local-fuzz self-test: running-sentinel refusal changed the durable receipt" >&2
    return 1
  fi
  rm -f "$running_entry"
  ln -s "$fixture" "$running_entry"
  if refuse_aggregate_collection_blockers "$path_fixture/repo" self-test \
      >/dev/null 2>&1; then
    echo "local-fuzz self-test: symlinked running sentinel was ignored" >&2
    return 1
  fi
  rm -f "$running_entry"
  refuse_aggregate_collection_blockers "$path_fixture/repo" self-test || return 1

  saved_function=$(declare -f aggregate_running_entries)
  aggregate_running_entries() { printf '%s\n' "$running_entry"; return 71; }
  producer_failed=false
  if refuse_aggregate_collection_blockers "$path_fixture/repo" self-test \
      >/dev/null 2>&1; then
    producer_failed=true
  fi
  eval "$saved_function"
  if $producer_failed; then
    echo "local-fuzz self-test: partial running-sentinel scan was accepted" >&2
    return 1
  fi

  run_dir="$path_fixture/collect-bundle"
  published_plugin_sha256=$plugin_hash
  mkdir -p "$run_dir"
  collect_aggregate_receipts self-test "$path_fixture/repo" "$records" self-test \
      ":" ":fuzzCodec" || return 1
  key=$(artifact_key ":")
  if [ "$(jq -sr 'length' "$records")" != 1 ] || \
      [ ! -f "$run_dir/aggregates/self-test/$key.tsv" ]; then
    echo "local-fuzz self-test: production aggregate collection did not retain durable evidence" >&2
    return 1
  fi

  saved_function=$(declare -f durable_aggregate_receipts)
  durable_aggregate_receipts() { printf '%s\n' "$stale_file"; return 71; }
  producer_failed=false
  if collect_aggregate_receipts self-test "$path_fixture/repo" "$records" self-test \
      ":" ":fuzzCodec" >/dev/null 2>&1; then
    producer_failed=true
  fi
  eval "$saved_function"
  if $producer_failed; then
    echo "local-fuzz self-test: partial durable-receipt scan was accepted" >&2
    return 1
  fi

  run_dir="$path_fixture/collect-race-bundle"
  mkdir -p "$run_dir"
  saved_function=$(declare -f refuse_aggregate_collection_blockers)
  aggregate_blocker_checks=0
  refuse_aggregate_collection_blockers() {
    aggregate_blocker_checks=$((aggregate_blocker_checks + 1))
    [ "$aggregate_blocker_checks" -eq 1 ]
  }
  producer_failed=false
  if collect_aggregate_receipts self-test "$path_fixture/repo" "$records" self-test \
      ":" ":fuzzCodec" >/dev/null 2>&1; then
    producer_failed=true
  fi
  eval "$saved_function"
  if $producer_failed; then
    echo "local-fuzz self-test: producer start during aggregate collection was accepted" >&2
    return 1
  fi

  run_dir="$path_fixture/collect-replacement-bundle"
  mkdir -p "$run_dir"
  saved_function=$(declare -f refuse_aggregate_collection_blockers)
  aggregate_blocker_checks=0
  refuse_aggregate_collection_blockers() {
    aggregate_blocker_checks=$((aggregate_blocker_checks + 1))
    if [ "$aggregate_blocker_checks" -eq 2 ]; then
      printf '\n' >> "$stale_file"
    fi
    return 0
  }
  producer_failed=false
  if collect_aggregate_receipts self-test "$path_fixture/repo" "$records" self-test \
      ":" ":fuzzCodec" >/dev/null 2>&1; then
    producer_failed=true
  fi
  eval "$saved_function"
  if $producer_failed; then
    echo "local-fuzz self-test: source receipt replacement after retention was accepted" >&2
    return 1
  fi
  cp "$aggregate_file" "$stale_file"

  printf 'legacy stale\n' > "$legacy_stale_file"
  target_hash=$(sha256_file "$target_receipt")
  jq -n --arg receipt_sha256 "$target_hash" --arg plugin_commit "$plugin_commit" \
    '{schema:2,kind:"local-fuzz-pointer",mode:"release",result:"passed",
      run_id:"run.A",plugin_sha:$plugin_commit,
      bundle:"local-fuzz-runs/run.A/receipt.json",receipt_sha256:$receipt_sha256}' \
    > "$pointer_file"

  # The second receipt row deliberately has an empty, non-final checkout path.
  # Running away from the pointer makes its SHA-named decoy visible if a future
  # @tsv/IFS parser collapses that empty field, while also pinning bundle
  # resolution to dirname(pointer) instead of PWD.
  verify_output_file="$path_fixture/verify-output"
  saved_pwd=$PWD
  cd "$verify_cwd"
  if ! sava_build_dir="$plugin_checkout" manifest="$manifest_fixture" \
      plugin_expected_slug='sava-software/sava-build' \
      verify_receipt "$pointer_file" > "$verify_output_file" 2>&1; then
    cd "$saved_pwd"
    verify_output=$(<"$verify_output_file")
    echo "local-fuzz self-test: production receipt verification failed:" >&2
    printf '%s\n' "$verify_output" >&2
    return 1
  fi
  cd "$saved_pwd"
  verify_output=$(<"$verify_output_file")
  if [ "$pointer_input_path" != "$pointer_file" ] || \
      [ "$resolved_receipt" != "$target_receipt" ] || \
      [ "$bundle_dir" != "$bundle_fixture" ]; then
    echo "local-fuzz self-test: pointer bundle was not resolved relative to its own directory" >&2
    return 1
  fi
  case "$verify_output" in
    *"revalidated 1 consumer checkout(s), 1 unavailable"*) ;;
    *) echo "local-fuzz self-test: verifier summary was incomplete: $verify_output" >&2; return 1 ;;
  esac

  # Each verifier projection must be completed successfully before even its first
  # row is trusted. Process substitution used to hide a producer that emitted a
  # plausible prefix and then failed.
  for projection in receipt_log_rows receipt_aggregate_rows receipt_checkout_rows; do
    saved_function=$(declare -f "$projection")
    eval "$projection() { printf '%s\\n' '{}'; return 71; }"
    producer_failed=false
    if (cd "$verify_cwd" && \
        sava_build_dir="$plugin_checkout" manifest="$manifest_fixture" \
        plugin_expected_slug='sava-software/sava-build' \
        verify_receipt "$pointer_file" >/dev/null 2>&1); then
      producer_failed=true
    fi
    eval "$saved_function"
    if $producer_failed; then
      echo "local-fuzz self-test: partial $projection producer was accepted" >&2
      return 1
    fi
  done

  # Keep the outer object internally consistent while lying about the retained
  # inner TSV. This must reach verify_receipt's cross-layer comparison rather than
  # being rejected by the JSON schema alone.
  cp "$target_receipt" "$fixture"
  jq '(.repositories[0].aggregates[0].total_executions)=101 |
      (.repositories[0].aggregates[0].executions_by_target.fuzzCodec)=101' \
    "$fixture" > "$target_receipt"
  target_hash=$(sha256_file "$target_receipt")
  jq --arg receipt_sha256 "$target_hash" '.receipt_sha256=$receipt_sha256' \
    "$pointer_file" > "$aggregate"
  mv "$aggregate" "$pointer_file"
  if verify_output=$(cd "$verify_cwd" && \
      sava_build_dir="$plugin_checkout" manifest="$manifest_fixture" \
      plugin_expected_slug='sava-software/sava-build' verify_receipt "$pointer_file" 2>&1); then
    echo "local-fuzz self-test: outer execution-count lie was accepted" >&2
    return 1
  fi
  case "$verify_output" in
    *"inner fuzz receipt execution total changed"*) ;;
    *) echo "local-fuzz self-test: execution-count lie was misreported: $verify_output" >&2; return 1 ;;
  esac
  cp "$fixture" "$target_receipt"
  target_hash=$(sha256_file "$target_receipt")
  jq --arg receipt_sha256 "$target_hash" '.receipt_sha256=$receipt_sha256' \
    "$pointer_file" > "$aggregate"
  mv "$aggregate" "$pointer_file"

  printf 'tampered jar\n' > "$jar_file"
  if verify_output=$(cd "$verify_cwd" && \
      sava_build_dir="$plugin_checkout" manifest="$manifest_fixture" \
      plugin_expected_slug='sava-software/sava-build' verify_receipt "$pointer_file" 2>&1); then
    echo "local-fuzz self-test: retained plugin jar corruption was accepted" >&2
    return 1
  fi
  case "$verify_output" in
    *"published 0.0.0-test plugin jar hash mismatch"*) ;;
    *) echo "local-fuzz self-test: jar failure was misreported: $verify_output" >&2; return 1 ;;
  esac
  printf 'plugin jar\n' > "$jar_file"

  mv "$consumer_checkout" "$unavailable_checkout"
  if verify_output=$(cd "$verify_cwd" && \
      sava_build_dir="$plugin_checkout" manifest="$manifest_fixture" \
      plugin_expected_slug='sava-software/sava-build' verify_receipt "$pointer_file" 2>&1); then
    mv "$unavailable_checkout" "$consumer_checkout"
    echo "local-fuzz self-test: zero available consumer checkouts were accepted" >&2
    return 1
  fi
  mv "$unavailable_checkout" "$consumer_checkout"
  case "$verify_output" in
    *"zero consumer checkouts were available"*) ;;
    *) echo "local-fuzz self-test: zero-checkout failure was misreported: $verify_output" >&2; return 1 ;;
  esac

  symlink_file="$bundle_fixture/logs/link.log"
  ln -s "$fixture" "$symlink_file"
  if bundle_artifact_path "logs/link.log" "self-test artifact" >/dev/null 2>&1; then
    echo "local-fuzz self-test: symlinked artifact was accepted" >&2
    return 1
  fi
  verification_inputs_unchanged || return 1
  printf 'changed consumer fuzz log\n' > "$log_file"
  if verification_inputs_unchanged >/dev/null 2>&1; then
    echo "local-fuzz self-test: artifact mutation after verification was accepted" >&2
    return 1
  fi
  printf 'consumer fuzz log\n' > "$log_file"
  verification_inputs_unchanged || return 1
  jq -n '{schema:2,kind:"local-fuzz-pointer",mode:"release",result:"in_progress"}' \
    > "$pointer_file"
  if verification_inputs_unchanged >/dev/null 2>&1; then
    echo "local-fuzz self-test: superseded canonical pointer remained valid" >&2
    return 1
  fi
  if [ ! -f "$stale_file" ]; then
    echo "local-fuzz self-test: last-success aggregate receipt was destroyed" >&2
    return 1
  fi
  rm -f "$stale_file"
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
parallel_targets=1
parallel_targets_set=false
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
    --parallel-targets)
      shift
      [ "$#" -gt 0 ] || { echo "local-fuzz: --parallel-targets requires a value" >&2; exit 2; }
      parallel_targets=$1
      parallel_targets_set=true
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
  if $release_mode || $seconds_set || $parallel_targets_set || $receipt_set || [ -n "$verify_path" ] ||
      [ "$consumer_count" -ne 0 ]; then
    echo "local-fuzz: --self-test combines with no other option" >&2
    exit 2
  fi
  self_test
  exit $?
fi
if [ -n "$verify_path" ]; then
  if $release_mode || $seconds_set || $parallel_targets_set || $receipt_set || [ "$consumer_count" -ne 0 ]; then
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
case "$parallel_targets" in
  ''|*[!0-9]*|0|0*)
    echo "local-fuzz: --parallel-targets must be a positive whole number without leading zeros" >&2
    exit 2
    ;;
esac
parallel_digits=$(printf '%s' "$parallel_targets" | wc -c | tr -d ' ')
if [ "$parallel_digits" -gt 10 ] || \
    { [ "$parallel_digits" -eq 10 ] && [ "$parallel_targets" -gt 2147483647 ]; }; then
  echo "local-fuzz: --parallel-targets must not exceed 2147483647" >&2
  exit 2
fi
if $release_mode; then
  receipt_mode=release
  if ! $seconds_set; then
    echo "local-fuzz: --release requires an explicit --seconds budget" >&2
    exit 2
  fi
  if ! $parallel_targets_set; then
    echo "local-fuzz: --release requires an explicit --parallel-targets bound" >&2
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
published_plugin_sha256=""

printf 'slug\tpath\tsha\torigin\tdirty\tresult\n' > "$preflight_file"
preflight_failed=""
if $release_mode; then
  if [ ! -x "$sava_build_dir/gradlew" ]; then
    preflight_failed="$preflight_failed sava-build(gradlew_not_executable)"
  fi
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
      append_execution_plan_row "$execution_plan" "$slug" "$repo" "$repo_sha" "$remote"
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
  while IFS=$'\t' read -r slug repo; do
    append_execution_plan_row "$execution_plan" "$slug" "$repo"
  done < "$plan_file"
fi

echo "local-fuzz: publishing 0.0.0-test from $sava_build_dir at $plugin_sha"
if ! (cd "$sava_build_dir" && ./gradlew --console=plain \
    publishSavaBuildTestPublicationToSavaTestRepoRepository) > "$plugin_publish_log" 2>&1; then
  cat "$plugin_publish_log"
  write_pointer "failed" ""
  echo "local-fuzz: local plugin publication failed; evidence: $run_dir" >&2
  exit 1
fi
if ! snapshot_published_plugin_jar; then
  write_pointer "failed" ""
  echo "local-fuzz: could not retain the published plugin identity; evidence: $run_dir" >&2
  exit 1
fi
if [ "$(git -C "$sava_build_dir" rev-parse HEAD)" != "$plugin_sha" ] ||
    [ "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)" != "$plugin_origin" ] ||
    [ "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" != "$plugin_status_before" ]; then
  write_pointer "failed" ""
  echo "local-fuzz: plugin checkout changed during local publication; evidence: $run_dir" >&2
  exit 1
fi

record_repo() {
  local slug=$1 repo=$2 sha=$3 origin=$4 dirty_before=$5 dirty_after=$6
  local tasks=$7 targets=$8 projects=$9
  shift 9
  local task_mode=$1 result=$2 output_hash=$3 log_file=$4 aggregates_file=$5
  jq -cn \
    --arg slug "$slug" --arg path "$repo" --arg sha "$sha" --arg origin "$origin" \
    --argjson dirty_before "$dirty_before" --argjson dirty_after "$dirty_after" \
    --arg tasks "$tasks" --arg targets "$targets" --arg projects "$projects" \
    --arg task_mode "$task_mode" \
    --arg result "$result" --arg output_sha256 "$output_hash" --arg log_file "$log_file" \
    --slurpfile aggregates "$aggregates_file" \
    '{slug:$slug,path:$path,sha:$sha,origin:$origin,
      dirty_before:$dirty_before,dirty_after:$dirty_after,
      tasks:($tasks|split("\n")|map(select(length>0))),
      registered_projects:($projects|split("\n")|map(select(length>0))),
      registered_targets:($targets|split("\n")|map(select(length>0))),
      task_mode:$task_mode,result:$result,output_sha256:$output_sha256,
      log_file:$log_file,aggregates:$aggregates}' >> "$records_file"
}

failed=""
expected_execution_rows=$(wc -l < "$plan_file" | tr -d ' ')
processed_execution_rows=0
while read_execution_plan_row slug repo pre_sha pre_origin; do
  processed_execution_rows=$((processed_execution_rows + 1))
  safe_slug=$(artifact_key "$slug")
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
  fuzz_projects=""
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
    if ! (cd "$repo" && run_consumer_gradle ./gradlew --console=plain \
        -PsavaBuildLocalRepo="$local_repo" tasks --all) \
        3<&- > "$out_file" 2>&1; then
      repo_result=task_discovery_failed
    else
      fuzz_projects=$(registered_fuzz_projects "$out_file")
      targets=$(registered_fuzz_tasks "$out_file")
      aggregate_present=false
      if [ -n "$fuzz_projects" ]; then aggregate_present=true; fi
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
    if ! (cd "$repo" && run_consumer_gradle ./gradlew --console=plain --continue --parallel \
        -PsavaBuildLocalRepo="$local_repo" -PmaxFuzzTime="$seconds" \
        -PmaxParallelFuzzTargets="$parallel_targets" "${task_args[@]}") \
        3<&- > "$out_file" 2>&1; then
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
    if ! collect_aggregate_receipts "$slug" "$repo" "$aggregate_records_file" "$safe_slug" \
        "$fuzz_projects" "$targets"; then
      repo_result=aggregate_evidence_invalid
    fi
  fi
  output_hash=$(sha256_file "$log_path")
  record_repo "$slug" "$repo" "$repo_sha" "$remote" "$dirty_before" "$dirty_after" \
    "$tasks" "$targets" "$fuzz_projects" "$task_mode" "$repo_result" "$output_hash" "$log_file" \
    "$aggregate_records_file"
  if [ "$repo_result" != passed ]; then
    failed="$failed $slug($repo_result)"
    echo "local-fuzz: FAILED $slug — $repo_result; log: $log_path" >&2
  fi
done 3< "$execution_plan"
if [ "$processed_execution_rows" -ne "$expected_execution_rows" ]; then
  failed="$failed execution-plan(truncated:$processed_execution_rows/$expected_execution_rows)"
  echo "local-fuzz: execution plan truncated after $processed_execution_rows of $expected_execution_rows rows" >&2
fi

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
  --argjson parallel_targets "$parallel_targets" \
  --arg plugin_sha "$plugin_sha" --arg plugin_tree "$plugin_tree" \
  --arg plugin_origin "$plugin_origin" \
  --arg test_jar_file "$retained_plugin_jar_file" \
  --arg test_jar_sha256 "$published_plugin_sha256" \
  --argjson plugin_dirty_before "$plugin_dirty_before" \
  --argjson plugin_dirty_after "$plugin_dirty_after" \
  --arg manifest_sha256 "$(sha256_file "$manifest")" \
  --arg preflight_sha256 "$(sha256_file "$preflight_file")" \
  --arg publish_output_sha256 "$(sha256_file "$plugin_publish_log")" \
  --slurpfile repositories "$records_file" \
  '{schema:3,kind:"local-fuzz-receipt",mode:$mode,run_id:$run_id,
    generated_at:$generated_at,result:$result,seconds_per_target:$seconds_per_target,
    parallel_targets:$parallel_targets,
    plugin:{sha:$plugin_sha,tree:$plugin_tree,origin:$plugin_origin,
      dirty_before:$plugin_dirty_before,dirty_after:$plugin_dirty_after,
      test_jar_file:$test_jar_file,test_jar_sha256:$test_jar_sha256},
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
