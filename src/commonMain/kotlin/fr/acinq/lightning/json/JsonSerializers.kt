@file:OptIn(ExperimentalSerializationApi::class)
@file:UseSerializers(
    // This is used by Kotlin at compile time to resolve serializers (defined in this file)
    // in order to build serializers for other classes (also defined in this file).
    // If we used @Serializable annotations directly on the actual classes, Kotlin would be
    // able to resolve serializers by itself. It is verbose, but it allows us to contain
    // serialization code in this file.
    JsonSerializers.CommitmentsSerializer::class,
    JsonSerializers.ClosingFeeratesSerializer::class,
    JsonSerializers.LocalParamsSerializer::class,
    JsonSerializers.RemoteParamsSerializer::class,
    JsonSerializers.LocalCommitSerializer::class,
    JsonSerializers.RemoteCommitSerializer::class,
    JsonSerializers.LocalChangesSerializer::class,
    JsonSerializers.RemoteChangesSerializer::class,
    JsonSerializers.EitherSerializer::class,
    JsonSerializers.ShaChainSerializer::class,
    JsonSerializers.ByteVectorSerializer::class,
    JsonSerializers.ByteVector32Serializer::class,
    JsonSerializers.ByteVector64Serializer::class,
    JsonSerializers.PublicKeySerializer::class,
    JsonSerializers.PrivateKeySerializer::class,
    JsonSerializers.KeyPathSerializer::class,
    JsonSerializers.SatoshiSerializer::class,
    JsonSerializers.MilliSatoshiSerializer::class,
    JsonSerializers.CltvExpirySerializer::class,
    JsonSerializers.CltvExpiryDeltaSerializer::class,
    JsonSerializers.FeeratePerKwSerializer::class,
    JsonSerializers.CommitmentSpecSerializer::class,
    JsonSerializers.PublishableTxsSerializer::class,
    JsonSerializers.HtlcTxAndSigsSerializer::class,
    JsonSerializers.ChannelConfigSerializer::class,
    JsonSerializers.ChannelFeaturesSerializer::class,
    JsonSerializers.FeaturesSerializer::class,
    JsonSerializers.ShortChannelIdSerializer::class,
    JsonSerializers.StaticParamsSerializer::class,
    JsonSerializers.BlockHeaderSerializer::class,
    JsonSerializers.OnChainFeeratesSerializer::class,
    JsonSerializers.ChannelKeysSerializer::class,
    JsonSerializers.TransactionSerializer::class,
    JsonSerializers.OutPointSerializer::class,
    JsonSerializers.ClosingTxProposedSerializer::class,
    JsonSerializers.LocalCommitPublishedSerializer::class,
    JsonSerializers.RemoteCommitPublishedSerializer::class,
    JsonSerializers.RevokedCommitPublishedSerializer::class,
    JsonSerializers.OnionRoutingPacketSerializer::class,
    JsonSerializers.FundingSignedSerializer::class,
    JsonSerializers.UpdateAddHtlcSerializer::class,
    JsonSerializers.UpdateFulfillHtlcSerializer::class,
    JsonSerializers.UpdateFailHtlcSerializer::class,
    JsonSerializers.UpdateFailMalformedHtlcSerializer::class,
    JsonSerializers.UpdateFeeSerializer::class,
    JsonSerializers.ChannelUpdateSerializer::class,
    JsonSerializers.ChannelAnnouncementSerializer::class,
    JsonSerializers.WaitingForRevocationSerializer::class,
    JsonSerializers.InteractiveTxParamsSerializer::class,
    JsonSerializers.SignedSharedTransactionSerializer::class,
    JsonSerializers.RbfStatusSerializer::class,
    JsonSerializers.EncryptedChannelDataSerializer::class,
    JsonSerializers.ShutdownSerializer::class,
    JsonSerializers.ClosingSignedSerializer::class,
    JsonSerializers.UpdateAddHtlcSerializer::class,
    JsonSerializers.CommitSigSerializer::class,
    JsonSerializers.EncryptedChannelDataSerializer::class,
    JsonSerializers.ChannelReestablishDataSerializer::class,
    JsonSerializers.FundingLockedSerializer::class,
    JsonSerializers.FundingCreatedSerializer::class,
    JsonSerializers.ChannelReadySerializer::class,
    JsonSerializers.ChannelReadyTlvShortChannelIdTlvSerializer::class,
    JsonSerializers.ClosingSignedTlvFeeRangeSerializer::class,
    JsonSerializers.ShutdownTlvChannelDataSerializer::class,
    JsonSerializers.GenericTlvSerializer::class,
    JsonSerializers.TlvStreamSerializer::class,
    JsonSerializers.ShutdownTlvSerializer::class,
    JsonSerializers.ClosingSignedTlvSerializer::class,
    JsonSerializers.ChannelReestablishTlvSerializer::class,
    JsonSerializers.ChannelReadyTlvSerializer::class,
    JsonSerializers.CommitSigTlvSerializer::class,
    JsonSerializers.UUIDSerializer::class,
)

