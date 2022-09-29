package fr.acinq.lightning.serialization.v1

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.mapSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
internal sealed class DirectedHtlc {
    abstract val add: UpdateAddHtlc

    fun to(): fr.acinq.lightning.transactions.DirectedHtlc = when (this) {
        is IncomingHtlc -> fr.acinq.lightning.transactions.IncomingHtlc(this.add)
        is OutgoingHtlc -> fr.acinq.lightning.transactions.OutgoingHtlc(this.add)
    }

    companion object {
        fun from(input: fr.acinq.lightning.transactions.DirectedHtlc): DirectedHtlc = when (input) {
            is fr.acinq.lightning.transactions.IncomingHtlc -> IncomingHtlc(input.add)
            is fr.acinq.lightning.transactions.OutgoingHtlc -> OutgoingHtlc(input.add)
        }
    }
}

@Serializable
internal data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
internal data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

@Serializable
internal data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feerate: FeeratePerKw,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {
    fun export() = fr.acinq.lightning.transactions.CommitmentSpec(htlcs.map { it.to() }.toSet(), feerate, toLocal, toRemote)
}

@Serializable
internal data class LocalChanges(val proposed: List<UpdateMessage>, val signed: List<UpdateMessage>, val acked: List<UpdateMessage>) {
    fun export() = fr.acinq.lightning.channel.LocalChanges(proposed, signed, acked)
}

@Serializable
internal data class RemoteChanges(val proposed: List<UpdateMessage>, val acked: List<UpdateMessage>, val signed: List<UpdateMessage>) {
    fun export() = fr.acinq.lightning.channel.RemoteChanges(proposed, signed, acked)
}

@Serializable
internal data class HtlcTxAndSigs(
    val txinfo: Transactions.TransactionWithInputInfo.HtlcTx,
    @Serializable(with = ByteVector64KSerializer::class) val localSig: ByteVector64,
    @Serializable(with = ByteVector64KSerializer::class) val remoteSig: ByteVector64
) {
    fun export() = fr.acinq.lightning.channel.HtlcTxAndSigs(txinfo, localSig, remoteSig)
}

@Serializable
internal data class PublishableTxs(val commitTx: Transactions.TransactionWithInputInfo.CommitTx, val htlcTxsAndSigs: List<HtlcTxAndSigs>) {
    fun export() = fr.acinq.lightning.channel.PublishableTxs(commitTx, htlcTxsAndSigs.map { it.export() })
}

@Serializable
internal data class LocalCommit(val index: Long, val spec: CommitmentSpec, val publishableTxs: PublishableTxs) {
    fun export() = fr.acinq.lightning.channel.LocalCommit(index, spec.export(), publishableTxs.export())
}

@Serializable
internal data class RemoteCommit(val index: Long, val spec: CommitmentSpec, @Serializable(with = ByteVector32KSerializer::class) val txid: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remotePerCommitmentPoint: PublicKey) {
    fun export() = fr.acinq.lightning.channel.RemoteCommit(index, spec.export(), txid, remotePerCommitmentPoint)
}

@Serializable
internal data class WaitingForRevocation(val nextRemoteCommit: RemoteCommit, val sent: CommitSig, val sentAfterLocalCommitIndex: Long, val reSignAsap: Boolean = false) {
    fun export() = fr.acinq.lightning.channel.WaitingForRevocation(nextRemoteCommit.export(), sent, sentAfterLocalCommitIndex, reSignAsap)
}

