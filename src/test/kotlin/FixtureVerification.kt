import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.util.Date
import java.util.HexFormat
import java.util.Properties

/**
 * Dependency verification for the TestKit fixtures: a fixture build runs under a strict policy, so
 * what it downloads is checked against a reviewed key or a pinned checksum before it runs (README,
 * Developing sava-build). The few fixtures whose subject is a policy of their own do not, and
 * download nothing ([savaBuildRunnerWithOwnPolicy]).
 *
 * The policy is test-only and lives in src/test/resources/fixture-policy; this repository's own
 * policy stays what the build that publishes resolves. [install] writes it into a fixture as the
 * three files and the declaration the plugin's guard asks of any consumer, so the guard passes in
 * a fixture that applies the settings plugin and runs `check`, `qualityGate` or `image`, and
 * FixtureVerificationTest runs the guard's rules over what is installed.
 *
 * What a fixture serves itself is unsigned: the plugin's test publication, and the stub modules a
 * test writes. No policy may exempt those (the guard refuses a `<trust>` entry and a pin of the test
 * publication, and the jar changes with every edit), so this signs them. Each test JVM makes one
 * OpenPGP key, holds its private half in memory only, signs a copy of the test publication
 * ([pluginRepository]) and whatever a test hands to [signRepository], and [install] trusts that key
 * for the groups in [LOCAL_GROUPS]. A key nobody else holds vouches for nothing else.
 */
object FixtureVerification {

  /** The groups of the modules fixtures serve themselves; the run's key is trusted for these alone. */
  val LOCAL_GROUPS = listOf("software.sava", "systems.glam", "org.example")

  private const val MODE = "org.gradle.dependency.verification"
  const val DECLARATION = "$MODE=strict"
  private const val METADATA = "gradle/verification-metadata.xml"
  private const val KEYRING = "gradle/verification-keyring.keys"
  private const val VERSION_SCOPED_KEYS = "gradle/verification/version-scoped-keys.txt"

