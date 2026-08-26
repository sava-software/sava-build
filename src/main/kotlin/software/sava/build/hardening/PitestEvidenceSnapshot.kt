package software.sava.build.hardening

import java.io.File

/**
 * Stable, published execution-time inputs used to bind a PIT report to the code,
 * tool and effective mutation configuration that produced it. Later optional
 * settings may be supplied by an internal capture overload to preserve this ABI.
 *
 * File iterables are realized by [PitestEvidenceSnapshot.capture]. Callers should
 * therefore invoke it only after producer tasks have completed; in particular, they
 * must not wrap a capture in a Provider that configuration-cache storage can realize.
 */
data class PitestEvidenceSnapshotInput(
  val suite: String,
  val invocationId: String,
  val pitestVersion: String,
  val junitPluginVersion: String,
  val javaVersion: String,
  val projectDirectory: File,
  val pluginCode: File,
  val sourceFiles: Iterable<File>,
  val classFiles: Iterable<File>,
  val runtimeClasspath: Iterable<File>,
  val toolClasspath: Iterable<File>,
  val mutationToolchainSha256: String,
  val targetClasses: Iterable<String>,
  val excludedClasses: Iterable<String>,
  val targetTests: String,
  val mutators: String,
  val threads: Int,
  val timeoutFactor: Double,
  val timeoutConst: Long,
  val mutationBytecodeRelease: Int,
  val recompileExcludes: Iterable<String>,
  val reportSha256: String,
  val scope: String,
  val historyAssisted: Boolean,
)

/**
 * Captures the canonical PIT evidence snapshot shared by execution, certification
 * and mode-snapshot revalidation.
 *
 * Content fingerprints are order-independent, while the two classpath-order hashes
 * deliberately retain iteration order because it controls JVM shadowing. Keep the
 * configuration text byte-for-byte stable: it is part of the persisted evidence
 * contract even though it is represented on disk only by its SHA-256.
 */
object PitestEvidenceSnapshot {

  /** PIT's own entry point; anything else is a substituted process worth recording. */
  const val DEFAULT_MAIN_CLASS = "org.pitest.mutationtest.commandline.MutationCoverageReport"

  /** The normalized absolute path, refused if it could forge an entry of the order hash. */
  private fun encodableClasspathPath(kind: String, file: java.io.File): String =
    HardeningNames.requireSingleLineValue("$kind entry", file.absoluteFile.normalize().path)

  /** Preserves the published capture contract and its canonical default configuration text. */
  fun capture(input: PitestEvidenceSnapshotInput): PitestEvidence = capture(input, emptyList())

  /**
   * Captures [input] plus the ordered PIT child/minion JVM arguments.
   *
   * The arguments remain outside [PitestEvidenceSnapshotInput] so adding this setting does
   * not break that published data class's constructor, component, copy, or default ABI.
   */
  internal fun capture(
    input: PitestEvidenceSnapshotInput,
    minionJvmArgs: Iterable<String>,
  ): PitestEvidence = capture(
    input,
    minionJvmArgs,
    PitestEvidence.fingerprintTree(input.pluginCode),
  )

  /**
   * Captures evidence with the plugin identity frozen when the convention was applied.
   * Callers separately validate that [PitestEvidenceSnapshotInput.pluginCode] still
   * hashes to this value at their execution boundaries.
   */
  internal fun capture(
    input: PitestEvidenceSnapshotInput,
    minionJvmArgs: Iterable<String>,
    pluginSha256: String,
  ): PitestEvidence = capture(input, minionJvmArgs, pluginSha256, 0)

  /**
   * Adds execution-only PIT settings without changing the published snapshot-input ABI.
   * Zero remains absent from the canonical text so every ordinary-run hash is stable.
   */
  internal fun capture(
    input: PitestEvidenceSnapshotInput,
    minionJvmArgs: Iterable<String>,
    pluginSha256: String,
    mutationUnitSize: Int,
  ): PitestEvidence = capture(
    input,
    minionJvmArgs,
    pluginSha256,
    mutationUnitSize,
    software.sava.build.hardening.task.HardeningCommandLines.PitestVerbosity.DEFAULT,
  )

