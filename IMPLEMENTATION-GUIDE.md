# Avocet implementation guide

Date: 2026-11-05. Applies to [improvement plan revision 2](IMPROVEMENT-PLAN.md).

Use the plan for priorities and the [contracts](IMPROVEMENT-CONTRACTS.md) for
required behavior. This guide explains how to implement and verify that work.
Proposed helper, test and API names below are suggestions, not existing APIs.
Commands are derived from the current build; they were not executed while
writing this guide.

## 1. Start here

1. Read the repository `AGENTS.md`, the plan and the relevant contract section.
2. Record the Avocet and Spoonbill commits and working-tree state. Preserve
   unrelated tracked and untracked files; do not use blanket staging or cleanup.
3. Start with M1 and one defect at a time. M2 is required architectural work;
   M3/M4 need the demand gates in the plan. Do not begin an identity rewrite to
   solve an encoding or cache defect.
4. For each change: add a focused regression, observe the expected failure,
   implement the repair, run the relevant checks, then record the result.
5. Stop after two failures of the same underlying tool operation and re-plan;
   escalate after the next failed attempt. A completed experiment that rejects
   a hypothesis is not a tool failure.

Run from Avocet:

```bash
git status --short
git rev-parse HEAD
nix develop . --no-write-lock-file --command sbt projects
nix develop . --no-write-lock-file --command sbt +test
```

The first full test run establishes a baseline; it is not proof that the known
defects are absent. Preserve logs for existing failures rather than weakening
tests or bundling unrelated repairs. All dependency resolution, builds, tests,
local artifact publication and benchmarks use the applicable repository's flake.
Do not install tools on the host. If the flake lacks a required browser/profiler,
resolve that through the repository's Nix configuration process.

Current baseline: Scala 3.3.7, JDK 21, uTest plus ScalaCheck, JVM and Scala.js.
Physical directories still use `levsha`; source packages use `avocet`. Do not
rename directories as part of these repairs.

## 2. Source map

Paths in this table are existing entry points, not a request to edit all of them.

| Area | Entry points | What to inspect/change |
| --- | --- | --- |
| Descriptions/interpreters | [Document](core/shared/src/main/scala/levsha/Document.scala), [RenderContext](core/shared/src/main/scala/levsha/RenderContext.scala), [StatefulRenderContext](core/shared/src/main/scala/levsha/StatefulRenderContext.scala) | Node/attribute/style categories, operation ordering, misc hooks and current IDs. |
| DSL and optimizer | [DSL](core/shared/src/main/scala/levsha/dsl/package.scala), [macro](core/shared/src/main/scala-3/levsha/dsl/DslOptimizerMacro.scala) | Composition, implicit conversions, lowering, evaluation count and ordering. |
| SSR encoding | [XhtmlRenderContext](core/shared/src/main/scala/levsha/impl/XhtmlRenderContext.scala), [Html5RenderContext](core/shared/src/main/scala/levsha/impl/Html5RenderContext.scala), [text helpers](core/shared/src/main/scala/levsha/text.scala) | Style/attribute encoding, void tags and serialization profiles. |
| Diff/cache/tape | [DiffRenderContext](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala), [HashBuilder](core/shared/src/main/scala/levsha/impl/HashBuilder.scala), [StringHelper](core/shared/src/main/scala/levsha/impl/internal/StringHelper.scala) | Header cache, exact comparison, lengths, growth, finalization and restore. |
| Positional IDs | [IdBuilder](core/shared/src/main/scala/levsha/IdBuilder.scala), [Id](core/shared/src/main/scala/levsha/Id.scala) | Depth/sibling bounds and borrowed versus owned IDs. |
| Scala.js DOM/events | [render helper](dom/src/main/scala/levsha/dom/package.scala), [performer](dom/src/main/scala/levsha/dom/DomChangesPerformer.scala), [events](events/src/main/scala/levsha/events.scala) | Publication, handler tables, subtree indexes, event ancestry and disposal. |
| Existing tests | [diff tests](core/shared/src/test/scala/levsha/DiffRenderContextTest.scala), [properties](core/shared/src/test/scala/levsha/DiffRenderContextProperties.scala), [recording performer](core/shared/src/test/scala/levsha/Change.scala), [DSL tests](core/shared/src/test/scala/levsha/DefaultDslTest.scala), [SSR tests](core/shared/src/test/scala/levsha/XhtmlRenderContextTest.scala), [ID tests](core/shared/src/test/scala/levsha/IdBuilderTest.scala) | Reuse helpers, add adversarial cases and test ordered outcomes. |
| Build and measurement | [build.sbt](build.sbt), [flake](flake.nix), [diff benchmark](bench/src/main/scala/levsha/benchmark/DiffRenderContextBenchmark.scala) | Cross-platform checks, browser facilities and workload-specific measurements. |

