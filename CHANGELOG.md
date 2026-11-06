# Changelog

## Unreleased (M2: durable architecture and API)

Builds on the M1 repairs below. Contract references are to
[IMPROVEMENT-CONTRACTS.md](IMPROVEMENT-CONTRACTS.md); evidence per package is
in [IMPLEMENTATION-EVIDENCE.md](IMPLEMENTATION-EVIDENCE.md).

### Added

- **Lifecycle API (§3.2).** `avocet.RenderRoot[M]` owns one
  `DiffRenderContext`, one performer and the misc entries of the published
  generation, and runs render → validate → finalize → diff → publish in one
  call. `render` returns a `RenderOutcome`: `Published(generation)`,
  `Rejected(cause)` (template, validation or limit failure; the previous
  tree and misc entries stay published) or `Invalidated(cause)` (performer
  failure; backend writes are not rolled back, the root refuses renders
  until `reset()` or `dispose()`). States `Idle`/`Rendering`/`Invalid`/
  `Disposed` are explicit; misuse throws `RenderException(INVALID_STATE)`.
  `save()`, `trim()`, `bufferCapacity`, `generation` and `misc` are
  exposed; `renderWith` hands the template a `StatefulRenderContext`.
- **Owned changes.** `avocet.impl.OwnedChange` and `OwnedChangesPerformer`
  (sink and `Buffer` forms) copy every borrowed argument of the fast
  performer, for queued or asynchronous consumers. The borrowing rules of
  `FastChangesPerformer` are documented on the trait.
- **Composable validation (§2.1).** `avocet.ValidatingRenderContext`
  decorates any `RenderContext` (`ValidatingRenderContext.stateful` for a
  `StatefulRenderContext`, forwarding `currentId`, `currentContainerId`,
  `subsequentId` and misc). `Validation` selects `structural`, `htmlShape`
  and `interactive` checks independently and the mode: `FailFast` throws a
  `ValidationException` before the operation is forwarded; `Collect(max)`
  caps the errors and still forwards. Errors are `ValidationError(code,
  position, element, detail)` with 28 stable codes and no payload.
  `RenderException.Code.InvalidDocument` is the code of
  `ValidationException`, which now extends the (no longer final)
  `RenderException`.
- **Typed authoring (§3.1).** `AttrKey[A]`, `BooleanAttrKey` and
  `StyleKey[A]` with explicit encoders (`string`, `int`, `long`, `tokens`,
  `url`, `boolean`, `px`, `css`); names validated at construction
  (`validated` returns an `Either`). `CheckedUrl` with `UrlPolicy`
  (`default`, `web`, `relativeOnly`), `CssValue` for data-safe declaration
  values, and the explicit escape hatches `trusted.inlineHandler`,
  `trusted.srcdoc`, `trusted.url`, `trusted.css`, `CheckedUrl.trusted`,
  `CssValue.trusted`. New combinators `fragment`, `each`, `noNode`,
  `noAttr`, `noStyle`. All of them have `DslOptimizerMacro` lowerings; the
  evaluation laws are documented on the `avocet.dsl` package and asserted
  by `OptimizerContractTest`.
- **Buffer retention (§5).** `DiffRenderContext.trim()` shrinks the
  backing buffer to twice the baseline (never below the initial size);
  `TrimPolicy.Automatic(afterSwaps, slack)` does it after a bounded number
  of consecutive small publishes; `bufferCapacity` and `baselineSize` are
  readable. `DiffRenderContext.apply` and the constructor gained a
  `trimPolicy` parameter (a compatible 3-argument constructor remains).
- **Scala.js adapter.** `avocet.dom.render(target, validation)` runs the
  adapter with validation; the adapter itself is now implemented on
  `RenderRoot`.
- Tests: `RenderLifecycleTest`, `OwnedChangesTest`,
  `ValidatingRenderContextTest`, `TypedDslTest` (with compile-negative
  cases), `OptimizerContractTest`, `BufferTrimTest`, `MigrationMatrixTest`,
  `RetainedMemoryTest` (JVM), `JsRetainedMemoryTest` (Scala.js), three
  more `AllocationGuardTest` cases, three more browser fixture checks.

### Changed (behavior)

- `avocet.dom`: an invalid, disposed or reentrant render now fails with
  `RenderException(INVALID_STATE)` instead of `IllegalStateException`.
- `DiffRenderContext.save()` after `swap()` writes the stale construction
  buffer as empty instead of copying the old baseline into it. A finalized
  document that was not swapped out is saved as before (Spoonbill's dev
  mode path). The format version stays 2.
- `RenderRoot` restored from a snapshot starts from the baseline only; a
  finalized candidate in the snapshot is discarded.

### Compatibility

- Source and binary: every M1 API is unchanged. Additions: a 4-argument
  `DiffRenderContext` constructor and `apply` (with `trimPolicy`); the
  1.0.0 3-argument constructor and `apply` remain as overloads, so a consumer
  compiled against 1.0.0 keeps resolving them (the first revision of this
  change had replaced the factory; a review caught it and a JVM test now
  checks the legacy signatures). Two deliberate exceptions: `RenderException`
  loses `final` (so `ValidationException` can extend it), and the
  `avocet.dom` adapter throws `RenderException` where it threw
  `IllegalStateException`.
- Saved buffers: format 2, readable by M1 readers; the stale-buffer change
  only makes snapshots smaller.
