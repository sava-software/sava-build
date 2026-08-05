#!/usr/bin/env bash
# Bridges reviewed local adoption evidence into the Git history that creates and
# publishes a release. The committed file is an owner attestation: for new records it
# derives each reviewed consumer's clean commit and completed certification receipts,
# then binds them to the exact candidate commit, tree, and plugin JAR.
set -euo pipefail
export LC_ALL=C

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd -P)
release_manifest="$sava_build_dir/.release-please-manifest.json"
attestations_dir="$sava_build_dir/release-attestations"
expected_origin_slug="sava-software/sava-build"
release_attestation_self_test_cleanup_path=""

usage() {
  cat <<'EOF'
Usage:
  tools/release-attestation.sh create-reviewed <version> --candidate <commit> --plugin-jar <path> --adoption <consumer-checkout> [--adoption <consumer-checkout> ...]
  tools/release-attestation.sh verify <version>
  tools/release-attestation.sh verify-tag <version>
  tools/release-attestation.sh verify-built-jar <version> <jar>
  tools/release-attestation.sh verify-pending-release
  tools/release-attestation.sh --self-test

`create-reviewed` reads completed hardening-certification receipts from each clean
consumer checkout and requires every suite to have loaded the exact candidate JAR.
Each `--adoption` value must be the canonical, symlink-free absolute path to the
consumer Git worktree root. Literal `.` or `..`, repeated separators, and empty or
trailing path components are refused.
Commit the generated file to the Release Please PR. Tag creation and publication then
validate the candidate identity and refuse a rebuilt JAR whose bytes differ from the
reviewed candidate.
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

require_no_symlink_components() {
  local requested=$1 label=$2 probe="" component
  local components=()
  case "$requested" in
    /*) ;;
    *)
      echo "release-attestation: internal error: $label path is not absolute: $requested" >&2
      return 1
      ;;
  esac
  IFS='/' read -r -a components <<< "${requested#/}"
  for component in "${components[@]}"; do
    [ -n "$component" ] || continue
    probe="$probe/$component"
    if [ -L "$probe" ]; then
      echo "release-attestation: $label path contains a symlink component: $probe" >&2
      return 1
    fi
  done
}

absolute_regular_directory() {
  local requested=$1 label=$2 absolute
  case "$requested" in
    /*) absolute=$requested ;;
    *)
      echo "release-attestation: $label must be an absolute path: $requested" >&2
      return 1
      ;;
  esac
  case "$absolute/" in
    */../*|*/./*|*//*)
      echo "release-attestation: $label path contains traversal or an empty component: $requested" >&2
      return 1
      ;;
  esac
  require_no_symlink_components "$absolute" "$label" || return 1
  if [ ! -d "$absolute" ]; then
    echo "release-attestation: missing $label directory: $absolute" >&2
    return 1
  fi
  (cd "$absolute" && pwd -P)
}

normalized_github_origin() {
  local value=$1 slug
  case "$value" in
    git@github.com:*) slug=${value#git@github.com:} ;;
    https://github.com/*) slug=${value#https://github.com/} ;;
    ssh://git@github.com/*) slug=${value#ssh://git@github.com/} ;;
    *) return 1 ;;
  esac
  slug=${slug%.git}
  if ! [[ "$slug" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]]; then
    return 1
  fi
  slug=$(printf '%s' "$slug" | tr '[:upper:]' '[:lower:]') || return 1
  printf '%s\t%s\n' "$slug" "https://github.com/$slug.git"
}

certification_receipt_summary() {
  local receipt=$1 relative=$2 expected_plugin_hash=$3 expected_commit=$4 expected_tree=$5
  local before after metadata project session plugin_hash suite_count suites
  local git_state git_commit git_tree git_status_sha256 git_project_directory expected_path
  if [ ! -f "$receipt" ] || [ -L "$receipt" ]; then
    echo "release-attestation: missing or symlinked certification receipt: $receipt" >&2
    return 1
  fi
  require_no_symlink_components "$receipt" "certification receipt" || return 1
  if [ -e "$(dirname "$receipt")/pitest-certification.running" ] ||
      [ -L "$(dirname "$receipt")/pitest-certification.running" ]; then
    echo "release-attestation: certification is incomplete or still running beside $receipt" >&2
    return 1
  fi
  before=$(sha256_file "$receipt") || return 1
  metadata=$(awk -F '\t' '
    $1 == "schema" {
      schemaRows++; if (NR != 1 || NF != 2 || $2 != "6") invalid=1; next
    }
    $1 == "project" {
      projectRows++; if (NR != 2 || NF != 2 || $2 == "") invalid=1; project=$2; next
    }
    $1 == "session" {
      sessionRows++; if (NR != 3 || NF != 2 || $2 == "") invalid=1; session=$2; next
    }
    $1 == "mode" {
      modeRows++; if (NR != 4 || NF != 2 || $2 != "fresh-full-strict") invalid=1; next
    }
    $1 == "gitState" {
      gitStateRows++; if (NR != 5 || NF != 2 ||
        ($2 != "clean" && $2 != "dirty" && $2 != "unavailable")) invalid=1
      gitState=$2; next
    }
    $1 == "gitCommit" {
      gitCommitRows++; if (NR != 6 || NF != 2 || $2 == "") invalid=1
      gitCommit=$2; next
    }
    $1 == "gitTree" {
      gitTreeRows++; if (NR != 7 || NF != 2 || $2 == "") invalid=1
      gitTree=$2; next
    }
    $1 == "gitStatusSha256" {
      gitStatusRows++; if (NR != 8 || NF != 2 || $2 == "") invalid=1
      gitStatus=$2; next
    }
    $1 == "gitProjectDirectory" {
      gitProjectRows++; if (NR != 9 || NF != 2 || $2 == "") invalid=1
      gitProject=$2; next
    }
    $1 == "pluginSha256" {
      pluginRows++; if (NR != 10 || NF != 2 || length($2) != 64 || $2 ~ /[^0-9a-f]/) invalid=1
      headerPlugin=$2; next
    }
    $1 == "columns" {
      columnsRows++
      if (NR != 11 || NF != 15 || $2 != "suite" || $3 != "name" || $4 != "invocation" ||
          $5 != "reportSha256" || $6 != "sourceSha256" || $7 != "classesSha256" ||
          $8 != "configurationSha256" || $9 != "pitestVersion" ||
          $10 != "pluginSha256" || $11 != "toolClasspathSha256" ||
          $12 != "mutationToolchainSha256" || $13 != "recordInputsSha256" ||
          $14 != "recordPitestVersion" ||
          $15 != "recordMutationToolchainSha256") invalid=1
      next
    }
    $1 == "suite" {
      suites++
      if (NR <= 11 || NF != 14 || $2 == "" || $3 == "" || $8 == "" || $13 == "" ||
          $14 == "" || seen[$2]++) invalid=1
      for (i=4; i<=12; i++) {
        if (i == 8) continue
        if (length($i) != 64 || $i ~ /[^0-9a-f]/) invalid=1
      }
      if (!(($13 == "no-record" && $14 == "no-record") ||
            ($13 == "legacy-unversioned" && $14 == "legacy-toolchain-unbound") ||
            ($13 == $8 && length($14) == 64 && $14 !~ /[^0-9a-f]/ &&
             ("x" $14) == ("x" $11)))) invalid=1
      if ($9 != headerPlugin) invalid=1
      if (suites == 1) {
        projectSource=$5; projectClasses=$6; projectPitest=$8
        projectToolClasspath=$10; projectMutationToolchain=$11
      } else if ($5 != projectSource || $6 != projectClasses || $8 != projectPitest ||
          $10 != projectToolClasspath || $11 != projectMutationToolchain) invalid=1
      next
    }
    { invalid=1 }
    END {
      if (invalid || schemaRows != 1 || projectRows != 1 || sessionRows != 1 ||
          modeRows != 1 || gitStateRows != 1 || gitCommitRows != 1 || gitTreeRows != 1 ||
          gitStatusRows != 1 || gitProjectRows != 1 || pluginRows != 1 || columnsRows != 1) exit 1
      if (gitState == "unavailable") {
        if (gitCommit != "unavailable" || gitTree != "unavailable" ||
            gitStatus != "unavailable" || gitProject != "unavailable") exit 1
      } else {
        if (length(gitCommit) != 40 || gitCommit ~ /[^0-9a-f]/ ||
            length(gitTree) != 40 || gitTree ~ /[^0-9a-f]/ ||
            length(gitStatus) != 64 || gitStatus ~ /[^0-9a-f]/) exit 1
        if (gitProject == "" || gitProject ~ /[\t\r\n]/) exit 1
        if (gitState == "clean" &&
            gitStatus != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") exit 1
        if (gitState == "dirty" &&
            gitStatus == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") exit 1
      }
      printf "%s\n%s\n%s\n%d\n%s\n%s\n%s\n%s\n%s\n", project, session,
        headerPlugin, suites, gitState, gitCommit, gitTree, gitStatus, gitProject
    }
  ' "$receipt") || {
    echo "release-attestation: malformed or mixed certification receipt: $receipt" >&2
    return 1
  }
  project=$(sed -n '1p' <<< "$metadata")
  session=$(sed -n '2p' <<< "$metadata")
  plugin_hash=$(sed -n '3p' <<< "$metadata")
  suite_count=$(sed -n '4p' <<< "$metadata")
  git_state=$(sed -n '5p' <<< "$metadata")
  git_commit=$(sed -n '6p' <<< "$metadata")
  git_tree=$(sed -n '7p' <<< "$metadata")
  git_status_sha256=$(sed -n '8p' <<< "$metadata")
  git_project_directory=$(sed -n '9p' <<< "$metadata")
  if [ "$git_state" != clean ]; then
    echo "release-attestation: certification receipt does not bind a clean Git checkout: $receipt" >&2
    echo "release-attestation: recorded Git state is $git_state" >&2
    return 1
  fi
  if [ "$git_commit" != "$expected_commit" ] || [ "$git_tree" != "$expected_tree" ]; then
    echo "release-attestation: stale certification receipt belongs to a different consumer revision: $receipt" >&2
    echo "release-attestation: expected $expected_commit/$expected_tree, found $git_commit/$git_tree" >&2
    return 1
  fi
  if [ "$plugin_hash" != "$expected_plugin_hash" ]; then
    echo "release-attestation: stale certification receipt loaded a different plugin JAR: $receipt" >&2
    echo "release-attestation: expected $expected_plugin_hash, found $plugin_hash" >&2
    return 1
  fi
  case "$git_project_directory" in
    .) expected_path="build/hardening/pitest-certification.tsv" ;;
    /*|''|*//*|../*|*/../*|./*|*/./*) expected_path="" ;;
    *) expected_path="$git_project_directory/build/hardening/pitest-certification.tsv" ;;
  esac
  if [ -z "$expected_path" ] || [ "$relative" != "$expected_path" ]; then
    echo "release-attestation: certification receipt path does not match its Git project directory: $receipt" >&2
    echo "release-attestation: recorded '$git_project_directory', receipt '$relative'" >&2
    return 1
  fi
  suites=$(awk -F '\t' '$1 == "suite" { print $2 }' "$receipt" |
    jq -Rsc 'split("\n") | map(select(length > 0)) | sort') || return 1
  after=$(sha256_file "$receipt") || return 1
  if [ "$before" != "$after" ]; then
    echo "release-attestation: certification receipt changed while being read: $receipt" >&2
    return 1
  fi
  jq -cn --arg path "$relative" --arg sha256 "$after" --arg project "$project" \
    --arg session "$session" --arg git_project_directory "$git_project_directory" \
    --argjson suites "$suites" --argjson suite_count "$suite_count" \
    '{path:$path,receipt_sha256:$sha256,schema:6,project:$project,session:$session,
      git_project_directory:$git_project_directory,
      suites:$suites,suite_count:$suite_count}'
}

reviewed_adoption_summary() {
  local requested=$1 expected_plugin_hash=$2 checkout git_root status remote origin_pair slug origin
  local commit tree inventory linked build_dir receipt receipt_root relative summary summary_suite_count
  local certifications='[]'
  local receipt_count=0 positive_suite_count=0
  checkout=$(absolute_regular_directory "$requested" "consumer checkout") || return 1
  if ! git_root=$(git -C "$checkout" rev-parse --show-toplevel 2>/dev/null); then
    echo "release-attestation: consumer checkout is not a Git worktree: $checkout" >&2
    return 1
  fi
  git_root=$(cd "$git_root" && pwd -P) || return 1
  if [ "$git_root" != "$checkout" ]; then
    echo "release-attestation: adoption path is not the consumer worktree root: $checkout" >&2
    return 1
  fi
  if ! status=$(git -C "$checkout" status --porcelain --untracked-files=all \
      --ignore-submodules=none); then
    echo "release-attestation: could not inspect consumer checkout status: $checkout" >&2
    return 1
  fi
  if [ -n "$status" ]; then
    echo "release-attestation: consumer checkout must be clean before reading certification: $checkout" >&2
    printf '%s\n' "$status" >&2
    return 1
  fi
  remote=$(git -C "$checkout" remote get-url origin 2>/dev/null || true)
  if ! origin_pair=$(normalized_github_origin "$remote"); then
    echo "release-attestation: consumer origin is not a supported GitHub remote: '$remote'" >&2
    return 1
  fi
  slug=${origin_pair%%$'\t'*}
  origin=${origin_pair#*$'\t'}
  commit=$(git -C "$checkout" rev-parse HEAD) || return 1
  tree=$(git -C "$checkout" rev-parse 'HEAD^{tree}') || return 1
  if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || ! [[ "$tree" =~ ^[0-9a-f]{40}$ ]]; then
    echo "release-attestation: consumer checkout did not resolve full commit/tree identities: $checkout" >&2
    return 1
  fi

  linked=$(find -P "$checkout" -type d -name .git -prune -o \
    -type d -name build -prune -o -type l -name build -print -quit) || return 1
  if [ -n "$linked" ]; then
    echo "release-attestation: certification evidence path contains a symlink: $linked" >&2
    return 1
  fi
  inventory=$(mktemp "${TMPDIR:-/tmp}/release-attestation-certifications.XXXXXX") || {
    echo "release-attestation: could not allocate certification inventory" >&2
    return 1
  }
  if ! find -P "$checkout" -type d -name .git -prune -o \
      -type d -name build -print0 -prune > "$inventory"; then
    rm -f "$inventory"
    echo "release-attestation: could not inventory certification receipts under $checkout" >&2
    return 1
  fi
  while IFS= read -r -d '' build_dir; do
    receipt="$build_dir/hardening/pitest-certification.tsv"
    if [ -L "$build_dir/hardening" ] || [ -L "$receipt" ]; then
      rm -f "$inventory"
      echo "release-attestation: certification evidence path contains a symlink: $receipt" >&2
      return 1
    fi
    [ -f "$receipt" ] || continue
    receipt_count=$((receipt_count + 1))
    receipt_root=$(git -C "$(dirname "$receipt")" rev-parse --show-toplevel 2>/dev/null || true)
    if [ -z "$receipt_root" ]; then
      rm -f "$inventory"
      echo "release-attestation: certification receipt is outside a Git worktree: $receipt" >&2
      return 1
    fi
    receipt_root=$(cd "$receipt_root" && pwd -P) || { rm -f "$inventory"; return 1; }
    if [ "$receipt_root" != "$checkout" ]; then
      rm -f "$inventory"
      echo "release-attestation: certification receipt belongs to a nested or different worktree: $receipt" >&2
      return 1
    fi
    case "$receipt" in
      "$checkout"/*) relative=${receipt#"$checkout"/} ;;
      *)
        rm -f "$inventory"
        echo "release-attestation: certification receipt escapes its consumer checkout: $receipt" >&2
        return 1
        ;;
    esac
    summary=$(certification_receipt_summary \
      "$receipt" "$relative" "$expected_plugin_hash" "$commit" "$tree") || {
      rm -f "$inventory"
      return 1
    }
    summary_suite_count=$(jq -er '.suite_count' <<< "$summary") || {
      rm -f "$inventory"
      return 1
    }
    positive_suite_count=$((positive_suite_count + summary_suite_count))
    certifications=$(jq -cn --argjson existing "$certifications" --argjson item "$summary" \
      '$existing + [$item]') || { rm -f "$inventory"; return 1; }
  done < "$inventory"
  rm -f "$inventory"
  if [ "$receipt_count" -eq 0 ]; then
    echo "release-attestation: no completed hardening certification receipt found under $checkout" >&2
    return 1
  fi
  certifications=$(jq -cS 'sort_by(.path)' <<< "$certifications") || return 1
  if [ "$positive_suite_count" -eq 0 ]; then
    echo "release-attestation: certification receipts under $checkout contain zero certified suites" >&2
    return 1
  fi
  if ! status=$(git -C "$checkout" status --porcelain --untracked-files=all \
      --ignore-submodules=none); then
    echo "release-attestation: could not re-inspect consumer checkout status: $checkout" >&2
    return 1
  fi
  if [ -n "$status" ] || [ "$commit" != "$(git -C "$checkout" rev-parse HEAD)" ] ||
      [ "$tree" != "$(git -C "$checkout" rev-parse 'HEAD^{tree}')" ]; then
    echo "release-attestation: consumer checkout changed while certification was read: $checkout" >&2
    [ -z "$status" ] || printf '%s\n' "$status" >&2
    return 1
  fi
  jq -cn --arg slug "$slug" --arg commit "$commit" --arg tree "$tree" \
    --arg origin "$origin" --arg plugin_hash "$expected_plugin_hash" \
    --argjson certifications "$certifications" \
    '{slug:$slug,commit:$commit,tree:$tree,origin:$origin,
      plugin_jar_sha256:$plugin_hash,certifications:$certifications}'
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
  local output tmp candidate_tree candidate_origin jar before after reviewed='[]' reviewed_after='[]'
  local adoption summary duplicates
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
    echo "release-attestation: create-reviewed requires at least one --adoption consumer-checkout" >&2
    return 1
  fi

  jar=$(absolute_file "$requested_jar") || return 1
  if [ ! -f "$jar" ] || [ -L "$jar" ]; then
    echo "release-attestation: missing or symlinked reviewed plugin JAR: $jar" >&2
    return 1
  fi
  require_no_symlink_components "$jar" "reviewed plugin JAR" || return 1
  before=$(sha256_file "$jar") || return 1
  for adoption in "$@"; do
    summary=$(reviewed_adoption_summary "$adoption" "$before") || return 1
    reviewed=$(jq -cn --argjson existing "$reviewed" --argjson item "$summary" \
      '$existing + [$item]') || return 1
  done
  reviewed=$(jq -cS 'sort_by(.slug)' <<< "$reviewed") || return 1
  duplicates=$(jq -r 'group_by(.slug) | map(select(length > 1) | .[0].slug) | .[]' \
    <<< "$reviewed") || return 1
  if [ -n "$duplicates" ]; then
    echo "release-attestation: duplicate derived adoption slug(s):" >&2
    printf '%s\n' "$duplicates" >&2
    return 1
  fi

  # Re-derive all consumer evidence after the first complete pass. This binds the
  # recorded clean HEADs and receipt hashes to one observation window instead of
  # allowing an adoption checkout or ignored build receipt to change mid-command.
  for adoption in "$@"; do
    summary=$(reviewed_adoption_summary "$adoption" "$before") || return 1
    reviewed_after=$(jq -cn --argjson existing "$reviewed_after" --argjson item "$summary" \
      '$existing + [$item]') || return 1
  done
  reviewed_after=$(jq -cS 'sort_by(.slug)' <<< "$reviewed_after") || return 1
  if [ "$reviewed" != "$reviewed_after" ]; then
    echo "release-attestation: reviewed consumer evidence changed during attestation" >&2
    return 1
  fi
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
    '{schema:3,kind:"sava-build-release-attestation",version:$version,
      candidate:{commit:$commit,tree:$tree,origin:$origin,plugin_jar_sha256:$plugin_hash},
      review:{kind:"derived-local-hardening-certifications",repositories:$repositories}}' > "$tmp"
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

attestation_schema_valid() {
  local file=$1 version=$2 schema
  schema=$(jq -r '.schema // empty' "$file") || return 1
  case "$schema" in
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
    3)
      jq -e --arg version "$version" --arg origin "$expected_origin_slug" '
        .candidate.plugin_jar_sha256 as $plugin |
        keys == ["candidate","kind","review","schema","version"] and
        .schema == 3 and .kind == "sava-build-release-attestation" and .version == $version and
        (.candidate | keys == ["commit","origin","plugin_jar_sha256","tree"]) and
        (.candidate.commit | test("^[0-9a-f]{40}$")) and
        (.candidate.tree | test("^[0-9a-f]{40}$")) and .candidate.origin == $origin and
        (.candidate.plugin_jar_sha256 | test("^[0-9a-f]{64}$")) and
        (.review | keys == ["kind","repositories"]) and
        .review.kind == "derived-local-hardening-certifications" and
        (.review.repositories | type == "array" and length > 0) and
        all(.review.repositories[];
          (. | keys == ["certifications","commit","origin","plugin_jar_sha256","slug","tree"]) and
          (.slug | test("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) and
          .slug == (.slug | ascii_downcase) and
          .origin == ("https://github.com/" + .slug + ".git") and
          (.commit | test("^[0-9a-f]{40}$")) and
          (.tree | test("^[0-9a-f]{40}$")) and
          .plugin_jar_sha256 == $plugin and
          (.certifications | type == "array" and length > 0) and
          (.certifications == (.certifications | sort_by(.path))) and
          (([.certifications[].path] | unique | length) == (.certifications | length)) and
          (([.certifications[].suite_count] | add) > 0) and
          all(.certifications[];
            (. | keys == ["git_project_directory","path","project","receipt_sha256","schema","session","suite_count","suites"]) and
            .schema == 6 and
            (.path | type == "string" and
              test("^(?:[^/]+/)*build/hardening/pitest-certification[.]tsv$") and
              (startswith("/") | not) and
              (contains("/../") | not) and
              (contains("/./") | not) and
              (split("/") | all(.[]; length > 0 and . != "." and . != ".."))) and
            (.git_project_directory | type == "string" and
              (. == "." or
                (test("^(?:[^/]+/)*[^/]+$") and
                  (split("/") | all(.[]; length > 0 and . != "." and . != ".."))))) and
            .path == (if .git_project_directory == "." then
                "build/hardening/pitest-certification.tsv"
              else .git_project_directory + "/build/hardening/pitest-certification.tsv" end) and
            (.receipt_sha256 | test("^[0-9a-f]{64}$")) and
            (.project | type == "string" and length > 0) and
            (.session | type == "string" and length > 0) and
            (.suite_count | type == "number" and . >= 0 and floor == .) and
            (.suites | type == "array") and
            ((.suites | length) == .suite_count) and
            (.suites == (.suites | sort)) and
            ((.suites | unique | length) == (.suites | length)) and
            all(.suites[]; type == "string" and length > 0)))
        and
        (([.review.repositories[].slug] | unique | length) == (.review.repositories | length)) and
        (.review.repositories == (.review.repositories | sort_by(.slug)))
      ' "$file" >/dev/null
      ;;
    *) return 1 ;;
  esac
}

verify_attestation() {
  local version=$1 file relative mode commit tree reviewed_count
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
  reviewed_count=$(jq -r '.review.repositories | length' "$file")
  echo "release-attestation: version $version binds $reviewed_count reviewed local adoption pass(es) to candidate $commit"
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
  local fixture script jar_hash failure_log
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
  jar_hash=$(printf 'plugin jar' | sha256_stream)
  failure_log="$fixture/release-attestation-failure.log"
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

  # The release path derives the reviewed cohort from clean consumer checkouts and
  # their completed certification receipts.
  local reviewed_fixture="$fixture/reviewed" reviewed_script reviewed_candidate reviewed_tree
  local reviewed_divergent
  local reviewed_jar reviewed_output valid_reviewed_attestation historic_reviewed_attestation
  local consumers="$fixture/reviewed-consumers" consumer_sava="$fixture/reviewed-consumers/sava"
  local consumer_ravina="$fixture/reviewed-consumers/ravina"
  local consumer_sava_copy="$fixture/reviewed-consumers/sava-copy"
  local consumer_missing="$fixture/reviewed-consumers/missing"
  local sava_commit sava_tree ravina_commit ravina_tree valid_sava_receipt
  local sava_receipt_sha ravina_core_receipt_sha ravina_server_receipt_sha
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

  write_reviewed_certification() {
    local receipt=$1 project=$2 session=$3 plugin=$4 suite=$5 git_project_directory=$6
    local git_commit git_tree
    local h1=1111111111111111111111111111111111111111111111111111111111111111
    local h2=2222222222222222222222222222222222222222222222222222222222222222
    local h3=3333333333333333333333333333333333333333333333333333333333333333
    local h4=4444444444444444444444444444444444444444444444444444444444444444
    mkdir -p "$(dirname "$receipt")"
    git_commit=$(git -C "$(dirname "$receipt")" rev-parse HEAD)
    git_tree=$(git -C "$(dirname "$receipt")" rev-parse 'HEAD^{tree}')
    printf '%s\n' \
      'schema	6' \
      "project	$project" \
      "session	$session" \
      'mode	fresh-full-strict' \
      'gitState	clean' \
      "gitCommit	$git_commit" \
      "gitTree	$git_tree" \
      'gitStatusSha256	e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' \
      "gitProjectDirectory	$git_project_directory" \
      "pluginSha256	$plugin" \
      'columns	suite	name	invocation	reportSha256	sourceSha256	classesSha256	configurationSha256	pitestVersion	pluginSha256	toolClasspathSha256	mutationToolchainSha256	recordInputsSha256	recordPitestVersion	recordMutationToolchainSha256' \
      "suite	$suite	$h1	$h2	$h3	$h4	$h1	1.25.9	$plugin	$h2	$h3	$h4	1.25.9	$h3" \
      > "$receipt"
  }
  write_empty_reviewed_certification() {
    local receipt=$1 project=$2 session=$3 plugin=$4 git_project_directory=$5
    local git_commit git_tree
    mkdir -p "$(dirname "$receipt")"
    git_commit=$(git -C "$(dirname "$receipt")" rev-parse HEAD)
    git_tree=$(git -C "$(dirname "$receipt")" rev-parse 'HEAD^{tree}')
    printf '%s\n' \
      'schema	6' \
      "project	$project" \
      "session	$session" \
      'mode	fresh-full-strict' \
      'gitState	clean' \
      "gitCommit	$git_commit" \
      "gitTree	$git_tree" \
      'gitStatusSha256	e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' \
      "gitProjectDirectory	$git_project_directory" \
      "pluginSha256	$plugin" \
      'columns	suite	name	invocation	reportSha256	sourceSha256	classesSha256	configurationSha256	pitestVersion	pluginSha256	toolClasspathSha256	mutationToolchainSha256	recordInputsSha256	recordPitestVersion	recordMutationToolchainSha256' \
      > "$receipt"
  }
  init_reviewed_consumer() {
    local checkout=$1 remote=$2
    mkdir -p "$checkout"
    printf '%s\n' 'build/' '*/build/' > "$checkout/.gitignore"
    printf '%s\n' 'consumer' > "$checkout/README.md"
    (
      cd "$checkout"
      GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git init -q
      GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null git remote add origin "$remote"
      GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
        git -c user.name=SelfTest -c user.email=self@test add .
      GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
        git -c user.name=SelfTest -c user.email=self@test commit -qm adoption
    )
  }
  mkdir -p "$consumers"
  init_reviewed_consumer "$consumer_sava" git@github.com:sava-software/sava.git
  init_reviewed_consumer "$consumer_ravina" https://github.com/sava-software/ravina.git
  init_reviewed_consumer "$consumer_sava_copy" ssh://git@github.com/SAVA-Software/SAVA.git
  init_reviewed_consumer "$consumer_missing" git@github.com:sava-software/missing.git
  write_reviewed_certification \
    "$consumer_sava/build/hardening/pitest-certification.tsv" : session.sava "$jar_hash" ws .
  write_reviewed_certification \
    "$consumer_ravina/build/hardening/pitest-certification.tsv" \
    : session.ravina.root "$jar_hash" core .
  write_reviewed_certification \
    "$consumer_ravina/ravina-core/build/hardening/pitest-certification.tsv" \
    :ravina-core session.ravina.core "$jar_hash" dispatch ravina-core
  write_empty_reviewed_certification \
    "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv" \
    :ravina-server session.ravina.server "$jar_hash" ravina-server
  write_reviewed_certification \
    "$consumer_sava_copy/build/hardening/pitest-certification.tsv" \
    : session.sava.copy "$jar_hash" ws .
  sava_commit=$(git -C "$consumer_sava" rev-parse HEAD)
  sava_tree=$(git -C "$consumer_sava" rev-parse 'HEAD^{tree}')
  ravina_commit=$(git -C "$consumer_ravina" rev-parse HEAD)
  ravina_tree=$(git -C "$consumer_ravina" rev-parse 'HEAD^{tree}')
  valid_sava_receipt=$(<"$consumer_sava/build/hardening/pitest-certification.tsv")

  expect_cli_failure "reviewed attestation without an adoption" \
    "requires at least one --adoption" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar"
  expect_cli_failure "short reviewed candidate" "not a full commit" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "${reviewed_candidate:0:12}" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  expect_cli_failure "non-ancestor reviewed candidate" "not an ancestor" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_divergent" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  expect_cli_failure "missing consumer checkout" "missing consumer checkout directory" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumers/absent"
  expect_cli_failure "consumer path traversal" "contains traversal" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumers/../reviewed-consumers/sava"
  expect_cli_failure "relative consumer path" "must be an absolute path" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "reviewed-consumers/sava"
  expect_cli_failure "consumer path trailing component" "contains traversal or an empty component" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava/"
  ln -s "$(basename "$consumer_sava")" "$consumers/sava-link"
  expect_cli_failure "symlinked consumer checkout" "contains a symlink component" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumers/sava-link"
  unlink "$consumers/sava-link"
  expect_cli_failure "consumer without certification" "no completed hardening certification" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_missing"
  write_empty_reviewed_certification \
    "$consumer_missing/build/hardening/pitest-certification.tsv" \
    : session.missing "$jar_hash" .
  expect_cli_failure "consumer with only zero-suite certification" "contain zero certified suites" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_missing"
  printf '%s\n' 'advance after certification' > "$consumer_sava/post-certification.txt"
  (
    cd "$consumer_sava"
    git add post-certification.txt
    git -c user.name=SelfTest -c user.email=self@test commit -qm 'advance after certification'
  )
  expect_cli_failure "stale consumer revision" "different consumer revision" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  write_reviewed_certification \
    "$consumer_sava/build/hardening/pitest-certification.tsv" : session.sava "$jar_hash" ws .
  sava_commit=$(git -C "$consumer_sava" rev-parse HEAD)
  sava_tree=$(git -C "$consumer_sava" rev-parse 'HEAD^{tree}')
  valid_sava_receipt=$(<"$consumer_sava/build/hardening/pitest-certification.tsv")

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "schema" { print "schema", "5"; next }
    $1 ~ /^git/ || $1 == "pluginSha256" { next }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "schema-5 consumer certification" "malformed or mixed certification" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "gitState" || $1 == "gitCommit" || $1 == "gitTree" ||
      $1 == "gitStatusSha256" || $1 == "gitProjectDirectory" { $2="unavailable" }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "unavailable certification Git identity" "does not bind a clean Git checkout" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "gitState" { $2="dirty" }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "dirty identity with clean status digest" "malformed or mixed certification" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "gitState" { $2="dirty" }
    $1 == "gitStatusSha256" {
      $2="1111111111111111111111111111111111111111111111111111111111111111"
    }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "dirty certification Git identity" "does not bind a clean Git checkout" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "gitCommit" { $2="0000000000000000000000000000000000000000" }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "mismatched certification Git revision" "different consumer revision" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "gitProjectDirectory" { $2="other" }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "mismatched certification project directory" \
    "path does not match its Git project directory" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"

  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "pluginSha256" {
      $2="0000000000000000000000000000000000000000000000000000000000000000"
    }
    { print }
  ' "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv" > \
    "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv"
  expect_cli_failure "zero-suite stale header plugin" "stale certification receipt" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_ravina"
  write_empty_reviewed_certification \
    "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv" \
    :ravina-server session.ravina.server "$jar_hash" ravina-server

  printf '%s\n' dirty > "$consumer_sava/dirty.txt"
  expect_cli_failure "dirty consumer checkout" "consumer checkout must be clean" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  unlink "$consumer_sava/dirty.txt"
  git -C "$consumer_sava" remote set-url origin /not/github
  expect_cli_failure "non-GitHub consumer origin" "not a supported GitHub remote" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  git -C "$consumer_sava" remote set-url origin git@github.com:sava-software/sava.git
  printf '%s\n' "$valid_sava_receipt" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.real"
  unlink "$consumer_sava/build/hardening/pitest-certification.tsv"
  ln -s pitest-certification.tsv.real \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "symlinked certification receipt" "evidence path contains a symlink" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  unlink "$consumer_sava/build/hardening/pitest-certification.tsv"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.real" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  printf '%s\n' running > "$consumer_sava/build/hardening/pitest-certification.running"
  expect_cli_failure "incomplete certification" "incomplete or still running" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  unlink "$consumer_sava/build/hardening/pitest-certification.running"
  sed "s/$jar_hash/0000000000000000000000000000000000000000000000000000000000000000/" \
    "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "stale consumer certification" "stale certification receipt" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"
  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "suite" {
      $9="0000000000000000000000000000000000000000000000000000000000000000"
    }
    { print }
  ' "$consumer_sava/build/hardening/pitest-certification.tsv" > \
    "$consumer_sava/build/hardening/pitest-certification.tsv.tmp"
  mv "$consumer_sava/build/hardening/pitest-certification.tsv.tmp" \
    "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "stale suite-row plugin with current header" \
    "malformed or mixed certification" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"
  printf '%b\n' "suite\tsecond\t1111111111111111111111111111111111111111111111111111111111111111\t2222222222222222222222222222222222222222222222222222222222222222\t0000000000000000000000000000000000000000000000000000000000000000\t4444444444444444444444444444444444444444444444444444444444444444\t1111111111111111111111111111111111111111111111111111111111111111\t1.25.9\t$jar_hash\t2222222222222222222222222222222222222222222222222222222222222222\t3333333333333333333333333333333333333333333333333333333333333333\t4444444444444444444444444444444444444444444444444444444444444444\t1.25.9\t3333333333333333333333333333333333333333333333333333333333333333" \
    >> "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "mixed consumer certification" "malformed or mixed certification" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  printf '%s\n' "$valid_sava_receipt" > "$consumer_sava/build/hardening/pitest-certification.tsv"
  expect_cli_failure "duplicate derived adoption" "duplicate derived adoption slug" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava" \
      --adoption "$consumer_sava_copy"
  expect_cli_failure "missing reviewed plugin JAR" "missing or symlinked reviewed plugin JAR" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar.missing" --adoption "$consumer_sava"
  ln -s "$(basename "$reviewed_jar")" "$reviewed_jar.link"
  expect_cli_failure "symlinked reviewed plugin JAR" "missing or symlinked reviewed plugin JAR" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar.link" --adoption "$consumer_sava"
  unlink "$reviewed_jar.link"
  git -C "$reviewed_fixture" remote set-url origin git@github.com:example/not-sava-build.git
  expect_cli_failure "wrong reviewed candidate origin" "candidate origin is" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
  git -C "$reviewed_fixture" remote set-url origin git@github.com:sava-software/sava-build.git
  sava_receipt_sha=$(sha256_file \
    "$consumer_sava/build/hardening/pitest-certification.tsv")
  ravina_core_receipt_sha=$(sha256_file \
    "$consumer_ravina/ravina-core/build/hardening/pitest-certification.tsv")
  ravina_server_receipt_sha=$(sha256_file \
    "$consumer_ravina/ravina-server/build/hardening/pitest-certification.tsv")
  "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
    --plugin-jar "$reviewed_jar" --adoption "$consumer_sava" \
    --adoption "$consumer_ravina" >/dev/null
  jq -e --arg candidate "$reviewed_candidate" --arg tree "$reviewed_tree" --arg jar "$jar_hash" \
      --arg sava_commit "$sava_commit" --arg sava_tree "$sava_tree" \
      --arg ravina_commit "$ravina_commit" --arg ravina_tree "$ravina_tree" \
      --arg sava_receipt_sha "$sava_receipt_sha" \
      --arg ravina_core_receipt_sha "$ravina_core_receipt_sha" \
      --arg ravina_server_receipt_sha "$ravina_server_receipt_sha" '
    .schema == 3 and .candidate.commit == $candidate and
    .candidate.tree == $tree and
    .candidate.plugin_jar_sha256 == $jar and
    .review.kind == "derived-local-hardening-certifications" and
    [.review.repositories[].slug] == ["sava-software/ravina","sava-software/sava"] and
    .review.repositories[0].commit == $ravina_commit and
    .review.repositories[0].tree == $ravina_tree and
    .review.repositories[0].plugin_jar_sha256 == $jar and
    [.review.repositories[0].certifications[].path] ==
      ["build/hardening/pitest-certification.tsv",
       "ravina-core/build/hardening/pitest-certification.tsv",
       "ravina-server/build/hardening/pitest-certification.tsv"] and
    all(.review.repositories[0].certifications[];
      .schema == 6 and (.receipt_sha256 | test("^[0-9a-f]{64}$"))) and
    [.review.repositories[0].certifications[].git_project_directory] ==
      [".","ravina-core","ravina-server"] and
    .review.repositories[0].certifications[1].receipt_sha256 ==
      $ravina_core_receipt_sha and
    .review.repositories[0].certifications[2].receipt_sha256 ==
      $ravina_server_receipt_sha and
    .review.repositories[0].certifications[2].suite_count == 0 and
    .review.repositories[0].certifications[2].suites == [] and
    .review.repositories[1].commit == $sava_commit and
    .review.repositories[1].tree == $sava_tree and
    .review.repositories[1].origin == "https://github.com/sava-software/sava.git" and
    .review.repositories[1].plugin_jar_sha256 == $jar and
    .review.repositories[1].certifications[0].receipt_sha256 == $sava_receipt_sha and
    .review.repositories[1].certifications[0].suites == ["ws"]
  ' "$reviewed_output" >/dev/null
  expect_cli_failure "reviewed attestation overwrite" "refusing to overwrite existing release record" \
    "$reviewed_script" create-reviewed 1.0.1 --candidate "$reviewed_candidate" \
      --plugin-jar "$reviewed_jar" --adoption "$consumer_sava"
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
  historic_reviewed_attestation=$(jq -nS --arg candidate "$reviewed_candidate" \
    --arg tree "$reviewed_tree" --arg jar "$jar_hash" \
    '{schema:2,kind:"sava-build-release-attestation",version:"1.0.1",
      candidate:{commit:$candidate,tree:$tree,origin:"sava-software/sava-build",
        plugin_jar_sha256:$jar},
      review:{kind:"reviewed-local-adoption-passes",
        repositories:["sava-software/ravina","sava-software/sava"]}}')
  printf '%s\n' "$historic_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'historical schema 2 review'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore schema 3 review'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  jq '.review.repositories[0].slug = "SAVA-SOFTWARE/RAVINA" |
      .review.repositories[0].origin = "https://github.com/SAVA-SOFTWARE/RAVINA.git"' \
    "$reviewed_output" > "$reviewed_output.tmp"
  mv "$reviewed_output.tmp" "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'uppercase review slug'
  )
  expect_cli_failure "uppercase adoption in committed review" "invalid release record schema" \
    "$reviewed_script" verify 1.0.1
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore lowercase review slug'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
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
  jq '.review.repositories[0].plugin_jar_sha256 =
      "0000000000000000000000000000000000000000000000000000000000000000"' \
    "$reviewed_output" > "$reviewed_output.tmp"
  mv "$reviewed_output.tmp" "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'mismatched review plugin'
  )
  expect_cli_failure "mismatched plugin in committed review" "invalid release record schema" \
    "$reviewed_script" verify 1.0.1
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore matched review plugin'
  )
  "$reviewed_script" verify 1.0.1 >/dev/null
  jq '.review.repositories[0].certifications[0].path =
      "../build/hardening/pitest-certification.tsv"' \
    "$reviewed_output" > "$reviewed_output.tmp"
  mv "$reviewed_output.tmp" "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'escaping review receipt'
  )
  expect_cli_failure "escaping receipt in committed review" "invalid release record schema" \
    "$reviewed_script" verify 1.0.1
  printf '%s\n' "$valid_reviewed_attestation" > "$reviewed_output"
  (
    cd "$reviewed_fixture"
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git add release-attestations/1.0.1.json
    GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null \
      git -c user.name=SelfTest -c user.email=self@test commit -qm 'restore contained review receipt'
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
      echo "release-attestation: create-reviewed requires at least one --adoption consumer-checkout" >&2
      exit 2
    fi
    create_reviewed_attestation "$version" "$candidate" "$plugin_jar" "${adoptions[@]}"
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
