package software.sava.build.verification

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * The little of OpenPGP (RFC 4880) the key-status check needs: the public keys in an armored
 * export, their v4 fingerprints, and the self-signatures that revoke them or give them an
 * expiry. Nothing is verified cryptographically: a revocation or an expiry read from a copy a
 * key server holds is a reason to look, and what the policy trusts is decided by its reviewer,
 * never here, and a signature's issuer is read from its subpackets, not proven, so a copy a key
 * server accepted from anyone is read as that uploader wrote it. Gradle's own keyring export
 * strips user ids and primary self-signatures, so this reads the server's copy, not the committed
 * keyring, when a status is wanted.
 */
object OpenPgpKeys {

  /** One public key as a key server or a keyring holds it: the primary key and its subkeys. */
  class PublicKey(
    val fingerprint: String,
    val created: Instant,
    /** Expiry from the newest self-signature over the key, a user id certification or a direct-key signature; null when it carries none or a zero interval. */
    val expiresAt: Instant?,
    /** Set by a key revocation signature (0x20) the key issued itself; a designated revoker's is not read. */
    val revocation: Revocation?,
    val subkeys: List<Subkey>,
    val userIds: List<String>,
    /**
     * Whether the copy carries a signature the key made over itself, a user id certification or a
     * direct-key signature. keys.openpgp.org serves a key without its user ids until the holder
     * verifies an address, so a copy from there may carry none and then says nothing about the
     * primary's expiry; [expiresAt] is null in that case as in the never-expires one.
     */
    val selfSigned: Boolean = true,
  ) {
    /** Every fingerprint this block answers for: the primary's and each subkey's. */
    val fingerprints: List<String> get() = listOf(fingerprint) + subkeys.map { it.fingerprint }
  }

  /** [canSign] follows the binding signature's key flags; a binding without flags is taken as able to sign. */
  class Subkey(val fingerprint: String, val created: Instant, val expiresAt: Instant?, val revocation: Revocation?, val canSign: Boolean = true)

  /**
   * A revocation signature's date and reason (RFC 4880, subpacket 29): code 1 (superseded) and 3
   * (retired) are soft, so what the key signed before the date still stands; code 0 (none given),
   * 2 (key material compromised) and a missing subpacket are hard, so nothing it signed does.
   */
  class Revocation(val at: Instant, val code: Int?, val text: String?) {
    val hard: Boolean get() = code == null || code == 0 || code == 2
    val reason: String get() {
      val name = when (code) { null -> "no reason subpacket"; 0 -> "no reason given"; 1 -> "superseded"; 2 -> "key compromised"; 3 -> "retired"; 32 -> "user id no longer valid"; else -> "reason code $code" }
      return if (text.isNullOrEmpty()) name else "$name: $text"
    }
  }

  /** Parses every v4 key in every armored public key block of [text]; a block that does not parse is skipped. */
  fun parse(text: String): List<PublicKey> =
    armoredBlocks(text).flatMap { runCatching { parseBlock(it) }.getOrDefault(emptyList()) }

  private fun armoredBlocks(text: String): List<ByteArray> {
    val blocks = mutableListOf<ByteArray>()
    var i = 0
    while (true) {
      val begin = text.indexOf("-----BEGIN PGP PUBLIC KEY BLOCK-----", i)
      if (begin < 0) break
      val end = text.indexOf("-----END PGP PUBLIC KEY BLOCK-----", begin)
      if (end < 0) break
      val body = text.substring(begin, end).lines().drop(1)
        .dropWhile { it.isNotBlank() }            // armor headers up to the blank line
        .filter { it.isNotBlank() && !it.startsWith("=") }
        .joinToString("")
      blocks += Base64.getMimeDecoder().decode(body)
      i = end + 1
    }
    return blocks
  }

  private class Packet(val tag: Int, val body: ByteArray)

