package com.turbomates.event.seriazlier

import com.turbomates.event.Event
import kotlin.reflect.KClass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
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

@Serializer(forClass = Event::class)
object EventSerializer : KSerializer<Event> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Event") {
        element<String>("type")
        element<JsonObject>("body")
    }

    @OptIn(InternalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Event {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can be loaded only by Json")
        val tree = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected JsonObject")
        val type: KClass<Event> = Class.forName(tree.getValue("type").jsonPrimitive.content).kotlin as KClass<Event>
        return input.json.decodeFromJsonElement(type.serializer(), tree.getValue("body").jsonObject)
    }

    @OptIn(InternalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Event) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("This class can be saved only by Json")
        val tree = JsonObject(
            mapOf(
                "type" to JsonPrimitive(value::class.qualifiedName!!),
                "body" to output.json.encodeToJsonElement(
                    value::class.serializer() as KSerializer<Event>,
                    value
                )
            )
        )
        output.encodeJsonElement(tree)
    }
}

