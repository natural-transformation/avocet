package avocet.dom.fixture

import avocet.{Document, RenderException, Validation, ValidationException}
import avocet.dsl._
import avocet.dsl.html._
import avocet.dom._
import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig}
import avocet.events.EventPhase
import org.scalajs.dom.{document, Element}
import org.scalajs.{dom => browser}

import scala.collection.mutable
import scala.scalajs.js

/**
  * Browser fixture for the Scala.js DOM adapter. It mounts into a fresh
  * container, drives first/repeated renders, handler dispatch, failure
  * containment, disposal and the SSR parse of the style-encoding probe, and
  * publishes the results on `window.avocetDomFixture` and in the page.
  *
  * Build and serve (from the repository root):
  * {{{
  * nix develop . --no-write-lock-file --command sbt domFixture/fastLinkJS
  * nix develop . --no-write-lock-file --command node misc/dom-fixture/serve.mjs
  * }}}
  * then open http://127.0.0.1:8765/misc/dom-fixture/index.html in a browser.
  */
object DomFixture {

  final case class Result(name: String, ok: Boolean, detail: String)

  private val results = mutable.Buffer.empty[Result]

  private def check(name: String)(body: => (Boolean, String)): Unit = {
    val (ok, detail) =
      try body
      catch { case e: Throwable => (false, s"threw ${e.getClass.getSimpleName}: ${e.getMessage}") }
    results += Result(name, ok, detail)
  }

  private def newContainer(): Element = {
    val el = document.createElement("div")
    document.body.appendChild(el)
    el
  }

  private def fire(target: browser.Node, tpe: String, bubbles: Boolean): Unit = {
    val ctor = if (tpe == "click") js.Dynamic.global.MouseEvent else js.Dynamic.global.Event
    val event = js.Dynamic.newInstance(ctor)(tpe, jsObject("bubbles" -> bubbles, "cancelable" -> true))
    target.asInstanceOf[js.Dynamic].dispatchEvent(event)
  }

  // The template root maps onto the container, so the container's outerHTML is the rendered tree.
  private def html(el: Element): String = el.outerHTML

  // `js.Dynamic.literal` clashes with the DSL implicits in scope, so build plain objects by hand.
  private def jsObject(fields: (String, js.Any)*): js.Dynamic = {
    val obj = (new js.Object).asInstanceOf[js.Dynamic]
    fields.foreach { case (key, value) => obj.updateDynamic(key)(value) }
    obj
  }

  def main(args: Array[String]): Unit = {
    firstAndRepeatedRender()
    handlers()
    failureContainment()
    disposal()
    reentrancy()
    ssrStyleEncoding()
    lifecycleApi()
    publish()
  }

  private def firstAndRepeatedRender(): Unit = {
    val container = newContainer()
    check("first render builds the tree") {
      render(container)(div(clazz := "a", width @= "10px", span("one"), "tail"))
      val expected = "<div class=\"a\" style=\"width: 10px;\"><span>one</span>tail</div>"
      (html(container) == expected, html(container))
    }
    val spanBefore = container.querySelector("span")
    check("second render updates in place and keeps unchanged nodes") {
      render(container)(div(clazz := "b", width @= "10px", span("two"), "tail"))
      val expected = "<div class=\"b\" style=\"width: 10px;\"><span>two</span>tail</div>"
      val sameSpan = container.querySelector("span") eq spanBefore
      (html(container) == expected && sameSpan, s"${html(container)} sameSpan=$sameSpan")
    }
    check("third render removes and replaces subtrees and cleans the index") {
      render(container)(div(clazz := "b", width @= "10px", p("x")))
      val expected = "<div class=\"b\" style=\"width: 10px;\"><p>x</p></div>"
      val index = debugIndexSize(container)
      (html(container) == expected && index == 3, s"${html(container)} index=$index")
    }
    check("render after shrinking grows back and ids stay positional") {
      render(container)(div(clazz := "b", width @= "10px", p("x"), ul(li("1"), li("2"))))
      val expected = "<div class=\"b\" style=\"width: 10px;\"><p>x</p><ul><li>1</li><li>2</li></ul></div>"
      (html(container) == expected, html(container))
    }
    dispose(container)
  }

