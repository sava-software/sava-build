package software.sava.build.guard;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What release-please would tag from each release pull request. release-please tags a merged
 * release pull request with the version in its title, unless its body parses into more than
 * one release section, when the body's version wins, and reads the body from a branch file
 * instead when the body carries its overflow message. Sections are HTML details elements
 * found by an HTML parser, after a parse, serialize and parse again that decodes escaped
 * markup, so the guard does not predict that parse: a body whose notes hold the markup a
 * section is made of, in any spelling, or the overflow message, is refused, and what is left
 * is the title's version, which must be a plain X.Y.Z version and the one the pull request
 * wrote into the manifest at its own commit. A merged pull request about to be tagged fails
 * the run; an open one, checked as if merged, is a warning.
 */
final class ReleasePullRequests {

  record PullRequest(long number, String title, String body, String headRef, String headSha, String baseRef,
                     List<String> labels, String mergeCommitSha, boolean merged) {
  }

  /** The findings of the check, by channel, and how many releases release-please would cut. */
  static final class Report {
    final List<String> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> notices = new ArrayList<>();
    final List<String> lines = new ArrayList<>();
    int fromMerged;
    int fromOpen;
  }

  static final Pattern PLAIN_VERSION = Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
  /** JavaScript's '.': any character but a line terminator (Java's leaves out U+0085 as well). */
  private static final String ANY = "[^\\n\\r\\u2028\\u2029]";
  // release-please's OVERFLOW_MESSAGE_REGEX, matched anywhere in the body, with the message's
  // period unescaped as release-please builds it, so it matches any character there.
  static final Pattern OVERFLOW = Pattern.compile(
      "This release is too large to preview in the pull request body" + ANY + " View the full release notes here: ("
          + ANY + "*)");
  // The markup release-please's HTML parser makes a release section of, as a tag or as the
  // escaped text a parse, serialize and parse again turns into one.
  static final Pattern SECTION_MARKUP = Pattern.compile("(?i)(?:<|&lt;?|&#0*60;?|&#x0*3c;?)/?(?:details|summary)");
  // release-please's COMPARE_REGEX, the version of a single release section.
  private static final Pattern SINGLE_RELEASE = Pattern.compile("^#{2,} \\[?(\\d+\\.\\d+\\.\\d+[^\\]]*)\\]?");
  private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(-([^+]+))?(\\+(" + ANY + "*))?");
  private static final String DEFAULT_TITLE_PATTERN = "chore${scope}: release${component} ${version}";
  private static final int MERGED_SEARCH_DEPTH = 200;

  private static final String REMEDY = "The newest valid Release-As footer wins, so a later commit with one, or "
      + "release-as in the config, sets the next release's version; the commits a merged release pull request was built "
      + "from are fixed, and a footer among them, like the commit text the body's notes come from, leaves only through "
      + "a BEGIN_COMMIT_OVERRIDE block in the description of the pull request that merged that commit, which replaces "
      + "its commit text.";

  private ReleasePullRequests() {
  }

  /**
   * The version release-please computes for a release, when the guard can tell, and where it
   * comes from: a release pull request naming another version was built from commits or edits
   * that are not there now. A null version with a source is one a pull request chose, or may
   * have chosen through a commit this guard cannot read, with a Release-As footer that belongs
   * to it: refused whatever the pull request names.
   */
  record Expected(String version, String source) {
    /** No expectation at all: the merge commit is not in the checkout, so the commits are unread. */
    static final Expected MISSING_MERGE = new Expected(null, null);
  }

