# Hardening process

The quality process for repos applying `software.sava.build.feature.hardening`:
what the tooling enforces, what requires judgment, and the conventions that
make both portable across codebases. The enforceable parts live in the plugin —
this document covers only the decisions the tooling cannot make for you.

Every rule here was earned from an observed incident; the incidents live in
`HARDENING_CASEBOOK.md`, cited as *(casebook: entry)*. Read an entry before
arguing with its rule — the counter-argument has usually been tried.

## Lifecycle

Verification is tiered by cost, and the tier is chosen by what the change can
affect — not by habit in either direction:

| When | Command | What it proves |
|---|---|---|
| Inner loop | the module's `test` (or `--tests` for the touched classes) | The change works. |
| Before handing off a change | the `pitest<Suite>`(s) whose mutated code the change can reach | No new unkilled mutants where the change lives. |
| Before a release | `qualityGate` on every module (`-PnoMutationHistory` if arcmutate history is active), long fuzz runs (`fuzz<Target> -PmaxFuzzTime=<seconds>`), jmh A/B vs the previous release | Nothing regressed anywhere; no parser crashes at depth; no performance regression. |

`qualityGate` = `test` + every registered `pitest<Suite>`, serialized, each
finalized by its baseline verification. Its cost scales with the repo's total
mutant population, not with the size of the diff — so running it per change
spends minutes re-learning results the change could not have moved. A suite
produces new information only when the change can alter code it mutates or
tests that cover that code.

Choosing the owning suites is a **reachability** question, not a file-path
one:

- the suite covering the edited files, plus any suite — including in a
  dependent module — whose mutated code calls the changed API.
- test-only edits still owe the suite those tests kill mutants in: a
  weakened or deleted test shows up as a new survivor, which is precisely
  what the ratchet is for. They owe nothing beyond it.
- doc, build-script, and comment changes owe no suite at all.

Both failure modes waste something real: per-change full gates turn an inner
loop into a queue, and never running the gate hides what only surfaces there
(`TIMED_OUT` flips under load, cross-module callers). The full gate runs
before anything is published — that is the requirement; *where* is a cost
decision. Wire it into CI if the runners can afford serialized PIT; otherwise
the release checklist owns a local run and CI stays on `check`. Either is
fine deliberately chosen; the failure mode is a repo where nobody owns it.

## Making the loop faster

The lifecycle says run fewer suites; the other half is making the suites you
run cheaper. The cost model is directly optimisable:

> **cost ≈ mutants × time to run the tests covering them**

- **Split an expensive class into its own suite** — one split took a suite
  46.7s → 20.9s, and the new seconds-long suite is all most edits owe.
- **Narrow `targetTests`** to the classes that actually cover the target —
  one suite went 10.6s → 6.1s.
- **Threads are not the lever they look like** — 8 threads bought ~10%, 10
  were slower than 8. Measure before spending here.

*(casebook: loop-speed measurements)*

### Incremental analysis needs arcmutate — free for open source, pre-wired

Open-source PIT accepts `--historyInputLocation`/`--historyOutputLocation`
but its only registered history factory throws — do not re-attempt on the
strength of the CLI flags existing, and note the failed run leaves the
previous report in place for the verify step to read *(casebook: the 11×
"speedup" that did no work)*.

**With a licence, activation is dropping one file.** The plugin keys
everything off `arcmutate-licence.txt` at the project or root-project
directory: when present, `com.arcmutate:base` (version pinned in the plugin,
overridable via `hardening.arcmutateBaseVersion`) joins PIT's classpath and
every suite runs `+arcmutate_history` against a rolling per-suite file at
`<module>/.pitest-history/<suite>.hist` — outside `build/` so `clean` cannot
erase it, git-ignored as machine-local state. Without the file, no dependency
and no flags: PIT runs exactly as open source, so unlicenced machines and CI
are unaffected.

Two honesty rules come with it. Each assisted run announces itself — a
lifecycle line at start and a `[history]` marker on the verify summary — so a
reused number is never mistaken for a re-earned one; with history active
*fast is the expected state*, and suspicion transfers to the exit code and
the marker. And **anything that writes or certifies the record runs
`-PnoMutationHistory`**: the pre-release gate (the release decision re-earns
every status from scratch), baseline refreshes with
`-PupdateMutationBaseline` (the accepted CSV is the gate's authority and must
come from observed runs), and the convergence method's runs (two assisted
runs agree by construction). Delete `.pitest-history/` to reset a machine's
history wholesale.

