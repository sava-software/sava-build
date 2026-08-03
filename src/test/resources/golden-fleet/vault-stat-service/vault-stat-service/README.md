# Mutation-testing baseline & triage policy — `vault-stat-service`

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. The canonical policy is sava-build's
`HARDENING.md`; this file records what is accepted *here* and why.

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (a rounding direction, an unsigned comparison, an exact
   UnsupportedReason emitted, a retry sentinel identity) over restating the
   implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason under "Triaged equivalent mutants" below. Acceptance is for
   mutants *equivalent with respect to observable behavior*, not for "hard to
   test".

Line numbers are part of the baseline key, so unrelated edits to a mutated file
shift entries: pure line drift — every new row a same-status shift of a stale
one, populations unchanged — passes on its own with a notice; anything mixed in
still fails and is triage first, refresh after. Duplicate rows are sibling
mutants of one compound condition and the comparison is a **multiset** — never
hand-dedupe the file.

## Suites

Three package-subtree suites (see `build.gradle.kts` for each suite's mutator
set, and "Mutator trials" below for the measured numbers behind each choice;
`pitestMutatorTrial -PtrialMutators=...` before enabling more):

- `pitestPositions` — `vault.stats.value.positions.*`, the per-protocol
  pricing math. Mutators as of 2026-07-25: `STRONGER` plus all three
  experimental candidates — this is the suite whose subject is `BigDecimal`
  and `BigInteger` arithmetic, which `STRONGER` alone cannot mutate.
- `pitestValuationManager` — `GlamVaultValuationManagerImpl` and its nested
  types, alone. Split out of `value` on 2026-07-25: it is the largest and
  highest-churn class in the repo and carried 40% of that suite's mutants, so
  a change to the state machine, the position dispatchers or the simulation
  decoder now re-runs ~548 mutants instead of ~993. Partitioned for inner-loop
  speed, not for coverage — same mutator set as `value`, and the split was
  verified to preserve every total (548 + 445 = 993 mutants, 44 + 25 = 69
  survived, 123 + 221 = 344 no-coverage, 1 + 1 = 2 timed out). Its baseline
  rows were *moved* from `value-accepted.csv` with their family labels intact,
  not re-seeded as untriaged.
- `pitestValue` — the rest of `vault.stats.value.*` (executor, caches,
  accounting, price sources, db). Mutators as of 2026-07-25:
  `STRONGER,EXPERIMENTAL_BIG_DECIMAL,EXPERIMENTAL_NAKED_RECEIVER` — trialled
  with `pitestMutatorTrial -PtrialMutators=…`; `EXPERIMENTAL_BIG_INTEGER`
  generates nothing here and is deliberately left off.
- `pitestService` — everything else (REST handlers, config, mints, monitors,
  logging, wiring).

## Baseline seed — 2026-07-24

Seeded with the full pre-existing unkilled population when the ratchet was
adopted; every row is `# untriaged` debt, not an acceptance. Shrinking a
baseline is always an improvement.

| Suite | SURVIVED | NO_COVERAGE |
|---|---|---|
| positions | 145 | 295 |
| value | 148 | 444 |
| service | 109 | 321 |

Where to pay down first (`pitest<Suite>Debt` for the live ranking):

