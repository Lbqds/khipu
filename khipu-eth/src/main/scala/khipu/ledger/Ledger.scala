package khipu.ledger

import akka.actor.ActorSystem
import akka.event.Logging
import akka.pattern.ask
import akka.util.ByteString
import java.math.BigInteger
import khipu.Hash
import khipu.domain.Account
import khipu.domain.Address
import khipu.domain.Block
import khipu.domain.Blockchain
import khipu.domain.BlockHeader
import khipu.domain.Receipt
import khipu.domain.SignedTransaction
import khipu.domain.Transaction
import khipu.domain.TxLogEntry
import khipu.validators._
import khipu.util.BlockchainConfig
import khipu.validators.BlockValidator
import khipu.validators.SignedTransactionValidator
import khipu.vm.EvmConfig
import khipu.vm.OutOfGas
import khipu.vm.PrecompiledContracts
import khipu.vm.Program
import khipu.vm.ProgramContext
import khipu.vm.ProgramError
import khipu.vm.ProgramResult
import khipu.vm.ProgramState
import khipu.vm.VM
import khipu.vm.UInt256
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

/**
 * EIP-161
 * https://github.com/ethereum/ethereumj/commit/7a08a68c9eb1515737739754093d5668864c69cb#diff-1217705a51746d79276a325e55ec22b5
 */
object Ledger {

  trait I {
    def executeBlock(block: Block, validators: Validators): Either[BlockExecutionError, BlockResult]
    def prepareBlock(block: Block, validators: Validators): BlockPreparationResult
    def simulateTransaction(stx: SignedTransaction, blockHeader: BlockHeader): TxResult
    def validateBlocksBeforeExecution(blocks: Seq[Block], validators: Validators): (Vector[Block], Option[BlockExecutionError])
  }

  type PC = ProgramContext[BlockWorldState, TrieStorage]
  type PR = ProgramResult[BlockWorldState, TrieStorage]

  final case class BlockResult(world: BlockWorldState, gasUsed: Long = 0, receipts: Seq[Receipt] = Nil, parallelCount: Int, dbReadTimePercent: Double)
  final case class BlockPreparationResult(block: Block, blockResult: BlockResult, stateRootHash: Hash)
  final case class TxResult(stx: SignedTransaction, world: BlockWorldState, gasUsed: Long, txFee: UInt256, logs: Seq[TxLogEntry], touchedAddresses: Set[Address], vmReturnData: ByteString, error: Option[ProgramError], isRevert: Boolean, parallelRaceConditions: Set[ProgramState.ParallelRace])

  sealed trait BlockExecutionError { def reason: String }
  final case class ValidationBeforeExecError(reason: String) extends BlockExecutionError
  final case class StateBeforeFailure(worldState: BlockWorldState, cumGas: Long, cumReceipts: Vector[Receipt])
  final case class TxsExecutionError(stx: SignedTransaction, stateBeforeError: StateBeforeFailure, error: SignedTransactionError) extends BlockExecutionError { def reason = error.toString }
  final case class ValidationAfterExecError(reason: String) extends BlockExecutionError

  trait BlockPreparationError
  final case class TxError(reason: String) extends BlockPreparationError

  /**
   * v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price) + Tv (Tx value). See YP equation number (65)
   *
   * @param tx Target transaction
   * @return Upfront cost
   */
  private def calculateUpfrontCost(tx: Transaction): UInt256 =
    calculateUpfrontGas(tx) + UInt256(tx.value)

  /**
   * v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price). See YP equation number (68)
   *
   * @param tx Target transaction
   * @return Upfront cost
   */
  private def calculateUpfrontGas(tx: Transaction): UInt256 =
    UInt256(BigInteger.valueOf(tx.gasLimit) multiply tx.gasPrice)

}
final class Ledger(blockchain: Blockchain, blockchainConfig: BlockchainConfig)(implicit system: ActorSystem) extends Ledger.I {
  import Ledger._
  import system.dispatcher

  private val log = Logging(system, this.getClass)

  val txProcessTimeout = 180.seconds
  val blockRewardCalculator = new BlockRewardCalculator(blockchainConfig)

  val txProcessor = system.actorOf(TxProcessor.props(this), "TxProcessor")

