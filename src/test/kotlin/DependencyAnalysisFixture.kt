import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A consumer build for the dependency-analysis tests: local modules only, a stub repository
 * serving the catalog platform (as SavaBuildConventionsSmokeTest has it) and the test JVM as the
 * toolchain, in which
 *  - module a exports a type,
 *  - module b requires a and returns a's type from a public method of an exported package, so the
 *    directive the scope check asks for is 'requires transitive',
 *  - module c, where asked for, requires a and never uses it.
 *
 * The ways a build declares the plugin are the README's (Dependency analysis), read from its
 * text with the tested version in the placeholder, so a recipe the README prints is one that ran
 * and a reworded recipe is the tested one.
 */
class DependencyAnalysisFixture(private val dir: File) {

  /** How the fixture's settings script declares the dependency-analysis plugin. */
  enum class Declaration {
    /** Nothing: the plugin is neither resolved nor applied. */
    NONE,

    /** The README's line in the settings plugins block. */
    ALWAYS,

    /** The README's buildscript block and conditional application, keyed by -PdependencyAudit. */
    ON_DEMAND,
  }

  companion object {
    const val PLAIN = "requires software.sava.test.a;"
    const val TRANSITIVE = "requires transitive software.sava.test.a;"

    /** The switch of the README's on-demand form. */
    const val AUDIT = "-PdependencyAudit"

    /** The version sava-build is tested against, from the catalog. */
    val version: String = savaBuildTestVersion("dependency-analysis-gradle-plugin")

    private val section: String = savaBuildRootFile("README.md").readText()
      .substringAfter("\n### Dependency analysis\n", "")
      .substringBefore("\n## ")
      .also { check(it.isNotEmpty()) { "README.md has no '### Dependency analysis' section" } }

    /** The section's one kotlin block that contains [needle], with the tested version in its placeholder. */
    fun recipe(needle: String): String {
      val blocks = Regex("```kotlin\\n([\\s\\S]*?)```").findAll(section)
        .map { it.groupValues[1] }.filter { needle in it }.toList()
      check(blocks.size == 1) {
        "expected one kotlin block with '$needle' in the README's Dependency analysis section, found ${blocks.size}"
      }
      val text = blocks.single()
      // A recipe that names the module or a plugin version does so through the placeholder.
      val namesVersion = "com.autonomousapps:" in text || "version \"" in text
      check(!namesVersion || "<dependency-analysis-version>" in text) {
        "the README recipe with '$needle' spells a version instead of the placeholder:\n$text"
      }
      return text.replace("<dependency-analysis-version>", version)
    }

    /** The line a build adds to its settings plugins block. */
    val alwaysLine: String = recipe("id(\"com.autonomousapps.build-health\") version")
      .lineSequence().single { "com.autonomousapps.build-health" in it }.trim()

    /** The conditional settings buildscript block. */
    val onDemandClasspath: String = recipe("buildscript {")

    /** The conditional application that follows the plugins block. */
    val onDemandApply: String = recipe("apply(plugin = \"com.autonomousapps.build-health\")")

    /** The project-script configuration that compiles with and without the plugin. */
    val ignoreScratch: String = recipe("withGroovyBuilder")

    /** The typed block, which needs the plugin's accessors. */
    const val TYPED_IGNORE_SCRATCH = "dependencyAnalysis {\n  issues {\n    ignoreSourceSet(\"scratch\")\n  }\n}\n"

    /** The scratch suite as HARDENING.md has consumers register it: a dependency of its own, no module-info, no test run. */
    val SCRATCH_SUITE = """
      testing {
        suites {
          register<JvmTestSuite>("scratch") {
            useJUnitJupiter()
            dependencies {
              implementation(project(":a"))
            }
            targets.configureEach {
              testTask.configure {
                enabled = false
              }
            }
          }
        }
      }
    """.trimIndent() + "\n"

    /** The component groups the plugin brings and sava-build's remaining dependencies do not. */
    val PLUGIN_GROUPS = listOf("com.autonomousapps", "com.squareup", "dev.zacsweers", "com.github.ben-manes", "com.google.guava")
  }

