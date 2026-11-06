# Avocet improvement plan

Status: proposed roadmap, revision 2. Date: 2026-11-05.

Priorities: **security, type safety, memory efficiency, flexible functional interfaces**.

This roadmap defines delivery order and architectural decisions. The detailed
[contracts and acceptance reference](IMPROVEMENT-CONTRACTS.md) defines the required
behavior. The [review](IMPROVEMENT-PLAN-REVIEW.md) supplies probe programs and
reported results against Avocet `baeeea2fcabde15c07b89f29c9fb9393cb9703fd` and
Spoonbill `ac74b78d3daa943a0c755c560dddb160370c5fea`.

## 1. Direction

Repair supported rendering first, then establish a clean foundation for future
APIs and backends. Security fixes must not wait for a new identity model, a broad
benchmark programme or optional widget features. Long-term design work remains
an explicit deliverable, not an indefinitely deferred collection of bug fixes.

Keep templates as composable descriptions interpreted into HTML or compact diff
buffers. Separate the authoring API, rendering lifecycle, diff semantics and host
adapters. Avoid a mandatory object-tree allocation per render or a mandatory
effect library. Make breaking changes when they remove persistent ambiguity and
have a tested migration; do not preserve unsafe behavior for compatibility.

## 2. Decisions that are settled

- **Exact output is mandatory.** Hashes accelerate comparisons; matching hashes
  never prove equality. Cache entries distinguish operation kind, namespace,
  name and attribute/style role. There is no optional residual wrong-output mode.
- **Security is context-specific.** Encode text and attribute boundaries, validate
  dynamic names, and make executable/URL/CSS policies explicit. A typed string,
  validator or external-widget boundary is not a universal sanitizer or sandbox.
- **One rendering algebra, several interpreters.** Keep an effect-free core and
  bounded mutable internals behind composable descriptions. Optimized and ordinary
  evaluation must preserve the same documented semantics.
- **Lifecycle and resource ownership are explicit.** The supported API must make
  begin/render/finalize/publish/dispose ordering reliable. Failed preparation must
  not publish new handlers; partial DOM failure invalidates the baseline rather
  than pretending to roll back browser operations.
- **Identity concepts remain distinct.** Logical sibling keys, runtime node
  identity, tree position and authorization are separate. Do not expose today's
  byte-buffer layout or positional-ID implementation as a permanent new contract.
- **Retain the public Scala.js DOM adapter and repair it.** Spoonbill does not use
  it, but that does not establish that it has no consumers. Treat its current
  support gaps honestly; future removal would need a separate compatibility decision.
- **Bound retained resources and define borrowed lifetimes.** Limits, exact cache
  keys, disposal and synchronous consumption of borrowed buffers are mandatory.
  Provide an explicit copying path where asynchronous consumers need owned data.

Forms, authorization, scheduling, databases and HTTP remain outside Avocet.
Spoonbill owns view revisions, authority checks, recovery and wire admission;
Avocet owns renderer semantics, buffer validity and the shared identity contract.

## 3. Immediate defects and evidence

Priorities below reflect rendering impact. Probe IDs refer to the review, not
issue severity. Its JVM probes were not independently rerun for this revision.
Promote their cases to deterministic regression tests before applying fixes.

| Priority / ID | Defect | Evidence and required repair |
| --- | --- | --- |
| P1 / D1 | Style data can break out of the SSR `style` attribute. | Review P3 and serializer source. Encode the enclosing attribute correctly; validate names and specify CSS policy separately. Verify both XHTML and inherited HTML5 paths. |
| P1 / D2 | Cached headers confuse attributes/styles and colliding names. | P10a/P10b and P4. Use exact complete cache keys, verify hits and eliminate cursor-dependent reuse. Warm-up/request order must not affect output. |
| P1 / D3 | Fixed headers fail for ordinary long names. | P1/P2 reproduce 10-character tags and long attribute/style names. Size headers correctly and check the encoded-name length representation; larger allocation alone does not repair a one-byte length overflow. |
| P1 / D4 | Hash-only equality suppresses real changes. | P4/P5 reproduce wrong tags and omitted text updates; attribute/subtree paths also require coverage. Verify exact semantic equality and keep collision cases in the suite. |
| P2 / D5 | Positional IDs wrap; bounds fail inconsistently. | P6/P7 and size arithmetic. Reject sibling exhaustion before wraparound, define depth/length errors and check buffer growth and restore inputs. |
| P1 / D6 | The DOM adapter has lifecycle and multi-handler defects. | Missing finalization and `toMap` handler collapse are visible in source; P8 reproduces the lifecycle sequence in JVM core, not the actual browser adapter. Repair and validate first/repeated render, handlers, replacement cleanup and disposal in Scala.js/browser tests. |

Also repair the property that catches exceptions and returns success. Expand
generators beyond short fixed names to long names, collision pairs and same-name
attribute/style cases. Ordered patch replay must supplement sorted-list assertions.

Do not overstate the evidence: P11 demonstrates serialized `<br></br>`, not a
browser parse. `renderHtml` currently uses `XhtmlRenderContext`; Spoonbill uses
`Html5RenderContext` with pretty-printing disabled. Confirm parser consequences
and distinguish these paths in tests and documentation.

## 4. Delivery milestones

