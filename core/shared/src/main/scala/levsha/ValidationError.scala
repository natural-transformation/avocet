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

/**
  * One diagnostic of [[ValidatingRenderContext]].
  *
  * Diagnostics are payload-free: `position` is the positional id of the
  * node concerned (`1_2_3`, the same ids the diff engine assigns), `element`
  * is the tag name of that node (capped) or empty, and `detail` names the
  * rule and at most a (capped) attribute or style name. Text and attribute
  * values never appear.
  */
final case class ValidationError(code: ValidationError.Code, position: String, element: String, detail: String) {
  override def toString: String =
    s"${code.name} at $position${if (element.isEmpty) "" else s" <$element>"}: $detail"
}

object ValidationError {

  /** Which selectable check group a code belongs to; see [[Validation]]. */
  sealed trait Check

  object Check {
    case object Structural extends Check
    case object HtmlShape extends Check
    case object Interactive extends Check
  }

  sealed abstract class Code(val name: String, val check: Check)

  object Code {
    import Check._

    // Structural protocol: the operation stream itself is malformed.
    case object CloseWithoutOpen extends Code("CLOSE_WITHOUT_OPEN", Structural)
    case object CloseMismatch extends Code("CLOSE_MISMATCH", Structural)
    case object AttrOutsideNode extends Code("ATTR_OUTSIDE_NODE", Structural)
    case object AttrAfterChild extends Code("ATTR_AFTER_CHILD", Structural)
    case object StyleAfterAttr extends Code("STYLE_AFTER_ATTR", Structural)
    case object DuplicateAttr extends Code("DUPLICATE_ATTR", Structural)
    case object DuplicateStyle extends Code("DUPLICATE_STYLE", Structural)
    case object MiscOutsideNode extends Code("MISC_OUTSIDE_NODE", Structural)
    case object UnclosedNode extends Code("UNCLOSED_NODE", Structural)
    case object DepthExceeded extends Code("DEPTH_EXCEEDED", Structural)

    // Namespace-aware HTML shape: a browser parser would build a different tree.
    case object VoidElementChild extends Code("VOID_ELEMENT_CHILD", HtmlShape)
    case object ParagraphClosedByBlock extends Code("PARAGRAPH_CLOSED_BY_BLOCK", HtmlShape)
    case object NestedForm extends Code("NESTED_FORM", HtmlShape)
    case object NestedAnchor extends Code("NESTED_ANCHOR", HtmlShape)
    case object NestedButton extends Code("NESTED_BUTTON", HtmlShape)
    case object TableFosterParent extends Code("TABLE_FOSTER_PARENT", HtmlShape)
    case object TableImpliedSection extends Code("TABLE_IMPLIED_SECTION", HtmlShape)
    case object RawTextChild extends Code("RAW_TEXT_CHILD", HtmlShape)
    case object SelectChild extends Code("SELECT_CHILD", HtmlShape)

    // Interactive bootstrap: SSR output parsed by a browser would not map onto the renderer's ids.
    case object AdjacentText extends Code("ADJACENT_TEXT", Interactive)
    case object EmptyText extends Code("EMPTY_TEXT", Interactive)
    case object MultipleRoots extends Code("MULTIPLE_ROOTS", Interactive)
    case object TextAtRoot extends Code("TEXT_AT_ROOT", Interactive)
    case object PreLeadingNewline extends Code("PRE_LEADING_NEWLINE", Interactive)
    case object CarriageReturn extends Code("CARRIAGE_RETURN", Interactive)
    case object DocumentElement extends Code("DOCUMENT_ELEMENT", Interactive)
    case object TemplateContent extends Code("TEMPLATE_CONTENT", Interactive)
    case object RawTextContent extends Code("RAW_TEXT_CONTENT", Interactive)

    val all: Seq[Code] = Seq(
      CloseWithoutOpen, CloseMismatch, AttrOutsideNode, AttrAfterChild, StyleAfterAttr, DuplicateAttr,
      DuplicateStyle, MiscOutsideNode, UnclosedNode, DepthExceeded,
      VoidElementChild, ParagraphClosedByBlock, NestedForm, NestedAnchor, NestedButton, TableFosterParent,
      TableImpliedSection, RawTextChild, SelectChild,
      AdjacentText, EmptyText, MultipleRoots, TextAtRoot, PreLeadingNewline, CarriageReturn, DocumentElement,
      TemplateContent, RawTextContent)
  }
}

/**
  * Thrown (fail-fast mode) or returned through [[RenderOutcome.Rejected]]
  * (both modes) when a document fails validation. `errors` is capped by the
  * collection mode; `truncated` says whether more were dropped.
  */
final class ValidationException(val errors: Seq[ValidationError], val truncated: Boolean, cause: Throwable = null)
  extends RenderException(
    RenderException.Code.InvalidDocument,
    s"${errors.size} error(s)${if (truncated) " (truncated)" else ""}; first: ${errors.headOption.getOrElse("")}") {
  if (cause != null) initCause(cause)
}

/**
  * Selects the validation checks of [[ValidatingRenderContext]] and how
  * errors are reported.
  *
  *  - `structural`: balanced nodes, operation order, duplicate attributes.
  *  - `htmlShape`: void elements, paragraph auto-closing, nested forms,
  *    table foster parenting and other parser normalizations, applied to
  *    HTML-namespace elements only.
  *  - `interactive`: the Spoonbill-style bootstrap profile, where HTML5
  *    output without pretty-printing is parsed by a browser and then
  *    patched by positional id. Text segmentation and parser-relocated
  *    constructs break that mapping.
  *
  * Resource and representation limits are enforced by the interpreters in
  * every mode; this selection only concerns opt-in diagnostics.
  */
final case class Validation(structural: Boolean, htmlShape: Boolean, interactive: Boolean, mode: ValidationMode) {
  def enabled: Boolean = structural || htmlShape || interactive
  def failFast: Validation = copy(mode = ValidationMode.FailFast)
  def collect(maxErrors: Int = ValidationMode.DefaultMaxErrors): Validation = copy(mode = ValidationMode.Collect(maxErrors))
}

object Validation {
  val off: Validation = Validation(structural = false, htmlShape = false, interactive = false, ValidationMode.FailFast)
  val structural: Validation = off.copy(structural = true)
  val html: Validation = structural.copy(htmlShape = true)
  val interactiveHtml: Validation = html.copy(interactive = true)
}

sealed trait ValidationMode

object ValidationMode {
  final val DefaultMaxErrors = 64
  /** The first error is thrown as a [[ValidationException]] before the operation is forwarded. */
  case object FailFast extends ValidationMode
  /** Up to `maxErrors` errors are kept; operations are still forwarded. */
  final case class Collect(maxErrors: Int = DefaultMaxErrors) extends ValidationMode {
    if (maxErrors < 1) throw RenderException.invalidArgument("maxErrors must be positive")
  }
}