  /**
   * Checks the merged (as listed, release-please's way) and open release pull requests.
   * releaseExists says whether a GitHub release exists for a tag: a merged release pull
   * request whose release exists is one release-please only labels (its create fails as a
   * duplicate), so nothing is checked for it. A bare tag without a release is no such thing:
   * release-please creates the release on it.
   */
  static void check(GitHub github, ReleaseConfig config, String targetBranch, String manifestPath,
                    List<PullRequest> merged, Expected openExpected, Map<String, Expected> mergedExpected,
                    Predicate<String> releaseExists, Report report) {
    if (config.skipGithubRelease) {
      report.lines.add("release pull requests not checked: skip-github-release is set, so release-please tags nothing");
      return;
    }
    final Map<String, String> manifests = new HashMap<>();
    for (final PullRequest pull : merged) {
      if (hasAllLabels(config.labels, pull.labels())) {
        // Built from the commits up to its merge: judged against what those gave, not HEAD.
        checkOne(github, config, manifestPath, pull, true, manifests, mergedExpected.get(pull.mergeCommitSha()),
            releaseExists, report);
      }
    }
    for (final PullRequest pull : open(github, targetBranch)) {
      if (hasAllLabels(config.labels, pull.labels())) {
        checkOne(github, config, manifestPath, pull, false, manifests, openExpected, releaseExists, report);
      }
    }
  }

  /**
   * The merge commits of the merged release pull requests release-please is about to tag,
   * keyed to their numbers: those with every configured label that it reads as a release
   * pull request, as {@link #wouldTag} has it. Any other labelled merged pull request is
   * passed over, and its commits read like any other's.
   */
  static Map<String, Long> releaseMergeCommits(ReleaseConfig config, List<PullRequest> merged) {
    final Map<String, Long> commits = new HashMap<>();
    if (config.skipGithubRelease) {
      return commits;
    }
    for (final PullRequest pull : merged) {
      if (hasAllLabels(config.labels, pull.labels()) && pull.mergeCommitSha() != null && wouldTag(config, pull)) {
        commits.put(pull.mergeCommitSha(), pull.number());
      }
    }
    return commits;
  }

  /**
   * Whether release-please reads the pull request as one to tag, by the same rules as
   * {@link #checkOne} reports them: a body with the overflow message or section markup
   * counts (release-please reads it, and this guard then refuses it), as does a title version
   * release-please fails on (it fails there before it looks at the branch); a body without a
   * delimiter line, a title or head branch it cannot read, a component mismatch beside a
   * section, or no version at all does not. A version past 2^53, which release-please rounds,
   * counts like any other: this guard refuses it only where release-please would tag it.
   */
  static boolean wouldTag(ReleaseConfig config, PullRequest pull) {
    if (OVERFLOW.matcher(pull.body()).find()) {
      return true;
    }
    final BodyParts body = splitBody(pull.body());
    if (body == null) {
      return false;
    }
    if (SECTION_MARKUP.matcher(body.content()).find()) {
      return true;
    }
    final TitleMatch title = parseTitle(pull.title(), config.pullRequestTitlePattern, config.componentNoSpace);
    final TitleMatch grouped = title != null ? title
        : parseTitle(pull.title(), config.groupPullRequestTitlePattern, config.componentNoSpace);
    if (grouped == null) {
      return false;
    }
    if (grouped.version() != null && !VERSION.matcher(grouped.version()).find()) {
      return true;
    }
    final String branchComponent = branchComponent(pull.headRef());
    if (branchComponent == null) {
      return false;
    }
    final Matcher single = SINGLE_RELEASE.matcher(ConventionalCommits.jsTrim(body.content()));
    final boolean section = single.find();
    if (section && !branchComponent.equals(config.component)) {
      return false;
    }
    return grouped.version() != null || section;
  }

  /** The merged pull requests release-please reads: the 200 most recently updated, as it lists them. */
  static List<PullRequest> merged(GitHub github, String targetBranch) {
    final List<PullRequest> merged = new ArrayList<>();
    for (int page = 1; ; page++) {
      final List<Map<String, Object>> pulls = github.pulls("closed", targetBranch, page);
      for (final Map<String, Object> pull : pulls) {
        final PullRequest parsed = pullRequest(pull);
        if (!parsed.merged()) {
          continue;
        }
        merged.add(parsed);
        if (merged.size() >= MERGED_SEARCH_DEPTH) {
          return merged;
        }
      }
      if (pulls.size() < 100) {
        return merged;
      }
    }
  }

