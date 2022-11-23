package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger

data class WalletState(val addresses: Map<String, List<UnspentItem>>, val parentTxs: Map<ByteVector32, Transaction>) {
    /** Electrum sends parent txs separately from utxo outpoints, this boolean indicates when the wallet is consistent*/
    val consistent: Boolean = addresses.flatMap { it.value }.all { parentTxs.containsKey(it.txid) }
    val utxos: List<Utxo> = addresses
        .flatMap { it.value }
        .filter { parentTxs.containsKey(it.txid) }
        .map { Utxo(parentTxs[it.txid]!!, it.outputIndex, it.blockHeight) }

    val confirmedUtxos: List<Utxo> = utxos.filter { it.blockHeight > 0L }
    val unconfirmedUtxos: List<Utxo> = utxos.filter { it.blockHeight == 0L }

    val confirmedBalance = confirmedUtxos.map { it.amount }.sum()
    val unconfirmedBalance = unconfirmedUtxos.map { it.amount }.sum()
    val totalBalance = confirmedBalance + unconfirmedBalance

    fun minus(reserved: Set<Utxo>): WalletState {
        val reservedIds = reserved.map {
            UnspentItemId(it.previousTx.txid, it.outputIndex)
        }.toSet()
        return copy(addresses = addresses.mapValues {
            it.value.filter { item ->
                !reservedIds.contains(UnspentItemId(item.txid, item.outputIndex))
            }
        })
    }

    data class Utxo(val previousTx: Transaction, val outputIndex: Int, val blockHeight: Long) {
        val outPoint = OutPoint(previousTx, outputIndex.toLong())
        val amount = previousTx.txOut[outputIndex].amount
    }

    data class UnspentItemId(val txid: ByteVector32, val outputIndex: Int)

    companion object {
        val empty: WalletState = WalletState(emptyMap(), emptyMap())

        /** Sign the given input if we have the corresponding private key (only works for P2WPKH scripts). */
        fun signInput(keyManager: KeyManager, tx: Transaction, index: Int, parentTxOut: TxOut?): Pair<Transaction, ScriptWitness?> {
            val witness = parentTxOut
                ?.let { script2PrivateKey(keyManager, it.publicKeyScript) }
                ?.let { privateKey ->
                    // mind this: the pubkey script used for signing is not the prevout pubscript (which is just a push
                    // of the pubkey hash), but the actual script that is evaluated by the script engine, in this case a PAY2PKH script
                    val publicKey = privateKey.publicKey()
                    val pubKeyScript = Script.pay2pkh(publicKey)
                    val sig = Transaction.signInput(
                        tx,
                        index,
                        pubKeyScript,
                        SigHash.SIGHASH_ALL,
                        parentTxOut.amount,
                        SigVersion.SIGVERSION_WITNESS_V0,
                        privateKey
                    )
                    Script.witnessPay2wpkh(publicKey, sig.byteVector())
                }
            return when (witness) {
                is ScriptWitness -> Pair(tx.updateWitness(index, witness), witness)
                else -> Pair(tx, null)
            }
        }

        /** Find the private key corresponding to this script, assuming this is a p2wpkh owned by us. */
        private fun script2PrivateKey(keyManager: KeyManager, publicKeyScript: ByteVector): PrivateKey? {
            val priv = keyManager.bip84PrivateKey(account = 1, addressIndex = 0)
            val script = Script.write(Script.pay2wpkh(priv.publicKey())).toByteVector()
            return if (script == publicKeyScript) priv else null
        }
    }
}

private sealed interface WalletCommand {
    companion object {
        object ElectrumConnected : WalletCommand
        data class ElectrumNotification(val msg: ElectrumResponse) : WalletCommand
        data class AddAddress(val bitcoinAddress: String) : WalletCommand
    }
}

class ElectrumMiniWallet(val chainHash: ByteVector32, private val client: ElectrumClient, private val scope: CoroutineScope, loggerFactory: LoggerFactory, private val name: String = "") : CoroutineScope by scope {

