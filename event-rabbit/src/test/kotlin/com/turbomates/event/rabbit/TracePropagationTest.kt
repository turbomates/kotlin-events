package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Delivery
import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import com.turbomates.event.subscriber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.RabbitMQContainer

/**
 * The trace a publisher is given has to reach the subscriber untouched: what `Telemetry.link` gets on
 * the consuming side is the [TraceInformation] handed to `publish`, field by field, and what sits in
 * the headers on the wire is what a W3C parser of any other consumer reads.
 */
class TracePropagationTest {
    private lateinit var container: RabbitMQContainer
    private lateinit var factory: ConnectionFactory

    @BeforeEach
    fun setUp() {
        container = RabbitMQContainer("rabbitmq:3")
        container.start()
        factory = ConnectionFactory().apply {
            host = container.host
            port = container.amqpPort
            username = "guest"
            password = "guest"
        }
    }

    @AfterEach
    fun tearDown() {
        container.stop()
    }

    @Test
    fun `the trace reaches the subscriber whole`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val handled = CompletableDeferred<Unit>()
        val subscriber = subscriber("sportsbook.WholeTrace", TestEvent.subscriber { handled.complete(Unit) })
        val queue = queueOf(subscriber, telemetry, this)
        queue.run()

        RabbitPublisher(config(), EventRegistry()).use { it.publish(TestEvent("traced"), TRACE) }
        val received = withTimeout(30.seconds) { handled.await(); telemetry.received.first() }
        queue.close()

