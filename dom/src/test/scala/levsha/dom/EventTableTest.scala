package avocet.dom

import avocet.Id
import avocet.events.EventPhase.{AtTarget, Bubbling, Capturing}
import utest._

import scala.collection.mutable

/** Node-only checks of handler composition and simulated propagation (no browser DOM). */
object EventTableTest extends TestSuite {

  def ev(id: String, tpe: String, phase: avocet.events.EventPhase, log: mutable.Buffer[String], name: String, result: Any = ()) =
    Id(id) -> Misc.Event(tpe, phase, () => { log += name; result })

  val tests = Tests {
    "two handlers on one node and event keep both" - {
      val log = mutable.Buffer.empty[String]
      val handlers = EventTable.build(Seq(ev("1_1", "click", Bubbling, log, "a", true), ev("1_1", "click", Bubbling, log, "b", true)))
      assert(handlers.size == 1)
      assert(handlers.values.head.size == 2)
      EventTable.dispatch(handlers, Id("1_1"), "click")
      assert(log.toList == List("a", "b"))
    }
    "capture, target and bubble order" - {
      val log = mutable.Buffer.empty[String]
      val handlers = EventTable.build(Seq(
        ev("1", "click", Capturing, log, "root-capture", true),
        ev("1", "click", Bubbling, log, "root-bubble", true),
        ev("1_2", "click", Capturing, log, "mid-capture", true),
        ev("1_2", "click", Bubbling, log, "mid-bubble", true),
        ev("1_2_3", "click", AtTarget, log, "target", true),
        ev("1_2_3", "click", Bubbling, log, "target-bubble", true)))
      val completed = EventTable.dispatch(handlers, Id("1_2_3"), "click")
      assert(completed)
      assert(log.toList == List("root-capture", "mid-capture", "target", "target-bubble", "mid-bubble", "root-bubble"))
    }
    "a handler that does not return true stops propagation after its key" - {
      val log = mutable.Buffer.empty[String]
      val handlers = EventTable.build(Seq(
        ev("1", "click", Bubbling, log, "root", true),
        ev("1_1", "click", Bubbling, log, "stop", ()),
        ev("1_1", "click", Bubbling, log, "sibling-handler", true)))
      val completed = EventTable.dispatch(handlers, Id("1_1"), "click")
      assert(!completed)
      assert(log.toList == List("stop", "sibling-handler"))
    }
    "other event types and nodes are untouched" - {
      val log = mutable.Buffer.empty[String]
      val handlers = EventTable.build(Seq(ev("1_1", "click", Bubbling, log, "click"), ev("1_2", "focus", Bubbling, log, "focus")))
      EventTable.dispatch(handlers, Id("1_2"), "click")
      assert(log.isEmpty)
      assert(EventTable.eventTypes(handlers) == Set("click", "focus"))
    }
  }
}
