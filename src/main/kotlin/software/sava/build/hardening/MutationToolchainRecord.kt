package software.sava.build.hardening

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.Properties
import java.util.jar.JarFile

/**
 * Portable identity of the mutation engine that last verified a committed suite record.
 *
 * Unlike [PitestEvidence.toolClasspathSha256], the artifact fingerprint deliberately
 * excludes absolute paths. Gradle cache locations differ between machines and therefore
 * cannot be committed as provenance. Artifact order and content remain significant.
 */
internal data class MutationToolchainRecord(
  val pitestVersion: String,
  val junitPluginVersion: String,
  val toolClasspathSha256: String,
  val arcMutateBaseVersion: String?,
  val arcMutateLicenceSha256: String?,
  val arcMutateLicenceExpires: LocalDate?,
) {

  init {
    requirePlainValue("pitest", pitestVersion)
    requirePlainValue("junitPlugin", junitPluginVersion)
    requireSha256("toolClasspathSha256", toolClasspathSha256)

    val licenceFields = listOf(
      arcMutateBaseVersion,
      arcMutateLicenceSha256,
      arcMutateLicenceExpires,
    )
    require(licenceFields.all { it == null } || licenceFields.all { it != null }) {
      "ArcMutate base, licence hash, and expiry must be either all present or all absent"
    }
    arcMutateBaseVersion?.let {
      requirePlainValue("arcMutateBase", it)
      require(it != ABSENT) { "arcMutateBase uses the reserved value '$ABSENT'" }
    }
    arcMutateLicenceSha256?.let { requireSha256("arcMutateLicenceSha256", it) }
  }

  /** Canonical schema-1 TSV. */
  fun render(): String = buildString {
    appendLine("schema\t$SCHEMA")
    appendLine("pitest\t$pitestVersion")
    appendLine("junitPlugin\t$junitPluginVersion")
    appendLine("toolClasspathSha256\t$toolClasspathSha256")
    appendLine("arcMutateBase\t${arcMutateBaseVersion ?: ABSENT}")
    appendLine("arcMutateLicenceSha256\t${arcMutateLicenceSha256 ?: ABSENT}")
    appendLine("arcMutateLicenceExpires\t${arcMutateLicenceExpires ?: ABSENT}")
  }

  /** Stable digest stored in receipts when the full sidecar need not be embedded. */
  val identitySha256: String
    get() = PitestEvidence.sha256(render())

  internal data class ResolvedArcMutateLicence(
    val sha256: String,
    val expires: LocalDate,
    val type: ArcMutateLicenceType,
  ) {
    fun requireValidOn(date: LocalDate) {
      val failDate = when (type) {
        ArcMutateLicenceType.EVALUATION -> expires.plusDays(7)
        ArcMutateLicenceType.OSSS,
        ArcMutateLicenceType.COMMERCIAL,
        -> expires.plusMonths(1)
      }
      require(!date.isAfter(failDate)) {
        "ArcMutate $type licence expired on $expires and its grace period ended on " +
            "$failDate (observation date $date)"
      }
    }
  }

  internal enum class ArcMutateLicenceType {
    OSSS,
    COMMERCIAL,
    EVALUATION,
  }

  companion object {
    const val SCHEMA = "1"
    const val ABSENT = "absent"
    const val SUPPORTED_ARCMUTATE_BASE_VERSION = "1.7.2"
    private const val arcMutatePomProperties =
        "META-INF/maven/com.arcmutate/base/pom.properties"
    private const val pitestPomProperties =
        "META-INF/maven/org.pitest/pitest-command-line/pom.properties"
    private const val junitPluginPomProperties =
        "META-INF/maven/org.pitest/pitest-junit5-plugin/pom.properties"
    private const val pitestSentinel =
        "org/pitest/mutationtest/commandline/MutationCoverageReport.class"
    private const val junitPluginSentinel =
        "org/pitest/junit5/JUnit5TestPluginFactory.class"
    private val arcMutatePathPrefixes = listOf("com/arcmutate/", "com/groupcdg/arcmutate/")
    private val arcMutateServicePrefixes = listOf("com.arcmutate.", "com.groupcdg.arcmutate.")

    private val fieldNames = setOf(
      "schema",
      "pitest",
      "junitPlugin",
      "toolClasspathSha256",
      "arcMutateBase",
      "arcMutateLicenceSha256",
      "arcMutateLicenceExpires",
    )
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val certificateDatePattern = Regex("[0-9]{2}/[0-9]{2}/[0-9]{4}")
    private val certificateDateFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
      .appendPattern("dd/MM/uuuu")
      .toFormatter(Locale.ROOT)
      .withResolverStyle(ResolverStyle.STRICT)

    /**
     * Captures one current identity and mirrors ArcMutate 1.7.2's type-specific
     * post-expiry grace boundary. The raw named expiry remains in the identity.
     * [arcMutateBaseVersion] is the configured version; it is recorded only when a
     * certificate is present and the ArcMutate artifact is therefore selected.
     */
    fun capture(
      pitestVersion: String,
      junitPluginVersion: String,
      toolClasspath: Iterable<File>,
      arcMutateBaseVersion: String,
      arcMutateEnabled: Boolean,
      reportDirectory: File,
      projectBaseDirectory: File,
      lookupStartDirectory: File,
      observationDate: LocalDate,
    ): MutationToolchainRecord {
      val artifacts = toolClasspath.toList()
      val inspectedArtifacts = artifacts.filter { it.isFile || it.isDirectory }
          .map(::inspectToolArtifact)
      requireConfiguredVersion(
          "PIT command-line", pitestVersion, pitestPomProperties, pitestSentinel,
          inspectedArtifacts)
      requireConfiguredVersion(
          "PIT JUnit 5 plugin", junitPluginVersion, junitPluginPomProperties,
          junitPluginSentinel, inspectedArtifacts)
      val effectiveArcMutateBase = resolveEffectiveArcMutateBase(inspectedArtifacts)
      require((effectiveArcMutateBase != null) == arcMutateEnabled) {
        "configured ArcMutate activation ($arcMutateEnabled) disagrees with the effective PIT " +
            "tool classpath (${effectiveArcMutateBase?.let { "base ${it.version} in ${it.file}" } ?: "no base artifact"}); " +
            "do not add or remove mutation-engine artifacts through a late JavaExec classpath override"
      }
      val licence = if (effectiveArcMutateBase != null) {
        require(arcMutateBaseVersion == SUPPORTED_ARCMUTATE_BASE_VERSION &&
            effectiveArcMutateBase.version == arcMutateBaseVersion) {
          "licensed ArcMutate provenance supports base $SUPPORTED_ARCMUTATE_BASE_VERSION, but " +
              "hardening configured $arcMutateBaseVersion and the effective artifact reports " +
              effectiveArcMutateBase.version
        }
        requireNotNull(resolveEffectiveArcMutateLicence(
            reportDirectory, projectBaseDirectory, lookupStartDirectory)) {
          "ArcMutate was enabled but its effective licence resolver found no certificate"
        }
      } else null
      licence?.requireValidOn(observationDate)
      return MutationToolchainRecord(
        pitestVersion = pitestVersion,
        junitPluginVersion = junitPluginVersion,
        toolClasspathSha256 = orderedArtifactContentSha256(artifacts),
        arcMutateBaseVersion = effectiveArcMutateBase?.version,
        arcMutateLicenceSha256 = licence?.sha256,
        arcMutateLicenceExpires = licence?.expires,
      )
    }

    /**
     * Reproduces ArcMutate 1.7.2's effective search boundary. Report-local and
     * `.pitest` stores precede the ancestry certificate and can select among several
     * signed licences using vendor-internal product/expiry rules. Those stores are
     * intentionally refused: the remaining nearest ancestry certificate is then the
     * observable input we can bind exactly, including in N-1 consumers.
     */
    fun resolveEffectiveArcMutateLicence(
      reportDirectory: File,
      projectBaseDirectory: File,
      lookupStartDirectory: File,
    ): ResolvedArcMutateLicence? {
      val shadowCandidates = buildList {
        addAll(licenceFiles(reportDirectory.resolve("arcmutate-licences"), 1))
        addAll(licenceFiles(projectBaseDirectory.resolve(".pitest"), 3))
      }.distinctBy { it.canonicalFile }.sortedBy { it.absolutePath }
      require(shadowCandidates.isEmpty()) {
        "ArcMutate found unsupported higher-precedence licence candidate(s): " +
            shadowCandidates.joinToString() +
            ". Remove those stores so the committed toolchain identity can bind the " +
            "nearest ancestry licence selected from the pinned project working directory."
      }

      var directory: File? = lookupStartDirectory.canonicalFile
      repeat(13) {
        val current = directory ?: return null
        val currentLicence = current.resolve("arcmutate-licence.txt")
        val legacyLicence = current.resolve("cdg-pitest-licence.txt")
        if (currentLicence.exists() || legacyLicence.exists()) {
          val selected = when {
            currentLicence.isFile -> currentLicence
            legacyLicence.isFile -> legacyLicence
            else -> throw IllegalArgumentException(
                "ArcMutate licence lookup stopped at non-regular path(s) in $current")
          }
          return ResolvedArcMutateLicence(
              sha256 = sha256(selected.readBytes()),
              expires = parseCertificateExpiry(selected),
              type = parseCertificateType(selected),
          )
        }
        directory = current.parentFile
      }
      return null
    }

    /**
     * Hashes the effective tool artifacts in classpath order without including any
     * checkout or Gradle-cache path. Directory artifacts retain their relative tree
     * names through [PitestEvidence.fingerprintTree].
     */
    fun orderedArtifactContentSha256(toolClasspath: Iterable<File>): String {
      // Conventional Java classpaths often contain output directories that do not
      // exist (for example build/resources/main when a project has no resources).
      // They cannot affect class loading, so exclude them from the effective,
      // portable identity rather than refusing an otherwise valid PIT invocation.
      val artifacts = toolClasspath.filter { it.isFile || it.isDirectory }.toList()
      require(artifacts.isNotEmpty()) {
        "mutation tool classpath contains no existing artifacts"
      }
      val identity = buildString {
        artifacts.forEachIndexed { index, artifact ->
          val kind = when {
            artifact.isFile -> "file"
            artifact.isDirectory -> "directory"
            else -> error("filtered mutation tool artifact changed type: $artifact")
          }
          append(index)
          append('\t')
          append(kind)
          append('\t')
          append(PitestEvidence.fingerprintTree(artifact))
          append('\n')
        }
      }
      return PitestEvidence.sha256(identity)
    }

    fun parse(text: String): MutationToolchainRecord {
      require('\r' !in text) { "mutation toolchain record contains a carriage return" }
      val lines = text.split('\n').let { split ->
        if (split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
      }
      require(lines.isNotEmpty() && lines.none { it.isEmpty() }) {
        "mutation toolchain record contains an empty line"
      }

      val values = linkedMapOf<String, String>()
      lines.forEachIndexed { index, line ->
        val tab = line.indexOf('\t')
        require(tab > 0 && line.indexOf('\t', tab + 1) == -1) {
          "mutation toolchain line ${index + 1} is not key<TAB>value"
        }
        val key = line.substring(0, tab)
        val value = line.substring(tab + 1)
        require(key in fieldNames) { "unknown mutation toolchain field '$key'" }
        require(values.put(key, value) == null) { "duplicate mutation toolchain field '$key'" }
      }
      val missing = fieldNames - values.keys
      require(missing.isEmpty()) {
        "missing mutation toolchain field(s): ${missing.sorted().joinToString(", ")}"
      }
      require(values.getValue("schema") == SCHEMA) {
        "unsupported mutation toolchain schema '${values.getValue("schema")}' (expected $SCHEMA)"
      }

      val base = values.getValue("arcMutateBase").unlessAbsent()
      val licenceHash = values.getValue("arcMutateLicenceSha256").unlessAbsent()
      val licenceExpires = values.getValue("arcMutateLicenceExpires").unlessAbsent()?.let { raw ->
        val parsed = runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }
          .getOrElse {
            throw IllegalArgumentException(
              "arcMutateLicenceExpires must be ISO yyyy-MM-dd or '$ABSENT', was '$raw'", it)
          }
        require(parsed.toString() == raw) {
          "arcMutateLicenceExpires must be normalized ISO yyyy-MM-dd, was '$raw'"
        }
        parsed
      }
      return MutationToolchainRecord(
        pitestVersion = values.getValue("pitest"),
        junitPluginVersion = values.getValue("junitPlugin"),
        toolClasspathSha256 = values.getValue("toolClasspathSha256"),
        arcMutateBaseVersion = base,
        arcMutateLicenceSha256 = licenceHash,
        arcMutateLicenceExpires = licenceExpires,
      )
    }

    private fun licenceFiles(root: File, maxDepth: Int): List<File> {
      val path = root.toPath()
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
      require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        "ArcMutate higher-precedence licence store is not a real directory: $root"
      }
      return Files.walk(path.toRealPath(), maxDepth).use { paths ->
        paths.filter { path ->
          path.fileName.toString().contains("licence") && Files.isRegularFile(path)
        }.map { it.toFile() }.toList()
      }
    }

    private data class EffectiveArcMutateBase(val version: String, val file: File)

    private data class InspectedToolArtifact(
      val file: File,
      val mavenVersions: Map<String, String>,
      val paths: Set<String>,
      val arcMutateSentinel: Boolean,
    )

    private fun resolveEffectiveArcMutateBase(
      toolClasspath: List<InspectedToolArtifact>,
    ): EffectiveArcMutateBase? {
      val realPitPresent = toolClasspath.any {
        pitestPomProperties in it.mavenVersions || pitestSentinel in it.paths
      }
      val bases = toolClasspath.mapNotNull { artifact ->
        val version = artifact.mavenVersions[arcMutatePomProperties]
        if (version == null && artifact.arcMutateSentinel) {
          throw IllegalArgumentException(
              "effective PIT tool classpath contains markerless ArcMutate code/services in " +
                  "${artifact.file}; refuse a shaded, repacked, or corrupt base whose version " +
                  "and licence contract cannot be proven")
        }
        if (version != null && !artifact.arcMutateSentinel && realPitPresent) {
          throw IllegalArgumentException(
              "effective PIT tool classpath contains an ArcMutate Maven marker but no " +
                  "ArcMutate code/services in ${artifact.file}; refusing a corrupt or spoofed " +
                  "base artifact")
        }
        version?.let { EffectiveArcMutateBase(it, artifact.file) }
      }
      require(bases.size <= 1) {
        "effective PIT tool classpath contains multiple ArcMutate base artifacts: " +
            bases.joinToString { "${it.version} in ${it.file}" }
      }
      return bases.singleOrNull()
    }

    private fun requireConfiguredVersion(
      label: String,
      configuredVersion: String,
      pomPath: String,
      sentinelPath: String,
      artifacts: List<InspectedToolArtifact>,
    ) {
      val recognized = artifacts.filter { pomPath in it.mavenVersions || sentinelPath in it.paths }
      recognized.firstOrNull { pomPath !in it.mavenVersions }?.let { markerless ->
        throw IllegalArgumentException(
            "$label code is present in ${markerless.file}, but $pomPath is missing or unreadable; " +
                "refusing to record a configured version for an unidentifiable effective artifact")
      }
      require(recognized.size <= 1) {
        "effective PIT tool classpath contains multiple $label artifacts: " +
            recognized.joinToString { it.file.toString() }
      }
      val effective = recognized.singleOrNull()?.mavenVersions?.getValue(pomPath) ?: return
      require(effective == configuredVersion) {
        "configured $label version $configuredVersion disagrees with effective artifact version " +
            "$effective in ${recognized.single().file}; update the hardening version or remove the " +
            "late JavaExec classpath override"
      }
    }

    private fun inspectToolArtifact(artifact: File): InspectedToolArtifact {
      val knownPomPaths = setOf(
          arcMutatePomProperties, pitestPomProperties, junitPluginPomProperties)
      if (artifact.isDirectory) {
        val versions = knownPomPaths.mapNotNull { path ->
          artifact.resolve(path).takeIf(File::isFile)?.let { marker ->
            path to readMavenVersion(marker.inputStream(), path, artifact)
          }
        }.toMap()
        val paths = buildSet {
          knownPomPaths.filterTo(this) { artifact.resolve(it).isFile }
          listOf(pitestSentinel, junitPluginSentinel).filterTo(this) {
            artifact.resolve(it).isFile
          }
        }
        val serviceSentinel = artifact.resolve("META-INF/services").listFiles()
            ?.asSequence()?.filter(File::isFile)?.any(::containsArcMutateService).orFalse()
        return InspectedToolArtifact(
            artifact,
            versions,
            paths,
            arcMutatePathPrefixes.any { artifact.resolve(it).exists() } || serviceSentinel,
        )
      }

      require(artifact.extension.equals("jar", ignoreCase = true)) {
        "mutation tool classpath contains unsupported file artifact $artifact; expected a jar"
      }
      return try {
        JarFile(artifact).use { jar ->
          val entries = jar.entries().asSequence().toList()
          val entryNames = entries.mapTo(HashSet()) { it.name }
          val versions = knownPomPaths.mapNotNull { path ->
            jar.getJarEntry(path)?.let { entry ->
              path to jar.getInputStream(entry).use { input ->
                readMavenVersion(input, path, artifact)
              }
            }
          }.toMap()
          val serviceSentinel = entries.asSequence()
              .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
              .any { entry ->
                jar.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                  lines.any(::isArcMutateServiceLine)
                }
              }
          InspectedToolArtifact(
              artifact,
              versions,
              entryNames,
              entryNames.any { name -> arcMutatePathPrefixes.any(name::startsWith) } ||
                  serviceSentinel,
          )
        }
      } catch (e: IllegalArgumentException) {
        throw e
      } catch (e: Exception) {
        throw IllegalArgumentException("cannot inspect mutation tool jar $artifact", e)
      }
    }

    private fun readMavenVersion(
      input: java.io.InputStream,
      markerPath: String,
      artifact: File,
    ): String {
      val properties = try {
        Properties().apply { load(input) }
      } catch (e: Exception) {
        throw IllegalArgumentException(
            "cannot parse Maven identity $markerPath in $artifact", e)
      }
      return properties.getProperty("version")?.trim()?.takeIf(String::isNotEmpty)
          ?: throw IllegalArgumentException(
              "Maven identity $markerPath has no version in $artifact")
    }

    private fun containsArcMutateService(file: File): Boolean =
        runCatching { file.useLines { lines -> lines.any(::isArcMutateServiceLine) } }
            .getOrElse { throw IllegalArgumentException("cannot inspect PIT service file $file", it) }

    private fun isArcMutateServiceLine(line: String): Boolean {
      val implementation = line.substringBefore('#').trim()
      return arcMutateServicePrefixes.any(implementation::startsWith)
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    private fun parseCertificateExpiry(file: File): LocalDate {
      val expiryRows = file.readLines().map(String::trim)
          .filter { it.startsWith("expires=") }
      require(expiryRows.size == 1) {
        "ArcMutate provenance supports exactly one signed certificate per file; found " +
            "${expiryRows.size} expires fields in $file"
      }
      val raw = expiryRows.single().substringAfter('=')
      require(certificateDatePattern.matches(raw)) {
        "ArcMutate licence expires must be strict dd/MM/yyyy, was '$raw' in $file"
      }
      return runCatching { LocalDate.parse(raw, certificateDateFormatter) }
        .getOrElse {
          throw IllegalArgumentException(
            "ArcMutate licence expires is not a valid dd/MM/yyyy date: '$raw' in $file", it)
        }
    }

    private fun parseCertificateType(file: File): ArcMutateLicenceType {
      val typeRows = file.readLines().map(String::trim)
          .filter { it.startsWith("type=") }
      require(typeRows.size == 1) {
        "ArcMutate provenance supports exactly one signed certificate per file; found " +
            "${typeRows.size} type fields in $file"
      }
      val raw = typeRows.single().substringAfter('=').uppercase(Locale.ROOT)
      return runCatching { ArcMutateLicenceType.valueOf(raw) }
          .getOrElse {
            throw IllegalArgumentException(
                "unsupported ArcMutate licence type '$raw' in $file", it)
          }
    }

    private fun String.unlessAbsent(): String? = if (this == ABSENT) null else this

    private fun requirePlainValue(field: String, value: String) {
      require(value.isNotEmpty() && value == value.trim() &&
          '\t' !in value && '\n' !in value && '\r' !in value) {
        "mutation toolchain field '$field' must be a non-blank single-line value"
      }
    }

    private fun requireSha256(field: String, value: String) {
      require(sha256Pattern.matches(value)) {
        "mutation toolchain field '$field' must be a lowercase SHA-256, was '$value'"
      }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
  }
}
