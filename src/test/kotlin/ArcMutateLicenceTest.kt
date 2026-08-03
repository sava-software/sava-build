import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

class ArcMutateLicenceTest {

  @Test
  fun `committed OSS certificate stays scoped and contains no private download URL`() {
    val certificate = File(savaBuildTestProperty("savaBuild.root"), "arcmutate-licence.txt")
    assertTrue(certificate.isFile, "the repository-root ArcMutate certificate must be committed")

    val text = certificate.readText()
    assertFalse(
      text.contains("subscriptions.arcmutate.com", ignoreCase = true),
      "the private subscription download URL must never accompany the public certificate",
    )

    val fields = Properties().apply { text.reader().use { load(it) } }
    assertEquals("software.sava.*", fields.getProperty("packages"))
    assertEquals("OSSS", fields.getProperty("type"))
    assertTrue(fields.getProperty("signature").isNullOrBlank().not(), "certificate signature is missing")
    assertTrue(fields.getProperty("expires").isNullOrBlank().not(), "certificate expiry is missing")
  }
}
