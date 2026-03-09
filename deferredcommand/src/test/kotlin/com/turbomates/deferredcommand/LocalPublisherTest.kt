package com.turbomates.deferredcommand

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class LocalPublisherTest {
    @Test
    fun test() {
        val registry = SubscribersRegistry()
        registry.register(TestDeferredCommandsSubscriber())
        val publisher = LocalPublisher(registry)
        runBlocking {
            publisher.publish(TestDeferredCommand())
        }
        assertEquals(1, counter)
    }

    private class TestDeferredCommandsSubscriber : DeferredCommandsSubscriber {
        override fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>> {
            return listOf(TestDeferredCommand.subscriber {
                counter++
            })
        }
    }

    private class TestDeferredCommand : DeferredCommand() {
        override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
        override val key: Key<out DeferredCommand> = Companion

        companion object : Key<TestDeferredCommand>
    }
}

private var counter = 0
