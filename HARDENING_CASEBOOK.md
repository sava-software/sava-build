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

## The liveness label that swallowed a finite sibling

Classifying an existing fleet's timeout rows exposed three different phenomena
that the old word "hang" had hidden:

- Ravina's mutated Fibonacci overflow guard still terminates. Its state advances
  bijectively modulo 2⁶⁴ and reaches the requested value only after roughly
  1.4×10¹⁹ iterations. That is finite excessive work (`cause:resource`), not
  watchdog detection, even though no mutation run will wait long enough to see it.
- Three other Ravina mutants were killed deterministically and timed out only
  when a slower covering test lost a load race. They were neither liveness nor
  acceptable resource evidence; the right disposition is to repair/isolate the
  harness and remove them from the audited set.
- An idl-src-gen mutant deleted a guard and blocked on an external pipe read. Its
  fixture's child process exits after 30 seconds solely so a broken test cannot
  wedge forever. That emergency event does not give the mutated path its own
  completion guarantee, so the row remains liveness and the fixture bound belongs
  in its README argument.

The classification also exposed an unresolved identity limitation: Ravina held a
reviewed liveness timeout and an accepted mutant at another line under the same
line-less `class,method,mutator` key. A key-wide cause can therefore cover the finite
sibling whenever that sibling times out. Promoting the row's `# line` tags into an
authorization boundary appeared to close that hole, but the first consumer reformat
showed why source position cannot be record identity. Collapsing imports and joining a
fluent call changed no mutation-site behavior, yet shifted a guard by five lines and
made certification refuse until a human copied a new number into the record.

The pragmatic repair keeps `# line` as diagnostic metadata only. Adding a method,
moving imports, changing indentation, or reflowing a multi-line expression must not
warn, fail, or require re-anchoring. `cause:liveness` remains a key-level judgment,
and the same-key mixed-cause sibling is a known blind spot until PIT evidence offers a
stable discriminator independent of source layout. Positive timeout-count drift still
prints every current line-full candidate so a reviewer can notice widened coverage,
but the line itself grants and revokes nothing. Rules: *classify by whether the mutated
path owns a finite completion guarantee, not by a fixture's safety escape or by
wall-clock practicality*; *do not turn source positions or formatting into behavioral
identity*; *a line-less cause cannot distinguish mixed-cause siblings at the same key,
so treat multiplicity growth there as a review prompt rather than claiming the record
proves more than it can*; *load-only KILLED↔TIMED_OUT races are harness debt, not a
third admissible cause category*.

## The liveness loop that raced the heap

Ravina's `LookupTableCacheMap.getOrFetchTables` iterated set bits with a manual
cursor. `MathMutator` changed `i + 1` to `i - 1`. When bit zero was set, the next
lookup threw immediately and one covering test killed the mutant. When the first set
bit was greater than zero, the cursor returned that same bit forever while appending
the same key to an `ArrayList`. The mutated control flow had no finite exit, but the
machine alternated between watchdog `TIMED_OUT` and heap `MEMORY_ERROR` depending on
which limit won.

That does not make the control flow resource work. It is `cause:liveness`; allocation
is an incidental effect of non-progress. But the cause label cannot promote
`MEMORY_ERROR` into completed evidence: the same status could mean a broken test JVM,
and certifying whichever machine limit wins would make the audit host-dependent. The
sound continuation is the process's existing second outcome — refactor the mutation
site out while preserving the independently tested contract — by traversing
`BitSet.stream()` in order into the same mutable list. Ravina must then use a
history-free run to prove the old cursor mutant absent before its timeout row and
obsolete heap prose leave.

Rules: *cause class describes the mutated path, while report status describes the
experiment*; *liveness authorizes valid `TIMED_OUT`, never `MEMORY_ERROR`*; *when a
non-progressing loop races the heap, make every covering path fail deterministically
without relying on test order or replace manual progress arithmetic with a
behavior-preserving abstraction and prove the coordinate gone*.

## The fresh decision made from cached history

In the same Ravina review, a test change made a previously timed-out mutant
deterministically `KILLED`. An ordinary PIT run still printed the old `TIMED_OUT`
status with its `[history]` marker; only `-PnoMutationHistory` exposed the kill. The
handoff nevertheless named the ordinary run as proof for retiring timeout rows, and
the plugin still let explicit `-PstrictTimeoutAudit` request ArcMutate history. Its
machine-local status and three-run quiet stashes could also be overwritten or advanced
by assisted reports, letting one later fresh run inherit cached observations.

The repair separates checking from deciding. Assisted reports may exercise the
ratchet, but every diagnostic that suggests a committed accepted/timeout edit names
the history-free command. Explicit strict audit disables history and refuses an old
assisted report. Assisted verification no longer writes either local stash, and both
stash formats advance so pre-fix cached observations reset on the first fresh run.
`clean` is irrelevant because `.pitest-history/` deliberately survives it.

Freshness also does not make one covering path a disposition. Ravina bounded one
`ComposedCall` clock, retired the row after one fresh `KILLED` run, and the next
fresh run resurfaced it through the second clock; only bounding both clocks made the
kill independent of PIT test order.

Rules: *a cache hit is not an observation even when the report is newly written*;
*manual accepted/timeout decisions require `-PnoMutationHistory`*; *strict evidence
must disable reuse structurally*; *when the meaning of machine-local evidence changes,
version and reset it rather than trusting bytes written under the old rule*.

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

## The fresh flag that changed the population

A Jetty dispatch suite measured 72 mutants on an ordinary licensed run and 72
under `hardeningCertify`, but 76 with `-PnoMutationHistory`. The flag intended
to disable reuse was wired to ArcMutate *availability*, so it removed
`com.arcmutate:base` from PIT's tool classpath. Certification already disabled
only the history feature and therefore kept the licensed population.

This was not merely a misleading comparison. Mode snapshots, convergence, and
the solo/gate recipe prescribe `-PnoMutationHistory`; their results can feed
`-PunionModeFlips` into accepted baselines. Both mode runs could agree on the
wrong 76-mutant toolchain and record rows for mutants absent from the
72-mutant toolchain certification later proved. Nothing in the mode output
named the four-mutant population change.

The repair separated the two levers: licence presence selects the PIT
toolchain, while the flag and certification suppress only the history feature
and its input/output arguments. A regression fixture now compares the ordinary,
explicitly fresh, and certification classpaths, evidence-bound tool hashes, and
populations. Rules: *freshness controls must not change engine identity*;
*when evidence moves between workflows, bind and compare the toolchain that
defined its mutant population*.

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

A third repo (a vault-statistics service, on-chain fee and NAV math) trialed
at adoption: its pricing suite grew by 43 `BigInteger` mutants (39 already
killed, 91%) and 30 `BigDecimal` (22 killed, 73%); the other two suites fired
zero for both, having no Big arithmetic at all. The unkilled remainder was the
point — twelve `multiply`→`divide` and `subtract`→`add` mutants inside fee and
NAV computations, several on leaf paths with no fixture at all. That suite had
been reporting 66% detection and a green ratchet while its money math was
unmutated. It is the evidence behind the per-run blind-spot advice: the gap
was found because someone thought to run the trial, and nothing in the build
would have suggested it.

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
load is the same shape smaller; the verify now refuses that incomplete report
instead of accepting PIT's detected score.

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
baseline holding the **union of observed survivals**; at the time, each newly
observed flip was hand-appended in canonical form (the mutator name PIT's
baseline writer uses, `returns.` prefix stripped), and quiet runs reported
recurring stale-entry warnings that were expected while the cause remained,
not line-churn to clean up. The named mode comparator now records both the
observed union and its `# flip insurance` note. Rule: *for a live flip family,
use `pitestModeCompareUnion` (or the witnessed-flip `BaselineUnion` escape
hatch plus its evidence note), keep the insured union, and stop single-run
refresh ping-pong*.

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

