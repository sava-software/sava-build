'use strict';
// Run by action.yml before release-please. release-please reads commits through the
// GitHub API and drops, at debug level, every message its parser cannot read, so a
// commit's notes and BREAKING CHANGE footer vanish from the release while the run
// stays green (sava 1d846b0, 2026-10). This parses the same range with the same
// parser first and fails naming the commit. The range, the exclude-paths rule, the
// message splitting and the parser version follow release-please 17.6
// (release-please-action 5.0.0). guard.test.mjs covers the rules below; the releases
// API fallback and the search depth are not exercised there.
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');
const { parser, toConventionalChangelogFormat } = require('@conventional-commits/parser');

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
const readJson = (file) => JSON.parse(fs.readFileSync(file, 'utf8'));
const config = readJson(process.env.CONFIG_FILE);
const manifest = readJson(process.env.MANIFEST_FILE);
const pkg = (config.packages || {})['.'] || {};
const setting = (name, fallback) => pkg[name] ?? config[name] ?? fallback;
const version = manifest['.'];
const component = pkg.component || pkg['package-name'] || '';
const separator = setting('tag-separator', '-');
// release-please's normalizePaths: no leading or trailing slash. An empty list still
// counts as configured there, and excludes every empty commit.
const excludePathsSetting = setting('exclude-paths', null);
const excludeConfigured = excludePathsSetting != null;
const excludePaths = (excludePathsSetting ?? [])
  .map((p) => String(p).replace(/\/+$/, '').replace(/^\/+/, ''));
const SEARCH_DEPTH = Number(setting('commit-search-depth', 500)) || 500;

// The tag release-please cut for the manifest version, its own spelling first.
const candidates = [];
if (version) {
  const v = setting('include-v-in-tag', true) ? 'v' : '';
  const prefix = setting('include-component-in-tag', false) && component ? component + separator : '';
  candidates.push(prefix + v + version, version, 'v' + version);
  if (component) candidates.push(component + separator + version, component + separator + 'v' + version);
}
const tagExists = (tag) => {
  try { git('rev-parse', '-q', '--verify', `refs/tags/${tag}^{commit}`); return true; } catch { return false; }
};
let base = candidates.find(tagExists) ?? null;
let baseText = base ? `tag ${base} (manifest version ${version})` : null;
if (!base && version) {
  // A release-merge run: the manifest names the version being released and its tag
  // does not exist yet, so the nearest earlier tag in the configured spelling is the
  // base, read locally rather than from the releases API, which can fail transiently.
  const v = setting('include-v-in-tag', true) ? 'v' : '';
  const globs = [];
  if (setting('include-component-in-tag', false) && component) globs.push(`${component}${separator}${v}[0-9]*`);
  globs.push(`${v}[0-9]*`, '[0-9]*', 'v[0-9]*');
  for (const glob of globs) {
    try {
      const tag = git('describe', '--tags', '--abbrev=0', `--match=${glob}`, 'HEAD').trim();
      if (tag) { base = tag; baseText = `nearest tag ${tag} (manifest version ${version} has no tag yet)`; break; }
    } catch { /* no tag of that spelling */ }
  }
}
if (!base && process.env.GH_TOKEN && process.env.GITHUB_REPOSITORY) {
  try {
    const latest = execFileSync('gh',
      ['api', `repos/${process.env.GITHUB_REPOSITORY}/releases/latest`, '--jq', '.tag_name'],
      { encoding: 'utf8' }).trim();
    if (latest && tagExists(latest)) { base = latest; baseText = `latest GitHub release ${latest}`; }
  } catch { /* no release yet */ }
}
const shas = git('rev-list', `--max-count=${SEARCH_DEPTH}`, base ? `${base}..HEAD` : 'HEAD')
  .split('\n').filter(Boolean);
if (!base) {
  console.log(`::notice::no release tag found for manifest version ${version ?? '(none)'}; ` +
    `checking the last ${shas.length} commit(s), as release-please does before a first release`);
}

