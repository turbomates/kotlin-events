package com.turbomates.event.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import com.turbomates.event.seriazlier.EventSerializer
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal class ListenerDeliveryCallback(
    private val channelInfo: RabbitQueue.ChannelInfo,
    private val config: QueueConfig,
    private val subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
    private val json: Json,
    private val telemetryService: Telemetry,
    private val scope: CoroutineScope,
    private val metrics: ConsumerMetrics = NoOpConsumerMetrics,
    private val errorHandler: (Throwable) -> Unit = {}
) : DeliverCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }

    // Unbounded from the channel's side, but the broker never delivers more than
    // prefetchCount unacked messages, so at most prefetchCount deliveries ever sit
    // in the buffer waiting for a free worker. trySend therefore never rejects.
    private val deliveries = Channel<Buffered>(Channel.UNLIMITED)

    // One worker per unit of concurrency: a subscriber processes at most
    // maxConcurrency messages at once, and with maxConcurrency == 1 a single
    // worker draining the channel preserves delivery order (FIFO).
    private val workers = List(config.maxConcurrency) {
        scope.launch {
            for (delivery in deliveries) {
                val waited = delivery.enqueuedAt.elapsedNow()
                try {
                    process(delivery.message, waited)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Throwable) {
                    logger.error("Failed to process delivery from ${config.queueName}", expected)
                    errorHandler(expected)
                }
            }
        }
    }

    override fun handle(consumerTag: String, message: Delivery) {
        // Never suspends, never rejects: the delivery is buffered and waits in the
        // channel until one of the maxConcurrency workers is free to process it.
        // The mark it carries is what ConsumerMetrics reports as `waited`.
        deliveries.trySend(Buffered(message, TimeSource.Monotonic.markNow()))
    }

    /**
     * The consumer is gone and no new deliveries arrive: the workers exit once they drain what is
     * already buffered. Those deliveries are still unacked on the channel, so they settle as usual
     * for as long as it stays open.
     */
    fun close() {
        deliveries.close()
    }

    /** Waits for the workers to finish the buffer [close] left them. */
    suspend fun awaitDrain() {
        workers.joinAll()
    }

    /**
     * Gives up on the drain and stops the workers where they are, for a subscriber that hangs long
     * enough to hold up everything waiting behind it. Best effort: a subscriber that blocks its
     * thread instead of suspending only stops when it returns. Whatever was in flight settles
     * nowhere and comes back with the redelivery.
     */
    fun cancel() {
        close()
        workers.forEach { it.cancel() }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun process(message: Delivery, waited: Duration) {
        val carrier = message.properties.headers ?: emptyMap()
        val traceInformation = TraceInformation(
            carrier[QueueConfig.TRACEPARENT_HEADER] as? String,
            carrier[QueueConfig.TRACESTATE_HEADER] as? String,
            carrier[QueueConfig.BAGGAGE_HEADER] as? String,
        )
        val attributes = mapOf<String, String>(
            "messaging.rabbitmq.delivery_tag" to message.envelope.deliveryTag.toString(),
            "messaging.retry_count" to message.properties.retryCount().toString(),
            "messaging.rabbitmq.routing_key" to message.envelope.routingKey,
            "messaging.rabbitmq.exchange" to channelInfo.exchange,
            "messaging.rabbitmq.queue" to config.queueName,
            "messaging.broker" to "rabbitmq",
        )
        val queue = config.queueName
        val routingKey = message.envelope.routingKey
        val retries = message.properties.retryCount()
        telemetryService.link(traceInformation, "kotlin.event.rabbit.worker", attributes) {
            val eventJsonString = String(message.body)
            val startedAt = TimeSource.Monotonic.markNow()
            try {
                logger.info("Event $eventJsonString accepted ")
                val event = json.decodeFromString(EventSerializer, eventJsonString)
                val callback = subscribers[event.key] as? EventSubscriber<Event>
                if (callback == null) {
                    // The queue is bound to a key this consumer has no subscriber for: the delivery
                    // is acked and gone, and the metric is the only trace it leaves.
                    report { metrics.noSubscriber(queue, routingKey) }
                } else {
                    callback.invoke(event)
                    report { metrics.handled(queue, routingKey, waited, startedAt.elapsedNow()) }
                }
                channelInfo.channel.basicAck(message.envelope.deliveryTag, false)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (expected: Throwable) {
                logger.error("Broken event: $eventJsonString. Message: ${expected.message}", expected)
                report { metrics.failed(queue, routingKey, retries, startedAt.elapsedNow(), expected) }
                with(channelInfo) {
                    if (config.isRetryEnabled()) {
                        if (retries >= config.maxRetries) {
                            logger.error(
                                "Couldn't process message after ${config.maxRetries} retries: $eventJsonString",
                                expected
                            )
                            channel.basicPublish(
                                exchange,
                                config.queueName.pl(),
                                message.properties.withExceptionInfo(expected),
                                message.body
                            )
                            channel.basicAck(message.envelope.deliveryTag, false)
                            report { metrics.parked(queue, routingKey, retries) }
                        } else {
                            channel.basicReject(message.envelope.deliveryTag, false)
                            report { metrics.retried(queue, routingKey, retries) }
                        }
                    } else {
                        // There are no retry queues to pace the redelivery: a bare nack puts the
                        // message back at the head of the queue and it comes back immediately — a
                        // hot loop for as long as it keeps failing. The worker holds the delivery
                        // unacked for retryDelay instead: only this stream waits, its order stays.
                        delay(config.retryDelay)
                        channel.basicNack(message.envelope.deliveryTag, false, true)
                        report { metrics.requeued(queue, routingKey) }
                    }
                }
                errorHandler(expected)
            }
        }
    }

    /**
     * [ConsumerMetrics] is documented as never throwing, but a broken implementation of it must not
     * be able to decide the fate of a delivery. A throw between the subscriber and the ack would
     * redeliver a message that was already processed, and a throw on the failure path would leave
     * the delivery unacked — holding its prefetch slot until the channel is closed — because it
     * escapes before the reject.
     */
    private inline fun report(metric: () -> Unit) {
        try {
            metric()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (broken: Throwable) {
            logger.error("ConsumerMetrics of ${config.queueName} threw", broken)
        }
    }

    private class Buffered(val message: Delivery, val enqueuedAt: TimeMark)

    private fun AMQP.BasicProperties.withExceptionInfo(exception: Throwable): AMQP.BasicProperties {
        val exceptionMessage = (exception.cause?.message ?: exception.message)?.truncateTo(maxHeaderSize)
        val stackTrace = exception.stackTraceToString()

        headers[EXCEPTION_HEADER] = exceptionMessage
        headers[EXCEPTION_STACKTRACE_HEADER] = stackTrace.truncateTo(maxHeaderSize - (exceptionMessage?.length ?: 0))
        return this
    }

    private fun String.truncateTo(maxLength: Int): String =
        if (length <= maxLength) {
            this
        } else {
            this.substring(0, maxLength)
        }

    private val maxHeaderSize by lazy {
        val maxFrameSize = channelInfo.channel.connection.frameMax
        if (maxFrameSize == 0) {
            MAX_EXCEPTION_HEADER_SIZE
        } else {
            maxFrameSize - MIN_HEADER_FRAME_SIZE
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun AMQP.BasicProperties.retryCount(): Long {
        if (headers == null) {
            return 0
        }
        val retries = headers["x-death"] as? List<*>
        return retries?.let { (it.firstOrNull() as Map<String, Any?>)["count"] as? Long } ?: 0L
    }

    private companion object {
        const val EXCEPTION_HEADER = "x-exception-message"
        const val EXCEPTION_STACKTRACE_HEADER = "x-exception-stacktrace"
        const val MAX_EXCEPTION_HEADER_SIZE = 4096
        const val MIN_HEADER_FRAME_SIZE = 20_000
    }
}


/**
 * The broker cancelled the consumer without being asked to — its queue was deleted or lost its
 * node. Runs on a thread of the amqp client, so it must not throw and must not block: it only
 * reports and hands recreation over to [onCancelled], otherwise the queue silently stops being
 * read for the rest of the process's life.
 */
internal class ListenerCancelCallback(
    private val queue: String,
    private val onCancelled: () -> Unit
) : CancelCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }
    override fun handle(consumerTag: String?) {
        logger.error("Consumer $consumerTag of $queue was cancelled by the broker")
        try {
            onCancelled()
        } catch (expected: Throwable) {
            logger.error("Failed to start recreation of the consumer of $queue", expected)
        }
    }
}