  static List<PullRequest> open(GitHub github, String targetBranch) {
    final List<PullRequest> open = new ArrayList<>();
    for (int page = 1; ; page++) {
      final List<Map<String, Object>> pulls = github.pulls("open", targetBranch, page);
      for (final Map<String, Object> pull : pulls) {
        open.add(pullRequest(pull));
      }
      if (pulls.size() < 100) {
        return open;
      }
    }
  }

  private static PullRequest pullRequest(Map<String, Object> pull) {
    final Map<String, Object> head = Json.asObject(pull.get("head"), "a pull request's head");
    final Map<String, Object> base = Json.asObject(pull.get("base"), "a pull request's base");
    final List<String> labels = new ArrayList<>();
    if (pull.get("labels") instanceof List<?> list) {
      for (final Object label : list) {
        labels.add(String.valueOf(Json.asObject(label, "a label").get("name")));
      }
    }
    return new PullRequest(
        ((Number) pull.get("number")).longValue(),
        pull.get("title") instanceof String title ? title : "",
        pull.get("body") instanceof String body ? body : "",
        String.valueOf(head.get("ref")),
        String.valueOf(head.get("sha")),
        String.valueOf(base.get("ref")),
        labels,
        pull.get("merge_commit_sha") instanceof String sha ? sha : null,
        pull.get("merged_at") != null);
  }

  static boolean hasAllLabels(List<String> expected, List<String> existing) {
    for (final String label : expected) {
      if (!existing.contains(label)) {
        return false;
      }
    }
    return true;
  }

