#!/usr/bin/env bash
# Refuses, by name and before the JDK downloads, a Java version below the floor of check.sh,
# which compiles TrustedChains with the JDK under test at --release 21: a lower JDK would fail
# there after its download, with a message that names no input. Arguments: the versions to
# check. A value that does not start with a number (an early-access project name) is left to
# setup-java. Run by the setup-checked-jdk action and by sava-build's root action.
set -euo pipefail
floor=21
for v in "$@"; do
  if [[ "$v" =~ ^([0-9]+) ]] && [ "${BASH_REMATCH[1]}" -lt "$floor" ]; then
    echo "::error::Java version '$v' is below $floor, the floor of the trust check compiled with the installed JDK (check-jdk-trust)."
    exit 1
  fi
done
