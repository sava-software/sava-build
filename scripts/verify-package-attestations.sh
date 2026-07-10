#!/usr/bin/env bash
#
# verify-package-attestations.sh
#
# Verify the GitHub build-provenance attestations for the Maven artifacts that
# `.github/workflows/gradle_plugin_publish.yml` publishes to GitHub Packages.
#
# That workflow:
#   * publishes the `software.sava:sava-build` Gradle plugin (main / sources /
#     javadoc jars) to https://maven.pkg.github.com/sava-software/sava-build
#   * runs `actions/attest` over `**/build/libs/*.jar`, which produces a signed
#     SLSA build-provenance attestation stored in GitHub's attestation store
#     (keyed by the artifact's sha256 digest).
#
# This script downloads the published jars, fetches each jar's attestation
# bundle from the GitHub attestations REST API, and verifies it with the
# `cosign` CLI, run from the `glam.systems/cosign:local` Docker image.
#
# Requirements (on the host): bash, curl, docker, python3, and sha256sum or
# shasum. A GitHub token with `read:packages` access is required to download
# the artifacts (and to read attestations for a private repo).
#
# Usage:
#   GITHUB_TOKEN=ghp_xxx ./scripts/verify-package-attestations.sh [VERSION]
#
# If VERSION is omitted the latest version from maven-metadata.xml is used.
#
# Environment overrides (all optional):
#   GITHUB_TOKEN          GitHub token with read:packages (required).
#   COSIGN_IMAGE          cosign Docker image       (default: glam.systems/cosign:local)
#   OWNER                 GitHub org/owner          (default: sava-software)
#   REPO                  GitHub repository         (default: sava-build)
#   GROUP                 Maven groupId             (default: software.sava)
#   ARTIFACT              Maven artifactId          (default: sava-build)
#   WORKFLOW              Workflow file name        (default: gradle_plugin_publish.yml)
#   WORKFLOW_REPO         Repository that DEFINES the attesting workflow
#                         (default: REPO). Artifacts published through the
#                         reusable publish.yml run in the consumer repository
#                         (REPO, which stores the jars and attestations), but
#                         the signing certificate's identity references the
#                         called workflow, i.e. sava-build's publish.yml — so
#                         pass WORKFLOW_REPO=sava-build for consumer artifacts.
#   CLASSIFIERS           Space-separated jar classifiers to verify. Use the
#                         literal "MAIN" for the classifier-less main jar.
#                         (default: "MAIN sources javadoc")
#
set -euo pipefail

# ----------------------------------------------------------------------------
# Configuration
# ----------------------------------------------------------------------------
COSIGN_IMAGE="${COSIGN_IMAGE:-glam.systems/cosign:local}"
OWNER="${OWNER:-sava-software}"
REPO="${REPO:-sava-build}"
GROUP="${GROUP:-software.sava}"
ARTIFACT="${ARTIFACT:-sava-build}"
WORKFLOW="${WORKFLOW:-gradle_plugin_publish.yml}"
WORKFLOW_REPO="${WORKFLOW_REPO:-${REPO}}"
OIDC_ISSUER="https://token.actions.githubusercontent.com"

# The Fulcio certificate SAN that `actions/attest` is issued is built from the
# OIDC token's job_workflow_ref, e.g. for a tag-push run of the workflow:
#   https://github.com/<owner>/<repo>/.github/workflows/<workflow>@refs/tags/<tag>
# For a job defined by a REUSABLE workflow, job_workflow_ref points at the
# called workflow (WORKFLOW_REPO), not the calling repository (REPO). We match
# any git ref with a regexp so the same script keeps working across releases.
CERT_IDENTITY_REGEXP="^https://github.com/${OWNER}/${WORKFLOW_REPO}/\.github/workflows/${WORKFLOW}@refs/"

# Default to the main jar plus the sources/javadoc jars that the attest step
# also covers via `**/build/libs/*.jar`. A leading empty element ("") means the
# classifier-less main jar.
read -r -a CLASSIFIERS <<<"${CLASSIFIERS-MAIN sources javadoc}"

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "ERROR: GITHUB_TOKEN must be set (needs read:packages access)." >&2
  exit 2
fi

# ----------------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------------
GROUP_PATH="${GROUP//.//}"
MAVEN_BASE="https://maven.pkg.github.com/${OWNER}/${REPO}/${GROUP_PATH}/${ARTIFACT}"
API_BASE="https://api.github.com/repos/${OWNER}/${REPO}"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

cosign() {
  docker run --rm \
    -v "${WORKDIR}:/work" -w /work \
    --entrypoint cosign "${COSIGN_IMAGE}" "$@"
}

# ----------------------------------------------------------------------------
# Resolve version
# ----------------------------------------------------------------------------
VERSION="${1:-}"
if [[ -z "${VERSION}" ]]; then
  echo "==> Resolving latest version from maven-metadata.xml"
  VERSION="$(curl -fsSL -u "x:${GITHUB_TOKEN}" "${MAVEN_BASE}/maven-metadata.xml" \
    | python3 -c "import sys,re;print(re.search(r'<latest>([^<]+)</latest>',sys.stdin.read()).group(1))")"
