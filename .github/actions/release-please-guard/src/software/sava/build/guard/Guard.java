package software.sava.build.guard;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
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
      out.println("::error::could not work out the releases release-please would cut: " + CommitRange.oneLine(e.toString()));
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

    final CommitRange range = CommitRange.read(git, config, github);
    if (range.baseText == null) {
      out.println("::notice::no release tag found for manifest version " + (config.version != null ? config.version : "(none)")
          + "; checking the last " + range.commitCount + " commit(s), as release-please does before a first release");
    }
    final int failures = checkCommits(config, range);
    final List<String> errors = new ArrayList<>();
    if (github == null) {
      out.println("releases not checked: GITHUB_REPOSITORY is not set");
    } else {
      checkReleases(config, github, errors);
    }
    for (final String message : new LinkedHashSet<>(errors)) {
      out.println("::error::" + message);
    }
    return failures > 0 || !errors.isEmpty() ? 1 : 0;
  }

  // Parse first, then judge: a Restates footer counts only on a part release-please reads,
  // since a carrier it drops never puts its restatement into the notes either.
  private int checkCommits(ReleaseConfig config, CommitRange range) {
    final List<String> restated = new ArrayList<>();
    for (final CommitRange.Part part : range.parts) {
      if (part.parsed()) {
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
      // Stopped at this guard's own limit: release-please may read on, a Release-As footer
      // or BREAKING CHANGE note included, so no Restates footer covers it, and what
      // release-please reads must change instead.
      if (part.limited()) {
        failures++;
        failureLines.add("::error::this guard cannot read " + part.sha() + " where release-please may: \"" + part.header()
            + "\" (" + part.error() + ")"
            + (part.overridden() ? ", read from the BEGIN_COMMIT_OVERRIDE block in pull request #" + part.pullRequest()
            + "'s body" : "") + ". A Release-As footer or BREAKING CHANGE note in it would count, so a Restates footer "
            + "does not cover it. Give release-please a shorter message to read instead: "
            + (part.pullRequest() != null ? "edit the BEGIN_COMMIT_OVERRIDE block in pull request #" + part.pullRequest()
            + "'s body, which replaces the commit's message" : "a BEGIN_COMMIT_OVERRIDE block in the body of a pull "
            + "request the commit belongs to, which this commit has none of") + ".");
        continue;
      }
      final boolean acknowledged = restated.stream().anyMatch(prefix -> part.sha().startsWith(prefix));
      if (conventionalHeader(part.header()) && !acknowledged) {
        failures++;
        failureLines.add("::error::release-please cannot parse " + part.sha() + " and would drop it from the release "
            + "notes, its BREAKING CHANGE footer included: \"" + part.header() + "\" (" + part.error() + "). A body or "
            + "footer line whose leading run (no whitespace, '(', ')', ':' or '!'; backticks count) runs straight into "
            + "'(' fails unless a ')' closes it before the next '(' or the end of the line: indent the line or put a "
            + "space before the '('. Push a commit that restates the header, body and footers in that form and ends "
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

  private void checkReleases(ReleaseConfig config, GitHub github, List<String> errors) {
    // release-please targets the repository's default branch unless told otherwise.
    final String targetBranch = !env("TARGET_BRANCH").isEmpty() ? env("TARGET_BRANCH") : env("GITHUB_REF_NAME");
    if (targetBranch.isEmpty()) {
      throw new IllegalStateException("no target branch: TARGET_BRANCH and GITHUB_REF_NAME are empty");
    }
    final ReleasePullRequests.Report report = new ReleasePullRequests.Report();
    ReleasePullRequests.check(github, config, targetBranch, repositoryPath(env("MANIFEST_FILE")), report);
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
