import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.verification.KeyStatus
import software.sava.build.verification.OpenPgpKeys
import software.sava.build.verification.VerificationPolicyRules
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The key-status reader and its classification over fixtures: three keys made with GnuPG on
 * 2026-10-31 (one current, one revoked with a reason, one generated as of 2020-01-01 with a
 * one-year expiry) and two copies real key servers served that day (the dev.cel signing key,
 * whose self-certification expired on 2025-01-19 and which signed cel 0.13.0 regardless, and
 * the sava key with its encryption subkey). Nothing here reaches the network: the task's HTTP
 * path is KeyStatusFunctionalTest's, against a server in the test JVM.
 */
class KeyStatusTest {

  private fun fixture(name: String): String =
    KeyStatusTest::class.java.getResource("/verification/keys/$name.asc")!!.readText()

  /** INDEX.txt: `<fingerprint> <fixture>` per line, written when the fixtures were made. */
  private val index: Map<String, String> = KeyStatusTest::class.java.getResource("/verification/keys/INDEX.txt")!!.readText()
    .lines().filter { it.isNotBlank() }.associate { line -> line.split(" ")[1] to line.split(" ")[0] }

  private val now: Instant = ZonedDateTime.parse("2026-10-31T18:00:00Z").toInstant()

  private fun key(name: String): OpenPgpKeys.PublicKey {
    val keys = OpenPgpKeys.parse(fixture(name))
    assertEquals(1, keys.size, name)
    assertEquals(index.getValue(name), keys[0].fingerprint, "$name fingerprint")
    return keys[0]
  }

  @Test
  fun `a current key has neither revocation nor expiry`() {
    val k = key("current")
    assertNull(k.revocation)
    assertNull(k.expiresAt)
    assertEquals(listOf("Current Fixture <current@fixture.invalid>"), k.userIds)
  }

  @Test
  fun `a revocation certificate is read with its date and reason`() {
    val k = key("revoked")
    assertNotNull(k.revocation)
    assertEquals("key status fixture", k.revocation!!.text)
    assertNotNull(k.revocation!!.code)
    assertTrue(k.revocation!!.reason.endsWith(": key status fixture"), k.revocation!!.reason)
    assertTrue(k.revocation!!.at.isAfter(ZonedDateTime.parse("2026-10-31T00:00:00Z").toInstant()), k.revocation!!.at.toString())
    assertTrue(k.revocation!!.at.isBefore(now), k.revocation!!.at.toString())
  }

  @Test
  fun `an expiry is the creation time plus the self-certification's validity`() {
    // generated as of 2020-01-01 with GnuPG's "1y", which is 365 days, so the leap year ends it on 2020-12-31
    assertEquals(ZonedDateTime.parse("2020-12-31T00:00:00Z").toInstant(), key("expired-generated").expiresAt)
    val cel = key("expired-cel")
    assertEquals("2025-01-19", cel.expiresAt!!.toString().substring(0, 10), "dev.cel key expired 2025-01-19")
    assertNull(cel.revocation)
  }

  @Test
  fun `a server copy of the sava key carries its encryption subkey as a fingerprint of its own`() {
    val k = key("sava-hkp")
    assertNull(k.revocation)
    assertEquals(ZonedDateTime.parse("2028-06-12T01:34:06Z").toInstant(), k.expiresAt, "as gpg shows the fixture's primary")
    assertTrue(k.selfSigned)
    // as gpg --with-subkey-fingerprints prints the fixture: one cv25519 subkey, usage e
    assertEquals(listOf("55F4CCE5D8CC9AAD37C80FFEB76A0DCD39F4A836"), k.subkeys.map { it.fingerprint })
    assertFalse(k.subkeys.single().canSign)
    assertEquals(listOf("01870AD9C9DFBB1F3502D06FB89447F3AD5E2ABF", "55F4CCE5D8CC9AAD37C80FFEB76A0DCD39F4A836"), k.fingerprints)
  }