Spoonbill consumes `avocet-core` and `avocet-events` artifacts. Its
[remote performer](../spoonbill/modules/spoonbill/src/main/scala/korolev/internal/RemoteDomChangesPerformer.scala)
and [browser client](../spoonbill/modules/spoonbill/src/main/es6/spoonbill.js) are
separate from `avocet-dom`. Test both adapters where applicable.

## 3. Work packages and dependency order

Use these as reviewable work packages, not necessarily one commit each.

| Package | Scope | Depends on | Completion evidence |
| --- | --- | --- | --- |
| T0 | Trustworthy regression helpers and first defect tests | Baseline inspection | Injected failure is detected; failing case is deterministic. |
| T1 | D1: SSR style encoding and name policy | Relevant T0 test | Safe parsed output in XHTML/HTML5 cases; correct DOM update values. |
| T2 | D2/D3: header cache and name storage | Relevant T0 tests | Cache-order independence; long-name and length-boundary cases pass. |
| T3 | D4: exact equality | T2 for cache-dependent cases | Colliding text, names, attributes and subtree candidates produce correct output. |
| T4 | D5: checked limits and restore validation | T0; coordinate tape changes with T2 | Boundary/malformed-input tests; no wraparound or reusable corrupt state. |
| T5 | D6: DOM lifecycle, events and cleanup | Core fixes needed by fixtures | Actual adapter renders repeatedly, dispatches correctly and releases resources. |
| T6 | Candidate integration and M1 delivery | Relevant T1–T5 fixes | Resolved candidate artifacts; targeted real-service/browser evidence; release notes. |
| T7 | M2: lifecycle, validation and typed authoring | Supported repaired baseline | Examples, type errors, failure rules and optimizer laws verified. |
| T8 | M2: retained-memory and performance engineering | T7 interfaces sufficiently stable | Bounded churn/disposal and workload-scoped measurements. |
| T9 | M3: keyed identity experiment and implementation | Named application demand; M2 contracts | Identity decision, browser behavior and coordinated protocol validation. |
| T10 | M4: external ownership | Named widget demand and sufficient identity contract | Mount/update/replacement/dispose and retention evidence. |

T1 can proceed without completing a universal replay engine or T2–T5. Complete
each security fix with its relevant tests; do not delay it behind optional work.
When several people work in parallel, assign ownership of `DiffRenderContext.scala`
to one integrator: T2–T4 overlap heavily. Test fixtures, serializer repairs and
consumer integration can proceed independently with clear boundaries.

## 4. T0 — make failures observable

### Build on the existing tests

- Remove the catch-and-return-success behavior from `DiffProperties`. Do not
  convert unexpected exceptions into discarded generated cases.
