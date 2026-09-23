import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.publish.GitHubPackagesUploadTask.Companion.publishes

/** What of a staged Maven repository reaches GitHub Packages, and what never does. */
class GitHubPackagesUploadSelectionTest {

  private val defaults = setOf("sha1", "sha256")

  @Test
  fun `artifacts, metadata files and signatures are uploaded`() {
    for (name in listOf("lib-1.2.3.jar", "lib-1.2.3-sources.jar", "lib-1.2.3.pom", "lib-1.2.3.module",
                        "lib-1.2.3.toml", "lib-1.2.3.jar.asc", "lib-1.2.3.pom.asc")) {
      assertTrue(publishes(name, defaults), name)
    }
  }

  @Test
  fun `only the configured checksums are uploaded, never MD5`() {
    assertTrue(publishes("lib-1.2.3.jar.sha1", defaults), "Maven Resolver reads SHA-1 by default")
    assertTrue(publishes("lib-1.2.3.jar.sha256", defaults))
    assertFalse(publishes("lib-1.2.3.jar.sha512", defaults), "not by default")
    assertFalse(publishes("lib-1.2.3.jar.md5", defaults))
    assertTrue(publishes("lib-1.2.3.jar.sha512", setOf("sha1", "sha256", "sha512")), "opted in")
    assertFalse(publishes("lib-1.2.3.jar.sha1", setOf("sha256")), "opted out")
    assertFalse(publishes("lib-1.2.3.jar.sha256", setOf("sha512")))
    // listing MD5 does not bring it back
    assertFalse(publishes("lib-1.2.3.jar.md5", setOf("md5", "sha1", "sha256", "sha512")))
  }

  @Test
  fun `a checksum of a signature is never uploaded`() {
    for (name in listOf("lib-1.2.3.jar.asc.sha1", "lib-1.2.3.jar.asc.sha256", "lib-1.2.3.pom.asc.sha512",
                        "lib-1.2.3.jar.asc.md5")) {
      assertFalse(publishes(name, defaults), name)
    }
  }

  @Test
  fun `maven-metadata is left to the registry`() {
    for (name in listOf("maven-metadata.xml", "maven-metadata.xml.sha1", "maven-metadata.xml.sha256",
                        "maven-metadata.xml.sha512")) {
      assertFalse(publishes(name, defaults), name)
    }
  }
}
