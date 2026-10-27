package software.sava.build.guard

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Java floor release-floor.sh refuses below, as the script spells it on a line of its own, read
 * once here for every test that holds something to it: check.sh's --release, the versions the
 * self-test workflow passes the root action, the versions the floor test tries.
 */
fun releaseFloor(): Int = Regex("""^floor=(\d+)$""", RegexOption.MULTILINE).find(Files.readString(Path.of(".github/actions/check-jdk-trust/release-floor.sh")))
  ?.groupValues?.get(1)?.toInt() ?: error("release-floor.sh names no floor on a line of its own")
