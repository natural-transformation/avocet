package avocet

import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig}
import utest._

import scala.compiletime.testing.typeCheckErrors

/**
  * Typed keys, URL and CSS policies and the trusted escape hatches. The
  * compile-negative cases use `scala.compiletime.testing.typeCheckErrors`,
  * which the current build supports without extra dependencies.
  */
object TypedDslTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  def html5(doc: Document[Nothing]): String = {
    val rc = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
    doc(rc)
    rc.mkString
  }

  val tests = Tests {

    "typed attributes encode their value once at render time" - {
      val tabIndex = AttrKey.int("tabindex")
      val rel = AttrKey.tokens("rel")
      val hrefUrl = AttrKey.url("href")
      val doc = a(tabIndex := 3, rel := List("noopener", "noreferrer"), hrefUrl := CheckedUrl("https://example.com/x?a=1&b=2").get, "go")
      assert(html5(doc) == """<a tabindex="3" rel="noopener noreferrer" href="https://example.com/x?a=1&amp;b=2">go</a>""")
      var encoded = 0
      val counting = AttrKey[Int]("data-n") { n => encoded += 1; n.toString }
      val node = div(counting := 7)
      assert(encoded == 0)
      html5(node)
      assert(encoded == 1)
    }

    "boolean attributes are present or absent" - {
      val disabledKey = AttrKey.boolean("disabled")
      assert(html5(button(disabledKey := true, "x")) == """<button disabled="">x</button>""")
      assert(html5(button(disabledKey := false, "x")) == """<button>x</button>""")
      assert(OpRecorder.record(button(disabledKey := false)) == Seq("open(html:button)", "close(button)"))
    }

    "typed styles" - {
      val w = StyleKey.px("width")
      val color = StyleKey.css("color")
      val doc = div(w @= 10, color @= CssValue("rgb(1, 2, 3)").get, "x")
      assert(html5(doc) == """<div style="width:10px;color:rgb(1, 2, 3);">x</div>""")
    }

    "names are validated when the key is built" - {
      val bad = intercept[RenderException](AttrKey.string("a b"))
      assert(bad.code == RenderException.Code.InvalidName)
      assert(intercept[RenderException](StyleKey.string("x\"y")).code == RenderException.Code.InvalidName)
      assert(intercept[RenderException](AttrKey.boolean("")).code == RenderException.Code.InvalidName)
      assert(AttrKey.validated[String](XmlNs.html, "data-ok")(identity).isRight)
      assert(AttrKey.validated[String](XmlNs.html, "on click")(identity).left.exists(_.code == RenderException.Code.InvalidName))
      assert(StyleKey.validated[String]("--custom")(identity).isRight)
      assert(StyleKey.validated[String]("")(identity).isLeft)
      assert(intercept[RenderException](trusted.inlineHandler("cl ick", "x")).code == RenderException.Code.InvalidName)
    }

    "URL policy" - {
      val accepted = Seq("https://example.com", "HTTP://x", "mailto:a@b", "tel:+1", "/relative/path", "#frag", "?q=1", "")
      accepted.foreach(u => assert(CheckedUrl(u).isDefined))
      val rejected = Seq("javascript:alert(1)", "JavaScript:alert(1)", "java\nscript:alert(1)", "\tjavascript:x", " https://x", "https://x ",
        "data:text/html,x", "vbscript:x", "file:///etc/passwd", "ftp://x")
      rejected.foreach(u => assert(CheckedUrl(u).isEmpty))
      assert(CheckedUrl("https://x", UrlPolicy.relativeOnly).isEmpty)
      assert(CheckedUrl("/x", UrlPolicy.relativeOnly).isDefined)
      assert(CheckedUrl("/x", UrlPolicy.web.copy(allowRelative = false)).isEmpty)
      assert(CheckedUrl("mailto:x", UrlPolicy.web).isEmpty)
      assert(CheckedUrl("ftp://x", UrlPolicy(Set("FTP"))).isDefined)
      // A colon after a path separator does not make a scheme.
      assert(CheckedUrl("foo/bar:baz").isDefined)
      assert(CheckedUrl("1abc:x").isDefined) // not a valid scheme, so a relative reference
      assert(CheckedUrl.trusted("javascript:void(0)").value == "javascript:void(0)")
      assert(trusted.url("javascript:void(0)") == CheckedUrl.trusted("javascript:void(0)"))
    }

    "CSS value policy" - {
      Seq("red", "10px", "1.5em", "rgb(1, 2, 3)", "#fff", "calc(100% - 10px)", "\"Helvetica Neue\", sans-serif", "0 0 1px black")
        .foreach(v => assert(CssValue(v).isDefined))
      Seq("", "red;position:fixed", "url(x)", "URL(x)", "expression(1)", "a}b", "a{b", "a<b", "a>b", "a\\62 ", "@import", "x/*y*/", "a\u0001b", "a" * 1025)
        .foreach(v => assert(CssValue(v).isEmpty))
      assert(CssValue.trusted("url(x)").value == "url(x)")
      assert(trusted.css("url(x)") == CssValue.trusted("url(x)"))
    }

    "CSS comments are rejected outside quoted strings; arithmetic and quoted text are kept" - {
      // A CSS tokenizer consumes `/*` up to `*/` (or the end) before any other
      // token, so inside an allowed function it is a comment, not arithmetic:
      // `calc(4px/*2)` opens an unterminated comment rather than dividing.
      Seq(
        "calc(1px/*note*/ + 2px)", "calc(1px /* note */ + 2px)", "min(1px, max(2px /* x */, 3px))",
        "rgb(1 2 3 /**/)", "calc(4px/*2)", "calc(1px /* x)", "10px /* top */", "calc(1px + 2px) /*")
        .foreach(v => assert(CssValue(v).isEmpty))
      Seq(
        "calc(100% - 10px)", "calc(100% / 2)", "calc(4px/2)", "calc(2 * 1px)", "calc(2*1px)",
        "calc((100% - 10px) / 2)", "calc(2 * (1px + 2px))", "1 / 2", "12px/1.5 sans-serif",
        "\"a /* b */ c\"", "'*/ x /*'")
        .foreach(v => assert(CssValue(v).isDefined))
    }

    "trusted escape hatches name their sink and are still escaped as attribute data" - {
      val doc = div(trusted.inlineHandler("click", "alert(\"1\")"), TagDef("iframe")(trusted.srcdoc("<p>x</p>")))
      assert(html5(doc) == """<div onclick="alert(&quot;1&quot;)"><iframe srcdoc="&lt;p&gt;x&lt;/p&gt;"></iframe></div>""")
    }

    "compile-negative: typed keys reject wrong value types" - {
      val intKey = typeCheckErrors("""{
        import avocet.dsl._
        val k = AttrKey.int("tabindex")
        k := "3"
      }""")
      assert(intKey.nonEmpty)
      val urlKey = typeCheckErrors("""{
        import avocet.dsl._
        val k = AttrKey.url("href")
        k := "https://example.com"
      }""")
      assert(urlKey.nonEmpty)
      val cssKey = typeCheckErrors("""{
        import avocet.dsl._
        val k = StyleKey.css("color")
        k @= "red"
      }""")
      assert(cssKey.nonEmpty)
      val boolKey = typeCheckErrors("""{
        import avocet.dsl._
        val k = AttrKey.boolean("disabled")
        k := "disabled"
      }""")
      assert(boolKey.nonEmpty)
      val noPublicCtor = typeCheckErrors("""{
        import avocet.dsl._
        new CheckedUrl("javascript:x")
      }""")
      assert(noPublicCtor.nonEmpty)
      val styleKeyIsNotAnAttr = typeCheckErrors("""{
        import avocet.dsl._
        val k = StyleKey.px("width")
        k := 10
      }""")
      assert(styleKeyIsNotAnAttr.nonEmpty)
      // Sanity: the positive forms do type-check in the same harness.
      val ok = typeCheckErrors("""{
        import avocet.dsl._
        import avocet.dsl.html._
        val k = AttrKey.int("tabindex")
        val u = AttrKey.url("href")
        div(k := 3, u := CheckedUrl.trusted("/x"), noAttr, noStyle, noNode)
      }""")
      assert(ok.isEmpty)
    }
  }
}
