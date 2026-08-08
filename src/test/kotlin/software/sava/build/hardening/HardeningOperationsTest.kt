package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HardeningOperationsTest {

  @Test
  fun `the central option inventory separates active and removed properties`() {
    assertEquals(11, HardeningOptionNames.descriptors.size)
    assertEquals(
      setOf(
        HardeningOptionNames.ADOPT_LOCAL_CORPUS,
        HardeningOptionNames.ISOLATE_MUTANTS,
        HardeningOptionNames.LIST_UNKILLED,
        HardeningOptionNames.MAX_FUZZ_TIME,
        HardeningOptionNames.MAX_PARALLEL_FUZZ_TARGETS,
        HardeningOptionNames.MUTATE_ONLY,
        HardeningOptionNames.NO_MUTATION_HISTORY,
        HardeningOptionNames.PITEST_MODE,
        HardeningOptionNames.SAVA_BUILD_LOCAL_REPO,
        HardeningOptionNames.STRICT_TIMEOUT_AUDIT,
        HardeningOptionNames.TRIAL_MUTATORS,
      ),
      HardeningOptionNames.descriptors.map { it.name }.toSet(),
    )
    assertEquals(
      mapOf(
        HardeningOptionNames.UPDATE_MUTATION_BASELINE to "pitest<Suite>BaselineUpdate",
        HardeningOptionNames.UNION_MUTATION_BASELINE to "pitest<Suite>BaselineUnion",
        HardeningOptionNames.PRUNE_MUTATION_BASELINE to "pitest<Suite>BaselinePrune",
        HardeningOptionNames.INIT_TIMEOUT_AUDIT to "pitest<Suite>TimeoutAuditInit",
        HardeningOptionNames.UNION_MODE_FLIPS to "pitestModeCompareUnion",
      ),
      HardeningOptionNames.removedWriterTaskByProperty,
    )
  }

  @Test
  fun `installed help derives discoverable read-only writer and fuzz task names`() {
    val help = HardeningHelpText.render(
      suiteNames = listOf("encoding"),
      fuzzTargetNames = listOf("wire"),
    )

    assertTrue(help.contains("pitestEncodingDebt"), help)
    assertTrue(help.contains("pitestEncodingBaselineRebase"), help)
    assertTrue(help.contains("pitestEncodingBaselineUpdate"), help)
    assertTrue(
      help.contains("complete report rewrite; may remove unmatched rows"),
      help,
    )
    assertTrue(help.contains("pitestEncodingBaselineUnion"), help)
    assertTrue(help.contains("pitestEncodingBaselineRetag"), help)
    assertTrue(help.contains("pitestEncodingBaselinePrune"), help)
    assertTrue(help.contains("pitestEncodingTimeoutAuditInit"), help)
    assertTrue(help.contains("pitestModeCompareUnion"), help)
    assertTrue(help.contains("mutationOwnershipAudit"), help)
    assertTrue(help.contains("whole-production owner/exclusion preflight"), help)
    assertTrue(help.contains("fuzzWireMinimize"), help)
    assertTrue(help.contains("durable receipt in .pitest-history/"), help)
    assertTrue(help.contains("-PupdateMutationBaseline"), help)
    assertTrue(help.contains("use pitest<Suite>BaselineUpdate"), help)
    assertTrue(help.contains("refused since sava-build 21.5.22"), help)
    assertTrue(help.contains("only supported committed-file write interface"), help)
  }

  @Test
  fun `removed writer message maps every old property to its task`() {
    val message = HardeningOptionNames.removedWriterMessage(
      HardeningOptionNames.removedWriterProperties,
    )

    assertTrue(message.contains("removed in sava-build 21.5.22"), message)
    HardeningOptionNames.removedWriterTaskByProperty.forEach { (property, task) ->
      assertTrue(message.contains("-P$property -> $task"), message)
    }
    assertTrue(message.contains("only supported committed-file write interface"), message)
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
    assertFalse(registry.modeFlipInsuranceRequested(":other"))
    assertTrue(registry.hasStateChangingOperation(":"))
    assertEquals(
      listOf("mode-compare:union-flips"),
      registry.descriptions(":"),
    )
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
