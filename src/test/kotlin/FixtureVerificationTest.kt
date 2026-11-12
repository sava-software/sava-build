import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import software.sava.build.verification.KeyStatus
import software.sava.build.verification.OpenPgpKeys
import software.sava.build.verification.VerificationPolicyRules
import java.io.File

/**
 * The fixture policy (src/test/resources/fixture-policy) as FixtureVerification installs it: what
 * a fixture receives keeps the rules the guard holds a consumer's policy to, an install is
 * repeatable without a change, and the committed files keep the reviews this repository's own
 * policy keeps: the keyring and the rules move together, a listed key stays at exact versions,
 * and a key noted as corroborated stays trusted without one. Nothing here runs a build;
 * FixtureVerificationFunctionalTest does.
 */
class FixtureVerificationTest {

  @TempDir
  lateinit var dir: File

  /** A committed policy file written out, for the readers that take a file. */
  private fun committed(name: String): File = File(dir, "committed-$name").apply { writeText(FixtureVerification.resource(name)) }

  @Test
  fun `an installed policy keeps the guard's rules`() {
    File(dir, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")
    FixtureVerification.install(dir)
    assertEquals(emptyList<String>(), VerificationPolicyRules.problems(dir, emptyList()))
    assertTrue(FixtureVerification.installedIn(dir))
    assertEquals(
      listOf("org.gradle.configuration-cache=true", FixtureVerification.DECLARATION),
      File(dir, "gradle.properties").readLines(),
    )
  }

  @Test
  fun `a second install changes no file`() {
    FixtureVerification.install(dir)
    fun state() = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to (it.lastModified() to it.readText()) }
    val installed = state()
    assertEquals(
      setOf("gradle.properties", "gradle/verification-metadata.xml", "gradle/verification-keyring.keys", "gradle/verification/version-scoped-keys.txt"),
      installed.keys,
    )
    FixtureVerification.install(dir)
    assertEquals(installed, state())
  }

  @Test
  fun `a fixture without the policy, or with an edited one, is not installed`() {
    assertFalse(FixtureVerification.installedIn(dir))
    FixtureVerification.install(dir)
    File(dir, "gradle/verification-metadata.xml").appendText("<!-- edited -->\n")
    assertFalse(FixtureVerification.installedIn(dir))
    val refused = assertThrows(IllegalStateException::class.java) { savaBuildRunner(dir, "help") }
    assertTrue(refused.message!!.contains("enableTestKitConfigurationCache"), refused.message)
    // each installed file counts, the list the guard reads included
    for (edited in listOf("gradle/verification-keyring.keys", "gradle/verification/version-scoped-keys.txt")) {
      File(dir, "gradle/verification-metadata.xml").delete()
      FixtureVerification.install(dir)
      assertTrue(FixtureVerification.installedIn(dir))
      File(dir, edited).appendText("\n")
      assertFalse(FixtureVerification.installedIn(dir), "$edited was edited")
      File(dir, edited).delete()
    }
  }

  @Test
  fun `a gradle properties file that relaxes the mode after the declaration is not installed`() {
    // Gradle reads the last value of a key, and reads the mode under the systemProp spelling too.
    val properties = File(dir, "gradle.properties")
    for (relaxing in listOf(
      "org.gradle.dependency.verification=off",
      "org.gradle.dependency.verification = lenient",
      "systemProp.org.gradle.dependency.verification=off",
      "systemProp.org.gradle.dependency.verification=strict",
    )) {
      properties.delete()
      FixtureVerification.install(dir)
      assertTrue(FixtureVerification.installedIn(dir))
      properties.appendText("$relaxing\n")
      assertFalse(FixtureVerification.installedIn(dir), relaxing)
      assertThrows(IllegalStateException::class.java) { savaBuildRunner(dir, "help") }
    }
    // other properties after the declaration change nothing
    properties.delete()
    FixtureVerification.install(dir)
    properties.appendText("org.gradle.caching=true\nsavaBuildLocalRepo=never-published\n")
    assertTrue(FixtureVerification.installedIn(dir))
  }

