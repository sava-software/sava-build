import DependencyAnalysisFixture.Declaration
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * sava-build does not ship the dependency-analysis plugin (com.autonomousapps): the published
 * module declares no dependency on it, a consumer that declares nothing resolves nothing of it
 * and has no module-directive scope check, and a consumer that adds the README's line to its
 * settings plugins block has the check on every module's 'check' again, with the plugin's root
 * aggregate. The check is java-module-dependencies' task over the plugin's advice, and its type
 * extends a class of the plugin, so 'software.sava.build.check.dependencies' wires it by name and
 * the plugin has to sit on the class path sava-build loads from: a declaration in a project's own
 * plugins block does not reach it. The on-demand form and the project-script spellings are
 * DependencyAnalysisOnDemandFunctionalTest's.
 */
class DependencyAnalysisIsolationFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val fixture by lazy { DependencyAnalysisFixture(fixtureDir) }

  @Test
  fun `the published module declares no dependency on the dependency-analysis plugin`() {
    val module = savaBuildTestArtifact("module").readText()
    val pom = savaBuildTestArtifact("pom").readText()
    // A dependency the module keeps, so an empty or differently shaped file cannot pass.
    assertTrue(module.contains("\"module\": \"java-module-dependencies\""), module)
    assertTrue(pom.contains("<artifactId>java-module-dependencies</artifactId>"), pom)
    assertFalse(module.contains("autonomousapps"), "the module metadata names the dependency-analysis plugin:\n$module")
    assertFalse(pom.contains("autonomousapps"), "the POM names the dependency-analysis plugin:\n$pom")
  }

  @Test
  fun `a build that declares nothing resolves nothing of the plugin and has no scope check`() {
    // Module b's directive is the one the check refuses: nothing refuses it here.
    fixture.write(Declaration.NONE)
    val (cold, reused) = assertConfigurationCacheRoundTrip { fixture.runner(":b:check", "--warning-mode=fail").build() }
    assertEquals(TaskOutcome.SUCCESS, cold.task(":b:compileJava")?.outcome, cold.output)
    assertEquals(TaskOutcome.SUCCESS, cold.task(":b:check")?.outcome, cold.output)
    assertNull(cold.task(":b:checkModuleDirectivesScope"), cold.output)
    assertNull(reused.task(":b:checkModuleDirectivesScope"), reused.output)
    val classpath = settingsClasspath(cold.output)
    assertTrue(classpath.contains("software.sava:sava-build:$savaBuildTestRepoVersion"), classpath)
    assertTrue(classpath.contains("org.gradlex:java-module-dependencies:"), classpath)
    for (group in DependencyAnalysisFixture.PLUGIN_GROUPS) {
      assertFalse(classpath.contains(group), "the settings classpath resolves $group: $classpath")
    }
    // None of the plugin's tasks, the root aggregate among them.
    val health = fixture.runner("buildHealth").buildAndFail()
    assertTrue(health.output.contains("Task 'buildHealth' not found"), health.output)
  }

  @Test
  fun `the settings plugin line puts the scope check on every module's check`() {
    fixture.write(Declaration.ALWAYS, unusedModule = true)
    val plain = fixture.runner(":b:check").buildAndFail()
    assertEquals(TaskOutcome.FAILED, plain.task(":b:checkModuleDirectivesScope")?.outcome, plain.output)
    assertTrue(plain.output.contains("Please add the following requires directives"), plain.output)
    assertTrue(plain.output.contains(DependencyAnalysisFixture.TRANSITIVE), plain.output)
    val classpath = settingsClasspath(plain.output)
    assertTrue(classpath.contains("com.autonomousapps:dependency-analysis-gradle-plugin:${DependencyAnalysisFixture.version}"), classpath)

    val unused = fixture.runner(":c:check").buildAndFail()
    assertEquals(TaskOutcome.FAILED, unused.task(":c:checkModuleDirectivesScope")?.outcome, unused.output)
    assertTrue(unused.output.contains("Please remove the following requires directives"), unused.output)

    // A module with nothing to correct runs the check too, and passes it.
    val clean = fixture.runner(":a:check").build()
    assertEquals(TaskOutcome.SUCCESS, clean.task(":a:checkModuleDirectivesScope")?.outcome, clean.output)
    assertEquals(TaskOutcome.SUCCESS, clean.task(":a:check")?.outcome, clean.output)
  }

  @Test
  fun `with the settings plugin line a corrected module passes and the root aggregate runs`() {
    fixture.write(Declaration.ALWAYS, bRequires = DependencyAnalysisFixture.TRANSITIVE)
    val (cold, reused) = assertConfigurationCacheRoundTrip { fixture.runner(":b:check", "--warning-mode=fail").build() }
    assertEquals(TaskOutcome.SUCCESS, cold.task(":b:checkModuleDirectivesScope")?.outcome, cold.output)
    assertEquals(TaskOutcome.SUCCESS, cold.task(":b:check")?.outcome, cold.output)
    assertEquals(TaskOutcome.SUCCESS, reused.task(":b:check")?.outcome, reused.output)

    val health = fixture.runner("buildHealth", "--warning-mode=fail").build()
    assertEquals(TaskOutcome.SUCCESS, health.task(":buildHealth")?.outcome, health.output)
    assertEquals(TaskOutcome.SUCCESS, health.task(":generateBuildHealth")?.outcome, health.output)
    // The aggregate takes every module's advice: module a's is computed here, module b's by the check above.
    assertEquals(TaskOutcome.SUCCESS, health.task(":a:filterAdvice")?.outcome, health.output)
    assertEquals(TaskOutcome.UP_TO_DATE, health.task(":b:filterAdvice")?.outcome, health.output)
  }

  @Test
  fun `a declaration in a project's own plugins block alone cannot reach the check`() {
    // Below the class path that loaded sava-build and java-module-dependencies, whose bridge
    // then cannot load the class its task extends. The README names this failure.
    fixture.write(
      Declaration.NONE,
      bBuild = "plugins {\n  id(\"com.autonomousapps.dependency-analysis\") version \"${DependencyAnalysisFixture.version}\"\n}\n",
    )
    val result = fixture.runner(":b:check").buildAndFail()
    assertTrue(result.output.contains("NoClassDefFoundError: com/autonomousapps/AbstractPostProcessingTask"), result.output)
  }
}
