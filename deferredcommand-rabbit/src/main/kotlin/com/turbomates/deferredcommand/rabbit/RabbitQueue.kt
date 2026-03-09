package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber
import com.turbomates.deferredcommand.SubscribersRegistry
import kotlinx.serialization.json.Json

class RabbitQueue(
    private val config: Config,
    private val json: Json,
    private val subscribersRegistry: SubscribersRegistry,
) {
    private val channels = mutableListOf<Channel>()
    private val connections = (1..config.connectionsCount).map { config.connectionFactory.newConnection() }
    val consumer: Channel.(QueueConfig, Map<DeferredCommand.Key<out DeferredCommand>, DeferredCommandSubscriber<out DeferredCommand>>) -> Unit =
        { queueConfig, subscribers ->
            basicQos(queueConfig.prefetchCount)
            basicConsume(
                queueConfig.queueName,
                false,
                ListenerDeliveryCallback(
                    ChannelInfo(queueConfig.queueName, this@RabbitQueue.config.exchange, this),
                    queueConfig,
                    subscribers,
                    json
                ),
                ListenerCancelCallback()
            )
        }

    fun run(queuesConfig: List<QueueConfig> = emptyList()) {
        val (deferredCommandsSubscribers, deferredCommandSubscribers) = subscribersRegistry.subscribers()
        deferredCommandsSubscribers.forEach { deferredCommandsSubscriber ->
            deferredCommandsSubscriber.consumers(queuesConfig)
        }
        deferredCommandSubscribers.forEach { deferredCommandSubscriber ->
            deferredCommandSubscriber.consumers(queuesConfig)
        }
    }

    private fun channel(queueConfig: QueueConfig): Channel {
        val channel = connections.random().createChannel()
        channels.add(channel)
        return channel.apply { queueConfig.dlxQueue() }
    }

    private fun DeferredCommandSubscriber<out DeferredCommand>.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(
            queueName(config.queuePrefix),
            prefetchCount = config.defaultPreFetch,
            maxRetries = config.defaultMaxRetries,
            retryDelay = config.defaultRetryDelay,
        )
        val channel = channel(queueConfig)
        channel.run { queueConfig.dlxQueue() }
        channel.queueBind(queueConfig.queueName, config.exchange, key.routeName())
        channel.consumer(queueConfig, mapOf(key to this))
    }

    private fun DeferredCommandsSubscriber.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(
            queueName(config.queuePrefix),
            prefetchCount = config.defaultPreFetch,
            maxRetries = config.defaultMaxRetries,
            retryDelay = config.defaultRetryDelay,
        )
        val channel = channel(queueConfig)
        subscribers().forEach { subscriber ->
            channel.queueBind(queueConfig.queueName, config.exchange, subscriber.key.routeName())
        }
        channel.consumer(queueConfig, subscribers().associateBy { it.key })
    }

    context(Channel)
    private fun QueueConfig.dlxQueue() {
        if (!isRetryEnabled()) {
            queueDeclare(queueName, true, false, false, mapOf())
            return
        }
        val exchange = this@RabbitQueue.config.exchange.dlx()
        exchangeDeclare(exchange, BuiltinExchangeType.DIRECT, true)
        queueDeclare(
            queueName, true, false, false, mapOf(
                "x-dead-letter-exchange" to exchange,
                "x-dead-letter-routing-key" to queueName.dlx(),
            )
        )
        queueBind(queueName, config.exchange.dlx(), queueName)

        queueDeclare(
            queueName.dlx(), true, false, false, mapOf(
                "x-dead-letter-exchange" to exchange,
                "x-dead-letter-routing-key" to queueName,
                "x-message-ttl" to retryDelay.inWholeMilliseconds
            )
        )
        queueBind(queueName.dlx(), config.exchange.dlx(), queueName.dlx())

        queueDeclare(queueName.pl(), true, false, false, mapOf())
        queueBind(queueName.pl(), config.exchange, queueName.pl())
    }

    fun close() {
        connections.forEach { it.close() }
        channels.forEach { it.close() }
    }

    data class ChannelInfo(val queue: String, val exchange: String, val channel: Channel)

    companion object {
        const val DLX_POSTFIX = "_dlx"
        const val PARKING_LOT_POSTFIX = "_pl"
    }
}

internal fun String.dlx(): String = this + RabbitQueue.DLX_POSTFIX
internal fun String.pl(): String = this + RabbitQueue.PARKING_LOT_POSTFIX
