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
  ): PitestEvidence {
    require(mutationUnitSize >= 0) { "PIT mutation unit size must not be negative" }
    // Realize once at the execution boundary. Apart from avoiding inconsistent views
    // of a mutable FileCollection, this guarantees the order hash and content hash see
    // exactly the same classpath membership.
    val runtimeFiles = input.runtimeClasspath.toList()
    val toolFiles = input.toolClasspath.toList()
    val minionArgs = minionJvmArgs.toList()
    val configurationText = buildString {
      appendLine("targetClasses=${input.targetClasses.sorted().joinToString(",")}")
      appendLine("excludedClasses=${input.excludedClasses.sorted().joinToString(",")}")
      appendLine("targetTests=${input.targetTests}")
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
      appendLine("mutationBytecodeRelease=${input.mutationBytecodeRelease}")
      appendLine("recompileExcludes=${input.recompileExcludes.sorted().joinToString(",")}")
      appendLine(
        "classpathOrderSha256=" + PitestEvidence.sha256(
          runtimeFiles.joinToString("\n") { it.absoluteFile.normalize().path }
        )
      )
      appendLine(
        "toolClasspathOrderSha256=" + PitestEvidence.sha256(
          toolFiles.joinToString("\n") { it.absoluteFile.normalize().path }
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
      sourceSha256 = PitestEvidence.fingerprint(input.projectDirectory, input.sourceFiles),
      classesSha256 = PitestEvidence.fingerprint(input.projectDirectory, input.classFiles),
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
