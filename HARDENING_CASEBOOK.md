# Hardening casebook

The observed evidence behind the rules in `HARDENING.md`. Each entry is one
incident: what happened, the numbers, and the rule it produced. The policy doc
cites entries as *(casebook: entry name)* — read the entry when you are about
to argue with the rule, because the entry is usually the argument you are
about to make, already tried.

## The allocation harness that flapped

An 81% suite read as a defect, and four documented equivalents — allocation
routing in decimal-conversion branches — were chased with a
`ThreadMXBean#getCurrentThreadAllocatedBytes` harness. Every cost the policy
now names was paid on a suite where the property was not a design goal:

- ~150k warmup-and-measure iterations per assertion took the 22-mutant suite
  from ~10s to ~38s, because PIT re-runs covering tests once per mutant.
- The first version discarded its measured results, so escape analysis
  scalar-replaced the dead values and erased the very allocation under test —
  but only on runs reaching the right JIT tier. It passed six times standalone
  and failed under the ratchet: 20/22, then 22/22, then 22/22.
- Bounds were per-method and thin: two methods sharing a branch shape had
  different allocation floors, so a budget loose enough for one let the
  other's mutant through at 88 bytes against a 90-byte bound; elsewhere the
  fast-path/mutant gap was 64 against 88 — visible, too narrow to assert.

Fully reverted (2026-07-20). The deeper error was upstream of the harness:
treating a percentage as a target when the remaining mutants were already
closed triage. Rules: *a suite's percentage is not a target*; *allocation and
timing harnesses are a last resort*; *a thin-margin bound is a flaky harness
with extra steps*.

## The 11× "speedup" that did no work

Prototyping PIT incremental analysis on open-source PIT: `pitest-entry` ships
an `org.pitest.mutationtest.incremental` package and the CLI accepts
`--historyInputLocation`/`--historyOutputLocation`, so it read as a wiring
job. At 1.25.8 the only registered history factory is
`ErroringHistoryFactory` — a throw:

> History has been enabled but no history plugin has been installed/activated.
> If you are using https://www.arcmutate.com remember to activate the history
> plugin with +arcmutate_history

The run dies in `EntryPoint.pickHistoryStore`, and the "docs are out of date"
reasoning had already produced one wrong correction before the jar was read.

How the failure *presented* is the transferable part: the second run finished
in 2.2s against 24.5s — an apparent 11× win. PIT had thrown immediately and
done nothing, while the previous run's report sat in `build/reports/pitest/`,
so the verify step read it and printed a full, plausible `58/94 detected
(61%)`. Only the exit code was honest. Rules: *a suite that got faster
without getting narrower is a bug report*; *delete report directories when
comparing runs*.

## Loop-speed measurements

The cost model is `mutants × time to run the covering tests`, and both
factors were measured directly:

- Moving one slow-to-cover service class out of a catch-all suite (and
  excluding it there) took that suite from **46.7s to 20.9s**; the new
  single-class suite runs in seconds and is the only suite most edits to that
  class owe.
- Restricting a suite that ran the module's whole test class set to the
  single test class covering its target: **10.6s → 6.1s**.
- PIT's `threads` on an 8-performance-core laptop: 8 threads bought ~10%,
  10 threads was *slower* than 8. Per-mutant work is JVM-bound;
  oversubscription costs more than the parallelism returns.

## System.out in a library factory

A factory method printed a table to `System.out` while building its result.
Nine mutants — the `print` calls and the loop driving them — were unkillable:
nothing asserts stdout, and capturing it would pin output that is not part of
the contract. The fix was not a test: returning the table as a string and
letting the CLI print it made the function pure, the mutants died, and a
library stopped writing to stdout. Baseline: nine accepted entries to none.

## logEpoch: twelve entries, one real equivalent

A service loop's `logEpoch(previous, latest)` held 12 accepted entries, only
*one* of which was a logging removal — the other eleven were branch selection
and arithmetic (a delta, a percentage, a three-way sign word) unkillable
purely because their sole consumer was a string. Extracting construction from
emission — `static String epochLogMessage(previous, latest, now)`, caller
does `logger.log(INFO, epochLogMessage(..))` — made the branch logic a pure
assertable function. The remaining emission is one `VoidMethodCallMutator`,
a genuine equivalent.

The first pass kept the method as-is, arguing it "isn't purely an output
method, because it returns the sample the loop consumes." It returned *its
own argument* — a `void` method with a convenience return. Check whether the
useful-looking return value is actually derived from the work before using it
to justify leaving a side effect where it is.

## The Newton's-method sqrt sweep

A mutant of an integer square root's initial guess (`v/2` → `2v`) was claimed
equivalent. Instead of accepting the prose argument, both variants were
reimplemented outside the codebase and diffed: every input below 200,000 plus
`2^e ± 3` for `e` in 60..129 — zero differences across 200,490 values, range
recorded in the acceptance note. "Verified equivalent over ⟨inputs⟩" survives
refactors that silently rot a prose argument, and the sweep took minutes.

## The sweep that falsified an acceptance

The Newton's-method entry above shows a sweep *confirming* an acceptance.
This one is the other outcome, and the better argument for the rule. A family
of backoff saturation-guard mutants sat accepted as "the delay at that index
is already clamped, so every error count yields the identical delay". A
differential sweep — both variants reimplemented with exact 64-bit wrapping
semantics, ~2 800 configs × error counts through every saturation point plus
the unsigned extremes — refuted it twice over:

- One accepted mutant was **killable at ordinary configs**, and chasing why
  exposed a real bug: the guard read `(max / initial) + initialDelay` where it
  meant `+ 1`, so nano-scale configs overflowed `errorCount * initialDelay`
  before the clamp and `delay()` returned a *negative* number.
