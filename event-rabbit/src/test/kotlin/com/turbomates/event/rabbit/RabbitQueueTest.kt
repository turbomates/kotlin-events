package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.NoOpTelemetry
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.subscriber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
            override fun name(): String {
                return "sportsbook.FeedSubscriber"
            }

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
        val rabbitQueue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = NoOpTelemetry()
        )
        rabbitQueue.run(listOf(QueueConfig(subscriber.queueName("test"), 3, retryDelay = 1.seconds)))
        publisher.publish(event)
        withTimeout(60.seconds) {
            launch {
                while (isActive && subscriber.count < 4) {
                    delay(500)
                }
                cancel()
            }
        }
        rabbitQueue.close()

        assertEquals(4, subscriber.count)
    }

    @Test
    fun `requeue without retries is paced by retryDelay`() = runBlocking {
        val registry = SubscribersRegistry()
        val subscriber = object : EventsSubscriber {
            override fun name(): String {
                return "sportsbook.RequeueSubscriber"
            }

            override fun subscribers(): List<EventSubscriber<out Event>> {
                return listOf(TestEvent.subscriber {
                    count++
                    throw Exception("test")
                })
            }

            var count = 0
        }
        registry.registry(subscriber)
        val publisher = RabbitPublisher(Config(factory, "test", "test"), Json)
        val rabbitQueue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = NoOpTelemetry()
        )
        rabbitQueue.run(listOf(QueueConfig(subscriber.queueName("test"), maxRetries = 0, retryDelay = 1.seconds)))
        publisher.publish(TestEvent("test"))
        delay(5.seconds)
        rabbitQueue.close()

        // The nack requeues, so the message keeps coming back — but a worker holds it for
        // retryDelay before each nack. Without the pause the loop is hot and the count would be
        // in the thousands after five seconds.
        assertTrue(subscriber.count >= 2, "message was not redelivered, count=${subscriber.count}")
        assertTrue(subscriber.count <= 10, "requeue loop is not paced, count=${subscriber.count}")
    }

    @Test
    fun `consumer is recreated after the broker cancels it`() = runBlocking {
        val registry = SubscribersRegistry()
        val subscriber = object : EventsSubscriber {
            override fun name(): String {
                return "sportsbook.RecreateSubscriber"
            }

            override fun subscribers(): List<EventSubscriber<out Event>> {
                return listOf(TestEvent.subscriber {
                    count++
                })
            }

            var count = 0
        }
        registry.registry(subscriber)
        val publisher = RabbitPublisher(Config(factory, "test", "test"), Json)
        val rabbitQueue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = NoOpTelemetry()
        )
        val queueName = subscriber.queueName("test")
        rabbitQueue.run(listOf(QueueConfig(queueName, maxRetries = 3, retryDelay = 1.seconds)))
        publisher.publish(TestEvent("first"))
        withTimeout(30.seconds) {
            while (subscriber.count < 1) {
                delay(100)
            }
        }

        // Deleting the queue makes the broker cancel the consumer.
        factory.newConnection().use { connection ->
            connection.createChannel().use { it.queueDelete(queueName) }
        }

        // The recreated consumer declares the queue and its bindings again, but a publish that
        // happens before it does is dropped by the exchange — so keep publishing until one lands.
        withTimeout(60.seconds) {
            while (subscriber.count < 2) {
                publisher.publish(TestEvent("second"))
                delay(500)
            }
        }
        rabbitQueue.close()

        assertTrue(subscriber.count >= 2)
    }
}

@Serializable
data class TestEvent(val name: String) : Event() {
    override val key: Key<out Event>
        get() = TestEvent

    companion object : Key<TestEvent>
}

