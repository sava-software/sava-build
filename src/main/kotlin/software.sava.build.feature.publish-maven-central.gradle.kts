import gradle.kotlin.dsl.accessors._50f2277622f984d2033e247386aa4fb0.nmcpAggregation

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
