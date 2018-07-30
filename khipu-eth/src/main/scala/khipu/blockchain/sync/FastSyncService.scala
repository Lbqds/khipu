package khipu.blockchain.sync

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.PoisonPill
import akka.actor.Props
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.ByteString
import java.math.BigInteger
import khipu.Hash
import khipu.blockchain.sync
import khipu.blockchain.sync.HandshakedPeersService.BlacklistPeer
import khipu.crypto
import khipu.domain.BlockHeader
import khipu.domain.Blockchain
import khipu.domain.Receipt
import khipu.network.handshake.EtcHandshake.PeerInfo
import khipu.network.p2p.messages.PV62
import khipu.network.p2p.messages.PV63
import khipu.network.p2p.messages.CommonMessages.Status
import khipu.network.rlpx.Peer
import khipu.network.rlpx.RLPxStage
import khipu.store.FastSyncStateStorage
import scala.collection.immutable
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * eth/63 fast synchronization algorithm
 * https://github.com/ethereum/go-ethereum/pull/1889
 *
 * An outline of the fast sync algorithm would be:
 * - Similarly to classical sync, download the block headers and bodies that make up the blockchain
 * - Similarly to classical sync, verify the header chain's consistency (POW, total difficulty, etc)
 * - Instead of processing the blocks, download the transaction receipts as defined by the header
 * - Store the downloaded blockchain, along with the receipt chain, enabling all historical queries
 * - When the chain reaches a recent enough state (head - 1024 blocks), pause for state sync:
 *   - Retrieve the entire Merkel Patricia state trie defined by the root hash of the pivot point
 *   - For every account found in the trie, retrieve it's contract code and internal storage state trie
 * - Upon successful trie download, mark the pivot point (head - 1024 blocks) as the current head
 * - Import all remaining blocks (1024) by fully processing them as in the classical sync
 *
 */
object FastSyncService {
  case object RetryStart
  case object BlockHeadersTimeout
  case object TargetBlockTimeout

  case object ProcessSyncingTask
  case object ProcessSyncingTick
  case object PersistSyncState

  final case class PeerWorkDone(peerId: String)
  final case class HeaderWorkDone(peerId: String)

  final case class MarkPeerBlockchainOnly(peer: Peer)

  sealed trait BlockBodyValidationResult
  case object Valid extends BlockBodyValidationResult
  case object Invalid extends BlockBodyValidationResult
  case object DbError extends BlockBodyValidationResult

  // always enqueue hashes at the head, this will get shorter pending queue !!!
  // thus we use List hashes here 
  final case class EnqueueNodes(hashes: List[NodeHash])
  final case class EnqueueBlockBodies(hashes: List[Hash])
  final case class EnqueueReceipts(hashes: List[Hash])

  final case class UpdateDownloadedNodesCount(update: Int)

  final case class SaveDifficulties(kvs: Map[Hash, BigInteger])
  final case class SaveHeaders(kvs: Map[Hash, BlockHeader])
  final case class SaveBodies(kvs: Map[Hash, PV62.BlockBody])
  final case class SaveReceipts(kvs: Map[Hash, Seq[Receipt]])
  final case class SaveAccountNodes(kvs: Map[Hash, Array[Byte]])
  final case class SaveStorageNodes(kvs: Map[Hash, Array[Byte]])
  final case class SaveEvmcodes(kvs: Map[Hash, ByteString])
}
trait FastSyncService extends HandshakedPeersService { _: SyncService =>
  import context.dispatcher
  import khipu.util.Config.Sync._
  import FastSyncService._

  private val fastSyncTimeout = RLPxStage.decodeTimeout.plus(20.seconds)

  protected def startFastSync() {
    log.info("Trying to start block synchronization (fast mode)")
    fastSyncStateStorage.getSyncState match {
      case Some(syncState) => startFastSync(syncState)
      case None            => startFastSyncFromScratch()
    }
  }

  private def startFastSync(syncState: SyncState) {
    log.info("Start fast synchronization")
    context become ((new SyncingHandler(syncState).receive) orElse peerUpdateBehavior orElse ommersBehavior)
    self ! ProcessSyncingTick
  }