  /**
   * called by minning
   */
  override def prepareBlock(
    block:      Block,
    validators: Validators
  ): BlockPreparationResult = {
    val parentStateRoot = blockchain.getBlockHeaderByHash(block.header.parentHash).map(_.stateRoot)
    val initialWorld = blockchain.getReadOnlyWorldState(None, blockchainConfig.accountStartNonce, parentStateRoot)

    val (execResult @ BlockResult(resultingWorldState, _, _, _, _), txExecuted) =
      executePreparedTransactions(block.body.transactionList, initialWorld, block.header, validators.signedTransactionValidator) //match {

    val worldRewardPaid = payBlockReward(block)(resultingWorldState)
    val worldPersisted = worldRewardPaid.commit().persist()
    BlockPreparationResult(block.copy(body = block.body.copy(transactionList = txExecuted)), execResult, worldPersisted.stateRootHash)
  }

  @tailrec
  private def executePreparedTransactions(
    signedTransactions:         Seq[SignedTransaction],
    world:                      BlockWorldState,
    blockHeader:                BlockHeader,
    signedTransactionValidator: SignedTransactionValidator,
    accGas:                     Long                       = 0,
    accReceipts:                Vector[Receipt]            = Vector(),
    executed:                   Vector[SignedTransaction]  = Vector()
  ): (BlockResult, Seq[SignedTransaction]) = {
    val evmCfg = EvmConfig.forBlock(blockHeader.number, blockchainConfig)

    executeTransactions_sequential(signedTransactions, blockHeader, signedTransactionValidator, evmCfg)(world) match {
      case Left(TxsExecutionError(stx, StateBeforeFailure(worldState, gas, receipts), reason)) =>
        //log.debug(s"failure while preparing block because of $reason in transaction with hash ${stx.hashAsHexString}")
        val txIndex = signedTransactions.indexWhere(tx => tx.hash == stx.hash)
        executePreparedTransactions(
          signedTransactions.drop(txIndex + 1),
          worldState, blockHeader, signedTransactionValidator, gas, receipts, executed ++ signedTransactions.take(txIndex)
        )
      case Right(br) => (br, executed ++ signedTransactions)
    }
  }

  override def simulateTransaction(stx: SignedTransaction, blockHeader: BlockHeader): TxResult = {
    val start = System.currentTimeMillis

    val gasLimit = stx.tx.gasLimit
    val evmCfg = EvmConfig.forBlock(blockHeader.number, blockchainConfig)

    val world1 = blockchain.getReadOnlyWorldState(None, blockchainConfig.accountStartNonce, Some(blockHeader.stateRoot))
    val world2 = if (world1.getAccount(stx.sender).isEmpty) {
      world1.saveAccount(stx.sender, Account.empty(blockchainConfig.accountStartNonce))
    } else {
      world1
    }

    val (checkpoint, context) = prepareProgramContext(stx, blockHeader, evmCfg)(world2)
    val result = runVM(stx, context, evmCfg)(checkpoint)

    val totalGasToRefund = calcTotalGasToRefund(gasLimit, result)
    val gasUsed = stx.tx.gasLimit - totalGasToRefund
    val txFee = UInt256(gasUsed) * UInt256(stx.tx.gasPrice)

    val elapsed = System.currentTimeMillis - start
    TxResult(stx, result.world, gasUsed, txFee, result.txLogs, result.addressesTouched, result.returnData, result.error, result.isRevert, result.parallelRaceConditions)
  }

  def validateBlocksBeforeExecution(blocks: Seq[Block], validators: Validators): (Vector[Block], Option[BlockExecutionError]) = {
    val start = System.currentTimeMillis

    val validatingBlocks = blocks.map(block => block.header.hash -> block).toMap

    val fs = blocks map { block =>
      (txProcessor ? TxProcessor.PreValidateWork(block, validatingBlocks, validators))(txProcessTimeout).mapTo[Either[BlockExecutionError, Unit]] map (block -> _)
    }

    val f = Future.sequence(fs) map { rs =>
      var itr = rs.iterator
      var errorOpt: Option[BlockExecutionError] = None
      var validatedBlocks = Vector[Block]()
      while (itr.hasNext && errorOpt.isEmpty) {
        itr.next() match {
          case (block, Left(error)) => errorOpt = Some(error)
          case (block, Right(_))    => validatedBlocks :+= block
        }
      }

      log.debug(s"pre-validated ${validatedBlocks.size} blocks parallel in ${(System.currentTimeMillis - start)}ms ${errorOpt.fold("")(x => x.toString)}")
      (validatedBlocks, errorOpt)
    }
    Await.result(f, Duration.Inf)
  }

