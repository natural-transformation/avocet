package avocet

import avocet.impl.DiffRenderContext
import java.lang.management.ManagementFactory
import utest._

/**
  * JVM-only allocation guard for the "no object tree per render" claim.
  * Measures bytes allocated by this thread per render+diff cycle after
  * warm-up. The engine writes into retained buffers and compares bytes, so a
  * steady-state cycle should allocate (almost) nothing; the limit leaves room
  * for JIT noise but not for per-node allocation.
  */
object AllocationGuardTest extends TestSuite {

  import PerfFixtures._
  import avocet.dsl._
  import avocet.dsl.html._

  private val threadMx = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def allocatedPerCycle(cycles: Int)(body: => Unit): Long = {
    val id = Thread.currentThread.threadId()
    val before = threadMx.getThreadAllocatedBytes(id)
    var i = 0
    while (i < cycles) { body; i += 1 }
    (threadMx.getThreadAllocatedBytes(id) - before) / cycles
  }

  private def minOver(samples: Int, cycles: Int)(body: => Unit): Long =
    (0 until samples).map(_ => allocatedPerCycle(cycles)(body)).min

  val tests = Tests {
    "raw interpreter: render + diff of a 1000-node tree allocates less than 1 KB per cycle" - {
      val n = 1000
      val rc = withBaseline(wide(_, n))
      val performer = new CountingPerformer
      var k = 0
      (0 until 500).foreach(_ => { cycle(rc, performer)(wide(_, n, changed = k % n)); k += 1 })
      val bytes = minOver(5, 100) { cycle(rc, performer)(wide(_, n, changed = k % n)); k += 1 }
      assert(bytes < 1024)
    }
    "optimized DSL: a static template cycle allocates less than 2 KB" - {
      val template = optimize {
        div(clazz := "page", width @= "100px",
          h1("Title"),
          ul(clazz := "list", li("one"), li("two"), li(clazz := "last", "three")),
          p(lang := "en", "paragraph ", span("inline"), " tail"),
          input(`type` := "text", placeholder := "name"),
          button(disabled, "ok"))
      }
      val rc = DiffRenderContext[Nothing]()
      template(rc); rc.finalizeDocument(); rc.swap()
      val performer = new CountingPerformer
      def once(): Unit = { template(rc); rc.finalizeDocument(); rc.diff(performer); rc.swap() }
      (0 until 2000).foreach(_ => once())
      val bytes = minOver(5, 200)(once())
      assert(performer.count == 0)
      assert(bytes < 2048)
    }
    "RenderRoot: a static template cycle through the lifecycle API allocates less than 2 KB" - {
      val template = optimize {
        div(clazz := "page", h1("Title"), ul(li("one"), li("two")), p("paragraph ", span("inline")))
      }
      val performer = new CountingPerformer
      val root = RenderRoot[Nothing](performer)
      def once(): Unit = assert(root.render(template).isPublished)
      (0 until 2000).foreach(_ => once())
      performer.count = 0
      val bytes = minOver(5, 200)(once())
      assert(performer.count == 0)
      assert(bytes < 2048)
    }
    "validation on: the same cycle through the validating decorator stays under 2 KB (measured cost of validation)" - {
      val template = optimize {
        div(clazz := "page", h1("Title"), ul(li("one"), li("two")), p("paragraph ", span("inline")))
      }
      val performer = new CountingPerformer
      val root = RenderRoot[Nothing](performer, Validation.interactiveHtml)
      def once(): Unit = assert(root.render(template).isPublished)
      (0 until 2000).foreach(_ => once())
      performer.count = 0
      val bytes = minOver(5, 200)(once())
      assert(performer.count == 0)
      assert(bytes < 2048)
    }
    "typed keys, fragment and each in an optimized template allocate only their encoded strings" - {
      val tabIndex = AttrKey.int("tabindex")
      val disabledKey = AttrKey.boolean("disabled")
      val widthPx = StyleKey.px("width")
      val items = List("a", "b", "c")
      val template = optimize {
        div(tabIndex := 1, disabledKey := true, widthPx @= 10, fragment(span("x"), noNode), each(items)(i => li(i)))
      }
      val rc = DiffRenderContext[Nothing]()
      template(rc); rc.finalizeDocument(); rc.swap()
      val performer = new CountingPerformer
      def once(): Unit = { template(rc); rc.finalizeDocument(); rc.diff(performer); rc.swap() }
      (0 until 2000).foreach(_ => once())
      val bytes = minOver(5, 200)(once())
      assert(performer.count == 0)
      // Two encoded strings ("1", "10px") and one list iterator per render; no Document wrappers.
      assert(bytes < 512)
    }
  }
}
