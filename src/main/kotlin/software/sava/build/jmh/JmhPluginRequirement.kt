package software.sava.build.jmh

import org.gradle.api.GradleException
import org.gradle.api.Project
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
 * failOnError to JMH as a flag JMH reads as false. A plugin whose version cannot be read, a
 * locally built jar without a manifest version, passes with a warning.
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

  fun apply(project: Project) {
    val plugin = visiblePluginClass()
      ?: throw GradleException(if (declaredBelow(project)) declaredBelowMessage() else missingMessage())
    val version = plugin.`package`?.implementationVersion
    if (version == null) {
      project.logger.warn(unreadableVersionMessage(null))
    } else {
      val order = compareToCompiledAgainst(version)
      if (order == null) project.logger.warn(unreadableVersionMessage(version))
      else if (order < 0) throw GradleException(olderMessage(version))
    }
    project.pluginManager.apply(plugin)
    JmhConventions.configure(project)
  }

  /** The declaration a benchmark build adds, at the version the feature is compiled against. */
  fun declaration(): String = "id(\"$PLUGIN_ID\") version \"${JmhToolDefaults.JMH_GRADLE_PLUGIN}\" apply false"

  /** The dependency build logic that depends on sava-build declares beside it. */
  fun dependency(): String = "$PLUGIN_MODULE:${JmhToolDefaults.JMH_GRADLE_PLUGIN}"

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
      ?: throw GradleException("the JMH Gradle plugin's descriptor at $descriptor names no implementation-class; " + expected())
    return try {
      Class.forName(implementationClass, false, loader)
    } catch (e: ClassNotFoundException) {
      throw GradleException("the JMH Gradle plugin's descriptor at $descriptor names $implementationClass, which its classpath does not hold; " + expected(), e)
    } catch (e: LinkageError) {
      throw GradleException("the JMH Gradle plugin ($PLUGIN_ID) found by software.sava.build.feature.jmh could not be loaded ($e); " + expected(), e)
    }
  }

  /** Whether the project's script classloader holds the plugin's descriptor: Gradle's own lookup, on the loader it consults last. */
  private fun declaredBelow(project: Project): Boolean =
    project.buildscript.classLoader.getResource(PLUGIN_DESCRIPTOR) != null

  private fun expected(): String =
    "the feature is compiled and tested against ${JmhToolDefaults.JMH_GRADLE_PLUGIN}: declare\n  ${declaration()}"

  private fun places(): String =
    "in the plugins {} block that declares the feature's version: a standalone benchmark build's own, or, where " +
      "software.sava.build is applied in settings.gradle.kts, that settings plugins {} block, which serves the " +
      "feature loaded there (a subproject's own block is below the feature). Build logic that depends on " +
      "sava-build (buildSrc, an included build, a plugin under test) also declares a dependency on " +
      "${dependency()} beside it, for the feature loaded there."

  private fun missingMessage(): String =
    "software.sava.build.feature.jmh configures the JMH Gradle plugin ($PLUGIN_ID) but does not ship it, and none is " +
      "declared where the feature can see it. Declare\n" +
      "  ${declaration()}\n" +
      "${places()} The feature is compiled and tested against ${JmhToolDefaults.JMH_GRADLE_PLUGIN}; the Plugin Portal " +
      "serves the plugin unsigned, so a strict dependency verification policy pins the checksums of its jar, module " +
      "metadata, marker POM and, for IDE sync, sources jar."

  private fun declaredBelowMessage(): String =
    "software.sava.build.feature.jmh found the JMH Gradle plugin ($PLUGIN_ID) on this project's classpath but not on " +
      "the one the feature loads from, where the feature's classes cannot see it. Declare it\n" +
      "  ${declaration()}\n" +
      "${places()}"

  private fun olderMessage(version: String): String =
    "software.sava.build.feature.jmh is compiled and tested against the JMH Gradle plugin ($PLUGIN_ID) " +
      "${JmhToolDefaults.JMH_GRADLE_PLUGIN} and this build declares $version, which the feature refuses. Change the " +
      "declaration to\n" +
      "  ${declaration()}\n" +
      "(a sava-build release that moves this version moves a benchmark build's declaration and plugin pins with it)."

  private fun unreadableVersionMessage(version: String?): String =
    "software.sava.build.feature.jmh could not read the JMH Gradle plugin's ($PLUGIN_ID) version " +
      (if (version == null) "(no Implementation-Version in its manifest)" else "('$version' is not a version)") +
      "; the feature is compiled and tested against ${JmhToolDefaults.JMH_GRADLE_PLUGIN}, and 0.7.3 and earlier hand " +
      "failOnError to JMH as a flag JMH reads as false."
}
