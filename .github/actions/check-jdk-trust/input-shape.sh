#!/usr/bin/env bash
# Refuses, by name, an input whose shape setup-java or the trust check cannot take: a download
# site that is not a host name, a release that is not a single feature release number or project
# name, or one below the floor (release-floor.sh). Arguments: --present or --all, then the site's
# label and value, then pairs of a version's label and value. Under --present an empty value
# passes, for a caller that checks what it has before anything runs and leaves the rest to a
# later step; under --all an empty value is refused. An empty site label stands for no site input
# at all (a job on actions/setup-java), and no site is checked. The label names the input and its
# variable and is all a shape refusal shows: a malformed value is not echoed, since a runner reads
# annotations out of a step's output; a well-formed one below the floor is named by
# release-floor.sh. Run by sava-build's root action, twice, by setup-checked-jdk and by the
# jlink-without-jmods workflow.
set -euo pipefail
# The bracket ranges below are collation-dependent in some locales; one rule for every caller.
export LC_ALL=C
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mode="${1:-}"
case "$mode" in
  --present|--all) ;;
  *) echo "::error::input-shape.sh: the first argument is --present or --all."; exit 2 ;;
esac
shift
if [ $(( $# % 2 )) -ne 0 ] || [ $# -lt 2 ]; then
  echo "::error::input-shape.sh takes a site's label and value, then pairs of a version's label and value; got $# argument(s)."
  exit 2
fi
site_re='^[A-Za-z0-9.-]+$'
release_re='^[A-Za-z0-9._-]+$'
label="$1"
site="$2"
shift 2
if [ -z "$label" ] && [ -n "$site" ]; then
  echo "::error::input-shape.sh: a site value under an empty label; the label names the input, and both are empty for a caller without a site."
  exit 2
fi
if [ -n "$label" ]; then
  if [ -z "$site" ]; then
    [ "$mode" = --present ] || { echo "::error::$label is empty."; exit 1; }
  elif ! [[ "$site" =~ $site_re ]]; then
    echo "::error::$label is not a host name."
    exit 1
  fi
fi
floor_args=()
while [ $# -gt 0 ]; do
  label="$1"
  version="$2"
  shift 2
  if [ -z "$version" ]; then
    [ "$mode" = --present ] || { echo "::error::$label is empty."; exit 1; }
  elif ! [[ "$version" =~ $release_re ]]; then
    echo "::error::$label is not a single release: setup-java's release is a feature release number or a project name."
    exit 1
  fi
  floor_args+=("$label" "$version")
done
# The array may be empty, which bash 3.2 (macOS) refuses under set -u without the guard.
exec bash "$here/release-floor.sh" ${floor_args[@]+"${floor_args[@]}"}