  private def startFastSyncFromScratch() = {
    log.info("Start fast synchronization from scratch")
    val peersUsedToChooseTarget = peersToDownloadFrom.filter(_._2.forkAccepted)

    if (peersUsedToChooseTarget.size >= minPeersToChooseTargetBlock) {
      log.debug(s"Asking ${peersUsedToChooseTarget.size} peers for block headers")

      // def f(f: Future[Int]) = f map {x => x + 1} recover {case _ => 0}
      // val fs = Future.sequence(List(f(Future.successful(1)), f(Future.failed(new RuntimeException("")))))
      // res22: scala.concurrent.Future[List[Int]] = Success(List(2, 0))
      Future.sequence(peersUsedToChooseTarget map {
        case (peer, PeerInfo(Status(protocolVersion, networkId, totalDifficulty, bestHash, genesisHash), _, _, _)) =>
          val request = sync.BlockHeadersRequest(peer.id, PV62.GetBlockHeaders(Right(bestHash), 1, 0, reverse = false))
          (peer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.BlockHeadersResponse]] map {
            case Some(sync.BlockHeadersResponse(peerId, Seq(header), true)) =>
              log.debug(s"Got BlockHeadersResponse with 1 header number=${header.number} from ${peer.id}")
              Some(peer -> header)

            case Some(sync.BlockHeadersResponse(peerId, headers, true)) =>
              self ! BlacklistPeer(peerId, s"Got BlockHeadersRespons with more than 1 header ${headers.size}, blacklisting for $blacklistDuration")
              None

            case Some(sync.BlockHeadersResponse(peerId, _, false)) =>
              self ! BlacklistPeer(peerId, s"Got BlockHeadersRespons with non-consistent headers, blacklisting for $blacklistDuration")
              None

            case None =>
              self ! BlacklistPeer(peer.id, s"Got empty block headers response for requested: ${request.message.block}")
              None

          } recover {
            case e: AskTimeoutException =>
              self ! BlacklistPeer(peer.id, s"${e.getMessage}")
              None
            case e =>
              self ! BlacklistPeer(peer.id, s"${e.getMessage}")
              None
          }
      }) map {
        receivedHeaders => tryStartFastSync(receivedHeaders.flatten)
      }
      //val timeout = context.system.scheduler.scheduleOnce(peerResponseTimeout, self, BlockHeadersTimeout)
      //context become waitingForBlockHeaders(peersUsedToChooseTarget.keySet, Map.empty, timeout)
    } else {
      log.info(s"Fast synchronization did not start yet. Need at least ${minPeersToChooseTargetBlock} peers, but there are only ${peersUsedToChooseTarget.size} available at the moment. Retrying in ${startRetryInterval}")
      scheduleStartRetry(startRetryInterval)
      context become startingFastSync
    }
  }

  private def tryStartFastSync(receivedHeaders: immutable.Iterable[(Peer, BlockHeader)]) {
    log.debug("Trying to start fast sync. Received {} block headers", receivedHeaders.size)
    if (receivedHeaders.size >= minPeersToChooseTargetBlock) {
      val (mostUpToDatePeer, mostUpToDateBlockHeader) = receivedHeaders.maxBy(_._2.number)
      val targetBlock = mostUpToDateBlockHeader.number - targetBlockOffset

      if (targetBlock < 1) {
        log.debug("Target block is less than 1 now, starting regular sync")
        appStateStorage.fastSyncDone()
        context become idle
        self ! SyncService.FastSyncDone
      } else {
        log.debug(s"Starting fast sync. Asking peer $mostUpToDatePeer for target block header ($targetBlock)")

        val request = sync.BlockHeadersRequest(mostUpToDatePeer.id, PV62.GetBlockHeaders(Left(targetBlock), 1, 0, reverse = false))
        (mostUpToDatePeer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.BlockHeadersResponse]] map {
          case Some(sync.BlockHeadersResponse(peerId, headers, true)) =>
            log.debug(s"Got BlockHeadersResponse with 1 header from ${mostUpToDatePeer.id}")
            headers.find(header => header.number == targetBlock) match {
              case Some(targetBlockHeader) =>
                log.info(s"Starting block synchronization (fast mode), target block ${targetBlockHeader}")
                val initialSyncState = SyncState(
                  targetBlockHeader.number,
                  mptNodesQueue = List(StateMptNodeHash(targetBlockHeader.stateRoot.bytes))
                )
                startFastSync(initialSyncState)

              case None =>
                self ! BlacklistPeer(peerId, s"did not respond with target block header, blacklisting and scheduling retry in $startRetryInterval")
                log.info(s"Block synchronization (fast mode) not started. Target block header not received. Retrying in ${startRetryInterval}")
                scheduleStartRetry(startRetryInterval)
                context become startingFastSync
            }

          case Some(sync.BlockHeadersResponse(peerId, _, false)) =>
            self ! BlacklistPeer(peerId, s"Got BlockHeadersRespons with non-consistent headers, blacklisting for $blacklistDuration")
            log.info(s"Block synchronization (fast mode) not started. Target block header is not consistent. Retrying in s{startRetryInterval}")
            scheduleStartRetry(startRetryInterval)
            context become startingFastSync

          case None =>
            self ! BlacklistPeer(mostUpToDatePeer.id, s"${mostUpToDatePeer.id} got empty block headers response for requested: ${request.message.block}")
            log.info(s"Block synchronization (fast mode) not started. Target block header receive empty. Retrying in {startRetryInterval}")
            scheduleStartRetry(startRetryInterval)
            context become startingFastSync

        } andThen {
          case Failure(e: AskTimeoutException) =>
            log.info(s"Block synchronization (fast mode) not started. Target block header receive timeout. Retrying in s{startRetryInterval}")
            self ! BlacklistPeer(mostUpToDatePeer.id, s"${e.getMessage}")
            scheduleStartRetry(startRetryInterval)
            context become startingFastSync
          case Failure(e) =>
            log.info(s"Block synchronization (fast mode) not started. Target block header receive ${e.getMessage}. Retrying in s{startRetryInterval}")
            self ! BlacklistPeer(mostUpToDatePeer.id, s"${e.getMessage}")
            scheduleStartRetry(startRetryInterval)
            context become startingFastSync
          case _ =>
        }
        //val timeout = context.system.scheduler.scheduleOnce(peerResponseTimeout, self, TargetBlockTimeout)
        //context become waitingForTargetBlock(mostUpToDatePeer, targetBlock, timeout)
      }

    } else {
      log.info(s"Block synchronization (fast mode) does not started. Need to receive block headers from at least $minPeersToChooseTargetBlock peers, but received only from s{receivedHeaders.size}. Retrying in $startRetryInterval")
      scheduleStartRetry(startRetryInterval)
      context become startingFastSync
    }
  }

  def startingFastSync: Receive = peerUpdateBehavior orElse ommersBehavior orElse {
    case RetryStart => startFastSync()
  }

  def scheduleStartRetry(interval: FiniteDuration) = {
    context.system.scheduler.scheduleOnce(interval, self, RetryStart)
  }

  private class SyncingHandler(initialSyncState: SyncState) {

    private var pendingBlockBodies = initialSyncState.blockBodiesQueue
    private var pendingReceipts = initialSyncState.receiptsQueue
    private var pendingMptNodes = initialSyncState.mptNodesQueue
    private var pendingNonMptNodes = initialSyncState.nonMptNodesQueue

    private var currDownloadedNodes = initialSyncState.downloadedNodesCount
    private var bestBlockHeaderNumber = initialSyncState.bestBlockHeaderNumber

    private var workingPeers = Map[String, Long]()
    private var headerWorkingPeer: Option[String] = None

    private var blockchainOnlyPeers = Map[String, Peer]()

    private val persistenceService = context.actorOf(PersistenceService.props(blockchain, initialSyncState), "persistence-service")
    private val syncStatePersist = context.actorOf(Props[FastSyncStatePersist], "state-persistor")
    //syncStatePersist ! fastSyncStateStorage

    private val syncStatePersistCancellable = context.system.scheduler.schedule(persistStateSnapshotInterval, persistStateSnapshotInterval, self, PersistSyncState)
    private val heartBeat = context.system.scheduler.schedule(syncRetryInterval, syncRetryInterval * 2, self, ProcessSyncingTick)

    val accountNodeStorage = blockchainStorages.accountNodeStorageFor(Some(initialSyncState.targetBlockNumber))
    val storageNodeStorage = blockchainStorages.storageNodeStorageFor(Some(initialSyncState.targetBlockNumber))

    private var currBlockNumber = 0L
    private var prevBlockNumber = 0L
    private var prevDownloadeNodes = 0
    private var prevReportTime = System.currentTimeMillis

    def receive: Receive = peerUpdateBehavior orElse ommersBehavior orElse {
      // always enqueue hashes at the head, this will get shorter pending queue !!!
      case EnqueueNodes(hashes) =>
        hashes foreach {
          case h: EvmcodeHash                => pendingNonMptNodes = h :: pendingNonMptNodes
          case h: StorageRootHash            => pendingNonMptNodes = h :: pendingNonMptNodes
          case h: StateMptNodeHash           => pendingMptNodes = h :: pendingMptNodes
          case h: ContractStorageMptNodeHash => pendingMptNodes = h :: pendingMptNodes
        }

      case EnqueueBlockBodies(hashes) =>
        pendingBlockBodies = hashes ::: pendingBlockBodies

      case EnqueueReceipts(hashes) =>
        pendingReceipts = hashes ::: pendingReceipts

      case UpdateDownloadedNodesCount(n) =>
        currDownloadedNodes += n

      case MarkPeerBlockchainOnly(peer) =>
        if (!blockchainOnlyPeers.contains(peer.id)) {
          blockchainOnlyPeers = blockchainOnlyPeers.take(blockchainOnlyPeersPoolSize) + (peer.id -> peer)
        }

      case ProcessSyncingTick =>
        processSyncing()

      case PeerWorkDone(peerId) =>
        workingPeers -= peerId
        processSyncing()

      case HeaderWorkDone(peerId) =>
        headerWorkingPeer = None
        workingPeers -= peerId
        processSyncing()

      case SyncService.ReportStatusTick =>
        reportStatus()

      case PersistSyncState =>
      //persistSyncState()  // TODO memory cost lots
    }

    private def persistSyncState() {
      syncStatePersist ! SyncState(
        initialSyncState.targetBlockNumber,
        currDownloadedNodes,
        bestBlockHeaderNumber,
        pendingMptNodes,
        pendingNonMptNodes,
        pendingBlockBodies,
        pendingReceipts
      )
    }

    private def validateBlocks(requestHashes: Seq[Hash], blockBodies: Seq[PV62.BlockBody]): BlockBodyValidationResult = {
      val headerToBody = (requestHashes zip blockBodies).map {
        case (hash, body) => (blockchain.getBlockHeaderByHash(hash), body)
      }

      headerToBody.collectFirst {
        case (None, _) => DbError
        case (Some(header), body) if validators.blockValidator.validateHeaderAndBody(header, body).isLeft => Invalid
      } getOrElse (Valid)
    }

    private def insertBlocks(requestedHashes: List[Hash], blockBodies: List[PV62.BlockBody]) {
      persistenceService ! SaveBodies((requestedHashes zip blockBodies).toMap)

      val receivedHashes = requestedHashes.take(blockBodies.size)
      updateBestBlockIfNeeded(receivedHashes)
      val remainingBlockBodies = requestedHashes.drop(blockBodies.size)
      self ! EnqueueBlockBodies(remainingBlockBodies)
    }

    private def insertHeaders(headers: List[BlockHeader]) {
      val blockHeadersObtained = headers.takeWhile { header =>
        val parentTd = blockchain.getTotalDifficultyByHash(header.parentHash)
        parentTd match {
          case Some(parentTotalDifficulty) =>
            // header is fetched and saved sequentially. TODO better logic
            blockchain.save(header)
            blockchain.save(header.hash, parentTotalDifficulty add header.difficulty)
            true
          case None =>
            false
        }
      }

      blockHeadersObtained.lastOption.foreach { lastHeader =>
        if (lastHeader.number > bestBlockHeaderNumber) {
          bestBlockHeaderNumber = lastHeader.number
        }
      }
    }

    def processSyncing() {
      if (isFullySynced) {
        log.info("[fast] Block synchronization in fast mode finished, switching to regular mode")
        finishFastSync()
      } else {
        if (isAnythingToDownload) {
          processDownload()
        } else {
          log.info(s"[fast] No more items to request, waiting for ${workingPeers.size} finishing requests")
        }
      }
    }

    private def finishFastSync() {
      cleanup()
      appStateStorage.fastSyncDone()
      context become idle
      blockchainOnlyPeers = Map()
      self ! SyncService.FastSyncDone
    }

    private def cleanup() {
      heartBeat.cancel()
      syncStatePersistCancellable.cancel()
      syncStatePersist ! PoisonPill
      fastSyncStateStorage.purge()
    }

    private def processDownload() {
      var peers = unassignedPeers
      if (peers.isEmpty) {
        if (workingPeers.nonEmpty) {
          log.debug("There are no available peers, waiting for responses")
        } else {
          log.debug(s"There are no peers to download from, scheduling a retry in ${syncRetryInterval}")
          timers.startSingleTimer(ProcessSyncingTask, ProcessSyncingTick, syncRetryInterval)
        }
      } else {
        val blockchainPeers = blockchainOnlyPeers.values.toSet

        if (pendingNonMptNodes.nonEmpty || pendingMptNodes.nonEmpty) {
          val nodeWorks = (peers -- blockchainPeers)
            .take(maxConcurrentRequests - workingPeers.size)
            .toSeq.sortBy(_.id)
            .foldLeft(Vector[(Peer, List[NodeHash])]()) {
              case (acc, peer) =>
                if (pendingNonMptNodes.nonEmpty || pendingMptNodes.nonEmpty) {
                  val (requestingNonMptNodes, remainingNonMptNodes) = pendingNonMptNodes.splitAt(nodesPerRequest)
                  val (requestingMptNodes, remainingMptNodes) = pendingMptNodes.splitAt(nodesPerRequest - requestingNonMptNodes.size)
                  val requestingNodes = requestingNonMptNodes ++ requestingMptNodes

                  pendingNonMptNodes = remainingNonMptNodes
                  pendingMptNodes = remainingMptNodes

                  acc :+ (peer, requestingNodes)
                } else {
                  acc
                }
            }
          nodeWorks foreach { case (peer, requestingNodes) => requestNodes(peer, requestingNodes) }
        }

        if (pendingReceipts.nonEmpty) {
          val receiptWorks = unassignedPeers.intersect(blockchainPeers)
            .take(maxConcurrentRequests - workingPeers.size)
            .toSeq.sortBy(_.id)
            .foldLeft(Vector[(Peer, List[Hash])]()) {
              case (acc, peer) =>
                if (pendingReceipts.nonEmpty) {
                  val (requestingReceipts, remainingReceipts) = pendingReceipts.splitAt(receiptsPerRequest)

                  pendingReceipts = remainingReceipts

                  acc :+ (peer, requestingReceipts)
                } else {
                  acc
                }
            }
          receiptWorks foreach { case (peer, requestingReceipts) => requestReceipts(peer, requestingReceipts) }
        }

        if (pendingBlockBodies.nonEmpty) {
          val bodyWorks = unassignedPeers.intersect(blockchainPeers)
            .take(maxConcurrentRequests - workingPeers.size)
            .toSeq.sortBy(_.id)
            .foldLeft(Vector[(Peer, List[Hash])]()) {
              case (acc, peer) =>
                if (pendingBlockBodies.nonEmpty) {
                  val (requestingHashes, remainingHashes) = pendingBlockBodies.splitAt(blockBodiesPerRequest)

                  pendingBlockBodies = remainingHashes

                  acc :+ (peer, requestingHashes)
                } else {
                  acc
                }
            }
          bodyWorks foreach { case (peer, requestingHashes) => requestBlockBodies(peer, requestingHashes) }
        }

        if (isHeaderToDownload && headerWorkingPeer.isEmpty) {
          val headerWorks = unassignedPeers.intersect(blockchainPeers)
            .take(maxConcurrentRequests - workingPeers.size)
            .toSeq.sortBy(_.id)
            .headOption flatMap { peer =>
              if (isHeaderToDownload && headerWorkingPeer.isEmpty) {
                Some(peer)
              } else {
                None
              }
            }
          headerWorks foreach { requestBlockHeaders }
        }
      }

    }

    private def unassignedPeers: Set[Peer] =
      peersToDownloadFrom.keySet filterNot { peer => workingPeers.contains(peer.id) }

    private def isAnythingToDownload =
      isAnythingQueued || isHeaderToDownload

    private def isHeaderToDownload =
      bestBlockHeaderNumber < initialSyncState.targetBlockNumber

    private def isAnythingQueued =
      pendingNonMptNodes.nonEmpty ||
        pendingMptNodes.nonEmpty ||
        pendingBlockBodies.nonEmpty ||
        pendingReceipts.nonEmpty

    private def isFullySynced =
      !isHeaderToDownload && !isAnythingQueued && workingPeers.isEmpty

    private def updateBestBlockIfNeeded(receivedHashes: Seq[Hash]) {
      val syncedBlocks = receivedHashes.flatMap { hash =>
        for {
          header <- blockchain.getBlockHeaderByHash(hash)
          _ <- blockchain.getBlockBodyByHash(hash)
        } yield header
      }

      if (syncedBlocks.nonEmpty) {
        val bestReceivedBlock = syncedBlocks.maxBy(_.number)
        if (bestReceivedBlock.number > appStateStorage.getBestBlockNumber) {
          currBlockNumber = bestReceivedBlock.number
          appStateStorage.putBestBlockNumber(bestReceivedBlock.number)
        }
      }
    }

    def requestBlockHeaders(peer: Peer) {
      log.debug(s"Request BlockHeaders from ${peer.id}")
      val start = System.currentTimeMillis

      workingPeers += (peer.id -> System.currentTimeMillis)
      headerWorkingPeer = Some(peer.id)
      val limit = math.min(blockHeadersPerRequest, initialSyncState.targetBlockNumber - bestBlockHeaderNumber)
      val request = sync.BlockHeadersRequest(peer.id, PV62.GetBlockHeaders(Left(bestBlockHeaderNumber + 1), limit, skip = 0, reverse = false))
      log.debug(s"Request block headers: ${request.message.block.fold(n => n, _.hexString)}, bestBlockHeaderNumber is $bestBlockHeaderNumber, target is ${initialSyncState.targetBlockNumber}")

      (peer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.BlockHeadersResponse]] map {
        case Some(sync.BlockHeadersResponse(peerId, headers, true)) =>
          log.debug(s"Got BlockHeadersResponse ${headers.size} from ${peer.id} in ${System.currentTimeMillis - start}ms")
          insertHeaders(headers)
          val blockHashes = headers.map(_.hash)
          self ! EnqueueBlockBodies(blockHashes)
          self ! EnqueueReceipts(blockHashes)

        case Some(sync.BlockHeadersResponse(peerId, headers, false)) =>
          self ! BlacklistPeer(peer.id, s"${peer.id} got non-consistent block headers response for requested: ${request.message.block}")

        case None =>
          self ! BlacklistPeer(peer.id, s"${peer.id} got empty block headers response for known header: ${request.message.block}")

      } andThen {
        case Failure(e: AskTimeoutException) =>
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case Failure(e) =>
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case _ =>
      } andThen {
        case _ => self ! HeaderWorkDone(peer.id)
      }
    }

    def requestBlockBodies(peer: Peer, requestingHashes: List[Hash]) {
      log.debug(s"Request BlockBodies from ${peer.id}")
      val start = System.currentTimeMillis

      workingPeers += (peer.id -> System.currentTimeMillis)
      val request = sync.BlockBodiesRequest(peer.id, PV62.GetBlockBodies(requestingHashes))
      (peer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.BlockBodiesResponse]] map {
        case Some(sync.BlockBodiesResponse(peerId, bodies)) =>
          log.debug(s"Got BlockBodiesResponse ${bodies.size} from ${peer.id} in ${System.currentTimeMillis - start}ms")
          validateBlocks(requestingHashes, bodies) match {
            case Valid =>
              insertBlocks(requestingHashes, bodies)

            case Invalid =>
              self ! EnqueueBlockBodies(requestingHashes)
              self ! BlacklistPeer(peerId, s"$peerId responded with invalid block bodies that are not matching block headers, blacklisting for $blacklistDuration")

            case DbError =>
              log.error("DbError")
              pendingBlockBodies = List()
              pendingReceipts = List()
              //todo adjust the formula to minimize redownloaded block headers
              bestBlockHeaderNumber = bestBlockHeaderNumber - 2 * blockHeadersPerRequest
              log.warning("missing block header for known hash")
              self ! ProcessSyncingTick
          }

        case None =>
          self ! BlacklistPeer(peer.id, s"${peer.id} got empty block bodies response for known hashes: ${requestingHashes.map(_.hexString)}")
          self ! EnqueueBlockBodies(requestingHashes)

      } andThen {
        case Failure(e: AskTimeoutException) =>
          self ! EnqueueBlockBodies(requestingHashes)
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case Failure(e) =>
          self ! EnqueueBlockBodies(requestingHashes)
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case _ =>
      } andThen {
        case _ => self ! PeerWorkDone(peer.id)
      }
    }

    def requestReceipts(peer: Peer, requestingReceipts: List[Hash]) {
      log.debug(s"Request Receipts from ${peer.id}")
      val start = System.currentTimeMillis

      workingPeers += (peer.id -> System.currentTimeMillis)
      val request = sync.ReceiptsRequest(peer.id, PV63.GetReceipts(requestingReceipts))
      (peer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.ReceiptsResponse]] map {
        case Some(sync.ReceiptsResponse(peerId, remainingHashes, receivedHashes, receiptsToSave)) =>
          log.debug(s"Got ReceiptsResponse ${receiptsToSave.size} from ${peer.id} in ${System.currentTimeMillis - start}ms")
          // TODO valid receipts
          persistenceService ! SaveReceipts(receiptsToSave.toMap)
          updateBestBlockIfNeeded(receivedHashes)

          self ! EnqueueReceipts(remainingHashes)

        case None =>
          self ! EnqueueReceipts(requestingReceipts)
          self ! BlacklistPeer(peer.id, s"${peer.id} got empty receipts for known hashes: ${requestingReceipts.map(_.hexString)}")

      } andThen {
        case Failure(e: AskTimeoutException) =>
          self ! EnqueueReceipts(requestingReceipts)
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case Failure(e) =>
          self ! EnqueueReceipts(requestingReceipts)
          self ! BlacklistPeer(peer.id, s"${e.getMessage}")
        case _ =>
      } andThen {
        case _ => self ! PeerWorkDone(peer.id)
      }
    }

    def requestNodes(peer: Peer, requestingNodes: List[NodeHash]) {
      log.debug(s"Request Nodes from ${peer.id}")
      val start = System.currentTimeMillis

      workingPeers += (peer.id -> System.currentTimeMillis)
      val request = sync.NodeDataRequest(peer.id, PV63.GetNodeData(requestingNodes.map(_.toHash)), requestingNodes)
      (peer.entity ? request)(fastSyncTimeout).mapTo[Option[sync.NodeDataResponse]] map {
        case Some(sync.NodeDataResponse(peerId, nDownloadedNodes, remainingHashes, childHashes, accounts, storages, evmcodes)) =>
          log.debug(s"Got NodeDataResponse $nDownloadedNodes from ${peer.id} in ${System.currentTimeMillis - start}ms")

          persistenceService ! SaveAccountNodes(accounts.map(kv => (kv._1, kv._2.toArray)).toMap)
          persistenceService ! SaveStorageNodes(storages.map(kv => (kv._1, kv._2.toArray)).toMap)
          persistenceService ! SaveEvmcodes(evmcodes.toMap)

          self ! EnqueueNodes(remainingHashes ++ childHashes)
          self ! UpdateDownloadedNodesCount(nDownloadedNodes)

        case None =>
          log.debug(s"${peer.id}, got empty mpt node response for known hashes ${requestingNodes.map(_.hexString)}. Mark peer blockchain only")
          self ! MarkPeerBlockchainOnly(peer)
          self ! EnqueueNodes(requestingNodes)

      } andThen {
        case Failure(e: AskTimeoutException) =>
          log.debug(s"${peer.id}, $e when request nodes, mark peer blockchain only")
          self ! EnqueueNodes(requestingNodes)
        //self ! MarkPeerBlockchainOnly(peer)
        case Failure(e) =>
          log.debug(s"${peer.id}, $e when request nodes, mark peer blockchain only")
          self ! EnqueueNodes(requestingNodes)
        //self ! MarkPeerBlockchainOnly(peer)
        case _ =>
      } andThen {
        case _ => self ! PeerWorkDone(peer.id)
      }
    }

    private def reportStatus() {
      val duration = (System.currentTimeMillis - prevReportTime) / 1000.0
      val nPendingNodes = pendingMptNodes.size + pendingNonMptNodes.size
      val nTotalNodes = currDownloadedNodes + nPendingNodes
      val blockRate = if (prevBlockNumber != 0) ((currBlockNumber - prevBlockNumber) / duration).toInt else 0
      val nodeRate = if (prevDownloadeNodes != 0) ((currDownloadedNodes - prevDownloadeNodes) / duration).toInt else 0
      prevReportTime = System.currentTimeMillis
      prevBlockNumber = currBlockNumber
      prevDownloadeNodes = currDownloadedNodes
      log.info(
        s"""|[fast] Block: ${currBlockNumber}/${initialSyncState.targetBlockNumber} $blockRate/s.
            |Peers (in/out): (${incomingPeers.size}/${outgoingPeers.size}), (working/good/black): (${workingPeers.size}/${peersToDownloadFrom.size}/${blacklistedPeers.size}).
            |State: $currDownloadedNodes/$nTotalNodes nodes $nodeRate/s $nPendingNodes pending.
            |""".stripMargin.replace("\n", " ")
      )
      log.debug(
        s"""|[fast] Connection status: connected(${workingPeers.keys.toSeq.sorted.mkString(", ")})/
            |handshaked(${handshakedPeers.keys.toSeq.sorted.mkString(", ")})
            | blacklisted(${blacklistedPeers.mkString(", ")})
            |""".stripMargin.replace("\n", " ")
      )
    }

  }
}

