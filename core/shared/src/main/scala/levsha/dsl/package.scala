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

import avocet.Document.{Attr, Empty, Node, Style}

import scala.language.implicitConversions

/**
  * Template DSL.
  *
  * Evaluation laws (they hold for plain and optimized templates alike, and
  * the optimizer tests assert them):
  *
  *  - A template is a description: building it evaluates its Scala
  *    arguments as usual (eagerly, once), rendering it runs the recorded
  *    operations. Expressions inside `optimize { ... }` are evaluated when
  *    the optimized node is rendered, and exactly once per render.
  *  - Dynamic expressions (attribute values, text, options, collections)
  *    are evaluated exactly once per render, in document order.
  *  - Operations reach the interpreter in document order: styles, then
  *    attributes, then children. A plain `TagDef.apply` sorts its arguments
  *    by kind to achieve this; the optimizer sorts at compile time.
  *  - An exception thrown by an expression propagates at render time, after
  *    the operations that precede it in document order were emitted.
  *  - A collection is iterated once per render; a reusable collection
  *    renders on every render, a single-use iterator only on the first.
  */
package object dsl extends Optimize {

  trait TagDef {
    def ns: XmlNs
    def name: String
    def apply[M](content: Document[M]*): Node[M] = Node { rc =>
      rc.openNode(ns, name)
      content.filter(_ != Empty).sortBy(_.kind).foreach(_(rc))
      rc.closeNode(name)
    }
  }

  trait AttrDef {
    def ns: XmlNs
    def name: String
    def :=[M](value: String): Attr[M] = Attr[M] { rc =>
      rc.setAttr(ns, name, value)
    }
  }

  trait StyleDef {
    def name: String
    def @=[M](value: String): Style[M] = Style[M] { rc =>
      rc.setStyle(name, value)
    }
  }

  def TagDef(tagName: String): TagDef = TagDef(XmlNs.html, tagName)

  def TagDef(namespace: XmlNs, tagName: String): TagDef = new TagDef {
    def ns: XmlNs = namespace
    def name: String = tagName
  }

  def AttrDef(attrName: String): AttrDef = AttrDef(XmlNs.html, attrName)

  def AttrDef(namespace: XmlNs, attrName: String): AttrDef = new AttrDef {
    def ns: XmlNs = namespace
    def name: String = attrName
  }

  def StyleDef(styleName: String): StyleDef = new StyleDef {
    def name: String = styleName
  }

//
//  final case class RichAttrDef[T](ns: XmlNs, attrName: String, mkString: T => String) {
//    def :=[M](value: T): Attr[M] = Attr[M] { rc =>
//      rc.setAttr(ns, attrName, mkString(value))
//    }
//    def :=[M](value: String): Attr[M] = Attr[M] { rc =>
//      rc.setAttr(ns, attrName, value)
//    }
//  }
//
//  final case class RichStyleDef[T](attrName: String, mkString: T => String) {
//    def @=[M](value: String): Style[M] = Style[M] { rc =>
//      rc.setStyle(attrName, value)
//    }
//    def @=[M](value: T): Style[M] = Style[M] { rc =>
//      rc.setStyle(attrName, mkString(value))
//    }
//  }

  def void[T]: Document.Node[T] with Document.Attr[T] = Empty

  /** Typed empty node; use it where `void` leaves inference open. Renders nothing. */
  val noNode: Node[Nothing] = Node[Nothing](_ => ())

  /** Typed empty attribute. Renders nothing. */
  val noAttr: Attr[Nothing] = Attr[Nothing](_ => ())

  /** Typed empty style. Renders nothing. */
  val noStyle: Style[Nothing] = Style[Nothing](_ => ())

  /**
    * Explicit fragment: renders `nodes` in order without a wrapping element.
    * The arguments are evaluated once, when `fragment` is called (before
    * rendering), like any Scala argument; the optimizer preserves that.
    */
  def fragment[M](nodes: Node[M]*): Node[M] =
    Node[M] { rc => nodes.foreach(_(rc)) }

  /**
    * Explicit iterable rendering: `each(items)(item => li(item))`. `f` is
    * applied to each element once, in iteration order, during rendering; the
    * collection is iterated once per render, so a single-use `Iterator`
    * renders only once. The optimizer lowers this to a loop that emits the
    * body's operations directly.
    */
  def each[M, A](xs: Iterable[A])(f: A => Node[M]): Node[M] =
    Node[M] { rc =>
      val i = xs.iterator
      while (i.hasNext) f(i.next())(rc)
    }

  /**
    * Add node or attribute conditionally
    * @example
    * {{{
    * button(
    *   when(inProgress)(disabled)
    *   "Push me"
    * )
    * }}}
    */
  def when[T](condition: Boolean)(doc: Document[T]): Document[T] =
    if (condition) doc else Empty

  /**
    * Use it when want overwrite default click behavior.
    * {{{
    *   a(href := "http://example.com", preventDefaultClickBehavior)
    * }}}
    */
  val preventDefaultClickBehavior: Attr[Nothing] = Attr { rc =>
    rc.setAttr(XmlNs.html, "onclick", "return false")
  }

  // Converters

  /** Converts String to text document node */
  implicit def stringToNode[M](value: String): Node[M] =
    Node { rc => rc.addTextNode(value) }

  /** Converts MiscType to text document node */
  implicit def miscToNode[M](value: M): Node[M] =
    Node { rc => rc.addMisc(value) }

  /** Converts iterable of attributes to document one attr */
  implicit def seqToAttr[M](xs: Iterable[Attr[M]]): Attr[M] =
    Attr(rc => xs.foreach(f => f(rc)))

  /** Converts iterable of attributes to document one style */
  implicit def seqToStyle[M](xs: Iterable[Style[M]]): Style[M] =
    Style(rc => xs.foreach(f => f(rc)))

  /** Converts iterable of templates to document fragment */
  implicit def seqToNode[M](xs: Iterable[Node[M]]): Node[M] =
    Node(rc => xs.foreach(f => f(rc))) // Apply render context to elements

  /** Converts sequence of T (which can be converted to Node) to document fragment */
  implicit def arbitrarySeqToNode[M, T](xs: Iterable[T])(implicit ev: T => Node[M]): Node[M] =
    Node(rc => xs.foreach(f => ev(f).apply(rc)))

  /** Implicitly unwraps optional attributes */
  implicit def optionToAttr[M](value: Option[Attr[M]]): Attr[M] =
    Attr(rc => if (value.nonEmpty) value.get(rc))

  /** Implicitly unwraps optional styles */
  implicit def optionToStyle[M](value: Option[Style[M]]): Style[M] =
    Style(rc => if (value.nonEmpty) value.get(rc))

  /** Implicitly unwraps optional documents */
  implicit def optionToNode[M](value: Option[Node[M]]): Node[M] =
    Node(rc => if (value.nonEmpty) value.get(rc))

  /** Converts option of T (which can be converted to Node) to document node */
  implicit def arbitraryOptionToNode[M, T](value: Option[T])(implicit ev: T => Node[M]): Node[M] =
    Node(rc => if (value.nonEmpty) ev(value.get).apply(rc))
}
