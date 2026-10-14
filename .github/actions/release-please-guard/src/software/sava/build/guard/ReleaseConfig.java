package software.sava.build.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The release-please settings the guard reads, resolved as release-please resolves them: a
 * package's own setting, else the top-level one, else release-please's default. The guard
 * checks a config with exactly one package, which every sava repository has.
 */
final class ReleaseConfig {

  /** The config the guard cannot read, or does not check. */
  static final class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
      super(message);
    }
  }

  final String configFile;
  final String manifestFile;
  /** The one package's path in the config and the manifest, "." at the repository root. */
  final String path;
  /** The manifest's version for the package, or null when it names none. */
  final String version;
  /** The release component, as release-please derives it: component, else package-name, else none. */
  final String component;
  final boolean includeVInTag;
  final boolean includeComponentInTag;
  final String tagSeparator;
  /** Whether exclude-paths is configured at all: an empty list still excludes empty commits. */
  final boolean excludeConfigured;
  final List<String> excludePaths;
  final int commitSearchDepth;
  /** The commits release-please reads per page of its history (commit-batch-size), 10 unless configured. */
  final int commitBatchSize;
  /** release-please's versioning strategy for the package, "default" unless configured. */
  final String versioning;
  /** The labels a release pull request carries, every one required. */
  final List<String> labels;
  final String pullRequestTitlePattern;
  final boolean componentNoSpace;
  final String groupPullRequestTitlePattern;
  /** The config's release-as, which release-please takes over every commit footer. */
  final String releaseAs;
  final boolean skipGithubRelease;

  private final Map<String, Object> config;
  private final Map<String, Object> pkg;

  static ReleaseConfig read(Path configFile, Path manifestFile) {
    return new ReleaseConfig(configFile.toString(), readJson(configFile, "config"),
        manifestFile.toString(), readJson(manifestFile, "manifest"));
  }

  private static Object readJson(Path file, String what) {
    final String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ConfigException("cannot read the release-please " + what + " " + file + ": " + e.getMessage());
    }
    try {
      return Json.parse(text);
    } catch (IllegalArgumentException e) {
      throw new ConfigException("the release-please " + what + " " + file + " is not JSON: " + e.getMessage());
    }
  }

  ReleaseConfig(String configFile, Object configJson, String manifestFile, Object manifestJson) {
    this.configFile = configFile;
    this.manifestFile = manifestFile;
    if (!(configJson instanceof Map)) {
      throw new ConfigException("the release-please config " + configFile + " is not a JSON object");
    }
    this.config = Json.asObject(configJson, configFile);
    final Object packages = config.get("packages");
    if (!(packages instanceof Map<?, ?> packageMap) || packageMap.size() != 1) {
      throw new ConfigException("this guard checks a release-please config with exactly one package; "
          + configFile + " has " + (packages instanceof Map<?, ?> map ? map.size() : "no packages"));
    }
    final Map<String, Object> packagesByPath = Json.asObject(packages, "packages");
    this.path = packagesByPath.keySet().iterator().next();
    final Object pkgJson = packagesByPath.get(path);
    this.pkg = pkgJson instanceof Map ? Json.asObject(pkgJson, "the package") : Map.of();
    if (!(manifestJson instanceof Map)) {
      throw new ConfigException("the release-please manifest " + manifestFile + " is not a JSON object");
    }
    final Object manifestVersion = Json.asObject(manifestJson, manifestFile).get(path);
    if (manifestVersion != null && !(manifestVersion instanceof String)) {
      throw new ConfigException("the release-please manifest " + manifestFile + " names no version string for "
          + path);
    }
    this.version = (String) manifestVersion;
    final Object componentSetting = setting("component");
    final Object packageName = setting("package-name");
    this.component = componentSetting instanceof String c && !c.isEmpty() ? c
        : packageName instanceof String p ? p : "";
    this.includeVInTag = bool("include-v-in-tag", true);
    this.includeComponentInTag = bool("include-component-in-tag", true);
    this.tagSeparator = setting("tag-separator") instanceof String s ? s : "-";
    // release-please's normalizePaths: no leading or trailing slash. An empty list still
    // counts as configured there, and excludes every empty commit.
    final Object excludeSetting = setting("exclude-paths");
    this.excludeConfigured = excludeSetting != null;
    final List<String> excluded = new ArrayList<>();
    if (excludeSetting instanceof List<?> list) {
      for (final Object p : list) {
        excluded.add(String.valueOf(p).replaceAll("/+$", "").replaceAll("^/+", ""));
      }
    }
    this.excludePaths = List.copyOf(excluded);
    this.commitSearchDepth = positive(setting("commit-search-depth"), 500);
    this.commitBatchSize = positive(config.get("commit-batch-size"), 10);
    this.versioning = setting("versioning") instanceof String s && !s.isEmpty() ? s : "default";
    final Object label = config.get("label");
    this.labels = label != null ? List.of(String.valueOf(label).split(",", -1)) : List.of("autorelease: pending");
    this.pullRequestTitlePattern = setting("pull-request-title-pattern") instanceof String s ? s : null;
    this.componentNoSpace = bool("component-no-space", false);
    this.groupPullRequestTitlePattern = config.get("group-pull-request-title-pattern") instanceof String s
        ? s : "chore: release ${branch}";
    this.releaseAs = setting("release-as") instanceof String s && !s.isEmpty() ? s : null;
    this.skipGithubRelease = bool("skip-github-release", false);
  }

  /** The package's setting, else the top-level one, else null (JavaScript's ??). */
  Object setting(String name) {
    final Object own = pkg.get(name);
    return own != null ? own : config.get(name);
  }

  private boolean bool(String name, boolean fallback) {
    final Object value = setting(name);
    return value instanceof Boolean b ? b : fallback;
  }

  // Number(setting) || fallback: anything that is not a positive number is the default.
  private static int positive(Object value, int fallback) {
    if (value instanceof Number n && n.intValue() > 0) {
      return n.intValue();
    }
    if (value instanceof String s) {
      try {
        final int parsed = Integer.parseInt(s.trim());
        if (parsed > 0) {
          return parsed;
        }
      } catch (NumberFormatException e) {
        // the default
      }
    }
    return fallback;
  }

  /** The spellings the tag of a version may have, release-please's configured one first. */
  List<String> tagCandidates(String version) {
    final String v = includeVInTag ? "v" : "";
    final String prefix = includeComponentInTag && !component.isEmpty() ? component + tagSeparator : "";
    final List<String> candidates = new ArrayList<>(Arrays.asList(prefix + v + version, version, "v" + version));
    if (!component.isEmpty()) {
      candidates.add(component + tagSeparator + version);
      candidates.add(component + tagSeparator + "v" + version);
    }
    return candidates;
  }

  /** The globs, in order, that find the nearest earlier tag in the configured spelling. */
  List<String> tagGlobs() {
    final String v = includeVInTag ? "v" : "";
    final List<String> globs = new ArrayList<>();
    if (includeComponentInTag && !component.isEmpty()) {
      globs.add(component + tagSeparator + v + "[0-9]*");
    }
    globs.add(v + "[0-9]*");
    globs.add("[0-9]*");
    globs.add("v[0-9]*");
    return globs;
  }

  /** The tag release-please spells for a version. */
  String tagOf(String version) {
    final String v = includeVInTag ? "v" : "";
    return includeComponentInTag && !component.isEmpty() ? component + tagSeparator + v + version : v + version;
  }

  /**
   * release-please's CommitExclude.shouldInclude for the package, negated: a commit is
   * excluded when every file it lists sits under an excluded path, so with exclude-paths
   * configured a commit that lists no file (an empty commit) is excluded too.
   */
  boolean excludedByPaths(List<String> files) {
    if (!excludeConfigured) {
      return false;
    }
    for (final String file : files) {
      boolean under = false;
      for (final String excluded : excludePaths) {
        if (file.startsWith(excluded + "/")) {
          under = true;
          break;
        }
      }
      if (!under) {
        return false;
      }
    }
    return true;
  }
}
