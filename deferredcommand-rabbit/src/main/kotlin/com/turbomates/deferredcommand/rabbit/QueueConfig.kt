package com.turbomates.deferredcommand.rabbit

import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class QueueConfig(
    val queueName: String,
    var maxRetries: Int = 0,
    var prefetchCount: Int = 100,
    var retryDelay: Duration = 1.minutes,
    var queueType: QueueType? = null,
    /**
     * Maximum number of messages this subscriber processes concurrently. Each
     * consumer gets its own pool of workers, so distribution stays fair across
     * subscribers and total load is bounded by `maxConcurrency * subscribers`.
     * Keep [prefetchCount] greater than or equal to this value.
     *
     * Affects ordering: a value of 1 processes deliveries strictly in order
     * (FIFO), one at a time. Any higher value processes them in parallel and no
     * longer preserves delivery order — do not raise it for subscribers that
     * require ordered processing.
     */
    var maxConcurrency: Int = 1,
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

internal fun DeferredCommandsSubscriber.config(prefix: String, block: QueueConfig.() -> Unit): QueueConfig {
    val config = QueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}

@JvmName("deferredCommandSubscriberConfig")
internal fun DeferredCommandSubscriber<*>.config(prefix: String, block: QueueConfig.() -> Unit): QueueConfig {
    val config = QueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}