## The status-blind prune

During a downstream adoption, an agent accepted six newly covered mutants by
hand-appending `SURVIVED # reason` rows — legitimately: the next run matched
them and passed. Then its own cleanup script pruned "since-killed" baseline
rows by matching `class,method,line,mutator` *without the status field*,
first match wins in file order. Each coordinate still carried its old
`NO_COVERAGE` row from the seeded baseline; that stale row, sitting earlier
in the file, consumed the run's one `SURVIVED` mutant and the script deleted
the freshly written acceptance instead. The verify then — correctly — paired
the leftover `NO_COVERAGE` row with the run's `SURVIVED` mutant, reported
`newly covered — triage, not a refresh`, and failed a baseline that had been
right one command earlier. The failure was initially misdiagnosed as the
verify refusing hand-edited acceptances; the tool was right and the script
was wrong, which is the point of recording it.

The second-order cost: recovering via `-PupdateMutationBaseline` rewrote the
flipped coordinates with fresh noteless rows, so the hand-written acceptance
reasons had to be re-added a third time. The refresh now carries a dropped
row's note onto the rewritten row at the same coordinate, annotated
`(carried across NO_COVERAGE -> SURVIVED)` — the argument travels, flagged
for re-reading rather than silently re-trusted.

Rules: *status is part of the row — a `NO_COVERAGE -> SURVIVED` flip is two
different rows at one coordinate, and any script touching a baseline must
match on the full row*; *to accept a newly covered mutant, flip the existing
row's status in place or let the refresh rewrite it — the note survives
either way*.

## The client built in a field initializer

A REST-client test class held its client in a field — `private final client =
buildClient()` — under `PER_CLASS` lifecycle, over a loopback harness that
matched every request's method and path. Coverage-wise that construction runs
once, attributed to whichever test executes first: a dexLabel test, say, which
never calls `swap()`. So the `URI::resolve` wiring mutants for `swapURI` and
`executeUltraOrderURI` read `SURVIVED` with `numberOfTestsRun=2` — PIT
faithfully ran the two tests its coverage said reach the builder, and neither
was the test that drives those URLs. The harness asserting every path was no
defense, because the pairing between "mutant runs" and "request asserted"
could never form. Nothing wanders, so the wandering-count rule never fires;
the row just sits in the baseline looking like a triage judgment call.

One test that builds the client *inside the test body* and drives each
resolved URL killed all of them at once, plus the response-mapping mutant
(`thenApply` dropped: the raw `HttpResponse` future flows through erased
generics until a field access finally CCEs — assert a parsed field, not
`assertNotNull`).

Rules: *construction wiring is only testable by the test that constructs —
build the client in the test method, and drive every URL it resolves from
there*; *a `SURVIVED` builder/constructor mutant in a `PER_CLASS` test class
with a field-initialized subject is this pattern until proven otherwise*.

## The seed clipped by its own max_len

The first `fuzz<Target>Minimize` run in a downstream repo, in pure-dedup mode
— advertised as "a no-op on a corpus whose every seed earns its place" —
reported `6 -> 6 file(s), 1 newly adopted, 1 redundant removed`. It had not
found redundancy. The target's `maxLen` was 1024 and its stack-overflow
regression probe was 3298 bytes: libFuzzer truncates any input longer than
`max_len` on load, so the merge saw a clipped copy with a new hash, the task
adopted the clip under its hash name, and the named original — a minimized
finding — was deleted as "redundant". The clip nested 19 type wrappers
against a depth bound of 64, so the corpus no longer reached what the seed
pinned, and nothing failed: the generated replay test replays whatever files
exist, so `check` stayed green while the corpus quietly lost its finding.

The truncation was not new, only newly visible: every `fuzz<Target>` run had
been loading the same clipped copy, so the campaign had explored a probe that
never reached the bound since the day the seed outgrew the cap. The commit
history made it look deliberate — the seed was committed at 3298 bytes into a
target already capped at 1024, and no tool ever objected.

Both tasks now refuse up front when a committed seed exceeds the target's
`maxLen` (and, under `-PadoptLocalCorpus`, when a stale local input does),
naming each oversized file. Rules: *a target's `maxLen` covers its largest
committed seed — the caps exist to bound exploration, not to re-edit
findings*; *a minimize diff that touches a named seed is triage, not
cleanup — the tool asked you to review the diff because this entry is what a
surprising diff looks like*.

## The note the line shift dropped

During ravina's adoption of the 21.5.10 multiset comparison, six surfaced
sibling mutants were accepted as duplicate baseline rows, each carrying a
`# note` naming its documented family. The next day a one-line comment in the
mutated source shifted every row in the file; the follow-up
`-PupdateMutationBaseline` — run exactly as the verify's own hint recommended
after the shifted rows were confirmed — rewrote all 28 rows at their new
lines and silently dropped all six notes. The status-flip carry never fired:
it keys on the full `class,method,line,mutator` coordinate, and a line shift
is precisely a change in that key. Nothing failed; the acceptance arguments
were simply gone, noticed only because the same agent had written them the
previous day, and restored by hand.

The refresh now pairs a dropped noted row with a *fresh* row that matches on
class, method, mutator and status — the same pairing the ratchet's shift
classifier uses — and carries the note verbatim, no re-read marker, because
a line move changes nothing about the mutant. The safety half has two
exclusions, both inherited from the classifier's precedence. Fresh rows
only: a killed row has no fresh counterpart, so its note dies with it rather
than relabelling an unrelated survivor elsewhere in the method. And fresh
rows that exactly duplicate an accepted row are classified out as surfaced
siblings first: a killed row still reads `SURVIVED` in the baseline, so it
shares the pairing key with a live survivor at another line, and a sibling
surfacing at that survivor's coordinate would otherwise hand the dead row's
note a fresh copy to ride — review caught this via the guard test, not the
original implementation.

Rules: *a note is part of its row, and a refresh that loses one is a bug in
the refresh, not bookkeeping*; *notes travel across both refresh
relationships — marked when the status flipped, verbatim when only the line
did*.

*Postscript (line-less keys):* the shift relationship no longer exists — a
row whose mutant only moved lines IS its accepted row, so there is nothing
to carry and nothing to drop. The rule's surviving half is the fate listing:
within a key, rows are assigned by line affinity, and a note that drops still
drops loudly, named per row.

## The baseline truncated mid-write

During a downstream repo's adoption (2026-07-23), an agent started a redundant
`-PupdateMutationBaseline` run, realized the answer it wanted was already in
the previous run's diff, and stopped the task — landing the kill mid-write
and leaving a one-byte `sdk-accepted.csv`. The next verify read an empty
ratchet and reported the suite's entire unkilled population as "38
unexplained new rows, 0 stale". That inversion was the tell: a healthy
baseline never goes 100% unexplained in one step, it goes stale. The
recovery was a single uninterrupted refresh — but only because the previous
run's report still described the intended content; an interrupt landing
during a busier evening loses the acceptance record outright.

Two fixes with different scopes. The tooling one: baseline rewrites are now
atomic — content lands in a sibling temp file that is moved over the
target, so an interrupt at any point leaves either the old baseline or the
new one, never a fragment. The behavioural one: a refresh run is a
write-transaction on the team's triage record, and killing it is not like
killing a test run — don't, and treat any all-rows-unexplained verify as a
damaged baseline rather than a broken ratchet.

Rules: *writes to the accepted record are atomic or they are bugs*; *a
verify reporting the whole population as unexplained-new is diagnosing the
baseline file, not the code*.

## The recompile that only failed when another compile ran

