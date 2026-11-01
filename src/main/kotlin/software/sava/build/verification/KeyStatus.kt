package software.sava.build.verification

import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a key server says today about each key a verification policy trusts. Gradle verifies a
 * signature against the committed keyring and nothing else: it checks neither revocation nor
 * expiry, and the keyring its export writes cannot even hold a revocation certificate. So a key
 * its publisher has revoked keeps vouching for every new release until the policy's reviewer
 * version-scopes it; this check is how the reviewer learns of it.
 */
object KeyStatus {

  /** The two servers the fleet's policies were authenticated against; a key is looked up on both. */
  val DEFAULT_SERVERS = listOf("https://keys.openpgp.org", "https://keyserver.ubuntu.com")

  /** [ABSENT] is every server answering that it has no copy; [UNDECIDED] is a server not answering, which decides nothing. */
  enum class State { CURRENT, EXPIRED, REVOKED, ABSENT, UNDECIDED }

  /** One key the policy trusts: its id as the policy writes it, and whether any rule trusts it beyond exact versions (a regex version matches many). */
  data class TrustedKey(val id: String, val versionFree: Boolean, val scopes: List<String>)

  class Finding(
    val key: TrustedKey,
    val state: State,
    val holder: String?,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val reason: String?,
    val servers: List<String>,
    /** Subkeys of a current primary the policy names that have expired or been revoked: Gradle accepts their signatures regardless. */
    val subkeyNotes: List<String> = emptyList(),
    /** No server copy carries a self-signature over the primary, so nothing is known of its expiry. */
    val unknownExpiry: Boolean = false,
  ) {
    /** A revoked key still trusted for every version fails, and so does a key a server left undecided: a question not answered is not a pass. The rest warn. */
    val fails: Boolean get() = (state == State.REVOKED && key.versionFree) || state == State.UNDECIDED
    val warns: Boolean get() = !fails && (state != State.CURRENT || subkeyNotes.isNotEmpty() || unknownExpiry)
  }

  /** The keys `gradle/verification-metadata.xml` trusts: every `trusted-key` id and every per-artifact `pgp` value. */
  fun trustedKeys(metadata: File): List<TrustedKey> {
    val document = DocumentBuilderFactory.newInstance().run {
      isNamespaceAware = true
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      newDocumentBuilder().parse(metadata)
    }
    fun elements(name: String) = document.getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    fun scope(element: Element) = listOf("group", "name", "version", "regex")
      .filter { element.hasAttribute(it) }.joinToString(" ") { "$it=\"${element.getAttribute(it)}\"" }
    val coordinates = listOf("group", "name", "version", "file", "regex")
    val keys = elements("trusted-key").map { key ->
      val trusting = key.getElementsByTagNameNS("*", "trusting").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
      val scopes = (if (trusting.isEmpty() || coordinates.any(key::hasAttribute)) listOf(key) else emptyList()) + trusting
      TrustedKey(key.getAttribute("id").uppercase(), scopes.any { it.getAttribute("version").isEmpty() || it.getAttribute("regex").toBoolean() }, scopes.map(::scope))
    }
    val pinned = elements("pgp").map { pgp ->
      val component = pgp.parentNode.parentNode as Element
      TrustedKey(pgp.getAttribute("value").uppercase(), false, listOf("${component.getAttribute("group")}:${component.getAttribute("name")}:${component.getAttribute("version")}"))
    }
    return (keys + pinned).groupBy { it.id }.map { (id, same) ->
      TrustedKey(id, same.any { it.versionFree }, same.flatMap { it.scopes })
    }.sortedBy { it.id }
  }

  /** Fetches a key from one server by fingerprint, primary or subkey; null when the server has no copy. */
  fun interface Fetcher { fun fetch(server: String, fingerprint: String): String? }

  /** Where gradle/gradle's own policy is read from, for the corroboration check. */
  const val GRADLE_POLICY = "https://raw.githubusercontent.com/gradle/gradle/master/gradle/verification-metadata.xml"