  private static void checkOne(GitHub github, ReleaseConfig config, String manifestPath, PullRequest pull,
                               boolean merged, Map<String, String> manifests, Expected expected,
                               Predicate<String> releaseExists, Report report) {
    final String which = (merged ? "merged" : "open") + " release pull request #" + pull.number();
    final String sha = merged ? pull.mergeCommitSha() : pull.headSha();
    final List<String> reasons = new ArrayList<>();

    // release-please reads the overflow message before anything else in the body, and takes
    // the body, sections and version included, from the file it links; a body release-please
    // wrote that way holds nothing but the message.
    final Matcher overflow = OVERFLOW.matcher(pull.body());
    if (overflow.find()) {
      if (merged) {
        report.fromMerged++;
        report.errors.add(which + " is about to be tagged, but its body carries release-please's overflow message, "
            + "so release-please reads the notes, and with them the release sections and version, from "
            + overflow.group(1) + " rather than from the pull request, and a file on a ref can change between this "
            + "check and the release. Nothing has been tagged. Edit #" + pull.number() + "'s body to hold the notes "
            + "themselves, with no overflow message, then re-run this job; or remove " + labelText(config)
            + " from it to skip the release.");
      } else {
        report.fromOpen++;
        report.warnings.add(which + ": once merged, its body carries release-please's overflow message, so "
            + "release-please reads the notes, and with them the release sections and version, from "
            + overflow.group(1) + " rather than from the pull request. Merged as it is, this guard fails the release "
            + "run and nothing is tagged until the merged pull request's body is edited to hold the notes themselves.");
      }
      return;
    }
    // Without a delimiter line release-please does not read the body as a release pull
    // request's, and tags nothing from it.
    final BodyParts body = splitBody(pull.body());
    if (body == null) {
      report.notices.add(which + " is left alone: its body has no '---' line, so release-please does not read it as "
          + "a release pull request and tags nothing from it");
      return;
    }
    // With the markup of a section in the notes, what release-please reads from the body is
    // not predicted: refused as such, before anything the title or branch would decide.
    final Matcher markup = SECTION_MARKUP.matcher(body.content());
    if (markup.find()) {
      reasons.add("its body holds the markup of a release section (\"" + markup.group() + "\"), and with more than "
          + "one section release-please takes the version from the body, not from the title");
      report(config, pull, merged, reasons, report);
      return;
    }

    final TitleMatch title = parseTitle(pull.title(), config.pullRequestTitlePattern, config.componentNoSpace);
    final TitleMatch grouped = title != null ? title
        : parseTitle(pull.title(), config.groupPullRequestTitlePattern, config.componentNoSpace);
    if (grouped == null) {
      report.notices.add(which + " is left alone: its title " + quote(pull.title())
          + " does not match the release pull request title pattern, so release-please tags nothing from it");
      return;
    }
    // A title version release-please fails on fails it before it looks at the branch; one
    // release-please rounds (past 2^53) is refused only where release-please would tag it,
    // after the branch and component checks.
    String version = null;
    String rounded = null;
    if (grouped.version() != null) {
      try {
        version = parseVersion(grouped.version());
      } catch (IllegalArgumentException e) {
        if (!VERSION.matcher(grouped.version()).find()) {
          reasons.add("its title names a version this guard refuses (" + e.getMessage() + "); fix the title, or remove "
              + labelText(config) + " from it");
          report(config, pull, merged, reasons, report);
          return;
        }
        rounded = e.getMessage();
      }
    }
    final String branchComponent = branchComponent(pull.headRef());
    if (branchComponent == null) {
      report.notices.add(which + " is left alone: its head branch " + quote(pull.headRef())
          + " is not a release-please branch name, so release-please tags nothing from it");
      return;
    }
    // The body's one section, if it has one. release-please compares the branch's component
    // with the configured one only then, and leaves the pull request alone on a mismatch
    // whatever the section names; with no section it tags the title's version.
    final Matcher single = SINGLE_RELEASE.matcher(ConventionalCommits.jsTrim(body.content()));
    final boolean section = single.find();
    if (section && !branchComponent.equals(config.component)) {
      report.notices.add(which + " is left alone: its head branch's component " + quote(branchComponent)
          + " is not the configured component " + quote(config.component) + ", so release-please tags nothing from it");
      return;
    }
    if (rounded != null) {
      reasons.add("its title names a version this guard refuses (" + rounded + "); fix the title, or remove "
          + labelText(config) + " from it");
      report(config, pull, merged, reasons, report);
      return;
    }
    // The section's version counts only when the title names none; release-please does not
    // read it otherwise.
    String sectionVersion = null;
    if (section && version == null) {
      try {
        sectionVersion = parseVersion(single.group(1));
      } catch (IllegalArgumentException e) {
        reasons.add("its body's release section names a version this guard refuses (" + e.getMessage()
            + "); edit the body, or remove " + labelText(config) + " from it");
        report(config, pull, merged, reasons, report);
        return;
      }
    }
    if (version == null) {
      version = sectionVersion;
    }
    if (version == null) {
      report.notices.add(which + " is left alone: neither its title nor its body names a version, so release-please "
          + "tags nothing from it");
      return;
    }
    // A merged release pull request whose GitHub release exists was released by a run that
    // then failed to relabel it: release-please's create fails as a duplicate, it labels the
    // pull request tagged and tags nothing, and nothing here may stand in the way of that.
    if (merged && releaseExists.test(config.tagOf(version))) {
      report.notices.add(which + " is already released as " + quote(config.tagOf(version)) + " (a GitHub release "
          + "exists for that tag): release-please labels it tagged and tags nothing, so its version is not checked");
      return;
    }

    if (merged) {
      report.fromMerged++;
    } else {
      report.fromOpen++;
    }
    // A merged pull request whose merge commit the checkout does not have (the merge landed
    // after the checkout fetched, or sits on no branch): the commits it was built from cannot
    // be checked from here, a contributor's footer among them included, so the release is
    // refused until a run from a checkout that has the merge.
    if (expected == Expected.MISSING_MERGE) {
      report.errors.add(which + "'s merge commit " + sha.substring(0, 7) + " is not in this checkout, so the commits it "
          + "was built from cannot be checked here, and release-please is about to tag it. Nothing has been tagged. Run "
          + "this workflow from the branch's current head once it has the merge (a push, or a dispatch), or remove "
          + labelText(config) + " from #" + pull.number() + " to skip the release.");
      expected = null;
    }
    String manifestVersion = null;
    boolean manifestRead = false;
    try {
      manifestVersion = manifestVersion(github, config, manifestPath, sha, manifests);
      manifestRead = true;
    } catch (GitHub.NotFoundException e) {
      if (merged) {
        report.errors.add("release pull request #" + pull.number() + "'s merge commit has no readable " + manifestPath
            + " (" + e.getMessage() + "), so the version it is about to be tagged with cannot be checked. Nothing has "
            + "been tagged. Remove " + labelText(config) + " from #" + pull.number() + " to skip the release.");
      } else {
        report.warnings.add(which + " not checked: " + manifestPath + " at its head could not be read ("
            + e.getMessage() + ")");
      }
    }
    final String tag = config.tagOf(version);
    final List<String> tagReasons = new ArrayList<>();
    if (!PLAIN_VERSION.matcher(tag).matches()) {
      tagReasons.add("not a plain X.Y.Z version");
    }
    if (manifestRead && !version.equals(manifestVersion)) {
      tagReasons.add("not " + quote(manifestVersion) + ", the version the release pull request wrote into " + manifestPath);
    }
    if (!tagReasons.isEmpty()) {
      reasons.add("release-please would tag it " + quote(tag) + ", which is " + String.join(", and ", tagReasons));
    }
    // The version must also be the one the commits give (those it was built from, for a
    // merged one): one computed from a Release-As footer or a commit override that has since
    // been edited away, or from any other source, is refused whether or not it is plain and
    // in the manifest; and one a pull request chose, or may have, is refused whatever it names.
    if (expected != null && expected.version() == null) {
      reasons.add(expected.source());
    } else if (expected != null && !version.equals(expected.version())) {
      reasons.add("its version " + quote(version) + " is not " + quote(expected.version()) + ", " + expected.source()
          + (merged ? "; release-please computed it from commits or pull request edits that are not there now, or the "
          + "pull request was merged before release-please refreshed it"
          : "; it comes from release-please's last run, or from an edit since: a pull request that only predates a "
          + "newer footer is rewritten by release-please once this run is green, an edited title is not"));
    }
    if (!reasons.isEmpty()) {
      report(config, pull, merged, reasons, report);
    }
  }

