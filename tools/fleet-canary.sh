#!/usr/bin/env bash
# Consumer-fleet canary for an unreleased sava-build checkout.
#
# Ordinary modes are deliberately convenient:
#   tools/fleet-canary.sh                         # available manifest siblings
#   tools/fleet-canary.sh --deep                  # manifest's annotated deep legs
#   tools/fleet-canary.sh ../sava ../ravina:pitestCalls
#
# Release mode is deliberately strict:
#   tools/fleet-canary.sh --release [--allow-advisories]
#
# It preflights every manifest checkout before publishing, requires matching
# GitHub remotes and clean stable revisions, and runs hardeningCertify against
# the unreleased plugin. Evidence is an immutable run directory under
# build/hardening/fleet-canary-runs; the canonical receipt is an atomic pointer
# whose in_progress state invalidates an older pass as soon as a new run starts.
set -euo pipefail
export LC_ALL=C

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd -P)
local_repo="$sava_build_dir/build/sava-test-repo"
published_plugin_jar="$local_repo/software/sava/sava-build/0.0.0-test/sava-build-0.0.0-test.jar"
retained_plugin_jar_file="plugin-0.0.0-test.jar"
manifest="$sava_build_dir/tools/fleet-manifest.txt"
canonical_receipt="$sava_build_dir/build/hardening/fleet-canary-receipt.json"
plugin_expected_slug="sava-software/sava-build"
pitest_certification_receipt_name="pitest-certification.tsv"
pitest_certification_sentinel_name="pitest-certification.running"
resolution_notice="resolved every 'software.sava.build*' plugin to 0.0.0-test"

usage() {
  cat <<'EOF'
Usage:
  tools/fleet-canary.sh
  tools/fleet-canary.sh --deep
  tools/fleet-canary.sh <consumer-dir>[:<pitestSuiteTask>] ...
  tools/fleet-canary.sh --release [--allow-advisories] [--receipt <path>]
  tools/fleet-canary.sh --verify-receipt <path>
  tools/fleet-canary.sh --self-test

Ordinary no-argument mode skips unavailable manifest siblings. --deep is valid
only in manifest mode; append :<pitestSuiteTask> to an explicit repo instead.
Release mode requires the complete clean fleet and writes a SHA-bound evidence
bundle. Findings fail unless --allow-advisories records their review.
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

snapshot_published_plugin_jar() {
  local source_before source_after retained_hash destination
  reject_symlink_components "$published_plugin_jar" "published plugin jar" || return 1
  if [ ! -f "$published_plugin_jar" ]; then
    echo "fleet-canary: publication produced no plugin jar at $published_plugin_jar" >&2
    return 1
  fi
  source_before=$(sha256_file "$published_plugin_jar") || return 1
  published_plugin_sha256=$source_before
  [ -n "${run_dir:-}" ] || return 0
  destination="$run_dir/$retained_plugin_jar_file"
  reject_symlink_components "$destination" "retained published plugin jar" || return 1
  cp "$published_plugin_jar" "$destination"
  reject_symlink_components "$published_plugin_jar" "published plugin jar" || return 1
  source_after=$(sha256_file "$published_plugin_jar") || return 1
  retained_hash=$(sha256_file "$destination") || return 1
  if [ "$source_before" != "$source_after" ] || [ "$source_after" != "$retained_hash" ]; then
    echo "fleet-canary: published plugin jar changed while being retained" >&2
    return 1
  fi
}

require_jq() {
  if ! command -v jq >/dev/null 2>&1; then
    echo "fleet-canary: jq is required for release receipts" >&2
    exit 2
  fi
}

manifest_slugs() {
  awk 'NF && $1 !~ /^#/ { print $1 }' "$manifest"
}

origin_slug() {
  local url=$1
  url=${url%.git}
  case "$url" in
    git@github.com:*) printf '%s\n' "${url#git@github.com:}" ;;
    https://github.com/*) printf '%s\n' "${url#https://github.com/}" ;;
    ssh://git@github.com/*) printf '%s\n' "${url#ssh://git@github.com/}" ;;
    *) printf '%s\n' "" ;;
  esac
}

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

registered_certification_projects() {
  awk '
    function normalize(task) {
      return task ~ /^:/ ? task : ":" task
    }
    / - Fresh, full, strict mutation certification; writes build\/hardening\/pitest-certification[.]tsv[.]/ {
      task=$0
      sub(/ - Fresh, full, strict mutation certification;.*$/, "", task)
      task=normalize(task)
      sub(/:hardeningCertify$/, "", task)
      print (task == "" ? ":" : task)
    }
  ' "$1" | sort -u
}

certification_evidence_matches() {
  local expected_projects=$1 records=$2 actual_projects
  actual_projects=$(jq -sr 'map(.project) | sort | .[]' "$records") || return 1
  [ "$actual_projects" = "$expected_projects" ]
}

