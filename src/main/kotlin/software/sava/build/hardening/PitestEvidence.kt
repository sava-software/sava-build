package software.sava.build.hardening

import java.io.File
import java.security.MessageDigest

/**
 * Provenance for one completed PIT report. A report is evidence only when this
 * manifest still describes the code and suite configuration being verified.
 *
 * The on-disk form is deliberately tiny and strict: sorted `key<TAB>value` rows.
 * It is machine-readable without adding a JSON dependency to every consumer build,
 * rejects duplicate/unknown/missing fields, and is deterministic apart from the
 * invocation id supplied by the caller.
 */
data class PitestEvidence(
  val suite: String,
  val invocationId: String,
  val pitestVersion: String,
  val junitPluginVersion: String,
  val pluginSha256: String,
  val identitySchema: String,
  val javaVersion: String,
  val sourceSha256: String,
  val classesSha256: String,
  val classpathSha256: String,
  val toolClasspathSha256: String,
  val mutationToolchainSha256: String,
  val configurationSha256: String,
  val reportSha256: String,
  val scope: String,
  val historyAssisted: Boolean,
) {

  fun render(): String {
    val fields = values()
    fields.forEach { (key, value) ->
      require('\t' !in value && '\n' !in value && '\r' !in value) {
        "evidence field '$key' contains a tab or newline"
      }
    }
    return fields.entries.sortedBy { it.key }
        .joinToString("\n", postfix = "\n") { (key, value) -> "$key\t$value" }
  }

  private fun values(): Map<String, String> = mapOf(
      "schema" to SCHEMA,
      "suite" to suite,
      "invocation" to invocationId,
      "pitest" to pitestVersion,
      "junitPlugin" to junitPluginVersion,
      "pluginSha256" to pluginSha256,
      "identitySchema" to identitySchema,
      "java" to javaVersion,
      "sourceSha256" to sourceSha256,
      "classesSha256" to classesSha256,
      "classpathSha256" to classpathSha256,
      "toolClasspathSha256" to toolClasspathSha256,
      "mutationToolchainSha256" to mutationToolchainSha256,
      "configurationSha256" to configurationSha256,
      "reportSha256" to reportSha256,
      "scope" to scope,
      "historyAssisted" to historyAssisted.toString(),
  )

  fun differences(expected: PitestEvidence): List<String> = buildList {
    val actual = values()
    val wanted = expected.values()
    wanted.keys.sorted().forEach { key ->
      if (actual[key] != wanted[key]) add("$key: recorded=${actual[key]} current=${wanted[key]}")
    }
  }

  /**
   * Identity of the code, tools, classpaths, and suite configuration behind an
   * observation. Invocation and report bytes are deliberately excluded: successive
   * fresh runs of identical inputs must share this identity while still carrying
   * distinct [invocationId] values.
   */
  fun inputIdentitySha256(): String = sha256(
      copy(invocationId = "", reportSha256 = "").render())

  companion object {
    const val SCHEMA = "3"
    const val LEGACY_MUTATION_TOOLCHAIN = "legacy-unbound"
    const val FULL_SCOPE = "full"
    const val CURRENT_IDENTITY_SCHEMA = "pit-csv-class-method-mutator-status-v2"

    private val schema2Fields = setOf(
        "schema", "suite", "invocation", "pitest", "junitPlugin", "pluginSha256",
        "identitySchema", "java",
        "sourceSha256", "classesSha256", "classpathSha256", "toolClasspathSha256",
        "configurationSha256", "reportSha256",
        "scope", "historyAssisted")
    private val fields = schema2Fields + "mutationToolchainSha256"

    fun parse(text: String): PitestEvidence {
      val values = linkedMapOf<String, String>()
      text.lineSequence().filter { it.isNotEmpty() }.forEachIndexed { index, line ->
        val split = line.indexOf('\t')
        require(split > 0) { "evidence line ${index + 1} is not key<TAB>value" }
        val key = line.substring(0, split)
        val value = line.substring(split + 1)
        require(values.put(key, value) == null) { "duplicate evidence field '$key'" }
      }
      val schema = values["schema"] ?: throw IllegalArgumentException("missing evidence field(s): schema")
      val expectedFields = when (schema) {
        "2" -> schema2Fields
        SCHEMA -> fields
        else -> throw IllegalArgumentException(
            "unsupported evidence schema '$schema' (expected $SCHEMA or N-1 schema 2)")
      }
      val unknown = values.keys - expectedFields
      require(unknown.isEmpty()) { "unknown evidence field(s): ${unknown.sorted().joinToString(", ")}" }
      val missing = expectedFields - values.keys
      require(missing.isEmpty()) { "missing evidence field(s): ${missing.sorted().joinToString(", ")}" }
      val history = values.getValue("historyAssisted")
      require(history == "true" || history == "false") {
        "historyAssisted must be true or false, was '$history'"
      }
      return PitestEvidence(
          suite = values.getValue("suite"),
          invocationId = values.getValue("invocation"),
          pitestVersion = values.getValue("pitest"),
          junitPluginVersion = values.getValue("junitPlugin"),
          pluginSha256 = values.getValue("pluginSha256"),
          identitySchema = values.getValue("identitySchema"),
          javaVersion = values.getValue("java"),
          sourceSha256 = values.getValue("sourceSha256"),
          classesSha256 = values.getValue("classesSha256"),
          classpathSha256 = values.getValue("classpathSha256"),
          toolClasspathSha256 = values.getValue("toolClasspathSha256"),
          mutationToolchainSha256 = values["mutationToolchainSha256"] ?: LEGACY_MUTATION_TOOLCHAIN,
          configurationSha256 = values.getValue("configurationSha256"),
          reportSha256 = values.getValue("reportSha256"),
          scope = values.getValue("scope"),
          historyAssisted = history.toBooleanStrict(),
      )
    }

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: File): String = sha256(file.readBytes())

    fun fingerprintTree(root: File): String = when {
      root.isFile -> sha256(root)
      root.isDirectory -> fingerprint(root, root.walkTopDown().filter(File::isFile).asIterable())
      else -> error("cannot fingerprint missing path $root")
    }

    /** Stable inventory of files whose extant members decide one suite's gate. */
    fun mutationRecordFiles(configDir: File, suite: String): List<File> = listOf(
      configDir.resolve("$suite-accepted.csv"),
      configDir.resolve("$suite-timeouts.csv"),
      configDir.resolve("$suite-pitest-version"),
      configDir.resolve("$suite-pitest-toolchain.tsv"),
      configDir.resolve("README.md"),
    )

    /** Files whose exact bytes decide whether one suite's mutation gate passes. */
    fun mutationRecordFingerprint(projectDir: File, configDir: File, suite: String): String {
      BaselineFiles.requireDirectoryOrMissing(projectDir, configDir)
      val recordFiles = mutationRecordFiles(configDir, suite)
      recordFiles.forEach { BaselineFiles.requireRegularFileOrMissing(projectDir, it) }
      return fingerprint(configDir, recordFiles.filter(File::isFile))
    }

    fun fingerprint(root: File, files: Iterable<File>): String {
      val digest = MessageDigest.getInstance("SHA-256")
      files.asSequence().flatMap { entry ->
        when {
          entry.isFile -> sequenceOf(entry)
          entry.isDirectory -> entry.walkTopDown().filter(File::isFile)
          else -> emptySequence()
        }
      }.distinctBy { it.absoluteFile.normalize().path }.map { file ->
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
            .getOrElse { file.absoluteFile.invariantSeparatorsPath }
        relative to file
      }.sortedBy { it.first }.forEach { (relative, file) ->
        digest.update(relative.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        file.inputStream().use { input ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
        digest.update(0.toByte())
      }
      return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
