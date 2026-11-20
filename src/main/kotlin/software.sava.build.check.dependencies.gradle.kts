import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck

plugins {
  id("java")
  id("org.gradlex.java-module-dependencies")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }

// The module-directive scope check exists only in a build that declares the dependency-analysis
// plugin, which sava-build does not ship (README: Dependency analysis): java-module-dependencies
// registers 'checkModuleDirectivesScope' when that plugin is applied, so 'check' depends on it,
// by name, under the same plugin id. Named, since the task's type extends a class of that plugin
// and cannot be loaded without it. Not a name filter over the task container: resolving one
// realized every task of the module on every 'check', recommendModuleVersions among them, whose
// constructor creates a latest.release configuration that a write of verification metadata
// then resolves.
pluginManager.withPlugin("com.autonomousapps.dependency-analysis") {
  tasks.check {
    dependsOn("checkModuleDirectivesScope")
  }
}
