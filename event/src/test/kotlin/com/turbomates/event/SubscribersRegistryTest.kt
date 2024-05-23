package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscribersRegistryTest {
    @Test
    fun `registry events subscriber`() {
        val registry = SubscribersRegistry()
        val subscriber = TestEventsSubscriber()
        registry.registry(subscriber)
        assertEquals(1, registry.subscribers(TestEvent()).count())
    }

    @Test
    fun `registry event subscriber`() {
        val registry = SubscribersRegistry()
        val subscriber = TestEventSubscriber()
        registry.registry(subscriber)
        assertEquals(1, registry.subscribers(TestEvent()).count())
    }

    @Test
    fun `registry subscribers`() {
        val registry = SubscribersRegistry()
        val subscriber1 = TestEventSubscriber()
        val subscriber2 = TestEventsSubscriber()
        registry.registry(subscriber1)
        registry.registry(subscriber2)
        val subscribers = registry.subscribers()
        assertEquals(subscriber1, subscribers.eventSubscribers.first())
        assertEquals(subscriber2, subscribers.eventsSubscribers.first())
    }

    private class TestEventSubscriber : EventSubscriber<TestEvent>(TestEvent) {
        override suspend fun invoke(event: TestEvent) {
            TODO("Not yet implemented")
        }
    }

    private class TestEventsSubscriber : EventsSubscriber {
        override fun subscribers(): List<EventSubscriber<out Event>> {
            return listOf(TestEvent.subscriber {
            })
        }
    }


    private class TestEvent : Event() {
        override val key: Key<out Event> = Companion

        companion object : Key<TestEvent>
    }
}