reject_symlink_components() {
  local target=$1 label=${2:-evidence path} probe
  case "$target" in
    /*) probe=$target ;;
    *) probe="$PWD/$target" ;;
  esac
  while [ "$probe" != "/" ]; do
    if [ -L "$probe" ]; then
      echo "fleet-canary: refusing symlinked $label component: $probe" >&2
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
  case "$base" in ''|.|..) echo "fleet-canary: invalid receipt filename '$base'" >&2; return 1 ;; esac
  reject_symlink_components "$raw_parent" "receipt directory" || return 1
  mkdir -p "$raw_parent"
  physical_parent=$(cd "$raw_parent" && pwd -P)
  receipt_path="$physical_parent/$base"
  receipt_parent=$physical_parent
  reject_symlink_components "$receipt_path" "receipt" || return 1
  case "$receipt_path" in
    */fleet-canary-runs/*)
      echo "fleet-canary: --receipt must name a pointer outside immutable run bundles" >&2
      return 1
      ;;
  esac
  case "$receipt_path" in
    "$sava_build_dir"/*)
      rel=${receipt_path#"$sava_build_dir"/}
      if ! git -C "$sava_build_dir" check-ignore -q -- "$rel"; then
        echo "fleet-canary: receipt inside the plugin checkout must be git-ignored: $rel" >&2
        return 1
      fi
      ;;
  esac
  if [ -e "$receipt_path" ] && ! jq -e '
      (.schema == 1 and .mode == "release") or
      (.schema == 2 and .kind == "fleet-canary-pointer")
    ' "$receipt_path" >/dev/null 2>&1; then
    echo "fleet-canary: refusing to replace a file that is not fleet evidence: $receipt_path" >&2
    return 1
  fi
}

write_pointer() {
  local result=$1 receipt_hash=${2:-} tmp generated_at
  reject_symlink_components "$receipt_path" "receipt" || return 1
  if [ "$result" != in_progress ] &&
      ! jq -e --arg run_id "$run_id" \
        '.schema == 2 and .kind == "fleet-canary-pointer" and .run_id == $run_id' \
        "$receipt_path" >/dev/null 2>&1; then
    echo "fleet-canary: run $run_id was superseded; refusing to replace the newer pointer" >&2
    return 1
  fi
  generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  tmp=$(mktemp "$receipt_parent/.fleet-canary-pointer.XXXXXX")
  jq -n \
    --arg result "$result" --arg generated_at "$generated_at" --arg run_id "$run_id" \
    --arg bundle "$bundle_rel" --arg receipt_sha256 "$receipt_hash" \
    --arg plugin_sha "$plugin_sha" \
    '{schema:2,kind:"fleet-canary-pointer",mode:"release",result:$result,
      generated_at:$generated_at,run_id:$run_id,plugin_sha:$plugin_sha,
      bundle:$bundle,receipt_sha256:$receipt_sha256}' > "$tmp"
  mv "$tmp" "$receipt_path"
}

validate_inner_certification() {
  awk -F '\t' '
    $1 == "schema" { schemaRows++; if (NF != 2 || $2 != "4") invalid=1; next }
    $1 == "project" { projectRows++; if (NF != 2 || $2 == "") invalid=1; next }
    $1 == "session" { sessionRows++; if (NF != 2 || $2 == "") invalid=1; next }
    $1 == "mode" {
      modeRows++; if (NF != 2 || $2 != "fresh-full-strict") invalid=1; next
    }
    $1 == "columns" {
      columnsRows++
      if (NF != 13 || $2 != "suite" || $3 != "name" || $4 != "invocation" ||
          $5 != "reportSha256" || $6 != "sourceSha256" || $7 != "classesSha256" ||
          $8 != "configurationSha256" || $9 != "pitestVersion" ||
          $10 != "pluginSha256" || $11 != "toolClasspathSha256" ||
          $12 != "recordInputsSha256" || $13 != "recordPitestVersion") invalid=1
      next
    }
    $1 == "suite" {
      suites++
      if (NF != 12 || $2 == "" || $3 == "" || $8 == "" || $12 == "" || seen[$2]++) invalid=1
      for (i=4; i<=11; i++) {
        if (i == 8) continue
        if (length($i) != 64 || $i ~ /[^0-9a-f]/) invalid=1
      }
      plugins[$9]=1
      next
    }
    NF > 0 { invalid=1 }
    END {
      for (plugin in plugins) pluginCount++
      if (invalid || schemaRows != 1 || projectRows != 1 || sessionRows != 1 ||
          modeRows != 1 || columnsRows != 1 || (suites > 0 && pluginCount != 1)) exit 1
      print suites + 0
    }
  ' "$1"
}

inner_certification_plugin_sha() {
  awk -F '\t' '
    $1 == "suite" {
      if (plugin == "") plugin=$9
      else if (plugin != $9) exit 1
    }
    END { print plugin }
  ' "$1"
}

inner_certification_project() {
  awk -F '\t' '$1 == "project" { print $2; exit }' "$1"
}

release_receipt_schema_valid() {
  jq -e '
    . as $receipt |
    .schema == 3 and .kind == "fleet-canary-receipt" and
    .mode == "release" and .result == "passed" and
    (.run_id | test("^run[.][A-Za-z0-9]+$")) and
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
    (([.repositories[].certifications[].path] | unique | length) ==
      ([.repositories[].certifications[].path] | length)) and
    all(.repositories[];
      .result == "passed" and .dirty_before == false and .dirty_after == false and
      (.sha | test("^[0-9a-f]{40}$")) and (.path | type == "string") and
      (.origin | type == "string" and length > 0) and
      (.tasks | type == "array" and
        any(.[]; . == "hardeningCertify") and any(.[]; . == "agentsTemplateInSync")) and
      (.certification_projects | type == "array" and length > 0) and
      all(.certification_projects[];
        type == "string" and test("^(:|:[^:\\t\\r\\n]+(:[^:\\t\\r\\n]+)*)$")) and
      ((.certification_projects | unique | length) == (.certification_projects | length)) and
      (.log_file | test("^logs/[A-Za-z0-9._-]+[.]log$")) and
      (.output_sha256 | test("^[0-9a-f]{64}$")) and
      (.findings | type == "string") and
      (.certifications | type == "array" and length > 0) and
      (([.certifications[].project] | sort) == (.certification_projects | sort)) and
      ([.certifications[].suite_count] | add > 0) and
      all(.certifications[];
        (.project | type == "string" and
          test("^(:|:[^:\\t\\r\\n]+(:[^:\\t\\r\\n]+)*)$")) and
        (.path | test("^certifications/[A-Za-z0-9._/-]+[.]tsv$")) and
        (.path | split("/") | all(.[]; . != "" and . != "." and . != "..")) and
        (.sha256 | test("^[0-9a-f]{64}$")) and
        (.suite_count | type == "number" and . >= 0) and
        ((.suite_count == 0 and .plugin_sha256 == "") or
          (.suite_count > 0 and (.plugin_sha256 | test("^[0-9a-f]{64}$")) and
            .plugin_sha256 == $receipt.plugin.test_jar_sha256)))) and
    ([.repositories[].certifications[] | select(.suite_count > 0) | .plugin_sha256] |
      unique | length == 1) and
    (.advisories_acknowledged | type == "boolean") and
    (([.repositories[] | select(.findings != "")] | length) == 0 or
      .advisories_acknowledged == true)
  ' "$1" >/dev/null
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
    echo "fleet-canary: no certification receipt at $requested" >&2
    return 1
  fi
  pointer_before=$(sha256_file "$requested") || return 1
  pointer_json=$(<"$requested")
  pointer_after=$(sha256_file "$requested") || return 1
  if [ "$pointer_before" != "$pointer_after" ]; then
    echo "fleet-canary: receipt changed while it was being resolved: $requested" >&2
    return 1
  fi
  if jq -e '.schema == 2 and .kind == "fleet-canary-pointer"' <<< "$pointer_json" >/dev/null 2>&1; then
    if ! jq -e '
        .mode == "release" and .result == "passed" and
        (.run_id | test("^run[.][A-Za-z0-9]+$")) and
        .bundle == ("fleet-canary-runs/" + .run_id + "/receipt.json") and
        (.plugin_sha | test("^[0-9a-f]{40}$")) and
        (.receipt_sha256 | test("^[0-9a-f]{64}$"))
      ' <<< "$pointer_json" >/dev/null; then
      echo "fleet-canary: canonical pointer is not a completed passing run: $requested" >&2
      return 1
    fi
    input_parent=$(cd "$(dirname "$requested")" && pwd -P)
    pointer_bundle=$(jq -r '.bundle' <<< "$pointer_json")
    target="$input_parent/$pointer_bundle"
    reject_symlink_components "$target" "bundle receipt" || return 1
    if [ ! -f "$target" ]; then
      echo "fleet-canary: pointed-to bundle receipt is missing: $target" >&2
      return 1
    fi
    expected=$(jq -r '.receipt_sha256' <<< "$pointer_json")
    if [ "$(sha256_file "$target")" != "$expected" ]; then
      echo "fleet-canary: bundle receipt hash does not match the canonical pointer: $target" >&2
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
    echo "fleet-canary: bundle receipt disappeared during verification: $resolved_receipt" >&2
    return 1
  fi
  current=$(sha256_file "$resolved_receipt") || return 1
  if [ "$current" != "$resolved_receipt_initial_sha" ]; then
    echo "fleet-canary: bundle receipt changed during verification: $resolved_receipt" >&2
    return 1
  fi
  if [ -n "$pointer_input_path" ]; then
    reject_symlink_components "$pointer_input_path" "receipt" || return 1
    if [ ! -f "$pointer_input_path" ]; then
      echo "fleet-canary: canonical pointer disappeared during verification: $pointer_input_path" >&2
      return 1
    fi
    current=$(sha256_file "$pointer_input_path") || return 1
    if [ "$current" != "$pointer_input_sha" ]; then
      echo "fleet-canary: canonical pointer was superseded during verification: $pointer_input_path" >&2
      return 1
    fi
  fi
}

bundle_artifact_path() {
  local relative=$1 label=$2 candidate parent physical_parent
  case "$relative" in
    ''|/*)
      echo "fleet-canary: refusing invalid $label path: $relative" >&2
      return 1
      ;;
  esac
  case "/$relative/" in
    */../*|*/./*|*//*)
      echo "fleet-canary: refusing $label path outside its bundle: $relative" >&2
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
        echo "fleet-canary: refusing $label path outside its bundle: $relative" >&2
        return 1
        ;;
    esac
  fi
  printf '%s\n' "$candidate"
}

verify_artifact() {
  local relative=$1 expected=$2 label=$3 path
  verified_artifact_path=""
  path=$(bundle_artifact_path "$relative" "$label") || return 1
  reject_symlink_components "$path" "$label" || return 1
  if [ ! -f "$path" ]; then
    echo "fleet-canary: missing $label: $path" >&2
    return 1
  fi
  if [ "$(sha256_file "$path")" != "$expected" ]; then
    echo "fleet-canary: $label hash mismatch: $path" >&2
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
    echo "fleet-canary: $label disappeared during verification: $path" >&2
    return 1
  fi
  current=$(sha256_file "$path") || return 1
  if [ "$current" != "$expected" ]; then
    echo "fleet-canary: $label changed during verification: $path" >&2
    return 1
  fi
}

verify_receipt() {
  local requested=$1 certified_sha certified_tree actual_tree recorded_manifest_sha
  local current_manifest_sha manifest_count receipt_count unexpected slug
  local log_file log_hash cert_file cert_hash cert_suites actual_suites cert_path
  local cert_plugin actual_plugin recorded_project actual_project
  local repo recorded_sha recorded_origin current_sha current_origin plugin_origin
  local checkout_count=0 unavailable_count=0
  require_jq
  resolve_receipt "$requested" || return 1
  if ! release_receipt_schema_valid "$resolved_receipt"; then
    echo "fleet-canary: receipt is not a successful strict evidence bundle: $resolved_receipt" >&2
    return 1
  fi
  if [ -n "$pointer_run_id" ] &&
      { [ "$(jq -r '.run_id' "$resolved_receipt")" != "$pointer_run_id" ] ||
        [ "$(jq -r '.plugin.sha' "$resolved_receipt")" != "$pointer_plugin_sha" ]; }; then
    echo "fleet-canary: pointer identity does not match its bundle receipt" >&2
    return 1
  fi

  certified_sha=$(jq -r '.plugin.sha' "$resolved_receipt")
  certified_tree=$(jq -r '.plugin.tree' "$resolved_receipt")
  if ! git -C "$sava_build_dir" cat-file -e "$certified_sha^{commit}" 2>/dev/null; then
    echo "fleet-canary: certified plugin commit $certified_sha is not in this checkout" >&2
    return 1
  fi
  actual_tree=$(git -C "$sava_build_dir" rev-parse "$certified_sha^{tree}")
  if [ "$actual_tree" != "$certified_tree" ]; then
    echo "fleet-canary: receipt tree $certified_tree does not belong to $certified_sha" >&2
    return 1
  fi
  if ! git -C "$sava_build_dir" merge-base --is-ancestor "$certified_sha" HEAD; then
    echo "fleet-canary: certified commit $certified_sha is not an ancestor of HEAD" >&2
    return 1
  fi
  if [ -n "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" ]; then
    echo "fleet-canary: current plugin checkout is dirty; it cannot match release evidence" >&2
    return 1
  fi
  plugin_origin=$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)
  if [ "$plugin_origin" != "$(jq -r '.plugin.origin' "$resolved_receipt")" ] ||
      [ "$(origin_slug "$plugin_origin")" != "$plugin_expected_slug" ]; then
    echo "fleet-canary: plugin origin changed after certification: $plugin_origin" >&2
    return 1
  fi
  unexpected=""
  while IFS= read -r changed; do
    case "$changed" in
      .release-please-manifest.json|CHANGELOG.md) ;;
      *) unexpected="$unexpected${unexpected:+$'\n'}$changed" ;;
    esac
  done < <(git -C "$sava_build_dir" diff --name-only "$certified_sha"..HEAD --)
  if [ -n "$unexpected" ]; then
    echo "fleet-canary: non-release-metadata files changed after certification:" >&2
    while IFS= read -r changed; do printf '  %s\n' "$changed" >&2; done <<< "$unexpected"
    return 1
  fi

  recorded_manifest_sha=$(jq -r '.manifest_sha256' "$resolved_receipt")
  current_manifest_sha=$(sha256_file "$manifest")
  if [ "$recorded_manifest_sha" != "$current_manifest_sha" ]; then
    echo "fleet-canary: fleet manifest changed after certification" >&2
    return 1
  fi
  manifest_count=$(manifest_slugs | wc -l | tr -d ' ')
  receipt_count=$(jq '.repositories | length' "$resolved_receipt")
  if [ "$manifest_count" != "$receipt_count" ]; then
    echo "fleet-canary: receipt covers $receipt_count repos; manifest requires $manifest_count" >&2
    return 1
  fi
  while IFS= read -r slug; do
    if ! jq -e --arg slug "$slug" '[.repositories[] | select(.slug == $slug)] | length == 1' \
        "$resolved_receipt" >/dev/null; then
      echo "fleet-canary: receipt does not contain exactly one record for $slug" >&2
      return 1
    fi
  done < <(manifest_slugs)

  verify_artifact "$(jq -r '.preflight_file' "$resolved_receipt")" \
    "$(jq -r '.preflight_sha256' "$resolved_receipt")" "preflight inventory" || return 1
  verify_artifact "$(jq -r '.publish_log_file' "$resolved_receipt")" \
    "$(jq -r '.publish_output_sha256' "$resolved_receipt")" "plugin publish log" || return 1
  verify_artifact "$(jq -r '.plugin.test_jar_file' "$resolved_receipt")" \
    "$(jq -r '.plugin.test_jar_sha256' "$resolved_receipt")" \
    "published 0.0.0-test plugin jar" || return 1
  while IFS=$'\t' read -r slug log_file log_hash; do
    if ! log_path_matches_slug "$slug" "$log_file"; then
      echo "fleet-canary: consumer log is not bound to repository $slug: $log_file" >&2
      return 1
    fi
    verify_artifact "$log_file" "$log_hash" "consumer log" || return 1
  done < <(jq -r '.repositories[] | [.slug,.log_file,.output_sha256] | @tsv' "$resolved_receipt")
  while IFS=$'\t' read -r slug cert_file cert_hash cert_suites recorded_project cert_plugin; do
    if ! artifact_path_matches_slug "certifications" "$slug" "$cert_file"; then
      echo "fleet-canary: inner PIT certification is not bound to repository $slug: $cert_file" >&2
      return 1
    fi
    verify_artifact "$cert_file" "$cert_hash" "inner PIT certification" || return 1
    cert_path=$verified_artifact_path
    actual_suites=$(validate_inner_certification "$cert_path") || {
      echo "fleet-canary: invalid inner PIT certification: $cert_path" >&2
      return 1
    }
    if [ "$actual_suites" != "$cert_suites" ]; then
      echo "fleet-canary: inner certification suite count changed: $cert_path" >&2
      return 1
    fi
    actual_plugin=$(inner_certification_plugin_sha "$cert_path") || {
      echo "fleet-canary: inconsistent plugin provenance: $cert_path" >&2
      return 1
    }
    if [ "$actual_plugin" != "$cert_plugin" ]; then
      echo "fleet-canary: inner certification plugin hash changed: $cert_path" >&2
      return 1
    fi
    if [ "$actual_suites" -gt 0 ] &&
        [ "$actual_plugin" != "$(jq -r '.plugin.test_jar_sha256' "$resolved_receipt")" ]; then
      echo "fleet-canary: inner certification does not describe the retained published plugin jar: $cert_path" >&2
      return 1
    fi
    actual_project=$(inner_certification_project "$cert_path")
    if [ "$actual_project" != "$recorded_project" ]; then
      echo "fleet-canary: inner certification project changed: $cert_path" >&2
      return 1
    fi
    verify_artifact_unchanged "$cert_path" "$cert_hash" "inner PIT certification" || return 1
  done < <(jq -r '.repositories[] as $repository | $repository.certifications[] |
      [$repository.slug,.path,.sha256,.suite_count,.project,.plugin_sha256] | @tsv' "$resolved_receipt")

  while IFS=$'\t' read -r slug repo recorded_sha recorded_origin; do
    if [ ! -e "$repo" ]; then
      echo "fleet-canary: NOTE $slug checkout unavailable; retained artifacts were verified" >&2
      unavailable_count=$((unavailable_count + 1))
      continue
    fi
    if ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      echo "fleet-canary: current checkout for $slug is not a Git worktree: $repo" >&2
      return 1
    fi
    current_sha=$(git -C "$repo" rev-parse HEAD)
    current_origin=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
    if [ "$current_sha" != "$recorded_sha" ]; then
      echo "fleet-canary: $slug moved from certified $recorded_sha to $current_sha" >&2
      return 1
    fi
    if [ "$current_origin" != "$recorded_origin" ] || [ "$(origin_slug "$current_origin")" != "$slug" ]; then
      echo "fleet-canary: $slug origin changed after certification: $current_origin" >&2
      return 1
    fi
    if [ -n "$(git -C "$repo" status --porcelain --untracked-files=all)" ]; then
      echo "fleet-canary: $slug checkout is dirty after certification" >&2
      return 1
    fi
    checkout_count=$((checkout_count + 1))
  done < <(jq -r '.repositories[] | [.slug,.path,.sha,.origin] | @tsv' "$resolved_receipt")

  verification_inputs_unchanged || return 1
  if [ "$checkout_count" -eq 0 ]; then
    echo "fleet-canary: retained artifacts for $receipt_count repos verified, but zero consumer checkouts were available; refusing full verification" >&2
    return 1
  fi
  echo "fleet-canary: retained artifacts verified for $receipt_count repos; revalidated $checkout_count consumer checkout(s), $unavailable_count unavailable; plugin $certified_sha"
}

# Deliberately coupled to emitted warning text. The first pattern remains pinned
# by HardeningRatchetFunctionalTest; advisory_pattern adds known warning families
# that do not enter the plugin's end-of-build advisory service.
findings_pattern='malformed row|not in the audited set|appear nowhere|match no mutant|no argument in config|advisory finding|written by PIT|swallowed by excludedClasses|match no swallowed|suppress nothing|marker dance'
advisory_pattern="$findings_pattern|mutating [0-9]+ test-source class|enabled mutator set cannot mutate|the recorded decline of .* is stale|declares no seedCorpus|recorded seedCorpus decline is stale"
deep_pattern='flipped SURVIVED -> TIMED_OUT|flipped NO_COVERAGE -> TIMED_OUT|timed-out drift vs previous run|predates the current stash format'

reprint_findings() {
  awk -v pat="$1" '
    $0 ~ pat { print; keep = 1; next }
    keep == 1 && /^  / { print; next }
    { keep = 0 }
  ' "$2"
}

self_test() {
  local fixture records output projects slugs basenames suites hash
  local path_fixture pointer_file target_receipt artifact_file symlink_file plan_probe stdin_probe
  local target_hash artifact_hash resolved_path saved_pwd long_name key repo_key plan_rows _stolen
  local probe_slug probe_repo probe_deep probe_sha probe_origin
  local row_suites zero_project zero_plugin
  require_jq
  fixture=$(mktemp)
  records=$(mktemp)
  path_fixture=$(mktemp -d)
  plan_probe=$(mktemp)
  stdin_probe=$(mktemp)
  path_fixture=$(cd "$path_fixture" && pwd -P)
  pointer_file="$path_fixture/pointer.json"
  target_receipt="$path_fixture/fleet-canary-runs/run.A/receipt.json"
  artifact_file="$path_fixture/fleet-canary-runs/run.A/logs/ok.log"
  symlink_file="$path_fixture/fleet-canary-runs/run.A/logs/link.log"
  trap '
    rm -f "$fixture" "$records" "$plan_probe" "$stdin_probe" "$symlink_file" "$artifact_file" \
      "$target_receipt" "$pointer_file"
    rmdir "$path_fixture/fleet-canary-runs/run.A/logs" \
      "$path_fixture/fleet-canary-runs/run.A" "$path_fixture/fleet-canary-runs" \
      "$path_fixture" 2>/dev/null || true
  ' RETURN
  printf '%s\n' \
    "hardening: 1 advisory finding(s) across 1 suite(s)" \
    "pitest 'x': mutating 1 test-source class(es)" \
    "pitest 'x': enabled mutator set cannot mutate arithmetic" \
    "fuzz target 'x' declares no seedCorpus" > "$fixture"
  output=$(reprint_findings "$advisory_pattern" "$fixture")
  for expected in "advisory finding" "test-source class" "cannot mutate" "no seedCorpus"; do
    case "$output" in *"$expected"*) ;; *) echo "fleet-canary self-test: missed $expected" >&2; return 1 ;; esac
  done
  printf '%s\n' \
    "hardeningCertify - Fresh, full, strict mutation certification; writes build/hardening/pitest-certification.tsv." \
    ":api+client:hardeningCertify - Fresh, full, strict mutation certification; writes build/hardening/pitest-certification.tsv." \
    "space project:hardeningCertify - Fresh, full, strict mutation certification; writes build/hardening/pitest-certification.tsv." \
    "hardeningCertifyPreflight - Internal setup" > "$fixture"
  projects=$(registered_certification_projects "$fixture")
  if [ "$projects" != ":
:api+client
:space project" ]; then
    echo "fleet-canary self-test: certification project parser produced: $projects" >&2
    return 1
  fi
  printf '%s\n' '{"project":":"}' '{"project":":api+client"}' > "$records"
  if certification_evidence_matches "$projects" "$records"; then
    echo "fleet-canary self-test: missing certification project evidence was accepted" >&2
    return 1
  fi
  printf '%s\n' '{"project":":space project"}' >> "$records"
  certification_evidence_matches "$projects" "$records" || {
    echo "fleet-canary self-test: exact certification project evidence was rejected" >&2; return 1;
  }
  printf '%s\n' '{"project":":api+client"}' >> "$records"
  if certification_evidence_matches "$projects" "$records"; then
    echo "fleet-canary self-test: duplicate certification project evidence was accepted" >&2
    return 1
  fi
  hash=$(printf '%064d' 0)
  printf 'schema\t4\nproject\t:\nsession\tsession-1\nmode\tfresh-full-strict\n' > "$fixture"
  printf 'columns\tsuite\tname\tinvocation\treportSha256\tsourceSha256\tclassesSha256\tconfigurationSha256\tpitestVersion\tpluginSha256\ttoolClasspathSha256\trecordInputsSha256\trecordPitestVersion\n' >> "$fixture"
  printf 'suite\tcodec\tinvocation-1\t%s\t%s\t%s\t%s\t1.17.2\t%s\t%s\t%s\tno-record\n' \
    "$hash" "$hash" "$hash" "$hash" "$hash" "$hash" "$hash" >> "$fixture"
  suites=$(validate_inner_certification "$fixture")
  [ "$suites" = 1 ] || {
    echo "fleet-canary self-test: inner certification count was $suites" >&2; return 1;
  }
  cp "$fixture" "$records"
  awk -F '\t' 'BEGIN { OFS="\t" } $1 == "suite" { $11="invalid" } { print }' \
    "$records" > "$fixture"
  if validate_inner_certification "$fixture" >/dev/null 2>&1; then
    echo "fleet-canary self-test: invalid record-input fingerprint was accepted" >&2
    return 1
  fi
  cp "$records" "$fixture"
  printf 'suite\tcodec\tinvocation-2\t%s\t%s\t%s\t%s\t1.17.2\t%s\t%s\t%s\tno-record\n' \
    "$hash" "$hash" "$hash" "$hash" "$hash" "$hash" "$hash" >> "$fixture"
  if validate_inner_certification "$fixture" >/dev/null 2>&1; then
    echo "fleet-canary self-test: duplicate suite evidence was accepted" >&2
    return 1
  fi
  printf 'schema\t4\nproject\t:\nsession\tsession-1\nmode\tfresh-full-strict\n' > "$fixture"
  printf 'columns\tsuite\tname\tinvocation\treportSha256\tWRONG\n' >> "$fixture"
  if validate_inner_certification "$fixture" >/dev/null 2>&1; then
    echo "fleet-canary self-test: malformed columns header was accepted" >&2
    return 1
  fi
  printf 'schema\t3\nproject\t:\nsession\tsession-1\nmode\tfresh-full-strict\n' > "$fixture"
  printf 'columns\tsuite\tname\tinvocation\treportSha256\tsourceSha256\tclassesSha256\tconfigurationSha256\tpitestVersion\tpluginSha256\ttoolClasspathSha256\trecordPitestVersion\n' >> "$fixture"
  printf 'suite\tcodec\tinvocation-1\t%s\t%s\t%s\t%s\t1.17.2\t%s\t%s\tno-record\n' \
    "$hash" "$hash" "$hash" "$hash" "$hash" "$hash" >> "$fixture"
  if validate_inner_certification "$fixture" >/dev/null 2>&1; then
    echo "fleet-canary self-test: obsolete inner certification schema was accepted" >&2
    return 1
  fi
  if bundle_artifact_path "../escape" "self-test artifact" >/dev/null 2>&1 ||
      bundle_artifact_path "logs/../../escape" "self-test artifact" >/dev/null 2>&1; then
    echo "fleet-canary self-test: bundle traversal was accepted" >&2
    return 1
  fi
  if [ "$(artifact_key 'a_b/c')" = "$(artifact_key 'a/b_c')" ]; then
    echo "fleet-canary self-test: artifact key collision" >&2
    return 1
  fi
  if artifact_path_matches_slug "certifications" "a_b/c" \
      "certifications/$(artifact_key 'a/b_c')/receipt.tsv"; then
    echo "fleet-canary self-test: cross-repository certification alias was accepted" >&2
    return 1
  fi
  long_name=$(printf '%0200d' 0)
  key=$(artifact_key "$long_name")
  [ "${#key}" = 64 ] || {
    echo "fleet-canary self-test: long artifact key has ${#key} characters" >&2; return 1;
  }
  artifact_path_matches_slug "certifications" "a_b/c" \
    "certifications/$(artifact_key 'a_b/c')/receipt.tsv" || {
      echo "fleet-canary self-test: matching repository certification path was rejected" >&2; return 1;
    }
  log_path_matches_slug "a_b/c" "logs/$(artifact_key 'a_b/c').log" || {
    echo "fleet-canary self-test: matching repository log path was rejected" >&2; return 1;
  }
  if log_path_matches_slug "a_b/c" "logs/$(artifact_key 'a/b_c').log"; then
    echo "fleet-canary self-test: cross-repository log alias was accepted" >&2
    return 1
  fi
  printf 'one\037/repo-one\037\037sha-one\037origin-one\n' > "$plan_probe"
  printf 'two\037/repo-two\037pitestCodec\037sha-two\037origin-two\n' >> "$plan_probe"
  printf 'stdin may be consumed by a child\n' > "$stdin_probe"
  plan_rows=0
  while IFS=$'\037' read -r -u 3 \
      probe_slug probe_repo probe_deep probe_sha probe_origin; do
    if [ "$probe_slug" = one ] &&
        { [ "$probe_repo" != /repo-one ] || [ -n "$probe_deep" ] ||
          [ "$probe_sha" != sha-one ] || [ "$probe_origin" != origin-one ]; }; then
      echo "fleet-canary self-test: execution plan collapsed an empty field" >&2
      return 1
    fi
    IFS= read -r _stolen || true
    plan_rows=$((plan_rows + 1))
  done 3< "$plan_probe" < "$stdin_probe"
  [ "$plan_rows" -eq 2 ] || {
    echo "fleet-canary self-test: execution plan shared stdin and was truncated" >&2; return 1;
  }

  mkdir -p "$(dirname "$artifact_file")"
  repo_key=$(artifact_key "sava-software/example")
  jq -n --arg hash "$hash" --arg repo_key "$repo_key" \
    '{schema:3,kind:"fleet-canary-receipt",mode:"release",result:"passed",run_id:"run.A",
      plugin:{sha:"0000000000000000000000000000000000000000",
        tree:"0000000000000000000000000000000000000000",
        origin:"git@github.com:sava-software/sava-build.git",dirty_before:false,dirty_after:false,
        test_jar_file:"plugin-0.0.0-test.jar",test_jar_sha256:$hash},
      manifest_sha256:$hash,preflight_file:"preflight.tsv",preflight_sha256:$hash,
      publish_log_file:"plugin-publish.log",publish_output_sha256:$hash,
      advisories_acknowledged:false,
      repositories:[{slug:"sava-software/example",path:"/tmp/example",
        sha:"0000000000000000000000000000000000000000",origin:"git@github.com:sava-software/example.git",
        dirty_before:false,dirty_after:false,result:"passed",
        tasks:["hardeningCertify","agentsTemplateInSync"],certification_projects:[":",":empty"],
        log_file:("logs/"+$repo_key+".log"),output_sha256:$hash,findings:"",
        certifications:[{project:":",path:("certifications/"+$repo_key+"/root.tsv"),
          sha256:$hash,suite_count:1,plugin_sha256:$hash},
          {project:":empty",path:("certifications/"+$repo_key+"/empty.tsv"),
            sha256:$hash,suite_count:0,plugin_sha256:""}]}]}' > "$target_receipt"
  release_receipt_schema_valid "$target_receipt" || {
    echo "fleet-canary self-test: realistic release receipt schema was rejected" >&2; return 1;
  }
  zero_project=""
  zero_plugin=unexpected
  while IFS=$'\t' read -r row_suites zero_project zero_plugin; do
    [ "$row_suites" = 0 ] && break
  done < <(jq -r '.repositories[].certifications[] |
      [.suite_count,.project,.plugin_sha256] | @tsv' "$target_receipt")
  if [ "$zero_project" != :empty ] || [ -n "$zero_plugin" ]; then
    echo "fleet-canary self-test: zero-suite certification fields shifted during parsing" >&2
    return 1
  fi
  jq --arg bad "$(printf '%064d' 1)" \
    '.repositories[0].certifications[0].plugin_sha256=$bad' "$target_receipt" > "$fixture"
  if release_receipt_schema_valid "$fixture"; then
    echo "fleet-canary self-test: stale consumer plugin identity was accepted" >&2
    return 1
  fi
  jq '.repositories[0].tasks=[]' "$target_receipt" > "$fixture"
  if release_receipt_schema_valid "$fixture"; then
    echo "fleet-canary self-test: structurally incomplete receipt was accepted" >&2
    return 1
  fi
  printf 'artifact\n' > "$artifact_file"
  target_hash=$(sha256_file "$target_receipt")
  artifact_hash=$(sha256_file "$artifact_file")
  jq -n --arg receipt_sha256 "$target_hash" \
    '{schema:2,kind:"fleet-canary-pointer",mode:"release",result:"passed",
      run_id:"run.A",plugin_sha:"0000000000000000000000000000000000000000",
      bundle:"fleet-canary-runs/run.A/receipt.json",receipt_sha256:$receipt_sha256}' \
    > "$pointer_file"
  saved_pwd=$PWD
  cd /
  if ! resolve_receipt "$pointer_file"; then
    cd "$saved_pwd"
    return 1
  fi
  cd "$saved_pwd"
  [ "$bundle_dir" = "$path_fixture/fleet-canary-runs/run.A" ] || {
    echo "fleet-canary self-test: pointer resolved bundle base to $bundle_dir" >&2; return 1;
  }
  resolved_path=$(bundle_artifact_path "logs/ok.log" "self-test artifact")
  [ "$resolved_path" = "$artifact_file" ] || {
    echo "fleet-canary self-test: contained artifact resolved to $resolved_path" >&2; return 1;
  }
  verify_artifact "logs/ok.log" "$artifact_hash" "self-test artifact" || return 1
  [ "$verified_artifact_path" = "$artifact_file" ] || return 1
  ln -s "$fixture" "$symlink_file"
  if bundle_artifact_path "logs/link.log" "self-test artifact" >/dev/null 2>&1; then
    echo "fleet-canary self-test: symlinked artifact was accepted" >&2
    return 1
  fi
  verification_inputs_unchanged || return 1
  printf 'changed\n' > "$artifact_file"
  if verification_inputs_unchanged >/dev/null 2>&1; then
    echo "fleet-canary self-test: artifact mutation after verification was accepted" >&2
    return 1
  fi
  printf 'artifact\n' > "$artifact_file"
  verification_inputs_unchanged || return 1
  jq -n '{schema:2,kind:"fleet-canary-pointer",mode:"release",result:"in_progress"}' \
    > "$pointer_file"
  if verification_inputs_unchanged >/dev/null 2>&1; then
    echo "fleet-canary self-test: superseded canonical pointer remained valid" >&2
    return 1
  fi
  [ "$(origin_slug git@github.com:sava-software/sava.git)" = "sava-software/sava" ] || return 1
  slugs=$(manifest_slugs)
  [ "$(printf '%s\n' "$slugs" | sort | uniq -d | wc -l | tr -d ' ')" = "0" ] || {
    echo "fleet-canary self-test: duplicate manifest slug" >&2; return 1;
  }
  basenames=$(printf '%s\n' "$slugs" | sed 's|.*/||')
  [ "$(printf '%s\n' "$basenames" | sort | uniq -d | wc -l | tr -d ' ')" = "0" ] || {
    echo "fleet-canary self-test: sibling checkout basename collision" >&2; return 1;
  }
  echo "fleet-canary: self-test passed"
}

