#!/usr/bin/env bash
# Refuses, by name and before the JDK downloads, a Java version below the floor of check.sh,
# which compiles TrustedChains with the JDK under test at --release 21: a lower JDK would fail
# there after its download, with a message that names no input. Arguments: pairs of a label and
# a version, the label naming the input the version comes from for the one error line (empty
# for none); two arguments each, so no value is parsed for a separator. A version that does not
# start with a number (an early-access project name) is left to setup-java. Run by the
# setup-checked-jdk action and by sava-build's root action.
set -euo pipefail
floor=21
if [ $(( $# % 2 )) -ne 0 ]; then
  echo "::error::release-floor.sh takes pairs of a label and a version; got $# argument(s)."
  exit 2
fi
while [ $# -gt 0 ]; do
  label="$1"
  v="$2"
  shift 2
  if [[ "$v" =~ ^([0-9]+) ]] && [ "${BASH_REMATCH[1]}" -lt "$floor" ]; then
    echo "::error::Java version '$v' is below $floor, the floor of the trust check compiled with the installed JDK (check-jdk-trust)${label:+; it comes from $label}."
    exit 1
  fi
done
