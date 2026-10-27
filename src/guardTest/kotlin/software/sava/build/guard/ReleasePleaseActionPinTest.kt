package software.sava.build.guard

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The port follows the release-please that release-please-action bundles at the pinned
 * commit: release-please 17.6.0 with @conventional-commits/parser 0.4.1. A bump of the
 * action is taken only with a re-recorded corpus and a re-checked port, then this pin.
 */
class ReleasePleaseActionPinTest {

  @Test
  fun `release-please-action is pinned at the commit the port was recorded from`() {
    val workflow = Files.readString(guardFile(".github/workflows/release-please.yml"))
    assertTrue(workflow.contains("googleapis/release-please-action@45996ed1f6d02564a971a2fa1b5860e934307cf7"),
      "release-please-action moved: re-record parser-corpus.json from the release-please it bundles, re-check " +
        "ConventionalCommits and ReleasePullRequests against that version, then update this pin")
  }
}
