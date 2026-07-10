plugins {
  `kotlin-dsl`
  id("maven-publish")
  id("signing")
  id("com.gradleup.nmcp")
  id("com.gradleup.nmcp.aggregation")
}

group = "software.sava"
version = providers.gradleProperty("version").getOrElse("")

// ./gradlew --write-verification-metadata pgp,sha256 check generatePrecompiledScriptPluginAccessors
dependencies {
  // https://github.com/autonomousapps/dependency-analysis-gradle-plugin
  // https://plugins.gradle.org/plugin/com.autonomousapps.dependency-analysis
  // https://mvnrepository.com/artifact/com.autonomousapps.dependency-analysis/com.autonomousapps.dependency-analysis.gradle.plugin
  implementation("com.autonomousapps:dependency-analysis-gradle-plugin:3.16.1")

  // https://github.com/iherasymenko/jlink-gradle-plugin
  // https://plugins.gradle.org/plugin/com.github.iherasymenko.jlink
  implementation("com.github.iherasymenko.jlink:jlink-plugin:0.9")
  // https://docs.gradle.com/develocity/gradle-plugin/current/
  implementation("com.gradle:develocity-gradle-plugin:4.5.0")
  // https://github.com/GradleUp/nmcp
  val nmcpVersion = providers.gradleProperty("nmcpVersion").orNull
    ?: error("Missing required Gradle property 'nmcpVersion'")
  implementation("com.gradleup.nmcp:nmcp:$nmcpVersion")
  // https://github.com/gradle/foojay-toolchains
  // https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention
  implementation("org.gradle.toolchains:foojay-resolver:1.0.0")
  // https://github.com/gradlex-org/java-module-dependencies
  implementation("org.gradlex:java-module-dependencies:1.13.1")
  // https://github.com/gradlex-org/java-module-testing
  implementation("org.gradlex:java-module-testing:1.8.1")
  // https://github.com/gradlex-org/jvm-dependency-conflict-resolution
  implementation("org.gradlex:jvm-dependency-conflict-resolution:2.5")
  // https://github.com/gradlex-org/extra-java-module-info
  implementation("org.gradlex:extra-java-module-info:1.14.2")

  testImplementation(gradleTestKit())
  // https://github.com/junit-team/junit-framework
  testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  // The smoke tests run TestKit builds of fixture projects that consume this
  // checkout via 'pluginManagement { includeBuild(...) }'.
  systemProperty("savaBuild.root", layout.projectDirectory.asFile.absolutePath)
  // TestKit builds the convention plugins outside this build's task graph, so
  // declare their sources as inputs to re-run tests when they change.
  inputs.dir("src/main/kotlin")
}

repositories {
  gradlePluginPortal()
}

gradlePlugin {
  plugins {
    // Named after the plugin id so the marker publication/task names match the
    // 'publishSoftware.sava.build.*' pattern of the precompiled script plugins.
    register("software.sava.build.feature-jdk-provisioning") {
      id = name
      displayName = "Deprecated alias for software.sava.build.feature.jdk-provisioning"
      implementationClass = "SavaJdkProvisioningAliasPlugin"
    }
  }
}

java {
  withJavadocJar()
  withSourcesJar()
}

// Keep in sync with the signing setup in
// src/main/kotlin/software.sava.build.feature.publish.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself).
val publishSigningEnabled = providers.gradleProperty("sign").getOrElse("false").toBoolean()
val signingKey = providers.environmentVariable("GPG_PUBLISH_SECRET").orNull
val signingPassphrase = providers.environmentVariable("GPG_PUBLISH_PHRASE").orNull
signing {
  sign(publishing.publications)
  useInMemoryPgpKeys(signingKey, signingPassphrase)
}
tasks.withType<Sign>().configureEach { enabled = publishSigningEnabled }

// Only publish markers for plugins that consumers request by id in a settings 'plugins {}'
// block: 'software.sava.build' (its marker task has no trailing '.', so it never matches the
// filter below), 'software.sava.build.feature.jdk-provisioning', and its deprecated alias.
// All other convention plugins are applied through those, or are resolved from the settings
// classpath, and need no marker.
val publishedMarkerTaskPrefixes = setOf(
  "publishSoftware.sava.build.feature.jdk-provisioningPluginMarker",
  "publishSoftware.sava.build.feature-jdk-provisioningPluginMarker"
)
tasks.named { name ->
  name.startsWith("publishSoftware.sava.build.") && publishedMarkerTaskPrefixes.none(name::startsWith)
}.configureEach {
  enabled = false
}
tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    "publishPluginMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.buildPluginMarkerMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.build.feature.jdk-provisioningPluginMarkerMavenPublicationToSavaGithubPackagesRepository",
    "publishSoftware.sava.build.feature-jdk-provisioningPluginMarkerMavenPublicationToSavaGithubPackagesRepository"
  )
}

// Keep in sync with src/main/kotlin/software.sava.build.feature.publish-maven-central.gradle.kts
// (this build produces the convention plugins, so it cannot apply them to itself).
nmcpAggregation {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
    password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
    publishingType = "USER_MANAGED"
  }
}

dependencies {
  nmcpAggregation(project(path))
}

// Allow callers to drop selected checksum files (e.g. md5, sha1, sha256, sha512) from the
// Maven Central deployment bundle via '-PmavenCentralExcludeChecksums=md5,sha1'.
val mavenCentralExcludeChecksums = providers.gradleProperty("mavenCentralExcludeChecksums")
  .map { value -> value.split(",").map(String::trim).filter(String::isNotEmpty) }
  .getOrElse(emptyList())

if (mavenCentralExcludeChecksums.isNotEmpty()) {
  tasks.named<Zip>("nmcpZipAggregation") {
    mavenCentralExcludeChecksums.forEach { extension ->
      exclude("**/*.$extension")
    }
  }
}

publishing {
  repositories {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      // https://docs.gradle.org/current/samples/sample_publishing_credentials.html
      credentials(PasswordCredentials::class)
    }
  }
}

val vcs = "https://github.com/sava-software/sava-build"
publishing.publications.withType<MavenPublication>().configureEach {
  pom {
    name = project.name
    description = "Sava Gradle Conventions"
    url = vcs
    licenses {
      license {
        name = "Apache License"
        url = "$vcs/blob/main/LICENSE"
      }
    }
    developers {
      developer {
        name = "Jim"
        id = "jpe7s"
        email = "jpe7s.salt188@passfwd.com"
        organization = "Sava Software"
        organizationUrl = "https://github.com/sava-software"
      }
    }
    scm {
      connection = "scm:git:git@github.com:sava-software/sava-build.git"
      developerConnection = "scm:git:ssh@github.com:sava-software/sava-build.git"
      url = vcs
    }
  }
}
