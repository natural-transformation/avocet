package avocet

import scala.collection.mutable

/**
  * Test-only render context that records every operation in order.
  * Used to compare optimized and plain templates and to assert operation order.
  */
final class OpRecorder[M] extends RenderContext[M] {
  val ops: mutable.Buffer[String] = mutable.Buffer.empty[String]
  def openNode(xmlns: XmlNs, name: String): Unit = ops += s"open(${xmlns.shortcut}:$name)"
  def closeNode(name: String): Unit = ops += s"close($name)"
  def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = ops += s"attr(${xmlNs.shortcut}:$name=$value)"
  def setStyle(name: String, value: String): Unit = ops += s"style($name=$value)"
  def addTextNode(text: String): Unit = ops += s"text($text)"
  def addMisc(misc: M): Unit = ops += s"misc($misc)"
}

object OpRecorder {
  def record[M](doc: Document[M]): Seq[String] = {
    val rc = new OpRecorder[M]
    doc(rc)
    rc.ops.toVector
  }
}