  /**
   * The keys the version-scoped list's header names as trusted on gradle/gradle's corroboration
   * alone: every fingerprint in the comment block that starts "Not listed, by decision", which the
   * fleet's lists carry in that shape (README, step 2). Empty when the file or the block is absent.
   */
  fun corroborationKeys(versionScopedKeys: File): Set<String> {
    if (!versionScopedKeys.isFile) return emptySet()
    val lines = versionScopedKeys.readLines()
    val start = lines.indexOfFirst { it.trimStart().startsWith("#") && it.contains("Not listed, by decision") }
    if (start < 0) return emptySet()
    val block = lines.drop(start).takeWhile { it.trimStart().startsWith("#") }.joinToString(" ")
    return Regex("\\b[0-9A-Fa-f]{40}\\b").findAll(block).map { it.value.uppercase() }.toSet()
  }

  /** One version-free scope of a trusted key in gradle/gradle's policy: a group, literal or regex, and perhaps a name. */
  class UpstreamScope(val group: String, val regex: Boolean, val name: String?) {
    /** Whether this scope lies in [group]: the literal, or a regex it matches. Names are not read: the trust is in the key's owner within the group. */
    fun covers(group: String): Boolean = if (regex) runCatching { Regex(this.group).matches(group) }.getOrDefault(false) else this.group == group
    override fun toString() = if (regex) "regex $group" else group + (name?.let { ":$it" } ?: "")
  }

  /** gradle/gradle's trusted keys by id, with their version-free scopes; a scope with a version corroborates nothing. */
  fun upstreamScopes(xml: String): Map<String, List<UpstreamScope>> {
    val document = DocumentBuilderFactory.newInstance().run {
      isNamespaceAware = true
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
    }
    val keys = document.getElementsByTagNameNS("*", "trusted-key").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    val coordinates = listOf("group", "name", "version", "file", "regex")
    return keys.groupBy({ it.getAttribute("id").uppercase() }) { key ->
      val trusting = key.getElementsByTagNameNS("*", "trusting").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
      val scopes = (if (trusting.isEmpty() || coordinates.any(key::hasAttribute)) listOf(key) else emptyList()) + trusting
      scopes.filter { it.getAttribute("version").isEmpty() && it.getAttribute("group").isNotEmpty() }
        .map { UpstreamScope(it.getAttribute("group"), it.getAttribute("regex").toBoolean(), it.getAttribute("name").ifEmpty { null }) }
    }.mapValues { (_, lists) -> lists.flatten() }
  }

  enum class Corroboration { VOUCHED, OTHER_GROUPS, ABSENT }

  /** The group a policy scope names: its literal, or the literal a regex of Gradle's shape starts with. */
  internal fun groupOf(scope: String): String? {
    val group = Regex("group=\"([^\"]*)\"").find(scope)?.groupValues?.get(1) ?: return null
    val regex = Regex("regex=\"([^\"]*)\"").find(scope)?.groupValues?.get(1)?.toBoolean() ?: false
    return if (regex) group.removePrefix("^").substringBefore("(").replace("[.]", ".") else group
  }

  /**
   * Whether gradle/gradle still vouches for [key] where this policy trusts it: every version-free
   * scope of ours lies in a group gradle/gradle names for the key. Their trust establishes the
   * key's owner, so a rule of theirs on one module vouches for the group.
   */
  fun corroboration(key: TrustedKey, upstream: Map<String, List<UpstreamScope>>): Pair<Corroboration, String> {
    val theirs = upstream[key.id] ?: return Corroboration.ABSENT to "no longer in gradle/gradle's policy"
    val ours = key.scopes.filter { !it.contains("version=") }.mapNotNull(::groupOf).distinct()
    val uncovered = ours.filter { group -> theirs.none { it.covers(group) } }
    return if (uncovered.isEmpty()) Corroboration.VOUCHED to "vouched by gradle/gradle for ${theirs.joinToString(", ")}"
    else Corroboration.OTHER_GROUPS to "gradle/gradle vouches for it in ${theirs.joinToString(", ")}, not in ${uncovered.joinToString(", ")}"
  }