release_mode=false
deep_run=false
allow_advisories=false
receipt_path="$canonical_receipt"
verify_path=""
self_test_requested=false
consumer_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --release) release_mode=true ;;
    --deep) deep_run=true ;;
    --allow-advisories) allow_advisories=true ;;
    --receipt)
      shift
      [ "$#" -gt 0 ] || { echo "fleet-canary: --receipt requires a path" >&2; exit 2; }
      receipt_path=$1
      ;;
    --verify-receipt)
      shift
      [ "$#" -gt 0 ] || { echo "fleet-canary: --verify-receipt requires a path" >&2; exit 2; }
      verify_path=$1
      ;;
    --self-test) self_test_requested=true ;;
    --help|-h) usage; exit 0 ;;
    --*) echo "fleet-canary: unknown option $1" >&2; usage >&2; exit 2 ;;
    *) consumer_args+=("$1") ;;
  esac
  shift
done

if $self_test_requested; then
  if $release_mode || $deep_run || $allow_advisories || [ -n "$verify_path" ] ||
      [ "$receipt_path" != "$canonical_receipt" ] || [ "${#consumer_args[@]}" -ne 0 ]; then
    echo "fleet-canary: --self-test combines with no other option" >&2
    exit 2
  fi
  self_test
  exit $?
