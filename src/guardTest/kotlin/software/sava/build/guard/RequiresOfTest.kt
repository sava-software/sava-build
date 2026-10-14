package software.sava.build.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The requires directives the fork pull request guard compares between two commits. */
class RequiresOfTest {

  @Test
  fun `directives are normalized, sorted, deduplicated, and JDK modules left out`() {
    val source = """
      module a.b {
        requires java.base;
        requires /*runtime*/ org.x.y;
        requires static
          transitive com.foo . bar; // trailing
        requires jdk.httpserver; /* requires fake.one; */
        requires transitive static org.z;
        requires org.z;
        requires //runtime
          org.r;
        exports a.b;
      }
    """.trimIndent()
    assertEquals(listOf("org.z", "runtime org.r", "runtime org.x.y", "static transitive com.foo.bar", "static transitive org.z"),
      RequiresOf.directives(source))
  }

  @Test
  fun `a comment that is not only runtime is a space, and identifiers around requires do not match`() {
    assertEquals(listOf("org.a"), RequiresOf.directives("requires/* note */org.a; xrequires org.b; a.requires org.c; requires\$ org.d;"))
    assertEquals(emptyList<String>(), RequiresOf.directives("module m { exports a; }"))
  }
}