@Serializable
internal data class LocalCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainDelayedOutputTx: Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx? = null,
    val htlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.HtlcTx?> = emptyMap(),
    val claimHtlcDelayedTxs: List<Transactions.TransactionWithInputInfo.ClaimLocalDelayedOutputTx> = emptyList(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    fun export() = fr.acinq.lightning.channel.LocalCommitPublished(commitTx, claimMainDelayedOutputTx, htlcTxs, claimHtlcDelayedTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
internal data class RemoteCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val claimHtlcTxs: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, Transactions.TransactionWithInputInfo.ClaimHtlcTx?> = emptyMap(),
    val claimAnchorTxs: List<Transactions.TransactionWithInputInfo.ClaimAnchorOutputTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    fun export() = fr.acinq.lightning.channel.RemoteCommitPublished(commitTx, claimMainOutputTx, claimHtlcTxs, claimAnchorTxs, irrevocablySpent)
}

@Serializable
internal data class RevokedCommitPublished(
    @Serializable(with = TransactionKSerializer::class) val commitTx: Transaction,
    @Serializable(with = PrivateKeyKSerializer::class) val remotePerCommitmentSecret: PrivateKey,
    val claimMainOutputTx: Transactions.TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx? = null,
    val mainPenaltyTx: Transactions.TransactionWithInputInfo.MainPenaltyTx? = null,
    val htlcPenaltyTxs: List<Transactions.TransactionWithInputInfo.HtlcPenaltyTx> = emptyList(),
    val claimHtlcDelayedPenaltyTxs: List<Transactions.TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx> = emptyList(),
    val irrevocablySpent: Map<@Serializable(with = OutPointKSerializer::class) OutPoint, @Serializable(with = TransactionKSerializer::class) Transaction> = emptyMap()
) {
    fun export() = fr.acinq.lightning.channel.RevokedCommitPublished(commitTx, remotePerCommitmentSecret, claimMainOutputTx, mainPenaltyTx, htlcPenaltyTxs, claimHtlcDelayedPenaltyTxs, irrevocablySpent)
}

/**
 * README: by design, we do not include channel private keys and secret here, so they won't be included in our backups (local files, encrypted peer backup, ...), so even
 * if these backups were compromised channel private keys would not be leaked unless the main seed was also compromised.
 * This means that they will be recomputed once when we convert serialized data to their "live" counterparts.
 */
@Serializable
internal data class LocalParams constructor(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = KeyPathKSerializer::class) val fundingKeyPath: KeyPath,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long,
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    val isFunder: Boolean,
    @Serializable(with = ByteVectorKSerializer::class) val defaultFinalScriptPubKey: ByteVector,
    val features: Features
) {
    fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.LocalParams(
        nodeId,
        nodeParams.keyManager.channelKeys(fundingKeyPath),
        dustLimit,
        maxHtlcValueInFlightMsat,
        channelReserve,
        htlcMinimum,
        toSelfDelay,
        maxAcceptedHtlcs,
        isFunder,
        defaultFinalScriptPubKey,
        features
    )
}

@Serializable
internal data class RemoteParams(
    @Serializable(with = PublicKeyKSerializer::class) val nodeId: PublicKey,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val maxHtlcValueInFlightMsat: Long,
    @Serializable(with = SatoshiKSerializer::class) val channelReserve: Satoshi,
    val htlcMinimum: MilliSatoshi,
    val toSelfDelay: CltvExpiryDelta,
    val maxAcceptedHtlcs: Int,
    @Serializable(with = PublicKeyKSerializer::class) val fundingPubKey: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val revocationBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val paymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val delayedPaymentBasepoint: PublicKey,
    @Serializable(with = PublicKeyKSerializer::class) val htlcBasepoint: PublicKey,
    val features: Features
) {
    fun export() = fr.acinq.lightning.channel.RemoteParams(
        nodeId,
        dustLimit,
        maxHtlcValueInFlightMsat,
        channelReserve,
        htlcMinimum,
        toSelfDelay,
        maxAcceptedHtlcs,
        fundingPubKey,
        revocationBasepoint,
        paymentBasepoint,
        delayedPaymentBasepoint,
        htlcBasepoint,
        features
    )
}

@Serializable
internal data class ChannelVersion(@Serializable(with = ByteVectorKSerializer::class) val bits: ByteVector) {
    init {
        require(bits.size() == 4) { "channel version takes 4 bytes" }
    }

    companion object {
        // NB: this is the only value that was supported in v1
        val standard = ChannelVersion(ByteVector("0000000f"))

        // This is the corresponding channel config
        val channelConfig = fr.acinq.lightning.channel.ChannelConfig.standard

        // These are the corresponding channel features
        val channelFeatures = fr.acinq.lightning.channel.ChannelFeatures(
            setOf(
                Feature.Wumbo,
                Feature.StaticRemoteKey,
                Feature.AnchorOutputs,
                Feature.ZeroReserveChannels,
                Feature.ZeroConfChannels,
            )
        )
    }
}

