# Avocet implementation and maintenance guide

Updated: 2026-11-11. Baseline: **Avocet 2.0.0**, source `c130282`.

The [plan](IMPROVEMENT-PLAN.md) records shipped milestones and remaining F2–F5
work. The [contracts](IMPROVEMENT-CONTRACTS.md) define supported behavior and
future acceptance requirements. M1/M2 are implemented; this guide now explains
how to adopt, maintain and extend that foundation. Do not repeat the original
repair programme as though its APIs and tests were still missing.

**F1 is complete:** Spoonbill 10.0.0 was publicly released on 2026-11-10 from
`f0d456344359eb63ea4b138d28352860eef49f3b`, selecting Avocet core/events 2.0.0.
Its [release verification](https://github.com/natural-transformation/spoonbill/releases/tag/v10.0.0)
covered 26 artifacts and 27 passing recovery-consumer checks, with two
inapplicable law branches canceled.
The pre-release candidate runs in [IMPLEMENTATION-EVIDENCE.md](IMPLEMENTATION-EVIDENCE.md)
remain historical evidence; do not relabel them as published-artifact checks.
F3 browser automation and the F4 CSS, HTML-profile and churn work were
delivered on 2026-11-11 in PR #2 (sections 5–5.2), together with a fix for the
stale node identity that an F4 probe found. F2 real-service cases (section 6) remain.
The release verification does not close that coverage gap.

## 1. Choose the work and preserve its scope

1. Read `AGENTS.md`, the plan and the relevant contract section. Record source
   commits and working-tree state; preserve unrelated tracked and untracked files.
2. For maintenance, start from 2.0.0 and its regressions. For F2, use an
   isolated Spoonbill 10.0.0 or current checkout and record its exact commit.
   M3/M4 need the named application/widget evidence
   in the plan before implementation begins.
3. Add a focused regression for a new defect or a representative example for an
   API change. Observe the old behavior, implement the change and run relevant
   checks. Keep security fixes independent of optional feature work.
4. Report what evidence actually exercises: serializer output, JVM/JS core,
   native DOM, synthetic client transport or an actual Spoonbill service.
5. Follow repository recovery policy: re-plan after two failures of the same
   underlying operation and escalate after the next failure. A completed
   experiment that rejects a hypothesis is not a tool failure.

All dependency resolution, installation, builds, tests, local publication and
benchmarks use the applicable repository-local flake. Do not install tools on
the host or substitute another environment manager.

```bash
git status --short
git rev-parse HEAD
nix develop . --no-write-lock-file --command sbt projects
```

Current Avocet build: Scala 3.3.7, JDK 21, uTest/ScalaCheck, JVM and Scala.js.
Physical paths still use `levsha`; packages and test selectors use `avocet`.
Do not rename directories as incidental cleanup.

## 2. What is already implemented

| Area | 2.0.0 entry points | Maintenance evidence |
| --- | --- | --- |
| Core descriptions/interpreters | [Document](core/shared/src/main/scala/levsha/Document.scala), [RenderContext](core/shared/src/main/scala/levsha/RenderContext.scala), [StatefulRenderContext](core/shared/src/main/scala/levsha/StatefulRenderContext.scala) | Existing/custom interpreter and migration fixtures. |
| Lifecycle | [RenderRoot](core/shared/src/main/scala/levsha/RenderRoot.scala), [OwnedChanges](core/shared/src/main/scala/levsha/impl/OwnedChanges.scala) | `RenderLifecycleTest`, `OwnedChangesTest`. |
| Validation | [ValidatingRenderContext](core/shared/src/main/scala/levsha/ValidatingRenderContext.scala), [ValidationError/Validation](core/shared/src/main/scala/levsha/ValidationError.scala) | `ValidatingRenderContextTest`, browser rejection cases. |
| Typed authoring | [TypedKeys](core/shared/src/main/scala/levsha/dsl/TypedKeys.scala), [Policies](core/shared/src/main/scala/levsha/dsl/Policies.scala), [DSL](core/shared/src/main/scala/levsha/dsl/package.scala) | `TypedDslTest`, `OptimizerContractTest`, `ReviewFixesTest`. |
| Optimizer | [DslOptimizerMacro](core/shared/src/main/scala-3/levsha/dsl/DslOptimizerMacro.scala) | Plain/optimized traces, once-only evaluation and collection-before-callback order. |
| SSR encoding | [XHTML](core/shared/src/main/scala/levsha/impl/XhtmlRenderContext.scala), [HTML5](core/shared/src/main/scala/levsha/impl/Html5RenderContext.scala), [text helpers](core/shared/src/main/scala/levsha/text.scala) | `XhtmlRenderContextTest`, native parser/update cases. |
| Diff, bounds and snapshots | [DiffRenderContext](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala), [IdBuilder](core/shared/src/main/scala/levsha/IdBuilder.scala), [TapeValidator](core/shared/src/main/scala/levsha/impl/internal/TapeValidator.scala) | Diff/ID regressions, ordered replay, restore and factory compatibility cases. |
| Memory | [TrimPolicy](core/shared/src/main/scala/levsha/impl/TrimPolicy.scala), diff buffer metrics | `BufferTrimTest`, JVM allocation/retention and JS retention tests; [M2 baseline](bench/BASELINE.md). |
| Scala.js DOM | [Adapter](dom/src/main/scala/levsha/dom/package.scala), [performer](dom/src/main/scala/levsha/dom/DomChangesPerformer.scala), [EventTable](dom/src/main/scala/levsha/dom/EventTable.scala) | Four event-table unit tests and the 50-check native browser fixture (`scripts/test-dom-fixture.sh`). |

The historical T0–T5 and T7–T8 work packages are implemented and remain useful
evidence categories. T6 records candidate validation; F1 subsequently delivered
released adoption in Spoonbill 10.0.0. Targeted real-service coverage remains
outstanding under F2. T9/M3 keyed
identity and T10/M4 external ownership are deferred.
Explicit DOM event outcome types and automatic text normalization were not
delivered by T7; treat them as future API decisions, not missing source files.

## 3. Use the released APIs according to their boundaries

- Use `RenderRoot.render`/`renderWith` for the high-level lifecycle. Handle
  `Published`, `Rejected` and `Invalidated` distinctly. Partial backend mutation
  needs its defined reset/remount strategy; preparation rejection retains the
  previous baseline and misc entries.
- Keep `FastChangesPerformer` arguments synchronous and borrowed. Use
  `OwnedChangesPerformer` when results outlive the callback; its copying
  allocation is intentional.
- Use `Validation.interactiveHtml` with its supported HTML5/bootstrap profile.
  It diagnoses adjacent/empty text and other parser-sensitive constructs; it
  does not normalize them or implement the whole browser parser.
- Inspect completion/errors yourself for a standalone collecting validator.
  Collection forwards operations; `RenderRoot` checks before publication.
- Typed keys improve value/category safety. Generic strings and trusted helpers
  still require deliberate policy. URL checking is not an origin/destination
  guarantee, and CSS acceptance is not universal harmlessness.
- Keep HTML serialization and DOM setter values separate. Escape at serialization
  boundaries rather than pre-escaping data sent to native setters.
- Use `trim()` or `TrimPolicy.Automatic` deliberately for large-to-small workloads
  and release roots with `dispose`. Small update allocation is not constant
  retained memory.

Read the [release changelog](CHANGELOG.md) before upgrading. Saved format 2
rejects unversioned 1.0 buffers. Spoonbill 10 already quarantines invalid DevMode
render buffers with an `.invalid` suffix, reports their path and terminally
rejects/reloads the legacy session across reconnect/restart. A fresh page creates
a new session; if quarantine fails, the original file is retained and reported.
Follow the [Spoonbill 10 migration guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/migration-v10.md);
do not delete domain, authentication or presentation-recovery data. Old three-argument factory
and constructor signatures remain, but lifecycle/name checks and DOM exception
behavior are stricter. Future major versions are acceptable when a cleaner design
justifies them; document migration instead of promising universal compatibility.

## 4. Change workflow and tests

The swallowed property failure was repaired. Preserve that behavior: unexpected
exceptions fail tests, and generated counterexamples retain seeds and shrinking.
The existing [ReferenceDom](core/shared/src/test/scala/levsha/ReferenceDom.scala)
applies operations in order; [OpRecorder](core/shared/src/test/scala/levsha/OpRecorder.scala)
and the [recording performer](core/shared/src/test/scala/levsha/Change.scala) support
trace comparisons. Do not sort before executing the oracle or retain mutable
borrowed IDs/buffers in a recorder.

| Change | Checks to retain or add |
| --- | --- |
| Encoding/policies | XHTML/HTML5 output, real parsing and updates; ordinary values, adversarial delimiters and intended policy boundaries. |
| Equality/tape layout | Fixed collisions, exact names/values/subtrees, cursor reuse, size boundaries and saved-format validation. Hashes remain a rejection shortcut only. |
| Bounds | Both ID and hash stacks, sibling exhaustion, active-half growth, invalid capacities and restore inputs. Do not widen public IDs incidentally. |
| Lifecycle/DOM | Repeated render, failed preparation, partial performer failure, handlers, descendant cleanup, disposal and connected/disconnected callback reentrancy. |
| Combinators/optimizer | Plain/optimized traces, evaluation count/order, exceptions, dynamic receivers/callback factories and collection reuse. |
| Memory | Churn, many contexts, large-to-small trees, snapshots and disposal; allocation separately from retained buffers/indexes/closures. |

Existing focused suites can be selected directly:

```bash
nix develop . --no-write-lock-file --command sbt \
  'coreJVM/testOnly avocet.DiffRenderContextRegressionTest avocet.ReviewFixesTest avocet.OptimizerContractTest avocet.RenderLifecycleTest' \
  'coreJS/testOnly avocet.DiffRenderContextRegressionTest avocet.ReviewFixesTest avocet.OptimizerContractTest avocet.RenderLifecycleTest'
```

Add or substitute `ValidatingRenderContextTest`, `TypedDslTest`, `OwnedChangesTest`,
`BufferTrimTest`, `MigrationMatrixTest` and other affected suites. The property
object is `avocet.DiffProperties`, not its source filename. JVM-specific suites
include `FactoryBinaryCompatTest`, `AllocationGuardTest` and `RetainedMemoryTest`;
Scala.js has `JsRetainedMemoryTest`; DOM has `EventTableTest`.

Confirm discovery in the output. After focused checks pass, run the relevant
broader gate once; repeat only for changes, failures or unresolved concerns:

```bash
nix develop . --no-write-lock-file --command sbt +test domFixture/fastLinkJS
nix develop . --no-write-lock-file --command sbt 'bench/compile'
nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh
git diff --check
```

Root aggregation includes DOM unit and fixture projects, but linking does not
execute browsers; the `.#browser` script does (section 5). Benchmarks remain separate. Inspect new/untracked files too;
preserve unrelated work and formatting.

## 5. Run the browser fixture

F3 (implemented 2026-11-11) runs the
[Scala fixture](dom-fixture/src/main/scala/levsha/dom/fixture/DomFixture.scala)
and its [F4 checks](dom-fixture/src/main/scala/levsha/dom/fixture/ProfileChecks.scala)
in Nix-pinned headless Chromium and WebKit:

```bash
nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh
```

The `.#browser` shell adds GNU timeout and the Playwright driver with its matching
Chromium/WebKit bundle to the default tools. Both come from the locked nixpkgs
input, the same revision Spoonbill 10.0.0 pins; nothing is downloaded at run time.
[The script](scripts/test-dom-fixture.sh) links `domFixture` first, so the browsers
never load a stale module, and then runs two bounded steps:

1. [run-browsers.mjs](misc/dom-fixture/run-browsers.mjs) serves the repository root
   on an OS-chosen loopback port and loads the existing HTML page in each engine.
   It requires a well-formed `window.avocetDomFixture` report (any other published
   value, `null` included, fails) with exactly `EXPECTED_CHECKS` (currently 50)
   uniquely named results, all passing, and no page error, crash, failed request
   or failed document/script response. It prints every check, failing
   details, the engine version, the module timestamp and the source commit.
   Exit 1 is a check or protocol failure, 2 a setup failure, 130/143/129 an
   interruption.
2. [runner-self-test.mjs](misc/dom-fixture/runner-self-test.mjs) proves that the
   runner fails closed. In both engines it injects a failing check, a dropped
   result, results that never arrive, a `null` report, a malformed result entry
   and an uncaught page error; each run must exit 1 with its message. It interrupts Chromium with SIGINT and WebKit with
   SIGTERM while they wait for results. After every run, passing runs included,
   the browser's process group and the server port must be gone; the interrupt
   cases first observe both alive, so the cleanup probe is not vacuous.

Deadlines: 30 s browser launch, 15 s navigation, 30 s fixture, then 10 s for a
graceful close before the browser is killed. The script bounds its steps at
1200 s, 180 s and 300 s. A runner killed by that outer bound with SIGKILL cannot
close its browser itself; Playwright's exit hook is then the remaining cleanup.
Update `EXPECTED_CHECKS` with every added or removed check.

The [PR workflow](.github/workflows/pr.yml) runs the script after `sbt +test`. On
Linux the shell also supplies the pinned Mesa EGL vendor, applied to the owned
WebKit process only. That workflow used to target only `master`, which does not
exist, so pull requests to `main` ran no CI at all; it now targets `main` too.
Its first Linux run, on PR #2, passed: 50 checks in both engines and the
self-test. Snapshot publishing is manual: the
[snapshot workflow](.github/workflows/snapshot.yml), formerly `master.yml` with a
trigger on the nonexistent `master` branch, runs only from the Actions tab's "Run
workflow". It tests and then publishes `BaseVersion-SNAPSHOT`; releases go through
`release.yml`.

For manual inspection, link and serve the page:

```bash
nix develop . --no-write-lock-file --command sbt domFixture/fastLinkJS
nix develop . --no-write-lock-file --command node misc/dom-fixture/serve.mjs
```

Then open `http://127.0.0.1:8765/misc/dom-fixture/index.html` and stop the server
afterward. `dom/test` and `fastLinkJS` alone are not browser tests, and a synthetic
Spoonbill client run does not establish `avocet-dom` correctness.

### 5.1 F4 — CSS comment contract (implemented 2026-11-11)

[`CssValue`](core/shared/src/main/scala/levsha/dsl/Policies.scala) now rejects a
slash immediately followed by an asterisk anywhere outside a quoted string: inside
and nested within allowed functions, and as an unterminated opener. A CSS tokenizer
consumes `/*` before any other token, so `calc(4px/*2)` is a comment, not arithmetic.
`calc(100% - 10px)`, `calc(100% / 2)`, `calc(2*1px)`, grouping and top-level quoted
text containing literal comment delimiters stay accepted. The resource-loader,
escape, length and delimiter rejections are unchanged, and `CssValue.trusted`
remains the unchecked escape hatch. A lone `*/` is not a comment token and is
unchanged.

Regressions: `TypedDslTest` "CSS comments are rejected outside quoted strings;
arithmetic and quoted text are kept" failed before the fix on
`calc(1px/*note*/ + 2px)`. The fixture check "data-safe CSS keeps arithmetic and
rejects comments inside functions" also compares setter and SSR-parsed values in
both engines; with the fix reverted it fails in both.

```bash
nix develop . --no-write-lock-file --command sbt \
  'coreJVM/testOnly avocet.TypedDslTest avocet.XhtmlRenderContextTest' \
  'coreJS/testOnly avocet.TypedDslTest avocet.XhtmlRenderContextTest'
```

Any wider grammar change is a contract expansion; resolve it before editing
unrelated rules.

### 5.2 F4 — supported HTML and lifecycle evidence (implemented 2026-11-11)

[ProfileChecks](dom-fixture/src/main/scala/levsha/dom/fixture/ProfileChecks.scala)
compares the browser's parse of the interactive SSR (`Html5RenderContext`, no
pretty-printing) with direct DOM construction by the adapter. Its canonical form
keeps namespaces, sorted attributes, `style` as the browser's `cssText` and every
text node separately, so merged, dropped or moved text is a difference.

