package avocet

import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.FastChangesPerformer

import java.nio.ByteBuffer

/**
  * Deterministic workloads for the performance guards. They call the
  * interpreter directly so the measured cost is the engine's, not the DSL's.
  */
object PerfFixtures {

  /** `n` sibling `div(class, text)` elements under one root; `changed` marks one text to differ. */
  def wide(rc: RenderContext[Nothing], n: Int, changed: Int = -1): Unit = {
    rc.openNode(XmlNs.html, "div")
    var i = 0
    while (i < n) {
      rc.openNode(XmlNs.html, "div")
      rc.setAttr(XmlNs.html, "class", "item")
      rc.addTextNode(if (i == changed) "changed" else "text")
      rc.closeNode("div")
      i += 1
    }
    rc.closeNode("div")
  }

  /** One chain of `depth` nested elements. */
  def deep(rc: RenderContext[Nothing], depth: Int): Unit = {
    var i = 0
    while (i < depth) { rc.openNode(XmlNs.html, "div"); rc.setAttr(XmlNs.html, "class", "level"); i += 1 }
    rc.addTextNode("leaf")
    i = 0
    while (i < depth) { rc.closeNode("div"); i += 1 }
  }

  /** One element with `n` attributes; `changed` marks one value to differ. */
  def manyAttrs(rc: RenderContext[Nothing], n: Int, changed: Int = -1): Unit = {
    rc.openNode(XmlNs.html, "div")
    var i = 0
    while (i < n) {
      rc.setAttr(XmlNs.html, attrNames(i), if (i == changed) "changed" else "value")
      i += 1
    }
    rc.closeNode("div")
  }

  val attrNames: Array[String] = Array.tabulate(512)(i => s"data-attribute-$i")

  /** Counts operations without decoding anything; keeps the JIT honest. */
  final class CountingPerformer extends FastChangesPerformer {
    var count: Int = 0
    def remove(id: FastId): Unit = count += 1
    def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = count += 1
    def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit = count += 1
    def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = count += 1
    def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit = count += 1
    def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = count += 1
    def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = count += 1
  }

  /** Context with `baseline` rendered and swapped in as the baseline. */
  def withBaseline(baseline: RenderContext[Nothing] => Unit): DiffRenderContext[Nothing] = {
    val rc = DiffRenderContext[Nothing]()
    baseline(rc)
    rc.finalizeDocument()
    rc.swap()
    rc
  }

  /** One render cycle: render `next`, finalize, diff, swap. Returns the number of emitted operations. */
  def cycle(rc: DiffRenderContext[Nothing], performer: CountingPerformer)(next: RenderContext[Nothing] => Unit): Int = {
    performer.count = 0
    next(rc)
    rc.finalizeDocument()
    rc.diff(performer)
    rc.swap()
    performer.count
  }

  /** Smallest wall-clock time of `runs` executions, in nanoseconds. */
  def timeMin(runs: Int)(body: => Unit): Long = {
    var best = Long.MaxValue
    var i = 0
    while (i < runs) {
      val start = System.nanoTime()
      body
      val elapsed = System.nanoTime() - start
      if (elapsed < best) best = elapsed
      i += 1
    }
    best
  }
}
