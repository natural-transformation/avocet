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

import java.nio.{ByteBuffer, ByteOrder}
import avocet._
import avocet.impl.DiffRenderContext._
import avocet.impl.internal.Op._
import avocet.impl.internal.{Hashing, NameValidator, StringHelper, TapeValidator}

import scala.annotation.switch

/*
Structures (tape format version 2)

node {
  byte OPEN
  int sum              // content hash: attributes, text and child hashes
  int length           // byte length of the node from `sum` up to and including CLOSE
  byte xmlns_code
  int tag_hash_code
  ushort name_length   // bytes, two per UTF-16 char
  byte name[name_length]
  attr attr_list[]
  byte LAST_ATTR
  node|text child[]
  byte CLOSE
}

text {
  byte TEXT
  int hash
  int length           // bytes
  byte value[length]
}

attr {
  byte ATTR
  byte xmlns_code (0 for styles)
  int attr_name_hash_code
  ushort name_length
  byte name[name_length]
  byte is_style (0 - attr; 1 - style)
  int value_hash
  int value_length
  byte value[value_length]
}

Node lengths are relative, so equal subtrees are byte-identical wherever they
are stored. Hashes are fast rejection tests only: every equality decision is
confirmed by comparing the encoded bytes.

Lifecycle

  render ops -> finalizeDocument() -> diff(performer) -> swap() -> [reset()] -> render ops ...

`lhs` is the document under construction (or just finalized), `rhs` the
baseline. `swap()` exchanges them; the next render op clears the stale
baseline that became `lhs`. `reset()` discards a partially written `lhs`
(for example after an exception thrown by a template) and restores a clean
construction state while the baseline stays intact.

Retention: the backing buffer keeps its peak size unless `trim()` is called
or an automatic `TrimPolicy` is configured; see `TrimPolicy`.
*/

