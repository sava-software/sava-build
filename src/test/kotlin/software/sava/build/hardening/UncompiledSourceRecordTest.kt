package software.sava.build.hardening

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class UncompiledSourceRecordTest {

  @TempDir
  lateinit var projectDir: File

  private fun file(path: String, text: String): File =
    File(projectDir, path).also { it.parentFile.mkdirs(); it.writeText(text) }

  @Test
  fun `record round trips and refuses anything but its exact shape`() {
    val record = UncompiledSourceRecord("run-1", "a".repeat(64))
    val rendered = record.render()

    assertEquals(record, UncompiledSourceRecord.parse(rendered))
    assertEquals("schema\t1\ninvocation\trun-1\nuncompiledSourceSha256\t${"a".repeat(64)}\n", rendered)
    listOf(
      rendered.replace("schema\t1", "schema\t2"),
      rendered.lineSequence().filterNot { it.startsWith("invocation") }.joinToString("\n"),
      rendered + "extra\tfield\n",
      rendered.replace("a".repeat(64), "not-a-digest"),
      rendered.replace("invocation\trun-1", "invocation\t"),
    ).forEach { text ->
      assertThrows(IllegalArgumentException::class.java, { UncompiledSourceRecord.parse(text) }, text)
    }
  }

  @Test
  fun `fingerprint covers exactly the evidence sources the recompile does not compile`() {
    val java = file("src/main/java/p/A.java", "package p; class A {}")
    val moduleInfo = file("src/main/java/module-info.java", "module p {}")
    val resource = file("src/main/resources/p/cases.json", "{}")
    val script = file("build.gradle.kts", "plugins { java }")
    val evidence = listOf(File(projectDir, "src/main"), script)
    val recompiled = listOf(java)

    val before = UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled)

    java.writeText("package p; class A { int n; }")
    assertEquals(before, UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled),
      "a compiled source is the recompile's business")
    resource.writeText("{\"changed\": true}")
    val resourceEdit = UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled)
    assertNotEquals(before, resourceEdit, "a resource edit moves it")
    resource.writeText("{}")
    moduleInfo.writeText("module p { requires java.logging; }")
    assertNotEquals(before, UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled),
      "module-info is outside the recompile")
    moduleInfo.writeText("module p {}")
    script.writeText("plugins { java }\n// changed")
    assertNotEquals(before, UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled),
      "a build-script edit moves it")
    script.writeText("plugins { java }")
    val selection = file("config/pitest/keys.csv", "p.A,run,MathMutator,SURVIVED")
    assertNotEquals(before, UncompiledSourceRecord.fingerprint(projectDir, evidence + selection, recompiled),
      "a selection file supplied to one invocation only moves it")
    assertEquals(before, UncompiledSourceRecord.fingerprint(projectDir, evidence, recompiled), "restored")
  }
}
