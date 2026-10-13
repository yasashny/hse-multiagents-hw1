package com.yasashny.agentshw1.llm.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object MessageRoleSerializer : KSerializer<MessageRole> {

    override val descriptor = PrimitiveSerialDescriptor("MessageRole", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MessageRole) {
        encoder.encodeString(value.wire)
    }

    override fun deserialize(decoder: Decoder): MessageRole =
        MessageRole.fromWire(decoder.decodeString())
}
