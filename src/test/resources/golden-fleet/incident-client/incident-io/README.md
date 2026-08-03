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

- None. The 2026-07-24 initial seed (`request` 6, `response` 1, `config` 4,
  `adapter` 19) was triaged 2026-07-24: 26 killed with new tests
  (comma-placement and empty-collection exact-body tests, values-array parser
  probes, sparse-config builder preservation, blank-alert-key UUID
  generation, exception accessors/canBeRetried boundaries and parse-failure
  messages) or removed by refactor (dead null-guards in
  `CreateIncidentRequestRecord.body` — the builder normalizes null
  collections to empty; dead `bearerToken` guard in
  `IncidentIoConfig.createClientBuilder` — the parser validates it present),
  4 accepted below. `request` and `response` are clean.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

- `# copy-on-write` (adapter: `IncidentIoRequestException$Parser.create:102`
  ×3) — `errors.size() > 1 ? unmodifiableList : errors` routing: both
  branches return a list with identical content (size ≤ 1 is already an
  immutable `List.of`), so the boundary/order mutants only change which
  content-equal instance escapes. Equal but not identical; killable only by
  asserting mutability the API does not promise.
- `# always-true-delegate` (config: `IncidentIoConfig$Parser.test:85`) —
  `return super.test(...)` where the superclass either returns true or
  throws on unknown fields; the mutated constant-true return preserves the
  call and its side effects, so no input can distinguish it. Escape hatch:
  a superclass path that returns false would make the propagation
  observable.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