object FastSyncStatePersist {
  case object GetStorage
}
/**
 * Persists current state of fast sync to a storage. Can save only one state at a time.
 * If during persisting new state is received then it will be saved immediately after current state
 * was persisted.
 * If during persisting more than one new state is received then only the last state will be kept in queue.
 */
class FastSyncStatePersist extends Actor with ActorLogging {
  import FastSyncStatePersist._

  def receive: Receive = {
    // after initialization send a valid Storage reference
    case storage: FastSyncStateStorage => context become idle(storage)
  }

  def idle(storage: FastSyncStateStorage): Receive = {
    // begin saving of the state to the storage and become busy
    case state: SyncState => persistState(storage, state)

    case GetStorage       => sender() ! storage.getSyncState()
  }

  def busy(storage: FastSyncStateStorage, stateToPersist: Option[SyncState]): Receive = {
    // update state waiting to be persisted later. we only keep newest state
    case state: SyncState => context become busy(storage, Some(state))
    // exception was thrown during persisting of a state. push
    case Failure(e) => throw e
    // state was saved in the storage. become idle
    case Success(s: FastSyncStateStorage) if stateToPersist.isEmpty => context become idle(s)
    // state was saved in the storage but new state is already waiting to be saved.
    case Success(s: FastSyncStateStorage) if stateToPersist.isDefined => stateToPersist.foreach(persistState(s, _))

    case GetStorage => sender() ! storage.getSyncState()
  }