- **value**: `GlamVaultValuationManagerImpl` (259) dominates — the uncovered
  strata are `prepareAUMTransaction`/`createTableMetaArray` (transaction
  assembly) and the RPC-facing `fetchAccounts`/`accept` paths; the tested
  state-machine/dispatch/staleness layers account for most of its kills.
  `GlamVaultExecutorImpl` (101) is almost entirely the untestable-as-written
  `run()` loop. *(2026-07-25: the run loops got a clock seam and the whole
  `NO_COVERAGE` population of this suite is now zero — see "value —
  `NO_COVERAGE` pay-down" below.)*

  *2026-07-24 paydown*: the seeded 148 `SURVIVED` were worked down to 62, all
  labeled (families below); `NO_COVERAGE` shrank 444 → 397 as side coverage
  (vault deletion teardown, the kVault share-token registration path, the
  mint-PDA supply read, the bounds-guard edges). The remaining `SURVIVED`
  population is triaged acceptance, not debt; the `NO_COVERAGE` strata remain
  the transaction-assembly and run-loop code documented above.

  *2026-07-25 mutator widening*: `EXPERIMENTAL_BIG_DECIMAL` and
  `EXPERIMENTAL_NAKED_RECEIVER` were enabled and the 12 newly-visible unkilled
  rows worked to 10 killed / 2 accepted; side coverage shrank `NO_COVERAGE`
  further (the disk-load / restart-recovery stratum of `GlamStateContextCache`
  is now largely covered). Details in the value section below.
- **positions**: spread evenly; the `priceInstruction` assembly halves of each
  class are lightly covered relative to their math halves. `KaminoPositions`
  (91) leads because the kVault happy path was fixture-skipped. *(2026-07-24:
  the `SURVIVED` population was triaged to zero untriaged rows — see the
  positions section below; what remains is `NO_COVERAGE` debt, mostly the
  Kamino/Loopscale/Marginfi/Phoenix `positionReport` strata and dispatch
  wiring — mechanical fixture work.)*

  *2026-07-25 mutator widening*: `EXPERIMENTAL_BIG_DECIMAL`,
  `EXPERIMENTAL_BIG_INTEGER` and `EXPERIMENTAL_NAKED_RECEIVER` were enabled,
  making 187 previously-invisible mutants visible (`BigDecimal`/`BigInteger`
  arithmetic and dropped fluent calls — the money-math shape this service
  exists to get right). 137 were already killed; the 50 unkilled were worked to
  **50 killed, 0 accepted**. The `positionReport` leaf strata that were the bulk
  of the remaining debt are now fixtured, so the whole baseline fell from 261
  rows to 97 (`NO_COVERAGE` 215 → 40). Details in the positions section below.
- **service**: `BaseJulLogger` (59) and `VaultStatsRestServerFactory` (46) are
  wiring; `HistoricalGlamValueHandlerImpl` (43) is the JDBC run loop —
  `NO_COVERAGE`, mechanical work if it becomes worth a `ResultSet` proxy pass.
  *(2026-07-24: the `SURVIVED` population was triaged to zero untriaged rows —
  79 killed, 30 accepted into the service families below; what remains is the
  `NO_COVERAGE` wiring/run-loop debt above.)*

  *2026-07-25 `NO_COVERAGE` pay-down*: the two clusters named above were worked
  with the generated test support (`JulRecorder`, `ConcurrencyHarness`) —
  `BaseJulLogger` 59 → **0** and `HistoricalGlamValueHandlerImpl` 43 → **8**,
  taking the suite from `30 survived / 303 no_coverage` to
  `37 survived / 209 no_coverage` (94 rows converted, all of them in these two
  classes — the counts reconcile exactly, so there was no incidental side
  coverage elsewhere). The seven newly surfaced survivors are triaged into the
  families below; the eight that remain are the wall-clock strata of the run
  loop. Details in the service section below.
  `VaultStatsRestServerFactory` (46) is now the largest remaining cluster.

## Determinism — 2026-07-25

`pitestConverge` (every suite run twice, per-mutant statuses diffed) is what
makes these baselines evidence rather than one sample: a wandering kill count
is a defect to chase, not something to re-ratchet past. Re-run it after any
change that touches concurrency, timing, or test ordering.

- **Before the suite split (3 suites, 2026-07-25):** 3 converged, zero flips.
- **After the split (4 suites, same day):** `1 flip, none crossing the
  unkilled boundary` — a `KILLED`↔`TIMED_OUT` oscillation on the single
  timeout mutant in `valuationManager`, which is fast enough to be caught
  outright on some runs. Both statuses are *detected*, so the ratchet cannot
  move and no insurance is needed.

Two observations worth keeping, because both look like defects and are not:

- **`RUN_ERROR` is transient infra, not a result.** One `valuationManager`
  gate run out of six reported 45 survivors and one unbaselined row; three
  solo runs and two later gate runs all report 44, and a `RUN_ERROR` was
  independently observed in another run on this machine under load. A
  `RUN_ERROR` is not valid mutation evidence, so the hardening parser now
  rejects the entire report. Re-run before touching a baseline — never refresh
  to absorb one.
- **`pitestModeCompare` reported `0 uninsured boundary flip(s)`** across solo
  and gate on the pre-split suites (see below); the post-split benign flip
  does not change that conclusion, since a `KILLED`↔`TIMED_OUT` pair is
  explicitly the benign category.

Its stated limit: it proves run-to-run stability only. Solo-vs-gate load flips
are a different question, answered below.

## Timed-out mutants — verified in both modes, 2026-07-25

`TIMED_OUT` counts as *detected* and is never written to the baseline, so a
mutant that is merely **slow** can read as a kill under load and be real debt
when run alone. The three-invocation check was run
(`pitestModeSnapshot -PpitestMode=solo` / `=gate` / `pitestModeCompare`, all
with `-PnoMutationHistory`):

> `pitestModeCompare (gate vs solo): 0 uninsured boundary flip(s), 0 unioned
> now, 0 already insured, 0 benign`

Identical per-mutant statuses in both modes, so **nothing is unioned** — flip
insurance is only ever written for rows actually observed to flip.

The stability is explained by what the timed-out mutants are: every one is
structural non-termination, not slowness, so no load profile can turn it into
a survivor.

- `valuationManager` (1) — `glamVaultTableUpdate` 1404 (`witness == null`
  forced true: a CAS spin loop that can never succeed). The unmutated loop is
  a standard lock-free retry — every iteration returns, breaks on a successful
  CAS, or retries after a CAS that failed *because another thread succeeded* —
  so the timeout is the mutant hanging, not a liveness defect in the code.
- `value` (2, was 1) — `GlamVaultExecutorImpl.refreshStateContext` 248 (was 236;
  `to == numVaults` break forced false: a batch loop with its exit condition
  gone), and `GlamStateContextCacheImpl.run` 136 (`ServiceContext::backoff`
  removed). The second is structural for the same reason as the first, one step
  less obviously: `backoff` is the *only* interruptible call on the discovery
  loop's failure path — `awaitNewGlobalConfig` sits after the joins and is never
  reached by a failing pass — so removing it turns a persistently failing
  discovery loop into a hot spin that can no longer be shut down. Not a slow
  mutant: no load profile makes an uninterruptible loop return.

  Two further `TIMED_OUT` rows appeared while covering the run loops and were
  **removed rather than accepted**, because a hang that PIT scores as
  `TIMED_OUT` is *detected* and therefore invisible in the baseline — the
  harness silently standing in for an assertion. Both were mutations that delete
  a loop's only wait, leaving it spinning against a fake clock that only the
  loop itself advances: `GlamVaultExecutorImpl.run` 77 and
  `JupiterPriceFetcherImpl.run` 120, each a `waitUntil` removal. The fix is a
  spin ceiling in the harness, not in production — `PacerTestHarness`'s
  `CLOCK_READ_BUDGET`, the Jupiter harness's per-cycle `MAX_CYCLES`, and the
  discovery harness's `RPC_CALL_BUDGET`/backoff budget — each two orders of
  magnitude above what a healthy cycle needs, and each escaping unchecked so the
  run ends and the test fails on its own assertions. Both rows are now `KILLED`.
- `service` (9) — `BaseGlamVaultTableHandler.extendAndCreateTables` 108 and
  116 (both loop exit conditions forced); `SparkleHandler.generatePNG` 107
  (increment reversed 1 → -1, and the loop comparison forced true — a pixel
  loop that counts away from its bound); `IncidentNotifierImpl.queueResponse`
  45 (`ReentrantLock::unlock` removed, so every later caller blocks forever),
  and `run` 57/58 (drain-loop equality forced false, and `Condition::await`
  removed); plus, since 2026-07-25, `BaseJulLogger.formatPlaceholders` 70 and
  81 — both `i++` skip-ahead statements reversed to `i--`, which the loop's own
  `i++` cancels out, so the renderer re-reads the same `\{` / `{}` forever.
  Same structural category as the rest: the mutated loop cannot terminate for
  any input, so no load profile turns it into a survivor.

  One **benign `KILLED`↔`TIMED_OUT` flip** was observed on 2026-07-25:
  `SparkleHandler.generatePNG` 114 (`MathMutator`) reported `KILLED`, then
  `TIMED_OUT`, then `KILLED` again across three consecutive runs of the same
  code. Both statuses are *detected*, so the row is in neither baseline and the
  ratchet cannot move; the mutant is in the same pixel loop as the two already
  listed above. Recorded here so a future reader does not chase it — the suite's
  timed-out count reads 9 or 10 depending on the run.

Re-run the three-invocation check after changes to loop structure, locking, or
suite composition. When reading a report directly rather than through the
task, note the XML uses single-quoted attributes (`status='TIMED_OUT'`) — a
double-quoted grep silently reports zero.

## Timed-out mutants — the audited set, 2026-07-28

sava-build 21.5.17's timeout audit was adopted: each suite now has a
`<suite>-timeouts.csv` holding one line-less `class,method,mutator` key per
audited timed-out mutant (lines ride in `#` comments; `positions` has an
empty file to arm the check, since it has never produced a timeout). The
verify warns on any timed-out mutant outside its suite's set and on members
matching no mutant — a new timeout is a reviewer-stop, because for exactly
those mutants the ratchet cannot see a weakened covering assertion. The
structural causes stay in this file; the sections above and below are them.

Adopting the audit surfaced exactly the gap it exists to catch: the current
`service` report holds **12** timed-out mutants, not the 9 the 2026-07-25
inventory above records. The three newcomers arrived with the 2026-07-26
parked-request interleaving tests (`KaminoVaultContextHandlerTests`) and were
never written down until now. All three are in
`KaminoVaultContextHandler.httpResponse`, the double-checked memoization:

- **52 (`RemoveConditionalMutator_EQUAL_ELSE`, the null-check forced
  false)** — an invalidated (null) response is answered as-is and no request
  ever takes the lock path, so the state the harness awaits — a second
  request parked `WAITING` inside `httpResponse` — becomes unconstructible;
  `ConcurrencyHarness.awaitTrue` polls a condition the mutant made impossible
  until the PIT watchdog fires.
- **53 (`VoidMethodCallMutator`, `lock.lock()` removed)** — the waiter never
  parks: it races into the rebuild unlocked and dies on the `finally`'s
  `unlock()` (`IllegalMonitorStateException` — not the holder), so the same
  awaited parked state never occurs.
- **62 (`VoidMethodCallMutator`, `lock.unlock()` removed)** — the leaked
  unlock, the same structural cause as `IncidentNotifierImpl.queueResponse`
  45: the test thread's reentrant rebuild leaves an extra hold, the lock
  never reaches zero, and the parked request blocks past `joinOrFail`'s
  bound. (53 and 62 share one audit key — `httpResponse` +
  `VoidMethodCallMutator` — which is the audit's stated resolution.)

None of the three can read `SURVIVED` under any load: the harness's own
budgets (`awaitTrue`'s ~5s poll ceiling, `joinOrFail`'s 5s bound) fail the
test even without the watchdog, so every outcome of the watchdog-vs-budget
race is detection. Because the watchdog (~4s) normally fires first they read
`TIMED_OUT`, but a `KILLED` reading on a fast run would be the benign flavour
— the same category as the recorded `SparkleHandler.generatePNG` 114 flapper,
which is why that (currently `KILLED`) mutant is pre-audited in
`service-timeouts.csv` rather than left to warn on its next flip. The sibling
`KaminoReserveContextsHandler` runs the identical interleaving and its
mutants read `KILLED` — the same race landing on the other side. On
2026-07-28 that sibling's leaked-unlock mutant (`httpResponse` 107,
`VoidMethodCallMutator` — `lock.unlock()` removed in the `finally`, the
`KaminoVaultContextHandler` 62 cause verbatim) landed `TIMED_OUT` and was
added to the audited set; its remaining `KILLED` siblings will join the same
line-less key if they flip too.

## Mutator trials — 2026-07-25

Every candidate was trialled per suite with
`pitestMutatorTrial -PtrialMutators=<CANDIDATE>`, one candidate per run so each
tally is readable. Numbers are recorded here even where nothing fired, so a
missing mutator reads as measured rather than forgotten. Format:
generated / killed by existing tests / unkilled.

| Candidate | positions | value | service |
|---|---|---|---|
| `EXPERIMENTAL_BIG_DECIMAL` | 30 / 22 / 8 | 1 / 1 / 0 | 0 — cannot fire |
| `EXPERIMENTAL_BIG_INTEGER` | 43 / 39 / 4 | 0 — cannot fire | 0 — cannot fire |
| `EXPERIMENTAL_NAKED_RECEIVER` | 114 / 76 / 38 | 29 / 17 / 12 | 334 / 171 / **163** |

Enabled: all three on `positions`; `BIG_DECIMAL` and `NAKED_RECEIVER` on
`value`. The 50 + 12 newly-visible unkilled rows were triaged to zero the same
day (sections below).

**`NAKED_RECEIVER` on `service` is deliberately left off.** It fires 334 times
there because the suite is builder- and wiring-shaped, and 163 of those are
unkilled — taking that on immediately after driving untriaged `SURVIVED` to
zero across all three suites would trade real signal for volume. This is a
measured decision, not an oversight, and it is recorded here rather than with
`declineMutator(...)` because the blind-spot scan does not advise
`NAKED_RECEIVER` at all; a decline for it would be reported as stale forever.
Revisit by re-running the trial and paying the population down in slices.

The two `Big*` mutators cannot fire on `service` (no `BigDecimal`/`BigInteger`
arithmetic in those classes), so there is nothing there to enable or decline —
the per-run blind-spot scan stays silent on that suite, which is the check that
this stays true.

## Triaged equivalent mutants (accepted with reasons)

Rows are labeled with the family whose argument lives here. Every accepted row
carries a family label; `# untriaged` marks debt that has not been argued.

## Untriaged debt

All remaining `# untriaged` rows are `NO_COVERAGE` — untested lines, which are
mechanical work and are never acceptable as "equivalent". Current counts and
where they sit are in the seed section above. Zero `SURVIVED` rows are
untriaged in any suite.

**`value` is exempt from the above as of 2026-07-25: it has zero `NO_COVERAGE`
rows and zero `# untriaged` rows of any status.** Its baseline is 42 rows, every
one a labelled `SURVIVED` acceptance.

### Deliberately not planned — 116 of the 372, 2026-07-25

Three `service` classes account for 116 `NO_COVERAGE` rows and are **decided
against, not pending**:

| Class | Rows |
|---|---|
| `vault.stats.rest.VaultStatsRestServerFactory` | 46 |
| `vault.stats.VaultStatsServiceContextImpl` | 36 |
| `vault.stats.Entrypoint` | 34 |

All three are wiring: they construct collaborators and hand them to each other.
A test over them can only assert that the objects it injected were the objects
passed on — restating the construction in a second place, which is the
implementation-restating shape the policy warns against. It would raise the
number without adding a way for the suite to fail on a real defect, and the
defects these classes actually suffer (a handler wired to the wrong path, a
cache shared where it should be per-vault) are caught by the handler and cache
suites that already exist.

**This is not an equivalence claim, and the rows keep the `# untriaged` label
deliberately.** The behaviour is genuinely unobserved; `NO_COVERAGE` is never
acceptable as "equivalent", and the label column is the mechanism for arguing
*equivalence*, so putting a family label here would say something false. The
argument lives in prose instead, which is the honest place for "we looked and
chose not to".

What would reverse it: any of these classes growing a branch — a conditional
wiring path, an environment switch, a retry — at which point the branch is
behaviour and belongs under test, and only the straight-line construction
around it stays here.

**Added 2026-07-26: the lookup-table assembly handlers** —
`BaseGlamVaultTableHandler`'s remaining 38 `NO_COVERAGE` rows (and
`CreateVaultTableHandlerImpl`'s 3) are declined on different grounds: the code
is on a deprecation path, not untestable. Solana v1 transactions are expected
to remove the need for address lookup tables, at which point this handler
family is deleted rather than maintained. Investing fixture work in code with
a planned removal buys regression protection for behaviour that is scheduled
to stop existing. The already-covered parts of these handlers (batch
boundaries, table selection, the serializer contract) stay covered and keep
their ratchet. What would reverse it: v1 rollout stalling long enough that
this code is still load-bearing at the next major piece of work on it.

**So the honest backlog is 256, not 372:** `valuationManager` 123 (transaction
assembly and the RPC-facing paths — real fixture work), `service` 93 once the
wiring above is set aside, `positions` 40, `value` 0.

*(2026-07-25, later the same day: `valuationManager`'s 123 were worked to **8** —
see the valuationManager section below — so the honest backlog is now **141**:
`service` 93, `positions` 40, `valuationManager` 8, `value` 0.)*

*(2026-07-26: the `positions` 40 and `service` 93 were paid down — see the
positions and service 2026-07-26 sections below. `positions` is at **0**
`NO_COVERAGE`; `service` retains only `HistoricalGlamValueHandlerImpl`'s 8
documented run-loop rows, which need the clock seam recorded with them. So the
honest backlog is `service` 8, `positions` 0, plus whatever remains of
`valuationManager`'s 8.)*

*(2026-07-26, same day: `valuationManager`'s 8 went to **1** — the lost-CAS
harness killed 5 of the 7 retry-leg rows and converted the other 2 to observed
`SURVIVED` acceptances; what remains is the single drift-placeholder row, an
owner call. See "valuationManager — the lost-CAS harness" below. Honest
backlog: `service` 8, `valuationManager` 1, `positions` 0, `value` 0.)*

### valuationManager — `NO_COVERAGE` pay-down, 2026-07-25

The transaction-assembly and RPC-facing strata this file has been calling debt
since the seed were fixtured. The suite went from
**44 survived / 123 no_coverage (380/548 detected, 69%)** to
**54 survived / 8 no_coverage (486/548 detected, 88%)**; the baseline fell from
167 rows to **62**, 54 of them labelled `SURVIVED` acceptances.

| method | before (SURVIVED / NO_COVERAGE) | after |
|---|---|---|
| `prepareAUMTransaction` (+ its lambda) | 0 / 38 | 6 / 0 |
| `createTableMetaArray` | 0 / 32 | 4 / 1 |
| `run` | 5 / 17 | 5 / 0 |
| `latestStateAccount` | 6 / 9 | 5 / 4 |
| `isKVaultTokenAccount` | 0 / 5 | 2 / 0 |
| `init` | 0 / 5 | 0 / 0 |
| `processSimulation` | 10 / 5 | 11 / 0 |
| `stateChange` (+ its lambda) | 1 / 4 | 1 / 3 |
| `accountType` | 0 / 3 | 0 / 0 |
| `vaultDeleted` (+ its lambda), `stateFilePath`, `deleteStateAccount` | 2 / 3 | 1 / 0 |
| `aumTransaction`, `usdValue`, `onKaminoVaultChange` | 1 / 2 | 0 / 0 |

(The `prepareAUMTransaction` line reads `0 / 38` because the two rows the seed
labelled `# tx-assembly-uncovered` were themselves `NO_COVERAGE` — that label was
always coverage debt with a named owner, never an equivalence claim.)

Unlike `value`'s `Pacer`, there was **no single seam**: the strata are different
in kind and each needed its own fixture. Three new/extended test classes:

- **`GlamVaultTransactionAssemblyTests`** — `prepareAUMTransaction` and
  `createTableMetaArray` driven directly. Asserts the assembled *artifact*, not
  the calls that produced it: the exact instruction sequence (compute budget,
  the `refreshReservesBatch` that replaces the placeholder only when a Kamino
  reserve staged accounts, one price instruction per registered position,
  validate-AUM last), the exact return-account set, the exact `UnsupportedReason`
  at each limit, and the exact lookup-table array. The Kamino reserve fixture is
  the `ReserveContext` shape `KaminoPositionsPriceInstructionTests` already uses
  (a real record with a scope price feed poked into its `TokenInfo`), so the six
  staged metas are hand-derived and byte-comparable.
- **`GlamVaultRunDispatchTests`** — `run()`'s dispatch, with the identity of the
  published `AumTransaction` as the observable: a rebuild replaces it, a skipped
  pass leaves it alone. All three `NO_CHANGE` sub-decisions are pinned in both
  directions, including the subtle one — an unsupported state is re-assembled
  only once its reasons are *already published*, so a newly seen reason is left
  to the pass that reports it. Also `init`'s three cases, `accountType` before
  and after deletion, the `-1` "ask the database" sentinel of `usdValue`,
  `onKaminoVaultChange`, both scheduled tasks (persist, state-file delete) and
  the state-file `IOException` warning.
- **`GlamVaultProcessSimulationTests`** (extended) — the two broad-catch arms
  told apart by their payloads (an instruction index the simulated transaction
  does not have reports that index and code; anything thrown before an
  instruction can be named reports zeroed index and code), and the position
  report chain's abort in both directions.

#### Test-visibility widenings (no behaviour change)

`prepareAUMTransaction`, `createTableMetaArray` and the `accountsNeededMap` field
became package-private with the house `// package-private for tests` tag, exactly
as `stateAccountChanged`, `processSimulation`, `createKaminoLendPosition`,
`aumTransactionRef` and `processedSlot` already are. `accountsNeededMap` is what
makes `latestStateAccount`'s apply-and-persist arm reachable at all: production
only ever reaches it when `accept` published a batch whose state image `accept`'s
own `stateChange` could not apply, which is not constructible single-threaded.

#### Two real bugs, both in the account-limit guard

`prepareAUMTransaction` collected the transaction's accounts into a
`HashSet` of **`AccountMeta`**, then asked it `contains(clockSysVar)` — a
`PublicKey`. Two defects fell out of that one line:

1. **The "clock already referenced" fast path was dead.** `AccountMeta`'s
   `hashCode` is its key's, so the bucket matched, but its `equals` is
   exact-class, and `PublicKey.equals` delegates back to it — so the query could
   never be true. Every vault therefore took the `else if`, rewriting instruction
   0 with the clock-carrying compute-budget instruction even when the clock was
   already in the account set. Harmless below the ceiling; **at exactly
   `Transaction.MAX_ACCOUNTS` it silently dropped the clock from the return
   accounts**, so `processSimulation` fell back to `Instant.now()` for the record
   timestamp and every interest accrual — the exact disagreement the comment
   above that fallback warns about.
2. **The ceiling over-counted.** Exact-class equality means a read meta and a
   write meta for the same account are two elements, while `Transaction.createTx`
   merges them by key — so a vault referencing one account at two privilege
   levels could be reported `TOO_MANY_ACCOUNTS` while serializing perfectly well.

Both are fixed by keying the set on `PublicKey`, which is what the consumer does.
Pinned by `anAlreadyReferencedClockIsRequestedWithoutRewritingTheComputeBudgetInstruction`,
`theAccountCeilingIsExactlyMaxAccounts` (fixtures asserted to land on 64 and 65
keys) and `anAccountReferencedAtTwoPrivilegeLevelsCountsOnce`.

#### One test weakened by a fixture accident, strengthened

`unsupportedIntegrationsAreReportedAndKnownOnesAreNot` picked its "known
authority" with `integrationAuthorities().keySet().iterator().next()`, which
lands on the **external position program** — handled by its own arm *above* the
authority check, so no authority integration ever reached the check and its
`containsKey` mutant was equivalent by accident of the fixture. It now selects
the minimum key that is not the EPI, protocol or mint integration program, so the
authority check is the only thing standing between that integration and an
unsupported report; the mutant is killed.

#### Escapes taken from earlier acceptances

The `# assembly-only-state` and `# tx-assembly-uncovered` families are **gone** —
both named "a `prepareAUMTransaction` fixture" as their escape and both are now
killed: `aumTransaction()`'s null return and `vaultDeleted`'s clearing of it are
pinned by `vaultDeletionDropsThePreparedTransaction`, the
`refreshReservesBatch(...).extraAccounts(...)` naked receiver by the staged-reserve
assertion, and the serialize-failure diagnostic's table rendering by
`aTransactionThatCannotBeSerializedIsReportedWithItsInstructionsAndTables`, which
overflows a `LookupTableAccountMeta`'s fixed read capacity to make
`Transaction.createTx` throw for a real reason.

#### Side coverage in `positions`

The assembly fixture reaches `VaultTokensPosition.setOracleAccounts`' Kamino
reserve arm, which had never been covered: eight `# untriaged` `NO_COVERAGE` rows
in `positions-accepted.csv` (`setOracleAccounts` 182 ×2, 186 ×2, 187, 189,
191 ×2) are now killed, by three assertions that are properties rather than
restatements — a shared reserve is staged, requested back and referenced exactly
once however many assets price off it; an uncached reserve reports
`MissingKaminoReserveAccount` and drops the refresh instruction rather than
emitting one with nothing to refresh; and a reserve already in the return-account
set but not yet referenced by the price instruction is still added. Those rows go
stale in the `positions` baseline, which shrinks it; refresh it with the next
`positions` pass.

#### New acceptance families

- `# zero-offset-add` (same family as `positions`/`value`) —
  `createTableMetaArray`'s `glamTables.length + driftTableKeys.size()`. The drift
  table list is the constant `List.of()`, so `+ 0` and `- 0` are the same
  address. Escape: drift lookup tables actually being wired up.
- `# size-boundary` — `prepareAUMTransaction`'s
  `transaction.size() > Transaction.MAX_SERIALIZED_LENGTH` widened to `>=`.
  **Not an equivalence claim**: a transaction serializing to exactly 1232 bytes
  is legal, and the mutant would wrongly report it `TOO_BIG`. It is unkilled
  because the assembly fixture's knobs are coarse — an asset costs 107 bytes, a
  duplicate asset entry 11, an account moved into a lookup table saves 31 — and
  the reachable sizes step straight over 1232 (…1224, 1235…). Escape: a
  one-byte knob, i.e. an instruction whose account list can carry a single extra
  reference to an account the transaction already includes. Both `ORDER`
  directions of the same guard are killed, so what is unpinned is the boundary
  itself, not the branch.

#### Rows folded into families that already existed

- `# allocation-size` — everything whose only effect is how large an array is
  reserved: `prepareAUMTransaction` 607's `2 + (positions.size() << 2)`,
  `createTableMetaArray` 1321's kVault count forced to include every external
  position, and both `isKVaultTokenAccount` forced-`true` returns. That method's
  **only** caller is the count that sizes the meta array, and the array is
  trimmed by `Arrays.copyOfRange` to what was actually filled, so over-counting
  is invisible. The forced-`false` directions under-size the array and are killed
  by the AIOOBE they cause.
- `# fast-path` — `createTableMetaArray` 1377's `i < tableMetas.length` ternary,
  both the boundary and the forced-`true` direction: both return a *copy* where
  the original array would have done, with identical content, and the array's
  only consumer is `Transaction.createTx`. The forced-`false` direction — which
  would hand `createTx` an array with trailing nulls — is killed.
- `# log-emission` — the four `System.Logger::log` calls in
  `prepareAUMTransaction` (unsupported base asset mint, too many accounts, too
  big, and the serialize-failure ERROR). Each is paired with an asserted
  observable: the `MissingAssetMeta` reason, `TOO_MANY_ACCOUNTS`, `TOO_BIG`, and
  the `failedToSerializeValueTransaction` notification whose arguments —
  including the rendered table addresses — are asserted exactly.
- `# defensive-unreachable` — now covers both `ixIndex < 0` guards in
  `processSimulation`: the report chain's `break` and the validate-index bounds
  check. The report chain starts at instruction index 1 (index 0 is the compute
  budget instruction) and every `positionReport` either returns `-1` or a value
  `>= 2`, so exactly 0 — the only input the widened boundary changes — is not
  expressible. Both forced directions of both guards are killed.
- `# race-recovery` — `latestStateAccount` 204's `compareAndSet` forced `true`.
  Single-threaded the CAS always succeeds, so the forcing is the branch taken
  anyway; same escape as the rest of the family. *(2026-07-26: escape taken —
  killed by the lost-CAS harness below; the family is retired in this suite.)*

#### Still `# untriaged` — 1 row, coverage debt, not equivalence

- **`createTableMetaArray` 1363** (seeded at 1345) — the `tableMetas[i++]` of
  the drift lookup-table loop. `driftTableKeys` is the constant `List.of()`, so
  the loop body is unreachable dead code kept as a deliberate placeholder (the
  comment above it states the intent: "always include drift and kamino main
  tables"). Policy ranks refactor above accept and this would refactor away
  cleanly, but deleting a placeholder deletes stated intent rather than dead
  logic, so it is an owner call. **Not** an equivalence claim: the row keeps
  `# untriaged` and the argument lives here, the same way the "deliberately not
  planned" `service` wiring does.

*(This subsection used to carry 7 further rows — the lost-CAS retry legs of
`latestStateAccount` 209 ×3 / 210 and `stateChange` 253 ×3 — declined on the
belief that no seam could force a CAS to fail deterministically. That belief
was wrong in detail and the escape was taken: see the lost-CAS harness section
below. 5 of the 7 are killed; the 2 that remain are `SURVIVED` acceptances with
an *observed* equivalence argument, `# stale-gate-subsumed`.)*

### valuationManager — the lost-CAS harness, 2026-07-26

The "one harness worth building here" from the 2026-07-25 pass was built:
`GlamVaultCasRaceTests` loses `compareAndSet` races **deterministically** —
single-threaded, no sleeps, no spin — by interposing the competing write on a
collaborator each spin loop itself invokes *between* its `get()` and its CAS.
`AtomicReference.get/set/compareAndSet` are final, so the reference cannot be
subclassed; the loop body is the only place a competing writer can stand.

The suite went from **486/548 detected (88%), 54 survived / 8 no_coverage** to
**500/548 detected (91%), 47 survived / 1 no_coverage**; the baseline fell from
62 rows to **48**. (Line numbers below are the pre-harness ones the earlier
sections use; the constructor seam shifted everything after line 92 by +18.)

**The seam** (behavior-preserving by construction, `BaseNotificationService`/
`Pacer` precedent): a package-private `GlamVaultValuationManagerImpl`
constructor overload accepting the `stateAccountRef` and `glamVaultTablesRef`
`AtomicReference`s; the existing constructor delegates with fresh references
holding the same initial values, so no production wiring changed. The test owns
the reference objects and hands them to its fakes.

**The interposition points**, one per loop family:

- **Lookup-table loops** (`glamVaultTableUpdate`, `removeGlamVaultTable`):
  while scanning the witness array the loops call `address()` (and
  `numAccounts()`) on each element, and `AddressLookupTable` is an interface —
  a proxy element planted in the witness array performs the competing `set` on
  the first `address()` read, exactly between the `get` and the CAS.
- **State-account loops** (`latestStateAccount`, `stateChange`): the only call
  between the `get` and the CAS is `witness.createIfChanged(account)`, and
  `MinGlamStateAccount`/`AccountInfo` are indeed records — but `createIfChanged`
  unconditionally calls `baseAssetMint().toByteArray()` on the witness, and
  `PublicKey` **is an interface**. A proxy key planted at the witness record's
  `baseAssetIndex` (delegating every method to the real key) performs the
  competing `set` from inside the derivation. This is what the 2026-07-25
  "records are unproxyable, there is no seam" argument missed: the record is
  closed, but a component it calls through is not.

**Converted from acceptance/debt to kills — 14 rows:**

| rows (pre-harness lines) | family was | killed by |
|---|---|---|
| `glamVaultTableUpdate` 1439, 1449 | `# race-guard` | a lost replacement/append CAS must retry against the competing array — the update is re-applied, never dropped |
| `removeGlamVaultTable` 1393, 1403 | `# race-guard` | a lost removal CAS must retry until the table is actually gone |
| `stateChange` 246 | `# race-guard` | a lost CAS must not schedule persistence/reprocessing for a record the reference does not hold |
| `latestStateAccount` 204 | `# race-recovery` | a lost CAS against a newer winner must return the winner *without* persisting the stale-derived record |
| `latestStateAccount` 209 ORDER_IF, 210 | `# untriaged` (NO_COVERAGE) | the retry leg must re-apply the update on top of an older winner (persist observed), and must hand `run()` the winner, not null (processedSlot observed) |
| `stateChange` 253 ×3 | `# untriaged` (NO_COVERAGE) | the retry leg's staleness re-check: a newer-or-equal winner is a silent return, an older one a retried apply — told apart by the exact `executeTask` count (`createIfChanged`'s no-change arm schedules a reprocess when the genuine retry leg must not) |
| `latestStateAccount` 180, 184 | `# race-recovery` | the pre-loop guards under the states only a race produces: a vault deleted between `run()`'s null check and the state read (interposed through the batch map's `get`) is a **silent** skip, not a logged NPE; a null-context batch image reports the diagnostic `IllegalStateException`, not the NPE two lines later |
| `run` 282 | `# error-funnel` | bonus: the `stateAccount == null` re-check after `latestStateAccount` is now driven by the mid-pass-deletion test — the funnel argument is no longer needed |

**New family, with the equivalence *observed* rather than assumed:**

- `# stale-gate-subsumed` — the boundary (`<=` → `<`) and forced-continue
  (ORDER_ELSE) mutants of the unsigned staleness gates in `latestStateAccount`:
  the pre-loop gate (old 188 ×2, now 206) and the retry leg's re-check (old
  209 ×2, now 227 — previously `NO_COVERAGE`, now covered by the harness).
  Diverting past either gate lands on `createIfChanged`, whose **first check is
  the identical unsigned comparison** returning null, which converges on the
  same "keep the witness, no side effect" — for `latestStateAccount` the
  no-change arm is a bare `return witness`, so unlike `stateChange` (whose
  no-change arm schedules a task and is therefore killable, and killed) there
  is no observable to split. The equal-slot and newer-slot lost-CAS tests drive
  the diverted path, so this is watched equivalence, not unreached code.
  Escape: `createIfChanged` losing its internal slot re-check, or the no-change
  arm growing any side effect. These rows were `# race-recovery`; that family
  is now retired in this suite.