  @Test
  fun `classification fails only a revoked key trusted for every version`() {
    val revoked = key("revoked")
    val expired = key("expired-cel")
    val current = key("current")
    val everyVersion = KeyStatus.TrustedKey(revoked.fingerprint, true, listOf("group=\"org.example\""))
    val exactVersion = KeyStatus.TrustedKey(revoked.fingerprint, false, listOf("group=\"org.example\" version=\"1.0\""))
    val revokedFree = KeyStatus.classify(everyVersion, listOf("s" to revoked), emptyList(), now)
    val revokedListed = KeyStatus.classify(exactVersion, listOf("s" to revoked), emptyList(), now)
    val expiredFree = KeyStatus.classify(KeyStatus.TrustedKey(expired.fingerprint, true, listOf("group=\"dev.cel\"")), listOf("s" to expired), emptyList(), now)
    val currentFree = KeyStatus.classify(KeyStatus.TrustedKey(current.fingerprint, true, listOf("group=\"x\"")), listOf("a" to current, "b" to current), emptyList(), now)
    val absent = KeyStatus.classify(KeyStatus.TrustedKey("0".repeat(40), true, listOf("group=\"x\"")), emptyList(), emptyList(), now)

    assertEquals(KeyStatus.State.REVOKED, revokedFree.state)
    assertTrue(revokedFree.fails)
    assertFalse(revokedFree.warns)
    assertTrue(revokedFree.reason!!.endsWith(": key status fixture"), revokedFree.reason)
    assertEquals(KeyStatus.State.REVOKED, revokedListed.state)
    assertFalse(revokedListed.fails, "a revoked key already at exact versions is the README's answer, not a failure")
    assertTrue(revokedListed.warns)
    assertEquals(KeyStatus.State.EXPIRED, expiredFree.state)
    assertFalse(expiredFree.fails)
    assertTrue(expiredFree.warns)
    assertEquals(KeyStatus.State.CURRENT, currentFree.state)
    assertFalse(currentFree.fails)
    assertFalse(currentFree.warns)
    assertEquals(listOf("a", "b"), currentFree.servers)
    assertEquals("Current Fixture <current@fixture.invalid>", currentFree.holder)
    assertEquals(KeyStatus.State.ABSENT, absent.state)
    assertTrue(absent.warns)
    assertFalse(absent.fails)
    assertEquals("no key server holds this key", absent.reason)
  }

  @Test
  fun `a server that does not answer leaves the key undecided, which fails, while a revocation seen anywhere still decides`() {
    val current = key("current")
    val revoked = key("revoked")
    val everyVersion = KeyStatus.TrustedKey(current.fingerprint, true, listOf("group=\"x\""))
    val noCopy = KeyStatus.classify(everyVersion, emptyList(), listOf("https://a: connect timed out"), now)
    assertEquals(KeyStatus.State.UNDECIDED, noCopy.state)
    assertTrue(noCopy.fails)
    assertFalse(noCopy.warns)
    assertEquals("a server did not answer, so the status is undecided: https://a: connect timed out", noCopy.reason)
    val oneCopy = KeyStatus.classify(everyVersion, listOf("b" to current), listOf("https://a: https://a/pks/lookup answered 503"), now)
    assertEquals(KeyStatus.State.UNDECIDED, oneCopy.state)
    assertTrue(oneCopy.fails)
    assertEquals(listOf("b"), oneCopy.servers)
    val revokedCopy = KeyStatus.classify(KeyStatus.TrustedKey(revoked.fingerprint, true, listOf("group=\"x\"")), listOf("b" to revoked), listOf("https://a: answered 503"), now)
    assertEquals(KeyStatus.State.REVOKED, revokedCopy.state)
    assertTrue(revokedCopy.fails)
    // end to end: a fetcher that fails on one server and has no copy on the other
    val fetcher = KeyStatus.Fetcher { server, _ -> if (server == "a") throw java.io.IOException("connect timed out") else null }
    val finding = KeyStatus.findings(listOf(KeyStatus.TrustedKey(revoked.fingerprint, true, listOf("group=\"x\""))), listOf("a", "b"), fetcher, now).single()
    assertEquals(KeyStatus.State.UNDECIDED, finding.state)
    assertTrue(finding.fails)
    assertTrue(finding.reason!!.contains("a: connect timed out"), finding.reason)
    // both servers answer that they have no copy: absent, a warning
    val absent = KeyStatus.findings(listOf(everyVersion), listOf("a", "b"), { _, _ -> null }, now).single()
    assertEquals(KeyStatus.State.ABSENT, absent.state)
    assertTrue(absent.warns)
    assertFalse(absent.fails)
    // a server's copy that does not carry the key is an error too
    val wrongKey = KeyStatus.findings(listOf(everyVersion), listOf("a"), { _, _ -> fixture("revoked") }, now).single()
    assertEquals(KeyStatus.State.UNDECIDED, wrongKey.state)
    assertTrue(wrongKey.reason!!.contains("does not carry ${current.fingerprint}"), wrongKey.reason)
  }

