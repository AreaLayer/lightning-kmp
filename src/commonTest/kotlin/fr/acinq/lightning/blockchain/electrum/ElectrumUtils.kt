package fr.acinq.lightning.blockchain.electrum

import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.ServerAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.kodein.log.LoggerFactory

suspend fun connectToElectrumServer(scope: CoroutineScope, addr: ServerAddress): ElectrumClient {
    val client = ElectrumClient(TcpSocket.Builder(), scope, LoggerFactory.default)
    client.start(addr)
    client.connectionStatus.first { it is ElectrumConnectionStatus.Closed }
    client.connectionStatus.first { it is ElectrumConnectionStatus.Connecting }
    client.connectionStatus.first { it is ElectrumConnectionStatus.Connected }

    return client
}

suspend fun CoroutineScope.connectToTestnetServer(): ElectrumClient =
    connectToElectrumServer(this, ServerAddress("testnet1.electrum.acinq.co", 51002, TcpSocket.TLS.UNSAFE_CERTIFICATES))

suspend fun CoroutineScope.connectToMainnetServer(): ElectrumClient =
    connectToElectrumServer(this, ServerAddress("electrum.acinq.co", 50002, TcpSocket.TLS.UNSAFE_CERTIFICATES))
