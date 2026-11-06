package avocet

import utest._


/**
  * Evaluation laws of the DSL (see the `avocet.dsl` package doc) for every
  * new combinator: plain and optimized templates emit the same operation
  * trace, evaluate dynamic expressions once, fail at the same point and
  * iterate collections the same number of times.
  */
object OptimizerContractTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  val tabIndex = AttrKey.int("tabindex")
  val hrefUrl = AttrKey.url("href")
  val disabledKey = AttrKey.boolean("disabled")
  val widthPx = StyleKey.px("width")
  val colorCss = StyleKey.css("color")

  def same(plain: => Document.Node[Nothing], fast: => Document.Node[Nothing]): Seq[String] = {
    val p = OpRecorder.record(plain)
    val f = OpRecorder.record(fast)
    assert(p == f)
    p
  }

  val tests = Tests {

    "typed keys lower to direct operations with the same trace" - {
      def plain(n: Int, on: Boolean) = a(tabIndex := n, disabledKey := on, hrefUrl := CheckedUrl.trusted("/x"), widthPx @= n, colorCss @= CssValue("red").get, "t")
      def fast(n: Int, on: Boolean) = optimize(a(tabIndex := n, disabledKey := on, hrefUrl := CheckedUrl.trusted("/x"), widthPx @= n, colorCss @= CssValue("red").get, "t"))
      val ops = same(plain(2, true), fast(2, true))
      assert(ops == Seq("open(html:a)", "style(width=2px)", "style(color=red)", "attr(html:tabindex=2)", "attr(html:disabled=)", "attr(html:href=/x)", "text(t)", "close(a)"))
      val off = same(plain(3, false), fast(3, false))
      assert(!off.exists(_.contains("disabled")))
    }

    "typed key values and encoders are evaluated once" - {
      var plainCount = 0
      var fastCount = 0
      val countingKey = AttrKey[Int]("data-n") { n => plainCount += 1; n.toString }
      val countingKeyFast = AttrKey[Int]("data-n") { n => fastCount += 1; n.toString }
      def plain = div(countingKey := { plainCount += 1; 1 }, disabledKey := { plainCount += 1; true }, widthPx @= { plainCount += 1; 2 })
      def fast = optimize(div(countingKeyFast := { fastCount += 1; 1 }, disabledKey := { fastCount += 1; true }, widthPx @= { fastCount += 1; 2 }))
      same(plain, fast)
      assert(plainCount == 4 && fastCount == 4)
    }

    "fragment and typed empties" - {
      def plain(flag: Boolean) = div(fragment(span("a"), if (flag) p("b") else noNode, "c"), if (flag) noAttr else clazz := "x", noStyle)
      def fast(flag: Boolean) = optimize(div(fragment(span("a"), if (flag) p("b") else noNode, "c"), if (flag) noAttr else clazz := "x", noStyle))
      assert(same(plain(true), fast(true)) == Seq("open(html:div)", "open(html:span)", "text(a)", "close(span)", "open(html:p)", "text(b)", "close(p)", "text(c)", "close(div)"))
      assert(same(plain(false), fast(false)) == Seq("open(html:div)", "attr(html:class=x)", "open(html:span)", "text(a)", "close(span)", "text(c)", "close(div)"))
      assert(OpRecorder.record(fragment[Nothing]()).isEmpty)
      assert(OpRecorder.record(optimize(div(fragment[Nothing]()))) == Seq("open(html:div)", "close(div)"))
    }

    "each iterates once per render in both forms and reuses a reusable collection" - {
      val items = List(1, 2, 3)
      var plainCount = 0
      var fastCount = 0
      def plain = ul(each(items)(i => li({ plainCount += 1; i.toString })))
      def fast = optimize(ul(each(items)(i => li({ fastCount += 1; i.toString }))))
      val ops = same(plain, fast)
      assert(ops == Seq("open(html:ul)", "open(html:li)", "text(1)", "close(li)", "open(html:li)", "text(2)", "close(li)", "open(html:li)", "text(3)", "close(li)", "close(ul)"))
      assert(plainCount == 3 && fastCount == 3)
      // Rendering the same node twice iterates the list twice.
      val p = plain
      OpRecorder.record(p); OpRecorder.record(p)
      assert(plainCount == 9)
      val f = fast
      OpRecorder.record(f); OpRecorder.record(f)
      assert(fastCount == 9)
    }

    "each over a single-use iterable renders only on the first render, in both forms" - {
      // `each` takes an `Iterable`; this one hands out the same iterator every time.
      def iterable() = new Iterable[Int] { private val it = Iterator(1, 2); def iterator: Iterator[Int] = it }
      val plainItems = iterable()
      val p = ul(each(plainItems)(i => li(i.toString)))
      val first = OpRecorder.record(p)
      val second = OpRecorder.record(p)
      assert(first.size == 8 && second == Seq("open(html:ul)", "close(ul)"))
      // The collection expression is bound outside `optimize`; inside, it would be re-evaluated per render.
      val fastItems = iterable()
      val f = optimize(ul(each(fastItems)(i => li(i.toString))))
      assert(OpRecorder.record(f) == first)
      assert(OpRecorder.record(f) == second)
    }

    "exception timing: construction time for plain arguments, render time inside optimize" - {
      def trace(doc: => Document.Node[Nothing]): (Seq[String], Boolean) = {
        val rc = new OpRecorder[Nothing]
        val threw = try { doc(rc); false } catch { case _: IllegalStateException => true }
        (rc.ops.toVector, threw)
      }
      // Plain: the argument expression runs while the template is built, before any operation.
      def plainEager = div(span("before"), p(tabIndex := { throw new IllegalStateException("x"); 1 }), span("after"))
      val eager = trace(plainEager)
      assert(eager == (Seq.empty, true))
      // Optimized: expressions run during rendering, after the operations that precede them.
      def fast = optimize(div(span("before"), p(tabIndex := { throw new IllegalStateException("x"); 1 }), span("after")))
      val f = trace(fast)
      assert(f._2)
      assert(f._1 == Seq("open(html:div)", "open(html:span)", "text(before)", "close(span)", "open(html:p)"))
      // A plain template that defers the failure to render time behaves like the optimized one.
      def plainDeferred = div(span("before"), p(Document.Attr[Nothing](_ => throw new IllegalStateException("x"))), span("after"))
      assert(trace(plainDeferred) == f)
    }

    "misc entries keep their position in both forms" - {
      final case class Ev(n: String)
      def plain = div(Ev("a"), span(Ev("b"), "t"), Ev("c"))
      def fast = optimize(div(Ev("a"), span(Ev("b"), "t"), Ev("c")))
      val p = OpRecorder.record[Ev](plain)
      val f = OpRecorder.record[Ev](fast)
      assert(p == f)
      assert(p == Seq("open(html:div)", "misc(Ev(a))", "open(html:span)", "misc(Ev(b))", "text(t)", "close(span)", "misc(Ev(c))", "close(div)"))
    }

    "an optimized template reused across renders re-evaluates its expressions" - {
      var n = 0
      val node = optimize(div(tabIndex := { n += 1; n }))
      val first = OpRecorder.record(node)
      val second = OpRecorder.record(node)
      assert(first == Seq("open(html:div)", "attr(html:tabindex=1)", "close(div)"))
      assert(second == Seq("open(html:div)", "attr(html:tabindex=2)", "close(div)"))
      // The plain form evaluated its arguments when it was built.
      var m = 0
      val plainNode = div(tabIndex := { m += 1; m })
      OpRecorder.record(plainNode); OpRecorder.record(plainNode)
      assert(m == 1)
    }

    "all new combinators in one optimized template emit the expected operations" - {
      val rc = new OpRecorder[Nothing]
      optimize(div(tabIndex := 1, disabledKey := true, widthPx @= 1, fragment(span("a")), each(List("x"))(s => p(s)), noNode))(rc)
      assert(rc.ops.toList == List("open(html:div)", "style(width=1px)", "attr(html:tabindex=1)", "attr(html:disabled=)",
        "open(html:span)", "text(a)", "close(span)", "open(html:p)", "text(x)", "close(p)", "close(div)"))
    }
  }
}
