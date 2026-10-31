import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.verification.KeyStatus
import software.sava.build.verification.OpenPgpKeys
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * The reader over packets built by hand, for what GnuPG does not write into a fixture: a
 * third party's certification, a zero expiry interval, a direct-key signature carrying the
 * expiry, several keys in one armored block, an encryption-only subkey, and a revocation
 * another key issued. Nothing is signed for real; the reader verifies nothing and reads the
 * issuer subpackets as written, which is what these packets exercise.
 */
class OpenPgpKeysTest {

  private val t0: Long = 1_600_000_000L // 2020-09-13T12:26:40Z
  private val year: Long = 365L * 86_400

  private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

  /** A new-format packet with a two-octet length. */
  private fun packet(tag: Int, body: ByteArray): ByteArray {
    require(body.size in 192..8383)
    val len = body.size - 192
    return byteArrayOf((0xC0 or tag).toByte(), ((len shr 8) + 192).toByte(), (len and 0xFF).toByte()) + body
  }

  /** A v4 key packet: version, creation time, algorithm 22 (EdDSA) and a dummy MPI large enough for a two-octet length. */
  private fun keyBody(created: Long, seed: Int): ByteArray =
    byteArrayOf(4) + be32(created) + byteArrayOf(22) + byteArrayOf(0x08, 0x00) + ByteArray(256) { (it + seed).toByte() }

  private fun fingerprint(keyBody: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-1")
    d.update(0x99.toByte()); d.update((keyBody.size shr 8).toByte()); d.update(keyBody.size.toByte()); d.update(keyBody)
    return d.digest().joinToString("") { "%02X".format(it) }
  }

  private fun sub(type: Int, data: ByteArray) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data
  private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  /** A v4 signature body: type, algorithms, the hashed subpackets (creation first), the unhashed issuer, a dummy hash prefix and MPI; padded to a two-octet packet length. */
  private fun sig(type: Int, created: Long, issuer: String, hashed: List<ByteArray> = emptyList(), issuerInHashed: Boolean = false): ByteArray {
    val h = ByteArrayOutputStream()
    h.write(sub(2, be32(created)))
    hashed.forEach(h::write)
    if (issuerInHashed) h.write(sub(33, byteArrayOf(4) + hex(issuer)))
    val u = ByteArrayOutputStream()
    if (!issuerInHashed) u.write(sub(16, hex(issuer.takeLast(16))))
    val hb = h.toByteArray(); val ub = u.toByteArray()
    return byteArrayOf(4, type.toByte(), 22, 10, (hb.size shr 8).toByte(), hb.size.toByte()) + hb +
      byteArrayOf((ub.size shr 8).toByte(), ub.size.toByte()) + ub + byteArrayOf(0x12, 0x34) + byteArrayOf(0x08, 0x00) + ByteArray(256)
  }

  private fun armor(vararg packets: ByteArray): String =
    "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(packets.reduce { a, b -> a + b }) +
      "\n-----END PGP PUBLIC KEY BLOCK-----\n"

  private fun uid(name: String) = packet(13, name.toByteArray().let { it + ByteArray(200 - it.size) })

