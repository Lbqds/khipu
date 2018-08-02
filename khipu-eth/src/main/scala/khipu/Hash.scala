package khipu

import akka.util.ByteString

/**
 * Usually node's hash except in ReferenceCountNodeStorage's pruneKey. TODO
 */
object Hash {
  val empty = Hash(Array[Byte]())
  def apply(): Hash = empty

  trait I {
    val bytes: Array[Byte]

    final def intHash: Int = {
      val n = math.min(bytes.length, 4)
      var h = 0
      var i = 0
      while (i < n) {
        h <<= 8
        h |= (bytes(i) & 0xFF)
        i += 1
      }
      h
    }

    final def longHash: Long = {
      val n = math.min(bytes.length, 8)
      var h = 0L
      var i = 0
      while (i < n) {
        h <<= 8
        h |= (bytes(i) & 0xFF)
        i += 1
      }
      h
    }

    final def value = new java.math.BigInteger(1, bytes)

    final def length = bytes.length
    final def isEmpty = bytes.length == 0
    final def nonEmpty = bytes.length != 0

    final def toByteString = ByteString(bytes)
    final def hexString: String = khipu.toHexString(bytes)

    final override def hashCode: Int = intHash

    final override def equals(any: Any) = any match {
      case that: Hash => java.util.Arrays.equals(this.bytes, that.bytes)
      case _          => false
    }

    final override def toString: String = hexString
  }

}
final case class Hash(bytes: Array[Byte]) extends Hash.I