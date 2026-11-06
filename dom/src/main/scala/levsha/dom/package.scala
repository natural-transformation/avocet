/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package avocet

import avocet.events.EventPhase
import org.scalajs.dom.Element
import org.scalajs.{dom => browser}

import scala.collection.mutable
import scala.scalajs.js

/**
  * Scala.js DOM backend, built on [[RenderRoot]].
  *
  * Lifecycle of one root (`render(target)`):
  *
  *  1. the template is rendered into a fresh buffer and finalized; event
  *     handlers are staged, not published;
  *  2. the diff is applied to the DOM while the root is `Rendering`;
  *  3. on success the new baseline and handler table are committed together;
  *  4. if the template throws (`RenderOutcome.Rejected`), the previous
  *     baseline and handlers stay in force and the exception is rethrown;
  *  5. if a DOM write fails (`RenderOutcome.Invalidated`), the root is
  *     invalid: DOM writes cannot be rolled back, so further renders are
  *     refused with `RenderException(INVALID_STATE)` until `dispose(target)`.
  *
  * Events that arrive while a diff is being applied (for example from a
  * custom element callback) are dropped with a console warning, and a render
  * started from such a callback is rejected. Native listeners are registered
  * on the root in the capture phase so non-bubbling events such as `focus`
  * and `blur` are delivered too; propagation between handlers is simulated
  * from the target id (see [[EventTable]]). `submit` events are prevented by
  * default, as before.
  *
  * The template root (id 1) is the target element itself: its attributes and
  * styles are applied to `target`, its children become `target`'s children, and
  * its tag name is not checked. A root "replacement" empties the target; it
  * does not remove attributes set earlier.
  *
  * `dispose(target)` removes the native listeners, the nodes Avocet created,
  * the handler table, the node index and the render buffers, and forgets the
  * root. It is idempotent, and `render(target)` afterwards starts from scratch.
  *
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
package object dom {

  import RenderRoot.State

  private final class Root(target: Element, validation: Validation) {

    private val performer = new DomChangesPerformer(target)
    private val root = RenderRoot[Misc](performer, validation)
    private var handlers: EventTable.Handlers = EventTable.empty
    private val nativeListeners = mutable.Map.empty[String, js.Function1[browser.Event, Unit]]

    private val nativeEventHandler: js.Function1[browser.Event, Unit] = { (nativeEvent: browser.Event) =>
      root.state match {
        case State.Idle =>
          if (nativeEvent.`type` == "submit") nativeEvent.preventDefault()
          targetIdOf(nativeEvent).foreach { targetId =>
            EventTable.dispatch(handlers, targetId, nativeEvent.`type`)
          }
        case State.Rendering =>
          browser.console.warn(s"avocet.dom: ${nativeEvent.`type`} event dropped while changes are being applied")
        case State.Invalid | State.Disposed => ()
      }
    }

    private def targetIdOf(nativeEvent: browser.Event): Option[Id] =
      nativeEvent.target.asInstanceOf[js.Dynamic].vid.asInstanceOf[Any] match {
        case vid: String => Some(Id(vid))
        case _ => None
      }

    def render(node: Document.Node[Misc]): Unit =
      // Invalid, disposed and reentrant renders are refused by the root with INVALID_STATE.
      root.render(node) match {
        case RenderOutcome.Published(_) =>
          handlers = EventTable.build(root.misc)
          EventTable.eventTypes(handlers).foreach(registerNativeListener)
        case RenderOutcome.Rejected(cause) => throw cause
        case RenderOutcome.Invalidated(cause) => throw cause
      }

    private def registerNativeListener(tpe: String): Unit =
      if (!nativeListeners.contains(tpe)) {
        nativeListeners.update(tpe, nativeEventHandler)
        target.addEventListener(tpe, nativeEventHandler, useCapture = true)
      }

    def dispose(): Unit = if (root.state != State.Disposed) {
      nativeListeners.foreach { case (tpe, listener) =>
        target.removeEventListener(tpe, listener, useCapture = true)
      }
      nativeListeners.clear()
      handlers = EventTable.empty
      performer.dispose()
      root.dispose()
    }

    def handlerCount: Int = handlers.valuesIterator.map(_.size).sum
    def indexSize: Int = performer.indexSize
    def isFailed: Boolean = root.state == State.Invalid
    def generation: Int = root.generation
  }

  private val roots = mutable.Map.empty[Element, Root]

  /** Renders `node` into `target`, diffing against the previous render of the same target. */
  def render(target: Element)(node: Document.Node[Misc]): Unit =
    render(target, Validation.off)(node)

  /**
    * As [[render]], with opt-in validation. The validation setting of the
    * first render of a target is the one in force until `dispose(target)`.
    */
  def render(target: Element, validation: Validation)(node: Document.Node[Misc]): Unit = {
    val root = roots.getOrElseUpdate(target, new Root(target, validation))
    root.render(node)
  }

  /** Releases everything Avocet holds for `target`. Safe to call twice. */
  def dispose(target: Element): Unit =
    roots.remove(target).foreach(_.dispose())

  def event(`type`: String, phase: EventPhase = EventPhase.Bubbling)
           (callback: => Any): Misc.Event = {
    Misc.Event(`type`, phase, () => callback)
  }

  // Test seams for fixtures in sub-packages.
  private[dom] def debugHandlerCount(target: Element): Int = roots.get(target).fold(0)(_.handlerCount)
  private[dom] def debugIndexSize(target: Element): Int = roots.get(target).fold(0)(_.indexSize)
  private[dom] def debugIsMounted(target: Element): Boolean = roots.contains(target)
  private[dom] def debugIsFailed(target: Element): Boolean = roots.get(target).exists(_.isFailed)
  private[dom] def debugGeneration(target: Element): Int = roots.get(target).fold(0)(_.generation)
}
