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
#      static half (row shape, README causes), the exclusion audit's static
#      half (when a prior run left build/mutation-classes behind), and falls
#      back to the baseline when no report exists, so no mutation runs are
#      needed
#   3. verifies each green build actually resolved 0.0.0-test: a settings
#      snippet predating -PsavaBuildLocalRepo ignores the property and resolves
#      the RELEASED plugin — green output that canaries nothing. The 0.0.0-test
#      settings plugin prints the local-repo notice at the end of every build it
#      was resolved into (configuration-cache hits included), so its absence
#      from a green build is a failure, not a maybe.
#   4. reprints every hardening warning it saw, per repo, and fails if any
#      consumer build failed (advisory findings are reported, never failures)
#
# Usage:
#   tools/fleet-canary.sh <consumer-repo-dir>[:<pitestSuiteTask>]...
#
# The optional :<pitestSuiteTask> (e.g. json-iterator:pitestUtil) is the deep
# leg: the named suite runs TWICE as a real mutation run, then its verify.
# Static checks cannot see the state the verify cycles between runs — the
# machine-local status stash is written by run N and read by run N+1, and both
# post-21.5.20 escapes lived exactly there (a flip advisory that false-fired
# from the second run on, and a format boundary that garbled the first
# comparison after a bump). One repo carrying the deep leg with its cheapest
# suite covers the class; name it on the repo whose baselines are richest in
# same-key siblings.
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
findings_pattern='malformed row|not in the audited set|appear nowhere|match no mutant|no argument in config|advisory finding|written by PIT|swallowed by excludedClasses|match no swallowed|suppress nothing|marker dance'

# The stash-cycle messages only the deep leg's two consecutive real runs can
# provoke, pinned on the same terms by 'the deep leg filter matches every
# stash-cycle message' — reword one and that test names this line.
deep_pattern='flipped SURVIVED -> TIMED_OUT|flipped NO_COVERAGE -> TIMED_OUT|timed-out drift vs previous run|predates the current stash format'

# The resolution proof: the 0.0.0-test settings plugin's FlowAction prints this line
# at the end of every build it was actually resolved into. Coupled to the notice's
# wording like the filter above; pinned by LocalRepoNoticeFunctionalTest ('the fleet
# canary resolution needle matches the notice') — reword the notice and that test
# names this line before the canary starts flagging every healthy repo.
resolution_notice="resolved every 'software.sava.build*' plugin to 0.0.0-test"

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
for arg in "$@"; do
  repo="${arg%%:*}"
  deep_task=""
  case "$arg" in *:*) deep_task="${arg#*:}" ;; esac
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
  else
    # Hardening repos also check their AGENTS.md template marker against THIS
    # checkout's digest: a template edit breaks every consumer's 'check' at
    # bump time by design, and the canary is where that obligation should be
    # announced — as a per-repo ADVISORY naming the marker dance (matched by
    # the reprint filter above) — before the release creates it, not one repo
    # at a time after. Advisory, not a failure: under -PsavaBuildLocalRepo a
    # stale marker is the expected state — the repo acknowledges a released
    # digest, and this checkout's has not shipped. Failing here forced repos
    # to acknowledge unreleased digests, which wedged their 'check' against
    # every published plugin until the release landed. fuzzWorkflowInSync
    # rides along: quiet in repos without a weekly soak, and a sub-second
    # static check that every registered fuzz target is actually in the soak's
    # task list where one exists.
    tasks="$tasks
agentsTemplateInSync
fuzzWorkflowInSync"
  fi
  echo "fleet-canary: $repo — $(echo "$tasks" | tr '\n' ' ')"
  if ! (cd "$repo" && ./gradlew --console=plain -PsavaBuildLocalRepo="$local_repo" $tasks) > "$out_file" 2>&1; then
    failed="$failed $repo"
    echo "fleet-canary: FAILED $repo — full output:"
    cat "$out_file"
  elif ! grep -qF "$resolution_notice" "$out_file"; then
    # Green, but this checkout's plugin never ran: nothing was canaried, and
    # reporting the repo green would be the canary's own version of the false
    # certification the strict flags refuse.
    failed="$failed $repo"
    echo "fleet-canary: FAILED $repo — build green but 0.0.0-test was never resolved" \
      "(settings snippet predating -PsavaBuildLocalRepo? see README's canonical form)"
  fi
  # Reprint what a person must review: the hardening warnings, if any.
  findings=$(grep -E "$findings_pattern" "$out_file" || true)
  if [ -n "$findings" ]; then
    warned="$warned $repo"
    echo "$findings"
  fi

  # Deep leg: two real runs of one suite so the verify compares against a stash
  # THIS checkout's plugin wrote, both rounds' verify output held to the same
  # review — round 1 owns the boundary messages (a stash-format reset prints
  # once and rewrites), round 2 the first real comparison. --rerun-tasks on
  # BOTH rounds: an up-to-date pitest task reuses a prior report and the stash
  # never cycles at all.
  if [ -n "$deep_task" ]; then
    echo "fleet-canary: $repo — deep leg: $deep_task twice (real mutation runs)"
    deep_findings=""
    for round in 1 2; do
      if ! (cd "$repo" && ./gradlew --console=plain -PsavaBuildLocalRepo="$local_repo" \
          --rerun-tasks "$deep_task") > "$out_file" 2>&1; then
        failed="$failed $repo(deep$round)"
        echo "fleet-canary: FAILED $repo — deep leg round $round; full output:"
        cat "$out_file"
        break
      fi
      # drift/flip/reset lines are exactly what the deep leg exists to surface;
      # they are load-judgment for a person, never failures. Grepped per round —
      # the stash-format reset notice prints on round 1 only, and a shared output
      # file would let round 2 overwrite it before a single grep saw it.
      round_findings=$(grep -E "$findings_pattern|$deep_pattern" "$out_file" || true)
      if [ -n "$round_findings" ]; then
        deep_findings="$deep_findings$round_findings"$'\n'
      fi
    done
    if [ -n "$deep_findings" ]; then
      warned="$warned $repo(deep)"
      printf '%s' "$deep_findings"
    fi
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
