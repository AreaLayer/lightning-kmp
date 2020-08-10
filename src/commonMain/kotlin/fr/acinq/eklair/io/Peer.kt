package fr.acinq.eklair.io

import fr.acinq.bitcoin.*
import fr.acinq.eklair.*
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEvent
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.channel.*
import fr.acinq.eklair.crypto.noise.*
import fr.acinq.eklair.payment.OutgoingPacket
import fr.acinq.eklair.payment.PaymentRequest
import fr.acinq.eklair.router.ChannelHop
import fr.acinq.eklair.utils.*
import fr.acinq.eklair.wire.*
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory


sealed class PeerEvent
data class BytesReceived(val data: ByteArray) : PeerEvent()
data class WatchReceived(val watch: WatchEvent) : PeerEvent()
data class ReceivePayment(val paymentPreimage: ByteVector32, val amount: MilliSatoshi, val expiry: CltvExpiry) : PeerEvent() {
    val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
}

data class SendPayment(val paymentRequest: PaymentRequest) : PeerEvent()
data class WrappedChannelEvent(val channelId: ByteVector32, val channelEvent: ChannelEvent) : PeerEvent()

sealed class PeerListenerEvent
data class PaymentRequestGenerated(val receivePayment: ReceivePayment, val request: String) : PeerListenerEvent()
data class PaymentReceived(val receivePayment: ReceivePayment) : PeerListenerEvent()