**Reclassified — the guard a race cannot reach:**

- `vaultDeleted` 545 (now 563) moved `# race-guard` → `# defensive-unreachable`.
  The null re-read after `getAndSet(null)` is not a race guard: the only writer
  of null is `vaultDeleted` itself, which sets the sticky
  `STATE_ACCOUNT_DELETED` gate *before* nulling the reference — so any thread
  observing a null reference must observe the gate set and lose the
  `putIfAbsent` above, never reaching the re-read. Unreachable under **every**
  interleaving, not just single-threaded ones; killing it would mean
  fabricating a state no writer order can produce.

**Deliberately out of the harness's reach — 1 row stays `# race-guard`:**

- `glamVaultTableUpdate` 1425 (now 1443), the null-arm
  `compareAndSet(null, [table])` forced true. Between that arm's `get()` and
  its CAS the loop calls **nothing** — no collaborator, no allocation with
  observable dispatch — so there is no interposition point and the lost-CAS leg
  cannot be reached deterministically. The mutant only differs when that CAS
  fails, and its killed ELSE sibling pins the success path. Escape: the null
  arm growing any call between the read and the CAS.

### value — SURVIVED pay-down, 2026-07-24

The seeded 148 `SURVIVED` rows were worked down to the 62 acceptances below
(86 baseline survivors killed; side coverage also converted ~47 `NO_COVERAGE`
rows and every newly surfaced survivor was killed or accepted into these
families). Notable kills along the way: the vault-deletion teardown is pinned
registration-by-registration (tables, pending fetches, positions, ATAs, kVault
subscription, mint usage, the once-only file-cleanup task); the kVault share
token account path is exercised end to end (cache hit, uncached-table queueing,
cached-table registration, removal unsubscribe) with a directly constructed
`KaminoVaultContext`; the `latestStateAccount`/`stateChange` unsigned staleness
gates are pinned in both directions including the equal-slot boundary; the
`fetchAccounts` batch limit is pinned exactly AT and one past
`MAX_MULTIPLE_ACCOUNTS`; `processSimulation` gained the mint-PDA supply read,
both bounds-guard edges, CPI program/event filtering, the kVault-shares
cache-invalidation error arm, and the mapped-error-message capture; every
`VaultAumRecord` SQL column binding is asserted by exact parameter index; the
`removeGlamVaultTable` splice special cases were refactored away (zero-length
`arraycopy` is a no-op) and the redundant `requestAumTxRebuild` in the
invalid-oracle arm was removed; rebuild-request scheduling is pinned through
the `compareAndSet` dedup gate. The memoizing `UnsupportedReason` factories are
pinned with per-invocation keys because PIT reuses minion JVMs across mutants —
a fixed key is already memoized by an earlier mutant's run of the same test,
making first-call mutants invisible.

