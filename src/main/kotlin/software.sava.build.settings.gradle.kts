plugins {
  id("com.autonomousapps.build-health")
  id("software.sava.build.base.repositories")
  id("org.gradlex.java-module-dependencies")
}

// Install the aggregate certification anchor while the root is guaranteed to be
// configured. Child feature plugins populate its exact inventory later; under
// configuration-on-demand they may be omitted, but the root task still exists to fail
// closed with its durable sentinel instead of becoming an unknown task.
gradle.beforeProject(org.gradle.api.Action<org.gradle.api.Project> {
  if (this == rootProject) {
    pluginManager.apply(
      software.sava.build.hardening.HardeningCertificationRootPlugin::class.java
    )
  }
})

// Announces a build running plugins from a local sava-build checkout instead of a
// published release. No-op unless '-PsavaBuildLocalRepo' is set; see the plugin's
// documentation for why the notice cannot live in the consumer's settings script.
// Applied by type rather than through a 'plugins {}' id: an id would need a
// 'gradlePlugin' registration and would then be published as a marker consumers could
// apply on their own, which is more surface than an internal notice deserves.
pluginManager.apply(SavaBuildLocalRepoNoticePlugin::class.java)

includeBuild(".")

// Publishing repositories opt in to the aggregation project (used for Maven Central
// bundling via 'software.sava.build.feature.publish-maven-central') by creating
// gradle/aggregation/build.gradle.kts.
if (file("gradle/aggregation/build.gradle.kts").exists()) {
  include(":aggregation")
  project(":aggregation").projectDir = file("gradle/aggregation")
}
