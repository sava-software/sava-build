package software.sava.build.verification

import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The rules a repository's dependency verification policy keeps, as [VerificationPolicyTask]
 * checks them in every consumer build and this repository's own test suite checks them against
 * its own policy; sava-build's README (Verifying sava artifacts) says why each holds.
 */
object VerificationPolicyRules {

  const val METADATA = "gradle/verification-metadata.xml"
  const val KEYRING = "gradle/verification-keyring.keys"
  const val VERSION_SCOPED_KEYS = "gradle/verification/version-scoped-keys.txt"
  const val README = "sava-build's README, Verifying sava artifacts,"
  private val MODE_PROPERTIES = listOf("org.gradle.dependency.verification", "systemProp.org.gradle.dependency.verification")
  /** Gradle refuses any other value when the build starts, before this guard runs. */
  private val MODES = setOf("strict", "lenient", "off")

  /** A verification mode a committed gradle.properties sets: [file] in [build], "." for the root. */
  private data class ModeSetting(val build: String, val file: File, val key: String, val value: String) {
    private val mode = value.trim().lowercase()
    val strict = mode == "strict"
    val accepted = mode in MODES
  }

  /**
   * The standalone builds of a checkout: direct child directories with a settings script of
   * their own, each relative to [root], included by the root build or not. Gradle reads only
   * the invoking build's `gradle/` directory, and a child invoked directly (`-p jmh`) is
   * verified by nothing else, so these must hold symlinks to the root policy. A child holding
   * a `.git` entry is another checkout, a clone or a worktree, not a build of this one.
   */
  fun standaloneBuilds(root: File): List<String> =
    (root.listFiles() ?: emptyArray())
      .filter { it.isDirectory && !it.name.startsWith(".") && it.name != "build" && it.name != "buildSrc" }
      .filter { candidate -> listOf("settings.gradle.kts", "settings.gradle").any { candidate.resolve(it).isFile } }
      .filterNot { it.resolve(".git").exists() }
      .map { it.name }
      .sorted()

