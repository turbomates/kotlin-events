package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.subscriber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.RabbitMQContainer

class RabbitQueueTest {
    private lateinit var factory: ConnectionFactory

    @BeforeEach
    fun setUp() {
        val underTest = RabbitMQContainer("rabbitmq:3")
        underTest.start()
        factory = ConnectionFactory().apply {
            host = underTest.host
            port = underTest.amqpPort
            username = "guest"
            password = "guest"
        }

    }

    @Test
    fun `check retry`() = runBlocking {
        val event = TestEvent("test")
        val registry = SubscribersRegistry()
        val subscriber = object : EventsSubscriber {
            override fun subscribers(): List<EventSubscriber<out Event>> {
                return listOf(TestEvent.subscriber {
                    count++
                    throw Exception("test")
                })
            }

            var count = 0;
        }
        registry.registry(subscriber)
        val publisher = RabbitPublisher(Config(factory, "test", "test"), Json)
        val rabbitQueue = RabbitQueue(Config(factory, "test", "test"), Json, registry)
        rabbitQueue.run(listOf(QueueConfig(subscriber::class.queueName("test"), 3, retryDelay = 1.seconds)))
        publisher.publish(event)
        withTimeout(60.seconds) {
            launch {
                while (isActive && subscriber.count < 4) {
                    delay(500)
                }
                cancel()
            }
        }

        assertEquals(4, subscriber.count)
    }

}

@Serializable
data class TestEvent(val name: String) : Event() {
    override val key: Key<out Event>
        get() = TestEvent

    companion object : Key<TestEvent>
}

