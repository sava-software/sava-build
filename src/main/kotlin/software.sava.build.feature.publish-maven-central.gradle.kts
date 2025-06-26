import gradle.kotlin.dsl.accessors._652051af0f5582a25d9af066ad361fe7.nmcpAggregation

plugins {
  id("maven-publish")
  id("com.gradleup.nmcp.aggregation")
}

nmcpAggregation {
  centralPortal {
    username = providers.environmentVariable("MAVEN_CENTRAL_TOKEN")
    password = providers.environmentVariable("MAVEN_CENTRAL_SECRET")
    publishingType = "USER_MANAGED"
  }
}
