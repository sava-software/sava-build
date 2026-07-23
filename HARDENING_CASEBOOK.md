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

## PIT's world is the class path

A module-path repo (gradlex whitebox test suites) hit the same trap from both
directions in one adoption. First: a mutant on a `ServiceLoader.load(..)`
success path was uncoverable — the module ships no provider, the patched test
module cannot add a `provides` clause, and a test-resources
`META-INF/services` file would register the provider under PIT but not under
the module-path `test` task, because named modules ignore it. That is a
harness that passes or fails depending on which task ran it; the mutant was
accepted as unreachable in-harness instead. Second, the inverse: a demo
module's round-trip tests discovered its backends via module-descriptor
`provides` clauses alone — green under `test`, and dead under PIT with the
misleading `3 tests did not pass without mutation` (each "failing" in 34ms,
before any mutant existed), because on the minion's class path
`ServiceLoader` found nothing.

The mechanism behind both: PIT minions run tests on the **class path**, so a
module-path repo's suites execute in a world where `module-info` services,
exports and readability do not exist. The fix for the second case was the
standard dual declaration — services named in `module-info` *and*
`META-INF/services` — which is also just correct packaging for a library
that classpath consumers can load. Rules: *declare services in both places*;
*never commit a harness whose result depends on which task ran it*.

## The logger shim that wedged the server

A suite mutating a whole adapter module included its logging shim — the
adapter framework's `LoggerFactory` binding, through which the framework's
own server threads log. The covering tests were socket round trips, so each
shim mutant ran inside the server serving them: one broke the server itself,
and the run sat wedged for 40+ minutes having written 21 of ~63 report rows.
PIT's per-mutant timeout never fired — it bounds test execution, and the hang
was in server machinery underneath the test.

The split that fixed it is not the cost-model split: the shim moved to its
own suite whose `targetTests` are in-process logger tests only (13/13, 100%,
ten seconds), and the dispatch suite excludes the package, so socket tests
still *execute* the real shim but never run against a mutated one. Rule:
*code the harness's own machinery executes gets its own suite, covered only
by tests that do not stand on that machinery*.

## EXPERIMENTAL_NAKED_RECEIVER trials

The BigInteger blind spot has a sibling: fluent APIs. Jetty's
`HttpFields.Mutable.put` returns the receiver, so every response-header write
in that adapter was an expression, not a statement — `VoidMethodCallMutator`
never fired on any of them, and a duplicate header write had already been
found by reading rather than by a survivor. `EXPERIMENTAL_NAKED_RECEIVER`
(replace a receiver-returning call with its receiver) makes exactly that
shape expressible.

Trialed per suite, per the Big-operator protocol: the jetty dispatch suite
grew by 10 mutants — 8 died against existing header assertions and 2 exposed
a real gap, the untested `Content-Type` on 404/405 error bodies; a
query-parsing suite grew by 5 (dropped `String` slicing, list building) and a
log-formatting suite by 7 (dropped `StringBuilder.append` chains), all killed
outright. Enabled on all three at zero baseline cost; the sibling adapters
were not trialed — their header APIs return `void`, so the default set
already sees them. Rule: *any API style that turns statements into
expressions moves defects out of the default mutators' sight; fluent builders
are the common case*.

## Cross-talk on ::1

Three adapter modules' socket tests ran in parallel Gradle projects, each
binding servers to `"localhost"` — which resolves to `127.0.0.1` for a bind —
while their `HttpClient`s connected to `http://localhost:<port>`, which may
try `::1` first. A wildcard-bound server in a *different module's* JVM can
hold the same port number on `::1` without any bind conflict, so the client
reached the wrong module's server: four tests failed with wrong status codes
and bodies — indistinguishable from real regressions — and every retry of a
single module passed, because the collision needed the parallel run. Clients
were switched to `http://127.0.0.1:<port>`; three consecutive parallel runs
came back clean. Rules: *socket-test clients name `127.0.0.1`, never
`localhost`*; *a failure that only reproduces under parallel module runs is
an isolation bug before it is a regression*.

## The handled-flag family that never settles

A Jetty controller returns booleans Jetty ignores once the response is
committed; their mutants are equivalent on the wire but their *detection* is
timing-dependent, flapping between `SURVIVED` and detected across runs — one
row was detected during the seeding run and survived under the quality gate
minutes later. Refreshing the baseline from any single run bakes in that
run's coin-flips: rows detected in the refresh run drop out, then fail the
next run they survive in — refresh ping-pong. The steady state is the
baseline holding the **union of observed survivals**, hand-appending each
newly observed flip in canonical form (the mutator name PIT's baseline
writer uses, `returns.` prefix stripped), and quiet runs reporting recurring
stale-entry warnings that are *expected and permanent*, not line-churn to
clean up. Rule: *for a flip family, stale warnings are the steady state;
union observed flips by hand and stop refreshing*.

## The error funnel

The dominant equivalence family in one adoption, distinct from
result-identical fast paths: mutants whose removal reaches code that *fails*
into the identical observable. A payment verifier's null guard, removed,
reached `Base64.decode(null)` — NPE, caught by the surrounding handler,
mapped to the same error code the guard returns. A controller's
`setStatus(500)` in a catch block, removed, still answered 500 because
`callback.failed(..)` on the next line produces one. A blank
`Access-Control-Request-Method` treated as a pre-flight looked up method
`" "`, which no handler map contains — the same 405 + Allow the
non-pre-flight path returns.

