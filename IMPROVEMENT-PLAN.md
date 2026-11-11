# Avocet improvement plan

Status: release baseline and follow-up roadmap, revision 4. Updated: 2026-11-11.

Priorities: **security, type safety, memory efficiency, flexible functional interfaces**.

## 1. Released baseline

[Avocet 2.0.0](https://github.com/natural-transformation/avocet/releases/tag/v2.0.0)
shipped M1 and M2 on 2026-11-06 at commit
`c130282f48c600ae3e6afc8e89babb59ef1943ca`. The library implementation is the
reviewed `921de4d`; the release commit changes versioning and release documentation.
Use the [changelog](CHANGELOG.md), [implementation evidence](IMPLEMENTATION-EVIDENCE.md)
and [benchmark baseline](bench/BASELINE.md) for delivered behavior and recorded checks.

[Spoonbill 10.0.0](https://github.com/natural-transformation/spoonbill/releases/tag/v10.0.0)
completed published adoption on 2026-11-10 from commit
`f0d456344359eb63ea4b138d28352860eef49f3b`. Its
[release build](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/build.sbt#L5)
selects **Avocet 2.0.0** for core/events. All 26 Spoonbill modules were verified
on Maven Central; the isolated public recovery-kit consumer passed 27 tests,
with two inapplicable law branches canceled. These consumer laws establish
recovery-kit packaging/protocol evidence, not the targeted renderer coverage in F2.
The release page and its attached verification report record this evidence.
**F1 is complete.** Spoonbill 9.0.0's selection of Avocet 1.0.0 is historical.

The [contracts](IMPROVEMENT-CONTRACTS.md) describe the supported 2.0 baseline,
continuing requirements and explicitly deferred features. The
[implementation guide](IMPLEMENTATION-GUIDE.md) is now a maintenance and next-change
guide. The [original review](IMPROVEMENT-PLAN-REVIEW.md) remains historical evidence
against Avocet 1.0.0-era sources; it is not a current defect list.

## 2. Architectural decisions retained

- **Exact output is mandatory.** Hashes accelerate rejection of unequal values;
  matching hashes do not prove equality. The problematic header cache was removed.
- **Security is context-specific.** Encoding, name syntax, URL/CSS policies and
  application authority are distinct. Typed values and validators are not universal
  sanitizers; trusted escape hatches remain explicit.
- **One rendering algebra, several interpreters.** Keep composable descriptions,
  an effect-independent core and compact buffer-based diffing. Avoid requiring an
  allocated object tree on every render.
- **Lifecycle and ownership are explicit.** `RenderRoot`, owned changes and DOM
  disposal provide the new foundation. Failed preparation retains the published
  baseline; partial DOM application cannot promise rollback.
- **Type safety and memory efficiency remain ongoing design requirements.** Use
  precise types and bounded ownership, measure allocation and retention separately,
  and preserve ordinary/optimized evaluation semantics when extending the DSL.
- **Identity remains positional in 2.0.0.** Logical keys, stable handles and tree
  position must be separated if M3 proceeds; no keyed/move API shipped.
- **Support both adapters deliberately.** Avocet's Scala.js DOM backend and
  Spoonbill's remote backend need their own tests. Passing one does not validate
  the other.

Forms, authentication, timers, persistence and HTTP remain outside Avocet.
Spoonbill owns view authority, admission, recovery and transport policy. A future
breaking release is acceptable when it produces a cleaner design and includes
an explicit migration; compatibility must not preserve unsafe behavior.

Spoonbill 10.0.0 includes [portable UI recovery](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/portable-ui-recovery-plan.md)
through generic payload/provider interfaces. Inline PostgreSQL snapshots remain
the default; no Cassandra, Redis or other named payload adapter is required.
Keep database and recovery coordination outside Avocet. Production rollout and
datastore qualification remain application work, not prerequisites for F2–F4.

## 3. Completed repairs and delivered APIs

The original D1–D6 identifiers are retained for links from the changelog. These
are repaired baseline defects, not work to implement again.

| ID | Delivered in 2.0.0 | Regression/evidence entry points |
| --- | --- | --- |
| D1 | SSR style values are encoded for the surrounding attribute; dynamic names are checked. | `XhtmlRenderContextTest`, browser SSR/update cases. |
| D2 | Thread-local header-cache confusion is eliminated by removing that cache. | `DiffRenderContextRegressionTest`, same-name attribute/style and collision cases. |
| D3 | Headers support the declared 1024-character name limit using checked 16-bit name lengths. | Long-tag, attribute/style-name and boundary regressions. |
| D4 | Tag, text, attribute and subtree equality checks verify bytes after hash matches. | Fixed collision fixtures and ordered replay properties. |
| D5 | IDs, depth, lengths and buffer growth are checked; saved format 2 is validated. Capacity 1 is rejected. | ID/buffer regressions, `ReviewFixesTest`, malformed-restore tests. |
| D6 | The DOM adapter renders repeatedly, preserves handlers, cleans descendant indexes, disposes roots and handles the reviewed reentrancy cases. | `EventTableTest`, the 23-check `domFixture`. |

M2 delivered `RenderRoot`/`RenderOutcome`, `OwnedChange`/`OwnedChangesPerformer`,
`Validation`/`ValidatingRenderContext`, typed attribute/style keys, URL/CSS policy
values, explicit trusted helpers, composition helpers and `TrimPolicy`.
Migration, optimizer, allocation and retained-memory tests accompany them.

Important limits remain: validation is optional and table-driven, not a complete
HTML parser; interactive validation diagnoses problematic text segmentation rather
than normalizing it; generic `renderHtml` still uses XHTML serialization; and
DOM callbacks retain their legacy `Any`/Boolean result convention. Do not interpret
“M2 shipped” as “every broader acceptance goal has been proved for every input.”

## 4. Delivery milestones

| Milestone | Current status | Next action |
| --- | --- | --- |
| M1: repaired baseline | Shipped in Avocet 2.0.0; adopted by Spoonbill 10.0.0 | Maintain the repairs and strengthen targeted service/browser coverage in F2. |
| M2: durable architecture and API | Shipped in Avocet 2.0.0 | Maintain the foundation; close documented coverage gaps and evaluate explicit follow-ups without rebuilding the API from scratch. |
| M3: keyed identity and moves | Deferred; demand gate not met | Name a real application screen and a reproducible reorder/state-preservation requirement before choosing identity or protocol changes. |
| M4: external subtree ownership | Deferred; demand gate not met | Name a real widget and its mount/update/dispose/recovery needs before adding the ownership API. |

Security, type safety, lifecycle clarity, memory bounds and migration quality
remain required for every subsequent change. They are not conditional on demand
for M3/M4. Stable handles are still an experiment to evaluate when M3 begins,
not an announced feature of 2.0.0.

## 5. Follow-up status and next implementation batch

| Follow-up | Status and remaining scope | Completion evidence |
| --- | --- | --- |
| F1: released Spoonbill adoption | Complete in 10.0.0. Preserve its renderer, timer/component, saved-buffer and migration regressions. | Published core/events 2.0.0 selected by Spoonbill; consumer release and public-artifact verification recorded above. |
| F2: repair-specific service fixtures | Open. Spoonbill has SSR/preset-ID unit tests, failed-render recovery tests and invalid-buffer service tests. Add explicit long-name, known-collision, style-encoding and SSR-first-live-patch cases through its real service/client. | Browser assertions inspect parsed SSR and subsequent live patches in Chromium/WebKit using the selected renderer artifacts. Existing synthetic transport or unit checks alone do not close this gap. |
| F3: repeatable browser automation | Complete (PR #2, merged 2026-11-11 as `7b0d249`): a `.#browser` shell, `scripts/test-dom-fixture.sh`, a fault-injection/interruption self-test and a PR workflow step. The fixture passes in Chromium 141 and WebKit 26 on aarch64-darwin and in the first Linux CI run. The PR workflow previously targeted only the missing `master` branch and now also targets `main`. | Nix-managed Chromium/WebKit run the linked Scala.js DOM adapter, fail on missing/partial results or page errors, and clean up owned servers/browsers under finite timeouts. |
| F4: conformance and documentation follow-through | Complete (PR #2, merged 2026-11-11): comment rejection in `CssValue` with JVM/JS and browser regressions, a 21-case supported/diagnosed/outside HTML matrix and 100-cycle churn checks in both engines. A probe found stale node identity in the DOM adapter (contracts §1.4); it is fixed with three regressions, for 50 fixture checks. No browser heap evidence was recorded. | Targeted regressions and documentation agree; resource/index/handler bounds are demonstrated without inferring heap or allocation claims from counts alone. |
| F5: future API decisions | Conditional. Evaluate text normalization/bootstrap handling and explicit DOM event outcomes with representative consumers. Neither is already shipped. | A design decision and migration evidence; this is separate from the F2–F4 hardening batch. |

Use [IMPLEMENTATION-GUIDE.md](IMPLEMENTATION-GUIDE.md) as the entry point. F3 and
F4 were delivered on 2026-11-11 in that order; F2 on Spoonbill's backend is next.
The original batch plan follows: execute
F3 first to make the existing DOM checks repeatable; then deliver focused F4 and
F2 changes using their respective backends. A small JVM/JS policy regression may
be developed independently while F3 is being built. The batch should:

1. Reuse the existing fixture/result protocol and Spoonbill's Nix/browser patterns,
   without claiming an Avocet browser command exists before implementing it.
2. Fix the narrow CSS comment discrepancy. The source currently admits `/` and
   `*` inside functions despite the comment-rejection claim. Recommend rejecting
   actual comment tokens outside quoted literals while retaining arithmetic such
   as `calc(100% / 2)` and `calc(2 * 1px)`. Add regressions before changing behavior;
   no exploitable bypass has been demonstrated by this documentation review.
3. Exercise the real Spoonbill HTML5/bootstrap/first-patch path, including valid
   long names and fixed hash-collision inputs. Preserve existing acceptance,
   event, component and timer behavior; do not implement the repaired engine again.
4. Add repeated mount/update/dispose checks using the DOM fixture's existing
   handler/index diagnostics. Report heap measurements separately when supported.

F2–F4 completion requires the commands, engines, test discovery and failure-path
evidence specified in the guide and contracts. It does not require keys, widgets,
text normalization, new database adapters or an application deployment.

Patch-byte measurements and additional workload coverage remain useful when
integration or performance work requires them. Spoonbill's
[latency report](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/event-latency-performance.md#remaining-limits)
leaves realistic write mixes, hot accounts, real browser rendering and external
store deployments to be measured. Choose a representative repeated workload
before altering realm locking or transaction structure; do not restart the full
historical benchmark programme merely to close F2–F4. Use the frozen M2 renderer
measurements as scoped historical evidence and refresh them when conditions change.

## 6. Evidence and migration

The historical Avocet implementation evidence records the second-review checks:
166 uTest JVM cases plus three ScalaCheck properties, 158 uTest Scala.js cases
plus three properties, four DOM unit tests and a 23-check Chromium fixture.
It also records candidate validation in an isolated Spoonbill checkout, including
real-service Chromium/WebKit checks. Those were pre-release candidate runs, not
a validation of the two published artifacts as a pair. Subsequent delivery is
recorded by Spoonbill 10.0.0's release and
[verification checklist](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/remaining-work-after-portable-recovery.md):
1,541 Scala tests, targeted invalid-buffer recovery, and real-service browser
handoffs were recorded during integration/release preparation. Public publication
and the isolated released-kit consumer were verified on 2026-11-10. The source
checklist was committed before publishing; use the release page for those final
external gates. Do not relabel historical benchmarks as newly rerun measurements.

The 2.0 release rejects unversioned 1.0 saved render buffers. Spoonbill 10.0.0
quarantines invalid legacy development buffers to `.invalid` siblings, reports
the affected path and returns a terminal full-page reload. The marker and failed
session prevent an old connection from adopting a fresh baseline after reconnect
or restart; guarded sessions bypass these buffers. Follow its
[migration guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/migration-v10.md), preserving application,
domain and authentication data. Legacy three-argument
`DiffRenderContext` factory/constructor signatures remain, but stricter lifecycle
and name checks and DOM exception behavior still require migration attention.
The positional bootstrap and wire protocol did not acquire move operations.

Use the [current validation commands](IMPROVEMENT-CONTRACTS.md#9-validation-commands-and-environment)
and [Spoonbill candidate procedure](IMPROVEMENT-CONTRACTS.md#92-spoonbill-candidate-integration).
For future Avocet changes, publish a unique local snapshot, never overwrite a
released artifact, and verify the consumer's resolved JARs before interpreting
results. Updating these documents does not modify or release either library.

## 7. Acceptance for future changes

Retain the [acceptance matrix](IMPROVEMENT-CONTRACTS.md#8-acceptance-matrix),
[memory requirements](IMPROVEMENT-CONTRACTS.md#5-memory-and-lifecycle-engineering)
and [compatibility rules](IMPROVEMENT-CONTRACTS.md#10-compatibility-and-migration).
Record commands, outcomes, seeds and exact candidate versions. Keep source
inspection, JVM probes, browser behavior and measurements distinct.

Stop a feature that misroutes events, weakens Spoonbill admission, retains
unbounded state or adds unjustified architectural complexity. Measure the cost
of correctness instead of weakening it to meet a speed target. Prefer focused
improvements to the shipped foundation; keep larger redesigns tied to evidence
and a clean long-term model.