  private def persistState(storage: FastSyncStateStorage, syncState: SyncState) {
    import context.dispatcher
    val persistingQueues: Future[Try[FastSyncStateStorage]] = Future {
      lazy val result = Try { storage.putSyncState(syncState) }
      if (log.isDebugEnabled) {
        val now = System.currentTimeMillis()
        result
        val end = System.currentTimeMillis()
        log.debug(s"Saving snapshot of a fast sync took ${end - now} ms")
        result
      } else {
        result
      }
    }
    persistingQueues pipeTo self
    context become busy(storage, None)
  }
}

object PersistenceService {
  def props(blockchain: Blockchain, initialSyncState: SyncState) =
    Props(classOf[PersistenceService], blockchain, initialSyncState).withDispatcher("khipu-persistence-pinned-dispatcher")
}
class PersistenceService(blockchain: Blockchain, initialSyncState: SyncState) extends Actor with ActorLogging {
  import FastSyncService._
  private val blockchainStorages = blockchain.storages
  val accountNodeStorage = blockchainStorages.accountNodeStorageFor(Some(initialSyncState.targetBlockNumber))
  val storageNodeStorage = blockchainStorages.storageNodeStorageFor(Some(initialSyncState.targetBlockNumber))

  override def preStart() {
    super.preStart()
    log.info("PersistenceService started")
  }