  private fun packets(data: ByteArray): List<Packet> {
    val out = mutableListOf<Packet>()
    var i = 0
    while (i < data.size) {
      val first = data[i].toInt() and 0xFF
      require(first and 0x80 != 0) { "not an OpenPGP packet at offset $i" }
      i++
      val tag: Int
      val body: ByteArray
      if (first and 0x40 != 0) {
        tag = first and 0x3F
        val chunks = mutableListOf<ByteArray>()
        while (true) {
          val b = data[i].toInt() and 0xFF
          val len: Int
          var partial = false
          when {
            b < 192 -> { len = b; i += 1 }
            b < 224 -> { len = ((b - 192) shl 8) + (data[i + 1].toInt() and 0xFF) + 192; i += 2 }
            b == 255 -> { len = int32(data, i + 1); i += 5 }
            else -> { len = 1 shl (b and 0x1F); i += 1; partial = true }
          }
          chunks += data.copyOfRange(i, i + len); i += len
          if (!partial) break
        }
        body = chunks.reduce { a, b -> a + b }
      } else {
        tag = (first shr 2) and 0x0F
        val len = when (first and 3) {
          0 -> (data[i].toInt() and 0xFF).also { i += 1 }
          1 -> ((data[i].toInt() and 0xFF shl 8) or (data[i + 1].toInt() and 0xFF)).also { i += 2 }
          2 -> int32(data, i).also { i += 4 }
          else -> data.size - i
        }
        body = data.copyOfRange(i, i + len); i += len
      }
      out += Packet(tag, body)
    }
    return out
  }

  private fun int32(d: ByteArray, at: Int): Int =
    ((d[at].toInt() and 0xFF) shl 24) or ((d[at + 1].toInt() and 0xFF) shl 16) or ((d[at + 2].toInt() and 0xFF) shl 8) or (d[at + 3].toInt() and 0xFF)

  private fun fingerprintV4(keyBody: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(0x99.toByte())
    digest.update((keyBody.size shr 8).toByte()); digest.update(keyBody.size.toByte())
    digest.update(keyBody)
    return digest.digest().joinToString("") { "%02X".format(it) }
  }

  private class Signature(
    val type: Int,
    val created: Instant,
    /** Subpacket 9 when present and not zero, which means the key never expires. */
    val keyExpirySeconds: Long?,
    val reasonCode: Int?,
    val reasonText: String?,
    val issuerKeyId: String?,
    val issuerFingerprint: String?,
    val keyFlags: Int?,
  ) {
    /** Whether the key with [fingerprint] issued this signature, by its issuer fingerprint or key id subpacket; a signature naming no issuer is nobody's. */
    fun issuedBy(fingerprint: String): Boolean =
      issuerFingerprint?.equals(fingerprint, ignoreCase = true) ?: issuerKeyId?.equals(fingerprint.takeLast(16), ignoreCase = true) ?: false
  }

  private fun signature(body: ByteArray): Signature? {
    if (body[0].toInt() != 4) return null
    val type = body[1].toInt() and 0xFF
    var created: Instant? = null
    var expiry: Long? = null
    var reasonCode: Int? = null
    var reasonText: String? = null
    var issuerKeyId: String? = null
    var issuerFingerprint: String? = null
    var keyFlags: Int? = null
    // The hashed area carries what the signer meant; the unhashed one is read for the issuer alone.
    val hashedLength = (body[4].toInt() and 0xFF shl 8) or (body[5].toInt() and 0xFF)
    val unhashedStart = 6 + hashedLength
    require(unhashedStart + 2 <= body.size) { "hashed subpackets beyond the signature packet" }
    val unhashedLength = (body[unhashedStart].toInt() and 0xFF shl 8) or (body[unhashedStart + 1].toInt() and 0xFF)
    // A subpacket that runs past its area is a malformed signature, not data: the caller passes the packet over.
    fun subpackets(from: Int, to: Int, hashed: Boolean) {
      require(to <= body.size) { "subpacket area beyond the signature packet" }
      var i = from
      while (i < to) {
        val b = body[i].toInt() and 0xFF
        val len: Int
        when {
          b < 192 -> { len = b; i += 1 }
          b < 255 -> { len = ((b - 192) shl 8) + (body[i + 1].toInt() and 0xFF) + 192; i += 2 }
          else -> { len = int32(body, i + 1); i += 5 }
        }
        require(len >= 1 && i + len <= to) { "subpacket beyond its area" }
        val subType = body[i].toInt() and 0x7F
        val sub = body.copyOfRange(i + 1, i + len)
        when (subType) {
          2 -> if (hashed) created = Instant.ofEpochSecond(int32(sub, 0).toLong() and 0xFFFFFFFFL)
          9 -> if (hashed) expiry = (int32(sub, 0).toLong() and 0xFFFFFFFFL).takeIf { it > 0 }
          16 -> issuerKeyId = issuerKeyId ?: sub.joinToString("") { "%02X".format(it) }
          27 -> if (hashed && sub.isNotEmpty()) keyFlags = sub[0].toInt() and 0xFF
          29 -> if (hashed && sub.isNotEmpty()) { reasonCode = sub[0].toInt() and 0xFF; reasonText = if (sub.size > 1) String(sub, 1, sub.size - 1, Charsets.UTF_8) else null }
          33 -> if (sub.size == 21 && sub[0].toInt() == 4) issuerFingerprint = issuerFingerprint ?: sub.copyOfRange(1, 21).joinToString("") { "%02X".format(it) }
        }
        i += len
      }
    }
    subpackets(6, unhashedStart, hashed = true)
    subpackets(unhashedStart + 2, unhashedStart + 2 + unhashedLength, hashed = false)
    return Signature(type, created ?: Instant.EPOCH, expiry, reasonCode, reasonText, issuerKeyId, issuerFingerprint, keyFlags)
  }

