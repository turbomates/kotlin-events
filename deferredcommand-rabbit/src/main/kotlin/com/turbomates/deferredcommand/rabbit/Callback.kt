package com.turbomates.deferredcommand.rabbit

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.DeliverCallback
import com.rabbitmq.client.Delivery
import com.turbomates.deferredcommand.DeferredCommand
import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.serializer.DeferredCommandSerializer
import com.turbomates.event.Telemetry
import com.turbomates.event.TraceInformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal class ListenerDeliveryCallback(
    private val channelInfo: RabbitQueue.ChannelInfo,
    private val config: QueueConfig,
    private val subscribers: Map<DeferredCommand.Key<out DeferredCommand>, DeferredCommandSubscriber<out DeferredCommand>>,
    private val json: Json,
    private val telemetryService: Telemetry,
    private val scope: CoroutineScope
) : DeliverCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }

    @Suppress("UNCHECKED_CAST")
    override fun handle(consumerTag: String, message: Delivery) {
        scope.launch {
            val carrier = message.properties.headers ?: emptyMap()
            val traceInformation = TraceInformation(
                carrier.stringHeader(QueueConfig.TRACEPARENT_HEADER),
                carrier.stringHeader(QueueConfig.TRACESTATE_HEADER),
                carrier.stringHeader(QueueConfig.BAGGAGE_HEADER),
            )
            val attributes = mapOf(
                "messaging.rabbitmq.delivery_tag" to message.envelope.deliveryTag.toString(),
                "messaging.retry_count" to message.properties.retryCount().toString(),
                "messaging.rabbitmq.routing_key" to message.envelope.routingKey,
                "messaging.rabbitmq.exchange" to channelInfo.exchange,
                "messaging.rabbitmq.queue" to config.queueName,
                "messaging.broker" to "rabbitmq",
            )
            telemetryService.link(traceInformation, "deferredcommand.rabbit.worker", attributes) {
                val commandJsonString = String(message.body)
                try {
                    logger.info("Deferred command $commandJsonString accepted")
                    val command = json.decodeFromString(DeferredCommandSerializer, commandJsonString)
                    val callback = subscribers[command.key] as? DeferredCommandSubscriber<DeferredCommand>
                    callback?.invoke(command)
                    channelInfo.channel.basicAck(message.envelope.deliveryTag, false)
                } catch (expected: Throwable) {
                    logger.error("Broken deferred command: $commandJsonString. Message: ${expected.message}", expected)
                    with(channelInfo) {
                        if (config.isRetryEnabled()) {
                            if (message.properties.retryCount() >= config.maxRetries) {
                                logger.error(
                                    "Couldn't process message after ${config.maxRetries} retries: $commandJsonString",
                                    expected
                                )
                                channel.basicPublish(
                                    exchange,
                                    config.queueName.pl(),
                                    message.properties.withExceptionInfo(expected),
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
    }

    private fun AMQP.BasicProperties.withExceptionInfo(exception: Throwable): AMQP.BasicProperties {
        val exceptionMessage = (exception.cause?.message ?: exception.message)?.truncateTo(maxHeaderSize)
        val stackTrace = exception.stackTraceToString()

        headers[EXCEPTION_HEADER] = exceptionMessage
        headers[EXCEPTION_STACKTRACE_HEADER] = stackTrace.truncateTo(maxHeaderSize - (exceptionMessage?.length ?: 0))
        return this
    }

    private fun String.truncateTo(maxLength: Int): String =
        if (length <= maxLength) {
            this
        } else {
            this.substring(0, maxLength)
        }

    private val maxHeaderSize by lazy {
        val maxFrameSize = channelInfo.channel.connection.frameMax
        if (maxFrameSize == 0) {
            MAX_EXCEPTION_HEADER_SIZE
        } else {
            maxFrameSize - MIN_HEADER_FRAME_SIZE
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

    private fun Map<String, Any?>.stringHeader(name: String): String? {
        return when (val value = this[name]) {
            null -> null
            is String -> value
            is ByteArray -> String(value)
            else -> value.toString()
        }
    }

    private companion object {
        const val EXCEPTION_HEADER = "x-exception-message"
        const val EXCEPTION_STACKTRACE_HEADER = "x-exception-stacktrace"
        const val MAX_EXCEPTION_HEADER_SIZE = 4096
        const val MIN_HEADER_FRAME_SIZE = 20_000
    }
}

internal class ListenerCancelCallback : CancelCallback {
    private val logger by lazy { LoggerFactory.getLogger(javaClass) }

    override fun handle(consumerTag: String?) {
        logger.error("Listener was cancelled $consumerTag")
        throw InterruptedException("Listener was cancelled $consumerTag")
    }
}
