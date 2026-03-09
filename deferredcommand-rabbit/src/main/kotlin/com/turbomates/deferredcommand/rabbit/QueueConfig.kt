package com.turbomates.deferredcommand.rabbit

import com.turbomates.deferredcommand.DeferredCommandSubscriber
import com.turbomates.deferredcommand.DeferredCommandsSubscriber
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
