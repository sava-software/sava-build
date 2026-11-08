package software.sava.build.jmh

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import java.lang.module.ModuleDescriptor
import java.util.Properties

/**
 * Applies the JMH Gradle plugin (me.champeau.jmh) for 'software.sava.build.feature.jmh', which
 * configures that plugin but does not ship it: the Plugin Portal serves the plugin unsigned, so a
 * build that resolves it pins its checksums per version in its dependency verification policy, a
 * cost only a benchmark build should pay (the README's row for the feature says what such a
 * build declares and pins). The consumer declares the plugin in the plugins block that declares
 * the feature's version; this guard applies it, or fails naming the declaration to add, and
 * refuses a plugin older than the one the feature is compiled against
 * (JmhToolDefaults.JMH_GRADLE_PLUGIN), the floor the feature's former implementation dependency
 * enforced through conflict resolution: 0.7.3 and earlier, for one, hand the feature's
 * failOnError to JMH as a flag JMH reads as false. A plugin whose task has no --jmhArgs property
 * as 0.7.4 has it (the option 0.7.4 added, which the conventions read) is refused whatever its
 * version says, so a locally built jar without a manifest version passes, with a warning, only
 * when it has the property; a plugin newer than the compiled-against version passes with a
 * warning, since the conventions read its --jmhArgs handling and its failOnError wiring as that
 * version has them.
 *
 * Why this shape, so a later review need not weigh the others again: Gradle's guide to binary
 * plugins ("Avoiding plugin dependency conflicts") has a plugin that builds on another plugin's
 * API depend on it compileOnly, document that the user declares that plugin, and defend against
 * NoClassDefFoundError, which is this guard, applying the plugin itself since a feature with one
 * purpose must refuse rather than silently do nothing. A dependency on the plugin in the
 * feature's own marker POM would hang on Gradle's undocumented order of marker publication and
 * need markers in the test repository; a module of its own for the feature would double the
 * release path (signing, staging, floors, test-repository filters) for two benchmark builds.
 *
 * Nothing here names a type of that plugin, so the feature's classes link whether or not the
 * plugin is on their classpath; JmhConventions, which does, is loaded only once the plugin is on
 * a classpath this class can see. The plugin is found the way Gradle's plugin registry finds it,
 * by its descriptor, on this class's own loader, and the class the descriptor names is the one
 * checked and applied, so the check, the floor and the application meet one copy. A descriptor
 * the project's script classloader holds but this class's loader does not is a plugin declared
 * where the feature cannot see it (a plugins or buildscript block, or buildSrc, under the
 * classpath the feature loads from, or beside it): the conventions would end in a
 * NoClassDefFoundError, so it is refused by name, and nothing is applied on the way.
 */
internal object JmhPluginRequirement {

  const val PLUGIN_ID = "me.champeau.jmh"
  const val PLUGIN_MODULE = "me.champeau.jmh:jmh-gradle-plugin"
  private const val PLUGIN_DESCRIPTOR = "META-INF/gradle-plugins/$PLUGIN_ID.properties"
  private const val FEATURE = "software.sava.build.feature.jmh"
  private const val PLUGIN = "the JMH Gradle plugin ($PLUGIN_ID)"
  private const val TASK_CLASS = "me.champeau.jmh.JMHTask"
  private val logger = Logging.getLogger(JmhPluginRequirement::class.java)

  fun apply(project: Project) {
    val plugin = visiblePluginClass()
      ?: throw GradleException(if (declaredBelow(project)) declaredBelowMessage() else missingMessage())
    val version = plugin.`package`?.implementationVersion
    val verdict = verdict(version, hasJmhArgs(plugin.classLoader))
    verdict.refusal?.let { throw GradleException(it) }
    verdict.warning?.let { logger.warn(it) }
    project.pluginManager.apply(plugin)
    try {
      JmhConventions.configure(project)
    } catch (e: LinkageError) {
      throw GradleException(apiMismatchMessage(version, e), e)
    }
  }

  /** A plugin whose API moved under the conventions, which are compiled against one version: the error [e], with the declaration to keep. */
  internal fun apiMismatchMessage(version: String?, e: LinkageError): String =
    "$FEATURE could not run against $PLUGIN ${version ?: "of unknown version"} with the conventions it is " +
      "${compiledAgainst()} ($e): the plugin's API differs. ${declare()}"

