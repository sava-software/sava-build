import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.sava.build.jmh.JmhPluginRequirement
import software.sava.build.jmh.JmhToolDefaults

/**
 * The version floor's order: the JDK's module version order, so a '-' or '+' suffix of the floor
 * is below it, a longer dotted sequence above the version it extends, and a string that is not a
 * version has no verdict (and so passes, with the guard's warning, when the plugin has 0.7.4's
 * --jmhArgs). The literals here order two fixed versions, so a floor bump in the catalog moves
 * no assertion.
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

  private val floor = JmhToolDefaults.JMH_GRADLE_PLUGIN

  @Test
  fun `the verdict refuses an older plugin, warns of a newer one and passes the compiled-against version in silence`() {
    assertEquals(JmhPluginRequirement.Verdict(), JmhPluginRequirement.verdict(floor, hasJmhArgs = true))
    val older = JmhPluginRequirement.verdict("$floor-rc1", hasJmhArgs = true)
    assertNull(older.warning)
    assertTrue(older.refusal!!.contains("the plugin on its classpath is $floor-rc1, which the feature refuses"), older.refusal)
    assertTrue(older.refusal!!.contains(JmhPluginRequirement.declaration()), older.refusal)
    assertTrue(older.refusal!!.contains(JmhPluginRequirement.dependency()), older.refusal)
    val newer = JmhPluginRequirement.verdict("$floor.1", hasJmhArgs = true)
    assertNull(newer.refusal)
    assertTrue(newer.warning!!.contains("the plugin on its classpath is $floor.1, newer"), newer.warning)
    assertTrue(newer.warning!!.contains("tested against that version alone"), newer.warning)
    assertTrue(newer.warning!!.contains(JmhPluginRequirement.dependency()), newer.warning)
  }

  @Test
  fun `a task without the --jmhArgs property is refused whatever the version, and an unreadable version passes with a warning when it has it`() {
    val unreadable = JmhPluginRequirement.verdict(null, hasJmhArgs = true)
    assertNull(unreadable.refusal)
    assertTrue(unreadable.warning!!.contains("(no Implementation-Version in its manifest)"), unreadable.warning)
    assertTrue(unreadable.warning!!.contains("has the --jmhArgs property as $floor has it, so it passes"), unreadable.warning)
    val garbage = JmhPluginRequirement.verdict("unspecified", hasJmhArgs = true)
    assertNull(garbage.refusal)
    assertTrue(garbage.warning!!.contains("('unspecified' is not a version)"), garbage.warning)
    for (version in listOf(null, "unspecified", floor, "$floor.1")) {
      val lacking = JmhPluginRequirement.verdict(version, hasJmhArgs = false)
      assertNull(lacking.warning, version)
      assertTrue(lacking.refusal!!.contains("is without the --jmhArgs property as $floor has it"), lacking.refusal)
      assertTrue(lacking.refusal!!.contains(if (version == null) "(no Implementation-Version in its manifest)" else "(version $version)"), lacking.refusal)
      assertTrue(lacking.refusal!!.contains(JmhPluginRequirement.dependency()), lacking.refusal)
    }
    // Older than the floor is refused as older, whatever the property.
    val older = JmhPluginRequirement.verdict("$floor-rc1", hasJmhArgs = false)
    assertTrue(older.refusal!!.contains("which the feature refuses"), older.refusal)
  }
}
