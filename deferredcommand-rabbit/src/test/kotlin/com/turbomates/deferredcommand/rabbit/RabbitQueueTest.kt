package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.ConnectionFactory
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber
import com.turbomates.deferredcommand.SubscribersRegistry
import com.turbomates.deferredcommand.serializer.LocalDateTimeSerializer
import com.turbomates.deferredcommand.subscriber
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
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
        val command = RetryDeferredCommand("test")
        val registry = SubscribersRegistry()
        val subscriber = object : DeferredCommandsSubscriber {
            override fun name(): String {
                return "sportsbook.FeedSubscriber"
            }

            override fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>> {
                return listOf(RetryDeferredCommand.subscriber {
                    count++
                    throw Exception("test")
                })
            }

            var count = 0
        }
        registry.register(subscriber)
        val publisher = RabbitPublisher(Config(factory, "test", "test"), Json)
        val rabbitQueue = RabbitQueue(
            Config(factory, "test", "test"),
            Json,
            registry,
            scope = this,
            telemetryService = RecordingTelemetry()
        )
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

    @Test
    fun `trace headers are propagated to telemetry`() = runBlocking {
        val expectedTrace = TraceInformation("tp-value", "ts-value", "baggage-value")
        val command = TraceDeferredCommand("trace")
        val telemetry = RecordingTelemetry()
        val registry = SubscribersRegistry()
        val subscriber = object : DeferredCommandsSubscriber {
            var count = 0

            override fun name(): String {
                return "sportsbook.TraceDeferredCommandsSubscriber"
            }

            override fun subscribers(): List<DeferredCommandSubscriber<out DeferredCommand>> {
                return listOf(TraceDeferredCommand.subscriber {
                    count++
                })
            }
        }

        registry.register(subscriber)
        val publisher = RabbitPublisher(Config(factory, "trace-test", "trace-test"), Json)
        val rabbitQueue = RabbitQueue(
            Config(factory, "trace-test", "trace-test"),
            Json,
            registry,
            scope = this,
            telemetryService = telemetry
        )
        rabbitQueue.run(listOf(QueueConfig(subscriber.queueName("trace-test"), 1, retryDelay = 1.seconds)))

        publisher.publish(command, expectedTrace)

        withTimeout(30.seconds) {
            launch {
                while (isActive && subscriber.count < 1) {
                    delay(250)
                }
                while (isActive && telemetry.lastTraceInformation == null) {
                    delay(250)
                }
                cancel()
            }
        }

        assertEquals(1, subscriber.count)
        assertEquals(expectedTrace, telemetry.lastTraceInformation)
    }
}

private class RecordingTelemetry : Telemetry {
    var lastTraceInformation: TraceInformation? = null

    override fun traceInformation(): TraceInformation {
        return TraceInformation(null, null, null)
    }

    override suspend fun link(
        traceInformation: TraceInformation,
        spanName: String,
        attributes: Map<String, String>,
        block: suspend TraceInformation.() -> Unit
    ) {
        lastTraceInformation = traceInformation
        block.invoke(traceInformation)
    }
}

@Serializable
private data class RetryDeferredCommand(val name: String) : DeferredCommand() {
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<RetryDeferredCommand>
}

@Serializable
private data class TraceDeferredCommand(val name: String) : DeferredCommand() {
    @Serializable(with = LocalDateTimeSerializer::class)
    override val executeAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

    override val key: Key<out DeferredCommand>
        get() = Companion

    companion object : Key<TraceDeferredCommand>
}
