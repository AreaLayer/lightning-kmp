@file:OptIn(ExperimentalSerializationApi::class)

package fr.acinq.lightning.wire

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test

@Serializable
data class MyUpdateAddHtlc(
    @Serializable(with = ByteVector32KSerializer::class) val channelId: ByteVector32,
    @Serializable(with = U64KSerializer::class) val htlcId: Long,
    @Serializable(with = MilliSatoshiSerializer::class) val amount: MilliSatoshi
)

object ByteVector32KSerializer : KSerializer<ByteVector32> {
    override val descriptor: SerialDescriptor get() = PrimitiveSerialDescriptor("ByteVector32", PrimitiveKind.BYTE)
    override fun deserialize(decoder: Decoder): ByteVector32 = ByteVector32(decoder.decodeSerializableValue(ByteArraySerializer()))
    override fun serialize(encoder: Encoder, value: ByteVector32) = encoder.encodeSerializableValue(ByteArraySerializer(), value.toByteArray())
}

object U64KSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor get() = PrimitiveSerialDescriptor("U64", PrimitiveKind.BYTE)
    override fun deserialize(decoder: Decoder): Long = Pack.int64BE(decoder.decodeSerializableValue(ByteArraySerializer()))
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeSerializableValue(ByteArraySerializer(), Pack.writeInt64BE(value))
}

object MilliSatoshiSerializer : KSerializer<MilliSatoshi> {
    override val descriptor: SerialDescriptor get() = PrimitiveSerialDescriptor("MilliSatoshi", PrimitiveKind.BYTE)
    override fun deserialize(decoder: Decoder): MilliSatoshi = MilliSatoshi(Pack.int64BE(decoder.decodeSerializableValue(ByteArraySerializer())))
    override fun serialize(encoder: Encoder, value: MilliSatoshi) = encoder.encodeSerializableValue(ByteArraySerializer(), Pack.writeInt64BE(value.toLong()))
}


class ByteVectorEncoder : AbstractEncoder() {
    var res = ByteVector.empty

    override val serializersModule: SerializersModule
        get() = EmptySerializersModule

    override fun encodeByte(value: Byte) {
        res = res.concat(value)
    }
}


class CustomSerializationTest : LightningTestSuite() {

    @Test
    fun `basic test`() {
        val htlc = MyUpdateAddHtlc(ByteVector32.fromValidHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"), 42, 123456.msat)
        val encoder = ByteVectorEncoder()
        MyUpdateAddHtlc.serializer().serialize(encoder, htlc)
        println(encoder.res.toHex())

    }
}