  @Test
  fun `the furthest expiry any server holds decides, and a copy without one means none`() {
    val expired = key("expired-cel")
    val current = key("current")
    val extended = OpenPgpKeys.PublicKey(expired.fingerprint, current.created, null, null, emptyList(), current.userIds)
    val later = OpenPgpKeys.PublicKey(expired.fingerprint, expired.created, now.plusSeconds(86_400), null, emptyList(), expired.userIds)
    val key = KeyStatus.TrustedKey(expired.fingerprint, true, listOf("group=\"dev.cel\""))
    assertEquals(KeyStatus.State.CURRENT, KeyStatus.classify(key, listOf("a" to expired, "b" to extended), emptyList(), now).state)
    val renewed = KeyStatus.classify(key, listOf("a" to expired, "b" to later), emptyList(), now)
    assertEquals(KeyStatus.State.CURRENT, renewed.state)
    assertEquals(now.plusSeconds(86_400), renewed.expiresAt)
    assertEquals(KeyStatus.State.EXPIRED, KeyStatus.classify(key, listOf("a" to expired), emptyList(), now).state)
  }

  @Test
  fun `a subkey the policy names expires or is revoked with the primary or on its own`() {
    val sava = key("sava-hkp")
    val sub = sava.subkeys.first()
    val named = KeyStatus.TrustedKey(sub.fingerprint, true, listOf("group=\"software.sava\""))
    assertEquals(KeyStatus.State.CURRENT, KeyStatus.classify(named, listOf("a" to sava), emptyList(), now).state)
    val revokedSub = OpenPgpKeys.PublicKey(sava.fingerprint, sava.created, sava.expiresAt, null,
      listOf(OpenPgpKeys.Subkey(sub.fingerprint, sub.created, sub.expiresAt, OpenPgpKeys.Revocation(now.minusSeconds(60), 3, "fixture"))), sava.userIds)
    val f = KeyStatus.classify(named, listOf("a" to revokedSub), emptyList(), now)
    assertEquals(KeyStatus.State.REVOKED, f.state)
    assertTrue(f.fails)
    val expiredPrimary = OpenPgpKeys.PublicKey(sava.fingerprint, sava.created, now.minusSeconds(60), null, listOf(sub), sava.userIds)
    assertEquals(KeyStatus.State.EXPIRED, KeyStatus.classify(named, listOf("a" to expiredPrimary), emptyList(), now).state)
    // the policy names the primary: an expired or revoked signing subkey is a warning on a current key, unless a server holds it renewed
    val primary = KeyStatus.TrustedKey(sava.fingerprint, true, listOf("group=\"software.sava\""))
    val expiredSub = OpenPgpKeys.PublicKey(sava.fingerprint, sava.created, null, null,
      listOf(OpenPgpKeys.Subkey(sub.fingerprint, sub.created, now.minusSeconds(60), null)), sava.userIds)
    val stale = KeyStatus.classify(primary, listOf("a" to expiredSub), emptyList(), now)
    assertEquals(KeyStatus.State.CURRENT, stale.state)
    assertTrue(stale.warns)
    assertEquals(listOf("subkey ${sub.fingerprint} expired ${now.minusSeconds(60)}"), stale.subkeyNotes)
    assertTrue(KeyStatus.describe(stale).contains("current (subkey ${sub.fingerprint} expired "), KeyStatus.describe(stale))
    // the same subkey without an expiry on another server: renewed, so no warning (both copies read as able to sign)
    val renewedCopy = OpenPgpKeys.PublicKey(sava.fingerprint, sava.created, null, null,
      listOf(OpenPgpKeys.Subkey(sub.fingerprint, sub.created, null, null)), sava.userIds)
    val renewedSub = KeyStatus.classify(primary, listOf("a" to expiredSub, "b" to renewedCopy), emptyList(), now)
    assertFalse(renewedSub.warns, renewedSub.subkeyNotes.toString())
    val revokedSubOnPrimary = KeyStatus.classify(primary, listOf("a" to revokedSub), emptyList(), now)
    assertEquals(KeyStatus.State.CURRENT, revokedSubOnPrimary.state)
    assertEquals(listOf("subkey ${sub.fingerprint} revoked ${now.minusSeconds(60)}"), revokedSubOnPrimary.subkeyNotes)
    // a copy that does not carry the fingerprint at all is an error, which leaves the key undecided
    val wrong = KeyStatus.classify(named, emptyList(), listOf("a returned a key block that does not carry ${sub.fingerprint}"), now)
    assertEquals(KeyStatus.State.UNDECIDED, wrong.state)
    assertTrue(wrong.fails)
  }

