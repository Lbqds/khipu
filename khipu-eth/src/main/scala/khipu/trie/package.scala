package khipu

import akka.util.ByteString
import khipu.vm.UInt256

package object trie {

  trait ByteArrayEncoder[T] {
    def toBytes(input: T): Array[Byte]
  }

  trait ByteArrayDecoder[T] {
    def fromBytes(bytes: Array[Byte]): T
  }

  trait ByteArraySerializable[T] extends ByteArrayEncoder[T] with ByteArrayDecoder[T]

  // TODO not used anywhere ?
  private val byteStringSerializer = new ByteArraySerializable[ByteString] {
    override def toBytes(input: ByteString): Array[Byte] = input.toArray
    override def fromBytes(bytes: Array[Byte]): ByteString = ByteString(bytes)
  }

  val byteArraySerializable = new ByteArraySerializable[Array[Byte]] {
    override def toBytes(input: Array[Byte]): Array[Byte] = input
    override def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes
  }

  val rlpUInt256Serializer = new ByteArraySerializable[UInt256] {
    import khipu.rlp.UInt256RLPImplicits._
    // NOTE should rlp decode first before deser to UInt256, see UInt256RLPImplicits
    override def fromBytes(bytes: Array[Byte]): UInt256 = bytes.toUInt256
    override def toBytes(input: UInt256): Array[Byte] = input.toBytes
  }

  val hashUInt256Serializable = new ByteArrayEncoder[UInt256] {
    override def toBytes(input: UInt256): Array[Byte] = crypto.kec256(input.bytes.toArray)
  }

  def toHash(bytes: Array[Byte]): Array[Byte] = crypto.kec256(bytes)

  import khipu.rlp.RLPImplicits._
  val EmptyTrieHash = toHash(rlp.encode(Array.ofDim[Byte](0)))
}
