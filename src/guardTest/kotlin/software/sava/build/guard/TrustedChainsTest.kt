package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** The path validator the check-jdk-trust action runs on an installed JDK. */
class TrustedChainsTest {

  @TempDir
  lateinit var directory: Path

  /** The paths the action ships: served by the eMudhra test hosts or the intermediates themselves. */
  private val incidentPaths = Path.of(".github/actions/check-jdk-trust/chains")

  private val certificates = CertificateFactory.getInstance("X.509")

  /**
   * A test resource: the synthetic century-long root, intermediate and leaf, the synthetic
   * certificates carrying a shipped CA's subject under another key and its key under another
   * subject, or an exported real root.
   */
  private fun resource(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("trust/$name")) { "missing test resource trust/$name" }.use { it.readBytes() }

  private fun certificatesOf(bytes: ByteArray): List<X509Certificate> =
    certificates.generateCertificates(bytes.inputStream()).map { it as X509Certificate }

  private fun anchor(bytes: ByteArray): TrustAnchor = TrustAnchor(certificatesOf(bytes).single(), null)

  private val syntheticRoot get() = anchor(resource("root.pem"))
  private val aaa get() = certificatesOf(resource("aaa-certificate-services.pem")).single()
  private val emSignEccRootG3 get() = certificatesOf(resource("emsign-ecc-root-ca-g3.pem")).single()

  /** SECOM's "Security Communication RootCA2", a root without a common name, from cacerts. */
  private val cnLessRoot get() = certificatesOf(resource("security-communication-rootca2.pem")).single()

  /** Runs the check over [paths] against [anchors]: its exit status and its report. */
  private fun report(paths: Path, anchors: Set<TrustAnchor>): Run {
    val bytes = ByteArrayOutputStream()
    val code = PrintStream(bytes, true, StandardCharsets.UTF_8).use { TrustedChains.run(paths, anchors, it) }
    return Run(code, bytes.toString(StandardCharsets.UTF_8))
  }

