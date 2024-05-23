package com.turbomates.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class LocalPublisherTest {
    @Test
    fun test() {
        val registry = SubscribersRegistry()
        registry.registry(TestEventsSubscriber())
        val publisher = LocalPublisher(registry)
        runBlocking {
            publisher.publish(TestEvent())
        }
        assertEquals(1, counter)
    }

    private class TestEventsSubscriber : EventsSubscriber {
        override fun subscribers(): List<EventSubscriber<out Event>> {
            return listOf(TestEvent.subscriber {
                counter++
            })
        }
    }


    private class TestEvent : Event() {
        override val key: Key<out Event> = Companion

        companion object : Key<TestEvent>
    }
}

private var counter = 0