## The mutation ratchet

Each `pitest<Suite>` run diffs its unkilled mutants (`SURVIVED` and
`NO_COVERAGE`) against the checked-in baseline at
`config/pitest/<suite>-accepted.csv` and fails on anything new. A new unkilled
mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks over restating the implementation: position after a skip,
   exact error text, allocation bounds — not "the code does what the code
   does".
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason in the repo's `config/pitest/README.md`. Acceptance is only for
   mutants *equivalent with respect to observable behavior* (e.g. a mutant
   that only over-allocates a `StringBuilder`), never for "hard to test".

The baseline is a ratchet: shrinking it is always an improvement, growing it
requires a written reason. Repos may seed their first baseline with the full
pre-existing survivor population — that is triage debt made explicit, not
acceptance; label it as such in `config/pitest/README.md`.

### `SURVIVED` and `NO_COVERAGE` are different problems

The ratchet treats both as unkilled, which is correct, but they call for
opposite responses:

- **`SURVIVED`** — a test executed the mutated line and could not tell the
  difference. Judgment call: strengthen the assertion or write an acceptance.
- **`NO_COVERAGE`** — no test reached the line. Nothing to judge; mechanical
  work. Never accept one as "equivalent" — you have not observed its
  behaviour, so you cannot know that it is.

Read the split before planning a pass: "89% killed, 56 to triage" may be 27
judgment calls and 29 untested methods — very different afternoons. When
accepting `NO_COVERAGE` is genuinely right (a path the harness cannot reach
without new scaffolding), say *that* in the note, not "equivalent".

`SURVIVED` has the same third category: a mutant distinguishable *in
principle* but unreachable through any deterministic harness. Accept it as
**unreachable in-harness** and name what would reach it — the named escape
hatch is what tells a later reader whether the acceptance still holds
*(casebook: the HTTP 199 guard)*.

Every `pitest<Suite>` run prints the split without being asked:

```
pitest 'client': 441/498 detected (88%) — 27 survived, 30 no_coverage
pitest 'vanity': 113/113 detected (100%) — 1 timed out (load-dependent)
```

and a ratchet failure groups new rows under the same two headings. `TIMED_OUT`
counts as detected, matching PIT, but is named separately because that
detection can flip (below). The percentage is rounded **down**, so it never
reads better than it is; PIT's own line rounds, so the two can differ by a
point while the counts agree. Error statuses (`RUN_ERROR`, `MEMORY_ERROR`)
are *not* counted as detected — there PIT is more generous and the counts
genuinely diverge; the split names them so the dip is explainable (see the
transient-failures section).

Baseline keys include line numbers, which churn when a mutated file is
edited: the verify task then reports stale entries alongside "new" ones.
Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

### `TIMED_OUT` is detected, and detection is load-dependent

A mutant that makes a loop non-terminating is caught by PIT's timeout and
counts as **detected** — so it is not written to the baseline. But the same
mutant can report `SURVIVED` when its suite runs alone and `TIMED_OUT` in a
multi-suite invocation, so the build fails or passes depending on *how you
invoked it*, and the failure looks exactly like a real regression.

- **Verify in both modes** before trusting a baseline — the suite alone, and
  under `qualityGate`. A row that differs between them belongs in the
  baseline; stale rows only warn, so a superset is safe in this direction.
- **Union only rows you have observed to flip.** Bulk-adding every
  `TIMED_OUT` row "to be safe" accepts mutants that are reliably detected
  today and silently stops the ratchet noticing if a later edit makes them
  genuinely survive.
- Prefer removing the cause: a fake collaborator that turns a would-be
  infinite loop into a deterministic assertion failure — a call budget, a
  bounded queue — beats leaning on the timeout.

### Two baseline-format traps

- **Rows can duplicate.** PIT emits multiple mutants sharing a
  `class,method,line,mutator,status` key; the baseline holds unique rows, so
  a run's raw row count legitimately exceeds the baseline's. Compare *unique*
  rows.
- **Hand-edited rows can silently never match.** The canonical mutator name
  strips the `org.pitest.…gregor.mutators.` package *and* the `returns.`
  sub-package; a row spelled `returns.NullReturnValsMutator` matches nothing
  and reports new forever. Prefer `-PupdateMutationBaseline`, which writes
  the canonical form; hand-edit only to union a known flip.

