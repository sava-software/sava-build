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
- **Split by layer, not just by cost**: code the harness's own machinery
  executes — a logging shim the server's threads log through, anything a
  socket test stands on — gets its own suite covered only by in-process
  tests. A mutant in that layer can wedge the machinery *underneath* the
  covering test, outside every per-test timeout's reach; one such run hung
  for 40+ minutes *(casebook: the logger shim that wedged the server)*.
- **Threads are not the lever they look like** — 8 threads bought ~10%, 10
  were slower than 8. Measure before spending here. On a suite heavy with
  await/signal tests, 8 threads *lost* to the 4-thread default outright:
  oversubscription inflates exactly the tests PIT re-runs most.
- **Scope the iteration loop with `-PmutateOnly=<glob[,glob]>`** — mutate
  only the class under attack while writing its kills, then re-run unscoped
  once before refreshing. The scoped report is stamped `.scoped` and every
  baseline-touching consumer (the ratchet, `-PupdateMutationBaseline`,
  `-PunionMutationBaseline`, mode snapshots) refuses it, so the shortcut
  cannot leak into the record. Coverage still runs the full test set.
- **Tune the per-test timeout to the suite's real runtimes** — PIT's default
  allowance is `recorded time × 1.25 + 4000ms`; every hanging-mutant
  detection pays that flat fee. Rank the suite's tests by duration first: one
  suite whose slowest test ran 0.6s cut ~19% wall time with
  `timeoutFactor = 2.0; timeoutConst = 1500L` and byte-identical results.
  Prefer raising the factor over the constant — load inflates a test in
  proportion to its own runtime — and if `SURVIVED -> TIMED_OUT` churn
  appears in the ratchet afterwards, raise the constant back before
  suspecting the code.
- **`pitest<Suite>Debt` prints where the debt lives** — survivors and
  no-coverage grouped by class, largest first, with the delta against the
  baseline. Use it to pick the next cluster instead of re-deriving the
  ranking from the CSV.

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
edited: the verify task then reports stale entries alongside "new" ones and
classifies each pairing, because two situations produce paired rows and call
for **opposite** responses:

- `(shifted from line N)` — same status, different line. The mutant moved.
  When the *whole* run is this — every new row a shift, populations
  unchanged — the verify passes on its own (see the line-drift section) and
  no immediate action is needed. Shifts mixed with anything else still fail:
  confirm the pairings, then refresh with `-PupdateMutationBaseline`.
- `(newly covered — was NO_COVERAGE at this line; triage, not a refresh)` —
  same line, different status. A test now *reaches* the mutant, which is a
  triage item: kill it, or accept it with a written reason. Refreshing here
  launders a fresh survivor into the baseline, which is precisely what the
  ratchet exists to prevent.

A stale row is consumed once it pairs, so several new rows cannot all claim
the same counterpart and report churn that did not happen. The failure closes
with the whole-set accounting the per-row hints cannot give — `churn: 3
shifted, 1 newly covered, 0 unexplained (of 4 new; 5 stale)` — and only calls
it line churn when nothing was newly covered and nothing is unexplained. A
non-zero *unexplained* count is the real thing: neither moved nor newly
reached. Pairing is greedy, so a method holding several mutants of one
mutator can leave a small residue unpaired even when the whole set is churn —
read a handful of unexplained rows against a heavily edited file as "check
these", not as proof of a regression. An extract-method refactor also lands
in *unexplained* deliberately: pairing keys on the method, so a mutant that
moved into a new method is not a shift — it needs re-triage at its new home,
where the covering tests may differ, not a refresh *(casebook: the
check-loop seam that deleted its flip insurance — its one unexplained row
was the relocated `unlock()`, and it became the family's one written
acceptance)*.
Two flags help triage without re-running: `-PlistUnkilled` prints every
unkilled row annotated with PIT's mutation description (which sub-condition
of a line, which direction a conditional was forced — the CSV omits it, the
XML report carries it), and the ratchet-failure listing carries the same
annotations.

