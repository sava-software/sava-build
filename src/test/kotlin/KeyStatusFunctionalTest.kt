import com.sun.net.httpserver.HttpServer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * verificationKeyStatus against a key server in this JVM that answers HKP's machine-readable
 * get with the fixture keys: a revoked key trusted for every version fails the task, after the
 * receipt is written; the same key at exact versions, an expired key and a key no server holds
 * warn; a repository with no policy has nothing to check. The fixture trusts the keys with
 * signature verification off, since the status check reads the policy's rules, not the
 * artifacts, and the fixture resolves this plugin's unsigned test publication.
 */
class KeyStatusFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  private lateinit var server: HttpServer
  private val requests = mutableListOf<String>()
  /** What the server answers at /upstream.xml, gradle/gradle's policy for the corroboration check; 404 when null. */
  private var upstreamXml: String? = null

  private val keys: Map<String, String> = KeyStatusFunctionalTest::class.java.getResource("/verification/keys/INDEX.txt")!!.readText()
    .lines().filter { it.isNotBlank() }.associate { line ->
      val (fingerprint, name) = line.split(" ")
      fingerprint to KeyStatusFunctionalTest::class.java.getResource("/verification/keys/$name.asc")!!.readText()
    }
  private val current = "7196217169F374EC409D4FDCDDC438C19242DE33"
  private val revoked = "BAC3875ACB834F9A779A52B2FC3980D3CB8388A0"
  private val expired = "63213B28A8C13E7172220DA4037DB4EF77494A5B"
  private val unknown = "0123456789ABCDEF0123456789ABCDEF01234567"
  /** The server answers 503 for this fingerprint. */
  private val broken = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0000"

  private val metadata get() = File(fixtureDir, "gradle/verification-metadata.xml")
  private val receipt get() = File(fixtureDir, "build/reports/verification/key-status.tsv")

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/pks/lookup") { exchange ->
      val query = URLDecoder.decode(exchange.requestURI.rawQuery ?: "", Charsets.UTF_8)
      requests += query
      val fingerprint = query.split('&').firstOrNull { it.startsWith("search=0x") }?.removePrefix("search=0x")?.uppercase()
      val body = keys[fingerprint]
      if (fingerprint == broken) {
        exchange.sendResponseHeaders(503, -1)
      } else if (body == null || !query.contains("op=get")) {
        exchange.sendResponseHeaders(404, -1)
      } else {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/pgp-keys")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      exchange.close()
    }
    server.createContext("/upstream.xml") { exchange ->
      requests += "upstream"
      val body = upstreamXml
      if (body == null) {
        exchange.sendResponseHeaders(404, -1)
      } else {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      exchange.close()
    }
    server.start()
  }

  private fun upstream(vararg rules: String) =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<verification-metadata xmlns=\"https://schema.gradle.org/dependency-verification\"><configuration><trusted-keys>\n" +
      rules.joinToString("\n") + "\n</trusted-keys></configuration></verification-metadata>\n"

  /** The list file with a by-decision note naming [keys], as the fleet's lists carry it. */
  private fun writeNote(vararg keys: String) {
    val list = File(fixtureDir, "gradle/verification/version-scoped-keys.txt")
    list.parentFile.mkdirs()
    list.writeText(
      "# Signing keys that ../verification-metadata.xml trusts only for exact versions.\n#\n" +
        "# Not listed, by decision: keys trusted for their groups with no version on the\n" +
        "# corroboration of gradle/gradle's own policy alone: " + keys.joinToString(", ") + ".\n\n" +
        "# Keys that must not vouch for new releases.\n"
    )
  }

  @AfterEach
  fun stopServer() = server.stop(0)

  private fun writeFixture(rules: String) {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        plugins {
          id("software.sava.build")
        }

        rootProject.name = "key-status"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText("plugins { java }\n")
    metadata.parentFile.mkdirs()
    metadata.writeText(
      listOf(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<verification-metadata xmlns=\"https://schema.gradle.org/dependency-verification\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"https://schema.gradle.org/dependency-verification https://schema.gradle.org/dependency-verification/dependency-verification-1.3.xsd\">",
        "   <configuration>",
        "      <verify-metadata>false</verify-metadata>",
        "      <verify-signatures>false</verify-signatures>",
        "      <trusted-keys>",
      ).joinToString("\n", postfix = "\n") +
        rules.trimIndent().lines().joinToString("\n", postfix = "\n") { "         $it" } +
        "      </trusted-keys>\n   </configuration>\n</verification-metadata>\n"
    )
  }

  private fun runner(vararg arguments: String) = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*arguments, "-PsavaBuildKeyServers=http://127.0.0.1:${server.address.port}", "--dependency-verification", "lenient", "--no-watch-fs")

  private fun run(vararg arguments: String): BuildResult = runner(*arguments).build()

  private fun fails(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

  private fun receiptRows(): Map<String, List<String>> = receipt.readLines().drop(1).filter { it.isNotEmpty() }
    .map { it.split("\t") }.associateBy { it[1] }

  @Test
  fun `a revoked key trusted for every version fails the task after the receipt is written`() {
    writeFixture(
      """
              <trusted-key id="$current" group="org.example"/>
              <trusted-key id="$revoked" group="org.example" name="revoked"/>
              <trusted-key id="$expired">
                 <trusting group="dev.cel"/>
                 <trusting group="dev.cel.other" version="1.0"/>
              </trusted-key>
      """.trimIndent()
    )
    val result = fails("verificationKeyStatus")
    assertEquals(TaskOutcome.FAILED, result.task(":verificationKeyStatus")?.outcome, result.output)
    assertTrue(result.output.contains("Revoked key(s) trusted for every version (sava-build's README, Verifying sava artifacts, Key status, says what to do):"), result.output)
    assertTrue(result.output.contains("  - $revoked (Revoked Fixture <revoked@fixture.invalid>) revoked, revoked 2026-10-31T"), result.output)
    assertTrue(result.output.contains(": key status fixture; trusted for every version of group=\"org.example\" name=\"revoked\""), result.output)
    assertTrue(result.output.contains("verificationKeyStatus: 3 trusted key(s) checked on http://127.0.0.1:${server.address.port}: 1 current, 1 expired, 1 revoked, 0 on no server, 0 undecided; receipt "), result.output)
    // the expired key warns in the same run, and the current one says nothing
    assertTrue(result.output.contains("verificationKeyStatus: $expired (") && result.output.contains(") expired, expires 2025-01-19T"), result.output)
    assertFalse(result.output.contains("verificationKeyStatus: $current"), result.output)
    val rows = receiptRows()
    assertEquals(setOf(current, revoked, expired), rows.keys)
    assertEquals(listOf("REVOKED", "true"), rows.getValue(revoked).subList(2, 4))
    assertEquals(listOf("EXPIRED", "true"), rows.getValue(expired).subList(2, 4))
    assertEquals(listOf("CURRENT", "true", "Current Fixture <current@fixture.invalid>", "", ""), rows.getValue(current).subList(2, 7))
    assertEquals(3, requests.size, requests.toString())
    assertTrue(requests.all { it.startsWith("op=get&options=mr&search=0x") }, requests.toString())
  }

  @Test
  fun `a revoked key at exact versions, an expired key and a key no server holds warn, and the task runs every time`() {
    writeFixture(
      """
              <trusted-key id="$revoked" group="org.example" name="revoked" version="1.0"/>
              <trusted-key id="$expired" group="dev.cel"/>
              <trusted-key id="$unknown" group="org.unknown"/>
      """.trimIndent()
    )
    val result = run("verificationKeyStatus")
    assertEquals(TaskOutcome.SUCCESS, result.task(":verificationKeyStatus")?.outcome, result.output)
    assertTrue(result.output.contains("3 trusted key(s) checked on http://127.0.0.1:${server.address.port}: 0 current, 1 expired, 1 revoked, 1 on no server, 0 undecided;"), result.output)
    assertTrue(result.output.contains("verificationKeyStatus: $revoked (Revoked Fixture <revoked@fixture.invalid>) revoked, revoked 2026-10-31T"), result.output)
    assertTrue(result.output.contains("; trusted at exact versions of group=\"org.example\" name=\"revoked\" version=\"1.0\""), result.output)
    assertTrue(result.output.contains("verificationKeyStatus: $unknown absent: no key server holds this key; trusted for every version of group=\"org.unknown\""), result.output)
    val rows = receiptRows()
    assertEquals(listOf("REVOKED", "false"), rows.getValue(revoked).subList(2, 4))
    assertEquals(listOf("ABSENT", "true", "", "", "", "no key server holds this key", "", ""), rows.getValue(unknown).subList(2, 10))
    // untracked: a second run asks the servers again, under a reused configuration cache
    val again = run("verificationKeyStatus")
    assertEquals(TaskOutcome.SUCCESS, again.task(":verificationKeyStatus")?.outcome, again.output)
    assertTrue(again.output.contains("Configuration cache entry reused."), again.output)
    assertEquals(6, requests.size, requests.toString())
  }

  @Test
  fun `a server that does not answer leaves the key undecided, which fails the task, and an empty server list is refused`() {
    writeFixture(
      """
              <trusted-key id="$current" group="org.example"/>
              <trusted-key id="$broken" group="org.broken"/>
      """.trimIndent()
    )
    val result = fails("verificationKeyStatus")
    assertEquals(TaskOutcome.FAILED, result.task(":verificationKeyStatus")?.outcome, result.output)
    assertTrue(result.output.contains("2 trusted key(s) checked on http://127.0.0.1:${server.address.port}: 1 current, 0 expired, 0 revoked, 0 on no server, 1 undecided;"), result.output)
    assertTrue(result.output.contains("Key(s) whose status a key server left undecided (run again when the servers answer; -PsavaBuildKeyServers names others):"), result.output)
    assertTrue(result.output.contains("  - $broken undecided: a server did not answer, so the status is undecided: http://127.0.0.1:${server.address.port}: http://127.0.0.1:${server.address.port}/pks/lookup?op=get&options=mr&search=0x$broken answered 503; trusted for every version of group=\"org.broken\""), result.output)
    assertFalse(result.output.contains("Revoked key(s)"), result.output)
    val rows = receiptRows()
    assertEquals(listOf("UNDECIDED", "true"), rows.getValue(broken).subList(2, 4))
    assertEquals(listOf("CURRENT", "true"), rows.getValue(current).subList(2, 4))
    // no server at all is a mistake, not a pass
    val empty = GradleRunner.create().withProjectDir(fixtureDir)
      .withArguments("verificationKeyStatus", "-PsavaBuildKeyServers=", "--dependency-verification", "lenient", "--no-watch-fs").buildAndFail()
    assertTrue(empty.output.contains("savaBuildKeyServers names no key server; leave the property unset for the two defaults"), empty.output)
  }

  @Test
  fun `a key the list's header names as corroborated is checked against gradle's policy, and warns when that trust is gone`() {
    writeFixture(
      """
              <trusted-key id="$current">
                 <trusting group="org.example"/>
                 <trusting group="^org[.]example[.]sub($|([.].*))" regex="true"/>
              </trusted-key>
              <trusted-key id="$expired" group="dev.cel" version="0.13.0"/>
      """.trimIndent()
    )
    writeNote(current)
    val source = "-PsavaBuildCorroborationSource=http://127.0.0.1:${server.address.port}/upstream.xml"
    // vouched: gradle/gradle names the group on a module of theirs, and a regex of theirs covers the group our regex starts with
    upstreamXml = upstream("<trusted-key id=\"$current\" group=\"org.example\" name=\"some-module\"/>", "<trusted-key id=\"$current\" group=\"^org[.]example($|([.].*))\" regex=\"true\"/>")
    val vouched = run("verificationKeyStatus", source)
    assertTrue(vouched.output.contains("1 key(s) the list's header names as trusted on gradle/gradle's corroboration: 1 vouched by its policy today, 0 not"), vouched.output)
    assertFalse(vouched.output.contains("vouches for it in") || vouched.output.contains("no longer in gradle/gradle"), vouched.output)
    assertEquals("vouched by gradle/gradle for org.example:some-module, regex ^org[.]example($|([.].*))", receiptRows().getValue(current).last())
    assertEquals("", receiptRows().getValue(expired).last(), "a listed key is not the corroboration's concern")
    // other groups only
    upstreamXml = upstream("<trusted-key id=\"$current\" group=\"org.other\"/>")
    val other = run("verificationKeyStatus", source)
    assertTrue(other.output.contains("verificationKeyStatus: $current gradle/gradle vouches for it in org.other, not in org.example, org.example.sub; trusted for every version of group=\"org.example\", group=\"^org[.]example[.]sub($|([.].*))\" regex=\"true\""), other.output)
    assertEquals(TaskOutcome.SUCCESS, other.task(":verificationKeyStatus")?.outcome)
    // gone from their policy
    upstreamXml = upstream("<trusted-key id=\"$revoked\" group=\"org.example\"/>")
    val gone = run("verificationKeyStatus", source)
    assertTrue(gone.output.contains("verificationKeyStatus: $current no longer in gradle/gradle's policy; trusted for every version of group=\"org.example\", group=\"^org[.]example[.]sub($|([.].*))\" regex=\"true\": find a publisher source, or move the key to exact versions and the list"), gone.output)
    assertEquals("no longer in gradle/gradle's policy", receiptRows().getValue(current).last())
    // the source unreachable: a warning, since the check itself only warns
    upstreamXml = null
    val unreachable = run("verificationKeyStatus", source)
    assertTrue(unreachable.output.contains("verificationKeyStatus: corroboration not checked: http://127.0.0.1:${server.address.port}/upstream.xml: http://127.0.0.1:${server.address.port}/upstream.xml answered 404"), unreachable.output)
    assertEquals(TaskOutcome.SUCCESS, unreachable.task(":verificationKeyStatus")?.outcome)
    assertEquals(4, requests.count { it == "upstream" }, requests.toString())
  }

  @Test
  fun `check never schedules the task, and a repository with no policy has nothing to check`() {
    writeFixture("""      <trusted-key id="$current" group="org.example"/>""")
    // check's plan holds the guard and not the status task (the fixture's policy, minimal by design, would fail the guard itself)
    val plan = run("check", "--dry-run")
    assertTrue(plan.output.contains(":verificationPolicy SKIPPED"), plan.output)
    assertFalse(plan.output.contains(":verificationKeyStatus"), plan.output)
    assertTrue(requests.isEmpty(), requests.toString())
    metadata.delete()
    val result = run("verificationKeyStatus")
    assertEquals(TaskOutcome.SUCCESS, result.task(":verificationKeyStatus")?.outcome, result.output)
    assertTrue(result.output.contains("verificationKeyStatus: no gradle/verification-metadata.xml, nothing to check"), result.output)
    assertTrue(receipt.isFile && receipt.readText().isEmpty())
    assertTrue(requests.isEmpty(), requests.toString())
  }
}