| Milestone | Work in order | Completion gate |
| --- | --- | --- |
| **M1: repaired baseline** | Make tests trustworthy; convert D1–D6 into regression cases; fix escaping, header/cache behavior, exact equality and checked limits; repair the supported DOM path. | Relevant JVM/JS tests pass; optimizer/plain examples agree; targeted browser cases exercise repaired behavior; a real Spoonbill service uses the verified candidate artifacts. Release notes identify behavior changes and remaining limitations. |
| **M2: durable architecture and API** | Establish the explicit render/dispose lifecycle, composable validation, context-specific typed helpers, bounded caches/buffer retention and owned/borrowed performer APIs. Define optimizer evaluation laws and supported HTML/bootstrap semantics. | Representative old/custom/new consumers compile and behave correctly; invalid uses fail clearly; browser conformance, failure containment and retention checks pass; allocation and performance costs are recorded. |
| **M3: keyed identity and moves — conditional** | First name a real application screen and its failing reorder behavior. Then compare stable handles with positional remapping, select an identity contract and implement coordinated adapters only if justified. | The screen preserves required DOM state and event targets; memory is bounded; Spoonbill bootstrap, stale events, nested components and mixed-version handling are proven. Otherwise defer this milestone explicitly. |
| **M4: external subtree ownership — conditional** | Name an editor/chart integration, define container/descendant ownership and lifecycle, then implement it against the selected identity contract. | Ordinary rendering leaves external descendants alone; move/replacement/disposal/recovery behavior and retention are verified. Otherwise defer. |

M1 can ship as focused repairs; it need not wait for all M2 work. **M2 is required
long-term work**, not conditional on a demand for keyed lists. Migration, accurate
documentation and release validation apply to every milestone, including M1.

Within M1, write the relevant failing regression before each fix rather than
waiting for a universal test framework. Reuse Spoonbill's Nix browser environment;
do not block urgent repairs on a second browser toolchain. Targeted browser
validation remains required for HTML/DOM changes. Broad benchmarks start before
performance-sensitive M2/M3 decisions and must not delay necessary security fixes.

## 5. Designing M2 for long-term use

Establish stable semantic boundaries rather than accumulating convenience wrappers:

1. **Authoring:** typed nodes/attributes/styles, validated dynamic extension points,
   explicit composition and misc/event adaptation. Keep ordinary Scala composition
   usable and improve inference without hiding trust decisions.
2. **Lifecycle:** a high-level scoped render operation owns preparation, completion
   and cleanup. Retain low-level integration APIs with explicit thread confinement,
   borrowing and failure rules; avoid two competing publication states.
3. **Backends:** document operation semantics independently of byte layout and
   transport. Version future capabilities rather than adding no-op performer methods.
   Do not add move scaffolding before M3 merely to anticipate it.
4. **Optimizer:** run every representative API example through optimized and plain
   interpretation. Assert operation/evaluation-order equivalence; measure allocation
   and fallback behavior. New helpers must lower efficiently or document their cost.
5. **Compatibility:** keep unsafe behavior out of the contract, provide migration
   adapters where they have a clear lifetime, and prefer a justified major release
   over permanent parallel models of identity or lifecycle.

Choose concrete defaults and signatures through compile-tested examples and
record decisions as each milestone starts. Open choices are implementation
strategy and measured limits—not whether to provide correct output or bounded
ownership. Preserve the detailed [functional API](IMPROVEMENT-CONTRACTS.md#3-type-safe-functional-authoring),
[memory](IMPROVEMENT-CONTRACTS.md#5-memory-and-lifecycle-engineering) and
[compatibility](IMPROVEMENT-CONTRACTS.md#10-compatibility-and-migration) contracts.

## 6. Candidate integration and release

Spoonbill consumes published `avocet-core` and `avocet-events` artifacts, currently
1.0.0, rather than this source checkout. Test a unique local snapshot published
through Nix, select that exact version in an isolated Spoonbill checkout, and
verify the resolved JARs before interpreting results. Do not overwrite a released
version locally. See the [candidate procedure](IMPROVEMENT-CONTRACTS.md#92-spoonbill-candidate-integration).

Existing Node and native-browser fixtures include synthetic transport; passing
them alone does not exercise the candidate Scala renderer. Add repair-specific
SSR/first-update cases and run them through a real service using the candidate.
Test `avocet-dom` separately because Spoonbill does not execute that adapter.

Every delivered repair has core regressions, relevant integration evidence,
release notes and a tracked Spoonbill dependency update. Publishing and merging
the update are explicit release steps, not effects of writing this plan. Qualify
README memory/allocation claims with measured workload and lifecycle limits.

## 7. Acceptance and decisions

Use the [acceptance matrix](IMPROVEMENT-CONTRACTS.md#8-acceptance-matrix) and
[Nix commands](IMPROVEMENT-CONTRACTS.md#9-validation-commands-and-environment).
Record commands, outcomes, seeds and candidate versions; distinguish source
evidence, JVM probes, browser behavior and performance measurements.

Before M1 closes, settle the name/encoding policy, name-length representation,
exact equality/cache implementation and DOM support tests. Before M2 exposes a
new API, settle lifecycle failure behavior, numeric limits, safe/trusted helper
boundaries and text/bootstrap normalization. Before M3 begins, name the application
case and required identity behavior; choose the algorithm only after the identity
and memory experiment. Before M4 begins, name the widget and its lifecycle needs.

Stop a feature that misroutes events, weakens Spoonbill admission, retains
unbounded state or adds unjustified architectural complexity. Measure the cost
of correctness instead of weakening it to meet a speed target. Security repairs,
validation and migration work continue even if M3/M4 are deferred.