  @Test
  fun `a runner takes only the options the helpers list`() {
    FixtureVerification.install(dir)
    // Another mode, a run that writes a policy, another build, an init script, another Gradle
    // user home, in each spelling Gradle takes (a long option with one dash too), and an option
    // nobody listed.
    val refusedOptions = listOf(
      listOf("--dependency-verification", "off"), listOf("--dependency-verification=lenient"), listOf("-dependency-verification", "off"),
      listOf("-F", "lenient"), listOf("-Foff"),
      listOf("--write-verification-metadata", "sha256"), listOf("-write-verification-metadata", "sha256"), listOf("-M", "pgp,sha256"),
      listOf("-Msha256"), listOf("--export-keys"), listOf("-export-keys"),
      listOf("-Dorg.gradle.dependency.verification=off"), listOf("-Dgradle.user.home=elsewhere"),
      listOf("--project-dir", "elsewhere"), listOf("-p", "elsewhere"), listOf("--init-script", "init.gradle"), listOf("-init-script", "init.gradle"),
      listOf("-I", "init.gradle"), listOf("--gradle-user-home", "elsewhere"), listOf("--gradle-user-home=elsewhere"), listOf("-g", "elsewhere"),
      listOf("--project-cache-dir", "elsewhere"), listOf("--some-option-nobody-listed"),
    )
    for (options in refusedOptions) {
      for (make in listOf<(Array<String>) -> Any>({ savaBuildRunner(dir, *it) }, { savaBuildRunnerWithOwnPolicy(dir, *it) })) {
        val refused = assertThrows(IllegalStateException::class.java, { make(arrayOf("help", *options.toTypedArray())) }, options.toString())
        assertTrue(refused.message!!.contains(options.first()), refused.message)
      }
    }
    // what the fixtures do pass is taken, and the mode is the runner's
    File(dir, "settings.gradle.kts").writeText("rootProject.name = \"options\"\n")
    val runner = savaBuildRunner(
      dir, "help", "-PmaxFuzzTime=1", "-Dorg.gradle.java.installations.auto-detect=false", "-x", "test", "-q", "--stacktrace",
      "--warning-mode=fail", "--configuration-cache", "tasks", "--group=verification",
    )
    val modes = setOf("strict", "lenient", "off")
    assertEquals(listOf("--dependency-verification", "strict"), runner.arguments.filter { it == "--dependency-verification" || it in modes })
    assertTrue("--no-watch-fs" in runner.arguments, runner.arguments.toString())
  }

  @Test
  fun `a fixture is checked again when it runs`() {
    FixtureVerification.install(dir)
    File(dir, "settings.gradle.kts").writeText("rootProject.name = \"checked-again\"\n")
    val runner = savaBuildRunner(dir, "help")
    File(dir, "gradle/verification-metadata.xml").appendText("<!-- edited after the runner was made -->\n")
    assertThrows(IllegalStateException::class.java) { runner.build() }
    assertThrows(IllegalStateException::class.java) { runner.buildAndFail() }
    assertThrows(IllegalStateException::class.java) { runner.withEnvironment(emptyMap()).build() }
  }

  @Test
  fun `a fixture is the root of its own build, with a settings script, when its runner is made and when it runs`() {
    // Without one Gradle looks for the root, and the policy it reads, in the directories above.
    FixtureVerification.install(dir)
    val unrooted = assertThrows(IllegalStateException::class.java) { savaBuildRunner(dir, "help") }
    assertTrue(unrooted.message!!.contains("has no settings script"), unrooted.message)
    for (script in listOf("settings.gradle.kts", "settings.gradle")) {
      val settings = File(dir, script).apply { writeText("") }
      val runner = savaBuildRunner(dir, "help")
      assertTrue(settings.delete())
      val removed = assertThrows(IllegalStateException::class.java) { runner.build() }
      assertTrue(removed.message!!.contains("has no settings script"), removed.message)
    }
  }

  @Test
  fun `a fixture outside the fixture policy reports refusals and never downloads, and the build that fills its Gradle user home is strict`() {
    assertEquals(listOf("--dependency-verification", "lenient", "--offline"), OWN_POLICY_ARGUMENTS)
    val primer = ownPolicyPrimer(dir).arguments
    val modes = setOf("strict", "lenient", "off")
    assertEquals(listOf("--dependency-verification", "strict"), primer.filter { it == "--dependency-verification" || it in modes })
    assertFalse("--offline" in primer, primer.toString())
    assertTrue(FixtureVerification.installedIn(dir), "the primer's own fixture holds the fixture policy")
  }

  @Test
  fun `a run outside the fixture policy carries no read-only dependency cache, whatever environment it is given`() {
    val given = mapOf(READ_ONLY_CACHE_VARIABLE to "/a/cache/no/strict/build/filled", "KEPT" to "as given")
    val ownPolicy = ownPolicyRunner(dir, arrayOf("help"))
    assertFalse(READ_ONLY_CACHE_VARIABLE in ownPolicy.environment!!, ownPolicy.environment.toString())
    assertEquals(mapOf("KEPT" to "as given"), ownPolicy.withEnvironment(given).environment)
    // what a test reads is a copy: writing to it changes nothing the run will carry
    @Suppress("UNCHECKED_CAST")
    runCatching { (ownPolicy.environment as MutableMap<String, String>)[READ_ONLY_CACHE_VARIABLE] = "/smuggled" }
    assertFalse(READ_ONLY_CACHE_VARIABLE in ownPolicy.environment!!, ownPolicy.environment.toString())
    // the strict build that fills the home is in the same daemons, so it carries none either
    val primer = ownPolicyPrimer(File(dir, "primer").apply { mkdirs() })
    assertFalse(READ_ONLY_CACHE_VARIABLE in primer.environment!!, primer.environment.toString())
    assertEquals(mapOf("KEPT" to "as given"), primer.withEnvironment(given).environment)
    // the strict runner verifies whatever it resolves, from any cache, and keeps the environment as given
    File(dir, "settings.gradle.kts").writeText("")
    FixtureVerification.install(dir)
    assertEquals(given, savaBuildRunner(dir, "help").withEnvironment(given).environment)
  }

