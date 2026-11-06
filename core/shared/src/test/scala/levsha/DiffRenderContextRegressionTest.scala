package avocet

import java.nio.ByteBuffer
import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.DummyChangesPerformer
import avocet.Change._
import avocet.dsl._
import avocet.dsl.html._
import utest._

import scala.language.implicitConversions

/**
  * Regressions for the defects confirmed in IMPROVEMENT-PLAN-REVIEW.md:
  * D2 header cache, D3 long names, D4 hash-only equality, D5 limits, and
  * the lifecycle rules added with the repair.
  */
object DiffRenderContextRegressionTest extends TestSuite {

  import DiffRenderContextTest.runDiff
  import HashCollisionFixtures._

  val html5 = XmlNs.html.uri

  def changesFromEmpty(doc: Document[Nothing]): Seq[Change] = {
    val performer = new DiffTestChangesPerformer()
    val rc = DiffRenderContext[Nothing]()
    doc(rc)
    rc.finalizeDocument()
    rc.diff(performer)
    performer.result
  }

  def expectCode(code: RenderException.Code)(f: => Unit): RenderException = {
    val e = intercept[RenderException](f)
    assert(e.code == code)
    e
  }

  val tests = Tests {

    "D3 long names" - {
      "tags of 10 and more characters render" - {
        val changes = changesFromEmpty(TagDef("blockquote")(TagDef("figcaption")()))
        assert(changes == Seq(create("1", "blockquote", html5), create("1_1", "figcaption", html5)))
      }
      "svg linearGradient renders" - {
        import svg._
        val changes = changesFromEmpty(linearGradient())
        assert(changes == Seq(create("1", "linearGradient", XmlNs.svg.uri)))
      }
      "long custom element and long attribute and style names render" - {
        val longTag = "my-custom-element-with-a-rather-long-name"
        val longAttr = "data-abcdefghijklmnopqrstuvwx"
        val longStyle = "-webkit-border-bottom-left-radius"
        val changes = changesFromEmpty(TagDef(longTag)(AttrDef(longAttr) := "x", StyleDef(longStyle) @= "1px"))
        assert(changes == Seq(create("1", longTag, html5), setStyle("1", longStyle, "1px"), setAttr("1", longAttr, html5, "x")))
      }
      "name length boundary: 1024 chars accepted, 1025 rejected" - {
        val ok = "a" * 1024
        val changes = changesFromEmpty(TagDef(ok)(AttrDef(ok) := "1", StyleDef(ok) @= "2"))
        assert(changes.length == 3)
        expectCode(RenderException.Code.LimitExceeded)(changesFromEmpty(TagDef("a" * 1025)()))
        expectCode(RenderException.Code.LimitExceeded)(changesFromEmpty(div(AttrDef("a" * 1025) := "1")))
        expectCode(RenderException.Code.LimitExceeded)(changesFromEmpty(div(StyleDef("a" * 1025) @= "1")))
      }
      "invalid names are rejected before writing" - {
        expectCode(RenderException.Code.InvalidName)(changesFromEmpty(TagDef("di v")()))
        expectCode(RenderException.Code.InvalidName)(changesFromEmpty(TagDef("")()))
        expectCode(RenderException.Code.InvalidName)(changesFromEmpty(div(AttrDef("a=\"b") := "1")))
        expectCode(RenderException.Code.InvalidName)(changesFromEmpty(div(StyleDef("color\" onx=\"y") @= "1")))
        expectCode(RenderException.Code.InvalidName)(changesFromEmpty(div(AttrDef("-x") := "1")))
        // Styles may start with a dash (vendor prefixes, custom properties)
        assert(changesFromEmpty(div(StyleDef("--x") @= "1")).length == 2)
      }
    }

    "D2 same-name attribute and style" - {
      "attribute first then style on one context" - {
        val changes = changesFromEmpty(div(img(AttrDef("width") := "100"), div(width @= "50px")))
        assert(changes == Seq(
          create("1", "div", html5),
          create("1_1", "img", html5), setAttr("1_1", "width", html5, "100"),
          create("1_2", "div", html5), setStyle("1_2", "width", "50px")))
      }
      "style first then attribute on one context" - {
        val changes = changesFromEmpty(div(div(height @= "1px"), img(AttrDef("height") := "2")))
        assert(changes == Seq(
          create("1", "div", html5),
          create("1_1", "div", html5), setStyle("1_1", "height", "1px"),
          create("1_2", "img", html5), setAttr("1_2", "height", html5, "2")))
      }
      "order of warm-up across contexts does not matter" - {
        val first = changesFromEmpty(img(AttrDef("width") := "100"))
        val second = changesFromEmpty(div(width @= "50px"))
        val third = changesFromEmpty(img(AttrDef("width") := "100"))
        assert(first == Seq(create("1", "img", html5), setAttr("1", "width", html5, "100")))
        assert(second == Seq(create("1", "div", html5), setStyle("1", "width", "50px")))
        assert(third == first)
      }
      "attribute to style of the same name is a remove and a set" - {
        val changes = runDiff(div(AttrDef("width") := "1"), div(width @= "1"))
        assert(changes == Seq(removeAttr("1", html5, "width"), setStyle("1", "width", "1")))
      }
      "same name in different namespaces are different attributes" - {
        val changes = runDiff(div(AttrDef(XmlNs.html, "href") := "a"), div(AttrDef(XmlNs.xlink, "href") := "a"))
        assert(changes == Seq(removeAttr("1", html5, "href"), setAttr("1", "href", XmlNs.xlink.uri, "a")))
      }
    }

    "D4 exact equality under hash collisions" - {
      "fixtures still collide" - HashCollisionFixtures.assertStillCollide()
      "colliding tag names are different tags" - {
        val changes = runDiff(TagDef(nameA)(), TagDef(nameB)())
        assert(changes == Seq(create("1", nameB, html5)))
      }
      "fresh context after another context used the colliding tag" - {
        changesFromEmpty(TagDef(nameA)())
        val changes = changesFromEmpty(TagDef(nameB)())
        assert(changes == Seq(create("1", nameB, html5)))
      }
      "colliding texts are updated" - {
        val changes = runDiff(div(valueA), div(valueB))
        assert(changes == Seq(createText("1_1", valueB)))
      }
      "colliding attribute names are different attributes" - {
        val changes = runDiff(div(AttrDef(nameA) := "1"), div(AttrDef(nameB) := "1"))
        assert(changes == Seq(removeAttr("1", html5, nameA), setAttr("1", nameB, html5, "1")))
      }
      "colliding attribute values are updated" - {
        val changes = runDiff(div(clazz := valueA), div(clazz := valueB))
        assert(changes == Seq(setAttr("1", "class", html5, valueB)))
      }
      "colliding style values are updated" - {
        val changes = runDiff(div(color @= valueA), div(color @= valueB))
        assert(changes == Seq(setStyle("1", "color", valueB)))
      }
      "unchanged subtree after a changed sibling emits nothing for the subtree" - {
        val changes = runDiff(
          div(span("a"), div(p(clazz := "x", "deep"), "t")),
          div(span("b"), div(p(clazz := "x", "deep"), "t")))
        assert(changes == Seq(createText("1_1_1", "b")))
      }
      "colliding subtree hashes still compare exactly" - {
        def rootSum(doc: Document[Nothing]): Int = {
          val rc = DiffRenderContext[Nothing]()
          doc(rc)
          rc.finalizeDocument()
          rc.finalizedRootSum
        }
        assert(rootSum(div(subtreeTextA)) == rootSum(div(subtreeTextB)))
        val changes = runDiff(section(div(subtreeTextA)), section(div(subtreeTextB)))
        assert(changes == Seq(createText("1_1_1", subtreeTextB)))
        assert(runDiff(div(subtreeTextA), div(subtreeTextB)) == Seq(createText("1_1", subtreeTextB)))
      }
      "repeated diffing does not corrupt cursors" - {
        val rc = DiffRenderContext[Nothing]()
        div(TagDef(nameA)(valueA))(rc)
        rc.finalizeDocument()
        rc.swap()
        div(TagDef(nameB)(valueB))(rc)
        rc.finalizeDocument()
        val p1 = new DiffTestChangesPerformer()
        rc.diff(p1)
        val p2 = new DiffTestChangesPerformer()
        rc.diff(p2)
        assert(p1.result == Seq(create("1_1", nameB, html5), createText("1_1_1", valueB)))
        assert(p2.result == p1.result)
      }
    }

    "D5 limits" - {
      "32767 siblings are accepted and the next one fails before wrapping" - {
        val rc = DiffRenderContext[Nothing]()
        rc.openNode(XmlNs.html, "div")
        var i = 0
        while (i < IdBuilder.MaxSiblings) { rc.addTextNode("x"); i += 1 }
        assert(rc.currentId.mkString == "1_32767")
        expectCode(RenderException.Code.LimitExceeded)(rc.addTextNode("x"))
        // The failed call left ids untouched
        assert(rc.currentId.mkString == "1_32767")
      }
      "depth 255 is accepted and depth 256 fails" - {
        val rc = DiffRenderContext[Nothing]()
        var i = 0
        while (i < DiffRenderContext.MaxDepth) { rc.openNode(XmlNs.html, "div"); i += 1 }
        expectCode(RenderException.Code.LimitExceeded)(rc.openNode(XmlNs.html, "div"))
        i = 0
        while (i < DiffRenderContext.MaxDepth) { rc.closeNode("div"); i += 1 }
        rc.finalizeDocument()
        rc.diff(DummyChangesPerformer)
      }
      "invalid initial capacities are rejected" - {
        for (size <- Seq(0, -1, 3, Int.MinValue))
          expectCode(RenderException.Code.InvalidArgument)(DiffRenderContext[Nothing](initialBufferSize = size))
      }
      "a tiny initial buffer grows for the active half" - {
        val performer = new DiffTestChangesPerformer()
        val rc = DiffRenderContext[Nothing](initialBufferSize = 2)
        val doc = div(clazz := "x" * 100, span("y" * 300), p("z"))
        doc(rc)
        rc.finalizeDocument()
        rc.diff(performer)
        assert(performer.result.length == 6)
        rc.swap()
        div(clazz := "x" * 100, span("y" * 300), p("w"))(rc)
        rc.finalizeDocument()
        val performer2 = new DiffTestChangesPerformer()
        rc.diff(performer2)
        assert(performer2.result == Seq(createText("1_2_1", "w")))
      }
      "reset after a failure restores a usable construction state" - {
        val rc = DiffRenderContext[Nothing]()
        div(span("a"))(rc)
        rc.finalizeDocument()
        rc.swap()
        // Fail mid-node, mid-attribute, then recover
        rc.openNode(XmlNs.html, "div")
        rc.openNode(XmlNs.html, "span")
        expectCode(RenderException.Code.InvalidName)(rc.setAttr(XmlNs.html, "bad name", "1"))
        rc.reset()
        div(span("b"))(rc)
        rc.finalizeDocument()
        val performer = new DiffTestChangesPerformer()
        rc.diff(performer)
        assert(performer.result == Seq(createText("1_1_1", "b")))
      }
    }

    "lifecycle" - {
      "diff without finalizeDocument fails clearly" - {
        val rc = DiffRenderContext[Nothing]()
        div("x")(rc)
        expectCode(RenderException.Code.InvalidState)(rc.diff(DummyChangesPerformer))
      }
      "finalizeDocument with an unclosed node fails and reset recovers" - {
        val rc = DiffRenderContext[Nothing]()
        rc.openNode(XmlNs.html, "div")
        expectCode(RenderException.Code.InvalidState)(rc.finalizeDocument())
        rc.reset()
        div("x")(rc)
        rc.finalizeDocument()
        val p = new DiffTestChangesPerformer()
        rc.diff(p)
        assert(p.result == Seq(create("1", "div", html5), createText("1_1", "x")))
      }
      "rendering after finalize without swap or reset fails" - {
        val rc = DiffRenderContext[Nothing]()
        div("x")(rc)
        rc.finalizeDocument()
        expectCode(RenderException.Code.InvalidState)(rc.openNode(XmlNs.html, "div"))
        expectCode(RenderException.Code.InvalidState)(rc.finalizeDocument())
      }
      "swap while a document is under construction fails" - {
        val rc = DiffRenderContext[Nothing]()
        rc.openNode(XmlNs.html, "div")
        expectCode(RenderException.Code.InvalidState)(rc.swap())
      }
      "attributes after children and misc outside a node fail" - {
        val rc = DiffRenderContext[Nothing]()
        rc.openNode(XmlNs.html, "div")
        rc.addTextNode("x")
        expectCode(RenderException.Code.InvalidState)(rc.setAttr(XmlNs.html, "a", "1"))
        rc.reset()
        expectCode(RenderException.Code.InvalidState)(rc.closeNode("div"))
        val withMisc = DiffRenderContext[String]()
        expectCode(RenderException.Code.InvalidState)(withMisc.addMisc("outside"))
      }
      "a performer failure leaves diff repeatable" - {
        val rc = DiffRenderContext[Nothing]()
        div(span("a"), span("b"))(rc)
        rc.finalizeDocument()
        rc.swap()
        div(span("a"), span("c"))(rc)
        rc.finalizeDocument()
        val failing = new avocet.impl.DiffRenderContext.ChangesPerformer {
          def removeAttr(id: FastId, xmlNs: String, name: String): Unit = ()
          def removeStyle(id: FastId, name: String): Unit = ()
          def remove(id: FastId): Unit = ()
          def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = ()
          def setStyle(id: FastId, name: String, value: String): Unit = ()
          def createText(id: FastId, text: String): Unit = throw new RuntimeException("boom")
          def create(id: FastId, xmlNs: String, tag: String): Unit = ()
        }
        intercept[RuntimeException](rc.diff(failing))
        val p = new DiffTestChangesPerformer()
        rc.diff(p)
        assert(p.result == Seq(createText("1_2_1", "c")))
      }
    }

    "save and restore" - {
      def saved(): ByteBuffer = {
        val rc = DiffRenderContext[Nothing]()
        div(clazz := "a", span("x"))(rc)
        rc.finalizeDocument()
        rc.diff(DummyChangesPerformer)
        rc.save()
      }
      "round trip keeps the baseline and leaves the caller's buffer untouched" - {
        val buffer = saved()
        buffer.position(3)
        val rc = DiffRenderContext[Nothing](savedBuffer = Some(buffer))
        assert(buffer.position() == 3)
        rc.swap()
        rc.reset()
        div(clazz := "b", span("x"))(rc)
        rc.finalizeDocument()
        val p = new DiffTestChangesPerformer()
        rc.diff(p)
        assert(p.result == Seq(setAttr("1", "class", html5, "b")))
      }
      "save is only allowed between render cycles" - {
        val rc = DiffRenderContext[Nothing]()
        rc.openNode(XmlNs.html, "div")
        expectCode(RenderException.Code.InvalidState)(rc.save())
      }
      "malformed snapshots are rejected" - {
        val ok = saved()
        def corrupted(f: ByteBuffer => Unit): ByteBuffer = {
          val copy = ByteBuffer.allocate(ok.capacity())
          copy.put(ok.duplicate())
          copy.rewind()
          f(copy)
          copy
        }
        def rejects(buffer: ByteBuffer): Unit =
          expectCode(RenderException.Code.InvalidSavedBuffer)(DiffRenderContext[Nothing](savedBuffer = Some(buffer)))
        rejects(corrupted(_.put(0, 'X'.toByte)))                 // magic
        rejects(corrupted(_.put(4, 1.toByte)))                   // version
        rejects(corrupted(_.put(16, 9.toByte)))                  // unknown opcode in lhs
        rejects(corrupted(_.put(16 + 9, 7.toByte)))              // namespace code
        rejects(corrupted(b => b.put(b.capacity() - 1, 9.toByte))) // CLOSE replaced in rhs
        rejects(ByteBuffer.allocate(8))                          // shorter than header
        val truncated = ByteBuffer.allocate(ok.capacity() - 5)
        val view = ok.duplicate()
        view.limit(ok.capacity() - 5)
        truncated.put(view)
        rejects(truncated)
      }
    }
  }
}
