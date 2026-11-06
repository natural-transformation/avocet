package avocet

import avocet.ValidationError.Code
import avocet.ValidationError.Code._
import avocet.impl.{DiffRenderContext, Html5RenderContext, TextPrettyPrintingConfig}
import utest._

/**
  * Every validation code has a case here, in both reporting modes. Shape
  * checks are exercised through the HTML5 interpreter with pretty-printing
  * off (the interactive SSR path), structural ones through the diff engine
  * and the recorder.
  */
object ValidatingRenderContextTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  /** Codes reported for `doc` in collect mode, with the inner context only recording. */
  def codes[M](doc: Document[M], checks: Validation = Validation.interactiveHtml): Seq[Code] =
    errors(doc, checks).map(_.code)

  def errors[M](doc: Document[M], checks: Validation = Validation.interactiveHtml): Seq[ValidationError] = {
    val rc = ValidatingRenderContext(new OpRecorder[M], checks.collect(64))
    doc(rc)
    rc.complete()
    rc.errors
  }

  def failFast(doc: Document[Nothing], checks: Validation = Validation.interactiveHtml): ValidationException = {
    val rc = ValidatingRenderContext(new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting), checks)
    intercept[ValidationException] { doc(rc); rc.complete() }
  }

  val tests = Tests {

    "code names are unique and grouped" - {
      assert(Code.all.map(_.name).distinct.size == Code.all.size)
      assert(Code.all.exists(_.check == ValidationError.Check.Structural))
      assert(Code.all.exists(_.check == ValidationError.Check.HtmlShape))
      assert(Code.all.exists(_.check == ValidationError.Check.Interactive))
    }

    "a valid complex template produces no diagnostics and the same output" - {
      val doc = DefaultDslTest.complexTemplate1
      assert(errors(doc).isEmpty)
      val plain = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      doc(plain)
      val inner = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      val validated = ValidatingRenderContext(inner, Validation.interactiveHtml)
      doc(validated)
      validated.complete()
      assert(inner.mkString == plain.mkString)
    }

    "structural" - {
      def raw(f: RenderContext[Nothing] => Unit): Document.Node[Nothing] = Document.Node(f)
      "close without open and mismatch" - {
        assert(codes(raw { rc => rc.openNode(XmlNs.html, "div"); rc.closeNode("span"); rc.closeNode("div") }, Validation.structural)
          == Seq(CloseMismatch, CloseWithoutOpen))
      }
      "attribute outside, after child, style after attribute" - {
        assert(codes(raw(_.setAttr(XmlNs.html, "a", "1")), Validation.structural) == Seq(AttrOutsideNode))
        assert(codes(raw { rc => rc.openNode(XmlNs.html, "div"); rc.addTextNode("t"); rc.setStyle("x", "1"); rc.closeNode("div") }, Validation.structural)
          == Seq(AttrAfterChild))
        assert(codes(raw { rc => rc.openNode(XmlNs.html, "div"); rc.setAttr(XmlNs.html, "a", "1"); rc.setStyle("x", "1"); rc.closeNode("div") }, Validation.structural)
          == Seq(StyleAfterAttr))
      }
      "duplicate attribute and style, same name across roles is fine" - {
        assert(codes(div(clazz := "a", clazz := "b"), Validation.structural) == Seq(DuplicateAttr))
        assert(codes(div(width @= "1", width @= "2"), Validation.structural) == Seq(DuplicateStyle))
        assert(codes(div(width @= "1", AttrDef("width") := "2"), Validation.structural).isEmpty)
        assert(codes(div(AttrDef(XmlNs.svg, "x") := "1", AttrDef("x") := "2"), Validation.structural).isEmpty)
      }
      "misc outside a node, unclosed nodes" - {
        assert(codes(Document.Node[String](_.addMisc("m")), Validation.structural) == Seq(MiscOutsideNode))
        val e = errors(raw { rc => rc.openNode(XmlNs.html, "div"); rc.openNode(XmlNs.html, "p") }, Validation.structural)
        assert(e.map(_.code) == Seq(UnclosedNode, UnclosedNode))
        assert(e.map(_.position) == Seq("1_1", "1"))
        assert(e.map(_.element) == Seq("p", "div"))
      }
      "nesting beyond the engine depth is reported and the stack stays consistent" - {
        val deep = raw { rc =>
          (0 until 257).foreach(_ => rc.openNode(XmlNs.html, "div"))
          (0 until 257).foreach(_ => rc.closeNode("div"))
        }
        val c = codes(deep, Validation.structural)
        assert(c == Seq(DepthExceeded, DepthExceeded))
      }
      "positions are the diff engine's ids" - {
        val e = errors(div(span(), p("t", "u")), Validation.interactiveHtml)
        assert(e.map(_.code) == Seq(AdjacentText))
        assert(e.head.position == "1_2_2" && e.head.element == "p")
      }
    }

    "html shape" - {
      "void element children" - {
        assert(codes(div(br(span("x")))) == Seq(VoidElementChild))
        assert(codes(div(img("alt text"))) == Seq(VoidElementChild))
        assert(codes(div(br(), "text")).isEmpty)
      }
      "paragraph closed by a block" - {
        assert(codes(p(div("x"))) == Seq(ParagraphClosedByBlock))
        assert(codes(p(span("x"), em("y"))).isEmpty)
        assert(codes(p(ul(li("x")))) == Seq(ParagraphClosedByBlock))
      }
      "nested form, anchor and button" - {
        assert(codes(form(div(form()))) == Seq(NestedForm))
        assert(codes(a(span(a()))) == Seq(NestedAnchor))
        assert(codes(button(button())) == Seq(NestedButton))
        assert(codes(div(form(), form())).isEmpty)
      }
      "table structure" - {
        assert(codes(table(tr(td("x")))) == Seq(TableImpliedSection))
        assert(codes(table(tbody(tr(td("x"))))).isEmpty)
        assert(codes(table(div("x"))) == Seq(TableFosterParent))
        assert(codes(table(tbody(td()))) == Seq(TableFosterParent))
        assert(codes(table(tbody(tr(div())))) == Seq(TableFosterParent))
        assert(codes(table(tbody("x"))) == Seq(TableFosterParent))
        assert(codes(table(tbody(" \n "))).isEmpty)
        assert(codes(table(colgroup(div()))) == Seq(TableFosterParent))
      }
      "raw text and select children" - {
        assert(codes(TagDef("textarea")(span("x")), Validation.html) == Seq(RawTextChild))
        assert(codes(select(div(), option("a"))) == Seq(SelectChild))
        assert(codes(select(optgroup(option("a")), hr())).isEmpty)
      }
      "HTML rules do not apply to other namespaces" - {
        import avocet.dsl.svg._
        val doc = Svg(TagDef(XmlNs.svg, "p")(TagDef(XmlNs.svg, "div")()), TagDef(XmlNs.svg, "br")(TagDef(XmlNs.svg, "a")()))
        assert(codes(doc).isEmpty)
        // An HTML child inside a foreign parent is not foster-parented by these rules either.
        assert(codes(TagDef(XmlNs.svg, "table")(div())).isEmpty)
      }
    }

    "interactive bootstrap" - {
      "text segmentation" - {
        assert(codes(div("a", "b")) == Seq(AdjacentText))
        assert(codes(div("a", span(), "b")).isEmpty)
        assert(codes(div("")) == Seq(EmptyText))
        assert(codes(Document.Node[Nothing](_.addTextNode("root"))) == Seq(TextAtRoot))
      }
      "roots and document elements" - {
        val two = Document.Node[Nothing] { rc => div()(rc); div()(rc) }
        assert(codes(two) == Seq(MultipleRoots))
        assert(codes(div(body())) == Seq(DocumentElement))
        assert(codes(div(TagDef("template")(span()))) == Seq(TemplateContent))
      }
      "parser text normalization" - {
        assert(codes(pre("\nx")) == Seq(PreLeadingNewline))
        assert(codes(pre("x\n")).isEmpty)
        assert(codes(pre(span(), "\nx")).isEmpty)
        assert(codes(div("a\r\nb")) == Seq(CarriageReturn))
        assert(codes(div(title := "a\rb")) == Seq(CarriageReturn))
        assert(codes(div(TagDef("script")("alert(1)"))) == Seq(RawTextContent))
        assert(codes(div(TagDef("textarea")("ok"))).isEmpty)
      }
    }

    "modes" - {
      "fail-fast throws before forwarding the offending operation" - {
        val inner = new OpRecorder[Nothing]
        val rc = ValidatingRenderContext(inner, Validation.html)
        val e = intercept[ValidationException](p(span("a"), div("b"))(rc))
        assert(e.code == RenderException.Code.InvalidDocument)
        assert(e.errors.map(_.code) == Seq(ParagraphClosedByBlock) && !e.truncated)
        assert(inner.ops.toList == List("open(html:p)", "open(html:span)", "text(a)", "close(span)"))
        assert(failFast(div("a", "b")).errors.head.code == AdjacentText)
      }
      "collect mode caps errors, forwards everything and reports truncation" - {
        val inner = new OpRecorder[Nothing]
        val rc = ValidatingRenderContext(inner, Validation.interactiveHtml.collect(3))
        div("", "", "", "", "")(rc)
        rc.complete()
        assert(rc.errors.size == 3 && rc.truncated)
        assert(inner.ops.size == 7)
        val collectedThrows = intercept[RenderException](ValidationMode.Collect(0))
        assert(collectedThrows.code == RenderException.Code.InvalidArgument)
      }
      "reset clears errors and the stack for the next document" - {
        val rc = ValidatingRenderContext(new OpRecorder[Nothing], Validation.structural.collect())
        Document.Node[Nothing](_.openNode(XmlNs.html, "div"))(rc)
        rc.complete()
        assert(rc.errors.nonEmpty)
        rc.reset()
        div(span())(rc)
        rc.complete()
        assert(rc.errors.isEmpty)
      }
      "selecting a check group only reports its codes" - {
        val doc = p(div("a", "b"))
        assert(codes(doc, Validation.structural).isEmpty)
        assert(codes(doc, Validation.html) == Seq(ParagraphClosedByBlock))
        assert(codes(doc, Validation.off.copy(interactive = true)) == Seq(AdjacentText))
      }
    }

    "stateful variant forwards ids and misc through the inner context" - {
      val misc = scala.collection.mutable.Buffer.empty[(String, String)]
      val inner = DiffRenderContext[String]((id, m) => misc += ((id.mkString, m)))
      val rc = ValidatingRenderContext.stateful(inner, Validation.interactiveHtml)
      var seen = List.empty[String]
      var forwarded = true
      rc.openNode(XmlNs.html, "ul")
      rc.openNode(XmlNs.html, "li")
      rc.addMisc("first")
      seen = rc.currentContainerId.mkString :: seen
      forwarded &&= rc.currentId == inner.currentId && rc.currentContainerId == inner.currentContainerId
      rc.closeNode("li")
      rc.openNode(XmlNs.html, "li")
      seen = rc.currentContainerId.mkString :: rc.subsequentId.mkString :: seen
      forwarded &&= rc.subsequentId == inner.subsequentId
      rc.closeNode("li")
      rc.closeNode("ul")
      rc.complete()
      inner.finalizeDocument()
      assert(rc.errors.isEmpty)
      assert(forwarded)
      assert(seen.reverse == List("1_1", "1_2_1", "1_2"))
      assert(misc.toList == List("1_1" -> "first"))
    }

    "plain and optimized templates get identical diagnostics" - {
      def plain(flag: Boolean) = p(if (flag) div("a", "b") else span("ok"))
      def fast(flag: Boolean) = optimize(p(if (flag) div("a", "b") else span("ok")))
      assert(errors(plain(true)) == errors(fast(true)))
      assert(errors(plain(true)).map(_.code) == Seq(ParagraphClosedByBlock, AdjacentText))
      assert(errors(plain(false)).isEmpty && errors(fast(false)).isEmpty)
    }
  }
}
