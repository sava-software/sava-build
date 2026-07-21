# Hardening process

The quality process for repos applying `software.sava.build.feature.hardening`:
what the tooling enforces, what requires judgment, and the conventions that
make both portable across codebases. The enforceable parts live in the plugin —
this document covers only the decisions the tooling cannot make for you.

## Lifecycle

Verification is tiered by cost, and the tier is chosen by what the change can
affect — not by habit in either direction:

| When | Command | What it proves |
|---|---|---|
| Inner loop | the module's `test` (or `--tests` for the touched classes) | The change works. |
| Before handing off a change | the `pitest<Suite>`(s) whose mutated code the change can reach | No new unkilled mutants where the change lives. |
| Before a release | `qualityGate` on every module, long fuzz runs (`fuzz<Target> -PmaxFuzzTime=<seconds>`), jmh A/B vs the previous release | Nothing regressed anywhere; no parser crashes at depth; no performance regression. |

`qualityGate` = `test` + every registered `pitest<Suite>`, serialized, each
finalized by its baseline verification. Its cost scales with the repo's total
mutant population, not with the size of the diff — so as suites accumulate it
drifts from "a minute" to "many", and running it per change spends that time
re-learning results the change could not have moved. A suite produces new
information only when the change can alter code it mutates or tests that
cover that code; every other suite's outcome is known before the run starts.

Choosing the owning suites is a **reachability** question, not a file-path
one:

- the suite covering the edited files, plus any suite — including in a
  dependent module — whose mutated code calls the changed API. (Editing a
  widening helper in a core encoding class also owes the rpc suite whose
  response types call it.)
- test-only edits still owe the suite those tests kill mutants in: a
  weakened or deleted test shows up as a new survivor, which is precisely
  what the ratchet is for. They owe nothing beyond it.
- doc, build-script, and comment changes owe no suite at all.

Both failure modes have been observed and both waste something real. Running
the full gate after a test-strengthening tweak or a file deletion spends
minutes per iteration confirming numbers that could not have changed — across
a session that is the difference between an inner loop and a queue. Never
running it lets cross-suite effects hide: `TIMED_OUT` flips under full-gate
load, and cross-module callers of a changed API, surface only there. The full
gate runs before anything is published — that is the requirement; where it
runs is a cost decision. CI wiring buys per-change coverage without per-change
local cost, but serialized PIT suites on hosted runners (fewer cores, and PIT
scales with them) can take long enough to be impractical there. In that case
the release checklist owns the run: the full gate is executed locally before
deciding to release, and CI stays on `check`. Choosing that deliberately is
fine; the failure mode is only a repo where nobody owns the pre-release run.

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
opposite responses and conflating them wastes effort:

- **`SURVIVED`** — a test *did* execute the mutated line and could not tell the
  difference. Either the assertion is too weak, or the mutant is equivalent.
  This is a judgment call, and the outcome is a strengthened assertion or a
  written acceptance.
- **`NO_COVERAGE`** — no test reached the line at all. There is nothing to
  judge; it is mechanical work. Never accept one as "equivalent" — you have not
  observed its behaviour, so you cannot know that it is.

Read the split before planning a pass. A suite sitting at "89% killed, 56 to
triage" may really be 27 judgment calls and 29 untested methods, and those are
very different afternoons. When accepting `NO_COVERAGE` is genuinely right —
a path the harness cannot reach without new scaffolding — say *that* in the
acceptance note, not "equivalent".

`SURVIVED` has the same third category, and it deserves the same honesty: a
mutant that is distinguishable *in principle* but unreachable through any
deterministic harness. Observed: the `< 200` half of an HTTP status-range
guard — the JDK client treats 1xx as interim responses and never surfaces one
as a final status, so a mock server replying 199 kills the connection before
the guard runs. The guard is not dead code (a real 199 would distinguish the
mutant) so "equivalent" would be false; accept it as **unreachable
in-harness** and name what would reach it (here, a raw-socket stub speaking
HTTP/1.1 by hand). That named escape hatch is what tells a later reader
whether the acceptance is still the right trade.

Every `pitest<Suite>` run prints the split without being asked:

```
pitest 'client': 441/498 detected (88%) — 27 survived, 30 no_coverage
pitest 'vanity': 113/113 detected (100%) — 1 timed out (load-dependent)
```

