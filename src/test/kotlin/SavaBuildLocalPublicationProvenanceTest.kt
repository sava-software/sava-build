import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import software.sava.build.hardening.PitestEvidence
import java.time.Instant

class SavaBuildLocalPublicationProvenanceTest {

  @Test
  fun `canonical clean and dirty provenance round trip`() {
    val clean = provenance()
    assertEquals(clean, SavaBuildLocalPublicationProvenance.parse(clean.render()))

    val dirty = provenance(
      state = SavaBuildLocalPublicationProvenance.GitState.DIRTY,
      statusSha256 = "3".repeat(64),
    )
    assertEquals(dirty, SavaBuildLocalPublicationProvenance.parse(dirty.render()))
  }

  @Test
  fun `description labels source identity as the snapshot at publication`() {
    assertEquals(
      "source snapshot at publication: commit ${"1".repeat(40)}; " +
        "tree ${"2".repeat(40)}; clean worktree; source-state SHA-256 ${"5".repeat(64)}",
      provenance().describeSourceSnapshotAtPublication(),
    )
  }

  @Test
  fun `parser refuses reordered additional and noncanonical rows`() {
    val canonical = provenance().render()
    val lines = canonical.lines()

    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        listOf(lines[1], lines[0]).plus(lines.drop(2)).joinToString("\n")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(canonical + "extra\trow\n")
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(canonical.removeSuffix("\n"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(canonical.replace("\n", "\r\n"))
    }
  }

  @Test
  fun `parser refuses contradictory Git state and invalid source identities`() {
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        provenance().render().replace("gitState\tclean", "gitState\tdirty")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        provenance().render().replace("gitCommit\t${"1".repeat(40)}", "gitCommit\tshort")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        provenance().render().replace("publishedAtUtc\t2026-09-02T12:34:56Z", "publishedAtUtc\t2026-09-02")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        provenance().render().replace("jarSha256\t${"4".repeat(64)}", "jarSha256\tnot-a-digest")
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      SavaBuildLocalPublicationProvenance.parse(
        provenance().render().replace(
          "sourceStateSha256\t${"5".repeat(64)}",
          "sourceStateSha256\tnot-a-digest",
        )
      )
    }
  }

  @Test
  fun `parser accepts Git SHA-256 object identities`() {
    val provenance = provenance(
      gitCommit = "a".repeat(64),
      gitTree = "b".repeat(64),
    )

    assertEquals(provenance, SavaBuildLocalPublicationProvenance.parse(provenance.render()))
  }

  private fun provenance(
    state: SavaBuildLocalPublicationProvenance.GitState =
      SavaBuildLocalPublicationProvenance.GitState.CLEAN,
    statusSha256: String = PitestEvidence.sha256(byteArrayOf()),
    gitCommit: String = "1".repeat(40),
    gitTree: String = "2".repeat(40),
  ) = SavaBuildLocalPublicationProvenance(
    gitState = state,
    gitCommit = gitCommit,
    gitTree = gitTree,
    gitStatusSha256 = statusSha256,
    sourceStateSha256 = "5".repeat(64),
    publishedAtUtc = Instant.parse("2026-09-02T12:34:56Z"),
    jarSha256 = "4".repeat(64),
  )
}
