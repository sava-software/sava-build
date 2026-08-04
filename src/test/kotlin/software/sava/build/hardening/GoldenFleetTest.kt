package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden-corpus tests over the consumer fleet's committed mutation-baseline files.
 *
 * `src/test/resources/golden-fleet/` holds byte-identical snapshots of the
 * `config/pitest/` directories enumerated by its `MANIFEST.txt` (source repo,
 * module path, and HEAD commit). This historical parser corpus is deliberately
 * independent of the release runner's current fleet inventory; its own manifest
 * is nevertheless exact, so an omitted snapshot cannot turn these checks vacuous.
 * Where [BaselineNotesTest]
 * and [TimeoutAuditTest] argue the format's edge cases with constructed lines,
 * these tests hold the parsers to the data the fleet actually shipped: a format
 * regression that would only surface in a consumer's verify after release fails
 * here first, against the exact rows it would break on.
 *
 * The corpus must stay byte-identical to the fleet's committed state — a failure
 * here is fixed in the parser (or recorded as a named known-bad row below), never
 * by editing the snapshot.
 */
class GoldenFleetTest {

  private companion object {
    data class ManifestEntry(val repo: String, val modulePath: String, val commit: String) {
      val corpusPath: String =
          "$repo/${modulePath.replace("/", "__")}"
    }

    /**
     * The corpus root, resolved through the classpath so the tests exercise the
     * same resource-processing path a packaging change would break.
     */
    val corpusRoot: File = GoldenFleetTest::class.java.getResource("/golden-fleet/MANIFEST.txt")
        ?.let { File(it.toURI()).parentFile }
        ?: error("golden-fleet corpus is missing from the test classpath")

    val manifestEntries: List<ManifestEntry> = File(corpusRoot, "MANIFEST.txt").readLines()
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        .map { (index, line) ->
          val fields = line.split('\t')
          require(fields.size == 3) {
            "golden-fleet/MANIFEST.txt:${index + 1}: expected repo, module path, and commit"
          }
          ManifestEntry(fields[0], fields[1], fields[2])
        }

    /**
     * Corpus files whose committed content genuinely fails an assertion, keyed by
     * corpus-relative path. Empty is the goal: an entry here records a real fleet
     * defect (named in the entry's comment) without weakening the assertion for
     * every other file. Currently none.
     */
    val knownMalformedAccepted: Set<String> = emptySet()
    val knownMalformedTimeouts: Set<String> = emptySet()
    val knownUndocumentedCauses: Set<String> = emptySet()

    fun corpusFiles(suffix: String): List<File> = corpusRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(suffix) }
        .sortedBy { it.relativeTo(corpusRoot).path }
        .toList()

    /**
     * Record lines presented by either supported baseline document schema. Parsing
     * through [BaselineDocument] keeps this fleet corpus sensitive to schema headers
     * while retaining malformed records for the dedicated assertion below.
     */
    fun rowLines(file: File): List<String> =
        if (file.name.endsWith("-accepted.csv")) {
          BaselineDocument.parse(file.readText()).entries.mapNotNull {
            when (it) {
              is BaselineDocument.Entry.Row -> it.raw
              is BaselineDocument.Entry.MalformedRow -> it.raw
              is BaselineDocument.Entry.Blank,
              is BaselineDocument.Entry.Comment -> null
            }
          }
        } else {
          file.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        }