- Wire protocol and bootstrap: unchanged. No new opcodes.
- The migration matrix (old consumer, opt-in new consumer, custom
  `RenderContext`, custom `ChangesPerformer`, Scala.js DOM application,
  Spoonbill candidate) is recorded in the evidence file.

### Known limitations

- `htmlShape` and `interactive` checks assume lowercase HTML names and the
  element tables in `HtmlShape`; they diagnose the common parser
  normalizations, not the whole parsing algorithm.
- `CheckedUrl` and `CssValue` are scheme and syntax policies; they do not
  make a destination or a style value harmless.
- The owned performer allocates one id and one string per name or value;
  synchronous consumers should keep using the fast API.
- Text inside `script`/`style` is still passed through the generic text
  encoder; `RAW_TEXT_CONTENT` diagnoses it, a trusted raw-text API is not
  provided.

## Unreleased (M1: repaired baseline)

Security and correctness repairs of the supported rendering paths. Defect
IDs refer to [IMPROVEMENT-PLAN.md](IMPROVEMENT-PLAN.md); the evidence per
package is in [IMPLEMENTATION-EVIDENCE.md](IMPLEMENTATION-EVIDENCE.md).

### Fixed

- **D1, SSR style encoding.** `XhtmlRenderContext` and `Html5RenderContext`
  now encode style values for the enclosing quoted `style` attribute. A value
  such as `red" onmouseover="alert(1)` stays data; it used to close the
  attribute and create an executable one.
- **D2, header cache.** The thread-local header cache that confused
  same-name attributes and styles (and colliding names across contexts) is
  gone. Headers are written directly; output no longer depends on which
  request a thread saw first.
- **D3, long names.** Tag, attribute and style names up to 1024 chars render.
  `blockquote`, `figcaption`, `linearGradient`, long custom elements and
  `-webkit-border-bottom-left-radius` used to throw `BufferOverflowException`.
- **D4, exact equality.** Hashes are now only a fast rejection test. Tag
  names, text, attribute names and values and whole subtrees are compared
  byte-for-byte before a change is suppressed. `Aa`/`BB`, `t86388`/`t118091`
  and a subtree collision pair are kept in the suite.
- **D5, limits.** Sibling indexes stop at 32767 with a `RenderException`
  instead of wrapping negative; nesting stops at 255; buffer growth uses
  checked arithmetic and sizes the active half; invalid initial capacities
  are rejected; name/text lengths have explicit maxima.
- **D6, Scala.js DOM adapter.** `avocet.dom.render` failed on its first call
  (no `finalizeDocument`), dropped all but one handler per element
  (`toMap`), and never released anything. It now runs the full lifecycle,
  keeps every handler, publishes handlers only after a successful diff,
  removes descendant index entries, delivers non-bubbling events through a
  capture listener, rejects reentrant renders, and has `dispose(target)`.

### Changed (behavior)

- Invalid dynamic names (`"a b"`, `"x=\"y"`, empty) are rejected with
  `RenderException(INVALID_NAME)` by every interpreter. Custom elements,
  namespaced attributes (`xlink:href`) and CSS custom properties remain valid.
- `DiffRenderContext` enforces its lifecycle: `diff` requires
  `finalizeDocument`; rendering into a finalized buffer requires `swap()` or
  `reset()`; `swap()` and `save()` refuse a half-built document;
  `finalizeDocument` refuses unclosed nodes. `reset()` fully restores the
  construction state and is the recovery path after an exception.
- Attributes after children and `addMisc` outside a node are rejected
  instead of corrupting the tape. The SSR contexts reject a style after an
  attribute (it used to emit a second `style` attribute) and an attribute
  after a child (it used to print into the element body).
- `reset()` no longer zero-fills the whole buffer on every render.
- All errors above are `avocet.RenderException` with a stable code; messages
  carry sizes and positions, never text or attribute values.

### Changed (formats and compatibility)

- **Saved buffer format version 2.** `save()` writes a header (`AVCT`,
  version, byte order, state) and only the used regions; the old unversioned
  format is rejected with `INVALID_SAVED_BUFFER`. Restored tapes are
  validated structurally before use. Spoonbill dev mode saves this buffer;
  delete a stale saved file once after upgrading.
- Tape layout: name lengths are 16-bit, node lengths are relative. Nothing
  outside `DiffRenderContext` and `save()` observes this.
- Source and binary: `RenderContext`, `ChangesPerformer`, `FastChangesPerformer`,
  `Id`, `FastId` and the DSL are unchanged. `IdBuilder` gained
  `ensureCanDescend`; `HashBuilder` gained a capacity constructor, `depth`
  and `clear`. `avocet.dom` gained `dispose(target)`.
- Wire protocol and bootstrap: unchanged. No new opcodes.

### Known limitations and policy notes

- The name policy is syntactic. `onclick` and other executable attribute
  names are accepted; typed safe/trusted helpers are M2 work.
- CSS values are protected as attribute data only; whether a CSS expression
  or URL is acceptable is not decided by the renderer.
- Text control characters and malformed surrogates are passed through.
- `renderHtml` still uses `XhtmlRenderContext` (`<br></br>`); Spoonbill's
  interactive path is `Html5RenderContext` with pretty-printing disabled.
- Duplicate attributes on one node are passed through as two operations.
- Correctness costs: an unchanged subtree is now confirmed by a byte
  comparison (O(size)) instead of an O(1) hash check. Measured costs are M2
  work; see the memory section of the contracts.
