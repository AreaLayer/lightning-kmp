package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.*
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.linesFlow
import fr.acinq.lightning.io.send
import fr.acinq.lightning.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.math.min


sealed interface ElectrumConnectionStatus {
    data class Closed(val reason: TcpSocket.IOException?) : ElectrumConnectionStatus
    object Connecting : ElectrumConnectionStatus
    data class Connected(val version: ServerVersionResponse, val height: Int, val header: BlockHeader) : ElectrumConnectionStatus
}

class ElectrumClient(
    socketBuilder: TcpSocket.Builder?,
    scope: CoroutineScope,
    private val loggerFactory: LoggerFactory
) : CoroutineScope by scope {
    private val logger = newLogger(loggerFactory)

    var socketBuilder: TcpSocket.Builder? = socketBuilder
        set(value) {
            logger.debug { "swap socket builder=$value" }
            field = value
        }

    private val json = Json { ignoreUnknownKeys = true }

    // electrum subscriptions
    private val _subscriptions = MutableSharedFlow<ElectrumResponse>()
    val subscriptions: SharedFlow<ElectrumResponse> get() = _subscriptions.asSharedFlow()

    // connection status
    private val _connectionState = MutableStateFlow<ElectrumConnectionStatus>(ElectrumConnectionStatus.Closed(null))
    val connectionStatus: StateFlow<ElectrumConnectionStatus> get() = _connectionState.asStateFlow()

    private val _connectionStateLegacy = MutableStateFlow<Connection>(Connection.CLOSED(null))
    val connectionState: StateFlow<Connection> get() = _connectionStateLegacy.asStateFlow()


    // internal mailbox
    private sealed interface Action {
        data class SendToServer(val request: Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>) : Action
        data class ProcessServerResponse(val response: Either<ElectrumResponse, JsonRPCResponse>) : Action
    }

    private var mailbox = Channel<Action>()

    // initial reconnection delay
    private var reconnectionDelay = 5000L


    private suspend fun sendRequest(socket: TcpSocket, request: ElectrumRequest, requestId: Int) {
        val bytes = request.asJsonRPCRequest(requestId).encodeToByteArray()
        try {
            socket.send(bytes, flush = true)
        } catch (ex: TcpSocket.IOException) {
            logger.warning(ex) { "cannot send to electrum server" }
            _connectionState.value = ElectrumConnectionStatus.Closed(ex)
            _connectionStateLegacy.value = Connection.CLOSED(ex)
            socket.close()
        }
    }

    // connect to an electrum server, exchange version messages (this is the electrumx "handshake") and get the server's tip
    private suspend fun connect(host: String, port: Int, tls: TcpSocket.TLS): Pair<TcpSocket, Flow<Either<ElectrumResponse, JsonRPCResponse>>> {
        logger.info { "connecting to $host:$port" }
        _connectionState.value = ElectrumConnectionStatus.Connecting
        _connectionStateLegacy.value = Connection.ESTABLISHING
        val socket = socketBuilder?.connect(host, port, tls, loggerFactory) ?: error("socket builder is null.")
        val flow = socket.linesFlow().map { json.decodeFromString(ElectrumResponseDeserializer, it) }
        val version = ServerVersion()
        sendRequest(socket, version, 0)
        val rpcFlow = flow.filterIsInstance<Either.Right<Nothing, JsonRPCResponse>>().map { it.value }
        val theirVersion = parseJsonResponse(version, rpcFlow.first())
        require(theirVersion is ServerVersionResponse) { "invalid server version response $theirVersion" }
        logger.info { "server version $theirVersion" }
        sendRequest(socket, HeaderSubscription, 0)
        val header = parseJsonResponse(HeaderSubscription, rpcFlow.first())
        require(header is HeaderSubscriptionResponse) { "invalid header subscription response $header" }
        logger.info { "server tip $header" }
        _subscriptions.emit(header)
        _connectionState.value = ElectrumConnectionStatus.Connected(theirVersion, header.blockHeight, header.header)
        _connectionStateLegacy.value = Connection.ESTABLISHED
        reconnectionDelay = 5000L
        return Pair(socket, flow)
    }

    // connect to an electrum server and launch processing jobs.
    // this method will terminate if it cannot connect or gets disconnected
    private suspend fun run(host: String, port: Int, tls: TcpSocket.TLS) {
        val (socket, flow) = connect(host, port, tls)
        val listenJob = launch {
            try {
                flow.collect { response -> mailbox.send(Action.ProcessServerResponse(response)) }
            } catch (t: Throwable) {
                if (isActive) logger.error(t) { "error in listening job" }
                _connectionState.value = ElectrumConnectionStatus.Closed(null)
                _connectionStateLegacy.value = Connection.CLOSED(null)
                socket.close()
            }
        }

        // pending requests map
        val requestMap = mutableMapOf<Int, Pair<ElectrumRequest, CompletableDeferred<ElectrumResponse>>>()
        var requestId = 0

        val processJob = launch {
            for (msg in mailbox) {
                when {
                    msg is Action.SendToServer -> {
                        requestMap[requestId] = msg.request
                        sendRequest(socket, msg.request.first, requestId++)
                    }

                    msg is Action.ProcessServerResponse && msg.response is Either.Left -> {
                        _subscriptions.emit(msg.response.value)
                    }

                    msg is Action.ProcessServerResponse && msg.response is Either.Right -> {
                        msg.response.value.id?.let { id ->
                            requestMap.remove(id)?.let { (request, replyTo) ->
                                replyTo.complete(parseJsonResponse(request, msg.response.value))
                            }
                        }
                    }
                }
            }
        }
        val pingJob = launch {
            while (isActive) {
                delay(10000)
                runCatching {
                    val pong = rpcCall<PingResponse>(Ping)
                    logger.debug { "received ping response $pong" }
                }
            }
        }
        logger.info { "running" }
        listenJob.join()
        mailbox.close()
        pingJob.cancelAndJoin()
        processJob.cancelAndJoin()

        // fail all pending requests
        requestMap.values.forEach { it.second.completeExceptionally(CancellationException("electrum client disconnected")) }

        // reset mailbox
        mailbox = Channel(Channel.BUFFERED)
        logger.info { "loop terminating" }
    }

    // connect to an electrum server, and re-connect whenever the connection is lost
    suspend fun start(serverAddress: ServerAddress) = launch {
        val (host, port, tls) = serverAddress
        while (isActive) {
            val job = launch {
                try {
                    run(host, port, tls)
                } catch (t: Throwable) {
                    if (t !is CancellationException) logger.error(t) { "connection failed" }
                }
            }
            job.join()
            logger.info { "restarting" }
            delay(reconnectionDelay)
            reconnectionDelay = min(2 * reconnectionDelay, 60000L)
        }
    }

    fun stop() = this.cancel(null)

    suspend fun send(request: ElectrumRequest, replyTo: CompletableDeferred<ElectrumResponse>) {
        mailbox.send(Action.SendToServer(Pair(request, replyTo)))
    }

    suspend inline fun <reified T : ElectrumResponse> rpcCall(request: ElectrumRequest): T {
        val replyTo = CompletableDeferred<ElectrumResponse>()
        send(request, replyTo)
        return replyTo.await() as T
    }

    suspend fun getTx(txid: ByteVector32): Transaction = rpcCall<GetTransactionResponse>(GetTransaction(txid)).tx

    suspend fun getMerkle(txid: ByteVector32, blockHeight: Int, contextOpt: Transaction? = null) = rpcCall<GetMerkleResponse>(GetMerkle(txid, blockHeight, contextOpt))

    suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = rpcCall<GetScriptHashHistoryResponse>(GetScriptHashHistory(scriptHash)).history

    suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = rpcCall<ScriptHashListUnspentResponse>(ScriptHashListUnspent(scriptHash)).unspents

    suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse = rpcCall(ScriptHashSubscription(scriptHash))

    suspend fun startHeaderSubscription(): HeaderSubscriptionResponse = rpcCall(HeaderSubscription)

    suspend fun broadcastTransaction(tx: Transaction): BroadcastTransactionResponse = rpcCall(BroadcastTransaction(tx))

    suspend fun estimateFees(confirmations: Int): EstimateFeeResponse = rpcCall(EstimateFees(confirmations))

    companion object {
        const val ELECTRUM_CLIENT_NAME = "3.3.6"
        const val ELECTRUM_PROTOCOL_VERSION = "1.4"
        val version = ServerVersion()
        internal fun computeScriptHash(publicKeyScript: ByteVector): ByteVector32 = Crypto.sha256(publicKeyScript).toByteVector32().reversed()
    }
}