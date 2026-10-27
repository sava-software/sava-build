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

  /**
   * Whether jlink links every provider of a service the linked modules use ('--bind-services').
   * Unset, it is off: binding pulls some forty JDK modules into a plain service image (javac,
   * jshell, jlink, jpackage, jdk.attach, jdk.jdwp.agent, java.desktop and what they require), and
   * on a JDK without jmods it pulls jdk.jlink in, which jlink refuses to link from a run-time
   * image. Without it a module reached only at run time, through ServiceLoader or a 'requires
   * static', has to be named in [addModules]: jdk.localedata for non-English formatting,
   * jdk.charsets, jdk.zipfs, jdk.crypto.cryptoki, an SLF4J provider, a JDBC driver; so do the
   * JDK's tools and agents, jdk.jfr for flight recording, jdk.jdwp.agent for -agentlib:jdwp,
   * jdk.attach. The image task prints what it left out and, until this property is set either
   * way, what binding would have linked, withholding true where binding would reach a module jlink
   * cannot link: an automatic module providing a used service, or one requiring it, which jlink
   * refuses, or jdk.jlink on a JDK without jmods (both on a Java 21 or newer toolchain, where the
   * report runs).
   */
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
