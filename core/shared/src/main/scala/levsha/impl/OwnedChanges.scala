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

package avocet.impl

import avocet.{FastId, Id, XmlNs}
import avocet.impl.DiffRenderContext.FastChangesPerformer
import avocet.impl.internal.StringHelper

import java.nio.ByteBuffer
import scala.collection.mutable

/**
  * A change that owns all of its data. Unlike the arguments of
  * [[DiffRenderContext.FastChangesPerformer]], an `OwnedChange` can be
  * queued, sent to another thread or kept across later renders.
  */
sealed trait OwnedChange {
  def id: Id
}

object OwnedChange {
  final case class Create(id: Id, xmlNs: XmlNs, tag: String) extends OwnedChange
  final case class CreateText(id: Id, text: String) extends OwnedChange
  final case class Remove(id: Id) extends OwnedChange
  final case class SetAttr(id: Id, xmlNs: XmlNs, name: String, value: String) extends OwnedChange
  final case class RemoveAttr(id: Id, xmlNs: XmlNs, name: String) extends OwnedChange
  final case class SetStyle(id: Id, name: String, value: String) extends OwnedChange
  final case class RemoveStyle(id: Id, name: String) extends OwnedChange
}

/**
  * Performer adapter for queued or asynchronous consumers. Every borrowed
  * argument of the fast API (the mutable id builder and the live tape) is
  * copied into an [[OwnedChange]] before `emit` is called, so the consumer
  * may retain the value freely. The cost is one id array and one string per
  * name or value per change; synchronous consumers that need no copies
  * should implement [[DiffRenderContext.FastChangesPerformer]] directly.
  */
abstract class OwnedChangesPerformer extends FastChangesPerformer {

  /** Receives each owned change in diff order. */
  def emit(change: OwnedChange): Unit

  private def string(source: ByteBuffer, offset: Int, length: Int): String = {
    val sb = new mutable.StringBuilder(length / 2)
    StringHelper.appendFromSource(source, sb, offset, length)
    sb.result()
  }

  def remove(id: FastId): Unit =
    emit(OwnedChange.Remove(id.mkId))

  def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
    emit(OwnedChange.Create(id.mkId, xmlNs, string(source, nameOffset, nameLength)))

  def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit =
    emit(OwnedChange.CreateText(id.mkId, string(source, offset, length)))

  def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
    emit(OwnedChange.RemoveAttr(id.mkId, xmlNs, string(source, nameOffset, nameLength)))

  def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
    emit(OwnedChange.RemoveStyle(id.mkId, string(source, nameOffset, nameLength)))

  def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit =
    emit(OwnedChange.SetAttr(id.mkId, xmlNs, string(source, nameOffset, nameLength), string(source, valueOffset, valueLength)))

  def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit =
    emit(OwnedChange.SetStyle(id.mkId, string(source, nameOffset, nameLength), string(source, valueOffset, valueLength)))
}

object OwnedChangesPerformer {

  /** Forwards every owned change to `sink`. */
  def apply(sink: OwnedChange => Unit): OwnedChangesPerformer = new OwnedChangesPerformer {
    def emit(change: OwnedChange): Unit = sink(change)
  }

  /**
    * Collects owned changes until [[drain]] is called. The collected values
    * stay valid across later renders of the same context.
    */
  final class Buffer extends OwnedChangesPerformer {
    private val changes = mutable.ArrayBuffer.empty[OwnedChange]
    def emit(change: OwnedChange): Unit = changes += change
    def size: Int = changes.size
    /** Returns the collected changes and clears the buffer. */
    def drain(): Vector[OwnedChange] = {
      val result = changes.toVector
      changes.clear()
      result
    }
  }
}
