package software.sava.build.jlink

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Lists the modules linked into the jlink image via its bundled 'bin/java --list-modules'. */
@UntrackedTask(because = "Reports to the console; produces no outputs")
abstract class JlinkModulesImageTask : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val imageDirectory: DirectoryProperty

  @get:Inject
  protected abstract val execOperations: ExecOperations

  @TaskAction
  fun execute() {
    val java = imageDirectory.get().file("bin/${executableName("java")}").asFile
    execOperations.exec {
      executable(java.absolutePath)
      args("--list-modules")
    }
  }
}
