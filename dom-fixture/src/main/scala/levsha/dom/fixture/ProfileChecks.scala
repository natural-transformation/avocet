package avocet.dom.fixture

import avocet.{Document, Validation, ValidationException, XmlNs}
import avocet.dsl._
import avocet.dsl.html._
import avocet.dsl.svg.{circle, cx, cy, r => radius, viewBox}
import avocet.dom._
import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig, XhtmlRenderContext}
import org.scalajs.dom.Element
import org.scalajs.{dom => browser}

import scala.collection.mutable
import scala.scalajs.js

import DomFixture.{check, fire, newContainer}

/**
  * F4 browser evidence: a bounded matrix of the interactive HTML profile, the
  * CSS comment policy and lifecycle churn.
  *
  * Profile cases compare the browser's own parse of the interactive SSR
  * (`Html5RenderContext`, no pretty-printing) with direct DOM construction by
  * the adapter. Supported shapes must agree on the first render, after an
  * update and after updating back. Diagnosed shapes must be rejected by
  * `Validation.interactiveHtml` with the named code, and their parse must
  * differ from direct construction, which is why they are diagnosed. Outside
  * cases record the consequence of serializers that are not the interactive
  * contract. Churn checks use the adapter's handler/index counts; they are
  * deterministic bounds, not heap measurements.
  */
object ProfileChecks {

  def run(): Unit = {
    htmlProfile()
    cssComments()
    churn()
  }

  private val xhtmlNs = "http://www.w3.org/1999/xhtml"

  // `html.br` is deprecated because the XHTML serializer writes `<br></br>`,
  // which an HTML parser reads as two elements; see the outside case below.
  private val lineBreak = TagDef("br")

  /**
    * Comparable form of a DOM subtree: namespace-qualified names, attributes
    * sorted by name (`style` as the browser's own `cssText`), and every text
    * node quoted on its own, so merged, dropped or moved text is a difference.
    */
  private def canonical(node: browser.Node): String = node.nodeType match {
    case 1 =>
      val el = node.asInstanceOf[Element]
      val ns = if (el.namespaceURI == xhtmlNs) "" else s"{${el.namespaceURI}}"
      val attrs = (0 until el.attributes.length).map { k =>
        val attr = el.attributes.item(k)
        val value = if (attr.name == "style") el.asInstanceOf[js.Dynamic].style.cssText.asInstanceOf[String] else attr.value
        s" ${attr.name}=${js.JSON.stringify(value)}"
      }.sorted
      val children = (0 until el.childNodes.length).map(k => canonical(el.childNodes(k)))
      s"<$ns${el.localName}${attrs.mkString}>${children.mkString}</${el.localName}>"
    case 3 => js.JSON.stringify(node.nodeValue)
    case other => s"<?node type $other>"
  }

  // Html5RenderContext extends XhtmlRenderContext and only changes void-element closing.
  private def serialize(doc: Document.Node[Misc], rc: XhtmlRenderContext[Misc]): String = {
    doc(rc)
    rc.mkString
  }

  private def interactiveSsr(doc: Document.Node[Misc]): String =
    serialize(doc, new Html5RenderContext[Misc](TextPrettyPrintingConfig.noPrettyPrinting))

  /** The browser's own parse of serialized markup: the first node of the body. */
  private def parsedRoot(markup: String): browser.Node =
    js.Dynamic.newInstance(js.Dynamic.global.DOMParser)().parseFromString(markup, "text/html")
      .body.firstChild.asInstanceOf[browser.Node]

  private def supported(name: String, doc: Document.Node[Misc], next: Document.Node[Misc]): Unit =
    check(s"html profile, supported: $name") {
      val container = newContainer()
      try {
        val steps = Seq("first render" -> doc, "update" -> next, "update back" -> doc).map { case (step, d) =>
          render(container, Validation.interactiveHtml)(d)
          (step, canonical(container), canonical(parsedRoot(interactiveSsr(d))))
        }
        steps.find { case (_, dom, parsed) => dom != parsed } match {
          case None => (true, steps.last._2)
          case Some((step, dom, parsed)) => (false, s"$step: dom=$dom parsed=$parsed")
        }
      } finally dispose(container)
    }

