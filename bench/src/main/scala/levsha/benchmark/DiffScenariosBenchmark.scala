package avocet.benchmark

import avocet.{FastId, RenderContext, XmlNs}
import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.FastChangesPerformer
import avocet.impl.{Html5RenderContext, TextPrettyPrintingConfig}
import org.openjdk.jmh.annotations._

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
  * Workload-specific measurements for the diff engine, with a counting
  * performer so the numbers isolate the engine from change reporting.
  * Record results in bench/BASELINE.md and compare after engine changes:
  *
  * {{{
  * nix develop . --no-write-lock-file --command sbt \
  *   'bench/Jmh/run -wi 5 -i 5 -f 3 -prof gc .*DiffScenariosBenchmark.*'
  * }}}
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
class DiffScenariosBenchmark {

  import DiffScenariosBenchmark._

  @Param(Array("2000"))
  var nodes: Int = _

  var unchanged: DiffRenderContext[Nothing] = _
  var textChange: DiffRenderContext[Nothing] = _
  var attrChange: DiffRenderContext[Nothing] = _
  var deepCtx: DiffRenderContext[Nothing] = _
  var manyAttrsCtx: DiffRenderContext[Nothing] = _
  var longNamesCtx: DiffRenderContext[Nothing] = _
  var collidingCtx: DiffRenderContext[Nothing] = _
  var churnCtx: DiffRenderContext[Nothing] = _
  var renderCtx: DiffRenderContext[Nothing] = _
  val performer = new CountingPerformer

  @Setup(Level.Trial)
  def setup(): Unit = {
    unchanged = withBaseline(wide(_, nodes))
    textChange = withBaseline(wide(_, nodes))
    attrChange = withBaseline(wide(_, nodes))
    deepCtx = withBaseline(deep(_, 200))
    manyAttrsCtx = withBaseline(manyAttrs(_, 64))
    longNamesCtx = withBaseline(longNames(_, nodes))
    collidingCtx = withBaseline(colliding(_, nodes, "t86388"))
    churnCtx = withBaseline(wide(_, 20))
    renderCtx = DiffRenderContext[Nothing]()
  }

  @Benchmark def renderOnly(): Int = {
    renderCtx.reset()
    wide(renderCtx, nodes)
    renderCtx.finalizeDocument()
    renderCtx.swap()
    nodes
  }

  @Benchmark def unchangedTree(): Int = cycle(unchanged, performer)(wide(_, nodes))

  @Benchmark def singleTextChange(): Int = {
    tick += 1
    cycle(textChange, performer)(wide(_, nodes, changedText = tick % nodes))
  }

  @Benchmark def singleAttrChange(): Int = {
    tick += 1
    cycle(attrChange, performer)(wide(_, nodes, changedAttr = tick % nodes))
  }

  @Benchmark def deepUnchanged(): Int = cycle(deepCtx, performer)(deep(_, 200))

  @Benchmark def manyAttrsOneChange(): Int = {
    tick += 1
    cycle(manyAttrsCtx, performer)(manyAttrs(_, 64, tick % 64))
  }

  @Benchmark def longNamesUnchanged(): Int = cycle(longNamesCtx, performer)(longNames(_, nodes))

  @Benchmark def collidingValuesAllChange(): Int = {
    tick += 1
    cycle(collidingCtx, performer)(colliding(_, nodes, if (tick % 2 == 0) "t86388" else "t118091"))
  }

  @Benchmark def growThenShrink(): Int = {
    cycle(churnCtx, performer)(wide(_, nodes))
    cycle(churnCtx, performer)(wide(_, 20))
  }

  @Benchmark def staticHtml5(): Int = {
    val rc = new Html5RenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
    wide(rc, nodes)
    rc.mkString.length
  }

  var tick: Int = 0
}

object DiffScenariosBenchmark {

  def wide(rc: RenderContext[Nothing], n: Int, changedText: Int = -1, changedAttr: Int = -1): Unit = {
    rc.openNode(XmlNs.html, "div")
    var i = 0
    while (i < n) {
      rc.openNode(XmlNs.html, "div")
      rc.setAttr(XmlNs.html, "class", if (i == changedAttr) "changed" else "item")
      rc.addTextNode(if (i == changedText) "changed" else "text")
      rc.closeNode("div")
      i += 1
    }
    rc.closeNode("div")
  }

  def deep(rc: RenderContext[Nothing], depth: Int): Unit = {
    var i = 0
    while (i < depth) { rc.openNode(XmlNs.html, "div"); rc.setAttr(XmlNs.html, "class", "level"); i += 1 }
    rc.addTextNode("leaf")
    i = 0
    while (i < depth) { rc.closeNode("div"); i += 1 }
  }

  val attrNames: Array[String] = Array.tabulate(64)(i => s"data-attribute-$i")

  def manyAttrs(rc: RenderContext[Nothing], n: Int, changed: Int = -1): Unit = {
    rc.openNode(XmlNs.html, "div")
    var i = 0
    while (i < n) { rc.setAttr(XmlNs.html, attrNames(i), if (i == changed) "changed" else "value"); i += 1 }
    rc.closeNode("div")
  }

  def longNames(rc: RenderContext[Nothing], n: Int): Unit = {
    rc.openNode(XmlNs.html, "my-custom-element-with-a-rather-long-name")
    var i = 0
    while (i < n) {
      rc.openNode(XmlNs.html, "figcaption")
      rc.setAttr(XmlNs.html, "data-abcdefghijklmnopqrstuvwx", "x")
      rc.setStyle("-webkit-border-bottom-left-radius", "1px")
      rc.closeNode("figcaption")
      i += 1
    }
    rc.closeNode("my-custom-element-with-a-rather-long-name")
  }

  /** Every text node carries a value from a colliding pair; alternating the pair changes them all. */
  def colliding(rc: RenderContext[Nothing], n: Int, text: String): Unit = {
    rc.openNode(XmlNs.html, "div")
    var i = 0
    while (i < n) { rc.openNode(XmlNs.html, "p"); rc.addTextNode(text); rc.closeNode("p"); i += 1 }
    rc.closeNode("div")
  }

  def withBaseline(baseline: RenderContext[Nothing] => Unit): DiffRenderContext[Nothing] = {
    val rc = DiffRenderContext[Nothing]()
    baseline(rc)
    rc.finalizeDocument()
    rc.swap()
    rc
  }

  def cycle(rc: DiffRenderContext[Nothing], performer: CountingPerformer)(next: RenderContext[Nothing] => Unit): Int = {
    performer.count = 0
    next(rc)
    rc.finalizeDocument()
    rc.diff(performer)
    rc.swap()
    performer.count
  }

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
}
