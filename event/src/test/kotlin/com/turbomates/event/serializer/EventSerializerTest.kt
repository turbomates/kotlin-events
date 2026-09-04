package com.turbomates.event.serializer

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.modules.SerializersModule

class EventSerializerTest {
    private val json = Json
    private val registry = EventRegistry(NamedEvent, TestEvent, PartitionedEvent, WrappingEvent)
    private val serializer = registry.serializer

    @Test
    fun serialize() {
        val event = TestEvent(1, "test")
        assertEquals(buildJsonObject {
            put("type", "event.serializer.test_event")
            putJsonObject("body") {
                put("int", 1)
                put("string", "test")
                put("eventId", event.eventId.toString())
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(serializer, event))
    }

    @Test
    fun deserialize() {
        val event = TestEvent(1, "test")
        val string = json.encodeToString(serializer, event)
        val read = json.decodeFromString(serializer, string)
        assertEquals(event, read)
        assertEquals(event.eventId, read.eventId, "the consumer reads the id the producer wrote")
    }

    @Test
    fun `event id is a uuid v7 of its own`() {
        val one = TestEvent(1, "test")
        val other = TestEvent(1, "test")
        assertEquals(7, one.eventId.version())
        assertNotEquals(one.eventId, other.eventId)
    }

    @Test
    fun `partition key stays out of the payload`() {
        val userId = UUID.randomUUID()
        val event = PartitionedEvent(userId)
        assertEquals(buildJsonObject {
            put("type", "event.serializer.partitioned_event")
            putJsonObject("body") {
                put("userId", userId.toString())
                put("eventId", event.eventId.toString())
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(serializer, event))
        assertEquals(userId, event.partitionKey)
    }

    @Test
    fun `partition key survives deserialization`() {
        val userId = UUID.randomUUID()
        val string = json.encodeToString(serializer, PartitionedEvent(userId))
        assertEquals(userId, json.decodeFromString(serializer, string).partitionKey)
    }

    @Test
    fun `event without a partition key`() {
        assertNull(TestEvent(1, "test").partitionKey)
    }

    @Test
    fun `a declared name is written instead of the class`() {
        val event = NamedEvent("test")
        assertEquals(buildJsonObject {
            put("type", "test.serializer.named")
            putJsonObject("body") {
                put("string", "test")
                put("eventId", event.eventId.toString())
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(serializer, event))
    }

    @Test
    fun `a declared name is read back through the registry`() {
        val event = NamedEvent("test")
        assertEquals(event, json.decodeFromString(serializer, json.encodeToString(serializer, event)))
    }

    @Test
    fun `a declared name nothing registered cannot be read`() {
        val payload = json.encodeToString(serializer, NamedEvent("test"))
        val failure = assertFailsWith<SerializationException> {
            json.decodeFromString(EventRegistry().serializer, payload)
        }
        assertEquals(true, failure.message?.contains("test.serializer.named"))
    }

    @Test
    fun `a name is written even by a registry that does not hold it`() {
        // Publishing needs only the name: the reader is another application with a registry of its
        // own. Requiring the entry here would fail the publisher of an event nobody local consumes.
        val payload = json.encodeToString(EventRegistry().serializer, NamedEvent("test"))
        assertEquals(true, payload.contains("\"test.serializer.named\""))
    }

    @Test
    fun `a derived name is written and read back the same way a declared one is`() {
        val event = TestEvent(1, "test")
        assertEquals(event, json.decodeFromString(serializer, json.encodeToString(serializer, event)))
    }

    @Test
    fun `a row written before the name reached the payload is still read by its class`() {
        val event = NamedEvent("test")
        val stored = buildJsonObject {
            put("type", NamedEvent::class.qualifiedName)
            putJsonObject("body") {
                put("string", "test")
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }
        assertEquals(event, json.decodeFromJsonElement(serializer, stored))
    }

    @Test
    fun `an event carrying another event writes it as a type and a body of its own`() {
        val payload = json.parseToJsonElement(registry.encode(WrappingEvent(NamedEvent("inner")))).jsonObject
        assertEquals("test.serializer.wrapping", payload.getValue("type").jsonPrimitive.content)
        val nested = payload.getValue("body").jsonObject.getValue("originalEvent").jsonObject
        assertEquals("test.serializer.named", nested.getValue("type").jsonPrimitive.content)
        assertEquals("inner", nested.getValue("body").jsonObject.getValue("string").jsonPrimitive.content)
    }

    @Test
    fun `an event carrying another event is read back through the registry`() {
        val event = WrappingEvent(NamedEvent("inner"))
        assertEquals(event, registry.decode(registry.encode(event)))
    }

    @Test
    fun `a contextual Event of the application is left where it is`() {
        val events = EventRegistry(
            NamedEvent,
            WrappingEvent,
            json = Json(from = EventRegistry.DEFAULT_JSON) {
                serializersModule = SerializersModule { contextual(Event::class, StringEventSerializer) }
            }
        )
        assertEquals(true, events.encode(WrappingEvent(NamedEvent("inner"))).contains("\"originalEvent\":\"inner\""))
    }
}

@Serializable
internal data class TestEvent(val int: Int, val string: String) : Event() {
    override val key get() = Companion

    companion object : Key<TestEvent>
}

@Serializable
internal data class NamedEvent(val string: String) : Event() {
    override val key get() = Companion

    companion object : Key<NamedEvent> {
        override val name = "test.serializer.named"
    }
}

@Serializable
internal data class PartitionedEvent(
    @Serializable(with = TestUUIDSerializer::class) val userId: UUID
) : Event() {
    override val key get() = Companion
    override val partitionKey get() = userId

    companion object : Key<PartitionedEvent>
}

/** An event carrying another one, the field the contextual [Event] of the registry is there for. */
@Serializable
internal data class WrappingEvent(@Contextual val originalEvent: Event) : Event() {
    override val key get() = Companion

    companion object : Key<WrappingEvent> {
        override val name = "test.serializer.wrapping"
    }
}

/** A contextual [Event] of an application, to check the registry does not take the slot from it. */
private object StringEventSerializer : KSerializer<Event> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("own.event", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Event = NamedEvent(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Event) = encoder.encodeString((value as NamedEvent).string)
}

private object TestUUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("uuid", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}
