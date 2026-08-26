package software.sava.build.hardening

import java.io.File
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
  /**
   * Digest of the report bytes this observation produced — a per-observation value,
   * not an input identity, despite sitting beside five fields that are. PIT orders a
   * mutant's covering tests by recorded time less a direct-hit bonus and stops at the
   * first kill, so near-ties race and a different `killingTest` lands in the report:
   * measured on json-iterator, 12 runs over identical source produced 9 distinct
   * values, and one pair matched by pure chance. Never compare it across runs as
   * evidence of equivalence — every legitimate read is same-invocation, and both
   * input-identity functions below blank it. (sava-build#103)
   */
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
   * Identity of the code, loaded sava-build plugin bytes, mutation tools, classpaths,
   * and suite configuration behind an observation. Invocation and report bytes are
   * deliberately excluded: successive fresh runs of identical inputs must share this
   * identity while still carrying distinct [invocationId] values. [pluginSha256]
   * remains a stable input for comparisons that require the exact wrapper identity.
   * Timeout retirement uses [timeoutRetirementInputIdentitySha256] instead, with its
   * stash format as the verifier-semantics fence. For a published plugin the value is
   * the JAR SHA-256; development class directories use a deterministic code-path tree
   * fingerprint.
   */
  fun inputIdentitySha256(): String = sha256(
      copy(invocationId = "", reportSha256 = "").render())

  /**
   * Input identity for the advisory timeout-retirement streak. The completed
   * evidence and every certifying boundary still bind [pluginSha256]. When the
   * captured PIT inputs and timeout-retirement semantics are unchanged, a plugin
   * fingerprint change alone need not erase prior quiet observations. Invocation/report
   * bytes identify individual observations and are excluded for the same reason as
   * [inputIdentitySha256].
   *
   * The timeout-quiet stash format is the compatibility fence for verifier or PIT
   * invocation semantics not represented by the remaining evidence fields. Bump
   * that format instead of adding the entire plugin JAR back to this identity.
   */
  /**
   * Retirement is a deliberate act, not passive accrual, and this identity encodes
   * that: it still binds `sourceSha256` — every main and test source plus the build
   * scripts — so the quiet streak survives a plugin upgrade but resets on essentially
   * any commit. That is intended. The streak answers "did three fresh full runs over
   * *these exact inputs* stay quiet", which is a measurement someone performs, not a
   * counter that drifts upward across ordinary development. Measured on json-iterator,
   * the distinction is often moot anyway: every audited key there times out on every
   * run, so the counters are pinned at zero for a reason no identity scoping would
   * change. Narrow this only with a repo whose timeouts are genuinely intermittent in
   * hand, and bump the timeout-quiet stash format when semantics move. (sava-build#102)
   */
  fun timeoutRetirementInputIdentitySha256(): String = sha256(
      copy(invocationId = "", reportSha256 = "", pluginSha256 = "").render())

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

    /**
     * Every hash of text goes through here, and this is the one step where the
     * canonical text stops being characters and becomes bytes — so it is the one
     * step where two different texts can become one input.
     *
     * `String.toByteArray` is lenient: it substitutes `?` for an unpaired UTF-16
     * surrogate rather than failing. `?` is not inert here, it is PIT's
     * single-character wildcard, so `Gen?` and `Gen\uD800` are two different
     * class-name patterns that encode to the same bytes. Every per-field validator
     * runs upstream of this and inspects the `String`, so none of them can see it —
     * and the plugin's own glob consumers keep the `String`, which is how one
     * identity can cover two different ownership verdicts.
     *
     * A strict encoder refuses instead of substituting. Well-formed UTF-16 encodes
     * to exactly the bytes it does today, so no valid hash moves; only inputs that
     * could collide are rejected, and this covers every text hashed here at once —
     * the configuration lines, the classpath-order strings, and the relative paths
     * in [fingerprint].
     */
    fun sha256(text: String): String = sha256(strictUtf8(text))

    private fun strictUtf8(text: String): ByteArray {
      val encoded = try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
      } catch (e: CharacterCodingException) {
        throw IllegalArgumentException(
            "evidence text is not well-formed UTF-16, so it cannot be hashed without being " +
                "folded onto a different value: ${text.map { "\\u%04x".format(it.code) }
                    .joinToString("", limit = 40)}", e)
      }
      return ByteArray(encoded.remaining()).also(encoded::get)
    }

    fun sha256(file: File): String = sha256(file.readBytes())

    /**
     * A file's bytes or a directory's whole tree, tagged by which it was.
     *
     * The two branches are different encodings, and untagged they shared one output
     * space with nothing separating them. That is not the theoretical hazard it
     * looks like: the file branch hashes arbitrary bytes, so a file simply *holding*
     * the directory branch's digest input produces the directory's value — write
     * `int32be(1) || "a" || SHA-256("X")` into a file and it fingerprints as the
     * directory containing `a` with content `X`. An empty file and an empty
     * directory collide with no effort at all.
     *
     * The tag therefore has to sit outside the hash. A prefix folded into the digest
     * input would be copyable by exactly the same trick, since the file branch's
     * input is whatever the file says it is.
     *
     * This changes the recorded value's shape for both kinds, which is a one-time
     * churn wherever one is committed — a consumer's mutation-toolchain record. The
     * value was ambiguous before, so there was nothing there worth preserving.
     */
    /**
     * A file's bytes or a directory's whole tree, in one field.
     *
     * The two branches are different encodings, and untagged they shared one output
     * space with nothing separating them — not theoretically: the file branch hashes
     * arbitrary bytes, so a file merely *holding* the directory encoding's digest
     * input produces the directory's value, and an empty file equals an empty
     * directory outright. The tag must sit outside the hash (an in-digest prefix is
     * copyable by the same trick), and it sits on the directory branch only: the file
     * branch is the published one — every consumer receipt's `pluginSha256` and
     * `tools/local-fuzz.sh`'s 64-hex format check read it — while directory-mode
     * values exist only in the includeBuild dev loop and regenerated build outputs.
     * A raw 64-hex value and a `tree:`-prefixed one can never collide.
     */
    fun fingerprintTree(root: File): String = when {
      root.isFile -> sha256(root)
      root.isDirectory ->
        "tree:" + fingerprint(root, root.walkTopDown().filter(File::isFile).asIterable())
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
      // Every distinct logical entry is kept, and nothing is chosen between aliases.
      // Earlier shapes both got this wrong in one direction or the other: deduping on
      // the lexically normalized path dropped a genuinely different file whose `..`
      // crossed a symlink, and deduping on the canonical path erased a real alias — a
      // tree holding `a` plus symlink `b -> a` exposes two classpath names and must
      // not fingerprint like a tree holding `a` alone. So the entry is the pair
      // (relative name, content digest): exact duplicates collapse, aliases survive
      // as the distinct names they are, and sorting by the full pair makes ties
      // between same-named entries deterministic whatever order they arrived in.
      }.map { file ->
        val relative = runCatching { file.relativeTo(root).invariantSeparatorsPath }
            .getOrElse { file.absoluteFile.invariantSeparatorsPath }
        relative to contentSha256Bytes(file)
      }.distinctBy { (relative, content) -> relative to content.toHex() }
          .sortedWith(compareBy({ it.first }, { it.second.toHex() }))
          .forEach { (relative, content) ->
        // Fixed-width framing, because the entries are not self-delimiting otherwise.
        // A NUL between the name, the content and the next entry is unambiguous only
        // if content cannot contain NUL — and this hashes compiled class files, which
        // routinely do. One file named `a` holding `X\0b\0Y` fed the digest exactly
        // the bytes that two files `a`=`X` and `b`=`Y` feed it, so different file
        // sets shared a fingerprint. The name's byte length pins where the name ends,
        // and folding the content to its own digest makes every entry the same size
        // whatever the bytes are, without holding a file in memory or trusting a
        // length that could change mid-read.
        // Strict, like every other text this hashes: a lenient encode folds an
        // unpaired surrogate onto '?' and two distinct names become one.
        val name = strictUtf8(relative)
        digest.update(intToBytes(name.size))
        digest.update(name)
        digest.update(content)
      }
      return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte())

    private fun contentSha256Bytes(file: File): ByteArray {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
  }
}
