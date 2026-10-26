package software.sava.build.guard;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertPathValidatorException.BasicReason;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXReason;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * Checks a JDK's trust store against the eMudhra intermediate CA compromise. For every PEM path in
 * a directory (target certificate first, its issuers after) it validates each prefix of the path
 * against the JDK's default trust anchors, as a TLS client builds the shortest path to an anchor
 * out of what a server sends, and it looks among those anchors for the targets themselves and for
 * the roots the compromised intermediates chain to. The paths all run through compromised issuers,
 * so a path that validates means the store still honours such an issuer. Validation happens at the
 * midpoint of the target's validity, so an expired target does not turn into a pass, and only two
 * rejections count as a fix: no trust anchor for the path, and a certificate on the JDK's
 * blocklist. Any other failure is inconclusive, since it proves nothing about the issuer.
 *
 * <p>As a program: one argument, the directory. Exit 0 when every path is rejected and no related
 * root is an anchor; 3 when a path validates, a target is an anchor or a related root is; 2 when
 * the check could not decide (misuse, no anchors, an unreadable file, an inconclusive rejection). Exit 1 is
 * never a verdict: the java launcher exits 1 on its own when the VM cannot start or main dies, and
 * check.sh must not read that as a trusting JDK.
 */
public final class TrustedChains {

  /** Every path rejected and no related root among the anchors. */
  public static final int CLEAN = 0;

  /** The check could not decide; never a pass. */
  public static final int UNDECIDED = 2;

  /** A path validates, or a related root is a trust anchor. */
  public static final int TRUSTING = 3;

  /** What validating a path against the anchors showed. */
  public enum Outcome { TRUSTED, REJECTED, INCONCLUSIVE }

  /** One path's result: the file, its target's subject, the outcome and the validator's words. */
  public record Verdict(Path file, String target, Outcome outcome, String detail) {}

  /**
   * The roots the compromised intermediates chain to: their issuers, and the roots that cross-sign
   * those. A store anchoring any of them trusts the intermediates unless a blocklist steps in.
   * openjdk.org/emudhra's CheckCerts fails on the last two, the ones Java trust stores carried.
   */
  static final List<String> RELATED_ROOTS = List.of(
      "emSign Root TLS CA - G3", "emSign Root CS CA - G3", "emSign ECC Root CA - G3", "AAA Certificate Services");

  /**
   * eMudhra's other roots, unrelated to the compromised intermediates: CheckCerts fails on them
   * too and Oracle removed them in 25.0.5.0.3, Mozilla and so Temurin keep G1. Reported, not failed.
   */
  static final List<String> OTHER_EMUDHRA_ROOTS = List.of("emSign Root CA - G1", "emSign Root CA - G2");

  private TrustedChains() {}

  public static void main(String... args) {
    if (args.length != 1) {
      System.err.println("Usage: TrustedChains <directory of PEM paths, target first>");
      System.exit(UNDECIDED);
    }
    int status;
    try {
      status = run(Path.of(args[0]), defaultAnchors(), System.out);
    } catch (GeneralSecurityException | RuntimeException e) {
      System.out.println("The check could not run:");
      e.printStackTrace(System.out);
      status = UNDECIDED;
    }
    System.exit(status);
  }

