package com.turbomates.event.serializer

import com.turbomates.event.seriazlier.LocalDateTimeSerializer
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class LocalDateTimeSerializerTest {
    @Test
    fun serialize() {
        val date = LocalDateTime.now()
        val string = Json.encodeToString(LocalDateTimeSerializer, date)
        assertEquals(
            Json.encodeToString(
                String.serializer(),
                date.format(LocalDateTimeSerializer.utcDateTimeFormat).toString()
            ),
            string
        )
    }

    @Test
    fun deserialize() {
        val date = LocalDateTime.now()
        val string =
            Json.decodeFromString(String.serializer(), "\"${date.format(LocalDateTimeSerializer.utcDateTimeFormat)}\"")
        val expectedDateTime = LocalDateTime.parse(string, LocalDateTimeSerializer.utcDateTimeFormat)
        assertEquals(
            date.format(LocalDateTimeSerializer.utcDateTimeFormat),
            expectedDateTime.format(LocalDateTimeSerializer.utcDateTimeFormat)
        )
    }
}
