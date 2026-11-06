# Review of IMPROVEMENT-PLAN.md

Reviewed document: [IMPROVEMENT-PLAN.md](IMPROVEMENT-PLAN.md), dated 2026-11-05 (untracked in the working tree at review time).

Code baseline: Avocet `baeeea2fcabde15c07b89f29c9fb9393cb9703fd`; Spoonbill `ac74b78d3daa943a0c755c560dddb160370c5fea` in the sibling checkout `../spoonbill`. These are the revisions the plan names.

Review date: 2026-11-05. Reviewer: Claude (Fable 5.1), at the maintainer's request.

Method: read every file the plan links, plus Spoonbill's renderer, performer, browser client and build; ran two throwaway programs against the core module (section 4, Appendix A and B). No repository file was changed by the review itself.

## Summary

The plan is technically accurate but mis-prioritised and over-long.

- All eleven source observations in section 3 of the plan hold. Most were reproduced at runtime; the rest are directly visible in the code.
- Every repository link, Spoonbill link, command and external reference in the plan resolves.
- The main weaknesses are structural. The plan files defects that are visible in the code as "unverified observations" to be classified in Phase 0. It reads as a contract document whose central decisions (section 15) stay open. It commits work to a dom module that is currently broken and has no consumer.
- The "suggested first implementation slice" in section 11 is the right instinct. It should start from the confirmed defect list below rather than from reproductions still to be written.

## 1. Verified facts

Rows marked "runtime" were reproduced with the programs in Appendix A; the exact output is in Appendix B.

