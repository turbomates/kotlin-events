package com.turbomates.deferredcommand.serializer

import com.turbomates.deferredcommand.DeferredCommand
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DeferredCommandSerializerTest {
    @Test
    fun serialize() {
        val command = TestDeferredCommand(1, "test")
        val json = Json
        assertEquals(buildJsonObject {
            put("type", TestDeferredCommand::class.qualifiedName)
            putJsonObject("body") {
                put("int", 1)
                put("string", "test")
                put("executeAt", command.executeAt.format(LocalDateTimeSerializer.utcDateTimeFormat))
                put("timestamp", command.timestamp.format(LocalDateTimeSerializer.utcDateTimeFormat))
            }
        }, json.encodeToJsonElement(DeferredCommandSerializer, command))
    }

    @Test
    fun deserialize() {
        val command = TestDeferredCommand(1, "test")
        val json = Json
        val string = json.encodeToString(DeferredCommandSerializer, command)
        assertEquals(command, json.decodeFromString(DeferredCommandSerializer, string))
    }
}

@Serializable
private data class TestDeferredCommand(val int: Int, val string: String) : DeferredCommand() {
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    override val key: Key<out DeferredCommand> = Companion

    companion object : Key<TestDeferredCommand>
}
