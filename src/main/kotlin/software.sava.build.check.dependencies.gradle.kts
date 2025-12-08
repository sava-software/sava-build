import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck
import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesScopeCheck

plugins {
  id("java")
  id("com.autonomousapps.dependency-analysis")
  id("org.gradlex.java-module-dependencies")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }

tasks.check {
  dependsOn(tasks.withType<ModuleDirectivesScopeCheck>())
}
