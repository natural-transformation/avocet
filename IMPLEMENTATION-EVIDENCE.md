# M1 implementation evidence

Date: 2026-11-05/06. Follows the record format of
[IMPLEMENTATION-GUIDE.md §12](IMPLEMENTATION-GUIDE.md). Producer commit for
every package: Avocet `baeeea2fcabde15c07b89f29c9fb9393cb9703fd` plus the
uncommitted working tree described in [CHANGELOG.md](CHANGELOG.md).
Environment: repository `flake.nix` (`nix develop . --no-write-lock-file`),
sbt 1.10.7, Scala 3.3.7, JDK 21.0.8 (Azul), Node from the flake, macOS
(Darwin 27.0.0), built-in Chromium 152 for the browser fixture.

Baseline before any change (`sbt projects +test`): JVM 25 passed, JS 25
passed. That run established only that the old suite was green; the known
defects were unreachable by it (see the review).

Final run (`sbt +test`, 2026-11-06 07:31): coreJVM 76 passed (73 utest +
3 ScalaCheck properties), coreJS 76 passed, dom 4 passed, eventsJVM/JS no
tests, domFixture compiled and linked. `bench/compile` passes.
Performance guards and the allocation guard: see "T8 (partial)" below.

## T0: trustworthy tests

- `DiffProperties`: the catch-and-return-true property is gone. Three
  properties now run: ordered replay of the diff reproduces the new tree
  (`ReferenceDom` oracle), self-diff emits nothing, and the existing sorted
  change-set comparison plus ordered replay. Generators include long names,
  same-name attribute/style pairs and the fixed collision fixtures.
- Two latent generator flaws surfaced by the richer fixtures were fixed in
  the generator (identical text replacement; a remove under a removed subtree).
  Failing seeds were `baLVZ3UZPGfLCFswlKcgTXVuDxrOUUlohNYWjJ3LKNM=` (JS) and
  the 13th case of the JVM run; both now pass on both platforms.
- `OpRecorder` + `DslTraceTest`: optimized and plain traces are identical for
  the complex template, SVG namespaces, conditionals/match forms, and dynamic
  expressions evaluate exactly once in both forms.
- Regression before fix: `DiffRenderContextRegressionTest` was written against
  the old engine; the D1–D5 cases fail there with the review's symptoms
  (`BufferOverflowException` for 10-char tags, empty change sets for `Aa`→`BB`
  and `t86388`→`t118091`, `1_-25536` ids). The old engine is the committed
  `baeeea2`; rerun the suite on it with `git stash` to reproduce.

## T1: D1 SSR style encoding

- Changed: `XhtmlRenderContext.setStyle` escapes values; all three
  interpreters validate names (`NameValidator`); raw-call ordering violations
  throw.
- Validation: `XhtmlRenderContextTest` (11 tests, JVM and JS): the probe
  `color @= "red\" onmouseover=\"alert(1)"` serializes as one `style`
  attribute on XHTML and HTML5; invalid names rejected; custom elements,
  `xlink:href`, `--custom` accepted.
- Browser path: the DOM fixture parses the HTML5 output with `DOMParser`:
  exactly `style` and `title` attributes, no `onmouseover`, no script;
  `width:10px` survives; the DOM-update path applies the same values through
  setters, the invalid `color` value is rejected by CSSOM, `title` is exact.
  Command and result in T5.

## T2/T3: D2 header cache, D3 long names, D4 exact equality

- Changed: header cache removed; 16-bit name lengths; relative node lengths;
  `regionsEqual` after every hash match (tags, texts, attribute names and
  values, whole subtrees); `Hashing` seam; `finalizedRootSum` read-only seam.
- Validation: `DiffRenderContextRegressionTest` "D2", "D3", "D4" groups (JVM
  and JS). Collision fixtures: `Aa`/`BB` (names), `t86388`/`t118091`
  (values), `s121742`/`s143709` (`div(text)` subtree sums; found once by an
  offline search over 143 709 candidates, hard-coded, asserted to still
  collide). Warm-up order across contexts covered.
- Saved buffer compatibility: format v2 with validation; old format rejected.

## T4: D5 checked limits and restore validation