  private def handlers(): Unit = {
    val container = newContainer()
    val log = mutable.Buffer.empty[String]
    def view(stopAtInner: Boolean) = div(
      event("click")({ log += "outer-bubble"; true }),
      event("click", EventPhase.Capturing)({ log += "outer-capture"; true }),
      button(
        event("click")({ log += "inner-1"; !stopAtInner }),
        event("click")({ log += "inner-2"; true }),
        "press"),
      input(`type` := "text", event("focus")({ log += "focus"; true })))
    render(container)(view(stopAtInner = false))
    check("several handlers for one element and event all run, in order") {
      log.clear()
      fire(container.querySelector("button"), "click", bubbles = true)
      val expected = List("outer-capture", "inner-1", "inner-2", "outer-bubble")
      (log.toList == expected && debugHandlerCount(container) == 5, s"${log.toList} handlers=${debugHandlerCount(container)}")
    }
    check("non-bubbling focus reaches its handler through the capture listener") {
      log.clear()
      fire(container.querySelector("input"), "focus", bubbles = false)
      (log.toList == List("focus"), log.toList.toString)
    }
    check("a handler that does not return true stops propagation") {
      render(container)(view(stopAtInner = true))
      log.clear()
      fire(container.querySelector("button"), "click", bubbles = true)
      (log.toList == List("outer-capture", "inner-1", "inner-2"), log.toList.toString)
    }
    check("handlers of a replaced subtree are gone") {
      render(container)(div("plain"))
      log.clear()
      fire(container.firstChild, "click", bubbles = true)
      (log.isEmpty && debugHandlerCount(container) == 0, s"${log.toList} handlers=${debugHandlerCount(container)}")
    }
    check("submit default action is prevented") {
      var prevented = false
      render(container)(div(form(event("submit")(true), button(`type` := "submit", "go"))))
      val formEl = container.querySelector("form")
      val submit = js.Dynamic.newInstance(js.Dynamic.global.Event)("submit", jsObject("bubbles" -> true, "cancelable" -> true))
      formEl.asInstanceOf[js.Dynamic].dispatchEvent(submit)
      prevented = submit.defaultPrevented.asInstanceOf[Boolean]
      (prevented, s"defaultPrevented=$prevented")
    }
    dispose(container)
  }

  private def failureContainment(): Unit = {
    val container = newContainer()
    val log = mutable.Buffer.empty[String]
    render(container)(div(event("click")({ log += "old"; true }), span("ok")))
    check("a throwing template keeps the published tree and handlers") {
      // The failure happens while rendering (not while building the argument), so the lifecycle is exercised.
      val failing = Document.Node[Misc] { rc => span("new")(rc); throw new RuntimeException("template failure") }
      val threw =
        try { render(container)(div(failing)); false }
        catch { case e: RuntimeException if e.getMessage == "template failure" => true }
      log.clear()
      fire(container.firstChild, "click", bubbles = true)
      (threw && html(container) == "<div><span>ok</span></div>" && log.toList == List("old"), s"threw=$threw ${html(container)} ${log.toList}")
    }
    check("the next valid render diffs against the retained baseline") {
      render(container)(div(span("after")))
      (html(container) == "<div><span>after</span></div>", html(container))
    }
    check("a DOM write failure marks the root failed and dispose recovers") {
      // Make `document.createElementNS` fail once, then render a document that must create a node.
      val createElementNS = document.asInstanceOf[js.Dynamic].createElementNS
      document.asInstanceOf[js.Dynamic].createElementNS = { () => throw new js.JavaScriptException("injected DOM failure") }: js.Function0[Any]
      val threw =
        try { render(container)(div(span("after"), p("more"))); false }
        catch { case _: Throwable => true }
        finally document.asInstanceOf[js.Dynamic].createElementNS = createElementNS
      val failed = debugIsFailed(container)
      val refused =
        try { render(container)(div("x")); false }
        catch { case e: RenderException => e.code == RenderException.Code.InvalidState }
      dispose(container)
      render(container)(div("fresh"))
      (threw && failed && refused && html(container) == "<div>fresh</div>", s"threw=$threw failed=$failed refused=$refused ${html(container)}")
    }
    dispose(container)
  }

