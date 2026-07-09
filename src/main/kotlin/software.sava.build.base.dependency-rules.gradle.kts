plugins {
  id("org.gradlex.extra-java-module-info")
  id("org.gradlex.jvm-dependency-conflict-resolution")
}

jvmDependencyConflicts {
  consistentResolution {
    platform("software.sava:solana-version-catalog:${solanaBOMVersion()}")
  }
}

// Dependency-specific module patches are opt-in per project:
//   id("software.sava.build.modules.postgresql")
//   id("software.sava.build.modules.gcp-kms")