| Resource | Limit | Test |
| --- | --- | --- |
| Siblings per level | 32767 (`IdBuilder.MaxSiblings`) | 32767 accepted, 32768th throws, ids untouched |
| Nesting | 255 (`DiffRenderContext.MaxDepth`); `HashBuilder.Stack` checked too | 255 accepted, 256th throws |
| Name length | 1024 chars | 1024 accepted, 1025 throws (tag, attr, style) |
| Text/value length | 2^24 chars | checked before writing (not exercised at the boundary) |
| Buffer | power-of-two ≥ 2, ≤ 1 GiB; active half sized | invalid capacities rejected; 2-byte initial buffer grows correctly |
| Restored tape | magic, version, byte order, lengths, opcodes, namespaces, balance, node lengths | malformed snapshots rejected (6 corruptions + truncation) |

Restoration copies the source and leaves the caller's position unchanged
(tested). After a failure mid-node or mid-attribute, `reset()` restores a
usable construction state and the old baseline still diffs correctly (tested).

## T5: D6 Scala.js DOM adapter

- Changed: `avocet.dom` lifecycle (prepare → applying → commit / failed),
  `EventTable` (all handlers kept, legacy stop rule), capture-phase native
  listeners, descendant index cleanup, `dispose(target)`, reentrancy
  rejection, root id = target element.
- Node tests: `dom/test` → `EventTableTest` 4 passed.
- Browser fixture (`domFixture` project, `misc/dom-fixture/`):
  ```bash
  nix develop . --no-write-lock-file --command sbt domFixture/fastLinkJS
  nix develop . --no-write-lock-file --command node misc/dom-fixture/serve.mjs
  # open http://127.0.0.1:8765/misc/dom-fixture/index.html
  ```
  Result 2026-11-06 in Chromium 152 (desktop app browser pane): page title
  `PASS avocet dom fixture: 17 checks, 0 failed`. Checks: first/second/third
  render, growth after shrink, several handlers in order, non-bubbling focus,
  propagation stop, replaced-subtree handlers gone, submit prevented,
  throwing template keeps baseline and handlers, next render diffs against
  the retained baseline, DOM write failure → failed → dispose recovers,
  dispose idempotent and complete, remount after dispose, reentrant render
  rejected from a custom-element callback, SSR parse, DOM update.
  The first fixture run found and fixed a real defect: the root node (id 1)
  was not mapped onto the target element.
- Not automated: the fixture is loaded manually; no headless runner is in
  this repository's flake. Spoonbill's Playwright shell could drive it later.

## T6: candidate integration