- The overflow domain also hid a second bug in a neighbouring strategy: the
  fibonacci constructor walked its sequence in raw longs past F(92), the
  largest fibonacci that fits — a cap just above it produced sequences with
  negative entries, and `Long.MAX_VALUE` as the cap (the natural "no ceiling"
  spelling) **hung the constructor**, live-reproduced and killed after 10s.

The fuzz harness had asserted exactly the violated properties all along —
delay within `[0, max]`, non-decreasing — but capped configs at 16 bits and
error counts at 128, so the overflow domain was unreachable: **a harness's
input domain bounds what its properties can protect**, the same way the
mutator set bounds what the ratchet can see. The harness now reaches the full
positive long range and probes the saturation boundaries; the fixes' own new
guard mutants were then swept the same way (four equivalents confirmed, zero
differences) rather than accepted on the argument that had just failed.

---
## The HTTP 199 guard

The `< 200` half of an HTTP status-range guard survived, and "equivalent"
would have been false: a real 199 *would* distinguish the mutant. But the JDK
client treats 1xx as interim responses and never surfaces one as a final
status — a mock server replying 199 kills the connection before the guard
runs. Accepted as **unreachable in-harness**, naming the escape hatch (a
raw-socket stub speaking HTTP/1.1 by hand) so a later reader can tell whether
the acceptance is still the right trade.

## EXPERIMENTAL_BIG_INTEGER trials

Trialed per suite rather than enabled wholesale. One adoption: a
fixed-point-heavy suite grew 541 → 655 mutants; a second grew by 50; a third
— the most `BigDecimal`-heavy code in the repo — grew by zero, and
`EXPERIMENTAL_BIG_DECIMAL` fired zero times across all three. A second repo
(sava) trialed every Big-mentioning suite: zero fires everywhere — the code
constructs and compares Big values but does no Big arithmetic (its table is
in that repo's `HARDENING_NOTES.md`; the same operators fired 114 times in a
fixed-point-heavy sibling).

The measurement worth internalising: existing tests written under `STRONGER`,
never against these operators, already killed 96–98% of the new mutants —
because they asserted properties (round trips, monotonicity, exact
boundaries) rather than restating implementations. Property assertions
generalise to mutation classes that did not exist when they were written.

## @Inherited is version-dependent

A wandering kill count traced to `@Execution`/`@TestInstance` on an abstract
test base not applying to its concrete subclasses, which interleaved over the
base's shared state (a mock server, an expectation queue) and made kills
order-dependent; annotating the concrete classes fixed it. But before
restructuring another repo's tests on that precedent, the resolved jar was
checked: at JUnit 6.0.3 and 6.1.2 both annotations carry `@Inherited`
(verified in bytecode, not docs), so the annotated base there was fine — and
`@Execution` is moot entirely unless parallel execution is enabled, which it
was not. One `javap` check settled what could have been a nine-class refactor.

## Flip insurance that outlived its cause

Baseline rows unioned in for observed `TIMED_OUT` flips are insurance against
a specific mechanism. One repo later removed the real waits that caused the
flapping (a clock seam); the insurance rows still matched real mutants, so no
stale warning ever fired. A full convergence run — 17 suites, 2297 mutants,
solo-vs-solo and solo-vs-gate — showed **zero** status flips, on baselines
carrying four rows unioned for flips that were no longer reproducible.
Re-measure after removing a flip's cause; nothing else will tell you.

## Unseeded floats and real waits

Unseeded float round-trip tests shifted `DoubleParser` survivors between
consecutive runs — a different fringe of mutants killed each time, baseline
flapping with no code change. Separately, removing two real backoff waits
from one class took it from 2.055s to 0.085s and its suite's PIT run from
~80s to ~21s: PIT re-runs covering tests per mutant, so one sleep is
multiplied by the mutant count.

## Six bugs from unsoftened assertions

In one repo's hardening pass, six real bugs — four of them
silent-wrong-answer defects — were found by writing coverage, hitting an
assertion that could not hold, and reporting it instead of weakening it.
*None* were found by a mutant kill. Mutation testing gets you to write the
test; the test finds the bug.

## Scaffolding mutated by its documenter

Two suites in one adoption pass were mutating their own test-source
scaffolding (`RecordingWebSocket`-style fakes matching no `*Test*` pattern) —
one of them registered by the same person who had just documented that exact
trap. That is why the plugin cross-references mutated classes against test
source directories and warns, rather than the doc having a paragraph.

## The config differential harness

The highest-value fuzz harness in one adoption found no crashes. A config
layer parsed the same logical object from JSON and `java.util.Properties`
via two independently maintained field lists, with only review keeping them
in step. A harness rendering a random config both ways and requiring the
parses to *agree* (or both to reject) turns a renamed key or a shifted
field-matcher ordinal into a concrete counter-example instead of a silent
production divergence. Crash-only fuzzing cannot see a wrong answer.

## MINION_DIED, worker EOF, and the daemon log

A first-invocation-of-the-day `pitestEncoding` exited 1 with no report; a
`test` task failed with `java.io.EOFException`. Both were written up as
"unexplained" — output had been piped to `/dev/null` — until the Gradle
daemon log (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) turned out to
hold everything: PIT's coverage minion had waited ~10s on its localhost
handshake socket, hit `SocketTimeoutException`, and died (`MINION_DIED`,
before any report was written); the test worker had died with no `hs_err`
dump — killed from outside, not crashed. An automatic retry on `MINION_DIED`
was considered and declined at ~1 occurrence per 100 suite runs: it would
mostly mask environment sickness. Per-mutant `RUN_ERROR` under multi-suite
load is the same shape smaller, and the verify summary now names it.
