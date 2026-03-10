package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.Publisher
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
import kotlinx.serialization.json.Json

class RabbitPublisher(
    private val config: Config,
    private val json: Json
) : Publisher {
    private val channel: Channel = config.connectionFactory.newConnection().createChannel()

    init {
        channel.declareLocalExchange(config.exchange)
    }

    override suspend fun publish(command: DeferredCommand) {
        channel.basicPublish(
            config.exchange,
            command.key.routeName(),
            null,
            json.encodeToString(DeferredCommandSerializer, command).toByteArray()
        )
    }

    private fun Channel.declareLocalExchange(exchange: String) {
        exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
    }
}
