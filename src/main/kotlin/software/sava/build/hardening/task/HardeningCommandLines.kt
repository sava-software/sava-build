package software.sava.build.hardening.task

import java.io.File

/** Pure command-line assembly kept outside Gradle task actions for direct testing. */
internal object HardeningCommandLines {

  data class Pitest(
    val applicationClasspath: List<File>,
    val targetClasses: List<String>,
    val mutateOnly: String?,
    val excludedClasses: List<String>,
    val targetTests: String,
    val sourceDirectories: List<File>,
    val reportDirectory: File,
    val projectBaseDirectory: File,
    val mutators: String,
    val outputFormats: List<String>,
    val timestampedReports: Boolean,
    val threads: Int,
    val timeoutFactor: String,
    val timeoutConst: Long,
    val historyActive: Boolean,
    val historyFile: File,
  )

  fun pitest(spec: Pitest): List<String> = buildList {
    add("--classPath=" + spec.applicationClasspath.joinToString(",") { it.absolutePath })
    val scopedTargets = spec.mutateOnly?.trim().orEmpty()
    add(
      "--targetClasses=" +
        if (scopedTargets.isNotEmpty()) scopedTargets else spec.targetClasses.joinToString(",")
    )
    if (spec.excludedClasses.isNotEmpty()) {
      add("--excludedClasses=" + spec.excludedClasses.joinToString(","))
    }
    add("--targetTests=${spec.targetTests}")
    add("--sourceDirs=" + spec.sourceDirectories.joinToString(",") { it.absolutePath })
    add("--reportDir=${spec.reportDirectory.absolutePath}")
    add("--projectBase=${spec.projectBaseDirectory.absolutePath}")
    add("--mutators=${spec.mutators}")
    add("--outputFormats=${spec.outputFormats.joinToString(",")}")
    add("--timestampedReports=${spec.timestampedReports}")
    add("--threads=${spec.threads}")
    add("--timeoutFactor=${spec.timeoutFactor}")
    add("--timeoutConst=${spec.timeoutConst}")
    if (spec.historyActive) {
      if (spec.historyFile.isFile) {
        add("--historyInputLocation=${spec.historyFile.absolutePath}")
      }
      add("--historyOutputLocation=${spec.historyFile.absolutePath}")
      add("--features=+arcmutate_history")
    }
  }

  data class FuzzRun(
    val targetClass: String,
    val maxTotalTimeSeconds: Int,
    val maxLen: Int?,
    val localCorpus: File,
    val seedCorpus: File?,
  )

  fun fuzzRun(spec: FuzzRun): List<String> = buildList {
    add("--target_class=${spec.targetClass}")
    add("-max_total_time=${spec.maxTotalTimeSeconds}")
    spec.maxLen?.let { add("-max_len=$it") }
    add(spec.localCorpus.absolutePath)
    spec.seedCorpus?.let { add(it.absolutePath) }
  }

  data class FuzzMinimize(
    val targetClass: String,
    val maxLen: Int?,
    val stagingCorpus: File,
    val seedCorpus: File,
    val localCorpus: File?,
  )

  fun fuzzMinimize(spec: FuzzMinimize): List<String> = buildList {
    add("--target_class=${spec.targetClass}")
    add("-merge=1")
    spec.maxLen?.let { add("-max_len=$it") }
    add(spec.stagingCorpus.absolutePath)
    add(spec.seedCorpus.absolutePath)
    spec.localCorpus?.let { add(it.absolutePath) }
  }
}