  private[ledger] def validateBlockBeforeExecution(block: Block, validatingBlocks: Map[Hash, Block], validators: Validators): Either[BlockExecutionError, Unit] = {
    val result = for {
      _ <- validators.blockHeaderValidator.validate(block.header, blockchain, validatingBlocks)
      _ <- validators.blockValidator.validateHeaderAndBody(block.header, block.body)
      _ <- validators.ommersValidator.validate(block.header.number, block.body.uncleNodesList, blockchain, validatingBlocks)
    } yield ()
    result.left.map(error => ValidationBeforeExecError(error.toString))
  }

  /**
   * Execute and validate on minned block
   */
  override def executeBlock(block: Block, validators: Validators): Either[BlockExecutionError, BlockResult] = {

    val start1 = System.currentTimeMillis
    val parallelResult = for {
      blockResult <- executeBlockTransactions(block, validators.signedTransactionValidator, isParallel = true)
      _ = log.debug(s"${block.header.number} parallel-executed in ${System.currentTimeMillis - start1}ms")

      start2 = System.currentTimeMillis
      worldRewardPaid = payBlockReward(block)(blockResult.world)
      worldCommitted = worldRewardPaid.commit() // State root hash needs to be up-to-date for validateBlockAfterExecution
      _ = log.debug(s"${block.header.number} committed in ${System.currentTimeMillis - start2}ms")

      start3 = System.currentTimeMillis
      _ <- validateBlockAfterExecution(block, worldCommitted.stateRootHash, blockResult.receipts, blockResult.gasUsed, validators.blockValidator)
      _ = log.debug(s"${block.header.number} post-validated in ${System.currentTimeMillis - start3}ms")
    } yield (blockResult, worldCommitted)

    parallelResult match {
      case Right((blockResult, worldCommitted)) =>
        val start4 = System.currentTimeMillis
        Right(blockResult)

      case left @ Left(error) =>
        log.debug(s"in parallel failed with error $error, try sequential ...")
        val start1 = System.currentTimeMillis
        for {
          blockResult <- executeBlockTransactions(block, validators.signedTransactionValidator, isParallel = false)
          _ = log.debug(s"${block.header.number} sequential-executed in ${System.currentTimeMillis - start1}ms")

          worldRewardPaid = payBlockReward(block)(blockResult.world)
          worldCommitted = worldRewardPaid.commit() // State root hash needs to be up-to-date for validateBlockAfterExecution

          _ <- validateBlockAfterExecution(block, worldCommitted.stateRootHash, blockResult.receipts, blockResult.gasUsed, validators.blockValidator)
        } yield blockResult
    }
  }

  /**
   * This function runs transaction
   *
   * @param block
   * @param blockchain
   * @param signedTransactionValidator
   */
  private def executeBlockTransactions(
    block:        Block,
    stxValidator: SignedTransactionValidator,
    isParallel:   Boolean
  ): Either[BlockExecutionError, BlockResult] = {
    val parentStateRoot = blockchain.getBlockHeaderByHash(block.header.parentHash).map(_.stateRoot)
    val evmCfg = EvmConfig.forBlock(block.header.number, blockchainConfig)

    def initialWorld = blockchain.getWorldState(block.header.number, blockchainConfig.accountStartNonce, parentStateRoot)

    if (isParallel) {
      val f = executeTransactions_inparallel(block.body.transactionList, block.header, stxValidator, evmCfg)(initialWorld)
      Await.result(f, Duration.Inf)
    } else {
      executeTransactions_sequential(block.body.transactionList, block.header, stxValidator, evmCfg)(initialWorld)
    }
  }