    private val logger = loggerFactory.newLogger(this::class)
    fun Logger.mdcinfo(msgCreator: () -> String) {
        log(
            level = Logger.Level.INFO, meta = mapOf("wallet" to name, "state" to walletStateFlow.value), msgCreator = msgCreator
        )
    }

    // state flow with the current balance
    private val _walletStateFlow = MutableStateFlow(WalletState(emptyMap(), emptyMap()))
    val walletStateFlow get() = _walletStateFlow.asStateFlow()

    // all currently watched script hashes and their corresponding bitcoin address
    private var scriptHashes: Map<ByteVector32, String> = emptyMap()

    // the mailbox of this "actor"
    private val mailbox: Channel<WalletCommand> = Channel(Channel.BUFFERED)

    fun addAddress(bitcoinAddress: String) {
        launch {
            mailbox.send(WalletCommand.Companion.AddAddress(bitcoinAddress))
        }
    }
    init {
        suspend fun WalletState.processSubscriptionResponse(msg: ScriptHashSubscriptionResponse): WalletState {
            val bitcoinAddress = scriptHashes[msg.scriptHash]
            return when {
                bitcoinAddress == null || msg.status.isEmpty() -> this
                else -> {
                    val unspents = client.getScriptHashUnspents(msg.scriptHash)
                    val newUtxos = unspents.minus((_walletStateFlow.value.addresses[bitcoinAddress] ?: emptyList()).toSet())
                    // request new parent txs
                    val parentTxs = newUtxos.map { utxo ->
                        val tx = client.getTx(utxo.txid)
                        logger.mdcinfo { "received parent transaction with txid=${tx.txid}" }
                        tx
                    }
                    val nextWalletState = this.copy(addresses = this.addresses + (bitcoinAddress to unspents), parentTxs = this.parentTxs + parentTxs.associateBy { it.txid })
                    logger.mdcinfo { "${unspents.size} utxo(s) for address=$bitcoinAddress balance=${nextWalletState.totalBalance}" }
                    unspents.forEach { logger.debug { "utxo=${it.outPoint.txid}:${it.outPoint.index} amount=${it.value} sat" } }
                    nextWalletState
                }
            }
        }

        suspend fun subscribe(bitcoinAddress: String): Triple<ByteVector32, String, ScriptHashSubscriptionResponse> {
            val pubkeyScript = ByteVector(Script.write(Bitcoin.addressToPublicKeyScript(chainHash, bitcoinAddress)))
            val scriptHash = ElectrumClient.computeScriptHash(pubkeyScript)
            logger.info { "subscribing to address=$bitcoinAddress pubkeyScript=$pubkeyScript scriptHash=$scriptHash" }
            val response = client.startScriptHashSubscription(scriptHash)
            return Triple(scriptHash, bitcoinAddress, response)
        }

        launch {
            // listen to connection events
            client.connectionStatus.filterIsInstance<Connection.ESTABLISHED>().collect { mailbox.send(WalletCommand.Companion.ElectrumConnected) }
        }
        launch {
            // listen to subscriptions events
            client.subscriptions.collect { mailbox.send(WalletCommand.Companion.ElectrumNotification(it)) }
        }
        launch {
            mailbox.consumeAsFlow().collect {
                when (it) {
                    is WalletCommand.Companion.ElectrumConnected -> {
                        logger.mdcinfo { "electrum connected" }
                        scriptHashes.values.forEach { scriptHash ->
                            val (_, _, response) = subscribe(scriptHash)
                            _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(response)
                        }
                    }

                    is WalletCommand.Companion.ElectrumNotification -> {
                        if (it.msg is ScriptHashSubscriptionResponse) {
                            _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(it.msg)
                        }
                    }

                    is WalletCommand.Companion.AddAddress -> {
                        logger.mdcinfo { "adding new address=${it.bitcoinAddress}" }
                        val (scriptHash, address, response) = subscribe(it.bitcoinAddress)
                        scriptHashes = scriptHashes + (scriptHash to address)
                        _walletStateFlow.value = _walletStateFlow.value.processSubscriptionResponse(response)
                    }
                }
            }
        }
    }
}