@OptIn(ExperimentalStdlibApi::class, ExperimentalCoroutinesApi::class)
class Peer(
    val socketBuilder: TcpSocket.Builder,
    val nodeParams: NodeParams,
    val remoteNodeId: PublicKey,
) {
    companion object {
        private val prefix: Byte = 0x00
        private val prologue = "lightning".encodeToByteArray()
    }

    private val input = Channel<PeerEvent>(10)
    private val output = Channel<ByteArray>(3)

    private val logger = LoggerFactory.default.newLogger(Logger.Tag(Peer::class))

    private val channelsChannel = ConflatedBroadcastChannel<Map<ByteVector32, ChannelState>>(HashMap())
    private val connectedChannel = ConflatedBroadcastChannel(false)
    private val listenerEventChannel = BroadcastChannel<PeerListenerEvent>(Channel.BUFFERED)

    // channels map, indexed by channel id
    // note that a channel starts with a temporary id then switchs to its final id once the funding tx is known
    private var channels by channelsChannel
    private var connected by connectedChannel

    // pending incoming payments, indexed by payment hash
    private val pendingIncomingPayments: HashMap<ByteVector32, ReceivePayment> = HashMap()

    private var theirInit: Init? = null

    suspend fun connect(address: String, port: Int) {
        logger.info { "connecting to {$remoteNodeId}@{$address}" }
        val socket = socketBuilder.connect(address, port, tls = false)
        val priv = nodeParams.keyManager.nodeKey.privateKey
        val pub = priv.publicKey()
        val keyPair = Pair(pub.value.toByteArray(), priv.value.toByteArray())
        val (enc, dec, ck) = handshake(
            keyPair,
            remoteNodeId.value.toByteArray(),
            { s -> socket.receiveFully(s) },
            { b -> socket.send(b) })
        val session = LightningSession(enc, dec, ck)

        suspend fun receive(): ByteArray {
            return session.receive { size -> val buffer = ByteArray(size); socket.receiveFully(buffer); buffer }
        }

        suspend fun send(message: ByteArray) {
            session.send(message) { data, flush -> socket.send(data, flush) }
        }

        val init = Hex.decode("001000000002a8a0")
        send(init)

        suspend fun doPing() {
            val ping = Hex.decode("0012000a0004deadbeef")
            while (true) {
                delay(30000)
                send(ping)
            }
        }

        suspend fun listen() {
            try {
                while (true) {
                    val received = receive()
                    input.send(BytesReceived(received))
                }
            } finally {
                connected = false
            }
        }

        suspend fun respond() {
            for (msg in output) {
                send(msg)
            }
        }

        coroutineScope {
            launch { run() }
            launch { doPing() }
            launch { respond() }
            launch { listen() }
        }

        delay(1000 * 1000) // TODO: WTF ?
    }

    suspend fun send(event: PeerEvent) {
        input.send(event)
    }

    fun openChannelsSubscription() = channelsChannel.openSubscription()
    fun openConnectedSubscription() = connectedChannel.openSubscription()
    fun openListenerEventSubscription() = listenerEventChannel.openSubscription()

    private suspend fun send(actions: List<ChannelAction>) {
        actions.forEach {
            when (it) {
                is SendMessage -> {
                    val encoded = LightningMessage.encode(it.message)
                    encoded?.let { bin ->
                        logger.info { "sending ${it.message}" }
                        output.send(bin)
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun handshake(
        ourKeys: Pair<ByteArray, ByteArray>,
        theirPubkey: ByteArray,
        r: suspend (Int) -> ByteArray,
        w: suspend (ByteArray) -> Unit
    ): Triple<CipherState, CipherState, ByteArray> {

        /**
         * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
         *
         * @param reader handshake state reader
         * @return the size of the message the reader is expecting
         */
        fun expectedLength(reader: HandshakeStateReader): Int = when (reader.messages.size) {
            3, 2 -> 50
            1 -> 66
            else -> throw RuntimeException("invalid state")
        }

        val writer = makeWriter(ourKeys, theirPubkey)
        val (state1, message, _) = writer.write(ByteArray(0))
        w(byteArrayOf(prefix) + message)

        val payload = r(expectedLength(state1))
        require(payload[0] == prefix)

        val (writer1, _, _) = state1.read(payload.drop(1).toByteArray())
        val (_, message1, foo) = writer1.write(ByteArray(0))
        val (enc, dec, ck) = foo!!
        w(byteArrayOf(prefix) + message1)
        return Triple(enc, dec, ck)
    }

    private fun makeWriter(localStatic: Pair<ByteArray, ByteArray>, remoteStatic: ByteArray) = HandshakeState.initializeWriter(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), remoteStatic, ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    private fun makeReader(localStatic: Pair<ByteArray, ByteArray>) = HandshakeState.initializeReader(
        handshakePatternXK, prologue,
        localStatic, Pair(ByteArray(0), ByteArray(0)), ByteArray(0), ByteArray(0),
        Secp256k1DHFunctions, Chacha20Poly1305CipherFunctions, SHA256HashFunctions
    )

    private suspend fun run() {
        logger.info { "peer is active" }
        for (event in input) {
            when {
                event is BytesReceived -> {
                    val msg = LightningMessage.decode(event.data)
                    when {
                        msg is Init -> {
                            logger.info { "received $msg" }
                            theirInit = msg
                            connected = true
                        }
                        msg is Ping -> {
                            logger.info { "received $msg" }
                            val pong = Pong(ByteVector(ByteArray(msg.pongLength)))
                            output.send(LightningMessage.encode(pong)!!)

                        }
                        msg is Pong -> {
                            logger.info { "received $msg" }
                        }
                        msg is Error && msg.channelId == ByteVector32.Zeroes -> {
                            logger.error { "connection error, failing all channels: ${msg.toAscii()}" }
                        }
                        msg is OpenChannel -> {
                            val localParams = LocalParams(
                                nodeParams.nodeId,
                                KeyPath("/1/2/3"),
                                nodeParams.dustLimit,
                                nodeParams.maxHtlcValueInFlightMsat,
                                Satoshi(600),
                                nodeParams.htlcMinimum,
                                nodeParams.toRemoteDelayBlocks,
                                nodeParams.maxAcceptedHtlcs,
                                false,
                                ByteVector.empty,
                                PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey(),
                                Features(
                                    setOf(
                                        ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Mandatory),
                                        ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                                        ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional),
                                    )
                                )

                            )
                            val state = WaitForInit(
                                StaticParams(nodeParams, remoteNodeId),
                                Pair(0, Block.RegtestGenesisBlock.header)
                            )
                            val (state1, actions1) = state.process(
                                InitFundee(
                                    msg.temporaryChannelId,
                                    localParams,
                                    theirInit!!
                                )
                            )
                            val (state2, actions2) = state1.process(MessageReceived(msg))
                            send(actions1 + actions2)
                            channels = channels + (msg.temporaryChannelId to state2)
                            logger.info { "new state for ${msg.temporaryChannelId}: $state2" }
                        }
                        msg is HasTemporaryChannelId && !channels.containsKey(msg.temporaryChannelId) -> {
                            logger.error { "received $msg for unknown temporary channel ${msg.temporaryChannelId}" }
                        }
                        msg is HasTemporaryChannelId -> {
                            logger.info { "received $msg for temporary channel ${msg.temporaryChannelId}" }
                            val state = channels[msg.temporaryChannelId]!!
                            val (state1, actions) = state.process(MessageReceived(msg))
                            channels = channels + (msg.temporaryChannelId to state1)
                            logger.info { "channel ${msg.temporaryChannelId} new state $state1" }
                            send(actions)
                            actions.forEach {
                                when (it) {
                                    is ChannelIdSwitch -> {
                                        logger.info { "id switch from ${it.oldChannelId} to ${it.newChannelId}" }
                                        channels = channels - it.oldChannelId + (it.newChannelId to state1)
                                    }
                                    is SendWatch -> {
                                        if (it.watch is WatchConfirmed) {
                                            // TODO: use a real watcher, here we just blindly confirm whatever tx they sent us
                                            val tx = Transaction(
                                                version = 2,
                                                txIn = listOf(),
                                                txOut = listOf(TxOut(Satoshi(100), (it.watch).publicKeyScript)),
                                                lockTime = 0L
                                            )
                                            input.send(
                                                WatchReceived(
                                                    WatchEventConfirmed(
                                                        it.watch.channelId,
                                                        it.watch.event,
                                                        100,
                                                        0,
                                                        tx
                                                    )
                                                )
                                            )
                                        }
                                    }
                                    else -> logger.warning { "ignoring $it" }
                                }
                            }
                        }
                        msg is HasChannelId && !channels.containsKey(msg.channelId) -> {
                            logger.error { "received $msg for unknown channel ${msg.channelId}" }
                        }
                        msg is HasChannelId -> {
                            logger.info { "received $msg for channel ${msg.channelId}" }
                            val state = channels[msg.channelId]!!
                            val (state1, actions) = state.process(MessageReceived(msg))
                            channels = channels + (msg.channelId to state1)
                            logger.info { "channel ${msg.channelId} new state $state1" }
                            send(actions)
                            actions.forEach {
                                when {
                                    it is ProcessAdd && !pendingIncomingPayments.containsKey(it.add.paymentHash) -> {
                                        logger.warning { "received ${it.add} } for which we don't have a preimage" }
                                    }
                                    it is ProcessAdd -> {
                                        val payment = pendingIncomingPayments[it.add.paymentHash]!!
                                        // TODO: check that we've been paid what we asked for
                                        logger.info { "received ${it.add} for $payment" }
                                        input.send(
                                            WrappedChannelEvent(
                                                msg.channelId,
                                                ExecuteCommand(CMD_FULFILL_HTLC(it.add.id, payment.paymentPreimage, commit = true))
                                            )
                                        )
                                        listenerEventChannel.send(PaymentReceived(payment))
                                    }
                                    it is ProcessCommand -> input.send(
                                        WrappedChannelEvent(
                                            msg.channelId,
                                            ExecuteCommand(it.command)
                                        )
                                    )
                                    it !is SendMessage -> {
                                        logger.warning { "ignoring $it" }
                                    }
                                }
                            }
                        }
                        else -> logger.warning { "received unhandled message ${Hex.encode(event.data)}" }
                    }
                } // event is ByteReceived
                event is WatchReceived && !channels.containsKey(event.watch.channelId) -> {
                    logger.error { "received watch event ${event.watch} for unknown channel ${event.watch.channelId}}" }
                }
                event is WatchReceived -> {
                    val state = channels[event.watch.channelId]!!
                    val (state1, actions) = state.process(fr.acinq.eklair.channel.WatchReceived(event.watch))
                    send(actions)
                    channels = channels + (event.watch.channelId to state1)
                    logger.info { "channel ${event.watch.channelId} new state $state1" }
                } // event is WatchReceived
                //
                // receive payments
                //
                event is ReceivePayment -> {
                    logger.info { "expecting to receive $event for payment hash ${event.paymentHash}" }
                    val pr = PaymentRequest(
                        "lnbcrt", event.amount, currentTimestampSeconds(), nodeParams.nodeId,
                        listOf(
                            PaymentRequest.Companion.TaggedField.PaymentHash(event.paymentHash),
                            PaymentRequest.Companion.TaggedField.Description("this is a kotlin test")
                        ),
                        ByteVector.empty
                    ).sign(nodeParams.privateKey)
                    logger.info { "payment request ${pr.write()}" }
                    pendingIncomingPayments[event.paymentHash] = event
                    listenerEventChannel.send(PaymentRequestGenerated(event, pr.write()))
                }
                //
                // send payments
                //
                event is SendPayment && channels.isEmpty() -> {
                    logger.error { "no channels to send payments with" }
                }
                event is SendPayment && event.paymentRequest.amount == null -> {
                    // TODO: support amount-less payment requests
                    logger.error { "payment request does not include an amount" }
                }
                event is SendPayment -> {
                    logger.info { "sending ${event.paymentRequest.amount} to ${event.paymentRequest.nodeId}" }

                    // find a channel with enough outgoing capacity
                    channels.values.forEach {
                        when (it) {
                            is Normal -> logger.info { "channel ${it.channelId} available for send ${it.commitments.availableBalanceForSend()}" }
                        }
                    }
                    val channel = channels.values.find { it is Normal && it.commitments.availableBalanceForSend() >= event.paymentRequest.amount!! } as Normal?
                    if (channel == null) logger.error { "cannot find channel with enough capacity" } else {
                        val normal = channel!!
                        val paymentId = UUID.randomUUID()
                        val expiryDelta = CltvExpiryDelta(18) // TODO: read value from payment request
                        val expiry = expiryDelta.toCltvExpiry(normal.currentBlockHeight.toLong())
                        val isDirectPayment = event.paymentRequest.nodeId == remoteNodeId
                        val finalPayload = when(isDirectPayment) {
                            true -> Onion.createSinglePartPayload(event.paymentRequest.amount!!, expiry)
                            false -> TODO("implement trampoline payment")
                        }
                        // one hop: this a direct payment to our peer
                        val hops = listOf(ChannelHop(nodeParams.nodeId, remoteNodeId, normal.channelUpdate))
                        val (cmd, sharedSecrets) = OutgoingPacket.buildCommand(Upstream.Local(paymentId), event.paymentRequest.paymentHash!!, hops, finalPayload)
                        val (state1, actions) = normal.process(ExecuteCommand(cmd))
                        channels = channels + (normal.channelId to state1)
                        send(actions)
                        actions.forEach {
                            when (it) {
                                is ProcessCommand -> input.send(WrappedChannelEvent(normal.channelId, ExecuteCommand(it.command)))
                            }
                        }
                        logger.info { "channel ${normal.channelId} new state $state1" }
                    }
                }
                event is WrappedChannelEvent && event.channelId == ByteVector32.Zeroes -> {
                    // this is for all channels
                    channels.forEach {
                        val (state1, actions) = it.value.process(event.channelEvent)
                        send(actions)
                        actions.forEach { action ->
                            when (action) {
                                is ProcessCommand -> input.send(WrappedChannelEvent(it.key, ExecuteCommand(action.command)))
                            }
                        }
                        channels = channels + (it.key to state1)
                    }
                }
                event is WrappedChannelEvent && !channels.containsKey(event.channelId) -> {
                    logger.error { "received ${event.channelEvent} for a unknown channel ${event.channelId}" }
                }
                event is WrappedChannelEvent -> {
                    val state = channels[event.channelId]!!
                    val (state1, actions) = state.process(event.channelEvent)
                    channels = channels + (event.channelId to state1)
                    send(actions)
                    actions.forEach {
                        when (it) {
                            is ProcessCommand -> input.send(WrappedChannelEvent(event.channelId, ExecuteCommand(it.command)))
                            else -> {
                            }
                        }
                    }
                }
            }
        }
    }
}