Refreshes are kept honest in both directions. `-PupdateMutationBaseline`
names every row it drops — a dropped flip-insurance union (below) must be
re-added with `-PunionMutationBaseline` once observed to flip again; before
this the drop was silent and the re-append relied on someone remembering the
README warning. And a baseline row may carry a trailing `# note` —
`# untriaged` is the conventional label for seeded debt — which both refresh
flags preserve and the verify summary counts (`140 rows, 70 marked
'# untriaged'`), so triage debt is a number the build prints rather than
prose that drifts from the CSV it describes.

### `TIMED_OUT` is detected, and detection is load-dependent

A mutant that makes a loop non-terminating is caught by PIT's timeout and
counts as **detected** — so it is not written to the baseline. But the same
mutant can report `SURVIVED` when its suite runs alone and `TIMED_OUT` in a
multi-suite invocation, so the build fails or passes depending on *how you
invoked it*, and the failure looks exactly like a real regression.

- **Verify in both modes** before trusting a baseline — the suite alone, and
  under `qualityGate`. A row that differs between them belongs in the
  baseline; stale rows only warn, so a superset is safe in this direction.
  The comparison is scripted: `pitestModeSnapshot`/`pitestModeCompare` (see
  the wandering-kill-count section) diff the two modes per mutant and write
  the observed-flip unions with `-PunionModeFlips`.
- **Run-to-run drift is announced automatically.** The verify stashes each
  run's `TIMED_OUT` and `SURVIVED` coordinates
  (`.pitest-history/<suite>.statuses`, machine-local) and names each
  newcomer's origin on the next run. `KILLED -> TIMED_OUT` is the benign
  flavour and gets a one-line count; `SURVIVED -> TIMED_OUT` gets a warning
  with the rows, because a mutant nobody killed now reads as detected purely
  through load — do not let a refresh quietly drop it from the baseline on
  the strength of that.
- **Union only rows you have observed to flip** — with
  `-PunionMutationBaseline`, which adds the run's unkilled rows in canonical
  form without dropping baseline rows that happened to be detected this run
  (a full `-PupdateMutationBaseline` there bakes in the run's coin-flips and
  starts refresh ping-pong). Bulk-adding every `TIMED_OUT` row "to be safe"
  accepts mutants that are reliably detected today and silently stops the
  ratchet noticing if a later edit makes them genuinely survive.
- **Prefer removing the cause**: a fake collaborator that turns a would-be
  infinite loop into a deterministic assertion failure — a call budget, a
  bounded queue — beats leaning on the timeout. A background wait-loop whose
  interior only racing threads can reach has the loop itself as the cause:
  extract the body into a package-private **single-cycle seam**
  (`checkCycle(long awaitNanos)`, where `awaitNanos <= 0` never parks) and
  drive one cycle inline — zero threads, zero waits, and the interior's
  mutants become ordinary assertion kills instead of a flip family. The one
  mutant no seam converts is the loop condition forced always-true:
  nontermination is PIT-timeout territory by construction, so it stays
  `TIMED_OUT` — detected, stable once the interior coverage is
  deterministic, and not a missing baseline row to hunt for *(casebook: the
  check-loop seam that deleted its flip insurance)*.
- **Flip families do not settle while their cause remains — and "the cause
  remains" is a claim to re-measure, not a fact to record once.** Mutants
  equivalent on the wire but timing-dependent in detection (socket suites
  are the breeding ground) can hold a steady state where the baseline is
  deliberately the union of observed survivals and quiet runs emit
  *permanent* stale-entry warnings. While the cause is live that is correct,
  not cleanup debt: refreshing from any single run bakes in that run's
  coin-flips and starts ping-pong *(casebook: the handled-flag family that
  never settles)*. But the steady state is a holding pattern, not a
  destination, and the trade is asymmetric: a wrongly-removed union costs
  one red build and one `-PunionModeFlips` to restore, evidence note
  included, while a wrongly-kept union **blinds the ratchet at that key** —
  a row accepted in both statuses can never fail again, so a later edit that
  makes the mutant genuinely survive passes silently. Write each union's
  removal criterion when the union is written (N quiet `pitestModeCompare`
  cycles, or the cause removed) — and prefer removing the cause outright,
  which deletes the insurance *for* something instead of waiting it out
  *(casebook: flip insurance that outlived its cause; the check-loop seam
  that deleted its flip insurance)*.

