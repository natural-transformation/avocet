# Avocet contracts and acceptance reference

Status: Avocet 2.0.0 baseline and continuing requirements. Updated: 2026-11-11.

The [roadmap](IMPROVEMENT-PLAN.md) records M1/M2 as shipped in
[Avocet 2.0.0](https://github.com/natural-transformation/avocet/releases/tag/v2.0.0).
This reference distinguishes implemented contracts from future acceptance work.
M3 keyed identity and M4 external subtree ownership remain conditional and unimplemented.

Avocet remains at the 2.0.0 source baseline (`c130282`).
[Spoonbill 10.0.0](https://github.com/natural-transformation/spoonbill/releases/tag/v10.0.0)
was published on 2026-11-10 at `f0d456344359eb63ea4b138d28352860eef49f3b`; its
[tagged build](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/build.sbt#L5)
selects Avocet 2.0.0. The release records publication of all 26 modules and a clean
Maven Central-only independent consumer run with 27 passing tests. F1 is complete;
that evidence does not close repair-specific browser coverage in F2–F4. See §9.2.
Spoonbill 9.0.0's Avocet 1.0.0 dependency and pre-release candidate checks remain
historical baselines, not the current consumer selection.

Use the [changelog](CHANGELOG.md), [implementation evidence](IMPLEMENTATION-EVIDENCE.md)
and [M2 benchmark baseline](bench/BASELINE.md) for provenance and tested workloads.
The original [review](IMPROVEMENT-PLAN-REVIEW.md) describes the pre-repair baseline.
Do not treat its defects or pre-release test counts as the current release state.
No runtime tests were rerun solely for this documentation refresh.

## 1. Security and correctness contracts

### 1.1 Trust and encoding

These remain requirements for future changes. Avocet 2.0.0 repairs SSR style-value encoding and supplies typed URL/CSS policy helpers; the table is not a claim that every executable context has a data-safe API:

| Context | Contract to establish |
| --- | --- |
| Ordinary text | Remains text through SSR and DOM updates, including delimiters, Unicode and control-character policy. |
| Ordinary attribute values | Cannot terminate their attribute or introduce another attribute through SSR. DOM updates must have equivalent semantics. |
| URL-bearing attributes | Escaping and URL policy are separate. A data-safe URL helper applies an explicit scheme policy; generic trusted attributes must not be advertised as URL sanitization. |
| CSS declarations | Protect the enclosing HTML attribute independently of CSS syntax. Clearly define trusted arbitrary CSS versus restricted data-safe values. |
| Script, style and other parser-sensitive content | Generic text encoding is not a universal encoder for these contexts. Specify dedicated trusted APIs or a restriction in the safe profile, including closing-tag and JSON-embedding cases. |
| Dynamic tag and attribute names | Require valid names and namespace rules; distinguish safe names from executable attributes. A syntactically valid name alone does not make an operation safe. |
| Inline event attributes and other executable sinks | Treat as explicit trusted-code escape hatches. Framework event bindings remain the recommended interactive API. |
| `iframe[srcdoc]` and other nested markup inputs | Outer attribute escaping does not make the nested document inert. Require an explicit trusted/restricted operation; do not expose it as an ordinary data-safe string attribute. |

Preserve custom elements and supported SVG/MathML use through explicit namespace-aware APIs. Do not apply HTML-only rules to every XML namespace.

2.0.0 provides `AttrKey`, `BooleanAttrKey`, `StyleKey`, `CheckedUrl`/`UrlPolicy`, `CssValue` and explicit `trusted` helpers. Generic string keys remain policy-free, and `Document.Node`, `Attr` and `Style` do not prove purity or universal safety. `CheckedUrl` checks scheme/syntax, not origin or destination content; relative references can still resolve across origins. CSS policy is syntactic and can depend on stylesheet context through custom properties. Never treat either value type as application authorization or general sanitization.

There is no trusted raw-text API for script/style content in 2.0.0; interactive validation diagnoses such content. A future raw HTML/text capability needs a narrowly defined construction/consumption contract. The CSS grammar's documented comment restriction also needs alignment with acceptance of comments inside allowed functions; that is follow-up F4, not a new guarantee. The current parser accepts `/` generally and `*` within functions, so `calc(1px /* note */ + 2px)` passes its token checks. The recommended repair rejects actual comment tokens while preserving arithmetic such as `calc(2 * 1px)` and `calc(4px / 2)`, and permitted quoted top-level values containing literal `/*` text. See the [F4 acceptance gate](#f4-bounded-html-conformance-and-css-policy).

Tests must demonstrate inert data through real parsing and later updates. Diagnostics may contain bounded error codes, positions and schema/tag metadata; they must not include text, attribute values, arbitrary keys, callback contents, credentials or entire rendered documents by default.

### 1.2 Equality and caches

- A non-cryptographic hash match is a candidate for equality, not proof of equality.
- 2.0.0 verifies names/values/subtrees after matching hashes and writes headers directly. The old thread-local header cache was removed. Preserve exact equality for namespace, operation kind and style/attribute distinctions.
- For subtree skipping, define an exact comparison that ignores irrelevant positional metadata where necessary. A larger hash alone does not satisfy this contract.
- Exercise deliberately colliding values through test seams and known collision cases. Tests must not depend on waiting for random collisions.
- If a future change reintroduces caching, use exact complete keys and bounded entry/byte retention with documented lifetime and eviction; static vocabulary may use a separate compact representation.
- Correctness fixes apply on production paths, even when optional diagnostic validation is disabled.

Any future cached payload must not depend on a shared mutable buffer cursor being left in the expected position. Compare immutable payloads or absolute reads against the current cache-free implementation before adopting them.

### 1.3 Bounds and failure containment

Current diff-engine limits are fixed constants, not a general configurable `RenderLimits` API:

| Resource | 2.0.0 behavior |
| --- | --- |
| Sibling position | At most 32767 per level; checked before increment. |
| Element nesting | At most 255. |
| Tag/attribute/style names | At most 1024 UTF-16 code units with name syntax checks. |
| Text/attribute/style value | At most 2^24 UTF-16 code units in the diff engine. |
| Backing buffer | At most 1 GiB total across both halves; initial size must be a power of two of at least 2. |
| Validation errors | Fail-fast or `ValidationMode.Collect(max)`; collection is capped. |
| Saved buffer | Format 2 (`AVCT`) with structural validation; the old unversioned format is rejected. |

Preserve these representation checks regardless of optional validation. Frame limits, patch-byte budgets and service-wide limits remain integration concerns; these engine constants do not prove a bounded total process footprint. Any future node-count, keyed-child, cache or configurable budget must define its own scope and admission behavior.

Check addition, multiplication, casts and buffer growth before allocation or mutation. A malformed saved buffer must fail validation without trusting offsets, lengths, opcode values or namespace codes. Internal saved render buffers are not an arbitrary untrusted-data interchange format.

Include negative/invalid initial capacities, fixed-header capacity and split-buffer growth in the boundary tests: sufficient total backing capacity does not necessarily mean the active half has room for the next operation.

Separate three outcomes:

1. **Before publication:** invalid rendering fails without replacing the previous published tree, event bindings or revision. Discard or restore partially constructed context state before reuse.
2. **During DOM application:** DOM writes are not generally rollbackable. A performer failure marks the baseline unusable; the integration blocks further events/patches until a defined reset or remount succeeds.
3. **Application side effects:** aborting a render does not undo effects already performed by application callbacks. Document this boundary and keep effects out of the recommended render function.

Custom elements can synchronously invoke code during DOM mutation. The adapter needs a defined applying/committed/failed lifecycle so such callbacks cannot observe mismatched old handlers and new node identities. Do not promise transactional browser DOM mutation.

### 1.4 Identity and authorization boundary

Every event must resolve to a live node within the intended root and the integration's accepted publication generation. Removed or replaced identities must not silently resolve to newly created content. Avocet must not treat possession of a key, ID, DOM property or client-provided string as authority.

Spoonbill continues to own authorization, view revision checks and stale-event rejection. New renderer identity must fit those checks; it cannot bypass them because a DOM node survived a move.

## 2. Validation and browser-compatible trees

### 2.1 Composable validation interpreter

2.0.0 ships `ValidatingRenderContext` and `ValidatingRenderContext.stateful`. `Validation.off`, `.structural`, `.html` and `.interactiveHtml` select progressively broader checks; `failFast` and `collect(maxErrors)` select reporting behavior.

Separate independently selectable checks:

- Structural protocol: balanced and matching nodes, legal operation order, duplicate attributes and bounded state.
- Namespace-aware HTML shape: void elements, paragraph auto-closing, nested forms and table normalization/foster parenting.
- Interactive bootstrap shape: text segmentation, unsupported parser-normalized constructs and identity mapping assumptions.
- Key/ownership rules are future M3/M4 requirements, not current checks.

The shipped validator reports 28 `ValidationError.Code` values with bounded location/name metadata. Fail-fast throws before forwarding the offending operation. Collect mode records bounded diagnostics and still forwards operations; callers of a standalone collecting wrapper must inspect completion/errors. `RenderRoot` does so before publishing. Optional diagnostics do not replace mandatory engine bounds.

Use the shipped stateful wrapper when the consumer needs `currentId`, `currentContainerId`, `subsequentId` or misc forwarding. Preserve these properties in future changes; a plain `RenderContext` decorator is not a drop-in replacement for all Spoonbill integration paths.

Current typed-key compile-negative tests cover value/category misuse, not arbitrary HTML validity at compile time. Keep runtime validation for dynamic templates. Any future literal diagnostics must agree with runtime and optimized/ordinary behavior.

### 2.2 SSR and DOM agreement

The shipped interactive profile diagnoses a documented subset of parser-normalized constructs, using lowercase HTML names and tables in `HtmlShape`; it is not a complete HTML parser. For the supported profile, retain this conformance target. In the equation below,
`renderInteractiveHtml` denotes serialization through `Html5RenderContext` with
`TextPrettyPrintingConfig.noPrettyPrinting`; it is notation for the contract,
not an existing helper or the current `renderHtml` function. For that profile:

`apply(diff(old, next), browserParse(renderInteractiveHtml(old)))` must produce the same supported DOM structure as `browserParse(renderInteractiveHtml(next))`.

Also compare against direct DOM construction. Define normalization explicitly for namespaces, attribute ordering and text segmentation; do not normalize away misplaced nodes, wrong attributes or lost identity to make tests pass.

2.0.0 diagnoses adjacent and empty text (`ADJACENT_TEXT`, `EMPTY_TEXT`) in interactive mode. It does not coalesce text, add parser boundary markers or change Spoonbill bootstrap indexing. CR, parser-dropped leading newlines and raw script/style content have diagnostics too. With validation disabled, these diagnostics provide no protection.

Making more convenient string composition safe remains follow-up F5: compare consistent normalization against explicit boundaries, then update every interpreter and bootstrap consumer together. This is a future semantic/compatibility decision, not a shipped M2 normalization feature.

Preserve meaningful whitespace in mixed content, `pre` and `textarea`; specify CR/LF and malformed-Unicode handling. Pretty-printing is not assumed semantics-preserving for interactive markup. Compare its documented behavior separately rather than trimming text to make tests agree.

Use `Html5RenderContext` with `TextPrettyPrintingConfig.noPrettyPrinting` for the initial interactive SSR contract, matching [Spoonbill's actual renderer](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/main/scala/korolev/server/internal/Html5RenderContext.scala). Avocet's [HTML5 interpreter](core/shared/src/main/scala/levsha/impl/Html5RenderContext.scala) inherits ordinary encoding methods but handles void-element closing separately.

The existing [renderHtml](core/shared/src/main/scala/levsha/text.scala) instead creates `XhtmlRenderContext` and defaults to pretty-printing. The review's P11 reports `<br></br>` from that serializer; add a browser fixture for the consequence rather than labeling serialized output as a browser result. Preserve a deliberate XHTML entry point for XML consumers if `renderHtml` changes, and document the compatibility impact. Test parser newline normalization and whitespace-sensitive content even with pretty-printing disabled.

Unsupported-shape diagnostics are already available. Expanding them, enabling validation by default or changing normalization requires compatibility notes and migration examples. Avoid silent HTML repair inside the diff engine.

## 3. Type-safe functional authoring

### 3.1 Preserve a small algebra and multiple interpreters

Keep template descriptions independent of effects and host services. Support interpretation into HTML, compact diff buffers, validation and test representations. Prefer composition over framework inheritance or global mutable configuration.

2.0.0 adds `fragment`, `each`, `noNode`, `noAttr` and `noStyle` alongside existing `when`, sequence conversions and node/attribute/style categories. Extend these where a concrete application reveals a gap; do not create parallel DSLs with competing semantics.

Shipped typed helpers are `AttrKey[A]`, `BooleanAttrKey` and `StyleKey[A]`, with explicit encoders, validated factory alternatives, numeric/URL/CSS helpers and category-specific empty values. Preserve validated dynamic-name construction and ordinary Scala composition. A typed URL key checks the supplied value type; generic string keys and explicit trusted helpers remain low-level policy choices.

Future identity/ownership concepts for M3/M4 (not public 2.0.0 types):

| Concept | Meaning | Must not mean |
| --- | --- | --- |
| `NodeKey` | Logical identity within one sibling collection | Global DOM ID or authorization token |
| `NodeHandle` | Renderer-issued runtime node identity | Caller-chosen key or current position |
| `TreePosition` | Location/ordering in a particular tree | Stable identity across moves |
| Ownership boundary | Which renderer may update descendants | Trust or isolation from browser code |

Use opaque types or small validated value types where they improve misuse prevention without unnecessary boxing. Do not make core consumers import an effect library to use these types.

### 3.2 Lifecycle API

`RenderRoot[M]` is the shipped high-level API. `render`/`renderWith` run construction, optional validation, finalization, diff and publication. Outcomes are `Published(generation)`, `Rejected(cause)` and `Invalidated(cause)`; states are `Idle`, `Rendering`, `Invalid` and `Disposed`. Rejected preparation retains the prior baseline/misc; invalidated application requires a reset/dispose strategy compatible with the backend. Low-level `DiffRenderContext` remains available for integrations such as Spoonbill.

The current API uses runtime lifecycle checks and explicit outcomes, not linear types or an effectful render algebra. Preserve thread confinement, reentrancy checks and disposed-state handling. Any future stage-specific API must justify its complexity/allocation cost and include a migration.

Make error reporting explicit at high-level boundaries. Low-level callbacks may still throw, but their failure must become the documented aborted/invalidated lifecycle outcome rather than a reusable half-published state.

The fast performer borrows mutable `FastId` and source-buffer arguments for synchronous consumption. `OwnedChange` and `OwnedChangesPerformer` (including `Buffer`) are the shipped copying path for queued/asynchronous consumers. Their allocation cost is intentional; do not retain borrowed scratch references or add per-operation wrappers to the synchronous fast path without evidence.

### 3.3 API acceptance examples

Compile examples for static HTML, conditional fragments, nested reusable templates, a custom attribute/namespace, several events on one element and a custom interpreter; add editable keyed rows only when M3 proceeds. Exercise both optimized and ordinary forms through the operation/evaluation trace harness established in M1. Add negative compile tests for violating exposed API capabilities; when keyed identity types are introduced, include mixing keys, handles and positions.

Measure source complexity, inferred types, diagnostic clarity, compilation cost and generated-code size alongside runtime costs. Keep the API useful for advanced composition; a shorter introductory example is not enough evidence.

Define construction-time versus render-time evaluation, once-only evaluation of dynamic expressions, operation order, exception timing and reusable collections versus single-use iterators. [DslOptimizerMacro](core/shared/src/main/scala-3/levsha/dsl/DslOptimizerMacro.scala) recognizes particular DSL shapes: every new public helper must have a tested lowering or a correct, documented fallback whose allocation cost is measured. Do not claim allocation-free behavior merely because a call is wrapped in `optimize`. The optimizer must preserve these documented semantics. Add operation-trace and evaluation-counter tests for dynamic tags, options, loops and spliced documents; bind expressions once and use a safe fallback when rewriting cannot preserve semantics. Recommending pure render functions does not excuse duplicated evaluation of arbitrary expressions the API accepts.

The DOM adapter still uses legacy `Any`/Boolean callback outcomes in 2.0.0. Explicit propagation/default-action outcome types remain follow-up F5; introducing them needs an intentional migration and tests for multiple handlers and phases.

## 4. Keyed reconciliation and identity redesign

### 4.1 User-visible contract

M3 starts only with a named application screen, a reproducible reorder problem and explicit browser-state requirements. If no such demand is demonstrated, defer keys and continue M1/M2. The contracts below do not authorize an identity rewrite merely to anticipate future features.

Make keys optional and sibling-scoped. Retain the current unkeyed semantics and fast path.

- A keyed collection requires a unique key for every participating direct child. Reject duplicate keys before publication; initially reject ambiguous mixed keyed/unkeyed collections.
- The same key under the same parent and a compatible element kind reuses that logical node during reorder.
- A tag/namespace change replaces the DOM node and retires the old runtime handle according to a documented lifecycle.
- Removal retires the identity; later reuse of the same logical key must not reactivate stale events or references.
- Moving between parents is initially remove/create unless a separately reviewed transfer contract is introduced.
- Keys are application data: avoid logging them, cap their representation and require stable equality. Do not persist entire business objects merely to key a row.

Reordering should preserve actual node identity. Focus, selection, uncontrolled input state and custom-element behavior still need browser evidence; a move primitive alone does not guarantee all of them.

### 4.2 Resolve identity before algorithm selection

Evaluate these two designs with the same fixture:

| Design | Benefit | Cost/risk |
| --- | --- | --- |
| Stable runtime handles plus separate parent/ordering metadata | Identity remains unchanged across moves; retirement and stale-reference checks are explicit. | Additional bounded indexing; new event ancestry representation and bootstrap mapping. |
| Positional IDs with subtree remapping on moves | Retains more existing ID representation. | Descendant IDs, client indexes, component references and event bindings must all change consistently; remapping can erase performance gains. |

The preferred hypothesis is stable handles for a new keyed-capable backend. It is not a settled implementation choice. Select it only after measuring memory and proving bootstrap, events, removal and recovery. If neither design meets the gates, retain the released positional implementation and defer keys; validation/lifecycle maintenance continues independently.

The decision record must specify SSR handle assignment, direct-DOM mounting, nested components, attribute IDs versus renderer IDs, handle generation/reuse, parent lookup, root isolation, saved-buffer compatibility and integration callbacks.

### 4.3 Change protocol and algorithms

Define insertion-before, move and in-place text-update semantics, their preconditions and ordered application. An explicit `setText` operation can preserve a text node where today's `createText` replaces it; verify its value separately from keyed element moves. Unknown handles, wrong roots, impossible parents and invalid order references must fail deterministically. Runtime handles and node type/namespace checks should detect mismatches where practical; a tag-name check alone cannot distinguish two same-tag rows.

Prefer a straightforward O(n) indexing pass and a bounded O(n log n) reorder strategy for general keyed siblings, with measured fast paths for append/prepend and unchanged lists. A longest-increasing-subsequence optimization is optional after correctness. Do not demand globally minimal edit scripts.

Keep bookkeeping proportional to the live keyed collection and release removed entries. Preserve the buffer engine where possible; a full object-tree allocation is a separate design proposal.

Do not add required methods to public performer traits and call the change compatible. Introduce an explicit new capability/version or release a major version with coordinated adapters. A backend without move support must reject keyed mode at setup or select an explicitly documented non-preserving mode; no silent fallback that promises identity preservation while replacing nodes.

## 5. Memory and lifecycle engineering

Measure allocations and retained memory separately, on JVM and Scala.js. Report renderer-owned storage, application input storage and backend DOM costs separately.

2.0.0 provides `bufferCapacity`, `baselineSize`, manual `trim()`, `TrimPolicy.Automatic`, smaller used-region snapshots and root/DOM disposal. The header cache is removed. Existing tests measure allocation, deterministic capacities and selected retention scenarios. Continuing requirements for changes in this area:

- Bound buffer growth and checked size calculations; document per-context ownership and thread confinement.
- Provide an explicit context/root disposal path, including event listeners, misc callbacks, detached descendants and any keyed indexes.
- Specify whether removed/replaced descendants are eagerly removed from node indexes and prove that obsolete references cannot receive updates.
- Bound shared/thread-local caches; distinguish static names from adversarial or high-cardinality dynamic names.
- Support a deliberate trim/discard policy after unusually large trees; avoid retaining peak-sized buffers forever or reallocating on every small fluctuation.
- Document save/restore copies and buffer-format versioning. Never deserialize an old format as a new one by assumption.
- Evaluate snapshots of used regions instead of copying full spare capacity. Specify byte order, input-buffer cursor ownership and whether restoration copies or borrows; keep the persistence format independent of hot-path layout when beneficial.
- Keep diagnostics bounded and payload-free. Reference release is required; secure physical erasure of JVM/JS strings is not promised.

Benchmark scenarios: static render; unchanged tree; sparse text/attribute edits; wide/deep trees; long Unicode values; growth then shrink; repeated mount/unmount; many independent roots; keyed prepend/remove/reverse/shuffle; multiple event handlers; validation on/off; colliding values and cache churn.

Record throughput, latency distribution, bytes allocated per operation, peak and retained heap, buffer capacity, live index/cache entries, DOM operation counts and Spoonbill patch bytes. Include long-lived churn runs, not only a small steady-state benchmark.

Use the recorded [M2 frozen baseline](bench/BASELINE.md#m2-frozen-baseline-2026-11-06) for comparable workloads. It reports average operation time and allocation; it is not a universal p95/service-latency guarantee. Retain the following review gates for future changes:

- No monotonic growth in renderer-owned live entries after equivalent render/remove/unmount cycles; after disposal, no strong roots remain from Avocet registries.
- Optional features allocate no keyed/validation structures when disabled.
- For comparable unkeyed benchmark scenarios, investigate average-time regressions above 5% and any new allocation proportional to node count. The earlier 10% p95 target requires a matching latency baseline before use; do not claim it was proved by average-time benchmarks. These are investigation gates, not permission to weaken correctness.
- Keyed scratch storage is bounded by configured live-child limits and scales O(n); time remains within the chosen algorithmic bound on reverse/shuffle cases.
- Keyed reorder tests preserve DOM identity and perform moves instead of recreating retained rows. Exact operation counts are asserted only for well-defined simple cases.
- Security/correctness repairs may cost performance. Document the tradeoff and obtain a maintainer decision rather than retaining unsafe behavior to meet a speed target.

## 6. Externally owned subtrees: conditional feature

Implement only after a concrete editor/chart integration establishes the need and a lifecycle example passes.

The proposed boundary lets Avocet own the container's identity, attributes and placement while an integration owns descendants. Define whether initial descendants are supplied by SSR or mounted exclusively by the integration, and how hydration/recovery adopts or replaces them.

Specify mount, update, move, replacement and dispose notifications in the integration layer. Avocet core communicates structural lifecycle; it does not execute arbitrary effects during diff calculation. Define event delegation from externally owned children and prevent it from accidentally resolving to a retired or foreign renderer node.

Do not retain arbitrary widget DOM snapshots or sensitive values in render buffers. A widget is ordinary trusted browser code, not a protected enclave. Raw HTML insertion is a separate feature with a separate trust contract.

Tests must cover no interference with external descendants during ordinary renders, container updates, move/replacement, exactly-once cleanup per instance, disconnect/recovery policy and bounded retention. If these cannot be specified without an unproven identity model, defer the feature.

## 7. Spoonbill and Scala.js integration

### Scala.js DOM backend

The repaired Scala.js backend is shipped. `EventTableTest` provides DOM-table unit coverage and `domFixture` has 50 browser checks: the 23 from 2.0.0 for rendering, handlers, cleanup, failures, reentrancy and selected policy cases, plus the F4 HTML-profile, CSS and churn checks and three node-identity regressions (§1.4). Preserve these alongside renderer changes and add move-related evidence only if M3 proceeds. Node unit tests do not substitute for browser execution. Since 2026-11-11 `scripts/test-dom-fixture.sh` runs the fixture headless in Chromium and WebKit (F3).

Specify simulated framework capture/target/bubble separately from native listener registration and propagation. Include non-bubbling `focus`/`blur`, events stopped before reaching the root, default prevention and text-node targets. A passing simulated propagation test does not establish support for every native event.

### Spoonbill

Spoonbill 10.0.0 uses Avocet 2.0.0; its released adoption completes F1. Its [portable UI recovery guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/portable-ui-recovery-plan.md) describes the shipped generic snapshot/provider boundary so users can supply their own database adapters, without a commitment to ship particular integrations. Recoverable presentation schemas are distinct from Avocet saved render buffers: `save()` is not a distributed ownership or authentication protocol.

For F2 and subsequent renderer changes, keep responsibilities separate and test these interfaces together:

- `RemoteDomChangesPerformer` serialization and the Scala-owned generated client protocol.
- Browser node indexes, bootstrap/preset IDs, root resets and incremental patches.
- Event counters, immutable handler publication, view revisions and obsolete-event rejection.
- Nested component identity/state, element references and lifecycle resources when nodes move or disappear.
- Guarded output admission, ownership takeover, sensitive cleanup and reconnect baseline reconstruction.

The current protocol has no move opcode. New opcodes require regenerated client constants, operand validation and an explicit rollout/version rule. An old client must not partially execute an unfamiliar patch. Prefer a version check before live interaction and a controlled bootstrap refresh when incompatible; do not replay user actions during that refresh.

Existing unkeyed applications must remain testable against explicitly resolved candidate artifacts when evaluating a future Avocet change. Verify that the candidate replaces the released dependency selected by the build. A new keyed client/server pairing must also be tested through real service transport. Browser DOM equality alone cannot establish correct authorization or event delivery.

Current Spoonbill coverage includes [HTML5 serialization and preset IDs](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/test/scala/korolev/server/internal/Html5RenderContextSpec.scala), [render rollback and frame cleanup](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/test/scala/korolev/internal/RenderLifecycleSpec.scala), [saved-context restoration](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/test/scala/korolev/DevModeSavedRenderContextSpec.scala) and [invalid development-buffer recovery](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/modules/spoonbill/src/test/scala/korolev/server/internal/services/InvalidDevRenderContextSpec.scala). These protect meaningful integration behavior, but they do not execute F2's complete SSR/bootstrap/first-patch matrix in a browser.

Reference contracts: [Spoonbill protocol generation](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/client-protocol-generation.md), [browser checks](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/browser-tests.md), [guarded recovery](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/clustered-browser-authentication.md). These links pin the released Spoonbill source; run integration commands from a separate Spoonbill checkout.

## 8. Acceptance matrix

| Area | Representative cases | Required evidence |
| --- | --- | --- |
| Encoding | HTML delimiters, quotes, Unicode, malformed surrogates/control policy, CSS attribute boundaries, URL schemes, `srcdoc`, script/style closing sequences | No execution through supported data-only APIs; equivalent SSR/update behavior; explicit trusted-path documentation. |
| Names/namespaces | Custom elements, SVG/MathML transitions, long names, invalid names and unknown namespace codes | Supported names work; unsupported input fails before corrupting output or indexes. |
| Collisions | Tag/attribute/value/subtree hash collisions; same-name style/attribute; cache reuse order | Exact intended tree and operations regardless of collisions or cache history. |
| Limits | Depth/sibling/length boundaries, arithmetic overflow, oversized trees and saved-buffer truncation | Bounded deterministic failure, no identifier wraparound or reusable corrupt context. |
| Render lifecycle | First render, repeat, no change, exception mid-render, exception mid-patch, nested callbacks | Baseline and handler state remain coherent or explicitly invalidated; recovery is defined. |
| HTML shape | Paragraph/table/form normalization, void children, adjacent/empty text, SSR first patch | Browser-parsed structure and IDs match the supported profile; unsupported shapes produce bounded diagnostics. |
| Events | Several event types/phases on one element, native/simulated propagation, non-bubbling focus/blur, default prevention, removed descendants, replaced roots | No handler loss, duplicate invocation or dispatch to retired nodes; native-event support precisely documented. |
| Keys | Append/prepend, reverse/shuffle, deletion/reinsertion, duplicate/mixed keys, same-key tag changes | Correct retained DOM identity, fresh identities where required, correct target/ancestor routing. |
| Integration | Nested components, delayed old events, mismatched client version, reconnect/full reset and authority change | Existing Spoonbill admission and security guarantees preserved; no action replay. |
| Memory | Large-to-small render, cache churn, many roots, repeated widget mount/unmount | Bounded retained entries and buffers; disposal releases framework references; measured allocations. |
| API/optimizer | Custom interpreters, ordinary/optimized templates, compile failures, event/misc composition | Equivalent semantics, clear errors, no accidental mandatory effects or per-node allocation. |

This matrix is a regression and extension checklist, not a statement that every case has release evidence. Consult the implementation evidence for coverage and the roadmap for completed F1 and remaining F2–F5 work; keyed/ownership cases apply only if those features proceed. Use reproducible random seeds with shrinking. Keep structural invalid-input generators separate from valid browser-HTML generators: rejecting an invalid tree is success, while silently catching an unexpected exception is not. Include deliberately injected faulty performers/encoders to establish that the tests detect relevant mistakes.

### F2: real-service renderer acceptance

Close F2 only when a targeted fixture starts an actual Spoonbill service using
explicitly resolved Avocet core/events artifacts, loads its real SSR and assembled
client in Chromium and WebKit, and applies the first incremental patch. Use
[Spoonbill's browser guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/browser-tests.md) for the existing
service-runner pattern; a dedicated repair fixture/command is still future work.

- Cover long valid names beyond the former short-name boundary, deliberate hash
  collisions, style/attribute distinctions and delimiter-bearing style values.
- Assert browser-parsed attributes and node IDs before the patch, then exact
  intended nodes/values afterward, including styled parents, component boundaries
  and subsequent siblings. Do not normalize away bootstrap or identity errors.
- Dispatch a real event after the patch and prove that the intended current
  handler runs once; retain obsolete-event and authorization checks.
- Record producer/consumer commits, resolved artifacts, browser versions,
  commands and assertions. Existing serializer tests, synthetic transport checks
  and generic service tests remain separate evidence, even when they pass.

### F3: repeatable Avocet DOM fixture execution

Close F3 only when the existing 23-check [DOM fixture](dom-fixture/src/main/scala/levsha/dom/fixture/DomFixture.scala)
is run by a committed headless Chromium/WebKit runner and the relevant CI job.
The current `window.avocetDomFixture` result object supplies `failed` and `results`;
require completion, the expected checks and zero failures, with nonzero exit on
missing output, timeout, page error or a failed assertion. Prove the runner fails
with an injected fixture failure. Own and clean up the loopback server and browser
processes on success, error and interruption; use bounded startup/run deadlines.

Pin browsers and the driver through Nix, following
[Spoonbill's browser environment](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/browser-tests.md) where useful.
Avocet's current flake supplies sbt/JDK/Node only; adding a browser environment,
runner and CI wiring is implementation work. `domFixture/fastLinkJS` and manually
opening the page cannot satisfy this automation gate. This fixture tests
`avocet-dom`; Spoonbill service tests do not substitute for it.

Status 2026-11-11: implemented by the `.#browser` shell, `scripts/test-dom-fixture.sh`,
`misc/dom-fixture/run-browsers.mjs` and its self-test, and a PR workflow step
(§9.1). The gate passes locally on aarch64-darwin in Chromium 141 and WebKit 26,
including the injected-failure and SIGINT/SIGTERM cleanup proofs. The first Linux
CI run is pending, because the PR workflow previously targeted only `master`.

### F4: bounded HTML conformance and CSS policy

Close F4 with a documented case matrix for the supported HTML profile, together
with deterministic JVM/Scala.js checks and browser evidence for parsing and
updates. Include void elements, paragraph/table/form normalization, mixed and
whitespace-sensitive content, adjacent/empty text, CR/leading-newline handling,
namespaces and parser-sensitive content. Classify each case as supported,
diagnosed or explicitly outside the profile. Preserve §2.2's no-pretty-printing
contract; do not imply full HTML-parser conformance or introduce text normalization
without F5's separate compatibility decision.

For CSS, align [the grammar](core/shared/src/main/scala/levsha/dsl/Policies.scala),
tests and README on comment rejection. Test actual comments inside allowed
functions, unterminated comments, arithmetic and permitted quoted literal values.
Preserve rejection of resource-loading/executable functions, escapes and attribute
breakout, and keep URL scheme policy distinct from origin/application authority.
Use browser parsing and updates for accepted policy examples; bounded unit tests
alone do not prove every CSS context safe. Record any deliberately changed
acceptance behavior and migration impact. This is a repair recommendation, not a
claim that the 2.0.0 parser already rejects comments.

Add bounded repeated mount/update/remove/dispose cases to the DOM fixture using
its existing handler/index diagnostics. Demonstrate that counts return to their
declared baseline and retired handlers no longer run. Run them through F3 in
both engines. Counts are not heap measurements: record browser heap evidence
separately when available, and note that Node's retained-heap assertion is
conditional on an exposed GC function.

Status 2026-11-11: the comment rejection, the 21-case matrix (7 supported,
12 diagnosed, 2 outside) and the churn checks are implemented and pass in both
engines; see the [guide](IMPLEMENTATION-GUIDE.md#52-f4--supported-html-and-lifecycle-evidence-implemented-2026-11-11).
No browser heap evidence was recorded. A probe found that a removed node put back
under the root dispatched the current handlers of its old position (§1.4). The
adapter now resolves event targets and removals by node identity, with three
fixture regressions.

## 9. Validation commands and environment

Use the applicable repository-local `flake.nix` for dependency resolution,
installation, builds, tests, local publication and benchmarks. No host tool
installation or alternative environment manager. Commands below describe current
facilities; no build was run solely for this documentation update.

### 9.1 Existing Avocet commands

From Avocet:

```bash
nix develop . --no-write-lock-file --command sbt +test domFixture/fastLinkJS
nix develop . --no-write-lock-file --command sbt 'bench/compile'
```

`+test` now includes core JVM/JS suites, DOM `EventTableTest` and the fixture
project through aggregation. Linking the fixture is not executing it in a browser.
The benchmark project remains outside root aggregation.

The browser fixture and server are already implemented:

```bash
nix develop . --no-write-lock-file --command node misc/dom-fixture/serve.mjs
```

Open `http://127.0.0.1:8765/misc/dom-fixture/index.html` and inspect all checks;
stop the owned server afterward. That is the manual path. The automated path
links the fixture and runs it in Nix-pinned headless Chromium and WebKit, then
self-tests the runner's failure and cleanup behavior:

```bash
nix develop .#browser --no-write-lock-file --command bash scripts/test-dom-fixture.sh
```

The `.#browser` shell adds the Playwright driver and browsers from the locked
nixpkgs input to the default sbt/JDK/Node tools. There is still no profiling
environment.

Use the scenario benchmark for comparable measurements:

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffScenariosBenchmark.*'
```

`bench/BASELINE.md` records M1/M2 workloads and exact historical settings. The
older `DiffRenderContextBenchmark` includes text-reporting overhead; do not use it
as an isolated engine-allocation measurement. JVM `AllocationGuardTest` and
`RetainedMemoryTest`, JS `JsRetainedMemoryTest`, and shared performance/trim tests
already exist. Browser retained memory and broad service patch-byte/latency
coverage are separate measurements, not automatically established by those tests.

### 9.2 Spoonbill candidate integration

**F1 is complete.** Spoonbill 10.0.0 selects published Avocet 2.0.0. Its
[release evidence](https://github.com/natural-transformation/spoonbill/releases/tag/v10.0.0)
records all 26 modules published and the Maven Central-only independent consumer
check passing 27 tests. The [local verification record](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/remaining-work-after-portable-recovery.md#local-verification)
also records resolved Avocet 2.0.0 JARs, 1,541 Scala tests, 122 Node tests and
Chromium/WebKit recovery handoffs. Those are recorded results, not reruns for this
refresh. That source checklist's unchecked publication boxes predate the release;
use the release evidence for their subsequent completion. Neither independent
consumer packaging nor recovery handoffs close F2's specific renderer matrix.

To recheck the released renderer baseline from Spoonbill 10.0.0, inspect both
core/events JARs and run the relevant suites:

```bash
# Spoonbill 10.0.0 checkout; avocetVersion is already 2.0.0.
nix develop . --no-write-lock-file --command \
  sbt 'show spoonbill/Compile/dependencyClasspath' 'spoonbill/test' 'testkit/test'
```

The focused renderer suites can also be selected directly (package names are
`spoonbill`, although their source directory still contains `korolev`):

```bash
nix develop . --no-write-lock-file --command sbt \
  'spoonbill/testOnly spoonbill.server.internal.Html5RenderContextSpec spoonbill.internal.RenderLifecycleSpec spoonbill.DevModeSavedRenderContextSpec spoonbill.server.internal.services.InvalidDevRenderContextSpec'
```

Record producer release, consumer commit and resolved artifact paths for every
new run. Do not overwrite released artifacts with `publishLocal` or rewrite tags.

**Future Avocet changes.** Publish core/events under a unique local snapshot,
select that exact version in an isolated consumer, and verify resolution:

```bash
# Avocet checkout: replace the example with a unique commit/iteration version.
env RELEASE_VERSION='2.0.1-PRODUCER_SHA-f4-ITERATION-SNAPSHOT' \
  nix develop . --no-write-lock-file --command \
  sbt 'show coreJVM/version' 'show eventsJVM/version' \
  'coreJVM/publishLocal' 'eventsJVM/publishLocal'
```

Replace `PRODUCER_SHA` and `ITERATION` before running; the example does not
select a public release version. Local publication is not a remote release.
The selected consumer version must
match exactly; stale resolved artifacts must be corrected before drawing
conclusions, rather than treating a mixed classpath as evidence about the candidate.

For renderer regressions or future candidates, run relevant client checks and
real-service browser tests:

```bash
# Isolated Spoonbill checkout.
nix develop . --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command \
  bash scripts/test-sensitive-acknowledgement.sh
```

The first two commands use synthetic client transport. The last starts an actual
Scala service, but still needs F2's targeted long-name/style/collision/SSR-first-patch
cases to cover those renderer repairs end to end. Consult
[Spoonbill's browser guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/browser-tests.md) for further service,
timer/component and authentication checks appropriate to the changed behavior.
Test `avocet-dom` separately; Spoonbill does not execute that adapter.

Only regenerate `spoonbill/generateBrowserProtocol` if protocol declarations change.
No move opcode or bootstrap protocol change shipped in Avocet 2.0.0. Record each
command, result, version and remaining gap. For performance, also record hardware,
Nix lock, runtime, warmup/forks, samples and raw evidence location.

## 10. Compatibility and migration

The 2.0.0 major release rejects saved buffers from 1.0.0, enforces stricter lifecycle/name rules, and changes DOM misuse exceptions to `RenderException`. Selected legacy factory/constructor signatures were retained and have regression coverage; this does not erase the major-release behavior changes.

Treat source, binary, serialized-buffer, DOM/bootstrap and wire compatibility separately for every future release. Spoonbill 10.0.0 quarantines invalid legacy development render buffers, retains a `.invalid` marker and requests a terminal full-page reload instead of attaching a fresh baseline to stale browser DOM. Repeated connections and service restarts must continue to reject that stale session; a fresh HTTP session may initialize normally. Guarded sessions bypass the legacy development-buffer restore path. Preserve failed-quarantine evidence and input/resource cleanup; do not broaden this recovery into deletion or rebuilding of durable presentation snapshots, application state or authorization data. See [Spoonbill's migration guide](https://github.com/natural-transformation/spoonbill/blob/f0d456344359eb63ea4b138d28352860eef49f3b/docs/migration-v10.md) and `InvalidDevRenderContextSpec`.

| Change | Default release treatment |
| --- | --- |
| Test improvements and documentation | No public runtime contract change. |
| Correctness/security fix | Document changed behavior and validate consumers; use the smallest appropriate release, with a major release if the public contract necessarily changes. |
| Optional validator/combinators | Additive only after checking existing custom contexts, optimizer behavior and binary signatures. |
| New mandatory limits or changed text segmentation | Explicit compatibility review; reject/normalize behavior and bootstrap consequences documented. |
| Performer/ID/event representation change | Assume a major-version candidate until actual compatibility evidence proves otherwise. |
| Saved-buffer format change | Version and validate; reject unsupported state or rebuild through the integration's approved path. |
| New Spoonbill wire operations | Coordinated server/client versioning and rollout, with explicit old-client behavior. |

Compatibility and migration apply to every repair and architecture release, independently of demand for keyed/widget features. Keep existing unkeyed APIs available during evaluation. If a new high-level lifecycle or identity API proves better, provide migration examples before deprecating old paths. Avoid a compatibility layer that retains two competing sources of truth for node identity.

Migration fixtures must include an unchanged old consumer, an opt-in new consumer, a custom `RenderContext`, a custom `ChangesPerformer`, a Scala.js DOM application and Spoonbill. Compilation is necessary but insufficient: compare runtime output, events and resource cleanup.

Rollback for a new feature means disabling new keyed/ownership usage or deploying a compatible prior client/server pair and rebuilding affected views. Do not interpret incompatible buffers with an older reader. Authority and business data remain outside Avocet and must not be rolled back as a renderer workaround.

## 11. Documentation and release evidence

For each delivered milestone, document encoding/trust rules, supported interpreter behavior, resource bounds and lifecycle, API/format compatibility and the resolved consumer version. Explain source/binary, saved-buffer, bootstrap and wire compatibility separately. A code fix is not yet a delivered Spoonbill benefit until the artifact release and dependency update are tracked. Remote publication/merging/deployment remain explicit release actions.

The 2.0.0 README already replaces the blanket constant-memory description with a scoped allocation/retention model. Maintain that accuracy: report allocations separately from retained buffers, callbacks and DOM roots, identify optimized/fallback paths and tie claims to workloads. Do not weaken security or exact output to retain a performance claim.

The existing review is historical evidence for the original plan and source baseline. Preserve it unchanged; link new regression results and design decisions as work proceeds. Eezo's [live-layer documentation](https://eezo.io/docs/explanation/the-live-layer) motivates validation, keyed moves and explicit subtree ownership, but does not dictate Avocet's execution model.
