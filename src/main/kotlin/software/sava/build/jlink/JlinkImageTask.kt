package software.sava.build.jlink

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Builds a runtime image by invoking the 'jlink' tool of the toolchain JDK directly.
 * Everything on [modulePath] must be an explicit or automatic module.
 */
@DisableCachingByDefault(because = "Caching a full runtime image is likely slower than relinking it")
abstract class JlinkImageTask : DefaultTask() {

  /** Locates the JDK whose 'bin/jlink' is executed. */
  @get:Nested
  abstract val javaLauncher: Property<JavaLauncher>

  @get:Classpath
  abstract val modulePath: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val output: DirectoryProperty

  @get:Input
  abstract val addModules: ListProperty<String>

  @get:Input
  abstract val addOptions: ListProperty<String>

  @get:Input
  @get:Optional
  abstract val bindServices: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val compress: Property<String>

  @get:Input
  @get:Optional
  abstract val dedupLegalNoticesErrorIfNotSameContent: Property<Boolean>

  @get:Input
  abstract val disablePlugin: ListProperty<String>

  @get:Input
  @get:Optional
  abstract val endian: Property<String>

  @get:Input
  abstract val excludeFiles: ListProperty<String>

  @get:Input
  abstract val excludeResources: ListProperty<String>

  @get:Input
  @get:Optional
  abstract val generateCdsArchive: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val ignoreSigningInformation: Property<Boolean>

  @get:Input
  abstract val includeLocales: ListProperty<String>

  @get:Input
  abstract val launcher: MapProperty<String, String>

  @get:Input
  abstract val limitModules: ListProperty<String>

  @get:Input
  @get:Optional
  abstract val noHeaderFiles: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val noManPages: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val stripDebug: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val stripJavaDebugAttributes: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val stripNativeCommands: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val vendorBugUrl: Property<String>

  @get:Input
  @get:Optional
  abstract val vendorVersion: Property<String>

  @get:Input
  @get:Optional
  abstract val vendorVmBugUrl: Property<String>

  @get:Input
  @get:Optional
  abstract val verbose: Property<Boolean>

  @get:Input
  @get:Optional
  abstract val vm: Property<String>

  @get:Inject
  protected abstract val execOperations: ExecOperations

  @get:Inject
  protected abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun execute() {
    val jlink = javaLauncher.get().metadata.installationPath
      .file("bin/${executableName("jlink")}").asFile
    check(jlink.isFile) { "jlink not found in the toolchain JDK: $jlink" }

    val modules = addModules.get()
    check(modules.isNotEmpty()) {
      "No modules to link. Set 'jlinkApplication.mainModule' or 'jlinkApplication.addModules'."
    }

    // jlink refuses to write into an existing directory, and Gradle pre-creates
    // registered output directories before the action runs.
    val outputDirectory = output.get().asFile
    fileSystemOperations.delete { delete(outputDirectory) }

    val arguments = mutableListOf(
      "--module-path", modulePath.files.joinToString(File.pathSeparator) { it.absolutePath },
      "--add-modules", modules.joinToString(",")
    )
    launcher.get().forEach { (name, moduleAndClass) ->
      arguments += "--launcher"
      arguments += "$name=$moduleAndClass"
    }

    fun flag(option: String, property: Property<Boolean>) {
      if (property.getOrElse(false)) arguments += option
    }

    fun value(option: String, property: Property<String>) {
      property.orNull?.let { arguments += "$option=$it" }
    }

    fun joined(option: String, property: ListProperty<String>, separator: String = ",") {
      val values = property.get()
      if (values.isNotEmpty()) arguments += "$option=${values.joinToString(separator)}"
    }

    joined("--add-options", addOptions, " ")
    flag("--bind-services", bindServices)
    value("--compress", compress)
    if (dedupLegalNoticesErrorIfNotSameContent.getOrElse(false)) {
      arguments += "--dedup-legal-notices=error-if-not-same-content"
    }
    disablePlugin.get().forEach {
      arguments += "--disable-plugin"
      arguments += it
    }
    value("--endian", endian)
    joined("--exclude-files", excludeFiles)
    joined("--exclude-resources", excludeResources)
    flag("--generate-cds-archive", generateCdsArchive)
    flag("--ignore-signing-information", ignoreSigningInformation)
    joined("--include-locales", includeLocales)
    joined("--limit-modules", limitModules)
    flag("--no-header-files", noHeaderFiles)
    flag("--no-man-pages", noManPages)
    flag("--strip-debug", stripDebug)
    flag("--strip-java-debug-attributes", stripJavaDebugAttributes)
    flag("--strip-native-commands", stripNativeCommands)
    value("--vendor-bug-url", vendorBugUrl)
    value("--vendor-version", vendorVersion)
    value("--vendor-vm-bug-url", vendorVmBugUrl)
    flag("--verbose", verbose)
    value("--vm", vm)
    arguments += "--output"
    arguments += outputDirectory.absolutePath

    execOperations.exec {
      executable(jlink.absolutePath)
      args(arguments)
    }
  }
}
