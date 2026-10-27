#!/usr/bin/env bash
# Checks the JDK that oracle-actions/setup-java, at the pin in action.yml beside this script,
# runs its downloader on: JAVA_HOME_21_X64, or JAVA_HOME_21_arm64, the action's own choice at
# that pin ("Run action with Java 21"; earlier releases used 17 and 11). Fails when neither is
# set, since the action would then fall back to /bin/java, which nothing checks. Run by the
# setup-checked-jdk action and by sava-build's root action, before the JDK downloads (the
# actions themselves were fetched at job setup, before any step, out of reach).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
home="${JAVA_HOME_21_X64:-}"
[ -d "$home" ] || home="${JAVA_HOME_21_arm64:-}"
if [ -z "$home" ]; then
  echo "::error::downloader-jdk.sh (setup-checked-jdk, sava-build's root action): neither JAVA_HOME_21_X64 nor JAVA_HOME_21_arm64 names a JDK 21 home. oracle-actions/setup-java reads the same variable and otherwise falls back to /bin/java, which nothing checks; set the variable to the JDK the download should run on."
  exit 1
fi
exec bash "$here/../check-jdk-trust/check.sh" "$home"
