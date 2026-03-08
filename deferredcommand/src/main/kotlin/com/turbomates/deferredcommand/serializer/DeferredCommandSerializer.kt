package com.turbomates.deferredcommand.serializer

import com.turbomates.deferredcommand.DeferredCommand
import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

object DeferredCommandSerializer : KSerializer<DeferredCommand> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("DeferredCommand") {
        element<String>("type")
        element<JsonObject>("body")
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): DeferredCommand {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can be loaded only by Json")
        val tree = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected JsonObject")
        val type: KClass<DeferredCommand> =
            Class.forName(tree.getValue("type").jsonPrimitive.content).kotlin as KClass<DeferredCommand>
        return input.json.decodeFromJsonElement(type.serializer(), tree.getValue("body").jsonObject)
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun serialize(encoder: Encoder, value: DeferredCommand) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("This class can be saved only by Json")
        val tree = JsonObject(
            mapOf(
                "type" to JsonPrimitive(value::class.qualifiedName!!),
                "body" to output.json.encodeToJsonElement(
                    value::class.serializer() as KSerializer<DeferredCommand>,
                    value
                )
            )
        )
        output.encodeJsonElement(tree)
    }
}