- `# race-guard` — observable only under a concurrent interleaving: CAS
  success-branch forcings and lock-free re-checks (the
  `glamVaultTableUpdate`/`removeGlamVaultTable` copy-on-write CAS breaks,
  `stateChange`'s witness CAS at 245, `vaultDeleted`'s post-gate null re-read,
  `UnsupportedExternalPosition`'s `get`/`putIfAbsent` memoization recovery
  legs, and `GlobalMintUsageTrackerImpl`'s double-checked empty-drop — the
  `REMOVE_EMPTY` compute function re-verifies null/empty authoritatively, so
  the pre-check legs are advisory). Single-threaded the CAS always succeeds
  and the guarded race never happens, so the forced branch is the branch
  taken anyway. Escape: a concurrency harness that loses a CAS race
  deterministically.

  *2026-07-26: the escape was taken where it can be taken.* The lost-CAS
  harness (`GlamVaultCasRaceTests`, see the valuationManager section) killed
  every member of this family that guards an `AtomicReference` spin loop — the
  table CAS breaks, `stateChange`'s witness CAS — and reclassified
  `vaultDeleted`'s null re-read (not a race guard at all; see
  `# defensive-unreachable` there). What stays in **this** suite's family is
  the `ConcurrentHashMap`-shaped remainder (`get`/`putIfAbsent` memoization
  recovery, compute-function re-checks, the merge remap, `acceptStateAccount`'s
  `putIfAbsent`): the harness's mechanism is interposition through a
  collaborator the loop calls between its read and its CAS, and these have no
  such window — the competing mutation would have to fire from inside CHM's own
  `putIfAbsent`/`compute`/`merge`, whose internal call order (when a key's
  `hashCode`/`equals` runs relative to bin locking) is unspecified. A harness
  built on unspecified library internals is the flaky-harness shape the policy
  forbids, so these rows remain acceptances with this narrower, sharper escape:
  a competing writer standing inside a *specified* callback (e.g. the remap
  function itself invoking a collaborator).
- `# log-emission` — `System.Logger::log` call removals plus the
  `error == null ? "null" : …` log-string ternary in
  `reportUnknownSimulationFailure` (762): the paired notification-service
  calls carry the same data and are asserted; log text is not a pinned
  contract here. `GlamStateContextCache.createManager` 113 is the same shape
  with no paired notification at all — a cache file that will not load is a
  WARN and a skip, and the skip is what the tests assert.
- `# fast-path` — both branches converge on the same observable: the seven
  per-protocol accessors' `get`-before-`computeIfAbsent` (forcing the miss
  path makes `computeIfAbsent` return the existing instance; the `instanceof`
  guards are killed via polluted-map tests), and `stateAccountChanged` 520
  where both legs escalate `ACCOUNTS_NEEDED`, differing only in a
  race-condition warning log.
- `# race-recovery` — `latestStateAccount`'s second-chance path (179, 183,
  187, 200): `run()` re-reads the batch's state image after `accept` already
  applied it on the same thread, so deterministically the slot pre-checks
  resolve to "keep the witness" and the mutants divert onto `createIfChanged`,
  whose own unsigned slot check subsumes them; the CAS-apply arm only executes
  when `accept`'s application lost a concurrent race. Escape: same harness as
  `# race-guard`.

  *2026-07-26: family closed — no rows carry this label anywhere.* Its rows had
  moved to the `valuationManager` baseline with the suite split; the lost-CAS
  harness killed 180/184/204 outright and the two staleness-gate rows moved to
  `# stale-gate-subsumed` with the subsumption now *observed* through the
  harness-driven diverted path (see the valuationManager section).
- `# error-funnel` — removal fails into the identical observable: `run()`'s
  null guards (263/268/274/281) NPE into `run()`'s broad catch — same no-op
  plus a warning log; observed: no service-context call differs.
  `acceptTable` 224's unknown-state-key guard NPEs into `acceptTable`'s own
  catch, another logged no-op — no state touched, the file write sits behind
  the (unreached) `glamVaultTableUpdate` acceptance. Also the five disk-load
  guards of `GlamStateContextCache` forced *true*: `createManager` 103/106
  (a non-regular file funnels into `readAccountData`'s IOException handler,
  which answers `AccountData.EMPTY`; an empty image funnels into
  `MinGlamStateAccount.deserialize`'s trailing-slot read, which throws into
  `createManager`'s own `catch (Exception)` — both reach the same `return
  null`, i.e. "no manager for this file") and the `loadTables` lambda's
  54/57/61 (a directory entry, a file without an account extension, and a
  table naming an unloaded vault each reach the lambda's per-file
  `catch (Exception)` instead of its skip branch — same "this file
  contributes no table", and the catch sits *inside* the `forEach` body so
  neighbouring files are unaffected). Funnel observed, not assumed:
  `junkInTheCacheDirectoriesIsSkippedWithoutFailingStartup` puts every one of
  those shapes in both directories and asserts the readable vault and its
  table still load. The corresponding forced-*false* siblings are killed —
  those are the directions that would drop a real vault or a real table.
- `# tie-selection` — a boundary shift that picks between equal values:
  `StateChange.escalate` (`<=` → `<` differs only at equal ordinals, and equal
  ordinals of one enum are the same constant, so both branches return the
  identical object) and `run()` 287 (the unsigned max of two equal slots is
  the same value either way).
- `# allocation-size` — `processSimulation` 915/917 capacity arithmetic for
  `ArrayList`/`HashMap.newHashMap`, and `GlamVaultValuationManager.createManager`
  61's `newKeySet(3 + (numAccounts << 2))` sizing hint: changes what is
  reserved, never what is computed.
- `# pattern-desugar` — `processSimulation` 806/807: the record patterns
  (`instanceof InstructionError(...)`, `IxError.Custom(...)`) desugar into
  duplicated type/null checks; every source-level input class (null error,
  non-instruction error, non-custom ix-error, custom code) is pinned and the
  killed same-line siblings carry those semantics; the surviving forced-true
  legs sit on compiler-duplicated checks unreachable through source
  semantics.
- `# defensive-unreachable` — `processSimulation` 966 `ixIndex < 0` → `<= 0`:
  differs only for a valid index of exactly 0, but the report chain starts at
  instruction index 1 (index 0 is the compute-budget instruction), so 0 can
  never reach the guard as a valid index; the `>= length` edge and the
  null-entry leg are killed.
- `# assembly-only-state` — `aumTransaction()`'s NullReturn (335) and
  `vaultDeleted`'s `aumTransactionRef.set(null)` (549): `aumTransactionRef` is
  only populated by `prepareAUMTransaction` (the transaction-assembly
  stratum, `NO_COVERAGE` by design), so no deterministic harness observes a
  non-null getter or its clearing. Escape: kill both when a
  `prepareAUMTransaction` fixture exists.
- `# dry-run-const` — `GlamVaultExecutorImpl.apply` 225's surviving leg forces
  `!Entrypoint.DRY_RUN` true, but `DRY_RUN` is a `static final` system
  property read at class-init and is false in the test JVM, so the forcing is
  identity there; the sentinel-filtering `instanceof` leg is killed. Escape: a
  separate JVM run with the dry-run property set.
- `# tx-assembly-uncovered` — **not an equivalence claim.** `NO_COVERAGE`
  rows are never accepted as equivalent (the behaviour was never observed);
  this label marks a row as *coverage* debt with a named owner so the
  baseline distinguishes it from unexamined `# untriaged` seed debt. Both
  rows are `prepareAUMTransaction` naked receivers (663
  `refreshReservesBatch(...).extraAccounts(kaminoReserveAccounts)`, 713 the
  `map(accountMeta -> …lookupTable().address().toBase58())` of the
  serialize-failure diagnostic). Reaching either needs the transaction-assembly
  fixture this suite does not have — 663 additionally needs a Kamino position
  contributing reserve accounts, 713 needs `Transaction.createTx` to throw
  with a non-empty table array. Both would be observable if reached (a
  refresh-reserves instruction without its reserves; a tables list rendered as
  object `toString`s), so they are debt, not closed work. Escape: the same
  `prepareAUMTransaction` fixture named under `# assembly-only-state`.

- `# sdk-loop-only` — `VaultAum`'s `createAumSqlExecutor` /
  `createPositionSqlExecutor` row-count lambdas (53/72): the returned count
  feeds `BatchSqlExecutor`'s internal accounting inside glam-sdk-java's async
  loop; the per-record `prepare`/`preparePositions` logic itself is pinned in
  `VaultAumRecordTests`. Escape: a synchronous `BatchSqlExecutor` test double
  in the sdk.

### value — mutator widening (BIG_DECIMAL + NAKED_RECEIVER), 2026-07-25

`pitestValue`'s mutator set was widened from `STRONGER` to
`STRONGER,EXPERIMENTAL_BIG_DECIMAL,EXPERIMENTAL_NAKED_RECEIVER` (trial numbers in
`build.gradle.kts`: BIG_DECIMAL 1 generated / 0 unkilled, BIG_INTEGER 0 — cannot
fire here, NAKED_RECEIVER 29 / 12). The single BigDecimal mutant —
`processSimulation`'s `decimalBaseAssetAUM.multiply(baseAssetPrice).setScale(2,
HALF_EVEN)` — was already killed by the banker's-rounding tie tests. The 12
newly-visible **NakedReceiver** rows (a fluent call replaced by its receiver, i.e.
the call's effect silently dropped) went to **10 killed / 2 accepted**.

Killed as-is:

- `GlamStateContextCache.loadStateContextFromDisk` 84 and 123 — `Path::resolve`
  dropped, flattening every environment's cache into one directory. New
  `GlamStateContextCacheLoadTests` drives `loadCache` over `@TempDir` layouts: a
  cold start creates one directory per `GlamEnv`; a debug vault is found under its
  environment directory and *not* in the cache root; a warm start restores both
  environments' vaults with the `GlamEnv` taken from the directory that held them
  and re-applies the lookup tables cached alongside.
- `GlamVaultValuationManagerImpl.latestStateAccount` 194 — `createIfChanged`
  dropped, taking the witness as if it were a freshly derived record. The witness
  comes back either way, so the *absence of a persist* is the only observable:
  `run()`'s re-read of an unchanged batch image must not rewrite the state file.
- `GlamVaultValuationManagerImpl.priceVault` 792 — `thenApplyAsync` dropped, so the
  future would carry the raw `TxSimulation` instead of the decoded `VaultAum`.
  Pinned with a direct-executor `taskExecutor` stub and a `simulateTransaction`
  proxy, asserting both the decoded result and that the *prepared* transaction and
  return accounts are what gets simulated.
- `GlamVaultExecutorImpl.refreshStateContext` 187 — `Stream::sorted` dropped.
  Widened to package-private; the cycle snapshot is asserted sorted by **unsigned**
  USD value and the refresh asserted to fetch in that order.

Killed after extracting the unit out of a wall-clock `run()` loop. Both service
`run()` loops open with `BasePeriodicService.waitUntil`, which sleeps to a
wall-clock second/minute boundary and exits only on interrupt, so no deterministic
single-cycle harness exists and the determinism rule forbids the sleeping one.
Following each file's own precedent (`fromJupiter`/`dropBatch` were already pure
package-private statics) and HARDENING's "extract the construction, not the
emission", the mutated expressions became named pure units with tests:

- `JupiterPriceFetcherImpl.mintsInUse` (was `run` 83, `Stream::mapMulti` — the
  request list would have been tracker entries, and every dropped mint would still
  cost a slot in the rate-limited budget) and `.priceBatch` (was `run` 96,
  `List::subList` — dropping it sends the *entire* mint list in every request).
- `GlamVaultExecutorImpl.submitPricing` (was `run` 72 and 104, duplicated
  `CompletableFuture::thenApply` — dropping it means no priced result ever reaches
  `apply`, the only path to the insert executors, so a healthy-looking cycle would
  persist nothing) and `.nonPricedVaults` (was `run` 143, `Stream::mapMulti`, the
  give-up diagnostic's payload).

Accepted: 2, both `NO_COVERAGE` in `prepareAUMTransaction` — see
`# tx-assembly-uncovered`, which is explicitly a coverage-debt label rather than an
equivalence family.

