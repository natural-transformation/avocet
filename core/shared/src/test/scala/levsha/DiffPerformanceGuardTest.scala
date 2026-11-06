package avocet

import avocet.impl.{DiffRenderContext, XhtmlRenderContext, TextPrettyPrintingConfig}
import utest._

/**
  * Performance regression guards. They compare the engine against itself
  * (scaling ratios and relative costs) so they hold on any machine and on
  * both platforms. Thresholds are wide: an algorithmic regression (quadratic
  * where linear is expected, or a per-cycle cost proportional to the buffer
  * size) trips them; ordinary noise does not. Absolute numbers come from the
  * JMH benchmarks in `bench`, not from here.
  */
object DiffPerformanceGuardTest extends TestSuite {

  import PerfFixtures._

  val Runs = 7

  val tests = Tests {

    "unchanged tree: diff cost scales linearly with tree size" - {
      val small = 2000
      val large = small * 8
      val rcSmall = withBaseline(wide(_, small))
      val rcLarge = withBaseline(wide(_, large))
      val performer = new CountingPerformer
      // Warm up both paths before measuring.
      cycle(rcSmall, performer)(wide(_, small)); cycle(rcLarge, performer)(wide(_, large))
      val smallTime = timeMin(Runs) { var i = 0; while (i < 8) { cycle(rcSmall, performer)(wide(_, small)); i += 1 } }
      val largeTime = timeMin(Runs)(cycle(rcLarge, performer)(wide(_, large)))
      val ratio = largeTime.toDouble / smallTime
      assert(performer.count == 0)
      // Linear: ratio about 1. Quadratic: about 8.
      assert(ratio < 3.5)
    }

    "single change in a wide tree: diff is not more expensive than the render" - {
      val n = 8000
      val rc = withBaseline(wide(_, n))
      val performer = new CountingPerformer
      cycle(rc, performer)(wide(_, n, changed = n / 2))
      // Render only (no diff) versus render + diff; measured on the same context.
      val renderTime = timeMin(Runs) { rc.reset(); wide(rc, n, changed = n / 2); rc.finalizeDocument() }
      val cycleTime = timeMin(Runs) { rc.reset(); cycle(rc, performer)(wide(_, n, changed = n / 2)) }
      // Sanity: the baseline now has one changed text; rendering the plain tree reverts exactly it.
      cycle(rc, performer)(wide(_, n, changed = n / 2))
      val changes = cycle(rc, performer)(wide(_, n))
      assert(changes == 1)
      // The diff reads what the render wrote once; it must stay cheaper than three extra renders.
      assert(cycleTime < renderTime * 4)
    }

    "deep tree: nesting to the limit does not change the per-node cost class" - {
      val performer = new CountingPerformer
      val rcShallow = withBaseline(deep(_, 25))
      val rcDeep = withBaseline(deep(_, 250))
      cycle(rcShallow, performer)(deep(_, 25)); cycle(rcDeep, performer)(deep(_, 250))
      val shallow = timeMin(Runs) { var i = 0; while (i < 10) { cycle(rcShallow, performer)(deep(_, 25)); i += 1 } }
      val deepTime = timeMin(Runs)(cycle(rcDeep, performer)(deep(_, 250)))
      assert(deepTime.toDouble / shallow < 3.5)
    }

    "many attributes: one changed value stays within the documented quadratic bound" - {
      val performer = new CountingPerformer
      val rcSmall = withBaseline(manyAttrs(_, 64))
      val rcLarge = withBaseline(manyAttrs(_, 256))
      // Alternate the changed attribute so every cycle sets one value and reverts another.
      var tick = 0
      def next(n: Int)(rc: RenderContext[Nothing]): Unit = { tick += 1; manyAttrs(rc, n, 1 + tick % 2) }
      cycle(rcSmall, performer)(next(64)); cycle(rcLarge, performer)(next(256))
      val small = timeMin(Runs)(cycle(rcSmall, performer)(next(64)))
      val large = timeMin(Runs)(cycle(rcLarge, performer)(next(256)))
      assert(performer.count == 2)
      // 4x attributes: quadratic gives 16x, cubic 64x.
      assert(large.toDouble / small < 40)
    }

    "growth then shrink: small cycles after a large render cost the same as on a fresh context" - {
      val performer = new CountingPerformer
      def smallCycles(rc: DiffRenderContext[Nothing]): Unit = { var i = 0; while (i < 200) { cycle(rc, performer)(wide(_, 20, changed = i % 20)); i += 1 } }
      val fresh = withBaseline(wide(_, 20))
      val grown = withBaseline(wide(_, 20))
      cycle(grown, performer)(wide(_, 30000))
      cycle(grown, performer)(wide(_, 20))
      smallCycles(fresh); smallCycles(grown)
      val freshTime = timeMin(Runs)(smallCycles(fresh))
      val grownTime = timeMin(Runs)(smallCycles(grown))
      // A per-cycle cost proportional to the retained buffer (for example zero-filling it) trips this.
      assert(grownTime.toDouble / freshTime < 4)
    }

    "SSR: serialization scales linearly" - {
      def render(n: Int): Int = {
        val rc = new XhtmlRenderContext[Nothing](TextPrettyPrintingConfig.noPrettyPrinting)
        wide(rc, n)
        rc.mkString.length
      }
      render(2000); render(16000)
      val small = timeMin(Runs) { var i = 0; while (i < 8) { render(2000); i += 1 } }
      val large = timeMin(Runs)(render(16000))
      assert(large.toDouble / small < 3.5)
    }
  }
}
