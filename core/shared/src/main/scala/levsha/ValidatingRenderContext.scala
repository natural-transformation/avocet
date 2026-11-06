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

import avocet.ValidationError.Code
import avocet.impl.DiffRenderContext
import avocet.impl.internal.HtmlShape

import scala.collection.mutable

/**
  * Opt-in validating decorator over any [[RenderContext]]. It checks the
  * operation stream according to [[Validation]] and forwards every
  * operation to `inner`, so the inner interpreter produces its normal output
  * (in collect mode even for a document with errors).
  *
  * Fail-fast mode throws a [[ValidationException]] before the offending
  * operation is forwarded. Collect mode records up to the configured number
  * of errors and keeps going; read [[errors]] and [[truncated]] afterwards.
  * Call [[complete]] once the template has finished (it reports unclosed
  * nodes) and [[reset]] before reusing the decorator for another document.
  *
  * The decorator keeps a bounded per-level stack (element names, child
  * counters) and a reusable attribute-name table; after warm-up a valid
  * document causes no allocation beyond what `inner` does. Positions in
  * errors are the positional ids the diff engine assigns, so a diagnostic
  * can be related to a DOM node.
  *
  * Plain and optimized templates emit the same operation stream, so they
  * produce the same diagnostics. Use [[ValidatingRenderContext.stateful]]
  * for integrations that need `currentId`.
  */
