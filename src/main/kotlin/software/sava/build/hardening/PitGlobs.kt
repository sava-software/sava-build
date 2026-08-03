package software.sava.build.hardening

/**
 * The one PIT-glob grammar, shared by every scanner that filters class names by a
 * suite's globs: `*` spans package separators, `?` is one character, and `**.` is
 * PIT's zero-or-more-packages marker — `**.Foo` matches `Foo` and `a.b.Foo`, which
 * plain `*`-expansion cannot express (it would demand at least one character before
 * the dot). Two private copies of this parsing drifted: one grew the `**.` handling
 * its scan documented as necessary while the other silently kept the plain
 * expansion, so the same glob selected different classes depending on which
 * advisory read it.
 */
internal object PitGlobs {

  fun toRegex(glob: String): Regex {
    val pattern = glob.split("**.").joinToString("(.*\\.)?") { part ->
      buildString {
        part.forEach { c ->
          when (c) {
            '*' -> append(".*")
            '?' -> append('.')
            else -> append(Regex.escape(c.toString()))
          }
        }
      }
    }
    return Regex(pattern)
  }
}
