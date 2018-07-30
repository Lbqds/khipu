package khipu.store.trienode

import akka.actor.ActorSystem
import khipu.Hash
import khipu.store.datasource.KesqueDataSource
import khipu.util.cache.sync.Cache
import scala.collection.mutable

trait PruningNodeKeyValueStorage extends NodeKeyValueStorage {
  /**
   * Determines and prunes mpt nodes based on last pruned block number tag and the current best block number
   *
   * @param lastPruned      Last pruned block number tag
   * @param bestBlockNumber Current best block number
   * @return PruneResult
   */
  def prune(lastPruned: => Long, bestBlockNumber: => Long): PruneResult
}

// --- helping classes

sealed trait PruningMode
case object ArchivePruning extends PruningMode
final case class HistoryPruning(history: Int) extends PruningMode

object PruningMode {
  type PruneFn = (=> Long, => Long) => PruneResult

  /**
   * Create a NodeKeyValueStorage to be used within MerklePatriciaTrie
   *
   * @param blockNumber block number to be used as tag when doing update / removal operations. None can be sent if read only
   * @return Storage to be used
   */
  def nodeKeyValueStorage(pruningMode: PruningMode, nodeStorage: NodeStorage, nodeKeyValueCache: Cache[Hash, Array[Byte]])(blockNumber: Option[Long])(implicit system: ActorSystem): PruningNodeKeyValueStorage =
    pruningMode match {
      case ArchivePruning          => new CachedNodeStorage(nodeStorage, nodeKeyValueCache) // new ArchiveNodeStorage(nodeStorage) // new DistributedNodeStorage(nodeStorage) 
      case HistoryPruning(history) => new ReferenceCountNodeStorage(nodeStorage, history, blockNumber)
    }

  def nodeTableStorage(pruningMode: PruningMode, nodeStorage: NodeStorage, source: KesqueDataSource)(blockNumber: Option[Long])(implicit system: ActorSystem): PruningNodeKeyValueStorage =
    pruningMode match {
      case ArchivePruning          => new NodeTableStorage(source) // new ArchiveNodeStorage(nodeStorage) // new DistributedNodeStorage(nodeStorage) 
      case HistoryPruning(history) => new ReferenceCountNodeStorage(nodeStorage, history, blockNumber)
    }
}

final case class PruneResult(lastPrunedBlockNumber: Long, pruned: Int) {
  override def toString: String = s"Number of mpt nodes deleted: $pruned. Last Pruned Block: $lastPrunedBlockNumber"
}

trait RangePrune {
  /**
   * Prunes data between [start, end)
   * @param start block to prone
   * @param end block where to stop. This one will not be pruned
   * @param pruneFn function that given a certain block number prunes the data and returns how many nodes were deleted
   * @return resulting PruneResult
   */
  def pruneBetween(start: Long, end: Long, pruneFn: Long => Int): PruneResult = {
    //log.debug(s"Pruning start for range $start - $end")
    val prunedCount = (start until end).foldLeft(0) { (acc, bn) =>
      acc + pruneFn(bn)
    }
    val result = PruneResult(end - 1, prunedCount)
    //log.debug(s"Pruning finished for range $start - $end. $result.")
    result
  }
}
