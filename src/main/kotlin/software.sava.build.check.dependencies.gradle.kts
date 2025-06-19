import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesOrderingCheck
import org.gradlex.javamodule.dependencies.tasks.ModuleDirectivesScopeCheck

plugins {
  id("java")
  id("com.autonomousapps.dependency-analysis")
}

tasks.withType<ModuleDirectivesOrderingCheck>().configureEach { enabled = false }

tasks.check {
  dependsOn(tasks.withType<ModuleDirectivesScopeCheck>())
}
