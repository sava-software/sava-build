package software.sava.build.jlink

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
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

  /**
   * The toolchain JDK files the image's content follows, hashed when the task runs: 'release', which
   * names the JDK's version and so the modules a link takes from its jmods, and the two files a link
   * from a JDK without jmods (Temurin 24 and later) copies from the installation as they stand rather
   * than from a hashed module, 'lib/security/cacerts' and 'lib/tzdb.dat' (jlink's
   * upgrade_files_java.base.conf). [javaLauncher]'s own fingerprint is the toolchain spec and the
   * language version only, so without these a JDK update within a feature release, or a trust-store
   * update under a jmod-less JDK whose cacerts the OS maintains, would leave a linked image up to
   * date. Files rather than the launcher's vendor and runtime version: Gradle caches that metadata
   * per installation for the daemon's life and stores it in the configuration cache entry, so after
   * an update in place (the same path holding a new JDK) it still reports the old version. A
   * collection rather than three files, since cacerts may be a dangling link on a JDK whose trust
   * store the OS has not generated yet: a missing file fingerprints too, and a vanished JDK still
   * stops the task at its jlink check.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  val toolchainFiles: FileCollection
    get() = objects.fileCollection().from(
      javaLauncher.map { launcher ->
        val home = launcher.metadata.installationPath
        listOf(home.file("release"), home.file("lib/security/cacerts"), home.file("lib/tzdb.dat"))
      }
    )

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

  @get:Inject
  protected abstract val objects: ObjectFactory

  @TaskAction
  fun execute() {
    val toolchain = javaLauncher.get().metadata
    val javaHome = toolchain.installationPath.asFile
    val jlink = File(javaHome, "bin/${executableName("jlink")}")
    check(jlink.isFile) { "jlink not found in the toolchain JDK: $jlink" }

    val modules = addModules.get().toMutableList()
    check(modules.isNotEmpty()) {
      "No modules to link. Set 'jlinkApplication.mainModule' or 'jlinkApplication.addModules'."
    }
    // JDK modules the requested options need but that only service binding used to pull in.
    fun ensure(module: String, reason: String) {
      if (module !in modules) {
        modules += module
        logger.info("Linking $module into the image: $reason")
      }
    }
    // SunEC moved into java.base in JDK 22. On an older JDK an image without jdk.crypto.ec has no
    // EC signatures, no X25519 and so almost no TLS, and nothing reports it until run time.
    if (toolchain.languageVersion.asInt() < 22) {
      ensure("jdk.crypto.ec", "the SunEC provider lives there on Java ${toolchain.languageVersion}")
    }
    // The java.util.random algorithms had a module of their own from JDK 17 (JEP 356) to 22, then
    // moved into java.base (JDK-8330005); on those releases RandomGenerator.getDefault() and
    // RandomGenerator.of() throw in an image without jdk.random.
    if (toolchain.languageVersion.asInt() in 17..22) {
      ensure("jdk.random", "the java.util.random algorithms live there on Java ${toolchain.languageVersion}")
    }
    if (includeLocales.get().isNotEmpty()) {
      ensure("jdk.localedata", "--include-locales takes the locale data from it")
    }
    // The archive is dumped from the JDK's default class list, which names java.util.logging
    // classes; without the module jlink prints a CDS warning per class.
    if (generateCdsArchive.getOrElse(false)) {
      ensure("java.logging", "the CDS class list names java.util.logging classes")
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
    reportLeftOut(outputDirectory, javaHome, toolchain.languageVersion.asInt())
  }

  /**
   * Names what the image does not hold: the module-path modules jlink left out, and what service
   * binding would have added. jlink links the modules named in --add-modules and what they
   * require; a module reached only at run time, through ServiceLoader or a 'requires static', is
   * left out unless it is named in addModules or bindServices pulls it in as a provider. The
   * analysis runs [LinkReport] on the toolchain JDK: the daemon's JVM may be older than the
   * toolchain and unable to read the module descriptors it compiled.
   */
  private fun reportLeftOut(imageDirectory: File, javaHome: File, toolchainRelease: Int) {
    // Nothing below shows at --quiet, so the analysis is not worth a JVM then.
    if (!logger.isLifecycleEnabled) return
    if (toolchainRelease < 21) {
      logger.lifecycle(
        "Not reporting the modules left out of ${imageDirectory.name}: the report runs on the toolchain JDK " +
          "and needs Java 21, and the toolchain is Java $toolchainRelease; imageModules shows what the image holds."
      )
      return
    }
    if (!File(imageDirectory, "release").isFile) {
      logger.lifecycle(
        "Not reporting the modules left out of ${imageDirectory.name}: without a release file " +
          "(the release-info plugin is disabled) the image does not say which modules it holds."
      )
      return
    }
    val java = File(javaHome, "bin/${executableName("java")}")
    // The jar this class was loaded from. A Gradle daemon serves the plugin's original jar
    // whenever its instrumentation agent is on, which is every ordinary daemon; only an agent-less
    // run (TestKit in debug mode, gradle --foreground) hands out the instrumented copy, whose
    // classes need Gradle's own on the classpath.
    val codeLocation = LinkReport::class.java.protectionDomain.codeSource?.location
    if (codeLocation == null) {
      logger.warn("Could not report the modules left out of ${imageDirectory.name}: the plugin's classes have no location to run LinkReport from.")
      return
    }
    val reportClasses = File(codeLocation.toURI())
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    // With ignoreExitValue set, exec only throws when the process cannot start at all.
    val result = try {
      execOperations.exec {
        executable(java.absolutePath)
        args("-cp", reportClasses.absolutePath, LinkReport::class.java.name, imageDirectory.absolutePath)
        if (bindServices.getOrElse(false)) args("--bind-services")
        val limit = limitModules.get()
        if (limit.isNotEmpty()) args("--limit-modules", limit.joinToString(","))
        args(modulePath.files.map { it.absolutePath })
        standardOutput = stdout
        errorOutput = stderr
        isIgnoreExitValue = true
      }
    } catch (e: Exception) {
      logger.warn("Could not report the modules left out of ${imageDirectory.name}: $java did not start: $e")
      return
    }
    if (result.exitValue != 0) {
      // The launcher's option banners ("Picked up JAVA_TOOL_OPTIONS: ...") come first and may name an Error.
      val lines = stderr.toString().lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("Picked up ") && !it.startsWith("NOTE: Picked up ") }
      val cause = lines.firstOrNull { it.contains("Exception") || it.contains("Error") } ?: lines.firstOrNull().orEmpty()
      logger.warn("Could not report the modules left out of ${imageDirectory.name} (exit ${result.exitValue}): $cause")
      return
    }
    val records = stdout.toString().lines().filter { it.isNotBlank() }.map { it.split('\t') }
    val leftOut = records.filter { it[0] == "left-out" }
    if (leftOut.isNotEmpty()) {
      val named = leftOut.map { (_, name, kind, role) ->
        name + when {
          kind == "automatic" -> " (automatic module, which jlink cannot link)"
          kind == "unlinkable" -> " (requires an automatic module, so jlink cannot link it)"
          role == "provider" -> " (service provider)"
          else -> ""
        }
      }
      logger.lifecycle(
        "jlink left ${leftOut.size} module-path module(s) out of ${imageDirectory.name}: ${named.joinToString(", ")}. " +
          "No linked module requires them at link time (a 'requires static' does not count); " +
          "name the ones the application loads at run time in jlinkApplication.addModules."
      )
    }
    // What binding would have linked is news until the build has answered the question: an
    // explicit bindServices, either way, is the answer, and ends the notice, and with it any
    // complaint that the question could not be worked out.
    if (bindServices.isPresent) return
    records.filter { it[0] == "error" }.forEach {
      logger.warn("Could not work out what service binding would add to ${imageDirectory.name}: ${it.getOrNull(1)}")
    }
    val bindable = records.filter { it[0] == "bindable" }
    if (bindable.isNotEmpty()) {
      // Application providers first, then the JDK's; the modules those require are only counted.
      val providers = bindable.filter { it[3] == "provider" }
        .sortedBy { it[2] != "module-path" }
        .map { (_, name, origin) -> if (origin == "module-path") "$name (module path)" else name }
      val required = bindable.size - providers.size
      // Binding links jdk.jlink, through java.base's ToolProvider service, and jlink cannot link
      // that from a JDK without jmods: there, true is no way out of the notice, unless a limit keeps
      // jdk.jlink out of binding's reach.
      val jdkJlinkBindable = bindable.any { it[1] == "jdk.jlink" }
      val ending = if (!jdkJlinkBindable || File(javaHome, "jmods").isDirectory) {
        "Name the ones the application needs in jlinkApplication.addModules; setting " +
          "jlinkApplication.bindServices (false once they are named, or true) ends this notice."
      } else {
        "Name the ones the application needs in jlinkApplication.addModules (not jdk.jlink: this toolchain " +
          "has no jmods, so jlink cannot link it, and bindServices = true would); setting " +
          "jlinkApplication.bindServices = false once they are named ends this notice."
      }
      logger.lifecycle(
        "Service binding (bindServices = true) would have linked ${bindable.size} more module(s) into " +
          "${imageDirectory.name}: ${providers.joinToString(", ")}" +
          (if (required > 0) ", and $required module(s) they require" else "") + ". " + ending
      )
    }
  }
}
