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

import avocet.Document.{Attr, Empty, Style}
import avocet.{RenderException, XmlNs}
import avocet.impl.internal.NameValidator

/**
  * Typed attribute: accepts only `A` and encodes it with `encode`. The name
  * is validated once, when the key is built, so a dynamic name fails at
  * construction instead of inside every render. `encode` runs at render
  * time, exactly once per `:=` evaluation, in both plain and optimized
  * templates (the optimizer lowers `key := value` to a direct `setAttr`).
  *
  * Encoding here means producing the attribute's value; the interpreter
  * still escapes it for its output format. A key does not decide whether an
  * attribute is executable: `AttrKey.string("onclick")` is a trusted-code
  * sink like any raw attribute. Use [[trusted]] to make such uses explicit.
  */
final class AttrKey[A](val ns: XmlNs, val name: String, val encode: A => String) {
  def :=[M](value: A): Attr[M] = Attr[M] { rc => rc.setAttr(ns, name, encode(value)) }
  override def toString: String = s"AttrKey(${ns.shortcut}:$name)"
}

object AttrKey {

  /** Builds a key for an HTML attribute; throws `INVALID_NAME` for an invalid name. */
  def apply[A](name: String)(encode: A => String): AttrKey[A] = apply(XmlNs.html, name)(encode)

  def apply[A](ns: XmlNs, name: String)(encode: A => String): AttrKey[A] = {
    NameValidator.checkAttrName(name)
    new AttrKey[A](ns, name, encode)
  }

  /** Builds a key for a dynamic name without throwing. */
  def validated[A](ns: XmlNs, name: String)(encode: A => String): Either[RenderException, AttrKey[A]] =
    try Right(apply(ns, name)(encode))
    catch { case e: RenderException => Left(e) }

  /** Plain string attribute; the value is data, escaped by the interpreter, with no further policy. */
  def string(name: String): AttrKey[String] = apply(name)(identity)
  def string(ns: XmlNs, name: String): AttrKey[String] = apply(ns, name)(identity)

  def int(name: String): AttrKey[Int] = apply(name)(_.toString)
  def long(name: String): AttrKey[Long] = apply(name)(_.toString)

  /** URL-bearing attribute: only a [[CheckedUrl]] (policy-checked or explicitly trusted) can be assigned. */
  def url(name: String): AttrKey[CheckedUrl] = apply(name)(_.value)

  /** Boolean attribute (`disabled`, `checked`): present with an empty value or absent. */
  def boolean(name: String): BooleanAttrKey = {
    NameValidator.checkAttrName(name)
    new BooleanAttrKey(XmlNs.html, name)
  }

  /** Space-separated token list (`class`, `rel`). Tokens are joined as given, with no further checks. */
  def tokens(name: String): AttrKey[Iterable[String]] = apply(name)(_.mkString(" "))
}

/** Boolean attribute: `key := true` sets `name=""`; `key := false` renders nothing. */
final class BooleanAttrKey(val ns: XmlNs, val name: String) {
  def :=[M](present: Boolean): Attr[M] =
    if (present) Attr[M] { rc => rc.setAttr(ns, name, "") } else Empty
  override def toString: String = s"BooleanAttrKey(${ns.shortcut}:$name)"
}

/**
  * Typed style: accepts only `A` and encodes it with `encode`. The
  * interpreter protects the enclosing `style` attribute; whether the CSS
  * value itself is acceptable is the key's policy (see [[CssValue]]).
  */
final class StyleKey[A](val name: String, val encode: A => String) {
  def @=[M](value: A): Style[M] = Style[M] { rc => rc.setStyle(name, encode(value)) }
  override def toString: String = s"StyleKey($name)"
}

object StyleKey {

  def apply[A](name: String)(encode: A => String): StyleKey[A] = {
    NameValidator.checkStyleName(name)
    new StyleKey[A](name, encode)
  }

  def validated[A](name: String)(encode: A => String): Either[RenderException, StyleKey[A]] =
    try Right(apply(name)(encode))
    catch { case e: RenderException => Left(e) }

  /** Trusted arbitrary CSS text, as the existing `@=` on `StyleDef`. */
  def string(name: String): StyleKey[String] = apply(name)(identity)

  /** Data-safe CSS value: only a [[CssValue]] can be assigned. */
  def css(name: String): StyleKey[CssValue] = apply(name)(_.value)

  /** Pixel length: `px("width") @= 10` renders `width:10px`. */
  def px(name: String): StyleKey[Int] = apply(name)(n => n.toString + "px")
}
