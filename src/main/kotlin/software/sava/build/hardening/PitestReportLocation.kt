package software.sava.build.hardening

/** One XML report observation; duplicate instances preserve mutation multiplicity. */
internal data class PitestReportLocation(
  val key: String,
  val status: String,
  val description: String,
)