| Class | Cases | Required observation |
| --- | --- | --- |
| Supported | Void elements; mixed inline content and lists; whitespace-sensitive `pre`/`textarea`; explicit table sections; forms and buttons; SVG namespace; styles and delimiter-bearing data | Validated first render, update and update back: the DOM equals the parsed SSR each time. |
| Diagnosed | `VOID_ELEMENT_CHILD`, `PARAGRAPH_CLOSED_BY_BLOCK`, `NESTED_FORM`, `NESTED_ANCHOR`, `TABLE_IMPLIED_SECTION`, `TABLE_FOSTER_PARENT`, `ADJACENT_TEXT`, `EMPTY_TEXT`, `CARRIAGE_RETURN`, `PRE_LEADING_NEWLINE`, `RAW_TEXT_CONTENT`, `TEMPLATE_CONTENT` | `Validation.interactiveHtml` rejects with exactly that code and leaves the DOM untouched; the parse differs from direct construction. |
| Outside | XHTML serializer (`renderHtml`) output; pretty-printed HTML5 SSR | `<br></br>` parses as two `br` elements; pretty-printing adds whitespace text nodes. |

The same codes have deterministic JVM/Scala.js coverage in
`ValidatingRenderContextTest` and `ReviewFixesTest`. This bounded matrix leaves out
`NESTED_BUTTON`, `SELECT_CHILD`, `DOCUMENT_ELEMENT` and the root-level codes, and
it introduces no F5 normalization.