During the incident-client adoption, `compileForPitest` failed three times in
one afternoon with the same shape: exactly 100 errors (javac's cap), every
import unresolved — external jars, dependency projects, junit itself — and an
immediate green re-run. Each failure looked like a transient and was waved
through with the "run it twice" reflex, which is precisely why it survived
long enough to be diagnosed by daemon-log archaeology instead of at first
sight.

The mechanism was deterministic all along. The recompile read its classpath
from the live task — `tasks.named("compileTestJava").map { it.classpath }` —
and the whitebox JPMS test plugin rewrites `compileTestJava`'s classpath
*while that task executes*, moving entries to the module path. So the value
the recompile observed depended on whether `compileTestJava` had actually run
in that invocation: after a clean or a test-source edit it executed, the
property was emptied under the recompile's feet, and the build failed; on
retry the task was up-to-date, its `doFirst` never fired, and the stale
configured value read fine. Reproduction was one command once the mechanism
was suspected: `clean` then `compileForPitest` failed every time.

The fix reads the *configured* `sourceSets.test.compileClasspath` instead of
the live task's property — same contents, immune to execution-time rewiring.
Rules: *a "transient" with a reproducible precondition is a bug wearing a
flake's clothing — the daemon log keeps the evidence to tell them apart*;
*never read another task's mutable state at execution time; snapshot
configuration-time state instead*.

## The sibling guessed wrong three times

Triaging the incident-client seed, three survivors in a row were misread
before the XML settled them. A compound condition compiles to one mutant per
operand and branch direction, all sharing `class,method,line,mutator` — and
"removed conditional - replaced equality check with true" does not say which
operand. Each time, the plausible reading ("the isEmpty check, obviously")
was wrong: the survivor was the null-operand direction reachable only through
a caller-supplied implementation, or the blank-string direction of a
`null || isBlank()` chain whose null side was already pinned.

The tell that resolves it without guessing already existed in the report:
`mutations.xml` records which test killed the twin at the same coordinate,
and the survivor is the *opposite* direction of whatever that test pinned.
The verify computed exactly this hint — `[detected sibling at this line:
KILLED by <test>]` — but printed it only on ratchet failures and scoped runs,
not on `-PlistUnkilled`, which is where triage actually reads rows. The fix
wires the hint into the `listUnkilled` listing. Rules: *identify which
sibling survived before arguing with it — the killed twin's test names the
direction*; *put triage information where the triager is looking*.

## The stub that returned the mutant's value

Two adapter suites carried an unkillable `NullReturnValsMutator` on a
`httpClient()` delegation — because the test stub's own `httpClient()`
returned null, so replacing the delegating return with `null` produced a
byte-identical observable. The mutant was equivalent purely by accident of
the fixture; a one-line stub change (return a real shared `HttpClient`) plus
an `assertSame` turned both into ordinary kills.

This is the fixture-value trap in general form, already known in one
special case — a test clock starting at 0 makes every "timestamp mutated to
0" mutant equivalent. A stub returning null, 0, `""`, `true`, or an empty
collection silently blinds the corresponding return-value mutant wherever
the stubbed value flows. Rule: *fixtures return distinguishable, non-default
values — a stub that returns the mutator's replacement value has withdrawn
that mutant from the game before the tests were consulted*.

## The copy-on-write family that split

The incident-client repo's guidance said survivors from the copy-on-write
builder pattern (`size() > 1 ? copy : as-is`) "are largely equivalent
mutants; don't chase them" — and 22 of the seeded rows matched the pattern.
Triaging them as one family would have been wrong in the direction that
matters: the family splits by branch direction, and one half is real.

The content-equal half (`ORDER_IF`, boundary flips routing a size-≤1
collection through the copying branch) only ever exchanges one immutable
collection for an equal one — genuinely equivalent, accepted with the
family label. But the mutable-escape half (`ORDER_ELSE`: a multi-entry
`ArrayList`/`LinkedHashMap` returned as-is where the code promises an
unmodifiable view) is observable with one assertion the API already
implies: `assertThrows(UnsupportedOperationException, () ->
record.links().add(...))`. Immutability tests at both sizes killed every
escape-direction mutant in the family, leaving only the equal-content
siblings for acceptance — and one seeded row that read as family noise was
exactly such an escape. Rules: *a family acceptance is per-direction, not
per-pattern*; *assert immutability of returned collections — it converts
the escape half of every copy-on-write cluster into kills*.

## The silence that named the wrong logger

A 600-second six-target campaign died at the ten-minute mark with a 5.1 GB
log: 6.9 million SEVERE lines, each carrying a full mutated IDL document.
The harness *had* the flood defense — a held reference to the JUL logger for
the parser class, level OFF, exactly as documented — and it silenced
nothing, because the parser's ERROR calls go through a `System.Logger` field
it inherits from an interface, so the logger's name is the *interface's*
fully-qualified name. JUL's formatter prints the source class and method
(`AnchorProgramDefinition parseIDL`), so the flood itself displayed the name
the silence was keyed to; every eyeball check confirmed the wrong belief.
The second unsilenced logger (a named-type parser with its own ERROR spray)
was found the same minute, once the first lie was visible.

Rules: *silence a logger by its declaration site — an interface-inherited
`System.Logger` carries the interface's name, and the formatter's class
column is not the logger name*; *verify a silence empirically — a silenced
harness prints zero lines, and reading the output format is how this one
survived review*.

## The fuzzer frozen by its own stdout

The relaunched campaign streamed through a filtering pipe whose consumer
died at the same ten-minute mark. Three of six targets then "ran" for an
hour: log file static, JVMs alive, threads `RUNNABLE` inside native
`startLibFuzzer` — which reads as a healthy quiet stretch, because a blocked
native `write(2)` to a full pipe does not park a Java thread state. What
settled it was the CPU column, not the thread dump: ~153 s of CPU over
3,900 s elapsed, and no growth between samples. libFuzzer freezes at
whatever instant its progress write cannot complete, silently discarding
the rest of the campaign.

The first kill had a second deception waiting: six `crash-<hash>` files, one
per parallel target, all stamped the kill minute. Replayed against every
harness, none reproduced — libFuzzer dumps its in-flight input on abnormal
death, so kill artifacts wear crash artifacts' clothing and classify only by
replay.

Rules: *campaign output goes to a file, never through a consumer that can
die mid-run*; *a fuzzer that stopped printing is diagnosed by CPU delta,
not thread state*; *crash artifacts stamped at the kill moment that replay
clean are dump-on-death, not findings*.

## The memoizing factory that could not be killed

A vault-statistics service had a family of `UnsupportedReason` factories that
memoize by public key: `computeIfAbsent(key, k -> new Reason(k, "..."))` over a
static `ConcurrentHashMap`. Fifteen mutants sat in those factories. Every
attempt to kill them failed, and each one read as a textbook equivalent — the
returned reason was identical whichever branch ran.

They were not equivalent; they were unreachable. PIT reuses minion JVMs across
mutants, so the static map still held the entry an *earlier* mutant's run of
the same test had inserted. With the test hard-coding its key, `computeIfAbsent`
found a hit and never invoked the mutated lambda at all. The mutant's code did
not run, so nothing could observe it — and "no test can observe this" is
precisely the sentence that gets a row accepted as equivalent.

Minting a fresh key per invocation (a counter in the test helper, so every call
takes the cache-miss path) killed thirteen of the fifteen outright. The two
that remained were genuinely equivalent and are accepted with that argument.

The rule: an unkillable mutant on a cache-miss path is a fixture bug until
proven otherwise. Ask whether the line can still execute before asking whether
its effect can be seen — process-lifetime state outlives the mutant that
created it, and a test that always supplies the same key only exercises the
miss path once per JVM, not once per mutant.

## The unlabeled row the shift reclassified

`# untriaged` seeding arrived in 21.5.12, so every baseline row written before
it is bare: no note, its acceptance argument living in the suite README under a
section the row itself never points at. Those rows are counted as their own
state — the verify summary and the debt listing both print `5 unlabeled`
separately from `13 '# untriaged'` — precisely so that settled-but-old triage
does not read as work outstanding.

A refresh converted them anyway. The line-shift carry added for *the note the
line shift dropped* builds its pairing pool with `mapNotNull` over the
annotations, which is exactly the set of rows that have a note; a bare row is
invisible to the lookup by construction. So a bare row fell through the shift
branch to the seeding branch, and any edit that moved lines — a javadoc
paragraph above the mutated method was enough — rewrote it as `# untriaged`.
Nothing failed and nothing was lost: the row was still accepted, still at the
right coordinate. Only its triage state had been reset, and the debt count went
up by rows that had been argued weeks earlier. The two prior fixes in this area
both taught that the refresh must not lose what a row records; this was the
same lesson at the state level rather than the note level.

The original fix paired bare dropped rows against fresh rows on the same
class/method/mutator/status key, in a second pool disjoint from the note pool,
after the note lookup and under the same exclusions — fresh rows only, surfaced
siblings classified out first. Only the shift is paired this way. A status flip
was deliberately left to seed debt: it changes what the mutant proves, so an
argument made before the flip had to be re-made.

Line-less keys later removed line shifts from baseline identity entirely. The
current updater carries a written note across a `SURVIVED`/`NO_COVERAGE` status
transition, marks that carry for re-reading, and uses maximum line affinity when
several siblings flip together. The transition remains visible without silently
discarding the only acceptance argument the row carried.

The pairing carries the note carry's ambiguity and carries it worse. The key
cannot distinguish a moved mutant from a killed unlabeled row plus genuinely
new debt elsewhere in the same method, and where the note pool holds only the
argued rows, the bare pool holds *every* unlabeled dropped row. A mispairing
here is silent in the worst way — new debt enters the baseline unlabeled, which
is to say it enters looking like triage someone already finished. Review pushed
the diagnostic past a bare count for that reason: the dropped-rows listing now
names the line each bare row was paired onto, the same way it names each note's
fate, so a wrong pairing is something you can read in the refresh output.

Rules: *a refresh may move a row but must never change its triage state*; *a
pool built by `mapNotNull` over annotations silently excludes the unannotated —
check what the absence of a note means before keying on its presence*; *when a
pairing can be wrong and its wrongness looks like success, print the pairing,
not a count of them*.

*Postscript (line-less keys):* the bare-shift pairing this entry hardened is
retired with the shift itself — an unlabeled row now keeps its state because
its key never churns, not because a pairing recognized the move. The
`mapNotNull` rule outlived the machinery: it is why baselines now parse as
ordered (key, note, lines) rows everywhere.

## The killed row recycled onto new debt at the same key

The line-full refresh paired dropped and fresh rows by
class/method/mutator/status to carry notes across line shifts, and the
pairing had one unauditable failure mode: a killed unlabeled row at one line
and genuinely new debt at another share that key, so the refresh paired them
and the new mutant entered the baseline looking settled. The compensating
heuristic scanned each class's pairings for a delta moving against the
strict-majority dominant one (`PAIRING OUTLIER`), softened same-delta groups
into a second-edit-region note, and re-zipped identical siblings in line
order after crosswise pairings produced two outlier warnings in production
that a human had to disprove by multiset comparison — a heuristic accreting
exceptions is a heuristic describing the wrong invariant.

Line-less keys dissolved the machinery and kept the hole: with no shift to
pair, a same-key kill-and-replace is simply invisible to the multiset, and
the doctrine now names it as the format's one deliberate blind spot instead
of half-covering it. The compensating control moved to metadata: every
refresh writes `# line` tags, and the drift advisory — row-level when every
row is tagged and counts match — fires whenever a mutant sits at a line no
tag names, which is more often than the outlier scan ever fired truthfully.

Rules: *a heuristic that accretes exceptions is describing an invariant the
design does not actually have — name the hole instead*; *when identity
churns, the machinery compensating for the churn inherits its failure
modes*; *a documented blind spot with a cheap tripwire beats an undocumented
one behind a scan that cries wolf*.

## The green run against stale classes

A source edit raced a concurrently running build; the next `pitestWs`
invocation reported `compileForPitest` UP-TO-DATE, ran PIT for real — 37
seconds, a full fresh report — against the pre-edit classes, and came back
green with zero baseline drift. The edit had added lines that had to shift
dozens of accepted rows, so zero drift was the tell, not the reassurance:
editing sources while a build is running can poison Gradle's incremental
state, and nothing downstream can notice — the report is fresh, the tasks
ran, the ratchet compared honestly, all against the old bytecode. The next
invocation compiled for real and told the truth (34 rows of drift).

Rules: *after a source edit, an implausibly quiet mutation run is suspect
until the log shows `compileForPitest` executed rather than UP-TO-DATE*; *a
certification is only as fresh as its compile — "UP-TO-DATE" for a compile
of the file you just edited is a contradiction, read it twice*; *never edit
sources while a build that will certify them is running*.

## The union write that deduped siblings

`pitestModeCompare -PunionModeFlips` parsed the baseline into a `Set` for its
insurance-membership checks — reasonable for membership, wrong for the write
that followed: rewriting the file from that set silently collapsed duplicate
sibling rows, a baseline shrink outside prune's rules that no output named.
Found not by a failure but by porting the parser to the shared pair-list
form and asking why the old one could not represent duplicates. The doctrine
already said the comparison is a multiset and the file keeps one row per
mutant; the code path that wrote the file was never held to it.

Rules: *every baseline reader uses the shared parser, because a private parse
is where a format invariant quietly dies*; *a data structure chosen for one
operation (membership) must not leak into another (rewrite) whose invariants
it cannot represent*; *when doctrine says multiset, grep every `.toSet()`
between a baseline read and a baseline write*.

## The partition the audit called a hole

The excluded-production-class advisory shipped in 21.5.19 and met its first
partitioned consumer the same week: a repo whose suites split one package
tree for inner-loop speed, each suite excluding the classes its siblings
target — the targeting policy's own "classes owned by another suite"
category. The audit knew only its own suite's globs, so a dry-run port of
its policy predicted ~80 advisory lines per real run, every one naming the
repo's deliberate structure. None of the advisory's remedies (rename, narrow
the glob, `recompileExcludes`) applied, and a permanent advisory is worse
than a wrong one: it trains readers to skim the channel every real finding
prints on. The release had also passed the fleet canary clean — the audit's
only trigger sat inside a real `pitest<Suite>` execution, which the canary
never performs, so the canary was structurally blind to the one check most
sensitive to real consumer globs. Fixed in both directions: sibling-suite
scopes are subtracted (ownership must be effective — a sibling whose targets
match but whose own exclusions also swallow the class is not an owner, so a
class excluded everywhere stays a finding in every suite that swallows it),
and the audit grew a static half in `pitest<Suite>Debt`, gated on recompiled
classes a prior run left behind, which puts it where the canary already
looks.

Rules: *a check must recognize every exclusion category the doctrine
endorses, or it will flag the doctrine*; *an advisory that fires on a repo's
deliberate structure every run is not noise but corrosion — it teaches
readers to skim the channel*; *every advisory needs a cheap read-only surface
that local adoption actually executes, because a check that only fires inside
a real mutation run ships unvalidated against real consumer data*.

## The advisory that named two thousand deliberate exclusions

The pre-release fleet canary for the partition fix answered a question the fix
itself had not asked: with sibling handoffs subtracted, what does the
excluded-production-class advisory still print against real consumer globs?
About two thousand lines across five repos — 1602 from a single suite, every
one of them a generated program binding under one `*.gen.*` exclusion, plus
253, 59, 46 and 41 from the same shape elsewhere. The partition fix had halved
the problem and left its larger half standing.

The targeting policy endorses three exclusion categories; the audit could
derive two. Test sources are found by source root and sibling ownership by
comparing suite scopes, but "these are generated bindings" is a judgment about
what classes *are*, and nothing in the globs distinguishes it from a forgotten
exclusion. None of the advisory's three remedies applied either — you cannot
rename generated code, narrowing the glob does not change what it swallows,
and `recompileExcludes` would drop sources the mutated code compiles against.
So the category was written down instead, on the `declineMutator` contract
already in the plugin: `declineExclusionAudit(glob, reason)`, keyed by the
glob so fifty swallowed classes are one decision, with a blank reason
suppressing nothing and a record that stops matching reported as deletable.
Trialled on the worst repo, one record took 1602 lines to 5 — and those five
were git-ignored integration mains, the same category needing their own line.

Rules: *when a check flags a category the doctrine endorses, the fix is a
channel to argue the category, not a quieter check*; *measure an advisory
against the whole fleet before shipping it — synthetic fixtures cannot
enumerate what real globs exclude*; *a remedy list that omits the remedy the
reader needs is how an advisory teaches skimming*.

## The flip that fired forever

The line-less coordinate landed in 21.5.20 and the baselines, the audited
timeout sets and the mode compare were all converted to match. The status
stash was converted too — its coordinate function is shared — but the
comparison built on top of it was not. It asked two sets: is this coordinate
timed out now, and did it hold a survivor last run. Under the old line-full
coordinate one key meant one mutant, and the answer to both could only be yes
if that mutant had moved. Line-less, a key is a bag of siblings, and a
compound condition routinely puts an accepted survivor and an audited timeout
in the same bag.

The first consumer to bump saw three such keys. It fired on every run,
reporting a flip in populations that were byte-identical run to run — and it
fired on the one advisory whose entire purpose is to stop a reviewer, the
mutant nobody killed now reading as detected. Worse, it was invisible for
exactly one run: the first run after the bump still had the old line-full
stash to compare against, nothing intersected, and the advisory stayed quiet
until the run that rewrote the stash in the new shape. A reader who checked
the upgrade run and moved on saw nothing.

The fix is the same shape the baseline comparison had already taken for the
same reason: compare per-key **counts**, not sets. A flip is a key whose
timeout count rose *and* whose survivor count fell. A key that gains a
timeout with its survivors intact is the benign `KILLED -> TIMED_OUT`
flavour, and a key that changed in neither direction is not news. The
set-subtraction one-liner that also silences the false positive
(`- prevTimedOut`) was rejected: it buys quiet by making any key that already
holds a timeout unable to ever report a real flip, which is the same blind
spot in the other direction. Counts keep one blind spot of their own, but a
transient one: a flip and a resolution coinciding at the same key in the same
run cancel each other's deltas and that run stays quiet — it takes a
coincidence to hide rather than a structure, and the warning re-fires on the
next run whose counts diverge, where the rejected subtraction stays blind at
that key forever.

A later consumer exposed a visibility seam between that count comparison and
the audited-timeout set. Audit membership is intentionally line-less: once
`class,method,mutator` was present, another sibling at that key becoming
`TIMED_OUT` was no longer "unaudited." The drift classifier still saw the
timeout count rise and correctly kept `KILLED -> TIMED_OUT` benign, but printed
only `1 newly timed out`; the one fact a reviewer needed — *which existing
audit argument now covered more mutants* — was absent unless they opened the
CSV and reconstructed it. Drift results now retain and print the changed
coordinates with `+N` multiplicity and the current line-full timeout
observations by default. Because the stash cannot identify which duplicate was
timed out previously, a key that already held a timeout prints **all** current
candidates rather than guessing which line is new. An audit row can silence the
missing-membership warning, never the evidence that its timeout population
grew or the lines a reviewer must inspect. Cause categories later made the
remaining identity limitation explicit: a key can hold both a liveness and a
resource sibling. Source-line tags cannot soundly close that gap because ordinary
formatting moves them, so they remain diagnostic metadata and never an authorization
boundary *(casebook: the liveness label that swallowed a finite sibling)*.

Rules: *when a key stops identifying one thing, every comparison built on it
is a multiset comparison — convert them together, not the ones you
remembered*; *an advisory that cannot distinguish "nothing moved" from "the
dangerous thing happened" is worse than no advisory, because it trains the
reader to filter the dangerous thing*; *a format migration that silences a
check for exactly one run hides its own regression from the person doing the
migration*; *line-less membership names the family and cannot distinguish a
mixed-cause sibling at the same key; source-line metadata may expose that risk for
review but cannot resolve it*.

## The trap that never existed

A repo's baseline carried twelve `NO_COVERAGE` return-value mutants across
two families, all accepted on the same argument: the lines were `return
f(...)` sites whose callee throws for every input reaching them, and
covering them would convert provably-equivalent mutants into fresh
`SURVIVED` entries. The argument was written into a test class's javadoc as
a deliberate-absence rationale — the inputs that would reach those lines
were kept out of the suite on purpose. It read as settled prudence: the
repo had taken a real version of that trap knowingly two weeks earlier and
documented the price.

The mechanism was wrong, and one existing test proved it. A quoted `"1e"`
had flowed through `readDouble()` since the parser landed — straight into
one of the twelve lines — and the row read `NO_COVERAGE` in every report
regardless. PIT's block coverage probes a block at its end: a block that
always exits by throw never completes, so its execution is invisible, and
its return-value mutants can never change status because the throw happens
before the mutated return. The feared conversion to `SURVIVED` requires the
block to complete, which is exactly what these blocks never do. The trap
was structurally impossible for every row that cited it.

The deliberate absence had therefore bought nothing and cost real coverage:
the untested inputs were the malformed-number and truncated-document shapes
whose exception contract the library explicitly promises. Writing the
missing tests changed no row's status — the twelve stayed `NO_COVERAGE`, as
the mechanism predicts — but killed ten *accepted `SURVIVED` siblings*
whose routing arguments ("sends more inputs to the reference oracle") had
only ever been checked in the equivalent direction. A mutant that routes
malformed input *away* from the oracle returns a number where the contract
demands a throw, and only the deliberately-absent inputs could see it.

Rules: *an acceptance argument must name its mechanism, not just its
conclusion — "covering this creates survivors" is checkable, and nobody
checked*; *a `NO_COVERAGE` row is not proof that no test reaches the line —
when a test demonstrably feeds it and the row stays unreached, the block
never completes*; *a deliberate test absence is a standing bet that the
absent inputs observe nothing — re-price it whenever the family it protects
changes*.

## The stale hint that named the wrong flag

After a killing pass, a suite's verify printed the shrink invitation it was
designed to print: "5 stale entries (since killed) — refresh with
-PpruneMutationBaseline (shrink-only; nothing new to bake in)". Prune then
reported "dropped nothing — every row matches this run". Both messages were
telling the truth by their own definitions, which was the defect: the
verify's stale count is a multiset comparison (a key holding thirteen rows
against twelve unkilled mutants has one stale row), while prune's matching
was key-level (twelve live mutants meant the key "matched", so all thirteen
rows stayed). The excess row slipped into the cross-status keep — its
coordinate held unkilled mutants, satisfied by the very same-status siblings
that had consumed the key's budget — and was reported as a "flip pending
triage" that no classifier would ever resolve.