and a ratchet failure groups the new rows under the same two headings, so the
first thing you see is which kind of work you are looking at.

`TIMED_OUT` is counted as detected, matching PIT, but is called out separately
because that detection can flip (see below) — a suite reporting timed-out rows
is one whose number is not stable across invocations. The percentage is rounded
**down**, so it never reads better than it is; PIT's own line rounds, so the two
can differ by a point while the counts agree.

Baseline keys include line numbers, which churn when a mutated file is
edited: the verify task then reports stale entries alongside "new" ones.
Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

### `TIMED_OUT` is detected, and detection is load-dependent

A mutant that makes a loop non-terminating is caught by PIT's timeout and
reported `TIMED_OUT`, which counts as **detected** — so it is not written to
the baseline. That is fine until the timing shifts: the same mutant can report
`SURVIVED` when its suite runs alone and `TIMED_OUT` in a multi-suite
invocation. The build then fails or passes depending on *how you invoked it*,
and the failure looks exactly like a real regression.

Observed, not theorized. Consequences worth internalising:

- **Verify in both modes** before trusting a baseline — the suite alone, and
  under `qualityGate`. A row that differs between them belongs in the baseline;
  stale rows are reported as a warning and never fail the build, so a superset
  is safe in this one direction.
- **Union only rows you have observed to flip.** Bulk-adding every `TIMED_OUT`
  row "to be safe" accepts mutants that are reliably detected today and
  silently stops the ratchet noticing if a later edit makes them genuinely
  survive. That is a real regression in coverage bought for an imagined one.
- Prefer removing the cause: if a fake collaborator can turn a would-be
  infinite loop into a deterministic assertion failure — a call budget, a
  bounded queue — do that instead of leaning on the timeout.

### Two baseline-format traps

Both cost a debugging cycle the first time:

- **Rows can duplicate.** PIT emits multiple mutants that share a
  `class,method,line,mutator,status` key; the baseline holds unique rows. So a
  run's raw row count legitimately exceeds the baseline's. Compare *unique*
  rows, or a converged baseline will look like it is diverging.
- **Hand-edited rows can silently never match.** The canonical mutator name
  strips both the `org.pitest.…gregor.mutators.` package and the `returns.`
  sub-package. A row spelled `returns.NullReturnValsMutator` sits in the file
  and matches nothing, so its mutant is reported new on every run. Prefer
  `-PupdateMutationBaseline`, which writes the canonical form; hand-edit only
  to union in a known flip, and match that form exactly.

### The recurring equivalence families

Most accepted mutants fall into a handful of shapes. Naming them makes triage
faster and acceptance notes consistent across repos — group the baseline by
family rather than listing rows:

- **Allocation-size only** — the mutant changes how much is reserved, never
  what is computed. Sizing formulas whose contract is "never under-allocate"
  live here.
- **Fast-path / alternate-path routing** — both branches reach the same result;
  the mutant only changes which one runs. A guard subsumed by a later branch is
  the common case: `if (a.feePayer()) return a;` is unkillable when the
  `signer()` branch below it returns the same object for every fee payer.
- **Equal but not identical** — the mutant builds a fresh instance instead of
  returning the argument. Killable only by asserting reference identity, which
  the API usually does not promise.
- **Identity short-circuits** — `this == o ||` at the top of `equals`. Removing
  it falls through to the field comparison, which returns the same answer for
  every input.
- **Defensive code unreachable in context** — a guard no live caller can
  trigger. Worth a note on *why* it is unreachable, since that is the claim
  which can rot.

A cluster that does not fit any of these is worth a second look before
accepting.

### When equivalence is cheap to verify, verify it

An acceptance note is an argument, and arguments rot with the code around
them. When the claimed equivalence spans a sweepable domain, reimplement both
variants outside the codebase and diff them. Observed: a mutant of a
Newton's-method integer square root (initial guess `v/2` → `2v`) was accepted
only after both versions agreed on every input below 200,000 plus `2^e ± 3`
for `e` in 60..129 — zero differences across 200,490 values, and the note
records the range. "Verified equivalent over ⟨inputs⟩" survives refactors
that would silently invalidate a prose argument, and the sweep is usually
minutes of work. When one is not feasible, the note should say what argument
stands in for it — that is the part a later reader must re-check.

