// Keep in sync with the nmcpAggregation and checksum-exclusion setup in the root
// build.gradle.kts, which duplicates it because it cannot apply the convention
// plugins it produces.
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
