package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Serializable

class EventStoreTest {
    @Test
    fun `events are raised in the order they were added`() {
        val store = EventStore()
        val events = List(5) { TestEvent() }
        events.forEach { store.addEvent(it) }

        assertEquals(events, store.raiseEvents().toList())
    }

    @Test
    fun `raising drains the store`() {
        val store = EventStore()
        store.addEvent(TestEvent())
        store.raiseEvents().toList()

        assertEquals(emptyList(), store.raiseEvents().toList())
    }

    @Serializable
    internal class TestEvent : Event() {
        override val key: Key<out Event> = Companion

        companion object : Key<TestEvent>
    }
}
