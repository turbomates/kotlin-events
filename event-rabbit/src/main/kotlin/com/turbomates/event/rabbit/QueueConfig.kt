package com.turbomates.event.rabbit

import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class QueueConfig(
    val queueName: String,
    var maxRetries: Int = 0,
    var prefetchCount: Int = 100,
    var retryDelay: Duration = 1.minutes,
) {
    fun isRetryEnabled(): Boolean = maxRetries > 0

    companion object {
        const val TRACEPARENT_HEADER = "traceparent"
        const val TRACESTATE_HEADER = "tracestate"
        const val BAGGAGE_HEADER = "baggage"
        operator fun invoke(name: String, block: QueueConfig.() -> Unit): QueueConfig {
            val config = QueueConfig(name)
            return config.also { it.block() }
        }
    }
}

internal fun EventsSubscriber.config(prefix: String, block: QueueConfig.() -> Unit): QueueConfig {
    val config = QueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}

@JvmName("eventSubscriberConfig")
internal fun EventSubscriber<*>.config(prefix: String, block: QueueConfig.() -> Unit): QueueConfig {
    val config = QueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}