  /**
   * This functions executes all the signed transactions from a block (till one of those executions fails)
   *
   * @param signedTransactions from the block that are left to execute
   * @param blockHeader of the block we are currently executing
   * @param stxValidator used to validate the signed transactions
   * @param evmCfg evm config
   * @param world that will be updated by the execution of the signedTransactions
   * @return a BlockResult if the execution of all the transactions in the block was successful or a BlockExecutionError
   *         if one of them failed
   */
  private def executeTransactions_sequential(
    signedTransactions: Seq[SignedTransaction],
    blockHeader:        BlockHeader,
    stxValidator:       SignedTransactionValidator,
    evmCfg:             EvmConfig
  )(initialWorld: BlockWorldState): Either[TxsExecutionError, BlockResult] = {
    var currWorld = initialWorld
    var txError: Option[TxsExecutionError] = None
    var txResults = Vector[TxResult]()

    val itr = signedTransactions.iterator
    while (itr.hasNext && txError.isEmpty) {
      val stx = itr.next()
      validateAndExecuteTransaction(stx, blockHeader, stxValidator, evmCfg)(currWorld.withTx(Some(stx))) match {
        case Right(txResult) =>
          currWorld = txResult.world
          txResults = txResults :+ txResult
        case Left(error) =>
          txError = Some(error)
      }
    }

    txError match {
      case Some(error) => Left(error)
      case None        => Right(postExecuteTransactions(blockHeader, evmCfg, txResults, 0, 0.0)(currWorld.withTx(None)))
    }
  }

  private def executeTransactions_inparallel(
    signedTransactions: Seq[SignedTransaction],
    blockHeader:        BlockHeader,
    stxValidator:       SignedTransactionValidator,
    evmCfg:             EvmConfig
  )(initialWorldFun: => BlockWorldState): Future[Either[TxsExecutionError, BlockResult]] = {
    val nTx = signedTransactions.size.toDouble

    val start = System.currentTimeMillis
    blockchain.storages.nodeStorage.source.clock.start()
    blockchain.storages.accountNodeDataSource.clock.start()
    blockchain.storages.storageNodeDataSource.clock.start()

    val fs = signedTransactions.map(stx => stx -> initialWorldFun.withTx(Some(stx))) map {
      case (stx, worldCopy) =>
        (txProcessor ? TxProcessor.ExecuteWork(worldCopy, stx, blockHeader, stxValidator, evmCfg))(txProcessTimeout).mapTo[(Either[TxsExecutionError, TxResult], Long)] // recover { case ex => s"$ex.getMessage" }
    }

    Future.sequence(fs) map { rs =>
      val dsGetElapsed1 = blockchain.storages.nodeStorage.source.clock.elasped + blockchain.storages.accountNodeDataSource.clock.elasped + blockchain.storages.storageNodeDataSource.clock.elasped
      blockchain.storages.nodeStorage.source.clock.start()
      blockchain.storages.accountNodeDataSource.clock.start()
      blockchain.storages.storageNodeDataSource.clock.start()

      val (results, elapses) = rs.unzip
      val elapsed = elapses.sum
      log.debug(s"${blockHeader.number} executed parallel in ${(System.currentTimeMillis - start)}ms, db get ${100.0 * dsGetElapsed1 / elapsed}%")

      var currWorld: Option[BlockWorldState] = None
      var txError: Option[TxsExecutionError] = None
      var txResults = Vector[TxResult]()
      var parallelCount = 0

      // re-execute tx under prevWorld, commit prevWorld to get all nodes exist, see BlockWorldState.getStorage and getStateRoott
      var reExecutedElapsed = 0L
      def reExecute(stx: SignedTransaction, prevWorld: BlockWorldState) = {
        var start = System.currentTimeMillis
        log.debug(s"${stx.hash} re-executing")
        // should commit prevWorld's state, since we may need to get newest account/storage/code by new state's hash
        validateAndExecuteTransaction(stx, blockHeader, stxValidator, evmCfg)(prevWorld.commit().withTx(Some(stx))) match {
          case Left(error) => txError = Some(error)
          case Right(newTxResult) =>
            currWorld = Some(newTxResult.world)
            txResults = txResults :+ newTxResult
        }
        reExecutedElapsed += (System.currentTimeMillis - start)
      }

      val itr = results.iterator
      while (itr.hasNext && txError.isEmpty) {
        val r = itr.next()
        r match {
          case Right(txResult) =>
            currWorld match {
              case None => // first tx
                parallelCount += 1
                currWorld = Some(txResult.world)
                txResults = txResults :+ txResult

              case Some(prevWorld) =>
                if (txResult.parallelRaceConditions.nonEmpty) {
                  log.debug(s"tx ${txResult.stx.hash} potential parallel race conditions ${txResult.parallelRaceConditions} occurred during executing")
                  // when potential parallel race conditions occurred during executing, it's difficult to judge if it was caused by conflict, so, just re-execute
                  reExecute(txResult.stx, prevWorld)
                } else {
                  prevWorld.merge(txResult.world) match {
                    case Left(raceCondiftions) =>
                      log.debug(s"tx ${txResult.stx.hash} has race conditions with prev world state:\n$raceCondiftions")
                      reExecute(txResult.stx, prevWorld)

                    case Right(mergedWorld) =>
                      parallelCount += 1
                      currWorld = Some(mergedWorld)
                      txResults = txResults :+ txResult
                  }
                }
            }

          case Left(error @ TxsExecutionError(stx, _, SignedTransactionError.TransactionSenderCantPayUpfrontCostError(_, _))) =>
            currWorld match {
              case None => txError = Some(error) // first tx
              case Some(prevWorld) =>
                reExecute(stx, prevWorld)
            }

          case Left(error) => txError = Some(error)
        }

        log.debug(s"${blockHeader.number} touched accounts (${r.fold(_.stx, _.stx).hash}):\n ${currWorld.map(_.touchedAccounts.mkString("\n", "\n", "\n")).getOrElse("")}")
      }

      val dsGetElapsed2 = blockchain.storages.nodeStorage.source.clock.elasped + blockchain.storages.accountNodeDataSource.clock.elasped + blockchain.storages.storageNodeDataSource.clock.elasped

      val parallelRate = if (parallelCount > 0) {
        parallelCount * 100.0 / nTx
      } else {
        0.0
      }
      val dbTimePercent = 100.0 * (dsGetElapsed1 + dsGetElapsed2) / (elapsed + reExecutedElapsed)

      log.debug(s"${blockHeader.number} re-executed in ${reExecutedElapsed}ms, ${100 - parallelRate}% with race conditions, db get ${100.0 * dsGetElapsed2 / reExecutedElapsed}%")
      log.debug(s"${blockHeader.number} touched accounts:\n ${currWorld.map(_.touchedAccounts.mkString("\n", "\n", "\n")).getOrElse("")}")

      txError match {
        case Some(error) => Left(error)
        case None        => Right(postExecuteTransactions(blockHeader, evmCfg, txResults, parallelCount, dbTimePercent)(currWorld.map(_.withTx(None)).getOrElse(initialWorldFun)))
      }

    } andThen {
      case Success(_)  =>
      case Failure(ex) => log.error(ex.getMessage, ex)
    }
  }