package fr.acinq.lightning.json

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.ShaChain
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic

/**
 * Json support for [ChannelState] based on `kotlinx-serialization`.
 *
 * The implementation is self-contained, all serializers are defined here as opposed to tagging
 * our classes with `@Serializable`. Depending on the class to serialize, we use two techniques
 * to define serializers:
 *  - `@Serializer(forClass = T::class)`, simplest method, equivalent to tagging T with `@Serializable`
 *  - [SurrogateSerializer] and its children ([StringSerializer], [LongSerializer]) for more complex cases
 *
 * Note that we manually have to define polymorphic classes in [SerializersModule], which would be done automatically
 * by Kotlin if we directly tagged `sealed classes` with `@Serializable`.
 */
object JsonSerializers {

    val json = Json {
        prettyPrint = true
        serializersModule = SerializersModule {
            polymorphic(ChannelStateWithCommitments::class) {
                subclass(LegacyWaitForFundingConfirmed::class, LegacyWaitForFundingConfirmedSerializer)
                subclass(LegacyWaitForFundingLocked::class, LegacyWaitForFundingLockedSerializer)
                subclass(WaitForChannelReady::class, WaitForChannelReadySerializer)
                subclass(WaitForFundingConfirmed::class, WaitForFundingConfirmedSerializer)
                subclass(Normal::class, NormalSerializer)
                subclass(ShuttingDown::class, ShuttingDownSerializer)
                subclass(Negotiating::class, NegotiatingSerializer)
                subclass(Closing::class, ClosingSerializer)
                subclass(WaitForRemotePublishFutureCommitment::class, WaitForRemotePublishFutureCommitmentSerializer)
            }
            polymorphic(UpdateMessage::class) {
                subclass(UpdateAddHtlc::class, UpdateAddHtlcSerializer)
                subclass(UpdateFailHtlc::class, UpdateFailHtlcSerializer)
                subclass(UpdateFailMalformedHtlc::class, UpdateFailMalformedHtlcSerializer)
                subclass(UpdateFulfillHtlc::class, UpdateFulfillHtlcSerializer)
                subclass(UpdateFee::class, UpdateFeeSerializer)
            }
            polymorphic(Tlv::class) {
                subclass(ShutdownTlv.ChannelData::class, ShutdownTlvChannelDataSerializer)
                subclass(ClosingSignedTlv.FeeRange::class, ClosingSignedTlvFeeRangeSerializer)
            }
            contextual(PolymorphicSerializer(ChannelStateWithCommitments::class))
            contextual(PolymorphicSerializer(UpdateMessage::class))
            contextual(PolymorphicSerializer(DirectedHtlc::class))
            // TODO following are serializers defined as @Contextual in project
            //   classes, which is why they must be explicitly set here. Once the
            //   @Contextual annotations have been removed this should be cleaned up
            contextual(OutPointSerializer)
            contextual(TxOutSerializer)
            contextual(TransactionSerializer)
            contextual(SatoshiSerializer)
            contextual(ByteVectorSerializer)
            contextual(ByteVector32Serializer)
            contextual(ByteVector64Serializer)
            contextual(ChannelConfigSerializer)
            contextual(PrivateKeySerializer)
            contextual(PublicKeySerializer)
            contextual(MilliSatoshiSerializer)
            contextual(UpdateAddHtlcSerializer)
            contextual(ChannelUpdateSerializer)
        }
    }

