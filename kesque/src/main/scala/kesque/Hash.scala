package kesque

import java.util.Arrays
import org.spongycastle.util.encoders.Hex

object Hash {
  val empty = Hash(Array[Byte]())
  def apply(): Hash = empty

  def intHash(bytes: Array[Byte]): Int = {
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

  def longHash(bytes: Array[Byte]): Long = {
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
}
final case class Hash(bytes: Array[Byte]) {
  def value = new java.math.BigInteger(1, bytes)

  def length = bytes.length
  def isEmpty = bytes.length == 0
  def nonEmpty = bytes.length != 0

  def hexString: String = Hex.toHexString(bytes)

  override def hashCode: Int = Hash.intHash(bytes)

  override def equals(any: Any) = {
    any match {
      case that: Hash => (this eq that) || Arrays.equals(this.bytes, that.bytes)
      case _          => false
    }
  }

  override def toString: String = hexString
}
