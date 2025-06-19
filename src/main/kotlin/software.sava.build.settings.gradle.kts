plugins {
  id("software.sava.build.report.develocity")
  id("software.sava.build.base.repositories")
  id("org.gradlex.java-module-dependencies")
}

includeBuild(".")

include(":aggregation")
project(":aggregation").projectDir = file("gradle/aggregation")
