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

import avocet.{RenderContext, RenderException, XmlNs}
import avocet.impl.internal.NameValidator
import avocet.impl.internal.Op._

import scala.annotation.switch
import scala.collection.mutable

/**
  * Generates XHTML output.
  *
  * Encoding contract: text and attribute values are XML-escaped, so they stay
  * data. Style names and values are escaped for the enclosing quoted `style`
  * attribute, so a CSS value can never close that attribute or add another
  * one. Whether a CSS expression or URL is acceptable is a separate policy
  * that this serializer does not decide. Tag, attribute and style names are
  * validated by [[NameValidator]] and never escaped.
  *
  * Operation order contract: all styles of an element precede its attributes,
  * and both precede its children. The DSL orders content this way; a raw
  * interpreter call out of order fails instead of emitting a duplicate
  * `style` attribute or attribute text inside the element body.
  *
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
class XhtmlRenderContext[MiscType](prettyPrinting: TextPrettyPrintingConfig) extends RenderContext[MiscType] {

  val builder: mutable.StringBuilder = new mutable.StringBuilder()

  private[impl] var lastOp: Int = OpClose
  private[impl] var indentation: Int = 0

  private[impl] def addIndentation(): Unit =
    if (prettyPrinting.enableAutoIndent) {
      var i = 0
      while (i < indentation) {
        var j = 0
        while (j < prettyPrinting.indentationSize) {
          builder.append(prettyPrinting.indentationChar)
          j += 1
        }
        i += 1
      }
    }

  def openNode(xmlns: XmlNs, name: String): Unit = {
    NameValidator.checkTagName(name)
    beforeOpenNode()
    addIndentation()
    builder.append('<')
    builder.append(name)
    lastOp = OpOpen
    indentation += 1
  }

  private def beforeOpenNode() = {
    if (lastOp != OpClose && lastOp != OpText) {
      if (lastOp == OpStyle) {
        builder.append('"')
      }
      builder.append('>')
      builder.append(prettyPrinting.lineBreak)
    }
  }

  def closeNode(name: String): Unit = {
    indentation -= 1
    if (lastOp == OpAttr || lastOp == OpOpen) {
      builder.append('>')
    } else if (lastOp == OpStyle) {
      builder.append("\">")
    } else {
      addIndentation()
    }
    builder.append('<')
    builder.append('/')
    builder.append(name)
    builder.append('>')
    builder.append(prettyPrinting.lineBreak)
    lastOp = OpClose
  }

  def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = {
    NameValidator.checkAttrName(name)
    if (lastOp == OpStyle) {
      builder.append('"')
    } else if (lastOp != OpOpen && lastOp != OpAttr) {
      throw RenderException.invalidState("setAttr is only allowed directly after openNode, styles or other attributes")
    }
    builder.append(' ')
    builder.append(name)
    builder.append('=')
    builder.append('"')
    builder.append(xmlEscape(value))
    builder.append('"')
    lastOp = OpAttr
  }

  def setStyle(name: String, value: String): Unit = {
    NameValidator.checkStyleName(name)
    if (lastOp == OpOpen) {
      builder.append(" style=\"")
    } else if (lastOp != OpStyle) {
      throw RenderException.invalidState("setStyle is only allowed directly after openNode or other styles")
    }
    builder.append(name)
    builder.append(":")
    // The value lives inside the quoted style attribute, so it needs the same encoding as any attribute value.
    builder.append(xmlEscape(value))
    builder.append(";")
    lastOp = OpStyle
  }

  private def xmlEscape(s: String) = {
    val possibleSize = (1.1 * s.length).toInt
    val sb = new mutable.StringBuilder(possibleSize)
    s.foreach { c =>
      (c: @switch) match {
        case '<' => sb.append("&lt;")
        case '>' => sb.append("&gt;")
        case '"' => sb.append("&quot;")
        case '&' => sb.append("&amp;")
        case _   => sb.append(c)
      }
    }
    sb.result()
  }

  def addTextNode(text: String): Unit = {
    beforeOpenNode()
    addIndentation()
    builder.append(xmlEscape(text))
    builder.append(prettyPrinting.lineBreak)
    lastOp = OpText
  }

  def addMisc(misc: MiscType): Unit = {}

  /** Creates string from buffer */
  def mkString: String = builder.result()
}
