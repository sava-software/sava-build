#!/usr/bin/env bash
# Warns, as a workflow annotation, when the project in the working directory does not verify its
# dependencies: no gradle/verification-metadata.xml; org.gradle.dependency.verification set to off
# or lenient in gradle.properties, which java.util.Properties itself reads here, as Gradle reads
# it (the last definition of a key; '=', ':' or whitespace between key and value; escapes and
# backslash continuations as it keeps them; the key case-sensitive, the value not), in either
# spelling, the Gradle property or the systemProp. one, since each disables verification on its
# own; or a metadata file whose verify-signatures is not true (Gradle's
# default for a missing element is false; the element is read case-insensitively, without
# whitespace and XML comments). Only that switch is read: a key under <ignored-keys> or a
# <trusted-artifacts> entry exempts what it names from signature verification and is not seen.
# verify-metadata governs the metadata files (POM, module) only and is not read; a -D or
# --dependency-verification on a command line is not seen. Run by sava-build's root action after
# its JDK setup, whose JAVA_HOME runs the properties reader (the path's java otherwise): a JVM
# start and an in-memory compile per run, 0.3 s on a laptop, taken over a grep that read the file
# its own way; a home that cannot run it, which has failed check.sh's compile before this step in
# the root action, fails this one too. Otherwise exits 0: a warning until the fleet carries the
# file, then an error. DependencyVerificationNoticeTest in
# sava-build runs it over fixtures.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
metadata=gradle/verification-metadata.xml
if [ ! -f "$metadata" ]; then
  echo "::warning::No gradle/verification-metadata.xml: Gradle verifies neither the checksums nor the signatures of this build's dependencies; sava-build's README (Verifying sava artifacts) has the procedure."
  exit 0
fi
# The values as Properties reads them, one key=value line per key defined; lower-cased by tr, not
# ${var,,}, since a macOS runner's bash is 3.2.
java="${JAVA_HOME:+$JAVA_HOME/bin/java}"
java="${java:-java}"
# A java that cannot run the reader fails the step: a pass it did not earn would be worse.
values=
if [ -f gradle.properties ]; then
  values=$("$java" "$here/src/software/sava/build/guard/PropertyValues.java" gradle.properties org.gradle.dependency.verification systemProp.org.gradle.dependency.verification | LC_ALL=C tr '[:upper:]' '[:lower:]')
fi
while IFS= read -r line; do
  [ -n "$line" ] || continue
  if [[ "${line#*=}" =~ ^[[:space:]]*(off|lenient)[[:space:]]*$ ]]; then
    echo "::warning::gradle/verification-metadata.xml is present but org.gradle.dependency.verification in gradle.properties sets verification off or lenient: the build's dependencies are not verified."
    exit 0
  fi
done <<< "$values"
squeezed=$(LC_ALL=C tr -d '[:space:]' < "$metadata" | LC_ALL=C tr '[:upper:]' '[:lower:]')
while [[ "$squeezed" == *'<!--'* ]]; do
  before=${squeezed%%<!--*}
  after=${squeezed#*<!--}
  squeezed="$before${after#*-->}"
done
if [[ "$squeezed" != *'<verify-signatures>true</verify-signatures>'* ]]; then
  echo "::warning::gradle/verification-metadata.xml verifies checksums but not signatures (verify-signatures absent or false); sava-build's README (Verifying sava artifacts) has the procedure that adds the sava key."
fi
