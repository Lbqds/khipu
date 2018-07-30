package khipu.store

import akka.util.ByteString
import boopickle.CompositePickler
import boopickle.Default._
import java.nio.ByteBuffer
import java.nio.ByteOrder
import khipu.Hash
import khipu.blockchain.sync
import khipu.util.BytesUtil.compactPickledBytes
import khipu.store.datasource.DataSource

object FastSyncStateStorage {
  val syncStateKey: String = "fast-sync-state"
}
final class FastSyncStateStorage(val source: DataSource) extends KeyValueStorage[String, sync.SyncState, FastSyncStateStorage] {
  import FastSyncStateStorage._
  implicit val byteOrder = ByteOrder.BIG_ENDIAN

  override val namespace: Array[Byte] = Namespaces.FastSyncStateNamespace

  //implicit val byteStringPickler: Pickler[ByteString] = transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray)
  //implicit val hashTypePickler: CompositePickler[sync.NodeHash] =
  //  compositePickler[sync.NodeHash]
  //    .addConcreteType[sync.StateMptNodeHash]
  //    .addConcreteType[sync.ContractStorageMptNodeHash]
  //    .addConcreteType[sync.StorageRootHash]
  //    .addConcreteType[sync.EvmcodeHash]

  override def keySerializer: String => Array[Byte] = _.getBytes
  override def valueSerializer: sync.SyncState => Array[Byte] =
    ss => {
      val builder = ByteString.newBuilder
      ss match {
        case sync.SyncState(targetBlockNumber, downloadedNodesCount, bestBlockHeaderNumber, mptNodesQueue, nonMptNodesQueue, blockBodiesQueue, receiptsQueue) =>
          builder.putLong(targetBlockNumber)
          builder.putInt(downloadedNodesCount)
          builder.putLong(bestBlockHeaderNumber)

          builder.putInt(mptNodesQueue.size)
          mptNodesQueue foreach { x =>
            builder.putByte(x.tpe)
            builder.putInt(x.bytes.length)
            builder.putBytes(x.bytes)
          }

          builder.putInt(nonMptNodesQueue.size)
          nonMptNodesQueue foreach { x =>
            builder.putByte(x.tpe)
            builder.putInt(x.bytes.length)
            builder.putBytes(x.bytes)
          }

          builder.putInt(blockBodiesQueue.size)
          blockBodiesQueue foreach { x =>
            builder.putInt(x.bytes.length)
            builder.putBytes(x.bytes)
          }

          builder.putInt(receiptsQueue.size)
          receiptsQueue foreach { x =>
            builder.putInt(x.bytes.length)
            builder.putBytes(x.bytes)
          }
      }
      builder.result.toArray
    }
  //compactPickledBytes(Pickle.intoBytes(ss)).toArray
  override def valueDeserializer: Array[Byte] => sync.SyncState =
    bytes => {
      if ((bytes eq null) || bytes.length == 0) {
        sync.SyncState(0)
      } else {

        val data = ByteString(bytes).iterator

        val targetBlockNumber = data.getLong
        val downloadedNodesCount = data.getInt
        val bestBlockHeaderNumber = data.getLong

        var queueSize = data.getInt
        var i = 0
        var mptNodesQueue = List[sync.NodeHash]()
        while (i < queueSize) {
          val tpe = data.getByte
          val length = data.getInt
          val bs = data.getBytes(length)
          val hash = tpe match {
            case sync.NodeHash.StateMptNode           => sync.StateMptNodeHash(bs)
            case sync.NodeHash.StorageRoot            => sync.StorageRootHash(bs)
            case sync.NodeHash.ContractStorageMptNode => sync.ContractStorageMptNodeHash(bs)
            case sync.NodeHash.Evmcode                => sync.EvmcodeHash(bs)
          }
          hash :: mptNodesQueue
          i += 1
        }

        queueSize = data.getInt
        i = 0
        var nonMptNodesQueue = List[sync.NodeHash]()
        while (i < queueSize) {
          val tpe = data.getByte
          val length = data.getInt
          val bs = data.getBytes(length)
          val hash = tpe match {
            case sync.NodeHash.StateMptNode           => sync.StateMptNodeHash(bs)
            case sync.NodeHash.StorageRoot            => sync.StorageRootHash(bs)
            case sync.NodeHash.ContractStorageMptNode => sync.ContractStorageMptNodeHash(bs)
            case sync.NodeHash.Evmcode                => sync.EvmcodeHash(bs)
          }
          hash :: nonMptNodesQueue
          i += 1
        }

        queueSize = data.getInt
        i = 0
        var blockBodiesQueue = List[Hash]()
        while (i < queueSize) {
          val length = data.getInt
          val bs = data.getBytes(length)
          Hash(bs) :: blockBodiesQueue
          i += 1
        }

        queueSize = data.getInt
        i = 0
        var receiptsQueue = List[Hash]()
        while (i < queueSize) {
          val length = data.getInt
          val bs = data.getBytes(length)
          Hash(bs) :: blockBodiesQueue
          i += 1
        }

        sync.SyncState(targetBlockNumber, downloadedNodesCount, bestBlockHeaderNumber, mptNodesQueue.reverse, nonMptNodesQueue.reverse, blockBodiesQueue.reverse, receiptsQueue.reverse)

      }
    }

  //Unpickle[sync.SyncState].fromBytes(ByteBuffer.wrap(bytes))

  protected def apply(dataSource: DataSource): FastSyncStateStorage = new FastSyncStateStorage(dataSource)

  def putSyncState(syncState: sync.SyncState): FastSyncStateStorage = put(syncStateKey, syncState)

  def getSyncState(): Option[sync.SyncState] = get(syncStateKey)

  def purge(): FastSyncStateStorage = remove(syncStateKey)

}