### When a cluster of unkillable mutants means the design is wrong

Several unkillable mutants in one place is a signal, not a nuisance. If they
all sit on logging or output calls, the side effect is usually in the wrong
layer rather than the mutants being equivalent.

Observed: a library factory method printed a table to `System.out` while
building its result. Nine mutants — the `print` calls and the loop driving
them — were unkillable, because nothing asserts stdout and capturing it would
pin output that is not part of the contract. The fix was not a test. Returning
the table as a string and letting the CLI print it made the function pure, the
mutants died, and a library stopped writing to stdout. The baseline went from
nine accepted entries to none.

Ask what the mutants have in common before accepting them as a group.

### Turning "equivalent" mutants into killable ones

A mutant that survives because tests cannot observe the difference is a hint
that a *property you care about* is unasserted. The canonical example:
grow-always / trim-always mutants in sized array readers are invisible to
result assertions but visible to
`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` — a
min-over-N-runs allocation bound kills them and locks the zero-allocation
design goal as an enforced invariant. If allocation, ordering, or laziness is
the point of the code, assert it.

**"The point of the code" is the whole gate, and it is easy to read past.**
Before reaching for this, answer: is the property a design goal you would
defend in review — a documented zero-allocation contract, a laziness guarantee
callers rely on — or is it an incidental micro-optimisation that happens to be
the only observable difference? Only the first earns the machinery. The second
is a mutant to accept with a written reason, and the reason is already the
answer: "this branch is allocation routing only."

The costs are real and were all paid on a suite where the answer was "no":

- **PIT re-runs the covering tests once per mutant.** The determinism section
  below says this about sleeps; it applies identically to a warmup-and-rounds
  measurement harness. ~150k iterations per assertion took a 22-mutant suite
  from ~10s to ~38s.
- **Measuring a discarded value measures nothing** — and does so
  *nondeterministically*. A result that is immediately dead can be
  scalar-replaced by escape analysis, erasing the very allocation under test,
  but only on runs that reach the right JIT tier. The first version of one such
  test passed six times standalone and failed under the ratchet. Assign every
  result to a `static volatile Object` sink.
- **Bounds are per-method and the margins can be thin.** Two methods sharing a
  branch shape had different allocation floors, so a budget loose enough for one
  let the other's mutant through at 88 bytes against a 90-byte bound. Elsewhere
  the gap between fast path and mutant was 64 against 88 — visible, but too
  narrow to assert without flapping.

A thin-margin allocation bound is a flaky harness with extra steps, and the
determinism section is unambiguous about which of those is worse than recorded
debt.

### A suite's percentage is not a target

An accepted mutant with a written reason is a **finished outcome**, not debt
waiting to be cleared. A suite sitting at 81% because four of its mutants are
documented equivalents is reporting an accurate number, and driving it to 100%
buys nothing — the four were already closed by the second-to-last paragraph of
the ratchet's three legal outcomes.

Read a low percentage as a question, not a defect: *which* mutants, and are they
`SURVIVED` or `NO_COVERAGE`? Uncovered lines are real work. Documented
equivalents are already done. The suites worth attention are the ones whose
baseline is growing, or whose entries say "hard to test" rather than why the
mutant cannot change behaviour.

## Targeting policy

Target mutation suites by **package wildcard with explicit exclusions**, never
by allowlist. An allowlist silently exempts every class added after it was
written; a wildcard makes a new class mutated by default and forgetting an
exclusion costs a slower run, not a blind spot. Exclude: test/fuzz/fixture
sources sharing the recompiled root, classes owned by another suite, and
deliberate opt-outs (constant tables) — each with a comment saying why.

**Give those exclusions a trailing wildcard**: `*Tests*`, not `*Tests`. A test
or fuzz class routinely holds nested helpers — a recording fake, a stub clock,
a harness's private `Parser` — and `*Tests` does not match
`FooTests$StubService`. Without the trailing wildcard PIT mutates that scaffolding
as if it were production code, and you triage mutants in your own fakes.

