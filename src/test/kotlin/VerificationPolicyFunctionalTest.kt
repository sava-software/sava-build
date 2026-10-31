import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The dependency verification policy guard: the settings plugin registers verificationPolicy
 * on the root and makes every check depend on it. This repository's own policy, Gradle's own
 * regeneration output, passes it; every rule has a case that fails it, each written as a
 * regeneration or a hand edit could write it; a repository with no policy and nothing of one is
 * left to the CI notice, and one with a keyring, a list or the declaration but no policy file
 * is a deleted policy. Every run passes --dependency-verification lenient, since the fixture
 * resolves the unsigned test publication of this plugin, which the fixture's own policy refuses.
 */
class VerificationPolicyFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private val metadata get() = File(fixtureDir, "gradle/verification-metadata.xml")
  private val keyring get() = File(fixtureDir, "gradle/verification-keyring.keys")
  private val versionScopedKeys get() = File(fixtureDir, "gradle/verification/version-scoped-keys.txt")

  private fun writeFixture(includedBuilds: List<String> = emptyList()) {
    enableTestKitConfigurationCache(fixtureDir)
    // the policy's declaration, which the adoption recipe commits beside the three files
    File(fixtureDir, "gradle.properties").appendText("org.gradle.dependency.verification=strict\n")
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "verification-policy"
        ${includedBuilds.joinToString("\n        ") { "includeBuild(\"$it\")" }}
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText("plugins { java }\n")
    // This repository's own policy: Gradle's regeneration output, so the passing case is real.
    metadata.parentFile.mkdirs()
    savaBuildRootFile("gradle/verification-metadata.xml").copyTo(metadata, overwrite = true)
    savaBuildRootFile("gradle/verification-keyring.keys").copyTo(keyring, overwrite = true)
    versionScopedKeys.parentFile.mkdirs()
    savaBuildRootFile("gradle/verification/version-scoped-keys.txt").copyTo(versionScopedKeys, overwrite = true)
    includedBuilds.forEach { writeStandaloneBuild(it, symlinks = false) }
  }

  /** A directory with a settings script of its own, holding the root policy as symlinks or copies. */
  private fun writeStandaloneBuild(name: String, symlinks: Boolean) {
    val build = File(fixtureDir, name)
    File(build, "gradle").mkdirs()
    File(build, "settings.gradle.kts").writeText("rootProject.name = \"$name\"\n")
    listOf(metadata, keyring).forEach { shared ->
      val copy = File(build, "gradle/${shared.name}").toPath()
      if (symlinks) {
        Files.createSymbolicLink(copy, Path.of("..", "..", "gradle", shared.name))
      } else {
        shared.copyTo(copy.toFile())
      }
    }
  }

  private fun mutate(from: String, to: String) {
    val text = metadata.readText()
    check(text.contains(from)) { "the policy no longer contains: $from" }
    metadata.writeText(text.replace(from, to))
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "--dependency-verification", "lenient", "--no-watch-fs")

  private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

  private fun fails(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

  private fun assertProblem(result: BuildResult, fragment: String) {
    assertTrue(result.output.contains("Dependency verification policy problems"), result.output)
    assertTrue(result.output.contains(fragment), "expected the problem '$fragment' in:\n${result.output}")
  }

  @Test
  fun `this repository's policy passes, from check and from a reused configuration cache`() {
    writeFixture()
    writeStandaloneBuild("jmh", symlinks = true)
    val check = run("check")
    assertEquals(TaskOutcome.SUCCESS, check.task(":verificationPolicy")?.outcome, check.output)
    run("verificationPolicy")
    val reused = run("verificationPolicy")
    assertTrue(reused.output.contains("Configuration cache entry reused."), reused.output)
    assertEquals(TaskOutcome.SUCCESS, reused.task(":verificationPolicy")?.outcome, reused.output)
  }

  @Test
  fun `the jlink image task depends on the guard too, so a Dockerfile's image-only run checks the policy`() {
    writeFixture()
    File(fixtureDir, "build.gradle.kts").appendText("tasks.register(\"image\")\n")
    val plan = run("image", "--dry-run")
    val guard = plan.output.indexOf(":verificationPolicy SKIPPED")
    val image = plan.output.indexOf(":image SKIPPED")
    assertTrue(guard >= 0 && image > guard, plan.output)
    // and a task of any other name is left alone
    File(fixtureDir, "build.gradle.kts").appendText("tasks.register(\"imageless\")\n")
    assertFalse(run("imageless", "--dry-run").output.contains(":verificationPolicy"), "imageless must not depend on the guard")
  }

  @Test
  fun `a standalone build's own guard follows its policy link into its parent's build and refuses any other link`() {
    writeFixture()
    // a standalone build that applies the settings plugin itself: its policy and keyring are
    // relative symlinks to the parent's, and it has no version-scoped list of its own
    val child = File(fixtureDir, "child")
    writePluginBuild(child, linkDepth = 2)
    val inChild = runnerIn(child)
    assertEquals(TaskOutcome.SUCCESS, inChild.build().task(":verificationPolicy")?.outcome)
    // the parent's problems are reported as the parent's: its gradle.properties holds the declaration
    enableTestKitConfigurationCache(fixtureDir)
    assertProblem(inChild.buildAndFail(), "in the parent build at ../: gradle.properties does not set org.gradle.dependency.verification=strict")
    File(fixtureDir, "gradle.properties").appendText("org.gradle.dependency.verification=strict\n")
    // a link to anything but the parent's policy is a policy in disguise
    val link = File(child, "gradle/verification-metadata.xml").toPath()
    val other = File(fixtureDir, "other")
    other.mkdirs()
    metadata.copyTo(File(other, metadata.name))
    Files.delete(link)
    Files.createSymbolicLink(link, Path.of("..", "..", "other", metadata.name))
    assertProblem(inChild.buildAndFail(), "; a standalone build links to its parent directory's gradle/verification-metadata.xml")
    // a dangling link leaves Gradle verifying nothing
    Files.delete(link)
    Files.createSymbolicLink(link, Path.of("..", "..", "gone", metadata.name))
    assertProblem(inChild.buildAndFail(), "gradle/verification-metadata.xml is a symlink to a missing target")
    // a linker the parent's guard never discovers (a clone of its own) is not checked back
    Files.delete(link)
    Files.createSymbolicLink(link, Path.of("..", "..", "gradle", metadata.name))
    File(child, ".git").mkdirs()
    assertProblem(inChild.buildAndFail(), "that build's guard never checks this one back")
    // nor is a nested build, which cannot link past its parent directory
    val nested = File(fixtureDir, "nest/deep")
    writePluginBuild(nested, linkDepth = 3)
    assertProblem(runnerIn(nested).buildAndFail(), "; a standalone build links to its parent directory's gradle/verification-metadata.xml")
  }

  /** A build applying the settings plugin, whose policy files are relative symlinks [linkDepth] directories up. */
  private fun writePluginBuild(dir: File, linkDepth: Int) {
    File(dir, "gradle").mkdirs()
    enableTestKitConfigurationCache(dir)
    File(dir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "${dir.name}"
      """.trimIndent() + "\n"
    )
    File(dir, "build.gradle.kts").writeText("plugins { java }\n")
    val up = Path.of(List(linkDepth) { ".." }.joinToString("/"))
    listOf(metadata, keyring).forEach { shared ->
      Files.createSymbolicLink(File(dir, "gradle/${shared.name}").toPath(), up.resolve("gradle").resolve(shared.name))
    }
  }

  private fun runnerIn(dir: File) = GradleRunner.create().withProjectDir(dir)
    .withArguments("verificationPolicy", "--dependency-verification", "lenient", "--no-watch-fs")

  @Test
  fun `a repository with no policy and nothing of one is left to the notice`() {
    writeFixture()
    metadata.delete()
    keyring.delete()
    versionScopedKeys.delete()
    enableTestKitConfigurationCache(fixtureDir) // gradle.properties without the declaration
    val result = run("verificationPolicy")
    assertEquals(TaskOutcome.SUCCESS, result.task(":verificationPolicy")?.outcome, result.output)
    // a standalone build's own strict line is no declaration: the root's gradle.properties holds it
    File(fixtureDir, "jmh").mkdirs()
    File(fixtureDir, "jmh/settings.gradle.kts").writeText("rootProject.name = \"jmh\"\n")
    File(fixtureDir, "jmh/gradle.properties").writeText("org.gradle.dependency.verification=strict\n")
    assertEquals(TaskOutcome.SUCCESS, run("verificationPolicy").task(":verificationPolicy")?.outcome)
  }

  @Test
  fun `a keyring, a list or the declaration without the policy file is a deleted policy, not an unadopted repository`() {
    // the file alone gone: deleted, or kept out of the commit by a deny-by-default .gitignore
    writeFixture()
    metadata.delete()
    val remnants = fails("verificationPolicy")
    assertProblem(remnants, "gradle/verification-metadata.xml is missing while gradle/verification-keyring.keys is present")
    assertProblem(remnants, "gradle/verification-metadata.xml is missing while gradle/verification/version-scoped-keys.txt is present")
    assertProblem(remnants, "gradle.properties sets org.gradle.dependency.verification=strict, the declaration of a policy, but gradle/verification-metadata.xml is missing")
    // the declaration outlives all three files, in either spelling Gradle reads
    writeFixture()
    metadata.delete()
    keyring.delete()
    versionScopedKeys.delete()
    assertProblem(fails("verificationPolicy"), "gradle.properties sets org.gradle.dependency.verification=strict, the declaration of a policy")
    File(fixtureDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\nsystemProp.org.gradle.dependency.verification = Strict\n")
    assertProblem(fails("verificationPolicy"), "gradle.properties sets systemProp.org.gradle.dependency.verification=Strict, the declaration of a policy")
    // a relaxing mode is a committed verification setting too, refused with or without a policy
    File(fixtureDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\norg.gradle.dependency.verification=off\n")
    assertProblem(fails("verificationPolicy"), "gradle.properties sets org.gradle.dependency.verification=off, a committed verification setting with no policy")
    // a standalone build's links outlive the policy too, dangling
    writeFixture()
    writeStandaloneBuild("jmh", symlinks = true)
    metadata.delete()
    keyring.delete()
    versionScopedKeys.delete()
    enableTestKitConfigurationCache(fixtureDir)
    val links = fails("verificationPolicy")
    assertProblem(links, "gradle/verification-metadata.xml is missing while jmh/gradle/verification-metadata.xml is a link left behind")
    assertProblem(links, "gradle/verification-metadata.xml is missing while jmh/gradle/verification-keyring.keys is a link left behind")
    // copies in a standalone build are left behind too, and a value Gradle refuses has no policy to go with
    writeFixture()
    writeStandaloneBuild("soak", symlinks = false)
    metadata.delete()
    keyring.delete()
    versionScopedKeys.delete()
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "soak/gradle.properties").writeText("org.gradle.dependency.verification=stric\n")
    val copies = fails("verificationPolicy")
    assertProblem(copies, "gradle/verification-metadata.xml is missing while soak/gradle/verification-metadata.xml is present")
    assertProblem(copies, "soak/gradle.properties sets org.gradle.dependency.verification=stric, which is not a mode Gradle accepts")
  }

  @Test
  fun `a policy needs its declaration in the root gradle properties file`() {
    writeFixture()
    enableTestKitConfigurationCache(fixtureDir) // gradle.properties without the declaration
    assertProblem(fails("verificationPolicy"), "gradle.properties does not set org.gradle.dependency.verification=strict, the policy's declaration")
    // a standalone build's own line does not declare the root's policy
    writeStandaloneBuild("jmh", symlinks = true)
    File(fixtureDir, "jmh/gradle.properties").writeText("org.gradle.dependency.verification=strict\n")
    assertProblem(fails("verificationPolicy"), "gradle.properties does not set org.gradle.dependency.verification=strict")
    // either spelling declares it
    File(fixtureDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\nsystemProp.org.gradle.dependency.verification = Strict\n")
    val declared = run("verificationPolicy")
    assertEquals(TaskOutcome.SUCCESS, declared.task(":verificationPolicy")?.outcome, declared.output)
  }

  @Test
  fun `a policy needs its keyring and its version-scoped list`() {
    writeFixture()
    keyring.delete()
    assertProblem(fails("verificationPolicy"), "gradle/verification-keyring.keys is missing")
    writeFixture()
    versionScopedKeys.delete()
    assertProblem(fails("verificationPolicy"), "gradle/verification/version-scoped-keys.txt is missing")
  }

  @Test
  fun `each setting must be one active element written exactly`() {
    writeFixture()
    mutate("<verify-signatures>true</verify-signatures>", "<verify-signatures> true </verify-signatures>")
    assertProblem(fails("verificationPolicy"), "<verify-signatures>true</verify-signatures> must appear once")
    writeFixture()
    mutate("<verify-metadata>true</verify-metadata>", "<verify-metadata>&#116;rue</verify-metadata>")
    assertProblem(fails("verificationPolicy"), "<verify-metadata>true</verify-metadata> must appear once")
    writeFixture()
    mutate("<keyring-format>armored</keyring-format>", "<!--<keyring-format>armored</keyring-format>-->")
    assertProblem(fails("verificationPolicy"), "<keyring-format>armored</keyring-format> must appear once")
    writeFixture()
    mutate("<key-servers enabled=\"false\"/>", "<key-servers enabled=\"false\"/>\n      <key-servers enabled=\"true\"/>")
    assertProblem(fails("verificationPolicy"), "<key-servers enabled=\"false\"/> must appear once")
    // a comment naming an element counts for nothing, as it does for Gradle
    writeFixture()
    mutate("<key-servers enabled=\"false\"/>", "<!-- <key-servers enabled=\"false\"/> stays off: keys come from the keyring -->\n      <key-servers enabled=\"false\"/>")
    assertEquals(TaskOutcome.SUCCESS, run("verificationPolicy").task(":verificationPolicy")?.outcome)
  }

  @Test
  fun `ignored keys, exemptions, per-artifact keys, weak digests and pins of the local test publication are rejected`() {
    writeFixture()
    mutate(
      "<key-servers enabled=\"false\"/>",
      "<key-servers enabled=\"false\"/>\n      <ignored-keys><ignored-key id=\"ABCDEF0123456789\"/></ignored-keys>" +
        "\n      <trusted-artifacts><trust group=\"org.example\" name=\"thing\"/></trusted-artifacts>"
    )
    mutate(
      "<components>",
      "<components>\n      <component group=\"org.example\" name=\"parent\" version=\"1\">" +
        "<artifact name=\"parent-1.pom\"><pgp value=\"0123456789ABCDEF0123456789ABCDEF01234567\"/></artifact></component>" +
        "\n      <component group=\"org.example\" name=\"weak\" version=\"1\"><artifact name=\"weak-1.jar\">" +
        "<sha1 value=\"da39a3ee5e6b4b0d3255bfef95601890afd80709\"/></artifact></component>" +
        "\n      <component group=\"software.sava\" name=\"sava-build\" version=\"0.0.0-test\">" +
        "<artifact name=\"sava-build-0.0.0-test.jar\"><sha256 value=\"" + "0".repeat(64) + "\"/></artifact></component>"
    )
    val result = fails("verificationPolicy")
    assertProblem(result, "<ignored-key id=\"ABCDEF0123456789\"> skips that key's signatures")
    assertProblem(result, "<trusted-artifacts> entry group=\"org.example\" name=\"thing\" exempts")
    assertProblem(result, "<pgp value=\"0123456789ABCDEF0123456789ABCDEF01234567\"> trusts a key for org.example:parent:1 only")
    assertProblem(result, "<sha1> on org.example:weak:1 weak-1.jar pins a digest with known collisions")
    assertProblem(result, "component software.sava:sava-build:0.0.0-test pins the unsigned local test publication")
  }

  @Test
  fun `a listed key is read whatever whitespace follows it`() {
    writeFixture()
    versionScopedKeys.writeText(versionScopedKeys.readText().replace(Regex("^([0-9A-F]{40}) ", RegexOption.MULTILINE), "$1\t"))
    run("verificationPolicy")
  }

  @Test
  fun `a rule needs a group, trusts no file, keeps to the subgroup regex, and scopes its own attributes`() {
    writeFixture()
    mutate(
      "<trusted-keys>",
      "<trusted-keys>" +
        "\n         <trusted-key id=\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\" name=\"thing\"/>" +
        "\n         <trusted-key id=\"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\" group=\"org.example\" file=\"thing-1.jar\"/>" +
        "\n         <trusted-key id=\"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC\" group=\"^org[.]example($|([.].*))\" regex=\"true\" version=\"1\"/>" +
        "\n         <trusted-key id=\"EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE\" group=\"^com($|([.].*))\" regex=\"true\"/>" +
        "\n         <trusted-key id=\"DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD\" group=\"org.example\" version=\"1\"><trusting group=\"org.other\"/></trusted-key>" +
        "\n         <trusted-key id=\"EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE\" group=\"org[.]example.*\" regex=\"true\"/>"
    )
    val result = fails("verificationPolicy")
    assertProblem(result, "trusted-key AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA has a rule without a group (name=\"thing\")")
    assertProblem(result, "trusted-key EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE has a regex rule (group=\"^com($|([.].*))\" regex=\"true\") other than a literal group and its subgroups")
    assertProblem(result, "trusted-key BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB trusts file=\"thing-1.jar\" group=\"org.example\" by file name")
    assertProblem(result, "trusted-key CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC has a regex rule")
    assertProblem(result, "trusted-key DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD trusts group=\"org.example\" version=\"1\" only at that version")
    assertProblem(result, "trusted-key EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE has a regex rule (group=\"org[.]example.*\" regex=\"true\")")
  }

  @Test
  fun `listed keys stay at exact versions and the others version-free, whatever the case`() {
    writeFixture()
    // the expired apache key, listed: widened by dropping its version, under a lower-case id
    mutate(
      "<trusted-key id=\"0CDE80149711EB46DFF17AE421A24B3F8B0F594A\" group=\"org.apache\" name=\"apache\" version=\"16\"/>",
      "<trusted-key id=\"0cde80149711eb46dff17ae421a24b3f8b0f594a\" group=\"org.apache\" name=\"apache\"/>"
    )
    // a publisher key narrowed to one version, as a regeneration that saw one version writes it
    mutate(
      "<trusted-key id=\"1D217F8475EEE9F19AB8DD6B793FD5751A0F0780\" group=\"com.squareup.okio\"/>",
      "<trusted-key id=\"1D217F8475EEE9F19AB8DD6B793FD5751A0F0780\" group=\"com.squareup.okio\" version=\"3.15.0\"/>"
    )
    val result = fails("verificationPolicy")
    assertProblem(result, "trusted-key 0CDE80149711EB46DFF17AE421A24B3F8B0F594A trusts group=\"org.apache\" name=\"apache\" beyond exact versions")
    assertProblem(result, "trusted-key 1D217F8475EEE9F19AB8DD6B793FD5751A0F0780 trusts group=\"com.squareup.okio\" version=\"3.15.0\" only at that version")
    // a listed key under a regex, with the flag as Gradle reads it
    writeFixture()
    mutate(
      "<trusted-key id=\"0CDE80149711EB46DFF17AE421A24B3F8B0F594A\" group=\"org.apache\" name=\"apache\" version=\"16\"/>",
      "<trusted-key id=\"0CDE80149711EB46DFF17AE421A24B3F8B0F594A\" group=\"^org[.]apache($|([.].*))\" regex=\"TRUE\"/>"
    )
    assertProblem(fails("verificationPolicy"), "trusted-key 0CDE80149711EB46DFF17AE421A24B3F8B0F594A trusts group=\"^org[.]apache($|([.].*))\" regex=\"TRUE\" beyond exact versions")
  }

  @Test
  fun `a stale list line is reported`() {
    writeFixture()
    versionScopedKeys.appendText("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF retired key no rule uses\n")
    assertProblem(fails("verificationPolicy"), "lists FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF, which no trusted-key uses any more")
  }

  @Test
  fun `a committed gradle properties file cannot relax the mode, in the root or a standalone build`() {
    writeFixture()
    File(fixtureDir, "gradle.properties").appendText("systemProp.org.gradle.dependency.verification = Lenient\n")
    assertProblem(fails("verificationPolicy"), "gradle.properties sets systemProp.org.gradle.dependency.verification=Lenient")
    writeFixture()
    writeStandaloneBuild("soak", symlinks = true)
    File(fixtureDir, "soak/gradle.properties").writeText("org.gradle.dependency.verification=off\n")
    assertProblem(fails("verificationPolicy"), "soak/gradle.properties sets org.gradle.dependency.verification=off")
    // a root value other than strict is the relaxed mode, not a missing declaration as well
    File(fixtureDir, "gradle.properties").writeText("org.gradle.configuration-cache=true\norg.gradle.dependency.verification=lenient\n")
    val lenient = fails("verificationPolicy")
    assertProblem(lenient, "gradle.properties sets org.gradle.dependency.verification=lenient; verification stays strict")
    assertFalse(lenient.output.contains("does not set org.gradle.dependency.verification=strict"), lenient.output)
    // a value Gradle refuses, where Gradle does not read it (in the root's it stops the build before the guard)
    File(fixtureDir, "soak/gradle.properties").writeText("org.gradle.dependency.verification=stric\n")
    assertProblem(fails("verificationPolicy"), "soak/gradle.properties sets org.gradle.dependency.verification=stric, which is not a mode Gradle accepts")
  }

  @Test
  fun `standalone builds read the root policy through symlinks, included or not, and another checkout inside the root is no build`() {
    writeFixture()
    writeStandaloneBuild("jmh", symlinks = true)
    // a scratch clone or worktree inside the root: a settings script beside a .git entry
    val clone = File(fixtureDir, "scratch-clone")
    File(clone, ".git").mkdirs()
    File(clone, "settings.gradle.kts").writeText("rootProject.name = \"scratch\"\n")
    assertEquals(TaskOutcome.SUCCESS, run("verificationPolicy").task(":verificationPolicy")?.outcome)
    val copy = File(fixtureDir, "jmh/gradle/verification-metadata.xml")
    copy.delete()
    metadata.copyTo(copy)
    assertProblem(fails("verificationPolicy"), "jmh/gradle/verification-metadata.xml must be a relative symlink to ../../gradle/verification-metadata.xml")
    // an absolute link reaches the shared file here and dangles in a clone at another path
    copy.delete()
    Files.createSymbolicLink(copy.toPath(), metadata.toPath().toAbsolutePath())
    assertProblem(fails("verificationPolicy"), "jmh/gradle/verification-metadata.xml must be a relative symlink to ../../gradle/verification-metadata.xml")
    // an included build invoked directly reads only its own gradle/, so it needs the symlinks too
    File(fixtureDir, "jmh").deleteRecursively()
    writeFixture(includedBuilds = listOf("tools"))
    assertProblem(fails("verificationPolicy"), "tools/gradle/verification-metadata.xml must be a relative symlink to ../../gradle/verification-metadata.xml")
  }
}