final class DiffRenderContext[-M](mc: MiscCallback[M], initialBufferSize: Int, savedBuffer: ByteBuffer, trimPolicy: TrimPolicy)
  extends StatefulRenderContext[M] {

  /** Compatible constructor: manual trim policy. */
  def this(mc: MiscCallback[M], initialBufferSize: Int, savedBuffer: ByteBuffer) =
    this(mc, initialBufferSize, savedBuffer, TrimPolicy.Manual)

  if ((initialBufferSize < 2) || ((initialBufferSize & (initialBufferSize - 1)) != 0))
    throw RenderException.invalidArgument("initialBufferSize should be a power of two of at least 2")
  if (initialBufferSize > MaxBufferSize)
    throw RenderException.invalidArgument(s"initialBufferSize exceeds $MaxBufferSize")

  private val idb = IdBuilder(MaxDepth + 1)
  private val hashStack = new HashBuilder(MaxDepth + 1)
  private var attrsOpened = false

  // Lifecycle flags (see the format comment above).
  private var lhsFinalized = false
  private var rhsFinalized = true
  private var lhsStale = false

  private var buffer: ByteBuffer = _
  private var lhs: ByteBuffer = _
  private var rhs: ByteBuffer = _

  // Consecutive publishes whose baseline used little of its half (automatic trim policy).
  private var idleSwaps = 0

  if (savedBuffer != null) restore(savedBuffer)
  else resizeBuffer(0)

  def openNode(xmlns: XmlNs, name: String): Unit = {
    NameValidator.checkTagName(name)
    ensureWritable()
    closeAttrs()
    requestResize(OpOpenSize + name.length * 2)
    // Both id checks run before either counter moves, so a failure leaves ids untouched.
    idb.ensureCanDescend()
    idb.incId()
    idb.incLevel()
    attrsOpened = true
    lhs.put(OpOpen.toByte)
    hashStack.open(lhs)
    lhs.putInt(0) // sum
    lhs.putInt(0) // length
    lhs.put(xmlns.code)
    lhs.putInt(Hashing.nameHash(name))
    StringHelper.putToBuffer(lhs, name, false)
  }

  def closeNode(name: String): Unit = {
    ensureWritable()
    if (!hashStack.nonEmpty)
      throw RenderException.invalidState("closeNode without a matching openNode")
    closeAttrs()
    requestResize(OpSize)
    idb.decLevel()
    lhs.put(OpClose.toByte)
    hashStack.close(lhs)
  }

  private def setAttrOrStyle(xmlNs: Byte, name: String, value: String, isStyle: Byte): Unit = {
    if (isStyle == 1) NameValidator.checkStyleName(name)
    else NameValidator.checkAttrName(name)
    checkValueLength(value, "attribute value")
    ensureWritable()
    if (!attrsOpened)
      throw RenderException.invalidState("setAttr/setStyle is only allowed directly after openNode")
    requestResize(OpAttrSize + name.length * 2 + value.length * 2)
    lhs.put(OpAttr.toByte)
    lhs.put(xmlNs)
    lhs.putInt(Hashing.nameHash(name))
    StringHelper.putToBuffer(lhs, name, false)
    lhs.put(isStyle)
    lhs.putInt(Hashing.valueHash(value))
    StringHelper.putToBuffer(lhs, value, true)
  }

  def setAttr(xmlNs: XmlNs, name: String, value: String): Unit =
    setAttrOrStyle(xmlNs.code, name, value, 0.toByte)

  def setStyle(name: String, value: String): Unit =
    setAttrOrStyle(0.toByte, name, value, 1.toByte)

  def addTextNode(text: String): Unit = {
    checkValueLength(text, "text")
    ensureWritable()
    closeAttrs()
    requestResize(OpTextSize + text.length * 2)
    idb.incId()
    val p = lhs.position()
    lhs.put(OpText.toByte)
    lhs.putInt(Hashing.valueHash(text)) // string hash
    StringHelper.putToBuffer(lhs, text, true)
    hashStack.hashText(lhs, p)
  }

  def addMisc(misc: M): Unit = {
    if (!idb.hasParent)
      throw RenderException.invalidState("addMisc is only allowed inside a node")
    idb.decLevelTmp()
    try mc(idb.mkId, misc)
    finally idb.incLevel()
  }

  /**
    * Swap buffers: the finalized document becomes the baseline. The buffer
    * that held the old baseline is cleared by the next render operation.
    * Content is not touched here, so `swap(); diff(); swap()` can replay a
    * diff in the other direction.
    */
  def swap(): Unit = {
    if (!lhsFinalized) {
      if (lhs.position() > 0 || hashStack.nonEmpty)
        throw RenderException.invalidState("swap() while a document is under construction; call finalizeDocument() or reset() first")
      // An empty construction buffer reads as an empty document.
      lhs.flip()
      lhsFinalized = true
    }
    val t = rhs
    rhs = lhs
    lhs = t
    val f = rhsFinalized
    rhsFinalized = lhsFinalized
    lhsFinalized = f
    lhsStale = true
    trimPolicy match {
      case TrimPolicy.Automatic(after, slack) =>
        // Shrink only after `after` consecutive small baselines, so one small render between large ones does not reallocate.
        if (rhs.limit().toLong * slack < lhs.capacity()) {
          idleSwaps += 1
          if (idleSwaps >= after) { trim(); idleSwaps = 0 }
        } else idleSwaps = 0
      case TrimPolicy.Manual => ()
    }
  }

  /** Capacity of the backing buffer (both halves), in bytes. */
  def bufferCapacity: Int = buffer.capacity()

  /** Bytes used by the current baseline. */
  def baselineSize: Int = rhs.limit()

  /**
    * Shrinks the backing buffer to the smallest power of two that holds the
    * baseline twice over (and at least `initialBufferSize`). Allowed between
    * render cycles only, when nothing is under construction. The baseline is
    * copied; the construction buffer starts empty. Returns true when the
    * buffer was reallocated.
    */
  def trim(): Boolean = {
    if (!lhsStale && !lhsFinalized && (lhs.position() > 0 || hashStack.nonEmpty))
      throw RenderException.invalidState("trim() while a document is under construction")
    if (lhsFinalized && !lhsStale)
      throw RenderException.invalidState("trim() is only allowed after swap() or reset()")
    val used = rhs.limit().toLong
    // The growth seed is at least 1 so the loop terminates for every accepted capacity.
    var half = Math.max(1L, (initialBufferSize / 2).toLong)
    while (half < used * 2) half *= 2
    val newSize = (half * 2).toInt
    if (newSize >= buffer.capacity()) false
    else {
      val old = rhs
      buffer = ByteBuffer.allocate(newSize).order(ByteOrder.nativeOrder())
      buffer.limit(newSize / 2)
      lhs = buffer.slice().order(ByteOrder.nativeOrder())
      buffer.position(newSize / 2)
      buffer.limit(newSize)
      rhs = buffer.slice().order(ByteOrder.nativeOrder())
      old.position(0)
      rhs.put(old)
      rhs.flip()
      prepareLhs()
      true
    }
  }

  /**
    * Discard whatever `lhs` holds and restore a clean construction state.
    * The baseline (`rhs`) is untouched. Call it after an exception during
    * rendering before rendering again.
    */
  def reset(): Unit = prepareLhs()

  private def prepareLhs(): Unit = {
    lhs.clear()
    idb.reset()
    hashStack.clear()
    attrsOpened = false
    lhsFinalized = false
    lhsStale = false
  }

  private def ensureWritable(): Unit =
    if (lhsStale) prepareLhs()
    else if (lhsFinalized)
      throw RenderException.invalidState("document is finalized; call swap() or reset() before rendering again")

  /**
    * Snapshot of both buffers in saved-buffer format version 2. Only used
    * regions are copied. Allowed between render cycles: when `lhs` is
    * finalized, stale (just swapped out) or empty; a stale buffer is saved
    * as an empty construction buffer. The returned buffer is owned by the
    * caller.
    *
    * Format: `AVCT` magic, version byte, byte-order byte (0 little, 1 big),
    * lhs-finalized byte, reserved byte, lhs length, rhs length, lhs bytes,
    * rhs bytes. Integers use the recorded byte order.
    */
  def save(): ByteBuffer = {
    if (!lhsFinalized && (lhs.position() > 0 || hashStack.nonEmpty))
      throw RenderException.invalidState("save() is only allowed when the document is finalized or empty")
    val lhsFinalizedLive = lhsFinalized && !lhsStale
    val lhsLen = if (lhsFinalizedLive) lhs.limit() else 0
    val rhsLen = rhs.limit()
    val out = ByteBuffer.allocate(SavedHeaderSize + lhsLen + rhsLen).order(ByteOrder.nativeOrder())
    out.put(SavedMagic)
    out.put(SavedFormatVersion.toByte)
    out.put(if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) 1.toByte else 0.toByte)
    out.put(if (lhsFinalizedLive) 1.toByte else 0.toByte)
    out.put(0.toByte)
    out.putInt(lhsLen)
    out.putInt(rhsLen)
    copyRegion(lhs, lhsLen, out)
    copyRegion(rhs, rhsLen, out)
    out.rewind()
    out
  }

  private def copyRegion(source: ByteBuffer, length: Int, target: ByteBuffer): Unit = {
    // `duplicate` keeps the live buffer's position and limit untouched.
    val view = source.duplicate()
    view.position(0)
    view.limit(length)
    target.put(view)
  }

  /**
    * Restores a snapshot made by [[save]]. The source is copied; its position
    * and limit are left unchanged. Both tapes are validated before they are
    * adopted, so a malformed snapshot fails here instead of corrupting a
    * later diff.
    */
  private def restore(saved: ByteBuffer): Unit = {
    val src = saved.duplicate()
    src.clear()
    if (src.remaining() < SavedHeaderSize)
      throw RenderException.invalidSavedBuffer("shorter than the header")
    var i = 0
    while (i < SavedMagic.length) {
      if (src.get() != SavedMagic(i)) throw RenderException.invalidSavedBuffer("bad magic")
      i += 1
    }
    val version = src.get()
    if (version != SavedFormatVersion)
      throw RenderException.invalidSavedBuffer(s"unsupported format version $version")
    val order = if (src.get() == 1.toByte) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
    if (order != ByteOrder.nativeOrder())
      throw RenderException.invalidSavedBuffer("byte order does not match this platform")
    src.order(order)
    val finalizedFlag = src.get()
    if (finalizedFlag != 0 && finalizedFlag != 1)
      throw RenderException.invalidSavedBuffer("invalid lhs state flag")
    src.get() // reserved
    val lhsLen = src.getInt()
    val rhsLen = src.getInt()
    if (lhsLen < 0 || rhsLen < 0 || lhsLen.toLong + rhsLen != src.remaining())
      throw RenderException.invalidSavedBuffer("region lengths do not match the buffer size")
    if (finalizedFlag == 0 && lhsLen != 0)
      throw RenderException.invalidSavedBuffer("unfinalized lhs with content")

    val half = {
      var h = Math.max(1L, (initialBufferSize / 2).toLong)
      val needed = Math.max(lhsLen, rhsLen).toLong
      while (h < needed) h *= 2
      if (h * 2 > MaxBufferSize) throw RenderException.limit(s"saved buffer exceeds $MaxBufferSize bytes")
      h.toInt
    }
    buffer = ByteBuffer.allocate(half * 2).order(ByteOrder.nativeOrder())
    buffer.limit(half)
    lhs = buffer.slice().order(ByteOrder.nativeOrder())
    buffer.position(half)
    buffer.limit(half * 2)
    rhs = buffer.slice().order(ByteOrder.nativeOrder())

    src.limit(src.position() + lhsLen)
    lhs.put(src)
    src.limit(src.position() + rhsLen)
    rhs.put(src)

    TapeValidator.validate(lhs, 0, lhsLen, MaxDepth, NameValidator.MaxNameLength * 2)
    TapeValidator.validate(rhs, 0, rhsLen, MaxDepth, NameValidator.MaxNameLength * 2)

    lhs.position(0)
    if (finalizedFlag == 1) lhs.limit(lhsLen) else lhs.limit(half)
    rhs.position(0)
    rhs.limit(rhsLen)
    lhsFinalized = finalizedFlag == 1
    rhsFinalized = true
    lhsStale = false
  }

  def finalizeDocument(): Unit = {
    if (lhsStale) prepareLhs()
    if (lhsFinalized)
      throw RenderException.invalidState("document already finalized")
    if (hashStack.nonEmpty)
      throw RenderException.invalidState(s"finalizeDocument() with ${hashStack.depth} unclosed node(s)")
    lhs.flip()
    idb.reset()
    lhsFinalized = true
  }

  /**
    * Emits the operations that turn the baseline into the finalized document.
    * Both buffers are only read, so `diff` can be repeated. If the performer
    * throws, the buffers and ids are restored and the exception propagates.
    */
  def diff(performer: FastChangesPerformer): Unit = {
    if (!lhsFinalized)
      throw RenderException.invalidState("finalizeDocument() must be called before diff()")
    lhs.rewind()
    rhs.rewind()
    idb.reset()
    try diffLoop(performer)
    finally {
      lhs.rewind()
      rhs.rewind()
      // Make `DiffRenderContext` safe to reuse after diff.
      // (Rendering expects IdBuilder to start from a clean state.)
      idb.reset()
    }
  }

  private def diffLoop(performer: FastChangesPerformer): Unit = {

    // `diff` mutates IdBuilder while traversing the tree.
    //
    // In some edge cases (eg when one side hits OpEnd at the root) we can end up calling
    // `decLevel()` while already at the root level, which underflows the IdBuilder and
    // breaks the next render cycle (eg `openNode()` will throw IndexOutOfBoundsException).
    //
    // Keep this helper local to `diff` to avoid changing the rest of the render pipeline.
    def decLevelSafely(): Unit =
      if (idb.hasParent) idb.decLevel()

    while (lhs.hasRemaining()) {
      val opA = getOp(lhs)
      val opB = getOp(rhs)
      if (opA == OpOpen && opB == OpOpen) {
        idb.incId()
        val startA = lhs.position()
        val startB = rhs.position()
        val sumA = lhs.getInt()
        val endA = startA + lhs.getInt()
        val sumB = rhs.getInt()
        val endB = startB + rhs.getInt()
        if (sumA == sumB && regionsEqual(lhs, startA, endA - startA, rhs, startB, endB - startB)) {
          // Byte-identical subtrees: nothing changed below this node.
          lhs.position(endA)
          rhs.position(endB)
        } else {
          val aXmlNs = lhs.get
          val aTagHash = lhs.getInt
          val aTagLength = StringHelper.readNameLength(lhs)
          val aTagOffset = lhs.position()
          lhs.position(aTagOffset + aTagLength)
          val bXmlNs = rhs.get
          val bTagHash = rhs.getInt
          val bTagLength = StringHelper.readNameLength(rhs)
          val bTagOffset = rhs.position()
          rhs.position(bTagOffset + bTagLength)
          val sameTag =
            aXmlNs == bXmlNs && aTagHash == bTagHash &&
              regionsEqual(lhs, aTagOffset, aTagLength, rhs, bTagOffset, bTagLength)
          if (!sameTag) {
            performer.create(idb, XmlNs.fromCode(aXmlNs), lhs, aTagOffset, aTagLength)
            rhs.position(endB)
            idb.incLevel()
            createLoop(lhs, performer)
            idb.decLevel()
          } else {
            compareAttrs(performer)
            idb.incLevel()
          }
        }
      } else if (opA == OpText && opB == OpText) {
        idb.incId()
        if (!compareTexts()) {
          skipText(rhs)
          lhs.getInt // hash
          val textLength = lhs.getInt()
          val textOffset = lhs.position()
          lhs.position(textOffset + textLength)
          performer.createText(idb, lhs, textOffset, textLength)
        } else {
          skipText(lhs)
          skipText(rhs)
        }
      } else if (opA == OpText && opB == OpOpen) {
        lhs.getInt // hash
        val aTextLength = lhs.getInt()
        val aTextOffset = lhs.position()
        lhs.position(aTextOffset + aTextLength)
        idb.incId()
        performer.createText(idb, lhs, aTextOffset, aTextLength)
        skipNode(rhs)
      } else if (opA == OpOpen && opB == OpText) {
        lhs.getInt // sum
        lhs.getInt // length
        val aXmlNs = lhs.get
        val aTagHash = lhs.getInt
        val aTagLength = StringHelper.readNameLength(lhs)
        val aTagOffset = lhs.position()
        lhs.position(aTagOffset + aTagLength)
        idb.incId()
        skipText(rhs)
        performer.create(idb, XmlNs.fromCode(aXmlNs), lhs, aTagOffset, aTagLength)
        idb.incLevel()
        createLoop(lhs, performer)
        idb.decLevel()
      } else if (opA == OpClose && opB == OpClose) {
        idb.decLevel()
      } else if ((opA == OpClose || opA == OpEnd) && opB != OpClose && opB != OpEnd) {
        unOp(rhs)
        deleteLoop(rhs, performer)
        decLevelSafely()
      } else if (opA != OpClose && opA != OpEnd && (opB == OpClose || opB == OpEnd)) {
        unOp(lhs)
        createLoop(lhs, performer)
        decLevelSafely()
      } else {
        // Both tapes were validated on construction, so this means an engine defect, not bad input.
        throw RenderException.invalidState(s"unexpected operations $opA/$opB at ${lhs.position()}/${rhs.position()}")
      }
    }
  }

  def currentId: Id = idb.mkId

  /** Test seam: content hash of the first root node of the finalized document. Read-only. */
  private[avocet] def finalizedRootSum: Int = {
    if (!lhsFinalized) throw RenderException.invalidState("document is not finalized")
    lhs.getInt(1)
  }

  def currentContainerId: Id = {
    idb.decLevelTmp()
    val id = idb.mkId
    idb.incLevel()
    id
  }

  def subsequentId: Id = {
    idb.incId()
    val id = idb.mkId
    idb.decId()
    id
  }

  private def closeAttrs(): Unit = {
    if (attrsOpened) {
      hashStack.hashAttrs(lhs)
      attrsOpened = false
      requestResize(OpSize)
      lhs.put(OpLastAttr.toByte)
    }
  }

  private def checkValueLength(value: String, kind: String): Unit =
    if (value.length > MaxTextLength)
      throw RenderException.limit(s"$kind length ${value.length} exceeds $MaxTextLength chars")

  private def getOp(x: ByteBuffer): Byte = {
    if (x.hasRemaining) x.get()
    else OpEnd.toByte
  }

  /** Skips a node whose OPEN byte has already been consumed. */
  private def skipNode(x: ByteBuffer): Unit = {
    val start = x.position()
    x.getInt() // sum
    x.position(start + x.getInt())
  }

  private def skipText(x: ByteBuffer): Unit = {
    x.getInt // hash
    skipString(x)
  }

  private def skipString(x: ByteBuffer): Unit = {
    val len = x.getInt()
    x.position(x.position() + len)
  }

  private def skipAttrText(x: ByteBuffer): Unit = {
    x.getInt() // hash
    skipString(x)
  }

  /** true is further is attr; false if end of list */
  private def checkAttr(x: ByteBuffer): Boolean = {
    getOp(x) == OpAttr
  }

  private def readIsStyle(x: ByteBuffer) =
    x.get() == 1

  private def unOp(x: ByteBuffer) = {
    x.position(x.position() - 1)
  }

  private def createLoop(x: ByteBuffer, performer: FastChangesPerformer): Unit = {
    val startLevel = idb.getLevel
    var continue = true
    while (continue) {
      val op = getOp(x)
      (op: @switch) match {
        case OpClose | OpEnd =>
          if (idb.getLevel == startLevel) continue = false
          else idb.decLevel()
        case OpAttr =>
          idb.decLevelTmp()
          val attrXmlNs = x.get
          val attrNameHash = x.getInt
          val attrNameLength = StringHelper.readNameLength(x)
          val attrNameOffset = x.position()
          x.position(attrNameOffset + attrNameLength)
          val isStyle = readIsStyle(x)
          val attrValueHash = x.getInt()
          val attrValueLength = x.getInt()
          val attrValueOffset = x.position()
          x.position(attrValueOffset + attrValueLength)
          if (isStyle) performer.setStyle(idb, x, attrNameOffset, attrNameLength, attrValueOffset, attrValueLength)
          else performer.setAttr(idb, XmlNs.fromCode(attrXmlNs), x, attrNameOffset, attrNameLength, attrValueOffset, attrValueLength)
          idb.incLevel()
        case OpText =>
          idb.incId()
          x.getInt() // hash
          val textLength = x.getInt()
          val textOffset = x.position()
          x.position(textOffset + textLength)
          performer.createText(idb, x, textOffset, textLength)
        case OpOpen =>
          idb.incId()
          x.getInt() // sum
          x.getInt() // length
          val aXmlNs = x.get
          val aTagHash = x.getInt
          val aTagLength = StringHelper.readNameLength(x)
          val aTagOffset = x.position()
          x.position(aTagOffset + aTagLength)
          performer.create(idb, XmlNs.fromCode(aXmlNs), x, aTagOffset, aTagLength)
          idb.incLevel()
        case OpLastAttr => // do nothing
      }
    }
  }

  private def deleteLoop(x: ByteBuffer, performer: FastChangesPerformer): Unit = {
    var continue = true
    while (continue) {
      (getOp(x): @switch) match {
        case OpOpen =>
          skipNode(x)
          idb.incId()
          performer.remove(idb)
        case OpText =>
          skipText(x)
          idb.incId()
          performer.remove(idb)
        case OpClose | OpEnd => continue = false
      }
    }
  }

  /** Exact comparison of two encoded regions. */
  private def regionsEqual(a: ByteBuffer, aOffset: Int, aLength: Int, b: ByteBuffer, bOffset: Int, bLength: Int): Boolean = {
    if (aLength != bLength) false
    else {
      var i = 0
      var equal = true
      while (equal && i < aLength) {
        equal = a.get(aOffset + i) == b.get(bOffset + i)
        i += 1
      }
      equal
    }
  }

  /** Reads an attribute header on `x` and returns the name offset; the cursor stops after `isStyle`. */
  private def sameAttrName(aXmlNs: Byte, aNameHash: Int, aNameOffset: Int, aNameLength: Int, aIsStyle: Boolean,
                           bXmlNs: Byte, bNameHash: Int, bNameOffset: Int, bNameLength: Int, bIsStyle: Boolean): Boolean =
    aIsStyle == bIsStyle && aXmlNs == bXmlNs && aNameHash == bNameHash &&
      regionsEqual(lhs, aNameOffset, aNameLength, rhs, bNameOffset, bNameLength)

  private def compareAttrs(performer: FastChangesPerformer): Unit = {
    // O(n*m) implementation
    // Real word test shows that this implementation is faster.
    // -----
    val startPosA = lhs.position()
    val startPosB = rhs.position()
    // Check the attrs were removed
    while (checkAttr(rhs)) {
      // Read attr name from rhs
      val bXmlNs = rhs.get()
      val bNameHash = rhs.getInt()
      val bNameLength = StringHelper.readNameLength(rhs)
      val bNameOffset = rhs.position()
      rhs.position(bNameOffset + bNameLength)
      val isStyleB = readIsStyle(rhs)
      var needToRemove = true
      skipAttrText(rhs)
      lhs.position(startPosA)
      while (needToRemove && checkAttr(lhs)) {
        val aXmlNs = lhs.get()
        val aNameHash = lhs.getInt()
        val aNameLength = StringHelper.readNameLength(lhs)
        val aNameOffset = lhs.position()
        lhs.position(aNameOffset + aNameLength)
        val isStyleA = readIsStyle(lhs)
        skipAttrText(lhs)
        if (sameAttrName(aXmlNs, aNameHash, aNameOffset, aNameLength, isStyleA, bXmlNs, bNameHash, bNameOffset, bNameLength, isStyleB))
          needToRemove = false
      }
      if (needToRemove) {
        if (isStyleB) performer.removeStyle(idb, rhs, bNameOffset, bNameLength)
        else performer.removeAttr(idb, XmlNs.fromCode(bXmlNs), rhs, bNameOffset, bNameLength)
      }
    }
    // Check the attrs were added or changed
    val endPosB = rhs.position()
    lhs.position(startPosA)
    while (checkAttr(lhs)) {
      val aXmlNs = lhs.get()
      val aNameHash = lhs.getInt()
      val aNameLength = StringHelper.readNameLength(lhs)
      val aNameOffset = lhs.position()
      lhs.position(aNameOffset + aNameLength)
      val isStyleA = readIsStyle(lhs)
      val aValueHash = lhs.getInt()
      val aValueLength = lhs.getInt()
      val aValueOffset = lhs.position()
      var needToSet = true
      var searching = true
      rhs.position(startPosB)
      while (searching && checkAttr(rhs)) {
        val bXmlNs = rhs.get()
        val bNameHash = rhs.getInt()
        val bNameLength = StringHelper.readNameLength(rhs)
        val bNameOffset = rhs.position()
        rhs.position(bNameOffset + bNameLength)
        val isStyleB = readIsStyle(rhs)
        val bValueHash = rhs.getInt()
        val bValueLength = rhs.getInt()
        val bValueOffset = rhs.position()
        rhs.position(bValueOffset + bValueLength)
        if (sameAttrName(aXmlNs, aNameHash, aNameOffset, aNameLength, isStyleA, bXmlNs, bNameHash, bNameOffset, bNameLength, isStyleB)) {
          // Same attribute in the baseline: set it only if the value really differs.
          searching = false
          needToSet = !(aValueHash == bValueHash &&
            regionsEqual(lhs, aValueOffset, aValueLength, rhs, bValueOffset, bValueLength))
        }
      }
      lhs.position(aValueOffset + aValueLength)
      if (needToSet) {
        if (isStyleA) performer.setStyle(idb, lhs, aNameOffset, aNameLength, aValueOffset, aValueLength)
        else performer.setAttr(idb, XmlNs.fromCode(aXmlNs), lhs, aNameOffset, aNameLength, aValueOffset, aValueLength)
      }
    }
    rhs.position(endPosB)
  }

  /** Exact text comparison; cursors are left at the TEXT hash field. */
  private def compareTexts(): Boolean = {
    val startPosA = lhs.position()
    val startPosB = rhs.position()
    val aHash = lhs.getInt()
    val aLength = lhs.getInt()
    val bHash = rhs.getInt()
    val bLength = rhs.getInt()
    val equal = aHash == bHash && regionsEqual(lhs, lhs.position(), aLength, rhs, rhs.position(), bLength)
    lhs.position(startPosA)
    rhs.position(startPosB)
    equal
  }

  /* Check write to lhs is available */
  private def requestResize(additionalSize: Int) = {
    if (additionalSize > lhs.remaining)
      resizeBuffer(additionalSize)
  }

  private def resizeBuffer(additionalSize: Int) = {
    // The active half must hold its current content plus the pending write;
    // enough total capacity is not enough on its own.
    val content = if (lhs == null) 0L else lhs.position().toLong
    val neededHalf = content + additionalSize
    var size: Long = if (buffer == null) initialBufferSize.toLong else buffer.capacity.toLong
    while (size / 2 < neededHalf) size *= 2
    if (size > MaxBufferSize)
      throw RenderException.limit(s"render buffer would exceed $MaxBufferSize bytes")
    val newSize = size.toInt

    buffer = ByteBuffer.allocate(newSize)
    buffer.order(ByteOrder.nativeOrder())

    lhs = {
      buffer.limit(newSize / 2)
      val buff = buffer.slice().order(ByteOrder.nativeOrder())
      if (lhs != null) {
        lhs.flip()
        buff.put(lhs)
      }
      buff
    }

    rhs = {
      buffer.position(newSize / 2)
      buffer.limit(buffer.capacity)
      val buff = buffer.slice().order(ByteOrder.nativeOrder())
      if (rhs != null) {
        rhs.position(0)
        buff.put(rhs)
      }
      buff.flip()
      buff
    }
  }
}

