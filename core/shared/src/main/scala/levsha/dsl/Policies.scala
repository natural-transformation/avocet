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
  * A data-safe CSS declaration value. The restricted grammar excludes
  * everything that can leave the declaration or load a resource: `;`, `{`,
  * `}`, `<`, `>`, backslash escapes, comments, `url(`, `expression(` and
  * at-rules. Lengths, colors, keywords, numbers, `calc()` and `rgb()` pass.
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

  def apply(value: String): Option[CssValue] =
    if (value.isEmpty || value.length > MaxLength) None
    else {
      var i = 0
      var ok = true
      while (ok && i < value.length) {
        val c = value.charAt(i)
        ok = c >= ' ' && c != 0x7F && c != ';' && c != '{' && c != '}' && c != '<' && c != '>' && c != '\\' && c != '@'
        i += 1
      }
      val lower = if (ok) value.toLowerCase else ""
      if (!ok || lower.contains("url(") || lower.contains("expression(") || lower.contains("/*") || lower.contains("-->")) None
      else Some(new CssValue(value))
    }

  /** Explicit escape hatch: arbitrary CSS text, as the existing `@=` on `StyleDef`. */
  def trusted(value: String): CssValue = new CssValue(value)
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