  /**
   * The problems with the policy under [root]: none for a policy that keeps the rules, its
   * declaration in the root gradle.properties included, none for a repository with no policy and
   * nothing of one, which the CI notice warns about, and none for a symlinked one, which is a
   * standalone build's view of its parent's policy. A keyring, a version-scoped list or the
   * declaration without the policy file is a policy deleted or never committed, not a repository
   * yet to adopt one, and a problem. [standaloneBuilds] are the builds [standaloneBuilds]
   * discovered, relative to the root.
   */
  fun problems(root: File, standaloneBuilds: List<String>): List<String> {
    val metadata = root.resolve(METADATA)
    val metadataPath = metadata.toPath()
    if (Files.isSymbolicLink(metadataPath)) {
      // A standalone build that applies the settings plugin itself links its policy to an
      // enclosing build's; its guard follows the link and runs the rules there, where the link
      // is checked back. Any other target is a policy in disguise, and a dangling link leaves
      // Gradle verifying nothing.
      val target = runCatching { metadataPath.toRealPath() }.getOrNull()
        ?: return listOf("$METADATA is a symlink to a missing target, so Gradle verifies nothing: restore the shared policy it points at.")
      val policyRoot = target.parent?.parent
      val thisRoot = root.toPath().toRealPath()
      if (policyRoot == null || target.parent.fileName.toString() != "gradle" || target.fileName.toString() != metadata.name ||
        thisRoot.parent != policyRoot
      ) {
        return listOf("$METADATA is a symlink to $target; a standalone build links to its parent directory's $METADATA, and a repository's own policy is a plain file.")
      }
      // The parent's rules check this build back, its keyring link and its gradle.properties,
      // only when they discover it among the parent's standalone builds.
      val enclosing = policyRoot.toFile()
      val siblings = standaloneBuilds(enclosing)
      if (thisRoot.fileName.toString() !in siblings) {
        return listOf("$METADATA links to the parent directory's policy, but that build's guard never checks this one back: a standalone build is a direct child with a settings script and no .git entry of its own.")
      }
      // Named as the parent's problems: this build's own gradle.properties declares nothing,
      // and a path in a message is relative to the parent.
      return problems(enclosing, siblings).map { "in the parent build at ../: $it" }
    }
    val keyring = root.resolve(KEYRING)
    val versionScopedKeys = root.resolve(VERSION_SCOPED_KEYS)
    val builds = listOf(".") + standaloneBuilds
    // The mode each committed gradle.properties sets, in either spelling Gradle reads. Strict in
    // the root's is the policy's declaration: Gradle's default while the file is present and
    // nothing without it, so the line changes nothing for Gradle and tells this guard a policy
    // belongs here. Any other value, in any build's, relaxes that build.
    val modes = builds.flatMap { build ->
      val file = root.resolve(build).resolve("gradle.properties").normalize()
      val properties = Properties().apply { if (file.isFile) file.reader().use { load(it) } }
      MODE_PROPERTIES.mapNotNull { key -> properties.getProperty(key)?.let { ModeSetting(build, file, key, it) } }
    }
    fun setting(it: ModeSetting) = "${it.file.relativeTo(root)} sets ${it.key}=${it.value}"
    val invalid = modes.filterNot { it.accepted }.map {
      "${setting(it)}, which is not a mode Gradle accepts (strict, lenient or off); the committed value is strict."
    }
    val relaxed = modes.filter { it.accepted && !it.strict }
    if (!metadata.isFile) {
      // No policy. With no keyring, no list, no standalone build's link and no declaration either,
      // the repository has not adopted one and the CI notice warns. Any of them without the file
      // is a policy deleted or never committed: a keyring or a link verifies nothing on its own,
      // and without the file Gradle verifies nothing whatever mode gradle.properties sets.
      val remnants = listOf(keyring, versionScopedKeys).filter { it.isFile } +
        standaloneBuilds.flatMap { build -> listOf(metadata, keyring).map { root.resolve(build).resolve("gradle").resolve(it.name) } }
          .filter { Files.isSymbolicLink(it.toPath()) || it.exists() }
      return remnants.map {
        val left = if (Files.isSymbolicLink(it.toPath())) "is a link left behind" else "is present"
        "$METADATA is missing while ${it.relativeTo(root)} $left: a policy was deleted or never committed " +
          "(a deny-by-default .gitignore needs allow-rules for all three files and the gradle/verification/ directory). " +
          "Restore it, or remove what is left of it."
      } + modes.filter { it.build == "." && it.strict }.map {
        "${setting(it)}, the declaration of a policy, but $METADATA is missing, so Gradle verifies nothing: " +
          "restore the policy, or remove the property when the repository stops verifying."
      } + relaxed.map {
        "${setting(it)}, a committed verification setting with no policy to apply it to: remove the line, or adopt a policy; " +
          "a committed gradle.properties never relaxes the mode."
      } + invalid
    }
    val problems = mutableListOf<String>()
    listOf(keyring, versionScopedKeys).filterNot { it.isFile }.forEach {
      problems += "${it.relativeTo(root)} is missing; $README describes the files the policy needs" +
        if (it == versionScopedKeys) " (comments alone, when no key is version-scoped)." else "."
    }
    if (problems.isNotEmpty()) {
      return problems
    }
    val document = DocumentBuilderFactory.newInstance().run {
      isNamespaceAware = true
      setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      newDocumentBuilder().parse(metadata)
    }
    fun elements(name: String) = document.getElementsByTagNameNS("*", name).let { nodes ->
      (0 until nodes.length).map { nodes.item(it) as Element }
    }
    fun Element.attributesText() = (0 until attributes.length).map { attributes.item(it) }
      .filter { it.nodeName != "id" }
      .joinToString(" ") { "${it.nodeName}=\"${it.nodeValue}\"" }

    // Gradle reads these settings from the raw element text without trimming, a later element
    // overrides an earlier one, and a commented-out element leaves Gradle's default in force.
    // So each must be one active <configuration> child, checked on the parsed document, and
    // appear in the file only as the exact expected text, checked on the raw text with its
    // comments removed, where neither padding nor an entity can hide a value Gradle would read
    // differently, and a comment naming an element counts for nothing, as it does for Gradle.
    val raw = metadata.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    fun requireExactly(element: String, exact: String, why: String) {
      val active = elements(element)
      val single = active.size == 1 && (active[0].parentNode as? Element)?.localName == "configuration"
      if (!single || Regex("<$element[\\s/>]").findAll(raw).count() != 1 || !raw.contains(exact)) {
        problems += "$exact must appear once, as an active <configuration> setting written exactly so: $why"
      }
    }
    requireExactly("verify-metadata", "<verify-metadata>true</verify-metadata>", "otherwise POM and module metadata go unverified.")
    requireExactly("verify-signatures", "<verify-signatures>true</verify-signatures>",
      "a checksum alone cannot tell a publisher's artifact from a substitute.")
    requireExactly("keyring-format", "<keyring-format>armored</keyring-format>", "the reviewed keyring is $KEYRING.")
    requireExactly("key-servers", "<key-servers enabled=\"false\"/>", "keys come from the committed keyring, never from a key server.")
    elements("ignored-key").forEach {
      problems += "<ignored-key id=\"${it.getAttribute("id")}\"> skips that key's signatures; regeneration writes it when a key is " +
        "missing. Authenticate the key and add it to $KEYRING instead."
    }
    elements("trust").forEach {
      problems += "<trusted-artifacts> entry ${it.attributesText()} exempts artifacts from signature verification; pin or trust them by key instead."
    }
    elements("pgp").forEach { pgp ->
      val component = pgp.parentNode.parentNode as Element
      problems += "<pgp value=\"${pgp.getAttribute("value")}\"> trusts a key for " +
        "${component.getAttribute("group")}:${component.getAttribute("name")}:${component.getAttribute("version")} only. " +
        "Move it to a trusted-key scoped to the publisher's group or module, or to an exact-version rule for a key in $VERSION_SCOPED_KEYS."
    }
    // A regeneration run with -PsavaBuildLocalRepo pins the unsigned local test publication.
    // (Key ids need no rule: Gradle refuses to read a trusted-key id shorter than 160 bits.)
    elements("component").filter { it.getAttribute("version") == "0.0.0-test" }.forEach {
      problems += "component ${it.getAttribute("group")}:${it.getAttribute("name")}:0.0.0-test pins the unsigned local test publication, " +
        "which a regeneration run with -PsavaBuildLocalRepo writes: remove it, and regenerate without the property."
    }
    listOf("md5", "sha1").forEach { digest ->
      elements(digest).forEach { element ->
        val artifact = element.parentNode as Element
        val component = artifact.parentNode as Element
        problems += "<$digest> on ${component.getAttribute("group")}:${component.getAttribute("name")}:${component.getAttribute("version")} " +
          "${artifact.getAttribute("name")} pins a digest with known collisions; an unsigned artifact is pinned by <sha256> only."
      }
    }

    // Gradle compares key ids without regard to case, and reads regex like Boolean.parseBoolean.
    val listed = versionScopedKeys.readLines().map(String::trim)
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .map { it.split(Regex("\\s+"), limit = 2) }
      .associate { it[0].uppercase() to it.getOrElse(1) { "" }.trim() }
    // A trusted-key's own attributes are a scope too, even when it also has <trusting> children.
    val coordinates = listOf("group", "name", "version", "file", "regex")
    val scopes = elements("trusted-key").flatMap { key ->
      val id = key.getAttribute("id").uppercase()
      val trusting = key.getElementsByTagNameNS("*", "trusting")
      val own = if (trusting.length == 0 || coordinates.any(key::hasAttribute)) listOf(id to key) else emptyList()
      own + (0 until trusting.length).map { id to trusting.item(it) as Element }
    }
    // The one regex shape allowed is the one Gradle writes: a literal group and its subgroups,
    // the group of at least two segments, since one would trust a whole top-level namespace.
    val groupAndSubgroups = Regex("""\^[A-Za-z0-9_-]+(\[\.\][A-Za-z0-9_-]+)+\(\$\|\(\[\.\]\.\*\)\)""")
    scopes.forEach { (id, scope) ->
      val version = scope.getAttribute("version")
      val regex = scope.getAttribute("regex").toBoolean()
      when {
        scope.getAttribute("group").isEmpty() ->
          problems += "trusted-key $id has a rule without a group${scope.attributesText().let { if (it.isEmpty()) "" else " ($it)" }}: " +
            "Gradle trusts a key for nothing without a group, name, version or file, and this policy wants a group. " +
            "Scope it to the publisher's group or module."
        regex && (!groupAndSubgroups.matches(scope.getAttribute("group")) || listOf("name", "version", "file").any(scope::hasAttribute)) ->
          problems += "trusted-key $id has a regex rule (${scope.attributesText()}) other than a literal group and its subgroups, " +
            "such as group=\"^com[.]example($|([.].*))\": write it without a regex, or in that form."
        scope.hasAttribute("file") ->
          problems += "trusted-key $id trusts ${scope.attributesText()} by file name; trust whole modules, and pin single files with a component entry."
        id in listed && (version.isEmpty() || regex) ->
          problems += "trusted-key $id trusts ${scope.attributesText()} beyond exact versions, but $VERSION_SCOPED_KEYS " +
            "lists it (${listed[id]}). Pin the exact version(s) it signed, without a regex."
        id !in listed && version.isNotEmpty() ->
          problems += "trusted-key $id trusts ${scope.attributesText()} only at that version. Publisher keys stay version-free " +
            "so a bump signed by the same key needs no edit: drop the version, or list the key in $VERSION_SCOPED_KEYS with the reason."
      }
    }
    (listed.keys - scopes.map { it.first }.toSet()).forEach {
      problems += "$VERSION_SCOPED_KEYS lists $it, which no trusted-key uses any more: remove the line."
    }

    // The declaration; a root value that is not strict is reported as such below.
    if (modes.none { it.build == "." }) {
      problems += "gradle.properties does not set org.gradle.dependency.verification=strict, the policy's declaration: add the line, " +
        "which changes nothing for Gradle (strict is its default while $METADATA is present, and it verifies nothing without it) " +
        "and makes this guard fail check should the file go missing."
    }
    problems += relaxed.map {
      "${setting(it)}; verification stays strict for every committed build. " +
        "Only a -PsavaBuildLocalRepo run passes --dependency-verification lenient, on its own command line."
    }
    problems += invalid

    standaloneBuilds.forEach { build ->
      listOf(metadata, keyring).forEach { shared ->
        val copy = root.resolve(build).resolve("gradle").resolve(shared.name)
        val path = copy.toPath()
        // a relative link, which a clone at another path keeps; an absolute one dangles there
        val link = if (Files.isSymbolicLink(path)) Files.readSymbolicLink(path) else null
        if (link == null || link.isAbsolute || !Files.exists(path) || path.toRealPath() != shared.toPath().toRealPath()) {
          problems += "${copy.relativeTo(root)} must be a relative symlink to ${shared.relativeTo(copy.parentFile)}, so the standalone build " +
            "$build enforces the one policy. Merge anything written into it into ${shared.relativeTo(root)}, then re-create the link."
        }
      }
    }

    return problems
  }
}
