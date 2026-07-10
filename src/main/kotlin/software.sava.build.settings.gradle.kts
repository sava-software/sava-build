plugins {
  id("software.sava.build.base.repositories")
  id("org.gradlex.java-module-dependencies")
}

includeBuild(".")

// Publishing repositories opt in to the aggregation project (used for Maven Central
// bundling via 'software.sava.build.feature.publish-maven-central') by creating
// gradle/aggregation/build.gradle.kts.
if (file("gradle/aggregation/build.gradle.kts").exists()) {
  include(":aggregation")
  project(":aggregation").projectDir = file("gradle/aggregation")
}
