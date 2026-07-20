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
    val defaultQueueType: QueueType = QueueType.CLASSIC,
    /**
     * Default upper bound on the number of messages a single subscriber
     * processes concurrently. Like the other `default*` values, it is only used
     * to build the [QueueConfig] auto-created for a subscriber that has no
     * explicit entry in the list passed to [RabbitQueue.run]; a [QueueConfig]
     * supplied there carries its own [QueueConfig.maxConcurrency].
     *
     * Every delivered message is dispatched to its own coroutine, so without a
     * limit the effective concurrency per subscriber equals its prefetch, and
     * across all subscribers it is `prefetch * subscribers` — enough to exhaust
     * a downstream resource such as a database connection pool. A per-subscriber
     * limit keeps distribution fair (no subscriber can monopolise the pool) and
     * bounds total load at `maxConcurrency * subscribers`. Keep
     * [defaultPreFetch] greater than or equal to this value so the broker can
     * buffer enough messages to hide delivery latency.
     */
    val defaultMaxConcurrency: Int = 1,
)
