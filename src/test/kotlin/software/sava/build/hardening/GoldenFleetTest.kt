package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Golden-corpus tests over the consumer fleet's committed mutation-baseline files.
 *
 * `src/test/resources/golden-fleet/` holds byte-identical snapshots of every
 * `config/pitest/` directory across the fleet (see `MANIFEST.txt` for the source
 * repo, module path, and HEAD commit of each snapshot). Where [BaselineNotesTest]
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
    /**
     * The corpus root, resolved through the classpath so the tests exercise the
     * same resource-processing path a packaging change would break.
     */
    val corpusRoot: File = GoldenFleetTest::class.java.getResource("/golden-fleet/MANIFEST.txt")
        ?.let { File(it.toURI()).parentFile }
        ?: error("golden-fleet corpus is missing from the test classpath")

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

    /** Row lines: non-blank lines that are not `#` comments — the parsers' input. */
    fun rowLines(file: File): List<String> =
        file.readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }

    fun relative(file: File): String = file.relativeTo(corpusRoot).invariantSeparatorsPath
  }

  @Test
  fun `the corpus is present with every fleet repo and file kind`() {
    val repos = corpusRoot.listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
    assertEquals(
        listOf("http-servers", "idl-src-gen", "incident-client", "ix-proxy",
            "json-iterator", "ravina", "sava", "vault-stat-service"),
        repos, "a fleet repo's snapshot went missing from the corpus")
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
  fun `the corpus canary counts well over 300 rows`() {
    // Guards the loading path, not the format: a resource-processing or layout
    // change that silently empties the corpus would turn every test above into a
    // vacuous pass over zero files.
    val totalRows = (corpusFiles("-accepted.csv") + corpusFiles("-timeouts.csv"))
        .sumOf { rowLines(it).size }
    assertTrue(totalRows > 300,
        "corpus row count fell to $totalRows — the golden fleet resources are not loading")
  }
}
