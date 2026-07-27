package com.turbomates.event.exposed

import com.turbomates.event.Event
import com.turbomates.event.seriazlier.EventSerializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

class EventSerializationTest {
    @AfterTest
    fun restoreDefaults() {
        EventSerialization.reset()
    }

    @Test
    fun `defaults keep the format the tables were written with`() {
        EventSerialization.reset()

        assertSame(EventSerializer, EventSerialization.serializer())
        assertEquals(true, EventSerialization.json().configuration.ignoreUnknownKeys)
        assertEquals(true, EventSerialization.json().configuration.encodeDefaults)
        assertEquals(false, EventSerialization.json().configuration.prettyPrint)
    }

    @Test
    fun `a custom format is handed to the columns`() {
        EventSerialization.reset()
        val module = SerializersModule { }
        val json = Json(from = EventSerialization.DEFAULT) { serializersModule = module }

        EventSerialization.configure(json)

        assertSame(json, EventSerialization.json())
        assertSame(module, EventSerialization.json().serializersModule)
        assertEquals(true, EventSerialization.json().configuration.ignoreUnknownKeys)
    }

    @Test
    fun `a custom event serializer is handed to the columns`() {
        EventSerialization.reset()

        EventSerialization.configure(serializer = PrefixedEventSerializer)

        assertSame(PrefixedEventSerializer, EventSerialization.serializer())
    }

    @Test
    fun `configuring after the columns bound their format fails`() {
        EventSerialization.reset()
        EventSerialization.json()

        assertFailsWith<EventSerializationInUseException> {
            EventSerialization.configure(Json)
        }
    }

    private object PrefixedEventSerializer : kotlinx.serialization.KSerializer<Event> by EventSerializer
}