  /** The declaration a benchmark build adds, at the version the feature is compiled against. */
  fun declaration(): String = "id(\"$PLUGIN_ID\") version \"${JmhToolDefaults.JMH_GRADLE_PLUGIN}\" apply false"

  /** The dependency build logic that depends on sava-build declares beside it. */
  fun dependency(): String = "$PLUGIN_MODULE:${JmhToolDefaults.JMH_GRADLE_PLUGIN}"

  /** The floor's verdict on a plugin: a refusal, a warning, or neither. */
  internal data class Verdict(val refusal: String? = null, val warning: String? = null)

  /**
   * The floor over the plugin's manifest [version] (null when the manifest has none) and whether
   * its task [hasJmhArgs], the property 0.7.4 added and the archive step reads: older than the
   * compiled-against version is refused; without the property the plugin is refused whatever its
   * version says (it predates 0.7.4, or its API moved); newer passes with a warning; a version that
   * cannot be read passes with a warning; the version itself passes.
   */
  internal fun verdict(version: String?, hasJmhArgs: Boolean): Verdict {
    val order = version?.let { compareToCompiledAgainst(it) }
    return when {
      version != null && order != null && order < 0 -> Verdict(refusal = olderMessage(version))
      !hasJmhArgs -> Verdict(refusal = lacksJmhArgsMessage(version))
      version == null || order == null -> Verdict(warning = unreadableVersionMessage(version))
      order > 0 -> Verdict(warning = newerMessage(version))
      else -> Verdict()
    }
  }

  /**
   * Whether the plugin's task class, on [loader], has the --jmhArgs property as 0.7.4 has it
   * (a Property-typed getJmhArgs), by name and signature and without initializing the class. A
   * task class that does not link is a plugin the feature cannot use, failed with its cause.
   */
  private fun hasJmhArgs(loader: ClassLoader): Boolean = try {
    Class.forName(TASK_CLASS, false, loader).getMethod("getJmhArgs").returnType == Property::class.java
  } catch (e: NoSuchMethodException) {
    false
  } catch (e: ClassNotFoundException) {
    false
  } catch (e: LinkageError) {
    throw GradleException("$FEATURE found $PLUGIN whose task class $TASK_CLASS could not be loaded ($e). The feature is ${compiledAgainst()}. ${declare()}", e)
  }

  /** Where [version] stands against the version the feature is compiled against; null when it is not a version. */
  internal fun compareToCompiledAgainst(version: String): Int? = compare(version, JmhToolDefaults.JMH_GRADLE_PLUGIN)

  /**
   * [left] against [right] in the JDK's module version order: numeric parts as numbers, a longer
   * dotted sequence above its prefix (0.7.4.1 above 0.7.4), and a '-' or '+' suffix below the
   * version it follows (0.7.4-rc1 and 0.7.4+1 below 0.7.4, where Gradle's own conflict resolution
   * would rank a '-1' above); null when either is not a version at all.
   */
  internal fun compare(left: String, right: String): Int? = runCatching {
    ModuleDescriptor.Version.parse(left).compareTo(ModuleDescriptor.Version.parse(right))
  }.getOrNull()

  /**
   * The plugin's implementation class, found by the descriptor on this class's loader and named by
   * it; null when that loader holds no descriptor. A descriptor whose class does not load is a
   * plugin the feature cannot use, failed here with the declaration the feature expects.
   */
  private fun visiblePluginClass(): Class<*>? {
    val loader = JmhPluginRequirement::class.java.classLoader
    val descriptor = loader.getResource(PLUGIN_DESCRIPTOR) ?: return null
    // No JVM-wide jar cache for the read, as Gradle's own descriptor read has it: a cached JarFile
    // would keep the plugin jar open for the daemon's life.
    val implementationClass = descriptor.openConnection().apply { useCaches = false }.getInputStream()
      .use { Properties().apply { load(it) } }
      .getProperty("implementation-class")
      ?: throw unusable(descriptor.toString(), "it names no implementation-class", null)
    return try {
      Class.forName(implementationClass, false, loader)
    } catch (e: ClassNotFoundException) {
      throw unusable(descriptor.toString(), "its classpath does not hold the $implementationClass it names", e)
    } catch (e: LinkageError) {
      throw unusable(descriptor.toString(), "$implementationClass could not be loaded ($e)", e)
    }
  }

  /** The descriptor the feature found is of a plugin it cannot use. */
  private fun unusable(descriptor: String, detail: String, cause: Throwable?): GradleException =
    GradleException("$FEATURE found a descriptor of $PLUGIN at $descriptor that it cannot use: $detail. The feature is " +
      "${compiledAgainst()}. ${declare()}", cause)

