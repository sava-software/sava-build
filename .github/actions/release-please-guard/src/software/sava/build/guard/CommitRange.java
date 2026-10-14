package software.sava.build.guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The commits release-please reads for the next release: those since the last release's
 * tag, each with the pull request release-please reads beside it, split and parsed as
 * release-please splits and parses them. The base is the tag the manifest version has, in
 * release-please's spelling first; on a release-merge run, before that tag exists, the
 * nearest earlier tag in the configured spelling; failing both, the latest GitHub release;
 * failing that, the last commits, as release-please reads them before a first release.
 */
final class CommitRange {

  /**
   * One part of a commit's message: parsed, or with the parser's message. The pull request
   * is the one release-please reads the commit with, if any; the message is that pull
   * request's commit override when it carries one. A part the guard stopped reading at its
   * own limit is one release-please may read on, so it is never taken for a dropped one.
   */
  record Part(String sha, Long pullRequest, boolean overridden, String header, String text, String error,
              boolean limited, String releaseAs) {
    boolean parsed() {
      return error == null;
    }
  }

  record Excluded(String sha, String header) {
  }

  /** A release pull request's merge commit, which release-please tags and never reads. */
  record Tagged(String sha, long pullRequest) {
  }

  private static final Pattern LINE_END = Pattern.compile("\r\n|\r|\n");
  private static final Pattern NEWLINES = Pattern.compile("\r?\n");
  private static final Pattern JS_SPACES = Pattern.compile(ConventionalCommits.JS_SPACE_CLASS + "+");

  /** What the range was read from, for the summary line; null when there is no base. */
  final String baseText;
  /** The version of the base tag, or null when there is no base or its tag names none. */
  final String baseVersion;
  final int commitCount;
  final List<Part> parts;
  final List<Excluded> excluded;
  final List<Tagged> tagged;

  private CommitRange(String baseText, String baseVersion, int commitCount, List<Part> parts, List<Excluded> excluded,
                      List<Tagged> tagged) {
    this.baseText = baseText;
    this.baseVersion = baseVersion;
    this.commitCount = commitCount;
    this.parts = parts;
    this.excluded = excluded;
    this.tagged = tagged;
  }

  static CommitRange read(Git git, ReleaseConfig config, GitHub github) {
    return read(git, config, github, Map.of());
  }

  /**
   * Reads the range. A commit in releaseMergeCommits (a merged release pull request's merge
   * commit, keyed to its number) is skipped: release-please tags it and reads commits only
   * after the tag, so neither its message nor the release notes in its pull request's body
   * are ever read as a commit.
   */
  static CommitRange read(Git git, ReleaseConfig config, GitHub github, Map<String, Long> releaseMergeCommits) {
    String base = null;
    String baseText = null;
    if (config.version != null) {
      for (final String candidate : config.tagCandidates(config.version)) {
        if (git.tagExists(candidate)) {
          base = candidate;
          baseText = "tag " + candidate + " (manifest version " + config.version + ")";
          break;
        }
      }
      if (base == null) {
        for (final String glob : config.tagGlobs()) {
          final String tag = git.nearestTag(glob);
          if (tag != null) {
            base = tag;
            baseText = "nearest tag " + tag + " (manifest version " + config.version + " has no tag yet)";
            break;
          }
        }
      }
    }
    if (base == null && github != null) {
      final String latest = github.latestReleaseTag();
      if (latest != null && git.tagExists(latest)) {
        base = latest;
        baseText = "latest GitHub release " + latest;
      }
    }
    String baseVersion = null;
    if (base != null) {
      try {
        baseVersion = ReleasePullRequests.parseVersion(base);
      } catch (IllegalArgumentException e) {
        // a tag that names no version
      }
    }
    final List<Git.Commit> commits = git.log(config.commitSearchDepth, base != null ? base + "..HEAD" : "HEAD");
    final Map<String, GitHub.AssociatedPullRequest> pullRequests = github == null ? Map.of()
        : pullRequestsOf(commits, github, config.commitBatchSize);
    final List<Part> parts = new ArrayList<>();
    final List<Excluded> excluded = new ArrayList<>();
    final List<Tagged> tagged = new ArrayList<>();
    for (final Git.Commit commit : commits) {
      final Long releasePullRequest = releaseMergeCommits.get(commit.sha());
      if (releasePullRequest != null) {
        tagged.add(new Tagged(commit.sha(), releasePullRequest));
        continue;
      }
      final GitHub.AssociatedPullRequest pullRequest = pullRequests.get(commit.sha());
      // release-please's preprocessCommitMessage: a BEGIN_COMMIT_OVERRIDE block in the pull
      // request's body replaces the commit message.
      final String override = pullRequest != null ? overrideOf(pullRequest.body()) : "";
      final String message = override.isEmpty() ? commit.message() : override;
      if (config.excludeConfigured && config.excludedByPaths(git.changedFiles(commit.sha()))) {
        excluded.add(new Excluded(commit.sha(), headerOf(message)));
        continue;
      }
      final Long number = pullRequest != null ? pullRequest.number() : null;
      for (final String part : ConventionalCommits.splitMessages(message)) {
        // trimmed, so a nested commit's header is its first line and not the blank line before
        // it. A lone CR ends the header too: the runner ends a log line there, so a header
        // printed whole could put a workflow command at the start of one.
        final String text = ConventionalCommits.jsTrim(part);
        final String header = headerOf(text);
        try {
          final ConventionalCommits.Parsed parsed = ConventionalCommits.parse(text);
          parts.add(new Part(commit.sha(), number, !override.isEmpty(), header, text, null, false, parsed.releaseAs()));
        } catch (ConventionalCommits.LimitException e) {
          parts.add(new Part(commit.sha(), number, !override.isEmpty(), header, text, oneLine(e.getMessage()), true, null));
        } catch (ConventionalCommits.ParseException e) {
          parts.add(new Part(commit.sha(), number, !override.isEmpty(), header, text, oneLine(e.getMessage()), false, null));
        }
      }
    }
    return new CommitRange(baseText, baseVersion, commits.size(), parts, excluded, tagged);
  }

