package khipu.network.rlpx

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import java.net.URI
import khipu.network.handshake.EtcHandshake
import khipu.network.p2p.MessageSerializable
import khipu.network.rlpx.PeerEntity.Status.Handshaked
import khipu.network.rlpx.auth.AuthHandshake
import khipu.network.rlpx.discovery.NodeDiscoveryService
import khipu.service.ServiceBoard
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success

object PeerManager {
  def props(peerConfiguration: PeerConfiguration) =
    Props(classOf[PeerManager], peerConfiguration)

  final case object GetPeers
  final case class Peers(peers: Map[Peer, PeerEntity.Status]) {
    def handshaked: Seq[Peer] = peers.collect { case (peer, Handshaked) => peer }.toSeq
  }
  final case class DropNode(peerId: String)

  final case class SendMessage(peerId: String, message: MessageSerializable)
}
class PeerManager(peerConfiguration: PeerConfiguration) extends Actor with ActorLogging {
  import context.dispatcher
  import PeerManager._

  private var peers = Map[String, Peer]()
  private var peersHandshaked = Map[String, Peer]()
  private var peersGoingToConnect = Map[String, Peer]()
  private var droppedNodes = Set[String]()

  private def incomingPeers = peersHandshaked.collect { case (id, p: IncomingPeer) => (id -> p) }
  private def outgoingPeers = peersHandshaked.collect { case (id, p: OutgoingPeer) => (id -> p) }

  private val serviceBoard = ServiceBoard(context.system)
  private def nodeDiscovery = NodeDiscoveryService.proxy(context.system)

  private def scheduler = context.system.scheduler

  override val supervisorStrategy = OneForOneStrategy() {
    case _ => SupervisorStrategy.Restart
  }

  scheduler.schedule(peerConfiguration.updateNodesInitialDelay, peerConfiguration.updateNodesInterval) {
    if (nodeDiscovery ne null) { // we are not sure whether nodeDiscovery started
      nodeDiscovery ! NodeDiscoveryService.GetDiscoveredNodes
    }
  }

  //knownNodesService ! KnownNodesService.GetKnownNodes

  override def receive: Receive = {
    case KnownNodesService.KnownNodes(addresses) =>
      // toMap to make sure only one node of same interface:port is left
      val idToNode = addresses.map(uri => (Peer.peerId(uri), uri)).toMap
      val urisToConnect = idToNode.filterNot {
        case (peerId, uri) => isInConnecting(peerId)
      }.take(peerConfiguration.maxPeers)

      if (urisToConnect.nonEmpty) {
        log.debug("[peer] Trying to connect to {} known nodes", urisToConnect.size)
        urisToConnect foreach {
          case (peerId, uri) => connectTo(peerId, uri)
        }
      }

    case NodeDiscoveryService.DiscoveredNodes(nodes) =>
      // toMap to make sure only one node of same interface:port is left
      val idToNode = nodes.map(node => (Peer.peerId(node.uri), node)).toMap
      val urisToConnect = idToNode.filterNot {
        case (peerId, uri) => isInConnecting(peerId) || droppedNodes.contains(peerId)
      }.toList.sortBy(-_._2.addTimestamp)
        .take(peerConfiguration.maxPeers - peersHandshaked.size).map(x => (x._1, x._2.uri))

      if (urisToConnect.nonEmpty) {
        log.info(s"""|[disc] Discovered ${nodes.size} nodes (${droppedNodes.size} dropped), handshaked ${peersHandshaked.size}/${peerConfiguration.maxPeers + peerConfiguration.maxIncomingPeers} 
        |(in/out): (${incomingPeers.size}/${outgoingPeers.size}). Connecting to ${urisToConnect.size} more nodes.""".stripMargin.replace("\n", " "))
        urisToConnect foreach {
          case (peerId, uri) => connectTo(peerId, uri)
        }
      }

    case PeerEntity.PeerEntityCreated(peer) =>
      if (peer.entity eq null) {
        log.warning(s"[peer] PeerEntityCreated: $peer entity is null")
      } else {
        peers += (peer.id -> peer)
        peersGoingToConnect -= peer.id
      }

    case PeerEntity.PeerHandshaked(peer, peerInfo) =>
      peers.get(peer.id) match {
        case Some(peer) =>
          log.debug(s"[peer] PeerHandshaked: $peer")
          peersHandshaked += (peer.id -> peer)
        case None =>
          log.warning(s"PeerHandshaked $peer does not found in peers")
      }

    case PeerEntity.PeerDisconnected(peerId) =>
      log.debug(s"[peer] PeerDisconnected: $peerId")
      peersHandshaked -= peerId

    case DropNode(peerId) =>
      log.debug(s"[peer] Dropped: $peerId")
      droppedNodes += peerId

    case GetPeers =>
      getPeers().pipeTo(sender())

    case SendMessage(peerId, message) =>
      peersHandshaked.get(peerId) foreach { _.entity ! PeerEntity.MessageToPeer(peerId, message) }

    case PeerEntity.PeerEntityStopped(peerId) =>
      log.debug(s"[peer] PeerEntityStopped: $peerId")
      peers -= peerId
      peersHandshaked -= peerId
  }

  private def connectTo(peerId: String, uri: URI) {
    if (outgoingPeers.size < peerConfiguration.maxPeers) {
      log.debug(s"[peer] Connecting to $peerId - $uri")

      val peer = new OutgoingPeer(peerId, uri)
      peersGoingToConnect += (peerId -> peer)

      import serviceBoard.materializer

      val authHandshake = AuthHandshake(serviceBoard.nodeKey, serviceBoard.secureRandom)
      val handshake = new EtcHandshake(serviceBoard.nodeStatus, serviceBoard.blockchain, serviceBoard.storages.appStateStorage, peerConfiguration, serviceBoard.forkResolverOpt)
      try {
        RLPx.startOutgoing(peer, serviceBoard.messageDecoder, serviceBoard.protocolVersion, authHandshake, handshake)(context.system)
      } catch {
        case e: Throwable =>
          log.error("[peer] error during connect to $peer", e)
      }
    } else {
      log.debug("[peer] Maximum number of connected peers reached.")
    }
  }

  private def isInConnecting(peerId: String): Boolean = {
    peersGoingToConnect.contains(peerId) || peers.contains(peerId)
  }

  private def getPeers(): Future[Peers] = {
    implicit val timeout = Timeout(2.seconds)

    Future.traverse(peersHandshaked.values) { peer =>
      (peer.entity ? PeerEntity.GetStatus).mapTo[PeerEntity.StatusResponse] map {
        sr => Success((peer, sr.status))
      }
    } map {
      r => Peers.apply(r.collect { case Success(v) => v }.toMap)
    }
  }

}