Isolated consumer checkout: a detached git worktree of Spoonbill at
`10ff271c` (the maintainer's current head; the plan named `ac74b78d`) in
`../spoonbill-avocet-candidate`, with `avocetVersion` set to the candidate.
The maintainer's own checkout and its staged work were not touched.

M1 candidate, published with the repository flake:

```bash
env RELEASE_VERSION=1.0.0-m1-baeeea2-SNAPSHOT nix develop . --no-write-lock-file --command \
  sbt 'show coreJVM/version' 'show eventsJVM/version' 'coreJVM/publishLocal' 'eventsJVM/publishLocal'
```

| Check (Spoonbill worktree, 2026-11-06 09:39) | Result |
| --- | --- |
| `show spoonbill/Compile/dependencyClasspath` | resolves `~/.ivy2/local/com.natural-transformation/avocet-core_3/1.0.0-m1-baeeea2-SNAPSHOT/jars/avocet-core_3.jar` and the matching `avocet-events_3.jar` |
| `spoonbill/test` | 614 succeeded, 0 failed |
| `testkit/test` | 19 succeeded, 0 failed |
| `node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs` | 61 pass, 0 fail (synthetic transport; client regression evidence only) |
| `bash scripts/test-sensitive-acknowledgement.sh` (real standalone Scala service, pinned Chromium and WebKit) | PASS in both engines |

The real-service run exercises the candidate renderer through Spoonbill's
HTML5 SSR path and live patches with its actual client. Repair-specific
real-service cases (long names, style encoding, collision-driven updates)
belong to Spoonbill's fixture suite and are a Spoonbill change; the renderer
behavior they would cover is established by the core and browser-fixture
tests above. Delete a stale dev-mode saved render buffer once after
upgrading (format v2).

M1+M2 candidate (`1.0.0-m2-baeeea2-SNAPSHOT`): see the final section below.


## T8 (partial): performance regression guards

- `DiffPerformanceGuardTest` (JVM and JS): self-relative guards on scaling
  (unchanged diff linear in size, deep trees, many-attribute quadratic bound,
  growth-then-shrink cycle cost, SSR linear) and on diff cost relative to a
  render. Thresholds are wide on purpose; they catch algorithmic regressions.
- `AllocationGuardTest` (JVM): bytes allocated per render+diff cycle via
  `ThreadMXBean`; raw interpreter 1000-node cycle < 1 KB, optimized static
  template cycle < 2 KB. Both pass on the repaired engine.
- `DiffScenariosBenchmark` (JMH, `bench`): unchanged, single text/attr
  change, deep, many attrs, long names, colliding values, grow-then-shrink,
  render-only and static HTML5, with a counting performer. Baseline numbers
  and the exact command are in [bench/BASELINE.md](bench/BASELINE.md).
- Not done: M2 retained-heap measurements and Spoonbill patch-byte counts.

## Outstanding after M1

- Release: versioning, remote publication and the Spoonbill dependency
  update are maintainer actions. Consumer validation is in T6 above.


# M2 implementation evidence

Date: 2026-11-06. Producer: Avocet `baeeea2fcabde15c07b89f29c9fb9393cb9703fd`
plus the uncommitted working tree (M1 and M2 together; see
[CHANGELOG.md](CHANGELOG.md)). Environment as above: repository `flake.nix`
(nixpkgs `c0b0e0fddf73fd517c3471e546c0df87a42d53f4`), sbt 1.10.7, Scala
3.3.7, JDK 21.0.8 (Azul), Node from the flake, macOS 27.0.1 on Apple M4 Max
(128 GiB), built-in Chromium 152 for the browser fixture.

Final run (`sbt +test domFixture/fastLinkJS`, 2026-11-06 09:50): coreJVM
160 passed (157 utest + 3 ScalaCheck properties), coreJS 153 passed (150 +
3), dom 4 passed, eventsJVM/JS no tests, domFixture compiled and linked;
`bench/compile` passes. An earlier full run (09:43) failed one
retained-memory case whose forced-GC heap-delta assertion was swamped by
suites running in parallel; that assertion was replaced by the
weak-reference check described under T8 and the suite passes alone and in
the parallel run.

Tooling note: twice, the first incremental compile after adding many new
sources looped (Zinc recompiled the same 11–17 files without end, at full
CPU, no output). Stopping sbt and rerunning converged in two rounds, and a
later single-file touch compiled in one cycle. Use
`-Dsbt.server.forcestart=false` and rerun if it recurs; no source change
was needed.

## T7: lifecycle API (contracts §3.2, §1.3)

- Decisions frozen through the standalone consumer `RenderLifecycleTest`:
  one root owns one `DiffRenderContext`, one performer and the misc entries
  of the published generation; `render(doc)` / `renderWith(template)`
  return a `RenderOutcome` (`Published(generation)`, `Rejected(cause)`,
  `Invalidated(cause)`); state misuse throws `RenderException(INVALID_STATE)`
  (not an outcome, because it is a programming error); `reset()` is the
  recovery from `Invalid` and replays from an empty baseline; `dispose()`
  releases the buffers and is idempotent. Fatal errors propagate after the
  state is put into `Idle` (template) or `Invalid` (performer).
- Signature changes made while prototyping: `render` returning an outcome
  instead of throwing (the DOM adapter and tests wanted both the failure
  and the state); `renderWith` added for stateful integrations; validation
  bound at construction so no decorator is allocated per render; the
  performer bound at construction (a root has one backend).
- Validation (per package): `RenderLifecycleTest` 13 cases (JVM and JS):
  first render, diff, rejected template keeps baseline and misc, limit
  error code, performer failure → `Invalid` → refused → `reset` → full
  replay, nested render refused, stateful ids, validation fail-fast and
  collect, collected errors attached when the engine throws first,
  save/restore, dispose, custom fast performer order.
- Owned performer: `OwnedChangesTest` (JVM and JS) shows that a retained
  `FastId` reads as the reset builder (`0`) after the diff returns, while
  `OwnedChangesPerformer.Buffer` values survive two further renders.
- Scala.js adapter rebuilt on `RenderRoot`: browser fixture below.

## T7: composable validation (contracts §2.1)

- `ValidatingRenderContext` / `StatefulValidatingRenderContext`, 28 codes
  in three selectable groups, fail-fast and capped collection. Positions are
  the diff engine's ids. `ValidatingRenderContextTest` (JVM and JS, 24
  cases) covers every code, both modes, the cap and truncation flag,
  `reset`, group selection, SVG exemption from HTML rules, id and misc
  forwarding of the stateful variant against the inner `DiffRenderContext`,
  and identical diagnostics for plain and optimized templates. The complex
  M1 template validates clean and serializes identically through the
  decorator (HTML5, no pretty-printing, the interactive SSR path).