  /**
   * The pull request release-please reads each commit with (its mergeCommitsGraphQL): the one
   * whose merge commit it is, when that pull request is the only one merged there among the
   * commits of the same page of its history (commit-batch-size of them; a squash or a merge
   * commit, or a rebase merge of one commit), else the first pull request the commit belongs
   * to.
   */
  private static Map<String, GitHub.AssociatedPullRequest> pullRequestsOf(List<Git.Commit> commits, GitHub github,
                                                                           int pageSize) {
    final List<String> shas = new ArrayList<>();
    for (final Git.Commit commit : commits) {
      shas.add(commit.sha());
    }
    final Map<String, List<GitHub.AssociatedPullRequest>> associated = github.associatedPullRequests(shas);
    final Map<String, GitHub.AssociatedPullRequest> chosen = new HashMap<>();
    for (int from = 0; from < shas.size(); from += pageSize) {
      final List<String> page = shas.subList(from, Math.min(shas.size(), from + pageSize));
      final Map<String, Integer> mergeCommitCount = new HashMap<>();
      for (final String sha : page) {
        for (final GitHub.AssociatedPullRequest pull : associated.getOrDefault(sha, List.of())) {
          if (pull.mergeCommitOid() != null) {
            mergeCommitCount.merge(pull.mergeCommitOid(), 1, Integer::sum);
          }
        }
      }
      for (final String sha : page) {
        choose(sha, associated.getOrDefault(sha, List.of()), mergeCommitCount, chosen);
      }
    }
    return chosen;
  }

  private static void choose(String sha, List<GitHub.AssociatedPullRequest> pulls, Map<String, Integer> mergeCommitCount,
                             Map<String, GitHub.AssociatedPullRequest> chosen) {
    for (final GitHub.AssociatedPullRequest pull : pulls) {
      if (sha.equals(pull.mergeCommitOid()) && mergeCommitCount.get(sha) == 1) {
        chosen.put(sha, pull);
        return;
      }
    }
    if (!pulls.isEmpty()) {
      chosen.put(sha, pulls.get(0));
    }
  }

  /** release-please's commit override from a pull request body, or "" when it carries none. */
  static String overrideOf(String body) {
    final String[] begun = body.split(Pattern.quote("BEGIN_COMMIT_OVERRIDE"), -1);
    if (begun.length < 2) {
      return "";
    }
    return ConventionalCommits.jsTrim(begun[1].split(Pattern.quote("END_COMMIT_OVERRIDE"), -1)[0]);
  }

  static String headerOf(String message) {
    return LINE_END.split(message, -1)[0];
  }

  /** A message on one line: line ends shown as <LF>, runs of whitespace as one space. */
  static String oneLine(String message) {
    return ConventionalCommits.jsTrim(JS_SPACES.matcher(NEWLINES.matcher(message).replaceAll("<LF>")).replaceAll(" "));
  }
}
