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
    excluded.push({ sha, header: message.split(/\r\n|\r|\n/)[0] });
    continue;
  }
  for (const part of splitMessages(message)) {
    // trimmed, so a nested commit's header is its first line and not the blank one. A lone
    // CR ends the header too: the runner ends a log line there, so a header printed whole
    // could put a workflow command at the start of one.
    const text = part.trim();
    const header = text.split(/\r\n|\r|\n/)[0];
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

// Release versions. The publishing workflows refuse a tag that is not a plain X.Y.Z
// version, and release-please decides each tag from a merged release pull request: its
// title, or its body (more than one release section, or a body re-read from a branch file
// when it carries the overflow message), read through a parse, serialize and parse again.
// A Release-As footer, a BEGIN_COMMIT_OVERRIDE block, the config's release-as, the
// manifest, contributor commit text in the body and an edited title can each steer it.
// Rather than re-derive that, this runs release-please's own release-building code
// (Manifest.fromManifest and buildReleases, from the release-please package at the version
// release-please-action bundles) over a GitHub client that can only read. Each tag it would
// cut must be a plain X.Y.Z version and carry the version the release pull request wrote
// into the manifest at its own commit, so a body that substitutes another plain version
// fails too. And release-please must read nothing for a pull request but its overflow file
// from <head branch>--release-notes, the only place it writes one: a link in the body to
// any other ref names a file someone else controls, which can change between this check
// and the release. A merged release pull request about to be tagged fails the run; an
// open one, built as if merged, is a warning.
const { Manifest, setLogger } = require('release-please');
const nodePath = require('node:path');
const PLAIN_VERSION = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const RELEASE_LABELS = config.label != null ? String(config.label).split(',') : ['autorelease: pending'];
const labelText = RELEASE_LABELS.map((label) => `'${label}'`).join(' and ');
// Where a wrong version comes from, and how each source is overridden.
const REMEDY = 'a Release-As footer gives way to a later commit with a valid one (the newest wins); release-as ' +
  'is set in the config; release sections, an overflow message or a link in the body come from commit text, which ' +
  'a BEGIN_COMMIT_OVERRIDE block in the description of the pull request that merged that commit replaces.';
// A failed gh call: the API, not the pull requests, so re-running can help.
class GitHubReadError extends Error {}
// What a pull request names does not exist: re-running cannot help.
class MissingFileError extends Error {}
// A GitHub client for release-please that only reads, through gh, the way release-please's
// own client reads (github-api.ts pullRequestIteratorWithoutFiles; github.ts
// getFileContentsOnBranch and getFileJson). Nothing else is provided, so release-please
// calling anything else fails the check. Every file read is recorded with the pull request
// release-please was reading at the time: it reads a pull request's overflow file before
// it asks for the next one. With openAsMerged, open pull requests are listed as merged at
// their head commit, to see what each would be tagged once merged.
function readOnlyGitHub(repository, defaultBranch, { openAsMerged = false } = {}) {
  const [owner, repo] = repository.split('/');
  const reads = [];
  let current = null;
  const api = (query, accept) => {
    try {
      return execFileSync('gh', ['api', ...(accept ? ['-H', `Accept: ${accept}`] : []), query],
        { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    } catch (error) {
      throw new (/HTTP 404/.test(String(error.stderr ?? error.message)) ? MissingFileError : GitHubReadError)(
        oneLine(error));
    }
  };
  const getFileContentsOnBranch = async (path, branch) => {
    reads.push({ pullRequest: current, path, branch });
    try {
      const text = api(`repos/${repository}/contents/${path.split('/').map(encodeURIComponent).join('/')}` +
        `?ref=${encodeURIComponent(branch)}`, 'application/vnd.github.raw');
      return { sha: '', content: Buffer.from(text).toString('base64'), parsedContent: text, mode: '100644' };
    } catch (error) {
      if (!(error instanceof MissingFileError)) throw error;
      throw new MissingFileError(`${path} does not exist on ${JSON.stringify(branch)}` +
        (current ? `, which release pull request #${current.number} makes release-please read` : ''));
    }
  };
  return {
    repository: { owner, repo, defaultBranch },
    reads,
    current: () => current,
    getFileContentsOnBranch,
    async getFileJson(path, branch) {
      return JSON.parse((await getFileContentsOnBranch(path, branch)).parsedContent);
    },
    async *pullRequestIterator(targetBranch, status = 'MERGED', maxResults = Number.MAX_SAFE_INTEGER) {
      const state = openAsMerged || status === 'OPEN' ? 'open' : 'closed';
      let results = 0;
      // current is cleared only when the listing ends normally: when release-please throws on
      // a pull request it closes this generator at the yield, and current still names it.
      for (let page = 1; ; page++) {
        const pulls = JSON.parse(api(`repos/${repository}/pulls?state=${state}&base=${encodeURIComponent(targetBranch)}` +
          `&sort=updated&direction=desc&per_page=100&page=${page}`));
        for (const pull of pulls) {
          if (!openAsMerged && status === 'MERGED' && !pull.merged_at) continue;
          results += 1;
          current = {
            headBranchName: pull.head.ref, baseBranchName: pull.base.ref, number: pull.number,
            title: pull.title, body: pull.body || '', labels: (pull.labels || []).map((label) => label.name),
            files: [], sha: openAsMerged ? pull.head.sha : pull.merge_commit_sha || undefined,
          };
          yield current;
          if (results >= maxResults) break;
        }
        if (results >= maxResults || pulls.length < 100) break;
      }
      current = null;
    },
  };
}
// Why a release release-please would cut is not safe to tag, or null.
function releaseProblem(release, expected, manifestPath) {
  const tag = release.tag.toString();
  const reasons = [];
  if (!PLAIN_VERSION.test(tag)) reasons.push('not a plain X.Y.Z version');
  if (release.tag.version.toString() !== expected) {
    reasons.push(`not ${JSON.stringify(expected ?? null)}, the version the release pull request wrote into ` +
      manifestPath);
  }
  return reasons.length ? `release-please would tag it ${JSON.stringify(tag)}, which is ${reasons.join(', and ')}` : null;
}
// Reads release-please made for a pull request from anywhere but where it writes: its
// overflow file from <head branch>--release-notes, anything else from the target branch.
const foreignReads = (reads, targetBranch) => reads.filter(({ pullRequest, path, branch }) => pullRequest &&
  branch !== (path === 'release-notes.md' ? `${pullRequest.headBranchName}--release-notes` : targetBranch));
const describeForeignRead = ({ pullRequest, path, branch }) =>
  `merged release pull request #${pullRequest.number} makes release-please read ${path} from ` +
  `${JSON.stringify(branch)}, not from ${JSON.stringify(`${pullRequest.headBranchName}--release-notes`)}, ` +
  'where release-please writes an overflowing body; a link in the body named that ref, and its file can change ' +
  `between this check and the release. Nothing has been tagged. Remove the link from #${pullRequest.number}'s ` +
  `body, or remove ${labelText} from it to skip the release; then ${REMEDY}`;
async function checkReleases(repository) {
  const quiet = () => {};
  setLogger({ error: quiet, warn: quiet, info: quiet, debug: quiet, trace: quiet });
  // release-please targets the repository's default branch unless told otherwise.
  const targetBranch = process.env.TARGET_BRANCH || process.env.GITHUB_REF_NAME;
  if (!targetBranch) throw new Error('no target branch: TARGET_BRANCH and GITHUB_REF_NAME are empty');
  // release-please reads the config and the manifest by repository path, as given: a
  // relative path is used unchanged; an absolute one is made relative to the repository.
  const repoPath = (file) => {
    if (!nodePath.isAbsolute(file)) return nodePath.posix.normalize(file.split(nodePath.sep).join('/'));
    const top = fs.realpathSync(git('rev-parse', '--show-toplevel').trim());
    return nodePath.relative(top, fs.realpathSync(file)).split(nodePath.sep).join('/');
  };
  const configPath = repoPath(process.env.CONFIG_FILE);
  const manifestPath = repoPath(process.env.MANIFEST_FILE);
  // The manifest at a release's own commit: the merge commit, or an open pull request's head.
  const manifests = new Map();
  const manifestAt = async (github, sha) => {
    if (!manifests.has(sha)) manifests.set(sha, await github.getFileJson(manifestPath, sha));
    return manifests.get(sha);
  };
  // Builds the releases. A failure while release-please reads a pull request, other than the
  // API failing, names that pull request, since release-please would fail on it the same
  // way; a read from a foreign ref before the failure is reported as such.
  const build = async (github) => {
    try {
      return await (await Manifest.fromManifest(github, targetBranch, configPath, manifestPath)).buildReleases();
    } catch (error) {
      const pr = github.current();
      if (error instanceof GitHubReadError || !pr) throw error;
      const foreign = foreignReads(github.reads, targetBranch).map(describeForeignRead);
      throw new Error(foreign.length ? foreign.join(' ') : `release-please fails on release pull request ` +
        `#${pr.number} (${oneLine(error)}), and would fail the same way when releasing; fix that pull request's ` +
        `title or body, or remove ${labelText} from it`);
    }
  };
  const errors = [];
  const mergedGitHub = readOnlyGitHub(repository, targetBranch);
  const merged = await build(mergedGitHub);
  errors.push(...foreignReads(mergedGitHub.reads, targetBranch).map(describeForeignRead));
  for (const release of merged) {
    let expected;
    try {
      expected = (await manifestAt(mergedGitHub, release.sha))[release.path];
    } catch (error) {
      if (!(error instanceof MissingFileError)) throw error;
      throw new Error(`release pull request #${release.pullRequest.number}'s merge commit has no readable ` +
        `${manifestPath} (${oneLine(error)})`);
    }
    const problem = releaseProblem(release, expected, manifestPath);
    if (problem) {
      errors.push(`merged release pull request #${release.pullRequest.number} is about to be tagged, but ${problem}. ` +
        'The publishing workflows refuse a tag that is not a plain X.Y.Z version or not the version of its ' +
        `commit's manifest. Nothing has been tagged. Edit #${release.pullRequest.number} so release-please reads the ` +
        "manifest's version from it (its title, with no release section, overflow message or link in its body), " +
        `then re-run this job; or remove ${labelText} from it to skip the release. Then fix where the version came ` +
        `from, or the next release repeats it: ${REMEDY}`);
    }
  }
  const openGitHub = readOnlyGitHub(repository, targetBranch, { openAsMerged: true });
  let open = [];
  try {
    open = await build(openGitHub);
  } catch (error) {
    console.log(`::warning::open release pull requests not checked: ${oneLine(error)}`);
  }
  for (const { pullRequest, path, branch } of foreignReads(openGitHub.reads, targetBranch)) {
    console.log(`::warning::open release pull request #${pullRequest.number} makes release-please read ${path} ` +
      `from ${JSON.stringify(branch)}; merged as it is, this guard fails the release run. Fix the source: ${REMEDY}`);
  }
  for (const release of open) {
    let expected;
    try {
      expected = (await manifestAt(openGitHub, release.sha))[release.path];
    } catch (error) {
      console.log(`::warning::open release pull request #${release.pullRequest.number} not checked: ${manifestPath} ` +
        `at its head could not be read (${oneLine(error)})`);
      continue;
    }
    const problem = releaseProblem(release, expected, manifestPath);
    if (problem) {
      console.log(`::warning::open release pull request #${release.pullRequest.number}: once merged, ${problem}. ` +
        'Merged as it is, this guard fails the release run and nothing is tagged until the merged pull request is ' +
        `fixed. Fix where the version came from first: ${REMEDY} release-please rewrites the pull request on its ` +
        'next run.');
    }
  }
  console.log(`checked the releases release-please would cut: ${merged.length} from merged and ${open.length} ` +
    'from open release pull request(s)');
  return errors;
}
const repository = process.env.GITHUB_REPOSITORY;
(async () => {
  let releaseErrors = [];
  if (!repository) {
    console.log('releases not checked: GITHUB_REPOSITORY is not set');
  } else {
    try {
      releaseErrors = await checkReleases(repository);
    } catch (error) {
      console.log(error instanceof GitHubReadError
        ? `::error::could not read the release pull requests from GitHub (${oneLine(error)}); re-run the job.`
        : `::error::could not work out the releases release-please would cut: ${oneLine(error)}`);
      process.exit(1);
    }
  }
  for (const message of releaseErrors) console.log(`::error::${message}`);
  process.exit(failures.length || releaseErrors.length ? 1 : 0);
})();
