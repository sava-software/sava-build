#!/usr/bin/env bash
# Warns, as a workflow annotation, when the project in the working directory does not verify its
# dependencies: no gradle/verification-metadata.xml; org.gradle.dependency.verification set to off
# or lenient in gradle.properties (its last definition, as java.util.Properties keeps it; '=', ':'
# or whitespace between key and value; the key case-sensitive and the value not, as Gradle reads
# them; the systemProp. spelling too; a backslash continuation is not followed); or a metadata
# file whose verify-signatures is not true (Gradle's
# default for a missing element is false; the element is read case-insensitively, without
# whitespace and XML comments). verify-metadata governs the metadata files (POM, module) only and
# is not read; a -D or --dependency-verification on a command line is not seen. Run by sava-build's
# root action after its JDK setup. Exits 0: a warning until the fleet carries the file, then an
# error. DependencyVerificationNoticeTest in sava-build runs it over fixtures.
set -euo pipefail
metadata=gradle/verification-metadata.xml
if [ ! -f "$metadata" ]; then
  echo "::warning::No gradle/verification-metadata.xml: Gradle verifies neither the checksums nor the signatures of this build's dependencies; sava-build's README (Verifying sava artifacts) has the procedure."
  exit 0
fi
# Lower-cased by tr, not ${var,,}: a macOS runner's bash is 3.2.
last=$({ [ -f gradle.properties ] && LC_ALL=C grep -E '^[[:space:]]*(systemProp\.)?org\.gradle\.dependency\.verification[[:space:]]*[=:[:space:]]' gradle.properties || :; } | tail -n 1 | LC_ALL=C tr '[:upper:]' '[:lower:]')
if [[ "$last" =~ [=:[:space:]][[:space:]]*(off|lenient)[[:space:]]*$ ]]; then
  echo "::warning::gradle/verification-metadata.xml is present but org.gradle.dependency.verification in gradle.properties sets verification off or lenient: the build's dependencies are not verified."
  exit 0
fi
squeezed=$(LC_ALL=C tr -d '[:space:]' < "$metadata" | LC_ALL=C tr '[:upper:]' '[:lower:]')
while [[ "$squeezed" == *'<!--'* ]]; do
  before=${squeezed%%<!--*}
  after=${squeezed#*<!--}
  squeezed="$before${after#*-->}"
done
if [[ "$squeezed" != *'<verify-signatures>true</verify-signatures>'* ]]; then
  echo "::warning::gradle/verification-metadata.xml verifies checksums but not signatures (verify-signatures absent or false); sava-build's README (Verifying sava artifacts) has the procedure that adds the sava key."
fi
