import org.gradle.jvm.toolchain.JavaToolchainService
import software.sava.build.jlink.JlinkApplicationExtension
import software.sava.build.jlink.JlinkImageTask
import software.sava.build.jlink.JlinkModulesImageTask
import software.sava.build.jlink.JlinkRunImageTask

plugins {
  id("java")
}

// In-house replacement for the retired 'com.github.iherasymenko.jlink' plugin: the tasks
// invoke the toolchain JDK's own jlink tool directly. The 'jlinkApplication' extension and the 'image', 'imageRun' and
// 'imageModules' tasks keep the upstream names, properties and 'build/images/<applicationName>'
// output layout, so consumer builds, scripts, and Dockerfiles are unaffected.

val jlinkApplication = extensions.create<JlinkApplicationExtension>("jlinkApplication")
jlinkApplication.applicationName.convention(project.name)
jlinkApplication.bindServices.convention(true)
jlinkApplication.ignoreSigningInformation.convention(true)

plugins.withId("application") {
  val application = the<JavaApplication>()
  jlinkApplication.applicationName.convention(provider { application.applicationName })
  jlinkApplication.mainModule.convention(application.mainModule)
  jlinkApplication.mainClass.convention(application.mainClass)
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

val mainModuleAndClass = jlinkApplication.mainModule
  .zip(jlinkApplication.mainClass) { module, mainClass -> "$module/$mainClass" }

val image = tasks.register<JlinkImageTask>("image") {
  group = "build"
  description = "Builds a jlink image of the project JVM application"
  javaLauncher.convention(javaToolchains.launcherFor(java.toolchain))
  modulePath.from(tasks.named("jar"), configurations.named("runtimeClasspath"))
  output.convention(layout.buildDirectory.dir(jlinkApplication.applicationName.map { "images/$it" }))
  addModules.convention(
    jlinkApplication.mainModule.map { listOf(it) }.orElse(listOf())
      .zip(jlinkApplication.addModules) { main, extra -> (main + extra).distinct() }
  )
  launcher.convention(
    jlinkApplication.applicationName
      .zip(mainModuleAndClass) { name, moduleAndClass -> mapOf(name to moduleAndClass) }
      .orElse(mapOf())
      .zip(jlinkApplication.launcher) { defaults, custom -> defaults + custom }
  )
  addOptions.convention(jlinkApplication.addOptions)
  bindServices.convention(jlinkApplication.bindServices)
  compress.convention(jlinkApplication.compress)
  dedupLegalNoticesErrorIfNotSameContent.convention(jlinkApplication.dedupLegalNoticesErrorIfNotSameContent)
  disablePlugin.convention(jlinkApplication.disablePlugin)
  endian.convention(jlinkApplication.endian)
  excludeFiles.convention(jlinkApplication.excludeFiles)
  excludeResources.convention(jlinkApplication.excludeResources)
  generateCdsArchive.convention(jlinkApplication.generateCdsArchive)
  ignoreSigningInformation.convention(jlinkApplication.ignoreSigningInformation)
  includeLocales.convention(jlinkApplication.includeLocales)
  limitModules.convention(jlinkApplication.limitModules)
  noHeaderFiles.convention(jlinkApplication.noHeaderFiles)
  noManPages.convention(jlinkApplication.noManPages)
  stripDebug.convention(jlinkApplication.stripDebug)
  stripJavaDebugAttributes.convention(jlinkApplication.stripJavaDebugAttributes)
  stripNativeCommands.convention(jlinkApplication.stripNativeCommands)
  vendorBugUrl.convention(jlinkApplication.vendorBugUrl)
  vendorVersion.convention(jlinkApplication.vendorVersion)
  vendorVmBugUrl.convention(jlinkApplication.vendorVmBugUrl)
  verbose.convention(jlinkApplication.verbose)
  vm.convention(jlinkApplication.vm)
}

tasks.register<JlinkRunImageTask>("imageRun") {
  group = "application"
  description = "Runs the project as a JVM application bundled with jlink"
  imageDirectory.convention(image.flatMap { it.output })
  mainModule.convention(jlinkApplication.mainModule)
  mainClass.convention(jlinkApplication.mainClass)
}

tasks.register<JlinkModulesImageTask>("imageModules") {
  group = "help"
  description = "Displays modules of the project JVM application bundled with jlink"
  imageDirectory.convention(image.flatMap { it.output })
}
