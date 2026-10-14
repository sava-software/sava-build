package software.sava.build.guard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** A marker for a setting the fixture must leave out, so release-please's own default applies. */
object Unset

/** The smallest JSON writer the fixtures need: maps, lists, strings, numbers, booleans and null. */
fun toJson(value: Any?): String = when (value) {
  null -> "null"
  is String -> buildString {
    append('"')
    for (c in value) {
      when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
      }
    }
    append('"')
  }
  is Boolean, is Number -> value.toString()
  is Map<*, *> -> value.entries.filter { it.value !== Unset }
    .joinToString(",", "{", "}") { toJson(it.key.toString()) + ":" + toJson(it.value) }
  is List<*> -> value.joinToString(",", "[", "]") { toJson(it) }
  else -> throw IllegalArgumentException("not JSON: $value")
}

data class Run(val code: Int, val out: String)

fun assertMatch(out: String, pattern: String) =
  assertTrue(Regex(pattern).containsMatchIn(out), "expected /$pattern/ in:\n$out")

fun assertNoMatch(out: String, pattern: String) =
  assertFalse(Regex(pattern).containsMatchIn(out), "did not expect /$pattern/ in:\n$out")

/** A scratch repository with release-please's config and manifest, run through the guard in-process. */
class Repo(private val config: Map<String, Any?> = emptyMap()) {
  val dir: Path = Files.createTempDirectory("guard-")
  private var n = 0

  init {
    git("init", "-q", "-b", "main")
    git("config", "user.email", "t@example.com")
    git("config", "user.name", "t")
    git("config", "commit.gpgsign", "false")
    git("config", "tag.gpgsign", "false")
  }

  fun git(vararg arguments: String, input: String? = null): String {
    val process = ProcessBuilder(listOf("git") + arguments).directory(dir.toFile()).redirectErrorStream(true).start()
    process.outputStream.use { if (input != null) it.write(input.toByteArray(StandardCharsets.UTF_8)) }
    val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output.trim()
  }

  /**
   * Commits the message; files default to one new file, and an empty map commits nothing.
   * git's default cleanup collapses runs of blank lines; [verbatim] keeps the message as is.
   */
  fun commit(message: String, files: Map<String, String>? = null, verbatim: Boolean = false): String {
    for ((name, text) in files ?: mapOf("f${++n}.txt" to "x")) {
      val file = dir.resolve(name)
      Files.createDirectories(file.parent)
      Files.writeString(file, text)
      git("add", name)
    }
    git("commit", "-q", "--allow-empty", "--cleanup=" + (if (verbatim) "verbatim" else "default"), "-F", "-", input = message)
    return git("rev-parse", "HEAD")
  }

  fun configure(version: String, extra: Map<String, Any?> = emptyMap()) {
    // The settings every sava repository's config has.
    val top = linkedMapOf<String, Any?>("release-type" to "simple", "include-v-in-tag" to false,
      "include-component-in-tag" to false, "versioning" to "always-bump-patch")
    top.putAll(config)
    @Suppress("UNCHECKED_CAST")
    val configuredPackage = (config["packages"] as? Map<String, Any?>)?.get(".") as? Map<String, Any?> ?: emptyMap()
    val pkg = linkedMapOf<String, Any?>("package-name" to "p")
    pkg.putAll(configuredPackage)
    pkg.putAll(extra)
    top["packages"] = mapOf("." to pkg)
    Files.writeString(dir.resolve("release-please-config.json"), toJson(top))
    Files.writeString(dir.resolve(".release-please-manifest.json"), toJson(mapOf("." to version)))
  }

  fun run(env: Map<String, String> = emptyMap()): Run {
    val full = linkedMapOf(
      "CONFIG_FILE" to dir.resolve("release-please-config.json").toString(),
      "MANIFEST_FILE" to dir.resolve(".release-please-manifest.json").toString(),
    )
    full.putAll(env)
    val bytes = ByteArrayOutputStream()
    val code = PrintStream(bytes, true, StandardCharsets.UTF_8).use { Guard(dir, full, it).run() }
    return Run(code, bytes.toString(StandardCharsets.UTF_8))
  }
}

const val HEAD = "release-please--branches--main--components--p"
val MERGE = "c".repeat(40)
val HEAD_SHA = "d".repeat(40)

fun manifestOf(version: String) = toJson(mapOf("." to version))

/** A body shaped like release-please's own: the header, the '---' delimiters and one release's notes. */
fun releaseBody(version: String, notes: String = "* later") =
  ":robot: I have created a release *beep* *boop*\n---\n\n\n" +
    "## [$version](https://github.com/o/r/compare/1.0.0...$version) (2026-10-13)\n\n\n### Bug Fixes\n\n$notes\n\n" +
    "---\nThis PR was generated with [Release Please](https://github.com/googleapis/release-please)."

fun pull(
  number: Int,
  version: String,
  title: String = "chore(main): release $version",
  body: String = releaseBody(version),
  merged: Boolean = true,
  open: Boolean = false,
  labels: List<String> = listOf("autorelease: pending"),
  head: String = HEAD,
  base: String = "main",
  merge: String = MERGE,
): Map<String, Any?> = mapOf(
  "number" to number, "title" to title, "body" to body,
  "head" to mapOf("ref" to head, "sha" to HEAD_SHA), "base" to mapOf("ref" to base),
  "state" to if (open) "open" else "closed",
  "merged_at" to if (merged && !open) "2026-10-13T00:00:00Z" else null,
  "merge_commit_sha" to if (merged && !open) merge else null,
  "labels" to labels.map { mapOf("name" to it) },
)

