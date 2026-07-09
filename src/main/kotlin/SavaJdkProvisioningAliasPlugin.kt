import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging

/**
 * Deprecated alias for 'software.sava.build.feature.jdk-provisioning'.
 *
 * Kept as a class-based plugin because a precompiled script plugin named
 * 'software.sava.build.feature-jdk-provisioning.settings.gradle.kts' would generate the same
 * JVM class name as the plugin it aliases ('-' and '.' both mangle to '_').
 */
class SavaJdkProvisioningAliasPlugin : Plugin<Settings> {
  override fun apply(settings: Settings) {
    Logging.getLogger(SavaJdkProvisioningAliasPlugin::class.java).warn(
      "Plugin 'software.sava.build.feature-jdk-provisioning' is deprecated; " +
        "use 'software.sava.build.feature.jdk-provisioning' instead."
    )
    settings.pluginManager.apply("software.sava.build.feature.jdk-provisioning")
  }
}
