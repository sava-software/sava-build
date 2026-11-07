import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import software.sava.build.jmh.JmhPluginRequirement
import software.sava.build.jmh.JmhToolDefaults

/**
 * The version floor's order: the JDK's module version order, so a '-' or '+' suffix of the floor
 * is below it, a longer dotted sequence above the version it extends, and a string that is not a
 * version has no verdict (and so passes, with the guard's warning). The literals here order two
 * fixed versions, so a floor bump in the catalog moves no assertion.
 */
class JmhPluginRequirementTest {

  private fun order(left: String, right: String): Int? = JmhPluginRequirement.compare(left, right)?.let(Integer::signum)

  @Test
  fun `releases order numerically`() {
    assertEquals(0, order("0.7.4", "0.7.4"))
    assertEquals(-1, order("0.7.3", "0.7.4"))
    assertEquals(1, order("0.7.10", "0.7.4"))
    assertEquals(1, order("0.8", "0.7.4"))
    assertEquals(0, order("0.8", "0.8.0"))
    assertEquals(1, order("1.0.0", "0.7.4"))
  }

  @Test
  fun `a suffix orders below the version it follows and a longer sequence above`() {
    assertEquals(-1, order("0.7.4-rc1", "0.7.4"))
    assertEquals(-1, order("0.7.4-SNAPSHOT", "0.7.4"))
    assertEquals(-1, order("0.7.4+1", "0.7.4"))
    assertEquals(1, order("0.7.4.20261107093000", "0.7.4"))
  }

  @Test
  fun `a string that is not a version has no verdict`() {
    assertNull(order("unknown", "0.7.4"))
    assertNull(order("", "0.7.4"))
    assertNull(order("0.7.4", "unknown"))
  }

  @Test
  fun `the floor, the declaration and the dependency are the compiled-against version`() {
    assertEquals(0, JmhPluginRequirement.compareToCompiledAgainst(JmhToolDefaults.JMH_GRADLE_PLUGIN))
    assertEquals(-1, JmhPluginRequirement.compareToCompiledAgainst("${JmhToolDefaults.JMH_GRADLE_PLUGIN}-rc1")?.let(Integer::signum))
    assertEquals("id(\"me.champeau.jmh\") version \"${JmhToolDefaults.JMH_GRADLE_PLUGIN}\" apply false", JmhPluginRequirement.declaration())
    assertEquals("me.champeau.jmh:jmh-gradle-plugin:${JmhToolDefaults.JMH_GRADLE_PLUGIN}", JmhPluginRequirement.dependency())
  }
}
