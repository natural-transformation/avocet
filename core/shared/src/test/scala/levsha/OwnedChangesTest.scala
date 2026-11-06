package avocet

import avocet.impl.{DiffRenderContext, OwnedChange, OwnedChangesPerformer}
import avocet.impl.DiffRenderContext.FastChangesPerformer
import utest._

import java.nio.ByteBuffer
import scala.collection.mutable

/**
  * The fast performer borrows its arguments; the owned adapter copies them.
  * The first test shows what a consumer that retains borrowed references
  * sees after later renders (the detection check); the others show that
  * owned changes keep their values.
  */
object OwnedChangesTest extends TestSuite {

  import avocet.dsl._
  import avocet.dsl.html._

  def cycle(rc: DiffRenderContext[Nothing], performer: FastChangesPerformer)(doc: Document.Node[Nothing]): Unit = {
    doc(rc); rc.finalizeDocument(); rc.diff(performer); rc.swap()
  }

  val tests = Tests {

    "a retained borrowed FastId no longer holds the reported id once the diff returns (why the owned adapter exists)" - {
      val retained = mutable.Buffer.empty[FastId]
      val reported = mutable.Buffer.empty[String]
      val performer = new FastChangesPerformer {
        def remove(id: FastId): Unit = ()
        def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = { retained += id; reported += id.mkId.mkString }
        def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit = ()
        def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = ()
        def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = ()
        def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = ()
        def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = ()
      }
      val rc = DiffRenderContext[Nothing]()
      cycle(rc, performer)(div(span("a"), span("b"), span("c")))
      assert(reported.toList == List("1", "1_1", "1_2", "1_3"))
      // The builder is reset when the diff returns: the retained references all read as the root slot.
      val afterwards = retained.map(_.mkId.mkString).toList
      assert(afterwards != reported.toList)
      assert(afterwards.forall(_ == "0"))
    }

    "owned changes keep their ids, names and values across later renders" - {
      val buffer = new OwnedChangesPerformer.Buffer
      val rc = DiffRenderContext[Nothing]()
      cycle(rc, buffer)(div(clazz := "first", width @= "1px", span("a")))
      val first = buffer.drain()
      val expected = Vector(
        OwnedChange.Create(Id("1"), XmlNs.html, "div"),
        OwnedChange.SetStyle(Id("1"), "width", "1px"),
        OwnedChange.SetAttr(Id("1"), XmlNs.html, "class", "first"),
        OwnedChange.Create(Id("1_1"), XmlNs.html, "span"),
        OwnedChange.CreateText(Id("1_1_1"), "a"))
      assert(first == expected)
      cycle(rc, buffer)(section(clazz := "second", height @= "2px", p("b"), "c"))
      cycle(rc, buffer)(div(span("z")))
      assert(first == expected)
      assert(buffer.drain().nonEmpty)
    }

    "the sink form receives the same sequence and removal operations" - {
      val seen = mutable.Buffer.empty[OwnedChange]
      val performer = OwnedChangesPerformer(seen += _)
      val rc = DiffRenderContext[Nothing]()
      cycle(rc, performer)(div(clazz := "a", width @= "1px", span("x"), span("y")))
      seen.clear()
      cycle(rc, performer)(div(span("x")))
      assert(seen.toVector == Vector(
        OwnedChange.RemoveStyle(Id("1"), "width"),
        OwnedChange.RemoveAttr(Id("1"), XmlNs.html, "class"),
        OwnedChange.Remove(Id("1_2"))))
    }
  }
}