The line-less key made this ordinary. Under line-full keys a killed sibling
was its own key and prune dropped it; line-less, every compound condition is
a key whose siblings share identity, and a partial kill at such a key is the
*expected* outcome of a good testing pass. The workaround was
`-PupdateMutationBaseline` — the flag whose safety depends on run conditions
— reached for precisely because the always-safe flag could not do the job it
was recommended for.

Two fixes travelled together: the cross-status keep now requires an
*unmatched* counterpart — an unkilled mutant at the coordinate whose
different status no row of its own key accounts for, consumed one per kept
row, which is the verify's newly-covered pairing rather than an
approximation of it — and which sibling is dropped follows line affinity
before file order — a row whose `# line` tag names no live line is preferred
as the absent sibling — so a noted live-anchored row is not dropped for its
bare sibling. Duplicate same-line siblings remain intrinsically ambiguous.
The unmatched requirement was the second dig at the same
spot: a coordinate-level *status* check fixed the same-status case but let a
mutant already matched by its own row vouch for a killed sibling at a
status-heterogeneous key (a `SURVIVED` row beside a `NO_COVERAGE` row —
three such keys existed in the fleet when this was written), reopening
"prune dropped nothing" against a hint that named it.

A later review found the recommendation itself still claimed more than one
report knew. Even when the hint and prune choose the same row, "since killed"
is an inference: an uninsured mutant that survives solo and reads killed under
gate load is indistinguishable from stable removal in that gate report. The
verify now prints a preview of the exact rows prune would remove, calls them
candidates, and says what evidence is missing: re-observation under the
relevant modes. It does not prescribe the destructive flag. Mode comparison
closes the other half of the loop: baseline multiplicity without a literal
`flip insurance` marker is reported as covered-but-unmarked, and
`-PunionModeFlips` annotates deterministic existing rows before adding only a
true multiplicity shortfall. The evidence now survives the next killed read
instead of depending on the operator remembering why a plain row existed.