  /** GET [url] as text; anything but 200 is an error. */
  fun fetchText(url: String, timeout: Duration = Duration.ofSeconds(20)): String {
    val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", "sava-build verificationKeyStatus").GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) throw IllegalStateException("$url answered ${response.statusCode()}")
    return response.body()
  }

  /**
   * HKP's machine-readable get by fingerprint, which both servers answer, keys.openpgp.org's Hagrid
   * and keyserver.ubuntu.com's Hockeypuck, for a primary or a subkey fingerprint; 404 is no copy.
   */
  class HttpFetcher(private val timeout: Duration = Duration.ofSeconds(20)) : Fetcher {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun fetch(server: String, fingerprint: String): String? {
      val url = "${server.trimEnd('/')}/pks/lookup?op=get&options=mr&search=0x$fingerprint"
      val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", "sava-build verificationKeyStatus").GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      return when {
        response.statusCode() == 200 && response.body().contains("-----BEGIN PGP PUBLIC KEY BLOCK-----") -> response.body()
        response.statusCode() == 404 -> null
        else -> throw IllegalStateException("$url answered ${response.statusCode()}")
      }
    }
  }

  /** Looks every trusted key up on every server and classifies it; a server that cannot be reached counts as not holding the key, with its error in the reason. */
  fun findings(keys: List<TrustedKey>, servers: List<String>, fetcher: Fetcher, now: Instant = Instant.now()): List<Finding> = keys.map { key ->
    val copies = mutableListOf<Pair<String, OpenPgpKeys.PublicKey>>()
    val errors = mutableListOf<String>()
    servers.forEach { server ->
      try {
        val armored = fetcher.fetch(server, key.id)
        if (armored != null) {
          // every copy of the key the answer holds counts: a server can return the same key more than once
          val matching = OpenPgpKeys.parse(armored).filter { key.id in it.fingerprints }
          if (matching.isEmpty()) errors += "$server returned a key block that does not carry ${key.id}" else matching.forEach { copies += server to it }
        }
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
      } catch (e: Exception) {
        errors += "$server: ${e.message ?: e.javaClass.simpleName}"
      }
    }
    classify(key, copies, errors, now)
  }

  internal fun classify(key: TrustedKey, copies: List<Pair<String, OpenPgpKeys.PublicKey>>, errors: List<String>, now: Instant): Finding {
    if (copies.isEmpty()) {
      return if (errors.isEmpty()) Finding(key, State.ABSENT, null, null, null, "no key server holds this key", emptyList())
      else Finding(key, State.UNDECIDED, null, null, null, "a server did not answer, so the status is undecided: " + errors.joinToString("; "), emptyList())
    }
    // The policy may name a subkey's fingerprint: its own revocation and expiry count, as the primary's always do.
    fun subkey(k: OpenPgpKeys.PublicKey) = k.subkeys.firstOrNull { it.fingerprint == key.id }
    // Every revocation any copy carries, the primary's and the named subkey's: a hard one outranks a soft one, then the earliest.
    val revocations = copies.flatMap { (_, k) -> listOfNotNull(k.revocation, subkey(k)?.revocation) }
    val revocation = revocations.filter { it.hard }.minByOrNull { it.at } ?: revocations.minByOrNull { it.at }
    // A key expires with its primary or, when the policy names a subkey, with that subkey too, whichever is first.
    // A newer self-signature can extend an expiry, so the furthest copy decides and a self-signed copy without
    // one means none; a copy with no self-signature over the primary (keys.openpgp.org strips unverified user
    // ids) says nothing about the primary and is passed over, and when no copy speaks the expiry is unknown.
    fun furthest(expiries: List<Instant?>): Instant? = if (expiries.isEmpty() || expiries.any { it == null }) null else expiries.filterNotNull().max()
    val selfSigned = copies.filter { (_, k) -> k.selfSigned }
    val primaryExpiry = furthest(selfSigned.map { (_, k) -> k.expiresAt })
    val namedSubkeyExpiry = furthest(copies.mapNotNull { (_, k) -> subkey(k) }.map { it.expiresAt })
    val expiresAt = listOfNotNull(primaryExpiry, namedSubkeyExpiry).minOrNull()
    val unknownExpiry = selfSigned.isEmpty()
    val holder = copies.firstNotNullOfOrNull { (_, k) -> k.userIds.firstOrNull() }
    val servers = copies.map { it.first }
    // A revocation on any copy decides; short of one, a server that did not answer might have held it.
    val state = when {
      revocation != null -> State.REVOKED
      errors.isNotEmpty() -> State.UNDECIDED
      expiresAt != null && expiresAt.isBefore(now) -> State.EXPIRED
      else -> State.CURRENT
    }
    // When the policy names the primary, a signing subkey that expired or was revoked is worth a look too:
    // the publisher may still sign with it, and Gradle accepts those signatures. An encryption-only subkey
    // signs nothing and is passed over. The furthest copy decides.
    val subkeyNotes = if (state != State.CURRENT || subkey(copies.first().second) != null) emptyList() else
      copies.flatMap { (_, k) -> k.subkeys }.filter { it.canSign }.groupBy { it.fingerprint }.toSortedMap().mapNotNull { (fingerprint, versions) ->
        val revoked = versions.mapNotNull { it.revocation }.minByOrNull { it.at }
        val expiry = if (versions.any { it.expiresAt == null }) null else versions.mapNotNull { it.expiresAt }.maxOrNull()
        when {
          revoked != null -> "subkey $fingerprint revoked ${revoked.at}"
          expiry != null && expiry.isBefore(now) -> "subkey $fingerprint expired $expiry"
          else -> null
        }
      }
    val notes = listOfNotNull(revocation?.reason) +
      (if (state == State.UNDECIDED) listOf("a server did not answer, so the status is undecided: " + errors.joinToString("; ")) else errors) +
      (if (unknownExpiry) listOf("no self-signature over the primary in any server copy, so its expiry is unknown") else emptyList())
    return Finding(key, state, holder, expiresAt, revocation?.at, notes.joinToString("; ").ifEmpty { null }, servers, subkeyNotes, unknownExpiry)
  }

  /** The receipt: one tab-separated line per key, dated, for the release checklist's record; [corroboration] by key id for the keys the list's header names. */
  fun receipt(findings: List<Finding>, now: Instant, corroboration: Map<String, String> = emptyMap()): String = buildString {
    appendLine("checked\tfingerprint\tstate\tversion_free\tholder\texpires\trevoked\treason\tservers\tsubkeys\tcorroboration")
    findings.forEach { f ->
      appendLine(listOf(now.toString(), f.key.id, f.state.name, f.key.versionFree.toString(), f.holder ?: "", f.expiresAt?.toString() ?: "",
        f.revokedAt?.toString() ?: "", f.reason ?: "", f.servers.joinToString(","), f.subkeyNotes.joinToString("; "), corroboration[f.key.id] ?: "")
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
    }
  }

  fun describe(f: Finding): String {
    val scopes = f.key.scopes.joinToString(", ")
    return "${f.key.id}${f.holder?.let { " ($it)" } ?: ""} ${f.state.name.lowercase()}" +
      (f.revokedAt?.let { ", revoked $it" } ?: "") + (f.expiresAt?.let { ", expires $it" } ?: "") +
      (f.reason?.let { ": $it" } ?: "") + (if (f.subkeyNotes.isEmpty()) "" else " (${f.subkeyNotes.joinToString("; ")})") +
      "; trusted ${if (f.key.versionFree) "for every version" else "at exact versions"} of $scopes"
  }
}
