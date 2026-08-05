package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build-wide semaphore for CPU-intensive hardening processes. The service carries no
 * state; each registration chooses its own `maxParallelUsages`, and typed JavaExec
 * tasks declare the applicable service with `usesService`.
 *
 * `mustRunAfter` edges only serialize tasks inside one project. A shared service also
 * covers tasks selected from several subprojects under `--parallel`. PIT and corpus
 * rewrites use an exclusive registration. Fuzz exploration uses a separate bounded
 * registration so concurrency is explicit and receipt-bound instead of an accidental
 * consequence of Gradle's task scheduler.
 */
abstract class HardeningExecutionLock : BuildService<BuildServiceParameters.None>
