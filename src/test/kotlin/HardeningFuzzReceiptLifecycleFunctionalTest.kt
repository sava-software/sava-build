import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Pins the machine-local fuzz aggregate's lifecycle independently of Jazzer execution. */
class HardeningFuzzReceiptLifecycleFunctionalTest {

  @TempDir
  lateinit var fixtureDir: File

  @TempDir
  lateinit var externalBuildDir: File

  private val receipt get() = File(fixtureDir, ".pitest-history/local-fuzz.tsv")
  private val running get() = File(fixtureDir, ".pitest-history/local-fuzz.running")
  private val legacyReceipt get() = File(fixtureDir, "build/hardening/local-fuzz.tsv")
  private val legacyRunning get() = File(fixtureDir, "build/hardening/local-fuzz.running")

  @BeforeEach
  fun writeFixture() {
    enableTestKitConfigurationCache(fixtureDir)
    File(fixtureDir, "settings.gradle.kts").writeText(
      """
        $savaBuildPluginManagement

        rootProject.name = "hardening-fuzz-receipt-lifecycle-test"
      """.trimIndent() + "\n"
    )
    File(fixtureDir, "build.gradle.kts").writeText(
      """
        plugins {
          java
          id("software.sava.build.feature.hardening")
        }
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
    .withProjectDir(fixtureDir)
    .withArguments(*args, "--configuration-cache", "--stacktrace")

  @Test
  fun `successful aggregate survives clean without becoming a configuration input`() {
    val first = runner("fuzzAll", "-PmaxFuzzTime=1").build()
    assertTrue(first.output.contains("Configuration cache entry stored."), first.output)
    assertTrue(receipt.isFile, "fuzzAll did not publish durable campaign evidence")
    assertFalse(running.exists(), "successful fuzzAll retained its running sentinel")

    val second = runner("fuzzAll", "-PmaxFuzzTime=1").build()
    assertTrue(second.output.contains("Configuration cache entry reused."), second.output)
    assertTrue(receipt.isFile, "reused fuzzAll graph did not replace its receipt")
    assertFalse(running.exists(), "reused successful campaign retained its sentinel")
    val completedReceipt = receipt.readBytes()

    runner("clean").build()

    assertTrue(receipt.isFile, "clean erased a completed local fuzz campaign")
    assertArrayEquals(completedReceipt, receipt.readBytes(), "clean changed durable fuzz evidence")
    assertFalse(running.exists(), "clean manufactured an in-progress campaign")
  }

  @Test
  fun `new campaign invalidates durable and legacy success before later failure`() {
    runner("fuzzAll", "-PmaxFuzzTime=1").build()
    legacyReceipt.apply {
      parentFile.mkdirs()
      writeText("legacy success\n")
    }
    legacyRunning.writeText("legacy interrupted campaign\n")

    val failed = runner("fuzzAll", "-PmaxFuzzTime=0").buildAndFail()

    assertTrue(failed.output.contains("0 is libFuzzer's run-forever sentinel"), failed.output)
    assertFalse(receipt.exists(), "failed replacement campaign retained the prior durable success")
    assertFalse(legacyReceipt.exists(), "failed replacement campaign retained legacy success")
    assertFalse(legacyRunning.exists(), "new preflight retained the superseded legacy sentinel")
    assertTrue(running.isFile, "failed replacement campaign did not remain visibly in progress")

    runner("fuzzAll", "-PmaxFuzzTime=1").build()
    assertTrue(receipt.isFile, "successful retry did not publish replacement evidence")
    assertFalse(running.exists(), "successful retry did not clear its own sentinel")
  }

  @Test
  fun `legacy cleanup permits an external build directory and still fails closed`() {
    File(fixtureDir, "build.gradle.kts").appendText(
      "\nlayout.buildDirectory = file(\"${externalBuildDir.invariantSeparatorsPath}\")\n"
    )
    val externalLegacyReceipt = externalBuildDir.resolve("hardening/local-fuzz.tsv")
    val externalLegacyRunning = externalBuildDir.resolve("hardening/local-fuzz.running")
    externalLegacyReceipt.apply {
      parentFile.mkdirs()
      writeText("legacy success outside the project\n")
    }
    externalLegacyRunning.writeText("legacy interruption outside the project\n")

    runner("fuzzAll", "-PmaxFuzzTime=1").build()

    assertTrue(receipt.isFile, "external legacy cleanup prevented a new durable receipt")
    assertFalse(externalLegacyReceipt.exists(), "external legacy receipt survived replacement")
    assertFalse(externalLegacyRunning.exists(), "external legacy sentinel survived replacement")

    externalLegacyReceipt.mkdirs()
    val failed = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()

    assertTrue(failed.output.contains("is not a regular file"), failed.output)
    assertFalse(receipt.exists(), "invalid legacy state left the prior durable success eligible")
    assertTrue(running.isFile, "invalid legacy state did not leave the new attempt in progress")
  }

  @Test
  fun `a non-owner finalizer cannot delete another campaigns receipt`() {
    receipt.parentFile.mkdirs()
    val ownerReceipt = "receipt published by the owning process\n".toByteArray()
    receipt.writeBytes(ownerReceipt)
    val lockFile = receipt.parentFile.resolve("local-fuzz.lock")

    FileChannel.open(
      lockFile.toPath(),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use {
        val rejected = runner("fuzzAll", "-PmaxFuzzTime=1").buildAndFail()
        assertTrue(rejected.output.contains("another fuzzAll campaign owns"), rejected.output)
      }
    }
    assertArrayEquals(
      ownerReceipt,
      receipt.readBytes(),
      "a rejected overlapping campaign deleted the owner's published receipt",
    )
    assertFalse(running.exists(), "a rejected non-owner overwrote the owner's campaign sentinel")

    val directFinalizer = runner("fuzzAllComplete", "-PmaxFuzzTime=1").buildAndFail()
    assertTrue(directFinalizer.output.contains("did not activate a campaign"), directFinalizer.output)
    assertArrayEquals(
      ownerReceipt,
      receipt.readBytes(),
      "a directly selected non-owner completion task deleted durable evidence",
    )
  }
}
