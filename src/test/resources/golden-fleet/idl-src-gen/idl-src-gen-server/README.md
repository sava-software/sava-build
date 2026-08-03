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

Seeded 2026-07-23 (`server` suite: 118 mutants, 46 killed, 72 accepted);
every row carries `# untriaged` (labeled 2026-07-24):
- `Entrypoint` (44 `NO_COVERAGE`) — server bootstrap: property parsing, RPC
  client construction, Jetty wiring. Needs a harness that starts the server
  against a stubbed RPC layer before unit-level coverage is meaningful.
- `IDLClient` + `IDLClient$IDLResponse` — **closed 2026-07-24** (0 rows).
  The three seeded `SURVIVED` rows fell first: the dropped
  `transaction.sign(payer)` passed the loopback gate because the verifier
  never checks signatures — `paymentHeaderTransactionIsSignedByThePayer`
  now asserts exactly one non-zero signature block on the wire — and the
  compute-budget `putInt32LE`/`putInt64LE` writes were unobserved until
  `computeBudgetInstructionsEncodeTheirValues` pinned the exact little-endian
  data bytes (the helpers went package-private for it). The 15 `NO_COVERAGE`
  rows followed via `IDLClientFetchTests`, a JDK-loopback harness driving
  `fetchIDLs` end-to-end (open pass-through, 402 → pay → retry with
  settlement round-trip, no-requirements and empty-input failures); the
  since-killed rows were dropped with `-PpruneMutationBaseline`.
- `IDLHandler` (10 `SURVIVED`, full line coverage) — response shaping the
  13 handler tests reach but do not yet pin exactly; the next triage target,
  since every survivor here is a judgment call away from a kill or an
  acceptance.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

`IDLHandler` triaged 2026-07-24 — its ten seeded survivors split 4 kills /
6 acceptances. The kills were the copy-on-write doctrine's escape half in
miniature: `buildResponse`'s `length > 0` guards mutated to treat a
*zero-length* decoded IDL as present, emitting malformed JSON
(`treatsEmptyDecodedIdlPayloadsAsAbsent` pins both the anchor and metadata
sides). The acceptances:

- **Redundant fast-path guards** (`# redundant-guard`) — the skipped guard
  reaches the identical observable through the code behind it:
  `decodeMetadataIdl:129` pair (null/empty data proceeds into
  `Metadata.read` inside the `catch (RuntimeException) -> null` funnel,
  returning the same null the guard returns) and `idlResponse:117`
  (a null `idlJson` put into the map is filtered by `buildResponse`'s own
  null check, so skipping this one is invisible).
- **Error funnel** (`# error-funnel`) — `decodeMetadataIdl:134`: forcing the
  `metadata == null` arm off sends null into `idlJSON`, which NPEs into the
  same catch and returns the identical null.
- **Defensive code unreachable in context** (`# defense-in-depth`) —
  `idlResponse:115`: every fetched account's address was derived into
  exactly one of the two lookup maps and anchor hits `continue` first, so
  `metadataProgram` cannot be null here; the guard defends against an RPC
  returning accounts nobody requested.
- **Allocation-only arithmetic** (`# allocation-only`) — `idlResponse:81`
  (`ArrayList` capacity hint).

Shrinking a baseline is always an improvement; growing one requires a reason
here.
