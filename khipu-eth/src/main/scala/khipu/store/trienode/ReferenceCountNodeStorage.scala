package khipu.store.trienode

import akka.util.ByteString
import java.math.BigInteger
import khipu.Hash

object ReferenceCountNodeStorage {
  /**
   * Model to be used to store, by block number, which block keys are no longer needed (and can potentially be deleted)
   */
  final case class PruneCandidates(var nodeKeys: Set[Hash])

  /**
   * Wrapper of MptNode in order to store number of references it has.
   *
   * @param nodeEncoded Encoded Mpt Node to be used in MerklePatriciaTrie
   * @param references  Number of references the node has. Each time it's updated references are increased and everytime it's deleted, decreased
   */
  final case class StoredNode(nodeEncoded: ByteString, var references: Int) {
    def incrementReferences(amount: Int): StoredNode = {
      references += amount
      this
    }
    def decrementReferences(amount: Int): StoredNode = {
      references -= amount
      this
    }
  }

  /**
   * Based on a block number tag, looks for no longer needed nodes and deletes them if it corresponds (a node that was
   * marked as unused in a certain block number tag, might be used later)
   *
   * @param blockNumber
   * @param nodeStorage
   * @return
   */
  private def prune(blockNumber: Long, nodeStorage: NodeStorage): Int = {
    val key = pruneKey(blockNumber)

    nodeStorage.get(key)
      .map(Encoding.pruneCandidatesFromBytes)
      .map { pruneCandidates =>
        // Get Node from storage and filter ones which have references = 0 now (maybe they were added again after blockNumber)
        pruneCandidates.nodeKeys.map { key =>
          key -> nodeStorage.get(key)
        }.collect {
          case (key, Some(node)) if Encoding.storedNodeFromBytes(node).references == 0 => key
        }
      }.map { nodeKeysToDelete =>
        // Update nodestorage removing all nodes that are no longer being used
        //println(s"node keys to prune: ${nodeKeysToDelete.map(nodeId => nodeId.hexString)}")
        nodeStorage.update(nodeKeysToDelete + key, Map())
        nodeKeysToDelete.size
      }.getOrElse(0)
  }

  /**
   * Key to be used to store PruneCandidates index. PruneKey -> PruneCandidates
   *
   * @param blockNumber Block Number Tag
   * @return Key
   */
  private def pruneKey(blockNumber: Long): Hash = {
    val bnBytes = BigInteger.valueOf(blockNumber).toByteArray
    val key = Array.ofDim[Byte](bnBytes.length + 1)
    key(0) = 'd'
    System.arraycopy(bnBytes, 0, key, 1, bnBytes.length)
    Hash(key)
  }

}

/**
 * This class helps to deal with two problems regarding MptNodes storage:
 * 1) Define a way to delete ones that are no longer needed
 * 2) Avoids removal of nodes that can be used in diferent trie branches because the hash it's the same
 *
 * To deal with (1) when a node is no longer needed it's tagged with the corresponding block number in order to be
 * able to release disk space used (as it's not going to be accessed any more)
 *
 * In order to solve (2), before saving a node, its wrapped with the number of references it has. The inverse operation
 * is done when getting a node.
 *
 * Using this storage will change data to be stored in nodeStorage in two ways (and it will, as consequence, make
 * different pruning mechanisms incompatible):
 * - Instead of saving KEY -> VALUE, it will store KEY -> (VALUE, REFERENCE_COUNT)
 * - Also, the following index will be appended: BLOCK_NUMBER_TAG -> Seq(KEY1, KEY2, ..., KEYn)
 */
final class ReferenceCountNodeStorage(
    source: NodeStorage, pruningOffset: Long, blockNumber: Option[Long] = None
) extends PruningNodeKeyValueStorage with RangePrune {
  import ReferenceCountNodeStorage._

  override def get(key: Hash): Option[Array[Byte]] =
    source.get(key).map(Encoding.storedNodeFromBytes).map(_.nodeEncoded.toArray)

  override def update(toRemove: Set[Hash], toUpsert: Map[Hash, Array[Byte]]): NodeKeyValueStorage = {
    require(blockNumber.isDefined)

    val bn = blockNumber.get
    // Process upsert changes. As the same node might be changed twice within the same update, we need to keep changes
    // within a map
    val upsertChanges = toUpsert.foldLeft(Map[Hash, StoredNode]()) {
      case (nodes, (nodeKey, nodeEncoded)) =>
        val node = nodes.get(nodeKey) // get from current changes
          .orElse(source.get(nodeKey).map(Encoding.storedNodeFromBytes)) // or get from DB
          .getOrElse(StoredNode(ByteString(nodeEncoded), 0)) // if it's new, return an empty stored node
          .incrementReferences(1)

        nodes + (nodeKey -> node)
    }

    // Look for block_number -> key prune candidates in order to update it if some node reaches 0 references
    val toPruneInThisBlockKey = pruneKey(bn)
    val pruneCandidates = source.get(toPruneInThisBlockKey).map(Encoding.pruneCandidatesFromBytes).getOrElse(PruneCandidates(Set()))

    val (toUpsertChanges, toRemoveChanges) = toRemove.foldLeft(upsertChanges, pruneCandidates) {
      case (acc @ (nodes, toDeleteInBlock), nodeKey) =>
        nodes.get(nodeKey) // get from current changes
          .orElse(source.get(nodeKey).map(Encoding.storedNodeFromBytes)) // or db
          .map(_.decrementReferences(1)) match {
            case Some(storedNode) =>
              if (storedNode.references == 0) {
                // if references is 0, mark it as prune candidate for current block tag
                toDeleteInBlock.nodeKeys += nodeKey
              } else {
                toDeleteInBlock
              }
              (nodes + (nodeKey -> storedNode), toDeleteInBlock)
            case None =>
              acc
          }
    }

    // map stored nodes to bytes in order to save them
    val toUpsertUpdated = toUpsertChanges.map {
      case (nodeKey, storedNode) => nodeKey -> Encoding.storedNodeToBytes(storedNode)
    }

    val toMarkAsDeleted = if (toRemoveChanges.nodeKeys.nonEmpty) {
      // Update prune candidates for current block tag if it references at least one block that should be removed
      Map(toPruneInThisBlockKey -> Encoding.pruneCandiatesToBytes(toRemoveChanges))
    } else {
      Map()
    }

    source.update(Set(), toUpsertUpdated ++ toMarkAsDeleted)

    this
  }

  /**
   * Determines and prunes mpt nodes based on last pruned block number tag and the current best block number
   *
   * @param lastPruned      Last pruned block number tag
   * @param bestBlockNumber Current best block number
   * @return PruneResult
   */
  override def prune(lastPruned: => Long, bestBlockNumber: => Long): PruneResult = {
    val from = lastPruned + 1
    val to = from.max(bestBlockNumber - pruningOffset)
    pruneBetween(from, to, bn => ReferenceCountNodeStorage.prune(bn, source))
  }
}