  /** One block may hold several transferable keys, as a key server's answer to a search does: each tag-6 packet starts one. */
  private fun parseBlock(data: ByteArray): List<PublicKey> {
    val packets = packets(data)
    val starts = packets.indices.filter { packets[it].tag == 6 }
    return starts.mapIndexedNotNull { n, start ->
      val end = starts.getOrElse(n + 1) { packets.size }
      runCatching { parseKey(packets.subList(start, end)) }.getOrNull()
    }
  }

  private fun parseKey(packets: List<Packet>): PublicKey? {
    val primary = packets.first()
    if (primary.body[0].toInt() != 4) return null
    val primaryCreated = Instant.ofEpochSecond(int32(primary.body, 1).toLong() and 0xFFFFFFFFL)
    val primaryFingerprint = fingerprintV4(primary.body)
    var primaryRevocation: Revocation? = null
    var newestSelfSig: Signature? = null
    val userIds = mutableListOf<String>()
    val subkeys = mutableListOf<Subkey>()
    var subkeyBody: ByteArray? = null
    var subkeyCreated: Instant? = null
    var subkeyBinding: Signature? = null
    var subkeyRevocation: Revocation? = null
    fun closeSubkey() {
      val body = subkeyBody ?: return
      val flags = subkeyBinding?.keyFlags
      subkeys += Subkey(fingerprintV4(body), subkeyCreated!!, subkeyBinding?.let { expiry(subkeyCreated!!, it) }, subkeyRevocation, flags == null || flags and 0x02 != 0)
      subkeyBody = null; subkeyCreated = null; subkeyBinding = null; subkeyRevocation = null
    }
    var inSubkey = false
    for (packet in packets.drop(1)) {
      when (packet.tag) {
        13 -> { closeSubkey(); inSubkey = false; userIds += String(packet.body, Charsets.UTF_8) }
        14 -> {
          // a subkey this reader cannot fingerprint (not v4) still ends the previous one and takes its own signatures
          closeSubkey(); inSubkey = true; subkeyBinding = null; subkeyRevocation = null
          if (packet.body[0].toInt() == 4) {
            subkeyBody = packet.body
            subkeyCreated = Instant.ofEpochSecond(int32(packet.body, 1).toLong() and 0xFFFFFFFFL)
          }
        }
        2 -> {
          // Only the key's own signatures speak for it: a third party's certification, or a
          // revocation it did not issue, says nothing about its expiry or standing here; a
          // signature packet that does not parse is somebody's and is passed over.
          val sig = runCatching { signature(packet.body) }.getOrNull() ?: continue
          if (!sig.issuedBy(primaryFingerprint)) continue
          when {
            sig.type == 0x20 && !inSubkey -> primaryRevocation = primaryRevocation ?: Revocation(sig.created, sig.reasonCode, sig.reasonText)
            sig.type == 0x28 && inSubkey -> subkeyRevocation = subkeyRevocation ?: Revocation(sig.created, sig.reasonCode, sig.reasonText)
            sig.type == 0x18 && inSubkey -> if (subkeyBinding == null || sig.created > subkeyBinding!!.created) subkeyBinding = sig
            (sig.type in 0x10..0x13 || sig.type == 0x1F) && !inSubkey -> if (newestSelfSig == null || sig.created > newestSelfSig!!.created) newestSelfSig = sig
          }
        }
      }
    }
    closeSubkey()
    return PublicKey(primaryFingerprint, primaryCreated, newestSelfSig?.let { expiry(primaryCreated, it) }, primaryRevocation, subkeys, userIds, newestSelfSig != null)
  }

  private fun expiry(created: Instant, sig: Signature): Instant? = sig.keyExpirySeconds?.let { created.plusSeconds(it) }
}
