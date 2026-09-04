package software.sava.build.hardening

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry
import software.sava.build.hardening.task.HardeningCertifyAllSelectionTask
import software.sava.build.hardening.task.HardeningCertifyAllTask
import software.sava.build.hardening.task.HardeningCertificationAggregatePublishTask
import javax.inject.Inject

/** Installs the aggregate anchor before child hardening projects contribute inventory. */
internal class HardeningCertificationRootPlugin @Inject constructor(
  private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {

  override fun apply(project: Project) {
    require(project == project.rootProject) {
      "HardeningCertificationRootPlugin can be applied only to the Gradle root project"
    }
    val identity = HardeningPluginIdentity.capture(HardeningCertificationRootPlugin::class.java)
    val identityService = project.gradle.sharedServices.registerIfAbsent(
      HardeningPluginIdentityService.SERVICE_NAME,
      HardeningPluginIdentityService::class.java,
    ) {
      parameters.applicationPluginSha256.set(identity.sha256)
      parameters.localRepoArtifactPath.set(HardeningPluginIdentityService.NO_LOCAL_ARTIFACT)
      parameters.applicationLocalRepoArtifactSha256.set(
        HardeningPluginIdentityService.NO_LOCAL_ARTIFACT)
    }
    val aggregateSession = project.gradle.sharedServices.registerIfAbsent(
      "hardeningCertificationAggregateSession",
      HardeningCertificationAggregateSession::class.java,
    ) {
      val buildPath = project.gradle.buildPath
      parameters.buildPath.set(buildPath)
      parameters.aggregateTaskPath.set(
        if (buildPath == ":") ":hardeningCertifyAll" else "$buildPath:hardeningCertifyAll")
      parameters.buildPath.disallowChanges()
      parameters.aggregateTaskPath.disallowChanges()
    }
    buildEventsListenerRegistry.onTaskCompletion(aggregateSession)

    val manifest = project.layout.projectDirectory.file(
      ".pitest-history/pitest-certification-all.tsv")
    val running = project.layout.projectDirectory.file(
      ".pitest-history/pitest-certification-all.running")
    val lock = project.layout.projectDirectory.file(
      ".pitest-history/pitest-certification-all.lock")

    val publish = project.tasks.register(
      "hardeningCertifyAllComplete",
      HardeningCertificationAggregatePublishTask::class.java,
    )
    publish.configure(Action<HardeningCertificationAggregatePublishTask> {
      description =
        "Internal to hardeningCertifyAll: hashes child receipts and publishes the root manifest."
      this.aggregateSession.set(aggregateSession)
      usesService(aggregateSession)
      gradleRootDirectory.set(project.layout.projectDirectory)
      manifestFile.set(manifest)
      runningFile.set(running)
    })

    val selection = project.tasks.register(
      "hardeningCertifyAllSelected",
      HardeningCertifyAllSelectionTask::class.java,
    )
    selection.configure(Action<HardeningCertifyAllSelectionTask> {
      description = "Internal to hardeningCertifyAll: establishes aggregate ownership."
      this.aggregateSession.set(aggregateSession)
      usesService(aggregateSession)
      gradleRootDirectory.set(project.layout.projectDirectory)
      manifestFile.set(manifest)
      runningFile.set(running)
      lockFile.set(lock)
    })

    val anchor = project.tasks.register(
      "hardeningCertifyAll",
      HardeningCertifyAllTask::class.java,
    )
    anchor.configure(Action<HardeningCertifyAllTask> {
      group = "verification"
      description =
        "Certifies every project using sava hardening and publishes a Gradle-root manifest."
      this.aggregateSession.set(aggregateSession)
      usesService(aggregateSession)
      gradleRootDirectory.set(project.layout.projectDirectory)
      manifestFile.set(manifest)
      runningFile.set(running)
      expectedPluginSha256.set(
        identityService.map { it.parameters.applicationPluginSha256.get() })
      excludedTaskNames.set(project.gradle.startParameter.excludedTaskNames.sorted())
      configureOnDemand.set(project.gradle.startParameter.isConfigureOnDemand)
      dependsOn(selection)
      finalizedBy(publish)
    })
  }
}
