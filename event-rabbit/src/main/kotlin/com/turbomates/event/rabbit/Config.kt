package com.turbomates.event.rabbit

import com.rabbitmq.client.ConnectionFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class Config(
    val connectionFactory: ConnectionFactory,
    val exchange: String,
    val queuePrefix: String,
    val connectionsCount: Int = 1,
    val defaultPreFetch: Int = 100,
    val defaultMaxRetries: Int = 3,
    val defaultRetryDelay: Duration = 1.minutes,
)
