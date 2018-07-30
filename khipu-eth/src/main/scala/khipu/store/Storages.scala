package khipu.store

import akka.actor.ActorSystem
import khipu.store.datasource.DataSources
import khipu.store.trienode.NodeKeyValueStorage
import khipu.store.trienode.NodeStorage
import khipu.store.trienode.PruningMode

object Storages {

  trait DefaultStorages extends Storages with DataSources {
    // lazy val -- to wait for other fields (cache pruningMode etc) to be set in last implementation, 

    lazy val evmCodeStorage = new EvmCodeStorage(evmCodeDataSource)
    lazy val accountNodeStorageFor: (Option[Long]) => NodeKeyValueStorage = bn => PruningMode.nodeTableStorage(pruningMode, nodeStorage, accountNodeDataSource)(bn)
    lazy val storageNodeStorageFor: (Option[Long]) => NodeKeyValueStorage = bn => PruningMode.nodeTableStorage(pruningMode, nodeStorage, storageNodeDataSource)(bn)
    //lazy val accountNodeStorageFor: (Option[Long]) => NodeKeyValueStorage = bn => PruningMode.nodeKeyValueStorage(pruningMode, nodeStorage, nodeKeyValueCache)(bn)
    //lazy val storageNodeStorageFor: (Option[Long]) => NodeKeyValueStorage = bn => PruningMode.nodeKeyValueStorage(pruningMode, nodeStorage, nodeKeyValueCache)(bn)

    lazy val blockHeadersStorage = new BlockHeadersStorage(blockHeadersDataSource)
    lazy val blockBodiesStorage = new BlockBodiesStorage(blockBodiesDataSource)
    lazy val blockNumberMappingStorage = new BlockNumberMappingStorage(blockHeightsHashesDataSource, blockNumberCache)
    lazy val receiptsStorage = new ReceiptsStorage(receiptsDataSource)

    lazy val totalDifficultyStorage = new TotalDifficultyStorage(totalDifficultyDataSource)
    lazy val transactionMappingStorage = new TransactionMappingStorage(transactionMappingDataSource)
    lazy val nodeStorage = new NodeStorage(mptDataSource)

    lazy val fastSyncStateStorage = new FastSyncStateStorage(fastSyncStateDataSource)
    lazy val appStateStorage = new AppStateStorage(appStateDataSource, PruningMode.nodeKeyValueStorage(pruningMode, nodeStorage, nodeKeyValueCache)(None).prune)
    lazy val knownNodesStorage = new KnownNodesStorage(knownNodesDataSource)
  }
}

trait Storages extends BlockchainStorages {
  implicit protected val system: ActorSystem

  def appStateStorage: AppStateStorage
  def fastSyncStateStorage: FastSyncStateStorage
  def knownNodesStorage: KnownNodesStorage
  def pruningMode: PruningMode
}