@Serializable
internal data class ClosingTxProposed(val unsignedTx: Transactions.TransactionWithInputInfo.ClosingTx, val localClosingSigned: ClosingSigned) {
    fun export() = fr.acinq.lightning.channel.ClosingTxProposed(unsignedTx, localClosingSigned)
}

@Serializable
internal data class Commitments(
    val channelVersion: ChannelVersion,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    val channelFlags: Byte,
    val localCommit: LocalCommit,
    val remoteCommit: RemoteCommit,
    val localChanges: LocalChanges,
    val remoteChanges: RemoteChanges,
    val localNextHtlcId: Long,
    val remoteNextHtlcId: Long,
    val payments: Map<Long, UUID>,
    @Serializable(with = EitherSerializer::class) val remoteNextCommitInfo: Either<WaitingForRevocation, @Serializable(with = PublicKeyKSerializer::class) PublicKey>,
    val commitInput: Transactions.InputInfo,
    @Serializable(with = ShaChainSerializer::class) val remotePerCommitmentSecrets: ShaChain,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val remoteChannelData: EncryptedChannelData = EncryptedChannelData.empty
) {
    fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Commitments(
        ChannelVersion.channelConfig,
        ChannelVersion.channelFeatures,
        localParams.export(nodeParams),
        remoteParams.export(),
        channelFlags,
        localCommit.export(),
        remoteCommit.export(),
        localChanges.export(),
        remoteChanges.export(),
        localNextHtlcId,
        remoteNextHtlcId,
        payments,
        remoteNextCommitInfo.transform({ x -> x.export() }, { y -> y }),
        commitInput,
        remotePerCommitmentSecrets,
        channelId,
        remoteChannelData
    )
}

@Serializable
internal data class OnChainFeerates(val mutualCloseFeerate: FeeratePerKw, val claimMainFeerate: FeeratePerKw, val fastFeerate: FeeratePerKw) {
    fun export() = fr.acinq.lightning.blockchain.fee.OnChainFeerates(mutualCloseFeerate, claimMainFeerate, fastFeerate)
}

@Serializable
internal data class StaticParams(@Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32, @Serializable(with = PublicKeyKSerializer::class) val remoteNodeId: PublicKey) {
    fun export(nodeParams: NodeParams): fr.acinq.lightning.channel.StaticParams {
        require(chainHash == nodeParams.chainHash) { "restoring data from a different chain" }
        return fr.acinq.lightning.channel.StaticParams(nodeParams, this.remoteNodeId)
    }
}

@Serializable
internal sealed class ChannelState {
    abstract val staticParams: StaticParams
    abstract val currentTip: Pair<Int, BlockHeader>
    abstract val currentOnChainFeerates: OnChainFeerates
}

@Serializable
internal sealed class ChannelStateWithCommitments : ChannelState() {
    abstract val commitments: Commitments
    val channelId: ByteVector32 get() = commitments.channelId
    abstract fun export(nodeParams: NodeParams): fr.acinq.lightning.channel.ChannelStateWithCommitments
}

@Serializable
internal data class Aborted(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates
) : ChannelState()

@Serializable
internal data class WaitForInit(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates
) : ChannelState()

@Serializable
internal data class Offline(val state: ChannelStateWithCommitments) : ChannelState() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
}

@Serializable
internal data class Syncing(val state: ChannelStateWithCommitments, val waitForTheirReestablishMessage: Boolean) : ChannelState() {
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates
}

@Serializable
internal data class WaitForOpenChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteInit: Init
) : ChannelState()

@Serializable
internal data class WaitForRemotePublishFutureCommitment(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val remoteChannelReestablish: ChannelReestablish
) : ChannelStateWithCommitments() {

    override fun export(nodeParams: NodeParams) =
        fr.acinq.lightning.channel.WaitForRemotePublishFutureCommitment(staticParams.export(nodeParams), currentTip, currentOnChainFeerates.export(), commitments.export(nodeParams), remoteChannelReestablish)
}