fi
if [ -n "$verify_path" ]; then
  if $release_mode || $deep_run || $allow_advisories ||
      [ "$receipt_path" != "$canonical_receipt" ] || [ "${#consumer_args[@]}" -ne 0 ]; then
    echo "fleet-canary: --verify-receipt combines with no run options or repo arguments" >&2
    exit 2
  fi
  verify_receipt "$verify_path"
  exit $?
fi
if $release_mode; then
  require_jq
  if $deep_run; then
    echo "fleet-canary: --deep is an ordinary diagnostic and cannot combine with --release" >&2
    exit 2
  fi
  if [ "${#consumer_args[@]}" -ne 0 ]; then
    echo "fleet-canary: --release always certifies the complete manifest" >&2
    exit 2
  fi
elif $allow_advisories || [ "$receipt_path" != "$canonical_receipt" ]; then
  echo "fleet-canary: --allow-advisories and --receipt require --release" >&2
  exit 2
fi
if $deep_run && [ "${#consumer_args[@]}" -ne 0 ]; then
  echo "fleet-canary: --deep uses manifest annotations and cannot combine with explicit repos; append :<task> instead" >&2
  exit 2
fi

plan_file=$(mktemp)
records_file=$(mktemp)
out_file=$(mktemp)
repo_log_tmp=$(mktemp)
pre_cert_file=$(mktemp)
inner_records_file=$(mktemp)
execution_plan=$(mktemp)
trap 'rm -f "$plan_file" "$records_file" "$out_file" "$repo_log_tmp" "$pre_cert_file" "$inner_records_file" "$execution_plan"' EXIT