fi
echo "==> Verifying ${GROUP}:${ARTIFACT}:${VERSION} published by ${OWNER}/${REPO} (${WORKFLOW})"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT
# mktemp -d is mode 0700; relax it so the non-root `cosign` user inside the
# container can read the bind-mounted jars and bundles.
chmod 755 "${WORKDIR}"

verified=0
attested=0
failed=0

for classifier in "${CLASSIFIERS[@]}"; do
  if [[ "${classifier}" == "MAIN" || -z "${classifier}" ]]; then
    jar="${ARTIFACT}-${VERSION}.jar"
  else
    jar="${ARTIFACT}-${VERSION}-${classifier}.jar"
  fi

  echo
  echo "----------------------------------------------------------------------"
  echo "Artifact: ${jar}"

  # 1. Download the published jar from GitHub Packages.
  http_code="$(curl -sSL -u "x:${GITHUB_TOKEN}" -o "${WORKDIR}/${jar}" \
    -w '%{http_code}' "${MAVEN_BASE}/${VERSION}/${jar}")"
  if [[ "${http_code}" != "200" ]]; then
    echo "  SKIP: could not download (HTTP ${http_code})."
    continue
  fi

  digest="$(sha256 "${WORKDIR}/${jar}")"
  echo "  sha256: ${digest}"

  # 2. Fetch the attestation bundle(s) for this digest from GitHub.
  att_json="${WORKDIR}/${jar}.attestations.json"
  att_code="$(curl -sS -o "${att_json}" -w '%{http_code}' \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${API_BASE}/attestations/sha256:${digest}")"

  if [[ "${att_code}" == "404" ]]; then
    echo "  NO ATTESTATION: GitHub has no attestation for this digest yet."
    echo "  (The attest step must have run for the release that produced this jar.)"
    continue
  fi
  if [[ "${att_code}" != "200" ]]; then
    echo "  ERROR: attestations API returned HTTP ${att_code}:"
    cat "${att_json}" >&2
    failed=$((failed + 1))
    continue
  fi

  # Persist one bundle reference per attestation. GitHub returns either an
  # inline "bundle" (typical for smaller repos) or a presigned "bundle_url"
  # (larger repos, where the bundle is stored raw-Snappy-compressed).
  count="$(python3 -c '
import json, sys
src, workdir, jar = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(src))
atts = data.get("attestations") or []
n = 0
for i, att in enumerate(atts):
    base = "%s/%s.bundle.%d" % (workdir, jar, i)
    if att.get("bundle") is not None:
        with open(base + ".sigstore.json", "w") as fh:
            json.dump(att["bundle"], fh)
        n += 1
    elif att.get("bundle_url"):
        with open(base + ".url", "w") as fh:
            fh.write(att["bundle_url"])
        n += 1
print(n)
' "${att_json}" "${WORKDIR}" "${jar}")"

  if [[ "${count}" == "0" ]]; then
    echo "  NO ATTESTATION: response contained no bundles."
    continue
  fi
  attested=$((attested + 1))
  echo "  Found ${count} attestation(s)."

  # 3. Verify each bundle with cosign.
  ok=0
  for ((i = 0; i < count; i++)); do
    base="${jar}.bundle.${i}"
    bundle="${base}.sigstore.json"

    # Materialise bundles that were served via a presigned URL.
    if [[ ! -f "${WORKDIR}/${bundle}" && -f "${WORKDIR}/${base}.url" ]]; then
      curl -fsSL -o "${WORKDIR}/${base}.raw" "$(cat "${WORKDIR}/${base}.url")"
      if ! python3 -c '
import json, sys
raw = bytes(open(sys.argv[1], "rb").read())
obj = None
try:                       # GitHub stores remote bundles raw-Snappy-compressed
    import cramjam
    obj = json.loads(bytes(cramjam.snappy.decompress_raw(raw)))
except Exception:
    pass
if obj is None:
    try:                   # fall back to a plain-JSON body
        obj = json.loads(raw)
    except Exception:
        sys.exit("could not decode bundle (install cramjam to decode Snappy-compressed bundles)")
json.dump(obj, open(sys.argv[2], "w"))
' "${WORKDIR}/${base}.raw" "${WORKDIR}/${bundle}"; then
        echo "  ERROR: failed to decode bundle ${i} from its presigned URL." >&2
        continue
      fi
    fi

    echo "  --> cosign verify-blob-attestation (bundle ${i})"
    if cosign verify-blob-attestation \
      --new-bundle-format \
      --bundle "/work/${bundle}" \
      --certificate-identity-regexp "${CERT_IDENTITY_REGEXP}" \
      --certificate-oidc-issuer "${OIDC_ISSUER}" \
      --type slsaprovenance1 \
      "/work/${jar}"; then
      ok=1
      break
    fi
  done

  if [[ "${ok}" == "1" ]]; then
    echo "  VERIFIED ✓"
    verified=$((verified + 1))
  else
    echo "  VERIFICATION FAILED ✗"
    failed=$((failed + 1))
  fi
done

echo
echo "======================================================================"
echo "Summary: verified=${verified} attested=${attested} failed=${failed}"
if [[ "${failed}" -gt 0 ]]; then
  exit 1
fi
if [[ "${attested}" -eq 0 ]]; then
  echo "No attestations were found for ${ARTIFACT}:${VERSION}. Nothing to verify yet."
  exit 3
fi
echo "All found attestations verified successfully."