    @Serializer(forClass = LegacyWaitForFundingConfirmed::class)
    object LegacyWaitForFundingConfirmedSerializer

    @Serializer(forClass = LegacyWaitForFundingLocked::class)
    object LegacyWaitForFundingLockedSerializer

    @Serializer(forClass = WaitForFundingConfirmed::class)
    object WaitForFundingConfirmedSerializer

    @Serializer(forClass = WaitForChannelReady::class)
    object WaitForChannelReadySerializer

    @Serializer(forClass = Normal::class)
    object NormalSerializer

    @Serializer(forClass = ShuttingDown::class)
    object ShuttingDownSerializer

    @Serializer(forClass = Negotiating::class)
    object NegotiatingSerializer

    @Serializer(forClass = Closing::class)
    object ClosingSerializer

    @Serializer(forClass = WaitForRemotePublishFutureCommitment::class)
    object WaitForRemotePublishFutureCommitmentSerializer

    @Serializer(forClass = InteractiveTxParams::class)
    object InteractiveTxParamsSerializer

    @Serializer(forClass = SignedSharedTransaction::class)
    object SignedSharedTransactionSerializer

    @Serializer(forClass = WaitForFundingConfirmed.Companion.RbfStatus::class)
    object RbfStatusSerializer

    @Serializer(forClass = Commitments::class)
    object CommitmentsSerializer

    @Serializer(forClass = ClosingFeerates::class)
    object ClosingFeeratesSerializer

    @Serializer(forClass = LocalParams::class)
    object LocalParamsSerializer

    @Serializer(forClass = RemoteParams::class)
    object RemoteParamsSerializer

    @Serializer(forClass = LocalCommit::class)
    object LocalCommitSerializer

    @Serializer(forClass = RemoteCommit::class)
    object RemoteCommitSerializer

    @Serializer(forClass = LocalChanges::class)
    object LocalChangesSerializer

    @Serializer(forClass = RemoteChanges::class)
    object RemoteChangesSerializer

    /**
     * Delegate serialization of type [T] to serialization of type [S].
     * @param transform a conversion method from [T] to [S]
     * @param delegateSerializer serializer for [S]
     */
    sealed class SurrogateSerializer<T, S>(val transform: (T) -> S, private val delegateSerializer: KSerializer<S>) : KSerializer<T> {
        override val descriptor: SerialDescriptor get() = delegateSerializer.descriptor
        override fun serialize(encoder: Encoder, value: T) = delegateSerializer.serialize(encoder, transform(value))
        override fun deserialize(decoder: Decoder): T = TODO("Not yet implemented")
    }

    sealed class StringSerializer<T>(toString: (T) -> String = { it.toString() }) : SurrogateSerializer<T, String>(toString, String.serializer())
    sealed class LongSerializer<T>(toLong: (T) -> Long) : SurrogateSerializer<T, Long>(toLong, Long.serializer())

    object StaticParamsSerializer : StringSerializer<StaticParams>({ "<skipped>" })
    object BlockHeaderSerializer : StringSerializer<BlockHeader>({ "<skipped>" })
    object OnChainFeeratesSerializer : StringSerializer<OnChainFeerates>({ "<skipped>" })