  private def diagnosed(code: String, name: String, doc: Document.Node[Misc]): Unit =
    check(s"html profile, diagnosed $code: $name") {
      val validated = newContainer()
      val direct = newContainer()
      try {
        val errors =
          try { render(validated, Validation.interactiveHtml)(doc); Nil }
          catch { case e: ValidationException => e.errors.map(_.code.name) }
        render(direct)(doc)
        val dom = canonical(direct)
        val parsed = canonical(parsedRoot(interactiveSsr(doc)))
        (errors == List(code) && validated.childNodes.length == 0 && dom != parsed, s"errors=$errors dom=$dom parsed=$parsed")
      } finally {
        dispose(validated)
        dispose(direct)
      }
    }

  private def htmlProfile(): Unit = {
    val svgRoot = TagDef(XmlNs.svg, "svg")
    supported("void elements",
      div(input(`type` := "text", value := "v"), lineBreak(), img(alt := "x"), "after"),
      div(input(`type` := "text", value := "w"), img(alt := "y"), "after"))
    supported("mixed inline content and lists",
      div(p("a ", b("bold"), " c"), ul(li("1"), li("2"))),
      div(p("a ", i("it"), " c"), ul(li("2"))))
    supported("whitespace-sensitive pre and textarea",
      div(pre("  a\n  b  "), textarea("x < y & z")),
      div(pre(" c "), textarea("x")))
    supported("explicit table sections",
      div(table(tbody(tr(td("1"), td("2"))))),
      div(table(tbody(tr(td("1")), tr(td("3"))))))
    supported("forms and buttons",
      div(form(input(name := "q"), button(`type` := "submit", "go"))),
      div(form(input(name := "r"), button(`type` := "submit", "send"))))
    supported("SVG namespace",
      div(svgRoot(viewBox := "0 0 10 10", circle(cx := "5", cy := "5", radius := "4"))),
      div(svgRoot(viewBox := "0 0 10 10", circle(cx := "5", cy := "5", radius := "3"))))
    supported("styles and delimiter-bearing data",
      div(width @= "10px", title := "a\" <b> & c", p("<script>x</script> & \"q\"")),
      div(width @= "20px", title := "d", p("e")))

    diagnosed("VOID_ELEMENT_CHILD", "a void element with a child", div(lineBreak("x")))
    diagnosed("PARAGRAPH_CLOSED_BY_BLOCK", "a block inside a paragraph", div(p(div("x"))))
    diagnosed("NESTED_FORM", "a form inside a form", div(form(form(input()))))
    diagnosed("NESTED_ANCHOR", "a link inside a link", div(a(a("x"))))
    diagnosed("TABLE_IMPLIED_SECTION", "a row directly in a table", div(table(tr(td("x")))))
    diagnosed("TABLE_FOSTER_PARENT", "a block directly in a table", div(table(div("x"))))
    diagnosed("ADJACENT_TEXT", "adjacent text nodes", div(p("a", "b")))
    diagnosed("EMPTY_TEXT", "an empty text node", div(p("")))
    diagnosed("CARRIAGE_RETURN", "a carriage return in text", div(p("a\rb")))
    diagnosed("PRE_LEADING_NEWLINE", "a leading newline in pre", div(pre("\nx")))
    diagnosed("RAW_TEXT_CONTENT", "escaped text inside style", div(TagDef("style")("a & b")))
    diagnosed("TEMPLATE_CONTENT", "children of template", div(TagDef("template")(span("x"))))

    check("html profile, outside: XHTML serialization (renderHtml) of br parses as two br elements") {
      val doc = div(lineBreak())
      val xhtml = serialize(doc, new XhtmlRenderContext[Misc](TextPrettyPrintingConfig.noPrettyPrinting))
      val xhtmlBreaks = parsedRoot(xhtml).asInstanceOf[Element].querySelectorAll("br").length
      val html5Breaks = parsedRoot(interactiveSsr(doc)).asInstanceOf[Element].querySelectorAll("br").length
      (xhtmlBreaks == 2 && html5Breaks == 1, s"xhtml=$xhtml breaks=$xhtmlBreaks html5 breaks=$html5Breaks")
    }
    check("html profile, outside: pretty-printed SSR adds whitespace text that direct DOM lacks") {
      val doc = div(p("a"), p("b"))
      val pretty = serialize(doc, new Html5RenderContext[Misc](TextPrettyPrintingConfig.default))
      val container = newContainer()
      render(container)(doc)
      val dom = canonical(container)
      dispose(container)
      val parsed = canonical(parsedRoot(pretty))
      (dom != parsed && dom == canonical(parsedRoot(interactiveSsr(doc))), s"dom=$dom pretty=$parsed")
    }
  }