  /** A PKCS12 trust store under [name] holding [root] alone, with the password check.sh's run is given. */
  private fun trustStore(name: String, root: X509Certificate): File {
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, null)
    store.setCertificateEntry("root", root)
    return directory.resolve(name).toFile().also { file ->
      file.outputStream().use { store.store(it, "changeit".toCharArray()) }
    }
  }

  private fun copyChain(name: String = "leaf-and-intermediate.pem"): Path =
    directory.resolve(name).also { Files.write(it, resource("chain.pem")) }

  /**
   * chain.pem with the last byte of the leaf's signature flipped: it still parses but no longer
   * verifies. Written as the two certificates' DER back to back, which the factory reads like PEM.
   */
  private fun writeDamagedChain(name: String = "damaged.pem"): Path {
    val chain = certificatesOf(resource("chain.pem"))
    val leaf = chain[0].encoded
    leaf[leaf.size - 1] = (leaf[leaf.size - 1].toInt() xor 0x01).toByte()
    return directory.resolve(name).also { Files.write(it, leaf + chain[1].encoded) }
  }

  @Test
  fun `a path validates against the root that issued it, at a date inside the target's validity`() {
    copyChain()
    val verdicts = TrustedChains.evaluate(directory, setOf(syntheticRoot))
    assertEquals(1, verdicts.size)
    assertEquals(TrustedChains.Outcome.TRUSTED, verdicts[0].outcome(), verdicts[0].toString())
    assertEquals("CN=leaf.sava-build.test,O=sava-build tests", verdicts[0].target())
  }

  @Test
  fun `a path is rejected against an anchor that did not issue it, and cannot be judged without anchors`() {
    copyChain()
    assertThrows(IllegalArgumentException::class.java) { TrustedChains.evaluate(directory, emptySet()) }
    val wrongRoot = TrustedChains.evaluate(directory, setOf(TrustAnchor(emSignEccRootG3, null)))
    assertEquals(TrustedChains.Outcome.REJECTED, wrongRoot[0].outcome(), wrongRoot[0].toString())
    assertTrue(wrongRoot[0].detail().startsWith("NO_TRUST_ANCHOR: "), wrongRoot[0].detail())
  }

  @Test
  fun `a path that fails for another reason is inconclusive, not a pass`() {
    writeDamagedChain()
    val verdicts = TrustedChains.evaluate(directory, setOf(syntheticRoot))
    assertEquals(TrustedChains.Outcome.INCONCLUSIVE, verdicts[0].outcome(), verdicts[0].toString())
    assertTrue(verdicts[0].detail().startsWith("INVALID_SIGNATURE: "), verdicts[0].detail())
  }

  @Test
  fun `a path validates through a prefix when a certificate it carries is issued by the anchor`() {
    // The intermediate as the anchor: the whole file ends above it (no trust anchor), the leaf alone
    // chains to it, as a TLS client would build the path.
    copyChain()
    val intermediate = certificatesOf(resource("chain.pem"))[1]
    val verdicts = TrustedChains.evaluate(directory, setOf(TrustAnchor(intermediate, null)))
    assertEquals(TrustedChains.Outcome.TRUSTED, verdicts[0].outcome(), verdicts[0].toString())
    assertTrue(verdicts[0].detail().endsWith("as its first 1 of 2 certificates"), verdicts[0].detail())
  }

  @Test
  fun `the emSign-issued paths validate against the root that issued their intermediates, which the files end below`() {
    // The served chains end at the cross-certificate of "emSign Root TLS CA - G3" by the ECC root; a
    // store anchoring that root itself (Microsoft's does) must still fail.
    val tlsRootG3 = certificatesOf(Files.readAllBytes(incidentPaths.resolve("dv-g3b-emudhra.we-have-a-problem.com.pem"))).last()
    assertEquals("emSign Root TLS CA - G3", TrustedChains.commonName(tlsRootG3.subjectX500Principal))
    assertEquals("emSign ECC Root CA - G3", TrustedChains.commonName(tlsRootG3.issuerX500Principal))
    val anchors = setOf(TrustAnchor(tlsRootG3, null))
    assertEquals(listOf(tlsRootG3.subjectX500Principal.name), TrustedChains.anchorsNamed(anchors, TrustedChains.RELATED_ROOTS))
    val (emSign, aaa) = TrustedChains.evaluate(incidentPaths, anchors).partition { !it.file().fileName.toString().contains("aaa") }
    assertEquals(13, emSign.size, emSign.toString())
    assertTrue(
      emSign.all { it.outcome() == TrustedChains.Outcome.TRUSTED || it.detail().startsWith("UNSPECIFIED: Untrusted certificate: ") },
      emSign.filter { it.outcome() != TrustedChains.Outcome.TRUSTED }.toString()
    )
    assertTrue(aaa.all { it.outcome() == TrustedChains.Outcome.REJECTED && it.detail().startsWith("NO_TRUST_ANCHOR: ") }, aaa.toString())
  }

  @Test
  fun `a target that is itself a trust anchor is trusted, whatever the blocklist says`() {
    // PKIX matches anchors to issuers, so a store anchoring one of the code-signing CAs, which no
    // served chain reaches, shows in no prefix; a client would take the match for the anchor.
    val csCa = certificatesOf(Files.readAllBytes(incidentPaths.resolve("em-ov-cs-ca-g3b-aaa-certificate-services.pem"))).single()
    assertEquals("EM OV CS CA - G3B", TrustedChains.commonName(csCa.subjectX500Principal))
    val anchors = setOf(TrustAnchor(csCa, null))
    assertEquals(emptyList<String>(), TrustedChains.anchorsNamed(anchors, TrustedChains.RELATED_ROOTS))
    val (own, others) = TrustedChains.evaluate(incidentPaths, anchors)
      .partition { it.file().fileName.toString() == "em-ov-cs-ca-g3b-aaa-certificate-services.pem" }
    assertEquals(TrustedChains.Outcome.TRUSTED, own.single().outcome(), own.toString())
    assertEquals("the target is itself a trust anchor, by subject and public key", own.single().detail())
    assertEquals(27, others.size)
    assertTrue(others.all { it.outcome() == TrustedChains.Outcome.REJECTED }, others.filter { it.outcome() != TrustedChains.Outcome.REJECTED }.toString())
    assertEquals(TrustedChains.TRUSTING, report(incidentPaths, anchors).code)
    // The match is by subject and key, each on its own: the anchor's subject under another key is
    // not it, nor is its key under another subject, nor is another CA.
    val otherKey = certificatesOf(resource("em-ov-cs-ca-g3b-other-key.pem")).single()
    assertEquals(csCa.subjectX500Principal, otherKey.subjectX500Principal)
    val otherSubject = certificatesOf(resource("em-ov-cs-ca-g3b-key-other-subject.pem")).single()
    assertNotEquals(csCa.subjectX500Principal, otherSubject.subjectX500Principal)
    assertArrayEquals(csCa.publicKey.encoded, otherSubject.publicKey.encoded)
    assertTrue(TrustedChains.isAnchor(csCa, anchors))
    assertFalse(TrustedChains.isAnchor(otherKey, anchors))
    assertFalse(TrustedChains.isAnchor(otherSubject, anchors))
    assertFalse(TrustedChains.isAnchor(aaa, anchors))
  }

  @Test
  fun `run is trusting on a validating path or a related root, undecided on an inconclusive one or none, clean otherwise`() {
    var out = ""
    fun run(anchors: Set<TrustAnchor>): Int {
      val (status, report) = report(directory, anchors)
      out = report
      return status
    }
    copyChain()
    assertEquals(TrustedChains.TRUSTING, run(setOf(syntheticRoot)))
    // Every path rejected, but a related root is an anchor.
    assertEquals(TrustedChains.TRUSTING, run(setOf(TrustAnchor(emSignEccRootG3, null))))
    assertTrue(out.contains("BAD ROOT: CN=emSign ECC Root CA - G3,"), out)
    val unrelated = TrustedChains.defaultAnchors().first {
      val commonName = TrustedChains.commonName(it.trustedCert.subjectX500Principal)
      commonName != null && commonName !in TrustedChains.RELATED_ROOTS && commonName !in TrustedChains.OTHER_EMUDHRA_ROOTS
    }
    assertEquals(TrustedChains.CLEAN, run(setOf(unrelated)))
    assertTrue(out.trimEnd().endsWith("All 1 paths rejected; no related root among the trust anchors."), out)
    assertTrue(!out.contains("BAD ROOT"), out)
    assertEquals(TrustedChains.UNDECIDED, run(emptySet()))
    // A validating path outranks an inconclusive one beside it; alone, the inconclusive path undecides.
    writeDamagedChain()
    assertEquals(TrustedChains.TRUSTING, run(setOf(syntheticRoot)))
    Files.delete(directory.resolve("leaf-and-intermediate.pem"))
    assertEquals(TrustedChains.UNDECIDED, run(setOf(syntheticRoot)))
    assertEquals(TrustedChains.CLEAN, run(setOf(unrelated)), "no anchor for the damaged path: rejected, not inconclusive")
    // No paths at all is an error, undecided; a related root still decides.
    Files.delete(directory.resolve("damaged.pem"))
    assertEquals(TrustedChains.UNDECIDED, run(setOf(syntheticRoot)))
    assertEquals(TrustedChains.TRUSTING, run(setOf(TrustAnchor(aaa, null))))
  }

  @Test
  fun `the shipped paths are intact, chain by signature to the roots their names say, and are rejected elsewhere`() {
    val files = Files.list(incidentPaths).use { listing -> listing.filter { it.toString().endsWith(".pem") }.sorted().toList() }
    assertEquals(28, files.size, files.toString())
    val roots = mapOf("AAA Certificate Services" to aaa, "emSign ECC Root CA - G3" to emSignEccRootG3)
    for (file in files) {
      val path = certificatesOf(Files.readAllBytes(file))
      val target = path.first()
      val name = file.fileName.toString().removeSuffix(".pem")
      // Served chains are named after their host, intermediates after themselves and their issuer.
      val expectedName = if (target.basicConstraints == -1) TrustedChains.commonName(target.subjectX500Principal)
      else slug(TrustedChains.commonName(target.subjectX500Principal)!!) + "-" + slug(TrustedChains.commonName(target.issuerX500Principal)!!)
      assertEquals(expectedName, name)
      // Every certificate is signed by the next one; the last by the real root its path reaches.
      path.zipWithNext().forEach { (certificate, issuer) ->
        assertEquals(certificate.issuerX500Principal, issuer.subjectX500Principal, "$name: order")
        certificate.verify(issuer.publicKey)
      }
      val last = path.last()
      val root = roots.entries.single { TrustedChains.commonName(last.issuerX500Principal) == it.key }
      last.verify(root.value.publicKey)
      assertEquals(root.key.startsWith("AAA"), name.contains("aaa"), "$name reaches ${root.key}")
    }
    val verdicts = TrustedChains.evaluate(incidentPaths, setOf(syntheticRoot))
    assertEquals(28, verdicts.size)
    assertTrue(verdicts.all { it.outcome() == TrustedChains.Outcome.REJECTED }, verdicts.filter { it.outcome() != TrustedChains.Outcome.REJECTED }.toString())
    // Against the roots they really chain to, PKIX accepts every path up to the blocklist: trusted
    // on an unpatched JVM, "Untrusted certificate" on one whose blocked.certs names the intermediates,
    // never an anchor or an inconclusive failure.
    val trusting = TrustedChains.evaluate(incidentPaths, roots.values.map { TrustAnchor(it, null) }.toSet())
    assertTrue(
      trusting.all { it.outcome() == TrustedChains.Outcome.TRUSTED || it.detail().startsWith("UNSPECIFIED: Untrusted certificate: ") },
      trusting.filter { it.outcome() != TrustedChains.Outcome.TRUSTED }.toString()
    )
  }

  @Test
  fun `the related roots are recognised among anchors by common name`() {
    // A root without a common name (Oracle's store carries four) is searched past, not tripped over:
    // first in the set, so a search that stopped at it would find neither related root.
    assertNull(TrustedChains.commonName(cnLessRoot.subjectX500Principal))
    val anchors = setOf(TrustAnchor(cnLessRoot, null), TrustAnchor(aaa, null), TrustAnchor(emSignEccRootG3, null), syntheticRoot)
    val related = TrustedChains.anchorsNamed(anchors, TrustedChains.RELATED_ROOTS)
    assertEquals(2, related.size, related.toString())
    assertEquals(4, TrustedChains.RELATED_ROOTS.size, "the two issuers of the intermediates and the two roots that cross-sign them")
    assertTrue(related.any { it.startsWith("CN=AAA Certificate Services,") }, related.toString())
    assertTrue(related.any { it.startsWith("CN=emSign ECC Root CA - G3,") }, related.toString())
    assertEquals(emptyList<String>(), TrustedChains.anchorsNamed(setOf(syntheticRoot), TrustedChains.RELATED_ROOTS))
    assertEquals(emptyList<String>(), TrustedChains.anchorsNamed(anchors, TrustedChains.OTHER_EMUDHRA_ROOTS))
    assertEquals("a\\, b", TrustedChains.commonName(X500Principal("CN=a\\, b,O=c")))
    assertNull(TrustedChains.commonName(X500Principal("O=no common name")))
  }

  @Test
  fun `a directory without paths is an error, not a pass`() {
    assertThrows(IllegalArgumentException::class.java) { TrustedChains.evaluate(directory, setOf(syntheticRoot)) }
    File(directory.toFile(), "notes.txt").writeText("not a chain\n")
    assertThrows(IllegalArgumentException::class.java) { TrustedChains.evaluate(directory, setOf(syntheticRoot)) }
  }

  @Test
  fun `the runtime's default anchors are read and searched`() {
    val anchors = TrustedChains.defaultAnchors()
    assertTrue(anchors.size > 50)
    TrustedChains.anchorsNamed(anchors, TrustedChains.RELATED_ROOTS)
  }

  @Test
  fun `check-sh exits 1 on a JDK that trusts a related root, 0 on a clean one and 2 without a verdict`() {
    // The script runs as the actions run it, on this JVM's JDK with the trust store pointed at one
    // the test writes: its exit plumbing (pipefail, the status read under the closing verdict line it
    // re-spells) is what could fail open, and no reading of the script sees that. A related root
    // alone is trusting whatever the JDK's blocklist says of the paths.
    assumeFalse(System.getProperty("os.name").startsWith("Windows"), "check.sh runs under bash")
    val home = File(System.getProperty("java.home"))
    assumeTrue(File(home, "bin/javac").canExecute(), "the test JVM is not a JDK")
    val script = File(".github/actions/check-jdk-trust/check.sh").absoluteFile
    fun check(home: File, store: File? = null): Run {
      val builder = ProcessBuilder("bash", script.path, home.path).directory(directory.toFile()).redirectErrorStream(true)
      builder.environment()["RUNNER_TEMP"] = directory.toString()
      builder.environment().remove("JAVA_TOOL_OPTIONS")
      if (store != null) {
        // Read by javac too, which only says so; the quotes survive the launcher's whitespace split.
        builder.environment()["JAVA_TOOL_OPTIONS"] =
          "-Djavax.net.ssl.trustStore=\"$store\" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
      }
      val process = builder.start()
      val out = process.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
      return Run(process.waitFor(), out)
    }
    val trusting = check(home, trustStore("trusting.p12", aaa))
    assertEquals(1, trusting.code, trusting.out)
    assertTrue(trusting.out.contains("BAD ROOT: CN=AAA Certificate Services,"), trusting.out)
    assertTrue(trusting.out.contains("::error::The JDK at $home still trusts a compromised eMudhra intermediate CA (CVE-2026-91403)"), trusting.out)
    val clean = check(home, trustStore("clean.p12", syntheticRoot.trustedCert))
    assertEquals(0, clean.code, clean.out)
    assertTrue(clean.out.contains("All 28 paths rejected; no related root among the trust anchors."), clean.out)
    // HotSpot itself exits 3 on -XX:+ExitOnOutOfMemoryError: a 3 without the verdict line is undecided.
    val stub = directory.resolve("stub-jdk").toFile()
    File(stub, "bin").mkdirs()
    File(stub, "bin/javac").also { it.writeText("#!/bin/sh\nexit 0\n") }.setExecutable(true)
    File(stub, "bin/java").also { it.writeText("#!/bin/sh\necho 'Terminating due to java.lang.OutOfMemoryError'\nexit 3\n") }.setExecutable(true)
    val undecided = check(stub)
    assertEquals(2, undecided.code, undecided.out)
    assertTrue(undecided.out.contains("::error::check-jdk-trust could not decide about the JDK at $stub (exit 3)"), undecided.out)
  }

  private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