  private class RunKey {
    private val pair = JcaPGPKeyPair(
      PublicKeyPacket.VERSION_4,
      PublicKeyAlgorithmTags.RSA_GENERAL,
      KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
      Date(),
    )
    private val signer = JcaPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256)
    private val ring = PGPKeyRingGenerator(
      PGPSignature.POSITIVE_CERTIFICATION,
      pair,
      "sava-build fixture key, one test run <fixture@sava-build.invalid>",
      JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1),
      null,
      null,
      signer,
      null,
    ).generatePublicKeyRing()

    val fingerprint: String = HexFormat.of().withUpperCase().formatHex(ring.publicKey.fingerprint)

    val publicKey: String = armored { ring.encode(it) }

    fun signature(content: ByteArray): String = armored { out ->
      PGPSignatureGenerator(signer, pair.publicKey).run {
        init(PGPSignature.BINARY_DOCUMENT, pair.privateKey)
        update(content)
        generate().encode(out)
      }
    }

    private fun armored(write: (ArmoredOutputStream) -> Unit): String =
      ByteArrayOutputStream().also { bytes -> ArmoredOutputStream(bytes).use(write) }.toString(Charsets.US_ASCII)
  }

  private val key by lazy { RunKey() }

  /** The fingerprint of this run's key, as the installed policy names it. */
  val fingerprint: String get() = key.fingerprint

  /** A detached, armored signature of [content] by this run's key: what a repository serves as `<file>.asc`. */
  fun signature(content: ByteArray): String = key.signature(content)

  /** Writes `<file>.asc` beside [file], replacing one that a change to the file made stale. */
  fun sign(file: File) {
    File(file.path + ".asc").writeText(signature(file.readBytes()))
  }

  /**
   * Signs every artifact and metadata file of the Maven repository at [repository]: each file in a
   * version directory other than a signature, a checksum or the provenance sidecar. A test that
   * edits a signed file afterwards calls [sign] on it again.
   */
  fun signRepository(repository: File) {
    repository.walkTopDown()
      .filter { it.isFile && it.name.startsWith("${it.parentFile.parentFile.name}-${it.parentFile.name}") }
      .filterNot { file -> UNSIGNED_SUFFIXES.any(file.name::endsWith) }
      .forEach(::sign)
  }

  private val UNSIGNED_SUFFIXES = listOf(".asc", ".md5", ".sha1", ".sha256", ".sha512", "-provenance.tsv")

  private var slotLock: FileLock? = null

  /** The build's directory for what this object keeps outside the fixtures. */
  private val workDirectory: File get() = File(savaBuildTestProperty("savaBuild.fixtureVerificationDir"))

  /**
   * The Gradle user home of the fixtures that run outside the fixture policy
   * ([savaBuildRunnerWithOwnPolicy]). Only a strict build downloads into it: those fixtures run
   * offline, so everything they can resolve from it was verified when it arrived.
   */
  val ownPolicyGradleHome: File get() = File(workDirectory, "own-policy-gradle-home")

  /**
   * A signed copy of the plugin's test publication, which every fixture resolves the plugin from.
   * It is one of a few numbered directories under the build directory, claimed by a file lock
   * for the life of this JVM, so that the test JVMs of one run, each with a key of its own, never
   * write the same copy. Settings scripts name it through a Gradle property, not by its path
   * ([savaBuildPluginManagement]).
   */
  val pluginRepository: File by lazy {
    val base = File(workDirectory, "repository").apply { mkdirs() }
    val slot = generateSequence(0) { it + 1 }.take(64).firstNotNullOfOrNull { n ->
      val channel = FileChannel.open(base.resolve("slot-$n.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock = runCatching { channel.tryLock() }.getOrNull()
      if (lock == null) {
        channel.close()
        null
      } else {
        slotLock = lock
        base.resolve("slot-$n")
      }
    } ?: error("no free slot for a signed copy of the test publication under $base")
    slot.deleteRecursively()
    val published = File(savaBuildTestProperty("savaBuild.testRepo"))
    check(published.copyRecursively(slot)) { "failed to copy the test publication from $published to $slot" }
    signRepository(slot)
    slot
  }

  /**
   * Installs the fixture policy in the build rooted at [projectDir]: the three policy files, with
   * this run's key added for the local groups, and the declaration in `gradle.properties`. A file
   * that already has these bytes is left alone, so a second call changes nothing a fixture's git
   * tree or the configuration cache could see. Called before a fixture commits its tree and before
   * its first build; [savaBuildRunner] checks that it was.
   */
  fun install(projectDir: File) {
    writeIfChanged(File(projectDir, METADATA), metadata)
    writeIfChanged(File(projectDir, KEYRING), keyring)
    writeIfChanged(File(projectDir, VERSION_SCOPED_KEYS), resource("version-scoped-keys.txt"))
    val properties = File(projectDir, "gradle.properties")
    val text = if (properties.isFile) properties.readText() else ""
    if (DECLARATION !in text.lines()) {
      properties.writeText(text + (if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + "$DECLARATION\n")
    }
  }

  /**
   * Whether the build rooted at [projectDir] holds what [install] writes, unchanged: the policy
   * with this run's key, the keyring and the list, byte for byte, and a `gradle.properties` whose
   * verification mode is strict as Gradle reads the file, where the last value of a key wins and
   * the `systemProp.` spelling sets the mode too.
   */
  fun installedIn(projectDir: File): Boolean {
    fun holds(path: String, text: String) = File(projectDir, path).let { it.isFile && it.readText() == text }
    val properties = File(projectDir, "gradle.properties").takeIf { it.isFile }
      ?.let { file -> Properties().apply { file.reader().use(::load) } } ?: return false
    return holds(METADATA, metadata) && holds(KEYRING, keyring) && holds(VERSION_SCOPED_KEYS, resource("version-scoped-keys.txt")) &&
      properties.getProperty(MODE) == "strict" && properties.getProperty("systemProp.$MODE") == null
  }

  /** The reviewed policy with this run's key trusted for the local groups. */
  private val metadata: String by lazy {
    val reviewed = resource("verification-metadata.xml")
    val close = "      </trusted-keys>"
    check(reviewed.split(close).size == 2) { "the fixture policy has no single trusted-keys element" }
    val local = buildString {
      appendLine("         <trusted-key id=\"$fingerprint\">")
      LOCAL_GROUPS.forEach { appendLine("            <trusting group=\"$it\"/>") }
      appendLine("         </trusted-key>")
    }
    reviewed.replace(close, local + close)
  }

  private val keyring: String by lazy {
    resource("verification-keyring.keys").trimEnd('\n') + "\n\n" + key.publicKey
  }

  /** A file of the committed fixture policy, by name. */
  fun resource(name: String): String =
    (FixtureVerification::class.java.getResource("/fixture-policy/$name") ?: error("no test resource /fixture-policy/$name"))
      .readText()

  private fun writeIfChanged(file: File, text: String) {
    if (!file.isFile || file.readText() != text) {
      file.parentFile.mkdirs()
      file.writeText(text)
    }
  }
}