### Two baseline-format traps

- **Duplicate rows are sibling mutants, not noise.** A compound condition
  (`a == null || b == null`) yields several mutants with identical
  `class,method,line,mutator` coordinates — one per operand or branch
  direction — and the baseline keeps one row per mutant, so identical lines
  legitimately repeat. The comparison is a *multiset* comparison: if two
  siblings are accepted and a third appears (or a killed sibling regresses),
  the count mismatch is flagged even though the row text already exists.
  Never hand-dedupe the file.
- **Hand-edited rows can silently never match.** The canonical mutator name
  strips the `org.pitest.…gregor.mutators.` package *and* the `returns.`
  sub-package; a row spelled `returns.NullReturnValsMutator` matches nothing
  and reports new forever. Prefer `-PupdateMutationBaseline`, which writes
  the canonical form; hand-edit only to union a known flip.

### Line numbers are metadata; pure drift passes on its own

Editing anything above a mutated method used to demand a baseline refresh for
rows that only moved. The verify now treats that case as metadata: when every
new row is a same-status line shift of a stale one *and* the
per-`(class, method, mutator, status)` population is unchanged, it passes with
a notice and the refresh can wait for a convenient moment. Anything else — a
newly covered row, an unexplained row, or a changed population (kills mixed
into the drift) — still fails and still wants the full triage-then-refresh
treatment. `-PnoDriftTolerance` restores strict behaviour; certifying runs
should use it alongside `-PnoMutationHistory`.

### When a mutant won't die — a decision tree

Worked in this order, each step cheaper than the one after it:

1. **Strengthen the assertion toward the property, not the implementation.**
   Content equality cannot tell a reuse from a reparse — assert identity.
   A log call is only pinned by asserting the *rendered* message (parameters
   included), a lock only by asserting it is free afterwards.
2. **Identify which sibling survived.** For `RemoveConditional` pairs on
   compound conditions, one bytecode direction is usually killed and its
   same-coordinate sibling survives; the verify prints
   `[detected sibling at this coordinate: KILLED by <test>]` on such rows.
   The survivor is the *opposite* branch of whatever that test pinned —
   often an in-lock recheck or short-circuit leg that only a concurrent
   interleaving could observe. Triage it as its own mutant; do not assume it
   is the one the test was aimed at.
3. **Suspect the code before declaring equivalence.** A survivor that looks
   unkillable is a claim that the guarded behaviour cannot be observed —
   sometimes true, sometimes the observation *path* is broken. The campaigns
   this process comes from found double-digit real defects exactly here:
   starved dispatches, corrupted invariants, crash-on-empty edge paths —
   all wearing "equivalent mutant" as camouflage. Ask what user-visible
   promise the mutated line serves; if there is one and no test can reach
   it, the code (or its API) is the problem.
4. **Accept with a named escape.** If it is genuinely equivalent, say *why*
   in the family note and name the condition under which it would become
   killable — a fixture that does not exist yet, a concurrency harness, a
   multi-row caller. The escape is what keeps the acceptance honest when
   the code changes underneath it.

### The recurring equivalence families

Most accepted mutants fall into a handful of shapes; group the baseline by
family rather than listing rows:

- **Allocation-size only** — changes how much is reserved, never what is
  computed.
- **Fast-path / alternate-path routing** — both branches reach the same
  result; a guard subsumed by a later branch is the common case
  (`if (a.feePayer()) return a;` above a `signer()` branch returning the same
  object).
- **Error-funnel redundancy** — removal reaches code that *fails* into the
  identical observable: a removed null guard NPEs into the catch that maps to
  the same error code; a removed `setStatus(500)` still answers 500 through
  `callback.failed`. Accept only after observing the funnel produce the
  identical response, and name the funnel in the note — "probably the same
  error" is the claim that rots *(casebook: the error funnel)*.
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
Newton's-method sqrt sweep)*. The sweep's other outcome is the stronger
argument: on one adoption it falsified an accepted family outright and led to
two real bugs, including a constructor hang — and showed that a fuzz harness
asserting the right properties over too small an input domain protects
nothing outside that domain *(casebook: the sweep that falsified an
acceptance)*. When a sweep is not feasible, the note says
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

