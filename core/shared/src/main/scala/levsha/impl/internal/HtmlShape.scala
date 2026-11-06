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

/**
  * Element tables behind the HTML shape and interactive bootstrap checks of
  * `ValidatingRenderContext`. They describe what the HTML parsing algorithm
  * does to a serialized tree; they apply to lowercase HTML-namespace names
  * only, which is what the DSL emits.
  */
private[avocet] object HtmlShape {

  /** Elements that can have no content; a serialized child ends up as a sibling. */
  val voidElements: Set[String] = Set(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

  /** Raw text elements: markup inside is text and entities are not decoded. */
  val rawTextElements: Set[String] = Set("script", "style", "xmp", "iframe", "noembed", "noframes", "plaintext")

  /** Escapable raw text elements: markup inside is text, entities are decoded. */
  val escapableRawTextElements: Set[String] = Set("textarea", "title")

  /** A start tag of one of these inside an open `p` closes the paragraph first. */
  val closesParagraph: Set[String] = Set(
    "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl", "fieldset",
    "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "main",
    "menu", "nav", "ol", "p", "pre", "search", "section", "summary", "table", "ul", "li", "dd", "dt", "listing",
    "plaintext", "xmp")

  /** Allowed direct children of `table`; anything else is foster-parented before the table. */
  val tableChildren: Set[String] = Set("caption", "colgroup", "thead", "tbody", "tfoot", "tr", "col", "script", "template", "style")

  /** Direct children of `table` that the parser wraps in an implied section, shifting positions. */
  val tableImpliedSection: Set[String] = Set("tr", "col")

  val tableSections: Set[String] = Set("thead", "tbody", "tfoot")
  val sectionChildren: Set[String] = Set("tr", "script", "template", "style")
  val rowChildren: Set[String] = Set("td", "th", "script", "template", "style")
  val colgroupChildren: Set[String] = Set("col", "template")

  /** Elements whose direct children are table structure (text that is not whitespace is foster-parented). */
  val tableStructure: Set[String] = Set("table", "thead", "tbody", "tfoot", "tr", "colgroup")

  /** Inside `select`, every other element start tag is ignored by the parser. */
  val selectChildren: Set[String] = Set("option", "optgroup", "hr", "script", "template")

  /** A single leading newline inside these elements is dropped by the parser. */
  val dropsLeadingNewline: Set[String] = Set("pre", "textarea", "listing")

  /** Document structure elements the parser relocates when they appear inside the body. */
  val documentElements: Set[String] = Set("html", "head", "body", "frameset")

  def isWhitespace(text: String): Boolean = {
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f') return false
      i += 1
    }
    true
  }

  def containsCarriageReturn(text: String): Boolean = text.indexOf('\r') >= 0
}