@Serializable
internal data class WaitForFundingCreated(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val temporaryChannelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi,
    val pushAmount: MilliSatoshi,
    val initialFeerate: FeeratePerKw,
    @Serializable(with = PublicKeyKSerializer::class) val remoteFirstPerCommitmentPoint: PublicKey,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: AcceptChannel
) : ChannelState()

@Serializable
internal data class FundingInput(
    @Serializable(with = TransactionKSerializer::class) val previousTx: Transaction,
    val outputIndex: Int,
    @Serializable(with = PrivateKeyKSerializer::class) val privateKey: PrivateKey
)

@Serializable
internal data class FundingInputs(@Serializable(with = SatoshiKSerializer::class) val fundingAmount: Satoshi, val inputs: List<FundingInput>)

@Serializable
internal data class InitInitiator(
    val fundingInputs: FundingInputs,
    val pushAmount: MilliSatoshi,
    val commitTxFeerate: FeeratePerKw,
    val fundingTxFeerate: FeeratePerKw,
    val localParams: LocalParams,
    val remoteInit: Init,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion
)

@Serializable
internal data class WaitForAcceptChannel(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    val init: InitInitiator,
    val lastSent: OpenChannel
) : ChannelState()

@Serializable
internal data class WaitForFundingSigned(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    val localParams: LocalParams,
    val remoteParams: RemoteParams,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction,
    @Serializable(with = SatoshiKSerializer::class) val fundingTxFee: Satoshi,
    val localSpec: CommitmentSpec,
    val localCommitTx: Transactions.TransactionWithInputInfo.CommitTx,
    val remoteCommit: RemoteCommit,
    val channelFlags: Byte,
    val channelVersion: ChannelVersion,
    val lastSent: FundingCreated
) : ChannelState()

@Serializable
internal data class WaitForFundingConfirmed(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long, // how long have we been waiting for the funding tx to confirm
    val deferred: FundingLocked?,
    @Serializable(with = EitherSerializer::class) val lastSent: Either<FundingCreated, FundingSigned>
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.WaitForFundingConfirmed(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        fundingTx,
        waitingSinceBlock,
        deferred,
        lastSent
    )
}

@Serializable
internal data class WaitForFundingLocked(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val lastSent: FundingLocked
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.WaitForFundingLocked(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        shortChannelId,
        lastSent
    )
}

@Serializable
internal data class Normal(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val shortChannelId: ShortChannelId,
    val buried: Boolean,
    val channelAnnouncement: ChannelAnnouncement?,
    val channelUpdate: ChannelUpdate,
    val remoteChannelUpdate: ChannelUpdate?,
    val localShutdown: Shutdown?,
    val remoteShutdown: Shutdown?
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Normal(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        shortChannelId,
        buried,
        channelAnnouncement,
        channelUpdate,
        remoteChannelUpdate,
        localShutdown,
        remoteShutdown,
        null
    )
}

@Serializable
internal data class ShuttingDown(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.ShuttingDown(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        localShutdown,
        remoteShutdown,
        null
    )
}

@Serializable
internal data class Negotiating(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    val localShutdown: Shutdown,
    val remoteShutdown: Shutdown,
    val closingTxProposed: List<List<ClosingTxProposed>>,
    val bestUnpublishedClosingTx: Transactions.TransactionWithInputInfo.ClosingTx?
) : ChannelStateWithCommitments() {
    init {
        require(closingTxProposed.isNotEmpty()) { "there must always be a list for the current negotiation" }
        require(!commitments.localParams.isFunder || !closingTxProposed.any { it.isEmpty() }) { "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing" }
    }

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Negotiating(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        localShutdown,
        remoteShutdown,
        closingTxProposed.map { x -> x.map { it.export() } },
        bestUnpublishedClosingTx,
        null
    )
}