Rules: *two mechanisms that share a definition must share the code that
computes it — the verify counted stale rows one way and prune matched them
another, and the gap between them was a recommendation that could not work*;
*a one-run preview can identify a proposed mutation but cannot authorize it*;
*when a hint names a flag, a test should hold the hint to it end-to-end,
through the flag's actual effect on the file*.

## The canary that skipped two consumers

Twenty plugin releases into the hardening rollout, a fresh inventory found
that the pre-release fleet roster was not the fleet. `idl-clients` and
`glam-sdk-java` both consumed the hardening plugin but appeared nowhere in the
manifest. Between them they carried six committed mutation-suite baselines and
fifteen registered fuzz targets — exactly the real-data shapes the canary exists
to exercise. Nothing in a green run disclosed their absence because the roster
was treated as truth.

The runner had a second ambiguity: no-argument mode skipped a listed repo whose
sibling checkout was missing and still exited green. That is useful while
developing on whatever happens to be checked out, but the same command was the
documented release check. It recorded neither consumer commits nor dirty state,
the Gradle tasks it actually found, or the plugin commit that `0.0.0-test`
contained. After the terminal scrolled away, “the fleet passed” could not answer
which fleet, at which revisions, against which candidate. A GitHub-driven fleet
workflow had briefly been tried and then removed as disproportionate; removing
it without supplying a strict local contract restored the original ambiguity.