    object ShaChainSerializer : StringSerializer<ShaChain>({ "<redacted>" })
    object PrivateKeySerializer : StringSerializer<PrivateKey>({ "<redacted>" })
    object OnionRoutingPacketSerializer : StringSerializer<OnionRoutingPacket>({ "<redacted>" })
    object ByteVectorSerializer : StringSerializer<ByteVector>()
    object ByteVector32Serializer : StringSerializer<ByteVector32>()
    object ByteVector64Serializer : StringSerializer<ByteVector64>()
    object PublicKeySerializer : StringSerializer<PublicKey>()
    object KeyPathSerializer : StringSerializer<KeyPath>()
    object ShortChannelIdSerializer : StringSerializer<ShortChannelId>()
    object OutPointSerializer : StringSerializer<OutPoint>({ "${it.txid}:${it.index}" })
    object TransactionSerializer : StringSerializer<Transaction>()

    @Serializer(forClass = PublishableTxs::class)
    object PublishableTxsSerializer

    @Serializable
    data class CommitmentsSpecSurrogate(val htlcsIn: List<UpdateAddHtlc>, val htlcsOut: List<UpdateAddHtlc>, val feerate: FeeratePerKw, val toLocal: MilliSatoshi, val toRemote: MilliSatoshi)
    object CommitmentSpecSerializer : SurrogateSerializer<CommitmentSpec, CommitmentsSpecSurrogate>(
        transform = { o ->
            CommitmentsSpecSurrogate(
                htlcsIn = o.htlcs.filterIsInstance<IncomingHtlc>().map { it.add },
                htlcsOut = o.htlcs.filterIsInstance<OutgoingHtlc>().map { it.add },
                feerate = o.feerate, toLocal = o.toLocal, toRemote = o.toRemote
            )
        },
        delegateSerializer = CommitmentsSpecSurrogate.serializer()
    )

    @Serializer(forClass = HtlcTxAndSigs::class)
    object HtlcTxAndSigsSerializer

    object ChannelConfigSerializer : SurrogateSerializer<ChannelConfig, List<String>>(
        transform = { o -> o.options.map { it.name } },
        delegateSerializer = ListSerializer(String.serializer())
    )

    object ChannelFeaturesSerializer : SurrogateSerializer<ChannelFeatures, List<String>>(
        transform = { o -> o.features.map { it.rfcName } },
        delegateSerializer = ListSerializer(String.serializer())
    )

    @Serializable
    data class FeaturesSurrogate(val activated: Map<String, String>, val unknown: Set<Int>)
    object FeaturesSerializer : SurrogateSerializer<Features, FeaturesSurrogate>(
        transform = { o -> FeaturesSurrogate(o.activated.map { it.key.rfcName to it.value.name }.toMap(), o.unknown.map { it.bitIndex }.toSet()) },
        delegateSerializer = FeaturesSurrogate.serializer()
    )

    @Serializable
    data class TxOutSurrogate(val amount: Satoshi, val publicKeyScript: ByteVector)
    object TxOutSerializer : SurrogateSerializer<TxOut, TxOutSurrogate>(
        transform = { o -> TxOutSurrogate(o.amount, o.publicKeyScript) },
        delegateSerializer = TxOutSurrogate.serializer()
    )

    object SatoshiSerializer : LongSerializer<Satoshi>({ it.toLong() })
    object MilliSatoshiSerializer : LongSerializer<MilliSatoshi>({ it.toLong() })
    object CltvExpirySerializer : LongSerializer<CltvExpiry>({ it.toLong() })
    object CltvExpiryDeltaSerializer : LongSerializer<CltvExpiryDelta>({ it.toLong() })
    object FeeratePerKwSerializer : LongSerializer<FeeratePerKw>({ it.toLong() })

    object ChannelKeysSerializer : SurrogateSerializer<ChannelKeys, KeyPath>(
        transform = { it.fundingKeyPath },
        delegateSerializer = KeyPathSerializer
    )

    @Serializer(forClass = ClosingTxProposed::class)
    object ClosingTxProposedSerializer

    @Serializer(forClass = LocalCommitPublished::class)
    object LocalCommitPublishedSerializer

    @Serializer(forClass = RemoteCommitPublished::class)
    object RemoteCommitPublishedSerializer

    @Serializer(forClass = RevokedCommitPublished::class)
    object RevokedCommitPublishedSerializer