@Serializable
internal data class Closing(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments,
    @Serializable(with = TransactionKSerializer::class) val fundingTx: Transaction?,
    val waitingSinceBlock: Long,
    val mutualCloseProposed: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val mutualClosePublished: List<Transactions.TransactionWithInputInfo.ClosingTx> = emptyList(),
    val localCommitPublished: LocalCommitPublished? = null,
    val remoteCommitPublished: RemoteCommitPublished? = null,
    val nextRemoteCommitPublished: RemoteCommitPublished? = null,
    val futureRemoteCommitPublished: RemoteCommitPublished? = null,
    val revokedCommitPublished: List<RevokedCommitPublished> = emptyList()
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Closing(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams),
        fundingTx,
        waitingSinceBlock,
        mutualCloseProposed,
        mutualClosePublished,
        localCommitPublished?.export(),
        remoteCommitPublished?.export(),
        nextRemoteCommitPublished?.export(),
        futureRemoteCommitPublished?.export(),
        revokedCommitPublished.map { it.export() }
    )
}

@Serializable
internal data class Closed(val state: Closing) : ChannelStateWithCommitments() {
    override val commitments: Commitments get() = state.commitments
    override val staticParams: StaticParams get() = state.staticParams
    override val currentTip: Pair<Int, BlockHeader> get() = state.currentTip
    override val currentOnChainFeerates: OnChainFeerates get() = state.currentOnChainFeerates

    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.Closed(state.export(nodeParams))
}

@Serializable
internal data class ErrorInformationLeak(
    override val staticParams: StaticParams,
    override val currentTip: Pair<Int, @Serializable(with = BlockHeaderKSerializer::class) BlockHeader>,
    override val currentOnChainFeerates: OnChainFeerates,
    override val commitments: Commitments
) : ChannelStateWithCommitments() {
    override fun export(nodeParams: NodeParams) = fr.acinq.lightning.channel.ErrorInformationLeak(
        staticParams.export(nodeParams),
        currentTip,
        currentOnChainFeerates.export(),
        commitments.export(nodeParams)
    )
}

internal object ShaChainSerializer : KSerializer<ShaChain> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ShaChain") {
        element("knownHashes", mapSerialDescriptor(String.serializer().descriptor, ByteVector32KSerializer.descriptor))
        element<Long>("lastIndex", isOptional = true)
    }

    private fun List<Boolean>.toBinaryString(): String = this.map { if (it) '1' else '0' }.joinToString(separator = "")
    private fun String.toBooleanList(): List<Boolean> = this.map { it == '1' }

    private val mapSerializer = MapSerializer(String.serializer(), ByteVector32KSerializer)

    override fun serialize(encoder: Encoder, value: ShaChain) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, mapSerializer, value.knownHashes.mapKeys { it.key.toBinaryString() })
        if (value.lastIndex != null) compositeEncoder.encodeLongElement(descriptor, 1, value.lastIndex)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ShaChain {
        var knownHashes: Map<List<Boolean>, ByteVector32>? = null
        var lastIndex: Long? = null

        val compositeDecoder = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (compositeDecoder.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> knownHashes = compositeDecoder.decodeSerializableElement(descriptor, 0, mapSerializer).mapKeys { it.key.toBooleanList() }
                1 -> lastIndex = compositeDecoder.decodeLongElement(descriptor, 1)
            }
        }
        compositeDecoder.endStructure(descriptor)

        return ShaChain(
            knownHashes ?: error("No knownHashes in structure"),
            lastIndex
        )
    }
}

internal class EitherSerializer<A : Any, B : Any>(val aSer: KSerializer<A>, val bSer: KSerializer<B>) :
    KSerializer<Either<A, B>> {

    override val descriptor = buildClassSerialDescriptor("Either", aSer.descriptor, bSer.descriptor) {
        element("left", aSer.descriptor, isOptional = true)
        element("right", bSer.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: Either<A, B>) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        when (value) {
            is Either.Left -> compositeEncoder.encodeSerializableElement(descriptor, 0, aSer, value.value)
            is Either.Right -> compositeEncoder.encodeSerializableElement(descriptor, 1, bSer, value.value)
        }
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): Either<A, B> {
        lateinit var either: Either<A, B>

        val compositeDecoder = decoder.beginStructure(descriptor)
        when (val i = compositeDecoder.decodeElementIndex(descriptor)) {
            0 -> either = Either.Left(compositeDecoder.decodeSerializableElement(descriptor, i, aSer))
            1 -> either = Either.Right(compositeDecoder.decodeSerializableElement(descriptor, i, bSer))
        }
        compositeDecoder.endStructure(descriptor)
        return either
    }
}