  // The finding for a release pull request, as an error when it is merged and about to be
  // tagged, as a warning when it is open and would be refused once merged.
  private static void report(ReleaseConfig config, PullRequest pull, boolean merged, List<String> reasons, Report report) {
    final String which = (merged ? "merged" : "open") + " release pull request #" + pull.number();
    if (merged) {
      report.errors.add(which + " is about to be tagged, but " + String.join("; and ", reasons) + ". The publishing "
          + "workflows refuse a tag that is not a plain X.Y.Z version or not the version of its commit's manifest. "
          + "Nothing has been tagged. Edit #" + pull.number() + " so release-please reads the manifest's version from "
          + "it (its title, with no release section markup or overflow message in its body), then re-run this job; or "
          + "remove " + labelText(config) + " from it to skip the release. Then fix where the version came from, or "
          + "the next release repeats it: " + REMEDY);
    } else {
      report.warnings.add(which + ": once merged, " + String.join("; and ", reasons) + ". Merged as it is, this guard "
          + "fails the release run and nothing is tagged until the merged pull request is fixed. Fix where the version "
          + "came from first: " + REMEDY + " release-please rewrites the pull request on its next run.");
    }
  }

  // The manifest at a release's own commit, read once per commit whether or not the read succeeds.
  private static String manifestVersion(GitHub github, ReleaseConfig config, String manifestPath, String sha,
                                        Map<String, String> manifests) {
    if (manifests.containsKey(sha)) {
      final String cached = manifests.get(sha);
      if (cached == null) {
        throw new GitHub.NotFoundException(manifestPath + " does not exist on " + quote(sha));
      }
      return cached;
    }
    final String text;
    try {
      text = github.fileAt(manifestPath, sha);
    } catch (GitHub.NotFoundException e) {
      manifests.put(sha, null);
      throw new GitHub.NotFoundException(manifestPath + " does not exist on " + quote(sha) + " (" + e.getMessage() + ")");
    }
    final Object json;
    try {
      json = Json.parse(text);
    } catch (IllegalArgumentException e) {
      manifests.put(sha, null);
      throw new GitHub.NotFoundException(manifestPath + " on " + quote(sha) + " is not JSON (" + e.getMessage() + ")");
    }
    final Object version = json instanceof Map ? Json.asObject(json, manifestPath).get(config.path) : null;
    final String text2 = version instanceof String s ? s : null;
    if (text2 == null) {
      manifests.put(sha, null);
      throw new GitHub.NotFoundException(manifestPath + " on " + quote(sha) + " names no version for " + config.path);
    }
    manifests.put(sha, text2);
    return text2;
  }

