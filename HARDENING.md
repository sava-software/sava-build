# Hardening process

The quality process for repos applying `software.sava.build.feature.hardening`.
The installed plugin is the authority for exact task names, options, formats,
and refusal behavior; run `./gradlew hardeningHelp` against the version in use.
This document owns human policy, interpretation, and safety rules. The
repository [README](README.md) owns setup and the `sava-build` release procedure,
and [HARDENING_CASEBOOK.md](HARDENING_CASEBOOK.md) is historical evidence rather
than current instructions. Consumer notes and `config/pitest/README.md` contain
only repository-specific ownership, measurements, reasons, and provenance—not
copied plugin mechanics. A consumer `AGENTS.md` carries the exact generated,
digest-pinned agent template below plus repository-specific facts; it must not
grow a second, independently maintained account of plugin behavior.

Every rule here was earned from an observed incident; the incidents live in
`HARDENING_CASEBOOK.md`, cited as *(casebook: entry)*. Read an entry before
arguing with its rule — the counter-argument has usually been tried.

## This work is first-party and defensive

The repos applying this process are libraries, services, and applications their
maintainers own and release or deploy. Hardening exists to find and fix our own
defects before they reach users: mutation testing, fuzzing, crash triage, and the
analysis around them all target code we wrote, in our own repository, and their
output is tests and fixes.

Some of the vocabulary overlaps with offensive tooling — fuzzing a parser,
minimizing a crash, reasoning about malformed input from an untrusted peer,
writing a harness that deliberately feeds a library garbage. That overlap is
inherent to testing input-handling code, not a signal about intent. Two rules
keep the description accurate:

- **State the purpose plainly** when the work would otherwise read as
  context-free tooling: whose code, why, and what the artifact is. A harness
  that exists to find bugs in this library's own parser should say so at the
  top of the file, as the fuzz harnesses here already do.
- **Do not euphemize to compensate.** Quote function names, error strings,
  and bug classes exactly as they are. Softened description is worse than
  plain description: a reader who cannot tell what the code does is the
  actual failure, and vagueness is what makes defensive work look evasive.

## Lifecycle

Verification is tiered by cost, and the tier is chosen by what the change can
affect — not by habit in either direction:

| When | Command | What it proves |
|---|---|---|
| Inner loop | the module's `test` (or `--tests` for the touched classes) | The change works. |
| Before handing off a change | the `pitest<Suite>`(s) whose mutated code the change can reach | No new unkilled mutants where the change lives. |
| Before a release | `hardeningCertify` on every module; an explicit local `fuzzAll -PmaxFuzzTime=<seconds> -PmaxParallelFuzzTargets=<count>` campaign when fuzz targets exist; JMH A/B vs the previous release where the project has a benchmarked performance contract | Every mutation result was freshly observed and provenance-bound; nothing regressed anywhere; configured fuzz boundaries did not crash; applicable performance contracts did not regress. |

