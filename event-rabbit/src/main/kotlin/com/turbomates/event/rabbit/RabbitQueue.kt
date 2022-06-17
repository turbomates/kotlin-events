package com.turbomates.event.rabbit

import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.SubscribersRegistry
import com.turbomates.event.seriazlier.EventSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class RabbitQueue(
    private val config: RabbitConfig,
    private val json: Json,
    private val subscribersRegistry: SubscribersRegistry,
) {
    fun run(queueConfig: List<RabbitQueueConfig> = emptyList()) {
        val (eventsSubscribers, eventSubscribers) = subscribersRegistry.subscribers()
        eventsSubscribers.forEach { eventsSubscriber ->
            val channel = config.connectionFactory.newConnection().createChannel()
            val queueName = eventsSubscriber.queueName(config.queuePrefix)
            channel.queueDeclare(queueName, true, false, false, mapOf())
            eventsSubscriber.subscribers().forEach { subscriber ->
                channel.queueBind(queueName, config.exchange, subscriber.key.routeName())
            }
            channel.basicConsume(
                queueName,
                false,
                ListenerDeliveryCallback(
                    channel,
                    eventsSubscriber.subscribers().associateBy { it.key },
                    json
                ),
                ListenerCancelCallback()
            )
        }
        eventSubscribers.forEach { subscriber ->
            val channel = config.connectionFactory.newConnection().createChannel()
            val queueName = subscriber.queueName(config.queuePrefix)
            channel.queueDeclare(queueName, true, false, false, mapOf())
            channel.queueBind(queueName, config.exchange, subscriber.key.routeName())
            channel.basicConsume(
                queueName,
                false,
                ListenerDeliveryCallback(channel, mapOf(subscriber.key to subscriber), json),
                ListenerCancelCallback()
            )
        }
    }

    private class ListenerDeliveryCallback(
        private val channel: Channel,
        private val subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
        private val json: Json
    ) : DeliverCallback {
        private val logger by lazy { LoggerFactory.getLogger(javaClass) }

        @Suppress("UNCHECKED_CAST")
        override fun handle(consumerTag: String, message: Delivery) = runBlocking {
            try {
                val eventJsonString = String(message.body)
                logger.info("Event $eventJsonString accepted ")
                val event = json.decodeFromString(EventSerializer, eventJsonString)

                val callback = subscribers[event.key] as? EventSubscriber<Event>
                callback?.invoke(event)
                channel.basicAck(message.envelope.deliveryTag, false)
            } catch (logging: Throwable) {
                channel.basicNack(message.envelope.deliveryTag, false, true)
                logger.error("Broken event: ${String(message.body)}. Message: ${logging.message}. Stacktrace: ${logging.printStackTrace()}")
            }
        }
    }

    private class ListenerCancelCallback : CancelCallback {
        private val logger by lazy { LoggerFactory.getLogger(javaClass) }
        override fun handle(consumerTag: String?) {
            logger.error("Listener was cancelled $consumerTag")
        }
    }
}