// release-please's splitMessages, verbatim: one message may carry several commits.
function splitMessages(message) {
  const parts = message.split('BEGIN_NESTED_COMMIT');
  const messages = [parts.shift()];
  for (const part of parts) {
    const [newMessage, ...rest] = part.split('END_NESTED_COMMIT');
    messages.push(newMessage);
    messages[0] = messages[0] + rest.join('END_NESTED_COMMIT');
  }
  const conventionalCommits = messages[0]
    .split(/\r?\n\r?\n(?=(?:feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(?:\(.*?\))?: )/)
    .filter(Boolean);
  return [...conventionalCommits, ...messages.slice(1)];
}

// The files GitHub lists for a commit: against the first parent, as release-please reads
// them. NUL-separated, so no path is quoted whatever bytes it holds.
function changedFiles(sha) {
  const parents = git('rev-list', '--parents', '-n', '1', sha).trim().split(/\s+/).slice(1);
  const listing = parents.length === 0
    ? git('diff-tree', '--root', '-r', '--no-commit-id', '--name-only', '-z', sha)
    : git('diff', '--name-only', '-z', parents[0], sha);
  return listing.split('\0').filter(Boolean);
}
// release-please's CommitExclude.shouldInclude for the root package, negated: a commit
// is excluded when every file it lists sits under an excluded path, so with
// exclude-paths configured a commit that lists no file (an empty commit) is excluded too.
const excludedByPaths = (files) =>
  excludeConfigured && files.every((file) => excludePaths.some((path) => file.startsWith(`${path}/`)));

// The same grammar decides error against notice: a part whose header line alone parses
// is a conventional commit release-please would have read. The pattern keeps the one
// header shape the grammar rejects but release-please still drops with its notes: an
// empty scope, or a scope holding a parenthesis.
const HEADER = /^[A-Za-z]+(\([^)]*\))?!?: /;
const headerParses = (header) => { try { parser(header); return true; } catch { return false; } };
const conventional = (header) => headerParses(header) || HEADER.test(header);
const oneLine = (error) => String(error && error.message ? error.message : error)
  .replace(/\r?\n/g, '<LF>').replace(/\s+/g, ' ').trim();

// Parse first, then judge: a Restates footer counts only on a part release-please reads,
// since a carrier it drops never puts its restatement into the notes either.
const RESTATES = /^Restates:\s*([0-9a-f]{7,40})\s*$/gim;
const parsed = [];
const excluded = [];
for (const sha of shas) {
  const message = git('log', '-1', '--format=%B', sha);
  if (excludeConfigured && excludedByPaths(changedFiles(sha))) {
    excluded.push({ sha, header: message.split('\n')[0] });
    continue;
  }
  for (const part of splitMessages(message)) {
    // trimmed, so a nested commit's header is its first line and not the blank one
    const text = part.trim();
    const header = text.split(/\r?\n/)[0];
    try {
      toConventionalChangelogFormat(parser(text));
      parsed.push({ sha, header, part: text, error: null });
    } catch (error) {
      parsed.push({ sha, header, part: text, error: oneLine(error) });
    }
  }
}
const restated = parsed.filter((p) => p.error === null)
  .flatMap((p) => [...p.part.matchAll(RESTATES)].map((m) => m[1].toLowerCase()));
const failures = [];
const dropped = [];
for (const { sha, header, error } of parsed) {
  if (error === null) continue;
  const acknowledged = restated.some((prefix) => sha.startsWith(prefix));
  if (conventional(header) && !acknowledged) failures.push({ sha, header, reason: error });
  else dropped.push({ sha, header, acknowledged });
}
for (const { sha, header } of excluded) {
  console.log(`release-please excludes ${sha.slice(0, 7)} (every listed file is under exclude-paths, ` +
    'or it lists none): ' + header);
}
for (const { sha, header, acknowledged } of dropped) {
  console.log(`release-please will not read ${sha.slice(0, 7)} ` +
    `(${acknowledged ? 'restated by a later commit' : 'not a conventional commit'}): ${header}`);
}
if (dropped.length) {
  console.log(`::notice::release-please will not read ${dropped.length} of the ${shas.length} ` +
    'commit(s) in this range (not conventional commits, or restated by a later commit); ' +
    'the step log lists them');
}
for (const { sha, header, reason } of failures) {
  console.log(`::error::release-please cannot parse ${sha} and would drop it from the release ` +
    `notes, its BREAKING CHANGE footer included: "${header}" (${reason}). A body or footer line ` +
    "whose leading run (no whitespace, '(', ')', ':' or '!'; backticks count) runs straight into " +
    "'(' fails unless a ')' closes it before the next '(' or the end of the line: indent the line " +
    "or put a space before the '('. Push a commit that restates the header, body and footers in " +
    `that form and ends with the footer "Restates: ${sha.slice(0, 7)}"` +
    (excludeConfigured ? '; with exclude-paths configured that commit must change a file ' +
      'outside them, since release-please excludes an empty commit' : '') +
    '. While this run is red the release PR is not refreshed: restate first, then merge.');
}
console.log(`checked ${shas.length} commit(s) since ${baseText ?? 'the start of history'}: ` +
  `${failures.length} unparseable conventional commit(s), ${dropped.length} release-please will not read` +
  (excludeConfigured ? `, ${excluded.length} excluded by exclude-paths` : ''));
process.exit(failures.length ? 1 : 0);
