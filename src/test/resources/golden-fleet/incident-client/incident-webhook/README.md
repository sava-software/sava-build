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

- None. The module was hardened at creation (2026-07-26): the initial `format`
  survivors (2) were killed with a null-`customDetails` custom-alert test, and
  the initial `config` survivors were killed with wire-level wiring tests
  (headers/bearer token observable only on requests) or removed by refactor
  (the `headers.size() > 1` copy-on-write conditional — header order is
  contractual, so every size takes the order-preserving copy; the `Map.copyOf`
  branch's kill was hash-salt-dependent, i.e. flaky, and the conditional was
  only an allocation micro-optimization). The initial `adapter` survivors were
  killed with a headers-plus-`extendRequest` composition wire test, a
  300-status boundary test, toString content assertions, and exception
  accessor/null-body tests, or removed by refactor (the `body.length == 0`
  arms in the empty-body reads — `new String(byte[0])` is already `""`, so the
  length check was a content-equal branch); 3 accepted below (plus 1 in
  `config`). The Telegram expansion (2026-07-26) added one accepted `format`
  row (truncation boundary) and killed its factory's blank-chat-id direction
  by pinning the factory's error message against the record's own validation.

## Triaged equivalent mutants (accepted with reasons)

Group by the principle that makes them equivalent (see the recurring families
in HARDENING.md); the baseline CSVs carry the exact keys.

- `# boundary-identity` (format: `TelegramTextFormat.render:36`) — the
  `text.length() > MAX_TEXT_LENGTH` truncation guard: the `>=` boundary mutant
  differs only for text of exactly the limit length, where
  `substring(0, MAX_TEXT_LENGTH)` returns identical content — both branches
  produce the same string, so no input can distinguish them. The at-limit and
  over-limit behaviours are pinned by exact-output tests.
- `# always-true-delegate` (config: `WebhookConfig$Parser.test:183`) —
  `return super.test(...)` where the superclass either returns true or throws
  on unknown fields; the mutated constant-true return preserves the call and
  its side effects, so no input can distinguish it. Escape hatch: a superclass
  path that returns false would make the propagation observable.
- `# empty-noop-guard` (adapter: `WebhookClient$Builder.createClient:46`) —
  `if (!headers.isEmpty())` guards building the header-applying request
  wrapper; with no headers the wrapper iterates an empty map and returns the
  builder unchanged, so forcing the guard true only swaps a null
  `extendRequest` (identity in `JsonHttpClient`) for an observable no-op.
- `# unreachable-1xx` (adapter: `WebhookClientImpl.lambda$static$0:23`) —
  the `statusCode < 200` arm of the success check. Only a *final* 1xx status
  distinguishes this direction, and `java.net.http` never surfaces one:
  interim responses are consumed by the protocol layer, so a raw-socket
  harness would hang or flap. A flaky harness is worse than recorded debt;
  the `>= 300` arm is pinned by the 300/400/503 wire tests.
- `# defensive-null-guard` (adapter: `WebhookClientImpl.lambda$static$0:27`) —
  `body == null ? "" : new String(body, UTF_8)`: `readBody` over the
  `ofInputStream` body handler never yields null (`readAllBytes` returns an
  empty array), so the mutated always-decode arm can only NPE on an input the
  transport cannot produce. The guard stays for the `readBody` contract,
  which declares null a legal return.

Shrinking a baseline is always an improvement; growing one requires a reason
here.