if [ "${#consumer_args[@]}" -eq 0 ]; then
  while read -r slug annot _rest; do
    case "$slug" in ''|\#*) continue ;; esac
    case "$annot" in \#*) annot='' ;; esac
    repo="$sava_build_dir/../${slug##*/}"
    deep_task=""
    if $deep_run; then case "$annot" in deep=*) deep_task=${annot#deep=} ;; esac; fi
    if [ ! -d "$repo" ] && ! $release_mode; then
      echo "fleet-canary: SKIP $slug — no sibling checkout at $repo" >&2
      continue
    fi
    printf '%s\t%s\t%s\n' "$slug" "$repo" "$deep_task" >> "$plan_file"
  done < "$manifest"
else
  for arg in "${consumer_args[@]}"; do
    repo=${arg%%:*}
    deep_task=""
    case "$arg" in *:*) deep_task=${arg#*:} ;; esac
    slug=$(basename "$repo")
    if git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      remote=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
      detected_slug=$(origin_slug "$remote")
      if [ -n "$detected_slug" ]; then slug=$detected_slug; fi
    fi
    printf '%s\t%s\t%s\n' "$slug" "$repo" "$deep_task" >> "$plan_file"
  done
fi
[ -s "$plan_file" ] || { echo "fleet-canary: nothing to run" >&2; exit 2; }

plugin_sha=$(git -C "$sava_build_dir" rev-parse HEAD)
plugin_tree=$(git -C "$sava_build_dir" rev-parse 'HEAD^{tree}')
plugin_origin=$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)
run_dir=""
run_id=""
bundle_rel=""
logs_dir=""
certifications_dir=""
plugin_publish_log="$out_file"
published_plugin_sha256=""