  @Test
  fun `this repository's policy reads as trusted keys with their version-freeness`() {
    val keys = KeyStatus.trustedKeys(savaBuildRootFile(VerificationPolicyRules.METADATA))
    assertTrue(keys.size >= 30, "${keys.size} keys")
    assertTrue(keys.all { it.id.length == 40 && it.id == it.id.uppercase() })
    assertEquals(keys.map { it.id }, keys.map { it.id }.sorted().distinct())
    val gradle = keys.single { it.id == "1BD97A6A154E7810EE0BC832E2F38302C8075E3D" }
    assertFalse(gradle.versionFree, "the revoked Gradle key is version-scoped: ${gradle.scopes}")
    assertTrue(gradle.scopes.all { "version=" in it }, gradle.scopes.toString())
    assertTrue(keys.count { it.versionFree } > keys.count { !it.versionFree }, "most keys are publisher keys trusted without a version")
    assertTrue(keys.single { it.id == "F3FF33E96F18AA62DD580F9651FBF517CE6D6B80" }.versionFree, "the Gradle signing subkey the policy names for gradle:gradle")
  }

  @Test
  fun `a regex version trusts many versions, so such a rule is not an exact-version one`() {
    val file = java.io.File.createTempFile("verification-metadata", ".xml")
    try {
      file.writeText(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<verification-metadata xmlns=\"https://schema.gradle.org/dependency-verification\">\n" +
          "<configuration><verify-metadata>false</verify-metadata><verify-signatures>false</verify-signatures><trusted-keys>\n" +
          "<trusted-key id=\"" + "a".repeat(40) + "\" group=\"org[.]example\" version=\".*\" regex=\"true\"/>\n" +
          "<trusted-key id=\"" + "b".repeat(40) + "\" group=\"org.example\" version=\"1.0\"/>\n" +
          "</trusted-keys></configuration></verification-metadata>\n"
      )
      val keys = KeyStatus.trustedKeys(file).associateBy { it.id }
      assertTrue(keys.getValue("A".repeat(40)).versionFree, "a regex version is every version")
      assertFalse(keys.getValue("B".repeat(40)).versionFree)
    } finally {
      file.delete()
    }
  }

  @Test
  fun `the receipt is one dated tab-separated line per key under a header`() {
    val current = key("current")
    val f = KeyStatus.classify(KeyStatus.TrustedKey(current.fingerprint, true, listOf("group=\"x\"")), listOf("https://a" to current), emptyList(), now)
    val lines = KeyStatus.receipt(listOf(f), now).lines().filter { it.isNotEmpty() }
    assertEquals(2, lines.size)
    assertEquals("checked\tfingerprint\tstate\tversion_free\tholder\texpires\trevoked\treason\tservers\tsubkeys", lines[0])
    assertEquals(listOf("2026-10-31T18:00:00Z", current.fingerprint, "CURRENT", "true", "Current Fixture <current@fixture.invalid>", "", "", "", "https://a", ""), lines[1].split("\t"))
  }
}
