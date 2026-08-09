package com.turbomates.event.seriazlier

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.hasDeclaredName
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

/**
 * Writes an event as `{"type": .., "body": {..}}` and reads it back.
 *
 * The `type` of an event that declares an [Event.Key.name] is that name, resolved through
 * [registry]: a string naming the event rather than the class holding it, so the class can be
 * renamed or moved without any of the rows written under it going dark.
 *
 * An event that declares no name is written under the qualified name of its class and read back by
 * loading that class, which is what every payload of this library used to be. It keeps the rows of
 * an application that has not declared its names, or has declared only some of them, readable in
 * both directions, and it is why an empty registry is a valid one.
 *
 * The class name is also the only way to read a row written before its event declared a name:
 * declaring one protects the rows written after it, not those already stored. Those keep needing
 * their class where it is, until their `type` is migrated to the declared name.
 *
 * Take the instance from [EventRegistry.serializer] instead of building one, so everything that
 * reads and writes the events of an application resolves names through the same registry.
 */
class EventSerializer(private val registry: EventRegistry) : KSerializer<Event> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Event") {
        element<String>("type")
        element<JsonObject>("body")
    }

    override fun deserialize(decoder: Decoder): Event {
        val input = decoder as? JsonDecoder ?: throw SerializationException("This class can be loaded only by Json")
        val tree = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected JsonObject")
        val type = tree.getValue("type").jsonPrimitive.content
        val serializer = registry[type] ?: byClassName(type)
        return input.json.decodeFromJsonElement(serializer, tree.getValue("body").jsonObject)
    }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun serialize(encoder: Encoder, value: Event) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("This class can be saved only by Json")
        val type = if (value.key.hasDeclaredName()) value.key.name else value::class.qualifiedName!!
        val tree = JsonObject(
            mapOf(
                "type" to JsonPrimitive(type),
                "body" to output.json.encodeToJsonElement(
                    value::class.serializer() as KSerializer<Event>,
                    value
                )
            )
        )
        output.encodeJsonElement(tree)
    }

    /** The event of a payload written under the qualified name of its class. */
    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun byClassName(type: String): KSerializer<Event> = try {
        (Class.forName(type).kotlin as KClass<Event>).serializer()
    } catch (notFound: ClassNotFoundException) {
        throw SerializationException(
            "Event '$type' is neither registered in the EventRegistry nor a class of this " +
                "application. Register it under the name it was stored with, or restore the class.",
            notFound
        )
    }
}