The plugin scripts the trial: `pitestMutatorTrial
-PtrialMutators=<CANDIDATE[,...]>` runs every suite with **only** the
candidate mutators — no ratchet, no history, reports kept apart under
`build/reports/pitest/<suite>-trial` so the real reports and baselines are
untouched — and tabulates generated / killed-by-existing-tests / unkilled
per suite, closing with "fired in N of M suites". A suite where the
candidates cannot fire exits PIT with an error by design; the trial reads
that as zero fired rather than failing the invocation. What was a hand-run
campaign per new PIT release (a run per suite, counts diffed by hand) is one
invocation; recording the numbers in `config/pitest/README.md` is still
yours. The "fired in N of M suites" tally is per **module**, so a multi-module
repo reads one line per module: `0 of 1` from a module with no such arithmetic
beside `1 of 1` from the module that has it is the expected shape, not a
miscount.

The same blindness has a structural sibling: **fluent APIs**. A call whose
return type is its receiver type is an expression, so `VoidMethodCallMutator`
never fires on it — a builder-style header write, a `StringBuilder.append`
chain, `String` slicing. `EXPERIMENTAL_NAKED_RECEIVER` (replace a call with
its receiver) makes the dropped call expressible; the same trial-and-record
protocol applies, and in three suites it fired 22 times at zero baseline
cost, twice exposing genuinely untested response headers *(casebook:
EXPERIMENTAL_NAKED_RECEIVER trials)*. When auditing a suite's blind spots,
ask what the mutated code's statements *return*, not only what they compute.

## The class path is PIT's world

