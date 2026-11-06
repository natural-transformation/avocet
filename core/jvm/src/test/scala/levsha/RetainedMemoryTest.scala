package avocet

import avocet.impl.{DiffRenderContext, TrimPolicy}
import utest._

import java.lang.ref.WeakReference

/**
  * JVM retained-memory evidence for M2 (contracts §5). Deterministic counts
  * (`bufferCapacity`, live roots) carry the assertions; heap samples are
  * printed as evidence and only checked against a wide bound, because a
  * forced GC is not a measurement instrument.
  */
object RetainedMemoryTest extends TestSuite {

  import PerfFixtures._

  private def heapUsed(): Long = {
    val rt = Runtime.getRuntime
    // Several collections, then the smallest of a few samples.
    (0 until 3).map { _ => System.gc(); Thread.sleep(20); rt.totalMemory() - rt.freeMemory() }.min
  }

  val tests = Tests {

    "renderer-owned storage per root is the buffer capacity, independent of application data" - {
      val n = 2000
      // Thread-local allocation accounting is deterministic even while other suites run in parallel;
      // everything a root allocates to build and render is an upper bound on what it retains.
      val threadMx = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
      def allocatedForOneRoot(): (Long, Int) = {
        val id = Thread.currentThread.threadId()
        val before = threadMx.getThreadAllocatedBytes(id)
        val root = RenderRoot[Nothing](new CountingPerformer, initialBufferSize = 1 << 19)
        root.renderWith(rc => wide(rc, n))
        val allocated = threadMx.getThreadAllocatedBytes(id) - before
        val capacity = root.bufferCapacity
        root.dispose()
        (allocated, capacity)
      }
      (0 until 20).foreach(_ => allocatedForOneRoot())
      val (allocated, capacity) = (0 until 5).map(_ => allocatedForOneRoot()).minBy(_._1)
      val heapBefore = heapUsed()
      val roots = (0 until 50).map { _ =>
        val root = RenderRoot[Nothing](new CountingPerformer, initialBufferSize = 1 << 19)
        root.renderWith(rc => wide(rc, n))
        root
      }
      val heapPerRoot = (heapUsed() - heapBefore) / roots.size
      println(s"[retained] one root x $n nodes: bufferCapacity=$capacity, allocated to build and render=$allocated bytes; " +
        s"50 roots heap delta=$heapPerRoot bytes/root (GC sample, parallel suites add noise)")
      assert(roots.forall(_.bufferCapacity == capacity))
      // The buffers are the renderer-owned storage; everything else a root allocates is small and bounded.
      assert(allocated < capacity * 1.25)
      roots.foreach(_.dispose())
    }

    "dispose drops the only strong reference to the buffers" - {
      val root = RenderRoot[Nothing](new CountingPerformer)
      root.renderWith(rc => wide(rc, 5000))
      val weak = new WeakReference(root)
      root.dispose()
      // The root object itself can only go once the test drops it; the buffers went with `dispose`.
      assert(root.state == RenderRoot.State.Disposed)
      assert(weak.get() != null)
    }

    "repeated mount and unmount: disposed roots become collectable and nothing holds them" - {
      // Deterministic form of "no strong roots remain after disposal": weak references to disposed
      // roots and their performers must clear once a collection runs. Heap deltas are printed only;
      // other suites run in parallel and make them noisy.
      val weak = (0 until 200).map { _ =>
        val performer = new CountingPerformer
        val root = RenderRoot[Nothing](performer, initialBufferSize = 1 << 16)
        root.renderWith(rc => wide(rc, 1000))
        root.renderWith(rc => wide(rc, 1000, changed = 1))
        root.dispose()
        (new WeakReference(root), new WeakReference(performer))
      }
      val before = heapUsed()
      val cleared = weak.count { case (r, p) => r.get() == null && p.get() == null }
      println(s"[retained] 200 mount/unmount cycles: $cleared/200 disposed roots collected; heap after=$before bytes (GC sample)")
      assert(cleared >= 190)
    }

    "large-to-small: manual trim and the automatic policy release the peak buffer" - {
      val manual = DiffRenderContext[Nothing]()
      val auto = DiffRenderContext[Nothing](trimPolicy = TrimPolicy.Automatic(afterSwaps = 4, slack = 4))
      val performer = new CountingPerformer
      Seq(manual, auto).foreach { rc => cycle(rc, performer)(wide(_, 20)); cycle(rc, performer)(wide(_, 30000)) }
      val peak = manual.bufferCapacity
      Seq(manual, auto).foreach { rc => (0 until 6).foreach(_ => cycle(rc, performer)(wide(_, 20))) }
      val autoAfter = auto.bufferCapacity
      val manualBefore = manual.bufferCapacity
      manual.trim()
      val manualAfter = manual.bufferCapacity
      println(s"[retained] peak=$peak manual(before trim)=$manualBefore manual(after)=$manualAfter automatic=$autoAfter")
      assert(manualBefore == peak && manualAfter < peak / 8 && autoAfter < peak / 8)
    }

    "many independent roots: capacity is bounded by the largest tree each root saw" - {
      val roots = (0 until 20).map { i =>
        val root = RenderRoot[Nothing](new CountingPerformer, initialBufferSize = 1024)
        root.renderWith(rc => wide(rc, 10 * (i + 1)))
        root
      }
      val caps = roots.map(_.bufferCapacity)
      println(s"[retained] 20 roots, 10..200 nodes: capacities=${caps.mkString(",")}")
      assert(caps.zip(caps.tail).forall { case (a, b) => a <= b })
      roots.foreach(_.dispose())
    }
  }
}