if $release_mode; then
  prepare_pointer_destination "$receipt_path"
  runs_parent="$receipt_parent/fleet-canary-runs"
  reject_symlink_components "$runs_parent" "fleet run directory"
  mkdir -p "$runs_parent"
  run_dir=$(mktemp -d "$runs_parent/run.XXXXXX")
  run_id=$(basename "$run_dir")
  bundle_rel="fleet-canary-runs/$run_id/receipt.json"
  write_pointer "in_progress"
  logs_dir="$run_dir/logs"
  certifications_dir="$run_dir/certifications"
  mkdir -p "$logs_dir" "$certifications_dir"
  plugin_publish_log="$run_dir/plugin-publish.log"

  preflight_failed=""
  : > "$run_dir/preflight.tsv"
  printf 'slug\tpath\tsha\torigin\tdirty\tresult\n' >> "$run_dir/preflight.tsv"
  plugin_dirty=$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)
  if [ ! -x "$sava_build_dir/gradlew" ]; then
    preflight_failed="$preflight_failed sava-build(gradlew_not_executable)"
  fi
  if [ "$(origin_slug "$plugin_origin")" != "$plugin_expected_slug" ]; then
    preflight_failed="$preflight_failed sava-build(remote_mismatch)"
  fi
  if [ -n "$plugin_dirty" ]; then
    printf '%s\n' "$plugin_dirty" > "$run_dir/plugin-dirty.txt"
    preflight_failed="$preflight_failed sava-build(dirty)"
  fi
  while IFS=$'\t' read -r slug repo deep_task; do
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
      >> "$run_dir/preflight.tsv"
    if [ "$result" = passed ]; then
      printf '%s\037%s\037%s\037%s\037%s\n' \
        "$slug" "$repo" "$deep_task" "$repo_sha" "$remote" >> "$execution_plan"
    else
      preflight_failed="$preflight_failed $slug($result)"
    fi
  done < "$plan_file"
  if [ -n "$preflight_failed" ]; then
    write_pointer "failed"
    echo "fleet-canary: release preflight FAILED:$preflight_failed" >&2
    echo "fleet-canary: evidence: $run_dir" >&2
    exit 1
  fi
else
  while IFS=$'\t' read -r slug repo deep_task; do
    printf '%s\037%s\037%s\n' "$slug" "$repo" "$deep_task" >> "$execution_plan"
  done < "$plan_file"
fi

echo "fleet-canary: publishing 0.0.0-test from $sava_build_dir at $plugin_sha"
if ! (cd "$sava_build_dir" && ./gradlew --console=plain \
    publishSavaBuildTestPublicationToSavaTestRepoRepository) > "$plugin_publish_log" 2>&1; then
  cat "$plugin_publish_log"
  if $release_mode; then write_pointer "failed"; fi
  echo "fleet-canary: local plugin publication failed" >&2
  exit 1