A release command without provenance is not durable evidence. Record the repository
commit and clean-tree state, exact selectors and options, exit result, retained-log
digest, and the plugin binary actually loaded by every consumer. An explicit local run
is valid; it needs neither a scheduled workflow nor an arbitrary soak window. A receipt
must bind the complete expected roster, refuse a stale shared plugin binary, and say
which recorded checkouts it revalidated. Keep evidence outside the tree it certifies
*(casebook: the canary that skipped two consumers)*. Releasing `sava-build` itself uses
the already-reviewed local adoption passes rather than requiring a duplicate full-fleet
campaign; its owner-attestation and Release Please mechanics live only in the README's
[Local adoption and release attestation](README.md#local-adoption-and-release-attestation)
section.

`qualityGate` = `test` + every registered `pitest<Suite>`, serialized, each
finalized by its baseline verification. `hardeningCertify` is the release form:
it automatically disables mutation history, rejects scoped and record-changing
flags before PIT starts, makes timeout drift and whole-production ownership strict,
requires a provenance-bound report for every suite, and writes
`build/hardening/pitest-certification.tsv`. Each suite row binds not only the report,
compiled code, source, configuration, PIT tool classpath, and loaded plugin binary, but
also the accepted baseline, audited timeout membership, recorded PIT-version and
mutation-toolchain sidecars, and the suite's triage README that decided whether the
observation was acceptable. The README is deliberately an exact whole-file input for
every suite in that project: legacy unlabeled rows, shared arguments, and cross-section
prose make a generic per-suite Markdown projection unsound. Any README edit therefore
invalidates the existing project receipts; finish even prose-only cleanup before the
final certification. A future narrower boundary requires an explicit versioned anchor
schema rather than inference from today’s free-form document. Its cost
scales with the repo's total
mutant population, not with the size of the diff — so running it per change
spends minutes re-learning results the change could not have moved. A suite
produces new information only when the change can alter code it mutates or
tests that cover that code.

`./gradlew clean hardeningCertify` is a supported cold proof: certification preflight is
ordered after `clean`, then fingerprints the sources and classes produced by the current
task graph. It is useful when validating a new plugin's evidence plumbing, but it is not
an extra release soak requirement; ordinary `hardeningCertify` is already a fresh PIT
observation.

Certification receipts are deliberately **project-scoped**. In a multi-project build,
the unqualified `hardeningCertify` selector runs each project that exposes that task and
each writes its own receipt and session UUID; no one child receipt claims that the whole
repository ran. The release fleet wrapper is the repository aggregate: before execution
it discovers every project exposing `hardeningCertify`, then retains every child receipt
and refuses unless the resulting project set matches that inventory exactly. A standalone
release checklist that does not use the wrapper must likewise gather every per-project
receipt rather than treating the first one found as repository evidence. Keeping
project-scoped `hardeningCertify` also preserves the useful ability to certify one module
without silently putting unrelated modules into certification mode.

Generated evidence must never select the configuration-cache task graph. The PIT
validators are always present and decide at execution time whether `.evidence.tsv`
exists; creating the manifest in a PIT run, or removing it with `clean`, therefore does
not invalidate an otherwise reusable `check` graph. Any future output-existence branch
during configuration reopens that bug class *(casebook: the output file that selected
the configuration-cache graph)*.

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
  baseline-touching consumer (the ratchet, named writer tasks, mode snapshots)
  refuses it, so the shortcut
  cannot leak into the record. Coverage still runs the full test set.
  A `.running` sentinel guards the same consumers against a *crashed or
  interrupted* run: PIT writes its CSV incrementally, so a partial report
  looks complete — the sentinel is written before PIT starts, cleared only
  on a clean exit, and any report still carrying it is refused as evidence
  (the verify runs as the failed task's finalizer, so without this a
  same-invocation prune workflow would rewrite the baseline from whatever
  fraction of the population PIT reached before dying).
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

### ArcMutate is an optional eligible-OSS toolchain, not a prerequisite

Open-source PIT accepts `--historyInputLocation`/`--historyOutputLocation`
but its only registered history factory throws — do not re-attempt on the
strength of the CLI flags existing, and note the failed run leaves the
previous report in place for the verify step to read *(casebook: the 11×
"speedup" that did no work)*.

The hardening plugin and this process are package-agnostic: any Java project can
adopt them. ArcMutate is optional, but in an eligible licensed project its base
plugin is part of the effective PIT toolchain and can affect the mutant population,
not only run speed. Assisted and fresh runs in that project must therefore retain
the same base plugin. Without a licence file, no ArcMutate dependency or flags are
added and PIT runs fully from scratch using its open-source engine.

**With an applicable licence, activation is dropping one file.** The plugin keys
everything off `arcmutate-licence.txt` at the project or root-project directory:
when present, `com.arcmutate:base` joins PIT's classpath. Licensed provenance is
currently audited for base `1.7.1`; overriding `hardening.arcmutateBaseVersion` to a
different licensed engine is refused until its lookup contract is audited. Ordinary suite runs
also enable `+arcmutate_history` against a rolling per-suite file at
`<module>/.pitest-history/<suite>.hist` — outside `build/` so `clean` cannot erase
it, git-ignored as machine-local state. The same directory holds durable local-fuzz
campaign state; it is a machine-local hardening-state boundary, not solely ArcMutate
history. A suite run with `-PnoMutationHistory` or
inside certification suppresses that feature and its history input/output arguments;
it does not remove the licensed base plugin. Mode snapshots and convergence refuse
assisted evidence and direct the operator to that explicit flag.

The certificate committed at the `sava-build` repository root is a signed,
self-contained OSS certificate scoped to `software.sava.*`. It is not an access token.
It is also not packaged into the published Gradle plugin, copied into consumers, or
activated merely because a build applies the plugin. Each eligible public open-source
Sava repository must deliberately copy the certificate to its own root before the
presence-based activation above can see it. Package scope alone is not eligibility: the
certificate does **not** apply to GLAM repositories, private `idl-src-gen`, or an
unrelated adopter. Those projects use the same hardening process with open-source PIT
unless they obtain a licence that applies to them. The unique
`subscriptions.arcmutate.com` download URL is private and must stay out of source,
documentation, logs, and commit messages.

Two honesty rules come with it. Each assisted run announces itself — a
lifecycle line at start and a `[history]` marker on the verify summary, read
from the report's own `.history-assisted` stamp so the tag describes the
report on disk, not this invocation's settings — so a reused number is never
mistaken for a re-earned one; with history active *fast is the expected
state*, and suspicion transfers to the exit code and the marker. And
**anything that writes or certifies the record runs without mutation history**
— enforced, not just prescribed: the per-suite record-writing tasks refuse a history-assisted
report outright, `hardeningCertify` disables history automatically and re-earns
every status from scratch, and the convergence method's runs refuse history too
(two assisted runs agree by construction). `-PnoMutationHistory` remains the
explicit override for other fresh runs. Both mechanisms disable only reuse while
retaining `com.arcmutate:base`, so ordinary, mode-comparison, convergence, and
certification populations share one licensed tool identity. Completed evidence binds
that identity, and each committed suite record binds its portable form as described
below. The typed PIT process also pins both its working directory and `--projectBase`
to the module. A late classpath override must retain recognizable PIT/JUnit Maven
versions equal to the configured versions; markerless ArcMutate package or service
sentinels are refused rather than mislabeled as open-source PIT. Its recorded identity
therefore follows the same engine and certificate search as the child JVM. Intentional
TestKit tools with neither real PIT/plugin sentinels remain supported.
Delete an individual `<suite>.hist` to reset that suite's ArcMutate history. Deleting
`.pitest-history/` resets all machine-local hardening state, including the last local
fuzz campaign receipt.

The installed plugin exposes discoverable writer tasks. The former
`-PupdateMutationBaseline`, `-PunionMutationBaseline`,
`-PpruneMutationBaseline`, `-PinitTimeoutAudit`, and `-PunionModeFlips`
properties are removed in sava-build 21.5.22. Every spelling, including
`-Pflag=false`, is refused during configuration before PIT or a task graph can
touch a committed record. The named tasks are the only supported write interface;
`hardeningHelp` prints their exact mapping and the remaining diagnostic properties.

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
3. **Accept it knowingly** — record the reason in the repo's
   `config/pitest/README.md`, then run `pitest<Suite>BaselineUpdate`. Acceptance
   may mean observable equivalence, a proved structural `NO_COVERAGE` trap, or
   a specifically named deterministic-harness limit; it never means merely
   "hard to test".

**A mutant is a question, not a specification.** Before writing a killing test,
state the intended behavior and identify an oracle independent of the current
implementation: a public contract, protocol specification, caller invariant,
reference implementation, or domain property. If that oracle contradicts the
unmutated program, first demonstrate the bug with a regression test that fails
against the current code, then fix production; a passing assertion copied from the
current result merely hides the bug. If the current behavior is correct, assert the
external property rather than private steps or an expected value generated by the
same implementation. With no defensible oracle, investigate or meet the explicit
acceptance bar above — never manufacture a test just to improve the score.

Keep the reporting proportional. At the PR or agent-handoff boundary, summarize
each nontrivial mutation-driven behavioral cluster, not every mutant, as
`Property: ... | Oracle: ... | Outcome: missing assertion / production bug / accepted equivalent`.
The durable test name and assertions normally carry the property. Add a test comment
only when a future maintainer could not otherwise recover the oracle or unusual
setup; never embed a PIT coordinate or line number there. The accepted-mutant README
remains for surviving accepted families, not a ledger of mutants the tests killed.

**The portable mutation-engine identity is part of the record.** PIT version alone is
not enough: the JUnit plugin, ordered PIT tool artifacts, ArcMutate base, and the
certificate that activates it can all move the population. Each suite therefore
commits two sidecars beside its accepted and timeout records:

- `<suite>-pitest-version` retains the plain PIT version for N-1 readers.
- `<suite>-pitest-toolchain.tsv` records schema, PIT and JUnit-plugin versions, a
  path-independent SHA-256 of the ordered artifact contents, and either `absent`
  ArcMutate fields or the base version, certificate SHA-256, and normalized expiry
  date. Absolute Gradle-cache paths are deliberately excluded, so two machines
loading the same artifacts produce the same identity.

That committed sidecar deliberately does not claim to identify every execution
input. The JDK and the loaded sava-build plugin are machine/build inputs rather than
portable PIT artifacts; each completed-run evidence manifest binds them separately as
`javaVersion` and `pluginSha256`, and certification carries those richer per-run
fingerprints into its receipt.

The sidecar records the certificate's raw named expiry. ArcMutate 1.7.1 gives an OSSS
or commercial certificate one calendar month of grace after that date (an evaluation
certificate gets seven days): the vendor emits its renewal warning during grace but
continues to supply the same licensed population. Hardening mirrors that effective
boundary — the final grace day is accepted and the following day is refused before PIT
can bless evidence. Malformed dates and certificates past grace are refusals.
The PIT child process is pinned to the project directory, and provenance reproduces
ArcMutate's ancestry lookup from there. Once activation has selected the base plugin,
that lookup uses the nearest ancestor containing `arcmutate-licence.txt` or the legacy
`cdg-pitest-licence.txt` name, preferring the current name when both occur in that
directory; the sidecar binds the bytes and expiry it actually selected. A
project-level certificate therefore intentionally wins over a root-project
certificate. Higher-precedence certificates under the PIT report's
`arcmutate-licences` directory or the project's `.pitest` tree are refused:
their vendor-internal selection rules would make the committed identity
ambiguous. Symlinked stores and multi-certificate files are refused for the
same reason. Keep the intended applicable certificate at the project or
root-project directory and remove unintended nearer legacy ancestry files.
Renewal changes its hash and expiry and is therefore a real toolchain transition even
when the base and PIT versions stay fixed. The private subscription download URL is
never part of either record.

An ordinary check warns when the current identity differs. Certification and every
ordinary record writer refuse the mismatch; manually editing either stamp would
claim provenance that no run earned. Rebase is the sole transition path:

1. When provenance is a valid pair or both sidecars are missing as a legacy record,
   run the ordinary `pitest<Suite>` and review the population difference while the
   old or explicitly unbound provenance remains visible. A torn pair, malformed
   sidecar, or internally disagreeing pair instead fails closed before the hardening
   baseline delta is available; do not soften that read gate or treat PIT's aggregate
   console counts as the review.
2. Run `pitest<Suite>BaselineRebase`. For a structurally invalid record this is the
   first complete hardening review: it makes a fresh, full, history-free observation,
   makes the timeout audit strict, retains every old accepted row, and adds each missing
   current gated copy as `# untriaged`.
3. Review its retained/added counts and the complete diff, then triage the additions.
   Rebase removes nothing; only later, repeated
   evidence plus `pitest<Suite>BaselinePrune` may retire old rows. The safe-superset
   baseline and both sidecars are one exception-transactional write plan. A first
   record writes sidecars before content, leaving only fail-closed orphans if
   interrupted. An existing-record Rebase writes its safe-superset content first,
   so N-1 sees conservative debt while this plugin refuses the still-old provenance.

This path also adopts old records. A committed baseline or timeout set missing both
sidecars is reported as the paired `legacy-unversioned` / `legacy-toolchain-unbound`
state. Ordinary checks and `hardeningCertify` announce both, the certification receipt
records that paired state, and every writer except Rebase refuses to adopt it. Exactly
one missing sidecar is structurally one-sided under the current schema. It may be a
complete record written by a pre-sidecar release or an interrupted newer write; the
plugin cannot distinguish those histories, so read/certification paths fail closed
and fresh safe-superset BaselineRebase is the recovery path. Rebase also
replaces malformed or internally disagreeing provenance; no stamp is hand-edited.
Certification may verify a both-missing legacy record against a fresh current
population, but it never pretends historical changes are attributable. Likewise, an
old completed report whose evidence predates portable toolchain identity is announced
as `legacy-unbound` and must be regenerated before it can support a transition.

The baseline is a ratchet: removing debt that is *proved stably gone* is an
improvement, while growing it requires a written reason. A single fresh run is
not that proof — an unmarked load- or mode-dependent flip looks exactly like a
removed mutant on the run where it reads killed. Repos may seed their first
baseline with the full pre-existing survivor population — that is triage debt
made explicit, not acceptance; label it as such in `config/pitest/README.md`.

### `SURVIVED` and `NO_COVERAGE` are different problems

The ratchet treats both as unkilled, which is correct, but they call for
opposite responses:

- **`SURVIVED`** — a test executed the mutated line and could not tell the
  difference. Judgment call: strengthen the assertion or write an acceptance.
- **`NO_COVERAGE`** — usually: no test reached the line. Nothing to judge;
  mechanical work. Never accept one as "equivalent" — you have not observed
  its behaviour, so you cannot know that it is.

`NO_COVERAGE` has one structural exception, and it changes the response
entirely: **a block that always exits by throw reads `NO_COVERAGE` forever,
executed or not.** PIT's block coverage probes a block at its *end*, so
`return f(...)` where `f` throws for every input reaching it never completes
its block — the line reads unreached no matter what executes it, and its
return-value mutants can never change status, because the throw happens
before the mutated return. Diagnose it by contradiction: a test demonstrably
feeds the line and the row stays `NO_COVERAGE` anyway *(casebook: the trap
that never existed)*. What such a row is owed is a test asserting the
throw's contract — exception type and message, ideally against a reference
oracle — plus a note naming the mechanism; coverage is not on offer.
Corollary: the fear that covering such a line converts its mutants into
fresh `SURVIVED` entries is void — that would require the block to complete
— so never leave a throw-terminated line untested "to avoid the trap".
Writing that fear down as a test-absence rationale is how one repo lost its
malformed-input contract coverage for two weeks.

Read the split before planning a pass: "89% killed, 56 to triage" may be 27
judgment calls and 29 untested methods — very different afternoons. When
accepting `NO_COVERAGE` is genuinely right (a path the harness cannot reach
without new scaffolding, or a throw-terminated block with its contract
asserted), say *that* in the note, not "equivalent".

`SURVIVED` has the same third category: a mutant distinguishable *in
principle* but unreachable through any deterministic harness. Accept it as
**unreachable in-harness** and name what would reach it — the named escape
hatch is what tells a later reader whether the acceptance still holds
*(casebook: the HTTP 199 guard)*.

**"The harness cannot reach this" is a claim with an expiry date.** A
downstream adaptation of this process accepted 95 rows across two families on
exactly that claim; it was never true, and disproving it took a single
session (2026-07-26). A second instance followed within the week
(2026-08-02): a six-row ServiceLoader family accepted as unreachable because
the module-path test setup cannot provide a service — which was true, and
beside the point, because PIT minions run on the class path, where a
test-resources services file is scanned (see the probe-and-branch pattern in
the class-path section); the kill took under an hour. So before writing an
unreachable acceptance, try the
thing: spend an hour on the harness before spending a paragraph on the
reason. When a row really is unreachable, write the reason per row rather
than per family and name the *specific* missing capability, because a later
reader should be testing the claim, not inheriting it. Expect
unreachable-acceptance families to shrink over time as harness scaffolding
accretes — one that grows is a harness that stopped being invested in, and
the family's age is part of what a triage pass should re-read.

Every `pitest<Suite>` run prints the split without being asked:

```
pitest 'client': 441/498 detected (88%) — 27 survived, 30 no_coverage
pitest 'vanity': 113/113 detected (100%) — 1 timed out (load-dependent)
```

and a ratchet failure groups new rows under the same two headings. `TIMED_OUT`
counts as detected, matching PIT, but is named separately because that
detection can flip (below). The percentage is rounded **down**, so it never
reads better than it is; PIT's own line rounds, so the two can differ by a
point while the counts agree. Terminal `NON_VIABLE`/`EQUIVALENT` outcomes are
shown but not counted as detected. Error or unfinished statuses (`RUN_ERROR`,
`MEMORY_ERROR`, `STARTED`, `NOT_STARTED`) fail the report closed: they describe
an experiment that did not complete, not evidence that can certify or shrink a
ratchet (see the transient-failures section).

Baseline keys are **line-less** — `class,method,mutator,STATUS` — because line
numbers churn whenever a mutated file is edited, and identity that churns
makes the ratchet police text moves instead of behavior. Lines still appear
on rows, demoted to metadata as trailing `# line N` tags (the audited-timeout
convention), kept for triage pointers and the line-drift advisory below. A
baseline update rewrites tags for rows matched to this report while protected
timeout/insurance rows keep their last observed tags; a green prune likewise rewrites
the tags of retained rows it matched at their own key. Union operations preserve
existing tags and tag only rows they add, while format-only migration
preserves the recorded tags. Editing above a mutated method therefore changes
*nothing* the comparison sees. Two situations still produce paired stale +
"new" rows, and they call for **opposite** responses:

- `(newly covered — was NO_COVERAGE; triage, not a refresh)` — same
  class/method/mutator, different status. A test now *reaches* the mutant,
  which is a triage item: kill it, or accept it with a written reason.
  Refreshing here launders a fresh survivor into the baseline, which is
  precisely what the ratchet exists to prevent.
- `(shares an accepted key — sibling debt surfaced, or a NEW mutant at that
  key; check the line)` — a "new" row identical to an accepted row: the key
  now holds more unkilled mutants than the baseline has rows. Either
  pre-existing sibling debt made visible (a set-based baseline upgraded, a
  compound condition's operands) or a genuinely new mutant landing at an
  accepted key — the report's line numbers say which; read them before
  accepting.

A stale row is consumed once it pairs, so several new rows cannot all claim
the same counterpart and report churn that did not happen. The failure closes
with the whole-set accounting the per-row hints cannot give — `churn: 1
newly covered, 1 surfaced sibling(s), 0 unexplained (of 2 new; 1 stale)`. A
non-zero *unexplained* count is the real thing: a genuinely new key. An
extract-method refactor lands in *unexplained* deliberately: the key names
the method, so a mutant that moved into a new method needs re-triage at its
new home, where the covering tests may differ *(casebook: the check-loop
seam that deleted its flip insurance — its one unexplained row was the
relocated `unlock()`, and it became the family's one written acceptance)*.
Two flags help triage without re-running: `-PlistUnkilled` prints every
unkilled row annotated with PIT's mutation description prefixed with its
line (`line 41: removed conditional…` — the key no longer carries the line,
so the description does), and the ratchet-failure listing carries the same
annotations.

Refreshes are kept honest in both directions. `pitest<Suite>BaselineUpdate` is
a report-driven rewrite plus explicit timeout/insurance keeps; it is not allowed to erase
evidence merely because this run detected a mutant through load. It reads the
same row-level keep plan as prune: rows holding the run's `TIMED_OUT` budget and
rows protected by their key's literal `# flip insurance` marker survive with their
notes and old line tags intact. A reviewed `SURVIVED`/`NO_COVERAGE` status
transition still resolves to the current row through the marked note-carry path;
unlike prune, Update can write the new status. Every other removed row is printed
with one of three honest dispositions: a `KILLED` observation sharing a recorded-line
anchor (duplicate sibling identity may still be ambiguous), coordinate absent from this
PIT report (possibly tool/population churn), or no tie between this row and a killed
observation. None is overstated as proof of a particular sibling's death *(casebook: the
migration refresh that deleted its evidence)*.

A baseline row may carry a trailing `# note` —
`# untriaged` is the conventional label for seeded debt, and
`pitest<Suite>BaselineUpdate` seeds it on **every genuinely new row** it
writes — a new sibling at an accepted key included, since the twin's
argument was written for the mutants it had, not for one more — so no new
row enters the baseline bare: triage
means replacing that label with a short family label (`# race-guard
family`, `# capacity-hint`) whose full argument lives in the README. An
already-unlabeled row is a different thing — it predates seeding (added in
21.5.12) and its argument lives in the README rather than on the row — and a
refresh preserves that state rather than converting it to seeded debt. Rows retained
by baseline writers preserve their notes and their original document slots; new rows
append. Whole-line comments are document prose, while a row-specific acceptance
argument belongs in that row's inline note. Update names every note that actually leaves
with a row. The verify summary counts notes
**per label** (`38 rows — 13 '# untriaged', 20 '# race-guard family', 5
unlabeled`; the debt task prints the same breakdown), so triage state is a
number the build prints rather than prose that drifts from the CSV it
describes. Rows that predate seeding
print as `unlabeled`; label them when touched. A label is also a pointer to
its argument: the verify *and* the debt listing warn when a family label has
no `# <label>` mention in `config/pitest/README.md`, so a typo'd label or an
orphaned argument surfaces instead of silently opening a new bucket — and it
surfaces in the listing where the counts are read, since a count is exactly
what makes a mistyped label read as finished triage
(`# untriaged` is exempt — seeded debt needs no section). Rows parse as an
ordered list, so duplicate sibling rows each keep their **own** note — the
note map keyed by row text that used to collapse them is gone, which is what
lets two siblings of one key carry two different family labels. All baseline
rewrites land atomically (a sibling temp file moved over the target), so an
interrupted refresh — a stopped task, a killed daemon — leaves the previous
baseline intact instead of a truncated file the next verify reads as an
empty ratchet *(casebook: the baseline truncated mid-write)*. Preservation extends across a
status flip: when a refresh rewrites a coordinate whose status changed (a
`NO_COVERAGE` row whose method a test now reaches), the dropped row's note is
carried onto the new row annotated `(carried across NO_COVERAGE ->
SURVIVED)` and the summary counts the carries — the acceptance argument
travels, but flagged for re-reading, because a reason written for an
unreached mutant is not automatically a reason once its behaviour is
observable *(casebook: the status-blind prune)*. Multiple flipped siblings use the
same maximum line-affinity assignment as same-status siblings before falling back to
file order, so uniquely anchored notes do not cross during the carry. Within one key,
accepted rows are assigned to the run's mutants by **maximum line affinity** first,
then by file order. A unique `# line` anchor attributes a row; repeated or overlapping
anchors provide deterministic allocation, not proof of sibling identity. Every note
that leaves a row is named loudly in the dropped listing (`note carried` / `note
dropped with the row`, losses counted) *(casebook: the note the line shift dropped —
the carry apparatus that entry describes is retired; affinity plus the fate listing
is what replaced it)*. Without line evidence the assignment is arbitrary, which is
the same-key blind spot below, not a bug to police.

The third refresh is mechanically shrink-only, not self-authorizing:
`pitest<Suite>BaselinePrune` drops baseline rows matching nothing this run and
adds no rows. One run cannot distinguish a stable removal from an uninsured
load- or mode-dependent flip, so the ordinary verify prints a **preview of the
exact candidate rows** without recommending the flag. Re-measure those rows
under the relevant solo/gate load and prune only when the same candidates stay
absent; a row proved to flip belongs in persistent `# flip insurance` instead.
Prune also refreshes the `# line` tag of each retained row matched at its own
key, using line affinity before file order; unmatched rows kept for
`TIMED_OUT`, a pending flip, or flip insurance retain their prior tags because
that run did not observe them at their own key. A line-only rewrite is still
atomic and occurs even when prune drops nothing, which makes prune safe for
clearing a reviewed line-drift advisory **when its candidate preview is
empty**. "Matching" is the
verify's own multiset comparison: a key holding more rows than the run's unkilled mutants
has excess to drop, and which sibling goes is decided by line affinity first
(a row whose `# line` tag names no live line is preferred as the absent sibling),
file order after — so a noted live-anchored row is not dropped for its bare sibling,
while duplicate same-line siblings remain inherently ambiguous,
and the candidate preview names the same row prune would remove *(casebook:
the stale hint that named the wrong flag)*. Two unmatched classes are kept
anyway, each named in the output: rows whose coordinate `TIMED_OUT` this run
(load-dependent detection, not a kill), and rows with an *unmatched*
different-status counterpart at their coordinate (a coverage flip the ratchet
must triage first — a same-status sibling is never that, and neither is a
mutant already matched by a row of its own key). Both keeps are **budgets,
not statuses**: at most as many rows per coordinate as mutants actually
timed out (or flipped) there, line affinity deciding which rows hold the
timeout budget — one audited permanent timeout cannot vouch for an unbounded
pile of genuinely killed siblings. Flip-insured rows are kept
unconditionally and decided *before* the timeout budget, so an insured row
never spends the budget its uninsured sibling needs. Prune and the candidate
preview read one row-level keep plan, so the preview and the eventual action
name exactly the same rows. Agreement prevents a tooling lie; repeated
observation supplies the evidence the tool cannot infer from one report.
Never substitute a hand-rolled cleanup script, which is how the status-blind
prune happened. The named record transitions are mutually exclusive; the build
refuses a combination.

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
  the observed-flip unions with `pitestModeCompareUnion`.
- **Run-to-run drift is announced automatically.** The verify stashes each
  run's per-status coordinates — every status, `KILLED` included
  (`.pitest-history/<suite>.statuses`, machine-local, a `# stash format`
  header naming its format) — and names each newcomer's origin on the next
  run. **Every positive timeout-count delta prints the affected coordinates,
  `+N` multiplicity, and current line-full
  `class,method,line,mutator` observations.** The stash is
  line-less, so when that key already held a timeout it cannot prove which
  current line is the newcomer; the output says so and prints every current
  `TIMED_OUT` candidate instead of inventing an attribution.
  `KILLED -> TIMED_OUT` is the benign flavour and gets a one-line count;
  `SURVIVED -> TIMED_OUT` gets a warning with the rows, because a
  mutant nobody killed now reads as detected purely through load — do not
  let a refresh quietly drop it from the baseline on the strength of that.
  `NO_COVERAGE -> TIMED_OUT` gets the same warning: the mutant was never
  even reached, and the test that newly covers it hangs instead of killing
  it. An origin the stash omits is one the comparison silently misreads —
  `NO_COVERAGE` omitted read the dangerous flip as benign, `KILLED` omitted
  read the benign flap as novelty — which is why every status is stashed
  and a stash written by an earlier format (headerless or five-field)
  resets the comparison for one announced run instead of comparing blind.
  A timeout with no prior *detected* read at its key — a new coordinate, a
  new sibling at a gated-only key, or a key whose only prior reads were
  PIT's not-counted-as-detected statuses — is named, with its multiplicity,
  as a **reviewer-stop first observation**, never as "previously detected".
  This default drift detail is independent of audited-set membership: the
  timeout set is intentionally line-less, so an existing membership row can
  cover a key whose timeout count just grew; membership suppresses the
  unaudited-row warning, but it never suppresses the changed coordinate from
  the drift output or its line-full candidates. Re-read that member's
  structural cause before treating the new sibling as covered by it. The two
  runs are compared as per-coordinate
  **counts**, not as sets of coordinates: the coordinate is line-less, so one
  key routinely holds an accepted survivor *and* an audited timeout at the
  same time, and asking a set "is this key timed out now and was it survived
  before" answers yes on every run including the ones where nothing moved. A
  flip is a key whose timeout count rose *and* whose unkilled count fell
  *(casebook: the flip that fired forever)*. The verify's candidate preview honours this: a
  baseline row whose coordinate read `TIMED_OUT` this run is reported as the
  load flip it is ("no refresh needed"), never included
  among rows prune would remove. A stale-looking
  row at a *flip-insured key* — any row of the key carrying a
  `# flip insurance` note, machine-written by `pitestModeCompareUnion` or riding
  in a hand annotation's parenthetical — gets the same honour: it is
  reported as the flap its insurance records (or, when its coordinate is
  alive at another status, as the newly-covered move the failure detail
  names), excluded from the candidate preview, and kept by prune and Update, so the
  explicit action cannot drop a row whose
  absence would fail the next solo run with an unexplained survivor. Both
  the keep and the hint are key-level, because which member of a flappy
  family reads killed on a given run is itself load-dependent; the row
  leaves by the union's written removal criterion, never by refresh.

  "Benign" is a boundary claim, not a shrug: `KILLED` and `TIMED_OUT` are
  both *detected*, neither is ever written to a baseline, so this flip is
  two clocks racing — the covering test reaching a failure versus the
  watchdog — over the same dead mutant, and no outcome of the race can move
  a verify or hide debt. The claim is earned per suite by the mode
  comparison above, which is what separates it from `SURVIVED -> TIMED_OUT`,
  where the race is between detection and *no detection*.
- **Union only rows you have observed to flip** — and prefer the
  `pitestModeCompareUnion` path, which writes the observation
  *into* the row as a `# flip insurance (<per-mode statuses>)` note a later
  reader can re-measure. The verify-side `pitest<Suite>BaselineUnion` remains
  as the escape hatch for a directly witnessed flip: it adds the run's
  unkilled rows in canonical form without dropping baseline rows that
  happened to be detected this run. Update now protects current timeout budgets
  and literal insurance too, but an *uninsured* mode flip is still indistinguishable
  from a stable removal in one report and can start refresh ping-pong. Union lands
  bare rows, so a hand union owes the evidence note by hand or the insurance
  is an unargued acceptance. Bulk-adding every `TIMED_OUT` row "to be safe"
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

  What a timeout-detected mutant costs, name it rather than absorb it: the
  watchdog observed slowness, not wrongness, so for that one mutant the
  ratchet can no longer see a **weakened covering assertion** — soften the
  test to uselessness and the timeout keeps "detecting" regardless. The
  compensating control is the audited set: one `class,method,mutator` row
  per member in `config/pitest/<suite>-timeouts.csv` (line-less so drift
  cannot churn membership; `#` comments allowed, and spacing around fields
  is normalized away, so rows may be aligned for reading), with the
  structural cause
  (the removed loop exit, the reversed increment, the leaked unlock)
  written per member in `config/pitest/README.md`. Adoption is seeded, not
  transcribed: a suite whose summary reports timeouts with no set on disk is
  pointed at `pitest<Suite>TimeoutAuditInit`, which writes the membership rows from the
  run's report (observed lines riding in `#` comments) and leaves only the
  causes to a person. The nudge also prints the would-be member rows
  paste-ready alongside the task hint: timeouts are load-dependent, so the run
  that prompted the nudge may be the only one holding them — a later
  seeding run against a clean report is rightly refused, and without the
  printed rows the coordinate that timed out is recoverable only from the
  daemon log. The seeder refuses to reseed an existing file, refuses a
  `-PmutateOnly` report like every other baseline-touching writer, refuses a
  report with nothing timed out (an empty seed would activate the audit
  with zero members to write causes for; timeouts are load-dependent, so
  re-run under the conditions whose summary reported them), and, like
  every other writer workflow, combines with none of them. Seeding is not
  the only way in: a suite that has never produced a timeout can *arm* the
  audit by committing a comments-only membership file — zero members is a
  legitimate audited set, and the suite's first timeout then warns as the
  unaudited-newcomer reviewer-stop instead of surfacing as the softer
  adoption hint; the seeder's empty-seed refusal is about refusing to
  fabricate evidence, not about forbidding empty sets. With the
  file present, the verify warns on any timed-out mutant missing from the
  set — a *new* member is a reviewer-stop, not load noise; the printed row
  is paste-ready, with the line riding in a `#` comment — warns on rows
  that do not parse as three fields (named malformed, never misdiagnosed as
  a member matching no mutant), and warns on members matching no mutant at
  all (retirement hygiene) as well as members whose class-and-method appear
  together in no single README section, each matched as a whole word (a
  cause that was never written; the same soft pointer rule family labels
  follow — the method name alone was trivially satisfied, since most
  dispatch members are named `handle`, and whole-file substring matching
  stayed trivial: `run` sits inside "rerun", and a sibling member's cause
  already names the class. The unit is a markdown-heading block, not a
  blank-line paragraph — measured against the fleet's 172 audited members,
  paragraphs false-flagged 41 documented causes in the house style that
  names the class in a section's intro and argues each method in its own
  paragraph below, while sections resolved all 172).
  Membership must also keep earning itself: a member is validated against
  *all* mutants, so a key that exists but never times out — pasted from the
  wrong report, or a timeout the tests since learned to kill outright —
  would otherwise sit accepted forever. The verify keeps a per-member quiet
  counter in `.pitest-history/`, keyed to the report's fingerprint so
  standalone verify re-runs of one report are one observation, and notices
  members with no timeout in 3+ consecutive mutation runs (the flip-family
  retirement criterion); a single quiet run is just the
  `KILLED`↔`TIMED_OUT` load flip, and a gate-load-only
  member is reset by gate runs, so the notice presumes nothing; a stale
  interlude drops the counter rather than freezing it — staleness means the
  code moved, so quietness is re-measured once the mutant returns instead of
  argued from the old method body. The
  line-less key is also the membership check's resolution: a *new* timed-out
  mutant in an already-audited method+mutator draws no **unaudited-member**
  warning. It is not therefore invisible: when the timeout count grew from
  the previous fresh report, run-to-run drift names the line-less key and
  prints every current line-full candidate (the stash cannot distinguish the
  old copy from the newcomer). A stable timeout multiplicity stays quiet; a
  growth is evidence to re-read. The README cause
  should name the line it argues about (the paste-ready row carries it in
  the `#` comment) so a reviewer can notice when the code at that line is
  no longer what the argument described — and "notice" has a machine half:
  the `# line` comment is parsed back, and a member whose observed timeout
  lines are all absent from its comment is warned as line drift (the anchor
  the cause argues about moved entirely; a *new* sibling line next to a
  recorded one does not masquerade as a full move, while its count growth is
  still named by the run-to-run drift output above). Advisory only, never a failure, by
  default: load can time out any mutant on any run, and both flavours are
  still detection. For certifying runs, `-PstrictTimeoutAudit` escalates
  exactly the findings that mean the audit is not being kept — an
  unaudited newcomer, a malformed row, a member whose cause was never
  written (the doctrine admits a newcomer only with its cause written, so a
  cause-less member is an unfinished admission, not hygiene — row-then-cause
  is a legitimate sequence *between* certifications, not during one), or a
  timeout-carrying suite with no
  set at all — to failures; hygiene
  findings (stale members, quiet streaks, drifted lines) stay advisory
  even there. An escalated finding is the failure, not an advisory — it is
  left out of the end-of-build summary, whose "none failed the build"
  framing must stay true. Both certification paths refuse a `-PmutateOnly`
  report outright, as the refresh flavours do: their checks are skipped
  entirely on a scoped report, so a green run would certify nothing while
  reading as a certification of the suite. Because every audit finding is advisory in the default modes,
  the build ends with a one-line-per-suite summary of the advisory findings
  it printed — a reviewer-stop nobody scrolls back to is not a stop.
  The audit's static half — row shape and cause presence — reads committed
  files only, so `pitest<Suite>Debt` runs it too (one implementation,
  `TimeoutAudit`, so the two tasks cannot disagree): paste a row or write a
  cause and confirm the tool agrees in seconds, without a mutation run.
  `Debt` knows no staleness (that needs a report), so it asks every
  well-formed member for its cause.
- **Flip families do not settle while their cause remains — and "the cause
  remains" is a claim to re-measure, not a fact to record once.** Mutants
  equivalent on the wire but timing-dependent in detection (socket suites
  are the breeding ground) can hold a steady state where the baseline is
  deliberately the union of observed survivals and quiet runs emit
  *permanent* stale-entry warnings. While the cause is live that is correct,
  not cleanup debt: refreshing an **uninsured** family from any single run
  bakes in that run's coin-flips and starts ping-pong; once the literal
  `# flip insurance` marker exists, Update preserves that key instead
  *(casebook: the handled-flag family that never settles)*. But the steady
  state is a holding pattern, not a destination, and the trade is asymmetric:
  a wrongly-removed union costs
  one red build and one `pitestModeCompareUnion` to restore, evidence note
  included, while a wrongly-kept union **blinds the ratchet at that key** —
  a row accepted in both statuses can never fail again, so a later edit that
  makes the mutant genuinely survive passes silently. Write each union's
  removal criterion when the union is written (N quiet `pitestModeCompare`
  cycles, or the cause removed) — and prefer removing the cause outright,
  which deletes the insurance *for* something instead of waiting it out
  *(casebook: flip insurance that outlived its cause; the check-loop seam
  that deleted its flip insurance)*.

### Three baseline-format traps

- **Status is part of the row.** A `NO_COVERAGE -> SURVIVED` flip is two
  different rows at one coordinate. A script that matches baseline rows by
  `class,method,mutator` alone — say, to remove entries absent from one run —
  lets the stale row sitting earlier in the file consume the surviving
  mutant's match and deletes the acceptance instead, leaving a baseline the
  verify rightly fails one command after it was correct. Match on the full
  row, and prefer the named writer tasks over hand-rolled scripts *(casebook: the
  status-blind prune)*.
- **Duplicate rows are sibling mutants, not noise.** A compound condition
  (`a == null || b == null`) yields several mutants with identical
  `class,method,mutator,STATUS` keys — one per operand, branch direction, or
  occurrence in the method — and the baseline keeps one row per mutant, so
  identical keys legitimately repeat (their `# line` tags telling them
  apart for a reader). The comparison is a *multiset* comparison: if two
  siblings are accepted and a third appears (or a killed sibling regresses),
  the count mismatch is flagged even though the row text already exists.
  Never hand-dedupe the file. The verify names each extra copy `(shares an
  accepted key — sibling debt surfaced, or a NEW mutant at that key; check
  the line)` and counts it in the churn tally.
- **Hand-edited rows can silently never match.** The canonical mutator name
  strips the `org.pitest.…gregor.mutators.` package *and* the `returns.`
  sub-package; a row spelled `returns.NullReturnValsMutator` matches nothing
  and reports new forever. Prefer the named `pitest<Suite>BaselineUpdate` after
  reviewing its complete report-driven rewrite. Use `pitest<Suite>BaselineUnion`
  or `pitestModeCompareUnion` for measured flips; do not hand-roll a union.

### Line numbers are metadata, and the one hole that buys

Lines left baseline identity entirely (they had already left the audited
timeout sets, for the same reason): editing above a mutated method changes
nothing the ratchet sees, so the drift classifier, the pure-drift tolerated
pass, the pairing-outlier scan and `-PnoDriftTolerance` are all retired —
there is no drift left to tolerate. What remains of lines is metadata and one
advisory: a key unkilled *only* at lines its tags do not name draws a
**line-drift advisory** — the code the acceptance argues about has moved, or
a new mutant sits under an old acceptance. Re-read the README argument. If
the same run's prune-candidate preview is empty, a green
`pitest<Suite>BaselinePrune` refreshes matched tags without accepting anything;
otherwise re-measure the candidates before combining a metadata refresh with
their deletion. The report-driven BaselineUpdate also refreshes tags, preserves
this run's timeout and flip-insurance budgets, and carries its normal
baseline-widening risk. Unions preserve existing tags and attach observed
tags to rows they add; the mode-flip union also preserves an existing row's
tag when it annotates that row. `migrateMutationBaselines` preserves recorded
tags because it has no current mutation report. The check is row-level where
the data supports it: when every row of a key carries a tag and the observed count
matches the row count, *any* unrecorded line is reported — the baseline's
multiset already fails a genuinely new sibling as a count change, so unlike
the audited timeout sets there is no new-sibling quiet case to preserve.
Partial tags or skewed counts fall back to the audit's key-level disjointness
(the skew is already failing the build or printing the candidate preview).

Accepted-baseline documents have an explicit schema. Schema 1 begins with
`!sava-hardening-baseline-schema,1`; the non-comment marker makes a legacy
reader at least diagnose the row as malformed instead of silently treating an
unknown schema as a comment. From this schema-aware release onward, an unknown
version is a hard refusal on every reading path. This release also reads the one N-1 form: an unversioned
file containing current line-less rows or legacy five-field rows
(`class,method,<line>,mutator,STATUS`). Legacy line fields demote to `# line`
metadata. Unknown, malformed, duplicate, or misplaced schema markers fail
loudly on every reading path.

That marker is deliberately scoped to `*-accepted.csv`. Audited timeout sets
were introduced with their present line-less `class,method,mutator` identity;
their `# line` tags have always been ignored metadata, so there is no older
representation for this migration to guard against. They remain an unversioned,
stable membership format. A future incompatible timeout-set change needs its own
marker, N-1 reader, migration, and rollback rather than borrowing the accepted
baseline's schema name.

Normal writer tasks preserve an existing document's schema state, while a newly
created baseline starts at schema 1; merely bumping the plugin therefore does
not stamp every checkout. `migrateMutationBaselines`
deliberately canonicalizes rows and adds the schema-1 marker without a
mutation run; run it only after every root, composite, and benchmark-build pin
that may read the committed files has moved to a schema-aware release.
Whitespace-only accepted-baseline placeholders canonicalize to absence during
migration and ordinary writes: they accept no mutant and carry no review
evidence, so a schema marker would protect nothing. A comment-only document is
not empty; its local evidence, layout, and marker remain. Schema 1's
`downgradeMutationBaselines` removes only the marker from substantive documents,
preserving row spelling, comments, blanks, order, and duplicates; an empty
placeholder likewise becomes absence. Downgrade is allowlisted per schema and
refuses malformed or newly structured content, so a future schema cannot become
silently lossy merely because its version becomes current. Run downgrade and
commit the result before rolling a consumer back to the N-1 plugin. Those two
tasks are the fleet migration and rollback plan. A migration can surface old
line drift as a review prompt, but it never changes baseline identity.
Rollback is representation-preserving for substantive documents, not a
byte-for-byte inverse of migration: empty placeholders removed during migration
remain absent, which N-1 reads identically to an empty baseline.

The price, named because it is paid deliberately: **a same-key swap is
invisible.** Kill one mutant and introduce a new one at the same
`class,method,mutator,STATUS` in one change and the multiset is unchanged —
the new mutant inherits the old row's acceptance, and the only trace is the
line-drift advisory — which, being row-level under matched counts, fires
whenever the new mutant sits at any line no tag names, not only when every
anchor moved. The line-full
design did not close this hole either; it covered it with a dominant-delta
heuristic (`PAIRING OUTLIER`) that produced its own false alarms *(casebook:
the killed row recycled onto new debt at the same key — the scan that entry
motivated is retired with the churn it policed)*. A documented hole that an
advisory sometimes lights up beats a heuristic that must be argued with:
when the advisory names a key whose argument you cannot re-derive from the
current code, treat it as the swap until shown otherwise.

### When a mutant won't die — a decision tree

Step zero, for `NO_COVERAGE` rows only: confirm the block can complete. A
mutant in a throw-terminated block (see the `SURVIVED`/`NO_COVERAGE` section)
cannot be covered *or* killed by any test — assert the throw's contract,
accept with the mechanism named, and skip the tree.

Worked in this order, each step cheaper than the one after it:

1. **Strengthen the assertion toward the property, not the implementation.**
   Content equality cannot tell a reuse from a reparse — assert identity.
   A log call is only pinned by asserting the *rendered* message (parameters
   included), a lock only by asserting it is free afterwards.
2. **Identify which sibling survived.** For `RemoveConditional` pairs on
   compound conditions, one bytecode direction is usually killed and its
   same-coordinate sibling survives; the verify prints
   `[detected sibling at this line: KILLED by <test>]` on such rows — in the
   ratchet-failure listing, scoped runs, and `-PlistUnkilled` alike.
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

**Before step 4, ask whether the mutated line can still execute.** PIT reuses
minion JVMs across mutants, so process-lifetime state set up by an *earlier*
mutant's run of the same test is still there. Memoizing factories are the
common shape: a `computeIfAbsent` over a static cache, keyed by a constant the
test hard-codes, is populated on first use and never re-invokes the mutated
lambda again — the mutant is unreachable by construction, and looks exactly
like an equivalent one. The tell is that the mutated code is a cache-miss path
whose key the test does not vary. The fix is a fixture change, not an
acceptance: mint a fresh key per invocation so every run takes the miss path
*(casebook: the memoizing factory that could not be killed)*.

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
- **Copy-on-write routing — accepted per-direction, never per-pattern** —
  `size() > 1 ? unmodifiableCopy : as-is` clusters split by branch
  direction: swapping one immutable collection for an equal one is
  equivalent, but the direction that lets a mutable multi-entry collection
  escape an API promising unmodifiable views is a kill — one
  `assertThrows(UnsupportedOperationException, ...)` per size converts it
  *(casebook: the copy-on-write family that split)*.
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
deliberate opt-outs. Comments keep the local configuration readable, but a production
opt-out is certification evidence only when it is recorded through
`declineExclusionAudit(glob, reason)` as described below.

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

`mutationOwnershipAudit` makes that inventory executable: it enumerates compiled
production classes and fails when a class is effectively owned by no suite, when an
ownership decline has no reason, or when a decline has gone stale. A deliberate opt-out
must first sit inside a suite's target universe, be excluded there, and carry
`declineExclusionAudit(glob, reason)` naming what owns correctness instead. This audit is
available directly and is mandatory under `hardeningCertify`; ordinary `qualityGate`
stays compatible with repos while they complete whole-population adoption.

## What the ratchet cannot see

The mutant population bounds what a clean ratchet proves, and the bound has
several independent edges. Keeping them in one named inventory — rather than
scattered where each was learned — is what stops a green run from being read
as more than it is. The generic edges:

- **The mutator set** — a mutant that is never generated cannot survive; the
  next section is this entry in detail, and the mutator-blindness advice is
  its machine check.
- **The class path** — PIT's world diverges from the module path's; the
  "class path is PIT's world" section below is this entry in detail.
- **Kills come only from `targetTests`.** Integration suites, live drift
  checks, localnet-style harnesses, and anything else outside the suite's
  test pattern are invisible: code exercised only through them reads as
  `NO_COVERAGE` even when thoroughly tested. That is useful information — it
  maps which logic is verifiable in seconds versus minutes — but read such a
  row accordingly before writing a unit test that merely restates wiring.
- **Excluded classes never enter the population.** Every `excludedClasses`
  glob and every auto-excluded fuzz harness is a deliberate hole; the
  excluded-production-class advisory names main-source classes a glob
  swallows so the hole stays deliberate. Suite-partition handoffs are not
  findings: a class some sibling suite actually mutates — its targets match,
  its own exclusions do not — is owned, and the advisory subtracts it; a
  class every suite excludes has no owner and is named in each suite that
  swallows it *(casebook: the partition the audit called a hole)*. The check
  runs inside each real `pitest<Suite>` execution and, statically, in
  `pitest<Suite>Debt` whenever a prior run left recompiled classes behind —
  the Debt half is what the fleet canary sees.

  The targeting policy endorses a third exclusion category the scan cannot
  derive: **deliberate opt-outs** — generated bindings, vendored code, a main
  that needs live credentials. "Generated" is a judgment about what the
  classes *are*, indistinguishable from a forgotten glob by inspecting globs,
  so it is written down instead:

  ```kotlin
  mutation.register("clients") {
    excludedClasses = listOf("com.example.clients.*.gen.*", …)
    declineExclusionAudit(
        "com.example.clients.*.gen.*",
        "generated IDL bindings; correctness rides on the generator's own suites",
    )
  }
  ```

  The record is keyed by the glob exactly as written, so a glob swallowing
  fifty classes is one decision rather than fifty rows, and it keeps earning
  itself on the `declineMutator` terms: a blank reason suppresses nothing and
  is itself reported, and a record whose glob stops swallowing anything is
  reported as deletable rather than fossilising. Reach for it only when the
  exclusion is genuinely a category the ratchet should not cover — a hole you
  have not argued is still a hole, and the advisory is how you find it.
- **Generated and reflective code.** Annotation-processor output, generated
  sources, and behavior reached only reflectively carry their correctness on
  their own tests, not on the ratchet.

Each consuming repo should keep its own instance of this inventory (in
`HARDENING_NOTES.md`), naming the repo-specific edges — the suites whose
kills depend on background-thread ceilings, the feature-gated paths, the
temporary package gaps that still prevent certification, and the argued declines for
code whose correctness lives elsewhere — because the generic list above cannot know
them. The notes are supplemental context: they do not waive
`mutationOwnershipAudit`, and a package deliberately outside mutation still needs to
be targeted, excluded, and recorded with `declineExclusionAudit` before certification.

## The mutator set bounds what the ratchet can see

Targeting chooses which classes are mutated; the mutator set chooses which
*defects are expressible*. The standard groups have a blind spot landing
exactly on money-handling code: `MathMutator` rewrites primitive bytecode
arithmetic, while `BigInteger`/`BigDecimal` arithmetic is method calls those
opcodes never touch. A fee computation on `BigInteger` is invisible to
`STRONGER` — the suite is blind by construction, not undertested, and no
ratchet failure can say so, because a mutant that is never generated cannot
survive.

Since the blind spot cannot announce itself through the ratchet, each
`pitest<Suite>` run looks for it directly: the classes about to be mutated are
scanned for arithmetic calls on `BigDecimal`/`BigInteger`, and when the
matching mutator is not enabled the run warns with the trial command and the
call-site count. The scan reads the constant pool of the recompiled classes,
counts only the operations these mutators rewrite (`add`, `multiply`, `and`,
`shiftLeft`, …) so that merely formatting or comparing a Big value does not
trip it, and never fails a build. It goes quiet as soon as the mutator is
enabled — or when the decision not to is recorded on the suite:

```kotlin
mutation.register("decimal") {
  declineMutator(
      "EXPERIMENTAL_BIG_DECIMAL",
      "trialed 2026-07-21: generated 0. It rewrites only (BigDecimal)BigDecimal " +
          "arithmetic, and this package's math is movePointLeft/Right(int).",
  )
}
```

The reason is not decoration. A suppression is worth exactly the argument
attached to it, and an empty one cannot be told apart later from an oversight —
so a blank reason suppresses nothing and is itself reported. Only decline what
was *measured*: a decline recorded to quiet a warning nobody investigated is the
failure this mechanism exists to surface, and it will read as settled to
everyone who comes after.

Declines expire the way baseline rows do. Enable the mutator, or delete the
arithmetic it argued about, and the run reports the decline as deletable rather
than letting it fossilise. `NAKED_RECEIVER` is
deliberately not advised this way: fluent APIs are near-universal, so it would
fire on almost every suite, and advice that always fires stops being read.

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
module-path `test` task — a harness whose *pass/fail* depends on which task
ran it, which is never committed *(casebook: PIT's world is the class path)*.

For real (main-source) services the resolution is the standard dual
declaration — `module-info` *and* `META-INF/services` — which is also just
correct packaging for a library classpath consumers can load. For test-only
providers, **probe and branch**: register fixture providers in a
test-resources `META-INF/services` file, then have the test probe
`ServiceLoader` once and assert the correct behavior for whichever world it
woke up in — providers found (the class path, so PIT: resolution, filtering,
and the wrong-provider rejection all execute and their mutants are killable)
or none found (the module path: the no-provider error, message included).
Pass/fail stays deterministic in both worlds, which is what the
never-committed rule actually forbids — environment-dependent *results*, not
environment-branched assertions. Nest the fixture providers inside the test
class itself: a top-level fixture matches no `Test*` exclusion and silently
joins the mutated population. The first consumer to try this killed a
six-row family that had sat accepted as unreachable in-harness for three
weeks, its named escape ("a blackbox test module with its own descriptor")
one capability larger than what the kill needed — the second such acceptance
disproven by just trying the harness, which is what the expiry-date rule in
the triage section is about.

That world is built by recompiling **every** main and test source into one
class-path root, which makes a git-ignored source file a parity hazard: it is
compiled on the machine that has it and absent everywhere else, so the tools
see a different class path per checkout. `hardening.recompileExcludes =
listOf("Integ.java")` drops such files by file name (a suite's
`excludedClasses` only keeps them out of the *mutant population* — the class
is still on the path, still loadable, still able to drag a dependency in).
Reach for it for scratch drivers and local experiment classes; anything a
build contract depends on belongs in the repo instead.

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
- **Stubs return distinguishable, non-default values.** A fixture method
  returning null, 0, `""`, `true`, or an empty collection makes the
  corresponding return-value mutant equivalent by accident wherever that
  value flows — the rule already named for clocks (non-zero origin)
  generalized to every stubbed return *(casebook: the stub that returned
  the mutant's value)*.
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
- **Build the client under test inside the test body, not in a field.** With
  `PER_CLASS` lifecycle a field-initialized client is constructed once, so
  PIT attributes the builder's and constructor's coverage to whichever test
  happens to run first — which can never pair a URL-wiring or configuration
  mutant with the test that drives that URL, *even when the harness asserts
  every path*. The mutants read `SURVIVED` with a test count that never
  includes the right test. It is the unstable-field-initializer coverage
  problem (see the wandering-kill-count section) wearing a REST-client
  costume: one `urlWiringIsCoveredFromInsideTheTest`-style test that builds
  the client in the test method restores the pairing *(casebook: the client
  built in a field initializer)*.
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

**A finding that cannot be fixed yet still gets its regression test — a
failing one, committed `@Disabled`.** The test asserts the *correct*
behavior, so it fails while the bug lives; disable it with the finding's
identifier as the reason and un-ignore it when the fix lands. The opposite
pattern — asserting the buggy value so the suite stays green — makes the bug
look intended and is exactly how findings rot. Keep a findings record with
the same lifecycle rule the Rust adaptation proved out: a finding leaves the
record only when it is fixed (delete the `@Disabled`, keep the test) or
argued not-a-bug in writing.

**Silence operator diagnostics by the logger's declaration site, not the
class that logs.** A parser that logs each failure at ERROR with the full
document floods a campaign into gigabytes, so harnesses silence it through
the JUL backend — but the name that matters is where the `System.Logger`
field is *declared*: a logger inherited from an interface carries the
interface's name, while JUL's formatter prints the *source class*, so a
silence keyed to the class you see in the output compiles, looks right
against the very lines it fails to suppress, and silences nothing. Verify a
silence by running the harness and seeing zero lines — never by reading the
log format *(casebook: the silence that named the wrong logger)*.

**A long campaign writes to a file, and a fuzzer that stops printing may be
frozen, not finished.** libFuzzer prints progress to the launching pipeline;
when the consumer dies, the next progress write blocks forever inside native
code and the JVM parks `RUNNABLE` in `startLibFuzzer` — by thread state
alone, indistinguishable from a healthy quiet stretch. The tell is the CPU
delta: a fuzzing JVM accumulates CPU continuously, a frozen one stops cold.
Route campaign output straight to a file rather than through any consumer
that can die mid-run. And a killed campaign dumps each in-flight input as a
`crash-<hash>` artifact — one per parallel target, all stamped the kill
moment, reproducing nothing: dump-on-death, not findings; replay against the
harness before treating them as crashes *(casebook: the fuzzer frozen by its
own stdout)*.

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

A target that declares **no** `seedCorpus` gets none of that, and used to get
it silently: no replay test, nothing re-run by `check`, and a
`fuzz<Target>Minimize` that fails only when someone happens to reach for it.
Since a fuzz target whose findings are never replayed is the exact rot the
replay mechanism exists to prevent, `generateFuzzReplayTests` now names every
corpus-less target and prints the one-line fix.

**Landing a fuzz target in a mutated package changes the suite in the same
build, in two directions.** The harness class itself leaves the mutant
population automatically: every registered target's class (plus its nested
classes) is appended to every suite's `excludedClasses`, because a mutated
harness weakens the fuzzer and can never be product risk — no hand-written
`*Fuzz*` exclusion row needed, and no silent gap when a suite registration
predates the module's first harness. The generated `<Harness>SeedReplayTest`,
though, matches the usual `*Test*` targetTests and joins the suite as a
killer immediately — seeds reach code no hand-written test covers, so expect
`NO_COVERAGE` rows to flip to `SURVIVED` and surface as new-unexplained churn
on the next verify. That is the replay doing its job: the flipped rows were
never-observed behavior, now observed and undetected. Kill or triage them
before refreshing — the flips are precisely where a targeted test is
cheapest to justify.

**A corpus does two independent jobs, and only the first is about the input
format.** A *bootstrap* corpus buys coverage a mutator would take too long to
reach alone — a transaction whose header, offsets, and lengths must all agree
before any body-walking code runs. A *regression* corpus is where a finding
lands: a committed reproducer replayed by `check` is how a fixed crash stays
fixed, and that holds whatever the format looks like. So "a mutator reaches this
format from scratch" is a sound answer to the first question and no answer at
all to the second — a target with nowhere to put a finding cannot satisfy the
seed-plus-named-test rule above. Expect a regression corpus to change no
mutation score; that is not what it is for. Where neither job applies, record it
with `declineSeedCorpus("...")`, under the same terms as a declined mutator: a
blank reason suppresses nothing, and a decline on a target that later declares a
corpus is reported as stale.

**Minimize the corpus with the tool, not by hand.** `fuzz<Target>Minimize`
wraps libFuzzer's `-merge=1`: it keeps only the inputs that add coverage,
smallest first. By default the only source is the committed seed corpus —
pure dedup, a no-op on a corpus whose every seed earns its place.
`-PadoptLocalCorpus` adds what the local `fuzz<Target>` runs accumulated
under `build/` as a second source, folding locally found interesting inputs
into the committed set — deliberately opt-in, because a long local campaign
can contribute megabytes of hash-named files, and because smaller local
inputs can displace a *named* seed whose coverage they replicate. The merge
writes to a staging directory and the seed corpus is replaced only from a
non-empty result, so a failed merge cannot wipe a committed corpus; seeds
whose content survives keep their committed file names (corpora name seeds
meaningfully — an account address, a minimized finding), and only genuinely
new inputs arrive under libFuzzer's hash names. Review the diff before
committing, and update the provenance README next to the corpus for anything
adopted or removed.

That name-keeping guarantee has one hole libFuzzer digs itself: any input
longer than `max_len` is silently truncated on load, so a committed seed
larger than the target's `maxLen` reaches the merge as a clipped copy with a
new hash — which would be adopted hash-named while the named original is
deleted, degrading exactly what the seed pins (a depth-bound probe fell below
the bound it probes). The same truncation applies on every `fuzz<Target>`
run: the campaign explores the clip, not the seed. Both tasks therefore
refuse up front when a committed seed exceeds `maxLen` — raise the cap to
cover the largest committed seed, or re-minimize the seed deliberately
*(casebook: the seed clipped by its own max_len)*.

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

### Local fuzz campaigns

Fuzzing is an explicit local campaign, not a scheduled-workflow obligation. Run every
target registered in a project with:

```shell
./gradlew --continue fuzzAll -PmaxFuzzTime=900 -PmaxParallelFuzzTargets=4 \
  --parallel --configuration-cache
```

`fuzzAll` derives its dependencies directly from the `hardening.fuzz` registrations,
so adding a target cannot leave a hand-maintained task list stale. It writes
`.pitest-history/local-fuzz.tsv` after all selected targets succeed. That machine-local
receipt deliberately survives `clean`, including a later `clean hardeningCertify`.
Starting the next campaign deletes it before any target runs and creates
`.pitest-history/local-fuzz.running`; failure or interruption leaves the sentinel and
no valid receipt, while success publishes the new receipt before clearing the sentinel.
An OS file lock held for the full aggregate invocation rejects a second `fuzzAll` before it
can overwrite the first campaign's sentinel or receipt; parallelism belongs inside one
owned campaign through `-PmaxParallelFuzzTargets`, not through competing Gradle processes.
The release runner requires the receipt without the sentinel and copies it into its
immutable bundle. `--continue` lets independent targets finish after one finds a failure;
Gradle still exits non-zero.
Run one `fuzz<Target>` directly for focused iteration.

`-PmaxFuzzTime` is a budget **for each target**, not for the aggregate.
`-PmaxParallelFuzzTargets` explicitly bounds concurrent fuzz children across all selected
projects (default `1`). Choose a value the machine can sustain; `4` is a reasonable review
starting point on a machine with at least four genuinely available cores. The configured
width is part of the receipt beside every achieved execution count, so a faster parallel
campaign is not mistaken for a serialized one. Do not combine mutation certification and
fuzzing in the same invocation: PIT and corpus rewrites retain their exclusive slot because
CPU saturation can turn mutation timeouts into load evidence.

A passing aggregate receipt proves work, not merely task completion. Each campaign target
must emit exactly one positive libFuzzer terminal `Done N runs in S second(s)` observation.
The typed task parses that count directly from the live child stdout/stderr while forwarding
the bytes unchanged; it does not recover evidence from a mutable Gradle log. The ignored
inner TSV and the release runner's immutable outer bundle bind every target's achieved
execution count and their exact total. Missing, duplicate, zero, inexact, or cross-layer
inconsistent counts or a mismatched parallelism declaration refuse the receipt. A standalone `fuzz<Target>` remains useful for
iteration but creates no aggregate proof.

A consumer's own fuzz certification must run every registered target against the exact
candidate plugin binary, invalidate stale aggregate receipts before execution, and retain
immutable, commit-bound evidence. A local campaign is sufficient; no soak window or scheduled
workflow is required. The `sava-build` owner instead records the relevant local adoption
passes already performed during development; its release mechanics live in the
[sava-build README](README.md#local-adoption-and-release-attestation), rather than being
copied into consumer or policy documentation.

`fuzzWorkflowInSync` remains only as a compatibility no-op for older consumer scripts.
It is not part of `check`, and the plugin neither requires nor validates a GitHub fuzz
workflow. A project may still schedule extra campaigns on its own terms, but that
schedule is not certification. Every finding still becomes both a committed seed and
a named regression test before the fix is considered complete.

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
stashed label, keyed line-lessly on `(class, method, mutator)` with statuses
compared as sorted multisets per key — the baseline's own key shape, so an
insurance row written here is a row the verify's comparison recognizes —
strictly stronger than sub-totals, and it names which key moved:

    ./gradlew <every pitest suite> pitestModeSnapshot -PpitestMode=solo -PnoMutationHistory
    ./gradlew qualityGate pitestModeSnapshot -PpitestMode=gate -PnoMutationHistory
    ./gradlew pitestModeCompare

Benign flips (`KILLED` <-> `TIMED_OUT`) are counted and tolerated. A flip
crossing the unkilled boundary is exactly the row the `TIMED_OUT` section
says belongs in the baseline: the compare fails naming each one unless it is
already insured there with the literal `flip insurance` marker — baseline
multiplicity alone is not persistent insurance, because an unmarked row can
become tomorrow's prune candidate. Siblings still count: per gated status a
key needs as many marked rows as the widest mode observed, and one row cannot
insure two flipped siblings. `pitestModeCompareUnion` first annotates deterministic
existing rows, preserving their family label, note, and `# line` tags, then
adds only a true multiplicity shortfall. Every selected row gains
`# flip insurance (<per-mode statuses>)` (or the same evidence appended to its
existing note), so a later reader can re-measure it and prune recognizes the
same literal marker. Newly added rows carry the lines observed in the
snapshots so the verify's row-level drift advisory covers them (an untagged
row would drop its whole key to the partial-tag fallback, weakening the check
for its siblings too). Two runs can match in total while disagreeing about
which mutants died; the headline number is not the check.

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
  only under multi-suite load. The report is refused rather than letting PIT's
  detected score turn infrastructure failure into certification. The refusal prints
  every offending CSV row, and `pitest<Suite>Debt` repeats those rows while falling
  back to the committed baseline for its read-only tally. Save that coordinate (or run
  Debt) before a quiet re-run replaces the report; a `RUN_ERROR` that persists at the
  same coordinate is not load and deserves investigation in the mutated bytecode.

**The evidence usually survives you discarding it.** The Gradle daemon keeps
complete build output — including PIT minion stack traces — at
`~/.gradle/daemon/<version>/daemon-<pid>.out.log`, even when the invoking
shell piped everything to `/dev/null`. Read it before recording any failure
as unexplained. The ratchet's missing-report failure prints this pointer, so
the recipe no longer has to be rediscovered per repo.

## Shared test scaffolding (generated)

`hardening.generateTestSupport = true` generates six small classes into the
test source set. They default to package `software.sava.hardening.support`; an
unrelated adopter should set `hardening.testSupportPackage` to a package it owns
to avoid a foreign or split package. They compile inside the consuming module so
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

The plugin has no package-namespace requirement. Any Java project can apply
`software.sava.build.feature.hardening`; the ArcMutate certificate described above is
an optional licensed PIT toolchain with its own, separate eligibility rules. A standalone
adopter must resolve the versioned plugin marker. A JUnit Platform consumer configures its
chosen engine normally, while a registered corpus additionally requires Jupiter for the
generated replay test. The minimal hardening-only build in the README shows that shape
without applying the other Sava conventions. Tool-bytecode releases follow the adopter's
Java toolchain, and the generated replay/support sources require Java 17+.

1. Apply `software.sava.build.feature.hardening` and register mutation suites
   (wildcard targets + exclusions) and fuzz targets. `hardeningInit` scaffolds
   the transcription: the `config/pitest/README.md` skeleton, the
   `.pitest-history/` git-ignore, and the adoption checklist with the current
   template digest. Run `./gradlew hardeningHelp` for the installed version's
   exact task and option surface.
2. Pin any unseeded randomness in the test suite (see above).
3. Seed the baselines with `./gradlew pitest<Suite>BaselineUpdate` per suite,
   review the written rows, and commit `config/pitest/`, including each suite's
   PIT-version and mutation-toolchain sidecars.
4. Review the `config/pitest/README.md` written by `hardeningInit`, then record
   accepted-mutant evidence (initially empty) and any seeded untriaged debt there.
5. Add the agent-instructions block below to the repo's `AGENTS.md` with the
   `hardening-template` marker. Run `./gradlew hardeningAgentTemplate` to print the
   exact block and digest carried by the installed plugin; `agentsTemplateInSync`
   points to the same task when the marker is missing or stale. Then decide who owns the pre-release
   `hardeningCertify` run: wire it into CI if the runners can afford it, otherwise
   record it as a release-checklist item run locally (see the lifecycle
   section) — and say which in `AGENTS.md`.
6. `hardeningInit` has already added `.pitest-history/` to `.gitignore`; leave that
   machine-local PIT-history and fuzz-campaign state boundary in place whether or not
   this repo is licensed. If the package,
   repository visibility, and entitlement all apply to an eligible public Sava repo,
   deliberately copy the `sava-build` repository-root certificate here and commit it
   as `arcmutate-licence.txt`. The plugin never distributes it. Never copy the Sava OSS
   certificate into GLAM, private `idl-src-gen`, or an unrelated project; all of them
   work without it using open-source PIT, or may supply a different licence that applies
   to them. Keep every private subscription download URL out of the repository and
   commit messages.
7. Register fuzz targets wherever arbitrary structured input can expose deeper parser,
   codec, state-machine, or round-trip behavior, and own an explicit local `fuzzAll`
   budget before release. A project with no meaningful fuzzable boundary may have zero
   targets — `fuzzAll` then records a valid zero-target campaign — but should record that
   judgment in its hardening notes. Scheduled GitHub campaigns are optional and are not
   checked by the plugin.
8. For a first-party `sava-build` consumer, add its canonical GitHub slug to
   `tools/fleet-manifest.txt` in the plugin repo. A checkout that happens to be
   absent during an ordinary canary is skipped; strict release certification
   refuses the omission, which is why the roster must be explicit.

## Agent instructions template

Copy into the repo's `AGENTS.md` (adjust file names). Run
`./gradlew hardeningAgentTemplate` to print the exact template baked into the installed
plugin version; do not copy a possibly newer block from moving `main`. The copies
drift, and a downstream block is an adapted snapshot, so no tooling can diff
cross-repo prose semantically. The plugin makes the drift **visible** instead of
trying: it carries a digest of this template's blockquote lines. When a root
`AGENTS.md` does not exist, `agentsTemplateInSync` (wired into `check`) warns and
prints the marker because adoption still requires the deliberate copy step above.
Once `AGENTS.md` exists, a missing or stale marker fails until the file contains
`<!-- hardening-template sha256:<digest> -->` acknowledging the release's template.
Thus editing the template below breaks already-adopted downstream checks on their
next plugin refresh, which is the point, and no list of downstream repos needs
maintaining anywhere. The marker is an acknowledgment, not a checksum of the local
block: update it only after re-diffing the block against the release-matched template
and syncing or **acting on** each changed bullet — a new requirement may mean new
code, not just new prose; that is how sava's corpus-replay gap went unnoticed until
an unrelated repo's agent tripped over it. The warning or failure prints the digest
to paste. One softening: under `-PsavaBuildLocalRepo` (the fleet canary, or any build
validating an unreleased checkout) a stale marker warns instead of failing — the repo
acknowledges a *released* digest and the checkout's has not shipped, so the marker
dance normally lands with the release, never before it. A deliberate RC-adoption
change may re-diff and stage the candidate block and marker now only when that consumer
commit will land with or after the published plugin pin it acknowledges; landing the
candidate marker while the older plugin remains selected would wedge ordinary checks.
A marker-less existing file still fails in local-repo mode; it is unadopted, not
merely waiting for a release.

> - **Scale verification to the change.** Iterate with the module's `test`
>   task; before handing off, run only the `pitest<Suite>`(s) whose mutated
>   code the change can reach — including suites in dependent modules that
>   call a changed API, and the owning suite for test-only edits (a weakened
>   test is exactly what the ratchet catches). The full `hardeningCertify` — every
>   suite freshly observed, serialized, provenance-bound, diffed against
>   `config/pitest/`, with strict timeout and ownership audits — is the pre-release
>   check, owned by CI or by the release checklist (this repo records which); it is
>   not the inner loop.
> - A new unkilled mutant has exactly three legal outcomes: **kill it** with a
>   test (prefer asserting the property it breaks over restating the
>   implementation), **refactor** it out of existence, or **accept it** with a
>   written reason in `config/pitest/README.md` **and a short family label on
>   the row itself** — refreshes seed new rows `# untriaged`, and triage means
>   replacing that label, so the baseline always says which rows are argued
>   and which are debt. Never run a baseline-update task just to make the build
>   pass.
> - **A mutant is a question, not a specification.** Before writing a killing
>   test, state the externally intended property and an oracle independent of the
>   current implementation: public contract, protocol specification, caller
>   invariant, reference implementation, or domain rule. If it contradicts current
>   behavior, first demonstrate the bug with a regression test that fails against
>   the unmutated code, then fix production; never add a passing assertion that
>   merely locks in the bug. At PR or handoff, report each nontrivial behavioral
>   cluster — not each mutant — as `Property: ... | Oracle: ... | Outcome: missing
>   assertion / production bug / accepted equivalent`. Test names and assertions
>   normally carry the durable property; comment only when the oracle or unusual
>   setup would otherwise be lost, and never embed PIT coordinates or line numbers.
> - Baseline keys are line-less (`class,method,mutator,STATUS`) — editing
>   above a mutated method churns nothing, and `# line` tags are review
>   metadata. A new mutant replacing a killed one at the same key can inherit
>   its acceptance, so treat a line-drift advisory whose written argument no
>   longer fits the code as that swap until shown otherwise. Use the installed
>   plugin's named writer tasks and heed their candidate previews; never hand-edit
>   record structure or provenance stamps. A PIT, PIT-plugin/tool-artifact,
>   ArcMutate-base, or certificate change uses `pitest<Suite>BaselineRebase`: it
>   preserves every old row, seeds new rows `# untriaged`, and stamps the reviewed
>   toolchain only after a successful fresh observation. Perform a schema
>   migration/rollback only with a fleet pin plan.
> - Consumer hardening notes contain only local ownership, measurements, acceptance
>   reasons, and provenance. `AGENTS.md` may carry this exact generated,
>   digest-pinned template plus those local facts, but no independently maintained
>   copy of plugin task semantics; use `hardeningHelp` and
>   `hardeningAgentTemplate` as the installed-version authorities.
> - **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
>   seconds instead of the full suite — then re-run unscoped before any
>   refresh; the tooling refuses to let a scoped report touch the baseline.
> - Identical baseline rows are sibling mutants of one compound condition and
>   the comparison is a multiset: never hand-dedupe. When one sibling
>   survives, the verify names the killed sibling's test — the survivor is
>   the opposite branch direction; triage it as its own mutant.
> - **Stubs and fixtures return distinguishable, non-default values.** A stub
>   returning null/0/""/true/empty makes the matching return-value mutant
>   equivalent by accident of the fixture — the clock non-zero-origin rule
>   generalized to every stubbed return.
> - **Copy-on-write clusters split by direction.** Assert immutability of
>   returned collections (`assertThrows(UnsupportedOperationException, ...)`)
>   at every size: the mutable-escape direction is a kill, not an acceptance;
>   only the content-equal siblings are family-accepted equivalents.
> - **Randomized tests use fixed seeds, and never sleep**: the ratchet needs
>   deterministic kills, and PIT re-runs the suite per mutant, so one real wait
>   costs minutes. Exploration belongs to the fuzz targets.
> - **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
>   detected and is not written to the baseline, and it is load-dependent — the
>   same mutant can report `SURVIVED` alone and `TIMED_OUT` under
>   `qualityGate`. Verify a baseline in both modes; union only rows observed to
>   flip, never every `TIMED_OUT` row.
> - **A new timed-out mutant is a reviewer-stop, not detection noise.** For
>   exactly these mutants the ratchet cannot see a weakened covering
>   assertion — a timeout keeps "detecting" whatever the test asserts — so
>   each suite's timeouts are an audited set, not a count:
>   `config/pitest/<suite>-timeouts.csv` holds line-less `class,method,mutator`
>   keys, and `config/pitest/README.md` the structural cause per member (the
>   removed loop exit, the reversed cursor, the leaked unlock). The verify
>   warns on any timeout outside the set — paste the printed row, then write
>   the cause — and on members matching no mutant; admit a newcomer only with
>   its cause written. The key is the check's resolution: a new timed-out
>   mutant in an already-audited method+mutator draws no warning, so name the
>   line in the README cause and re-read it when that code changes.
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
>   changing any baseline. Reproduce it under the relevant solo/gate loads,
>   inspect per-mutant coordinates, remove real waits, and move construction
>   coverage into the test body before deciding whether it is a product defect,
>   a load-dependent timeout, or a harness defect.
> - **Build the subject under test inside the test body, not in a field.**
>   Under `PER_CLASS` lifecycle a field-initialized client's construction
>   coverage attaches to whichever test runs first, so wiring mutants can
>   never pair with the test that drives what they wire — they survive even
>   under a harness that asserts every request. One test that constructs the
>   client in the test method and drives each configured URL restores the
>   pairing.
> - **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
>   arithmetic and receiver-returning fluent calls can be invisible to the
>   enabled defaults. Follow the plugin's trial advice per suite, enable only
>   mutators proved to fire, and record the measured numbers and declines.
> - Module-path and mutation-test service discovery can differ. Declare real
>   services in every runtime representation the project supports, probe the
>   active environment in test-only scaffolding, and never commit a harness
>   whose pass/fail result depends on which task launched it.
> - `SURVIVED` and `NO_COVERAGE` are different problems: the first is a
>   judgment call about equivalence, the second is usually an untested line
>   and is mechanical. Never accept a `NO_COVERAGE` mutant as "equivalent" —
>   you have not observed its behaviour. One structural exception: a block
>   that always exits by throw reads `NO_COVERAGE` forever, executed or not
>   (PIT probes a block at its end), and its return-value mutants can never
>   change status. Such a line is owed a test asserting the throw's contract,
>   not coverage — and never leave one untested fearing a covered-line
>   `SURVIVED` conversion, which would require the block to complete.
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
>   one usually means the run did less than you think. Read the task's evidence
>   markers and scope; only a fresh full certification may support a release.
>   The process itself needs no ArcMutate licence and applies to any Java package.
> - **Transient infra failures are not results.** PIT `MINION_DIED` fails
>   before writing a report, so it cannot corrupt one — re-run the suite; a
>   Gradle-worker `EOFException` death is the same shape, and a per-mutant
>   `RUN_ERROR` under load is the same shape smaller (the hardening parser refuses
>   the report rather than certifying PIT's detected score). The refusal and
>   `pitest<Suite>Debt` name every offending row; retain the coordinate before a
>   quiet re-run replaces the report, because the same coordinate twice is a defect,
>   not load. The daemon log
>   (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
>   full output even when the shell discarded it — read it before calling a
>   failure unexplained.
> - Fuzz findings become a committed seed input **and** a named regression
>   test, never just a fix — and the committed corpus is replayed by a unit
>   test inside `check`, so it cannot rot between fuzz runs.
> - **Run fuzz campaigns explicitly and locally.** `fuzzAll` is derived from every
>   registered target, so it cannot drift from a hand-written workflow task list;
>   set and record `-PmaxFuzzTime=<seconds>` and
>   `-PmaxParallelFuzzTargets=<count>` before release. Scheduled GitHub fuzz
>   workflows are optional and are not release evidence.
> - **When one thing has two representations, fuzz the differential.** Two
>   parsers for one config, an encode/decode round trip, a fast path beside a
>   reference path: assert the two *agree* rather than that neither crashes.
>   Crash-only fuzzing cannot see a wrong answer.
> - **Time-dependent code takes a clock**, so tests advance time instead of
>   waiting. Give test clocks a non-zero origin — a clock starting at 0 makes
>   every "start timestamp mutated to 0" mutant equivalent by accident.