### The recurring equivalence families

Most accepted mutants fall into a handful of shapes; group the baseline by
family rather than listing rows:

- **Allocation-size only** — changes how much is reserved, never what is
  computed.
- **Fast-path / alternate-path routing** — both branches reach the same
  result; a guard subsumed by a later branch is the common case
  (`if (a.feePayer()) return a;` above a `signer()` branch returning the same
  object).
- **Equal but not identical** — a fresh instance instead of the argument;
  killable only by asserting reference identity the API does not promise.
- **Identity short-circuits** — `this == o ||` atop `equals`; removal falls
  through to a field comparison with the same answer.
- **Defensive code unreachable in context** — note *why* it is unreachable;
  that claim is the part that rots.

A cluster fitting none of these deserves a second look before accepting.

### When equivalence is cheap to verify, verify it

An acceptance note is an argument, and arguments rot with the code around
them. When the claimed equivalence spans a sweepable domain, reimplement both
variants outside the codebase, diff them over the range, and record the range
in the note — "verified equivalent over ⟨inputs⟩" survives refactors that
silently invalidate prose, and the sweep is usually minutes *(casebook: the
Newton's-method sqrt sweep)*. When a sweep is not feasible, the note says
what argument stands in for it — that is what a later reader must re-check.

### When a cluster of unkillable mutants means the design is wrong

Several unkillable mutants in one place is a signal. If they sit on logging
or output calls, the side effect is usually in the wrong layer: extract the
*construction* (a pure, assertable function — the table as a string, the log
message as a `static String`) from the *emission*, and the cluster becomes
ordinary testable code, usually leaving one genuinely equivalent
`VoidMethodCallMutator` *(casebook: System.out in a library factory; logEpoch)*.

Watch for a fake justification: a method that returns *its own argument* is a
`void` method with a convenience return, not "not purely an output method".
Check whether the useful-looking return value is derived from the work. Ask
what the mutants have in common before accepting them as a group.

### Turning "equivalent" mutants into killable ones

A mutant that survives because tests cannot observe the difference hints that
a *property you care about* is unasserted — grow/trim mutants in sized array
readers are invisible to result assertions but visible to
`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`. If
allocation, ordering, or laziness is the point of the code, assert it.

**"The point of the code" is the whole gate, and it is easy to read past.**
Is the property a design goal you would defend in review — a documented
zero-allocation contract, a laziness guarantee callers rely on — or an
incidental micro-optimisation that happens to be the only observable
difference? Only the first earns the machinery; the second is an acceptance
whose reason is already written ("this branch is allocation routing only").

The costs are real *(casebook: the allocation harness that flapped)*:

- PIT re-runs covering tests once per mutant, so a measurement harness
  multiplies like a sleep does.
- A discarded result can be scalar-replaced by escape analysis, erasing the
  allocation under test nondeterministically — every result needs a
  `static volatile Object` sink.
- Bounds are per-method and margins can be single-digit bytes. A thin-margin
  allocation bound is a flaky harness with extra steps, and the determinism
  section is unambiguous about which of those is worse than recorded debt.

### A suite's percentage is not a target

An accepted mutant with a written reason is a **finished outcome**, not debt.
A suite at 81% because four mutants are documented equivalents is reporting
an accurate number; driving it to 100% buys nothing — the four were closed by
outcome 3. Read a low percentage as a question: *which* mutants, `SURVIVED`
or `NO_COVERAGE`? Uncovered lines are real work; documented equivalents are
done. The suites worth attention are those whose baseline is growing, or
whose entries say "hard to test" instead of why the mutant cannot change
behaviour *(casebook: the allocation harness that flapped)*.

## Targeting policy

Target mutation suites by **package wildcard with explicit exclusions**,
never by allowlist. An allowlist silently exempts every class added after it
was written; a wildcard mutates a new class by default, and a forgotten
exclusion costs a slower run, not a blind spot. Exclude: test/fuzz/fixture
sources sharing the recompiled root, classes owned by another suite, and
deliberate opt-outs — each with a comment saying why.

**Give exclusions a trailing wildcard** (`*Tests*`, not `*Tests`): test
classes hold nested helpers, and `*Tests` does not match
`FooTests$StubService`. **Then stop trusting the naming convention**:
top-level test-source classes are routinely named for their role —
`RecordingWebSocket`, `StubHttpResponse` — and match no `*Test*` pattern;
extracting a shared fake out of a test class is exactly the refactor that
silently adds it to the mutated population.

Exclusions must cover the **test source set**, not a naming convention. The
plugin checks: `pitest<Suite>Verify` cross-references mutated classes against
test source directories and warns, naming the classes to exclude. It warns
rather than fails because an upgrading repo already has those mutants in an
accepted baseline — fix the exclusions and re-seed; the baseline should
shrink. It is a check and not a paragraph because the trap caught its own
documenter *(casebook: scaffolding mutated by its documenter)*.

Converting an existing allowlist to a wildcard is worth doing, but size it
first: it surfaces every class the allowlist exempted, which can be an order
of magnitude more unkilled mutants — pre-existing debt becoming visible, not
new debt. Seed it, label it untriaged, work it down. Cheap lie-detector for
an allowlist: list the module's main classes, subtract what any suite's
patterns match, read what is left.

## The mutator set bounds what the ratchet can see

Targeting chooses which classes are mutated; the mutator set chooses which
*defects are expressible*. The standard groups have a blind spot landing
exactly on money-handling code: `MathMutator` rewrites primitive bytecode
arithmetic, while `BigInteger`/`BigDecimal` arithmetic is method calls those
opcodes never touch. A fee computation on `BigInteger` is invisible to
`STRONGER` — the suite is blind by construction, not undertested, and no
ratchet failure will ever say so.

The remedy is `EXPERIMENTAL_BIG_INTEGER` / `EXPERIMENTAL_BIG_DECIMAL`, named
per suite (they belong to no group). Before enabling: pitest ≥ 1.25.8 on
current JDKs; **trial each suite and enable only what fires** — a mutator
that cannot fire costs baseline churn and buys nothing; **record the trial
numbers with the override** in `config/pitest/README.md` so the omission
reads as measured, not forgotten. Property-asserting tests already kill
96–98% of newly expressible mutants; implementation-restating ones do not
*(casebook: EXPERIMENTAL_BIG_INTEGER trials)*.

## Test conventions for new or changed API

- **Value, null/empty, and wrong-type cases** for every reader; type-guarded
  reads need the mismatch path exercised in both directions.
- **Position-after assertions**: any method that skips or consumes input gets
  a test that reads a known value *after* it — correct results at a wrong
  position are the iterator bug class tests most often miss.
- **Both case-fold directions and exact-length boundaries** for string/span
  matching (upper-vs-lower and lower-vs-upper are different code paths;
  `ǅ`-class titlecase chars break naive folds).
- **Parameterize across input sources** (byte- and char-backed iterators):
  behavior can legitimately diverge per source; pin both or document why
  either is accepted.
- **Allocation bounds** where zero-alloc is the contract (see above).
- **Assert the guard's own message when its fallback throws the same type.**
  A bare `assertThrows(ArithmeticException.class, ..)` cannot tell an
  explicit overflow guard from its absence when the unguarded path ends in
  `longValueExact()` — pin the message or give the guard its own type.
- **Drive both branches of every sentinel substitution.** For
  `x == null ? sentinel : x`, the absent case alone passes even if the method
  ignores its argument; the present case alone misses a dropped substitution.
  Assert both — and that the *other* positions did not move.
- **Records with array components compare by identity.** `assertEquals` on
  such a record is an identity check dressed as a value assertion. Compare
  scalar fields and `assertArrayEquals` the arrays.
- **Reach for package-private, not reflection, when a test needs an
  internal.** Same-package tests see it, a rename fails at compile time
  instead of runtime, and reflective indirection is exactly what makes a
  mutant's effect unobservable.
- **When a test you believe in will not go green, suspect the code before you
  soften the assertion.** This is where the practice pays: real bugs surface
  as assertions that cannot hold, not as mutant kills *(casebook: six bugs
  from unsoftened assertions)*.

## Fuzzing

Fuzz targets are smoke tests at the default 60s; real exploration is longer
runs via `-PmaxFuzzTime`. Every finding becomes two artifacts: a minimized
input committed to the seed corpus, and a named regression unit test. A crash
fixed without both is a crash that can return.

**Replay the corpus inside `check`.** A committed corpus that only runs when
someone remembers to fuzz is a directory of files, not a regression suite;
feeding every seed through the harness costs milliseconds per build.

**When one thing has two representations, fuzz the differential** — two
parsers for one config, an encode/decode round trip, a fast path beside a
reference path: require the two to *agree* (or both to reject). Crash-only
fuzzing cannot see a wrong answer *(casebook: the config differential
harness)*.

## Determinism requirement

The ratchet compares runs, so kills must be deterministic. **Fixed seeds
always** — an unseeded random test kills a different fringe of mutants each
run and flaps the baseline. **No real waits** — PIT re-runs covering tests
per mutant, so one sleep is multiplied by the mutant count, and waits are
also what makes kills non-reproducible; if `qualityGate` gets slower, look
for a reintroduced wait first *(casebook: unseeded floats and real waits)*.

**Prefer a clock seam to a prohibition.** A `NanoClock`-style interface with
`nanoTime()` and `sleep(millis)`, injected through every factory (clockless
overload defaulting to the system clock), lets a test clock advance time
*only when the code under test sleeps* — pacing and backoff become an exact
function of the delays requested, and timing assertions become equalities.
Two mutation-specific details:

- **Give test clocks a non-zero origin.** A clock starting at 0 makes every
  "start timestamp mutated to 0" mutant equivalent by accident of the
  fixture. One literal fixes it.
- **Carry both readings.** If the interface derives wall-clock millis from
  monotonic nanos by default (system implementation overriding with the real
  epoch clock), a test clock implementing `nanoTime()` alone advances both
  coherently, and no mutant hides in a mixed-source comparison.

**A flaky harness is worse than recorded debt.** A sleep-ordered or
spin-waiting test that kills a mutant *most of the time* puts the ratchet
back into the state this document exists to prevent. If the interleaving
cannot be made deterministic, record the mutant and say why.

### A wandering kill count is a defect to chase, not re-ratchet past

An unkilled count that differs between invocations with no code change is
broken somewhere, and refreshing the baseline bakes in whichever run wrote
it. `TIMED_OUT` flips (above) are one mechanism; two more:

- **`@Execution`/`@TestInstance` on an abstract base not reaching concrete
  subclasses** — version-dependent: JUnit 6 marks both `@Inherited`, older
  lines did not, and `@Execution` is moot without parallel execution. One
  `javap` of the resolved jar settles it before any test restructuring
  *(casebook: @Inherited is version-dependent)*.
- **Coverage attributed to a field or static initializer is unstable.** A
  factory reached only through a `static final` initializer flaps between
  killed and survived. Call it from inside a `@Test` — which usually yields
  a real assertion for free.

Convergence is checkable, and worth scripting. With an arcmutate licence
active, every run below takes `-PnoMutationHistory` — assisted runs agree by
construction:

1. Run every `pitest<Suite>`, copy each
   `build/reports/pitest/<suite>/mutations.csv` aside.
2. **Delete the report directories**, then run again — not optional: Gradle
   serves an up-to-date `pitest<Suite>` without re-running PIT, and a second
   run without the delete compares a file to itself.
3. Key rows on `(class, method, line, mutator)` and diff **per-mutant
   status** — strictly stronger than sub-totals and it names which mutant
   moved. Flag flips crossing the `SURVIVED`/`NO_COVERAGE` boundary; only
   those can move the ratchet.
4. Repeat against `qualityGate` — `TIMED_OUT` flapping appears between solo
   and multi-suite runs, never between two solo runs, so two solo runs
   agreeing proves the weaker thing.

Two runs can match in total while disagreeing about which mutants died; the
headline number is not the check.

**Sweep for accepted rows that match nothing in any mode** while you have the
data: per-run stale warnings get dismissed as solo-vs-gate mode noise, so
diff each `<suite>-accepted.csv` against the union of unkilled sets across
all runs — rows matching in *no* mode are widening the gate for nothing.
And **revisit rows unioned for a flip once you remove the cause** (a clock
seam, a suite split): they still match real mutants, so no warning will ever
fire on them; only re-measuring tells you the insurance now covers nothing
*(casebook: flip insurance that outlived its cause)*.

### Transient infrastructure failures are not mutation results

Three recurring signatures have nothing to do with mutants *(casebook:
MINION_DIED, worker EOF, and the daemon log)*:

- **PIT `MINION_DIED` during coverage generation** — the coverage minion's
  ~10s socket handshake timed out; upstream, intermittent, no exposed knob
  (`--timeoutConst` is per-mutant test time, unrelated). It fails *before any
  report is written*, so it can fail a build but never poison a result:
  re-run the suite. An automatic plugin retry was considered and declined at
  ~1 per 100 suite runs — it would mostly mask environment sickness;
  reconsider if the rate rises.
- **`java.io.EOFException` from a test task** — the forked worker JVM died
  abruptly; no `hs_err` file means killed from outside, not crashed.
  One-shot; re-run.
- **`RUN_ERROR` on individual mutants** — the same shape per-mutant, observed
  only under multi-suite load. The summary names these as `run_error (not
  counted as detected)` — deliberately stricter than PIT — so the dip in
  detected has a visible cause. Not gated, cannot fail the build; a
  `RUN_ERROR` that *persists* on one mutant across quiet re-runs is not load
  and deserves a look at the mutated bytecode.

**The evidence usually survives you discarding it.** The Gradle daemon keeps
complete build output — including PIT minion stack traces — at
`~/.gradle/daemon/<version>/daemon-<pid>.out.log`, even when the invoking
shell piped everything to `/dev/null`. Read it before recording any failure
as unexplained.

## Adopting in a new repo

1. Apply `software.sava.build.feature.hardening` and register mutation suites
   (wildcard targets + exclusions) and fuzz targets.
2. Pin any unseeded randomness in the test suite (see above).
3. Seed the baselines: `./gradlew pitest<Suite> -PupdateMutationBaseline` per
   suite, commit `config/pitest/`.
4. Add a `config/pitest/README.md` from an existing repo's copy: triaged
   equivalents (initially empty) and the untriaged-debt note.
5. Add the agent-instructions block below to the repo's `AGENTS.md` with the
   `hardening-template` marker (run `agentsTemplateInSync` — its message
   prints the current digest), and decide who owns the pre-release
   `qualityGate` run: wire it into CI if the runners can afford it, otherwise
   record it as a release-checklist item run locally (see the lifecycle
   section) — and say which in `AGENTS.md`.
6. If the repo will use arcmutate incremental analysis (free licences for
   open source): add `.pitest-history/` to `.gitignore` — the plugin writes
   there but cannot ignore it for you — and decide whether the licence
   certificate is committed (usual for an OSS licence, so every clone gets
   history) or kept machine-local. Drop `arcmutate-licence.txt` at the repo
   root; nothing else changes.

## Agent instructions template

Copy into the repo's `AGENTS.md` (adjust file names). The copies drift: a
downstream block is an adapted snapshot, and no tooling can diff cross-repo
prose semantically. The plugin makes the drift **visible** instead of trying:
it carries a digest of this template's blockquote lines, and every consuming
module's `agentsTemplateInSync` task (wired into `check`) fails until the
repo's `AGENTS.md` contains `<!-- hardening-template sha256:<digest> -->`
acknowledging the current template — so editing the template below breaks
every downstream `check` on its next plugin refresh, which is the point, and
no list of downstream repos needs maintaining anywhere. The marker is an
acknowledgment, not a checksum of the local block: update it only after
re-diffing the block against the template and syncing or **acting on** each
changed bullet — a new requirement may mean new code, not just new prose;
that is how sava's corpus-replay gap went unnoticed until an unrelated
repo's agent tripped over it. The failure message prints the digest to
paste.

> - **Scale verification to the change.** Iterate with the module's `test`
>   task; before handing off, run only the `pitest<Suite>`(s) whose mutated
>   code the change can reach — including suites in dependent modules that
>   call a changed API, and the owning suite for test-only edits (a weakened
>   test is exactly what the ratchet catches). The full `qualityGate` — every
>   suite, serialized, diffed against `config/pitest/` — is the pre-release
>   check, owned by CI or by the release checklist (this repo records which);
>   it is not the inner loop.
> - A new unkilled mutant has exactly three legal outcomes: **kill it** with a
>   test (prefer asserting the property it breaks over restating the
>   implementation), **refactor** it out of existence, or **accept it** with a
>   written reason in `config/pitest/README.md`. Never run
>   `-PupdateMutationBaseline` just to make the build pass.
> - Line-number churn from editing a mutated file shows up as paired stale +
>   "new" baseline entries; confirm they're the shifted old ones before
>   refreshing.
> - **Randomized tests use fixed seeds, and never sleep**: the ratchet needs
>   deterministic kills, and PIT re-runs the suite per mutant, so one real wait
>   costs minutes. Exploration belongs to the fuzz targets.
> - **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
>   detected and is not written to the baseline, and it is load-dependent — the
>   same mutant can report `SURVIVED` alone and `TIMED_OUT` under
>   `qualityGate`. Verify a baseline in both modes; union only rows observed to
>   flip, never every `TIMED_OUT` row.
> - **A flaky harness is worse than recorded debt.** If an interleaving or a
>   boundary cannot be made deterministic, accept the mutant with a written
>   reason rather than chasing it with sleeps or spin-waits.
> - **A suite's percentage is not a target.** An accepted mutant with a written
>   reason is finished work, not debt. Before trying to raise a number, check
>   whether the remainder is `NO_COVERAGE` (real work) or documented
>   equivalents (already closed).
> - **Allocation and timing harnesses are a last resort**, reserved for
>   properties that are a stated design goal. They re-run once per mutant, need
>   a `volatile` sink so escape analysis cannot delete what they measure, and
>   flap when the margin is thin.
> - When a test you believe in will not go green, **suspect the code before you
>   soften the assertion** — that is where this process finds real bugs.
> - **A wandering unkilled count is a defect, not noise** — chase it before
>   refreshing any baseline. Known causes: real waits, `TIMED_OUT` load flips,
>   `@Execution`/`@TestInstance` not reaching concrete test classes from an
>   abstract base (version-dependent — JUnit 6 marks both `@Inherited`; check
>   the resolved jar), and coverage attributed to field initializers —
>   exercise factories from inside a `@Test`.
> - **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
>   arithmetic is method calls, invisible to the default arithmetic mutators —
>   fixed-point and fee math needs `EXPERIMENTAL_BIG_INTEGER` (pitest ≥
>   1.25.8). Trial per suite, enable only what fires, and record the numbers.
> - `SURVIVED` and `NO_COVERAGE` are different problems: the first is a
>   judgment call about equivalence, the second is an untested line and is
>   mechanical. Never accept a `NO_COVERAGE` mutant as "equivalent" — you have
>   not observed its behaviour.
> - Exclusions must cover the **test source set**, not a naming convention:
>   shared fakes are named `RecordingFoo` / `StubFoo` and match no `*Test*`
>   pattern. After registering or widening a suite, list the mutated classes and
>   confirm none live under `src/test`.
> - **Verify by the absence of failures, not the presence of passes.** Counting
>   `PASSED` lines hides a failure sitting next to them, and a green
>   `clean build` can mean the build cache short-circuited rather than that
>   tests ran. Check the failure count and confirm the task actually executed.
>   A mutation run has a second version of this: a *failed* PIT run leaves the
>   previous run's report in place, so the summary you read can describe a run
>   that never happened. Trust the exit code, and delete report directories
>   when comparing runs.
> - **A suite that got faster without getting narrower is a bug report.** Real
>   speedups come from fewer mutants or faster covering tests; an unexplained
>   one usually means the run did less than you think. Exception: a summary
>   carrying the `[history]` marker is arcmutate incremental reuse and fast is
>   expected — but the pre-release gate still runs with `-PnoMutationHistory`
>   to re-earn every status from scratch.
> - **Transient infra failures are not results.** PIT `MINION_DIED` fails
>   before writing a report, so it cannot corrupt one — re-run the suite; a
>   Gradle-worker `EOFException` death is the same shape, and a per-mutant
>   `RUN_ERROR` under load is the same shape smaller (the summary names it,
>   and it is not counted as detected). The daemon log
>   (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
>   full output even when the shell discarded it — read it before calling a
>   failure unexplained.
> - Fuzz findings become a committed seed input **and** a named regression
>   test, never just a fix — and the committed corpus is replayed by a unit
>   test inside `check`, so it cannot rot between fuzz runs.
> - **When one thing has two representations, fuzz the differential.** Two
>   parsers for one config, an encode/decode round trip, a fast path beside a
>   reference path: assert the two *agree* rather than that neither crashes.
>   Crash-only fuzzing cannot see a wrong answer.
> - **Time-dependent code takes a clock**, so tests advance time instead of
>   waiting. Give test clocks a non-zero origin — a clock starting at 0 makes
>   every "start timestamp mutated to 0" mutant equivalent by accident.
