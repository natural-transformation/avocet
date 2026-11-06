# Avocet contracts and acceptance reference

Status: proposed contracts supporting [Improvement plan revision 2](IMPROVEMENT-PLAN.md).
Date: 2026-11-05.

The roadmap owns priorities and milestone gates. This reference owns behavioral
contracts, validation and migration detail. M1 repairs supported rendering; M2
establishes the clean long-term API/lifecycle/resource foundation. Only M3 keyed
reconciliation and M4 external-subtree ownership are conditional on demonstrated
application demand. Contracts for those features apply if they proceed.

Evidence comes from the source baseline and reported probes in the
[review](IMPROVEMENT-PLAN-REVIEW.md). The probes were not rerun for this document
revision. JVM serializer/core observations, actual Scala.js adapter behavior,
real browser parsing and performance measurements remain distinct evidence.

## 1. Security and correctness contracts

### 1.1 Trust and encoding

Use the following context-specific contracts. M1 fixes demonstrated encoding defects; M2 exposes the typed safe/trusted boundary without weakening these requirements:

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

Deliver a documented data-safe authoring profile in M2 where it materially prevents mistakes; retain a clearly identified low-level trusted interface for advanced integrations. The existing `Document.Node`, `Attr` and `Style` distinctions are useful, but do not by themselves prove encoding safety or purity. Do not introduce a misleading universal `SafeString`. If a trusted HTML capability is ever added, it must carry a narrowly defined construction and consumption contract rather than an automatic `String` conversion.

Tests must demonstrate inert data through real parsing and later updates. Diagnostics may contain bounded error codes, positions and schema/tag metadata; they must not include text, attribute values, arbitrary keys, callback contents, credentials or entire rendered documents by default.

### 1.2 Equality and caches

- A non-cryptographic hash match is a candidate for equality, not proof of equality.
- Verify exact relevant names and values before suppressing a change or reusing a cached header. Account for namespace, operation kind and style/attribute distinctions.
- For subtree skipping, define an exact comparison that ignores irrelevant positional metadata where necessary. A larger hash alone does not satisfy this contract.
- Exercise deliberately colliding values through test seams and known collision cases. Tests must not depend on waiting for random collisions.
- Bound dynamic-name caches and document their scope, lifetime and eviction. Static vocabulary can use a separate compact representation.
- Correctness fixes apply on production paths, even when optional diagnostic validation is disabled.

Cached payloads must not depend on a shared mutable buffer cursor being left in the expected position. Evaluate immutable header payloads or absolute reads together with the cache lifetime policy.

### 1.3 Bounds and failure containment

Specify limits for depth, sibling count, node count, identifier/name lengths, text and attribute bytes, total rendered bytes, patch operations/bytes, keyed children, diagnostic count and cache size. Limits may be configurable, but arithmetic and representation limits are mandatory checks.

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

Add an opt-in validating interpreter/decorator around rendering operations. The final public name is to be settled by compile-tested examples; `ValidatingRenderContext` is a working name.

Separate independently selectable checks:

- Structural protocol: balanced and matching nodes, legal operation order, duplicate attributes and bounded state.
- Namespace-aware HTML shape: void elements, paragraph auto-closing, nested forms and table normalization/foster parenting.
- Interactive bootstrap shape: text segmentation, unsupported parser-normalized constructs and identity mapping assumptions.
- Key/ownership rules once those capabilities exist.

Use deterministic structured errors with stable codes and bounded location information. Offer a fail-fast mode and a capped diagnostic collection mode for development tooling. Validation policy is explicit; production wrappers are opt-in initially, while mandatory internal memory/identity checks remain active in all modes.

Support stateful rendering integrations without hiding `currentId`, misc callbacks or lifecycle behavior. A plain decorator over `RenderContext` is not automatically a drop-in replacement for `StatefulRenderContext`; test the actual Spoonbill integration shape.

Compile-time diagnostics may handle obvious literal misuse, but dynamic templates must receive equivalent runtime checks. Optimized and ordinary templates must produce equivalent validation results and semantics.

### 2.2 SSR and DOM agreement

