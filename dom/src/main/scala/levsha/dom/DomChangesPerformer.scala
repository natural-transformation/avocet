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

package avocet.dom

import avocet.{FastId, Id, XmlNs}
import avocet.impl.DiffRenderContext.ChangesPerformer
import org.scalajs.dom.raw.HTMLElement
import org.scalajs.dom.{Element, Node}
import org.scalajs.{dom => browserDom}

import scala.collection.mutable
import scala.scalajs.js

/**
  * Applies changes to the browser DOM under `target`.
  *
  * The index maps positional ids to live nodes. Replacing or removing a node
  * drops the index entries of its whole subtree, so a retired node can never
  * receive a later update. A change that targets a missing node means the
  * index and the engine's baseline disagree; it throws, and the caller marks
  * the root as failed.
  */
final class DomChangesPerformer(target: Element) extends ChangesPerformer {

  private val index = mutable.Map[Id, Node](Id.TopLevel -> target)

  // The template root (id 1) is the target element itself, so events on it resolve to that id.
  target.asInstanceOf[js.Dynamic].vid = Id.TopLevel.mkString

  /** Number of indexed nodes, root included. Test seam. */
  private[dom] def indexSize: Int = index.size

  private def vidOf(node: Node): Option[String] =
    node.asInstanceOf[js.Dynamic].vid.asInstanceOf[Any] match {
      case vid: String => Some(vid)
      case _ => None
    }

  /**
    * The id under which `node` is live in this root, if any. The `vid` a node
    * carries is only a hint: a removed node keeps it, and a node of another
    * root carries the same positional ids. Only an index entry that maps the
    * id back to this very node makes it live.
    */
  private[dom] def liveIdOf(node: Node): Option[Id] =
    vidOf(node).map(Id(_)).filter(id => index.get(id).exists(_ eq node))

  /**
    * Drops the index entries of `node` and all its descendants; the target
    * itself stays indexed. Descendants that are not live here, such as a
    * removed node that other code put back, keep the live entries of their ids.
    */
  private def forget(node: Node): Unit = {
    if (node ne target) liveIdOf(node).foreach(index.remove)
    var child = node.firstChild
    while (child != null) {
      forget(child)
      child = child.nextSibling
    }
  }

  /** Removes every child of the target and forgets their index entries. */
  private def clearTarget(): Unit = {
    forget(target)
    while (target.firstChild != null) target.removeChild(target.firstChild)
  }

  private def create(fastId: FastId)(createNewElement: => Node): Unit = {
    val id = fastId.mkId
    if (id == Id.TopLevel) {
      // The root node maps onto the target: it is never replaced, only emptied. Its tag is not checked.
      clearTarget()
      return
    }
    val parent = id.parent.flatMap(index.get).getOrElse(missing(id, "create"))
    val newEl = createNewElement
    newEl.asInstanceOf[js.Dynamic].vid = id.mkString
    index.get(id) match {
      case Some(oldEl) =>
        forget(oldEl)
        if (oldEl.parentNode == parent) parent.replaceChild(newEl, oldEl)
        else parent.appendChild(newEl)
      case None =>
        parent.appendChild(newEl)
    }
    index.update(id, newEl)
  }

  private def missing(id: Id, op: String): Nothing =
    throw new IllegalStateException(s"DOM index has no node for $op at ${id.mkString}")

  private def element(fastId: FastId, op: String): Element = {
    val id = fastId.mkId
    index.get(id) match {
      case Some(node: Element) => node
      case Some(_) => throw new IllegalStateException(s"$op targets a text node at ${id.mkString}")
      case None => missing(id, op)
    }
  }

  def createText(id: FastId, text: String): Unit = create(id) {
    browserDom.document.createTextNode(text)
  }

  def create(id: FastId, xmlNs: String, tag: String): Unit = create(id) {
    browserDom.document.createElementNS(xmlNs, tag)
  }

  def remove(id: FastId): Unit = {
    val nodeId = id.mkId
    if (nodeId == Id.TopLevel) { clearTarget(); return }
    val el = index.getOrElse(nodeId, missing(nodeId, "remove"))
    forget(el)
    if (el.parentNode != null) el.parentNode.removeChild(el)
  }

  def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit = {
    val node = element(id, "setAttr")
    if (xmlNs eq XmlNs.html.uri) node.setAttribute(name, value)
    else node.setAttributeNS(xmlNs, name, value)
  }

  def removeAttr(id: FastId, xmlNs: String, name: String): Unit = {
    val node = element(id, "removeAttr")
    if (xmlNs eq XmlNs.html.uri) node.removeAttribute(name)
    else node.removeAttributeNS(xmlNs, name)
  }

  def setStyle(id: FastId, name: String, value: String): Unit = element(id, "setStyle") match {
    case node: HTMLElement => node.style.setProperty(name, value)
    case node => browserDom.console.warn(s"Can't set style to $node")
  }

  def removeStyle(id: FastId, name: String): Unit = element(id, "removeStyle") match {
    case node: HTMLElement => node.style.removeProperty(name)
    case node => browserDom.console.warn(s"Can't remove style from $node")
  }

  /** Removes every node this performer created and clears the index. */
  def dispose(): Unit = {
    index.remove(Id.TopLevel)
    index.values.foreach { node =>
      if (node.parentNode == target) target.removeChild(node)
    }
    index.clear()
  }
}
