package khipu.blockchain.sync

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Timers
import khipu.network.handshake.EtcHandshake.PeerInfo
import khipu.network.p2p.messages.WireProtocol.Disconnect
import khipu.network.rlpx.IncomingPeer
import khipu.network.rlpx.OutgoingPeer
import khipu.network.rlpx.Peer
import khipu.network.rlpx.PeerEntity
import khipu.network.rlpx.PeerManager
import khipu.service.ServiceBoard
import khipu.store.AppStateStorage
import khipu.util
import scala.concurrent.duration.FiniteDuration

object HandshakedPeersService {
  final case class BlacklistPeer(peerId: String, reason: String)

  private case class UnblacklistPeerTask(peerId: String)
  private case class UnblacklistPeerTick(peerId: String)
}
trait HandshakedPeersService extends Actor with Timers with ActorLogging {
  import context.dispatcher
  import HandshakedPeersService._

  private val serviceBoard = ServiceBoard(context.system)
  private def peerManager = serviceBoard.peerManage

  protected def appStateStorage: AppStateStorage

  protected var handshakedPeers = Map[String, (Peer, PeerInfo)]()
  protected var incomingPeers = Set[String]()
  protected var outgoingPeers = Set[String]()
  protected var blacklistedPeers = Map[String, Int]()

  def peerUpdateBehavior: Receive = {
    case PeerEntity.PeerHandshaked(peer, peerInfo: PeerInfo) =>
      log.debug(s"[sync] added handshaked peer: $peer")
      handshakedPeers += (peer.id -> (peer, peerInfo))
      peer match {
        case x: IncomingPeer => incomingPeers += peer.id
        case x: OutgoingPeer => outgoingPeers += peer.id
      }
      log.debug(s"[sync] handshaked peers: ${handshakedPeers.size}")

    case PeerEntity.PeerDisconnected(peerId) if handshakedPeers.contains(peerId) =>
      log.debug(s"[sync] peer $peerId disconnected")

      removePeer(peerId)

    case PeerEntity.PeerInfoUpdated(peerId, peerInfo) =>
      log.debug(s"[sync] UpdatedPeerInfo: $peerInfo")
      handshakedPeers.get(peerId) foreach {
        case (peer, _) =>
          if (peerInfo.maxBlockNumber > appStateStorage.getEstimatedHighestBlock) {
            appStateStorage.putEstimatedHighestBlock(peerInfo.maxBlockNumber)
          }

          if (!peerInfo.forkAccepted) {
            log.debug(s"[sync] peer $peerId is not running the accepted fork, disconnecting")
            val disconnect = Disconnect(Disconnect.Reasons.UselessPeer)
            peer.entity ! PeerEntity.MessageToPeer(peerId, disconnect)
            removePeer(peerId)
            blacklist(peerId, util.Config.Sync.blacklistDuration, disconnect.toString, always = true)
          } else {
            handshakedPeers += (peerId -> (peer, peerInfo))
          }

      }

    case BlacklistPeer(peerId, reason) =>
      blacklist(peerId, util.Config.Sync.blacklistDuration, reason)

    case UnblacklistPeerTick(peerId) =>
      timers.cancel(UnblacklistPeerTask(peerId))

    case PeerEntity.PeerEntityStopped(peerId) =>
      log.debug(s"[sync] peer $peerId stopped")
      removePeer(peerId)
  }

  def removePeer(peerId: String) {
    log.debug(s"[sync] removing peer $peerId")
    timers.cancel(UnblacklistPeerTask(peerId))
    handshakedPeers.get(peerId) match {
      case Some((x: IncomingPeer, _)) => incomingPeers -= peerId
      case Some((x: OutgoingPeer, _)) => outgoingPeers -= peerId
      case _                          =>
    }
    handshakedPeers -= peerId
  }

  def peersToDownloadFrom: Map[Peer, PeerInfo] =
    handshakedPeers.collect {
      case (peerId, (peer, info)) if !isBlacklisted(peerId) => (peer, info)
    }

  private def blacklist(peerId: String, duration: FiniteDuration, reason: String, always: Boolean = false) {
    val blacklistTimes = blacklistedPeers.getOrElse(peerId, 0)
    timers.cancel(UnblacklistPeerTask(peerId))
    log.debug(s"[sync] blacklisting peer $peerId, $reason")
    if (!always && blacklistTimes < Int.MaxValue) {
      timers.startSingleTimer(UnblacklistPeerTask(peerId), UnblacklistPeerTick(peerId), duration)
    } else {
      peerManager ! PeerManager.DropNode(peerId)
    }
    blacklistedPeers += (peerId -> (blacklistTimes + 1))
  }

  def isBlacklisted(peerId: String): Boolean = blacklistedPeers.getOrElse(peerId, 0) >= 4
}