  /** Whether the project's script classloader holds the plugin's descriptor: Gradle's own lookup, on the loader it consults last. */
  private fun declaredBelow(project: Project): Boolean =
    project.buildscript.classLoader.getResource(PLUGIN_DESCRIPTOR) != null

  private fun compiledAgainst(): String = "compiled and tested against ${JmhToolDefaults.JMH_GRADLE_PLUGIN}"

  /**
   * The remedy of a placement refusal (the plugin missing or declared where the feature cannot see
   * it): the declaration, and where it goes.
   */
  private fun declareWhere(): String = "Declare\n  ${declaration()}\n${places()}"

  /**
   * The remedy of a version or API verdict, given to a build that already declares the plugin where
   * the feature sees it: the declaration to hold, and the form build logic uses.
   */
  private fun declare(): String =
    "Declare\n  ${declaration()}\nin the plugins {} block that has the plugin now (build logic that depends on sava-build declares " +
      "${dependency()} beside it)."

  /** Where the plugin is declared: on the classpath the feature loads from, whichever that is. */
  private fun places(): String =
    "in the plugins {} block that declares the feature's version, since the plugin must sit on the classpath the " +
      "feature loads from or on one above it: a standalone benchmark build's own block, a parent project's block " +
      "in a multi-project build, or, where software.sava.build is applied in settings.gradle.kts, that settings " +
      "plugins {} block (a subproject's own block is below the feature). Build " +
      "logic that depends on sava-build (buildSrc, an included build, a plugin under test) declares a dependency on " +
      "${dependency()} beside it for the feature it loads itself; where software.sava.build is also applied in " +
      "settings, the settings copy of the feature is the one that runs, and the settings block supplies the plugin."

  private fun missingMessage(): String =
    "$FEATURE configures $PLUGIN but does not ship it, and none is declared where the feature can see it. " +
      "${declareWhere()} The feature is ${compiledAgainst()}; the Plugin Portal serves the plugin unsigned, so a strict " +
      "dependency verification policy pins the checksums of its jar, module metadata, marker POM and, for IDE sync, " +
      "sources jar."

  private fun declaredBelowMessage(): String =
    "$FEATURE found $PLUGIN on this project's classpath but not on the one the feature loads from, where the " +
      "feature's classes cannot see it. ${declareWhere()}"

  private fun olderMessage(version: String): String =
    "$FEATURE is ${compiledAgainst()} and the plugin on its classpath is $version, which the feature refuses. " +
      "${declare()} Where the lower version arrived through a dependency of build logic or of another plugin, raise " +
      "that dependency to ${JmhToolDefaults.JMH_GRADLE_PLUGIN} instead. A sava-build release that moves this version " +
      "moves a benchmark build's declaration and plugin pins with it."

  private fun newerMessage(version: String): String =
    "$FEATURE is ${compiledAgainst()} and the plugin on its classpath is $version, newer. The conventions read the " +
      "plugin's --jmhArgs handling and its failOnError wiring as ${JmhToolDefaults.JMH_GRADLE_PLUGIN} has them and were " +
      "tested against that version alone, so a benchmark build keeps the declaration at that version until a " +
      "sava-build release moves it. ${declare()}"

  private fun unreadable(version: String?): String =
    "$FEATURE could not read the version of $PLUGIN " +
      (if (version == null) "(no Implementation-Version in its manifest)" else "('$version' is not a version)")

  private fun unreadableVersionMessage(version: String?): String =
    "${unreadable(version)}; the feature is ${compiledAgainst()}, and 0.7.3 and earlier hand failOnError to JMH as a " +
      "flag JMH reads as false. The plugin's task has the --jmhArgs property as ${JmhToolDefaults.JMH_GRADLE_PLUGIN} " +
      "has it, so it passes."

  private fun lacksJmhArgsMessage(version: String?): String =
    "$FEATURE is ${compiledAgainst()}, and the task of $PLUGIN on its classpath " +
      (if (version == null) "(no Implementation-Version in its manifest) " else "(version $version) ") +
      "is without the --jmhArgs property as ${JmhToolDefaults.JMH_GRADLE_PLUGIN} has it (0.7.4 added the option, and " +
      "the feature's archive step reads it): the plugin predates that version or its API moved, and the feature " +
      "refuses it. ${declare()}"
}
