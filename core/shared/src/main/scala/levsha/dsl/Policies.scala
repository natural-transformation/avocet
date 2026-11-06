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

package avocet.dsl

import avocet.Document.Attr
import avocet.XmlNs
import avocet.impl.internal.NameValidator

/**
  * Scheme policy for [[CheckedUrl]]. Scheme names are compared case-insensitively.
  * A value without a scheme is a relative reference and is accepted only
  * when `allowRelative` is set.
  */
final case class UrlPolicy(schemes: Set[String], allowRelative: Boolean = true) {
  private val lowered = schemes.map(_.toLowerCase)
  def allowsScheme(scheme: String): Boolean = lowered.contains(scheme.toLowerCase)
}

object UrlPolicy {
  /** `http`, `https`, `mailto`, `tel` and relative references. */
  val default: UrlPolicy = UrlPolicy(Set("http", "https", "mailto", "tel"))
  /** `http` and `https` only, relative references included. */
  val web: UrlPolicy = UrlPolicy(Set("http", "https"))
  /** Relative references only (same document or same origin). */
  val relativeOnly: UrlPolicy = UrlPolicy(Set.empty)
}

/**
  * A URL value accepted under a declared [[UrlPolicy]], or explicitly
  * trusted. It carries exactly that guarantee: the scheme is one the policy
  * allows (or the value is a relative reference) and the text has no control
  * characters that a browser would strip before resolving the scheme. It is
  * not a general sanitizer; the destination's content is the application's
  * business.
  */
final class CheckedUrl private[dsl] (val value: String) {
  override def toString: String = value
  override def equals(obj: Any): Boolean = obj match {
    case that: CheckedUrl => that.value == value
    case _ => false
  }
  override def hashCode(): Int = value.hashCode
}

object CheckedUrl {

  /** Checks `value` under `policy`; `None` when it is rejected. Leading and trailing whitespace is rejected, not trimmed. */
  def apply(value: String, policy: UrlPolicy = UrlPolicy.default): Option[CheckedUrl] =
    if (!wellFormed(value)) None
    else schemeOf(value) match {
      case null => if (policy.allowRelative) Some(new CheckedUrl(value)) else None
      case scheme => if (policy.allowsScheme(scheme)) Some(new CheckedUrl(value)) else None
    }

  /** Explicit escape hatch: no check at all. The caller vouches for the value. */
  def trusted(value: String): CheckedUrl = new CheckedUrl(value)

  /**
    * The scheme of an absolute URL, or `null` for a relative reference.
    * Browsers strip ASCII tab and newline anywhere in a URL before parsing,
    * which `wellFormed` already rejects, so a plain scan is enough here.
    */
  private def schemeOf(value: String): String = {
    val colon = value.indexOf(':')
    if (colon <= 0) null
    else {
      var i = 0
      var valid = isAsciiLetter(value.charAt(0))
      while (valid && i < colon) {
        val c = value.charAt(i)
        valid = isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'
        i += 1
      }
      if (valid) value.substring(0, colon) else null
    }
  }

  private def wellFormed(value: String): Boolean = {
    if (value.isEmpty) return true
    if (value.charAt(0) <= ' ' || value.charAt(value.length - 1) <= ' ') return false
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if (c < ' ' || c == 0x7F) return false
      i += 1
    }
    true
  }

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}


/**
  * A data-safe CSS declaration value, accepted only when it matches an
  * allowlisted grammar: whitespace, commas and slashes; numbers with a unit
  * or `%`; hex colors; identifiers (keywords, custom property names, vendor
  * prefixes); quoted strings at the top level only (font names, `content`);
  * and calls of the functions in [[CssValue.allowedFunctions]] with the same
  * grammar inside, balanced. Everything else is rejected: in particular every
  * function that can load a resource or run code (`url`, `image-set`, `src`,
  * `image`, `element`, `attr`, `expression`, vendor variants), strings inside
  * functions, escapes, comments, `;`, `{`, `}`, `<`, `>`, `!` and at-rules.
  * The interpreter still escapes the value for the enclosing attribute.
  */
final class CssValue private[dsl] (val value: String) {
  override def toString: String = value
  override def equals(obj: Any): Boolean = obj match {
    case that: CssValue => that.value == value
    case _ => false
  }
  override def hashCode(): Int = value.hashCode
}

object CssValue {

  final val MaxLength = 1024

  /** Functions whose arguments can neither load a resource nor run code. Names are compared lower-cased. */
  val allowedFunctions: Set[String] = Set(
    "calc", "min", "max", "clamp", "var", "env",
    "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix",
    "linear-gradient", "radial-gradient", "conic-gradient",
    "repeating-linear-gradient", "repeating-radial-gradient", "repeating-conic-gradient",
    "translate", "translatex", "translatey", "translatez", "translate3d",
    "scale", "scalex", "scaley", "scalez", "scale3d",
    "rotate", "rotatex", "rotatey", "rotatez", "rotate3d",
    "skew", "skewx", "skewy", "matrix", "matrix3d", "perspective",
    "cubic-bezier", "steps", "repeat", "minmax", "fit-content")

