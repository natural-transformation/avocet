package avocet

import avocet.RenderOutcome.{Invalidated, Published, Rejected}
import avocet.RenderRoot.State
import avocet.impl.{OwnedChange, OwnedChangesPerformer}
import avocet.impl.DiffRenderContext.FastChangesPerformer
import utest._

import java.nio.ByteBuffer
import scala.collection.mutable

/**
  * The standalone consumer that drove the `RenderRoot` signatures: initial
  * render, replacement, invalid input, performer failure, custom interpreter
  * use (stateful ids), save/restore and cleanup. Each case asserts the
  * outcome and the state the root is left in.
  */
object RenderLifecycleTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  final case class Ev(name: String)

  /** Performer that throws on the `failAt`-th change; `remove` works so a reset can replay. */
  final class FailingPerformer(failAt: Int) extends OwnedChangesPerformer {
    var seen = 0
    val changes = mutable.Buffer.empty[OwnedChange]
    def emit(change: OwnedChange): Unit = {
      seen += 1
      if (seen == failAt) throw new RuntimeException("backend failure")
      changes += change
    }
  }

  def tags(changes: Seq[OwnedChange]): Seq[String] = changes.collect { case OwnedChange.Create(_, _, tag) => tag }

  val tests = Tests {

    "first render publishes the full tree and the misc entries" - {
      val performer = new OwnedChangesPerformer.Buffer
      val root = RenderRoot[Ev](performer)
      assert(root.state == State.Idle && root.generation == 0 && root.misc.isEmpty)
      val outcome = root.render(div(clazz := "a", button(Ev("click"), "go"), span("x")))
      assert(outcome == Published(1))
      val changes = performer.drain()
      assert(tags(changes) == Seq("div", "button", "span"))
      assert(root.misc == Vector(Id("1_1") -> Ev("click")))
      assert(root.state == State.Idle)
    }

    "second render diffs against the published baseline" - {
      val performer = new OwnedChangesPerformer.Buffer
      val root = RenderRoot[Ev](performer)
      root.render(div(span("one"), span("two")))
      performer.drain()
      assert(root.render(div(span("one"), p("two"))) == Published(2))
      val changes = performer.drain()
      assert(changes == Vector(OwnedChange.Create(Id("1_2"), XmlNs.html, "p"), OwnedChange.CreateText(Id("1_2_1"), "two")))
    }

    "a throwing template is rejected and the baseline and misc entries stay" - {
      val performer = new OwnedChangesPerformer.Buffer
      val root = RenderRoot[Ev](performer)
      root.render(div(Ev("old"), span("a")))
      performer.drain()
      // A plain template evaluates its arguments when it is built, so the failure must happen at render time.
      val failing = Document.Node[Ev] { rc => span("b")(rc); throw new IllegalArgumentException("boom") }
      val outcome = root.render(div(failing))
      assert(outcome match { case Rejected(_: IllegalArgumentException) => true; case _ => false })
      assert(root.state == State.Idle && root.generation == 1 && root.misc == Vector(Id("1") -> Ev("old")))
      assert(performer.size == 0)
      // The next valid render diffs against the retained baseline.
      assert(root.render(div(span("b"))) == Published(2))
      assert(performer.drain() == Vector(OwnedChange.CreateText(Id("1_1_1"), "b")))
      assert(root.misc.isEmpty)
    }

    "an engine limit error in the template is rejected with its code" - {
      val root = RenderRoot[Ev](new OwnedChangesPerformer.Buffer)
      val outcome = root.render(TagDef("a" * 2000)())
      assert(outcome.failure.exists { case e: RenderException => e.code == RenderException.Code.LimitExceeded; case _ => false })
      assert(root.state == State.Idle)
      assert(root.render(div()) == Published(1))
    }

    "a performer failure invalidates the root; reset replays from an empty baseline" - {
      val performer = new FailingPerformer(failAt = 3)
      val root = RenderRoot[Ev](performer)
      val outcome = root.render(div(Ev("h"), span("a"), span("b")))
      assert(outcome match { case Invalidated(e) => e.getMessage == "backend failure"; case _ => false })
      assert(root.state == State.Invalid && root.generation == 0 && root.misc.isEmpty)
      val refused = intercept[RenderException](root.render(div()))
      assert(refused.code == RenderException.Code.InvalidState)
      root.reset()
      assert(root.state == State.Idle && root.generation == 1)
      performer.changes.clear()
      assert(root.render(div(Ev("h"), span("a"))) == Published(2))
      // Full replay: the root element is created again, then its content.
      assert(tags(performer.changes.toSeq) == Seq("div", "span"))
      assert(root.misc == Vector(Id("1") -> Ev("h")))
    }

    "a nested render from a misc callback is refused and the outer render still completes" - {
      val performer = new OwnedChangesPerformer.Buffer
      var root: RenderRoot[Ev] = null
      var nested: Option[RenderException] = None
      root = RenderRoot[Ev](performer)
      // Anything that runs inside the template (misc callbacks included) sees the root in `Rendering`.
      val template = Document.Node[Ev] { rc =>
        rc.openNode(XmlNs.html, "div")
        try root.render(div()) catch { case e: RenderException => nested = Some(e) }
        rc.closeNode("div")
      }
      assert(root.render(template) == Published(1))
      assert(nested.exists(_.code == RenderException.Code.InvalidState))
    }

    "stateful ids are visible to the template through renderWith" - {
      val root = RenderRoot[Ev](new OwnedChangesPerformer.Buffer)
      val ids = mutable.Buffer.empty[String]
      val outcome = root.renderWith { rc =>
        rc.openNode(XmlNs.html, "ul")
        rc.openNode(XmlNs.html, "li"); ids += rc.currentContainerId.mkString; rc.closeNode("li")
        rc.openNode(XmlNs.html, "li"); ids += rc.currentContainerId.mkString; rc.closeNode("li")
        rc.closeNode("ul")
      }
      assert(outcome == Published(1))
      assert(ids.toList == List("1_1", "1_2"))
    }

    "validation rejects an invalid document before anything is published" - {
      val performer = new OwnedChangesPerformer.Buffer
      val root = RenderRoot[Ev](performer, Validation.html)
      root.render(div(span("ok")))
      performer.drain()
      val outcome = root.render(p(div("block in paragraph")))
      val errors = outcome match { case Rejected(e: ValidationException) => e.errors; case _ => Nil }
      assert(errors.map(_.code) == Seq(ValidationError.Code.ParagraphClosedByBlock))
      assert(performer.size == 0 && root.generation == 1)
    }

    "collect mode reports every error of a rejected document" - {
      val root = RenderRoot[Ev](new OwnedChangesPerformer.Buffer, Validation.html.collect(8))
      val outcome = root.render(p(div(), br(span("x")), form(form())))
      val codes = outcome match { case Rejected(e: ValidationException) => e.errors.map(_.code); case _ => Nil }
      import ValidationError.Code._
      // `div` closes the paragraph once; by the time `form` arrives the parser has no open paragraph, so only the nested form is reported.
      assert(codes == Seq(ParagraphClosedByBlock, VoidElementChild, NestedForm))
    }

    "collect mode attaches the collected errors when the engine throws first" - {
      val root = RenderRoot[Ev](new OwnedChangesPerformer.Buffer, Validation.structural.collect())
      val template = Document.Node[Ev] { rc =>
        rc.openNode(XmlNs.html, "div")
        rc.closeNode("div")
        rc.closeNode("div") // reported by the validator, then refused by the engine
      }
      val outcome = root.render(template)
      assert(outcome match {
        case Rejected(e: ValidationException) =>
          e.errors.map(_.code) == Seq(ValidationError.Code.CloseWithoutOpen) && e.getCause.isInstanceOf[RenderException]
        case _ => false
      })
    }

    "save and restore continue from the published baseline" - {
      val first = RenderRoot[Ev](new OwnedChangesPerformer.Buffer)
      first.render(div(span("a"), span("b")))
      val saved: ByteBuffer = first.save()
      val performer = new OwnedChangesPerformer.Buffer
      val second = RenderRoot[Ev](performer, savedBuffer = Some(saved))
      assert(second.render(div(span("a"), span("c"))) == Published(1))
      assert(performer.drain() == Vector(OwnedChange.CreateText(Id("1_2_1"), "c")))
    }

    "dispose releases the root; every later call fails with INVALID_STATE" - {
      val root = RenderRoot[Ev](new OwnedChangesPerformer.Buffer)
      root.render(div(Ev("x")))
      root.dispose()
      root.dispose()
      assert(root.state == State.Disposed && root.misc.isEmpty)
      Seq[() => Any](() => root.render(div()), () => root.reset(), () => root.save(), () => root.trim(), () => root.bufferCapacity)
        .foreach { call =>
          val e = intercept[RenderException](call())
          assert(e.code == RenderException.Code.InvalidState)
        }
    }

    "a custom fast performer is driven in order with borrowed arguments" - {
      val log = mutable.Buffer.empty[String]
      val performer = new FastChangesPerformer {
        def remove(id: FastId): Unit = log += s"remove ${id.mkId}"
        def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = log += s"create ${id.mkId}"
        def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit = log += s"text ${id.mkId}"
        def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = log += s"removeAttr ${id.mkId}"
        def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = log += s"removeStyle ${id.mkId}"
        def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = log += s"setAttr ${id.mkId}"
        def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = log += s"setStyle ${id.mkId}"
      }
      val root = RenderRoot[Ev](performer)
      root.render(div(clazz := "a", span("x"), span("y")))
      log.clear()
      root.render(div(width @= "1px", span("x")))
      // Removed attributes and styles are reported before the new ones are set; then the extra child goes.
      assert(log.toList == List("removeAttr 1", "setStyle 1", "remove 1_2"))
    }
  }
}
