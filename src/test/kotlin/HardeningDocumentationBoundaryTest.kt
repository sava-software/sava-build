import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class HardeningDocumentationBoundaryTest {

  private val projectRoot = File(savaBuildTestProperty("savaBuild.root"))
  private val readme = projectRoot.resolve("README.md").readText()
  private val hardening = projectRoot.resolve("HARDENING.md").readText()

  @Test
  fun `release mechanics live in the sava-build README only`() {
    assertTrue(readme.contains("Releasing is Release Please plus the ordinary check"))
    assertTrue(readme.contains("optional diagnostic, not a tag or publication prerequisite"))
    assertFalse(readme.contains("tools/local-fuzz.sh --release --seconds"))
    assertFalse(hardening.contains("tools/local-fuzz.sh --release --seconds"))
    // The owner-attestation ceremony was removed deliberately (agents act as the owner,
    // so authorization-shaped gates gated nothing); provenance is GitHub's native
    // actions/attest at publish time. Nothing may reintroduce the ceremony by pointer.
    assertFalse(readme.contains("release-attestation"))
    assertFalse(hardening.contains("release-attestations/"))
    assertTrue(
      hardening.contains("README.md#local-adoption-and-releasing"),
      "portable policy must point release owners to the one operational contract",
    )
  }

  @Test
  fun `installed plugin owns mechanics and consumer docs own local evidence`() {
    assertTrue(
      hardening.contains("run `./gradlew :module:hardeningHelp` against the version") &&
          hardening.contains("`./gradlew :hardeningHelp` when the root project owns the plugin"),
    )
    assertTrue(
      hardening.contains("Consumer hardening notes contain only local ownership") &&
          hardening.contains("AGENTS.md` carries this exact generated") &&
          hardening.contains("repository-specific facts outside its bounded block") &&
          hardening.contains("Local prose may name a project-qualified task") &&
          hardening.contains("descriptions of task output, pass/fail or warning conditions") &&
          hardening.contains("./gradlew :module:hardeningAgentTemplate") &&
          hardening.contains("unqualified task name can select every hardening project"),
      "the generated agent template must distinguish its pinned AGENTS copy from local notes",
    )
    val featureRow = readme.lineSequence()
      .single { it.startsWith("| `software.sava.build.feature.hardening`") }
    assertTrue(
      featureRow.contains("./gradlew :module:hardeningHelp") &&
          featureRow.contains("./gradlew :hardeningHelp"),
    )
    assertFalse(featureRow.contains("-PupdateMutationBaseline"))
    assertFalse(featureRow.contains("configuration cache"))
  }

  @Test
  fun `template upgrade prose routes to installed project-qualified task authority`() {
    val compactReadme = readme.replace(Regex("\\s+"), " ")
    assertTrue(
      compactReadme.contains(
        "agentsTemplateInSync` checks the root `AGENTS.md` acknowledgment of the installed agent-instructions template"
      ) && compactReadme.contains("is used by `check` and `qualityGate`") &&
          compactReadme.contains("./gradlew :module:hardeningHelp") &&
          compactReadme.contains("project-qualified `hardeningAgentTemplate` and `hardeningAgentTemplateDiff`") &&
          compactReadme.contains("one chosen owner reports the installed version's guidance"),
      "template upgrades must defer to one installed, project-qualified task surface",
    )
    assertFalse(readme.contains("`agentsTemplateInSync` fails a consumer"))
    assertFalse(readme.contains("Recomputed per-tag"))
  }

  @Test
  fun `acceptance prose avoids unaudited source-line locators`() {
    val compactHardening = hardening.replace(Regex("\\s+"), " ")
    assertTrue(
      compactHardening.contains(
        "Do not copy source line numbers anywhere in `config/pitest/README.md`"
      ) && compactHardening.contains(
        "acceptance and timeout arguments, retired-incident prose, tables, and inline or fenced coordinate rosters"
      ) && compactHardening.contains(
        "A roster is narrative evidence, not protected membership"
      ) && compactHardening.contains(
        "retain line-less class/method/mutator evidence and meaningful multiplicity as `xN`"
      ) && compactHardening.contains(
        "The current PIT report and the row's `# line` tag are the sole transient"
      ),
      "all README prose, including coordinate rosters, must avoid a second source-line index",
    )
  }

  @Test
  fun `certification retry policy is project-atomic`() {
    val compactHardening = hardening.replace(Regex("\\s+"), " ")
    assertTrue(
      compactHardening.contains("A later clean, history-free, full unscoped run") &&
          compactHardening.contains("sufficient closure for a non-recurring invalid execution") &&
          compactHardening.contains("creates no accepted-baseline, timeout-set, provenance, or mutation-record debt") &&
          compactHardening.contains("all suites in that project intentionally re-run") &&
          compactHardening.contains("Receipts from other projects are independent"),
      "invalid-outcome closure and project-atomic certification retry must be explicit",
    )
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
          compactHardening.contains("audited default PIT 1.30.0") &&
          compactHardening.contains("`arcmutateMissing` value controls only the HTML promotion"),
      "diagnostic doctrine must distinguish PIT's promotion flag from validated tool identity",
    )
  }

  @Test
  fun `ArcMutate 1_7_2 audit keeps opt-in Groovy and assisted history outside fresh evidence`() {
    val compactHardening = hardening.replace(Regex("\\s+"), " ")
    assertTrue(
      compactHardening.contains("Base 1.7.2 also registers Groovy support") &&
          compactHardening.contains("`groovy` feature is off by default") &&
          compactHardening.contains("historic `EQUIVALENT` result") &&
          compactHardening.contains("assisted output remains check-only") &&
          compactHardening.contains("fresh history-free evidence is required"),
      "the audited ArcMutate boundary must distinguish opt-in and assisted-only behavior",
    )
  }
}
