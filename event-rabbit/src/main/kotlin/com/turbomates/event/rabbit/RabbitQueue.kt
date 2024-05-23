package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import com.turbomates.event.SubscribersRegistry
import kotlinx.serialization.json.Json

class RabbitQueue(
    private val config: Config,
    private val json: Json,
    private val subscribersRegistry: SubscribersRegistry,
) {
    val consumer: Channel.(QueueConfig, Map<Event.Key<out Event>, EventSubscriber<out Event>>) -> Unit = { config, subscribers ->
        basicConsume(
            config.queueName,
            false,
            ListenerDeliveryCallback(
                ChannelInfo(config.queueName, this@RabbitQueue.config.exchange, this),
                config,
                subscribers,
                json
            ),
            ListenerCancelCallback()
        )
    }

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
        val channel = config.connectionFactory.newConnection().createChannel()
        return channel.apply { queueConfig.dlxQueue() }
    }

    private fun EventSubscriber<out Event>.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(queueName(config.queuePrefix))
        val channel = channel(queueConfig)
        channel.run { queueConfig.dlxQueue() }
        channel.queueBind(queueConfig.queueName, config.exchange, key.routeName())
        channel.consumer(queueConfig, mapOf(key to this))
    }

    private fun EventsSubscriber.consumers(queuesConfig: List<QueueConfig>) {
        val queueConfig = queuesConfig.find { it.queueName == queueName(config.queuePrefix) } ?: QueueConfig(queueName(config.queuePrefix))
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

    data class ChannelInfo(val queue: String, val exchange: String, val channel: Channel)
    companion object {
        const val DLX_POSTFIX = "_dlx"
        const val PARKING_LOT_POSTFIX = "_pl"
    }
}

internal fun String.dlx(): String = this + RabbitQueue.DLX_POSTFIX
internal fun String.pl(): String = this + RabbitQueue.PARKING_LOT_POSTFIX
