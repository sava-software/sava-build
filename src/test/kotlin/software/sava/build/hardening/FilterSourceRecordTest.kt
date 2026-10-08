package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FilterSourceRecordTest {

  @TempDir
  lateinit var projectDir: File

  private fun file(path: String, text: String): File =
    File(projectDir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

  @Test
  fun `record round trips and refuses anything but its exact shape`() {
    val record = FilterSourceRecord("run-1", "b".repeat(64))
    val rendered = record.render()

    assertEquals(record, FilterSourceRecord.parse(rendered))
    assertEquals("schema\t1\ninvocation\trun-1\nfilterSourceSha256\t${"b".repeat(64)}\n", rendered)
    listOf(
      rendered.replace("schema\t1", "schema\t2"),
      rendered.lineSequence().filterNot { it.startsWith("invocation") }.joinToString("\n"),
      rendered + "extra\tfield\n",
      rendered.replace("b".repeat(64), "not-a-digest"),
      rendered.replace("invocation\trun-1", "invocation\t"),
      rendered.replace("filterSourceSha256", "uncompiledSourceSha256"),
    ).forEach { text ->
      assertThrows(IllegalArgumentException::class.java, { FilterSourceRecord.parse(text) }, text)
    }
  }

  @Test
  fun `fingerprint covers the evidence sources under the roots and nothing else`() {
    val main = file("src/main/java/p/A.java", "package p; class A {}")
    val moduleInfo = file("src/main/java/module-info.java", "module p {}")
    val test = file("src/test/java/p/ATest.java", "package p; class ATest {}")
    val resource = file("src/main/resources/p/cases.json", "{}")
    val roots = listOf(File(projectDir, "src/main/java"))
    val sources = listOf(main, moduleInfo, test, resource)

    val before = FilterSourceRecord.fingerprint(projectDir, roots, sources)

    test.writeText("package p; class ATest { // a test comment\n}")
    assertEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources),
      "a test source is outside the roots the filter reads")
    resource.writeText("{\"changed\": true}")
    assertEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources),
      "a resource is outside the roots too; the uncompiled-source record covers it")
    file("src/main/java/.DS_Store", "finder noise")
    assertEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources),
      "a file under the roots that the source set excludes is not an evidence source")
    main.writeText("package p; class A {} // a comment")
    assertNotEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources),
      "text under the roots moves it, compiled or not")
    main.writeText("package p; class A {}")
    moduleInfo.writeText("module p { requires java.logging; }")
    assertNotEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources),
      "module-info sits under the roots")
    moduleInfo.writeText("module p {}")
    assertEquals(before, FilterSourceRecord.fingerprint(projectDir, roots, sources), "restored")
    assertEquals(
      FilterSourceRecord.fingerprint(projectDir, listOf(File(projectDir, "src/absent/java")), sources),
      PitestEvidence.fingerprint(projectDir, emptyList()),
      "a root with no member records the empty fingerprint",
    )
  }
}
