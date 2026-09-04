package com.turbomates.event.serializer

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
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
 * The `type` is the [Event.Key.name] of the event, resolved back through [registry]: a string
 * naming the event rather than the class holding it, so a class that declares its name can be
 * renamed or moved without any of the rows written under it going dark. An event that declares
 * nothing carries the name derived from its class, which is no more stable than the class was —
 * what it is not is a second format.
 *
 * The `body` is written and read with the same entry of [registry], so an event registered with a
 * serializer of its own is stored in the shape that serializer gives it. An event the registry does
 * not hold is written under the serializer of its own class: the outbox writes whatever the
 * transaction raised, and what a published event is read by is the registry of another application.
 *
 * A `type` the registry does not hold is looked up as a class and read by loading it. That is how
 * every payload of this library used to be written, and it is the only way to read a row stored
 * before the name of its event was written to it: the class has to stay where it is until those
 * rows are gone, or their `type` migrated.
 *
 * Take the instance from [EventRegistry.serializer] instead of building one, so everything that
 * reads and writes the events of an application resolves names through the same registry. The
 * registry also registers it as the contextual serializer of [Event] in the json of its payloads,
 * which is what makes a `@Contextual` [Event] field of an event work without an application
 * building this class over a registry of its own.
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
        val name = value.key.name
        // The registry is one table, not a reading half: an event registered with a serializer of
        // its own is written with it too, or the rows would be written in one shape and read in
        // another. An event nothing registered is still written — under the serializer of its own
        // class, the way every payload of this library was — because the outbox writes whatever the
        // transaction raised, and the reader of a published event is another application anyway.
        val serializer = registry[name] ?: (value::class.serializer() as KSerializer<Event>)
        val tree = JsonObject(
            mapOf(
                "type" to JsonPrimitive(name),
                "body" to output.json.encodeToJsonElement(serializer, value)
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
