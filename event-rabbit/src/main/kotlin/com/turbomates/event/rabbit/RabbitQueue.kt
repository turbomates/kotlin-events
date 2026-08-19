package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.EventRegistry
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.Telemetry
import com.turbomates.event.hasDeclaredName
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
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

class RabbitQueue(
    private val config: Config,
    /**
     * The events of the application and how they are read: the name a delivery carries resolved back
     * to an event, and the `Json` its payload is decoded with. Every subscriber started here
     * registers its own key, so a consumer only application passes a fresh registry; an application
     * that also publishes or reads an outbox passes the one the rest of it was built with.
     */
    private val events: EventRegistry,
    private val subscribersRegistry: SubscribersRegistry,
    private val scope: CoroutineScope,
    private val telemetryService: Telemetry,
    private val queueType: QueueType? = null,
    private val metrics: ConsumerMetrics = NoOpConsumerMetrics,
    private val errorHandler: (Throwable) -> Unit = {},
    private val boundRoutes: BoundRoutes? = null
) {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }
    private val channels = CopyOnWriteArrayList<Channel>()
    private val connections = (1..config.connectionsCount).map { config.connectionFactory.newConnection() }
    // A child of the caller's scope shared by every consumer's workers: cancelling
    // the caller's scope stops them, and close() cancels just this scope.
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    fun run(queuesConfig: List<QueueConfig> = emptyList()) {
        val (eventsSubscribers, eventSubscribers) = subscribersRegistry.subscribers()
        register(eventsSubscribers.flatMap { it.subscribers() } + eventSubscribers)
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

    /**
     * Everything this application consumes it also has to decode, and the keys of its subscribers are
     * the whole list of it — so the consuming side of the registry needs no help from the application.
     * The events it publishes are the ones to register by hand, nothing here enumerates those.
     */
    private fun register(subscribers: List<EventSubscriber<out Event>>) {
        val derived = subscribers.map { it.key }.filterNot { events.register(it) }
        if (derived.isEmpty()) return
        logger.warn(
            "${derived.size} subscribed events have no declared name and are routed and stored under " +
                "one derived from their class, which changes when the class is renamed or moved: " +
                "${derived.joinToString { it.routeName() }}. Override Event.Key.name on them."
        )
    }

    private fun channel(queueConfig: QueueConfig): Channel {
        val channel = connections.random().createChannel()
        try {
            // The queues below bind to this exchange, so it has to exist before them. A consumer only
            // application has no publisher to declare it, and the publisher of an application that has
            // one declares it when it publishes, which may be long after the queues are bound.
            channel.exchangeDeclare(config.exchange, BuiltinExchangeType.TOPIC, true)
            channel.apply { queueConfig.dlxQueue() }
        } catch (expected: Throwable) {
            runCatching { channel.close() }
            throw expected
        }
        channels.add(channel)
        return channel
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
        val routes = key.routes()
        consume(queueConfig, mapOf(key to this)) { channel ->
            routes.forEach { route ->
                channel.queueBind(queueConfig.queueName, config.exchange, route)
            }
        }
        return BoundQueue(queueConfig.queueName, routes)
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
        // Captured once: an implementation is free to build a fresh list per call, and the routes —
        // both the ones bound here, re-derived by every recreation, and the ones syncBindings
        // measures the queue against — must not diverge from the map consuming them.
        val subscribers = subscribers()
        val routes = subscribers.flatMap { it.key.routes() }.toSet()
        consume(queueConfig, subscribers.associateBy { it.key }) { channel ->
            routes.forEach { route ->
                channel.queueBind(queueConfig.queueName, config.exchange, route)
            }
        }
        return BoundQueue(queueConfig.queueName, routes)
    }

    /**
     * The routes a queue is bound to for this key: the one its events are published under, plus the
     * one they used to be published under before the name was declared, see [Config.bindLegacyRoutes].
     * Both belong to the set [syncBindings] measures against, or the legacy route would be unbound
     * the moment it is bound.
     */
    @Suppress("DEPRECATION")
    private fun Event.Key<*>.routes(): Set<String> {
        val legacy = if (config.bindLegacyRoutes && hasDeclaredName()) legacyRouteName() else null
        return setOfNotNull(routeName(), legacy)
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
        var deliveries: ListenerDeliveryCallback? = null
        try {
            bind(channel)
            channel.basicQos(queueConfig.prefetchCount)
            val callback = ListenerDeliveryCallback(
                ChannelInfo(queueConfig.queueName, config.exchange, channel),
                queueConfig,
                subscribers,
                events,
                telemetryService,
                workerScope,
                metrics,
                errorHandler
            )
            deliveries = callback
            channel.basicConsume(
                queueConfig.queueName,
                false,
                callback,
                ListenerCancelCallback(queueConfig.queueName) {
                    recreate(channel, callback, queueConfig, subscribers, bind)
                },
                ListenerShutdownCallback(
                    queueConfig.queueName,
                    config.connectionFactory.isAutomaticRecoveryEnabled
                ) {
                    recreate(channel, callback, queueConfig, subscribers, bind)
                }
            )
        } catch (expected: Throwable) {
            // A half-built consumer must not leak: recreate() retries this whole function every
            // few seconds and would pile up open channels and idle workers otherwise.
            deliveries?.close()
            channels.remove(channel)
            runCatching { channel.close() }
            throw expected
        }
    }

    /**
     * The consumer ended without being asked to — cancelled by the broker because its queue was
     * deleted or lost its node, or taken down with the channel under it. Either way nothing reads
     * the queue any more, and neither the channel nor the client brings it back. The old workers
     * drain what they already buffered, then the whole consumer is built anew, retrying until the
     * broker accepts it or the scope shuts down.
     *
     * Every attempt is paced by [recreateDelay], the successful one included: a consumer the broker
     * accepts and cancels right back — a queue that keeps expiring, one being deleted in a loop —
     * would otherwise churn a channel and its declarations as fast as the broker answers.
     */
    private fun recreate(
        cancelled: Channel,
        deliveries: ListenerDeliveryCallback,
        queueConfig: QueueConfig,
        subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
        bind: (Channel) -> Unit
    ) {
        // The end of one consumer can be reported both as a cancel and as a shutdown of its
        // channel; it is rebuilt once.
        if (!deliveries.claimRecreation()) {
            return
        }
        workerScope.launch {
            deliveries.close()
            // The drain is bounded: a subscriber hung on a buffered delivery must not hold the
            // recreation hostage — that is the very "queue is never read again" this function
            // exists to prevent. Its workers are dropped instead of left behind, or a queue that
            // is cancelled over and over accumulates a set of them per recreation.
            if (withTimeoutOrNull(drainTimeout) { deliveries.awaitDrain() } == null) {
                logger.error("Workers of ${queueConfig.queueName} outlived $drainTimeout, cancelling them")
                deliveries.cancel()
            }
            channels.remove(cancelled)
            runCatching { cancelled.close() }
            while (isActive) {
                delay(recreateDelay)
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
                }
            }
        }
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
        private val recreateDelay = 5.seconds
        private val drainTimeout = 30.seconds
    }
}

internal fun String.dlx(): String = this + RabbitQueue.DLX_POSTFIX
internal fun String.pl(): String = this + RabbitQueue.PARKING_LOT_POSTFIX
