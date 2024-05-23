package com.turbomates.event.serializer

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
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
}

@Serializable
private data class TestEvent(val int: Int, val string: String) : Event() {
    override val key: Key<out Event> = Companion

    companion object : Key<TestEvent>
}
