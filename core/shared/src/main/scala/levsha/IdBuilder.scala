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

import java.nio.ShortBuffer
import scala.collection.mutable

/**
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
object IdBuilder {

  /** Largest sibling index. Positions are stored as `Short`; the check keeps them from wrapping. */
  final val MaxSiblings: Int = Short.MaxValue

  /** Default number of levels; the root is level 1, so nesting is limited to `DefaultMaxLevel - 1`. */
  final val DefaultMaxLevel: Int = 256

  def apply(maxLevel: Int = DefaultMaxLevel): IdBuilder =
    new IdBuilder(maxLevel)
}

/**
  * Positional id builder. Every limit is checked before the state changes,
  * so a failed call leaves the builder exactly as it was.
  */
final class IdBuilder(maxLevel: Int) extends FastId {

  import IdBuilder.MaxSiblings

  if (maxLevel < 1)
    throw RenderException.invalidArgument(s"maxLevel must be positive, got $maxLevel")

  private var level = 1
  private val buffer = ShortBuffer.allocate(maxLevel)
  private def index = level - 1

  // Initial limit
  buffer.limit(level)

  /** Throws when one more level would exceed `maxLevel`. Call it before `incId()` when both must succeed together. */
  def ensureCanDescend(): Unit =
    if (level >= maxLevel)
      throw RenderException.limit(s"nesting depth exceeds ${maxLevel - 1}")

  def incLevel(): Unit = {
    ensureCanDescend()
    level += 1
    buffer.limit(level)
  }

  /** Just decreases level */
  def decLevelTmp(): Unit = {
    ensureHasParent()
    level -= 1
    buffer.limit(level)
  }

  /** Resets current id and decreases level */
  def decLevel(): Unit = {
    ensureHasParent()
    buffer.put(index, 0.toShort)
    level -= 1
    buffer.limit(level)
  }

  def incId(): Unit = {
    val current = buffer.get(index)
    if (current >= MaxSiblings)
      throw RenderException.limit(s"more than $MaxSiblings siblings at level $level")
    buffer.put(index, (current + 1).toShort)
  }

  def decId(): Unit = {
    val updated = buffer.get(index) - 1
    buffer.put(index, updated.toShort)
  }

  def getLevel: Int = level

  def mkArray: Array[Short] = {
    val clone = new Array[Short](buffer.limit)
    buffer.rewind()
    var i = 0
    while (buffer.hasRemaining) {
      clone(i) = buffer.get()
      i += 1
    }
    clone
  }

  def mkString: String = mkString(Id.DefaultSeparator)

  def mkString(sep: Char): String = {
    val sb = new mutable.StringBuilder()
    mkStringLevel(sb, sep, buffer.limit())
    sb.result()
  }

  def mkStringParent(sb: mutable.StringBuilder): Unit =
    mkStringLevel(sb, Id.DefaultSeparator, level - 1)

  def mkStringParent(sb: mutable.StringBuilder, sep: Char): Unit =
    mkStringLevel(sb, sep, level - 1)

  def mkString(sb: mutable.StringBuilder): Unit = {
    mkStringLevel(sb, Id.DefaultSeparator, level)
  }

  def mkString(sb: mutable.StringBuilder, sep: Char): Unit = {
    mkStringLevel(sb, sep, level)
  }

  def hasParent: Boolean = level > 1

  private def ensureHasParent(): Unit =
    if (level <= 1)
      throw RenderException.invalidState("id level underflow: no parent level")

  private def mkStringLevel(sb: mutable.StringBuilder, sep: Char, l: Int): Unit = {
    buffer.rewind()
    var continue = true
    while (continue) {
      sb.append(buffer.get())
      if (buffer.position() >= l) {
        buffer.position(buffer.limit())
        continue = false
      } else sb.append(sep)
    }
  }

  def mkId: Id = new Id(mkArray)

  def reset(): Unit = {
    buffer.clear()
    while (buffer.hasRemaining) buffer.put(0.toShort)
    level = 1
    buffer.rewind()
    buffer.limit(level)
  }
}