  static String labelText(ReleaseConfig config) {
    return String.join(" and ", config.labels.stream().map(label -> "'" + label + "'").toList());
  }

  static String quote(String text) {
    return text == null ? "null" : Json.string(text);
  }

  // ---- release-please's PullRequestBody.splitBody

  record BodyParts(String header, String content, String footer) {
  }

  static BodyParts splitBody(String body) {
    final List<String> lines = Arrays.asList(ConventionalCommits.jsTrim(body).replace("\r\n", "\n").split("\n", -1));
    final int index = lines.indexOf("---");
    if (index == -1) {
      return null;
    }
    int lastIndex = lines.lastIndexOf("---");
    if (lastIndex == index) {
      lastIndex = lines.size() - 1;
    }
    // JavaScript's slice: empty when the range is inverted, as when the delimiter is the last line.
    return new BodyParts(
        ConventionalCommits.jsTrim(String.join("\n", lines.subList(0, index))),
        index + 1 >= lastIndex ? "" : String.join("\n", lines.subList(index + 1, lastIndex)),
        String.join("\n", lines.subList(Math.min(lastIndex + 1, lines.size()), lines.size())));
  }

  // ---- release-please's PullRequestTitle.parse

  record TitleMatch(String version, String component, String branch) {
  }

  static TitleMatch parseTitle(String title, String pattern, boolean componentNoSpace) {
    final Pattern regex = titlePattern(pattern, componentNoSpace);
    final Matcher match = regex.matcher(title);
    if (!match.matches()) {
      return null;
    }
    return new TitleMatch(group(match, "version"), group(match, "component"), group(match, "branch"));
  }

