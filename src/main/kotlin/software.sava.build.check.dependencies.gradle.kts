import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck

plugins {
  id("java")
  id("org.gradlex.java-module-dependencies")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }

// The module-directive scope check exists only in a build that declares the dependency-analysis
// plugin, which sava-build does not ship (README: Dependency analysis): java-module-dependencies
// registers 'checkModuleDirectivesScope' when that plugin is applied. Matched by name and lazily,
// since the task's type extends a class of that plugin and cannot be loaded without it.
tasks.check {
  dependsOn(tasks.named { it == "checkModuleDirectivesScope" })
}