  def apply(value: String): Option[CssValue] =
    if (matchesGrammar(value)) Some(new CssValue(value)) else None

  /** Explicit escape hatch: arbitrary CSS text, as the existing `@=` on `StyleDef`. */
  def trusted(value: String): CssValue = new CssValue(value)

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isHex(c: Char): Boolean = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
  private def isLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
  private def isIdentStart(c: Char): Boolean = isLetter(c) || c == '-' || c == '_' || c > 0x7F
  private def isIdentChar(c: Char): Boolean = isIdentStart(c) || isDigit(c)

  /** Single pass over the value; returns false at the first token outside the grammar. */
  private def matchesGrammar(s: String): Boolean = {
    val n = s.length
    if (n == 0 || n > MaxLength) return false
    var i = 0
    var depth = 0
    while (i < n) {
      val c = s.charAt(i)
      if (c == ' ' || c == '\t' || c == ',' || c == '/') i += 1
      else if (c == ')') {
        if (depth == 0) return false
        depth -= 1
        i += 1
      } else if (c == '"' || c == '\'') {
        // Strings only at the top level: inside a function they could name a resource.
        if (depth > 0) return false
        val close = s.indexOf(c.toInt, i + 1)
        if (close < 0) return false
        var j = i + 1
        while (j < close) {
          val d = s.charAt(j)
          if (d < ' ' || d == 0x7F || d == '\\' || d == '(' || d == ')' || d == ';' || d == '{' || d == '}' || d == '<' || d == '>') return false
          j += 1
        }
        i = close + 1
      } else if (c == '#') {
        val start = i + 1
        i += 1
        while (i < n && isHex(s.charAt(i))) i += 1
        val len = i - start
        if (len != 3 && len != 4 && len != 6 && len != 8) return false
      } else if (isDigit(c) || c == '.' || ((c == '+' || c == '-') && i + 1 < n && (isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '.'))) {
        if (c == '+' || c == '-') i += 1
        var digits = 0
        while (i < n && isDigit(s.charAt(i))) { i += 1; digits += 1 }
        if (i < n && s.charAt(i) == '.') {
          i += 1
          var fraction = 0
          while (i < n && isDigit(s.charAt(i))) { i += 1; fraction += 1 }
          if (fraction == 0) return false
          digits += fraction
        }
        if (digits == 0) return false
        // Exponent only when followed by a digit; otherwise `e` starts a unit such as `em`.
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
          val next = if (i + 1 < n) s.charAt(i + 1) else ' '
          if (isDigit(next)) { i += 1; while (i < n && isDigit(s.charAt(i))) i += 1 }
          else if ((next == '+' || next == '-') && i + 2 < n && isDigit(s.charAt(i + 2))) { i += 2; while (i < n && isDigit(s.charAt(i))) i += 1 }
        }
        if (i < n && s.charAt(i) == '%') i += 1
        else while (i < n && isLetter(s.charAt(i))) i += 1
      } else if (isIdentStart(c)) {
        val start = i
        while (i < n && isIdentChar(s.charAt(i))) i += 1
        if (i < n && s.charAt(i) == '(') {
          if (!allowedFunctions.contains(s.substring(start, i).toLowerCase)) return false
          depth += 1
          i += 1
        }
      } else return false
    }
    depth == 0
  }
}

/**
  * Explicit trusted-code escape hatches. Each helper names the sink it
  * feeds; none of them checks or sanitizes its input beyond the name policy.
  * The ordinary data-safe way to react to events is the framework's misc
  * event binding, not an inline handler.
  */
object trusted {

  /** Inline event handler attribute (`onclick`); `code` is executable script text. */
  def inlineHandler[M](event: String, code: String): Attr[M] = {
    NameValidator.checkAttrName(event)
    val name = "on" + event
    Attr[M] { rc => rc.setAttr(XmlNs.html, name, code) }
  }

  /** `iframe[srcdoc]`: a nested document; outer escaping does not make it inert. */
  def srcdoc[M](html: String): Attr[M] =
    Attr[M] { rc => rc.setAttr(XmlNs.html, "srcdoc", html) }

  /** A URL the caller vouches for; see [[CheckedUrl.trusted]]. */
  def url(value: String): CheckedUrl = CheckedUrl.trusted(value)

  /** Arbitrary CSS text; see [[CssValue.trusted]]. */
  def css(value: String): CssValue = CssValue.trusted(value)
}