  private static String group(Matcher match, String name) {
    try {
      return match.group(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** release-please's generateMatchPattern: each replacement applies to the first occurrence only. */
  static Pattern titlePattern(String pullRequestTitlePattern, boolean componentNoSpace) {
    String pattern = pullRequestTitlePattern != null ? pullRequestTitlePattern : DEFAULT_TITLE_PATTERN;
    pattern = replaceFirst(pattern, "[", "\\[");
    pattern = replaceFirst(pattern, "]", "\\]");
    pattern = replaceFirst(pattern, "(", "\\(");
    pattern = replaceFirst(pattern, ")", "\\)");
    pattern = replaceFirst(pattern, "${scope}", "(\\((?<branch>[\\w./-]+)\\))?");
    pattern = replaceFirst(pattern, "${component}", componentNoSpace
        ? "?(?<component>@?[\\w./-]*)?"
        : " ?(?<component>@?[\\w./-]*)?");
    pattern = replaceFirst(pattern, "${version}", "v?(?<version>[0-9]" + ANY + "*)");
    pattern = replaceFirst(pattern, "${branch}", "(?<branch>[\\w./-]+)?");
    return Pattern.compile("^" + pattern + "$");
  }

  private static String replaceFirst(String text, String target, String replacement) {
    final int at = text.indexOf(target);
    return at < 0 ? text : text.substring(0, at) + replacement + text.substring(at + target.length());
  }

  // ---- release-please's BranchName.parse, for the component a release branch names

  private static final Pattern AUTORELEASE_BRANCH = Pattern.compile("^release-?([\\w.-]*)?-v([0-9]" + ANY + "*)$");
  private static final Pattern COMPONENT_BRANCH = Pattern.compile(
      "^release-please--branches--(" + ANY + "+)--components--(" + ANY + "+)$");
  private static final Pattern GROUP_BRANCH = Pattern.compile(
      "^release-please--branches--(" + ANY + "+)--groups--(" + ANY + "+)$");
  private static final Pattern DEFAULT_BRANCH = Pattern.compile("^release-please--branches--(" + ANY + "+)$");
  private static final Pattern V12_COMPONENT_BRANCH = Pattern.compile(
      "^release-please/branches/([^/]+)/components/(" + ANY + "+)$");
  private static final Pattern V12_DEFAULT_BRANCH = Pattern.compile("^release-please/branches/([^/]+)$");

  /**
   * The component of a release-please branch name ("" when it names none; the group of a
   * group branch, as release-please has it), or null for any other name. The autorelease
   * spelling is tried first, but never on a release-please--branches name.
   */
  static String branchComponent(String branchName) {
    Matcher m = AUTORELEASE_BRANCH.matcher(branchName);
    if (!branchName.startsWith("release-please--branches") && m.matches()) {
      // release-please parses the version there and fails, naming no branch, when it finds
      // none; a component it would round still names the branch.
      if (!VERSION.matcher(m.group(2)).find()) {
        return null;
      }
      return m.group(1) == null ? "" : m.group(1);
    }
    m = COMPONENT_BRANCH.matcher(branchName);
    if (m.matches()) {
      return m.group(2);
    }
    m = GROUP_BRANCH.matcher(branchName);
    if (m.matches()) {
      return m.group(2);
    }
    if (DEFAULT_BRANCH.matcher(branchName).matches()) {
      return "";
    }
    m = V12_COMPONENT_BRANCH.matcher(branchName);
    if (m.matches()) {
      return m.group(2);
    }
    if (V12_DEFAULT_BRANCH.matcher(branchName).matches()) {
      return "";
    }
    return null;
  }

  // ---- release-please's Version.parse and toString

  // 2^53: a JavaScript Number keeps an integer exact up to here, so release-please does too.
  private static final BigInteger MAX_EXACT_COMPONENT = BigInteger.valueOf(9_007_199_254_740_992L);

  /**
   * The version string release-please makes of the text: the first X.Y.Z[-pre][+build] in it.
   * A component release-please would round (past 2^53) is refused rather than kept exact. The
   * message says what release-please does with the text: fail, as its own message has it, or
   * round.
   */
  static String parseVersion(String text) {
    final Matcher m = VERSION.matcher(text);
    if (!m.find()) {
      throw new IllegalArgumentException("unable to parse version string: " + text + ", on which release-please fails");
    }
    final StringBuilder version = new StringBuilder();
    for (int group = 1; group <= 3; group++) {
      final BigInteger component = new BigInteger(m.group(group));
      if (component.compareTo(MAX_EXACT_COMPONENT) > 0) {
        throw new IllegalArgumentException("a version component past 2^53 in " + text + ", which release-please rounds");
      }
      version.append(group > 1 ? "." : "").append(component);
    }
    if (m.group(5) != null) {
      version.append('-').append(m.group(5));
    }
    // An empty build part is left out, as release-please's toString leaves out an empty string.
    if (m.group(7) != null && !m.group(7).isEmpty()) {
      version.append('+').append(m.group(7));
    }
    return version.toString();
  }
}
