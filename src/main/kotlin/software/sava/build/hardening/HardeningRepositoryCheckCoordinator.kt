package software.sava.build.hardening

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes repository-scoped checks once when the hardening plugin is applied by
 * several projects in one Gradle build. Each project still exposes the discoverable
 * task, but duplicate task selections must not print the same root-AGENTS finding
 * once per project or miscount it as several suite findings.
 */
abstract class HardeningRepositoryCheckCoordinator :
  BuildService<BuildServiceParameters.None>, AutoCloseable {

  private val claims = ConcurrentHashMap.newKeySet<String>()

  fun claim(operation: String, repositoryFile: String, templateDigest: String, mode: String): Boolean =
    claims.add(listOf(operation, repositoryFile, templateDigest, mode).joinToString("\u0000"))

  override fun close() = Unit
}