PIT minions run tests on the class path. A repo whose tasks otherwise run on
the module path therefore hardens code in a world where `module-info`
services, exports and readability do not exist, and the divergence cuts both
ways: a module-descriptor `provides` clause is invisible to a minion's
`ServiceLoader` (tests that discover implementations that way fail under PIT
with a misleading "did not pass without mutation"), while a test-resources
`META-INF/services` file is honored by the minion but ignored by the
module-path `test` task — a harness whose result depends on which task ran
it, which is never committed *(casebook: PIT's world is the class path)*.

For real (main-source) services the resolution is the standard dual
declaration — `module-info` *and* `META-INF/services` — which is also just
correct packaging for a library classpath consumers can load. For test-only
providers there is no clean dual form; accept the uncovered path as
unreachable in-harness and name the escape (a blackbox test module with its
own descriptor).

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
- **"Wire-invisible" configuration is usually observable through an injected
  recording collaborator.** An executor preference, a thread-pool binding — a
  recording wrapper that delegates and counts turns "no test can see this"
  into an assertion; three such acceptances became kills that way. For
  trivial log emissions, capturing the log stream (a JUL handler) is the
  cheap alternative to the extract-construction refactor and pins a real
  contract: failures are never silent. In a modular repo the unlock is one
  line — `testModuleInfo { requires("java.logging") }` — and it applies to
  `System.Logger` emissions too, whose default backend is JUL: attach a
  handler to `java.util.logging.Logger.getLogger(<same name>)` and assert
  the record (its `getThrown()` pins *which* failure was reported).
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
feeding every seed through the harness costs milliseconds per build. The
plugin generates the replay: every fuzz target with a `seedCorpus` gets a
`<Harness>SeedReplayTest` in the test source set, so the corpus runs inside
`test` — and under PIT, where the replay participates as a killer. The
generated test resolves a corpus under the test resources as a classpath
resource (hermetic under any working directory; anything else falls back to
its configured path), replays only regular files, and **fails on an empty
corpus** — deleting every seed is exactly the rot the replay exists to
catch, so it cannot pass silently. Repos carrying hand-written replay
classes can delete them once nothing in them exceeds that; seed provenance
prose (what each input pins) moves to a README **next to** the corpus
directory — never inside it, where the file would itself be fed to the
harness as a seed.

**When a reference implementation would re-derive the same bugs, generate
the oracle instead.** For a parser whose natural differential partner is
just the same scanner written twice, build the *input* from fuzz-chosen
tokens and construct the expected output alongside from each token's
documented meaning — ground truth by construction. The design obligation is
token independence: no token may change the meaning of its neighbor (end
every token on a character that cannot open an escape or pair with a
following delimiter). One such harness ran 154M executions against a
placeholder formatter with the substitution semantics as the oracle.

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

**Socket harnesses add their own determinism rules.** Test clients name
`127.0.0.1`, never `localhost`: a client resolving `localhost` may try `::1`
first and reach a *different JVM's* wildcard bind on the same port number —
no bind conflict, just wrong answers that only reproduce under parallel
module runs *(casebook: cross-talk on ::1)*. A failure that appears only in
parallel runs is an isolation bug to chase before it is a regression.

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

Convergence is checkable, and the plugin scripts it: `pitestConverge` runs
every suite twice in one invocation — snapshotting and clearing the reports
between rounds, since Gradle would otherwise serve the second run from the
first — and diffs per-mutant statuses, failing on flips that cross the
unkilled boundary. With an arcmutate licence active it refuses to run without
`-PnoMutationHistory` — assisted runs agree by construction. Know what a
green converge proves: both rounds run in the same serialized mode, so zero
flips demonstrates run-to-run determinism only — solo-vs-`qualityGate` load
flips are exactly what it cannot see. `TIMED_OUT` flapping appears between a
quiet run and a loaded one, never between two quiet runs, so two quiet runs
agreeing proves the weaker thing.

The mode comparison is scripted too. `pitestModeSnapshot -PpitestMode=<label>`
stashes the current reports under a label and **clears them** — not
optional: Gradle serves an up-to-date `pitest<Suite>` without re-running
PIT, so an uncleared second run compares a file to itself. It refuses a
partial report set (a suite would be diffed against its absence) and a
history-assisted report (a reused status is not an observation of the
mode). `pitestModeCompare` then diffs **per-mutant status** across every
stashed label, keyed on `(class, method, line, mutator)` — strictly
stronger than sub-totals, and it names which mutant moved:

    ./gradlew <every pitest suite> pitestModeSnapshot -PpitestMode=solo -PnoMutationHistory
    ./gradlew qualityGate pitestModeSnapshot -PpitestMode=gate -PnoMutationHistory
    ./gradlew pitestModeCompare

Benign flips (`KILLED` <-> `TIMED_OUT`) are counted and tolerated. A flip
crossing the unkilled boundary is exactly the row the `TIMED_OUT` section
says belongs in the baseline: the compare fails naming each one unless it is
already insured there, and `-PunionModeFlips` writes the union — append-only,
each row annotated `# flip insurance (<per-mode statuses>)` so the note
carries its own evidence and a later reader can re-measure it. Two runs can
match in total while disagreeing about which mutants died; the headline
number is not the check.

**Sweep for accepted rows that match nothing in any mode** while you have the
data: per-run stale warnings get dismissed as solo-vs-gate mode noise, so
diff each `<suite>-accepted.csv` against the union of unkilled sets across
all runs — rows matching in *no* mode are widening the gate for nothing;
`pitestModeCompare` prints this sweep over its snapshots automatically.
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
as unexplained. The ratchet's missing-report failure prints this pointer, so
the recipe no longer has to be rediscovered per repo.

## Shared test scaffolding (generated)

`hardening.generateTestSupport = true` generates six small classes into the
test source set (package `software.sava.hardening.support` — fixed; it names
the plugin, not the consuming repo), compiled inside the consuming module so
the module path and PIT's class path both just work: no published artifact,
no version skew across repos, always plugin-synced. Off by default;
regenerated every build.

- **`ConcurrencyHarness`** — deterministic sequencing for concurrency tests:
  `awaitTrue(what, condition)` polls an observable side effect instead of
  sleeping a guessed length; `awaitState(thread, states...)` proves a worker
  is parked (`WAITING` for an unbounded await, `TIMED_WAITING` for a bounded
  one) before the test pokes it; `joinOrFail(thread, millis, what)` bounds
  every join so a hung thread fails the test, not the build. The companion
  conventions: assert timing only as a *lower* bound (load lengthens
  intervals, never shortens them), and give the woken path a generous
  join — the mutants these tests target reveal themselves as never-wakes,
  which the bound converts into a clean assertion failure or a PIT timeout,
  both detected.

- **`Ports.freePort()`** — ephemeral-port probe. Connect to `127.0.0.1`,
  never `localhost` (see the socket determinism rules).
- **`LoopbackHttpServer`** — a scripted raw-socket HTTP server: enqueue the
  exact response bytes — a 199 status, a truncated header block, things a
  well-behaved server library refuses to produce — and assert the recorded
  requests. This is the standing scaffolding for transport paths and
  status-boundary guards otherwise accepted as *unreachable in-harness*; if
  an acceptance note names "a raw socket speaking HTTP/1.1 by hand" as its
  escape hatch, this is that escape hatch.
- **`ManualScheduledExecutor`** — a deterministic, single-threaded
  `ScheduledExecutorService`: tasks run only when the test advances the fake
  clock (non-zero origin, per the clock rules), so pacing, backoff and
  reconnect choreography become exact functions of the delays requested.
  Blocking multi-submit (`invokeAll`/`invokeAny`) deliberately throws — it
  has no deterministic single-threaded meaning.
- **`RecordingExecutor`** — delegates and counts dispatches, for
  "wire-invisible" executor configuration (see test conventions).
- **`JulRecorder`** — captures a logger's records while attached, forcing the
  logger to `ALL` with parent handlers detached and restoring both on close:
  attaching to a logger the repo silenced in test setup — the very pattern this
  replaces — would otherwise capture nothing at all, and anything it did
  publish would still reach the console. `messages()` renders each record the
  way a handler would, because services log `{0}` patterns whose interesting
  values live in the record's *parameters*; asserting against `getMessage()`
  alone silently never matches them. `logged(fragment)` is the predicate form.
  Needs `java.logging` readable from the test module — in a modular repo that
  is `testModuleInfo { requires("java.logging") }`, which also unlocks
  capturing `System.Logger` output through its JUL backend (see the test
  conventions). In a repo that will not add the requires, omit the class
  instead of forgoing the rest:
  `hardening.testSupportExcludes = listOf("JulRecorder")` (any helper can be
  excluded by simple name).

## Adopting in a new repo

1. Apply `software.sava.build.feature.hardening` and register mutation suites
   (wildcard targets + exclusions) and fuzz targets. `hardeningInit` scaffolds
   the transcription: the `config/pitest/README.md` skeleton, the
   `.pitest-history/` git-ignore, and the adoption checklist with the current
   template digest.
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
> - Pure line drift — every new baseline entry a same-status shift of a stale
>   one, populations unchanged — passes on its own with a notice; refresh at a
>   convenient moment. Anything mixed in (newly covered, unexplained, changed
>   counts) still fails and is triage first, refresh after.
> - **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
>   seconds instead of the full suite — then re-run unscoped before any
>   refresh; the tooling refuses to let a scoped report touch the baseline.
> - Identical baseline rows are sibling mutants of one compound condition and
>   the comparison is a multiset: never hand-dedupe. When one sibling
>   survives, the verify names the killed sibling's test — the survivor is
>   the opposite branch direction; triage it as its own mutant.
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
>   1.25.8) — and fluent calls returning their receiver are expressions,
>   invisible to `VoidMethodCallMutator` — builder-style writes need
>   `EXPERIMENTAL_NAKED_RECEIVER`. Trial per suite, enable only what fires,
>   and record the numbers.
> - **PIT minions run on the class path**, even in module-path repos:
>   `module-info` services are invisible to them, and a test-resources
>   `META-INF/services` is invisible to the module-path `test` task. Real
>   services are declared in both places; a harness whose result depends on
>   which task ran it is never committed.
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