  fun write(
    declaration: Declaration,
    bRequires: String = PLAIN,
    bBuild: String? = null,
    unusedModule: Boolean = false,
    scratchSource: Boolean = false,
  ) {
    enableTestKitConfigurationCache(dir)
    File(dir, "settings.gradle.kts").writeText(
      buildString {
        appendLine(savaBuildPluginManagement)
        appendLine()
        if (declaration == Declaration.ON_DEMAND) appendLine(onDemandClasspath)
        appendLine("plugins {")
        appendLine("  id(\"software.sava.build\")")
        if (declaration == Declaration.ALWAYS) appendLine("  $alwaysLine")
        appendLine("}")
        appendLine()
        appendLine("rootProject.name = \"dependency-analysis\"")
        appendLine()
        appendLine("javaModules {")
        appendLine("  directory(\".\") {")
        appendLine("    group = \"software.sava.test\"")
        appendLine("    plugin(\"software.sava.build.java-module\")")
        appendLine("  }")
        appendLine("}")
        appendLine()
        appendLine("dependencyResolutionManagement {")
        appendLine("  repositories { maven { url = uri(\"stub-repo\") } }")
        appendLine("}")
        appendLine()
        if (declaration == Declaration.ON_DEMAND) appendLine(onDemandApply)
        appendLine("// Every component the settings classpath resolved, on one line the tests read.")
        appendLine(
          "println(\"SETTINGS_CLASSPATH=\" + buildscript.configurations.getByName(\"classpath\")" +
            ".incoming.resolutionResult.allComponents.joinToString(\",\") { it.id.displayName })"
        )
      }
    )
    source(
      "gradle/sava.properties",
      "solanaBOMVersion=0.0.0-smoke\n" +
        "javaVersion=${Runtime.version().feature()}\n" +
        "javaVendor=${System.getProperty("java.vendor")}\n",
    )
    source(
      "stub-repo/software/sava/solana-version-catalog/0.0.0-smoke/solana-version-catalog-0.0.0-smoke.pom",
      """
      <project xmlns="http://maven.apache.org/POM/4.0.0">
        <modelVersion>4.0.0</modelVersion>
        <groupId>software.sava</groupId>
        <artifactId>solana-version-catalog</artifactId>
        <version>0.0.0-smoke</version>
        <packaging>pom</packaging>
      </project>
      """.trimIndent() + "\n",
    )

    source("a/src/main/java/module-info.java", "module software.sava.test.a {\n  exports software.sava.test.a;\n}\n")
    source("a/src/main/java/software/sava/test/a/A.java", "package software.sava.test.a;\n\npublic final class A {\n}\n")

    moduleB(bRequires)
    source(
      "b/src/main/java/software/sava/test/b/B.java",
      "package software.sava.test.b;\n\nimport software.sava.test.a.A;\n\npublic final class B {\n  public A a() {\n    return new A();\n  }\n}\n",
    )
    if (bBuild != null) source("b/build.gradle.kts", bBuild)
    if (scratchSource) source("b/src/scratch/java/scratch/Driver.java", "package scratch;\n\nclass Driver {\n}\n")

    if (unusedModule) {
      source("c/src/main/java/module-info.java", "module software.sava.test.c {\n  $PLAIN\n  exports software.sava.test.c;\n}\n")
      source("c/src/main/java/software/sava/test/c/C.java", "package software.sava.test.c;\n\npublic final class C {\n}\n")
    }
  }

  /** Module b's descriptor with the given directive for module a. */
  fun moduleB(requires: String) =
    source("b/src/main/java/module-info.java", "module software.sava.test.b {\n  $requires\n  exports software.sava.test.b;\n}\n")

  fun source(path: String, text: String) {
    File(dir, path).apply {
      parentFile.mkdirs()
      writeText(text)
    }
  }

  /** A runner over the fixture with the test JVM as the only toolchain, so nothing is provisioned. */
  fun runner(vararg arguments: String): GradleRunner = savaBuildRunner(
    dir,
    *arguments, "--stacktrace", "--console=plain",
    "-Dorg.gradle.java.installations.auto-detect=false",
    "-Dorg.gradle.java.installations.auto-download=false",
    "-Dorg.gradle.java.installations.paths=${System.getProperty("java.home")}",
  )
}

/** The settings classpath line the fixture prints at configuration time; absent from a reused configuration. */
fun settingsClasspath(output: String): String =
  output.lineSequence().firstOrNull { it.startsWith("SETTINGS_CLASSPATH=") } ?: error("no SETTINGS_CLASSPATH line:\n$output")