**Then stop trusting the naming convention.** The trailing wildcard only helps
for helpers *nested inside* a test class. Top-level test-source classes are
routinely named for their role rather than their kind — `RecordingWebSocket`,
`StubHttpResponse`, `LiveMainNetDriftCheck` — and match no `*Test*` pattern at
all. Extracting a shared fake out of a test class is exactly the refactor that
silently adds it to the mutated population.

Exclusions must cover the **test source set**, not a naming convention. The
plugin checks this for you: `pitest<Suite>Verify` cross-references every mutated
class against the project's test source directories and warns when a suite is
mutating its own scaffolding, naming the classes to exclude.

It warns rather than fails, because a repo upgrading the plugin will already
have those mutants sitting in an accepted baseline — the warning tells you the
baseline is inflated, not that the build is broken. Fix the exclusions and
re-seed; the baseline should shrink.

Two of the suites in one adoption pass had this defect, one of them introduced
by the same person who had just documented the trap. That is why it is a check
and not a paragraph.

Converting an existing allowlist to a wildcard is worth doing, but size it
first: it surfaces every class the allowlist had been exempting, which can be
an order of magnitude more unkilled mutants than the suite currently reports.
That is pre-existing debt becoming visible, not new debt — seed it, label it as
untriaged in `config/pitest/README.md`, and work it down. A cheap way to check
an allowlist is lying to you: list the module's main classes, subtract the ones
any suite's patterns match, and read what is left.

## The mutator set bounds what the ratchet can see

Targeting chooses which classes are mutated; the mutator set chooses which
*defects are expressible*. A kill percentage is only meaningful relative to
that set, and the standard groups have a blind spot that lands exactly on
money-handling code: `MathMutator` rewrites primitive bytecode arithmetic —
`IADD`, `LMUL` and friends — while `BigInteger`/`BigDecimal` arithmetic is
method calls, which those opcodes never touch. A fixed-point conversion or
fee computation written on `BigInteger` is invisible to `STRONGER`, so a
suite can report a healthy percentage while mutating only the conditionals
*around* the math it exists to protect. No ratchet failure ever surfaces
this; the suite is blind by construction, not undertested.

The remedy is `EXPERIMENTAL_BIG_INTEGER` / `EXPERIMENTAL_BIG_DECIMAL`, which
belong to no standard group and must be named per suite. Before enabling:

- **pitest ≥ 1.25.8 is required on current JDKs** — the mutators misbehaved
  on Java 25 before that release.
- **Trial each suite and read the counts** — enable what fires, not the
  matching pair. Measured on one adoption: a fixed-point-heavy suite grew
  541 → 655 mutants; a second grew by 50; a third — the most
  `BigDecimal`-heavy code in the repo — grew by zero, and
  `EXPERIMENTAL_BIG_DECIMAL` fired zero times across all three. Enabling a
  mutator that cannot fire costs baseline churn and buys nothing.
- **Record the trial numbers with the override** in `config/pitest/README.md`,
  so the next reader knows the omitted mutator was measured, not forgotten.

One measurement from that trial is worth internalising: the existing tests —
written under `STRONGER`, never against these operators — already killed
96–98% of the new mutants, because they asserted properties (round trips,
monotonicity, exact boundary values) rather than restating implementations.
Property assertions generalise to mutation classes that did not exist when
they were written; implementation-restating ones do not.

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
  behavior can legitimately diverge per source, and the test should either
  pin both or document why it accepts either.
- **Allocation bounds** where zero-alloc is the contract (see above).
- **Assert the guard's own message when its fallback throws the same type.**
  A bare `assertThrows(ArithmeticException.class, ..)` cannot tell an explicit
  overflow guard from its absence when the unguarded path ends in
  `longValueExact()` — the mutant removes the guard and the test still passes,
  on the wrong throw site. Pin the guard's message (or give it its own type).
- **Drive both branches of every sentinel substitution.** For
  `x == null ? sentinel : x` wiring, the absent case alone passes even if the
  method ignores its argument entirely, and the present case alone misses a
  dropped substitution. Assert both directions — and that the *other*
  positions did not move, which is what catches an off-by-one in the slot.
- **Records with array components compare by identity.** `assertEquals` on
  such a record (or on a `byte[]` directly) is an identity check dressed as a
  value assertion — it passes against the same instance and fails against an
  equal one. Compare the scalar fields and `assertArrayEquals` the arrays.
