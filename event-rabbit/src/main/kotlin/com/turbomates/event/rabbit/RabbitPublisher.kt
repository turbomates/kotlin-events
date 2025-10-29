package com.turbomates.event.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import com.turbomates.event.Event
import com.turbomates.event.Publisher
import com.turbomates.event.TraceInformation
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

    override suspend fun publish(event: Event, traceInformation: TraceInformation?) {
        val propsBuilder = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2) // 2 = persistent

        traceInformation?.let { trace ->
            propsBuilder.headers(
                mapOf(
                    QueueConfig.TRACEPARENT_HEADER to trace.traceparent,
                    QueueConfig.TRACESTATE_HEADER to trace.tracestate,
                    QueueConfig.BAGGAGE_HEADER to trace.toString()
                )
            )
        }
        channel.basicPublish(
            config.exchange,
            event.key.routeName(),
            propsBuilder.build(),
            json.encodeToString(EventSerializer, event).toByteArray()
        )
    }

    private fun Channel.declareLocalExchange(exchange: String) {
        exchangeDeclare(exchange, BuiltinExchangeType.TOPIC, true)
    }
}