  private def disposal(): Unit = {
    val container = newContainer()
    val log = mutable.Buffer.empty[String]
    render(container)(div(event("click")({ log += "x"; true }), span("a")))
    check("dispose removes nodes, handlers, listeners and the root; it is idempotent") {
      dispose(container)
      dispose(container)
      fire(container, "click", bubbles = true)
      val clean = container.innerHTML == "" && !debugIsMounted(container) && debugHandlerCount(container) == 0 && debugIndexSize(container) == 0
      (clean && log.isEmpty, s"${html(container)} mounted=${debugIsMounted(container)} log=${log.toList}")
    }
    check("dispose from a rendering callback is rejected and keeps the published view") {
      render(container)(div("keep"))
      val threw =
        try { render(container)(div(Document.Node[Misc] { _ => dispose(container) }, "x")); false }
        catch { case _: RenderException => true }
      val kept = html(container) == "<div>keep</div>" && debugIsMounted(container)
      val stillWorks = { render(container)(div("next")); html(container) == "<div>next</div>" }
      (threw && kept && stillWorks, s"threw=$threw kept=$kept ${html(container)}")
    }
    check("render after dispose mounts from scratch") {
      render(container)(div("again"))
      (html(container) == "<div>again</div>" && debugIndexSize(container) == 2, s"${html(container)} index=${debugIndexSize(container)}")
    }
    dispose(container)
  }

  private def reentrancy(): Unit = {
    val container = newContainer()
    check("a render started from a custom element callback during apply is rejected") {
      var rejected = false
      var callbackRan = false
      js.Dynamic.global.window.__avocetReentrant = { () =>
        callbackRan = true
        try render(container)(div("nested"))
        catch { case e: RenderException if e.code == RenderException.Code.InvalidState => rejected = true }
      }: js.Function0[Unit]
      render(container)(div(TagDef("x-reentrant")()))
      js.Dynamic.global.window.__avocetReentrant = null
      val afterwards = { render(container)(div("later")); html(container) == "<div>later</div>" }
      (callbackRan && rejected && afterwards, s"callbackRan=$callbackRan rejected=$rejected ${html(container)}")
    }
    dispose(container)
  }

  /** M2: validation through the adapter, generations and the typed authoring API in a real DOM. */
  private def lifecycleApi(): Unit = {
    val container = newContainer()
    val hrefUrl = AttrKey.url("href")
    val tabIndex = AttrKey.int("tabindex")
    val widthPx = StyleKey.px("width")
    check("validated render publishes a valid typed document and bumps the generation") {
      render(container, Validation.interactiveHtml)(div(
        a(hrefUrl := CheckedUrl("https://example.com/?a=1&b=2").get, tabIndex := 2, "link"),
        each(List("x", "y"))(s => p(widthPx @= 10, s))))
      val expected = "<div><a href=\"https://example.com/?a=1&amp;b=2\" tabindex=\"2\">link</a><p style=\"width: 10px;\">x</p><p style=\"width: 10px;\">y</p></div>"
      (html(container) == expected && debugGeneration(container) == 1, s"${html(container)} gen=${debugGeneration(container)}")
    }
    check("an invalid document is rejected with coded errors and the DOM is untouched") {
      val before = html(container)
      val errors =
        try { render(container, Validation.interactiveHtml)(div(p(div("block")), "a", "b")); Nil }
        catch { case e: ValidationException => e.errors.map(_.code.name) }
      (errors == List("PARAGRAPH_CLOSED_BY_BLOCK") && html(container) == before && debugGeneration(container) == 1, s"errors=$errors ${html(container)}")
    }
    check("data-safe CSS rejects resource loaders and an accepted gradient makes no request") {
      val rejected = Seq("image-set(\"https://example.invalid/x.png\" 1x)", "url(https://example.invalid/y.png)", "src(\"x\")")
        .forall(v => CssValue(v).isEmpty)
      val backgroundImage = StyleKey.css("background-image")
      // Own container: a removed style leaves `style=""` behind, which would change later outerHTML checks.
      val cssContainer = newContainer()
      render(cssContainer)(div(backgroundImage @= CssValue("linear-gradient(red, blue)").get, "g"))
      val applied = cssContainer.asInstanceOf[browser.html.Element].style.getPropertyValue("background-image").contains("linear-gradient")
      dispose(cssContainer)
      val entries = js.Dynamic.global.performance.getEntriesByType("resource").asInstanceOf[js.Array[js.Dynamic]]
      val requested = entries.exists(e => e.name.asInstanceOf[String].contains("example.invalid"))
      (rejected && applied && !requested, s"rejected=$rejected applied=$applied requested=$requested")
    }
    check("a rejected javascript URL never reaches the DOM") {
      val link = CheckedUrl("javascript:alert(1)").fold(CheckedUrl.trusted("#rejected"))(identity)
      render(container, Validation.interactiveHtml)(div(a(hrefUrl := link, "safe")))
      (html(container) == "<div><a href=\"#rejected\">safe</a></div>" && debugGeneration(container) == 2, html(container))
    }
    dispose(container)
  }

