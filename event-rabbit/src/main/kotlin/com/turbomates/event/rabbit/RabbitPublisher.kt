package com.turbomates.event.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.seriazlier.EventSerializer
import kotlinx.serialization.json.Json

class RabbitPublisher(
    private val config: Config,
    private val json: Json
) : Publisher {
    private val channel: Channel = config.connectionFactory.newConnection().createChannel()

    init {
        channel.declareLocalExchange(config.exchange)
    }

    override suspend fun publish(event: Event) {
        channel.basicPublish(
            config.exchange,
            event.key.routeName(),
            null,
            json.encodeToString(EventSerializer, event).toByteArray()
        )
    }

    private fun Channel.declareLocalExchange(exchange: String) {
        exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
    }
}
