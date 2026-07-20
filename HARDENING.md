# Hardening process

The quality process for repos applying `software.sava.build.feature.hardening`:
what the tooling enforces, what requires judgment, and the conventions that
make both portable across codebases. The enforceable parts live in the plugin —
this document covers only the decisions the tooling cannot make for you.

## Lifecycle

| When | Command | What it proves |
|---|---|---|
| Every change | `./gradlew qualityGate` | Unit tests pass and no new unkilled mutants exist in any suite. |
| Before a release | long fuzz runs (`fuzz<Target> -PmaxFuzzTime=<seconds>`), jmh A/B vs the previous release | No parser crashes at depth; no performance regression. |

`qualityGate` = `test` + every registered `pitest<Suite>`, serialized, each
finalized by its baseline verification. It is the definition of "this change
is safe to merge". While iterating, run just the `pitest<Suite>` that owns
the code you touched — `qualityGate` is the before-commit command, not the
inner-loop one.

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

### Turning "equivalent" mutants into killable ones

A mutant that survives because tests cannot observe the difference is a hint
that a *property you care about* is unasserted. The canonical example:
grow-always / trim-always mutants in sized array readers are invisible to
result assertions but visible to
`com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes` — a
min-over-N-runs allocation bound kills them and locks the zero-allocation
design goal as an enforced invariant. If allocation, ordering, or laziness is
the point of the code, assert it.

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

Converting an existing allowlist to a wildcard is worth doing, but size it
first: it surfaces every class the allowlist had been exempting, which can be
an order of magnitude more unkilled mutants than the suite currently reports.
That is pre-existing debt becoming visible, not new debt — seed it, label it as
untriaged in `config/pitest/README.md`, and work it down. A cheap way to check
an allowlist is lying to you: list the module's main classes, subtract the ones
any suite's patterns match, and read what is left.

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

## Adopting in a new repo

1. Apply `software.sava.build.feature.hardening` and register mutation suites
   (wildcard targets + exclusions) and fuzz targets.
2. Pin any unseeded randomness in the test suite (see above).
3. Seed the baselines: `./gradlew pitest<Suite> -PupdateMutationBaseline` per
   suite, commit `config/pitest/`.
4. Add a `config/pitest/README.md` from an existing repo's copy: triaged
   equivalents (initially empty) and the untriaged-debt note.
5. Add the agent-instructions block below to the repo's `AGENTS.md`, and wire
   `qualityGate` into CI.

## Agent instructions template

Copy into the repo's `AGENTS.md` (adjust file names):

> - **Run `./gradlew qualityGate` after changing main sources** — unit tests
>   plus every PIT suite, each diffed against its accepted baseline in
>   `config/pitest/`. It is the definition of "safe to commit".
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
> - When a test you believe in will not go green, **suspect the code before you
>   soften the assertion** — that is where this process finds real bugs.
> - Fuzz findings become a committed seed input **and** a named regression
>   test, never just a fix.