- Cost when enabled: `AllocationGuardTest` "validation on" cycle allocates
  < 2 KB per render (same bound as without validation; the decorator
  allocates nothing on a valid document after warm-up). When off, no
  validation structure is allocated (`RenderRoot` keeps `null`).
- Browser evidence: fixture checks "an invalid document is rejected with
  coded errors and the DOM is untouched" (code `PARAGRAPH_CLOSED_BY_BLOCK`,
  generation unchanged).

## T7: typed authoring and optimizer laws (contracts §3.1, §3.3)

- `AttrKey`/`BooleanAttrKey`/`StyleKey`, `CheckedUrl`+`UrlPolicy`,
  `CssValue`, `trusted`, `fragment`, `each`, `noNode`/`noAttr`/`noStyle`;
  macro lowerings for each; evaluation laws on the `avocet.dsl` package doc.
- `TypedDslTest` (JVM and JS): encoders, boolean presence, px/css styles,
  name validation at construction, URL policy (accepts http/https/mailto/
  tel/relative; rejects `javascript:` in any case, embedded newline or tab,
  leading/trailing space, `data:`, `vbscript:`, `file:`, `ftp:` under the
  default policy; policy variants), CSS value policy, trusted hatches still
  attribute-escaped, and compile-negative cases through
  `scala.compiletime.testing.typeCheckErrors` (wrong value types for int,
  url, css and boolean keys; `CheckedUrl` has no public constructor; a
  `StyleKey` is not an attribute) plus a positive control.
- `OptimizerContractTest` (JVM and JS): identical traces for typed keys,
  fragment, typed empties and `each`; once-only evaluation of values and
  encoders; collection reuse (reusable list renders each time, single-use
  iterable only once, in both forms); exception timing (plain arguments
  fail at construction, inside `optimize` at render time after the
  preceding operations, a deferred plain form matches the optimized one);
  misc positions; re-evaluation across renders of an optimized node.
- Allocation: `AllocationGuardTest` "typed keys, fragment and each" cycle
  allocates < 512 bytes (two encoded strings and one list iterator).
- Migration matrix `MigrationMatrixTest` (JVM and JS): the same view as an
  unchanged old consumer (raw `DiffRenderContext` + string `ChangesPerformer`)
  and as an opt-in new consumer (`RenderRoot` + validation + owned
  performer + typed keys + `each`) emits identical change sequences and misc
  entries over four renders; a custom `RenderContext` (`OpRecorder`) sees
  identical operations from old/new/plain/optimized forms; a custom
  `ChangesPerformer` runs unchanged under `RenderRoot`; an old consumer's
  saved buffer restores into a new root. The Scala.js DOM application is
  the fixture below; Spoonbill is the maintainer's candidate run (the
  parent session took that step; artifacts are published with
  `env RELEASE_VERSION=<candidate> ... coreJVM/publishLocal eventsJVM/publishLocal`).

## T8: memory engineering (contracts §5)

- Buffer trim policy: `DiffRenderContext.trim()`, `TrimPolicy.Automatic`,
  `bufferCapacity`, `baselineSize`. `BufferTrimTest` (JVM and JS, 7 cases):
  manual trim shrinks and the next diff is exact, refused mid-construction
  and for an unpublished finalized document, automatic trim after N small
  publishes, hysteresis reset by a large render, never below the initial
  size or twice the baseline, argument checks, `RenderRoot.trim`.
- Retained memory, JVM (`RetainedMemoryTest`, run alone and in the full
  suite):

  | Measurement | Result |
  | --- | --- |
  | One root, 2000-node `wide` tree, pre-sized 512 KiB buffer | `bufferCapacity` 524 288 B; thread-local allocation to build and render 526 520 B (buffer + 2.2 KB) |
  | 50 such roots, forced-GC heap delta | 1.05–1.8 MB/root; noisy because other suites run in parallel, so it is printed, not asserted |
  | 200 mount/render/render/unmount cycles | 200/200 disposed roots collectable (weak references cleared); alone, forced-GC heap delta −1 184 B |
  | Large (30 000 nodes) then small (20) | peak 8 388 608 B; manual trim → 65 536 B; `Automatic(4, 4)` → 65 536 B after 4 small publishes |
  | 20 roots of 10..200 nodes, initial 1 KiB | capacities 2 048 … 32 768 B, monotone in tree size |