  private def postExecuteTransactions(
    blockHeader:   BlockHeader,
    evmCfg:        EvmConfig,
    txResults:     Vector[TxResult],
    parallelCount: Int,
    dbTimePercent: Double
  )(world: BlockWorldState): BlockResult = {
    val (accGas, accTxFee, accTouchedAddresses, accReceipts) = txResults.foldLeft(0L, UInt256.Zero, Set[Address](), Vector[Receipt]()) {
      case ((accGas, accTxFee, accTouchedAddresses, accReceipts), TxResult(stx, worldAfterTx, gasUsed, txFee, logs, touchedAddresses, _, error, isRevert, _)) =>

        val postTxState = if (evmCfg.eip658) {
          if (error.isDefined || isRevert) Receipt.Failure else Receipt.Success
        } else {
          worldAfterTx.stateRootHash
          //worldAfterTx.commit().stateRootHash // TODO here if get stateRootHash, should commit first, but then how about parallel running? how about sending a lazy evaulate function instead of value?
        }

        log.debug(s"Tx ${stx.hash} gasLimit: ${stx.tx.gasLimit}, gasUsed: $gasUsed, cumGasUsed: ${accGas + gasUsed}")

        val receipt = Receipt(
          postTxState = postTxState,
          cumulativeGasUsed = accGas + gasUsed,
          logsBloomFilter = BloomFilter.create(logs),
          logs = logs
        )

        (accGas + gasUsed, accTxFee + txFee, accTouchedAddresses ++ touchedAddresses, accReceipts :+ receipt)
    }

    val minerAddress = Address(blockHeader.beneficiary)
    val worldPayMinerForGas = world.pay(minerAddress, accTxFee)

    // find empty touched accounts to be deleted
    val deadAccounts = if (evmCfg.eip161) {
      (accTouchedAddresses + minerAddress) filter (worldPayMinerForGas.isAccountDead)
    } else {
      Set[Address]()
    }
    //log.debug(s"touched accounts: ${result.addressesTouched}, miner: $minerAddress")
    log.debug(s"dead accounts accounts: $deadAccounts")
    val worldDeletedDeadAccounts = deleteAccounts(deadAccounts)(worldPayMinerForGas)

    log.debug(s"$blockHeader, accGas $accGas, receipts = $accReceipts")
    BlockResult(worldDeletedDeadAccounts, accGas, accReceipts, parallelCount, dbTimePercent)
  }

