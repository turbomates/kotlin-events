package com.turbomates.event.rabbit

import com.turbomates.event.EventSubscriber
import com.turbomates.event.EventsSubscriber
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class RabbitQueueConfig(
    val queueName: String,
    var maxRetries: Int = 0,
    var connectionsCount: Int = 1,
    var retryDelay: Duration = 1.minutes,
) {
    companion object {
        operator fun invoke(name: String, block: RabbitQueueConfig.() -> Unit): RabbitQueueConfig {
            val config = RabbitQueueConfig(name)
            return config.also { it.block() }
        }
    }
}

internal fun KClass<out EventsSubscriber>.config(prefix: String, block: RabbitQueueConfig.() -> Unit): RabbitQueueConfig {
    val config = RabbitQueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}

@JvmName("eventSubscriberConfig")
internal fun KClass<out EventSubscriber<*>>.config(prefix: String, block: RabbitQueueConfig.() -> Unit): RabbitQueueConfig {
    val config = RabbitQueueConfig(this.queueName(prefix))
    return config.also { it.block() }
}
