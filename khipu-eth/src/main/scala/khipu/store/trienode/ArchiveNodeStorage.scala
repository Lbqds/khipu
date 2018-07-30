package khipu.store.trienode

import khipu.Hash

/**
 * This class is used to store Nodes (defined in mpt/Node.scala), by using:
 * Key: hash of the RLP encoded node
 * Value: the RLP encoded node
 */
final class ArchiveNodeStorage(source: NodeStorage) extends PruningNodeKeyValueStorage {

  override def get(key: Hash): Option[Array[Byte]] = source.get(key)

  override def update(toRemove: Set[Hash], toUpsert: Map[Hash, Array[Byte]]): NodeKeyValueStorage = {
    source.update(Set(), toUpsert)
    this
  }

  /**
   * Determines and prunes mpt nodes based on last pruned block number tag and the current best block number
   *
   * @param lastPruned      Last pruned block number tag
   * @param bestBlockNumber Current best block number
   * @return PruneResult
   */
  override def prune(lastPruned: => Long, bestBlockNumber: => Long): PruneResult = PruneResult(0, 0)
}
