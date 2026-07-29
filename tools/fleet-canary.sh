#!/usr/bin/env bash
# Pre-release fleet canary: validates consumer repos against this checkout's
# unreleased plugin before a release PR is merged. Most rollout surprises are
# advisory checks meeting real consumer data (committed baselines, audited
# timeout sets, README causes) that synthetic test fixtures cannot enumerate —
# this runs the real checks against the real data, in seconds per repo.
#
# What it does:
#   1. publishes this checkout as 0.0.0-test to build/sava-test-repo
#   2. per consumer repo, derives every pitest<Suite>Debt task from the
#      committed config/pitest/*-accepted.csv / *-timeouts.csv files and runs
#      them with -PsavaBuildLocalRepo — Debt carries the audited-timeout set's
#      static half (row shape, README causes) and falls back to the baseline
#      when no report exists, so no mutation runs are needed
#   3. reprints every hardening warning it saw, per repo, and fails if any
#      consumer build failed (advisory findings are reported, never failures)
#
# Usage:
#   tools/fleet-canary.sh <consumer-repo-dir>...
#
# Notes earned the hard way:
#   - Debt tasks are invoked by NAME, not project path: nested module
#     directories (a kms/core layout) need not match Gradle project paths, and
#     a bare name runs in every project that has the task.
#   - No --quiet anywhere: Gradle's --quiet suppresses WARN, which is exactly
#     the output being canaried.
set -euo pipefail

sava_build_dir=$(cd "$(dirname "$0")/.." && pwd)
local_repo="$sava_build_dir/build/sava-test-repo"

# The reprint filter: one alternation per hardening warning a person must review.
# Deliberately string-coupled to the plugin's message texts; the coupling is pinned
# by HardeningRatchetFunctionalTest ('the fleet canary reprint filter matches every
# warning it canaries'), which provokes each warning and greps a real verify's
# output with this exact pattern — reword a message and that test names this line.
findings_pattern='malformed row|not in the audited set|appear nowhere|match no mutant|no argument in config|advisory finding'

if [ "$#" -eq 0 ]; then
  echo "usage: tools/fleet-canary.sh <consumer-repo-dir>..." >&2
  exit 2
fi

echo "fleet-canary: publishing 0.0.0-test from $sava_build_dir"
(cd "$sava_build_dir" && ./gradlew --console=plain publishSavaBuildTestPublicationToSavaTestRepoRepository) > /dev/null

out_file=$(mktemp)
trap 'rm -f "$out_file"' EXIT

failed=""
warned=""
for repo in "$@"; do
  if [ ! -f "$repo/gradlew" ]; then
    echo "fleet-canary: SKIP $repo — no gradlew" >&2
    continue
  fi
  # Suite names from committed pitest config; every registered suite with a
  # baseline or an audited set has a Debt task.
  tasks=$(find "$repo" -type d -name build -prune -o \
      \( -name '*-accepted.csv' -o -name '*-timeouts.csv' \) -path '*/config/pitest/*' -print \
    | sed -e 's|.*/||' -e 's|-accepted\.csv$||' -e 's|-timeouts\.csv$||' \
    | sort -u \
    | awk '{ print "pitest" toupper(substr($0, 1, 1)) substr($0, 2) "Debt" }')
  if [ -z "$tasks" ]; then
    # Still worth a run: plugin application and settings-snippet compatibility
    # break loudest in repos with nothing else to check.
    echo "fleet-canary: $repo — no pitest baselines; plugin-resolution smoke test only"
    tasks="help"
  fi
  echo "fleet-canary: $repo — $(echo "$tasks" | tr '\n' ' ')"
  if ! (cd "$repo" && ./gradlew --console=plain -PsavaBuildLocalRepo="$local_repo" $tasks) > "$out_file" 2>&1; then
    failed="$failed $repo"
    echo "fleet-canary: FAILED $repo — full output:"
    cat "$out_file"
  fi
  # Reprint what a person must review: the hardening warnings, if any.
  findings=$(grep -E "$findings_pattern" "$out_file" || true)
  if [ -n "$findings" ]; then
    warned="$warned $repo"
    echo "$findings"
  fi
done

echo
if [ -n "$failed" ]; then
  echo "fleet-canary: FAILED:$failed"
  exit 1
fi
if [ -n "$warned" ]; then
  echo "fleet-canary: green, with advisory findings in:$warned (reprinted above — advisory, not failures)"
else
  echo "fleet-canary: green — no failures, no advisory findings"
fi
