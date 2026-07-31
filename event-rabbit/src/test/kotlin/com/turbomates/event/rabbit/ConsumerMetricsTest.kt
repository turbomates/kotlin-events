package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.NoOpTelemetry
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.subscriber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.RabbitMQContainer

class ConsumerMetricsTest {
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
    fun `reports a handled delivery`() = runBlocking {
        val metrics = RecordingConsumerMetrics()
        val registry = SubscribersRegistry()
        val subscriber = subscriber("sportsbook.HandlingSubscriber") { }
        registry.registry(subscriber)
        val queue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = NoOpTelemetry(),
            metrics = metrics
        )
        queue.run(listOf(QueueConfig(subscriber.queueName("test"), maxRetries = 3, retryDelay = 1.seconds)))
        RabbitPublisher(Config(factory, "test", "test"), Json).publish(TestEvent("test"))

        withTimeout(TIMEOUT) {
            while (metrics.handled.isEmpty()) {
                delay(POLL)
            }
        }
        queue.close()

        val handled = metrics.handled.single()
        assertEquals(subscriber.queueName("test"), handled.queue)
        assertEquals(TestEvent.routeName(), handled.routingKey)
        // The wait for a free worker and the run of the subscriber are measured apart, so a queue
        // starved of workers does not read as a slow subscriber.
        assertTrue(handled.waited >= Duration.ZERO)
        assertTrue(handled.took >= Duration.ZERO)
        assertTrue(metrics.failed.isEmpty())
    }

    @Test
    fun `reports the retries and the parking lot of a failing subscriber`() = runBlocking {
        val metrics = RecordingConsumerMetrics()
        val registry = SubscribersRegistry()
        val subscriber = subscriber("sportsbook.FailingSubscriber") { throw IllegalStateException("test") }
        registry.registry(subscriber)
        val queue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = NoOpTelemetry(),
            metrics = metrics
        )
        queue.run(listOf(QueueConfig(subscriber.queueName("test"), maxRetries = 2, retryDelay = 1.seconds)))
        RabbitPublisher(Config(factory, "test", "test"), Json).publish(TestEvent("test"))

        withTimeout(TIMEOUT) {
            while (metrics.parked.isEmpty()) {
                delay(POLL)
            }
        }
        queue.close()

        // maxRetries = 2: the first two attempts go back through the retry queue, the third gives up
        // and the message is published to the parking lot.
        assertEquals(listOf(0L, 1L, 2L), metrics.failed.map { it.retries })
        assertEquals(listOf(0L, 1L), metrics.retried.map { it.retries })
        assertEquals(listOf(2L), metrics.parked.map { it.retries })
        assertTrue(metrics.failed.all { it.error is IllegalStateException })
        assertTrue(metrics.handled.isEmpty())
    }

    /**
     * An anonymous subscriber has no qualified name to derive a queue name from, so the name — and
     * with it the queue — is given here.
     */
    private fun subscriber(name: String, action: suspend (TestEvent) -> Unit) = object : EventsSubscriber {
        override fun name(): String = name
        override fun subscribers(): List<EventSubscriber<out Event>> = listOf(TestEvent.subscriber(action))
    }

    private class RecordingConsumerMetrics : ConsumerMetrics {
        val handled = CopyOnWriteArrayList<Handled>()
        val failed = CopyOnWriteArrayList<Failed>()
        val retried = CopyOnWriteArrayList<Outcome>()
        val parked = CopyOnWriteArrayList<Outcome>()

        override fun handled(queue: String, routingKey: String, waited: Duration, took: Duration) {
            handled.add(Handled(queue, routingKey, waited, took))
        }

        override fun failed(queue: String, routingKey: String, retries: Long, took: Duration, error: Throwable) {
            failed.add(Failed(queue, routingKey, retries, error))
        }

        override fun retried(queue: String, routingKey: String, retries: Long) {
            retried.add(Outcome(queue, routingKey, retries))
        }

        override fun parked(queue: String, routingKey: String, retries: Long) {
            parked.add(Outcome(queue, routingKey, retries))
        }

        class Handled(val queue: String, val routingKey: String, val waited: Duration, val took: Duration)
        class Failed(val queue: String, val routingKey: String, val retries: Long, val error: Throwable)
        class Outcome(val queue: String, val routingKey: String, val retries: Long)
    }

    private companion object {
        val POLL = 100.milliseconds
        val TIMEOUT = 60.seconds
    }
}