  // TODO see TODO at lines
  private[ledger] def validateAndExecuteTransaction(
    stx:          SignedTransaction,
    blockHeader:  BlockHeader,
    stxValidator: SignedTransactionValidator,
    evmCfg:       EvmConfig
  )(world: BlockWorldState): Either[TxsExecutionError, TxResult] = {

    val (senderAccount, worldForTx) = world.getAccount(stx.sender) match {
      case Some(account) => (account, world)
      case None =>
        val emptyAccount = world.emptyAccount
        (emptyAccount, world.saveAccount(stx.sender, emptyAccount))
    }

    val upfrontCost = calculateUpfrontCost(stx.tx)

    stxValidator.validate(stx, senderAccount, blockHeader, upfrontCost, accumGasUsed = 0L) match { // TODO validate accumGasUsed lazily for asyn execution
      case Right(_) | Left(SignedTransactionError.TransactionNonceError(_, _)) => // TODO validate TransactionNonceError lazily for async execution
        Right(executeTransaction(stx, blockHeader, evmCfg)(worldForTx))

      case Left(error) =>
        Left(TxsExecutionError(stx, StateBeforeFailure(world, 0L, Vector()), error)) // TODO content of StateBeforeFailure
    }
  }

  private def executeTransaction(
    stx:         SignedTransaction,
    blockHeader: BlockHeader,
    evmCfg:      EvmConfig
  )(world: BlockWorldState): TxResult = {
    val start = System.currentTimeMillis

    // TODO catch prepareProgramContext's throwable (MPTException etc from mtp) here
    val (checkpoint, context) = prepareProgramContext(stx, blockHeader, evmCfg)(world)

    val result = runVM(stx, context, evmCfg)(checkpoint)

    val gasLimit = stx.tx.gasLimit
    val totalGasToRefund = calcTotalGasToRefund(gasLimit, result)
    val gasUsed = stx.tx.gasLimit - totalGasToRefund
    val gasPrice = UInt256(stx.tx.gasPrice)
    val txFee = UInt256(gasUsed) * gasPrice
    val refund = UInt256(totalGasToRefund) * gasPrice

    // print trace
    //log.info(s"\nTx 0x${stx.hash} executing ${result.trace.mkString("\n", "\n", "\n")}")
    //log.info(s"0x${stx.hash} gasLimit: ${stx.tx.gasLimit} gasUsed $gasUsed, isRevert: ${result.isRevert}, error: ${result.error}")

    val worldRefundGasPaid = result.world.pay(stx.sender, refund)
    val worldDeletedAccounts = deleteAccounts(result.addressesToDelete)(worldRefundGasPaid)

    //log.debug(
    //  s"""Transaction 0x${stx.hashAsHexString} execution end. Summary:
    //     | - Value: ${stx.tx.value}
    //     | - Error: ${result.error}.
    //     | - Total Gas to Refund: $totalGasToRefund
    //     | - Execution gas paid to miner: $txFee""".stripMargin
    //)

    val elapsed = System.currentTimeMillis - start
    TxResult(stx, worldDeletedAccounts, gasUsed, txFee, result.txLogs, result.addressesTouched, result.returnData, result.error, result.isRevert, result.parallelRaceConditions)
  }

