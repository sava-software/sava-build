plugins {
  id("java")
}

tasks.withType<Javadoc>().configureEach {
  val standardOption = options as StandardJavadocDocletOptions
  standardOption.addStringOption("Xdoclint:none", "-quiet")
  standardOption.addBooleanOption("html5", true)
}

// The javadoc tool of newer JDKs bundles the DejaVu web fonts (~4 MB, dwarfing the
// documentation itself) into every output. Drop them from the published javadoc jar;
// browsers fall back to system fonts. Keeps releases well within Maven Central's
// size limits.
tasks.withType<Jar>().configureEach {
  if (name == "javadocJar") {
    exclude("resource-files/fonts/**")
    // Pre-JDK-25 layout, should the toolchain ever be downgraded.
    exclude("fonts/**")
  }
}