Churn: 100 cycles of mount, grow, shrink, remove and dispose with one to five
handler rows. After each step the handler and index counts equal their declared
values (`1 + n` and `1 + 2n`; `0` and `2` after removal; `0` and `0` after
disposal) and the root leaves the registry. Afterwards the 100 retired buttons
receive clicks and run nothing, and the current view runs exactly its two
handlers. These counts are deterministic bounds, not heap or allocation
measurements; no browser heap evidence was recorded.

Stale node identity, found by a probe and fixed on 2026-11-11 (contracts §1.4):
the adapter used the `vid` property on an event target, and on each removed
descendant, as its positional id. A removed node keeps that property, and a node
of another root carries the same ids. So a removed node that other code put back
under the root ran the current handlers of its old position, and removing a
subtree that held such a node dropped the live nodes' index entries, after which
the next update invalidated the root. `DomChangesPerformer.liveIdOf` now accepts
an id only when the index maps it back to that very node, for event targets and
for removal. [IdentityChecks](dom-fixture/src/main/scala/levsha/dom/fixture/IdentityChecks.scala)
covers a removed node put back, a node moved in from another root and a stale
node inside a removed subtree; all three failed in both engines before the fix.
Events from foreign content (no live id, for example widget-inserted children)
still dispatch no handler, not even an ancestor's; delegation from externally
owned children remains M4 work.

