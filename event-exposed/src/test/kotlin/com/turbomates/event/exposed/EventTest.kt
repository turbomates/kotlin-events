package com.turbomates.event.exposed

import com.turbomates.event.Event
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test


class Test {
    @Test
    fun `test`() {
        val event = PublicEvent(MyEvent1("test"))
        val test = Json { encodeDefaults = true }.encodeToJsonElement(PublicEvent.serializer(), event)
        println(test)
    }
}

@Serializable
class MyEvent1(val name: String) : Event() {
    override val key get() = Companion

    companion object : Event.Key<MyEvent1>
}


