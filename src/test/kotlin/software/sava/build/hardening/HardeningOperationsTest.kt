package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardeningOperationsTest {

  @Test
  fun `the central option inventory names all legacy properties once`() {
    assertEquals(14, HardeningOptionNames.descriptors.size)
    assertEquals(
      setOf(
        HardeningOptionNames.ADOPT_LOCAL_CORPUS,
        HardeningOptionNames.INIT_TIMEOUT_AUDIT,
        HardeningOptionNames.LIST_UNKILLED,
        HardeningOptionNames.MAX_FUZZ_TIME,
        HardeningOptionNames.MUTATE_ONLY,
        HardeningOptionNames.NO_MUTATION_HISTORY,
        HardeningOptionNames.PITEST_MODE,
        HardeningOptionNames.PRUNE_MUTATION_BASELINE,
        HardeningOptionNames.SAVA_BUILD_LOCAL_REPO,
        HardeningOptionNames.STRICT_TIMEOUT_AUDIT,
        HardeningOptionNames.TRIAL_MUTATORS,
        HardeningOptionNames.UNION_MODE_FLIPS,
        HardeningOptionNames.UNION_MUTATION_BASELINE,
        HardeningOptionNames.UPDATE_MUTATION_BASELINE,
      ),
      HardeningOptionNames.descriptors.map { it.name }.toSet(),
    )
    assertEquals(
      5,
      HardeningOptionNames.descriptors.count { it.discoverableTask != null },
    )
  }

  @Test
  fun `installed help derives discoverable writer and fuzz task names`() {
    val help = HardeningHelpText.render(
      suiteNames = listOf("encoding"),
      fuzzTargetNames = listOf("wire"),
    )

    assertTrue(help.contains("pitestEncodingBaselineUpdate"), help)
    assertTrue(help.contains("pitestEncodingBaselineUnion"), help)
    assertTrue(help.contains("pitestEncodingBaselinePrune"), help)
    assertTrue(help.contains("pitestEncodingTimeoutAuditInit"), help)
    assertTrue(help.contains("pitestModeCompareUnion"), help)
    assertTrue(help.contains("fuzzWireMinimize"), help)
    assertTrue(help.contains("-PupdateMutationBaseline"), help)
    assertTrue(help.contains("compatibility alias for pitest<Suite>BaselineUpdate"), help)
    assertTrue(help.contains("-Pflag=false` is still present"), help)
  }

  @Test
  fun `legacy writer properties select exactly one operation`() {
    assertEquals(
      BaselineWriteOperation.CHECK,
      BaselineWriteOperation.fromLegacyProperties(emptySet()),
    )
    BaselineWriteOperation.entries.filter { it.legacyProperty != null }.forEach { operation ->
      assertEquals(
        operation,
        BaselineWriteOperation.fromLegacyProperties(setOf(operation.legacyProperty!!)),
      )
    }

    val failure = assertThrows(IllegalArgumentException::class.java) {
      BaselineWriteOperation.fromLegacyProperties(
        setOf(
          HardeningOptionNames.UPDATE_MUTATION_BASELINE,
          HardeningOptionNames.PRUNE_MUTATION_BASELINE,
        )
      )
    }
    assertTrue(failure.message!!.contains("pass at most one"), failure.message)
  }

  @Test
  fun `legacy suite and mode writer families are centrally mutually exclusive`() {
    val none = LegacyWriteSelection.fromProperties(emptySet())
    assertEquals(BaselineWriteOperation.CHECK, none.suiteOperation)
    assertFalse(none.modeFlipInsurance)
    assertFalse(none.hasStateChangingOperation)
    val modeOnly =
        LegacyWriteSelection.fromProperties(setOf(HardeningOptionNames.UNION_MODE_FLIPS))
    assertEquals(BaselineWriteOperation.CHECK, modeOnly.suiteOperation)
    assertTrue(modeOnly.modeFlipInsurance)
    assertTrue(modeOnly.hasStateChangingOperation)
    BaselineWriteOperation.entries.filter { it.legacyProperty != null }.forEach { operation ->
      val suiteOnly = LegacyWriteSelection.fromProperties(setOf(operation.legacyProperty!!))
      assertEquals(operation, suiteOnly.suiteOperation)
      assertFalse(suiteOnly.modeFlipInsurance)
      assertTrue(suiteOnly.hasStateChangingOperation)
      val failure = assertThrows(IllegalArgumentException::class.java) {
        LegacyWriteSelection.fromProperties(
          setOf(operation.legacyProperty!!, HardeningOptionNames.UNION_MODE_FLIPS)
        )
      }
      assertTrue(
        failure.message!!.contains("both are state-changing baseline workflows"),
        failure.message,
      )
    }
  }

  @Test
  fun `suite writer requests are scoped and idempotent but conflicting modes fail`() {
    val registry = HardeningOperationRegistry()

    registry.requestSuite(":a", "encoding", BaselineWriteOperation.PRUNE)
    registry.requestSuite(":a", "encoding", BaselineWriteOperation.PRUNE)
    registry.requestSuite(":b", "encoding", BaselineWriteOperation.UPDATE)

    assertEquals(BaselineWriteOperation.PRUNE, registry.suiteOperation(":a", "encoding"))
    assertEquals(BaselineWriteOperation.UPDATE, registry.suiteOperation(":b", "encoding"))
    assertEquals(BaselineWriteOperation.CHECK, registry.suiteOperation(":a", "parsing"))
    assertEquals(
      BaselineWriteOperation.UNION,
      registry.resolveSuiteOperation(
        ":a",
        "parsing",
        setOf(HardeningOptionNames.UNION_MUTATION_BASELINE),
      ),
    )
    assertEquals(
      BaselineWriteOperation.PRUNE,
      registry.resolveSuiteOperation(":a", "encoding", emptySet()),
    )
    assertThrows(IllegalArgumentException::class.java) {
      registry.resolveSuiteOperation(
        ":a",
        "encoding",
        setOf(HardeningOptionNames.UPDATE_MUTATION_BASELINE),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      registry.requestSuite(":a", "encoding", BaselineWriteOperation.UNION)
    }
    val poisoned = assertThrows(IllegalArgumentException::class.java) {
      registry.suiteOperation(":a", "encoding")
    }
    assertTrue(poisoned.message!!.contains("poisoned"), poisoned.message)
  }

  @Test
  fun `project writers are exclusive with suite writers and expose their intent`() {
    val registry = HardeningOperationRegistry()
    assertFalse(registry.hasStateChangingOperation(":"))

    registry.requestModeFlipInsurance(":")

    assertTrue(registry.modeFlipInsuranceRequested(":"))
    assertTrue(
      registry.resolveModeFlipInsurance(
        ":other", setOf(HardeningOptionNames.UNION_MODE_FLIPS))
    )
    assertTrue(registry.hasStateChangingOperation(":"))
    assertEquals(
      listOf("mode-compare:union-flips"),
      registry.descriptions(":"),
    )
    assertThrows(IllegalArgumentException::class.java) {
      registry.resolveModeFlipInsurance(":", setOf(HardeningOptionNames.UNION_MODE_FLIPS))
    }
    assertThrows(IllegalArgumentException::class.java) {
      registry.requestSuite(":", "encoding", BaselineWriteOperation.INIT_TIMEOUT_AUDIT)
    }

    val reverse = HardeningOperationRegistry()
    reverse.requestSuite(":", "encoding", BaselineWriteOperation.INIT_TIMEOUT_AUDIT)
    assertThrows(IllegalArgumentException::class.java) {
      reverse.requestProject(":", ProjectWriteOperation.SCHEMA_MIGRATE)
    }
  }

  @Test
  fun `schema migration and downgrade requests are idempotent but mutually exclusive`() {
    val registry = HardeningOperationRegistry()

    registry.requestProject(":", ProjectWriteOperation.SCHEMA_MIGRATE)
    registry.requestProject(":", ProjectWriteOperation.SCHEMA_MIGRATE)
    registry.requireProjectOperation(":", ProjectWriteOperation.SCHEMA_MIGRATE)
    assertEquals(ProjectWriteOperation.SCHEMA_MIGRATE, registry.projectOperation(":"))
    assertEquals(listOf("schema:migrate"), registry.descriptions(":"))

    assertThrows(IllegalArgumentException::class.java) {
      registry.requestProject(":", ProjectWriteOperation.SCHEMA_DOWNGRADE)
    }
    assertThrows(IllegalArgumentException::class.java) {
      registry.requireProjectOperation(":", ProjectWriteOperation.SCHEMA_MIGRATE)
    }
  }
}
