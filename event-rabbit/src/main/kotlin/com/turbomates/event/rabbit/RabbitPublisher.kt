package com.turbomates.event.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
import com.turbomates.event.seriazlier.EventSerializer
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Publishes events to the topic exchange of [Config.exchange] and returns only after the broker has
 * confirmed the message.
 *
 * The caller of this publisher is usually the outbox, which commits the deletion of the row as soon
 * as `publish` returns: a fire and forget `basicPublish` hands the frame to a socket buffer, so a
 * connection lost right after it loses the event with the row already gone. The channel therefore
 * runs in confirm mode and `publish` waits for the ack, a nack or a timeout throws and leaves the
 * deletion to be rolled back, so the event is published again on the next sweep.
 *
 * One channel serves every publish and a mutex keeps them one at a time. A channel is not thread safe
 * to begin with, and a confirm belongs to the channel rather than to a message: `waitForConfirms`
 * returns when everything unconfirmed on the channel is acked, so two publishes sharing it would wait
 * for each other and a nack of one would fail the other, which for the outbox means republishing an
 * event the broker already took. Serialized publishes keep the answer unambiguous, at the price of a
 * confirm round trip per event and per process.
 *
 * Connection and channel are opened on demand and dropped whenever a publish fails, so a broker
 * restart or a killed connection costs the failed event a redelivery instead of breaking the
 * publisher for the rest of the process lifetime.
 *
 * @param confirmTimeout how long to wait for the broker confirm before failing the publish.
 * @param errorHandler called with the event and the failure whenever a publish does not reach the
 * broker — a dead connection, a nack, a confirm that timed out. It is the counterpart of the
 * `errorHandler` of [RabbitQueue] on the publishing side, and like it, it only observes: the failure
 * is rethrown either way, which is what keeps the event in the outbox. A handler that throws is
 * logged and ignored, it never replaces the error the caller has to see.
 */
class RabbitPublisher(
    private val config: Config,
    private val json: Json,
    private val confirmTimeout: Duration = 30.seconds,
    private val buildProperties: AMQP.BasicProperties.Builder.() -> Unit = {},
    private val errorHandler: (Event, Throwable) -> Unit = { _, _ -> }
) : Publisher, AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val publishMutex = Mutex()
    private val lifecycleLock = Any()
    private var connection: Connection? = null
    private var channel: Channel? = null

    @Volatile
    private var closed = false

    override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
        val propsBuilder = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2) // 2 = persistent
            .apply(buildProperties)

        traceInformation?.let { trace ->
            propsBuilder.headers(
                mapOf(
                    QueueConfig.TRACEPARENT_HEADER to trace.traceparent,
                    QueueConfig.TRACESTATE_HEADER to trace.tracestate,
                    QueueConfig.BAGGAGE_HEADER to trace.toString()
                )
            )
        }
        val properties = propsBuilder.build()
        val body = json.encodeToString(EventSerializer, event).toByteArray()
        val routingKey = event.key.routeName()
        publishMutex.withLock {
            // Everything below blocks: the publish itself and the wait for the confirm.
            withContext(Dispatchers.IO) {
                val channel = channel()
                try {
                    channel.basicPublish(config.exchange, routingKey, properties, body)
                    // Throws on nack and on timeout, both leave the event in the outbox.
                    channel.waitForConfirmsOrDie(confirmTimeout.inWholeMilliseconds)
                } catch (expected: Throwable) {
                    // The channel is closed by a nack and unusable after a dead connection, drop it
                    // and let the next publish open a new one.
                    discardChannel()
                    report(event, expected)
                    throw expected
                }
            }
        }
    }

    /**
     * Closes the channel and the connection. The publisher is unusable afterwards, a publish on a
     * closed publisher throws instead of silently opening a new connection.
     */
    override fun close() {
        closed = true
        synchronized(lifecycleLock) {
            channel?.let { runCatching { it.close() } }
            channel = null
            connection?.let { runCatching { it.close() } }
            connection = null
        }
    }

    /**
     * The caller of this publisher is the outbox, and what it does with the failure — keep the row,
     * count the attempt, hold the stream back — is decided by the exception below, not here. A
     * handler of the application that throws must therefore not replace it: it is logged and the
     * original failure goes on to the caller.
     */
    private fun report(event: Event, error: Throwable) {
        // A cancelled publish is a stopped worker, not a broker that refused the event.
        if (error is CancellationException) {
            return
        }
        try {
            errorHandler(event, error)
        } catch (ignore: Throwable) {
            logger.error("rabbit publisher error handler threw", ignore)
        }
    }

    private fun channel(): Channel = synchronized(lifecycleLock) {
        // close() flips the flag before it takes this lock, so no publish racing with it can leave a
        // connection open behind the close.
        check(!closed) { "rabbit publisher of exchange ${config.exchange} is closed" }
        channel?.takeIf { it.isOpen } ?: createChannel().also { channel = it }
    }

    private fun createChannel(): Channel {
        val channel = connection().createChannel()
            ?: throw IOException("rabbit connection has no channel left for exchange ${config.exchange}")
        channel.declareLocalExchange(config.exchange)
        // Confirm mode is per channel and cannot be undone, it is set once when the channel is opened.
        channel.confirmSelect()
        return channel
    }

    private fun connection(): Connection {
        return connection?.takeIf { it.isOpen } ?: config.connectionFactory.newConnection().also { connection = it }
    }

    private fun discardChannel() = synchronized(lifecycleLock) {
        channel?.let { runCatching { it.close() } }
        channel = null
    }

    private fun Channel.declareLocalExchange(exchange: String) {
        exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
    }
}
