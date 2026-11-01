import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The gcp-kms convention excludes, on the KMS client and the libraries of its graph a module
 * may require directly, the runtime dependencies the client never uses on the fleet's path.
 * The excludes are a dependency's, so the POM and module-metadata writers publish them and a
 * dependency outside that family keeps its own graph. The first test reads the excludes as
 * declared and as both writers print them, without resolving, under the configuration cache;
 * the second resolves the client's runtime graph from the local cache or Maven Central and
 * holds the resolved set. The module-info path, a `requires google.cloud.kms` the
 * java-module-dependencies plugin turns into the dependency, needs the fleet's version
 * catalog from GitHub Packages, so it was validated in a clone of ravina (2026-10-31) rather
 * than here.
 */
class GcpKmsExclusionsFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  /** The convention's list, as the convention spells it. */
  private val excluded = listOf(
    "io.grpc:grpc-googleapis", "io.grpc:grpc-xds", "io.grpc:grpc-alts", "io.grpc:grpc-services", "io.grpc:grpc-inprocess",
    "dev.cel:runtime", "dev.cel:common", "dev.cel:protobuf",
    "com.google.re2j:re2j", "org.conscrypt:conscrypt-openjdk-uber", "org.threeten:threeten-extra",
    "org.apache.httpcomponents:httpclient", "org.apache.httpcomponents:httpcore", "commons-codec:commons-codec",
    "com.google.android:annotations", "com.google.code.findbugs:jsr305", "javax.annotation:javax.annotation-api",
  )

  private fun writeFixture(moduleRequires: Boolean = false) {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "gcp-kms-exclusions"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        import org.gradle.api.artifacts.component.ModuleComponentIdentifier

        plugins {
          `java-library`
          `maven-publish`
          id("software.sava.build.modules.gcp-kms")
          ${if (moduleRequires) "id(\"org.gradlex.java-module-dependencies\") version \"1.13.2\"" else ""}
        }

        group = "software.sava"
        version = "0.0.0"

        repositories { mavenCentral() }

        dependencies {
          implementation("com.google.cloud:google-cloud-kms:2.102.0")
          testImplementation("com.google.api:gax:2.87.0")
          testImplementation("com.google.api:gax-grpc:2.87.0:testlib")
          testImplementation("io.grpc:grpc-xds:1.83.0")
          implementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
        }

        ${if (moduleRequires) "testModuleInfo { requires(\"com.google.auth.oauth2\"); requires(\"com.google.auth\") }" else ""}

        publishing {
          publications {
            create<MavenPublication>("maven") { from(components["java"]) }
          }
        }

        tasks.register("printResolved") {
          val artifacts = configurations.runtimeClasspath.get().incoming.artifacts.resolvedArtifacts
          doLast {
            val names = artifacts.get().map { (it.id.componentIdentifier as ModuleComponentIdentifier).moduleIdentifier.toString() }.sorted()
            println("resolved runtimeClasspath: " + names.joinToString(" "))
          }
        }

        tasks.register("printExcludes") {
          val lines = listOf("implementation", "testImplementation").flatMap { configurationName ->
            configurations.getByName(configurationName).dependencies.withType<ModuleDependency>().map { dependency ->
              val excludes = dependency.excludeRules.map { "${'$'}{it.group}:${'$'}{it.module}" }.sorted()
              "${'$'}configurationName ${'$'}{dependency.group}:${'$'}{dependency.name} excludes ${'$'}{excludes.size}: ${'$'}{excludes.joinToString(" ")}"
            }
          }
          doLast { lines.forEach { println(it) } }
        }
      """.trimIndent() + "\n"
    )
  }

  /**
   * A named module requiring the client through java-module-dependencies, with the versions from a
   * platform of the build's own: the convention's patches give the client's family their module
   * names and the plugin the coordinates behind them, so the module path resolves and compiles.
   */
  private fun writeModulePathFixture() {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "gcp-kms-module-path"
        include("platform")
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "platform").mkdirs()
    File(fixtureDir, "platform/build.gradle.kts").writeText(
      """
        plugins { `java-platform` }
        dependencies { constraints { api("com.google.cloud:google-cloud-kms:2.102.0") } }
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        import org.gradle.api.artifacts.component.ModuleComponentIdentifier

        plugins {
          `java-library`
          id("software.sava.build.modules.gcp-kms")
          id("org.gradlex.java-module-dependencies") version "1.13.2"
        }

        repositories { mavenCentral() }

        dependencies { implementation(platform(project(":platform"))) }

        tasks.register("printCompileModulePath") {
          val artifacts = configurations.compileClasspath.get().incoming.artifacts.resolvedArtifacts
          doLast {
            val names = artifacts.get().map { (it.id.componentIdentifier as ModuleComponentIdentifier).moduleIdentifier.toString() }.sorted()
            println("resolved compileClasspath: " + names.joinToString(" "))
          }
        }
      """.trimIndent() + "\n"
    )
    val sources = File(fixtureDir, "src/main/java/fixture")
    sources.mkdirs()
    // java-module-dependencies holds the module name to the project's, under an optional prefix
    File(fixtureDir, "src/main/java/module-info.java").writeText("module fixture.gcp.kms.module.path {\n  requires google.cloud.kms;\n}\n")
    File(sources, "Uses.java").writeText(
      "package fixture;\n\npublic final class Uses {\n  public static final String KEY = com.google.cloud.kms.v1.CryptoKeyName.of(\"p\", \"l\", \"r\", \"k\").toString();\n}\n"
    )
  }

  private fun run(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--no-watch-fs")
    .build()

  @Test
  fun `the client and its family carry the excludes, a dependency outside it none, and both writers publish them`() {
    writeFixture()
    val (result, _) = assertConfigurationCacheRoundTrip {
      run("printExcludes", "generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
    }
    assertEquals(TaskOutcome.SUCCESS, result.task(":printExcludes")?.outcome, result.output)
    val lines = result.output.lines()
    val kms = lines.single { it.startsWith("implementation com.google.cloud:google-cloud-kms ") }
    val gax = lines.single { it.startsWith("testImplementation com.google.api:gax ") }
    val testlib = lines.single { it.startsWith("testImplementation com.google.api:gax-grpc ") }
    val xds = lines.single { it.startsWith("testImplementation io.grpc:grpc-xds ") }
    val junit = lines.single { it.startsWith("implementation org.junit.jupiter:junit-jupiter-api ") }
    listOf(kms, gax).forEach { line ->
      assertTrue(line.contains(" excludes ${excluded.size}: "), line)
      excluded.forEach { assertTrue(line.contains(" $it"), "$it missing from: $line") }
    }
    // a classifier dependency (gax-grpc's test library) keeps the in-process channel it uses
    assertTrue(testlib.contains(" excludes ${excluded.size - 1}: "), testlib)
    assertFalse(testlib.contains(" io.grpc:grpc-inprocess"), testlib)
    // the DirectPath opt-in: grpc-xds declared directly loses only the dev.cel jars and threeten-extra
    assertTrue(xds.contains(" excludes 4: dev.cel:common dev.cel:protobuf dev.cel:runtime org.threeten:threeten-extra"), xds)
    assertTrue(junit.contains(" excludes 0: "), junit)

    val pom = File(fixtureDir, "build/publications/maven/pom-default.xml").readText()
    val dependencies = Regex("<dependency>.*?</dependency>", RegexOption.DOT_MATCHES_ALL).findAll(pom).map { it.value }.toList()
    val kmsPom = dependencies.single { it.contains("<artifactId>google-cloud-kms</artifactId>") }
    val junitPom = dependencies.single { it.contains("<artifactId>junit-jupiter-api</artifactId>") }
    assertEquals(excluded.size, Regex("<exclusion>").findAll(kmsPom).count(), kmsPom)
    excluded.forEach { coordinate ->
      val (group, module) = coordinate.split(":")
      assertTrue(
        Regex("<exclusion>\\s*<groupId>${Regex.escape(group)}</groupId>\\s*<artifactId>${Regex.escape(module)}</artifactId>\\s*</exclusion>").containsMatchIn(kmsPom),
        "$coordinate not excluded in the POM:\n$kmsPom",
      )
    }
    assertEquals(0, Regex("<exclusion>").findAll(junitPom).count(), junitPom)

    // Gradle module metadata, which a Gradle consumer reads before the POM: the client's
    // dependency in runtimeElements carries the excludes, the one other runtime dependency none.
    val module = File(fixtureDir, "build/publications/maven/module.json").readText()
    val runtimeElements = module.substring(module.indexOf("\"name\": \"runtimeElements\""))
    val kmsStart = runtimeElements.indexOf("\"module\": \"google-cloud-kms\"")
    val junitStart = runtimeElements.indexOf("\"module\": \"junit-jupiter-api\"")
    assertTrue(kmsStart >= 0 && junitStart >= 0, module)
    val excludesStart = runtimeElements.indexOf("\"excludes\": [", kmsStart)
    assertTrue(excludesStart in (kmsStart + 1) until junitStart || (junitStart < kmsStart && excludesStart > kmsStart), "no excludes on google-cloud-kms:\n$runtimeElements")
    val excludes = runtimeElements.substring(excludesStart, runtimeElements.indexOf("]", excludesStart))
    assertEquals(excluded.size, Regex("\"module\": \"").findAll(excludes).count(), excludes)
    excluded.forEach { coordinate ->
      val (group, name) = coordinate.split(":")
      assertTrue(Regex("\"group\": \"${Regex.escape(group)}\",\\s*\"module\": \"${Regex.escape(name)}\"").containsMatchIn(excludes), "$coordinate not excluded in the module metadata:\n$excludes")
    }
    val junitExcludes = runtimeElements.indexOf("\"excludes\": [", junitStart)
    val junitEnd = runtimeElements.indexOf("\"module\": \"", junitStart + 1).let { if (it < 0) runtimeElements.length else it }
    assertFalse(junitExcludes in junitStart until junitEnd, "junit-jupiter-api carries excludes:\n$runtimeElements")
  }

  @Test
  fun `a test module's requires of the Google auth modules becomes a dependency that carries the excludes`() {
    writeFixture(moduleRequires = true)
    val result = run("printExcludes")
    val lines = result.output.lines()
    val oauth = lines.single { it.startsWith("testImplementation com.google.auth:google-auth-library-oauth2-http ") }
    val credentials = lines.single { it.startsWith("testImplementation com.google.auth:google-auth-library-credentials ") }
    listOf(oauth, credentials).forEach { line ->
      assertTrue(line.contains(" excludes ${excluded.size}: "), line)
      excluded.forEach { assertTrue(line.contains(" $it"), "$it missing from: $line") }
    }
    assertFalse(result.output.contains("missing in"), "java-module-dependencies must not warn of an unmapped name:\n${result.output}")
  }

  @Test
  fun `a named module requiring the client compiles on the module path the convention patches`() {
    writeModulePathFixture()
    val result = run("compileJava", "printCompileModulePath", "--no-configuration-cache")
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava")?.outcome, result.output)
    assertFalse(result.output.contains("missing in"), "java-module-dependencies must map google.cloud.kms through the convention's patch:\n${result.output}")
    val resolved = result.output.lines().single { it.startsWith("resolved compileClasspath: ") }
      .removePrefix("resolved compileClasspath: ").split(" ").toSet()
    listOf("com.google.cloud:google-cloud-kms", "com.google.api:gax", "com.google.api:gax-grpc")
      .forEach { assertTrue(it in resolved, "$it missing from $resolved") }
    // the proto jars the convention merges into the client's and gax-grpc's modules arrive inside them, not as artifacts of their own
    listOf("com.google.api.grpc:proto-google-cloud-kms-v1", "com.google.api.grpc:proto-google-common-protos")
      .forEach { assertFalse(it in resolved, "$it resolved as an artifact of its own despite the merge: $resolved") }
    excluded.forEach { assertFalse(it in resolved, "$it resolved despite the exclusion: $resolved") }
  }

  @Test
  fun `the resolved runtime graph holds the client and none of the excluded jars`() {
    writeFixture()
    // The graph is what this test holds, so the task runs without the configuration cache,
    // which cannot store a resolved-artifacts provider captured by a script task's action.
    val result = run("printResolved", "--no-configuration-cache")
    assertEquals(TaskOutcome.SUCCESS, result.task(":printResolved")?.outcome, result.output)
    val resolved = result.output.lines().single { it.startsWith("resolved runtimeClasspath: ") }
      .removePrefix("resolved runtimeClasspath: ").split(" ").toSet()
    listOf("com.google.cloud:google-cloud-kms", "com.google.api:gax-grpc", "io.grpc:grpc-netty-shaded", "org.threeten:threetenbp")
      .forEach { assertTrue(it in resolved, "$it missing from $resolved") }
    excluded.forEach { assertFalse(it in resolved, "$it resolved despite the exclusion: $resolved") }
    // The whole set, as the fixture's pinned versions resolve it: a jar arriving or leaving is a change to review.
    val expected = setOf(
      "com.google.api.grpc:proto-google-iam-v1",
      "com.google.api:api-common",
      "com.google.api:gax",
      "com.google.api:gax-grpc",
      "com.google.api:gax-httpjson",
      "com.google.auth:google-auth-library-credentials",
      "com.google.auth:google-auth-library-oauth2-http",
      "com.google.auto.value:auto-value-annotations",
      "com.google.cloud:google-cloud-kms",
      "com.google.code.gson:gson",
      "com.google.errorprone:error_prone_annotations",
      "com.google.guava:failureaccess",
      "com.google.guava:guava",
      "com.google.guava:listenablefuture",
      "com.google.http-client:google-http-client",
      "com.google.http-client:google-http-client-gson",
      "com.google.j2objc:j2objc-annotations",
      "com.google.protobuf:protobuf-java",
      "com.google.protobuf:protobuf-java-util",
      "io.grpc:grpc-api",
      "io.grpc:grpc-auth",
      "io.grpc:grpc-context",
      "io.grpc:grpc-core",
      "io.grpc:grpc-netty-shaded",
      "io.grpc:grpc-protobuf",
      "io.grpc:grpc-protobuf-lite",
      "io.grpc:grpc-stub",
      "io.grpc:grpc-util",
      "io.opencensus:opencensus-api",
      "io.opencensus:opencensus-contrib-http-util",
      "io.perfmark:perfmark-api",
      "org.codehaus.mojo:animal-sniffer-annotations",
      "org.jspecify:jspecify",
      "org.junit.jupiter:junit-jupiter-api",
      "org.junit.platform:junit-platform-commons",
      "org.opentest4j:opentest4j",
      "org.threeten:threetenbp",
    )
    assertEquals(expected, resolved, "arrived: ${resolved - expected}; left: ${expected - resolved}")
  }
}