The discipline that keeps these honest: the claim is "the funnel produces
the *identical* response", and that is checkable — same status, same error
code, same payer/payload fields — not arguable. Two of these were verified
by tests that pin the funnel output; the acceptance notes name the funnel.
Rule: *accept a guard's removal only after observing the funnel produce the
identical response, and write down which funnel*.

## The check-loop seam that deleted its flip insurance

A websocket client's check loop held the policy's canonical "never settles"
steady state: five keys unioned in both `SURVIVED` and `NO_COVERAGE` as flip
insurance, each observed flipping across identical runs, with permanent
stale warnings accepted as the cost. The loop interior was reachable only by
builder-path tests whose websockets ran real executors — threads racing the
test scheduler — while the deterministic inline tests covered only the
interrupt- and closed-exit paths.

The first scripted `pitestModeCompare` run (solo vs `qualityGate`) named all
six quiet insurance halves as "unkilled in no snapshotted mode" in 700ms —
the question a hand-diff had never been cheap enough to ask. Cross-checking
the snapshots showed the keys still matched real mutants; the quiet halves
were insurance on a race that had produced two identical observations. Two
data points cannot distinguish "settled" from "1-in-10 flapper quiet twice",
so instead of waiting out a re-measure criterion, the cause was removed the
same day: the loop body became a package-private single-cycle seam
(`checkCycle(long awaitNanos)`, `awaitNanos <= 0` never parks), and three
inline tests drove the interior deterministically — the retry-window resend,
the socketless no-op, and the unhandled-exception funnel, its `ERROR` record
asserted through `System.Logger`'s JUL backend so the funnel cannot go
silent.

The numbers: the refactor shifted every line below the loop, and the churn
classifier read the 124-row failure as `123 shifted, 0 newly covered,
1 unexplained` — the one unexplained was the `unlock()` removal, which had
moved *methods* and correctly refused to pair; it became the family's one
written acceptance (cross-thread-only observable; a timing harness one call
does not earn). Baseline 140 → 130: six insurance halves deleted for cause,
four live halves killed outright, one relocated. The follow-up mode compare:
zero flips of any kind, zero dead rows, and the suite's permanent stale
warning gone. The loop-condition-forced-true mutant stayed `TIMED_OUT` in
both modes — nontermination is PIT-timeout territory by construction, and
with deterministic interior coverage that detection is stable.

Rules: *a background loop interior only racing threads can reach is a
single-cycle seam waiting to be extracted*; *flip families do not settle
while their cause remains — remove the cause and the insurance is deleted
for something*; *an extract-method refactor reports its moved mutants as
unexplained, deliberately*.

## The sibling absorbed by its accepted twin

Found while auditing why a suite's baseline held 968 rows against a report
with 1,030 unkilled mutants: 56 coordinates carried more than one mutant with
identical `class,method,line,mutator` keys — compound conditions emit one
mutant per operand or branch direction, all on one line. The old set-based
comparison collapsed them, which meant a killed sibling could regress to
`SURVIVED` and be silently absorbed by its already-accepted twin's row: a
real ratchet hole, not an ergonomic nit. The comparison is now a multiset —
one baseline row per mutant, duplicates preserved, refreshes
multiplicity-exact — and migrating two suites materialized 62 previously
absorbed sibling copies, every one inside an already-triaged family (in-lock
race guards, null-key arms). The verify also names the killed sibling's test
on such rows (`[detected sibling at this line: … KILLED by …]`), because the
survivor is the opposite branch direction of whatever that test pinned, and
guessing the direction from the mutator name had been the campaign's single
biggest triage time sink.

Rules: *identical rows are distinct mutants — count them, never dedupe*; *a
survivor at a coordinate with a killed sibling is the other branch, and the
killing test says which branch that is*.

## Timeout budgets sized to the tests, not the default

A suite whose slowest quiet-run test took 0.575s was paying PIT's default
per-test allowance — `time × 1.25 + 4000ms` — on every hanging-mutant
detection: ~4s of flat fee against tests that finish in milliseconds. Ranking
the suite's test durations first, `timeoutFactor = 2.0; timeoutConst = 1500`
cut the wall clock ~19% with byte-identical results across a confirmation
rerun. The factor was raised while the constant was cut deliberately: load
inflates a test in proportion to its own runtime, so proportional headroom is
the safe kind. The failure mode to watch is `SURVIVED -> TIMED_OUT` — a
mutant nobody killed reading as detected purely because its tests ran slow —
which the verify now names by origin on the next run. On the same suite,
`threads = 8` on 10 cores *lost* to the 4-thread default (3m32 vs 3m18
back-to-back): the suite's await/signal tests are exactly what
oversubscription inflates, and exactly what PIT re-runs most.

Rules: *rank test durations before touching timeout knobs, and prefer factor
over constant*; *thread counts are measured, not assumed — a timing-heavy
suite can lose throughput to parallelism*.