object DiffRenderContext {

  final val DefaultDiffRenderContextBufferSize = 1024 * 64

  /** Deepest supported element nesting; the root element has depth 1. */
  final val MaxDepth = 255

  /** Longest supported text node or attribute/style value, in UTF-16 chars. */
  final val MaxTextLength = 1 << 24

  /** Largest backing buffer (both halves together), in bytes. */
  final val MaxBufferSize = 1 << 30

  private[impl] val SavedMagic: Array[Byte] = Array('A'.toByte, 'V'.toByte, 'C'.toByte, 'T'.toByte)
  private[impl] final val SavedFormatVersion = 2
  private[impl] final val SavedHeaderSize = 16

  def apply[MiscType](
    onMisc: MiscCallback[MiscType] = (_: Id, _: MiscType) => (),
    initialBufferSize: Int = DefaultDiffRenderContextBufferSize,
    savedBuffer: Option[ByteBuffer] = None,
    trimPolicy: TrimPolicy = TrimPolicy.Manual
  ): DiffRenderContext[MiscType] = {
    new DiffRenderContext[MiscType](onMisc, initialBufferSize, savedBuffer.orNull, trimPolicy)
  }

  /**
    * The 1.0.0 factory signature, kept for binary compatibility: a consumer
    * compiled against 1.0.0 calls this JVM method. Source code resolves to
    * the overload above, which has the defaults.
    */
  def apply[MiscType](
    onMisc: MiscCallback[MiscType],
    initialBufferSize: Int,
    savedBuffer: Option[ByteBuffer]
  ): DiffRenderContext[MiscType] =
    new DiffRenderContext[MiscType](onMisc, initialBufferSize, savedBuffer.orNull, TrimPolicy.Manual)