  /**
   * Runs the check over the paths in {@code directory} against {@code anchors}, printing to
   * {@code out}, and returns the exit status: a related root among the anchors or a validating
   * path is TRUSTING whatever else happened, then any inconclusive path or failure is UNDECIDED.
   */
  static int run(Path directory, Set<TrustAnchor> anchors, PrintStream out) {
    out.printf("Java %s (%s) at %s, %d trust anchors%n",
        System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
        System.getProperty("java.home"), anchors.size());
    if (anchors.isEmpty()) {
      out.println("The default trust store holds no trust anchors: it is empty, missing or unreadable.");
      return UNDECIDED;
    }
    List<String> related = anchorsNamed(anchors, RELATED_ROOTS);
    List<Verdict> verdicts = null;
    try {
      verdicts = evaluate(directory, anchors);
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      out.println("The paths could not be validated:");
      e.printStackTrace(out);
    }
    long trusted = 0;
    long inconclusive = 0;
    if (verdicts != null) {
      for (Verdict verdict : verdicts) {
        out.printf("%-12s %s: %s%n", verdict.outcome(), verdict.file().getFileName(), verdict.detail());
        if (verdict.outcome() == Outcome.TRUSTED) {
          trusted++;
        } else if (verdict.outcome() == Outcome.INCONCLUSIVE) {
          inconclusive++;
        }
      }
    }
    for (String root : related) {
      out.printf("BAD ROOT: %s%n", root);
    }
    for (String root : anchorsNamed(anchors, OTHER_EMUDHRA_ROOTS)) {
      out.printf("note: %s is a trust anchor (an eMudhra root unrelated to the compromised intermediates;"
          + " chains/README.md says why it is reported, not failed)%n", root);
    }
    if (trusted > 0 || !related.isEmpty()) {
      out.printf("%d path(s) validate and %d related root(s) are trust anchors: this runtime still trusts a compromised issuer.%n",
          trusted, related.size());
      return TRUSTING;
    }
    if (verdicts == null) {
      return UNDECIDED;
    }
    if (inconclusive > 0) {
      out.printf("%d of %d paths failed for a reason that says nothing about the issuer; the check cannot decide.%n",
          inconclusive, verdicts.size());
      return UNDECIDED;
    }
    out.printf("All %d paths rejected; no related root among the trust anchors.%n", verdicts.size());
    return CLEAN;
  }

  /** The accepted issuers of the runtime's default trust manager: cacerts, or javax.net.ssl.trustStore. */
  static Set<TrustAnchor> defaultAnchors() throws GeneralSecurityException {
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
    Set<TrustAnchor> anchors = new LinkedHashSet<>();
    for (var manager : factory.getTrustManagers()) {
      if (manager instanceof X509TrustManager x509) {
        for (X509Certificate issuer : x509.getAcceptedIssuers()) {
          anchors.add(new TrustAnchor(issuer, null));
        }
      }
    }
    return anchors;
  }

  /** The subjects of the anchors whose common name is one of {@code commonNames}. */
  static List<String> anchorsNamed(Set<TrustAnchor> anchors, List<String> commonNames) {
    List<String> found = new ArrayList<>();
    for (TrustAnchor anchor : anchors) {
      X500Principal subject = anchor.getTrustedCert().getSubjectX500Principal();
      String commonName = commonName(subject);
      if (commonName != null && commonNames.contains(commonName)) {
        found.add(subject.getName());
      }
    }
    return found;
  }

  /** The CN attribute of a name, or null; the names this check looks for carry no escaped commas. */
  static String commonName(X500Principal principal) {
    for (String rdn : principal.getName(X500Principal.RFC2253).split("(?<!\\\\),")) {
      if (rdn.startsWith("CN=")) {
        return rdn.substring(3);
      }
    }
    return null;
  }

  /**
   * Validates each {@code .pem} file of the directory against the anchors. An empty directory, an
   * unreadable file or an empty anchor set is an error, never a pass.
   */
  static List<Verdict> evaluate(Path directory, Set<TrustAnchor> anchors) throws IOException, GeneralSecurityException {
    if (anchors.isEmpty()) {
      throw new IllegalArgumentException("No trust anchors to validate against");
    }
    List<Path> files;
    try (Stream<Path> listing = Files.list(directory)) {
      files = listing.filter(path -> path.getFileName().toString().endsWith(".pem")).sorted().toList();
    }
    if (files.isEmpty()) {
      throw new IllegalArgumentException("No .pem files in " + directory);
    }
    CertificateFactory certificates = CertificateFactory.getInstance("X.509");
    CertPathValidator validator = CertPathValidator.getInstance("PKIX");
    List<Verdict> verdicts = new ArrayList<>();
    for (Path file : files) {
      List<X509Certificate> path;
      try (InputStream in = Files.newInputStream(file)) {
        path = certificates.generateCertificates(in).stream().map(X509Certificate.class::cast).toList();
      }
      if (path.isEmpty()) {
        throw new IllegalArgumentException(file + " holds no certificate");
      }
      PKIXParameters parameters = new PKIXParameters(anchors);
      parameters.setRevocationEnabled(false);
      parameters.setDate(midpoint(path.get(0)));
      verdicts.add(verdict(file, path, parameters, certificates, validator));
    }
    return verdicts;
  }

