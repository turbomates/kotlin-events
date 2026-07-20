package com.turbomates.deferredcommand.rabbit

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
    val defaultQueueType: QueueType = QueueType.CLASSIC,
    /**
     * Default upper bound on the number of messages a single subscriber
     * processes concurrently, applied to every consumer that does not override
     * [QueueConfig.maxConcurrency].
     *
     * Each consumer drains its deliveries through this many worker coroutines,
     * so total load is bounded by `maxConcurrency * subscribers` — keeping a
     * downstream resource such as a database connection pool from being
     * exhausted — and no subscriber can monopolise it via a burst.
     */
    val defaultMaxConcurrency: Int = 1,
)
