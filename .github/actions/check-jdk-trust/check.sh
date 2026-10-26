#!/usr/bin/env bash
# Compiles and runs TrustedChains on a JDK against the paths under chains/, and turns its exit
# status into a workflow annotation. Argument: the JDK's home (empty means JAVA_HOME). Shared by
# the check-jdk-trust and setup-checked-jdk actions, which run it from their own directories.
# Exits 0 for a clean JDK, 1 for one that still trusts a compromised issuer, 2 when undecided.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
home="${1:-${JAVA_HOME:-}}"
if [ -z "$home" ] || [ ! -x "$home/bin/java" ] || [ ! -x "$home/bin/javac" ]; then
  echo "::error::check-jdk-trust: no JDK at '$home'; pass java-home or set JAVA_HOME to a JDK."
  exit 2
fi
classes=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/check-jdk-trust.XXXXXX") || {
  echo "::error::check-jdk-trust could not create a working directory under ${RUNNER_TEMP:-${TMPDIR:-/tmp}}."
  exit 2
}
trap 'rm -rf "$classes" || :' EXIT
# The whole source directory, as the Gradle check compiles it, not one file by name.
if ! find "$here/src" -name '*.java' -print0 | xargs -0 "$home/bin/javac" --release 21 -d "$classes"; then
  echo "::error::check-jdk-trust could not compile its validator with the JDK at $home (a JDK below 21, or a broken one); the output above says why."
  exit 2
fi
status=0
"$home/bin/java" -cp "$classes" software.sava.build.guard.TrustedChains "$here/chains" 2>&1 | tee "$classes/report.txt" || status=$?
if [ "$status" = 0 ]; then
  exit 0
fi
# TrustedChains exits 0, 2 or 3 and closes its report with a verdict line. 1 is the java launcher's
# own, a VM that would not start, and HotSpot itself exits 3 on -XX:+ExitOnOutOfMemoryError, so a 3
# counts as the verdict only under the verdict's closing line.
if [ "$status" = 3 ] && grep -q 'this runtime still trusts a compromised issuer\.$' "$classes/report.txt"; then
  echo "::error::The JDK at $home still trusts a compromised eMudhra intermediate CA (CVE-2026-91403): a path above validates, or a root they chain to is a trust anchor. Install a release that removes the roots; chains/README.md in sava-build names them, and openjdk.org/emudhra has the vendor guidance."
  exit 1
fi
echo "::error::check-jdk-trust could not decide about the JDK at $home (exit $status); the output above says why."
exit 2