| Plan observation (section 3) | What the code actually does | Evidence |
| --- | --- | --- |
| Cached header buffers have fixed sizes | `DiffRenderContext.openNode` throws `java.nio.BufferOverflowException` for any tag name of 10 or more characters: the per-name header is a 32-byte buffer with 14 bytes of fixed fields. This blocks `blockquote`, `figcaption`, most custom elements and SVG names such as `linearGradient`. Attribute and style names of 29 or more characters overflow the 64-byte attribute header the same way, for example `-webkit-border-bottom-left-radius`. | runtime P1, P2; [DiffRenderContext.scala line 122](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L122), [line 150](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L150) |
| Attribute and style headers share a cache lookup whose key omits the style discriminator | Confirmed, and reachable with ordinary templates. The cache key is the namespace byte XOR the name hash; `XmlNs.html.code` is 0, which is also the byte styles pass. After `width := "100"` on one element, a later `width @= "50px"` on the same thread is emitted as `setAttr`. After a `height` style, a later `height` attribute is emitted as `setStyle`. The cache is a `ThreadLocal`, so on a server the outcome depends on which request each thread saw first. | runtime P10a, P10b; [line 146](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L146), [line 650](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L650) |
| Text, attribute and subtree decisions use hashes | Two different strings with the same `MurmurHash3.stringHash` produce no text update at all; a colliding pair turned up after about 118 000 generated strings. Tag names compare by `String.hashCode`, which has trivial collisions: diffing `Aa` to `BB` emits nothing. The open-node header cache is keyed by that same hash, so a fresh context that opens `BB` while the thread cache already holds `Aa` emits `create(1, Aa)`, the wrong tag name. Subtree skipping (`sumA != sumB`) is hash-only too (by reading). | runtime P4, P5; [line 118](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L118), [line 257](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L257), [line 268](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L268), [line 594](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L594) |
| Text and ordinary attributes use XML escaping; CSS declarations use a different path | Style names and values are appended with no escaping. `color @= "red\" onmouseover=\"alert(1)"` renders as `<div style="color:red" onmouseover="alert(1);">`: the value closes the `style` attribute and creates an executable one. Attribute values are escaped correctly (`&quot;`). Tag names and attribute names are not escaped either. | runtime P3; [XhtmlRenderContext.scala line 98](core/shared/src/main/scala/levsha/impl/XhtmlRenderContext.scala#L98) |
| Node positions use `Short`; nesting uses fixed stacks | Past 32 767 siblings the index wraps negative with no error: 40 000 text siblings gave the id `1_-25536`. Depth 257 throws `IllegalArgumentException` from the id buffer, which is the better of the two failure modes. | runtime P6, P7; [IdBuilder.scala line 26](core/shared/src/main/scala/levsha/IdBuilder.scala#L26), [line 59](core/shared/src/main/scala/levsha/IdBuilder.scala#L59) |
| `closeNode` does not check the supplied name | Confirmed by reading: the `name` argument is unused. Related: two `setAttr` calls with the same name on one node pass through as two operations. | reading; runtime P9; [line 136](core/shared/src/main/scala/levsha/impl/DiffRenderContext.scala#L136) |
| Text calls are separately encoded while browsers merge adjacent text | Confirmed and consequential. Spoonbill's client assigns ids by walking `childNodes` positionally (`registerRoot`, [spoonbill.js line 187](../spoonbill/modules/spoonbill/src/main/es6/spoonbill.js#L187)), so two adjacent SSR text nodes that the browser merges shift every following sibling id. Preset-id mode re-syncs elements through the `k` attribute but still counts text nodes on the server. | reading |
| Path ids, prefix ancestry, no move operation | Confirmed. Spoonbill's `RemoteDomChangesPerformer` emits no move operation. | reading |
| DOM helper publishes events before applying changes, uses `miscBuffer.toMap`, and skips the reset/finalize cycle | `avocet.dom.render` fails on its first call with `scala.MatchError: 0` because it never calls `finalizeDocument`. Core added that method in commit 5e035a1 ("Better skip algo; Better attr/stype comparison; GC-friendly ChangesPerformer"), the commit right after the last functional change to the dom module (72a3ba9); the later dom commits are renames and build changes. The module has no tests and Spoonbill does not import it. Separately, `miscBuffer.toMap` is keyed by node id, so a second event on the same element is dropped. That is a defect, not a test gap. | runtime P8; [dom/package.scala line 59](dom/src/main/scala/levsha/dom/package.scala#L59), [line 75](dom/src/main/scala/levsha/dom/package.scala#L75) |
| DOM adapter keeps node indexes and a process-local roots map; no unmount | Confirmed by reading. `remove` deletes only the removed node's own index entry, never its descendants. | [DomChangesPerformer.scala line 58](dom/src/main/scala/levsha/dom/DomChangesPerformer.scala#L58) |
| The arbitrary-tree property catches exceptions and returns `true`; the other property compares sorted lists | Confirmed by reading. Also: the generators use eight tag names of at most six characters and a handful of attribute and style names, so the header-size and name-collision classes above are structurally unreachable by the existing property tests. | [DiffRenderContextProperties.scala line 29](core/shared/src/test/scala/levsha/DiffRenderContextProperties.scala#L29), [line 48](core/shared/src/test/scala/levsha/DiffRenderContextProperties.scala#L48) |

Other checks that passed:

- Every repository-relative link in the plan points at an existing file. The three Spoonbill documents, the three Node test files, `scripts/test-native-browsers.sh`, the `generateBrowserProtocol` task and the `browser` dev shell exist at `ac74b78d`.
- The build facts match: Scala 3.3.7; sbt, JDK 21 and Node from `flake.nix`; sbt 1.10.7; root aggregation excludes `bench`; `dom` has no test sources; CI runs `nix develop --command sbt +test`.
- The Eezo page cited in section 17 returns HTTP 200.
- Spoonbill's use of the lifecycle (`finalizeDocument`, `swap`, `save` in dev mode) matches the plan's description.

Two facts the plan does not state:

- Spoonbill depends on the published `avocet-core` and `avocet-events` 1.0.0 artifacts (`avocetVersion` in its `build.sbt`), not on source. A candidate Avocet reaches Spoonbill only through a local snapshot publish and a version bump there.
- Spoonbill renders SSR with `Html5RenderContext` and `TextPrettyPrintingConfig.noPrettyPrinting`, not with `renderHtml`. The default `renderHtml` uses `XhtmlRenderContext`, which emits `<br></br>`; browsers parse that end tag as a second `br` element (runtime P11). `Html5RenderContext`, which self-closes void elements, is never mentioned in the plan.

## 2. Feedback

### 2.1 Promote the confirmed defects out of Phase 0

Section 3 defers every row to "minimal reproductions and impact classification". The table above already is that work for the rows that matter. Replace the observation table with a known-defects list carrying a severity, and make Phase 1 the fix list for it. Phase 0 then has three items:

1. Make the arbitrary-tree property able to fail (remove the catch-and-return-`true`).
2. Add one regression test per confirmed defect; the Appendix A programs are a starting point.
3. Run the existing suite and record the result.

Benchmarks, the benchmark baseline and the browser test environment gate nothing in Phase 1. Move them to the first phase that needs them.

### 2.2 Order by likelihood, not by theme

Section 4.2 gives hash collisions the most space, yet a random 32-bit collision needs on the order of billions of comparisons. The three defects that hit ordinary templates today are the unescaped style values, the width/height cache mix-up and the ten-character tag-name limit. Suggested Phase 1 order:

1. Escape style names and values in `XhtmlRenderContext`, and decide the policy for tag and attribute names (section 4.1).
2. Fix the header cache: include the operation kind in the key, verify the cached name bytes on a hit, size each header from the name length instead of 32 or 64 bytes, and use one hash function for names everywhere.
3. Replace sibling-index wraparound with a checked failure; keep the depth failure but make it a defined error.
4. Decide the collision policy for text, attribute values and subtree sums, and write the decision down: either accept the residual risk explicitly, or compare bytes when hashes match and measure the cost. This is the one place the plan asks for research instead of a decision; it should be a decision.

### 2.3 Decide the fate of the dom module

The plan assumes a working built-in Scala.js backend: section 10 plans DOM and browser tests for it, Phase 1 requires suites to pass "on JVM/JS and applicable browsers", and section 14 lists "a Scala.js DOM application" as a migration fixture. The module has been unusable since commit 5e035a1 and nothing depends on it. Add a decision to section 15: fix it with one smoke test (render, render again, dispose), or mark it experimental and drop it from release scope. Either way, browser evidence for the renderer can come from Spoonbill's existing native-browser suite rather than from new Avocet infrastructure.

### 2.4 Gate the identity redesign on demand, as section 9 already does

Phases 3 and 4 are the most expensive work in the plan. Stable handles change `Id` and `FastId`, event ancestry, the DOM performer, the Spoonbill client, its protocol and its bootstrap. Section 9 rightly requires "a concrete editor/chart integration" before building ownership boundaries; section 7 has no equivalent gate. Name the Spoonbill screen that needs DOM identity preserved across a reorder (focus, selection, uncontrolled inputs, media). If none exists, defer keyed reconciliation explicitly, which section 15 already permits, and ship the validation and security work alone.

### 2.5 Name the macro optimizer as the constraint on new API

Every combinator section 6 proposes (typed keys, keyed children, typed attribute and style keys) must be matched in `DslOptimizerMacro`; an unmatched shape silently falls back to the unoptimised path ([DslOptimizerMacro.scala line 88](core/shared/src/main/scala-3/levsha/dsl/DslOptimizerMacro.scala#L88)). The plan lists "optimized and ordinary agreement" as an acceptance criterion. Turn it into a test harness that runs every example through both forms and compares the emitted operations, and build it in Phase 1: the fixes in 2.2 touch the same engine, and the harness catches optimizer regressions for free.

### 2.6 Shorten and split

Most sections are contracts ("must", "define", "specify") rather than steps, and section 15 leaves the central decisions open. Split the document:

- a two-page plan: defects, fixes in order, tests, release and the Spoonbill bump;
- a separate contracts and acceptance reference (current sections 4, 5, 12 and 14).

Remove the closing meta sentences ("File integrity ... checked separately when the plan is saved", "This change adds a planning document only"); they belong in a commit message. Link or drop the "two independent design reviews" mentioned in section 17.

### 2.7 Smaller corrections

- Section 5.2: reference `Html5RenderContext` as the interactive SSR path (it is what Spoonbill uses, with pretty-printing off) and note that `renderHtml` emits `<br></br>`.
- Section 13: state how a candidate is wired into Spoonbill (local snapshot publish, `avocetVersion` bump; the Spoonbill build already has a snapshot resolver). Section 14: count that bump as part of done for every fix, since Spoonbill users only see released artifacts.
- Section 3, DOM helper row: state the `toMap` behaviour as a defect (one event per node survives).
- Section 12 and the property tests: add long names, colliding names and same-name attribute/style pairs to the generators; today they cannot reach these classes.
- README: it promises "memory usage is constant" and "without additional memory allocation" ([README.md line 7](README.md#L7), [line 91](README.md#L91)), which the plan's own non-goals say not to claim. Add a documentation deliverable to qualify these.
- Section 4.3: the sibling overflow is silent and the depth overflow is loud; the plan lumps them together, and the silent one matters more.

## 3. Proposed replacement for Phase 0 and Phase 1

| Phase | Deliverables | Exit gate |
| --- | --- | --- |
| 0. Trustworthy tests | Property test can fail; one regression test per confirmed defect in section 1; existing suite run and recorded; optimized-versus-plain harness. | Each regression test fails on the current code and the suite is green otherwise. |
| 1. Fixes | Style escaping; header cache key, name verification and sizing; checked sibling index; collision policy decided and implemented; dom module decision executed; `toMap` event loss fixed if the module is kept. | Regression tests pass on JVM and JS; a Spoonbill snapshot build passes its existing Node and native-browser suites; release notes written. |

Phases 2 to 6 can stay as drafted, each behind the demand gate from 2.4.

## 4. How the facts were checked

Environment: the repository `flake.nix` entered with `nix develop . --no-write-lock-file`; sbt 1.10.7, Scala 3.3.7, JDK 21 and Node from the flake; macOS host; 2026-11-05.

The two programs in Appendix A were compiled into the `coreJVM` test classpath with a session-only sbt setting. No project file was modified, and the sources lived outside the repository:

```bash
nix develop . --no-write-lock-file --command sbt -batch \
  'set coreJVM / Test / unmanagedSourceDirectories += file("<directory containing Probe.scala>")' \
  'coreJVM/Test/runMain avocet.probe.Probe'
```

```bash
nix develop . --no-write-lock-file --command sbt -batch \
  'set coreJVM / Test / unmanagedSourceDirectories += file("<directory containing Probe2.scala>")' \
  'coreJVM/Test/runMain avocet.probe2.Probe2'
```

Both runs compiled and completed with exit code 0; their output is in Appendix B. Not run: the existing test suite, the Scala.js build, the benchmarks and any browser test. The plan's statements about those were checked by reading the build and CI definitions only. sbt wrote only to ignored `target/` directories.

## Appendix A: probe programs

Throwaway code, not production quality. Each `attempt` prints `OK` with a result, or `THREW` with the exception. To re-run, place each file in its own directory and use the commands in section 4.

### Probe.scala

```scala
package avocet.probe

import avocet._
import avocet.impl._
import avocet.impl.DiffRenderContext.ChangesPerformer
import scala.util.hashing.MurmurHash3

// Throwaway probe: exercises the diff engine and HTML serializer directly
// to check the claims made in IMPROVEMENT-PLAN.md section 3.
object Probe {
  final class Rec extends ChangesPerformer {
    val out = scala.collection.mutable.Buffer.empty[String]
    def removeAttr(id: FastId, xmlNs: String, name: String): Unit = out += s"removeAttr(${id.mkId}, $name)"
    def removeStyle(id: FastId, name: String): Unit = out += s"removeStyle(${id.mkId}, $name)"
    def remove(id: FastId): Unit = out += s"remove(${id.mkId})"
    def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = out += s"setAttr(${id.mkId}, $name=$value)"
    def setStyle(id: FastId, name: String, value: String): Unit = out += s"setStyle(${id.mkId}, $name=$value)"
    def createText(id: FastId, text: String): Unit = out += s"createText(${id.mkId}, $text)"
    def create(id: FastId, xmlNs: String, tag: String): Unit = out += s"create(${id.mkId}, $tag)"
  }

  def attempt(label: String)(f: => Any): Unit =
    try println(s"[$label] OK: ${f}")
    catch { case e: Throwable => println(s"[$label] THREW: ${e.getClass.getName}: ${e.getMessage}") }

  def diffOf(a: RenderContext[Nothing] => Unit, b: RenderContext[Nothing] => Unit): Seq[String] = {
    val rc = DiffRenderContext[Nothing]()
    a(rc); rc.finalizeDocument(); rc.swap()
    b(rc); rc.finalizeDocument()
    val p = new Rec
    rc.diff(p)
    p.out.toSeq
  }

  def main(args: Array[String]): Unit = {
    attempt("P1 openNode span (4 chars)") { val rc = DiffRenderContext[Nothing](); rc.openNode(XmlNs.html, "span"); rc.closeNode("span"); "no exception" }
    attempt("P1 openNode blockquote (10 chars)") { val rc = DiffRenderContext[Nothing](); rc.openNode(XmlNs.html, "blockquote"); rc.closeNode("blockquote"); "no exception" }
    attempt("P1 openNode figcaption (10 chars)") { val rc = DiffRenderContext[Nothing](); rc.openNode(XmlNs.html, "figcaption"); rc.closeNode("figcaption"); "no exception" }
    attempt("P2 setAttr 29-char name") { val rc = DiffRenderContext[Nothing](); rc.openNode(XmlNs.html, "div"); rc.setAttr(XmlNs.html, "data-abcdefghijklmnopqrstuvwx", "x"); rc.closeNode("div"); "no exception" }
    attempt("P2 setStyle -webkit-border-bottom-left-radius (33 chars)") { val rc = DiffRenderContext[Nothing](); rc.openNode(XmlNs.html, "div"); rc.setStyle("-webkit-border-bottom-left-radius", "1px"); rc.closeNode("div"); "no exception" }

    attempt("P3 SSR: style value and attr value containing a double quote") {
      val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.default)
      rc.openNode(XmlNs.html, "div")
      rc.setStyle("color", "red\" onmouseover=\"alert(1)")
      rc.setAttr(XmlNs.html, "title", "a\" onmouseover=\"alert(2)")
      rc.closeNode("div")
      "\n" + rc.mkString
    }

    attempt("P4 tag Aa -> BB (String.hashCode collision): diff changes") {
      diffOf(rc => { rc.openNode(XmlNs.html, "Aa"); rc.closeNode("Aa") },
             rc => { rc.openNode(XmlNs.html, "BB"); rc.closeNode("BB") }).mkString("; ")
    }
    attempt("P4 fresh context, create BB while thread cache holds Aa header") {
      val rc = DiffRenderContext[Nothing]()
      rc.openNode(XmlNs.html, "BB"); rc.closeNode("BB"); rc.finalizeDocument()
      val p = new Rec; rc.diff(p); p.out.mkString("; ")
    }

    attempt("P5 text update between two strings with equal MurmurHash3") {
      val seen = scala.collection.mutable.HashMap.empty[Int, String]
      var found: Option[(String, String)] = None
      var i = 0
      while (found.isEmpty) {
        val s = "t" + i.toString
        val h = MurmurHash3.stringHash(s)
        seen.get(h) match {
          case Some(o) if o != s => found = Some((o, s))
          case _ => seen.update(h, s)
        }
        i += 1
      }
      val (s1, s2) = found.get
      val changes = diffOf(rc => { rc.openNode(XmlNs.html, "div"); rc.addTextNode(s1); rc.closeNode("div") },
                           rc => { rc.openNode(XmlNs.html, "div"); rc.addTextNode(s2); rc.closeNode("div") })
      s"'$s1' -> '$s2' (hash ${MurmurHash3.stringHash(s1)}): changes = [${changes.mkString("; ")}]"
    }

    attempt("P6 40000 sibling text nodes: currentId of last") {
      val rc = DiffRenderContext[Nothing]()
      rc.openNode(XmlNs.html, "div")
      var i = 0; while (i < 40000) { rc.addTextNode("x"); i += 1 }
      val id = rc.currentId.mkString
      rc.closeNode("div"); id
    }
    attempt("P7 nesting depth 300") {
      val rc = DiffRenderContext[Nothing]()
      var i = 0; while (i < 300) { rc.openNode(XmlNs.html, "div"); i += 1 }
      i = 0; while (i < 300) { rc.closeNode("div"); i += 1 }
      "no exception"
    }

    attempt("P8 dom-module sequence: render, diff WITHOUT finalizeDocument") {
      val rc = DiffRenderContext[Nothing]()
      rc.openNode(XmlNs.html, "div"); rc.addTextNode("hi"); rc.closeNode("div")
      val p = new Rec; rc.diff(p); rc.swap(); "changes = [" + p.out.mkString("; ") + "]"
    }

    attempt("P9 duplicate attribute on one node") {
      diffOf(rc => { rc.openNode(XmlNs.html, "div"); rc.closeNode("div") },
             rc => { rc.openNode(XmlNs.html, "div"); rc.setAttr(XmlNs.html, "class", "a"); rc.setAttr(XmlNs.html, "class", "b"); rc.closeNode("div") }).mkString("; ")
    }
  }
}
```

### Probe2.scala

```scala
package avocet.probe2

import avocet._
import avocet.impl._
import avocet.impl.DiffRenderContext.ChangesPerformer

// Second throwaway probe: attribute and style with the same name share the
// per-thread header cache key because XmlNs.html.code == 0 == the style marker.
object Probe2 {
  final class Rec extends ChangesPerformer {
    val out = scala.collection.mutable.Buffer.empty[String]
    def removeAttr(id: FastId, xmlNs: String, name: String): Unit = out += s"removeAttr(${id.mkId}, $name)"
    def removeStyle(id: FastId, name: String): Unit = out += s"removeStyle(${id.mkId}, $name)"
    def remove(id: FastId): Unit = out += s"remove(${id.mkId})"
    def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = out += s"setAttr(${id.mkId}, $name=$value)"
    def setStyle(id: FastId, name: String, value: String): Unit = out += s"setStyle(${id.mkId}, $name=$value)"
    def createText(id: FastId, text: String): Unit = out += s"createText(${id.mkId}, $text)"
    def create(id: FastId, xmlNs: String, tag: String): Unit = out += s"create(${id.mkId}, $tag)"
  }

  def attempt(label: String)(f: => Any): Unit =
    try println(s"[$label] OK: ${f}")
    catch { case e: Throwable => println(s"[$label] THREW: ${e.getClass.getName}: ${e.getMessage}") }

  def changesFromEmpty(b: RenderContext[Nothing] => Unit): Seq[String] = {
    val rc = DiffRenderContext[Nothing]()
    b(rc); rc.finalizeDocument()
    val p = new Rec
    rc.diff(p)
    p.out.toSeq
  }

  def main(args: Array[String]): Unit = {
    attempt("P10a attr width first, then style width (same thread)") {
      changesFromEmpty { rc =>
        rc.openNode(XmlNs.html, "img"); rc.setAttr(XmlNs.html, "width", "100"); rc.closeNode("img")
        rc.openNode(XmlNs.html, "div"); rc.setStyle("width", "50px"); rc.closeNode("div")
      }.mkString("; ")
    }
    attempt("P10b style height first, then attr height (same thread)") {
      changesFromEmpty { rc =>
        rc.openNode(XmlNs.html, "div"); rc.setStyle("height", "1px"); rc.closeNode("div")
        rc.openNode(XmlNs.html, "img"); rc.setAttr(XmlNs.html, "height", "2"); rc.closeNode("img")
      }.mkString("; ")
    }
    attempt("P11 default renderHtml output for br and input") {
      val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      rc.openNode(XmlNs.html, "p"); rc.addTextNode("a"); rc.openNode(XmlNs.html, "br"); rc.closeNode("br"); rc.addTextNode("b"); rc.closeNode("p")
      rc.mkString
    }
  }
}
```

## Appendix B: probe output

First program (verbatim):

```text
[P1 openNode span (4 chars)] OK: no exception
[P1 openNode blockquote (10 chars)] THREW: java.nio.BufferOverflowException: null
[P1 openNode figcaption (10 chars)] THREW: java.nio.BufferOverflowException: null
[P2 setAttr 29-char name] THREW: java.nio.BufferOverflowException: null
[P2 setStyle -webkit-border-bottom-left-radius (33 chars)] THREW: java.nio.BufferOverflowException: null
[P3 SSR: style value and attr value containing a double quote] OK: 
<div style="color:red" onmouseover="alert(1);" title="a&quot; onmouseover=&quot;alert(2)"></div>

[P4 tag Aa -> BB (String.hashCode collision): diff changes] OK: 
[P4 fresh context, create BB while thread cache holds Aa header] OK: create(1, Aa)
[P5 text update between two strings with equal MurmurHash3] OK: 't86388' -> 't118091' (hash 431770790): changes = []
[P6 40000 sibling text nodes: currentId of last] OK: 1_-25536
[P7 nesting depth 300] THREW: java.lang.IllegalArgumentException: newLimit > capacity: (257 > 256)
[P8 dom-module sequence: render, diff WITHOUT finalizeDocument] THREW: scala.MatchError: 0 (of class java.lang.Byte)
[P9 duplicate attribute on one node] OK: setAttr(1, class=a); setAttr(1, class=b)
```

Second program (verbatim; the P11 case covers only `br`):

```text
[P10a attr width first, then style width (same thread)] OK: create(1, img); setAttr(1, width=100); create(2, div); setAttr(2, width=50px)
[P10b style height first, then attr height (same thread)] OK: create(1, div); setStyle(1, height=1px); create(2, img); setStyle(2, height=2)
[P11 default renderHtml output for br and input] OK: <p>a<br></br>b</p>
```