class ValidatingRenderContext[-M] private[avocet] (inner: RenderContext[M], val checks: Validation)
  extends RenderContext[M] {

  import ValidatingRenderContext._

  private val maxDepth = DiffRenderContext.MaxDepth
  // Per-level state; index = depth of the open element (0 = outermost).
  private val names = new Array[String](maxDepth)
  // A `p` the parser has already closed (reported once) is not closed again by later descendants.
  private val paragraphClosed = new Array[Boolean](maxDepth)
  private val isHtml = new Array[Boolean](maxDepth)
  // childCounts(level) = children emitted so far at `level`; level 0 is the root level.
  private val childCounts = new Array[Int](maxDepth + 1)
  private val lastWasText = new Array[Boolean](maxDepth + 1)
  private var depth = 0
  private var overflow = 0 // opens beyond maxDepth, so closes can be matched
  private var phase = PhaseChildren // phase of the innermost open element
  private var openForms = 0
  private var openAnchors = 0
  private var openButtons = 0
  private var openSelects = 0
  // Attribute table of the innermost open element, reused across elements.
  private var attrNames = new Array[String](16)
  private var attrNs = new Array[Byte](16)
  private var attrIsStyle = new Array[Boolean](16)
  private var attrCount = 0

  private val collected = mutable.ArrayBuffer.empty[ValidationError]
  private var dropped = 0

  /** Errors recorded so far (collect mode). */
  def errors: Seq[ValidationError] = collected.toVector

  /** True when the collection cap dropped further errors. */
  def truncated: Boolean = dropped > 0

  /** Clears the stack and the errors; the inner context is not touched. */
  def reset(): Unit = {
    depth = 0; overflow = 0; phase = PhaseChildren; attrCount = 0
    openForms = 0; openAnchors = 0; openButtons = 0; openSelects = 0
    childCounts(0) = 0; lastWasText(0) = false
    collected.clear(); dropped = 0
  }

  /** Reports every still-open element; call it after the template. */
  def complete(): Unit =
    if (checks.structural && depth > 0) {
      var level = depth - 1
      while (level >= 0) {
        report(Code.UnclosedNode, positionOf(level), names(level), "element was never closed")
        level -= 1
      }
    }

  // --- operations ------------------------------------------------------------

  def openNode(xmlns: XmlNs, name: String): Unit = {
    val html = xmlns eq XmlNs.html
    if (depth >= maxDepth) {
      if (checks.structural) report(Code.DepthExceeded, positionOf(depth - 1), names(depth - 1), s"nesting deeper than $maxDepth")
      overflow += 1
      inner.openNode(xmlns, name)
      return
    }
    childCounts(depth) += 1
    if (depth > 0) {
      if (checks.htmlShape) checkElementParent(html, name)
    } else if (checks.interactive && childCounts(0) > 1) {
      report(Code.MultipleRoots, positionOf(0), name, "a second root node; the interactive profile has one root")
    }
    if (checks.interactive && html) checkInteractiveElement(name)
    lastWasText(depth) = false
    names(depth) = name
    isHtml(depth) = html
    paragraphClosed(depth) = false
    depth += 1
    childCounts(depth) = 0
    lastWasText(depth) = false
    phase = PhaseStyles
    attrCount = 0
    if (html) name match {
      case "form" => openForms += 1
      case "a" => openAnchors += 1
      case "button" => openButtons += 1
      case "select" => openSelects += 1
      case _ => ()
    }
    inner.openNode(xmlns, name)
  }

  def closeNode(name: String): Unit = {
    if (overflow > 0) {
      overflow -= 1
    } else if (depth == 0) {
      if (checks.structural) report(Code.CloseWithoutOpen, positionOf(0), name, "closeNode without an open element")
    } else {
      val level = depth - 1
      if (checks.structural && names(level) != name)
        report(Code.CloseMismatch, positionOf(level), names(level), "closeNode name differs from the open element")
      if (isHtml(level)) names(level) match {
        case "form" => openForms -= 1
        case "a" => openAnchors -= 1
        case "button" => openButtons -= 1
        case "select" => openSelects -= 1
        case _ => ()
      }
      depth -= 1
      phase = PhaseChildren
    }
    inner.closeNode(name)
  }

  def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = {
    checkAttrOrStyle(xmlNs.code, name, value, isStyle = false)
    inner.setAttr(xmlNs, name, value)
  }

  def setStyle(name: String, value: String): Unit = {
    checkAttrOrStyle(0, name, value, isStyle = true)
    inner.setStyle(name, value)
  }

  def addTextNode(text: String): Unit = {
    if (overflow == 0) {
      childCounts(depth) += 1
      phase = PhaseChildren
      if (depth > 0 && checks.htmlShape) checkTextParent(text)
      if (checks.interactive) checkInteractiveText(text)
      lastWasText(depth) = true
    }
    inner.addTextNode(text)
  }

  def addMisc(misc: M): Unit = {
    if (checks.structural && depth == 0 && overflow == 0)
      report(Code.MiscOutsideNode, positionOf(0), "", "addMisc outside any element")
    inner.addMisc(misc)
  }

  // --- checks ------------------------------------------------------------------

  private def checkAttrOrStyle(ns: Byte, name: String, value: String, isStyle: Boolean): Unit = {
    if (overflow > 0) return
    if (checks.structural) {
      if (depth == 0)
        report(Code.AttrOutsideNode, positionOf(0), "", s"${kindOf(isStyle)} outside any element")
      else if (phase == PhaseChildren)
        report(Code.AttrAfterChild, currentPosition, currentName, s"${kindOf(isStyle)} after a child node")
      else if (isStyle && phase == PhaseAttrs)
        report(Code.StyleAfterAttr, currentPosition, currentName, "style after an attribute; styles precede attributes")
      else if (seenAttr(ns, name, isStyle))
        report(if (isStyle) Code.DuplicateStyle else Code.DuplicateAttr, currentPosition, currentName,
          s"${kindOf(isStyle)} '${cap(name)}' set twice")
      if (depth > 0 && phase != PhaseChildren) {
        if (!isStyle) phase = PhaseAttrs
        rememberAttr(ns, name, isStyle)
      }
    }
    if (checks.interactive && HtmlShape.containsCarriageReturn(value))
      report(Code.CarriageReturn, currentPosition, currentName, s"${kindOf(isStyle)} '${cap(name)}' value contains CR; the parser normalizes it to LF")
  }

  private def checkElementParent(childIsHtml: Boolean, name: String): Unit = {
    val level = depth - 1
    if (!isHtml(level)) return
    val parent = names(level)
    if (HtmlShape.voidElements.contains(parent))
      report(Code.VoidElementChild, positionOf(level), parent, "void element with a child; the parser makes it a sibling")
    else if (HtmlShape.rawTextElements.contains(parent) || HtmlShape.escapableRawTextElements.contains(parent))
      report(Code.RawTextChild, positionOf(level), parent, "element inside a raw text element becomes text")
    if (!childIsHtml) return
    if (HtmlShape.closesParagraph.contains(name)) {
      // The parser closes a `p` that is open anywhere in button scope, not only a direct parent.
      val pLevel = paragraphInButtonScope(level)
      if (pLevel >= 0) {
        paragraphClosed(pLevel) = true
        report(Code.ParagraphClosedByBlock, positionOf(pLevel), "p", s"<$name> closes the open paragraph")
      }
    }
    if (name == "form" && openForms > 0)
      report(Code.NestedForm, positionOf(level), parent, "form inside a form is ignored by the parser")
    if (name == "a" && openAnchors > 0)
      report(Code.NestedAnchor, positionOf(level), parent, "anchor inside an anchor closes the outer one")
    if (name == "button" && openButtons > 0)
      report(Code.NestedButton, positionOf(level), parent, "button inside a button closes the outer one")
    if (parent == "table") {
      if (!HtmlShape.tableChildren.contains(name))
        report(Code.TableFosterParent, positionOf(level), parent, s"<$name> directly in a table is moved before it")
      else if (HtmlShape.tableImpliedSection.contains(name))
        report(Code.TableImpliedSection, positionOf(level), parent, s"<$name> directly in a table gets an implied section; positions shift")
    } else if (HtmlShape.tableSections.contains(parent)) {
      if (!HtmlShape.sectionChildren.contains(name))
        report(Code.TableFosterParent, positionOf(level), parent, s"<$name> in a table section is moved before the table")
    } else if (parent == "tr") {
      if (!HtmlShape.rowChildren.contains(name))
        report(Code.TableFosterParent, positionOf(level), parent, s"<$name> in a row is moved before the table")
    } else if (parent == "colgroup") {
      if (!HtmlShape.colgroupChildren.contains(name))
        report(Code.TableFosterParent, positionOf(level), parent, s"<$name> in a colgroup is dropped")
    }
    if (openSelects > 0 && !HtmlShape.selectChildren.contains(name))
      report(Code.SelectChild, positionOf(level), parent, s"<$name> inside a select is ignored by the parser")
  }

  private def checkTextParent(text: String): Unit = {
    val level = depth - 1
    if (!isHtml(level)) return
    val parent = names(level)
    if (HtmlShape.voidElements.contains(parent))
      report(Code.VoidElementChild, positionOf(level), parent, "void element with text; the parser makes it a sibling")
    else if (HtmlShape.tableStructure.contains(parent) && !HtmlShape.isWhitespace(text))
      report(Code.TableFosterParent, positionOf(level), parent, "text in table structure is moved before the table")
  }

  private def checkInteractiveElement(name: String): Unit = {
    if (HtmlShape.documentElements.contains(name))
      report(Code.DocumentElement, positionOf(depth), name, "document structure element inside the root is relocated by the parser")
    else if (name == "template")
      report(Code.TemplateContent, positionOf(depth), name, "template content is parsed into a fragment, not the tree")
  }

  private def checkInteractiveText(text: String): Unit = {
    val position = positionOf(depth)
    if (text.isEmpty)
      report(Code.EmptyText, position, "", "empty text creates no DOM node; later positions shift")
    if (depth == 0)
      report(Code.TextAtRoot, position, "", "text at the root level")
    else {
      val parent = names(depth - 1)
      if (lastWasText(depth))
        report(Code.AdjacentText, position, parent, "adjacent text nodes are merged by the parser")
      if (isHtml(depth - 1)) {
        if (HtmlShape.rawTextElements.contains(parent))
          report(Code.RawTextContent, position, parent, "text inside a raw text element is escaped by SSR but not decoded by the parser")
        if (childCounts(depth) == 1 && text.startsWith("\n") && HtmlShape.dropsLeadingNewline.contains(parent))
          report(Code.PreLeadingNewline, position, parent, "a leading newline here is dropped by the parser")
      }
    }
    if (HtmlShape.containsCarriageReturn(text))
      report(Code.CarriageReturn, position, "", "text contains CR; the parser normalizes it to LF")
  }

  // --- attribute table ---------------------------------------------------------

  private def seenAttr(ns: Byte, name: String, isStyle: Boolean): Boolean = {
    var i = 0
    while (i < attrCount) {
      if (attrIsStyle(i) == isStyle && attrNs(i) == ns && attrNames(i) == name) return true
      i += 1
    }
    false
  }

  private def rememberAttr(ns: Byte, name: String, isStyle: Boolean): Unit = {
    if (attrCount == attrNames.length) {
      attrNames = java.util.Arrays.copyOf(attrNames, attrCount * 2)
      attrNs = java.util.Arrays.copyOf(attrNs, attrCount * 2)
      attrIsStyle = java.util.Arrays.copyOf(attrIsStyle, attrCount * 2)
    }
    attrNames(attrCount) = name
    attrNs(attrCount) = ns
    attrIsStyle(attrCount) = isStyle
    attrCount += 1
  }

  // --- reporting -----------------------------------------------------------------

  private def currentPosition: String = if (depth == 0) positionOf(0) else positionOf(depth - 1)
  private def currentName: String = if (depth == 0) "" else names(depth - 1)

  /** Positional id of the node most recently counted at `level` (`1_2_3` style). */
  /** Level of the nearest open `p` in button scope below `level`, or -1. Foreign elements and scoping elements end the search. */
  private def paragraphInButtonScope(level: Int): Int = {
    var j = level
    var found = -1
    while (found < 0 && j >= 0) {
      if (!isHtml(j)) j = -1
      else {
        val n = names(j)
        if (n == "p" && !paragraphClosed(j)) found = j
        else if (HtmlShape.buttonScopeBoundary.contains(n)) j = -1
        else j -= 1
      }
    }
    found
  }

  private def positionOf(level: Int): String = {
    val sb = new mutable.StringBuilder()
    var i = 0
    while (i <= level) {
      if (i > 0) sb.append('_')
      sb.append(childCounts(i))
      i += 1
    }
    sb.result()
  }

  private def report(code: Code, position: String, element: String, detail: String): Unit = {
    val error = ValidationError(code, position, cap(element), detail)
    checks.mode match {
      case ValidationMode.FailFast => throw new ValidationException(Seq(error), truncated = false)
      case ValidationMode.Collect(max) => if (collected.size < max) collected += error else dropped += 1
    }
  }
}