        assertEquals(TRACE, received)
    }

    @Test
    fun `a null tracestate stays null and does not break the publish`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val handled = CompletableDeferred<Unit>()
        val subscriber = subscriber("sportsbook.NullState", TestEvent.subscriber { handled.complete(Unit) })
        val queue = queueOf(subscriber, telemetry, this)
        queue.run()
        val trace = TRACE.copy(tracestate = null)

        RabbitPublisher(config(), EventRegistry()).use { it.publish(TestEvent("traced"), trace) }
        val received = withTimeout(30.seconds) { handled.await(); telemetry.received.first() }
        queue.close()

        assertEquals(trace, received)
        assertNull(received.tracestate)
    }

    @Test
    fun `a publish without a trace links nothing and is handled`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val handled = CompletableDeferred<Unit>()
        val subscriber = subscriber("sportsbook.NoTrace", TestEvent.subscriber { handled.complete(Unit) })
        val queue = queueOf(subscriber, telemetry, this)
        queue.run()

        RabbitPublisher(config(), EventRegistry()).use { it.publish(TestEvent("untraced")) }
        val received = withTimeout(30.seconds) { handled.await(); telemetry.received.first() }
        queue.close()

        assertEquals(TraceInformation(null, null, null), received)
    }

    @Test
    fun `the headers on the wire are the w3c values`() = runBlocking {
        val config = config()
        val delivered = CompletableDeferred<Delivery>()
        factory.newConnection().use { connection ->
            val channel = connection.createChannel()
            channel.exchangeDeclare(config.exchange, BuiltinExchangeType.TOPIC, true)
            channel.queueDeclare("wire-test", true, false, false, emptyMap())
            channel.queueBind("wire-test", config.exchange, "#")
            channel.basicConsume("wire-test", true, { _, delivery -> delivered.complete(delivery) }, { _ -> })

            RabbitPublisher(config, EventRegistry()).use { it.publish(TestEvent("traced"), TRACE) }
            val headers = withTimeout(30.seconds) { delivered.await() }.properties.headers

            // toString() is what a consumer of any language does with these, so it is the contract.
            assertEquals(TRACE.traceparent, headers[QueueConfig.TRACEPARENT_HEADER].toString())
            assertEquals(TRACE.tracestate, headers[QueueConfig.TRACESTATE_HEADER].toString())
            assertEquals(TRACE.baggage, headers[QueueConfig.BAGGAGE_HEADER].toString())
            assertFalse(headers[QueueConfig.BAGGAGE_HEADER].toString().contains("TraceInformation("))
        }
    }

    @Test
    fun `the trace survives a retry`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val handled = CompletableDeferred<Unit>()
        var deliveries = 0
        val subscriber = subscriber("sportsbook.RetryTrace", TestEvent.subscriber {
            deliveries++
            if (deliveries == 1) {
                throw Exception("first delivery fails")
            }
            handled.complete(Unit)
        })
        val queue = queueOf(subscriber, telemetry, this)
        queue.run(listOf(QueueConfig(subscriber.queueName(PREFIX), maxRetries = 3, retryDelay = 1.seconds)))

        RabbitPublisher(config(), EventRegistry()).use { it.publish(TestEvent("retried"), TRACE) }
        withTimeout(60.seconds) { handled.await() }
        queue.close()

        assertEquals(2, telemetry.received.size)
        assertEquals(listOf(TRACE, TRACE), telemetry.received.toList())
    }

    @Test
    fun `the trace survives the parking lot`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val subscriber = subscriber("sportsbook.ParkedTrace", TestEvent.subscriber { throw Exception("always fails") })
        val queue = queueOf(subscriber, telemetry, this)
        val queueName = subscriber.queueName(PREFIX)
        queue.run(listOf(QueueConfig(queueName, maxRetries = 1, retryDelay = 1.seconds)))
        val parked = CompletableDeferred<Delivery>()
        val connection = factory.newConnection()
        connection.createChannel()
            .basicConsume(queueName.pl(), true, { _, delivery -> parked.complete(delivery) }, { _ -> })

        RabbitPublisher(config(), EventRegistry()).use { it.publish(TestEvent("parked"), TRACE) }
        val headers = withTimeout(60.seconds) { parked.await() }.properties.headers
        queue.close()
        connection.close()

        assertEquals(TRACE.traceparent, headers.stringHeader(QueueConfig.TRACEPARENT_HEADER))
        assertEquals(TRACE.tracestate, headers.stringHeader(QueueConfig.TRACESTATE_HEADER))
        assertEquals(TRACE.baggage, headers.stringHeader(QueueConfig.BAGGAGE_HEADER))
        assertEquals("always fails", headers.stringHeader("x-exception-message"))
    }

    private fun config() = Config(factory, PREFIX, "trace-test")

    private fun queueOf(subscriber: EventsSubscriber, telemetry: Telemetry, scope: CoroutineScope) = RabbitQueue(
        config(),
        EventRegistry(),
        SubscribersRegistry().also { it.registry(subscriber) },
        scope = scope,
        telemetryService = telemetry
    )

    private fun subscriber(name: String, vararg subscribers: EventSubscriber<out Event>): EventsSubscriber =
        object : EventsSubscriber {
            override fun name(): String = name
            override fun subscribers(): List<EventSubscriber<out Event>> = subscribers.toList()
        }

    /** Records what the consumer hands to `link` and runs the subscriber, no span involved. */
    private class RecordingTelemetry : Telemetry {
        val received = CopyOnWriteArrayList<TraceInformation>()

        override fun traceInformation() = TraceInformation(null, null, null)

        override suspend fun link(
            traceInformation: TraceInformation,
            spanName: String,
            attributes: Map<String, String>,
            block: suspend TraceInformation.() -> Unit
        ) {
            received.add(traceInformation)
            block(traceInformation)
        }
    }

    private companion object {
        const val PREFIX = "trace-test"
        val TRACE = TraceInformation(
            traceparent = "00-9b023cb5d867194bf771f7eaff3ab185-b7ad6b7169203331-01",
            tracestate = "congo=t61rcWkgMzE",
            baggage = "betforge.reference.type=bet,betforge.reference.id=2ee710ae-cd4a-425d-a0a3-e34f72b0f265",
        )
    }
}
