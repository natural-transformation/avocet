package avocet

/**
  * Test-only reference interpreter of change operations.
  *
  * It applies emitted changes in order to a map of positional ids and
  * compares the result with the intended tree. The semantics are written
  * independently of the production diff algorithm:
  *
  *  - `create`/`createText` replace the node at the id (and drop its
  *    descendants) or add it under an existing element parent;
  *  - `remove` drops the node and its descendants without renumbering;
  *  - attribute and style operations require an existing element.
  *
  * It is an oracle for tests, not a production virtual DOM.
  */
object ReferenceDom {

  sealed trait Node
  final case class Element(tag: String, ns: String, attrs: Map[(String, String), String], styles: Map[String, String]) extends Node
  final case class Text(value: String) extends Node

  /** Flat tree keyed by positional id (`List(1)` is the root). */
  type Tree = Map[List[Int], Node]

  def fromDoc(doc: TestDoc): Tree = flatten(List(1), doc).toMap

  private def flatten(id: List[Int], doc: TestDoc): List[(List[Int], Node)] = doc match {
    case TestDoc.Text(value) => List(id -> Text(value))
    case TestDoc.Element(name, attrs, styles, xs) =>
      val self = id -> Element(name, XmlNs.html.uri, attrs.map { case (k, v) => (XmlNs.html.uri, k) -> v }, styles)
      self :: xs.toList.zipWithIndex.flatMap { case (child, i) => flatten(id :+ (i + 1), child) }
  }

  def apply(tree: Tree, changes: Seq[Change]): Tree =
    changes.foldLeft(tree)(applyOne)

  def applyOne(tree: Tree, change: Change): Tree = change match {
    case Change.create(id, tag, ns) =>
      requireParent(tree, id)
      dropSubtree(tree, id) + (id -> Element(tag, ns, Map.empty, Map.empty))
    case Change.createText(id, text) =>
      requireParent(tree, id)
      dropSubtree(tree, id) + (id -> Text(text))
    case Change.remove(id) =>
      if (!tree.contains(id)) fail(s"remove of missing node ${show(id)}")
      dropSubtree(tree, id)
    case Change.setAttr(id, name, ns, value) =>
      val el = element(tree, id, change)
      tree + (id -> el.copy(attrs = el.attrs + ((ns, name) -> value)))
    case Change.removeAttr(id, ns, name) =>
      val el = element(tree, id, change)
      if (!el.attrs.contains((ns, name))) fail(s"removeAttr of missing attribute on ${show(id)}")
      tree + (id -> el.copy(attrs = el.attrs - ((ns, name))))
    case Change.setStyle(id, name, value) =>
      val el = element(tree, id, change)
      tree + (id -> el.copy(styles = el.styles + (name -> value)))
    case Change.removeStyle(id, name) =>
      val el = element(tree, id, change)
      if (!el.styles.contains(name)) fail(s"removeStyle of missing style on ${show(id)}")
      tree + (id -> el.copy(styles = el.styles - name))
  }

  private def requireParent(tree: Tree, id: List[Int]): Unit =
    if (id.length > 1) tree.get(id.init) match {
      case Some(_: Element) => ()
      case Some(_: Text) => fail(s"parent of ${show(id)} is a text node")
      case None => fail(s"parent of ${show(id)} does not exist")
    }

  private def element(tree: Tree, id: List[Int], change: Change): Element = tree.get(id) match {
    case Some(el: Element) => el
    case Some(_: Text) => fail(s"$change targets a text node")
    case None => fail(s"$change targets a missing node")
  }

  private def dropSubtree(tree: Tree, id: List[Int]): Tree =
    tree.filterNot { case (k, _) => k.startsWith(id) }

  private def show(id: List[Int]): String = id.mkString("_")

  private def fail(message: String): Nothing =
    throw new AssertionError(s"ReferenceDom: $message")

  /** Human readable difference between two trees, empty when equal. */
  def describeDifference(expected: Tree, actual: Tree): String = {
    val keys = (expected.keySet ++ actual.keySet).toList.sorted(Ordering.Implicits.seqOrdering[List, Int])
    keys.flatMap { k =>
      (expected.get(k), actual.get(k)) match {
        case (e, a) if e == a => Nil
        case (e, a) => List(s"  ${show(k)}: expected ${e.getOrElse("<absent>")}, actual ${a.getOrElse("<absent>")}")
      }
    }.mkString("\n")
  }
}
