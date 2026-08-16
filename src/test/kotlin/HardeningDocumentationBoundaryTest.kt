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
    assertFalse(readme.contains("tools/local-fuzz.sh --release --seconds"))
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
          hardening.contains("AGENTS.md` carries this exact generated") &&
          hardening.contains("repository-specific facts outside its bounded block") &&
          hardening.contains("./gradlew :module:hardeningAgentTemplate") &&
          hardening.contains("unqualified task name can select every hardening project"),
      "the generated agent template must distinguish its pinned AGENTS copy from local notes",
    )
    val featureRow = readme.lineSequence()
      .single { it.startsWith("| `software.sava.build.feature.hardening`") }
    assertTrue(featureRow.contains("hardeningHelp"))
    assertFalse(featureRow.contains("-PupdateMutationBaseline"))
    assertFalse(featureRow.contains("configuration cache"))
  }

  @Test
  fun `local validation distinguishes dependency refresh from cache reuse`() {
    val compactReadme = readme.replace(Regex("\\s+"), " ")
    assertTrue(
      compactReadme.contains(
        "treat that invocation as a transport refresh, not a configuration-cache reuse probe"
      ) && compactReadme.contains("repeat the same task graph without it twice") &&
          compactReadme.contains("second no-refresh run must report"),
      "local validation must not ask one --refresh-dependencies invocation to prove cache reuse",
    )
  }

  @Test
  fun `licensed diagnostic explains PITs ArcMutate promotion flag`() {
    val compactHardening = hardening.replace(Regex("\\s+"), " ")
    assertTrue(
      compactHardening.contains("captured toolchain is its activation identity") &&
          compactHardening.contains("audited default PIT 1.25.9") &&
          compactHardening.contains("`arcmutateMissing` value controls only the HTML promotion"),
      "diagnostic doctrine must distinguish PIT's promotion flag from validated tool identity",
    )
  }
}
