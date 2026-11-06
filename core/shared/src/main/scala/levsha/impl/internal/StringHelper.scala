package avocet.impl.internal

import java.nio.ByteBuffer
import scala.collection.mutable
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets

trait StringHelper {
  def appendFromSource(source: ByteBuffer, target: mutable.StringBuilder, offset: Int, length: Int): Unit
  def putToBuffer(target: ByteBuffer, source: String, big: Boolean): Unit
}

object StringHelper extends StringHelperInstancePlatformSpecific

// Currently all platforms works with DefaultStringHelper
// Maybe will be changed in future
trait StringHelperInstancePlatformSpecific extends DefaultStringHelper

/**
  * Strings are stored as UTF-16 code units, two bytes per char. Every length
  * in the tape counts bytes, not chars. A name length is an unsigned 16-bit
  * value (`big = false`); a text or value length is a signed 32-bit value
  * (`big = true`).
  */
trait DefaultStringHelper extends StringHelper {

  /** Largest name length in bytes that fits the 16-bit length field. */
  final val MaxNameBytes = 0xFFFF

  def appendFromSource(source: ByteBuffer, target: mutable.StringBuilder, offset: Int, length: Int): Unit = {
    if (length > 0) {
      var i = 0
      while (i < length) {
        val c = source.getChar(offset + i)
        target.append(c)
        i += 2
      }
    }
  }

  def putToBuffer(target: ByteBuffer, source: String, big: Boolean): Unit = {
    val length = source.length
    val byteLength = length * 2
    if (big) target.putInt(byteLength)
    else {
      // Callers validate name length first; this guard keeps the tape sound even if they do not.
      if (byteLength > MaxNameBytes)
        throw avocet.RenderException.limit(s"name length $byteLength bytes exceeds $MaxNameBytes")
      target.putShort(byteLength.toShort)
    }
    if (length > 0) {
      var i = 0
      while (i < length) {
        target.putChar(source(i))
        i += 1
      }
    }
  }

  /** Reads an unsigned 16-bit name length. */
  def readNameLength(source: ByteBuffer): Int =
    source.getShort() & 0xFFFF
}