  /**
   * This function validates that the various results from execution are consistent with the block. This includes:
   *   - Validating the resulting stateRootHash
   *   - Doing BlockValidator.validateBlockReceipts validations involving the receipts
   *   - Validating the resulting gas used
   *
   * @param block to validate
   * @param stateRootHash from the resulting state trie after executing the txs from the block
   * @param receipts associated with the execution of each of the tx from the block
   * @param gasUsed, accumulated gas used for the execution of the txs from the block
   * @param blockValidator used to validate the receipts with the block
   * @return None if valid else a message with what went wrong
   */
  private def validateBlockAfterExecution(
    block:          Block,
    stateRootHash:  Hash,
    receipts:       Seq[Receipt],
    gasUsed:        Long,
    blockValidator: BlockValidator
  ): Either[BlockExecutionError, Unit] = {
    if (block.header.gasUsed != gasUsed) {
      Left(ValidationAfterExecError(s"Block ${block.header.number} has invalid gas used, expected ${block.header.gasUsed} but got $gasUsed"))
    } else if (block.header.stateRoot != stateRootHash) {
      Left(ValidationAfterExecError(s"Block ${block.header.number} has invalid state root hash, expected ${block.header.stateRoot.hexString} but got ${stateRootHash.hexString}"))
    } else {
      blockValidator.validateBlockAndReceipts(block, receipts) match {
        case Left(error) => Left(ValidationAfterExecError(error.toString))
        case right       => Right(())
      }
    }
  }

  /**
   * This function updates state in order to pay rewards based on YP section 11.3
   *
   * @param block
   * @param world
   * @return
   */
  private def payBlockReward(block: Block)(world: BlockWorldState): BlockWorldState = {
    val minerAddress = Address(block.header.beneficiary)
    val minerAccount = getAccountToPay(minerAddress)(world)
    val minerReward = blockRewardCalculator.calcBlockMinerReward(block.header.number, block.body.uncleNodesList.size)
    val afterMinerReward = world.saveAccount(minerAddress, minerAccount.increaseBalance(UInt256(minerReward)))
    log.debug(s"Paying block ${block.header.number} reward of $minerReward to miner with account address $minerAddress")

    block.body.uncleNodesList.foldLeft(afterMinerReward) { (ws, ommer) =>
      val ommerAddress = Address(ommer.beneficiary)
      val account = getAccountToPay(ommerAddress)(ws)
      val ommerReward = blockRewardCalculator.calcOmmerMinerReward(block.header.number, ommer.number)
      log.debug(s"Paying block ${block.header.number} reward of $ommerReward to ommer with account address $ommerAddress")
      ws.saveAccount(ommerAddress, account.increaseBalance(UInt256(ommerReward)))
    }
  }

  private def getAccountToPay(address: Address)(world: BlockWorldState): Account = {
    world.getAccount(address).getOrElse(world.emptyAccount)
  }

  /**
   * Increments account nonce by 1 stated in YP equation (69) and
   * Pays the upfront Tx gas calculated as TxGasPrice * TxGasLimit from balance. YP equation (68)
   * remember the checkpoint world state
   * prepareProgtamContext
   * Note we use one fewer than the sender’s nonce
   * value; we assert that we have incremented the sender account’s
   * nonce prior to this call, and so the value used
   * is the sender’s nonce at the beginning of the responsible
   * transaction or VM operation
   */
  private def prepareProgramContext(stx: SignedTransaction, blockHeader: BlockHeader, evmCfg: EvmConfig)(world: BlockWorldState): (BlockWorldState, PC) = {
    val senderAddress = stx.sender
    val account = world.getGuaranteedAccount(senderAddress)
    val (checkpoint, worldAtCheckpoint) = {
      val worldx = world.withdraw(senderAddress, calculateUpfrontGas(stx.tx)).increaseNonce(senderAddress)
      (worldx.copy, worldx)
    }

    val (worldBeforeTransfer, recipientAddress, program) = if (stx.tx.isContractCreation) {
      val newContractAddress = worldAtCheckpoint.createAddress(senderAddress)
      val world = if (evmCfg.eip161) {
        worldAtCheckpoint.increaseNonce(newContractAddress)
      } else {
        worldAtCheckpoint
      }
      log.debug(s"newContractAddress: $newContractAddress")
      (world, newContractAddress, Program(stx.tx.payload.toArray))
    } else {
      val txReceivingAddress = stx.tx.receivingAddress.get
      log.debug(s"txReceivingAddress: $txReceivingAddress")
      (worldAtCheckpoint, txReceivingAddress, Program(world.getCode(txReceivingAddress).toArray))
    }

    val worldAfterTransfer = worldBeforeTransfer.transfer(senderAddress, recipientAddress, UInt256(stx.tx.value))
    val initialAddressesToDelete = Set[Address]()
    val initialAddressesTouched = Set(recipientAddress)

    val context: PC = ProgramContext(
      stx,
      recipientAddress,
      program,
      blockHeader,
      worldAfterTransfer,
      evmCfg,
      initialAddressesToDelete,
      initialAddressesTouched,
      isStaticCall = false
    )

    (checkpoint, context)
  }

