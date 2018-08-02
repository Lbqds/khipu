package khipu.ledger

import khipu.Deleted
import khipu.Log
import khipu.Original
import khipu.Updated
import khipu.domain.Account
import khipu.domain.Address
import khipu.trie.MerklePatriciaTrie

/**
 * '''Immutable''' tried based accounts: address -> account
 *
 * Before commit(), all kv is kept in logs. commit() will write kv actually to
 * trie's node tree.  And underlyingTire.persist() will write trie nodes to
 * persistence.
 *
 */
object TrieAccounts {
  val DeletedValue = Deleted(null)

  def apply(underlyingTrie: MerklePatriciaTrie[Address, Account]) =
    new TrieAccounts(underlyingTrie, Map())
}
final class TrieAccounts private (
    underlyingTrie:           MerklePatriciaTrie[Address, Account],
    private[ledger] var logs: Map[Address, Log[Account]]
) {
  import TrieAccounts._

  def underlying = underlyingTrie

  def +(kv: (Address, Account)) = put(kv._1, kv._2)
  def -(address: Address) = remove(address)

  def get(address: Address): Option[Account] = {
    logs.get(address) match {
      case None => underlyingTrie.get(address) map { account =>
        logs += (address -> Original(account))
        account
      }
      case Some(Original(account)) => Some(account)
      case Some(Updated(account))  => Some(account)
      case Some(Deleted(account))  => None
    }
  }

  def put(address: Address, account: Account): TrieAccounts = {
    val updatedLogs = logs + (address -> Updated(account))
    new TrieAccounts(underlyingTrie, updatedLogs)
  }

  def remove(address: Address): TrieAccounts = {
    val updatedLogs = logs + (address -> DeletedValue)
    new TrieAccounts(underlyingTrie, updatedLogs)
  }

  def commit(): TrieAccounts = {
    val committedTrie = this.logs.foldLeft(this.underlyingTrie) {
      case (accTrie, (k, Deleted(_)))  => accTrie - k
      case (accTrie, (k, Updated(v)))  => accTrie + (k -> v)
      case (accTrie, (k, Original(v))) => accTrie
    }
    new TrieAccounts(committedTrie, Map())
  }
}
