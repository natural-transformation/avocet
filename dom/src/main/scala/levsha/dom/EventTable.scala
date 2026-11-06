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

package avocet.dom

import avocet.Id
import avocet.events.{EventId, calculateEventPropagation}

/**
  * Handler table of the DOM adapter. It has no browser dependency so the
  * composition and propagation rules can be unit-tested on Node.
  *
  * Keys are `(node id, event type, phase)`. Several handlers registered for
  * one key all run, in registration order; none is dropped. Propagation
  * (capturing ancestors, target, bubbling ancestors as computed by
  * `calculateEventPropagation`) continues past a key only when every
  * handler of that key returned `true`. Any other result stops it. This is
  * the legacy contract of the `event` helper; a typed outcome is M2 work.
  */
private[dom] object EventTable {

  type Handlers = Map[EventId, Vector[() => Any]]

  val empty: Handlers = Map.empty

  def build(misc: Iterable[(Id, Misc)]): Handlers =
    misc.foldLeft(empty) {
      case (acc, (id, Misc.Event(tpe, phase, callback))) =>
        val key = EventId(id, tpe, phase)
        acc.updated(key, acc.getOrElse(key, Vector.empty) :+ callback)
    }

  def eventTypes(handlers: Handlers): Set[String] =
    handlers.keysIterator.map(_.`type`).toSet

  /** Runs the handlers along the propagation path. Returns true when propagation reached the end. */
  def dispatch(handlers: Handlers, target: Id, tpe: String): Boolean =
    calculateEventPropagation(target, tpe).forall { key =>
      handlers.get(key) match {
        case None => true
        case Some(callbacks) =>
          // `map` is strict: every handler of the key runs before the decision.
          callbacks.map(callback => callback() == true).forall(identity)
      }
    }
}
