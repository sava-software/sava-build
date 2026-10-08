'use strict';
// Run by action.yml before release-please. release-please reads commits through the
// GitHub API and drops, at debug level, every message its parser cannot read, so a
// commit's notes and BREAKING CHANGE footer vanish from the release while the run
// stays green (sava 1d846b0, 2026-10). This parses the same range with the same
// parser first and fails naming the commit. The range, the message splitting and the
// parser version follow release-please 17.6 (release-please-action 5.0.0).
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
if (!base && process.env.GH_TOKEN && process.env.GITHUB_REPOSITORY) {
  try {
    const latest = execFileSync('gh',
      ['api', `repos/${process.env.GITHUB_REPOSITORY}/releases/latest`, '--jq', '.tag_name'],
      { encoding: 'utf8' }).trim();
    if (latest && tagExists(latest)) { base = latest; baseText = `latest GitHub release ${latest}`; }
  } catch { /* no release yet */ }
}
const SEARCH_DEPTH = 500; // release-please's default commit-search-depth
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

const HEADER = /^[A-Za-z]+(\([^)]*\))?!?: /;
const RESTATES = /^Restates:\s*([0-9a-f]{7,40})\s*$/im;
const messages = shas.map((sha) => ({ sha, message: git('log', '-1', '--format=%B', sha) }));
const restated = messages.flatMap(({ message }) => {
  const match = RESTATES.exec(message);
  return match ? [match[1].toLowerCase()] : [];
});
const failures = [];
const dropped = [];
for (const { sha, message } of messages) {
  for (const part of splitMessages(message)) {
    const header = part.split('\n')[0];
    try {
      toConventionalChangelogFormat(parser(part));
    } catch (error) {
      const reason = String(error.message ?? error).split('\n')[0];
      const acknowledged = restated.some((prefix) => sha.startsWith(prefix));
      if (HEADER.test(header) && !acknowledged) failures.push({ sha, header, reason });
      else dropped.push({ sha, header, acknowledged });
    }
  }
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
    `notes, its BREAKING CHANGE footer included: "${header}" (${reason}). Push an empty commit ` +
    `that restates its header, body and footers in a form the parser reads (a body line ` +
    `beginning with word( and holding a nested parenthesis is read as a type(scope) header: ` +
    `indent or reword it) and ends with the footer "Restates: ${sha.slice(0, 7)}".`);
}
console.log(`checked ${shas.length} commit(s) since ${baseText ?? 'the start of history'}: ` +
  `${failures.length} unparseable conventional commit(s), ${dropped.length} release-please will not read`);
process.exit(failures.length ? 1 : 0);
