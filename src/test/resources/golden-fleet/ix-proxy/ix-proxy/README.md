# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`. Full policy — the three legal
outcomes for a new survivor, determinism requirements, targeting rules —
lives in sava-build's `HARDENING.md`.

Never refresh with `-PupdateMutationBaseline` just to make the build pass:
kill the mutant, refactor it out of existence, or record its equivalence
reason below. Pure line drift (every new row a same-status shift of a
stale one, populations unchanged) passes on its own with a notice —
refresh at a convenient moment. Anything else fails with a per-row
classification (`shifted` vs `newly covered` vs unexplained) and a churn
tally: a newly covered row is triage, not churn, and identical rows are
sibling mutants of one compound condition — the comparison is a
multiset, so never hand-dedupe the CSV.

A baseline row may carry a trailing `# note` — `# untriaged` is the
conventional label for seeded debt. Notes are preserved across
`-PupdateMutationBaseline` / `-PunionMutationBaseline` rewrites, and the
verify task counts rows marked `# untriaged` so the debt stays a printed
number, not prose.

## Untriaged debt

A first baseline seeded from the pre-existing survivor population is triage
debt made explicit, not acceptance. List it here until each key is killed,
refactored away, or moved below with a reason.

- **Seeded 2026-07-29** (`pitestIxProxy -PupdateMutationBaseline`): 328 rows —
  148 `SURVIVED`, 180 `NO_COVERAGE` — against 27 killed of 355 generated.
  The `NO_COVERAGE` population is dominated by `ConfigLoader` (never
  exercised by a test) and the error/edge branches of the parsers
  (`ProgramMapConfig`, `IxMapConfig`, `DynamicAccountConfig`,
  `IndexedAccountMetaRecord`); the `SURVIVED` population by the mapping
  runtime (`BaseIxProxy`, `PayerIxProxy`, proxy lookup paths), where
  `IxMapperTest` executes the code but asserts too little of it. Use
  `pitestIxProxyDebt` to rank the remainder by class when picking the next
  cluster.
- **Refreshed 2026-07-29** after migrating `IxMapConfig.Parser` to the
  json-iterator `FieldMatcher`/`readByteArray`/`readIntArray` APIs: 284 rows
  (104 `SURVIVED`, 180 `NO_COVERAGE`) of 311 generated — the refactor
  deleted the hand-rolled two-pass `mark()`/`reset()` array loops and their
  44 mutants outright; the 7 rows in `Parser.create` carried across as pure
  line shifts.
- **Worked 2026-07-29** from 27/311 (8%) to 289/311 (92%): direct
  parse-and-assert suites for every config parser, transaction/table
  plumbing tests for `ProgramProxyMap`, `createProxy` validation and proxy
  behaviour tests for `IxMapConfig`/`PayerIxProxy`/`IdentityIxProxy`/
  `IxProxyRecord`, lookup tests for both program-proxy shapes, and a
  scripted `StubHttpClient` driving `ConfigLoader`'s remote worker without a
  socket. Baseline 284 → 22 rows: 12 accepted with reasons (below), 10
  `# untriaged`.

- **Worked 2026-07-31**: the Worker retry path, previously the remaining
  `# untriaged` debt (10 `NO_COVERAGE` rows), was covered by adding a
  `ConfigLoader.Sleeper` seam (package-private; production passes
  `Thread::sleep`) and driving `Worker` directly on the test thread with a
  scripted flaky `StubHttpClient` — backoff schedule, cap, retry bound,
  and the InterruptedException handler are all asserted without real waits.
  A truncated-instruction-data guard was also added to
  `BaseIxProxy.validateMapping` (previously an `ArrayIndexOutOfBoundsException`
  escaped from `Arrays.equals`). Baseline 22 → 13 rows: the 12 accepted
  equivalents carried over; the worker's retry log call is newly covered and
  accepted as `# log-only` (below). No `# untriaged` rows remain.