  /**
   * A TLS client builds the shortest path from the target to an anchor out of the certificates a
   * server sends, so a path validates when any prefix of it does: the target alone when its issuer
   * is an anchor, the whole file when only the root at its end is. TRUSTED when the target is itself
   * an anchor, by subject and public key (a client takes a certificate matching an anchor for the
   * anchor and never puts it before the blocklist, and PKIX matches anchors to issuers only, so the
   * two code-signing CAs, which no served chain reaches, show up in no other way), or when a prefix
   * validates; else INCONCLUSIVE when one fails for a reason that proves nothing; else REJECTED, with
   * the blocklist's words when a prefix reached an anchor and was blocked there, else the whole path's.
   */
  private static Verdict verdict(Path file, List<X509Certificate> path, PKIXParameters parameters,
                                 CertificateFactory certificates, CertPathValidator validator) throws GeneralSecurityException {
    String target = path.get(0).getSubjectX500Principal().getName();
    if (isAnchor(path.get(0), parameters.getTrustAnchors())) {
      return new Verdict(file, target, Outcome.TRUSTED, "the target is itself a trust anchor, by subject and public key");
    }
    String inconclusive = null;
    String blocked = null;
    String unanchored = null;
    for (int length = path.size(); length >= 1; length--) {
      try {
        validator.validate(certificates.generateCertPath(path.subList(0, length)), parameters);
        String detail = length == path.size()
            ? "validates against the trust anchors"
            : "validates against the trust anchors as its first " + length + " of " + path.size() + " certificates";
        return new Verdict(file, target, Outcome.TRUSTED, detail);
      } catch (CertPathValidatorException e) {
        String detail = e.getReason() + ": " + e.getMessage();
        if (classify(e) == Outcome.INCONCLUSIVE) {
          inconclusive = inconclusive == null ? detail : inconclusive;
        } else if (e.getReason() == PKIXReason.NO_TRUST_ANCHOR) {
          unanchored = unanchored == null ? detail : unanchored;
        } else {
          blocked = blocked == null ? detail : blocked;
        }
      }
    }
    if (inconclusive != null) {
      return new Verdict(file, target, Outcome.INCONCLUSIVE, inconclusive);
    }
    return new Verdict(file, target, Outcome.REJECTED, blocked != null ? blocked : unanchored);
  }

  /** Whether an anchor carries the certificate's subject and public key, as PKIXValidator matches them. */
  static boolean isAnchor(X509Certificate certificate, Set<TrustAnchor> anchors) {
    for (TrustAnchor anchor : anchors) {
      X509Certificate trusted = anchor.getTrustedCert();
      if (trusted != null && trusted.getSubjectX500Principal().equals(certificate.getSubjectX500Principal())
          && Arrays.equals(trusted.getPublicKey().getEncoded(), certificate.getPublicKey().getEncoded())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Only two rejections show the issuer is distrusted: no anchor for the path (the root is gone)
   * and a blocked certificate (the JDK's lib/security/blocked.certs, reported as an unspecified
   * "Untrusted certificate"). An expired, revoked, weak or badly signed path proves nothing.
   */
  static Outcome classify(CertPathValidatorException e) {
    if (e.getReason() == PKIXReason.NO_TRUST_ANCHOR) {
      return Outcome.REJECTED;
    }
    if (e.getReason() == BasicReason.UNSPECIFIED && String.valueOf(e.getMessage()).startsWith("Untrusted certificate")) {
      return Outcome.REJECTED;
    }
    return Outcome.INCONCLUSIVE;
  }

  private static Date midpoint(X509Certificate certificate) {
    long from = certificate.getNotBefore().getTime();
    long to = certificate.getNotAfter().getTime();
    return new Date(from + (to - from) / 2);
  }
}
