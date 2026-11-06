package avocet.impl

import java.nio.{ByteBuffer}
import avocet.RenderException
import scala.util.hashing.MurmurHash3

/**
  * Maintains the content hash (`sum`) and the length field of every open node
  * while a document is written to the tape.
  *
  * The length is stored relative to the node's `sum` field, so two equal
  * subtrees have byte-identical encodings wherever they sit in the buffer.
  */
class HashBuilder(private val posStack: Stack = new Stack(),
                  private var lastOpenPos: Int = 0) {

  /** Builds a hash stack able to hold `capacity` nested open nodes. */
  def this(capacity: Int) = this(new Stack(new Array[Int](capacity)), 0)

  def open(target: ByteBuffer): Unit = {
    val pos = target.position()
    posStack.push(pos)
    lastOpenPos = pos + 8
  }

  def hashAttrs(target: ByteBuffer) = if (posStack.nonEmpty) {
    val nodeStart = posStack.peek
    val attrsEnd = target.position()
    val size = attrsEnd - lastOpenPos
    // Hash content of the last tag
    var i = lastOpenPos
    var hash = target.getInt(nodeStart)
    while (i < attrsEnd) {
      hash = MurmurHash3.mix(hash, target.get(i) & 0xFF)
      i = i + 1
    }
    target.putInt(nodeStart, hash)
  }

  def hashText(target: ByteBuffer, textStart: Int) = if (posStack.nonEmpty) {
    val nodeStart = posStack.peek
    val textEnd = target.position()
    val size = textEnd - lastOpenPos
    // Hash content of the last tag
    var i = textStart
    var hash = target.getInt(nodeStart)
    while (i < textEnd) {
      hash = MurmurHash3.mix(hash, target.get(i) & 0xFF)
      i = i + 1
    }
    target.putInt(nodeStart, hash)
  }

  def close(target: ByteBuffer) = {
    val nodeStartPos = posStack.pop()
    val nodeEndPos = target.position()
    val size = nodeEndPos - lastOpenPos
    // Hash content of the last tag
    var hash = target.getInt(nodeStartPos)
    // Save current hash to target
    hash = MurmurHash3.finalizeHash(hash, size)
    target.putInt(nodeStartPos, hash)
    // Length of the node relative to its `sum` field (position independent)
    target.putInt(nodeStartPos + 4, nodeEndPos - nodeStartPos)
    // Updated proper node hash
    if (posStack.nonEmpty) {
      val properHash = target.getInt(posStack.peek)
      target.putInt(posStack.peek, MurmurHash3.mix(properHash, hash))
    }
  }

  /** Number of currently open nodes. */
  def depth: Int = posStack.size

  def nonEmpty: Boolean = posStack.nonEmpty

  /** Forgets every open node. Used when a partially written document is discarded. */
  def clear(): Unit = {
    posStack.clear()
    lastOpenPos = 0
  }
}

private[impl] class Stack(val array: Array[Int] = new Array(256),
                    var i: Int = 0) {
  def push(v: Int): Unit = {
    if (i >= array.length)
      throw RenderException.limit(s"nesting depth exceeds ${array.length}")
    array(i) = v
    i = i + 1
  }

  def pop(): Int = {
    if (i <= 0)
      throw RenderException.invalidState("closeNode without a matching openNode")
    i = i - 1
    array(i)
  }

  def peek: Int =
    array(i - 1)

  def nonEmpty: Boolean =
    i > 0

  def size: Int = i

  def clear(): Unit =
    i = 0
}
/*
ul(0
  li(a), 10
  li(b), 20
  li(c), 30
  li(d), 40
)
*/