`TIMED_OUT` note: this suite now reports 2 timed-out mutants
(`GlamVaultValuationManagerImpl.glamVaultTableUpdate` 1404's `witness == null`
forced true, and `GlamVaultExecutorImpl.refreshStateContext` 236's
`to == numVaults` break forced false). Both are *genuine non-termination* — a spin
loop whose CAS can never succeed, and a batch loop whose exit condition is gone —
not slow mutants, so they cannot flip to `SURVIVED` under a lighter or heavier
load and need no flip-insurance union. Two consecutive unscoped runs reported the
identical `580/993 — 69 survived, 344 no_coverage, 2 timed out`.

Side coverage from the new tests converted ~46 `NO_COVERAGE` rows into kills
(the disk-load and restart-recovery strata: per-environment cache directories,
warm-start table restore, the debug-vault load filter, the corrupt-entry skip that
must not fail startup, the tokenized-vault shares mint on `validateAum`, and
`priceVault`'s unsupported/pending-rebuild gates). Seven rows flipped
`NO_COVERAGE → SURVIVED` and were triaged into the existing `# error-funnel`,
`# log-emission` and `# allocation-size` families above. One real robustness
property was pinned along the way: the state-file `forEach` has **no** per-file
recovery, so a null manager reaching `manager.key()` escapes `loadCache` entirely
— an unreadable cache entry would take the process down before it binds a port;
`junkInTheCacheDirectoriesIsSkippedWithoutFailingStartup` now holds that guard.

### value — `NO_COVERAGE` pay-down, 2026-07-25

`hardening { generateTestSupport = true }` put `ManualScheduledExecutor` (a
deterministic fake clock at a non-zero origin) and `JulRecorder` in reach, and the
suite's whole `NO_COVERAGE` population — **221 rows, gone to zero** — was worked off
against them. The baseline fell from **246 rows (25 `SURVIVED`, 221 `NO_COVERAGE`) to
42 rows, all `SURVIVED`, all labelled**; detection went 359/447 → **405/447 (90%)**.

| class | before (SURVIVED / NO_COVERAGE) | after |
|---|---|---|
| `GlamVaultExecutorImpl` | 1 / 90 | 9 / 0 |
| `JupiterPriceFetcherImpl` | 0 / 31 | 0 / 0 |
| `GlamStateContextCacheImpl` | 4 / 25 | 4 / 0 |
| `OrcaWhirlpoolCacheImpl` | 0 / 23 | 6 / 0 |
| `PositionReportLeaf` | 0 / 20 | 0 / 0 |
| `WhirlpoolState` | 0 / 8 | 1 / 0 |
| `BasePeriodicService` | 0 / 7 | 0 / 0 |
| `MintPrice` | 0 / 7 | 0 / 0 |
| `PositionReportNode` | 0 / 2 | 0 / 0 |
| `GlamStateContextCache` | 6 / 1 | 6 / 0 |
| factories (`GlamVaultExecutor`, `OrcaWhirlpoolCache`, `JupiterPriceFetcher`) | 0 / 5 | 0 / 0 |

#### The production seam: `Pacer`

Both `BasePeriodicService` run loops were previously recorded as
"untestable as written": each is a `for(;;)` whose only pause is a wall-clock sleep and
whose only exit is an interrupt, so a single-cycle harness would have had to sleep for
real — which the determinism rule forbids and which PIT would multiply by the mutant
count.

The seam is one new interface, `Pacer`, carrying **everything those loops read from or
block on the clock**: `now()`, `localTime()`, `nanoTime()`, `sleep(unit, amount)`.
`SystemPacer` is the production implementation and is *literally the code that was
inline before the seam existed* (`Instant.now()`, `LocalTime.now()`,
`System.nanoTime()`, `TimeUnit#sleep`). It is behavior-preserving by construction, and
it is injected the way `BaseNotificationService`'s notifier already is in this repo:
the existing constructors are unchanged and delegate to an overload that passes
`Pacer.SYSTEM`, so no production wiring — `GlamVaultExecutor.createExecutor`,
`JupiterPriceFetcher.createFetcher`, `Entrypoint` — mentions it. `waitUntil` changed
from `protected static` to `protected` instance for the same reason and with the same
body. `SystemPacer` is itself covered, including the sleep: pre-setting the interrupt
status makes `Thread.sleep` throw immediately, so "this reaches a real interruptible
wait" is asserted without the test ever waiting.

`PacerTestHarness` (test-side, `*Test*`-named so the suite's `excludedClasses` glob
keeps it out of the mutated population) implements `Pacer` over the generated
`ManualScheduledExecutor`: no wait is real, every wait is recorded with the unit and
amount the loop asked for, the fake clock advances by exactly that amount, and a wait
budget interrupts the loop at a chosen point. Pacing, backoff and retry choreography
become exact functions of the loop's own arithmetic.

What that bought, as *properties* rather than restatements:

- **`GlamVaultExecutorImpl.run`** — the 55/60 second alignment is a hand-derived pair of
  waits from a `:10` origin (45s then 5s), landing every vault in a cycle on one
  `date_minute`; vaults price most-valuable-first; stale-oracle retries back off exactly
  one second per attempt (1s, 2s) and give up naming exactly the still-unpriced vaults;
  state changes retry *immediately* with a targeted re-fetch and **no** backoff, with
  each BitSet slot resolving independently (no future, null AUM, settled record, fresh
  stale failure, and a vault deleted mid-retry); the BitSet is cleared between cycles, so
  a give-up cannot leak into the next minute; consecutive IO failures back off one second
  each **capped at ten**, and one clean cycle resets the count; a non-IO failure ends the
  loop *without* the interrupt status, so a crash is distinguishable from a shutdown.
  The retry diagnostics' counts are asserted through `JulRecorder`, which is what makes
  the `++numVaults` / `++numStaleOracles` increments observable at all.
- **`JupiterPriceFetcherImpl.run`** — requests are paced at `1s / requestsPerSecond`
  measured from *issue* time; a budget past one request per nanosecond floors the delay
  to zero and stops pacing entirely (which is also the boundary that makes the
  `nowNanos < nextRequestNanos` comparison's `<=` mutant visible); a failed batch is
  re-requested, never skipped, until `MAX_BATCH_ATTEMPTS`, then dropped with a WARNING
  naming its exact window, and the drop resets the per-batch counter for the next batch;
  `errorCount` decays on every success and never below zero, so a first failure after a
  clean run costs one unit of delay and not none.
- **`GlamStateContextCacheImpl.run`** — the defensive discovery loop needed no seam at
  all: its wait is `globalConfigCache.awaitNewGlobalConfig`, already an injected
  dependency. One pass initialises every registered vault (a vault whose `init` throws is
  warned about and the rest still start), then scans all three sources — lookup tables,
  production state accounts, staging state accounts — and routes each; a failed pass logs
  and backs off on a *growing consecutive* count.

#### Two production changes beyond the seam

- **A dead null guard and a stale doc comment in `JupiterPriceFetcherImpl`.**
  `fromJupiter`'s javadoc claimed it "returns null when the block id exceeds the signed
  long range", and `run` guarded on that with `if (mintPrice != null)`. The
  implementation has no null return — a block id past 2^63 narrows to its own unsigned
  bit pattern, which is the *documented and tested* behaviour
  (`aBlockIdPastTheSignedRangePersistsAsItsUnsignedBitPattern`). The guard was
  unreachable, so its mutants were unkillable by construction; policy ranks refactor
  above accept, so the guard was deleted and the comment corrected. `AGENTS.md`'s
  "a `blockId` past long range soft-fails (skip + warn)" landmine was stale for the same
  reason and was corrected with it.
- **`JupiterPriceFetcher`'s statement binder became a named method.** As
  `(ps, record) -> record.prepare(ps)` it was only ever invoked from inside glam-sdk-java's
  async batch loop, so its row-count mutant was `NO_COVERAGE` with no reachable caller —
  the same shape as `VaultAum`'s `# sdk-loop-only` rows. It is now
  `JupiterPriceFetcherImpl::prepareRow`, package-private and directly pinned, following
  the precedent of `fromJupiter`/`dropBatch`/`priceBatch` in the same file. (`VaultAum`'s
  two rows are `SURVIVED`, not `NO_COVERAGE`, and stay accepted.)

#### Mechanical coverage, no new tooling

`PositionReportLeaf` and `MintPrice` reuse `VaultAumRecordTests`' recording
`PreparedStatement` `Proxy`, asserting each column by exact parameter index — `MintPrice`
notably pins that `timestamp` binds at **3**, not 4, because the `mint_prices` INSERT
carries a literal `'JUPITER'` source that occupies no parameter. `WhirlpoolState` is
byte-poked at the generated offsets with a `0x5A` tripwire fill. `PositionReportNode`
pins that its row count is the *sum* over the subtree. `OrcaWhirlpoolCacheImpl` is pure
in-memory and was covered outright: the size+discriminator dispatch guard, unsigned
freshest-wins in both directions, the logged-null contract for an undecodable account,
and the program subscription's four arguments.

#### New acceptance families (2026-07-25)

- `# unreachable-boundary` — `GlamVaultExecutorImpl.run` 121 and 145, the `i < 0` break
  of each state-change walk widened to `i <= 0`. `i` is always
  `stateChanges.nextSetBit(prev + 1)` with `prev >= 0`, so the result is either `-1` or
  `>= 1`; exactly 0 is not expressible. Escape: a walk whose `nextSetBit` argument can be
  0, i.e. restructuring the loop to re-scan from the start.
- `# retryable-sentinel-exhaustive` — `GlamVaultExecutorImpl.run` 99 and 136, the
  forced-*true* directions of `== RETRY_STATE_CHANGE` and of the
  `== RETRY_PRICE_TOO_OLD || == RETRY_PRICE_DIVERGENCE_TOO_LARGE` pair. Both sit under a
  `vaultAUM.retryable()` gate, and `retryable()` is true for exactly three values — the
  three `VaultAumSentinel` constants — so at line 99 the value *is*
  `RETRY_STATE_CHANGE`, and at 136 it is one of the other two. Forcing "yes" is the
  branch already taken. The forced-*false* directions are killed
  (`aStateChangeRetryThatComesBackStaleJoinsTheStaleOracleGroup` drives one vault through
  each stale kind in the same cycle and asserts the retry diagnostic counts both).
  Escape: a fourth retryable `VaultAum`.
- `# empty-init` — `GlamStateContextCacheImpl.acceptStateAccount` 201, `manager.init()`
  dropped after a newly discovered vault is registered. The only vault type this fixture
  can construct is `SingleAssetVault`, whose `SingleAssetValuationManager.init()` has an
  empty body, so for that type the removal is genuinely behaviour-preserving. **Not a
  claim about the other types**: a `Vault`/`TokenizedVault` fixture would build a
  `GlamVaultValuationManagerImpl`, whose `init()` does real work — and that class belongs
  to the `pitestValuationManager` suite, so pulling it in here would move that suite's
  baseline. Escape: cover this from the `valuationManager` suite, where the fixture
  already belongs.
- `# guard-subsumed` — `OrcaWhirlpoolCacheImpl.accept(List, Map)` 74, the
  `AccountFetcher.isNull` skip forced false. Every account it would have skipped is
  either `null` — caught by `accept(AccountInfo)`'s own null guard — or zero-length, which
  cannot equal `Whirlpool.BYTES` and so fails the dispatch gate. Both routes are the same
  no-op the skip produces. Escape: an "absent" representation whose data length is
  `Whirlpool.BYTES`.

Rows folded into families that already existed:

- `# fast-path` — `OrcaWhirlpoolCacheImpl.whirlpool` 39's three surviving directions
  (`existing != null` forced false, the unsigned `>= 0` forced false, and the `>=` → `>`
  boundary). All three fall through to the `merge` below, whose remap function
  re-evaluates the identical unsigned comparison and returns the identical instance; the
  guard is a lock-free fast path, not a decision. The killable directions — skipping the
  merge when the update *is* newer — are killed by
  `onlyAStrictlyNewerSlotReplacesTheCachedState`.
- `# race-guard` — `OrcaWhirlpoolCacheImpl`'s `merge` remap at 46 (single-threaded it only
  ever runs with `previous.slot < update.slot`, so "keep previous" is unreachable) and
  `GlamStateContextCacheImpl.acceptStateAccount` 199's `putIfAbsent(...) == null` forced
  true (single-threaded it is always null; the leg exists for the thread that loses the
  registration race).
- `# allocation-size` — `GlamVaultExecutorImpl.run` 65's `stateCache.size() << 1` and 72's
  two grow-guard directions. The array is re-allocated from the *snapshot* length
  whenever the snapshot outgrows it (killed by
  `theResultsArrayGrowsWhenTheSnapshotOutgrowsIt`, which registers more vaults than the
  cache reports), so an under-sized initial hint self-heals and an over-eager re-allocation
  reserves more without computing anything different.
- `# zero-offset-add` — `WhirlpoolState.read` 27's `o + WhirlpoolRewardInfo.MINT_OFFSET`,
  where the generated constant is 0. Same family and same escape as the positions rows: a
  regenerated IDL type moving the field off offset 0 makes it killable.

### positions — SURVIVED pay-down, 2026-07-24

The seeded 145 `SURVIVED` rows were worked down to the 46 acceptances below
(102 baseline survivors killed; the tests also converted ~80 `NO_COVERAGE`
rows into kills and surfaced newly-covered survivors, all of which were killed
or accepted into these families). Notable kills along the way: the
`priceInstruction` ledger/collateral walk of `LoopscalePositions` is now
pinned against a hand-poked `Loan` image with tripwire bytes outside every
legal read region; the kVault allocation walk is pinned to exactly
`VAULT_ALLOCATION_STRATEGY_LEN` slots with a poisoned sentinel one stride past
the array; `growthInside`'s strict-lower/inclusive-upper wrap boundaries are
pinned with hand-derived fee expectations at the exact boundary ticks;
serialized `numVaults`/`numPositions` instruction arguments are byte-compared
against reference instructions; and every `-1` failure sentinel is asserted
exactly (the manager tests `index < 0`, so a mutated `return 0` is a fake
success).

- `# log-only` — `VoidMethodCallMutator` on `System.Logger::log` in soft-fail
  paths (Loopscale/Orca/VaultTokens/Phoenix), including both rejection
  warnings in `LoopscalePositions.u64AmountOrNull`. The observable contract —
  the null/-1/skip result and any `UnsupportedReason` — is pinned by tests;
  the removed call only drops the diagnostic WARN text. Extracting message
  construction for assertion is not worth a layer here; escape: if any of
  these messages becomes an alerting input, pin the rendered text. (The
  monitor tests in the `service` suite do capture JUL output, so the escape is
  cheap to take if one of these ever needs to be observable — it was weighed
  for the `u64AmountOrNull` pair, whose warnings are the only signal that a
  vault stopped reporting, and declined only to keep this family consistent.)
- `# capacity-hint` — `ArrayList`/`HashSet` initial-capacity arithmetic
  (`<< 1`, `* 3`, size sums): changes what is reserved, never what is
  computed.
- `# zero-offset-add` — `a + F_OFFSET` mutated to `a - F_OFFSET` where the
  generated constant is 0 (`CollateralData.ASSET_MINT_OFFSET`,
  `WhirlpoolRewardInfo.MINT_OFFSET`,
  `PositionRewardInfo.GROWTH_INSIDE_CHECKPOINT_OFFSET`,
  `Balance.ACTIVE_OFFSET`, Phoenix `POSITION_ASSET_ID_OFFSET`): `a ± 0` is the
  same address. Escape: a regenerated IDL type moving any of these fields off
  offset 0 makes the row killable (and the mutation report will flag it as
  newly non-equivalent debt).
- `# zero-value-guard` — a zero/short-circuit guard whose removal computes the
  identical zero through the arithmetic it guards: zero-elapsed interest
  accrual (`rate * 0 / 1e18`), `feeDelta`/reward-delta with zero liquidity or
  zero growth delta (`0 * L / Q64`), token deltas of zero liquidity,
  `decimalAmount` ternaries whose value is dead when `amount == 0` (the leaf
  gate `amount != 0` is separately pinned), and the SPL stake-pool
  zero-lamports leg (`0 / supply` equals the `ZERO` shortcut; the
  divide-by-zero leg is killed by test).
- `# fee-gate-subsumed` — `kVaultPositionLeaf`'s AUM entry gate (`signum() >
  0` boundary/forced-true at line 410) and the `sinceLast > 0` management-fee
  gate (line 418). Management and performance charges are non-negative for
  any on-chain-valid fee configuration (kVault fee bps are bounded u64;
  `prevAum` is a non-negative scaled fraction), so fees only reduce AUM and
  the post-fee `signum() > 0` recheck (line 435, killed by test) reproduces
  the identical zero leaf; a zero-elapsed management fee multiplies by zero.
  Escape: fee bps outside the on-chain-representable range (would read as a
  negative long and *increase* AUM) — not constructible from validated kVault
  state.
- `# mod-2pow128-canonical` — `OrcaWhirlpoolPricing.valuePosition` line 131's
  `rewardGrowthGlobal.add(growthDelta).and(U128_MASK)` naked-receiver drop.
  The accrued reward growth global has exactly one consumer, `growthInside`,
  whose every arm is a `wrappingSub` — and `wrappingSub` masks its own result,
  so an unmasked sum congruent mod 2^128 produces a bit-identical
  `growthInside`. Port-fidelity note: the mask mirrors the Rust `u128`
  wrap-around, it does not change results. Escape: any consumer that uses the
  accrued growth global outside mod-2^128 arithmetic (a comparison, a division
  that is not preceded by a `wrappingSub`) makes this killable.
  The `add` sibling at the same line is killed by the emission-window test.
- `# identity-only-refresh` — `RegisteredPositions.positions` line 73: the
  forced-true directions of the refresh condition rebuild and store a sorted
  array with identical content when nothing changed. The field is private and
  only read via binary search / `isEmpty`, so the difference is array
  identity only; the killable directions (stale cache after removal/swap,
  first-parse NPE) are all killed by tests.
- `# unreachable-null-slot` — `RegisteredPositions.positionReport` line 133's
  `observation == null` guard: `ObservationState.read` materializes all 16
  `PositionObservation` slots from account bytes, so a null slot cannot reach
  the loop. Escape: a hand-constructed `ObservationState` (not byte-decoded)
  with null slots, or a generated reader change that leaves trailing slots
  null.
- `# reward-skip-funnel` — Orca reward skip guards forced *false*: a
  zero-amount reward prices through `tokenUsd`, which returns `ZERO` for a
  zero amount (adding `ZERO` to the leaf), and a `NONE` reward mint funnels
  into the missing-asset-meta lookup, which returns null and is skipped by
  the (test-killed) null guard — identical leaf value either way, one extra
  WARN log at most. The skip-everything (forced-true) directions are killed
  by the owed-reward test.
- `# unreachable-null-guard` — the `x == null ||` leg of a compound guard whose
  null is not constructible in context; same shape as
  `# unreachable-null-slot`, one step further out. `LoopscalePositions`
  `positionReport` 244: `Ledger.read` materializes `principalMint` with
  `readPubKey`, so only the `PublicKey.NONE` leg can ever fire (that leg IS
  killed — the fixture's trailing `NONE` ledgers must be skipped).
  `LoopscaleVaultPositions` `positionReport` 227: `pricingByVault` is keyed by
  the very `stakeToVault.values()` set the stake loop iterates, and the vault
  loop above either populated every key or returned -1, so `pricing` cannot be
  null (the `lpSupply() == 0` leg IS killed). Escape: either a generated reader
  that can return a null pubkey field, or a `pricingByVault` population path
  that can skip a vault without failing the report.
- `# redundant-floor` — `KaminoPositions.kVaultPositionLeaf` line 438's
  `vaultAum.setScale(0, RoundingMode.FLOOR)` naked-receiver drop:
  `toBigInteger()` on the next call truncates toward zero, and the enclosing
  `vaultAum.signum() > 0` gate (line 436, killed by test) makes truncation and
  floor the same operation. The `setScale` states the on-chain `floor(aum)`
  intent explicitly and is kept for that reason. Escape: a negative `vaultAum`
  reaching this line — i.e. the post-fee `signum() > 0` gate moving or widening.

#### positions — 2026-07-25, the widened mutator set

`EXPERIMENTAL_BIG_DECIMAL` / `EXPERIMENTAL_BIG_INTEGER` /
`EXPERIMENTAL_NAKED_RECEIVER` surfaced 50 unkilled mutants, all in money math:
`multiply`→`divide`, `subtract`→`add`, `negate`→`plus`, `and`→`or`, and dropped
`movePointLeft` / `stripTrailingZeros` / `multiply` / `Instruction::extraAccounts`
calls. **All 50 were killed**; the acceptances added were three rows that the
new coverage surfaced underneath them (`# redundant-floor`,
`# mod-2pow128-canonical`, and the two `# unreachable-null-guard` legs), plus
family-labelled `# log-only` / `# capacity-hint` rows.

Most were `NO_COVERAGE` on `positionReport` leaf strata that had never been
fixtured, so the work was new fixtures rather than sharper assertions:
`StakeAccountPositionsTests`, `PhoenixPositionsReportTests`,
`LoopscalePositionsReportTests`, `LoopscaleVaultPositionsReportTests`,
`MarginfiPositionsReportTests`, `KaminoPositionsPriceInstructionTests` and
`MarginfiCacheFetchTests` are new. Notable outcomes:

- **`stripTrailingZeros` drops were converted into tightened assertions, not
  acceptances.** Every leaf value is normalized before it is persisted, so the
  scale is part of the observable, and `compareTo` cannot see it. The leaf
  assertions in Kamino/Loopscale/LoopscaleVault/Marginfi/Orca/Phoenix/Stake/
  VaultTokens now use exact `BigDecimal.equals` against a hand-derived value
  whose raw product carries trailing zeros (e.g. `1.292900` must report as
  `1.2929`). Zero `stripTrailingZeros` rows remain accepted.
- **`Instruction::extraAccounts` drops are real behavioural differences** — the
  priced accounts never reach the instruction — and every one is killed by
  asserting the resolved tail of the emitted instruction's account list
  (Kamino ×3, Phoenix, Stake, Marginfi). The Kamino obligation walk gained the
  house tripwire treatment: both `DEPOSITS_LEN`/`BORROWS_LEN` arrays fully
  populated with distinct cached reserves and an uncached sentinel one stride
  past each, so any over-read surfaces as a phantom reason.
- **The `OrcaWhirlpoolPricing` `and`/`subtract`/`max` rows were killable**, not
  mod-2^128 equivalents: every test fixture had `reward_last_updated_timestamp`
  at 0, which makes `currentTimestamp - unsigned(lastUpdated)` and
  `currentTimestamp + unsigned(lastUpdated)` agree and makes the `max(ZERO)`
  clamp inert. Three tests (a non-zero emission window, a last-updated in the
  future, and a `u64`-max last-updated) kill all four.
- **`OrcaWhirlpoolPricing.unsigned128` was deleted rather than accepted.**
  idl-clients `6ea8877` changed the generated `DynamicTickData` to decode every
  growth-outside field with `getUInt128LE`/`readU128Array`, so the helper's
  negative branch became unreachable and the helper a pure identity. Its three
  accepted rows were unkillable because the branch is dead, *not* because the
  signed and unsigned readings are congruent — the policy ranks refactor above
  accept, and this was the refactor case. What now catches a regenerated type
  flipping signedness is `legacyAndDynamicTickArrayLayoutsValueIdentically` plus
  `aHighBitGrowthOutsideValuesIdenticallyThroughBothTickArrayLayouts`, which
  drive a `2^128 - 2^64` growth-outside value through both layouts.
- `KaminoPositions.positionReport`'s `fromSf(...).stripTrailingZeros()` was
  refactored away: `KaminoUtil.fromSf` already normalizes its quotient, so the
  second call was a no-op and its mutant could never be killed.

Two production fixes landed with this pass (owner-approved, both latent):

- `VaultTokensPosition.onchainPriceForMint`'s `KaminoReserve` arm hand-read
  `market_price_sf` with `getInt128LE` while the generated `ReserveLiquidity`
  reads that same `u128` with `getUInt128LE`; a top-bit-set price would have
  reported NEGATIVE. Now `getUInt128LE`, pinned by
  `aKaminoMarketPriceWithTheTopBitSetDecodesPositive`.
- `OrcaWhirlpoolPricing.unsigned128` deleted (above).

One hazard was pinned rather than fixed, per the port-fidelity rule:
`LoopscalePositions.positionReport` narrows a signed `strategyNavRaw` to a
`long` and hands it to `AssetMetaContext#toDecimal(long)`, whose contract is
that a long is an unsigned `u64`. A negative NAV (fees claimable exceeding every
balance) therefore values as `2^64 + nav` — an astronomic positive leaf.
The state is not constructible from a valid on-chain strategy (the on-chain
`u64` arithmetic underflows first), so
`aNegativeStrategyNavReadsAsAnUnsignedU64Amount` pins the current behaviour the
way `feeCheckpointAheadOfGlobalGrowthWrapsMod2Pow128` does. The house pattern
for impossible inputs is a downstream plausibility tripwire like
`OrcaPositions.IMPLAUSIBLE_LEAF`; adding one to Loopscale is an owner call.

### positions — `NO_COVERAGE` pay-down, 2026-07-26

The suite's whole remaining `NO_COVERAGE` population — **32 rows, gone to
zero** — was the mechanical tail this file had already triaged as fixture work:
`OrcaPositions.positionReport` (23), `MarginfiCacheImpl.accept` (7), and the
two `isEmpty` constants (`JupiterPositions`, `VaultTokensPosition`). The suite
went from **862/953 detected (58 survived, 33 no_coverage)** to
**894/953 detected (94%), 59 survived, 0 no_coverage**; the baseline is now 59
rows, every one a labelled `SURVIVED` acceptance.

- **`OrcaPositionsReportTests`** (new) — the `positionReport` stratum around the
  already-pinned `positionLeaf`/`tokenUsd` math, reusing
  `OrcaPositionsMathTests`' closed-form byte fixtures. Pinned as properties:
  the aggregate event is read from the inner-instruction slot at exactly
  `ixIndex` and exactly one slot is consumed (`ixIndex + 1` returned); an empty
  slot leaves the node total null while leaves still compute (the breakdown is
  not discarded because the on-chain aggregate is unreadable); each
  fetch-and-skip leg is exercised separately in the direction that would
  mis-report (a missing position/whirlpool account skips only its own position;
  a missing asset meta skips the position and — pinned with a deliberately
  unindexable inner-instructions array — consumes no instruction slot); the
  tick arrays resolve at addresses derived from the whirlpool ACCOUNT's owner
  (a foreign owner derives nothing), and a missing tick array skips only the
  LEAF while still consuming the slot, because the price instruction was
  emitted at assembly time where tick arrays are derived, never fetched; the
  implausible-amount tripwire aborts the whole report with the `-1` failure
  convention and no partial report survives.
- **`MarginfiCacheImplTests`** (extended) — `accept`, the websocket streaming
  consumer: a Bank-shaped, marginfi-owned account is decoded into the cache at
  the STREAMED slot (pinned by a later stale update losing against it), and
  each of the three advisory-filter re-checks — foreign owner, wrong length,
  wrong discriminator — is pinned in the ignore direction with the other two
  conditions satisfied.
- The two `isEmpty` rows are contract pins, not coverage filler: both classes
  answer a constant `false` because they must never be treated as removable by
  the dispatch chain (`VaultTokensPosition` prices the vault ATAs every cycle;
  `JupiterPositions` is a placeholder whose removal semantics would otherwise
  be decided by a default).

One newly covered survivor was accepted, as a new family:

- `# zeroness-only-counter` — `OrcaPositions.positionReport` 214's
  `++numPositions` reversed to `--numPositions`. The counter's ONLY consumer is
  the `numPositions == 0` "did any position resolve" check: with `n` resolved
  positions the unmutated count is `n` and the mutated count is `-n`, which are
  zero for exactly the same inputs, so no test can tell them apart. Kept as a
  counter rather than refactored to a boolean because it mirrors
  `priceInstruction`'s `numPositions`, which is serialized into the instruction
  and IS pinned by byte comparison. Escape: any consumer of the magnitude — the
  count joining the report or a diagnostic — makes both directions killable.

### service — SURVIVED pay-down, 2026-07-24

The seeded 109 `SURVIVED` rows were worked down to the acceptances below.
Notable kills along the way: an `IncidentNotifier` seam (formerly `PagerDutyNotifier`) injected through
`BaseNotificationService` (production default unchanged — the process-wide
singleton; tests inject a recording notifier, never touching the singleton)
converted 16 `queueResponse` removals plus the severity-gated queue routing
into assertions; JUL log capture pinned every monitor's rendered messages and
levels; the mint-metadata JSON is now pinned byte-exact (escaping mutants
survive parse-back-only checks because the decoded value is unchanged); and
lookup-table batching boundaries (30/28, free-slot top-up, smallest-table
selection ties) are pinned through recording `SPLAccountClient` proxies.

- `# fast-path guard` — the outer null-check of a double-checked memoization
  (`PriceVaultStatusHandler.httpResponse`, `KaminoReserveContextsHandler.httpResponse`,
  `KaminoVaultContextHandler.httpResponse`) or a redundant-work short-circuit
  (`KaminoNotificationServiceImpl.onMappingChange`'s severity==warning check,
  which only skips re-scanning; the escalation loop is idempotent — it can
  only set critical). Forcing the guard true routes through the in-lock
  recheck (or the idempotent re-scan) to the identical result; the memoized
  instance identity is separately pinned by `assertSame` tests, so only the
  performance fast path is unasserted.
- `# race-guard family` — the in-lock re-read of the double-checked
  memoization (`PriceVaultStatusHandler` line 35). Deterministically
  (single-threaded) the outer and inner reads always agree, so the mutated leg
  is only observable under a concurrent interleaving. Escape: a two-thread
  harness that parks one builder inside the lock while a listener invalidates.
  *(2026-07-26: that escape was taken for both Kamino handlers — their in-lock
  rechecks are now KILLED by the parked-request interleaving described in the
  2026-07-26 section below, so only the `PriceVaultStatusHandler` row remains
  in this family.)*
- `# defensive-guard` — a guard whose triggering state is unreachable in
  context: `PriceVaultStatusHandler`'s `!reasons.isEmpty()` render guard
  (`onUnsupportedVaultState` removes on empty, pinned by test, so the map
  never holds an empty reason set); `SparkleHandler.generatePNG`'s
  `|ny| > 1` / `|nx| <= 1` legs (for LENGTH=1024, |n| is at most exactly 1.0
  at index 0 — the boundary itself is killed by the edge-pixel tests, the
  always-false comparison directions are not expressible). Escape: a raster
  size or listener contract change would make these reachable.
- `# null-guard subsumed` — a null/blank guard whose guarded callee is itself
  null-safe with the identical outcome: `PriceVaultsServiceConfig` stale-vault
  duration (`ServiceConfigUtil.parseDuration(null)` returns null, which
  downstream defaults identically to the unset field), and `KaminoJson`'s
  paired `entry != null` / `json != null` chain guards (`toJson(null entry)`
  returns null, which the json guard then skips — each guard's forced-true is
  absorbed by the other).
- `# redundant-blank-guard` — `JupiterPriceApiConfig`'s `isBlank()` leg:
  `PropertiesParser.getProperty` already normalizes blank values to null and
  strips, so a non-null blank string cannot reach the check. Escape: that
  normalization moving out of `getProperty`.
- `# subsumed-return` — `PriceVaultsServiceConfig.Parser::test` propagating
  `super.test(...)`: the base parser's `test` either throws on an unknown
  field or returns true (its only return statement), so forcing the
  propagated value to true cannot change control flow.
- `# capacity-hint` — allocation sizing only (`ArrayList`/`HashMap`/
  `StringBuilder` initial capacities in the table handlers and
  `KaminoVaultContextHandler.createHandler`): changes what is reserved, never
  what is computed.
- `# same-result routing` — both branches produce the identical observable:
  `ExtendVaultTableHandler` line 95's `<` boundary (at `numAccounts ==
  extendTo` the multi-transaction path degenerates to the same single
  extend-all transaction — traced for both the `available < 30` and
  `available >= 30` shapes); `GlamVaultTableHandler`'s `numVaults == 0` early
  return (an empty state list funnels to the identical `HttpResponse.EMPTY`
  at the `numTables == 0` check); `KaminoReserveContextsHandler.onReserveChange`'s
  same-market leg (removing a reserve from its own market map and re-putting
  it reproduces the same entries, byte-identical JSON).
- `# fp-equivalent` — `SparkleHandler.generatePNG` line 94's `% 360` replaced
  with `* 360`: the modulus only strips whole turns before `toRadians`, and
  the downstream `(pixelAngle + angleRadTau) % TAU` strips them again, so the
  variants agree in real arithmetic; the difference is double rounding
  (~1e-12 rad against a 1/255 alpha quantum). Killable only by hunting a key
  whose drift crosses a rounding boundary — a flaky harness, worse than
  recorded debt.
- `# compression-tuning` — `SparkleHandler.httpResponse`'s
  `canWriteCompressed` branch and `setCompressionQuality(0.0f)`: PNG
  compression settings change encoded size/speed, never the decoded raster,
  which is what the response contract pins (the round-trip decode test).
- `# error-funnel` — `SparklePathHandler.httpResponse` line 17's
  `to - PUBLIC_KEY_LENGTH` search origin: the searched window can only
  contain a '/' when the candidate key is shorter than any valid 32-byte
  base58 encoding, and every such path throws in `fromBase58Encoded` into
  the catch that answers the identical 400 in both variants.
- `# invariant-guard` — `HistoricalGlamValueHandlerImpl$CacheEntry.addHourlyRecord`'s
  inner `isAfter(lastDaily)`: `addHourlyRecord` appends every accepted record
  to both deques, so daily's tail always equals hourly's tail and the inner
  check mirrors the outer one. Escape: a DB-loaded cache (`readResults`)
  whose daily tail is newer than an incoming record.

### service — NO_COVERAGE pay-down, 2026-07-25

The two largest `NO_COVERAGE` clusters in the repo were worked with the
generated test support (`hardening { generateTestSupport = true }`, package
`software.sava.hardening.support`, outside every suite's `targetClasses`):

**`systems.glam.services.logging.BaseJulLogger` — 59 → 0.** The single biggest
cluster, and it had *no* test at all. New `BaseJulLoggerTests` drives a nested
`Subject` subclass through a `JulRecorder` and asserts the published
`LogRecord`: rendered message text, level, thrown **identity**, and the source
class/method the record is attributed to. Notable properties pinned rather than
merely covered:

- **`resolveCaller` reports the application frame, not the facade's.** One test
  logs through two facade frames (`emitVia` → `emit` → `log`) and asserts the
  record still names the *test* method — that is the `dropWhile` on
  `className`, whose forced-`false` direction would attribute every record to
  the facade. Its forced-`true` direction, and the `walk` lambda's null return,
  are killed by the same assertion from the other side.
- **The fallback caller is reachable, and is now covered.** A `Thread` subclass
  nested *inside* `Subject` (so its class name starts with `className`) makes
  every frame below `log` a facade frame; the walk then finds nothing and
  `resolveCaller` answers `Caller(jul.getName(), "log")`. That is the only way
  to reach the `stackFrame == null` arm, and it turns line 51's null branch and
  its `NullReturnVals` siblings from `NO_COVERAGE` into kills.
- **The renderer's edge cases are hand-derived**, one assertion per shape:
  a leading placeholder (`{}!` — pins `indexOf('{') < 0` against `<= 0`),
  surplus placeholders (`{} {} {}` with one value leaves the extras literal),
  surplus values, `\{}` escaping, a backslash before a non-brace, a *trailing*
  backslash, a lone `}`, `{x}`, and a trailing `{`. Every array shape
  (`Object[]`/`int[]`/`long[]`/`double[]`/`float[]`/`boolean[]`/`byte[]`/
  `short[]`/`char[]`) is rendered element-wise in one message, and nested object
  arrays are asserted to render deeply.
- **`logFormat`'s three-legged guard is split by leg**: a null message, a null
  value array, and an *empty* value array each get their own test. The empty
  case is the interesting one — it short-circuits to the **raw** message, so
  `\{}` stays escaped, which is what distinguishes it from the formatted path.

One redundant condition was **refactored away rather than accepted**:
`formatPlaceholders`' escape guard read `i + 2 <= len && i + 1 < len && …`, and
those two bounds are the identical condition. Five of its mutants were
unkillable purely because each leg subsumed the other; with the first leg
removed, every mutant on the surviving bound is killable (and is killed) by the
trailing-backslash test.

**`…rest.handlers.glam.HistoricalGlamValueHandlerImpl` — 43 → 5.** New
`HistoricalGlamValueHandlerQueryTests` drives the handler over layered
`java.lang.reflect.Proxy` JDBC fakes (`DataSource` → `Connection` →
`PreparedStatement` → `ResultSet`, the value-returning inverse of the recording
statement proxy) plus a direct-dispatch `ExecutorService`, which collapses the
handler's two concurrent queries into a deterministic single-threaded sequence.
Pinned: both selects' parameter bindings **by exact index** (including the
168-hour hourly window, asserted against the hour boundary either side of the
call), the assembled JSON of the first request byte-for-byte, the memoized
response *identity* of every later request, the two independent 500 paths (an
hourly `SQLException` inline and a daily one through `CompletableFuture.join`),
the rendered `logSqlException` diagnostic captured with `JulRecorder`, and — the
two that are easy to get wrong — a non-SQL `RuntimeException` from the daily
query **escaping** rather than being laundered into a 500, and an `Error`
staying wrapped in its `CompletionException` because it is not a
`RuntimeException`.

The double-checked lock is covered on both sides. The **in-lock** recheck was
`NO_COVERAGE`, which is never acceptable as an equivalence, so it was covered
with a forced interleaving rather than argued away: the builder thread is held
inside its hourly query (which runs inside the lock), the second request is
released only once it is *observed* `BLOCKED` inside `httpResponse` (via
`ConcurrencyHarness.awaitTrue` on thread state **and** stack frame — not a
sleep), and only then is the builder let go. That kills both the in-lock
`NullReturnVals` and the in-lock guard's forced-`false` direction; what remains
is the outer fast-path check, which is the pre-existing `# fast-path guard`
shape.

`run()`'s hourly query pass was **extracted verbatim** into a package-private
`readHourlyUpdates(Timestamp)` — the same "extract the construction, not the
emission" move already used for `JupiterPriceFetcherImpl.mintsInUse` and
`GlamVaultExecutorImpl.submitPricing`, and for the same reason: the loop opens
with a `Thread.sleep` to the next hour boundary, so nothing below it is
reachable without a real wait. The extracted unit is now pinned — rows are
routed to the cache entry of the vault they name, the record columns are read
from offset 2 (after the state key), and rows naming a vault no request has
cached are **dropped** rather than creating an entry.

New families (the rows themselves carry these labels):

- `# formatter-fast-path` — `BaseJulLogger.formatPlaceholders` 57's
  `message.indexOf('{') < 0` early return forced *false*: the substitution loop
  copies a brace-free message character by character into the identical string,
  so only a `StringBuilder` allocation differs. The forced-*true* direction (no
  substitution ever) and the boundary shift (`<= 0`, which would skip a message
  whose first character is `{`) are both killed by test. Escape: any transform
  applied to characters outside a placeholder.
- `# null-throwable-overload` — `BaseJulLogger.log` 26's `t == null` forced
  *false*, i.e. always calling `logp(level, class, method, message, t)`.
  `Logger.logp`'s throwable overload does `LogRecord::setThrown` with the value
  it is given, so passing a null throwable produces a record indistinguishable
  from the no-throwable overload's. The forced-*true* direction — which would
  silently drop every exception the service logs — is killed by
  `aThrowableIsAttachedToTheRecordByIdentity`, which asserts `assertSame` on the
  record's `getThrown()`.
- `# switch-default-identity` — `BaseJulLogger.stringify` 94's `!cls.isArray()`
  forced *false*: a non-array value falls through the pattern switch (it matches
  none of the nine array patterns) to `default -> v.toString()`, which is the
  branch the guard was short-circuiting to. The forced-*true* direction, which
  would render every array as its JVM identity string, is killed by
  `everyArrayShapeRendersElementWise`.
- `# always-positive-delay` — `HistoricalGlamValueHandlerImpl.run` 76's
  `sleepMillis > 0` guard, both the boundary shift (`>=`) and the forced-*true*
  direction. `sleepUntil` is `truncate(now, HOURS) + 1h + 5s`, which is strictly
  after `now` for every `now`, so `Duration.between(now, sleepUntil).toMillis()`
  is at least 5000 and the guard is always taken — the mutants are the branch
  the code takes anyway. The forced-*false* direction is a real behaviour change
  (the loop would spin without pacing) and **is** killed: the interrupt test's
  data source throws if the loop reaches the database before its first wait
  completes. Escape: the `// TODO` at the top of `run()` — if the writing
  service pushes records instead, this pacing disappears with the guard.

Joining existing families: `BaseJulLogger.formatPlaceholders` 61's
`new StringBuilder(len << 2)` sizing joins `# capacity-hint`, and
`HistoricalGlamValueHandlerImpl.httpResponse` 251's outer memoization check
joins `# fast-path guard` alongside the three handlers already listed there —
forcing it onto the slow path routes through the in-lock recheck to the
identical memoized instance, which the concurrency test asserts by identity.

Still `# untriaged` — **coverage debt, not equivalence**. Eight rows remain, all
in `run()`, all behind the wall-clock pacing that the determinism rule forbids a
test from waiting out (`AGENTS.md`: randomized tests never sleep, and PIT
re-runs the suite per mutant):

- 80 ×2 — the `cacheMap.isEmpty()` skip, and 87 — the `readHourlyUpdates(…)`
  call site. Only reachable after a full hour's sleep has elapsed; the *body* of
  87 is now fully covered through the extracted method, so what is left
  uncovered is the wiring of the call, not the work it does.
- 90 ×2 — the retry diagnostic (`++failureCount` and the `System.Logger::log`
  call) and 94 ×2 — the capped backoff (`Math.min(failureCount, 21) * 1000` and
  its `Thread.sleep`). Reaching these needs the hour wait *and* a failing query,
  and observing the backoff needs a real multi-second sleep. **Not** extracted
  the way `readHourlyUpdates` was: the retry loop's whole content is the sleep,
  so extracting it would move the untestable code rather than isolate a testable
  unit.
- 101 — the broad `catch (RuntimeException)` diagnostic at the very bottom of
  the loop. Same gate: nothing inside the loop body runs without the hour wait.

Escape for all of them, and for the run-loop rows that were converted: a clock
seam. `ManualScheduledExecutor` (already generated) would cover every one of
these deterministically, but `run()` calls `Thread.sleep` directly, and swapping
its pacing for an injected scheduler is a production redesign, not a test
change — an owner call. The interrupt path (`Thread.currentThread().interrupt()`
on shutdown) *is* covered, by interrupting before entering the loop so the very
first `Thread.sleep` throws.

Note for future readers: `BaseJulLogger` has **no subclass anywhere in this
repository** and its package is not exported by `module-info.java`, so it is
currently unreachable dead code. It is now fully specified by tests rather than
deleted; deleting it would remove the class and its tests together and is an
owner call.

### service — `NO_COVERAGE` pay-down, 2026-07-26

The mechanical tail outside the declined wiring and lookup-table handlers was
worked off: **44 `NO_COVERAGE` rows converted, and 2 `SURVIVED` acceptances
became kills as a side effect**, taking the suite from
**37 survived / 209 no_coverage (246 baseline rows)** to
**35 survived / 165 no_coverage (200 rows)**. Every remaining `NO_COVERAGE` row
is one already argued in prose: the three wiring classes (116), the deprecated
lookup-table handlers (41), and `HistoricalGlamValueHandlerImpl`'s clock-gated
run loop (8). Zero `SURVIVED` rows are untriaged.

- **`MintMetadataHandlerTests`** (new) — the RPC-backed mint-metadata query
  path, 24 rows across `MintMetadataHandler` and its two parameter fronts plus
  `GlamVaultMintMetaData.createCache`. The `RpcCaller` fake is the
  `MarginfiCacheFetchTests` shape (a real record over one Proxy `BalancedItem`;
  `courteousGet` blocks on the completed future, so no executor). Pinned:
  a cached mint serves its exact bytes with the RPC untouched; a cache miss
  fetches exactly the queried key once and dispatches — missing account and
  null-data account each answer the exact 400 body, a foreign owner answers the
  exact 400 body, a Token-2022 mint (the TLV fixture from
  `GlamVaultMintMetaDataTests`) serves its exact rendered JSON and the second
  request is answered from the cache the update populated, and a mint with no
  TokenMetadata extension answers `{}`; a `RuntimeException` is a 500 carrying
  the exception's message, logged at ERROR with the thrown attached by identity
  (asserted through `JulRecorder`, so the `logger.log` removal is a kill, not a
  `# log-emission` acceptance); both handlers' malformed-key 400s.
- **`KaminoNotificationServiceTests`** (extended) — the scope-account lifecycle
  events (`onScopeAccountDeleted`, `onNewScopeConfiguration`, 4 rows): both
  funnel through `onScopeContext`, asserted as an INFO event whose custom
  details dump every configuration field (slot at -1 pinning the unsigned
  rendering), a tracked response queued by instance identity, and the INFO log
  line naming the event with the config JSON.
- **The in-lock leg of both Kamino handlers' double-checked memoization** — the
  `# race-guard family` escape, taken: the handlers' `lock` was already
  package-private for tests, and `ReentrantLock` is reentrant, so the forced
  interleaving needs no latch inside the builder. The test invalidates, holds
  the lock, observes a second request parked `WAITING` inside `httpResponse`
  (state AND stack frame, per `ConcurrencyHarness`), rebuilds REENTRANTLY on
  its own thread, releases, and asserts the parked request answers the
  memoized instance. That kills the two `NO_COVERAGE` in-lock returns AND the
  two accepted in-lock recheck rows (`# race-guard family` now holds only
  `PriceVaultStatusHandler`).
- **Factory and delegation one-liners**, each asserted as a property rather
  than `assertNotNull` alone: `GlobalConfigNotificationService.createService`
  must SUBSCRIBE the created service to the global-config cache (asserted by
  instance identity — without it no config change reaches the monitor); the
  other three monitor factories pin `key() == PublicKey.NONE` (also covering
  `BaseNotificationService.key`); `HistoricalGlamValueHandler.createHandler`
  answers a request exactly like the directly constructed handler over the
  same JDBC fakes; `VaultStatsServiceContext.createContext` and
  `ServerContext.serviceContext` hand back the exact injected integration
  context; `VaultStatsRestServerImpl` submits every runnable handler in order
  and reports their count; `SparkleQueryHandler` parses `key=`, defaults
  `format=` to svg (byte-compared against a direct render), and answers the
  exact 400 on a malformed key; `JupiterPriceApiConfig.createClient` builds a
  client against the default Jupiter endpoint.

These are deliberately thin tests for thin code: each asserts the one
behavioural fact the class contributes (a subscription, a delegation identity,
a dispatch), which is what distinguishes them from the declined wiring classes,
where no such fact exists apart from the construction itself.
