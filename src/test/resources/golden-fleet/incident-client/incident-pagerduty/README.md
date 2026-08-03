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

- None. The 2026-07-24 initial seed (`payload` 47, `config` 4, `adapter` 4,
  `response` clean) was triaged 2026-07-24: 30 killed with new tests
  (single-entry prototype-copy immutability, chained link/image/custom-detail
  adds, null-collection prototypes, typed custom-detail overloads, timestamp
  and dedup-key defaults, blank-vs-null branches of every optional field,
  sparse-config builder preservation, adapter delegation) or removed by
  refactor (dead null-guard in `customDetailsObject` — both constructors
  initialize the map), 20 accepted below. `response` and `adapter` are clean.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

- `# copy-on-write` (payload: both builders' `create` size ternaries ×12,
  `customDetailsObject:145`, `link:177`, `image:189`) — single-vs-multi
  routing in the copy-on-write collections. The `create` wrappers only ever
  wrap collections that are already immutable at size ≤ 1 (`List.of` /
  `Map.copyOf`), so `unmodifiable*` vs as-is is indistinguishable; the
  size==1 setter branches only re-copy content-equal maps/lists. The
  mutable-escape direction (`ORDER_ELSE`, multi-entry as-is) is killed by
  the immutability tests — these are the content-equal siblings.
- `# defensive-copy` (payload: prototype constructor `<init>:82/88/94`) —
  the `isEmpty()` branch of each prototype-copy ternary: an empty non-null
  prototype collection is routed to the size-check chain and lands on
  `Map.copyOf`/`List.copyOf` of an empty collection — content-equal to the
  `Map.of()`/`List.of()` the guard returns. Equal but not identical.
- `# truncation-boundary` (payload: `summary:122`) — `length() > 1_024`
  mutated to `>=`: at exactly 1024 chars `substring(0, 1024)` returns the
  same instance (`summaryExactly1024IsNotCopied` pins the identity), so the
  boundary flip is unobservable by construction.
- `# status-boundary` (adapter: `PagerDutyEventClientImpl` response parser,
  the `code < 200` arm of the success gate) — the forced-true direction is
  observable only with a final HTTP status below 200, which
  `java.net.http.HttpClient` cannot deliver: 1xx interim responses are
  consumed inside the client, and `jdk.httpserver` refuses to emit them as
  final. Unreachable in-harness. Escape hatch: a raw socket speaking
  HTTP/1.1 by hand (sava-build's `LoopbackHttpServer`) could write a
  literal sub-200 final status line, at the cost of the client blocking
  until its request timeout.
- `# always-true-delegate` (config: `PagerDutyConfig$Parser.test:99`) —
  `return super.test(...)` where the superclass either returns true or
  throws on unknown fields; the constant-true mutant preserves the call and
  its side effects. Mirrors the same acceptance in incident-io.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
