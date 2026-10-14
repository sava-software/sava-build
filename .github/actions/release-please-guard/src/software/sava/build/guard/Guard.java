package software.sava.build.guard;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Run by action.yml before release-please, in a full-history checkout of the target branch.
 *
 * <p>release-please reads commits through the GitHub API and drops, at debug level, every
 * message its parser cannot read, so a commit's notes and BREAKING CHANGE footer vanish from
 * the release while the run stays green (sava 1d846b0, 2026-10). This parses the same range
 * with the same grammar first and fails naming the commit.
 *
 * <p>A Release-As footer sets the next version outright, and release-please reads it from any
 * commit, a squash-merged pull request's included, and from the commit override a pull
 * request's body may carry in place of the commit message. The footer release-please would
 * take must belong to a commit pushed to the target branch directly: one that belongs to a
 * pull request fails the run.
 *
 * <p>The publishing workflows refuse a tag that is not a plain X.Y.Z version, so each release
 * pull request is checked for what release-please would tag it with, before it is tagged.
 *
 * <p>Environment: CONFIG_FILE and MANIFEST_FILE (release-please's, as paths in the checkout),
 * GITHUB_REPOSITORY and GH_TOKEN for the API reads (without them, the pull request checks
 * are skipped), TARGET_BRANCH or GITHUB_REF_NAME for the branch release-please targets, and
 * GITHUB_API_URL for the API.
 */
public final class Guard {

  private static final Pattern RESTATES = Pattern.compile("(?im)^Restates:\\s*([0-9a-f]{7,40})\\s*$");
  private static final Pattern HEADER = Pattern.compile("^[A-Za-z]+(\\([^)]*\\))?!?: ");

  private final Path directory;
  private final Map<String, String> env;
  private final PrintStream out;

  public Guard(Path directory, Map<String, String> env, PrintStream out) {
    this.directory = directory;
    this.env = env;
    this.out = out;
  }

  public static void main(String[] args) {
    System.exit(new Guard(Path.of("").toAbsolutePath(), System.getenv(), System.out).run());
  }

  private String env(String name) {
    final String value = env.get(name);
    return value == null ? "" : value;
  }

  /** Runs every check and prints the findings; the exit code of the action. */
  public int run() {
    try {
      return check();
    } catch (GitHub.ReadException e) {
      out.println("::error::could not read from GitHub (" + CommitRange.oneLine(e.getMessage()) + "); re-run the job.");
      return 1;
    } catch (ReleaseConfig.ConfigException | Git.GitException e) {
      out.println("::error::" + CommitRange.oneLine(e.getMessage()));
      return 1;
    } catch (RuntimeException e) {
      out.println("::error::could not work out the releases release-please would cut: "
          + CommitRange.oneLine(e.getMessage() != null ? e.getMessage() : e.toString()));
      return 1;
    }
  }

  private int check() {
    if (env("CONFIG_FILE").isEmpty() || env("MANIFEST_FILE").isEmpty()) {
      throw new ReleaseConfig.ConfigException("CONFIG_FILE and MANIFEST_FILE must name release-please's config and manifest");
    }
    final Git git = new Git(directory);
    final ReleaseConfig config = ReleaseConfig.read(directory.resolve(env("CONFIG_FILE")),
        directory.resolve(env("MANIFEST_FILE")));
    final String repository = env("GITHUB_REPOSITORY");
    final GitHub github = repository.isEmpty() ? null
        : new GitHub(env("GITHUB_API_URL").isEmpty() ? "https://api.github.com" : env("GITHUB_API_URL"),
            env("GH_TOKEN"), repository);

    // The merged release pull requests release-please is about to tag: their merge commits
    // are tagged, never read as commits.
    final String targetBranch = github == null ? null : targetBranch();
    final List<ReleasePullRequests.PullRequest> merged = github == null ? List.of()
        : ReleasePullRequests.merged(github, targetBranch);
    final CommitRange range = CommitRange.read(git, config, github, ReleasePullRequests.releaseMergeCommits(config, merged));
    if (range.baseText == null) {
      out.println("::notice::no release tag found for manifest version " + (config.version != null ? config.version : "(none)")
          + "; checking the last " + range.commitCount + " commit(s), as release-please does before a first release");
    }
    for (final CommitRange.Tagged tagged : range.tagged) {
      out.println("release-please tags " + tagged.sha().substring(0, 7) + ", the merge commit of release pull request #"
          + tagged.pullRequest() + ", and reads it as no commit");
    }
    final int failures = checkCommits(config, range, github != null);
    final List<String> errors = new ArrayList<>();
    checkReleaseAs(config, range, github, errors);
    if (github == null) {
      out.println("releases not checked: GITHUB_REPOSITORY is not set");
    } else {
      checkReleases(config, github, git, targetBranch, merged, range, errors);
    }
    for (final String message : new LinkedHashSet<>(errors)) {
      out.println("::error::" + message);
    }
    return failures > 0 || !errors.isEmpty() ? 1 : 0;
  }

  // Parse first, then judge: a Restates footer counts only on a part release-please reads,
  // since a carrier it drops never puts its restatement into the notes either, and only in
  // a commit's own message: a pull request's override can be edited after the merge.
  // provenanceKnown: whether GitHub was asked which pull request each commit belongs to.
  private int checkCommits(ReleaseConfig config, CommitRange range, boolean provenanceKnown) {
    final List<String> restated = new ArrayList<>();
    for (final CommitRange.Part part : range.parts) {
      if (part.parsed() && !part.overridden()) {
        final Matcher m = RESTATES.matcher(part.text());
        while (m.find()) {
          restated.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
        }
      }
    }
    int failures = 0;
    int dropped = 0;
    final List<String> failureLines = new ArrayList<>();
    for (final CommitRange.Part part : range.parts) {
      if (part.parsed()) {
        continue;
      }
      final boolean acknowledged = restated.stream().anyMatch(prefix -> part.sha().startsWith(prefix));
      // Stopped at this guard's own limit: release-please may read on, a Release-As footer or
      // BREAKING CHANGE note included. For a commit a pull request brought, that is the pull
      // request choosing what release-please reads, so no Restates footer covers it and the
      // pull request's commit override must change what release-please reads; a commit pushed
      // directly was a maintainer's to begin with, and a Restates footer leaves it to
      // release-please.
      if (part.limited()) {
        if (provenanceKnown && part.pullRequest() == null && acknowledged) {
          out.println("::notice::this guard cannot read " + part.sha().substring(0, 7) + " where release-please may ("
              + part.error() + "); restated by a later commit, and pushed directly, so left to release-please: "
              + part.header());
          continue;
        }
        failures++;
        failureLines.add("::error::this guard cannot read " + part.sha() + " where release-please may: \"" + part.header()
            + "\" (" + part.error() + ")"
            + (part.overridden() ? ", read from the BEGIN_COMMIT_OVERRIDE block in pull request #" + part.pullRequest()
            + "'s body" : "") + ". A Release-As footer or BREAKING CHANGE note in it would count. "
            + (part.pullRequest() != null
            ? "Give release-please a shorter message to read instead: edit the BEGIN_COMMIT_OVERRIDE block in pull request #"
            + part.pullRequest() + "'s body, which replaces the commit's message; a Restates footer does not cover a "
            + "commit a pull request brought."
            : "Push a commit that restates its header, body and footers within that limit and ends with the footer "
            + "\"Restates: " + part.sha().substring(0, 7) + "\"" + (config.excludeConfigured ? ", changing a file outside "
            + "exclude-paths, since release-please excludes an empty commit" : "") + "; pushed directly, the commit is "
            + "then left to release-please" + (provenanceKnown ? "" : " (not here: whether it was pushed directly is "
            + "not checked, GITHUB_REPOSITORY being unset)") + ".")
            + " While this run is red the release PR is not refreshed.");
        continue;
      }
      if (conventionalHeader(part.header()) && !acknowledged) {
        failures++;
        failureLines.add("::error::release-please cannot parse " + part.sha() + " and would drop it from the release "
            + "notes, its BREAKING CHANGE footer included: \"" + part.header() + "\" (" + part.error() + ")"
            + (part.overridden() ? ", read from the BEGIN_COMMIT_OVERRIDE block in pull request #" + part.pullRequest()
            + "'s body, which replaces the commit's own message" : "") + ". A body or "
            + "footer line whose leading run (no whitespace, '(', ')', ':' or '!'; backticks count) runs straight into "
            + "'(' fails unless a ')' closes it before the next '(' or the end of the line: indent the line or put a "
            + "space before the '('. "
            + (part.overridden() ? "Edit that block in #" + part.pullRequest() + "'s body, or push" : "Push")
            + " a commit that restates the header, body and footers in that form and ends "
            + "with the footer \"Restates: " + part.sha().substring(0, 7) + "\""
            + (config.excludeConfigured ? "; with exclude-paths configured that commit must change a file outside "
            + "them, since release-please excludes an empty commit" : "")
            + ". While this run is red the release PR is not refreshed: restate first, then merge.");
      } else {
        dropped++;
        out.println("release-please will not read " + part.sha().substring(0, 7) + " ("
            + (acknowledged ? "restated by a later commit" : "not a conventional commit") + "): " + part.header());
      }
    }
    for (final CommitRange.Excluded excluded : range.excluded) {
      out.println("release-please excludes " + excluded.sha().substring(0, 7)
          + " (every listed file is under exclude-paths, or it lists none): " + excluded.header());
    }
    if (dropped > 0) {
      out.println("::notice::release-please will not read " + dropped + " of the " + range.commitCount
          + " commit(s) in this range (not conventional commits, or restated by a later commit); the step log lists them");
    }
    failureLines.forEach(out::println);
    out.println("checked " + range.commitCount + " commit(s) since " + (range.baseText != null ? range.baseText
        : "the start of history") + ": " + failures + " unparseable conventional commit(s), " + dropped
        + " release-please will not read" + (config.excludeConfigured ? ", " + range.excluded.size()
        + " excluded by exclude-paths" : ""));
    return failures;
  }

  // The same grammar decides error against notice: a part whose header line alone parses is a
  // conventional commit release-please would have read. The pattern keeps the one header shape
  // the grammar rejects but release-please still drops with its notes: an empty scope, or a
  // scope holding a parenthesis.
  static boolean conventionalHeader(String header) {
    return ConventionalCommits.headerParses(header) || HEADER.matcher(header).find();
  }

  /**
   * The Release-As footer release-please takes the next version from: the config's release-as
   * first, else the first parsed part, newest commit first, that carries one, in the commit's
   * message or in its pull request's override. That commit must have been pushed directly;
   * one that belongs to a pull request chose the version for the pull request's author.
   */
  private CommitRange.Part checkReleaseAs(ReleaseConfig config, CommitRange range, GitHub github, List<String> errors) {
    if (config.releaseAs != null) {
      out.println("release-as is set to " + ReleasePullRequests.quote(config.releaseAs) + " in " + config.configFile
          + ": release-please takes the next version from there and reads no Release-As footer");
      return null;
    }
    CommitRange.Part winner = null;
    for (final CommitRange.Part part : range.parts) {
      if (!part.parsed() || part.releaseAs() == null) {
        continue;
      }
      final String carrier = part.sha().substring(0, 7);
      final String footer = "the Release-As footer of " + carrier + " (" + ReleasePullRequests.quote(part.releaseAs())
          + (part.overridden() ? ", from the commit override in pull request #" + part.pullRequest() + "'s body" : "")
          + ")";
      if (winner != null) {
        out.println(footer + " is overridden by " + (winner.sha().equals(part.sha()) ? "an earlier footer of the same commit"
            : "the newer one of " + winner.sha().substring(0, 7)));
        continue;
      }
      winner = part;
      String reads;
      try {
        final String version = ReleasePullRequests.parseVersion(part.releaseAs());
        reads = "version " + ReleasePullRequests.quote(version);
        if (!ReleasePullRequests.PLAIN_VERSION.matcher(version).matches()) {
          out.println("::warning::" + footer + " is read by release-please as " + reads + ", not a plain X.Y.Z "
              + "version, which the publishing workflows refuse; push a newer commit with a valid Release-As footer");
        }
      } catch (IllegalArgumentException e) {
        reads = "nothing (" + e.getMessage() + ")";
        out.println("::warning::" + footer + " names a version this guard refuses (" + e.getMessage()
            + "); push a newer commit with a valid Release-As footer");
      }
      if (github == null) {
        out.println("::warning::" + footer + " sets the next version, but whether " + carrier
            + " was pushed directly or belongs to a pull request is not checked: GITHUB_REPOSITORY is not set");
        continue;
      }
      if (part.pullRequest() == null) {
        out.println("release-please takes the next version, " + reads + ", from " + footer + ", which was pushed directly");
      } else {
        errors.add("release-please takes the next version, " + reads + ", from " + footer + ", which belongs to pull "
            + "request #" + part.pullRequest() + ": a pull request may not choose the version. Push a commit with the "
            + "Release-As footer you want to the target branch directly (release-please takes the newest; "
            + (config.excludeConfigured ? "with exclude-paths configured it must change a file outside them, since "
            + "release-please excludes an empty commit" : "an empty commit will do") + "), or set release-as in "
            + config.configFile + ". While this run is red, release-please neither refreshes the release pull request "
            + "nor tags anything.");
      }
    }
    return winner;
  }

  /**
   * The version release-please computes from a range of commits under a config, when the
   * guard can tell: the config's release-as, else the Release-As footer release-please takes,
   * else, under always-bump-patch, the patch bump of the base release. Under another
   * versioning strategy, or before a first release, it is not computed, and the release pull
   * requests' versions go unchecked against the commits.
   */
  private ReleasePullRequests.Expected expectedVersion(ReleaseConfig config, List<CommitRange.Part> parts,
                                                       String baseVersion, String whose) {
    // A version this guard refuses where the expectation comes from (one release-please fails
    // on, or rounds: the config's release-as, a direct footer, the manifest's version it bumps)
    // refuses the release outright: release-please would tag whatever the pull request names,
    // which the refused version can never justify.
    if (config.releaseAs != null) {
      try {
        return new ReleasePullRequests.Expected(ReleasePullRequests.parseVersion(config.releaseAs),
            "release-as in " + config.configFile);
      } catch (IllegalArgumentException e) {
        return new ReleasePullRequests.Expected(null, "release-as in " + config.configFile
            + " names a version this guard refuses (" + e.getMessage() + ")");
      }
    }
    // The first footer release-please takes, newest commit first. A commit this guard could
    // not read to its end may carry one, so from there on the version is not known here
    // until a footer this guard did read decides it. Every part that may decide it answers
    // to the same rule as the next release's footer: one that belongs to a pull request
    // refuses the release, whatever it names or may name, since a pull request may not
    // choose the version; a direct one this guard could not read hides nothing behind it.
    String unread = null;
    for (final CommitRange.Part part : parts) {
      final boolean footer = part.parsed() && part.releaseAs() != null;
      if (!part.limited() && !footer) {
        continue;
      }
      final String sha = part.sha().substring(0, 7);
      if (part.pullRequest() != null) {
        return new ReleasePullRequests.Expected(null, (part.limited()
            ? "a commit among its commits that this guard cannot read, " + sha + ", belongs to pull request #"
            + part.pullRequest() + " and may carry the Release-As footer release-please takes"
            : "the Release-As footer release-please takes among its commits is that of " + sha
            + ", which belongs to pull request #" + part.pullRequest())
            + ": a pull request may not choose the version");
      }
      if (part.limited()) {
        if (unread == null) {
          unread = sha;
        }
        continue;
      }
      if (unread != null) {
        // The footer release-please takes is this one or the unread commit's: not known here.
        break;
      }
      try {
        return new ReleasePullRequests.Expected(ReleasePullRequests.parseVersion(part.releaseAs()),
            "the version the Release-As footer of " + sha + " gives");
      } catch (IllegalArgumentException e) {
        return new ReleasePullRequests.Expected(null, "the Release-As footer release-please takes among its commits, "
            + "that of " + sha + ", names a version this guard refuses (" + e.getMessage() + ")");
      }
    }
    if (unread != null) {
      out.println(whose + " not checked against the commits: " + unread
          + " may carry a Release-As footer this guard cannot read");
      return null;
    }
    if (config.versioning.equals("always-bump-patch") && config.version != null) {
      // release-please bumps the manifest's version, its tag found or not: one this guard
      // refuses justifies nothing, whatever tag the commits were read from.
      try {
        ReleasePullRequests.parseVersion(config.version);
      } catch (IllegalArgumentException e) {
        return new ReleasePullRequests.Expected(null, "the manifest's version " + config.version + " in "
            + config.manifestFile + ", which release-please bumps, names a version this guard refuses ("
            + e.getMessage() + ")");
      }
    }
    final Matcher base = baseVersion == null ? null : ReleasePullRequests.PLAIN_VERSION.matcher(baseVersion);
    if (config.versioning.equals("always-bump-patch") && base != null && base.matches()) {
      final String next = base.group(1) + "." + base.group(2) + "." + (Long.parseLong(base.group(3)) + 1);
      return new ReleasePullRequests.Expected(next, "the patch bump of " + baseVersion + " that always-bump-patch gives");
    }
    out.println(whose + " not checked against the commits: " + (!config.versioning.equals("always-bump-patch")
        ? "versioning is " + config.versioning + ", which this guard does not compute"
        : baseVersion == null ? "there is no release to bump from"
        : "the last release's version " + baseVersion + " is not a plain X.Y.Z version, whose patch bump this guard "
        + "does not compute"));
    return null;
  }

  /**
   * The version each merged release pull request should carry: what the commits up to its
   * merge gave under the config as of its merge, since release-please built it from those,
   * whatever landed or changed after the merge. The commits are read from the merge commit's
   * first parent, with the depth that config gives, as release-please read them. A merge
   * commit the checkout does not have (the merge landed after the checkout fetched, or sits
   * on no branch) has no expectation ({@link ReleasePullRequests.Expected#MISSING_MERGE}),
   * which refuses the release where release-please would tag it: judged from the branch as
   * checked out, the commits it was built from would go unread, a contributor's footer among
   * them included. A config the checkout cannot read there is replaced by the config as
   * checked out, with a note.
   */
  private Map<String, ReleasePullRequests.Expected> mergedExpectations(ReleaseConfig config, Git git, GitHub github,
                                                                        List<ReleasePullRequests.PullRequest> merged) {
    final Map<String, ReleasePullRequests.Expected> expectations = new HashMap<>();
    final Map<String, Long> releaseMergeCommits = ReleasePullRequests.releaseMergeCommits(config, merged);
    for (final ReleasePullRequests.PullRequest pull : merged) {
      if (!ReleasePullRequests.hasAllLabels(config.labels, pull.labels()) || pull.mergeCommitSha() == null
          || !ReleasePullRequests.wouldTag(config, pull)) {
        continue;
      }
      final String which = "merged release pull request #" + pull.number();
      final String before = pull.mergeCommitSha() + "^1";
      if (!git.commitExists(before)) {
        expectations.put(pull.mergeCommitSha(), ReleasePullRequests.Expected.MISSING_MERGE);
        continue;
      }
      final ReleaseConfig asMerged = configAt(git, config, pull.mergeCommitSha().substring(0, 7) + "^1", before, which);
      final CommitRange boundary = CommitRange.read(git, asMerged, github, releaseMergeCommits, before);
      expectations.put(pull.mergeCommitSha(), expectedVersion(asMerged, boundary.parts, boundary.baseVersion,
          which + "'s version is"));
    }
    return expectations;
  }

  /** The config and manifest at a revision, else the ones as checked out, with a note. */
  private ReleaseConfig configAt(Git git, ReleaseConfig config, String at, String revision, String which) {
    final String configPath = repositoryPath(env("CONFIG_FILE"));
    final String manifestPath = repositoryPath(env("MANIFEST_FILE"));
    try {
      return ReleaseConfig.parse(configPath + " at " + at, git.show(revision, configPath),
          manifestPath + " at " + at, git.show(revision, manifestPath));
    } catch (Git.GitException | ReleaseConfig.ConfigException e) {
      out.println(which + "'s release-please config or manifest at " + at + " could not be read ("
          + CommitRange.oneLine(e.getMessage()) + "); its version is judged with the config as checked out");
      return config;
    }
  }

  // release-please targets the repository's default branch unless told otherwise.
  private String targetBranch() {
    final String targetBranch = !env("TARGET_BRANCH").isEmpty() ? env("TARGET_BRANCH") : env("GITHUB_REF_NAME");
    if (targetBranch.isEmpty()) {
      throw new IllegalStateException("no target branch: TARGET_BRANCH and GITHUB_REF_NAME are empty");
    }
    return targetBranch;
  }

  private void checkReleases(ReleaseConfig config, GitHub github, Git git, String targetBranch,
                             List<ReleasePullRequests.PullRequest> merged, CommitRange range, List<String> errors) {
    final ReleasePullRequests.Expected open = expectedVersion(config, range.parts, range.baseVersion,
        "the open release pull requests' versions are");
    final ReleasePullRequests.Report report = new ReleasePullRequests.Report();
    ReleasePullRequests.check(github, config, targetBranch, repositoryPath(env("MANIFEST_FILE")), merged, open,
        mergedExpectations(config, git, github, merged), github::releaseExists, report);
    report.lines.forEach(out::println);
    for (final String notice : report.notices) {
      out.println("::notice::" + notice);
    }
    final Set<String> warnings = new LinkedHashSet<>(report.warnings);
    for (final String warning : warnings) {
      out.println("::warning::" + warning);
    }
    out.println("checked the releases release-please would cut: " + report.fromMerged + " from merged and "
        + report.fromOpen + " from open release pull request(s)");
    errors.addAll(report.errors);
  }

  // release-please reads the manifest by repository path, as given: a relative path is used
  // unchanged; an absolute one is made relative to the repository, both resolved through
  // their links.
  private String repositoryPath(String file) {
    final Path path = Path.of(file);
    final String separator = path.getFileSystem().getSeparator();
    if (!path.isAbsolute()) {
      return path.normalize().toString().replace(separator, "/");
    }
    try {
      final Path top = new Git(directory).topLevel().toRealPath();
      return top.relativize(path.toRealPath()).toString().replace(separator, "/");
    } catch (java.io.IOException e) {
      throw new ReleaseConfig.ConfigException("cannot resolve " + file + " within the repository: " + e.getMessage());
    }
  }
}
