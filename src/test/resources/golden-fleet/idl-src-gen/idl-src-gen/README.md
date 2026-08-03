# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy — the three legal
outcomes for a new survivor, determinism requirements, targeting rules —
lives in sava-build's `HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Line numbers are part of the baseline key, so edits to a
mutated file shift entries — pure drift (every new row a same-status shift,
populations unchanged) passes on its own with a notice; anything mixed in is
triage first, refresh after. The comparison is a **multiset**: identical rows
are sibling mutants of one compound condition — never hand-dedupe. Prefer
`-PunionMutationBaseline` (append-only) over a full update when insuring
observed rows.

Every row carries a trailing family label (`… # logging-only`), fully
labeled 2026-07-23: `# untriaged` marks the debt bucket (refreshes seed it on
every genuinely new row), and each argued acceptance carries the label of the
family below that holds its full argument — the verify prints the per-label
breakdown, so triage state is a number the build reports. Triage means
replacing `# untriaged` with a family label (adding the family here if it is
new). Refreshes carry labels across status flips and pure line shifts; when
several same `(class, method, mutator, status)` rows shift at once the carry
can shuffle labels between them — audit against `-PlistUnkilled` after a
drift refresh (see docs/hardening.md).

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18, replacing the untriaged seed baselines. Every `SURVIVED`
row in both CSVs is one of these; grouped by the principle that makes it
equivalent.

**Logging / diagnostics only** (`# logging-only`) — removing the call cannot change any
contract the caller observes:
- `LegacyAnchorIdlConverter.fixConvertedDiscriminators:182` (`logger.log` of
  a corrected discriminator).
- `ParseUtil.decompress:306` (DEBUG log of the base64 payload on the way
  into the thrown `UncheckedIOException`; the throw itself is asserted by
  `decompressFailsFastOnMalformedPayload`). Was `System.err.println` until
  2026-07-24: an unconditional dump of attacker-writable account bytes is a
  log-flooding vector on the server path — found when a fuzz campaign's log
  hit 600 MB of base64.

**Redundant fast-path guards** (`# redundant-guard`) — the skipped guard routes to a general path
that produces the identical result (often the same string instance):
- `ParseUtil.escapeInvalidJavaDocTags:114` `isEmpty` skip (an empty string
  has no `@`, so the scan returns it unchanged) and the `indexOf('@') < 0`
  skip (a scan of `@`-free content never allocates and returns the input).
- `ParseUtil.removeInvalidMarkdownChars:173` `isEmpty` skip (a zero-length
  loop returns the input).

**Identity writes** (`# identity-write`) — the mutant makes the code do explicitly what already
holds: `ParseUtil.cleanName:246,252` (`c != '_'` forced: replaces an
underscore *with* an underscore).

**Skip-evaluation guards** (`# skip-eval-guard`) — `removeInvalidMarkdownChars:192,199,206,214`
`if (keep)` chain: rule bodies only ever clear `keep`, so evaluating a later
rule for an already-dropped character cannot resurrect it.

**Allocation-only arithmetic** (`# allocation-only`) — `escapeInvalidJavaDocTags:132`
(`StringBuilder` capacity).

**Unreachable boundaries / defense-in-depth** (`# defense-in-depth`):
- `SerDeUtil.writeVal:141` negative-value guard: every caller passes an
  array length or a 0/1 presence flag, never a negative value.
- `SerDeUtil.fixedLengthString:104` all-noise window: the loop exits with
  `i == from - 1`, so the else-branch builds an empty string and the
  `isBlank` check returns the same null.

**Compilation artifacts with no source-level counterpart** (`# javac-or-chain`) —
`ParseUtil.sanitizeVariableName:271,286`: every first/inner character class
(letter, `_`, `$`, digit, other) is asserted at offset zero and behind a
shifted offset (`ParseUtilTests`, `AnchorPDATest`); the surviving branch
flips inside javac's OR-chain lowering change no asserted partition.

**Empty-collection collapse with an identical observable result** (`# empty-collapse`) — the
guard picks a shared empty singleton, and the general branch builds an
equal-but-distinct empty collection:
- `ProgramConfigParser.test:664` `excludeTypes`: `Set.copyOf` of an empty
  set returns the same canonical `Set.of()` instance the guard returns, so
  no assertion can separate them. (The sibling `ignoreInstructions` guard at
  `:650` wraps in `unmodifiableSet` instead and *is* killed, by `assertSame`
  in `emptyCollectionsCollapseToSharedEmpties`.)
