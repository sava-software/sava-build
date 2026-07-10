package software.sava.build.jlink

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Runs the application from the jlink image via its bundled 'bin/java'. */
@UntrackedTask(because = "Runs the application; produces no outputs")
abstract class JlinkRunImageTask : DefaultTask() {

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val imageDirectory: DirectoryProperty

  @get:Internal
  abstract val mainModule: Property<String>

  @get:Internal
  abstract val mainClass: Property<String>

  @get:Inject
  protected abstract val execOperations: ExecOperations

  @TaskAction
  fun execute() {
    val java = imageDirectory.get().file("bin/${executableName("java")}").asFile
    val module = mainModule.get() + (mainClass.orNull?.let { "/$it" } ?: "")
    execOperations.exec {
      executable(java.absolutePath)
      args("--module", module)
    }
  }
}
