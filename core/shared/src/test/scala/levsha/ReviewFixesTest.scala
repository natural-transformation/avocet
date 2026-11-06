package avocet

import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.DummyChangesPerformer
import avocet.dsl._
import avocet.dsl.html._
import avocet.ValidationError.Code._
import utest._

/**
  * Regressions for the PR review findings on the M2 change set:
  * capacity-1 growth loop, CssValue resource loading, optimizer evaluation
  * counts for dynamic receivers and callbacks, paragraph button scope.
  * (The factory binary compatibility check is JVM-only, see FactoryBinaryCompatTest;
  * the reentrant dispose case runs in the browser fixture.)
  */
object ReviewFixesTest extends TestSuite {

  def codes(doc: Document.Node[Nothing]): Seq[ValidationError.Code] = {
    val rc = ValidatingRenderContext[Nothing](new OpRecorder[Nothing], Validation.html.collect(16))
    doc(rc)
    rc.errors.map(_.code)
  }

  def same(plain: Document.Node[Nothing], fast: Document.Node[Nothing]): Seq[String] = {
    val p = OpRecorder.record(plain)
    val f = OpRecorder.record(fast)
    assert(p == f)
    p
  }

  val tests = Tests {
    "capacity 1 is rejected; capacity 2 trims and restores a non-empty baseline" - {
      val e = intercept[RenderException](DiffRenderContext[Nothing](initialBufferSize = 1))
      assert(e.code == RenderException.Code.InvalidArgument)
      val rc = DiffRenderContext[Nothing](initialBufferSize = 2)
      div(span("a" * 50), p("b"))(rc)
      rc.finalizeDocument()
      rc.swap()
      rc.trim()
      div(span("a" * 50), p("c"))(rc)
      rc.finalizeDocument()
      val performer = new Change.DiffTestChangesPerformer()
      rc.diff(performer)
      assert(performer.result == Seq(Change.createText(List(1, 2, 1), "c")))
      val restored = DiffRenderContext[Nothing](initialBufferSize = 2, savedBuffer = Some(rc.save()))
      restored.swap()
      restored.reset()
      div(span("a" * 50), p("c"))(restored)
      restored.finalizeDocument()
      val none = new Change.DiffTestChangesPerformer()
      restored.diff(none)
      assert(none.result.isEmpty)
    }

    "CssValue rejects every resource-loading function and accepts the allowlisted grammar" - {
      val rejected = Seq(
        "image-set(\"https://example.invalid/x.png\" 1x)",
        "-webkit-image-set(\"x\" 1x)",
        "src(\"x\")", "image(\"x\")", "element(#x)", "attr(x)", "url(x)", "URL(x)",
        "linear-gradient(url(x))", "calc(image-set(\"x\" 1x))",
        "var(--x, \"s\")", "rgb(1, 2, 3", "a)", "!important", "x\\62", "x/*y*/", "@import",
        "cross-fade(url(a), url(b))", "paint(x)", "counter(x)", "format(\"woff\")")
      rejected.foreach(v => assert(CssValue(v).isEmpty))
      val accepted = Seq(
        "red", "10px", "1.5em", "-2px", ".5", "1e3px", "100%", "#fff", "#80808080",
        "rgb(1, 2, 3)", "hsla(1, 2%, 3%, .5)", "calc(100% - 10px)", "clamp(1rem, 2vw, 3rem)",
        "var(--main-color)", "linear-gradient(red, blue)", "translate(1px, 2px) rotate(3deg)",
        "\"Helvetica Neue\", sans-serif", "0 0 1px black", "-webkit-box", "inherit", "1px solid red",
        "repeat(3, minmax(0, 1fr))")
      accepted.foreach(v => assert(CssValue(v).isDefined))
    }

    "dynamic receivers are evaluated once in both forms" - {
      var plainCount = 0
      var fastCount = 0
      def keyP() = { plainCount += 1; AttrKey.int("data-n") }
      def keyF() = { fastCount += 1; AttrKey.int("data-n") }
      def boolP() = { plainCount += 1; AttrKey.boolean("disabled") }
      def boolF() = { fastCount += 1; AttrKey.boolean("disabled") }
      def styleP() = { plainCount += 1; StyleKey.px("width") }
      def styleF() = { fastCount += 1; StyleKey.px("width") }
      def defP() = { plainCount += 1; AttrDef("data-d") }
      def defF() = { fastCount += 1; AttrDef("data-d") }
      def plain = div(keyP() := 1, boolP() := false, styleP() @= 2, defP() := "v")
      def fast = optimize(div(keyF() := 1, boolF() := false, styleF() @= 2, defF() := "v"))
      val ops = same(plain, fast)
      assert(ops == Seq("open(html:div)", "style(width=2px)", "attr(html:data-n=1)", "attr(html:data-d=v)", "close(div)"))
      assert(plainCount == 4)
      assert(fastCount == 4)
    }

    "a dynamic each callback is created once per render and keeps its state across elements" - {
      def counter(): Int => Document.Node[Nothing] = {
        var n = 0
        _ => { n += 1; li(n.toString) }
      }
      def plain = ul(each(List(1, 2, 3))(counter()))
      def fast = optimize(ul(each(List(1, 2, 3))(counter())))
      val ops = same(plain, fast)
      assert(ops == Seq("open(html:ul)", "open(html:li)", "text(1)", "close(li)", "open(html:li)", "text(2)", "close(li)", "open(html:li)", "text(3)", "close(li)", "close(ul)"))
    }

    "a dynamic option and mapper are evaluated once in both forms" - {
      var plainCount = 0
      var fastCount = 0
      def optP() = { plainCount += 1; Option("x") }
      def optF() = { fastCount += 1; Option("x") }
      def mapP(): String => Document.Node[Nothing] = { plainCount += 1; s => span(s) }
      def mapF(): String => Document.Node[Nothing] = { fastCount += 1; s => span(s) }
      def seqP() = { plainCount += 1; Seq("a", "b") }
      def seqF() = { fastCount += 1; Seq("a", "b") }
      def plain = div(optP().map(mapP()), seqP().map(mapP()))
      def fast = optimize(div(optF().map(mapF()), seqF().map(mapF())))
      val ops = same(plain, fast)
      assert(ops == Seq("open(html:div)", "open(html:span)", "text(x)", "close(span)", "open(html:span)", "text(a)", "close(span)", "open(html:span)", "text(b)", "close(span)", "close(div)"))
      assert(plainCount == 4)
      assert(fastCount == 4)
    }

    "paragraph closed through inline descendants and button scope" - {
      assert(codes(div(p(span(div("x"))))) == Seq(ParagraphClosedByBlock))
      assert(codes(div(p(span(em(ul(li("x"))))))) == Seq(ParagraphClosedByBlock))
      assert(codes(div(p(span("x")))).isEmpty)
      assert(codes(p(button(div("x")))).isEmpty)
    }
  }
}
