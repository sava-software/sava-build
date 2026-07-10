package software.sava.build.jlink

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the jlink image produced by the 'software.sava.build.feature.jlink'
 * convention plugin. Property names match the 'jlinkApplication' extension of the
 * retired 'com.github.iherasymenko.jlink' plugin so existing consumer builds keep working.
 */
abstract class JlinkApplicationExtension {
  abstract val mainModule: Property<String>
  abstract val mainClass: Property<String>
  abstract val applicationName: Property<String>

  /** Modules linked in addition to [mainModule] and everything it requires. */
  abstract val addModules: ListProperty<String>

  /** Default JVM options baked into the image ('--add-options'). */
  abstract val addOptions: ListProperty<String>
  abstract val bindServices: Property<Boolean>

  /** e.g. "zip-6" */
  abstract val compress: Property<String>
  abstract val dedupLegalNoticesErrorIfNotSameContent: Property<Boolean>
  abstract val disablePlugin: ListProperty<String>

  /** "little" or "big" */
  abstract val endian: Property<String>
  abstract val excludeFiles: ListProperty<String>
  abstract val excludeResources: ListProperty<String>
  abstract val generateCdsArchive: Property<Boolean>
  abstract val ignoreSigningInformation: Property<Boolean>
  abstract val includeLocales: ListProperty<String>

  /** Extra launcher scripts: name to "module/mainClass". A launcher named after
   *  [applicationName] is generated automatically when [mainModule] and [mainClass] are set. */
  abstract val launcher: MapProperty<String, String>
  abstract val limitModules: ListProperty<String>
  abstract val noHeaderFiles: Property<Boolean>
  abstract val noManPages: Property<Boolean>
  abstract val stripDebug: Property<Boolean>
  abstract val stripJavaDebugAttributes: Property<Boolean>
  abstract val stripNativeCommands: Property<Boolean>
  abstract val vendorBugUrl: Property<String>
  abstract val vendorVersion: Property<String>
  abstract val vendorVmBugUrl: Property<String>
  abstract val verbose: Property<Boolean>

  /** "client", "server", "minimal" or "all" */
  abstract val vm: Property<String>
}