object ValidatingRenderContext {

  private final val PhaseStyles = 0
  private final val PhaseAttrs = 1
  private final val PhaseChildren = 2

  /** Longest element or attribute name copied into a diagnostic. */
  final val MaxNameInError = 64

  private def cap(name: String): String = if (name.length <= MaxNameInError) name else name.substring(0, MaxNameInError)
  private def kindOf(isStyle: Boolean): String = if (isStyle) "style" else "attribute"

  def apply[M](inner: RenderContext[M], checks: Validation): ValidatingRenderContext[M] =
    new ValidatingRenderContext[M](inner, checks)

  /** Validating decorator that also forwards the ids of a [[StatefulRenderContext]]. */
  def stateful[M](inner: StatefulRenderContext[M], checks: Validation): StatefulValidatingRenderContext[M] =
    new StatefulValidatingRenderContext[M](inner, checks)
}

/**
  * [[ValidatingRenderContext]] for stateful integrations: `currentId`,
  * `currentContainerId` and `subsequentId` come straight from the inner
  * context, so an id captured inside a template is the id the diff engine
  * assigned. Misc callbacks are forwarded unchanged.
  */
final class StatefulValidatingRenderContext[-M] private[avocet] (inner: StatefulRenderContext[M], checks: Validation)
  extends ValidatingRenderContext[M](inner, checks) with StatefulRenderContext[M] {
  def currentContainerId: Id = inner.currentContainerId
  def currentId: Id = inner.currentId
  def subsequentId: Id = inner.subsequentId
}