- `ConfigParser.createConfig:741` empty program list: both branches feed
  `Collections.unmodifiableList`, so neither identity nor equality can
  distinguish `List.of()` from a wrapped empty `ArrayList`.

**`Path.resolve` of an absolute path is the identity** (`# resolve-identity`) — forcing the
`isAbsolute()` guard routes an already-absolute path through
`workDirectory.resolve(...)`, which returns it unchanged:
`ProgramConfigParser.createConfig:538` (idlFile) and
`Entrypoint.resolveCrateDirectory:813` (source directory).

**Process-harness acceptances (2026-07-23)** — `LegacyAnchorIdlConverter.convert`
is driven by `LegacyAnchorIdlConverterProcessTests` against stub `anchor`
executables; the surviving mutants inside it are grouped by family:
- *Logging/diagnostics only* (`# logging-only`): `convert:119,122,130` (`logger.log` of timeout /
  exit-code / missing-CLI warnings), `lambda$fixConvertedDiscriminators$0:192`
  (the unpatched-entry warning), and `convert:115`
  (`redirectErrorStream` — the merged stderr only feeds the warning log).
- *Unobservable cleanup* (`# unobservable-cleanup`): `convert:118` (`destroyForcibly` of the timed-out
  child — the orphan is invisible to the caller's contract; under load this
  row can read `TIMED_OUT` instead of `SURVIVED`, since its removal leaves the
  open-stdout stub child running — keep the baseline row through such flips),
  `convert:136,137`
  (`deleteQuietly` of the temp files), and the `deleteQuietly:212` null-guard
  pair (defends the createTempFile-failure path no harness reaches).
- *Error funnel* (`# error-funnel`): the `convert:121` conditional pair — removing the
  `Files.notExists(out)` guard fails into the `IOException` catch and produces
  the identical `null`; observed identical through the zero-exit-no-output
  test, which passes with either operand forced.
