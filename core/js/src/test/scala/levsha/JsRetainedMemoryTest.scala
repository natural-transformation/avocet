package avocet

import avocet.impl.{DiffRenderContext, TrimPolicy}
import utest._

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global

/**
  * Scala.js retained-memory evidence (Node runtime). The deterministic
  * checks mirror the JVM suite; the V8 heap numbers are printed when the
  * runtime exposes them and compared only against a wide bound.
  */
object JsRetainedMemoryTest extends TestSuite {

  import PerfFixtures._

  private def heapUsed(): Double =
    if (js.typeOf(global.process) != "undefined" && js.typeOf(global.process.memoryUsage) != "undefined") {
      if (js.typeOf(global.gc) == "function") global.gc()
      global.process.memoryUsage().heapUsed.asInstanceOf[Double]
    } else -1.0

  val tests = Tests {

    "renderer-owned storage per root is the buffer capacity" - {
      val n = 2000
      val before = heapUsed()
      val roots = (0 until 20).map { _ =>
        val root = RenderRoot[Nothing](new CountingPerformer)
        root.renderWith(rc => wide(rc, n))
        root
      }
      val capacity = roots.head.bufferCapacity
      val after = heapUsed()
      val perRoot = if (before < 0) -1.0 else (after - before) / roots.size
      println(s"[retained-js] 20 roots x $n nodes: bufferCapacity=$capacity bytes/root, heap delta=$perRoot bytes/root (gc exposed: ${js.typeOf(global.gc) == "function"})")
      assert(roots.forall(_.bufferCapacity == capacity))
      roots.foreach(_.dispose())
    }

    "large-to-small: trim and the automatic policy release the peak buffer" - {
      val manual = DiffRenderContext[Nothing]()
      val auto = DiffRenderContext[Nothing](trimPolicy = TrimPolicy.Automatic(afterSwaps = 4, slack = 4))
      val performer = new CountingPerformer
      Seq(manual, auto).foreach { rc => cycle(rc, performer)(wide(_, 20)); cycle(rc, performer)(wide(_, 30000)) }
      val peak = manual.bufferCapacity
      Seq(manual, auto).foreach { rc => (0 until 6).foreach(_ => cycle(rc, performer)(wide(_, 20))) }
      val autoAfter = auto.bufferCapacity
      manual.trim()
      val manualAfter = manual.bufferCapacity
      println(s"[retained-js] peak=$peak manual(after trim)=$manualAfter automatic=$autoAfter")
      assert(manualAfter < peak / 8 && autoAfter < peak / 8)
    }

    "repeated mount and unmount leaves no growth beyond a wide bound" - {
      def mountCycle(): Unit = {
        val root = RenderRoot[Nothing](new CountingPerformer)
        root.renderWith(rc => wide(rc, 1000))
        root.renderWith(rc => wide(rc, 1000, changed = 1))
        root.dispose()
      }
      (0 until 20).foreach(_ => mountCycle())
      val before = heapUsed()
      (0 until 100).foreach(_ => mountCycle())
      val after = heapUsed()
      println(s"[retained-js] 100 mount/unmount cycles: heap delta=${after - before} bytes")
      // Without an exposed gc the delta includes garbage that V8 has not collected yet.
      if (js.typeOf(global.gc) == "function") assert(after - before < 4.0 * 1024 * 1024)
    }
  }
}
