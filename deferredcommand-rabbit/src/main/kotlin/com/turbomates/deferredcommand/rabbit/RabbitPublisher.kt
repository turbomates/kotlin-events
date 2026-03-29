package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.Publisher
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
import com.turbomates.event.TraceInformation
import kotlinx.serialization.json.Json

class RabbitPublisher(
    private val config: Config,
    private val json: Json,
    private val buildProperties: AMQP.BasicProperties.Builder.() -> Unit = {}
) : Publisher {
    private val channel: Channel = config.connectionFactory.newConnection().createChannel()

    init {
        channel.declareLocalExchange(config.exchange)
    }

    override suspend fun publish(command: DeferredCommand, traceInformation: TraceInformation?) {
        val propsBuilder = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2)
            .apply(buildProperties)

        traceInformation?.let { trace ->
            propsBuilder.headers(
                mapOf(
                    QueueConfig.TRACEPARENT_HEADER to trace.traceparent,
                    QueueConfig.TRACESTATE_HEADER to trace.tracestate,
                    QueueConfig.BAGGAGE_HEADER to trace.baggage
                )
            )
        }

        channel.basicPublish(
            config.exchange,
            command.key.routeName(),
            propsBuilder.build(),
            json.encodeToString(DeferredCommandSerializer, command).toByteArray()
        )
    }

    private fun Channel.declareLocalExchange(exchange: String) {
        exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
    }
}
