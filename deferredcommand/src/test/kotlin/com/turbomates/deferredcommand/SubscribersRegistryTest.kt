package com.turbomates.deferredcommand

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscribersRegistryTest {
    @Test
    fun `register deferred commands subscriber`() {
        val registry = SubscribersRegistry()
        val subscriber = TestDeferredCommandsSubscriber()
        registry.register(subscriber)
        assertEquals(1, registry.subscribers(TestDeferredCommand()).count())
    }

    @Test
    fun `register deferred command subscriber`() {
        val registry = SubscribersRegistry()
        val subscriber = TestDeferredCommandSubscriber()
        registry.register(subscriber)
        assertEquals(1, registry.subscribers(TestDeferredCommand()).count())
    }

    @Test
    fun `register subscribers`() {
        val registry = SubscribersRegistry()
        val subscriber1 = TestDeferredCommandSubscriber()
        val subscriber2 = TestDeferredCommandsSubscriber()
        registry.register(subscriber1)
        registry.register(subscriber2)
        val subscribers = registry.subscribers()
        assertEquals(subscriber1, subscribers.deferredCommandSubscribers.first())
        assertEquals(subscriber2, subscribers.deferredCommandsSubscribers.first())
    }

    private class TestDeferredCommandSubscriber : DeferredCommandSubscriber<TestDeferredCommand>(TestDeferredCommand) {
        override suspend fun invoke(command: TestDeferredCommand) {
            TODO("Not yet implemented")
        }
    }

    private class TestDeferredCommandsSubscriber : DeferredCommandsSubscriber {
        override fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>> {
            return listOf(TestDeferredCommand.subscriber {
            })
        }
    }

    private class TestDeferredCommand : DeferredCommand() {
        override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
        override val key: Key<out DeferredCommand> = Companion

        companion object : Key<TestDeferredCommand>
    }
}