fi
if ! snapshot_published_plugin_jar; then
  if $release_mode; then write_pointer "failed"; fi
  echo "fleet-canary: could not retain the published plugin identity" >&2
  exit 1
fi
if $release_mode; then
  if [ "$(git -C "$sava_build_dir" rev-parse HEAD)" != "$plugin_sha" ] ||
      [ "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)" != "$plugin_origin" ] ||
      [ -n "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" ]; then
    write_pointer "failed"
    echo "fleet-canary: plugin checkout changed during local publication" >&2
    exit 1
  fi
fi

snapshot_certifications() {
  local repo=$1 destination=$2 file
  : > "$destination"
  while IFS= read -r file; do
    reject_symlink_components "$file" "inner PIT certification" || return 1
    printf '%s\t%s\n' "$file" "$(sha256_file "$file")" >> "$destination"
  done < <(find "$repo" -type f -path "*/build/hardening/$pitest_certification_receipt_name" \
    -print 2>/dev/null | sort)
}

collect_certifications() {
  local slug=$1 repo=$2 previous_file=$3 output_file=$4 safe_slug=$5 expected_projects=$6
  local source relative relative_key destination relative_dest previous_hash
  local source_before_hash source_after_hash copy_hash suites plugin_hash project running
  local total_suites=0 count=0
  : > "$output_file"
  running=$(find "$repo" -type f -path "*/build/hardening/$pitest_certification_sentinel_name" \
    -print 2>/dev/null | sort)
  if [ -n "$running" ]; then
    echo "fleet-canary: $slug retained in-progress PIT certification sentinel(s):" >&2
    printf '  %s\n' "$running" >&2
    return 1
  fi
  while IFS= read -r source; do
    reject_symlink_components "$source" "inner PIT certification" || return 1
    source_before_hash=$(sha256_file "$source") || return 1
    previous_hash=$(awk -F '\t' -v path="$source" '$1 == path { print $2; exit }' "$previous_file")
    if [ -n "$previous_hash" ] && [ "$previous_hash" = "$source_before_hash" ]; then
      echo "fleet-canary: hardeningCertify left stale evidence unchanged: $source" >&2
      return 1
    fi
    relative=${source#"$repo"/}
    case "$relative" in "$source"|../*|*/../*) echo "fleet-canary: certification escaped repo: $source" >&2; return 1 ;; esac
    relative_key=$(artifact_key "$relative")
    relative_dest="certifications/$safe_slug/$relative_key.tsv"
    destination="$run_dir/$relative_dest"
    reject_symlink_components "$destination" "retained PIT certification" || return 1
    if [ -e "$destination" ]; then
      echo "fleet-canary: duplicate certification evidence path for $slug: $source" >&2
      return 1
    fi
    mkdir -p "$(dirname "$destination")"
    cp "$source" "$destination"
    reject_symlink_components "$source" "inner PIT certification" || return 1
    source_after_hash=$(sha256_file "$source") || return 1
    copy_hash=$(sha256_file "$destination")
    if [ "$source_before_hash" != "$source_after_hash" ] ||
        [ "$copy_hash" != "$source_after_hash" ]; then
      echo "fleet-canary: certification changed while being retained: $source" >&2
      return 1
    fi
    suites=$(validate_inner_certification "$destination") || {
      echo "fleet-canary: invalid retained inner PIT certification: $destination" >&2; return 1;
    }
    plugin_hash=$(inner_certification_plugin_sha "$destination") || {
      echo "fleet-canary: inconsistent retained plugin provenance: $destination" >&2; return 1;
    }
    if [ "$suites" -gt 0 ] && [ "$plugin_hash" != "$published_plugin_sha256" ]; then
      echo "fleet-canary: $slug certified with plugin $plugin_hash, not published jar $published_plugin_sha256" >&2
      return 1
    fi
    project=$(inner_certification_project "$destination")
    verify_artifact_unchanged "$destination" "$copy_hash" \
      "retained inner PIT certification" || return 1
    jq -cn --arg project "$project" --arg path "$relative_dest" --arg sha256 "$copy_hash" \
      --arg plugin_sha256 "$plugin_hash" --argjson suite_count "$suites" \
      '{project:$project,path:$path,sha256:$sha256,suite_count:$suite_count,
        plugin_sha256:$plugin_sha256}' >> "$output_file"
    total_suites=$((total_suites + suites))
    count=$((count + 1))
  done < <(find "$repo" -type f -path "*/build/hardening/$pitest_certification_receipt_name" \
    -print 2>/dev/null | sort)
  if [ "$count" -eq 0 ] || [ "$total_suites" -eq 0 ]; then
    echo "fleet-canary: $slug produced no mutation-suite certification evidence" >&2
    return 1
  fi
  if ! certification_evidence_matches "$expected_projects" "$output_file"; then
    echo "fleet-canary: $slug certification receipts do not exactly cover discovered hardeningCertify projects" >&2
    return 1
  fi
}

record_repo() {
  local slug=$1 repo=$2 sha=$3 origin=$4 dirty_before=$5 dirty_after=$6
  local tasks=$7 certification_projects=$8 result=$9 output_hash=${10}
  local findings=${11} log_file=${12} certifications_file=${13}
  jq -cn \
    --arg slug "$slug" --arg path "$repo" --arg sha "$sha" --arg origin "$origin" \
    --argjson dirty_before "$dirty_before" --argjson dirty_after "$dirty_after" \
    --arg tasks "$tasks" --arg certification_projects "$certification_projects" \
    --arg result "$result" --arg output_sha256 "$output_hash" \
    --arg findings "$findings" --arg log_file "$log_file" \
    --slurpfile certifications "$certifications_file" \
    '{slug:$slug,path:$path,sha:$sha,origin:$origin,
      dirty_before:$dirty_before,dirty_after:$dirty_after,
      tasks:($tasks|split("\n")|map(select(length>0))),
      certification_projects:($certification_projects|split("\n")|map(select(length>0))),
      result:$result,
      output_sha256:$output_sha256,findings:$findings,log_file:$log_file,
      certifications:$certifications}' >> "$records_file"
}