## 6. F2 — extend Spoonbill 10 real-service coverage

Start from an isolated Spoonbill 10.0.0 or current checkout. Its
[release-tag build](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/build.sbt#L5)
already selects `2.0.0`; do not repeat the F1 upgrade from 1.0.0. Use published
2.0.0 artifacts for baseline checks. For producer changes use a unique local
candidate as in section 7. **Do not publish locally over 1.0.0 or 2.0.0.**

```bash
# Isolated Spoonbill 10/current checkout; baseline needs no dependency edit.
nix develop . --no-write-lock-file --command \
  sbt 'show spoonbill/Compile/dependencyClasspath' 'spoonbill/test' 'testkit/test'
```

Verify that both core and events resolve to 2.0.0; record the consumer commit and
JAR paths. Check runtime/adapter classpaths too if a service reports mixed-version
linkage. Correct stale resolution before interpreting results. Historical 614/19
candidate test counts are not expected counts for the released Spoonbill timer
and component code.

Spoonbill's actual SSR path is its
[Html5RenderContext](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/main/scala/korolev/server/internal/Html5RenderContext.scala)
with pretty-printing disabled, not `renderHtml`. Its preset `k` identifier is
already delayed until styles are emitted. Preserve that shipped fix and the
[HTML5 regressions](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/test/scala/korolev/server/internal/Html5RenderContextSpec.scala).

Build F2 on the existing owned-service pattern in
[SensitiveAcknowledgementBrowserFixture](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/standalone/src/test/scala/korolev/server/SensitiveAcknowledgementBrowserFixture.scala)
and its [runner](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/scripts/test-sensitive-acknowledgement.sh). Add a
dedicated fixture/runner or focused extension that uses the real Scala service,
SSR/bootstrap and assembled browser client with synthetic application data.
The targeted F2 suite does not exist yet; these are implementation entry points.

| New case | Required observation in Chromium and WebKit |
| --- | --- |
| Long names | Legal long element/attribute names survive SSR parsing and actual patch updates without truncation or misaddressing. |
| Collisions | Use fixed collision witnesses from Avocet's [fixtures](core/shared/src/test/scala/levsha/HashCollisionFixtures.scala); distinct values/subtrees produce and apply the required update. |
| Style encoding | Delimiter/quote-bearing style and attribute data remains correctly separated during SSR and setter updates; no unintended handler/attribute appears. |
| SSR first patch | Bootstrap the actual HTML5 response, perform an ordinary client event, then assert the first server patch targets the correct parsed nodes and retains unaffected identity. |

Bound service startup, requests and browser execution, and clean up only owned
resources. Retain relevant timer/component ownership checks when state,
publication or cleanup is touched. Preserve the shipped invalid-buffer quarantine
and terminal-reload behavior, including existing `spoonbill.DevModeSavedRenderContextSpec`
and `spoonbill.server.internal.services.InvalidDevRenderContextSpec` regressions.

Existing checks from the isolated Spoonbill checkout:

```bash
nix develop . --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command \
  bash scripts/test-sensitive-acknowledgement.sh
```

The first two checks use synthetic client transport. The last starts an actual
Scala service but lacks some targeted F2 cases. Follow
[Spoonbill's browser guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/browser-tests.md) for other applicable
service/authentication checks. Regenerate protocol constants only when their
Scala declarations change; no move opcode shipped in Avocet 2.0.0.

Finish F2 by recording the exact producer/consumer versions, service and browser
commands, discovered cases and per-engine results. Published-artifact consumer
checks and synthetic-client passes cannot substitute for these targeted real-service
assertions. Do not alter released artifacts or tags.

## 7. Test future Avocet candidates

For changes beyond 2.0.0, choose a unique snapshot for the exact producer commit
and iteration, and publish only to the local resolver:

```bash
# Avocet checkout; replace the example with a unique candidate version.
env RELEASE_VERSION='2.0.1-PRODUCER_SHA-f4-ITERATION-SNAPSHOT' \
  nix develop . --no-write-lock-file --command \
  sbt 'show coreJVM/version' 'show eventsJVM/version' \
  'coreJVM/publishLocal' 'eventsJVM/publishLocal'
```

Replace `PRODUCER_SHA` and `ITERATION` before running the command; the example
is a unique-version template, not a selected public release. Select that exact version in an isolated
Spoonbill 10/current consumer, verify resolved artifacts and
run section 6's relevant checks. Local publication is unnecessary for adopting
the published 2.0.0 release and is not a remote release. Source edits alone do
not change a consumer's dependency selection.

## 8. Maintain the long-term design

M2 is shipped. Extend one coherent implementation rather than layering competing
lifecycle, identity or policy models over it.

Spoonbill's [portable UI recovery work](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/portable-ui-recovery-plan.md)
uses generic consumer/provider interfaces and user-supplied database adapters.
Keep backend clients, ownership admission and presentation projection outside Avocet; do not substitute serialized render
buffers for the application's explicitly selected recovery state.

- Prototype API changes with an unchanged old consumer, opt-in new consumer,
  custom context, custom performer, Scala.js app and Spoonbill. Test output,
  events and cleanup as well as compilation.
- Keep rendering effect-independent and thread-confined. Preserve coherent
  publication, failure invalidation and disposal ownership; typed wrappers do
  not remove the need for runtime checks.
- Keep operation semantics separate from tape/transport layout. Decide saved-buffer,
  source/binary, bootstrap and wire compatibility independently.
- Every new combinator needs a tested lowering or correct documented fallback.
  Preserve input-before-callback ordering, once-only dynamic evaluation and
  exception timing; measure inference, compile cost and generated size too.
- Future text normalization must coordinate every interpreter and bootstrap
  consumer. Current diagnostics are not normalization. Explicit DOM event outcome
  types likewise need migration from the still-supported legacy callbacks.

## 9. Performance and retained memory

The [frozen M2 baseline](bench/BASELINE.md#m2-frozen-baseline-2026-11-06) and JVM/JS
allocation/retention tests already exist. Use their recorded environment/workload;
take a fresh comparable baseline when conditions change. Do not relabel historical
working-tree measurements as new measurements of published artifacts.

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffScenariosBenchmark.*'
```

Investigate comparable average-time regressions above 5% and new allocation
proportional to node count. A p95 target needs its own latency baseline. Separate
engine, serialization and backend costs; the older reporting-performer benchmark
does not isolate engine allocation.

Measure JVM and Scala.js separately. Distinguish allocation from retained buffers,
indexes, callbacks and DOM nodes, with deterministic capacity/churn checks alongside
heap observations. Patch-byte/service measurements and automated browser retention
checks remain separate work; a forced-GC sample does not establish them. Preserve
exact correctness even if it costs CPU.

## 10. Migration and release checklist

For each new delivery, record the actual source/binary, saved-buffer, bootstrap
and wire compatibility impact. Keep the old/new consumer and custom-interpreter
fixtures, and add focused cases when a boundary changes. A major release permits
a deliberate migration, not an undocumented change in meaning.

Tie release notes and benchmark claims to exact artifacts and test evidence.
F1's Spoonbill 10.0.0 delivery is complete; track any later dependency release
separately from Avocet 2.0.0 and the historical candidate evidence.
Do not replace already-released artifacts or tags, and do not treat a local
snapshot's successful tests as proof of the published artifact pair.

## 11. Conditional feature work

F5's text normalization/bootstrap handling and explicit DOM event outcomes remain
conditional API decisions. Require representative consumers and migration
evidence before changing either contract; they are not prerequisites for F2–F4.

M3 requires a real screen with a reproducible reorder/state-preservation need.
Compare stable handles with positional remapping before choosing an algorithm
or protocol; cover node reuse, retirement, stale events and memory in the decision.
Backend capabilities and client compatibility must be settled before emitting moves.

M4 requires a real widget and explicit container/descendant ownership with
mount/update/dispose/recovery evidence. It is not a raw-HTML shortcut or sandbox.
Keep both milestones deferred if demand is absent; security, type safety and
memory maintenance continues regardless.

## 12. Evidence, review and handoff

For a new change, record:

```text
Follow-up/defect/contract IDs:
Producer and consumer commits/releases/candidate version:
Changed behavior and compatibility impact:
Regression before fix: command, expected failure, observed result
Validation after fix: command, pass/fail, relevant assertions
Browser path: SSR parser / Scala.js adapter / real Spoonbill service
Memory/performance evidence, when relevant:
Remaining gap and next action:
```

Keep the evidence file and original review as historical records. Add dated
results rather than rewriting candidate provenance. The 2.0.0 changelog and
README already describe the released API and scoped memory model; update them
for actual changes or specific remaining discrepancies.

Before completion, verify relevant tests are discovered and pass, migration and
cleanup behavior are covered, the consumer runs the intended artifacts and
unresolved gates are explicit. Publishing, pushing, merging and deploying remain
separate authorized release actions. This documentation refresh performs none
of them and does not rerun builds or benchmarks.