  /** F4 CSS policy in a real engine: arithmetic applies identically through SSR and setters; comments never pass. */
  private def cssComments(): Unit =
    check("data-safe CSS keeps arithmetic and rejects comments inside functions") {
      val widthCss = StyleKey.css("width")
      val admitted = Seq("calc(1px /* note */ + 2px)", "calc(4px/*2)", "min(1px, max(2px /* x */, 3px))").filter(v => CssValue(v).isDefined)
      val container = newContainer()
      val mismatches = Seq("calc(100% - 10px)", "calc(100% / 2)", "calc(2 * 10px)").flatMap { v =>
        CssValue(v) match {
          case None => List(s"$v rejected")
          case Some(css) =>
            render(container)(div(widthCss @= css, "w"))
            val applied = container.asInstanceOf[browser.html.Element].style.getPropertyValue("width")
            val parsed = parsedRoot(interactiveSsr(div(widthCss @= css))).asInstanceOf[browser.html.Element].style.getPropertyValue("width")
            if (applied.nonEmpty && applied == parsed) Nil else List(s"$v applied=[$applied] parsed=[$parsed]")
        }
      }
      dispose(container)
      (admitted.isEmpty && mismatches.isEmpty, s"admitted=$admitted mismatches=$mismatches")
    }

  /** Bounded churn: counts return to their declared baseline and retired nodes dispatch nothing. */
  private def churn(): Unit = {
    val cycles = 100
    val container = newContainer()
    var clicks = 0
    // One handler on the root and one per row; each row holds one text node,
    // so a view of n rows has 1 + n handlers and 1 + 2n indexed nodes.
    def rows(count: Int, label: String): Document.Node[Misc] =
      div(event("click")({ clicks += 1; true }),
        each((0 until count).toList)(k => button(event("click")({ clicks += 1; true }), s"$label$k")))
    val retired = mutable.Buffer.empty[browser.Node]
    check("repeated mount, update, remove and dispose return handler and index counts to baseline") {
      val mismatches = mutable.Buffer.empty[String]
      def expect(cycle: Int, step: String, handlers: Int, indexed: Int): Unit = {
        val (h, x) = (debugHandlerCount(container), debugIndexSize(container))
        if (h != handlers || x != indexed) mismatches += s"cycle $cycle $step: handlers=$h (want $handlers) index=$x (want $indexed)"
      }
      for (cycle <- 0 until cycles) {
        val n = 1 + cycle % 5
        render(container)(rows(n, "a"))
        expect(cycle, "mount", 1 + n, 1 + 2 * n)
        retired += container.querySelector("button")
        render(container)(rows(n + 3, "b"))
        expect(cycle, "grow", 4 + n, 7 + 2 * n)
        render(container)(rows(n, "c"))
        expect(cycle, "shrink", 1 + n, 1 + 2 * n)
        render(container)(div("removed"))
        expect(cycle, "remove", 0, 2)
        dispose(container)
        expect(cycle, "dispose", 0, 0)
        if (debugIsMounted(container) || container.childNodes.length != 0) mismatches += s"cycle $cycle: still mounted or not emptied"
      }
      (mismatches.isEmpty, s"${mismatches.size} mismatches over $cycles cycles ${mismatches.take(3).mkString("; ")}")
    }
    check("after churn, retired nodes dispatch nothing and the current view dispatches once per handler") {
      render(container)(rows(2, "d"))
      clicks = 0
      retired.foreach(node => fire(node, "click", bubbles = true))
      val retiredClicks = clicks
      fire(container.querySelector("button"), "click", bubbles = true)
      val liveClicks = clicks - retiredClicks
      dispose(container)
      val released = debugHandlerCount(container) == 0 && debugIndexSize(container) == 0
      (retired.size == cycles && retiredClicks == 0 && liveClicks == 2 && released,
        s"retired=${retired.size} retiredClicks=$retiredClicks liveClicks=$liveClicks released=$released")
    }
  }
}
