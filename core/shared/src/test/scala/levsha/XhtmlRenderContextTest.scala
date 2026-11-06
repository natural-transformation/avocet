package avocet

import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig, XhtmlRenderContext}
import utest._

object XhtmlRenderContextTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  def xhtml(doc: Document[Nothing]): String = {
    val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
    doc(rc)
    rc.mkString
  }

  def html5(doc: Document[Nothing]): String = {
    val rc = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
    doc(rc)
    rc.mkString
  }

  def expectCode(code: RenderException.Code)(f: => Unit): Unit = {
    val e = intercept[RenderException](f)
    assert(e.code == code)
  }

  val tests = Tests {
    test("text nodes should be escaped") - {
      val rc = new XhtmlRenderContext(TextPrettyPrintingConfig.noPrettyPrinting)
      div("1 < 3 > 2 && \"ha ha ha\" ").apply(rc)
      val result = rc.mkString
      assert(result == "<div>1 &lt; 3 &gt; 2 &amp;&amp; &quot;ha ha ha&quot; </div>")
    }
    test("attr values should be escaped") - {
      val rc = new XhtmlRenderContext(TextPrettyPrintingConfig.noPrettyPrinting)
      input(value := "project=\"PRJ\"").apply(rc)
      val result = rc.mkString
      assert(result == "<input value=\"project=&quot;PRJ&quot;\"></input>")
    }

    // D1: style data must stay inside the quoted style attribute.
    val styleProbe = div(
      color @= "red\" onmouseover=\"alert(1)",
      backgroundColor @= "url(x?a=1&b=<2>)",
      title := "a\" onmouseover=\"alert(2)",
      "t")
    val styleProbeExpected =
      "<div style=\"color:red&quot; onmouseover=&quot;alert(1);background-color:url(x?a=1&amp;b=&lt;2&gt;);\"" +
        " title=\"a&quot; onmouseover=&quot;alert(2)\">t</div>"

    test("style values are encoded for the enclosing attribute (XHTML)") - {
      val result = xhtml(styleProbe)
      assert(result == styleProbeExpected)
    }
    test("style values are encoded for the enclosing attribute (HTML5)") - {
      val result = html5(styleProbe)
      assert(result == styleProbeExpected)
    }
    test("HTML5 void element with styles and attributes") - {
      val result = html5(input(color @= "a\"b", value := "v"))
      assert(result == "<input style=\"color:a&quot;b;\" value=\"v\"/>")
    }
    test("ordinary styles still work") - {
      val result = xhtml(div(backgroundColor @= "black", width @= "10px", clazz := "x", span("y")))
      assert(result == "<div style=\"background-color:black;width:10px;\" class=\"x\"><span>y</span></div>")
    }
    test("invalid dynamic names are rejected, not escaped") - {
      expectCode(RenderException.Code.InvalidName)(xhtml(TagDef("div onclick=x")()))
      expectCode(RenderException.Code.InvalidName)(xhtml(div(AttrDef("a\"b") := "1")))
      expectCode(RenderException.Code.InvalidName)(xhtml(div(StyleDef("color\" onmouseover=\"x") @= "1")))
      expectCode(RenderException.Code.InvalidName)(html5(TagDef("a b")()))
    }
    test("custom elements, namespaced attributes and custom properties are accepted") - {
      val result = xhtml(TagDef("my-element")(AttrDef(XmlNs.xlink, "xlink:href") := "#a", StyleDef("--my-prop") @= "1"))
      assert(result == "<my-element style=\"--my-prop:1;\" xlink:href=\"#a\"></my-element>")
    }

    // Ordering contract for raw interpreter calls: styles, then attributes, then children.
    test("a style after an attribute is rejected instead of emitting a second style attribute") - {
      val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      rc.openNode(XmlNs.html, "div")
      rc.setAttr(XmlNs.html, "class", "x")
      expectCode(RenderException.Code.InvalidState)(rc.setStyle("color", "red"))
    }
    test("an attribute after a child is rejected") - {
      val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      rc.openNode(XmlNs.html, "div")
      rc.addTextNode("x")
      expectCode(RenderException.Code.InvalidState)(rc.setAttr(XmlNs.html, "class", "x"))
    }
  }
}
