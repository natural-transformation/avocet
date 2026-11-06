package avocet

import avocet.Document.Node
import avocet.impl.TextPrettyPrintingConfig
import avocet.text._

object DefaultDslTest extends utest.TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  def tests = {
    import utest._
    Tests {
      "if (expr) node else void" - {
        val result = renderHtml(ifexpr1(true), TextPrettyPrintingConfig.noPrettyPrinting)
        assert(result == """<div class="hello"></div>""")
      }
      "if (expr) void else node" - {
        val result = renderHtml(ifexpr2(false), TextPrettyPrintingConfig.noPrettyPrinting)
        assert(result == """<div class="hello"></div>""")
      }
      "if (expr) foo else bar" - {
        val result1 = renderHtml(ifexpr3(true), TextPrettyPrintingConfig.noPrettyPrinting)
        val result2 = renderHtml(ifexpr3(false), TextPrettyPrintingConfig.noPrettyPrinting)
        assert(result1 == """<div class="foo"></div>""")
        assert(result2 == """<div class="bar"></div>""")
      }
      "complex template 1" - {
        val result1 = renderHtml(complexTemplate1)
        val result2 = renderHtml(optimizedComplexTemplate1)
        assert(result1 == result2)
      }
      "sort content for unoptimized templates" - {
        val expect = """<div style="background-color:black;" class="foo"><a></a>hello</div>"""
        val result = renderHtml(unorderedTemplate, TextPrettyPrintingConfig.noPrettyPrinting)
        assert(result == expect)
      }
      "sort content for optimized templates" - {
        val expect = """<div style="background-color:black;" class="foo"><a></a>hello</div>"""
        val result = renderHtml(optimizedUnorderedTemplate, TextPrettyPrintingConfig.noPrettyPrinting)
        assert(result == expect)
      }
    }
  }

  def ifexpr1(expr: Boolean): Node[Nothing] =
    optimize {
      val hello = "hello"
      div(if (expr) clazz := hello else void)
    }
  def ifexpr2(expr: Boolean): Node[Nothing] =
    optimize(div(if (expr) void else clazz := "hello"))
  def ifexpr3(expr: Boolean): Node[Nothing] =
    optimize(div(if (expr) clazz := "foo" else clazz := "bar"))

  val items = Seq(1, 2, 3)
  val optValue1 = Option("hello")
  val optValue2 = Option.empty[String]

  val complexTemplate1 =
    div(
      when(2+2==5)(checked),
      border @= "1 px solid",
      h1(disabled, backgroundColor @= "red", "The Items!"),
      optValue1.map(x => p(x)),
      optValue1 match {
        case Some(x) => p(x)
        case None => void
      },
      select(
        option("one"),
        option(selected, "two")
      ),
      ul(
        items map { i =>
          li(
            optValue2.map(x => clazz := x),
            a(href := s"http://example.com/items/$i", s"Go $i")
          )
        }
      )
    )

  val optimizedComplexTemplate1 = optimize {
    div(
      when(2+2==5)(checked),
      border @= "1 px solid",
      h1(disabled, backgroundColor @= "red", "The Items!"),
      optValue1.map(x => p(x)),
      optValue1 match {
        case Some(x) => p(x)
        case None => void
      },
      select(
        option("one"),
        option(selected, "two")
      ),
      ul(
        items map { i =>
          li(
            optValue2.map(x => clazz := x),
            a(href := s"http://example.com/items/$i", s"Go $i")
          )
        }
      )
    )
  }

  val unorderedTemplate = {
    div(
      a(),
      "hello",
      void,
      clazz := "foo",
      backgroundColor @= "black"
    )
  }

  val optimizedUnorderedTemplate = optimize {
    div(
      a(),
      "hello",
      void,
      clazz := "foo",
      backgroundColor @= "black"
    )
  }
}

/**
  * Operation-trace and evaluation-count checks: optimized and plain templates
  * must emit the same operations in the same order, and dynamic expressions
  * must be evaluated exactly once in both forms.
  */
object DslTraceTest extends utest.TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  def tests = {
    import utest._
    Tests {
      "complex template: plain and optimized traces are identical" - {
        val plain = OpRecorder.record(DefaultDslTest.complexTemplate1)
        val optimized = OpRecorder.record(DefaultDslTest.optimizedComplexTemplate1)
        assert(plain == optimized)
        assert(plain.head == "open(html:div)")
        assert(plain.contains("style(border=1 px solid)"))
      }
      "namespaces survive both forms" - {
        import svg._
        def doc(x: Int) = Svg(width := "10", TagDef(XmlNs.svg, "circle")(AttrDef(XmlNs.svg, "r") := x.toString))
        def optimizedDoc(x: Int) = optimize(Svg(width := "10", TagDef(XmlNs.svg, "circle")(AttrDef(XmlNs.svg, "r") := x.toString)))
        val plain = OpRecorder.record(doc(3))
        val fast = OpRecorder.record(optimizedDoc(3))
        assert(plain == fast)
        assert(plain == Seq("open(svg:svg)", "attr(html:width=10)", "open(svg:circle)", "attr(svg:r=3)", "close(circle)", "close(svg)"))
      }
      "dynamic expressions are evaluated once in both forms" - {
        var plainCount = 0
        var optimizedCount = 0
        val items = Seq(1, 2)
        val opt = Option("o")
        def plain = div(
          clazz := { plainCount += 1; "c" },
          opt.map(o => p({ plainCount += 1; o })),
          items.map(i => span({ plainCount += 1; i.toString })))
        def fast = optimize(div(
          clazz := { optimizedCount += 1; "c" },
          opt.map(o => p({ optimizedCount += 1; o })),
          items.map(i => span({ optimizedCount += 1; i.toString }))))
        val plainOps = OpRecorder.record(plain)
        val fastOps = OpRecorder.record(fast)
        assert(plainOps == fastOps)
        assert(plainCount == 4)
        assert(optimizedCount == 4)
      }
      "conditional and match forms agree" - {
        def plain(flag: Boolean, o: Option[String]) = div(
          if (flag) clazz := "yes" else void,
          when(!flag)(span("no")),
          o match { case Some(x) => p(x); case None => void })
        def fast(flag: Boolean, o: Option[String]) = optimize(div(
          if (flag) clazz := "yes" else void,
          when(!flag)(span("no")),
          o match { case Some(x) => p(x); case None => void }))
        for ((flag, o) <- Seq((true, Some("a")), (false, None), (true, None), (false, Some("b")))) {
          val p = OpRecorder.record(plain(flag, o))
          val f = OpRecorder.record(fast(flag, o))
          assert(p == f)
        }
      }
    }
  }
}