  override def postStop() {
    log.info("PersistenceService stopped")
    super.postStop()
  }

  def receive: Receive = {
    case SaveDifficulties(kvs) =>
      val start = System.currentTimeMillis
      kvs foreach { case (k, v) => blockchain.save(k, v) }
      log.debug(s"SaveDifficulties ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveHeaders(kvs) =>
      val start = System.currentTimeMillis
      kvs foreach { case (k, v) => blockchain.save(v) }
      log.debug(s"SaveHeaders ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveBodies(kvs) =>
      val start = System.currentTimeMillis
      kvs foreach { case (k, v) => blockchain.save(k, v) }
      log.debug(s"SaveBodies ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveReceipts(kvs) =>
      val start = System.currentTimeMillis
      kvs foreach { case (k, v) => blockchain.save(k, v) }
      log.debug(s"SaveReceipts ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveAccountNodes(kvs) =>
      val start = System.currentTimeMillis
      kvs map { case (k, v) => }
      accountNodeStorage.update(Set(), kvs)
      log.debug(s"SaveAccountNodes ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveStorageNodes(kvs) =>
      val start = System.currentTimeMillis
      storageNodeStorage.update(Set(), kvs)
      log.debug(s"SaveStorageNodes ${kvs.size} in ${System.currentTimeMillis - start} ms")

    case SaveEvmcodes(kvs) =>
      val start = System.currentTimeMillis
      kvs foreach { case (k, v) => blockchain.save(k, v) }
      log.debug(s"SaveEvmcodes ${kvs.size} in ${System.currentTimeMillis - start} ms")
  }
}