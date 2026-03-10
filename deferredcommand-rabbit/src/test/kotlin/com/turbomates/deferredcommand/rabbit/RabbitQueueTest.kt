package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.ConnectionFactory
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber
import com.turbomates.deferredcommand.SubscribersRegistry
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import com.turbomates.deferredcommand.subscriber
import java.time.LocalDateTime
import java.time.ZoneOffset
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
        val command = TestDeferredCommand("test")
        val registry = SubscribersRegistry()
        val subscriber = object : DeferredCommandsSubscriber {
            override fun name(): String {
                return "sportsbook.FeedSubscriber"
            }

            override fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>> {
                return listOf(TestDeferredCommand.subscriber {
                    count++
                    throw Exception("test")
                })
            }

            var count = 0
        }
        registry.register(subscriber)
        val publisher = RabbitPublisher(Config(factory, "test", "test"), Json)
        val rabbitQueue = RabbitQueue(Config(factory, "test", "test"), Json, registry)
        rabbitQueue.run(listOf(QueueConfig(subscriber.queueName("test"), 3, retryDelay = 1.seconds)))
        publisher.publish(command)
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
data class TestDeferredCommand(val name: String) : DeferredCommand() {
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<TestDeferredCommand>
}
