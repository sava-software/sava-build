# Certificate paths of the eMudhra intermediate CA compromise

Twenty-eight PEM paths, target certificate first, through the eMudhra intermediate CAs whose
private keys were recoverable from their certificates (CVE-2026-91403, disclosed 2026-10-20).

- The fourteen `*-emudhra.we-have-a-problem.com.pem` files are the chains, leaf first, that the
  test hosts of that name served on 20 October 2026 (the two `noct` chains on 22 October), as
  published in the reference site's `chains.zip`. The `-aaa-` chains go through the intermediate's
  cross-signature by Sectigo's "AAA Certificate Services", the others through "emSign Root TLS CA -
  G3", itself cross-signed by "emSign ECC Root CA - G3".
- The fourteen `em-*.pem` files are the intermediates themselves as targets, from the reference
  site's `impacted-intermediates.json`: the twelve TLS issuing CAs (six keys, each certified by
  "emSign Root TLS CA - G3" and by "AAA Certificate Services"), and the two code-signing CAs "EM OV
  CS CA - G3B/G3C" as certified by "AAA Certificate Services", which no served chain reaches and
  which PKIX would accept as TLS issuers (it does not check a CA's extended key usage). Their other
  certificates, by "emSign Root CS CA - G3", are left out: that root is in no Java trust store, so
  no Java path reaches them. The emSign-issued files carry the cross-signed "emSign Root TLS CA - G3"
  as their second certificate; the AAA-issued ones are the target alone, since AAA is the anchor.

## The check

`TrustedChains`, run by `check.sh`, validates every prefix of each path (the target alone, then
with each issuer added) against the JDK's default trust anchors with the PKIX validator, the way a
TLS client builds the shortest path to an anchor out of what a server sends; so a store that
anchors "emSign Root TLS CA - G3" itself, as Microsoft's does, fails although the files end at
that root's cross-certificate. A path whose target is itself an anchor, by subject and public key,
is trusted outright: a client takes a certificate matching an anchor for the anchor and never puts
it before the blocklist, PKIX matches anchors to issuers only, and the two code-signing CAs reach no
served chain whose leaf would show it. Validation happens at the midpoint of the target's validity,
so the verdicts do not change when the leaves expire in May 2027 and the AAA-issued intermediates at
the end of 2028.

The check fails, exit 3, when a prefix validates, when a target is an anchor, or when one of the
four roots the intermediates chain to is itself a trust anchor: their issuers "emSign Root TLS CA -
G3" and "emSign Root CS CA - G3", and "emSign ECC Root CA - G3" and "AAA Certificate Services",
which cross-sign those. So a JDK
whose fix is a blocklist alone does not pass: Oracle's CVE-2026-114820 lists 25.0.5.0.2 and
27.0.1.0.2, which keep the roots, as affected. Only two rejections count as a fix, no trust anchor
for the path and a certificate on the JDK's blocklist (`lib/security/blocked.certs`); an expired,
revoked, weak or badly signed path makes the check stop undecided (exit 2) rather than pass, as do
an empty trust store and an unreadable path. Exit 1 is the java launcher's own, a VM that would not
start, and is reported as undecided too. `check.sh` turns a 3 under the report's own closing
verdict line into a failing annotation that names the problem (HotSpot itself exits 3 on
`-XX:+ExitOnOutOfMemoryError`), and anything else non-zero into one that says the check could not decide.

openjdk.org/emudhra's CheckCerts fails on four roots of the Java trust store: "emSign ECC Root CA -
G3" and "AAA Certificate Services", as this check does, and "emSign Root CA - G1" and "- G2",
eMudhra roots unrelated to the compromised intermediates, which Oracle removed in 25.0.5.0.3 and
Mozilla, and so Temurin, keep. This check deviates there on purpose: G1 and G2 are reported when
present and do not fail it.

## Releases

Releases that pass: Oracle JDK 25.0.5.0.3 and OpenJDK 27.0.1.0.3 (roots removed, 2026-10-25), and
the Temurin 25.0.5+8 and 21.0.13+9 archives (roots removed, 2026-10-22). Adoptium's apt packages,
which the hosted Ubuntu runners carry (21.0.12+1 and 25.0.4+1 on image 20261023), read the OS
trust store instead whatever their version (`lib/security/cacerts` is a symlink to
`/etc/ssl/certs/adoptium/cacerts`, rebuilt by an update-ca-certificates hook) and pass only once
that store distrusts "emSign ECC Root CA - G3" and "AAA Certificate Services": Ubuntu's
ca-certificates 20260816ubuntu0.24.04.1 does (AAA had left Mozilla's bundle in 20260601~24.04.1),
actions/runner-images#15053 proposes it for the image (open on 2026-10-26, with #15018 tracking the
incident), and the `distrust-compromised-roots` action does it on an image that has neither.