The repair kept both legitimate workflows and named the boundary. Ordinary
mode may skip unavailable siblings and accept explicit subsets. Release mode
requires every canonical slug, matching remotes, and clean trees; discovers
registered tasks rather than inferring the suite inventory from whichever
files already exist; and writes an immutable run bundle with hashed publish,
consumer, and inner-certification evidence under ignored `build/`. Starting a
rerun first changes the canonical pointer to `in_progress`, so interruption
cannot leave yesterday's pass looking current. The build-free validator
rehashes the bundle and checks still-present consumer revisions. The release
owner retains and validates both bundles before merging the Release Please PR;
at that stage, the tag workflow could not consume this machine-local evidence.
Long fuzzing is invoked explicitly with a recorded budget; a cron schedule and
arbitrary wait window remain optional.

Rules: *an inventory is part of the safety mechanism — audit it from the
consumers, not only from itself*; *“skip” and “certify” are different commands,
even when they share an implementation*; *green without revisions, task names,
and retained results is a recollection, not release evidence*; *when automation
is too expensive, replace it with a strict local contract rather than a softer
claim*.

## The certification that the release path could not see

The strict local fleet and fuzz runners fixed the cost and evidence problems, but
their deliberately uncommitted bundles left one structural gap: neither the action
that created the release tag nor the tag-triggered publisher could tell whether the
owner had run them. GitHub's artifact attestation proved where the published JAR was
built; it did not attest that the consumer fleet had certified that candidate. A
perfect local run and a forgotten local run therefore looked identical to automation.

The repair keeps the large bundles local and commits a small, versioned owner
attestation. Its generator re-verifies both canonical pointers, requires the fleet and
fuzz receipts to name the same plugin commit, tree, JAR, manifest, and consumer
`{slug, commit, origin}` inventory, and permits only Release Please metadata after the
certified candidate. Release Please refuses to create a pending tag without that file;
the publisher verifies it again against the exact tag checkout. The record carries
hashes, not machine paths or the evidence itself, so the immutable bundles still have
to be retained. This is a forgetfulness and stale-candidate gate, not a new trust root.

Rules: *expensive local evidence can have a cheap committed handoff*; *artifact
provenance and process certification answer different questions*; *the operation that
creates a release must observe the evidence required to authorize it*.

### Supersession: reviewed adoption, not a duplicate release fleet

The first implementation made the strict aggregate fleet and fuzz receipts mandatory at
tag time. The 21.5.22 recovery demonstrated that this repeated the repository-by-repository
adoption work against older snapshots, amplified load-only PIT failures, and turned a useful
diagnostic into release ceremony. The release record now names the local adoption passes the
owner actually reviewed and binds their exact candidate JAR. The no-build-cache tag build
must reproduce that JAR before publication. Strict aggregate runners remain available when a
change warrants them, but cutting a release does not itself require another fleet campaign.

Rules: *record the evidence that informed the decision; do not manufacture a second process
merely to satisfy the record*; *bind reviewer judgment to exact artifact bytes*; *optional
diagnostics stay optional*.

## The migration refresh that deleted its evidence

The first Sava consumer to take a candidate PIT bump followed the prescribed
version-migration path: observe the new population, change the suite stamp, then run
the full baseline update. The ordinary verify and the writer described the same row
in opposite terms. Verify said an accepted `checkCycle` `unlock()` mutant had read
`TIMED_OUT` under load — detection by the watchdog, not a kill — and that prune would
keep it. Update then removed that exact row among ten entries “not unkilled this run.”
Nine of the ten were absent from the new tool population; none was a demonstrated
test kill. A second suite lost another load-dependent row the same way. Only a manual
before/after snapshot exposed the distinction.

The writer was internally consistent: its pure transition received only the current
`SURVIVED`/`NO_COVERAGE` multiset, so every accepted row absent from that multiset was
a drop. The timeout-budget and flip-insurance machinery had already computed which
absences were protected, but Update never read that plan. A workflow once described
as a deliberately destructive full rewrite had quietly become the standard tool-bump
migration, putting the old footgun on the release path.

The same review found the provenance boundary too narrow. Sava's `ws` suite measured
605 mutants without ArcMutate and 573 with it on identical source, while the committed
record named only the PIT version. Adding, renewing, or expiring the certificate could
therefore look like code churn with no toolchain warning. The certification receipt
bound the current classpath, but no portable committed identity existed to compare on
the next run.