## Timed-out mutants (audited set)

`ConfigLoader$Worker.get` line 138, `MathMutator` and
`RemoveConditionalMutator_ORDER_ELSE` (seeded 2026-07-31): both mutate the
retry bound `++errorCount > maxRetries` — the increment into a decrement,
the comparison into always-false — so the IOException retry loop never
exits. The covering tests inject a non-blocking `Sleeper`, so the mutant
spins the loop indefinitely instead of sleeping, and PIT can only detect it
as a timeout. The cause is structural (an unbounded loop), not a slow test:
any mutation that removes the loop's only exit lands here.

## Mutator-set trials

`STRONGER` is the default. `EXPERIMENTAL_NAKED_RECEIVER` was trialed
2026-07-29 (`-PtrialMutators=STRONGER,EXPERIMENTAL_NAKED_RECEIVER`):
355 generated without → 355 with, zero fires (re-trialed after the
FieldMatcher migration the same day: 311 → 311) — this code returns records,
arrays and fresh instructions, not fluent receivers, so it stays off.
Re-trial if builder-style code is introduced. No mutated class performs
`BigInteger`/`BigDecimal` arithmetic, so `EXPERIMENTAL_BIG_INTEGER` was not
trialed.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

- `# defensive-null-tables` (6 rows, `ProgramProxyMap` lines 60/89/127):
  both operand directions of `tables == null || tables.length == 0` on the
  `== null` operand. sava's `TransactionRecord` never returns a null
  `tableAccountMetas()` — it uses the `NO_TABLES` empty-array constant — so
  the null operand is defensive against foreign `Transaction`
  implementations. Killing it would need a hand-rolled fake `Transaction`
  returning null, a test that restates the implementation rather than a
  property. The `length == 0` operands at the same coordinates are killed.
- `# delegation-equivalent` (1 row, `ProgramProxyMap.mapTransactionWithTables`
  line 114): removing the `numNewTables == 1` fast path routes a single
  added table through the multi-table branch, and sava's transaction factory
  normalizes a single-entry meta array back to the single-table form — the
  resulting transaction is identical; only the internal meta wrapper's
  identity differs, which no property-level assertion should pin.
- `# single-variant-guard` (2 rows, `IxMapConfig.createProxy` line 58): the
  false direction of both operands of
  `proxyType != null && proxyType != ProxyType.PAYER`. `ProxyType` has a
  single constant, so "declared type is not PAYER" is unsatisfiable and the
  guard cannot fire — the mutants are equivalent until a second proxy type
  exists. **Re-triage when a `ProxyType` constant is added**; both true
  directions are killed by the payer tests.
- `# invariant-guard` (1 row, `BaseIxProxy.validateMapping` line 29): the
  false direction of `cpiDiscriminatorBytes.length != cpiDiscriminator.length()`
  — `cpiDiscriminatorBytes` is `cpiDiscriminator.data()` captured in the
  constructor, so the two lengths agree by construction of every
  `Discriminator` implementation; the guard exists to catch a broken foreign
  `Discriminator` and cannot fire in-harness.
- `# log-only` (1 row, `ConfigLoader$Worker.get` line 142): removing the
  `System.Logger::log` call that announces a retry. The retry's observable
  behaviour — the backoff delays, the bound, the eventual result or rethrow —
  is fully asserted by `ConfigLoaderTests`; the log line is operator
  diagnostics with no functional effect, and pinning it would mean asserting
  on a logging backend, a test that restates the implementation.
- `# empty-copy-equivalent` (2 rows, `IxProxyRecord.mapInstructionUnchecked`
  line 80): `len > 0` guards a payload `System.arraycopy`; at `len == 0`
  (instruction data is exactly the discriminator) the copy is a zero-length
  no-op, so both the boundary flip and the forced-true direction are
  behaviourally identical. `len < 0` is unreachable — the proxy
  discriminator write into the undersized target array fails first.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