  @Test
  fun `the run's key is trusted for the local groups and for nothing in the committed policy`() {
    FixtureVerification.install(dir)
    val installed = KeyStatus.trustedKeys(File(dir, "gradle/verification-metadata.xml")).associateBy { it.id }
    val run = installed.getValue(FixtureVerification.fingerprint)
    assertEquals(FixtureVerification.LOCAL_GROUPS.map { "group=\"$it\"" }, run.scopes)
    val reviewed = FixtureVerification.resource("verification-metadata.xml")
    assertFalse(FixtureVerification.fingerprint in reviewed)
    // No reviewed rule reaches a local group either: only the run's key vouches for local artifacts.
    FixtureVerification.LOCAL_GROUPS.forEach { group ->
      assertFalse("group=\"$group\"" in reviewed, "the committed fixture policy trusts a key for the local group $group")
    }
    assertFalse("0.0.0-test" in reviewed, "the committed fixture policy names the test publication")
  }

  @Test
  fun `the keyring holds exactly the keys the committed policy names, each block read by the policy's reader`() {
    // As KeyStatusTest holds this repository's own keyring and rules together.
    val keyring = FixtureVerification.resource("verification-keyring.keys")
    val keys = OpenPgpKeys.parse(keyring)
    assertEquals(Regex("-----BEGIN PGP PUBLIC KEY BLOCK-----").findAll(keyring).count(), keys.size, "every armored block must be read as one key")
    val labels = Regex("(?m)^pub\\s+([0-9A-F]{16})\\s*$").findAll(keyring).map { it.groupValues[1] }.toList()
    assertEquals(labels, keys.map { it.fingerprint.takeLast(16) }, "each block's pub label must be its primary key's, in file order")
    val named = KeyStatus.trustedKeys(committed("verification-metadata.xml")).map { it.id }.toSet()
    val orphans = keys.filter { key -> key.fingerprints.none { it in named } }.map { "pub ${it.fingerprint.takeLast(16)} ${it.userIds}" }
    assertEquals(emptyList<String>(), orphans, "keyring blocks no trusted-key rule names")
    val missing = named - keys.flatMap { it.fingerprints }.toSet()
    assertEquals(emptySet<String>(), missing, "trusted-key rules whose key no keyring block holds, as primary or subkey")
  }

  @Test
  fun `listed keys stay at exact versions and noted keys stay trusted without one`() {
    val keys = KeyStatus.trustedKeys(committed("verification-metadata.xml"))
    assertTrue(keys.all { it.id.length == 40 && it.id == it.id.uppercase() })
    assertEquals(keys.map { it.id }.sorted().distinct(), keys.map { it.id })
    val list = committed("version-scoped-keys.txt")
    val listed = list.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore(' ').uppercase() }
    assertEquals(listed.distinct(), listed)
    val byId = keys.associateBy { it.id }
    assertEquals(emptyList<String>(), listed.filter { byId[it]?.versionFree != false }, "listed keys that no rule uses, or that a rule trusts beyond exact versions")
    assertEquals(listed.toSet(), keys.filterNot { it.versionFree }.map { it.id }.toSet(), "the keys at exact versions are the listed ones")
    val noted = KeyStatus.corroborationKeys(list)
    assertTrue(noted.isNotEmpty(), "the list's by-decision note names the corroboration-only keys")
    assertEquals(emptySet<String>(), noted - byId.keys, "noted keys no rule names")
    assertTrue(noted.none { it in listed }, "a listed key is at exact versions, not corroborated")
    assertTrue(noted.all { byId.getValue(it).versionFree }, "every noted key is trusted without a version")
  }

  @Test
  fun `a signature covers the bytes signed and is replaced when they change`() {
    val repository = File(dir, "repo")
    val version = File(repository, "org/example/lib/1.0").apply { mkdirs() }
    val jar = File(version, "lib-1.0.jar").apply { writeText("one") }
    File(version, "lib-1.0.pom").writeText("<project/>")
    File(version, "lib-1.0.jar.sha256").writeText("checksum")
    File(version, "notes.txt").writeText("not an artifact of this module")
    FixtureVerification.signRepository(repository)
    assertEquals(
      listOf("lib-1.0.jar", "lib-1.0.jar.asc", "lib-1.0.jar.sha256", "lib-1.0.pom", "lib-1.0.pom.asc", "notes.txt"),
      version.list()!!.sorted(),
    )
    val first = File(version, "lib-1.0.jar.asc").readText()
    assertTrue(first.startsWith("-----BEGIN PGP SIGNATURE-----"), first)
    jar.writeText("two")
    FixtureVerification.sign(jar)
    assertFalse(first == File(version, "lib-1.0.jar.asc").readText())
  }
}