The repair separated ordinary debt editing from provenance migration. Update now
shares the row allocator: budgeted timeout rows and rows protected by a key's literal
flip-insurance marker persist, while status transitions still carry their notes to
the current status. Every other removal says whether PIT reported a kill at a recorded
line, omitted the coordinate entirely, or left sibling identity ambiguous. A new
`BaselineRebase` is the only task allowed to cross PIT or mutation-toolchain
provenance: it runs fresh and strict,
preserves every old row, adds new current rows as `# untriaged`, and commits the PIT
and portable toolchain sidecars with the safe-superset record through one rollback-
capable plan. A new record puts sidecars first and can leave only fail-closed orphans;
an existing-record Rebase puts conservative safe-superset content first so an N-1
reader never sees debt removed under stale provenance. Old population entries can be
pruned later, after the evidence required by prune's own doctrine exists.

Rules: *detected is not synonymous with killed*; *a tool migration starts with a
safe superset, never a destructive rewrite*; *the committed provenance identity is a
portable PIT-engine/tool-classpath identity, including licence bytes and expiry; the
per-run evidence and certification receipt separately bind the JDK and sava-build
plugin*; *a removal message must say what the report proves, not the cause an operator
might infer*.

## The output file that selected the configuration-cache graph

A consumer's cold configuration-cache check stored successfully, and an immediate
second check reused it. Running PIT then created
`build/reports/pitest/<suite>/.evidence.tsv`; the next identical check missed the cache
because that filesystem entry “has been created.” `clean` removed it, and the following
check missed again because the same entry “has been removed.” The plugin's own output
made a stable consumer graph alternate forever between two cache keys.

The evidence validator was sound at execution time. The defect was the configuration
branch that scheduled it only when `.evidence.tsv` already existed. Gradle correctly
tracked that existence query as a configuration input, so a generated report sidecar
became graph structure. Cold-store tests had proved that each individual shape could be
serialized; none exercised the transition from absent to present and back.

The fix made the graph invariant. Verification and mode snapshots always depend on the
typed validator; its action checks for a completed manifest first and returns before
realizing the expensive classpath/source collections on the legacy no-manifest path.
The regression test now stores once, creates the evidence file, verifies reuse, removes
it, and verifies reuse again.

Rules: *build output is execution state, never a configuration-time graph selector*;
*configuration-cache coverage includes state transitions, not only cold storage*;
*when a validator is conditional, keep the task edge invariant and make the action
cheap on the no-evidence path*.

## The clean proof that erased the fuzz proof

A consumer completed its local `fuzzAll` campaign, then followed the supported cold
mutation proof with `clean hardeningCertify`. The fuzz aggregate lived under
`build/hardening/`, so `clean` removed it without warning. Both expensive observations
had succeeded, but they could not coexist long enough for the release runner to retain
them. Reversing the checklist order only moved the trap: a later clean build could still
erase valid campaign evidence, while leaving no marker that distinguished deliberate
cleanup from an interrupted or never-started fuzz run.

The aggregate was also being asked to serve two incompatible lifecycles. It is generated
state and must never be committed, but it is durable evidence of an explicit campaign,
not a disposable product of the current build directory. Moving the receipt and its
in-progress sentinel to the already-ignored project-local `.pitest-history/` boundary
made that distinction concrete. `clean` now preserves a completed receipt. Starting a
new campaign validates and deletes the durable receipt before any target runs, atomically
publishes the sentinel, and only then checks and removes the one-release legacy state.
That ordering matters: even a malformed legacy path leaves the new attempt visibly
incomplete. The legacy path is confined to Gradle's configured build directory rather
than the checkout, because centralized builds may put it elsewhere. Success writes the
new receipt while the sentinel is still present and clears the sentinel last; failure or
interruption therefore leaves no state that can be mistaken for a pass. The release
runner invalidates regular receipts in both locations, refuses any filesystem entry at
either generation's running-sentinel name, and retains only the new durable receipt.
The aggregate also holds an OS lock for the full Gradle invocation. Without that ownership,
an older campaign could finish after a newer one failed, publish its own receipt, and delete
the newer failure sentinel. A competing invocation now fails before touching either file.

Rules: *generated does not mean disposable — choose lifecycle from the claim an artifact
supports*; *a new attempt invalidates the prior success before doing work*; *publish
success before clearing the in-progress marker, so every interrupted state fails closed*;
*a release checklist should not need a magic ordering merely to keep two valid proofs
from deleting one another*.

## Eight per-target minutes compressed into one

A Ravina adoption selected eight fuzz targets with a 121-second budget under Gradle's
parallel execution and completed the aggregate in about 135 seconds. The number looked
efficient, but it exposed two missing contracts. `maxFuzzTime` was intended to describe
each target's opportunity to explore; running every CPU-bound native driver at once made
the work achieved by that wall-clock budget depend on fleet shape and machine load. The
fuzz tasks also did not share PIT's execution semaphore, so a fuzz process elsewhere in
the same multi-project build could turn mutation timeouts into load-dependent evidence.

The aggregate receipt compounded the ambiguity: it recorded the requested budget and a
list of tasks, but no achieved campaign count. A green Java process therefore proved only
that the wrapper returned normally, not how much libFuzzer work the release was claiming.
Mutable console logs could not repair that provenance after the fact.

The first repair serialized all CPU-intensive hardening children. That made the observation
comparable but made real fleet reviews needlessly additive. The final contract separates the
concerns: PIT and corpus rewrites remain exclusive, while fuzz exploration uses an explicit
build-wide `maxParallelFuzzTargets` semaphore. The chosen width is recorded beside the
budget, each target's positive terminal `Done N runs in S second(s)` count, and their total.
The local release runner copies and revalidates the same values in its immutable outer
bundle. A standalone fuzz task stays a fast developer tool and makes no aggregate claim.

Rules: *parallelism that affects achieved work is an input, not scheduler trivia*; *bound it,
record it, and record the work actually achieved*; *do not run PIT beside CPU-bound fuzzers*;
*capture process evidence from the live stream, then bind it through every retained receipt
layer*.

## The growth guard called allocation routing

Json-iterator carried five accepted mutants under the doctrine's unqualified
“allocation-size only” family. Their return values were identical, but the mutations
removed or reversed growth policy: two `parseMultiByteString` guards, a
`widenToCharBuf` capacity path, and `Math.max(n, x << 1)` changed to `>> 1` in
`JIUtil.ensureCapacity`. These were not constant-factor routing choices. They changed
how the work scaled, and one bulk-input test “killed” a sibling only by exhausting the
heap. Under a loaded certification that coordinate became `TIMED_OUT`; alone it read
`KILLED`. A resource regression had manufactured the same random one-coordinate abort
three consumers had spent long certification reruns diagnosing.

The deterministic oracle was smaller, not heavier. `TestAllocation` decoded 54
characters from a two-character starting buffer and allowed 8 KiB where the mutant
used roughly 1 MiB — an orders-of-magnitude margin in 29 lines. It killed the growth
family without racing the heap or watchdog. One first attempt wrapped the right
allocation counter around the wrong overload and observed no difference; reaching the
mutated path remained part of the proof. Iterator moved 1798/1919 to 1802/1919, util
351/394 to 352/394, and their accepted baselines shrank 121 to 118 and 43 to 42.

This does not repeal the allocation-harness warning above. Its 88-vs-90-byte gap was
an incidental constant factor with a thin margin. A capacity guard or amortisation
condition explicitly exists to control growth, and a test can choose a small input
whose ratio is enormous. Rules: *content equality does not imply complexity
equivalence*; *“allocation-size only” applies to incidental constant factors, never a
changed complexity class*; *make the ratio large while keeping the input small, so the
assertion fails before the heap does*; *a sound oracle around the wrong code path proves
nothing*.