- **When a test you believe in will not go green, suspect the code before you
  soften the assertion.** This is where the practice pays. In one repo's
  hardening pass, six real bugs — four of them silent-wrong-answer defects —
  were found this way, and *none* of them by a mutant kill: each surfaced
  because someone writing coverage hit an assertion that could not hold and
  reported it instead of weakening the test to pass. Mutation testing gets you
  to write the test; the test finds the bug.

## Fuzzing

Fuzz targets are smoke tests at the default 60s; real exploration is longer
runs via `-PmaxFuzzTime`. Every finding becomes two artifacts: a minimized
input committed to the seed corpus, and a named regression unit test. A crash
fixed without both is a crash that can return.

## Determinism requirement

The ratchet compares runs, so kills must be deterministic. Randomized tests
must use **fixed seeds** — an unseeded random test kills a different fringe of
mutants each run and makes the baseline flap (this was observed, not
theorized: unseeded float round-trip tests shifted `DoubleParser` survivors
between consecutive runs). Per-run exploration is the fuzz targets' job, not
the unit suite's.

**No real waits in tests, and the reason is not only determinism.** PIT re-runs
the covering tests once per mutant, so a single one-second sleep is multiplied
by the mutant count: removing two real backoff waits from one class took it
from 2.055s to 0.085s and its suite's PIT run from ~80s to ~21s. Sleeps,
timing tolerances and busy-waits are also precisely what makes a kill
non-reproducible. If `qualityGate` starts getting slower, look for a
reintroduced wait before anything else.

**A flaky harness is worse than recorded debt.** Facing mutants blocked on
concurrency or timing, the temptation is to write a sleep-ordered or
spin-waiting test that kills them most of the time. Do not: accepted debt with
a written reason is stable and honest, while a harness that flaps puts the
ratchet back into the state this document exists to prevent. If you cannot make
the interleaving deterministic, record the mutant and say why.

### A wandering kill count is a defect to chase, not re-ratchet past

An unkilled count that differs between invocations with no code change is
broken somewhere, and refreshing the baseline hides it at the worst moment:
the baseline records whichever run wrote it, so a lucky run bakes in a row
that later runs fail on. `TIMED_OUT` flips (above) are one mechanism. Two
more, both observed and both generic to JUnit-under-PIT:

- **`@Execution` and `@TestInstance` are not `@Inherited`.** Placed on an
  abstract test base, they do nothing for its concrete subclasses — which
  then interleave over the base's shared state (a mock server, an
  expectation queue) and make kills order-dependent. Annotate the concrete
  classes, not the base.
- **Coverage attributed to a field or static initializer is unstable.** A
  factory reached only through a `static final` field's initializer flaps
  between killed and survived. Call it from inside a `@Test` — which
  usually yields a real assertion for free, since the object the factory
  builds has observable configuration.

Convergence is checkable: rerun until consecutive runs agree on the
per-mutator sub-totals, not just the headline number — two runs can match in
total while disagreeing about which mutants died.

## Adopting in a new repo

1. Apply `software.sava.build.feature.hardening` and register mutation suites
   (wildcard targets + exclusions) and fuzz targets.
2. Pin any unseeded randomness in the test suite (see above).
3. Seed the baselines: `./gradlew pitest<Suite> -PupdateMutationBaseline` per
   suite, commit `config/pitest/`.
4. Add a `config/pitest/README.md` from an existing repo's copy: triaged
   equivalents (initially empty) and the untriaged-debt note.
5. Add the agent-instructions block below to the repo's `AGENTS.md`, and
   decide who owns the pre-release `qualityGate` run: wire it into CI if the
   runners can afford it, otherwise record it as a release-checklist item run
   locally (see the lifecycle section) — and say which in `AGENTS.md`.

## Agent instructions template

Copy into the repo's `AGENTS.md` (adjust file names):

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
>   `@Execution`/`@TestInstance` missing from concrete test classes (neither is
>   `@Inherited`), and coverage attributed to field initializers — exercise
>   factories from inside a `@Test`.
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
> - Fuzz findings become a committed seed input **and** a named regression
>   test, never just a fix.
