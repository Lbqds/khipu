package khipu.network.rlpx

import akka.actor.ActorRef
import java.net.InetSocketAddress
import java.net.URI

object Peer {
  def peerId(uri: URI): String = peerId(new InetSocketAddress(uri.getHost, uri.getPort))
  def peerId(remoteAddress: InetSocketAddress): String = {
    val ip = remoteAddress.getAddress.getHostAddress
    val port = remoteAddress.getPort
    s"$ip:$port"
  }

  def uri(nodeId: Array[Byte], socketAddress: InetSocketAddress) = {
    new URI(s"enode://${khipu.toHexString(nodeId)}@${socketAddress.getHostName}:${socketAddress.getPort}")
  }
}
sealed trait Peer {
  val id: String

  def remoteAddress: InetSocketAddress

  protected var _uri: Option[URI]
  def uri = _uri
  def uri_=(uri: URI) {
    _uri = Some(uri)
  }

  private var _entity: ActorRef = null
  def entity = _entity
  /**
   * To make sure that peerEntity is at the same host of connection, always create
   * it from the TCP stage
   */
  def entity_=(_entity: ActorRef) {
    this._entity = _entity
  }
}

final class OutgoingPeer(val id: String, theUri: URI) extends Peer {
  protected var _uri: Option[URI] = Some(theUri)
  val remoteAddress = new InetSocketAddress(theUri.getHost, theUri.getPort)

  override def toString = s"OutgoingPeer($id)"
}

final class IncomingPeer(val id: String, val remoteAddress: InetSocketAddress) extends Peer {
  protected var _uri: Option[URI] = None // wil be set later

  override def toString = s"IncomingPeer($id)"
}