failed=""
warned=""
consumer_cache_args=()
if $release_mode; then consumer_cache_args+=(--configuration-cache); fi
expected_execution_rows=$(wc -l < "$plan_file" | tr -d ' ')
processed_execution_rows=0
while IFS=$'\037' read -r -u 3 slug repo deep_task pre_sha pre_origin; do
  processed_execution_rows=$((processed_execution_rows + 1))
  repo_result=passed
  dirty_before=false
  dirty_after=false
  tasks=""
  recorded_tasks=""
  certification_projects=""
  repo_sha=${pre_sha:-}
  remote=${pre_origin:-}
  if $release_mode; then
    safe_slug=$(artifact_key "$slug")
    log_file="logs/$safe_slug.log"
    repo_log="$run_dir/$log_file"
    reject_symlink_components "$repo_log" "consumer log"
    : > "$repo_log"
    snapshot_certifications "$repo" "$pre_cert_file"
  else
    log_file=""
    repo_log="$repo_log_tmp"
    : > "$repo_log"
    if [ ! -d "$repo" ]; then repo_result=missing
    elif [ ! -f "$repo/gradlew" ]; then repo_result=no_gradlew
    elif ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then repo_result=not_git
    else
      repo_sha=$(git -C "$repo" rev-parse HEAD)
      remote=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
      if [ -n "$(git -C "$repo" status --porcelain --untracked-files=all)" ]; then dirty_before=true; fi
    fi
  fi

  if [ "$repo_result" = passed ]; then
    if $release_mode; then
      if ! (cd "$repo" && ./gradlew --console=plain "${consumer_cache_args[@]}" \
          -PsavaBuildLocalRepo="$local_repo" tasks --all) \
          3<&- > "$out_file" 2>&1; then
        repo_result=task_discovery_failed
      else
        if ! grep -qF "$resolution_notice" "$out_file"; then
          repo_result=resolution_missing
        else
          certification_projects=$(registered_certification_projects "$out_file")
        fi
        if [ "$repo_result" = passed ] && [ -n "$certification_projects" ]; then
          tasks="hardeningCertify"
        elif [ "$repo_result" = passed ]; then
          repo_result=certification_task_missing
        fi
      fi
      cat "$out_file" >> "$repo_log"
    else
      tasks=$(find "$repo" -type d -name build -prune -o \
          \( -name '*-accepted.csv' -o -name '*-timeouts.csv' \) -path '*/config/pitest/*' -print \
        | sed -e 's|.*/||' -e 's|-accepted\.csv$||' -e 's|-timeouts\.csv$||' \
        | sort -u | awk '{ print "pitest" toupper(substr($0, 1, 1)) substr($0, 2) "Debt" }')
      if [ -z "$tasks" ]; then tasks=help; fi
    fi
  fi

  if [ "$repo_result" = passed ]; then
    tasks="$tasks"$'\n'"agentsTemplateInSync"
    recorded_tasks=$tasks
    echo "fleet-canary: $slug@$repo_sha — $(echo "$tasks" | tr '\n' ' ')"
    task_args=()
    while IFS= read -r task; do [ -n "$task" ] && task_args+=("$task"); done <<< "$tasks"
    if ! (cd "$repo" && ./gradlew --console=plain "${consumer_cache_args[@]}" \
        -PsavaBuildLocalRepo="$local_repo" \
        "${task_args[@]}") 3<&- > "$out_file" 2>&1; then
      repo_result=checks_failed
    elif ! grep -qF "$resolution_notice" "$out_file"; then
      repo_result=resolution_missing
    fi
    cat "$out_file" >> "$repo_log"
  fi

  if [ "$repo_result" = passed ] && [ -n "$deep_task" ]; then
    for round in 1 2; do
      recorded_tasks="$recorded_tasks"$'\n'"$deep_task (--rerun-tasks round $round)"
      if ! (cd "$repo" && ./gradlew --console=plain -PsavaBuildLocalRepo="$local_repo" \
          --rerun-tasks "$deep_task") 3<&- > "$out_file" 2>&1; then
        repo_result="deep_round_${round}_failed"
        cat "$out_file" >> "$repo_log"
        break
      elif ! grep -qF "$resolution_notice" "$out_file"; then
        repo_result="deep_round_${round}_resolution_missing"
        cat "$out_file" >> "$repo_log"
        break
      fi
      cat "$out_file" >> "$repo_log"
    done
  fi

  if git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    current_sha=$(git -C "$repo" rev-parse HEAD)
    current_origin=$(git -C "$repo" remote get-url origin 2>/dev/null || true)
    if [ -n "$(git -C "$repo" status --porcelain --untracked-files=all)" ]; then dirty_after=true; fi
    if $release_mode && [ "$repo_result" = passed ]; then
      if [ "$current_sha" != "$repo_sha" ]; then repo_result=head_changed
      elif [ "$current_origin" != "$remote" ] || [ "$(origin_slug "$current_origin")" != "$slug" ]; then
        repo_result=remote_changed
      elif $dirty_after; then repo_result=dirty_after
      fi
    fi
  elif $release_mode && [ "$repo_result" = passed ]; then
    repo_result=not_git_after
  fi

  if $release_mode && [ "$repo_result" = passed ]; then
    if ! collect_certifications "$slug" "$repo" "$pre_cert_file" "$inner_records_file" \
        "$safe_slug" "$certification_projects"; then
      repo_result=certification_evidence_invalid
    fi
  else
    : > "$inner_records_file"
  fi
  findings=$(reprint_findings "$advisory_pattern|$deep_pattern" "$repo_log" || true)
  if [ -n "$findings" ]; then
    warned="$warned $slug"
    echo "$findings"
    if $release_mode && ! $allow_advisories && [ "$repo_result" = passed ]; then
      repo_result=advisories_unacknowledged
    fi
  fi
  if $release_mode; then
    output_hash=$(sha256_file "$repo_log")
    record_repo "$slug" "$repo" "$repo_sha" "$remote" "$dirty_before" "$dirty_after" \
      "$recorded_tasks" "$certification_projects" "$repo_result" "$output_hash" "$findings" \
      "$log_file" "$inner_records_file"
  fi
  if [ "$repo_result" != passed ]; then
    failed="$failed $slug($repo_result)"
    echo "fleet-canary: FAILED $slug — $repo_result; log: $repo_log" >&2
    if ! $release_mode; then cat "$repo_log" >&2; fi
  fi
done 3< "$execution_plan"
if [ "$processed_execution_rows" -ne "$expected_execution_rows" ]; then
  failed="$failed execution-plan(truncated:$processed_execution_rows/$expected_execution_rows)"
  echo "fleet-canary: execution plan truncated after $processed_execution_rows of $expected_execution_rows rows" >&2
fi

if $release_mode; then
  plugin_dirty_after=false
  if [ "$(git -C "$sava_build_dir" rev-parse HEAD)" != "$plugin_sha" ] ||
      [ "$(git -C "$sava_build_dir" remote get-url origin 2>/dev/null || true)" != "$plugin_origin" ] ||
      [ -n "$(git -C "$sava_build_dir" status --porcelain --untracked-files=all)" ]; then
    plugin_dirty_after=true
    failed="$failed sava-build(changed_during_run)"
  fi
  receipt_result=passed
  [ -z "$failed" ] || receipt_result=failed
  receipt_tmp=$(mktemp "$run_dir/.receipt.XXXXXX")
  generated_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  jq -n \
    --arg generated_at "$generated_at" --arg run_id "$run_id" --arg result "$receipt_result" \
    --arg plugin_sha "$plugin_sha" --arg plugin_tree "$plugin_tree" \
    --arg plugin_origin "$plugin_origin" \
    --arg test_jar_file "$retained_plugin_jar_file" \
    --arg test_jar_sha256 "$published_plugin_sha256" \
    --argjson plugin_dirty_after "$plugin_dirty_after" \
    --arg manifest_sha256 "$(sha256_file "$manifest")" \
    --arg preflight_file "preflight.tsv" \
    --arg preflight_sha256 "$(sha256_file "$run_dir/preflight.tsv")" \
    --arg publish_log_file "plugin-publish.log" \
    --arg publish_output_sha256 "$(sha256_file "$plugin_publish_log")" \
    --argjson advisories_acknowledged "$allow_advisories" \
    --slurpfile repositories "$records_file" \
    '{schema:3,kind:"fleet-canary-receipt",mode:"release",run_id:$run_id,
      generated_at:$generated_at,result:$result,
      plugin:{sha:$plugin_sha,tree:$plugin_tree,origin:$plugin_origin,
        dirty_before:false,dirty_after:$plugin_dirty_after,
        test_jar_file:$test_jar_file,test_jar_sha256:$test_jar_sha256},
      manifest_sha256:$manifest_sha256,
      preflight_file:$preflight_file,preflight_sha256:$preflight_sha256,
      publish_log_file:$publish_log_file,
      publish_output_sha256:$publish_output_sha256,
      advisories_acknowledged:$advisories_acknowledged,repositories:$repositories}' > "$receipt_tmp"
  mv "$receipt_tmp" "$run_dir/receipt.json"
  receipt_hash=$(sha256_file "$run_dir/receipt.json")
  write_pointer "$receipt_result" "$receipt_hash"
  echo "fleet-canary: evidence bundle: $run_dir"
  echo "fleet-canary: canonical pointer: $receipt_path"
fi

if [ -n "$failed" ]; then
  echo "fleet-canary: FAILED:$failed" >&2
  exit 1
fi
if [ -n "$warned" ]; then
  if $release_mode; then
    echo "fleet-canary: release-certified with reviewed advisory findings in:$warned"
  else
    echo "fleet-canary: green, with advisory findings in:$warned"
  fi
else
  echo "fleet-canary: green — no failures, no advisory findings"
fi
