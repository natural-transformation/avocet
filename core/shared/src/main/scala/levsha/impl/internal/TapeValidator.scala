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

package avocet.impl.internal

import java.nio.ByteBuffer
import avocet.RenderException
import avocet.impl.internal.Op._

/**
  * Structural validation of a diff tape before it is adopted as a baseline.
  *
  * The walk checks opcodes, namespace codes, name/text lengths, node lengths,
  * balance and the attribute/child ordering of the tape grammar:
  *
  * {{{
  * document := (node | text)*
  * node     := OPEN sum length xmlns nameHash nameLength name attr* LAST_ATTR (node | text)* CLOSE
  * attr     := ATTR xmlns nameHash nameLength name isStyle valueHash valueLength value
  * text     := TEXT hash length value
  * }}}
  *
  * Hashes are not recomputed: a wrong hash can only cost an unnecessary
  * exact comparison or an unnecessary create, never wrong output.
  */
private[impl] object TapeValidator {

  /** Validates `buffer` in `[start, end)`. Throws [[RenderException]] with a bounded message. */
  def validate(buffer: ByteBuffer, start: Int, end: Int, maxDepth: Int, maxNameBytes: Int): Unit = {
    val ends = new Array[Int](maxDepth)
    var depth = 0
    var pos = start
    var expectAttrs = false

    def fail(reason: String): Nothing =
      throw RenderException.invalidSavedBuffer(s"$reason at offset ${pos - start}")

    def need(bytes: Int): Unit =
      if (bytes < 0 || pos > end - bytes) fail("truncated operation")

    def readNameLength(): Int = {
      need(2)
      val length = buffer.getShort(pos) & 0xFFFF
      pos += 2
      if (length > maxNameBytes || (length & 1) != 0 || length == 0) fail("invalid name length")
      need(length)
      pos += length
      length
    }

    def readValueLength(): Unit = {
      need(4)
      val length = buffer.getInt(pos)
      pos += 4
      if (length < 0 || (length & 1) != 0) fail("invalid value length")
      need(length)
      pos += length
    }

    def readXmlNs(): Int = {
      need(1)
      val code = buffer.get(pos)
      pos += 1
      if (code < 0 || code > 3) fail("unknown namespace code")
      code
    }

    while (pos < end) {
      val op = buffer.get(pos)
      pos += 1
      (op: Int) match {
        case OpOpen =>
          if (expectAttrs) fail("node before LAST_ATTR")
          if (depth >= maxDepth) fail("nesting depth exceeds limit")
          need(8)
          val nodeStart = pos
          val length = buffer.getInt(pos + 4)
          if (length < OpOpenSize || length > end - nodeStart) fail("invalid node length")
          ends(depth) = nodeStart + length
          depth += 1
          pos += 8
          readXmlNs()
          need(4) // name hash
          pos += 4
          readNameLength()
          expectAttrs = true
        case OpAttr =>
          if (!expectAttrs) fail("attribute outside of attribute list")
          val xmlNs = readXmlNs()
          need(4) // name hash
          pos += 4
          readNameLength()
          need(1)
          val isStyle = buffer.get(pos)
          pos += 1
          if (isStyle != 0 && isStyle != 1) fail("invalid style flag")
          if (isStyle == 1 && xmlNs != 0) fail("style with namespace")
          need(4) // value hash
          pos += 4
          readValueLength()
        case OpLastAttr =>
          if (!expectAttrs) fail("LAST_ATTR without open node")
          expectAttrs = false
        case OpText =>
          if (expectAttrs) fail("text before LAST_ATTR")
          need(4) // hash
          pos += 4
          readValueLength()
        case OpClose =>
          if (expectAttrs) fail("CLOSE before LAST_ATTR")
          if (depth == 0) fail("CLOSE without open node")
          depth -= 1
          if (pos != ends(depth)) fail("node length mismatch")
        case _ =>
          fail(s"unknown opcode $op")
      }
    }
    if (depth != 0) fail(s"$depth unclosed node(s)")
    if (expectAttrs) fail("missing LAST_ATTR")
  }
}