  /**
    * Fast performer. `id` and `source` are borrowed for the duration of the
    * call: `id` is the engine's mutable id builder and `source` is the live
    * tape. Copy what you need (`id.mkId`, the chars at the offsets) before
    * returning; never retain the references.
    */
  trait FastChangesPerformer {
    def remove(id: FastId): Unit
    def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit
    def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit
    def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit
    def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit
    def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit
    def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit
  }

  /**
    * Convenience performer that decodes names and values into owned strings.
    * `id` is still borrowed (see [[FastChangesPerformer]]).
    */
  trait ChangesPerformer extends FastChangesPerformer {
    def removeAttr(id: FastId, xmlNs: String, name: String): Unit
    def removeStyle(id: FastId, name: String): Unit
    def setAttr(id: FastId, xmlNs: String, name: String, value: String): Unit
    def setStyle(id: FastId, name: String, value: String): Unit
    def createText(id: FastId, text: String): Unit
    def create(id: FastId, xmlNs: String, tag: String): Unit

    // Fast impl

    private def stringFromSource(source: ByteBuffer, offset: Int, length: Int) = {
      val sb = new scala.collection.mutable.StringBuilder()
      StringHelper.appendFromSource(source, sb, offset, length)
      sb.result()
    }

    def create(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
      create(id, xmlNs.uri, stringFromSource(source, nameOffset, nameLength))
    def removeAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
      removeAttr(id, xmlNs.uri, stringFromSource(source, nameOffset, nameLength))
    def removeStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int): Unit =
      removeStyle(id, stringFromSource(source, nameOffset, nameLength))
    def setAttr(id: FastId, xmlNs: XmlNs, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit = {
      val name = stringFromSource(source, nameOffset, nameLength)
      val value = stringFromSource(source, valueOffset, valueLength)
      setAttr(id, xmlNs.uri, name, value)
    }
    def setStyle(id: FastId, source: ByteBuffer, nameOffset: Int, nameLength: Int, valueOffset: Int, valueLength: Int): Unit =
      setStyle(id, stringFromSource(source, nameOffset, nameLength), stringFromSource(source, valueOffset, valueLength))
    def createText(id: FastId, source: ByteBuffer, offset: Int, length: Int): Unit =
      createText(id, stringFromSource(source, offset, length))
  }

