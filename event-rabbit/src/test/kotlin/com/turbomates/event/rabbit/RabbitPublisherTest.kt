package com.turbomates.event.rabbit

import com.turbomates.event.EventRegistry
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.ConnectionFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.RabbitMQContainer

class RabbitPublisherTest {
    private lateinit var container: RabbitMQContainer
    private lateinit var factory: ConnectionFactory

    @BeforeEach
    fun setUp() {
        container = RabbitMQContainer("rabbitmq:3")
        container.start()
        factory = connectionFactory()
    }

    @AfterEach
    fun tearDown() {
        container.stop()
    }

    @Test
    fun `publishes concurrently from a single publisher`() = runBlocking {
        val config = Config(factory, "publisher-test", "publisher-test")
        val received = consume(config.exchange, "publisher-test-queue", MESSAGES)
        val publisher = RabbitPublisher(config, EventRegistry())

        (1..MESSAGES)
            .map { number -> async(Dispatchers.IO) { publisher.publish(TestEvent("event-$number")) } }
            .awaitAll()
        assertTrue(received.latch.await(30, TimeUnit.SECONDS), "only ${received.bodies.size} of $MESSAGES delivered")

        publisher.close()
        assertEquals(MESSAGES, received.bodies.size)
    }

    @Test
    fun `publishes again after the connection was killed`() = runBlocking {
        // Automatic recovery of the amqp client is off, so the publisher itself has to notice the
        // dead connection and open a new one.
        val config = Config(connectionFactory().apply { isAutomaticRecoveryEnabled = false }, "kill-test", "kill-test")
        val received = consume(config.exchange, "kill-test-queue", 2)
        val publisher = RabbitPublisher(config, EventRegistry())

        publisher.publish(TestEvent("before"))
        withContext(Dispatchers.IO) { container.execInContainer("rabbitmqctl", "close_all_connections", "killed") }
        // The publish that runs into the dead connection is allowed to fail, the event stays in the
        // outbox then. What must not happen is a publisher broken for the rest of the process.
        runCatching { publisher.publish(TestEvent("during")) }
        publisher.publish(TestEvent("after"))

        assertTrue(received.latch.await(30, TimeUnit.SECONDS), "only ${received.bodies.size} of 2 delivered")
        publisher.close()
        assertTrue(received.bodies.any { it.contains("after") }, "the event published after the kill was lost")
    }

    @Test
    fun `fails when the publisher is closed`() = runBlocking {
        val publisher = RabbitPublisher(Config(factory, "closed-test", "closed-test"), EventRegistry())
        publisher.publish(TestEvent("open"))
        publisher.close()

        assertFailsWith<IllegalStateException> { publisher.publish(TestEvent("closed")) }
        Unit
    }

    private fun connectionFactory() = ConnectionFactory().apply {
        host = container.host
        port = container.amqpPort
        username = "guest"
        password = "guest"
    }

    private fun consume(exchange: String, queue: String, expected: Int): Received {
        val channel = connectionFactory().newConnection().createChannel()
        channel.exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
        channel.queueDeclare(queue, true, false, false, emptyMap())
        channel.queueBind(queue, exchange, "#")
        val received = Received(CountDownLatch(expected), ConcurrentLinkedQueue())
        channel.basicConsume(queue, true, { _, delivery ->
            received.bodies.add(String(delivery.body))
            received.latch.countDown()
        }, { _ -> })
        return received
    }

    private class Received(val latch: CountDownLatch, val bodies: ConcurrentLinkedQueue<String>)

    private companion object {
        const val MESSAGES = 50
    }
}