  private def ssrStyleEncoding(): Unit = {
    val probe = div(
      color @= "red\" onmouseover=\"alert(1)",
      backgroundColor @= "url(x?a=1&b=<2>)",
      width @= "10px",
      title := "a\" onmouseover=\"alert(2)",
      "t")
    check("SSR style data parses as one style attribute and no handler") {
      val rc = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
      probe(rc)
      val parsed = js.Dynamic.newInstance(js.Dynamic.global.DOMParser)().parseFromString(rc.mkString, "text/html")
      val el = parsed.body.firstChild
      val names = (0 until el.attributes.length.asInstanceOf[Int]).map(i => el.attributes.item(i).name.asInstanceOf[String]).toList.sorted
      val style = el.getAttribute("style").asInstanceOf[String]
      val title = el.getAttribute("title").asInstanceOf[String]
      val styleOk = style == "color:red\" onmouseover=\"alert(1);background-color:url(x?a=1&b=<2>);width:10px;"
      val titleOk = title == "a\" onmouseover=\"alert(2)"
      val widthOk = el.style.getPropertyValue("width").asInstanceOf[String] == "10px"
      val scriptFree = parsed.querySelectorAll("script").length.asInstanceOf[Int] == 0
      (names == List("style", "title") && styleOk && titleOk && widthOk && scriptFree, s"attrs=$names style=[$style] title=[$title] width=${el.style.getPropertyValue("width")}")
    }
    check("DOM update applies the same values through setters") {
      val container = newContainer()
      render(container)(probe)
      val el = container.asInstanceOf[browser.html.Element]
      val names = (0 until el.attributes.length).map(i => el.attributes.item(i).name).toList.sorted
      val colorRejected = el.style.getPropertyValue("color") == ""
      val widthOk = el.style.getPropertyValue("width") == "10px"
      val titleOk = el.getAttribute("title") == "a\" onmouseover=\"alert(2)"
      dispose(container)
      (names == List("style", "title") && colorRejected && widthOk && titleOk, s"attrs=$names color=[${el.style.getPropertyValue("color")}] title=[${el.getAttribute("title")}]")
    }
  }

  private def publish(): Unit = {
    val failed = results.count(!_.ok)
    val summary = s"avocet dom fixture: ${results.size} checks, $failed failed"
    val pre = document.createElement("pre")
    pre.id = "results"
    pre.textContent = (summary +: results.map(r => s"${if (r.ok) "PASS" else "FAIL"} ${r.name}${if (r.ok) "" else s" -- ${r.detail}"}")).mkString("\n")
    document.body.appendChild(pre)
    document.title = if (failed == 0) "PASS " + summary else "FAIL " + summary
    js.Dynamic.global.window.avocetDomFixture = jsObject(
      "summary" -> summary,
      "failed" -> failed,
      "results" -> js.Array(results.toSeq.map(r => jsObject("name" -> r.name, "ok" -> r.ok, "detail" -> r.detail)): _*))
  }
}