- Retained memory, Scala.js (`JsRetainedMemoryTest` on Node, no `--expose-gc`):
  `bufferCapacity` 524 288 B/root for the 2000-node tree, V8 `heapUsed`
  delta 37 423 B/root for 20 roots (the ArrayBuffer backing is not
  counted in `heapUsed`); peak 8 388 608 B → 65 536 B after trim and under
  the automatic policy; mount/unmount delta negative. The deterministic
  checks are the assertions; heap figures are printed.
- Allocation per cycle (`AllocationGuardTest`, JVM, `ThreadMXBean`, min of
  5×100–200 cycles after warm-up): raw 1000-node cycle < 1 KB; optimized
  static template < 2 KB; the same through `RenderRoot` < 2 KB; through
  `RenderRoot` with `Validation.interactiveHtml` < 2 KB; typed keys +
  fragment + each < 512 B. All pass.
- Performance guards (`DiffPerformanceGuardTest`, JVM and JS) pass
  unchanged after the `swap()` policy check and the `RenderRoot` layer.
- JMH baseline: see [bench/BASELINE.md](bench/BASELINE.md) (command,
  environment and figures recorded there).

## T7/T8: browser path (Scala.js adapter on RenderRoot)

- Command (as in T5) and result 2026-11-06 09:42, Chromium 152
  (`Chrome/152.0.7977.167`, desktop app browser pane): page title
  `PASS avocet dom fixture: 20 checks, 0 failed`. The 17 M1 checks pass on
  the adapter rebuilt over `RenderRoot` (invalid/disposed/reentrant renders
  now surface as `RenderException(INVALID_STATE)`; the "throwing template"
  check now throws at render time, which the M1 version did not exercise
  because a plain template evaluates its arguments when built). New checks:
  validated typed document published with generation 1 (`AttrKey.url`,
  `AttrKey.int`, `StyleKey.px`, `each`), invalid document rejected with
  `PARAGRAPH_CLOSED_BY_BLOCK` and the DOM untouched, a rejected
  `javascript:` URL replaced by the application's fallback before it reaches
  the DOM.
- Still not automated: the fixture is loaded manually in the browser pane.

## Outstanding after M2

- Release actions as above.
- The browser fixture is loaded manually; a headless runner would need a
  Nix-provided browser in this repository's flake or Spoonbill's `.#browser`
  shell driving this page.

## M3 and M4: deferred by their demand gates

Both milestones are conditional in [IMPROVEMENT-PLAN.md §4](IMPROVEMENT-PLAN.md)
and the guide (§11) says to defer them explicitly when the gate evidence does
not exist. It does not:

- **M3 keyed identity.** No application screen with a failing reorder
  behavior (focus, selection, uncontrolled input state, media or widget
  identity lost across a reorder) has been named, and no browser fixture
  captures such a failure. Spoonbill's current screens use positional ids and
  its protocol has no move opcode; nothing in this work requires one. Deciding
  an identity contract without that fixture would be the "identity rewrite to
  solve an encoding or cache defect" the guide forbids.
- **M4 external subtree ownership.** No editor or chart widget integration has
  been named, and M4 depends on the identity contract M3 would settle.

Decision: M3 and M4 are deferred. M1 and M2 ship without them; unkeyed
behavior, the positional id representation and the wire protocol are
unchanged. The deferral is revisited when a maintainer names the screen or
widget and its required browser state, per the plan's gates. No move
scaffolding or no-op performer methods were added in anticipation.

## T6 again: Spoonbill with the M1+M2 candidate

Published from the final working tree (after the independent `+test` run
above) as `1.0.0-m2-baeeea2-SNAPSHOT` with the same `publishLocal` command,
and selected in the Spoonbill worktree's `avocetVersion`.

| Check (Spoonbill worktree, 2026-11-06) | Result |
| --- | --- |
| `show spoonbill/Compile/dependencyClasspath` | resolves the `1.0.0-m2-baeeea2-SNAPSHOT` core and events jars |
| `spoonbill/test` | 614 succeeded, 0 failed |
| `testkit/test` | 19 succeeded, 0 failed |
| `bash scripts/test-sensitive-acknowledgement.sh` | PASS in Chromium and WebKit (after `sbt clean`) |

