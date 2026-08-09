package com.turbomates.event.serializer

import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class EventSerializerTest {
    private val json = Json
    private val serializer = EventRegistry(NamedEvent).serializer

    @Test
    fun serialize() {
        val event = TestEvent(1, "test")
        assertEquals(buildJsonObject {
            put("type", TestEvent::class.qualifiedName)
            putJsonObject("body") {
                put("int", 1)
                put("string", "test")
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(serializer, event))
    }

    @Test
    fun deserialize() {
        val event = TestEvent(1, "test")
        val string = json.encodeToString(serializer, event)
        assertEquals(event, json.decodeFromString(serializer, string))
    }

    @Test
    fun `partition key stays out of the payload`() {
        val userId = UUID.randomUUID()
        val event = PartitionedEvent(userId)
        assertEquals(buildJsonObject {
            put("type", PartitionedEvent::class.qualifiedName)
            putJsonObject("body") {
                put("userId", userId.toString())
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
    fun `a row written before the name was declared is still read by its class`() {
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
}

@Serializable
private data class TestEvent(val int: Int, val string: String) : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<TestEvent>
}

@Serializable
private data class NamedEvent(val string: String) : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<NamedEvent> {
        override val name = "test.serializer.named"
    }
}

@Serializable
private data class PartitionedEvent(
    @Serializable(with = TestUUIDSerializer::class) val userId: UUID
) : Event() {
    override val key get() = Companion
    override val partitionKey get() = userId

    companion object : Key<PartitionedEvent>
}

private object TestUUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("uuid", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}