- *Unreachable in-harness* (`# unreachable-in-harness`): `convert:87` (the two-arg delegate binds the real
  `anchor` CLI name — exercising it runs whatever the machine's PATH holds)
  and `convert:133` (the `InterruptedException` re-interrupt — reaching it
  deterministically depends on JDK-specific NIO/process interrupt behaviour,
  and a flaky harness is worse than recorded debt).

One `convert` mutant is detected only by PIT's timeout — stable solo and
under load, observed 2026-07-23 after the harness test expansion: dropping
the `waitFor` timeout guard (`convert:121` at time of writing) sends the
timeout-scenario stub child's held-open stdout into an unbounded
`readAllBytes`, so the hang *is* the mutation's observable effect, and the
comment above the guard argues why the read must stay unbounded — no rewrite
can turn this one into a deterministic kill. `TIMED_OUT` is not baselined;
if the coordinate ever reads `SURVIVED`, treat it like the `destroyForcibly`
flip above: union it with a note rather than chasing it with timing tricks.
This is the sole member of the audited timeout set
(`jsonParse-timeouts.csv`, adopted with sava-build 21.5.17): the watchdog
observes slowness, not wrongness, so for this mutant the ratchet cannot see
a weakened covering assertion — this paragraph is the compensating written
cause the set's key (`LegacyAnchorIdlConverter,convert,RemoveConditionalMutator_EQUAL_ELSE`)
points at. Any other timed-out mutant will draw the verify's unaudited-set
warning: identify its structural cause and write it here before pasting the
printed row into the set.

**Unreachable in-harness: the system half of a time seam** (`# unreachable-in-harness`) —
`Entrypoint$Clock$1.currentTimeMillis:58` (long return → 0) and
`Entrypoint$Clock$1.sleep:63` (dropped `TimeUnit::sleep`): `Clock.SYSTEM` is
the two-line delegation to real time that the clock seam exists to keep *out*
of tests — the pacing logic behind it is fully killed through the injected
test clock (`EntrypointPacingTests`, 2026-07-23). What would reach these: only
a real fetch run against a remote IDL host, which is nondeterministic by
definition. Re-visit only if the seam is ever widened.

**IDL selection, and the fetch loop it runs inside (2026-07-25)** — `Entrypoint.run`
gained a harness: `EntrypointIDLSelectionTests` drains a task queue on the test
thread over programs whose IDL is a local `idlFile`, which is what made the
Associated-Token selection (`IDLResult.select`, `compareVersions`,
`generatedAdditionalPrograms`) killable and shrank the `run`/`parseIDL`
`NO_COVERAGE` bucket. The branches that path deliberately does not take now read
`SURVIVED` instead of `NO_COVERAGE` — a strict improvement (they execute; the
harness simply cannot vary them), and each names what would reach it:
- *Logging/diagnostics only* (`# logging-only`): `IDLResult.select` logs the
  unmatched-`"idlProgram"` warning and the "described by both IDLs, generating from
  this one" notice; `ProgramConfig.parseIDL` logs the no-IDL-found error. The
  selection and the `null` return they accompany are asserted by
  `anUnmatchedIdlProgramFallsBackToTheDefaultOrder` and
  `aProgramWithoutAnIdlIsSkipped`.
- *Unreachable in-harness* (`# unreachable-in-harness`), each needing an
  orchestration harness this suite does not have:
  - `Entrypoint.run` `idlHost != null` forced false — the remote-fetch branch; the
    opposite direction is killed (an `idlURL` program with no semaphore fails
    loudly). Reaching it needs a local HTTP server serving an IDL.
  - `ProgramConfig.parseIDL` `syncIdl` forced false — the on-chain sync branch;
    needs a stubbed `SolanaRpcClient` plus an IDL account payload.
  - `ProgramConfig.parseIDL` `accountInfo == null` forced true — the same, from the
    other side: every config here reaches its local file, so `idlAccounts` is empty
    and the mutant agrees with reality.
  - `ProgramConfig.parseIDL` `converted != jsonBytes` and
    `ProgramConfig.convertLegacyIdl` `idlType != anchor` — both only diverge for a
    *legacy anchor* IDL, whose conversion is the `anchor` CLI subprocess; for every
    IDL the harness parses, `convertLegacyIdl` returns its argument and the
    re-write is skipped either way.

**Unreachable because an earlier assignment removes the case** (`# earlier-assignment`) —
`Entrypoint.resolveCrateDirectory:811` `sourceDirectory == null`: both call
sites sit in the rust branch of `createConfig`, which assigns the
`deps/gen/<name>` default before either call, so the method never observes a
null source directory. (`resolveSourceDirectory`, the java-path sibling,
genuinely does and is covered.)

## Mutator-set trial (2026-07-21)

Per HARDENING.md ("the mutator set bounds what the ratchet can see"),
`EXPERIMENTAL_BIG_INTEGER` was trialed on both suites: `serDe` 1670 → 1671
generated (the one new mutant, `SerDeUtil.toUnsignedBigInteger:169`, is
killed), `jsonParse` 609 → 609 — zero fires. Enabled on `serDe` only: the
enable is free (no baseline change, one more expressible defect covered);
enabling on `jsonParse` would be churn for a mutator that cannot fire there.
Re-trial `jsonParse` if Big arithmetic is introduced in the generator packages.

`EXPERIMENTAL_BIG_DECIMAL` was **not** trialed, and the omission is measured
rather than forgotten: `BigDecimal` appears nowhere in `src/main/java`
(`grep -rl BigDecimal` is empty), so the mutator has no call site to rewrite in
either suite. Trial it if `BigDecimal` is ever introduced.

## Mutator-set trial: `EXPERIMENTAL_NAKED_RECEIVER` (2026-07-23)

Fluent calls returning their receiver are expressions, invisible to
`VoidMethodCallMutator`. Trialed via the plugin's scripted
`pitestMutatorTrial -PtrialMutators=EXPERIMENTAL_NAKED_RECEIVER`; fired in
both suites and is enabled on both:

- **`serDe`**: 1 generated, 1 killed by existing tests. Free enable
  (1671 → 1672 total mutants).
- **`jsonParse`**: 65 generated — 31 killed by existing tests, 3 `SURVIVED`,
  31 `NO_COVERAGE` (609 → 676 total, with the STRONGER siblings the multiset
  now counts). All three survivors were genuinely untested behaviour, killed
  with new tests rather than accepted:
  - `ParseUtil.parseDocs:74,79` — dropped `String::strip` on segments around
    embedded newlines (`parseDocsStripsPaddingAroundEmbeddedNewLines`).
  - `Entrypoint$ConfigParser.lambda$createConfig$0:762` — dropped
    `RustExtensionConfig::withDefaultSecurityTxt`, i.e. extensions silently
    losing the config-level default security txt
    (`extensionsInheritTheDefaultSecurityTxt`).

  The 31 `NO_COVERAGE` all land inside the documented untriaged debt bucket
  below (`Entrypoint`/`ProgramConfig` +27, `LegacyAnchorIdlConverter` +3,
  `BootstrapMetadataProgram` +1) and were unioned into the baseline with
  `-PunionMutationBaseline` — growth whose reason is this mutator widening,
  not new untested code.

## Multiset migration (2026-07-23)

The pre-multiset baseline held unique rows, undercounting sibling mutants of
compound conditions. The first run under the multiset-comparing plugin
surfaced 34 such sibling rows (all `NO_COVERAGE`, all in the untriaged debt
bucket, `RemoveConditional`/`NakedReceiver` pairs on already-accepted
coordinates); they were unioned in the same `-PunionMutationBaseline` pass as
the trial's rows. Baseline 261 → 326 rows: 65 added, 0 dropped, 0 newly
covered, 0 unexplained.

## Baseline verification (2026-07-21)

Run against HARDENING.md's convergence method, since both suites' baselines had
just been refreshed:

- **Converged.** Two solo `pitestJsonParse` runs with the report directory
  deleted in between agree on **per-mutant status** across all 525 unique
  `(class, method, line, mutator)` keys — not merely on the headline number.
- **No mode flips.** Per-mutant status is identical between the solo runs and
  the multi-suite `qualityGate` run, for both suites. Nothing needs unioning
  into a baseline for a `TIMED_OUT` flip.
- ~~**One stable `TIMED_OUT`**: `ParseUtil.parseDocs:72` (`MathMutator`)~~
  Resolved 2026-07-21: the `parseDocs` split loop advanced its position by
  re-running `indexOf` from a derived offset, so a mutant that failed to
  advance re-found the same newline and hung — detected only by PIT's
  load-dependent timeout. Rewritten as a char scan whose loop index advances
  independently of the segment bookkeeping: every mutant of the bookkeeping
  now throws (`charAt(-1)`) or corrupts an exactly-asserted segment list, and
  the suite reports **no `TIMED_OUT` at all**. The rewrite also refactored the
  previously-accepted `parseDocs:74` `newLine >= 0` boundary mutant out of
  existence (baseline 262 → 261). Any *new* `TIMED_OUT` in either suite is now
  unexplained by default and worth chasing immediately.
- **No dead accepted rows.** Every row in both `*-accepted.csv` matches a real
  unkilled mutant in at least one mode, so neither baseline is widening the gate
  for nothing.

## Untriaged debt

All remaining `NO_COVERAGE` rows (jsonParse only; serDe has none), counts as
of the 2026-07-23 mutator widening, multiset migration, clock seam, and
converter process harness (283 total):
- `Entrypoint` + `ProgramConfig` (~230) — CLI/orchestration: filesystem
  layout, network fetch loops, on-chain IDL sync. Needs an end-to-end
  harness with a stubbed filesystem/HTTP layer before unit-level mutation
  coverage is meaningful. The pure config-parsing layer underneath
  (`ProgramConfigParser`, `ConfigParser`, `CommonsParser`) was split out of
  this bucket and is now covered by `ProgramConfigParserTests` and
  `ConfigParserTests`; `run` and the local-`idlFile` half of `parseIDL` were
  split out 2026-07-25 by `EntrypointIDLSelectionTests` (see the selection
  section above). What is left in `run`/`parseIDL` is the network, on-chain
  and legacy-conversion branches that harness cannot vary.
- `LegacyAnchorIdlConverter.convert` (2) — resolved 2026-07-23 by the stub-CLI
  process harness (`LegacyAnchorIdlConverterProcessTests`); what remains is
  the accepted unreachable-in-harness pair documented above. The pure logic
  (`isLegacy`, `fixConvertedDiscriminators`) is fully covered and killed.
- `BootstrapMetadataProgram.main` (5) — a bootstrap entry point.

The `anchor`/`codama` parser packages and the source renderers are deliberate
suite exclusions (see `build.gradle.kts`), verified end-to-end by the
generated-source tests — promote them to their own suites when unit tests
grow to kill their mutants rather than baselining them wholesale.

Shrinking the baseline is always an improvement; growing it requires a
reason here.