  object DummyChangesPerformer extends ChangesPerformer {
    def removeAttr(id: FastId, xmlNs: String, name: String): Unit = ()
    def removeStyle(id: FastId, name: String): Unit = ()
    def remove(id: FastId): Unit = ()
    def setAttr(id: FastId, name: String, xmlNs: String, value: String): Unit = ()
    def setStyle(id: FastId, name: String, value: String): Unit = ()
    def createText(id: FastId, text: String): Unit = ()
    def create(id: FastId, tag: String, xmlNs: String): Unit = ()
    // Fast API
    override def create(id: FastId,
                        xmlNs: XmlNs,
                        source: ByteBuffer,
                        nameOffset: Int,
                        nameLength: Int): Unit = ()
    override def removeAttr(id: FastId,
                            xmlNs: XmlNs,
                            source: ByteBuffer,
                            nameOffset: Int,
                            nameLength: Int): Unit = ()
    override def removeStyle(id: FastId,
                             source: ByteBuffer,
                             nameOffset: Int,
                             nameLength: Int): Unit = ()
    override def setAttr(id: FastId,
                         xmlNs: XmlNs,
                         source: ByteBuffer,
                         nameOffset: Int,
                         nameLength: Int,
                         valueOffset: Int,
                         valueLength: Int): Unit = ()
    override def setStyle(id: FastId,
                          source: ByteBuffer,
                          nameOffset: Int,
                          nameLength: Int,
                          valueOffset: Int,
                          valueLength: Int): Unit = ()
    override def createText(id: FastId,
                            source: ByteBuffer,
                            offset: Int,
                            length: Int): Unit = ()
  }

  type MiscCallback[MiscType] = (Id, MiscType) => _
}