    fun relative(file: File): String = file.relativeTo(corpusRoot).invariantSeparatorsPath
  }

  @Test
  fun `the manifest exactly describes the corpus inventory`() {
    val malformedEntries = manifestEntries.filter {
      it.repo.isBlank() || it.modulePath.isBlank() || !it.commit.matches(Regex("[0-9a-f]{40}"))
    }
    assertEquals(emptyList<ManifestEntry>(), malformedEntries, "malformed golden-fleet manifest row(s)")
    assertEquals(
        manifestEntries.size,
        manifestEntries.distinctBy { it.corpusPath }.size,
        "golden-fleet manifest maps more than one row to the same corpus directory")

    val expectedModules = manifestEntries.map { it.corpusPath }.sorted()
    val actualModules = corpusRoot.listFiles { file -> file.isDirectory }.orEmpty()
        .flatMap { repo ->
          repo.listFiles { file -> file.isDirectory }.orEmpty()
              .map { module -> "${repo.name}/${module.name}" }
        }
        .sorted()
    assertEquals(expectedModules, actualModules,
        "golden-fleet module directories diverged from MANIFEST.txt")

    val expectedRepos = manifestEntries.map { it.repo }.distinct().sorted()
    val actualRepos = corpusRoot.listFiles { file -> file.isDirectory }.orEmpty()
        .map { it.name }.sorted()
    assertEquals(expectedRepos, actualRepos,
        "golden-fleet repository directories diverged from MANIFEST.txt")

    expectedModules.forEach { modulePath ->
      val module = File(corpusRoot, modulePath)
      assertTrue(File(module, "README.md").isFile, "$modulePath has no snapshotted README.md")
      assertTrue(
          module.listFiles { file -> file.isFile && file.name.endsWith("-accepted.csv") }
              .orEmpty().isNotEmpty(),
          "$modulePath has no snapshotted accepted baseline",
      )
    }

    assertTrue(corpusFiles("-accepted.csv").isNotEmpty(), "no accepted baselines in the corpus")
    assertTrue(corpusFiles("-timeouts.csv").isNotEmpty(), "no timeout memberships in the corpus")
    assertTrue(corpusFiles("README.md").isNotEmpty(), "no READMEs in the corpus")
  }

  @Test
  fun `every golden accepted row parses as well-formed`() {
    val failures = corpusFiles("-accepted.csv")
        .filterNot { relative(it) in knownMalformedAccepted }
        .flatMap { file ->
          rowLines(file).filter { BaselineNotes.malformed(it) }
              .map { "${relative(file)}: $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "malformed accepted row(s) in fleet data the parser must handle")
  }

  @Test
  fun `every golden accepted row canonicalizes to a render fixed point`() {
    // The first round-trip may change a row — a legacy five-field row migrates its
    // line field to a '# line' tag, and spacing or '# lines 137/141' separators
    // normalize — but the canonical form must be a fixed point: a second
    // parse/render that changes anything would churn every refresh forever.
    val failures = corpusFiles("-accepted.csv").flatMap { file ->
      rowLines(file).mapNotNull { line ->
        val canonical = BaselineNotes.render(BaselineNotes.parse(line))
        val again = BaselineNotes.render(BaselineNotes.parse(canonical))
        if (again == canonical) null
        else "${relative(file)}:\n    original:  $line\n    canonical: $canonical\n    again:     $again"
      }
    }
    assertEquals(emptyList<String>(), failures, "canonical form is not a render fixed point")
  }

  @Test
  fun `summarize speaks for every non-empty golden baseline`() {
    corpusFiles("-accepted.csv").forEach { file ->
      val rows = rowLines(file).map { BaselineNotes.parse(it) }
      val summary = BaselineNotes.summarize(
          rows.mapNotNull { it.note }, rows.count { it.note == null })
      if (rows.isNotEmpty()) {
        assertNotNull(summary, "${relative(file)}: a non-empty baseline summarized to nothing")
      }
    }
  }

  @Test
  fun `every golden timeout membership parses as well-formed`() {
    val failures = corpusFiles("-timeouts.csv")
        .filterNot { relative(it) in knownMalformedTimeouts }
        .flatMap { file ->
          TimeoutAudit.parse(file.readLines()).malformed.map { "${relative(file)}: $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "malformed timeout row(s) in fleet data the parser must handle")
  }

  @Test
  fun `every golden audited timeout has its cause documented in the module README`() {
    // The fleet's discipline, held as a fixture: each audited member's structural
    // cause lives in the module's config/pitest/README.md, and undocumentedCauses
    // is the check that keeps it that way — so against shipped data it must find
    // nothing (a hit is either a fleet defect to record above, or a resolver
    // regression that would nag every consumer about causes they already wrote).
    val failures = corpusFiles("-timeouts.csv")
        .filterNot { relative(it) in knownUndocumentedCauses }
        .flatMap { file ->
          val readme = File(file.parentFile, "README.md")
          assertTrue(readme.isFile,
              "${relative(file)}: no adjacent README.md — the fleet always pairs them")
          TimeoutAudit.undocumentedCauses(
              TimeoutAudit.parse(file.readLines()).members) { readme.readText() }
              .map { "${relative(file)}: cause? $it" }
        }
    assertEquals(emptyList<String>(), failures,
        "audited timeout member(s) whose README cause did not resolve")
  }

  @Test
  fun `the corpus canary retains every snapshotted baseline row`() {
    // This is deliberately exact. The manifest inventory catches a missing module,
    // while the row total catches partial resource loss within a still-present one.
    // A deliberate corpus refresh updates this number alongside its provenance.
    val totalRows = corpusFiles("-accepted.csv")
        .sumOf { BaselineDocument.parse(it.readText()).rows.size } +
        corpusFiles("-timeouts.csv").sumOf { rowLines(it).size }
    assertEquals(1564, totalRows,
        "golden-fleet baseline row count changed; restore lost resources or record the intentional refresh")
  }
}
