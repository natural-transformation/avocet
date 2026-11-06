package avocet

import avocet.impl.{DiffRenderContext, Html5RenderContext, OwnedChange, OwnedChangesPerformer, TextPrettyPrintingConfig}
import avocet.impl.DiffRenderContext.ChangesPerformer
import utest._

import scala.collection.mutable

/**
  * Migration matrix of the contracts (§10): the same application view
  * rendered by an unchanged old consumer, an opt-in new consumer, a custom
  * `RenderContext` and a custom `ChangesPerformer`. Output, events and
  * cleanup are compared, not only compilation. The Scala.js DOM application
  * is the `domFixture` project; Spoonbill is covered by the candidate
  * publish recorded in IMPLEMENTATION-EVIDENCE.md.
  */
object MigrationMatrixTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  final case class Ev(name: String)

  /** The old-style view: raw string attributes, implicit sequences and options. */
  def oldView(items: Seq[String], selectedItem: Option[String]): Document.Node[Ev] =
    div(clazz := "list", Ev("root-click"),
      ul(items.map(item => li(selectedItem.filter(_ == item).map(_ => clazz := "selected"), a(href := s"/items/$item", Ev("item-click"), item)))),
      selectedItem.map(s => p(s"selected: $s")))

  /** The same view with the M2 authoring API. */
  val hrefUrl = AttrKey.url("href")
  val selectedClass = AttrKey.tokens("class")
  def newView(items: Seq[String], selectedItem: Option[String]): Document.Node[Ev] =
    optimize(div(clazz := "list", Ev("root-click"),
      ul(each(items)(item => li(
        if (selectedItem.contains(item)) selectedClass := List("selected") else noAttr,
        a(hrefUrl := CheckedUrl(s"/items/$item").get, Ev("item-click"), item)))),
      selectedItem.map(s => p(s"selected: $s"))))

  def html5(doc: Document.Node[Ev]): String = {
    val rc = new Html5RenderContext[Ev](TextPrettyPrintingConfig.noPrettyPrinting)
    doc(rc)
    rc.mkString
  }

  /** Old consumer: drives the low-level context by hand with a string-decoding performer. */
  final class OldConsumer {
    val changes = mutable.Buffer.empty[String]
    val misc = mutable.Buffer.empty[(String, Ev)]
    private val performer = new ChangesPerformer {
      def removeAttr(id: FastId, xmlNs: String, name: String): Unit = changes += s"removeAttr ${id.mkId} $name"
      def removeStyle(id: FastId, name: String): Unit = changes += s"removeStyle ${id.mkId} $name"
      def remove(id: FastId): Unit = changes += s"remove ${id.mkId}"
      def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = changes += s"setAttr ${id.mkId} $name=$value"
      def setStyle(id: FastId, name: String, value: String): Unit = changes += s"setStyle ${id.mkId} $name=$value"
      def createText(id: FastId, text: String): Unit = changes += s"text ${id.mkId} $text"
      def create(id: FastId, xmlNs: String, tag: String): Unit = changes += s"create ${id.mkId} $tag"
    }
    private val rc = DiffRenderContext[Ev]((id, ev) => misc += ((id.mkString, ev)))
    def render(doc: Document.Node[Ev]): Unit = {
      misc.clear()
      doc(rc); rc.finalizeDocument(); rc.diff(performer); rc.swap()
    }
  }

  /** New consumer: `RenderRoot` with validation and the owned performer. */
  final class NewConsumer {
    val performer = new OwnedChangesPerformer.Buffer
    val root = RenderRoot[Ev](performer, Validation.interactiveHtml)
    def render(doc: Document.Node[Ev]): Vector[OwnedChange] = {
      val outcome = root.render(doc)
      assert(outcome.isPublished)
      performer.drain()
    }
    def misc: Seq[(String, Ev)] = root.misc.map { case (id, ev) => (id.mkString, ev) }
  }

  def describe(c: OwnedChange): String = c match {
    case OwnedChange.Create(id, _, tag) => s"create $id $tag"
    case OwnedChange.CreateText(id, text) => s"text $id $text"
    case OwnedChange.Remove(id) => s"remove $id"
    case OwnedChange.SetAttr(id, _, name, value) => s"setAttr $id $name=$value"
    case OwnedChange.RemoveAttr(id, _, name) => s"removeAttr $id $name"
    case OwnedChange.SetStyle(id, name, value) => s"setStyle $id $name=$value"
    case OwnedChange.RemoveStyle(id, name) => s"removeStyle $id $name"
  }

  val tests = Tests {

    "old and new views serialize identically" - {
      val items = Seq("a", "b")
      assert(html5(oldView(items, Some("b"))) == html5(newView(items, Some("b"))))
      assert(html5(oldView(items, None)) == html5(newView(items, None)))
      assert(html5(newView(items, Some("b"))) ==
        """<div class="list"><ul><li><a href="/items/a">a</a></li><li class="selected"><a href="/items/b">b</a></li></ul><p>selected: b</p></div>""")
    }

    "old consumer and new consumer emit the same changes and misc entries across renders" - {
      val old = new OldConsumer
      val fresh = new NewConsumer
      val steps = Seq((Seq("a", "b"), None), (Seq("a", "b", "c"), Some("b")), (Seq("c"), Some("c")), (Seq.empty[String], None))
      steps.foreach { case (items, selected) =>
        old.changes.clear()
        old.render(oldView(items, selected))
        val fromNew = fresh.render(newView(items, selected)).map(describe)
        assert(old.changes.toVector == fromNew)
        assert(old.misc.toList == fresh.misc.toList)
      }
      assert(fresh.root.generation == steps.size)
    }

    "a custom RenderContext sees the same operations from old, new, plain and optimized forms" - {
      val items = Seq("x", "y")
      val fromOld = OpRecorder.record(oldView(items, Some("x")))
      val fromNew = OpRecorder.record(newView(items, Some("x")))
      assert(fromOld == fromNew)
      assert(fromOld.count(_.startsWith("misc")) == 3)
    }

    "a custom ChangesPerformer works unchanged under RenderRoot" - {
      val log = mutable.Buffer.empty[String]
      val custom = new ChangesPerformer {
        def removeAttr(id: FastId, xmlNs: String, name: String): Unit = log += s"-$name@${id.mkId}"
        def removeStyle(id: FastId, name: String): Unit = log += s"-style:$name@${id.mkId}"
        def remove(id: FastId): Unit = log += s"-@${id.mkId}"
        def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = log += s"+$name=$value@${id.mkId}"
        def setStyle(id: FastId, name: String, value: String): Unit = log += s"+style:$name=$value@${id.mkId}"
        def createText(id: FastId, text: String): Unit = log += s"+'$text'@${id.mkId}"
        def create(id: FastId, xmlNs: String, tag: String): Unit = log += s"+$tag@${id.mkId}"
      }
      val root = RenderRoot[Ev](custom)
      assert(root.render(newView(Seq("a"), None)).isPublished)
      log.clear()
      assert(root.render(newView(Seq("a"), Some("a"))).isPublished)
      assert(log.toList == List("+class=selected@1_1_1", "+p@1_2", "+'selected: a'@1_2_1"))
      root.dispose()
      assert(root.state == RenderRoot.State.Disposed)
    }

    "the saved buffer of an old consumer restores into a new root" - {
      val rc = DiffRenderContext[Ev]()
      oldView(Seq("a"), None)(rc); rc.finalizeDocument(); rc.swap()
      val performer = new OwnedChangesPerformer.Buffer
      val root = RenderRoot[Ev](performer, savedBuffer = Some(rc.save()))
      assert(root.render(newView(Seq("a", "b"), None)).isPublished)
      assert(performer.drain().map(describe) == Vector("create 1_1_2 li", "create 1_1_2_1 a", "setAttr 1_1_2_1 href=/items/b", "text 1_1_2_1_1 b"))
    }
  }
}
