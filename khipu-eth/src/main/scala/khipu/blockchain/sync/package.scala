package khipu.blockchain

import akka.util.ByteString
import khipu.Command
import khipu.Hash
import khipu.crypto
import khipu.domain.Account
import khipu.domain.BlockHeader
import khipu.domain.Receipt
import khipu.network.p2p.Message
import khipu.network.p2p.MessageSerializable
import khipu.network.p2p.messages.PV62
import khipu.network.p2p.messages.PV63

package object sync {
  object NodeHash {
    val StateMptNode = 0.toByte
    val ContractStorageMptNode = 1.toByte
    val StorageRoot = 2.toByte
    val Evmcode = 3.toByte
  }
  sealed trait NodeHash extends Hash.I {
    def toHash: Hash = Hash(bytes)
    def tpe: Byte
  }
  final case class StateMptNodeHash(bytes: Array[Byte]) extends NodeHash { def tpe = NodeHash.StateMptNode }
  final case class ContractStorageMptNodeHash(bytes: Array[Byte]) extends NodeHash { def tpe = NodeHash.ContractStorageMptNode }
  final case class StorageRootHash(bytes: Array[Byte]) extends NodeHash { def tpe = NodeHash.StorageRoot }
  final case class EvmcodeHash(bytes: Array[Byte]) extends NodeHash { def tpe = NodeHash.Evmcode }

  final case class SyncState(
    targetBlockNumber:     Long,
    downloadedNodesCount:  Int            = 0,
    bestBlockHeaderNumber: Long           = 0,
    mptNodesQueue:         List[NodeHash] = Nil,
    nonMptNodesQueue:      List[NodeHash] = Nil,
    blockBodiesQueue:      List[Hash]     = Nil,
    receiptsQueue:         List[Hash]     = Nil
  )

  sealed trait RequestToPeer[M <: Message, R <: PeerResponse] extends Command {
    def id = peerId
    def peerId: String
    def messageToSend: MessageSerializable

    def processResponse(message: M): Option[R]
  }

  sealed trait PeerResponse extends Command {
    def id = peerId
    def peerId: String
  }

  /**
   * https://blog.ethereum.org/2015/06/26/state-tree-pruning/
   */
  final case class NodeDataResponse(peerId: String, nDownloadedNodes: Int, remainingHashes: List[NodeHash], childHashes: List[NodeHash], receviceAccounts: List[(Hash, ByteString)], receivedStorages: List[(Hash, ByteString)], receivdeEvmcodes: List[(Hash, ByteString)]) extends PeerResponse
  final case class NodeDataRequest(peerId: String, message: PV63.GetNodeData, requestHashes: List[NodeHash]) extends RequestToPeer[PV63.NodeData, NodeDataResponse] {
    def messageToSend = message

    def processResponse(nodeData: PV63.NodeData) = {
      if (nodeData.values.isEmpty) {
        None
      } else {

        val receivedHashes = nodeData.values.toList.map(v => Hash(crypto.kec256(v.toArray)))

        val receives = receivedHashes
        val requests = requestHashes.map(x => x.toHash -> x).toMap

        val remainingHashes = requestHashes.filterNot(h => receives.contains(h.toHash))

        val (childHashes, receivedAccounts, receivedStorages, receivedEvmcodes) = (nodeData.values zip receivedHashes).foldLeft((List[NodeHash](), List[(Hash, ByteString)](), List[(Hash, ByteString)](), List[(Hash, ByteString)]())) {
          case ((childHashes, receivedAccounts, receivedStorages, receivedEvmcodes), (value, receive)) =>
            requests.get(receive) match {
              case None =>
                (childHashes, receivedAccounts, receivedStorages, receivedEvmcodes)

              case Some(x: StateMptNodeHash) =>
                val node = nodeData.toMptNode(value)
                val hashes = processStateNode(node)
                (childHashes ::: hashes, (x.toHash, value) :: receivedAccounts, receivedStorages, receivedEvmcodes)

              case Some(x: StorageRootHash) =>
                val node = nodeData.toMptNode(value)
                val hashes = processContractMptNode(node)
                (childHashes ::: hashes, receivedAccounts, (x.toHash, value) :: receivedStorages, receivedEvmcodes)

              case Some(x: ContractStorageMptNodeHash) =>
                val node = nodeData.toMptNode(value)
                val hashes = processContractMptNode(node)
                (childHashes ::: hashes, receivedAccounts, (x.toHash, value) :: receivedStorages, receivedEvmcodes)

              case Some(x: EvmcodeHash) =>
                (childHashes, receivedAccounts, receivedStorages, (x.toHash, value) :: receivedEvmcodes)
            }
        }

        val nDownloadedNodes = nodeData.values.size

        Some(NodeDataResponse(peerId, nDownloadedNodes, remainingHashes, childHashes, receivedAccounts, receivedStorages, receivedEvmcodes))
      }
    }

    private def processStateNode(mptNode: PV63.MptNode): List[NodeHash] = {
      mptNode match {
        case node: PV63.MptLeaf =>
          val account = node.getAccount
          val codeHash = account.codeHash match {
            case Account.EmptyCodeHash => Nil
            case hash                  => List(EvmcodeHash(hash.bytes))
          }
          val storageHash = account.stateRoot match {
            case Account.EmptyStorageRootHash => Nil
            case hash                         => List(StorageRootHash(hash.bytes))
          }

          codeHash ::: storageHash

        case node: PV63.MptBranch =>
          node.children.toList.collect {
            case Left(PV63.MptHash(child)) => child
          }.filter(_.nonEmpty).map(hash => StateMptNodeHash(hash.bytes))

        case node: PV63.MptExtension =>
          node.child.fold(
            mptHash => List(StateMptNodeHash(mptHash.hash.bytes)),
            mptNode => Nil
          )
      }
    }

    private def processContractMptNode(mptNode: PV63.MptNode): List[NodeHash] = {
      mptNode match {
        case node: PV63.MptLeaf =>
          Nil

        case node: PV63.MptBranch =>
          node.children.toList.collect {
            case Left(PV63.MptHash(child)) => child
          }.filter(_.nonEmpty).map(hash => ContractStorageMptNodeHash(hash.bytes))

        case node: PV63.MptExtension =>
          node.child.fold(
            mptHash => List(ContractStorageMptNodeHash(mptHash.hash.bytes)),
            mptNode => Nil
          )
      }
    }
  }

  final case class ReceiptsResponse(peerId: String, remainingHashes: List[Hash], receivedHashes: List[Hash], receiptsToSave: List[(Hash, Seq[Receipt])]) extends PeerResponse
  final case class ReceiptsRequest(peerId: String, message: PV63.GetReceipts) extends RequestToPeer[PV63.Receipts, ReceiptsResponse] {
    def messageToSend = message

    def processResponse(receipts: PV63.Receipts) = {
      val requestHashes = message.blockHashes.toList
      if (receipts.receiptsForBlocks.isEmpty) {
        None
      } else {
        val receiptsToSave = (requestHashes zip receipts.receiptsForBlocks)

        val receivedHashes = requestHashes.take(receipts.receiptsForBlocks.size)
        val remainingHashes = requestHashes.drop(receipts.receiptsForBlocks.size)

        Some(ReceiptsResponse(peerId, remainingHashes, receivedHashes, receiptsToSave))
      }
    }
  }

  final case class BlockHeadersResponse(peerId: String, headers: List[BlockHeader], isConsistent: Boolean) extends PeerResponse
  final case class BlockHeadersRequest(peerId: String, message: PV62.GetBlockHeaders) extends RequestToPeer[PV62.BlockHeaders, BlockHeadersResponse] {
    def messageToSend = message

    def processResponse(blockHeaders: PV62.BlockHeaders) = {
      val headers = (if (message.reverse) blockHeaders.headers.reverse else blockHeaders.headers).toList

      if (headers.nonEmpty) {
        if (isHeadersConsistent(headers)) {
          Some(BlockHeadersResponse(peerId, headers, true))
        } else {
          Some(BlockHeadersResponse(peerId, List(), false))
        }
      } else {
        None
      }
    }

    private def isHeadersConsistent(headers: List[BlockHeader]): Boolean = {
      if (headers.length > 1) {
        headers.zip(headers.tail).forall {
          case (parent, child) =>
            parent.hash == child.parentHash && parent.number + 1 == child.number
        }
      } else {
        true
      }
    }
  }

  final case class BlockBodiesResponse(peerId: String, bodies: List[PV62.BlockBody]) extends PeerResponse
  final case class BlockBodiesRequest(peerId: String, message: PV62.GetBlockBodies) extends RequestToPeer[PV62.BlockBodies, BlockBodiesResponse] {
    def messageToSend = message

    def processResponse(blockBodies: PV62.BlockBodies) = {
      val requestedHashes = message.hashes
      if (blockBodies.bodies.isEmpty) {
        None
      } else {
        Some(BlockBodiesResponse(peerId, blockBodies.bodies.toList))
      }
    }
  }

}
