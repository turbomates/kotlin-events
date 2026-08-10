package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.Telemetry
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class RabbitQueue(
    private val config: Config,
    private val json: Json,
    private val subscribersRegistry: SubscribersRegistry,
    private val scope: CoroutineScope,
    private val telemetryService: Telemetry,
    private val queueType: QueueType? = null,
    private val metrics: ConsumerMetrics = NoOpConsumerMetrics,
    private val errorHandler: (Throwable) -> Unit = {}
) {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }
    private val channels = CopyOnWriteArrayList<Channel>()
    private val connections = (1..config.connectionsCount).map { config.connectionFactory.newConnection() }
    // A child of the caller's scope shared by every consumer's workers: cancelling
    // the caller's scope stops them, and close() cancels just this scope.
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    fun run(queuesConfig: List<QueueConfig> = emptyList()) {
        val (eventsSubscribers, eventSubscribers) = subscribersRegistry.subscribers()
        eventsSubscribers.forEach { eventsSubscriber ->
            eventsSubscriber.consumers(queuesConfig)

        }
        eventSubscribers.forEach { eventSubscriber ->
            eventSubscriber.consumers(queuesConfig)
        }
    }

    private fun channel(queueConfig: QueueConfig): Channel {
        val channel = connections.random().createChannel()
        channels.add(channel)
        // The queues below bind to this exchange, so it has to exist before them. A consumer only
        // application has no publisher to declare it, and the publisher of an application that has
        // one declares it when it publishes, which may be long after the queues are bound.
        channel.exchangeDeclare(config.exchange, BuiltinExchangeType.TOPIC, true)
        return channel.apply { queueConfig.dlxQueue() }
    }

    private fun EventSubscriber<out Event>.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(
            queueName(
                config.queuePrefix
            ),
            prefetchCount = config.defaultPreFetch,
            maxRetries = config.defaultMaxRetries,
            retryDelay = config.defaultRetryDelay,
            queueType = queueType,
            maxConcurrency = config.defaultMaxConcurrency,
        )
        consume(queueConfig, mapOf(key to this)) { channel ->
            channel.queueBind(queueConfig.queueName, config.exchange, key.routeName())
        }
    }

    private fun EventsSubscriber.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(
            queueName(
                config.queuePrefix
            ),
            prefetchCount = config.defaultPreFetch,
            maxRetries = config.defaultMaxRetries,
            retryDelay = config.defaultRetryDelay,
            queueType = queueType,
            maxConcurrency = config.defaultMaxConcurrency,
        )
        consume(queueConfig, subscribers().associateBy { it.key }) { channel ->
            subscribers().forEach { subscriber ->
                channel.queueBind(queueConfig.queueName, config.exchange, subscriber.key.routeName())
            }
        }
    }

    /**
     * Everything a consumer is made of, in one place so it can be done again from scratch: a fresh
     * channel with the queues declared, the bindings of the caller, and the subscription itself.
     * The cancel callback re-enters through [recreate] when the broker cancels the consumer.
     */
    private fun consume(
        queueConfig: QueueConfig,
        subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
        bind: (Channel) -> Unit
    ) {
        queueConfig.validateConcurrency()
        val channel = channel(queueConfig)
        bind(channel)
        channel.basicQos(queueConfig.prefetchCount)
        val deliveries = ListenerDeliveryCallback(
            ChannelInfo(queueConfig.queueName, config.exchange, channel),
            queueConfig,
            subscribers,
            json,
            telemetryService,
            workerScope,
            metrics,
            errorHandler
        )
        channel.basicConsume(
            queueConfig.queueName,
            false,
            deliveries,
            ListenerCancelCallback(queueConfig.queueName) {
                recreate(channel, deliveries, queueConfig, subscribers, bind)
            }
        )
    }

    /**
     * The broker cancelled the consumer behind our back — the queue was deleted or lost its node.
     * The channel is still open but idle, so without this the queue is never read again. The old
     * workers drain what they already buffered, then the whole consumer is built anew, retrying
     * until the broker accepts it or the scope shuts down.
     */
    private fun recreate(
        cancelled: Channel,
        deliveries: ListenerDeliveryCallback,
        queueConfig: QueueConfig,
        subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
        bind: (Channel) -> Unit
    ) {
        workerScope.launch {
            deliveries.stop()
            channels.remove(cancelled)
            runCatching { cancelled.close() }
            while (isActive) {
                try {
                    consume(queueConfig, subscribers, bind)
                    logger.info("Consumer of ${queueConfig.queueName} was recreated")
                    return@launch
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (expected: Throwable) {
                    logger.error(
                        "Failed to recreate consumer of ${queueConfig.queueName}, next attempt in $recreateDelay",
                        expected
                    )
                    delay(recreateDelay)
                }
            }
        }
    }

    context(channel: Channel)
    private fun QueueConfig.dlxQueue() {
        val queueTypeArguments = mapOf("x-queue-type" to resolvedQueueType().value)
        if (!isRetryEnabled()) {
            channel.queueDeclare(queueName, true, false, false, queueTypeArguments)
            return
        }
        val exchange = this@RabbitQueue.config.exchange.dlx()
        channel.exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, true)
        channel.queueDeclare(
            queueName, true, false, false, queueTypeArguments + mapOf(
                "x-dead-letter-exchange" to exchange,
                "x-dead-letter-routing-key" to queueName.dlx(),
            )
        )
        channel.queueBind(queueName, config.exchange.dlx(), queueName)

        channel.queueDeclare(
            queueName.dlx(), true, false, false, queueTypeArguments + mapOf(
                "x-dead-letter-exchange" to exchange,
                "x-dead-letter-routing-key" to queueName,
                "x-message-ttl" to retryDelay.inWholeMilliseconds
            )
        )
        channel.queueBind(queueName.dlx(), config.exchange.dlx(), queueName.dlx())

        channel.queueDeclare(queueName.pl(), true, false, false, queueTypeArguments)
        channel.queueBind(queueName.pl(), config.exchange, queueName.pl())
    }

    private fun QueueConfig.resolvedQueueType(): QueueType {
        return queueType ?: config.defaultQueueType
    }

    private fun QueueConfig.validateConcurrency() {
        require(maxConcurrency >= 1) {
            "maxConcurrency must be >= 1 (was $maxConcurrency) for queue '$queueName'"
        }
        // prefetchCount == 0 means unlimited prefetch in RabbitMQ.
        require(prefetchCount == 0 || prefetchCount >= maxConcurrency) {
            "prefetchCount ($prefetchCount) must be 0 (unlimited) or >= maxConcurrency " +
                "($maxConcurrency) for queue '$queueName'"
        }
    }

    fun close() {
        workerScope.cancel()
        // Closing a connection also closes its channels, so close channels first and
        // ignore "already closed" errors to keep shutdown best-effort.
        channels.forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
    }

    data class ChannelInfo(val queue: String, val exchange: String, val channel: Channel)
    companion object {
        const val DLX_POSTFIX = "_dlx"
        const val PARKING_LOT_POSTFIX = "_pl"
        private val recreateDelay = 5.seconds
    }
}

internal fun String.dlx(): String = this + RabbitQueue.DLX_POSTFIX
internal fun String.pl(): String = this + RabbitQueue.PARKING_LOT_POSTFIX
