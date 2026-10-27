#!/usr/bin/env bash
# Disables the roots the compromised eMudhra intermediates chain to, "emSign ECC Root CA - G3" and
# "AAA Certificate Services", in a Debian-family OS trust store and regenerates the derived stores,
# the way Ubuntu's ca-certificates 20260816ubuntu0.24.04.1 does (AAA had already left Mozilla's
# bundle in 20260601~24.04.1) and actions/runner-images#15053 proposes for the hosted image. Shared
# by the distrust-compromised-roots action (the jmods-less workflow uses it), setup-checked-jdk and
# sava-build's root action. Exits 0 without changing anything where there is no passwordless sudo
# or no OS bundle; a check of the JDK that downloads (check-jdk-trust) is what proves the store
# clean.
set -euo pipefail
root_cns=("emSign ECC Root CA - G3" "AAA Certificate Services")
conf=/etc/ca-certificates.conf
bundle=/usr/share/ca-certificates/mozilla
if ! command -v sudo >/dev/null 2>&1 || ! sudo -n true 2>/dev/null; then
  echo "::notice::distrust-compromised-roots: no passwordless sudo on this runner; the OS trust store was left as it is."
  exit 0
fi
if [ ! -f "$conf" ] || [ ! -d "$bundle" ]; then
  echo "::notice::distrust-compromised-roots: no Debian-style trust store ($conf, $bundle); nothing to change."
  exit 0
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "::error::distrust-compromised-roots: openssl is needed to read the OS bundle's subjects and is not installed."
  exit 1
fi
changed=0
shopt -s nullglob
# The bundle names each file after Mozilla's label for the certificate, not its subject
# (Comodo_AAA_Services_root.crt holds "AAA Certificate Services"); the common name decides.
for crt in "$bundle"/*.crt; do
  if ! subject=$(openssl x509 -in "$crt" -noout -subject -nameopt multiline 2>/dev/null); then
    echo "::warning::distrust-compromised-roots: could not read $crt; skipped."
    continue
  fi
  cn=$(printf '%s\n' "$subject" | awk -F'= *' '/commonName/ {print $2}')
  for root_cn in "${root_cns[@]}"; do
    if [ "$cn" = "$root_cn" ]; then
      rel="mozilla/$(basename "$crt")"
      if grep -qxF "$rel" "$conf"; then
        sudo -n sed -i "s|^${rel}\$|!${rel}|" "$conf"
        echo "Distrusted $rel ($cn) in $conf."
        changed=1
      fi
    fi
  done
done
if [ "$changed" = 1 ]; then
  sudo -n update-ca-certificates
else
  echo "The OS trust store trusts neither \"${root_cns[0]}\" nor \"${root_cns[1]}\"; nothing to change."
fi