    @Serializer(forClass = FundingSigned::class)
    object FundingSignedSerializer

    @Serializer(forClass = EncryptedChannelData::class)
    object EncryptedChannelDataSerializer

    @Serializer(forClass = UpdateAddHtlc::class)
    object UpdateAddHtlcSerializer

    @Serializer(forClass = UpdateFulfillHtlc::class)
    object UpdateFulfillHtlcSerializer

    @Serializer(forClass = UpdateFailHtlc::class)
    object UpdateFailHtlcSerializer

    @Serializer(forClass = UpdateFailMalformedHtlc::class)
    object UpdateFailMalformedHtlcSerializer

    @Serializer(forClass = UpdateFee::class)
    object UpdateFeeSerializer

    @Serializer(forClass = ChannelUpdate::class)
    object ChannelUpdateSerializer

    @Serializer(forClass = ChannelAnnouncement::class)
    object ChannelAnnouncementSerializer

    @Serializer(forClass = Shutdown::class)
    object ShutdownSerializer

    @Serializer(forClass = ClosingSigned::class)
    object ClosingSignedSerializer

    @Serializer(forClass = CommitSig::class)
    object CommitSigSerializer

    @Serializer(forClass = ChannelReestablish::class)
    object ChannelReestablishDataSerializer

    @Serializer(forClass = FundingLocked::class)
    object FundingLockedSerializer

    @Serializer(forClass = FundingCreated::class)
    object FundingCreatedSerializer

    @Serializer(forClass = ChannelReady::class)
    object ChannelReadySerializer

    @Serializer(forClass = ChannelReadyTlv.ShortChannelIdTlv::class)
    object ChannelReadyTlvShortChannelIdTlvSerializer

    @Serializer(forClass = ClosingSignedTlv.FeeRange::class)
    object ClosingSignedTlvFeeRangeSerializer

    @Serializer(forClass = ShutdownTlv.ChannelData::class)
    object ShutdownTlvChannelDataSerializer

    @Serializer(forClass = ShutdownTlv::class)
    object ShutdownTlvSerializer

    @Serializer(forClass = CommitSigTlv::class)
    object CommitSigTlvSerializer

    @Serializer(forClass = ClosingSignedTlv::class)
    object ClosingSignedTlvSerializer

    @Serializer(forClass = ChannelReadyTlv::class)
    object ChannelReadyTlvSerializer

    @Serializer(forClass = ChannelReestablishTlv::class)
    object ChannelReestablishTlvSerializer

    @Serializer(forClass = GenericTlv::class)
    object GenericTlvSerializer

    @Serializable
    data class TlvStreamSurrogate(val records: List<Tlv>, val unknown: List<GenericTlv> = listOf())
    class TlvStreamSerializer<T : Tlv> : KSerializer<TlvStream<T>> {
        private val delegateSerializer = TlvStreamSurrogate.serializer()
        override val descriptor: SerialDescriptor = delegateSerializer.descriptor
        override fun serialize(encoder: Encoder, value: TlvStream<T>) =
            delegateSerializer.serialize(encoder, TlvStreamSurrogate(value.records, value.unknown))
        override fun deserialize(decoder: Decoder): TlvStream<T> = TODO()
    }

    class EitherSerializer<A : Any, B : Any>(val aSer: KSerializer<A>, val bSer: KSerializer<B>) : KSerializer<Either<A, B>> {
        @Serializable
        data class Surrogate<A : Any, B : Any>(val left: A?, val right: B?)

        override val descriptor = Surrogate.serializer(aSer, bSer).descriptor

        override fun serialize(encoder: Encoder, value: Either<A, B>) {
            val surrogate = Surrogate(value.left, value.right)
            return encoder.encodeSerializableValue(Surrogate.serializer(aSer, bSer), surrogate)
        }

        override fun deserialize(decoder: Decoder): Either<A, B> = TODO()
    }

    @Serializer(forClass = WaitingForRevocation::class)
    object WaitingForRevocationSerializer

    object UUIDSerializer : StringSerializer<UUID>()
}