The first service run after switching candidates failed with
`NoSuchMethodError: DiffRenderContext$.apply$default$4()`: the `standalone`
module's resolution cache still held the M1 jar while the core module had
recompiled against M2 (whose `apply` gained the `trimPolicy` parameter).
`sbt clean` re-resolved everything to the M2 jars and the check passed.
This is a consumer build-cache effect, not a renderer defect, but it shows
that an old `avocet-core` jar on the classpath with new Spoonbill classes
fails at session start; the dependency bump must ship together.

## PR review findings (2026-11-06) and their fixes

Six findings from the review of the first PR revision. All were confirmed
against the code before changing anything; the fixes and their regressions
are listed per finding. Tests: `ReviewFixesTest` (JVM and JS),
`FactoryBinaryCompatTest` (JVM), two new browser-fixture checks.

| Finding | Confirmed cause | Fix | Regression |
| --- | --- | --- | --- |
| P1 factory binary break | `DiffRenderContext.apply` went from 3 to 4 parameters; the 1.0.0 JVM method disappeared. Spoonbill recompiled during validation, hiding it. | The 4-parameter `apply` keeps the defaults; the 1.0.0 3-parameter `apply` is back as an overload. Changelog corrected: it claimed "additive". | `FactoryBinaryCompatTest` resolves the legacy JVM signature and `apply$default$1..3` by reflection and renders through it. |
| P1 `CssValue` resource loading | Blacklist (`url(`, `expression(`, comments) let `image-set("…")`, `src(`, `image(` and others fetch. | Allowlisted grammar: whitespace/commas/slashes, numbers with units, hex colors, identifiers, top-level strings, and only the functions in `CssValue.allowedFunctions`, balanced. | `ReviewFixesTest`: 21 rejected inputs including `image-set`, `-webkit-image-set`, `src`, `image`, `element`, `attr`, nested `url` and `image-set` inside allowed functions; 22 accepted ordinary values. Fixture: rejected loaders and an accepted gradient with no resource entry for the probe host. |
| P2 optimizer evaluation counts | Typed-key lowerings spliced the receiver three times (boolean key: zero when false); `each`, option and seq lowerings re-spliced a dynamic callback per element; option lowerings spliced the option twice. The old `AttrDef` lowering had the receiver twice. | Receivers bound once (`val k = …`); lambda literals are still inlined, any other callback expression is bound once outside the loop and applied per element; options bound once. | `ReviewFixesTest`: dynamic key/definition factories evaluated 4 times in both forms; a stateful callback factory yields 1,2,3 in both forms; dynamic option/seq and mapper evaluated once each. |
| P2 reentrant dispose | `dispose(target)` removed the registry entry, listeners and DOM before `RenderRoot.dispose()` rejected the rendering state. | Eligibility checked first (`INVALID_STATE` while rendering); registry entry removed only after a successful dispose. | Fixture: dispose from a rendering callback is rejected, the published view and the root survive, the next render works. |
| P2 paragraph scope | Only the direct parent was compared with `p`. | Walk the open-element stack to the nearest button-scope boundary (`HtmlShape.buttonScopeBoundary`) or foreign element; a closed `p` is reported once. | `ReviewFixesTest`: `p(span(div))` and `p(span(em(ul(li))))` report; `p(span)` and `p(button(div))` do not; existing `p(ul(li))` still reports once. |
| P2 capacity 1 | Size 1 passed the power-of-two check; `trim()` and restore seeded growth with `1 / 2 = 0` and looped forever. | Sizes below 2 are rejected; both growth seeds are at least 1. | `ReviewFixesTest`: size 1 rejected; size 2 renders, trims, saves and restores a non-empty baseline. |

Verification of the fixed revision (2026-11-06 13:30): `sbt +test` coreJVM
164 passed, coreJS 156 passed, dom 4; `domFixture/fastLinkJS` links and the
browser fixture passes 22 of 22 checks in Chromium 152 (two new checks);
`bench/compile` passes. Candidate `1.0.0-r1-baeeea2-SNAPSHOT` in the
Spoonbill worktree after `sbt clean`: `spoonbill/test` 614, `testkit/test`
19, real-service check PASS in Chromium and WebKit. One existing test
expectation changed with the paragraph fix (`RenderLifecycleTest`): a
paragraph closed by `div` is no longer reported a second time for a later
`form`, which matches the parser.
