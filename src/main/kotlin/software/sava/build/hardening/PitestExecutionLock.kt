package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build-wide semaphore for PIT executions. The service carries no state; consumers
 * register it once on the shared Gradle instance with `maxParallelUsages = 1` and
 * every PIT JavaExec declares `usesService`.
 *
 * `mustRunAfter` edges only serialize tasks inside one project. A shared service also
 * covers suites selected from several subprojects under `--parallel`, keeping worker
 * pools from changing each other's timeout behavior.
 */
abstract class PitestExecutionLock : BuildService<BuildServiceParameters.None>