- Convert the [review's probes](IMPROVEMENT-PLAN-REVIEW.md#appendix-a-probe-programs)
  into assertions. Probe process exit code zero is not a passing regression:
  those programs deliberately catch and print failures.
- Use fixed collision inputs from the review. Do not search for collisions in
  every test run. Assert the fixture still collides under the tested hash before
  checking that its distinct contents are rendered correctly.
- Retain generated seeds and shrinking. Keep invalid-operation generators
  separate from valid browser-tree generators.

Suggested test support: an operation recorder plus a small independent reference
tree interpreter. The existing `Change.DiffTestChangesPerformer` copies IDs;
preserve that property because `FastId` and source buffers are borrowed mutable
arguments. Tests must not accidentally retain the renderer's scratch objects.

Apply emitted changes **in order** and compare the resulting tree with the
intended next tree. Do not sort before executing them. The reference interpreter
must implement the documented operation semantics independently of the production
diff algorithm. Keep it test-only; it is not a replacement production VDOM.

Create optimized/plain operation-trace examples alongside the defect work.
`DefaultDslTest` already compares some serialized output; add checks for operation
order, namespaces and evaluation counts. Full new-API coverage grows in T7.

### Focused commands

These names exist in the current sources. Confirm discovery in the output;
“no tests to run” does not establish coverage.

```bash
nix develop . --no-write-lock-file --command sbt \
  'coreJVM/testOnly avocet.DiffRenderContextTest avocet.DiffProperties avocet.DefaultDslTest avocet.XhtmlRenderContextTest avocet.IdBuilderTest' \
  'coreJS/testOnly avocet.DiffRenderContextTest avocet.DiffProperties avocet.DefaultDslTest avocet.XhtmlRenderContextTest avocet.IdBuilderTest'
```

For a single change, narrow that list to affected suites; broaden after the
focused checks pass. Shared tests belong under `core/shared/src/test/scala/levsha`.
Do not put JVM-only concurrency helpers into shared tests without a JS strategy.

## 5. T1 — repair SSR style encoding

1. Add a regression with two consecutive style declarations, including a value
   containing a quote and markup-like text, followed by an ordinary attribute.
   Keep interleaved raw style/attribute operations in a separate ordering-contract
   test; the normal DSL groups styles before attributes.
2. Exercise both `XhtmlRenderContext` and `Html5RenderContext` with explicit
   no-pretty-printing settings. The latter inherits encoding methods but changes
   closing-tag behavior.
3. Encode style name/value data for the enclosing quoted HTML attribute. Factor
   a shared writer if useful; avoid double encoding ordinary attribute values.
4. Validate dynamic tag/attribute/style names according to the selected contract.
   Escaping an invalid name is not a substitute for validating it. Keep custom
   elements, namespaced attributes and CSS custom properties in the supported set
   where the contract permits them.
5. In a real browser, parse the SSR output and assert that no additional attribute
   or executable node was introduced. Apply the corresponding DOM update and
   compare the intended attribute/style value under the defined CSS policy.

Do not HTML-escape values passed to DOM APIs merely because the SSR path needs
encoding; DOM setters receive values, not serialized markup. Outer attribute
encoding and the acceptability of a CSS expression or URL are separate questions.
Inline handlers, `srcdoc` and script/style content require the explicit trust
rules in the contracts, not a blanket “safe string” conversion.

Done: the malicious-shaped test input remains data on supported data-only paths;
ordinary styles still work; both serializer profiles and update behavior are
covered. A source string comparison alone does not establish browser behavior.

## 6. T2/T3 — repair storage and equality together coherently

### Header cache and sizing

Start in `openNode`, `setAttrOrStyle`, `getLocalHeadersCache` and `StringHelper`.

1. Define exact header identity: operation kind, namespace, name and style versus
   attribute role. A hash is only a lookup accelerator; verify the full key.
2. Allocate/encode enough header storage using checked size arithmetic. Remove
   the fixed 32/64-byte assumption without leaving truncating length casts.
3. Choose the encoded-name strategy before changing readers. Either retain the
   current representation with an explicit validated limit for the repair, or
   widen it consistently and version/validate saved buffers. Do not reinterpret
   a signed byte as a larger length in only one path. Current lengths count UTF-16
   bytes, not characters; update every reader, skipper and hash/header layout
   together if that representation changes.
4. Eliminate shared cached cursor assumptions. Use immutable payloads or absolute
   reads with clear ownership, and bound dynamic cache entries/bytes.
5. Test both warm-up orders for same-name attribute/style pairs, multiple contexts
   on one execution thread, and namespaces/operation kinds with colliding lookup
   hashes. Tests must isolate cache setup without relying on suite order.

Useful fixed cases: `blockquote`, `figcaption`, `linearGradient`, long custom
elements, long CSS property names, `width` as attribute then style, and `height`
as style then attribute. Include the selected length limit immediately below,
at and above the boundary. Test current short names too.

### Exact comparison

Audit every equality shortcut, not only `compareTexts`: tag/namespace comparison,
attribute identity/value comparison, cache hits and equal-subtree skipping.

- Compare semantic fields exactly before treating distinct encoded inputs as
  equal. Include lengths, namespace and operation kind where applicable.
- Keep hashing as a fast rejection test. Do not replace one 32-bit hash with a
  larger hash and call equality solved.
- Account for subtree end offsets and other positional metadata: comparing only
  hashes is wrong, while comparing whole buffers may include irrelevant metadata.
  Prefer a shared, cursor-safe comparator over the relevant encoded fields.
- Avoid allocating an object tree or decoding every field into a temporary
  `String` solely to compare it. Start with a clear correct implementation and
  optimize measured costs afterward.
- Test candidate equality with deliberate hash collisions through a narrow
  package-private test seam if necessary. Do not expose a production switch that
  turns exact checking off.

Use the review's `Aa`/`BB` tag case and `t86388`/`t118091` text case, plus attribute
and subtree collision cases. The oracle must check the resulting tree, not just
that some patch was emitted. Verify repeated diffing does not corrupt cursors.

Done: output is independent of hash collision or cache history; headers support
the declared name range; unchanged trees remain semantically unchanged; saved
buffer compatibility is explicitly handled.

## 7. T4 — checked bounds and safe restoration

Create a limit/representation inventory before adding scattered `require` calls:

| Resource | Check point | Failure requirement |
| --- | --- | --- |
| Sibling position and nesting | Before ID increment/stack push | No narrowing wraparound or partially advanced ID state. |
| Name/text/attribute lengths | Before calculating encoded size or writing | Explicit unit and maximum; no signed-byte truncation. |
| Active buffer capacity | Before growth/write | Checked arithmetic and enough room in the active half, not merely the backing buffer. |
| Cached headers and diagnostics | Before retention | Bounded entries/bytes; no payload-bearing error messages. |
| Restored tape | Before adopting it as a baseline | Validate sizes, offsets, opcodes, namespaces, byte order and format version. |

Use checked arithmetic before allocation. Test invalid initial capacities and
the distinction between the doubled backing buffer and each logical half.
Cover both `IdBuilder` and the separate `HashBuilder.Stack`; fixing one nesting
limit does not establish the other's safety.
Document whether restoration copies/borrows the source and whether it changes
the caller's buffer position.

After a failure, either restore a known reusable construction state or mark the
context invalid and require recreation. A later valid render must not continue
using a half-updated ID/hash stack. Test failures mid-node, mid-attribute, at
growth boundaries and during restore.

The repair can keep the current positional representation with clear limits.
Do not widen public IDs as an incidental overflow fix; that changes events,
saved state and Spoonbill integration. A future representation change belongs
behind an explicit compatibility decision.

## 8. T5 — repair the Scala.js DOM lifecycle

Create actual adapter fixtures; the current repository has no DOM test sources.
`dom/test` may therefore succeed without exercising this behavior.

Implement and verify one normal-cycle sequence:

1. Prepare a fresh writable construction buffer and a staged misc/event table.
2. Render and finalize the candidate document.
3. Enter an explicit applying state, then apply the ordered diff.
4. On success, commit the candidate baseline and handler table coherently, make
   the next construction buffer ready and return to the live state.
5. On preparation failure, retain the prior published baseline/handlers and
   discard the candidate. On partial DOM failure, invalidate and require a
   defined remount/reset; do not publish success or continue ordinary patches.

Map this sequence to the actual `swap`/`reset` semantics once and cover first,
second and repeated renders. Do not copy a two-render test helper into a reusable
loop without handling its buffer lifecycle: `swap()` exchanges buffers but does
not finalize or reset them. Never publish misc handlers before
knowing which tree they describe.

Build handler entries by `(node identity, event type, phase)` directly rather
than collapsing all misc entries through `Map[Id, Misc]`. Specify how multiple
handlers for the same full event key compose; do not accidentally drop them in
a second map conversion.

On removal/replacement, remove descendant index entries and obsolete references.
Add a public disposal/unmount path that unregisters native listeners, clears
event/misc/index state and removes the root from the registry. Disposal must be
idempotent. Do not keep retired roots alive through a closure or global map.

Native DOM mutations can invoke custom-element callbacks synchronously. Define
whether reentrant rendering/events are rejected or deferred in a bounded way
during application. Do not claim DOM rollback. Test non-bubbling focus/blur,
native versus simulated capture/bubble, default prevention, text-node targets,
multiple handlers, subtree replacement and reentrancy.

Build/link check (existing tasks):

```bash
nix develop . --no-write-lock-file --command sbt 'dom/compile' 'dom/Compile/fastLinkJS'
```

Then load the linked fixture in real browsers using a repository-managed harness.
Reuse Spoonbill's pinned browser environment if practical, but **execute the
Avocet Scala.js adapter**. Record the exact new harness command once it exists;
neither Node-only core tests nor Spoonbill's unrelated client fixtures prove DOM
adapter correctness. The contracts require browser fixtures, not a particular
new JavaScript testing dependency.

## 9. T6 — integrate the candidate into Spoonbill

Use the [candidate procedure](IMPROVEMENT-CONTRACTS.md#92-spoonbill-candidate-integration).
Do this in an isolated consumer checkout; preserve the maintainer's current work.
Replace the example version below with a unique identifier for the tested commit
and iteration. Never publish over the released `1.0.0` locally.

```bash
# Avocet checkout: local publication only.
env RELEASE_VERSION=1.0.0-guidecheck-SNAPSHOT \
  nix develop . --no-write-lock-file --command \
  sbt 'show coreJVM/version' 'show eventsJVM/version' \
  'coreJVM/publishLocal' 'eventsJVM/publishLocal'
```

Set the isolated Spoonbill checkout's `avocetVersion` to exactly that candidate.
Then verify the actual resolved core/events JARs before testing:

```bash
# Isolated Spoonbill checkout.
nix develop . --no-write-lock-file --command \
  sbt 'show spoonbill/Compile/dependencyClasspath' 'spoonbill/test' 'testkit/test'
```

Capture the producer commit/version and resolved paths. Source-only changes in
Avocet cannot affect a consumer that still resolves the released artifacts.

Add targeted cases to a real-service fixture: long names, style encoding,
attribute/style cache reuse, collision-driven updates and SSR followed by its
first live patch. Include adjacent/empty text in the supported-profile decision.
Spoonbill's actual SSR path is HTML5 with pretty-printing disabled; `renderHtml`
is a different current entry point.

Existing real-service/browser environment example:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command \
  bash scripts/test-sensitive-acknowledgement.sh
```

That command exercises a real Scala service, but existing cases do not cover all
renderer repairs. Add the focused fixture cases before claiming the new fixes
are validated. Follow [Spoonbill's browser guide](../spoonbill/docs/browser-tests.md)
for other applicable checks and service requirements.

Client/protocol regressions are separate:

```bash
nix develop . --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
```

Regenerate protocol constants only if the Scala-owned protocol changes. M1 should
not introduce move opcodes or a new identity protocol. Any actual bootstrap or
format change still requires its own compatibility evidence.

## 10. T7/T8 — implement the long-term foundation

M2 is required even if keyed lists and widgets are deferred. Deliver small slices
with a single implementation of each semantic rule, not an accumulating facade
over inconsistent lower-level behavior.

### Lifecycle and public errors

Prototype the high-level render/dispose API in a tiny standalone consumer before
freezing its signatures. Exercise initial rendering, replacement, invalid input,
custom interpreter use and cleanup. Encapsulate construction/finalization and
make invalid/disposed states explicit. Keep the lower-level fast API available
with documented thread confinement and borrowing rules.

Use structured, bounded errors. Avoid adding mandatory abstract methods to every
existing interpreter merely to expose diagnostics; choose an additive adapter
or a deliberate major-version contract. Do not introduce an effect parameter on
every rendering operation. Runtime checks remain necessary because Scala values
and arbitrary callbacks are not linear or provably pure.

Build the migration matrix before freezing the API: an unchanged old consumer,
an opt-in new consumer, a custom `RenderContext`, a custom `ChangesPerformer`,
a Scala.js DOM application and Spoonbill. Check output, events and cleanup as
well as compilation. Record source/binary, saved-buffer, bootstrap and wire
compatibility separately; an adapter must not hide a changed semantic contract.

### Validation

Implement a composable operation-stream validator. Separate structural checks,
HTML/namespace shape checks and the interactive-bootstrap profile. A stateful
wrapper must forward IDs and misc callbacks correctly; a generic wrapper around
`RenderContext` alone does not satisfy `StatefulRenderContext` consumers.

Keep resource/representation checks mandatory even when optional diagnostic
validation is off. Test ordinary and optimized forms through the same validator.
Cap accumulated diagnostics. Use the contracts' `renderInteractiveHtml` notation
for HTML5/no-pretty-printing equivalence; do not accidentally test the current
XHTML `renderHtml` path instead.

### Typed composition and optimizer

Introduce typed attributes/styles and validated name/URL helpers only after the
encoding policy is settled. Preserve custom extension points and clear trusted
escape hatches. Keep logical keys, runtime IDs and positions separate if/when
those new types are exposed.

For each new combinator:

1. Write an ordinary Scala example and negative compile-use examples.
2. Compare optimized/plain operation traces and dynamic-expression evaluation
   counts, including failure timing and collection reuse.
3. Add a lowering to `DslOptimizerMacro` where justified; otherwise use a correct
   fallback and document/measure its allocation cost.
4. Check type inference, compilation cost and generated size alongside runtime
   behavior. Do not solve double evaluation solely by telling callers to be pure.

Choose compile-negative testing facilities supported by the current Scala/Nix
build. Proposed test names such as `OptimizerContractTest` and
`RenderLifecycleTest` are not existing commands until added and discovered.

### Memory engineering

Implement bounds and disposal first, then tune buffer reuse/shrink policy,
header-cache storage and owned snapshot size using measured workloads. Do not
optimize by dropping exact comparisons or retaining removed objects indefinitely.

Before M2 optimization, establish and freeze the baseline and investigation gates
from the [memory contract](IMPROVEMENT-CONTRACTS.md#5-memory-and-lifecycle-engineering).
Measure JVM and Scala.js separately, distinguishing allocation per update from
retained memory and separating renderer costs from application data and DOM costs.
Do not move a threshold afterward merely to classify a regression as acceptable.

The fast performer borrows `FastId` and buffer arguments for synchronous use.
Queued consumers need explicit copied/owned data. Test an owned recorder across
later renders to ensure it retains values rather than overwritten scratch state.

Use deterministic counts for retained indexes/cache entries plus browser/JVM
memory evidence; do not rely on a single forced-GC sample. Exercise repeated
mount/unmount, large-to-small trees, many contexts, cache churn and snapshot copies.
Record post-disposal strong-reference roots where the harness can inspect them.

Existing benchmark commands, to run after establishing trustworthy fixtures:

```bash
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffRenderContextBenchmark.*'
nix develop . --no-write-lock-file --command sbt \
  'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*StaticRenderingComparision.*'
```

The existing diff benchmark includes text-reporting performer work, and static
result consumption needs review. Separate encoding, diff-only, serialization
and end-to-end measurements. Add pinned profiling facilities through Nix before
reporting retained-heap results. Root `+test` does not run the benchmark project.

## 11. T9/T10 — conditional features

### Keyed identity

Before T9, record the application screen, reorder operation and state that must
survive: focus, selection, input state, media or widget identity. Capture the
current failure in a browser fixture. Without that evidence, defer T9 explicitly
and continue mandatory M2 work.

Evaluate stable runtime handles plus ancestry against positional remapping on
that fixture. Record identity retention for same-key/tag changes, removal/reuse,
cross-parent movement and root replacement. Measure live and temporary indexes.
Choose the identity contract before the diff algorithm or wire opcodes.

After selection, implement the core keyed semantics and ordered model tests,
then the Scala.js backend and Spoonbill protocol together. Reject duplicate/mixed
keys as specified; test old queued events after moves/removals and unsupported
client capabilities before patch application. Keep unkeyed behavior available.
Do not add default no-op move methods to old performers or silently recreate
nodes while promising identity preservation.

### External subtree ownership

Start T10 with one actual widget integration and a lifecycle fixture. Define which
side owns the container and descendants and what remount/recovery does. Diff
calculation remains effect-free; mount/dispose effects belong to the adapter.
Test rendering around the widget, replacement, event delegation, cleanup and
retention. An external subtree is not a security sandbox or a raw-HTML shortcut.

## 12. Evidence, review and handoff

For each work package, attach a concise record:

```text
Package and defect/contract IDs:
Producer and consumer commits/candidate version:
Changed behavior and compatibility impact:
Regression before fix: command, expected failure, observed result
Validation after fix: command, pass/fail, relevant assertions
Browser path exercised: SSR parser / Scala.js adapter / real Spoonbill service
Memory/performance evidence, when relevant:
Outstanding failure or deferred gate and next action:
```

Never replace “not run” with “should pass”. New fixtures must be named and
discovered in logs. A passing synthetic client test, serializer string comparison
or JVM lifecycle probe establishes only the behavior it actually exercises.

Before marking a package complete:

- [ ] Relevant regression tests fail on the old behavior and pass on the repair.
- [ ] Required JVM/JS/browser paths have evidence, or the remaining blocker is explicit.
- [ ] Existing custom APIs, saved formats and bootstrap/wire changes are accounted for.
- [ ] Cleanup, bounds and error output follow the contracts.
- [ ] The actual candidate artifacts reach the tested consumer.
- [ ] Release/migration notes and scoped README claim changes are prepared.
- [ ] Only task-scoped files are included in any commit.

Run `git diff --check` on tracked changes and inspect newly added files too.
Use existing formatting conventions; do not reformat unrelated code. Broaden
tests once after focused checks pass, then repeat only for new changes, failures
or unresolved concerns.

M1 and M2 can ship independently of deferred M3/M4. Track the artifact release
and Spoonbill dependency update so users actually receive repairs. Remote
publication, pushing, merging or deployment are separate authorized release
actions. This guide does not perform them.
