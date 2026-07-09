// Opt-in module patch for projects that ship the PostgreSQL JDBC driver.
// Converts the automatic module to an explicit one so it can be jlinked.
plugins {
  id("org.gradlex.extra-java-module-info")
}

extraJavaModuleInfo {
  module("org.postgresql:postgresql", "org.postgresql.jdbc") {
    requires("java.logging")
    requires("java.management")
    requires("java.naming")
    requires("java.sql")
    // requires("java.transaction.xa")

    // provides("java.sql.Driver", "org.postgresql.Driver")

    exports("org.postgresql.ds")
    exports("org.postgresql.ds.common")
  }
}
