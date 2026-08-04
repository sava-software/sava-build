package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommittedMutationProvenanceTest {

  private fun toolchain(pit: String = "1.25.9") = MutationToolchainRecord(
    pitestVersion = pit,
    junitPluginVersion = "1.2.3",
    toolClasspathSha256 = "a".repeat(64),
    arcMutateBaseVersion = null,
    arcMutateLicenceSha256 = null,
    arcMutateLicenceExpires = null,
  ).render()

  @Test
  fun `paired provenance classifies bound legacy torn orphan and disagreement`() {
    val bound = CommittedMutationProvenance.classify(true, "1.25.9\n", toolchain())
    assertEquals("1.25.9", bound.pitVersion)
    assertNotNull(bound.toolchain)
    assertFalse(bound.torn)
    assertFalse(bound.orphan)
    assertFalse(bound.legacyUnbound)
    assertFalse(bound.disagreement)

    assertTrue(CommittedMutationProvenance.classify(true, null, null).legacyUnbound)
    assertTrue(CommittedMutationProvenance.classify(true, "1.25.9\n", null).torn)
    assertTrue(CommittedMutationProvenance.classify(false, "1.25.9\n", toolchain()).orphan)
    assertTrue(
      CommittedMutationProvenance.classify(true, "1.25.8\n", toolchain()).disagreement)
  }

  @Test
  fun `PIT version stamp is exactly one normalized nonblank line`() {
    listOf("", "\n", " 1.25.9\n", "1.25.9 \n", "1.25.9\r\n", "1.25.9\n\n")
      .forEach { malformed ->
        val classified =
          CommittedMutationProvenance.classify(true, malformed, toolchain())
        assertNull(classified.pitVersion, "accepted malformed stamp ${malformed.toByteArray().contentToString()}")
        assertNotNull(classified.malformedPitVersion)
      }

    assertEquals(
      "1.25.9",
      CommittedMutationProvenance.classify(true, "1.25.9", toolchain()).pitVersion,
    )
    assertEquals(
      "1.25.9",
      CommittedMutationProvenance.classify(true, "1.25.9\n", toolchain()).pitVersion,
    )
  }

  @Test
  fun `malformed toolchain remains present for torn and orphan arithmetic`() {
    val malformed = CommittedMutationProvenance.classify(true, "1.25.9\n", "broken\n")
    assertNotNull(malformed.malformedToolchain)
    assertTrue(malformed.toolchainFilePresent)
    assertFalse(malformed.torn)
  }
}
