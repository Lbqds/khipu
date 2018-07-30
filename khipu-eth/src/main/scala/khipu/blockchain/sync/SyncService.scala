package khipu.blockchain.sync

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.client.ClusterClientReceptionist
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.cluster.singleton.ClusterSingletonProxy
import akka.cluster.singleton.ClusterSingletonProxySettings
import akka.pattern.ask
import khipu.crypto
import khipu.domain.Block
import khipu.domain.Blockchain
import khipu.network.p2p.Message
import khipu.network.rlpx.PeerConfiguration
import khipu.ledger.Ledger
import khipu.ommers.OmmersPool
import khipu.store.AppStateStorage
import khipu.store.FastSyncStateStorage
import khipu.transactions.PendingTransactionsService
import khipu.util.Config.Sync._
import khipu.util.MiningConfig
import khipu.validators.Validators
import scala.concurrent.duration._

object SyncService {
  def props(
    ledger:               Ledger.I,
    validators:           Validators,
    blockchain:           Blockchain,
    appStateStorage:      AppStateStorage,
    fastSyncStateStorage: FastSyncStateStorage,
    miningConfig:         MiningConfig,
    peerConfiguration:    PeerConfiguration
  ) = Props(classOf[SyncService], ledger, validators, blockchain, appStateStorage, fastSyncStateStorage, miningConfig, peerConfiguration).withDispatcher("khipu-sync-pinned-dispatcher")

  val name = "syncService"
  val managerName = "khipuSingleton-" + name
  val managerPath = "/user/" + managerName
  val path = managerPath + "/" + name
  val proxyName = "khipuSingletonProxy-" + name
  val proxyPath = "/user/" + proxyName

  def start(system: ActorSystem, role: Option[String],
            ledger:               Ledger.I,
            validators:           Validators,
            blockchain:           Blockchain,
            appStateStorage:      AppStateStorage,
            fastSyncStateStorage: FastSyncStateStorage,
            miningConfig:         MiningConfig,
            peerConfiguration:    PeerConfiguration): ActorRef = {
    val settings = ClusterSingletonManagerSettings(system).withRole(role).withSingletonName(name)
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = props(ledger, validators, blockchain, appStateStorage, fastSyncStateStorage, miningConfig, peerConfiguration),
        terminationMessage = PoisonPill,
        settings = settings
      ), name = managerName
    )
  }

  def startProxy(system: ActorSystem, role: Option[String]): ActorRef = {
    val settings = ClusterSingletonProxySettings(system).withRole(role).withSingletonName(name)
    val proxy = system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = managerPath,
        settings = settings
      ), name = proxyName
    )
    ClusterClientReceptionist(system).registerService(proxy)
    proxy
  }

  def proxy(system: ActorSystem) = system.actorSelection(proxyPath)

  final case class ReceivedMessage(peerId: String, response: Message)

  final case class MinedBlock(block: Block)

  case object StartSync
  case object ReportStatusTask
  case object ReportStatusTick
  case object FastSyncDone
}
class SyncService(
    protected val ledger:               Ledger,
    protected val validators:           Validators,
    protected val blockchain:           Blockchain,
    protected val appStateStorage:      AppStateStorage,
    protected val fastSyncStateStorage: FastSyncStateStorage,
    miningConfig:                       MiningConfig,
    protected val peerConfiguration:    PeerConfiguration
) extends FastSyncService with RegularSyncService {
  import SyncService._
  import context.dispatcher

  protected val blockchainStorages = blockchain.storages

  // This should be cluster single instance only, instant it in SyncService
  val ommersPool: ActorRef = context.actorOf(OmmersPool.props(blockchain, miningConfig), "ommersPool")

  protected def pendingTransactionsService = PendingTransactionsService.proxy(context.system)

  override def postStop() {
    log.info("SyncService stopped")
    super.postStop()
  }

  override def receive: Receive = idle

  def idle: Receive = peerUpdateBehavior orElse {
    case StartSync =>
      appStateStorage.putSyncStartingBlock(appStateStorage.getBestBlockNumber)
      (appStateStorage.isFastSyncDone, doFastSync) match {
        case (false, true) =>
          startFastSync()

        case (true, true) =>
          log.debug(s"do-fast-sync is set to $doFastSync but fast sync won't start because it already completed")
          startRegularSync()

        case (true, false) =>
          startRegularSync()

        case (false, false) =>
          fastSyncStateStorage.purge()
          startRegularSync()
      }

      timers.startPeriodicTimer(ReportStatusTask, ReportStatusTick, reportStatusInterval)

    case FastSyncDone =>
      startRegularSync()
  }

  def ommersBehavior: Receive = {
    case x: OmmersPool.GetOmmers =>
      ommersPool forward x
  }
}