## The timeout whose cause still terminated

Sava classified its fourteen audited timeout members by their written causes. Thirteen
were liveness failures: a removed loop exit or progress step, or a lock that could never
be released. One, `Base58.limbsLength`, returned the same answer after much more finite
work. It timed out when allocation and GC caught up under load and survived when idle.
The free-prose audit had accepted both shapes even though only the first makes the PIT
watchdog the unavoidable oracle.

Widening package-private visibility and asserting the method's stated value bound killed
all six relevant mutants. An allocation harness killed five, required management modules,
a volatile sink and warm-up, and left the flapper. The direct contract moved the suite
960/984 to 961/984, survivors 24 to 23, timeouts two to one, and the baseline 25 to 23
after two independent runs agreed. Rules: *classify a timeout before admitting it*;
*watchdog detection is for non-completion after deterministic seams are exhausted*;
*finite excessive work needs contract-first review, not timeout membership*; *prefer a
value bound, operation counter, or injected clock before allocation or time*.

## The load average that explained nothing

Six Sava certification failures initially looked load-correlated. They occurred at six
different coordinates, none repeated, and the machine's load average supplied a tidy
story — until certification succeeded at load 280–308, failed at 44, and a baseline
rebase succeeded at 102. A quiet solo run even produced a `RUN_ERROR`. The measured
condition described where an event was first noticed, not what caused it.

The durable discriminator remained coordinate recurrence. Retain the row before a quiet
rerun overwrites it; the same coordinate recurring is a code/test defect to investigate,
while different one-offs remain transient infrastructure evidence. Rules: *load average
is context, not diagnosis*; *do not turn correlation into an automatic retry*; *identity
and recurrence decide whether two failures are the same observation*.

## The fake clock that still waited 416ms

Ravina's `EpochInfoServiceImpl.getAndSetEpochInfo` `MathMutator` moved between
`KILLED` and `TIMED_OUT` across four candidate versions in five days. Two harness
changes targeted costs that looked suspicious, and each appeared to retire the row
until a later loaded certification brought it back. The mutant itself was killed by
an inline assertion in every mode; what raced the watchdog was a covering test's
wall-clock cost.

PIT had printed the useful measurement on every run: the slowest coverage-phase test,
`everySampleBeingFilteredOutDoesNotKillTheLoop`, took 416ms. The test filtered every
performance sample, leaving no slot statistics, so the loop parked for the default
slot duration even though no assertion observed that delay. Its `NanoClock` was fake,
but the wait used a real `java.util.concurrent.Condition` and therefore the real AQS
clock. Driving that one verified path through an existing no-park seam reduced the
test to 30ms and made the kill stable.

That successful local fix was not a recipe. Applying the same seam mechanically to
nine other tests immediately broke two: one needed pacing behavior from the real path,
and another exercised interrupt handling that the seam bypassed. The broad rewrite was
reverted. The tool can cheaply surface the project, suite, test, and duration; only the
consumer can decide which harness cost is irrelevant without changing the behavior
under test.

Rules: *measure harness cost before inventing a timeout cause*; *PIT's slowest
coverage-phase test is a lead, not proof that it covers a mutant*; *when the test does
cover mutated code, irrelevant wall-clock work is repaid across those mutants and can
manufacture load flips*; *report the cost and remeasure a behavior-preserving change,
never prescribe one mechanical seam for every test*.

## The ancestry check that never ran

A manual `git merge-base --is-ancestor <reviewed> <tag>` returned non-zero and briefly
looked like a release-integrity failure. The tag object simply had not been fetched. No
ancestry question had been evaluated. Production release verification already resolves
the commit and tag objects before asking the graph question, but the bare diagnostic
command hid that distinction.

Rules: *prove both objects exist before interpreting graph predicates*; *exit 1 from a
successfully evaluated ancestry predicate means false, while command/evaluation errors
need their own message*; *“could not check” is never evidence for either answer*.

## The reviewed repository that ran different bytes

The schema-2 release attestation for 21.5.23 named json-iterator among the reviewed
adoptions and bound the release candidate JAR at
`495f0d43332978bea8001358991bc15b00064125f8ad5b705087a18a6d9671d3`. Json-iterator's
last pre-release pass had actually certified an earlier candidate JAR at
`516b1b79e63e50ad51d3d0849d9ec28d632c2183f9c12e0dd441ceab893c762e`. The command
accepted repository slugs from the owner and derived everything else, so every release
gate passed: the artifact itself was sound, but the durable record overstated which
consumer had observed those exact bytes. A post-release published-artifact pass later
closed the real validation gap; it did not make the original claim derived evidence.

Schema 3 removes that asserted field. `create-reviewed --adoption` now takes a clean
consumer checkout, derives its GitHub origin and commit/tree, discovers completed
schema-6 certification receipts, and refuses unless the project-level plugin identity and
every suite row name the retained candidate JAR. It records the receipt hashes, sessions,
projects, suites, and relative
paths so a multi-project adoption says exactly what ran. The committed schema-2 record
remains verifiable as a historical statement; no schema-1 record was ever committed, so
the deleted fleet-backed creation path and its dead validator were removed together.

Rules: *a reviewer name is not artifact evidence*; *derive every claim that existing
receipts can prove*; *bind the consumer commit and exact loaded bytes together*;
*preserve shipped old schemas for verification without allowing them for new records*.

## The compatible checkout counted as a feature validator

A later candidate repaired ArcMutate-history decision paths. Ravina exercised that
behavior directly. Idl-src-gen then certified the exact same JAR cleanly, but its
private package namespace made ArcMutate history unavailable: the pass proved
compatibility and receipt transport, not the repaired history behavior. The release
discussion nevertheless began treating “both repositories healthy” as two independent
validations because schema 3 represented both with the same repository shape and its
verifier printed one undifferentiated adoption count.

The receipts were correct; the interpretation was not. A completed certification can
derive exact bytes, consumer revision, projects, and suites. It cannot derive which
changed feature a reviewer deliberately exercised.

Schema 4 keeps one derived repository inventory and adds an owner-reviewed role to
each entry: `feature-path` or `certification-only`. New records must state
whether their basis is consumer-feature, plugin-only, or deliberately
certification-only. A consumer-feature record requires at least one feature-path
checkout; the other bases refuse that role. Verification prints the total certified
consumer count and its feature-path subset separately. Historical schema-2 and
schema-3 records retain their original meanings.

Rules: *hardening certification is not changed-feature coverage*; *do not turn one evidence kind
into another by counting it*; *make a narrower release claim explicit rather than
silently empty*; *plugin-owned tests do not impersonate consumer validation*.

## The optional fleet runner that remained mandatory to maintain

Deliberate local consumer adoptions became the release proof, and the owner attestation
began deriving their exact certification receipts directly. The old aggregate fleet
runner was no longer a tag or publication gate, but `check`, all three build workflows,
and several functional tests still compiled and self-tested its 1,829-line shell program.
Its warning reprint filters and second receipt protocol therefore remained a mandatory
maintenance surface even though running the experiment was optional.

Deleting the superseded orchestrator did not mean deleting evidence with a different
job. The golden mutation corpus still tests parsers against real shipped baselines, and
the local-fuzz roster still supports an explicit bounded cross-repository campaign. The
schema-2 attestation actually committed for 21.5.23 also remains verifiable. What left was
the duplicate runner, its workflow/build hooks, its message-coupling tests, and the
never-committed schema-1 fleet-attestation path.

Rules: *when the release proof changes, remove the superseded orchestrator instead of
calling it optional while testing it everywhere*; *retain fixtures by the independent
claim they prove, not because they once belonged to the old runner*; *backward
compatibility protects records that exist, not hypothetical formats that never shipped*.
