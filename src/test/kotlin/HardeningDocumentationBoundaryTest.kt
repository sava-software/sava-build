import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HardeningDocumentationBoundaryTest {

  private val projectRoot = File(savaBuildTestProperty("savaBuild.root"))
  private val readme = projectRoot.resolve("README.md").readText()
  private val hardening = projectRoot.resolve("HARDENING.md").readText()

  @Test
  fun `release review mechanics live in the sava-build README only`() {
    assertTrue(readme.contains("tools/release-attestation.sh create-reviewed"))
    assertTrue(readme.contains("optional diagnostic, not a tag or publication prerequisite"))
    assertFalse(readme.contains("tools/fleet-canary.sh --release"))
    assertFalse(readme.contains("tools/local-fuzz.sh --release --seconds"))
    assertFalse(hardening.contains("tools/fleet-canary.sh --release"))
    assertFalse(hardening.contains("tools/local-fuzz.sh --release --seconds"))
    assertTrue(
      hardening.contains("README.md#local-adoption-and-release-attestation"),
      "portable policy must point release owners to the one operational contract",
    )
  }

  @Test
  fun `installed plugin owns mechanics and consumer docs own local evidence`() {
    assertTrue(hardening.contains("run `./gradlew hardeningHelp` against the version in use"))
    assertTrue(
      hardening.contains("Consumer hardening notes contain only local ownership") &&
          hardening.contains("AGENTS.md` may carry this exact generated"),
      "the generated agent template must distinguish its pinned AGENTS copy from local notes",
    )
    val featureRow = readme.lineSequence()
      .single { it.startsWith("| `software.sava.build.feature.hardening`") }
    assertTrue(featureRow.contains("hardeningHelp"))
    assertFalse(featureRow.contains("-PupdateMutationBaseline"))
    assertFalse(featureRow.contains("configuration cache"))
  }
}