  @Test
  fun `a third party's newer certification does not clear the key's own expiry`() {
    val key = keyBody(t0, 1); val fpr = fingerprint(key)
    val other = fingerprint(keyBody(t0, 2))
    val block = armor(packet(6, key), uid("Example <e@example.invalid>"),
      packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(year))))),
      packet(2, sig(0x10, t0 + 2 * year, other)))
    val parsed = OpenPgpKeys.parse(block).single()
    assertEquals(fpr, parsed.fingerprint)
    assertEquals(Instant.ofEpochSecond(t0 + year), parsed.expiresAt)
    assertEquals(listOf("Example <e@example.invalid>"), parsed.userIds.map { it.trimEnd('\u0000') })
  }

  @Test
  fun `the key's own newer certification without an expiry, or with a zero interval, means it never expires`() {
    val key = keyBody(t0, 3); val fpr = fingerprint(key)
    val renewed = armor(packet(6, key), uid("Renewed"),
      packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(year))))),
      packet(2, sig(0x13, t0 + year / 2, fpr, issuerInHashed = true)))
    assertNull(OpenPgpKeys.parse(renewed).single().expiresAt)
    val zero = armor(packet(6, key), uid("Zero"), packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(0))))))
    assertNull(OpenPgpKeys.parse(zero).single().expiresAt)
  }

  @Test
  fun `a direct-key signature carries the expiry when the certifications do not, and the newest self-signature wins`() {
    val key = keyBody(t0, 4); val fpr = fingerprint(key)
    val direct = armor(packet(6, key), packet(2, sig(0x1F, t0, fpr, listOf(sub(9, be32(year))))), uid("Direct"), packet(2, sig(0x13, t0 - 1, fpr)))
    assertEquals(Instant.ofEpochSecond(t0 + year), OpenPgpKeys.parse(direct).single().expiresAt)
    val superseded = armor(packet(6, key), packet(2, sig(0x1F, t0, fpr, listOf(sub(9, be32(year))))), uid("Direct"), packet(2, sig(0x13, t0 + 1, fpr)))
    assertNull(OpenPgpKeys.parse(superseded).single().expiresAt)
  }

  @Test
  fun `several keys in one armored block are read apart, each with its own standing`() {
    val a = keyBody(t0, 5); val b = keyBody(t0, 6)
    val block = armor(packet(6, a), uid("A"), packet(2, sig(0x13, t0, fingerprint(a))),
      packet(6, b), packet(2, sig(0x20, t0 + 10, fingerprint(b), listOf(sub(29, byteArrayOf(3) + "gone".toByteArray())))), uid("B"), packet(2, sig(0x13, t0, fingerprint(b))))
    val keys = OpenPgpKeys.parse(block)
    assertEquals(listOf(fingerprint(a), fingerprint(b)), keys.map { it.fingerprint })
    assertNull(keys[0].revocation)
    assertNotNull(keys[1].revocation)
    assertEquals(3, keys[1].revocation!!.code)
    assertEquals("gone", keys[1].revocation!!.text)
    assertEquals("retired: gone", keys[1].revocation!!.reason)
    assertFalse(keys[1].revocation!!.hard)
    assertEquals(Instant.ofEpochSecond(t0 + 10), keys[1].revocation!!.at)
  }

  @Test
  fun `a copy without user ids, as the openpgp key server serves an unverified key, is not self-signed and does not vote on expiry`() {
    val key = keyBody(t0, 12); val fpr = fingerprint(key); val sub = keyBody(t0, 13)
    val stripped = armor(packet(6, key), packet(14, sub), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x02)), sub(9, be32(year))))))
    val full = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(year))))), packet(14, sub), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x02)), sub(9, be32(year))))))
    val hagrid = OpenPgpKeys.parse(stripped).single()
    val ubuntu = OpenPgpKeys.parse(full).single()
    assertFalse(hagrid.selfSigned)
    assertNull(hagrid.expiresAt)
    assertTrue(ubuntu.selfSigned)
    assertEquals(Instant.ofEpochSecond(t0 + year), ubuntu.expiresAt)
    assertEquals(Instant.ofEpochSecond(t0 + year), hagrid.subkeys.single().expiresAt, "the binding signature survives the stripping")
    val now = Instant.ofEpochSecond(t0 + 2 * year)
    val trusted = KeyStatus.TrustedKey(fpr, true, listOf("group=\"x\""))
    // the stripped copy does not veto the expiry the full copy carries
    val both = KeyStatus.classify(trusted, listOf("a" to hagrid, "b" to ubuntu), emptyList(), now)
    assertEquals(KeyStatus.State.EXPIRED, both.state)
    assertFalse(both.unknownExpiry)
    // only stripped copies: nothing is known of the primary's expiry, which is said and warned about
    val only = KeyStatus.classify(trusted, listOf("a" to hagrid), emptyList(), now)
    assertEquals(KeyStatus.State.CURRENT, only.state)
    assertTrue(only.unknownExpiry)
    assertTrue(only.warns)
    assertEquals("no self-signature over the primary in any server copy, so its expiry is unknown", only.reason)
    // the policy naming the signing subkey: its binding still decides, stripped or not
    val named = KeyStatus.classify(KeyStatus.TrustedKey(fingerprint(sub), true, listOf("group=\"x\"")), listOf("a" to hagrid), emptyList(), now)
    assertEquals(KeyStatus.State.EXPIRED, named.state)
  }

  @Test
  fun `a revocation another key issued is not the key's own`() {
    val key = keyBody(t0, 7); val fpr = fingerprint(key); val other = fingerprint(keyBody(t0, 8))
    val block = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, other)), uid("K"), packet(2, sig(0x13, t0, fpr)))
    assertNull(OpenPgpKeys.parse(block).single().revocation)
    val own = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, fpr)), uid("K"), packet(2, sig(0x13, t0, fpr)))
    val bare = OpenPgpKeys.parse(own).single().revocation!!
    assertNull(bare.code)
    assertTrue(bare.hard, "no reason subpacket is a hard revocation")
    assertEquals("no reason subpacket", bare.reason)
    val compromised = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, fpr, listOf(sub(29, byteArrayOf(2))))), uid("K"), packet(2, sig(0x13, t0, fpr)))
    val hard = OpenPgpKeys.parse(compromised).single().revocation!!
    assertEquals(2, hard.code)
    assertTrue(hard.hard)
    assertEquals("key compromised", hard.reason)
    val superseded = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, fpr, listOf(sub(29, byteArrayOf(1))))), uid("K"), packet(2, sig(0x13, t0, fpr)))
    assertFalse(OpenPgpKeys.parse(superseded).single().revocation!!.hard)
  }

  @Test
  fun `a subpacket that runs past its area makes the signature malformed, which is passed over`() {
    val key = keyBody(t0, 17); val fpr = fingerprint(key)
    // a newer self-certification whose hashed area ends with "05 09": an expiry subpacket claiming four data octets it does not have,
    // so that an unchecked reader takes the zero unhashed length and hash prefix after it as a zero expiry (never expires)
    val hashed = sub(2, be32(t0 + 1)) + sub(33, byteArrayOf(4) + hex(fpr)) + byteArrayOf(5, 9)
    val malformed = byteArrayOf(4, 0x13, 22, 10, (hashed.size shr 8).toByte(), hashed.size.toByte()) + hashed + byteArrayOf(0, 0) + byteArrayOf(0, 0) + byteArrayOf(0x08, 0x00) + ByteArray(256)
    val block = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(year))))), packet(2, malformed))
    assertEquals(Instant.ofEpochSecond(t0 + year), OpenPgpKeys.parse(block).single().expiresAt, "the older, well-formed certification stands")
  }

  @Test
  fun `every copy of the key in one answer counts, and a hard revocation outranks a soft one`() {
    val key = keyBody(t0, 18); val fpr = fingerprint(key)
    val current = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr)))
    val retired = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, fpr, listOf(sub(29, byteArrayOf(3))))), uid("K"), packet(2, sig(0x13, t0, fpr)))
    val compromised = armor(packet(6, key), packet(2, sig(0x20, t0 + 20, fpr, listOf(sub(29, byteArrayOf(2))))), uid("K"), packet(2, sig(0x13, t0, fpr)))
    val now = Instant.ofEpochSecond(t0 + year)
    val trusted = KeyStatus.TrustedKey(fpr, true, listOf("group=\"x\""))
    // one server answers with two copies of the key, the current one first: the revoked copy still decides
    val twoCopies = KeyStatus.findings(listOf(trusted), listOf("a", "b"), { server, _ -> if (server == "a") current + retired else null }, now).single()
    assertEquals(KeyStatus.State.REVOKED, twoCopies.state)
    assertTrue(twoCopies.fails)
    assertEquals("retired", twoCopies.reason)
    // a later compromise on another server outranks the earlier retirement
    val both = KeyStatus.findings(listOf(trusted), listOf("a", "b"), { server, _ -> if (server == "a") retired else compromised }, now).single()
    assertEquals(Instant.ofEpochSecond(t0 + 20), both.revokedAt)
    assertEquals("key compromised", both.reason)
    // a named subkey's compromise outranks its primary's retirement
    val sub = keyBody(t0, 19)
    val subkeyCompromised = armor(packet(6, key), packet(2, sig(0x20, t0 + 10, fpr, listOf(sub(29, byteArrayOf(3))))), uid("K"), packet(2, sig(0x13, t0, fpr)),
      packet(14, sub), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x02))))), packet(2, sig(0x28, t0 + 30, fpr, listOf(sub(29, byteArrayOf(2))))))
    val named = KeyStatus.classify(KeyStatus.TrustedKey(fingerprint(sub), true, listOf("group=\"x\"")), listOf("a" to OpenPgpKeys.parse(subkeyCompromised).single()), emptyList(), now)
    assertEquals(KeyStatus.State.REVOKED, named.state)
    assertEquals("key compromised", named.reason)
  }

  @Test
  fun `a signature packet that does not parse is passed over without losing the key`() {
    val key = keyBody(t0, 14); val fpr = fingerprint(key)
    // a hashed area of one octet: a subpacket length of zero and no type octet after it
    val bad = byteArrayOf(4, 0x10, 22, 10, 0, 1, 0) + byteArrayOf(0, 0) + byteArrayOf(0x12, 0x34) + byteArrayOf(0x08, 0x00) + ByteArray(256)
    val block = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr, listOf(sub(9, be32(year))))), packet(2, bad))
    val parsed = OpenPgpKeys.parse(block).single()
    assertEquals(Instant.ofEpochSecond(t0 + year), parsed.expiresAt)
  }

  @Test
  fun `a subkey the reader cannot fingerprint takes its own signatures, which the next subkey does not inherit`() {
    val key = keyBody(t0, 15); val fpr = fingerprint(key)
    val v5 = byteArrayOf(5) + be32(t0) + byteArrayOf(22) + ByteArray(250)
    val v4 = keyBody(t0, 16)
    val block = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr)),
      packet(14, v5), packet(2, sig(0x28, t0 + 5, fpr, listOf(sub(29, byteArrayOf(3))))),
      packet(14, v4), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x02))))))
    val parsed = OpenPgpKeys.parse(block).single()
    assertEquals(listOf(fingerprint(v4)), parsed.subkeys.map { it.fingerprint })
    assertNull(parsed.subkeys.single().revocation)
    assertTrue(parsed.subkeys.single().canSign)
  }

  @Test
  fun `an encryption-only subkey is read as unable to sign and never warned about`() {
    val key = keyBody(t0, 9); val fpr = fingerprint(key)
    val enc = keyBody(t0, 10); val sign = keyBody(t0, 11)
    val block = armor(packet(6, key), uid("K"), packet(2, sig(0x13, t0, fpr)),
      packet(14, enc), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x0C)), sub(9, be32(year))))),
      packet(14, sign), packet(2, sig(0x18, t0, fpr, listOf(sub(27, byteArrayOf(0x02)), sub(9, be32(year))))))
    val parsed = OpenPgpKeys.parse(block).single()
    assertEquals(listOf(false, true), parsed.subkeys.map { it.canSign })
    assertTrue(parsed.subkeys.all { it.expiresAt == Instant.ofEpochSecond(t0 + year) })
    val now = Instant.ofEpochSecond(t0 + 2 * year)
    val finding = KeyStatus.classify(KeyStatus.TrustedKey(fpr, true, listOf("group=\"x\"")), listOf("s" to parsed), emptyList(), now)
    assertEquals(KeyStatus.State.CURRENT, finding.state)
    assertEquals(listOf("subkey ${fingerprint(sign)} expired ${Instant.ofEpochSecond(t0 + year)}"), finding.subkeyNotes)
    assertFalse(finding.subkeyNotes.any { fingerprint(enc) in it })
  }
}
