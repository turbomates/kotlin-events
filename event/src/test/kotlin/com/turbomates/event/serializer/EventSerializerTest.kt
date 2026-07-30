package com.turbomates.event.serializer

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
    @Test
    fun serialize() {
        val event = TestEvent(1, "test")
        val json = Json
        assertEquals(buildJsonObject {
            put("type", TestEvent::class.qualifiedName)
            putJsonObject("body") {
                put("int", 1)
                put("string", "test")
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(EventSerializer, event))
    }

    @Test
    fun deserialize() {
        val event = TestEvent(1, "test")
        val json = Json
        val string = json.encodeToString(EventSerializer, event)
        assertEquals(event, json.decodeFromString(EventSerializer, string))
    }

    @Test
    fun `partition key stays out of the payload`() {
        val userId = UUID.randomUUID()
        val event = PartitionedEvent(userId)
        val json = Json
        assertEquals(buildJsonObject {
            put("type", PartitionedEvent::class.qualifiedName)
            putJsonObject("body") {
                put("userId", userId.toString())
                put("timestamp", event.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(EventSerializer, event))
        assertEquals(userId, event.partitionKey)
    }

    @Test
    fun `partition key survives deserialization`() {
        val userId = UUID.randomUUID()
        val json = Json
        val string = json.encodeToString(EventSerializer, PartitionedEvent(userId))
        assertEquals(userId, json.decodeFromString(EventSerializer, string).partitionKey)
    }

    @Test
    fun `event without a partition key`() {
        assertNull(TestEvent(1, "test").partitionKey)
    }
}

@Serializable
private data class TestEvent(val int: Int, val string: String) : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<TestEvent>
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
