package avocet.dom.fixture

import avocet.dsl._
import avocet.dsl.html._
import avocet.dom._

import scala.util.Try

import DomFixture.{check, fire, newContainer}

/**
  * Node identity (contracts §1.4): an event or a removal must resolve to a
  * node this root currently renders. The positional id an element carries is
  * only a hint; a removed node keeps it, and a node of another root carries
  * the same ids. Each case moves a node the way application or third-party
  * code could.
  */
object IdentityChecks {

  def run(): Unit = {
    removedNodePutBack()
    nodeFromAnotherRoot()
    staleNodeInRemovedSubtree()
  }

  private def removedNodePutBack(): Unit =
    check("a removed node put back under the root dispatches no handler") {
      val container = newContainer()
      var clicks = 0
      def view(label: String) =
        div(event("click")({ clicks += 1; true }), button(event("click")({ clicks += 1; true }), label))
      render(container)(view("old"))
      val old = container.querySelector("button")
      render(container)(div(event("click")({ clicks += 1; true }), "gone"))
      // A new button now holds the removed one's position, and so its id.
      render(container)(view("live"))
      container.appendChild(old)
      clicks = 0
      fire(old, "click", bubbles = true)
      val staleClicks = clicks
      fire(container.querySelector("button"), "click", bubbles = true)
      val liveClicks = clicks - staleClicks
      dispose(container)
      container.removeChild(old)
      (staleClicks == 0 && liveClicks == 2, s"staleClicks=$staleClicks liveClicks=$liveClicks")
    }

  private def nodeFromAnotherRoot(): Unit =
    check("a node moved in from another root dispatches neither root's handlers") {
      val first = newContainer()
      val second = newContainer()
      var firstClicks = 0
      var secondClicks = 0
      render(first)(div(event("click")({ firstClicks += 1; true }), button(event("click")({ firstClicks += 1; true }), "first")))
      render(second)(div(event("click")({ secondClicks += 1; true }), button(event("click")({ secondClicks += 1; true }), "second")))
      val moved = second.querySelector("button")
      first.appendChild(moved)
      fire(moved, "click", bubbles = true)
      dispose(first)
      dispose(second)
      first.removeChild(moved)
      (firstClicks == 0 && secondClicks == 0, s"firstClicks=$firstClicks secondClicks=$secondClicks")
    }

  private def staleNodeInRemovedSubtree(): Unit =
    check("removing a subtree that holds a removed node keeps the live index intact") {
      val container = newContainer()
      var clicks = 0
      def live(attrs: Boolean) =
        button(if (attrs) clazz := "x" else noAttr, event("click")({ clicks += 1; true }), "live")
      render(container)(div(p(button("old"))))
      val old = container.querySelector("button")
      render(container)(div(p("text")))
      render(container)(div(p(live(attrs = false)), section()))
      // The removed button, which still carries the live button's id, inside a node Avocet will remove.
      container.querySelector("section").appendChild(old)
      render(container)(div(p(live(attrs = false))))
      val indexed = debugIndexSize(container)
      val updated = Try(render(container)(div(p(live(attrs = true))))).isSuccess &&
        container.querySelector("button").getAttribute("class") == "x"
      clicks = 0
      fire(container.querySelector("button"), "click", bubbles = true)
      dispose(container)
      // Root, p, button and its text stay indexed after the section is removed.
      (indexed == 4 && updated && clicks == 1, s"indexed=$indexed updated=$updated clicks=$clicks")
    }
}
