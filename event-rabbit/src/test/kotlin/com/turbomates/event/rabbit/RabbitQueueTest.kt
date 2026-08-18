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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.RabbitMQContainer

class RabbitQueueTest {
    private lateinit var container: RabbitMQContainer
    private lateinit var factory: ConnectionFactory

    @BeforeEach
    fun setUp() {
        // The management plugin image: the bindings of a queue are readable over HTTP only.
        container = RabbitMQContainer("rabbitmq:3-management")
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
        // The measurement window opens at the first delivery, not at the publish — a slow
        // container start must not eat into it.
        withTimeout(30.seconds) {
            while (subscriber.count < 1) {
                delay(100)
            }
        }
        val delivered = subscriber.count
        delay(5.seconds)
        rabbitQueue.close()
        val redeliveries = subscriber.count - delivered

        // The nack requeues, so the message keeps coming back — but a worker holds it for
        // retryDelay before each nack. Without the pause the loop is hot and the count would be
        // in the thousands after five seconds.
        assertTrue(redeliveries >= 1, "message was not redelivered, count=${subscriber.count}")
        assertTrue(redeliveries <= 10, "requeue loop is not paced, count=${subscriber.count}")
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

    @Test
    fun `route of a dropped subscription is unbound`() = runBlocking {
        val config = Config(factory, "test", "test")
        val management = ManagementApi.of(factory, container.httpPort)
        val before = subscriber(listOf(TestEvent.subscriber { }, AnotherTestEvent.subscriber { }))
        val queue = before.queueName(config.queuePrefix)
        queueOf(config, before, this).run { run(); close() }

        assertEquals(
            setOf(TestEvent.routeName(), AnotherTestEvent.routeName()),
            management.of(queue, config.exchange)
        )

        // Same subscriber, one event less: the route of the event it dropped is stale now.
        val after = subscriber(listOf(TestEvent.subscriber { }))
        queueOf(config, after, this, boundRoutes = management).run { run(); close() }

        assertEquals(setOf(TestEvent.routeName()), management.of(queue, config.exchange))
        // The retry machinery binds the same queue to the dlx exchange, that one is none of the
        // sync's business.
        assertEquals(setOf(queue), management.of(queue, config.exchange.dlx()))
    }

    @Test
    fun `bindings are left alone without bound routes`() = runBlocking {
        val config = Config(factory, "test", "test")
        val before = subscriber(listOf(TestEvent.subscriber { }, AnotherTestEvent.subscriber { }))
        val queue = before.queueName(config.queuePrefix)
        queueOf(config, before, this).run { run(); close() }

        val after = subscriber(listOf(TestEvent.subscriber { }))
        queueOf(config, after, this).run { run(); close() }

        assertEquals(
            setOf(TestEvent.routeName(), AnotherTestEvent.routeName()),
            ManagementApi.of(factory, container.httpPort).of(queue, config.exchange)
        )
    }

    @Test
    fun `routes of a queue shared by two subscribers survive each other`() = runBlocking {
        val config = Config(factory, "test", "test")
        val management = ManagementApi.of(factory, container.httpPort)
        // The same name(), so the same queue: its routes are the union of both subscribers, and
        // neither may take the routes of the other for stale ones.
        val first = subscriber(listOf(TestEvent.subscriber { }))
        val second = subscriber(listOf(AnotherTestEvent.subscriber { }))
        val queue = first.queueName(config.queuePrefix)
        RabbitQueue(
            config,
            Json,
            SubscribersRegistry().also { it.registry(first); it.registry(second) },
            scope = this,
            telemetryService = NoOpTelemetry(),
            boundRoutes = management
        ).run { run(); close() }

        assertEquals(
            setOf(TestEvent.routeName(), AnotherTestEvent.routeName()),
            management.of(queue, config.exchange)
        )
    }

    @Test
    fun `a declared name is bound together with the route it replaced`() = runBlocking {
        val config = Config(factory, "test", "test")
        val management = ManagementApi.of(factory, container.httpPort)
        val subscriber = subscriber(listOf(NamedTestEvent.subscriber { }))
        val queue = subscriber.queueName(config.queuePrefix)
        queueOf(config, subscriber, this).run { run(); close() }

        assertEquals(
            setOfNotNull(NamedTestEvent.routeName(), NamedTestEvent.legacyRouteName()),
            management.of(queue, config.exchange)
        )
        assertEquals("rabbit.test.named", NamedTestEvent.routeName())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `the route a declared name replaced is unbound once the legacy binding is turned off`() = runBlocking {
        val config = Config(factory, "test", "test")
        val management = ManagementApi.of(factory, container.httpPort)
        val subscriber = subscriber(listOf(NamedTestEvent.subscriber { }))
        val queue = subscriber.queueName(config.queuePrefix)
        queueOf(config, subscriber, this).run { run(); close() }

        val migrated = config.copy(bindLegacyRoutes = false)
        queueOf(migrated, subscriber, this, boundRoutes = management).run { run(); close() }

        assertEquals(setOf(NamedTestEvent.routeName()), management.of(queue, config.exchange))
    }

    @Test
    fun `an event of a declared name reaches the subscriber that registered itself`() = runBlocking {
        val config = Config(factory, "test", "test")
        var received: Event? = null
        val subscriber = subscriber(listOf(NamedTestEvent.subscriber { received = it }))
        // Two registries that never met: the consumer registers the keys of its own subscribers, and
        // the publisher needs none — it takes the name off the event it is given.
        val rabbitQueue = queueOf(config, subscriber, this)
        rabbitQueue.run()
        RabbitPublisher(config, Json).use { it.publish(NamedTestEvent("test")) }
        withTimeout(60.seconds) {
            while (isActive && received == null) {
                delay(100)
            }
        }
        rabbitQueue.close()

        assertEquals(NamedTestEvent("test"), received)
    }

    private fun queueOf(
        config: Config,
        subscriber: EventsSubscriber,
        scope: CoroutineScope,
        boundRoutes: BoundRoutes? = null
    ): RabbitQueue = RabbitQueue(
        config,
        Json,
        SubscribersRegistry().also { it.registry(subscriber) },
        scope = scope,
        telemetryService = NoOpTelemetry(),
        boundRoutes = boundRoutes
    )

    private fun subscriber(subscribers: List<EventSubscriber<out Event>>): EventsSubscriber =
        object : EventsSubscriber {
            override fun name(): String = "sportsbook.SyncSubscriber"
            override fun subscribers(): List<EventSubscriber<out Event>> = subscribers
        }
}

@Serializable
data class TestEvent(val name: String) : Event() {
    override val key: Key<out Event>
        get() = TestEvent

    companion object : Key<TestEvent>
}

@Serializable
data class AnotherTestEvent(val name: String) : Event() {
    override val key: Key<out Event>
        get() = AnotherTestEvent

    companion object : Key<AnotherTestEvent>
}

@Serializable
data class NamedTestEvent(val value: String) : Event() {
    override val key: Key<out Event>
        get() = NamedTestEvent

    companion object : Key<NamedTestEvent> {
        override val name = "rabbit.test.named"
    }
}

