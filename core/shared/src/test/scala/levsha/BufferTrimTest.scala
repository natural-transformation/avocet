package avocet

import avocet.impl.{DiffRenderContext, OwnedChange, OwnedChangesPerformer, TrimPolicy}
import avocet.impl.DiffRenderContext.FastChangesPerformer
import utest._

/**
  * Buffer retention policy: manual `trim()` and the automatic policy with
  * hysteresis. Sizes are deterministic (`bufferCapacity`), so these are
  * exact checks, not memory samples.
  */
object BufferTrimTest extends TestSuite {

  import PerfFixtures.{CountingPerformer, wide}

  val Initial = 1024

  /** One render cycle with any performer; returns the counting performer's count when it is one. */
  def cycle(rc: DiffRenderContext[Nothing], performer: FastChangesPerformer)(next: RenderContext[Nothing] => Unit): Int = {
    performer match { case c: CountingPerformer => c.count = 0; case _ => () }
    next(rc); rc.finalizeDocument(); rc.diff(performer); rc.swap()
    performer match { case c: CountingPerformer => c.count; case _ => -1 }
  }

  def grown(policy: TrimPolicy = TrimPolicy.Manual): (DiffRenderContext[Nothing], CountingPerformer) = {
    val rc = DiffRenderContext[Nothing](initialBufferSize = Initial, trimPolicy = policy)
    val performer = new CountingPerformer
    cycle(rc, performer)(wide(_, 20))
    cycle(rc, performer)(wide(_, 5000))
    (rc, performer)
  }

  val tests = Tests {

    "manual trim shrinks to fit the baseline and the next diff is still exact" - {
      val (rc, performer) = grown()
      val peak = rc.bufferCapacity
      assert(peak > Initial * 16)
      cycle(rc, performer)(wide(_, 20))
      assert(rc.bufferCapacity == peak)
      assert(rc.trim())
      val trimmed = rc.bufferCapacity
      assert(trimmed < peak && trimmed >= rc.baselineSize * 4 && trimmed >= Initial)
      assert(!rc.trim())
      // The copied baseline still diffs exactly: one changed text, nothing else.
      val changes = cycle(rc, performer)(wide(_, 20, changed = 3))
      assert(changes == 1)
      val buffer = new OwnedChangesPerformer.Buffer
      cycle(rc, buffer)(wide(_, 20))
      assert(buffer.drain() == Vector(OwnedChange.CreateText(Id("1_4_1"), "text")))
    }

    "trim refuses a document under construction or a finalized unpublished one" - {
      val (rc, _) = grown()
      rc.openNode(XmlNs.html, "div")
      assert(intercept[RenderException](rc.trim()).code == RenderException.Code.InvalidState)
      rc.closeNode("div")
      rc.finalizeDocument()
      assert(intercept[RenderException](rc.trim()).code == RenderException.Code.InvalidState)
      // Once the small document is published it is the baseline, and the peak buffer can go.
      rc.swap()
      assert(rc.trim())
    }

    "automatic policy shrinks after the configured number of small publishes" - {
      val (rc, performer) = grown(TrimPolicy.Automatic(afterSwaps = 3, slack = 4))
      val peak = rc.bufferCapacity
      cycle(rc, performer)(wide(_, 20)); cycle(rc, performer)(wide(_, 20))
      assert(rc.bufferCapacity == peak)
      cycle(rc, performer)(wide(_, 20))
      assert(rc.bufferCapacity < peak)
      // One correct diff after the automatic trim.
      assert(cycle(rc, performer)(wide(_, 20, changed = 1)) == 1)
    }

    "a large render between small ones resets the hysteresis" - {
      val (rc, performer) = grown(TrimPolicy.Automatic(afterSwaps = 3, slack = 4))
      val peak = rc.bufferCapacity
      cycle(rc, performer)(wide(_, 20)); cycle(rc, performer)(wide(_, 20))
      cycle(rc, performer)(wide(_, 5000))
      cycle(rc, performer)(wide(_, 20)); cycle(rc, performer)(wide(_, 20))
      assert(rc.bufferCapacity == peak)
    }

    "the automatic policy never shrinks below the initial size or the baseline" - {
      val rc = DiffRenderContext[Nothing](initialBufferSize = Initial, trimPolicy = TrimPolicy.Automatic(afterSwaps = 1, slack = 2))
      val performer = new CountingPerformer
      (0 until 5).foreach(_ => cycle(rc, performer)(wide(_, 1)))
      assert(rc.bufferCapacity == Initial)
      cycle(rc, performer)(wide(_, 200))
      assert(rc.bufferCapacity >= rc.baselineSize * 2)
    }

    "invalid policy arguments are rejected" - {
      assert(intercept[RenderException](TrimPolicy.Automatic(afterSwaps = 0)).code == RenderException.Code.InvalidArgument)
      assert(intercept[RenderException](TrimPolicy.Automatic(slack = 1)).code == RenderException.Code.InvalidArgument)
    }

    "RenderRoot exposes capacity and trim" - {
      import avocet.dsl._
      import avocet.dsl.html._
      val root = RenderRoot[Nothing](new CountingPerformer, initialBufferSize = Initial)
      root.render(div(each(0 until 5000)(i => p(i.toString))))
      val peak = root.bufferCapacity
      root.render(div("small"))
      assert(root.bufferCapacity == peak)
      assert(root.trim())
      assert(root.bufferCapacity < peak)
      assert(root.render(div("small")).isPublished)
    }
  }
}
