// node --test, from this directory after `npm ci`: builds scratch repositories and runs
// guard.js the way action.yml does. Each case names the release-please behaviour it mirrors.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, mkdirSync } from 'node:fs';
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
  const run = () => {
    const r = spawnSync('node', [GUARD], {
      cwd: dir, encoding: 'utf8',
      env: { ...process.env, GH_TOKEN: '', GITHUB_REPOSITORY: '',
        CONFIG_FILE: join(dir, 'release-please-config.json'),
        MANIFEST_FILE: join(dir, '.release-please-manifest.json') },
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
