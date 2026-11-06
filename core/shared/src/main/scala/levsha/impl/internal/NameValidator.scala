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

import avocet.RenderException

/**
  * Name policy shared by every interpreter.
  *
  * A dynamic tag, attribute or style name is accepted when it is non-empty,
  * at most [[MaxNameLength]] characters long and consists of ASCII letters,
  * digits, `-`, `_`, `.`, `:` or non-ASCII characters that are neither
  * whitespace nor control characters. Tag and attribute names start with an
  * ASCII letter, `_` or `:`; style names may also start with `-` so that
  * vendor prefixes and CSS custom properties (`--name`) work.
  *
  * The policy is syntactic: it keeps a name inside its markup position (it can
  * never close a tag, start another attribute or carry a quote). It does not
  * decide whether an attribute is executable; `onclick` is a valid name.
  * Escaping is not a substitute for this check: an invalid name is rejected,
  * never rewritten.
  */
private[avocet] object NameValidator {

  /** Maximum name length in UTF-16 chars. Encoded names use two bytes per char. */
  final val MaxNameLength = 1024

  def checkTagName(name: String): Unit = check(name, "tag", allowLeadingDash = false)

  def checkAttrName(name: String): Unit = check(name, "attribute", allowLeadingDash = false)

  def checkStyleName(name: String): Unit = check(name, "style", allowLeadingDash = true)

  private def check(name: String, kind: String, allowLeadingDash: Boolean): Unit = {
    val length = name.length
    if (length == 0)
      throw RenderException.invalidName(kind, "name is empty")
    if (length > MaxNameLength)
      throw RenderException.limit(s"$kind name length $length exceeds $MaxNameLength chars")
    val first = name.charAt(0)
    val firstOk = isAsciiLetter(first) || first == '_' || first == ':' || (allowLeadingDash && first == '-')
    if (!firstOk)
      throw RenderException.invalidName(kind, s"invalid first character (code ${first.toInt})")
    var i = 1
    while (i < length) {
      val c = name.charAt(i)
      if (!isNameChar(c))
        throw RenderException.invalidName(kind, s"invalid character at index $i (code ${c.toInt})")
      i += 1
    }
  }

  private def isAsciiLetter(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  private def isNameChar(c: Char): Boolean =
    isAsciiLetter(c) ||
      (c >= '0' && c <= '9') ||
      c == '-' || c == '_' || c == '.' || c == ':' ||
      (c > 0x7F && !Character.isWhitespace(c) && !Character.isISOControl(c))
}