Define a supported interactive HTML profile. In the equation below,
`renderInteractiveHtml` denotes serialization through `Html5RenderContext` with
`TextPrettyPrintingConfig.noPrettyPrinting`; it is notation for the contract,
not an existing helper or the current `renderHtml` function. For that profile:

`apply(diff(old, next), browserParse(renderInteractiveHtml(old)))` must produce the same supported DOM structure as `browserParse(renderInteractiveHtml(next))`.

Also compare against direct DOM construction. Define normalization explicitly for namespaces, attribute ordering and text segmentation; do not normalize away misplaced nodes, wrong attributes or lost identity to make tests pass.

Adjacent/empty text handling is a design decision: either consistently coalesce/elide them across interpreters and bootstrap indexing, or encode boundaries understood by every backend. Do not reject convenient string composition solely because one backend currently counts text operations differently. Bootstrap changes require corresponding Spoonbill tests, including its preset-ID mode and nested components.

Preserve meaningful whitespace in mixed content, `pre` and `textarea`; specify CR/LF and malformed-Unicode handling. Pretty-printing is not assumed semantics-preserving for interactive markup. Compare its documented behavior separately rather than trimming text to make tests agree.

Use `Html5RenderContext` with `TextPrettyPrintingConfig.noPrettyPrinting` for the initial interactive SSR contract, matching [Spoonbill's actual renderer](../spoonbill/modules/spoonbill/src/main/scala/korolev/server/internal/Html5RenderContext.scala). Avocet's [HTML5 interpreter](core/shared/src/main/scala/levsha/impl/Html5RenderContext.scala) inherits ordinary encoding methods but handles void-element closing separately.

The existing [renderHtml](core/shared/src/main/scala/levsha/text.scala) instead creates `XhtmlRenderContext` and defaults to pretty-printing. The review's P11 reports `<br></br>` from that serializer; add a browser fixture for the consequence rather than labeling serialized output as a browser result. Preserve a deliberate XHTML entry point for XML consumers if `renderHtml` changes, and document the compatibility impact. Test parser newline normalization and whitespace-sensitive content even with pretty-printing disabled.

Begin with diagnostics for unsupported shapes. Any later change that rejects previously accepted production markup requires a compatibility note, migration examples and an appropriate release boundary. Avoid silent HTML repair inside the diff engine.

## 3. Type-safe functional authoring

### 3.1 Preserve a small algebra and multiple interpreters

Keep template descriptions independent of effects and host services. Support interpretation into HTML, compact diff buffers, validation and test representations. Prefer composition over framework inheritance or global mutable configuration.

Improve discoverable combinators for fragments, conditional content, iterable rendering and misc/event adaptation where the existing API is awkward. Existing `when`, sequence conversion, `Node`/`Attr`/`Style` distinctions and optimizer behavior are foundations, not missing features to duplicate.

Consider typed attribute/style keys (`AttrKey[A]` / `StyleKey[A]`, proposed names) with explicit encoders and category-specific builders that avoid sorting mixed content at runtime. For example, a numeric attribute can accept an integer and a URL attribute can accept a value checked under its declared URL policy, without converting every value through an unrestricted string API. Keep validated dynamic-name construction available. Provide explicit composition alongside existing conversions, and evaluate separate empty node/attribute/style values where they clarify inference.

Proposed typed concepts, subject to API evaluation:

| Concept | Meaning | Must not mean |
| --- | --- | --- |
| `NodeKey` | Logical identity within one sibling collection | Global DOM ID or authorization token |
| `NodeHandle` | Renderer-issued runtime node identity | Caller-chosen key or current position |
| `TreePosition` | Location/ordering in a particular tree | Stable identity across moves |
| Validated name/URL helpers | A specific syntactic/policy guarantee | Universal HTML safety |
| Ownership boundary | Which renderer may update descendants | Trust or isolation from browser code |

Use opaque types or small validated value types where they improve misuse prevention without unnecessary boxing. Do not make core consumers import an effect library to use these types.

### 3.2 Lifecycle API

Prototype a high-level render-cycle API that performs the required begin/render/finalize/diff/publish sequence consistently. Preserve low-level access for integrations that need it, but make incomplete lifecycle sequences difficult in the normal path.

A scoped builder or stage-specific result can describe completed render data. Do not claim Scala typestate makes values linear or prevents all retained-reference misuse; enforce closed/disposed states at runtime where required. Avoid per-node wrapper allocation merely to express lifecycle stages.

Make error reporting explicit at high-level boundaries. Low-level callbacks may still throw, but their failure must become the documented aborted/invalidated lifecycle outcome rather than a reusable half-published state.

The existing fast performer borrows mutable `FastId` and source-buffer arguments. Document synchronous consumption and prohibit retaining those references after the callback. Provide a separate owned/copying adapter for queued or asynchronous consumers. Type names alone cannot enforce these lifetimes, and the fast path should not require allocating a slice wrapper per operation. This is shared M2 lifecycle/API work, independent of whether keyed reconciliation proceeds.

### 3.3 API acceptance examples

Compile examples for static HTML, conditional fragments, nested reusable templates, a custom attribute/namespace, several events on one element and a custom interpreter; add editable keyed rows only when M3 proceeds. Exercise both optimized and ordinary forms through the operation/evaluation trace harness established in M1. Add negative compile tests for violating exposed API capabilities; when keyed identity types are introduced, include mixing keys, handles and positions.

Measure source complexity, inferred types, diagnostic clarity, compilation cost and generated-code size alongside runtime costs. Keep the API useful for advanced composition; a shorter introductory example is not enough evidence.

Define construction-time versus render-time evaluation, once-only evaluation of dynamic expressions, operation order, exception timing and reusable collections versus single-use iterators. [DslOptimizerMacro](core/shared/src/main/scala-3/levsha/dsl/DslOptimizerMacro.scala) recognizes particular DSL shapes: every new public helper must have a tested lowering or a correct, documented fallback whose allocation cost is measured. Do not claim allocation-free behavior merely because a call is wrapped in `optimize`. The optimizer must preserve these documented semantics. Add operation-trace and evaluation-counter tests for dynamic tags, options, loops and spliced documents; bind expressions once and use a safe fallback when rewriting cannot preserve semantics. Recommending pure render functions does not excuse duplicated evaluation of arbitrary expressions the API accepts.

In the DOM adapter, replace ambiguous `Any`/Boolean event callback results in a new API with explicit propagation/default-action outcomes. Preserve or adapt legacy behavior intentionally, with tests for multiple handlers and phases.

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

The preferred hypothesis is stable handles for a new keyed-capable backend. It is not a settled implementation choice. Select it only after measuring memory and proving bootstrap, events, removal and recovery. If neither design meets the gates, ship validation/lifecycle work independently and defer keys.

The decision record must specify SSR handle assignment, direct-DOM mounting, nested components, attribute IDs versus renderer IDs, handle generation/reuse, parent lookup, root isolation, saved-buffer compatibility and integration callbacks.

### 4.3 Change protocol and algorithms

Define insertion-before, move and in-place text-update semantics, their preconditions and ordered application. An explicit `setText` operation can preserve a text node where today's `createText` replaces it; verify its value separately from keyed element moves. Unknown handles, wrong roots, impossible parents and invalid order references must fail deterministically. Runtime handles and node type/namespace checks should detect mismatches where practical; a tag-name check alone cannot distinguish two same-tag rows.

Prefer a straightforward O(n) indexing pass and a bounded O(n log n) reorder strategy for general keyed siblings, with measured fast paths for append/prepend and unchanged lists. A longest-increasing-subsequence optimization is optional after correctness. Do not demand globally minimal edit scripts.

Keep bookkeeping proportional to the live keyed collection and release removed entries. Preserve the buffer engine where possible; a full object-tree allocation is a separate design proposal.

Do not add required methods to public performer traits and call the change compatible. Introduce an explicit new capability/version or release a major version with coordinated adapters. A backend without move support must reject keyed mode at setup or select an explicitly documented non-preserving mode; no silent fallback that promises identity preservation while replacing nodes.

## 5. Memory and lifecycle engineering

Measure allocations and retained memory separately, on JVM and Scala.js. Report renderer-owned storage, application input storage and backend DOM costs separately.

Required work:

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

Initial proposed performance gates, to calibrate and freeze against M2 baselines before optimization work (they must not delay necessary M1 security repairs):

- No monotonic growth in renderer-owned live entries after equivalent render/remove/unmount cycles; after disposal, no strong roots remain from Avocet registries.
- Optional features allocate no keyed/validation structures when disabled.
- For unchanged unkeyed workloads, investigate regressions above 5% throughput or 10% p95 latency, and any new allocation proportional to rendered node count. These are investigation gates, not measured achievements or permission to weaken correctness.
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

Retain and repair the publicly documented Scala.js DOM backend. The missing-finalization sequence is reproduced in JVM core and multi-handler collapse is visible in source; actual browser-adapter verification remains required. A render/re-render/dispose smoke test is the first fixture, not the entire support bar. Update the backend alongside renderer changes. Cover root disposal, descendant cleanup, multiple event types/phases on one node, event propagation after moves and reentrant callbacks during patch application. Node-based Scala.js unit tests do not substitute for native browser DOM tests.

Specify simulated framework capture/target/bubble separately from native listener registration and propagation. Include non-bubbling `focus`/`blur`, events stopped before reaching the root, default prevention and text-node targets. A passing simulated propagation test does not establish support for every native event.

### Spoonbill

Keep responsibilities separate and test the following interfaces together:

- `RemoteDomChangesPerformer` serialization and the Scala-owned generated client protocol.
- Browser node indexes, bootstrap/preset IDs, root resets and incremental patches.
- Event counters, immutable handler publication, view revisions and obsolete-event rejection.
- Nested component identity/state, element references and lifecycle resources when nodes move or disappear.
- Guarded output admission, ownership takeover, sensitive cleanup and reconnect baseline reconstruction.

The current protocol has no move opcode. New opcodes require regenerated client constants, operand validation and an explicit rollout/version rule. An old client must not partially execute an unfamiliar patch. Prefer a version check before live interaction and a controlled bootstrap refresh when incompatible; do not replay user actions during that refresh.

Old unkeyed applications must remain testable against explicitly resolved candidate artifacts, not the released dependency still selected by the build. A new keyed client/server pairing must also be tested through real service transport. Browser DOM equality alone cannot establish correct authorization or event delivery.

Reference contracts: [Spoonbill protocol generation](../spoonbill/docs/client-protocol-generation.md), [browser checks](../spoonbill/docs/browser-tests.md), [guarded recovery](../spoonbill/docs/clustered-browser-authentication.md). These sibling links require both repositories to be checked out.

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

Use reproducible random seeds with shrinking. Keep structural invalid-input generators separate from valid browser-HTML generators: rejecting an invalid tree is success, while silently catching an unexpected exception is not. Include deliberately injected faulty performers/encoders to establish that the tests detect relevant mistakes.

## 9. Validation commands and environment

All dependency resolution, builds, tests and benchmarks run through the applicable repository-local `flake.nix`. No host tool installation or alternative environment manager. If a required browser/profiler is unavailable, add it through an authorized Nix/build change; do not install it on the host.

### 9.1 Existing Avocet commands

Run from the Avocet repository. These are derived from the current build and CI, but were not executed while preparing this plan:

```bash
nix develop . --no-write-lock-file --command sbt projects
nix develop . --no-write-lock-file --command sbt +test
nix develop . --no-write-lock-file --command sbt 'coreJVM/test' 'coreJS/test' 'eventsJVM/test' 'eventsJS/test' 'dom/test'
nix develop . --no-write-lock-file --command sbt 'dom/Compile/fastLinkJS'
nix develop . --no-write-lock-file --command sbt 'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffRenderContextBenchmark.*'
nix develop . --no-write-lock-file --command sbt 'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*StaticRenderingComparision.*'
```

M1 uses focused regressions plus the relevant existing suites. The JMH commands above support M2/M3 decisions and do not gate urgent security repairs. The full test command and targeted suites overlap; choose the relevant checks per change rather than repeatedly running both without a reason. Root aggregation excludes `bench`, so benchmarks need their own invocation. The existing diff benchmark uses a text-reporting performer; it is not an isolated measure of engine allocation. Add counting/no-op and actual backend measurements before drawing allocation conclusions.

The current flake provides sbt, JDK and Node. It does not define a native-browser or retained-heap profiling environment. Reuse Spoonbill's Nix browser facilities for targeted M1 browser evidence. Add/document an Avocet adapter harness through that environment or a justified Nix extension; it must execute the Scala.js adapter rather than only Spoonbill's client. Add retained-heap profiling facilities and exact commands when M2 memory measurements begin. Planned browser suites and API compatibility checks do not exist merely because they are named in this document.

No DOM test sources were found in the reviewed checkout; a successful current `dom/test` task would not establish browser coverage. Add actual fixtures before using that task as evidence. Review JMH result consumption as well, so dead-code elimination or reporting overhead cannot create misleading wins.

### 9.2 Spoonbill candidate integration

Spoonbill currently selects `avocet-core` and `avocet-events` 1.0.0 in [build.sbt](../spoonbill/build.sbt). A source edit in Avocet does not change that dependency. For implementation validation, use an isolated consumer checkout and a unique candidate version. The following is the existing local-publication mechanism, not a command executed for this revision:

```bash
# From Avocet; replace the example with a unique version for the tested commit/run.
env RELEASE_VERSION=1.0.0-plancheck-SNAPSHOT \
  nix develop . --no-write-lock-file --command \
  sbt 'show coreJVM/version' 'show eventsJVM/version' \
  'coreJVM/publishLocal' 'eventsJVM/publishLocal'
```

Set `avocetVersion` in the isolated Spoonbill `build.sbt` to the exact same candidate value. Never republish a released version such as `1.0.0` locally. The consumer already enables a remote snapshot resolver for snapshot versions; local `publishLocal` supplies the candidate through the normal local resolver, without remote publication.

Verify resolution before running checks:

```bash
# From the isolated Spoonbill checkout.
nix develop . --no-write-lock-file --command \
  sbt 'show spoonbill/Compile/dependencyClasspath' 'spoonbill/test' 'testkit/test'
```

Record the resolved Avocet JAR paths, candidate version and producer commit; verify that both core and events use that candidate. Then run relevant client checks:

```bash
nix develop . --no-write-lock-file --command sbt 'spoonbill/generateBrowserProtocol'
nix develop . --no-write-lock-file --command node --test misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
```

Protocol generation is needed only for protocol changes. Existing Node and `test-native-browsers.sh` suites use synthetic client transport and do not, by themselves, execute the candidate Scala renderer. Their success is necessary regression evidence for relevant client changes, not proof of renderer fixes.

Add targeted long-name, style-encoding, collision and SSR/first-update cases to a real-service fixture that invokes the candidate renderer. Reuse the documented service/browser environment; for example, this existing command runs actual Scala service behavior but needs additional repair-specific cases to establish those fixes:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command \
  bash scripts/test-sensitive-acknowledgement.sh
```

Keep dedicated Scala.js DOM adapter tests because Spoonbill uses its own performer/client. A passing Spoonbill fixture cannot establish `avocet.dom` support. Follow the [browser guide](../spoonbill/docs/browser-tests.md) for real-service and authentication recovery checks, including required environment variables and disposable services. M3 must add targeted keyed/event/identity fixtures; the existing checks do not establish those new properties.

For every meaningful check, record command, commit/configuration, pass/fail, short result and the next action for a failure. For performance, also record hardware, OS, Nix lock, JVM/JS engine, warmup, forks, sample count, variance, fixture sizes and raw result location. Do not turn a source-level expectation into a measured result.

## 10. Compatibility and migration

Treat source, binary, serialized-buffer, DOM/bootstrap and wire compatibility separately.

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

Replace the README's blanket constant-memory and no-additional-allocation claims with workload-scoped evidence. Report allocations separately from retained buffers, caches, callbacks and DOM roots, and identify optimized versus fallback paths. Do not weaken security or exact output to retain an old performance claim.

The existing review is historical evidence for the original plan and source baseline. Preserve it unchanged; link new regression results and design decisions as work proceeds. Eezo's [live-layer documentation](https://eezo.io/docs/explanation/the-live-layer) motivates validation, keyed moves and explicit subtree ownership, but does not dictate Avocet's execution model.
