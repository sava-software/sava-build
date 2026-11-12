import DependencyAnalysisFixture.Declaration
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The README's on-demand form of the dependency analysis: a conditional settings buildscript
 * block and a conditional application, so the plugin is on the settings classpath only for an
 * invocation that passes -PdependencyAudit. An ordinary build resolves nothing of the plugin and
 * has no scope check; an audit build has the check and fails module b's plain requires; neither
 * reuses the other's configuration. A project script that configures the analysis has to compile
 * in both, which the typed block does not and the README's withPlugin spelling does.
 */
class DependencyAnalysisOnDemandFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val fixture by lazy { DependencyAnalysisFixture(fixtureDir) }

  private fun reusedConfiguration(result: BuildResult) = result.output.contains("Reusing configuration cache")

  private fun assertOrdinary(result: BuildResult, reused: Boolean) {
    assertEquals(TaskOutcome.SUCCESS, result.task(":b:check")?.outcome, result.output)
    assertNull(result.task(":b:checkModuleDirectivesScope"), result.output)
    assertEquals(reused, reusedConfiguration(result), result.output)
    if (!reused) {
      val classpath = settingsClasspath(result.output)
      assertTrue(classpath.contains("org.gradlex:java-module-dependencies:"), classpath)
      for (group in DependencyAnalysisFixture.PLUGIN_GROUPS) {
        assertFalse(classpath.contains(group), "an ordinary build resolves $group: $classpath")
      }
    }
  }

  private fun assertAuditRefusesPlainRequires(result: BuildResult, reused: Boolean) {
    assertEquals(TaskOutcome.FAILED, result.task(":b:checkModuleDirectivesScope")?.outcome, result.output)
    assertTrue(result.output.contains(DependencyAnalysisFixture.TRANSITIVE), result.output)
    assertFalse(result.output.contains("NoClassDefFoundError"), result.output)
    assertEquals(reused, reusedConfiguration(result), result.output)
    if (!reused) {
      val classpath = settingsClasspath(result.output)
      assertTrue(classpath.contains("com.autonomousapps:dependency-analysis-gradle-plugin:${DependencyAnalysisFixture.version}"), classpath)
    }
  }

  @Test
  fun `the plugin is resolved and the check runs only for an invocation that asks`() {
    fixture.write(Declaration.ON_DEMAND)
    assertOrdinary(fixture.runner(":b:check", "--warning-mode=fail").build(), reused = false)
    assertOrdinary(fixture.runner(":b:check", "--warning-mode=fail").build(), reused = true)
    val audit = arrayOf(DependencyAnalysisFixture.AUDIT, ":b:check")
    assertAuditRefusesPlainRequires(fixture.runner(*audit).buildAndFail(), reused = false)
    assertAuditRefusesPlainRequires(fixture.runner(*audit).buildAndFail(), reused = true)
    assertOrdinary(fixture.runner(":b:check", "--warning-mode=fail").build(), reused = false)
  }

  @Test
  fun `an audit passes a corrected module and runs the root aggregate`() {
    fixture.write(Declaration.ON_DEMAND, bRequires = DependencyAnalysisFixture.TRANSITIVE, unusedModule = true)
    val corrected = fixture.runner(DependencyAnalysisFixture.AUDIT, ":b:check", "--warning-mode=fail").build()
    assertEquals(TaskOutcome.SUCCESS, corrected.task(":b:checkModuleDirectivesScope")?.outcome, corrected.output)
    assertEquals(TaskOutcome.SUCCESS, corrected.task(":b:check")?.outcome, corrected.output)
    val unused = fixture.runner(DependencyAnalysisFixture.AUDIT, ":c:check").buildAndFail()
    assertEquals(TaskOutcome.FAILED, unused.task(":c:checkModuleDirectivesScope")?.outcome, unused.output)
    // The root aggregate exists under an audit; at the plugin's default severity it reports
    // module c's advice without failing. The report is read, since the task succeeds as well
    // over an aggregate that left a module out.
    val health = fixture.runner(DependencyAnalysisFixture.AUDIT, "buildHealth").build()
    assertEquals(TaskOutcome.SUCCESS, health.task(":buildHealth")?.outcome, health.output)
    val report = File(fixtureDir, "build/reports/dependency-analysis/build-health-report.txt").readText()
    assertTrue(report.contains("Advice for :c"), report)
  }

  @Test
  fun `the withPlugin spelling ignores a source set under an audit and compiles without the plugin`() {
    val scratch = DependencyAnalysisFixture.SCRATCH_SUITE
    fixture.write(
      Declaration.ON_DEMAND,
      bRequires = DependencyAnalysisFixture.TRANSITIVE,
      bBuild = scratch + "\n" + DependencyAnalysisFixture.ignoreScratch,
      scratchSource = true,
    )
    // Without the plugin nothing analyses the scratch source set, so check does not compile it.
    val ordinary = fixture.runner(":b:check", "--warning-mode=fail").build()
    assertEquals(TaskOutcome.SUCCESS, ordinary.task(":b:check")?.outcome, ordinary.output)
    assertNull(ordinary.task(":b:checkModuleDirectivesScope"), ordinary.output)
    assertNull(ordinary.task(":b:compileScratchJava"), ordinary.output)

    val audit = fixture.runner(DependencyAnalysisFixture.AUDIT, ":b:check", "--warning-mode=fail").build()
    assertEquals(TaskOutcome.SUCCESS, audit.task(":b:checkModuleDirectivesScope")?.outcome, audit.output)
    assertEquals(TaskOutcome.SUCCESS, audit.task(":b:check")?.outcome, audit.output)
    assertNull(audit.task(":b:compileScratchJava"), audit.output)

    // The control: the same audit without the block analyses the source set and refuses the
    // suite's own dependencies, so the block is what the pass above rests on.
    fixture.source("b/build.gradle.kts", scratch)
    val control = fixture.runner(DependencyAnalysisFixture.AUDIT, ":b:check").buildAndFail()
    assertEquals(TaskOutcome.FAILED, control.task(":b:checkModuleDirectivesScope")?.outcome, control.output)
    assertTrue(control.output.contains("scratchModuleInfo {"), control.output)
  }

  @Test
  fun `the typed block compiles only where the plugin is present`() {
    val script = DependencyAnalysisFixture.SCRATCH_SUITE + "\n" + DependencyAnalysisFixture.TYPED_IGNORE_SCRATCH
    fixture.write(Declaration.ON_DEMAND, bRequires = DependencyAnalysisFixture.TRANSITIVE, bBuild = script, scratchSource = true)
    // What a consumer that keeps the typed block sees once the plugin is no longer always there.
    val ordinary = fixture.runner(":b:check").buildAndFail()
    assertTrue(ordinary.output.contains("Unresolved reference 'dependencyAnalysis'"), ordinary.output)
    assertNull(ordinary.task(":b:check"), ordinary.output)

    val audit = fixture.runner(DependencyAnalysisFixture.AUDIT, ":b:check").build()
    assertEquals(TaskOutcome.SUCCESS, audit.task(":b:checkModuleDirectivesScope")?.outcome, audit.output)
    assertEquals(TaskOutcome.SUCCESS, audit.task(":b:check")?.outcome, audit.output)

    // The settings plugin line is where the plugin is always present, and where a consumer
    // script that keeps its typed block goes on compiling.
    fixture.write(Declaration.ALWAYS, bRequires = DependencyAnalysisFixture.TRANSITIVE, bBuild = script, scratchSource = true)
    val always = fixture.runner(":b:check").build()
    assertEquals(TaskOutcome.SUCCESS, always.task(":b:checkModuleDirectivesScope")?.outcome, always.output)
    assertEquals(TaskOutcome.SUCCESS, always.task(":b:check")?.outcome, always.output)
  }
}