fun filler(from: Int, count: Int, merged: Boolean = true): List<Map<String, Any?>> =
  (0 until count).map { pull(from + it, "0.0.1", title = "fix: x", body = "x", labels = emptyList(), merged = merged) }

/** A pull request a commit belongs to, as the GraphQL query lists it: number, body and merge commit. */
fun assoc(number: Int, mergeCommit: String? = null, body: String = ""): Map<String, Any?> =
  mapOf("number" to number, "body" to body, "mergeCommit" to mergeCommit?.let { mapOf("oid" to it) })

/**
 * A stand-in for the GitHub API: serves the requested page of the given pull requests in
 * that state, file contents by ref and path, and the pull requests each commit belongs to
 * (the GraphQL query, from [commitPulls]; a commit in [unknownCommits] is one GitHub does
 * not have), and whether a GitHub release exists for a tag (those in [releases]); logs every
 * call. Every call fails with [fail], and a file read with the status [failing] holds for its
 * ref|path, or for its path on any ref. A file mapped to null does not exist.
 */
class FakeGitHub(
  repo: Repo,
  private val pulls: List<Map<String, Any?>>,
  files: Map<String, String?> = emptyMap(),
  private val branch: String = "main",
  private val fail: Int? = null,
  private val failing: Map<String, Int> = emptyMap(),
  private val commitPulls: Map<String, List<Map<String, Any?>>> = emptyMap(),
  private val unknownCommits: Set<String> = emptySet(),
  private val releases: Set<String> = emptySet(),
) : AutoCloseable {
  private val files: Map<String, String?>
  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val log = mutableListOf<String>()

  init {
    val manifest = Files.readString(repo.dir.resolve(".release-please-manifest.json"))
    // The manifest at a release's own commit (MERGE for merged, HEAD_SHA for open pull
    // requests) is the scratch repository's unless a test says otherwise.
    this.files = linkedMapOf<String, String?>(
      "$MERGE|.release-please-manifest.json" to manifest,
      "$HEAD_SHA|.release-please-manifest.json" to manifest,
    ).apply { putAll(files) }
    server.createContext("/") { exchange -> handle(exchange) }
    server.start()
  }

  val env: Map<String, String>
    get() = mapOf(
      "GITHUB_API_URL" to "http://127.0.0.1:${server.address.port}",
      "GH_TOKEN" to "stub", "GITHUB_REPOSITORY" to "o/r", "TARGET_BRANCH" to branch,
    )

  fun calls(): List<String> = synchronized(log) { log.toList() }

  private fun handle(exchange: HttpExchange) {
    val query = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
    synchronized(log) { log.add(query) }
    if (fail != null) return respond(exchange, fail, "failed")
    val contents = Regex("^/repos/o/r/contents/(.+)\\?ref=(.+)$").find(query)
    if (contents != null) {
      val path = contents.groupValues[1].split("/").joinToString("/") { URLDecoder.decode(it, StandardCharsets.UTF_8) }
      val key = URLDecoder.decode(contents.groupValues[2], StandardCharsets.UTF_8) + "|" + path
      val status = failing[key] ?: failing[path]
      if (status != null) return respond(exchange, status, "failed")
      val text = files[key] ?: return respond(exchange, 404, "{\"message\":\"Not Found $key\"}")
      return respond(exchange, 200, text)
    }
    val release = Regex("^/repos/o/r/releases/tags/(.+)$").find(query)
    if (release != null) {
      val tag = URLDecoder.decode(release.groupValues[1], StandardCharsets.UTF_8)
      return if (tag in releases) respond(exchange, 200, toJson(mapOf("tag_name" to tag)))
      else respond(exchange, 404, "{\"message\":\"Not Found\"}")
    }
    if (query == "/graphql") {
      val request = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
      val repository = linkedMapOf<String, Any?>()
      for (alias in Regex("""(c\d+): object\(oid: \\"([0-9a-f]{40})\\"\)""").findAll(request)) {
        val sha = alias.groupValues[2]
        repository[alias.groupValues[1]] = if (sha in unknownCommits) null
        else mapOf("associatedPullRequests" to mapOf("nodes" to (commitPulls[sha] ?: emptyList())))
      }
      return respond(exchange, 200, toJson(mapOf("data" to mapOf("repository" to repository))))
    }
    if (query.startsWith("/repos/o/r/pulls?")) {
      val state = Regex("[?&]state=(\\w+)").find(query)!!.groupValues[1]
      val page = Regex("[?&]page=(\\d+)").find(query)!!.groupValues[1].toInt()
      val per = Regex("[?&]per_page=(\\d+)").find(query)!!.groupValues[1].toInt()
      val listed = pulls.filter { it["state"] == state }
      val from = minOf((page - 1) * per, listed.size)
      return respond(exchange, 200, toJson(listed.subList(from, minOf(page * per, listed.size))))
    }
    respond(exchange, 404, "{\"message\":\"Not Found\"}")
  }

  private fun respond(exchange: HttpExchange, status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  override fun close() = server.stop(0)
}

/** After a release pull request merged: the manifest holds the version it wrote, the patch bump of the last tag. */
fun released(config: Map<String, Any?> = emptyMap(), version: String = "25.12.1"): Repo {
  val r = Repo(config)
  r.commit("chore: start\n")
  r.git("tag", "25.12.0")
  r.commit("fix: later\n")
  r.configure(version)
  return r
}
