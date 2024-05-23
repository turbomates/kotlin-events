package com.turbomates.event.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.turbomates.event.Event
import com.turbomates.event.EventSubscriber
import com.turbomates.event.seriazlier.EventSerializer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal class ListenerDeliveryCallback(
    private val channelInfo: RabbitQueue.ChannelInfo,
    private val config: QueueConfig,
    private val subscribers: Map<Event.Key<out Event>, EventSubscriber<out Event>>,
    private val json: Json,
) : DeliverCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }

    @Suppress("UNCHECKED_CAST")
    override fun handle(consumerTag: String, message: Delivery) {
        runBlocking {
            val eventJsonString = String(message.body)
            try {
                logger.info("Event $eventJsonString accepted ")
                val event = json.decodeFromString(EventSerializer, eventJsonString)

                val callback = subscribers[event.key] as? EventSubscriber<Event>
                callback?.invoke(event)
                channelInfo.channel.basicAck(message.envelope.deliveryTag, false)
            } catch (expected: Throwable) {
                logger.error("Broken event: $eventJsonString. Message: ${expected.message}", expected)
                with(channelInfo) {
                    if (config.isRetryEnabled()) {
                        if (message.properties.retryCount() >= config.maxRetries) {
                            logger.error(
                                "Couldn't process message after ${config.maxRetries} retries: $eventJsonString",
                                expected
                            )
                            channel.basicPublish(
                                exchange,
                                config.queueName.pl(),
                                message.properties,
                                message.body
                            )
                            channel.basicAck(message.envelope.deliveryTag, false)
                        } else {
                            channel.basicReject(message.envelope.deliveryTag, false)
                        }
                    } else {
                        channel.basicNack(message.envelope.deliveryTag, false, true)
                    }
                }

            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun AMQP.BasicProperties.retryCount(): Long {
        if (headers == null) {
            return 0
        }
        val retries = headers["x-death"] as? List<*>
        return retries?.let { (it.firstOrNull() as Map<String, Any?>)["count"] as? Long } ?: 0L
    }
}


internal class ListenerCancelCallback : CancelCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }
    override fun handle(consumerTag: String?) {
        logger.error("Listener was cancelled $consumerTag")
    }
}