  /**
   * @param checkpoint - world will return checkpoint if result error or isRevert
   */
  private def runVM(stx: SignedTransaction, context: PC, evmCfg: EvmConfig)(checkpoint: BlockWorldState): PR = {
    val r = if (stx.tx.isContractCreation) { // create
      VM.run(context)
    } else { // call
      PrecompiledContracts.getContractForAddress(context.targetAddress, evmCfg) match {
        case Some(contract) =>
          contract.run(context)
        case None =>
          VM.run(context)
      }
    }

    val result = if (stx.tx.isContractCreation && !r.error.isDefined && !r.isRevert) {
      saveCreatedContract(context.env.ownerAddr, r, evmCfg)
    } else {
      r
    }

    if (result.error.isDefined || result.isRevert) {
      // rollback to the world before transfer was done if an error happened
      // the error result may be caused by parallel conflict, so merge all possible modifies
      result.copy(world = checkpoint.mergeRaceConditions(result.world), addressesToDelete = Set(), addressesTouched = Set(), txLogs = Vector(), parallelRaceConditions = Set(ProgramState.OnError))
    } else {
      result
    }
  }

  private def saveCreatedContract(address: Address, result: PR, evmCfg: EvmConfig): PR = {
    val codeDepositCost = evmCfg.calcCodeDepositCost(result.returnData)

    if (result.gasRemaining < codeDepositCost) {
      if (evmCfg.exceptionalFailedCodeDeposit) {
        // TODO set returnData to empty bytes ByteString()?
        result.copy(error = Some(OutOfGas))
      } else {
        result
      }
    } else if (result.returnData.length > evmCfg.maxContractSize) {
      // contract size too large
      log.warning(s"Contract size too large: ${result.returnData.length}")
      // TODO set returnData to empty bytes ByteString()?
      result.copy(error = Some(OutOfGas))
    } else {
      // even result.isRevert? this is a different behavior from CREATE opcode? 
      result.copy(
        gasRemaining = result.gasRemaining - codeDepositCost,
        world = result.world.saveCode(address, result.returnData)
      )
    }
  }

  /**
   * Calculate total gas to be refunded
   * See YP, eq (72)
   */
  private def calcTotalGasToRefund(gasLimit: Long, result: PR): Long = {
    if (result.error.isDefined) {
      0
    } else {
      if (result.isRevert) {
        result.gasRemaining
      } else {
        val gasUsed = gasLimit - result.gasRemaining
        result.gasRemaining + math.min(gasUsed / 2, result.gasRefund)
      }
    }
  }

  /**
   * Delete all accounts (that appear in SUICIDE list). YP eq (78).
   * The contract storage should be cleared during pruning as nodes could be used in other tries.
   * The contract code is also not deleted as there can be contracts with the exact same code, making it risky to delete
   * the code of an account in case it is shared with another one.
   * FIXME: [EC-242]
   *   Should we delete the storage associated with the deleted accounts?
   *   Should we keep track of duplicated contracts for deletion?
   *
   * @param addressesToDelete
   * @param worldState
   * @return a worldState equal worldState except that the accounts from addressesToDelete are deleted
   */
  private def deleteAccounts(addressesToDelete: Set[Address])(worldState: BlockWorldState): BlockWorldState = {
    addressesToDelete.foldLeft(worldState) { case (world, address) => world.deleteAccount(address) }
  }
}