  /**
   * Binds an explicitly customized PIT verbosity without changing ordinary-run
   * configuration hashes. Diagnostic tasks do not persist suite evidence, but a
   * consumer's late customization of a decision-grade task must remain observable.
   */
  internal fun capture(
    input: PitestEvidenceSnapshotInput,
    minionJvmArgs: Iterable<String>,
    pluginSha256: String,
    mutationUnitSize: Int,
    verbosity: String,
  ): PitestEvidence = capture(
    input,
    minionJvmArgs,
    pluginSha256,
    mutationUnitSize,
    verbosity,
    emptyList(),
  )

  /**
   * Binds the suite's removed test classes without changing the published
   * snapshot-input ABI. An empty set stays absent from the canonical text, so a
   * suite that removes nothing keeps the `configurationSha256` it already
   * published — the same rule [minionJvmArgs] and [mutationUnitSize] follow.
   *
   * That is a statement about this one field. A completed report binds plugin
   * bytes, sources, classes and classpaths too, and an upgrade moves the plugin
   * hash regardless, so it does not mean a recorded report survives one. What it
   * means is that adding the setting is not itself a reason for anything to go
   * stale: the field reads exactly as it did before the setting existed.
   */
  internal fun capture(
    input: PitestEvidenceSnapshotInput,
    minionJvmArgs: Iterable<String>,
    pluginSha256: String,
    mutationUnitSize: Int,
    verbosity: String,
    excludedTestClasses: Iterable<String>,
    mainClass: String = DEFAULT_MAIN_CLASS,
  ): PitestEvidence {
    require(mutationUnitSize >= 0) { "PIT mutation unit size must not be negative" }
    val normalizedVerbosity =
      software.sava.build.hardening.task.HardeningCommandLines.PitestVerbosity.normalize(verbosity)
    // Realize every Iterable exactly once, before anything reads one.
    //
    // Two reasons, and the second is why this is all of them rather than the two the
    // classpath hashes needed. A mutable FileCollection can be seen inconsistently by
    // two reads, so the order hash and the content hash must share one view. And
    // `Iterable` is a published input type that promises nothing about repeatability:
    // a one-shot sequence validates clean and then serializes as empty, and a
    // stateful one can answer safely while being checked and differently while being
    // written. Validating a field and then re-reading it is the same
    // time-of-check/time-of-use hole wherever it appears, so nothing below touches
    // `input` again.
    val runtimeFiles = input.runtimeClasspath.toList()
    val toolFiles = input.toolClasspath.toList()
    val sourceFiles = input.sourceFiles.toList()
    val classFiles = input.classFiles.toList()
    val targetClasses = input.targetClasses.toList()
    val excludedClasses = input.excludedClasses.toList()
    val recompileExcludes = input.recompileExcludes.toList()
    val minionArgs = minionJvmArgs.toList()
    // Every raw textual field, checked here rather than on the way in. The canonical
    // text below is one `key=value` line per setting with list fields comma-joined,
    // so a value carrying a newline writes a line of its own and a list entry
    // carrying a comma writes a neighbouring entry. Either lets two configurations
    // that behave differently render identically, which is the one property this
    // whole identity rests on. Checking a field at a time, as its own boundary
    // happened to need it, is what let a pair collide across two fields that were
    // each individually fine.
    //
    // Here because every `capture` overload funnels through this function and the
    // one-argument form is published: a check on any caller's inputs leaves this
    // boundary able to mint a colliding identity for the next caller.
    //
    // The fields not listed cannot forge anything. `threads`, the timeouts,
    // `mutationUnitSize` and `mutationBytecodeRelease` are numbers; `verbosity` is
    // normalized against a fixed set; `minionJvmArgs` is length-prefixed, which is
    // the unambiguous encoding the rest would need if rejecting were not cheaper;
    // and the two classpath fields are already hashes.
    targetClasses.forEach { HardeningNames.requireEncodableListEntry("targetClasses", it) }
    excludedClasses.forEach { HardeningNames.requireEncodableListEntry("excludedClasses", it) }
    recompileExcludes.forEach { HardeningNames.requireEncodableListEntry("recompileExcludes", it) }
    val excludedTests = excludedTestClasses.map(HardeningNames::requireTestExclusionGlob)
    // Scalars, where a comma is the field's own legal separator and only a line
    // break or NUL can reach past it.
    HardeningNames.requireSingleLineValue("targetTests", input.targetTests)
    HardeningNames.requireSingleLineValue("mutators", input.mutators)
    val configurationText = buildString {
      appendLine("targetClasses=${targetClasses.sorted().joinToString(",")}")
      appendLine("excludedClasses=${excludedClasses.sorted().joinToString(",")}")
      appendLine("targetTests=${input.targetTests}")
      if (excludedTests.isNotEmpty()) {
        appendLine("excludedTestClasses=${excludedTests.sorted().joinToString(",")}")
      }
      appendLine("mutators=${input.mutators}")
      appendLine("threads=${input.threads}")
      if (minionArgs.isNotEmpty()) {
        appendLine("minionJvmArgs=" + minionArgs.joinToString("") { "${it.length}:$it" })
      }
      appendLine("timeoutFactor=${input.timeoutFactor}")
      appendLine("timeoutConst=${input.timeoutConst}")
      if (mutationUnitSize > 0) {
        appendLine("mutationUnitSize=$mutationUnitSize")
      }
      if (normalizedVerbosity !=
          software.sava.build.hardening.task.HardeningCommandLines.PitestVerbosity.DEFAULT) {
        appendLine("verbosity=$normalizedVerbosity")
      }
      // Which process was started is part of what produced the report, and the task
      // keeps a main class as a supported compatibility surface — so a fixed main
      // reading a system property could emit either of two reports, and the second
      // would validate against the first's identity. Absent at the default, so no
      // existing hash moves.
      if (mainClass != DEFAULT_MAIN_CLASS) {
        appendLine("mainClass=${HardeningNames.requireSingleLineValue("mainClass", mainClass)}")
      }
      appendLine("mutationBytecodeRelease=${input.mutationBytecodeRelease}")
      appendLine("recompileExcludes=${recompileExcludes.sorted().joinToString(",")}")
      // The order hashes join paths with a newline, and a newline is a legal
      // character in a Unix path — so for Y = X + "\n" + X the orders [X, Y] and
      // [Y, X] join to the same string. Both contain the same files, so the
      // order-independent content hashes match too, and two classpaths that shadow
      // differently in the JVM would share a complete evidence identity. Refused
      // rather than escaped, which keeps every ordinary path's hash unchanged.
      appendLine(
        "classpathOrderSha256=" + PitestEvidence.sha256(
          runtimeFiles.joinToString("\n") { encodableClasspathPath("runtime classpath", it) }
        )
      )
      appendLine(
        "toolClasspathOrderSha256=" + PitestEvidence.sha256(
          toolFiles.joinToString("\n") { encodableClasspathPath("tool classpath", it) }
        )
      )
    }
    return PitestEvidence(
      suite = input.suite,
      invocationId = input.invocationId,
      pitestVersion = input.pitestVersion,
      junitPluginVersion = input.junitPluginVersion,
      pluginSha256 = pluginSha256,
      identitySchema = PitestEvidence.CURRENT_IDENTITY_SCHEMA,
      javaVersion = input.javaVersion,
      sourceSha256 = PitestEvidence.fingerprint(input.projectDirectory, sourceFiles),
      classesSha256 = PitestEvidence.fingerprint(input.projectDirectory, classFiles),
      classpathSha256 = PitestEvidence.fingerprint(input.projectDirectory, runtimeFiles),
      toolClasspathSha256 = PitestEvidence.fingerprint(input.projectDirectory, toolFiles),
      mutationToolchainSha256 = input.mutationToolchainSha256,
      configurationSha256 = PitestEvidence.sha256(configurationText),
      reportSha256 = input.reportSha256,
      scope = input.scope,
      historyAssisted = input.historyAssisted,
    )
  }
}
