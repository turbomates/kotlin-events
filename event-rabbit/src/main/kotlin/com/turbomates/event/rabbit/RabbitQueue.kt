package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val errorHandler: (Throwable) -> Unit = {},
    private val boundRoutes: BoundRoutes? = null
) {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }
    private val channels = mutableListOf<Channel>()
    private val connections = (1..config.connectionsCount).map { config.connectionFactory.newConnection() }
    // A child of the caller's scope shared by every consumer's workers: cancelling
    // the caller's scope stops them, and close() cancels just this scope.
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    val consumer: Channel.(QueueConfig, Map<Event.Key<out Event>, EventSubscriber<out Event>>) -> Unit =
        { config, subscribers ->
            config.validateConcurrency()
            basicQos(config.prefetchCount)
            basicConsume(
                config.queueName,
                false,
                ListenerDeliveryCallback(
                    ChannelInfo(config.queueName, this@RabbitQueue.config.exchange, this),
                    config,
                    subscribers,
                    json,
                    telemetryService,
                    workerScope,
                    metrics,
                    errorHandler
                ),
                ListenerCancelCallback()
            )
        }

    fun run(queuesConfig: List<QueueConfig> = emptyList()) {
        val (eventsSubscribers, eventSubscribers) = subscribersRegistry.subscribers()
        val bound = eventsSubscribers.map { eventsSubscriber ->
            eventsSubscriber.consumers(queuesConfig)
        } + eventSubscribers.map { eventSubscriber ->
            eventSubscriber.consumers(queuesConfig)
        }
        // A queue name comes from the name of its subscriber, but two of them may answer the same
        // name, and then the queue is shared and its routes are the union. Syncing a queue once its
        // subscribers are all set up keeps one of the two from unbinding the routes of the other.
        bound.groupBy(BoundQueue::queue, BoundQueue::routes).forEach { (queue, routes) ->
            syncBindings(queue, routes.flatten().toSet())
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

    private fun EventSubscriber<out Event>.consumers(queuesConfig: List<QueueConfig>): BoundQueue {
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
        val channel = channel(queueConfig)
        channel.run { queueConfig.dlxQueue() }
        channel.queueBind(queueConfig.queueName, config.exchange, key.routeName())
        channel.consumer(queueConfig, mapOf(key to this))
        return BoundQueue(queueConfig.queueName, setOf(key.routeName()))
    }

    private fun EventsSubscriber.consumers(queuesConfig: List<QueueConfig>): BoundQueue {
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
        val channel = channel(queueConfig)
        val routes = subscribers().map { it.key.routeName() }.toSet()
        routes.forEach { route ->
            channel.queueBind(queueConfig.queueName, config.exchange, route)
        }
        channel.consumer(queueConfig, subscribers().associateBy { it.key })
        return BoundQueue(queueConfig.queueName, routes)
    }

    // queueBind only ever adds: a subscription dropped since the last start leaves its binding
    // behind, and the queue keeps receiving an event nothing here handles — one whose type this
    // application no longer knows does not even decode and ends up in the parking lot. With
    // [boundRoutes] given, every route bound to the queue from the exchange and not in [routes] —
    // all the routes of the queue, from every subscriber that shares it — is unbound, bindings made
    // by hand included; without it nothing is touched. Best effort: a broker that does not answer is
    // logged and left alone, the consumer starts either way.
    private fun syncBindings(queue: String, routes: Set<String>) {
        if (boundRoutes == null) return
        try {
            val stale = boundRoutes.of(queue, config.exchange) - routes
            if (stale.isEmpty()) return
            // A channel of its own: an unbind that fails closes the channel it runs on, and taking
            // the consumer of the queue down with it would cost more than the stale binding.
            connections.random().createChannel().use { channel ->
                stale.forEach { route ->
                    logger.info("Unbinding stale route $route of $queue from ${config.exchange}")
                    channel.queueUnbind(queue, config.exchange, route)
                }
            }
        } catch (interrupted: InterruptedException) {
            // The HTTP call of a BoundRoutes is a blocking one: hand the interruption back to
            // whoever owns the thread that called run() instead of ending it here.
            Thread.currentThread().interrupt()
            logger.error("Interrupted while syncing the bindings of $queue", interrupted)
        } catch (expected: Exception) {
            logger.error("Failed to sync the bindings of $queue", expected)
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

    // A queue and everything its subscriber bound it to, what [syncBindings] measures against.
    private data class BoundQueue(val queue: String, val routes: Set<String>)

    data class ChannelInfo(val queue: String, val exchange: String, val channel: Channel)
    companion object {
        const val DLX_POSTFIX = "_dlx"
        const val PARKING_LOT_POSTFIX = "_pl"
    }
}

internal fun String.dlx(): String = this + RabbitQueue.DLX_POSTFIX
internal fun String.pl(): String = this + RabbitQueue.PARKING_LOT_POSTFIX
